// Seamless-loop analysis engine.
// Port of the PyMusicLooper 3.6.0 analysis pipeline (analysis.py / audio.py),
// which itself drives librosa 0.11 primitives. Every stage below mirrors the
// reference implementation; deviations are marked with [dev] comments.
//
// Pipeline: mono mixdown -> normalize -> trim(top_db=40) -> STFT(2048/512) ->
//   A-weighted power dB -> mel(128, fmax=8k) -> onset envelope (spectral flux)
//   -> chroma(12, tuning-estimated) -> plp pulse + beat_track DP -> beat grid
//   -> beat pair matching (chroma note distance + loudness gate) -> pruning ->
//   forward/backward cosine-similarity scoring -> zero-crossing snap.

#include "loop_engine_internal.h"

#include "fft.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <thread>
#include <cstring>
#include <cstdlib>
#include <limits>
#include <numeric>
#include <vector>

namespace loop_engine {
namespace {

using detail::Cdp;
using detail::rint;

constexpr int    kNfft = 2048;
constexpr int    kHop = 512;
constexpr int    kBins = kNfft / 2 + 1; // 1025
constexpr int    kMels = 128;
constexpr double kMelFmax = 8000.0;
// np.finfo(np.float32).tiny — librosa uses this threshold when the feature
// buffers are float32 (chroma / trim path).
constexpr double kTiny32 = 1.1920928955078125e-07;
// np.finfo(np.float64).tiny — used by the float64 rhythm path (plp/tempogram).
constexpr double kTiny64 = 2.2250738585072014e-308;
// PyMusicLooper calls librosa.beat.beat_track()/librosa.beat.plp() WITHOUT `sr`,
// so librosa's 22050 Hz default governs all tempo/bpm semantics (tempogram
// window, bpm axis, frames-per-beat) regardless of the real sample rate.
// Replicating that quirk is required to land on the same beat grid.
constexpr double kBeatSr = 22050.0;

// v2 §1.2: progress reporting helper. Observers only — never alters numeric
// results. Throttles callbacks to <= 20 Hz; a false return from the user
// callback marks the run cancelled (checked at each hook point).
struct ProgressReporter {
    explicit ProgressReporter(const ProgressFn& fn) : fn_(fn) {}

