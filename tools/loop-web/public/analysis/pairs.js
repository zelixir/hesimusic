// Beat-pair candidate generation, pruning, subsequence scoring and sample-level
// zero-crossing snap. Direct port of loop_engine.cpp.

export const HOP = 512;

export function timeToFrames(seconds, sr) {
  const samples = Math.trunc(seconds * sr);
  return Math.trunc(samples / HOP);
}

export function timeToSamplesTrunc(seconds, sr) {
  return Math.trunc(seconds * sr);
}

// Build reference-identical candidate pairs. checks counts the number of
// time-window-valid pairs actually tested (useful for the O(B^2) guardrail).
export function findPairs(chroma, T, beats, cmax, minLoop, maxLoop, chromaThreshold, dbThreshold) {
  const B = beats.length;
  const dev = new Float64Array(B);
  for (let bi = 0; bi < B; bi++) {
    const t = beats[bi];
    let s = 0;
    for (let c = 0; c < 12; c++) {
      const v = chroma[c * T + t];
      s += v * v;
    }
    dev[bi] = chromaThreshold * Math.sqrt(s);
  }
  const out = [];
  let checks = 0;
  for (let bi = 0; bi < B; bi++) {
    const tEnd = beats[bi];
    const colEnd = new Float64Array(12);
    for (let c = 0; c < 12; c++) colEnd[c] = chroma[c * T + tEnd];
    for (let si = 0; si < B; si++) {
      const tStart = beats[si];
      const len = tEnd - tStart;
      if (len < minLoop) break;
      if (len > maxLoop) continue;
      checks++;
      let nd2 = 0;
      for (let c = 0; c < 12; c++) {
        const d = colEnd[c] - chroma[c * T + tStart];
        nd2 += d * d;
      }
      const nd = Math.sqrt(nd2);
      if (nd <= dev[bi]) {
        const ld = Math.abs(cmax[tEnd] - cmax[tStart]);
        if (ld <= dbThreshold) out.push({ startF: tStart, endF: tEnd, nd, ld });
      }
    }
  }
  return { pairs: out, checks };
}

function filteredAboveEps(values) {
  const out = [];
  for (const v of values) if (v > 1e-3) out.push(v);
  return out;
}

function percentileSorted(sorted, q) {
  const n = sorted.length;
  if (n === 0) return NaN;
  if (n === 1) return sorted[0];
  const pos = (q / 100) * (n - 1);
  const lo = Math.floor(pos);
  let hi = Math.ceil(pos);
  if (hi >= n) hi = n - 1;
  return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo);
}

export function prunePairs(pairList) {
  if (pairList.length < 100) return pairList;
  const lds = pairList.map((p) => p.ld);
  const nds = pairList.map((p) => p.nd);
  const ldf = filteredAboveEps(lds).sort((a, b) => a - b);
  const ndf = filteredAboveEps(nds).sort((a, b) => a - b);
  let dbThr;
  let ndThr;
  if (ldf.length > 3) dbThr = percentileSorted(ldf, 50);
  else dbThr = Math.max(...lds);
  if (ndf.length > 3) ndThr = percentileSorted(ndf, 75);
  else ndThr = Math.max(...nds);
  const ldBound = Math.max(0.25, dbThr);
  return pairList.filter((p) => p.ld <= ldBound && p.nd <= ndThr);
}

function subseqSim(b1Start, b2Start, testEndOffset, w, chroma, colNorm, T) {
  const testLen = Math.abs(testEndOffset);
  let b1 = b1Start;
  let b2 = b2Start;
  let maxOffset = 0;
  if (testEndOffset < 0) {
    const mno = Math.max(testEndOffset, Math.max(-b1Start, -b2Start));
    b1 += mno;
    b2 += mno;
    maxOffset = -mno;
  } else {
    const b1e = Math.min(b1Start + testLen, T);
    const b2e = Math.min(b2Start + testLen, T);
    maxOffset = Math.min(b1e - b1Start, b2e - b2Start);
  }
  let num = 0;
  let den = 0;
  const W = w.length;
  for (let j = 0; j < W; j++) {
    let x = 0;
    if (j < maxOffset) {
      const ta = b1 + j;
      const tb = b2 + j;
      let dot = 0;
      for (let c = 0; c < 12; c++) dot += chroma[c * T + ta] * chroma[c * T + tb];
      x = dot / Math.max(colNorm[ta] * colNorm[tb], 1e-10);
    }
    num += w[j] * x;
    den += w[j];
  }
  return den > 0 ? num / den : 0;
}

