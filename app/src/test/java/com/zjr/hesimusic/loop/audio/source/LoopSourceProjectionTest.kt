package com.zjr.hesimusic.loop.audio.source

import com.zjr.hesimusic.loop.LoopWindow
import org.junit.Assert.assertEquals
import org.junit.Test

class LoopSourceProjectionTest {

    private val aUs = 10_000_000L
    private val bUs = 50_000_000L
    private val lUs = bUs - aUs
    private val window = LoopWindow(songId = 7L, loopStartUs = aUs, loopEndUs = bUs)

    @Test
    fun `display wraps inside AB`() {
        assertEquals(aUs, LoopSourceProjection.displayUs(0L, window))
        assertEquals(aUs + 5_000_000L, LoopSourceProjection.displayUs(5_000_000L, window))
        assertEquals(aUs, LoopSourceProjection.displayUs(lUs, window))
        assertEquals(aUs + 7_000_000L, LoopSourceProjection.displayUs(lUs + 7_000_000L, window))
        assertEquals(15_000L, LoopSourceProjection.displayMs(5_000L, window))
    }

    @Test
    fun `seek maps display to the current loop round`() {
        assertEquals(5_000_000L, LoopSourceProjection.timelineUsForDisplay(15_000_000L, 5_000_000L, window))
        // 向后拖：当前在 25s，目标 12s -> 同轮 2s（不是下一轮 42s）。
        assertEquals(2_000_000L, LoopSourceProjection.timelineUsForDisplay(12_000_000L, 25_000_000L, window))
        // 跨轮后仍在当前轮内。
        assertEquals(lUs + 2_000_000L, LoopSourceProjection.timelineUsForDisplay(12_000_000L, lUs + 25_000_000L, window))
    }

    @Test
    fun `display outside AB is clamped into the loop`() {
        assertEquals(0L, LoopSourceProjection.timelineUsForDisplay(0L, 5_000_000L, window))
        assertEquals(lUs - 1L, LoopSourceProjection.timelineUsForDisplay(bUs + 95_000_000L, 5_000_000L, window))
    }

    @Test
    fun `initial timeline maps mode start to A origin`() {
        assertEquals(0L, LoopSourceProjection.initialTimelineUs(aUs, window))
        assertEquals(35_000_000L, LoopSourceProjection.initialTimelineUs(bUs - 5_000_000L, window))
    }

    @Test
    fun `display to timeline and back is stable inside AB`() {
        for (displayUs in listOf(aUs, aUs + 1L, 15_000_000L, 35_000_000L, bUs - 1L)) {
            val timelineUs = LoopSourceProjection.timelineUsForDisplay(displayUs, 0L, window)
            assertEquals(displayUs, LoopSourceProjection.displayUs(timelineUs, window))
        }
    }

    @Test
    fun `invalid or null window passes through`() {
        assertEquals(123L, LoopSourceProjection.displayUs(123L, null))
        assertEquals(123L, LoopSourceProjection.timelineUsForDisplay(123L, 0L, null))
    }
}
