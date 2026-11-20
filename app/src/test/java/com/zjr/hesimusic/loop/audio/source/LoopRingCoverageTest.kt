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

/**
 * 00022/00023：环带帧级覆盖位图（跳填）纯 JVM 测试。
 */
class LoopRingCoverageTest {

    private val sr = 1_000
    private val ch = 1
    private val frames = 1_000L

    private fun pcm(startFrame: Long, count: Int, channels: Int = ch): ByteArray {
        val bpf = channels * 2
        val out = ByteArray(count * bpf)
        val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) {
            val value = (startFrame + i).toShort()
            repeat(channels) { bb.putShort(value) }
        }
        return out
    }

    @Test
    fun `jump write makes exactly that range readable`() {
        val ring = LoopRing(sr, ch, frames)
        assertEquals(100, ring.writeFramesAt(500, pcm(500, 100)))
        assertEquals(100L, ring.contiguousFilledFrames(500, 100))
        assertEquals(0L, ring.contiguousFilledFrames(499, 1))
        assertEquals(0L, ring.contiguousFilledFrames(600, 1))
        assertEquals(100L, ring.coveredFrames)
        assertFalse(ring.isFullyCovered)

        val reader = RingBandReader(ring)
        reader.seekToFrame(500)
        val read = reader.read(200)
        assertNotNull(read)
        assertEquals(100, read!!.frames)
        assertArrayEquals(pcm(500, 100), read.data)
        assertNull("未填充区必须返回 null（Extractor->CONTINUE）", reader.read(10))
    }

    @Test
    fun `jump write across ring end wraps and stays contiguous`() {
        val ring = LoopRing(sr, ch, frames)
        val source = pcm(990, 20)
        val first = ring.writeFramesAt(990, source)
        assertEquals(10, first)
        val second = ring.writeFramesAt(0, source, first * ring.bytesPerFrame)
        assertEquals(10, second)
        assertEquals(20L, ring.contiguousFilledFrames(990, 20))
        assertEquals(20L, ring.coveredFrames)
        val reader = RingBandReader(ring)
        reader.seekToFrame(990)
        val read = reader.read(20)
        assertNotNull(read)
        assertArrayEquals(source, read!!.data)
    }

    @Test
    fun `jump write never corrupts already filled prefix`() {
        val ring = LoopRing(sr, ch, frames)
        val prefix = ByteArray(400 * ring.bytesPerFrame) { 0x11 }
        assertEquals(400, ring.append(prefix))
        assertEquals(400L, ring.coveredFrames)

        ring.writeFramesAt(800, pcm(800, 100))
        assertEquals(500L, ring.coveredFrames)
        assertArrayEquals(prefix, ring.copyFrames(0, 400))
        assertArrayEquals(pcm(800, 100), ring.copyFrames(800, 100))
        assertEquals("已填前缀必须保持连续", 400L, ring.contiguousFilledFrames(0, 400))
        assertEquals(0L, ring.contiguousFilledFrames(400, 10))
    }

    @Test
    fun `unfilled scan and gap length follow coverage`() {
        val ring = LoopRing(sr, ch, frames)
        assertEquals(0L, ring.firstUnfilledFrame())
        ring.append(pcm(0, 100))
        assertEquals(100L, ring.firstUnfilledFrame())
        assertEquals(0L, ring.distanceToFilledFrame(0))
        assertEquals(900L, ring.distanceToFilledFrame(100))

        ring.writeFramesAt(800, pcm(800, 50))
        assertEquals(300L, ring.distanceToFilledFrame(500))
        assertEquals(50L, ring.contiguousFilledFrames(800, 200))
        assertEquals(700L, ring.distanceToFilledFrame(100))
        assertEquals(0L, ring.distanceToFilledFrame(800))
        assertEquals(150L, ring.distanceToFilledFrame(850))
    }

    @Test
    fun `await range filled wakes on jump write`() {
        val ring = LoopRing(sr, ch, frames)
        val worker = Thread {
            Thread.sleep(30)
            ring.writeFramesAt(900, pcm(900, 50))
        }
        worker.start()
        assertTrue(ring.awaitRangeFilled(900, 50, 2_000))
        worker.join()
    }

    @Test
    fun `sequential append keeps old semantics`() {
        val ring = LoopRing(sr, ch, frames)
        assertEquals(600, ring.append(pcm(0, 600)))
        assertEquals(400, ring.append(pcm(600, 400), 0))
        assertEquals(0, ring.append(pcm(0, 10)))
        assertEquals(1_000L, ring.coveredFrames)
        assertTrue(ring.isFullyCovered)
        assertArrayEquals(pcm(0, 1_000), ring.copyFrames(0, 1_000))
    }
}
