package com.zjr.hesimusic.data.preferences

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** 歌曲级列表的排序模式。 */
enum class SongSortMode(val label: String) {
    /** 按首字母分组排序（默认）。 */
    TITLE_INITIAL("首字母"),

    /** 按音轨号升序，0/缺失排最后，同级按标题。 */
    TRACK_NUMBER("音轨号");

    /** 切换到另一种排序模式。 */
    fun toggled(): SongSortMode = if (this == TITLE_INITIAL) TRACK_NUMBER else TITLE_INITIAL
}

/**
 * 歌曲列表排序模式偏好，按列表维度（listKey）持久化到 SharedPreferences。
 */
@Singleton
class SortPreferences @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs: SharedPreferences = context.getSharedPreferences("sort_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_PREFIX = "sort_mode_"
    }

    fun getSortMode(listKey: String): SongSortMode {
        val stored = prefs.getString(KEY_PREFIX + listKey, null)
        return runCatching { stored?.let(SongSortMode::valueOf) }.getOrNull() ?: SongSortMode.TITLE_INITIAL
    }

    fun setSortMode(listKey: String, mode: SongSortMode) {
        prefs.edit().putString(KEY_PREFIX + listKey, mode.name).apply()
    }
}
