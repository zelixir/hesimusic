#pragma once

// Internal debug hook shared with the desktop validation CLI.
// Not part of the public engine contract.
#include "loop_engine.h"

#include <vector>

namespace loop_engine::debug {

struct PairInfo {
    int startFrame = 0;      // frame index after trim-offset remap (pre zero-crossing)
    int endFrame = 0;
    int64_t loopStart = 0;   // zero-crossing snapped sample
    int64_t loopEnd = 0;
    double noteDistance = 0.0;
    double loudnessDifference = 0.0;
    double score = 0.0;              // final score (v8: blended, see loop_engine.cpp)
    // v8 seam-quality diagnostics (host CLI only; not part of the JNI contract).
    double legacyScore = 0.0;        // pre-v8 chroma-sequence score
    double seamQuality = 0.0;        // 0..1 blended continuity at the B->A splice
    double seamCorrelation = 0.0;    // peak normalized xcorr of the two tail windows
    double seamRmsJumpDb = 0.0;      // |short-term RMS after A - before B| in dB
    double seamFluxRatio = 1.0;      // seam spectral distance / local mean distance
    bool seamEvaluated = false;      // false when the seam stage is off (default) or bounds fell back
    int64_t refinedStartSample = 0;  // pre-zero-crossing refined position (original coords)
    int64_t refinedEndSample = 0;
};

// ---------------------------------------------------------------------------
// Algorithm lab (issue 00030): JNI-passed knobs plus the full dual-algorithm
// candidate list from ONE analysis pass.
//
// Scope: this struct is only consumed by debug::analyzeWithDebug when the caller
// passes a non-null LabParams. Production analyze() passes nullptr, so the
// engine keeps its existing behaviour/defaults (including the env switches used
// by the host CLI). Nothing here changes the scoring formulas: the lab only
// exposes the legacy score, the seam components and both rankings that the
// engine already computes internally.
struct LabParams {
    bool seamEnabled = true;      // false = legacy-only path (seam stage skipped)
    bool refineEnabled = true;    // sub-beat +/-rangeMs local search
    double seamWeight = 0.50;     // blend: (1-w)*legacy + w*seamQuality
    double corrWeight = 0.50;     // seam-quality sub-weights; normalized below
    double rmsWeight = 0.30;
    double fluxWeight = 0.20;
    int refineRangeMs = 30;       // 0..100; 0 = no search even when enabled
};

// One evaluated candidate. Every field is measured by the single pipeline run:
// - legacyScore / legacyRank: pre-v8 chroma-sequence score and its global rank
//   over every pruned beat pair (the "original algorithm" output);
// - fusedScore / fusedRank: the new ranking with the weights above, ranked over
//   the evaluated pool;
// - loopStart/loopEnd: emitted boundaries for the fused ranking (refined when
//   enabled); anchorStart/anchorEnd: the boundaries the legacy path emits
//   (beat-aligned + zero-crossing, no sub-beat refine);
// - the corr/rms/flux raw values and their 0..1 sub-scores, so the lab UI can
//   re-rank for a weight sweep without a second analysis.
struct LabCandidate {
    int64_t loopStart = 0;
    int64_t loopEnd = 0;
    int64_t anchorStart = 0;
    int64_t anchorEnd = 0;
    double legacyScore = 0.0;
    double fusedScore = 0.0;
    int legacyRank = -1;
    int fusedRank = -1;
    double seamQuality = 0.0;
    double seamCorrelation = 0.0;
    double seamRmsJumpDb = 0.0;
    double seamFluxRatio = 1.0;
    double corrScore = 0.0;
    double energyScore = 0.0;
    double timbreScore = 0.0;
    double noteDistance = 0.0;
    double loudnessDifference = 0.0;
    bool seamEvaluated = false;
};

struct LabInfo {
    // Fused-rank order when the seam stage ran; legacy-rank order otherwise.
    std::vector<LabCandidate> candidates;
    int64_t seamStageUs = 0;      // wall time of the seam stage (0 when skipped)
    int seamEvaluatedCount = 0;
    int scoredPairs = 0;          // all pruned beat pairs (legacy ranking domain)
    int evaluatedPool = 0;        // candidates the seam stage actually scored
    bool seamEnabled = false;
};

struct DebugInfo {
    int64_t trimOffset = 0;
    int nFrames = 0;         // STFT/chroma frame count
    double tuning = 0.0;
    double bpm = 0.0;
    int framesPerBeat = 0;
    std::vector<int> beats;  // union grid (beat_track + plp), trimmed-audio frame coords
    int nBeatsPlp = 0;
    std::vector<int> beatsPlpHead;
    std::vector<int> beatsPlpFull;
    int nPairsBeforePrune = 0;
    int nPairsAfterPrune = 0;
    double chromaSum = 0.0;
    double chromaAbsSum = 0.0;
    double onsetSum = 0.0;
    double pulseSum = 0.0;
    std::vector<double> chromaCol0;
    std::vector<double> chromaColN;
    std::vector<double> onsetHead;
    std::vector<double> onsetTail;
    std::vector<double> powerDbCmaxHead;
    std::vector<double> powerDbCmaxTail;
    std::vector<double> weightsHead;
    int testOffset = 0;
    std::vector<double> pulseFull;   // normalized plp pulse, T values
    std::vector<double> onsetFull;   // onset envelope, T values
    std::vector<double> tgMean;      // time-aggregated tempogram (win lags)
    double melSum = 0.0;
    std::vector<double> melRow0Head; // mel[0][0..8]
    std::vector<double> melCol0;     // mel[0..8][0]
    std::vector<int> beatsBt;        // beat_track DP beats only (pre-union)
    std::vector<PairInfo> pairs; // score-desc, zero-crossing applied
};

// Same pipeline as analyze(); fills dbg when non-null.
// v2 §1.2: optional progress callback (default = none), see loop_engine.h.
// labParams/labOut: algorithm-lab only. When labParams is null the function is
// bit-for-bit the previous behaviour (env switches + production defaults);
// passing them switches to the JNI-passed knobs and fills the dual-algorithm
// candidate list. labOut is independent of `dbg` (dbg may stay null).
Result analyzeWithDebug(const float* interleavedPcm, int channels, int64_t frames,
                        int sampleRate, int maxCandidates, DebugInfo* dbg,
                        const ProgressFn& onProgress = ProgressFn(),
                        const LabParams* labParams = nullptr,
                        LabInfo* labOut = nullptr);

} // namespace loop_engine::debug