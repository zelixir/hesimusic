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

    @Test
    fun `seam 开关打开时 NORMAL 到 SEAM 再回源 三圈逐样本一致`() {
        val (controller, sink, fake) = rig(window(2000, 62000))
        controller.seamBufferMode = SeamBufferMode.ON
        controller.seamSinkRouteEnabled = true
        controller.seamDeltaUs = 1_000_000L
        controller.seamDecodeRange = { s, e ->
            PcmTestSupport.bytesOf(PcmTestSupport.pcm16Buffer(s.toInt(), (e - s).toInt()))
        }
        val seeks = mutableListOf<Long>()
        controller.seamSeekRequested = { seeks.add(it) }

        fun feedRange(startFrame: Int, frames: Int, chunk: Int = 1000) {
            var f = startFrame
            val end = startFrame + frames
            while (f < end) {
                val n = minOf(chunk, end - f)
                assertTrue(feedFully(sink, PcmTestSupport.pcm16Buffer(f, n), f * 1000L))
                f += n
            }
        }

        // 第一圈：A..B-Δ + S 的 X
        feedRange(2000, 59_000)
        feedRange(61_000, 1000)
        // S 已输出 Y，源 seek 到 A+Δ；第二圈
        feedRange(3000, 58_000)
        feedRange(61_000, 1000)
        // 第三圈
        feedRange(3000, 58_000)
        feedRange(61_000, 1000)

        val out = ByteArrayOutputStream()
        fake.feeds.forEach { out.write(it.bytes) }
        val actual = out.toByteArray()
        val expected = PcmTestSupport.bytesOf(PcmTestSupport.pcm16Buffer(2000, 60_000)) +
            PcmTestSupport.bytesOf(PcmTestSupport.pcm16Buffer(2000, 60_000)) +
            PcmTestSupport.bytesOf(PcmTestSupport.pcm16Buffer(2000, 60_000))
        assertArrayEquals(
            "三圈输出必须与理论 [A,B) 逐样本一致",
            expected,
            actual.copyOf(expected.size),
        )
        assertTrue("应完成 3 次 S 切换: " + sink.seamDebugStats(), sink.seamDebugStats().contains("switch=3"))
        assertTrue("切换点样本缺口应为 0: " + sink.seamDebugStats(), sink.seamDebugStats().contains("gap=0"))
        assertTrue("每圈应请求 seek A+Δ: " + seeks, seeks.size >= 2)
        assertTrue("seek 目标应为 A+Δ", seeks.all { it == 3_000_000L })
        assertTrue("切回源应调用 discontinuity", fake.discontinuityCalls >= 2)
    }

    @Test
    fun `seam S 输出被 delegate 部分消费时不丢帧`() {
        val (controller, sink, fake) = rig(window(0, 31_000), maxFramesPerCall = 3)
        controller.seamBufferMode = SeamBufferMode.ON
        controller.seamSinkRouteEnabled = true
        controller.seamDeltaUs = 1_000_000L
        controller.seamDecodeRange = { s, e ->
            PcmTestSupport.bytesOf(PcmTestSupport.pcm16Buffer(s.toInt(), (e - s).toInt()))
        }
        controller.seamSeekRequested = { }

        var f = 0
        while (f < 30_000) {
            assertTrue(feedFully(sink, PcmTestSupport.pcm16Buffer(f, 3), f * 1000L))
            f += 3
        }
        assertTrue(feedFully(sink, PcmTestSupport.pcm16Buffer(30_000, 1000), 30_000_000L))

        val seamFeeds = fake.feeds.filter { it.ptsUs >= 30_000_000L && it.ptsUs < 32_000_000L }
        assertEquals("S 必须完整输出 X+Y 共 2Δ", 2000, seamFeeds.sumOf { it.frames })
        val actualSeam = seamFeeds.flatMap { it.bytes.toList() }.toByteArray()
        val expectedSeam = PcmTestSupport.bytesOf(PcmTestSupport.pcm16Buffer(30_000, 1000)) +
            PcmTestSupport.bytesOf(PcmTestSupport.pcm16Buffer(0, 1000))
        assertArrayEquals(expectedSeam, actualSeam)
        assertTrue(sink.seamDebugStats().contains("switch=1"))
    }

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

        // flush 后从前奏播到 B 前一帧（199 帧），源端结束，没有 crossedB。
        val afterFlush = fake.feeds.size
        assertTrue(sink.handleBuffer(PcmTestSupport.pcm16Buffer(0, 199), 0L, 1))
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
    fun `ring 不完整且无前缀时停在捕获 避免永久空转`() {
        val (controller, sink, fake) = rig()
        val src = PcmTestSupport.pcm16Buffer(150, 100)

        assertTrue(sink.handleBuffer(src, 150_000L, 1))

        assertEquals("ring 只捕获后半段，不完整", 50L, controller.engine.capturedFrames)
        assertFalse(controller.isRingComplete)
        assertEquals("未完整不再降级", 0L, controller.degradeCount)
        assertFalse("无连续前缀时不应进入重放", controller.isReplaying)
        assertEquals("转发应裁到 B", 50, fake.feeds.first().frames)

        sink.playToEndOfStream()
        assertEquals("无法重放时 playToEndOfStream 仍转发 delegate", 1, fake.playToEndCalls)
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
        assertTrue(
            controller.seedRing(
                window(1000, 2000),
                PcmTestSupport.bytesOf(PcmTestSupport.pcm16Buffer(1000, 1000)),
                PcmTestSupport.SAMPLE_RATE,
                1,
            ).isInstalled,
        )
        sink.playToEndOfStream()
        assertTrue(controller.isReplaying)

        controller.engine.onDegrade?.invoke(1_000_000L)

        assertEquals("音频线程不得直接调用服务层回调", 0, targets.size)
        assertEquals("降级应 dispatch 到主线程", 1, queued.size)
        queued.removeAt(0).run()
        assertEquals("主线程执行后应回调一次", 1, targets.size)
        assertEquals("target 必须夹到 A", 1_000_000L, targets[0])

        // flush 后再次收到降级事件 -> 再次 dispatch
        sink.flush()
        controller.engine.onDegrade?.invoke(1_000_000L)
        assertEquals("flush 后应重新排队一次回调", 1, queued.size)
        queued.removeAt(0).run()
        assertEquals("flush 后应再次回调", 2, targets.size)
        assertEquals("目标值仍不得小于 A", 1_000_000L, targets[1])
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
            controller.seedRing(
                window(1000, 2000),
                PcmTestSupport.bytesOf(PcmTestSupport.pcm16Buffer(1000, 1000)),
                PcmTestSupport.SAMPLE_RATE,
                1,
            ).isInstalled,
        )
        sink.playToEndOfStream()
        assertTrue(controller.isReplaying)
        controller.engine.onDegrade?.invoke(1_000_000L)
        assertEquals("回调未注册时不应 dispatch", 0, queued.size)

        val targets = mutableListOf<Long>()
        controller.onDegradeRequested = { targets.add(it) }
        assertEquals("注册回调后应补发 pending 事件", 1, queued.size)
        queued.removeAt(0).run()
        assertEquals(1, targets.size)
        assertEquals("target 必须夹到 A", 1_000_000L, targets[0])
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
    fun `offset 在 configure 之前写入时捕获仍按媒体时间命中 B 并重放`() {
        val controller = LoopAudioController()
        controller.preallocDispatcher = { it.run() }
        controller.degradeDispatcher = { it.run() }
        val fake = FakeAudioSink()
        val sink = LoopAudioSink(controller, fake)

        // 复现 ExoPlayer 的真实顺序：setOutputStreamOffsetUs 先于 configure。
        val offset = 1_000_000_000_000L
        sink.setOutputStreamOffsetUs(offset)
        sink.configure(PcmTestSupport.format(), 4096, null)
        controller.window = window(100, 200)

        // 未裁剪输入：0..250 帧，PTS 带 1e12 偏移；引擎必须用媒体时间 0 起算。
        assertTrue(
            sink.handleBuffer(
                PcmTestSupport.pcm16Buffer(0, 250),
                offset,
                1,
            )
        )

        assertTrue("ring 应完整命中 [A,B)", controller.isRingComplete)
        assertTrue("应进入重放", controller.isReplaying)

        val pre = fake.feeds.first()
        assertEquals("预录段应转发 [0,B)", 200, pre.frames)
        assertEquals("delegate 仍应看到 offset+媒体时间", offset, pre.ptsUs)

        val replay = fake.feeds.filter { it.ptsUs >= offset + bUs }
        assertTrue("应产生重放输出", replay.isNotEmpty())
        assertEquals(
            "重放 PTS = offset + B + (k-1)L + 环内偏移",
            offset + bUs,
            replay.first().ptsUs,
        )
        assertArrayEquals(
            "重放内容应从 A 开始",
            PcmTestSupport.bytesOf(PcmTestSupport.pcm16Buffer(100, 10)),
            replay.first().bytes,
        )

        fake.currentPositionUs = offset + 123_456L
        sink.getCurrentPositionUs(false)
        assertEquals("controller.positionUs 必须是媒体时间", 123_456L, controller.positionUs)
    }

    @Test
    fun `offset 在流中途变化时不串味`() {
        val controller = LoopAudioController()
        controller.preallocDispatcher = { it.run() }
        controller.degradeDispatcher = { it.run() }
        val fake = FakeAudioSink()
        val sink = LoopAudioSink(controller, fake)
        val firstOffset = 1_000_000_000_000L
        sink.setOutputStreamOffsetUs(firstOffset)
        sink.configure(PcmTestSupport.format(), 4096, null)
        controller.window = window(100, 200)

        // 第一段只播前奏 [0,100)，不捕获
        assertTrue(sink.handleBuffer(PcmTestSupport.pcm16Buffer(0, 100), firstOffset, 1))
        assertEquals(0L, controller.engine.capturedFrames)

        // seek/流重启后 offset 改变；输入 PTS 用新 offset + 媒体时间
        val secondOffset = 2_000_000_000_000L
        sink.setOutputStreamOffsetUs(secondOffset)
        assertTrue(
            sink.handleBuffer(
                PcmTestSupport.pcm16Buffer(100, 150),
                secondOffset + 100_000L,
                1,
            )
        )

        assertTrue("offset 变化后仍应精确捕获 [100,200)", controller.isRingComplete)
        assertTrue(controller.isReplaying)
        val replay = fake.feeds.filter { it.ptsUs >= secondOffset + bUs }
        assertTrue("应产生重放输出", replay.isNotEmpty())
        assertEquals(
            "重放必须使用新的 offset 域",
            secondOffset + bUs,
            replay.first().ptsUs,
        )
        assertArrayEquals(
            "捕获内容不能串入旧 offset 的帧",
            PcmTestSupport.bytesOf(PcmTestSupport.pcm16Buffer(100, 10)),
            replay.first().bytes,
        )

        fake.currentPositionUs = secondOffset + 150_000L
        sink.getCurrentPositionUs(false)
        assertEquals("位置也必须跟随新 offset 归一化", 150_000L, controller.positionUs)
    }

    @Test
    fun `重放态 delegate 拒绝写入时不消费输入并返回 false`() {
        val (controller, sink, fake) = rig()
        assertTrue(feedFully(sink, PcmTestSupport.pcm16Buffer(0, 250), 0L))
        assertTrue(controller.isReplaying)

        fake.maxFramesPerCall = 0
        fake.feeds.clear()
        val src = PcmTestSupport.pcm16Buffer(300, 50)
        assertFalse("delegate 不可写时应背压", sink.handleBuffer(src, 300_000L, 1))
        assertEquals("未写出重放时不得消费输入", 0, src.position())
        assertTrue("delegate 不应收到任何帧", fake.feeds.isEmpty())

        // 同一 buffer 在 delegate 可写后应可正常消费并产出重放
        fake.maxFramesPerCall = 7
        assertTrue("可写后应消费该 buffer", sink.handleBuffer(src, 300_000L, 1))
        assertEquals("输入应被消费", src.limit(), src.position())
        assertTrue("应产出重放", fake.feeds.isNotEmpty())
    }

    @Test
    fun `连续喂 3L 帧输入时输出至少 3L 且输入消费不超前`() {
        val (controller, sink, fake) = rig(maxFramesPerCall = 7)
        assertTrue(feedFully(sink, PcmTestSupport.pcm16Buffer(0, 250), 0L))
        assertTrue(controller.isReplaying)
        fake.feeds.clear()

        var consumedInputFrames = 0
        val fedInputFrames = 300
        for (i in 0 until 30) {
            val start = 1000 + i * 10
            val src = PcmTestSupport.pcm16Buffer(start, 10)
            val pts = start * 1000L
            var retries = 0
            while (!sink.handleBuffer(src, pts, 1)) {
                if (++retries > 1000) fail("输入在 1000 次重试内仍未消费")
            }
            consumedInputFrames += 10
        }

        val outFrames = fake.feeds.sumOf { it.frames }
        assertTrue("3L 输入应至少产出 3L 重放，实际 " + outFrames, outFrames >= fedInputFrames)
        assertTrue("输入消费量不得超过输入量", consumedInputFrames <= fedInputFrames)
        assertTrue("节流后不应无限超前产出，实际 " + outFrames, outFrames <= 400)
        var lastPts = Long.MIN_VALUE
        for (feed in fake.feeds) {
            assertTrue("重放 PTS 应单调", feed.ptsUs >= lastPts)
            lastPts = feed.ptsUs
        }
    }

    @Test
    fun `getCurrentPositionUs 在重放态也会驱动泵`() {
        val (controller, sink, fake) = rig(maxFramesPerCall = 7)
        assertTrue(feedFully(sink, PcmTestSupport.pcm16Buffer(0, 250), 0L))
        assertTrue(controller.isReplaying)
        fake.feeds.clear()

        repeat(3) { sink.getCurrentPositionUs(false) }

        assertTrue("位置查询应作为 EOS 兜底驱动泵", fake.feeds.isNotEmpty())
        assertTrue(fake.feeds.sumOf { it.frames } > 0)
    }

    @Test
    fun `前缀只覆盖到 A+2s 时 seek 35s 仍从 35s 源输入播放`() {
        val w = window(20000, 50000)
        val (controller, sink, fake) = rig(w)
        // 首片只覆盖 A..A+2s
        assertTrue(
            controller.seedRingRange(
                w,
                0L,
                PcmTestSupport.bytesOf(PcmTestSupport.pcm16Buffer(20000, 2000)),
                PcmTestSupport.SAMPLE_RATE,
                1,
            ).isInstalled,
        )
        // 模拟 seek flush 后，解码器从 35s 喂入
        sink.flush()
        val ptsUs = 35_000_000L
        val before = fake.feeds.size
        assertTrue(
            sink.handleBuffer(
                PcmTestSupport.pcm16Buffer(35000, 10),
                ptsUs,
                1,
            )
        )

        assertFalse("前缀覆盖不到 35s 时不得提前进入 A 的重放", controller.isReplaying)
        val feed = fake.feeds.drop(before).firstOrNull { it.ptsUs >= ptsUs }
        assertTrue("必须转发 35s 源输入", feed != null)
        assertEquals("源输入 PTS 必须保持 35s", ptsUs, feed!!.ptsUs)
        assertArrayEquals(
            "必须输出源 35s 的样本",
            PcmTestSupport.bytesOf(PcmTestSupport.pcm16Buffer(35000, 10)),
            feed.bytes.copyOfRange(0, 20),
        )
    }

    @Test
    fun `flush 后输入 PTS 落在区间内部时从该相位继续`() {
        val (controller, sink, fake) = rig()
        assertTrue(feedFully(sink, PcmTestSupport.pcm16Buffer(0, 250), 0L))
        assertTrue(controller.isReplaying)

        // seek 后的 flush：ring 保留且完整
        sink.flush()
        assertTrue(controller.isRingComplete)
        val afterFlush = fake.feeds.size

        // 新输入到来前 renderer 可能调 playToEndOfStream：不得先按 0 进重放
        val playToEndBefore = fake.playToEndCalls
        sink.playToEndOfStream()
        assertFalse(controller.isReplaying)
        assertEquals("等待相位输入时不得转发 delegate EOS", playToEndBefore, fake.playToEndCalls)

        // 输入 PTS = A+20 帧（区间内部）
        val ptsUs = 120_000L
        assertTrue(
            sink.handleBuffer(
                PcmTestSupport.pcm16Buffer(120, 10),
                ptsUs,
                1,
            )
        )

        assertTrue("应立刻重新进入重放", controller.isReplaying)
        val replay = fake.feeds.drop(afterFlush).firstOrNull { it.ptsUs >= ptsUs }
        assertTrue("应产生重放输出", replay != null)
        assertEquals("首个重放 PTS 应等于输入 PTS", ptsUs, replay!!.ptsUs)
        assertArrayEquals(
            "输出前 10 帧应来自源 A+20 帧",
            PcmTestSupport.bytesOf(PcmTestSupport.pcm16Buffer(120, 10)),
            replay.bytes.copyOfRange(0, 20),
        )
    }

    @Test
    fun `discontinuity 未伴随 flush 时也按输入 PTS 重定位`() {
        val (controller, sink, fake) = rig()
        assertTrue(feedFully(sink, PcmTestSupport.pcm16Buffer(0, 250), 0L))
        assertTrue(controller.isReplaying)
        val after = fake.feeds.size

        sink.handleDiscontinuity()
        val ptsUs = 120_000L
        assertTrue(
            sink.handleBuffer(
                PcmTestSupport.pcm16Buffer(120, 10),
                ptsUs,
                1,
            )
        )

        assertTrue(controller.isReplaying)
        val replay = fake.feeds.drop(after).firstOrNull { it.ptsUs >= ptsUs }
        assertTrue("应产生重放输出", replay != null)
        assertEquals("首个重放 PTS 应等于输入 PTS", ptsUs, replay!!.ptsUs)
        assertArrayEquals(
            "输出前 10 帧应来自源 A+20 帧",
            PcmTestSupport.bytesOf(PcmTestSupport.pcm16Buffer(120, 10)),
            replay.bytes.copyOfRange(0, 20),
        )
    }

    @Test
    fun `重放中 seek 到 B 之后按 PTS 相位从环内起点继续`() {
        val (controller, sink, fake) = rig()
        assertTrue(feedFully(sink, PcmTestSupport.pcm16Buffer(0, 250), 0L))
        assertTrue(controller.isReplaying)

        // 模拟 seek 后的 flush：ring 保留且完整，输入从 raw 位置重新喂入
        sink.flush()
        assertTrue(controller.isRingComplete)
        val afterFlush = fake.feeds.size

        // A=100ms,B=200ms,L=100 帧；输入 PTS = B + 12345 帧 -> 相位 45 帧
        val startFrame = 200 + 12345
        val ptsUs = startFrame * 1000L
        assertTrue(
            sink.handleBuffer(
                PcmTestSupport.pcm16Buffer(startFrame, 10),
                ptsUs,
                1,
            )
        )

        assertTrue("应重新进入重放", controller.isReplaying)
        val replay = fake.feeds.drop(afterFlush).firstOrNull { it.ptsUs >= ptsUs }
        assertTrue("应产生重放输出", replay != null)
        assertEquals("首个重放 PTS 应等于输入 PTS", ptsUs, replay!!.ptsUs)
        assertArrayEquals(
            "应从环内 45 帧(源 145 帧)开始",
            PcmTestSupport.bytesOf(PcmTestSupport.pcm16Buffer(145, 10)),
            replay.bytes.copyOfRange(0, 20),
        )
    }

    @Test
    fun `未裁剪输入越过 B 后由后续 buffer 驱动泵继续重放`() {
        val controller = LoopAudioController()
        controller.preallocDispatcher = { it.run() }
        controller.degradeDispatcher = { it.run() }
        val fake = FakeAudioSink()
        val sink = LoopAudioSink(controller, fake)
        val offset = 1_000_000_000_000L
        sink.setOutputStreamOffsetUs(offset)
        sink.configure(PcmTestSupport.format(), 4096, null)
        controller.window = window(100, 200)

        // 第一次越过 B（未裁剪，buffer 本身超过 B）
        assertTrue(sink.handleBuffer(PcmTestSupport.pcm16Buffer(0, 250), offset, 1))
        assertTrue(controller.isReplaying)
        val before = fake.feeds.size

        // 未裁剪输入继续喂 [250,1000) 帧；sink 应丢弃输入并继续泵 ring。
        for (start in 250 until 1000 step 50) {
            assertTrue(
                sink.handleBuffer(
                    PcmTestSupport.pcm16Buffer(start, 50),
                    offset + start * 1000L,
                    1,
                )
            )
        }

        val replay = fake.feeds.drop(before)
        assertTrue("未裁剪输入期间也应持续泵出重放数据", replay.isNotEmpty())
        var lastPts = Long.MIN_VALUE
        for (feed in replay) {
            assertTrue("重放 PTS 应带 offset 且单调", feed.ptsUs >= offset + bUs)
            assertTrue("重放 PTS 应单调", feed.ptsUs >= lastPts)
            assertEquals(ByteOrder.LITTLE_ENDIAN, feed.order)
            lastPts = feed.ptsUs
        }
        assertEquals("未裁剪路径不依赖 delegate EOS", 0, fake.playToEndCalls)
    }

    @Test
    fun `tap 侧车写出 configure seam flush end`() {
        val file = File.createTempFile("loop-sidecar-", ".wav")
        val sidecar = File(file.parentFile, file.name + ".jsonl")
        try {
            val controller = LoopAudioController()
            controller.preallocDispatcher = { it.run() }
            controller.degradeDispatcher = { it.run() }
            controller.setPcmTap(file)
            val fake = FakeAudioSink()
            val sink = LoopAudioSink(controller, fake)
            sink.configure(PcmTestSupport.format(), 4096, null)
            controller.window = window(100, 200)

            assertTrue(sink.handleBuffer(PcmTestSupport.pcm16Buffer(0, 250), 0L, 1))
            sink.flush()
            sink.release()

            val lines = sidecar.readLines()
            assertTrue(
                "configure 事件应包含 sr/ch/bpf",
                lines.any {
                    it.contains("\"ev\":\"configure\"") &&
                        it.contains("\"sr\":1000") &&
                        it.contains("\"ch\":1") &&
                        it.contains("\"bpf\":2")
                },
            )
            assertTrue(
                "run 事件应包含 aMs/bMs/sampleRate",
                lines.any {
                    it.contains("\"ev\":\"run\"") &&
                        it.contains("\"mode\":\"sink\"") &&
                        it.contains("\"aMs\":100") &&
                        it.contains("\"bMs\":200") &&
                        it.contains("\"sampleRate\":1000")
                },
            )
            assertTrue(
                "seam 事件应包含 iter/outFrames/ptsUs",
                lines.any {
                    it.contains("\"ev\":\"seam\"") &&
                        it.contains("\"iter\":1") &&
                        it.contains("\"outFrames\":") &&
                        it.contains("\"ptsUs\":200000")
                },
            )
            assertTrue("flush 事件应存在", lines.any { it.contains("\"ev\":\"flush\"") })

            val endLine = lines.last { it.contains("\"ev\":\"end\"") }
            val totalFrames = Regex("\"totalFrames\":(\\d+)")
                .find(endLine)!!.groupValues[1].toLong()
            assertEquals(
                "end.totalFrames 应等于 WAV 的 PCM 帧数",
                (file.length() - 44L) / 2L,
                totalFrames,
            )
            assertTrue("end 应带 flushCount=1", endLine.contains("\"flushCount\":1"))
            assertTrue("end 应带 degradeCount=0", endLine.contains("\"degradeCount\":0"))
        } finally {
            file.delete()
            sidecar.delete()
        }
    }

    @Test
    fun `tap 侧车写出 degrade 事件`() {
        val file = File.createTempFile("loop-sidecar-degrade-", ".wav")
        val sidecar = File(file.parentFile, file.name + ".jsonl")
        try {
            val controller = LoopAudioController()
            controller.preallocDispatcher = { it.run() }
            controller.degradeDispatcher = { it.run() }
            controller.setPcmTap(file)
            val fake = FakeAudioSink()
            val sink = LoopAudioSink(controller, fake)
            sink.configure(PcmTestSupport.format(), 4096, null)
            controller.window = window(1000, 2000)
            assertTrue(
                controller.seedRing(
                    window(1000, 2000),
                    PcmTestSupport.bytesOf(PcmTestSupport.pcm16Buffer(1000, 1000)),
                    PcmTestSupport.SAMPLE_RATE,
                    1,
                ).isInstalled,
            )
            sink.playToEndOfStream()
            assertTrue(controller.isReplaying)

            controller.engine.onDegrade?.invoke(1_000_000L)
            sink.release()

            val lines = sidecar.readLines()
            assertTrue(
                "degrade 事件应包含 targetRawUs",
                lines.any {
                    it.contains("\"ev\":\"degrade\"") &&
                        it.contains("\"targetRawUs\":1000000")
                },
            )
            assertTrue(
                "run 事件应反映当前窗口",
                lines.any {
                    it.contains("\"ev\":\"run\"") &&
                        it.contains("\"aMs\":1000") &&
                        it.contains("\"bMs\":2000")
                },
            )
            val endLine = lines.last { it.contains("\"ev\":\"end\"") }
            assertTrue("end 应带 degradeCount=0（直接注入事件不改引擎计数）", endLine.contains("\"degradeCount\":0"))
        } finally {
            file.delete()
            sidecar.delete()
        }
    }

    @Test
    fun `输入 PTS 大跳变主动重置 delegate 时间戳基线`() {
        val (controller, sink, fake) = rig(window(1000, 2000))
        assertTrue(
            controller.seedRing(
                window(1000, 2000),
                PcmTestSupport.bytesOf(PcmTestSupport.pcm16Buffer(1000, 1000)),
                PcmTestSupport.SAMPLE_RATE,
                1,
            ).isInstalled,
        )
        sink.playToEndOfStream()
        assertTrue(controller.isReplaying)

        // 第一次输入建立 lastInputPts
        assertTrue(sink.handleBuffer(PcmTestSupport.pcm16Buffer(1500, 10), 1_500_000L, 1))
        val before = fake.discontinuityCalls

        // 跨过 0.5s 跳变（模拟 seek），必须主动通知 delegate
        assertTrue(sink.handleBuffer(PcmTestSupport.pcm16Buffer(3000, 10), 3_000_000L, 1))

        assertTrue(
            "输入 PTS 大跳变应主动调用 delegate.handleDiscontinuity",
            fake.discontinuityCalls > before,
        )
    }

    @Test
    fun `pendingReplay 连续零消费时自愈不冻结`() {
        val (controller, sink, fake) = rig()
        val w = window(100, 200)
        assertTrue(
            controller.seedRing(
                w,
                PcmTestSupport.bytesOf(PcmTestSupport.pcm16Buffer(100, 100)),
                PcmTestSupport.SAMPLE_RATE,
                1,
            ).isInstalled,
        )
        // delegate 永远消费 0 帧：制造 pendingReplay=true / lastPump=0 的死锁
        fake.maxFramesPerCall = 0
        sink.playToEndOfStream()
        assertTrue(controller.isReplaying)

        repeat(25) { sink.isEnded() }

        assertFalse("零消费必须自愈退出 REPLAYING", controller.isReplaying)
        assertTrue("应记一次自愈", controller.engine.replaySelfHealCount >= 1L)

        // 恢复 delegate 后必须还能继续输出
        fake.maxFramesPerCall = Int.MAX_VALUE
        val before = fake.feeds.size
        assertTrue(sink.handleBuffer(PcmTestSupport.pcm16Buffer(100, 10), 100_000L, 1))
        assertTrue("自愈后必须恢复输出", fake.feeds.size > before)
    }

    @Test
    fun `REPLAYING 中 seek 回前奏转捕获且有输出`() {
        val w = window(1000, 2000)
        val (controller, sink, fake) = rig(w)
        assertTrue(
            controller.seedRing(
                w,
                PcmTestSupport.bytesOf(PcmTestSupport.pcm16Buffer(1000, 1000)),
                PcmTestSupport.SAMPLE_RATE,
                1,
            ).isInstalled,
        )
        sink.playToEndOfStream()
        assertTrue(controller.isReplaying)
        val before = fake.feeds.size

        sink.handleDiscontinuity()
        assertTrue(
            sink.handleBuffer(
                PcmTestSupport.pcm16Buffer(900, 10),
                900_000L,
                1,
            )
        )

        assertFalse("前奏输入必须退出重放", controller.isReplaying)
        val feed = fake.feeds.drop(before).firstOrNull { it.ptsUs >= 900_000L }
        assertTrue("前奏必须有输出，不能空泵卡死", feed != null)
        assertEquals(900_000L, feed!!.ptsUs)
        assertEquals(10, feed.frames)
    }

    @Test
    fun `seed 安装在区间内后连续输入不静音且相位不跳`() {
        val (controller, sink, fake) = rig()
        val w = window(100, 200)
        assertTrue(sink.handleBuffer(PcmTestSupport.pcm16Buffer(0, 150), 0L, 1))
        assertFalse(controller.isRingComplete)

        assertTrue(
            controller.seedRing(
                w,
                PcmTestSupport.bytesOf(PcmTestSupport.pcm16Buffer(100, 100)),
                PcmTestSupport.SAMPLE_RATE,
                1,
            ).isInstalled,
        )
        assertEquals("应按最近输入 end=150 对齐", 150_000L, controller.engine.currentPositionUs())
        assertTrue(controller.isReplaying)
        val framesBefore = fake.feeds.sumOf { it.frames }

        repeat(10) { i ->
            val frame = 150 + i * 10
            assertTrue(
                "seed 后第 " + i + " 个输入必须正常消费",
                sink.handleBuffer(
                    PcmTestSupport.pcm16Buffer(frame, 10),
                    frame * 1000L,
                    1,
                ),
            )
        }

        val framesAfter = fake.feeds.sumOf { it.frames }
        assertTrue("安装后必须有 delegate 输出（不静音）", framesAfter > framesBefore)
        val replay = fake.feeds.firstOrNull { it.ptsUs >= 150_000L }
        assertTrue("应有重放输出", replay != null)
        assertEquals(150_000L, replay!!.ptsUs)
        assertArrayEquals(
            "输出应从环内 50 帧(源 150)开始",
            PcmTestSupport.bytesOf(PcmTestSupport.pcm16Buffer(150, 10)),
            replay.bytes.copyOfRange(0, 20),
        )
    }

    @Test
    fun `前奏中 seed 后 seek 到区间内不被拉回 A`() {
        val w = window(1000, 2000)
        val (controller, sink, fake) = rig(w)
        assertTrue(sink.handleBuffer(PcmTestSupport.pcm16Buffer(0, 500), 0L, 1))

        assertTrue(
            controller.seedRing(
                w,
                PcmTestSupport.bytesOf(PcmTestSupport.pcm16Buffer(1000, 1000)),
                PcmTestSupport.SAMPLE_RATE,
                1,
            ).isInstalled,
        )
        assertEquals("前奏中 seed 不应提前进重放", LoopPcmEngine.State.CAPTURING, controller.engine.state)
        val afterSeed = fake.feeds.size

        assertTrue(
            sink.handleBuffer(
                PcmTestSupport.pcm16Buffer(1500, 10),
                1_500_000L,
                1,
            )
        )

        assertTrue("seek 后应进入重放", controller.isReplaying)
        assertTrue(
            "位置不得被打回 A",
            controller.engine.currentPositionUs() >= 1_500_000L,
        )
        val replay = fake.feeds.drop(afterSeed).firstOrNull { it.ptsUs >= 1_500_000L }
        assertTrue("应有重放输出", replay != null)
        assertEquals(1_500_000L, replay!!.ptsUs)
        assertArrayEquals(
            "输出应来自环内 1500 帧",
            PcmTestSupport.bytesOf(PcmTestSupport.pcm16Buffer(1500, 10)),
            replay.bytes.copyOfRange(0, 20),
        )
    }

    @Test
    fun `重放中再次 seed 被拒绝但输出不静音`() {
        val (controller, sink, fake) = rig()
        val w = window(100, 200)
        assertTrue(
            controller.seedRing(
                w,
                PcmTestSupport.bytesOf(PcmTestSupport.pcm16Buffer(100, 100)),
                PcmTestSupport.SAMPLE_RATE,
                1,
            ).isInstalled,
        )
        sink.playToEndOfStream()
        assertTrue(controller.isReplaying)
        val framesBefore = fake.feeds.sumOf { it.frames }

        val busy = controller.seedRing(
            w,
            PcmTestSupport.bytesOf(PcmTestSupport.pcm16Buffer(100, 100)),
            PcmTestSupport.SAMPLE_RATE,
            1,
        )
        assertEquals(LoopPcmEngine.SeedStatus.REJECTED_BUSY, busy.status)
        assertTrue(sink.handleBuffer(PcmTestSupport.pcm16Buffer(250, 10), 250_000L, 1))
        assertTrue("旧 ring 必须继续输出", fake.feeds.sumOf { it.frames } > framesBefore)
    }

    @Test
    fun `debug PCM tap 落 WAV 且数据长度正确`() {
        val file = File.createTempFile("loop-tap-", ".wav")
        val sidecar = File(file.parentFile, file.name + ".jsonl")
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

            val lines = sidecar.readLines()
            assertTrue(
                "window=null 时 run 的 aMs/bMs 应为 null",
                lines.any {
                    it.contains("\"ev\":\"run\"") &&
                        it.contains("\"aMs\":null") &&
                        it.contains("\"bMs\":null")
                },
            )
        } finally {
            file.delete()
            sidecar.delete()
        }
    }
}
