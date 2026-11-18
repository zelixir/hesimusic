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

    fun create(
        item: MediaItem,
        timeline: LoopingPcmTimeline,
        provider: PcmRangeProvider,
        logger: (String) -> Unit = {},
        prefetchBlocks: Int = SOURCE_PREFETCH_BLOCKS,
        onSeamOutputFrames: ((Long) -> Unit)? = null,
    ): MediaSource {
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
