package com.zjr.hesimusic.loop.analysis

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Process
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import javax.inject.Singleton
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import javax.inject.Inject
import kotlin.coroutines.coroutineContext
import dagger.hilt.android.qualifiers.ApplicationContext

/** 分析用 mono float PCM(契约 §2.4;v8 §14.1 起为唯一解码形态)。 */
class MonoPcm(val data: FloatArray, val sampleRate: Int, val frames: Long)

/**
 * 测试/诊断用并行解码开关(生产不设置)。
 *
 * - [segmentCountOverride]:强制段数(1 = 强制单线程),真机对照测试用;
 * - [disableParallel]:完全关闭并行解码。
 */
internal object ParallelDecodeHooks {
    @Volatile
    var segmentCountOverride: Int? = null

    @Volatile
    var disableParallel: Boolean = false
}

/**
 * MediaExtractor + MediaCodec 同步解码器(契约 §2.4)。
 *
 * - 支持 Android 系统可解码的一切音频格式(ogg vorbis / flac / mp3 / m4a / aac / opus / wav 等)
 * - 整轨解码进内存;超过 maxSeconds 抛 LoopException("曲目过长")
 * - 仅提供分析用 mono 解码;播放链不使用本解码器(PCM 环回由播放侧实现)
 *
 * v7 §decode-parallel:整曲分析解码按时间切成 2~4 段,每段独立
 * MediaExtractor+MediaCodec 并行解码(段内仍复用“seek 前 1s + PTS 追赶 + 严格帧数”
 * 精确对齐),拼接前做接缝逐样本校验;任一环节不一致/失败即回退单线程解码,并把该
 * mime 记为不可并行(进程内缓存)。
 */
@Singleton
class PcmDecoder @Inject constructor(@ApplicationContext private val context: Context) {

    /** MediaCodec 输出的原始 16bit 交错 PCM(声道数可为源声道数)。 */
    private class RawPcm(val data: ShortArray, val channels: Int, val sampleRate: Int, val frames: Long)

    /** 探测到的音轨元信息(并行切段用)。 */
    private class TrackProbe(
        val mime: String,
        val sampleRate: Int,
        val channels: Int,
        val durationUs: Long,
    )

    /** 最近一次 decodeMono 是否走了并行路径(诊断/真机测试断言用)。 */
    @Volatile
    internal var lastDecodeUsedParallel: Boolean = false
        private set

    /** 最近一次 decodeMono 的段数(1 = 单线程)。 */
    @Volatile
    internal var lastDecodeSegmentCount: Int = 1
        private set

