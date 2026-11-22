// Host-side contract test for the flat record returned by loop_jni.cpp
// (loop_engine::flattenCandidates, candidate_flat.h).
//
// v8 appended seamQuality as the 6th double, v8.1 appended legacyScore as the
// 7th. This test pins the contract old consumers rely on: the first five fields
// keep their positions and values, the record grows 5 -> 6 -> 7 doubles, and the
// PRODUCTION default (seam stage off) writes 0.0 as the seam "unknown" sentinel
// while legacyScore == score.
//
// The seam stage is retained for the task-B experiment: with LOOP_ENGINE_SEAM=1
// and LOOP_ENGINE_SEAM_WEIGHT=0.50 the record still carries the v8 fused score
// (score == 0.5*legacy + 0.5*seam). With the stage on and the weight at its
// default 0.0 the seam is measured but the ranking stays the legacy one.
//
// Build/run (host, no Android device needed):
//   cmake -S app/src/main/cpp/loop_engine/tests -B build/loop-engine-tests
//   cmake --build build/loop-engine-tests --config Release --target loop_engine_flat_test
//   ./build/loop-engine-tests/loop_engine_flat_test     (MSVC: path under Release/)
//
// Exit code 0 = all checks passed.

#include "candidate_flat.h"
#include "loop_engine.h"

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

// Pin the seam switches so the test never depends on the developer shell.
// clearSeamEnv() = production default (stage off, blend weight 0.0).
void clearSeamEnv() {
#ifdef _WIN32
    _putenv_s("LOOP_ENGINE_SEAM", "");
    _putenv_s("LOOP_ENGINE_SEAM_WEIGHT", "");
#else
    unsetenv("LOOP_ENGINE_SEAM");
    unsetenv("LOOP_ENGINE_SEAM_WEIGHT");
#endif
}

// Explicit experiment configuration: on/off plus an optional weight override
// ("" leaves the engine default 0.0 in place).
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

// Deterministic synthetic 4/4 loop material (same shape as the parallel test):
// a 4 s pattern repeats, so the engine has real beat/loop structure to find.
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

loop_engine::LoopCandidate makeCandidate(int64_t start, int64_t end, double score,
                                        double noteDistance, double loudness, double seam,
                                        double legacy) {
    loop_engine::LoopCandidate c;
    c.loopStart = start;
    c.loopEnd = end;
    c.score = score;
    c.noteDistance = noteDistance;
    c.loudnessDifference = loudness;
    c.seamQuality = seam;
    c.legacyScore = legacy;
    return c;
}

// Field 0..4 must stay exactly the pre-v8 record; 5 = seamQuality, 6 = legacyScore.
void checkRecord(const std::vector<double>& flat, size_t index,
                 const loop_engine::LoopCandidate& c, const std::string& label) {
    const size_t base = index * size_t(loop_engine::kFlatFieldsPerCandidate);
    check(flat[base + 0] == double(c.loopStart), label + " field0 loopStart unchanged");
    check(flat[base + 1] == double(c.loopEnd), label + " field1 loopEnd unchanged");
    check(flat[base + 2] == c.score, label + " field2 score unchanged");
    check(flat[base + 3] == c.noteDistance, label + " field3 noteDistance unchanged");
    check(flat[base + 4] == c.loudnessDifference, label + " field4 loudnessDifference unchanged");
    check(flat[base + 5] == c.seamQuality, label + " field5 seamQuality appended by v8");
    check(flat[base + 6] == c.legacyScore, label + " field6 legacyScore appended by v8.1");
}

void testManualLayout() {
    loop_engine::Result r;
    r.candidates.push_back(makeCandidate(76792, 3544053, 0.923, 0.12, 0.34, 0.86, 0.987));
    r.candidates.push_back(makeCandidate(100, 200, 0.5, 0.2, 0.3, 0.0, 0.5));
    const std::vector<double> flat = loop_engine::flattenCandidates(r);
    check(flat.size() == 2 * 7, "2 candidates -> 14 doubles (was 10 before v8)");
    check(flat.size() % loop_engine::kFlatFieldsPerCandidate == 0, "record stride is 7");
    checkRecord(flat, 0, r.candidates[0], "manual[0]");
    checkRecord(flat, 1, r.candidates[1], "manual[1]");

    loop_engine::Result empty;
    check(loop_engine::flattenCandidates(empty).empty(), "empty result -> empty flat array");
}

