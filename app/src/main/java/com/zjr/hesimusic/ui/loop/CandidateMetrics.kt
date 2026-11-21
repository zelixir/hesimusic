package com.zjr.hesimusic.ui.loop

import java.util.Locale
import kotlin.math.roundToInt

/**
 * 候选行指标文案(纯 JVM 可测,不依赖 Compose)。
 *
 * 同时给出两个数,让用户看得出候选是"和声像"还是"接缝真顺":
 * - 相似度:**legacyScore**(接缝融合前的纯和弦序列相似度,即旧版 score 语义);
 *   老数据/过渡格式里 legacy 未知(0)时回退显示「综合分 = score」,不冒充相似度;
 * - 接缝:seamQuality 百分比,**只在已知时显示**。0/NaN/Inf 表示接缝阶段未运行
 *   (LOOP_ENGINE_SEAM=0)、老数据或用户手动微调后的合成候选;此时只显示相似度
 *   (legacy 也未知时显示综合分),绝不显示"接缝 0%"误导用户。
 *
 * 样例:`相似度 0.98 / 接缝 86% · 音高距离 0.012 · 响度比 0.34`;
 * legacy 未知时:`综合分 0.75 · 音高距离 0.012 · 响度比 0.34`。
 */
internal fun candidateMetricsText(
    score: Double,
    legacyScore: Double,
    noteDistance: Double,
    loudnessDifference: Double,
    seamQuality: Double,
): String = buildString {
    append(similarityOrCombinedLabel(score, legacyScore))
    seamQualityLabel(seamQuality)?.let { append(" / ").append(it) }
    append(" · 音高距离 ").append(fmtCandidateMetric(noteDistance, 3))
    append(" · 响度比 ").append(fmtCandidateMetric(loudnessDifference, 2))
}

/**
 * 首选指标:`相似度 {legacyScore}`;legacy 未知(0/NaN/Inf)→ 回退 `综合分 {score}`。
 *
 * legacy=0 是"没透出"的哨兵(老 JSON / 6 字段过渡格式 / 合成候选),不是"相似度为 0",
 * 所以不能显示成 0.00;用综合分既诚实又能解释候选为何被选中。
 */
internal fun similarityOrCombinedLabel(score: Double, legacyScore: Double): String =
    if (legacyScore.isFinite() && legacyScore > 0.0) {
        "相似度 " + fmtCandidateMetric(legacyScore, 2)
    } else {
        "综合分 " + fmtCandidateMetric(score, 2)
    }

/**
 * `接缝 86%`;**未知 → null**(调用方据此不渲染接缝)。
 *
 * 已知值夹到 0..1;正数至少显示 1%(避免 0.4% 被四舍五入成"接缝 0%")。
 */
internal fun seamQualityLabel(seamQuality: Double): String? {
    if (!seamQuality.isFinite() || seamQuality <= 0.0) return null
    return "接缝 " + seamQualityPercent(seamQuality) + "%"
}

/**
 * 长按候选行时展示的"排序依据"(纯文案,可单测):
 * 关键是把融合分与两个分量分开,避免用户拿相似度去解释排序。
 */
internal fun candidateRankingText(
    score: Double,
    legacyScore: Double,
    seamQuality: Double,
): String {
    val legacyKnown = legacyScore.isFinite() && legacyScore > 0.0
    val seamKnown = seamQuality.isFinite() && seamQuality > 0.0
    return buildString {
        append("综合分 ").append(fmtCandidateMetric(score, 2))
        append("(候选按此分排序,不是单看相似度或接缝)\n")
        append("相似度(纯和弦)")
        if (legacyKnown) append(fmtCandidateMetric(legacyScore, 2)) else append("未知")
        append(" · 接缝")
        if (seamKnown) append(" ").append(seamQualityPercent(seamQuality)).append("%")
        else append("未评估")
        append("\n")
        val explanation = when {
            !seamKnown -> "接缝阶段未运行/未评估,综合分即相似度。"
            !legacyKnown -> "相似度未透出(老数据),综合分由相似度与接缝融合得到。"
            else -> "综合分由相似度与接缝融合得到。"
        }
        append(explanation)
    }
}

private fun seamQualityPercent(seamQuality: Double): Int =
    (seamQuality.coerceIn(0.0, 1.0) * 100.0).roundToInt().coerceAtLeast(1)

private fun fmtCandidateMetric(value: Double, decimals: Int): String =
    String.format(Locale.US, "%.${decimals}f", value)
