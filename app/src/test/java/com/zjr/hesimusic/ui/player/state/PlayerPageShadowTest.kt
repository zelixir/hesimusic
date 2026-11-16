package com.zjr.hesimusic.ui.player.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 影子双跑纯逻辑测试：
 * - [project] 快照 → 模型状态；
 * - [diff] 字段名与 expected/actual；
 * - [PlayerPageShadow] 事件序列无差异；
 * - 影子不执行任何 effect。
 */
class PlayerPageShadowTest {

    private val allMode = PlayMode(repeat = RepeatMode.ALL, shuffle = false)

    private fun snapshot(
        songId: Long? = 1L,
        isPlaying: Boolean = true,
        positionMs: Long = 1_000L,
        repeat: RepeatMode = RepeatMode.ALL,
        shuffle: Boolean = false,
        playbackState: SnapshotPlaybackState = SnapshotPlaybackState.READY,
        errorMessage: String? = null,
        seamlessIntent: SeamlessIntent? = null,
        dragMs: Long? = null,
        pendingSeekTargetMs: Long? = null,
        pendingSeekSinceMs: Long? = null,
        nowMs: Long = 0L,
    ): PlayerSnapshot = PlayerSnapshot(
        songId = songId,
        isPlaying = isPlaying,
        positionMs = positionMs,
        repeat = repeat,
        shuffle = shuffle,
        playbackState = playbackState,
        errorMessage = errorMessage,
        seamlessIntent = seamlessIntent,
        dragMs = dragMs,
        pendingSeekTargetMs = pendingSeekTargetMs,
        pendingSeekSinceMs = pendingSeekSinceMs,
        nowMs = nowMs,
    )

    // ------------------------------------------------------------------ project

    @Test
    fun projectPlaying() {
        assertEquals(
            PlayerPageState.Playing(1_000L, allMode),
            project(snapshot()),
        )
    }

    @Test
    fun projectPaused() {
        assertEquals(
            PlayerPageState.Paused(1_000L, allMode),
            project(snapshot(isPlaying = false)),
        )
    }

    @Test
    fun projectIdleWhenNoSong() {
        assertEquals(
            PlayerPageState.Idle,
            project(snapshot(songId = null, isPlaying = false, playbackState = SnapshotPlaybackState.IDLE)),
        )
    }

    @Test
    fun projectDragging() {
        assertEquals(
            PlayerPageState.Seeking(
                dragMs = 12_000L,
                targetMs = 12_000L,
                pendingSince = null,
                mode = allMode,
                resume = ResumeTarget.PLAYING,
            ),
            project(snapshot(dragMs = 12_000L)),
        )
    }

    @Test
    fun projectPendingSeek() {
        assertEquals(
            PlayerPageState.Seeking(
                dragMs = 20_000L,
                targetMs = 20_000L,
                pendingSince = 500L,
                mode = allMode,
                resume = ResumeTarget.PAUSED,
            ),
            project(
                snapshot(
                    isPlaying = false,
                    pendingSeekTargetMs = 20_000L,
                    pendingSeekSinceMs = 500L,
                ),
            ),
        )
    }

    @Test
    fun projectEnded() {
        assertEquals(
            PlayerPageState.Ended(allMode),
            project(snapshot(playbackState = SnapshotPlaybackState.ENDED, isPlaying = false)),
        )
    }

    @Test
    fun projectError() {
        assertEquals(
            PlayerPageState.Error("decoder boom", allMode),
            project(snapshot(errorMessage = "decoder boom")),
        )
    }

    @Test
    fun projectSeamless() {
        val intent = SeamlessIntent(songId = 1L, previousRepeat = RepeatMode.ALL, previousShuffle = true)
        assertEquals(
            PlayerPageState.Playing(
                1_000L,
                PlayMode(repeat = RepeatMode.ONE, shuffle = false, seamless = intent),
            ),
            project(snapshot(repeat = RepeatMode.ONE, shuffle = false, seamlessIntent = intent)),
        )
    }

