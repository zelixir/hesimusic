package com.zjr.hesimusic.ui.player

import com.zjr.hesimusic.loop.LoopWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 松手 seek「待生效目标」覆盖层的纯函数单测(issue 00001 第 2 条)。
 *
 * 只覆盖策略算术:收敛容差、超时兜底、折返后的期望落点、覆盖层取值优先级。
 */
class SeekOverlayPolicyTest {

    // A=20000ms,B=50000ms,L=30000ms
    private val window = LoopWindow(songId = 1L, loopStartUs = 20_000_000L, loopEndUs = 50_000_000L)

    @Test
    fun `期望落点按展示口径折返`() {
        assertEquals("无窗口原样返回", 30_000L, SeekOverlayPolicy.expectedDisplayMs(30_000L, null))
        assertEquals("前奏(目标 < A)原样返回", 10_000L, SeekOverlayPolicy.expectedDisplayMs(10_000L, window))
        assertEquals("区间内目标折返是幂等的", 30_000L, SeekOverlayPolicy.expectedDisplayMs(30_000L, window))
        assertEquals("目标恰为 B 折回 A", 20_000L, SeekOverlayPolicy.expectedDisplayMs(50_000L, window))
        assertEquals("多轮之后的目标折回同相位", 25_000L, SeekOverlayPolicy.expectedDisplayMs(115_000L, window))
    }

    @Test
    fun `收敛判定容差为 1200ms`() {
        assertTrue("完全一致算收敛", SeekOverlayPolicy.hasConverged(30_000L, 30_000L))
        assertTrue("正向恰好 1200ms 算收敛", SeekOverlayPolicy.hasConverged(31_200L, 30_000L))
        assertTrue("反向恰好 1200ms 算收敛", SeekOverlayPolicy.hasConverged(28_800L, 30_000L))
        assertFalse("1201ms 不算收敛", SeekOverlayPolicy.hasConverged(31_201L, 30_000L))
        assertFalse("旧位置离目标太远不算收敛", SeekOverlayPolicy.hasConverged(5_000L, 30_000L))
    }

    @Test
    fun `超时兜底 1500ms 强制释放`() {
        assertFalse("未收敛且未超时:继续显示目标", SeekOverlayPolicy.shouldRelease(0L, 30_000L, 1_499L))
        assertTrue("超时即使位置没动也释放", SeekOverlayPolicy.shouldRelease(0L, 30_000L, 1_500L))
        assertTrue("收敛时提前释放", SeekOverlayPolicy.shouldRelease(30_500L, 30_000L, 200L))
        assertFalse("恰好 1499ms 且差 1201ms:仍保持", SeekOverlayPolicy.shouldRelease(28_799L, 30_000L, 1_499L))
    }

    @Test
    fun `A067 seek 后 controller 已乐观 mask 成请求值 立即释放`() {
        // MediaController.seekTo 在 controller 侧立即 mask 成新位置(A-067),旧值不会出现;
        // 因此 UI 读到"实际位置 == 请求值"时直接收敛,不需要等一次"旧→新"的变化。
        assertTrue("乐观值即最终值:elapsed=0 也应释放", SeekOverlayPolicy.shouldRelease(30_000L, 30_000L, 0L))
        // 真正需要等的是 session 侧 clamp/调整后的最终值;在它到达前继续显示目标。
        assertFalse(
            "实际位置仍是旧值 5000ms 时不能释放(不能假设它已经是旧快照)",
            SeekOverlayPolicy.shouldRelease(5_000L, 30_000L, 0L),
        )
    }

    @Test
    fun `覆盖层取值优先级 拖动 高于 待生效目标 高于 服务端位置`() {
        assertEquals("拖动中优先本地值", 1L, SeekOverlayPolicy.shownPositionMs(1L, 2L, 3L))
        assertEquals("松手后显示待生效目标", 2L, SeekOverlayPolicy.shownPositionMs(null, 2L, 3L))
        assertEquals("无覆盖层时显示服务端位置", 3L, SeekOverlayPolicy.shownPositionMs(null, null, 3L))
    }
}
