package com.zjr.hesimusic.loop.audio

import com.zjr.hesimusic.loop.LoopWindow
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 环缓冲区解码预填（seed）单测：ring 立即完整、不依赖先捕获、prefilled 不被捕获覆盖、
 * 窗口变更失效、长度/格式校验、controller 的 pending 暂存。
 */
class LoopRingSeedTest {

    private fun window(aMs: Long, bMs: Long): LoopWindow =
        LoopWindow(songId = 1L, loopStartUs = aMs * 1000L, loopEndUs = bMs * 1000L)

    private fun newEngine(sampleRate: Int = 1000, bytesPerFrame: Int = 2): LoopPcmEngine {
        val engine = LoopPcmEngine()
        engine.configure(
            LoopPcmEngine.Format(
                sampleRate = sampleRate,
                channelCount = 1,
                bytesPerFrame = bytesPerFrame,
                pcmIsFloat = false,
            )
        )
        return engine
    }

    private fun seedBytes(startFrame: Int, frameCount: Int): ByteArray =
        PcmTestSupport.bytesOf(PcmTestSupport.pcm16Buffer(startFrame, frameCount))

    private fun chunkBytes(engine: LoopPcmEngine, chunk: LoopPcmEngine.ReplayChunk): ByteArray {
        val dst = ByteBuffer.allocate(chunk.byteLength).order(ByteOrder.LITTLE_ENDIAN)
        engine.copyChunk(chunk, dst)
        dst.flip()
        return PcmTestSupport.bytesOf(dst)
    }

    // ---------------------------------------------------------------- 引擎：seed 即完整

    @Test
    fun `seed 安装后 ring 立即完整且不依赖先捕获`() {
        val engine = newEngine()
        val w = window(100, 200)
        engine.setWindow(w)
        val pcm = seedBytes(100, 100)

        val result = engine.seedRing(w, pcm, 1000, 1)

        assertEquals(LoopPcmEngine.SeedStatus.INSTALLED, result.status)
        assertEquals(100L, result.frames)
        assertTrue(engine.isRingComplete)
        assertTrue(engine.isRingPrefilled)
        assertEquals(100L, engine.capturedFrames)
        assertEquals(100L, engine.ringFrames)
        assertEquals(0L, engine.degradeCount)
        assertEquals(LoopPcmEngine.State.CAPTURING, engine.state)

        // 没有任何播放捕获：源端 EOS 也应直接进入重放，且输出就是预填 PCM。
        assertTrue(engine.onInputEnded())
        assertEquals(LoopPcmEngine.State.REPLAYING, engine.state)
        val chunk = engine.pumpChunk(1 shl 20)
        assertNotNull(chunk)
        assertEquals(100, chunk!!.frames)
        assertArrayEquals(pcm, chunkBytes(engine, chunk))
        assertEquals(0L, engine.degradeCount)
    }

    @Test
    fun `seed 后越过 B 的输入立即进入重放并输出环内 PCM`() {
        val engine = newEngine()
        val w = window(100, 200)
        engine.setWindow(w)
        val pcm = seedBytes(100, 100)
        engine.seedRing(w, pcm, 1000, 1)

        // 只播最后 5 帧：普通捕获路径下 ring 会缺 [A, B-5ms)，这里应直接可用。
        val src = PcmTestSupport.pcm16Buffer(195, 5)
        val plan = engine.onBuffer(PcmTestSupport.ptsOfFrame(195), 5, src)
        assertEquals(5, plan.forwardFrames)
        assertTrue(plan.enterReplayWhenDrained)
        engine.onInputDrained(plan)

        assertEquals(LoopPcmEngine.State.REPLAYING, engine.state)
        val chunk = engine.pumpChunk(1 shl 20)!!
        assertArrayEquals(pcm, chunkBytes(engine, chunk))
        assertEquals(0L, engine.degradeCount)
    }

