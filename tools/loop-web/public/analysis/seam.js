// Seam quality metrics for a candidate loop [A, B):
//   - normalized waveform cross-correlation of [B-W, B) vs [A, A+W) (best lag,
//     peak), computed on 4x-decimated 50 ms windows for speed;
//   - short-term RMS (dB) difference of the same two windows;
//   - spectral-flux jump at the junction vs the surrounding frames;
//   - junction sample step normalized by local RMS.
// The synthetic 0..1 seamQuality combines them (weights documented in README).

import { fftPow2 } from './fft.js';
import { hannPeriodic } from './dsp.js';

const FLUX_NFFT = 1024;
const FLUX_HOP = 256;

export function createSeamEvaluator(pcm, sampleRate) {
  const N = pcm.length;
  const sr = sampleRate;
  const cache = new Map();
  const hann = hannPeriodic(FLUX_NFFT);
  const fftRe = new Float64Array(FLUX_NFFT);
  const fftIm = new Float64Array(FLUX_NFFT);
  const frames = [];
  const magBins = FLUX_NFFT / 2 + 1;

  function computeFlux(A, B) {
    let near = Math.min(Math.round(0.04 * sr), A, N - A, B, N - B);
    if (near < FLUX_NFFT) near = Math.min(Math.floor(FLUX_NFFT / 2), A, B, N - A);
    if (near < 8) return { seamFlux: 0, sideFlux: 0, fluxJump: 0, fluxRatio: 1, fluxJumpDb: 0 };
    const segLen = 2 * near;
    const seg = new Float64Array(segLen);
    for (let i = 0; i < near; i++) {
      seg[i] = pcm[B - near + i];
      seg[near + i] = pcm[A + i];
    }
    const J = near;
    frames.length = 0;
    for (let s = 0; s + FLUX_NFFT <= segLen; s += FLUX_HOP) {
      for (let i = 0; i < FLUX_NFFT; i++) {
        fftRe[i] = seg[s + i] * hann[i];
        fftIm[i] = 0;
      }
      fftPow2(fftRe, fftIm, FLUX_NFFT, false);
      const mag = new Float64Array(magBins);
      let norm = 0;
      for (let k = 0; k < magBins; k++) {
        const m = Math.hypot(fftRe[k], fftIm[k]);
        mag[k] = m;
        norm += m * m;
      }
      norm = Math.sqrt(norm);
      if (norm > 1e-12) for (let k = 0; k < magBins; k++) mag[k] /= norm;
      frames.push({ s, mag });
    }
    if (frames.length < 3) return { seamFlux: 0, sideFlux: 0, fluxJump: 0, fluxRatio: 1, fluxJumpDb: 0 };
    const fluxes = new Float64Array(frames.length - 1);
    for (let t = 1; t < frames.length; t++) {
      const a = frames[t - 1].mag;
      const b = frames[t].mag;
      let s = 0;
      for (let k = 0; k < magBins; k++) {
        const d = b[k] - a[k];
        s += d * d;
      }
      fluxes[t - 1] = Math.sqrt(s);
    }
    // transitions whose windows straddle the junction
    const seamIdx = [];
    for (let t = 1; t < frames.length; t++) {
      const s0 = frames[t - 1].s;
      const s1 = frames[t].s;
      if (s0 <= J && J < s1 + FLUX_NFFT) seamIdx.push(t - 1);
    }
    const isSeam = new Uint8Array(fluxes.length);
    for (const i of seamIdx) isSeam[i] = 1;
    let seamFlux = 0;
    if (seamIdx.length) {
      for (const i of seamIdx) if (fluxes[i] > seamFlux) seamFlux = fluxes[i];
    }
    const side = [];
    for (let i = 0; i < fluxes.length; i++) if (!isSeam[i]) side.push(fluxes[i]);
    side.sort((a, b) => a - b);
    const sideFlux = side.length ? side[side.length >> 1] : 0;
    const excess = Math.max(0, (seamFlux - sideFlux) / (sideFlux + 0.01));
    const ratio = seamFlux / (sideFlux + 1e-9);
    const jumpDb = 20 * Math.log10((seamFlux + 1e-4) / (sideFlux + 1e-4));
    return { seamFlux, sideFlux, fluxJump: excess, fluxRatio: ratio, fluxJumpDb: jumpDb };
  }

  function crossCorrelation(A, B) {
    const W = Math.min(Math.round(0.05 * sr), A, N - A, B);
    if (W < Math.max(32, Math.round(0.005 * sr))) return null;
    const D = Math.max(1, Math.round(W / 600));
    const Wd = Math.floor(W / D);
    const x = new Float64Array(Wd);
    const y = new Float64Array(Wd);
    for (let d = 0; d < Wd; d++) {
      let sx = 0;
      let sy = 0;
      const base = d * D;
      for (let k = 0; k < D; k++) {
        sx += pcm[B - W + base + k];
        sy += pcm[A + base + k];
      }
      x[d] = sx / D;
      y[d] = sy / D;
    }
    let mx = 0;
    let my = 0;
    for (let i = 0; i < Wd; i++) {
      mx += x[i];
      my += y[i];
    }
    mx /= Wd;
    my /= Wd;
    const px = new Float64Array(Wd + 1);
    const py = new Float64Array(Wd + 1);
    for (let i = 0; i < Wd; i++) {
      const a = x[i] - mx;
      const b = y[i] - my;
      x[i] = a;
      y[i] = b;
      px[i + 1] = px[i] + a * a;
      py[i + 1] = py[i] + b * b;
    }
    const L = Math.max(1, Math.min(Math.round((0.01 * sr) / D), Math.floor(Wd / 2)));
    const ncc = new Float64Array(2 * L + 1);
    let best = -Infinity;
    let bestLag = 0;
    for (let lag = -L; lag <= L; lag++) {
      const lo = Math.max(0, -lag);
      const hi = Math.min(Wd, Wd - lag);
      let dot = 0;
      for (let i = lo; i < hi; i++) dot += x[i] * y[i + lag];
      const ex = px[hi] - px[lo];
      const ey = py[hi + lag] - py[lo + lag];
      const den = Math.sqrt(Math.max(ex * ey, 0));
      const v = den > 1e-20 ? dot / den : 0;
      ncc[lag + L] = v;
      if (v > best) {
        best = v;
        bestLag = lag;
      }
    }
    let lagInterp = bestLag;
    if (bestLag > -L && bestLag < L) {
      const c = ncc[bestLag + L];
      const l = ncc[bestLag + L - 1];
      const r = ncc[bestLag + L + 1];
      const denom = l - 2 * c + r;
      if (Math.abs(denom) > 1e-12) {
        const delta = (0.5 * (l - r)) / denom;
        if (Math.abs(delta) <= 1) lagInterp = bestLag + delta;
      }
    }
    // sample-domain lag: positive means y (head) is earlier in phase
    const lagSamples = lagInterp * D;
    return {
      peak: Math.max(0, Math.min(1, best)),
      lagMs: (lagSamples / sr) * 1000,
      W,
    };
  }

  function evaluate(A, B) {
    const key = A + ':' + B;
    const hit = cache.get(key);
    if (hit) return hit;
    const W = Math.min(Math.round(0.05 * sr), A, N - A, B);
    let rmsDiffDb = 0;
    let step = 0;
    if (W >= 8) {
      let sx = 0;
      let sy = 0;
      for (let i = 0; i < W; i++) {
        const l = pcm[B - W + i];
        const r = pcm[A + i];
        sx += l * l;
        sy += r * r;
      }
      const rmsL = Math.sqrt(sx / W);
      const rmsR = Math.sqrt(sy / W);
      const dbL = 20 * Math.log10(rmsL + 1e-9);
      const dbR = 20 * Math.log10(rmsR + 1e-9);
      rmsDiffDb = Math.abs(dbL - dbR);
      const localRms = Math.sqrt((sx + sy) / (2 * W));
      step = Math.abs(pcm[A] - pcm[B - 1]) / (localRms + 1e-9);
    }
    const xc = crossCorrelation(A, B);
    const fl = computeFlux(A, B);
    const corrScore = xc ? xc.peak * Math.max(0, 1 - Math.abs(xc.lagMs) / 10) : 0;
    const loudScore = Math.exp(-rmsDiffDb / 3);
    const fluxScore = Math.exp(-fl.fluxJump);
    const stepScore = Math.exp(-step * 20);
    const quality = Math.max(0, Math.min(1,
      0.15 * corrScore + 0.25 * loudScore + 0.35 * fluxScore + 0.25 * stepScore));
    const res = {
      quality,
      xcorrLagMs: xc ? xc.lagMs : 0,
      xcorrPeak: xc ? xc.peak : 0,
      rmsDiffDb,
      seamFlux: fl.seamFlux,
      sideFlux: fl.sideFlux,
      fluxJump: fl.fluxJump,
      fluxRatio: fl.fluxRatio,
      fluxJumpDb: fl.fluxJumpDb,
      junctionStep: step,
      components: { corr: corrScore, loud: loudScore, flux: fluxScore, step: stepScore },
      windowSamples: W,
    };
    cache.set(key, res);
    return res;
  }

  return { evaluate, cache };
}

