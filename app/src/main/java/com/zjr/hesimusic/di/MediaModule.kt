package com.zjr.hesimusic.di

import android.content.Context
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
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
        extractorsFactory: DefaultExtractorsFactory
    ): ExoPlayer {
        Log.i(TAG, "Building ExoPlayer")
        val startTime = System.currentTimeMillis()
        
        val player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(context, extractorsFactory)
            )
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        
        val duration = System.currentTimeMillis() - startTime
        Log.i(TAG, "ExoPlayer created in ${duration}ms")
        
        return player
    }
}
