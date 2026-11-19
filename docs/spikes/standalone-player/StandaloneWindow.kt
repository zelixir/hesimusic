package com.zjr.hesimusic.loop.audio.standalone

/**
 * 独立循环内核的纯逻辑窗口描述（不依赖 Android / media3）。
 *
 * 口径与生产 [com.zjr.hesimusic.loop.audio.source.LoopingPcmTimeline] 一致：
 * - A/B 是媒体时间 µs；
 * - 环的起点是 A 之后第一个整帧 `aFrame = ceil(A*sr/1e6)`；
 * - 环的右开边界是 `bFrame = ceil(B*sr/1e6)`；
 * - 环帧数 `frames = bFrame - aFrame`，因此 B->A 折返是**帧级精确**的。
 */
data class StandaloneWindow(
    val path: String,
    val sampleRate: Int,
    val channels: Int,
    val startUs: Long,
    val endUs: Long,
) {
    init {
        require(path.isNotBlank()) { "path must not be blank" }
        require(sampleRate > 0) { "sampleRate must be > 0" }
        require(channels in 1..2) { "only mono/stereo is supported by the demo, channels=$channels" }
        require(startUs >= 0L) { "startUs must be >= 0" }
        require(endUs > startUs) { "endUs must be > startUs" }
    }

    val bytesPerFrame: Int = channels * 2

    /** A 之后第一个整帧的绝对帧号。 */
    val aFrame: Long = frameCeil(startUs, sampleRate)

    /** B 之后第一个整帧的绝对帧号（右开）。 */
    val bFrame: Long = frameCeil(endUs, sampleRate)

    /** 环内帧数。 */
    val frames: Long = bFrame - aFrame

    /** 整环 PCM 字节数（Long，避免 10 分钟窗口溢出 Int）。 */
    val ringBytes: Long = frames * bytesPerFrame.toLong()

    /** 一轮的帧级时长（µs），与真实输出帧数严格对应。 */
    val loopDurationUs: Long = frames * 1_000_000L / sampleRate

    init {
        require(frames > 0L) { "loop window has no full frame: A=$startUs B=$endUs" }
    }

    /** 环内偏移 ms -> 环内帧号（clamp 到 [0, frames-1]）。 */
    fun frameForOffsetMs(offsetMs: Long): Long {
        if (offsetMs <= 0L) return 0L
        val absolute = startUs + offsetMs * 1_000L
        val frame = frameCeil(absolute, sampleRate) - aFrame
        return frame.coerceIn(0L, frames - 1L)
    }

    /** 环内帧号 -> 环内偏移 ms（显示用，向下取整）。 */
    fun offsetMsForFrame(frame: Long): Long {
        val f = Math.floorMod(frame, frames)
        return f * 1_000L / sampleRate
    }

    companion object {
        /** 与 [com.zjr.hesimusic.loop.analysis.Pcm16Frames.frameAtOrAfterUs] 同口径。 */
        fun frameCeil(us: Long, sampleRate: Int): Long {
            if (us <= 0L || sampleRate <= 0) return 0L
            return (us * sampleRate + 999_999L) / 1_000_000L
        }
    }
}

/** 内存策略：整环 vs 分块流式。 */
enum class StandaloneMode {
    FULL_RING,
    CHUNKED,
}

/**
 * 按“环字节数 <= 堆上限/10”选择整环；否则降级为分块流式（不保留整窗 PCM）。
 *
 * 这里只做纯逻辑判定，真正的分块实现在 [StandaloneChunkedEngine]。
 */
object StandalonePlanner {
    const val HEAP_DIVISOR: Int = 10

    fun decideMode(ringBytes: Long, heapMaxBytes: Long): StandaloneMode {
        if (heapMaxBytes <= 0L) return StandaloneMode.CHUNKED
        val budget = heapMaxBytes / HEAP_DIVISOR
        if (ringBytes <= budget && ringBytes <= Int.MAX_VALUE.toLong() - 8L) {
            return StandaloneMode.FULL_RING
        }
        return StandaloneMode.CHUNKED
    }
}
