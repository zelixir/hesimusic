package com.zjr.hesimusic.loop.audio.source

/**
 * RingBand 的流式 PCM 解码器抽象。
 *
 * 真实实现使用一次 MediaExtractor+MediaCodec 会话；测试用 fake 分片回调。
 * 实现应只输出 [startUs,endUs) 的 PCM16 交错数据（可内部用 preroll 解码后丢弃）。
 *
 * 00022/00023：环带支持跳到任意 offset 开始填充，所以一次 [stream] 可能被
 * [cancel] 中途打断（renderer 收到新的 seek 目标）；实现必须在几十毫秒内返回。
 */
interface RingBandStreamDecoder {
    fun stream(
        startUs: Long,
        endUs: Long,
        prerollUs: Long,
        onPcm: (data: ByteArray, size: Int) -> Unit,
    )

    /** 请求打断当前 [stream]；下一次 [stream] 调用会重新开始。 */
    fun cancel() {}

    fun close()
}
