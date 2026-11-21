#pragma once

#include <vector>

#include "loop_engine.h"

namespace loop_engine {

// Flat double layout returned across JNI (loop_jni.cpp) and parsed by
// LoopCandidates.kt. One record per candidate, score-descending input order:
//
//   [loopStart, loopEnd, score, noteDistance, loudnessDifference, seamQuality]
//
// v8 appended seamQuality as the 6th element ("add, never remove"): the first
// five positions/meanings are unchanged, so a reader that consumes whole
// 6-double records still sees the pre-v8 fields at their old offsets.
// seamQuality is 0.0 when the seam stage did not run (LOOP_ENGINE_SEAM=0 or
// bounds fallback); Kotlin/UI treats 0 as "unknown", never as a real 0%.
constexpr int kFlatFieldsPerCandidate = 6;

inline std::vector<double> flattenCandidates(const Result& result) {
    std::vector<double> flat;
    flat.reserve(result.candidates.size() * size_t(kFlatFieldsPerCandidate));
    for (const LoopCandidate& candidate : result.candidates) {
        flat.push_back(static_cast<double>(candidate.loopStart));
        flat.push_back(static_cast<double>(candidate.loopEnd));
        flat.push_back(candidate.score);
        flat.push_back(candidate.noteDistance);
        flat.push_back(candidate.loudnessDifference);
        flat.push_back(candidate.seamQuality);
    }
    return flat;
}

} // namespace loop_engine
