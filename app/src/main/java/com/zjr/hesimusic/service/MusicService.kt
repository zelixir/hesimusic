package com.zjr.hesimusic.service

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.zjr.hesimusic.data.mapper.toMediaItem
import com.zjr.hesimusic.data.preferences.PlaybackPreferences
import com.zjr.hesimusic.data.repository.SongRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MusicService : MediaSessionService() {

    companion object {
        private const val TAG = "MusicService"
    }

    @Inject
    lateinit var player: ExoPlayer

    @Inject
    lateinit var playbackPreferences: PlaybackPreferences

    @Inject
    lateinit var songRepository: SongRepository

    private var mediaSession: MediaSession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    // Flag to prevent saving state while restoring it
    private var isRestoringState = false

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate: MusicService created")
        
        val forwardingPlayer = object : ForwardingPlayer(player) {
            override fun getAvailableCommands(): Player.Commands {
                return super.getAvailableCommands().buildUpon()
                    .add(Player.COMMAND_SEEK_TO_NEXT)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .build()
            }

            override fun isCommandAvailable(command: Int): Boolean {
                return if (command == Player.COMMAND_SEEK_TO_NEXT || command == Player.COMMAND_SEEK_TO_PREVIOUS) {
                    mediaItemCount > 0
                } else {
                    super.isCommandAvailable(command)
                }
            }

            override fun seekToNext() {
                if (hasNextMediaItem()) {
                    super.seekToNext()
                } else {
                    if (mediaItemCount > 0) {
                        seekToDefaultPosition(0)
                        if (playbackState == Player.STATE_ENDED || !isPlaying) {
                            play()
                        }
                    }
                }
            }
            
            override fun seekToPrevious() {
                if (hasPreviousMediaItem()) {
                    super.seekToPrevious()
                } else {
                    if (mediaItemCount > 0) {
                        seekToDefaultPosition(mediaItemCount - 1)
                        if (playbackState == Player.STATE_ENDED || !isPlaying) {
                            play()
                        }
                    }
                }
            }
        }

        mediaSession = MediaSession.Builder(this, forwardingPlayer)
            .setCallback(MusicSessionCallback())
            .build()

        restorePlaybackState()
        setupPlayerListeners()
        startPeriodicSave()
    }

    private fun restorePlaybackState() {
        Log.d(TAG, "restorePlaybackState: starting state restoration")
        isRestoringState = true  // Prevent saving state during restoration
        
        serviceScope.launch {
            try {
                // Get saved state first
                val repeatMode = playbackPreferences.getRepeatMode()
                val savedShuffleMode = playbackPreferences.getShuffleModeEnabled()
                val savedSongId = playbackPreferences.getCurrentSongId()
                val savedIndex = playbackPreferences.getCurrentSongIndex()
                val savedPosition = playbackPreferences.getSavedPosition()
                val playlistContext = playbackPreferences.getPlaylistContext()
                
                Log.d(TAG, "restorePlaybackState: repeatMode=$repeatMode, shuffleMode=$savedShuffleMode")
                Log.d(TAG, "restorePlaybackState: songId=$savedSongId, index=$savedIndex, playlistContext=$playlistContext")

                val queueIds = playbackPreferences.getQueue()
                
                if (queueIds.isNotEmpty()) {
                    val songs = songRepository.getSongsByIds(queueIds)
                    Log.d(TAG, "restorePlaybackState: found ${songs.size} songs for ${queueIds.size} queue IDs")
                    
                    // Maintain order based on IDs
                    val sortedSongs = queueIds.mapNotNull { id -> songs.find { it.id == id } }
                    val mediaItems = sortedSongs.map { it.toMediaItem() }
                    
                    if (mediaItems.isNotEmpty()) {
                        // Set repeat mode but NOT shuffle mode yet (to avoid shuffling the queue)
                        player.repeatMode = repeatMode
                        player.shuffleModeEnabled = false
                        
                        // Set media items
                        player.setMediaItems(mediaItems)
                        player.prepare()
                        
                        // Find the correct index using song ID (more reliable than saved index when shuffle was enabled)
                        var targetIndex = savedIndex
                        if (savedSongId > 0) {
                            val foundIndex = mediaItems.indexOfFirst { it.mediaId == savedSongId.toString() }
                            if (foundIndex >= 0) {
                                targetIndex = foundIndex
                                Log.d(TAG, "restorePlaybackState: found song by ID at index $foundIndex")
                            } else {
                                Log.w(TAG, "restorePlaybackState: song ID $savedSongId not found, using saved index $savedIndex")
                            }
                        }
                        
                        // Validate and clamp targetIndex to valid range
                        if (targetIndex !in mediaItems.indices) {
                            Log.w(TAG, "restorePlaybackState: index $targetIndex out of range, mediaItems size=${mediaItems.size}, using index 0")
                            targetIndex = 0
                        }
                        
                        Log.d(TAG, "restorePlaybackState: seeking to index=$targetIndex, position=$savedPosition")
                        player.seekTo(targetIndex, savedPosition)
                        
                        // Do not auto-play
                        player.pause()
                        
                        // Now enable shuffle mode if it was enabled (after seeking to the correct song)
                        if (savedShuffleMode) {
                            player.shuffleModeEnabled = true
                            Log.d(TAG, "restorePlaybackState: enabled shuffle mode after restoration")
                        }
                        
                        Log.d(TAG, "restorePlaybackState: state restored successfully")
                    } else {
                        Log.w(TAG, "restorePlaybackState: no media items to restore")
                    }
                } else {
                    Log.d(TAG, "restorePlaybackState: no saved queue to restore")
                }
            } finally {
                isRestoringState = false  // Allow saving state again
            }
        }
    }

    private fun setupPlayerListeners() {
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                Log.d(TAG, "onMediaItemTransition: mediaId=${mediaItem?.mediaId}, reason=$reason")
                saveCurrentState()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                Log.d(TAG, "onPlaybackStateChanged: state=$playbackState")
                saveCurrentState()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                Log.d(TAG, "onIsPlayingChanged: isPlaying=$isPlaying")
                saveCurrentState()
            }
            
            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                Log.d(TAG, "onTimelineChanged: windowCount=${timeline.windowCount}, reason=$reason")
                if (!isRestoringState) {
                    saveQueueState()
                }
            }
        })
    }

    private fun startPeriodicSave() {
        serviceScope.launch {
            while (isActive) {
                if (player.isPlaying && !isRestoringState) {
                    saveCurrentState()
                }
                delay(5000) // Save every 5 seconds
            }
        }
    }

    private fun saveCurrentState() {
        if (isRestoringState) {
            Log.d(TAG, "saveCurrentState: skipped (restoring state)")
            return
        }
        
        val index = player.currentMediaItemIndex
        val position = player.currentPosition
        val mediaItem = player.currentMediaItem
        val songId = mediaItem?.mediaId?.toLongOrNull() ?: -1L
        
        playbackPreferences.saveCurrentSongIndex(index)
        playbackPreferences.saveCurrentSongId(songId)
        playbackPreferences.saveCurrentPosition(position)
    }
    
    private fun saveQueueState() {
        if (isRestoringState) {
            Log.d(TAG, "saveQueueState: skipped (restoring state)")
            return
        }
        
        val mediaItems = List(player.mediaItemCount) { i -> player.getMediaItemAt(i) }
        val ids = mediaItems.mapNotNull { it.mediaId.toLongOrNull() }
        playbackPreferences.saveQueue(ids)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy: MusicService destroyed, saving final state")
        saveCurrentState() // Save one last time
        saveQueueState()   // Also save queue state
        serviceScope.cancel()
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }

    // Callback to handle custom actions if needed
    private inner class MusicSessionCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            Log.d(TAG, "MusicSessionCallback.onConnect: controller=${controller.packageName}")
            val sessionExtras = Bundle()
            // Use try-catch to prevent crashes if player is not ready or other issues
            try {
                sessionExtras.putInt("AUDIO_SESSION_ID", player.audioSessionId)
            } catch (e: Exception) {
                Log.e(TAG, "MusicSessionCallback.onConnect: error getting audio session ID", e)
            }
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setSessionExtras(sessionExtras)
                .build()
        }
    }
}
