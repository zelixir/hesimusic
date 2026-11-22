package com.zjr.hesimusic.loop

import androidx.media3.common.Player
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 显式意图容器:只持有「武装的是哪首歌 + 进入前的 repeat/shuffle」,无 DB / 无播放副作用。
 */
class LoopIntentTest {

    private fun emissions(intent: LoopIntent, block: () -> Unit): List<LoopIntent.Armed?> = runBlocking {
        val seen = mutableListOf<LoopIntent.Armed?>()
        val job = launch(Dispatchers.Unconfined) {
            intent.armed.collect { seen.add(it) }
        }
        block()
        yield()
        job.cancel()
        seen.toList()
    }

    @Test
    fun `初始未武装`() {
        assertNull("新建 LoopIntent 不应有意图(会话级、不持久化)", LoopIntent().armed.value)
    }

    @Test
    fun `arm 记录目标歌与进入前的 repeat 和 shuffle`() {
        val intent = LoopIntent()
        intent.arm(42L, Player.REPEAT_MODE_ALL, previousShuffleEnabled = true)
        assertEquals(
            "arm 应记录 songId + previousRepeatMode + previousShuffleEnabled",
            LoopIntent.Armed(
                songId = 42L,
                previousRepeatMode = Player.REPEAT_MODE_ALL,
                previousShuffleEnabled = true,
            ),
            intent.armed.value
        )
    }

    @Test
    fun `arm 也能记录 shuffle 关闭`() {
        val intent = LoopIntent()
        intent.arm(7L, Player.REPEAT_MODE_OFF, previousShuffleEnabled = false)
        assertEquals(
            "进入前 shuffle=false 也要如实记录(退出时按 false 恢复)",
            LoopIntent.Armed(7L, Player.REPEAT_MODE_OFF, previousShuffleEnabled = false),
            intent.armed.value
        )
    }

    @Test
    fun `重复 arm 以最后一次为准`() {
        val intent = LoopIntent()
        intent.arm(1L, Player.REPEAT_MODE_ALL, previousShuffleEnabled = true)
        intent.arm(2L, Player.REPEAT_MODE_OFF, previousShuffleEnabled = false)
        assertEquals(
            "arm 别的歌应替换意图(不叠加),两个原值都更新",
            LoopIntent.Armed(2L, Player.REPEAT_MODE_OFF, previousShuffleEnabled = false),
            intent.armed.value
        )
    }

    @Test
    fun `arm 非法 songId 被忽略`() {
        val intent = LoopIntent()
        intent.arm(0L, Player.REPEAT_MODE_ALL, previousShuffleEnabled = true)
        assertNull("songId=0 不可能成为无缝目标,不应留下意图", intent.armed.value)
        intent.arm(-3L, Player.REPEAT_MODE_ALL, previousShuffleEnabled = true)
        assertNull("负 songId 同样忽略", intent.armed.value)
    }

    @Test
    fun `clear 幂等且不重复 emit`() {
        val intent = LoopIntent()
        val seen = emissions(intent) {
            intent.arm(1L, Player.REPEAT_MODE_ALL, previousShuffleEnabled = true)
            intent.clear()
            intent.clear()
        }
        assertEquals(
            "null -> 武装 -> null;重复 clear 不应重复 emit",
            listOf(null, LoopIntent.Armed(1L, Player.REPEAT_MODE_ALL, previousShuffleEnabled = true), null),
            seen
        )
        assertNull("clear 后应无意图", intent.armed.value)
    }

    @Test
    fun `armEntry 标记入口准备中(00029)`() {
        val intent = LoopIntent()
        intent.armEntry(42L, Player.REPEAT_MODE_ALL, previousShuffleEnabled = false)
        assertEquals("armEntry 必须同时武装意图", 42L, intent.armed.value?.songId)
        assertEquals("armEntry 必须留下入口标记", 42L, intent.entryPendingSongId.value)
        assertEquals(true, intent.isEntryPendingFor(42L))
        assertEquals(false, intent.isEntryPendingFor(43L))
    }

    @Test
    fun `armEntry 先置入口标记再 emit armed(00029)`() {
        val intent = LoopIntent()
        val seenEntry = mutableListOf<Long?>()
        runBlocking {
            val job = launch(Dispatchers.Unconfined) {
                intent.armed.collect { seenEntry.add(intent.entryPendingSongId.value) }
            }
            intent.armEntry(42L, Player.REPEAT_MODE_ALL, previousShuffleEnabled = false)
            yield()
            job.cancel()
        }
        assertEquals(
            "armed 订阅者重算窗口时入口标记必须已可见，否则第一次 refresh 会走整表重建",
            listOf(null, 42L),
            seenEntry,
        )
    }

    @Test
    fun `普通 arm 清掉残留的入口标记(00029)`() {
        val intent = LoopIntent()
        intent.armEntry(42L, Player.REPEAT_MODE_ALL, previousShuffleEnabled = false)
        // debug /loop/enter 等非 UI 入口走普通 arm：不能继承"准备中"语义。
        intent.arm(43L, Player.REPEAT_MODE_ONE, previousShuffleEnabled = false)
        assertNull("普通 arm 必须清掉入口标记", intent.entryPendingSongId.value)
    }

    @Test
    fun `clearEntry 只清指定歌(00029)`() {
        val intent = LoopIntent()
        intent.armEntry(42L, Player.REPEAT_MODE_ALL, previousShuffleEnabled = false)
        intent.clearEntry(43L)
        assertEquals("别的歌不能清掉本次入口", 42L, intent.entryPendingSongId.value)
        intent.clearEntry(42L)
        assertNull("目标歌清掉后入口结束", intent.entryPendingSongId.value)
        assertEquals("入口结束不能动意图", 42L, intent.armed.value?.songId)
    }

    @Test
    fun `clear 同时清入口(00029)`() {
        val intent = LoopIntent()
        intent.armEntry(42L, Player.REPEAT_MODE_ALL, previousShuffleEnabled = false)
        intent.clear()
        assertNull("退出循环必须同时结束入口准备", intent.entryPendingSongId.value)
    }

    @Test
    fun `相同意图重复 arm 不重复 emit`() {
        val intent = LoopIntent()
        val seen = emissions(intent) {
            intent.arm(1L, Player.REPEAT_MODE_ALL, previousShuffleEnabled = true)
            intent.arm(1L, Player.REPEAT_MODE_ALL, previousShuffleEnabled = true)
        }
        assertEquals(
            "相同 Armed 值不应重复 emit",
            listOf(null, LoopIntent.Armed(1L, Player.REPEAT_MODE_ALL, previousShuffleEnabled = true)),
            seen
        )
    }
}
