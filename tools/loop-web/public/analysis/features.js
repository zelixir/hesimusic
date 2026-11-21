// Spectral feature extraction: port of loop_engine.cpp stftPass / estimateTuning /
// computeChroma / powerDbColMax / onsetEnvelope, with two deliberate memory
// reductions that do not change the candidate semantics:
//   1) the STFT is computed twice instead of storing T*1025 power/weighted
//      matrices (bit-identical inputs, same math);
//   2) the global power_to_db reference (median of sWeighed) is omitted because
//      every consumer compares two cmax values, so the constant cancels exactly
//      (see README "语义一致性与差异").
// Extra per-frame features (MFCC13, RMS, spectral flux) are produced for the
// experimental algorithms D/E; the baseline path ignores them.

import { fftPow2 } from './fft.js';
import { hannPeriodic, medianSorted } from './dsp.js';

export const NFFT = 2048;
export const HOP = 512;
export const BINS = NFFT / 2 + 1; // 1025
export const MELS = 128;
export const MEL_FMAX = 8000;
export const BEAT_SR = 22050; // librosa default rate used by PyMusicLooper's chroma/beat calls
const TINY32 = 1.1920928955078125e-07;
const TINY64 = 2.2250738585072014e-308;

function hzToMel(f) {
  const fSp = 200 / 3;
  const minLogMel = 1000 / fSp;
  const logstep = Math.log(6.4) / 27;
  if (f >= 1000) return minLogMel + Math.log(f / 1000) / logstep;
  return f / fSp;
}

function melToHz(m) {
  const fSp = 200 / 3;
  const minLogMel = 15;
  const logstep = Math.log(6.4) / 27;
  if (m >= minLogMel) return 1000 * Math.exp(logstep * (m - minLogMel));
  return fSp * m;
}

export function buildMelBasis(sr, nMels = MELS, fmax = MEL_FMAX) {
  const points = nMels + 2;
  const melF = new Float64Array(points);
  const m0 = hzToMel(0);
  const m1 = hzToMel(Math.min(fmax, sr / 2));
  for (let i = 0; i < points; i++) melF[i] = melToHz(m0 + ((m1 - m0) * i) / (points - 1));
  const lo = new Int32Array(nMels);
  const hi = new Int32Array(nMels);
  const w = new Float64Array(nMels * BINS);
  for (let i = 0; i < nMels; i++) {
    const fd0 = melF[i + 1] - melF[i];
    const fd1 = melF[i + 2] - melF[i + 1];
    const enorm = 2 / (melF[i + 2] - melF[i]);
    let l = BINS;
    let h = -1;
    for (let k = 0; k < BINS; k++) {
      const ff = (k * sr) / NFFT;
      const lower = (ff - melF[i]) / fd0;
      const upper = (melF[i + 2] - ff) / fd1;
      let wv = Math.max(0, Math.min(lower, upper)) * enorm;
      wv = Math.fround(wv);
      w[i * BINS + k] = wv;
      if (wv !== 0) {
        if (k < l) l = k;
        if (k > h) h = k;
      }
    }
    if (h < l) {
      l = 0;
      h = -1;
    }
    lo[i] = l;
    hi[i] = h;
  }
  return { mels: nMels, lo, hi, w };
}

export function aWeightingDb(sr) {
  const aw = new Float64Array(BINS);
  const c0 = 12194.217 * 12194.217;
  const c1 = 20.598997 * 20.598997;
  const c2 = 107.65265 * 107.65265;
  const c3 = 737.86223 * 737.86223;
  for (let k = 0; k < BINS; k++) {
    const f = (k * sr) / NFFT;
    const f2 = f * f;
    const w =
      2 + 20 * (Math.log10(c0) + 2 * Math.log10(f2) - Math.log10(f2 + c0) -
        Math.log10(f2 + c1) - 0.5 * Math.log10(f2 + c2) - 0.5 * Math.log10(f2 + c3));
    aw[k] = Math.max(-80, w);
  }
  return aw;
}

