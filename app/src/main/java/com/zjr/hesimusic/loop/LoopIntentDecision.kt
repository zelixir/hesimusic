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
 * 2. **用户把 repeat 改成非 ONE** → 退出;repeat 保留用户的新选择,且**不写 shuffle**
 *    (保持无缝期间的 false)。3 态按钮的语义是"单曲循环 → 列表循环",此时恢复
 *    prevShuffle 会把显示跳成「随机播放」,与用户刚按下的操作打架;
 * 3. **用户改 shuffle** → 退出;shuffle 保留用户的新选择,repeat 恢复进入前的值
 *    ([modeToRestoreOnShuffleChange]),并强制满足下面的不变量。
 *
 * **不变量(全局)**:`shuffle=true ⇒ repeat != ONE`。
 * UI 的显示优先级是 shuffle > repeat,两者同时成立时"显示 = 实际"会自相矛盾。
 * 因此任何把 shuffle 置 true 的退出路径都必须把 repeat 调整成非 ONE ——
 * [modeToRestoreOnShuffleChange] 在 prevRepeat == REPEAT_MODE_ONE 时改用 REPEAT_MODE_ALL
 * (协调器会打一条日志说明)。后续状态机(00014)也复用这条不变量。
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
     * 进入前的状态来自 UI 的正常模式组合(shuffle=true 时 repeat 一定不是 ONE),
     * 所以恢复后不变量 `shuffle=true ⇒ repeat != ONE` 自然成立。
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
     * 用户改 repeat 退出:**不写任何模式**。
     *
     * - repeat 保留用户刚选的新值(如 3 态按钮的「列表循环」= ALL);
     * - shuffle 保持无缝期间的 false,不恢复 prevShuffle —— 否则用户刚点「列表循环」,
     *   显示却跳成「随机播放」,与用户操作打架。
     *
     * 未武装 → null(不退出);武装 → 空 [ModeRestore](仍然退出,但不动播放器)。
     */
    fun modeToRestoreOnRepeatChange(armed: LoopIntent.Armed?): ModeRestore? {
        if (armed == null) return null
        return ModeRestore()
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
     * 用户改 shuffle 退出:shuffle 保留用户的新值(不写),repeat 恢复进入前的值。
     *
     * **不变量**:`shuffle=true ⇒ repeat != ONE`。当用户把 shuffle 打开且 prevRepeat 恰好是
     * REPEAT_MODE_ONE 时,恢复成 ONE 会违反不变量(UI 会同时是"随机"和"单曲"),此时改用
     * REPEAT_MODE_ALL(协调器会打日志说明这是不变量强制)。
     *
     * 未武装 → null(不退出)。repeat 已等于目标值 → repeatMode=null(不写)。
     */
    fun modeToRestoreOnShuffleChange(
        armed: LoopIntent.Armed?,
        current: Mode,
    ): ModeRestore? {
        if (armed == null) return null
        val previous = armed.previousRepeatMode
        val targetRepeat = if (current.shuffleEnabled && previous == Player.REPEAT_MODE_ONE) {
            // 不变量强制:shuffle=true 时不能是单曲循环。
            Player.REPEAT_MODE_ALL
        } else {
            previous
        }
        return ModeRestore(
            repeatMode = targetRepeat.takeIf { it != current.repeatMode },
            shuffleEnabled = null,
        )
    }
}
