package com.zjr.hesimusic.service

import com.zjr.hesimusic.loop.LoopWindow
import com.zjr.hesimusic.loop.analysis.Pcm16Frames
import com.zjr.hesimusic.loop.analysis.Pcm16Segment
import com.zjr.hesimusic.loop.audio.LoopAudioController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 服务层分片预填单测（纯 JVM）：假解码器 + 真 [LoopAudioController]。
 *
 * 覆盖：前缀上限裁剪、prefixOnly 完成态、短窗口填满、进度单调、取消、失败停止。
 */
class LoopRingPrefillerTest {

    private val songId = 7L
    private val otherSongId = 9L
    private val chunkMs = 20

    private fun window(songId: Long = this.songId, aMs: Long = 100L, bMs: Long = 200L): LoopWindow =
        LoopWindow(songId = songId, loopStartUs = aMs * 1_000L, loopEndUs = bMs * 1_000L)

    /** 假解码器：按区间返回 ceil((end−start)*sr/1e6) 帧；可阻塞（从第 N 次起）/失败/gate。 */
    private class FakeDecoder(
        var sampleRate: Int = 1_000,
        var channels: Int = 1,
        /** 从第几次 decode 调用开始阻塞（1 基；默认永不阻塞）。 */
        var blockFrom: Int = Int.MAX_VALUE,
        var fail: Boolean = false,
    ) {
        val calls = mutableListOf<Triple<String, Long, Long>>()
        var started: Int = 0
        var cancelled: Int = 0
        var gate: CompletableDeferred<Unit>? = null

        suspend fun decode(path: String, startUs: Long, endUs: Long): Pcm16Segment {
            calls += Triple(path, startUs, endUs)
            started += 1
            try {
                gate?.await()
                if (started >= blockFrom) awaitCancellation()
            } catch (e: CancellationException) {
                cancelled += 1
                throw e
            }
            if (fail) throw IllegalStateException("decode boom")
            val frames = Pcm16Frames.framesForUsCeil(endUs - startUs, sampleRate)
            return Pcm16Segment(
                data = ByteArray((frames * channels * 2).toInt()),
                sampleRate = sampleRate,
                channels = channels,
                frames = frames,
            )
        }
    }

    private fun newController(sampleRate: Int = 1_000, channels: Int = 1): LoopAudioController {
        val controller = LoopAudioController()
        controller.preallocDispatcher = { it.run() }
        controller.degradeDispatcher = { it.run() }
        controller.onFormatConfigured(sampleRate, channels, channels * 2, false)
        return controller
    }

    private fun newPrefiller(
        controller: LoopAudioController,
        decoder: FakeDecoder,
        paths: Map<Long, String>? = null,
        chunkMs: Int = this.chunkMs,
        maxPrefixFrames: Long = 0L,
        maxPrefixMs: Int = 12_000,
        fullFillMaxMs: Int = 30_000,
        fillRemainingInBackground: Boolean = false,
    ): LoopRingPrefiller {
        val pathMap = paths ?: mapOf(
            songId to "/music/seven.flac",
            otherSongId to "/music/nine.flac",
        )
        return LoopRingPrefiller(
            audio = controller,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            decode = { path, startUs, endUs -> decoder.decode(path, startUs, endUs) },
            resolvePath = { id -> pathMap[id] },
            dispatcher = Dispatchers.Unconfined,
            chunkMs = chunkMs,
            maxPrefixFrames = maxPrefixFrames,
            maxPrefixMs = maxPrefixMs,
            fullFillMaxMs = fullFillMaxMs,
            fillRemainingInBackground = fillRemainingInBackground,
        )
    }

    /** Unconfined 下多数动作同步完成；yield 一次兜底让被取消的协程跑完收尾。 */
    private fun settle() {
        runBlocking { yield() }
    }

    private fun collectProgress(pre: LoopRingPrefiller): Pair<MutableList<LoopRingPrefiller.Progress>, kotlinx.coroutines.Job> {
        val seen = mutableListOf<LoopRingPrefiller.Progress>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val job = scope.launch { pre.progress.collect { seen += it } }
        return seen to job
    }

    // ---------------------------------------------------------------- 前缀上限

    @Test
    fun `长窗口只填前缀并报 prefixOnly 完成态`() {
        val controller = newController()
        val w = window(aMs = 0L, bMs = 60_000L) // 60s @1kHz = 60000 帧
        controller.window = w
        val decoder = FakeDecoder()
        val pre = newPrefiller(controller, decoder, chunkMs = 2_000, maxPrefixMs = 12_000)
        val (seen, collectJob) = collectProgress(pre)

        pre.onWindowApplied(w)
        settle()
        collectJob.cancel()

        // 只填 [A, A+12s)：6 片 × 2s。
        assertEquals(6, decoder.started)
        assertEquals(12_000L, controller.coveredFrames)
        assertFalse(controller.isRingComplete)
        val last = seen.last()
        assertTrue(last.prefixOnly)
        assertFalse(last.complete)
        assertTrue(last.targetReached)
        assertEquals(12_000L, last.totalFrames)
        assertEquals(12_000L, last.coveredFrames)
        assertEquals(1f, last.fraction, 0.0001f)
    }

