package com.zjr.hesimusic.loop.audio.source

import android.content.Context
import android.util.Log

/**
 * 默认引擎迁移：2.3.x 之前老用户可能被旧开关写成
 * loop_engine_main=m3 / loop_engine=m3，只改默认值无法覆盖“键存在”的用户。
 *
 * 版本键保护：只迁移一次。版本 >= 2 后用户手动选择 m3 仍然被尊重。
 */
internal object LoopEngineDefaultsMigration {

    private const val TAG = "LoopSourceMainPlayer"
    private const val PREFS = "loop_settings"
    private const val KEY_VERSION = "loop_engine_default_version"
    private const val KEY_MAIN = "loop_engine_main"
    private const val KEY_ENGINE = "loop_engine"

    const val VERSION = 2

    data class Plan(
        val fromVersion: Int,
        val toVersion: Int,
        val mainEnabled: Boolean?,
        val engine: String?,
        val changed: Boolean,
    )

    /** 纯迁移计划；单测直接覆盖旧值/新值/空偏好/已迁移。 */
    fun plan(oldVersion: Int, mainRaw: String?, engineRaw: String?): Plan {
        if (oldVersion >= VERSION) {
            return Plan(oldVersion, oldVersion, null, null, false)
        }
        // 老版本无法区分“用户显式 m3”和旧默认 m3；一次性迁移统一到新默认。
        return Plan(oldVersion, VERSION, true, "source", true)
    }

    @Synchronized
    fun migrate(context: Context): Plan {
        val prefs = try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        } catch (t: Throwable) {
            Log.w(TAG, "migrate defaults: open prefs failed", t)
            return Plan(0, 0, null, null, false)
        }
        val oldVersion = try {
            prefs.getInt(KEY_VERSION, 0)
        } catch (_: Throwable) {
            0
        }
        val mainRaw = prefs.getString(KEY_MAIN, null)
        val engineRaw = prefs.getString(KEY_ENGINE, null)
        val plan = plan(oldVersion, mainRaw, engineRaw)
        if (!plan.changed) return plan
        try {
            prefs.edit()
                .putInt(KEY_VERSION, plan.toVersion)
                .putString(KEY_MAIN, "source")
                .putString(KEY_ENGINE, "source")
                .apply()
            Log.i(
                TAG,
                "migrate defaults from=" + plan.fromVersion +
                    " to=" + plan.toVersion +
                    " mainEnabled=true engine=source",
            )
        } catch (t: Throwable) {
            Log.w(TAG, "migrate defaults write failed", t)
        }
        return plan
    }
}
