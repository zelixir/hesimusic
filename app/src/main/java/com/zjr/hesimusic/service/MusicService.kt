package com.zjr.hesimusic.service

import android.content.Intent
import android.os.Bundle
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

    @Inject
    lateinit var player: ExoPlayer

    @Inject
    lateinit var playbackPreferences: PlaybackPreferences

    @Inject
    lateinit var songRepository: SongRepository

    private var mediaSession: MediaSession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
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
        serviceScope.launch {
            // Restore playback mode
            player.repeatMode = playbackPreferences.getRepeatMode()
            player.shuffleModeEnabled = playbackPreferences.getShuffleModeEnabled()

            val queueIds = playbackPreferences.getQueue()
            if (queueIds.isNotEmpty()) {
                val songs = songRepository.getSongsByIds(queueIds)
                // Maintain order based on IDs
                val sortedSongs = queueIds.mapNotNull { id -> songs.find { it.id == id } }
                val mediaItems = sortedSongs.map { it.toMediaItem() }
                
                if (mediaItems.isNotEmpty()) {
                    player.setMediaItems(mediaItems)
                    val index = playbackPreferences.getCurrentSongIndex()
                    val position = playbackPreferences.getLastPosition()
                    
                    if (index in mediaItems.indices) {
                        player.seekTo(index, position)
                    }
                    player.prepare()
                    // Do not auto-play
                    player.pause()
                }
            }
        }
    }

    private fun setupPlayerListeners() {
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                saveCurrentState()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                saveCurrentState()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                saveCurrentState()
            }
            
            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                 saveQueueState()
            }
        })
    }

    private fun startPeriodicSave() {
        serviceScope.launch {
            while (isActive) {
                if (player.isPlaying) {
                    saveCurrentState()
                }
                delay(5000) // Save every 5 seconds
            }
        }
    }

    private fun saveCurrentState() {
        playbackPreferences.saveCurrentSongIndex(player.currentMediaItemIndex)
        playbackPreferences.saveCurrentPosition(player.currentPosition)
    }
    
    private fun saveQueueState() {
        val mediaItems = List(player.mediaItemCount) { i -> player.getMediaItemAt(i) }
        val ids = mediaItems.mapNotNull { it.mediaId.toLongOrNull() }
        playbackPreferences.saveQueue(ids)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onDestroy() {
        saveCurrentState() // Save one last time
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
            val sessionExtras = Bundle()
            // Use try-catch to prevent crashes if player is not ready or other issues
            try {
                sessionExtras.putInt("AUDIO_SESSION_ID", player.audioSessionId)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setSessionExtras(sessionExtras)
                .build()
        }
    }
}
