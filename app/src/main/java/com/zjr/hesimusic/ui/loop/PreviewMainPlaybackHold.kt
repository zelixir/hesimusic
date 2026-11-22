package com.zjr.hesimusic.ui.loop

import com.zjr.hesimusic.ui.loop.state.LoopPageState

/**
 * 分析/预览页与**主播放**之间的交接（#107 用户裁决 / 体验审计 D11）。
 *
 * 旧行为（D11 第 3 别扭点）：
 * - 一进分析页就 `musicViewModel.pause()`，正在听的歌立刻断；
 * - 离开页面无条件 `resume()`，用户进来前本来就是暂停的，出去反而自己播起来。
 *
 * 新裁决：
 * 1. **分析期间不碰主播放**（Loading / Analyzing / Failed）：从列表启动分析，主播放继续播；
 * 2. **真正进入结果页（Ready）才暂停**，让预览试听独占音频；暂停前记下主播放是否在播；
 * 3. **真正离开页面时按记录还原**：进入结果页前在播 → 恢复播放；本来就是暂停 → 保持暂停；
 * 4. 分析期间直接退出（没到 Ready）→ 从未接管过主播放，退出时什么都不做；
 * 5. 配置变更重建（KEEP_ALIVE）不会再次触发暂停，也不会提前恢复；
 * 6. Ready → Analyzing（页内「重新分析」）不释放接管记录：页面还在这首分析里，
 *    主播放保持暂停，最终离开时仍按**首次进入结果页前**的状态还原。
 *
 * 这只是一个纯状态记录器：不碰 Android/播放器，调用方根据返回值决定 pause()/resume()。
 */
class PreviewMainPlaybackHold {

    /**
     * null = 尚未接管主播放；
     * true / false = 首次进入结果页前，主播放是否正在播（离开时据此决定是否 resume）。
     */
    var heldWhilePlaying: Boolean? = null
        private set

    val isHolding: Boolean get() = heldWhilePlaying != null

    /**
     * 页面状态变化时调用（由 LoopScreen 在组合中驱动，含配置变更重建后的首帧）。
     *
     * @param mainPlaying 此刻主播放是否真的在播（暂停前读取）。
     * @return true = 调用方应执行一次 `MusicViewModel.pause()`。
     *   Loading/Analyzing/Failed 恒 false（分析不打断播放）；已经接管过也返回 false
     *   （重复组合/重建不重复暂停）。
     */
    fun onPageStateChanged(state: LoopPageState, mainPlaying: Boolean): Boolean {
        // 分析中/失败：绝不碰主播放（#107 判据 1/4）。
        if (state !is LoopPageState.Ready) return false
        // 已经接管：重建/重复 Ready 不再暂停（判据 2 只发生一次）。
        if (heldWhilePlaying != null) return false
        heldWhilePlaying = mainPlaying
        return true
    }

    /**
     * **真正离开页面**时调用一次（配置变更重建不算离开）。
     *
     * @return true = 调用方应恢复主播放（进入结果页前在播）；
     *   false = 保持暂停或从未接管（进入前本来就是暂停 / 分析中直接返回）。
     */
    fun onPageLeft(): Boolean {
        val wasPlaying = heldWhilePlaying ?: return false
        heldWhilePlaying = null
        return wasPlaying
    }
}
