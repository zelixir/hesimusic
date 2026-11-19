package com.zjr.hesimusic.loop.audio.standalone

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.util.ArrayDeque
import kotlin.math.max
import kotlin.math.min

/**
 * 分块模式：不保留整窗 PCM，固定容量 PCM 队列 + 顺序分块解码。
 *
 * 窗口大于“堆/10”时自动选择本实现（10 分钟窗口的降级路径）。内存上限由
 * [maxQueuedFrames] 决定（默认 4 秒），与窗口长度无关。
 *
 * 接缝：块边界按**帧号**切分，解码器对每块输出精确帧数；块队列顺序在 B 之后直接接
 * A 的第一块，因此 B->A 仍是样本级连续（不依赖取模，也不做淡入淡出）。
 *
 * 代价：每块一次 MediaExtractor+MediaCodec 会话（原型实现），seek 需要重开解码器，
 * 延迟高于整环模式；这是用内存换取的降级路径，报告里单列。
 */
class ChunkedLoopEngine(
    override val window: StandaloneWindow,
    private val prefillMs: Long = 500L,
    private val log: (String) -> Unit = {},
) : StandalonePlayback {

    override val mode: StandaloneMode = StandaloneMode.CHUNKED

    private val bytesPerFrame = window.bytesPerFrame
    private val sampleRate = window.sampleRate
    private val channelMask =
        if (window.channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO

    /** 队列上限 4 秒；块 1 秒。 */
    private val chunkFrames: Long = sampleRate.toLong()
    private val maxQueuedFrames: Long = sampleRate.toLong() * 4L

    private val minBufferBytes: Int = AudioTrack.getMinBufferSize(
        sampleRate,
        channelMask,
        AudioFormat.ENCODING_PCM_16BIT,
    )
    private val bufferSizeBytes: Int = run {
        val floor = max(minBufferBytes * 4, StandaloneLoopPlayer.CHUNK_FRAMES * bytesPerFrame * 4)
        ((floor + bytesPerFrame - 1) / bytesPerFrame) * bytesPerFrame
    }

    private val track: AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(channelMask)
                .build(),
        )
        .setBufferSizeInBytes(bufferSizeBytes)
        .setTransferMode(AudioTrack.MODE_STREAM)
        .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        .build()

    private val lock = Object()
    private val queue = ArrayDeque<PcmChunk>()
    private var queuedFrames = 0L
    private var current: PcmChunk? = null
    private var currentOffsetFrames = 0
    private var nextDecodeFrame = 0L
    private var loopCursorFrames = 0L
    private var seekGeneration = 0L
    private var pendingSeekGeneration = -1L
    private val writeBuf = ByteArray(StandaloneLoopPlayer.CHUNK_FRAMES * bytesPerFrame)

    @Volatile
    private var running = true

    @Volatile
    private var playing = false

    @Volatile
    private var producerError: String? = null

    @Volatile
    private var currentDecoder: StandaloneRangeDecoder? = null

    @Volatile
    private var decodeStartedNs: Long = 0L

    @Volatile
    private var firstChunkNs: Long = 0L

    @Volatile
    private var seekFirstChunkNs: Long = 0L

    @Volatile
    private var playCallNs: Long = 0L

    @Volatile
    private var firstWriteNs: Long = 0L

    @Volatile
    private var firstHeadNs: Long = 0L

    @Volatile
    private var seekRequestNs: Long = 0L

    @Volatile
    private var seekFirstWriteNs: Long = 0L

    @Volatile
    private var seekFirstHeadNs: Long = 0L

    @Volatile
    private var underrunStart: Int = 0

    @Volatile
    private var underrunCount: Int = 0

    @Volatile
    private var totalFramesWritten: Long = 0L

    @Volatile
    private var lastError: String? = null

    private var producer: Thread? = null
    private var writer: Thread? = null
    private var monitor: Thread? = null

    private class PcmChunk(val startFrame: Long, val frames: Long, val data: ByteArray)

    override fun prepareAndPlay(): StandaloneStartMetrics {
        val startNs = SystemClock.elapsedRealtimeNanos()
        startProducer()
        val prefillFrames = ((prefillMs * sampleRate) / 1_000L)
            .coerceIn(1L, min(window.frames, maxQueuedFrames))
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            val ready = synchronized(lock) { queuedFrames >= prefillFrames || producerError != null }
            if (ready) break
            Thread.sleep(2L)
        }
        val err = producerError
        if (err != null && synchronized(lock) { queuedFrames <= 0L }) {
            throw StandaloneDecodeException("chunked producer failed before first sound: $err")
        }
        val prefillReadyNs = SystemClock.elapsedRealtimeNanos()
        startWriterAndMonitor()
        play()
        val metrics = StandaloneStartMetrics(
            mode = mode,
            window = window,
            ringBytes = maxQueuedFrames * bytesPerFrame,
            heapMaxBytes = Runtime.getRuntime().maxMemory(),
            bufferBytes = bufferSizeBytes,
            decodeStartNs = decodeStartedNs,
            firstChunkNs = firstChunkNs,
            prefillReadyNs = prefillReadyNs,
            playCallNs = playCallNs,
            firstWriteNs = firstWriteNs,
            firstHeadNs = firstHeadNs,
        )
        log(
            "chunked start prefillMs=${(prefillReadyNs - startNs) / 1_000_000L} " +
                "queuedFrames=${synchronized(lock) { queuedFrames }} chunkFrames=$chunkFrames " +
                "queueBytes=${metrics.ringBytes}",
        )
        return metrics
    }

    private fun startProducer() {
        if (producer != null) return
        decodeStartedNs = SystemClock.elapsedRealtimeNanos()
        producer = Thread({ producerLoop() }, "StandaloneChunkProducer").apply {
            Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT)
            start()
        }
    }

    private fun producerLoop() {
        while (running) {
            val gen = synchronized(lock) { seekGeneration }
            val startFrame = synchronized(lock) { nextDecodeFrame }
            if (startFrame >= window.frames) {
                synchronized(lock) { nextDecodeFrame = 0L }
                continue
            }
            val endFrame = min(startFrame + chunkFrames, window.frames)
            val startUs = window.startUs + startFrame * 1_000_000L / sampleRate
            val endUs = window.startUs + endFrame * 1_000_000L / sampleRate
            val decoder = StandaloneRangeDecoder(
                path = window.path,
                sampleRate = sampleRate,
                channels = window.channels,
            )
            currentDecoder = decoder
            val out = ByteArrayOutputStream(((endFrame - startFrame) * bytesPerFrame).toInt())
            try {
                decoder.decode(startUs, endUs) { data, size ->
                    out.write(data, 0, size)
                }
                val data = out.toByteArray()
                val frames = data.size / bytesPerFrame
                if (frames.toLong() != endFrame - startFrame) {
                    throw StandaloneDecodeException(
                        "chunk frames=$frames expected=${endFrame - startFrame}",
                    )
                }
                synchronized(lock) {
                    if (gen != seekGeneration) {
                        // seek happened while decoding; discard and retry at the new target.
                        return@synchronized
                    }
                    while (running && queuedFrames + frames > maxQueuedFrames) {
                        lock.wait(50L)
                        if (gen != seekGeneration) return@synchronized
                    }
                    if (!running) return
                    if (gen != seekGeneration) return@synchronized
                    queue.addLast(PcmChunk(startFrame, frames, data))
                    queuedFrames += frames
                    nextDecodeFrame = if (endFrame >= window.frames) 0L else endFrame
                    if (firstChunkNs == 0L) {
                        firstChunkNs = SystemClock.elapsedRealtimeNanos()
                        log(
                            "chunked firstChunk afterMs=${(firstChunkNs - decodeStartedNs) / 1_000_000L} " +
                                "frames=$frames startFrame=$startFrame",
                        )
                    }
                    if (gen != seekGeneration) {
                        seekFirstChunkNs = SystemClock.elapsedRealtimeNanos()
                    }
                    lock.notifyAll()
                }
            } catch (t: Throwable) {
                val cancelled = gen != synchronized(lock) { seekGeneration }
                if (!cancelled) {
                    producerError = t.message ?: t.javaClass.simpleName
                    log("chunked producer error: $producerError")
                }
            } finally {
                currentDecoder = null
            }
        }
    }

    private fun startWriterAndMonitor() {
        if (writer == null) {
            writer = Thread({ writerLoop() }, "StandaloneChunkWriter").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }
        }
        if (monitor == null) {
            monitor = Thread({ monitorLoop() }, "StandaloneChunkMonitor").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun play() {
        synchronized(lock) {
            if (!running) return
            if (playCallNs == 0L) {
                playCallNs = SystemClock.elapsedRealtimeNanos()
                prefillLocked()
                underrunStart = runCatching { track.underrunCount }.getOrDefault(0)
                track.play()
                playing = true
                log(
                    "chunked player play bufferBytes=$bufferSizeBytes " +
                        "queuedFrames=$queuedFrames",
                )
            } else if (!playing) {
                track.play()
                playing = true
                log("chunked player resume")
            }
            lock.notifyAll()
        }
    }

    private fun prefillLocked() {
        var writtenBytes = 0
        while (writtenBytes < bufferSizeBytes && running) {
            if (!takeChunkLocked()) {
                try {
                    lock.wait(50L)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
                if (producerError != null && queue.isEmpty()) return
                continue
            }
            val chunk = current ?: return
            val frames = min(
                StandaloneLoopPlayer.CHUNK_FRAMES.toLong(),
                chunk.frames - currentOffsetFrames,
            ).toInt()
            if (frames <= 0) {
                current = null
                continue
            }
            System.arraycopy(
                chunk.data,
                currentOffsetFrames * bytesPerFrame,
                writeBuf,
                0,
                frames * bytesPerFrame,
            )
            val written = try {
                track.write(writeBuf, 0, frames * bytesPerFrame, AudioTrack.WRITE_BLOCKING)
            } catch (t: Throwable) {
                lastError = "prefill: ${t.message}"
                -1
            }
            if (written <= 0) {
                lastError = "prefill write=$written"
                return
            }
            val framesWritten = written / bytesPerFrame
            currentOffsetFrames += framesWritten
            writtenBytes += written
            loopCursorFrames = (loopCursorFrames + framesWritten) % window.frames
            totalFramesWritten += framesWritten
        }
    }

    private fun takeChunkLocked(): Boolean {
        val c = current
        if (c != null && currentOffsetFrames < c.frames) return true
        current = null
        currentOffsetFrames = 0
        val next = queue.pollFirst() ?: return false
        queuedFrames -= next.frames
        current = next
        return true
    }

    private fun writerLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        while (true) {
            synchronized(lock) {
                while (running && !playing) {
                    try {
                        lock.wait(50L)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return
                    }
                }
                if (!running) return
                if (!takeChunkLocked()) {
                    if (producerError != null && queue.isEmpty()) {
                        // producer 已经失败：保持等待，UI 会显示错误；不空转。
                    }
                    try {
                        lock.wait(5L)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return
                    }
                    continue
                }
                val chunk = current ?: continue
                val frames = min(
                    StandaloneLoopPlayer.CHUNK_FRAMES.toLong(),
                    chunk.frames - currentOffsetFrames,
                ).toInt()
                if (frames <= 0) {
                    current = null
                    continue
                }
                System.arraycopy(
                    chunk.data,
                    currentOffsetFrames * bytesPerFrame,
                    writeBuf,
                    0,
                    frames * bytesPerFrame,
                )
                val written = try {
                    track.write(writeBuf, 0, frames * bytesPerFrame, AudioTrack.WRITE_BLOCKING)
                } catch (t: Throwable) {
                    lastError = "write: ${t.message}"
                    -1
                }
                if (written <= 0) {
                    if (written < 0 && running) log("chunked write error=$written")
                    continue
                }
                val framesWritten = written / bytesPerFrame
                if (framesWritten <= 0) continue
                currentOffsetFrames += framesWritten
                loopCursorFrames = (loopCursorFrames + framesWritten) % window.frames
                totalFramesWritten += framesWritten
                val now = SystemClock.elapsedRealtimeNanos()
                if (firstWriteNs == 0L) firstWriteNs = now
                if (pendingSeekGeneration == seekGeneration) {
                    pendingSeekGeneration = -1L
                    seekFirstWriteNs = now
                }
            }
        }
    }

    override fun seekToOffsetMs(offsetMs: Long): StandaloneSeekMetrics {
        val t0 = SystemClock.elapsedRealtimeNanos()
        val targetFrame = window.frameForOffsetMs(offsetMs)
        synchronized(lock) {
            if (!running) {
                return StandaloneSeekMetrics(offsetMs, targetFrame, 0, -1, -1, 0)
            }
            seekRequestNs = t0
            seekFirstChunkNs = 0L
            seekFirstWriteNs = 0L
            seekFirstHeadNs = 0L
            seekGeneration += 1L
            pendingSeekGeneration = seekGeneration
            queue.clear()
            queuedFrames = 0L
            current = null
            currentOffsetFrames = 0
            nextDecodeFrame = targetFrame
            loopCursorFrames = targetFrame
            currentDecoder?.cancel()
            val wasPlaying = playing
            try {
                if (wasPlaying) track.pause()
                track.flush()
            } catch (t: Throwable) {
                lastError = "seek flush: ${t.message}"
            }
            if (wasPlaying) {
                try {
                    track.play()
                } catch (t: Throwable) {
                    lastError = "seek play: ${t.message}"
                }
            }
            lock.notifyAll()
        }
        val decodeWaitMs = awaitNonZero({ seekFirstChunkNs }, 5_000L)
        val firstWriteMs = awaitNonZero({ seekFirstWriteNs }, 5_000L)
        val firstHeadMs = awaitNonZero({ seekFirstHeadNs }, 5_000L)
        val totalMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000L
        log(
            "chunked seek offsetMs=$offsetMs frame=$targetFrame chunkWait=$decodeWaitMs " +
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

    private fun awaitNonZero(value: () -> Long, timeoutMs: Long): Long {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val v = value()
            if (v > 0L) return v
            if (!running) return -1L
            Thread.sleep(2L)
        }
        return -1L
    }

    private fun monitorLoop() {
        var lastUnderrun = 0
        while (running) {
            try {
                if (playing) {
                    val head = track.playbackHeadPosition
                    if (head > 0) {
                        val now = SystemClock.elapsedRealtimeNanos()
                        if (firstHeadNs == 0L) firstHeadNs = now
                        if (seekFirstWriteNs > 0L && seekFirstHeadNs == 0L) {
                            seekFirstHeadNs = now
                        }
                    }
                    val u = track.underrunCount
                    if (u != lastUnderrun) {
                        lastUnderrun = u
                        underrunCount = u
                    }
                }
            } catch (_: Throwable) {
                return
            }
            try {
                Thread.sleep(2L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    override fun pause() {
        synchronized(lock) {
            if (!running || !playing) return
            playing = false
            try {
                track.pause()
            } catch (_: Throwable) {
            }
            lock.notifyAll()
        }
    }

    override fun resume() = play()

    override fun isPlaying(): Boolean = playing
    override fun positionMs(): Long = synchronized(lock) {
        loopCursorFrames * 1_000L / sampleRate
    }

    override fun underrunCount(): Int = underrunCount
    override fun firstHeadNs(): Long = firstHeadNs
    override fun firstWriteNs(): Long = firstWriteNs
    override fun playCallNs(): Long = playCallNs
    override fun decodeError(): String? = producerError

    override fun release() {
        synchronized(lock) {
            if (!running) return
            running = false
            playing = false
            currentDecoder?.cancel()
            lock.notifyAll()
        }
        try {
            track.pause()
        } catch (_: Throwable) {
        }
        try {
            track.flush()
        } catch (_: Throwable) {
        }
        try {
            track.stop()
        } catch (_: Throwable) {
        }
        for (t in listOf(producer, writer, monitor)) {
            try {
                t?.join(1_000L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        try {
            track.release()
        } catch (_: Throwable) {
        }
        log(
            "chunked release totalFrames=$totalFramesWritten underruns=$underrunCount " +
                "err=${producerError ?: "-"}",
        )
    }
}