    @Test
    fun `prefilled 窗口内播放捕获不会覆盖环内 PCM`() {
        val engine = newEngine()
        val w = window(100, 200)
        engine.setWindow(w)
        val pcm = seedBytes(100, 100)
        engine.seedRing(w, pcm, 1000, 1)

        // 用完全不同的样本“覆盖” [A,B)：若允许捕获，ring 会被改写。
        val overwrite = ByteBuffer.allocate(100 * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 100) overwrite.putShort(12345)
        overwrite.flip()
        val plan = engine.onBuffer(PcmTestSupport.ptsOfFrame(100), 100, overwrite)
        assertEquals(100, plan.forwardFrames)
        assertTrue(plan.enterReplayWhenDrained)
        engine.onInputDrained(plan)

        assertEquals(LoopPcmEngine.State.REPLAYING, engine.state)
        assertEquals(100L, engine.capturedFrames)
        val chunk = engine.pumpChunk(1 shl 20)!!
        assertArrayEquals("prefilled ring 不得被捕获覆盖", pcm, chunkBytes(engine, chunk))
    }

    @Test
    fun `seed 安装在区间内时保持当前相位并立即输出`() {
        val engine = newEngine()
        val w = window(100, 200)
        engine.setWindow(w)

        // 前奏 0..49，再进入区间 120..129；ring 尚未完整
        val prelude = engine.onBuffer(0L, 50, PcmTestSupport.pcm16Buffer(0, 50))
        engine.onInputDrained(prelude)
        val inRegion = engine.onBuffer(120_000L, 10, PcmTestSupport.pcm16Buffer(120, 10))
        engine.onInputDrained(inRegion)
        assertFalse(engine.isRingComplete)
        assertEquals(LoopPcmEngine.State.CAPTURING, engine.state)

        val result = engine.seedRing(w, seedBytes(100, 100), 1000, 1)

        assertEquals(LoopPcmEngine.SeedStatus.INSTALLED, result.status)
        assertEquals("安装后应按最近输入位置(130)对齐", 130_000L, engine.currentPositionUs())
        assertEquals(LoopPcmEngine.State.REPLAYING, engine.state)
        val chunk = engine.pumpChunk(1 shl 20)!!
        assertEquals(30L, chunk.frameOffsetInRound)
        assertEquals(130_000L, chunk.ptsUs)
        assertEquals(70, chunk.frames)
        assertArrayEquals(
            "输出应来自 seed 的环内 30 帧",
            seedBytes(130, 70),
            chunkBytes(engine, chunk),
        )
    }

    @Test
    fun `前奏中 seed 后 seek 到区间内按 PTS 进入重放`() {
        val engine = newEngine()
        val w = window(1000, 2000)
        engine.setWindow(w)

        // 前奏只播到 499 帧
        val prelude = engine.onBuffer(0L, 500, PcmTestSupport.pcm16Buffer(0, 500))
        engine.onInputDrained(prelude)
        assertEquals(1000L, engine.ringFrames)
        // 后台 seed 完成时还在前奏，不应改变捕获/输出
        assertEquals(
            LoopPcmEngine.SeedStatus.INSTALLED,
            engine.seedRing(w, seedBytes(1000, 1000), 1000, 1).status,
        )
        assertEquals(LoopPcmEngine.State.CAPTURING, engine.state)

        // 随后 seek 输入跳到区间内 1500；PTS 跳变 1000 帧 > 0.5s 阈值
        val plan = engine.onBuffer(1_500_000L, 10, PcmTestSupport.pcm16Buffer(1500, 10))

        assertTrue(plan.discard)
        assertEquals(LoopPcmEngine.State.REPLAYING, engine.state)
        assertEquals("不得落回 A", 1_500_000L, engine.currentPositionUs())
        val chunk = engine.pumpChunk(1 shl 20)!!
        assertEquals(500L, chunk.frameOffsetInRound)
        assertEquals(1_500_000L, chunk.ptsUs)
        assertArrayEquals(
            "输出应来自环内 1500 帧",
            seedBytes(1500, 500),
            chunkBytes(engine, chunk),
        )
    }

