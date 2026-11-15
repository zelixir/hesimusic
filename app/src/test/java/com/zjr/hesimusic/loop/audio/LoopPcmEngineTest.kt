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

class LoopPcmEngineTest {

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

    private fun window(aMs: Long, bMs: Long): LoopWindow =
        LoopWindow(songId = 1L, loopStartUs = aMs * 1000L, loopEndUs = bMs * 1000L)

    private fun chunkBytes(engine: LoopPcmEngine, chunk: LoopPcmEngine.ReplayChunk): ByteArray {
        val dst = ByteBuffer.allocate(chunk.byteLength).order(ByteOrder.LITTLE_ENDIAN)
        engine.copyChunk(chunk, dst)
        dst.flip()
        return PcmTestSupport.bytesOf(dst)
    }

    private fun pcmBytes(startFrame: Int, frameCount: Int): ByteArray =
        PcmTestSupport.bytesOf(PcmTestSupport.pcm16Buffer(startFrame, frameCount))

    // ---------------------------------------------------------------- 切帧精度

    @Test
    fun `按 PTS 精确切帧 A B 落在 buffer 中间时 0 帧误差`() {
        val engine = newEngine()
        // A=123456us,B=234567us @1kHz -> aFrame=124,bFrame=235,L=111 帧
        engine.setWindow(LoopWindow(1L, 123_456L, 234_567L))
        val src = PcmTestSupport.pcm16Buffer(0, 500)

        val plan = engine.onBuffer(0L, 500, src)

        assertEquals("转发帧数应裁到 B 的 ceil 帧号 235", 235, plan.forwardFrames)
        assertEquals("转发字节数", 235 * 2, plan.forwardLength)
        assertTrue("本 buffer 越过 B,消费完应进入重放", plan.enterReplayWhenDrained)
        assertEquals("ring 应捕获 [124,235) 共 111 帧", 111L, engine.capturedFrames)
        assertTrue("捕获完整后 ring 应标记完整", engine.isRingComplete)

        engine.onInputDrained(plan)
        assertEquals("消费完应切到重放态", LoopPcmEngine.State.REPLAYING, engine.state)

        val chunk = engine.pumpChunk(1 shl 20)
        assertNotNull("重放应产生 chunk", chunk)
        assertEquals("重放帧数 = ring 长度", 111, chunk!!.frames)
        assertEquals("重放首样本 PTS 应为 B", 234_567L, chunk.ptsUs)
        assertArrayEquals(
            "ring 内容应严格是源 PCM 的 [124,235) 帧",
            pcmBytes(124, 111),
            chunkBytes(engine, chunk),
        )
    }

    @Test
    fun `buffer 起点已在区间内时按 PTS 定位捕获偏移`() {
        val engine = newEngine()
        engine.setWindow(LoopWindow(1L, 123_456L, 234_567L))
        val src = PcmTestSupport.pcm16Buffer(110, 200)
        val pts = PcmTestSupport.ptsOfFrame(110)

        val plan = engine.onBuffer(pts, 200, src)

        assertEquals("转发应从 110 帧到 B(235),共 125 帧", 125, plan.forwardFrames)
        assertEquals("ring 仍应捕获完整的 [124,235)", 111L, engine.capturedFrames)
        engine.onInputDrained(plan)
        val chunk = engine.pumpChunk(1 shl 20)!!
        assertArrayEquals(
            "捕获偏移应随 buffer PTS 正确平移",
            pcmBytes(124, 111),
            chunkBytes(engine, chunk),
        )
    }

