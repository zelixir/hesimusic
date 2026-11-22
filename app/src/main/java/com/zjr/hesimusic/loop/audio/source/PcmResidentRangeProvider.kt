package com.zjr.hesimusic.loop.audio.source

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.zjr.hesimusic.loop.analysis.Pcm16Frames
import com.zjr.hesimusic.loop.analysis.Pcm16Segment
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 前奏/尾奏块解码的**常驻解码会话**（issue 00029）。
 *
 * 00028 的块路径每解一块都新建 MediaExtractor+MediaCodec，并各自 seek 到块首前 250ms。
 * 这种做法有两个结构性问题：
 * 1. **块边界爆音**：每次 seek 后 MediaCodec 的首个输出 PTS / priming 处理并不完全
 *    一致，PcmDecoder 按“首 PTS + 帧数”对齐后，每块的真实起点会漂移 0~30 帧
 *    （真机实测 prelude/tail 每 1.000s 边界偏移在 -20..+32 帧之间变化），块拼接处
 *    因此出现样本级台阶（听感 = 每几秒一次小爆音）。
 * 2. **区外 seek 慢**：一次 seek 要重建 codec（真机 120~280ms），而环内 seek 是常数时间。
 *
 * 本类把同一个 extractor/codec 会话保留下来：
 * - 顺序块请求（当前块 → 前 1/2 块）**不 seek、不 flush**，直接从上一块的下一帧继续取；
 * - 只有跨块跳读（用户 seek / 缓存淘汰后重解）才 seek 一次；
 * - 帧号从会话首个输出 PTS 锚定后按帧数连续推进，块与块之间天然逐样本连续；
 * - 会话首个输出晚于请求起点（MP3 文件头 priming，真机 anchorUs≈40ms）时补静音，
 *   而不是像 PcmDecoder 那样抛“解码起点晚于区间起点”并丢掉整个首块。
 *
 * 线程模型：所有 [decode]/[decodeAllowShort] 只能由**同一个解码线程**调用
 * （[PcmRangeBlockReader] 的单线程 executor）。[release] 可从任意线程调用；若此时
 * 正在解码，只置标志，native 释放在解码线程的 finally 里做（同
 * [MediaCodecRingBandDecoder] 的 SIGSEGV 防护）。
 */
