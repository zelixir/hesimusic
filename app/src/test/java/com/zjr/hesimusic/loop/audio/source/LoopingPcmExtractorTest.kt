package com.zjr.hesimusic.loop.audio.source

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.common.DataReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/** Extractor 路径：连续输出 PCM、PTS 单调、seek 后第一帧映射正确。 */
class LoopingPcmExtractorTest {

    private class FakeOutput : ExtractorOutput {
        val track = FakeTrack()
        var seekMap: SeekMap? = null
        override fun track(id: Int, type: Int): TrackOutput = track
        override fun endTracks() = Unit
        override fun seekMap(seekMap: SeekMap) {
            this.seekMap = seekMap
        }
    }

    private class FakeTrack : TrackOutput {
        val bytes = ArrayList<ByteArray>()
        val pts = ArrayList<Long>()
        var format: Format? = null
        var totalBytes = 0

        override fun format(format: Format) {
            this.format = format
        }

        override fun sampleData(
            input: DataReader,
            length: Int,
            allowEndOfInput: Boolean,
            sampleDataPart: Int,
        ): Int = 0

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            val arr = ByteArray(length)
            data.readBytes(arr, 0, length)
            bytes += arr
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

    @Test
    fun `first read predecodes two blocks before output`() {
        val tl = LoopingPcmTestSupport.timeline()
        val provider = LoopingPcmTestSupport.provider(tl)
        val core = LoopingPcmSourceCore(tl, provider, prefetchBlocks = 6)
        val out = FakeOutput()
        val extractor = LoopingPcmExtractor(core, tl)
        extractor.init(out)
        assertEquals(Extractor.RESULT_CONTINUE, extractor.read(fakeInput(), PositionHolder()))
        assertTrue("ready blocks=" + core.readyBlockCount(), core.readyBlockCount() >= 2)
        assertTrue("first read must output samples", out.track.totalBytes > 0)
    }

    @Test
    fun `short block is rejected and totalReadFrames does not advance`() {
        val tl = LoopingPcmTestSupport.timeline()
        val bad = object : PcmRangeProvider {
            override fun decode(startUs: Long, endUs: Long) =
                com.zjr.hesimusic.loop.analysis.Pcm16Segment(
                    data = ByteArray(0),
                    sampleRate = tl.sampleRate,
                    channels = tl.channels,
                    frames = 0,
                )
        }
        val core = LoopingPcmSourceCore(tl, bad, prefetchBlocks = 6)
        val out = FakeOutput()
        val extractor = LoopingPcmExtractor(core, tl)
        extractor.init(out)
        assertEquals(Extractor.RESULT_CONTINUE, extractor.read(fakeInput(), PositionHolder()))
        assertEquals(0L, core.totalReadFrames())
        assertEquals(0, out.track.totalBytes)
    }

    @Test
    fun `paused gate stops extractor read`() {
        val tl = LoopingPcmTestSupport.timeline()
        val provider = LoopingPcmTestSupport.provider(tl)
        val core = LoopingPcmSourceCore(tl, provider, prefetchBlocks = 2)
        repeat(4) { core.pumpDecode() }
        val out = FakeOutput()
        val extractor = LoopingPcmExtractor(core, tl)
        extractor.init(out)
        val input = fakeInput()
        val seek = PositionHolder()
        assertEquals(Extractor.RESULT_CONTINUE, extractor.read(input, seek))
        val frameBefore = core.currentFrame()
        val samplesBefore = out.track.pts.size
        try {
            LoopSourcePauseGate.pause()
            assertEquals(Extractor.RESULT_CONTINUE, extractor.read(input, seek))
            assertEquals(frameBefore, core.currentFrame())
            assertEquals(samplesBefore, out.track.pts.size)
        } finally {
            LoopSourcePauseGate.reset()
        }
        assertEquals(Extractor.RESULT_CONTINUE, extractor.read(input, seek))
        assertTrue(core.currentFrame() != frameBefore)
    }

    @Test
    fun `read outputs three seconds of monotonic PCM and seek lands on target`() {
        val tl = LoopingPcmTestSupport.timeline()
        val provider = LoopingPcmTestSupport.provider(tl)
        val core = LoopingPcmSourceCore(tl, provider, prefetchBlocks = 2)
        repeat(4) { core.pumpDecode() }

        val out = FakeOutput()
        val extractor = LoopingPcmExtractor(core, tl)
        extractor.init(out)
        assertEquals(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_AUDIO)
        assertNotNull(out.seekMap)
        assertNotNull(out.track.format)
        assertEquals(tl.sampleRate, out.track.format!!.sampleRate)
        assertEquals(tl.channels, out.track.format!!.channelCount)

        val input = fakeInput()
        val seekPosition = PositionHolder()
        repeat(3) {
            assertEquals(Extractor.RESULT_CONTINUE, extractor.read(input, seekPosition))
        }
        val frames = out.track.totalBytes / tl.bytesPerFrame
        assertTrue("must output at least 3s, frames=" + frames, frames >= 3_000L)
        assertEquals(3, out.track.pts.size)
        for (i in 1 until out.track.pts.size) {
            assertTrue("PTS must be monotonic", out.track.pts[i] > out.track.pts[i - 1])
        }

        // seek 到 1s：下一帧 PTS 必须是 1_000_000，数据从 A+1s 开始。
        extractor.seek(0L, 1_000_000L)
        out.track.pts.clear()
        assertEquals(Extractor.RESULT_CONTINUE, extractor.read(input, seekPosition))
        assertEquals(1_000_000L, out.track.pts.single())
    }
}
