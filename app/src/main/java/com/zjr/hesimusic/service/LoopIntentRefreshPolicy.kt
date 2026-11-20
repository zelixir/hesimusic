package com.zjr.hesimusic.service

import androidx.media3.common.Player

/**
 * LoopPlaybackCoordinator 的播放器事件过滤(A-016)。
 *
 * `setMediaItems` **每次**都会派发 `onMediaItemTransition(reason=PLAYLIST_CHANGED)`
 * (ExoPlayerImpl 的窗口 uid 变化分派),所以旧注释"重设队列不会触发 transition"是错的,
 * 不能把"没有事件"当作 settle 的前提。
 *
 * 只有 AUTO / REPEAT / SEEK 代表当前 item 真的换了;PLAYLIST_CHANGED 只在
 * `currentMediaItem` 由空变非空时才是有意义的补算信号(冷启动 restore / 先 arm 后灌队列)。
 */
internal object LoopIntentRefreshPolicy {

    /** 该 transition 是否值得触发一次窗口重算。 */
    fun shouldRefreshOnTransition(
        reason: Int,
        previousItemPresent: Boolean,
        currentItemPresent: Boolean,
    ): Boolean = when (reason) {
        Player.MEDIA_ITEM_TRANSITION_REASON_AUTO,
        Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT,
        Player.MEDIA_ITEM_TRANSITION_REASON_SEEK,
        -> currentItemPresent

        Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED ->
            !previousItemPresent && currentItemPresent

        else -> false
    }

    /**
     * 是否需要启动兜底补算:只有当前 item 还为空时才值得等
     * (settle 的前提是"currentMediaItem 由空变非空",不是"队列没变所以没事件")。
     */
    fun shouldStartSettle(currentItemMissing: Boolean): Boolean = currentItemMissing

    /** 单次 settle 尝试:item 已出现才 refresh,否则继续等。 */
    fun shouldAttemptSettle(currentItemPresent: Boolean): Boolean = currentItemPresent
}
