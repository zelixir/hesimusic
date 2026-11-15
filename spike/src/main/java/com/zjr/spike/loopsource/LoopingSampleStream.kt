package com.zjr.spike.loopsource

import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.source.SampleStream

/**
 * 包住“当前轮”的子 SampleStream，并把读请求转给 period 处理轮次切换 / 时间戳改写。
 * 对 renderer 而言它永远是同一个 SampleStream（不触发流切换）。
 */
@UnstableApi
class LoopingSampleStream(
    private val period: LoopingWindowPeriod,
    private val index: Int,
) : SampleStream {

    @Volatile
    var delegate: SampleStream? = null

    override fun isReady(): Boolean = period.isStreamReady(index)

    override fun maybeThrowError() {
        period.maybeThrowStreamError(index)
    }

    override fun readData(formatHolder: FormatHolder, buffer: DecoderInputBuffer, flags: Int): Int =
        period.readData(index, formatHolder, buffer, flags)

    override fun skipData(positionUs: Long): Int = period.skipData(index, positionUs)
}