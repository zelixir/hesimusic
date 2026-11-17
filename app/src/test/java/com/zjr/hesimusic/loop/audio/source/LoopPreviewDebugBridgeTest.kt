package com.zjr.hesimusic.loop.audio.source

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopPreviewDebugBridgeTest {

    @After
    fun tearDown() {
        LoopPreviewDebugBridge.release()
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

    @Test
    fun `five engine switches leave no bridge handles`() {
        repeat(5) {
            LoopPreviewDebugBridge.register { }
            LoopPreviewDebugBridge.registerTap { }
            assertTrue(LoopPreviewDebugBridge.requestSeek(it.toLong()))
            LoopPreviewDebugBridge.release()
            assertNull(LoopPreviewDebugBridge.seekRequest)
            assertNull(LoopPreviewDebugBridge.tapSink)
            assertFalse(LoopPreviewDebugBridge.requestSeek(1L))
        }
        assertNull(LoopPreviewDebugBridge.seekRequest)
        assertNull(LoopPreviewDebugBridge.tapSink)
        assertFalse(LoopPreviewDebugBridge.requestSeek(2L))
    }
}
