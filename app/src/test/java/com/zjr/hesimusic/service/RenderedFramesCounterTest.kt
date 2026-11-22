package com.zjr.hesimusic.service

import org.junit.Assert.assertEquals
import org.junit.Test

/** 00029：outputFrames 必须只随播放位置正增量增长，暂停/seek 不产生假增长。 */
class RenderedFramesCounterTest {

    @Test
    fun forwardPositionAccumulatesFrames() {
        val c = RenderedFramesCounter()
        c.onPosition(1_000L, 44_100)
        assertEquals(0L, c.value()) // 第一次只建基线
        assertEquals(44_100L, c.onPosition(2_000L, 44_100))
        assertEquals(88_200L, c.onPosition(3_000L, 44_100))
    }

    @Test
    fun staticPositionDoesNotGrow() {
        val c = RenderedFramesCounter()
        c.onPosition(1_000L, 44_100)
        c.onPosition(2_000L, 44_100)
        val at2s = c.value()
        assertEquals(at2s, c.onPosition(2_000L, 44_100))
        assertEquals(at2s, c.onPosition(2_000L, 44_100))
    }

    @Test
    fun backwardSeekDoesNotDecrease() {
        val c = RenderedFramesCounter()
        c.onPosition(1_000L, 44_100)
        c.onPosition(2_000L, 44_100)
        val at2s = c.value()
        assertEquals(at2s, c.onPosition(500L, 44_100)) // seek 回退：不减
        assertEquals(at2s + 44_100L, c.onPosition(1_500L, 44_100))
    }

    @Test
    fun largeJumpIsIgnored() {
        val c = RenderedFramesCounter()
        c.onPosition(0L, 44_100)
        c.onPosition(1_000L, 44_100)
        val before = c.value()
        assertEquals(before, c.onPosition(10_000L, 44_100)) // >2000ms 视为 seek/跳变
    }

    @Test
    fun unknownSampleRateDoesNotGrow() {
        val c = RenderedFramesCounter()
        c.onPosition(0L, 0)
        assertEquals(0L, c.onPosition(1_000L, 0))
    }
}
