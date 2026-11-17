package com.zjr.hesimusic.loop.audio.source

import android.content.Context
import android.util.Log
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import com.zjr.hesimusic.loop.audio.LoopAudioController
import java.nio.ByteBuffer

/**
 * 只做 PCM 取证的 AudioSink：委托给 [DefaultAudioSink]，不改 PTS、不做环回，
 * 只把 delegate 实际消费的 PCM 片段交给 [LoopAudioController] 的 tap。
 *
 * 这样 SOURCE 模式既不包 [com.zjr.hesimusic.loop.audio.LoopAudioSink]（不二次映射 PTS），
 * 又能让既有 /debug/tap + tools/loop-smoothness.ts 继续分析预览页输出。
 */
@UnstableApi
class TapAudioSink(
    delegate: AudioSink,
    private val controller: LoopAudioController,
) : ForwardingAudioSink(delegate) {

    private var handledBuffers = 0L
    private var lastBufferLogMs = 0L
    private var forcedPlay = false

    override fun configure(
        inputFormat: Format,
        specifiedBufferSize: Int,
        outputChannels: IntArray?,
    ) {
        if (MimeTypes.AUDIO_RAW == inputFormat.sampleMimeType &&
            inputFormat.channelCount > 0 &&
            inputFormat.sampleRate > 0
        ) {
            // 只告诉 controller tap 格式；临时清 window 避免 M3′ engine 按源窗口
            // 调度 ring 预分配（SOURCE 不需要 ring，不能白占内存）。
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
        Log.d(
            "LoopPcmSource",
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
        val before = buffer.position()
        // DefaultAudioSink 可能消费后不移动 position/limit；先留一个视图，
        // consumed 时按快照写 tap，保证 PCM 不丢。
        val tapSnapshot = if (controller.isTapActive && buffer.hasRemaining()) {
            // delegate 可能 clear/rewind buffer；tap 开启时先拷贝真实 PCM。
            val copy = ByteArray(buffer.remaining())
            buffer.duplicate().get(copy)
            ByteBuffer.wrap(copy)
        } else {
            null
        }
        val consumed = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        val after = buffer.position()
        handledBuffers += 1L
        val now = System.currentTimeMillis()
        if (handledBuffers == 1L || now - lastBufferLogMs >= 1_000L) {
            lastBufferLogMs = now
            Log.d(
                "LoopPcmSource",
                "tap handleBuffer n=" + handledBuffers + " ptsUs=" + presentationTimeUs +
                    " consumed=" + consumed + " before=" + before + " after=" + after +
                    " delta=" + (after - before),
            )
        }
        if (after > before) {
            controller.tapPcm(buffer, before, after - before)
        } else if (consumed && tapSnapshot != null) {
            // DefaultAudioSink 不保证移动 ByteBuffer.position；使用 delegate 前的快照。
            controller.tapPcm(tapSnapshot, tapSnapshot.position(), tapSnapshot.remaining())
        }
        if (!forcedPlay) {
            // SOURCE 诊断兜底：renderer 已读数据但未回调 onStarted 时，
            // 主动启动一次 AudioTrack（幂等），打破 BUFFERING 死锁。
            forcedPlay = true
            Log.w("LoopPcmSource", "tap force play after first handleBuffer")
            super.play()
        }
        return consumed
    }

    override fun play() {
        Log.d("LoopPcmSource", "tap play")
        super.play()
    }

    override fun pause() {
        Log.d("LoopPcmSource", "tap pause")
        forcedPlay = false
        super.pause()
    }

    override fun handleDiscontinuity() {
        Log.w("LoopPcmSource", "tap handleDiscontinuity")
        super.handleDiscontinuity()
    }

    override fun flush() {
        Log.d("LoopPcmSource", "tap flush")
        forcedPlay = false
        controller.onSinkFlush()
        super.flush()
    }

    override fun reset() {
        Log.d("LoopPcmSource", "tap reset")
        forcedPlay = false
        controller.onSinkReset()
        super.reset()
    }

    override fun release() {
        controller.onSinkRelease()
        super.release()
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
        Log.d("LoopPcmSource", "buildAudioSink source float=" + floatOutput + " params=" + playbackParams)
        val delegate = DefaultAudioSink.Builder(context)
            // PCM16 直通；float 输出会让 tap 的 bytesPerFrame 口径变化。
            .setEnableFloatOutput(false)
            .setEnableAudioTrackPlaybackParams(playbackParams)
            .build()
        return TapAudioSink(delegate, controller)
    }
}
