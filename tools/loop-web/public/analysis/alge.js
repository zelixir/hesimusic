// Algorithm E: three-stage "whole-second waveform overlap" matcher.
//
// 1) Coarse (log-mel patch cosine, anchors every 250 ms, optional compression):
//    finds a superset of candidate pairs. Deliberately permissive.
// 2) Verify (4 s patches at 8 kHz, FFT normalized cross-correlation over
//    -4 s..+4 s lags): proves that the two windows really overlap and recovers
//    the exact lag. Hard rule: anchor distance >= 2 x patch length so the
//    trivial self-alignment peak (|lag| ~= distance) cannot exist.
// 3) Select (slide a 50 ms seam point through the aligned overlap): zero
//    crossing / short-term energy difference / flux jump pick the final A/B.
//
// Two hard rules from the user:
//   - distance >= 2 x window length (otherwise the two windows share audio and
//     NCC is dominated by the overlap -> false matches);
//   - normalized cross-correlation, never a plain dot product.
// Multi-peak NCC (peaks >= 0.95 x max) all go to stage 3.

import { fftPow2, irfft } from './fft.js';
import { hannPeriodic, mulberry32, clamp } from './dsp.js';
import { jacobiEigen, qrColumns, nextPow2 } from './linalg.js';
import { cheapSeamScore } from './seam.js';

const TINY = 1e-12;

function now() {
  return globalThis.performance ? globalThis.performance.now() : Date.now();
}

export const E_DEFAULTS = {
  anchorStepSec: 0.25,
  patchSec: 4,
  coarseTopK: 2000,
  coarseSimThreshold: 0.5,
  verifyMaxPairs: 250,
  verifyPeakThreshold: 0.8,
  verifyBudgetMs: 2500,
  maxPeaks: 5,
  multiPeakFactor: 0.95,
  minOverlapFrac: 0.5,
  selectStepMs: 50,
  compression: 'none',
  pcaDims: 256,
  rpDims: 256,
  slideVariants: 0,
  maxAnchors: 1600,
  seed: 20261121,
};

function sinc(x) {
  if (x === 0) return 1;
  const px = Math.PI * x;
  return Math.sin(px) / px;
}

// Windowed-sinc resampler (16 taps). Good enough for waveform NCC and
// independent of OfflineAudioContext (unavailable in workers).
const RESAMPLE_PHASES = 512;
const resampleTables = new Map();
function buildResampleTable(cutoff, radius) {
  const taps = 2 * radius + 1;
  const table = new Float64Array(RESAMPLE_PHASES * taps);
  for (let p = 0; p < RESAMPLE_PHASES; p++) {
    const frac = p / RESAMPLE_PHASES;
    for (let k = -radius; k <= radius; k++) {
      const d = frac - k;
      const win = 0.5 + 0.5 * Math.cos((Math.PI * d) / (radius + 1));
      table[p * taps + (k + radius)] = cutoff * sinc(cutoff * d) * win;
    }
  }
  return table;
}

export function resampleMono(pcm, sr, target) {
  if (sr === target) return Float32Array.from(pcm);
  const ratio = sr / target;
  const outLen = Math.floor((pcm.length * target) / sr);
  const out = new Float32Array(outLen);
  const cutoff = Math.min(1, target / sr);
  const radius = 8;
  const taps = 2 * radius + 1;
  const tableKey = cutoff + ':' + radius;
  let table = resampleTables.get(tableKey);
  if (!table) {
    table = buildResampleTable(cutoff, radius);
    resampleTables.set(tableKey, table);
  }
  const last = pcm.length - 1;
  for (let o = 0; o < outLen; o++) {
    const center = o * ratio;
    const base = Math.floor(center);
    const phase = ((center - base) * RESAMPLE_PHASES) | 0;
    const row = phase * taps;
    let acc = 0;
    let wsum = 0;
    for (let k = -radius; k <= radius; k++) {
      let pos = base + k;
      if (pos < 0) pos = 0;
      else if (pos > last) pos = last;
      const w = table[row + (k + radius)];
      acc += w * pcm[pos];
      wsum += w;
    }
    out[o] = wsum !== 0 ? acc / wsum : 0;
  }
  return out;
}