// librosa chroma filter bank (util.chroma) under the 22050 Hz default rate.
export function buildChromaBasis(tuning) {
  const nF = NFFT;
  const frqbins = new Float64Array(nF);
  const a440 = 440 * Math.pow(2, tuning / 12);
  const octDenom = a440 / 16;
  frqbins[0] = 0;
  for (let j = 1; j < nF; j++) frqbins[j] = 12 * Math.log2((j * BEAT_SR) / nF / octDenom);
  frqbins[0] = frqbins[1] - 18;
  const binwidth = new Float64Array(nF);
  for (let j = 0; j + 1 < nF; j++) binwidth[j] = Math.max(frqbins[j + 1] - frqbins[j], 1);
  binwidth[nF - 1] = 1;
  const wts = new Float64Array(12 * nF);
  for (let c = 0; c < 12; c++) {
    for (let j = 0; j < nF; j++) {
      let d = frqbins[j] - c;
      d = (d + 6 + 120) % 12;
      d -= 6;
      const x = (2 * d) / binwidth[j];
      wts[c * nF + j] = Math.exp(-0.5 * x * x);
    }
  }
  for (let j = 0; j < nF; j++) {
    let len = 0;
    for (let c = 0; c < 12; c++) {
      const v = wts[c * nF + j];
      len += v * v;
    }
    len = Math.sqrt(len);
    if (len >= TINY64) for (let c = 0; c < 12; c++) wts[c * nF + j] /= len;
  }
  for (let j = 0; j < nF; j++) {
    const g = Math.exp(-0.5 * Math.pow((frqbins[j] / 12 - 5) / 2, 2));
    for (let c = 0; c < 12; c++) wts[c * nF + j] *= g;
  }
  const basis = new Float64Array(12 * BINS);
  for (let c = 0; c < 12; c++) {
    for (let j = 0; j < BINS; j++) {
      basis[c * BINS + j] = Math.fround(wts[((c + 3) % 12) * nF + j]);
    }
  }
  return basis;
}

export function estimateTuning(candMag, candPitch, count) {
  if (count === 0) return 0;
  const mags = new Float64Array(count);
  for (let i = 0; i < count; i++) mags[i] = candMag[i];
  mags.sort();
  const threshold = medianSorted(mags, count);
  const counts = new Int32Array(100);
  for (let i = 0; i < count; i++) {
    if (!(candMag[i] >= threshold)) continue;
    const pitch = candPitch[i];
    if (pitch <= 0) continue;
    let residual = (12 * Math.log2(pitch / 27.5)) % 1;
    if (residual >= 0.5) residual -= 1;
    let bin = Math.floor((residual + 0.5) / 0.01);
    if (bin < 0) bin = 0;
    if (bin >= 100) bin = 99;
    counts[bin]++;
  }
  let best = 0;
  for (let i = 1; i < 100; i++) if (counts[i] > counts[best]) best = i;
  return -0.5 + 0.01 * best;
}

export function preprocess(pcm, sampleRate) {
  const n = pcm.length;
  let mn = 0;
  let mx = 0;
  let maxAbs = 0;
  for (let i = 0; i < n; i++) {
    const v = pcm[i];
    if (v < mn) mn = v;
    if (v > mx) mx = v;
    const a = v < 0 ? -v : v;
    if (a > maxAbs) maxAbs = a;
  }
  if (mn === 0 && mx === 0) return { ok: false, reason: '音频为纯静音' };
  if (!(maxAbs > 0)) return { ok: false, reason: '音频幅度为 0' };
  const inv = 1 / maxAbs;
  for (let i = 0; i < n; i++) pcm[i] = Math.fround(pcm[i] * inv);

  const nRms = Math.floor(n / HOP) + 1;
  // Prefix sums of squares -> exact zero-padded centered-window MSE in O(n).
  const prefix = new Float64Array(n + 1);
  for (let i = 0; i < n; i++) prefix[i + 1] = prefix[i] + pcm[i] * pcm[i];
  let maxMse = 0;
  const mse = new Float64Array(nRms);
  for (let t = 0; t < nRms; t++) {
    const begin = t * HOP - (NFFT >> 1);
    const end = begin + NFFT;
    const b = begin < 0 ? 0 : begin;
    const e = end > n ? n : end;
    const s = e > b ? prefix[e] - prefix[b] : 0;
    const m = s / NFFT;
    mse[t] = m;
    if (m > maxMse) maxMse = m;
  }
  const refDb = 20 * Math.log10(Math.max(1e-5, Math.sqrt(maxMse)));
  let first = -1;
  let last = -1;
  for (let t = 0; t < nRms; t++) {
    const db = 20 * Math.log10(Math.max(1e-5, Math.sqrt(mse[t]))) - refDb;
    if (db > -40) {
      if (first < 0) first = t;
      last = t;
    }
  }
  if (first < 0) return { ok: false, reason: '修剪后没有非静音内容' };
  const trimStart = first * HOP;
  const trimEnd = Math.min(n, (last + 1) * HOP);
  if (trimEnd <= trimStart) return { ok: false, reason: '修剪后音频为空' };
  return { ok: true, trimStart, trimEnd, mono: pcm, maxAbs };
}

// DCT-II basis (13 x mels), orthonormal, used for MFCC-like vectors.
function buildDct13(nMels) {
  const m = new Float64Array(13 * nMels);
  for (let c = 0; c < 13; c++) {
    const s = c === 0 ? Math.sqrt(1 / nMels) : Math.sqrt(2 / nMels);
    for (let j = 0; j < nMels; j++) {
      m[c * nMels + j] = s * Math.cos((Math.PI * c * (j + 0.5)) / nMels);
    }
  }
  return m;
}