    // Returns false iff the run is cancelled and the caller should abort.
    bool report(double fraction) {
        if (!static_cast<bool>(fn_)) return true;
        if (cancelled_) return false;
        fraction = std::max(0.0, std::min(1.0, fraction));
        const bool isFinal = fraction >= 1.0;
        if (!isFinal) {
            const auto now = std::chrono::steady_clock::now();
            if (now - last_ < std::chrono::milliseconds(50)) return true; // <= 20 Hz
            if (fraction <= lastSent_) return true;
        }
        last_ = std::chrono::steady_clock::now();
        lastSent_ = fraction;
        if (!fn_(static_cast<float>(fraction))) cancelled_ = true;
        return !cancelled_;
    }

private:
    ProgressFn fn_;
    std::chrono::steady_clock::time_point last_{std::chrono::steady_clock::now()};
    double lastSent_ = -1.0;
    bool cancelled_ = false;
};

// v6 §10.4-2: deterministic parallel sharding.
// Shards split by independent iteration ranges; every shard writes disjoint
// output slices and cross-shard reductions use exact max / integer sums over
// shard-ordered partials, so results stay bitwise identical to the serial run.
// v7 §10.4-3: shard count can be capped from the environment (1..8). The value
// is re-read per call so host tests can compare serial and parallel runs in a
// single process; the app never sets the variable, so production keeps the
// default (hardware concurrency, capped at 8).
unsigned engineThreadCount() {
    unsigned n = 0;
    const char* env = std::getenv("LOOP_ENGINE_THREADS");
    if (env != nullptr && env[0] != '\0') {
        char* end = nullptr;
        const long v = std::strtol(env, &end, 10);
        if (end != env && v > 0) n = static_cast<unsigned>(v);
    }
    if (n == 0) n = std::thread::hardware_concurrency();
    if (n == 0) n = 2u;
    if (n > 8u) n = 8u; // phone-friendly ceiling; also bounds scratch buffers
    return n;
}

int shardCount(int64_t items, int64_t minItemsPerShard = 64) {
    if (items <= 0) return 1;
    int64_t shards = static_cast<int64_t>(engineThreadCount());
    if (shards <= 1) return 1;
    if (minItemsPerShard > 1 && items / shards < minItemsPerShard) shards = 1;
    return static_cast<int>(shards);
}

// body(lo, hi, shard): process independent range [lo, hi), shard = shard index.
// minItemsPerShard keeps tiny ranges serial (thread spawn is ~tens of us).
template <class Body>
void parallelForRangeItems(int64_t begin, int64_t end, const Body& body,
                           int64_t minItemsPerShard) {
    const int64_t items = end - begin;
    if (items <= 0) return;
    const int shards = shardCount(items, minItemsPerShard);
    if (shards <= 1) {
        body(begin, end, 0);
        return;
    }
    std::vector<std::thread> threads;
    threads.reserve(static_cast<size_t>(shards));
    for (int s = 0; s < shards; s++) {
        const int64_t lo = begin + items * s / shards;
        const int64_t hi = begin + items * (s + 1) / shards;
        if (hi <= lo) continue;
        threads.emplace_back([&body, lo, hi, s]() { body(lo, hi, s); });
    }
    for (auto& t : threads) t.join();
}

template <class Body>
void parallelForRange(int64_t begin, int64_t end, const Body& body) {
    parallelForRangeItems(begin, end, body, 64);
}

// librosa.time_to_frames: floor(int(seconds * sr) / hop)
int timeToFrames(double seconds, int sr) {
    const int64_t samples = static_cast<int64_t>(seconds * double(sr));
    return static_cast<int>(samples / kHop);
}

int64_t timeToSamplesTrunc(double seconds, int sr) {
    return static_cast<int64_t>(seconds * double(sr));
}

// ---------------------------------------------------------------- mel basis
double hzToMel(double f) {
    const double fSp = 200.0 / 3.0;
    const double minLogMel = 1000.0 / fSp; // 15
    const double logstep = std::log(6.4) / 27.0;
    if (f >= 1000.0) return minLogMel + std::log(f / 1000.0) / logstep;
    return f / fSp;
}

double melToHz(double m) {
    const double fSp = 200.0 / 3.0;
    const double minLogMel = 15.0;
    const double logstep = std::log(6.4) / 27.0;
    if (m >= minLogMel) return 1000.0 * std::exp(logstep * (m - minLogMel));
    return fSp * m;
}

struct MelBasis {
    std::vector<int> lo, hi;      // nonzero [lo, hi] fft-bin range per mel row
    std::vector<double> w;        // row-major weights (float32-rounded like librosa)
};

MelBasis buildMelBasis(int sr) {
    const int points = kMels + 2;
    std::vector<double> melF(points);
    const double m0 = hzToMel(0.0);
    const double m1 = hzToMel(kMelFmax);
    for (int i = 0; i < points; i++)
        melF[size_t(i)] = melToHz(m0 + (m1 - m0) * double(i) / double(points - 1));

    MelBasis b;
    b.lo.resize(size_t(kMels));
    b.hi.resize(size_t(kMels));
    b.w.assign(size_t(kMels) * size_t(kBins), 0.0);
    for (int i = 0; i < kMels; i++) {
        const double fd0 = melF[size_t(i + 1)] - melF[size_t(i)];
        const double fd1 = melF[size_t(i + 2)] - melF[size_t(i + 1)];
        const double enorm = 2.0 / (melF[size_t(i + 2)] - melF[size_t(i)]); // slaney norm
        int lo = kBins, hi = -1;
        for (int k = 0; k < kBins; k++) {
            const double ff = double(k) * double(sr) / double(kNfft);
            const double lower = (ff - melF[size_t(i)]) / fd0;
            const double upper = (melF[size_t(i + 2)] - ff) / fd1;
            double wv = std::max(0.0, std::min(lower, upper)) * enorm;
            wv = double(float(wv)); // librosa builds the basis in float32
            b.w[size_t(i) * size_t(kBins) + size_t(k)] = wv;
            if (wv != 0.0) {
                lo = std::min(lo, k);
                hi = std::max(hi, k);
            }
        }
        if (hi < lo) { lo = 0; hi = -1; }
        b.lo[size_t(i)] = lo;
        b.hi[size_t(i)] = hi;
    }
    return b;
}

// ----------------------------------------------------------- A-weighting dB
void aWeightingDb(int sr, std::vector<double>& aw) {
    aw.resize(size_t(kBins));
    const double c0 = 12194.217 * 12194.217;
    const double c1 = 20.598997 * 20.598997;
    const double c2 = 107.65265 * 107.65265;
    const double c3 = 737.86223 * 737.86223;
    for (int k = 0; k < kBins; k++) {
        const double f = double(k) * double(sr) / double(kNfft);
        const double f2 = f * f;
        const double w = 2.0 + 20.0 * (std::log10(c0) + 2.0 * std::log10(f2)
                                       - std::log10(f2 + c0) - std::log10(f2 + c1)
                                       - 0.5 * std::log10(f2 + c2)
                                       - 0.5 * std::log10(f2 + c3));
        aw[size_t(k)] = std::max(-80.0, w); // min_db=-80
    }
}

// ------------------------------------------------------------------- STFT
struct SpectralData {
    int T = 0;
    std::vector<float> sPower;   // frame-major [t * kBins + k], |STFT|^2
    std::vector<float> sWeighed; // frame-major, perceptual weighting (dB, top_db clipped)
    std::vector<double> mel;     // [m * T + t]
    double dbFloor = 0.0;        // global power_to_db top_db floor
};

bool stftPass(const std::vector<double>& y, int sr, SpectralData& sd, ProgressReporter* prog) {
    const int64_t M = static_cast<int64_t>(y.size());
    if (M < 1) return false;
    const int T = static_cast<int>(M / kHop) + 1; // 1 + floor(M/512), centered
    sd.T = T;
    sd.sPower.assign(size_t(T) * size_t(kBins), 0.0f);
    sd.sWeighed.assign(size_t(T) * size_t(kBins), 0.0f);
    sd.mel.assign(size_t(kMels) * size_t(T), 0.0);

    std::vector<double> hann;
    detail::hannPeriodic(kNfft, hann);
    const MelBasis melb = buildMelBasis(sr);
    std::vector<double> aw;
    aWeightingDb(sr, aw);

    // pass 1: STFT power (v6 §10.4-2 parallel by frames; gmax = exact cross-shard max)
    {
        const int shards = shardCount(int64_t(T));
        std::vector<double> shardGmax(size_t(std::max(1, shards)),
                                      -std::numeric_limits<double>::infinity());
        parallelForRange(0, T, [&](int64_t lo, int64_t hi, int shard) {
            std::vector<double> frame(kNfft);
            std::vector<Cdp> spec(kBins);
            double local = -std::numeric_limits<double>::infinity();
            for (int t = int(lo); t < int(hi); t++) {
                const int64_t begin = int64_t(t) * kHop - kNfft / 2;
                for (int i = 0; i < kNfft; i++) {
                    const int64_t pos = begin + i;
                    const double v = (pos >= 0 && pos < M) ? y[size_t(pos)] : 0.0;
                    frame[size_t(i)] = v * hann[size_t(i)];
                }
                detail::rfft(frame.data(), kNfft, spec.data());
                float* pw = &sd.sPower[size_t(t) * size_t(kBins)];
                for (int k = 0; k < kBins; k++) {
                    const double re = spec[size_t(k)].real();
                    const double im = spec[size_t(k)].imag();
                    const double p = re * re + im * im;
                    pw[k] = float(p);
                    local = std::max(local, 10.0 * std::log10(std::max(1e-10, p)));
                }
            }
            shardGmax[size_t(shard)] = local;
        });
        double gmaxLog = -std::numeric_limits<double>::infinity();
        for (double v : shardGmax) gmaxLog = std::max(gmaxLog, v);
        sd.dbFloor = gmaxLog - 80.0; // top_db clip floor (ref=1.0)
        if (prog != nullptr && !prog->report(0.30)) return false;
    }
    // pass 2: perceptual weighting + mel accumulation (v6 §10.4-2 parallel by frames)
    {
        parallelForRange(0, T, [&](int64_t lo, int64_t hi, int /*shard*/) {
            std::vector<double> swd(kBins);
            std::vector<double> melCol(kMels);
            for (int t = int(lo); t < int(hi); t++) {
                const float* pw = &sd.sPower[size_t(t) * size_t(kBins)];
                float* sw = &sd.sWeighed[size_t(t) * size_t(kBins)];
                for (int k = 0; k < kBins; k++) {
                    const double db = 10.0 * std::log10(std::max(1e-10, double(pw[k])));
                    swd[size_t(k)] = aw[size_t(k)] + std::max(db, sd.dbFloor);
                    sw[k] = float(swd[size_t(k)]);
                }
                std::fill(melCol.begin(), melCol.end(), 0.0);
                for (int m = 0; m < kMels; m++) {
                    const int lo2 = melb.lo[size_t(m)];
                    const int hi2 = melb.hi[size_t(m)];
                    if (hi2 < lo2) continue;
                    const double* row = &melb.w[size_t(m) * size_t(kBins)];
                    double s = 0.0;
                    for (int k = lo2; k <= hi2; k++) s += row[k] * swd[size_t(k)];
                    melCol[size_t(m)] = s;
                }
                for (int m = 0; m < kMels; m++)
                    sd.mel[size_t(m) * size_t(T) + size_t(t)] = melCol[size_t(m)];
            }
        });
        if (prog != nullptr && !prog->report(0.50)) return false;
    }
    return true;
}

// ------------------------------------------------------------------ tuning
// librosa.piptrack + pitch_tuning over the power spectrogram (|S|^2 passed as S).
double estimateTuning(const std::vector<float>& sPower, int T, int sr) {
    // chroma_stft() is called by PyMusicLooper without `sr`, so the tuning
    // estimation runs under librosa's 22050 Hz default.
    (void)sr;
    int kMin = 0;
    while (kMin < kBins && double(kMin) * kBeatSr / double(kNfft) < 150.0) kMin++;
    int kMax = kBins - 1;
    while (kMax >= 0 && double(kMax) * kBeatSr / double(kNfft) >= 4000.0) kMax--;
    if (kMin > kMax) return 0.0;

    // v7 §10.4-3: both passes are parallel by frame. Pass A concatenates the
    // per-shard candidate lists in shard order, so the magnitude sequence (and
    // therefore the median) is identical. Pass B counts are integers, so the
    // histogram is order independent.
    std::vector<float> mags;
    {
        const int shards = std::max(1, shardCount(int64_t(T)));
        std::vector<std::vector<float>> shardMags(static_cast<size_t>(shards));
        parallelForRange(0, T, [&](int64_t lo, int64_t hi, int shard) {
            std::vector<double> col(kBins);
            std::vector<float>& out = shardMags[size_t(shard)];
            out.reserve(size_t(hi - lo) * size_t(kMax - kMin + 1) / 3u + 16u);
            for (int t = int(lo); t < int(hi); t++) {
                const float* p = &sPower[size_t(t) * size_t(kBins)];
                for (int k = 0; k < kBins; k++) col[size_t(k)] = p[k];
                double mx = 0.0;
                for (int k = 0; k < kBins; k++) mx = std::max(mx, col[size_t(k)]);
                const double refV = 0.1 * mx; // threshold * np.max(S, axis=-2)
                for (int k = kMin; k <= kMax; k++) {
                    const double v = col[size_t(k)];
                    if (!(v > refV)) continue;
                    const double xl = (k >= 1 && col[size_t(k - 1)] > refV) ? col[size_t(k - 1)] : 0.0;
                    const double xr = (k + 1 < kBins && col[size_t(k + 1)] > refV) ? col[size_t(k + 1)] : 0.0;
                    // localmax: x[k] > x[k-1] and x[k] >= x[k+1]; k == 0 never a max
                    const bool isMax = k >= 1 && v > xl && (k + 1 < kBins ? v >= xr : v > xl);
                    if (!isMax) continue;
                    double avg, shift;
                    if (k == 0) {
                        avg = col[1] - col[0];
                        shift = 0.0;
                    } else if (k == kBins - 1) {
                        avg = col[size_t(k)] - col[size_t(k - 1)];
                        shift = 0.0;
                    } else {
                        avg = 0.5 * (col[size_t(k + 1)] - col[size_t(k - 1)]);
                        const double a = col[size_t(k + 1)] + col[size_t(k - 1)] - 2.0 * col[size_t(k)];
                        const double b = 0.5 * (col[size_t(k + 1)] - col[size_t(k - 1)]);
                        shift = (std::abs(b) >= std::abs(a)) ? 0.0 : -b / a;
                    }
                    const double mag = v + 0.5 * avg * shift; // dskew folded in
                    out.push_back(float(mag));
                }
            }
        });
        size_t total = 0;
        for (const std::vector<float>& v : shardMags) total += v.size();
        if (total == 0) return 0.0; // no pitch candidates -> pitch_tuning returns 0.0
        mags.reserve(total);
        for (const std::vector<float>& v : shardMags) mags.insert(mags.end(), v.begin(), v.end());
    }

    std::vector<double> magD(mags.size());
    for (size_t i = 0; i < mags.size(); i++) magD[i] = mags[i];
    std::sort(magD.begin(), magD.end());
    const double threshold = detail::medianOf(magD.data(), int(magD.size()));

    // pass B: histogram the tuning residual of pitches above the threshold
    const int nHistBins = 100;
    std::vector<int> counts(nHistBins, 0);
    {
        const int shards = std::max(1, shardCount(int64_t(T)));
        std::vector<std::vector<int>> shardCounts(
            size_t(shards), std::vector<int>(size_t(nHistBins), 0));
        parallelForRange(0, T, [&](int64_t lo, int64_t hi, int shard) {
            std::vector<double> col(kBins);
            std::vector<int>& cnt = shardCounts[size_t(shard)];
            for (int t = int(lo); t < int(hi); t++) {
                const float* p = &sPower[size_t(t) * size_t(kBins)];
                for (int k = 0; k < kBins; k++) col[size_t(k)] = p[k];
                double mx = 0.0;
                for (int k = 0; k < kBins; k++) mx = std::max(mx, col[size_t(k)]);
                const double refV = 0.1 * mx;
                for (int k = kMin; k <= kMax; k++) {
                    const double v = col[size_t(k)];
                    if (!(v > refV)) continue;
                    const double xl = (k >= 1 && col[size_t(k - 1)] > refV) ? col[size_t(k - 1)] : 0.0;
                    const double xr = (k + 1 < kBins && col[size_t(k + 1)] > refV) ? col[size_t(k + 1)] : 0.0;
                    const bool isMax = k >= 1 && v > xl && (k + 1 < kBins ? v >= xr : v > xl);
                    if (!isMax) continue;
                    double avg, shift;
                    if (k == 0) { avg = col[1] - col[0]; shift = 0.0; }
                    else if (k == kBins - 1) { avg = col[size_t(k)] - col[size_t(k - 1)]; shift = 0.0; }
                    else {
                        avg = 0.5 * (col[size_t(k + 1)] - col[size_t(k - 1)]);
                        const double a = col[size_t(k + 1)] + col[size_t(k - 1)] - 2.0 * col[size_t(k)];
                        const double b = 0.5 * (col[size_t(k + 1)] - col[size_t(k - 1)]);
                        shift = (std::abs(b) >= std::abs(a)) ? 0.0 : -b / a;
                    }
                    const double mag = v + 0.5 * avg * shift;
                    if (!(double(mag) >= threshold)) continue;
                    const double pitch = (double(k) + shift) * kBeatSr / double(kNfft);
                    if (pitch <= 0.0) continue;
                    double residual = std::fmod(12.0 * std::log2(pitch / 27.5), 1.0);
                    if (residual >= 0.5) residual -= 1.0;
                    int bin = int(std::floor((residual + 0.5) / 0.01));
                    if (bin < 0) bin = 0;
                    if (bin >= nHistBins) bin = nHistBins - 1;
                    cnt[size_t(bin)]++;
                }
            }
        });
        for (const std::vector<int>& v : shardCounts)
            for (int i = 0; i < nHistBins; i++) counts[size_t(i)] += v[size_t(i)];
    }
    int best = 0;
    for (int i = 1; i < nHistBins; i++)
        if (counts[size_t(i)] > counts[size_t(best)]) best = i;
    return -0.5 + 0.01 * double(best);
}

// ------------------------------------------------------------------ chroma
void buildChromaBasis(double tuning, int sr, std::vector<double>& basis /*12*kBins*/) {
    const int nF = kNfft;
    std::vector<double> frqbins(nF);
    const double a440 = 440.0 * std::pow(2.0, tuning / 12.0);
    const double octDenom = a440 / 16.0;
    frqbins[0] = 0.0;
    for (int j = 1; j < nF; j++)
        frqbins[size_t(j)] = 12.0 * std::log2((double(j) * double(sr) / double(nF)) / octDenom);
    frqbins[0] = frqbins[1] - 1.5 * 12.0; // synthetic DC bin: 1.5 octaves below bin 1

    std::vector<double> binwidth(nF);
    for (int j = 0; j + 1 < nF; j++)
        binwidth[size_t(j)] = std::max(frqbins[size_t(j + 1)] - frqbins[size_t(j)], 1.0);
    binwidth[size_t(nF - 1)] = 1.0;

    std::vector<double> wts(size_t(12) * size_t(nF));
    for (int c = 0; c < 12; c++) {
        for (int j = 0; j < nF; j++) {
            double d = frqbins[size_t(j)] - double(c);
            d = std::fmod(d + 6.0 + 120.0, 12.0); // np.remainder semantics
            if (d < 0.0) d += 12.0;
            d -= 6.0;
            const double x = 2.0 * d / binwidth[size_t(j)];
            wts[size_t(c) * size_t(nF) + size_t(j)] = std::exp(-0.5 * x * x);
        }
    }
    // util.normalize(wts, norm=2, axis=0): unit l2 per fft bin (column)
    for (int j = 0; j < nF; j++) {
        double len = 0.0;
        for (int c = 0; c < 12; c++) {
            const double v = wts[size_t(c) * size_t(nF) + size_t(j)];
            len += v * v;
        }
        len = std::sqrt(len);
        if (len >= kTiny64)
            for (int c = 0; c < 12; c++) wts[size_t(c) * size_t(nF) + size_t(j)] /= len;
    }
    // Gaussian octave dominance window (ctroct=5.0, octwidth=2)
    for (int j = 0; j < nF; j++) {
        const double g = std::exp(-0.5 * std::pow((frqbins[size_t(j)] / 12.0 - 5.0) / 2.0, 2.0));
        for (int c = 0; c < 12; c++) wts[size_t(c) * size_t(nF) + size_t(j)] *= g;
    }
    // base_c=True: np.roll(wts, -3 * (12 // 12), axis=0) -> out[c] = in[(c+3) % 12]
    basis.assign(size_t(12) * size_t(kBins), 0.0);
    for (int c = 0; c < 12; c++)
        for (int j = 0; j < kBins; j++)
            basis[size_t(c) * size_t(kBins) + size_t(j)] =
                double(float(wts[size_t((c + 3) % 12) * size_t(nF) + size_t(j)]));
}

bool computeChroma(const std::vector<float>& sPower, int T, int sr, double tuning,
                   std::vector<double>& chroma /*12*T*/, ProgressReporter* prog) {
    // Same librosa-default-sr quirk as the tuning: the chroma filter bank is
    // built for 22050 Hz regardless of the real sample rate.
    (void)sr;
    std::vector<double> basis;
    buildChromaBasis(tuning, kBeatSr, basis);
    chroma.assign(size_t(12) * size_t(T), 0.0);
    // v6 §10.4-2: parallel by frames (each frame writes its own chroma column)
    parallelForRange(0, T, [&](int64_t lo, int64_t hi, int /*shard*/) {
        std::vector<double> S(kBins);
        std::vector<double> col(12);
        for (int t = int(lo); t < int(hi); t++) {
            const float* p = &sPower[size_t(t) * size_t(kBins)];
            for (int k = 0; k < kBins; k++) S[size_t(k)] = p[k];
            for (int c = 0; c < 12; c++) {
                const double* w = &basis[size_t(c) * size_t(kBins)];
                double s = 0.0;
                for (int k = 0; k < kBins; k++) s += w[k] * S[size_t(k)];
                col[size_t(c)] = s;
            }
            // util.normalize(raw_chroma, norm=inf, axis=-2), float32 threshold
            double len = 0.0;
            for (int c = 0; c < 12; c++) len = std::max(len, std::abs(col[size_t(c)]));
            if (len >= kTiny32) {
                for (int c = 0; c < 12; c++) col[size_t(c)] /= len;
            }
            for (int c = 0; c < 12; c++)
                chroma[size_t(c) * size_t(T) + size_t(t)] = double(float(col[size_t(c)]));
        }
    });
    (void)prog; // progress reported by caller at phase boundary (no JNI from workers)
    return true;
}

// ------------------------------------------------------------ power dB max
void powerDbColMax(const std::vector<float>& sWeighed, int T, std::vector<double>& cmax) {
    // v7 §10.4-3: global max and per-frame column max run in parallel. Both are
    // exact max reductions, so the values are bitwise identical to the serial walk.
    double gmaxLog = -std::numeric_limits<double>::infinity();
    {
        const int shards = std::max(1, shardCount(int64_t(sWeighed.size())));
        std::vector<double> part(size_t(shards), -std::numeric_limits<double>::infinity());
        parallelForRange(0, int64_t(sWeighed.size()), [&](int64_t lo, int64_t hi, int shard) {
            double local = -std::numeric_limits<double>::infinity();
            for (int64_t i = lo; i < hi; i++)
                local = std::max(local, 10.0 * std::log10(std::max(1e-10, double(sWeighed[size_t(i)])))) ;
            part[size_t(shard)] = local;
        });
        for (double v : part) gmaxLog = std::max(gmaxLog, v);
    }
    std::vector<float> copy = sWeighed; // median reference
    const size_t n = copy.size();
    const auto mid = copy.begin() + ptrdiff_t(n / 2);
    std::nth_element(copy.begin(), mid, copy.end());
    double median = double(*mid);
    if (n % 2 == 0) {
        const auto mx = std::max_element(copy.begin(), mid);
        median = 0.5 * (median + double(*mx));
    }
    const double dbRef = 10.0 * std::log10(std::max(1e-10, median));
    const double floorDb = (gmaxLog - dbRef) - 80.0; // top_db=80
    cmax.assign(size_t(T), 0.0);
    parallelForRange(0, T, [&](int64_t lo, int64_t hi, int /*shard*/) {
        for (int t = int(lo); t < int(hi); t++) {
            const float* sw = &sWeighed[size_t(t) * size_t(kBins)];
            double mx = -std::numeric_limits<double>::infinity();
            for (int k = 0; k < kBins; k++) {
                const double d = 10.0 * std::log10(std::max(1e-10, double(sw[k]))) - dbRef;
                mx = std::max(mx, std::max(d, floorDb));
            }
            cmax[size_t(t)] = mx;
        }
    });
}

// ------------------------------------------------------------ onset envelope
// onset_strength(S=mel): mean over mel bins of max(0, S[:, t] - S[:, t-1]),
// zero-padded 3 frames at the front (lag=1 plus center compensation), then
// truncated back to T frames.
void onsetEnvelope(const std::vector<double>& mel, int T, std::vector<double>& env) {
    env.assign(size_t(T), 0.0);
    // v6 §10.4-2: parallel by j (env[j+3] independent writes)
    parallelForRange(0, std::max(0, T - 3), [&](int64_t lo, int64_t hi, int /*shard*/) {
        for (int j = int(lo); j < int(hi); j++) {
            double s = 0.0;
            for (int m = 0; m < kMels; m++) {
                const double d = mel[size_t(m) * size_t(T) + size_t(j + 1)] -
                                 mel[size_t(m) * size_t(T) + size_t(j)];
                if (d > 0.0) s += d;
            }
            env[size_t(j + 3)] = s / double(kMels);
        }
    });
}

// ------------------------------------------------------------------- plp
inline bool lexGreater(const Cdp& a, const Cdp& b) {
    if (a.real() != b.real()) return a.real() > b.real();
    return a.imag() > b.imag();
}

// librosa.util.localmax (1-D): x[i] > x[i-1] && x[i] >= x[i+1]; index 0 never.
void localMaxIndices(const std::vector<double>& x, std::vector<int>& out) {
    const int n = int(x.size());
    for (int i = 1; i < n; i++) {
        const bool left = x[size_t(i)] > x[size_t(i - 1)];
        const bool right = (i + 1 < n) ? (x[size_t(i)] >= x[size_t(i + 1)]) : true;
        if (left && right) out.push_back(i);
    }
}

// Predominant local pulse (librosa.beat.plp with defaults + normalized onset env).
void plpPulse(const std::vector<double>& env, int sr, std::vector<double>& pulse) {
    const int T = int(env.size());
    const int W = 384;
    const int bins = W / 2 + 1; // 193
    const int nFrames = T + 1;  // centered stft with hop=1
    std::vector<double> win;
    detail::hannPeriodic(W, win);

    std::vector<Cdp> ft(size_t(nFrames) * size_t(bins));
    // v7 §10.4-3: forward STFT frames only read env/win and write their own row.
    parallelForRange(0, nFrames, [&](int64_t lo, int64_t hi, int /*shard*/) {
        std::vector<double> frame(W);
        std::vector<Cdp> spec(bins);
        for (int i = int(lo); i < int(hi); i++) {
            for (int j = 0; j < W; j++) {
                const int64_t pos = int64_t(i) - W / 2 + j;
                const double v = (pos >= 0 && pos < T) ? env[size_t(pos)] : 0.0;
                frame[size_t(j)] = v * win[size_t(j)];
            }
            detail::rfft(frame.data(), W, spec.data());
            for (int k = 0; k < bins; k++)
                ft[size_t(i) * size_t(bins) + size_t(k)] = spec[size_t(k)];
        }
    });
    // pin to feasible tempo range [30, 300] bpm
    std::vector<char> keep(bins, 0);
    for (int k = 0; k < bins; k++) {
        const double bpmBin = double(k) * (kBeatSr * 60.0 / 512.0) / double(W);
        keep[size_t(k)] = (bpmBin >= 30.0 && bpmBin <= 300.0) ? 1 : 0;
    }
    const double normBase = std::sqrt(kTiny64); // tiny(complex128) ** 0.5
    // v7 §10.4-3: per-frame tempo-bin pinning/normalization, disjoint rows.
    parallelForRange(0, nFrames, [&](int64_t lo, int64_t hi, int /*shard*/) {
        for (int i = int(lo); i < int(hi); i++) {
            Cdp* col = &ft[size_t(i) * size_t(bins)];
            double peakFtmag = 0.0;
            for (int k = 0; k < bins; k++) {
                if (!keep[size_t(k)]) continue;
                peakFtmag = std::max(peakFtmag, std::log1p(1e6 * std::abs(col[size_t(k)])));
            }
            Cdp lexMax(0.0, 0.0);
            bool hasLex = false;
            for (int k = 0; k < bins; k++) {
                if (!keep[size_t(k)]) { col[size_t(k)] = Cdp(0.0, 0.0); continue; }
                const double ftm = std::log1p(1e6 * std::abs(col[size_t(k)]));
                if (ftm < peakFtmag) col[size_t(k)] = Cdp(0.0, 0.0);
                else if (!hasLex || lexGreater(col[size_t(k)], lexMax)) {
                    lexMax = col[size_t(k)];
                    hasLex = true;
                }
            }
            // np.abs(ftgram.max(axis=-2)) uses numpy lexicographic ordering on complex
            const double denom = normBase + std::abs(lexMax);
            for (int k = 0; k < bins; k++) col[size_t(k)] /= denom;
        }
    });
    // istft(ftgram, hop_length=1, n_fft=384, length=T)
    // Replicates librosa.istft exactly, including its split overlap-add: the
    // first start_frame=192 frames go into a head buffer and are copied out as
    // y[0:383] = head[192:575] (a phase shift by construction), then the main
    // frames i >= 192 accumulate at y[i..]. This asymmetry is part of the
    // reference output and determines the plp beat phase.
    pulse.assign(size_t(T), 0.0);
    std::vector<double> recon(W);
    const int startFrame = (W / 2 + 0) / 1; // ceil(192 / hop=1)
    {
        std::vector<double> head(size_t(W) + size_t(startFrame - 1), 0.0); // 575
        for (int j = 0; j < startFrame; j++) {
            detail::irfft(&ft[size_t(j) * size_t(bins)], W, recon.data());
            for (int m = 0; m < W; m++) head[size_t(j + m)] += win[size_t(m)] * recon[size_t(m)];
        }
        const int copyN = std::min(W + (startFrame - 1) - (W / 2), T); // 383
        for (int q = 0; q < copyN; q++) pulse[size_t(q)] = head[size_t(W / 2 + q)];
    }
    // Main frames g >= start_frame land at y[(g - start_frame) * hop] because
    // librosa's overlap-add offset is start_frame*hop - n_fft//2 (= 0 here):
    // the whole reconstruction is the ideal OLA advanced by n_fft//2 samples.
    // v7 §10.4-3: IFFT runs in parallel per chunk, but the overlap-add itself
    // stays serial in ascending frame order, so every pulse[n] receives its
    // contributions in exactly the same order as before (bitwise identical).
    const int kIstftChunk = 512;
    std::vector<double> chunkRecon(size_t(kIstftChunk) * size_t(W));
    for (int c0 = startFrame; c0 < nFrames; c0 += kIstftChunk) {
        const int c1 = std::min(nFrames, c0 + kIstftChunk);
        const int count = c1 - c0;
        parallelForRangeItems(0, count, [&](int64_t lo, int64_t hi, int /*shard*/) {
            for (int64_t k = lo; k < hi; k++) {
                detail::irfft(&ft[size_t(c0 + int(k)) * size_t(bins)], W,
                              &chunkRecon[size_t(k) * size_t(W)]);
            }
        }, 8);
        for (int i = c0; i < c1; i++) {
            const int base = i - startFrame;
            const int n0 = std::max(base, 0);
            const int n1 = std::min(T, base + W);
            const double* rec = &chunkRecon[size_t(i - c0) * size_t(W)];
            for (int n = n0; n < n1; n++) {
                const int j = n - base;
                pulse[size_t(n)] += win[size_t(j)] * rec[size_t(j)];
            }
        }
    }
    // window_sumsquare over all n_frames = T+1 frames, then librosa.istft
    // divides y[q] by the envelope INDEXED FROM start = n_fft//2, i.e.
    // divisor[y[q]] = wss_full[q + 192] (util.fix_length(wss[start:], T)).
    {
        std::vector<double> wssFull(size_t(T) + size_t(W), 0.0);
        for (int i = 0; i < nFrames; i++) {
            const int n0 = i;
            const int n1 = std::min(T + W, i + W);
            for (int n = n0; n < n1; n++) wssFull[size_t(n)] += win[size_t(n - i)] * win[size_t(n - i)];
        }
        for (int n = 0; n < T; n++) {
            const double d = wssFull[size_t(n + W / 2)];
            if (d > kTiny64) pulse[size_t(n)] /= d;
        }
    }
    double mx = 0.0;
    for (double& v : pulse) {
        v = std::max(0.0, v); // np.clip(pulse, 0, None)
        mx = std::max(mx, v);
    }
    if (mx >= kTiny64)
        for (double& v : pulse) v /= mx; // util.normalize(pulse, axis=-1)
}

// ------------------------------------------------------------ beat tracking
struct BeatTrackResult {
    double bpm = 0.0;
    std::vector<int> beats;
};

// librosa.feature.tempogram + tempo: windowed autocorrelation tempogram with a
// log-normal prior around 120 bpm.
double estimateTempo(const std::vector<double>& oenv, int sr, std::vector<double>* tgMeanOut = nullptr) {
    (void)sr;
    const int N = int(oenv.size());
    const int win = int(timeToSamplesTrunc(8.0, kBeatSr) / kHop); // time_to_frames(8.0, sr=22050)
    if (win < 2 || N < 2) return 0.0;
    const int half = win / 2;

    std::vector<double> padded(size_t(N) + size_t(2 * half), 0.0);
    for (int i = 0; i < half; i++)
        padded[size_t(i)] = oenv[size_t(0)] * (double(i) / double(half));
    std::copy(oenv.begin(), oenv.end(), padded.begin() + half);
    for (int i = 0; i < half; i++)
        padded[size_t(N + half + i)] =
            oenv[size_t(N - 1)] * (double(half - 1 - i) / double(half));

    std::vector<double> acwin;
    detail::hannPeriodic(win, acwin);

    int FF = 1024; // >= 2*win-1 zero-padded autocorrelation length (win=344 -> 687)
    while (FF < 2 * win - 1) FF <<= 1;
    std::vector<double> tgMean(win, 0.0);
    // v7 §10.4-3: per-frame autocorrelation (2 FFTs) is independent; the tgMean
    // accumulation stays serial in ascending t, so the summation order and the
    // resulting bits are unchanged. Only lags < win are used downstream, so the
    // chunk buffer stores just those.
    const int kTempoChunk = 128;
    std::vector<double> chunkAc(size_t(kTempoChunk) * size_t(win), 0.0);
    std::vector<double> chunkLen(size_t(kTempoChunk), 0.0);
    for (int c0 = 0; c0 < N; c0 += kTempoChunk) {
        const int c1 = std::min(N, c0 + kTempoChunk);
        const int count = c1 - c0;
        parallelForRangeItems(0, count, [&](int64_t lo, int64_t hi, int /*shard*/) {
            std::vector<double> buf(FF, 0.0);
            std::vector<Cdp> spec(FF / 2 + 1);
            std::vector<double> ac(FF);
            for (int64_t kk = lo; kk < hi; kk++) {
                const int t = c0 + int(kk);
                // buf[win..FF) is zero for every frame; only [0, win) is refreshed.
                for (int i = 0; i < win; i++) {
                    const int64_t pos = int64_t(t) + i;
                    buf[size_t(i)] = (pos < int64_t(padded.size())) ? padded[size_t(pos)] * acwin[size_t(i)] : 0.0;
                }
                detail::rfft(buf.data(), FF, spec.data());
                for (int k = 0; k <= FF / 2; k++) {
                    const double re = spec[size_t(k)].real();
                    const double im = spec[size_t(k)].imag();
                    spec[size_t(k)] = Cdp(re * re + im * im, 0.0);
                }
                detail::irfft(spec.data(), FF, ac.data());
                double len = 0.0;
                for (int lag = 0; lag < win; lag++) len = std::max(len, std::abs(ac[size_t(lag)]));
                double* dst = &chunkAc[size_t(kk) * size_t(win)];
                for (int lag = 0; lag < win; lag++) dst[size_t(lag)] = ac[size_t(lag)];
                chunkLen[size_t(kk)] = len;
            }
        }, 8);
        for (int k = 0; k < count; k++) {
            const double len = chunkLen[size_t(k)];
            if (len >= kTiny64) {
                const double* ac = &chunkAc[size_t(k) * size_t(win)];
                for (int lag = 0; lag < win; lag++) tgMean[size_t(lag)] += ac[size_t(lag)] / len;
            }
        }
    }
    for (int lag = 0; lag < win; lag++) tgMean[size_t(lag)] /= double(N);
    if (tgMeanOut != nullptr) *tgMeanOut = tgMean;

    std::vector<double> bpms(win);
    bpms[0] = std::numeric_limits<double>::infinity();
    for (int l = 1; l < win; l++)
        bpms[size_t(l)] = 60.0 * kBeatSr / (double(kHop) * double(l));
    int maxIdx = 0;
    for (int l = 0; l < win; l++) {
        if (bpms[size_t(l)] < 320.0) { maxIdx = l; break; }
    }
    double bestVal = -std::numeric_limits<double>::infinity();
    int bestLag = 0;
    for (int l = 0; l < win; l++) {
        double prior = -0.5 * std::pow((std::log2(bpms[size_t(l)]) - std::log2(120.0)), 2.0);
        if (l < maxIdx) prior = -std::numeric_limits<double>::infinity();
        const double v = std::log1p(1e6 * tgMean[size_t(l)]) + prior;
        if (v > bestVal) { bestVal = v; bestLag = l; }
    }
    return bpms[size_t(bestLag)];
}

// librosa.beat.beat_track dynamic-programming tracker (Ellis 2007).
BeatTrackResult beatTrack(const std::vector<double>& oenv, int sr, std::vector<double>* tgMeanOut = nullptr) {
    BeatTrackResult res;
    const int N = int(oenv.size());
    bool anyPos = false;
    for (double v : oenv)
        if (v != 0.0) { anyPos = true; break; }
    if (N < 2 || !anyPos) return res;

    // __normalize_onsets: divide by std (ddof=1)
    double mean = 0.0;
    for (double v : oenv) mean += v;
    mean /= double(N);
    double var = 0.0;
    for (double v : oenv) var += (v - mean) * (v - mean);
    const double stdv = std::sqrt(var / double(N - 1));
    std::vector<double> onsets(N);
    for (int i = 0; i < N; i++)
        onsets[size_t(i)] = oenv[size_t(i)] / (stdv + kTiny64);

    res.bpm = estimateTempo(oenv, sr, tgMeanOut);
    if (!(res.bpm > 0.0)) { res.bpm = 0.0; return res; }

    const double frameRate = kBeatSr / double(kHop); // librosa default sr governs bpm semantics
    const int fpb = int(detail::rint(frameRate * 60.0 / res.bpm));
    if (fpb <= 0) { res.beats.clear(); return res; }

    // localscore: same-mode gaussian convolution (window ~ 1 beat wide)
    std::vector<double> localscore(N, 0.0);
    {
        std::vector<double> window(2 * fpb + 1);
        for (int k = 0; k <= 2 * fpb; k++) {
            const double d = double(k - fpb) * 32.0 / double(fpb);
            window[size_t(k)] = std::exp(-0.5 * d * d);
        }
        for (int i = 0; i < N; i++) {
            const int jlo = std::max(0, i - fpb);
            const int jhi = std::min(N - 1, i + fpb);
            double s = 0.0;
            for (int j = jlo; j <= jhi; j++)
                s += window[size_t(fpb + i - j)] * onsets[size_t(j)];
            localscore[size_t(i)] = s;
        }
    }

    // dynamic program
    std::vector<double> cumscore(N, 0.0);
    std::vector<int> backlink(N, -1);
    double maxLocal = 0.0;
    for (double v : localscore) maxLocal = std::max(maxLocal, v);
    const double scoreThresh = 0.01 * maxLocal;
    const double tightness = 100.0;
    const double logFpb = std::log(double(fpb));
    bool firstBeat = true;
    for (int i = 0; i < N; i++) {
        double bestScore = -std::numeric_limits<double>::infinity();
        int beatLocation = -1;
        const int startLoc = i - int(detail::rint(double(fpb) / 2.0));
        const int stopLoc = i - 2 * fpb; // inclusive
        for (int loc = startLoc; loc >= stopLoc; loc--) {
            if (loc < 0) break;
            const double d = std::log(double(i - loc)) - logFpb;
            const double score = cumscore[size_t(loc)] - tightness * d * d;
            if (score > bestScore) {
                bestScore = score;
                beatLocation = loc;
            }
        }
        cumscore[size_t(i)] = localscore[size_t(i)] + (beatLocation >= 0 ? bestScore : 0.0);
        if (firstBeat && localscore[size_t(i)] < scoreThresh) backlink[size_t(i)] = -1;
        else {
            backlink[size_t(i)] = beatLocation;
            firstBeat = false;
        }
    }

    // last beat: highest-index local maximum above half the median of local maxima
    std::vector<int> lmaxIdx;
    localMaxIndices(cumscore, lmaxIdx);
    std::vector<double> sel;
    sel.reserve(lmaxIdx.size());
    for (int v : lmaxIdx) sel.push_back(cumscore[size_t(v)]);
    double threshold = 0.0;
    if (!sel.empty()) {
        std::sort(sel.begin(), sel.end());
        threshold = 0.5 * detail::medianOf(sel.data(), int(sel.size()));
    }
    int tail = N - 1;
    for (int i = N - 1; i >= 0; i--) {
        if (std::binary_search(lmaxIdx.begin(), lmaxIdx.end(), i) &&
            cumscore[size_t(i)] >= threshold) {
            tail = i;
            break;
        }
    }
    std::vector<char> beatsBool(N, 0);
    {
        int n = tail;
        while (n >= 0) {
            beatsBool[size_t(n)] = 1;
            n = backlink[size_t(n)];
        }
    }
    // __trim_beats: suppress leading/trailing beats under half the RMS of the
    // hanning(5)-smoothed localscore at beat positions
    {
        std::vector<double> sel2;
        for (int i = 0; i < N; i++)
            if (beatsBool[size_t(i)]) sel2.push_back(localscore[size_t(i)]);
        if (!sel2.empty()) {
            std::vector<double> h5;
            detail::hannSymmetric(5, h5);
            const int B = int(sel2.size());
            std::vector<double> conv(B + 4, 0.0);
            for (int a = 0; a < B; a++)
                for (int b = 0; b < 5; b++)
                    conv[size_t(a + b)] += sel2[size_t(a)] * h5[size_t(b)];
            const int s0 = 2;                       // len(w) // 2
            const int s1 = std::min(B + 4, N + 2);  // len(localscore) + len(w)//2
            double sum = 0.0;
            int cnt = 0;
            for (int i = s0; i < s1; i++) {
                sum += conv[size_t(i)] * conv[size_t(i)];
                cnt++;
            }
            double thr = 0.0;
            if (cnt > 0) thr = 0.5 * std::sqrt(sum / double(cnt));
            int n = 0;
            while (n < N && localscore[size_t(n)] <= thr) {
                beatsBool[size_t(n)] = 0;
                n++;
            }
            n = N - 1;
            while (n >= 0 && localscore[size_t(n)] <= thr) {
                beatsBool[size_t(n)] = 0;
                n--;
            }
        }
    }
    for (int i = 0; i < N; i++)
        if (beatsBool[size_t(i)]) res.beats.push_back(i);
    return res;
}

// ------------------------------------------------------- candidate pairs
struct PairRaw {
    int startF = 0;
    int endF = 0;
    double nd = 0.0;
    double ld = 0.0;
};

bool findPairs(const std::vector<double>& chroma, int T, const std::vector<int>& beats,
               const std::vector<double>& cmaxDb, int minLoop, int maxLoop,
               std::vector<PairRaw>& out, ProgressReporter* prog) {
    const int B = int(beats.size());
    std::vector<double> dev(B);
    for (int bi = 0; bi < B; bi++) {
        const int t = beats[size_t(bi)];
        double s = 0.0;
        for (int c = 0; c < 12; c++) {
            const double v = chroma[size_t(c) * size_t(T) + size_t(t)];
            s += v * v;
        }
        dev[size_t(bi)] = 0.0875 * std::sqrt(s); // ACCEPTABLE_NOTE_DEVIATION
    }
    // v6 §10.4-2: parallel by end-beat; shard-local vectors concatenated in
    // shard order so `out` sequence stays bitwise identical to serial.
    (void)prog; // progress reported by caller at phase boundary
    const int shards = shardCount(int64_t(B) * int64_t(std::max(1, B)));
    std::vector<std::vector<PairRaw>> shardOut(size_t(std::max(1, shards)));
    parallelForRange(0, B, [&](int64_t lo, int64_t hi, int shard) {
        std::vector<double> colEnd(12);
        for (int bi = int(lo); bi < int(hi); bi++) {
            const int tEnd = beats[size_t(bi)];
            for (int c = 0; c < 12; c++)
                colEnd[size_t(c)] = chroma[size_t(c) * size_t(T) + size_t(tEnd)];
            for (int si = 0; si < B; si++) {
                const int tStart = beats[size_t(si)];
                const int len = tEnd - tStart;
                if (len < minLoop) break;  // beats ascend: all later starts are shorter
                if (len > maxLoop) continue;
                double nd2 = 0.0;
                for (int c = 0; c < 12; c++) {
                    const double d = colEnd[size_t(c)] - chroma[size_t(c) * size_t(T) + size_t(tStart)];
                    nd2 += d * d;
                }
                const double nd = std::sqrt(nd2);
                if (nd <= dev[size_t(bi)]) {
                    const double ld = std::abs(cmaxDb[size_t(tEnd)] - cmaxDb[size_t(tStart)]);
                    if (ld <= 0.5) // ACCEPTABLE_LOUDNESS_DIFFERENCE
                        shardOut[size_t(shard)].push_back(PairRaw{tStart, tEnd, nd, ld});
                }
            }
        }
    });
    for (const auto& sv : shardOut) out.insert(out.end(), sv.begin(), sv.end());
    if (prog != nullptr && !prog->report(0.70)) return false;
    return true;
}

void prunePairs(std::vector<PairRaw>& pairs) {
    if (pairs.size() < 100) return; // reference prunes only when >= 100 pairs
    std::vector<double> lds, nds;
    lds.reserve(pairs.size());
    nds.reserve(pairs.size());
    for (const PairRaw& p : pairs) {
        lds.push_back(p.ld);
        nds.push_back(p.nd);
    }
    auto filteredAboveEps = [](const std::vector<double>& v) {
        std::vector<double> out;
        for (double x : v)
            if (x > 1e-3) out.push_back(x);
        return out;
    };
    const std::vector<double> ldf = filteredAboveEps(lds);
    const std::vector<double> ndf = filteredAboveEps(nds);
    double dbThr, ndThr;
    if (ldf.size() > 3) {
        std::vector<double> s = ldf;
        std::sort(s.begin(), s.end());
        dbThr = detail::percentileLinear(s.data(), int(s.size()), 50.0);
    } else {
        dbThr = *std::max_element(lds.begin(), lds.end());
    }
    if (ndf.size() > 3) {
        std::vector<double> s = ndf;
        std::sort(s.begin(), s.end());
        ndThr = detail::percentileLinear(s.data(), int(s.size()), 75.0);
    } else {
        ndThr = *std::max_element(nds.begin(), nds.end());
    }
    const double ldBound = std::max(0.25, dbThr);
    std::vector<PairRaw> kept;
    kept.reserve(pairs.size());
    for (const PairRaw& p : pairs)
        if (p.ld <= ldBound && p.nd <= ndThr) kept.push_back(p);
    pairs.swap(kept);
}

// Weighted cosine similarity of the chroma sequences following (or preceding)
// the two beats; zero-padded when the track boundary truncates the window.
double subseqSim(int b1Start, int b2Start, int testEndOffset, const std::vector<double>& w,
                 const std::vector<double>& chroma, const std::vector<double>& colNorm, int T) {
    const int testLen = std::abs(testEndOffset);
    int b1 = b1Start, b2 = b2Start;
    int maxOffset = 0;
    if (testEndOffset < 0) {
        const int mno = std::max(testEndOffset, std::max(-b1Start, -b2Start));
        b1 += mno;
        b2 += mno;
        maxOffset = -mno;
    } else {
        const int b1e = std::min(b1Start + testLen, T);
        const int b2e = std::min(b2Start + testLen, T);
        maxOffset = std::min(b1e - b1Start, b2e - b2Start);
    }
    double num = 0.0, den = 0.0;
    const int W = int(w.size());
    for (int j = 0; j < W; j++) {
        double x = 0.0;
        if (j < maxOffset) {
            const int ta = b1 + j;
            const int tb = b2 + j;
            double dot = 0.0;
            for (int c = 0; c < 12; c++)
                dot += chroma[size_t(c) * size_t(T) + size_t(ta)] *
                       chroma[size_t(c) * size_t(T) + size_t(tb)];
            x = dot / std::max(colNorm[size_t(ta)] * colNorm[size_t(tb)], 1e-10);
        }
        num += w[size_t(j)] * x;
        den += w[size_t(j)];
    }
    return den > 0.0 ? num / den : 0.0;
}

// Audacity-style nearest rising zero crossing (pymusiclooper.analysis).
int64_t nearestZeroCrossing(const float* pcm, int channels, int64_t totalFrames, int sr,
                            int64_t sampleIdx) {
    const int windowSize = int(std::max(1.0, double(sr) / 100.0));
    const int64_t offset = windowSize / 2;
    const int64_t neg = std::max<int64_t>(0, sampleIdx - offset);
    const int64_t pos = std::min<int64_t>(totalFrames, sampleIdx + offset);
    const int64_t L = pos - neg;
    if (L <= 0) return sampleIdx;
    const int64_t diff = sampleIdx - offset;
    const int64_t offsetCorrection = diff < 0 ? -diff : 0;
    std::vector<double> dist(L, 0.0);
    for (int ch = 0; ch < channels; ch++) {
        double prev = 2.0;
        for (int64_t i = 0; i < L; i++) {
            const double x = pcm[size_t((neg + i) * channels + ch)];
            double fdist = std::abs(x);
            if (prev * x > 0.0) fdist += 0.4;
            else if (prev > 0.0) fdist += 0.1;
            prev = x;
            dist[size_t(i)] += fdist;
            dist[size_t(i)] += 0.1 * std::abs(double(i - offset + offsetCorrection)) /
                              (double(windowSize) / 2.0);
        }
    }
    int64_t argmin = 0;
    double minDist = dist[0];
    for (int64_t i = 1; i < L; i++) {
        if (dist[size_t(i)] < minDist) {
            minDist = dist[size_t(i)];
            argmin = i;
        }
    }
    if (channels == 1 && minDist > 0.2) return sampleIdx;
    if (channels > 1 && minDist > 0.6 * double(channels)) return sampleIdx;
    return sampleIdx + argmin - offset + offsetCorrection;
}

} // namespace

// ------------------------------------------------------------ orchestration
namespace debug {

Result analyzeWithDebug(const float* interleavedPcm, int channels, int64_t frames,
                        int sampleRate, int maxCandidates, DebugInfo* dbg,
                        const ProgressFn& onProgress) {
    Result result;
    result.totalFrames = frames > 0 ? frames : 0;
    result.sampleRate = sampleRate;
    try {
        ProgressReporter prog(onProgress);
        if (interleavedPcm == nullptr || channels <= 0 || frames <= 0 || sampleRate <= 0 ||
            maxCandidates <= 0)
            return result;

        // ---- mono mixdown (librosa.to_mono) ----
        // v7 §10.4-3: parallel over samples; min/max/max|.| are exact reductions
        // over shard-ordered partials, so the result is bitwise identical.
        std::vector<double> mono(frames, 0.0);
        const int mixShards = std::max(1, shardCount(frames));
        std::vector<double> mixMin(size_t(mixShards), 0.0);
        std::vector<double> mixMax(size_t(mixShards), 0.0);
        std::vector<double> mixAbs(size_t(mixShards), 0.0);
        parallelForRange(0, frames, [&](int64_t lo, int64_t hi, int shard) {
            double lmin = 0.0, lmax = 0.0, labs = 0.0;
            for (int64_t i = lo; i < hi; i++) {
                double s = 0.0;
                for (int ch = 0; ch < channels; ch++)
                    s += interleavedPcm[size_t(i * channels + ch)];
                const double v = s / double(channels);
                mono[size_t(i)] = v;
                lmin = std::min(lmin, v);
                lmax = std::max(lmax, v);
                labs = std::max(labs, std::abs(v));
            }
            mixMin[size_t(shard)] = lmin;
            mixMax[size_t(shard)] = lmax;
            mixAbs[size_t(shard)] = labs;
        });
        double mn = 0.0, mx = 0.0, maxAbs = 0.0;
        for (int s = 0; s < mixShards; s++) {
            mn = std::min(mn, mixMin[size_t(s)]);
            mx = std::max(mx, mixMax[size_t(s)]);
            maxAbs = std::max(maxAbs, mixAbs[size_t(s)]);
        }
        if (mn == 0.0 && mx == 0.0) return result; // pure silence
        if (!(maxAbs > 0.0)) return result;
        parallelForRange(0, frames, [&](int64_t lo, int64_t hi, int /*shard*/) {
            for (int64_t i = lo; i < hi; i++) mono[size_t(i)] /= maxAbs; // normalize to [-1, 1]
        });
        if (!prog.report(0.02)) return result;

        // ---- trim leading/trailing silence (top_db=40) ----
        const int64_t nTotal = frames;
        const int64_t nRmsFrames = nTotal / kHop + 1;
        std::vector<double> mse(nRmsFrames, 0.0);
        const int mseShards = std::max(1, shardCount(nRmsFrames));
        std::vector<double> mseMax(size_t(mseShards), 0.0);
        parallelForRange(0, nRmsFrames, [&](int64_t lo, int64_t hi, int shard) {
            double localMax = 0.0;
            for (int64_t t = lo; t < hi; t++) {
                const int64_t begin = t * kHop - kNfft / 2;
                double s = 0.0;
                for (int i = 0; i < kNfft; i++) {
                    const int64_t pos = begin + i;
                    if (pos >= 0 && pos < nTotal) {
                        const double v = mono[size_t(pos)];
                        s += v * v;
                    }
                }
                const double m = s / double(kNfft);
                mse[size_t(t)] = m;
                localMax = std::max(localMax, m);
            }
            mseMax[size_t(shard)] = localMax;
        });
        double maxMse = 0.0;
        for (double v : mseMax) maxMse = std::max(maxMse, v);
        const double refDb = 20.0 * std::log10(std::max(1e-5, std::sqrt(maxMse)));
        int64_t firstNonsilent = -1, lastNonsilent = -1;
        for (int64_t t = 0; t < nRmsFrames; t++) {
            const double db = 20.0 * std::log10(std::max(1e-5, std::sqrt(mse[size_t(t)]))) - refDb;
            if (db > -40.0) {
                if (firstNonsilent < 0) firstNonsilent = t;
                lastNonsilent = t;
            }
        }
        if (firstNonsilent < 0) return result;
        const int64_t trimStart = firstNonsilent * kHop;
        const int64_t trimEnd = std::min(nTotal, (lastNonsilent + 1) * kHop);
        if (trimEnd <= trimStart) return result;
        if (dbg != nullptr) dbg->trimOffset = trimStart;

        std::vector<double> y(mono.begin() + ptrdiff_t(trimStart), mono.begin() + ptrdiff_t(trimEnd));
        const double totalDuration = double(nTotal) / double(sampleRate);
        if (!prog.report(0.05)) return result;

        // ---- STFT + spectral features ----
        SpectralData sd;
        if (!stftPass(y, sampleRate, sd, &prog)) return result;
        const int T = sd.T;
        if (T < 8) return result;
        if (dbg != nullptr) dbg->nFrames = T;

        if (!prog.report(0.51)) return result;
        const double tuning = estimateTuning(sd.sPower, T, sampleRate);
        if (dbg != nullptr) dbg->tuning = tuning;
        if (!prog.report(0.53)) return result;
        std::vector<double> chroma;
        if (!computeChroma(sd.sPower, T, sampleRate, tuning, chroma, &prog)) return result;
        std::vector<double> cmaxDb;
        powerDbColMax(sd.sWeighed, T, cmaxDb);
        if (!prog.report(0.58)) return result;
        sd.sPower.clear();
        sd.sPower.shrink_to_fit();
        sd.sWeighed.clear();
        sd.sWeighed.shrink_to_fit();

        std::vector<double> oenv;
        onsetEnvelope(sd.mel, T, oenv);
        if (!prog.report(0.585)) return result;
        if (dbg != nullptr) {
            double ms = 0.0;
            for (double v : sd.mel) ms += v;
            dbg->melSum = ms;
            for (int i = 0; i < std::min(8, T); i++) dbg->melRow0Head.push_back(sd.mel[size_t(i)]);
            for (int i = 0; i < std::min(8, kMels); i++) dbg->melCol0.push_back(sd.mel[size_t(i) * size_t(T)]);
        }
        sd.mel.clear();
        sd.mel.shrink_to_fit();

        if (dbg != nullptr) {
            double cs = 0.0, ca = 0.0, os = 0.0;
            for (double v : chroma) { cs += v; ca += std::abs(v); }
            for (double v : oenv) os += v;
            dbg->chromaSum = cs;
            dbg->chromaAbsSum = ca;
            dbg->onsetSum = os;
            for (int i = 0; i < std::min(12, T); i++) dbg->chromaCol0.push_back(chroma[size_t(i) * size_t(T)]);
            for (int i = 0; i < std::min(12, T); i++)
                dbg->chromaColN.push_back(chroma[size_t(i) * size_t(T) + size_t(T - 1)]);
            for (int i = 0; i < std::min(8, int(oenv.size())); i++) dbg->onsetHead.push_back(oenv[size_t(i)]);
            for (int i = std::max(0, int(oenv.size()) - 8); i < int(oenv.size()); i++)
                dbg->onsetTail.push_back(oenv[size_t(i)]);
            for (int i = 0; i < std::min(8, T); i++) dbg->powerDbCmaxHead.push_back(cmaxDb[size_t(i)]);
            for (int i = std::max(0, T - 8); i < T; i++) dbg->powerDbCmaxTail.push_back(cmaxDb[size_t(i)]);
        }

        // ---- beat grid: beat_track DP + plp local maxima, unioned ----
        std::vector<double> pulse;
        plpPulse(oenv, sampleRate, pulse);
        if (!prog.report(0.595)) return result;
        if (dbg != nullptr) {
            double ps = 0.0;
            for (double v : pulse) ps += v;
            dbg->pulseSum = ps;
            dbg->pulseFull = pulse;
            dbg->onsetFull = oenv;
        }
        std::vector<int> beatsPlp;
        localMaxIndices(pulse, beatsPlp);
        const BeatTrackResult bt = beatTrack(oenv, sampleRate, dbg != nullptr ? &dbg->tgMean : nullptr);
        if (dbg != nullptr) dbg->beatsBt = bt.beats;
        std::vector<int> beats;
        beats.reserve(bt.beats.size() + beatsPlp.size());
        beats.insert(beats.end(), bt.beats.begin(), bt.beats.end());
        beats.insert(beats.end(), beatsPlp.begin(), beatsPlp.end());
        std::sort(beats.begin(), beats.end());
        beats.erase(std::unique(beats.begin(), beats.end()), beats.end());
        if (beats.empty()) return result;
        if (!prog.report(0.60)) return result;
        if (dbg != nullptr) {
            dbg->bpm = bt.bpm;
            dbg->framesPerBeat = int(detail::rint(double(sampleRate) / double(kHop) * 60.0 / bt.bpm));
            dbg->nBeatsPlp = int(beatsPlp.size());
            for (int i = 0; i < int(std::min<size_t>(20, beatsPlp.size())); i++)
                dbg->beatsPlpHead.push_back(beatsPlp[size_t(i)]);
            dbg->beatsPlpFull = beatsPlp;
            dbg->beats = beats;
        }

        // ---- loop duration bounds ----
        const int minLoopFrames =
            std::max(1, timeToFrames(double(int(0.35 * totalDuration)), sampleRate));
        const int maxLoopFrames = timeToFrames(totalDuration, sampleRate);

        std::vector<PairRaw> pairs;
        if (!findPairs(chroma, T, beats, cmaxDb, minLoopFrames, maxLoopFrames, pairs, &prog)) return result;
        if (dbg != nullptr) dbg->nPairsBeforePrune = int(pairs.size());
        if (pairs.empty()) return result;
        prunePairs(pairs);
        if (dbg != nullptr) dbg->nPairsAfterPrune = int(pairs.size());
        if (pairs.empty()) return result;
        if (!prog.report(0.72)) return result;

        // ---- scoring ----
        const double bpm = bt.bpm > 0.0 ? bt.bpm : 120.0;
        const double secondsToTest = 12.0 / (bpm / 60.0);
        int testOffset = int(timeToSamplesTrunc(secondsToTest, sampleRate) / kHop);
        if (testOffset > T) testOffset = T / 4;
        if (testOffset < 1) return result;
        if (dbg != nullptr) dbg->testOffset = testOffset;
        const int wStart = std::max(2, testOffset / 12);
        std::vector<double> w(testOffset);
        if (testOffset == 1) {
            w[0] = double(wStart);
        } else {
            const double ls = std::log(double(wStart));
            const double le = std::log(1.0);
            for (int i = 0; i < testOffset; i++)
                w[size_t(i)] = std::exp(ls + (le - ls) * double(i) / double(testOffset - 1));
        }
        if (dbg != nullptr)
            for (int i = 0; i < int(std::min<size_t>(6, w.size())); i++) dbg->weightsHead.push_back(w[size_t(i)]);

        std::vector<double> colNorm(T, 0.0);
        for (int t = 0; t < T; t++) {
            double s = 0.0;
            for (int c = 0; c < 12; c++) {
                const double v = chroma[size_t(c) * size_t(T) + size_t(t)];
                s += v * v;
            }
            colNorm[size_t(t)] = std::sqrt(s);
        }
        std::vector<double> wrev(w.rbegin(), w.rend());

        struct ScoredPair {
            PairRaw p;
            double score;
        };
        // v6 §10.4-2: scoring parallel by candidate index (scored[i] disjoint writes;
        // stable_sort input sequence identical to serial push order)
        std::vector<ScoredPair> scored;
        scored.resize(pairs.size());
        parallelForRange(0, int64_t(pairs.size()), [&](int64_t lo, int64_t hi, int /*shard*/) {
            for (int64_t i = lo; i < hi; i++) {
                const PairRaw& p = pairs[size_t(i)];
                const double fwd = subseqSim(p.startF, p.endF, testOffset, w, chroma, colNorm, T);
                const double bwd = subseqSim(p.startF, p.endF, -testOffset, wrev, chroma, colNorm, T);
                scored[size_t(i)] = ScoredPair{p, std::max(fwd, bwd)};
            }
        });
        if (!prog.report(0.95)) return result;
        std::stable_sort(scored.begin(), scored.end(),
                         [](const ScoredPair& a, const ScoredPair& b) { return a.score > b.score; });

        // ---- frame -> sample (trim offset + zero-crossing snap) ----
        int emitted = 0;
        for (const ScoredPair& sp : scored) {
            if (emitted >= maxCandidates) break;
            int sf = sp.p.startF;
            int ef = sp.p.endF;
            if (trimStart > 0) {
                sf = int((int64_t(sf) * kHop + trimStart) / kHop);
                ef = int((int64_t(ef) * kHop + trimStart) / kHop);
            }
            const int64_t startSample = int64_t(sf) * kHop;
            const int64_t endSample = int64_t(ef) * kHop;
            LoopCandidate c;
            c.loopStart = nearestZeroCrossing(interleavedPcm, channels, frames, sampleRate, startSample);
            c.loopEnd = nearestZeroCrossing(interleavedPcm, channels, frames, sampleRate, endSample);
            c.score = sp.score;
            c.noteDistance = sp.p.nd;
            c.loudnessDifference = sp.p.ld;
            result.candidates.push_back(c);
            emitted++;
            prog.report(0.97);
        }
        if (dbg != nullptr) {
            int count = 0;
            for (const ScoredPair& sp : scored) {
                if (count >= maxCandidates) break;
                int sf = sp.p.startF;
                int ef = sp.p.endF;
                if (trimStart > 0) {
                    sf = int((int64_t(sf) * kHop + trimStart) / kHop);
                    ef = int((int64_t(ef) * kHop + trimStart) / kHop);
                }
                PairInfo pi;
                pi.startFrame = sf;
                pi.endFrame = ef;
                pi.loopStart = result.candidates[size_t(count)].loopStart;
                pi.loopEnd = result.candidates[size_t(count)].loopEnd;
                pi.noteDistance = sp.p.nd;
                pi.loudnessDifference = sp.p.ld;
                pi.score = sp.score;
                dbg->pairs.push_back(pi);
                count++;
            }
        }
        prog.report(1.0);
        return result;
    } catch (...) {
        Result fail;
        fail.totalFrames = frames > 0 ? frames : 0;
        fail.sampleRate = sampleRate;
        return fail; // never let exceptions escape [contract 2.1]
    }
}
} // namespace debug

Result analyze(const float* interleavedPcm, int channels, int64_t frames, int sampleRate,
               int maxCandidates, const ProgressFn& onProgress) {
    return debug::analyzeWithDebug(interleavedPcm, channels, frames, sampleRate, maxCandidates,
                                   nullptr, onProgress);
}

} // namespace loop_engine