function melBasisGeneric(sr, nfft, nMels, fmax) {
  const bins = nfft / 2 + 1;
  const hzToMel = (f) => (f >= 1000 ? 15 + Math.log(f / 1000) / (Math.log(6.4) / 27) : f / (200 / 3));
  const melToHz = (m) => (m >= 15 ? 1000 * Math.exp((Math.log(6.4) / 27) * (m - 15)) : (200 / 3) * m);
  const m0 = hzToMel(0);
  const m1 = hzToMel(Math.min(fmax, sr / 2));
  const pts = new Float64Array(nMels + 2);
  for (let i = 0; i < nMels + 2; i++) pts[i] = melToHz(m0 + ((m1 - m0) * i) / (nMels + 1));
  const lo = new Int32Array(nMels);
  const hi = new Int32Array(nMels);
  const w = new Float64Array(nMels * bins);
  for (let i = 0; i < nMels; i++) {
    const fd0 = pts[i + 1] - pts[i];
    const fd1 = pts[i + 2] - pts[i + 1];
    const enorm = 2 / (pts[i + 2] - pts[i]);
    let l = bins;
    let h = -1;
    for (let k = 0; k < bins; k++) {
      const ff = (k * sr) / nfft;
      const lower = (ff - pts[i]) / fd0;
      const upper = (pts[i + 2] - ff) / fd1;
      let wv = Math.max(0, Math.min(lower, upper)) * enorm;
      wv = Math.fround(wv);
      w[i * bins + k] = wv;
      if (wv !== 0) {
        if (k < l) l = k;
        if (k > h) h = k;
      }
    }
    lo[i] = l;
    hi[i] = h;
  }
  return { bins, lo, hi, w };
}

export function computeLogMel(pcm, sr, nMels = 64, hopSec = 0.032) {
  const nfft = sr >= 32000 ? 2048 : 1024;
  const hop = Math.max(1, Math.round(hopSec * sr));
  const T = Math.floor(pcm.length / hop) + 1;
  const frames = T;
  const bins = nfft / 2 + 1;
  const win = hannPeriodic(nfft);
  const basis = melBasisGeneric(sr, nfft, nMels, 8000);
  const re = new Float64Array(nfft);
  const im = new Float64Array(nfft);
  const data = new Float32Array(T * nMels);
  const mel = new Float64Array(nMels);
  for (let t = 0; t < T; t++) {
    const begin = t * hop - (nfft >> 1);
    for (let i = 0; i < nfft; i++) {
      const pos = begin + i;
      re[i] = (pos >= 0 && pos < pcm.length ? pcm[pos] : 0) * win[i];
      im[i] = 0;
    }
    fftPow2(re, im, nfft, false);
    for (let m = 0; m < nMels; m++) {
      let s = 0;
      const off = m * bins;
      const l = basis.lo[m];
      const h = basis.hi[m];
      for (let k = l; k <= h; k++) s += basis.w[off + k] * (re[k] * re[k] + im[k] * im[k]);
      mel[m] = s;
    }
    for (let m = 0; m < nMels; m++) data[t * nMels + m] = Math.log(Math.max(1e-10, mel[m]));
  }
  return { data, frames, nMels, hop, nfft, sr };
}

function normalizeRows(vec, N, dim) {
  for (let i = 0; i < N; i++) {
    let s = 0;
    for (let d = 0; d < dim; d++) s += vec[i * dim + d] * vec[i * dim + d];
    const n = Math.sqrt(s);
    if (n > TINY) for (let d = 0; d < dim; d++) vec[i * dim + d] /= n;
  }
}

function projectAll(patches, N, D, comp, k) {
  const out = new Float32Array(N * k);
  for (let i = 0; i < N; i++) {
    for (let j = 0; j < k; j++) {
      let s = 0;
      for (let d = 0; d < D; d++) s += (patches[i * D + d] - comp.mean[d]) * comp.components[d * k + j];
      out[i * k + j] = s;
    }
  }
  normalizeRows(out, N, k);
  return out;
}

