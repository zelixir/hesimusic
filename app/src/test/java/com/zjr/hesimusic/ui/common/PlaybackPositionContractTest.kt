package com.zjr.hesimusic.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A-066 / A-067 的数值契约。
 *
 * 修复前:POSITION_REFRESH_WINDOW_MS = 2000(< MediaSession 的 3000ms 事件周期),
 * 注释与假设是"约 1s 刷新、speed=1 外推"。这里把真实周期、>3s 窗口与
 * speed 外推一起钉住。
 */
class PlaybackPositionContractTest {

    @Test
    fun `刷新窗口必须覆盖完整的 3s 位置事件周期`() {
        assertEquals("MediaSession 默认周期位置事件是 3s", 3_000L, PlaybackPositionContract.EVENT_PERIOD_MS)
        assertTrue(
            "刷新窗口必须 >3s,否则暂停态拖动会等不到下一次位置事件",
            PlaybackPositionContract.REFRESH_WINDOW_MS > PlaybackPositionContract.EVENT_PERIOD_MS,
        )
        assertTrue(PlaybackPositionContract.REFRESH_WINDOW_MS > 3_000L)
        assertNotEquals("修复前的 2s 窗口不足以覆盖 3s 周期", 2_000L, PlaybackPositionContract.REFRESH_WINDOW_MS)
    }

    @Test
    fun `两次事件之间按真实 speed 外推而不是 speed 1`() {
        // 3s 前的 PlayerInfo:位置 10s。
        assertEquals(13_000L, PlaybackPositionContract.extrapolateMs(10_000L, 3_000L, 1f))
        assertEquals(
            "2x 播放 3s 后应前进 6s;按 speed=1 只会算出 13s",
            16_000L,
            PlaybackPositionContract.extrapolateMs(10_000L, 3_000L, 2f),
        )
        assertNotEquals(
            PlaybackPositionContract.extrapolateMs(10_000L, 3_000L, 1f),
            PlaybackPositionContract.extrapolateMs(10_000L, 3_000L, 2f),
        )
    }

    @Test
    fun `暂停态只在 seek 刷新窗口内读位置`() {
        assertTrue("播放中每秒读", PlaybackPositionContract.shouldRefresh(true, 10_000L, 0L))
        assertTrue("暂停但仍在 seek 窗口内", PlaybackPositionContract.shouldRefresh(false, 9_999L, 10_000L))
        assertFalse("暂停且窗口已过:不再轮询位置", PlaybackPositionContract.shouldRefresh(false, 10_000L, 10_000L))
    }
}
