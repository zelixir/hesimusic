package com.zjr.hesimusic.service

import com.zjr.hesimusic.loop.LoopPhase
import com.zjr.hesimusic.loop.LoopWindow

/**
 * 主播放器**最近一次权威位置**的进程内快照（issue 00028）。
 *
 * 为什么需要：MediaController 在两次 MediaSession 位置事件之间会按 speed 本地外推，
 * UI 看到的 controller.currentPosition 可能暂时冲出循环区间。UI 的折返函数需要区分：
 * - loop 阶段的"外推过 B" -> 折回 A（旧行为，避免进度条冲出区间）；
 * - tail 阶段的"真的在 B 之后" -> 原样保留（不能折回 A）。
 *
 * 这里记录 [com.zjr.hesimusic.service.LoopSessionPlayer] 自己算出的**时间轴 T / 展示位置**，
 * 只作为 claim 的 phase 提示与 /state 诊断，不参与播放/seek/位置计算。
 */
object LoopPlaybackPhaseRuntime {

    @Volatile
    var songId: Long? = null
        private set

    /** 数据源时间轴 T（µs，SOURCE 整曲口径）。 */
    @Volatile
    var timelineUs: Long = 0L
        private set

    /** 展示位置（µs）：prelude/tail 直通、loop 折返。 */
    @Volatile
    var displayUs: Long = 0L
        private set

    @Volatile
    var fullDurationUs: Long = 0L
        private set

    @Volatile
    var window: LoopWindow? = null
        private set

    @Volatile
    var phase: LoopPhase? = null
        private set

    fun update(
        songId: Long?,
        timelineUs: Long,
        displayUs: Long,
        window: LoopWindow?,
        fullDurationUs: Long,
    ) {
        this.songId = songId
        this.timelineUs = timelineUs.coerceAtLeast(0L)
        this.displayUs = displayUs.coerceAtLeast(0L)
        this.window = window
        this.fullDurationUs = fullDurationUs.coerceAtLeast(0L)
        this.phase = LoopPhase.ofDisplayUs(this.displayUs, window)
    }

    fun phaseFor(songId: Long?, window: LoopWindow?): LoopPhase? {
        val w = window ?: return null
        if (songId == null || this.songId != songId) return null
        if (this.window != w) return null
        return phase
    }

    /** 当前 T 的 epoch 内偏移（µs）；无窗口时返回 T 本身。 */
    fun localTimelineUs(): Long =
        com.zjr.hesimusic.loop.audio.source.LoopWholeTimeline.localTimelineUs(
            timelineUs,
            fullDurationUs,
            window,
        )

    fun reset() {
        songId = null
        timelineUs = 0L
        displayUs = 0L
        fullDurationUs = 0L
        window = null
        phase = null
    }
}
