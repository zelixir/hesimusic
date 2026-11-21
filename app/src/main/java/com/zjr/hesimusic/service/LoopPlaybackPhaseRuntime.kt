package com.zjr.hesimusic.service

import com.zjr.hesimusic.loop.LoopPhase
import com.zjr.hesimusic.loop.LoopWindow

/**
 * 主播放器**最近一次权威展示位置**的进程内快照（issue 00028）。
 *
 * 为什么需要：MediaController 在两次 MediaSession 位置事件之间会按 speed 本地外推，
 * UI 看到的 controller.currentPosition 可能暂时冲出循环区间。UI 的折返函数需要区分：
 * - loop 阶段的"外推过 B" -> 折回 A（旧行为，避免进度条冲出区间）；
 * - tail 阶段的"真的在 B 之后" -> 原样保留（不能折回 A）。
 *
 * 这里记录 [com.zjr.hesimusic.service.LoopSessionPlayer] 自己算出的展示位置（权威值），
 * 只作为 claim 的 phase 提示，不参与播放/seek/位置计算。判定仍然是 [LoopPhase.ofDisplayUs]
 * 的纯函数，不引入第二套阶段口径。
 */
object LoopPlaybackPhaseRuntime {

    @Volatile
    var songId: Long? = null
        private set

    @Volatile
    var displayUs: Long = 0L
        private set

    @Volatile
    var window: LoopWindow? = null
        private set

    fun update(songId: Long?, displayUs: Long, window: LoopWindow?) {
        this.songId = songId
        this.displayUs = displayUs.coerceAtLeast(0L)
        this.window = window
    }

    fun phaseFor(songId: Long?, window: LoopWindow?): LoopPhase? {
        val w = window ?: return null
        if (songId == null || this.songId != songId) return null
        if (this.window != w) return null
        return LoopPhase.ofDisplayUs(displayUs, w)
    }

    fun reset() {
        songId = null
        displayUs = 0L
        window = null
    }
}
