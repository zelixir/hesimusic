// Algorithm D: beat-level 27-d short-window descriptors + random-projection LSH
// candidate generation. The brute-force reference is algorithm A's full pairing;
// pipeline.js computes recall and hit status.
//
// 27-d descriptor per beat (window +/-250 ms):
//   chroma mean (12) | MFCC mean (13) | RMS mean (1) | spectral-flux mean (1)
// Then z-score per dimension, L2 normalize, optional PCA->12, optional int8.

import { HOP } from './pairs.js';
import { mulberry32 } from './dsp.js';
import { jacobiEigen } from './linalg.js';

export function buildBeatDescriptors(features, beats, sampleRate) {
  const { T, chroma, mfcc, frameRms, frameFlux } = features;
  const B = beats.length;
  const dim = 27;
  const raw = new Float64Array(B * dim);
  const half = Math.max(1, Math.round((0.25 * sampleRate) / HOP));
  for (let bi = 0; bi < B; bi++) {
    const f = beats[bi];
    const lo = Math.max(0, f - half);
    const hi = Math.min(T - 1, f + half);
    const cnt = hi - lo + 1;
    for (let c = 0; c < 12; c++) {
      let s = 0;
      for (let t = lo; t <= hi; t++) s += chroma[c * T + t];
      raw[bi * dim + c] = s / cnt;
    }
    for (let c = 0; c < 13; c++) {
      let s = 0;
      for (let t = lo; t <= hi; t++) s += mfcc[t * 13 + c];
      raw[bi * dim + 12 + c] = s / cnt;
    }
    let r = 0;
    let fx = 0;
    for (let t = lo; t <= hi; t++) {
      r += frameRms[t];
      fx += frameFlux[t];
    }
    raw[bi * dim + 25] = r / cnt;
    raw[bi * dim + 26] = fx / cnt;
  }
  // z-score each dimension, then L2 normalize each beat vector.
  for (let d = 0; d < dim; d++) {
    let mean = 0;
    for (let i = 0; i < B; i++) mean += raw[i * dim + d];
    mean /= B;
    let varsum = 0;
    for (let i = 0; i < B; i++) {
      const q = raw[i * dim + d] - mean;
      varsum += q * q;
    }
    const sd = Math.sqrt(varsum / Math.max(1, B - 1));
    if (sd > 1e-12) {
      for (let i = 0; i < B; i++) raw[i * dim + d] = (raw[i * dim + d] - mean) / sd;
    } else {
      for (let i = 0; i < B; i++) raw[i * dim + d] = 0;
    }
  }
  for (let i = 0; i < B; i++) {
    let s = 0;
    for (let d = 0; d < dim; d++) s += raw[i * dim + d] * raw[i * dim + d];
    const n = Math.sqrt(s);
    if (n > 1e-12) for (let d = 0; d < dim; d++) raw[i * dim + d] /= n;
  }
  return { vectors: raw, dim, B };
}

export function pcaReduce(vectors, dim, B, k) {
  const mean = new Float64Array(dim);
  for (let i = 0; i < B; i++) for (let d = 0; d < dim; d++) mean[d] += vectors[i * dim + d];
  for (let d = 0; d < dim; d++) mean[d] /= Math.max(1, B);
  const cov = new Float64Array(dim * dim);
  for (let i = 0; i < B; i++) {
    for (let a = 0; a < dim; a++) {
      const va = vectors[i * dim + a] - mean[a];
      for (let b = a; b < dim; b++) cov[a * dim + b] += va * (vectors[i * dim + b] - mean[b]);
    }
  }
  for (let a = 0; a < dim; a++) for (let b = a; b < dim; b++) {
    const v = cov[a * dim + b] / Math.max(1, B - 1);
    cov[a * dim + b] = v;
    cov[b * dim + a] = v;
  }
  const eig = jacobiEigen(cov, dim);
  const order = Array.from({ length: dim }, (_, i) => i).sort((a, b) => eig.values[b] - eig.values[a]);
  const total = eig.values.reduce((s, x) => s + Math.max(0, x), 0);
  let kept = 0;
  for (let i = 0; i < Math.min(k, dim); i++) kept += Math.max(0, eig.values[order[i]]);
  const out = new Float64Array(B * k);
  for (let j = 0; j < k; j++) {
    const col = order[j];
    for (let i = 0; i < B; i++) {
      let s = 0;
      for (let d = 0; d < dim; d++) s += (vectors[i * dim + d] - mean[d]) * eig.vectors[d * dim + col];
      out[i * k + j] = s;
    }
  }
  for (let i = 0; i < B; i++) {
    let s = 0;
    for (let d = 0; d < k; d++) s += out[i * k + d] * out[i * k + d];
    const n = Math.sqrt(s);
    if (n > 1e-12) for (let d = 0; d < k; d++) out[i * k + d] /= n;
  }
  return { vectors: out, dim: k, varianceRetention: total > 0 ? kept / total : 1 };
}