export function computeFeatures(mono, trimStart, trimEnd, sampleRate, hooks = {}) {
  const onProgress = hooks.onProgress || (() => {});
  const now = () => (globalThis.performance ? globalThis.performance.now() : Date.now());
  const y = mono.subarray(trimStart, trimEnd);
  const M = y.length;
  const T = Math.floor(M / HOP) + 1;
  if (T < 8) return { ok: false, reason: '有效音频太短' };

  const hann = hannPeriodic(NFFT);
  const re = new Float64Array(NFFT);
  const im = new Float64Array(NFFT);
  const p32 = new Float64Array(BINS);
  const swd = new Float64Array(BINS);
  const sw32 = new Float64Array(BINS);
  const prevMag = new Float64Array(BINS);
  const curMag = new Float64Array(BINS);

  const kMinStart = 0;
  let kMin = kMinStart;
  while (kMin < BINS && ((kMin * BEAT_SR) / NFFT) < 150) kMin++;
  let kMax = BINS - 1;
  while (kMax >= 0 && ((kMax * BEAT_SR) / NFFT) >= 4000) kMax--;

  let passAMs = 0;
  let fftAMs = 0;
  let gmaxLog = -Infinity;
  const magCap = 8192;
  let mags = new Float32Array(magCap);
  let pitches = new Float64Array(magCap);
  let candCount = 0;

  const tA0 = now();
  for (let t = 0; t < T; t++) {
    const begin = t * HOP - (NFFT >> 1);
    for (let i = 0; i < NFFT; i++) {
      const pos = begin + i;
      re[i] = (pos >= 0 && pos < M ? y[pos] : 0) * hann[i];
      im[i] = 0;
    }
    const tf0 = now();
    fftPow2(re, im, NFFT, false);
    fftAMs += now() - tf0;

    let mx = 0;
    for (let k = 0; k < BINS; k++) {
      const p = re[k] * re[k] + im[k] * im[k];
      const f = Math.fround(p);
      p32[k] = f;
      const lg = 10 * Math.log10(Math.max(1e-10, p));
      if (lg > gmaxLog) gmaxLog = lg;
      if (f > mx) mx = f;
    }
    const refV = 0.1 * mx;
    for (let k = kMin; k <= kMax; k++) {
      const v = p32[k];
      if (!(v > refV)) continue;
      const xl = k >= 1 && p32[k - 1] > refV ? p32[k - 1] : 0;
      const xr = k + 1 < BINS && p32[k + 1] > refV ? p32[k + 1] : 0;
      const isMax = k >= 1 && v > xl && (k + 1 < BINS ? v >= xr : v > xl);
      if (!isMax) continue;
      let avg;
      let shift;
      if (k === 0) {
        avg = p32[1] - p32[0];
        shift = 0;
      } else if (k === BINS - 1) {
        avg = p32[k] - p32[k - 1];
        shift = 0;
      } else {
        avg = 0.5 * (p32[k + 1] - p32[k - 1]);
        const a = p32[k + 1] + p32[k - 1] - 2 * p32[k];
        const b = 0.5 * (p32[k + 1] - p32[k - 1]);
        shift = Math.abs(b) >= Math.abs(a) ? 0 : -b / a;
      }
      const mag = v + 0.5 * avg * shift;
      if (candCount >= mags.length) {
        const nm = new Float32Array(mags.length * 2);
        nm.set(mags);
        mags = nm;
        const np = new Float64Array(pitches.length * 2);
        np.set(pitches);
        pitches = np;
      }
      mags[candCount] = Math.fround(mag);
      pitches[candCount] = ((k + shift) * BEAT_SR) / NFFT;
      candCount++;
    }
    if ((t & 255) === 0) onProgress(0.05 + 0.2 * (t / T), 'stftA');
  }
  passAMs += now() - tA0;

  const tuning = estimateTuning(mags, pitches, candCount);

  // Pass B: chroma + mel (both onset and MFCC) + cmax + per-frame D features.
  const chromaBasis = buildChromaBasis(tuning);
  const melBasis = buildMelBasis(sampleRate);
  const aw = aWeightingDb(sampleRate);
  const dct = buildDct13(MELS);
  const chroma = new Float32Array(12 * T);
  const onset = new Float64Array(T);
  const cmaxRaw = new Float64Array(T);
  const mfcc = new Float32Array(T * 13);
  const frameRms = new Float32Array(T);
  const frameFlux = new Float32Array(T);

  const prefix = new Float64Array(M + 1);
  for (let i = 0; i < M; i++) prefix[i + 1] = prefix[i] + y[i] * y[i];
  const floorPower = gmaxLog - 80;
  let prevMel = new Float64Array(MELS);
  const curMel = new Float64Array(MELS);
  const melPow = new Float64Array(MELS);
  let gmaxSw = -Infinity;

  let passBMs = 0;
  let fftBMs = 0;
  const tB0 = now();
  for (let t = 0; t < T; t++) {
    const begin = t * HOP - (NFFT >> 1);
    for (let i = 0; i < NFFT; i++) {
      const pos = begin + i;
      re[i] = (pos >= 0 && pos < M ? y[pos] : 0) * hann[i];
      im[i] = 0;
    }
    const tf0 = now();
    fftPow2(re, im, NFFT, false);
    fftBMs += now() - tf0;

    for (let k = 0; k < BINS; k++) {
      const p = re[k] * re[k] + im[k] * im[k];
      const f = Math.fround(p);
      p32[k] = f;
      const db = 10 * Math.log10(Math.max(1e-10, f));
      const sd = aw[k] + Math.max(db, floorPower);
      swd[k] = sd;
      sw32[k] = Math.fround(sd);
    }

    // chroma (column t of 12 x T)
    for (let c = 0; c < 12; c++) {
      let s = 0;
      const off = c * BINS;
      for (let k = 0; k < BINS; k++) s += chromaBasis[off + k] * p32[k];
      chroma[c * T + t] = s;
    }
    let maxAbsChroma = 0;
    for (let c = 0; c < 12; c++) {
      const v = Math.abs(chroma[c * T + t]);
      if (v > maxAbsChroma) maxAbsChroma = v;
    }
    if (maxAbsChroma >= TINY32) {
      const inv = 1 / maxAbsChroma;
      for (let c = 0; c < 12; c++) {
        chroma[c * T + t] = Math.fround(chroma[c * T + t] * inv);
      }
    }

    // mel of sWeighed (onset) and mel of power (MFCC)
    for (let m = 0; m < MELS; m++) {
      const l = melBasis.lo[m];
      const h = melBasis.hi[m];
      if (h < l) {
        curMel[m] = 0;
        melPow[m] = 0;
        continue;
      }
      let ss = 0;
      let sp = 0;
      const off = m * BINS;
      for (let k = l; k <= h; k++) {
        const w = melBasis.w[off + k];
        ss += w * swd[k];
        sp += w * p32[k];
      }
      curMel[m] = ss;
      melPow[m] = sp;
    }
    // onset: env[j+3] = mean(max(0, mel[j+1]-mel[j]))
    if (t >= 1 && t <= T - 3) {
      let s = 0;
      for (let m = 0; m < MELS; m++) {
        const d = curMel[m] - prevMel[m];
        if (d > 0) s += d;
      }
      onset[t + 2] = s / MELS;
    }
    prevMel.set(curMel);

    // MFCC-like DCT over log power-mel
    for (let c = 0; c < 13; c++) {
      let s = 0;
      const off = c * MELS;
      for (let m = 0; m < MELS; m++) s += dct[off + m] * Math.log(Math.max(1e-10, melPow[m]));
      mfcc[t * 13 + c] = s;
    }

    // cmax (log of sWeighed, top_db floor applied later) + flux + rms
    let maxLogSw = -Infinity;
    for (let k = 0; k < BINS; k++) {
      const lg = 10 * Math.log10(Math.max(1e-10, sw32[k]));
      if (lg > maxLogSw) maxLogSw = lg;
      curMag[k] = Math.sqrt(p32[k]);
    }
    if (maxLogSw > gmaxSw) gmaxSw = maxLogSw;
    cmaxRaw[t] = maxLogSw;
    let flux = 0;
    for (let k = 0; k < BINS; k++) {
      const d = curMag[k] - prevMag[k];
      if (d > 0) flux += d * d;
      prevMag[k] = curMag[k];
    }
    frameFlux[t] = Math.sqrt(flux);
    const b = begin < 0 ? 0 : begin;
    const e = begin + NFFT > M ? M : begin + NFFT;
    frameRms[t] = Math.sqrt(Math.max(0, prefix[e] - prefix[b]) / NFFT);

    if ((t & 127) === 0) onProgress(0.28 + 0.34 * (t / T), 'chroma');
  }
  passBMs += now() - tB0;

  // Apply the global top_db floor to cmax. The constant power_to_db reference
  // cancels in every consumer (only cmax differences are used).
  const floorSw = gmaxSw - 80;
  const cmax = new Float64Array(T);
  for (let t = 0; t < T; t++) cmax[t] = Math.max(cmaxRaw[t], floorSw);

  return {
    ok: true,
    T,
    chroma,
    cmax,
    onset,
    tuning,
    mfcc,
    frameRms,
    frameFlux,
    timings: {
      stftAMs: passAMs,
      stftBMs: passBMs,
      fftMs: fftAMs + fftBMs,
      tuningMs: passAMs + passBMs - fftAMs - fftBMs,
    },
    gmaxLog,
    trimStart,
    trimEnd,
  };
}