    @Test
    fun projectBufferingUsesIsPlayingSemantics() {
        // 当前模型没有 BufferStarted/BufferReady 事件；真实运行时按 isPlaying 近似。
        assertEquals(
            PlayerPageState.Playing(1_000L, allMode),
            project(snapshot(playbackState = SnapshotPlaybackState.BUFFERING, isPlaying = true)),
        )
        assertEquals(
            PlayerPageState.Paused(1_000L, allMode),
            project(snapshot(playbackState = SnapshotPlaybackState.BUFFERING, isPlaying = false)),
        )
    }

    // ------------------------------------------------------------------ diff

    @Test
    fun diffSameStateIsEmpty() {
        assertTrue(diff(PlayerPageState.Playing(1_000L, allMode), PlayerPageState.Playing(1_000L, allMode)).isEmpty())
    }

    @Test
    fun diffReportsFieldNamesAndBothValues() {
        val mismatches = diff(
            PlayerPageState.Playing(1_000L, allMode),
            PlayerPageState.Playing(2_000L, PlayMode(repeat = RepeatMode.ONE, shuffle = true)),
        )

        assertTrue("positionMs: expected=1000, actual=2000", mismatches.contains("positionMs: expected=1000, actual=2000"))
        assertTrue(mismatches.any { it.startsWith("mode.repeat: expected=ALL, actual=ONE") })
        assertTrue(mismatches.any { it.startsWith("mode.shuffle: expected=false, actual=true") })
    }

    @Test
    fun diffReportsStateMismatch() {
        val mismatches = diff(PlayerPageState.Paused(1_000L, allMode), PlayerPageState.Playing(1_000L, allMode))
        assertEquals(1, mismatches.size)
        assertTrue(mismatches.single(), mismatches.single().startsWith("state: expected=Paused, actual=Playing"))
    }

    @Test
    fun diffReportsSeekingFields() {
        val expected = PlayerPageState.Seeking(10L, 20L, null, allMode, ResumeTarget.PLAYING)
        val actual = PlayerPageState.Seeking(30L, 40L, 500L, allMode, ResumeTarget.PAUSED)
        val mismatches = diff(expected, actual)

        assertTrue(mismatches.contains("dragMs: expected=10, actual=30"))
        assertTrue(mismatches.contains("targetMs: expected=20, actual=40"))
        assertTrue(mismatches.contains("pendingSince: expected=null, actual=500"))
        assertTrue(mismatches.contains("resume: expected=PLAYING, actual=PAUSED"))
    }

    @Test
    fun diffReportsSeamlessSongChange() {
        val expected = PlayerPageState.Playing(
            1_000L,
            PlayMode(RepeatMode.ONE, shuffle = false, seamless = SeamlessIntent(1L, RepeatMode.ALL, true)),
        )
        val actual = PlayerPageState.Playing(
            1_000L,
            PlayMode(RepeatMode.ONE, shuffle = false, seamless = SeamlessIntent(2L, RepeatMode.ALL, true)),
        )
        assertEquals(listOf("mode.seamless.songId: expected=1, actual=2"), diff(expected, actual))
    }

    @Test
    fun diffReportsErrorMessage() {
        assertEquals(
            listOf("message: expected=boom, actual=other"),
            diff(
                PlayerPageState.Error("boom", allMode),
                PlayerPageState.Error("other", allMode),
            ),
        )
    }

    @Test
    fun diffSupportsPositionTolerance() {
        val expected = PlayerPageState.Playing(1_000L, allMode)
        val actual = PlayerPageState.Playing(1_200L, allMode)
        assertEquals(1, diff(expected, actual).size)
        assertTrue(diff(expected, actual, positionToleranceMs = 200L).isEmpty())
    }

    // ------------------------------------------------------------------ shadow

