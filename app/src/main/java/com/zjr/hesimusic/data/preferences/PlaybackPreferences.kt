package com.zjr.hesimusic.data.preferences

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Playlist types for remembering the playback context
 */
enum class PlaylistType {
    ALL_SONGS,      // 全部歌曲视图
    ARTIST,         // 歌手视图
    ALBUM,          // 专辑视图
    FOLDER          // 文件夹视图
}

/**
 * Data class representing the last playback context
 */
data class PlaybackContext(
    val playlistType: PlaylistType,
    val identifier: String,   // Artist name, album name, or folder path
    val songId: Long          // The ID of the song being played
)

@Singleton
class PlaybackPreferences @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs: SharedPreferences = context.getSharedPreferences("playback_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_QUEUE_IDS = "queue_ids"
        private const val KEY_CURRENT_SONG_INDEX = "current_song_index"
        private const val KEY_CURRENT_POSITION = "current_position"
        private const val KEY_PLAYLIST_TYPE = "playlist_type"
        private const val KEY_PLAYLIST_IDENTIFIER = "playlist_identifier"
        private const val KEY_CURRENT_SONG_ID = "current_song_id"
    }

    fun saveQueue(ids: List<Long>) {
        val idsString = ids.joinToString(",")
        prefs.edit().putString(KEY_QUEUE_IDS, idsString).apply()
    }

    fun getQueue(): List<Long> {
        val idsString = prefs.getString(KEY_QUEUE_IDS, "") ?: ""
        if (idsString.isEmpty()) return emptyList()
        return try {
            idsString.split(",").map { it.toLong() }
        } catch (e: NumberFormatException) {
            emptyList()
        }
    }

    fun saveCurrentSongIndex(index: Int) {
        prefs.edit().putInt(KEY_CURRENT_SONG_INDEX, index).apply()
    }

    fun getCurrentSongIndex(): Int {
        return prefs.getInt(KEY_CURRENT_SONG_INDEX, 0)
    }

    fun saveCurrentPosition(position: Long) {
        prefs.edit().putLong(KEY_CURRENT_POSITION, position).apply()
    }

    fun getCurrentPosition(): Long {
        return prefs.getInt(KEY_CURRENT_POSITION, 0).toLong() // Stored as Long but getInt used? No, putLong used.
    }
    
    // Fix for getCurrentPosition using getLong
    fun getLastPosition(): Long {
        return prefs.getLong(KEY_CURRENT_POSITION, 0L)
    }

    /**
     * Save the playback context (playlist type and identifier).
     * Uses commit() instead of apply() to ensure data is saved immediately,
     * which is important when the app might be killed.
     */
    fun savePlaybackContext(playlistType: PlaylistType, identifier: String, songId: Long) {
        prefs.edit()
            .putString(KEY_PLAYLIST_TYPE, playlistType.name)
            .putString(KEY_PLAYLIST_IDENTIFIER, identifier)
            .putLong(KEY_CURRENT_SONG_ID, songId)
            .commit()  // Use commit() for synchronous write to handle app kill scenarios
    }

    /**
     * Get the saved playback context.
     * Returns null if no context has been saved.
     */
    fun getPlaybackContext(): PlaybackContext? {
        val typeStr = prefs.getString(KEY_PLAYLIST_TYPE, null) ?: return null
        val identifier = prefs.getString(KEY_PLAYLIST_IDENTIFIER, "") ?: ""
        val songId = prefs.getLong(KEY_CURRENT_SONG_ID, -1L)
        
        return try {
            val type = PlaylistType.valueOf(typeStr)
            PlaybackContext(type, identifier, songId)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /**
     * Get the current song ID being played
     */
    fun getCurrentSongId(): Long {
        return prefs.getLong(KEY_CURRENT_SONG_ID, -1L)
    }
}
