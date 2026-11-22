package com.zjr.hesimusic.loop.audio.source

import com.zjr.hesimusic.loop.analysis.Pcm16Frames
import com.zjr.hesimusic.loop.analysis.Pcm16Segment
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** issue 00028：前奏/尾奏按需块读取器（纯 JVM）。 */
class PcmRangeBlockReaderTest {

    private val sr = 1_000
    private val ch = 1

    /** 每个样本值 = 绝对媒体帧号；用于断言"听到的正是请求位置"。 */
    private class AbsoluteProvider(
        private val sr: Int,
        private val ch: Int,
    ) : PcmRangeProvider {
        val calls = ArrayList<Pair<Long, Long>>()
        override fun decode(startUs: Long, endUs: Long): Pcm16Segment {
            calls += startUs to endUs
            val startFrame = Pcm16Frames.frameAtOrAfterUs(startUs, sr)
            val frames = Pcm16Frames.framesForUsCeil(endUs - startUs, sr)
            val data = ByteArray((frames * ch * 2).toInt())
            var offset = 0
            for (i in 0 until frames.toInt()) {
                val value = (startFrame + i).toShort()
                repeat(ch) {
                    data[offset++] = (value.toInt() and 0xff).toByte()
                    data[offset++] = ((value.toInt() shr 8) and 0xff).toByte()
                }
            }
            return Pcm16Segment(data, sr, ch, frames)
        }
    }

    private fun readExactly(reader: PcmRangeBlockReader, frames: Int): ByteArray {
        val out = ByteArray(frames * ch * 2)
        var copied = 0
        var guard = 0
        while (copied < frames) {
            val r = reader.read(frames - copied)
            if (r == null) {
                reader.kick()
                assertTrue(guard++ < 200 && reader.awaitCurrentReady(200L))
                continue
            }
            System.arraycopy(r.data, 0, out, copied * ch * 2, r.data.size)
            copied += r.frames
        }
        return out
    }

    private fun sampleAt(data: ByteArray, frame: Int): Int {
        val offset = frame * ch * 2
        return (data[offset].toInt() and 0xff) or ((data[offset + 1].toInt() and 0xff) shl 8)
    }

    @Test
    fun readsSequentiallyAcrossBlockBoundaries() {
        val provider = AbsoluteProvider(sr, ch)
        val reader = PcmRangeBlockReader(provider, 1_000L, 5_000L, sr, ch)
        val data = readExactly(reader, 4_000)
        for (i in 0 until 4_000) {
            assertEquals("frame $i", 1_000 + i, sampleAt(data, i))
        }
        assertEquals(5_000L, reader.currentFrame())
        reader.release()
    }

    @Test
    fun seekReadsFromRequestedAbsoluteFrame() {
        val provider = AbsoluteProvider(sr, ch)
        val reader = PcmRangeBlockReader(provider, 1_000L, 5_000L, sr, ch)
        reader.seekToFrame(3_500L)
        val data = readExactly(reader, 300)
        for (i in 0 until 300) {
            assertEquals(3_500 + i, sampleAt(data, i))
        }
        reader.release()
    }

    @Test
    fun copyCachedRangeDoesNotAdvanceCursor() {
        val provider = AbsoluteProvider(sr, ch)
        val reader = PcmRangeBlockReader(provider, 1_000L, 5_000L, sr, ch)
        reader.seekToFrame(3_000L)
        readExactly(reader, 100)
        val cursor = reader.currentFrame()
        val copy = reader.copyCachedRange(3_000L, 100, 500L)
        assertTrue(copy != null)
        for (i in 0 until 100) {
            assertEquals(3_000 + i, sampleAt(copy!!, i))
        }
        assertEquals(cursor, reader.currentFrame())
        reader.release()
    }

    @Test
    fun seekClampsToRangeAndEmptyRangeIsNoOp() {
        val provider = AbsoluteProvider(sr, ch)
        val reader = PcmRangeBlockReader(provider, 1_000L, 5_000L, sr, ch)
        reader.seekToFrame(0L)
        assertEquals(1_000L, reader.currentFrame())
        reader.seekToFrame(9_999L)
        assertEquals(4_999L, reader.currentFrame())
        reader.release()

        val empty = PcmRangeBlockReader(provider, 1_000L, 1_000L, sr, ch)
        assertEquals(0L, empty.totalFrames())
        assertTrue(empty.read(10) == null)
        empty.release()
    }

