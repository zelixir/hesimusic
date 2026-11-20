package com.zjr.hesimusic.loop.audio.source

import android.content.Context
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import com.zjr.hesimusic.core.invariants.AudioOutputFrames
import com.zjr.hesimusic.loop.audio.LoopAudioController
import java.nio.ByteBuffer

/**
 * 只做 PCM 取证的 AudioSink：委托给 [DefaultAudioSink]，不改 PTS、不做环回。
 *
 * tap 必须在 delegate 之前写：DefaultAudioSink 的**写入路径按实际写入量推进 position**
 * （audioTrack.write(buffer, size, WRITE_NON_BLOCKING)），但**早期返回路径不推进**
 * （未初始化 / mayHandleBuffer 拒绝 / drain 未完成）；而且 delegate 可能先把数据吃进
 * processor 后仍返回 false。所以 consumed=false 不能证明"没消费"，等 consumed 会漏数据。
 * 同一个 ByteBuffer 可能被多次传入，因此用 buffer 身份 + pts + 已写区间去重。
 *
 * **不变量（issue 00020 回归）：本类绝不自己调用 [play]。**
 * 曾经有一版诊断兜底在首个 handleBuffer 之后强制 `super.play()`（当时怀疑“sink 不启动导致
 * BUFFERING 死锁”），它在 pause() 后会被重新触发：播放器已暂停（UI 进「无缝分析」页会暂停主播放），
 * AudioTrack 却被重新 start，于是 ExoPlayer 每次 doSomeWork 喂进来的那一小段 PCM 就变成
 * “~96ms 出声 + ~0.9s 静音”的循环——用户听到的正是“0.x 秒播放 + 0.x 秒停顿”。
 * 播放/暂停的唯一所有者是 ExoPlayer，sink 只允许被动响应。
 */
