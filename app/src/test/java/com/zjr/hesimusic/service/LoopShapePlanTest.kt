package com.zjr.hesimusic.service

import com.zjr.hesimusic.loop.LoopWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 形态应用计划单测（纯 JVM）。
 *
 * 生产路径不裁剪，所以「进入/退出无缝」的正常路径必须是**纯状态写**（只写 audio.window、
 * 不 seek、不动媒体结构）；只有两种例外各 seek 一次：进入时预卷、退出时重放态续播。
 * 这些分支全部集中在纯函数 [LoopShapePlan] 里，这里逐条钉住。
 *
 * 口径：A = 10s, B = 20s, L = 10s，raw 为引擎媒体位置（µs）。
 */
class LoopShapePlanTest {

    private val window = LoopWindow(songId = 7L, loopStartUs = A, loopEndUs = B)

    // 同歌、A/B 变了（用来构造「已应用窗口 ≠ 目标窗口」且 display 仍落在新区间内的场景）
    private val widerWindow = LoopWindow(songId = 7L, loopStartUs = 5_000_000L, loopEndUs = 30_000_000L)

    private fun plan(
        songId: Long? = 7L,
        target: LoopWindow? = window,
        applied: LoopWindow? = null,
        rawUs: Long = 15_000_000L,
        ringCoveredFrames: Long = 0L,
        wasReplaying: Boolean = false,
    ) = LoopShapePlan.plan(songId, target, applied, rawUs, ringCoveredFrames, wasReplaying)

    // ---------------------------------------------------------------- 窗口归属

    @Test
    fun targetWindow_onlyAppliesToCurrentSong() {
        assertEquals(window, LoopShapePlan.targetWindow(7L, window))
        assertNull(LoopShapePlan.targetWindow(8L, window))
        assertNull(LoopShapePlan.targetWindow(null, window))
        assertNull(LoopShapePlan.targetWindow(7L, null))
    }

    // ---------------------------------------------------------------- 1. 形态应用 = 纯状态写

    @Test
    fun noop_whenWindowUnchanged() {
        // 同一窗口（含 A/B 与 songId）连续 refresh：连音频层都不重复写
        val p = plan(target = window, applied = window, rawUs = 15_000_000L, ringCoveredFrames = 0L)
        assertEquals(LoopShapePlan.Kind.NOOP, p.kind)
        assertEquals(window, p.window)
        assertNull(p.seekRawUs)
        // 重放中（raw 已越过 B）也是 NOOP：不能因为位置在 B 之后就再 seek 一次
        val replaying = plan(target = window, applied = window, rawUs = 45_000_000L, ringCoveredFrames = 0L)
        assertEquals(LoopShapePlan.Kind.NOOP, replaying.kind)
        assertNull(replaying.seekRawUs)
    }

    @Test
    fun enter_isPureStateWrite_whenRingAlreadyComplete() {
        val p = plan(target = window, applied = null, rawUs = 15_000_000L, ringCoveredFrames = 1L)
        assertEquals(LoopShapePlan.Kind.ENTER, p.kind)
        assertEquals(window, p.window)
        assertNull("进入时 ring 已完整 → 只写窗口，不 seek", p.seekRawUs)
    }

    @Test
    fun enter_isPureStateWrite_whenPositionOutsideWindow() {
        // 前奏期起播：会自然流经 [A,B)，不需要预卷
        val prelude = plan(target = window, applied = null, rawUs = 5_000_000L, ringCoveredFrames = 0L)
        assertEquals(LoopShapePlan.Kind.ENTER, prelude.kind)
        assertNull(prelude.seekRawUs)
        // raw 已越过 B（且没有旧窗口可折返）：同样只写窗口
        val beyond = plan(target = window, applied = null, rawUs = 45_000_000L, ringCoveredFrames = 0L)
        assertEquals(LoopShapePlan.Kind.ENTER, beyond.kind)
        assertNull(beyond.seekRawUs)
    }

    // ---------------------------------------------------------------- 2. 进入/改 A/B 的预卷