    @Test
    fun `buffer 恰好结束在 B 也应进入重放`() {
        val engine = newEngine()
        engine.setWindow(window(100, 200))
        val src = PcmTestSupport.pcm16Buffer(0, 200)

        val plan = engine.onBuffer(0L, 200, src)
        assertTrue("结束于 B 的最后一个 buffer 也要触发重放", plan.enterReplayWhenDrained)
        assertEquals("B 样本不转发", 200, plan.forwardFrames)

        engine.onInputDrained(plan)
        assertEquals("应进入重放", LoopPcmEngine.State.REPLAYING, engine.state)

        val chunk = engine.pumpChunk(1 shl 20)!!
        assertEquals("PTS < B 的常规进入应从环内 0 开始", 0L, chunk.frameOffsetInRound)
        assertEquals("常规进入轮次为 1", 1L, chunk.round)
        assertEquals("常规进入首 chunk PTS = B", 200_000L, chunk.ptsUs)
    }

    @Test
    fun `多 buffer 拼接捕获完整 ring`() {
        val engine = newEngine()
        engine.setWindow(window(100, 200))

        for (start in 0 until 200 step 10) {
            val src = PcmTestSupport.pcm16Buffer(start, 10)
            val plan = engine.onBuffer(PcmTestSupport.ptsOfFrame(start.toLong()), 10, src)
            assertEquals("B 之前的 buffer 应全部转发", 10, plan.forwardFrames)
            engine.onInputDrained(plan)
        }

        assertEquals("20 个 10ms buffer 后 ring 应完整", 100L, engine.capturedFrames)
        assertTrue(engine.isRingComplete)
        assertEquals(LoopPcmEngine.State.REPLAYING, engine.state)

        val chunk = engine.pumpChunk(1 shl 20)!!
        assertArrayEquals("ring 内容应为源 PCM 的 [100,200)", pcmBytes(100, 100), chunkBytes(engine, chunk))
    }

    // ---------------------------------------------------------------- 重放/漂移

    @Test
    fun `跨三轮重放帧数严格等于 3L 且逐轮逐位相同 PTS 零漂移`() {
        val engine = newEngine()
        engine.setWindow(window(100, 200))
        val src = PcmTestSupport.pcm16Buffer(0, 250)
        val plan = engine.onBuffer(0L, 250, src)
        engine.onInputDrained(plan)

        val all = ByteArrayOutputStream()
        var totalFrames = 0L
        var lastPts = -1L
        val expectedRing = pcmBytes(100, 100)

        while (totalFrames < 300L) {
            val chunk = engine.pumpChunk(40)
            assertNotNull("累计不到 3L 时应持续产生 chunk", chunk)
            chunk!!
            assertEquals(
                "第 k 轮起点 = B+(k-1)L+环内偏移",
                200_000L + totalFrames * 1000L,
                chunk.ptsUs,
            )
            assertTrue("重放 PTS 必须单调递增", chunk.ptsUs > lastPts)
            assertEquals(
                "chunk 的轮内偏移应等于累计帧 mod L",
                totalFrames % 100L,
                chunk.frameOffsetInRound,
            )
            assertEquals(
                "chunk 的轮次应从 1 开始",
                totalFrames / 100L + 1L,
                chunk.round,
            )
            all.write(chunkBytes(engine, chunk))
            engine.onReplayChunkConsumed(chunk.byteLength)
            totalFrames += chunk.frames.toLong()
            lastPts = chunk.ptsUs
        }

        assertEquals("三轮累计输出帧数应严格 = 3L", 300L, totalFrames)
        assertEquals("引擎累计重放帧数", 300L, engine.totalReplayFrames)
        assertEquals("三轮应正好折返 3 次", 3L, engine.seamCount)

        val allBytes = all.toByteArray()
        assertArrayEquals("第 1 轮 PCM", expectedRing, allBytes.copyOfRange(0, 200))
        assertArrayEquals("第 2 轮应与第 1 轮逐位相同", expectedRing, allBytes.copyOfRange(200, 400))
        assertArrayEquals("第 3 轮应与第 1 轮逐位相同", expectedRing, allBytes.copyOfRange(400, 600))
    }

