package com.zjr.hesimusic.ui.loop

import com.zjr.hesimusic.loop.LoopPosition
import com.zjr.hesimusic.loop.LoopWindow
import com.zjr.hesimusic.loop.audio.source.LoopSourceProjection
import com.zjr.hesimusic.ui.loop.state.LoopPageEffect
import com.zjr.hesimusic.ui.loop.state.LoopPageEvent
import com.zjr.hesimusic.ui.loop.state.LoopPageMode
import com.zjr.hesimusic.ui.loop.state.LoopPageReducer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 试听四模式的**起点 / 第一次回绕**契约（issue 00007）。
 *
 * 场景套件实测的缺陷：点「完整循环」1.9s 后 displayMs=19325（A=17496，约 A+1.8s），
 * 期望是 0..8s。根因是 SOURCE 路线的起播映射把 FULL 与 LOOP_ONLY 一并落到 A。
 *
 * 本测试同时钉住：
 * - 每种模式走哪条引擎（SOURCE 表达不了 FULL 的「0..A 前奏」，必须走 M3′）；
 * - 该引擎下真正的起点映射（[LoopPosition] / [LoopSourceProjection] 的生产函数）；
 * - 第一次回绕恒为 B→A（不是 B→0）；
 * - UI reducer 与播放器计划两层口径一致。
 */
class PreviewStartPlanTest {

    private val songId = 7L

    /** 与真机场景 7 一致的量级：A=17.496s，循环 24s。 */
    private val aMs = 17_496L
    private val bMs = 41_496L

    private fun window(a: Long = aMs, b: Long = bMs): LoopWindow =
        LoopWindow(songId, a * 1_000L, b * 1_000L)

    private fun us(ms: Long): Long = ms * 1_000L

    // ------------------------------------------------------------ 完整循环 FULL

    @Test
    fun fullStartsAtZeroSoThePreludePlays() {
        // 展示口径起点恒为 0（前奏参与），与 A 无关。
        assertEquals(0L, PreviewStartPlan.displayStartMs(PreviewMode.FULL, aMs, bMs))

        // SOURCE 的 T=0 恒等于 A，表达不了 0..A 的前奏 → FULL 必须走 M3′。
        assertTrue("A>0 时 FULL 有前奏", PreviewStartPlan.hasPrelude(PreviewMode.FULL, window()))
        assertFalse(
            "前奏非空的 FULL 不能走 SOURCE（否则起点被映射成 A）",
            PreviewStartPlan.usesSource(PreviewMode.FULL, window(), sourceModeEnabled = true),
        )

        // M3′ 路线：seek 0（不是 A），前奏期 display 原样（0..A 是真实媒体时间）。
        val w = window()
        assertEquals(0L, LoopPosition.rawSeekUs(0L, w))
        assertEquals(0L, LoopPosition.displayUs(0L, w))
        assertEquals(us(1_900L), LoopPosition.displayUs(us(1_900L), w))
        assertEquals(us(aMs - 1L), LoopPosition.displayUs(us(aMs - 1L), w))
    }

    @Test
    fun fullRegressionReportedByDeviceSuite() {
        // 缺陷现场：点 FULL 后 1.9s，display 应是 ~1.9s，而不是 A+1.8s(=19325)。
        val w = window()
        val newDisplayUs = LoopPosition.displayUs(us(1_900L), w)
        assertEquals(us(1_900L), newDisplayUs)

        // 旧（错误）路线：SOURCE 的 T=1.9s → display = A + 1.9s = 19396ms（套件实测 19325）
        val oldSourceDisplayUs = LoopSourceProjection.displayUs(us(1_900L), w)
        assertEquals(us(aMs + 1_900L), oldSourceDisplayUs)
        assertNotEquals("新路线不得再落在 A 之后", oldSourceDisplayUs, newDisplayUs)
        assertTrue("新路线 1.9s 时 display 必须 <= 2s", newDisplayUs / 1_000L <= 2_000L)
    }

    @Test
    fun fullFirstWrapIsBToA() {
        val w = window()
        val aUs = us(aMs)
        val bUs = us(bMs)

        // 前奏/环内：原样透出。
        assertEquals(bUs - 1L, LoopPosition.displayUs(bUs - 1L, w))
        // B 处折回 A —— 不是 0，也不是 B。
        assertEquals(aUs, LoopPosition.displayUs(bUs, w))
        assertNotEquals("第一次回绕是 B→A，不是 B→0", 0L, LoopPosition.displayUs(bUs, w))
        assertEquals(aUs + 1_000L, LoopPosition.displayUs(bUs + 1_000L, w))
        // 第二轮同样折回 A。
        assertEquals(aUs + 5_000L, LoopPosition.displayUs(bUs + w.lengthUs + 5_000L, w))
    }

    // ------------------------------------------------------------ 仅循环段 LOOP_ONLY

    @Test
    fun loopOnlyStartsAtAAndWrapsToA() {
        val w = window()
        assertTrue(
            "仅循环段只描述 [A,B) 内的落点，SOURCE 可表达",
            PreviewStartPlan.usesSource(PreviewMode.LOOP_ONLY, w, sourceModeEnabled = true),
        )

        // SOURCE 时间轴 T=0 恒对应 A。
        assertEquals(0L, PreviewStartPlan.sourceStartMs(PreviewMode.LOOP_ONLY, aMs, bMs, w))
        assertEquals(us(aMs), LoopSourceProjection.displayUs(0L, w))

        // 环内终点 B−1 原样，T=L（= 媒体 B）折回 A。
        assertEquals(us(bMs) - 1L, LoopSourceProjection.displayUs(w.lengthUs - 1L, w))
        assertEquals(us(aMs), LoopSourceProjection.displayUs(w.lengthUs, w))
        assertNotEquals(
            "回绕不得回到 0（A=17.5s 时 0 在循环区外）",
            0L,
            LoopSourceProjection.displayUs(w.lengthUs, w),
        )

        // M3′ 兜底起点是 A−300ms 预卷（不是 A 正点，也不是 0）。
        assertEquals(aMs - 300L, PreviewStartPlan.displayStartMs(PreviewMode.LOOP_ONLY, aMs, bMs))
        // A 很小时预卷钳到 0。
        assertEquals(0L, PreviewStartPlan.displayStartMs(PreviewMode.LOOP_ONLY, 100L, 20_000L))
    }

