package com.zjr.hesimusic.ui.loop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 候选行指标文案的纯 JVM 单测(不引入 Compose/Robolectric)。
 *
 * 重点:seamQuality 未知(0/NaN/Inf)时**不得**出现"接缝",避免用户把"没量"读成"接缝为 0"。
 */
class CandidateMetricsTest {

    @Test
    fun `接缝已知时同时显示相似度与接缝百分比`() {
        val text = candidateMetricsText(
            score = 0.98,
            noteDistance = 0.012,
            loudnessDifference = 0.34,
            seamQuality = 0.86,
        )
        assertTrue(text, text.contains("相似度 0.98"))
        assertTrue(text, text.contains("接缝 86%"))
        assertEquals("相似度 0.98 / 接缝 86% · 音高距离 0.012 · 响度比 0.34", text)
    }

    @Test
    fun `seamQuality 为 0 时只显示相似度_不出现接缝`() {
        val text = candidateMetricsText(
            score = 0.77,
            noteDistance = 0.05,
            loudnessDifference = 0.06,
            seamQuality = 0.0,
        )
        assertTrue(text, text.contains("相似度 0.77"))
        assertFalse("未知接缝不得显示成 0%: $text", text.contains("接缝"))
        assertNull(seamQualityLabel(0.0))
    }

    @Test
    fun `NaN 与 Inf 同样按未知处理`() {
        assertNull(seamQualityLabel(Double.NaN))
        assertNull(seamQualityLabel(Double.POSITIVE_INFINITY))
        assertNull(seamQualityLabel(Double.NEGATIVE_INFINITY))
        val text = candidateMetricsText(0.5, 0.1, 0.2, Double.NaN)
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
}