    /** 容器时长比最后一个编码样本晚：只产出到 [realEndUs]，后面应为静音补齐。 */
    private class ShortTailProvider(
        private val sr: Int,
        private val ch: Int,
        private val realEndUs: Long,
    ) : PcmRangeProvider {
        override fun decode(startUs: Long, endUs: Long): Pcm16Segment {
            val effectiveEnd = minOf(endUs, realEndUs)
            val startFrame = Pcm16Frames.frameAtOrAfterUs(startUs, sr)
            val frames = Pcm16Frames.framesForUsCeil((effectiveEnd - startUs).coerceAtLeast(0L), sr)
            val data = ByteArray((frames * ch * 2).toInt())
            var offset = 0
            for (i in 0 until frames.toInt()) {
                val value = (startFrame + i).toShort()
                repeat(ch) {
                    data[offset++] = (value.toInt() and 0xff).toByte()
                    data[offset++] = ((value.toInt() shr 8) and 0xff).toByte()
                }
            }
            return Pcm16Segment(data, sr, ch, frames)
        }
    }

    @Test
    fun lastBlockShorterThanContainerIsPaddedWithSilenceInsteadOfRetrying() {
        // 期望 4s，实际只到 3.9s：最后 100ms 必须是静音，而不是失败/无限重试。
        val provider = ShortTailProvider(sr, ch, realEndUs = 3_900_000L)
        val reader = PcmRangeBlockReader(provider, 0L, 4_000L, sr, ch)
        val data = readExactly(reader, 4_000)
        assertEquals(3_899, sampleAt(data, 3_899))
        for (i in 3_900 until 4_000) {
            assertEquals("padded frame $i", 0, sampleAt(data, i))
        }
        assertEquals(4_000L, reader.currentFrame())
        reader.release()
    }

    /**
     * 真实 PcmDecoder 的行为：请求越过最后一个编码样本时**抛异常**，而不是返回短段。
     * 最后一块必须逐步缩短请求拿到真实结尾音频，剩余不足 1s 的部分静音补齐。
     */
    private class ThrowingPastEndProvider(
        private val sr: Int,
        private val ch: Int,
        private val realEndUs: Long,
    ) : PcmRangeProvider {
        override fun decode(startUs: Long, endUs: Long): Pcm16Segment {
            if (endUs > realEndUs) {
                throw IllegalStateException(
                    "环缓冲解码不足: 需要 " + (endUs - startUs) + "us",
                )
            }
            val startFrame = Pcm16Frames.frameAtOrAfterUs(startUs, sr)
            val frames = Pcm16Frames.framesForUsCeil(endUs - startUs, sr)
            val data = ByteArray((frames * ch * 2).toInt())
            var offset = 0
            for (i in 0 until frames.toInt()) {
                val value = (startFrame + i).toShort()
                repeat(ch) {
                    data[offset++] = (value.toInt() and 0xff).toByte()
                    data[offset++] = ((value.toInt() shr 8) and 0xff).toByte()
                }
            }
            return Pcm16Segment(data, sr, ch, frames)
        }
    }

    @Test
    fun lastBlockPastRealEndIsTrimmedThenPaddedInsteadOfFailed() {
        // 期望 4s；真实音频只到 3.9s；最后块 [3.0,4.0) 首轮请求 4.0s -> 抛异常。
        val provider = ThrowingPastEndProvider(sr, ch, realEndUs = 3_900_000L)
        val reader = PcmRangeBlockReader(provider, 0L, 4_000L, sr, ch)
        val data = readExactly(reader, 4_000)
        assertEquals(3_899, sampleAt(data, 3_899))
        for (i in 3_900 until 4_000) {
            assertEquals("padded frame $i", 0, sampleAt(data, i))
        }
        assertEquals(4_000L, reader.currentFrame())
        assertTrue("must not stay failed", reader.error() == null)
        reader.release()
    }

    @Test
    fun lastBlockEntirelyPastRealEndBecomesSilenceWithoutHanging() {
        val provider = ThrowingPastEndProvider(sr, ch, realEndUs = 3_000_000L)
        val reader = PcmRangeBlockReader(provider, 0L, 4_000L, sr, ch)
        val data = readExactly(reader, 4_000)
        assertEquals(2_999, sampleAt(data, 2_999))
        for (i in 3_000 until 4_000) {
            assertEquals("padded frame $i", 0, sampleAt(data, i))
        }
        assertTrue(reader.error() == null)
        reader.release()
    }

