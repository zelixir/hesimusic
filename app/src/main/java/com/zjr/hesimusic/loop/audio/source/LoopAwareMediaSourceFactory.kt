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
            recordSelect(
                kind = SELECT_LOOP,
                mediaItem = mediaItem,
                songId = tag.songId,
                loopStartUs = tag.loopStartUs,
                loopEndUs = tag.loopEndUs,
            )
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
        recordSelect(
            kind = SELECT_DELEGATE,
            mediaItem = mediaItem,
            songId = null,
            loopStartUs = null,
            loopEndUs = null,
        )
        RingBandLog.i(
            "source factory select=delegate enabled=" + enabled +
                " tag=" + (mediaItem.localConfiguration?.tag?.javaClass?.simpleName ?: "none") +
                " cacheKey=" + mediaItem.localConfiguration?.customCacheKey +
                " mediaId=" + mediaItem.mediaId +
                " uri=" + mediaItem.localConfiguration?.uri,
        )
        return delegate.createMediaSource(mediaItem)
    }

    /**
     * 记录一次 select（00021 不变量取证）：kind=loop 表示真的构建了循环 PCM 源，
     * delegate 表示把 item 交给了普通文件源。按 mediaId 保留最近 64 条，供 debug 侧
     * 判断「当前 item 的源最近一次是不是 loop」。
     */
    private fun recordSelect(
        kind: String,
        mediaItem: MediaItem,
        songId: Long?,
        loopStartUs: Long?,
        loopEndUs: Long?,
    ) {
        val record = SourceSelect(
            kind = kind,
            mediaId = mediaItem.mediaId,
            cacheKey = mediaItem.localConfiguration?.customCacheKey,
            songId = songId,
            loopStartUs = loopStartUs,
            loopEndUs = loopEndUs,
            atMs = System.currentTimeMillis(),
        )
        synchronized(recentSelects) {
            recentSelects.addLast(record)
            while (recentSelects.size > MAX_SELECT_RECORDS) {
                recentSelects.removeFirst()
            }
        }
    }

    companion object {
        /** 00021 取证/兜底：真正由本工厂创建的循环源数量（进程内单调递增）。 */
        private val loopSourceCount = java.util.concurrent.atomic.AtomicLong(0L)

        /** select 种类：循环 PCM 源。 */
        const val SELECT_LOOP: String = "loop"

        /** select 种类：普通文件源（delegate）。 */
        const val SELECT_DELEGATE: String = "delegate"

        /** 只保留最近 N 次 select（队列预取会为每首歌各建一次源）。 */
        private const val MAX_SELECT_RECORDS = 64

        private val recentSelects = ArrayDeque<SourceSelect>()

        /** 一次 createMediaSource 的取证记录（纯数据）。 */
        data class SourceSelect(
            val kind: String,
            val mediaId: String,
            val cacheKey: String?,
            val songId: Long?,
            val loopStartUs: Long?,
            val loopEndUs: Long?,
            val atMs: Long,
        )

        /** 最近一次 select（不限 mediaId）。 */
        fun lastSelect(): SourceSelect? = synchronized(recentSelects) {
            recentSelects.lastOrNull()
        }

        /**
         * 当前 mediaId 最近一次 select。没有记录（例如进程启动前的旧源）返回 null。
         *
         * 注意：同一个 mediaId 可能因为队列里重复出现同一首歌而有多条记录；这里取**最近**
         * 一条，符合「当前 item 的源最近一次是怎么建的」这一语义。
         */
        fun lastSelectFor(mediaId: String): SourceSelect? = synchronized(recentSelects) {
            for (index in recentSelects.indices.reversed()) {
                val record = recentSelects[index]
                if (record.mediaId == mediaId) return record
            }
            null
        }

        /** 单测隔离用。 */
        fun resetSelectsForTest() {
            synchronized(recentSelects) { recentSelects.clear() }
        }

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
                    fullDurationUs = tag.fullDurationUs,
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