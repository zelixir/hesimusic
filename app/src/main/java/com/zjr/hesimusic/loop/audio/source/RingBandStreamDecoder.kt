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

    /**
     * 最近一次 [stream] 是否解到了**源尾**（EOS）：抽取器已无更多样本，或解码器输出了 EOS 帧。
     *
     * 00024：窗口 B 落在文件尾（整曲窗口/自由试听）时，容器给的总时长通常比最后一个编码样本
     * 略长，解码器在目标位置之后**没有可输出帧**（真机日志 `decode short emitted=0 target=29`）。
     * 这不是解码故障——渲染器据此把剩余帧按静音补齐（标记 tailTrimmed）并让播放继续。
     * 默认 false：只实现"正常能出帧"的假解码器无需关心这个信号。
     */
    fun lastStreamReachedEndOfStream(): Boolean = false
}
