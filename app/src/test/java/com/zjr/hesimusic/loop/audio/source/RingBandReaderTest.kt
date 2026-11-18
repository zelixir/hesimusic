package com.zjr.hesimusic.loop.audio.source

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RingBandReaderTest {

    private val sr = 1_000
    private val ch = 1
    private val bpf = ch * 2
    private val frames = 1_000L

    private fun pcm(count: Int, startFrame: Long = 0L): ByteArray {
        val bb = ByteBuffer.allocate(count * bpf).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) {
            bb.putShort((startFrame + i).toShort())
        }
        return bb.array()
    }

    @Test
    fun `partial ring only serves filled range and never returns zeros`() {
        val ring = LoopRing(sr, ch, frames)
        ring.append(pcm(500))
        val reader = RingBandReader(ring)
        val first = reader.read(600)
        assertNotNull(first)
        assertEquals(500, first!!.frames)
        assertArrayEquals(pcm(500), first.data)
        assertNull("beyond filled must return null/CONTINUE", reader.read(100))
        assertTrue(reader.underruns >= 1L)

        ring.append(pcm(500, startFrame = 500))
        val second = reader.read(500)
        assertNotNull(second)
        assertEquals(500, second!!.frames)
        assertArrayEquals(pcm(500, startFrame = 500), second.data)
    }

    @Test
    fun `full ring wraps by frame modulo with monotonic PTS`() {
        val ring = LoopRing(sr, ch, frames)
        ring.append(pcm(1_000))
        val reader = RingBandReader(ring)
        val oneLoop = pcm(1_000)
        val expected = ByteArray(3 * oneLoop.size)
        repeat(3) { k -> System.arraycopy(oneLoop, 0, expected, k * oneLoop.size, oneLoop.size) }

        val actual = ByteArray(3_000 * bpf)
        var offset = 0
        var lastPts = -1L
        var seamCount = 0
        for (k in 0 until 6) {
            val r = reader.read(500)
            assertNotNull(r)
            val read = r!!
            if (lastPts >= 0L) assertTrue("PTS monotonic", read.ptsUs >= lastPts)
            lastPts = read.ptsUs
            System.arraycopy(read.data, 0, actual, offset, read.data.size)
            offset += read.data.size
            if (reader.lastSeamOutputFrames > 0L &&
                reader.lastSeamOutputFrames <= reader.totalReadFrames &&
                read.offsetFrames < 500
            ) {
                seamCount += 1
            }
        }
        assertArrayEquals(expected, actual)
        assertEquals(3_000L * bpf, offset.toLong())
        assertTrue("seam should be observed", seamCount >= 2)
    }

    @Test
    fun `two filled blocks are readable with correct PTS`() {
        val ring = LoopRing(sr, ch, 5_000L)
        ring.append(pcm(2_000))
        val reader = RingBandReader(ring)
        val first = reader.read(500)
        assertNotNull(first)
        assertEquals(500, first!!.frames)
        assertEquals(0L, first.ptsUs)
        assertArrayEquals(pcm(500), first.data)
        val second = reader.read(500)
        assertNotNull(second)
        assertEquals(500L, second!!.ptsUs * sr / 1_000_000L)
        assertArrayEquals(pcm(500, startFrame = 500), second.data)
    }

    @Test
    fun `five blocks read three laps byte exact`() {
        val ring = LoopRing(sr, ch, 5_000L)
        val oneLoop = pcm(5_000)
        ring.append(oneLoop)
        val reader = RingBandReader(ring)
        val expected = ByteArray(3 * oneLoop.size)
        repeat(3) { k -> System.arraycopy(oneLoop, 0, expected, k * oneLoop.size, oneLoop.size) }
        val actual = ByteArray(expected.size)
        var out = 0
        while (out < actual.size) {
            val r = reader.read(500)
            assertNotNull(r)
            System.arraycopy(r!!.data, 0, actual, out, r.data.size)
            out += r.data.size
        }
        assertArrayEquals(expected, actual)
    }

    @Test
    fun `seek near tail crosses seam byte exact`() {
        val ring = LoopRing(sr, ch, 1_000L)
        ring.append(pcm(1_000))
        val reader = RingBandReader(ring)
        reader.seekToFrame(990)
        val r = reader.read(30)
        assertNotNull(r)
        val expected = ByteArray(30 * bpf)
        System.arraycopy(pcm(10, startFrame = 990), 0, expected, 0, 10 * bpf)
        System.arraycopy(pcm(20, startFrame = 0), 0, expected, 10 * bpf, 20 * bpf)
        assertArrayEquals(expected, r!!.data)
        assertTrue(reader.lastSeamOutputFrames > 0L)
    }

    @Test
    fun `seek only changes offset`() {
        val ring = LoopRing(sr, ch, frames)
        ring.append(pcm(1_000))
        val reader = RingBandReader(ring)
        reader.read(100)
        val before = reader.totalReadFrames
        reader.seekToFrame(250)
        assertEquals(250L, reader.offsetFrames)
        val r = reader.read(10)
        assertNotNull(r)
        assertArrayEquals(pcm(10, startFrame = 250), r!!.data)
        assertTrue("seek must not re-render", reader.filledFrames == 1_000L)
        assertTrue(reader.totalReadFrames > before)
    }

    @Test
    fun `size guard switches large bands to fallback`() {
        val timeline = LoopingPcmTimeline(
            songId = 1L,
            loopStartUs = 0L,
            loopEndUs = 100_000_000L,
            format = PcmTrackFormat(48_000, 2),
        )
        val bytes = timeline.loopFrames * timeline.bytesPerFrame
        assertTrue(RingBandSource.canUse(timeline, bytes * 10L))
        assertFalse(RingBandSource.canUse(timeline, bytes * 10L - 1L))
    }
}
