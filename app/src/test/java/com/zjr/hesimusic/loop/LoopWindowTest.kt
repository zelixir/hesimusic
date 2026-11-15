package com.zjr.hesimusic.loop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopWindowTest {

    private val window = LoopWindow(songId = 7L, loopStartUs = 1_000_000L, loopEndUs = 1_100_000L)

    @Test
    fun `长度等于终点减起点`() {
        assertEquals("lengthUs 应为 loopEndUs - loopStartUs", 100_000L, window.lengthUs)
    }

    @Test
    fun `合法窗口判定为有效`() {
        assertTrue("songId>0 且 L>0 且起点>=0 应判为合法", window.isValid())
    }

    @Test
    fun `songId 非正数时窗口非法`() {
        assertFalse("songId=0 应非法", window.copy(songId = 0L).isValid())
        assertFalse("songId 为负应非法", window.copy(songId = -3L).isValid())
    }

    @Test
    fun `零长度窗口非法`() {
        assertFalse(
            "起点==终点(长度 0)应非法",
            window.copy(loopEndUs = window.loopStartUs).isValid()
        )
    }

    @Test
    fun `终点早于起点时窗口非法`() {
        assertFalse("终点 < 起点应非法", window.copy(loopEndUs = 999_999L).isValid())
    }

    @Test
    fun `起点为负时窗口非法`() {
        assertFalse("起点为负应非法", window.copy(loopStartUs = -1L).isValid())
    }

    @Test
    fun `containsUs 包含起点不包含终点`() {
        assertTrue("起点应包含", window.containsUs(1_000_000L))
        assertTrue("区间内最后一个微秒应包含", window.containsUs(1_099_999L))
        assertFalse("终点应不包含(半开区间)", window.containsUs(1_100_000L))
        assertFalse("起点之前应不包含", window.containsUs(999_999L))
    }

    @Test
    fun `非法窗口不包含任何位置`() {
        val bad = LoopWindow(songId = 1L, loopStartUs = -10L, loopEndUs = 5L)
        assertFalse("非法窗口的 containsUs 应恒为 false", bad.containsUs(0L))
    }

    @Test
    fun `长度为 1 微秒的窗口边界`() {
        val micro = LoopWindow(songId = 1L, loopStartUs = 10L, loopEndUs = 11L)
        assertEquals("长度为 1µs 时 lengthUs 应为 1", 1L, micro.lengthUs)
        assertTrue("起点包含", micro.containsUs(10L))
        assertFalse("终点不包含", micro.containsUs(11L))
        assertFalse("终点之前 0µs 不存在其它位置", micro.containsUs(9L))
    }
}
