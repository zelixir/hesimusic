package com.zjr.hesimusic.ui.loop

import com.zjr.hesimusic.loop.analysis.LoopCandidateKt
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
import org.junit.Test
import kotlin.math.roundToLong

/**
 * `AnalysisDone.selectedCandidate` 的 reducer 单测(issue 00016 §3)。
 *
 * 重进结果页时 VM 会把"已确认那个候选"的下标带进 AnalysisDone;reducer 必须:
 * - 选中该项(而不是恒等于 0),并把 A/B 取成该候选的窗口;
 * - 越界下标夹回合法区间,空列表退回 0(维持 Ready 的不变量);
 * - 不传(=0,新分析路径)时行为与改造前完全一致。
 */
class LoopPageAnalysisDoneSelectedTest {

    private val sampleRate = 44_100

    private fun candidates(): List<LoopCandidateKt> = (0 until 10).map { index ->
        LoopCandidateKt(
            startSamples = sampleRate.toLong() * (index * 4 + 1),
            endSamples = sampleRate.toLong() * (index * 4 + 1) + sampleRate.toLong() * 3,
            score = 0.95 - index * 0.05,
            noteDistance = 0.01 * index,
            loudnessDifference = 0.02 * index,
        )
    }

    private fun done(
        candidates: List<LoopCandidateKt>,
        selected: Int = 0,
        durationMs: Long = 600_000L,
    ) = LoopPageEvent.AnalysisDone(
        songId = 1L,
        candidates = candidates,
        sampleRate = sampleRate,
        durationMs = durationMs,
        confirmed = true,
        selectedCandidate = selected,
    )

    private fun expectMs(samples: Long): Long = (samples * 1000.0 / sampleRate).roundToLong()

    @Test
    fun `恢复时选中项不是0而是传入的下标`() {
        val list = candidates()
        val (state, effects) = LoopPageReducer.reduce(LoopPageState.Loading, done(list, selected = 6))

        val ready = state as LoopPageState.Ready
        assertEquals("必须停在已确认那个候选", 6, ready.selectedCandidate)
        assertEquals("候选列表原样保留", list, ready.candidates)
        assertEquals("A 应取选中候选的窗口", expectMs(list[6].startSamples), ready.aMs)
        assertEquals("B 应取选中候选的窗口", expectMs(list[6].endSamples), ready.bMs)
        assertEquals(LoopPageMode.FULL, ready.mode)
        assertEquals(PlaybackState.Stopped, ready.playback)
        assertEquals(PrefillState.NotStarted, ready.prefill)
        assertEquals(DragState.Idle, ready.drag)
        assertEquals(0L, ready.positionMs)
        assertEquals(
            "仍要为选中候选启动预填(否则无声)",
            listOf(LoopPageEffect.StartPrefill(ready.window())),
            effects,
        )
    }

    @Test
    fun `越界下标被夹到合法区间`() {
        val list = candidates()
        val (tooBig, _) = LoopPageReducer.reduce(LoopPageState.Loading, done(list, selected = 99))
        assertEquals("上界夹到 size-1", 9, (tooBig as LoopPageState.Ready).selectedCandidate)

        val (negative, _) = LoopPageReducer.reduce(LoopPageState.Loading, done(list, selected = -3))
        assertEquals("下界夹到 0", 0, (negative as LoopPageState.Ready).selectedCandidate)
    }

    @Test
    fun `新分析路径不传下标时行为不变`() {
        val list = candidates()
        val (state, effects) = LoopPageReducer.reduce(
            LoopPageState.Analyzing(com.zjr.hesimusic.ui.loop.state.AnalysisPhase.ANALYZING, 1f),
            done(list),
        )
        val ready = state as LoopPageState.Ready
        assertEquals("默认仍是 score 最优的第 0 个", 0, ready.selectedCandidate)
        assertEquals(expectMs(list[0].startSamples), ready.aMs)
        assertEquals(expectMs(list[0].endSamples), ready.bMs)
        assertEquals(listOf(LoopPageEffect.StartPrefill(ready.window())), effects)
    }

    @Test
    fun `空候选保持旧语义_不越界不预填`() {
        val (state, effects) = LoopPageReducer.reduce(
            LoopPageState.Analyzing(com.zjr.hesimusic.ui.loop.state.AnalysisPhase.DECODING, 0.5f),
            done(emptyList(), selected = 5, durationMs = 600_000L),
        )
        val ready = state as LoopPageState.Ready
        assertEquals(0, ready.selectedCandidate)
        assertEquals(0L, ready.aMs)
        assertEquals(600_000L, ready.bMs)
        assertEquals(emptyList<LoopPageEffect>(), effects)
    }
}
