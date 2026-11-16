package com.zjr.hesimusic.ui.loop

import com.zjr.hesimusic.loop.LoopWindow
import com.zjr.hesimusic.loop.analysis.LoopCandidateKt
import com.zjr.hesimusic.ui.loop.state.DragState
import com.zjr.hesimusic.ui.loop.state.LoopPageEffect
import com.zjr.hesimusic.ui.loop.state.LoopPageEvent
import com.zjr.hesimusic.ui.loop.state.LoopPageMode
import com.zjr.hesimusic.ui.loop.state.LoopPageReducer
import com.zjr.hesimusic.ui.loop.state.LoopPageState
import com.zjr.hesimusic.ui.loop.state.PlaybackState
import com.zjr.hesimusic.ui.loop.state.PrefillState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * effect runner 单测:不依赖 Android/ExoPlayer/Room。
 *
 * 用 FakeLoopPageHost 记录调用序列,验证:
 * - effect 严格按 reducer 列表顺序执行;
 * - 同批已有同位置 SeekTo 时,Play 以 seek=false 执行(不二次 raw seek);
 * - AwaitingPrefill 的 PrefillReady/PrefillFailed 一定产出 Play;
 * - 删除/确认先持久化,成功后返回/回调。
 */
class LoopPageEffectRunnerTest {

    private val songId = 7L
    private val window = LoopWindow(songId, 10_000_000L, 50_000_000L)

    private fun candidate(aMs: Long, bMs: Long): LoopCandidateKt = LoopCandidateKt(
        startSamples = aMs * 44_100L / 1000L,
        endSamples = bMs * 44_100L / 1000L,
        score = 1.0,
        noteDistance = 0.1,
        loudnessDifference = 0.2,
    )

    private val candidates = listOf(
        candidate(30_000L, 60_000L),
        candidate(10_000L, 50_000L),
    )

    private fun ready(
        playback: PlaybackState = PlaybackState.Playing,
        prefill: PrefillState = PrefillState.Ready,
    ): LoopPageState.Ready = LoopPageState.Ready(
        songId = songId,
        durationMs = 120_000L,
        sampleRate = 44_100,
        mode = LoopPageMode.FULL,
        aMs = 10_000L,
        bMs = 50_000L,
        candidates = candidates,
        selectedCandidate = 0,
        confirmed = false,
        prefill = prefill,
        playback = playback,
        positionMs = 0L,
        drag = DragState.Idle,
    )

    private class FakeLoopPageHost(
        private val confirmResult: Boolean = true,
        private val demoteResult: Boolean = true,
        private val deleteResult: Boolean = true,
    ) : LoopPageEffectHost {

        val calls = mutableListOf<String>()

        override fun play(mode: LoopPageMode, startMs: Long, seek: Boolean) {
            calls += "play:" + mode + ":" + startMs + ":" + seek
        }

        override fun pause() {
            calls += "pause"
        }

        override fun seekTo(positionMs: Long) {
            calls += "seek:" + positionMs
        }

        override fun startPrefill(window: LoopWindow) {
            calls += "startPrefill:" + window.loopStartUs + "-" + window.loopEndUs
        }

        override fun cancelPrefill() {
            calls += "cancelPrefill"
        }

        override fun persistConfirm(
            songId: Long,
            window: LoopWindow,
            onResult: (Boolean) -> Unit,
        ) {
            calls += "persistConfirm:" + songId
            onResult(confirmResult)
        }

        override fun persistDemote(songId: Long, onResult: (Boolean) -> Unit) {
            calls += "persistDemote:" + songId
            onResult(demoteResult)
        }

        override fun persistDelete(songId: Long, onResult: (Boolean) -> Unit) {
            calls += "persistDelete:" + songId
            onResult(deleteResult)
        }

        override fun onConfirmSaved() {
            calls += "onConfirmSaved"
        }

        override fun toast(text: String) {
            calls += "toast:" + text
        }

        override fun navigateBack() {
            calls += "navigateBack"
        }

        override fun restartAnalysis() {
            calls += "restartAnalysis"
        }
    }

    @Test
    fun sameBatchSeekThenPlayDoesNotSeekTwice() {
        val host = FakeLoopPageHost()
        LoopPageEffectRunner(host).run(
            listOf(
                LoopPageEffect.SeekTo(1_000L),
                LoopPageEffect.Play(LoopPageMode.FULL, 1_000L),
            ),
        )
        assertEquals(
            listOf("seek:1000", "play:FULL:1000:false"),
            host.calls,
        )
    }

    @Test
    fun playWithoutSeekStillSeeks() {
        val host = FakeLoopPageHost()
        LoopPageEffectRunner(host).run(
            listOf(LoopPageEffect.Play(LoopPageMode.SEAM, 55_000L)),
        )
        assertEquals(listOf("play:SEAM:55000:true"), host.calls)
    }

