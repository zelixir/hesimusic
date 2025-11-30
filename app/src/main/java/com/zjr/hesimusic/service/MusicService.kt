package com.zjr.hesimusic.service

import android.content.Intent
import android.os.Bundle
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MusicService : MediaSessionService() {

    @Inject
    lateinit var player: ExoPlayer

    private var mediaSession: MediaSession? = null

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
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onDestroy() {
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
