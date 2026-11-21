package com.zjr.hesimusic.loop.audio.source

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import java.util.concurrent.Executors

/**
 * 虚拟容器 DataSource：Extractor 完全不读取真实字节，所有 PCM 来自块缓存。
 * 保留 unknown length，让 ProgressiveMediaSource 按动态/未知时长处理。
 */
@UnstableApi
class LoopingPcmDataSource : BaseDataSource(/* isNetwork= */ false) {

    private var uri: Uri? = null
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        opened = true
        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        C.RESULT_END_OF_INPUT

    override fun getUri(): Uri? = uri

    override fun close() {
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    class Factory : DataSource.Factory {
        override fun createDataSource(): DataSource = LoopingPcmDataSource()
    }
}

/** 用自定义 Extractor + ProgressiveMediaSource 构造循环 PCM 源。 */
@UnstableApi
object LoopingPcmExtractorMediaSource {

    /**
     * @param ringBudgetDivisor 环预算分母（窗口 PCM <= heap/divisor 才建环）。
     *   默认 [RingBandSource.BUDGET_DIVISOR_MAIN]（heap/10，主播放器口径）；
     *   预览（分析页试听）传 [RingBandSource.BUDGET_DIVISOR_PREVIEW]（heap/6），
     *   让 Shiny Up 这类 ~42MB 的长窗口也能走环带而不是回退 1 秒块实现（00022）。
     */
    fun create(
        item: MediaItem,
        timeline: LoopingPcmTimeline,
        provider: PcmRangeProvider,
        logger: (String) -> Unit = {},
        prefetchBlocks: Int = SOURCE_PREFETCH_BLOCKS,
        onSeamOutputFrames: ((Long) -> Unit)? = null,
        ringBudgetDivisor: Long = RingBandSource.BUDGET_DIVISOR_MAIN,
    ): MediaSource {
        val heap = Runtime.getRuntime().maxMemory()
        val ringProvider = provider as? PcmDecoderRangeProvider
        val bandBytes = timeline.loopFrames * timeline.bytesPerFrame
        val budget = RingBandSource.budgetBytes(heap, ringBudgetDivisor)
        if (RingBandSource.canUse(timeline, heap, ringBudgetDivisor) && ringProvider != null) {
            logger(
                "RingBand select bandBytes=" + bandBytes +
                    " heap=" + heap +
                    " budget=" + budget +
                    " divisor=" + ringBudgetDivisor,
            )
            return RingBandSource.create(
                item = item,
                timeline = timeline,
                provider = provider,
                logger = logger,
                onSeamOutputFrames = onSeamOutputFrames,
            )
        }
        logger(
            "RingBand fallback reason=" + (
                if (ringProvider == null) "PROVIDER" else "SIZE"
            ) + " bandBytes=" + bandBytes +
                " budget=" + budget +
                " divisor=" + ringBudgetDivisor,
        )
        val core = LoopingPcmSourceCore(
            timeline = timeline,
            provider = provider,
            prefetchBlocks = prefetchBlocks,
        ).also {
            it.onSeamOutputFrames = onSeamOutputFrames
        }
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "LoopPcmPrefetch").apply { isDaemon = true }
        }
        val prefetcher = LoopPcmPrefetcher(
            core = core,
            executor = executor,
            targetBlocks = prefetchBlocks,
            logger = logger,
        )
        val extractors = ExtractorsFactory {
            arrayOf<Extractor>(LoopingPcmExtractor(core, timeline, logger, prefetcher))
        }
        val factory = ProgressiveMediaSource.Factory(LoopingPcmDataSource.Factory(), extractors)
        return factory.createMediaSource(item)
    }

    /** 6 块 ≈ 6s；LoadControl max 12s 留出解码抖动余量。 */
    const val SOURCE_PREFETCH_BLOCKS: Int = 6
}
