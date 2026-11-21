package com.zjr.hesimusic.loop.audio.source

import com.zjr.hesimusic.loop.analysis.Pcm16Segment
import com.zjr.hesimusic.loop.analysis.PcmDecoder
import kotlinx.coroutines.runBlocking

/**
 * 生产用 [PcmRangeProvider]：把既有的 [PcmDecoder.decodePcm16Range] 适配成同步接口。
 *
 * 只允许在**解码 worker 线程**调用（[runBlocking] 会阻塞当前线程）；不要在播放线程调用。
 * 现有实现每次范围解码都会创建/释放 MediaExtractor+MediaCodec；原型可接受，
 * 生产优化方向是常驻解码会话（见 docs/loop-data-source-design.md）。
 */
class PcmDecoderRangeProvider(
    private val decoder: PcmDecoder,
    val path: String,
) : PcmShortRangeProvider {

    override fun decode(startUs: Long, endUs: Long): Pcm16Segment = runBlocking {
        decoder.decodePcm16Range(path, startUs, endUs)
    }

    /** 尾/前奏最后一块：一次解码拿真实可用帧，不因越过编码末尾抛异常。 */
    override fun decodeAllowShort(startUs: Long, endUs: Long): Pcm16Segment = runBlocking {
        decoder.decodePcm16RangeAllowShort(path, startUs, endUs)
    }
}
