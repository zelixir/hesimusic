package com.zjr.hesimusic.loop.audio.source

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopPreviewDebugBridgeTest {

    @After
    fun tearDown() {
        LoopPreviewDebugBridge.unregister()
    }

    @Test
    fun `registered seek bridge receives display ms and records target`() {
        var applied: Long? = null
        LoopPreviewDebugBridge.register { ms -> applied = ms }
        assertTrue(LoopPreviewDebugBridge.requestSeek(30_000L))
        assertEquals(30_000L, applied)
        assertEquals(30_000L, LoopPreviewDebugBridge.lastRequestedMs)
    }

    @Test
    fun `unregistered bridge rejects seek`() {
        LoopPreviewDebugBridge.unregister()
        assertFalse(LoopPreviewDebugBridge.requestSeek(1_000L))
    }
}