function compressPatches(patches, N, D, opts) {
  const mode = opts.compression || 'none';
  if (mode === 'none') {
    normalizeRows(patches, N, D);
    return { mode, vectors: patches, dim: D, varianceRetention: null, distortion: null, quality: null };
  }
  if (mode === 'rp') {
    const k = Math.min(opts.rpDims || 256, D);
    const rng = mulberry32(opts.seed || 20261121);
    const r = new Float64Array(D * k);
    const scale = 1 / Math.sqrt(k);
    for (let i = 0; i < D * k; i++) r[i] = rng.normal() * scale;
    const raw = new Float64Array(N * k);
    for (let i = 0; i < N; i++) {
      for (let d = 0; d < D; d++) {
        const x = patches[i * D + d];
        if (x === 0) continue;
        for (let j = 0; j < k; j++) raw[i * k + j] += x * r[d * k + j];
      }
    }
    // distance distortion on sampled anchor pairs (full vs projected, before L2)
    const sample = Math.min(N, 200);
    let sumErr = 0;
    let maxErr = 0;
    let cnt = 0;
    const rng2 = mulberry32((opts.seed || 20261121) ^ 0x9e3779b9);
    for (let s = 0; s < sample; s++) {
      const i = Math.floor(rng2.next() * N);
      const j = Math.floor(rng2.next() * N);
      if (i === j) continue;
      let df = 0;
      let dp = 0;
      for (let d = 0; d < D; d++) {
        const q = patches[i * D + d] - patches[j * D + d];
        df += q * q;
      }
      for (let d = 0; d < k; d++) {
        const q = raw[i * k + d] - raw[j * k + d];
        dp += q * q;
      }
      df = Math.sqrt(df);
      dp = Math.sqrt(dp);
      if (df > 1e-9) {
        const e = Math.abs(dp - df) / df;
        sumErr += e;
        if (e > maxErr) maxErr = e;
        cnt++;
      }
    }
    const vectors = new Float32Array(N * k);
    for (let i = 0; i < N * k; i++) vectors[i] = raw[i];
    normalizeRows(vectors, N, k);
    return {
      mode,
      vectors,
      dim: k,
      varianceRetention: null,
      distortion: cnt ? { mean: sumErr / cnt, max: maxErr } : null,
      quality: null,
    };
  }
  // Randomized PCA (sample-fit Gram + subspace projection).
  const S = Math.min(N, 512);
  // Keep k well below the sample count so the reported variance retention is
  // meaningful (otherwise a 225-anchor file would trivially retain 100%).
  const k = Math.min(opts.pcaDims || 256, Math.max(1, Math.floor(S / 2)), D);
  const idx = new Int32Array(S);
  for (let i = 0; i < S; i++) idx[i] = Math.floor((i * N) / S);
  const mean = new Float64Array(D);
  for (let s = 0; s < S; s++) {
    const row = idx[s] * D;
    for (let d = 0; d < D; d++) mean[d] += patches[row + d];
  }
  for (let d = 0; d < D; d++) mean[d] /= S;
  const xs = new Float64Array(S * D);
  let totalSq = 0;
  for (let s = 0; s < S; s++) {
    const row = idx[s] * D;
    for (let d = 0; d < D; d++) {
      const v = patches[row + d] - mean[d];
      xs[s * D + d] = v;
      totalSq += v * v;
    }
  }
  const rng = mulberry32(opts.seed || 20261121);
  const omega = new Float64Array(D * k);
  for (let i = 0; i < D * k; i++) omega[i] = rng.normal();
  const y = new Float64Array(S * k);
  for (let s = 0; s < S; s++) {
    for (let d = 0; d < D; d++) {
      const xv = xs[s * D + d];
      if (xv === 0) continue;
      for (let j = 0; j < k; j++) y[s * k + j] += xv * omega[d * k + j];
    }
  }
  const { q } = qrColumns(y, S, k);
  const b = new Float64Array(k * D);
  for (let s = 0; s < S; s++) {
    for (let j = 0; j < k; j++) {
      const qv = q[s * k + j];
      if (qv === 0) continue;
      for (let d = 0; d < D; d++) b[j * D + d] += qv * xs[s * D + d];
    }
  }
  const c = new Float64Array(k * k);
  for (let j1 = 0; j1 < k; j1++) {
    for (let j2 = j1; j2 < k; j2++) {
      let s = 0;
      for (let d = 0; d < D; d++) s += b[j1 * D + d] * b[j2 * D + d];
      c[j1 * k + j2] = s;
      c[j2 * k + j1] = s;
    }
  }
  const eig = jacobiEigen(c, k);
  const order = Array.from({ length: k }, (_, i) => i).sort((a, b2) => eig.values[b2] - eig.values[a]);
  let kept = 0;
  for (const o of order) if (eig.values[o] > 0) kept += eig.values[o];
  const components = new Float64Array(D * k);
  const lambdaFloor = Math.max(kept, 1e-12) * 1e-9;
  for (let j = 0; j < k; j++) {
    const col = order[j];
    // PCA whitening: divide each principal direction by sqrt(eigenvalue), so
    // every retained dimension contributes unit variance before L2 normalization.
    const scale = 1 / Math.sqrt(Math.max(eig.values[col], lambdaFloor));
    for (let d = 0; d < D; d++) {
      let s = 0;
      for (let p = 0; p < k; p++) s += b[p * D + d] * eig.vectors[p * k + col];
      components[d * k + j] = s * scale;
    }
  }
  const vectors = projectAll(patches, N, D, { mean, components }, k);
  return {
    mode,
    vectors,
    dim: k,
    varianceRetention: totalSq > 0 ? clamp(kept / totalSq, 0, 1) : 1,
    distortion: null,
    quality: null,
  };
}

