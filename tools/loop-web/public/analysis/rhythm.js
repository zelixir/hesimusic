// Rhythm analysis: port of loop_engine.cpp plpPulse / estimateTempo / beatTrack.
// All bpm/tempo semantics use librosa's 22050 Hz default rate (PyMusicLooper
// calls beat_track/plp without sr), matching the C++ engine.

import { fft, fftPow2, irfft } from './fft.js';
import { hannPeriodic, hannSymmetric, medianSorted, rint } from './dsp.js';

const BEAT_SR = 22050;
const HOP = 512;
const TINY64 = 2.2250738585072014e-308;

function lexGreater(ar, ai, br, bi) {
  if (ar !== br) return ar > br;
  return ai > bi;
}

export function localMaxIndices(x) {
  const out = [];
  const n = x.length;
  for (let i = 1; i < n; i++) {
    const left = x[i] > x[i - 1];
    const right = i + 1 < n ? x[i] >= x[i + 1] : true;
    if (left && right) out.push(i);
  }
  return out;
}

// librosa.beat.plp with defaults, streaming forward/inverse per frame so no
// T x 193 complex matrix is retained. Arithmetic order matches the C++ port.
export function plpPulse(env) {
  const T = env.length;
  const W = 384;
  const nBins = W / 2 + 1;
  const win = hannPeriodic(W);
  const startFrame = W / 2;
  const pulse = new Float64Array(T);
  const head = new Float64Array(W + startFrame - 1);
  const re = new Float64Array(W);
  const im = new Float64Array(W);
  const recon = new Float64Array(W);
  const keep = new Uint8Array(nBins);
  for (let k = 0; k < nBins; k++) {
    const bpmBin = (k * (BEAT_SR * 60 / HOP)) / W;
    keep[k] = bpmBin >= 30 && bpmBin <= 300 ? 1 : 0;
  }
  const normBase = Math.sqrt(TINY64);
  for (let i = 0; i < T + 1; i++) {
    for (let j = 0; j < W; j++) {
      const pos = i - W / 2 + j;
      re[j] = (pos >= 0 && pos < T ? env[pos] : 0) * win[j];
      im[j] = 0;
    }
    fft(re, im, W, false);
    let peak = 0;
    for (let k = 0; k < nBins; k++) {
      if (!keep[k]) continue;
      const m = Math.log1p(1e6 * Math.hypot(re[k], im[k]));
      if (m > peak) peak = m;
    }
    let lexR = 0;
    let lexI = 0;
    let hasLex = false;
    for (let k = 0; k < nBins; k++) {
      if (!keep[k]) {
        re[k] = 0;
        im[k] = 0;
        continue;
      }
      const m = Math.log1p(1e6 * Math.hypot(re[k], im[k]));
      if (m < peak) {
        re[k] = 0;
        im[k] = 0;
      } else if (!hasLex || lexGreater(re[k], im[k], lexR, lexI)) {
        lexR = re[k];
        lexI = im[k];
        hasLex = true;
      }
    }
    const denom = normBase + Math.hypot(lexR, lexI);
    for (let k = 0; k < nBins; k++) {
      re[k] /= denom;
      im[k] /= denom;
    }
    irfft(re, im, W, recon);
    if (i < startFrame) {
      for (let m = 0; m < W; m++) head[i + m] += win[m] * recon[m];
    } else {
      const base = i - startFrame;
      const n0 = base > 0 ? base : 0;
      const n1 = Math.min(T, base + W);
      for (let n = n0; n < n1; n++) pulse[n] += win[n - base] * recon[n - base];
    }
  }
  const copyN = Math.min(W + (startFrame - 1) - W / 2, T);
  for (let q = 0; q < copyN; q++) pulse[q] = head[W / 2 + q];

  const wssFull = new Float64Array(T + W);
  for (let i = 0; i < T + 1; i++) {
    const n0 = i;
    const n1 = Math.min(T + W, i + W);
    for (let n = n0; n < n1; n++) wssFull[n] += win[n - i] * win[n - i];
  }
  for (let n = 0; n < T; n++) {
    const d = wssFull[n + W / 2];
    if (d > TINY64) pulse[n] /= d;
  }
  let mx = 0;
  for (let n = 0; n < T; n++) {
    if (pulse[n] < 0) pulse[n] = 0;
    if (pulse[n] > mx) mx = pulse[n];
  }
  if (mx >= TINY64) for (let n = 0; n < T; n++) pulse[n] /= mx;
  return pulse;
}

