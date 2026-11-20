package com.zjr.hesimusic.loop.audio.source

import com.zjr.hesimusic.loop.analysis.Pcm16Frames
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * 00024（真 bug 回归）：窗口 B 落在文件尾（整曲窗口 / 自由试听）时，
 * 解码器在目标位置之后没有可输出帧，`fillGap` 不能把"0 帧"直接判成致命错误。
 *
 * 三类必须分别断言：
 * 1. 目标帧数 < 一个解码粒度 → 静音补齐，播放继续；
 * 2. 已到文件尾（解码器报 EOS）→ 静音补齐并标记 tailTrimmed；
 * 3. 远离文件尾的持续 0 帧 → 仍判 DECODE_EXCEPTION。
 */
class RingBandRendererTailFillTest {

    private val sr = LoopingPcmTestSupport.SAMPLE_RATE
    private val channels = LoopingPcmTestSupport.CHANNELS
    private val bytesPerFrame = channels * 2

    private fun timeline(loopFrames: Long) = LoopingPcmTestSupport.timeline(
        aUs = 0L,
        bUs = loopFrames * 1_000_000L / sr,
        sr = sr,
        ch = channels,
    )

    private fun awaitTrue(message: String, timeoutMs: Long = 3_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(2L)
        assertTrue(message, condition())
    }

    /**
     * "文件尾"解码器：[contentEndFrame] 之后没有可输出帧。
     *
     * @param reportEos true = 像真实的 MediaCodec 会话一样报告 EOS（抽取器已无样本/输出 EOS）
     */
    private class TailDecoder(
        private val sr: Int,
        private val channels: Int,
        private val contentEndFrame: Long,
        private val reportEos: Boolean,
    ) : RingBandStreamDecoder {

        val sessionStarts = CopyOnWriteArrayList<Long>()

        @Volatile
        private var eos = false

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
            sessionStarts += startUs
            val start = Pcm16Frames.frameAtOrAfterUs(startUs, sr)
            val end = minOf(Pcm16Frames.frameAtOrAfterUs(endUs, sr), contentEndFrame)
            if (end <= start) {
                // 真机现场：seek 落点之后没有样本 → emitted=0（不是为了接近尾部才这样）。
                eos = reportEos && start >= contentEndFrame
                return
            }
            var frame = start
            while (frame < end && !cancelled) {
                val n = minOf(997L, end - frame).toInt()
                onPcm(tailPcm(frame, n, channels), n * channels * 2)
                frame += n.toLong()
            }
            eos = reportEos && end >= contentEndFrame
        }

        override fun lastStreamReachedEndOfStream(): Boolean = eos

