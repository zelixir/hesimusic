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

/**
 * 把 [LoopingPcmSourceCore] 包装成"虚拟无限长 PCM 容器"的 [Extractor]。
 *
 * 这样 ExoPlayer 使用成熟的 [androidx.media3.exoplayer.source.ProgressiveMediaSource]
 * 加载路径：period 创建、loader、buffered、track selection、renderer 启动全部复用
 * media3 实现，不再手写 MediaPeriod 契约。
 *
 * [read] 忽略 [ExtractorInput]（它由空 DataSource 支撑），直接从块缓存取 PCM；
 * B→A 的 PTS 由 core 连续递增，不需要文件层 seek。
 *
 * 00022/00026：**必须自己节流**，不能只依赖 media3 的 LoadControl —— 这个源是
 * "无限长 + 时长未知(TIME_UNSET)"的循环 PCM，真机出现过 loader 把 SampleQueue 一路
 * 推到堆爆（OOM @ ExoPlayer:Loader:ProgressiveMediaPeriod，402MB 堆满）。环带路径
 * ([RingBandExtractor]) 早就有同款节流，这里补齐：发射量 <= 已发射基准 + 5 秒 + 墙钟活跃时间，
 * 超预算就 sleep 后返回"无样本"，绝不继续产出。
 */
