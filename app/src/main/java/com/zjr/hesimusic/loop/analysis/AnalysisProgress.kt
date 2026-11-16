package com.zjr.hesimusic.loop.analysis

import java.util.concurrent.ConcurrentHashMap

/**
 * 分析进度纯函数(issue 00018)。
 *
 * 进度口径不再是"阶段名 + 粗粒度百分比",而是:
 *
 * **progress = 已完成工作量 / 预计总工作量**
 *
 * - 工作量单位:解码用**已解码帧数**,分析用**已分析块数**;
 * - 预算比例:解码/分析按 decode:analyze 时间比例分配(默认 [DEFAULT_DECODE_WEIGHT] /
 *   [DEFAULT_ANALYZE_WEIGHT],真机实测 5.3s : 2.7s;实际比例由 [AnalysisTiming] 校准);
 * - 单调 + 平滑:输出永不回退,EMA(alpha)平滑,单次更新台阶不超过 [MAX_STEP];
 * - 端点:开始即 [START](>0);最后 5% 留给收尾([WORK_END]..1.0),终态 [Phase.DONE] 立即 1.0。
 *
 * 纯函数:无 Android/协程/IO 依赖,可 JVM 单测。
 */
object AnalysisProgress {

    /** 分析进度阶段。 */
    enum class Phase {
        /** 分析已开始,但还没有可计量的工作量(准备/首帧前)。 */
        STARTING,

        /** 解码阶段:工作量 = 已解码帧 / 预计总帧。 */
        DECODING,

        /** 分析阶段:工作量 = 解码(已完成)+ 已分析块 / 预计总块。 */
        ANALYZING,

        /** 收尾(解析候选/落库):进度停在 [WORK_END],等终态。 */
        FINALIZING,

        /** 终态:直接 1.0。 */
        DONE,
    }

    /** 分析开始时的进度(>0,表示"已开始",避免长时间停在 0)。 */
    const val START = 0.02f

    /** 工作量走完时的进度;最后 5% 保留给收尾/落库。 */
    const val WORK_END = 0.95f

    /** 真机实测默认预算比例:解码约 5.3s、分析约 2.7s。 */
    const val DEFAULT_DECODE_WEIGHT = 0.66f
    const val DEFAULT_ANALYZE_WEIGHT = 0.34f

    /** EMA 平滑系数(0.25 ≈ 4 个更新周期收敛 63%)。 */
    const val DEFAULT_ALPHA = 0.25f

    /** 单次更新允许的最大进度台阶(阶段切换也在内)。 */
    const val MAX_STEP = 0.05f

    /**
     * 只计算**未平滑**的工作量目标(供落库/心跳逻辑复用),返回值 ∈ [0,1]。
     *
     * @param phase 当前阶段;
     * @param decodedFrames 已解码帧数(解码中 = 当前帧,分析中 = 总帧);
     * @param totalFrames 预计总帧数(<=0 表示未知,按 0 计);
     * @param analyzedBlocks 已分析块数;
     * @param totalBlocks 预计总块数(<=0 表示未知,按 0 计);
     * @param decodeWeight / [analyzeWeight] 解码/分析预算权重(可配置,内部归一化)。
     */
    fun workTarget(
        phase: Phase,
        decodedFrames: Long,
        totalFrames: Long,
        analyzedBlocks: Long,
        totalBlocks: Long,
        decodeWeight: Float = DEFAULT_DECODE_WEIGHT,
        analyzeWeight: Float = DEFAULT_ANALYZE_WEIGHT,
    ): Float {
        if (phase == Phase.DONE) return 1f
        val wd = normalizedDecodeWeight(decodeWeight, analyzeWeight)
        val wa = 1f - wd
        val decodeFraction = fraction(decodedFrames, totalFrames)
        val analyzeFraction = fraction(analyzedBlocks, totalBlocks)
        val span = WORK_END - START
        return when (phase) {
            Phase.STARTING -> START
            Phase.DECODING -> START + span * wd * decodeFraction
            Phase.ANALYZING -> START + span * (wd + wa * analyzeFraction)
            Phase.FINALIZING -> WORK_END
            Phase.DONE -> 1f
        }.coerceIn(0f, 1f)
    }