    /**
     * 分析用:mono float(16bit 满幅尺度,引擎内部归一化),所有声道平均下混。
     *  onProgress ∈ [0,1](≤20Hz,完成必报 1.0);超过 maxSeconds 抛 [LoopException]。
     */
    suspend fun decodeMono(
        path: String,
        maxSeconds: Int = 900,
        onProgress: ((Float) -> Unit)? = null,
    ): MonoPcm = withContext(Dispatchers.Default) {
        lastDecodeUsedParallel = false
        lastDecodeSegmentCount = 1

        val probe = if (!ParallelDecodeHooks.disableParallel) {
            try {
                probeTrack(path)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }

        if (probe != null && probe.durationUs > maxSeconds * 1_000_000L) {
            throw LoopException("曲目过长")
        }
        if (probe != null) {
            val cores = Runtime.getRuntime().availableProcessors()
            val requested = ParallelDecodeHooks.segmentCountOverride
                ?: SegmentPlanner.segmentCount(cores, probe.durationUs)
            val canParallel = requested >= 2 && !isParallelUnsafe(probe.mime)
            if (canParallel) {
                // 估算总帧数(仅用于切段/进度;尾段以 EOS 为准,不影响拼接总长)。
                val estimate = probe.durationUs * probe.sampleRate / Pcm16Frames.US_PER_SECOND
                if (estimate > 0L) {
                    val plan = SegmentPlanner.plan(
                        totalFrames = estimate,
                        sampleRate = probe.sampleRate,
                        channels = probe.channels,
                        segmentCount = requested,
                        overlapFrames = OVERLAP_FRAMES,
                    )
                    val started = System.currentTimeMillis()
                    val result = try {
                        ParallelMonoDecode.decode(
                            plan = plan,
                            decoder = MediaCodecSegmentDecoder(path, plan),
                            onProgress = onProgress,
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "parallel decode threw; fall back to serial: " + e.message, e)
                        null
                    }
                    if (result is ParallelDecodeResult.Success) {
                        lastDecodeUsedParallel = true
                        lastDecodeSegmentCount = plan.segmentCount
                        val mono = result.mono
                        onProgress?.invoke(1f)
                        Log.d(
                            "LoopPerf",
                            "pcm parallel decode=" + (System.currentTimeMillis() - started) +
                                "ms frames=" + mono.size + " segments=" + plan.segmentCount +
                                " ch=" + probe.channels + " sr=" + probe.sampleRate,
                        )
                        return@withContext MonoPcm(mono, probe.sampleRate, mono.size.toLong())
                    }
                    if (result is ParallelDecodeResult.Rejected) {
                        Log.w(TAG, "parallel decode rejected (" + result.reason + "); fall back to serial")
                    }
                    markParallelUnsafe(probe.mime)
                }
            }
        }

        // ---- 单线程回退(原路径,语义不变) ----
        val raw = decodeInternal(
            path,
            maxSeconds,
            onProgress,
            // v7 §decode-parallel:分析解码是用户等待的前台操作,用 DEFAULT 优先级。
            // 若沿用 BACKGROUND(nice=10),EAS 调度会把并行段压到小核,4 段也拿不到大核;
            // 播放链的预填解码(decodePcm16Range)仍保持 BACKGROUND。
            threadPriority = ANALYSIS_DECODE_PRIORITY,
        )
        val ch = raw.channels
        val frames = raw.frames.toInt()
        val mono = FloatArray(frames)
        if (ch == 1) {
            for (i in 0 until frames) mono[i] = raw.data[i].toFloat()
        } else {
            var idx = 0
            for (f in 0 until frames) {
                var acc = 0L
                for (c in 0 until ch) acc += raw.data[idx++].toInt()
                mono[f] = acc.toFloat() / ch
            }
        }
        MonoPcm(mono, raw.sampleRate, raw.frames)
    }

    /**
     * 精确解码 [path] 的 [startUs, endUs) 区间为 PCM16（交错、小端、源声道/源采样率）。
     *
     * 环缓冲预填专用；线程优先级与 [decodeMono] 一致（BACKGROUND）。
     *
     * 对齐算法：
     * 1. 先 seek 到 startUs − 1s（SEEK_TO_PREVIOUS_SYNC），让解码器先从区间之前产出，
     *    避免 MP3/AAC 的 sync 帧/编码器延迟导致起点偏后；
     * 2. 以**第一个解码输出帧的 PTS** 为锚点，按帧号推算每帧时间（anchorUs + f*1e6/sr）；
     * 3. 丢弃所有 PTS < startUs 的帧（= 对齐到 ceil(startUs*sr/1e6) 帧）；
     * 4. 取接下来的 ceil((endUs−startUs)*sr/1e6) 帧；不足说明解码结果短于区间 → 抛 [LoopException]
     *    （不静默补零）。
     *
     * 说明：A/B 通常不落在帧边界，第 4 步的“严格帧数”可能比“PTS < endUs 的帧数”多 1 帧
     * （末尾整帧）。装 ring 时由调用方（[RingChunkedSeeder]）按环长度裁掉多余帧。
     *
     * 分片预填每片都调用本函数：每次 seek 到片起点前 1s 再追赶，片与片之间由帧号
     * 对齐（[RingChunkedSeeder]），因此不会因解码器 seek 精度产生缝隙或重复。
     */
    suspend fun decodePcm16Range(path: String, startUs: Long, endUs: Long): Pcm16Segment =
        withContext(Dispatchers.Default) {
            if (startUs < 0L) throw LoopException("区间起点非法")
            if (endUs <= startUs) throw LoopException("区间长度非法")
            if (endUs - startUs > MAX_PCM16_RANGE_US) throw LoopException("区间过长")
            withBackgroundPriority {
                decodePcm16Internal(path, startUs, endUs = endUs)
            }
        }

    /** 兼容入口：整段解码 = 一次范围解码（[decodePcm16Range]）。 */
    suspend fun decodePcm16(path: String, startUs: Long, endUs: Long): Pcm16Segment =
        decodePcm16Range(path, startUs, endUs)

    private suspend fun <T> withThreadPriority(priority: Int, block: suspend () -> T): T {
        val tid = Process.myTid()
        val originalPriority = try {
            Process.getThreadPriority(tid)
        } catch (_: Exception) {
            0
        }
        try {
            Process.setThreadPriority(tid, priority)
        } catch (_: Exception) {}
        try {
            return block()
        } finally {
            try { Process.setThreadPriority(tid, originalPriority) } catch (_: Exception) {}
        }
    }

    /** 环缓冲预填(播放链)保持 BACKGROUND,避免与播放/UI 抢 CPU。 */
    private suspend fun <T> withBackgroundPriority(block: suspend () -> T): T =
        withThreadPriority(Process.THREAD_PRIORITY_BACKGROUND, block)

    /**
     * 单段解码(并行路径):返回该段 mono 主体 + 接缝校验用的 overlap 帧。
     * 每次调用创建并释放独立 MediaExtractor/MediaCodec(段解完立刻释放)。
     */
    private inner class MediaCodecSegmentDecoder(
        private val path: String,
        private val plan: SegmentPlan,
    ) : MonoSegmentDecoder {

        override suspend fun decode(
            segment: DecodeSegment,
            onFrames: (Long) -> Unit,
        ): DecodedSegment = withThreadPriority(ANALYSIS_DECODE_PRIORITY) {
            val started = System.currentTimeMillis()
            val target = if (segment.toEos) null else segment.frameCount + plan.overlapFrames
            val pcm = decodePcm16Internal(
                path = path,
                startUs = plan.startUs(segment),
                endUs = null,
                targetFramesOverride = target,
                toEos = segment.toEos,
                onFrames = onFrames,
                keepFromStart = segment.index == 0,
            )
            if (pcm.sampleRate != plan.sampleRate || pcm.channels != plan.channels) {
                throw LoopException(
                    "解码输出格式与探测不一致: sr=" + pcm.sampleRate + "/" + plan.sampleRate +
                        " ch=" + pcm.channels + "/" + plan.channels,
                )
            }
            val frames = pcm.frames.toInt()
            val monoAll = downmixToMono(pcm.data, pcm.channels, frames)
            val mainFrames = if (segment.toEos) monoAll.size else segment.frameCount.toInt()
            if (mainFrames > monoAll.size) {
                throw LoopException("并行段解码不足: 需要 " + mainFrames + " 帧,实际 " + monoAll.size)
            }
            val mono = if (mainFrames == monoAll.size) monoAll else monoAll.copyOf(mainFrames)
            val overlap = if (segment.toEos) {
                FloatArray(0)
            } else {
                val n = minOf(plan.overlapFrames.toInt(), monoAll.size - mainFrames)
                if (n <= 0) FloatArray(0) else monoAll.copyOfRange(mainFrames, mainFrames + n)
            }
            Log.d(
                "LoopPerf",
                "pcm segment=" + segment.index + " startFrame=" + segment.startFrame +
                    " frames=" + mono.size + " overlap=" + overlap.size +
                    " ms=" + (System.currentTimeMillis() - started),
            )
            DecodedSegment(segment, mono, overlap)
        }
    }

    /** PCM16 交错 buffer -> mono float(与单线程路径逐样本相同的下混公式)。 */
    private fun downmixToMono(data: ByteArray, channels: Int, frames: Int): FloatArray {
        val mono = FloatArray(frames)
        if (frames <= 0) return mono
        val shorts = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        if (channels == 1) {
            for (i in 0 until frames) mono[i] = shorts.get(i).toFloat()
        } else {
            var idx = 0
            for (f in 0 until frames) {
                var acc = 0L
                for (c in 0 until channels) acc += shorts.get(idx++).toInt()
                mono[f] = acc.toFloat() / channels
            }
        }
        return mono
    }

    /** 探测音轨元信息(不启动解码器)。失败返回 null -> 单线程路径。 */
    private fun probeTrack(path: String): TrackProbe? {
        val extractor = MediaExtractor()
        try {
            openExtractor(extractor, path)
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("audio/")) continue
                if (!f.containsKey(MediaFormat.KEY_SAMPLE_RATE) ||
                    !f.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ||
                    !f.containsKey(MediaFormat.KEY_DURATION)
                ) {
                    return null
                }
                val sr = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                val durationUs = f.getLong(MediaFormat.KEY_DURATION)
                if (sr <= 0 || ch <= 0 || durationUs <= 0L) return null
                return TrackProbe(mime, sr, ch, durationUs)
            }
            return null
        } finally {
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    private suspend fun decodePcm16Internal(
        path: String,
        startUs: Long,
        endUs: Long?,
        targetFramesOverride: Long? = null,
        toEos: Boolean = false,
        onFrames: ((Long) -> Unit)? = null,
        keepFromStart: Boolean = false,
    ): Pcm16Segment {
        val decodeStartMs = System.currentTimeMillis()
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            openExtractor(extractor, path)

            var trackIndex = -1
            var inputFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    inputFormat = f
                    break
                }
            }
            val format = inputFormat ?: throw LoopException("文件中没有可解码的音频轨")
            extractor.selectTrack(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: throw LoopException("未知的音频编码格式")

            codec = try {
                MediaCodec.createDecoderByType(mime)
            } catch (e: Exception) {
                throw LoopException("设备不支持解码该音频格式", e)
            }

            // 先 seek 再 start：解码器从 [startUs − 1s, ...) 开始产出，保证覆盖区间起点。
            // keepFromStart（并行解码第 0 段）不 seek：从文件头连续解码 == 单线程前缀。
            if (!keepFromStart) {
                extractor.seekTo(
                    (startUs - SEEK_BACK_US).coerceAtLeast(0L),
                    MediaExtractor.SEEK_TO_PREVIOUS_SYNC,
                )
            }
            codec.configure(format, null, null, 0)
            codec.start()

            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            checkPcmFormat(format, channels, sampleRate)

            var targetFrames = targetFramesOverride ?: -1L
            var bytesPerFrame = channels * 2
            var out: ByteArray? = null
            var outStream: java.io.ByteArrayOutputStream? = null
            var outOffset = 0
            var keptFrames = 0L
            var anchorUs = Long.MIN_VALUE
            var streamFrame = 0L
            var keepStartFrame = 0L
            var inputDone = false
            var outputDone = false
            val info = MediaCodec.BufferInfo()

            while (!outputDone) {
                coroutineContext.ensureActive()
                if (!toEos && targetFrames > 0L && keptFrames >= targetFrames) break

                // 1) 喂入:extractor → codec
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)
                            ?: throw LoopException("MediaCodec 输入缓冲获取失败")
                        val sampleSize = extractor.readSampleData(buf, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                // 2) 取出:codec → 裁切后的 PCM16
                val outIdx = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        val newChannels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        val newSampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        checkPcmFormat(f, newChannels, newSampleRate)
                        if (newChannels != channels || newSampleRate != sampleRate) {
                            if (anchorUs != Long.MIN_VALUE) {
                                throw LoopException("解码输出格式中途变化")
                            }
                            channels = newChannels
                            sampleRate = newSampleRate
                            bytesPerFrame = channels * 2
                        }
                    }
                    outIdx >= 0 -> {
                        if (info.size > 0) {
                            val buffer = codec.getOutputBuffer(outIdx)
                            if (buffer != null) {
                                val frames = info.size.toLong() / bytesPerFrame
                                if (frames > 0L) {
                                    if (anchorUs == Long.MIN_VALUE) {
                                        anchorUs = info.presentationTimeUs
                                        if (!keepFromStart && anchorUs > startUs) {
                                            throw LoopException(
                                                "解码起点晚于区间起点: anchorUs=" + anchorUs +
                                                    " startUs=" + startUs,
                                            )
                                        }
                                        if (!toEos && targetFramesOverride == null) {
                                            val end = endUs ?: throw LoopException("区间终点缺失")
                                            targetFrames = Pcm16Frames.framesForUsCeil(
                                                end - startUs,
                                                sampleRate,
                                            )
                                        }
                                        if (!toEos) {
                                            if (targetFrames <= 0L) throw LoopException("区间不足一帧")
                                            val targetBytes = targetFrames * bytesPerFrame
                                            if (targetBytes > MAX_PCM16_BYTES) {
                                                throw LoopException("区间过长")
                                            }
                                            out = ByteArray(targetBytes.toInt())
                                        } else {
                                            outStream = java.io.ByteArrayOutputStream(
                                                (sampleRate * bytesPerFrame).coerceAtLeast(1024),
                                            )
                                        }
                                        keepStartFrame = if (keepFromStart) {
                                            0L
                                        } else {
                                            Pcm16Frames.frameAtOrAfterUs(
                                                startUs - anchorUs,
                                                sampleRate,
                                            )
                                        }
                                    }
                                    val clip = if (toEos) {
                                        val from = maxOf(streamFrame, keepStartFrame)
                                        val to = streamFrame + frames
                                        if (to > from) from until to else null
                                    } else {
                                        Pcm16Frames.clipBuffer(
                                            bufferStartFrame = streamFrame,
                                            bufferFrames = frames,
                                            keepStartFrame = keepStartFrame,
                                            keepFrames = targetFrames,
                                        )
                                    }
                                    if (clip != null) {
                                        val srcFrame = (clip.first - streamFrame).toInt()
                                        val len = ((clip.last - clip.first + 1L) * bytesPerFrame).toInt()
                                        val dup = buffer.duplicate()
                                        // MediaCodec 返回的 buffer position/limit 已经对准本段数据，
                                        // 以 position 为基准，兼容 offset=0 与 offset!=0 两种实现。
                                        val srcStart = dup.position() + srcFrame * bytesPerFrame
                                        val srcEnd = srcStart + len
                                        if (srcStart < 0 || srcEnd > dup.limit()) {
                                            throw LoopException("解码输出 buffer 越界")
                                        }
                                        dup.position(srcStart)
                                        dup.limit(srcEnd)
                                        if (toEos) {
                                            val chunk = ByteArray(len)
                                            dup.get(chunk, 0, len)
                                            outStream!!.write(chunk)
                                        } else {
                                            dup.get(out!!, outOffset, len)
                                            outOffset += len
                                        }
                                        keptFrames += clip.last - clip.first + 1L
                                        onFrames?.invoke(keptFrames)
                                    }
                                    streamFrame += frames
                                }
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }

            if (toEos) {
                val data = outStream?.toByteArray() ?: ByteArray(0)
                val frames = data.size.toLong() / bytesPerFrame
                if (frames <= 0L) throw LoopException("解码结果为空")
                return Pcm16Segment(data, sampleRate, channels, frames)
            }
            if (targetFrames <= 0L) throw LoopException("解码结果为空")
            if (keptFrames < targetFrames) {
                throw LoopException(
                    "环缓冲解码不足: 需要 " + targetFrames + " 帧,实际 " + keptFrames + " 帧",
                )
            }
            Log.d(
                "LoopPerf",
                "pcm16 decode=" + (System.currentTimeMillis() - decodeStartMs) +
                    "ms frames=" + keptFrames + " ch=" + channels + " sr=" + sampleRate +
                    " startUs=" + startUs + " endUs=" + (endUs ?: -1L),
            )
            return Pcm16Segment(out!!, sampleRate, channels, keptFrames)
        } catch (e: CancellationException) {
            throw e
        } catch (e: LoopException) {
            throw e
        } catch (e: Exception) {
            throw LoopException(e.message ?: "音频解码失败", e)
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    private fun openExtractor(extractor: MediaExtractor, path: String) {
        try {
            if (path.startsWith("content:")) {
                extractor.setDataSource(context, Uri.parse(path), null)
            } else {
                extractor.setDataSource(path)
            }
        } catch (e: Exception) {
            throw LoopException("无法读取音频文件", e)
        }
    }

    // ---------- 内部实现:MediaExtractor + MediaCodec 同步解码 ----------

    private suspend fun decodeInternal(
        path: String,
        maxSeconds: Int,
        onProgress: ((Float) -> Unit)?,
        threadPriority: Int,
    ): RawPcm = withContext(Dispatchers.Default) {
        // v3 §5.1 / v4 §7.1:解码线程降优先级(优先级由调用方按链路选择),
        // 降低与 UI 线程的 CPU 争抢;结束时恢复池线程原优先级,避免污染协程线程池。
        val decodeStartMs = System.currentTimeMillis()
        val tid = Process.myTid()
        val originalPriority = try {
            Process.getThreadPriority(tid)
        } catch (_: Exception) {
            0
        }
        try {
            Process.setThreadPriority(tid, threadPriority)
        } catch (_: Exception) {}
        var lastProgressMs = 0L
        var lastProgressFraction = -1.0f
        // 按输入流时间/轨道时长上报(≤20Hz;时长未知时不上报,完成仍报 1.0)
        fun reportProgress(sampleTimeUs: Long, durationUs: Long) {
            val cb = onProgress ?: return
            if (durationUs <= 0L) return
            val fraction = (sampleTimeUs.toFloat() / durationUs).coerceIn(0f, 0.999f)
            val now = System.currentTimeMillis()
            if (now - lastProgressMs < 50 || fraction <= lastProgressFraction) return
            lastProgressMs = now
            lastProgressFraction = fraction
            cb(fraction)
        }

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            openExtractor(extractor, path)

            var trackIndex = -1
            var inputFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    inputFormat = f
                    break
                }
            }
            val format = inputFormat ?: throw LoopException("文件中没有可解码的音频轨")
            extractor.selectTrack(trackIndex)
            val trackDurationUs =
                if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L

            val maxDurationUs = maxSeconds * 1_000_000L
            if (format.containsKey(MediaFormat.KEY_DURATION) &&
                format.getLong(MediaFormat.KEY_DURATION) > maxDurationUs
            ) {
                throw LoopException("曲目过长")
            }
            val mime = format.getString(MediaFormat.KEY_MIME) ?: throw LoopException("未知的音频编码格式")

            codec = try {
                MediaCodec.createDecoderByType(mime)
            } catch (e: Exception) {
                throw LoopException("设备不支持解码该音频格式", e)
            }
            codec.configure(format, null, null, 0)
            codec.start()

            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            checkPcmFormat(format, channels, sampleRate)

            val chunks = ArrayList<ShortArray>()
            var totalFrames = 0L
            var inputDone = false
            var outputDone = false
            val info = MediaCodec.BufferInfo()

            while (!outputDone) {
                coroutineContext.ensureActive()

                // 1) 喂入:extractor → codec
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)
                            ?: throw LoopException("MediaCodec 输入缓冲获取失败")
                        val sampleSize = extractor.readSampleData(buf, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                            coroutineContext.ensureActive()
                            reportProgress(extractor.sampleTime, trackDurationUs)
                        }
                    }
                }

