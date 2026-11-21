package com.zjr.hesimusic.ui.player

import com.zjr.hesimusic.loop.LoopPhase

/**
 * 播放页“当前处于无缝循环的哪个阶段”的**纯展示映射**（D05/D10 后续体验项）。
 *
 * 背景：进了 prelude/tail 后，播放头会落在 A..B 色带之外；如果状态行/色带仍显示成
 * “正在循环”，用户会以为进度条坏了。这里把 /state.loop.phase 的 prelude/loop/tail
 * 映射成：
 * - 状态行文案（前奏 / 中 / 尾奏）；
 * - 色带是否激活（prelude/tail = 虚线 + 低不透明度；loop = 实线高亮）。
 *
 * 纯函数、无 Compose/Android 依赖；PlayerScreen 与色带都只读这一份映射。
 */

internal enum class LoopBandStyle {
    /** 没有可画的循环段（普通播放 / claim 未生效）。 */
    NONE,

    /** loop 阶段：实线高亮。 */
    ACTIVE,

    /** prelude / tail：虚线 + 降低不透明度（“循环段当前未激活”）。 */
    GHOST,
}

internal data class LoopPhaseVisual(
    val bandStyle: LoopBandStyle,
    /** 循环段填充不透明度（0..1）；NONE 时无意义。 */
    val fillAlpha: Float,
    /** A/B 刻度不透明度（0..1）；NONE 时无意义。 */
    val tickAlpha: Float,
    /** 循环段是否用虚线描边。 */
    val dashed: Boolean,
    /** 状态行里的阶段词；loop 不写。 */
    val phaseLabel: String?,
)

internal fun loopPhaseVisual(phase: LoopPhase?): LoopPhaseVisual = when (phase) {
    LoopPhase.PRELUDE -> LoopPhaseVisual(
        bandStyle = LoopBandStyle.GHOST,
        fillAlpha = 0.08f,
        tickAlpha = 0.35f,
        dashed = true,
        phaseLabel = "前奏",
    )

    LoopPhase.LOOP -> LoopPhaseVisual(
        bandStyle = LoopBandStyle.ACTIVE,
        fillAlpha = 0.20f,
        tickAlpha = 1f,
        dashed = false,
        phaseLabel = null,
    )

    LoopPhase.TAIL -> LoopPhaseVisual(
        bandStyle = LoopBandStyle.GHOST,
        fillAlpha = 0.08f,
        tickAlpha = 0.35f,
        dashed = true,
        phaseLabel = "尾奏",
    )

    null -> LoopPhaseVisual(
        bandStyle = LoopBandStyle.NONE,
        fillAlpha = 0f,
        tickAlpha = 0f,
        dashed = false,
        phaseLabel = null,
    )
}

/**
 * 状态行文案。
 *
 * - prelude: "无缝循环中 · 前奏 · 00:23–04:21"
 * - loop:    "无缝循环中 · 00:23–04:21"
 * - tail:    "无缝循环中 · 尾奏 · 00:23–04:21"
 * - 没有可画的窗口（claim 未生效/普通播放）: "无缝循环未生效"
 */
internal fun loopPhaseStatusText(phase: LoopPhase?, rangeLabel: String?): String {
    if (rangeLabel.isNullOrBlank()) return "无缝循环未生效"
    return when (phase) {
        LoopPhase.PRELUDE -> "无缝循环中 · 前奏 · " + rangeLabel
        LoopPhase.TAIL -> "无缝循环中 · 尾奏 · " + rangeLabel
        else -> "无缝循环中 · " + rangeLabel
    }
}
