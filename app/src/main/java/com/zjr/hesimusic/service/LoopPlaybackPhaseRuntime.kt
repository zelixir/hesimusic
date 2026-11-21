package com.zjr.hesimusic.service

import com.zjr.hesimusic.loop.LoopPhase
import com.zjr.hesimusic.loop.LoopWindow
import com.zjr.hesimusic.loop.audio.source.LoopWholeTimeline

/**
 * 主播放器**最近一次权威位置**的进程内快照（issue 00028）。
 *
 * 为什么需要：MediaController 在两次 MediaSession 位置事件之间会按 speed 本地外推，
 * UI 看到的 controller.currentPosition 可能暂时冲出循环区间。UI 的折返函数需要区分：
 * - loop 阶段的"外推过 B" -> 折回 A（旧行为，避免进度条冲出区间）；
 * - tail 阶段的"真的在 B 之后" -> 原样保留（不能折回 A）。
 *
 * 这里记录 [LoopSessionPlayer] 自己算出的**时间轴 T / 展示位置**。所有字段装在同一个
 * [Snapshot] 里、一次 volatile 写发布，/state 一次读取不会拼出"T 是新的、display 是旧的"
 * 的混合现场。只作为 claim 的 phase 提示与 /state 诊断，不参与播放/seek/位置计算。
 */
object LoopPlaybackPhaseRuntime {

    data class Snapshot(
        val songId: Long?,
        /** 数据源时间轴 T（µs，SOURCE 整曲口径）。 */
        val timelineUs: Long,
        /** 展示位置（µs）：prelude/tail 直通、loop 折返。 */
        val displayUs: Long,
        val fullDurationUs: Long,
        val window: LoopWindow?,
        val phase: LoopPhase?,
    ) {
        val localTimelineUs: Long
            get() = LoopWholeTimeline.localTimelineUs(timelineUs, fullDurationUs, window)
    }

    @Volatile
    var current: Snapshot? = null
        private set

    fun update(
        songId: Long?,
        timelineUs: Long,
        displayUs: Long,
        window: LoopWindow?,
        fullDurationUs: Long,
    ) {
        val display = displayUs.coerceAtLeast(0L)
        current = Snapshot(
            songId = songId,
            timelineUs = timelineUs.coerceAtLeast(0L),
            displayUs = display,
            fullDurationUs = fullDurationUs.coerceAtLeast(0L),
            window = window,
            phase = LoopPhase.ofDisplayUs(display, window),
        )
    }

    fun phaseFor(songId: Long?, window: LoopWindow?): LoopPhase? {
        val snap = current ?: return null
        val w = window ?: return null
        if (songId == null || snap.songId != songId) return null
        if (snap.window != w) return null
        return snap.phase
    }

    fun reset() {
        current = null
    }
}
