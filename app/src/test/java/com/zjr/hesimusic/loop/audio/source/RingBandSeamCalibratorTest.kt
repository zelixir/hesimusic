package com.zjr.hesimusic.loop.audio.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin

class RingBandSeamCalibratorTest {

    private val ch = 2
    private val bpf = ch * 2
    private val windowFrames = 2_205 // 50ms @44.1k
    private val ringFrames = windowFrames + 1_000

    /** 确定性噪声，避免音乐周期性把互相关做出多个等价峰。 */
    private fun noise(frames: Int, seed: Int = 0x12345): ShortArray {
        var x = seed
        return ShortArray(frames) {
            x = x * 1_103_515_245 + 12_345
            (x ushr 8).toShort()
        }
    }

    private fun pcm(samples: ShortArray): ByteArray {
        val bb = ByteBuffer.allocate(samples.size * bpf).order(ByteOrder.LITTLE_ENDIAN)
        for (v in samples) repeat(ch) { bb.putShort(v) }
        return bb.array()
    }

    /**
     * 构造“环带窗口相对 prelude 窗口偏移 lag 帧”的测试对：
     * lag>=0 时 ring[lag + i] = tail[i]，重叠 = window-lag。
     */
    private fun pair(lag: Int, tail: ShortArray = noise(windowFrames)): Pair<ByteArray, ByteArray> {
        val ring = noise(ringFrames, seed = 0x54321)
        if (lag >= 0) {
            val overlap = windowFrames - lag
            System.arraycopy(tail, 0, ring, lag, overlap)
        } else {
            val overlap = windowFrames + lag
            System.arraycopy(tail, -lag, ring, 0, overlap)
        }
        return pcm(tail) to pcm(ring)
    }

    private fun calibrate(
        lag: Int,
        tail: ShortArray = noise(windowFrames),
        minOverlap: Int = 882,
        maxLag: Int = 1_000,
    ): RingBandSeamCalibrator.Result {
        val (t, r) = pair(lag, tail)
        return RingBandSeamCalibrator.calibrate(
            preludeTail = t,
            preludeTailFrames = windowFrames,
            ringPrefix = r,
            ringPrefixFrames = ringFrames,
            bytesPerFrame = bpf,
            maxLagFrames = maxLag,
            minOverlapFrames = minOverlap,
        )
    }

    @Test
    fun fiftyMsWindowCalibrates213FrameLag() {
        val r = calibrate(213)
        assertTrue(r.applied)
        assertEquals(213, r.lagFrames)
        assertEquals(2_205 - 213, r.windowFrames)
        assertEquals(1.0, r.peak, 1e-6)
    }

    @Test
    fun fiftyMsWindowCalibrates1000FrameLag() {
        val r = calibrate(1_000)
        assertTrue(r.applied)
        assertEquals(1_000, r.lagFrames)
        assertEquals(1_205, r.windowFrames)
    }

    @Test
    fun crossAReferenceCalibrates213FrameLag() {
        val half = 1_102
        val refFrames = half * 2
        val ref = noise(refFrames, seed = 0x2468)
        // ring 起点比 A 提前 213 帧：ring[0] = ref[A-213 - (A-half)] = ref[half-213]
        val ring = noise(ringFrames, seed = 0x1357)
        val overlapAtStart = half - 213
        System.arraycopy(ref, overlapAtStart, ring, 0, refFrames - overlapAtStart)
        val r = RingBandSeamCalibrator.calibrateCrossA(
            reference = pcm(ref),
            referenceFrames = refFrames,
            ringPrefix = pcm(ring),
            ringPrefixFrames = ringFrames,
            bytesPerFrame = bpf,
        )
        assertTrue(r.applied)
        assertEquals("reference", r.mode)
        assertEquals(-213, r.lagFrames)
        assertEquals(1_102 + 213, r.windowFrames)
    }

    @Test
    fun prefixOverlapFallbackCalibrates213FrameLag() {
        val lag = 213
        val tail = noise(windowFrames)
        val ring = noise(ringFrames, seed = 0x99999)
        System.arraycopy(tail, windowFrames - lag, ring, 0, lag)
        val r = RingBandSeamCalibrator.calibrate(
            preludeTail = pcm(tail),
            preludeTailFrames = windowFrames,
            ringPrefix = pcm(ring),
            ringPrefixFrames = ringFrames,
            bytesPerFrame = bpf,
        )
        assertTrue(r.applied)
        assertEquals(213, r.lagFrames)
        assertEquals("prefix", r.mode)
        assertEquals(213, r.windowFrames)
    }

    @Test
    fun periodicContentIsRejectedAsAmbiguous() {
        val sine = ShortArray(windowFrames) { i ->
            (sin(2.0 * Math.PI * 1_000.0 * i / 44_100.0) * 20_000).toInt().toShort()
        }
        val r = calibrate(213, tail = sine)
        assertFalse(r.applied)
        assertEquals("ambiguous-peak", r.reason)
    }

    @Test
    fun unrelatedContentIsRejectedByPeak() {
        val (t, _) = pair(0)
        val r = RingBandSeamCalibrator.calibrate(
            preludeTail = t,
            preludeTailFrames = windowFrames,
            ringPrefix = pcm(noise(ringFrames, seed = 0x77777)),
            ringPrefixFrames = ringFrames,
            bytesPerFrame = bpf,
        )
        assertFalse(r.applied)
        assertEquals("low-peak", r.reason)
    }

    @Test
    fun tooSmallWindowIsRejected() {
        val r = RingBandSeamCalibrator.calibrate(
            preludeTail = pcm(noise(500)),
            preludeTailFrames = 500,
            ringPrefix = pcm(noise(500)),
            ringPrefixFrames = 500,
            bytesPerFrame = bpf,
            minOverlapFrames = 882,
        )
        assertFalse(r.applied)
        assertEquals("overlap-too-small", r.reason)
    }

    @Test
    fun shiftBeyondMaxLagIsNotApplied() {
        val r = calibrate(1_200)
        assertFalse(r.applied)
    }

    @Test
    fun missingDataIsRejected() {
        val r = RingBandSeamCalibrator.calibrate(
            preludeTail = ByteArray(0),
            preludeTailFrames = 0,
            ringPrefix = ByteArray(0),
            ringPrefixFrames = 0,
            bytesPerFrame = bpf,
        )
        assertFalse(r.applied)
        assertEquals("no-data", r.reason)
    }
}