function top20Overlap(patches, vectors, dim, N, D, sr, minDistSamples, maxDistSamples, stepSamples) {
  const subset = Math.min(N, 600);
  const idx = new Int32Array(subset);
  for (let i = 0; i < subset; i++) idx[i] = Math.floor((i * N) / subset);
  const fullPairs = [];
  const compPairs = [];
  for (let a = 0; a < subset; a++) {
    for (let b = a + 1; b < subset; b++) {
      const d = (idx[b] - idx[a]) * stepSamples;
      if (d < minDistSamples || d > maxDistSamples) continue;
      const i = idx[a];
      const j = idx[b];
      let dotFull = 0;
      for (let q = 0; q < D; q++) dotFull += patches[i * D + q] * patches[j * D + q];
      let dotComp = 0;
      for (let q = 0; q < dim; q++) dotComp += vectors[i * dim + q] * vectors[j * dim + q];
      fullPairs.push({ key: i * N + j, sim: dotFull });
      compPairs.push({ key: i * N + j, sim: dotComp });
    }
  }
  const top = (arr) => {
    arr.sort((a, b) => b.sim - a.sim);
    return new Set(arr.slice(0, 20).map((x) => x.key));
  };
  const fa = top(fullPairs);
  const ca = top(compPairs);
  let inter = 0;
  for (const k of fa) if (ca.has(k)) inter++;
  return { overlap: inter / Math.max(1, Math.min(20, fa.size)), fullTop: [...fa], compTop: [...ca] };
}

function heapPush(heap, item, K) {
  if (heap.length < K) {
    heap.push(item);
    let i = heap.length - 1;
    while (i > 0) {
      const p = (i - 1) >> 1;
      if (heap[p].sim <= heap[i].sim) break;
      const t = heap[p];
      heap[p] = heap[i];
      heap[i] = t;
      i = p;
    }
  } else if (item.sim > heap[0].sim) {
    heap[0] = item;
    let i = 0;
    for (;;) {
      const l = 2 * i + 1;
      const r = l + 1;
      let m = i;
      if (l < heap.length && heap[l].sim < heap[m].sim) m = l;
      if (r < heap.length && heap[r].sim < heap[m].sim) m = r;
      if (m === i) break;
      const t = heap[m];
      heap[m] = heap[i];
      heap[i] = t;
      i = m;
    }
  }
}

function buildAnchors(nSamples, sr, opts) {
  const stepSamples = Math.max(1, Math.round(opts.anchorStepSec * sr));
  const patchLen = Math.round(opts.patchSec * sr);
  let count = Math.floor((nSamples - patchLen) / stepSamples) + 1;
  if (count < 2) return null;
  let step = stepSamples;
  while (count > opts.maxAnchors) {
    step += stepSamples;
    count = Math.floor((nSamples - patchLen) / step) + 1;
  }
  const times = new Float64Array(count);
  const samples = new Float64Array(count);
  const rms = new Float64Array(count);
  for (let i = 0; i < count; i++) {
    samples[i] = i * step;
    times[i] = samples[i] / sr;
  }
  return { count, stepSamples: step, samples, times, rms, effectiveStepSec: step / sr };
}

function buildPatches(lm, anchors, patchFrames, nMels) {
  const N = anchors.count;
  const D = patchFrames * nMels;
  const patches = new Float32Array(N * D);
  for (let i = 0; i < N; i++) {
    const af = Math.round(anchors.samples[i] / lm.hop);
    for (let f = 0; f < patchFrames; f++) {
      const src = (af + f) * nMels;
      const dst = i * D + f * nMels;
      for (let m = 0; m < nMels; m++) patches[dst + m] = lm.data[src + m];
    }
  }
  return { patches, D, patchFrames };
}

