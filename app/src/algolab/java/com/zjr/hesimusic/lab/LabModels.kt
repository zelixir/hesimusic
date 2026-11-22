package com.zjr.hesimusic.lab

import kotlin.math.sqrt

/**
 * 实验参数。所有值都来自 UI,并在 [sanitized] 里夹到安全范围;
 * native 侧还会再做一次防御性归一化(见 loop_engine.cpp normalizeSeamWeights)。
 */
data class LabParams(
    /** true = 接缝融合(双算法输出);false = 仅初版路径(性能基线/复现初版)。 */
    val seamEnabled: Boolean = true,
    /** 亚拍精修(±refineRangeMs 局部搜索)。 */
    val refineEnabled: Boolean = true,
    /** 融合权重 w:score = (1-w)*legacyScore + w*seamQuality,默认 0.50。 */
    val seamWeight: Double = 0.50,
    /** 接缝质量的三项权重(UI 显示原始值,引擎按和归一化)。 */
    val corrWeight: Double = 0.50,
    val rmsWeight: Double = 0.30,
    val fluxWeight: Double = 0.20,
    /** 精修搜索半径(ms),默认 30。 */
    val refineRangeMs: Int = 30,
    /** 候选数量 N,默认 10。 */
    val maxCandidates: Int = 10,
) {
    fun sanitized(): LabParams = copy(
        seamWeight = sanitize01(seamWeight, fallback = 0.50),
        corrWeight = sanitizeNonNegative(corrWeight),
        rmsWeight = sanitizeNonNegative(rmsWeight),
        fluxWeight = sanitizeNonNegative(fluxWeight),
        refineRangeMs = refineRangeMs.coerceIn(0, 100),
        maxCandidates = maxCandidates.coerceIn(3, 40),
    )

    /** 归一化后的 [corr, rms, flux];全 0/NaN 时回退到 0.50/0.30/0.20。 */
    fun normalizedWeights(): DoubleArray {
        var c = if (corrWeight.isFinite()) corrWeight.coerceAtLeast(0.0) else 0.0
        var r = if (rmsWeight.isFinite()) rmsWeight.coerceAtLeast(0.0) else 0.0
        var f = if (fluxWeight.isFinite()) fluxWeight.coerceAtLeast(0.0) else 0.0
        var sum = c + r + f
        if (!(sum > 0.0) || !sum.isFinite()) {
            c = 0.50
            r = 0.30
            f = 0.20
            sum = 1.0
        }
        return doubleArrayOf(c / sum, r / sum, f / sum)
    }

    private fun sanitize01(value: Double, fallback: Double): Double =
        if (value.isFinite()) value.coerceIn(0.0, 1.0) else fallback

    private fun sanitizeNonNegative(value: Double): Double =
        if (value.isFinite()) value.coerceIn(0.0, 1.0) else 0.0
}

/** 一个被评估的候选;同一行同时带两套算法的分数/排名与接缝分项。 */
data class LabCandidate(
    val loopStartSamples: Long,
    val loopEndSamples: Long,
    /** 初版路径会输出的边界(拍点 + 过零,未做亚拍精修)。 */
    val anchorStartSamples: Long,
    val anchorEndSamples: Long,
    val legacyScore: Double,
    val fusedScore: Double,
    /** 初版排名,0-based;在**全部已评分拍对**上的全局排名。 */
    val legacyRank: Int,
    /** 融合排名,0-based;在接缝候选池内的排名。 */
    val fusedRank: Int,
    val seamQuality: Double,
    val correlation: Double,
    val rmsJumpDb: Double,
    val fluxRatio: Double,
    val corrScore: Double,
    val energyScore: Double,
    val timbreScore: Double,
    val noteDistance: Double,
    val loudnessDifference: Double,
    val seamEvaluated: Boolean,
) {
    fun startMs(sampleRate: Int): Double = samplesToMs(loopStartSamples, sampleRate)
    fun endMs(sampleRate: Int): Double = samplesToMs(loopEndSamples, sampleRate)
    fun anchorStartMs(sampleRate: Int): Double = samplesToMs(anchorStartSamples, sampleRate)
    fun anchorEndMs(sampleRate: Int): Double = samplesToMs(anchorEndSamples, sampleRate)

    /** 用给定三项权重重新合成的接缝质量;未测量时返回 NaN。 */
    fun seamSubScore(weights: DoubleArray): Double =
        if (!seamEvaluated) Double.NaN
        else weights[0] * corrScore + weights[1] * energyScore + weights[2] * timbreScore

    /** 任意融合权重 w 下的融合分;未测量接缝时退化为初版分。 */
    fun fusedScoreFor(seamWeight: Double, weights: DoubleArray): Double {
        if (!seamEvaluated) return legacyScore
        val w = if (seamWeight.isFinite()) seamWeight.coerceIn(0.0, 1.0) else 0.0
        return (1.0 - w) * legacyScore + w * seamSubScore(weights)
    }

    private fun samplesToMs(samples: Long, sampleRate: Int): Double =
        if (sampleRate > 0) samples * 1000.0 / sampleRate else 0.0
}

