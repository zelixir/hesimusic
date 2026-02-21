package com.zjr.hesimusic.ui.settings

import androidx.lifecycle.ViewModel
import com.zjr.hesimusic.data.preferences.AppThemeMode
import com.zjr.hesimusic.data.preferences.AppThemePalette
import com.zjr.hesimusic.data.preferences.PlaybackPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val playbackPreferences: PlaybackPreferences
) : ViewModel() {
    val minAlbumTrackCount: StateFlow<Int> = playbackPreferences.minAlbumTrackCountFlow
    val minArtistTrackCount: StateFlow<Int> = playbackPreferences.minArtistTrackCountFlow
    val showMediaNotification: StateFlow<Boolean> = playbackPreferences.showMediaNotificationFlow
    val appThemeMode: StateFlow<AppThemeMode> = playbackPreferences.appThemeModeFlow
    val appThemePalette: StateFlow<AppThemePalette> = playbackPreferences.appThemePaletteFlow
    val customThemeColor: StateFlow<Int> = playbackPreferences.customThemeColorFlow
    val startupImageUri: StateFlow<String?> = playbackPreferences.startupImageUriFlow
    val listBackgroundImageUri: StateFlow<String?> = playbackPreferences.listBackgroundImageUriFlow

    fun updateMinAlbumTrackCount(value: Int) {
        playbackPreferences.saveMinAlbumTrackCount(value)
    }

    fun updateMinArtistTrackCount(value: Int) {
        playbackPreferences.saveMinArtistTrackCount(value)
    }

    fun updateShowMediaNotification(value: Boolean) {
        playbackPreferences.saveShowMediaNotification(value)
    }

    fun updateAppThemeMode(value: AppThemeMode) {
        playbackPreferences.saveAppThemeMode(value)
    }

    fun updateAppThemePalette(value: AppThemePalette) {
        playbackPreferences.saveAppThemePalette(value)
    }

    fun updateCustomThemeColor(value: Int) {
        playbackPreferences.saveCustomThemeColor(value)
    }

    fun updateStartupImageUri(value: String?) {
        playbackPreferences.saveStartupImageUri(value)
    }

    fun updateListBackgroundImageUri(value: String?) {
        playbackPreferences.saveListBackgroundImageUri(value)
    }
}