    /** 模拟 PcmDecoderRangeProvider 的 allowShort 能力；统计每类解码调用次数。 */
    private class CountingShortProvider(
        private val sr: Int,
        private val ch: Int,
        private val realEndUs: Long,
    ) : PcmShortRangeProvider {
        var strictCalls = 0
        var shortCalls = 0

        override fun decode(startUs: Long, endUs: Long): Pcm16Segment {
            strictCalls += 1
            if (endUs > realEndUs) throw IllegalStateException("short")
            return slice(startUs, minOf(endUs, realEndUs))
        }

        override fun decodeAllowShort(startUs: Long, endUs: Long): Pcm16Segment {
            shortCalls += 1
            return slice(startUs, minOf(endUs, realEndUs))
        }

        private fun slice(startUs: Long, endUs: Long): Pcm16Segment {
            val startFrame = Pcm16Frames.frameAtOrAfterUs(startUs, sr)
            val frames = Pcm16Frames.framesForUsCeil((endUs - startUs).coerceAtLeast(0L), sr)
            val data = ByteArray((frames * ch * 2).toInt())
            var offset = 0
            for (i in 0 until frames.toInt()) {
                val value = (startFrame + i).toShort()
                repeat(ch) {
                    data[offset++] = (value.toInt() and 0xff).toByte()
                    data[offset++] = ((value.toInt() shr 8) and 0xff).toByte()
                }
            }
            return Pcm16Segment(data, sr, ch, frames)
        }
    }

    @Test
    fun lastBlockUsesOneAllowShortDecodeAndStaysWithinBudget() {
        val provider = CountingShortProvider(sr, ch, realEndUs = 3_900_000L)
        val reader = PcmRangeBlockReader(provider, 0L, 4_000L, sr, ch)
        val started = System.nanoTime()
        val data = readExactly(reader, 4_000)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L
        assertEquals("only the last block may use allowShort", 1, provider.shortCalls)
        assertEquals("last block must not fall back to trial decodes", 3, provider.strictCalls)
        assertTrue("last block budget must be <100ms, was " + elapsedMs + "ms", elapsedMs < 100L)
        assertEquals(3_899, sampleAt(data, 3_899))
        for (i in 3_900 until 4_000) assertEquals(0, sampleAt(data, i))
        assertTrue(reader.error() == null)
        reader.release()
    }

    /**
     * 00029 真机复现：MP3 从文件头 seek 后首个输出 PTS≈40ms，PcmDecoder 抛
     * "解码起点晚于区间起点"，旧逻辑把首块标记 FAILED 并把游标推到 block 1（前 1s 被跳过）。
     * 修复后首块用 anchor 重解 + 前置静音，0..expected 帧都应可读。
     */
    private class AnchorLateProvider(
        private val sr: Int,
        private val ch: Int,
        private val anchorUs: Long,
    ) : PcmRangeProvider {
        var anchorRetries = 0
        override fun decode(startUs: Long, endUs: Long): Pcm16Segment {
            if (startUs == 0L) {
                throw IllegalStateException(
                    "解码起点晚于区间起点: anchorUs=" + anchorUs + " startUs=0",
                )
            }
            if (startUs == anchorUs) anchorRetries += 1
            val startFrame = Pcm16Frames.frameAtOrAfterUs(startUs, sr)
            val frames = Pcm16Frames.framesForUsCeil(endUs - startUs, sr)
            val data = ByteArray((frames * ch * 2).toInt())
            var offset = 0
            for (i in 0 until frames.toInt()) {
                val value = (startFrame + i).toShort()
                repeat(ch) {
                    data[offset++] = (value.toInt() and 0xff).toByte()
                    data[offset++] = ((value.toInt() shr 8) and 0xff).toByte()
                }
            }
            return Pcm16Segment(data, sr, ch, frames)
        }
    }

    @Test
    fun firstBlockWithLateAnchorIsPaddedInsteadOfSkipped() {
        val provider = AnchorLateProvider(sr, ch, anchorUs = 40_000L)
        val reader = PcmRangeBlockReader(provider, 0L, 4_000L, sr, ch)
        val data = readExactly(reader, 4_000)
        for (i in 0 until 40) assertEquals("priming frame $i", 0, sampleAt(data, i))
        for (i in 40 until 4_000) assertEquals("frame $i", i, sampleAt(data, i))
        assertEquals(1, provider.anchorRetries)
        assertTrue(reader.error() == null)
        reader.release()
    }

