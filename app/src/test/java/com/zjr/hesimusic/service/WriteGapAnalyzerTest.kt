package com.zjr.hesimusic.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 00029 判据：确认并保存入口单段 <=120ms；/loop/enter 冷入口单段 <=300ms；
 * 段数 >=2 = FAIL（二次 reconfigure）。这里用合成 writtenFrames 序列锁住分析口径。
 */
class WriteGapAnalyzerTest {

    private fun samples(vararg pairs: Pair<Long, Long>): List<WriteGapAnalyzer.Sample> =
        pairs.map { WriteGapAnalyzer.Sample(it.first, it.second) }

    @Test
    fun continuousWrites_haveNoGap() {
        val s = (0..20).map { WriteGapAnalyzer.Sample(it * 10L, it * 1024L) }
        assertTrue(WriteGapAnalyzer.internalGaps(s).isEmpty())
    }

    @Test
    fun oneStall_isOneSegment() {
        // 0..100ms 连续，100..390 停写，390 恢复 -> 单段 290ms。
        val s = ArrayList<WriteGapAnalyzer.Sample>()
        for (i in 0..10) s += WriteGapAnalyzer.Sample(i * 10L, i * 1024L)
        for (i in 11..39) s += WriteGapAnalyzer.Sample(i * 10L, 10L * 1024L)
        s += WriteGapAnalyzer.Sample(390L, 11L * 1024L)
        for (i in 40..50) s += WriteGapAnalyzer.Sample(i * 10L, (i - 28L) * 1024L)
        val gaps = WriteGapAnalyzer.internalGaps(s, minGapMs = 30L)
        assertEquals(1, gaps.size)
        assertEquals(290L, gaps[0].durationMs)
    }

    @Test
    fun twoStalls_areTwoSegments_fail() {
        val s = ArrayList<WriteGapAnalyzer.Sample>()
        for (i in 0..10) s += WriteGapAnalyzer.Sample(i * 10L, i * 1024L)
        for (i in 11..28) s += WriteGapAnalyzer.Sample(i * 10L, 10L * 1024L)
        s += WriteGapAnalyzer.Sample(290L, 11L * 1024L)
        for (i in 30..45) s += WriteGapAnalyzer.Sample(i * 10L, 11L * 1024L)
        s += WriteGapAnalyzer.Sample(460L, 12L * 1024L)
        val gaps = WriteGapAnalyzer.internalGaps(s, minGapMs = 30L)
        assertEquals(2, gaps.size)
        assertEquals(listOf(190L, 170L), gaps.map { it.durationMs })
    }

    @Test
    fun leadingAndTrailingStalls_areIgnored() {
        // 前 200ms 没有增长（页面暂停/冷启动）不算；最后一次增长后结束也不算。
        val s = listOf(
            WriteGapAnalyzer.Sample(0, 0),
            WriteGapAnalyzer.Sample(100, 0),
            WriteGapAnalyzer.Sample(200, 0),
            WriteGapAnalyzer.Sample(210, 1024),
            WriteGapAnalyzer.Sample(220, 2048),
            WriteGapAnalyzer.Sample(400, 2048),
        )
        assertTrue(WriteGapAnalyzer.internalGaps(s).isEmpty())
    }

    @Test
    fun threshold30ms_keepsShortJitterOut() {
        val s = listOf(
            WriteGapAnalyzer.Sample(0, 0),
            WriteGapAnalyzer.Sample(10, 1024),
            WriteGapAnalyzer.Sample(35, 1024),
            WriteGapAnalyzer.Sample(45, 2048),
        )
        // 10->45 = 35ms，>=30 记一段；改成 20ms 抖动则不记。
        assertEquals(1, WriteGapAnalyzer.internalGaps(s, 30L).size)
        assertTrue(WriteGapAnalyzer.internalGaps(s, 40L).isEmpty())
    }
}
