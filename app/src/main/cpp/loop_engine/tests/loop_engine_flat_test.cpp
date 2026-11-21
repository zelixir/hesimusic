// Host-side contract test for the flat record returned by loop_jni.cpp
// (loop_engine::flattenCandidates, candidate_flat.h).
//
// v8 appended seamQuality as the 6th double of every candidate record. This
// test pins the part old consumers rely on: the first five fields keep their
// positions and values, the record grows 5 -> 6 doubles, and the seam stage
// being disabled (LOOP_ENGINE_SEAM=0) writes 0.0 (the "unknown" sentinel)
// instead of a stale/garbage value.
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

void setSeam(int on) {
#ifdef _WIN32
    _putenv_s("LOOP_ENGINE_SEAM", on ? "1" : "0");
#else
    setenv("LOOP_ENGINE_SEAM", on ? "1" : "0", 1);
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
                                        double noteDistance, double loudness, double seam) {
    loop_engine::LoopCandidate c;
    c.loopStart = start;
    c.loopEnd = end;
    c.score = score;
    c.noteDistance = noteDistance;
    c.loudnessDifference = loudness;
    c.seamQuality = seam;
    return c;
}

// Field 0..4 must stay exactly the pre-v8 record; field 5 is the new seamQuality.
void checkRecord(const std::vector<double>& flat, size_t index,
                 const loop_engine::LoopCandidate& c, const std::string& label) {
    const size_t base = index * size_t(loop_engine::kFlatFieldsPerCandidate);
    check(flat[base + 0] == double(c.loopStart), label + " field0 loopStart unchanged");
    check(flat[base + 1] == double(c.loopEnd), label + " field1 loopEnd unchanged");
    check(flat[base + 2] == c.score, label + " field2 score unchanged");
    check(flat[base + 3] == c.noteDistance, label + " field3 noteDistance unchanged");
    check(flat[base + 4] == c.loudnessDifference, label + " field4 loudnessDifference unchanged");
    check(flat[base + 5] == c.seamQuality, label + " field5 seamQuality appended");
}

void testManualLayout() {
    loop_engine::Result r;
    r.candidates.push_back(makeCandidate(76792, 3544053, 0.987, 0.12, 0.34, 0.86));
    r.candidates.push_back(makeCandidate(100, 200, 0.5, 0.2, 0.3, 0.0));
    const std::vector<double> flat = loop_engine::flattenCandidates(r);
    check(flat.size() == 2 * 6, "2 candidates -> 12 doubles (was 10 before v8)");
    check(flat.size() % loop_engine::kFlatFieldsPerCandidate == 0, "record stride is 6");
    checkRecord(flat, 0, r.candidates[0], "manual[0]");
    checkRecord(flat, 1, r.candidates[1], "manual[1]");

    loop_engine::Result empty;
    check(loop_engine::flattenCandidates(empty).empty(), "empty result -> empty flat array");
}

void testAnalyzedLayout(const std::vector<float>& pcm, int sr) {
    const int64_t frames = static_cast<int64_t>(pcm.size());
    setSeam(1);
    const loop_engine::Result on =
        loop_engine::analyze(pcm.data(), 1, frames, sr, 10);
    check(!on.candidates.empty(), "seam on: analysis returns candidates");
    const std::vector<double> flatOn = loop_engine::flattenCandidates(on);
    check(flatOn.size() == on.candidates.size() * 6, "seam on: flat length is 6 x N");
    check(flatOn.size() == on.candidates.size() * size_t(loop_engine::kFlatFieldsPerCandidate),
          "seam on: flat length matches kFlatFieldsPerCandidate");
    int measured = 0;
    for (size_t i = 0; i < on.candidates.size(); i++) {
        const loop_engine::LoopCandidate& c = on.candidates[i];
        checkRecord(flatOn, i, c, "seamOn[" + std::to_string(i) + "]");
        check(c.seamQuality >= 0.0 && c.seamQuality <= 1.0, "seamQuality in [0,1]");
        if (c.seamQuality > 0.0) measured++;
    }
    check(measured > 0, "seam on: at least one candidate has a measured seam");

    setSeam(0);
    const loop_engine::Result off =
        loop_engine::analyze(pcm.data(), 1, frames, sr, 10);
    setSeam(1);
    check(!off.candidates.empty(), "seam off: analysis still returns candidates");
    const std::vector<double> flatOff = loop_engine::flattenCandidates(off);
    check(flatOff.size() == off.candidates.size() * 6, "seam off: flat length is 6 x N");
    for (size_t i = 0; i < off.candidates.size(); i++) {
        checkRecord(flatOff, i, off.candidates[i], "seamOff[" + std::to_string(i) + "]");
        check(off.candidates[i].seamQuality == 0.0,
              "seam off: seamQuality is the 0.0 unknown sentinel");
    }
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
