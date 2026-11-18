package com.zjr.hesimusic.loop.audio.source

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 环带渲染器：后台单线程调用一次连续解码 [A,B)，把 PCM 顺序 append 进环。
 *
 * 使用现有 PcmRangeProvider 的唯一一次长区间调用，避免每 1s 重启 MediaCodec。
 * 环未满时 Extractor 只返回 CONTINUE；一旦 filledFrames > 0 即可按高水位取数。
 */
class RingBandRenderer(
    private val timeline: LoopingPcmTimeline,
    private val provider: PcmRangeProvider,
    private val ring: LoopRing,
    private val logger: (String) -> Unit = {},
) {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "RingBandRender").apply { isDaemon = true }
    }
    private val started = AtomicBoolean(false)

    @Volatile
    var releaseRequested: Boolean = false
        private set

    @Volatile
    var error: String? = null
        private set

    fun start() {
        if (!started.compareAndSet(false, true)) return
        try {
            executor.execute {
                try {
                    if (releaseRequested) return@execute
                    logger(
                        "RingBand render start [" + timeline.loopStartUs + "," +
                            timeline.loopEndUs + ") frames=" + timeline.loopFrames,
                    )
                    val segment = provider.decode(timeline.loopStartUs, timeline.loopEndUs)
                    if (releaseRequested) return@execute
                    if (segment.sampleRate != timeline.sampleRate ||
                        segment.channels != timeline.channels
                    ) {
                        error = "format mismatch sr=" + segment.sampleRate +
                            "/" + timeline.sampleRate + " ch=" + segment.channels +
                            "/" + timeline.channels
                        logger("RingBand render error: " + error)
                        return@execute
                    }
                    val frames = minOf(segment.frames, ring.frames)
                    if (frames < ring.frames) {
                        error = "short render got=" + frames + " want=" + ring.frames
                        logger("RingBand render error: " + error)
                        return@execute
                    }
                    val bytes = (frames * ring.bytesPerFrame).toInt()
                    val written = ring.append(segment.data, 0)
                    logger(
                        "RingBand render done frames=" + written +
                            " bytes=" + bytes + " decodeFrames=" + segment.frames,
                    )
                } catch (t: Throwable) {
                    error = t.javaClass.simpleName + ": " + t.message
                    logger("RingBand render crash: " + error)
                }
            }
        } catch (t: Throwable) {
            error = "submit failed: " + t.message
            logger("RingBand render error: " + error)
        }
    }

    fun release() {
        releaseRequested = true
        executor.shutdownNow()
    }
}
