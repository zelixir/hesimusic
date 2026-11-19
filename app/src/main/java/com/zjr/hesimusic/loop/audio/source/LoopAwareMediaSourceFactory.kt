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
            // 00021 取证：tag 真正到达这里才说明"音频源已换"，而不只是时间线 item 带了 tag。
            loopSourceCount.incrementAndGet()
            RingBandLog.i(
                "source factory select=loop songId=" + tag.songId +
                    " [" + tag.loopStartUs + "," + tag.loopEndUs + ")" +
                    " mediaId=" + mediaItem.mediaId +
                    " uri=" + mediaItem.localConfiguration?.uri +
                    " cacheKey=" + mediaItem.localConfiguration?.customCacheKey +
                    " tag=" + tag.javaClass.simpleName +
                    " created=" + loopSourceCount.get(),
            )
            return sourceFactory(mediaItem, tag)
        }
        RingBandLog.i(
            "source factory select=delegate enabled=" + enabled +
                " tag=" + (mediaItem.localConfiguration?.tag?.javaClass?.simpleName ?: "none") +
                " cacheKey=" + mediaItem.localConfiguration?.customCacheKey +
                " mediaId=" + mediaItem.mediaId +
                " uri=" + mediaItem.localConfiguration?.uri,
        )
        return delegate.createMediaSource(mediaItem)
    }

    companion object {
        /** 00021 取证/兜底：真正由本工厂创建的循环源数量（进程内单调递增）。 */
        private val loopSourceCount = java.util.concurrent.atomic.AtomicLong(0L)

        /** 协调器用它判断 replaceMediaItem 是否真的让工厂重建了源（否则走整表重建兜底）。 */
        fun loopSourcesCreated(): Long = loopSourceCount.get()

        /** 纯逻辑：只有 SOURCE 且 tag 为 [LoopSourceItemTag] 时才接管。 */
        internal fun loopTagOf(tag: Any?, enabled: Boolean): LoopSourceItemTag? =
            if (enabled && tag is LoopSourceItemTag) tag else null

        fun defaultSourceFactory(
            pcmDecoder: PcmDecoder,
            onSeamOutputFrames: ((Long) -> Unit)? = null,
        ): (MediaItem, LoopSourceItemTag) -> MediaSource =
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
                    // 真机取证必须能看见 RingBand 选路/回退（原先默认 logger={} 把所有
                    // "RingBand select/fallback" 都吞了，无法判断到底走了环带还是块缓存）。
                    logger = { message -> RingBandLog.i(message) },
                    onSeamOutputFrames = onSeamOutputFrames,
                )
            }
    }
}