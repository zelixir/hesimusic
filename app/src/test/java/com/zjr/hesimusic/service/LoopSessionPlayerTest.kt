package com.zjr.hesimusic.service

import com.zjr.hesimusic.loop.LoopWindow
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 位置/时长/seek 投影单测（纯 JVM）。
 *
 * 为什么测 [LoopSessionProjection] 而不是 LoopSessionPlayer 本人：
 * LoopSessionPlayer 只是 ForwardingPlayer 的薄转发（取 raw → 调投影 → µs/ms 换算），
 * 而 media3 的 Player 接口有上百个方法、ForwardingPlayer 的构造还要求应用 looper，
 * 在纯 JVM 单测里造一个 Player 既不现实也不值得。所以投影逻辑全部抽到纯函数对象里，
 * 这里穷举它的四个入口（外加 buffered 收口），SessionPlayer 只保留 10 行转发。
 *
 * 口径：A = 10s, B = 20s, L = 10s；raw 为引擎媒体位置（µs），display 为对外进度（µs）。
 */
class LoopSessionPlayerTest {

    private val window = LoopWindow(songId = 7L, loopStartUs = A, loopEndUs = B)

    // ---------------------------------------------------------------- 1. window = null 全透传

    @Test
    fun windowNull_displaySeekDurationBufferedPassThrough() {
        assertEquals(12_345_678L, LoopSessionProjection.displayUs(12_345_678L, null))
        assertEquals(
            3_000_000L,
            LoopSessionProjection.seekUs(3_000_000L, null, currentRawUs = 9_000_000L, isRingComplete = false),
        )
        assertEquals(0L, LoopSessionProjection.durationUs(null, 225_000_000L))
        assertEquals(
            5_000_000L,
            LoopSessionProjection.bufferedUs(5_000_000L, null, currentDisplayUs = 4_000_000L, durationUs = 0L),
        )
    }

    // ---------------------------------------------------------------- 2. display 折返

    @Test
    fun display_preludeIsPlayedOnce() {
        // raw < A：前奏只播一次，原样返回
        assertEquals(0L, LoopSessionProjection.displayUs(0L, window))
        assertEquals(5_000_000L, LoopSessionProjection.displayUs(5_000_000L, window))
        assertEquals(A - 1L, LoopSessionProjection.displayUs(A - 1L, window))
    }

    @Test
    fun display_firstIterationStaysRaw() {
        assertEquals(A, LoopSessionProjection.displayUs(A, window))
        assertEquals(15_000_000L, LoopSessionProjection.displayUs(15_000_000L, window))
        assertEquals(B - 1L, LoopSessionProjection.displayUs(B - 1L, window))
    }

    @Test
    fun display_foldsEveryIterationBackToWindow() {
        // raw == B（首轮终点的下一采样）→ 折回 A
        assertEquals(A, LoopSessionProjection.displayUs(B, window))
        // 第 1 轮 + 5s
        assertEquals(15_000_000L, LoopSessionProjection.displayUs(B + 5_000_000L, window))
        // 第 2 轮 + 7s：10 + 2*10 + 7 = 47s
        assertEquals(17_000_000L, LoopSessionProjection.displayUs(A + 2 * L + 7_000_000L, window))
        // 第 3 轮 + 5s：10 + 3*10 + 5 = 45s
        assertEquals(15_000_000L, LoopSessionProjection.displayUs(A + 3 * L + 5_000_000L, window))
    }

    @Test
    fun display_invalidWindowPassesThrough() {
        val broken = LoopWindow(songId = 7L, loopStartUs = B, loopEndUs = A) // 负长度 → 非法
        assertEquals(15_000_000L, LoopSessionProjection.displayUs(15_000_000L, broken))
    }

    // ---------------------------------------------------------------- 3. seek 三分支

    @Test
    fun seek_beforeWindowReturnsTarget() {
        // target < A：回到前奏，播到 B 后自然进入折返
        assertEquals(5_000_000L, LoopSessionProjection.seekUs(5_000_000L, window, currentRawUs = 33_000_000L, isRingComplete = true))
        assertEquals(9_000_000L, LoopSessionProjection.seekUs(9_000_000L, window, currentRawUs = 0L, isRingComplete = true))
    }

