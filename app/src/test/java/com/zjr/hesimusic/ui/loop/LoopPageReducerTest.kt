package com.zjr.hesimusic.ui.loop

import com.zjr.hesimusic.loop.LoopWindow
import com.zjr.hesimusic.loop.analysis.LoopCandidateKt
import com.zjr.hesimusic.ui.loop.state.AnalysisPhase
import com.zjr.hesimusic.ui.loop.state.DragState
import com.zjr.hesimusic.ui.loop.state.LoopHandle
import com.zjr.hesimusic.ui.loop.state.LoopPageEffect
import com.zjr.hesimusic.ui.loop.state.LoopPageEvent
import com.zjr.hesimusic.ui.loop.state.LoopPageMode
import com.zjr.hesimusic.ui.loop.state.LoopPageReducer
import com.zjr.hesimusic.ui.loop.state.LoopPageState
import com.zjr.hesimusic.ui.loop.state.PlaybackState
import com.zjr.hesimusic.ui.loop.state.PrefillState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.math.roundToLong

/**
 * 状态 × 事件全矩阵测试（第一轮：纯模型）。
 *
 * 11 个代表性状态 × 29 个事件 = 319 个参数化用例。矩阵的默认期望是
 * **no-op 恒等**（同一个 state 实例 + 空 effect）；凡是有语义的转换都显式列在
 * [LoopPageMatrix.EXPECTATIONS] 里。这样任何“本应非法却偷偷改了状态”的组合都会红。
 */
