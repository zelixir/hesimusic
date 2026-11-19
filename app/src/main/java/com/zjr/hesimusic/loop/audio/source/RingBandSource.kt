package com.zjr.hesimusic.loop.audio.source

import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource

/**
 * 方案 A 的环带 MediaSource 工厂。
 *
 * 只负责组装「一次性连续渲染 -> 环 -> 取模 Extractor」；模式开关仍在调用方。
 */
@UnstableApi
object RingBandSource {

    /** L*bytesPerFrame <= heap/10 才启用环带，否则回退旧分块实现。 */
    fun canUse(timeline: LoopingPcmTimeline, maxMemoryBytes: Long): Boolean {
        val bytes = timeline.loopFrames * timeline.bytesPerFrame
        return bytes > 0L && bytes <= maxMemoryBytes / 10L
    }

    fun create(
        item: MediaItem,
        timeline: LoopingPcmTimeline,
        provider: PcmRangeProvider,
        logger: (String) -> Unit = {},
        onSeamOutputFrames: ((Long) -> Unit)? = null,
        testDecoder: RingBandStreamDecoder? = null,
    ): MediaSource {
        val decoder = testDecoder ?: run {
            val real = provider as? PcmDecoderRangeProvider
                ?: throw IllegalArgumentException("RingBand requires PcmDecoderRangeProvider")
            MediaCodecRingBandDecoder(
                path = real.path,
                sampleRate = timeline.sampleRate,
                channels = timeline.channels,
                logger = logger,
            )
        }
        val ring = LoopRing(timeline.sampleRate, timeline.channels, timeline.loopFrames)
        val startOffset = Math.floorMod(
            timeline.frameAtTimeline(0L) - timeline.aFrame,
            timeline.loopFrames,
        )
        val reader = RingBandReader(ring, startOffset)
        val renderer = RingBandRenderer(timeline, decoder, ring, logger)
        val extractor = RingBandExtractor(
            timeline = timeline,
            ring = ring,
            reader = reader,
            renderer = renderer,
            logger = logger,
            onSeamOutputFrames = onSeamOutputFrames,
        )
        val extractors = ExtractorsFactory { arrayOf<Extractor>(extractor) }
        val factory = ProgressiveMediaSource.Factory(
            LoopingPcmDataSource.Factory(),
            extractors,
        )
        renderer.start()
        return RingBandMediaSource(
            delegate = factory.createMediaSource(item),
            extractor = extractor,
            ring = ring,
        )
    }
}
