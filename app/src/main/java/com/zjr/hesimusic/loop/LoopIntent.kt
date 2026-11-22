package com.zjr.hesimusic.loop

import android.content.Context
import android.content.SharedPreferences
import androidx.media3.common.Player
import com.zjr.hesimusic.data.model.Song
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
     * 00029：一次「启动无缝循环」的入口正在准备（环带源尚未 READY）。
     *
     * 产品语义（用户裁决）：点无缝循环后**立刻**不再播旧位置内容，源就绪后从 0 起播。
     * 因此 UI 在 [armEntry] 之前就发出 pause、之后立刻 seek 0，这段时间播放器是暂停的；
     * 协调器在源 READY 时必须知道"这是入口、要自动起播"（不能把它当用户暂停）。
     *
     * 非持久化：只在本次进入的几百毫秒内存在，进程重启的 restore 不会带上它。
     */
    private val _entryPendingSongId = MutableStateFlow<Long?>(null)

    /** 正在准备的入口目标歌；null = 没有入口在准备。 */
    val entryPendingSongId: StateFlow<Long?> = _entryPendingSongId.asStateFlow()

    /**
     * 00029：本次入口是否跳过协调器的 ENTRY_UI_SETTLE_MS 让帧。
     *
     * 结果页「确认并保存」后页面立刻 pop，主线程被 pop/重组占住，16ms 的 delay 实际
     * 会等约 140ms（1333 trace 实测 142ms），纯属入口静音；没有旧位置 UI 需要保护。
     * 播放页可见时仍需要这一帧把「正在进入」画出来，保持 false。
     */
    @Volatile
    private var entrySkipUiSettle: Boolean = false

    /** 入口歌对象（路径/时长）由 UI 直接传来，省掉协调器入口上的 Room 查询。 */
    @Volatile
    private var entrySong: Song? = null

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
        // 非入口 arm（debug /loop/enter 等）没有"准备中"语义：清掉上一次的入口标记。
        _entryPendingSongId.value = null
        entrySkipUiSettle = false
        entrySong = null
        applyArmed(songId, previousRepeatMode, previousShuffleEnabled)
    }

    /**
     * 00029：UI「菜单·无缝循环 / 结果页·确认并保存」的唯一进入点。
     *
     * 与 [arm] 的区别只是多一个**非持久化**的入口标记；UI 随后必须立刻 pause + seek 0，
     * 让旧位置的内容在源重建前就停声。协调器在环带源 READY 后清标记并起播。
     *
     * **顺序很重要**：[armed] 的订阅者可能在 [applyArmed] 的同一帧内联重算窗口；
     * 入口标记必须先落值，否则第一次 refresh 会看到 entry=null 而走整表重建
     * （真机 1318：整表重建 231ms 把 pause 命令堵在主线程后面，旧位置又播了 ~300ms）。
     */
    fun armEntry(
        songId: Long,
        previousRepeatMode: Int,
        previousShuffleEnabled: Boolean,
        skipUiSettle: Boolean = false,
        song: Song? = null,
    ) {
        if (songId <= 0L) return
        _entryPendingSongId.value = songId
        entrySkipUiSettle = skipUiSettle
        entrySong = song
        applyArmed(songId, previousRepeatMode, previousShuffleEnabled)
    }

    /** 入口时 UI 手里的 Song；协调器用它填 songFor 缓存，避免入口 Room 查询。 */
    fun entrySongFor(songId: Long): Song? =
        entrySong?.takeIf { it.id == songId }

    /** 该入口是否跳过 UI 让帧（播放页不可见=true；播放页可见=false）。 */
    fun shouldSkipEntryUiSettle(songId: Long): Boolean =
        entrySkipUiSettle && _entryPendingSongId.value == songId

    private fun applyArmed(songId: Long, previousRepeatMode: Int, previousShuffleEnabled: Boolean) {
        val armed = Armed(
            songId = songId,
            previousRepeatMode = previousRepeatMode,
            previousShuffleEnabled = previousShuffleEnabled,
        )
        _armed.value = armed
        store.save(armed)
    }

    /** 入口准备结束（源已起播 / 回退 / 失败 / 超时）；songId 非空时只清该歌。 */
    fun clearEntry(songId: Long? = null) {
        if (songId == null || _entryPendingSongId.value == songId) {
            _entryPendingSongId.value = null
            entrySkipUiSettle = false
            entrySong = null
        }
    }

    /** 该歌的入口是否仍在准备。 */
    fun isEntryPendingFor(songId: Long?): Boolean =
        songId != null && songId > 0L && _entryPendingSongId.value == songId

    /** 清空意图(切歌退出 / 用户改 repeat 或 shuffle / 测试重置)。幂等。 */
    fun clear() {
        _entryPendingSongId.value = null
        entrySkipUiSettle = false
        entrySong = null
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
