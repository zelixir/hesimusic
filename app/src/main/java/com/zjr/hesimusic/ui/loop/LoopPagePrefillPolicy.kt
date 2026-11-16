package com.zjr.hesimusic.ui.loop

import com.zjr.hesimusic.loop.LoopWindow
import com.zjr.hesimusic.loop.analysis.RingChunkedSeeder

/**
 * 预览页预填策略(纯函数,便于单测):
 * - 窗口 <=30s:整窗填满(与旧行为一致);
 * - 窗口 >30s:只填 [A, A+12s)(6 片),前缀就绪即可起播,不再等整环。
 *
 * 与 service/LoopRingPrefiller 的 maxPrefixMs=12000 对齐;页面只负责预览侧。
 */
internal object LoopPagePrefillPolicy {

    const val LONG_WINDOW_MS = 30_000L
    const val PREFIX_MAX_MS = 12_000L

    /** 实际要解码/seed 的窗口。 */
    fun prefillWindow(window: LoopWindow): LoopWindow {
        val lengthMs = window.lengthUs / 1000L
        if (lengthMs <= LONG_WINDOW_MS) return window
        val endUs = minOf(window.loopEndUs, window.loopStartUs + PREFIX_MAX_MS * 1000L)
        return LoopWindow(window.songId, window.loopStartUs, endUs)
    }

    /** 目标窗口按片长可切出的片数(向上取整);269s 窗口 -> 6,30s 窗口 -> 15。 */
    fun chunkCount(
        window: LoopWindow,
        chunkMs: Int = RingChunkedSeeder.DEFAULT_CHUNK_MS,
    ): Int {
        val target = prefillWindow(window)
        val lengthMs = target.lengthUs / 1000L
        val chunk = chunkMs.coerceAtLeast(1).toLong()
        return ((lengthMs + chunk - 1L) / chunk).toInt()
    }
}
