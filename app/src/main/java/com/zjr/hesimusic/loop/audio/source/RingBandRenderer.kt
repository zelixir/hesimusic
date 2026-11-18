package com.zjr.hesimusic.loop.audio.source

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 环带渲染器：后台单线程用**一次连续解码会话**从 A-preroll 解到 B，
 * 每收到一段 PCM 就 append 进环；首 2 块就绪后 Extractor 即可出样本。
 */
class RingBandRenderer(
    private val timeline: LoopingPcmTimeline,
    private val decoder: RingBandStreamDecoder,
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
                var lastLoggedBlock = -1
                try {
                    RingBandLog.i(
                        "RingBand render start [" + timeline.loopStartUs + "," +
                            timeline.loopEndUs + ") frames=" + timeline.loopFrames +
                            " blocks=" + timeline.blockCount,
                    )
                    RingBandLog.i("RingBand decode session open prerollMs=" + PREROLL_US / 1000L)
                    decoder.stream(
                        startUs = timeline.loopStartUs,
                        endUs = timeline.loopEndUs,
                        prerollUs = PREROLL_US,
                    ) { data, size ->
                        if (releaseRequested) return@stream
                        val frames = size / timeline.bytesPerFrame
                        if (frames <= 0) return@stream
                        val appended = ring.append(data, 0)
                        if (appended < frames) {
                            error = "ring append short wrote=" + appended + " want=" + frames
                            throw IllegalStateException(error)
                        }
                        val block = (ring.renderedFrames / timeline.sampleRate).toInt()
                        stopBlock = block
                        if (block != lastLoggedBlock) {
                            lastLoggedBlock = block
                            RingBandLog.i(
                                "RingBand append block=" + block +
                                    " filledFrames=" + ring.renderedFrames,
                            )
                        }
                    }
                    RingBandLog.i("RingBand decode session close")
                    if (ring.renderedFrames < timeline.loopFrames) {
                        error = "decoder short filled=" + ring.renderedFrames +
                            " want=" + timeline.loopFrames
                        stopReason = "SHORT"
                    }
                } catch (t: Throwable) {
                    error = t.javaClass.simpleName + ": " + t.message
                    stopReason = if (releaseRequested) "RELEASE" else "DECODE_EXCEPTION"
                } finally {
                    try {
                        decoder.close()
                    } catch (t: Throwable) {
                        if (error == null) error = "close: " + t.message
                    }
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

    private companion object {
        const val PREROLL_US = 300_000L
    }
}
