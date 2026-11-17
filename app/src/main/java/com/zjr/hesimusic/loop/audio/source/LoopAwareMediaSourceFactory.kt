package com.zjr.hesimusic.loop.audio.source

import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.source.MediaSource
import com.zjr.hesimusic.loop.analysis.PcmDecoder

/**
 * 主播放器的 MediaSourceFactory：只对带 [LoopSourceItemTag] 的 item 返回循环 PCM 源，
 * 其余 item 原样交给 delegate（首页/队列/普通播放完全不变）。
 */
@UnstableApi
class LoopAwareMediaSourceFactory(
    private val delegate: MediaSource.Factory,
    private val enabled: Boolean,
    private val sourceFactory: (MediaItem, LoopSourceItemTag) -> MediaSource,
) : MediaSource.Factory {

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

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val tag = loopTagOf(mediaItem.localConfiguration?.tag, enabled)
        if (tag != null) {
            return sourceFactory(mediaItem, tag)
        }
        return delegate.createMediaSource(mediaItem)
    }

    companion object {
        /** 纯逻辑：只有 SOURCE 且 tag 为 [LoopSourceItemTag] 时才接管。 */
        internal fun loopTagOf(tag: Any?, enabled: Boolean): LoopSourceItemTag? =
            if (enabled && tag is LoopSourceItemTag) tag else null

        fun defaultSourceFactory(pcmDecoder: PcmDecoder): (MediaItem, LoopSourceItemTag) -> MediaSource =
            { item, tag ->
                val format = PcmFileFormatProbe.probe(tag.filePath)
                val timeline = LoopingPcmTimeline(
                    songId = tag.songId,
                    loopStartUs = tag.loopStartUs,
                    loopEndUs = tag.loopEndUs,
                    format = format,
                    entryPositionUs = tag.loopStartUs,
                )
                LoopingPcmExtractorMediaSource.create(
                    item = item,
                    timeline = timeline,
                    provider = PcmDecoderRangeProvider(pcmDecoder, tag.filePath),
                )
            }
    }
}
