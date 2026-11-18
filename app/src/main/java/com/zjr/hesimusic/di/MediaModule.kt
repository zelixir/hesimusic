package com.zjr.hesimusic.di

import android.content.Context
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import com.zjr.hesimusic.loop.analysis.PcmDecoder
import com.zjr.hesimusic.loop.audio.LoopAudioController
import com.zjr.hesimusic.loop.audio.LoopRenderersFactory
import com.zjr.hesimusic.loop.audio.source.LoopAwareMediaSourceFactory
import com.zjr.hesimusic.loop.audio.source.LoopSourceMainPlayer
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
        loopAudioController: LoopAudioController,
        pcmDecoder: PcmDecoder,
    ): ExoPlayer {
        Log.i(TAG, "Building ExoPlayer")
        val startTime = System.currentTimeMillis()
        // 主播放器在进程启动时读取会话开关：SOURCE 用默认 sink + 数据源循环，
        // M3_PRIME 保持 LoopRenderersFactory，行为完全不变。
        LoopSourceMode.refreshFromSettings(context)
        LoopSourceMainPlayer.refreshFromSettings(context)
        val mainSource = LoopSourceMainPlayer.shouldUseSourceForMainPlayer()
        LoopSourceMainPlayer.activateMainPlayer(mainSource)
        // renderers 与 MediaSourceFactory 必须同源，避免半切换。
        val renderersFactory = if (mainSource) {
            SourceRenderersFactory(context, loopAudioController)
        } else {
            LoopRenderersFactory(context, loopAudioController)
        }
        val defaultMediaSourceFactory = DefaultMediaSourceFactory(context, extractorsFactory)
        val loopAwareFactory = LoopAwareMediaSourceFactory(
            delegate = defaultMediaSourceFactory,
            enabled = mainSource,
            sourceFactory = LoopAwareMediaSourceFactory.defaultSourceFactory(
                pcmDecoder = pcmDecoder,
                onSeamOutputFrames = { outFrames -> loopAudioController.tapSeam(outFrames) },
            ),
        )
        // 这里不按 mainSource 另建 delegate：factory 内部用同一条件判断，
        // 普通 item / 开关关闭时原样走 default。
        val builder = ExoPlayer.Builder(context)
            .setMediaSourceFactory(loopAwareFactory)
            .setRenderersFactory(renderersFactory)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
        if (mainSource) {
            // 1s 块 + 解码抖动：4s 起播/12s 上限，避免每 1~2s 饿死。
            builder.setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(4_000, 12_000, 1_500, 2_500)
                    .build(),
            )
        }
        val player = builder.build()

        val duration = System.currentTimeMillis() - startTime
        Log.i(TAG, "ExoPlayer created in ${duration}ms")

        return player
    }
}
