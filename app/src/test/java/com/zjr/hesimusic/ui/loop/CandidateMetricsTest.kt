package com.zjr.hesimusic.ui.loop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 候选行指标文案的纯 JVM 单测(不引入 Compose/Robolectric)。
 *
 * 重点:
 * - 「相似度」必须是 legacyScore(纯和弦相似度),不是融合分 score;
 * - legacy 未知(0/NaN/Inf)时回退「综合分」,不冒充相似度;
 * - seamQuality 未知(0/NaN/Inf)时**不得**出现"接缝",避免把"没量"读成"接缝为 0"。
 */
class CandidateMetricsTest {

    @Test
    fun `相似度用 legacyScore 而不是融合分_接缝已知时同时显示百分比`() {
        val text = candidateMetricsText(
            score = 0.92, // 融合分
            legacyScore = 0.98, // 纯和弦相似度
            noteDistance = 0.012,
            loudnessDifference = 0.34,
            seamQuality = 0.86,
        )
        assertTrue(text, text.contains("相似度 0.98"))
        assertFalse("不能把融合分 0.92 当成相似度: $text", text.contains("0.92"))
        assertTrue(text, text.contains("接缝 86%"))
        assertEquals("相似度 0.98 / 接缝 86% · 音高距离 0.012 · 响度比 0.34", text)
    }

    @Test
    fun `seamQuality 为 0 时只显示相似度_不出现接缝`() {
        val text = candidateMetricsText(
            score = 0.77,
            legacyScore = 0.77,
            noteDistance = 0.05,
            loudnessDifference = 0.06,
            seamQuality = 0.0,
        )
        assertTrue(text, text.contains("相似度 0.77"))
        assertFalse("未知接缝不得显示成 0%: $text", text.contains("接缝"))
        assertNull(seamQualityLabel(0.0))
    }

    @Test
    fun `legacy 未知时回退显示综合分_不冒充相似度`() {
        val text = candidateMetricsText(
            score = 0.75,
            legacyScore = 0.0,
            noteDistance = 0.05,
            loudnessDifference = 0.06,
            seamQuality = 0.86,
        )
        assertTrue("legacy=0 应回退综合分: $text", text.contains("综合分 0.75"))
        assertFalse("不得把未知 legacy 显示成相似度 0.00: $text", text.contains("相似度"))
        assertTrue(text, text.contains("接缝 86%"))
        assertEquals("综合分 0.75 / 接缝 86% · 音高距离 0.050 · 响度比 0.06", text)
    }

    @Test
    fun `相似度标签只认 legacyScore`() {
        assertEquals("相似度 0.98", similarityOrCombinedLabel(0.50, 0.98))
        assertEquals("综合分 0.50", similarityOrCombinedLabel(0.50, 0.0))
        assertEquals("综合分 0.50", similarityOrCombinedLabel(0.50, Double.NaN))
    }

    @Test
    fun `NaN 与 Inf 同样按未知处理`() {
        assertNull(seamQualityLabel(Double.NaN))
        assertNull(seamQualityLabel(Double.POSITIVE_INFINITY))
        assertNull(seamQualityLabel(Double.NEGATIVE_INFINITY))
        val text = candidateMetricsText(0.5, 0.5, 0.1, 0.2, Double.NaN)
        assertFalse(text, text.contains("接缝"))
    }

    @Test
    fun `负接缝分按未知处理`() {
        assertNull(seamQualityLabel(-0.2))
    }

    @Test
    fun `接缝百分比四舍五入并夹到 0 到 100`() {
        assertEquals("接缝 86%", seamQualityLabel(0.855))
        assertEquals("接缝 100%", seamQualityLabel(1.0))
        assertEquals("接缝 100%", seamQualityLabel(1.5))
    }

    @Test
    fun `极小正数不会显示成接缝 0 百分比`() {
        assertEquals("接缝 1%", seamQualityLabel(0.004))
    }

    @Test
    fun `长按排序依据_综合分与两个分量分开显示`() {
        val text = candidateRankingText(score = 0.92, legacyScore = 0.98, seamQuality = 0.86)
        assertTrue(text, text.contains("综合分 0.92"))
        assertTrue(text, text.contains("相似度(纯和弦)0.98"))
        assertTrue(text, text.contains("接缝 86%"))
        assertTrue(text, text.contains("融合"))
    }

    @Test
    fun `长按排序依据_legacy 或 seam 未知时说清楚`() {
        val noSeam = candidateRankingText(score = 0.77, legacyScore = 0.77, seamQuality = 0.0)
        assertTrue(noSeam, noSeam.contains("接缝未评估"))
        assertTrue(noSeam, noSeam.contains("综合分即相似度"))
        val noLegacy = candidateRankingText(score = 0.75, legacyScore = 0.0, seamQuality = 0.86)
        assertTrue(noLegacy, noLegacy.contains("相似度(纯和弦)未知"))
        assertTrue(noLegacy, noLegacy.contains("综合分由相似度与接缝融合"))
    }
}
