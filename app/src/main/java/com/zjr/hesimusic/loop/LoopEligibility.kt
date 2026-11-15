package com.zjr.hesimusic.loop

import androidx.media3.common.Player
import com.zjr.hesimusic.data.model.SongLoop

/**
 * 无缝资格判定:唯一的判定入口(纯函数),**显式意图驱动**。
 *
 * 以下条件全部满足才返回窗口:
 * 1. songId 有效(非 null 且 > 0);
 * 2. 显式意图已武装,且**武装的正是这首歌**([LoopIntent.Armed.songId] == songId);
 * 3. song_loop 行存在且已确认(confirmed = true);
 * 4. 当前 repeat 意图 == 单曲循环(用户进入时被设为 ONE,菜单显示的就是它);
 * 5. 由行算出的窗口合法([LoopWindow.isValid])。
 *
 * 与旧语义的差别:仅"行已确认 + repeat==ONE"**不再**自动无缝 —— 切到另一首已确认歌
 * (repeat 仍是 ONE)不会无缝,必须重新显式进入。意图是会话级、不持久化。
 *
 * 约定:**行必须是当前歌的行**(调用方按 songId 查询),本函数不做 DB 查询。
 * 引擎不会因为本函数而修改 repeatMode —— 唯一的 repeat 写回是协调器里
 * "离开武装歌时恢复 prevRepeat"的显式动作(见 LoopIntentDecision)。
 */
object LoopEligibility {

    private const val US_PER_MS = 1_000L

    fun derive(
        songId: Long?,
        repeatMode: Int,
        row: SongLoop?,
        armedSongId: Long?,
    ): LoopWindow? {
        if (songId == null || songId <= 0L) return null
        if (armedSongId == null || armedSongId != songId) return null
        if (row == null || !row.confirmed) return null
        if (repeatMode != Player.REPEAT_MODE_ONE) return null

        val window = LoopWindow(
            songId = songId,
            loopStartUs = row.loopStartMs * US_PER_MS,
            loopEndUs = row.loopEndMs * US_PER_MS,
        )
        return window.takeIf { it.isValid() }
    }
}
