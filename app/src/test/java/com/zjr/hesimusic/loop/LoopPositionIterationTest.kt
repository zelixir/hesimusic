package com.zjr.hesimusic.loop

import org.junit.Assert.assertEquals
import org.junit.Test

class LoopPositionIterationTest {

    private val window = LoopWindow(songId = 1L, loopStartUs = 1_000_000L, loopEndUs = 1_100_000L)

    @Test
    fun `第 0 轮起点就是 A`() {
        assertEquals("k=0 应为 A", 1_000_000L, LoopPosition.iterationStartUs(0L, window))
    }

    @Test
    fun `第 k 轮起点为 A 加 k 倍长度`() {
        assertEquals("k=1", 1_100_000L, LoopPosition.iterationStartUs(1L, window))
        assertEquals("k=7", 1_700_000L, LoopPosition.iterationStartUs(7L, window))
    }

    @Test
    fun `负数轮次按第 0 轮处理`() {
        assertEquals("k<0 防护", 1_000_000L, LoopPosition.iterationStartUs(-3L, window))
    }

    @Test
    fun `非法窗口的轮次起点退化为 A`() {
        val bad = LoopWindow(songId = 1L, loopStartUs = 100L, loopEndUs = 100L)
        assertEquals("非法窗口不应产生偏移", 100L, LoopPosition.iterationStartUs(5L, bad))
    }

    @Test
    fun `原始位置轮次索引`() {
        assertEquals("前奏期属于第 0 轮", 0L, LoopPosition.iterationIndexUs(0L, window))
        assertEquals("恰在 A 属于第 0 轮", 0L, LoopPosition.iterationIndexUs(1_000_000L, window))
        assertEquals("区间内属于第 0 轮", 0L, LoopPosition.iterationIndexUs(1_099_999L, window))
        assertEquals("恰在 B 属于第 1 轮", 1L, LoopPosition.iterationIndexUs(1_100_000L, window))
        assertEquals("第 2 轮", 2L, LoopPosition.iterationIndexUs(1_299_999L, window))
        assertEquals("第 3 轮边界", 3L, LoopPosition.iterationIndexUs(1_300_000L, window))
    }
}
