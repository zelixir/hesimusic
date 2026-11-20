package com.zjr.hesimusic.core.invariants

import kotlin.math.abs

/**
 * docs/exoplayer-assumptions-verified.md §12.2 里 12 条 UNDEFINED 的运行时断言 code。
 *
 * 命名约定与既有不变量一致：`<domain>-<what>`，全部来自「we must assert this ourselves」，
 * 不是 media3 的承诺（见报告 §12.2 的触发点/失败含义）。
 *
 * 判定器只做纯逻辑（无 Android / 无 media3 依赖），采样由 debug 源集的
 * PlaybackInvariantMonitor 完成；main 源集里只有 [LoopInvariantProbes] 的计数/读取。
 */
object AssumptionInvariantCode {

    /** A-025：prepared 后 media3 没有继续驱动 continueLoading（我们的主动踢也没发生）。 */
    const val PERIOD_CONTINUE_LOADING_MISSING = "period-continue-loading-missing"

    /** A-037：extractor 重复建 track / endTracks 后新 track / seekMap 顺序错。 */
    const val EXTRACTOR_TRACK_AFTER_END_TRACKS = "extractor-track-after-end-tracks"

    /** A-049：正常播放态收到空 buffer（remaining < bytesPerFrame）。 */
    const val SINK_EMPTY_BUFFER_WHILE_PLAYING = "sink-empty-buffer-while-playing"

    /** A-055：configure 次数少于窗口切分次数，或 configure 后首帧 PTS 不连续。 */
    const val SINK_CONFIGURE_SPLIT_MISMATCH = "sink-configure-split-mismatch"

    /** A-091：帧↔µs 往返误差 >= 1 帧，或连续多圈 A/B 帧号差不为常数。 */
    const val TIMELINE_FRAME_US_ROUNDTRIP_DRIFT = "timeline-frame-us-roundtrip-drift"

    /** A-093：duration 三态（负 / 0 / 正）在会话投影分支与约定不符。 */
    const val PROJECTION_DURATION_BRANCH_MISMATCH = "projection-duration-branch-mismatch"

    /** A-NEW-01：sink 返回位置的域与最近一次 handleBuffer 的 PTS 域不一致。 */
    const val SINK_POSITION_DOMAIN_MISMATCH = "sink-position-domain-mismatch"

    /** A-NEW-03：seek 到同 ms 位置后仍等 discontinuity，超时仍挂起/位置未到。 */
    const val SEEK_SAME_POSITION_DISCONTINUITY_TIMEOUT = "seek-same-position-discontinuity-timeout"

    /** A-NEW-07：注入 prepare 错误后 3s 内没有收到 onPlayerError。 */
    const val ERROR_PREPARE_NOT_SURFACED = "error-prepare-not-surfaced"

    /** A-NEW-09：configure 没有伴随 flush，却把正在重放的 pending 数据丢了。 */
    const val SINK_CONFIGURE_DROPPED_PENDING_REPLAY = "sink-configure-dropped-pending-replay"

    /** A-NEW-11：controller 连接后 availableCommands 缺少我们依赖的命令。 */
    const val CONTROLLER_AVAILABLE_COMMANDS_MISSING = "controller-available-commands-missing"

    /** A-NEW-12：暂停超过 N 秒后媒体通知消失（服务被回收/通知被撤）。 */
    const val SERVICE_NOTIFICATION_GONE_AFTER_PAUSE = "service-notification-gone-after-pause"

    /** §12.2 的全部 code（顺序与文档一致）。 */
    val ALL: List<String> = listOf(
        PERIOD_CONTINUE_LOADING_MISSING,
        EXTRACTOR_TRACK_AFTER_END_TRACKS,
        SINK_EMPTY_BUFFER_WHILE_PLAYING,
        SINK_CONFIGURE_SPLIT_MISMATCH,
        TIMELINE_FRAME_US_ROUNDTRIP_DRIFT,
        PROJECTION_DURATION_BRANCH_MISMATCH,
        SINK_POSITION_DOMAIN_MISMATCH,
        SEEK_SAME_POSITION_DISCONTINUITY_TIMEOUT,
        ERROR_PREPARE_NOT_SURFACED,
        SINK_CONFIGURE_DROPPED_PENDING_REPLAY,
        CONTROLLER_AVAILABLE_COMMANDS_MISSING,
        SERVICE_NOTIFICATION_GONE_AFTER_PAUSE,
    )
}

