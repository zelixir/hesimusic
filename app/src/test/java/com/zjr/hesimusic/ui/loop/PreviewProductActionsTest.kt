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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预览页三个产品动作的**状态机契约**(场景 #84 / #85 / #86)。
 *
 * 纯 reducer + effect runner,不碰 Android/ExoPlayer:
 * - #84 停止:必须 Pause + SeekTo(模式起点),且之后任何"就绪"事件都不得重新 Play;
 * - #85 当前位置→B:必须把 B 改成**派发那一刻**的页面位置,A 不变,并 ApplyWindow 新窗口;
 * - #86 重新分析:Pause/CancelPrefill 必须排在 RestartAnalysis 之前真实执行。
 *
 * 真机上的"停止后 sink 不再写帧"由 [PreviewPlayer.pause] 的既有路径保证
 * (pause → 渲染器停止喂 sink;尾帧 ≤ 硬件 in-flight ~50-100ms 见 docs);
 * 这里锁的是"应用侧不会在停止后再发出 Play"这条容易被回归的契约。
 */
class PreviewProductActionsTest {

    private val songId = 7L
    private val sr = 44_100

    private class RecordingHost : LoopPageEffectHost {
        val calls = mutableListOf<String>()

        override fun play(mode: LoopPageMode, startMs: Long, seek: Boolean) {
            calls += "play:" + mode + ":" + startMs + ":seek=" + seek
        }

        override fun pause() {
            calls += "pause"
        }

        override fun seekTo(positionMs: Long) {
            calls += "seek:" + positionMs
        }

        override fun startPrefill(window: LoopWindow) {
            calls += "startPrefill"
        }

        override fun cancelPrefill() {
            calls += "cancelPrefill"
        }

        override fun applyWindow(window: LoopWindow) {
            calls += "applyWindow:" + window.loopStartUs / 1000L + "-" + window.loopEndUs / 1000L
        }

        override fun persistConfirm(songId: Long, window: LoopWindow, onResult: (Boolean) -> Unit) {
            calls += "persistConfirm"
            onResult(true)
        }

        override fun persistDemote(songId: Long, onResult: (Boolean) -> Unit) {
            calls += "persistDemote"
            onResult(true)
        }

        override fun persistDelete(songId: Long, onResult: (Boolean) -> Unit) {
            calls += "persistDelete"
            onResult(true)
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

    private fun candidate(startSamples: Long, endSamples: Long) = LoopCandidateKt(
        startSamples = startSamples,
        endSamples = endSamples,
        score = 0.9,
        noteDistance = 0.05,
        loudnessDifference = 0.05,
    )

    private fun ready(
        playback: PlaybackState = PlaybackState.Playing,
        mode: LoopPageMode = LoopPageMode.LOOP_ONLY,
        aMs: Long = 10_000L,
        bMs: Long = 50_000L,
        positionMs: Long = 40_272L,
        sourceMode: Boolean = true,
    ) = LoopPageState.Ready(
        songId = songId,
        durationMs = 200_000L,
        sampleRate = sr,
        mode = mode,
        aMs = aMs,
        bMs = bMs,
        candidates = listOf(candidate(441_000L, 2_205_000L)),
        selectedCandidate = 0,
        confirmed = false,
        prefill = PrefillState.Ready,
        playback = playback,
        positionMs = positionMs,
        drag = DragState.Idle,
        sourceMode = sourceMode,
    )

    /** 与 [LoopViewModel.stopPreview] 相同的两步派发:ToggerPlay(暂停) → Seek(模式起点)。 */
    private fun dispatchStop(state: LoopPageState.Ready): Pair<LoopPageState.Ready, List<LoopPageEffect>> {
        val (paused, pauseEffects) = LoopPageReducer.reduce(state, LoopPageEvent.TogglePlay)
        assertEquals("停止第一步必须是暂停", listOf(LoopPageEffect.Pause), pauseEffects)
        val pausedState = paused as LoopPageState.Ready
        val startMs = when (pausedState.mode) {
            LoopPageMode.FULL -> 0L
            LoopPageMode.LOOP_ONLY -> (pausedState.aMs - 300L).coerceAtLeast(0L)
            LoopPageMode.SEAM -> maxOf(pausedState.aMs, pausedState.bMs - 5_000L)
            LoopPageMode.FREE -> 0L
        }
        val (stopped, stopEffects) = LoopPageReducer.reduce(paused, LoopPageEvent.Seek(startMs))
        assertEquals("停止第二步是回到模式起点", listOf(LoopPageEffect.SeekTo(startMs)), stopEffects)
        return stopped as LoopPageState.Ready to (pauseEffects + stopEffects)
    }

    // ------------------------------------------------------------------ #84 停止

    @Test
    fun `issue84 stop pauses and rewinds to the mode start without replaying`() {
        val state = ready(playback = PlaybackState.Playing)
        val host = RecordingHost()

        val (after, effects) = dispatchStop(state)
        LoopPageEffectRunner(host).run(effects)

        val startMs = (state.aMs - 300L).coerceAtLeast(0L)
        assertEquals(
            "停止只允许先 pause 再 seek 回起点,绝不允许再 play",
            listOf("pause", "seek:" + startMs),
            host.calls,
        )
        assertTrue(host.calls.none { it.startsWith("play:") })
        assertEquals(PlaybackState.Paused, after.playback)
        assertEquals("停止必须回到模式起点", startMs, after.positionMs)
        assertEquals("停止不得改 A/B", state.aMs, after.aMs)
        assertEquals(state.bMs, after.bMs)
    }

    @Test
    fun `issue84 ready events after stop can never restart playback`() {
        val (stopped, _) = dispatchStop(ready(playback = PlaybackState.Playing))

        // 停止后,数据源可能还会吐一个迟到 READY/首块事件(seek/flush 的正常尾部)。
        val (afterReady, readyEffects) = LoopPageReducer.reduce(stopped, LoopPageEvent.PrefillReady)
        assertTrue(
            "停止后的迟到 READY 不得产生 Play(否则就是看不见的续播)",
            readyEffects.none { it is LoopPageEffect.Play },
        )
        assertEquals(PlaybackState.Paused, (afterReady as LoopPageState.Ready).playback)

        // M3′ 路线同样:暂停态收到 PrefillReady 也不得自动起播。
        val (m3Stopped, _) = dispatchStop(ready(playback = PlaybackState.Playing, sourceMode = false))
        val (afterM3, m3Effects) = LoopPageReducer.reduce(m3Stopped, LoopPageEvent.PrefillReady)
        assertTrue(m3Effects.none { it is LoopPageEffect.Play })
        assertEquals(PlaybackState.Paused, (afterM3 as LoopPageState.Ready).playback)
    }

    // ------------------------------------------------------------------ #85 当前位置→B

    @Test
    fun `issue85 set B uses the page position at dispatch and keeps A`() {
        val state = ready(
            playback = PlaybackState.Playing,
            aMs = 17_496L,
            bMs = 50_167L,
            positionMs = 40_272L,
        )
        val host = RecordingHost()

        // 与 [LoopViewModel.setBFromCurrent] 相同的三步派发(位置取派发时刻页面位置)。
        val (dragStarted, _) = LoopPageReducer.reduce(state, LoopPageEvent.DragBStart(state.positionMs))
        val (dragEnded, _) = LoopPageReducer.reduce(dragStarted, LoopPageEvent.DragBEnd)
        val (committed, effects) = LoopPageReducer.reduce(dragEnded, LoopPageEvent.CommitWindow)
        LoopPageEffectRunner(host).run(effects)

        val after = committed as LoopPageState.Ready
        assertEquals("B 必须改成当前位置", 40_272L, after.bMs)
        assertNotEquals("不能保留旧 B", state.bMs, after.bMs)
        assertEquals("A 不得被改动", 17_496L, after.aMs)
        assertEquals(
            "新窗口必须下发给预览(新数据源/cache key)",
            "applyWindow:17496-40272",
            host.calls.firstOrNull { it.startsWith("applyWindow:") },
        )
        assertEquals("LOOP_ONLY 改 B 后从 A 前预卷继续试听", 17_196L, after.positionMs)
    }

    @Test
    fun `issue85 B tracks the live position rather than a cached one`() {
        // 同一个 Ready,页面位置不同 → B 必须不同(证明读的是派发时刻位置,不是缓存)。
        fun commitAt(positionMs: Long): Long {
            val state = ready(playback = PlaybackState.Playing, positionMs = positionMs)
            val (a, _) = LoopPageReducer.reduce(state, LoopPageEvent.DragBStart(positionMs))
            val (b, _) = LoopPageReducer.reduce(a, LoopPageEvent.DragBEnd)
            val (c, _) = LoopPageReducer.reduce(b, LoopPageEvent.CommitWindow)
            return (c as LoopPageState.Ready).bMs
        }
        assertEquals(42_000L, commitAt(42_000L))
        assertEquals(43_000L, commitAt(43_000L))
        assertNotEquals(commitAt(42_000L), commitAt(44_000L))
    }

    // ------------------------------------------------------------------ #86 重新分析

    @Test
    fun `issue86 reanalyze executes pause and cancel before restart`() {
        val host = RecordingHost()
        val (_, effects) = LoopPageReducer.reduce(ready(), LoopPageEvent.ManualReanalyze)
        LoopPageEffectRunner(host).run(effects)

        assertEquals(
            "进入分析态的执行顺序:先停预览、再取消预填、最后才重启分析",
            listOf("pause", "cancelPrefill", "restartAnalysis"),
            host.calls,
        )
        assertTrue(host.calls.none { it.startsWith("play:") })
    }
}