    @Test
    fun `A B 不在帧边界时轮次 PTS 按帧数反算而非 B-A`() {
        val engine = newEngine()
        // L=111 帧 -> 111000us，与 B-A=111111us 不同
        engine.setWindow(LoopWindow(1L, 123_456L, 234_567L))
        val plan = engine.onBuffer(0L, 500, PcmTestSupport.pcm16Buffer(0, 500))
        engine.onInputDrained(plan)

        val first = engine.pumpChunk(1 shl 20)!!
        assertEquals("首轮 PTS = B", 234_567L, first.ptsUs)
        engine.onReplayChunkConsumed(first.byteLength)

        val second = engine.pumpChunk(1 shl 20)!!
        assertEquals(
            "第二轮 PTS = B + 帧量化后的 L(111000us)",
            234_567L + 111_000L,
            second.ptsUs,
        )
    }

    @Test
    fun `部分消费 chunk 后引擎位置只按已确认帧前进`() {
        val engine = newEngine()
        engine.setWindow(window(100, 200))
        val plan = engine.onBuffer(0L, 250, PcmTestSupport.pcm16Buffer(0, 250))
        engine.onInputDrained(plan)

        val chunk = engine.pumpChunk(40)!!
        assertEquals("首 chunk 从 B 开始", 200_000L, engine.currentPositionUs())
        engine.onReplayChunkConsumed(10) // 5 帧
        assertEquals(
            "部分消费后位置 = B + 5 帧，不能重复累加",
            205_000L,
            engine.currentPositionUs(),
        )
        assertEquals("chunk 未确认完时不再产生新 chunk", null, engine.pumpChunk(40))
        engine.onReplayChunkConsumed(chunk.byteLength - 10)
        assertEquals("整段确认后位置 = B + 20 帧", 220_000L, engine.currentPositionUs())
    }

    // ---------------------------------------------------------------- 生命周期

    @Test
    fun `flush 退出重放但保留 ring 可直接续播`() {
        val engine = newEngine()
        engine.setWindow(window(100, 200))
        val plan = engine.onBuffer(0L, 250, PcmTestSupport.pcm16Buffer(0, 250))
        engine.onInputDrained(plan)
        assertEquals(LoopPcmEngine.State.REPLAYING, engine.state)

        engine.onFlush()

        assertEquals("flush 后应退出重放", LoopPcmEngine.State.CAPTURING, engine.state)
        assertTrue("flush 不应清掉 ring 完整性", engine.isRingComplete)
        assertEquals("ring 帧数保留", 100L, engine.ringFrames)

        // 从 B 之后的输入重新进入重放，无需重新捕获。
        val resumePlan = engine.onBuffer(200_000L, 10, PcmTestSupport.pcm16Buffer(200, 10))
        assertTrue(resumePlan.enterReplayWhenDrained)
        engine.onInputDrained(resumePlan)
        assertEquals("应重新进入重放", LoopPcmEngine.State.REPLAYING, engine.state)
        val chunk = engine.pumpChunk(1 shl 20)!!
        assertEquals("重放首样本 PTS 仍为 B", 200_000L, chunk.ptsUs)
    }

    @Test
    fun `源端 EOS 时 ring 完整但最后 buffer 停在 B 前一帧也能进入重放`() {
        val engine = newEngine()
        engine.setWindow(window(100, 200))
        val plan = engine.onBuffer(0L, 250, PcmTestSupport.pcm16Buffer(0, 250))
        engine.onInputDrained(plan)
        engine.onFlush()
        assertTrue(engine.isRingComplete)

        // 从 190 帧播放到 199 帧（最后一帧停在 B 之前），没有触发 crossedB
        val tail = engine.onBuffer(190_000L, 9, PcmTestSupport.pcm16Buffer(190, 9))
        assertFalse("结束于 B 前一帧的 buffer 不触发 crossedB", tail.enterReplayWhenDrained)
        engine.onInputDrained(tail)
        assertEquals("仍应处于捕获态", LoopPcmEngine.State.CAPTURING, engine.state)

        assertTrue("源端 EOS 应兜底进入重放", engine.onInputEnded())
        assertEquals(LoopPcmEngine.State.REPLAYING, engine.state)
        val chunk = engine.pumpChunk(1 shl 20)!!
        assertEquals("兜底重放首样本 PTS = B", 200_000L, chunk.ptsUs)
    }

