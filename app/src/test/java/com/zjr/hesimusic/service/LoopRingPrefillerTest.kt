package com.zjr.hesimusic.service

import com.zjr.hesimusic.loop.LoopWindow
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
 * 服务层环缓冲预填单测（纯 JVM）：可注入假解码器/假路径解析，用 [LoopAudioController] 真身验证落点。
 *
 * 覆盖：窗口应用且 ring 不完整 → 触发一次；ring 已完整 → 不触发；窗口清空/切换（含切歌）→
 * 取消旧任务；解码期间窗口变化 → 丢弃结果；失败不重试轰炸；路径缺失跳过。
 */
class LoopRingPrefillerTest {

    private val songId = 7L
    private val otherSongId = 9L

    private fun window(songId: Long = this.songId, aMs: Long = 100L, bMs: Long = 200L): LoopWindow =
        LoopWindow(songId = songId, loopStartUs = aMs * 1_000L, loopEndUs = bMs * 1_000L)

    /** 假解码器：可阻塞（取消测试）、可失败、可用 gate 控制完成时机。 */
    private class FakeDecoder(
        var frames: Long = 100L,
        var sampleRate: Int = 1_000,
        var channels: Int = 1,
        var block: Boolean = false,
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
                if (block) awaitCancellation()
            } catch (e: CancellationException) {
                cancelled += 1
                throw e
            }
            if (fail) throw IllegalStateException("decode boom")
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
        // 单测里让预分配/降级 dispatch 同步执行，保证确定性。
        controller.preallocDispatcher = { it.run() }
        controller.degradeDispatcher = { it.run() }
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
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        return LoopRingPrefiller(
            audio = controller,
            scope = scope,
            decode = { path, startUs, endUs -> decoder.decode(path, startUs, endUs) },
            resolvePath = { id -> pathMap[id] },
            dispatcher = Dispatchers.Unconfined,
        )
    }

    /** Unconfined 下多数动作同步完成；yield 一次兜底让被取消的协程跑完收尾。 */
    private fun settle() {
        runBlocking { yield() }
    }

    // ---------------------------------------------------------------- 触发/幂等

    @Test
    fun `ring 不完整时窗口应用触发一次预填`() {
        val controller = newController()
        val decoder = FakeDecoder()
        val pre = newPrefiller(controller, decoder)
        val w = window()
        // 服务层顺序：先写 audio.window，再触发预填（比较并设置依赖它）。
        controller.window = w

        pre.onWindowApplied(w)

        assertEquals("应解码一次", 1, decoder.started)
        assertEquals(Triple("/music/seven.flac", 100_000L, 200_000L), decoder.calls.single())
        // sink 尚未 configure（格式未知）→ 音频层暂存 pending，说明 seedRing 已被调用。
        assertTrue(controller.hasPendingSeed)

        // 同一窗口重复应用：不重复解码（不重试轰炸）。
        pre.onWindowApplied(w)
        assertEquals(1, decoder.started)
    }

    @Test
    fun `ring 已完整时不触发预填`() {
        val controller = newController()
        controller.onFormatConfigured(1_000, 1, 2, false)
        val w = window()
        controller.window = w
        assertTrue(controller.seedRing(w, ByteArray(200), 1_000, 1).isInstalled)
        assertTrue(controller.isRingComplete)

        val decoder = FakeDecoder()
        val pre = newPrefiller(controller, decoder)
        pre.onWindowApplied(w)

        assertEquals(0, decoder.started)
    }

    // ---------------------------------------------------------------- 取消

    @Test
    fun `窗口清空会取消进行中的预填`() {
        val controller = newController()
        val decoder = FakeDecoder(block = true)
        val pre = newPrefiller(controller, decoder)

        pre.onWindowApplied(window())
        assertEquals(1, decoder.started)
        assertTrue(pre.hasActiveJobForTest)

        pre.onWindowApplied(null)
        settle()

        assertEquals("进行中的解码应被取消", 1, decoder.cancelled)
        assertEquals("取消后不应再发起解码", 1, decoder.started)
        assertFalse(pre.hasActiveJobForTest)
    }

    @Test
    fun `窗口切换会取消旧任务并开始新的预填`() {
        val controller = newController()
        val decoder = FakeDecoder(block = true)
        val pre = newPrefiller(controller, decoder)

        pre.onWindowApplied(window(aMs = 100L, bMs = 200L))
        assertEquals(1, decoder.started)

        pre.onWindowApplied(window(aMs = 300L, bMs = 400L))
        settle()

        assertEquals(1, decoder.cancelled)
        assertEquals(2, decoder.started)
        assertEquals(300_000L, decoder.calls[1].second)
        assertEquals(400_000L, decoder.calls[1].third)
        assertTrue(pre.hasActiveJobForTest)
    }

    @Test
    fun `切歌时按新歌路径预填并取消旧任务`() {
        val controller = newController()
        val decoder = FakeDecoder(block = true)
        val pre = newPrefiller(controller, decoder)

        pre.onWindowApplied(window())
        pre.onWindowApplied(window(songId = otherSongId))
        settle()

        assertEquals(1, decoder.cancelled)
        assertEquals(2, decoder.started)
        assertEquals("/music/nine.flac", decoder.calls[1].first)
        assertEquals(otherSongId, window(songId = otherSongId).songId)
    }

    // ---------------------------------------------------------------- 落点/失败

    @Test
    fun `预填完成安装 ring 且不产生降级`() {
        val controller = newController()
        controller.onFormatConfigured(1_000, 1, 2, false)
        val w = window()
        controller.window = w
        val decoder = FakeDecoder(frames = 100L)
        val pre = newPrefiller(controller, decoder)

        pre.onWindowApplied(w)
        settle()

        assertEquals(1, decoder.started)
        assertTrue(controller.isRingComplete)
        assertTrue(controller.isRingPrefilled)
        assertFalse(controller.hasPendingSeed)
        assertEquals(0L, controller.degradeCount)
    }

    @Test
    fun `解码失败只记日志且同一窗口不重试`() {
        val controller = newController()
        val decoder = FakeDecoder(fail = true)
        val pre = newPrefiller(controller, decoder)
        val w = window()

        pre.onWindowApplied(w)
        settle()

        assertEquals(1, decoder.started)
        assertFalse(controller.isRingComplete)
        assertFalse(controller.isRingPrefilled)

        // 同一窗口再次应用（例如 row 刷新）：不重复解码。
        pre.onWindowApplied(w)
        assertEquals(1, decoder.started)
    }

    @Test
    fun `路径缺失时跳过且不污染 ring`() {
        val controller = newController()
        val decoder = FakeDecoder()
        val pre = newPrefiller(controller, decoder, paths = emptyMap())

        pre.onWindowApplied(window())
        settle()

        assertEquals(0, decoder.started)
        assertFalse(controller.isRingComplete)
        assertFalse(controller.isRingPrefilled)
        assertFalse(controller.hasPendingSeed)
    }

    @Test
    fun `解码期间窗口变化则丢弃结果`() {
        val controller = newController()
        controller.onFormatConfigured(1_000, 1, 2, false)
        val w1 = window()
        controller.window = w1
        val gate = CompletableDeferred<Unit>()
        val decoder = FakeDecoder(frames = 100L).also { it.gate = gate }
        val pre = newPrefiller(controller, decoder)

        pre.onWindowApplied(w1)
        assertEquals(1, decoder.started)

        // 解码未完成时窗口被换掉（比较并设置：旧结果必须丢弃）。
        val w2 = window(aMs = 300L, bMs = 400L)
        controller.window = w2
        gate.complete(Unit)
        settle()

        assertFalse("旧窗口的预填结果必须丢弃", controller.isRingComplete)
        assertFalse(controller.isRingPrefilled)
    }
}

