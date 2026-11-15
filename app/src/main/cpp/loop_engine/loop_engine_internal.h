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
    double score = 0.0;
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
Result analyzeWithDebug(const float* interleavedPcm, int channels, int64_t frames,
                        int sampleRate, int maxCandidates, DebugInfo* dbg,
                        const ProgressFn& onProgress = ProgressFn());

} // namespace loop_engine::debug
