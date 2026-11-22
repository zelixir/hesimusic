// Regression test for the production ranking of the loop engine.
//
// Requirement (task A): production must rank candidates exactly like the initial
// (pre-v8) engine — pure chroma-sequence similarity — while the v8 seam fusion
// stays compiled in for the task-B experiment.
//
// This test pins:
//   1. default env = production: no seam stage, seamQuality == 0 (unknown),
//      score == legacyScore, candidates in non-increasing legacy order;
//   2. the FINAL RANKING equals the legacyScore ranking: with the seam stage
//      switched on but the blend weight at its default 0.0, the emitted
//      candidates are the same candidates in the same order with bitwise equal
//      legacyScore (the seam measurements run, the ranking does not change);
//   3. the retained experiment still works: LOOP_ENGINE_SEAM_WEIGHT=0.50 writes
//      the v8 fused score (score == 0.5*legacy + 0.5*seam).
//
// Build/run (host, no Android device needed):
//   cmake -S app/src/main/cpp/loop_engine/tests -B build/loop-engine-tests
//   cmake --build build/loop-engine-tests --config Release --target loop_engine_ranking_test
//   ./build/loop-engine-tests/Release/loop_engine_ranking_test
//
// Exit code 0 = all checks passed.

#include "loop_engine.h"
#include "loop_engine_internal.h"

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
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

// Pin the seam switches: clearSeamEnv() is the production default (stage off,
// weight 0.0); setSeam(on, weight) is the explicit experiment configuration.
void clearSeamEnv() {
#ifdef _WIN32
    _putenv_s("LOOP_ENGINE_SEAM", "");
    _putenv_s("LOOP_ENGINE_SEAM_WEIGHT", "");
#else
    unsetenv("LOOP_ENGINE_SEAM");
    unsetenv("LOOP_ENGINE_SEAM_WEIGHT");
#endif
}

void setSeam(int on, const char* weight) {
#ifdef _WIN32
    _putenv_s("LOOP_ENGINE_SEAM", on ? "1" : "0");
    _putenv_s("LOOP_ENGINE_SEAM_WEIGHT", weight);
#else
    setenv("LOOP_ENGINE_SEAM", on ? "1" : "0", 1);
    if (weight[0] == '\0') unsetenv("LOOP_ENGINE_SEAM_WEIGHT");
    else setenv("LOOP_ENGINE_SEAM_WEIGHT", weight, 1);
#endif
}

double noise(uint32_t& state) {
    state = state * 1664525u + 1013904223u;
    return (double(state >> 8) / 16777216.0) * 2.0 - 1.0;
}

// Deterministic synthetic 4/4 loop material (same shape as the other host
// tests): a 4 s pattern repeats, so the engine has real beat/loop structure.
std::vector<float> makeMusic(double seconds, int sr) {
    const int64_t frames = static_cast<int64_t>(seconds * sr);
    std::vector<float> pcm(static_cast<size_t>(frames), 0.0f);
    uint32_t seed = 12345u;
    const double twoPi = 6.283185307179586476925286766559;
    const double loopLen = 4.0;
    const double chords[2][3] = {{220.0, 277.183, 329.628}, {246.942, 311.127, 369.994}};
    const double melody[8] = {440.0, 523.251, 587.330, 659.255, 783.991, 659.255, 587.330, 523.251};
    for (int64_t i = 0; i < frames; i++) {
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
        pcm[static_cast<size_t>(i)] = static_cast<float>(v);
    }
    return pcm;
}

struct RunResult {
    loop_engine::Result result;
    loop_engine::debug::DebugInfo debug;
};

RunResult run(const std::vector<float>& pcm, int sr, int maxCandidates) {
    RunResult out;
    out.result = loop_engine::debug::analyzeWithDebug(
        pcm.data(), 1, static_cast<int64_t>(pcm.size()), sr, maxCandidates,
        &out.debug, loop_engine::ProgressFn());
    return out;
}

