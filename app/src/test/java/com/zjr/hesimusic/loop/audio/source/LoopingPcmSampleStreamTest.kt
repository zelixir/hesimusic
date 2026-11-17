package com.zjr.hesimusic.loop.audio.source

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.source.SampleStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** media3 SampleStream 契约（JVM，不启动 ExoPlayer/AudioTrack）。 */
class LoopingPcmSampleStreamTest {

    private fun format(tl: LoopingPcmTimeline): Format = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_RAW)
        .setPcmEncoding(C.ENCODING_PCM_16BIT)
        .setSampleRate(tl.sampleRate)
        .setChannelCount(tl.channels)
        .build()

    private class Setup(
        val core: LoopingPcmSourceCore,
        val stream: LoopingPcmSampleStream,
        val format: Format,
        val timeline: LoopingPcmTimeline,
    )

    private fun setup(): Setup {
        val tl = LoopingPcmTestSupport.timeline()
        val core = LoopingPcmSourceCore(tl, LoopingPcmTestSupport.provider(tl))
        return Setup(core, LoopingPcmSampleStream(core, format(tl)), format(tl), tl)
    }

    private fun readSample(
        stream: LoopingPcmSampleStream,
        buffer: DecoderInputBuffer,
        formatHolder: FormatHolder,
        flags: Int = 0,
    ): Int = stream.readData(formatHolder, buffer, flags)

    @Test
    fun `format is sent once then PCM buffers carry our PTS`() {
        val s = setup()
        val holder = FormatHolder()
        val buffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)
        assertFalse(s.stream.isReady())
        assertTrue(s.core.pumpDecode())

        assertEquals(C.RESULT_FORMAT_READ, readSample(s.stream, buffer, holder))
        assertNotNull(holder.format)
        assertEquals(MimeTypes.AUDIO_RAW, holder.format!!.sampleMimeType)
        assertEquals(C.ENCODING_PCM_16BIT, holder.format!!.pcmEncoding)
        assertEquals(s.timeline.sampleRate, holder.format!!.sampleRate)

        assertEquals(C.RESULT_BUFFER_READ, readSample(s.stream, buffer, holder))
        assertEquals(0L, buffer.timeUs)
        val data = buffer.data
        assertNotNull(data)
        assertEquals(0, LoopingPcmTestSupport.sampleAt(data!!.duplicate().let {
            val bytes = ByteArray(it.remaining())
            it.get(bytes)
            bytes
        }, 0))

        val firstFrames = buffer.data!!.limit() / s.timeline.bytesPerFrame
        assertTrue(s.core.pumpDecode()) // block 1
        assertTrue(s.core.pumpDecode()) // block 2
        assertEquals(C.RESULT_BUFFER_READ, readSample(s.stream, buffer, holder))
        assertEquals(firstFrames * 1_000L, buffer.timeUs)
    }

    @Test
    fun `peek does not advance and the next normal read is identical`() {
        val s = setup()
        val holder = FormatHolder()
        val buffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)
        s.core.pumpDecode()
        readSample(s.stream, buffer, holder)
        assertEquals(C.RESULT_BUFFER_READ, readSample(s.stream, buffer, holder))
        val frames = buffer.data!!.limit() / s.timeline.bytesPerFrame
        val expectedPts = frames * 1_000L
        assertTrue(s.core.pumpDecode()) // block 1, the next buffer

        assertEquals(C.RESULT_BUFFER_READ, readSample(s.stream, buffer, holder, SampleStream.FLAG_PEEK))
        assertEquals(expectedPts, buffer.timeUs)
        val first = buffer.data!!.duplicate()

        assertEquals(C.RESULT_BUFFER_READ, readSample(s.stream, buffer, holder))
        assertEquals(expectedPts, buffer.timeUs)
        assertEquals(first, buffer.data!!.duplicate())
    }

    @Test
    fun `seek resets format and next PTS is the seek target`() {
        val s = setup()
        val holder = FormatHolder()
        val buffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)
        s.core.pumpDecode()
        readSample(s.stream, buffer, holder)
        readSample(s.stream, buffer, holder)

        s.core.seekToTimelineUs(10_000_000L)
        s.stream.onSeek()
        assertTrue(s.core.pumpDecode())
        assertEquals(C.RESULT_FORMAT_READ, readSample(s.stream, buffer, holder))
        assertEquals(C.RESULT_BUFFER_READ, readSample(s.stream, buffer, holder))
        assertEquals(10_000_000L, buffer.timeUs)
        val bytes = buffer.data!!.duplicate().let {
            val out = ByteArray(it.remaining())
            it.get(out)
            out
        }
        assertEquals(10_000, LoopingPcmTestSupport.sampleAt(bytes, 0))
    }

    @Test
    fun `omit sample data still advances PTS`() {
        val s = setup()
        val holder = FormatHolder()
        val buffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)
        s.core.pumpDecode()
        readSample(s.stream, buffer, holder)
        assertEquals(C.RESULT_BUFFER_READ, readSample(s.stream, buffer, holder))
        val frames = buffer.data!!.limit() / s.timeline.bytesPerFrame
        assertTrue(s.core.pumpDecode()) // block 1
        assertTrue(s.core.pumpDecode()) // block 2

        assertEquals(
            C.RESULT_BUFFER_READ,
            readSample(s.stream, buffer, holder, SampleStream.FLAG_OMIT_SAMPLE_DATA),
        )
        assertEquals(C.RESULT_BUFFER_READ, readSample(s.stream, buffer, holder))
        assertEquals((frames * 2L) * 1_000L, buffer.timeUs)
    }

    @Test
    fun `skipData advances the timeline cursor`() {
        val s = setup()
        s.core.pumpDecode()
        assertEquals(500, s.stream.skipData(500_000L))
        assertEquals(500_000L, s.core.currentPtsUs())
    }
}
