package com.zjr.hesimusic.ui.common

import com.zjr.hesimusic.loop.LoopWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放界面位置折返 + A/B 标记比例的纯函数单测。
 *
 * 位置口径:毫秒(UI);窗口口径:微秒。A=50s,B=80s,L=30s。
 */
class LoopPlaybackUiTest {

    private val songId = 7L
    private val window = LoopWindow(
        songId = songId,
        loopStartUs = 50_000_000L,
        loopEndUs = 80_000_000L,
    )

    private fun fold(rawMs: Long, w: LoopWindow? = window, id: Long? = songId): Long =
        foldDisplayPositionMs(rawMs, w, id)

    @Test
    fun `窗口为 null 时原样透传`() {
        assertEquals("普通播放不做任何折返", 123_456L, fold(123_456L, w = null))
        assertEquals("位置为 0 也原样", 0L, fold(0L, w = null))
        assertEquals("超出 B 的位置也原样(普通播放没有 B)", 110_000L, fold(110_000L, w = null))
    }

    @Test
    fun `当前歌未知时不折返`() {
        assertEquals("没有当前歌:原样透传", 110_000L, fold(110_000L, id = null))
    }

    @Test
    fun `raw 在区间内不变`() {
        assertEquals("区间内位置原样", 65_000L, fold(65_000L))
        assertEquals("恰在 A 点不变", 50_000L, fold(50_000L))
        assertEquals("区间内最后一个毫秒原样", 79_999L, fold(79_999L))
    }

    @Test
    fun `raw 超过 B 折回区间内`() {
        assertEquals("恰在 B 点折回 A", 50_000L, fold(80_000L))
        assertEquals("B 后 1s 对应 A 后 1s", 51_000L, fold(81_000L))
        assertEquals("B 后 15s 落在区间中点", 65_000L, fold(95_000L))
        assertEquals("整一轮后回到 A(50s→80s→110s 的 110s)", 50_000L, fold(110_000L))
        assertEquals("第 3 轮同一相位", 57_000L, fold(50_000L + 3 * 30_000L + 7_000L))
    }

    @Test
    fun `raw 在 A 之前(前奏)原样返回`() {
        assertEquals("前奏 0s", 0L, fold(0L))
        assertEquals("A 前 1ms", 49_999L, fold(49_999L))
    }

    @Test
    fun `window 属于别的歌时不折返`() {
        assertEquals("窗口是别的歌的:原样透传", 110_000L, fold(110_000L, id = songId + 1))
        assertEquals("id 不匹配时区间内位置也不变", 65_000L, fold(65_000L, id = 999L))
    }

    @Test
    fun `非法窗口原样透传`() {
        val zeroLength = LoopWindow(songId, 50_000_000L, 50_000_000L)
        assertEquals("零长度窗口不折返", 110_000L, fold(110_000L, w = zeroLength))
        val reversed = LoopWindow(songId, 80_000_000L, 50_000_000L)
        assertEquals("终点早于起点不折返", 110_000L, fold(110_000L, w = reversed))
    }

    @Test
    fun `折返幂等`() {
        val once = fold(110_000L)
        assertEquals("已折返的位置再折一次不变", once, fold(once))
        val once2 = fold(95_000L)
        assertEquals("区间内位置再折一次不变", once2, fold(once2))
    }

    @Test
    fun `折返结果始终落在区间内`() {
        var raw = 50_000L
        while (raw <= 500_000L) {
            val folded = fold(raw)
            assertTrue("$raw ms 折返后不应早于 A", folded >= 50_000L)
            assertTrue("$raw ms 折返后不应到达 B", folded < 80_000L)
            raw += 1_000L
        }
    }

    @Test
    fun `极大位置不溢出`() {
        val folded = fold(Long.MAX_VALUE)
        assertEquals("超长位置按原样透传,不变成负数", Long.MAX_VALUE, folded)
    }

    @Test
    fun `毫秒精度不丢位`() {
        assertEquals("1ms 折返精度", 50_001L, fold(80_001L))
        assertEquals("整秒折返精度", 79_999L, fold(79_999L))
    }

    // ---- A/B 标记比例 ----

    @Test
    fun `标记比例按整曲时长换算`() {
        val fractions = loopMarkerFractions(window, 180_000L)
        assertNotNull("命中时应给出 A/B 比例", fractions)
        assertEquals("A = 50/180", 50.0 / 180.0, fractions!!.start.toDouble(), 1e-5)
        assertEquals("B = 80/180", 80.0 / 180.0, fractions.end.toDouble(), 1e-5)
    }

    @Test
    fun `标记比例在窗口缺失或非法时为空`() {
        assertNull("窗口为 null 不画标记", loopMarkerFractions(null, 180_000L))
        assertNull("时长未知不画标记", loopMarkerFractions(window, 0L))
        assertNull("负时长不画标记", loopMarkerFractions(window, -1L))
        val zeroLength = LoopWindow(songId, 50_000_000L, 50_000_000L)
        assertNull("非法窗口不画标记", loopMarkerFractions(zeroLength, 180_000L))
    }

    @Test
    fun `B 超出整曲时长时比例被钳到 1`() {
        val fractions = loopMarkerFractions(window, 60_000L)
        assertNotNull(fractions)
        assertEquals("A = 50/60", 50.0 / 60.0, fractions!!.start.toDouble(), 1e-5)
        assertEquals("B 钳到 1", 1f, fractions.end)
    }
}
