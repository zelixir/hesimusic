package com.zjr.hesimusic.loop.audio

import androidx.media3.exoplayer.audio.AudioSink
import com.zjr.hesimusic.loop.LoopWindow
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class LoopAudioSinkTest {

    private val bUs = 200_000L

    private fun window(aMs: Long, bMs: Long): LoopWindow =
        LoopWindow(songId = 1L, loopStartUs = aMs * 1000L, loopEndUs = bMs * 1000L)

    private fun rig(
        window: LoopWindow? = window(100, 200),
        maxFramesPerCall: Int = Int.MAX_VALUE,
    ): Triple<LoopAudioController, LoopAudioSink, FakeAudioSink> {
        val controller = LoopAudioController()
        // 单测里让预分配/降级 dispatch 同步执行，保证确定性。
        controller.preallocDispatcher = { it.run() }
        controller.degradeDispatcher = { it.run() }
        val fake = FakeAudioSink()
        fake.maxFramesPerCall = maxFramesPerCall
        val sink = LoopAudioSink(controller, fake)
        sink.configure(PcmTestSupport.format(), 4096, null)
        controller.window = window
        return Triple(controller, sink, fake)
    }

    private fun feedFully(sink: LoopAudioSink, buffer: ByteBuffer, ptsUs: Long): Boolean {
        var guard = 0
        while (guard++ < 20_000) {
            if (sink.handleBuffer(buffer, ptsUs, 1)) return true
        }
        fail("输入在 20000 次重试内仍未消费完")
        return false
    }

    private fun replayFeeds(fake: FakeAudioSink, fromPtsUs: Long): List<FakeAudioSink.Feed> =
        fake.feeds.filter { it.ptsUs >= fromPtsUs }

    private fun expectedRing(): ByteArray =
        PcmTestSupport.bytesOf(PcmTestSupport.pcm16Buffer(100, 100))

    // ---------------------------------------------------------------- 捕获/切帧

    @Test
    fun `A B 落在 buffer 中间时只转发到 B 且进入重放`() {
        val (controller, sink, fake) = rig()
        val src = PcmTestSupport.pcm16Buffer(0, 250)

        assertTrue("整段输入应被消费", sink.handleBuffer(src, 0L, 1))

        val pre = fake.feeds.first()
        assertEquals("越 B 的 buffer 只能转发 [0,B) 的 200 帧", 200, pre.frames)
        assertEquals("转发 PTS", 0L, pre.ptsUs)
        assertArrayEquals(
            "转发字节应严格裁到 B(不含 B 样本)",
            PcmTestSupport.bytesOf(PcmTestSupport.pcm16Buffer(0, 200)),
            pre.bytes,
        )
        assertEquals("buffer 的 limit 应被裁到 B", 400, src.limit())
        assertTrue("ring 应完整", controller.isRingComplete)
        assertTrue("应切到重放", controller.isReplaying)
        assertEquals("ring 帧数 = L", 100L, controller.engine.ringFrames)
    }

    @Test
    fun `多 buffer 背压时复用同一个 ByteBuffer 实例`() {
        val (controller, sink, fake) = rig(maxFramesPerCall = 3)
        val src = PcmTestSupport.pcm16Buffer(0, 250)

        assertTrue("背压重试最终应消费完整段", feedFully(sink, src, 0L))

        val preFeeds = fake.feeds.filter { it.ptsUs < bUs }
        assertTrue("应有多次背压重试", preFeeds.size > 10)
        val identity = System.identityHashCode(src)
        assertTrue(
            "未吃完时必须复用同一个 ByteBuffer 实例",
            preFeeds.all { it.bufferIdentity == identity },
        )
        assertEquals("预录内容应累计到 B", 200, preFeeds.sumOf { it.frames })
        assertTrue(controller.isReplaying)
    }

    @Test
    fun `重放背压时复用同一个 direct LE ByteBuffer`() {
        val (controller, sink, fake) = rig(maxFramesPerCall = 3)
        val src = PcmTestSupport.pcm16Buffer(0, 250)
        assertTrue(feedFully(sink, src, 0L))

        // 初始泵已经消费了第一段的一部分；再泵两次，仍应停留在同一个 chunk。
        sink.playToEndOfStream()
        sink.playToEndOfStream()

        val replay = replayFeeds(fake, bUs)
        assertTrue("应已有重放输出", replay.size >= 3)
        val firstChunkIdentity = replay.first().bufferIdentity
        assertTrue(
            "重放未吃完时必须复用同一个 ByteBuffer",
            replay.take(3).all { it.bufferIdentity == firstChunkIdentity },
        )
        assertTrue(
            "重放 buffer 必须 LITTLE_ENDIAN",
            replay.all { it.order == ByteOrder.LITTLE_ENDIAN },
        )
        assertTrue(controller.isReplaying)
    }

    // ---------------------------------------------------------------- 泵/漂移

    @Test
    fun `泵送跨三轮 帧数 3L 零漂移 逐轮逐位相同`() {
        val (controller, sink, fake) = rig(maxFramesPerCall = 7)
        val src = PcmTestSupport.pcm16Buffer(0, 250)
        assertTrue(feedFully(sink, src, 0L))
        assertTrue("捕获后应进入重放", controller.isReplaying)

        fun replayTotal(): Int = replayFeeds(fake, bUs).sumOf { it.frames }
        var guard = 0
        while (replayTotal() < 300 && guard++ < 1000) {
            sink.playToEndOfStream()
        }
        assertTrue("泵送应至少输出 3L 帧，实际 = " + replayTotal(), replayTotal() >= 300)

        var cumulative = 0L
        for (feed in replayFeeds(fake, bUs)) {
            val expectedPts = bUs + cumulative * 1000L
            assertEquals("第 k 轮第 offset 帧的 PTS 必须由帧号反算", expectedPts, feed.ptsUs)
            assertEquals("重放 buffer 必须 LITTLE_ENDIAN", ByteOrder.LITTLE_ENDIAN, feed.order)
            cumulative += feed.frames.toLong()
        }
        assertTrue("重放累计帧数应 >= 3L", cumulative >= 300L)

        val all = ByteArrayOutputStream()
        replayFeeds(fake, bUs).forEach { all.write(it.bytes) }
        val bytes = all.toByteArray()
        assertTrue("重放输出应覆盖 3 轮", bytes.size >= 600)
        val ring = expectedRing()
        assertArrayEquals("第 1 轮 PCM 应等于 ring", ring, bytes.copyOfRange(0, 200))
        assertArrayEquals("第 2 轮应与第 1 轮逐位相同", ring, bytes.copyOfRange(200, 400))
        assertArrayEquals("第 3 轮应与第 1 轮逐位相同", ring, bytes.copyOfRange(400, 600))
        assertTrue("三轮后应至少折返 3 次", controller.seamCount >= 3L)
    }

    @Test
    fun `循环态 playToEndOfStream 不转发 delegate isEnded 恒 false`() {
        val (controller, sink, fake) = rig()
        assertTrue(feedFully(sink, PcmTestSupport.pcm16Buffer(0, 250), 0L))
        assertTrue(controller.isReplaying)

        val before = fake.playToEndCalls
        fake.ended = true
        repeat(5) { sink.playToEndOfStream() }

        assertEquals("循环态不得调用 delegate.playToEndOfStream()", before, fake.playToEndCalls)
        assertFalse("循环态 isEnded 必须恒为 false", sink.isEnded())
        assertEquals("循环态不得调用 delegate.isEnded()", 0, fake.isEndedCalls)
    }

    // ---------------------------------------------------------------- 生命周期

    @Test
    fun `flush 清重放态但保留 ring 从 B 之后可直接续播`() {
        val (controller, sink, fake) = rig()
        assertTrue(feedFully(sink, PcmTestSupport.pcm16Buffer(0, 250), 0L))
        assertTrue(controller.isReplaying)

        sink.flush()

        assertEquals("flush 应转发 delegate", 1, fake.flushCalls)
        assertFalse("flush 后应退出重放", controller.isReplaying)
        assertTrue("flush 不应清掉 ring", controller.isRingComplete)

        val afterFlush = fake.feeds.size
        assertTrue(
            "B 之后的输入应直接重新进入重放",
            sink.handleBuffer(PcmTestSupport.pcm16Buffer(200, 10), bUs, 1),
        )
        assertTrue("应重新进入重放", controller.isReplaying)
        val newFeeds = fake.feeds.drop(afterFlush)
        val firstReplay = newFeeds.firstOrNull { it.ptsUs >= bUs }
        assertTrue("应产生重放输出", firstReplay != null)
        assertEquals("重放首样本 PTS = B", bUs, firstReplay!!.ptsUs)
        assertArrayEquals(
            "保留的 ring 应从 A 开始",
            PcmTestSupport.bytesOf(PcmTestSupport.pcm16Buffer(100, 10)),
            firstReplay.bytes,
        )
    }

    @Test
    fun `源端 EOS 兜底 ring 完整时从 B 前一帧也能接上重放`() {
        val (controller, sink, fake) = rig()
        assertTrue(feedFully(sink, PcmTestSupport.pcm16Buffer(0, 250), 0L))
        sink.flush()
        assertTrue(controller.isRingComplete)

        // 播放到 B 前一帧（199 帧）后源端结束，没有 crossedB。
        val afterFlush = fake.feeds.size
        assertTrue(sink.handleBuffer(PcmTestSupport.pcm16Buffer(190, 9), 190_000L, 1))
        assertFalse("尚未触发 crossedB", controller.isReplaying)

        val playToEndBefore = fake.playToEndCalls
        sink.playToEndOfStream()

        assertEquals("循环态不得转发 delegate.playToEndOfStream", playToEndBefore, fake.playToEndCalls)
        assertTrue("源端 EOS 应兜底进入重放", controller.isReplaying)
        val replay = fake.feeds.drop(afterFlush).firstOrNull { it.ptsUs >= bUs }
        assertTrue("应产生重放输出", replay != null)
        assertEquals("兜底重放首样本 PTS = B", bUs, replay!!.ptsUs)
        assertArrayEquals(
            "兜底重放应从 ring 开头输出",
            PcmTestSupport.bytesOf(PcmTestSupport.pcm16Buffer(100, 10)),
            replay.bytes,
        )
    }

    @Test
    fun `窗口变更时重建 ring 并使用新窗口重放`() {
        val (controller, sink, fake) = rig()
        assertTrue(feedFully(sink, PcmTestSupport.pcm16Buffer(0, 250), 0L))
        assertTrue(controller.isReplaying)

        controller.window = window(150, 250)
        assertFalse("窗口变更后 ring 应失效", controller.isRingComplete)

        val afterChange = fake.feeds.size
        assertTrue(
            "新窗口的输入应被消费",
            sink.handleBuffer(PcmTestSupport.pcm16Buffer(0, 300), 0L, 1),
        )
        assertTrue("新窗口 ring 应完整", controller.isRingComplete)
        assertTrue("新窗口应进入重放", controller.isReplaying)

        val newReplay = fake.feeds.drop(afterChange).firstOrNull { it.ptsUs >= 250_000L }
        assertTrue("应产生新窗口重放输出", newReplay != null)
        assertEquals("新窗口首轮 PTS = 新 B", 250_000L, newReplay!!.ptsUs)
        assertArrayEquals(
            "新窗口 ring 应从新 A 开始",
            PcmTestSupport.bytesOf(PcmTestSupport.pcm16Buffer(150, 10)),
            newReplay.bytes,
        )
    }

    @Test
    fun `ring 超限时 sink 直通且不接管 EOS`() {
        val (controller, sink, fake) = rig(window = LoopWindow(1L, 0L, 300_000_001L))
        val src = PcmTestSupport.pcm16Buffer(0, 100)

        assertTrue(sink.handleBuffer(src, 0L, 1))

        assertEquals("超限应计入降级", 1L, controller.degradeCount)
        assertFalse("超限时 ring 不完整", controller.isRingComplete)
        assertFalse("超限时不应重放", controller.isReplaying)
        assertEquals("超限时应直通全部输入", 100, fake.feeds.first().frames)

        sink.playToEndOfStream()
        assertEquals("超限时 playToEndOfStream 应转发 delegate", 1, fake.playToEndCalls)
        fake.ended = true
        assertTrue("超限时 isEnded 应转发 delegate", sink.isEnded())
        assertEquals("超限时 isEnded 应转发 delegate", 1, fake.isEndedCalls)
    }

    @Test
    fun `ring 不完整时降级为直通 避免永久空转`() {
        val (controller, sink, fake) = rig()
        val src = PcmTestSupport.pcm16Buffer(150, 100)

        assertTrue(sink.handleBuffer(src, 150_000L, 1))

        assertEquals("ring 只捕获后半段，不完整", 50L, controller.engine.capturedFrames)
        assertFalse(controller.isRingComplete)
        assertTrue("应计入降级", controller.degradeCount >= 1L)
        assertFalse("不完整时不应进入重放", controller.isReplaying)
        assertEquals("转发应裁到 B", 50, fake.feeds.first().frames)

        sink.playToEndOfStream()
        assertEquals("降级后 playToEndOfStream 应转发 delegate", 1, fake.playToEndCalls)
    }

    @Test
    fun `getCurrentPositionUs 写入 controller 并扣除输出偏移`() {
        val controller = LoopAudioController()
        val fake = FakeAudioSink()
        val sink = LoopAudioSink(controller, fake)
        sink.configure(PcmTestSupport.format(), 4096, null)

        fake.currentPositionUs = 123_456L
        assertEquals("应转发 delegate 位置", 123_456L, sink.getCurrentPositionUs(false))
        assertEquals("controller 位置应等于媒体位置", 123_456L, controller.positionUs)

        sink.setOutputStreamOffsetUs(1_000_000_000L)
        fake.currentPositionUs = 1_000_123_456L
        assertEquals("带 offset 的 delegate 位置应原样返回", 1_000_123_456L, sink.getCurrentPositionUs(false))
        assertEquals("controller 应存媒体位置", 123_456L, controller.positionUs)

        // 重放期 delegate 可能返回 1e12+media 或 media，controller 都必须落在媒体时间。
        val (replayController, sink2, fake2) = rig()
        assertTrue(feedFully(sink2, PcmTestSupport.pcm16Buffer(0, 250), 0L))
        sink2.setOutputStreamOffsetUs(1_000_000_000_000L)
        fake2.currentPositionUs = 1_000_000_000_000L + bUs
        val rawReplay = sink2.getCurrentPositionUs(false)
        assertEquals("delegate 原始位置应原样返回", 1_000_000_000_000L + bUs, rawReplay)
        assertEquals(
            "重放期 controller 应存 delegate 的媒体位置(扣除 output offset)",
            bUs,
            replayController.positionUs,
        )

        fake2.currentPositionUs = AudioSink.CURRENT_POSITION_NOT_SET
        val fallback = sink2.getCurrentPositionUs(false)
        assertEquals("delegate 未设置位置时应返回 delegate 原值", AudioSink.CURRENT_POSITION_NOT_SET, fallback)
        assertTrue(
            "delegate 未设置位置时 controller 仍应保持引擎媒体位置",
            replayController.positionUs >= bUs,
        )
    }

    @Test
    fun `降级回调经 dispatch 异步抛出且目标正确 同一事件只一次`() {
        val controller = LoopAudioController()
        controller.preallocDispatcher = { it.run() }
        val queued = mutableListOf<Runnable>()
        controller.degradeDispatcher = { queued.add(it) }
        val targets = mutableListOf<Long>()
        controller.onDegradeRequested = { targets.add(it) }

        val fake = FakeAudioSink()
        val sink = LoopAudioSink(controller, fake)
        sink.configure(PcmTestSupport.format(), 4096, null)
        controller.window = window(1000, 2000)

        // 从 1.5s 进入，只捕获 [1.5s,2s)，ring 不完整 -> 降级
        assertTrue(
            sink.handleBuffer(
                PcmTestSupport.pcm16Buffer(1500, 1000),
                1_500_000L,
                1,
            )
        )

        assertEquals("音频线程不得直接调用服务层回调", 0, targets.size)
        assertEquals("降级应 dispatch 到主线程", 1, queued.size)
        queued.removeAt(0).run()
        assertEquals("主线程执行后应回调一次", 1, targets.size)
        assertEquals("target = A - 250ms", 750_000L, targets[0])

        // 同一事件后继续喂普通输入，不应再次回调
        assertTrue(sink.handleBuffer(PcmTestSupport.pcm16Buffer(0, 10), 0L, 1))
        assertEquals("同一降级事件不得重复回调", 1, targets.size)

        // flush 后再次失败 -> 再次回调
        sink.flush()
        assertTrue(
            sink.handleBuffer(
                PcmTestSupport.pcm16Buffer(2000, 10),
                2_000_000L,
                1,
            )
        )
        assertEquals("flush 后应重新排队一次回调", 1, queued.size)
        queued.removeAt(0).run()
        assertEquals("flush 后应再次回调", 2, targets.size)
        assertEquals("目标值仍为 A - 250ms", 750_000L, targets[1])
    }

    @Test
    fun `降级发生在回调注册前时注册后补发一次`() {
        val controller = LoopAudioController()
        controller.preallocDispatcher = { it.run() }
        val queued = mutableListOf<Runnable>()
        controller.degradeDispatcher = { queued.add(it) }
        val fake = FakeAudioSink()
        val sink = LoopAudioSink(controller, fake)
        sink.configure(PcmTestSupport.format(), 4096, null)
        controller.window = window(1000, 2000)

        assertTrue(
            sink.handleBuffer(
                PcmTestSupport.pcm16Buffer(1500, 1000),
                1_500_000L,
                1,
            )
        )
        assertEquals("回调未注册时不应 dispatch", 0, queued.size)

        val targets = mutableListOf<Long>()
        controller.onDegradeRequested = { targets.add(it) }
        assertEquals("注册回调后应补发 pending 事件", 1, queued.size)
        queued.removeAt(0).run()
        assertEquals(1, targets.size)
        assertEquals(750_000L, targets[0])
    }

    @Test
    fun `预分配在首次 handleBuffer 前完成且音频线程不分配`() {
        val controller = LoopAudioController()
        controller.preallocDispatcher = { it.run() }
        val fake = FakeAudioSink()
        val sink = LoopAudioSink(controller, fake)
        sink.configure(PcmTestSupport.format(), 4096, null)

        controller.window = window(100, 200)

        assertEquals("window 写入时就应完成预分配", 100L, controller.engine.ringFrames)
        assertFalse("预分配完成后不应 pending", controller.engine.isPreallocationPending())

        assertTrue(sink.handleBuffer(PcmTestSupport.pcm16Buffer(0, 250), 0L, 1))
        assertTrue(controller.isReplaying)
        assertEquals("首次回调不得在音频线程分配 ring", 0L, controller.engine.audioThreadAllocationCount)
    }

    @Test
    fun `预分配未完成时 handleBuffer 背压且不消费 buffer`() {
        val controller = LoopAudioController()
        var pending: Runnable? = null
        controller.preallocDispatcher = { pending = it }
        val fake = FakeAudioSink()
        val sink = LoopAudioSink(controller, fake)
        sink.configure(PcmTestSupport.format(), 4096, null)
        controller.window = window(100, 200)

        assertTrue(controller.engine.isPreallocationPending())
        val src = PcmTestSupport.pcm16Buffer(0, 250)
        assertFalse("预分配未完成应背压", sink.handleBuffer(src, 0L, 1))
        assertEquals("背压时不得消费 buffer", 0, src.position())
        assertEquals("背压时不得喂 delegate", 0, fake.feeds.size)
        assertEquals("音频线程不得分配", 0L, controller.engine.audioThreadAllocationCount)

        pending!!.run()
        assertFalse("预分配完成后不应 pending", controller.engine.isPreallocationPending())
        assertTrue(sink.handleBuffer(src, 0L, 1))
        assertTrue(controller.isReplaying)
        assertEquals("预分配完成后的首次回调不分配", 0L, controller.engine.audioThreadAllocationCount)
    }

    @Test
    fun `window 先于 configure 写入时 configure 后完成预分配`() {
        val controller = LoopAudioController()
        controller.preallocDispatcher = { it.run() }
        controller.window = window(100, 200)
        assertEquals("format 未知时无法预分配", 0L, controller.engine.ringFrames)

        val fake = FakeAudioSink()
        val sink = LoopAudioSink(controller, fake)
        sink.configure(PcmTestSupport.format(), 4096, null)

        assertEquals("configure 后应完成预分配", 100L, controller.engine.ringFrames)
        assertFalse(controller.engine.isPreallocationPending())
        assertTrue(sink.handleBuffer(PcmTestSupport.pcm16Buffer(0, 250), 0L, 1))
        assertEquals(0L, controller.engine.audioThreadAllocationCount)
    }

    @Test
    fun `debug PCM tap 落 WAV 且数据长度正确`() {
        val file = File.createTempFile("loop-tap-", ".wav")
        try {
            val controller = LoopAudioController()
            controller.setPcmTap(file)
            val fake = FakeAudioSink()
            val sink = LoopAudioSink(controller, fake)
            sink.configure(PcmTestSupport.format(), 4096, null)

            assertTrue(sink.handleBuffer(PcmTestSupport.pcm16Buffer(0, 100), 0L, 1))
            sink.release()

            val bytes = file.readBytes()
            assertEquals("WAV 长度 = 44 头 + 200 字节 PCM", 244, bytes.size)
            assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
            assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
            val dataSize = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(40)
            assertEquals("data chunk 长度应为 200", 200, dataSize)
        } finally {
            file.delete()
        }
    }
}
