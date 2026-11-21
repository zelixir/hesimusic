package com.zjr.hesimusic.loop.audio.source

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 环带渲染器：后台单线程按**帧级覆盖位图**填环。
 *
 * 00022/00023 的核心改动：
 * - 不再"从 A 顺序解到 B"：Extractor seek 后调用 [requestFillAt]，渲染线程立刻
 *   cancel 当前解码会话，从目标 offset 开始跳填（环内按取模写），所以读到环尾
 *   不必等解码从 A 追上来；
 * - 跳填只填缺口（[LoopRing.distanceToFilledFrame]），已填区域不会被破坏；
 * - 跳填完成后继续把剩余缺口补齐（从上次写游标续填 / 扫描第一个缺口），
 *   这样 B→A 回绕时前缀始终有效。
 *
 * 00024（整曲窗口/自由试听）：窗口 B 落在文件尾时，最后一个编码样本之后**没有可输出帧**，
 * 解码器会返回 0 帧。此时缺口按静音补齐并标记 tailTrimmed（[LoopRing.tailTrimmedFrames]），
 * 播放继续；只有"远离文件尾的持续 0 帧"才判 DECODE_EXCEPTION。
 */
class RingBandRenderer(
    private val timeline: LoopingPcmTimeline,
    private val decoder: RingBandStreamDecoder,
    private val ring: LoopRing,
    private val logger: (String) -> Unit = {},
) {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "RingBandRender").apply { isDaemon = true }
    }
    private val started = AtomicBoolean(false)
    private val lock = Object()

    @Volatile
    var releaseRequested: Boolean = false
        private set

    @Volatile
    var error: String? = null
        private set

    /** 渲染线程当前填到的环内位置；-1 = 尚未写过。 */
    @Volatile
    var writeOffsetFrames: Long = -1L
        private set

    /** seek 请求次数（诊断用）。 */
    @Volatile
    var jumpCount: Long = 0L
        private set

    /**
     * 已启动的解码填充会话数（诊断用）。
     *
     * 00028(#105)：短窗口（0.5s）只需要 1~2 个会话就能填满整环，之后渲染线程 DONE 退出；
     * 接缝回绕**不**再解码。这里把会话数暴露给测试/日志，用来证明"回绕不会退化成逐次解码"。
     */
    @Volatile
    var sessionCount: Long = 0L
        private set

    /** 最近一次跳填目标的 offset（诊断用）。 */
    @Volatile
    var lastJumpOffsetFrames: Long = -1L
        private set

    /** 最近一次解码会话是否到达源尾（诊断/单测）。 */
    @Volatile
    var lastFillReachedEndOfStream: Boolean = false
        private set

    /** 用静音补齐（窗口尾部短于预期）的帧数；正常循环窗口恒为 0。 */
    val tailTrimmedFrames: Long get() = ring.tailTrimmedFrames

    /** 一个解码粒度（帧）：AAC 1024 / MP3 1152，取 2048 帧并保证不少于 20ms。 */
    private val decodeGranularityFrames: Long =
        maxOf(MIN_DECODE_GRANULARITY_FRAMES, timeline.sampleRate.toLong() * DECODE_GRANULARITY_MS / 1000L)

    /** 远离源尾的连续 0 帧会话数；达到 [MAX_EMPTY_FILL_ATTEMPTS] 才判致命。 */
    private var emptyFillAttempts: Int = 0

    private var pendingOffsetFrames: Long = -1L

    @Volatile
    private var generation: Long = 0L

    fun start() {
        if (!started.compareAndSet(false, true)) return
        try {
            executor.execute { runFillLoop() }
        } catch (t: Throwable) {
            error = "submit failed: " + t.message
            RingBandLog.w("RingBand render stop reason=SUBMIT block=-1 err=" + error)
        }
    }

    /**
     * 请求优先填充环内 [offsetFrames] 起的数据（Extractor seek 后调用）。
     *
     * 已填充则直接返回；否则打断当前解码会话，让渲染线程从该 offset 开始解。
     * 可在任意线程调用（实际来自 media3 的 loading 线程）。
     */
    fun requestFillAt(offsetFrames: Long) {
        if (releaseRequested) return
        val offset = Math.floorMod(offsetFrames, timeline.loopFrames)
        if (ring.isRangeFilled(offset, 1L)) return
        synchronized(lock) {
            pendingOffsetFrames = offset
            generation += 1L
            jumpCount += 1L
            lastJumpOffsetFrames = offset
        }
        RingBandLog.i(
            "RingBand fill request offset=" + offset + " generation=" + generation +
                " covered=" + ring.coveredFrames + " filled=" + ring.renderedFrames,
        )
        decoder.cancel()
        synchronized(lock) { lock.notifyAll() }
    }

    fun release() {
        if (releaseRequested) return
        releaseRequested = true
        RingBandLog.i("RingBand renderer release requested")
        // 关键（SIGSEGV 修复）：**绝不**在释放线程里 close codec/extractor。
        // 渲染线程可能正在 MediaExtractor.readSampleData / MediaCodec.getInputBuffer 里，
        // 跨线程 close 会让它触碰已释放的 native 结构（真机 SIGSEGV in __memcpy）。
        // 这里只置标志 + cancel（纯 volatile 写）+ 唤醒，真正的 close 由渲染线程 finally 做。
        decoder.cancel()
        synchronized(lock) { lock.notifyAll() }
        executor.shutdownNow()
        try {
            executor.awaitTermination(RELEASE_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (!executor.isTerminated) {
            // 宁可让渲染线程稍后自行收尾，也不要跨线程强关 native 资源。
            RingBandLog.w(
                "RingBand renderer release deferred: render thread still finishing; " +
                    "codec will be closed by RingBandRender",
            )
        }
        RingBandLog.i(
            "RingBand renderer release bandBytes=" +
                (timeline.loopFrames * timeline.bytesPerFrame) +
                " released=true terminated=" + executor.isTerminated,
        )
    }

    // ------------------------------------------------------------------ 渲染线程

    private fun runFillLoop() {
        var stopReason = "DONE"
        var sessions = 0
        try {
            RingBandLog.i(
                "RingBand render start [" + timeline.loopStartUs + "," +
                    timeline.loopEndUs + ") frames=" + timeline.loopFrames +
                    " blocks=" + timeline.blockCount,
            )
            while (!releaseRequested) {
                val target = nextFillTarget()
                if (target == null) {
                    if (ring.isFullyCovered) {
                        stopReason = "DONE"
                        break
                    }
                    synchronized(lock) {
                        if (!releaseRequested && pendingOffsetFrames < 0L) {
                            lock.wait(IDLE_WAIT_MS)
                        }
                    }
                    continue
                }
                sessions += 1
                fillGap(target.offsetFrames, target.generation)
                if (error != null) {
                    stopReason = "DECODE_EXCEPTION"
                    break
                }
            }
            if (error == null) {
                stopReason = if (releaseRequested) "RELEASE" else "DONE"
            }
        } catch (t: Throwable) {
            if (!releaseRequested) {
                error = if (error == null) t.javaClass.simpleName + ": " + t.message else error
            }
            stopReason = if (releaseRequested) "RELEASE" else "DECODE_EXCEPTION"
        } finally {
            try {
                decoder.close()
            } catch (t: Throwable) {
                if (error == null && !releaseRequested) error = "close: " + t.message
            }
            val message = "RingBand render stop reason=" + stopReason +
                " sessions=" + sessions +
                " covered=" + ring.coveredFrames +
                " filledFrames=" + ring.renderedFrames +
                " tailTrimmed=" + ring.tailTrimmedFrames +
                " audioCovered=" + ring.audioCoveredFrames +
                " jumps=" + jumpCount +
                " err=" + (error ?: "-")
            if (stopReason == "DONE") RingBandLog.i(message) else RingBandLog.w(message)
            logger(message)
        }
    }

    private class FillTarget(val offsetFrames: Long, val generation: Long)

    /**
     * 选下一个要填的起点：seek 目标 > 上次写游标 > 环内第一个缺口；null = 无需再填。
     *
     * 与 [generation] 一起原子取出：若此后又有新 seek，本次会话会立即被放弃。
     */
    private fun nextFillTarget(): FillTarget? = synchronized(lock) {
        val gen = generation
        val pending = pendingOffsetFrames
        pendingOffsetFrames = -1L
        if (pending >= 0L && !ring.isRangeFilled(pending, 1L)) {
            return@synchronized FillTarget(pending, gen)
        }
        val cursor = writeOffsetFrames
        if (cursor >= 0L && !ring.isRangeFilled(cursor, 1L)) {
            return@synchronized FillTarget(cursor, gen)
        }
        val gap = ring.firstUnfilledFrame()
        if (gap >= 0L) return@synchronized FillTarget(gap, gen)
        null
    }

    /** 从 [startOffset] 开始把连续缺口填满；遇到新的 seek 目标会立即放弃本次会话。 */
    private fun fillGap(startOffset: Long, myGeneration: Long) {
        val gapFrames = ring.distanceToFilledFrame(startOffset)
        if (gapFrames <= 0L) return
        sessionCount += 1L
        val sessionStartedMs = System.currentTimeMillis()
        val startAbsFrame = timeline.aFrame + startOffset
        val endAbsFrame = minOf(startAbsFrame + gapFrames, timeline.bFrame)
        val frames = endAbsFrame - startAbsFrame
        if (frames <= 0L) return
        val startUs = if (startOffset == 0L) timeline.loopStartUs else timeline.frameUs(startAbsFrame)
        val endUs = if (endAbsFrame >= timeline.bFrame) {
            timeline.loopEndUs
        } else {
            timeline.frameUs(endAbsFrame)
        }
        var writeOffset = startOffset
        var written = 0L
        var nextLogAt = 1L
        RingBandLog.i(
            "RingBand fill start session=" + sessionCount +
                " offset=" + startOffset + " frames=" + frames +
                " generation=" + myGeneration + " covered=" + ring.coveredFrames,
        )
        decoder.stream(startUs, endUs, PREROLL_US) { data, size ->
            if (releaseRequested) return@stream
            if (generation != myGeneration) {
                // 新目标已到：让解码器尽快返回，渲染线程下一轮直接从新 offset 填。
                decoder.cancel()
                return@stream
            }
            val chunkFrames = size / timeline.bytesPerFrame
            if (chunkFrames <= 0) return@stream
            var srcBytes = 0
            var left = chunkFrames.toLong()
            while (left > 0L && written < frames) {
                val allowed = minOf(left, frames - written).toInt()
                val wrote = ring.writeFramesAt(writeOffset, data, srcBytes)
                if (wrote <= 0) break
                writeOffset = Math.floorMod(writeOffset + wrote, timeline.loopFrames)
                writeOffsetFrames = writeOffset
                srcBytes += wrote * timeline.bytesPerFrame
                left -= wrote.toLong()
                written += wrote.toLong()
                val block = (writeOffset / timeline.sampleRate).toInt()
                if (block >= nextLogAt) {
                    nextLogAt = block + 1L
                    RingBandLog.i(
                        "RingBand fill block=" + block +
                            " offset=" + writeOffset +
                            " covered=" + ring.coveredFrames +
                            " filled=" + ring.renderedFrames,
                    )
                }
            }
        }
        // 00024：会话结束后再判断缺口——"产出 0 帧"本身不是错误，得看源是否已到尾部。
        val reachedSourceEnd = decoder.lastStreamReachedEndOfStream()
        lastFillReachedEndOfStream = reachedSourceEnd
        val missing = frames - written
        val silenceBefore = ring.tailTrimmedFrames
        if (missing > 0L && !releaseRequested && generation == myGeneration) {
            val subGranularity = frames < decodeGranularityFrames ||
                missing < decodeGranularityFrames
            when {
                // (a) 已到源尾：B 超出文件实际音频内容（整曲窗口最常见），剩余帧物理上不存在。
                reachedSourceEnd -> {
                    writeOffset = fillSilenceTail(writeOffset, missing, "source-end")
                    emptyFillAttempts = 0
                }
                // (b) 缺口小于一个解码粒度：seek 落点无法保证覆盖这几帧，静音补齐后继续播放。
                subGranularity -> {
                    writeOffset = fillSilenceTail(writeOffset, missing, "sub-granularity")
                    emptyFillAttempts = 0
                }
                // (c) 远离源尾的"持续 0 帧"才是真解码故障（00024）。
                written == 0L -> {
                    emptyFillAttempts += 1
                    if (emptyFillAttempts >= MAX_EMPTY_FILL_ATTEMPTS) {
                        error = "decoder produced no frames offset=" + startOffset +
                            " frames=" + frames +
                            " attempts=" + emptyFillAttempts +
                            " eos=" + reachedSourceEnd +
                            " covered=" + ring.coveredFrames
                    }
                }
                else -> emptyFillAttempts = 0
            }
        } else if (missing <= 0L) {
            emptyFillAttempts = 0
        }
        RingBandLog.i(
            "RingBand fill end session=" + sessionCount +
                " ms=" + (System.currentTimeMillis() - sessionStartedMs) +
                " offset=" + writeOffset + " written=" + written +
                " missing=" + (missing.coerceAtLeast(0L)) +
                " silence=" + (ring.tailTrimmedFrames - silenceBefore) +
                " eos=" + reachedSourceEnd +
                " generation=" + myGeneration + " covered=" + ring.coveredFrames +
                " trimmed=" + ring.tailTrimmedFrames +
                // 小窗口取证：填满整环即 DONE，回绕不再需要解码。
                " fullyCovered=" + ring.isFullyCovered,
        )
    }

    /**
     * 把不足的 [count] 帧按静音补进环带（尾部短于预期）。
     *
     * 覆盖位图照常推进到窗口末尾：读取端不会卡在缺口上，播放能正常结束/回绕；
     * 但 [LoopRing.tailTrimmedFrames] / [LoopRing.audioCoveredFrames] 会如实记录这段是
     * 补齐的静音，而不是真实音频。
     *
     * @return 补齐后的环内写游标。
     */
    private fun fillSilenceTail(writeCursor: Long, count: Long, reason: String): Long {
        var offset = writeCursor
        var left = count
        var filled = 0L
        while (left > 0L && !releaseRequested) {
            val wrote = ring.fillSilenceAt(offset, left)
            if (wrote <= 0) break
            offset = Math.floorMod(offset + wrote, timeline.loopFrames)
            left -= wrote.toLong()
            filled += wrote.toLong()
            writeOffsetFrames = offset
        }
        if (filled > 0L) {
            RingBandLog.w(
                "RingBand fill silence offset=" + writeCursor + " frames=" + filled +
                    " reason=" + reason +
                    " covered=" + ring.coveredFrames +
                    " trimmed=" + ring.tailTrimmedFrames +
                    " audioCovered=" + ring.audioCoveredFrames,
            )
        }
        return offset
    }

    private companion object {
        const val PREROLL_US = 300_000L
        const val IDLE_WAIT_MS = 500L
        const val RELEASE_WAIT_MS = 500L

        /** 一个解码粒度（帧）：AAC 1024 / MP3 1152，取 2048 帧兜住。 */
        const val MIN_DECODE_GRANULARITY_FRAMES = 2_048L

        /** 解码粒度的时长下限（毫秒），避免低采样率下把可解码的缺口当静音。 */
        const val DECODE_GRANULARITY_MS = 20L

        /** 远离源尾时允许的连续 0 帧会话数；超过才判 DECODE_EXCEPTION。 */
        const val MAX_EMPTY_FILL_ATTEMPTS = 3
    }
}
