package com.zjr.hesimusic.loop

import android.content.Context
import android.content.SharedPreferences
import androidx.media3.common.Player
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 无缝循环的**显式意图**（D16 起持久化到 SharedPreferences）。
 *
 * 语义（契约 §1/§2.4 的意图驱动版本）：
 * - 只有用户在长按菜单点「无缝循环」或在分析页点「确认并保存」时才 [arm] —— 且只对**那一首歌**;
 * - 进入时记录当时的**两个**模式（[Armed.previousRepeatMode] / [Armed.previousShuffleEnabled]）；
 *   播放层随后在**唯一写点**同时应用 repeat=ONE 且 shuffle=false，并把这些值写进持久化偏好
 *   （[LoopModePreference.Entered]），保证 UI 显示 / 播放器 / 偏好三者一致；
 * - 手动切到别的歌 → 播放层调用 [clear] 并恢复两个进入前的值（连同偏好）；
 * - 用户把 repeat 改成非 ONE / 把 shuffle 改回来 → 播放层调用 [clear]（被用户改的那个属性
 *   保留用户的新选择，另一个属性恢复进入前的值）；
 * - **进程重启**：意图从偏好读回（[bindPersistence]）。这是 D07 的产品决定——
 *   "上次在循环，回来还在循环更自然"；但恢复**必须**通过协调器的完整一致性校验
 *   （当前歌 == 武装歌、行已确认、窗口合法、repeat == ONE），任一不满足就显式清意图 +
 *   恢复进入前模式 + 发布 restore-unavailable 通知，绝不出现"显示循环但实际没循环"。
 *
 * 本类无 DB / 无播放副作用，只持有意图；资格判定见 [LoopEligibility]，
 * 进入/退出决策见 [LoopIntentDecision]，落点见 service 层 LoopPlaybackCoordinator。
 */
@Singleton
class LoopIntent @Inject constructor() {

    /** 持久化后端；未注入时（纯 JVM 单测）用 [Store.None]，行为与旧会话级意图一致。 */
    internal interface Store {
        fun load(): Armed?

        fun save(armed: Armed?)

        object None : Store {
            override fun load(): Armed? = null

            override fun save(armed: Armed?) = Unit
        }
    }

    /** 已武装的无缝意图:目标歌 + 进入前的 repeat / shuffle(退出时恢复)。 */
    data class Armed(
        val songId: Long,
        val previousRepeatMode: Int,
        val previousShuffleEnabled: Boolean,
    )

    private var store: Store = Store.None

    private val _armed = MutableStateFlow<Armed?>(null)

    /** 当前武装意图;null = 没有显式进入过无缝(或已退出)。 */
    val armed: StateFlow<Armed?> = _armed.asStateFlow()

    /**
     * Hilt 成员注入：安装 SharedPreferences 后端并读回上次的意图。
     *
     * 由 Hilt 在构造后、首次使用前调用（框架用，业务代码不要调）。
     * 纯 JVM 单测通过 [LoopIntent] 的无参/Store 构造，不经过这里。
     */
    @Inject
    fun bindPersistence(@ApplicationContext context: Context) {
        val shared = SharedPreferencesStore(
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        )
        store = shared
        if (_armed.value == null) {
            store.load()?.let { _armed.value = it }
        }
    }

    /** 单测用：注入假存储并在构造时读回（模拟进程重启）。 */
    internal constructor(store: Store) : this() {
        this.store = store
        store.load()?.let { _armed.value = it }
    }

    /**
     * 为 [songId] 武装无缝意图,并记录进入前的 repeat / shuffle。
     *
     * 无效 songId(<= 0)直接忽略:不可能成为无缝目标,也不应留下脏意图。
     * 重复 arm 同一首/别的歌都只是**替换**意图(以最后一次显式进入为准)。
     */
    fun arm(songId: Long, previousRepeatMode: Int, previousShuffleEnabled: Boolean) {
        if (songId <= 0L) return
        val armed = Armed(
            songId = songId,
            previousRepeatMode = previousRepeatMode,
            previousShuffleEnabled = previousShuffleEnabled,
        )
        _armed.value = armed
        store.save(armed)
    }

    /** 清空意图(切歌退出 / 用户改 repeat 或 shuffle / 测试重置)。幂等。 */
    fun clear() {
        if (_armed.value == null) return
        _armed.value = null
        store.save(null)
    }

    private companion object {
        const val PREFS_NAME = "loop_intent"
        const val KEY_SONG_ID = "armed_song_id"
        const val KEY_PREVIOUS_REPEAT = "previous_repeat_mode"
        const val KEY_PREVIOUS_SHUFFLE = "previous_shuffle_enabled"
    }

    /** 真实设备上的持久化实现；文件小、写入都是单 key，apply() 足够。 */
    private class SharedPreferencesStore(
        private val prefs: SharedPreferences,
    ) : Store {
        override fun load(): Armed? {
            val songId = prefs.getLong(KEY_SONG_ID, -1L)
            if (songId <= 0L) return null
            return Armed(
                songId = songId,
                previousRepeatMode = prefs.getInt(KEY_PREVIOUS_REPEAT, Player.REPEAT_MODE_ALL),
                previousShuffleEnabled = prefs.getBoolean(KEY_PREVIOUS_SHUFFLE, false),
            )
        }

        override fun save(armed: Armed?) {
            val editor = prefs.edit()
            if (armed == null) {
                editor.remove(KEY_SONG_ID)
                    .remove(KEY_PREVIOUS_REPEAT)
                    .remove(KEY_PREVIOUS_SHUFFLE)
            } else {
                editor.putLong(KEY_SONG_ID, armed.songId)
                    .putInt(KEY_PREVIOUS_REPEAT, armed.previousRepeatMode)
                    .putBoolean(KEY_PREVIOUS_SHUFFLE, armed.previousShuffleEnabled)
            }
            editor.apply()
        }
    }
}

/**
 * D16：进入/退出无缝循环时**持久化偏好**必须写入的模式。
 *
 * 进入 = 单曲循环 + 关随机（与播放页/底部条的显示一致）；退出 = 进入前的模式。
 * 协调器、debug /loop/enter、/confirm 都写这一份，避免"UI 一条路、调试口另一条路"。
 */
object LoopModePreference {

    data class Mode(
        val repeatMode: Int,
        val shuffleEnabled: Boolean,
    )

    /** 循环期间的真实模式：单曲循环 + 关随机。 */
    val Entered: Mode = Mode(Player.REPEAT_MODE_ONE, shuffleEnabled = false)

    /** 退出后应恢复的模式（来自进入前记录）。 */
    fun restored(armed: LoopIntent.Armed): Mode =
        Mode(armed.previousRepeatMode, armed.previousShuffleEnabled)
}
