package com.zjr.hesimusic.loop

import org.junit.Assert.assertEquals
import org.junit.Test

class LoopPositionDisplayTest {

    // A=1_000_000µs,B=1_100_000µs,L=100_000µs
    private val window = LoopWindow(songId = 1L, loopStartUs = 1_000_000L, loopEndUs = 1_100_000L)

    private fun display(raw: Long): Long = LoopPosition.displayUs(raw, window)

    @Test
    fun `前奏区间原样返回`() {
        assertEquals("raw=0 应原样返回", 0L, display(0L))
        assertEquals("raw 刚进 A 之前应原样返回", 999_999L, display(999_999L))
    }

    @Test
    fun `原始位置恰在 A 点`() {
        assertEquals("raw==A 属于折返区,显示仍为 A", 1_000_000L, display(1_000_000L))
    }

    @Test
    fun `区间内位置原样返回`() {
        assertEquals("区间内位置正好等于展示位置", 1_050_000L, display(1_050_000L))
        assertEquals("区间内最后一个微秒原样返回", 1_099_999L, display(1_099_999L))
    }

    @Test
    fun `原始位置恰在 B 点折返到 A`() {
        assertEquals("raw==B 应折返到 A(B 不包含)", 1_000_000L, display(1_100_000L))
    }

    @Test
    fun `B 之后的位置折返到区间内`() {
        assertEquals("B 之后 1µs 对应 A 之后 1µs", 1_000_001L, display(1_100_001L))
        assertEquals("B+150_000 对应 A+50_000", 1_050_000L, display(1_250_000L))
    }

    @Test
    fun `多轮折返保持在区间内`() {
        val raw = 1_000_000L + 3L * 100_000L + 12_345L
        assertEquals("第 3 轮内同一相位", 1_012_345L, display(raw))
        val raw10 = 1_000_000L + 10L * 100_000L
        assertEquals("整 10 轮后回到 A", 1_000_000L, display(raw10))
    }

    @Test
    fun `长度为 1 微秒时所有位置折返到 A`() {
        val micro = LoopWindow(songId = 1L, loopStartUs = 10L, loopEndUs = 11L)
        assertEquals("前奏原样返回", 9L, LoopPosition.displayUs(9L, micro))
        assertEquals("A 点", 10L, LoopPosition.displayUs(10L, micro))
        assertEquals("B 点折返到 A", 10L, LoopPosition.displayUs(11L, micro))
        assertEquals("B 之后仍折返到 A", 10L, LoopPosition.displayUs(10_000L, micro))
        assertEquals(
            "极大原始位置不溢出且仍折返到 A",
            10L,
            LoopPosition.displayUs(Long.MAX_VALUE, micro)
        )
    }

    @Test
    fun `极大原始位置折返不溢出`() {
        val w = LoopWindow(songId = 1L, loopStartUs = 0L, loopEndUs = 100_000L)
        assertEquals(
            "raw=Long.MAX 时结果应为 Long.MAX mod L",
            Long.MAX_VALUE % 100_000L,
            LoopPosition.displayUs(Long.MAX_VALUE, w)
        )
    }

    @Test
    fun `负数原始位置原样返回`() {
        assertEquals("负的原始位置按前奏处理", -5L, display(-5L))
        assertEquals("Long.MIN 原样返回", Long.MIN_VALUE, display(Long.MIN_VALUE))
    }

    @Test
    fun `非法窗口原样返回不做折返`() {
        assertEquals(
            "零长度窗口不应折返",
            150L,
            LoopPosition.displayUs(150L, LoopWindow(songId = 1L, loopStartUs = 100L, loopEndUs = 100L))
        )
        assertEquals(
            "终点早于起点的窗口不应折返",
            150L,
            LoopPosition.displayUs(150L, LoopWindow(songId = 1L, loopStartUs = 100L, loopEndUs = 50L))
        )
        assertEquals(
            "起点为负的窗口不应折返",
            150L,
            LoopPosition.displayUs(150L, LoopWindow(songId = 1L, loopStartUs = -10L, loopEndUs = 50L))
        )
        assertEquals(
            "songId 非正的窗口不应折返",
            150L,
            LoopPosition.displayUs(150L, LoopWindow(songId = 0L, loopStartUs = 100L, loopEndUs = 200L))
        )
    }
}
