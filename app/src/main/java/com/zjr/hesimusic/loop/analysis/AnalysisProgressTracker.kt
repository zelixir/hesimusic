package com.zjr.hesimusic.loop.analysis

import kotlin.math.ceil
import kotlin.math.roundToLong

/**
 * 单次分析的进度跟踪器(issue 00018)。
 *
 * - 工作单位:解码帧 / 分析块;解码/分析回调只给 fraction,这里按标称总量还原成工作量
 *   (比例不变,避免为了拿帧号去改解码器/JNI 契约);
 * - 预算:解码/分析按 [AnalysisTiming] 最近一次同档位实测比例加权(默认 0.66/0.34);
 * - 输出:单调 + EMA 平滑 + 上报台阶上限(见 [AnalysisProgress.compute]);
 * - 节流:常规更新 ≤10Hz(100ms),阶段切换强制立即上报;
 * - 心跳:工作回调空档(或进度已追平工作量目标)时按 [CREEP_STEP] 微步推进,
 *   收尾段则按 [AnalysisProgress.MAX_STEP] 逐步逼近 [AnalysisProgress.WORK_END],
 *   保证不会出现 >1s 完全静止,也不会在 Done 前一次跳到底。
 *
 * 时钟可注入([clock]),便于 JVM 单测构造假时间线。
 */
