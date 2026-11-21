package com.zjr.hesimusic.loop.audio.source

import com.zjr.hesimusic.loop.LoopPhase
import com.zjr.hesimusic.loop.LoopWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * issue 00028：整曲时间轴（prelude / loop / tail）的纯函数投影。
 *
 * 口径：A=10s B=30s dur=40s L=20s。
 */
class LoopWholeTimelineTest {

    private val aUs = 10_000_000L
    private val bUs = 30_000_000L
    private val durUs = 40_000_000L
    private val window = LoopWindow(7L, aUs, bUs)
    private val tailBase = LoopWholeTimeline.TAIL_BASE_US

    @Test
    fun displayPassesPreludeThrough() {
        assertEquals(0L, LoopWholeTimeline.displayUs(0L, window, durUs))
        assertEquals(5_000_000L, LoopWholeTimeline.displayUs(5_000_000L, window, durUs))
        assertEquals(aUs - 1L, LoopWholeTimeline.displayUs(aUs - 1L, window, durUs))
    }

    @Test
    fun displayLoopsOnlyFromAToB() {
        assertEquals(aUs, LoopWholeTimeline.displayUs(aUs, window, durUs))
        assertEquals(aUs + 1L, LoopWholeTimeline.displayUs(aUs + 1L, window, durUs))
        assertEquals(bUs - 1L, LoopWholeTimeline.displayUs(bUs - 1L, window, durUs))
        // B 立刻回 A（右开）。
        assertEquals(aUs, LoopWholeTimeline.displayUs(bUs, window, durUs))
        assertEquals(aUs + 2_000_000L, LoopWholeTimeline.displayUs(bUs + 2_000_000L, window, durUs))
        assertEquals(aUs + 1L, LoopWholeTimeline.displayUs(bUs + 3L * window.lengthUs + 1L, window, durUs))
    }

    @Test
    fun displayTailIsLinearThenWrapsToNextEpochPrelude() {
        assertEquals(bUs, LoopWholeTimeline.displayUs(tailBase, window, durUs))
        assertEquals(bUs + 5_000_000L, LoopWholeTimeline.displayUs(tailBase + 5_000_000L, window, durUs))
        // epoch span = TAIL_BASE + tail(10s)；到边界即下一个 epoch 的 prelude 0。
        val span = LoopWholeTimeline.epochSpanUs(durUs, window)
        assertEquals(tailBase + 10_000_000L, span)
        assertEquals(0L, LoopWholeTimeline.displayUs(span, window, durUs))
        assertEquals(5_000_000L, LoopWholeTimeline.displayUs(span + 5_000_000L, window, durUs))
        assertEquals(aUs + 2_000_000L, LoopWholeTimeline.displayUs(span + aUs + 2_000_000L, window, durUs))
    }

    @Test
    fun displayWithoutWindowPassesThrough() {
        assertEquals(12_345L, LoopWholeTimeline.displayUs(12_345L, null, durUs))
    }

    @Test
    fun seekBeforeAPrelude() {
        assertEquals(0L, LoopWholeTimeline.timelineUsForDisplay(0L, 5_000_000L, window, durUs))
        assertEquals(3_000_000L, LoopWholeTimeline.timelineUsForDisplay(3_000_000L, 20_000_000L, window, durUs))
    }

    @Test
    fun seekInsideLoopKeepsCurrentRoundOnlyAfterB() {
        // 当前在首轮/prelude：直接落到同一相位(T=D)。
        assertEquals(12_000_000L, LoopWholeTimeline.timelineUsForDisplay(12_000_000L, 5_000_000L, window, durUs))
        // 当前已过 B（第 2 轮 T=B+5s）：保持当前轮，T=B+L+(D-A)。
        val current = bUs + 5_000_000L
        assertEquals(
            bUs + 2_000_000L,
            LoopWholeTimeline.timelineUsForDisplay(12_000_000L, current, window, durUs),
        )
    }