// librosa.feature.tempogram + tempo with a log-normal prior around 120 bpm.
export function estimateTempo(oenv) {
  const N = oenv.length;
  const win = Math.floor(Math.trunc(8.0 * BEAT_SR) / HOP);
  if (win < 2 || N < 2) return 0;
  const half = win >> 1;
  const padded = new Float64Array(N + 2 * half);
  for (let i = 0; i < half; i++) padded[i] = oenv[0] * (i / half);
  for (let i = 0; i < N; i++) padded[half + i] = oenv[i];
  for (let i = 0; i < half; i++) padded[N + half + i] = oenv[N - 1] * ((half - 1 - i) / half);

  const acwin = hannPeriodic(win);
  let FF = 1024;
  while (FF < 2 * win - 1) FF <<= 1;
  const tgMean = new Float64Array(win);
  const buf = new Float64Array(FF);
  const re = new Float64Array(FF);
  const im = new Float64Array(FF);
  const spRe = new Float64Array(FF / 2 + 1);
  const spIm = new Float64Array(FF / 2 + 1);
  const ac = new Float64Array(FF);

  for (let t = 0; t < N; t++) {
    for (let i = 0; i < win; i++) {
      const pos = t + i;
      buf[i] = (pos < padded.length ? padded[pos] : 0) * acwin[i];
    }
    for (let i = win; i < FF; i++) buf[i] = 0;
    for (let i = 0; i < FF; i++) {
      re[i] = buf[i];
      im[i] = 0;
    }
    fftPow2(re, im, FF, false);
    for (let k = 0; k <= FF / 2; k++) {
      const rr = re[k];
      const ii = im[k];
      spRe[k] = rr * rr + ii * ii;
      spIm[k] = 0;
    }
    irfft(spRe, spIm, FF, ac);
    let len = 0;
    for (let lag = 0; lag < win; lag++) {
      const a = Math.abs(ac[lag]);
      if (a > len) len = a;
    }
    if (len >= TINY64) {
      const inv = 1 / len;
      for (let lag = 0; lag < win; lag++) tgMean[lag] += ac[lag] * inv;
    }
  }
  for (let lag = 0; lag < win; lag++) tgMean[lag] /= N;

  const bpms = new Float64Array(win);
  bpms[0] = Infinity;
  for (let l = 1; l < win; l++) bpms[l] = (60 * BEAT_SR) / (HOP * l);
  let maxIdx = 0;
  for (let l = 0; l < win; l++) {
    if (bpms[l] < 320) {
      maxIdx = l;
      break;
    }
  }
  let bestVal = -Infinity;
  let bestLag = 0;
  for (let l = 0; l < win; l++) {
    let prior = -0.5 * Math.pow(Math.log2(bpms[l]) - Math.log2(120), 2);
    if (l < maxIdx) prior = -Infinity;
    const v = Math.log1p(1e6 * tgMean[l]) + prior;
    if (v > bestVal) {
      bestVal = v;
      bestLag = l;
    }
  }
  return bpms[bestLag];
}

