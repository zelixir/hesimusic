#pragma once

#include <vector>

#include "loop_engine.h"

namespace loop_engine {

// Flat double layout returned across JNI (loop_jni.cpp) and parsed by
// LoopCandidates.kt. One record per candidate, score-descending input order:
//
//   [loopStart, loopEnd, score, noteDistance, loudnessDifference,
//    seamQuality, legacyScore]
//
// v8 appended seamQuality; v8.1 appended legacyScore (the pre-blend, pre-v8
// score semantics). Appends are strictly "add, never remove/reorder": the first
// five positions/meanings are unchanged, so fields keep their old offsets.
// seamQuality is 0.0 when the seam stage did not run (the production default,
// LOOP_ENGINE_SEAM=0, or a bounds fallback); legacyScore equals score in that
// path because ranking never blends (both carry the pre-v8 score). Kotlin
// treats seamQuality 0 as "unknown", never as a real 0%.
constexpr int kFlatFieldsPerCandidate = 7;

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
        flat.push_back(candidate.legacyScore);
    }
    return flat;
}

} // namespace loop_engine