                // 2) 取出:codec → PCM short 交错
                val outIdx = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        checkPcmFormat(f, channels, sampleRate)
                    }
                    outIdx >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null) {
                            val shorts = outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            val n = shorts.remaining()
                            if (n > 0) {
                                val chunk = ShortArray(n)
                                shorts.get(chunk)
                                chunks.add(chunk)
                                totalFrames += n / channels
                                // 解码中途兜底:部分格式声明的时长不可靠
                                if (totalFrames > maxSeconds.toLong() * sampleRate) {
                                    throw LoopException("曲目过长")
                                }
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                    // INFO_TRY_AGAIN_LATER:继续循环
                }
            }

            if (totalFrames <= 0L) throw LoopException("解码结果为空")
            onProgress?.invoke(1f)

            // v4 §7.1 LoopPerf 通用解码链路埋点(分析/预览 enter/桥接/app-ui 预取共用)
            Log.d(
                "LoopPerf",
                "pcm decode=" + (System.currentTimeMillis() - decodeStartMs) + "ms frames=" +
                    totalFrames + " ch=" + channels + " sr=" + sampleRate
            )

            val totalShorts = chunks.sumOf { it.size }
            val data = ShortArray(totalShorts)
            var off = 0
            for (c in chunks) {
                c.copyInto(data, off)
                off += c.size
            }
            chunks.clear()
            RawPcm(data, channels, sampleRate, totalShorts.toLong() / channels)
        } catch (e: CancellationException) {
            throw e
        } catch (e: LoopException) {
            throw e
        } catch (e: Exception) {
            throw LoopException(e.message ?: "音频解码失败", e)
        } finally {
            try { Process.setThreadPriority(tid, originalPriority) } catch (_: Exception) {}
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    private fun checkPcmFormat(format: MediaFormat, channels: Int, sampleRate: Int) {
        if (channels <= 0 || sampleRate <= 0) {
            throw LoopException("解码输出缺少声道/采样率信息")
        }
        if (format.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
            format.getInteger(MediaFormat.KEY_PCM_ENCODING) != AudioFormat.ENCODING_PCM_16BIT
        ) {
            throw LoopException("解码器输出的 PCM 编码不受支持")
        }
    }

    private fun isParallelUnsafe(mime: String): Boolean = parallelUnsafeMimes.contains(mime)

    private fun markParallelUnsafe(mime: String) {
        parallelUnsafeMimes.add(mime)
        Log.w(TAG, "decode-parallel disabled for mime=" + mime + " (process-wide)")
    }

    private companion object {
        const val TAG = "PcmDecoder"
        const val TIMEOUT_US = 10_000L

        /** 预填解码提前量：从 startUs − 1s 开始 seek，给 MP3/AAC sync 帧留余量。 */
        const val SEEK_BACK_US = 1_000_000L

        /** 预填区间上限（µs）：与引擎单窗口 300s 上限一致。 */
        const val MAX_PCM16_RANGE_US = 300_000_000L

        /** 预填 PCM 上限：64MB（与引擎 ring 上限一致）。 */
        const val MAX_PCM16_BYTES = 64L * 1024L * 1024L

        /** 接缝校验额外帧（约 85ms @48k）：覆盖编码器延迟/帧边界错位。 */
        const val OVERLAP_FRAMES = 4096L

        /** 分析解码线程优先级：前台用户等待,用 DEFAULT 以允许大核参与(见 decodeMono 注释)。 */
        val ANALYSIS_DECODE_PRIORITY: Int = Process.THREAD_PRIORITY_DEFAULT

        /** 已验证不可并行(或首次并行失败)的 mime;进程内缓存,后续直接单线程。 */
        val parallelUnsafeMimes: MutableSet<String> =
            Collections.synchronizedSet(mutableSetOf<String>())
    }
}
