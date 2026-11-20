package com.zjr.hesimusic.loop.audio.source

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.zjr.hesimusic.loop.analysis.Pcm16Frames
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 单次连续 MediaExtractor+MediaCodec 会话。
 *
 * - seek 到 A-preroll 的前一个同步点；
 * - 输出 buffer 按 PTS 对齐到 A，丢弃 preroll 和 A 之前的帧；
 * - 只回调 [A,B) 的 PCM16 交错数据，边解边回调（不整段缓存）；
 * - close 由调用方/renderer 的 finally 负责。
 */
class MediaCodecRingBandDecoder(
    private val path: String,
    private val sampleRate: Int,
    private val channels: Int,
    private val logger: (String) -> Unit = {},
) : RingBandStreamDecoder {

    @Volatile
    private var codec: MediaCodec? = null

    @Volatile
    private var extractor: MediaExtractor? = null

    /** 00022：renderer 换了填充目标时置位，stream 循环尽快退出（不销毁 codec，由 finally 释放）。 */
    @Volatile
    private var cancelled = false

    /** 释放请求：由 [close] 置位；真正的 native release 只在没有 stream 在跑时执行。 */
    @Volatile
    private var closeRequested = false

    /** stream 生命周期锁：保证 native 调用（readSampleData/getInputBuffer）与 release 互斥。 */
    private val sessionLock = Any()
    private var sessionActive = false

    override fun cancel() {
        cancelled = true
    }

    override fun stream(
        startUs: Long,
        endUs: Long,
        prerollUs: Long,
        onPcm: (data: ByteArray, size: Int) -> Unit,
    ) {
        synchronized(sessionLock) {
            if (closeRequested) return
            sessionActive = true
        }
        cancelled = false
        val bpf = channels * 2
        val targetStartFrame = Pcm16Frames.frameAtOrAfterUs(startUs, sampleRate)
        val targetEndFrame = Pcm16Frames.frameAtOrAfterUs(endUs, sampleRate)
        val targetFrames = (targetEndFrame - targetStartFrame).coerceAtLeast(1L)
        var emittedFrames = 0L
        var firstOutputLogged = false
        var firstInputLogs = 0
        val ex = MediaExtractor()
        extractor = ex
        try {
            ex.setDataSource(path)
            var trackIndex = -1
            var inputFormat: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    inputFormat = f
                    break
                }
            }
            require(trackIndex >= 0) { "no audio track: " + path }
            ex.selectTrack(trackIndex)
            val fmt = inputFormat ?: error("null input format")
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: error("missing mime")
            val seekUs = (startUs - prerollUs).coerceAtLeast(0L)
            ex.seekTo(seekUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            // 00021 取证：seek 之后 extractor 自己认为落在哪（PTS 口径）。
            RingBandLog.i(
                "RingBand decoder seek seekUs=" + seekUs +
                    " sampleTimeAfterSeek=" + ex.sampleTime +
                    " targetStartUs=" + startUs + " targetStartFrame=" + targetStartFrame,
            )
            val c = MediaCodec.createDecoderByType(mime)
            codec = c
            c.configure(fmt, null, null, 0)
            c.start()
            logger(
                "RingBand decode session open seekUs=" + seekUs +
                    " target=[" + startUs + "," + endUs + ")",
            )

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var outputPcmFloat = false
            var outputChannels = channels
            var outputBpf = bpf

            while (!outputDone && emittedFrames < targetFrames && !cancelled && !closeRequested) {
                if (!inputDone) {
                    val inIdx = c.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        val inBuf = c.getInputBuffer(inIdx)
                        if (inBuf == null) {
                            c.queueInputBuffer(inIdx, 0, 0, 0, 0)
                        } else {
                            val size = ex.readSampleData(inBuf, 0)
                            if (size < 0) {
                                c.queueInputBuffer(
                                    inIdx,
                                    0,
                                    0,
                                    0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                inputDone = true
                            } else {
                                val pts = ex.sampleTime
                                if (firstInputLogs < 3) {
                                    RingBandLog.i(
                                        "RingBand decoder input[" + firstInputLogs +
                                            "] size=" + size + " sampleTimeUs=" + pts,
                                    )
                                    firstInputLogs += 1
                                }
                                c.queueInputBuffer(inIdx, 0, size, pts, 0)
                                ex.advance()
                            }
                        }
                    }
                }

                when (val outIdx = c.dequeueOutputBuffer(info, 10_000L)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = c.outputFormat
                        val enc = if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            of.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        } else {
                            2
                        }
                        outputPcmFloat = enc == 4
                        if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            outputChannels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                        outputBpf = outputChannels * 2
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> {
                        if (outIdx >= 0) {
                            if (info.size > 0) {
                                val outBuf = c.getOutputBuffer(outIdx)
                                if (outBuf != null) {
                                    outBuf.position(info.offset)
                                    outBuf.limit(info.offset + info.size)
                                    val pcm = if (outputPcmFloat) {
                                        floatToPcm16(outBuf, outputChannels)
                                    } else {
                                        val bytes = ByteArray(outBuf.remaining())
                                        outBuf.get(bytes)
                                        bytes
                                    }
                                    val bufferStartFrame = Pcm16Frames.frameAtOrAfterUs(
                                        info.presentationTimeUs,
                                        sampleRate,
                                    )
                                    val skipFrames = (targetStartFrame - bufferStartFrame)
                                        .coerceAtLeast(0L)
                                    val framesInBuffer = (pcm.size / outputBpf).toLong()
                                    val available = (framesInBuffer - skipFrames)
                                        .coerceAtLeast(0L)
                                    val remaining = targetFrames - emittedFrames
                                    val takeFrames = minOf(available, remaining)
                                    if (takeFrames > 0L) {
                                        if (cancelled) {
                                            c.releaseOutputBuffer(outIdx, false)
                                            break
                                        }
                                        val from = (skipFrames * outputBpf).toInt()
                                        val bytes = (takeFrames * outputBpf).toInt()
                                        if (!firstOutputLogged) {
                                            firstOutputLogged = true
                                            // firstSamples 是**跳过 A 之前样本后**真正写入环带的首批样本，
                                            // 应与 song40169.pcm 的 A 帧逐样本一致（00021 验收口径）。
                                            RingBandLog.i(
                                                "RingBand decode firstPtsUs=" + info.presentationTimeUs +
                                                    " targetStartUs=" + startUs +
                                                    " bufferStartFrame=" + bufferStartFrame +
                                                    " targetStartFrame=" + targetStartFrame +
                                                    " skipFrames=" + skipFrames +
                                                    " framesInBuffer=" + framesInBuffer +
                                                    " takeFrames=" + takeFrames +
                                                    " firstSamples=[" +
                                                    firstSamples(pcm, from, outputBpf) + "]",
                                            )
                                        }
                                        onPcm(pcm.copyOfRange(from, from + bytes), bytes)
                                        emittedFrames += takeFrames
                                    } else if (bufferStartFrame > targetStartFrame + emittedFrames + sampleRate) {
                                        logger(
                                            "RingBand decode gap bufferStartFrame=" +
                                                bufferStartFrame + " targetStart=" + targetStartFrame +
                                                " emitted=" + emittedFrames,
                                        )
                                    }
                                }
                            }
                            c.releaseOutputBuffer(outIdx, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                outputDone = true
                            }
                        }
                    }
                }
            }
            if (cancelled) {
                logger(
                    "RingBand decode cancelled emitted=" + emittedFrames +
                        " target=" + targetFrames,
                )
            } else if (emittedFrames < targetFrames) {
                logger(
                    "RingBand decode short emitted=" + emittedFrames +
                        " target=" + targetFrames,
                )
            }
        } finally {
            // native 资源只允许在**跑 stream 的线程**上释放（会话内所有 native 调用已返回）。
            synchronized(sessionLock) {
                releaseSessionLocked()
                sessionActive = false
            }
            logger("RingBand decode session close emittedFrames=" + emittedFrames)
        }
    }

    /** 调用方必须在持有 [sessionLock] 且 [sessionActive] == false 时调用。 */
    private fun releaseSessionLocked() {
        try {
            codec?.stop()
        } catch (_: Throwable) {
        }
        try {
            codec?.release()
        } catch (_: Throwable) {
        }
        try {
            extractor?.release()
        } catch (_: Throwable) {
        }
        codec = null
        extractor = null
    }

    /** 前 8 个交错 int16（L,R,L,R...），给离线脚本与源 PCM 逐样本比对。 */
    private fun firstSamples(
        pcm: ByteArray,
        fromBytes: Int,
        bytesPerFrame: Int,
        count: Int = 8,
    ): String {
        if (bytesPerFrame <= 0 || fromBytes < 0 || fromBytes >= pcm.size) return "-"
        val end = minOf(pcm.size, fromBytes + count * 2)
        val bb = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        val sb = StringBuilder()
        var i = fromBytes
        while (i + 1 < end) {
            if (sb.isNotEmpty()) sb.append(',')
            sb.append(bb.getShort(i).toInt())
            i += 2
        }
        return sb.toString()
    }

    private fun floatToPcm16(src: ByteBuffer, ch: Int): ByteArray {
        val floats = src.order(ByteOrder.nativeOrder()).asFloatBuffer()
        val count = floats.remaining()
        val out = ByteArray(count * 2)
        val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        while (floats.hasRemaining()) {
            val v = (floats.get().coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            bb.putShort(v)
        }
        return out
    }

    /**
     * 释放会话；幂等，可从任意线程调用。
     *
     * 若此时有 stream 正在跑，**只置标志**（cancel + closeRequested），native 释放推迟到
     * stream 所在线程的 finally —— 跨线程 close 会在 readSampleData/getInputBuffer 中途
     * 释放 native 结构，真机表现为 SIGSEGV in __memcpy（seek-ux 已复现）。
     */
    override fun close() {
        closeRequested = true
        cancelled = true
        synchronized(sessionLock) {
            if (!sessionActive) releaseSessionLocked()
        }
    }
}