// Level-3 cheap seam scoring for algorithm E, on 8 kHz mono audio.
// Returns the three interpretable sub-scores plus their weighted total.
export function cheapSeamScore(pcm8k, sr8k, A, B) {
  const N = pcm8k.length;
  const W = Math.min(Math.round(0.05 * sr8k), A, B, N - A);
  if (W < 16) return { score: 0, zc: 0, energy: 0, flux: 0, step: 0, loudDiffDb: 0 };
  const winLen = Math.max(1, Math.round(0.005 * sr8k));
  const zcDistance = (idx) => {
    let best = Infinity;
    const lo = Math.max(0, idx - winLen);
    const hi = Math.min(N - 1, idx + winLen);
    for (let i = lo; i <= hi; i++) {
      if (i > 0 && (pcm8k[i - 1] <= 0) !== (pcm8k[i] <= 0)) {
        const d = Math.abs(i - idx);
        if (d < best) best = d;
      }
    }
    return best;
  };
  const zcA = zcDistance(A);
  const zcB = zcDistance(B - 1);
  const zcScore = Math.exp(-(Math.min(zcA, winLen) + Math.min(zcB, winLen)) / winLen);
  let sx = 0;
  let sy = 0;
  for (let i = 0; i < W; i++) {
    const l = pcm8k[B - W + i];
    const r = pcm8k[A + i];
    sx += l * l;
    sy += r * r;
  }
  const rmsL = Math.sqrt(sx / W);
  const rmsR = Math.sqrt(sy / W);
  const dbL = 20 * Math.log10(rmsL + 1e-9);
  const dbR = 20 * Math.log10(rmsR + 1e-9);
  const loudDiffDb = Math.abs(dbL - dbR);
  const energyScore = Math.exp(-loudDiffDb / 3);
  const localRms = Math.sqrt((sx + sy) / (2 * W));
  const step = Math.abs(pcm8k[A] - pcm8k[B - 1]) / (localRms + 1e-9);
  // Flux: energy-envelope jump at the junction vs the two sides (cheap, 8 kHz).
  const hop = Math.max(8, Math.round(0.01 * sr8k));
  const frame = Math.max(hop, Math.round(0.02 * sr8k));
  const seamEnergy = (windowStart) => {
    let s = 0;
    let c = 0;
    for (let i = windowStart; i < windowStart + frame; i++) {
      const j = i < B ? i : A + (i - B);
      if (j >= 0 && j < N) {
        s += pcm8k[j] * pcm8k[j];
        c++;
      }
    }
    return c ? Math.sqrt(s / c) : 0;
  };
  let sum = 0;
  let cnt = 0;
  for (let d = -4; d <= 4; d++) {
    if (d === 0) continue;
    sum += seamEnergy(B + d * hop);
    cnt++;
  }
  const side = cnt ? sum / cnt : 0;
  const atSeam = Math.max(seamEnergy(B - hop), seamEnergy(B));
  const flux = side > 1e-9 ? Math.max(0, atSeam / side - 1) : 0;
  const fluxScore = Math.exp(-flux * 4);
  // Content check at 8 kHz: the 200 ms after A' must actually equal the 200 ms
  // after B'. Without this, a stage-2 peak produced by a partially matching
  // window can place a locally smooth but musically wrong seam.
  const cw = Math.min(W, Math.round(0.2 * sr8k));
  let dot = 0;
  let ea = 0;
  let eb = 0;
  for (let i = 0; i < cw; i++) {
    const a = pcm8k[A + i];
    const b = pcm8k[B + i];
    dot += a * b;
    ea += a * a;
    eb += b * b;
  }
  const content = Math.max(0, Math.min(1, dot / (Math.sqrt(ea * eb) + 1e-12)));
  const score = 0.1 * zcScore + 0.2 * energyScore + 0.25 * fluxScore + 0.15 * stepScoreFn(step) + 0.3 * content;
  return { score, zc: zcScore, energy: energyScore, flux: fluxScore, step: stepScoreFn(step), content, contentNcc: content, loudDiffDb, stepRaw: step, fluxRaw: flux };
}

function stepScoreFn(step) {
  return Math.exp(-step * 20);
}
