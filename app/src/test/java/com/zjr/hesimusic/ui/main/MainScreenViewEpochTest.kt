package com.zjr.hesimusic.ui.main

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 视图 epoch 推进(issue 00002 回归)。
 *
 * 回归场景:歌曲 → 歌单 → 歌曲 切回来时不再自动定位当前播放歌。
 * 根因是「已自动定位」标志用 rememberSaveable 保存,被 HorizontalPager 的 SaveableStateHolder
 * 跨视图切换恢复;修复后标志以单调递增的 viewEpoch 为 key,epoch 一变旧标志即失效。
 * 这组用例锁住「epoch 必须单调递增、不能复用页号」这一关键点。
 */
class MainScreenViewEpochTest {

    @Test
    fun `same page keeps epoch so returning from details does not relocate`() {
        // 详情页 / 分析页返回:页号不变,epoch 必须不变 → 列表恢复标志、保留滚动位置。
        assertEquals(5, nextViewEpoch(previousPage = 0, currentPage = 0, currentEpoch = 5))
    }

    @Test
    fun `switching to another view advances epoch`() {
        assertEquals(6, nextViewEpoch(previousPage = 0, currentPage = 1, currentEpoch = 5))
    }

    @Test
    fun `switching back to the original page still advances epoch`() {
        // 0 → 1 → 0:页号回到 0,但 epoch 必须继续增长,否则旧标志会被恢复(00002 的回归点)。
        val afterFirstSwitch = nextViewEpoch(previousPage = 0, currentPage = 1, currentEpoch = 0)
        val afterSwitchBack = nextViewEpoch(previousPage = 1, currentPage = 0, currentEpoch = afterFirstSwitch)

        assertEquals(1, afterFirstSwitch)
        assertEquals(2, afterSwitchBack)
    }
}