    @Test
    fun `seed 安装在后 B 输入之后按相位继续`() {
        val engine = newEngine()
        val w = window(100, 200)
        engine.setWindow(w)

        // 输入整体越过 B(2530)，ring 未完整 → 原有路径降级
        val plan = engine.onBuffer(2_530_000L, 10, PcmTestSupport.pcm16Buffer(2530, 10))
        engine.onInputDrained(plan)
        assertEquals("无前缀时停在捕获", LoopPcmEngine.State.CAPTURING, engine.state)
        assertEquals("未完整不得降级", 0L, engine.degradeCount)

        val result = engine.seedRing(w, seedBytes(100, 100), 1000, 1)
        assertEquals(LoopPcmEngine.SeedStatus.INSTALLED, result.status)
        assertEquals(LoopPcmEngine.State.REPLAYING, engine.state)
        assertEquals(2_530_000L, engine.currentPositionUs())
        val chunk = engine.pumpChunk(1 shl 20)!!
        assertEquals(30L, chunk.frameOffsetInRound)
        assertEquals(2_530_000L, chunk.ptsUs)
    }

    @Test
    fun `REPLAYING 中收到前奏输入转捕获`() {
        val engine = newEngine()
        val w = window(1000, 2000)
        engine.setWindow(w)
        assertTrue(
            engine.seedRing(
                w,
                seedBytes(1000, 1000),
                1000,
                1,
            ).isInstalled,
        )
        assertTrue(engine.onInputEnded())
        assertEquals(LoopPcmEngine.State.REPLAYING, engine.state)

        assertTrue("前奏输入必须退出重放", engine.onPreludeInput(900_000L))
        assertEquals(LoopPcmEngine.State.CAPTURING, engine.state)

        // 转捕获后前奏可以正常转发，不会卡在重放空泵
        val plan = engine.onBuffer(900_000L, 10, PcmTestSupport.pcm16Buffer(900, 10))
        assertEquals(10, plan.forwardFrames)
        engine.onInputDrained(plan)
        assertEquals(LoopPcmEngine.State.CAPTURING, engine.state)
    }

    @Test
    fun `重放中 seed 被拒绝且不打断当前输出`() {
        val engine = newEngine()
        val w = window(100, 200)
        engine.setWindow(w)
        assertEquals(
            LoopPcmEngine.SeedStatus.INSTALLED,
            engine.seedRing(w, seedBytes(100, 100), 1000, 1).status,
        )
        assertTrue(engine.onInputEnded())
        assertEquals(LoopPcmEngine.State.REPLAYING, engine.state)

        val first = engine.pumpChunk(40)!!
        engine.onReplayChunkConsumed(first.byteLength)
        val posBefore = engine.currentPositionUs()

        val result = engine.seedRing(w, seedBytes(100, 100), 1000, 1)

        assertEquals(LoopPcmEngine.SeedStatus.REJECTED_BUSY, result.status)
        assertEquals("旧 ring 继续服务", LoopPcmEngine.State.REPLAYING, engine.state)
        assertEquals(posBefore, engine.currentPositionUs())
        val next = engine.pumpChunk(1 shl 20)!!
        assertEquals("PTS 连续", posBefore, next.ptsUs)
        assertTrue(next.frames > 0)
    }

    @Test
    fun `分片预填按连续前缀追加三片`() {
        val engine = newEngine()
        val w = window(100, 200)
        engine.setWindow(w)

        assertEquals(
            LoopPcmEngine.SeedStatus.INSTALLED,
            engine.seedRingRange(w, 0L, seedBytes(100, 40), 1000, 1).status,
        )
        assertEquals(40L, engine.coveredFrames)
        assertFalse(engine.isRingComplete)

        assertEquals(
            LoopPcmEngine.SeedStatus.INSTALLED,
            engine.seedRingRange(w, 40L, seedBytes(140, 30), 1000, 1).status,
        )
        assertEquals(70L, engine.coveredFrames)
        assertFalse(engine.isRingComplete)

        assertEquals(
            LoopPcmEngine.SeedStatus.INSTALLED,
            engine.seedRingRange(w, 70L, seedBytes(170, 30), 1000, 1).status,
        )
        assertEquals(100L, engine.coveredFrames)
        assertEquals(100L, engine.capturedFrames)
        assertTrue(engine.isRingComplete)

        engine.onInputEnded()
        val chunk = engine.pumpChunk(1 shl 20)!!
        assertArrayEquals(seedBytes(100, 100), chunkBytes(engine, chunk))
    }