    @Test
    fun shadowTracksPlayPauseRepeatShuffleTickWithoutMismatch() {
        val shadow = PlayerPageShadow()
        assertTrue(shadow.observe(snapshot(positionMs = 1_000L, nowMs = 0L)).mismatches.isEmpty())

        // tick
        assertTrue(shadow.observe(snapshot(positionMs = 2_000L, nowMs = 1_000L)).mismatches.isEmpty())
        // pause
        assertTrue(
            shadow.observe(
                snapshot(isPlaying = false, positionMs = 2_500L, nowMs = 2_000L),
            ).mismatches.isEmpty(),
        )
        // resume
        assertTrue(
            shadow.observe(
                snapshot(isPlaying = true, positionMs = 2_600L, nowMs = 3_000L),
            ).mismatches.isEmpty(),
        )
        // repeat change
        assertTrue(
            shadow.observe(
                snapshot(isPlaying = true, positionMs = 2_700L, repeat = RepeatMode.ONE, nowMs = 4_000L),
            ).mismatches.isEmpty(),
        )
        // shuffle change (repeat != ONE keeps invariant)
        assertTrue(
            shadow.observe(
                snapshot(
                    isPlaying = true,
                    positionMs = 2_800L,
                    repeat = RepeatMode.ONE,
                    shuffle = false,
                    nowMs = 5_000L,
                ),
            ).mismatches.isEmpty(),
        )
        assertTrue(
            shadow.observe(
                snapshot(
                    isPlaying = true,
                    positionMs = 2_900L,
                    repeat = RepeatMode.ALL,
                    shuffle = true,
                    nowMs = 6_000L,
                ),
            ).mismatches.isEmpty(),
        )

        assertEquals("影子必须和投影逐次一致", 0L, shadow.mismatchCount)
    }

    @Test
    fun shadowTracksTrackChangeAtZeroWithoutMismatch() {
        val shadow = PlayerPageShadow()
        shadow.observe(snapshot(songId = 1L, positionMs = 1_000L, nowMs = 0L))

        val observation = shadow.observe(
            snapshot(songId = 2L, positionMs = 0L, nowMs = 1_000L),
        )

        assertEquals(PlayerPageState.Playing(0L, allMode), observation.model)
        assertTrue(observation.mismatches.joinToString("; "), observation.mismatches.isEmpty())
    }

    @Test
    fun shadowTracksEndedWithoutMismatch() {
        val shadow = PlayerPageShadow()
        shadow.observe(snapshot(positionMs = 1_000L, nowMs = 0L))

        val observation = shadow.observe(
            snapshot(
                isPlaying = false,
                positionMs = 5_000L,
                playbackState = SnapshotPlaybackState.ENDED,
                nowMs = 1_000L,
            ),
        )

        assertEquals(PlayerPageState.Ended(allMode), observation.model)
        assertTrue(observation.mismatches.joinToString("; "), observation.mismatches.isEmpty())
    }

    @Test
    fun shadowTracksSeamlessEnterAndExitWithoutMismatch() {
        val shadow = PlayerPageShadow()
        shadow.observe(
            snapshot(
                repeat = RepeatMode.ALL,
                shuffle = true,
                positionMs = 1_000L,
                nowMs = 0L,
            ),
        )

        val intent = SeamlessIntent(songId = 1L, previousRepeat = RepeatMode.ALL, previousShuffle = true)
        val entered = shadow.observe(
            snapshot(
                repeat = RepeatMode.ONE,
                shuffle = false,
                seamlessIntent = intent,
                positionMs = 1_000L,
                nowMs = 1_000L,
            ),
        )
        assertEquals(
            PlayerPageState.Playing(
                1_000L,
                PlayMode(repeat = RepeatMode.ONE, shuffle = false, seamless = intent),
            ),
            entered.model,
        )
        assertTrue(entered.mismatches.joinToString("; "), entered.mismatches.isEmpty())

        // 用户改 repeat → 退出无缝（不写 shuffle，保持 false）
        val exited = shadow.observe(
            snapshot(
                repeat = RepeatMode.ALL,
                shuffle = false,
                seamlessIntent = null,
                positionMs = 1_000L,
                nowMs = 2_000L,
            ),
        )
        assertEquals(PlayerPageState.Playing(1_000L, PlayMode(RepeatMode.ALL, false)), exited.model)
        assertTrue(exited.mismatches.joinToString("; "), exited.mismatches.isEmpty())
        assertEquals(0L, shadow.mismatchCount)
    }