    @Test
    fun playWithDifferentSeekTargetStillSeeks() {
        val host = FakeLoopPageHost()
        LoopPageEffectRunner(host).run(
            listOf(
                LoopPageEffect.SeekTo(1_000L),
                LoopPageEffect.Play(LoopPageMode.FULL, 2_000L),
            ),
        )
        assertEquals(
            listOf("seek:1000", "play:FULL:2000:true"),
            host.calls,
        )
    }

    @Test
    fun selectCandidateOrderIsCancelStartSeekAndNoPause() {
        val host = FakeLoopPageHost()
        val state = ready(
            playback = PlaybackState.Playing,
            prefill = PrefillState.Ready,
        )
        val (next, effects) = LoopPageReducer.reduce(state, LoopPageEvent.SelectCandidate(1))
        LoopPageEffectRunner(host).run(effects)

        assertEquals(
            listOf(
                "cancelPrefill",
                "startPrefill:10000000-50000000",
                "seek:0",
            ),
            host.calls,
        )
        val nextReady = next as LoopPageState.Ready
        assertEquals(PlaybackState.AwaitingPrefill(LoopPageMode.FULL, 0L), nextReady.playback)

        // 预填完成必须补 Play;runner 按 reducer 的 effect 序列执行。
        val (_, readyEffects) = LoopPageReducer.reduce(nextReady, LoopPageEvent.PrefillReady)
        LoopPageEffectRunner(host).run(readyEffects)
        assertEquals("play:FULL:0:true", host.calls.last())
    }

    @Test
    fun prefillReadyFromAwaitingAlwaysPlays() {
        val host = FakeLoopPageHost()
        val state = ready(
            playback = PlaybackState.AwaitingPrefill(LoopPageMode.SEAM, 55_000L),
            prefill = PrefillState.InProgress(0.6f),
        )
        val (_, effects) = LoopPageReducer.reduce(state, LoopPageEvent.PrefillReady)
        LoopPageEffectRunner(host).run(effects)
        assertEquals(listOf("play:SEAM:55000:true"), host.calls)
    }

    @Test
    fun prefillFailedFromAwaitingPlaysAndToasts() {
        val host = FakeLoopPageHost()
        val state = ready(
            playback = PlaybackState.AwaitingPrefill(LoopPageMode.SEAM, 55_000L),
            prefill = PrefillState.InProgress(0.6f),
        )
        val (_, effects) = LoopPageReducer.reduce(
            state,
            LoopPageEvent.PrefillFailed("decode boom"),
        )
        LoopPageEffectRunner(host).run(effects)
        assertEquals(
            listOf(
                "play:SEAM:55000:true",
                "toast:环缓冲准备失败，已按直通播放",
            ),
            host.calls,
        )
    }

    @Test
    fun deletePersistsThenNavigatesBack() {
        val host = FakeLoopPageHost(deleteResult = true)
        LoopPageEffectRunner(host).run(
            listOf(
                LoopPageEffect.CancelPrefill,
                LoopPageEffect.PersistDelete(songId),
            ),
        )
        assertEquals(
            listOf(
                "cancelPrefill",
                "persistDelete:7",
                "toast:已删除分析结果，下次进入将重新分析",
                "navigateBack",
            ),
            host.calls,
        )
    }

    @Test
    fun deleteFailureToastsAndStaysOnPage() {
        val host = FakeLoopPageHost(deleteResult = false)
        LoopPageEffectRunner(host).run(listOf(LoopPageEffect.PersistDelete(songId)))
        assertEquals(
            listOf("persistDelete:7", "toast:删除失败，请重试"),
            host.calls,
        )
    }

    @Test
    fun confirmPersistsThenCallsOnConfirmSaved() {
        val host = FakeLoopPageHost(confirmResult = true)
        LoopPageEffectRunner(host).run(
            listOf(LoopPageEffect.PersistConfirm(songId, window)),
        )
        assertEquals(
            listOf("persistConfirm:7", "onConfirmSaved"),
            host.calls,
        )
    }

    @Test
    fun confirmFailureToasts() {
        val host = FakeLoopPageHost(confirmResult = false)
        LoopPageEffectRunner(host).run(
            listOf(LoopPageEffect.PersistConfirm(songId, window)),
        )
        assertEquals(
            listOf("persistConfirm:7", "toast:保存失败，请重试"),
            host.calls,
        )
    }

    @Test
    fun demotePersistsThenToasts() {
        val host = FakeLoopPageHost(demoteResult = true)
        LoopPageEffectRunner(host).run(listOf(LoopPageEffect.PersistDemote(songId)))
        assertEquals(
            listOf("persistDemote:7", "toast:已转为临时结果，不再无缝"),
            host.calls,
        )
    }

    @Test
    fun toastNavigateAndRestartKeepOrder() {
        val host = FakeLoopPageHost()
        LoopPageEffectRunner(host).run(
            listOf(
                LoopPageEffect.Toast("hello"),
                LoopPageEffect.NavigateBack,
                LoopPageEffect.RestartAnalysis,
            ),
        )
        assertEquals(
            listOf("toast:hello", "navigateBack", "restartAnalysis"),
            host.calls,
        )
    }
}
