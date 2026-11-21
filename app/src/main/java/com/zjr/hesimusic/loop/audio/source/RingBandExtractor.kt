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
import java.io.IOException

/** 环带播放的 Extractor：read 从 RingBandReader 取模；seek 只改 offset。 */
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
        logger("RingBand extractor init")
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
        val emitted = reader.totalReadFrames
        if (emitted >= allowed) {
            queueGuard = true
            reader.recordWait()
            if (now - lastThrottleLogMs >= 5_000L) {
                lastThrottleLogMs = now
                RingBandLog.w(
                    "RingBand read throttle emitted=" + emitted +
                        " allowed=" + allowed +
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
        val budget = (allowed - emitted).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        // 不要把预算切成 176 帧这种碎样本；预算不足最小样本时直接节流。
        if (budget < MIN_SAMPLE_FRAMES) {
            queueGuard = true
            reader.recordWait()
            if (now - lastThrottleLogMs >= 5_000L) {
                lastThrottleLogMs = now
                RingBandLog.w(
                    "RingBand read throttle emitted=" + emitted +
                        " budget=" + budget +
                        " minSample=" + MIN_SAMPLE_FRAMES +
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
        var result = reader.read(request)
        if (result == null) {
            // media3 会立刻重试；这里只等**当前读取位置**被跳填/续填补齐，不能空转。
            // （00022 之前的实现等的是 ring 高水位，等于逼解码从 A 顺序追到环尾。）
            if (awaitFilled(reader.offsetFrames)) {
                result = reader.read(request)
            }
            if (result == null) {
                lastEmitClockMs = -1L
                return Extractor.RESULT_CONTINUE
            }
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
                    " underruns=" + reader.underruns,
            )
        }
        if (!firstReadLogged) {
            firstReadLogged = true
            RingBandLog.i(
                "RingBand firstRead filledFrames=" + ring.renderedFrames +
                    " offset=" + result.offsetFrames +
                    " frames=" + result.frames,
            )
        }
        val now2 = System.currentTimeMillis()
        if (now2 - lastLogMs >= 1_000L) {
            lastLogMs = now2
            val activeSec = activeElapsedMs / 1000.0
            val emittedSec = reader.totalReadFrames.toDouble() / timeline.sampleRate
            val rateWindowMs = (now2 - lastRateLogMs).coerceAtLeast(1L)
            val readsPerSecond = if (lastRateLogMs == 0L) 0L else
                (reader.reads - readsAtLastRateLog) * 1000L / rateWindowMs
            readsAtLastRateLog = reader.reads
            lastRateLogMs = now2
            RingBandLog.i(
                "RingBand read offset=" + reader.offsetFrames +
                    " filled=" + ring.renderedFrames +
                    " emitted=" + reader.totalReadFrames +
                    " aheadOfRealtime=" + String.format("%.2f", emittedSec - activeSec) +
                    "s queueGuard=" + queueGuard +
                    " readsPerSecond=" + readsPerSecond +
                    " underruns=" + reader.underruns +
                    " reads=" + reader.reads +
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
        return Extractor.RESULT_CONTINUE
    }

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

    /** 等环内 [offsetFrames] 这一帧可读（跳填写入会立即唤醒）。 */
    private fun awaitFilled(offsetFrames: Long): Boolean {
        reader.recordWait()
        val ready = reader.awaitFilled(offsetFrames, 1L, READ_WAIT_MS)
        logHeapIfDue(System.currentTimeMillis())
        if (!ready) {
            val now = System.currentTimeMillis()
            if (now - lastWaitLogMs >= 5_000L) {
                lastWaitLogMs = now
                RingBandLog.w(
                    "RingBand read wait filled=" + ring.renderedFrames +
                        " covered=" + ring.coveredFrames +
                        " offset=" + reader.offsetFrames,
                )
            }
        }
        return ready
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
        val offset = Math.floorMod(
            timeline.frameAtTimeline(timeUs) - timeline.aFrame,
            timeline.loopFrames,
        )
        reader.seekToOffset(offset, timeUs)
        // 00023：seek 后重新给满 maxAhead 的发射预算，否则墙钟节流会按实时速度喂数据，
        // media3 要等 1.5s 才凑够起播缓冲（表现为 seek 慢 1~2 秒）。
        emittedBaseFrames = reader.totalReadFrames
        activeElapsedMs = 0L
        lastActiveClockMs = clock()
        // 00022：让渲染线程立刻跳到目标 offset 开始解码，而不是从 A 顺序追上来。
        renderer.requestFillAt(offset)
        // 真机计时探针：从 seek 请求到第一个 PCM buffer 进入 AudioSink（LoopPerf 日志）。
        LoopPreviewDebugBridge.noteSeekRequest(timeUs / 1000L)
        logger(
            "RingBand seek position=" + position + " timeUs=" + timeUs +
                " offsetFrames=" + offset,
        )
    }

    override fun release() {
        if (released) return
        released = true
        renderer.release()
        ring.close()
        RingBandLog.i("RingBand release bandBytes=" + bandBytes + " released=true")
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
    }
}
