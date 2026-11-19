package com.zjr.hesimusic.loop.audio.source

import android.content.Context
import android.provider.Settings
import android.util.Log

/**
 * 循环实现路线开关。
 *
 * - [M3_PRIME]：现有 M3′（LoopAudioSink 捕获 + 重放 + PTS 改写），默认，保持现网行为；
 * - [SOURCE]：循环 PCM 数据源（LoopingPcmMediaSource + 块缓存），预览页与正式播放
 *   在支持的模式下走该路径。
 *
 * 默认 M3_PRIME。切换是**会话级**的：预览播放器在创建时读取；主播放器在 ExoPlayer
 * 构建时读取。运行期可以写 [current]（debug/测试用），已经创建的播放器不会自动换 sink。
 *
 * 真机验证开关（不新增 UI，不改 debug 源集）：
 * ```
 * adb shell settings put global hesi_loop_source_mode 1   # SOURCE
 * adb shell settings put global hesi_loop_source_mode 0   # M3′（默认）
 * ```
 * 预览播放器在打开结果页时读取；主播放器在进程启动时读取。
 */
enum class LoopSourceMode {
    M3_PRIME,
    SOURCE,
    ;

    companion object {
        private const val TAG = "LoopSourceMode"
        private const val SETTING_KEY = "hesi_loop_source_mode"

        @Volatile
        var current: LoopSourceMode = SOURCE

        /**
         * 调试/测试用的强制覆盖:非 null 时**优先于**全局 setting。
         *
         * 真机上(部分 ROM)adb 没有 WRITE_SECURE_SETTINGS,改不了 global setting,
         * 只能靠进程内覆盖来验证 SOURCE 路径。
         */
        @Volatile
        var debugOverride: LoopSourceMode? = null

        fun isSource(): Boolean = current == SOURCE

        /** 缺省引擎（无用户偏好/无 global setting）：环带 SOURCE。 */
        fun defaultMode(): LoopSourceMode = SOURCE

        /** 纯函数：prefs 原始值 -> 引擎；null/未知均回默认 SOURCE。 */
        fun fromPreference(raw: String?): LoopSourceMode = when (raw) {
            "m3" -> M3_PRIME
            "source" -> SOURCE
            else -> defaultMode()
        }

        /** 读全局 setting（0/1）；没有设置时保持默认 M3′。 */
        private const val PREFS = "loop_settings"
        private const val PREF_KEY = "loop_engine"

        /** 用户在结果页选的引擎(持久化)。没有选过时返回 null。 */
        fun saved(context: Context): LoopSourceMode? {
            val raw = try {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(PREF_KEY, null)
            } catch (t: Throwable) {
                null
            }
            if (raw == null) return null
            return fromPreference(raw)
        }

        /** 用户在结果页切换引擎:写偏好并立即生效(重新进页生效)。 */
        fun select(context: Context, mode: LoopSourceMode) {
            try {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(PREF_KEY, if (mode == SOURCE) "source" else "m3").apply()
            } catch (t: Throwable) {
                Log.w(TAG, "save engine preference failed", t)
            }
            current = mode
            Log.i(TAG, "loop source mode -> " + mode + " (user)")
        }

        fun refreshFromSettings(context: Context) {
            debugOverride?.let { forced ->
                current = forced
                return
            }
            saved(context)?.let { chosen ->
                current = chosen
                return
            }
            // 缺省/未设置走 SOURCE；显式写 0 才回到老引擎(回退路径)。
            val raw = try {
                Settings.Global.getString(context.contentResolver, SETTING_KEY)
            } catch (t: Throwable) {
                Log.w(TAG, "read $SETTING_KEY failed", t)
                current = defaultMode()
                return
            }
            val next = when (raw) {
                "0" -> M3_PRIME
                null -> defaultMode()
                else -> SOURCE
            }
            if (next != current) {
                Log.i(TAG, "loop source mode -> $next (global $SETTING_KEY=$raw)")
            }
            current = next
        }
    }
}

/** 标记“这个 MediaItem 由循环 PCM 数据源产生”，供 LoopSessionPlayer 识别。 */
object LoopSourceMediaTag

/**
 * 正式播放用 tag：MediaSourceFactory 据此只替换当前 item，不全局改队列。
 * 文件路径/窗口都在 tag 里，factory 可为该 item 构建 LoopingPcmExtractorMediaSource。
 */
data class LoopSourceItemTag(
    val songId: Long,
    val loopStartUs: Long,
    val loopEndUs: Long,
    val filePath: String,
)
