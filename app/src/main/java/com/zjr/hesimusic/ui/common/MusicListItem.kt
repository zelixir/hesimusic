package com.zjr.hesimusic.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp

/** 序号列宽度(改造前后一致,保证列表左侧布局不变)。 */
private val INDEX_COLUMN_WIDTH = 40.dp

/** 行内容 padding(见 [MusicListItem] 的 Row);∞ 画在序号文字下方的这段留白里,不参与测量。 */
private val ROW_CONTENT_PADDING = 16.dp

/** 无缝状态符号:宽度 ≈ 「两位数字」宽度的 80%(数字宽度由字体度量,不写死 dp)。 */
private const val LOOP_BADGE_WIDTH_RATIO = 0.8f

/** ∞ 的自然高宽比:两个相切圆 → 高 = 宽 / 2。 */
private const val LOOP_BADGE_ASPECT_RATIO = 0.5f

/** 圆环线宽相对符号高度的比例。 */
private const val LOOP_BADGE_STROKE_RATIO = 0.18f

/** ∞ 与序号数字(基线)之间留 1dp。 */
private const val LOOP_BADGE_GAP_DP = 1f

/** 用于量「两位数字」宽度的探针;必须与序号同字号。 */
private const val TWO_DIGIT_PROBE = "00"

internal const val LOOP_CONFIRMED_DESCRIPTION = "已确认无缝循环"
internal const val LOOP_UNCONFIRMED_DESCRIPTION = "有无缝分析结果未确认"

/** 已确认无缝循环 = 绿色。 */
internal val LoopConfirmedColor = Color(0xFF3FB950)

/** 有无缝分析结果但未确认 = 淡灰色。 */
internal val LoopUnconfirmedColor = Color(0xFF9AA0A6)

/**
 * 序号下方 ∞ 符号的绘制尺寸(全部为像素)。
 *
 * 宽度 = 两位数字宽度 × 0.8;高度自然取宽度的一半(两相切圆),但不超过行内留白允许的高度
 * (超大字体缩放下避免画到下一行)。
 */
internal data class LoopBadgeGeometry(
    val widthPx: Float,
    val heightPx: Float,
    val strokeWidthPx: Float
)

internal fun loopBadgeGeometry(twoDigitWidthPx: Float, maxHeightPx: Float): LoopBadgeGeometry {
    val width = twoDigitWidthPx * LOOP_BADGE_WIDTH_RATIO
    val naturalHeight = width * LOOP_BADGE_ASPECT_RATIO
    val height = if (maxHeightPx > 0f) minOf(naturalHeight, maxHeightPx) else naturalHeight
    return LoopBadgeGeometry(
        widthPx = width,
        heightPx = height,
        strokeWidthPx = (height * LOOP_BADGE_STROKE_RATIO).coerceAtLeast(1f)
    )
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MusicListItem(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Default.MusicNote,
    isCurrent: Boolean = false,
    index: Int? = null,
    indexText: String? = null,
    indexTextColor: Color? = null,
    /**
     * 序号下方的无缝循环状态符号(issue 00007):
     * null = 无分析结果(不画任何像素);true = 已确认(绿);false = 有结果未确认(淡灰)。
     */
    loopStatus: Boolean? = null,
    titleTextStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    subtitleTextStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    val backgroundColor = if (isCurrent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f) else Color.Transparent
    val contentColor = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    val subtitleColor = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        color = backgroundColor
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val resolvedIndexText = indexText ?: index?.toString()
            if (resolvedIndexText != null) {
                SongIndexColumn(
                    indexText = resolvedIndexText,
                    color = indexTextColor ?: subtitleColor,
                    loopStatus = loopStatus
                )
            }

            /* Icon removed as per request
            Surface(
                shape = MaterialTheme.shapes.small,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(12.dp)
                        .size(24.dp),
                    tint = if (isCurrent) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            */
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = titleTextStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = contentColor
                )
                Text(
                    text = subtitle,
                    style = subtitleTextStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = subtitleColor
                )
            }
        }
    }
}

/**
 * 序号列(固定 40dp 宽)+ 其下方的无缝状态符号。
 *
 * 行高不变的关键:∞ 用 [Canvas] + matchParentSize 叠在序号 Text 之上,**不参与测量**;
 * 它画在文字基线下方的行内留白(16dp padding)里,因此序号仍垂直居中、行高与改造前完全一致。
 */