    @Test
    fun `窗口变更重建 ring 且旧内容失效`() {
        val engine = newEngine()
        engine.setWindow(window(100, 200))
        val plan = engine.onBuffer(0L, 250, PcmTestSupport.pcm16Buffer(0, 250))
        engine.onInputDrained(plan)
        val firstChunk = engine.pumpChunk(40)!!
        engine.onReplayChunkConsumed(firstChunk.byteLength)

        engine.setWindow(window(150, 250))

        assertEquals("窗口变更后应退出环回态", LoopPcmEngine.State.PASS_THROUGH, engine.state)
        assertFalse("窗口变更后 ring 应失效", engine.isRingComplete)
        assertEquals("窗口变更后旧 ring 立即释放", 0L, engine.ringFrames)
        assertEquals("新窗口捕获进度清零", 0L, engine.capturedFrames)

        val newPlan = engine.onBuffer(0L, 300, PcmTestSupport.pcm16Buffer(0, 300))
        assertEquals("新窗口 B=250，转发应裁到 250 帧", 250, newPlan.forwardFrames)
        assertEquals("新窗口应捕获 [150,250)", 100L, engine.capturedFrames)
        assertEquals("新窗口 ring 帧数", 100L, engine.ringFrames)
        engine.onInputDrained(newPlan)
        val chunk = engine.pumpChunk(1 shl 20)!!
        assertEquals("新窗口首轮 PTS = 新 B", 250_000L, chunk.ptsUs)
        assertArrayEquals("新窗口 ring 内容", pcmBytes(150, 100), chunkBytes(engine, chunk))
    }

    @Test
    fun `ring 不完整时降级 且 flush 后可回补缺口`() {
        val engine = newEngine()
        engine.setWindow(window(100, 200))

        // 从区间中部 seek 进来：只捕获到 [150,200)
        val partial = engine.onBuffer(150_000L, 100, PcmTestSupport.pcm16Buffer(150, 100))
        assertEquals("转发应裁到 B(200)", 50, partial.forwardFrames)
        engine.onInputDrained(partial)

        assertFalse("只捕获一半时 ring 不完整", engine.isRingComplete)
        assertFalse("ring 不完整时应降级为不可环回", engine.isLoopConfigured())
        assertTrue("应计入降级", engine.degradeCount >= 1L)
        assertEquals("不应进入重放", LoopPcmEngine.State.PASS_THROUGH, engine.state)

        // 服务层 seek 回 A 之前补缺口
        engine.onFlush()
        val fill = engine.onBuffer(100_000L, 50, PcmTestSupport.pcm16Buffer(100, 50))
        assertEquals("补前半段应全部转发", 50, fill.forwardFrames)
        engine.onInputDrained(fill)
        assertTrue("补齐后 ring 应完整", engine.isRingComplete)
        assertEquals("捕获帧数", 100L, engine.capturedFrames)

        val atB = engine.onBuffer(200_000L, 10, PcmTestSupport.pcm16Buffer(200, 10))
        assertTrue("B 之后的输入触发重放", atB.enterReplayWhenDrained)
        engine.onInputDrained(atB)
        assertEquals("补齐后可重新进入重放", LoopPcmEngine.State.REPLAYING, engine.state)
        val chunk = engine.pumpChunk(1 shl 20)!!
        assertEquals("重放首样本 PTS = B", 200_000L, chunk.ptsUs)
        assertArrayEquals("回补后 ring 内容完整", pcmBytes(100, 100), chunkBytes(engine, chunk))
    }

    @Test
    fun `ring 超过 300 秒上限时降级且不重复计数`() {
        val engine = newEngine()
        engine.setWindow(LoopWindow(1L, 0L, 300_000_001L))

        assertFalse("超过 300s 应不可环回", engine.isLoopConfigured())
        assertEquals("降级计数 +1", 1L, engine.degradeCount)
        assertFalse("ring 不完整", engine.isRingComplete)

        assertFalse("重复检查不应重复计数", engine.isLoopConfigured())
        assertEquals("降级计数仍为 1", 1L, engine.degradeCount)
    }

