package com.zjr.hesimusic.loop

import androidx.media3.common.Player
import com.zjr.hesimusic.data.model.SongLoop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LoopRuntimeTest {

    private fun row(
        songId: Long = 1L,
        loopStartMs: Long = 1_000L,
        loopEndMs: Long = 3_000L,
        confirmed: Boolean = true,
    ) = SongLoop(
        songId = songId,
        loopStartSamples = loopStartMs * 44L,
        loopEndSamples = loopEndMs * 44L,
        sampleRate = 44_100,
        loopStartMs = loopStartMs,
        loopEndMs = loopEndMs,
        score = 0.9,
        noteDistance = 0.12,
        loudnessDifference = 0.34,
        createdAt = 1_700_000_000_000L,
        confirmed = confirmed,
    )

    /**
     * 收集一次窗口序列:用 Unconfined 让收集器在 refresh/clear 的调用栈内同步恢复,
     * 从而确定性地观察"是否 emit"。
     */
    private fun emissions(runtime: LoopRuntime, block: () -> Unit): List<LoopWindow?> = runBlocking {
        val seen = mutableListOf<LoopWindow?>()
        val job = launch(Dispatchers.Unconfined) {
            runtime.window.collect { seen.add(it) }
        }
        block()
        yield()
        job.cancel()
        seen.toList()
    }

    @Test
    fun `初始窗口为空`() {
        assertNull("新建 LoopRuntime 不应有窗口", LoopRuntime().window.value)
    }

    @Test
    fun `命中时刷新出窗口`() {
        val runtime = LoopRuntime()
        runtime.refresh(1L, Player.REPEAT_MODE_ONE, row())
        assertEquals(
            "刷新后窗口应可由 StateFlow 读到",
            LoopWindow(songId = 1L, loopStartUs = 1_000_000L, loopEndUs = 3_000_000L),
            runtime.window.value
        )
    }

    @Test
    fun `相同窗口值不重复 emit`() {
        val runtime = LoopRuntime()
        val seen = emissions(runtime) {
            runtime.refresh(1L, Player.REPEAT_MODE_ONE, row())
            runtime.refresh(1L, Player.REPEAT_MODE_ONE, row())
            // 只改非窗口字段(score),算出的窗口仍然相等 -> 不应再 emit
            runtime.refresh(1L, Player.REPEAT_MODE_ONE, row().copy(score = 0.123))
        }
        assertEquals(
            "初始 null + 首次命中,共 2 次;后续相同窗口不应重复 emit",
            listOf(null, LoopWindow(songId = 1L, loopStartUs = 1_000_000L, loopEndUs = 3_000_000L)),
            seen
        )
    }

    @Test
    fun `失格条件刷新为 null`() {
        val runtime = LoopRuntime()
        val seen = emissions(runtime) {
            runtime.refresh(1L, Player.REPEAT_MODE_ONE, row())
            runtime.refresh(1L, Player.REPEAT_MODE_ALL, row())
            runtime.refresh(1L, Player.REPEAT_MODE_ALL, row())
        }
        assertEquals(
            "null -> 窗口 -> null;重复失格不应重复 emit",
            listOf(null, LoopWindow(songId = 1L, loopStartUs = 1_000_000L, loopEndUs = 3_000_000L), null),
            seen
        )
        assertNull("失格后窗口应为空", runtime.window.value)
    }

    @Test
    fun `clear 置空且重复 clear 不重复 emit`() {
        val runtime = LoopRuntime()
        val seen = emissions(runtime) {
            runtime.refresh(1L, Player.REPEAT_MODE_ONE, row())
            runtime.clear()
            runtime.clear()
        }
        assertEquals(
            "null -> 窗口 -> null;重复 clear 不应重复 emit",
            listOf(null, LoopWindow(songId = 1L, loopStartUs = 1_000_000L, loopEndUs = 3_000_000L), null),
            seen
        )
        assertNull("clear 后窗口应为空", runtime.window.value)
    }

    @Test
    fun `切换歌曲后窗口跟随新歌`() {
        val runtime = LoopRuntime()
        runtime.refresh(1L, Player.REPEAT_MODE_ONE, row(songId = 1L))
        runtime.refresh(
            2L,
            Player.REPEAT_MODE_ONE,
            row(songId = 2L, loopStartMs = 5_000L, loopEndMs = 9_000L)
        )
        assertEquals(
            "窗口应重算为新歌的 A/B",
            LoopWindow(songId = 2L, loopStartUs = 5_000_000L, loopEndUs = 9_000_000L),
            runtime.window.value
        )
        runtime.refresh(3L, Player.REPEAT_MODE_ONE, null)
        assertNull("新歌无行时窗口应置空", runtime.window.value)
    }
}
