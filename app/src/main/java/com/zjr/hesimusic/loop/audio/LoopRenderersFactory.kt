package com.zjr.hesimusic.loop.audio

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink

/**
 * 把 [LoopAudioSink] 注入 ExoPlayer 的唯一入口。
 *
 * 保留 DefaultRenderersFactory 传给 buildAudioSink 的两个开关（float output /
 * AudioTrack playback params），避免包装后丢失既有行为。
 */
@UnstableApi
class LoopRenderersFactory(
    context: Context,
    private val controller: LoopAudioController,
) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        floatOutput: Boolean,
        playbackParams: Boolean,
    ): AudioSink {
        val delegate = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(floatOutput)
            .setEnableAudioTrackPlaybackParams(playbackParams)
            .build()
        return LoopAudioSink(controller, delegate)
    }
}
