package com.zjr.hesimusic.loop.audio.source

import androidx.media3.common.C
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.source.SampleStream
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopTrackAssignmentTest {

    private val fake = object : SampleStream {
        override fun isReady(): Boolean = true
        override fun maybeThrowError() {}
        override fun readData(
            formatHolder: FormatHolder,
            buffer: DecoderInputBuffer,
            readFlags: Int,
        ): Int = C.RESULT_NOTHING_READ
        override fun skipData(positionUs: Long): Int = 0
    }

    @Test
    fun `audio selection can live at renderer index one`() {
        val selected = booleanArrayOf(false, true)
        val streams = arrayOfNulls<SampleStream>(2)
        val reset = BooleanArray(2)

        LoopTrackAssignment.assign(selected, streams, fake, reset)

        assertNull(streams[0])
        assertSame(fake, streams[1])
        assertTrue(reset[1])
    }

    @Test
    fun `unselected slots are cleared`() {
        val selected = booleanArrayOf(false, true)
        val streams = arrayOfNulls<SampleStream>(2)
        val other = object : SampleStream {
            override fun isReady(): Boolean = false
            override fun maybeThrowError() {}
            override fun readData(
                formatHolder: FormatHolder,
                buffer: DecoderInputBuffer,
                readFlags: Int,
            ): Int = C.RESULT_NOTHING_READ
            override fun skipData(positionUs: Long): Int = 0
        }
        streams[0] = other
        val reset = BooleanArray(2)

        LoopTrackAssignment.assign(selected, streams, fake, reset)

        assertNull(streams[0])
        assertSame(fake, streams[1])
    }
}
