package com.zjr.hesimusic.loop.audio.source

import com.zjr.hesimusic.loop.analysis.Pcm16Segment

/**
 * 循环 PCM 数据源的解码后端契约（纯 JVM，可测试）。
 *
 * 生产实现 = [PcmDecoderRangeProvider]（包 [com.zjr.hesimusic.loop.analysis.PcmDecoder]）；
 * 单测实现 = 合成 PCM 生成器。
 *
 * 对齐契约（与 [com.zjr.hesimusic.loop.analysis.PcmDecoder.decodePcm16Range] 一致）：
 * - 返回的 PCM 从 `ceil(startUs * sampleRate / 1e6)` 这一帧开始；
 * - 返回帧数至少覆盖 `[ceil(startUs*sr/1e6), ceil(endUs*sr/1e6))`，可多 1 帧；
 * - 16bit 小端交错，声道数与采样率必须与 [PcmTrackFormat] 一致。
 */
fun interface PcmRangeProvider {
    fun decode(startUs: Long, endUs: Long): Pcm16Segment
}

/** 音轨格式（循环数据源只需要采样率与声道数）。 */
data class PcmTrackFormat(
    val sampleRate: Int,
    val channels: Int,
) {
    init {
        require(sampleRate > 0) { "sampleRate must be > 0" }
        require(channels > 0) { "channels must be > 0" }
    }

    val bytesPerFrame: Int get() = channels * 2
}

/**
 * 块缓存 key。
 *
 * A/B 变化必须视为新 key（新窗口 = 新块网格 = 旧块不可复用）。
 *
 * @param loopStartSamples 窗口起点 A 对应的**绝对媒体帧号** `ceil(A*sr/1e6)`
 * @param loopEndSamples 窗口终点 B 对应的绝对媒体帧号 `ceil(B*sr/1e6)`
 */
data class PcmBlockCacheKey(
    val songId: Long,
    val loopStartSamples: Long,
    val loopEndSamples: Long,
    val sampleRate: Int,
    val channels: Int,
    /** 原始 A（µs）；A/B 变化但帧号相同也必须换 key。 */
    val loopStartUs: Long,
    /** 原始 B（µs）。 */
    val loopEndUs: Long,
)
