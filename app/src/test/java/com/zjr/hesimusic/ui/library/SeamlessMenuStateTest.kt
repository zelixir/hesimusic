package com.zjr.hesimusic.ui.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 长按菜单「无缝」两项的显示/可用决策(纯函数 [resolveSeamlessMenuState])。
 *
 * 口径(issue 00006):
 * - 「无缝分析」始终显示,仅 CUE 分轨置灰;
 * - 「无缝循环」仅 confirmed=1 显示;null(查询中/查询失败)按未确认处理,防止闪现。
 */
class SeamlessMenuStateTest {

    @Test
    fun `unknown confirmed hides seamless loop and keeps analysis enabled`() {
        for (confirmed in listOf<Boolean?>(null, false)) {
            val state = resolveSeamlessMenuState(isCue = false, confirmed = confirmed)
            assertFalse("confirmed=$confirmed", state.showSeamlessLoop)
            assertFalse("confirmed=$confirmed", state.seamlessLoopEnabled)
            assertTrue("confirmed=$confirmed", state.showSeamlessAnalysis)
            assertTrue("confirmed=$confirmed", state.seamlessAnalysisEnabled)
        }
    }

    @Test
    fun `confirmed result shows both entries enabled`() {
        val state = resolveSeamlessMenuState(isCue = false, confirmed = true)
        assertTrue(state.showSeamlessLoop)
        assertTrue(state.seamlessLoopEnabled)
        assertTrue(state.showSeamlessAnalysis)
        assertTrue(state.seamlessAnalysisEnabled)
    }

    @Test
    fun `cue track shows only disabled seamless analysis`() {
        val state = resolveSeamlessMenuState(isCue = true, confirmed = null)
        assertFalse(state.showSeamlessLoop)
        assertTrue(state.showSeamlessAnalysis)
        assertFalse(state.seamlessAnalysisEnabled)
    }

    @Test
    fun `cue track never enables seamless loop even with stale confirmed row`() {
        val state = resolveSeamlessMenuState(isCue = true, confirmed = true)
        assertTrue(state.showSeamlessLoop)
        assertFalse(state.seamlessLoopEnabled)
        assertTrue(state.showSeamlessAnalysis)
        assertFalse(state.seamlessAnalysisEnabled)
    }
}
