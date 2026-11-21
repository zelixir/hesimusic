package com.zjr.hesimusic.service

import com.zjr.hesimusic.loop.LoopWindow
import com.zjr.hesimusic.loop.audio.source.LoopSourceItemTag
import com.zjr.hesimusic.loop.audio.source.LoopWholeTimeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 进入/退出循环的纯计划单测（不碰 Player）。
 *
 * 口径：A = 10s，B = 50s，L = 40s；媒体时间与数据源时间轴 T 的单位都是 ms（T=0 ↔ A）。
 */
class LoopSourceItemPlanTest {

    private val aMs = 10_000L
    private val bMs = 50_000L
    private val lengthMs = bMs - aMs
    private val window = LoopWindow(songId = 7L, loopStartUs = aMs * 1_000L, loopEndUs = bMs * 1_000L)
    private val queue = listOf("song-1", "song-2", "song-3", "song-4")

    private fun enter(
        positionMs: Long,
        index: Int = 2,
        songId: Long = 7L,
        path: String = "/music/a.mp3",
        fullDurationUs: Long = 0L,
        entryTimelineUs: Long = 0L,
    ) =
        LoopSourceItemPlan.enter(
            index = index,
            itemCount = queue.size,
            positionMs = positionMs,
            window = window,
            songId = songId,
            filePath = path,
            fullDurationUs = fullDurationUs,
            entryTimelineUs = entryTimelineUs,
        )

    // ---------------------------------------------------------------- 进入：只动当前项

    @Test
    fun `enter touches only the current item`() {
        val plan = enter(positionMs = 12_000L)!!
        assertEquals(listOf(2), plan.touchedIndices)
        assertEquals(2, plan.index)
        // 按计划替换后的队列：只有当前项变化，长度与其它项逐项相同。
        val after = queue.mapIndexed { i, id -> if (i in plan.touchedIndices) "LOOP" else id }
        assertEquals(queue.size, after.size)
        for (i in queue.indices) {
            if (i == plan.index) continue
            assertEquals("queue item $i must stay untouched", queue[i], after[i])
        }
        assertEquals("LOOP", after[plan.index])
    }

    @Test
    fun `enter before A starts at A`() {
        // 播放位置在前奏段（A 之前）：进循环从 A 起播（前奏只播一次）。
        assertEquals(0L, enter(positionMs = 3_000L)!!.seekTimelineMs)
        assertEquals(0L, enter(positionMs = 0L)!!.seekTimelineMs)
        // 正好在 A：T=0。
        assertEquals(0L, enter(positionMs = aMs)!!.seekTimelineMs)
    }

    @Test
    fun `enter inside the window keeps the entry offset`() {
        // A+entryOffset：用户在 12s 点无缝循环，不会被拽回 A。
        assertEquals(2_000L, enter(positionMs = 12_000L)!!.seekTimelineMs)
        assertEquals(1L, enter(positionMs = aMs + 1L)!!.seekTimelineMs)
        // B-1ms 进循环：T = L-1ms。
        assertEquals(lengthMs - 1L, enter(positionMs = bMs - 1L)!!.seekTimelineMs)
    }

    @Test
    fun `enter after B folds to a single round`() {
        // repeat=ONE 收尾在 B 之后（如 B+5s）：落点是同一相位 5s，而不是跨轮的 L+5s。
        assertEquals(5_000L, enter(positionMs = bMs + 5_000L)!!.seekTimelineMs)
        assertEquals(0L, enter(positionMs = aMs + lengthMs)!!.seekTimelineMs)
    }

    @Test
    fun `whole timeline enters at prelude zero regardless of current position`() {
        val durUs = 60_000_000L
        // issue 00028：点无缝循环的位置 = 曲目开头 0，不是 A，也不是当前位置。
        assertEquals(0L, enter(3_000L, fullDurationUs = durUs)!!.seekTimelineMs)
        assertEquals(0L, enter(12_000L, fullDurationUs = durUs)!!.seekTimelineMs)
        assertEquals(0L, enter(bMs + 5_000L, fullDurationUs = durUs)!!.seekTimelineMs)
        assertEquals(durUs, enter(12_000L, fullDurationUs = durUs)!!.tag.fullDurationUs)
    }