    @Test
    fun `ring 超过 64MB 上限时降级`() {
        val engine = newEngine(sampleRate = 48_000, bytesPerFrame = 32)
        // 200s * 48000 * 32B = 307MB > 64MB
        engine.setWindow(LoopWindow(1L, 0L, 200_000_000L))

        assertFalse("超过 64MB 应不可环回", engine.isLoopConfigured())
        assertEquals("降级计数 +1", 1L, engine.degradeCount)
    }

    @Test
    fun `区间不足一帧时降级`() {
        val engine = newEngine()
        // @1kHz 下 A=100us、B=200us 都落在第 1 帧之前 -> L=0
        engine.setWindow(LoopWindow(1L, 100L, 200L))

        assertFalse("不足一帧应不可环回", engine.isLoopConfigured())
        assertEquals("降级计数 +1", 1L, engine.degradeCount)
    }

    @Test
    fun `降级回调目标为轮起点减 250ms 且同一事件只回调一次`() {
        val engine = newEngine()
        engine.setWindow(window(1000, 2000))
        val targets = mutableListOf<Long>()
        engine.onDegrade = { targets.add(it) }

        // 从区间中部进来：只捕获 [1500,2000) 共 500 帧（缺前 500 帧）
        val partial = engine.onBuffer(1_500_000L, 1000, PcmTestSupport.pcm16Buffer(1500, 1000))
        engine.onInputDrained(partial)

        assertEquals("降级应回调一次", 1, targets.size)
        assertEquals("target = A - 250ms", 750_000L, targets[0])
        assertEquals("降级计数 +1", 1L, engine.degradeCount)

        // 同一事件重复消费不应重复回调
        engine.onInputDrained(partial)
        assertEquals("同一降级事件只回调一次", 1, targets.size)

        // flush 重新 arm 后再次失败，应再次回调
        engine.onFlush()
        val atB = engine.onBuffer(2_000_000L, 10, PcmTestSupport.pcm16Buffer(2000, 10))
        engine.onInputDrained(atB)
        assertEquals("flush 后应可再次触发", 2, targets.size)
        assertEquals("目标值仍为 A - 250ms", 750_000L, targets[1])
    }

    @Test
    fun `预分配 ring 后首次 onBuffer 不在音频线程分配`() {
        val fmt = LoopPcmEngine.Format(
            sampleRate = 1000,
            channelCount = 1,
            bytesPerFrame = 2,
            pcmIsFloat = false,
        )
        val w = window(100, 200)
        val engine = LoopPcmEngine()
        engine.requirePreallocation()
        engine.configure(fmt)
        engine.setWindow(w)

        assertTrue("预分配未完成时应为 pending", engine.isPreallocationPending())
        val plan = LoopPcmEngine.planRing(w, fmt)!!
        assertEquals("ring 预算 100 帧 * 2B", 200L, plan.byteSize)
        assertTrue(engine.offerPreallocatedRing(w, fmt, ByteArray(plan.byteSize.toInt())))
        assertFalse("offer 后不应再 pending", engine.isPreallocationPending())
        assertEquals("ring 帧数应已就绪", 100L, engine.ringFrames)

        val inputPlan = engine.onBuffer(0L, 250, PcmTestSupport.pcm16Buffer(0, 250))
        assertEquals("应正常捕获", 100L, engine.capturedFrames)
        assertTrue("ring 应完整", engine.isRingComplete)
        assertEquals("音频线程不得再分配 ring", 0L, engine.audioThreadAllocationCount)
        assertTrue("应进入重放", inputPlan.enterReplayWhenDrained)
        engine.onInputDrained(inputPlan)
        assertEquals(LoopPcmEngine.State.REPLAYING, engine.state)
    }

