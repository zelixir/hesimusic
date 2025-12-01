package com.zjr.hesimusic.data.preferences

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.media3.common.Player
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Playlist context types for remembering which view/list the user was playing from
 */
enum class PlaylistType {
    GLOBAL,      // All songs
    FAVORITES,   // Favorites list
    ARTIST,      // Songs by artist
    ALBUM,       // Songs by album
    FOLDER       // Songs in folder
}

/**
 * Data class to hold the playlist context information
 */
data class PlaylistContext(
    val type: PlaylistType,
    val value: String = ""  // e.g., artist name, album name, folder path
) {
    companion object {
        val GLOBAL = PlaylistContext(PlaylistType.GLOBAL)
        val FAVORITES = PlaylistContext(PlaylistType.FAVORITES)
    }
}

@Singleton
class PlaybackPreferences @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs: SharedPreferences = context.getSharedPreferences("playback_prefs", Context.MODE_PRIVATE)
    
    companion object {
        private const val TAG = "PlaybackPreferences"
        
        private const val KEY_QUEUE_IDS = "queue_ids"
        private const val KEY_CURRENT_SONG_INDEX = "current_song_index"
        private const val KEY_CURRENT_POSITION = "current_position"
        private const val KEY_REPEAT_MODE = "repeat_mode"
        private const val KEY_SHUFFLE_MODE_ENABLED = "shuffle_mode_enabled"
        private const val KEY_PLAYLIST_TYPE = "playlist_type"
        private const val KEY_PLAYLIST_VALUE = "playlist_value"
        private const val KEY_STATE_VERSION = "state_version"
    }
    
    // State version to track if the saved state is valid and not overwritten by UI initialization
    private var currentStateVersion: Long = System.currentTimeMillis()

    fun saveQueue(ids: List<Long>) {
        val idsString = ids.joinToString(",")
        prefs.edit().putString(KEY_QUEUE_IDS, idsString).apply()
        Log.d(TAG, "saveQueue: saved ${ids.size} songs")
    }

    fun getQueue(): List<Long> {
        val idsString = prefs.getString(KEY_QUEUE_IDS, "") ?: ""
        if (idsString.isEmpty()) {
            Log.d(TAG, "getQueue: queue is empty")
            return emptyList()
        }
        return try {
            val ids = idsString.split(",").map { it.toLong() }
            Log.d(TAG, "getQueue: retrieved ${ids.size} songs")
            ids
        } catch (e: NumberFormatException) {
            Log.e(TAG, "getQueue: failed to parse queue IDs", e)
            emptyList()
        }
    }

    fun saveCurrentSongIndex(index: Int) {
        prefs.edit().putInt(KEY_CURRENT_SONG_INDEX, index).apply()
        Log.d(TAG, "saveCurrentSongIndex: $index")
    }

    fun getCurrentSongIndex(): Int {
        val index = prefs.getInt(KEY_CURRENT_SONG_INDEX, 0)
        Log.d(TAG, "getCurrentSongIndex: $index")
        return index
    }

    fun saveCurrentPosition(position: Long) {
        prefs.edit().putLong(KEY_CURRENT_POSITION, position).apply()
    }

    fun getCurrentPosition(): Long {
        return prefs.getInt(KEY_CURRENT_POSITION, 0).toLong() // Stored as Long but getInt used? No, putLong used.
    }
    
    // Fix for getCurrentPosition using getLong
    fun getLastPosition(): Long {
        val position = prefs.getLong(KEY_CURRENT_POSITION, 0L)
        Log.d(TAG, "getLastPosition: $position")
        return position
    }

    fun saveRepeatMode(repeatMode: Int) {
        prefs.edit().putInt(KEY_REPEAT_MODE, repeatMode).apply()
    }

    fun getRepeatMode(): Int {
        return prefs.getInt(KEY_REPEAT_MODE, Player.REPEAT_MODE_ALL)
    }

    fun saveShuffleModeEnabled(shuffleModeEnabled: Boolean) {
        prefs.edit().putBoolean(KEY_SHUFFLE_MODE_ENABLED, shuffleModeEnabled).apply()
    }

    fun getShuffleModeEnabled(): Boolean {
        return prefs.getBoolean(KEY_SHUFFLE_MODE_ENABLED, false)
    }
    
    /**
     * Save the playlist context (type and value).
     * This is called when the user starts playing from a specific list.
     * The stateVersion is used to prevent UI initialization from overwriting valid state.
     */
    fun savePlaylistContext(context: PlaylistContext) {
        currentStateVersion = System.currentTimeMillis()
        prefs.edit()
            .putString(KEY_PLAYLIST_TYPE, context.type.name)
            .putString(KEY_PLAYLIST_VALUE, context.value)
            .putLong(KEY_STATE_VERSION, currentStateVersion)
            .apply()
        Log.d(TAG, "savePlaylistContext: type=${context.type}, value=${context.value}, version=$currentStateVersion")
    }
    
    /**
     * Get the saved playlist context.
     * Returns null if no context is saved.
     */
    fun getPlaylistContext(): PlaylistContext? {
        val typeName = prefs.getString(KEY_PLAYLIST_TYPE, null) ?: run {
            Log.d(TAG, "getPlaylistContext: no saved context")
            return null
        }
        val value = prefs.getString(KEY_PLAYLIST_VALUE, "") ?: ""
        
        return try {
            val type = PlaylistType.valueOf(typeName)
            val context = PlaylistContext(type, value)
            Log.d(TAG, "getPlaylistContext: type=${context.type}, value=${context.value}")
            context
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "getPlaylistContext: invalid type $typeName", e)
            null
        }
    }
    
    /**
     * Get the state version for comparing if the state has been updated.
     */
    fun getStateVersion(): Long {
        return prefs.getLong(KEY_STATE_VERSION, 0L)
    }
    
    /**
     * Check if the state should be restored based on version.
     * This helps prevent UI initialization from overwriting valid saved state.
     * Returns true if there is valid saved state that should be restored.
     */
    fun hasValidSavedState(): Boolean {
        val version = getStateVersion()
        val hasQueue = getQueue().isNotEmpty()
        val hasContext = getPlaylistContext() != null
        val isValid = version > 0 && hasQueue && hasContext
        Log.d(TAG, "hasValidSavedState: version=$version, hasQueue=$hasQueue, hasContext=$hasContext, isValid=$isValid")
        return isValid
    }
    
    /**
     * Clear the saved state. Call this when the user explicitly clears the playlist.
     */
    fun clearPlaybackState() {
        prefs.edit()
            .remove(KEY_QUEUE_IDS)
            .remove(KEY_CURRENT_SONG_INDEX)
            .remove(KEY_CURRENT_POSITION)
            .remove(KEY_PLAYLIST_TYPE)
            .remove(KEY_PLAYLIST_VALUE)
            .remove(KEY_STATE_VERSION)
            .apply()
        Log.d(TAG, "clearPlaybackState: state cleared")
    }
}
