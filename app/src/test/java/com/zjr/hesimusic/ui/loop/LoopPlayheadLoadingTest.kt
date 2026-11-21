package com.zjr.hesimusic.ui.loop

import com.zjr.hesimusic.ui.loop.state.DragState
import com.zjr.hesimusic.ui.loop.state.LoopPageMode
import com.zjr.hesimusic.ui.loop.state.LoopPageState
import com.zjr.hesimusic.ui.loop.state.PlaybackState
import com.zjr.hesimusic.ui.loop.state.PrefillState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * issue 00022:等待首帧出声期间,进度指针**必须冻结在落点**,不得前移。
 *
 * 真机现象:点「接缝检查」后播放器还在 buffering(真实位置停在 B−5s 一步没走),但播放头是
 * "锚点 + 时间外推"算出来的 —— 于是指针以 1s/s 自己往前走,出声那一刻再被真实位置拽回去,
 * 用户看到的就是"指针乱跳 / 回跳"。
 *
 * 契约:[LoopPlayhead.interpolate] 在 loading=true 时**只**返回 [anchorMs]
 * (加载中的锚点 = 本次操作的落点,由 play/seek effect 在点击同一轮写入),不做任何外推/折返。
 */
class LoopPlayheadLoadingTest {

    /** Shiny Up 的现场数值:A=23.301s B=261.457s,接缝检查落点 = B−5s = 256.457s。 */
    private val landingMs = 256_457L

    private fun ready(
        playback: PlaybackState = PlaybackState.Playing,
        positionMs: Long = landingMs,
        mode: LoopPageMode = LoopPageMode.SEAM,
        aMs: Long = 23_301L,
        bMs: Long = 261_457L,
        durationMs: Long = 284_865L,
    ): LoopPageState.Ready = LoopPageState.Ready(
        songId = 41_126L,
        durationMs = durationMs,
        sampleRate = 44_100,
        mode = mode,
        aMs = aMs,
        bMs = bMs,
        candidates = emptyList(),
        selectedCandidate = 0,
        confirmed = true,
        prefill = PrefillState.FirstBlockLoading,
        playback = playback,
        positionMs = positionMs,
        drag = DragState.Idle,
        sourceMode = true,
    )

    @Test
    fun loadingKeepsPointerAtLanding() {
        val state = ready()
        // 锚点 = 落点,点击后已经过了 2 秒(buffering):外推会给出 258457,冻结必须给 256457。
        assertEquals(landingMs, LoopPlayhead.interpolate(state, landingMs, 1_000L, 3_000L, loading = true))
    }

    @Test
    fun loadingIgnoresWallClock() {
        // 加载持续很久(60 秒)也不能漂移:只能用锚点(落点),不能用 now−anchor 外推。
        val state = ready()
        assertEquals(landingMs, LoopPlayhead.interpolate(state, landingMs, 1_000L, 61_000L, loading = true))
    }

    @Test
    fun loadingNeverFoldsAcrossLoopWindow() {
        // 落点在 [A,B) 内且靠近 B:加载中必须原样返回,不能被 A+((t-A)%L) 折返。
        val nearB = 261_400L
        val state = ready(positionMs = nearB)
        assertEquals(nearB, LoopPlayhead.interpolate(state, nearB, 1_000L, 9_000L, loading = true))
    }

    @Test
    fun sixtyFpsWhileLoadingNeverAdvances() {
        // 模拟 60fps 画 2 秒:每一帧都必须 == 落点(这就是"指针冻结")。
        val state = ready()
        for (frame in 0 until 120) {
            val now = 1_000L + frame * 16L
            assertEquals("frame " + frame, landingMs, LoopPlayhead.interpolate(state, landingMs, 1_000L, now, loading = true))
        }
    }

    @Test
    fun afterLoadingStartsMovingFromLanding() {
        val state = ready()
        // VM 在冻结期间每帧把锚点时刻贴到 now,所以解除冻结的第一帧只前进一帧的量。
        val firstFrame = LoopPlayhead.interpolate(state, landingMs, 3_000L, 3_016L, loading = false)
        assertEquals(landingMs + 16L, firstFrame)
        assertTrue("must keep moving after the first frame", firstFrame > landingMs)
    }

    @Test
    fun pausedStillReturnsPosition() {
        // 既有口径不变:非 Playing 一律返回 positionMs(暂停不画插值)。
        val state = ready(playback = PlaybackState.Paused)
        assertEquals(landingMs, LoopPlayhead.interpolate(state, 1_000L, 1_000L, 9_999L, loading = true))
    }

    @Test
    fun notLoadingKeepsInterpolationRules() {
        // 回归保护:loading=false 时插值与折返规则和 00023 完全一致(真的在播就该走)。
        val state = ready()
        assertEquals(landingMs + 2_000L, LoopPlayhead.interpolate(state, landingMs, 1_000L, 3_000L, loading = false))
        // 默认参数(= 不传 loading)也是同一条路径。
        assertEquals(landingMs + 2_000L, LoopPlayhead.interpolate(state, landingMs, 1_000L, 3_000L))
    }

    @Test
    fun probeRecordsFrozenFlag() {
        // 观测点只是两个 volatile:写进去读出来必须一致(/debug/preview 的 playheadMs 用它)。
        LoopPlayheadProbe.note(landingMs, frozen = true)
        assertEquals(landingMs, LoopPlayheadProbe.lastPlayheadMs)
        assertTrue(LoopPlayheadProbe.lastFrozen)
        LoopPlayheadProbe.reset()
        assertEquals(-1L, LoopPlayheadProbe.lastPlayheadMs)
    }
}
