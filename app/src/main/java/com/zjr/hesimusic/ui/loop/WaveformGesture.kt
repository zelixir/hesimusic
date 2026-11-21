package com.zjr.hesimusic.ui.loop

import kotlin.math.abs

/** 波形松手后的意图。 */
sealed interface WaveformRelease {
    /** 真正拖动过 A/B 手柄:松手提交窗口(CommitWindow)。 */
    data object Commit : WaveformRelease

    /** 没拖动过(含按点落在手柄命中区内的"点一下"):按松手位置 seek。 */
    data class Seek(val positionMs: Long) : WaveformRelease
}

/**
 * 波形手势的纯判定(可 JVM 单测)。
 *
 * 回归(issue 00023 用户报:点 A 点左边,指针跑到 B 点左侧):
 * 根因不是"目标映射用了 (target−A) mod L",而是按下点落在 A/B 手柄命中区(24dp)时,
 * 手势进入了"拖手柄"分支;但**手指没有移动**时既不会 DragAStart 也不会 DragAMove,
 * 松手却发了 CommitWindow → reducer 把位置退回"模式起点",于是:
 * - SEAM:模式起点 = B−5s → 指针出现在 **B 点左侧**;
 * - LOOP_ONLY:模式起点 = A−300ms → 指针跳到 A;
 * - FULL:模式起点 = 0 → 指针跳到 0。
 *
 * 规则:
 * - 按下→抬起之间没有超过拖拽阈值 → 一律当 seek(落点 = 抬起位置,手柄命中区内也一样);
 * - 真正拖动过 → 交回 Commit(原有 A/B 拖拽语义不变)。
 */
object WaveformGesture {

    /** A/B 手柄命中(A 优先,与旧的 nearHandle 一致)。返回 'A' / 'B' / null。 */
    fun handleAt(
        x: Float,
        width: Float,
        aMs: Long,
        bMs: Long,
        durationMs: Long,
        tolerancePx: Float,
    ): Char? {
        if (width <= 0f || durationMs <= 0L) return null
        val xa = aMs.toFloat() / durationMs * width
        val xb = bMs.toFloat() / durationMs * width
        return when {
            abs(x - xa) <= tolerancePx -> 'A'
            abs(x - xb) <= tolerancePx -> 'B'
            else -> null
        }
    }

    /** 触摸点 x → 展示位置(ms),钳在 [0, durationMs]。 */
    fun positionMsAt(x: Float, width: Float, durationMs: Long): Long {
        if (width <= 0f || durationMs <= 0L) return 0L
        val raw = x.toDouble() / width.toDouble() * durationMs.toDouble()
        return raw.toLong().coerceIn(0L, durationMs)
    }

    /**
     * 松手意图。
     *
     * @param dragged 本次手势是否真正拖动过(超过阈值)
     * @param upX 抬起点的 x(像素)
     */
    fun release(
        dragged: Boolean,
        upX: Float,
        width: Float,
        durationMs: Long,
    ): WaveformRelease =
        if (dragged) {
            WaveformRelease.Commit
        } else {
            WaveformRelease.Seek(positionMsAt(upX, width, durationMs))
        }
}
