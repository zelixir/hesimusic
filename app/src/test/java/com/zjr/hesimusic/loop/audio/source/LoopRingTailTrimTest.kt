package com.zjr.hesimusic.loop.audio.source

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 00024：环带覆盖位图要能表达"尾部短于预期"（covered 到 duration 为止），
 * 同时把静音补齐的帧单独记账，绝不覆盖已填的真实音频。
 */
class LoopRingTailTrimTest {

    private val sr = 1_000
    private val ch = 1
    private val frames = 1_000L

    private fun pcm(startFrame: Long, count: Int): ByteArray {
        val out = ByteArray(count * ch * 2)
        val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) {
            val value = (startFrame + i).toShort()
            repeat(ch) { bb.putShort(value) }
        }
        return out
    }

    @Test
    fun `silence pad covers the tail and marks it trimmed`() {
        val ring = LoopRing(sr, ch, frames)
        ring.append(pcm(0, 900))
        assertEquals(100, ring.fillSilenceAt(900, 100))

        assertEquals(1_000L, ring.coveredFrames)
        assertTrue(ring.isFullyCovered)
        assertEquals(100L, ring.tailTrimmedFrames)
        assertEquals(900L, ring.audioCoveredFrames)
        assertEquals(900L, ring.tailTrimmedFromOffset)
        assertTrue(ring.hasTailTrim)
        // 真实音频原样保留，尾部读出来是静音
        assertArrayEquals(pcm(0, 900), ring.copyFrames(0, 900))
        assertArrayEquals(ByteArray(100 * ring.bytesPerFrame), ring.copyFrames(900, 100))
        val reader = RingBandReader(ring)
        reader.seekToFrame(900)
        val read = reader.read(100)
        assertTrue(read != null && read.frames == 100)
    }

    @Test
    fun `silence pad never overwrites already covered audio`() {
        val ring = LoopRing(sr, ch, frames)
        val audio = pcm(500, 100)
        ring.writeFramesAt(500, audio)
        // 400..600 里 500..600 已有真实音频，只能补 400..500
        assertEquals(100, ring.fillSilenceAt(400, 200))
        assertEquals(200L, ring.coveredFrames)
        assertEquals(100L, ring.tailTrimmedFrames)
        assertEquals(100L, ring.audioCoveredFrames)
        assertArrayEquals("已覆盖的真实音频不能被静音覆盖", audio, ring.copyFrames(500, 100))
        assertArrayEquals(ByteArray(100 * ring.bytesPerFrame), ring.copyFrames(400, 100))
    }

    @Test
    fun `silence pad wakes readers waiting on the tail`() {
        val ring = LoopRing(sr, ch, frames)
        ring.append(pcm(0, 900))
        val ready = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>(null)
        val waiter = Thread {
            try {
                ready.set(ring.awaitRangeFilled(900, 100, 2_000))
            } catch (t: Throwable) {
                failure.set(t)
            }
        }
        waiter.start()
        Thread.sleep(20)
        assertFalse("补齐前必须还在等", ready.get())
        ring.fillSilenceAt(900, 100)
        waiter.join(2_000)
        assertFalse(waiter.isAlive)
        assertTrue(ready.get())
        failure.get()?.let { throw it }
    }

    @Test
    fun `silence pad stops at ring end for the caller to wrap`() {
        val ring = LoopRing(sr, ch, frames)
        assertEquals("不跨环尾，只补到环末尾", 5, ring.fillSilenceAt(995, 20))
        assertEquals(5L, ring.coveredFrames)
        assertEquals(0L, ring.contiguousFilledFrames(0, 1))
        // 跨环由调用方自己循环（renderer 的 fillSilenceTail 就这么做）
        assertEquals(15, ring.fillSilenceAt(0, 15))
        assertEquals(20L, ring.coveredFrames)
        assertEquals(20L, ring.tailTrimmedFrames)
    }

    @Test
    fun `close clears the tail trim bookkeeping`() {
        val ring = LoopRing(sr, ch, frames)
        ring.fillSilenceAt(990, 10)
        assertEquals(10L, ring.tailTrimmedFrames)
        assertTrue(ring.hasTailTrim)
        ring.close()
        assertEquals(0L, ring.tailTrimmedFrames)
        assertEquals(-1L, ring.tailTrimmedFromOffset)
        assertFalse(ring.hasTailTrim)
    }
}