/** controller 侧我们真正下发/读取的命令名（debug 采样把 media3 常量映射成这些字符串）。 */
object ExpectedControllerCommands {
    val SET: Set<String> = setOf(
        "play-pause",
        "prepare",
        "seek-in-current-media-item",
        "seek-to-next",
        "seek-to-previous",
        "set-media-item",
        "change-media-items",
        "set-repeat-mode",
        "set-shuffle-mode",
        "get-current-media-item",
        "get-timeline",
    )
}

/**
 * 帧 ↔ µs 的纯整数换算（与 LoopPcmEngine/LoopAudioSink 的生产公式同口径）。
 *
 * 口径（§10 / A-091）：帧号一律用 ceil(us*sr/1e6) 取整，时间一律用整数算术，
 * 不做浮点累积。往返误差必须 < 1 帧，否则长期播放会累积漂移。
 */
object AssumptionFrameUs {

    const val US_PER_SECOND: Long = 1_000_000L

    fun usToFrameCeil(us: Long, sampleRate: Int): Long {
        if (sampleRate <= 0 || us <= 0L) return 0L
        val whole = us / US_PER_SECOND
        val rem = us % US_PER_SECOND
        return whole * sampleRate + (rem * sampleRate + US_PER_SECOND - 1L) / US_PER_SECOND
    }

    fun frameToUsCeil(frame: Long, sampleRate: Int): Long {
        if (sampleRate <= 0 || frame <= 0L) return 0L
        val whole = frame / sampleRate
        val rem = frame % sampleRate
        return whole * US_PER_SECOND + (rem * US_PER_SECOND + sampleRate - 1L) / sampleRate
    }

    /** 一帧对应的 µs（向上取整；用于「误差 < 1 帧」的阈值）。 */
    fun frameDurationUs(sampleRate: Int): Long =
        if (sampleRate <= 0) 0L else (US_PER_SECOND + sampleRate - 1L) / sampleRate

    /** 一个媒体时间锚点 us -> frame -> us 的往返误差（µs）。 */
    fun roundtripErrorUs(us: Long, sampleRate: Int): Long =
        abs(frameToUsCeil(usToFrameCeil(us, sampleRate), sampleRate) - us)

    /** 半开区间 [A, B) 的帧数。 */
    fun abFrameDelta(aUs: Long, bUs: Long, sampleRate: Int): Long =
        (usToFrameCeil(bUs, sampleRate) - usToFrameCeil(aUs, sampleRate))

    /** A 与 B 两个锚点往返误差的最大值（µs）。 */
    fun maxRoundtripErrorUs(aUs: Long, bUs: Long, sampleRate: Int): Long =
        maxOf(roundtripErrorUs(aUs, sampleRate), roundtripErrorUs(bUs, sampleRate))
}

// ---------------------------------------------------------------- A-025

/** 一次 prepared 后的加载驱动采样。计数均从当前 period 创建起算。 */
data class PeriodContinueLoadingSample(
    val atMs: Long,
    /** 0 = 当前 period 还没 prepared。 */
    val preparedAtMs: Long,
    val continueLoadingAfterPrepare: Long,
    val continueLoadingAtManualKick: Long,
    val manualKicks: Long,
    val graceMs: Long = DEFAULT_GRACE_MS,
) {
    companion object {
        const val DEFAULT_GRACE_MS: Long = 500L
    }
}

