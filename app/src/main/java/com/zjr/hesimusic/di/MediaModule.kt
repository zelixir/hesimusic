package com.zjr.hesimusic.di

import android.content.Context
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import com.zjr.hesimusic.loop.LoopWindow
import com.zjr.hesimusic.loop.analysis.PcmDecoder
import com.zjr.hesimusic.loop.audio.LoopAudioController
import com.zjr.hesimusic.loop.audio.LoopRenderersFactory
import com.zjr.hesimusic.loop.audio.source.LoopAwareMediaSourceFactory
import com.zjr.hesimusic.loop.audio.source.LoopSourceItemTag
import com.zjr.hesimusic.loop.audio.source.LoopSourceMainPlayer
import com.zjr.hesimusic.loop.audio.source.LoopSourceMode
import com.zjr.hesimusic.loop.audio.source.SourceRenderersFactory
import com.zjr.hesimusic.service.LoopSourceFallback
import com.zjr.hesimusic.service.MainPlayerRoute
import com.zjr.hesimusic.service.MainPlayerRouting
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
        // ★ 唯一一次判定：renderers / MediaSourceFactory / 协调器 / 会话层四处接线点
        //   全部从这一个快照取值，杜绝「换了输出通路、播放源还是普通文件」的半切换。
        val route = MainPlayerRoute.of(LoopSourceMainPlayer.shouldUseSourceForMainPlayer())
        MainPlayerRouting.activate(route)
        LoopSourceMainPlayer.activateMainPlayer(route.useSourceForMainPlayer)
        val renderersFactory = if (MainPlayerRouting.renderersUseSource(route)) {
            SourceRenderersFactory(context, loopAudioController)
        } else {
            LoopRenderersFactory(context, loopAudioController)
        }
        val defaultMediaSourceFactory = DefaultMediaSourceFactory(context, extractorsFactory)
        val loopAwareFactory = LoopAwareMediaSourceFactory(
            delegate = defaultMediaSourceFactory,
            enabled = MainPlayerRouting.mediaSourceFactoryEnabled(route),
            sourceFactory = guardedSourceFactory(
                build = LoopAwareMediaSourceFactory.defaultSourceFactory(
                    pcmDecoder = pcmDecoder,
                    onSeamOutputFrames = { outFrames -> loopAudioController.tapSeam(outFrames) },
                ),
                delegate = defaultMediaSourceFactory,
            ),
        )
        // 这里不按 route 另建 delegate：factory 内部用同一条件判断，
        // 普通 item / 开关关闭时原样走 default。
        val builder = ExoPlayer.Builder(context)
            .setMediaSourceFactory(loopAwareFactory)
            .setRenderersFactory(renderersFactory)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
        if (MainPlayerRouting.renderersUseSource(route)) {
            // 1s 块 + 解码抖动：4s 起播/12s 上限，避免每 1~2s 饿死。
            builder.setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(4_000, 12_000, 1_500, 2_500)
                    .build(),
            )
        }
        val player = builder.build()

        val duration = System.currentTimeMillis() - startTime
        Log.i(
            TAG,
            "ExoPlayer created in " + duration + "ms route=" + route.useSourceForMainPlayer +
                " renderers=" + renderersFactory.javaClass.simpleName +
                " factoryEnabled=" + MainPlayerRouting.mediaSourceFactoryEnabled(route),
        )

        return player
    }

    /**
     * 循环数据源的**最后一道**失败防线。
     *
     * 预检在 LoopPlaybackCoordinator（格式/预算/文件都存在性）已经做过，这里兜运行期异常
     * （探测和构建之间的文件变化、解码器初始化失败、环分配失败…）：登记回退、打日志、
     * 返回普通文件源，绝不让异常抛回主线程打崩播放。
     */
    private fun guardedSourceFactory(
        build: (MediaItem, LoopSourceItemTag) -> MediaSource,
        delegate: MediaSource.Factory,
    ): (MediaItem, LoopSourceItemTag) -> MediaSource = { item, tag ->
        try {
            build(item, tag)
        } catch (t: Throwable) {
            LoopSourceFallback.reportBuildFailure(
                songId = tag.songId,
                window = LoopWindow(tag.songId, tag.loopStartUs, tag.loopEndUs),
                throwable = t,
            )
            Log.w(
                TAG,
                "source build failed -> fallback M3' songId=" + tag.songId +
                    " window=[" + tag.loopStartUs + "," + tag.loopEndUs + ")" +
                    " path=" + tag.filePath,
                t,
            )
            delegate.createMediaSource(item)
        }
    }
}