/** native header 的 Kotlin 映射(见 lab_flat.h 顶部注释)。 */
data class LabRawResult(
    val version: Int,
    /** 接缝阶段墙钟耗时(ms);seamEnabled=false 时为 0。 */
    val seamStageMs: Double,
    /** 全部已评分拍对数(初版排名的定义域)。 */
    val scoredPairs: Int,
    /** 接缝阶段实际评估的候选池大小。 */
    val evaluatedPool: Int,
    val seamEnabled: Boolean,
    val seamEvaluatedCount: Int,
    val candidates: List<LabCandidate>,
)

/** 单次分析的双算法原始结果解析(唯一解析点,便于 JVM 单测/复用)。 */
object LabResultParser {
    const val HEADER_FIELDS = 6
    const val FIELDS_PER_CANDIDATE = 18
    const val SUPPORTED_VERSION = 1

    /** @return null = 数组为空/版本不支持/长度不合法;不猜测截断语义。 */
    fun parse(flat: DoubleArray?): LabRawResult? {
        if (flat == null || flat.size < HEADER_FIELDS) return null
        if (!flat[0].isFinite() || flat[0].toInt() != SUPPORTED_VERSION) return null
        val body = flat.size - HEADER_FIELDS
        if (body < 0 || body % FIELDS_PER_CANDIDATE != 0) return null
        val count = body / FIELDS_PER_CANDIDATE
        val candidates = ArrayList<LabCandidate>(count)
        var i = HEADER_FIELDS
        repeat(count) {
            val legacyRank = flat[i + 4].toInt()
            val fusedRank = flat[i + 5].toInt()
            candidates += LabCandidate(
                loopStartSamples = flat[i].toLong(),
                loopEndSamples = flat[i + 1].toLong(),
                legacyScore = sanitize(flat[i + 2]),
                fusedScore = sanitize(flat[i + 3]),
                legacyRank = legacyRank,
                fusedRank = fusedRank,
                seamQuality = sanitize(flat[i + 6]),
                correlation = sanitize(flat[i + 7]),
                rmsJumpDb = sanitize(flat[i + 8]),
                fluxRatio = sanitize(flat[i + 9]),
                corrScore = sanitize(flat[i + 10]),
                energyScore = sanitize(flat[i + 11]),
                timbreScore = sanitize(flat[i + 12]),
                noteDistance = sanitize(flat[i + 13]),
                loudnessDifference = sanitize(flat[i + 14]),
                seamEvaluated = flat[i + 15] >= 0.5,
                anchorStartSamples = flat[i + 16].toLong(),
                anchorEndSamples = flat[i + 17].toLong(),
            )
            i += FIELDS_PER_CANDIDATE
        }
        return LabRawResult(
            version = flat[0].toInt(),
            seamStageMs = sanitize(flat[1]),
            scoredPairs = flat[2].toInt(),
            evaluatedPool = flat[3].toInt(),
            seamEnabled = flat[4] >= 0.5,
            seamEvaluatedCount = flat[5].toInt(),
            candidates = candidates,
        )
    }

    private fun sanitize(value: Double): Double = if (value.isFinite()) value else 0.0
}

// ---------------------------------------------------------------------------
// 一次实验的完整快照(用于 UI 汇总与导出)
// ---------------------------------------------------------------------------

data class LabSongInfo(
    val title: String,
    val artist: String,
    val album: String,
    val path: String,
    val durationMs: Long,
    val sampleRate: Int,
    val frames: Long,
)

