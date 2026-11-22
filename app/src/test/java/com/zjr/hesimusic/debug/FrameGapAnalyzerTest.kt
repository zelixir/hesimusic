package com.zjr.hesimusic.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameGapAnalyzerTest {

    @Test
    fun continuouslyGrowingFramesHaveNoGaps() {
        val n = 101
        val ts = LongArray(n) { it * 5L }
        val fs = LongArray(n) { it * 441L }
        val r = FrameGapAnalyzer.analyze(ts, fs, 60_000L, 500L)
        assertEquals(101, r.samples)
        assertTrue(r.gaps.isEmpty())
        assertEquals(0L, r.lastGapMs)
    }

    @Test
    fun normal20msCadenceHasNoGapsAndThresholdIs60() {
        val ts = LongArray(201) { it * 5L } // 0..1000
        val fs = LongArray(201) { i -> (i / 4L) * 441L } // 每 20ms 变化一次
        val r = FrameGapAnalyzer.analyze(ts, fs, 60_000L, 1_000L)
        assertEquals(20L, r.writeIntervalP50Ms)
        assertEquals(60L, r.thresholdMs)
        assertTrue(r.gaps.isEmpty())
    }

    @Test
    fun twentyMsCadenceWith150msStallIsOneGap() {
        val ts = LongArray(401) { it * 5L } // 0..2000
        val fs = LongArray(401) { i ->
            val t = i * 5L
            when {
                t in 400L..549L -> (400L / 20L) * 441L // 停 150ms
                else -> (t / 20L) * 441L
            }
        }
        val r = FrameGapAnalyzer.analyze(ts, fs, 60_000L, 2_000L)
        assertEquals(1, r.gaps.size)
        assertEquals(150L, r.gaps[0].durationMs)
    }

    @Test
    fun slow50msCadenceHasNoGapsAndThresholdIs150() {
        val ts = LongArray(201) { it * 5L } // 0..1000
        val fs = LongArray(201) { i -> (i / 10L) * 441L } // 每 50ms 变化一次
        val r = FrameGapAnalyzer.analyze(ts, fs, 60_000L, 1_000L)
        assertEquals(50L, r.writeIntervalP50Ms)
        assertEquals(150L, r.thresholdMs)
        assertTrue(r.gaps.isEmpty())
    }

    @Test
    fun hundredMsStallIsOneGap() {
        val ts = LongArray(61) { it * 5L } // 0..300
        val fs = LongArray(61) { i ->
            val t = i * 5L
            when {
                t < 100L -> t * 10L
                t < 200L -> 1_000L
                else -> 2_000L + (t - 200L) * 10L
            }
        }
        val r = FrameGapAnalyzer.analyze(ts, fs, 60_000L, 300L)
        assertEquals(1, r.gaps.size)
        assertEquals(100L, r.gaps[0].durationMs)
        assertEquals(100L, r.lastGapMs)
    }

    @Test
    fun fifteenMsStallIsBelowThreshold() {
        val ts = LongArray(61) { it * 5L }
        val fs = LongArray(61) { i ->
            val t = i * 5L
            when {
                t < 100L -> t * 10L
                t < 115L -> 1_000L
                else -> 2_000L + (t - 115L) * 10L
            }
        }
        val r = FrameGapAnalyzer.analyze(ts, fs, 60_000L, 300L)
        assertTrue(r.gaps.isEmpty())
    }
}
