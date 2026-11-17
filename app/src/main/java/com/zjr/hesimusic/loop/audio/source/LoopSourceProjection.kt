package com.zjr.hesimusic.loop.audio.source

import com.zjr.hesimusic.loop.LoopWindow

/**
 * 循环数据源的对外位置投影（纯函数，可 JVM 单测）。
 *
 * 数据源时间轴 T 从 0 单调递增；T=0 对应当前窗口的 A（origin=A）。
 * 对外展示位置永远折返到 [A,B)：
 *
 * ```
 * displayUs(T) = A + floorMod(T, L)
 * ```
 */
object LoopSourceProjection {

    const val US_PER_MS: Long = 1_000L

    fun displayUs(timelineUs: Long, window: LoopWindow?): Long {
        if (window == null || !window.isValid()) return timelineUs
        return window.loopStartUs + Math.floorMod(timelineUs, window.lengthUs)
    }

    fun displayMs(timelineMs: Long, window: LoopWindow?): Long =
        displayUs(timelineMs * US_PER_MS, window) / US_PER_MS

    /**
     * 展示位置 → 数据源时间轴上的 seek 目标。
     *
     * 保持当前所在轮次：T' = roundBase(currentT) + (display - A)。这样向后拖不会
     * 被“模”到几乎整圈以后；超出当前轮时由数据源内部在 B 处继续折返。
     *
     * @param displayUs 目标展示位置（钳到 [A,B)）
     * @param currentTimelineUs 当前数据源时间轴位置（µs）
     */
    fun timelineUsForDisplay(
        displayUs: Long,
        currentTimelineUs: Long,
        window: LoopWindow?,
    ): Long {
        if (window == null || !window.isValid()) return displayUs.coerceAtLeast(0L)
        val a = window.loopStartUs
        val l = window.lengthUs
        val clamped = displayUs.coerceIn(a, (window.loopEndUs - 1L).coerceAtLeast(a))
        val current = currentTimelineUs.coerceAtLeast(0L)
        val roundBase = current - Math.floorMod(current, l)
        val target = roundBase + (clamped - a)
        return target.coerceAtLeast(0L)
    }

    fun initialTimelineUs(modeStartUs: Long, window: LoopWindow?): Long {
        if (window == null || !window.isValid()) return modeStartUs.coerceAtLeast(0L)
        return (modeStartUs - window.loopStartUs).coerceAtLeast(0L)
    }
}
