package com.zjr.hesimusic.service

import androidx.media3.common.Player

/**
 * 队列首尾的手写绕回策略(A-085)。
 *
 * BasePlayer.getNextMediaItemIndex() 用 getRepeatModeForNavigation():它只把
 * REPEAT_ONE 降为 OFF,**保留 REPEAT_ALL**。因此 repeat=ALL 时最后一项的
 * `hasNextMediaItem()` 仍为 true,播放器自己会绕回;手写绕回分支在 ALL 下不可达,
 * 也不应该执行(否则会和播放器的绕回叠加)。
 *
 * 只有 repeat != ALL(尤其是 OFF)时,最后一项的 hasNext=false 才需要手写绕回。
 */
internal object MusicNavigationPolicy {

    fun shouldWrapToStart(hasNext: Boolean, repeatMode: Int, mediaItemCount: Int): Boolean =
        !hasNext && mediaItemCount > 0 && repeatMode != Player.REPEAT_MODE_ALL

    fun shouldWrapToEnd(hasPrevious: Boolean, repeatMode: Int, mediaItemCount: Int): Boolean =
        !hasPrevious && mediaItemCount > 0 && repeatMode != Player.REPEAT_MODE_ALL
}
