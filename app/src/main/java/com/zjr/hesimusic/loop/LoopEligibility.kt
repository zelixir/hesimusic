package com.zjr.hesimusic.loop

import androidx.media3.common.Player
import com.zjr.hesimusic.data.model.SongLoop
import com.zjr.hesimusic.service.LoopWindowRules

/**
 * 无缝资格判定:唯一的判定入口(纯函数),**显式意图驱动**。
 *
 * 以下条件全部满足才返回窗口:
 * 1. songId 有效(非 null 且 > 0);
 * 2. 显式意图已武装,且**武装的正是这首歌**([LoopIntent.Armed.songId] == songId);
 * 3. song_loop 行存在且**已确认**([isConfirmedForMainPlayback]):未确认的临时候选只是草稿,
 *    **只能在结果页预览试听**,绝不允许进入主播放的无缝循环;
 * 4. 当前 repeat 意图 == 单曲循环(用户进入时被设为 ONE,菜单显示的就是它);
 * 5. 由行算出的窗口通过 [LoopWindowRules] 的唯一合法性判据(含 500ms 下限与时长上界)。
 *
 * 与旧语义的差别:仅"行已确认 + repeat==ONE"**不再**自动无缝 —— 切到另一首已确认歌
 * (repeat 仍是 ONE)不会无缝,必须重新显式进入。意图在进程重启后由 [LoopIntent] 恢复,
 * 但只有本函数仍判定可用时才会真正恢复循环(见 LoopPlaybackCoordinator 的 restore 分支)。
 *
 * 约定:**行必须是当前歌的行**(调用方按 songId 查询),本函数不做 DB 查询。
 * 引擎不会因为本函数而修改 repeatMode —— 唯一的 repeat 写回是协调器里
 * "离开武装歌时恢复 prevRepeat"的显式动作(见 LoopIntentDecision)。
 */
object LoopEligibility {

    private const val US_PER_MS = 1_000L

    /**
     * 主播放的确认门（D13）：只有用户确认过的最终结果才能进入主播放无缝。
     *
     * "未确认候选不能直接循环"是**刻意**的产品区分，不是遗漏：
     * - 主播放（本函数 / LoopRuntime / claim）→ 必须 confirmed=true；
     * - 结果页预览试听（ui/loop 的 PreviewPlayer）→ 允许试听未确认候选，它不经过本函数。
     */
    fun isConfirmedForMainPlayback(row: SongLoop?): Boolean = row?.confirmed == true

    fun derive(
        songId: Long?,
        repeatMode: Int,
        row: SongLoop?,
        armedSongId: Long?,
        durationUs: Long = 0L,
    ): LoopWindow? {
        if (songId == null || songId <= 0L) return null
        if (armedSongId == null || armedSongId != songId) return null
        if (!isConfirmedForMainPlayback(row)) return null
        if (repeatMode != Player.REPEAT_MODE_ONE) return null

        val window = LoopWindow(
            songId = songId,
            loopStartUs = row!!.loopStartMs * US_PER_MS,
            loopEndUs = row.loopEndMs * US_PER_MS,
        )
        // D14：唯一判据。1-sample / 499ms / B 越界的历史行在这里与 claim/fallback 同口径拒绝。
        return LoopWindowRules.writable(window, durationUs)
    }
}
