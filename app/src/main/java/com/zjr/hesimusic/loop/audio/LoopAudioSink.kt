package com.zjr.hesimusic.loop.audio

import android.media.AudioFormat
import android.util.Log
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
 * 时间域约定（生产实现的核心不变量）：
 * - engine 内部：capture/plan/currentPosition/replay chunk 全部是**媒体时间**（0 起）；
 * - delegate：handleBuffer PTS = 引擎媒体时间 + 当前 outputStreamOffsetUs；
 * - controller.positionUs：媒体时间（getCurrentPositionUs 已归一化）；
 * - outputStreamOffsetUs 可能先于 configure 被 ExoPlayer 设置（探针实测 1e12），
 *   所以 configure 绝不能重置它，只能在 setOutputStreamOffsetUs 回调里更新。
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

    /** ring 预分配未完成时的背压重试计数。 */
    private var preallocRetries: Int = 0

    // ---- LoopPerf 诊断计数（1Hz Log.d） ----
    private var pumpCalls: Long = 0L
    private var pumpWriteFrames: Long = 0L
    private var lastPumpWrote: Long = 0L
    private var playToEndCalls: Long = 0L
    private var isEndedCalls: Long = 0L
    private var lastDelegateEnded: Boolean = false
    private var positionPumpCalls: Long = 0L
    private var lastPerfLogNanos: Long = 0L

    // 活性看门狗：pendingReplay 连续多轮零消费时自愈，绝不永久卡 REPLAYING。
    private var stuckReplayAttempts: Int = 0
    private var stuckReplaySinceNanos: Long = 0L

    /** 最近一次成功交给 delegate 的起始媒体 PTS（含 offset 域）；用于拒绝倒退重放。 */
    private var lastDelegatePtsUs: Long = Long.MIN_VALUE

    /** 上一次同步给引擎的窗口；窗口变化时丢弃旧窗口的 pending 重放 chunk。 */
    private var observedWindow: LoopWindow? = null

    private class PendingInput(
        val buffer: ByteBuffer,
        /** 这段输入第一个样本的媒体时间 PTS（已归一化，不含 output offset）。 */
        val baseMediaPtsUs: Long,
        /** plan 的转发起点（绝对 buffer 偏移）。 */
        val forwardStartOffset: Int,
        /** plan 的转发终点（绝对 buffer 偏移，= B 截断点）。 */
        val forwardEndOffset: Int,
        val plan: LoopPcmEngine.BufferPlan,
    )

    private class PendingReplay(
        val chunk: LoopPcmEngine.ReplayChunk,
        val buffer: ByteBuffer,
        /** 剩余数据第一个样本的**媒体时间** PTS；写 delegate 时再加当前 output offset。 */
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
        // 注意：不要在这里重置 outputStreamOffsetUs。
        // ExoPlayer 会在 configure 之前先调 setOutputStreamOffsetUs（探针实测 1e12），
        // 重置会把 sink 时间轴打回媒体时间，导致 PTS 与 A/B 不同域、捕获永不命中。
        pendingInput = null
        pendingReplay = null
        stuckReplayAttempts = 0
        stuckReplaySinceNanos = 0L
        lastDelegatePtsUs = Long.MIN_VALUE

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

        // ring 预分配未完成：对 delegate 施加背压（不消费本 buffer），
        // 避免音频线程自己分配 64MB，也避免丢数据。超时后降级为直通。
        if (engine.isPreallocationPending()) {
            if (preallocRetries++ < MAX_PREALLOC_RETRIES) {
                return false
            }
            preallocRetries = 0
            engine.abandonPreallocation()
        } else {
            preallocRetries = 0
        }

        // 背压重试：delegate 没吃完的输入必须用同一个 ByteBuffer 继续。
        val pending = pendingInput
        if (pending != null) {
            if (pending.buffer === buffer) {
                return continuePendingInput(buffer)
            }
            pendingInput = null
        }

        val frames = buffer.remaining() / bytesPerFrame
        if (frames <= 0) {
            // 空 buffer 在重放态也要消费掉，避免 renderer 因 false 反复重试空 buffer。
            if (engine.state == LoopPcmEngine.State.REPLAYING) {
                pump()
                buffer.position(buffer.limit())
                return true
            }
            return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        }

        // 引擎只看媒体时间（0 起）；delegate 仍看“媒体时间 + 当前 output offset”。
        val mediaPts = presentationTimeUs - outputStreamOffsetUs

        // 重放态节流：只有 pump() 真的写出 >0 帧，才消费/丢弃这个输入 buffer。
        // 否则返回 false 让 renderer 重试（重试即再次泵），解码器不会被喂到 EOS。
        if (engine.state == LoopPcmEngine.State.REPLAYING) {
            // 不变量：重放态收到 PTS < A 的输入（降级 seek 把播放头拉到前奏）时，
            // 必须立刻退出重放转 CAPTURING，否则 pump 无有效帧会永久卡死。
            val w = controller.window
            if (w != null && mediaPts < w.loopStartUs && engine.onPreludeInput(mediaPts)) {
                pendingReplay = null
                try {
                    Log.w("LoopPerf", "replay->capture prelude mediaPtsUs=" + mediaPts + " A=" + w.loopStartUs)
                } catch (_: Throwable) {
                }
            }
            if (engine.state == LoopPcmEngine.State.REPLAYING) {
                // discontinuity 未伴随 flush 时，用第一段新输入 PTS 重新定位相位。
                if (engine.isPostFlushPhasePending() && engine.tryRephaseFromInput(mediaPts)) {
                    buffer.position(buffer.limit())
                    pump()
                    return true
                }
                if (engine.state != LoopPcmEngine.State.REPLAYING) {
                    pendingReplay = null
                }
                if (engine.state == LoopPcmEngine.State.REPLAYING) {
                    val written = pump()
                    if (written > 0L) {
                        buffer.position(buffer.limit())
                        return true
                    }
                    return false
                }
                // tryRephaseFromInput 把状态切回 CAPTURING（seek 到前奏）：落到下面正常处理。
            }
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
        val accepted = super.handleBuffer(
            buffer,
            mediaPts + outputStreamOffsetUs,
            1,
        )
        val after = buffer.position()
        if (after > before) {
            lastDelegatePtsUs = mediaPts + outputStreamOffsetUs
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
            baseMediaPtsUs = mediaPts,
            forwardStartOffset = start,
            forwardEndOffset = end,
            plan = plan,
        )
        return false
    }

    override fun playToEndOfStream() {
        playToEndCalls += 1L
        if (loopEngaged()) {
            // seek/flush 后还没看到新输入：等待 handleBuffer 决定相位，不能先按 0 进重放。
            if (engine.state == LoopPcmEngine.State.CAPTURING &&
                (engine.isPostFlushPhasePending() ||
                    (engine.isRingPrefilled && !engine.isRingComplete))
            ) {
                return
            }
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
        isEndedCalls += 1L
        if (loopEngaged()) {
            pump()
            return false
        }
        val ended = super.isEnded()
        lastDelegateEnded = ended
        return ended
    }

    override fun getCurrentPositionUs(forceDiscontinuityPosition: Boolean): Long {
        // EOS 兜底：若 renderer 不再调 playToEndOfStream/isEnded，位置查询仍可驱动泵。
        if (!released && !disabled && engine.state == LoopPcmEngine.State.REPLAYING) {
            positionPumpCalls += 1L
            pump()
        }
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
     * delegate 位置 -> 媒体位置。正常路径下 delegate 位置 = outputOffset + media；
     * 少数内部 reset 情况下可能已经是 media，因此 raw < offset 时不再减。
     */
    private fun toMediaPositionUs(rawUs: Long): Long {
        val offset = outputStreamOffsetUs
        return if (offset > 0L && rawUs >= offset) rawUs - offset else rawUs
    }

    override fun handleDiscontinuity() {
        engine.onInputDiscontinuity()
        super.handleDiscontinuity()
    }

    override fun setOutputStreamOffsetUs(offsetUs: Long) {
        outputStreamOffsetUs = offsetUs
        super.setOutputStreamOffsetUs(offsetUs)
    }

    override fun flush() {
        pendingInput = null
        pendingReplay = null
        stuckReplayAttempts = 0
        stuckReplaySinceNanos = 0L
        lastDelegatePtsUs = Long.MIN_VALUE
        preallocRetries = 0
        engine.onFlush()
        controller.onSinkFlush()
        super.flush()
    }

    override fun reset() {
        pendingInput = null
        pendingReplay = null
        stuckReplayAttempts = 0
        stuckReplaySinceNanos = 0L
        lastDelegatePtsUs = Long.MIN_VALUE
        preallocRetries = 0
        engine.onReset()
        controller.onSinkReset()
        super.reset()
    }

    override fun release() {
        released = true
        disabled = true
        pendingInput = null
        pendingReplay = null
        stuckReplayAttempts = 0
        stuckReplaySinceNanos = 0L
        lastDelegatePtsUs = Long.MIN_VALUE
        preallocRetries = 0
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
            preallocRetries = 0
        }
        engine.setWindow(window)
    }

    private fun continuePendingInput(buffer: ByteBuffer): Boolean {
        val p = pendingInput ?: return false
        if (p.buffer !== buffer) return false

        val consumedFrames = (
            (buffer.position() - p.forwardStartOffset).coerceAtLeast(0) / bytesPerFrame
            ).toLong()
        val mediaPtsUs = p.baseMediaPtsUs + consumedFrames * 1_000_000L / sampleRate
        buffer.limit(p.forwardEndOffset)

        val before = buffer.position()
        val accepted = super.handleBuffer(
            buffer,
            mediaPtsUs + outputStreamOffsetUs,
            1,
        )
        val after = buffer.position()
        if (after > before) {
            lastDelegatePtsUs = mediaPtsUs + outputStreamOffsetUs
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

    /**
     * renderer 的 ~100Hz 轮询驱动的泵：把 ring 经 delegate 继续喂回去。
     *
     * @return 本次实际写进 delegate 的帧数。0 表示 delegate 满/不可写。
     */
    private fun pump(): Long {
        val before = engine.totalReplayFrames
        pumpCalls += 1L
        pumpBody()
        val written = engine.totalReplayFrames - before
        lastPumpWrote = written
        pumpWriteFrames += written
        maybeLogPerf()
        return written
    }

    private fun pumpBody() {
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
        val delegatePtsUs = pending.ptsUs + outputStreamOffsetUs
        val oneFrameUs = 1_000_000L / sampleRate.coerceAtLeast(1)
        if (lastDelegatePtsUs != Long.MIN_VALUE &&
            delegatePtsUs < lastDelegatePtsUs - oneFrameUs
        ) {
            try {
                Log.w(
                    "LoopPerf",
                    "replay timestamp backward pts=" + delegatePtsUs +
                        " last=" + lastDelegatePtsUs +
                        " " + engine.debugStateString(),
                )
            } catch (_: Throwable) {
            }
            engine.onReplayTimestampBackward()
            pendingReplay = null
            return false
        }
        try {
            // pending.ptsUs 是引擎媒体时间；delegate 必须看到同一 offset 域。
            super.handleBuffer(bb, delegatePtsUs, 1)
        } catch (t: Throwable) {
            try {
                Log.w("LoopPerf", "replay write failed pts=" + delegatePtsUs + " " + engine.debugStateString(), t)
            } catch (_: Throwable) {
            }
            engine.onReplayFailure()
            pendingReplay = null
            throw t
        }
        val consumed = bb.position() - before
        if (consumed > 0) {
            lastDelegatePtsUs = delegatePtsUs
            stuckReplayAttempts = 0
            stuckReplaySinceNanos = 0L
            controller.tapPcm(bb, before, consumed)
            // 逐次上报已消费字节，让引擎位置/接缝打点与 delegate 实际进度一致。
            engine.onReplayChunkConsumed(consumed)
        }
        if (bb.hasRemaining()) {
            if (consumed <= 0) {
                val now = System.nanoTime()
                if (stuckReplaySinceNanos == 0L) stuckReplaySinceNanos = now
                stuckReplayAttempts += 1
                if (stuckReplayAttempts >= MAX_STUCK_REPLAY_WRITES ||
                    now - stuckReplaySinceNanos >= STUCK_REPLAY_TIMEOUT_NANOS
                ) {
                    try {
                        Log.w(
                            "LoopPerf",
                            "replay stuck recovery attempts=" + stuckReplayAttempts +
                                " ptsUs=" + pending.ptsUs +
                                " remaining=" + bb.remaining() +
                                " " + engine.debugStateString(),
                        )
                    } catch (_: Throwable) {
                    }
                    engine.onReplayStuck()
                    pendingReplay = null
                    stuckReplayAttempts = 0
                    stuckReplaySinceNanos = 0L
                    return false
                }
            } else {
                stuckReplayAttempts = 0
                stuckReplaySinceNanos = 0L
            }
            val frames = consumed / bytesPerFrame
            if (frames > 0) {
                pending.ptsUs += frames.toLong() * 1_000_000L / sampleRate
            }
            return false
        }
        stuckReplayAttempts = 0
        stuckReplaySinceNanos = 0L
        return true
    }

    /** 1Hz 低频诊断：确认 EOS 后 renderer 是否还在调 playToEndOfStream/isEnded。 */
    private fun maybeLogPerf() {
        val now = System.nanoTime()
        if (now - lastPerfLogNanos < PERF_LOG_INTERVAL_NANOS) return
        lastPerfLogNanos = now
        val message = "state=" + engine.state +
            " pumpWrites=" + pumpWriteFrames +
            " pumpCalls=" + pumpCalls +
            " lastPump=" + lastPumpWrote +
            " pendingReplay=" + (pendingReplay != null) +
            " pendingInput=" + (pendingInput != null) +
            " playToEnd=" + playToEndCalls +
            " isEndedCalls=" + isEndedCalls +
            " lastDelegateEnded=" + lastDelegateEnded +
            " posPumps=" + positionPumpCalls +
            " stallCount=" + engine.stallCount +
            " covered=" + engine.coveredFrames +
            " cursor=" + engine.currentCursorFrames() +
            " pendingInputBytes=" + (pendingInput?.buffer?.remaining() ?: 0) +
            " pendingReplayBytes=" + (pendingReplay?.buffer?.remaining() ?: 0) +
            " selfHeal=" + engine.replaySelfHealCount +
            " replayReject=" + engine.replayRejectCount +
            " writeFail=" + engine.replayFailureCount
        try {
            Log.d("LoopPerf", message)
        } catch (t: Throwable) {
            // JVM 单测的 android.jar 是 stub；生产环境不会走到这里。
        }
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

        /** 预分配背压上限：约 2s（renderer ~100Hz 轮询）。 */
        const val MAX_PREALLOC_RETRIES = 200

        /** pendingReplay 连续零消费多少轮后判定卡死。 */
        const val MAX_STUCK_REPLAY_WRITES: Int = 20

        /** pendingReplay 零消费持续多久后判定卡死。 */
        const val STUCK_REPLAY_TIMEOUT_NANOS: Long = 200_000_000L

        /** LoopPerf 诊断日志间隔。 */
        const val PERF_LOG_INTERVAL_NANOS = 1_000_000_000L
    }
}