    @Test
    fun `分片预填拒绝非连续起点`() {
        val engine = newEngine()
        val w = window(100, 200)
        engine.setWindow(w)
        engine.seedRingRange(w, 0L, seedBytes(100, 40), 1000, 1)

        val gap = engine.seedRingRange(w, 50L, seedBytes(150, 10), 1000, 1)

        assertEquals(LoopPcmEngine.SeedStatus.REJECTED_GAP, gap.status)
        assertEquals(40L, engine.coveredFrames)
        assertFalse(engine.isRingComplete)
    }

    @Test
    fun `无前缀到 B 停在捕获 首片落地即进入重放`() {
        val engine = newEngine()
        val w = window(1000, 2000)
        engine.setWindow(w)

        // 没有任何连续覆盖：越过 B 也不能凭空重放，停在捕获且不降级
        val plan = engine.onBuffer(1_990_000L, 10, PcmTestSupport.pcm16Buffer(1990, 10))
        engine.onInputDrained(plan)
        assertEquals(LoopPcmEngine.State.CAPTURING, engine.state)
        assertEquals("未完整不得降级", 0L, engine.degradeCount)
        assertFalse(engine.isRingComplete)

        // 第一片 300 帧落地后，前缀非空即可进入重放（不再等 0.5s 门槛）
        engine.seedRingRange(w, 0L, seedBytes(1000, 300), 1000, 1)
        assertEquals(300L, engine.coveredFrames)
        assertEquals(LoopPcmEngine.State.REPLAYING, engine.state)
        assertEquals("不得降级", 0L, engine.degradeCount)
    }

    @Test
    fun `覆盖耗尽请求 A+cursor 的向前续播`() {
        val engine = newEngine()
        val w = window(1000, 4000)
        engine.setWindow(w)
        val targets = mutableListOf<Long>()
        engine.onDegrade = { targets.add(it) }
        engine.seedRingRange(w, 0L, seedBytes(1000, 600), 1000, 1)

        val plan = engine.onBuffer(2_500_000L, 1500, PcmTestSupport.pcm16Buffer(2500, 1500))
        assertEquals("应转发到 B", 1_500, plan.forwardFrames)
        engine.onInputDrained(plan)
        assertEquals(LoopPcmEngine.State.REPLAYING, engine.state)

        // 从 A 播到前缀尽头：请求 seek 到 A+600ms 续播
        val chunk = engine.pumpChunk(1 shl 20)!!
        assertEquals(600, chunk.frames)
        engine.onReplayChunkConsumed(chunk.byteLength)
        assertEquals(null, engine.pumpChunk(1 shl 20))

        assertEquals(LoopPcmEngine.State.CAPTURING, engine.state)
        assertEquals("目标 = A + cursor", 1_600_000L, targets.single())
        assertEquals(1L, engine.degradeCount)
        assertEquals("续播路径不得永久封锁", false, engine.debugStateString().contains("blocked=true"))
    }

    @Test
    fun `覆盖耗尽续播后 flush 可重新进入重放`() {
        val engine = newEngine()
        val w = window(1000, 2000)
        engine.setWindow(w)
        val targets = mutableListOf<Long>()
        engine.onDegrade = { targets.add(it) }
        engine.seedRingRange(w, 0L, seedBytes(1000, 600), 1000, 1)
        val plan = engine.onBuffer(1_990_000L, 10, PcmTestSupport.pcm16Buffer(1990, 10))
        engine.onInputDrained(plan)
        assertEquals(LoopPcmEngine.State.REPLAYING, engine.state)

        val chunk = engine.pumpChunk(1 shl 20)!!
        engine.onReplayChunkConsumed(chunk.byteLength)
        assertEquals(null, engine.pumpChunk(1 shl 20))
        assertEquals(LoopPcmEngine.State.CAPTURING, engine.state)
        assertEquals(1_600_000L, targets.single())
        assertEquals(1L, engine.degradeCount)

        // 模拟协调器 seek 落地后的 flush + 补片
        engine.onFlush()
        engine.seedRingRange(w, 600L, seedBytes(1600, 400), 1000, 1)
        assertTrue(engine.isRingComplete)
        val atB = engine.onBuffer(2_000_000L, 10, PcmTestSupport.pcm16Buffer(2000, 10))
        engine.onInputDrained(atB)
        assertEquals("flush 后应可重新进入重放", LoopPcmEngine.State.REPLAYING, engine.state)
        assertEquals("续播不触发永久封锁", 1L, engine.degradeCount)
    }

