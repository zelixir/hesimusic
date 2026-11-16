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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 服务层分片预填单测（纯 JVM）：假解码器 + 真 [LoopAudioController]，验证逐片追加、
 * 取消、失败停止、窗口比较并设置。
 */
class LoopRingPrefillerTest {

    private val songId = 7L
    private val otherSongId = 9L
    private val chunkMs = 20

    private fun window(songId: Long = this.songId, aMs: Long = 100L, bMs: Long = 200L): LoopWindow =
        LoopWindow(songId = songId, loopStartUs = aMs * 1_000L, loopEndUs = bMs * 1_000L)

    /** 假解码器：按区间返回 ceil((end−start)*sr/1e6) 帧；可阻塞（取消测试）/失败/gate 控制。 */
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

    private fun newController(): LoopAudioController {
        val controller = LoopAudioController()
        controller.preallocDispatcher = { it.run() }
        controller.degradeDispatcher = { it.run() }
        controller.onFormatConfigured(1_000, 1, 2, false)
        return controller
    }

    private fun newPrefiller(
        controller: LoopAudioController,
        decoder: FakeDecoder,
        paths: Map<Long, String>? = null,
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
        )
    }

    /** Unconfined 下多数动作同步完成；yield 一次兜底让被取消的协程跑完收尾。 */
    private fun settle() {
        runBlocking { yield() }
    }

    // ---------------------------------------------------------------- 逐片追加

    @Test
    fun `窗口应用后按 2s 以下的片长逐片追加直到完整`() {
        val controller = newController()
        val w = window()
        controller.window = w
        val decoder = FakeDecoder()
        val pre = newPrefiller(controller, decoder)

        pre.onWindowApplied(w)
        settle()

        // 100ms 窗口 @1kHz = 100 帧；片长 20ms = 20 帧 → 5 片，按时间顺序推进。
        assertEquals(5, decoder.started)
        assertEquals(
            listOf(
                Triple("/music/seven.flac", 100_000L, 120_000L),
                Triple("/music/seven.flac", 120_000L, 140_000L),
                Triple("/music/seven.flac", 140_000L, 160_000L),
                Triple("/music/seven.flac", 160_000L, 180_000L),
                Triple("/music/seven.flac", 180_000L, 200_000L),
            ),
            decoder.calls,
        )
        assertEquals(100L, controller.coveredFrames)
        assertTrue(controller.isRingComplete)
        assertEquals(0L, controller.degradeCount)
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
    }

    // ---------------------------------------------------------------- 取消

    @Test
    fun `窗口清空会取消进行中的分片`() {
        val controller = newController()
        val w = window()
        controller.window = w
        // 第一片成功落地，第二片阻塞在解码里，等待外部取消。
        val decoder = FakeDecoder(blockFrom = 2)
        val pre = newPrefiller(controller, decoder)

        pre.onWindowApplied(w)
        assertTrue(pre.hasActiveJobForTest)
        // 第一片已落地，第二片正在解码（阻塞中）。
        assertEquals(2, decoder.started)
        assertEquals(20L, controller.coveredFrames)

        pre.onWindowApplied(null)
        settle()

        assertEquals("进行中的解码应被取消", 1, decoder.cancelled)
        assertEquals("取消后不再解码后续片", 2, decoder.started)
        assertFalse(pre.hasActiveJobForTest)
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

        // 解码未完成时窗口被换掉：旧片必须丢弃，不能污染新窗口的 ring。
        val w2 = window(aMs = 300L, bMs = 400L)
        controller.window = w2
        gate.complete(Unit)
        settle()

        assertEquals(0L, controller.coveredFrames)
        assertFalse(controller.isRingComplete)
    }

    // ---------------------------------------------------------------- 失败

    @Test
    fun `单片解码失败后停止且保留已灌入前缀`() {
        val controller = newController()
        val w = window()
        controller.window = w
        val decoder = FakeDecoder()
        // 第一片成功、第二片失败：只保留前 20 帧前缀。
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
}

