package com.zjr.hesimusic.loop.audio.source

import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.Allocation
import androidx.media3.exoplayer.upstream.Allocator
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue

/** MediaSource 生命周期：MaskingMediaSource 可能先 createPeriod 后 enable，不能抛异常。 */
class LoopingPcmMediaSourceLifecycleTest {

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

    private object NoAllocator : Allocator {
        override fun allocate(): Allocation =
            throw UnsupportedOperationException("not used")

        override fun release(allocation: Allocation) = Unit
        override fun release(allocationNode: Allocator.AllocationNode) = Unit
        override fun trim() = Unit
        override fun getTotalBytesAllocated(): Int = 0
        override fun getIndividualAllocationLength(): Int = 0
    }

    @Test
    fun `create period works before enable and prepares first block`() {
        val tl = LoopingPcmTestSupport.timeline()
        val provider = LoopingPcmTestSupport.provider(tl)
        val dispatcher = FakeDispatcher()
        val source = LoopingPcmMediaSource(
            item = MediaItem.Builder().setMediaId("1").build(),
            timeline = tl,
            provider = provider,
            dispatcher = dispatcher,
            logger = {},
        )
        var refreshed: Timeline? = null
        val caller = object : MediaSource.MediaSourceCaller {
            override fun onSourceInfoRefreshed(source: MediaSource, timeline: Timeline) {
                refreshed = timeline
            }
        }
        source.prepareSource(caller, null as TransferListener?, PlayerId.UNSET)
        assertNotNull(refreshed)

        // 不调用 enable，模拟 MaskingMediaSource/WrappingMediaSource 的懒准备时序。
        val period = source.createPeriod(
            MediaSource.MediaPeriodId("test-period"),
            NoAllocator,
            0L,
        )
        assertNotNull(period)
        assertTrue(period is LoopingPcmMediaPeriod)
        source.releasePeriod(period)
        source.releaseSource(caller)
    }
}
