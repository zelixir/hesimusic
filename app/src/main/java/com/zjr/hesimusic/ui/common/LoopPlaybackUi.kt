package com.zjr.hesimusic.ui.common

import com.zjr.hesimusic.loop.LoopWindow
import com.zjr.hesimusic.service.LoopClaim

/**
 * 播放界面(底部播放条 / 正在播放页)与无缝循环窗口之间的**纯函数**。
 *
 * 只做算术:不查库、不碰播放器、不依赖 Android/Compose,便于单测。
 */

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
 * @param sourceLoopActive 循环**数据源真的生效**了吗(00027)。
 *   false = 窗口被判定不可用 / SOURCE 构建失败 / 当前窗口没有真正接管:
 *   此时播放的是普通整曲,位置必须原样透传,否则会出现"进度条折回 A、耳朵在尾奏"。
 */
fun foldDisplayPositionMs(
    rawPositionMs: Long,
    window: LoopWindow?,
    currentSongId: Long?,
    sourceLoopActive: Boolean,
): Long = LoopClaim(
    songId = currentSongId,
    window = window,
    sourceActive = sourceLoopActive,
).displayPositionMs(rawPositionMs)

/**
 * 00027 单一事实源版本：折返只认 [LoopClaim]。
 *
 * 生产代码必须用这个重载；旧的 (window, songId, sourceLoopActive) 重载只为兼容既有单测，
 * 它无法表达 fallbackReason / C2 回退，容易再次漏判。
 */
fun foldDisplayPositionMs(rawPositionMs: Long, claim: LoopClaim): Long =
    claim.displayPositionMs(rawPositionMs)

/**
 * 当前**真正生效**的无缝窗口(00027)。
 *
 * 窗口本身由 LoopRuntime 按显式意图 + song_loop 行算出,它只代表"用户想要循环";
 * SOURCE 路线可能因为预算/格式/构建失败回退成普通整曲。UI 的 A/B 标记、循环区间样式、
 * 位置折返都必须看这个函数,而不是直接看 LoopRuntime.window,否则又会画出
 * "A 点进度 + 尾奏声音"。
 *
 * @param loopEngaged 播放管线真的在环回吗:
 *   - SOURCE 路线:= 源已安装且该窗口未被判为不可用;
 *   - M3′ 路线:= 窗口已写入音频层(环回在 sink 层,window 生效即真)。
 *   调用方负责把路线/源真值折算成这一个布尔(见 MusicViewModel)。
 * @return 真正生效的窗口;null = 不按循环形态展示
 */
fun effectiveLoopWindow(
    window: LoopWindow?,
    currentSongId: Long?,
    loopEngaged: Boolean,
): LoopWindow? = LoopClaim(
    songId = currentSongId,
    window = window,
    sourceActive = loopEngaged,
).effectiveWindow

/** 00027 单一事实源版本：A/B 标记只认 [LoopClaim]。 */
fun effectiveLoopWindow(claim: LoopClaim): LoopWindow? = claim.effectiveWindow

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
