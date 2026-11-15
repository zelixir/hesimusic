package com.zjr.spike

import android.os.SystemClock
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 包住 DefaultAudioSink 的取证层（CONTRACT §4/§3）。
 *
 * 两种重放形态：
 *  - mode=sink（M3'）：PCM 层环回，**始终通过 delegate(DefaultAudioSink) 喂数据**。
 *    输入在 B 处截断（Clip 到 B），到达 B 后切换到 ring 重放；重放由 renderer 对
 *    playToEndOfStream()/isEnded() 的 ~100Hz 轮询驱动（delegate 泵送），
 *    不调用 delegate.playToEndOfStream()、不进入 ended 态 → AudioTrack 不 stop、
 *    位置持续前进、无 configure/discontinuity/flush。
 *  - 其余 mode：只做取证（PCM 落盘 + sink 调用序列 + baseline/window 的 seam 打点）。
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

    // seam 打点（baseline / window）
    private val seamMode: Boolean = cfg.mode == "baseline" || cfg.mode == "window"
    private val mediaMode: Boolean = cfg.mode == "media"

    @Volatile
    private var mediaSeamArmed: Boolean = false

    @Volatile
    private var mediaSeamIter: Int = 0

    @Volatile
    private var mediaSeamTargetRawUs: Long = 0L
    private var lastConsumedPtsUs: Long = -1L
    private var seamArmed: Boolean = false
    private var seamRound: Int = 0

    @Volatile
    private var lastPositionUs: Long = 0L

    // ExoPlayer 给 feed.ptsUs 加了一个全局输出偏移（1e12），capture 需要减掉它才是曲内时间。
    @Volatile
    private var outputStreamOffsetUs: Long = 0L

    private val sinkMode: Boolean = cfg.mode == "sink"

    // ---- 捕获 [A,B) ----
    private val captureLock = Any()
    private val captured = ByteArrayOutputStream()
    private var capturedFrames = 0L
    private var captureLoggedBegin = false
    private var captureLoggedEnd = false

    // ---- M3' 重放状态（全部在 playback 线程上；无独立线程） ----
    @Volatile
    private var replayActive: Boolean = false

    @Volatile
    private var replayDisabled: Boolean = false

    @Volatile
    private var degradeRequested: Boolean = false

    /** ring 就绪后的完整 PCM 与帧数。 */
    private var ring: ByteArray? = null
    private var ringFrames: Long = 0L
    private var replayRound: Int = 0
    private var replayOffset: Int = 0
    private var replayBuffer: ByteBuffer? = null
    private var lastSeamRound: Int = 0
    private var pumpCalls: Long = 0L
    private var lastPosLogMs: Long = 0L
    private var lastFeedEndUs: Long = 0L

    @Volatile
    var onNeedFullRing: (() -> Unit)? = null

    // ---------------------------------------------------------------- configure

    override fun configure(format: Format, bufferSize: Int, audioTrackChannelConfig: IntArray?) {
        val bitsPerSample = bitsPerSample(format.pcmEncoding)
        val bpf = bitsPerSample / 8 * format.channelCount
        sampleRate = format.sampleRate
        channels = format.channelCount
        bytesPerFrame = if (bpf > 0) bpf else 4
        replayDisabled = false
        resetReplayState()
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
        if (sinkMode) return handleSinkBuffer(buffer, presentationTimeUs)
        return handleTapBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
    }

    /** 非 sink 模式：照旧取证 + baseline/window/media 的 seam。 */
    private fun handleTapBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
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
            val data = readBytes(buffer, startPos, endPos)
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
            if (mediaMode && mediaSeamArmed) {
                val targetPtsUs = outputStreamOffsetUs + mediaSeamTargetRawUs
                if (presentationTimeUs >= targetPtsUs - 200L) {
                    mediaSeamArmed = false
                    rec.event(
                        "seam",
                        linkedMapOf(
                            "iter" to mediaSeamIter,
                            "outFrames" to cumulativeBefore,
                            "ptsUs" to presentationTimeUs,
                            "src" to "media",
                            "trigger" to "source",
                            "targetPtsUs" to targetPtsUs,
                        ),
                    )
                }
            }
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
     * M3'：输入在 B 处截断；到 B 后切换为 ring 重放，之后输入全部丢弃（返回 true 保持管线流动）。
     */
    private fun handleSinkBuffer(buffer: ByteBuffer, presentationTimeUs: Long): Boolean {
        val startPos = buffer.position()
        val mediaPts = presentationTimeUs - outputStreamOffsetUs
        val bpf = bytesPerFrame
        val sr = sampleRate

        if (replayActive) {
            val bytes = buffer.limit() - startPos
            buffer.position(buffer.limit())
            if (bytes > 0) {
                rec.event(
                    "feed_discard",
                    linkedMapOf(
                        "bytes" to bytes,
                        "frames" to bytes / bpf,
                        "ptsUs" to presentationTimeUs,
                        "reason" to "replay",
                    ),
                )
            }
            pumpReplay()
            return true
        }

        val bFrame = cfg.bMs.toLong() * sr / 1000L
        val startFrame = mediaPts * sr / 1_000_000L
        val boundary = ((bFrame - startFrame) * bpf).toInt()

        if (boundary <= startPos) {
            val bytes = buffer.limit() - startPos
            buffer.position(buffer.limit())
            if (bytes > 0) {
                rec.event(
                    "feed_discard",
                    linkedMapOf(
                        "bytes" to bytes,
                        "frames" to bytes / bpf,
                        "ptsUs" to presentationTimeUs,
                        "reason" to "past-b",
                    ),
                )
            }
            lastFeedEndUs = presentationTimeUs + (bytes / bpf) * 1_000_000L / sr
            startReplay()
            pumpReplay()
            return true
        }

        var hitEnd = false
        if (boundary < buffer.limit()) {
            val trimmed = buffer.limit() - boundary
            buffer.limit(boundary)
            hitEnd = true
            rec.event(
                "trim_at_b",
                linkedMapOf(
                    "ptsUs" to presentationTimeUs,
                    "trimmedBytes" to trimmed,
                    "trimmedFrames" to trimmed / bpf,
                ),
            )
        }

        val ret: Boolean
        synchronized(delegateLock) {
            ret = super.handleBuffer(buffer, presentationTimeUs, 1)
        }
        val consumed = buffer.position() - startPos
        lastPtsUs = presentationTimeUs
        if (consumed > 0) {
            val data = readBytes(buffer, startPos, buffer.position())
            rec.writePcm(data, bpf)
            val frames = consumed / bpf
            lastFeedEndUs = presentationTimeUs + frames * 1_000_000L / sr
            rec.event(
                "feed",
                linkedMapOf(
                    "frames" to frames,
                    "ptsUs" to presentationTimeUs,
                    "bytes" to consumed,
                    "ret" to ret,
                ),
            )
            captureRegion(data, startPos, presentationTimeUs)
        } else {
            rec.event(
                "feed_wait",
                linkedMapOf(
                    "ptsUs" to presentationTimeUs,
                    "ret" to ret,
                ),
            )
        }
        if (hitEnd && !buffer.hasRemaining()) {
            startReplay()
            pumpReplay()
        }
        return ret
    }

    private fun readBytes(buffer: ByteBuffer, from: Int, to: Int): ByteArray {
        val dup = buffer.duplicate()
        dup.position(from)
        dup.limit(to)
        val data = ByteArray(to - from)
        dup.get(data)
        return data
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

    /** 从 delegate 实际消费的这段 PCM 中，精确截取落在 [aMs,bMs) 的部分（按帧）。 */
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

    // ------------------------------------------------------------ EOS / 泵送

    override fun playToEndOfStream() {
        eosSeen = true
        if (sinkMode) {
            pumpCalls += 1
            if (pumpCalls <= 3 || pumpCalls % 500 == 0L) {
                rec.event(
                    "pump",
                    linkedMapOf(
                        "via" to "play_to_eos",
                        "calls" to pumpCalls,
                        "replayActive" to replayActive,
                        "round" to replayRound,
                        "offset" to replayOffset,
                    ),
                )
            }
            startReplay()
            pumpReplay()
            // 关键：不调用 delegate.playToEndOfStream()，AudioTrack 不会 stop。
            return
        }
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
    }

    override fun isEnded(): Boolean {
        if (sinkMode) {
            pumpReplay()
            if (replayActive) {
                if (lastEnded) {
                    lastEnded = false
                    rec.event("eos_swallow", linkedMapOf("round" to replayRound, "offset" to replayOffset))
                }
                return false
            }
        }
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
        if (sinkMode && replayActive) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastPosLogMs >= 500L) {
                lastPosLogMs = now
                rec.event(
                    "pos",
                    linkedMapOf(
                        "us" to pos,
                        "mediaUs" to pos - outputStreamOffsetUs,
                        "round" to replayRound,
                        "ringOffset" to replayOffset,
                    ),
                )
            }
        }
        if (pos + 50_000L < lastPositionUs) {
            rec.event("position_reset", linkedMapOf("fromUs" to lastPositionUs, "toUs" to pos))
        }
        lastPositionUs = pos
        return pos
    }

    override fun setOutputStreamOffsetUs(offsetUs: Long) {
        outputStreamOffsetUs = offsetUs
        rec.event("output_stream_offset", linkedMapOf("offsetUs" to offsetUs))
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
        if (sinkMode) resetReplayState()
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
        if (sinkMode) resetReplayState()
        super.reset()
    }

    override fun release() {
        rec.event("sink_release", linkedMapOf("totalFrames" to rec.totalFrames))
        probe.stopSampling()
        replayDisabled = true
        replayActive = false
        super.release()
    }

    // -------------------------------------------------------------- M3' 重放

    private fun expectedRingFrames(): Long = (cfg.bMs - cfg.aMs).toLong() * sampleRate / 1000L

    /** ring 就绪（[A,B) 捕获完整）后切换为 delegate 泵送重放。 */
    private fun startReplay() {
        if (replayActive) return
        val expected = expectedRingFrames()
        val have = capturedFrames
        if (have < expected - 2) {
            if (!degradeRequested && onNeedFullRing != null) {
                degradeRequested = true
                rec.event(
                    "replay_degraded",
                    linkedMapOf(
                        "capturedFrames" to have,
                        "expectedFrames" to expected,
                        "action" to "seek-to-A",
                    ),
                )
                onNeedFullRing?.invoke()
            }
            return
        }
        val bytes = synchronized(captureLock) { captured.toByteArray() }
        ring = bytes
        ringFrames = (bytes.size / bytesPerFrame).toLong()
        if (replayDisabled) return
        replayActive = true
        replayRound = 1
        replayOffset = 0
        replayBuffer = null
        lastSeamRound = 0
        rec.event(
            "looping",
            linkedMapOf(
                "on" to true,
                "aMs" to cfg.aMs,
                "bMs" to cfg.bMs,
                "ringFrames" to ringFrames,
                "ringBytes" to bytes.size,
                "mode" to "delegate-pump",
                "startPtsUs" to cfg.bUs,
            ),
        )
        pumpReplay()
    }

    /**
     * 把 ring 写进 delegate。每次调用尽量写满 delegate 的可用空间；renderer 对
     * playToEndOfStream()/isEnded() 的 ~100Hz 轮询负责持续驱动（泵）。
     */
    private fun pumpReplay() {
        if (!replayActive) return
        val ring = ring ?: return
        val bpf = bytesPerFrame
        val sr = sampleRate
        if (bpf <= 0 || sr <= 0 || ring.isEmpty()) return
        val chunkBytes = maxOf(bpf * 512, bpf * (sr / 100))
        var guard = 0
        while (guard++ < 128) {
            if (replayOffset >= ring.size) {
                replayOffset = 0
                replayRound += 1
                replayBuffer = null
            }
            if (replayOffset == 0 && lastSeamRound != replayRound) {
                lastSeamRound = replayRound
                val roundPts = cfg.bUs + (replayRound - 1).toLong() * cfg.loopUs
                rec.event(
                    "seam",
                    linkedMapOf(
                        "iter" to replayRound,
                        "outFrames" to rec.totalFrames,
                        "ptsUs" to roundPts,
                        "src" to "sink2",
                        "trigger" to "pump",
                    ),
                )
            }
            var bb = replayBuffer
            if (bb == null) {
                val len = minOf(chunkBytes, ring.size - replayOffset)
                val buf = ByteBuffer.allocateDirect(len).order(ByteOrder.LITTLE_ENDIAN)
                buf.put(ring, replayOffset, len)
                buf.flip()
                replayBuffer = buf
                bb = buf
            }
            val roundPts = cfg.bUs + (replayRound - 1).toLong() * cfg.loopUs
            val chunkPts = roundPts + (replayOffset.toLong() / bpf) * 1_000_000L / sr
            val before = bb.position()
            val accepted: Boolean
            try {
                synchronized(delegateLock) {
                    accepted = super.handleBuffer(bb, chunkPts, 1)
                }
            } catch (t: Throwable) {
                rec.event(
                    "replay_error",
                    linkedMapOf(
                        "round" to replayRound,
                        "offset" to replayOffset,
                        "error" to t.javaClass.name,
                        "message" to (t.message ?: ""),
                        "stack" to t.stackTraceToString(),
                    ),
                )
                replayActive = false
                return
            }
            val consumed = bb.position() - before
            if (consumed > 0) {
                val data = readBytes(bb, before, bb.position())
                rec.writePcm(data, bpf)
                rec.event(
                    "feed",
                    linkedMapOf(
                        "frames" to consumed / bpf,
                        "ptsUs" to chunkPts,
                        "bytes" to consumed,
                        "ret" to accepted,
                        "src" to "sink2",
                        "iter" to replayRound,
                    ),
                )
                replayOffset += consumed
            }
            if (bb.hasRemaining()) {
                // delegate 缓冲满：等下一次泵（renderer 的 ~100Hz 轮询）
                return
            }
            replayBuffer = null
        }
    }

    private fun resetReplayState() {
        replayActive = false
        replayBuffer = null
        ring = null
        ringFrames = 0L
        replayRound = 0
        replayOffset = 0
        lastSeamRound = 0
        degradeRequested = false
        lastFeedEndUs = 0L
        synchronized(captureLock) {
            captured.reset()
            capturedFrames = 0L
            captureLoggedBegin = false
            captureLoggedEnd = false
        }
    }

    fun replayIsActive(): Boolean = replayActive

    /** 收尾用：停止泵送（不做线程 join，M3' 无独立线程）。 */
    fun stopReplay() {
        replayDisabled = true
        replayActive = false
    }

    /** M1：源层通知新一轮起点（第一个样本改写后的时间戳），tap 在下一次喂到该 pts 时打 seam。 */
    fun armSeam(iter: Int, firstPtsUs: Long) {
        mediaSeamIter = iter
        mediaSeamTargetRawUs = firstPtsUs
        mediaSeamArmed = true
    }

    fun probeEnded(): Boolean = super.isEnded()

    fun probePending(): Boolean = super.hasPendingData()

    // ------------------------------------------------------------------ helpers

    private fun pcmName(encoding: Int): String = when (encoding) {
        android.media.AudioFormat.ENCODING_PCM_8BIT -> "pcm8"
        android.media.AudioFormat.ENCODING_PCM_16BIT -> "pcm16"
        android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED -> "pcm24"
        android.media.AudioFormat.ENCODING_PCM_32BIT -> "pcm32"
        android.media.AudioFormat.ENCODING_PCM_FLOAT -> "float32"
        else -> "enc:" + encoding
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
}