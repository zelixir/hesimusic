package com.zjr.hesimusic.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #体验审计2：进循环保留队列的纯决策测试。
 *
 * 目标歌已在队列 → 只移 index；不在队列 → 只替换当前项（长度/index 不变）；
 * 单曲队列 → 无可保留（走原生 setMediaItem）。
 */
class LoopEntryQueuePolicyTest {

    private val queue = listOf("11", "22", "33")

    @Test
    fun songAlreadyInQueue_seeksWithoutReplacing() {
        val plan = LoopEntryQueuePolicy.plan(
            armedSongId = 22L,
            requestedMediaId = "22",
            queueMediaIds = queue,
            currentIndex = 0,
        )
        assertEquals(LoopEntryQueuePolicy.Plan(1, replaceCurrent = false), plan)
    }

    @Test
    fun queueCopyMediaId_matchesBySongId() {
        // 队列里是 ad-hoc 副本 mediaId，请求项是普通 id：仍应定位到该副本。
        val plan = LoopEntryQueuePolicy.plan(
            armedSongId = 22L,
            requestedMediaId = "22",
            queueMediaIds = listOf("11", "queue:7:22", "33"),
            currentIndex = 0,
        )
        assertEquals(LoopEntryQueuePolicy.Plan(1, replaceCurrent = false), plan)
    }

    @Test
    fun songOutsideQueue_replacesOnlyCurrentItem() {
        val plan = LoopEntryQueuePolicy.plan(
            armedSongId = 99L,
            requestedMediaId = "99",
            queueMediaIds = queue,
            currentIndex = 1,
        )
        assertEquals(LoopEntryQueuePolicy.Plan(1, replaceCurrent = true), plan)
    }

    @Test
    fun singleItemQueue_hasNothingToPreserve() {
        assertNull(
            LoopEntryQueuePolicy.plan(
                armedSongId = 99L,
                requestedMediaId = "99",
                queueMediaIds = listOf("11"),
                currentIndex = 0,
            ),
        )
    }

    @Test
    fun notArmedForThisSong_isNotIntercepted() {
        assertNull(
            LoopEntryQueuePolicy.plan(
                armedSongId = 11L,
                requestedMediaId = "22",
                queueMediaIds = queue,
                currentIndex = 0,
            ),
        )
    }

    @Test
    fun unarmedOrUnparsable_isNotIntercepted() {
        assertNull(LoopEntryQueuePolicy.plan(null, "22", queue, 0))
        assertNull(LoopEntryQueuePolicy.plan(22L, null, queue, 0))
        assertNull(LoopEntryQueuePolicy.plan(22L, "not-a-song-id", queue, 0))
    }

    @Test
    fun invalidCurrentIndex_outsideQueue_isNotIntercepted() {
        assertNull(
            LoopEntryQueuePolicy.plan(
                armedSongId = 99L,
                requestedMediaId = "99",
                queueMediaIds = queue,
                currentIndex = 9,
            ),
        )
    }

    @Test
    fun planSemantics_areExplicit() {
        val plan = LoopEntryQueuePolicy.plan(99L, "99", queue, 2)!!
        assertTrue(plan.replaceCurrent)
        assertFalse(plan.targetIndex != 2)
    }
}
