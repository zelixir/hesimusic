#pragma once

#include <vector>

#include "loop_engine_internal.h"

namespace loop_engine {

// Flat double layout for the algorithm-lab JNI entry point (loop_jni.cpp
// analyzeLab). One analysis produces BOTH algorithms' view of the same decoded
// PCM, so the lab never decodes/analyses twice.
//
// Header (6 doubles):
//   [0] version (= kLabFlatVersion)
//   [1] seamStageMs            (wall time of the seam stage; 0 when skipped)
//   [2] scoredPairs            (every pruned beat pair -> legacy ranking domain)
//   [3] evaluatedPool          (candidates the seam stage scored)
//   [4] seamEnabled            (0/1; 0 => seam fields are "not measured")
//   [5] seamEvaluatedCount
//
// Then one record per evaluated candidate, in fused-rank order when the seam
// stage ran, legacy-rank order otherwise (18 doubles):
//   [0] loopStart            emitted start for the fused ranking (sample index)
//   [1] loopEnd              emitted end
//   [2] legacyScore          pre-v8 chroma-sequence score
//   [3] fusedScore           (1-w)*legacyScore + w*seamQuality
//   [4] legacyRank           0-based global rank over scoredPairs
//   [5] fusedRank            0-based rank over the evaluated pool
//   [6] seamQuality          weighted corr/rms/flux blend (0 when not measured)
//   [7] correlation          raw normalized xcorr peak
//   [8] rmsJumpDb            |RMS after A - RMS before B| in dB
//   [9] fluxRatio            seam spectral distance / local mean distance
//  [10] corrScore            normalized correlation term (0..1)
//  [11] energyScore          normalized energy term (0..1)
//  [12] timbreScore          normalized timbre term (0..1)
//  [13] noteDistance
//  [14] loudnessDifference
//  [15] seamEvaluated        0/1
//  [16] anchorStart          start the legacy path emits (no sub-beat refine)
//  [17] anchorEnd            end the legacy path emits
//
// Appends are only ever at the end of the header/record; the lab parser rejects
// unknown versions instead of guessing.
constexpr int kLabFlatVersion = 1;
constexpr int kLabHeaderFields = 6;
constexpr int kLabFieldsPerCandidate = 18;

inline std::vector<double> flattenLab(const debug::LabInfo& info) {
    std::vector<double> flat;
    flat.reserve(size_t(kLabHeaderFields) +
                 info.candidates.size() * size_t(kLabFieldsPerCandidate));
    flat.push_back(static_cast<double>(kLabFlatVersion));
    flat.push_back(static_cast<double>(info.seamStageUs) / 1000.0);
    flat.push_back(static_cast<double>(info.scoredPairs));
    flat.push_back(static_cast<double>(info.evaluatedPool));
    flat.push_back(info.seamEnabled ? 1.0 : 0.0);
    flat.push_back(static_cast<double>(info.seamEvaluatedCount));
    for (const debug::LabCandidate& c : info.candidates) {
        flat.push_back(static_cast<double>(c.loopStart));
        flat.push_back(static_cast<double>(c.loopEnd));
        flat.push_back(c.legacyScore);
        flat.push_back(c.fusedScore);
        flat.push_back(static_cast<double>(c.legacyRank));
        flat.push_back(static_cast<double>(c.fusedRank));
        flat.push_back(c.seamQuality);
        flat.push_back(c.seamCorrelation);
        flat.push_back(c.seamRmsJumpDb);
        flat.push_back(c.seamFluxRatio);
        flat.push_back(c.corrScore);
        flat.push_back(c.energyScore);
        flat.push_back(c.timbreScore);
        flat.push_back(c.noteDistance);
        flat.push_back(c.loudnessDifference);
        flat.push_back(c.seamEvaluated ? 1.0 : 0.0);
        flat.push_back(static_cast<double>(c.anchorStart));
        flat.push_back(static_cast<double>(c.anchorEnd));
    }
    return flat;
}

} // namespace loop_engine
