package com.zjr.hesimusic.lab

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

private val W_A = 78.dp
private val W_B = 78.dp
private val W_SCORE = 66.dp
private val W_DELTA = 64.dp
private val W_RANK = 42.dp
private val W_SHIFT = 50.dp
private val W_SUB = 60.dp
private val W_SEAM = 62.dp
private val W_REF = 66.dp

/**
 * 候选对比表:每行一个候选,同时展示两套分数/名次与接缝分项。
 * 表头与所有行包在同一个 horizontalScroll 里,保证列对齐。
 */
@Composable
internal fun CandidateTableCard(
    state: LabUiState,
    result: LabResult,
    onSortMode: (LabSortMode) -> Unit,
    onOnlyTopN: (Boolean) -> Unit,
) {
    val summary = result.summary
    val topIds = remember(summary) {
        (summary.legacyTop + summary.fusedTop).map { it.legacyRank }.toSet()
    }
    val newIds = remember(summary) { summary.newInFused.map { it.legacyRank }.toHashSet() }
    val missingIds = remember(summary) { summary.missingFromFused.map { it.legacyRank }.toHashSet() }
    val all = result.raw.candidates
    val shown = remember(state.onlyTopN, state.sortMode, all, topIds) {
        val filtered = if (state.onlyTopN) all.filter { it.legacyRank in topIds } else all
        when (state.sortMode) {
            LabSortMode.FUSED -> filtered.sortedBy { it.fusedRank }
            LabSortMode.LEGACY -> filtered.sortedBy { it.legacyRank }
        }
    }
    val hasReference = result.accuracy != null
    LabCard(title = "6. 候选对比表(双算法)") {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = state.sortMode == LabSortMode.FUSED,
                onClick = { onSortMode(LabSortMode.FUSED) },
                label = { Text("新版名次") },
            )
            FilterChip(
                selected = state.sortMode == LabSortMode.LEGACY,
                onClick = { onSortMode(LabSortMode.LEGACY) },
                label = { Text("初版名次") },
            )
            FilterChip(
                selected = state.onlyTopN,
                onClick = { onOnlyTopN(!state.onlyTopN) },
                label = { Text(if (state.onlyTopN) "前 N 并集" else "全部候选") },
            )
        }
        Text(
            "显示 ${shown.size} / ${all.size} 行;前 N(N=${summary.topN})并集高亮;★ = 融合 top1。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            HeaderRow(hasReference)
            shown.forEachIndexed { index, candidate ->
                CandidateRow(
                    result = result,
                    candidate = candidate,
                    topIds = topIds,
                    newIds = newIds,
                    missingIds = missingIds,
                    hasReference = hasReference,
                    alternate = index % 2 == 1,
                )
            }
        }
    }
}

@Composable
private fun HeaderRow(hasReference: Boolean) {
    Row(
        Modifier.background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        HeaderCell("A ms", W_A)
        HeaderCell("B ms", W_B)
        HeaderCell("初版分", W_SCORE)
        HeaderCell("融合分", W_SCORE)
        HeaderCell("Δ分", W_DELTA)
        HeaderCell("初#", W_RANK)
        HeaderCell("新#", W_RANK)
        HeaderCell("Δ#", W_SHIFT)
        HeaderCell("接缝", W_SEAM)
        HeaderCell("corr", W_SUB)
        HeaderCell("rms", W_SUB)
        HeaderCell("flux", W_SUB)
        if (hasReference) {
            HeaderCell("ΔA ref", W_REF)
            HeaderCell("ΔB ref", W_REF)
        }
    }
}

@Composable
private fun HeaderCell(text: String, width: Dp) {
    Text(
        text = text,
        modifier = Modifier
            .width(width)
            .padding(horizontal = 4.dp, vertical = 5.dp),
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.End,
        maxLines = 1,
        overflow = TextOverflow.Clip,
    )
}