function patchCosineAt(data, nMels, frameA, frameB, frames) {
  let dot = 0;
  let na = 0;
  let nb = 0;
  for (let f = 0; f < frames; f++) {
    const rowa = (frameA + f) * nMels;
    const rowb = (frameB + f) * nMels;
    for (let m = 0; m < nMels; m++) {
      const a = data[rowa + m];
      const b = data[rowb + m];
      dot += a * b;
      na += a * a;
      nb += b * b;
    }
  }
  return dot / (Math.sqrt(na * nb) + TINY);
}

function coarseSearch(vectors, dim, N, anchors, lm, patchInfo, opts, sr, active) {
  const t0 = now();
  const minLoopSamples = Math.max(opts.minLoopSec * sr, 2 * opts.patchSec * sr);
  const maxLoopSamples = opts.maxLoopSec * sr;
  const minOverlapSamples = 2 * opts.patchSec * sr;
  const topK = Math.max(1, opts.coarseTopK | 0);
  const threshold = opts.coarseSimThreshold;
  const heap = [];
  let checked = 0;
  let above = 0;
  let overlapDropped = 0;
  let silentSkipped = 0;
  const stepSamples = anchors.stepSamples;
  const variants = opts.slideVariants | 0;
  const variantOffsetFrames = Math.round((0.25 * sr) / lm.hop);
  const maxFrames = lm.frames;
  const patchFrames = patchInfo.patchFrames;
  for (let i = 0; i < N; i++) {
    for (let j = i + 1; j < N; j++) {
      const d = (j - i) * stepSamples;
      if (d > maxLoopSamples) break;
      if (d < minOverlapSamples) {
        overlapDropped++;
        continue;
      }
      if (d < minLoopSamples) continue;
      if (active && (!active[i] || !active[j])) { silentSkipped++; continue; }
      checked++;
      let sim = 0;
      if (variants > 0 && compDimIsFull(dim, patchInfo, lm)) {
        const afi = Math.round(anchors.samples[i] / lm.hop);
        const afj = Math.round(anchors.samples[j] / lm.hop);
        for (let o = -(variants - 1); o <= variants - 1; o++) {
          const shift = o * variantOffsetFrames;
          const bf = afj + shift;
          if (bf < 0 || bf + patchFrames > maxFrames) continue;
          const s = patchCosineAt(lm.data, lm.nMels, afi, bf, patchFrames);
          if (s > sim) sim = s;
        }
      } else {
        let dot = 0;
        for (let q = 0; q < dim; q++) dot += vectors[i * dim + q] * vectors[j * dim + q];
        sim = dot;
      }
      if (sim < threshold) continue;
      above++;
      heapPush(heap, { sim, i, j }, topK);
    }
  }
  heap.sort((a, b) => b.sim - a.sim);
  return {
    pairs: heap,
    ms: now() - t0,
    stats: {
      checked,
      aboveThreshold: above,
      overlapDropped,
      silentSkipped,
      kept: heap.length,
      minLoopSamples,
      maxLoopSamples,
      minDistanceSec: minOverlapSamples / sr,
    },
  };
}

function compDimIsFull(dim, patchInfo, lm) {
  return dim === patchInfo.patchFrames * lm.nMels;
}