/**
 * A-025：prepared 后 500ms 内如果既没有 media3 调 continueLoading、也没有我们主动踢，
 * 加载就没有任何驱动。若只有主动踢（continueLoadingAfterPrepare>0 而 atManualKick==0），
 * 不报错——但 probe 会把「主动踢 vs media3 调用」的计数暴露出来，用于判断能否删掉主动踢。
 */
class PeriodContinueLoadingEvaluator {

    private var reportedPreparedAtMs = 0L

    fun evaluate(sample: PeriodContinueLoadingSample): InvariantEvent? {
        if (sample.preparedAtMs <= 0L) return null
        if (sample.atMs - sample.preparedAtMs < sample.graceMs) return null
        if (sample.continueLoadingAfterPrepare > 0L) {
            reportedPreparedAtMs = sample.preparedAtMs
            return null
        }
        if (reportedPreparedAtMs == sample.preparedAtMs) return null
        reportedPreparedAtMs = sample.preparedAtMs
        return assumptionViolation(
            AssumptionInvariantCode.PERIOD_CONTINUE_LOADING_MISSING,
            sample.atMs,
            "prepared+" + (sample.atMs - sample.preparedAtMs) + "ms continueLoading=0 " +
                "manualKicks=" + sample.manualKicks,
        )
    }

    fun reset() {
        reportedPreparedAtMs = 0L
    }
}

// ---------------------------------------------------------------- A-037

/**
 * 一次 extractor 生命周期采样。
 *
 * [generation] 在每次 init 自增；[tracksCreated] 是本次 init 的 track 数；
 * 三个 Count 是跨 extractor 累计的违规计数（只在真正违规时自增）。
 */
data class ExtractorTrackSample(
    val atMs: Long,
    val generation: Long,
    val tracksCreated: Int,
    val endTracksSeen: Boolean,
    val seekMapBeforeEndTracks: Boolean,
    val trackAfterEndTracksCount: Long,
    val seekMapAfterEndTracksCount: Long,
    val seekMapMissingCount: Long,
)

/**
 * A-037：track 只建一次；seekMap 必须在 endTracks 之前给出；endTracks 之后不能再建 track。
 * 任一违规都会让 media3 静默丢弃新 track/sample（"added new track after finishing tracks"）。
 */
class ExtractorTrackEvaluator {

    private var lastGeneration = Long.MIN_VALUE
    private var lastTrackAfter = 0L
    private var lastSeekMapAfter = 0L
    private var lastSeekMapMissing = 0L

    fun evaluate(sample: ExtractorTrackSample): InvariantEvent? {
        val fresh = sample.generation != lastGeneration
        val duplicateTracks = fresh && sample.tracksCreated > 1
        val trackAfterEnd = sample.trackAfterEndTracksCount > lastTrackAfter
        val seekMapAfterEnd = sample.seekMapAfterEndTracksCount > lastSeekMapAfter
        val seekMapMissing = sample.seekMapMissingCount > lastSeekMapMissing

        lastGeneration = sample.generation
        lastTrackAfter = sample.trackAfterEndTracksCount
        lastSeekMapAfter = sample.seekMapAfterEndTracksCount
        lastSeekMapMissing = sample.seekMapMissingCount

        if (!duplicateTracks && !trackAfterEnd && !seekMapAfterEnd && !seekMapMissing) return null
        val detail = "tracksCreated=" + sample.tracksCreated +
            " trackAfterEnd=" + sample.trackAfterEndTracksCount +
            " seekMapAfterEnd=" + sample.seekMapAfterEndTracksCount +
            " seekMapMissing=" + sample.seekMapMissingCount +
            " endTracks=" + sample.endTracksSeen +
            " seekMapBeforeEnd=" + sample.seekMapBeforeEndTracks
        return assumptionViolation(
            AssumptionInvariantCode.EXTRACTOR_TRACK_AFTER_END_TRACKS,
            sample.atMs,
            detail,
        )
    }

    fun reset() {
        lastGeneration = Long.MIN_VALUE
        lastTrackAfter = 0L
        lastSeekMapAfter = 0L
        lastSeekMapMissing = 0L
    }
}