    @Test
    fun preRoll_whenEnteringInsideWindowWithIncompleteRing() {
        // display 15s ∈ [A,B)，k = 0 → 本轮起点 A → 预卷 A - 250ms
        val p = plan(target = window, applied = null, rawUs = 15_000_000L, ringCoveredFrames = 0L)
        assertEquals(LoopShapePlan.Kind.PRE_ROLL, p.kind)
        assertEquals(window, p.window)
        assertEquals(9_750_000L, p.seekRawUs)
        // 恰在 A
        assertEquals(
            9_750_000L,
            plan(target = window, applied = null, rawUs = A, ringCoveredFrames = 0L).seekRawUs,
        )
    }

    @Test
    fun preRoll_keepsCurrentIteration() {
        // 旧窗口 W（A=10,B=20）下 raw 45s → display 15s；新窗口 W2(5..30)，k = (45-5)/25 = 1
        // → 正常映射 5 + 25 + (15-5) = 40s → 本轮起点 30s → 预卷 29.75s
        val p = plan(target = widerWindow, applied = window, rawUs = 45_000_000L, ringCoveredFrames = 0L)
        assertEquals(LoopShapePlan.Kind.PRE_ROLL, p.kind)
        assertEquals(widerWindow, p.window)
        assertEquals(29_750_000L, p.seekRawUs)
    }

    @Test
    fun preRoll_clampedAtZeroWhenLoopStartIsTiny() {
        val early = LoopWindow(songId = 7L, loopStartUs = 100_000L, loopEndUs = 10_100_000L)
        val p = plan(target = early, applied = null, rawUs = 5_000_000L, ringCoveredFrames = 0L)
        assertEquals(LoopShapePlan.Kind.PRE_ROLL, p.kind)
        assertEquals(0L, p.seekRawUs)
    }

    // ---------------------------------------------------------------- 3. 退出无缝

    @Test
    fun exitSeek_whenReplaying_mustSeekToDisplayPosition() {
        // 重放期 raw 已越过 B（45s → display 15s）：只清窗口会让播放卡住，必须 seek 回 15s
        val p = plan(target = null, applied = window, rawUs = 45_000_000L, wasReplaying = true)
        assertEquals(LoopShapePlan.Kind.EXIT_SEEK, p.kind)
        assertNull(p.window)
        assertEquals(15_000_000L, p.seekRawUs)
        // 前奏区间的重放退出一致处理
        assertEquals(
            5_000_000L,
            plan(target = null, applied = window, rawUs = 5_000_000L, wasReplaying = true).seekRawUs,
        )
    }

    @Test
    fun exit_isPureStateWriteWhenNotReplaying() {
        // 未进入重放（还在捕获/直通）：清窗口即可，输入仍在自然前进，不需要 seek
        val p = plan(target = null, applied = window, rawUs = 15_000_000L, wasReplaying = false)
        assertEquals(LoopShapePlan.Kind.EXIT, p.kind)
        assertNull(p.window)
        assertNull(p.seekRawUs)
    }

    @Test
    fun exit_doesNotFoldUsingAnotherSongsWindow() {
        // 切歌：applied 是上一首的窗口，即使 isReplaying 有残留也不能拿它折返新歌位置
        val p = plan(songId = 8L, target = null, applied = window, rawUs = 45_000_000L, wasReplaying = true)
        assertEquals(LoopShapePlan.Kind.EXIT, p.kind)
        assertNull(p.seekRawUs)
    }

    @Test
    fun exit_withoutAppliedWindowIsNoop() {
        // 本来就没有窗口（当前不无缝）→ 目标也为空 → NOOP，连状态写都不需要
        val p = plan(target = null, applied = null, rawUs = 15_000_000L, wasReplaying = true)
        assertEquals(LoopShapePlan.Kind.NOOP, p.kind)
        assertNull(p.seekRawUs)
        // songId 未知（空队列）时只清窗口，不拿旧窗口折返位置
        val noSong = plan(songId = null, target = null, applied = window, rawUs = 15_000_000L, wasReplaying = true)
        assertEquals(LoopShapePlan.Kind.EXIT, noSong.kind)
        assertNull(noSong.seekRawUs)
    }

    private companion object {
        private const val A = 10_000_000L
        private const val B = 20_000_000L
    }
}