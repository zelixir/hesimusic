package com.zjr.hesimusic.ui.loop

import com.zjr.hesimusic.ui.loop.state.LoopPageMode
import com.zjr.hesimusic.ui.loop.state.LoopPageState
import com.zjr.hesimusic.ui.loop.state.PlaybackState

/**
 * 播放头 60fps 插值(纯函数,可 JVM 单测)。
 *
 * 折返规则(issue 00023):
 * - 循环区外自由试听([LoopPageState.Ready.auditionOutside])与自由模式 → **不折返**,
 *   按真实媒体时间推进(否则"拖到 0:30 却显示循环区内的位置"会制造 seek 被夹回的错觉);
 * - 循环试听 → 越过 A 后按 [aMs, bMs) 折返,与音频层的环回对齐。
 */
object LoopPlayhead {

    fun interpolate(
        state: LoopPageState.Ready,
        anchorMs: Long,
        anchorElapsedRealtime: Long,
        nowElapsedRealtime: Long,
    ): Long {
        if (state.playback !is PlaybackState.Playing) return state.positionMs
        if (anchorElapsedRealtime == 0L) return state.positionMs

        val duration = state.durationMs.coerceAtLeast(0L)
        val extrapolated = anchorMs + (nowElapsedRealtime - anchorElapsedRealtime)
        if (state.auditionOutside || state.mode == LoopPageMode.FREE) {
            return extrapolated.coerceIn(0L, duration)
        }

        val a = state.aMs
        val b = state.bMs
        val length = b - a
        if (length <= 0L) return extrapolated.coerceIn(0L, duration)
        return if (extrapolated < a) {
            extrapolated.coerceIn(0L, duration)
        } else {
            a + ((extrapolated - a) % length)
        }
    }
}
