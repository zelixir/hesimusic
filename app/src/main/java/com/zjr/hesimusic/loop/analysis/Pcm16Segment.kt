package com.zjr.hesimusic.loop.analysis

/** 环缓冲预填用的 PCM16 交错片段（小端，[channels] 声道，[sampleRate] Hz，[frames] 帧）。 */
class Pcm16Segment(
    val data: ByteArray,
    val sampleRate: Int,
    val channels: Int,
    val frames: Long,
) {
    val bytesPerFrame: Int get() = channels * 2

    /**
     * 只保留前 [frames] 帧（裁剪片尾多余整帧；[frames] >= 本段帧数时原样返回）。
     *
     * 预填按环长度裁剪时用：区间长度换算的“严格帧数”可能比环帧数多 1。
     */
    fun takeFrames(frames: Long): Pcm16Segment {
        if (frames >= this.frames) return this
        if (frames <= 0L) return Pcm16Segment(ByteArray(0), sampleRate, channels, 0L)
        val bytes = (frames * bytesPerFrame).toInt()
        return Pcm16Segment(data.copyOf(bytes), sampleRate, channels, frames)
    }

    /**
     * 丢弃前 [frames] 帧（跳过播放捕获已覆盖的前缀；<=0 原样返回）。
     */
    fun dropFrames(frames: Long): Pcm16Segment {
        if (frames <= 0L) return this
        if (frames >= this.frames) return Pcm16Segment(ByteArray(0), sampleRate, channels, 0L)
        val offsetBytes = (frames * bytesPerFrame).toInt()
        return Pcm16Segment(
            data = data.copyOfRange(offsetBytes, data.size),
            sampleRate = sampleRate,
            channels = channels,
            frames = this.frames - frames,
        )
    }
}

/**
 * 预填解码的纯帧号算术（无 Android 依赖，可 JVM 单测）。
 *
 * 口径：帧 f 的媒体时间(相对锚点) = floor(f * 1e6 / sampleRate)。因此“第一个 PTS ≥ us 的帧”
 * 是 ceil(us * sampleRate / 1e6)；区间 [startUs, endUs) 的严格帧数（含末尾整帧）是
 * ceil((endUs − startUs) * sampleRate / 1e6)。
 */
object Pcm16Frames {

    const val US_PER_SECOND: Long = 1_000_000L

    /** 严格帧数 = ceil(durationUs * sampleRate / 1e6)；非正输入返回 0。 */
    fun framesForUsCeil(durationUs: Long, sampleRate: Int): Long {
        if (durationUs <= 0L || sampleRate <= 0) return 0L
        return (durationUs * sampleRate + US_PER_SECOND - 1L) / US_PER_SECOND
    }

    /** 相对锚点帧 0 的帧号 = ceil(offsetUs * sampleRate / 1e6)；offsetUs <= 0 时为 0。 */
    fun frameAtOrAfterUs(offsetUs: Long, sampleRate: Int): Long {
        if (offsetUs <= 0L || sampleRate <= 0) return 0L
        return (offsetUs * sampleRate + US_PER_SECOND - 1L) / US_PER_SECOND
    }

    /**
     * 单个解码输出 buffer 里应保留的流内帧区间（左闭右开）。
     *
     * @param bufferStartFrame 本 buffer 首帧的流内帧号
     * @param bufferFrames 本 buffer 的帧数
     * @param keepStartFrame 需要保留的第一个流内帧号
     * @param keepFrames 需要保留的总帧数（严格帧数）
     * @return null = 本 buffer 一帧都不需要（尚未追上起点，或已经收满）
     */
    fun clipBuffer(
        bufferStartFrame: Long,
        bufferFrames: Long,
        keepStartFrame: Long,
        keepFrames: Long,
    ): LongRange? {
        if (bufferFrames <= 0L || keepFrames <= 0L) return null
        val keepEnd = keepStartFrame + keepFrames
        val from = maxOf(bufferStartFrame, keepStartFrame)
        val to = minOf(bufferStartFrame + bufferFrames, keepEnd)
        if (to <= from) return null
        return from until to
    }
}
