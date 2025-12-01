package com.zjr.hesimusic.ui.common

import android.content.ComponentName
import android.content.Context
import android.os.CountDownTimer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.zjr.hesimusic.data.mapper.toMediaItem
import com.zjr.hesimusic.data.model.Song
import com.zjr.hesimusic.data.preferences.PlaybackContext
import com.zjr.hesimusic.data.preferences.PlaybackPreferences
import com.zjr.hesimusic.data.preferences.PlaylistType
import com.zjr.hesimusic.data.repository.SongRepository
import com.zjr.hesimusic.service.MusicService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MusicViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playbackPreferences: PlaybackPreferences,
    private val songRepository: SongRepository
) : ViewModel() {

    private var mediaControllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null

    private val _uiState = MutableStateFlow(MusicUiState())
    val uiState: StateFlow<MusicUiState> = _uiState.asStateFlow()

    private val _sleepTimerState = MutableStateFlow<Long?>(null)
    val sleepTimerState: StateFlow<Long?> = _sleepTimerState.asStateFlow()
    
    // Track the current playback context
    private var currentPlaylistType: PlaylistType = PlaylistType.ALL_SONGS
    private var currentPlaylistIdentifier: String = ""
    
    // Flag to prevent saving context during app startup/restore
    // Use @Volatile for thread-safe access as it may be accessed from different threads
    @Volatile
    private var isRestoringState: Boolean = true
    
    companion object {
        // Delay to allow MediaController to finish setup before allowing context saves
        private const val MEDIA_CONTROLLER_SETUP_DELAY_MS = 1000L
    }

    private var sleepTimer: CountDownTimer? = null

    init {
        // Load last played song for immediate UI update
        viewModelScope.launch {
            val queueIds = playbackPreferences.getQueue()
            val currentIndex = playbackPreferences.getCurrentSongIndex()
            if (queueIds.isNotEmpty() && currentIndex in queueIds.indices) {
                val songId = queueIds[currentIndex]
                val songs = songRepository.getSongsByIds(listOf(songId))
                val song = songs.firstOrNull()
                if (song != null) {
                    val mediaItem = song.toMediaItem()
                    val position = playbackPreferences.getLastPosition()
                    _uiState.update {
                        it.copy(
                            currentMediaItem = mediaItem,
                            currentPosition = position,
                            duration = song.duration
                        )
                    }
                }
            }
            
            // Restore the playback context (playlist type and identifier)
            playbackPreferences.getPlaybackContext()?.let { context ->
                currentPlaylistType = context.playlistType
                currentPlaylistIdentifier = context.identifier
            }
            
            // Mark restoration as complete after a delay to allow MediaController to finish setup
            delay(MEDIA_CONTROLLER_SETUP_DELAY_MS)
            isRestoringState = false
        }

        val sessionToken = SessionToken(context, ComponentName(context, MusicService::class.java))
        mediaControllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        mediaControllerFuture?.addListener({
            mediaController = mediaControllerFuture?.get()
            mediaController?.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    updateState()
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    updateState()
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    updateState()
                    // Only save playback context when user manually changes track, not during restore
                    if (!isRestoringState) {
                        saveCurrentPlaybackContext()
                    }
                }

                override fun onRepeatModeChanged(repeatMode: Int) {
                    updateState()
                }

                override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                    updateState()
                }
                
                override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                    updateState()
                }
            })
            updateState()
            startProgressUpdateLoop()
        }, MoreExecutors.directExecutor())
    }

    private fun startProgressUpdateLoop() {
        viewModelScope.launch {
            while (isActive) {
                if (mediaController?.isPlaying == true) {
                    updateState()
                }
                delay(1000) // Update every second
            }
        }
    }

    private fun updateState() {
        mediaController?.let { controller ->
            val playlist = List(controller.mediaItemCount) { i -> controller.getMediaItemAt(i) }
            val audioSessionId = controller.sessionExtras.getInt("AUDIO_SESSION_ID", 0)
            _uiState.update {
                it.copy(
                    isPlaying = controller.isPlaying,
                    currentMediaItem = controller.currentMediaItem,
                    playbackState = controller.playbackState,
                    repeatMode = controller.repeatMode,
                    shuffleModeEnabled = controller.shuffleModeEnabled,
                    currentPosition = controller.currentPosition,
                    duration = controller.duration,
                    bufferedPosition = controller.bufferedPosition,
                    playlist = playlist,
                    audioSessionId = audioSessionId
                )
            }
        }
    }
    
    /**
     * Save the current playback context (current song ID) when track changes
     */
    private fun saveCurrentPlaybackContext() {
        mediaController?.currentMediaItem?.mediaId?.toLongOrNull()?.let { songId ->
            playbackPreferences.savePlaybackContext(
                currentPlaylistType,
                currentPlaylistIdentifier,
                songId
            )
        }
    }

    fun play(song: Song) {
        mediaController?.let { controller ->
            val mediaItem = song.toMediaItem()
            controller.setMediaItem(mediaItem)
            controller.prepare()
            controller.play()
        }
    }
    
    /**
     * Play a list of songs from a specific playlist context.
     * Saves the context for restoration when the app restarts.
     */
    fun playList(
        songs: List<Song>, 
        startIndex: Int = 0,
        playlistType: PlaylistType = PlaylistType.ALL_SONGS,
        playlistIdentifier: String = ""
    ) {
        if (songs.isEmpty()) return
        
        // Ensure startIndex is within bounds
        val safeIndex = startIndex.coerceIn(0, songs.lastIndex)
        
        mediaController?.let { controller ->
            val mediaItems = songs.map { it.toMediaItem() }
            controller.setMediaItems(mediaItems, safeIndex, 0)
            controller.prepare()
            controller.play()
            
            // Save the playback context
            currentPlaylistType = playlistType
            currentPlaylistIdentifier = playlistIdentifier
            val songId = songs[safeIndex].id
            playbackPreferences.savePlaybackContext(playlistType, playlistIdentifier, songId)
        }
    }
    
    /**
     * Get the saved playback context for restoring the playlist view on app startup
     */
    fun getPlaybackContext(): PlaybackContext? {
        return playbackPreferences.getPlaybackContext()
    }

    fun pause() {
        mediaController?.pause()
    }

    fun resume() {
        mediaController?.play()
    }
    
    fun skipToNext() {
        mediaController?.let { controller ->
            if (controller.hasNextMediaItem()) {
                controller.seekToNext()
            } else {
                if (controller.mediaItemCount > 0) {
                    controller.seekToDefaultPosition(0)
                    controller.play()
                }
            }
        }
    }
    
    fun skipToPrevious() {
        mediaController?.seekToPrevious()
    }

    fun seekTo(position: Long) {
        mediaController?.seekTo(position)
        updateState() // Immediate update for UI responsiveness
    }

    fun setRepeatMode(repeatMode: Int) {
        mediaController?.repeatMode = repeatMode
    }

    fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) {
        mediaController?.shuffleModeEnabled = shuffleModeEnabled
    }

    fun removeMediaItem(index: Int) {
        mediaController?.removeMediaItem(index)
    }

    fun moveMediaItem(currentIndex: Int, newIndex: Int) {
        mediaController?.moveMediaItem(currentIndex, newIndex)
    }
    
    fun clearPlaylist() {
        mediaController?.clearMediaItems()
    }

    fun startSleepTimer(minutes: Int) {
        sleepTimer?.cancel()
        sleepTimer = object : CountDownTimer(minutes * 60 * 1000L, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                _sleepTimerState.value = millisUntilFinished
            }
            override fun onFinish() {
                pause()
                _sleepTimerState.value = null
            }
        }.start()
    }

    fun cancelSleepTimer() {
        sleepTimer?.cancel()
        _sleepTimerState.value = null
    }

    override fun onCleared() {
        super.onCleared()
        mediaControllerFuture?.let {
            MediaController.releaseFuture(it)
        }
        sleepTimer?.cancel()
    }
}

data class MusicUiState(
    val isPlaying: Boolean = false,
    val currentMediaItem: MediaItem? = null,
    val playbackState: Int = Player.STATE_IDLE,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val shuffleModeEnabled: Boolean = false,
    val currentPosition: Long = 0L,
    val duration: Long = 0L,
    val bufferedPosition: Long = 0L,
    val playlist: List<MediaItem> = emptyList(),
    val audioSessionId: Int = 0
)