    @Test
    fun `连续两次覆盖耗尽请求的续播目标单调向前`() {
        val engine = newEngine()
        val w = window(1000, 31000)
        engine.setWindow(w)
        val targets = mutableListOf<Long>()
        engine.onDegrade = { targets.add(it) }
        engine.seedRingRange(w, 0L, seedBytes(1000, 2000), 1000, 1)

        // 进入 round0 的 A 相位重放，播到前缀尽头
        engine.onInputDiscontinuity()
        assertTrue(engine.tryRephaseFromInput(1_000_000L))
        assertEquals(LoopPcmEngine.State.REPLAYING, engine.state)
        var chunk = engine.pumpChunk(1 shl 20)!!
        assertEquals(2000, chunk.frames)
        engine.onReplayChunkConsumed(chunk.byteLength)
        assertEquals(null, engine.pumpChunk(1 shl 20))
        assertEquals(3_000_000L, targets[0])
        assertEquals(LoopPcmEngine.State.CAPTURING, engine.state)

        // seek/flush 后补充前缀，再次 round0 耗尽
        engine.onFlush()
        engine.seedRingRange(w, 2000L, seedBytes(3000, 2000), 1000, 1)
        engine.onInputDiscontinuity()
        assertTrue(engine.tryRephaseFromInput(1_000_000L))
        chunk = engine.pumpChunk(1 shl 20)!!
        assertEquals(4000, chunk.frames)
        engine.onReplayChunkConsumed(chunk.byteLength)
        assertEquals(null, engine.pumpChunk(1 shl 20))

        assertEquals(listOf(3_000_000L, 5_000_000L), targets)
        assertEquals(2L, engine.degradeCount)
        assertTrue("目标必须单调向前", targets[1] > targets[0])
        assertFalse("不得进入永久封锁", engine.debugStateString().contains("blocked=true"))
    }

    @Test
    fun `重放中追加分片不重置当前游标`() {
        val engine = newEngine()
        val w = window(1000, 2000)
        engine.setWindow(w)
        engine.seedRingRange(w, 0L, seedBytes(1000, 600), 1000, 1)
        val plan = engine.onBuffer(1_990_000L, 10, PcmTestSupport.pcm16Buffer(1990, 10))
        engine.onInputDrained(plan)
        assertEquals(LoopPcmEngine.State.REPLAYING, engine.state)

        val first = engine.pumpChunk(100 * 2)!!
        assertEquals(100, first.frames)
        engine.onReplayChunkConsumed(first.byteLength)
        val posBefore = engine.currentPositionUs()
        assertEquals(2_100_000L, posBefore)

        engine.seedRingRange(w, 600L, seedBytes(1600, 400), 1000, 1)

        assertEquals("追加不得重置相位", posBefore, engine.currentPositionUs())
        assertEquals(LoopPcmEngine.State.REPLAYING, engine.state)
        val next = engine.pumpChunk(1 shl 20)!!
        assertEquals(posBefore, next.ptsUs)
        assertTrue(next.frames > 0)
    }

    @Test
    fun `窗口变更后分片覆盖归零`() {
        val engine = newEngine()
        val w = window(100, 200)
        engine.setWindow(w)
        engine.seedRingRange(w, 0L, seedBytes(100, 40), 1000, 1)
        assertEquals(40L, engine.coveredFrames)

        engine.setWindow(window(150, 250))

        assertEquals(0L, engine.coveredFrames)
        assertEquals(0L, engine.capturedFrames)
        assertFalse(engine.isRingComplete)
        assertEquals(
            LoopPcmEngine.SeedStatus.REJECTED_WINDOW,
            engine.seedRingRange(w, 0L, seedBytes(100, 40), 1000, 1).status,
        )
    }