// ---------------------------------------------------------------- A-049

/** 空 buffer 计数（remaining < bytesPerFrame）。只有正常播放态出现才是异常。 */
data class EmptyBufferSample(
    val atMs: Long,
    val total: Long,
    val whileReplaying: Long,
    val whilePaused: Long,
    val whileActive: Long,
)

/**
 * A-049：空 buffer 只在重放/暂停态出现。正常播放态出现说明源侧产出异常（短读/丢帧），
 * 不能继续被 "返回 true 吞掉" 掩盖。
 */
class EmptyBufferEvaluator {

    private var lastActive = 0L

    fun evaluate(sample: EmptyBufferSample): InvariantEvent? {
        if (sample.whileActive < lastActive) {
            lastActive = sample.whileActive
            return null
        }
        val delta = sample.whileActive - lastActive
        lastActive = sample.whileActive
        if (delta <= 0L) return null
        return assumptionViolation(
            AssumptionInvariantCode.SINK_EMPTY_BUFFER_WHILE_PLAYING,
            sample.atMs,
            "active=" + sample.whileActive +
                " (+" + delta + ") total=" + sample.total +
                " replay=" + sample.whileReplaying +
                " paused=" + sample.whilePaused,
        )
    }

    fun reset() {
        lastActive = 0L
    }
}

// ---------------------------------------------------------------- A-055

/** configure 次数 / 窗口切分次数 / configure 后首帧 PTS 跳变。 */
data class ConfigureSplitSample(
    val atMs: Long,
    val configureCount: Long,
    val windowSplitCount: Long,
    val configurePtsJumpCount: Long,
    val lastConfigurePtsJumpUs: Long,
    /** 最近一次 item/窗口切分的时刻；0 = 未知（视为早已超过 grace）。 */
    val lastWindowSplitAtMs: Long = 0L,
    /** 边界发生后允许 configure 迟到的宽限（prepare 可能需要几秒）。 */
    val windowSplitGraceMs: Long = DEFAULT_WINDOW_SPLIT_GRACE_MS,
) {
    companion object {
        const val DEFAULT_WINDOW_SPLIT_GRACE_MS: Long = 3_000L
    }
}

/**
 * A-055：不要依赖「窗口边界必 configure」，但当它发生时必须满足
 * configureCount >= windowSplitCount，且每次 configure 后首帧 PTS 连续
 * （以媒体时间域比较；跨 flush/seek 不比较）。
 */
class ConfigureSplitEvaluator {

    private var lastBadSignature: String? = null
    private var lastJumpCount = 0L

    fun evaluate(sample: ConfigureSplitSample): InvariantEvent? {
        val jumpDelta = sample.configurePtsJumpCount - lastJumpCount
        lastJumpCount = sample.configurePtsJumpCount
        if (jumpDelta > 0L) {
            lastBadSignature = null
            return assumptionViolation(
                AssumptionInvariantCode.SINK_CONFIGURE_SPLIT_MISMATCH,
                sample.atMs,
                "configure first pts jump=" + sample.lastConfigurePtsJumpUs + "us" +
                    " count=" + sample.configurePtsJumpCount,
            )
        }
        val splitSettled = sample.lastWindowSplitAtMs <= 0L ||
            sample.atMs - sample.lastWindowSplitAtMs >= sample.windowSplitGraceMs
        if (sample.configureCount < sample.windowSplitCount && splitSettled) {
            val signature = sample.configureCount.toString() + "/" + sample.windowSplitCount
            if (signature == lastBadSignature) return null
            lastBadSignature = signature
            return assumptionViolation(
                AssumptionInvariantCode.SINK_CONFIGURE_SPLIT_MISMATCH,
                sample.atMs,
                "configureCount=" + sample.configureCount +
                    " < windowSplitCount=" + sample.windowSplitCount,
            )
        }
        lastBadSignature = null
        return null
    }

    fun reset() {
        lastBadSignature = null
        lastJumpCount = 0L
    }
}

