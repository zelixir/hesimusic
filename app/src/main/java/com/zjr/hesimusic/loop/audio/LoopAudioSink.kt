package com.zjr.hesimusic.loop.audio

import android.media.AudioFormat
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import com.zjr.hesimusic.loop.LoopWindow
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 生产版 M3' sink：包住 delegate（DefaultAudioSink），在 PCM 层完成无缝环回。
 *
 * 探针已验证的硬约束全部保留：
 * - 重放用的 direct ByteBuffer 必须 LITTLE_ENDIAN；
 * - delegate 没吃完时必须复用**同一个** ByteBuffer 实例重试；
 * - release 后置 disabled，防止收尾期被再次泵入（探针曾在 stop 后多打一个 seam）；
 * - 重放期不调用 delegate.playToEndOfStream() / 不进入 ended 态（AudioTrack 不 stop）。
 *
 * 本类不 import 任何 android.* 运行时 API（只用 media3 的 Format/C），因此可以在
 * JVM 单测里配 FakeAudioSink 直接跑。
 */
@UnstableApi
class LoopAudioSink(
    private val controller: LoopAudioController,
    delegate: AudioSink,
) : ForwardingAudioSink(delegate) {

    private val engine: LoopPcmEngine
        get() = controller.engine

    @Volatile
    private var sampleRate: Int = 0

    @Volatile
    private var channelCount: Int = 0

    @Volatile
    private var bytesPerFrame: Int = 0

    @Volatile
    private var pcmIsFloat: Boolean = false

    @Volatile
    private var configured: Boolean = false

    @Volatile
    private var outputStreamOffsetUs: Long = 0L

    /** release 之后禁止再启动重放。 */
    @Volatile
    private var released: Boolean = false

    @Volatile
    private var disabled: Boolean = false

    private var pendingInput: PendingInput? = null
    private var pendingReplay: PendingReplay? = null

    /** 上一次同步给引擎的窗口；窗口变化时丢弃旧窗口的 pending 重放 chunk。 */
    private var observedWindow: LoopWindow? = null

    private class PendingInput(
        val buffer: ByteBuffer,
        /** 这段输入第一个样本的 PTS（可能含 outputStreamOffsetUs）。 */
        val basePtsUs: Long,
        /** plan 的转发起点（绝对 buffer 偏移）。 */
        val forwardStartOffset: Int,
        /** plan 的转发终点（绝对 buffer 偏移，= B 截断点）。 */
        val forwardEndOffset: Int,
        val plan: LoopPcmEngine.BufferPlan,
    )

    private class PendingReplay(
        val chunk: LoopPcmEngine.ReplayChunk,
        val buffer: ByteBuffer,
        /** 剩余数据第一个样本的 PTS（每次部分消费后按帧推进）。 */
        var ptsUs: Long,
    )

    // ------------------------------------------------------------------ AudioSink

    override fun configure(format: Format, bufferSize: Int, audioTrackChannelConfig: IntArray?) {
        val bpf = bytesPerFrameOf(format)
        sampleRate = format.sampleRate.coerceAtLeast(1)
        channelCount = format.channelCount.coerceAtLeast(1)
        bytesPerFrame = bpf
        pcmIsFloat = format.pcmEncoding == C.ENCODING_PCM_FLOAT
        configured = sampleRate > 0 && channelCount > 0 && bpf > 0
        released = false
        disabled = false
        outputStreamOffsetUs = 0L
        pendingInput = null
        pendingReplay = null

        controller.onFormatConfigured(sampleRate, channelCount, bpf, pcmIsFloat)
        observedWindow = controller.window
        engine.setWindow(controller.window)
        super.configure(format, bufferSize, audioTrackChannelConfig)
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        if (released || disabled) {
            buffer.position(buffer.limit())
            return true
        }
        if (!configured || bytesPerFrame <= 0) {
            return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        }

        syncWindow()

        // 背压重试：delegate 没吃完的输入必须用同一个 ByteBuffer 继续。
        val pending = pendingInput
        if (pending != null) {
            if (pending.buffer === buffer) {
                return continuePendingInput(buffer)
            }
            pendingInput = null
        }

        val mediaPts = presentationTimeUs - outputStreamOffsetUs
        val frames = buffer.remaining() / bytesPerFrame
        if (frames <= 0) {
            // 不足一帧：交给 delegate 自行处理（通常是空 buffer）。
            return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        }

        val plan = engine.onBuffer(mediaPts, frames, buffer)
        if (plan.discard || plan.forwardLength <= 0) {
            buffer.position(buffer.limit())
            engine.onInputDrained(plan)
            if (plan.enterReplayWhenDrained) {
                pump()
            } else if (engine.state == LoopPcmEngine.State.REPLAYING) {
                pump()
            }
            return true
        }

        val start = plan.forwardOffset
        val end = start + plan.forwardLength
        buffer.position(start)
        buffer.limit(end)
        val before = buffer.position()
        val accepted = super.handleBuffer(buffer, presentationTimeUs, 1)
        val after = buffer.position()
        if (after > before) {
            controller.tapPcm(buffer, before, after - before)
        }

        if (after >= end) {
            pendingInput = null
            engine.onInputDrained(plan)
            if (plan.enterReplayWhenDrained) {
                pump()
            }
            return true
        }

        pendingInput = PendingInput(
            buffer = buffer,
            basePtsUs = presentationTimeUs,
            forwardStartOffset = start,
            forwardEndOffset = end,
            plan = plan,
        )
        return false
    }

    override fun playToEndOfStream() {
        if (loopEngaged()) {
            // 源端 EOS 兜底：最后一个 buffer 可能恰好停在 B 前一帧，没触发 crossedB。
            if (engine.state == LoopPcmEngine.State.CAPTURING && !engine.onInputEnded()) {
                // ring 不完整 -> 已降级为直通，交还 delegate。
                super.playToEndOfStream()
                return
            }
            // 关键：循环态只泵，不调用 delegate 同名方法（delegate 永不进入 ended）。
            pump()
            return
        }
        super.playToEndOfStream()
    }

    override fun isEnded(): Boolean {
        if (loopEngaged()) {
            pump()
            return false
        }
        return super.isEnded()
    }

    override fun getCurrentPositionUs(forceDiscontinuityPosition: Boolean): Long {
        val raw = super.getCurrentPositionUs(forceDiscontinuityPosition)
        if (!released) {
            if (raw != AudioSink.CURRENT_POSITION_NOT_SET) {
                // 优先用 delegate 的原生位置（AudioTrack playhead），只做坐标归一化。
                controller.updatePositionUs(toMediaPositionUs(raw))
            } else if (engine.state == LoopPcmEngine.State.REPLAYING) {
                // delegate 未给出位置时，回落到引擎帧游标。
                val enginePos = engine.currentPositionUs()
                if (enginePos >= 0L) controller.updatePositionUs(enginePos)
            }
        }
        return raw
    }

    /**
     * delegate 位置 -> 媒体位置。ExoPlayer 通常给 PTS 加一个较大的 outputStreamOffset
     * （探针实测 1e12），但重放期我们喂的是媒体时间 PTS，delegate 位置可能已经是媒体时间。
     */
    private fun toMediaPositionUs(rawUs: Long): Long {
        val offset = outputStreamOffsetUs
        return if (offset > 0L && rawUs >= offset) rawUs - offset else rawUs
    }

    override fun setOutputStreamOffsetUs(offsetUs: Long) {
        outputStreamOffsetUs = offsetUs
        super.setOutputStreamOffsetUs(offsetUs)
    }

    override fun flush() {
        pendingInput = null
        pendingReplay = null
        engine.onFlush()
        controller.onSinkFlush()
        super.flush()
    }

    override fun reset() {
        pendingInput = null
        pendingReplay = null
        engine.onReset()
        controller.onSinkReset()
        super.reset()
    }

    override fun release() {
        released = true
        disabled = true
        pendingInput = null
        pendingReplay = null
        engine.onRelease()
        controller.onSinkRelease()
        super.release()
    }

    // ------------------------------------------------------------------ 内部

    private fun loopEngaged(): Boolean {
        if (released || disabled || !configured) return false
        syncWindow()
        return engine.isLoopConfigured()
    }

    private fun syncWindow() {
        val window = controller.window
        if (window != observedWindow) {
            observedWindow = window
            // 旧窗口的 ring/游标已失效，旧 chunk 不能再喂给 delegate。
            pendingReplay = null
        }
        engine.setWindow(window)
    }

    private fun continuePendingInput(buffer: ByteBuffer): Boolean {
        val p = pendingInput ?: return false
        if (p.buffer !== buffer) return false

        val consumedFrames = (
            (buffer.position() - p.forwardStartOffset).coerceAtLeast(0) / bytesPerFrame
            ).toLong()
        val ptsUs = p.basePtsUs + consumedFrames * 1_000_000L / sampleRate
        buffer.limit(p.forwardEndOffset)

        val before = buffer.position()
        val accepted = super.handleBuffer(buffer, ptsUs, 1)
        val after = buffer.position()
        if (after > before) {
            controller.tapPcm(buffer, before, after - before)
        }

        if (after >= p.forwardEndOffset) {
            pendingInput = null
            engine.onInputDrained(p.plan)
            if (p.plan.enterReplayWhenDrained) {
                pump()
            }
            return true
        }
        return false
    }

    /** renderer 的 ~100Hz 轮询驱动的泵：把 ring 经 delegate 继续喂回去。 */
    private fun pump() {
        if (released || disabled) return
        if (engine.state != LoopPcmEngine.State.REPLAYING) {
            pendingReplay = null
            return
        }
        var guard = 0
        while (guard++ < MAX_CHUNKS_PER_PUMP) {
            if (engine.state != LoopPcmEngine.State.REPLAYING) {
                pendingReplay = null
                return
            }
            val pending = pendingReplay
            if (pending != null) {
                if (!writePendingReplay(pending)) return
                pendingReplay = null
                continue
            }

            val chunk = engine.pumpChunk(replayChunkBytes()) ?: break
            val bb = ByteBuffer.allocateDirect(chunk.byteLength).order(ByteOrder.LITTLE_ENDIAN)
            engine.copyChunk(chunk, bb)
            bb.flip()
            pendingReplay = PendingReplay(chunk, bb, chunk.ptsUs)
        }
    }

    /** @return true = 这个 chunk 已全部喂给 delegate。 */
    private fun writePendingReplay(pending: PendingReplay): Boolean {
        val bb = pending.buffer
        val before = bb.position()
        try {
            super.handleBuffer(bb, pending.ptsUs, 1)
        } catch (t: Throwable) {
            engine.onReplayFailure()
            pendingReplay = null
            throw t
        }
        val consumed = bb.position() - before
        if (consumed > 0) {
            controller.tapPcm(bb, before, consumed)
            // 逐次上报已消费字节，让引擎位置/接缝打点与 delegate 实际进度一致。
            engine.onReplayChunkConsumed(consumed)
        }
        if (bb.hasRemaining()) {
            val frames = consumed / bytesPerFrame
            if (frames > 0) {
                pending.ptsUs += frames.toLong() * 1_000_000L / sampleRate
            }
            return false
        }
        return true
    }

    private fun replayChunkBytes(): Int {
        val chunkMs = LoopPcmEngine.REPLAY_CHUNK_MS.coerceAtLeast(1)
        val frames = maxOf(1, sampleRate / (1000 / chunkMs))
        return maxOf(bytesPerFrame, frames * bytesPerFrame)
    }

    private fun bytesPerFrameOf(format: Format): Int {
        val bitsPerSample = when (format.pcmEncoding) {
            C.ENCODING_PCM_8BIT -> 8
            C.ENCODING_PCM_16BIT -> 16
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 32
            else -> 16
        }
        val channels = if (format.channelCount > 0) format.channelCount else 2
        return maxOf(1, bitsPerSample / 8 * channels)
    }

    private companion object {
        const val MAX_CHUNKS_PER_PUMP = 128
    }
}
