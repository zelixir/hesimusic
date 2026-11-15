package com.zjr.hesimusic.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import com.zjr.hesimusic.loop.LoopWindow

/**
 * 无缝循环 A/B 标记的绘制层(底部播放条细进度条 / 正在播放页全宽滑条共用)。
 *
 * 设计要点:
 * 1. **只在绘制阶段读 State**:窗口值在 Canvas 的 DrawScope 里读,窗口变化只触发重绘,
 *    不会重组外层界面;位置每秒变化的重组路径完全不经过这里;
 * 2. **未命中不画**:窗口为 null(普通播放 / 窗口属于别的歌)时整层不画任何像素;
 * 3. 配色与分析页(LoopScreen)保持一致:A 琥珀 / B 红 / 循环区绿。
 *
 * 调用方应传 MusicViewModel.activeLoopWindow(已按当前歌过滤)。
 */

/** A 刻度:琥珀色(与分析页 MarkerAColor 同值)。 */
private val MarkerAColor = Color(0xFFD29922)

/** B 刻度:红色(与分析页 MarkerBColor 同值)。 */
private val MarkerBColor = Color(0xFFF85149)

/** 循环区高亮:半透明绿(与分析页 LoopRegionFill 同色系,略提高不透明度以便压在进度填充上看清)。 */
private val LoopRegionFill = Color(0x333FB950)

/** Material3 Slider 手柄宽(HandleWidth = 4.dp),轨道两端各内缩其一半。 */
private val SliderHandleWidth = 4.dp

/** Material3 Slider 轨道高(TrackHeight = 16.dp),在滑条内垂直居中。 */
private val SliderTrackHeight = 16.dp

/**
 * 底部播放条(2dp 细进度条)上的标记层。
 *
 * 直接盖在进度线所在区域上:整条线高度即标记带高度,所以 A/B 刻度就是两个竖向色块。
 */
@Composable
fun LoopBarMarkerOverlay(
    window: State<LoopWindow?>,
    durationMs: Long,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val fractions = loopMarkerFractions(window.value, durationMs) ?: return@Canvas
        drawLoopMarkerLayer(
            fractions = fractions,
            insetPx = 0f,
            bandTop = 0f,
            bandHeight = size.height,
            tickWidth = 2.dp.toPx(),
        )
    }
}

/**
 * 正在播放页(Material3 全宽 Slider)上的标记层。
 *
 * 几何对齐 Material3 Slider(material3 1.3.0):
 * - 滑条总宽 = 轨道宽 + 手柄宽,轨道两端各内缩半个手柄宽(HandleWidth = 4.dp → 2.dp);
 * - 轨道高 16.dp,在滑条内垂直居中(滑条最小高度 48.dp)。
 * 标记层与 Slider 同尺寸叠放,按同样的内缩/垂直位置绘制才能与轨道严格对齐。
 */
@Composable
fun LoopSliderMarkerOverlay(
    window: State<LoopWindow?>,
    durationMs: Long,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val fractions = loopMarkerFractions(window.value, durationMs) ?: return@Canvas
        val trackHeight = SliderTrackHeight.toPx().coerceAtMost(size.height)
        drawLoopMarkerLayer(
            fractions = fractions,
            insetPx = (SliderHandleWidth / 2).toPx(),
            bandTop = ((size.height - trackHeight) / 2f).coerceAtLeast(0f),
            bandHeight = trackHeight,
            tickWidth = 3.dp.toPx(),
        )
    }
}

/**
 * 画一层标记:循环区半透明高亮 + A/B 两根刻度。
 *
 * @param insetPx 进度轨道两端的内缩(像素)
 * @param bandTop 标记带顶部 y(像素)
 * @param bandHeight 标记带高度(像素)
 * @param tickWidth A/B 刻度线宽(像素)
 */
private fun DrawScope.drawLoopMarkerLayer(
    fractions: LoopMarkerFractions,
    insetPx: Float,
    bandTop: Float,
    bandHeight: Float,
    tickWidth: Float,
) {
    val usableWidth = (size.width - insetPx * 2f).coerceAtLeast(0f)
    val xa = insetPx + usableWidth * fractions.start
    val xb = insetPx + usableWidth * fractions.end
    val bandWidth = (xb - xa).coerceAtLeast(0f)

    // 循环区高亮(先画,压在刻度下面)
    drawRoundRect(
        color = LoopRegionFill,
        topLeft = Offset(xa, bandTop),
        size = Size(bandWidth, bandHeight),
        cornerRadius = CornerRadius(bandHeight / 2f),
    )
    // A 刻度(琥珀)
    drawRect(
        color = MarkerAColor,
        topLeft = Offset(xa - tickWidth / 2f, bandTop),
        size = Size(tickWidth, bandHeight),
    )
    // B 刻度(红)
    drawRect(
        color = MarkerBColor,
        topLeft = Offset(xb - tickWidth / 2f, bandTop),
        size = Size(tickWidth, bandHeight),
    )
}
