package com.zjr.hesimusic.loop.audio.source

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import com.zjr.hesimusic.core.invariants.LoopInvariantProbes
import com.zjr.hesimusic.loop.LoopPhase
import java.io.IOException

/**
 * 环带播放的 Extractor：read 从 RingBandReader 取模；seek 只改 offset。
 *
 * issue 00028：主播放器使用**整曲时间轴**（[LoopingPcmTimeline.wholeTimeline]）：
 * - T<A 读前奏块（[PcmRangeBlockReader]，按需解码）；
 * - A≤T<TAIL_BASE 读环带，B 处由 reader 取模折返；
 * - T≥TAIL_BASE 读尾奏块，播到曲末在源内回绕到下一个 epoch 的 prelude 0（T 连续单调，
 *   不发 EOS / 不依赖 repeat=ONE；display 由 [LoopWholeTimeline] 映射回 0）。
 *
 * 折返开关是**源内状态**：phase 由 [positionUs] 判定，seek 到 >B 改为 tail（不折返），
 * seek 回 ≤B 恢复 loop（折返）。它不依赖 UI。
 */
@UnstableApi
class RingBandExtractor(
    private val timeline: LoopingPcmTimeline,
    private val ring: LoopRing,
    private val reader: RingBandReader,
    private val renderer: RingBandRenderer,
    private val logger: (String) -> Unit = {},
    private val onSeamOutputFrames: ((Long) -> Unit)? = null,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
    private val preludeReader: PcmRangeBlockReader? = null,
    private val tailReader: PcmRangeBlockReader? = null,
) : Extractor {

    private var trackOutput: TrackOutput? = null
    private var samples = 0L
    private var lastLogMs = 0L
    private var lastSeam = -1L
    private var firstReadLogged = false
    private var lastWaitLogMs = 0L
    private var lastHeapLogMs = 0L
    private var lastThrottleLogMs = 0L
    private var activeElapsedMs = 0L
    private var lastActiveClockMs = -1L

    /** 上一次 read 因"源没数据"返回 null 的时刻；用于量曲末冻结（尾块解码/补齐）。 */
    private var noDataSinceMs = -1L

    /** 当前数据源时间轴位置（下一帧的 T，µs）；整曲口径下驱动阶段选择。 */
    @Volatile
    private var positionUs = 0L

    /** 源内已输出帧数（含前奏/尾奏）；节流预算用它而不是环带 reader。 */
    private var emittedFrames = 0L

    /** 环带 reader 是否已按 positionUs 对齐（seek / 前奏→循环切换后置 false）。 */
    private var loopReaderSynced = false

    // ---- 00028(#105) 短窗口接缝取证 -------------------------------------------------
    /** B→A 回绕次数（每次 seek 重新计数）。 */
    @Volatile
    var seamCount: Long = 0L
        private set

    /** 连续两次"成功输出样本"之间最大的墙钟间隔（ms）：节流/等待/seek 后的首帧不计入。 */
    @Volatile
    var maxEmitGapMs: Long = 0L
        private set

    /** 第一次回绕的时刻（注入时钟，ms）；-1 = 还没回绕过。 */
    @Volatile
    var firstSeamAtMs: Long = -1L
        private set

    /** 最近一次回绕的时刻；-1 = 还没回绕过。 */
    @Volatile
    var lastSeamAtMs: Long = -1L
        private set

    /** 相邻两次回绕间隔的最大/最小值（ms）；min 为 0 = 样本不足。 */
    @Volatile
    var maxSeamIntervalMs: Long = 0L
        private set

    @Volatile
    var minSeamIntervalMs: Long = 0L
        private set

    /** 上一次成功输出的时刻；<0 = 本次输出是"重新开始"（节流/等待/seek 之后）。 */
    private var lastEmitClockMs = -1L

    /** seek 时的已输出帧数基准：seek 后允许重新领先 [MAX_AHEAD_SECONDS]（00023）。 */
    private var emittedBaseFrames = 0L
    private var queueGuard = false
    private var readsAtLastRateLog = 0L
    private var lastRateLogMs = 0L
    private val maxAheadFrames = timeline.sampleRate * MAX_AHEAD_SECONDS
    @Volatile
    private var released = false
    private val bandBytes = timeline.loopFrames * timeline.bytesPerFrame

    private val format: Format = Format.Builder()
        .setId("ring-band")
        .setSampleMimeType(MimeTypes.AUDIO_RAW)
        .setPcmEncoding(C.ENCODING_PCM_16BIT)
        .setSampleRate(timeline.sampleRate)
        .setChannelCount(timeline.channels)
        .setMaxInputSize(READ_CHUNK_BYTES)
        .build()

    private val seekMap = object : SeekMap {
        override fun isSeekable(): Boolean = true
        override fun getDurationUs(): Long = C.TIME_UNSET
        override fun getSeekPoints(positionUs: Long): SeekMap.SeekPoints {
            val p = positionUs.coerceAtLeast(0L)
            return SeekMap.SeekPoints(SeekPoint(p, p))
        }
    }

    /**
     * 保险:单候选 extractor 时 media3 直接采用(BundledExtractorsAdapter 不调 sniff),
     * 这里返回 true 只覆盖被显式 sniff 的路径,不代表"sniff=true 才会被采用"。
     */
    override fun sniff(input: ExtractorInput): Boolean = true

    override fun init(output: ExtractorOutput) {
        LoopInvariantProbes.onExtractorInit()
        trackOutput = output.track(0, C.TRACK_TYPE_AUDIO).also { it.format(format) }
        LoopInvariantProbes.onExtractorTrackCreated()
        output.seekMap(seekMap)
        LoopInvariantProbes.onExtractorSeekMap()
        output.endTracks()
        LoopInvariantProbes.onExtractorEndTracks()
        logger(
            "RingBand extractor init wholeTimeline=" + timeline.wholeTimeline +
                " preludeFrames=" + timeline.aFrame +
                " tailFrames=" + timeline.tailFrames,
        )
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        // 任何"本次没吐样本"的返回都必须打断 emit 间隔测量：否则暂停/节流时间会被算成停顿。
        if (LoopSourcePauseGate.paused) {
            lastEmitClockMs = -1L
            return Extractor.RESULT_CONTINUE
        }
        renderer.error?.let { throw IOException("RingBand render failed: " + it) }
        val track = trackOutput ?: run {
            lastEmitClockMs = -1L
            return Extractor.RESULT_CONTINUE
        }
        // 关键：不能无限领先播放头。这里用「加载活跃时间」做墙钟节流，
        // 最多领先 5s；暂停/长时间不读时不计入 activeElapsed，避免恢复后暴冲。
        val now = clock()
        val allowed = allowedFrames(now)
        if (emittedFrames >= allowed) {
            queueGuard = true
            reader.recordWait()
            if (now - lastThrottleLogMs >= 5_000L) {
                lastThrottleLogMs = now
                RingBandLog.w(
                    "RingBand read throttle emitted=" + emittedFrames +
                        " allowed=" + allowed +
                        " phase=" + currentPhase().wire +
                        " queueGuard=true",
                )
            }
            try {
                sleeper(THROTTLE_SLEEP_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            lastEmitClockMs = -1L
            return Extractor.RESULT_CONTINUE
        }
        val budget = (allowed - emittedFrames).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        // 不要把预算切成 176 帧这种碎样本；预算不足最小样本时直接节流。
        if (budget < MIN_SAMPLE_FRAMES) {
            queueGuard = true
            reader.recordWait()
            if (now - lastThrottleLogMs >= 5_000L) {
                lastThrottleLogMs = now
                RingBandLog.w(
                    "RingBand read throttle emitted=" + emittedFrames +
                        " budget=" + budget +
                        " minSample=" + MIN_SAMPLE_FRAMES +
                        " phase=" + currentPhase().wire +
                        " queueGuard=true",
                )
            }
            try {
                sleeper(THROTTLE_SLEEP_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            lastEmitClockMs = -1L
            return Extractor.RESULT_CONTINUE
        }
        val request = minOf(READ_CHUNK_BYTES / timeline.bytesPerFrame, budget)
        queueGuard = false
        var result = readNext(request)
        if (result == null) {
            if (noDataSinceMs < 0L) noDataSinceMs = clock()
            lastEmitClockMs = -1L
            return Extractor.RESULT_CONTINUE
        }
        val data = ParsableByteArray(result.data)
        track.sampleData(data, result.data.size)
        track.sampleMetadata(result.ptsUs, C.BUFFER_FLAG_KEY_FRAME, result.data.size, 0, null)
        samples += 1L
        // 00028(#105)：回绕取证。间隔按注入时钟算，节流/等待后的首帧已重置基线，
        // 所以 sincePrevSeamMs 反映的是"两轮循环真实耗时"（0.5s 窗口应≈500ms）。
        if (lastEmitClockMs >= 0L) {
            val gap = (now - lastEmitClockMs).coerceAtLeast(0L)
            if (gap > maxEmitGapMs) maxEmitGapMs = gap
        }
        lastEmitClockMs = now
        reader.lastSeamOutputFrames.takeIf { it >= 0L && it != lastSeam }?.let { seam ->
            lastSeam = seam
            seamCount += 1L
            val previousSeamMs = lastSeamAtMs
            if (firstSeamAtMs < 0L) firstSeamAtMs = now
            lastSeamAtMs = now
            var sincePrevious = -1L
            if (previousSeamMs >= 0L) {
                sincePrevious = (now - previousSeamMs).coerceAtLeast(0L)
                if (sincePrevious > maxSeamIntervalMs) maxSeamIntervalMs = sincePrevious
                if (minSeamIntervalMs <= 0L || sincePrevious < minSeamIntervalMs) {
                    minSeamIntervalMs = sincePrevious
                }
            }
            RingBandLog.i(
                "RingBand seam idx=" + seamCount +
                    " outFrames=" + seam +
                    " tMs=" + now +
                    " sincePrevSeamMs=" + sincePrevious +
                    " maxEmitGapMs=" + maxEmitGapMs +
                    " sessions=" + renderer.sessionCount +
                    " covered=" + ring.coveredFrames + "/" + timeline.loopFrames +
                    " offset=" + reader.offsetFrames +
                    " reads=" + reader.reads,
            )
            logger("RingBand seam outFrames=" + seam)
            onSeamOutputFrames?.invoke(seam)
        }
        val nowForLog = System.currentTimeMillis()
        if (nowForLog - lastLogMs >= 5_000L) {
            lastLogMs = nowForLog
            logger(
                "RingBand durationMs=" + (timeline.loopFrames * 1000L / timeline.sampleRate) +
                    " bandFrames=" + timeline.loopFrames +
                    " filledFrames=" + ring.renderedFrames +
                    " offset=" + reader.offsetFrames +
                    " reads=" + reader.reads +
                    " underruns=" + reader.underruns +
                    " phase=" + currentPhase().wire +
                    " positionUs=" + positionUs,
            )
        }
        if (!firstReadLogged) {
            firstReadLogged = true
            RingBandLog.i(
                "RingBand firstRead filledFrames=" + ring.renderedFrames +
                    " offset=" + result.offsetFrames +
                    " frames=" + result.frames +
                    " phase=" + currentPhase().wire +
                    " positionUs=" + positionUs,
            )
        }
        val now2 = System.currentTimeMillis()
        if (now2 - lastLogMs >= 1_000L) {
            lastLogMs = now2
            val activeSec = activeElapsedMs / 1000.0
            val emittedSec = emittedFrames.toDouble() / timeline.sampleRate
            val rateWindowMs = (now2 - lastRateLogMs).coerceAtLeast(1L)
            val readsPerSecond = if (lastRateLogMs == 0L) 0L else
                (reader.reads - readsAtLastRateLog) * 1000L / rateWindowMs
            readsAtLastRateLog = reader.reads
            lastRateLogMs = now2
            RingBandLog.i(
                "RingBand read offset=" + reader.offsetFrames +
                    " filled=" + ring.renderedFrames +
                    " emitted=" + emittedFrames +
                    " aheadOfRealtime=" + String.format("%.2f", emittedSec - activeSec) +
                    "s queueGuard=" + queueGuard +
                    " readsPerSecond=" + readsPerSecond +
                    " underruns=" + reader.underruns +
                    " reads=" + reader.reads +
                    " phase=" + currentPhase().wire +
                    " positionUs=" + positionUs +
                    // 短窗口接缝：seam 次数/首末时刻/环回间隔/两次输出最大间隔。
                    " seams=" + seamCount +
                    " firstSeamMs=" + firstSeamAtMs +
                    " seamIntervalMinMs=" + minSeamIntervalMs +
                    " seamIntervalMaxMs=" + maxSeamIntervalMs +
                    " maxEmitGapMs=" + maxEmitGapMs +
                    " sessions=" + renderer.sessionCount,
            )
            logHeapIfDue(now2)
        }
        if (samples == 1L || samples % 100L == 0L) {
            logger("RingBand read n=" + samples + " frames=" + result.frames)
        }
        noDataSinceMs = -1L
        return Extractor.RESULT_CONTINUE
    }

    private class Next(
        val data: ByteArray,
        val frames: Int,
        val ptsUs: Long,
        val offsetFrames: Long,
    )

    private fun readNext(request: Int): Next? {
        if (!timeline.wholeTimeline) return readRing(request)
        return when (currentPhase()) {
            LoopPhase.PRELUDE -> readPrelude(request)
            LoopPhase.LOOP -> readRing(request)
            LoopPhase.TAIL -> readTail(request)
        }
    }

    /** 前奏：按需块读取；到 A 之前 1~2 秒提前请求环带跳到 A。 */
    private fun readPrelude(request: Int): Next? {
        val prelude = preludeReader ?: return null
        // 新 epoch / 从 tail 回绕进来时环带 reader 还停在上一轮，到 A 时必须重新对齐。
        loopReaderSynced = false
        val frame = timeline.frameAtTimeline(positionUs)
        if (frame >= timeline.aFrame || timeline.localTimelineUs(positionUs) >= timeline.loopStartUs) {
            // 用 epoch 内偏移比较，不能用绝对 T（tail 回绕后的 epochBase 远大于 A）。
            positionUs = timeline.epochStartUs(positionUs) + timeline.frameUs(timeline.aFrame)
            return readRing(request)
        }
        val epochBase = timeline.epochStartUs(positionUs)
        // 00029：帧号换算有 ±1 帧的 floor/ceil 误差。旧代码用严格不等判断，尾奏（B 不对齐
        // 整帧）每 0.5~1s 就会“看到 currentFrame 比目标早/晚 1 帧”并 seek 回去，常驻解码
        // 会话因此每块前导 1 帧，30s 累计漂移几十帧（听感是极小的周期性跳变）。差 1 帧内不 seek。
        if (!frameNear(prelude.currentFrame(), frame)) prelude.seekToFrame(frame)
        if (epochBase + timeline.frameUs(timeline.aFrame) - positionUs <= PRELUDE_TO_LOOP_PREROLL_US) {
            // 交界预取：确保 A 处的环带块已经在内存里，避免 prelude→loop 的可听缝隙。
            renderer.requestFillAt(0L)
        }
        val maxFrames = minOf(request.toLong(), timeline.aFrame - frame).toInt()
        val result = prelude.read(maxFrames) ?: run {
            prelude.kick()
            prelude.awaitCurrentReady(READ_WAIT_MS)
            prelude.read(maxFrames)
        } ?: return null
        // PTS 用**绝对时间轴 T**（epochBase + 本地帧时间），保证 tail 回绕到新 epoch 时
        // PTS 继续单调递增，不会出现"PTS 从 sentinel 跳回 0"的倒退。
        val pts = epochBase + timeline.frameUs(result.startFrame)
        positionUs = epochBase + timeline.frameUs(result.startFrame + result.frames)
        emittedFrames += result.frames.toLong()
        return Next(result.data, result.frames, pts, Long.MIN_VALUE)
    }

    /** 环带：B→A 由 RingBandReader 取模；切进来时按 positionUs 对齐 offset。 */
    private fun readRing(request: Int): Next? {
        if (!loopReaderSynced) {
            val frame = timeline.frameAtTimeline(positionUs)
            val offset = Math.floorMod(frame - timeline.aFrame, timeline.loopFrames)
            reader.seekToOffset(offset, positionUs)
            renderer.requestFillAt(offset)
            loopReaderSynced = true
        }
        var result = reader.read(request)
        if (result == null) {
            // media3 会立刻重试；这里只等**当前读取位置**被跳填/续填补齐，不能空转。
            reader.recordWait()
            if (reader.awaitFilled(reader.offsetFrames, 1L, READ_WAIT_MS)) {
                result = reader.read(request)
            }
            if (result == null) {
                val now = System.currentTimeMillis()
                if (now - lastWaitLogMs >= 5_000L) {
                    lastWaitLogMs = now
                    RingBandLog.w(
                        "RingBand read wait filled=" + ring.renderedFrames +
                            " covered=" + ring.coveredFrames +
                            " offset=" + reader.offsetFrames +
                            " phase=" + currentPhase().wire,
                    )
                }
                return null
            }
        }
        positionUs = result.ptsUs + framesToUs(result.frames.toLong())
        emittedFrames += result.frames.toLong()
        return Next(result.data, result.frames, result.ptsUs, result.offsetFrames)
    }

    /** 尾奏：按需块读取；到曲末时在源内回绕到下一个 epoch 的 prelude 0（不发 EOS）。 */
    private fun readTail(request: Int): Next? {
        val tail = tailReader ?: return null
        val frame = timeline.frameAtTimeline(positionUs)
        if (frame >= timeline.durationFrames) {
            // 已经站在曲末（例如 seek 到 duration）：直接回绕到下一个 epoch 的 0。
            val frozenMs = freezeMsNow()
            positionUs = timeline.nextEpochStartUs(positionUs)
            loopReaderSynced = false
            logTailWrap("already-at-end", frozenMs)
            return readPrelude(request)
        }
        if (!frameNear(tail.currentFrame(), frame)) tail.seekToFrame(frame)
        val maxFrames = minOf(request.toLong(), timeline.durationFrames - frame).toInt()
        val result = tail.read(maxFrames) ?: run {
            tail.kick()
            tail.awaitCurrentReady(READ_WAIT_MS)
            tail.read(maxFrames)
        } ?: return null
        // tail 的 PTS 是**时间轴 T（sentinel 带内）**，不是媒体时间：播放器位置必须能投影回
        // 展示位置；媒体帧只用于从块缓存取 PCM。
        val pts = positionUs
        positionUs += framesToUs(result.frames.toLong())
        emittedFrames += result.frames.toLong()
        val atEnd = result.startFrame + result.frames >= timeline.durationFrames
        if (atEnd) {
            // 源内回绕：T 连续推进到下一个 epoch 起点（display 回到 0），不依赖 EOS/repeat，
            // 因此不会出现 buffering/播放失败，也不会让 claim 掉成 false。
            val frozenMs = freezeMsNow()
            positionUs = timeline.nextEpochStartUs(positionUs)
            loopReaderSynced = false
            logTailWrap("last-frame", frozenMs)
        }
        return Next(result.data, result.frames, pts, Long.MIN_VALUE)
    }

    private fun currentPhase(): LoopPhase =
        if (!timeline.wholeTimeline) LoopPhase.LOOP
        else timeline.phaseAt(positionUs)

    private fun allowedFrames(now: Long): Long {
        if (lastActiveClockMs < 0L) {
            lastActiveClockMs = now
        } else {
            val delta = now - lastActiveClockMs
            lastActiveClockMs = now
            if (delta in 0L..200L) activeElapsedMs += delta
        }
        return emittedBaseFrames + maxAheadFrames + activeElapsedMs * timeline.sampleRate / 1000L
    }

    private fun framesToUs(frames: Long): Long = frames * 1_000_000L / timeline.sampleRate

    /** 数据源时间轴映射出的帧号允许 ±1 帧误差（floor/ceil 方向不同），不要因此回 seek。 */
    private fun frameNear(current: Long, target: Long): Boolean =
        current == target || Math.abs(current - target) <= 1L

    /** 自上次 read 返回"源没数据"以来的毫秒数（曲末冻结观测）。 */
    private fun freezeMsNow(): Long =
        if (noDataSinceMs < 0L) 0L else (clock() - noDataSinceMs).coerceAtLeast(0L)

    /**
     * 回绕瞬间的现场日志：display/timeline/local 三个值 + 冻结毫秒。
     *
     * - positionUs 是回绕后的新 epoch 起点（display 应为 0）；
     * - localUs 是 epoch 内偏移（回绕后≈0）；
     * - epochStartUs 是新 epoch 的绝对起点。
     */
    private fun logTailWrap(reason: String, frozenMs: Long) {
        val window = com.zjr.hesimusic.loop.LoopWindow(
            timeline.songId,
            timeline.loopStartUs,
            timeline.loopEndUs,
        )
        val displayUs = LoopWholeTimeline.displayUs(positionUs, window, timeline.fullDurationUs)
        val localUs = timeline.localTimelineUs(positionUs)
        val epochStartUs = timeline.epochStartUs(positionUs)
        RingBandLog.i(
            "RingBand tail wrap reason=" + reason +
                " timelineUs=" + positionUs +
                " localUs=" + localUs +
                " epochStartUs=" + epochStartUs +
                " displayUs=" + displayUs +
                " frozenMs=" + frozenMs +
                " durationUs=" + timeline.fullDurationUs,
        )
    }

    /**
     * seek 意图预热（issue 00029）：在 media3 真正 seek 之前，把目标 timeline 位置
     * 对应的 prelude/tail 块先解掉。环带 seek 则提前触发对应的跳填。
     *
     * 只读/只填缓存，不改 positionUs/cursor；真正的 seek 仍由 [seek] 完成。
     */
    fun prewarmTimelineUs(timelineUs: Long) {
        if (!timeline.wholeTimeline) {
            val frame = timeline.frameAtTimeline(timelineUs)
            val offset = Math.floorMod(frame - timeline.aFrame, timeline.loopFrames)
            renderer.requestFillAt(offset)
            return
        }
        val local = timeline.localTimelineUs(timelineUs)
        when {
            local < timeline.loopStartUs -> {
                preludeReader?.prewarmFrame(timeline.frameAtTimeline(timelineUs))
            }
            local >= com.zjr.hesimusic.loop.audio.source.LoopWholeTimeline.TAIL_BASE_US -> {
                tailReader?.prewarmFrame(timeline.frameAtTimeline(timelineUs))
            }
            else -> {
                val offset = Math.floorMod(
                    timeline.frameAtTimeline(timelineUs) - timeline.aFrame,
                    timeline.loopFrames,
                )
                renderer.requestFillAt(offset)
            }
        }
    }

    override fun seek(position: Long, timeUs: Long) {
        // 00023 后续的分段计时：请求 → media3 真正调到这里（loading 线程）。
        LoopPreviewDebugBridge.seekStages.noteExtractorSeek()
        // 每次进入/换窗口重新统计接缝：第一次回绕 vs 之后每次的停顿才有对比意义。
        seamCount = 0L
        firstSeamAtMs = -1L
        lastSeamAtMs = -1L
        maxSeamIntervalMs = 0L
        minSeamIntervalMs = 0L
        maxEmitGapMs = 0L
        lastEmitClockMs = -1L
        lastSeam = -1L
        val t = timeUs.coerceAtLeast(0L)
        positionUs = t
        if (!timeline.wholeTimeline) {
            val offset = Math.floorMod(
                timeline.frameAtTimeline(t) - timeline.aFrame,
                timeline.loopFrames,
            )
            reader.seekToOffset(offset, t)
            renderer.requestFillAt(offset)
            loopReaderSynced = true
        } else {
            when (currentPhase()) {
                LoopPhase.PRELUDE -> {
                    preludeReader?.seekToFrame(timeline.frameAtTimeline(t))
                    // 进前奏时只把环带读指针指向 A；环带填充等离 A 还有 2s
                    // （readPrelude）再启动，避免前奏期间环带全速解码抢 CPU。
                    reader.seekToOffset(0L, timeline.loopStartUs)
                    if (timeline.frameUs(timeline.aFrame) -
                        timeline.localTimelineUs(t) <= PRELUDE_TO_LOOP_PREROLL_US
                    ) {
                        renderer.requestFillAt(0L)
                    }
                    loopReaderSynced = false
                }
                LoopPhase.LOOP -> {
                    val offset = Math.floorMod(
                        timeline.frameAtTimeline(t) - timeline.aFrame,
                        timeline.loopFrames,
                    )
                    reader.seekToOffset(offset, t)
                    renderer.requestFillAt(offset)
                    loopReaderSynced = true
                }
                LoopPhase.TAIL -> {
                    tailReader?.seekToFrame(timeline.frameAtTimeline(t))
                    loopReaderSynced = false
                }
            }
        }
        // 00023：seek 后重新给满 maxAhead 的发射预算，否则墙钟节流会按实时速度喂数据，
        // media3 要等 1.5s 才凑够起播缓冲（表现为 seek 慢 1~2 秒）。
        emittedBaseFrames = emittedFrames
        activeElapsedMs = 0L
        lastActiveClockMs = clock()
        // 00022：让渲染线程立刻跳到目标 offset 开始解码，而不是从 A 顺序追上来。
        if (!timeline.wholeTimeline) {
            renderer.requestFillAt(
                Math.floorMod(timeline.frameAtTimeline(t) - timeline.aFrame, timeline.loopFrames),
            )
        }
        // 真机计时探针：从 seek 请求到第一个 PCM buffer 进入 AudioSink（LoopPerf 日志）。
        LoopPreviewDebugBridge.noteSeekRequest(timeUs / 1000L)
        logger(
            "RingBand seek position=" + position + " timeUs=" + timeUs +
                " phase=" + currentPhase().wire +
                " positionUs=" + positionUs,
        )
    }

    override fun release() {
        if (released) return
        released = true
        preludeReader?.release()
        tailReader?.release()
        renderer.release()
        ring.close()
        RingBandLog.i(
            "RingBand release bandBytes=" + bandBytes +
                " preludeFrames=" + timeline.aFrame +
                " tailFrames=" + timeline.tailFrames +
                " released=true",
        )
        logHeapIfDue(System.currentTimeMillis(), force = true)
    }

    private fun logHeapIfDue(now: Long, force: Boolean = false) {
        if (!force && now - lastHeapLogMs < 5_000L) return
        lastHeapLogMs = now
        val rt = Runtime.getRuntime()
        RingBandLog.i(
            "RingBand heap usedHeap=" + (rt.totalMemory() - rt.freeMemory()) +
                " maxHeap=" + rt.maxMemory() +
                " bandBytes=" + bandBytes,
        )
    }

    private companion object {
        const val READ_CHUNK_BYTES = 64 * 1024
        const val READ_WAIT_MS = 8L
        const val THROTTLE_SLEEP_MS = 4L
        const val MAX_AHEAD_SECONDS = 5L
        const val MIN_SAMPLE_FRAMES = 1024

        /** 前奏离 A 还有该时长时，提前要求环带跳到 A 填写（交界无缝）。 */
        const val PRELUDE_TO_LOOP_PREROLL_US = 2_000_000L
    }
}