function verifyPair(pcm8k, L, F, buffers, a8, b8, opts) {
  const { xRe, xIm, yRe, yIm, cRe, cIm, corr, px, py, ncc } = buffers;
  let mx = 0;
  let my = 0;
  for (let i = 0; i < L; i++) {
    mx += pcm8k[a8 + i];
    my += pcm8k[b8 + i];
  }
  mx /= L;
  my /= L;
  for (let i = 0; i < L; i++) {
    xRe[i] = pcm8k[a8 + i] - mx;
    yRe[i] = pcm8k[b8 + i] - my;
    xIm[i] = 0;
    yIm[i] = 0;
  }
  for (let i = L; i < F; i++) {
    xRe[i] = 0;
    xIm[i] = 0;
    yRe[i] = 0;
    yIm[i] = 0;
  }
  px[0] = 0;
  py[0] = 0;
  for (let i = 0; i < L; i++) {
    px[i + 1] = px[i] + xRe[i] * xRe[i];
    py[i + 1] = py[i] + yRe[i] * yRe[i];
  }
  fftPow2(xRe, xIm, F, false);
  fftPow2(yRe, yIm, F, false);
  for (let k = 0; k <= F / 2; k++) {
    // conj(X) * Y -> IFFT gives corr[lag] = sum_t x[t] * y[t + lag]
    cRe[k] = xRe[k] * yRe[k] + xIm[k] * yIm[k];
    cIm[k] = xRe[k] * yIm[k] - xIm[k] * yRe[k];
  }
  irfft(cRe, cIm, F, corr);
  let globalMax = -2;
  let globalLag = 0;
  const minOverlap = Math.max(1, Math.floor(opts.minOverlapFrac * L));
  for (let lag = -L + 1; lag <= L - 1; lag++) {
    const overlap = L - Math.abs(lag);
    if (overlap < minOverlap) {
      ncc[lag + L - 1] = -2;
      continue;
    }
    const corrVal = lag >= 0 ? corr[lag] : corr[F + lag];
    const ex = lag >= 0 ? px[L - lag] : px[L] - px[-lag];
    const ey = lag >= 0 ? py[L] - py[lag] : py[L + lag];
    const den = Math.sqrt(Math.max(ex * ey, 0));
    const v = den > TINY ? corrVal / den : 0;
    ncc[lag + L - 1] = v;
    if (v > globalMax) {
      globalMax = v;
      globalLag = lag;
    }
  }
  if (globalMax < opts.verifyPeakThreshold) {
    return { accepted: false, reason: 'peak', peak: globalMax, lag: globalLag };
  }
  const candidates = [];
  for (let lag = -L + 2; lag <= L - 2; lag++) {
    const v = ncc[lag + L - 1];
    if (v < opts.verifyPeakThreshold || v < opts.multiPeakFactor * globalMax) continue;
    if (v >= ncc[lag + L - 2] && v >= ncc[lag + L]) {
      candidates.push({ lag, peak: v, overlapRatio: (L - Math.abs(lag)) / L });
    }
  }
  candidates.sort((a, b) => b.peak - a.peak);
  const peaks = [];
  const minSep = Math.max(1, Math.round(0.1 * 8000));
  for (const c of candidates) {
    let ok = true;
    for (const p of peaks) if (Math.abs(p.lag - c.lag) < minSep) { ok = false; break; }
    if (ok) peaks.push(c);
    if (peaks.length >= opts.maxPeaks) break;
  }
  return { accepted: peaks.length > 0, peaks, peak: globalMax, lag: globalLag };
}

function selectSeam(pcm8k, a8, b8, lag, L, opts) {
  const guard = Math.round(0.05 * 8000);
  const sMin = Math.max(0, -lag) + guard;
  const sMax = Math.min(L, L - lag) - guard - 1;
  if (sMax <= sMin) return null;
  const step = Math.max(1, Math.round((opts.selectStepMs / 1000) * 8000));
  const evalAt = (s) => cheapSeamScore(pcm8k, 8000, a8 + s, b8 + s + lag);
  let bestS = sMin;
  let best = null;
  for (let s = sMin; s <= sMax; s += step) {
    const e = evalAt(s);
    if (!best || e.score > best.score) {
      best = e;
      bestS = s;
    }
  }
  let radius = step;
  for (let pass = 0; pass < 2; pass++) {
    const sub = Math.max(1, Math.round(radius / 5));
    const lo = Math.max(sMin, bestS - radius);
    const hi = Math.min(sMax, bestS + radius);
    for (let s = lo; s <= hi; s += sub) {
      const e = evalAt(s);
      if (e.score > best.score) {
        best = e;
        bestS = s;
      }
    }
    radius = sub;
  }
  return { s: bestS, score: best };
}

