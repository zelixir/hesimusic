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