    /**
     * 计算本次要上报的进度(单调 + EMA 平滑 + 台阶上限)。
     *
     * @param phase 当前阶段;
     * @param decodedFrames 已解码帧数;
     * @param totalFrames 预计总帧数;
     * @param analyzedBlocks 已分析块数;
     * @param totalBlocks 预计总块数;
     * @param smoothedPrev 上一次的平滑输出(传 0/N/A 表示新一次分析,进度从 [START] 重新开始);
     * @param decodeWeight / [analyzeWeight] 预算权重(可配置);
     * @param alpha EMA 系数(默认 [DEFAULT_ALPHA]);
     * @param maxStep 单次更新台阶上限(默认 [MAX_STEP]);
     * @return ∈ [0,1],且 >= 钳制后的 [smoothedPrev]。
     */
    fun compute(
        phase: Phase,
        decodedFrames: Long,
        totalFrames: Long,
        analyzedBlocks: Long,
        totalBlocks: Long,
        smoothedPrev: Float,
        decodeWeight: Float = DEFAULT_DECODE_WEIGHT,
        analyzeWeight: Float = DEFAULT_ANALYZE_WEIGHT,
        alpha: Float = DEFAULT_ALPHA,
        maxStep: Float = MAX_STEP,
    ): Float {
        val prev = sanitizeProgress(smoothedPrev)
        // 终态立即 1.0。正常路径先经 FINALIZING(0.95),所以最后一步恰好是 MAX_STEP。
        if (phase == Phase.DONE) return 1f

        val target = workTarget(
            phase = phase,
            decodedFrames = decodedFrames,
            totalFrames = totalFrames,
            analyzedBlocks = analyzedBlocks,
            totalBlocks = totalBlocks,
            decodeWeight = decodeWeight,
            analyzeWeight = analyzeWeight,
        )
        val stepLimit = finiteOrDefault(maxStep, MAX_STEP).coerceIn(0f, 1f)

        val out = when (phase) {
            // 收尾段很短且紧接着 DONE:直接以台阶上限逼近 WORK_END,
            // 避免 EMA 滞后导致 DONE 那一步超过台阶上限。
            Phase.FINALIZING -> if (prev >= WORK_END) prev else prev + minOf(stepLimit, WORK_END - prev)

            Phase.STARTING, Phase.DECODING, Phase.ANALYZING -> {
                val a = finiteOrDefault(alpha, DEFAULT_ALPHA).coerceIn(0f, 1f)
                var eased = maxOf(prev, prev + a * (target - prev))
                // 已开始就至少从 START 起步(避免准备期长时间停在 0)。
                eased = maxOf(eased, minOf(START, prev + stepLimit))
                if (stepLimit > 0f) minOf(eased, prev + stepLimit) else prev
            }

            Phase.DONE -> 1f
        }
        return maxOf(prev, out).coerceIn(0f, 1f)
    }

    /** 已完成/总量 → [0,1];总量未知(<=0)按 0 处理,避免除零/NaN。 */
    private fun fraction(done: Long, total: Long): Float {
        if (done <= 0L || total <= 0L) return 0f
        if (done >= total) return 1f
        return (done.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
    }

    /** 权重归一化:非有限值/负值按 0,总和为 0 时退回默认比例。 */
    private fun normalizedDecodeWeight(decodeWeight: Float, analyzeWeight: Float): Float {
        val dw = weightOrZero(decodeWeight)
        val aw = weightOrZero(analyzeWeight)
        val sum = dw + aw
        return if (sum <= 0f) DEFAULT_DECODE_WEIGHT else (dw / sum).coerceIn(0f, 1f)
    }

    private fun weightOrZero(value: Float): Float =
        if (value.isNaN() || value.isInfinite()) 0f else value.coerceAtLeast(0f)

    private fun finiteOrDefault(value: Float, fallback: Float): Float =
        if (value.isNaN() || value.isInfinite()) fallback else value

    private fun sanitizeProgress(value: Float): Float =
        if (value.isNaN()) 0f else value.coerceIn(0f, 1f)
}

/**
 * 时间预算校准(issue 00018):记录最近一次(按时长档)实测的 decode:analyze 耗时比例,
 * 下一次同档位分析按同样比例分配进度预算。
 *
 * "同格式"信息在本层拿不到(不做容器探测),用**时长档**近似:同一首歌 / 相同时长的曲目
 * 编解码工作量基本一致。
 */
object AnalysisTiming {

    /** 时长分档粒度:10s。 */
    const val DURATION_BUCKET_MS = 10_000L

    /** 单一阶段权重安全区间,避免病态比例把某一段压成 0。 */
    const val MIN_WEIGHT = 0.05f
    const val MAX_WEIGHT = 0.95f

    /** 未被校准时的默认解码权重(真机实测 5.3s : 2.7s)。 */
    @Volatile
    var defaultDecodeWeight: Float = AnalysisProgress.DEFAULT_DECODE_WEIGHT
        set(value) {
            field = if (value.isNaN() || value.isInfinite()) {
                AnalysisProgress.DEFAULT_DECODE_WEIGHT
            } else {
                value.coerceIn(MIN_WEIGHT, MAX_WEIGHT)
            }
        }

    private val measuredDecodeWeights = ConcurrentHashMap<Long, Float>()

    /** 时长 → 档位(<=0 归到 0 档)。 */
    fun bucketOf(durationMs: Long): Long =
        if (durationMs <= 0L) 0L else durationMs / DURATION_BUCKET_MS

    /**
     * 记录一次成功分析的实际耗时;任一段 <=0 时忽略(首次分析/异常路径不校准)。
     */
    fun record(durationMs: Long, decodeMs: Long, analyzeMs: Long) {
        if (decodeMs <= 0L || analyzeMs <= 0L) return
        val total = decodeMs.toDouble() + analyzeMs.toDouble()
        if (total <= 0.0 || total.isInfinite() || total.isNaN()) return
        val weight = (decodeMs.toDouble() / total).toFloat().coerceIn(MIN_WEIGHT, MAX_WEIGHT)
        measuredDecodeWeights[bucketOf(durationMs)] = weight
    }

    /**
     * 该时长档最近一次实测的解码权重;没有同档位测量时用 [defaultDecodeWeight]。
     */
    fun decodeWeightFor(durationMs: Long): Float =
        measuredDecodeWeights[bucketOf(durationMs)] ?: defaultDecodeWeight

    /** 已校准的档位快照(诊断/测试用)。 */
    fun snapshot(): Map<Long, Float> = measuredDecodeWeights.toMap()

    /** 清空校准并恢复默认(测试/诊断用)。 */
    fun clear() {
        measuredDecodeWeights.clear()
        defaultDecodeWeight = AnalysisProgress.DEFAULT_DECODE_WEIGHT
    }
}