class PcmResidentRangeProvider(
    private val path: String,
    private val expectedSampleRate: Int,
    private val expectedChannels: Int,
    private val logger: (String) -> Unit = {},
    /** seek 时多解的前导时长（裁掉后再对齐请求起点）。 */
    private val seekPrerollUs: Long = DEFAULT_SEEK_PREROLL_US,
) : PcmShortRangeProvider, ReleasablePcmRangeProvider {

    private val lock = Any()

    private var extractor: MediaExtractor? = null
    private var codec: MediaCodec? = null

    private var outputSampleRate: Int = expectedSampleRate
    private var outputChannels: Int = expectedChannels
    private var outputBytesPerFrame: Int = expectedChannels * 2
    private var outputPcmFloat: Boolean = false

    /** 会话内“下一个应输出帧”的绝对媒体帧号；<0 = 会话刚打开/seek 后尚未见到首个 buffer。 */
    private var nextFrame: Long = -1L

    /** 上一块末尾多解出来的样本（下一块开头直接消费，避免丢帧/重复）。 */
    private var pending: ByteArray? = null
    private var pendingFrame: Long = -1L

    private var inputDone: Boolean = false
    private var outputDone: Boolean = false

    @Volatile
    private var released: Boolean = false

    /** 是否有 decode 正在本类内部执行（决定 release 能否立即释放 native）。 */
    private var active: Boolean = false

    /** 诊断：会话打开次数 / seek 次数 / 解码范围次数。 */
    @Volatile
    var sessionOpens: Long = 0L
        private set

    @Volatile
    var seekCount: Long = 0L
        private set

    @Volatile
    var decodeCount: Long = 0L
        private set

    /** 诊断：PTS 时间轴与本类按样本数推进的 nextFrame 之间的累计差（帧）。 */
    @Volatile
    var ptsDriftFrames: Long = 0L
        private set

    private var ptsBuffers: Long = 0L

    /** 最近一次会话打开失败原因（诊断用）。 */
    @Volatile
    var lastError: String? = null
        private set

    override fun decode(startUs: Long, endUs: Long): Pcm16Segment =
        decodeInternal(startUs, endUs, allowShort = false)

    override fun decodeAllowShort(startUs: Long, endUs: Long): Pcm16Segment =
        decodeInternal(startUs, endUs, allowShort = true)

    override fun release() {
        synchronized(lock) {
            released = true
            if (!active) closeNativeLocked()
        }
    }

    // ------------------------------------------------------------------ 解码

    private fun decodeInternal(startUs: Long, endUs: Long, allowShort: Boolean): Pcm16Segment {
        synchronized(lock) {
            if (released) throw IllegalStateException("resident decoder released")
            active = true
        }
        try {
            if (endUs <= startUs) throw IllegalArgumentException("range must be non-empty")
            val sampleRate = outputSampleRate
            val startFrame = Pcm16Frames.frameAtOrAfterUs(startUs, sampleRate)
            val endFrame = Pcm16Frames.frameAtOrAfterUs(endUs, sampleRate)
            val wantFrames = (endFrame - startFrame).coerceAtLeast(0L)
            if (wantFrames <= 0L) {
                return Pcm16Segment(ByteArray(0), outputSampleRate, outputChannels, 0L)
            }
            ensureSession()
            if (!canContinueFrom(startFrame)) seekTo(startFrame)
            decodeCount += 1L

            val out = ByteArray((wantFrames * outputBytesPerFrame).toInt())
            var outFrames = 0L
            outFrames += consumePending(startFrame, endFrame, out, outFrames)

            val info = MediaCodec.BufferInfo()
            while (outFrames < wantFrames) {
                if (released) throw IllegalStateException("resident decoder released during decode")
                feedInput()
                when (val outIdx = codec!!.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> applyOutputFormatLocked()
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (outputDone) break
                    }
                    else -> {
                        if (outIdx >= 0) {
                            if (info.size > 0) {
                                val pcm = readOutputBytes(outIdx, info)
                                if (pcm.isNotEmpty()) {
                                    outFrames += consumeBufferLocked(
                                        pcm = pcm,
                                        presentationTimeUs = info.presentationTimeUs,
                                        startFrame = startFrame,
                                        endFrame = endFrame,
                                        out = out,
                                        outFrames = outFrames,
                                    )
                                }
                            }
                            codec!!.releaseOutputBuffer(outIdx, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                outputDone = true
                            }
                        }
                    }
                }
                if (outputDone && outFrames < wantFrames) {
                    if (pending == null) break
                }
            }
            if (outFrames < wantFrames) {
                if (allowShort) {
                    val bytesShort = (outFrames * outputBytesPerFrame).toInt()
                    return Pcm16Segment(
                        data = if (bytesShort > 0) out.copyOf(bytesShort) else ByteArray(0),
                        sampleRate = outputSampleRate,
                        channels = outputChannels,
                        frames = outFrames,
                    )
                }
                throw IllegalStateException(
                    "resident decode short: want=" + wantFrames + " got=" + outFrames +
                        " startFrame=" + startFrame + " eos=" + outputDone,
                )
            }
            return Pcm16Segment(out, outputSampleRate, outputChannels, wantFrames)
        } catch (t: Throwable) {
            // 会话出错后下一次请求重新打开；避免半坏状态被复用。
            synchronized(lock) { closeNativeLocked() }
            lastError = (t.message ?: t.javaClass.simpleName)
            throw t
        } finally {
            synchronized(lock) {
                active = false
                if (released) closeNativeLocked()
            }
        }
    }

    /** 顺序请求：上一块结束的下一帧，或 pending 里刚好接着。 */
    private fun canContinueFrom(startFrame: Long): Boolean {
        if (nextFrame < 0L) return false
        // 跨太远的跳读必须 seek，绝不能靠“顺序解到那里”追（一次 seek 84s 会卡 8s+）。
        val p = pending
        if (p != null) {
            val pendingEnd = pendingFrame + p.size / outputBytesPerFrame
            if (startFrame in pendingFrame..pendingEnd) return true
            if (startFrame == nextFrame) return true
            return false
        }
        if (startFrame == nextFrame) return true
        // 已连续跳到下一个块：只有目标就在下一块的合理范围内才继续。
        return startFrame > nextFrame && startFrame - nextFrame <= outputSampleRate.toLong() * 2L
    }

    /**
     * 消费 pending（上一块多解的样本）。返回本次消费的帧数。
     *
     * pending 覆盖 [pendingFrame, pendingFrame+pendingFrames)；它可能从 startFrame
     * 之前开始（跳过），也可能晚于 startFrame（缺口补静音，正常不应发生）。
     */
    private fun consumePending(startFrame: Long, endFrame: Long, out: ByteArray, outFrames: Long): Long {
        val p = pending ?: return 0L
        val pendingFrames = p.size / outputBytesPerFrame
        val from = maxOf(startFrame, pendingFrame)
        val to = minOf(endFrame, pendingFrame + pendingFrames)
        var produced = 0L
        if (from > startFrame && outFrames == 0L) {
            produced += fillSilence(startFrame, from, out, outFrames + produced)
        }
        if (to > from) {
            val srcOffset = ((from - pendingFrame) * outputBytesPerFrame).toInt()
            val len = ((to - from) * outputBytesPerFrame).toInt()
            val dst = ((outFrames + produced) * outputBytesPerFrame).toInt()
            System.arraycopy(p, srcOffset, out, dst, len)
            produced += (to - from)
        }
        // 丢弃 pending 中不再需要的前缀；保留后半段给下一次请求。
        val remainingFrom = maxOf(to, pendingFrame)
        val remainingFrames = pendingFrame + pendingFrames - remainingFrom
        if (remainingFrames > 0L) {
            val off = ((remainingFrom - pendingFrame) * outputBytesPerFrame).toInt()
            pending = p.copyOfRange(off, p.size)
            pendingFrame = remainingFrom
        } else {
            pending = null
            pendingFrame = -1L
        }
        return produced
    }

    private fun consumeBufferLocked(
        pcm: ByteArray,
        presentationTimeUs: Long,
        startFrame: Long,
        endFrame: Long,
        out: ByteArray,
        outFrames: Long,
    ): Long {
        val frames = (pcm.size / outputBytesPerFrame).toLong()
        if (frames <= 0L) return 0L
        var bufferFrame = nextFrame
        val ptsFrame = Pcm16Frames.frameAtOrAfterUs(presentationTimeUs, outputSampleRate)
        if (bufferFrame < 0L) {
            bufferFrame = ptsFrame
            nextFrame = bufferFrame
            // 会话首个输出晚于请求起点：MP3 priming / 容器首帧，补静音而不是丢整块。
            if (bufferFrame > startFrame) {
                val lead = bufferFrame - startFrame
                if (lead > outputSampleRate) {
                    throw IllegalStateException(
                        "resident decode first PTS too late: anchorFrame=" + bufferFrame +
                            " requestFrame=" + startFrame,
                    )
                }
                if (lead > 0L) {
                    logger(
                        "PcmResident lead silence frames=" + lead +
                            " anchorUs=" + presentationTimeUs + " requestFrame=" + startFrame,
                    )
                }
            }
        }
        var produced = 0L
        val from = maxOf(bufferFrame, startFrame)
        val to = minOf(bufferFrame + frames, endFrame)
        if (from > startFrame && outFrames + produced == 0L) {
            produced += fillSilence(startFrame, from, out, outFrames + produced)
        }
        if (to > from) {
            val srcOffset = ((from - bufferFrame) * outputBytesPerFrame).toInt()
            val len = ((to - from) * outputBytesPerFrame).toInt()
            val dst = ((outFrames + produced) * outputBytesPerFrame).toInt()
            System.arraycopy(pcm, srcOffset, out, dst, len)
            produced += (to - from)
        }
        // 本 buffer 越过请求终点：余量留给下一块。
        if (bufferFrame + frames > endFrame) {
            val keepFrom = maxOf(endFrame, bufferFrame)
            val off = ((keepFrom - bufferFrame) * outputBytesPerFrame).toInt()
            if (off < pcm.size) {
                pending = pcm.copyOfRange(off, pcm.size)
                pendingFrame = keepFrom
            }
        }
        nextFrame = bufferFrame + frames
        // 00029 尾奏 45ppm 漂移取证：如果 PTS 时间轴和“按样本数推进”的 nextFrame
        // 每秒差 ~2 帧，会在这里累计出来（正常应为 0）。只观测，不改变输出。
        val delta = ptsFrame - bufferFrame
        ptsDriftFrames += delta
        ptsBuffers += 1L
        if (ptsBuffers % 64L == 0L) {
            logger(
                "PcmResident ptsDrift frames=" + ptsDriftFrames +
                    " buffers=" + ptsBuffers + " next=" + nextFrame +
                    " pts=" + ptsFrame + " lastDelta=" + delta,
            )
        }
        return produced
    }

    private fun fillSilence(fromFrame: Long, toFrame: Long, out: ByteArray, outFrames: Long): Long {
        val frames = (toFrame - fromFrame).coerceAtLeast(0L)
        if (frames <= 0L) return 0L
        val bytes = (frames * outputBytesPerFrame).toInt()
        val offset = (outFrames * outputBytesPerFrame).toInt()
        if (offset + bytes > out.size) {
            throw IllegalStateException("resident silence overflow")
        }
        java.util.Arrays.fill(out, offset, offset + bytes, 0)
        return frames
    }

    // ------------------------------------------------------------------ 会话

    private fun ensureSession() {
        if (codec != null) return
        if (path.startsWith("content:")) {
            throw IOException("PcmResidentRangeProvider requires a file path: " + path)
        }
        val ex = MediaExtractor()
        var c: MediaCodec? = null
        try {
            ex.setDataSource(path)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) throw IOException("no audio track: " + path)
            ex.selectTrack(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: throw IOException("missing mime")
            c = MediaCodec.createDecoderByType(mime)
            c.configure(format, null, null, 0)
            c.start()
            extractor = ex
            codec = c
            outputSampleRate = expectedSampleRate
            outputChannels = expectedChannels
            outputBytesPerFrame = expectedChannels * 2
            outputPcmFloat = false
            resetStreamState()
            sessionOpens += 1L
            lastError = null
            logger(
                "PcmResident open session#" + sessionOpens + " path=" + path +
                    " sr=" + outputSampleRate + " ch=" + outputChannels,
            )
        } catch (t: Throwable) {
            try { c?.stop() } catch (_: Throwable) {}
            try { c?.release() } catch (_: Throwable) {}
            try { ex.release() } catch (_: Throwable) {}
            throw t
        }
    }

    private fun seekTo(startFrame: Long) {
        val ex = extractor ?: throw IllegalStateException("extractor not open")
        val c = codec ?: throw IllegalStateException("codec not open")
        val targetUs = if (startFrame <= 0L) 0L else startFrame * 1_000_000L / outputSampleRate
        val seekUs = (targetUs - seekPrerollUs).coerceAtLeast(0L)
        ex.seekTo(seekUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        c.flush()
        resetStreamState()
        seekCount += 1L
        logger(
            "PcmResident seek targetFrame=" + startFrame + " seekUs=" + seekUs +
                " targetUs=" + targetUs + " seeks=" + seekCount,
        )
    }

    private fun resetStreamState() {
        nextFrame = -1L
        pending = null
        pendingFrame = -1L
        inputDone = false
        outputDone = false
    }

    private fun feedInput() {
        val c = codec ?: return
        val ex = extractor ?: return
        if (inputDone) return
        val inIdx = c.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
        if (inIdx < 0) return
        val buf = c.getInputBuffer(inIdx)
        if (buf == null) {
            c.queueInputBuffer(inIdx, 0, 0, 0L, 0)
            return
        }
        val size = ex.readSampleData(buf, 0)
        if (size < 0) {
            c.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            inputDone = true
        } else {
            c.queueInputBuffer(inIdx, 0, size, ex.sampleTime, 0)
            ex.advance()
        }
    }

    private fun applyOutputFormatLocked() {
        val f = codec!!.outputFormat
        if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
            val sr = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            if (sr > 0) outputSampleRate = sr
        }
        if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
            val ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            if (ch > 0) {
                outputChannels = ch
                outputBytesPerFrame = ch * 2
            }
        }
        outputPcmFloat = if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
            f.getInteger(MediaFormat.KEY_PCM_ENCODING) == 4
        } else {
            false
        }
    }

    private fun readOutputBytes(outIdx: Int, info: MediaCodec.BufferInfo): ByteArray {
        val buffer = codec!!.getOutputBuffer(outIdx) ?: return ByteArray(0)
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        return if (outputPcmFloat) floatToPcm16(buffer, outputChannels) else {
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            bytes
        }
    }

    private fun floatToPcm16(src: ByteBuffer, channels: Int): ByteArray {
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

    private fun closeNativeLocked() {
        val hadSession = codec != null || extractor != null
        try { codec?.stop() } catch (_: Throwable) {}
        try { codec?.release() } catch (_: Throwable) {}
        try { extractor?.release() } catch (_: Throwable) {}
        codec = null
        extractor = null
        resetStreamState()
        if (hadSession) logger("PcmResident close path=" + path + " released=" + released)
    }

    private companion object {
        const val DEQUEUE_TIMEOUT_US: Long = 10_000L
        const val DEFAULT_SEEK_PREROLL_US: Long = 250_000L
    }
}
