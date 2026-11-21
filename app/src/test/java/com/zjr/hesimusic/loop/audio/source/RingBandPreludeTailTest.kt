package com.zjr.hesimusic.loop.audio.source

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import com.zjr.hesimusic.loop.analysis.Pcm16Frames
import com.zjr.hesimusic.loop.analysis.Pcm16Segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * issue 00028：整曲时间轴 extractor 的三阶段（prelude / loop / tail）。
 *
 * 口径：sr=1000、ch=1、A=2s、B=4s、dur=6s；环带内容 = 绝对帧号（2000..3999），
 * 前奏/尾奏由按需块读取器生成同样的绝对帧号，所以"听到的值"直接可对账。
 */
class RingBandPreludeTailTest {

    private val sr = 1_000
    private val ch = 1
    private val aUs = 2_000_000L
    private val bUs = 4_000_000L
    private val durUs = 6_000_000L

    private val timeline = LoopingPcmTimeline(
        songId = 7L,
        loopStartUs = aUs,
        loopEndUs = bUs,
        format = PcmTrackFormat(sr, ch),
        fullDurationUs = durUs,
    )

    private class AbsoluteProvider(private val sr: Int, private val ch: Int) : PcmRangeProvider {
        override fun decode(startUs: Long, endUs: Long): Pcm16Segment {
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

    private class FakeTrack(private val channels: Int) : TrackOutput {
        val bytes = ByteArrayOutputStream()
        val pts = ArrayList<Long>()
        var eosFlags = 0
        var sampleCount = 0

        fun resetSamples() {
            bytes.reset()
            pts.clear()
            eosFlags = 0
            sampleCount = 0
        }

        override fun format(format: androidx.media3.common.Format) = Unit

        override fun sampleData(
            input: DataReader,
            length: Int,
            allowEndOfInput: Boolean,
            sampleDataPart: Int,
        ): Int = 0

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            val array = ByteArray(length)
            data.readBytes(array, 0, length)
            bytes.write(array)
            sampleCount += length / (channels * 2)
        }

        override fun sampleMetadata(
            timeUs: Long,
            flags: Int,
            size: Int,
            offset: Int,
            cryptoData: TrackOutput.CryptoData?,
        ) {
            // 数据样本的 metadata 与 EOS 是分开的两次调用；只记录数据样本 PTS。
            if (size > 0) pts += timeUs
            if (flags and C.BUFFER_FLAG_END_OF_STREAM != 0) eosFlags += 1
        }
    }

    private class FakeOutput(channels: Int) : ExtractorOutput {
        val track = FakeTrack(channels)
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

    private fun emptyDecoder(): RingBandStreamDecoder = object : RingBandStreamDecoder {
        override fun stream(
            startUs: Long,
            endUs: Long,
            prerollUs: Long,
            onPcm: (data: ByteArray, size: Int) -> Unit,
        ) = Unit

        override fun close() = Unit
    }

    private fun ringContent(): LoopRing = LoopRing(sr, ch, timeline.loopFrames).also { ring ->
        val data = ByteArray((timeline.loopFrames * ch * 2).toInt())
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until timeline.loopFrames.toInt()) {
            bb.putShort((timeline.aFrame + i).toShort())
        }
        ring.append(data)
    }

    private fun sampleAt(track: FakeTrack, frame: Int): Int {
        val data = track.bytes.toByteArray()
        val offset = frame * ch * 2
        return (data[offset].toInt() and 0xff) or ((data[offset + 1].toInt() and 0xff) shl 8)
    }

    private class Fixture {
        lateinit var extractor: RingBandExtractor
        lateinit var output: FakeOutput
        lateinit var renderer: RingBandRenderer
        lateinit var prelude: PcmRangeBlockReader
        lateinit var tail: PcmRangeBlockReader
        var now = 0L
        val input: ExtractorInput = Proxy.newProxyInstance(
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
    }

    private fun fixture(): Fixture {
        val f = Fixture()
        val ring = ringContent()
        val reader = RingBandReader(ring, timeline.readerStartOffsetFrames())
        f.renderer = RingBandRenderer(timeline, emptyDecoder(), ring)
        f.renderer.start()
        f.prelude = PcmRangeBlockReader(
            provider = AbsoluteProvider(sr, ch),
            startFrame = 0L,
            endFrameExclusive = timeline.aFrame,
            sampleRate = sr,
            channels = ch,
        )
        f.tail = PcmRangeBlockReader(
            provider = AbsoluteProvider(sr, ch),
            startFrame = timeline.bFrame,
            endFrameExclusive = timeline.durationFrames,
            sampleRate = sr,
            channels = ch,
        )
        f.extractor = RingBandExtractor(
            timeline = timeline,
            ring = ring,
            reader = reader,
            renderer = f.renderer,
            preludeReader = f.prelude,
            tailReader = f.tail,
            clock = { f.now },
            sleeper = { f.now += it },
        )
        f.output = FakeOutput(ch)
        f.extractor.init(f.output)
        return f
    }