data class LabTiming(
    val decodeMs: Long,
    /** JNI 分析总耗时(含接缝阶段)。 */
    val analyzeMs: Long,
    /** 接缝阶段墙钟耗时(ms);未开启接缝时为 0。 */
    val seamStageMs: Double,
    /** 接缝阶段均摊到每个候选的耗时(ms);无测量时为 NaN。 */
    val perCandidateMs: Double,
    val totalMs: Long,
) {
    /**
     * 用"接缝阶段耗时 / 其余分析耗时"估算相对初版的耗时增幅。
     * 初版不跑接缝阶段,所以分母 ≈ 初版分析耗时;这是单次分析内可得的估计值。
     * 精确 A/B 可把「接缝融合」关掉再跑一次(见 docs/algo-lab.md)。
     */
    val estimatedOverheadPercent: Double
        get() {
            val legacyMs = analyzeMs - seamStageMs
            if (!seamStageMs.isFinite() || seamStageMs <= 0.0 || legacyMs <= 0.0) return 0.0
            return seamStageMs / legacyMs * 100.0
        }
}

data class LabMemory(
    val peakJavaBytes: Long,
    val peakNativeBytes: Long,
    val peakPssKb: Int,
    val sampled: Boolean,
)

data class LabResult(
    val generatedAt: Long,
    val song: LabSongInfo,
    val params: LabParams,
    val normalizedWeights: DoubleArray,
    val raw: LabRawResult,
    val timing: LabTiming,
    val memory: LabMemory,
    val summary: LabSummary,
    val sweep: List<SweepPoint>,
    val accuracy: ReferenceAccuracy?,
)

// ---------------------------------------------------------------------------
// 对比/扫描/准确度(纯逻辑,便于验证与导出)
// ---------------------------------------------------------------------------

data class RankedCandidate(
    val candidate: LabCandidate,
    /** 0-based 本地排名(在该排序下)。 */
    val rank: Int,
    val score: Double,
)

data class SweepPoint(
    val seamWeight: Double,
    val overlapAt5: Double,
    val overlapAt10: Double,
    val overlapAtN: Double,
    val top1Same: Boolean,
    val avgSeamQualityTopN: Double,
    val measured: Boolean,
)

data class LabSummary(
    val topN: Int,
    val overlapAt5: Int,
    val overlapAt10: Int,
    val overlapAtN: Int,
    val legacyTop: List<LabCandidate>,
    val fusedTop: List<LabCandidate>,
    val newInFused: List<LabCandidate>,
    val missingFromFused: List<LabCandidate>,
    /** 前 N 并集上的平均 Δ分 = fused - legacy。 */
    val avgDeltaScoreTopN: Double,
    /** 整个候选池上的平均 |Δ分|。 */
    val avgAbsDeltaScorePool: Double,
    /** 前 N 并集上的最大 |fusedRank - legacyRank|。 */
    val maxRankShift: Int,
    val maxRankShiftCandidate: LabCandidate?,
    val top1Changed: Boolean,
    val legacyTop1Seam: Double,
    val fusedTop1Seam: Double,
    val avgSeamQualityLegacyTop: Double,
    val avgSeamQualityFusedTop: Double,
    /** 前 N 并集里最小的 (fused - legacy);负得越多表示和声相似度让步越大。 */
    val worstLegacyScoreDropTopN: Double,
    val seamMeasured: Boolean,
) {
    val overlapAt5Fraction: Double get() = overlapAt5 / 5.0
    val overlapAt10Fraction: Double get() = overlapAt10 / 10.0
    val overlapAtNFraction: Double get() = if (topN > 0) overlapAtN.toDouble() / topN else 0.0
}

data class AlgoAccuracy(
    /** 池内离参考 A/B 最近的候选(按两端偏差的欧氏距离)。 */
    val candidate: LabCandidate?,
    val startDeviationMs: Double?,
    val endDeviationMs: Double?,
    val distanceMs: Double?,
    val within50Ms: Int,
    val within100Ms: Int,
)

data class ReferenceAccuracy(
    val referenceStartMs: Double,
    val referenceEndMs: Double,
    /** 初版排名坐标(anchor)对参考的偏差。 */
    val legacy: AlgoAccuracy,
    /** 融合排名坐标(loop,含精修)对参考的偏差。 */
    val fused: AlgoAccuracy,
)

object LabComparison {

    fun legacyRanked(candidates: List<LabCandidate>): List<RankedCandidate> =
        candidates
            .sortedWith(compareByDescending<LabCandidate> { it.legacyScore }.thenBy { it.legacyRank })
            .mapIndexed { index, candidate -> RankedCandidate(candidate, index, candidate.legacyScore) }

