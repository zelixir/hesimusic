package com.zjr.hesimusic.loop.audio.standalone

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 独立内核的音频格式探测结果。 */
data class StandaloneFormat(
    val sampleRate: Int,
    val channels: Int,
    val durationUs: Long,
)

/**
 * 只用 MediaExtractor + MediaCodec 的范围解码器（不依赖 media3 / ExoPlayer）。
 *
 * 与生产 [com.zjr.hesimusic.loop.audio.source.MediaCodecRingBandDecoder] 同口径：
 * - 先 seek 到 startUs - preroll 的前一个同步点；
 * - 以输出 buffer 的 PTS 换算成帧号，按 `ceil(us*sr/1e6)` 对齐；
 * - 只回调 [startUs, endUs) 对应的精确帧，不补零、不静默截短。
 *
 * 每次 decode 都创建/释放一个 MediaCodec 会话；分块模式每块一次，整环模式一次。
 * 这是原型实现；生产化应改为常驻解码会话（见报告）。
 */
class StandaloneRangeDecoder(
    private val path: String,
    private val sampleRate: Int,
    private val channels: Int,
    private val prerollUs: Long = 500_000L,
) {
    private val bytesPerFrame: Int = channels * 2

    @Volatile
    private var cancelled: Boolean = false

    @Volatile
    private var codec: MediaCodec? = null

    @Volatile
    private var extractor: MediaExtractor? = null

    /** 取消当前解码；decode 循环会在下一次 10ms 轮询时退出。 */
    fun cancel() {
        cancelled = true
    }

    /**
     * 解码 [startUs, endUs) 并逐段回调 PCM16 交错数据。
     *
     * @return 实际输出的帧数（等于 `ceil(end*sr/1e6) - ceil(start*sr/1e6)`）。
     * @throws StandaloneDecodeException 解码失败/短于目标/格式不符。
     */
    fun decode(startUs: Long, endUs: Long, onPcm: (ByteArray, Int) -> Unit): Long {
        require(startUs >= 0L) { "startUs must be >= 0" }
        require(endUs > startUs) { "endUs must be > startUs" }
        val targetStartFrame = StandaloneWindow.frameCeil(startUs, sampleRate)
        val targetEndFrame = StandaloneWindow.frameCeil(endUs, sampleRate)
        val targetFrames = targetEndFrame - targetStartFrame
        require(targetFrames > 0L) { "range shorter than one frame" }

        var emittedFrames = 0L
        val ex = MediaExtractor()
        extractor = ex
        var c: MediaCodec? = null
        try {
            try {
                ex.setDataSource(path)
            } catch (t: Throwable) {
                throw StandaloneDecodeException("cannot open audio file: $path", t)
            }
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
            if (trackIndex < 0 || inputFormat == null) {
                throw StandaloneDecodeException("no audio track: $path")
            }
            ex.selectTrack(trackIndex)
            val fmt = inputFormat
            val mime = fmt.getString(MediaFormat.KEY_MIME)
                ?: throw StandaloneDecodeException("missing audio mime")

            val seekUs = (startUs - prerollUs).coerceAtLeast(0L)
            ex.seekTo(seekUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            c = try {
                MediaCodec.createDecoderByType(mime)
            } catch (t: Throwable) {
                throw StandaloneDecodeException("device cannot decode $mime", t)
            }
            codec = c
            c.configure(fmt, null, null, 0)
            c.start()

            var outputChannels = channels
            var outputBpf = bytesPerFrame
            var pcmFloat = false
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone && emittedFrames < targetFrames) {
                if (cancelled || Thread.currentThread().isInterrupted) {
                    throw StandaloneDecodeException("decode cancelled")
                }

                if (!inputDone) {
                    val inIdx = c.dequeueInputBuffer(TIMEOUT_US)
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
                                c.queueInputBuffer(inIdx, 0, size, ex.sampleTime, 0)
                                ex.advance()
                            }
                        }
                    }
                }

                when (val outIdx = c.dequeueOutputBuffer(info, TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = c.outputFormat
                        val enc = if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            of.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        } else {
                            AudioFormat.ENCODING_PCM_16BIT
                        }
                        pcmFloat = enc == AudioFormat.ENCODING_PCM_FLOAT
                        if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            outputChannels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                        if (outputChannels != channels) {
                            throw StandaloneDecodeException(
                                "decoder output channels=$outputChannels != probe $channels",
                            )
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
                                    val pcm = if (pcmFloat) {
                                        floatToPcm16(outBuf)
                                    } else {
                                        val bytes = ByteArray(outBuf.remaining())
                                        outBuf.get(bytes)
                                        bytes
                                    }
                                    val framesInBuffer = pcm.size / outputBpf
                                    if (framesInBuffer > 0) {
                                        val bufferStartFrame = StandaloneWindow.frameCeil(
                                            info.presentationTimeUs,
                                            sampleRate,
                                        )
                                        val skipFrames = (targetStartFrame - bufferStartFrame)
                                            .coerceAtLeast(0L)
                                        if (emittedFrames == 0L && bufferStartFrame > targetStartFrame) {
                                            throw StandaloneDecodeException(
                                                "decoder seek overshot: bufferStart=$bufferStartFrame " +
                                                    "targetStart=$targetStartFrame ptsUs=${info.presentationTimeUs}",
                                            )
                                        }
                                        val available = framesInBuffer - skipFrames
                                        val remaining = targetFrames - emittedFrames
                                        val takeFrames = minOf(available, remaining)
                                        if (takeFrames > 0L) {
                                            val from = (skipFrames * outputBpf).toInt()
                                            val len = (takeFrames * outputBpf).toInt()
                                            onPcm(pcm.copyOfRange(from, from + len), len)
                                            emittedFrames += takeFrames
                                        }
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

            if (emittedFrames < targetFrames) {
                throw StandaloneDecodeException(
                    "decode short: emitted=$emittedFrames target=$targetFrames " +
                        "startUs=$startUs endUs=$endUs",
                )
            }
            return emittedFrames
        } catch (e: StandaloneDecodeException) {
            throw e
        } catch (t: Throwable) {
            throw StandaloneDecodeException(t.message ?: "decode failed", t)
        } finally {
            try {
                c?.stop()
            } catch (_: Throwable) {
            }
            try {
                c?.release()
            } catch (_: Throwable) {
            }
            try {
                ex.release()
            } catch (_: Throwable) {
            }
            codec = null
            extractor = null
        }
    }

    private fun floatToPcm16(src: ByteBuffer): ByteArray {
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

    companion object {
        private const val TIMEOUT_US = 10_000L

        /** 只探测音频轨格式（不解码）。 */
        fun probe(path: String): StandaloneFormat {
            val ex = MediaExtractor()
            try {
                ex.setDataSource(path)
                for (i in 0 until ex.trackCount) {
                    val f = ex.getTrackFormat(i)
                    val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                    if (!mime.startsWith("audio/")) continue
                    val sr = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    val ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    val duration = if (f.containsKey(MediaFormat.KEY_DURATION)) {
                        f.getLong(MediaFormat.KEY_DURATION)
                    } else {
                        0L
                    }
                    if (sr <= 0 || ch <= 0) {
                        throw StandaloneDecodeException("invalid audio format sr=$sr ch=$ch")
                    }
                    return StandaloneFormat(sr, ch, duration)
                }
                throw StandaloneDecodeException("no audio track: $path")
            } catch (e: StandaloneDecodeException) {
                throw e
            } catch (t: Throwable) {
                throw StandaloneDecodeException("probe failed: $path", t)
            } finally {
                try {
                    ex.release()
                } catch (_: Throwable) {
                }
            }
        }
    }
}

class StandaloneDecodeException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)
