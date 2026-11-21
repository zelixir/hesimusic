package com.zjr.hesimusic.loop.audio.source

import androidx.media3.common.DataReader
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import com.zjr.hesimusic.loop.analysis.Pcm16Frames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList

/**
 * #105 0.5s 极短窗口：环带大小、填充会话数、回绕是否重新解码。
 *
 * 结论口径（本测试锁住）：
 * - 0.5s @44.1kHz/立体声 → 22_050 帧 → **88_200 B** 环带；
 * - 整环由 **1 个解码会话** 填满（0.5s 窗口没有"逐块解码"）；
 * - 填满后 renderer DONE 退出；B→A 回绕只做 Reader 取模，**不会起新会话**；
 * - 回绕期间 extractor 每次 read 都能立即出样本（不会等解码），
 *   相邻输出最大墙钟间隔 <= 100ms（本机 JVM 上通常是 0~2ms）。
 */
class RingBandShortWindowSeamTest {

    private val sampleRate = 44_100
    private val channels = 2
    private val aUs = 1_000_000L
    private val bUs = 1_500_000L // 0.5s 窗口
    private val bytesPerFrame = channels * 2

    private fun timeline(): LoopingPcmTimeline = LoopingPcmTestSupport.timeline(
        aUs = aUs,
        bUs = bUs,
        sr = sampleRate,
        ch = channels,
    )

