package com.zjr.hesimusic.loop

import androidx.media3.common.Player
import com.zjr.hesimusic.data.model.SongLoop

/**
 * 无缝资格判定:唯一的判定入口(纯函数)。
 *
 * 以下条件全部满足才返回窗口:
 * 1. songId 有效(非 null 且 > 0);
 * 2. song_loop 行存在;
 * 3. 行已确认(confirmed = true);
 * 4. 用户 repeat 意图 == 单曲循环;
 * 5. 由行算出的窗口合法([LoopWindow.isValid])。
 *
 * 约定:**行必须是当前歌的行**(调用方按 songId 查询),本函数不做 DB 查询。
 * 引擎永远不会因为本函数而修改 repeatMode —— repeat 只代表用户意图。
 */
object LoopEligibility {

    private const val US_PER_MS = 1_000L

    fun derive(songId: Long?, repeatMode: Int, row: SongLoop?): LoopWindow? {
        if (songId == null || songId <= 0L) return null
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
