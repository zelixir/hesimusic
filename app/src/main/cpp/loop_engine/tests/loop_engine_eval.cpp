// Offline evaluation CLI for the loop engine (host-only).
//
// Reads a WAV file (PCM 8/16/24/32-bit or IEEE float 32/64), runs the engine
// once, and reports both the engine candidates and an INDEPENDENT seam-quality
// measurement computed here (not from engine internals), so old vs new ranking
// can be compared on the same objective scale.
//
// Build:
//   cmake -S app/src/main/cpp/loop_engine/tests -B build/loop-engine-tests
//   cmake --build build/loop-engine-tests --config Release
// Run:
//   loop_engine_eval <file.wav> [maxCandidates] [label]
//
// The JSON line on stdout is what tools/scripts consume; diagnostics go to stderr.

#include "loop_engine.h"
#include "loop_engine_internal.h"

#include "fft.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

namespace {

struct Wav {
    int sampleRate = 0;
    int channels = 0;
    std::vector<float> interleaved; // as read (raw channels)
    std::vector<float> mono;        // arithmetic mixdown
    int64_t frames = 0;
};

uint32_t rd32(const unsigned char* p) {
    return uint32_t(p[0]) | (uint32_t(p[1]) << 8) | (uint32_t(p[2]) << 16) | (uint32_t(p[3]) << 24);
}
uint16_t rd16(const unsigned char* p) { return uint16_t(p[0]) | (uint16_t(p[1]) << 8); }

bool readWav(const char* path, Wav& out, std::string& err) {
    FILE* f = std::fopen(path, "rb");
    if (f == nullptr) { err = "open failed"; return false; }
    std::fseek(f, 0, SEEK_END);
    const long size = std::ftell(f);
    std::fseek(f, 0, SEEK_SET);
    if (size < 44) { std::fclose(f); err = "too small"; return false; }
    std::vector<unsigned char> buf(static_cast<size_t>(size), 0);
    if (std::fread(buf.data(), 1, buf.size(), f) != buf.size()) {
        std::fclose(f); err = "short read"; return false;
    }
    std::fclose(f);
    if (std::memcmp(buf.data(), "RIFF", 4) != 0 || std::memcmp(buf.data() + 8, "WAVE", 4) != 0) {
        err = "not RIFF/WAVE"; return false;
    }
    int format = 0, channels = 0, bits = 0, sampleRate = 0;
    const unsigned char* data = nullptr;
    size_t dataSize = 0;
    size_t pos = 12;
    while (pos + 8 <= buf.size()) {
        const unsigned char* ck = buf.data() + pos;
        const uint32_t ckSize = rd32(ck + 4);
        const size_t body = pos + 8;
        if (std::memcmp(ck, "fmt ", 4) == 0 && ckSize >= 16 && body + 16 <= buf.size()) {
            format = int(rd16(buf.data() + body));
            channels = int(rd16(buf.data() + body + 2));
            sampleRate = int(rd32(buf.data() + body + 4));
            bits = int(rd16(buf.data() + body + 14));
            if (format == 0xFFFE && ckSize >= 26 && body + 26 <= buf.size()) {
                format = int(rd16(buf.data() + body + 24)); // subformat GUID first 2 bytes
            }
        } else if (std::memcmp(ck, "data", 4) == 0) {
            data = buf.data() + body;
            dataSize = std::min<size_t>(ckSize, buf.size() - body);
        }
        pos = body + ckSize + (ckSize & 1u);
    }
    if (data == nullptr || channels <= 0 || bits <= 0 || sampleRate <= 0) {
        err = "missing fmt/data"; return false;
    }
    const size_t bytesPerSample = size_t(bits) / 8u;
    if (bytesPerSample == 0) { err = "bad bits"; return false; }
    const size_t frameBytes = bytesPerSample * size_t(channels);
    const size_t nFrames = dataSize / frameBytes;
    out.sampleRate = sampleRate;
    out.channels = channels;
    out.frames = int64_t(nFrames);
    out.interleaved.resize(nFrames * size_t(channels));
    for (size_t i = 0; i < nFrames; i++) {
        for (int ch = 0; ch < channels; ch++) {
            const unsigned char* p = data + i * frameBytes + size_t(ch) * bytesPerSample;
            double v = 0.0;
            if (format == 3) {
                if (bits == 32) { float x; std::memcpy(&x, p, 4); v = x; }
                else if (bits == 64) { double x; std::memcpy(&x, p, 8); v = x; }
            } else if (format == 1) {
                if (bits == 8) v = (double(p[0]) - 128.0) / 128.0;
                else if (bits == 16) v = double(int16_t(rd16(p))) / 32768.0;
                else if (bits == 24) {
                    int32_t x = int32_t(p[0]) | (int32_t(p[1]) << 8) | (int32_t(p[2]) << 16);
                    if (x & 0x800000) x |= ~0xFFFFFF;
                    v = double(x) / 8388608.0;
                } else if (bits == 32) v = double(int32_t(rd32(p))) / 2147483648.0;
            }
            out.interleaved[i * size_t(channels) + size_t(ch)] = float(v);
        }
    }
    out.mono.resize(nFrames);
    for (size_t i = 0; i < nFrames; i++) {
        double s = 0.0;
        for (int ch = 0; ch < channels; ch++) s += out.interleaved[i * size_t(channels) + size_t(ch)];
        out.mono[i] = float(s / double(channels));
    }
    return true;
}

double rmsRange(const std::vector<float>& x, int64_t begin, int64_t end) {
    begin = std::max<int64_t>(0, begin);
    end = std::min<int64_t>(int64_t(x.size()), end);
    if (end <= begin) return 0.0;
    double s = 0.0;
    for (int64_t i = begin; i < end; i++) s += double(x[size_t(i)]) * double(x[size_t(i)]);
    return std::sqrt(s / double(end - begin));
}

// Frame magnitude spectrum (normalized to unit L2), periodic Hann, zero-padded.
void unitMagnitude(const std::vector<float>& x, int64_t start, int nfft, std::vector<double>& out) {
    std::vector<double> frame(static_cast<size_t>(nfft), 0.0);
    std::vector<double> win;
    loop_engine::detail::hannPeriodic(nfft, win);
    for (int i = 0; i < nfft; i++) {
        const int64_t p = start + i;
        const double v = (p >= 0 && p < int64_t(x.size())) ? double(x[size_t(p)]) : 0.0;
        frame[size_t(i)] = v * win[size_t(i)];
    }
    std::vector<loop_engine::detail::Cdp> spec(size_t(nfft / 2 + 1));
    loop_engine::detail::rfft(frame.data(), nfft, spec.data());
    out.resize(size_t(nfft / 2 + 1));
    double norm = 0.0;
    for (size_t k = 0; k < out.size(); k++) {
        const double re = spec[k].real(), im = spec[k].imag();
        out[k] = std::sqrt(re * re + im * im);
        norm += out[k] * out[k];
    }
    norm = std::sqrt(norm);
    if (norm > 1e-12) for (double& v : out) v /= norm;
}

double cosineDistance(const std::vector<double>& a, const std::vector<double>& b) {
    double dot = 0.0;
    for (size_t k = 0; k < a.size() && k < b.size(); k++) dot += a[k] * b[k];
    return std::max(0.0, std::min(1.0, 1.0 - dot));
}

struct SeamMetrics {
    double corr = 0.0;       // peak normalized xcorr of the tail windows
    double rmsJumpDb = 0.0;  // |rms after A - rms before B| in dB
    double fluxRatio = 1.0;  // seam spectral distance / local mean distance
    double stepNorm = 0.0;   // boundary sample step / local rms
    double seamScore = 0.0;  // the same 0..1 blend the engine uses
};

SeamMetrics measureSeam(const std::vector<float>& x, int64_t a, int64_t b, int sr) {
    SeamMetrics m;
    const int64_t total = int64_t(x.size());
    if (a <= 0 || b <= 0 || a >= total || b >= total || b <= a) return m;
    const int64_t win = std::max<int64_t>(64, int64_t(sr) * 50 / 1000);
    const int64_t lagMax = std::max<int64_t>(8, int64_t(sr) * 10 / 1000);

    // --- phase continuity: tail at B vs tail at A (both are "the 50 ms before the cut")
    const int64_t w = std::min<int64_t>(win, std::min(a, b));
    if (w >= 32) {
        double eb = 0.0, ea = 0.0;
        for (int64_t i = 0; i < w; i++) {
            const double vb = double(x[size_t(b - w + i)]);
            const double va = double(x[size_t(a - w + i)]);
            eb += vb * vb;
            ea += va * va;
        }
        const double denom = std::sqrt(eb * ea);
        if (denom > 1e-9) {
            double best = -1.0;
            for (int64_t lag = -lagMax; lag <= lagMax; lag++) {
                const int64_t lo = std::max<int64_t>(0, lag);
                const int64_t hi = std::min<int64_t>(w - 1, w - 1 + lag);
                double num = 0.0;
                for (int64_t i = lo; i <= hi; i++) {
                    num += double(x[size_t(b - w + i)]) * double(x[size_t(a - w + i - lag)]);
                }
                best = std::max(best, num / denom);
            }
            m.corr = best;
        }
    }

    // --- short-term energy on both sides of the seam
    const double rb = rmsRange(x, b - win, b);
    const double ra = rmsRange(x, a, a + win);
    if (rb > 1e-6 && ra > 1e-6) m.rmsJumpDb = std::fabs(20.0 * std::log10(ra / rb));
    else m.rmsJumpDb = 60.0;

    // --- boundary step, normalized by the local rms
    const double localRms = std::max(1e-6, 0.5 * (rb + ra));
    m.stepNorm = std::fabs(double(x[size_t(a)]) - double(x[size_t(b - 1)])) / localRms;

    // --- spectral flux at the seam vs local consecutive-frame flux
    const int nfft = 1024;
    const int hop = 256;
    std::vector<double> seamA, seamB, tmp1, tmp2;
    unitMagnitude(x, b - nfft, nfft, seamB); // frame ending at the cut
    unitMagnitude(x, a, nfft, seamA);        // frame starting after the jump
    const double seamDist = cosineDistance(seamA, seamB);
    double localSum = 0.0;
    int localCount = 0;
    for (int k = 3; k >= 1; k--) { // 3 consecutive distances on the left side
        unitMagnitude(x, b - nfft - int64_t(k - 1) * hop, nfft, tmp1);
        unitMagnitude(x, b - nfft - int64_t(k) * hop, nfft, tmp2);
        localSum += cosineDistance(tmp1, tmp2);
        localCount++;
    }
    for (int k = 0; k < 3; k++) { // 3 on the right side
        unitMagnitude(x, a + int64_t(k) * hop, nfft, tmp1);
        unitMagnitude(x, a + int64_t(k + 1) * hop, nfft, tmp2);
        localSum += cosineDistance(tmp1, tmp2);
        localCount++;
    }
    const double localMean = localCount > 0 ? localSum / double(localCount) : 0.0;
    m.fluxRatio = seamDist / std::max(localMean, 1e-4);

    // --- blend (identical formula in loop_engine.cpp so old/new are comparable)
    const double corrScore = std::max(0.0, std::min(1.0, (m.corr - 0.25) / 0.75));
    const double energyScore = std::exp(-m.rmsJumpDb / 3.0);
    const double timbreScore = 1.0 / (1.0 + std::max(0.0, m.fluxRatio - 1.0));
    m.seamScore = 0.50 * corrScore + 0.30 * energyScore + 0.20 * timbreScore;
    return m;
}

std::string jsonEscape(const std::string& s) {
    std::string o;
    for (char c : s) {
        if (c == '\\' || c == '"') o.push_back('\\');
        o.push_back(c);
    }
    return o;
}

} // namespace

