package com.zjr.hesimusic.ui.loop

import com.zjr.hesimusic.ui.loop.state.PrefillState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopPagePrefillUiTest {

    @Test
    fun progressLabelGoesFromZeroToTwelveSeconds() {
        assertEquals("准备环缓冲 0s/12s", prefillProgressLabel(PrefillState.InProgress(0f, 0L, 12_000L)))
        assertEquals("准备环缓冲 4s/12s", prefillProgressLabel(PrefillState.InProgress(0.33f, 4_000L, 12_000L)))
        assertEquals("准备环缓冲 8s/12s", prefillProgressLabel(PrefillState.InProgress(0.66f, 8_000L, 12_000L)))
        assertEquals("准备环缓冲 12s/12s", prefillProgressLabel(PrefillState.InProgress(1f, 12_000L, 12_000L)))
    }

    @Test
    fun progressLabelClampsOvershootAndZeroTotal() {
        assertEquals(
            "准备环缓冲 12s/12s",
            prefillProgressLabel(PrefillState.InProgress(1f, 20_000L, 12_000L)),
        )
        assertEquals(
            "正在准备环缓冲…",
            prefillProgressLabel(PrefillState.InProgress(0f, 0L, 0L)),
        )
    }

    @Test
    fun partialAndReadyHideProgress() {
        assertNull(prefillProgressLabel(PrefillState.Partial(8_000L, 12_000L)))
        assertNull(prefillProgressLabel(PrefillState.Ready))
        assertNull(prefillProgressLabel(PrefillState.NotStarted))
        assertNull(prefillProgressLabel(PrefillState.Failed("x")))
    }

    @Test
    fun prefixReadyForPartialAndReadyOnly() {
        assertTrue(prefillPrefixReady(PrefillState.Partial(8_000L, 12_000L)))
        assertTrue(prefillPrefixReady(PrefillState.Ready))
        assertFalse(prefillPrefixReady(PrefillState.InProgress(0.5f, 6_000L, 12_000L)))
        assertFalse(prefillPrefixReady(PrefillState.NotStarted))
        assertFalse(prefillPrefixReady(PrefillState.Failed("x")))
    }
}