@Composable
private fun CandidateRow(
    result: LabResult,
    candidate: LabCandidate,
    topIds: Set<Int>,
    newIds: Set<Int>,
    missingIds: Set<Int>,
    hasReference: Boolean,
    alternate: Boolean,
) {
    val sampleRate = result.song.sampleRate
    val inTop = candidate.legacyRank in topIds
    val background = when {
        candidate.fusedRank == 0 -> MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
        inTop -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)
        alternate -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
        else -> Color.Transparent
    }
    val shift = candidate.fusedRank - candidate.legacyRank
    val shiftText = when {
        shift < 0 -> "↑${-shift}"
        shift > 0 -> "↓$shift"
        else -> "—"
    }
    val shiftColor = when {
        shift < 0 -> GoodColor
        shift > 0 -> BadColor
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val delta = candidate.fusedScore - candidate.legacyScore
    val deltaColor = when {
        delta > 1e-9 -> GoodColor
        delta < -1e-9 -> BadColor
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val source = if (candidate.seamEvaluated) {
        listOf(
            fmt(candidate.corrScore),
            fmt(candidate.energyScore),
            fmt(candidate.timbreScore),
        )
    } else {
        listOf("—", "—", "—")
    }

    Row(Modifier.background(background)) {
        Cell(fmt(candidate.startMs(sampleRate), 1), W_A)
        Cell(fmt(candidate.endMs(sampleRate), 1), W_B)
        Cell(fmt(candidate.legacyScore), W_SCORE)
        Cell(fmt(candidate.fusedScore), W_SCORE, bold = candidate.fusedRank == 0)
        Cell(signed(delta), W_DELTA, color = deltaColor)
        Cell(
            "${candidate.legacyRank + 1}" +
                (if (candidate.legacyRank in missingIds) " －" else ""),
            W_RANK,
            color = if (candidate.legacyRank in missingIds) BadColor else Color.Unspecified,
        )
        Cell(
            "${candidate.fusedRank + 1}" +
                (if (candidate.legacyRank in newIds) " ＋" else "") +
                (if (candidate.fusedRank == 0) " ★" else ""),
            W_RANK,
            bold = candidate.fusedRank == 0,
            color = if (candidate.legacyRank in newIds) GoodColor else Color.Unspecified,
        )
        Cell(shiftText, W_SHIFT, color = shiftColor)
        Cell(if (candidate.seamEvaluated) fmt(candidate.seamQuality) else "—", W_SEAM)
        Cell(source[0], W_SUB)
        Cell(source[1], W_SUB)
        Cell(source[2], W_SUB)
        if (hasReference) {
            val reference = result.accuracy
            val refA = reference?.referenceStartMs
            val refB = reference?.referenceEndMs
            if (refA != null && refB != null) {
                val da = candidate.startMs(sampleRate) - refA
                val db = candidate.endMs(sampleRate) - refB
                Cell(
                    signed(da, 1),
                    W_REF,
                    color = if (abs(da) <= 50.0) GoodColor else BadColor,
                )
                Cell(
                    signed(db, 1),
                    W_REF,
                    color = if (abs(db) <= 50.0) GoodColor else BadColor,
                )
            } else {
                Cell("—", W_REF)
                Cell("—", W_REF)
            }
        }
    }
}

@Composable
private fun Cell(
    text: String,
    width: Dp,
    color: Color = Color.Unspecified,
    bold: Boolean = false,
) {
    Text(
        text = text,
        modifier = Modifier
            .width(width)
            .padding(horizontal = 4.dp, vertical = 3.dp),
        fontSize = 10.sp,
        fontFamily = FontFamily.Monospace,
        color = color,
        fontWeight = if (bold) FontWeight.SemiBold else null,
        textAlign = TextAlign.End,
        maxLines = 1,
        overflow = TextOverflow.Clip,
    )
}

/**
 * seamWeight 0→1 扫描曲线:两条线分别是前 5 / 前 10 名与初版排名的重合率。
 * w=0 必为 100%(融合分退化为初版分),w=1 表示只看接缝质量。
 */
@Composable
internal fun SweepCard(points: List<SweepPoint>, topN: Int) {
    val color5 = MaterialTheme.colorScheme.primary
    val color10 = MaterialTheme.colorScheme.tertiary
    val grid = MaterialTheme.colorScheme.outlineVariant
    LabCard(title = "seamWeight 扫描(前 N 与初版一致性)") {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(170.dp)
                .padding(vertical = 6.dp),
        ) {
            val left = 6.dp.toPx()
            val right = size.width - 6.dp.toPx()
            val top = 8.dp.toPx()
            val bottom = size.height - 8.dp.toPx()
            val span = (right - left).coerceAtLeast(1f)
            fun x(index: Int): Float = left + span * index / 8f
            fun y(value: Double): Float {
                val v = value.toFloat().coerceIn(0f, 1f)
                return bottom - (bottom - top) * v
            }
            listOf(0.0, 0.25, 0.5, 0.75, 1.0).forEach { v ->
                drawLine(
                    color = grid,
                    start = Offset(left, y(v)),
                    end = Offset(right, y(v)),
                    strokeWidth = 1f,
                )
            }
            fun series(values: List<Double>, color: Color) {
                if (values.isEmpty()) return
                val path = Path()
                values.forEachIndexed { index, value ->
                    if (index == 0) path.moveTo(x(index), y(value)) else path.lineTo(x(index), y(value))
                }
                drawPath(
                    path = path,
                    color = color,
                    style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
                )
                values.forEachIndexed { index, value ->
                    drawCircle(color, radius = 3.dp.toPx(), center = Offset(x(index), y(value)))
                }
            }
            series(points.map { it.overlapAt5 }, color5)
            series(points.map { it.overlapAt10 }, color10)
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LegendDot(color5, "重合@5")
            LegendDot(color10, "重合@10")
            Text(
                "纵轴 0–100%,横轴 w=0.000 … 1.000(9 点)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        points.forEach { point ->
            Row(Modifier.fillMaxWidth()) {
                Text(
                    "w=${fmt(point.seamWeight, 3)}",
                    modifier = Modifier.width(80.dp),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    "@5 ${pct(point.overlapAt5)}",
                    modifier = Modifier.width(84.dp),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    "@10 ${pct(point.overlapAt10)}",
                    modifier = Modifier.width(92.dp),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    "@N ${pct(point.overlapAtN)}",
                    modifier = Modifier.width(90.dp),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    "top1 ${if (point.top1Same) "同" else "换"} · 接缝 ${fmt(point.avgSeamQualityTopN)}",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            "w=0 即初版排名(重合 100%);N=$topN。曲线越早下降,说明融合对初版改动越大。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .background(color, CircleShape),
        )
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}
