package com.zjr.hesimusic.loop.audio.source

import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.common.DataReader
import androidx.media3.common.util.ParsableByteArray
import com.zjr.hesimusic.loop.audio.PcmTestSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class RingBandExtractorTest {

    private class FakeTrack : TrackOutput {
        var totalBytes = 0
        var pts = ArrayList<Long>()
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

    @Test
    fun `two filled blocks are readable and PTS starts at zero`() {
        val tl = LoopingPcmTestSupport.timeline(aUs = 0L, bUs = 5_000_000L, sr = 1_000)
        val ring = LoopRing(tl.sampleRate, tl.channels, tl.loopFrames)
        ring.append(PcmTestSupport.pcm16Buffer(0, 2_000).let { buf ->
            val data = ByteArray(buf.remaining()); buf.get(data); data
        })
        val reader = RingBandReader(ring)
        val renderer = RingBandRenderer(
            timeline = tl,
            provider = LoopingPcmTestSupport.provider(tl),
            ring = ring,
        )
        val extractor = RingBandExtractor(tl, ring, reader, renderer)
        val out = FakeOutput()
        extractor.init(out)
        val input = Proxy.newProxyInstance(
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
        assertEquals(Extractor.RESULT_CONTINUE, extractor.read(input, PositionHolder()))
        assertTrue("must output samples", out.track.totalBytes > 0)
        assertEquals(0L, out.track.pts.first())
    }
}