// librosa.beat.beat_track dynamic program (Ellis 2007), including
// __normalize_onsets and __trim_beats.
export function beatTrack(env) {
  const N = env.length;
  let anyPos = false;
  for (let i = 0; i < N; i++) {
    if (env[i] !== 0) {
      anyPos = true;
      break;
    }
  }
  if (N < 2 || !anyPos) return { bpm: 0, beats: [] };

  let mean = 0;
  for (let i = 0; i < N; i++) mean += env[i];
  mean /= N;
  let varSum = 0;
  for (let i = 0; i < N; i++) {
    const d = env[i] - mean;
    varSum += d * d;
  }
  const stdv = Math.sqrt(varSum / (N - 1));
  const onsets = new Float64Array(N);
  for (let i = 0; i < N; i++) onsets[i] = env[i] / (stdv + TINY64);

  const bpm = estimateTempo(env);
  if (!(bpm > 0)) return { bpm: 0, beats: [] };
  const frameRate = BEAT_SR / HOP;
  const fpb = rint((frameRate * 60) / bpm);
  if (fpb <= 0) return { bpm, beats: [] };

  const localscore = new Float64Array(N);
  {
    const window = new Float64Array(2 * fpb + 1);
    for (let k = 0; k <= 2 * fpb; k++) {
      const d = ((k - fpb) * 32) / fpb;
      window[k] = Math.exp(-0.5 * d * d);
    }
    for (let i = 0; i < N; i++) {
      const jlo = Math.max(0, i - fpb);
      const jhi = Math.min(N - 1, i + fpb);
      let s = 0;
      for (let j = jlo; j <= jhi; j++) s += window[fpb + i - j] * onsets[j];
      localscore[i] = s;
    }
  }

  const cumscore = new Float64Array(N);
  const backlink = new Int32Array(N).fill(-1);
  let maxLocal = 0;
  for (let i = 0; i < N; i++) if (localscore[i] > maxLocal) maxLocal = localscore[i];
  const scoreThresh = 0.01 * maxLocal;
  const tightness = 100;
  const logFpb = Math.log(fpb);
  let firstBeat = true;
  for (let i = 0; i < N; i++) {
    let bestScore = -Infinity;
    let beatLocation = -1;
    const startLoc = i - rint(fpb / 2);
    const stopLoc = i - 2 * fpb;
    for (let loc = startLoc; loc >= stopLoc; loc--) {
      if (loc < 0) break;
      const d = Math.log(i - loc) - logFpb;
      const score = cumscore[loc] - tightness * d * d;
      if (score > bestScore) {
        bestScore = score;
        beatLocation = loc;
      }
    }
    cumscore[i] = localscore[i] + (beatLocation >= 0 ? bestScore : 0);
    if (firstBeat && localscore[i] < scoreThresh) {
      backlink[i] = -1;
    } else {
      backlink[i] = beatLocation;
      firstBeat = false;
    }
  }

  const lmaxIdx = localMaxIndices(cumscore);
  const sel = lmaxIdx.map((i) => cumscore[i]).sort((a, b) => a - b);
  const threshold = sel.length ? 0.5 * medianSorted(sel, sel.length) : 0;
  let tail = N - 1;
  const lmaxSet = new Uint8Array(N);
  for (const idx of lmaxIdx) lmaxSet[idx] = 1;
  for (let i = N - 1; i >= 0; i--) {
    if (lmaxSet[i] && cumscore[i] >= threshold) {
      tail = i;
      break;
    }
  }
  const beatsBool = new Uint8Array(N);
  let n = tail;
  while (n >= 0) {
    beatsBool[n] = 1;
    n = backlink[n];
  }

  // __trim_beats
  const sel2 = [];
  for (let i = 0; i < N; i++) if (beatsBool[i]) sel2.push(localscore[i]);
  if (sel2.length) {
    const h5 = hannSymmetric(5);
    const B = sel2.length;
    const conv = new Float64Array(B + 4);
    for (let a = 0; a < B; a++) {
      for (let b = 0; b < 5; b++) conv[a + b] += sel2[a] * h5[b];
    }
    const s0 = 2;
    const s1 = Math.min(B + 4, N + 2);
    let sum = 0;
    let cnt = 0;
    for (let i = s0; i < s1; i++) {
      sum += conv[i] * conv[i];
      cnt++;
    }
    const thr = cnt > 0 ? 0.5 * Math.sqrt(sum / cnt) : 0;
    let q = 0;
    while (q < N && localscore[q] <= thr) {
      beatsBool[q] = 0;
      q++;
    }
    q = N - 1;
    while (q >= 0 && localscore[q] <= thr) {
      beatsBool[q] = 0;
      q--;
    }
  }
  const beats = [];
  for (let i = 0; i < N; i++) if (beatsBool[i]) beats.push(i);
  return { bpm, beats };
}

export function computeBeatGrid(onset) {
  const pulse = plpPulse(onset);
  const beatsPlp = localMaxIndices(pulse);
  const bt = beatTrack(onset);
  const union = new Int32Array(bt.beats.length + beatsPlp.length);
  let k = 0;
  for (const b of bt.beats) union[k++] = b;
  for (const b of beatsPlp) union[k++] = b;
  union.sort();
  let w = 0;
  for (let i = 0; i < union.length; i++) {
    if (i === 0 || union[i] !== union[w - 1]) union[w++] = union[i];
  }
  return {
    pulse,
    beatsPlp,
    bpm: bt.bpm,
    beatsBt: bt.beats,
    beats: union.subarray(0, w),
  };
}
