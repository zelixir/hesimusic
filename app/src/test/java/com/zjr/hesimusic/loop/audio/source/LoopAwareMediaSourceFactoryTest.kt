package com.zjr.hesimusic.loop.audio.source

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LoopAwareMediaSourceFactoryTest {

    @After
    fun tearDown() {
        LoopSourceMode.current = LoopSourceMode.M3_PRIME
    }

    @Test
    fun `loop tag is selected only in source mode`() {
        val tag = LoopSourceItemTag(7L, 20_000_000L, 35_000_000L, "/tmp/a.mp3")
        LoopSourceMode.current = LoopSourceMode.SOURCE
        assertEquals(tag, LoopAwareMediaSourceFactory.loopTagOf(tag))
        assertNull(LoopAwareMediaSourceFactory.loopTagOf("normal-tag"))
        assertNull(LoopAwareMediaSourceFactory.loopTagOf(null))
    }

    @Test
    fun `m3 mode never selects source tag`() {
        LoopSourceMode.current = LoopSourceMode.M3_PRIME
        val tag = LoopSourceItemTag(7L, 0L, 10L, "/tmp/a.mp3")
        assertNull(LoopAwareMediaSourceFactory.loopTagOf(tag))
    }
}
