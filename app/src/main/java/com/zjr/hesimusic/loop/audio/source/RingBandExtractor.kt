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
        if (LoopSourcePauseGate.paused) return Extractor.RESULT_CONTINUE
        renderer.error?.let { throw IOException("RingBand render failed: " + it) }
        val track = trackOutput ?: return Extractor.RESULT_CONTINUE
        val minFrames = minOf(timeline.loopFrames, 2L * timeline.sampleRate)
        if (ring.renderedFrames < minFrames && !awaitFilled(minFrames)) {
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
            return Extractor.RESULT_CONTINUE
        }
        val request = minOf(READ_CHUNK_BYTES / timeline.bytesPerFrame, budget)
        queueGuard = false
        var result = reader.read(request)
        if (result == null) {
            // 高水位后 media3 会立刻重试；必须在这里等新 append，不能空转。
            val target = if (ring.renderedFrames >= timeline.loopFrames) {
                reader.offsetFrames + 1L
            } else {
                ring.renderedFrames + 1L
            }
            if (awaitFilled(target)) {
                result = reader.read(request)
            }
            if (result == null) return Extractor.RESULT_CONTINUE
        }
        val data = ParsableByteArray(result.data)
        track.sampleData(data, result.data.size)
        track.sampleMetadata(result.ptsUs, C.BUFFER_FLAG_KEY_FRAME, result.data.size, 0, null)
        samples += 1L
        reader.lastSeamOutputFrames.takeIf { it >= 0L && it != lastSeam }?.let { seam ->
            lastSeam = seam
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
                    " reads=" + reader.reads,
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
        return maxAheadFrames + activeElapsedMs * timeline.sampleRate / 1000L
    }

    private fun awaitFilled(targetFrames: Long): Boolean {
        reader.recordWait()
        val ready = ring.awaitRenderedAtLeast(targetFrames, READ_WAIT_MS)
        logHeapIfDue(System.currentTimeMillis())
        if (!ready) {
            val now = System.currentTimeMillis()
            if (now - lastWaitLogMs >= 5_000L) {
                lastWaitLogMs = now
                RingBandLog.w(
                    "RingBand read wait filled=" + ring.renderedFrames +
                        " offset=" + reader.offsetFrames +
                        " target=" + targetFrames,
                )
            }
        }
        return ready
    }

    override fun seek(position: Long, timeUs: Long) {
        val offset = Math.floorMod(
            timeline.frameAtTimeline(timeUs) - timeline.aFrame,
            timeline.loopFrames,
        )
        reader.seekToOffset(offset, timeUs)
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
