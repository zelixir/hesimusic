package com.zjr.hesimusic.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.zjr.hesimusic.loop.LoopPhase
import com.zjr.hesimusic.loop.LoopWindow
import com.zjr.hesimusic.ui.common.loopMarkerFractions

/**
 * 播放页的 A/B 色带（阶段感知版）。
 *
 * 与 ui/common 的共享标记层相比只多一件事：prelude/tail 阶段把循环段画成**虚线 + 低不透明度**，
 * 表示“循环段存在、但当前不在其中”；loop 阶段仍是实线高亮。几何/配色与共享层保持一致，
 * 避免同一个页面里出现两种 A/B 位置。
 *
 * 阶段由 [loopPhaseVisual] 唯一映射（纯函数，单测覆盖三种 phase 的样式）；
 * 窗口在 Canvas 的 drawScope 里读 State，窗口变化只重绘、不重组外层界面。
 */

/** A 刻度：琥珀色（与 ui/common 及分析页同值）。 */
private val MarkerAColor = Color(0xFFD29922)

/** B 刻度：红色（与 ui/common 及分析页同值）。 */
private val MarkerBColor = Color(0xFFF85149)

/** 循环区高亮：绿色（与 ui/common 同色系）。 */
private val LoopRegionColor = Color(0xFF3FB950)

/** prelude/tail 的虚线圈色（同一绿色，半透明）。 */
private val GhostOutlineColor = Color(0x733FB950)

/** Material3 Slider 手柄宽（与 ui/common 的几何口径一致）。 */
private val SliderHandleWidth = 4.dp

/** Material3 Slider 轨道高（与 ui/common 的几何口径一致）。 */
private val SliderTrackHeight = 16.dp

@Composable
internal fun LoopPhaseMarkerOverlay(
    window: State<LoopWindow?>,
    phase: LoopPhase?,
    durationMs: Long,
    modifier: Modifier = Modifier,
) {
    val visual = loopPhaseVisual(phase)
    if (visual.bandStyle == LoopBandStyle.NONE) return
    Canvas(modifier) {
        val fractions = loopMarkerFractions(window.value, durationMs) ?: return@Canvas
        val trackHeight = SliderTrackHeight.toPx().coerceAtMost(size.height)
        val insetPx = (SliderHandleWidth / 2).toPx()
        val bandTop = ((size.height - trackHeight) / 2f).coerceAtLeast(0f)
        val usableWidth = (size.width - insetPx * 2f).coerceAtLeast(0f)
        val xa = insetPx + usableWidth * fractions.start
        val xb = insetPx + usableWidth * fractions.end
        val bandWidth = (xb - xa).coerceAtLeast(0f)
        val radius = CornerRadius(trackHeight / 2f)

        // 循环段填充：loop=实线高亮，prelude/tail=低不透明度。
        drawRoundRect(
            color = LoopRegionColor.copy(alpha = visual.fillAlpha),
            topLeft = Offset(xa, bandTop),
            size = Size(bandWidth, trackHeight),
            cornerRadius = radius,
        )
        // prelude/tail 再加一圈虚线描边，避免“低不透明度”被看成渲染错误。
        if (visual.dashed) {
            drawRoundRect(
                color = GhostOutlineColor,
                topLeft = Offset(xa, bandTop),
                size = Size(bandWidth, trackHeight),
                cornerRadius = radius,
                style = Stroke(
                    width = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(
                        floatArrayOf(6.dp.toPx(), 4.dp.toPx()),
                    ),
                ),
            )
        }
        // A/B 刻度：非激活阶段降低不透明度。
        val tickWidth = 3.dp.toPx()
        drawRect(
            color = MarkerAColor.copy(alpha = visual.tickAlpha),
            topLeft = Offset(xa - tickWidth / 2f, bandTop),
            size = Size(tickWidth, trackHeight),
        )
        drawRect(
            color = MarkerBColor.copy(alpha = visual.tickAlpha),
            topLeft = Offset(xb - tickWidth / 2f, bandTop),
            size = Size(tickWidth, trackHeight),
        )
    }
}
