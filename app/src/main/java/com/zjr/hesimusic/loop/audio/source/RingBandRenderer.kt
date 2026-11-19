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
                var firstChunkLogged = false
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
                        if (!firstChunkLogged) {
                            firstChunkLogged = true
                            RingBandLog.i(
                                "RingBand render firstChunk frames=" + frames +
                                    " firstSamples=[" + firstSamples(data, timeline.bytesPerFrame) + "]",
                            )
                        }
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
        if (releaseRequested) return
        releaseRequested = true
        RingBandLog.i("RingBand renderer release requested")
        try {
            decoder.close()
        } catch (_: Throwable) {
        }
        executor.shutdownNow()
        try {
            executor.awaitTermination(500, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        RingBandLog.i(
            "RingBand renderer release bandBytes=" +
                (timeline.loopFrames * timeline.bytesPerFrame) +
                " released=true",
        )
    }

    /** 前 8 个交错 int16（L,R,L,R...），供离线脚本与源 PCM A 点逐样本比对。 */
    private fun firstSamples(pcm: ByteArray, bytesPerFrame: Int, count: Int = 8): String {
        if (bytesPerFrame <= 0 || pcm.isEmpty()) return "-"
        val bb = java.nio.ByteBuffer.wrap(pcm).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val n = minOf(count, pcm.size / 2)
        val sb = StringBuilder()
        for (i in 0 until n) {
            if (i > 0) sb.append(',')
            sb.append(bb.getShort(i * 2).toInt())
        }
        return sb.toString()
    }

    private companion object {
        const val PREROLL_US = 300_000L
    }
}
