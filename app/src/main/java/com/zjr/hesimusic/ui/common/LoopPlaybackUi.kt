package com.zjr.hesimusic.ui.common

import com.zjr.hesimusic.loop.LoopPosition
import com.zjr.hesimusic.loop.LoopWindow

/**
 * 播放界面(底部播放条 / 正在播放页)与无缝循环窗口之间的**纯函数**。
 *
 * 只做算术:不查库、不碰播放器、不依赖 Android/Compose,便于单测。
 */

/** 毫秒转微秒前的安全上限,避免 Long 乘法溢出。 */
private const val MAX_FOLDABLE_POSITION_MS = Long.MAX_VALUE / 1000L

/**
 * 播放位置的**展示折返**(幂等)。
 *
 * 为什么需要:MediaSession 的 PlaybackState 大约 1s 才刷新一次,MediaController 在两次刷新之间
 * 会按 speed=1 本地外推位置,跨过 B 的瞬间 UI 可能看到位置冲出 [A, B)(≤1s 后回落)。
 * 这里把展示位置再折返一次:
 * - 服务层(`LoopSessionPlayer`)已折返过的位置传进来结果不变(**幂等**);
 * - 外推溢出的位置被拉回区间内;
 * - 窗口为 null(普通播放)或属于别的歌时原样透传。
 *
 * 口径:入参/出参都是**毫秒**(UI 口径),窗口是微秒,换算只发生在本函数内。
 * 不改变 duration,也不改变 seek 语义(seek 仍按展示口径下发,由服务层映射到引擎)。
 *
 * @param rawPositionMs 控制器给出的原始位置(毫秒)
 * @param window 当前无缝窗口;null = 普通播放
 * @param currentSongId 当前播放项对应的 songId(由 mediaId 映射而来);null = 无当前歌
 */
fun foldDisplayPositionMs(
    rawPositionMs: Long,
    window: LoopWindow?,
    currentSongId: Long?,
): Long {
    if (window == null || currentSongId == null) return rawPositionMs
    // 窗口属于别的歌:不是当前这首的无缝播放,不折返
    if (window.songId != currentSongId) return rawPositionMs
    // 超长位置原样透传,避免 ×1000 溢出成负数
    if (rawPositionMs > MAX_FOLDABLE_POSITION_MS || rawPositionMs < -MAX_FOLDABLE_POSITION_MS) {
        return rawPositionMs
    }
    return LoopPosition.displayUs(rawPositionMs * 1000L, window) / 1000L
}

/** A/B 在进度条上的比例(0..1,半开区间 [start, end))。 */
data class LoopMarkerFractions(val start: Float, val end: Float)

/**
 * 由无缝窗口与**整曲时长**算出 A/B 在进度条上的比例。
 *
 * 窗口为 null / 非法 / 时长未知 / 区间退化 → 返回 null,调用方据此**完全不绘制**标记。
 */
fun loopMarkerFractions(window: LoopWindow?, durationMs: Long): LoopMarkerFractions? {
    if (window == null || !window.isValid() || durationMs <= 0L) return null
    val durationUs = durationMs * 1000.0
    val start = (window.loopStartUs / durationUs).coerceIn(0.0, 1.0)
    val end = (window.loopEndUs / durationUs).coerceIn(0.0, 1.0)
    if (end <= start) return null
    return LoopMarkerFractions(start.toFloat(), end.toFloat())
}