    @Test
    fun `帧上限精确裁剪`() {
        val controller = newController()
        val w = window(aMs = 0L, bMs = 60_000L)
        controller.window = w
        val decoder = FakeDecoder()
        val pre = newPrefiller(controller, decoder, chunkMs = 2_000, maxPrefixFrames = 5_000L)

        pre.onWindowApplied(w)
        settle()

        assertEquals(5_000L, controller.coveredFrames)
        assertFalse(controller.isRingComplete)
        val last = pre.progress.value
        assertTrue(last.prefixOnly)
        assertEquals(5_000L, last.totalFrames)
        assertTrue(last.targetReached)
        // 一次探测（拿采样率）+ 3 片（0..2000 / 2000..4000 / 4000..5000）。
        assertEquals(4, decoder.started)
    }

    @Test
    fun `显式帧上限对短窗口同样生效`() {
        val controller = newController()
        val w = window(aMs = 0L, bMs = 30_000L)
        controller.window = w
        val decoder = FakeDecoder()
        val pre = newPrefiller(controller, decoder, chunkMs = 2_000, maxPrefixFrames = 5_000L)

        pre.onWindowApplied(w)
        settle()

        assertEquals(5_000L, controller.coveredFrames)
        assertFalse(controller.isRingComplete)
        assertTrue(pre.progress.value.prefixOnly)
        assertTrue(pre.progress.value.targetReached)
    }

    @Test
    fun `269s 窗口前缀 12s 只需 6 片`() {
        val controller = newController(sampleRate = 48_000, channels = 2)
        val w = LoopWindow(songId, 19_776_000L, 288_896_000L)
        controller.window = w
        val decoder = FakeDecoder(sampleRate = 48_000, channels = 2)
        val pre = newPrefiller(controller, decoder, chunkMs = 2_000, maxPrefixMs = 12_000)

        pre.onWindowApplied(w)
        settle()

        assertEquals(6, decoder.started)
        assertEquals(576_000L, controller.coveredFrames) // 12s × 48k
        assertFalse(controller.isRingComplete)
        val last = pre.progress.value
        assertTrue(last.prefixOnly)
        assertFalse(last.complete)
        assertEquals(576_000L, last.totalFrames)
        // UI 可直接显示「12s/12s」：进度里带 sampleRate 与 ms 换算。
        assertEquals(48_000, last.sampleRate)
        assertEquals(12_000L, last.totalMs)
        assertEquals(12_000L, last.coveredMs)
    }

    @Test
    fun `短窗口直接填满保持旧行为`() {
        val controller = newController()
        val w = window(aMs = 0L, bMs = 30_000L) // 30s ≤ fullFillMaxMs(30s)
        controller.window = w
        val decoder = FakeDecoder()
        val pre = newPrefiller(controller, decoder, chunkMs = 2_000, maxPrefixMs = 12_000)

        pre.onWindowApplied(w)
        settle()

        assertEquals(15, decoder.started)
        assertEquals(30_000L, controller.coveredFrames)
        assertTrue(controller.isRingComplete)
        val last = pre.progress.value
        assertFalse(last.prefixOnly)
        assertTrue(last.complete)
        assertEquals(30_000L, last.totalFrames)
    }

    @Test
    fun `前缀完成后可选后台填满`() {
        val controller = newController()
        val w = window(aMs = 0L, bMs = 60_000L)
        controller.window = w
        val decoder = FakeDecoder()
        val pre = newPrefiller(
            controller,
            decoder,
            chunkMs = 2_000,
            maxPrefixMs = 12_000,
            fillRemainingInBackground = true,
        )
        pre.onWindowApplied(w)
        settle()

        assertTrue(controller.isRingComplete)
        assertEquals(60_000L, controller.coveredFrames)
        // 前缀先就绪（可起播），随后后台继续填满整环。
        val last = pre.progress.value
        assertTrue(last.prefixReady)
        assertTrue(last.complete)
        assertEquals(60_000L, last.coveredFrames)
    }

    // ---------------------------------------------------------------- 进度

