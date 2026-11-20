package com.zjr.hesimusic.loop.audio.source

import com.zjr.hesimusic.loop.analysis.Pcm16Frames
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 00022/00023：环带跳填调度（seek 目标优先，已填区不被破坏）。
 */
class RingBandRendererJumpFillTest {

    private val sr = 1_000
    private val channels = 1
    private val totalFrames = 20_000L
    private val timeline = LoopingPcmTestSupport.timeline(
        aUs = 0L,
        bUs = totalFrames * 1_000_000L / sr,
        sr = sr,
        ch = channels,
    )

    private fun pcm(startFrame: Long, count: Int): ByteArray {
        val out = ByteArray(count * channels * 2)
        val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) {
            val value = (startFrame + i).toShort()
            repeat(channels) { bb.putShort(value) }
        }
        return out
    }

    private class SyntheticDecoder(
        private val sr: Int,
        private val channels: Int,
        private val chunkFrames: Int = 500,
        private val delayMs: Long = 0L,
    ) : RingBandStreamDecoder {

        val openedStartUs = CopyOnWriteArrayList<Long>()
        var onOpen: ((Long) -> Unit)? = null

        @Volatile
        private var cancelled = false

        override fun cancel() {
            cancelled = true
        }

        override fun stream(
            startUs: Long,
            endUs: Long,
            prerollUs: Long,
            onPcm: (data: ByteArray, size: Int) -> Unit,
        ) {
            cancelled = false
            openedStartUs += startUs
            onOpen?.invoke(startUs)
            if (cancelled) return
            var frame = Pcm16Frames.frameAtOrAfterUs(startUs, sr)
            val end = Pcm16Frames.frameAtOrAfterUs(endUs, sr)
            while (frame < end && !cancelled) {
                val n = minOf(chunkFrames.toLong(), end - frame).toInt()
                val out = ByteArray(n * channels * 2)
                val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until n) {
                    val value = (frame + i).toShort()
                    repeat(channels) { bb.putShort(value) }
                }
                onPcm(out, out.size)
                frame += n
                if (delayMs > 0L) Thread.sleep(delayMs)
            }
        }

        override fun close() = Unit
    }

    @Test
    fun `seek target is decoded first instead of waiting for the sequential prefix`() {
        val target = 15_000L
        val decoder = SyntheticDecoder(sr, channels)
        val ring = LoopRing(sr, channels, totalFrames)
        val renderer = RingBandRenderer(timeline, decoder, ring)
        decoder.onOpen = { startUs ->
            if (decoder.openedStartUs.size == 1) {
                assertEquals("首会话从 A 起（随后被打断）", 0L, startUs)
                renderer.requestFillAt(target)
            }
        }
        renderer.start()
        try {
            assertTrue(
                "跳填目标必须在 2s 内可读",
                ring.awaitRangeFilled(target, 500, 2_000),
            )
            assertEquals(target, renderer.lastJumpOffsetFrames)
            assertTrue("跳填后必须重新从目标开始解码", decoder.openedStartUs.size >= 2)
            assertEquals(
                "第二个解码会话必须直接从目标 offset 起",
                target * 1_000_000L / sr,
                decoder.openedStartUs[1],
            )
            val reader = RingBandReader(ring)
            reader.seekToFrame(target)
            val read = reader.read(500)
            assertNotNull(read)
            assertEquals(target, read!!.offsetFrames)
            assertArrayEquals(pcm(target, 500), read.data)

            assertTrue("其余缺口最终也要补齐", ring.awaitRangeFilled(0, totalFrames, 5_000))
            assertTrue(ring.isFullyCovered)
            assertArrayEquals(pcm(0, 1_000), ring.copyFrames(0, 1_000))
            assertEquals(null, renderer.error)
        } finally {
            renderer.release()
        }
    }

    @Test
    fun `jump fill preserves an already filled prefix`() {
        val ring = LoopRing(sr, channels, totalFrames)
        val prefix = ByteArray(5_000 * channels * 2) { 0x11 }
        ring.append(prefix)
        val decoder = SyntheticDecoder(sr, channels)
        val renderer = RingBandRenderer(timeline, decoder, ring)
        decoder.onOpen = { startUs ->
            if (decoder.openedStartUs.size == 1) {
                assertTrue("首个缺口在已填前缀之后", startUs >= 5_000L)
                renderer.requestFillAt(15_000L)
            }
        }
        renderer.start()
        try {
            assertTrue(ring.awaitRangeFilled(15_000L, 1_000, 2_000))
            assertTrue(ring.awaitRangeFilled(0, totalFrames, 5_000))
            assertArrayEquals("已填前缀不得被跳填破坏", prefix, ring.copyFrames(0, 5_000))
            assertArrayEquals(pcm(7_000, 100), ring.copyFrames(7_000, 100))
        } finally {
            renderer.release()
        }
    }

    @Test
    fun `rapid seek churn settles on the latest target and still fills everything`() {
        val decoder = SyntheticDecoder(sr, channels, chunkFrames = 500, delayMs = 1L)
        val ring = LoopRing(sr, channels, totalFrames)
        val renderer = RingBandRenderer(timeline, decoder, ring)
        renderer.start()
        try {
            val targets = listOf(12_000L, 16_000L, 3_000L, 18_000L, 9_000L)
            for (target in targets) {
                renderer.requestFillAt(target)
                Thread.sleep(5)
            }
            assertTrue(
                "最后一次 seek 目标必须优先可读",
                ring.awaitRangeFilled(targets.last(), 1_000, 3_000),
            )
            assertTrue(ring.awaitRangeFilled(0, totalFrames, 8_000))
            assertTrue(ring.isFullyCovered)
            assertEquals(targets.last(), renderer.lastJumpOffsetFrames)
            assertTrue("连续 seek 不能堆积/退化", renderer.error == null)
            assertArrayEquals(pcm(9_000, 500), ring.copyFrames(9_000, 500))
        } finally {
            renderer.release()
        }
    }
}