// ---------------------------------------------------------------- A-091

/** 帧↔µs 往返误差 + 连续多圈 A/B 帧号差。 */
data class FrameUsRoundtripSample(
    val atMs: Long,
    val sampleRate: Int,
    val frameDurationUs: Long,
    val roundtripErrorUs: Long,
    val abFrameDeltas: List<Long>,
)

/**
 * A-091：往返误差必须 < 1 帧（严格），连续（最多三圈）A/B 帧号差必须恒定。
 * media3 不承诺取整方向，这是我们的自有不变量；漂移会长期累积成环路错位。
 */
class FrameUsRoundtripEvaluator {

    private var lastBadSignature: String? = null

    fun evaluate(sample: FrameUsRoundtripSample): InvariantEvent? {
        if (sample.sampleRate <= 0 || sample.frameDurationUs <= 0L) return null
        val errorBad = sample.roundtripErrorUs >= sample.frameDurationUs
        val deltaBad = sample.abFrameDeltas.size >= 2 &&
            sample.abFrameDeltas.distinct().size > 1
        if (!errorBad && !deltaBad) {
            lastBadSignature = null
            return null
        }
        val signature = sample.sampleRate.toString() + ":" +
            sample.roundtripErrorUs + ":" + sample.abFrameDeltas
        if (signature == lastBadSignature) return null
        lastBadSignature = signature
        return assumptionViolation(
            AssumptionInvariantCode.TIMELINE_FRAME_US_ROUNDTRIP_DRIFT,
            sample.atMs,
            "roundtripError=" + sample.roundtripErrorUs + "us" +
                " frame=" + sample.frameDurationUs + "us" +
                " abDeltas=" + sample.abFrameDeltas,
        )
    }

    fun reset() {
        lastBadSignature = null
    }
}

// ---------------------------------------------------------------- A-093

/** duration 三态在会话投影里实际走的分支。 */
enum class DurationBranch {
    /** 透传/数据库兜底（durationUs < 0 或投影不生效）。 */
    FALLBACK,
    /** 长度为 0 的真实窗口。 */
    ZERO,
    /** 按真实时长投影/钳位。 */
    CLAMP,
}

/** 会话层 duration 投影采样。 */
data class ProjectionDurationSample(
    val atMs: Long,
    val windowActive: Boolean,
    val durationUs: Long,
    val branch: DurationBranch,
    val projectedUs: Long,
    val displayedPositionUs: Long,
)

/**
 * A-093：media3 只把 TIME_UNSET 当未知；0 就是长度 0 的 window。
 * 约定：durationUs<0 → FALLBACK；==0 → ZERO；>0 → CLAMP 到 [0, duration]。
 * 当前实现用「投影结果 >0 才算 CLAMP」把 0 也当成了不投影——一旦出现 0 时长源会被本断言抓到。
 */
class ProjectionDurationEvaluator {

    private var lastBadSignature: String? = null

    fun evaluate(sample: ProjectionDurationSample): InvariantEvent? {
        if (!sample.windowActive) {
            lastBadSignature = null
            return null
        }
        val expected = when {
            sample.durationUs < 0L -> DurationBranch.FALLBACK
            sample.durationUs == 0L -> DurationBranch.ZERO
            else -> DurationBranch.CLAMP
        }
        val problems = ArrayList<String>(3)
        if (sample.branch != expected) {
            problems += "branch=" + sample.branch + " expected=" + expected
        }
        if (expected == DurationBranch.CLAMP) {
            if (sample.projectedUs != sample.durationUs) {
                problems += "projected=" + sample.projectedUs + " expected=" + sample.durationUs
            }
            if (sample.displayedPositionUs < 0L || sample.displayedPositionUs > sample.durationUs) {
                problems += "display=" + sample.displayedPositionUs +
                    " outOf=[0," + sample.durationUs + "]"
            }
        } else {
            if (sample.projectedUs != 0L) {
                problems += "projected=" + sample.projectedUs + " expected=0"
            }
            if (sample.displayedPositionUs < 0L) {
                problems += "display=" + sample.displayedPositionUs + " < 0"
            }
        }
        if (problems.isEmpty()) {
            lastBadSignature = null
            return null
        }
        val signature = sample.durationUs.toString() + ":" + sample.branch + ":" +
            sample.projectedUs + ":" + sample.displayedPositionUs
        if (signature == lastBadSignature) return null
        lastBadSignature = signature
        return assumptionViolation(
            AssumptionInvariantCode.PROJECTION_DURATION_BRANCH_MISMATCH,
            sample.atMs,
            problems.joinToString(","),
        )
    }