void testAnalyzedLayout(const std::vector<float>& pcm, int sr) {
    const int64_t frames = static_cast<int64_t>(pcm.size());

    // Production default (no env): the initial algorithm. The seam stage does
    // not run, so seamQuality is the 0.0 "unknown" sentinel and the score is
    // the pure legacy score in the initial score-descending order.
    clearSeamEnv();
    const loop_engine::Result legacy =
        loop_engine::analyze(pcm.data(), 1, frames, sr, 10);
    check(!legacy.candidates.empty(), "default: analysis returns candidates");
    const std::vector<double> flatLegacy = loop_engine::flattenCandidates(legacy);
    check(flatLegacy.size() == legacy.candidates.size() * 7,
          "default: flat length is 7 x N");
    check(flatLegacy.size() ==
              legacy.candidates.size() * size_t(loop_engine::kFlatFieldsPerCandidate),
          "default: flat length matches kFlatFieldsPerCandidate");
    for (size_t i = 0; i < legacy.candidates.size(); i++) {
        const loop_engine::LoopCandidate& c = legacy.candidates[i];
        const std::string label = "default[" + std::to_string(i) + "]";
        checkRecord(flatLegacy, i, c, label);
        check(c.seamQuality == 0.0, label + " seamQuality is the 0.0 unknown sentinel");
        check(c.legacyScore == c.score, label + " default: legacyScore == score");
        if (i > 0) {
            check(legacy.candidates[i - 1].score >= c.score,
                  label + " score is non-increasing (initial ranking)");
        }
    }

    // Seam stage explicitly on with the default weight (0.0): the seam is
    // measured, but the ranking and score stay the legacy ones.
    setSeam(1, "");
    const loop_engine::Result measured =
        loop_engine::analyze(pcm.data(), 1, frames, sr, 10);
    check(!measured.candidates.empty(), "seam on w=0: analysis returns candidates");
    int measuredCount = 0;
    for (size_t i = 0; i < measured.candidates.size(); i++) {
        const loop_engine::LoopCandidate& c = measured.candidates[i];
        const std::string label = "seamOnW0[" + std::to_string(i) + "]";
        check(c.legacyScore == c.score, label + " seam on w=0: legacyScore == score");
        if (c.seamQuality > 0.0) measuredCount++;
    }
    check(measuredCount > 0, "seam on w=0: at least one candidate has a measured seam");
    check(measured.candidates.size() == legacy.candidates.size(),
          "seam on w=0: candidate count unchanged");
    for (size_t i = 0; i < measured.candidates.size() && i < legacy.candidates.size(); i++) {
        check(measured.candidates[i].legacyScore == legacy.candidates[i].legacyScore,
              "seam on w=0: legacy ranking position unchanged at " + std::to_string(i));
    }

    // Task-B configuration: the retained v8 fusion is still wired and writes the
    // 0.5/0.5 blend.
    setSeam(1, "0.50");
    const loop_engine::Result fused =
        loop_engine::analyze(pcm.data(), 1, frames, sr, 10);
    check(!fused.candidates.empty(), "seam fused: analysis returns candidates");
    const std::vector<double> flatFused = loop_engine::flattenCandidates(fused);
    check(flatFused.size() == fused.candidates.size() * 7,
          "seam fused: flat length is 7 x N");
    int fusedMeasured = 0;
    for (size_t i = 0; i < fused.candidates.size(); i++) {
        const loop_engine::LoopCandidate& c = fused.candidates[i];
        const std::string label = "seamFused[" + std::to_string(i) + "]";
        checkRecord(flatFused, i, c, label);
        check(c.seamQuality >= 0.0 && c.seamQuality <= 1.0, label + " seamQuality in [0,1]");
        check(c.legacyScore >= 0.0 && c.legacyScore <= 1.0, label + " legacyScore in [0,1]");
        check(std::fabs(c.score - (0.5 * c.legacyScore + 0.5 * c.seamQuality)) < 1e-12,
              label + " score is the 0.5/0.5 blend of legacy and seam");
        if (c.seamQuality > 0.0) fusedMeasured++;
    }
    check(fusedMeasured > 0, "seam fused: at least one candidate has a measured seam");

    clearSeamEnv();
}

} // namespace

int main() {
    std::printf("loop_engine flat JNI record layout test\n");
    testManualLayout();
    testAnalyzedLayout(makeMusic(12.0, 44100), 44100);
    if (gFailures == 0) {
        std::printf("ALL PASS\n");
        return 0;
    }
    std::printf("%d FAILURE(S)\n", gFailures);
    return 1;
}
