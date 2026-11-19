package com.zjr.hesimusic.loop.audio.standalone

/** 一次启动的首声/解码指标（绝对 elapsedRealtimeNanos，调用方自行做差）。 */
data class StandaloneStartMetrics(
    val mode: StandaloneMode,
    val window: StandaloneWindow,
    val ringBytes: Long,
    val heapMaxBytes: Long,
    val bufferBytes: Int,
    val decodeStartNs: Long,
    val firstChunkNs: Long,
    val prefillReadyNs: Long,
    val playCallNs: Long,
    val firstWriteNs: Long,
    val firstHeadNs: Long,
)

/** 一次 seek 的延迟指标（ms，-1 表示未观测到）。 */
data class StandaloneSeekMetrics(
    val targetOffsetMs: Long,
    val targetFrame: Long,
    val decodeWaitMs: Long,
    val firstWriteMs: Long,
    val firstHeadMs: Long,
    val totalMs: Long,
)

/**
 * 独立循环内核的统一入口。
 *
 * 实现只有两种：
 * - [FullRingLoopEngine]：整段 [A,B) 解成环，取模播放；窗口 <= 堆/10 时使用；
 * - [ChunkedLoopEngine]：固定容量 PCM 队列 + 顺序分块解码；窗口更大时降级使用。
 */
interface StandalonePlayback {
    val window: StandaloneWindow
    val mode: StandaloneMode

    /** 解码到首声门槛后开始播放；返回启动指标（首声可能稍后才出现，用 [firstHeadNs] 轮询）。 */
    fun prepareAndPlay(): StandaloneStartMetrics

    /** 跳转到窗口内 [offsetMs]；返回 seek 延迟指标。 */
    fun seekToOffsetMs(offsetMs: Long): StandaloneSeekMetrics

    fun pause()
    fun resume()
    fun isPlaying(): Boolean
    fun positionMs(): Long
    fun underrunCount(): Int
    fun firstHeadNs(): Long
    fun firstWriteNs(): Long
    fun playCallNs(): Long
    fun decodeError(): String?
    fun release()
}

/** 根据窗口大小/堆上限选择整环或分块。 */
object StandaloneEngineFactory {
    fun create(
        path: String,
        startUs: Long,
        endUs: Long,
        prefillMs: Long = 400L,
        log: (String) -> Unit = {},
    ): StandalonePlayback {
        val probe = StandaloneRangeDecoder.probe(path)
        val window = StandaloneWindow(path, probe.sampleRate, probe.channels, startUs, endUs)
        val heapMax = Runtime.getRuntime().maxMemory()
        return when (StandalonePlanner.decideMode(window.ringBytes, heapMax)) {
            StandaloneMode.FULL_RING -> FullRingLoopEngine(window, prefillMs, log)
            StandaloneMode.CHUNKED -> ChunkedLoopEngine(window, prefillMs, log)
        }
    }
}
