#pragma once

#include <cstdint>
#include <functional>
#include <vector>

// Seamless-loop analysis engine (port of PyMusicLooper 3.6.0 analysis pipeline).
// Pure C++17, standard library only. See docs/loop-seamless.md section 2.1
// and docs/loop-seamless-v2.md section 1.2 (progress callback).
namespace loop_engine {

struct LoopCandidate {
    int64_t loopStart = 0;         // inclusive; sample index on the input-rate mono stream
    int64_t loopEnd = 0;           // exclusive
    double score = 0.0;            // higher is better; v8 fused ranking score (0..1)
    double noteDistance = 0.0;     // chroma distance, lower is better
    double loudnessDifference = 0.0;
    // v8 seam quality (0..1, higher = the B->A splice is closer to "the song kept
    // playing"). Appended as the 6th field of the flat JNI record (see
    // candidate_flat.h); the first five fields keep their pre-v8 meaning and
    // order. 0.0 is the "not measured" sentinel: the seam stage is off
    // (LOOP_ENGINE_SEAM=0) or the candidate bounds fell back, and the UI treats
    // it as unknown instead of rendering a misleading "seam 0%".
    double seamQuality = 0.0;
};

struct Result {
    std::vector<LoopCandidate> candidates; // sorted by score desc, at most maxCandidates
    int64_t totalFrames = 0;
    int sampleRate = 0;
};

// v2 §1.2: optional analysis progress callback (interface-level addition only;
// the algorithm behavior is unchanged). fraction is 0..1 (onset/beat 0~0.6,
// candidate pairing + chroma/scoring 0.6~1.0, throttled to <= 20 Hz internally).
// Return false from the callback to cancel the analysis early; the engine then
// returns an empty candidate list (same contract as any other failure).
using ProgressFn = std::function<bool(float)>;

// interleavedPcm: interleaved float PCM; the engine mixes down to mono internally.
// Never throws; returns an empty candidate list on failure.
Result analyze(const float* interleavedPcm, int channels, int64_t frames,
               int sampleRate, int maxCandidates = 10,
               const ProgressFn& onProgress = ProgressFn());

} // namespace loop_engine