    fun reset() {
        lastBadSignature = null
    }
}

// ---------------------------------------------------------------- A-NEW-01

/**
 * sink 位置查询采样。所有值都在「delegate 时间域」：
 * offsetUs 是 ExoPlayer 的 output stream offset（初始约 1e12），
 * lastDelegatePtsUs 是最近一次写给 delegate 的 PTS（= 媒体时间 + offset）。
 */
data class SinkPositionDomainSample(
    val atMs: Long,
    val offsetUs: Long,
    val lastDelegatePtsUs: Long,
    val rawPositionUs: Long,
    val returnedPositionUs: Long,
    val toleranceUs: Long = DEFAULT_TOLERANCE_US,
) {
    companion object {
        const val DEFAULT_TOLERANCE_US: Long = 10_000_000L
    }
}

/**
 * A-NEW-01：sink 返回的位置必须与最近 handleBuffer 的 PTS 同域。
 * offset>0 时，同域意味着二者都 >= offset（renderer 时间）；一方在媒体域一方在 offset 域
 * 会让 ExoPlayer 的位置静默错约 1e12µs（≈11.6 天）。
 */
class SinkPositionDomainEvaluator {

    private var reported = false

    fun evaluate(sample: SinkPositionDomainSample): InvariantEvent? {
        if (sample.lastDelegatePtsUs == Long.MIN_VALUE ||
            sample.returnedPositionUs == Long.MIN_VALUE ||
            sample.returnedPositionUs == 0L
        ) {
            reported = false
            return null
        }
        if (sample.offsetUs <= 0L) {
            val gap = abs(sample.returnedPositionUs - sample.lastDelegatePtsUs)
            if (gap <= sample.toleranceUs) {
                reported = false
                return null
            }
        } else {
            val returnedInOffset = sample.returnedPositionUs >= sample.offsetUs
            val lastPtsInOffset = sample.lastDelegatePtsUs >= sample.offsetUs
            if (returnedInOffset == lastPtsInOffset) {
                reported = false
                return null
            }
        }
        if (reported) return null
        reported = true
        return assumptionViolation(
            AssumptionInvariantCode.SINK_POSITION_DOMAIN_MISMATCH,
            sample.atMs,
            "offset=" + sample.offsetUs +
                " lastPts=" + sample.lastDelegatePtsUs +
                " raw=" + sample.rawPositionUs +
                " returned=" + sample.returnedPositionUs,
        )
    }

    fun reset() {
        reported = false
    }
}

// ---------------------------------------------------------------- A-NEW-03

/** 同 ms 位置 seek 的收敛采样。 */
data class SamePositionSeekSample(
    val atMs: Long,
    val requestAtMs: Long,
    val requestedMs: Long,
    val positionAtRequestMs: Long,
    val positionNowMs: Long,
    val discontinuitySeen: Boolean,
    val elapsedSinceRequestMs: Long,
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    /** UI 的 pendingSeek 覆盖层是否仍在等待（真机由 debug 注入/证据提供）。 */
    val overlayPending: Boolean = false,
) {
    companion object {
        const val DEFAULT_TIMEOUT_MS: Long = 1_500L
        const val SAME_POSITION_TOLERANCE_MS: Long = 1L
        const val POSITION_TOLERANCE_MS: Long = 50L
    }
}

