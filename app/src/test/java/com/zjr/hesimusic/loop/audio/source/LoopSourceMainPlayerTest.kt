package com.zjr.hesimusic.loop.audio.source

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopSourceMainPlayerTest {

    @After
    fun tearDown() {
        LoopSourceMainPlayer.setEnabledForTest(null)
        LoopSourceMainPlayer.activateMainPlayer(false)
        LoopSourceMode.current = LoopSourceMode.M3_PRIME
    }

    @Test
    fun `main switch and preview switch must both be on`() {
        LoopSourceMode.current = LoopSourceMode.SOURCE
        LoopSourceMainPlayer.setEnabledForTest(false)
        assertFalse(LoopSourceMainPlayer.shouldUseSourceForMainPlayer())

        LoopSourceMainPlayer.setEnabledForTest(true)
        assertTrue(LoopSourceMainPlayer.shouldUseSourceForMainPlayer())

        LoopSourceMode.current = LoopSourceMode.M3_PRIME
        assertFalse(LoopSourceMainPlayer.shouldUseSourceForMainPlayer())
    }

    @Test
    fun `main player route is a construction-time snapshot`() {
        LoopSourceMainPlayer.activateMainPlayer(true)
        LoopSourceMode.current = LoopSourceMode.M3_PRIME
        assertTrue("factory/coordinator must keep the snapshot", LoopSourceMainPlayer.isMainPlayerActive)
        LoopSourceMainPlayer.activateMainPlayer(false)
        assertFalse(LoopSourceMainPlayer.isMainPlayerActive)
    }
}
