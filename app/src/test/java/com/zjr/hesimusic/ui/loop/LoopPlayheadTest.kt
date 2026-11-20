package com.zjr.hesimusic.ui.loop

import com.zjr.hesimusic.ui.loop.state.LoopPageMode
import com.zjr.hesimusic.ui.loop.state.LoopPageState
import com.zjr.hesimusic.ui.loop.state.PlaybackState
import com.zjr.hesimusic.ui.loop.state.PrefillState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * issue 00023:播放头插值的折返口径。
 *
 * - 循环试听:越过 A 之后按 [A,B) 折返(与音频层环回对齐);
 * - 区外自由试听 / 自由模式:**不折返**,按真实媒体时间走
 *   —— 否则"拖到 0:30 却显示循环区内的位置"会再次制造 seek 被夹回的错觉。
 */
class LoopPlayheadTest {

    private fun ready(
        mode: LoopPageMode = LoopPageMode.LOOP_ONLY,
        playback: PlaybackState = PlaybackState.Playing,
        positionMs: Long = 35_000L,
        auditionOutside: Boolean = false,
        aMs: Long = 30_000L,
        bMs: Long = 60_000L,
        durationMs: Long = 120_000L,
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
        prefill = PrefillState.Ready,
        playback = playback,
        positionMs = positionMs,
        drag = com.zjr.hesimusic.ui.loop.state.DragState.Idle,
        sourceMode = true,
        auditionOutside = auditionOutside,
    )

    @Test
    fun insideLoopFoldsAfterA() {
        val state = ready()
        // 锚点 35s,推进 30s → 65s,折返到 30 + (65-30)%30 = 35s
        assertEquals(35_000L, LoopPlayhead.interpolate(state, 35_000L, 1_000L, 31_000L))
    }

    @Test
    fun beforeAIsNotFolded() {
        val state = ready(positionMs = 5_000L)
        // 锚点 5s,推进 10s → 15s(仍在前奏区,< A)
        assertEquals(15_000L, LoopPlayhead.interpolate(state, 5_000L, 1_000L, 11_000L))
    }

    @Test
    fun outsideAuditionDoesNotFold() {
        val state = ready(auditionOutside = true, positionMs = 10_000L)
        // 锚点 10s,推进 10s → 20s:必须原样显示 20s(不得折回区间)
        assertEquals(20_000L, LoopPlayhead.interpolate(state, 10_000L, 1_000L, 11_000L))
    }

    @Test
    fun outsideAuditionClampsToDuration() {
        val state = ready(auditionOutside = true, positionMs = 100_000L, durationMs = 120_000L)
        assertEquals(120_000L, LoopPlayhead.interpolate(state, 100_000L, 1_000L, 30_000L))
    }

    @Test
    fun freeModeDoesNotFold() {
        val state = ready(mode = LoopPageMode.FREE, positionMs = 10_000L)
        assertEquals(20_000L, LoopPlayhead.interpolate(state, 10_000L, 1_000L, 11_000L))
    }

    @Test
    fun pausedReturnsStatePosition() {
        val state = ready(playback = PlaybackState.Paused, positionMs = 42_000L)
        assertEquals(42_000L, LoopPlayhead.interpolate(state, 41_000L, 1_000L, 99_000L))
    }
}
