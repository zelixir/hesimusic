package com.zjr.hesimusic.ui.common

import android.content.ComponentName
import android.content.Context
import android.os.CountDownTimer
import android.util.Log
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
import com.zjr.hesimusic.data.preferences.PlaybackPreferences
import com.zjr.hesimusic.data.preferences.PlaylistContext
import com.zjr.hesimusic.data.repository.FavoriteRepository
import com.zjr.hesimusic.data.repository.SongRepository
import com.zjr.hesimusic.data.scanner.TagLibHelper
import com.zjr.hesimusic.service.MusicService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class MusicViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playbackPreferences: PlaybackPreferences,
    private val songRepository: SongRepository,
    private val favoriteRepository: FavoriteRepository,
    private val tagLibHelper: TagLibHelper
) : ViewModel() {

    companion object {
        private const val TAG = "MusicViewModel"
    }

    private var mediaControllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null

    private val _uiState = MutableStateFlow(MusicUiState())
    val uiState: StateFlow<MusicUiState> = _uiState.asStateFlow()

    private val _sleepTimerState = MutableStateFlow<Long?>(null)
    val sleepTimerState: StateFlow<Long?> = _sleepTimerState.asStateFlow()
    
    // Expose the saved playlist context for UI to use when restoring state
    private val _savedPlaylistContext = MutableStateFlow<PlaylistContext?>(null)
    val savedPlaylistContext: StateFlow<PlaylistContext?> = _savedPlaylistContext.asStateFlow()

    private var sleepTimer: CountDownTimer? = null
    
    // Track the file path for which artwork has been loaded to avoid redundant loading
    private var loadedArtworkPath: String? = null

    init {
        Log.d(TAG, "init: MusicViewModel created")
        
        // Load last played song and playlist context for immediate UI update
        viewModelScope.launch {
            val queueIds = playbackPreferences.getQueue()
            val savedSongId = playbackPreferences.getCurrentSongId()
            val currentIndex = playbackPreferences.getCurrentSongIndex()
            val playlistContext = playbackPreferences.getPlaylistContext()
            
            Log.d(TAG, "init: restoring state - queueIds=${queueIds.size}, savedSongId=$savedSongId, currentIndex=$currentIndex, playlistContext=$playlistContext")
            
            // Update the saved playlist context for UI to use
            _savedPlaylistContext.value = playlistContext
            
            // Prefer song ID over index (more reliable when shuffle was enabled)
            val songIdToLoad = if (savedSongId > 0) {
                savedSongId
            } else if (queueIds.isNotEmpty() && currentIndex in queueIds.indices) {
                queueIds[currentIndex]
            } else {
                null
            }
            
            if (songIdToLoad != null) {
                val songs = songRepository.getSongsByIds(listOf(songIdToLoad))
                val song = songs.firstOrNull()
                if (song != null) {
                    Log.d(TAG, "init: restored song '${song.title}' (id=$songIdToLoad)")
                    val mediaItem = song.toMediaItem()
                    val position = playbackPreferences.getSavedPosition()
                    val isFavorite = favoriteRepository.isFavoriteSync(song.filePath, song.startPosition)
                    // Ensure duration is valid (not negative)
                    val safeDuration = song.duration.coerceAtLeast(0L)
                    val safePosition = position.coerceAtLeast(0L)
                    _uiState.update {
                        it.copy(
                            currentMediaItem = mediaItem,
                            currentPosition = safePosition,
                            duration = safeDuration,
                            currentSongFilePath = song.filePath,
                            currentSongStartPosition = song.startPosition,
                            isCurrentSongFavorite = isFavorite,
                            playlistContext = playlistContext
                        )
                    }
                } else {
                    Log.w(TAG, "init: could not find song with id $songIdToLoad")
                }
            } else {
                Log.d(TAG, "init: no valid saved state to restore")
            }
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
                    Log.d(TAG, "onMediaItemTransition: mediaId=${mediaItem?.mediaId}, reason=$reason")
                    updateState()
                    // Update favorite status when song changes
                    updateCurrentSongFavoriteStatus()
                    // Load artwork when song changes
                    loadArtworkForCurrentSong()
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
            updateCurrentSongFavoriteStatus()
            loadArtworkForCurrentSong()
            startProgressUpdateLoop()
        }, MoreExecutors.directExecutor())
    }

    private fun updateCurrentSongFavoriteStatus() {
        viewModelScope.launch {
            val currentFilePath = _uiState.value.currentSongFilePath
            val currentStartPosition = _uiState.value.currentSongStartPosition
            if (currentFilePath != null) {
                val isFavorite = favoriteRepository.isFavoriteSync(currentFilePath, currentStartPosition)
                _uiState.update { it.copy(isCurrentSongFavorite = isFavorite) }
            }
        }
    }
    
    private fun loadArtworkForCurrentSong() {
        viewModelScope.launch {
            val currentFilePath = _uiState.value.currentSongFilePath
            if (currentFilePath != null && currentFilePath != loadedArtworkPath) {
                // Clear previous artwork immediately when song changes
                _uiState.update { it.copy(artworkBytes = null) }
                loadedArtworkPath = currentFilePath
                
                // Load artwork asynchronously on IO dispatcher
                val artworkBytes = withContext(Dispatchers.IO) {
                    try {
                        tagLibHelper.extractArtwork(currentFilePath)
                    } catch (e: Exception) {
                        null
                    }
                }
                
                // Only update if the current song hasn't changed while loading
                if (_uiState.value.currentSongFilePath == currentFilePath) {
                    _uiState.update { it.copy(artworkBytes = artworkBytes) }
                }
            }
        }
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
            // Extract file path from current media item URI
            val currentFilePath = controller.currentMediaItem?.localConfiguration?.uri?.path
            // Extract start position from clipping configuration (for CUE tracks)
            // This corresponds to Song.startPosition which is set in SongMapper.toMediaItem()
            // For non-CUE tracks, clippingConfiguration is not set so we default to 0L
            val currentStartPosition = controller.currentMediaItem?.clippingConfiguration?.startPositionMs ?: 0L
            val safePosition = controller.currentPosition.coerceAtLeast(0L)
            
            // Capture all controller state before any async operation
            val isPlaying = controller.isPlaying
            val currentMediaItem = controller.currentMediaItem
            val playbackState = controller.playbackState
            val repeatMode = controller.repeatMode
            val shuffleModeEnabled = controller.shuffleModeEnabled
            val bufferedPosition = controller.bufferedPosition
            val controllerDuration = controller.duration
            
            // Helper function to update UI state with given duration
            fun performStateUpdate(duration: Long) {
                _uiState.update {
                    it.copy(
                        isPlaying = isPlaying,
                        currentMediaItem = currentMediaItem,
                        playbackState = playbackState,
                        repeatMode = repeatMode,
                        shuffleModeEnabled = shuffleModeEnabled,
                        currentPosition = safePosition,
                        duration = duration,
                        bufferedPosition = bufferedPosition,
                        playlist = playlist,
                        audioSessionId = audioSessionId,
                        currentSongFilePath = currentFilePath,
                        currentSongStartPosition = currentStartPosition
                    )
                }
            }
            
            // If controller.duration is negative (C.TIME_UNSET), get duration from database
            if (controllerDuration < 0 && currentFilePath != null) {
                viewModelScope.launch {
                    val song = songRepository.getSongByFilePathAndStartPosition(currentFilePath, currentStartPosition)
                    val correctDuration = song?.duration?.coerceAtLeast(0L) ?: 0L
                    performStateUpdate(correctDuration)
                }
            } else {
                performStateUpdate(controllerDuration.coerceAtLeast(0L))
            }
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
    
    fun playList(songs: List<Song>, startIndex: Int = 0, context: PlaylistContext? = null) {
         mediaController?.let { controller ->
            Log.d(TAG, "playList: ${songs.size} songs, startIndex=$startIndex, context=$context")
            val mediaItems = songs.map { it.toMediaItem() }
            controller.setMediaItems(mediaItems, startIndex, 0)
            controller.prepare()
            controller.play()
            
            // Save playlist context if provided
            context?.let {
                playbackPreferences.savePlaylistContext(it)
                Log.d(TAG, "playList: saved context $it")
            }
        }
    }

    fun pause() {
        mediaController?.pause()
    }

    fun resume() {
        mediaController?.play()
    }
    
    fun skipToNext() {
        mediaController?.let { controller ->
            val wasPlaying = controller.isPlaying
            if (controller.hasNextMediaItem()) {
                controller.seekToNext()
                // Auto-play if was paused
                if (!wasPlaying) {
                    controller.play()
                }
            } else {
                if (controller.mediaItemCount > 0) {
                    controller.seekToDefaultPosition(0)
                    // Auto-play if was paused
                    if (!wasPlaying) {
                        controller.play()
                    }
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
        playbackPreferences.saveRepeatMode(repeatMode)
    }

    fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) {
        mediaController?.shuffleModeEnabled = shuffleModeEnabled
        playbackPreferences.saveShuffleModeEnabled(shuffleModeEnabled)
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

    fun toggleCurrentSongFavorite() {
        val filePath = _uiState.value.currentSongFilePath ?: return
        val startPosition = _uiState.value.currentSongStartPosition
        viewModelScope.launch {
            val newFavoriteState = favoriteRepository.toggleFavorite(filePath, startPosition)
            _uiState.update { it.copy(isCurrentSongFavorite = newFavoriteState) }
        }
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
    val audioSessionId: Int = 0,
    val currentSongFilePath: String? = null,
    val currentSongStartPosition: Long = 0L,
    val isCurrentSongFavorite: Boolean = false,
    val artworkBytes: ByteArray? = null,
    val playlistContext: PlaylistContext? = null
) {
    // Override equals and hashCode to handle ByteArray comparison properly
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MusicUiState

        if (isPlaying != other.isPlaying) return false
        if (currentMediaItem != other.currentMediaItem) return false
        if (playbackState != other.playbackState) return false
        if (repeatMode != other.repeatMode) return false
        if (shuffleModeEnabled != other.shuffleModeEnabled) return false
        if (currentPosition != other.currentPosition) return false
        if (duration != other.duration) return false
        if (bufferedPosition != other.bufferedPosition) return false
        if (playlist != other.playlist) return false
        if (audioSessionId != other.audioSessionId) return false
        if (currentSongFilePath != other.currentSongFilePath) return false
        if (isCurrentSongFavorite != other.isCurrentSongFavorite) return false
        if (playlistContext != other.playlistContext) return false
        if (artworkBytes != null) {
            if (other.artworkBytes == null) return false
            if (!artworkBytes.contentEquals(other.artworkBytes)) return false
        } else if (other.artworkBytes != null) return false

        return true
    }

    override fun hashCode(): Int {
        var result = isPlaying.hashCode()
        result = 31 * result + (currentMediaItem?.hashCode() ?: 0)
        result = 31 * result + playbackState
        result = 31 * result + repeatMode
        result = 31 * result + shuffleModeEnabled.hashCode()
        result = 31 * result + currentPosition.hashCode()
        result = 31 * result + duration.hashCode()
        result = 31 * result + bufferedPosition.hashCode()
        result = 31 * result + playlist.hashCode()
        result = 31 * result + audioSessionId
        result = 31 * result + (currentSongFilePath?.hashCode() ?: 0)
        result = 31 * result + isCurrentSongFavorite.hashCode()
        result = 31 * result + (playlistContext?.hashCode() ?: 0)
        result = 31 * result + (artworkBytes?.contentHashCode() ?: 0)
        return result
    }
}