    @Test
    fun seekAfterBEntersTailBand() {
        assertEquals(tailBase, LoopWholeTimeline.timelineUsForDisplay(bUs, 12_000_000L, window, durUs))
        assertEquals(
            tailBase + 5_000_000L,
            LoopWholeTimeline.timelineUsForDisplay(bUs + 5_000_000L, 12_000_000L, window, durUs),
        )
        // 曲末 = tail 终点 = 下一个 epoch 的 0：seek 到 duration 等价于从头（prelude）。
        val span = LoopWholeTimeline.epochSpanUs(durUs, window)
        assertEquals(
            span,
            LoopWholeTimeline.timelineUsForDisplay(durUs, 12_000_000L, window, durUs),
        )
        assertEquals(0L, LoopWholeTimeline.displayUs(span, window, durUs))
    }

    @Test
    fun seekFromTailEpochStaysInThatEpoch() {
        val span = LoopWholeTimeline.epochSpanUs(durUs, window)
        val tailT = span + tailBase + 5_000_000L // 第 1 个 epoch 的 tail B+5s
        assertEquals(span + 12_000_000L, LoopWholeTimeline.timelineUsForDisplay(12_000_000L, tailT, window, durUs))
        assertEquals(span + 3_000_000L, LoopWholeTimeline.timelineUsForDisplay(3_000_000L, tailT, window, durUs))
        // 再从第 1 个 epoch 的 tail seek 到 >B：仍落在该 epoch 的 tail。
        assertEquals(
            span + tailBase + 7_000_000L,
            LoopWholeTimeline.timelineUsForDisplay(bUs + 7_000_000L, tailT, window, durUs),
        )
    }

    @Test
    fun seekRoundTripMatchesDisplay() {
        for (display in listOf(0L, 3_000_000L, aUs, 12_000_000L, bUs - 1L, bUs, bUs + 5_000_000L)) {
            val t = LoopWholeTimeline.timelineUsForDisplay(display, 5_000_000L, window, durUs)
            assertEquals("display=$display", display, LoopWholeTimeline.displayUs(t, window, durUs))
        }
    }

    @Test
    fun phaseFlipsExactlyAtAForDisplayAndTimeline() {
        // #61：display 从 A-1ms 到 A+1ms 必须 prelude -> loop，不能停在 prelude。
        assertEquals(LoopPhase.PRELUDE, LoopPhase.ofDisplayUs(aUs - 1_000L, window))
        assertEquals(LoopPhase.LOOP, LoopPhase.ofDisplayUs(aUs, window))
        assertEquals(LoopPhase.LOOP, LoopPhase.ofDisplayUs(aUs + 1_000L, window))
        assertEquals(LoopPhase.LOOP, LoopPhase.ofDisplayUs(18_042_000L, window))
        assertEquals(
            LoopPhase.PRELUDE,
            LoopWholeTimeline.phaseOfTimelineUs(aUs - 1_000L, window, durUs),
        )
        assertEquals(
            LoopPhase.LOOP,
            LoopWholeTimeline.phaseOfTimelineUs(aUs, window, durUs),
        )
        assertEquals(
            LoopPhase.LOOP,
            LoopWholeTimeline.phaseOfTimelineUs(aUs + 1_000L, window, durUs),
        )
    }

    @Test
    fun phaseFollowsDisplayPosition() {
        assertEquals(LoopPhase.PRELUDE, LoopPhase.ofDisplayUs(0L, window))
        assertEquals(LoopPhase.PRELUDE, LoopPhase.ofDisplayUs(aUs - 1L, window))
        assertEquals(LoopPhase.LOOP, LoopPhase.ofDisplayUs(aUs, window))
        assertEquals(LoopPhase.LOOP, LoopPhase.ofDisplayUs(bUs - 1L, window))
        assertEquals(LoopPhase.TAIL, LoopPhase.ofDisplayUs(bUs, window))
        assertEquals(LoopPhase.TAIL, LoopPhase.ofDisplayUs(durUs, window))
        assertNull(LoopPhase.ofDisplayUs(0L, null))
    }
}
