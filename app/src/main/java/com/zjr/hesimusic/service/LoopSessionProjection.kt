package com.zjr.hesimusic.service

import com.zjr.hesimusic.loop.LoopPosition
import com.zjr.hesimusic.loop.LoopWindow
import com.zjr.hesimusic.loop.audio.source.LoopSourceProjection

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

    // ---------------------------------------------------------------- SOURCE 口径

    /**
     * SOURCE 模式：数据源时间轴 T(ms) → 对外媒体时间(µs)。
     *
     * 与 [displayUs] 的区别：SOURCE 的 T 从 0 单调递增且 **T=0 对应当前窗口的 A**，没有
     * 「前奏只播一次」语义，所以对外位置恒为 `A + floorMod(T, L)`（不做 raw < A 的透传）。
     * 通知栏 / MediaSession / 进度条必须读媒体时间，不能读带 offset 的 T。
     */
    fun sourceDisplayUs(timelineMs: Long, window: LoopWindow?): Long =
        LoopSourceProjection.displayUs(timelineMs * US_PER_MS, window)

    /** SOURCE 模式：数据源时间轴 T(ms) → 对外媒体时间(ms)（[getCurrentPosition] 的口径）。 */
    fun sourceDisplayMs(timelineMs: Long, window: LoopWindow?): Long =
        LoopSourceProjection.displayMs(timelineMs, window)

    /** SOURCE 模式：展示位置 → 数据源时间轴 T（[seekTo] 的口径）。 */
    fun sourceTimelineUsForDisplay(
        displayUs: Long,
        currentTimelineUs: Long,
        window: LoopWindow?,
    ): Long = LoopSourceProjection.timelineUsForDisplay(displayUs, currentTimelineUs, window)

    /**
     * 对外时长（µs）。返回 0 表示「不投影」，调用方要透传播放器原生时长。
     *
     * 契约：window 非空且整曲时长已知 → 整曲时长；否则透传。
     */
    fun durationUs(window: LoopWindow?, fullDurationUs: Long): Long =
        if (window != null && fullDurationUs > 0L) fullDurationUs else 0L

    /**
     * MediaSession / 通知 / 锁屏看到的对外时长（ms）。
     *
     * 00027/P0-2：SOURCE 回退（预检五因或 build-failed）时原生 duration 可能是
     * `C.TIME_UNSET`（SOURCE 时间轴故意不声明时长），直接透传会让通知/车机显示 -1。
     * 只要整曲时长已知就一律报整曲——**不因 sourceUnavailable 短路**；只有整曲未知
     * （window=null 或 fullDurationUs<=0）才透传原生值。
     *
     * @param nativeDurationMs `super.getDuration()` 的值（可为负 = TIME_UNSET）
     */
    @Suppress("UNUSED_PARAMETER")
    fun sessionDurationMs(
        sourceUnavailable: Boolean,
        window: LoopWindow?,
        fullDurationUs: Long,
        nativeDurationMs: Long,
    ): Long {
        // sourceUnavailable 在这里**不参与短路**：回退时反而更需要整曲时长。
        val projectedUs = durationUs(window, fullDurationUs)
        return if (projectedUs > 0L) projectedUs / US_PER_MS else nativeDurationMs
    }

    /**
     * 展示位置 → 引擎原始 seek 位置（µs）。
     *
     * 只做**位置映射**(见 [LoopPosition.rawSeekUs]),不再叠加任何"降级预卷":
     * 分片预填上线后,ring 覆盖不足是常态,把用户拖到的位置改成「本轮起点 − 250ms」
     * 会直接表现为"拖到区间中却跳回区段开始"(issue 00001)。缺口由捕获/后续分片补齐。
     */
    fun seekUs(
        targetUs: Long,
        window: LoopWindow?,
        @Suppress("UNUSED_PARAMETER") currentRawUs: Long,
        @Suppress("UNUSED_PARAMETER") isRingComplete: Boolean,
    ): Long {
        if (window == null) return targetUs
        return LoopPosition.rawSeekUs(targetUs, window)
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

    // ---------------------------------------------------------------- 窗口来源（C2 修复）

    /** 窗口来源：服务层派生态 / 重放期的音频层权威值 / 无（透传）。 */
    enum class WindowSource {
        /** runtime.window（服务层派生态，正常情况下就是它）。 */
        RUNTIME,
        /** runtime.window 暂不可用（异步 refresh 尚未落值/还是上一首）时的重放期回退。 */
        AUDIO_FALLBACK,
        /** 没有任何可用窗口 → 调用方完全透传。 */
        NONE,
    }

    data class ResolvedWindow(
        val window: LoopWindow?,
        val source: WindowSource,
    )

    /**
     * 选出「当前项该用哪个窗口」。
     *
     * 为什么需要回退（E2E 15472/15579 实测）：重放开始后 1.0~1.5s 内，对外 position 会跟着
     * ExoPlayer 原始位置冲到 B+1381ms（历史最大 2.7s）再折返 —— 因为 runtime.window 由
     * LoopPlaybackCoordinator.refresh() 的异步协程写入，在 transition/seek 之后的短窗口里
     * 可能还是 null 或上一首的，而会话层当时只认它 → 退化成透传。
     *
     * 音频层的 window 是在 syncShape 里**同步**写的，且引擎只有在 window 非空时才重放，
     * 所以「isReplaying == true」时 audio.window 就是权威口径。
     *
     * 两处都做 songId 归属校验：事件可能早于/晚于 transition。
     */
    fun resolveWindow(
        songId: Long?,
        runtimeWindow: LoopWindow?,
        audioWindow: LoopWindow?,
        isReplaying: Boolean,
    ): ResolvedWindow {
        val usableRuntime = runtimeWindow?.takeIf { songId != null && it.songId == songId }
        if (usableRuntime != null) return ResolvedWindow(usableRuntime, WindowSource.RUNTIME)

        // 只在重放期回退：非重放期音频层没有 window 语义，透传才是正确行为
        if (isReplaying) {
            val usableAudio = audioWindow?.takeIf { songId != null && it.songId == songId }
            if (usableAudio != null) return ResolvedWindow(usableAudio, WindowSource.AUDIO_FALLBACK)
        }
        return ResolvedWindow(null, WindowSource.NONE)
    }
}
