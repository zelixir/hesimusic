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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** SOURCE 模式：不启动环缓冲预填、不进入 AwaitingPrefill。 */
class LoopPageSourceModeTest {

    private val songId = 7L
    private val sr = 44_100

    private fun candidate(aMs: Long, bMs: Long): LoopCandidateKt = LoopCandidateKt(
        startSamples = aMs * sr / 1000L,
        endSamples = bMs * sr / 1000L,
        score = 1.0,
        noteDistance = 0.1,
        loudnessDifference = 0.2,
    )

    private fun ready(
        sourceMode: Boolean = true,
        playback: PlaybackState = PlaybackState.Stopped,
        prefill: PrefillState = PrefillState.NotStarted,
        mode: LoopPageMode = LoopPageMode.LOOP_ONLY,
    ): LoopPageState.Ready = LoopPageState.Ready(
        songId = songId,
        durationMs = 120_000L,
        sampleRate = sr,
        mode = mode,
        aMs = 30_000L,
        bMs = 60_000L,
        candidates = listOf(candidate(30_000L, 60_000L)),
        selectedCandidate = 0,
        confirmed = false,
        prefill = prefill,
        playback = playback,
        positionMs = 0L,
        drag = DragState.Idle,
        sourceMode = sourceMode,
    )

    @Test
    fun `analysis done in source mode does not start prefill`() {
        val (next, effects) = LoopPageReducer.reduce(
            LoopPageState.Analyzing(AnalysisPhase.DECODING, 0.5f),
            LoopPageEvent.AnalysisDone(
                songId = songId,
                candidates = listOf(candidate(30_000L, 60_000L)),
                sampleRate = sr,
                durationMs = 120_000L,
                sourceMode = true,
            ),
        )
        val ready = next as LoopPageState.Ready
        assertTrue(ready.sourceMode)
        assertTrue(effects.none { it is LoopPageEffect.StartPrefill })
        assertTrue(effects.none { it is LoopPageEffect.CancelPrefill })
    }

    @Test
    fun `candidate click in source mode emits apply window seek play without prefill`() {
        val (next, effects) = LoopPageReducer.reduce(
            ready(),
            LoopPageEvent.SelectCandidate(0),
        )
        val ready = next as LoopPageState.Ready
        assertEquals(PlaybackState.Playing, ready.playback)
        assertEquals(PrefillState.FirstBlockLoading, ready.prefill)
        assertTrue(effects.any { it is LoopPageEffect.ApplyWindow })
        assertTrue(effects.any { it is LoopPageEffect.SeekTo })
        assertTrue(effects.any { it is LoopPageEffect.Play })
        assertTrue(effects.none { it is LoopPageEffect.StartPrefill })
        assertTrue(effects.none { it is LoopPageEffect.CancelPrefill })
    }

    @Test
    fun `commit window in source mode does not await prefill`() {
        val (next, effects) = LoopPageReducer.reduce(
            ready(playback = PlaybackState.Playing, prefill = PrefillState.Ready),
            LoopPageEvent.CommitWindow,
        )
        val ready = next as LoopPageState.Ready
        assertTrue(ready.playback is PlaybackState.Playing)
        assertTrue(effects.any { it is LoopPageEffect.ApplyWindow })
        assertTrue(effects.none { it is LoopPageEffect.StartPrefill })
        assertTrue(effects.none { it is LoopPageEffect.CancelPrefill })
    }

    @Test
    fun `toggle play in source mode goes straight to playing`() {
        val (next, effects) = LoopPageReducer.reduce(
            ready(playback = PlaybackState.Stopped),
            LoopPageEvent.TogglePlay,
        )
        val ready = next as LoopPageState.Ready
        assertEquals(PlaybackState.Playing, ready.playback)
        assertEquals(PrefillState.FirstBlockLoading, ready.prefill)
        assertTrue(effects.any { it is LoopPageEffect.Play })
        assertFalse(effects.any { it is LoopPageEffect.StartPrefill })
    }

    @Test
    fun `prefill events are ignored in source mode`() {
        val state = ready(prefill = PrefillState.FirstBlockLoading)
        val (next, effects) = LoopPageReducer.reduce(state, LoopPageEvent.PrefillProgress(0.5f))
        assertTrue(next === state)
        assertTrue(effects.isEmpty())

        val (readyNext, readyEffects) = LoopPageReducer.reduce(
            state,
            LoopPageEvent.PrefillReady,
        )
        assertEquals(PrefillState.Ready, (readyNext as LoopPageState.Ready).prefill)
        assertTrue(readyEffects.isEmpty())
    }
}
