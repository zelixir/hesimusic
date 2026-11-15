package com.zjr.hesimusic.di

import android.content.Context
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import com.zjr.hesimusic.loop.LoopRuntime
import com.zjr.hesimusic.loop.audio.LoopAudioController
import com.zjr.hesimusic.loop.audio.LoopRenderersFactory
import com.zjr.hesimusic.service.LoopMediaSourceFactory
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
        loopAudioController: LoopAudioController,
        loopRuntime: LoopRuntime
    ): ExoPlayer {
        Log.i(TAG, "Building ExoPlayer")
        val startTime = System.currentTimeMillis()

        val player = ExoPlayer.Builder(context)
            // 队列里的 MediaItem 不带 clip;只有"当前播放项命中无缝窗口"时,
            // 由 LoopMediaSourceFactory 额外套一层 ClippingMediaSource(0, B)(见 docs/loop-v3-service-design.md)
            .setMediaSourceFactory(
                LoopMediaSourceFactory(DefaultMediaSourceFactory(context, extractorsFactory), loopRuntime)
            )
            .setRenderersFactory(LoopRenderersFactory(context, loopAudioController))
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        val duration = System.currentTimeMillis() - startTime
        Log.i(TAG, "ExoPlayer created in ${duration}ms")

        return player
    }
}
