package com.zjr.hesimusic.ui.loop

import com.zjr.hesimusic.loop.LoopWindow
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * issue 00023 回归(场景套件 #18):"刚切完模式,紧接着区外 seek"的时序。
 *
 * 真机现象:点「仅循环段」后立刻 POST /debug/preview-seek {1458},请求下发了
 * (appliedPlayerTargetMs=1458)但播放器位置在 13606/24161 —— 根因是"整曲窗口的环带源"
 * PTS 以已读帧数单调推进,seek 回退时 PTS 被 max() 留在读前的位置。
 * 修复:区外自由试听改用**原生 media3 源**,每次区外 seek 走原生 seek(不换源、不投影)。
 *
 * 这里锁的是**状态机判定**:区外第一次 seek 只发生一次 FREE_ENTER(换源),
 * 之后连续区外 seek 全部是 FREE_SEEK(绝不能再"重进自由试听"或折回循环)。
 */
class PreviewSeekPolicyTest {

    // 与失败场景同口径:Redstar Rising A=17496 B=50167。
    private val window = LoopWindow(songId = 39944L, loopStartUs = 17_496_000L, loopEndUs = 50_167_000L)
    private val aMs = 17_496L
    private val bMs = 50_167L

    @Test
    fun modeSwitchThenImmediateOutsideSeekEntersFreeExactlyOnce() {
        // 刚点完「仅循环段」:outsideLoop=false,第一次区外 seek → 进自由试听(换源一次)。
        assertEquals(
            PreviewSeekAction.FREE_ENTER,
            PreviewSeekPolicy.decide(PreviewMode.LOOP_ONLY, window, outsideLoop = false, targetMs = 1458L),
        )
        // 之后连续区外 seek(场景 #18 的 5 个点位)都必须是原生 seek,不再换源。
        for (target in listOf(1458L, 2916L, 4374L, 5832L, 7290L, 7496L)) {
            assertEquals(
                "已进入自由试听后 target=$target 不得再换源",
                PreviewSeekAction.FREE_SEEK,
                PreviewSeekPolicy.decide(PreviewMode.LOOP_ONLY, window, outsideLoop = true, targetMs = target),
            )
        }
    }

    @Test
    fun outsideSeekAfterModeSwitchInEveryLoopModeEntersFree() {
        for (mode in listOf(PreviewMode.LOOP_ONLY, PreviewMode.SEAM, PreviewMode.FULL)) {
            assertEquals(
                "$mode 区外首次 seek 必须进自由试听",
                PreviewSeekAction.FREE_ENTER,
                PreviewSeekPolicy.decide(mode, window, outsideLoop = false, targetMs = aMs - 1L),
            )
        }
    }

    @Test
    fun loopWindowBoundaryIsRightOpen() {
        // A 属于区间内;A-1 属于区外;B 属于区外(右开,与环回边界一致)。
        assertEquals(
            PreviewSeekAction.LOOP_FAST,
            PreviewSeekPolicy.decide(PreviewMode.LOOP_ONLY, window, outsideLoop = false, targetMs = aMs),
        )
        assertEquals(
            PreviewSeekAction.FREE_ENTER,
            PreviewSeekPolicy.decide(PreviewMode.LOOP_ONLY, window, outsideLoop = false, targetMs = aMs - 1L),
        )
        assertEquals(
            PreviewSeekAction.LOOP_FAST,
            PreviewSeekPolicy.decide(PreviewMode.LOOP_ONLY, window, outsideLoop = false, targetMs = bMs - 1L),
        )
        assertEquals(
            PreviewSeekAction.FREE_ENTER,
            PreviewSeekPolicy.decide(PreviewMode.LOOP_ONLY, window, outsideLoop = false, targetMs = bMs),
        )
    }

    @Test
    fun seekBackInsideRestoresLoop() {
        assertEquals(
            PreviewSeekAction.LOOP_RESTORE,
            PreviewSeekPolicy.decide(PreviewMode.LOOP_ONLY, window, outsideLoop = true, targetMs = aMs + 1000L),
        )
        assertEquals(
            PreviewSeekAction.LOOP_RESTORE,
            PreviewSeekPolicy.decide(PreviewMode.SEAM, window, outsideLoop = true, targetMs = bMs - 1L),
        )
    }

    @Test
    fun freeModeIsNeverFreeAudition() {
        // 用户显式选「自由」= 整段直通,不存在"区外";区外 seek 仍是普通 seek。
        assertEquals(
            PreviewSeekAction.LOOP_FAST,
            PreviewSeekPolicy.decide(PreviewMode.FREE, window, outsideLoop = false, targetMs = 1458L),
        )
        // 无窗口时同理。
        assertEquals(
            PreviewSeekAction.LOOP_FAST,
            PreviewSeekPolicy.decide(PreviewMode.LOOP_ONLY, null, outsideLoop = false, targetMs = 1458L),
        )
    }

    @Test
    fun freeAuditionSeekTargetIsRawMediaTime() {
        // 关键回归:自由试听里 seek 目标 = 请求值本身(原生源按文件时间),不折回 [A,B)。
        // 这里用"判定不产生任何循环投影"来锁定语义:重复区外 seek 一律 FREE_SEEK。
        var outside = false
        val targets = listOf(1458L, 2916L, 4374L, 5832L, 7290L)
        for (target in targets) {
            val action = PreviewSeekPolicy.decide(PreviewMode.LOOP_ONLY, window, outside, target)
            assertEquals(
                "target=$target 必须是区外路径(不得走环内快速 seek)",
                if (outside) PreviewSeekAction.FREE_SEEK else PreviewSeekAction.FREE_ENTER,
                action,
            )
            outside = true
        }
    }
}