    @Test
    fun `进度单调且最终达到目标`() {
        val controller = newController()
        val w = window(aMs = 0L, bMs = 60_000L)
        controller.window = w
        val decoder = FakeDecoder()
        val pre = newPrefiller(controller, decoder, chunkMs = 2_000, maxPrefixMs = 12_000)
        val (seen, collectJob) = collectProgress(pre)

        pre.onWindowApplied(w)
        settle()
        collectJob.cancel()

        assertTrue("进度应至少有一次发射", seen.size >= 2)
        for (i in 1 until seen.size) {
            assertTrue(
                "coveredFrames 必须单调不减",
                seen[i].coveredFrames >= seen[i - 1].coveredFrames,
            )
            assertTrue("fraction 必须在 [0,1]", seen[i].fraction in 0f..1f)
        }
        val last = seen.last()
        assertEquals(last.totalFrames, last.coveredFrames)
        assertEquals(1f, last.fraction, 0.0001f)
    }

    // ---------------------------------------------------------------- 取消/失败

    @Test
    fun `窗口清空会取消进行中的分片并复位进度`() {
        val controller = newController()
        val w = window()
        controller.window = w
        // 第一片成功落地，第二片阻塞在解码里，等待外部取消。
        val decoder = FakeDecoder(blockFrom = 2)
        val pre = newPrefiller(controller, decoder)

        pre.onWindowApplied(w)
        assertEquals(2, decoder.started)
        assertEquals(20L, controller.coveredFrames)
        assertTrue(pre.hasActiveJobForTest)

        pre.onWindowApplied(null)
        settle()

        assertEquals("进行中的解码应被取消", 1, decoder.cancelled)
        assertEquals("取消后不再解码后续片", 2, decoder.started)
        assertFalse(pre.hasActiveJobForTest)
        assertEquals(0L, pre.progress.value.coveredFrames)
    }

    @Test
    fun `切歌（窗口 songId 变化）会取消旧任务并按新路径重新开始`() {
        val controller = newController()
        val w1 = window()
        controller.window = w1
        val decoder = FakeDecoder(blockFrom = 1)
        val pre = newPrefiller(controller, decoder)

        pre.onWindowApplied(w1)
        assertEquals(1, decoder.started)

        val w2 = window(songId = otherSongId, aMs = 300L, bMs = 400L)
        controller.window = w2
        pre.onWindowApplied(w2)
        settle()

        assertEquals(1, decoder.cancelled)
        assertEquals(2, decoder.started)
        assertEquals("/music/nine.flac", decoder.calls[1].first)
        assertEquals(300_000L, decoder.calls[1].second)
    }

    @Test
    fun `预填期间窗口变化则丢弃该片`() {
        val controller = newController()
        val w1 = window()
        controller.window = w1
        val gate = CompletableDeferred<Unit>()
        val decoder = FakeDecoder().also { it.gate = gate }
        val pre = newPrefiller(controller, decoder)

        pre.onWindowApplied(w1)
        assertEquals(1, decoder.started)

        val w2 = window(aMs = 300L, bMs = 400L)
        controller.window = w2
        gate.complete(Unit)
        settle()

        assertEquals(0L, controller.coveredFrames)
        assertFalse(controller.isRingComplete)
    }

    @Test
    fun `单片解码失败后停止且保留已灌入前缀`() {
        val controller = newController()
        val w = window()
        controller.window = w
        val decoder = FakeDecoder()
        var calls = 0
        val pre = LoopRingPrefiller(
            audio = controller,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            decode = { path, startUs, endUs ->
                calls += 1
                if (calls == 2) throw IllegalStateException("decode boom")
                decoder.decode(path, startUs, endUs)
            },
            resolvePath = { id -> mapOf(songId to "/music/seven.flac")[id] },
            dispatcher = Dispatchers.Unconfined,
            chunkMs = chunkMs,
            maxPrefixMs = 12_000,
            fullFillMaxMs = 30_000,
        )

        pre.onWindowApplied(w)
        settle()

        assertEquals(20L, controller.coveredFrames)
        assertFalse(controller.isRingComplete)

        // 同一窗口再次应用：不重试轰炸。
        pre.onWindowApplied(w)
        assertEquals(2, calls)
    }

    @Test
    fun `路径缺失时跳过且不污染 ring`() {
        val controller = newController()
        val w = window()
        controller.window = w
        val decoder = FakeDecoder()
        val pre = newPrefiller(controller, decoder, paths = emptyMap())

        pre.onWindowApplied(w)
        settle()

        assertEquals(0, decoder.started)
        assertEquals(0L, controller.coveredFrames)
        assertFalse(controller.isRingComplete)
    }

    @Test
    fun `ring 已完整时不触发预填`() {
        val controller = newController()
        val w = window()
        controller.window = w
        val first = newPrefiller(controller, FakeDecoder())
        first.onWindowApplied(w)
        settle()
        assertTrue(controller.isRingComplete)

        val decoder = FakeDecoder()
        val pre = newPrefiller(controller, decoder)
        pre.onWindowApplied(w)

        assertEquals(0, decoder.started)
        assertTrue(pre.progress.value.complete)
    }
}

