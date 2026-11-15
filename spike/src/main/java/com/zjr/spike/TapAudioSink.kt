package com.zjr.spike

import android.os.Handler
import android.os.Looper
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 包住 DefaultAudioSink 的取证层（CONTRACT §4 M3 / §3）。
 *
 * 职责：
 *  1. 逐次记录 configure/feed/flush/handleDiscontinuity/playToEndOfStream/isEnded/hasPendingData/
 *     setOutputStreamOffsetUs/getCurrentPositionUs 等调用；
 *  2. 把每次 handleBuffer 中 delegate 真正消费掉的 PCM 按到达顺序写 WAV（feed.frames 是实际写入帧数）；
 *  3. mode=sink：捕获 [aMs,bMs) 的 PCM，EOS 后在独立线程原地重放，观察 EOS 之后 renderer 是否还喂
 *     buffer、delegate 在 playToEndOfStream 之后是否还接受写入（M3 生死判据）。
 */
@UnstableApi
class TapAudioSink(
    delegate: AudioSink,
    private val rec: SpikeRecorder,
    private val cfg: SpikeConfig,
    private val probe: TrackProbeProvider,
) : ForwardingAudioSink(delegate) {

    private val delegateLock = Any()

    @Volatile
    private var sampleRate: Int = 44100

    @Volatile
    private var channels: Int = 2

    @Volatile
    private var bytesPerFrame: Int = 4

    @Volatile
    private var eosSeen: Boolean = false

    @Volatile
    private var lastEnded: Boolean = false

    @Volatile
    private var lastPending: Boolean = true

    @Volatile
    private var lastPtsUs: Long = 0L

    // seam 打点（baseline / window）：模块层得知"新的一轮开始"时，在下一个被消费的
    // handleBuffer 首样本上打点。字段语义按 CONTRACT §3.2 2026-11-15 裁定：
    //   iter      = 完成这一轮后的轮次序号（从 1 开始）
    //   outFrames = 自运行开始累计已写进 delegate 的帧数
    //   ptsUs     = 新一轮第一个样本喂给 delegate 时使用的时间戳
    private val seamMode: Boolean = cfg.mode == "baseline" || cfg.mode == "window"
    private var lastConsumedPtsUs: Long = -1L
    private var seamArmed: Boolean = false
    private var seamRound: Int = 0

    @Volatile
    private var lastPositionUs: Long = 0L

    // ExoPlayer 给 feed.ptsUs 加了一个全局输出偏移（1e12），capture 需要减掉它才是曲内时间。
    @Volatile
    private var outputStreamOffsetUs: Long = 0L

    // DefaultAudioSink 的 flush() 会在内部 new Handler()，必须在有 Looper 的 playback 线程上调用。
    @Volatile
    private var playbackLooper: Looper? = null

    private val sinkMode: Boolean = cfg.mode == "sink"

    // ---- sink 模式的重放状态 ----
    private val captureLock = Any()
    private val captured = ByteArrayOutputStream()
    private var capturedFrames = 0L

    @Volatile
    private var replayActive = false

    @Volatile
    private var replayAttempted = false

    @Volatile
    private var replayThread: Thread? = null

    private var replayIterations = 0
    private var captureLoggedBegin = false
    private var captureLoggedEnd = false

    // ---------------------------------------------------------------- configure

    override fun configure(format: Format, bufferSize: Int, audioTrackChannelConfig: IntArray?) {
        val bitsPerSample = bitsPerSample(format.pcmEncoding)
        val bpf = bitsPerSample / 8 * format.channelCount
        sampleRate = format.sampleRate
        channels = format.channelCount
        bytesPerFrame = if (bpf > 0) bpf else 4
        playbackLooper = Looper.myLooper()
        rec.event(
            "configure",
            linkedMapOf(
                "sampleRate" to format.sampleRate,
                "channels" to format.channelCount,
                "pcm" to pcmName(format.pcmEncoding),
                "bytesPerFrame" to bytesPerFrame,
                "bufferSize" to bufferSize,
                "formatTag" to wavFormatTag(format.pcmEncoding),
                "bitsPerSample" to bitsPerSample,
            ),
        )
        rec.createWav(format.sampleRate, format.channelCount, bitsPerSample, wavFormatTag(format.pcmEncoding))
        super.configure(format, bufferSize, audioTrackChannelConfig)
    }

    // --------------------------------------------------------------- handleBuffer

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        val startPos = buffer.position()
        val ret: Boolean
        synchronized(delegateLock) {
            ret = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        }
        val endPos = buffer.position()
        val consumed = endPos - startPos
        lastPtsUs = presentationTimeUs
        val cumulativeBefore = rec.totalFrames
        if (consumed > 0) {
            val dup = buffer.duplicate()
            dup.position(startPos)
            dup.limit(endPos)
            val data = ByteArray(consumed)
            dup.get(data)
            rec.writePcm(data, bytesPerFrame)
            val frames = consumed / bytesPerFrame
            rec.event(
                "feed",
                linkedMapOf(
                    "frames" to frames,
                    "ptsUs" to presentationTimeUs,
                    "bytes" to consumed,
                    "ret" to ret,
                    "afterEos" to eosSeen,
                ),
            )
            if (seamMode) maybeEmitSeam(presentationTimeUs, cumulativeBefore)
            if (sinkMode) captureRegion(data, startPos, presentationTimeUs)
        } else {
            rec.event(
                "feed_wait",
                linkedMapOf(
                    "ptsUs" to presentationTimeUs,
                    "ret" to ret,
                    "afterEos" to eosSeen,
                ),
            )
        }
        return ret
    }

    /**
     * baseline/window 的接缝打点：PTS 大幅回跳（REPEAT_ONE=seek / 窗口复位）或
     * setOutputStreamOffsetUs 变化（gapless 推进到下一窗口）都会 arm，下一段被消费的
     * PCM 即新一轮首样本。
     */
    private fun maybeEmitSeam(ptsUs: Long, cumulativeBefore: Long) {
        if (lastConsumedPtsUs >= 0 && ptsUs < lastConsumedPtsUs - 200_000L) {
            seamArmed = true
        }
        if (seamArmed && lastConsumedPtsUs >= 0) {
            seamArmed = false
            seamRound += 1
            rec.event(
                "seam",
                linkedMapOf(
                    "iter" to seamRound,
                    "outFrames" to cumulativeBefore,
                    "ptsUs" to ptsUs,
                    "src" to cfg.mode,
                    "trigger" to if (ptsUs < lastConsumedPtsUs - 200_000L) "pts_reset" else "offset",
                ),
            )
        }
        lastConsumedPtsUs = ptsUs
    }

    /** 从 delegate 实际消费的这段 PCM 中，截取落在 [aMs,bMs) 的部分。 */
    private fun captureRegion(data: ByteArray, startPositionInBuffer: Int, ptsUs: Long) {
        val bpf = bytesPerFrame
        if (bpf <= 0) return
        val sr = sampleRate
        val mediaPtsUs = ptsUs - outputStreamOffsetUs
        val baseFrame = mediaPtsUs * sr / 1_000_000L + startPositionInBuffer / bpf
        val frames = data.size / bpf
        val aFrame = cfg.aMs.toLong() * sr / 1000L
        val bFrame = cfg.bMs.toLong() * sr / 1000L
        val from = maxOf(0L, aFrame - baseFrame)
        val to = minOf(frames.toLong(), bFrame - baseFrame)
        if (to <= from) return
        val byteFrom = (from * bpf).toInt()
        val byteLen = ((to - from) * bpf).toInt()
        synchronized(captureLock) {
            captured.write(data, byteFrom, byteLen)
            capturedFrames += (to - from)
            if (!captureLoggedBegin) {
                captureLoggedBegin = true
                rec.event(
                    "capture_begin",
                    linkedMapOf(
                        "firstFrame" to (baseFrame + from),
                        "aFrame" to aFrame,
                        "bFrame" to bFrame,
                        "frames" to capturedFrames,
                    ),
                )
            }
            if (!captureLoggedEnd && capturedFrames >= (bFrame - aFrame)) {
                captureLoggedEnd = true
                rec.event(
                    "capture_end",
                    linkedMapOf("frames" to capturedFrames, "targetFrames" to (bFrame - aFrame)),
                )
            }
        }
    }

    // ------------------------------------------------------------ EOS / 生命周期

    override fun playToEndOfStream() {
        eosSeen = true
        rec.event(
            "play_to_eos",
            linkedMapOf(
                "capturedFrames" to capturedFrames,
                "ended" to super.isEnded(),
                "pending" to super.hasPendingData(),
                "lastPtsUs" to lastPtsUs,
                "posUs" to super.getCurrentPositionUs(true),
            ),
        )
        super.playToEndOfStream()
        rec.event(
            "play_to_eos_done",
            linkedMapOf(
                "ended" to super.isEnded(),
                "pending" to super.hasPendingData(),
                "posUs" to super.getCurrentPositionUs(true),
            ),
        )
        if (sinkMode) startReplay()
    }

    override fun isEnded(): Boolean {
        val ended = super.isEnded()
        if (ended != lastEnded) {
            lastEnded = ended
            rec.event(
                if (ended) "eos_out" else "eos_clear",
                linkedMapOf(
                    "pending" to super.hasPendingData(),
                    "replayActive" to replayActive,
                    "afterEos" to eosSeen,
                ),
            )
        }
        // M3：EOS 之后仍然返回 false（吞掉 EOS），让 renderer 留在播放态继续被我们喂数据。
        return if (sinkMode && replayActive) false else ended
    }

    override fun hasPendingData(): Boolean {
        val pending = super.hasPendingData()
        if (pending != lastPending) {
            lastPending = pending
            rec.event("pending_changed", linkedMapOf("pending" to pending, "ended" to super.isEnded()))
        }
        return pending
    }

    override fun getCurrentPositionUs(forceDiscontinuityPosition: Boolean): Long {
        val pos = super.getCurrentPositionUs(forceDiscontinuityPosition)
        if (pos == AudioSink.CURRENT_POSITION_NOT_SET) return pos
        // 位置回跳（PTS 复位）是接缝的重要证据，回跳 >50ms 时打点。
        if (pos + 50_000L < lastPositionUs) {
            rec.event("position_reset", linkedMapOf("fromUs" to lastPositionUs, "toUs" to pos))
        }
        lastPositionUs = pos
        return pos
    }

    override fun setOutputStreamOffsetUs(offsetUs: Long) {
        outputStreamOffsetUs = offsetUs
        rec.event("output_stream_offset", linkedMapOf("offsetUs" to offsetUs))
        // window(LoopingMediaSource) 的 gapless 推进：每进一个窗口 offset 增大，下一段 PCM 即新一轮。
        if (seamMode && lastConsumedPtsUs >= 0) {
            seamArmed = true
        }
        super.setOutputStreamOffsetUs(offsetUs)
    }

    override fun flush() {
        rec.event(
            "flush",
            linkedMapOf(
                "afterEos" to eosSeen,
                "capturedFrames" to capturedFrames,
                "posUs" to super.getCurrentPositionUs(true),
                "totalFrames" to rec.totalFrames,
            ),
        )
        super.flush()
    }

    override fun handleDiscontinuity() {
        rec.event(
            "discontinuity",
            linkedMapOf("lastPtsUs" to lastPtsUs, "posUs" to super.getCurrentPositionUs(true)),
        )
        super.handleDiscontinuity()
    }

    override fun reset() {
        rec.event("reset", linkedMapOf("totalFrames" to rec.totalFrames))
        super.reset()
    }

    override fun release() {
        rec.event("sink_release", linkedMapOf("totalFrames" to rec.totalFrames))
        probe.stopSampling()
        stopReplay()
        super.release()
    }

    // -------------------------------------------------------------- M3 重放

    private fun startReplay() {
        if (replayAttempted || replayActive || capturedFrames <= 0L) {
            if (!replayAttempted && capturedFrames <= 0L) {
                rec.event(
                    "looping",
                    linkedMapOf(
                        "on" to false,
                        "reason" to "no-captured-pcm",
                        "capturedFrames" to capturedFrames,
                    ),
                )
            }
            return
        }
        replayAttempted = true
        val ring: ByteArray
        synchronized(captureLock) { ring = captured.toByteArray() }
        val sr = sampleRate
        val bpf = bytesPerFrame
        val frames = ring.size / bpf
        rec.event(
            "looping",
            linkedMapOf(
                "on" to true,
                "aMs" to cfg.aMs,
                "bMs" to cfg.bMs,
                "ringFrames" to frames,
                "ringBytes" to ring.size,
            ),
        )
        replayActive = true
        val looper = playbackLooper
        if (looper == null) {
            rec.event("replay_error", linkedMapOf("phase" to "post", "message" to "no playback looper captured"))
            replayActive = false
            return
        }
        // 变体实验：REPLAY_ON_PLAYBACK_THREAD=true 时在 playback 线程上喂（会冻结 player），
        // 否则在独立线程上喂（player 保持运行，能观察 isEnded / playToEndOfStream 行为）。
        if (REPLAY_ON_PLAYBACK_THREAD) {
            Handler(looper).post {
                replayLoop(ring, sr, bpf)
            }
        } else {
            val t = Thread({ replayLoop(ring, sr, bpf) }, "spike-replay")
            replayThread = t
            t.start()
        }
    }

    /**
     * 在 EOS 之后把捕获的 [A,B) PCM 循环写入 delegate。
     * 每一段都用单调递增的 PTS（从 bUs 起、每轮 + L），并打 seam 点。
     * delegate 若拒绝（返回 false / 抛异常），如实记录 —— 这正是 M3 可行性判据。
     */
    private fun replayLoop(ring: ByteArray, sr: Int, bpf: Int) {
        val chunkBytes = maxOf(bpf * 1024, bpf * (sr / 100))
        val frames = ring.size / bpf
        val loopUs = frames * 1_000_000L / sr
        var iter = 0
        // 不 flush：EOS 后直接续喂是否能被 DefaultAudioSink 接受，是 M3 的关键判据。
        // （flush 会重建 AudioTrack；若能不 flush 就续喂，接缝代价小得多。）
        try {
            super.play()
        } catch (t: Throwable) {
            rec.event("replay_error", linkedMapOf("phase" to "play", "error" to t.javaClass.name, "message" to (t.message ?: "")))
        }
        rec.event("replay_start", linkedMapOf("frames" to frames, "loopUs" to loopUs, "chunkBytes" to chunkBytes))
        var ptsUs = maxOf(cfg.bUs, lastPtsUs + 10_000L)
        while (!rec.stopped && replayActive && iter < cfg.loops) {
            try {
                probe.track?.let { track ->
                    try {
                        if (track.playState != android.media.AudioTrack.PLAYSTATE_PLAYING) {
                            track.play()
                            rec.event("replay_track_play", linkedMapOf("iter" to iter, "state" to track.playState))
                        }
                    } catch (e: Throwable) {
                        rec.event("replay_track_error", linkedMapOf("message" to (e.message ?: e.javaClass.name)))
                    }
                }
                rec.event(
                    "seam",
                    linkedMapOf(
                        "iter" to (iter + 1),
                        "outFrames" to rec.totalFrames,
                        "ptsUs" to ptsUs,
                        "src" to "replay",
                        "trigger" to "replay_start",
                    ),
                )
                var off = 0
                while (off < ring.size && !rec.stopped && replayActive) {
                    val len = minOf(chunkBytes, ring.size - off)
                    val chunkStart = off
                    // DefaultAudioSink 要求 buffer 的 order 是 LITTLE_ENDIAN（handleBuffer 内 checkArgument）
                    val bb = ByteBuffer.allocateDirect(len).order(ByteOrder.LITTLE_ENDIAN)
                    bb.put(ring, off, len)
                    bb.flip()
                    val chunkPts = ptsUs + (off.toLong() / bpf) * 1_000_000L / sr
                    // 关键：delegate 未吃完时必须用同一个 ByteBuffer 实例重试（DefaultAudioSink 的契约）。
                    while (bb.hasRemaining() && !rec.stopped && replayActive) {
                        val posBefore = bb.position()
                        var accepted = false
                        try {
                            synchronized(delegateLock) {
                                accepted = super.handleBuffer(bb, chunkPts, 0)
                            }
                        } catch (t: Throwable) {
                            rec.event(
                                "replay_error",
                                linkedMapOf(
                                    "iter" to iter,
                                    "off" to off,
                                    "error" to t.javaClass.name,
                                    "message" to (t.message ?: ""),
                                    "stack" to t.stackTraceToString(),
                                ),
                            )
                            replayActive = false
                            return
                        }
                        val consumed = bb.position() - posBefore
                        if (consumed > 0) {
                            val data = ByteArray(consumed)
                            val dup = bb.duplicate()
                            dup.position(posBefore)
                            dup.limit(bb.position())
                            dup.get(data)
                            rec.writePcm(data, bpf)
                            rec.event(
                                "feed",
                                linkedMapOf(
                                    "frames" to consumed / bpf,
                                    "ptsUs" to chunkPts,
                                    "bytes" to consumed,
                                    "ret" to accepted,
                                    "src" to "replay",
                                    "iter" to iter,
                                ),
                            )
                            off += consumed
                        }
                        if (bb.hasRemaining()) {
                            // delegate 缓冲满，等它排空后继续用同一个 buffer 重试
                            try {
                                Thread.sleep(5L)
                            } catch (_: InterruptedException) {
                                replayActive = false
                                return
                            }
                        }
                    }
                    if (off == chunkStart) {
                        rec.event("replay_stall", linkedMapOf("iter" to iter, "off" to off))
                        break
                    }
                }
                ptsUs += loopUs
                iter += 1
                replayIterations = iter
            } catch (ie: InterruptedException) {
                break
            } catch (t: Throwable) {
                rec.event(
                    "replay_error",
                    linkedMapOf("iter" to iter, "error" to t.javaClass.name, "message" to (t.message ?: ""), "stack" to t.stackTraceToString()),
                )
                break
            }
        }
        rec.event(
            "replay_end",
            linkedMapOf("iterations" to replayIterations, "stopped" to rec.stopped, "active" to replayActive),
        )
        replayActive = false
    }

    fun stopReplay() {
        replayActive = false
        val t = replayThread
        if (t != null && t.isAlive) {
            try {
                t.join(800L)
            } catch (_: InterruptedException) {
            }
        }
    }

    fun replayIsActive(): Boolean = replayActive

    /** 仅供收尾时观察（不回填 M3 的吞 EOS 逻辑）。 */
    fun probeEnded(): Boolean = super.isEnded()

    fun probePending(): Boolean = super.hasPendingData()

    // ------------------------------------------------------------------ helpers

    private fun pcmName(encoding: Int): String = when (encoding) {
        android.media.AudioFormat.ENCODING_PCM_8BIT -> "pcm8"
        android.media.AudioFormat.ENCODING_PCM_16BIT -> "pcm16"
        android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED -> "pcm24"
        android.media.AudioFormat.ENCODING_PCM_32BIT -> "pcm32"
        android.media.AudioFormat.ENCODING_PCM_FLOAT -> "float32"
        else -> "enc:$encoding"
    }

    private fun bitsPerSample(encoding: Int): Int = when (encoding) {
        android.media.AudioFormat.ENCODING_PCM_8BIT -> 8
        android.media.AudioFormat.ENCODING_PCM_16BIT -> 16
        android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
        android.media.AudioFormat.ENCODING_PCM_32BIT, android.media.AudioFormat.ENCODING_PCM_FLOAT -> 32
        else -> 16
    }

    private fun wavFormatTag(encoding: Int): Int =
        if (encoding == android.media.AudioFormat.ENCODING_PCM_FLOAT) 3 else 1

    companion object {
        private const val TAG = "TapAudioSink"

        // M3 实验开关：true = 在 ExoPlayer playback 线程上喂（会冻结 player 消息循环）。
        private const val REPLAY_ON_PLAYBACK_THREAD = false
    }
}