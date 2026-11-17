package com.zjr.hesimusic.loop.audio.source

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.source.SampleStream
import java.io.IOException
import java.nio.ByteOrder

/**
 * 循环 PCM 数据源的 [SampleStream] 实现。
 *
 * 直接读 [LoopingPcmSourceCore] 的块缓存，不经过 [androidx.media3.exoplayer.source.SampleQueue]，
 * 因此块缓存就是唯一的 PCM 缓冲：不会出现"块缓存 + SampleQueue"双份内存，也不会在 seek 时
 * 需要把已入队的数据再搬一遍。对外 PTS 完全由 core 生成。
 */
@UnstableApi
class LoopingPcmSampleStream(
    private val core: LoopingPcmSourceCore,
    private val format: Format,
) : SampleStream {

    private var formatSent = false

    override fun isReady(): Boolean = core.isCurrentBlockReady()

    override fun maybeThrowError() {
        core.error()?.let { throw IOException(it) }
    }

    override fun readData(
        formatHolder: FormatHolder,
        buffer: DecoderInputBuffer,
        readFlags: Int,
    ): Int {
        if (!formatSent || (readFlags and SampleStream.FLAG_REQUIRE_FORMAT) != 0) {
            formatHolder.format = format
            formatSent = true
            return C.RESULT_FORMAT_READ
        }

        val peek = (readFlags and SampleStream.FLAG_PEEK) != 0
        val omitData = (readFlags and SampleStream.FLAG_OMIT_SAMPLE_DATA) != 0
        val result = core.read(READ_CHUNK_BYTES, advance = !peek) ?: return C.RESULT_NOTHING_READ

        buffer.clear()
        buffer.timeUs = result.ptsUs
        buffer.setFlags(C.BUFFER_FLAG_KEY_FRAME)
        if (!omitData) {
            buffer.ensureSpaceForWrite(result.data.size)
            val data = buffer.data ?: return C.RESULT_NOTHING_READ
            data.order(ByteOrder.LITTLE_ENDIAN)
            data.put(result.data)
            buffer.flip()
        }
        return C.RESULT_BUFFER_READ
    }

    override fun skipData(positionUs: Long): Int = core.skipToPts(positionUs)

    /** 数据源内部 seek 后，让渲染器下一次重新拿一次 Format。 */
    fun onSeek() {
        formatSent = false
    }

    private companion object {
        /** 单次交给渲染器的 PCM 上限：64KB，约 42ms@48k/2ch/16bit。 */
        const val READ_CHUNK_BYTES: Int = 64 * 1024
    }
}
