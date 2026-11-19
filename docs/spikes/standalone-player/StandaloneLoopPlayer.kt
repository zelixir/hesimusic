package com.zjr.hesimusic.loop.audio.standalone

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.os.SystemClock
import kotlin.math.max

/**
 * 只用 [AudioTrack] 的循环播放器（不经过 media3 的 AudioSink）。
 *
 * 播放线程从 [StandaloneRing] 按帧取模读取，写 AudioTrack MODE_STREAM。
 * 环内取模保证 B->A 是样本级连续；播放线程不做分配，只有一次 4KB 左右的栈外缓冲。
 *
 * 首声路径：play() 前先向 AudioTrack 预填 [bufferSizeBytes] 的数据，再 play()，
 * 避免“先 play 再填”导致的启动 underrun。
 */
class StandaloneLoopPlayer(
    private val ring: StandaloneRing,
    private val sampleRate: Int,
    private val channels: Int,
    private val loopFrames: Long,
    private val log: (String) -> Unit = {},
) {
    private val bytesPerFrame: Int = channels * 2
    private val channelMask: Int =
        if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO

    private val minBufferBytes: Int = AudioTrack.getMinBufferSize(
        sampleRate,
        channelMask,
        AudioFormat.ENCODING_PCM_16BIT,
    )

    /** 约 80ms 的硬件缓冲；整环在内存里，写线程只需跟上，不会因解码阻塞。 */
    val bufferSizeBytes: Int = run {
        val floor = max(minBufferBytes * 4, CHUNK_FRAMES * bytesPerFrame * 4)
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
    private val writeBuf = ByteArray(CHUNK_FRAMES * bytesPerFrame)

    @Volatile
    private var running = true

    @Volatile
    private var playing = false

    /** 写游标（环内帧号）；seek 后由播放线程继续推进。 */
    private var cursorFrames = 0L

    private var seekGeneration = 0L
    private var appliedSeekGeneration = 0L
    private var firstWriteAfterSeek = false

    @Volatile
    var playCallNs: Long = 0L
        private set

    @Volatile
    var firstWriteNs: Long = 0L
        private set

    @Volatile
    var firstHeadNs: Long = 0L
        private set

    @Volatile
    var seekRequestNs: Long = 0L
        private set

    @Volatile
    var seekFirstWriteNs: Long = 0L
        private set

    @Volatile
    var seekFirstHeadNs: Long = 0L
        private set

    @Volatile
    var underrunStart: Int = 0
        private set

    @Volatile
    var underrunCount: Int = 0
        private set

    @Volatile
    var totalFramesWritten: Long = 0L
        private set

    @Volatile
    var lastError: String? = null
        private set

    private var writer: Thread? = null
    private var monitor: Thread? = null

    fun start() {
        writer = Thread({ writeLoop() }, "StandaloneAudioWrite").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        monitor = Thread({ monitorLoop() }, "StandaloneAudioMonitor").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * 开始播放：首次调用先预填硬件缓冲，再 play()。
     * 之后 resume 只 play()，不重复预填。
     */
    fun play() {
        synchronized(lock) {
            if (!running) return
            if (playCallNs == 0L) {
                playCallNs = SystemClock.elapsedRealtimeNanos()
                prefillLocked()
                underrunStart = runCatching { track.underrunCount }.getOrDefault(0)
                track.play()
                playing = true
                firstWriteAfterSeek = false
                log(
                    "player play bufferBytes=$bufferSizeBytes minBuffer=$minBufferBytes " +
                        "prefilledFrames=$cursorFrames",
                )
            } else if (!playing) {
                track.play()
                playing = true
                log("player resume atFrames=$cursorFrames")
            }
            lock.notifyAll()
        }
    }

    fun pause() {
        synchronized(lock) {
            if (!running || !playing) return
            playing = false
            try {
                track.pause()
            } catch (t: Throwable) {
                lastError = "pause: ${t.message}"
            }
            lock.notifyAll()
            log("player pause atFrames=$cursorFrames")
        }
    }

    fun isPlaying(): Boolean = playing

    /** 环内帧号（写游标）。 */
    fun cursorFrames(): Long = synchronized(lock) { cursorFrames }

    fun positionMs(): Long = cursorFrames() * 1_000L / sampleRate

    /**
     * 跳到环内 [frame]（自动 floorMod）。会 pause -> flush -> play，
     * 清掉旧缓冲，保证新位置从第一个样本开始。
     */
    fun seekToFrame(frame: Long) {
        synchronized(lock) {
            if (!running) return
            val target = Math.floorMod(frame, loopFrames)
            seekRequestNs = SystemClock.elapsedRealtimeNanos()
            seekFirstWriteNs = 0L
            seekFirstHeadNs = 0L
            val wasPlaying = playing
            try {
                if (wasPlaying) track.pause()
                track.flush()
            } catch (t: Throwable) {
                lastError = "seek flush: ${t.message}"
            }
            cursorFrames = target
            seekGeneration += 1L
            firstWriteAfterSeek = true
            if (wasPlaying) {
                try {
                    track.play()
                } catch (t: Throwable) {
                    lastError = "seek play: ${t.message}"
                }
            }
            lock.notifyAll()
            log("player seek targetFrames=$target wasPlaying=$wasPlaying")
        }
    }

    /** 等待 seek 后第一次写入完成；返回耗时 ms（超时 -1）。 */
    fun awaitSeekFirstWrite(timeoutMs: Long): Long {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val ns = seekFirstWriteNs
            if (ns > 0L) return (ns - seekRequestNs) / 1_000_000L
            if (!running) return -1L
            Thread.sleep(2)
        }
        return -1L
    }

    fun awaitSeekFirstHead(timeoutMs: Long): Long {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val ns = seekFirstHeadNs
            if (ns > 0L) return (ns - seekRequestNs) / 1_000_000L
            if (!running) return -1L
            Thread.sleep(2)
        }
        return -1L
    }

    fun release() {
        synchronized(lock) {
            if (!running) return
            running = false
            playing = false
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
        try {
            writer?.join(1_000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        try {
            monitor?.join(1_000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        try {
            track.release()
        } catch (_: Throwable) {
        }
        log("player release totalFrames=$totalFramesWritten underruns=$underrunCount")
    }

    private fun prefillLocked() {
        var writtenBytes = 0
        val targetBytes = bufferSizeBytes
        while (writtenBytes < targetBytes && running) {
            val pos = cursorFrames
            val available = ring.availableFrom(pos)
            if (available <= 0L) {
                // 理论上 engine 已等到 prefill 门槛；这里最多等 200ms 兜底。
                if (!ring.awaitRenderedAtLeast(pos + 1L, 200L)) break
                continue
            }
            val contiguous = minOf(available, loopFrames - pos)
            val frames = minOf(CHUNK_FRAMES.toLong(), contiguous).toInt()
            if (frames <= 0) break
            ring.readInto(pos, writeBuf, 0, frames)
            val bytes = frames * bytesPerFrame
            val written = track.write(writeBuf, 0, bytes, AudioTrack.WRITE_BLOCKING)
            if (written <= 0) {
                lastError = "prefill write=$written"
                break
            }
            val framesWritten = written / bytesPerFrame
            cursorFrames = (pos + framesWritten) % loopFrames
            totalFramesWritten += framesWritten
            writtenBytes += written
            if (written < bytes) break
        }
    }

    private fun writeLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        while (true) {
            synchronized(lock) {
                while (running && !playing) {
                    try {
                        lock.wait(50)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return
                    }
                }
                if (!running) return
                if (appliedSeekGeneration != seekGeneration) {
                    appliedSeekGeneration = seekGeneration
                }
                val pos = cursorFrames
                val available = ring.availableFrom(pos)
                if (available <= 0L) {
                    if (ring.closed) return
                    try {
                        lock.wait(5)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return
                    }
                    continue
                }
                val contiguous = minOf(available, loopFrames - pos)
                val frames = minOf(CHUNK_FRAMES.toLong(), contiguous).toInt()
                if (frames <= 0) continue
                ring.readInto(pos, writeBuf, 0, frames)
                val bytes = frames * bytesPerFrame
                val written = try {
                    track.write(writeBuf, 0, bytes, AudioTrack.WRITE_BLOCKING)
                } catch (t: Throwable) {
                    lastError = "write: ${t.message}"
                    -1
                }
                if (written <= 0) {
                    if (written < 0 && running) {
                        log("player write error=$written err=${lastError ?: "-"}")
                    }
                    continue
                }
                val framesWritten = written / bytesPerFrame
                if (framesWritten <= 0) continue
                cursorFrames = (pos + framesWritten) % loopFrames
                totalFramesWritten += framesWritten
                val now = SystemClock.elapsedRealtimeNanos()
                if (firstWriteNs == 0L) firstWriteNs = now
                if (firstWriteAfterSeek) {
                    firstWriteAfterSeek = false
                    seekFirstWriteNs = now
                }
            }
        }
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
                // track released / illegal state: stop monitoring
                return
            }
            try {
                Thread.sleep(2)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    companion object {
        /** 每次写 512 帧（44.1k 约 11.6ms）；阻塞写最坏阻塞约一个 chunk。 */
        const val CHUNK_FRAMES: Int = 512
    }
}