@Composable
private fun SongIndexColumn(
    indexText: String,
    color: Color,
    loopStatus: Boolean?
) {
    val textStyle = MaterialTheme.typography.bodyLarge
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val columnWidthPx = with(density) { INDEX_COLUMN_WIDTH.roundToPx() }

    // 与实际 Text 相同的约束(宽 40dp、最多两行)测量,拿数字宽度与最后一行基线。
    val indexLayout = remember(textMeasurer, indexText, textStyle, columnWidthPx, layoutDirection) {
        textMeasurer.measure(
            text = indexText,
            style = textStyle,
            overflow = TextOverflow.Ellipsis,
            maxLines = 2,
            constraints = Constraints(maxWidth = columnWidthPx),
            layoutDirection = layoutDirection
        )
    }
    // 字体度量「两位数字」宽度:随字号/字体缩放变化,不写死 dp。
    val twoDigitWidthPx = remember(textMeasurer, textStyle) {
        textMeasurer.measure(text = TWO_DIGIT_PROBE, style = textStyle).size.width.toFloat()
    }
    val maxBadgeHeightPx = with(density) { (ROW_CONTENT_PADDING - LOOP_BADGE_GAP_DP.dp).toPx() }
    val geometry = remember(twoDigitWidthPx, maxBadgeHeightPx) {
        loopBadgeGeometry(twoDigitWidthPx, maxBadgeHeightPx)
    }

    Box(modifier = Modifier.width(INDEX_COLUMN_WIDTH)) {
        Text(
            text = indexText,
            style = textStyle,
            color = color,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(INDEX_COLUMN_WIDTH)
        )
        if (loopStatus != null) {
            LoopSeamBadge(
                indexLayout = indexLayout,
                geometry = geometry,
                color = if (loopStatus) LoopConfirmedColor else LoopUnconfirmedColor,
                description = if (loopStatus) {
                    LOOP_CONFIRMED_DESCRIPTION
                } else {
                    LOOP_UNCONFIRMED_DESCRIPTION
                }
            )
        }
    }
}

/**
 * 序号下方的 ∞ 符号:两个相切圆环(静态绘制,一帧一次)。
 *
 * semantics 挂在 Canvas 上,而整行 Surface 是 combinedClickable(mergeDescendants=true),
 * 因此该描述会合并进行节点一起朗读,不会多出一个可聚焦节点。
 */
@Composable
private fun BoxScope.LoopSeamBadge(
    indexLayout: TextLayoutResult,
    geometry: LoopBadgeGeometry,
    color: Color,
    description: String
) {
    Canvas(
        modifier = Modifier
            .matchParentSize()
            .semantics { contentDescription = description }
    ) {
        if (geometry.widthPx <= 0f || geometry.heightPx <= 0f) return@Canvas
        val gapPx = LOOP_BADGE_GAP_DP.dp.toPx()
        // 紧贴数字下方:数字(无降部)坐在基线上,从最后一行基线往下 1dp 起画。
        val baseline = if (indexLayout.lineCount > 0) indexLayout.lastBaseline else indexLayout.size.height.toFloat()
        val top = baseline + gapPx
        val centerX = indexLineCenterX(indexLayout)
        val ovalWidth = geometry.widthPx / 2f
        val ovalHeight = geometry.heightPx
        val stroke = Stroke(width = geometry.strokeWidthPx)
        drawOval(color, topLeft = Offset(centerX - ovalWidth, top), size = Size(ovalWidth, ovalHeight), style = stroke)
        drawOval(color, topLeft = Offset(centerX, top), size = Size(ovalWidth, ovalHeight), style = stroke)
    }
}

/** 序号文字实际占用的水平范围中心(px),用于让 ∞ 与数字居中对齐(LTR/RTL 都成立)。 */
private fun indexLineCenterX(layout: TextLayoutResult): Float {
    if (layout.lineCount <= 0) return layout.size.width / 2f
    var left = Float.MAX_VALUE
    var right = 0f
    for (line in 0 until layout.lineCount) {
        val lineLeft = layout.getLineLeft(line)
        val lineRight = layout.getLineRight(line)
        if (lineLeft < left) left = lineLeft
        if (lineRight > right) right = lineRight
    }
    if (right <= left) return layout.size.width / 2f
    return (left + right) / 2f
}
