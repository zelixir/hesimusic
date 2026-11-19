package com.zjr.hesimusic.ui.common

import com.zjr.hesimusic.data.mapper.buildQueueMediaId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [shouldKeepCurrentItem] 决定「无缝循环」入口是沿用当前时间轴(只 play)还是重建队列。
 * 这里只覆盖纯判定,不触碰 MediaController。
 */
class MusicViewModelKeepPositionTest {

    @Test
    fun `same song keeps the current item`() {
        assertTrue(
            shouldKeepCurrentItem(
                currentMediaId = "42",
                songId = 42L,
                sameQueueHead = false,
            )
        )
    }

    @Test
    fun `different song rebuilds the queue`() {
        assertFalse(
            shouldKeepCurrentItem(
                currentMediaId = "41",
                songId = 42L,
                sameQueueHead = false,
            )
        )
    }

    @Test
    fun `no current item rebuilds the queue`() {
        assertFalse(
            shouldKeepCurrentItem(
                currentMediaId = null,
                songId = 42L,
                sameQueueHead = false,
            )
        )
    }

    @Test
    fun `queue copy of the same song keeps the current item even after the head was consumed`() {
        // 队列头在 transition 时就被 consume 了,正在播的副本通常已不是 head;
        // 不能因此退回重建(否则又要 flush + 位置归零)。
        assertTrue(
            shouldKeepCurrentItem(
                currentMediaId = buildQueueMediaId(sequence = 3, songId = 42),
                songId = 42L,
                sameQueueHead = false,
            )
        )
    }

    @Test
    fun `queue copy of another song rebuilds the queue`() {
        assertFalse(
            shouldKeepCurrentItem(
                currentMediaId = buildQueueMediaId(sequence = 3, songId = 41),
                songId = 42L,
                sameQueueHead = true,
            )
        )
    }

    @Test
    fun `unknown media id format falls back to the queue head signal`() {
        assertTrue(
            shouldKeepCurrentItem(
                currentMediaId = "session-item-3",
                songId = 42L,
                sameQueueHead = true,
            )
        )
        assertFalse(
            shouldKeepCurrentItem(
                currentMediaId = "session-item-3",
                songId = 42L,
                sameQueueHead = false,
            )
        )
    }
}
