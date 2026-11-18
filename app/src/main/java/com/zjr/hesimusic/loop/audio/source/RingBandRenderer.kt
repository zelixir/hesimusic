package com.zjr.hesimusic.loop.audio.source

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 环带渲染器：后台单线程按 1s 块顺序解码 [A,B)，每块 append 进环。
 *
 * 这样首 2 块就绪即可让 Extractor 出样本；剩余块继续在后台填环。
 * 使用现有 PcmRangeProvider 的块区间接口；块边界由 T0/T1 门禁和真机测量兜底。
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
                var stopReason = "DONE"
                var stopBlock = -1
                try {
                    RingBandLog.i(
                        "RingBand render start [" + timeline.loopStartUs + "," +
                            timeline.loopEndUs + ") frames=" + timeline.loopFrames +
                            " blocks=" + timeline.blockCount,
                    )
                    for (block in 0 until timeline.blockCount) {
                        stopBlock = block
                        if (releaseRequested) {
                            stopReason = "RELEASE"
                            break
                        }
                        val startUs = timeline.blockDecodeStartUs(block)
                        val endUs = timeline.blockDecodeEndUs(block)
                        val expected = timeline.blockFrames(block)
                        val segment = try {
                            provider.decode(startUs, endUs)
                        } catch (t: Throwable) {
                            error = t.javaClass.simpleName + ": " + t.message
                            stopReason = "DECODE_EXCEPTION"
                            break
                        }
                        if (releaseRequested) {
                            stopReason = "RELEASE"
                            break
                        }
                        if (segment.sampleRate != timeline.sampleRate ||
                            segment.channels != timeline.channels
                        ) {
                            error = "format mismatch block=" + block +
                                " sr=" + segment.sampleRate + "/" + timeline.sampleRate +
                                " ch=" + segment.channels + "/" + timeline.channels
                            stopReason = "FORMAT"
                            break
                        }
                        if (segment.frames < expected) {
                            error = "short render block=" + block +
                                " got=" + segment.frames + " want=" + expected
                            stopReason = "SHORT"
                            break
                        }
                        val trimmed = segment.takeFrames(expected)
                        val appended = ring.append(trimmed.data, 0)
                        if (appended < expected) {
                            error = "ring append short block=" + block +
                                " wrote=" + appended + " want=" + expected
                            stopReason = "RING_SHORT"
                            break
                        }
                        RingBandLog.i(
                            "RingBand append block=" + block +
                                " filledFrames=" + ring.renderedFrames +
                                " expectedFrames=" + expected,
                        )
                        logger(
                            "RingBand append block=" + block +
                                " filledFrames=" + ring.renderedFrames,
                        )
                    }
                } catch (t: Throwable) {
                    error = t.javaClass.simpleName + ": " + t.message
                    stopReason = "CRASH"
                } finally {
                    val message = "RingBand render stop reason=" + stopReason +
                        " block=" + stopBlock +
                        " filledFrames=" + ring.renderedFrames +
                        " err=" + (error ?: "-")
                    if (stopReason == "DONE") RingBandLog.i(message) else RingBandLog.w(message)
                    logger(message)
                }
            }
        } catch (t: Throwable) {
            error = "submit failed: " + t.message
            RingBandLog.w("RingBand render stop reason=SUBMIT block=-1 err=" + error)
        }
    }

    fun release() {
        releaseRequested = true
        executor.shutdownNow()
    }
}
