package com.zjr.hesimusic.ui.loop

import com.zjr.hesimusic.loop.LoopWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopPagePrefillPolicyTest {

    private fun window(lengthMs: Long, startMs: Long = 30_000L): LoopWindow =
        LoopWindow(7L, startMs * 1000L, (startMs + lengthMs) * 1000L)

    @Test
    fun unpreparedPlayerDefersPrefillUntilPlaybackStarts() {
        assertTrue("未 prepare 必须先起播", LoopPagePrefillPolicy.shouldDeferStart(isPlayerPrepared = false))
        assertFalse("已 prepare 不应推迟", LoopPagePrefillPolicy.shouldDeferStart(isPlayerPrepared = true))
    }

    @Test
    fun longWindowOnlyFillsTwelveSecondPrefix() {
        val w = window(269_000L)
        val target = LoopPagePrefillPolicy.prefillWindow(w)
        assertEquals(30_000_000L, target.loopStartUs)
        assertEquals(42_000_000L, target.loopEndUs)
        assertEquals(12_000L, target.lengthUs / 1000L)
        assertEquals("269s 窗口只解 6 片", 6, LoopPagePrefillPolicy.chunkCount(w))
    }

    @Test
    fun thirtySecondWindowStillFillsWholeWindow() {
        val w = window(30_000L)
        val target = LoopPagePrefillPolicy.prefillWindow(w)
        assertEquals(w, target)
        assertEquals("30s 窗口 15 片", 15, LoopPagePrefillPolicy.chunkCount(w))
    }

    @Test
    fun shortWindowFillsWholeWindow() {
        val w = window(20_000L)
        assertEquals(w, LoopPagePrefillPolicy.prefillWindow(w))
        assertEquals(10, LoopPagePrefillPolicy.chunkCount(w))
    }

    @Test
    fun justOverThirtySecondsStillPrefixesTwelveSeconds() {
        val w = window(31_000L)
        val target = LoopPagePrefillPolicy.prefillWindow(w)
        assertEquals(12_000L, target.lengthUs / 1000L)
        assertEquals(6, LoopPagePrefillPolicy.chunkCount(w))
    }
}
