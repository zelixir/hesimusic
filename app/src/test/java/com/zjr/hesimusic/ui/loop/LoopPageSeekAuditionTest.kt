package com.zjr.hesimusic.ui.loop

import com.zjr.hesimusic.ui.loop.state.DragState
import com.zjr.hesimusic.ui.loop.state.LoopPageEffect
import com.zjr.hesimusic.ui.loop.state.LoopPageEvent
import com.zjr.hesimusic.ui.loop.state.LoopPageMode
import com.zjr.hesimusic.ui.loop.state.LoopPageReducer
import com.zjr.hesimusic.ui.loop.state.LoopPageState
import com.zjr.hesimusic.ui.loop.state.PlaybackState
import com.zjr.hesimusic.ui.loop.state.PrefillState
import com.zjr.hesimusic.ui.loop.state.window
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * issue 00023:分析页"循环区外 seek"的交互语义(纯 reducer 单测)。
 *
 * 口径(与 PreviewPlayer.seekToDisplayMs 的注释一致):
 * - 区外 seek **允许**,落点就是用户点的位置(不再折回 [A,B));状态进入"区外自由试听";
 * - 区外只产出一个 SeekTo:换源/按文件时间播放在播放层完成,UI 不加等待、不重建播放器;
 * - 拖回 [A,B) 自动恢复循环(同一 A/B/模式),给一个"首块加载中"弱提示;
 * - 区外自由试听期间改 A/B **不 seek、不重装源**(否则播放头会被拽回 A);
 * - 显式点模式 / 选候选 = 明确的循环试听意图,一律退出自由试听。
 */
class LoopPageSeekAuditionTest {

    private fun ready(
        aMs: Long = 30_000L,
        bMs: Long = 60_000L,
        durationMs: Long = 120_000L,
        mode: LoopPageMode = LoopPageMode.LOOP_ONLY,
        playback: PlaybackState = PlaybackState.Playing,
        positionMs: Long = 35_000L,
        prefill: PrefillState = PrefillState.Ready,
        auditionOutside: Boolean = false,
        sourceMode: Boolean = true,
        drag: DragState = DragState.Idle,
    ): LoopPageState.Ready = LoopPageState.Ready(
        songId = 7L,
        durationMs = durationMs,
        sampleRate = 44_100,
        mode = mode,
        aMs = aMs,
        bMs = bMs,
        candidates = emptyList(),
        selectedCandidate = 0,
        confirmed = false,
        prefill = prefill,
        playback = playback,
        positionMs = positionMs,
        drag = drag,
        sourceMode = sourceMode,
        auditionOutside = auditionOutside,
    )

    private fun seekTargets(effects: List<LoopPageEffect>): List<Long> =
        effects.filterIsInstance<LoopPageEffect.SeekTo>().map { it.positionMs }

    // ------------------------------------------------------------------ 区外 seek

    @Test
    fun outsideSeekKeepsTargetAndEntersFreeAudition() {
        for (target in listOf(0L, 10_000L, 30_000L - 1L, 60_000L, 90_000L)) {
            val (next, effects) = LoopPageReducer.reduce(ready(), LoopPageEvent.Seek(target))
            val ready = next as LoopPageState.Ready
            assertEquals("区外 seek 必须落到用户点的位置: $target", target, ready.positionMs)
            assertEquals("区外 seek 只发一次 SeekTo: $target", listOf(target), seekTargets(effects))
            assertTrue("区外 seek 必须进入自由试听: $target", ready.auditionOutside)
            assertEquals(
                "区外试听不再等预填(位置与会话都不该被卡住): $target",
                PrefillState.NotStarted,
                ready.prefill,
            )
            assertTrue("区外 seek 不得额外发 Play(播放/暂停由播放层按原状态保持)", effects.size == 1)
        }
    }

    @Test
    fun outsideSeekBoundaryIsRightOpenAtB() {
        // B 本身属于区外(环回区间右开),A 属于区内。
        val atA = LoopPageReducer.reduce(ready(), LoopPageEvent.Seek(30_000L))
        assertFalse("A 在区间内", (atA.first as LoopPageState.Ready).auditionOutside)
        assertEquals(
            "区内 seek 仍是快路径:只 SeekTo",
            listOf(LoopPageEffect.SeekTo(30_000L)),
            atA.second,
        )

        val atB = LoopPageReducer.reduce(ready(), LoopPageEvent.Seek(60_000L))
        assertTrue("B 属于区外(右开)", (atB.first as LoopPageState.Ready).auditionOutside)
    }

