// Orchestrates all algorithm tiers and produces the UI/export payload.
// A is always computed (it is both a deliverable and the brute-force reference
// for D's recall measurement). B/C refine A; D and E are experimental tiers.

import { preprocess, computeFeatures, HOP } from './features.js';
import { computeBeatGrid } from './rhythm.js';
import {
  findPairs, prunePairs, buildScoringPlan, scorePairs,
  timeToFrames, frameToSample, nearestZeroCrossing, timeToSamplesTrunc,
} from './pairs.js';
import { createSeamEvaluator } from './seam.js';
import { prepareAlgorithmD } from './algd.js';
import { runAlgorithmE } from './alge.js';

export const TOOL_VERSION = '1.0.0';

export function defaultParams() {
  return {
    algorithms: { baseline: true, seam: true, subbeat: true, lsh: false, whole: false },
    minLoopSec: null,
    maxLoopSec: null,
    chromaThreshold: 0.0875,
    dbThreshold: 0.5,
    topN: 10,
    seamSort: true,
    minSeamQuality: 0,
    seamPool: 40,
    subbeatCandidates: 3,
    subbeatBudgetMs: 1200,
    beatStride: 1,
    d: { tables: 8, width: 0.3, int8: false, pca: false, refinePool: 60, subbeat: false, subbeatCandidates: 2 },
    e: {
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
    },
  };
}

function now() {
  return globalThis.performance ? globalThis.performance.now() : Date.now();
}

function mergeParams(raw) {
  const def = defaultParams();
  const p = { ...def, ...(raw || {}) };
  p.algorithms = { ...def.algorithms, ...((raw && raw.algorithms) || {}) };
  p.d = { ...def.d, ...((raw && raw.d) || {}) };
  p.e = { ...def.e, ...((raw && raw.e) || {}) };
  p.topN = Math.max(1, Math.min(50, p.topN | 0));
  p.seamPool = Math.max(p.topN, Math.min(300, p.seamPool | 0));
  p.subbeatCandidates = Math.max(1, Math.min(10, p.subbeatCandidates | 0));
  p.beatStride = Math.max(1, Math.min(16, p.beatStride | 0));
  return p;
}

function refineSubBeat(evaluator, sr, nSamples, A, B, deadline, opts = {}) {
  const rangeMs = opts.rangeMs || 30;
  const minLenSamples = Math.max(1, Math.round(0.1 * sr));
  const clampPos = (a, b) => {
    let aa = Math.max(0, Math.min(nSamples - 1, a));
    let bb = Math.max(0, Math.min(nSamples, b));
    if (bb - aa < minLenSamples) {
      bb = Math.min(nSamples, aa + minLenSamples);
      if (bb - aa < minLenSamples) aa = Math.max(0, bb - minLenSamples);
    }
    return [aa, bb];
  };
  let [bestA, bestB] = clampPos(A, B);
  let best = evaluator.evaluate(bestA, bestB).quality;
  let evals = 1;
  let budgetHit = false;
  const coarseStep = Math.max(1, Math.round((5 / 1000) * sr));
  const coarseRange = Math.round((rangeMs / 1000) * sr);
  const passes = [
    { step: coarseStep, range: coarseRange },
    { step: Math.max(1, Math.round((1 / 1000) * sr)), range: Math.round((5 / 1000) * sr) },
  ];
  for (const pass of passes) {
    for (let endpoint = 0; endpoint < 2; endpoint++) {
      const baseA = bestA;
      const baseB = bestB;
      for (let delta = -pass.range; delta <= pass.range; delta += pass.step) {
        if (now() > deadline) {
          budgetHit = true;
          break;
        }
        const a = endpoint === 0 ? baseA + delta : baseA;
        const b = endpoint === 1 ? baseB + delta : baseB;
        const [ca, cb] = clampPos(a, b);
        const q = evaluator.evaluate(ca, cb).quality;
        evals++;
        if (q > best + 1e-9) {
          best = q;
          bestA = ca;
          bestB = cb;
        }
      }
      if (budgetHit) break;
    }
    if (budgetHit) break;
  }
  return { A: bestA, B: bestB, quality: best, evals, budgetHit };
}

