package com.zjr.hesimusic.loop.audio.source

import com.zjr.hesimusic.loop.analysis.Pcm16Frames
import com.zjr.hesimusic.loop.analysis.Pcm16Segment
import org.junit.Assert.assertEquals
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
