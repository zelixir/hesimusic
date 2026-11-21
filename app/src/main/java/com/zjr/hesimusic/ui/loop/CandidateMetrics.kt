package com.zjr.hesimusic.ui.loop

import java.util.Locale
import kotlin.math.roundToInt

/**
 * 候选行指标文案(纯 JVM 可测,不依赖 Compose)。
 *
 * 同时给出两个数,让用户看得出候选是"和声像"还是"接缝真顺":
 * - 相似度:引擎排序分 [score](v8 起为"和弦相似度 + 接缝质量"的融合分);
 * - 接缝:seamQuality 百分比，**只在已知时显示**。0/NaN/Inf 表示接缝阶段未运行
 *   (LOOP_ENGINE_SEAM=0)、老数据或用户手动微调后的合成候选;此时只显示相似度,
 *   绝不显示"接缝 0%"误导用户。
 *
 * 样例:`相似度 0.98 / 接缝 86% · 音高距离 0.012 · 响度比 0.34`。
 */
internal fun candidateMetricsText(
    score: Double,
    noteDistance: Double,
    loudnessDifference: Double,
    seamQuality: Double,
): String = buildString {
    append("相似度 ").append(fmtCandidateMetric(score, 2))
    seamQualityLabel(seamQuality)?.let { append(" / ").append(it) }
    append(" · 音高距离 ").append(fmtCandidateMetric(noteDistance, 3))
    append(" · 响度比 ").append(fmtCandidateMetric(loudnessDifference, 2))
}

/**
 * `接缝 86%`;**未知 → null**(调用方据此不渲染接缝)。
 *
 * 已知值夹到 0..1;正数至少显示 1%(避免 0.4% 被四舍五入成"接缝 0%")。
 */
internal fun seamQualityLabel(seamQuality: Double): String? {
    if (!seamQuality.isFinite() || seamQuality <= 0.0) return null
    val percent = (seamQuality.coerceIn(0.0, 1.0) * 100.0).roundToInt().coerceAtLeast(1)
    return "接缝 " + percent + "%"
}

private fun fmtCandidateMetric(value: Double, decimals: Int): String =
    String.format(Locale.US, "%.${decimals}f", value)