// The identity of a ranked candidate across runs: the beat-grid pair it came
// from plus the pre-blend score. Positions are deliberately NOT compared: the
// seam experiment may refine them, the ranking must not change.
void checkSameLegacyRanking(const RunResult& expected, const RunResult& actual,
                            const std::string& label) {
    check(expected.debug.pairs.size() == actual.debug.pairs.size(),
          label + " ranked candidate count matches legacy ranking");
    const size_t n = expected.debug.pairs.size() < actual.debug.pairs.size()
                         ? expected.debug.pairs.size()
                         : actual.debug.pairs.size();
    for (size_t i = 0; i < n; i++) {
        const auto& e = expected.debug.pairs[i];
        const auto& a = actual.debug.pairs[i];
        const std::string at = label + "[" + std::to_string(i) + "]";
        check(e.startFrame == a.startFrame, at + " startFrame");
        check(e.endFrame == a.endFrame, at + " endFrame");
        check(e.legacyScore == a.legacyScore, at + " legacyScore (bitwise)");
        check(e.score == a.score, at + " score (bitwise)");
    }
}

void testProductionDefault(const std::vector<float>& pcm, int sr) {
    clearSeamEnv();
    const RunResult r = run(pcm, sr, 10);
    check(!r.result.candidates.empty(), "default: analysis returns candidates");
    check(!r.debug.pairs.empty(), "default: debug pairs present");
    for (size_t i = 0; i < r.result.candidates.size(); i++) {
        const loop_engine::LoopCandidate& c = r.result.candidates[i];
        const std::string label = "default[" + std::to_string(i) + "]";
        check(c.seamQuality == 0.0,
              label + " seamQuality is the 0.0 unknown sentinel (stage off)");
        check(c.score == c.legacyScore,
              label + " production score == legacyScore (initial algorithm)");
        if (i > 0) {
            check(r.result.candidates[i - 1].legacyScore >= c.legacyScore,
                  label + " ranking is non-increasing by legacyScore");
        }
    }
    for (size_t i = 0; i < r.debug.pairs.size(); i++) {
        const std::string label = "defaultPair[" + std::to_string(i) + "]";
        check(!r.debug.pairs[i].seamEvaluated, label + " seam stage did not run");
        check(r.debug.pairs[i].legacyScore == r.debug.pairs[i].score,
              label + " pair legacyScore == pair score");
    }
}

void testRankingEqualsLegacyRanking(const std::vector<float>& pcm, int sr) {
    // Production default...
    clearSeamEnv();
    const RunResult production = run(pcm, sr, 10);

    // ...must equal the ranking obtained when the seam stage runs but is not
    // allowed to influence the rank (weight 0.0, the engine default weight).
    setSeam(1, "");
    const RunResult measured = run(pcm, sr, 10);
    checkSameLegacyRanking(production, measured,
                           "ranking == legacyScore ranking (seam on, w=0)");
    int seamMeasured = 0;
    for (const auto& p : measured.debug.pairs)
        if (p.seamEvaluated && p.seamQuality > 0.0) seamMeasured++;
    check(seamMeasured > 0,
          "seam on w=0: seam quality is still measured (code retained)");
    for (size_t i = 0; i < measured.result.candidates.size(); i++) {
        check(measured.result.candidates[i].score ==
                  measured.result.candidates[i].legacyScore,
              "seam on w=0: candidate " + std::to_string(i) + " score == legacyScore");
    }

    // Explicit blend (task B): the retained v8 fusion still writes the 0.5/0.5
    // score, so the experiment app does not need code changes.
    setSeam(1, "0.50");
    const RunResult fused = run(pcm, sr, 10);
    check(!fused.result.candidates.empty(), "seam fused: analysis returns candidates");
    int fusedSeams = 0;
    for (const loop_engine::LoopCandidate& c : fused.result.candidates) {
        check(std::fabs(c.score - (0.5 * c.legacyScore + 0.5 * c.seamQuality)) < 1e-12,
              "seam fused: score is the 0.5/0.5 blend");
        if (c.seamQuality > 0.0) fusedSeams++;
    }
    check(fusedSeams > 0, "seam fused: seam quality measured");
    clearSeamEnv();
}

} // namespace

int main() {
    std::printf("loop_engine production ranking regression test\n");
    const int sr = 44100;
    const std::vector<float> music = makeMusic(12.0, sr);
    testProductionDefault(music, sr);
    testRankingEqualsLegacyRanking(music, sr);
    clearSeamEnv();
    if (gFailures == 0) {
        std::printf("ALL PASS\n");
        return 0;
    }
    std::printf("%d FAILURE(S)\n", gFailures);
    return 1;
}
