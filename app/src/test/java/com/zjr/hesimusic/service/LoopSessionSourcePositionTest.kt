package com.zjr.hesimusic.service

import com.zjr.hesimusic.loop.LoopPhase
import com.zjr.hesimusic.loop.LoopWindow
import com.zjr.hesimusic.loop.audio.source.LoopSourceProjection
import com.zjr.hesimusic.loop.audio.source.LoopWholeTimeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 位置投影：通知栏 / MediaSession / 进度条读到的必须是**媒体时间**，不是带 offset 的数据源
 * 时间轴 T（T=0 对应 A）。
 *
 * 口径：A = 10s，B = 50s，L = 40s；SOURCE 的 T 从 0 单调递增，display = A + floorMod(T,L)。
 */
class LoopSessionSourcePositionTest {

    private val aMs = 10_000L
    private val bMs = 50_000L
    private val lengthMs = bMs - aMs
    private val window = LoopWindow(songId = 7L, loopStartUs = aMs * 1_000L, loopEndUs = bMs * 1_000L)

    @Test
    fun `position at A is media time A`() {
        assertEquals(aMs, LoopSessionProjection.sourceDisplayMs(0L, window))
        assertEquals(aMs * 1_000L, LoopSessionProjection.sourceDisplayUs(0L, window))
    }

    @Test
    fun `position at A plus one millisecond`() {
        assertEquals(aMs + 1L, LoopSessionProjection.sourceDisplayMs(1L, window))
        assertEquals((aMs + 1L) * 1_000L, LoopSessionProjection.sourceDisplayUs(1L, window))
    }

    @Test
    fun `position at B minus one millisecond`() {
        assertEquals(bMs - 1L, LoopSessionProjection.sourceDisplayMs(lengthMs - 1L, window))
        assertEquals((bMs - 1L) * 1_000L, LoopSessionProjection.sourceDisplayUs(lengthMs - 1L, window))
    }

    @Test
    fun `position after the wrap`() {
        // T 走满一圈：回到 A，不是继续增长到 B。
        assertEquals(aMs, LoopSessionProjection.sourceDisplayMs(lengthMs, window))
        assertEquals(aMs + 2_345L, LoopSessionProjection.sourceDisplayMs(lengthMs + 2_345L, window))
        assertEquals(aMs + 1L, LoopSessionProjection.sourceDisplayMs(3 * lengthMs + 1L, window))
    }

    @Test
    fun `display never returns the raw timeline T`() {
        for (t in listOf(0L, 1L, 12_345L, lengthMs - 1L, lengthMs, lengthMs + 7_000L, 5 * lengthMs + 3L)) {
            val display = LoopSessionProjection.sourceDisplayMs(t, window)
            assertTrue("display $display must stay inside [A,B)", display in aMs until bMs)
            if (t >= bMs) {
                assertNotEquals("raw T must not leak into the notification", t, display)
            }
        }
    }

    @Test
    fun `whole timeline shows prelude loop and tail truthfully`() {
        val durUs = 60_000_000L
        // T=0 是曲目开头，不是 A。
        assertEquals(0L, LoopSessionProjection.sourceDisplayMs(0L, window, durUs))
        assertEquals(5_000L, LoopSessionProjection.sourceDisplayMs(5_000L, window, durUs))
        assertEquals(aMs, LoopSessionProjection.sourceDisplayMs(aMs, window, durUs))
        assertEquals(12_000L, LoopSessionProjection.sourceDisplayMs(12_000L, window, durUs))
        // loop 在 B 折回 A
        assertEquals(aMs, LoopSessionProjection.sourceDisplayMs(bMs, window, durUs))
        assertEquals(aMs + 2_000L, LoopSessionProjection.sourceDisplayMs(bMs + 2_000L, window, durUs))
        // tail 不折返
        val tailT = LoopWholeTimeline.TAIL_BASE_US / 1_000L
        assertEquals(bMs, LoopSessionProjection.sourceDisplayMs(tailT, window, durUs))
        assertEquals(bMs + 5_000L, LoopSessionProjection.sourceDisplayMs(tailT + 5_000L, window, durUs))
    }

    @Test
    fun `whole timeline seek maps prelude loop and tail`() {
        val durUs = 60_000_000L
        assertEquals(5_000L, LoopSessionProjection.sourceTimelineUsForDisplay(
            5_000_000L, 0L, window, durUs,
        ) / 1_000L)
        assertEquals(12_000L, LoopSessionProjection.sourceTimelineUsForDisplay(
            12_000_000L, 0L, window, durUs,
        ) / 1_000L)
        assertEquals(
            bMs + 5_000L,
            LoopSessionProjection.sourceDisplayMs(
                LoopSessionProjection.sourceTimelineUsForDisplay(
                    50_000_000L + 5_000_000L,
                    12_000_000L,
                    window,
                    durUs,
                ) / 1_000L,
                window,
                durUs,
            ),
        )
        // B 之后 -> tail sentinel，不被折回 A。
        val t = LoopSessionProjection.sourceTimelineUsForDisplay(
            bMs * 1_000L + 5_000_000L,
            12_000_000L,
            window,
            durUs,
        )
        assertTrue(t >= LoopWholeTimeline.TAIL_BASE_US)
    }

    @Test
    fun `phase follows display position`() {
        assertEquals(LoopPhase.PRELUDE, LoopSessionProjection.phaseOf(5_000_000L, window))
        assertEquals(LoopPhase.LOOP, LoopSessionProjection.phaseOf(12_000_000L, window))
        assertEquals(LoopPhase.TAIL, LoopSessionProjection.phaseOf(bMs * 1_000L, window))
    }

    @Test
    fun `no window passes the timeline through`() {
        assertEquals(12_345L, LoopSessionProjection.sourceDisplayMs(12_345L, null))
        assertEquals(12_345_000L, LoopSessionProjection.sourceDisplayUs(12_345L, null))
    }

    @Test
    fun `notification seek maps media time back to the current round`() {
        // 通知栏/进度条拖到 12s：落在**当前轮**（T=42s），不是回到 A，也不是跨到下一轮。
        val targetT = LoopSourceProjection.timelineUsForDisplay(
            displayUs = 12_000L * 1_000L,
            currentTimelineUs = lengthMs * 1_000L + 5_000_000L,
            window = window,
        )
        assertEquals(42_000L, targetT / 1_000L)
        assertEquals(12_000L, LoopSessionProjection.sourceDisplayMs(targetT / 1_000L, window))
    }
}
