package com.zjr.hesimusic.loop.audio.source

import com.zjr.hesimusic.loop.audio.PcmTestSupport
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class RingBandRendererStreamingTest {

    private class FakeDecoder(private val pcm: ByteArray) : RingBandStreamDecoder {
        val calls = AtomicInteger()
        override fun stream(
            startUs: Long,
            endUs: Long,
            prerollUs: Long,
            onPcm: (data: ByteArray, size: Int) -> Unit,
        ) {
            calls.incrementAndGet()
            var offset = 0
            while (offset < pcm.size) {
                val size = minOf(100 * 2, pcm.size - offset)
                onPcm(pcm.copyOfRange(offset, offset + size), size)
                offset += size
            }
        }

        override fun close() = Unit
    }

    @Test
    fun `single streaming session fills ring and three laps are byte exact`() {
        val tl = LoopingPcmTestSupport.timeline(aUs = 0L, bUs = 1_000_000L, sr = 1_000)
        val ring = LoopRing(tl.sampleRate, tl.channels, tl.loopFrames)
        val oneLoop = PcmTestSupport.bytesOf(PcmTestSupport.pcm16Buffer(0, 1_000))
        val decoder = FakeDecoder(oneLoop)
        val renderer = RingBandRenderer(tl, decoder, ring)
        renderer.start()
        assertTrue("ring fill timeout", ring.awaitRenderedAtLeast(tl.loopFrames, 2_000L))
        assertEquals(1, decoder.calls.get())

        val reader = RingBandReader(ring)
        val expected = ByteArray(3 * oneLoop.size)
        repeat(3) { k -> System.arraycopy(oneLoop, 0, expected, k * oneLoop.size, oneLoop.size) }
        val actual = ByteArray(expected.size)
        var out = 0
        while (out < actual.size) {
            val r = reader.read(500)
            requireNotNull(r)
            System.arraycopy(r.data, 0, actual, out, r.data.size)
            out += r.data.size
        }
        assertArrayEquals(expected, actual)
        renderer.release()
    }
}