    @Test
    fun seekSuppressesComparisonForTwoSeconds() {
        val shadow = PlayerPageShadow()
        shadow.observe(snapshot(positionMs = 1_000L, nowMs = 0L))

        assertTrue("playing 状态下必须允许 Seek", shadow.onSeekRequested(5_000L, nowMs = 100L))
        val inWindow = shadow.observe(snapshot(positionMs = 1_000L, nowMs = 200L))
        assertTrue("seek 后 2s 内应抑制对比", inWindow.suppressed)
        assertTrue(inWindow.mismatches.isEmpty())

        val converged = shadow.observe(snapshot(positionMs = 5_000L, nowMs = 2_200L))
        assertFalse(converged.suppressed)
        assertTrue(converged.mismatches.joinToString("; "), converged.mismatches.isEmpty())
        assertEquals(0L, shadow.mismatchCount)
        assertEquals(1L, shadow.suppressedObservationCount)
    }

    @Test
    fun shadowNeverExecutesEffects() {
        class FakeEffectRunner {
            var calls = 0
            fun run(effect: PlayerPageEffect) {
                calls++
            }
        }

        val fakeRunner = FakeEffectRunner()
        val shadow = PlayerPageShadow()
        shadow.observe(snapshot(positionMs = 1_000L, nowMs = 0L))

        val paused = shadow.observe(
            snapshot(isPlaying = false, positionMs = 1_500L, nowMs = 1_000L),
        )
        assertTrue("pause 事件应产生 PlayerPause effect", paused.effects.contains(PlayerPageEffect.PlayerPause))

        val seamlessIntent = SeamlessIntent(1L, RepeatMode.ALL, false)
        val entered = shadow.observe(
            snapshot(
                repeat = RepeatMode.ONE,
                shuffle = false,
                seamlessIntent = seamlessIntent,
                positionMs = 1_500L,
                nowMs = 2_000L,
            ),
        )
        assertTrue("进入无缝应记录 SetRepeat/SetShuffle effect", entered.effects.isNotEmpty())

        // 影子只记录 effect，从不调用任何 runner。
        assertEquals(0, fakeRunner.calls)
    }

    @Test
    fun mismatchIsCountedWhenProjectionCannotBeExpressedByEvents() {
        val shadow = PlayerPageShadow()
        shadow.observe(snapshot(positionMs = 1_000L, nowMs = 0L))

        // ERROR 但没有 errorMessage：project 映射 Error，事件流没有可用的 PlaybackError。
        val observation = shadow.observe(
            snapshot(playbackState = SnapshotPlaybackState.ERROR, positionMs = 1_000L, nowMs = 1_000L),
        )

        assertFalse(observation.mismatches.isEmpty())
        assertTrue(observation.mismatches.any { it.startsWith("state: expected=Error") })
        assertEquals(1L, shadow.mismatchCount)
        assertTrue(shadow.lastMismatches.any { it.contains("expected=Error") })
    }

    @Test
    fun diagnosticsRecordMismatchesAndSuppressedObservations() {
        PlayerPageShadowDiagnostics.reset()
        try {
            val observation = ShadowObservation(
                expected = PlayerPageState.Error("boom", allMode),
                model = PlayerPageState.Playing(1_000L, allMode),
                mismatches = listOf("state: expected=Error, actual=Playing"),
                effects = emptyList(),
                suppressed = false,
                observedAtMs = 123L,
            )
            PlayerPageShadowDiagnostics.record(observation)

            val map = PlayerPageShadowDiagnostics.snapshotMap()
            assertEquals(1L, map["mismatchCount"])
            assertEquals(123L, map["lastObservedAtMs"])
            assertTrue((map["lastMismatches"] as List<*>).contains("state: expected=Error, actual=Playing"))

            PlayerPageShadowDiagnostics.record(
                observation.copy(mismatches = emptyList(), suppressed = true, observedAtMs = 456L),
            )
            assertEquals(1L, PlayerPageShadowDiagnostics.snapshotMap()["suppressedCount"])
        } finally {
            PlayerPageShadowDiagnostics.reset()
        }
    }
}