    // ---------------------------------------------------------------- 失效

    @Test
    fun `窗口变更或清空后预填失效`() {
        val engine = newEngine()
        val w = window(100, 200)
        engine.setWindow(w)
        engine.seedRing(w, seedBytes(100, 100), 1000, 1)
        assertTrue(engine.isRingComplete)

        engine.setWindow(window(150, 250))
        assertFalse(engine.isRingComplete)
        assertFalse(engine.isRingPrefilled)
        assertEquals(LoopPcmEngine.State.PASS_THROUGH, engine.state)

        val w2 = window(150, 250)
        assertEquals(
            LoopPcmEngine.SeedStatus.INSTALLED,
            engine.seedRing(w2, seedBytes(150, 100), 1000, 1).status,
        )
        assertTrue(engine.isRingComplete)

        engine.setWindow(null)
        assertFalse(engine.isRingComplete)
        assertFalse(engine.isRingPrefilled)
    }

    // ---------------------------------------------------------------- 校验

    @Test
    fun `长度不足被拒绝且不补零`() {
        val engine = newEngine()
        val w = window(100, 200)
        engine.setWindow(w)

        val result = engine.seedRing(w, seedBytes(100, 99), 1000, 1)

        assertEquals(LoopPcmEngine.SeedStatus.REJECTED_SIZE, result.status)
        assertEquals(99L, result.frames)
        assertFalse(engine.isRingComplete)
        assertFalse(engine.isRingPrefilled)
        assertEquals(0L, engine.ringFrames)
    }

    @Test
    fun `格式不匹配被拒绝`() {
        val engine = newEngine()
        val w = window(100, 200)
        engine.setWindow(w)

        assertEquals(
            LoopPcmEngine.SeedStatus.REJECTED_FORMAT,
            engine.seedRing(w, seedBytes(100, 100), 8000, 1).status,
        )
        assertEquals(
            LoopPcmEngine.SeedStatus.REJECTED_FORMAT,
            engine.seedRing(w, seedBytes(100, 100), 1000, 2).status,
        )
        assertFalse(engine.isRingComplete)
    }

    @Test
    fun `窗口不一致被拒绝`() {
        val engine = newEngine()
        engine.setWindow(window(100, 200))

        val result = engine.seedRing(window(150, 250), seedBytes(150, 100), 1000, 1)

        assertEquals(LoopPcmEngine.SeedStatus.REJECTED_WINDOW, result.status)
        assertFalse(engine.isRingComplete)
    }

    @Test
    fun `A B 不落在帧边界时多余尾帧被丢弃`() {
        val engine = newEngine(sampleRate = 44_100)
        val w = LoopWindow(1L, 1_001_000L, 3_005_000L)
        engine.setWindow(w)
        // 严格帧数 88377，ring 长度 = 132521 − 44145 = 88376（尾帧丢弃）。
        val seeded = seedBytes(44_145, 88_377)

        val result = engine.seedRing(w, seeded, 44_100, 1)

        assertEquals(LoopPcmEngine.SeedStatus.INSTALLED, result.status)
        assertEquals(88_376L, result.frames)
        assertEquals(88_376L, engine.ringFrames)
        engine.onInputEnded()
        val chunk = engine.pumpChunk(1 shl 24)!!
        assertEquals(88_376, chunk.frames)
        assertArrayEquals(seedBytes(44_145, 88_376), chunkBytes(engine, chunk))
    }

    // ---------------------------------------------------------------- controller：pending

    private fun newController(): LoopAudioController {
        val controller = LoopAudioController()
        controller.preallocDispatcher = { it.run() }
        controller.degradeDispatcher = { it.run() }
        return controller
    }

    @Test
    fun `controller 在 configure 前暂存并在格式就绪后安装`() {
        val controller = newController()
        val w = window(100, 200)
        val pcm = seedBytes(100, 100)

        val staged = controller.seedRing(w, pcm, 1000, 1)
        assertEquals(LoopPcmEngine.SeedStatus.PENDING, staged.status)
        assertTrue(controller.hasPendingSeed)

        controller.window = w
        assertTrue("窗口写入后仍等格式", controller.hasPendingSeed)

        controller.onFormatConfigured(1000, 1, 2, false)
        assertTrue(controller.isRingComplete)
        assertTrue(controller.isRingPrefilled)
        assertFalse(controller.hasPendingSeed)
        assertEquals(0L, controller.degradeCount)
    }

