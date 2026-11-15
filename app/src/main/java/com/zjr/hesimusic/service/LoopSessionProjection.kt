package com.zjr.hesimusic.service

import com.zjr.hesimusic.loop.LoopPosition
import com.zjr.hesimusic.loop.LoopWindow

/**
 * 会话层位置/时长投影（纯函数，无 Android / Media3 依赖，可在纯 JVM 单测穷举）。
 *
 * 口径：
 * - **raw**：引擎（ExoPlayer + LoopAudioSink）自己的媒体位置（µs）。无缝重放期 =
 *   B + (k-1)L + 环内偏移，由 [com.zjr.hesimusic.loop.audio.LoopAudioController.positionUs] 给出。
 * - **display**：UI / 通知 / MediaSession 看到的进度（µs），永远折返进 [A, B)（前奏只播一次）。
 * - **时长**：无缝时对外恒为**整曲**时长（不裁剪时与原生一致，覆盖只为防御）。
 *
 * [LoopSessionPlayer] 只做「取 raw → 调本对象 → 换算 ms」的薄转发，所有判断都集中在这里，
 * 因此位置投影可以脱离 Media3 Player 在纯 JVM 上验证（见 LoopSessionPlayerTest）。
 *
 * 契约：docs/loop-v3-service-design.md §2.2 / §4 / §5。
 */
object LoopSessionProjection {

    const val US_PER_MS: Long = 1_000L

    /**
     * 降级预卷量：目标落在 [A,B) 但 ring 尚未覆盖该区间时，回跳到**该轮起点前 250ms**，
     * 让本轮完整经过 [A,B) 以补齐 ring（P0 实测：seek 到 A 会因解码 seek 精度落到 A+15ms）。
     */
    const val DEGRADE_PREROLL_US: Long = 250_000L

    /**
     * 取引擎原始位置（µs）。
     *
     * 重放期用 sink 推进的位置（此时播放器自身位置冻结在 B）；非重放期用播放器位置（ms → µs）。
     */
    fun rawUs(isReplaying: Boolean, replayPositionUs: Long, playerPositionMs: Long): Long =
        if (isReplaying) replayPositionUs else playerPositionMs * US_PER_MS

    /** 原始位置 → 展示位置（µs）；window = null 时完全透传。非法窗口同样透传（见 LoopPosition）。 */
    fun displayUs(rawUs: Long, window: LoopWindow?): Long =
        if (window == null) rawUs else LoopPosition.displayUs(rawUs, window)

    /**
     * 对外时长（µs）。返回 0 表示「不投影」，调用方要透传播放器原生时长。
     *
     * 契约：window 非空且整曲时长已知 → 整曲时长；否则透传。
     */
    fun durationUs(window: LoopWindow?, fullDurationUs: Long): Long =
        if (window != null && fullDurationUs > 0L) fullDurationUs else 0L

    /**
     * 展示位置 → 引擎原始 seek 位置（µs）。
     *
     * 三个分支由 [LoopPosition.rawSeekUs] 决定（target < A 回前奏 / [A,B) 保持当前轮次 /
     * >= B 折回第 0 轮）。在此基础上叠加**降级预卷**：
     * 若 ring 不完整且目标落在 [A,B) 内，则改为 seek 到「该轮起点 − 250ms」，
     * 让本轮完整流经 [A,B) 后环回，从而补齐 ring（契约 §4）。
     */
    fun seekUs(
        targetUs: Long,
        window: LoopWindow?,
        currentRawUs: Long,
        isRingComplete: Boolean,
    ): Long {
        if (window == null) return targetUs
        val rawUs = LoopPosition.rawSeekUs(targetUs, window, currentRawUs)
        if (isRingComplete || !window.containsUs(targetUs)) return rawUs
        // rawSeekUs 在 [A,B) 分支返回 start + k*L + (target-start)，
        // 反推本轮起点 = raw - (target - start)（与 iterationIndexUs 同义，但不必暴露 internal）。
        val iterationStartUs = rawUs - (targetUs - window.loopStartUs)
        return degradedSeekUs(iterationStartUs)
    }

    /**
     * 降级预卷的原始位置：该轮起点前 [DEGRADE_PREROLL_US]（不越过 0）。
     *
     * 预卷落在「该轮起点 − 250ms」而不是更早，是为了让 250ms 的补偿全部发生在环回点之前：
     * 解码 seek 精度约 15ms、PCM 帧量化 < 1ms，250ms 足够兜住且用户几乎无感。
     */
    fun degradedSeekUs(iterationStartUs: Long): Long =
        (iterationStartUs - DEGRADE_PREROLL_US).coerceAtLeast(0L)

    /**
     * 缓冲位置（µs，展示口径）：先折返，再保证不小于当前展示位置，最后 clamp 到对外时长。
     *
     * 重放期播放器自身缓冲位冻结在 B，折返后是 A，若不与当前位置取 max，
     * 进度条的缓冲条会「倒退」到环回点前。
     */
    fun bufferedUs(
        rawBufferedUs: Long,
        window: LoopWindow?,
        currentDisplayUs: Long,
        durationUs: Long,
    ): Long {
        val projected = displayUs(rawBufferedUs, window)
        val atLeastCurrent = maxOf(projected, currentDisplayUs)
        return if (durationUs > 0L) atLeastCurrent.coerceAtMost(durationUs) else atLeastCurrent
    }
}
