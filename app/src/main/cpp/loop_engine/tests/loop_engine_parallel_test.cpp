// Host-side regression test for the loop_engine parallel sharding (v7 §10.4-3).
//
// Contract: every thread count must produce results that are bitwise identical
// to the serial run (LOOP_ENGINE_THREADS=1) — candidates AND all DebugInfo
// feature fields (chroma, onset, pulse, tempo aggregate, beat grids, pairs).
// This is what makes the parallelisation safe for the "top1 must match
// PyMusicLooper" regression: the arithmetic itself is unchanged, only the
// scheduling is.
//
// Build/run (host, no Android device needed):
//   cmake -S app/src/main/cpp/loop_engine/tests -B build/loop-engine-tests
//   cmake --build build/loop-engine-tests --config Release
//   ./build/loop-engine-tests/loop_engine_parallel_test      (MSVC: path under Release/)
//
// Exit code 0 = all checks passed.

#include "loop_engine.h"
#include "loop_engine_internal.h"

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

namespace {

int gFailures = 0;

void check(bool cond, const std::string& what) {
    if (!cond) {
        std::printf("FAIL: %s\n", what.c_str());
        gFailures++;
    }
}

void setThreads(int n) {
    char buf[16];
    std::snprintf(buf, sizeof(buf), "%d", n);
#ifdef _WIN32
    _putenv_s("LOOP_ENGINE_THREADS", buf);
#else
    setenv("LOOP_ENGINE_THREADS", buf, 1);
#endif
}

uint32_t nextRand(uint32_t& state) {
    state = state * 1664525u + 1013904223u;
    return state;
}

double noise(uint32_t& state) {
    return (double(nextRand(state) >> 8) / 16777216.0) * 2.0 - 1.0;
}

struct Pcm {
    std::vector<float> data;
    int channels = 1;
    int sampleRate = 44100;
    int64_t frames = 0;
};

// Deterministic synthetic 4/4 loop material: kick every 0.5 s, hats every
// 0.125 s, a two-chord alternation every 2 s and an 8-step melody. A 4 s
// pattern repeats, so the engine has real beat/loop structure to find.
Pcm makeMusic(double seconds, int sr, int channels) {
    Pcm pcm;
    pcm.channels = channels;
    pcm.sampleRate = sr;
    pcm.frames = static_cast<int64_t>(seconds * sr);
    pcm.data.assign(static_cast<size_t>(pcm.frames) * static_cast<size_t>(channels), 0.0f);
    uint32_t seed = 12345u;
    const double twoPi = 6.283185307179586476925286766559;
    const double loopLen = 4.0;
    const double chords[2][3] = {{220.0, 277.183, 329.628}, {246.942, 311.127, 369.994}};
    const double melody[8] = {440.0, 523.251, 587.330, 659.255, 783.991, 659.255, 587.330, 523.251};
    for (int64_t i = 0; i < pcm.frames; i++) {
        const double t = double(i) / double(sr);
        const double tl = std::fmod(t, loopLen);
        const int section = (int(std::floor(tl / 2.0))) & 1;
        double v = 0.0;
        for (int n = 0; n < 3; n++) v += 0.18 * std::sin(twoPi * chords[section][n] * t);
        const int step = int(std::floor(tl / 0.25)) & 7;
        const double env = std::exp(-std::fmod(tl, 0.25) * 6.0);
        v += 0.22 * env * std::sin(twoPi * melody[step] * t);
        const double kt = std::fmod(tl, 0.5);
        v += 0.55 * std::exp(-kt * 18.0) * std::sin(twoPi * (55.0 - 25.0 * kt) * t);
        const double ht = std::fmod(tl, 0.125);
        v += 0.06 * std::exp(-ht * 80.0) * noise(seed);
        if (v > 1.0) v = 1.0;
        if (v < -1.0) v = -1.0;
        if (channels == 1) {
            pcm.data[static_cast<size_t>(i)] = static_cast<float>(v);
        } else {
            for (int ch = 0; ch < channels; ch++) {
                const double pan = (ch == 0) ? 1.0 : 0.85;
                const double detune = (ch == 0) ? 1.0 : 1.002;
                pcm.data[static_cast<size_t>(i * channels + ch)] =
                    static_cast<float>(v * pan * detune);
            }
        }
    }
    return pcm;
}

Pcm makeTone(double seconds, int sr) {
    Pcm pcm;
    pcm.channels = 1;
    pcm.sampleRate = sr;
    pcm.frames = static_cast<int64_t>(seconds * sr);
    pcm.data.assign(static_cast<size_t>(pcm.frames), 0.0f);
    const double twoPi = 6.283185307179586476925286766559;
    for (int64_t i = 0; i < pcm.frames; i++) {
        const double t = double(i) / double(sr);
        pcm.data[static_cast<size_t>(i)] =
            static_cast<float>(0.4 * std::sin(twoPi * 440.0 * t) + 0.2 * std::sin(twoPi * 660.0 * t));
    }
    return pcm;
}

Pcm makeSilentEdges(double seconds, int sr, int channels) {
    Pcm pcm = makeMusic(seconds, sr, channels);
    const int64_t edge = static_cast<int64_t>(0.6 * sr);
    for (int64_t i = 0; i < edge && i < pcm.frames; i++) {
        for (int ch = 0; ch < channels; ch++) {
            pcm.data[static_cast<size_t>(i * channels + ch)] = 0.0f;
            pcm.data[static_cast<size_t>((pcm.frames - 1 - i) * channels + ch)] = 0.0f;
        }
    }
    return pcm;
}

struct Snapshot {
    loop_engine::Result result;
    loop_engine::debug::DebugInfo dbg;
};

Snapshot run(const Pcm& pcm, int threads) {
    setThreads(threads);
    Snapshot s;
    s.result = loop_engine::debug::analyzeWithDebug(
        pcm.data.data(), pcm.channels, pcm.frames, pcm.sampleRate, 10, &s.dbg,
        loop_engine::ProgressFn());
    return s;
}

bool sameDouble(double a, double b) {
    return std::memcmp(&a, &b, sizeof(double)) == 0;
}

void checkDoubles(const std::vector<double>& a, const std::vector<double>& b,
                  const std::string& label) {
    check(a.size() == b.size(), label + " size");
    const size_t n = a.size() < b.size() ? a.size() : b.size();
    for (size_t i = 0; i < n; i++) {
        if (!sameDouble(a[i], b[i])) {
            check(false, label + "[" + std::to_string(i) + "] differs");
            return;
        }
    }
}

void checkInts(const std::vector<int>& a, const std::vector<int>& b, const std::string& label) {
    check(a == b, label + " differs");
}

void compare(const Snapshot& ref, const Snapshot& got, const std::string& label) {
    check(ref.result.totalFrames == got.result.totalFrames, label + " totalFrames");
    check(ref.result.sampleRate == got.result.sampleRate, label + " sampleRate");
    check(ref.result.candidates.size() == got.result.candidates.size(), label + " candidate count");
    const size_t nc = ref.result.candidates.size() < got.result.candidates.size()
                          ? ref.result.candidates.size()
                          : got.result.candidates.size();
    for (size_t i = 0; i < nc; i++) {
        const loop_engine::LoopCandidate& a = ref.result.candidates[i];
        const loop_engine::LoopCandidate& b = got.result.candidates[i];
        check(a.loopStart == b.loopStart, label + " candidate start");
        check(a.loopEnd == b.loopEnd, label + " candidate end");
        check(sameDouble(a.score, b.score), label + " candidate score");
        check(sameDouble(a.noteDistance, b.noteDistance), label + " candidate noteDistance");
        check(sameDouble(a.loudnessDifference, b.loudnessDifference),
              label + " candidate loudnessDifference");
    }

    const loop_engine::debug::DebugInfo& a = ref.dbg;
    const loop_engine::debug::DebugInfo& b = got.dbg;
    check(a.trimOffset == b.trimOffset, label + " trimOffset");
    check(a.nFrames == b.nFrames, label + " nFrames");
    check(sameDouble(a.tuning, b.tuning), label + " tuning");
    check(sameDouble(a.bpm, b.bpm), label + " bpm");
    check(a.framesPerBeat == b.framesPerBeat, label + " framesPerBeat");
    check(a.nBeatsPlp == b.nBeatsPlp, label + " nBeatsPlp");
    check(a.nPairsBeforePrune == b.nPairsBeforePrune, label + " nPairsBeforePrune");
    check(a.nPairsAfterPrune == b.nPairsAfterPrune, label + " nPairsAfterPrune");
    check(sameDouble(a.chromaSum, b.chromaSum), label + " chromaSum");
    check(sameDouble(a.chromaAbsSum, b.chromaAbsSum), label + " chromaAbsSum");
    check(sameDouble(a.onsetSum, b.onsetSum), label + " onsetSum");
    check(sameDouble(a.pulseSum, b.pulseSum), label + " pulseSum");
    check(sameDouble(a.melSum, b.melSum), label + " melSum");
    check(a.testOffset == b.testOffset, label + " testOffset");

    checkInts(a.beats, b.beats, label + " beats");
    checkInts(a.beatsPlpHead, b.beatsPlpHead, label + " beatsPlpHead");
    checkInts(a.beatsPlpFull, b.beatsPlpFull, label + " beatsPlpFull");
    checkInts(a.beatsBt, b.beatsBt, label + " beatsBt");

    checkDoubles(a.chromaCol0, b.chromaCol0, label + " chromaCol0");
    checkDoubles(a.chromaColN, b.chromaColN, label + " chromaColN");
    checkDoubles(a.onsetHead, b.onsetHead, label + " onsetHead");
    checkDoubles(a.onsetTail, b.onsetTail, label + " onsetTail");
    checkDoubles(a.powerDbCmaxHead, b.powerDbCmaxHead, label + " powerDbCmaxHead");
    checkDoubles(a.powerDbCmaxTail, b.powerDbCmaxTail, label + " powerDbCmaxTail");
    checkDoubles(a.weightsHead, b.weightsHead, label + " weightsHead");
    checkDoubles(a.pulseFull, b.pulseFull, label + " pulseFull");
    checkDoubles(a.onsetFull, b.onsetFull, label + " onsetFull");
    checkDoubles(a.tgMean, b.tgMean, label + " tgMean");
    checkDoubles(a.melRow0Head, b.melRow0Head, label + " melRow0Head");
    checkDoubles(a.melCol0, b.melCol0, label + " melCol0");

    check(a.pairs.size() == b.pairs.size(), label + " pair count");
    const size_t np = a.pairs.size() < b.pairs.size() ? a.pairs.size() : b.pairs.size();
    for (size_t i = 0; i < np; i++) {
        check(a.pairs[i].startFrame == b.pairs[i].startFrame, label + " pair startFrame");
        check(a.pairs[i].endFrame == b.pairs[i].endFrame, label + " pair endFrame");
        check(a.pairs[i].loopStart == b.pairs[i].loopStart, label + " pair loopStart");
        check(a.pairs[i].loopEnd == b.pairs[i].loopEnd, label + " pair loopEnd");
        check(sameDouble(a.pairs[i].noteDistance, b.pairs[i].noteDistance), label + " pair nd");
        check(sameDouble(a.pairs[i].loudnessDifference, b.pairs[i].loudnessDifference),
              label + " pair ld");
        check(sameDouble(a.pairs[i].score, b.pairs[i].score), label + " pair score");
    }
}

void testSignal(const char* name, const Pcm& pcm) {
    const Snapshot serial = run(pcm, 1);
    const int threadCounts[] = {1, 2, 3, 4, 8, 16};
    for (int threads : threadCounts) {
        const Snapshot got = run(pcm, threads);
        compare(serial, got, std::string(name) + " threads=" + std::to_string(threads));
    }
    // Determinism: same thread count twice.
    const Snapshot again = run(pcm, 4);
    compare(run(pcm, 4), again, std::string(name) + " repeat");
    std::printf("  %s: frames=%lld candidates=%zu ok\n", name,
                (long long)pcm.frames, serial.result.candidates.size());
}

void testCancellation(const Pcm& pcm) {
    setThreads(4);
    std::vector<float> seen;
    loop_engine::Result r = loop_engine::debug::analyzeWithDebug(
        pcm.data.data(), pcm.channels, pcm.frames, pcm.sampleRate, 10, nullptr,
        [&](float fraction) {
            seen.push_back(fraction);
            return fraction < 0.4f; // cancel after the first third
        });
    check(r.candidates.empty(), "cancel -> empty candidates");
    check(!seen.empty(), "cancel -> progress observed");
    for (size_t i = 1; i < seen.size(); i++) {
        check(seen[i] >= seen[i - 1], "progress monotonic");
        check(seen[i] <= 1.0f, "progress in [0,1]");
    }
    std::printf("  cancel: callbacks=%zu ok\n", seen.size());
}

void testDegenerate() {
    // Too short for a single STFT frame: engine must return empty, both serial
    // and parallel, without crashing.
    Pcm tiny;
    tiny.channels = 1;
    tiny.sampleRate = 44100;
    tiny.frames = 100;
    tiny.data.assign(100, 0.25f);
    const Snapshot a = run(tiny, 1);
    const Snapshot b = run(tiny, 8);
    compare(a, b, "tiny threads=8");
    check(a.result.candidates.empty(), "tiny -> empty candidates");

    Pcm silent;
    silent.channels = 1;
    silent.sampleRate = 44100;
    silent.frames = 44100;
    silent.data.assign(44100, 0.0f);
    compare(run(silent, 1), run(silent, 8), "silence threads=8");
    std::printf("  degenerate inputs ok\n");
}

} // namespace

int main() {
    std::printf("loop_engine parallel/serial bit-exactness test\n");
    const int sr = 44100;
    testSignal("music-12s-stereo", makeMusic(12.0, sr, 2));
    testSignal("music-6s-mono", makeMusic(6.0, sr, 1));
    testSignal("music-9s-silent-edges", makeSilentEdges(9.0, sr, 2));
    testSignal("tone-3s", makeTone(3.0, sr));
    testCancellation(makeMusic(12.0, sr, 2));
    testDegenerate();

    if (gFailures == 0) {
        std::printf("ALL PASS\n");
        return 0;
    }
    std::printf("%d FAILURE(S)\n", gFailures);
    return 1;
}