    private class ShortWindowDecoder(
        private val sampleRate: Int,
        private val channels: Int,
        private val chunkFrames: Int = 8_192,
    ) : RingBandStreamDecoder {

        val openedStartUs = CopyOnWriteArrayList<Long>()

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
            if (cancelled) return
            var frame = Pcm16Frames.frameAtOrAfterUs(startUs, sampleRate)
            val end = Pcm16Frames.frameAtOrAfterUs(endUs, sampleRate)
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
            }
        }

        override fun close() = Unit
    }

    private class FakeTrack : TrackOutput {
        var totalBytes = 0
        val pts = ArrayList<Long>()

        override fun format(format: androidx.media3.common.Format) = Unit

        override fun sampleData(
            input: DataReader,
            length: Int,
            allowEndOfInput: Boolean,
            sampleDataPart: Int,
        ): Int = 0

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            data.skipBytes(length)
            totalBytes += length
        }

        override fun sampleMetadata(
            timeUs: Long,
            flags: Int,
            size: Int,
            offset: Int,
            cryptoData: TrackOutput.CryptoData?,
        ) {
            pts += timeUs
        }
    }

    private class FakeOutput : ExtractorOutput {
        val track = FakeTrack()
        override fun track(id: Int, type: Int): TrackOutput = track
        override fun endTracks() = Unit
        override fun seekMap(seekMap: SeekMap) = Unit
    }

    private fun fakeInput(): ExtractorInput = Proxy.newProxyInstance(
        ExtractorInput::class.java.classLoader,
        arrayOf(ExtractorInput::class.java),
    ) { _, method, _ ->
        when (method.returnType) {
            java.lang.Boolean.TYPE -> false
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            else -> null
        }
    } as ExtractorInput

    private fun awaitCovered(ring: LoopRing, timeoutMs: Long = 3_000L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (ring.isFullyCovered) return true
            Thread.sleep(5L)
        }
        return ring.isFullyCovered
    }

    @Test
    fun `half second window is one 88KB band filled by a single decode session`() {
        val tl = timeline()
        val ring = LoopRing(tl.sampleRate, tl.channels, tl.loopFrames)
        val decoder = ShortWindowDecoder(sampleRate, channels)
        val logs = CopyOnWriteArrayList<String>()
        val renderer = RingBandRenderer(tl, decoder, ring, logger = { logs += it })
        renderer.start()
        try {
            assertTrue("环带必须填满", awaitCovered(ring))
            // 0.5s × 44100 帧/s = 22050 帧 × 4 B/帧 = 88200 B（≈86.1KiB）。
            assertEquals(22_050L, tl.loopFrames)
            assertEquals(88_200L, ring.frames * ring.bytesPerFrame)
            assertEquals("0.5s 窗口只需要一个解码会话", 1L, renderer.sessionCount)
            assertEquals(1, decoder.openedStartUs.size)
            assertNull(renderer.error)

            val deadline = System.currentTimeMillis() + 2_000L
            while (System.currentTimeMillis() < deadline &&
                logs.none { it.contains("stop reason=DONE") }
            ) {
                Thread.sleep(5L)
            }
            assertTrue("渲染线程必须 DONE 退出:\n" + logs.joinToString("\n"), logs.any {
                it.contains("stop reason=DONE")
            })
            Thread.sleep(50L)
            assertEquals("DONE 之后不得再起会话", 1L, renderer.sessionCount)
        } finally {
            renderer.release()
        }
    }

    @Test
    fun `wrapping the half second band never starts another decode`() {
        val tl = timeline()
        val ring = LoopRing(tl.sampleRate, tl.channels, tl.loopFrames)
        val decoder = ShortWindowDecoder(sampleRate, channels)
        val renderer = RingBandRenderer(tl, decoder, ring)
        renderer.start()
        try {
            assertTrue(awaitCovered(ring))
            assertEquals(1, decoder.openedStartUs.size)

            val reader = RingBandReader(ring)
            val out = FakeOutput()
            val extractor = RingBandExtractor(tl, ring, reader, renderer)
            extractor.init(out)
            val input = fakeInput()
            val position = PositionHolder()

            var lastBytes = out.track.totalBytes
            repeat(12) { i ->
                extractor.read(input, position)
                assertTrue(
                    "第 " + (i + 1) + " 次 read 没吐出样本：回绕退化成等解码了",
                    out.track.totalBytes > lastBytes,
                )
                lastBytes = out.track.totalBytes
            }
            val emittedFrames = out.track.totalBytes / bytesPerFrame
            assertTrue("至少输出 4s(=8 次回绕)，实际 " + emittedFrames + " 帧", emittedFrames >= 4L * sampleRate)
            assertTrue("回绕次数必须 >= 8，实际 " + extractor.seamCount, extractor.seamCount >= 8L)

            // 回绕全程：解码会话数不变（没有逐次/逐块解码）。
            assertEquals("回绕不得新起解码会话", 1, decoder.openedStartUs.size)
            assertEquals("回绕不得新起填充会话", 1L, renderer.sessionCount)
            assertNull(renderer.error)

            // 相邻输出最大间隔：源侧（loading thread 吐样本）不得有 > 100ms 的停顿。
            assertTrue(
                "maxEmitGapMs=" + extractor.maxEmitGapMs + " 超过 100ms",
                extractor.maxEmitGapMs <= 100L,
            )

            // PTS 段内严格单调（回绕不重置 PTS、不触发 seek）。
            assertTrue("PTS 必须严格递增", out.track.pts.size >= 2)
            for (i in 1 until out.track.pts.size) {
                assertTrue(
                    "PTS 非单调 @" + i + ": " + out.track.pts[i - 1] + " -> " + out.track.pts[i],
                    out.track.pts[i] > out.track.pts[i - 1],
                )
            }
        } finally {
            renderer.release()
        }
    }

    @Test
    fun `jump fill into the middle of the band settles without further sessions`() {
        val tl = timeline()
        val ring = LoopRing(tl.sampleRate, tl.channels, tl.loopFrames)
        val decoder = ShortWindowDecoder(sampleRate, channels)
        val renderer = RingBandRenderer(tl, decoder, ring)
        renderer.start()
        try {
            val reader = RingBandReader(ring)
            val out = FakeOutput()
            val extractor = RingBandExtractor(tl, ring, reader, renderer)
            extractor.init(out)
            val input = fakeInput()
            val position = PositionHolder()

            // seek 到窗口中间：先跳填补齐 [offset,B)，再补 [0,offset)，最多 2 个会话。
            extractor.seek(0L, aUs + 250_000L)
            assertTrue("跳填后环带必须填满", awaitCovered(ring))
            val sessionsAfterFill = renderer.sessionCount
            assertTrue(
                "跳填只需 <= 2 个会话（常态 1~2），实际 " + sessionsAfterFill,
                sessionsAfterFill in 1L..2L,
            )
            val openedAfterFill = decoder.openedStartUs.size

            repeat(8) { extractor.read(input, position) }
            assertEquals("回绕不得新起填充会话", sessionsAfterFill, renderer.sessionCount)
            assertEquals("回绕不得新起解码会话", openedAfterFill, decoder.openedStartUs.size)
            assertNull(renderer.error)
        } finally {
            renderer.release()
        }
    }
}