@UnstableApi
class LoopingPcmExtractor(
    private val core: LoopingPcmSourceCore,
    private val timeline: LoopingPcmTimeline,
    private val logger: (String) -> Unit = {},
    private val prefetcher: LoopPcmPrefetcher? = null,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
) : Extractor {

    private var trackOutput: TrackOutput? = null
    private var formatSent = false
    private var started = false
    private var samples = 0L
    private var lastReadLogMs = 0L
    private var lastThrottleLogMs = 0L

    /** seek 时的已发射帧数基准：seek 后允许重新领先 [MAX_AHEAD_SECONDS]（与环带同口径）。 */
    private var emittedBaseFrames = 0L

    /** 加载线程真正在"活跃推数据"的墙钟累计（单次间隔 >200ms 视为停顿，不计入）。 */
    private var activeElapsedMs = 0L
    private var lastActiveClockMs = -1L
    private var queueGuard = false
    private val maxAheadFrames = timeline.sampleRate * MAX_AHEAD_SECONDS

    private val format: Format = Format.Builder()
        .setId("loop-pcm-extractor")
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
        val track = output.track(0, C.TRACK_TYPE_AUDIO)
        LoopInvariantProbes.onExtractorTrackCreated()
        trackOutput = track
        track.format(format)
        formatSent = true
        output.seekMap(seekMap)
        LoopInvariantProbes.onExtractorSeekMap()
        output.endTracks()
        LoopInvariantProbes.onExtractorEndTracks()
        logger("extractor init format=" + format.sampleMimeType + "/" + format.pcmEncoding + " " + timeline.sampleRate + "Hz/" + timeline.channels + "ch")
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        if (LoopSourcePauseGate.paused) return Extractor.RESULT_CONTINUE
        val track = trackOutput ?: return Extractor.RESULT_CONTINUE

        // 00026：先算发射预算（墙钟活跃时间），超预算就等待，绝不继续往 SampleQueue 里塞。
        val now = clock()
        val allowed = allowedFrames(now)
        val emitted = core.totalReadFrames()
        if (emitted >= allowed) {
            queueGuard = true
            logThrottle(now, emitted, allowed, "full")
            sleepQuietly(THROTTLE_SLEEP_MS)
            return Extractor.RESULT_CONTINUE
        }
        val budgetFrames = (allowed - emitted).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        // 不把预算切成碎样本；预算不足最小样本时同样等待（否则每次只吐几帧，等于空转）。
        if (budgetFrames < MIN_SAMPLE_FRAMES) {
            queueGuard = true
            logThrottle(now, emitted, allowed, "minSample")
            sleepQuietly(THROTTLE_SLEEP_MS)
            return Extractor.RESULT_CONTINUE
        }

        // 起播门槛：至少 2 块（2s）就绪再吐首个 sample，避免“先出声再断 347ms”。
        if (!started && core.readyBlockCount() < 2) {
            var guard = 0
            while (core.readyBlockCount() < 2 && guard < 4 && core.pumpDecode()) guard += 1
            if (core.readyBlockCount() < 2) {
                prefetcher?.kick()
                return Extractor.RESULT_CONTINUE
            }
        }
        started = true
        prefetcher?.kick()
        if (!formatSent) {
            track.format(format)
            formatSent = true
        }
        prefetcher?.kick()

        val requestBytes = minOf(
            READ_CHUNK_BYTES,
            (budgetFrames.toLong() * timeline.bytesPerFrame).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        )
        var result = core.read(requestBytes)
        if (result == null) {
            // 虚拟容器没有真实 I/O；加载线程按需解一块，避免空转。
            core.pumpDecode()
            result = core.read(requestBytes)
        }
        if (result == null) {
            // 块还没就绪：这里是"等数据"，不算节流。
            queueGuard = false
            return Extractor.RESULT_CONTINUE
        }
        queueGuard = false

        val bpf = timeline.bytesPerFrame
        val framesValid = result.data.size / bpf
        if (bpf <= 0 || result.data.size % bpf != 0 || framesValid != result.frames || result.frames <= 0) {
            // 绝不把短数据/未填满数组当有效样本；不推进已由 core 负责，这里只拒绝输出。
            logger(
                "read invalid bytes=" + result.data.size + " frames=" + result.frames +
                    " bpf=" + bpf + " block=" + core.currentBlockIndex(),
            )
            return Extractor.RESULT_CONTINUE
        }
        val data = ParsableByteArray(result.data)
        track.sampleData(data, result.data.size)
        track.sampleMetadata(result.ptsUs, C.BUFFER_FLAG_KEY_FRAME, result.data.size, 0, null)
        samples += 1L
        val framesOut = requestBytes / bpf
        if (framesValid < framesOut) {
            warnLog(
                "read short framesOut=" + framesOut + " framesValid=" + framesValid +
                    " block=" + core.currentBlockIndex() +
                    " remainingFrames=" + core.currentBlockRemainingFrames() +
                    " blockState=" + core.currentBlockState() +
                    " cursorFrames=" + core.currentFrame(),
            )
        }
        val nowLog = System.currentTimeMillis()
        if (nowLog - lastReadLogMs >= 1_000L) {
            lastReadLogMs = nowLog
            logger(
                "read framesOut=" + framesOut +
                    " framesValid=" + framesValid +
                    " block=" + core.currentBlockIndex() +
                    " remainingFrames=" + core.currentBlockRemainingFrames() +
                    " blockState=" + core.currentBlockState() +
                    " cursorFrames=" + core.currentFrame(),
            )
        }
        if (samples == 1L || samples % 100L == 0L) {
            logger("extractor read n=" + samples + " bytes=" + result.data.size + " ptsUs=" + result.ptsUs)
        }
        return Extractor.RESULT_CONTINUE
    }

    /**
     * 发射预算（帧）：基准 + [MAX_AHEAD_SECONDS] + 墙钟活跃时间。
     *
     * 统计口径与 [RingBandExtractor] 一致：只累计"连续被读"的墙钟时间（单次间隔 <=200ms），
     * 停顿/暂停/测速等待都不计入，因此恢复后不会因为停顿攒出巨大的领先量。
     */
    private fun allowedFrames(now: Long): Long {
        if (lastActiveClockMs < 0L) {
            lastActiveClockMs = now
        } else {
            val delta = now - lastActiveClockMs
            lastActiveClockMs = now
            if (delta in 0L..MAX_ACTIVE_DELTA_MS) activeElapsedMs += delta
        }
        return emittedBaseFrames + maxAheadFrames + activeElapsedMs * timeline.sampleRate / 1000L
    }

    /** 距离预算顶还有多少秒（负 = 已超）；诊断/测试用。 */
    fun aheadOfBudgetSeconds(): Double {
        val emitted = core.totalReadFrames()
        val allowed = emittedBaseFrames + maxAheadFrames + activeElapsedMs * timeline.sampleRate / 1000L
        return (allowed - emitted).toDouble() / timeline.sampleRate
    }

    /** 上一次 read 是否因为超预算而被节流（测试/诊断用）。 */
    fun isThrottling(): Boolean = queueGuard

    private fun logThrottle(now: Long, emitted: Long, allowed: Long, why: String) {
        if (now - lastThrottleLogMs < 5_000L) return
        lastThrottleLogMs = now
        logger(
            "read throttle why=" + why + " emitted=" + emitted +
                " allowed=" + allowed +
                " aheadOfRealtime=" + String.format("%.2f", (emitted - activeElapsedMs * timeline.sampleRate / 1000L).toDouble() / timeline.sampleRate) + "s" +
                " queueGuard=true",
        )
    }

    private fun sleepQuietly(ms: Long) {
        try {
            sleeper(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    override fun seek(position: Long, timeUs: Long) {
        core.seekToTimelineUs(timeUs)
        formatSent = false
        started = false
        // 00022 口径：seek 后重新给满 maxAhead 的发射预算，否则墙钟节流会按实时速度喂数据，
        // 首声要多等 1~2 秒（环带路径同一个坑）。
        emittedBaseFrames = core.totalReadFrames()
        activeElapsedMs = 0L
        lastActiveClockMs = clock()
        queueGuard = false
        prefetcher?.kick()
        logger("extractor seek position=" + position + " timeUs=" + timeUs)
    }

    override fun release() {
        prefetcher?.release()
        core.release()
    }

    private fun warnLog(message: String) {
        try {
            android.util.Log.w("LoopPcmSource", message)
        } catch (_: Throwable) {
            // JVM 单测里 android.util.Log 是 stub。
        }
    }

    private companion object {
        const val READ_CHUNK_BYTES = 64 * 1024
        /** 最多领先播放头 5 秒（与 RingBandExtractor.MAX_AHEAD_SECONDS 同口径）。 */
        const val MAX_AHEAD_SECONDS = 5L
        const val THROTTLE_SLEEP_MS = 4L
        const val MIN_SAMPLE_FRAMES = 1024
        /**
         * 单次 read 间隔超过这个值就当"停顿"，不计入活跃时间。
         *
         * 环带路径用 200ms（它的 read 从预填好的环里取数，几乎每 4ms 一次）；分块路径的
         * read 可能要现解一个 1 秒块，相邻 read 常常间隔 1 秒以上 —— 用 200ms 会把真实
         * 播放时间当停顿丢掉，节流就变成"低于实时"（真机实测 0.83x，40 秒后会欠载）。
         * 这里放宽到 1.5 秒：正常的稀疏读取全额记账；真正的长停顿（暂停/后台）仍不入账。
         */
        const val MAX_ACTIVE_DELTA_MS = 1_500L
    }
}
