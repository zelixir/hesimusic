package com.zjr.hesimusic.loop

import androidx.media3.common.Player

/**
 * 显式意图的退出决策(纯函数,无状态、无播放副作用)。
 *
 * 只有两条规则会退出无缝:
 * 1. 切到别的歌(含当前歌变为 null)→ 退出并恢复进入前的 repeat([shouldExitOnSongChange]
 *    + [repeatToRestore]);
 * 2. 用户把 repeat 改成非 ONE → 退出并清空意图([shouldClearOnRepeatChange])。
 *
 * 关键约定:**新歌 == 武装歌时永不退出** —— 进入武装歌本身(显式入口先 arm 再 play)
 * 会触发一次 transition,那次必须保留意图,否则无缝永远无法生效。
 */
object LoopIntentDecision {

    /**
     * 目标歌是否已离开武装歌。
     *
     * armed == null → 没有意图,不退出;
     * newSongId == armed.songId → 仍在武装歌上(含"刚切进武装歌"),不退出;
     * 其余(别的歌 / null)→ 退出。
     */
    fun shouldExitOnSongChange(armed: LoopIntent.Armed?, newSongId: Long?): Boolean =
        armed != null && newSongId != armed.songId

    /**
     * 退出时需要写回的 repeat 模式。
     *
     * - 不退出(见 [shouldExitOnSongChange])→ null(保持现状);
     * - 退出但 prevRepeat 与当前 repeat 相同 → null(无需写,避免无意义的播放器事件);
     * - 否则 → armed.previousRepeatMode。
     *
     * 注意:返回 null **不代表**不退出。是否清空意图只看 [shouldExitOnSongChange],
     * 调用方必须先清意图、再按本函数的非空返回值写 repeat(顺序见协调器注释)。
     */
    fun repeatToRestore(
        armed: LoopIntent.Armed?,
        newSongId: Long?,
        currentRepeatMode: Int,
    ): Int? {
        if (!shouldExitOnSongChange(armed, newSongId)) return null
        val previous = armed!!.previousRepeatMode
        return previous.takeIf { it != currentRepeatMode }
    }

    /**
     * repeat 事件是否应视为「用户手动把 repeat 改成非 ONE」并清空意图。
     *
     * 判定依据是**持久化偏好**:用户改 repeat 走 MusicViewModel.setRepeatMode,
     * 会同步把偏好写成同一个值;而队列播放的内部临时改写(MusicViewModel
     * applyQueuePlaybackOverridesIfNeeded:REPEAT_ONE → ALL)只动播放器、不写偏好。
     * 因此:
     * - repeat 变成非 ONE 且 persisted == 新值 → 用户手动改 → 清意图(切回 ONE 也不会自动恢复);
     * - repeat 变成非 ONE 但 persisted 仍是旧值 → 内部临时改写 → 保留意图(窗口本来就会因
     *   repeat != ONE 而置空,临时改写结束后 repeat 恢复 ONE 时无缝自然回来)。
     *
     * repeat == ONE / 未武装 → false(不退出)。
     */
    fun shouldClearOnRepeatChange(
        armed: LoopIntent.Armed?,
        newRepeatMode: Int,
        persistedRepeatMode: Int,
    ): Boolean {
        if (armed == null) return false
        if (newRepeatMode == Player.REPEAT_MODE_ONE) return false
        return persistedRepeatMode == newRepeatMode
    }
}
