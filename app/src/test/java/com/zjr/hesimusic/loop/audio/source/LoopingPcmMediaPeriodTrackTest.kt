package com.zjr.hesimusic.loop.audio.source

import androidx.media3.common.C
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** selectTracks 把唯一音频流挂到 audio renderer 槽位后，SampleStream 能读出 PCM。 */
class LoopingPcmMediaPeriodTrackTest {

    private class FakeDispatcher : CallbackDispatcher {
        private val posted = LinkedBlockingQueue<Runnable>()
        override fun post(runnable: Runnable) {
            posted.add(runnable)
        }

        override fun postDelayed(runnable: Runnable, delayMs: Long) = Unit
        override fun remove(runnable: Runnable) = Unit

        fun drain() {
            while (true) {
                (posted.poll() ?: return).run()
            }
        }
    }

    private class FakeCallback : MediaPeriod.Callback {
        val prepared = CountDownLatch(1)
        override fun onPrepared(mediaPeriod: MediaPeriod) {
            prepared.countDown()
        }

        override fun onContinueLoadingRequested(source: MediaPeriod) {}
    }

    @Test
    fun `selectTracks assigns audio stream and readData returns PCM`() {
        val tl = LoopingPcmTestSupport.timeline()
        val provider = LoopingPcmTestSupport.provider(tl)
        val dispatcher = FakeDispatcher()
        val period = LoopingPcmMediaPeriod(
            timeline = tl,
            provider = provider,
            dispatcher = dispatcher,
            logger = {},
        )
        val callback = FakeCallback()
        try {
            period.prepare(callback, 0L)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (!callback.prepared.await(5, TimeUnit.MILLISECONDS) &&
                System.nanoTime() < deadline
            ) {
                dispatcher.drain()
            }
            assertEquals(0L, callback.prepared.count)

            // audio 在默认 renderers 的 index 1；selection 只放 index 1。
            val selections = arrayOfNulls<ExoTrackSelection>(2)
            selections[1] = Proxy.newProxyInstance(
                ExoTrackSelection::class.java.classLoader,
                arrayOf(ExoTrackSelection::class.java),
            ) { _, method, _ ->
                when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    else -> null
                }
            } as ExoTrackSelection
            val streams = arrayOfNulls<androidx.media3.exoplayer.source.SampleStream>(2)
            val reset = BooleanArray(2)
            period.selectTracks(
                selections = selections,
                mayRetainStreamFlags = BooleanArray(2),
                streams = streams,
                streamResetFlags = reset,
                positionUs = 0L,
            )
            assertNull("video slot must stay empty", streams[0])
            assertNotNull("audio slot must receive the stream", streams[1])
            assertTrue(reset[1])

            val holder = FormatHolder()
            val buffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)
            val stream = streams[1]!!
            assertEquals(C.RESULT_FORMAT_READ, stream.readData(holder, buffer, 0))
            assertNotNull(holder.format)
            assertEquals(C.RESULT_BUFFER_READ, stream.readData(holder, buffer, 0))
            assertTrue("readData must return PCM bytes", buffer.data?.remaining() ?: 0 > 0)
            assertEquals(0L, buffer.timeUs)
        } finally {
            period.release()
        }
    }
}
