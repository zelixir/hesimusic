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
import java.nio.ByteOrder
import javax.inject.Inject
import kotlin.coroutines.coroutineContext
import dagger.hilt.android.qualifiers.ApplicationContext

/** 分析用 mono float PCM(契约 §2.4;v8 §14.1 起为唯一解码形态)。 */
class MonoPcm(val data: FloatArray, val sampleRate: Int, val frames: Long)

/**
 * MediaExtractor + MediaCodec 同步解码器(契约 §2.4)。
 *
 * - 支持 Android 系统可解码的一切音频格式(ogg vorbis / flac / mp3 / m4a / aac / opus / wav 等)
 * - 整轨解码进内存;超过 maxSeconds 抛 LoopException("曲目过长")
 * - 仅提供分析用 mono 解码;播放链不使用本解码器(PCM 环回由播放侧实现)
 */
@Singleton
class PcmDecoder @Inject constructor(@ApplicationContext private val context: Context) {

    /** MediaCodec 输出的原始 16bit 交错 PCM(声道数可为源声道数)。 */
    private class RawPcm(val data: ShortArray, val channels: Int, val sampleRate: Int, val frames: Long)

    /** 分析用:mono float(16bit 满幅尺度,引擎内部归一化),所有声道平均下混。
     *  onProgress ∈ [0,1](≤20Hz,完成必报 1.0);超过 maxSeconds 抛 [LoopException]。 */
    suspend fun decodeMono(
        path: String,
        maxSeconds: Int = 900,
        onProgress: ((Float) -> Unit)? = null,
    ): MonoPcm = withContext(Dispatchers.Default) {
        val raw = decodeInternal(
            path,
            maxSeconds,
            onProgress,
            threadPriority = Process.THREAD_PRIORITY_BACKGROUND, // v4 §7.1:分析解码维持 BACKGROUND
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
            try {
                if (path.startsWith("content:")) {
                    extractor.setDataSource(context, Uri.parse(path), null)
                } else {
                    extractor.setDataSource(path)
                }
            } catch (e: Exception) {
                throw LoopException("无法读取音频文件", e)
            }

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

    private companion object {
        const val TIMEOUT_US = 10_000L
    }
}
