package com.zjr.hesimusic.data.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.media3.common.Player
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaybackPreferences @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs: SharedPreferences = context.getSharedPreferences("playback_prefs", Context.MODE_PRIVATE)

    fun saveQueue(ids: List<Long>) {
        val idsString = ids.joinToString(",")
        prefs.edit().putString("queue_ids", idsString).apply()
    }

    fun getQueue(): List<Long> {
        val idsString = prefs.getString("queue_ids", "") ?: ""
        if (idsString.isEmpty()) return emptyList()
        return try {
            idsString.split(",").map { it.toLong() }
        } catch (e: NumberFormatException) {
            emptyList()
        }
    }

    fun saveCurrentSongIndex(index: Int) {
        prefs.edit().putInt("current_song_index", index).apply()
    }

    fun getCurrentSongIndex(): Int {
        return prefs.getInt("current_song_index", 0)
    }

    fun saveCurrentPosition(position: Long) {
        prefs.edit().putLong("current_position", position).apply()
    }

    fun getCurrentPosition(): Long {
        return prefs.getInt("current_position", 0).toLong() // Stored as Long but getInt used? No, putLong used.
    }
    
    // Fix for getCurrentPosition using getLong
    fun getLastPosition(): Long {
        return prefs.getLong("current_position", 0L)
    }

    fun saveRepeatMode(repeatMode: Int) {
        prefs.edit().putInt("repeat_mode", repeatMode).apply()
    }

    fun getRepeatMode(): Int {
        return prefs.getInt("repeat_mode", Player.REPEAT_MODE_ALL)
    }

    fun saveShuffleModeEnabled(shuffleModeEnabled: Boolean) {
        prefs.edit().putBoolean("shuffle_mode_enabled", shuffleModeEnabled).apply()
    }

    fun getShuffleModeEnabled(): Boolean {
        return prefs.getBoolean("shuffle_mode_enabled", false)
    }
}
