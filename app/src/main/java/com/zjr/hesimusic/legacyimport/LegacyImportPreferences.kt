package com.zjr.hesimusic.legacyimport

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext

/**
 * 设置导入的**白名单**。
 *
 * 为什么不能照抄上一版备份的「整份 SharedPreferences」:
 * 1. `playback_prefs` 不只存设置,还存**播放状态**(队列 id、当前歌曲 id、播放位置、歌单上下文)。
 *    上一版的歌曲 id 与 v2 完全不同(两个 applicationId 各扫各的),把这些 id 搬过来 =
 *    一条指向不存在歌曲的队列;
 * 2. `loop_settings` 是循环引擎选择与"默认值已迁移"版本标记,属于另一条工作线
 *    (分析/引擎默认值),导入旧值会把引擎选择悄悄改回去;
 * 3. `loop_intent` / `loop_notices` 是一次性状态/已读标记,没有导入价值;
 * 4. 主题图片的 URI 如果是 SAF 的 `content://`,授权属于上一版的 UID,v2 打不开,
 *    只有绝对路径/`file://` 才导入。
 *
 * 因此:文件白名单 + 键黑名单 + 路径类键的取值校验,三者都在这里集中。
 */
internal object LegacyPreferencePolicy {

    /** 允许导入的设置文件(去掉 .xml 后缀)。 */
    val allowedFiles: Set<String> = setOf("playback_prefs", "scan_prefs", "sort_prefs")

    /** 播放状态键:属于"这一次播放的现场",不属于设置。 */
    private val blockedKeys: Set<String> = setOf(
        "queue_ids",
        "current_song_index",
        "current_song_id",
        "current_position",
        "playlist_type",
        "playlist_value",
        "state_version",
    )

    /** 取值必须是 v2 能直接打开的本机路径(SAF 授权无法跨应用继承)。 */
    private val pathKeys: Set<String> = setOf(
        "startup_image_uri",
        "list_background_image_uri",
    )

    fun normalizeFileName(raw: String): String = raw.trim().removeSuffix(".xml")

    fun filter(file: LegacyPreferenceFile): List<LegacyPreferenceEntry> {
        val name = normalizeFileName(file.name)
        if (name !in allowedFiles) return emptyList()
        return file.entries.filter { entry ->
            if (entry.key in blockedKeys) {
                false
            } else if (entry.key in pathKeys) {
                val value = entry.value
                value is String && (value.startsWith("/") || value.startsWith("file://"))
            } else {
                true
            }
        }
    }
}

/** 把过滤后的设置写进本机 SharedPreferences(合并写入,不清空其它键)。 */
internal class LegacyPreferenceWriter(@ApplicationContext private val context: Context) {

    /**
     * 本机是否已经是这个值(用于"重复导入 = 无操作"的幂等判定)。
     * 类型不匹配/读失败一律视为"不同",让写入流程自己兜底。
     */
    fun alreadyMatches(fileName: String, entry: LegacyPreferenceEntry): Boolean {
        val name = LegacyPreferencePolicy.normalizeFileName(fileName)
        if (name !in LegacyPreferencePolicy.allowedFiles) return false
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        if (!prefs.contains(entry.key)) return false
        return try {
            when (entry.type) {
                "string" -> prefs.getString(entry.key, null) == (entry.value as? String)
                "boolean" -> prefs.getBoolean(entry.key, false) == booleanValue(entry.value)
                "int" -> prefs.getInt(entry.key, 0) == (entry.value as? Number)?.toInt()
                "long" -> prefs.getLong(entry.key, 0L) == (entry.value as? Number)?.toLong()
                "float" -> prefs.getFloat(entry.key, 0f) == (entry.value as? Number)?.toFloat()
                "string_set" -> {
                    val items = (entry.value as? List<*>)?.mapNotNull { it?.toString() }?.toSet()
                    items != null && prefs.getStringSet(entry.key, emptySet()) == items
                }
                else -> false
            }
        } catch (e: ClassCastException) {
            false
        }
    }

    /** @return 实际写入的键值数。 */
    fun apply(files: List<LegacyPreferenceFile>): Int {
        var applied = 0
        for (file in files) {
            val name = LegacyPreferencePolicy.normalizeFileName(file.name)
            if (name !in LegacyPreferencePolicy.allowedFiles) continue
            val prefs: SharedPreferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val editor = prefs.edit()
            for (entry in file.entries) {
                if (put(editor, entry)) applied++
            }
            editor.apply()
        }
        return applied
    }

    private fun put(editor: SharedPreferences.Editor, entry: LegacyPreferenceEntry): Boolean {
        val value = entry.value
        return try {
            when (entry.type) {
                "string" -> {
                    editor.putString(entry.key, value?.toString() ?: return false)
                    true
                }
                "boolean" -> {
                    editor.putBoolean(entry.key, booleanValue(value) ?: return false)
                    true
                }
                "int" -> {
                    val parsed = (value as? Number)?.toInt() ?: (value as? String)?.toIntOrNull() ?: return false
                    editor.putInt(entry.key, parsed)
                    true
                }
                "long" -> {
                    val parsed = (value as? Number)?.toLong() ?: (value as? String)?.toLongOrNull() ?: return false
                    editor.putLong(entry.key, parsed)
                    true
                }
                "float" -> {
                    val parsed = (value as? Number)?.toFloat() ?: (value as? String)?.toFloatOrNull() ?: return false
                    editor.putFloat(entry.key, parsed)
                    true
                }
                "string_set" -> {
                    val items = (value as? List<*>)?.mapNotNull { it?.toString() } ?: return false
                    editor.putStringSet(entry.key, items.toSet())
                    true
                }
                else -> {
                    Log.w(TAG, "跳过未知设置类型: key=" + entry.key + " type=" + entry.type)
                    false
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "写入设置失败: key=" + entry.key, e)
            false
        }
    }

    private fun booleanValue(value: Any?): Boolean? = when (value) {
        is Boolean -> value
        is String -> value.toBooleanStrictOrNull()
        is Number -> value.toInt() != 0
        else -> null
    }

    private companion object {
        const val TAG = "LegacyImportPrefs"
    }
}