@RunWith(Parameterized::class)
class LoopPageReducerTest(
    private val case: LoopPageMatrix.Case,
) {

    @Test
    fun matrixCaseMatchesSpec() {
        val (next, effects) = LoopPageReducer.reduce(case.state, case.event)
        when (val expected = case.expected) {
            LoopPageMatrix.Expectation.NoOp -> {
                assertSame("$case: no-op 必须返回同一 state 实例", case.state, next)
                assertEquals("$case: no-op 不得产生 effect", emptyList<LoopPageEffect>(), effects)
            }

            is LoopPageMatrix.Expectation.Transition -> {
                assertEquals("$case: 新状态", expected.state, next)
                assertEquals("$case: effect 序列", expected.effects, effects)
            }
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{index}: {0}")
        fun data(): List<Array<Any>> = LoopPageMatrix.CASES.map { arrayOf<Any>(it) }
    }
}

/** 矩阵完整性 + 所有可达状态的全局不变量。 */
class LoopPageReducerMatrixCoverageTest {

    @Test
    fun matrixCoversEveryStateTimesEveryEventExactlyOnce() {
        val stateCount = LoopPageMatrix.STATES.size
        val eventCount = LoopPageMatrix.EVENTS.size
        assertEquals("状态数 × 事件数", stateCount * eventCount, LoopPageMatrix.CASES.size)

        val keys = LoopPageMatrix.CASES.map { it.stateKey to it.eventKey }.toSet()
        assertEquals("不允许重复组合", stateCount * eventCount, keys.size)
        for (stateKey in LoopPageMatrix.STATES.keys) {
            for (eventKey in LoopPageMatrix.EVENTS.keys) {
                assertTrue("矩阵缺组合: $stateKey × $eventKey", (stateKey to eventKey) in keys)
            }
        }
    }

    @Test
    fun everyReductionKeepsReadyInvariants() {
        for (case in LoopPageMatrix.CASES) {
            val (next, _) = LoopPageReducer.reduce(case.state, case.event)
            assertReadyInvariants(next, case.toString())
        }
    }

    private fun assertReadyInvariants(state: LoopPageState, label: String) {
        if (state !is LoopPageState.Ready) return

        assertTrue("$label: aMs >= 0", state.aMs >= 0L)
        assertTrue("$label: bMs > aMs", state.bMs > state.aMs)
        assertTrue(
            "$label: 循环段长度 >= ${LoopPageReducer.MIN_LOOP_LENGTH_MS}ms",
            state.bMs - state.aMs >= LoopPageReducer.MIN_LOOP_LENGTH_MS,
        )
        assertTrue("$label: bMs <= durationMs", state.bMs <= state.durationMs)
        assertTrue("$label: positionMs 在 [0,durationMs]", state.positionMs in 0L..state.durationMs)

        if (state.candidates.isEmpty()) {
            assertEquals("$label: 无候选时 selectedCandidate 必须为 0", 0, state.selectedCandidate)
        } else {
            assertTrue("$label: selectedCandidate 必须落在 candidates.indices", state.selectedCandidate in state.candidates.indices)
        }

        when (val drag = state.drag) {
            is DragState.Dragging -> {
                val activeMs = if (drag.handle == LoopHandle.A) state.aMs else state.bMs
                assertEquals("$label: ${drag.handle} 手柄 ms 必须等于当前 A/B", drag.ms, activeMs)
            }

            DragState.Idle -> Unit
        }

        if (state.playback is PlaybackState.AwaitingPrefill) {
            assertTrue(
                "$label: AwaitingPrefill 必须伴随 InProgress prefill（保证后续一定有 Play/Failed 补发）",
                state.prefill is PrefillState.InProgress,
            )
        }
    }
}

/**
 * 已报 bug 的模型级回归断言（00008 / 00009）以及非法操作的恒等断言。
 */
class LoopPageReducerRegressionTest {

    private val base: LoopPageState.Ready = LoopPageMatrix.ready()

    private fun seekTargets(effects: List<LoopPageEffect>): List<Long> =
        effects.filterIsInstance<LoopPageEffect.SeekTo>().map { it.positionMs }

    private fun playEffects(effects: List<LoopPageEffect>): List<LoopPageEffect.Play> =
        effects.filterIsInstance<LoopPageEffect.Play>()

    // ------------------------------------------------------------------ 00008

    @Test
    fun issue00008_repeatedSeamSwitchAlwaysReturnsToBMinusFiveSeconds() {
        val (afterFirst, firstEffects) = LoopPageReducer.reduce(
            base,
            LoopPageEvent.SwitchMode(LoopPageMode.SEAM),
        )
        assertEquals(
            "首次切接缝检查:一次 SeekTo(B−5s) + Play(SEAM,B−5s)",
            listOf(
                LoopPageEffect.SeekTo(55_000L),
                LoopPageEffect.Play(LoopPageMode.SEAM, 55_000L),
            ),
            firstEffects,
        )
        assertTrue("不得 seek 到区段中间(45s)", 45_000L !in seekTargets(firstEffects))

        // 用户口径:重复点击必须每次回到 B−5s 重新起播,不是 no-op。
        val (afterSecond, secondEffects) = LoopPageReducer.reduce(
            afterFirst,
            LoopPageEvent.SwitchMode(LoopPageMode.SEAM),
        )
        assertEquals(
            "第二次点接缝检查也必须 SeekTo(B−5s) + Play(SEAM,B−5s)",
            listOf(
                LoopPageEffect.SeekTo(55_000L),
                LoopPageEffect.Play(LoopPageMode.SEAM, 55_000L),
            ),
            secondEffects,
        )
        assertTrue("第二次不得 seek 到区段中间(45s)", 45_000L !in seekTargets(secondEffects))
        val secondReady = afterSecond as LoopPageState.Ready
        assertEquals("第二次点击后位置回到 B−5s", 55_000L, secondReady.positionMs)
        assertEquals(PlaybackState.Playing, secondReady.playback)

        // 播放位置 tick 之后再次点「接缝检查」:仍然回到 B−5s。
        val (afterTick, tickEffects) = LoopPageReducer.reduce(afterSecond, LoopPageEvent.Tick(56_000L))
        assertEquals(emptyList<LoopPageEffect>(), tickEffects)
        assertEquals(56_000L, (afterTick as LoopPageState.Ready).positionMs)
        val (afterThird, thirdEffects) = LoopPageReducer.reduce(
            afterTick,
            LoopPageEvent.SwitchMode(LoopPageMode.SEAM),
        )
        assertEquals(
            "第三次点接缝检查仍回到 B−5s",
            listOf(
                LoopPageEffect.SeekTo(55_000L),
                LoopPageEffect.Play(LoopPageMode.SEAM, 55_000L),
            ),
            thirdEffects,
        )
        assertEquals(55_000L, (afterThird as LoopPageState.Ready).positionMs)
    }

    @Test
    fun seamStartClampsToAWhenLoopShorterThanFiveSeconds() {
        val shortLoop = LoopPageMatrix.ready(
            aMs = 30_000L,
            bMs = 32_000L,
            positionMs = 30_000L,
        )
        val (next, effects) = LoopPageReducer.reduce(
            shortLoop,
            LoopPageEvent.SwitchMode(LoopPageMode.SEAM),
        )
        assertEquals(
            "B−5s < A 时取 A",
            listOf(LoopPageEffect.SeekTo(30_000L)),
            effects.filterIsInstance<LoopPageEffect.SeekTo>(),
        )
        assertEquals(
            listOf(LoopPageEffect.Play(LoopPageMode.SEAM, 30_000L)),
            effects.filterIsInstance<LoopPageEffect.Play>(),
        )
        assertEquals(30_000L, (next as LoopPageState.Ready).positionMs)
    }

    @Test
    fun issue00008_pausedSeamSwitchRestartsAtBMinusFiveSeconds() {
        val (paused, pauseEffects) = LoopPageReducer.reduce(base, LoopPageEvent.TogglePlay)
        assertEquals("播放中切换播放键应暂停", listOf(LoopPageEffect.Pause), pauseEffects)

        val (_, effects) = LoopPageReducer.reduce(paused, LoopPageEvent.SwitchMode(LoopPageMode.SEAM))
        assertEquals("暂停态重新点接缝检查仍从 B−5s 起", listOf(55_000L), seekTargets(effects))
        assertEquals(
            listOf(LoopPageEffect.Play(LoopPageMode.SEAM, 55_000L)),
            playEffects(effects),
        )
    }

    // ------------------------------------------------------------------ 00009

    @Test
    fun issue00009_selectCandidateWhilePlayingNeverPausesAndPlaysAfterPrefillReady() {
        val (next, effects) = LoopPageReducer.reduce(base, LoopPageEvent.SelectCandidate(1))

        assertTrue("换候选不得产生多余 Pause", effects.none { it is LoopPageEffect.Pause })
        assertEquals(
            "必须取消旧预填 + 为新窗口启动预填 + 回到模式起点",
            listOf(
                LoopPageEffect.CancelPrefill,
                LoopPageEffect.StartPrefill(LoopWindow(7L, 10_000_000L, 50_000_000L)),
                LoopPageEffect.SeekTo(0L),
            ),
            effects,
        )
        assertTrue(
            "换候选后必须进入 AwaitingPrefill",
            next is LoopPageState.Ready && next.playback is PlaybackState.AwaitingPrefill,
        )
        val nextReady = next as LoopPageState.Ready
        assertEquals(PrefillState.InProgress(0f), nextReady.prefill)

        // 预填期间位置可以继续动；但状态必须显式标记“等待预填”，不能假装有输出。
        val (ticking, tickEffects) = LoopPageReducer.reduce(nextReady, LoopPageEvent.Tick(1_500L))
        assertEquals(emptyList<LoopPageEffect>(), tickEffects)
        val tickingReady = ticking as LoopPageState.Ready
        assertEquals(1_500L, tickingReady.positionMs)
        assertEquals(PlaybackState.AwaitingPrefill(LoopPageMode.FULL, 0L), tickingReady.playback)

        // 预填完成必须补发 Play —— 这是 00009 的“必然有声音”断言。
        val (afterReady, readyEffects) = LoopPageReducer.reduce(tickingReady, LoopPageEvent.PrefillReady)
        assertEquals(
            "预填完成后必须补发 Play（否则就是位置在动但无输出）",
            listOf(LoopPageEffect.Play(LoopPageMode.FULL, 0L)),
            readyEffects,
        )
        val afterReadyState = afterReady as LoopPageState.Ready
        assertEquals(PlaybackState.Playing, afterReadyState.playback)
        assertEquals(PrefillState.Ready, afterReadyState.prefill)
        assertEquals(0L, afterReadyState.positionMs)
    }

    @Test
    fun issue00009_commitWindowWhilePlayingNeverPausesAndPlaysAfterPrefillReady() {
        var state: LoopPageState = base
        var commitEffects: List<LoopPageEffect> = emptyList()
        val events = listOf(
            LoopPageEvent.DragAStart(32_000L),
            LoopPageEvent.DragAMove(35_000L),
            LoopPageEvent.DragAEnd,
            LoopPageEvent.CommitWindow,
        )
        for (event in events) {
            val (next, effects) = LoopPageReducer.reduce(state, event)
            state = next
            if (event == LoopPageEvent.CommitWindow) commitEffects = effects
        }

        assertTrue("CommitWindow 不得产生多余 Pause", commitEffects.none { it is LoopPageEffect.Pause })
        assertEquals(
            listOf(
                LoopPageEffect.CancelPrefill,
                LoopPageEffect.StartPrefill(LoopWindow(7L, 35_000_000L, 60_000_000L)),
                LoopPageEffect.SeekTo(0L),
            ),
            commitEffects,
        )
        val ready = state as LoopPageState.Ready
        assertEquals("拖动松手后必须进入 AwaitingPrefill", PlaybackState.AwaitingPrefill(LoopPageMode.FULL, 0L), ready.playback)

        val (afterReady, readyEffects) = LoopPageReducer.reduce(ready, LoopPageEvent.PrefillReady)
        assertEquals(listOf(LoopPageEffect.Play(LoopPageMode.FULL, 0L)), readyEffects)
        assertEquals(PlaybackState.Playing, (afterReady as LoopPageState.Ready).playback)
    }

    // ------------------------------------------------------------------ 保存 / 预填失败

    @Test
    fun togglePlayFromStoppedStartsImmediatelyWithoutAwaiting() {
        val stopped = LoopPageMatrix.ready(
            playback = PlaybackState.Stopped,
            prefill = PrefillState.InProgress(0f, 0L, 12_000L),
            positionMs = 0L,
        )
        val (next, effects) = LoopPageReducer.reduce(stopped, LoopPageEvent.TogglePlay)
        assertEquals(
            "首次起播必须立即 Play,不能等预填",
            listOf(LoopPageEffect.Play(LoopPageMode.FULL, 0L)),
            effects,
        )
        val ready = next as LoopPageState.Ready
        assertEquals(PlaybackState.Playing, ready.playback)
        assertTrue(ready.playback !is PlaybackState.AwaitingPrefill)
    }

    @Test
    fun prefillProgressWhilePlayingOnlyUpdatesProgress() {
        val playing = LoopPageMatrix.ready(
            playback = PlaybackState.Playing,
            prefill = PrefillState.InProgress(0f, 0L, 12_000L),
        )
        val (next, effects) = LoopPageReducer.reduce(
            playing,
            LoopPageEvent.PrefillProgress(0.16f, 2_000L, 12_000L),
        )
        assertEquals(emptyList<LoopPageEffect>(), effects)
        val ready = next as LoopPageState.Ready
        assertEquals(PrefillState.InProgress(0.16f, 2_000L, 12_000L), ready.prefill)
        assertEquals(PlaybackState.Playing, ready.playback)
    }

    @Test
    fun prefillPartialReadyStartsPlaybackAndProgressDoesNotInterruptIt() {
        val awaiting = LoopPageMatrix.ready(
            prefill = PrefillState.InProgress(0.3f, 4_000L, 12_000L),
            playback = PlaybackState.AwaitingPrefill(LoopPageMode.FULL, 0L),
            positionMs = 0L,
        )
        val (partial, partialEffects) = LoopPageReducer.reduce(
            awaiting,
            LoopPageEvent.PrefillPartialReady(8_000L, 12_000L),
        )
        assertEquals(
            "前缀就绪立即补 Play",
            listOf(LoopPageEffect.Play(LoopPageMode.FULL, 0L)),
            partialEffects,
        )
        val partialReady = partial as LoopPageState.Ready
        assertEquals(PrefillState.Partial(8_000L, 12_000L), partialReady.prefill)
        assertEquals(PlaybackState.Playing, partialReady.playback)
        assertEquals(0L, partialReady.positionMs)

        // 前缀就绪后进度继续推进:只更新 PrefillState,不改播放状态/不产生 effect。
        val (advanced, progressEffects) = LoopPageReducer.reduce(
            partialReady,
            LoopPageEvent.PrefillProgress(0.9f, 11_000L, 12_000L),
        )
        assertEquals(emptyList<LoopPageEffect>(), progressEffects)
        val advancedReady = advanced as LoopPageState.Ready
        assertEquals(PrefillState.Partial(11_000L, 12_000L), advancedReady.prefill)
        assertEquals(PlaybackState.Playing, advancedReady.playback)
        assertEquals(0L, advancedReady.positionMs)
    }

    @Test
    fun prefillPartialReadyWithoutAwaitingDoesNotPlay() {
        val playing = LoopPageMatrix.ready(
            prefill = PrefillState.InProgress(0.3f, 4_000L, 12_000L),
            playback = PlaybackState.Playing,
        )
        val (next, effects) = LoopPageReducer.reduce(
            playing,
            LoopPageEvent.PrefillPartialReady(8_000L, 12_000L),
        )
        assertEquals(emptyList<LoopPageEffect>(), effects)
        val ready = next as LoopPageState.Ready
        assertEquals(PrefillState.Partial(8_000L, 12_000L), ready.prefill)
        assertEquals(PlaybackState.Playing, ready.playback)
    }

    @Test
    fun confirmAndSaveWorksWhenPrefillIsNotReady() {
        val notStarted = LoopPageMatrix.ready(
            prefill = PrefillState.NotStarted,
            playback = PlaybackState.Stopped,
            positionMs = 0L,
        )
        val (saved, effects) = LoopPageReducer.reduce(notStarted, LoopPageEvent.ConfirmAndSave)
        assertEquals(
            listOf(LoopPageEffect.PersistConfirm(7L, LoopWindow(7L, 30_000_000L, 60_000_000L))),
            effects,
        )
        assertTrue("预填未开始时也必须能保存", (saved as LoopPageState.Ready).confirmed)

        val inProgress = LoopPageMatrix.ready(prefill = PrefillState.InProgress(0.2f))
        val (saved2, effects2) = LoopPageReducer.reduce(inProgress, LoopPageEvent.ConfirmAndSave)
        assertEquals(1, effects2.count { it is LoopPageEffect.PersistConfirm })
        assertTrue((saved2 as LoopPageState.Ready).confirmed)

        val awaiting = LoopPageMatrix.ready(
            prefill = PrefillState.InProgress(0.3f),
            playback = PlaybackState.AwaitingPrefill(LoopPageMode.FULL, 0L),
            positionMs = 0L,
        )
        val (saved3, effects3) = LoopPageReducer.reduce(awaiting, LoopPageEvent.ConfirmAndSave)
        assertEquals(1, effects3.count { it is LoopPageEffect.PersistConfirm })
        assertTrue("预填进行中/AwaitingPrefill 时也必须能保存", (saved3 as LoopPageState.Ready).confirmed)
    }

    @Test
    fun prefillFailureWhileAwaitingFallsBackToPlayInsteadOfStayingSilent() {
        val awaiting = LoopPageMatrix.ready(
            prefill = PrefillState.InProgress(0.6f),
            playback = PlaybackState.AwaitingPrefill(LoopPageMode.SEAM, 55_000L),
            mode = LoopPageMode.SEAM,
            positionMs = 55_000L,
        )
        val (next, effects) = LoopPageReducer.reduce(awaiting, LoopPageEvent.PrefillFailed("decode boom"))
        assertEquals(
            "预填失败也必须补发 Play（降级直通），不得留下“位置在动但无声”",
            listOf(
                LoopPageEffect.Play(LoopPageMode.SEAM, 55_000L),
                LoopPageEffect.Toast(LoopPageReducer.PREFILL_FALLBACK_TOAST),
            ),
            effects,
        )
        val nextReady = next as LoopPageState.Ready
        assertEquals(PrefillState.Failed("decode boom"), nextReady.prefill)
        assertEquals(PlaybackState.Playing, nextReady.playback)
        assertEquals(55_000L, nextReady.positionMs)
    }

    // ------------------------------------------------------------------ 非法操作 / 边界

    @Test
    fun illegalOperationsAreIdentityNoOps() {
        val failed = LoopPageState.Failed("boom")
        for (event in listOf(
            LoopPageEvent.DragAStart(10_000L),
            LoopPageEvent.DragAMove(10_000L),
            LoopPageEvent.DragAEnd,
            LoopPageEvent.CommitWindow,
            LoopPageEvent.ConfirmAndSave,
            LoopPageEvent.TogglePlay,
            LoopPageEvent.Tick(1_000L),
            LoopPageEvent.SwitchMode(LoopPageMode.SEAM),
            LoopPageEvent.SelectCandidate(0),
        )) {
            val (next, effects) = LoopPageReducer.reduce(failed, event)
            assertSame("Failed 态 $event 必须恒等", failed, next)
            assertEquals("Failed 态 $event 不得产生 effect", emptyList<LoopPageEffect>(), effects)
        }

        val analyzing = LoopPageState.Analyzing(AnalysisPhase.ANALYZING, 0.7f)
        for (event in listOf(
            LoopPageEvent.ConfirmAndSave,
            LoopPageEvent.Tick(1_000L),
            LoopPageEvent.SwitchMode(LoopPageMode.SEAM),
            LoopPageEvent.PrefillReady,
            LoopPageEvent.SelectCandidate(0),
        )) {
            val (next, effects) = LoopPageReducer.reduce(analyzing, event)
            assertSame("Analyzing 态 $event 必须恒等", analyzing, next)
            assertEquals("Analyzing 态 $event 不得产生 effect", emptyList<LoopPageEffect>(), effects)
        }
    }

    @Test
    fun invalidCandidateAndBrokenDragSequenceAreIdentityNoOps() {
        val (badIndex, badEffects) = LoopPageReducer.reduce(base, LoopPageEvent.SelectCandidate(9))
        assertSame("越界候选必须恒等", base, badIndex)
        assertEquals(emptyList<LoopPageEffect>(), badEffects)

        val (moveWithoutStart, moveEffects) = LoopPageReducer.reduce(base, LoopPageEvent.DragAMove(33_000L))
        assertSame("未 Start 的 DragMove 必须恒等", base, moveWithoutStart)
        assertEquals(emptyList<LoopPageEffect>(), moveEffects)

        val (endWithoutStart, endEffects) = LoopPageReducer.reduce(base, LoopPageEvent.DragAEnd)
        assertSame("未 Start 的 DragEnd 必须恒等", base, endWithoutStart)
        assertEquals(emptyList<LoopPageEffect>(), endEffects)

        val draggingA = LoopPageMatrix.ready(drag = DragState.Dragging(LoopHandle.A, 32_000L), aMs = 32_000L)
        val (wrongEnd, wrongEndEffects) = LoopPageReducer.reduce(draggingA, LoopPageEvent.DragBEnd)
        assertSame("拖 A 时结束 B 必须恒等", draggingA, wrongEnd)
        assertEquals(emptyList<LoopPageEffect>(), wrongEndEffects)
    }

    @Test
    fun demoteAndDeleteRejectInapplicableOperations() {
        val (demoted, demoteEffects) = LoopPageReducer.reduce(base, LoopPageEvent.Demote)
        assertSame("未 confirmed 时 Demote 必须恒等", base, demoted)
        assertEquals(emptyList<LoopPageEffect>(), demoteEffects)

        val noCandidates = base.copy(candidates = emptyList(), selectedCandidate = 0)
        val (deleted, deleteEffects) = LoopPageReducer.reduce(noCandidates, LoopPageEvent.Delete)
        assertSame("无候选时 Delete 必须恒等", noCandidates, deleted)
        assertEquals(emptyList<LoopPageEffect>(), deleteEffects)
    }

    @Test
    fun tickAndSeekClampToDuration() {
        val (ticked, tickEffects) = LoopPageReducer.reduce(base, LoopPageEvent.Tick(999_999L))
        assertEquals(emptyList<LoopPageEffect>(), tickEffects)
        assertEquals(120_000L, (ticked as LoopPageState.Ready).positionMs)

        val (seeked, seekEffects) = LoopPageReducer.reduce(base, LoopPageEvent.Seek(-5L))
        assertEquals(listOf(LoopPageEffect.SeekTo(0L)), seekEffects)
        assertEquals(0L, (seeked as LoopPageState.Ready).positionMs)
    }

    @Test
    fun modeStartPositionsMatchPreviewPlayerContract() {
        val state = LoopPageMatrix.ready(aMs = 30_000L, bMs = 60_000L)

        val (_, loopOnlyEffects) = LoopPageReducer.reduce(state, LoopPageEvent.SwitchMode(LoopPageMode.LOOP_ONLY))
        assertEquals("仅循环段从 A−300ms 预卷", listOf(29_700L), seekTargets(loopOnlyEffects))
        assertEquals(listOf(LoopPageEffect.Play(LoopPageMode.LOOP_ONLY, 29_700L)), playEffects(loopOnlyEffects))

        val (_, freeEffects) = LoopPageReducer.reduce(state, LoopPageEvent.SwitchMode(LoopPageMode.FREE))
        assertEquals("自由模式从 0 起播", listOf(0L), seekTargets(freeEffects))
        assertEquals(listOf(LoopPageEffect.Play(LoopPageMode.FREE, 0L)), playEffects(freeEffects))
    }

    @Test
    fun backFromEveryStateOnlyNavigatesBack() {
        for ((key, state) in LoopPageMatrix.STATES) {
            val (next, effects) = LoopPageReducer.reduce(state, LoopPageEvent.Back)
            assertSame("$key: Back 不得改状态", state, next)
            assertEquals("$key: Back 只发 NavigateBack", listOf(LoopPageEffect.NavigateBack), effects)
        }
    }
}

/**
 * 矩阵夹具 / 期望表。
 *
 * 新增状态或事件时，这里自动扩展笛卡尔积；没有显式期望的组合一律按 no-op 恒等断言。
 */
object LoopPageMatrix {

    const val SONG_ID = 7L
    const val DURATION_MS = 120_000L
    const val SAMPLE_RATE = 44_100
    const val A0 = 30_000L
    const val B0 = 60_000L
    const val MIN_LOOP = 500L

    sealed interface Expectation {
        data object NoOp : Expectation
        data class Transition(
            val state: LoopPageState,
            val effects: List<LoopPageEffect>,
        ) : Expectation
    }

    data class Case(
        val stateKey: String,
        val eventKey: String,
        val state: LoopPageState,
        val event: LoopPageEvent,
        val expected: Expectation,
    ) {
        override fun toString(): String = "$stateKey × $eventKey"
    }

    private fun candidate(aMs: Long, bMs: Long): LoopCandidateKt = LoopCandidateKt(
        startSamples = aMs * SAMPLE_RATE / 1000L,
        endSamples = bMs * SAMPLE_RATE / 1000L,
        score = 1.0,
        noteDistance = 0.1,
        loudnessDifference = 0.2,
    )

    val CANDIDATES: List<LoopCandidateKt> = listOf(
        candidate(30_000L, 60_000L),
        candidate(10_000L, 50_000L),
        candidate(40_000L, 90_000L),
    )

    fun ready(
        songId: Long = SONG_ID,
        durationMs: Long = DURATION_MS,
        sampleRate: Int = SAMPLE_RATE,
        mode: LoopPageMode = LoopPageMode.FULL,
        aMs: Long = A0,
        bMs: Long = B0,
        candidates: List<LoopCandidateKt> = CANDIDATES,
        selectedCandidate: Int = 0,
        confirmed: Boolean = false,
        prefill: PrefillState = PrefillState.Ready,
        playback: PlaybackState = PlaybackState.Playing,
        positionMs: Long = 35_000L,
        drag: DragState = DragState.Idle,
    ): LoopPageState.Ready = LoopPageState.Ready(
        songId = songId,
        durationMs = durationMs,
        sampleRate = sampleRate,
        mode = mode,
        aMs = aMs,
        bMs = bMs,
        candidates = candidates,
        selectedCandidate = selectedCandidate,
        confirmed = confirmed,
        prefill = prefill,
        playback = playback,
        positionMs = positionMs,
        drag = drag,
    )

    val STATES: Map<String, LoopPageState> = linkedMapOf(
        "Loading" to LoopPageState.Loading,
        "Analyzing" to LoopPageState.Analyzing(AnalysisPhase.DECODING, 0.25f),
        "Ready.NotStarted.Stopped" to ready(
            prefill = PrefillState.NotStarted,
            playback = PlaybackState.Stopped,
            positionMs = 0L,
        ),
        "Ready.PrefillInProgress.Playing" to ready(prefill = PrefillState.InProgress(0.4f)),
        "Ready.PrefixPartial.Playing" to ready(
            prefill = PrefillState.Partial(8_000L, 12_000L),
        ),
        "Ready.PrefillReady.Playing" to ready(),
        "Ready.AwaitingPrefill" to ready(
            prefill = PrefillState.InProgress(0.7f),
            playback = PlaybackState.AwaitingPrefill(LoopPageMode.FULL, 0L),
            positionMs = 0L,
        ),
        "Ready.DraggingA" to ready(
            aMs = 32_000L,
            drag = DragState.Dragging(LoopHandle.A, 32_000L),
        ),
        "Ready.DraggingB" to ready(
            bMs = 65_000L,
            drag = DragState.Dragging(LoopHandle.B, 65_000L),
        ),
        "Ready.Confirmed.Paused" to ready(
            confirmed = true,
            playback = PlaybackState.Paused,
        ),
        "Ready.Seam.Playing" to ready(
            mode = LoopPageMode.SEAM,
            playback = PlaybackState.Playing,
            positionMs = 55_000L,
        ),
    )

    val ANALYSIS_DONE: LoopPageEvent.AnalysisDone = LoopPageEvent.AnalysisDone(
        songId = SONG_ID,
        candidates = CANDIDATES,
        sampleRate = SAMPLE_RATE,
        durationMs = DURATION_MS,
        confirmed = false,
    )

    val EVENTS: Map<String, LoopPageEvent> = linkedMapOf(
        "Enter(noSaved)" to LoopPageEvent.Enter(false),
        "Enter(hasSaved)" to LoopPageEvent.Enter(true),
        "AnalysisProgress" to LoopPageEvent.AnalysisProgress(AnalysisPhase.DECODING, 0.3f),
        "AnalysisDone" to ANALYSIS_DONE,
        "AnalysisFailed" to LoopPageEvent.AnalysisFailed("analysis boom"),
        "Retry" to LoopPageEvent.Retry,
        "ManualReanalyze" to LoopPageEvent.ManualReanalyze,
        "SelectCandidate(1)" to LoopPageEvent.SelectCandidate(1),
        "DragAStart" to LoopPageEvent.DragAStart(32_000L),
        "DragAMove" to LoopPageEvent.DragAMove(33_000L),
        "DragAEnd" to LoopPageEvent.DragAEnd,
        "DragBStart" to LoopPageEvent.DragBStart(65_000L),
        "DragBMove" to LoopPageEvent.DragBMove(66_000L),
        "DragBEnd" to LoopPageEvent.DragBEnd,
        "CommitWindow" to LoopPageEvent.CommitWindow,
        "SwitchMode(SEAM)" to LoopPageEvent.SwitchMode(LoopPageMode.SEAM),
        "SwitchMode(FULL)" to LoopPageEvent.SwitchMode(LoopPageMode.FULL),
        "TogglePlay" to LoopPageEvent.TogglePlay,
        "Tick" to LoopPageEvent.Tick(70_000L),
        "PrefillStarted" to LoopPageEvent.PrefillStarted,
        "PrefillProgress" to LoopPageEvent.PrefillProgress(0.8f, 8_000L, 12_000L),
        "PrefillPartialReady" to LoopPageEvent.PrefillPartialReady(8_000L, 12_000L),
        "PrefillReady" to LoopPageEvent.PrefillReady,
        "PrefillFailed" to LoopPageEvent.PrefillFailed("prefill boom"),
        "ConfirmAndSave" to LoopPageEvent.ConfirmAndSave,
        "Demote" to LoopPageEvent.Demote,
        "Delete" to LoopPageEvent.Delete,
        "Back" to LoopPageEvent.Back,
        "Seek" to LoopPageEvent.Seek(70_000L),
    )

    private fun window(aMs: Long, bMs: Long): LoopWindow =
        LoopWindow(SONG_ID, aMs * 1000L, bMs * 1000L)

    private fun candidateMs(
        candidate: LoopCandidateKt,
        sampleRate: Int,
        durationMs: Long,
    ): Pair<Long, Long> {
        val sr = if (sampleRate > 0) sampleRate else 44_100
        val maxA = (durationMs - MIN_LOOP).coerceAtLeast(0L)
        val aMs = (candidate.startSamples * 1000.0 / sr).roundToLong().coerceIn(0L, maxA)
        val bMs = (candidate.endSamples * 1000.0 / sr).roundToLong()
            .coerceIn(aMs + MIN_LOOP, durationMs)
        return aMs to bMs
    }

    private fun modeStart(mode: LoopPageMode, aMs: Long, bMs: Long): Long = when (mode) {
        LoopPageMode.FULL -> 0L
        LoopPageMode.LOOP_ONLY -> (aMs - 300L).coerceAtLeast(0L)
        // 极短循环段 B−5s < A 时取 A。
        LoopPageMode.SEAM -> maxOf(aMs, bMs - 5_000L)
        LoopPageMode.FREE -> 0L
    }

    private fun analysisDoneExpected(): Expectation.Transition {
        val first = CANDIDATES.first()
        val (aMs, bMs) = candidateMs(first, SAMPLE_RATE, DURATION_MS)
        val state = ready(
            aMs = aMs,
            bMs = bMs,
            prefill = PrefillState.NotStarted,
            playback = PlaybackState.Stopped,
            positionMs = 0L,
        )
        return Expectation.Transition(
            state,
            listOf(LoopPageEffect.StartPrefill(window(aMs, bMs))),
        )
    }

    private fun selectExpected(state: LoopPageState.Ready): Expectation.Transition {
        val candidate = state.candidates[1]
        val (aMs, bMs) = candidateMs(candidate, state.sampleRate, state.durationMs)
        val startMs = modeStart(state.mode, aMs, bMs)
        val auditionImmediately = state.playback !is PlaybackState.Playing &&
            state.playback !is PlaybackState.AwaitingPrefill
        val next = state.copy(
            selectedCandidate = 1,
            aMs = aMs,
            bMs = bMs,
            positionMs = startMs,
            drag = DragState.Idle,
            prefill = PrefillState.InProgress(0f),
            playback = if (auditionImmediately) {
                PlaybackState.Playing
            } else {
                PlaybackState.AwaitingPrefill(state.mode, startMs)
            },
        )
        val effects = mutableListOf<LoopPageEffect>(
            LoopPageEffect.CancelPrefill,
            LoopPageEffect.StartPrefill(window(aMs, bMs)),
            LoopPageEffect.SeekTo(startMs),
        )
        if (auditionImmediately) {
            effects += LoopPageEffect.Play(state.mode, startMs)
        }
        return Expectation.Transition(next, effects)
    }

    private fun commitExpected(state: LoopPageState.Ready): Expectation.Transition {
        val startMs = modeStart(state.mode, state.aMs, state.bMs)
        val active = state.playback is PlaybackState.Playing ||
            state.playback is PlaybackState.AwaitingPrefill
        val playback = when (state.playback) {
            PlaybackState.Playing, is PlaybackState.AwaitingPrefill ->
                PlaybackState.AwaitingPrefill(state.mode, startMs)

            else -> state.playback
        }
        val next = state.copy(
            positionMs = startMs,
            drag = DragState.Idle,
            prefill = if (active) PrefillState.InProgress(0f) else PrefillState.NotStarted,
            playback = playback,
        )
        return Expectation.Transition(
            next,
            listOf(
                LoopPageEffect.CancelPrefill,
                LoopPageEffect.StartPrefill(window(state.aMs, state.bMs)),
                LoopPageEffect.SeekTo(startMs),
            ),
        )
    }

    private fun switchExpected(
        state: LoopPageState.Ready,
        mode: LoopPageMode,
    ): Expectation.Transition {
        val startMs = modeStart(mode, state.aMs, state.bMs)
        return Expectation.Transition(
            state.copy(mode = mode, playback = PlaybackState.Playing, positionMs = startMs),
            listOf(
                LoopPageEffect.SeekTo(startMs),
                LoopPageEffect.Play(mode, startMs),
            ),
        )
    }

    private fun toggleExpected(state: LoopPageState.Ready): Expectation.Transition = when (state.playback) {
        PlaybackState.Playing, is PlaybackState.AwaitingPrefill ->
            Expectation.Transition(state.copy(playback = PlaybackState.Paused), listOf(LoopPageEffect.Pause))

        PlaybackState.Paused, PlaybackState.Stopped ->
            Expectation.Transition(
                state.copy(playback = PlaybackState.Playing),
                listOf(LoopPageEffect.Play(state.mode, state.positionMs)),
            )
    }

    private fun expectations(): Map<Pair<String, String>, Expectation> {
        val map = mutableMapOf<Pair<String, String>, Expectation>()

        fun expect(stateKey: String, eventKey: String, expectation: Expectation) {
            map[stateKey to eventKey] = expectation
        }

        // ---- Loading / Analyzing / Failed ----
        expect(
            "Loading",
            "Enter(noSaved)",
            Expectation.Transition(
                LoopPageState.Analyzing(AnalysisPhase.DECODING, 0f),
                listOf(LoopPageEffect.RestartAnalysis),
            ),
        )
        expect(
            "Loading",
            "AnalysisProgress",
            Expectation.Transition(LoopPageState.Analyzing(AnalysisPhase.DECODING, 0.3f), emptyList()),
        )
        expect("Loading", "AnalysisDone", analysisDoneExpected())
        expect(
            "Loading",
            "AnalysisFailed",
            Expectation.Transition(LoopPageState.Failed("analysis boom"), emptyList()),
        )

        expect(
            "Analyzing",
            "AnalysisProgress",
            Expectation.Transition(LoopPageState.Analyzing(AnalysisPhase.DECODING, 0.3f), emptyList()),
        )
        expect("Analyzing", "AnalysisDone", analysisDoneExpected())
        expect(
            "Analyzing",
            "AnalysisFailed",
            Expectation.Transition(LoopPageState.Failed("analysis boom"), emptyList()),
        )

        expect(
            "Failed",
            "Retry",
            Expectation.Transition(
                LoopPageState.Analyzing(AnalysisPhase.DECODING, 0f),
                listOf(LoopPageEffect.RestartAnalysis),
            ),
        )

        // 页内「重新分析」:Loading / Failed / Ready 都转 Analyzing;Analyzing 时 no-op。
        expect(
            "Loading",
            "ManualReanalyze",
            Expectation.Transition(
                LoopPageState.Analyzing(AnalysisPhase.DECODING, 0f),
                listOf(LoopPageEffect.RestartAnalysis),
            ),
        )
        expect(
            "Failed",
            "ManualReanalyze",
            Expectation.Transition(
                LoopPageState.Analyzing(AnalysisPhase.DECODING, 0f),
                listOf(LoopPageEffect.RestartAnalysis),
            ),
        )

        // Back 对所有状态有效，只发 NavigateBack，不改状态。
        for ((stateKey, state) in STATES) {
            expect(stateKey, "Back", Expectation.Transition(state, listOf(LoopPageEffect.NavigateBack)))
        }

        // ---- Ready × 所有事件 ----
        for ((stateKey, state) in STATES) {
            val ready = state as? LoopPageState.Ready ?: continue

            expect(
                stateKey,
                "ManualReanalyze",
                Expectation.Transition(
                    LoopPageState.Analyzing(AnalysisPhase.DECODING, 0f),
                    listOf(LoopPageEffect.RestartAnalysis),
                ),
            )
            expect(stateKey, "SelectCandidate(1)", selectExpected(ready))
            expect(stateKey, "CommitWindow", commitExpected(ready))
            expect(stateKey, "TogglePlay", toggleExpected(ready))
            expect(
                stateKey,
                "Tick",
                Expectation.Transition(ready.copy(positionMs = 70_000L), emptyList()),
            )
            expect(
                stateKey,
                "Seek",
                Expectation.Transition(
                    ready.copy(positionMs = 70_000L),
                    listOf(LoopPageEffect.SeekTo(70_000L)),
                ),
            )
            expect(
                stateKey,
                "ConfirmAndSave",
                Expectation.Transition(
                    ready.copy(confirmed = true),
                    listOf(LoopPageEffect.PersistConfirm(ready.songId, window(ready.aMs, ready.bMs))),
                ),
            )

            if (ready.candidates.isNotEmpty()) {
                val deleted = ready.copy(
                    candidates = emptyList(),
                    selectedCandidate = 0,
                    confirmed = false,
                    drag = DragState.Idle,
                    prefill = PrefillState.NotStarted,
                    playback = if (ready.playback is PlaybackState.AwaitingPrefill) {
                        PlaybackState.Paused
                    } else {
                        ready.playback
                    },
                )
                expect(
                    stateKey,
                    "Delete",
                    Expectation.Transition(
                        deleted,
                        listOf(
                            LoopPageEffect.CancelPrefill,
                            LoopPageEffect.PersistDelete(ready.songId),
                        ),
                    ),
                )
            }

            if (ready.confirmed) {
                expect(
                    stateKey,
                    "Demote",
                    Expectation.Transition(
                        ready.copy(confirmed = false),
                        listOf(LoopPageEffect.PersistDemote(ready.songId)),
                    ),
                )
            }

            val dragIdle = ready.drag == DragState.Idle

            // 00008(用户口径):同模式重复点击也必须重新 SeekTo + Play;
            // 只有拖拽中的模式点击是 no-op。
            if (dragIdle) {
                expect(stateKey, "SwitchMode(SEAM)", switchExpected(ready, LoopPageMode.SEAM))
                expect(stateKey, "SwitchMode(FULL)", switchExpected(ready, LoopPageMode.FULL))
            }

            if (dragIdle) {
                expect(
                    stateKey,
                    "DragAStart",
                    Expectation.Transition(
                        ready.copy(aMs = 32_000L, drag = DragState.Dragging(LoopHandle.A, 32_000L)),
                        emptyList(),
                    ),
                )
                expect(
                    stateKey,
                    "DragBStart",
                    Expectation.Transition(
                        ready.copy(bMs = 65_000L, drag = DragState.Dragging(LoopHandle.B, 65_000L)),
                        emptyList(),
                    ),
                )
            }

            when (val drag = ready.drag) {
                is DragState.Dragging -> when (drag.handle) {
                    LoopHandle.A -> {
                        expect(
                            stateKey,
                            "DragAMove",
                            Expectation.Transition(
                                ready.copy(aMs = 33_000L, drag = DragState.Dragging(LoopHandle.A, 33_000L)),
                                emptyList(),
                            ),
                        )
                        expect(
                            stateKey,
                            "DragAEnd",
                            Expectation.Transition(ready.copy(drag = DragState.Idle), emptyList()),
                        )
                    }

                    LoopHandle.B -> {
                        expect(
                            stateKey,
                            "DragBMove",
                            Expectation.Transition(
                                ready.copy(bMs = 66_000L, drag = DragState.Dragging(LoopHandle.B, 66_000L)),
                                emptyList(),
                            ),
                        )
                        expect(
                            stateKey,
                            "DragBEnd",
                            Expectation.Transition(ready.copy(drag = DragState.Idle), emptyList()),
                        )
                    }
                }

                DragState.Idle -> Unit
            }

            when (val prefill = ready.prefill) {
                PrefillState.NotStarted -> {
                    expect(
                        stateKey,
                        "PrefillStarted",
                        Expectation.Transition(ready.copy(prefill = PrefillState.InProgress(0f)), emptyList()),
                    )
                    expect(
                        stateKey,
                        "PrefillProgress",
                        Expectation.Transition(
                            ready.copy(prefill = PrefillState.InProgress(0.8f, 8_000L, 12_000L)),
                            emptyList(),
                        ),
                    )
                    expect(
                        stateKey,
                        "PrefillPartialReady",
                        Expectation.Transition(
                            ready.copy(prefill = PrefillState.Partial(8_000L, 12_000L)),
                            emptyList(),
                        ),
                    )
                    expect(
                        stateKey,
                        "PrefillReady",
                        Expectation.Transition(ready.copy(prefill = PrefillState.Ready), emptyList()),
                    )
                    expect(
                        stateKey,
                        "PrefillFailed",
                        Expectation.Transition(ready.copy(prefill = PrefillState.Failed("prefill boom")), emptyList()),
                    )
                }

                is PrefillState.InProgress -> {
                    expect(
                        stateKey,
                        "PrefillProgress",
                        Expectation.Transition(
                            ready.copy(prefill = PrefillState.InProgress(0.8f, 8_000L, 12_000L)),
                            emptyList(),
                        ),
                    )
                    val awaiting = ready.playback as? PlaybackState.AwaitingPrefill
                    if (awaiting == null) {
                        expect(
                            stateKey,
                            "PrefillPartialReady",
                            Expectation.Transition(
                                ready.copy(prefill = PrefillState.Partial(8_000L, 12_000L)),
                                emptyList(),
                            ),
                        )
                        expect(
                            stateKey,
                            "PrefillReady",
                            Expectation.Transition(ready.copy(prefill = PrefillState.Ready), emptyList()),
                        )
                        expect(
                            stateKey,
                            "PrefillFailed",
                            Expectation.Transition(ready.copy(prefill = PrefillState.Failed("prefill boom")), emptyList()),
                        )
                    } else {
                        expect(
                            stateKey,
                            "PrefillPartialReady",
                            Expectation.Transition(
                                ready.copy(
                                    prefill = PrefillState.Partial(8_000L, 12_000L),
                                    playback = PlaybackState.Playing,
                                    positionMs = awaiting.resumeAtMs,
                                ),
                                listOf(LoopPageEffect.Play(awaiting.resumeMode, awaiting.resumeAtMs)),
                            ),
                        )
                        expect(
                            stateKey,
                            "PrefillReady",
                            Expectation.Transition(
                                ready.copy(
                                    prefill = PrefillState.Ready,
                                    playback = PlaybackState.Playing,
                                    positionMs = awaiting.resumeAtMs,
                                ),
                                listOf(LoopPageEffect.Play(awaiting.resumeMode, awaiting.resumeAtMs)),
                            ),
                        )
                        expect(
                            stateKey,
                            "PrefillFailed",
                            Expectation.Transition(
                                ready.copy(
                                    prefill = PrefillState.Failed("prefill boom"),
                                    playback = PlaybackState.Playing,
                                    positionMs = awaiting.resumeAtMs,
                                ),
                                listOf(
                                    LoopPageEffect.Play(awaiting.resumeMode, awaiting.resumeAtMs),
                                    LoopPageEffect.Toast(LoopPageReducer.PREFILL_FALLBACK_TOAST),
                                ),
                            ),
                        )
                    }
                }

                is PrefillState.Partial -> {
                    expect(
                        stateKey,
                        "PrefillProgress",
                        Expectation.Transition(
                            ready.copy(prefill = PrefillState.Partial(8_000L, 12_000L)),
                            emptyList(),
                        ),
                    )
                    expect(
                        stateKey,
                        "PrefillReady",
                        Expectation.Transition(ready.copy(prefill = PrefillState.Ready), emptyList()),
                    )
                }

                PrefillState.Ready -> Unit
                PrefillState.FirstBlockLoading -> Unit
                is PrefillState.Failed -> Unit
            }
        }

        return map
    }

    private val EXPECTATIONS: Map<Pair<String, String>, Expectation> = expectations()

    val CASES: List<Case> = STATES.flatMap { (stateKey, state) ->
        EVENTS.map { (eventKey, event) ->
            Case(
                stateKey = stateKey,
                eventKey = eventKey,
                state = state,
                event = event,
                expected = EXPECTATIONS[stateKey to eventKey] ?: Expectation.NoOp,
            )
        }
    }
}