    @Test
    fun `controller 在格式不一致时丢弃暂存`() {
        val controller = newController()
        val w = window(100, 200)

        assertEquals(
            LoopPcmEngine.SeedStatus.PENDING,
            controller.seedRing(w, seedBytes(100, 100), 48_000, 1).status,
        )
        assertTrue(controller.hasPendingSeed)

        controller.window = w
        controller.onFormatConfigured(1000, 1, 2, false)

        assertFalse("sampleRate 不一致应丢弃", controller.hasPendingSeed)
        assertFalse(controller.isRingComplete)
        assertFalse(controller.isRingPrefilled)
    }

    @Test
    fun `controller 在窗口变更时丢弃旧窗口的暂存`() {
        val controller = newController()
        controller.seedRing(window(100, 200), seedBytes(100, 100), 1000, 1)
        assertTrue(controller.hasPendingSeed)

        controller.window = window(150, 250)
        assertFalse(controller.hasPendingSeed)
    }

    @Test
    fun `controller seedRingRange 返回 PENDING 并在格式就绪后追加`() {
        val controller = newController()
        val w = window(100, 200)

        assertEquals(
            LoopPcmEngine.SeedStatus.PENDING,
            controller.seedRingRange(w, 0L, seedBytes(100, 20), 1000, 1).status,
        )
        assertEquals(0L, controller.coveredFrames)

        controller.onFormatConfigured(1000, 1, 2, false)
        controller.window = w
        assertEquals(
            LoopPcmEngine.SeedStatus.INSTALLED,
            controller.seedRingRange(w, 0L, seedBytes(100, 20), 1000, 1).status,
        )
        assertEquals(20L, controller.coveredFrames)

        assertEquals(
            LoopPcmEngine.SeedStatus.INSTALLED,
            controller.seedRingRange(w, 20L, seedBytes(120, 20), 1000, 1).status,
        )
        assertEquals(40L, controller.coveredFrames)
        assertFalse(controller.isRingComplete)
    }

    @Test
    fun `controller 在 configure 之后直接安装`() {
        val controller = newController()
        controller.onFormatConfigured(1000, 1, 2, false)
        val w = window(100, 200)
        controller.window = w
        val pcm = seedBytes(100, 100)

        val result = controller.seedRing(w, pcm, 1000, 1)

        assertTrue(result.isInstalled)
        assertTrue(controller.isRingComplete)
        assertTrue(controller.isRingPrefilled)
        assertFalse(controller.hasPendingSeed)
    }

    // ---------------------------------------------------------------- sink 集成

    @Test
    fun `sink 侧 seed 后越过 B 立即从环内重放`() {
        val controller = newController()
        val fake = FakeAudioSink()
        val sink = LoopAudioSink(controller, fake)
        sink.configure(PcmTestSupport.format(), 4096, null)
        val w = window(100, 200)
        controller.window = w
        val pcm = seedBytes(100, 100)
        assertTrue(controller.seedRing(w, pcm, PcmTestSupport.SAMPLE_RATE, 1).isInstalled)

        val src = PcmTestSupport.pcm16Buffer(198, 2)
        assertTrue(sink.handleBuffer(src, PcmTestSupport.ptsOfFrame(198), 1))

        assertTrue("应切入重放", controller.isReplaying)
        assertEquals("预填不应产生降级", 0L, controller.degradeCount)
        val replay = fake.feeds.filter { it.ptsUs >= 200_000L }
        assertTrue("应有重放输出", replay.isNotEmpty())
        val collected = ByteArrayOutputStream()
        for (feed in replay) {
            collected.write(feed.bytes)
            if (collected.size() >= pcm.size) break
        }
        assertArrayEquals("重放输出应为预填的环内 PCM", pcm, collected.toByteArray())
    }
}

