package com.zjr.hesimusic.loop.audio.source

/**
 * RingBand 的流式 PCM 解码器抽象。
 *
 * 真实实现使用一次 MediaExtractor+MediaCodec 会话；测试用 fake 分片回调。
 * 实现应只输出 [startUs,endUs) 的 PCM16 交错数据（可内部用 preroll 解码后丢弃）。
 */
interface RingBandStreamDecoder {
    fun stream(
        startUs: Long,
        endUs: Long,
        prerollUs: Long,
        onPcm: (data: ByteArray, size: Int) -> Unit,
    )

    fun close()
}
