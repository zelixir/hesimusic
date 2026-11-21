package com.zjr.hesimusic.loop.audio.source

import com.zjr.hesimusic.loop.LoopPhase
import com.zjr.hesimusic.loop.LoopWindow

/**
 * 主播放器"整曲时间轴"的位置投影（issue 00028，纯函数、可 JVM 单测）。
 *
 * 时间轴按**等长 epoch** 连续推进，T 永远单调，不需要 EOS、也不会让 PTS 倒退：
 *
 * ```
 * 每个 epoch 长度 span = TAIL_BASE + tailLen（tailLen = dur - B，无尾奏时 tailLen=0）
 *
 * epoch 内偏移 R = T mod span：
 *   [0, A)          prelude        media = R
 *   [A, B)          首轮 loop      media = R
 *   [B, TAIL_BASE)  后续 loop      media = A + floorMod(R - A, L)
 *   [TAIL_BASE, span) tail         media = B + (R - TAIL_BASE)
 *
 * tail 播到 span 边界 -> 下一个 epoch 的 R=0（prelude），T 连续、相位自动回 prelude。
 * 这样"尾奏播完回 0"与 B→A 折返一样是源内取模，不依赖 ExoPlayer 的 EOS/repeat。
 * ```
 *
 * tail 只有 seek 到 B 之后才会进入；循环区间对外仍只标 [A,B)。
 */
object LoopWholeTimeline {

    /**
     * epoch 内循环段与 tail 段的分界（µs）。正常循环播放每秒只推进 ~1s，单次会话不可能
     * 走到这里；取 1e15 µs ≈ 31.7 年，且必须是 1_000_000 的整数倍（Player 位置单位 ms）。
     */
    const val TAIL_BASE_US: Long = 1_000_000_000_000_000L

    /** 进入循环的起点：永远 0（整曲开头），不是 A，也不是当前播放位置。 */
    const val PRELUDE_START_US: Long = 0L

    /** 一个 epoch 的长度（µs）；窗口非法/时长未知时返回 0。 */
    fun epochSpanUs(fullDurationUs: Long, window: LoopWindow?): Long {
        if (window == null || !window.isValid()) return 0L
        val dur = fullDurationUs.coerceAtLeast(0L)
        if (dur <= 0L) return TAIL_BASE_US
        val tail = (dur - window.loopEndUs).coerceAtLeast(0L)
        return TAIL_BASE_US + tail
    }

    /** epoch 内偏移（µs）：T mod span。 */
    fun localTimelineUs(timelineUs: Long, fullDurationUs: Long, window: LoopWindow?): Long {
        val span = epochSpanUs(fullDurationUs, window)
        if (span <= 0L) return timelineUs.coerceAtLeast(0L)
        return Math.floorMod(timelineUs.coerceAtLeast(0L), span)
    }

    /** 当前 T 所在 epoch 的起点。 */
    fun epochStartUs(timelineUs: Long, fullDurationUs: Long, window: LoopWindow?): Long {
        val span = epochSpanUs(fullDurationUs, window)
        if (span <= 0L) return timelineUs.coerceAtLeast(0L)
        val t = timelineUs.coerceAtLeast(0L)
        return (t / span) * span
    }

    /** tail 播完后的下一个 epoch 起点（= 当前 epoch 起点 + span）。 */
    fun nextEpochStartUs(timelineUs: Long, fullDurationUs: Long, window: LoopWindow?): Long {
        val span = epochSpanUs(fullDurationUs, window)
        val t = timelineUs.coerceAtLeast(0L)
        if (span <= 0L) return t
        return epochStartUs(t, fullDurationUs, window) + span
    }

    /** 时间轴 T -> 展示位置（µs）。 */
    fun displayUs(timelineUs: Long, window: LoopWindow?, fullDurationUs: Long = 0L): Long {
        if (window == null || !window.isValid()) return timelineUs
        val local = localTimelineUs(timelineUs, fullDurationUs, window)
        val display = when {
            local < window.loopStartUs -> local
            local < TAIL_BASE_US -> {
                window.loopStartUs + Math.floorMod(local - window.loopStartUs, window.lengthUs)
            }
            else -> window.loopEndUs + (local - TAIL_BASE_US)
        }
        return clampToDuration(display, fullDurationUs)
    }

    fun displayMs(timelineMs: Long, window: LoopWindow?, fullDurationUs: Long = 0L): Long =
        displayUs(timelineMs * 1_000L, window, fullDurationUs) / 1_000L

    /**
     * 展示位置 -> 数据源时间轴 T（seek 落点）。
     *
     * - D < A：当前 epoch 的前奏；
     * - A <= D < B：首轮；若当前在 B 之后的循环轮，则保持当前轮次；
     * - D >= B：当前 epoch 的 tail 段。
     * - 整曲时长未知或没有尾奏时，D>=B 按 B 右开折回循环段。
     */
    fun timelineUsForDisplay(
        displayUs: Long,
        currentTimelineUs: Long,
        window: LoopWindow?,
        fullDurationUs: Long = 0L,
    ): Long {
        if (window == null || !window.isValid()) return displayUs.coerceAtLeast(0L)
        val duration = fullDurationUs.coerceAtLeast(0L)
        val upper = if (duration > 0L) duration else Long.MAX_VALUE
        val display = displayUs.coerceIn(0L, upper)
        val a = window.loopStartUs
        val b = window.loopEndUs
        val length = window.lengthUs
        val span = epochSpanUs(duration, window)
        val current = currentTimelineUs.coerceAtLeast(0L)
        val epochBase = if (span > 0L) (current / span) * span else 0L
        val local = if (span > 0L) current - epochBase else current

        if (display < a) return epochBase + display

        if (display < b) {
            val inFirstPassOrPrelude = local < b
            val inTail = local >= TAIL_BASE_US
            if (inFirstPassOrPrelude || inTail) return epochBase + display
            val roundBase = epochBase + b + ((local - b) / length) * length
            return roundBase + (display - a)
        }

        // D >= B：只有真的存在尾奏（曲末在 B 之后）才进入 tail；否则按 B 右开折回循环段。
        val tailExists = duration > b
        if (!tailExists) {
            val fallback = (b - 1L).coerceAtLeast(a)
            return timelineUsForDisplay(fallback, currentTimelineUs, window, duration)
        }
        return epochBase + TAIL_BASE_US + (display - b)
    }

    /** 数据源时间轴 T 的阶段（epoch 内偏移口径）。 */
    fun phaseOfTimelineUs(timelineUs: Long, window: LoopWindow?, fullDurationUs: Long = 0L): LoopPhase? {
        if (window == null || !window.isValid()) return null
        val local = localTimelineUs(timelineUs, fullDurationUs, window)
        return when {
            local < window.loopStartUs -> LoopPhase.PRELUDE
            local < TAIL_BASE_US -> LoopPhase.LOOP
            else -> LoopPhase.TAIL
        }
    }

    private fun clampToDuration(displayUs: Long, fullDurationUs: Long): Long {
        if (fullDurationUs <= 0L) return displayUs.coerceAtLeast(0L)
        return displayUs.coerceIn(0L, fullDurationUs)
    }
}
