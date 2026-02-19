package com.zjr.hesimusic.ui.settings

import androidx.lifecycle.ViewModel
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

    fun updateMinAlbumTrackCount(value: Int) {
        playbackPreferences.saveMinAlbumTrackCount(value)
    }

    fun updateMinArtistTrackCount(value: Int) {
        playbackPreferences.saveMinArtistTrackCount(value)
    }
}