    @Test
    fun `window change keeps the current display position`() {
        val durUs = 60_000_000L
        // A/B 变化（已经处于 source item）时，调用方把当前展示位置映射成新窗口的 T。
        assertEquals(
            11_000L,
            enter(12_000L, fullDurationUs = durUs, entryTimelineUs = 11_000_000L)!!.seekTimelineMs,
        )
    }

    @Test
    fun `enter carries the window and file path in the tag`() {
        val tag = enter(positionMs = 12_000L)!!.tag
        assertEquals(7L, tag.songId)
        assertEquals(aMs * 1_000L, tag.loopStartUs)
        assertEquals(bMs * 1_000L, tag.loopEndUs)
        assertEquals("/music/a.mp3", tag.filePath)
    }

    @Test
    fun `enter rejects invalid arguments`() {
        assertNull(enter(positionMs = 12_000L, index = -1))
        assertNull(enter(positionMs = 12_000L, index = queue.size))
        assertNull(enter(positionMs = 12_000L, songId = 9L))
        assertNull(enter(positionMs = 12_000L, path = "  "))
        assertNull(
            LoopSourceItemPlan.enter(
                index = 0,
                itemCount = 1,
                positionMs = 1_000L,
                window = LoopWindow(7L, loopStartUs = bMs * 1_000L, loopEndUs = aMs * 1_000L),
                songId = 7L,
                filePath = "/music/a.mp3",
            ),
        )
        assertNull(
            LoopSourceItemPlan.enter(0, 1, 1_000L, null, 7L, "/music/a.mp3"),
        )
    }

    // ---------------------------------------------------------------- 退出：映射回媒体时间

    private val tag = LoopSourceItemTag(7L, aMs * 1_000L, bMs * 1_000L, "/music/a.mp3")

    private fun exit(timelineMs: Long, index: Int = 2) = LoopSourceItemPlan.exit(
        index = index,
        itemCount = queue.size,
        timelineMs = timelineMs,
        tag = tag,
    )

    @Test
    fun `exit maps timeline back to media time`() {
        // mediaMs = A + floorMod(T, L)
        assertEquals(aMs, exit(0L)!!.seekMediaMs)
        assertEquals(aMs + 1L, exit(1L)!!.seekMediaMs)
        assertEquals(bMs - 1L, exit(lengthMs - 1L)!!.seekMediaMs)
        // 回绕后：T 走满一圈回到 A；再走 2.345s 就是 A+2.345s（不是 T 本身）。
        assertEquals(aMs, exit(lengthMs)!!.seekMediaMs)
        assertEquals(aMs + 2_345L, exit(lengthMs + 2_345L)!!.seekMediaMs)
        assertEquals(aMs + 5_000L, exit(3 * lengthMs + 5_000L)!!.seekMediaMs)
    }

    @Test
    fun `whole timeline exit maps prelude loop and tail back to media time`() {
        val wholeTag = LoopSourceItemTag(
            7L,
            aMs * 1_000L,
            bMs * 1_000L,
            "/music/a.mp3",
            fullDurationUs = 60_000_000L,
        )
        fun wholeExit(timelineMs: Long) = LoopSourceItemPlan.exit(
            index = 2,
            itemCount = queue.size,
            timelineMs = timelineMs,
            tag = wholeTag,
        )!!.seekMediaMs
        // prelude 直通
        assertEquals(0L, wholeExit(0L))
        assertEquals(5_000L, wholeExit(5_000L))
        // loop 折返：T 是整曲时间轴，B 处回 A
        assertEquals(12_000L, wholeExit(12_000L))
        assertEquals(aMs, wholeExit(bMs))
        assertEquals(aMs + 2_000L, wholeExit(bMs + 2_000L))
        // tail 直通（sentinel 时间带）
        val tailT = (LoopWholeTimeline.TAIL_BASE_US + 5_000_000L) / 1_000L
        assertEquals(bMs + 5_000L, wholeExit(tailT))
    }

    @Test
    fun `exit touches only the current item`() {
        val plan = exit(12_000L)!!
        assertEquals(listOf(2), plan.touchedIndices)
        assertTrue(plan.seekMediaMs in aMs until bMs)
    }

    @Test
    fun `exit without a source tag is rejected`() {
        assertNull(LoopSourceItemPlan.exit(0, 1, 1_000L, null))
        assertNull(exit(1_000L, index = -1))
        assertNull(exit(1_000L, index = queue.size))
    }
}
