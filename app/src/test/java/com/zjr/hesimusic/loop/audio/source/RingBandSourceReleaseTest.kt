package com.zjr.hesimusic.loop.audio.source

import android.os.Handler
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.drm.DrmSessionEventListener
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MediaSourceEventListener
import androidx.media3.exoplayer.upstream.Allocator
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RingBandSourceReleaseTest {

    private class NoopDecoder : RingBandStreamDecoder {
        override fun stream(
            startUs: Long,
            endUs: Long,
            prerollUs: Long,
            onPcm: (data: ByteArray, size: Int) -> Unit,
        ) = Unit

        override fun close() = Unit
    }

    private class FakeDelegate : MediaSource {
        override fun addEventListener(handler: Handler, listener: MediaSourceEventListener) = Unit
        override fun removeEventListener(listener: MediaSourceEventListener) = Unit
        override fun addDrmEventListener(handler: Handler, listener: DrmSessionEventListener) = Unit
        override fun removeDrmEventListener(listener: DrmSessionEventListener) = Unit
        override fun getMediaItem(): MediaItem = MediaItem.Builder().setMediaId("ring-release").build()
        override fun prepareSource(
            caller: MediaSource.MediaSourceCaller,
            transferListener: TransferListener?,
            playerId: PlayerId,
        ) = Unit

        override fun maybeThrowSourceInfoRefreshError() = Unit
        override fun enable(caller: MediaSource.MediaSourceCaller) = Unit
        override fun createPeriod(
            id: MediaSource.MediaPeriodId,
            allocator: Allocator,
            startPositionUs: Long,
        ): MediaPeriod = throw UnsupportedOperationException()

        override fun releasePeriod(mediaPeriod: MediaPeriod) = Unit
        override fun disable(caller: MediaSource.MediaSourceCaller) = Unit
        override fun releaseSource(caller: MediaSource.MediaSourceCaller) = Unit
    }

    @Test
    fun `ten create release cycles drop ring reference and close data`() {
        val tl = LoopingPcmTestSupport.timeline(aUs = 0L, bUs = 1_000_000L, sr = 1_000)
        val rings = ArrayList<LoopRing>()
        repeat(10) { i ->
            val ring = LoopRing(tl.sampleRate, tl.channels, tl.loopFrames)
            val reader = RingBandReader(ring)
            val renderer = RingBandRenderer(tl, NoopDecoder(), ring)
            val extractor = RingBandExtractor(tl, ring, reader, renderer)
            val source = RingBandMediaSource(FakeDelegate(), extractor, ring)
            val held = source.ringForTest()
            assertNotNull("source $i must own a ring", held)
            rings += held!!
            source.releaseForTest()
            assertNull("source $i after release", source.ringForTest())
            assertTrue("ring $i data must be closed", ring.closed)
        }
        val weak = rings.map { java.lang.ref.WeakReference(it) }
        rings.clear()
        System.gc()
        Thread.sleep(20)
        assertTrue(
            "released rings must not be retained by source",
            weak.all { it.get() == null || it.get()?.closed == true },
        )
    }
}