    @Test
    fun freeModeNeverMarksOutsideAudition() {
        val (next, _) = LoopPageReducer.reduce(
            ready(mode = LoopPageMode.FREE, auditionOutside = false),
            LoopPageEvent.Seek(10_000L),
        )
        assertFalse("自由模式整段直通,不存在'区外'", (next as LoopPageState.Ready).auditionOutside)
    }

    @Test
    fun seekOutsideFromAwaitingPrefillResumesPlayingIntent() {
        val awaiting = ready(
            playback = PlaybackState.AwaitingPrefill(LoopPageMode.LOOP_ONLY, 30_000L),
            prefill = PrefillState.InProgress(0.4f),
        )
        val (next, effects) = LoopPageReducer.reduce(awaiting, LoopPageEvent.Seek(10_000L))
        val ready = next as LoopPageState.Ready
        assertEquals(
            "换到区外就不能再等旧预填(那扇门永远不会开)",
            PlaybackState.Playing,
            ready.playback,
        )
        assertEquals(
            "补一次 Play 保证'位置到了就有声音意图'",
            listOf(LoopPageEffect.SeekTo(10_000L), LoopPageEffect.Play(LoopPageMode.LOOP_ONLY, 10_000L)),
            effects,
        )
    }

    // ------------------------------------------------------------------ 回区间

    @Test
    fun seekBackInsideRestoresLoopAndMarksFirstBlockLoading() {
        val outside = ready(auditionOutside = true, positionMs = 10_000L)
        val (next, effects) = LoopPageReducer.reduce(outside, LoopPageEvent.Seek(45_000L))
        val ready = next as LoopPageState.Ready
        assertFalse("回到区间必须退出自由试听", ready.auditionOutside)
        assertEquals(45_000L, ready.positionMs)
        assertEquals(
            "恢复循环要重新装窗口/源:弱提示首块加载中",
            PrefillState.FirstBlockLoading,
            ready.prefill,
        )
        assertEquals(
            "恢复只发 SeekTo(换源与续播由播放层完成,UI 不额外加 Play 造成二次 seek)",
            listOf(LoopPageEffect.SeekTo(45_000L)),
            effects,
        )
    }

    @Test
    fun seekInsideWhilePausedKeepsPaused() {
        val outside = ready(
            auditionOutside = true,
            playback = PlaybackState.Paused,
            positionMs = 10_000L,
        )
        val (next, effects) = LoopPageReducer.reduce(outside, LoopPageEvent.Seek(45_000L))
        val ready = next as LoopPageState.Ready
        assertEquals("暂停态鼠标 seek 不得擅自起播", PlaybackState.Paused, ready.playback)
        assertEquals(listOf(LoopPageEffect.SeekTo(45_000L)), effects)
    }

    @Test
    fun seekInsideWhileStillOutsideStaysFreeAudition() {
        val outside = ready(auditionOutside = true, positionMs = 10_000L)
        val (next, effects) = LoopPageReducer.reduce(outside, LoopPageEvent.Seek(5_000L))
        val ready = next as LoopPageState.Ready
        assertTrue("仍在区外:保持自由试听", ready.auditionOutside)
        assertEquals(listOf(LoopPageEffect.SeekTo(5_000L)), effects)
        assertEquals(PrefillState.NotStarted, ready.prefill)
    }

    // ------------------------------------------------------------------ A/B 与模式

    @Test
    fun commitWindowDuringFreeAuditionDoesNotPullPlayheadBack() {
        var state: LoopPageState = ready(auditionOutside = true, positionMs = 10_000L)
        var commitEffects: List<LoopPageEffect> = emptyList()
        for (event in listOf(
            LoopPageEvent.DragAStart(32_000L),
            LoopPageEvent.DragAMove(34_000L),
            LoopPageEvent.DragAEnd,
            LoopPageEvent.CommitWindow,
        )) {
            val (next, effects) = LoopPageReducer.reduce(state, event)
            state = next
            if (event == LoopPageEvent.CommitWindow) commitEffects = effects
        }
        val ready = state as LoopPageState.Ready
        assertEquals("A 必须按拖拽更新", 34_000L, ready.aMs)
        assertEquals("松手不得 seek 回 A(区外试听位置必须保留)", 10_000L, ready.positionMs)
        assertTrue("仍处于自由试听", ready.auditionOutside)
        assertEquals(
            "松手只同步窗口记录(不 seek、不 Play、不 CancelPrefill/StartPrefill)",
            listOf(LoopPageEffect.ApplyWindow(ready.window())),
            commitEffects,
        )
        assertEquals("不产生 SeekTo", emptyList<Long>(), seekTargets(commitEffects))
        assertTrue("不产生 Play", commitEffects.none { it is LoopPageEffect.Play })
    }