internal class AnalysisProgressTracker(
    durationMs: Long,
    private val clock: () -> Long = System::currentTimeMillis,
    private val decodeWeight: Float = AnalysisTiming.decodeWeightFor(durationMs),
    private val publish: (AnalysisProgress.Phase, Float) -> Unit,
) {

    /** 预计总帧:时长已知时按 44.1kHz 估算(只影响归一化比例),未知时用标称工作量。 */
    private var totalFrames: Long = if (durationMs > 0L) {
        durationMs * NOMINAL_SAMPLE_RATE / 1000L
    } else {
        NOMINAL_WORK_UNITS
    }

    private var phase = AnalysisProgress.Phase.STARTING
    private var smoothed = 0f
    private var lastWorkTarget = 0f
    private var lastWorkMs = 0L
    private var lastEmitMs = 0L
    private var lastEmitted = -1f
    private var finished = false

    /** 当前上报值(测试/诊断用);未上报过为 -1。 */
    val reportedProgress: Float get() = lastEmitted

    /** 当前阶段(测试/诊断用)。 */
    val currentPhase: AnalysisProgress.Phase get() = phase

    @Synchronized
    fun start() {
        lastWorkMs = clock()
        update(AnalysisProgress.Phase.STARTING, decodedFrames = 0L, analyzedBlocks = 0L, force = true)
    }

    @Synchronized
    fun onDecode(fraction: Float) {
        lastWorkMs = clock()
        update(
            phase = AnalysisProgress.Phase.DECODING,
            decodedFrames = (sanitizeFraction(fraction) * totalFrames).roundToLong(),
            analyzedBlocks = 0L,
            force = false,
        )
    }

    /** 解码完成:用真实帧数替换估算总量,并立即上报分析起点。 */
    @Synchronized
    fun onDecodeComplete(frames: Long) {
        if (frames > 0L) totalFrames = frames
        lastWorkMs = clock()
        update(
            phase = AnalysisProgress.Phase.ANALYZING,
            decodedFrames = totalFrames,
            analyzedBlocks = 0L,
            force = true,
        )
    }

    @Synchronized
    fun onAnalyze(fraction: Float) {
        lastWorkMs = clock()
        update(
            phase = AnalysisProgress.Phase.ANALYZING,
            decodedFrames = totalFrames,
            analyzedBlocks = (sanitizeFraction(fraction) * PROGRESS_BLOCKS).roundToLong(),
            force = false,
        )
    }

    /**
     * 进入收尾段:只切换阶段,不在这里迈台阶。
     *
     * 引擎最后几次回调可能和收尾落在同一个 100ms 采样窗口里;若这里再推一步,
     * 外部采样会看到「最后回调 + 收尾」叠加成一个大台阶。台阶统一交给 [heartbeat]
     * 按 100ms 节拍推进(0.95 之前每次最多 +[AnalysisProgress.MAX_STEP])。
     */
    @Synchronized
    fun onFinalizing() {
        if (finished) return
        phase = AnalysisProgress.Phase.FINALIZING
        lastWorkTarget = AnalysisProgress.WORK_END
        lastWorkMs = clock()
    }

    /**
     * 心跳(由管理器每 100ms 调一次):
     * - 收尾段:上报值按 [AnalysisProgress.MAX_STEP] 逐步逼近 0.95;
     * - 其他阶段:工作回调空档 ≥ [STALL_FILL_AFTER_MS],或进度已经追平/超过工作量目标
     *   (例如并行解码失败后回退单线程、进度暂时不再前进)时,做一次极小步进。
     */
    @Synchronized
    fun heartbeat() {
        if (finished) return
        val now = clock()
        if (phase == AnalysisProgress.Phase.FINALIZING) {
            if (lastEmitted >= AnalysisProgress.WORK_END - EMIT_EPSILON) return
            smoothed = minOf(AnalysisProgress.WORK_END, smoothed + AnalysisProgress.MAX_STEP)
            emit()
            return
        }
        if (now - lastEmitMs < UPDATE_INTERVAL_MS) return
        val stalled = now - lastWorkMs >= STALL_FILL_AFTER_MS
        val aheadOfWork = smoothed > lastWorkTarget
        if (!stalled && !aheadOfWork) return
        val next = minOf(AnalysisProgress.WORK_END, smoothed + CREEP_STEP)
        if (next <= smoothed) return
        smoothed = next
        emit()
    }

    /** 收尾是否**已上报**到 0.95(内部值到 0.95 但还没发出去不算)。 */
    @Synchronized
    fun finalizingSettled(): Boolean =
        phase == AnalysisProgress.Phase.FINALIZING &&
            lastEmitted >= AnalysisProgress.WORK_END - EMIT_EPSILON

    /**
     * 从当前上报值走到 0.95 所需的等待预算(毫秒,含 1s 余量,覆盖调度抖动)。
     * 心跳每个 100ms tick 最多推一个 [AnalysisProgress.MAX_STEP]。
     */
    @Synchronized
    fun finalizeWaitBudgetMs(): Long {
        val remaining = (AnalysisProgress.WORK_END - lastEmitted.coerceIn(0f, 1f)).coerceAtLeast(0f)
        val ticks = ceil(remaining / AnalysisProgress.MAX_STEP).toInt().coerceAtLeast(0)
        return ticks.toLong() * UPDATE_INTERVAL_MS + 1_000L
    }

    /**
     * 分析成功:按 DONE 台阶再推进一步(0.95 → 1.0),随后由状态机发 Done(1.0)。
     */
    @Synchronized
    fun finish() {
        if (finished) return
        phase = AnalysisProgress.Phase.DONE
        smoothed = AnalysisProgress.compute(
            phase = AnalysisProgress.Phase.DONE,
            decodedFrames = totalFrames,
            totalFrames = totalFrames,
            analyzedBlocks = PROGRESS_BLOCKS,
            totalBlocks = PROGRESS_BLOCKS,
            smoothedPrev = smoothed,
            decodeWeight = decodeWeight,
            analyzeWeight = 1f - decodeWeight,
        )
        if (smoothed > lastEmitted + EMIT_EPSILON) emit()
        finished = true
    }

    private fun update(
        phase: AnalysisProgress.Phase,
        decodedFrames: Long,
        analyzedBlocks: Long,
        force: Boolean,
    ) {
        if (finished) return
        this.phase = phase
        val analyzeWeight = 1f - decodeWeight
        lastWorkTarget = AnalysisProgress.workTarget(
            phase = phase,
            decodedFrames = decodedFrames,
            totalFrames = totalFrames,
            analyzedBlocks = analyzedBlocks,
            totalBlocks = PROGRESS_BLOCKS,
            decodeWeight = decodeWeight,
            analyzeWeight = analyzeWeight,
        )
        smoothed = AnalysisProgress.compute(
            phase = phase,
            decodedFrames = decodedFrames,
            totalFrames = totalFrames,
            analyzedBlocks = analyzedBlocks,
            totalBlocks = PROGRESS_BLOCKS,
            smoothedPrev = smoothed,
            decodeWeight = decodeWeight,
            analyzeWeight = analyzeWeight,
        )
        if (!force) {
            val now = clock()
            if (now - lastEmitMs < UPDATE_INTERVAL_MS) return
            if (smoothed <= lastEmitted + EMIT_EPSILON) return
        }
        emit()
    }

    private fun emit() {
        // 台阶上限作用在**真正上报的值**上:被节流吞掉的更新会在下一次上报时累积,
        // 若只约束内部值,外部 100ms 采样仍可能一次看到两个台阶叠加。
        val reported = when {
            lastEmitted < 0f -> smoothed
            // 终态不压 epsilon,保证最后一个上报值就是 1.0。
            phase == AnalysisProgress.Phase.DONE -> minOf(smoothed, 1f)
            // 减 1e-6 抵消 Float 加法舍入,保证外部实测相邻差严格 ≤ MAX_STEP。
            else -> minOf(smoothed, lastEmitted + AnalysisProgress.MAX_STEP - STEP_EPSILON)
        }
        lastEmitMs = clock()
        lastEmitted = reported
        publish(phase, reported)
    }

    private fun sanitizeFraction(fraction: Float): Float =
        if (fraction.isNaN()) 0f else fraction.coerceIn(0f, 1f)

    companion object {
        /** 常规进度上报间隔(≤10Hz)。 */
        const val UPDATE_INTERVAL_MS = 100L

        /** 工作回调空档超过该值 → 心跳微步,避免 >1s 完全静止。 */
        const val STALL_FILL_AFTER_MS = 250L

        /** 心跳微步(每次 100ms tick 最多推进 0.2%)。 */
        const val CREEP_STEP = 0.002f

        /** 低于该增量不重复上报。 */
        const val EMIT_EPSILON = 1e-4f

        /** 抵消 Float 加法舍入,保证相邻上报差严格不超过 MAX_STEP。 */
        private const val STEP_EPSILON = 1e-6f

        /** 分析块标称总量(引擎只给 fraction,这里归一化成块数)。 */
        const val PROGRESS_BLOCKS = 1_000L

        /** 时长未知时的标称总帧数(只影响归一化比例)。 */
        const val NOMINAL_WORK_UNITS = 1_000_000L

        /** 解码帧数估算用标称采样率。 */
        const val NOMINAL_SAMPLE_RATE = 44_100
    }
}
