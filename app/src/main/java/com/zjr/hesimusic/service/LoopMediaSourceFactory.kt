package com.zjr.hesimusic.service

import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.ClippingMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MediaSourceFactory
import androidx.media3.exoplayer.source.WrappingMediaSource
import androidx.media3.exoplayer.upstream.CmcdConfiguration
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.text.SubtitleParser
import com.zjr.hesimusic.data.mapper.mediaIdToSongId
import com.zjr.hesimusic.loop.LoopRuntime

/**
 * 装饰 MediaSourceFactory：只在「当前 MediaItem 命中无缝窗口（songId 相同）」时给它加 clip。
 *
 * 输入在 B 结束（ClippingMediaSource(0, B)）→ 解码到 B 即 EOS → LoopAudioSink 在 B 精确切到
 * PCM 重放（M3'）。队列里的 MediaItem 永远保持原样（不带 clippingConfiguration），
 * 只有真正 createMediaSource 的那一刻才决定是否裁剪，所以队列/恢复/收藏/上下首零影响。
 *
 * 接线（由 lead 在 di/MediaModule.kt 做）：
 *   ExoPlayer.Builder(context)
 *       .setMediaSourceFactory(LoopMediaSourceFactory(DefaultMediaSourceFactory(context, extractorsFactory), runtime))
 *
 * 契约：docs/loop-v3-service-design.md §2.1。
 */
@UnstableApi
class LoopMediaSourceFactory(
    private val delegate: MediaSourceFactory,
    private val runtime: LoopRuntime,
) : MediaSourceFactory {

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val window = runtime.window.value
        val songId = mediaIdToSongId(mediaItem.mediaId)
        val source = delegate.createMediaSource(mediaItem)
        val shaped = if (window != null && songId != null && window.songId == songId) {
            // 0 起裁到 loopEndUs：B 之后不再送样本，sink 在 B 由 CAPTURING 切 REPLAYING
            ClippingMediaSource(source, 0L, window.loopEndUs)
        } else {
            source
        }
        return NonUpdatableMediaSource(shaped)
    }

    // 其余全部转发 delegate —— 装饰层不改变任何提取/DRM/错误策略语义。

    override fun getSupportedTypes(): IntArray = delegate.supportedTypes

    override fun setDrmSessionManagerProvider(
        drmSessionManagerProvider: DrmSessionManagerProvider,
    ): MediaSource.Factory {
        delegate.setDrmSessionManagerProvider(drmSessionManagerProvider)
        return this
    }

    override fun setLoadErrorHandlingPolicy(
        loadErrorHandlingPolicy: LoadErrorHandlingPolicy,
    ): MediaSource.Factory {
        delegate.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        return this
    }

    override fun setCmcdConfigurationFactory(
        cmcdConfigurationFactory: CmcdConfiguration.Factory,
    ): MediaSource.Factory {
        delegate.setCmcdConfigurationFactory(cmcdConfigurationFactory)
        return this
    }

    override fun experimentalParseSubtitlesDuringExtraction(
        experimentalParseSubtitlesDuringExtraction: Boolean,
    ): MediaSource.Factory {
        delegate.experimentalParseSubtitlesDuringExtraction(experimentalParseSubtitlesDuringExtraction)
        return this
    }

    override fun setSubtitleParserFactory(
        subtitleParserFactory: SubtitleParser.Factory,
    ): MediaSource.Factory {
        delegate.setSubtitleParserFactory(subtitleParserFactory)
        return this
    }

    override fun experimentalSetCodecsToParseWithinGopSampleDependencies(
        codecsToParseWithinGopSampleDependencies: Int,
    ): MediaSource.Factory {
        delegate.experimentalSetCodecsToParseWithinGopSampleDependencies(codecsToParseWithinGopSampleDependencies)
        return this
    }
}

/**
 * 强制 Player.replaceMediaItem 走「重建 MediaSource」的路径。
 *
 * 原因（已用 javap 核对 media3 1.8.0 的 ExoPlayerImpl.replaceMediaItems 字节码）：
 * 它先调用 canUpdateMediaSourcesWithMediaItems，只要旧 MediaSource.canUpdateMediaItem(新 item)
 * 为 true 就原地 updateMediaItem **不会**再问 MediaSourceFactory ——
 * 而 ProgressiveMediaSource 只比较 URI/imageDurationMs/customCacheKey，
 * ClippingMediaSource 只比较两个 MediaItem 里的 clippingConfiguration（我们的 item 从不带 clip），
 * 于是「同一个 item」永远被判为可原地更新，clip 形态不会改变。
 * 这里显式返回 false，让 replaceMediaItem 一定重建当前项并重新经过 factory。
 *
 * 影响面：只有我们自己的 applyShape 会调用 replaceMediaItem，因此不会改变其它播放语义。
 */
@UnstableApi
private class NonUpdatableMediaSource(
    source: MediaSource,
) : WrappingMediaSource(source) {
    override fun canUpdateMediaItem(mediaItem: MediaItem): Boolean = false
}