    @Test
    fun `预分配未完成时引擎保持 pending 且音频线程不分配`() {
        val fmt = LoopPcmEngine.Format(
            sampleRate = 1000,
            channelCount = 1,
            bytesPerFrame = 2,
            pcmIsFloat = false,
        )
        val engine = LoopPcmEngine()
        engine.requirePreallocation()
        engine.configure(fmt)
        engine.setWindow(window(100, 200))

        assertTrue(engine.isPreallocationPending())
        assertFalse(engine.isLoopConfigured())
        val plan = engine.onBuffer(0L, 250, PcmTestSupport.pcm16Buffer(0, 250))
        assertFalse("pending 时不应进入环回捕获", plan.enterReplayWhenDrained)
        assertEquals("pending 时音频线程不得分配", 0L, engine.audioThreadAllocationCount)
        assertEquals("pending 时不应捕获", 0L, engine.capturedFrames)
    }

    @Test
    fun `ring 完整时按输入 PTS 折返相位初始化重放游标`() {
        val engine = newEngine()
        engine.setWindow(window(100, 200))
        val plan = engine.onBuffer(0L, 250, PcmTestSupport.pcm16Buffer(0, 250))
        engine.onInputDrained(plan)
        engine.onFlush()
        assertTrue(engine.isRingComplete)

        // 输入 PTS = B + 12345 帧；L=100 帧 -> 环内相位 45 帧，轮次 124
        val startFrame = 200 + 12345
        val ptsUs = startFrame * 1000L
        val seekPlan = engine.onBuffer(ptsUs, 10, PcmTestSupport.pcm16Buffer(startFrame, 10))

        assertEquals("应直接丢弃该输入并进入重放", 0, seekPlan.forwardFrames)
        assertTrue(seekPlan.enterReplayWhenDrained)
        assertEquals("环内游标 = (PTS-A) mod L", 45L, seekPlan.entryCursorFrames)
        assertEquals("轮次应保证 PTS 连续", 124L, seekPlan.entryRound)

        engine.onInputDrained(seekPlan)
        assertEquals(LoopPcmEngine.State.REPLAYING, engine.state)
        assertEquals(
            "引擎位置应等于输入 PTS",
            ptsUs,
            engine.currentPositionUs(),
        )

        val chunk = engine.pumpChunk(1 shl 20)!!
        assertEquals("首个 chunk 从环内 45 帧开始", 45L, chunk.frameOffsetInRound)
        assertEquals("轮次 124", 124L, chunk.round)
        assertEquals("首个 chunk PTS = 输入 PTS", ptsUs, chunk.ptsUs)
        assertEquals("播放剩余 55 帧后折返", 55, chunk.frames)
    }

    @Test
    fun `ring 不完整时相位保持 0 且仍然降级`() {
        val engine = newEngine()
        engine.setWindow(window(100, 200))

        // 先只捕获 [150,200)，ring 不完整
        val partial = engine.onBuffer(150_000L, 100, PcmTestSupport.pcm16Buffer(150, 100))
        engine.onInputDrained(partial)
        assertFalse(engine.isRingComplete)
        engine.onFlush()
        assertFalse(engine.isRingComplete)

        val startFrame = 200 + 12345
        val seekPlan = engine.onBuffer(
            startFrame * 1000L,
            10,
            PcmTestSupport.pcm16Buffer(startFrame, 10),
        )
        assertEquals("ring 不完整时不得套用 PTS 相位", 0L, seekPlan.entryCursorFrames)
        assertEquals("ring 不完整时轮次保持 1", 1L, seekPlan.entryRound)

        engine.onInputDrained(seekPlan)
        assertEquals("ring 不完整应维持降级，不进入重放", LoopPcmEngine.State.PASS_THROUGH, engine.state)
        assertFalse(engine.state == LoopPcmEngine.State.REPLAYING)
    }