@UnstableApi
class TapAudioSink(
    delegate: AudioSink,
    private val controller: LoopAudioController,
) : ForwardingAudioSink(delegate) {

    private data class WrittenRange(
        val ptsUs: Long,
        val start: Int,
        val end: Int,
    )

    private var handledBuffers = 0L
    private var lastBufferLogMs = 0L
    private var lastDutyLogMs = 0L
    private var tapBytesPerFrame = 0
    private var seenFrames = 0L
    private var writtenFrames = 0L
    private var tapWasActive = false

    /** 输出侧不变量（00020）：真正交给 delegate 的帧数，按 controller 分桶累计（始终开启）。 */
    private val outputFrames = AudioOutputFrames.counterFor(controller)
    private var sinkBytesPerFrame = 0

    /** 只保留最近 16 个 buffer 身份，防止同一块反复回调时重复写。 */
    private val writtenRanges = object : LinkedHashMap<Int, WrittenRange>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, WrittenRange>?): Boolean =
            size > 16
    }

    override fun configure(
        inputFormat: Format,
        specifiedBufferSize: Int,
        outputChannels: IntArray?,
    ) {
        controller.tapEngineSeamsEnabled = false
        tapBytesPerFrame = (inputFormat.channelCount * 2).coerceAtLeast(0)
        sinkBytesPerFrame = bytesPerFrameOf(inputFormat)
        if (MimeTypes.AUDIO_RAW == inputFormat.sampleMimeType &&
            inputFormat.channelCount > 0 &&
            inputFormat.sampleRate > 0
        ) {
            val window = controller.window
            controller.window = null
            try {
                controller.onFormatConfigured(
                    sampleRate = inputFormat.sampleRate,
                    channels = inputFormat.channelCount,
                    bytesPerFrame = inputFormat.channelCount * 2,
                    pcmIsFloat = false,
                )
            } finally {
                controller.window = window
            }
        }
        logD(
            "tap configure " + inputFormat.sampleMimeType + "/" + inputFormat.pcmEncoding +
                " " + inputFormat.sampleRate + "Hz/" + inputFormat.channelCount +
                "ch buffer=" + specifiedBufferSize,
        )
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        handledBuffers += 1L
        if (buffer.hasRemaining()) {
            // 00022/00023 真机计时：seek 请求 → 首个 PCM buffer 进入 AudioSink。
            LoopPreviewDebugBridge.consumeSeekProbe()?.let { probe ->
                try {
                    Log.d(
                        "LoopPerf",
                        "first-audio afterSeek=" + probe[0] + "ms seekDisplayMs=" + probe[1] +
                            " ptsUs=" + presentationTimeUs + " bufferBytes=" + buffer.remaining(),
                    )
                } catch (_: Throwable) {
                    // JVM 单测里 android.util.Log 是抛异常的 stub。
                }
            }
        }
        val active = controller.isTapActive
        if (active && !tapWasActive) {
            seenFrames = 0L
            writtenFrames = 0L
            writtenRanges.clear()
            lastDutyLogMs = 0L
        }
        tapWasActive = active

        val start = buffer.position()
        val end = buffer.limit()
        val hasData = buffer.hasRemaining()
        if (hasData && tapBytesPerFrame > 0) {
            seenFrames += (end - start).toLong() / tapBytesPerFrame
        }

        if (active && hasData) {
            // delegate 之前拷贝并立即写；不能等 consumed。
            val copy = ByteArray(end - start)
            buffer.duplicate().get(copy)
            val identity = System.identityHashCode(buffer)
            val previous = writtenRanges[identity]
            val writeFrom = if (previous != null &&
                previous.ptsUs == presentationTimeUs &&
                start >= previous.start &&
                start < previous.end
            ) {
                previous.end
            } else {
                start
            }
            if (writeFrom < end) {
                val written = controller.tapPcm(
                    ByteBuffer.wrap(copy),
                    writeFrom - start,
                    end - writeFrom,
                )
                if (written > 0) {
                    if (tapBytesPerFrame > 0) {
                        writtenFrames += written.toLong() / tapBytesPerFrame
                    }
                    val newStart = previous?.takeIf {
                        it.ptsUs == presentationTimeUs && start >= it.start
                    }?.start ?: start
                    writtenRanges[identity] = WrittenRange(
                        ptsUs = presentationTimeUs,
                        start = newStart,
                        end = end,
                    )
                }
            }
        }

        val consumed = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        // 输出侧不变量（00020）：统计真正交给 delegate 的帧数。
        // consumed=true 表示整段被接收（media3 契约：buffer.position 已推进到 limit）；
        // consumed=false 时按**实际推进量**计 —— 写入路径会推进，早期返回路径不推进。
        if (sinkBytesPerFrame > 0) {
            val consumedBytes = if (consumed) {
                (end - start).coerceAtLeast(0)
            } else {
                (buffer.position() - start).coerceAtLeast(0)
            }
            if (consumedBytes > 0) {
                outputFrames.addAndGet((consumedBytes / sinkBytesPerFrame).toLong())
            }
        }
        val now = System.currentTimeMillis()
        if (handledBuffers == 1L || now - lastBufferLogMs >= 1_000L) {
            lastBufferLogMs = now
            logD(
                "tap handleBuffer n=" + handledBuffers + " ptsUs=" + presentationTimeUs +
                    " consumed=" + consumed + " before=" + start +
                    " after=" + buffer.position() +
                    " delta=" + (buffer.position() - start),
            )
        }
        if (now - lastDutyLogMs >= 1_000L) {
            lastDutyLogMs = now
            logD(
                "tapDuty writtenFrames=" + writtenFrames +
                    " seenFrames=" + seenFrames +
                    " calls=" + handledBuffers,
            )
        }
        return consumed
    }

    override fun play() {
        logD("tap play")
        super.play()
    }

    override fun pause() {
        logD("tap pause")
        super.pause()
    }

    override fun handleDiscontinuity() {
        logW("tap handleDiscontinuity")
        super.handleDiscontinuity()
    }

    override fun flush() {
        logD("tap flush")
        controller.onSinkFlush()
        super.flush()
    }

    override fun reset() {
        logD("tap reset")
        controller.onSinkReset()
        super.reset()
    }

    override fun release() {
        controller.onSinkRelease()
        super.release()
    }

    /** 与 LoopAudioSink 同口径：按 pcmEncoding + 声道数算每帧字节（仅用于帧数换算）。 */
    private fun bytesPerFrameOf(format: Format): Int {
        val bitsPerSample = when (format.pcmEncoding) {
            C.ENCODING_PCM_8BIT -> 8
            C.ENCODING_PCM_16BIT -> 16
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 32
            else -> 16
        }
        val channels = if (format.channelCount > 0) format.channelCount else 2
        return maxOf(1, bitsPerSample / 8 * channels)
    }

    private fun logD(message: String) {
        try {
            Log.d("LoopPcmSource", message)
        } catch (_: Throwable) {
            // JVM 单测里 android.util.Log 是 stub。
        }
    }

    private fun logW(message: String) {
        try {
            Log.w("LoopPcmSource", message)
        } catch (_: Throwable) {
            // JVM 单测里 android.util.Log 是 stub。
        }
    }
}

/**
 * SOURCE 模式的 RenderersFactory：默认 AudioSink + [TapAudioSink]（仅取证）。
 * 不创建 [com.zjr.hesimusic.loop.audio.LoopAudioSink]，因此不会改写 PTS。
 */
@UnstableApi
class SourceRenderersFactory(
    context: Context,
    private val controller: LoopAudioController,
) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        floatOutput: Boolean,
        playbackParams: Boolean,
    ): AudioSink {
        val delegate = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(false)
            .setEnableAudioTrackPlaybackParams(playbackParams)
            .build()
        return TapAudioSink(delegate, controller)
    }
}
