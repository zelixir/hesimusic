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

    override fun stream(
        startUs: Long,
        endUs: Long,
        prerollUs: Long,
        onPcm: (data: ByteArray, size: Int) -> Unit,
    ) {
        val bpf = channels * 2
        val targetStartFrame = Pcm16Frames.frameAtOrAfterUs(startUs, sampleRate)
        val targetEndFrame = Pcm16Frames.frameAtOrAfterUs(endUs, sampleRate)
        val targetFrames = (targetEndFrame - targetStartFrame).coerceAtLeast(1L)
        var emittedFrames = 0L
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

            while (!outputDone && emittedFrames < targetFrames) {
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
                                        val from = (skipFrames * outputBpf).toInt()
                                        val bytes = (takeFrames * outputBpf).toInt()
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
            if (emittedFrames < targetFrames) {
                logger(
                    "RingBand decode short emitted=" + emittedFrames +
                        " target=" + targetFrames,
                )
            }
        } finally {
            try {
                codec?.stop()
            } catch (_: Throwable) {
            }
            try {
                codec?.release()
            } catch (_: Throwable) {
            }
            try {
                ex.release()
            } catch (_: Throwable) {
            }
            codec = null
            extractor = null
            logger("RingBand decode session close emittedFrames=" + emittedFrames)
        }
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

    override fun close() {
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
}
