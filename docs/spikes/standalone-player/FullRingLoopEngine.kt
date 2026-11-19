package com.zjr.hesimusic.loop.audio.standalone

import android.os.Process
import android.os.SystemClock

/**
 * 整环模式：把 [A,B) 完整解码进 [StandaloneRing]，播放线程按帧取模。
 *
 * 首声路径与后台解码重叠：解出 [prefillMs] 的连续前缀后立即开播，剩余部分在后台继续
 * append；播放线程只在“写游标追上已解前缀”时短暂等待。因为 MediaCodec 解码速度远高于
 * 实时，整窗解码通常在首声后几百 ms 内完成，之后 seek 是 O(1) 的纯游标跳转。
 */
class FullRingLoopEngine(
    override val window: StandaloneWindow,
    private val prefillMs: Long = 400L,
    private val log: (String) -> Unit = {},
) : StandalonePlayback {

    override val mode: StandaloneMode = StandaloneMode.FULL_RING

    private val ring = StandaloneRing(window.sampleRate, window.channels, window.frames)
    private val player = StandaloneLoopPlayer(
        ring = ring,
        sampleRate = window.sampleRate,
        channels = window.channels,
        loopFrames = window.frames,
        log = log,
    )

    @Volatile
    private var decodeThread: Thread? = null

    @Volatile
    private var decodeStartedNs: Long = 0L

    @Volatile
    private var firstChunkNs: Long = 0L

    @Volatile
    private var decodeComplete: Boolean = false

    @Volatile
    private var decodeMs: Long = -1L

    @Volatile
    private var decodeFailure: String? = null

    private var released = false

    override fun prepareAndPlay(): StandaloneStartMetrics {
        val startNs = SystemClock.elapsedRealtimeNanos()
        player.start()
        startDecode()

        val prefillFrames = ((prefillMs * window.sampleRate) / 1_000L)
            .coerceIn(1L, window.frames)
        val prefillDeadline = System.currentTimeMillis() + 5_000L
        var prefillOk = ring.awaitRenderedAtLeast(
            prefillFrames,
            (prefillDeadline - System.currentTimeMillis()).coerceAtLeast(0L),
        )
        while (!prefillOk && System.currentTimeMillis() < prefillDeadline) {
            val err = decodeFailure
            if (err != null) throw StandaloneDecodeException("decode failed before first sound: $err")
            prefillOk = ring.awaitRenderedAtLeast(
                prefillFrames,
                (prefillDeadline - System.currentTimeMillis()).coerceAtLeast(0L),
            )
        }
        if (!prefillOk && ring.renderedFrames <= 0L) {
            throw StandaloneDecodeException(
                "no PCM before first sound: rendered=${ring.renderedFrames} " +
                    "err=${decodeFailure ?: "-"}",
            )
        }
        val prefillReadyNs = SystemClock.elapsedRealtimeNanos()
        player.play()
        val playCallNs = player.playCallNs
        log(
            "full-ring start decodeStartMs=${(decodeStartedNs - startNs) / 1_000_000L} " +
                "prefillMs=${(prefillReadyNs - startNs) / 1_000_000L} " +
                "renderedFrames=${ring.renderedFrames}/${window.frames} " +
                "ringBytes=${window.ringBytes} heapMax=${Runtime.getRuntime().maxMemory()}",
        )
        return StandaloneStartMetrics(
            mode = mode,
            window = window,
            ringBytes = window.ringBytes,
            heapMaxBytes = Runtime.getRuntime().maxMemory(),
            bufferBytes = player.bufferSizeBytes,
            decodeStartNs = decodeStartedNs,
            firstChunkNs = firstChunkNs,
            prefillReadyNs = prefillReadyNs,
            playCallNs = playCallNs,
            firstWriteNs = player.firstWriteNs,
            firstHeadNs = player.firstHeadNs,
        )
    }

    private fun startDecode() {
        if (decodeThread != null) return
        decodeStartedNs = SystemClock.elapsedRealtimeNanos()
        decodeThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val t0 = SystemClock.elapsedRealtimeNanos()
            try {
                val decoder = StandaloneRangeDecoder(
                    path = window.path,
                    sampleRate = window.sampleRate,
                    channels = window.channels,
                )
                val emitted = decoder.decode(window.startUs, window.endUs) { data, size ->
                    val frames = size / window.bytesPerFrame
                    if (frames <= 0) return@decode
                    val appended = ring.append(data, 0)
                    if (appended != frames) {
                        throw StandaloneDecodeException(
                            "ring append short: wrote=$appended want=$frames " +
                                "rendered=${ring.renderedFrames}/${window.frames}",
                        )
                    }
                    if (firstChunkNs == 0L) {
                        firstChunkNs = SystemClock.elapsedRealtimeNanos()
                        log(
                            "full-ring firstChunk afterMs=${(firstChunkNs - decodeStartedNs) / 1_000_000L} " +
                                "frames=$frames",
                        )
                    }
                }
                if (emitted != window.frames) {
                    throw StandaloneDecodeException(
                        "decoded frames=$emitted != loopFrames=${window.frames}",
                    )
                }
                decodeComplete = true
            } catch (t: Throwable) {
                decodeFailure = t.message ?: t.javaClass.simpleName
                log("full-ring decode error: $decodeFailure")
            } finally {
                decodeMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000L
                log(
                    "full-ring decode done ms=$decodeMs frames=${ring.renderedFrames}/" +
                        "${window.frames} complete=$decodeComplete err=${decodeFailure ?: "-"}",
                )
            }
        }, "StandaloneFullRingDecode").apply { start() }
    }

    override fun seekToOffsetMs(offsetMs: Long): StandaloneSeekMetrics {
        val t0 = SystemClock.elapsedRealtimeNanos()
        val targetFrame = window.frameForOffsetMs(offsetMs)
        var decodeWaitMs = 0L
        if (targetFrame >= ring.renderedFrames && !decodeComplete) {
            val waitStart = SystemClock.elapsedRealtimeNanos()
            val ok = ring.awaitRenderedAtLeast(targetFrame + 1L, 5_000L)
            decodeWaitMs = (SystemClock.elapsedRealtimeNanos() - waitStart) / 1_000_000L
            if (!ok) {
                throw StandaloneDecodeException(
                    "seek target frame=$targetFrame not decoded: rendered=${ring.renderedFrames} " +
                        "complete=$decodeComplete err=${decodeFailure ?: "-"}",
                )
            }
        }
        player.seekToFrame(targetFrame)
        val firstWriteMs = player.awaitSeekFirstWrite(2_000L)
        val firstHeadMs = player.awaitSeekFirstHead(2_000L)
        val totalMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000L
        log(
            "full-ring seek offsetMs=$offsetMs frame=$targetFrame decodeWait=$decodeWaitMs " +
                "write=$firstWriteMs head=$firstHeadMs total=$totalMs",
        )
        return StandaloneSeekMetrics(
            targetOffsetMs = offsetMs,
            targetFrame = targetFrame,
            decodeWaitMs = decodeWaitMs,
            firstWriteMs = firstWriteMs,
            firstHeadMs = firstHeadMs,
            totalMs = totalMs,
        )
    }

    override fun pause() = player.pause()
    override fun resume() = player.play()
    override fun isPlaying(): Boolean = player.isPlaying()
    override fun positionMs(): Long = player.positionMs()
    override fun underrunCount(): Int = player.underrunCount
    override fun firstHeadNs(): Long = player.firstHeadNs
    override fun firstWriteNs(): Long = player.firstWriteNs
    override fun playCallNs(): Long = player.playCallNs
    override fun decodeError(): String? = decodeFailure

    override fun release() {
        if (released) return
        released = true
        try {
            player.release()
        } catch (_: Throwable) {
        }
        ring.close()
        val t = decodeThread
        if (t != null) {
            try {
                t.join(1_000L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        log(
            "full-ring release renderedFrames=${ring.renderedFrames} decodeMs=$decodeMs " +
                "complete=$decodeComplete err=${decodeFailure ?: "-"}",
        )
    }
}