    fun fusedRanked(
        candidates: List<LabCandidate>,
        seamWeight: Double,
        weights: DoubleArray,
    ): List<RankedCandidate> =
        candidates
            .sortedWith(
                compareByDescending<LabCandidate> { it.fusedScoreFor(seamWeight, weights) }
                    .thenBy { it.legacyRank }
            )
            .mapIndexed { index, candidate ->
                RankedCandidate(candidate, index, candidate.fusedScoreFor(seamWeight, weights))
            }

    fun summarize(raw: LabRawResult, topN: Int): LabSummary {
        val n = topN.coerceIn(1, maxOf(1, raw.candidates.size))
        val legacyOrdered = raw.candidates.sortedBy { it.legacyRank }
        val fusedOrdered = raw.candidates.sortedBy { it.fusedRank }
        val legacyTop = legacyOrdered.take(n)
        // 汇总用**引擎**给出的 fusedRank(与表格/导出一致);sweep 才用本地重算。
        val fusedTop = fusedOrdered.take(n)
        // 前 5 / 前 10 独立于 N 计算,避免 N<5 或 N<10 时把前 N 冒充前 5/前 10。
        val legacyTop5 = legacyOrdered.take(5)
        val fusedTop5 = fusedOrdered.take(5)
        val legacyTop10 = legacyOrdered.take(10)
        val fusedTop10 = fusedOrdered.take(10)
        val legacySet = legacyTop.map { it.legacyRank }.toHashSet()
        val fusedSet = fusedTop.map { it.legacyRank }.toHashSet()
        val union = (legacyTop + fusedTop).distinctBy { it.legacyRank }
        val newInFused = fusedTop.filter { it.legacyRank !in legacySet }
        val missingFromFused = legacyTop.filter { it.legacyRank !in fusedSet }

        val measured = raw.seamEnabled && raw.candidates.isNotEmpty() &&
            raw.candidates.all { it.seamEvaluated }
        val seam = { c: LabCandidate? -> if (c != null && c.seamEvaluated) c.seamQuality else Double.NaN }
        val legacyTop1 = legacyTop.firstOrNull()
        val fusedTop1 = fusedTop.firstOrNull()

        return LabSummary(
            topN = n,
            overlapAt5 = overlapCount(legacyTop5, fusedTop5, 5),
            overlapAt10 = overlapCount(legacyTop10, fusedTop10, 10),
            overlapAtN = overlapCount(legacyTop, fusedTop, n),
            legacyTop = legacyTop,
            fusedTop = fusedTop,
            newInFused = newInFused,
            missingFromFused = missingFromFused,
            avgDeltaScoreTopN = meanOrNaN(union.map { it.fusedScore - it.legacyScore }),
            avgAbsDeltaScorePool = meanOrNaN(raw.candidates.map { kotlin.math.abs(it.fusedScore - it.legacyScore) }),
            maxRankShift = union.maxOfOrNull { kotlin.math.abs(it.fusedRank - it.legacyRank) } ?: 0,
            maxRankShiftCandidate = union.maxByOrNull { kotlin.math.abs(it.fusedRank - it.legacyRank) },
            top1Changed = legacyTop1 != null && fusedTop1 != null &&
                legacyTop1.legacyRank != fusedTop1.legacyRank,
            legacyTop1Seam = seam(legacyTop1),
            fusedTop1Seam = seam(fusedTop1),
            avgSeamQualityLegacyTop = meanOrNaN(
                legacyTop.mapNotNull { if (it.seamEvaluated) it.seamQuality else null }
            ),
            avgSeamQualityFusedTop = meanOrNaN(
                fusedTop.mapNotNull { if (it.seamEvaluated) it.seamQuality else null }
            ),
            worstLegacyScoreDropTopN = union.minOfOrNull { it.fusedScore - it.legacyScore } ?: Double.NaN,
            seamMeasured = measured,
        )
    }

