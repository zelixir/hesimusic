package com.hesimusic.player

import android.content.Context
import com.google.android.exoplayer2.ExoPlayer

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
