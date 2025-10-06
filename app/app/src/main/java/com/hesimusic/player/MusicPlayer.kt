package com.hesimusic.player

import android.content.Context
import androidx.media3.exoplayer.ExoPlayer


class MusicPlayer(context: Context) {
    private val player = ExoPlayer.Builder(context).build()

    fun playUrl(url: String) {
        // TODO: prepare media item and play
    }

    fun pause() {
        player.pause()
    }

    fun release() {
        player.release()
    }
}
