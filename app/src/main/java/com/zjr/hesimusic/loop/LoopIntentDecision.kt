package com.zjr.hesimusic.loop

import androidx.media3.common.Player

/**
 * 显式意图的进入/退出决策(纯函数,无状态、无播放副作用)。
 *
 * 无缝进入时,播放层在唯一写点强制 **repeat=ONE + shuffle=false**(显示单曲循环,
 * 与实际一致);退出时按事件类型决定写回什么:
 *
 * 1. **切到别的歌**(含当前歌变为 null)→ 两个属性都恢复进入前的值
 *    ([modeToRestoreOnSongChange]);
 * 2. **用户把 repeat 改成非 ONE** → 退出;被用户改的 repeat **保留用户的新选择**,
 *    只把无缝强制关掉的 shuffle 恢复回进入前的值([modeToRestoreOnRepeatChange]);
 * 3. **用户改 shuffle** → 退出;shuffle 保留用户的新选择,只把无缝强制设成 ONE 的
 *    repeat 恢复回进入前的值([modeToRestoreOnShuffleChange])。
 *
 * 为什么 2/3 不把用户刚改的属性也恢复:那会立刻把用户刚按下的按钮弹回去
 * (例如用户从 ONE 切到 ALL,恢复 prevRepeat=OFF 会把 ALL 改回 OFF),与"用户意图优先"矛盾。
 *
 * 关键约定:**新歌 == 武装歌时永不退出** —— 进入武装歌本身(显式入口先 arm 再 play)
 * 会触发一次 transition,那次必须保留意图,否则无缝永远无法生效。
 */
object LoopIntentDecision {

    /** 某一时刻的播放模式:repeat + shuffle 两个用户意图。 */
    data class Mode(
        val repeatMode: Int,
        val shuffleEnabled: Boolean,
    )

    /**
     * 退出时要写回播放器的字段。
     *
     * 字段为 null = **不写**(保持当前值),用于"进入前的值恰好等于当前值"时避免多余事件;
     * 对象本身为 null(见各函数的返回值)= **不退出**。
     */
    data class ModeRestore(
        val repeatMode: Int? = null,
        val shuffleEnabled: Boolean? = null,
    ) {
        /** 两个字段都无需写(仍然退出,只是不需要动播放器)。 */
        val isEmpty: Boolean get() = repeatMode == null && shuffleEnabled == null
    }

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
     * 切歌退出:repeat 与 shuffle 都恢复进入前的值;不退出返回 null。
     *
     * 每个字段只在"进入前的值 != 当前值"时给出(否则为 null,不写)。
     */
    fun modeToRestoreOnSongChange(
        armed: LoopIntent.Armed?,
        newSongId: Long?,
        current: Mode,
    ): ModeRestore? {
        if (!shouldExitOnSongChange(armed, newSongId)) return null
        return ModeRestore(
            repeatMode = armed!!.previousRepeatMode.takeIf { it != current.repeatMode },
            shuffleEnabled = armed.previousShuffleEnabled.takeIf { it != current.shuffleEnabled },
        )
    }

    /**
     * repeat 事件是否应视为「用户手动把 repeat 改成非 ONE」并清空意图。
     *
     * 判定依据是**持久化偏好**:用户改 repeat 走 MusicViewModel.setRepeatMode,
     * 会同步把偏好写成同一个值;而队列播放的内部临时改写(MusicViewModel
     * applyQueuePlaybackOverridesIfNeeded:REPEAT_ONE → ALL)只动播放器、不写偏好。
     * 无缝进入写 ONE 时也**不写偏好**,所以不会把自己触发的回调当成用户操作。
     *
     * - repeat 变成非 ONE 且 persisted == 新值 → 用户手动改 → 清意图;
     * - repeat 变成非 ONE 但 persisted 仍是旧值 → 内部临时改写 → 保留意图;
     * - repeat == ONE / 未武装 → false(不退出)。
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

    /**
     * 用户改 repeat 退出:repeat 保留用户的新值(不写),只恢复被无缝强制关掉的 shuffle。
     *
     * 未武装 → null(不退出)。shuffle 已等于进入前的值 → shuffleEnabled=null(不写)。
     */
    fun modeToRestoreOnRepeatChange(
        armed: LoopIntent.Armed?,
        current: Mode,
    ): ModeRestore? {
        if (armed == null) return null
        return ModeRestore(
            repeatMode = null,
            shuffleEnabled = armed.previousShuffleEnabled.takeIf { it != current.shuffleEnabled },
        )
    }

    /**
     * shuffle 事件是否应视为「用户手动改 shuffle」并清空意图。
     *
     * 与 repeat 同理用持久化偏好区分:用户改 shuffle 走 MusicViewModel.setShuffleModeEnabled,
     * 会写偏好;而无缝进入写 false、退出写回 prevShuffle、队列临时改写都**不写偏好**,
     * 因此不会被误判成用户操作。
     *
     * - persisted == 新值 → 用户手动改 → 清意图;
     * - 否则 → 内部写入 → 保留意图;
     * - 未武装 → false。
     */
    fun shouldClearOnShuffleChange(
        armed: LoopIntent.Armed?,
        newShuffleEnabled: Boolean,
        persistedShuffleEnabled: Boolean,
    ): Boolean {
        if (armed == null) return false
        return persistedShuffleEnabled == newShuffleEnabled
    }

    /**
     * 用户改 shuffle 退出:shuffle 保留用户的新值(不写),只恢复被无缝强制设成 ONE 的 repeat。
     *
     * 未武装 → null(不退出)。repeat 已等于进入前的值 → repeatMode=null(不写)。
     */
    fun modeToRestoreOnShuffleChange(
        armed: LoopIntent.Armed?,
        current: Mode,
    ): ModeRestore? {
        if (armed == null) return null
        return ModeRestore(
            repeatMode = armed.previousRepeatMode.takeIf { it != current.repeatMode },
            shuffleEnabled = null,
        )
    }
}
