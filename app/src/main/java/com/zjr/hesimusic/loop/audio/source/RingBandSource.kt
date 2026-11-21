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

    /**
     * **主播放器**的环预算分母：窗口 PCM 字节数 <= heap/6 才建环。
     *
     * 00027：原为 heap/10（402MB -> 40.27MB），Shiny Up 这类 238s 窗口（42.0MB）刚好超出
     * 1.7MB，主播放器回退成普通整曲播放；但 UI 仍按窗口折返进度条 -> "显示 A、声音在尾奏"。
     * 主播放器 SOURCE 路线没有整曲预填/解码的额外大对象（环就是唯一大对象），与预览对齐到
     * heap/6（402MB -> 67MB）后，42MB 能真正建环。
     *
     * 与 [com.zjr.hesimusic.service.LoopSourceFallback.RING_BUDGET_DIVISOR] **必须同口径**；
     * 改这里时两处一起改（见 RingBandPreviewBudgetTest）。
     */
    const val BUDGET_DIVISOR_MAIN: Long = 6L

    /**
     * **预览（分析页试听）** 的环预算分母：heap/6。
     *
     * 00022：Shiny Up 这类长窗口（A=23.301s B=261.457s，L=238.156s@44.1kHz = 42,010,716B）
     * 曾在 heap/10（402MB -> 40.27MB）下判超预算，回退到分块实现（1 秒块 + 块缓存重建），
     * 点接缝检查要 1~2 秒才出声。预览路径没有整曲解码/正式播放的预填开销，环带就是唯一大对象：
     * 42MB 仍不到堆的 11%，放宽到 1/6（402MB -> 67MB）留足余量；超过 1/6 的窗口依旧回退，
     * 保证"环带永远不是 OOM 的来源"。
     *
     * 00027 起主播放器也统一为 heap/6（[BUDGET_DIVISOR_MAIN]），两档一致。
     */
    const val BUDGET_DIVISOR_PREVIEW: Long = 6L

    /**
     * 环预算字节数 = heap / [divisor]。
     *
     * 无堆信息/非法分母返回 0（= 拒绝建环），避免得到"0 字节也算通过"的边界。
     */
    fun budgetBytes(maxMemoryBytes: Long, divisor: Long = BUDGET_DIVISOR_MAIN): Long =
        if (maxMemoryBytes <= 0L || divisor <= 0L) 0L else maxMemoryBytes / divisor

    /** L*bytesPerFrame <= heap/divisor 才启用环带，否则回退旧分块实现。 */
    fun canUse(
        timeline: LoopingPcmTimeline,
        maxMemoryBytes: Long,
        divisor: Long = BUDGET_DIVISOR_MAIN,
    ): Boolean {
        val bytes = timeline.loopFrames * timeline.bytesPerFrame
        return bytes > 0L && bytes <= budgetBytes(maxMemoryBytes, divisor)
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
