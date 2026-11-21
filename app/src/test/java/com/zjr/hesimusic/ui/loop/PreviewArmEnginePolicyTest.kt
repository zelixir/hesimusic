package com.zjr.hesimusic.ui.loop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #112：自由(M3) → 仅循环段 必须重建 SOURCE 的纯决策测试。
 *
 * 端到端行为（engine=SOURCE、位置=A、TapAudioSink 帧增长）由真机场景 112 复验；
 * 这里钉住"何时重建 / 重建后从哪起播"，避免退回到"留在 M3"。
 */
class PreviewArmEnginePolicyTest {

    @Test
    fun freeM3ToLoopOnly_requiresSourceRebuild() {
        assertTrue(
            PreviewArmEnginePolicy.requiresSourceRebuild(
                wantSource = true,   // LOOP_ONLY / SEAM 由 SOURCE 表达
                engineIsSource = false, // 当前停留在 FREE 的 M3
            ),
        )
    }

    @Test
    fun alreadySource_doesNotRebuild() {
        assertFalse(PreviewArmEnginePolicy.requiresSourceRebuild(true, true))
    }

    @Test
    fun modeNotExpressibleBySource_doesNotRebuild() {
        // FREE 没有窗口 / 前奏非空的 FULL → 不应强建 SOURCE。
        assertFalse(PreviewArmEnginePolicy.requiresSourceRebuild(false, false))
        assertFalse(PreviewArmEnginePolicy.requiresSourceRebuild(false, true))
    }

    @Test
    fun rebuildStart_reusesPreviewStartPlan() {
        val a = 10_000L
        val b = 30_000L
        // LOOP_ONLY：A 前 300ms 预卷（PreviewStartPlan 的既定口径）。
        assertEquals(9_700L, PreviewArmEnginePolicy.rebuildStartMs(PreviewMode.LOOP_ONLY, a, b))
        // SEAM：max(A, B-5s)。
        assertEquals(25_000L, PreviewArmEnginePolicy.rebuildStartMs(PreviewMode.SEAM, a, b))
        // FULL / FREE：展示口径起点 0。
        assertEquals(0L, PreviewArmEnginePolicy.rebuildStartMs(PreviewMode.FULL, a, b))
        assertEquals(0L, PreviewArmEnginePolicy.rebuildStartMs(PreviewMode.FREE, a, b))
        // A=0 时预卷被钳回 0。
        assertEquals(0L, PreviewArmEnginePolicy.rebuildStartMs(PreviewMode.LOOP_ONLY, 0L, b))
    }
}
