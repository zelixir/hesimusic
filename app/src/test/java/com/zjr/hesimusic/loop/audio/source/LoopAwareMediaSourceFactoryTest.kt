package com.zjr.hesimusic.loop.audio.source

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LoopAwareMediaSourceFactoryTest {

    @After
    fun tearDown() {
        LoopSourceMode.current = LoopSourceMode.M3_PRIME
        LoopSourceMainPlayer.setEnabledForTest(null)
    }

    @Test
    fun `loop tag is selected only when main source is enabled`() {
        val tag = LoopSourceItemTag(7L, 20_000_000L, 35_000_000L, "/tmp/a.mp3")
        assertEquals(tag, LoopAwareMediaSourceFactory.loopTagOf(tag, enabled = true))
        assertNull(LoopAwareMediaSourceFactory.loopTagOf("normal-tag", enabled = true))
        assertNull(LoopAwareMediaSourceFactory.loopTagOf(null, enabled = true))
        assertNull(LoopAwareMediaSourceFactory.loopTagOf(tag, enabled = false))
    }

    @Test
    fun `main switch and preview switch are both required by pure function`() {
        LoopSourceMode.current = LoopSourceMode.SOURCE
        LoopSourceMainPlayer.setEnabledForTest(false)
        assertEquals(false, LoopSourceMainPlayer.shouldUseSourceForMainPlayer())
        LoopSourceMainPlayer.setEnabledForTest(true)
        assertEquals(true, LoopSourceMainPlayer.shouldUseSourceForMainPlayer())
        LoopSourceMode.current = LoopSourceMode.M3_PRIME
        assertEquals(false, LoopSourceMainPlayer.shouldUseSourceForMainPlayer())
    }
}