        override fun close() = Unit
    }

    /** 永远出不了帧、也不报 EOS 的坏解码器。 */
    private class ZeroDecoder : RingBandStreamDecoder {
        val sessionCount = AtomicInteger()

        override fun stream(
            startUs: Long,
            endUs: Long,
            prerollUs: Long,
            onPcm: (data: ByteArray, size: Int) -> Unit,
        ) {
            sessionCount.incrementAndGet()
        }

        override fun lastStreamReachedEndOfStream(): Boolean = false

        override fun close() = Unit
    }

    // ① 目标帧数 < 一个解码粒度：真机那 29 帧缺口
    @Test
    fun `tail gap smaller than one decode granularity is padded with silence`() {
        val loopFrames = 10_000L
        val contentEnd = loopFrames - 29L
        val tl = timeline(loopFrames)
        val ring = LoopRing(sr, channels, tl.loopFrames)
        // 关键：这个 decoder 不报 EOS，只有"缺口 < 解码粒度"这条规则能救它。
        val decoder = TailDecoder(sr, channels, contentEnd, reportEos = false)
        val renderer = RingBandRenderer(tl, decoder, ring)
        renderer.start()
        try {
            awaitTrue("环带必须被补满（尾部静音）") { ring.isFullyCovered }
            Thread.sleep(50)
            assertNull("小缺口不能判致命错误", renderer.error)
            assertEquals(29L, renderer.tailTrimmedFrames)
            assertTrue(ring.hasTailTrim)
            assertEquals(contentEnd, ring.audioCoveredFrames)
            // 真实音频原样保留，尾部是静音
            assertArrayEquals(
                tailPcm(contentEnd - 64L, 64, channels),
                ring.copyFrames(contentEnd - 64L, 64),
            )
            assertArrayEquals(ByteArray(29 * bytesPerFrame), ring.copyFrames(contentEnd, 29))
            // 覆盖位图能表达"尾部短于预期"：读取端能一路读到窗口末尾，不会卡在缺口
            val reader = RingBandReader(ring)
            reader.seekToFrame(contentEnd)
            val read = reader.read(29)
            assertNotNull("尾部静音也必须可读（否则 Extractor 会一直等缺口）", read)
            assertEquals(29, read!!.frames)
        } finally {
            renderer.release()
        }
    }

    // ② 已到文件尾：缺口大于一个解码粒度，只有 EOS 信号能判"尾部"
    @Test
    fun `reaching end of file pads the rest of the window instead of failing`() {
        val loopFrames = 20_000L
        val contentEnd = 17_000L
        val tl = timeline(loopFrames)
        val ring = LoopRing(sr, channels, tl.loopFrames)
        val decoder = TailDecoder(sr, channels, contentEnd, reportEos = true)
        val renderer = RingBandRenderer(tl, decoder, ring)
        renderer.start()
        try {
            awaitTrue("环带必须被补满") { ring.isFullyCovered }
            Thread.sleep(50)
            assertNull("到文件尾不是错误", renderer.error)
            assertTrue("解码器必须报告 EOS", renderer.lastFillReachedEndOfStream)
            assertEquals(
                "3000 帧缺口 > 一个解码粒度，只能靠 EOS 判尾部",
                3_000L,
                renderer.tailTrimmedFrames,
            )
            assertEquals(contentEnd, ring.audioCoveredFrames)
            assertEquals(3_000L, ring.tailTrimmedFrames)
            assertEquals(contentEnd, ring.tailTrimmedFromOffset)
            assertArrayEquals(ByteArray(3_000 * bytesPerFrame), ring.copyFrames(contentEnd, 3_000))
            assertArrayEquals(
                tailPcm(contentEnd - 32L, 32, channels),
                ring.copyFrames(contentEnd - 32L, 32),
            )
            assertEquals("尾部一次会话就够，不必反复空解", 1, decoder.sessionStarts.size)
        } finally {
            renderer.release()
        }
    }

    // ③ 远离文件尾的持续 0 帧：仍然致命
    @Test
    fun `sustained zero frames away from the file tail stays fatal`() {
        val loopFrames = 20_000L
        val tl = timeline(loopFrames)
        val ring = LoopRing(sr, channels, tl.loopFrames)
        ring.append(tailPcm(0L, 5_000, channels))
        val decoder = ZeroDecoder()
        val renderer = RingBandRenderer(tl, decoder, ring)
        renderer.start()
        try {
            awaitTrue("持续 0 帧且不在文件尾必须判致命错误") { renderer.error != null }
            val error = renderer.error
            assertNotNull(error)
            assertTrue(error!!, error.contains("decoder produced no frames"))
            assertTrue(error, error.contains("attempts=3"))
            assertFalse("绝不能偷偷用静音补齐中段缺口", ring.hasTailTrim)
            assertEquals(5_000L, ring.coveredFrames)
            assertTrue("必须重试若干次而不是一次就放弃", decoder.sessionCount.get() >= 3)
        } finally {
            renderer.release()
        }
    }

    // ⑤ 整曲窗口：从 0 一路读到回绕之后都不再卡在尾部缺口上
    @Test
    fun `whole song window keeps reading across the trimmed tail and the wraparound`() {
        val loopFrames = 10_000L
        val contentEnd = loopFrames - 29L
        val tl = timeline(loopFrames)
        val ring = LoopRing(sr, channels, tl.loopFrames)
        val renderer = RingBandRenderer(tl, TailDecoder(sr, channels, contentEnd, reportEos = false), ring)
        renderer.start()
        try {
            awaitTrue("整环（含尾部静音）必须可读") { ring.isFullyCovered }
            val reader = RingBandReader(ring)
            val wanted = loopFrames + loopFrames / 2
            var guard = 0
            while (reader.totalReadFrames < wanted && guard < 100_000) {
                val read = reader.read(257)
                assertNotNull(
                    "尾部/回绕处不能返回 null（Extractor 会一直空转 → 长时间 BUFFERING）: " +
                        "offset=" + reader.offsetFrames,
                    read,
                )
                guard++
            }
            assertTrue("必须能连续读到回绕之后", reader.totalReadFrames >= wanted)
            assertEquals("第一次回绕必须发生在环尾", loopFrames, reader.lastSeamOutputFrames)
            assertNull(renderer.error)
        } finally {
            renderer.release()
        }
    }

    // ④ 部分写入 + 小于解码粒度的剩余缺口：补齐而不是无限重试
    @Test
    fun `partial fill with a sub granularity shortfall is padded instead of retried forever`() {
        val loopFrames = 10_000L
        val contentEnd = loopFrames - 100L
        val tl = timeline(loopFrames)
        val ring = LoopRing(sr, channels, tl.loopFrames)
        val decoder = TailDecoder(sr, channels, contentEnd, reportEos = false)
        val renderer = RingBandRenderer(tl, decoder, ring)
        renderer.start()
        try {
            awaitTrue("部分写入后的小缺口也必须补齐") { ring.isFullyCovered }
            Thread.sleep(50)
            assertNull(renderer.error)
            assertEquals(100L, renderer.tailTrimmedFrames)
            assertEquals(1, decoder.sessionStarts.size)
            assertArrayEquals(tailPcm(0L, 100, channels), ring.copyFrames(0, 100))
            assertArrayEquals(ByteArray(100 * bytesPerFrame), ring.copyFrames(contentEnd, 100))
        } finally {
            renderer.release()
        }
    }
}

/** 从 [startFrame] 开始的 mono/multi-channel PCM16（样本值 = 帧号），与既有测试同口径。 */
private fun tailPcm(startFrame: Long, count: Int, channels: Int): ByteArray {
    val out = ByteArray(count * channels * 2)
    val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
    for (i in 0 until count) {
        val value = (startFrame + i).toShort()
        repeat(channels) { bb.putShort(value) }
    }
    return out
}