    private fun drain(f: Fixture, targetSamples: Int, maxCalls: Int = 500): Int {
        var calls = 0
        while (f.output.track.sampleCount < targetSamples && calls < maxCalls) {
            f.now += 100L
            val before = f.output.track.sampleCount
            val result = f.extractor.read(f.input, PositionHolder())
            calls += 1
            if (result == Extractor.RESULT_END_OF_INPUT) break
            if (f.output.track.sampleCount == before && result == Extractor.RESULT_CONTINUE) {
                f.now += 100L
            }
        }
        return f.output.track.sampleCount
    }

    @Test
    fun preludePlaysFromZeroThenLoopStartsAtAWithoutGap() {
        val f = fixture()
        drain(f, 2_500)
        assertTrue("must output prelude+loop", f.output.track.sampleCount >= 2_500)
        assertEquals("first sample is media frame 0", 0, sampleAt(f.output.track, 0))
        assertEquals("last prelude frame", 1_999, sampleAt(f.output.track, 1_999))
        assertEquals("first loop frame is A", 2_000, sampleAt(f.output.track, 2_000))
        assertEquals(0L, f.output.track.pts.first())
        f.extractor.release()
    }

    @Test
    fun seekIntoPreludePlaysRequestedFrame() {
        val f = fixture()
        f.extractor.seek(0L, 500_000L)
        drain(f, 100)
        assertEquals(500, sampleAt(f.output.track, 0))
        assertEquals(500_000L, f.output.track.pts.first())
        f.extractor.release()
    }

    @Test
    fun seekAfterBEntersTailThenWrapsToNextEpochPreludeAndBackToLoop() {
        val f = fixture()
        val tailT = LoopWholeTimeline.timelineUsForDisplay(
            displayUs = 4_500_000L,
            currentTimelineUs = 3_000_000L,
            window = com.zjr.hesimusic.loop.LoopWindow(7L, aUs, bUs),
            fullDurationUs = durUs,
        )
        f.extractor.seek(0L, tailT)
        f.now += 100L
        f.extractor.read(f.input, PositionHolder())
        assertEquals("tail samples must not be folded to A", 4_500, sampleAt(f.output.track, 0))
        assertEquals(tailT, f.output.track.pts.first())

        // 4.5s..6s = 1500 帧；之后源内回绕到下一个 epoch 的 prelude 0，继续出声。
        drain(f, 1_700)
        assertEquals("last tail frame", 5_999, sampleAt(f.output.track, 1_499))
        assertEquals("tail end wraps to prelude 0 (no EOS)", 0, sampleAt(f.output.track, 1_500))
        assertEquals("prelude continues after wrap", 1, sampleAt(f.output.track, 1_501))
        assertEquals("must not emit EOS (source-internal wrap)", 0, f.output.track.eosFlags)
        val span = timeline.epochSpanUs
        assertTrue(
            "wrapped sample PTS must reach the next epoch start, pts=" + f.output.track.pts.last(),
            f.output.track.pts.last() >= span,
        )
        val displayAfterWrap = LoopWholeTimeline.displayUs(
            f.output.track.pts.last(),
            com.zjr.hesimusic.loop.LoopWindow(7L, aUs, bUs),
            durUs,
        )
        assertTrue(
            "after wrap display must be in prelude, display=" + displayAfterWrap,
            displayAfterWrap < aUs,
        )

        f.output.track.resetSamples()
        f.extractor.seek(0L, 3_000_000L)
        drain(f, 50)
        assertEquals("seek back <=B restores loop content", 3_000, sampleAt(f.output.track, 0))
        f.extractor.release()
    }

    @Test
    fun seekToBIsTailBoundaryAndSeekBackInsideRestoresLoop() {
        val f = fixture()
        val bT = LoopWholeTimeline.timelineUsForDisplay(
            displayUs = bUs,
            currentTimelineUs = 3_000_000L,
            window = com.zjr.hesimusic.loop.LoopWindow(7L, aUs, bUs),
            fullDurationUs = durUs,
        )
        f.extractor.seek(0L, bT)
        f.now += 100L
        f.extractor.read(f.input, PositionHolder())
        assertEquals(4_000, sampleAt(f.output.track, 0))

        f.output.track.resetSamples()
        f.extractor.seek(0L, 3_000_000L)
        f.now += 100L
        f.extractor.read(f.input, PositionHolder())
        assertEquals(3_000, sampleAt(f.output.track, 0))
        f.extractor.release()
    }

    @Test
    fun preludePrefetchCoversTheAHandoff() {
        val f = fixture()
        var guard = 0
        while (f.prelude.readyBlockCount() < 2 && guard++ < 200) {
            f.prelude.kick()
            f.prelude.awaitCurrentReady(20L)
        }
        assertTrue(
            "prelude should prefetch both blocks up to A, ready=" + f.prelude.readyBlockCount(),
            f.prelude.readyBlockCount() >= 2,
        )
        f.extractor.release()
    }
}