function quantizeInt8(vectors, dim, B) {
  const q = new Int8Array(B * dim);
  for (let i = 0; i < B * dim; i++) q[i] = Math.max(-127, Math.min(127, Math.round(vectors[i] * 127)));
  return q;
}

export function runLsh(vectors, dim, B, beats, minLoopFrames, maxLoopFrames, opts, int8) {
  const t0 = globalThis.performance ? performance.now() : Date.now();
  const tables = Math.max(1, opts.tables | 0);
  const width = Math.max(0.01, opts.width);
  const seed = opts.seed || 1234567;
  const rng = mulberry32(seed);
  const maxBucket = opts.maxBucket || 200;
  const q = int8 ? quantizeInt8(vectors, dim, B) : null;
  const maps = [];
  const hashVecs = [];
  for (let t = 0; t < tables; t++) {
    const a = new Float64Array(dim);
    let norm = 0;
    for (let d = 0; d < dim; d++) {
      a[d] = rng.normal();
      norm += a[d] * a[d];
    }
    norm = Math.sqrt(norm) || 1;
    for (let d = 0; d < dim; d++) a[d] /= norm;
    const b = rng.next() * width;
    hashVecs.push({ a, b });
    maps.push(new Map());
  }
  let bucketPairs = 0;
  let skippedBuckets = 0;
  for (let t = 0; t < tables; t++) {
    const { a, b } = hashVecs[t];
    const map = maps[t];
    for (let i = 0; i < B; i++) {
      let dot = 0;
      for (let d = 0; d < dim; d++) {
        dot += (q ? q[i * dim + d] / 127 : vectors[i * dim + d]) * a[d];
      }
      const key = Math.floor((dot + b) / width);
      let arr = map.get(key);
      if (!arr) {
        arr = [];
        map.set(key, arr);
      }
      arr.push(i);
    }
    for (const arr of map.values()) {
      if (arr.length < 2) continue;
      if (arr.length > maxBucket) {
        skippedBuckets++;
        continue;
      }
      bucketPairs += (arr.length * (arr.length - 1)) / 2;
    }
  }
  const seen = new Map();
  for (let t = 0; t < tables; t++) {
    for (const arr of maps[t].values()) {
      if (arr.length < 2 || arr.length > maxBucket) continue;
      for (let i = 0; i < arr.length; i++) {
        for (let j = i + 1; j < arr.length; j++) {
          const bi = arr[i];
          const bj = arr[j];
          if (bj <= bi) continue;
          const startF = beats[bi];
          const endF = beats[bj];
          const len = endF - startF;
          if (len < minLoopFrames || len > maxLoopFrames) continue;
          const key = bi * B + bj;
          let rec = seen.get(key);
          if (!rec) {
            rec = { startF, endF, bi, bj, tables: 0, similarity: 0 };
            seen.set(key, rec);
          }
          rec.tables++;
        }
      }
    }
  }
  const candidates = Array.from(seen.values());
  for (const c of candidates) {
    let dot = 0;
    const bi = c.bi;
    const bj = c.bj;
    if (q) {
      let na = 0;
      let nb = 0;
      for (let d = 0; d < dim; d++) {
        dot += q[bi * dim + d] * q[bj * dim + d];
        na += q[bi * dim + d] * q[bi * dim + d];
        nb += q[bj * dim + d] * q[bj * dim + d];
      }
      c.similarity = dot / (Math.sqrt(na * nb) + 1e-12);
    } else {
      for (let d = 0; d < dim; d++) dot += vectors[bi * dim + d] * vectors[bj * dim + d];
      c.similarity = dot;
    }
  }
  const t1 = globalThis.performance ? performance.now() : Date.now();
  return {
    candidates,
    ms: t1 - t0,
    stats: {
      tables,
      width,
      dim,
      int8: !!int8,
      beats: B,
      buckets: maps.reduce((s, m) => s + m.size, 0),
      bucketPairs,
      skippedBuckets,
      candidates: candidates.length,
    },
  };
}

export function prepareAlgorithmD(features, beats, sampleRate, minLoopFrames, maxLoopFrames, opts) {
  const t0 = globalThis.performance ? performance.now() : Date.now();
  const desc = buildBeatDescriptors(features, beats, sampleRate);
  let vectors = desc.vectors;
  let dim = desc.dim;
  let pca = null;
  if (opts.pca) {
    pca = pcaReduce(vectors, dim, desc.B, Math.min(12, dim));
    vectors = pca.vectors;
    dim = pca.dim;
  }
  const t1 = globalThis.performance ? performance.now() : Date.now();
  const lsh = runLsh(vectors, dim, desc.B, beats, minLoopFrames, maxLoopFrames, opts, opts.int8);
  const t2 = globalThis.performance ? performance.now() : Date.now();
  return {
    beats: desc.B,
    vectors,
    dim,
    pcaVariance: pca ? pca.varianceRetention : null,
    candidates: lsh.candidates,
    timings: { featureMs: t1 - t0, lshMs: lsh.ms, totalMs: t2 - t0 },
    stats: lsh.stats,
  };
}