    /** seamWeight 0.0 → 1.0 的 9 个点;一致性 = 前 N 名重合数 / N。 */
    fun sweep(raw: LabRawResult, params: LabParams, topN: Int): List<SweepPoint> {
        if (!raw.seamEnabled || raw.candidates.isEmpty() ||
            raw.candidates.any { !it.seamEvaluated }
        ) {
            return emptyList()
        }
        val n = topN.coerceIn(1, maxOf(1, raw.candidates.size))
        val weights = params.normalizedWeights()
        val byLegacy = raw.candidates.sortedBy { it.legacyRank }
        // 每条曲线各自对照「初版的前 k 名」:N<5 或 N<10 时不能拿前 N 冒充前 5/前 10。
        val legacyTop5 = byLegacy.take(5).map { it.legacyRank }.toHashSet()
        val legacyTop10 = byLegacy.take(10).map { it.legacyRank }.toHashSet()
        val legacyTopN = byLegacy.take(n).map { it.legacyRank }.toHashSet()
        return (0..8).map { step ->
            val w = step / 8.0
            val ranked = byLegacy.sortedWith(
                compareByDescending<LabCandidate> { it.fusedScoreFor(w, weights) }
                    .thenBy { it.legacyRank }
            )
            val top = ranked.take(n)
            SweepPoint(
                seamWeight = w,
                overlapAt5 = overlapFraction(ranked, legacyTop5, 5),
                overlapAt10 = overlapFraction(ranked, legacyTop10, 10),
                overlapAtN = overlapFraction(ranked, legacyTopN, n),
                top1Same = ranked.firstOrNull()?.legacyRank == byLegacy.firstOrNull()?.legacyRank,
                avgSeamQualityTopN = meanOrNaN(top.map { it.seamQuality }),
                measured = true,
            )
        }
    }

    /** 参考标注(ms)存在时,分别给初版/融合算"最近候选"的两端偏差。 */
    fun accuracy(
        raw: LabRawResult,
        referenceStartMs: Double,
        referenceEndMs: Double,
        sampleRate: Int,
    ): ReferenceAccuracy? {
        if (!referenceStartMs.isFinite() || !referenceEndMs.isFinite()) return null
        if (raw.candidates.isEmpty() || sampleRate <= 0) return null
        return ReferenceAccuracy(
            referenceStartMs = referenceStartMs,
            referenceEndMs = referenceEndMs,
            legacy = accuracyFor(raw.candidates, referenceStartMs, referenceEndMs, sampleRate, useAnchor = true),
            fused = accuracyFor(raw.candidates, referenceStartMs, referenceEndMs, sampleRate, useAnchor = false),
        )
    }

    private data class AccuracyRow(
        val candidate: LabCandidate,
        val startDeviationMs: Double,
        val endDeviationMs: Double,
        val distanceMs: Double,
    )

    private fun accuracyFor(
        candidates: List<LabCandidate>,
        referenceStartMs: Double,
        referenceEndMs: Double,
        sampleRate: Int,
        useAnchor: Boolean,
    ): AlgoAccuracy {
        val rows = candidates.map { c ->
            val a = if (useAnchor) c.anchorStartMs(sampleRate) else c.startMs(sampleRate)
            val b = if (useAnchor) c.anchorEndMs(sampleRate) else c.endMs(sampleRate)
            val da = a - referenceStartMs
            val db = b - referenceEndMs
            AccuracyRow(c, da, db, sqrt(da * da + db * db))
        }
        val best = rows.minByOrNull { it.distanceMs }
        return AlgoAccuracy(
            candidate = best?.candidate,
            startDeviationMs = best?.startDeviationMs,
            endDeviationMs = best?.endDeviationMs,
            distanceMs = best?.distanceMs,
            within50Ms = rows.count {
                kotlin.math.abs(it.startDeviationMs) <= 50.0 &&
                    kotlin.math.abs(it.endDeviationMs) <= 50.0
            },
            within100Ms = rows.count {
                kotlin.math.abs(it.startDeviationMs) <= 100.0 &&
                    kotlin.math.abs(it.endDeviationMs) <= 100.0
            },
        )
    }

    private fun overlapCount(a: List<LabCandidate>, b: List<LabCandidate>, n: Int): Int {
        val k = n.coerceAtLeast(0)
        val set = a.take(k).map { it.legacyRank }.toHashSet()
        return b.take(k).count { it.legacyRank in set }
    }

    private fun overlapFraction(
        ranked: List<LabCandidate>,
        legacyTop: Set<Int>,
        n: Int,
    ): Double {
        if (n <= 0) return Double.NaN
        val take = minOf(n, ranked.size)
        if (take == 0) return Double.NaN
        return ranked.take(take).count { it.legacyRank in legacyTop }.toDouble() / take
    }

    private fun meanOrNaN(values: List<Double>): Double {
        val finite = values.filter { it.isFinite() }
        return if (finite.isEmpty()) Double.NaN else finite.average()
    }
}
