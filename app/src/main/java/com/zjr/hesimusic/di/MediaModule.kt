package com.zjr.hesimusic.di

import android.content.Context
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import com.zjr.hesimusic.loop.audio.LoopAudioController
import com.zjr.hesimusic.loop.audio.LoopRenderersFactory
import com.zjr.hesimusic.loop.audio.source.LoopSourceMode
import com.zjr.hesimusic.loop.audio.source.SourceRenderersFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object MediaModule {

    private const val TAG = "MediaModule"

    @Provides
    @Singleton
    fun provideAudioAttributes(): AudioAttributes {
        Log.d(TAG, "Creating AudioAttributes")
        return AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()
    }

    @Provides
    @Singleton
    fun provideDefaultExtractorsFactory(): DefaultExtractorsFactory {
        Log.d(TAG, "Creating DefaultExtractorsFactory")
        return DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
    }

    @Provides
    @Singleton
    fun provideExoPlayer(
        @ApplicationContext context: Context,
        audioAttributes: AudioAttributes,
        extractorsFactory: DefaultExtractorsFactory,
        loopAudioController: LoopAudioController
    ): ExoPlayer {
        Log.i(TAG, "Building ExoPlayer")
        val startTime = System.currentTimeMillis()

        // SOURCE 模式：默认 AudioSink + tap（不包 LoopAudioSink，避免二次拦截 PCM）；
        // M3_PRIME：保持现有 LoopRenderersFactory，行为完全不变。
        val renderersFactory = if (LoopSourceMode.isSource()) {
            SourceRenderersFactory(context, loopAudioController)
        } else {
            LoopRenderersFactory(context, loopAudioController)
        }
        val player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(context, extractorsFactory)
            )
            .setRenderersFactory(renderersFactory)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        val duration = System.currentTimeMillis() - startTime
        Log.i(TAG, "ExoPlayer created in ${duration}ms")

        return player
    }
}
