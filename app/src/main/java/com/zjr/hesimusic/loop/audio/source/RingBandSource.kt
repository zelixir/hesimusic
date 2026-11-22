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
        val reader = RingBandReader(ring, timeline.readerStartOffsetFrames())
        val renderer = RingBandRenderer(timeline, decoder, ring, logger)
        // issue 00029：前奏/尾奏改用**常驻解码会话**，不再每块新建 codec。
        // 只有真实文件路径 + PcmDecoderRangeProvider 才有常驻能力；content:// 与测试假
        // provider 继续走旧的逐块路径（decodePrerollUs=250ms）。
        val preludeResident = residentProvider(provider, timeline, logger)
        val tailResident = residentProvider(provider, timeline, logger)
        // issue 00028：整曲时间轴时，[0,A) 前奏与 (B,dur] 尾奏按需块解码（不整段进内存）。
        val preludeReader = if (timeline.wholeTimeline && timeline.aFrame > 0L) {
            PcmRangeBlockReader(
                provider = preludeResident ?: provider,
                startFrame = 0L,
                endFrameExclusive = timeline.aFrame,
                sampleRate = timeline.sampleRate,
                channels = timeline.channels,
                decodePrerollUs = if (preludeResident != null) 0L else PcmRangeBlockReader.DECODE_PREROLL_US,
                logger = logger,
            )
        } else {
            null
        }
        val tailReader = if (timeline.wholeTimeline && timeline.durationFrames > timeline.bFrame) {
            PcmRangeBlockReader(
                provider = tailResident ?: provider,
                startFrame = timeline.bFrame,
                endFrameExclusive = timeline.durationFrames,
                sampleRate = timeline.sampleRate,
                channels = timeline.channels,
                prefetchBlocks = 4,
                prefetchOnInit = false,
                decodePrerollUs = if (tailResident != null) 0L else PcmRangeBlockReader.DECODE_PREROLL_US,
                logger = logger,
            )
        } else {
            null
        }
        val extractor = RingBandExtractor(
            timeline = timeline,
            ring = ring,
            reader = reader,
            renderer = renderer,
            logger = logger,
            onSeamOutputFrames = onSeamOutputFrames,
            preludeReader = preludeReader,
            tailReader = tailReader,
        )
        val extractors = ExtractorsFactory { arrayOf<Extractor>(extractor) }
        val factory = ProgressiveMediaSource.Factory(
            LoopingPcmDataSource.Factory(),
            extractors,
        )
        // 00029：**前奏存在时不要 eager start**。环带填充会连续解码几十秒音频，
        // 与前奏块解码抢 CPU；第一次 requestFillAt（离 A 2s / 进入循环 / seek）
        // 时才启动渲染线程。没有前奏的窗口（A=0）或 legacy 预览仍立即启动，保持旧语义。
        if (!timeline.wholeTimeline || timeline.aFrame <= 0L) {
            renderer.start()
        }
        val prewarmToken = RingBandPrewarm.register { timelineUs ->
            extractor.prewarmTimelineUs(timelineUs)
        }
        return RingBandMediaSource(
            delegate = factory.createMediaSource(item),
            extractor = extractor,
            ring = ring,
            prewarmToken = prewarmToken,
        )
    }

    /**
     * 能开常驻会话就开；content:// 走旧的 PcmDecoder（MediaExtractor 需要 Context）。
     * 失败（设备 decoder 实例受限等）时返回 null，调用方自动回退旧路径，不影响播放。
     */
    private fun residentProvider(
        provider: PcmRangeProvider,
        timeline: LoopingPcmTimeline,
        logger: (String) -> Unit,
    ): PcmRangeProvider? {
        val real = provider as? PcmDecoderRangeProvider ?: return null
        if (real.path.startsWith("content:")) {
            logger("RingBand resident provider skipped reason=content-uri path=" + real.path)
            return null
        }
        return PcmResidentRangeProvider(
            path = real.path,
            expectedSampleRate = timeline.sampleRate,
            expectedChannels = timeline.channels,
            logger = logger,
        )
    }
}
