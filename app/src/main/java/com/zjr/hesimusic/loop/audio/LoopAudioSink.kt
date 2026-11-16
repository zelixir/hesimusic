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

    /** 最近一次输入 buffer 的媒体 PTS；用于自动识别 seek 大跳变。 */
    private var lastInputPtsUs: Long = Long.MIN_VALUE

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

    private enum class SeamRouteState { NORMAL, SEAM, WAIT_SOURCE, FALLBACK }

    private class PendingSeam(
        val data: ByteArray,
        var offset: Int,
        val ptsUs: Long,
        val source: Boolean,
    )

    private var seamBuffer: LoopSeamSpliceBuffer? = null
    private var seamState = SeamRouteState.NORMAL
    private var seamPrepared = false
    private var seamSeekIssued = false
    private var pendingSeam: PendingSeam? = null
    private val seamQueue = ArrayDeque<PendingSeam>()
    private var seamDiscontinuityCount = 0L
    private var seamSwitchCount = 0L
    private var seamDeferCount = 0L
    private var seamVerifyFailCount = 0L
    private var seamMaxGapFrames = 0L

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
        lastInputPtsUs = Long.MIN_VALUE

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

        if (seamRouteActive()) {
            val handled = handleSeamBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
            if (seamState != SeamRouteState.FALLBACK) return handled
            // 失败回退：buffer 未被消费时落到 M3′ 正常路径。
            if (buffer.position() >= buffer.limit()) return false
        }

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
        val stateBefore = engine.state
        val prevInputPts = lastInputPtsUs
        lastInputPtsUs = mediaPts
        if (prevInputPts != Long.MIN_VALUE &&
            kotlin.math.abs(mediaPts - prevInputPts) > INPUT_PTS_JUMP_US
        ) {
            // ExoPlayer 偶尔不在 seek 后调 handleDiscontinuity；输入 PTS 大跳变
            // 等价于时间戳不连续，主动重置 delegate 基线，否则后续重放 PTS 会被拒收。
            lastDelegatePtsUs = Long.MIN_VALUE
            try {
                super.handleDiscontinuity()
                Log.w(
                    "LoopPerf",
                    "input pts jump " + prevInputPts + "->" + mediaPts +
                        " -> delegate discontinuity",
                )
            } catch (_: Throwable) {
            }
        }
        if (engine.isPostFlushPhasePending()) {
            try {
                Log.d(
                    "LoopPerf",
                    "input pendingPhase rawPts=" + presentationTimeUs +
                        " offset=" + outputStreamOffsetUs +
                        " mediaPts=" + mediaPts +
                        " " + engine.debugStateString(),
                )
            } catch (_: Throwable) {
            }
        }

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
                logInputState("rephase", mediaPts, stateBefore)
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
        logInputState("onBuffer", mediaPts, stateBefore)
        if (plan.discard || plan.forwardLength <= 0) {
            buffer.position(buffer.limit())
            engine.onInputDrained(plan)
            logInputState("onDrained", mediaPts, stateBefore)
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
            logInputState("onDrained", mediaPts, stateBefore)
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

    private fun handleSeamBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        val seam = seamBuffer ?: return false
        val originalLimit = buffer.limit()
        val inputStart = buffer.position()
        val frames = buffer.remaining() / bytesPerFrame
        if (frames <= 0) {
            pumpSeam()
            return pendingSeam == null
        }
        val mediaPts = presentationTimeUs - outputStreamOffsetUs


        // 先写掉 delegate 没吃完的 S/源片段，背压优先。
        if (pendingSeam != null) {
            if (!writePendingSeam()) return false
        }

        if (seamState == SeamRouteState.SEAM) {
            buffer.position(originalLimit)
            pumpSeam()
            return pendingSeam == null
        }

        if (seamState == SeamRouteState.WAIT_SOURCE) {
            val targetUs = frameToUsCeil(seam.resumeFrame())
            if (kotlin.math.abs(mediaPts - targetUs) <= 10_000L) {
                switchSeamToSource()
                // 落到下面，用同一个 buffer 正常写源。
            } else {
                // 源还没 seek 到 A+Δ：丢弃输入，必要时顺延一个 Δ，绝不静音卡死。
                buffer.position(originalLimit)
                if (pendingSeam == null && seam.seamDeferCount < 2L) {
                    val deferred = seam.deferSeam()
                    if (deferred != null) {
                        seamDeferCount += 1L
                        queueSeam(
                            deferred,
                            frameToUsCeil(seam.windowEndFrame + seam.seamDeferCount * seam.deltaFrames),
                        )
                        pumpSeam()
                    }
                }
                return true
            }
        }

        // NORMAL：只放行到 B-Δ；跨过边界就切 S。
        val bDeltaFrame = seam.windowEndFrame - seam.deltaFrames
        val startFrame = usToFrameFloor(mediaPts)
        if (startFrame >= bDeltaFrame) {
            seamLog(
                "check src=" + mediaPts + " bDelta=" + frameToUsCeil(bDeltaFrame) +
                    " state=" + seamState + " readyX=" + seam.isPrefixReady +
                    " readyY=" + seam.isSuffixReady + " verified=" + seam.isVerified +
                    " decision=ROUTE",
            )
            if (!tryBeginSeam(seam, buffer, 0, minOf(frames.toLong(), seam.deltaFrames).toInt())) {
                buffer.position(inputStart)
                buffer.limit(originalLimit)
                seamState = SeamRouteState.FALLBACK
                return false
            }
            buffer.position(originalLimit)
            pumpSeam()
            return pendingSeam == null
        }

        val framesToBoundary = bDeltaFrame - startFrame
        if (frames.toLong() <= framesToBoundary) {
            val ok = writeSourceSlice(buffer, mediaPts, buffer.remaining(), originalLimit)
            return ok
        }

        // 先校验（此时 buffer 里仍有边界样本），再只转发到边界。
        if (!seamPrepared) {
            seamPrepared = seam.prepareSeamPrefix() && seam.prepareSeamSuffix()
        }
        if (!seamPrepared) {
            buffer.position(inputStart)
            buffer.limit(originalLimit)
            seamState = SeamRouteState.FALLBACK
            Log.w("LoopPerf", "seam prepare unavailable -> fallback M3'")
            return false
        }
        val liveBytes = minOf(
            (frames - framesToBoundary).toInt() * bytesPerFrame,
            (seam.deltaFrames * bytesPerFrame).toInt(),
        )
        if (!tryBeginSeam(seam, buffer, framesToBoundary.toInt() * bytesPerFrame, liveBytes / bytesPerFrame)) {
            buffer.position(inputStart)
            buffer.limit(originalLimit)
            seamState = SeamRouteState.FALLBACK
            Log.w("LoopPerf", "seam verify failed -> fallback M3'")
            return false
        }
        // 只写边界前源音频。
        val before = buffer.position()
        val srcStart = buffer.position()
        buffer.limit(srcStart + framesToBoundary.toInt() * bytesPerFrame)
        val sourceOk = writeSourceSlice(buffer, mediaPts, framesToBoundary.toInt() * bytesPerFrame, buffer.limit())
        if (!sourceOk) {
            buffer.position(originalLimit)
            return false
        }
        buffer.position(originalLimit)
        pumpSeam()
        return pendingSeam == null
    }

    private fun tryBeginSeam(
        seam: LoopSeamSpliceBuffer,
        buffer: ByteBuffer,
        liveOffsetBytes: Int,
        liveFrames: Int,
    ): Boolean {
        if (!seamPrepared) {
            seamPrepared = seam.prepareSeamPrefix() && seam.prepareSeamSuffix()
        }
        if (!seamPrepared) {
            seamLog("skip reason=PREPARE x=" + seam.isPrefixReady + " y=" + seam.isSuffixReady)
            return false
        }
        val live = ByteArray(maxOf(0, liveFrames) * bytesPerFrame)
        if (live.isNotEmpty()) {
            val dup = buffer.duplicate()
            dup.position(buffer.position() + liveOffsetBytes)
            dup.get(live, 0, live.size)
        }
        if (!seam.verifyLivePrefix(live, liveFrames)) {
            seamVerifyFailCount += 1L
            seamLog("skip reason=VERIFY liveFrames=" + liveFrames)
            return false
        }
        if (!seam.beginSeam()) {
            seamLog("skip reason=BEGIN x=" + seam.isPrefixReady + " y=" + seam.isSuffixReady)
            return false
        }
        seamState = SeamRouteState.SEAM
        seamSwitchCount += 1L
        seamSeekIssued = false
        if (!seamSeekIssued) {
            seamSeekIssued = true
            controller.seamSeekRequested?.invoke(
                frameToUsCeil(seam.windowStartFrame + seam.deltaFrames),
            )
        }
        queueSeam(seam.seamPrefixBytes(), frameToUsCeil(seam.windowEndFrame - seam.deltaFrames))
        queueSeam(seam.seamSuffixBytes(), frameToUsCeil(seam.windowEndFrame))
        seamLog("route switch=" + seamSwitchCount + " seek=" + (seam.windowStartFrame + seam.deltaFrames))
        return true
    }

    private fun switchSeamToSource() {
        try {
            super.handleDiscontinuity()
        } catch (_: Throwable) {
        }
        lastDelegatePtsUs = Long.MIN_VALUE
        lastInputPtsUs = Long.MIN_VALUE
        seamDiscontinuityCount += 1L
        seamState = SeamRouteState.NORMAL
        seamSeekIssued = false
        try {
            Log.d(
                "LoopPerf",
                "seam->source discontinuity count=" + seamDiscontinuityCount +
                    " resumeFrame=" + (seamBuffer?.resumeFrame() ?: -1L),
            )
        } catch (_: Throwable) {
        }
    }

    private fun queueSeam(data: ByteArray, ptsUs: Long) {
        if (data.isEmpty()) return
        seamQueue.addLast(PendingSeam(data, 0, ptsUs, source = false))
    }

    private fun writePendingSeam(): Boolean {
        val pending = pendingSeam ?: return true
        val remaining = pending.data.size - pending.offset
        if (remaining <= 0) {
            pendingSeam = null
            return true
        }
        val slice = ByteBuffer.wrap(pending.data, pending.offset, remaining)
        val before = slice.position()
        val consumedFrames = pending.offset / bytesPerFrame
        val pts = pending.ptsUs + consumedFrames * 1_000_000L / sampleRate
        val accepted = try {
            super.handleBuffer(slice, pts + outputStreamOffsetUs, 1)
        } catch (t: Throwable) {
            Log.w("LoopPerf", "seam write failed pts=" + pts, t)
            pendingSeam = null
            seamState = SeamRouteState.FALLBACK
            return false
        }
        val consumed = slice.position() - before
        if (consumed > 0) {
            controller.tapPcm(slice, before, consumed)
            lastDelegatePtsUs = pts + outputStreamOffsetUs
            seamBuffer?.noteOutput(
                usToFrameFloor(pts),
                (consumed / bytesPerFrame).toLong(),
            )
            pending.offset += consumed
        }
        if (pending.offset >= pending.data.size) {
            pendingSeam = null
            return true
        }
        return accepted
    }

    private fun pumpSeam(): Boolean {
        while (pendingSeam != null || seamQueue.isNotEmpty()) {
            if (pendingSeam == null) pendingSeam = seamQueue.removeFirst()
            if (!writePendingSeam()) return false
        }
        val seam = seamBuffer ?: return true
        if (seamState != SeamRouteState.SEAM) return true
        val resume = seam.finishSeam()
        seamState = SeamRouteState.WAIT_SOURCE
        try {
            Log.d("LoopPerf", "seam done resumeFrame=" + resume + " defer=" + seamDeferCount)
        } catch (_: Throwable) {
        }
        return true
    }

    private fun writeSourceSlice(
        buffer: ByteBuffer,
        mediaPtsUs: Long,
        bytes: Int,
        restoreLimit: Int,
    ): Boolean {
        val start = buffer.position()
        val end = minOf(start + bytes, restoreLimit)
        buffer.limit(end)
        val before = buffer.position()
        val accepted = try {
            super.handleBuffer(buffer, mediaPtsUs + outputStreamOffsetUs, 1)
        } catch (t: Throwable) {
            Log.w("LoopPerf", "seam source write failed pts=" + mediaPtsUs, t)
            seamState = SeamRouteState.FALLBACK
            return false
        }
        val after = buffer.position()
        if (after > before) {
            lastDelegatePtsUs = mediaPtsUs + outputStreamOffsetUs
            controller.tapPcm(buffer, before, after - before)
            seamBuffer?.noteOutput(
                usToFrameFloor(mediaPtsUs),
                ((after - before) / bytesPerFrame).toLong(),
            )
        }
        if (after < end) {
            val remain = ByteArray(end - after)
            buffer.get(remain)
            pendingSeam = PendingSeam(remain, 0, mediaPtsUs + (after - start) * 1_000_000L / sampleRate, source = true)
            buffer.limit(restoreLimit)
            return false
        }
        buffer.limit(restoreLimit)
        return true
    }

    private fun usToFrameFloor(us: Long): Long {
        val sr = sampleRate.toLong()
        return (us * sr) / 1_000_000L
    }

    override fun playToEndOfStream() {
        playToEndCalls += 1L
        if (seamRouteActive()) {
            if (seamState == SeamRouteState.WAIT_SOURCE && pendingSeam == null) {
                val seam = seamBuffer
                if (seam != null && seam.seamDeferCount < 2L) {
                    val deferred = seam.deferSeam()
                    if (deferred != null) {
                        seamDeferCount += 1L
                        queueSeam(
                            deferred,
                            frameToUsCeil(seam.windowEndFrame + seam.seamDeferCount * seam.deltaFrames),
                        )
                    }
                }
            }
            pumpSeam()
            return
        }
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
        if (seamRouteActive()) {
            pumpSeam()
            return false
        }
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
        // delegate 已被告知时间戳不连续：允许后续重放从更早的位置重新开始。
        lastDelegatePtsUs = Long.MIN_VALUE
        lastInputPtsUs = Long.MIN_VALUE
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
        pendingSeam = null
        seamQueue.clear()
        if (!seamSeekIssued) {
            seamBuffer?.cancel()
            seamBuffer = null
            seamState = SeamRouteState.FALLBACK
        }
        stuckReplayAttempts = 0
        stuckReplaySinceNanos = 0L
        lastDelegatePtsUs = Long.MIN_VALUE
        lastInputPtsUs = Long.MIN_VALUE
        preallocRetries = 0
        engine.onFlush()
        controller.onSinkFlush()
        super.flush()
    }

    override fun reset() {
        pendingInput = null
        pendingReplay = null
        pendingSeam = null
        seamQueue.clear()
        if (!seamSeekIssued) {
            seamBuffer?.cancel()
            seamBuffer = null
            seamState = SeamRouteState.FALLBACK
        }
        stuckReplayAttempts = 0
        stuckReplaySinceNanos = 0L
        lastDelegatePtsUs = Long.MIN_VALUE
        lastInputPtsUs = Long.MIN_VALUE
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
        pendingSeam = null
        seamQueue.clear()
        seamBuffer?.cancel()
        seamBuffer = null
        seamState = SeamRouteState.FALLBACK
        seamSeekIssued = false
        stuckReplayAttempts = 0
        stuckReplaySinceNanos = 0L
        lastDelegatePtsUs = Long.MIN_VALUE
        lastInputPtsUs = Long.MIN_VALUE
        preallocRetries = 0
        engine.onRelease()
        controller.onSinkRelease()
        super.release()
    }

    /** 单测/诊断用：接缝路由计数。 */
    internal fun seamDebugStats(): String =
        "switch=" + seamSwitchCount +
            " defer=" + seamDeferCount +
            " verifyFail=" + seamVerifyFailCount +
            " gap=" + seamMaxGapFrames +
            " bytes=" + (seamBuffer?.seamBufferBytes ?: 0L)

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
        refreshSeamRoute(window)
    }

    private fun seamRouteActive(): Boolean =
        controller.seamActiveForWindow &&
            seamBuffer != null &&
            seamState != SeamRouteState.FALLBACK

    private fun seamLog(message: String) {
        if (controller.seamBufferMode != SeamBufferMode.ON ||
            !controller.seamSinkRouteEnabled
        ) {
            return
        }
        try {
            Log.d("LoopPerf", "seam " + message)
        } catch (_: Throwable) {
        }
    }

    private fun refreshSeamRoute(window: com.zjr.hesimusic.loop.LoopWindow?) {
        if (!controller.seamActiveForWindow || window == null || sampleRate <= 0 ||
            bytesPerFrame < 2
        ) {
            seamLog(
                "skip refresh active=" + controller.seamActiveForWindow +
                    " window=" + (window != null) + " sr=" + sampleRate +
                    " bpf=" + bytesPerFrame,
            )
            seamBuffer = null
            pendingSeam = null
            seamState = SeamRouteState.FALLBACK
            return
        }
        val existing = seamBuffer
        if (existing != null &&
            existing.windowStartFrame == usToFrameCeil(window.loopStartUs) &&
            existing.windowEndFrame == usToFrameCeil(window.loopEndUs)
        ) {
            return
        }
        val a = usToFrameCeil(window.loopStartUs)
        val b = usToFrameCeil(window.loopEndUs)
        val loop = b - a
        val delta = (controller.seamDeltaUs * sampleRate / 1_000_000L)
            .coerceIn(1L, maxOf(1L, loop / 4L))
        if (loop < delta * 2L) {
            seamBuffer = null
            seamState = SeamRouteState.FALLBACK
            return
        }
        val buffer = LoopSeamSpliceBuffer(
            sampleRate = sampleRate,
            bytesPerFrame = bytesPerFrame,
            windowStartFrame = a,
            windowEndFrame = b,
            deltaFrames = delta,
            decodeRange = { s, e -> controller.seamDecodeRange?.invoke(s, e) },
        )
        buffer.beginCapture()
        seamPrepared = buffer.prepareSeamPrefix() && buffer.prepareSeamSuffix()
        seamLog(
            "refresh a=" + a + " b=" + b + " d=" + delta +
                " prefix=" + buffer.isPrefixReady + " suffix=" + buffer.isSuffixReady +
                " prepared=" + seamPrepared,
        )
        seamBuffer = buffer
        pendingSeam = null
        seamSeekIssued = false
        seamState = SeamRouteState.NORMAL
        seamSwitchCount = 0L
        seamDeferCount = 0L
        seamVerifyFailCount = 0L
        seamMaxGapFrames = 0L
        seamDiscontinuityCount = 0L
    }

    private fun usToFrameCeil(us: Long): Long {
        val sr = sampleRate.toLong()
        return (us * sr + 999_999L) / 1_000_000L
    }

    private fun frameToUsCeil(frame: Long): Long {
        val sr = sampleRate.toLong()
        return (frame * 1_000_000L + sr - 1L) / sr
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
            // 不把重放写异常抛给 renderer：退出重放后让下一段源输入继续，
            // 避免一次坏 PTS 把整个音频 renderer 打死。
            return false
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
    private fun logInputState(tag: String, mediaPts: Long, before: LoopPcmEngine.State) {
        if (before == engine.state) return
        try {
            Log.d(
                "LoopPerf",
                "input " + tag + " mediaPts=" + mediaPts +
                    " state " + before + "->" + engine.state +
                    " " + engine.debugStateString(),
            )
        } catch (_: Throwable) {
        }
    }

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
            " writeFail=" + engine.replayFailureCount +
            " seamSwitch=" + seamSwitchCount +
            " seamDefer=" + seamDeferCount +
            " seamVerifyFail=" + seamVerifyFailCount +
            " seamDiscontinuity=" + seamDiscontinuityCount +
            " seamGap=" + seamMaxGapFrames +
            " seamBytes=" + (seamBuffer?.seamBufferBytes ?: 0L)
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

        /** 输入 PTS 跳变超过 0.5s 视为 seek/discontinuity。 */
        const val INPUT_PTS_JUMP_US: Long = 500_000L

        /** pendingReplay 连续零消费多少轮后判定卡死。 */
        const val MAX_STUCK_REPLAY_WRITES: Int = 20

        /** pendingReplay 零消费持续多久后判定卡死。 */
        const val STUCK_REPLAY_TIMEOUT_NANOS: Long = 200_000_000L

        /** LoopPerf 诊断日志间隔。 */
        const val PERF_LOG_INTERVAL_NANOS = 1_000_000_000L
    }
}