    // ------------------------------------------------------------ 接缝检查 SEAM

    @Test
    fun seamStartsFiveSecondsBeforeBAndWrapsToA() {
        val w = window()
        assertTrue(PreviewStartPlan.usesSource(PreviewMode.SEAM, w, sourceModeEnabled = true))

        // 展示起点 = B−5s；SOURCE 落点 T = (B−5s) − A。
        assertEquals(bMs - 5_000L, PreviewStartPlan.displayStartMs(PreviewMode.SEAM, aMs, bMs))
        val t0 = PreviewStartPlan.sourceStartMs(PreviewMode.SEAM, aMs, bMs, w)
        assertEquals(bMs - 5_000L - aMs, t0)
        assertEquals(us(bMs - 5_000L), LoopSourceProjection.displayUs(us(t0), w))

        // 播到 B 即折回 A（第一次回绕 B→A）。
        assertEquals(us(aMs), LoopSourceProjection.displayUs(us(t0) + us(5_000L), w))
        assertNotEquals(0L, LoopSourceProjection.displayUs(us(t0) + us(5_000L), w))
    }

    @Test
    fun seamOnShortWindowStartsAtA() {
        // B−5s < A：落点取 A，仍在循环区内。
        val a = 60_000L
        val b = 63_000L
        assertEquals(a, PreviewStartPlan.displayStartMs(PreviewMode.SEAM, a, b))
        assertEquals(0L, PreviewStartPlan.sourceStartMs(PreviewMode.SEAM, a, b, window(a, b)))
        assertEquals(us(a), LoopSourceProjection.displayUs(0L, window(a, b)))
    }

    // ------------------------------------------------------------ 自由 FREE

    @Test
    fun freeStartsAtZeroAndNeverWraps() {
        assertEquals(0L, PreviewStartPlan.displayStartMs(PreviewMode.FREE, aMs, bMs))
        assertFalse(
            "自由模式没有窗口，不能走 SOURCE",
            PreviewStartPlan.usesSource(PreviewMode.FREE, window(), sourceModeEnabled = true),
        )
        assertFalse(PreviewStartPlan.usesSource(PreviewMode.FREE, null, sourceModeEnabled = true))

        // 直通路线不装窗口：位置投影原样（display == 媒体时间），且没有回绕点。
        assertEquals(us(9_000L), LoopSourceProjection.displayUs(us(9_000L), null))
        assertEquals(0L, PreviewStartPlan.sourceStartMs(PreviewMode.FREE, aMs, bMs, null))
    }

    // ------------------------------------------------------------ 路由 / 两层一致

    @Test
    fun fullWithoutPreludeMayStayOnSourceAndStillStartsAtMediaZero() {
        // A=0：前奏为空，FULL 与 LOOP_ONLY 同语义，可继续走 SOURCE（长窗口不受 ring 上限约束）。
        val w = window(0L, bMs)
        assertFalse(PreviewStartPlan.hasPrelude(PreviewMode.FULL, w))
        assertTrue(PreviewStartPlan.usesSource(PreviewMode.FULL, w, sourceModeEnabled = true))
        assertEquals(0L, PreviewStartPlan.sourceStartMs(PreviewMode.FULL, 0L, bMs, w))
        assertEquals(0L, LoopSourceProjection.displayUs(0L, w))
        assertEquals(0L, LoopSourceProjection.displayUs(w.lengthUs, w))
    }

    @Test
    fun sourceDisabledNeverUsesSource() {
        for (mode in PreviewMode.values()) {
            assertFalse(
                "$mode: 引擎开关关闭时必须走 M3′",
                PreviewStartPlan.usesSource(mode, window(), sourceModeEnabled = false),
            )
        }
    }

    @Test
    fun uiReducerAndPlayerPlanAgreeOnModeStarts() {
        val state = LoopPageMatrix.ready(aMs = aMs, bMs = bMs)
        val pairs = listOf(
            LoopPageMode.FULL to PreviewMode.FULL,
            LoopPageMode.LOOP_ONLY to PreviewMode.LOOP_ONLY,
            LoopPageMode.SEAM to PreviewMode.SEAM,
            LoopPageMode.FREE to PreviewMode.FREE,
        )
        for ((pageMode, previewMode) in pairs) {
            val (_, effects) = LoopPageReducer.reduce(state, LoopPageEvent.SwitchMode(pageMode))
            val expected = PreviewStartPlan.displayStartMs(previewMode, aMs, bMs)
            val seek = effects.filterIsInstance<LoopPageEffect.SeekTo>().single()
            val play = effects.filterIsInstance<LoopPageEffect.Play>().single()
            assertEquals("$pageMode: SeekTo 起点", expected, seek.positionMs)
            assertEquals("$pageMode: Play 起点", expected, play.startMs)
            assertEquals(previewMode, play.mode.toPreviewMode())
        }
    }
}