export function runAlgorithmE(pcm, sr, minLoopSec, maxLoopSec, rawOpts, hooks = {}) {
  const opts = { ...E_DEFAULTS, ...rawOpts, minLoopSec, maxLoopSec };
  const t0 = now();
  const lm = computeLogMel(pcm, sr, 64, 0.032);
  const tFeature = now();
  const anchors = buildAnchors(pcm.length, sr, opts);
  if (!anchors) {
    return { ok: false, reason: '音频太短，无法放置 4 秒锚点窗' };
  }
  const patchFrames = Math.max(8, Math.floor(sr / lm.hop));
  const patchInfo = buildPatches(lm, anchors, patchFrames, lm.nMels);
  const active = new Uint8Array(anchors.count);
  let maxRms = 0;
  const patchLenSamples = Math.round(opts.patchSec * sr);
  for (let i = 0; i < anchors.count; i++) {
    const start = anchors.samples[i];
    let s = 0;
    for (let q = 0; q < patchLenSamples; q++) {
      const v = pcm[start + q];
      s += v * v;
    }
    anchors.rms[i] = Math.sqrt(s / patchLenSamples);
    if (anchors.rms[i] > maxRms) maxRms = anchors.rms[i];
  }
  const rmsGate = Math.max(1e-5, maxRms * 0.01);
  for (let i = 0; i < anchors.count; i++) active[i] = anchors.rms[i] >= rmsGate ? 1 : 0;
  const comp = compressPatches(patchInfo.patches, anchors.count, patchInfo.D, opts);
  const tCompress = now();
  const coarse = coarseSearch(comp.vectors, comp.dim, anchors.count, anchors, lm, patchInfo, opts, sr, active);
  const tCoarse = now();
  if (hooks.onProgress) hooks.onProgress(0.4, 'e-coarse');

  const resampleStart = now();
  const pcm8k = resampleMono(pcm, sr, 8000);
  const resampleMs = now() - resampleStart;
  const L = Math.round(opts.patchSec * 8000);
  const F = nextPow2(2 * L + 1);
  const buffers = {
    xRe: new Float64Array(F), xIm: new Float64Array(F),
    yRe: new Float64Array(F), yIm: new Float64Array(F),
    cRe: new Float64Array(F), cIm: new Float64Array(F),
    corr: new Float64Array(F),
    px: new Float64Array(L + 1), py: new Float64Array(L + 1),
    ncc: new Float64Array(2 * L - 1),
  };
  const verifyStart = now();
  const matches = [];
  let verifyDone = 0;
  let budgetHit = false;
  let lowPeak = 0;
  let selfAlign = 0;
  const lowPeakExamples = [];
  const verifyCap = Math.min(opts.verifyMaxPairs, coarse.pairs.length);
  for (let pi = 0; pi < verifyCap; pi++) {
    if (now() - verifyStart > opts.verifyBudgetMs) {
      budgetHit = true;
      break;
    }
    const pair = coarse.pairs[pi];
    const a8 = Math.round((anchors.samples[pair.i] * 8000) / sr);
    const b8 = Math.round((anchors.samples[pair.j] * 8000) / sr);
    if (a8 + L > pcm8k.length || b8 + L > pcm8k.length) continue;
    const d8 = b8 - a8;
    const res = verifyPair(pcm8k, L, F, buffers, a8, b8, opts);
    verifyDone++;
    if (!res.accepted) {
      lowPeak++;
      if (lowPeakExamples.length < 10) {
        lowPeakExamples.push({
          anchorA: anchors.times[pair.i],
          anchorB: anchors.times[pair.j],
          peak: res.peak,
          lagMs: (res.lag / 8000) * 1000,
          reason: 'NCC 峰值低于阈值',
        });
      }
      continue;
    }
    for (const peak of res.peaks) {
      // Self-alignment guard: an alignment whose lag is ~= the anchor distance
      // collapses the two windows onto the same audio, and any alignment that
      // leaves no real global overlap is also rejected. With the enforced
      // distance >= 2 x window these should never fire, but the guard is kept
      // as a defence if that constraint is ever relaxed.
      if (Math.abs(peak.lag) >= 0.9 * d8 || Math.abs(peak.lag) > d8 - L * (1 - opts.minOverlapFrac)) {
        selfAlign++;
        continue;
      }
      matches.push({
        i: pair.i,
        j: pair.j,
        anchorA: anchors.times[pair.i],
        anchorB: anchors.times[pair.j],
        lagSamples8k: peak.lag,
        lagMs: (peak.lag / 8000) * 1000,
        peak: peak.peak,
        overlapRatio: peak.overlapRatio,
        peakCount: res.peaks.length,
        similarity: pair.sim,
        a8,
        b8,
      });
    }
    if ((pi & 7) === 0 && hooks.onProgress) hooks.onProgress(0.5 + 0.2 * (pi / verifyCap), 'e-verify');
  }
  const tVerify = now();

  const candidates = [];
  const seen = new Set();
  for (const m of matches) {
    const sel = selectSeam(pcm8k, m.a8, m.b8, m.lagSamples8k, L, opts);
    if (!sel) continue;
    const A8 = m.a8 + sel.s;
    const B8 = m.b8 + sel.s + m.lagSamples8k;
    const A = Math.round((A8 * sr) / 8000);
    const B = Math.round((B8 * sr) / 8000);
    if (A < 0 || B >= pcm.length || B - A < Math.round(0.1 * sr)) continue;
    const key = Math.round(A / (0.01 * sr)) + ':' + Math.round(B / (0.01 * sr));
    if (seen.has(key)) continue;
    seen.add(key);
    candidates.push({
      anchorA: m.anchorA,
      anchorB: m.anchorB,
      similarity: m.similarity,
      nccPeak: m.peak,
      bestLagMs: m.lagMs,
      overlapRatio: m.overlapRatio,
      peakCount: m.peakCount,
      A,
      B,
      aMs: (A / sr) * 1000,
      bMs: (B / sr) * 1000,
      lengthMs: ((B - A) / sr) * 1000,
      select: {
        score: sel.score.score,
        zc: sel.score.zc,
        energy: sel.score.energy,
        flux: sel.score.flux,
        step: sel.score.step,
        content: sel.score.content,
        loudDiffDb: sel.score.loudDiffDb,
        fluxRaw: sel.score.fluxRaw,
        stepRaw: sel.score.stepRaw,
      },
    });
  }
  candidates.sort((a, b) => b.select.score - a.select.score);
  const tSelect = now();

  // Compression quality check (top-20 overlap / variance / distortion).
  let quality = null;
  const qcStart = now();
  if (comp.mode !== 'none') {
    const ov = top20Overlap(
      patchInfo.patches, comp.vectors, comp.dim, anchors.count, patchInfo.D, sr,
      Math.max(opts.minLoopSec * sr, 2 * opts.patchSec * sr), opts.maxLoopSec * sr,
      anchors.stepSamples,
    );
    quality = {
      top20Overlap: ov.overlap,
      varianceRetention: comp.varianceRetention,
      distanceDistortion: comp.distortion,
      dim: comp.dim,
      fullDim: patchInfo.D,
      qcMs: now() - qcStart,
    };
  } else {
    quality = { top20Overlap: 1, varianceRetention: null, distanceDistortion: null, dim: patchInfo.D, fullDim: patchInfo.D, qcMs: 0 };
  }

  // Debug: first 10 pairs rejected by the overlap rule (for UI inspection).
  const dropped = [];
  for (let i = 0; i < anchors.count && dropped.length < 10; i++) {
    for (let j = i + 1; j < anchors.count && dropped.length < 10; j++) {
      const d = (j - i) * anchors.stepSamples;
      if (d >= 2 * opts.patchSec * sr) break;
      dropped.push({
        anchorA: anchors.times[i],
        anchorB: anchors.times[j],
        distanceSec: (anchors.times[j] - anchors.times[i]),
        reason: '两窗距离 < 2x窗长，会共享音频（假匹配）',
      });
    }
  }

  return {
    ok: true,
    candidates,
    matches: matches.slice(0, 120),
    timings: {
      featureMs: tFeature - t0,
      compressMs: tCompress - tFeature,
      coarseMs: tCoarse - tCompress,
      verifyMs: tVerify - tCoarse,
      selectMs: tSelect - tVerify,
      resampleMs,
      qualityMs: quality ? quality.qcMs : 0,
      totalMs: now() - t0,
    },
    stats: {
      anchors: anchors.count,
      resampleMs,
      stepSec: anchors.effectiveStepSec,
      patchSec: opts.patchSec,
      patchDim: patchInfo.D,
      coarseChecked: coarse.stats.checked,
      coarseAboveThreshold: coarse.stats.aboveThreshold,
      coarseKept: coarse.stats.kept,
      overlapDropped: coarse.stats.overlapDropped,
      silentSkipped: coarse.stats.silentSkipped,
      activeAnchors: active.reduce((a, b) => a + b, 0),
      verifyRequested: verifyCap,
      verifyDone,
      verifyBudgetHit: budgetHit,
      lowPeak,
      selfAlign,
      matches: matches.length,
      candidates: candidates.length,
      compression: comp.mode,
      compressionDim: comp.dim,
      varianceRetention: comp.varianceRetention,
      distanceDistortion: comp.distortion,
      top20Overlap: quality.top20Overlap,
    },
    compression: quality,
    debug: { droppedOverlap: dropped, lowPeakExamples },
  };
}
