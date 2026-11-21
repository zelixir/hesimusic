package com.zjr.hesimusic.ui.loop

import com.zjr.hesimusic.loop.analysis.LoopCandidateKt
import com.zjr.hesimusic.ui.loop.state.AnalysisPhase
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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToLong

/**
 * 场景 #86:Ready 中「重新分析」期间不得有"看不见的预览声"。
 *
 * 旧行为:Ready --ManualReanalyze--> Analyzing 只产出 RestartAnalysis,
 * 预览播放器没人管 —— 页面已经切成分析骨架,旧预览还在持续写帧出声。
 *
 * 契约:
 * 1. Ready 进入 Analyzing 必须产出 [LoopPageEffect.Pause](+ CancelPrefill);
 * 2. Analyzing 里重复点「重新分析」= no-op(不重复 pause/重启);
 * 3. 分析完成必须把**新窗口**同步给预览(ApplyWindow),否则下一次 play 仍按旧 A/B 播;
 * 4. 分析期间不产生 Play(不允许自动复播旧预览)。
 */
class PreviewAnalysisPauseTest {

    private val sr = 44_100
    private val durationMs = 200_000L
    private val songId = 42L

    private fun candidate(startSamples: Long, endSamples: Long) = LoopCandidateKt(
        startSamples = startSamples,
        endSamples = endSamples,
        score = 0.9,
        noteDistance = 0.05,
        loudnessDifference = 0.05,
    )

    private fun ready(
        playback: PlaybackState = PlaybackState.Playing,
        aMs: Long = 10_000L,
        bMs: Long = 60_000L,
        sourceMode: Boolean = true,
    ) = LoopPageState.Ready(
        songId = songId,
        durationMs = durationMs,
        sampleRate = sr,
        mode = LoopPageMode.LOOP_ONLY,
        aMs = aMs,
        bMs = bMs,
        candidates = listOf(candidate(441_000L, 2_646_000L)),
        selectedCandidate = 0,
        confirmed = false,
        prefill = PrefillState.Ready,
        playback = playback,
        positionMs = 35_000L,
        drag = DragState.Idle,
        sourceMode = sourceMode,
    )

    @Test
    fun `reanalyze while playing pauses the preview and cancels prefill before restart`() {
        val (next, effects) = LoopPageReducer.reduce(ready(), LoopPageEvent.ManualReanalyze)

        assertEquals(LoopPageState.Analyzing(AnalysisPhase.DECODING, 0f), next)
        assertEquals(
            "进入分析态必须先停预览、再取消预填、最后才重启分析",
            listOf(
                LoopPageEffect.Pause,
                LoopPageEffect.CancelPrefill,
                LoopPageEffect.RestartAnalysis,
            ),
            effects,
        )
        assertTrue("分析期间绝不允许自动 Play", effects.none { it is LoopPageEffect.Play })
    }

    @Test
    fun `reanalyze while paused still pauses so a stale sink cannot keep writing`() {
        val (_, effects) = LoopPageReducer.reduce(
            ready(playback = PlaybackState.Paused),
            LoopPageEvent.ManualReanalyze,
        )
        assertTrue("暂停态也要幂等 pause(防止 sink 残留写帧)", effects.contains(LoopPageEffect.Pause))
        assertTrue(effects.contains(LoopPageEffect.CancelPrefill))
    }

    @Test
    fun `reanalyze while free auditioning also pauses`() {
        val state = ready().copy(auditionOutside = true, positionMs = 120_000L, mode = LoopPageMode.FREE)
        val (next, effects) = LoopPageReducer.reduce(state, LoopPageEvent.ManualReanalyze)
        assertEquals(LoopPageState.Analyzing(AnalysisPhase.DECODING, 0f), next)
        assertTrue(effects.contains(LoopPageEffect.Pause))
    }

    @Test
    fun `repeated reanalyze in analyzing is a no-op`() {
        val analyzing = LoopPageState.Analyzing(AnalysisPhase.ANALYZING, 0.4f)
        val (next, effects) = LoopPageReducer.reduce(analyzing, LoopPageEvent.ManualReanalyze)
        assertSame(analyzing, next)
        assertEquals(emptyList<LoopPageEffect>(), effects)
    }

    @Test
    fun `reanalyze from loading has no preview to pause`() {
        val (_, effects) = LoopPageReducer.reduce(LoopPageState.Loading, LoopPageEvent.ManualReanalyze)
        assertEquals(listOf(LoopPageEffect.RestartAnalysis), effects)
        assertFalse(effects.contains(LoopPageEffect.Pause))
    }

    @Test
    fun `analysis done after reanalyze applies the new window to the preview`() {
        val (analyzing, _) = LoopPageReducer.reduce(ready(), LoopPageEvent.ManualReanalyze)
        val newStartSamples = 2_205_000L
        val newEndSamples = 4_410_000L
        val (readyAgain, effects) = LoopPageReducer.reduce(
            analyzing,
            LoopPageEvent.AnalysisDone(
                songId = songId,
                candidates = listOf(candidate(newStartSamples, newEndSamples)),
                sampleRate = sr,
                durationMs = durationMs,
                sourceMode = true,
            ),
        )
        val ready = readyAgain as LoopPageState.Ready
        val expectedA = (newStartSamples * 1000.0 / sr).roundToLong()
        val expectedB = (newEndSamples * 1000.0 / sr).roundToLong()
        val expectedWindow = com.zjr.hesimusic.loop.LoopWindow(
            songId = songId,
            loopStartUs = expectedA * 1000L,
            loopEndUs = expectedB * 1000L,
        )
        assertEquals("A 必须来自新分析结果", 50_000L, ready.aMs)
        assertEquals("B 必须来自新分析结果", 100_000L, ready.bMs)
        assertEquals(
            "必须把新窗口应用到预览(否则下一次 play 仍播旧 A/B)",
            listOf(LoopPageEffect.ApplyWindow(expectedWindow)),
            effects,
        )
        assertTrue("分析完成不得自动起播", effects.none { it is LoopPageEffect.Play })
    }

    @Test
    fun `analysis done keeps prefill for M3 mode after window sync`() {
        val (analyzing, _) = LoopPageReducer.reduce(
            ready(sourceMode = false, playback = PlaybackState.Paused),
            LoopPageEvent.ManualReanalyze,
        )
        val (readyAgain, effects) = LoopPageReducer.reduce(
            analyzing,
            LoopPageEvent.AnalysisDone(
                songId = songId,
                candidates = listOf(candidate(441_000L, 2_646_000L)),
                sampleRate = sr,
                durationMs = durationMs,
                sourceMode = false,
            ),
        )
        val ready = readyAgain as LoopPageState.Ready
        assertEquals(
            listOf(
                LoopPageEffect.ApplyWindow(ready.window()),
                LoopPageEffect.StartPrefill(ready.window()),
            ),
            effects,
        )
    }
}