/**
 * A-NEW-03：media3 对「seek 到当前 ms 位置」是 no-op，不会发 discontinuity。
 *
 * 判定：同位置 seek + 没有 discontinuity + 超过超时窗口后——
 * - overlayPending 仍为 true（还在等 discontinuity）→ 覆盖层会永久卡住；
 * - 或位置没有落在目标±50ms（seek 丢了）→ 位置不更新。
 * 位置已到目标且覆盖层已收掉则视为成功（超时即成功，不要求 discontinuity）。
 */
class SamePositionSeekEvaluator {

    private var reportedRequestAtMs = Long.MIN_VALUE

    fun evaluate(sample: SamePositionSeekSample): InvariantEvent? {
        if (abs(sample.requestedMs - sample.positionAtRequestMs) >
            SamePositionSeekSample.SAME_POSITION_TOLERANCE_MS
        ) {
            return null
        }
        if (sample.discontinuitySeen) return null
        if (sample.elapsedSinceRequestMs < sample.timeoutMs) return null
        val reached = abs(sample.positionNowMs - sample.requestedMs) <=
            SamePositionSeekSample.POSITION_TOLERANCE_MS
        if (!sample.overlayPending && reached) return null
        if (reportedRequestAtMs == sample.requestAtMs) return null
        reportedRequestAtMs = sample.requestAtMs
        return assumptionViolation(
            AssumptionInvariantCode.SEEK_SAME_POSITION_DISCONTINUITY_TIMEOUT,
            sample.atMs,
            "request=" + sample.requestedMs +
                " now=" + sample.positionNowMs +
                " elapsed=" + sample.elapsedSinceRequestMs +
                " overlayPending=" + sample.overlayPending +
                " discontinuity=" + sample.discontinuitySeen,
        )
    }

    fun reset() {
        reportedRequestAtMs = Long.MIN_VALUE
    }
}

// ---------------------------------------------------------------- A-NEW-07

/** prepare 错误是否在 deadline 内变成 onPlayerError。 */
data class PrepareErrorSample(
    val atMs: Long,
    /** 0 = 当前没有注入/没有待验证的 prepare 错误。 */
    val injectedAtMs: Long,
    val playerErrorAtMs: Long,
    val deadlineMs: Long = DEFAULT_DEADLINE_MS,
) {
    companion object {
        const val DEFAULT_DEADLINE_MS: Long = 3_000L
    }
}

/**
 * A-NEW-07：我们只能从 MediaPeriod.maybeThrowPrepareError 抛错（source 侧抛错没人调）。
 * 注入 prepare 错误后，onPlayerError 必须在 3s 内到达；否则播放器会永远 BUFFERING。
 */
class PrepareErrorEvaluator {

    fun evaluate(sample: PrepareErrorSample): InvariantEvent? {
        if (sample.injectedAtMs <= 0L) return null
        if (sample.playerErrorAtMs > 0L &&
            sample.playerErrorAtMs <= sample.injectedAtMs + sample.deadlineMs
        ) {
            return null
        }
        if (sample.atMs - sample.injectedAtMs < sample.deadlineMs) return null
        return assumptionViolation(
            AssumptionInvariantCode.ERROR_PREPARE_NOT_SURFACED,
            sample.atMs,
            "injected+" + (sample.atMs - sample.injectedAtMs) + "ms errorAt=" + sample.playerErrorAtMs,
        )
    }

    fun reset() = Unit
}

// ---------------------------------------------------------------- A-NEW-09

/** configure 与 pending 重放的关系（累计计数）。 */
data class ConfigurePendingReplaySample(
    val atMs: Long,
    val configureCount: Long,
    val configureWithoutFlushCount: Long,
    val pendingReplayDroppedCount: Long,
    val pendingReplayContinuedCount: Long,
)

/**
 * A-NEW-09：同 format 的 configure 可能重复到达且不伴随 flush。若 configure 时还有
 * pending 重放数据，必须继续而不是清掉；否则重复 configure 会丢一段正在重放的数据
 * （用户可听到环回点附近短静音/跳帧）。
 */
