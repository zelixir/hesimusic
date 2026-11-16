package com.zjr.hesimusic.loop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 无缝循环的**显式意图**(会话级,不持久化)。
 *
 * 语义(契约 §1/§2.4 的意图驱动版本):
 * - 只有用户在长按菜单点「无缝循环」或在分析页点「确认并保存」时才 [arm] —— 且只对**那一首歌**;
 * - 进入时记录当时的**两个**模式([Armed.previousRepeatMode] / [Armed.previousShuffleEnabled]);
 *   播放层随后在**唯一写点**同时应用 repeat=ONE 且 shuffle=false(菜单显示单曲循环,
 *   不会因为 shuffle 仍开着而显示成「随机播放」);
 * - 手动切到别的歌 → 播放层调用 [clear] 并恢复两个进入前的值;
 * - 用户把 repeat 改成非 ONE / 把 shuffle 改回来 → 播放层调用 [clear](被用户改的那个属性
 *   保留用户的新选择,另一个属性恢复进入前的值);
 * - 进程重启后 StateFlow 自然为空 → **不**自动无缝(C7 旧判据作废)。
 *
 * 本类无 DB / 无播放副作用,只持有意图;资格判定见 [LoopEligibility],
 * 进入/退出决策见 [LoopIntentDecision],落点见 service 层 LoopPlaybackCoordinator。
 */
@Singleton
class LoopIntent @Inject constructor() {

    /** 已武装的无缝意图:目标歌 + 进入前的 repeat / shuffle(退出时恢复)。 */
    data class Armed(
        val songId: Long,
        val previousRepeatMode: Int,
        val previousShuffleEnabled: Boolean,
    )

    private val _armed = MutableStateFlow<Armed?>(null)

    /** 当前武装意图;null = 没有显式进入过无缝(或已退出)。 */
    val armed: StateFlow<Armed?> = _armed.asStateFlow()

    /**
     * 为 [songId] 武装无缝意图,并记录进入前的 repeat / shuffle。
     *
     * 无效 songId(<= 0)直接忽略:不可能成为无缝目标,也不应留下脏意图。
     * 重复 arm 同一首/别的歌都只是**替换**意图(以最后一次显式进入为准)。
     */
    fun arm(songId: Long, previousRepeatMode: Int, previousShuffleEnabled: Boolean) {
        if (songId <= 0L) return
        _armed.value = Armed(
            songId = songId,
            previousRepeatMode = previousRepeatMode,
            previousShuffleEnabled = previousShuffleEnabled,
        )
    }

    /** 清空意图(切歌退出 / 用户改 repeat 或 shuffle / 测试重置)。幂等。 */
    fun clear() {
        _armed.value = null
    }
}
