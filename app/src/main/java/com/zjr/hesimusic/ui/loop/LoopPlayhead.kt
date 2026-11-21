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
 *
 * 加载态(issue 00022):找接缝/换模式后播放器进入 buffering,真实位置**停在落点不动**,
 * 此时只返回加载落点(锚点)、绝不外推 —— 否则指针会以 1s/s 先跑到目标前面,
 * 首帧出声时再被真实位置拽回来,看起来就是"指针乱跳"。
 */
object LoopPlayhead {

    /**
     * @param loading true = 播放器正在加载(等待首块/缓冲),真实位置没有任何推进。
     *   此时**不做任何外推**:返回 [anchorMs] —— 加载中的锚点就是本次操作的落点
     *   (seek 目标/模式起点,由 play/seek effect 在点击同一轮写入);播放器真正推进后
     *   位置采样会把锚点换成真实位置。
     */
    fun interpolate(
        state: LoopPageState.Ready,
        anchorMs: Long,
        anchorElapsedRealtime: Long,
        nowElapsedRealtime: Long,
        loading: Boolean = false,
    ): Long {
        if (state.playback !is PlaybackState.Playing) return state.positionMs
        if (anchorElapsedRealtime == 0L) return state.positionMs
        // 00022:加载态不外推 —— 显示位置必须来自"真实播放位置",加载中它 = 落点(锚点)。
        if (loading) return anchorMs

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

/**
 * 播放头观测点(issue 00022 真机量测)。
 *
 * 60fps 插值结果由 [com.zjr.hesimusic.ui.loop.LoopViewModel.interpolatedPositionMs] 每帧写入,
 * 只供 PreviewPlayer 的 debugSnapshot(/debug/preview)读出,用来断言"等待期间指针冻结"。
 * 生产路径只有两个 volatile 写,没有订阅/分配,也不影响绘制。
 */
object LoopPlayheadProbe {

    /** 最近一帧真正画出来的播放头位置(展示口径 ms);-1 = 还没算过。 */
    @Volatile
    var lastPlayheadMs: Long = -1L

    /** 最近一帧是否因为"加载中/未播放"而冻结在真实位置。 */
    @Volatile
    var lastFrozen: Boolean = false

    fun note(playheadMs: Long, frozen: Boolean) {
        lastPlayheadMs = playheadMs
        lastFrozen = frozen
    }

    fun reset() {
        lastPlayheadMs = -1L
        lastFrozen = false
    }
}