class ConfigurePendingReplayEvaluator {

    private var lastDropped = 0L

    fun evaluate(sample: ConfigurePendingReplaySample): InvariantEvent? {
        if (sample.pendingReplayDroppedCount < lastDropped) {
            lastDropped = sample.pendingReplayDroppedCount
            return null
        }
        val delta = sample.pendingReplayDroppedCount - lastDropped
        lastDropped = sample.pendingReplayDroppedCount
        if (delta <= 0L) return null
        return assumptionViolation(
            AssumptionInvariantCode.SINK_CONFIGURE_DROPPED_PENDING_REPLAY,
            sample.atMs,
            "dropped=" + sample.pendingReplayDroppedCount +
                " (+" + delta + ")" +
                " configure=" + sample.configureCount +
                " withoutFlush=" + sample.configureWithoutFlushCount +
                " continued=" + sample.pendingReplayContinuedCount,
        )
    }

    fun reset() {
        lastDropped = 0L
    }
}

// ---------------------------------------------------------------- A-NEW-11

/** controller 连接后 availableCommands 的采样。 */
data class AvailableCommandsSample(
    val atMs: Long,
    val controllerGeneration: Long,
    val connected: Boolean,
    val available: Set<String>,
    val expected: Set<String> = ExpectedControllerCommands.SET,
)

/**
 * A-NEW-11：MediaController 对未授权命令只打 warning 后静默 return。
 * 连接完成后必须确认 availableCommands 覆盖我们真正下发的命令集合。
 */
class AvailableCommandsEvaluator {

    private var lastSignature: String? = null

    fun evaluate(sample: AvailableCommandsSample): InvariantEvent? {
        if (!sample.connected) {
            lastSignature = null
            return null
        }
        val missing = sample.expected.filterNot { it in sample.available }.toSet()
        if (missing.isEmpty()) {
            lastSignature = null
            return null
        }
        val signature = sample.controllerGeneration.toString() + ":" + missing.sorted()
        if (signature == lastSignature) return null
        lastSignature = signature
        return assumptionViolation(
            AssumptionInvariantCode.CONTROLLER_AVAILABLE_COMMANDS_MISSING,
            sample.atMs,
            "missing=" + missing.sorted(),
        )
    }

    fun reset() {
        lastSignature = null
    }
}

// ---------------------------------------------------------------- A-NEW-12

/** 暂停时长与媒体通知是否仍在。 */
data class ServiceNotificationSample(
    val atMs: Long,
    val pausedForMs: Long,
    val notificationPresent: Boolean,
    val minAliveMs: Long = DEFAULT_MIN_ALIVE_MS,
) {
    companion object {
        const val DEFAULT_MIN_ALIVE_MS: Long = 5_000L
    }
}

/**
 * A-NEW-12：暂停后通知必须继续存在（默认 N=5s，按媒体通知配置的 timeout 调整）。
 * 消失说明服务被回收/通知被撤，用户从通知栏无法恢复播放。
 */
class ServiceNotificationAliveEvaluator {

    private var reported = false

    fun evaluate(sample: ServiceNotificationSample): InvariantEvent? {
        if (sample.pausedForMs <= 0L) {
            reported = false
            return null
        }
        if (sample.pausedForMs < sample.minAliveMs) return null
        if (sample.notificationPresent) {
            reported = false
            return null
        }
        if (reported) return null
        reported = true
        return assumptionViolation(
            AssumptionInvariantCode.SERVICE_NOTIFICATION_GONE_AFTER_PAUSE,
            sample.atMs,
            "pausedFor=" + sample.pausedForMs + "ms minAlive=" + sample.minAliveMs + "ms",
        )
    }

    fun reset() {
        reported = false
    }
}

private fun assumptionViolation(code: String, atMs: Long, detail: String): InvariantEvent =
    InvariantEvent(
        code = code,
        message = "INVARIANT violated: " + code + " " + detail,
        atMs = atMs,
    )