    @Test
    fun switchModeThenImmediateOutsideSeekKeepsTarget() {
        // 场景套件 #18 的时序:点「仅循环段」后**立刻**区外 seek(Redstar Rising:A=17496 B=50167)。
        val beforeSwitch = ready(
            aMs = 17_496L,
            bMs = 50_167L,
            durationMs = 90_828L,
            mode = LoopPageMode.FULL,
            positionMs = 0L,
            auditionOutside = false,
        )
        val (afterSwitch, switchEffects) = LoopPageReducer.reduce(
            beforeSwitch,
            LoopPageEvent.SwitchMode(LoopPageMode.LOOP_ONLY),
        )
        assertEquals(
            "切模式:回到模式起点并从那里起播",
            listOf(
                LoopPageEffect.SeekTo(17_196L),
                LoopPageEffect.Play(LoopPageMode.LOOP_ONLY, 17_196L),
            ),
            switchEffects,
        )

        val (afterSeek, seekEffects) = LoopPageReducer.reduce(afterSwitch, LoopPageEvent.Seek(1_458L))
        val ready = afterSeek as LoopPageState.Ready
        assertEquals("区外 seek 必须落到请求值,不得折回 A/窗口起点", 1_458L, ready.positionMs)
        assertTrue("区外 seek 进入自由试听", ready.auditionOutside)
        assertEquals(
            "只发 SeekTo:不得再发 Play/ApplyWindow(否则会把播放头拽回窗口起点)",
            listOf(LoopPageEffect.SeekTo(1_458L)),
            seekEffects,
        )
        assertEquals("自由试听不显示环缓冲等待", PrefillState.NotStarted, ready.prefill)
        assertEquals("暂停/播放意图不变", PlaybackState.Playing, ready.playback)
    }

    @Test
    fun seekInsideNewWindowAfterAbChangeRestoresLoop() {
        // 自由试听中把 A 从 30s 拖到 34s,再 seek 到 40s:必须按**新**窗口判定为"回到区间"。
        val outsideWithNewWindow = ready(
            auditionOutside = true,
            positionMs = 10_000L,
            aMs = 34_000L,
            bMs = 60_000L,
        )
        val (next, effects) = LoopPageReducer.reduce(
            outsideWithNewWindow,
            LoopPageEvent.Seek(40_000L),
        )
        val ready = next as LoopPageState.Ready
        assertFalse("40s 在新窗口 [34s,60s) 内 → 恢复循环", ready.auditionOutside)
        assertEquals(PrefillState.FirstBlockLoading, ready.prefill)
        assertEquals(listOf(LoopPageEffect.SeekTo(40_000L)), effects)
    }

    @Test
    fun switchModeLeavesFreeAudition() {
        val outside = ready(auditionOutside = true, positionMs = 10_000L)
        val (next, effects) = LoopPageReducer.reduce(outside, LoopPageEvent.SwitchMode(LoopPageMode.SEAM))
        val ready = next as LoopPageState.Ready
        assertFalse("点模式 = 明确的循环试听意图", ready.auditionOutside)
        assertEquals(LoopPageMode.SEAM, ready.mode)
        assertEquals(
            "模式起点落点 + Play",
            listOf(
                LoopPageEffect.SeekTo(55_000L),
                LoopPageEffect.Play(LoopPageMode.SEAM, 55_000L),
            ),
            effects,
        )
    }

    @Test
    fun selectCandidateLeavesFreeAudition() {
        val outside = ready(auditionOutside = true, positionMs = 10_000L)
        val withCandidates = outside.copy(
            candidates = listOf(
                com.zjr.hesimusic.loop.analysis.LoopCandidateKt(
                    startSamples = 30_000L * 44_100L / 1000L,
                    endSamples = 60_000L * 44_100L / 1000L,
                    score = 1.0,
                    noteDistance = 0.1,
                    loudnessDifference = 0.2,
                ),
            ),
        )
        val (next, _) = LoopPageReducer.reduce(withCandidates, LoopPageEvent.SelectCandidate(0))
        assertFalse("选候选 = 明确的循环试听意图", (next as LoopPageState.Ready).auditionOutside)
    }

    @Test
    fun togglePlayPreservesFreeAudition() {
        val outside = ready(auditionOutside = true, positionMs = 10_000L)
        val (next, effects) = LoopPageReducer.reduce(outside, LoopPageEvent.TogglePlay)
        val ready = next as LoopPageState.Ready
        assertTrue("暂停/继续不得悄悄退出自由试听", ready.auditionOutside)
        assertEquals(listOf(LoopPageEffect.Pause), effects)
    }
}
