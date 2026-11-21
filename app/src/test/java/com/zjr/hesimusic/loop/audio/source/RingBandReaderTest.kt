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
        assertEquals(0L, reader.underruns)
        reader.recordWait()
        assertEquals(1L, reader.underruns)

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

    /**
     * issue 00023 裁决 a:seek 时 **PTS 基准重置为 seek 目标**(允许回退)。
     *
     * 旧实现"PTS 只增不减"是区内回退/区外自由 seek 落点错的根因:seek 回退被 max() 留在读前位置。
     * 新语义:段内严格单调;跨 seek 允许回退;参与节流的 totalReadFrames 仍只增不减。
     */
    @Test
    fun `seek resets PTS base to the requested target`() {
        val ring = LoopRing(sr, ch, 1_000L)
        ring.append(pcm(1_000))
        val reader = RingBandReader(ring)
        reader.read(100)
        val before = reader.read(100)!!.ptsUs
        val totalBefore = reader.totalReadFrames
        reader.seekToOffset(900L, targetUs = 2_000_000L)
        val after = reader.read(50)!!
        assertEquals("seek 后首个样本 PTS = seek 目标", 2_000_000L, after.ptsUs)
        assertEquals("段基准同步", 2_000_000L, reader.segmentBasePtsUs)

        // 段内严格单调。
        val next = reader.read(50)!!
        assertTrue("段内单调: " + after.ptsUs + " -> " + next.ptsUs, next.ptsUs > after.ptsUs)
        // 回退 seek 允许 PTS 变小(媒体时间回退),但 totalReadFrames 不能被回退。
        // before = 第 2 次 read 的 PTS(100 帧 @1kHz = 100_000us),回退目标取 50_000us。
        reader.seekToOffset(0L, targetUs = 50_000L)
        val rewind = reader.read(10)!!
        assertEquals(50_000L, rewind.ptsUs)
        assertTrue(
            "允许回退到 seek 前更小的 PTS: before=" + before + " rewind=" + rewind.ptsUs,
            rewind.ptsUs < before,
        )
        assertTrue("totalReadFrames 必须继续单调", reader.totalReadFrames > totalBefore)
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
        // 00027:主/预览统一 heap/6;边界仍是"刚好等于预算通过,少 1 字节拒绝"。
        assertTrue(RingBandSource.canUse(timeline, bytes * 6L))
        assertFalse(RingBandSource.canUse(timeline, bytes * 6L - 1L))
    }
}
