package com.zjr.hesimusic.ui.player

import com.zjr.hesimusic.loop.LoopPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阶段可见性（体验审计第 5 条）的纯映射单测。
 *
 * 判据：
 * - 文案：prelude 写“前奏”、tail 写“尾奏”、loop 不写阶段词；claim 未生效写“未生效”；
 * - 色带：prelude/tail = GHOST（虚线 + 低不透明度），loop = ACTIVE（实线高亮）；
 * - 两段非激活样式的 alpha 必须明显低于 loop，且都带虚线。
 */
class LoopPhaseUiTest {

    private val range = "00:23–04:21"

    @Test
    fun prelude_status_says_qianzou() {
        assertEquals(
            "无缝循环中 · 前奏 · " + range,
            loopPhaseStatusText(LoopPhase.PRELUDE, range),
        )
    }

    @Test
    fun loop_status_has_no_phase_word() {
        assertEquals(
            "无缝循环中 · " + range,
            loopPhaseStatusText(LoopPhase.LOOP, range),
        )
        assertFalse(loopPhaseStatusText(LoopPhase.LOOP, range).contains("前奏"))
        assertFalse(loopPhaseStatusText(LoopPhase.LOOP, range).contains("尾奏"))
    }

    @Test
    fun tail_status_says_weizou() {
        assertEquals(
            "无缝循环中 · 尾奏 · " + range,
            loopPhaseStatusText(LoopPhase.TAIL, range),
        )
    }

    @Test
    fun unknown_phase_falls_back_to_loop_style_text() {
        assertEquals(
            "无缝循环中 · " + range,
            loopPhaseStatusText(null, range),
        )
    }

    @Test
    fun no_window_always_says_not_effective() {
        assertEquals("无缝循环未生效", loopPhaseStatusText(null, null))
        assertEquals("无缝循环未生效", loopPhaseStatusText(LoopPhase.PRELUDE, ""))
        assertEquals("无缝循环未生效", loopPhaseStatusText(LoopPhase.LOOP, "  "))
    }

    @Test
    fun loop_band_is_active_solid() {
        val visual = loopPhaseVisual(LoopPhase.LOOP)
        assertEquals(LoopBandStyle.ACTIVE, visual.bandStyle)
        assertEquals(1f, visual.tickAlpha)
        assertFalse(visual.dashed)
        assertNull("loop 不写阶段词", visual.phaseLabel)
    }

    @Test
    fun prelude_band_is_ghost_dashed_and_dimmed() {
        val visual = loopPhaseVisual(LoopPhase.PRELUDE)
        assertEquals(LoopBandStyle.GHOST, visual.bandStyle)
        assertEquals("前奏", visual.phaseLabel)
        assertTrue(visual.dashed)
        assertTrue("GHOST 填充必须比 ACTIVE 更淡", visual.fillAlpha < loopPhaseVisual(LoopPhase.LOOP).fillAlpha)
        assertTrue("GHOST 刻度必须比 ACTIVE 更淡", visual.tickAlpha < 1f)
    }

    @Test
    fun tail_band_is_ghost_dashed_and_dimmed() {
        val visual = loopPhaseVisual(LoopPhase.TAIL)
        assertEquals(LoopBandStyle.GHOST, visual.bandStyle)
        assertEquals("尾奏", visual.phaseLabel)
        assertTrue(visual.dashed)
        assertTrue(visual.fillAlpha < loopPhaseVisual(LoopPhase.LOOP).fillAlpha)
        assertTrue(visual.tickAlpha < 1f)
    }

    @Test
    fun no_phase_means_no_band() {
        val visual = loopPhaseVisual(null)
        assertEquals(LoopBandStyle.NONE, visual.bandStyle)
        assertEquals(0f, visual.fillAlpha)
        assertEquals(0f, visual.tickAlpha)
        assertFalse(visual.dashed)
        assertNull(visual.phaseLabel)
    }
}
