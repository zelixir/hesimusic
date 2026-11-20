package com.zjr.hesimusic.loop.audio.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * SIGSEGV 回归（seek-ux 真机复现）：release() 不得在释放线程上 close codec/extractor。
 *
 * 契约：release 只置停止标志；native 资源的 close 必须由跑 stream 的 RingBandRender 线程执行。
 */
class RingBandRendererReleaseSafetyTest {

    private val timeline = LoopingPcmTestSupport.timeline(
        aUs = 0L,
        bUs = 20_000_000L,
        sr = 1_000,
        ch = 1,
    )

    /**
     * 模拟"native 调用进行中"：stream 不可被中断，只能由测试放行。
     * close 记录调用线程，用于断言不是释放线程关的会话。
     */
    private class BlockingDecoder : RingBandStreamDecoder {
        val streamStarted = CountDownLatch(1)

        @Volatile
        var unblocked = false

        @Volatile
        var cancelled = false

        val closeCalls = AtomicInteger()

        @Volatile
        var closeThread: String? = null

        override fun cancel() {
            cancelled = true
        }

        override fun stream(
            startUs: Long,
            endUs: Long,
            prerollUs: Long,
            onPcm: (data: ByteArray, size: Int) -> Unit,
        ) {
            streamStarted.countDown()
            // 真实 MediaExtractor.readSampleData 不可被 Thread.interrupt 打断。
            while (!unblocked) {
                try {
                    Thread.sleep(2)
                } catch (_: InterruptedException) {
                    Thread.interrupted()
                }
            }
        }

        override fun close() {
            closeCalls.incrementAndGet()
            closeThread = Thread.currentThread().name
        }
    }

    @Test
    fun `release does not close from the releasing thread while stream is in flight`() {
        val decoder = BlockingDecoder()
        val ring = LoopRing(timeline.sampleRate, timeline.channels, timeline.loopFrames)
        val renderer = RingBandRenderer(timeline, decoder, ring)
        renderer.start()
        assertTrue("stream 必须已开始", decoder.streamStarted.await(2, TimeUnit.SECONDS))

        val releaseThread = Thread.currentThread().name
        val started = System.nanoTime()
        renderer.release()
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L
        assertTrue("release 必须有限等待而不是无限阻塞: ${elapsedMs}ms", elapsedMs <= 3_000L)
        assertEquals("release 线程绝不允许 close native 会话", 0, decoder.closeCalls.get())

        decoder.unblocked = true
        val deadline = System.currentTimeMillis() + 2_000L
        while (decoder.closeCalls.get() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
        }
        assertEquals("渲染线程退出时必须 close 一次", 1, decoder.closeCalls.get())
        assertNotEquals(releaseThread, decoder.closeThread)
        assertEquals("RingBandRender", decoder.closeThread)
    }

    @Test
    fun `double release closes exactly once`() {
        val decoder = BlockingDecoder()
        val ring = LoopRing(timeline.sampleRate, timeline.channels, timeline.loopFrames)
        val renderer = RingBandRenderer(timeline, decoder, ring)
        renderer.start()
        assertTrue(decoder.streamStarted.await(2, TimeUnit.SECONDS))
        decoder.unblocked = true
        Thread.sleep(50)
        renderer.release()
        renderer.release()
        val deadline = System.currentTimeMillis() + 2_000L
        while (decoder.closeCalls.get() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
        }
        Thread.sleep(100)
        assertEquals("close 必须幂等", 1, decoder.closeCalls.get())
    }
}
