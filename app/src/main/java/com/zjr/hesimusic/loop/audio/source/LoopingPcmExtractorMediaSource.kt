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
    ): MediaSource {
        val core = LoopingPcmSourceCore(timeline, provider)
        val extractors = ExtractorsFactory {
            arrayOf<Extractor>(LoopingPcmExtractor(core, timeline, logger))
        }
        val factory = ProgressiveMediaSource.Factory(LoopingPcmDataSource.Factory(), extractors)
        return factory.createMediaSource(item)
    }
}