function candidateFromPair(item, algorithm, id, trimStart, pcm, sr, seam) {
  const A = nearestZeroCrossing(pcm, sr, frameToSample(item.p.startF, trimStart));
  const B = nearestZeroCrossing(pcm, sr, frameToSample(item.p.endF, trimStart));
  return {
    id,
    algorithm,
    rank: 0,
    aSample: A,
    bSample: B,
    aMs: (A / sr) * 1000,
    bMs: (B / sr) * 1000,
    lengthMs: ((B - A) / sr) * 1000,
    score: item.score,
    chromaDist: item.p.nd,
    loudnessDiff: item.p.ld,
    startF: item.p.startF,
    endF: item.p.endF,
    sourceKey: item.p.startF + ':' + item.p.endF,
    seam: seam || null,
  };
}

function rankCandidates(list) {
  list.forEach((c, i) => {
    c.rank = i;
  });
  return list;
}

export function analyze(pcm, sampleRate, rawParams, hooks = {}) {
  const params = mergeParams(rawParams);
  const onProgress = hooks.onProgress || (() => {});
  const isCancelled = hooks.isCancelled || (() => false);
  const totalStart = now();
  const n = pcm.length;
  if (!(pcm instanceof Float32Array) || n < 1024 || !(sampleRate > 0)) {
    return { ok: false, error: '无效的音频数据' };
  }
  const durationSec = n / sampleRate;

  onProgress(0.005, 'preprocess');
  const preStart = now();
  const pre = preprocess(pcm, sampleRate);
  if (!pre.ok) return { ok: false, error: pre.reason || '预处理失败' };
  const preMs = now() - preStart;
  if (isCancelled()) return { ok: false, error: '已取消' };

  onProgress(0.01, 'features');
  const featStart = now();
  const features = computeFeatures(pre.mono, pre.trimStart, pre.trimEnd, sampleRate, {
    onProgress: (f, stage) => onProgress(Math.max(0.01, Math.min(0.62, f)), stage),
  });
  if (!features.ok) return { ok: false, error: features.reason || '特征提取失败' };
  const featureMs = now() - featStart;
  if (isCancelled()) return { ok: false, error: '已取消' };

  onProgress(0.63, 'rhythm');
  const rhythmStart = now();
  const grid = computeBeatGrid(features.onset);
  const rhythmMs = now() - rhythmStart;
  if (isCancelled()) return { ok: false, error: '已取消' };

  const beatsRaw = grid.beats.length;
  let beats = Array.from(grid.beats);
  if (params.beatStride > 1) {
    beats = beats.filter((_, i) => i % params.beatStride === 0);
  }
  const beatsUsed = beats.length;

  const totalDuration = n / sampleRate;
  const minLoopFrames = params.minLoopSec != null
    ? Math.max(1, timeToFrames(params.minLoopSec, sampleRate))
    : Math.max(1, timeToFrames(0.35 * totalDuration, sampleRate));
  const maxLoopFrames = params.maxLoopSec != null
    ? Math.max(minLoopFrames + 1, timeToFrames(params.maxLoopSec, sampleRate))
    : timeToFrames(totalDuration, sampleRate);
  const minLoopSec = (minLoopFrames * HOP) / sampleRate;
  const maxLoopSec = (maxLoopFrames * HOP) / sampleRate;

  onProgress(0.72, 'pairs');
  const pairsStart = now();
  let found = { pairs: [], checks: 0 };
  let pruned = [];
  if (beatsUsed > 0) {
    found = findPairs(features.chroma, features.T, beats, features.cmax,
      minLoopFrames, maxLoopFrames, params.chromaThreshold, params.dbThreshold);
    pruned = prunePairs(found.pairs);
  }
  const pairMs = now() - pairsStart;
  if (isCancelled()) return { ok: false, error: '已取消' };

  const plan = buildScoringPlan(features.T, grid.bpm, sampleRate);
  onProgress(0.80, 'score');
  const scoreStart = now();
  const scored = plan && pruned.length ? scorePairs(pruned, features.chroma, features.T, plan) : [];
  const scoreMs = now() - scoreStart;
  if (isCancelled()) return { ok: false, error: '已取消' };

  const evaluator = createSeamEvaluator(pre.mono, sampleRate);
  const snapStart = now();
  const seamPool = Math.min(scored.length, params.seamPool);
  const pool = new Array(seamPool);
  for (let i = 0; i < seamPool; i++) {
    const item = scored[i];
    const c = candidateFromPair(item, 'seam', 'pool-' + i, pre.trimStart, pre.mono, sampleRate, null);
    c.seam = evaluator.evaluate(c.aSample, c.bSample);
    pool[i] = c;
  }
  const snapMs = now() - snapStart;

  const baselineList = pool.slice(0, Math.min(params.topN, pool.length)).map((c, i) => {
    const copy = { ...c, algorithm: 'baseline', id: 'baseline-' + i };
    return copy;
  });
  rankCandidates(baselineList);

  // ---- algorithm B: seam-quality ranking / filtering ----
  let seamList = [];
  let seamEvals = 0;
  const seamStart = now();
  if (params.algorithms.seam || params.algorithms.subbeat) {
    const combined = pool.map((c) => ({
      c,
      combined: 0.6 * (c.seam ? c.seam.quality : 0) + 0.4 * c.score,
    }));
    combined.sort((a, b) => {
      if (params.seamSort) return b.combined - a.combined;
      return b.c.score - a.c.score;
    });
    const filtered = combined.filter((x) => (x.c.seam ? x.c.seam.quality : 0) >= params.minSeamQuality);
    seamList = filtered.slice(0, Math.min(params.topN, filtered.length)).map((x, i) => ({
      ...x.c,
      algorithm: 'seam',
      id: 'seam-' + i,
      combined: x.combined,
    }));
    rankCandidates(seamList);
    seamEvals = pool.length;
  }
  const seamMs = now() - seamStart;

  // ---- algorithm C: sub-beat refinement of B's top candidates ----
  let subbeatList = [];
  let subbeatEvals = 0;
  let subbeatBudgetHit = false;
  const subStart = now();
  if (params.algorithms.subbeat) {
    const deadline = now() + params.subbeatBudgetMs;
    const input = (seamList.length ? seamList : baselineList).slice(0, params.subbeatCandidates);
    subbeatList = input.map((c, i) => {
      const r = refineSubBeat(evaluator, sampleRate, n, c.aSample, c.bSample, deadline);
      subbeatEvals += r.evals;
      subbeatBudgetHit = subbeatBudgetHit || r.budgetHit;
      const seam = evaluator.evaluate(r.A, r.B);
      return {
        ...c,
        algorithm: 'subbeat',
        id: 'subbeat-' + i,
        aSample: r.A,
        bSample: r.B,
        aMs: (r.A / sampleRate) * 1000,
        bMs: (r.B / sampleRate) * 1000,
        lengthMs: ((r.B - r.A) / sampleRate) * 1000,
        seam,
        refine: {
          dAMs: ((r.A - c.aSample) / sampleRate) * 1000,
          dBMs: ((r.B - c.bSample) / sampleRate) * 1000,
          evals: r.evals,
          budgetHit: r.budgetHit,
        },
        base: { aMs: c.aMs, bMs: c.bMs, score: c.score, chromaDist: c.chromaDist, loudnessDiff: c.loudnessDiff },
      };
    });
    subbeatList.sort((a, b) => b.seam.quality - a.seam.quality);
    rankCandidates(subbeatList);
  }
  const subMs = now() - subStart;
  if (isCancelled()) return { ok: false, error: '已取消' };

  // ---- algorithm D: beat descriptors + LSH, with brute-force recall ----
  let lshResult = null;
  let recall = null;
  let bruteOnly = [];
  let lshComparative = null;
  let lshError = null;
  const dStart = now();
  if (params.algorithms.lsh && beatsUsed === 0) {
    lshError = '没有检测到节拍，D 无法生成 beat 特征';
  }
  if (params.algorithms.lsh && beatsUsed > 0) {
    const d = prepareAlgorithmD(features, beats, sampleRate, minLoopFrames, maxLoopFrames, {
      tables: params.d.tables,
      width: params.d.width,
      int8: params.d.int8,
      pca: params.d.pca,
      seed: params.e.seed,
      maxBucket: 200,
    });
    const bruteAllKeys = new Set(found.pairs.map((p) => p.startF + ':' + p.endF));
    const bruteTopKeys = new Set(baselineList.map((c) => c.sourceKey));
    const lshAllKeys = new Set(d.candidates.map((c) => c.startF + ':' + c.endF));
    const sortedLsh = d.candidates.slice().sort((a, b) => (b.similarity - a.similarity) || (b.tables - a.tables));
    const refinePool = sortedLsh.slice(0, Math.min(params.d.refinePool, sortedLsh.length));
    const dSeamStart = now();
    const refined = refinePool.map((cand, i) => {
      const key = cand.startF + ':' + cand.endF;
      const A = nearestZeroCrossing(pre.mono, sampleRate, frameToSample(cand.startF, pre.trimStart));
      const B = nearestZeroCrossing(pre.mono, sampleRate, frameToSample(cand.endF, pre.trimStart));
      const seam = evaluator.evaluate(A, B);
      return {
        id: 'lsh-' + i,
        algorithm: 'lsh',
        rank: 0,
        aSample: A,
        bSample: B,
        aMs: (A / sampleRate) * 1000,
        bMs: (B / sampleRate) * 1000,
        lengthMs: ((B - A) / sampleRate) * 1000,
        score: null,
        chromaDist: null,
        loudnessDiff: null,
        similarity: cand.similarity,
        tablesHit: cand.tables,
        startF: cand.startF,
        endF: cand.endF,
        sourceKey: key,
        hit: bruteAllKeys.has(key) ? 'hit' : 'lsh-only',
        inBruteTop: bruteTopKeys.has(key),
        seam,
        combined: 0.6 * seam.quality + 0.4 * cand.similarity,
      };
    });
    refined.sort((a, b) => b.combined - a.combined);
    let dOut = refined.slice(0, Math.min(params.topN, refined.length));
    if (params.d.subbeat) {
      const deadline = now() + params.subbeatBudgetMs;
      dOut = dOut.slice(0, params.d.subbeatCandidates).map((c) => {
        const r = refineSubBeat(evaluator, sampleRate, n, c.aSample, c.bSample, deadline);
        const seam = evaluator.evaluate(r.A, r.B);
        return {
          ...c,
          aSample: r.A,
          bSample: r.B,
          aMs: (r.A / sampleRate) * 1000,
          bMs: (r.B / sampleRate) * 1000,
          lengthMs: ((r.B - r.A) / sampleRate) * 1000,
          seam,
          combined: 0.6 * seam.quality + 0.4 * c.similarity,
          refine: { dAMs: ((r.A - c.aSample) / sampleRate) * 1000, dBMs: ((r.B - c.bSample) / sampleRate) * 1000, evals: r.evals },
        };
      });
      dOut.sort((a, b) => b.combined - a.combined);
    }
    const dSeamMs = now() - dSeamStart;
    const lshTopKeys = new Set(dOut.map((c) => c.sourceKey));
    const preTopKeys = new Set(sortedLsh.slice(0, dOut.length).map((c) => c.startF + ':' + c.endF));
    let topMembership = 0;
    for (const k of bruteTopKeys) if (lshAllKeys.has(k)) topMembership++;
    let interAll = 0;
    for (const k of lshAllKeys) if (bruteAllKeys.has(k)) interAll++;
    let interTop = 0;
    for (const k of lshTopKeys) if (bruteTopKeys.has(k)) interTop++;
    let interPreTop = 0;
    for (const k of preTopKeys) if (bruteTopKeys.has(k)) interPreTop++;
    recall = {
      bruteAllCount: bruteAllKeys.size,
      lshAllCount: lshAllKeys.size,
      allIntersection: interAll,
      allRatio: bruteAllKeys.size ? interAll / bruteAllKeys.size : 0,
      bruteTopCount: bruteTopKeys.size,
      lshTopCount: lshTopKeys.size,
      topIntersection: interTop,
      topRatio: bruteTopKeys.size ? interTop / bruteTopKeys.size : 0,
      topNMembership: topMembership,
      topNMembershipRatio: bruteTopKeys.size ? topMembership / bruteTopKeys.size : 0,
      sets: {
        bruteTop: Array.from(bruteTopKeys),
        lshTop: Array.from(lshTopKeys),
        bruteAllCount: bruteAllKeys.size,
        lshAllCount: lshAllKeys.size,
      },
      preTopIntersection: interPreTop,
      preTopRatio: bruteTopKeys.size ? interPreTop / bruteTopKeys.size : 0,
      top1Same: !!(dOut[0] && baselineList[0] && dOut[0].sourceKey === baselineList[0].sourceKey),
      top1SamePreRefine: !!(sortedLsh[0] && baselineList[0] && (sortedLsh[0].startF + ':' + sortedLsh[0].endF) === baselineList[0].sourceKey),
      topN: dOut.length,
    };
    bruteOnly = baselineList.filter((c) => !lshTopKeys.has(c.sourceKey)).map((c) => ({
      ...c,
      algorithm: 'bruteOnly',
      id: 'bruteOnly-' + c.rank,
      hit: 'brute-only',
    }));
    lshComparative = {
      candidates: rankCandidates(dOut),
      telemetry: {
        totalMs: now() - dStart,
        featureMs: d.timings.featureMs,
        lshMs: d.timings.lshMs,
        seamMs: dSeamMs,
        frames: features.T,
        beats: beatsUsed,
        lshCandidates: d.candidates.length,
        refined: refined.length,
        pcaVariance: d.pcaVariance,
        stats: d.stats,
      },
    };
  }
  const dMs = now() - dStart;
  if (isCancelled()) return { ok: false, error: '已取消' };

  // ---- algorithm E: three-stage whole-window overlap matcher ----
  let eResult = null;
  const eStart = now();
  if (params.algorithms.whole) {
    const e = runAlgorithmE(pre.mono, sampleRate, minLoopSec, maxLoopSec, params.e, {
      onProgress: (f, stage) => onProgress(0.9 + 0.08 * f, stage),
    });
    if (e.ok) {
      const list = e.candidates.slice(0, Math.min(params.topN, e.candidates.length));
      const enriched = list.map((c, i) => {
        const seam = evaluator.evaluate(c.A, c.B);
        return {
          ...c,
          id: 'whole-' + i,
          algorithm: 'whole',
          rank: i,
          aSample: c.A,
          bSample: c.B,
          seam,
          combined: 0.5 * seam.quality + 0.5 * c.select.score,
        };
      });
      eResult = {
        candidates: enriched,
        telemetry: {
          ...e.timings,
          totalMs: now() - eStart,
          frames: features.T,
          beats: beatsUsed,
          anchors: e.stats.anchors,
          stats: e.stats,
          quality: e.compression,
          debug: e.debug,
          matches: e.matches,
        },
      };
    } else {
      eResult = { error: e.reason || '算法 E 未产生结果', candidates: [] };
    }
  }
  const eMs = now() - eStart;

  const meta = {
    toolVersion: TOOL_VERSION,
    sampleRate,
    durationSec,
    frames: n,
    trimmedFrames: pre.trimEnd - pre.trimStart,
    trimStartSec: pre.trimStart / sampleRate,
    T: features.T,
    tuning: features.tuning,
    bpm: grid.bpm,
    framesPerBeat: grid.bpm > 0 ? Math.round((sampleRate / HOP) * 60 / grid.bpm) : 0,
    beatsRaw,
    beatsUsed,
    beatStride: params.beatStride,
    minLoopSec,
    maxLoopSec,
    minLoopFrames,
    maxLoopFrames,
    params,
    preprocessMs: preMs,
    featureMs,
    rhythmMs,
    pairMs,
    scoreMs,
    snapMs,
    seamMs,
    subMs,
    dMs,
    eMs,
    totalMs: now() - totalStart,
    pairsChecked: found.checks,
    pairsKept: pruned.length,
    timings: features.timings,
    warnings: [],
  };
  if (beatsUsed === 0) {
    meta.warnings.push({ code: 'no-beats', message: '没有检测到节拍；A/B/C/D 无法生成候选，但 E 仍可运行。' });
  }
  if (durationSec > 600) {
    meta.warnings.push({
      code: 'long-file',
      message: '文件超过 10 分钟：配对阶段是 O(B²)，可能很慢。建议开启“降采样 beat（每 k 拍取一个）”或关闭部分算法。',
    });
  }
  if (params.beatStride > 1) {
    meta.warnings.push({
      code: 'beat-stride',
      message: '已降采样 beat：每 ' + params.beatStride + ' 拍取一个（' + beatsRaw + ' -> ' + beatsUsed + '）；这会减少可发现的候选取样点。',
    });
  }
  if (durationSec > 900) {
    meta.warnings.push({ code: 'memory', message: '超长文件会占用较多内存（解码 + 单声道 PCM）。' });
  }

  const featurePerFrameMs = features.T ? (features.timings.stftAMs + features.timings.stftBMs) / features.T : 0;
  const baselineTelemetry = {
    totalMs: preMs + featureMs + rhythmMs + pairMs + scoreMs + snapMs,
    featureMs,
    stftMs: features.timings.stftAMs + features.timings.stftBMs,
    rhythmMs,
    pairMs,
    scoreMs,
    snapMs,
    frames: features.T,
    beats: beatsUsed,
    beatsRaw,
    pairsChecked: found.checks,
    pairsKept: pruned.length,
    timePerFrameMs: featurePerFrameMs,
    pairsPerBeat: beatsUsed ? found.checks / beatsUsed : 0,
    bpm: grid.bpm,
    tuning: features.tuning,
  };

  return {
    ok: true,
    meta,
    algorithms: {
      baseline: { id: 'baseline', label: 'A baseline', color: '#4ea1ff', candidates: baselineList, telemetry: baselineTelemetry },
      seam: {
        id: 'seam',
        label: 'B +seamQuality',
        color: '#f5a524',
        candidates: seamList,
        telemetry: {
          totalMs: seamMs + snapMs,
          rankMs: seamMs,
          poolEvalMs: snapMs,
          seamEvals,
          candidatesInPool: pool.length,
          frames: features.T,
          beats: beatsUsed,
          msPerCandidate: pool.length ? (seamMs + snapMs) / pool.length : 0,
        },
      },
      subbeat: {
        id: 'subbeat',
        label: 'C +sub-beat',
        color: '#a78bfa',
        candidates: subbeatList,
        telemetry: {
          totalMs: subMs,
          evals: subbeatEvals,
          budgetHit: subbeatBudgetHit,
          frames: features.T,
          beats: beatsUsed,
        },
      },
      lsh: lshComparative
        ? { id: 'lsh', label: 'D LSH', color: '#34d399', candidates: lshComparative.candidates, telemetry: lshComparative.telemetry }
        : { id: 'lsh', label: 'D LSH', color: '#34d399', candidates: [], telemetry: null, skipped: true, error: lshError },
      whole: eResult && eResult.candidates
        ? { id: 'whole', label: 'E 整秒重合', color: '#fb7185', candidates: eResult.candidates, telemetry: eResult.telemetry, error: eResult.error }
        : { id: 'whole', label: 'E 整秒重合', color: '#fb7185', candidates: [], telemetry: null, skipped: true },
    },
    recall,
    comparison: { bruteOnly, bruteOnlyCount: bruteOnly.length },
    debug: {
      plan: plan ? { testOffset: plan.testOffset, wStart: plan.w[0] } : null,
      scoredCount: scored.length,
    },
  };
}
