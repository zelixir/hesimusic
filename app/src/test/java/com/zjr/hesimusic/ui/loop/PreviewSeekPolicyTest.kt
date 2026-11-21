package com.zjr.hesimusic.ui.loop

import com.zjr.hesimusic.loop.LoopWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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


    // ------------------------------------------------------------------ 落点契约(A 之前/B 之后/边界)

    // Shiny Up!(issue 00023 用户报告):A=23301 B=261457 duration=284865。
    private val shinyWindow = LoopWindow(songId = 41126L, loopStartUs = 23_301_000L, loopEndUs = 261_457_000L)
    private val shinyDuration = 284_865L

    @Test
    fun beforeAAlwaysFreeAuditionWithLandingEqualToTarget() {
        val points = listOf(0L, 23_301L - 10_000L, 23_301L - 1_000L)
        for (target in points) {
            val action = PreviewSeekPolicy.decide(PreviewMode.LOOP_ONLY, shinyWindow, outsideLoop = false, targetMs = target)
            assertEquals("target=" + target + " 必须在区外(自由试听)", PreviewSeekAction.FREE_ENTER, action)
            assertEquals(
                "target=" + target + " 的展示落点必须等于请求值(不能折到 B 左侧)",
                target,
                PreviewSeekPolicy.landingDisplayMs(action, target, shinyWindow),
            )
            // 已在自由试听里:继续原生 seek,落点仍等于请求值。
            val again = PreviewSeekPolicy.decide(PreviewMode.LOOP_ONLY, shinyWindow, outsideLoop = true, targetMs = target)
            assertEquals(PreviewSeekAction.FREE_SEEK, again)
            assertEquals(target, PreviewSeekPolicy.landingDisplayMs(again, target, shinyWindow))
        }
    }

    @Test
    fun afterBAlwaysFreeAuditionWithLandingEqualToTarget() {
        val points = listOf(261_457L + 5_000L, 261_457L + 20_000L)
        for (target in points) {
            assertTrue("测试点必须在时长内", target <= shinyDuration)
            val action = PreviewSeekPolicy.decide(PreviewMode.LOOP_ONLY, shinyWindow, outsideLoop = false, targetMs = target)
            assertEquals("target=" + target + " 必须在区外", PreviewSeekAction.FREE_ENTER, action)
            assertEquals(target, PreviewSeekPolicy.landingDisplayMs(action, target, shinyWindow))
        }
    }

    @Test
    fun loopBoundaryPointsKeepRequestedLanding() {
        val cases = listOf(
            23_301L to PreviewSeekAction.LOOP_FAST, // A 在区间内
            23_301L + 1L to PreviewSeekAction.LOOP_FAST,
            261_457L - 1L to PreviewSeekAction.LOOP_FAST,
            261_457L to PreviewSeekAction.FREE_ENTER, // B 右开 → 区外
        )
        for ((target, expected) in cases) {
            val action = PreviewSeekPolicy.decide(PreviewMode.LOOP_ONLY, shinyWindow, outsideLoop = false, targetMs = target)
            assertEquals("target=" + target, expected, action)
            assertEquals(
                "target=" + target + " 的落点必须等于请求值",
                target,
                PreviewSeekPolicy.landingDisplayMs(action, target, shinyWindow),
            )
        }
    }

    @Test
    fun restoreTimelineNeverFoldsOutsideTargetsIntoTheLoopTail() {
        // 窗口外的目标钳到窗口起点/终点,绝不 (target−A) mod L 折到环尾。
        assertEquals(0L, PreviewSeekPolicy.restoreTimelineMs(0L, shinyWindow))
        assertEquals(0L, PreviewSeekPolicy.restoreTimelineMs(23_301L - 1_000L, shinyWindow))
        assertEquals(
            (261_457L - 23_301L) - 1L,
            PreviewSeekPolicy.restoreTimelineMs(999_999L, shinyWindow),
        )
        // 折返口径自洽:restore 后的展示位置仍等于目标(区内)。
        for (target in listOf(23_301L + 1_000L, 100_000L, 261_457L - 1L)) {
            val t = PreviewSeekPolicy.restoreTimelineMs(target, shinyWindow)
            assertEquals(target, PreviewSeekPolicy.displayOfTimelineMs(t, shinyWindow))
        }
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