export function buildScoringPlan(T, bpm, sampleRate) {
  const safeBpm = bpm > 0 ? bpm : 120;
  const secondsToTest = 12 / (safeBpm / 60);
  let testOffset = Math.trunc(timeToSamplesTrunc(secondsToTest, sampleRate) / HOP);
  if (testOffset > T) testOffset = Math.trunc(T / 4);
  if (testOffset < 1) return null;
  const wStart = Math.max(2, Math.trunc(testOffset / 12));
  const w = new Float64Array(testOffset);
  if (testOffset === 1) {
    w[0] = wStart;
  } else {
    const ls = Math.log(wStart);
    const le = Math.log(1);
    for (let i = 0; i < testOffset; i++) w[i] = Math.exp(ls + ((le - ls) * i) / (testOffset - 1));
  }
  return { testOffset, w, wrev: Float64Array.from(w).reverse() };
}

export function scorePairs(pairList, chroma, T, plan) {
  const colNorm = new Float64Array(T);
  for (let t = 0; t < T; t++) {
    let s = 0;
    for (let c = 0; c < 12; c++) {
      const v = chroma[c * T + t];
      s += v * v;
    }
    colNorm[t] = Math.sqrt(s);
  }
  const scored = new Array(pairList.length);
  for (let i = 0; i < pairList.length; i++) {
    const p = pairList[i];
    const fwd = subseqSim(p.startF, p.endF, plan.testOffset, plan.w, chroma, colNorm, T);
    const bwd = subseqSim(p.startF, p.endF, -plan.testOffset, plan.wrev, chroma, colNorm, T);
    scored[i] = { p, score: Math.max(fwd, bwd) };
  }
  scored.sort((a, b) => b.score - a.score);
  return scored;
}

// Audacity-style nearest rising zero crossing (mono path; the app runs it on
// the interleaved stereo stream — see README deviations).
export function nearestZeroCrossing(pcm, sampleRate, sampleIdx) {
  const totalFrames = pcm.length;
  const windowSize = Math.max(1, Math.trunc(sampleRate / 100));
  const offset = Math.trunc(windowSize / 2);
  const neg = Math.max(0, sampleIdx - offset);
  const pos = Math.min(totalFrames, sampleIdx + offset);
  const L = pos - neg;
  if (L <= 0) return sampleIdx;
  const diff = sampleIdx - offset;
  const offsetCorrection = diff < 0 ? -diff : 0;
  const dist = new Float64Array(L);
  let prev = 2;
  for (let i = 0; i < L; i++) {
    const x = pcm[neg + i];
    let fdist = Math.abs(x);
    if (prev * x > 0) fdist += 0.4;
    else if (prev > 0) fdist += 0.1;
    prev = x;
    dist[i] = fdist + (0.1 * Math.abs(i - offset + offsetCorrection)) / (windowSize / 2);
  }
  let argmin = 0;
  let minDist = dist[0];
  for (let i = 1; i < L; i++) {
    if (dist[i] < minDist) {
      minDist = dist[i];
      argmin = i;
    }
  }
  if (minDist > 0.2) return sampleIdx;
  return sampleIdx + argmin - offset + offsetCorrection;
}

export function frameToSample(frame, trimStart) {
  let sf = frame;
  if (trimStart > 0) sf = Math.trunc((frame * HOP + trimStart) / HOP);
  return sf * HOP;
}

export function sampleToFrame(sample, trimStart) {
  return Math.trunc((sample - trimStart) / HOP);
}