    @Test
    fun seek_insideWindowKeepsCurrentIteration() {
        // 当前在第 2 轮（raw = A + 2L + 3s = 33s），seek 到展示位置 14s
        // → 同一轮的 10 + 2*10 + 4 = 34s（不把播放位置拽回第 0 轮）
        assertEquals(
            34_000_000L,
            LoopSessionProjection.seekUs(14_000_000L, window, currentRawUs = 33_000_000L, isRingComplete = true),
        )
        // 当前在前奏 / 恰在 A：k = 0
        assertEquals(14_000_000L, LoopSessionProjection.seekUs(14_000_000L, window, currentRawUs = 0L, isRingComplete = true))
        assertEquals(A, LoopSessionProjection.seekUs(A, window, currentRawUs = 0L, isRingComplete = true))
    }

    @Test
    fun seek_atOrAfterWindowEndFoldsToFirstIteration() {
        // target >= B：折回第 0 轮坐标（与旧行为一致）
        assertEquals(A, LoopSessionProjection.seekUs(B, window, currentRawUs = 33_000_000L, isRingComplete = true))
        assertEquals(15_000_000L, LoopSessionProjection.seekUs(25_000_000L, window, currentRawUs = 33_000_000L, isRingComplete = true))
        assertEquals(15_000_000L, LoopSessionProjection.seekUs(A + 3 * L + 5_000_000L, window, currentRawUs = 0L, isRingComplete = true))
    }

    // ---------------------------------------------------------------- 4. 降级预卷

    @Test
    fun degrade_prerollsToIterationStartMinus250ms() {
        // 正常映射：34s；本轮起点 = 34s - (14s - 10s) = 30s → 预卷 29.75s
        assertEquals(
            29_750_000L,
            LoopSessionProjection.seekUs(14_000_000L, window, currentRawUs = 33_000_000L, isRingComplete = false),
        )
        // target 恰为 A 时预卷到本轮起点前 250ms（第 2 轮 → 29.75s）
        assertEquals(
            29_750_000L,
            LoopSessionProjection.seekUs(A, window, currentRawUs = 33_000_000L, isRingComplete = false),
        )
        // k = 0：本轮起点 = A = 10s → 9.75s（前奏尾部也要完整流经，才能补齐从 A 开始的 ring）
        assertEquals(
            9_750_000L,
            LoopSessionProjection.seekUs(12_000_000L, window, currentRawUs = 0L, isRingComplete = false),
        )
    }

    @Test
    fun degrade_prerollNeverGoesBelowZero() {
        val early = LoopWindow(songId = 7L, loopStartUs = 100_000L, loopEndUs = 10_100_000L)
        assertEquals(0L, LoopSessionProjection.degradedSeekUs(100_000L))
        assertEquals(
            0L,
            LoopSessionProjection.seekUs(5_000_000L, early, currentRawUs = 0L, isRingComplete = false),
        )
        // 起点足够大时保持精确的 250ms 预卷
        assertEquals(9_750_000L, LoopSessionProjection.degradedSeekUs(A))
    }

    @Test
    fun degrade_skippedWhenRingComplete() {
        assertEquals(
            34_000_000L,
            LoopSessionProjection.seekUs(14_000_000L, window, currentRawUs = 33_000_000L, isRingComplete = true),
        )
    }

    @Test
    fun degrade_skippedWhenTargetOutsideWindow() {
        // target < A：回前奏，本来就会完整流经 [A,B)，不需要预卷
        assertEquals(
            5_000_000L,
            LoopSessionProjection.seekUs(5_000_000L, window, currentRawUs = 33_000_000L, isRingComplete = false),
        )
        // target >= B：折回第 0 轮，同样会完整流经 [A,B)
        assertEquals(
            15_000_000L,
            LoopSessionProjection.seekUs(25_000_000L, window, currentRawUs = 33_000_000L, isRingComplete = false),
        )
        // 非法窗口：containsUs 为 false → 原样透传
        val broken = LoopWindow(songId = 7L, loopStartUs = B, loopEndUs = A)
        assertEquals(
            15_000_000L,
            LoopSessionProjection.seekUs(15_000_000L, broken, currentRawUs = 33_000_000L, isRingComplete = false),
        )
    }

    // ---------------------------------------------------------------- 5. 时长覆盖