    /**
     * 00029 尾奏漂移的整数帧回归：tail 起点 bFrame=7,653,379 不是 1s 整数倍。
     * 旧怀疑是 us↔帧换算每块丢/多 1~2 帧；这里用绝对帧号做样本值，30s 逐样本断言
     * 读出的内容就是 [startFrame, startFrame+30s)，块边界累计偏移必须为 0。
     */
    @Test
    fun tailWindowStartingAtNonSecondBoundaryHasZeroFrameDrift() {
        val tailSr = 44_100
        val start = 7_653_379L
        val frames = tailSr * 30
        val provider = AbsoluteProvider(tailSr, ch)
        val reader = PcmRangeBlockReader(
            provider = provider,
            startFrame = start,
            endFrameExclusive = start + frames,
            sampleRate = tailSr,
            channels = ch,
            prefetchBlocks = 4,
            decodePrerollUs = 0L,
        )
        val data = readExactly(reader, frames)
        for (i in 0 until frames) {
            val expected = ((start + i) and 0xffffL).toInt()
            assertEquals("frame $i", expected, sampleAt(data, i))
        }
        assertEquals(start + frames, reader.currentFrame())
        reader.release()
    }

    /** 00029：常驻 provider 的顺序块请求必须从块首开始，不能带 250ms 预卷（否则每块都会 seek）。 */
    @Test
    fun residentModeRequestsBlocksWithoutPreroll() {
        val provider = AbsoluteProvider(sr, ch)
        val reader = PcmRangeBlockReader(
            provider = provider,
            startFrame = 0L,
            endFrameExclusive = 4_000L,
            sampleRate = sr,
            channels = ch,
            decodePrerollUs = 0L,
        )
        val data = readExactly(reader, 2_000)
        for (i in 0 until 2_000) assertEquals(i, sampleAt(data, i))
        assertEquals(0L, provider.calls.first().first)
        for ((callStart, callEnd) in provider.calls) {
            assertTrue("start must be block-aligned (no 250ms preroll): $callStart", callStart % 1_000L == 0L)
            assertTrue(callEnd > callStart)
        }
        reader.release()
    }

    private class ReleasableProvider(
        private val sr: Int,
        private val ch: Int,
    ) : ReleasablePcmRangeProvider {
        var released = false
        override fun decode(startUs: Long, endUs: Long): Pcm16Segment {
            val frames = Pcm16Frames.framesForUsCeil(endUs - startUs, sr)
            return Pcm16Segment(ByteArray((frames * ch * 2).toInt()), sr, ch, frames)
        }
        override fun release() { released = true }
    }

    @Test
    fun releaseClosesReleasableProvider() {
        val provider = ReleasableProvider(sr, ch)
        val reader = PcmRangeBlockReader(provider, 0L, 2_000L, sr, ch)
        reader.release()
        assertTrue(provider.released)
    }

    @Test
    fun seededFirstBlockIsServedWithoutProviderDecode() {
        // 00029：结果页轻量预热把首块种进 reader；入口读第一帧时不能再解一次。
        val provider = AbsoluteProvider(sr, ch)
        val frameBytes = sr * ch * 2
        val data = ByteArray(frameBytes)
        for (i in 0 until sr) {
            val value = (1000 + i).toShort()
            data[i * 2] = (value.toInt() and 0xff).toByte()
            data[i * 2 + 1] = ((value.toInt() shr 8) and 0xff).toByte()
        }
        val seeded = PcmBlock(index = 0, startFrame = 0L, frames = sr, data = data)
        val reader = PcmRangeBlockReader(
            provider = provider,
            startFrame = 0L,
            endFrameExclusive = 2L * sr,
            sampleRate = sr,
            channels = ch,
            prefetchBlocks = 0,
            prefetchOnInit = true,
            initialBlocks = listOf(seeded),
        )
        val read = reader.read(16)
        assertNotNull(read)
        assertEquals(16, read!!.frames)
        assertArrayEquals(data.copyOfRange(0, 16 * ch * 2), read.data)
        assertTrue("首块已种，provider 不应被调用来解它", provider.calls.isEmpty())
        reader.release()
    }

    @Test
    fun decodeErrorsDoNotHangAndExposeLastError() {
        // 非最后块的真失败必须暴露；最后块的"越过媒体结尾"才允许静音补齐。
        val failing = object : PcmRangeProvider {
            override fun decode(startUs: Long, endUs: Long): Pcm16Segment {
                if (endUs <= 1_000_000L) throw IllegalStateException("boom")
                val frames = com.zjr.hesimusic.loop.analysis.Pcm16Frames
                    .framesForUsCeil(endUs - startUs, sr)
                return Pcm16Segment(ByteArray((frames * ch * 2).toInt()), sr, ch, frames)
            }
        }
        val reader = PcmRangeBlockReader(failing, 0L, 2_000L, sr, ch)
        var guard = 0
        while (reader.error() == null && guard++ < 100) {
            reader.kick()
            reader.awaitCurrentReady(20L)
        }
        assertTrue(reader.error()?.contains("decode") == true)
        reader.release()
    }
}