    @Test
    fun `重放开始与每次折返都触发 seam 回调`() {
        val engine = newEngine()
        engine.setWindow(window(100, 200))
        val seams = mutableListOf<Triple<Long, Long, Long>>()
        engine.onSeam = { iter, outFrames, ptsUs -> seams.add(Triple(iter, outFrames, ptsUs)) }

        val plan = engine.onBuffer(0L, 250, PcmTestSupport.pcm16Buffer(0, 250))
        engine.onInputDrained(plan)

        assertEquals("重放开始应有一条 seam", 1, seams.size)
        assertEquals("首条 iter=1", 1L, seams[0].first)
        assertEquals("首条 outFrames=0", 0L, seams[0].second)
        assertEquals("首条 ptsUs=B", 200_000L, seams[0].third)

        var total = 0L
        while (total < 200L) {
            val chunk = engine.pumpChunk(40)!!
            engine.onReplayChunkConsumed(chunk.byteLength)
            total += chunk.frames.toLong()
        }

        assertEquals("两轮折返后共 3 条 seam（含首条）", 3, seams.size)
        assertEquals("第二次 seam iter=2", 2L, seams[1].first)
        assertEquals("第二次 outFrames=L", 100L, seams[1].second)
        assertEquals("第二次 ptsUs=B+L", 300_000L, seams[1].third)
        assertEquals("第三次 seam iter=3", 3L, seams[2].first)
        assertEquals("第三次 outFrames=2L", 200L, seams[2].second)
        assertEquals("第三次 ptsUs=B+2L", 400_000L, seams[2].third)
    }

    @Test
    fun `release 后同格式 configure 可恢复环回能力`() {
        val fmt = LoopPcmEngine.Format(
            sampleRate = 1000,
            channelCount = 1,
            bytesPerFrame = 2,
            pcmIsFloat = false,
        )
        val engine = LoopPcmEngine()
        engine.configure(fmt)
        engine.setWindow(window(100, 200))
        val plan = engine.onBuffer(0L, 250, PcmTestSupport.pcm16Buffer(0, 250))
        engine.onInputDrained(plan)
        assertEquals(LoopPcmEngine.State.REPLAYING, engine.state)

        engine.onRelease()
        assertFalse("release 后不可环回", engine.isLoopConfigured())

        engine.configure(fmt)
        assertTrue("同格式 configure 应恢复环回能力", engine.isLoopConfigured())
        assertTrue("ring 保留", engine.isRingComplete)

        val atB = engine.onBuffer(200_000L, 10, PcmTestSupport.pcm16Buffer(200, 10))
        assertTrue(atB.enterReplayWhenDrained)
        engine.onInputDrained(atB)
        assertEquals("应能重新进入重放", LoopPcmEngine.State.REPLAYING, engine.state)
        assertEquals("重放首样本 PTS = B", 200_000L, engine.pumpChunk(1 shl 20)!!.ptsUs)
    }

    @Test
    fun `window 为 null 时纯直通`() {
        val engine = newEngine()
        engine.setWindow(null)
        val src = PcmTestSupport.pcm16Buffer(0, 50)

        val plan = engine.onBuffer(0L, 50, src)

        assertFalse("直通时不应丢弃", plan.discard)
        assertEquals("直通应转发全部字节", 100, plan.forwardLength)
        assertEquals("直通不应切重放", LoopPcmEngine.State.PASS_THROUGH, engine.state)
        assertFalse(engine.isLoopConfigured())
    }

    @Test
    fun `release 后一律直通`() {
        val engine = newEngine()
        engine.setWindow(window(100, 200))
        val plan = engine.onBuffer(0L, 250, PcmTestSupport.pcm16Buffer(0, 250))
        engine.onInputDrained(plan)
        assertEquals(LoopPcmEngine.State.REPLAYING, engine.state)

        engine.onRelease()

        assertEquals("release 后退出环回", LoopPcmEngine.State.PASS_THROUGH, engine.state)
        val src = PcmTestSupport.pcm16Buffer(0, 20)
        val after = engine.onBuffer(0L, 20, src)
        assertFalse("release 后应直通", after.discard)
        assertEquals("release 后应转发全部", 40, after.forwardLength)
    }
}