int main(int argc, char** argv) {
    if (argc < 2) {
        std::fprintf(stderr, "usage: %s <file.wav> [maxCandidates] [label]\n", argv[0]);
        return 2;
    }
    const int maxCandidates = argc >= 3 ? std::max(1, std::atoi(argv[2])) : 10;
    const std::string label = argc >= 4 ? argv[3] : "";
    Wav wav;
    std::string err;
    if (!readWav(argv[1], wav, err)) {
        std::fprintf(stderr, "wav read failed: %s\n", err.c_str());
        return 3;
    }
    loop_engine::debug::DebugInfo dbg;
    const auto t0 = std::chrono::steady_clock::now();
    loop_engine::Result res = loop_engine::debug::analyzeWithDebug(
            wav.interleaved.data(), wav.channels, wav.frames, wav.sampleRate, maxCandidates,
            &dbg, loop_engine::ProgressFn());
    const auto t1 = std::chrono::steady_clock::now();
    const double ms = std::chrono::duration<double, std::milli>(t1 - t0).count();

    std::printf("{\"label\":\"%s\",\"file\":\"%s\",\"sr\":%d,\"frames\":%lld,"
                "\"analysisMs\":%.2f,\"bpm\":%.4f,\"trimOffset\":%lld,"
                "\"pairsBefore\":%d,\"pairsAfter\":%d,\"candidates\":[",
                jsonEscape(label).c_str(), jsonEscape(argv[1]).c_str(), wav.sampleRate,
                (long long)wav.frames, ms, dbg.bpm, (long long)dbg.trimOffset,
                dbg.nPairsBeforePrune, dbg.nPairsAfterPrune);
    const size_t n = res.candidates.size();
    for (size_t i = 0; i < n; i++) {
        const loop_engine::LoopCandidate& c = res.candidates[i];
        const SeamMetrics m = measureSeam(wav.mono, c.loopStart, c.loopEnd, wav.sampleRate);
        std::printf("%s{\"i\":%zu,\"start\":%lld,\"end\":%lld,\"startMs\":%.3f,"
                    "\"endMs\":%.3f,\"lenMs\":%.3f,\"score\":%.6f,\"nd\":%.6f,"
                    "\"ld\":%.6f,\"corr\":%.6f,\"rmsJumpDb\":%.4f,"
                    "\"fluxRatio\":%.4f,\"stepNorm\":%.6f,\"seamScore\":%.6f}",
                    i > 0 ? "," : "", i, (long long)c.loopStart, (long long)c.loopEnd,
                    1000.0 * double(c.loopStart) / double(wav.sampleRate),
                    1000.0 * double(c.loopEnd) / double(wav.sampleRate),
                    1000.0 * double(c.loopEnd - c.loopStart) / double(wav.sampleRate),
                    c.score, c.noteDistance, c.loudnessDifference, m.corr, m.rmsJumpDb,
                    m.fluxRatio, m.stepNorm, m.seamScore);
    }
    std::printf("]}\n");
    return 0;
}
