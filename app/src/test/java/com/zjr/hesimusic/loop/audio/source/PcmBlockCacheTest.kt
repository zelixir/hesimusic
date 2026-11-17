package com.zjr.hesimusic.loop.audio.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmBlockCacheTest {

    private val key = PcmBlockCacheKey(
        songId = 7L,
        loopStartSamples = 0L,
        loopEndSamples = 30_000L,
        sampleRate = 1_000,
        channels = 1,
        loopStartUs = 0L,
        loopEndUs = 30_000_000L,
    )

    private fun cache(maxBytes: Long = 8_000L): PcmBlockCache =
        PcmBlockCache(key, framesPerBlock = 1_000, bytesPerFrame = 2, maxBytes = maxBytes)

    private fun block(index: Int, frames: Int = 1_000): PcmBlock =
        PcmBlock(
            index = index,
            startFrame = index.toLong() * 1_000L,
            frames = frames,
            data = ByteArray(frames * 2) { (index + it).toByte() },
        )

    @Test
    fun `pending then ready is visible`() {
        val c = cache()
        assertNull(c.state(0))
        assertTrue(c.markPending(0))
        assertEquals(PcmBlockCache.State.PENDING, c.state(0))
        assertNull(c.get(0))
        c.put(block(0))
        assertEquals(PcmBlockCache.State.READY, c.state(0))
        assertEquals(2_000, c.get(0)?.data?.size)
    }

    @Test
    fun `duplicate markPending is rejected`() {
        val c = cache()
        assertTrue(c.markPending(0))
        assertTrue(!c.markPending(0))
        c.put(block(0))
        assertTrue(!c.markPending(0))
    }

    @Test
    fun `failed can be retried explicitly`() {
        val c = cache()
        c.markPending(1)
        c.markFailed(1)
        assertEquals(PcmBlockCache.State.FAILED, c.state(1))
        assertEquals(1, c.failedBlockCount())
        c.retryFailed(1)
        assertNull(c.state(1))
    }

    @Test
    fun `eviction drops farthest blocks and keeps playhead`() {
        val c = cache(maxBytes = 3L * 2_000L)
        for (i in 0..4) c.put(block(i))
        c.trimToBudget(playhead = 2)
        assertTrue("active=" + c.activeBlockCount(), c.activeBlockCount() <= 3)
        assertEquals(PcmBlockCache.State.READY, c.state(2))
        assertTrue(c.readyIndices().contains(2))
        assertEquals(3L * 2_000L, c.cachedBytes())
    }

    @Test
    fun `single block larger than budget is still kept`() {
        val c = cache(maxBytes = 1L)
        c.trimToBudget(playhead = 0)
        c.put(block(0))
        assertEquals(2_000L, c.budgetBytes)
        assertEquals(1, c.activeBlockCount())
        assertEquals(PcmBlockCache.State.READY, c.state(0))
    }

    @Test
    fun `partial last block is accepted`() {
        val c = cache()
        c.put(block(5, frames = 30))
        assertEquals(PcmBlockCache.State.READY, c.state(5))
        assertEquals(60, c.get(5)?.data?.size)
    }

    @Test
    fun `clear removes all state`() {
        val c = cache()
        c.put(block(0))
        c.markPending(1)
        c.clear()
        assertEquals(0, c.activeBlockCount())
        assertNull(c.state(0))
        assertNull(c.state(1))
    }
}
