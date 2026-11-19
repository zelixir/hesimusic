package com.zjr.hesimusic.core.invariants

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 输出侧不变量（issue 00020）纯 JVM 测试。
 *
 * 规则与任务约定一致：
 * - paused + ΔwrittenFrames>0 → 触发；
 * - paused + Δ==0 → 不触发；
 * - playing + 连续 1s Δ==0 → 触发。
 */
class OutputInvariantTest {

    @Test
    fun pausedWithIncreasingFramesTriggers() {
        val evaluator = OutputInvariantEvaluator()
        assertNull(evaluator.onSample(OutputInvariantSample(0L, playWhenReady = false, writtenFrames = 100L)))
        val event = evaluator.onSample(
            OutputInvariantSample(500L, playWhenReady = false, writtenFrames = 200L),
        )
        assertNotNull(event)
        assertEquals(InvariantCode.PAUSED_BUT_SINK_WROTE, event!!.code)
        assertEquals(
            "INVARIANT violated: paused-but-sink-wrote frames=100",
            event.message,
        )
        assertEquals(500L, event.atMs)
    }

    @Test
    fun pausedWithoutFramesDoesNotTrigger() {
        val evaluator = OutputInvariantEvaluator()
        for (i in 0..20) {
            assertNull(
                evaluator.onSample(
                    OutputInvariantSample(i * 500L, playWhenReady = false, writtenFrames = 100L),
                ),
            )
        }
    }

    @Test
    fun playingSilentForOneSecondTriggers() {
        val evaluator = OutputInvariantEvaluator()
        // 第一帧只建立基线（状态未知，不归因）。
        assertNull(evaluator.onSample(OutputInvariantSample(0L, playWhenReady = true, writtenFrames = 1_000L)))
        // [0,500) 零帧：静音从 0ms 起算。
        assertNull(evaluator.onSample(OutputInvariantSample(500L, playWhenReady = true, writtenFrames = 1_000L)))
        // [0,1000) 连续 1s 零帧 → 触发。
        val event = evaluator.onSample(
            OutputInvariantSample(1_000L, playWhenReady = true, writtenFrames = 1_000L),
        )
        assertNotNull(event)
        assertEquals(InvariantCode.PLAYING_BUT_SINK_SILENT, event!!.code)
        assertEquals("INVARIANT violated: playing-but-sink-silent for=1000ms", event.message)
    }

    @Test
    fun playingWithFramesDoesNotTrigger() {
        val evaluator = OutputInvariantEvaluator()
        var frames = 1_000L
        for (i in 0..10) {
            assertNull(
                evaluator.onSample(
                    OutputInvariantSample(i * 500L, playWhenReady = true, writtenFrames = frames),
                ),
            )
            frames += 96L
        }
    }

    @Test
    fun bufferingSilenceDoesNotTrigger() {
        val evaluator = OutputInvariantEvaluator()
        for (i in 0..10) {
            assertNull(
                evaluator.onSample(
                    OutputInvariantSample(
                        i * 500L,
                        playWhenReady = true,
                        writtenFrames = 1_000L,
                        buffering = true,
                        playbackStateReady = false,
                    ),
                ),
            )
        }
    }

    @Test
    fun sampleRightAfterPausingIsIgnoredBecauseDeltaMayPredateThePause() {
        val evaluator = OutputInvariantEvaluator()
        // 播放中写了帧。
        assertNull(evaluator.onSample(OutputInvariantSample(0L, playWhenReady = true, writtenFrames = 100L)))
        // 暂停后的第一次采样：Δ 里可能仍含暂停前的帧 → 跳过。
        assertNull(evaluator.onSample(OutputInvariantSample(500L, playWhenReady = false, writtenFrames = 300L)))
        // 之后仍继续写 → 触发。
        val event = evaluator.onSample(
            OutputInvariantSample(1_000L, playWhenReady = false, writtenFrames = 400L),
        )
        assertNotNull(event)
        assertEquals(InvariantCode.PAUSED_BUT_SINK_WROTE, event!!.code)
    }

    @Test
    fun pausedWriteIsReportedOncePerPauseStreak() {
        val evaluator = OutputInvariantEvaluator()
        assertNull(evaluator.onSample(OutputInvariantSample(0L, playWhenReady = false, writtenFrames = 100L)))
        assertNotNull(evaluator.onSample(OutputInvariantSample(500L, playWhenReady = false, writtenFrames = 200L)))
        assertNull(evaluator.onSample(OutputInvariantSample(1_000L, playWhenReady = false, writtenFrames = 300L)))
        // 恢复播放后重新武装。
        assertNull(evaluator.onSample(OutputInvariantSample(1_500L, playWhenReady = true, writtenFrames = 400L)))
        assertNull(evaluator.onSample(OutputInvariantSample(2_000L, playWhenReady = false, writtenFrames = 500L)))
        assertNotNull(evaluator.onSample(OutputInvariantSample(2_500L, playWhenReady = false, writtenFrames = 600L)))
    }
}
