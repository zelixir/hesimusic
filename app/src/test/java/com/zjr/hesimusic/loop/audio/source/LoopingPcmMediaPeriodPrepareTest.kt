package com.zjr.hesimusic.loop.audio.source

import androidx.media3.exoplayer.source.MediaPeriod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** MediaPeriod 准备阶段：首块正常、provider 永久 pending 时 2s 超时报错。 */
class LoopingPcmMediaPeriodPrepareTest {

    private class FakeDispatcher : CallbackDispatcher {
        private val posted = LinkedBlockingQueue<Runnable>()
        private val delayed = mutableListOf<Runnable>()

        override fun post(runnable: Runnable) {
            posted.add(runnable)
        }

        override fun postDelayed(runnable: Runnable, delayMs: Long) {
            synchronized(delayed) { delayed += runnable }
        }

        override fun remove(runnable: Runnable) {
            synchronized(delayed) { delayed.removeAll { it === runnable } }
        }

        fun drainPosted() {
            while (true) {
                val runnable = posted.poll() ?: return
                runnable.run()
            }
        }

        fun runDelayed() {
            val copy = synchronized(delayed) { delayed.toList() }
            copy.forEach { it.run() }
        }

        fun hasDelayed(): Boolean = synchronized(delayed) { delayed.isNotEmpty() }
    }

    private class FakeCallback : MediaPeriod.Callback {
        val prepared = CountDownLatch(1)
        var continueLoadingCalls: Int = 0

        override fun onPrepared(mediaPeriod: MediaPeriod) {
            prepared.countDown()
        }

        override fun onContinueLoadingRequested(source: MediaPeriod) {
            continueLoadingCalls += 1
        }
    }

    @Test
    fun `prepare decodes first block and calls onPrepared`() {
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
                dispatcher.drainPosted()
            }
            assertEquals(0L, callback.prepared.count)
            assertEquals(
                "§12.3: media3 会在 handleLoadingPeriodPrepared 末尾自己调 maybeContinueLoading," +
                    "period 不应再主动踢 loader",
                0,
                callback.continueLoadingCalls,
            )
            assertTrue("first block decode must call provider", provider.decodeCount() >= 1)
            assertTrue(
                "continuous ready blocks must cover at least 3s for LoadControl",
                period.getBufferedPositionUs() >= 3_000_000L,
            )
        } finally {
            period.release()
        }
    }

    @Test
    fun `100 continueLoading calls while a target block is pending submit only one decode`() {
        val tl = LoopingPcmTestSupport.timeline()
        val provider = LoopingPcmTestSupport.provider(tl)
        val gate = CountDownLatch(1)
        provider.gate = gate
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
            provider.awaitDecodeStarted()

            val info = androidx.media3.exoplayer.LoadingInfo.Builder()
                .setPlaybackPositionUs(0L)
                .setPlaybackSpeed(1f)
                .build()
            repeat(100) { period.continueLoading(info) }

            assertEquals(1, provider.decodeCount())
            assertEquals(0L, period.currentRequestGeneration())
            assertTrue("dedupe should skip the repeated requests", period.dedupeSkipCount() >= 100)
        } finally {
            gate.countDown()
            period.release()
        }
    }

    @Test
    fun `permanently pending provider times out instead of buffering forever`() {
        val tl = LoopingPcmTestSupport.timeline()
        val provider = LoopingPcmTestSupport.provider(tl)
        val gate = CountDownLatch(1)
        provider.gate = gate
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
            provider.awaitDecodeStarted()
            assertTrue("prepare must schedule a timeout", dispatcher.hasDelayed())
            dispatcher.runDelayed()
            dispatcher.drainPosted()

            try {
                period.maybeThrowPrepareError()
                fail("pending provider must surface a prepare timeout error")
            } catch (_: IOException) {
                // expected
            }
            assertEquals("timeout must not silently report prepared", 1L, callback.prepared.count)
        } finally {
            gate.countDown()
            period.release()
        }
    }
}