    @Test
    fun duration_projectedToFullSongWhenWindowActive() {
        // 整曲 225s：进度条/通知必须看到整曲时长，而不是 clip 后的 B
        assertEquals(225_000_000L, LoopSessionProjection.durationUs(window, 225_000_000L))
        // 整曲时长未知（Song.duration = 0）→ 返回 0，调用方透传原生时长
        assertEquals(0L, LoopSessionProjection.durationUs(window, 0L))
        // window 为空 → 不投影
        assertEquals(0L, LoopSessionProjection.durationUs(null, 225_000_000L))
    }

    // ---------------------------------------------------------------- 6. 重放期 vs 非重放期

    @Test
    fun raw_replayPhaseUsesSinkPositionPlainPhaseUsesPlayerPosition() {
        // 重放期：用 sink 推进的媒体位置（B + kL + 环内偏移），player 自身位置冻结在 B
        assertEquals(
            34_000_000L,
            LoopSessionProjection.rawUs(isReplaying = true, replayPositionUs = 34_000_000L, playerPositionMs = 20_000L),
        )
        // 非重放期：用播放器位置（ms → µs）
        assertEquals(
            20_000_000L,
            LoopSessionProjection.rawUs(isReplaying = false, replayPositionUs = 34_000_000L, playerPositionMs = 20_000L),
        )
        // 同一时刻两种取法对外表现完全不同：重放期 14s（第 2 轮内），非重放期冻结在 A
        val replayDisplay = LoopSessionProjection.displayUs(
            LoopSessionProjection.rawUs(true, 34_000_000L, 20_000L),
            window,
        )
        val plainDisplay = LoopSessionProjection.displayUs(
            LoopSessionProjection.rawUs(false, 34_000_000L, 20_000L),
            window,
        )
        assertEquals(14_000_000L, replayDisplay)
        assertEquals(A, plainDisplay)
        // seek 的 currentRawUs 也必须来自同一口径，否则会算错轮次
        assertEquals(
            34_000_000L,
            LoopSessionProjection.seekUs(
                targetUs = 14_000_000L,
                window = window,
                currentRawUs = LoopSessionProjection.rawUs(true, 34_000_000L, 20_000L),
                isRingComplete = true,
            ),
        )
    }

    // ---------------------------------------------------------------- 7. buffered 收口

    @Test
    fun buffered_foldsAndClampsToDuration() {
        // 缓冲位 25s → 折返 15s，且不小于当前展示位置 14s
        assertEquals(
            15_000_000L,
            LoopSessionProjection.bufferedUs(25_000_000L, window, currentDisplayUs = 14_000_000L, durationUs = 225_000_000L),
        )
        // 缓冲位折返后小于当前位置（重放期播放器缓冲冻结在 B）→ 取当前位置，进度条不倒退
        assertEquals(
            14_000_000L,
            LoopSessionProjection.bufferedUs(B, window, currentDisplayUs = 14_000_000L, durationUs = 225_000_000L),
        )
        // 不超过对外时长
        assertEquals(
            12_000_000L,
            LoopSessionProjection.bufferedUs(25_000_000L, window, currentDisplayUs = 14_000_000L, durationUs = 12_000_000L),
        )
    }

    // ---------------------------------------------------------------- 8. 往返一致性

    @Test
    fun roundTrip_seekThenDisplayReturnsSameDisplayPosition() {
        val currentRawUs = 33_000_000L
        for (target in listOf(A, A + 1L, A + 4_999_999L, B - 1L)) {
            val raw = LoopSessionProjection.seekUs(target, window, currentRawUs, isRingComplete = true)
            assertEquals(target, LoopSessionProjection.displayUs(raw, window))
        }
    }

    @Test
    fun millisecondsConversionKeepsDisplayedPosition() {
        // SessionPlayer 对外的单位是 ms：34_000_000us 的 raw 应报 14_000ms
        val rawUs = LoopSessionProjection.rawUs(true, 34_000_000L, 20_000L)
        assertEquals(14_000L, LoopSessionProjection.displayUs(rawUs, window) / LoopSessionProjection.US_PER_MS)
    }

    private companion object {
        private const val A = 10_000_000L
        private const val B = 20_000_000L
        private const val L = B - A
    }
}
