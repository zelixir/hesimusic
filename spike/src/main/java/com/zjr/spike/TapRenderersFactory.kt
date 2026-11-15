package com.zjr.spike

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink

/** 只做一件事：buildAudioSink 返回注入的 TapAudioSink（内部是 DefaultAudioSink，pcm16 默认输出）。 */
@UnstableApi
class TapRenderersFactory(
    context: Context,
    private val sink: AudioSink,
) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink = sink
}
