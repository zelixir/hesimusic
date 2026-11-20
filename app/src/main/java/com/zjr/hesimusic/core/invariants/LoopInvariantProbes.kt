package com.zjr.hesimusic.core.invariants

/**
 * §12.2 运行时断言的**事实探针**：main 源集（loop/audio、extractor）只写计数/读取，
 * debug 源集的 PlaybackInvariantMonitor 只读取并交给 [AssumptionInvariantCode] 对应的纯判定器。
 *
 * 为什么放在 core/invariants：release 不含 debug 源集，main 不能反向引用 debug；
 * 这里只依赖 java.util.concurrent，零 Android 依赖，可被任意线程安全写入。
 *
 * 说明：计数是**进程级**的，多个 period/extractor 并存时以最近一次生命周期为准
 * （preload 下一首时会上报最新 period 的数字）；违规 Count 则是累计值，判定器用增量去重。
 */
object LoopInvariantProbes {

    private val lock = Any()

    // ------------------------------------------------------------ A-025 period

    private var periodCreatedAtMs: Long = 0L
    private var periodPreparedAtMs: Long = 0L
    private var continueLoadingAfterPrepare: Long = 0L
    private var continueLoadingAtManualKick: Long = 0L
    private var manualKicks: Long = 0L

    fun onPeriodCreated() {
        synchronized(lock) {
            periodCreatedAtMs = System.currentTimeMillis()
            periodPreparedAtMs = 0L
            continueLoadingAfterPrepare = 0L
            continueLoadingAtManualKick = 0L
            manualKicks = 0L
        }
    }

    fun onPeriodPrepared() {
        synchronized(lock) {
            if (periodPreparedAtMs == 0L) {
                periodPreparedAtMs = System.currentTimeMillis()
            }
        }
    }

    /** media3 调 continueLoading；只统计 prepared 之后的调用（之前的是正常加载）。 */
    fun onPeriodContinueLoading() {
        synchronized(lock) {
            if (periodPreparedAtMs != 0L) {
                continueLoadingAfterPrepare += 1L
            }
        }
    }

    /** 我们在 onPrepared 之后主动补一次 onContinueLoadingRequested。 */
    fun onPeriodManualKick() {
        synchronized(lock) {
            manualKicks += 1L
            continueLoadingAtManualKick = continueLoadingAfterPrepare
        }
    }

    fun periodSnapshot(): PeriodProbeSnapshot = synchronized(lock) {
        PeriodProbeSnapshot(
            createdAtMs = periodCreatedAtMs,
            preparedAtMs = periodPreparedAtMs,
            continueLoadingAfterPrepare = continueLoadingAfterPrepare,
            continueLoadingAtManualKick = continueLoadingAtManualKick,
            manualKicks = manualKicks,
        )
    }

    // ------------------------------------------------------------ A-037 extractor

    private var extractorGeneration: Long = 0L
    private var extractorTracksCreated: Int = 0
    private var extractorEndTracksSeen: Boolean = false
    private var extractorSeekMapBeforeEndTracks: Boolean = false
    private var extractorTrackAfterEndTracksCount: Long = 0L
    private var extractorSeekMapAfterEndTracksCount: Long = 0L
    private var extractorSeekMapMissingCount: Long = 0L

    fun onExtractorInit() {
        synchronized(lock) {
            extractorGeneration += 1L
            extractorTracksCreated = 0
            extractorEndTracksSeen = false
            extractorSeekMapBeforeEndTracks = false
        }
    }

    fun onExtractorTrackCreated() {
        synchronized(lock) {
            extractorTracksCreated += 1
            if (extractorEndTracksSeen) {
                extractorTrackAfterEndTracksCount += 1L
            }
        }
    }

    fun onExtractorSeekMap() {
        synchronized(lock) {
            if (extractorEndTracksSeen) {
                extractorSeekMapAfterEndTracksCount += 1L
            } else {
                extractorSeekMapBeforeEndTracks = true
            }
        }
    }

    fun onExtractorEndTracks() {
        synchronized(lock) {
            extractorEndTracksSeen = true
            if (!extractorSeekMapBeforeEndTracks) {
                extractorSeekMapMissingCount += 1L
            }
        }
    }

    fun extractorSnapshot(): ExtractorProbeSnapshot = synchronized(lock) {
        ExtractorProbeSnapshot(
            generation = extractorGeneration,
            tracksCreated = extractorTracksCreated,
            endTracksSeen = extractorEndTracksSeen,
            seekMapBeforeEndTracks = extractorSeekMapBeforeEndTracks,
            trackAfterEndTracksCount = extractorTrackAfterEndTracksCount,
            seekMapAfterEndTracksCount = extractorSeekMapAfterEndTracksCount,
            seekMapMissingCount = extractorSeekMapMissingCount,
        )
    }

    // ------------------------------------------------------------ A-049 empty buffer

    private var emptyBuffersTotal: Long = 0L
    private var emptyBuffersWhileReplaying: Long = 0L
    private var emptyBuffersWhilePaused: Long = 0L
    private var emptyBuffersWhileActive: Long = 0L

    /**
     * @param replaying 引擎处于 REPLAYING（重放态吞空 buffer 是设计行为）
     * @param playing delegate 当前是 play 态（renderer 已 start）
     */
    fun onSinkEmptyBuffer(replaying: Boolean, playing: Boolean) {
        synchronized(lock) {
            emptyBuffersTotal += 1L
            when {
                replaying -> emptyBuffersWhileReplaying += 1L
                !playing -> emptyBuffersWhilePaused += 1L
                else -> emptyBuffersWhileActive += 1L
            }
        }
    }

    fun emptyBufferSnapshot(): EmptyBufferProbeSnapshot = synchronized(lock) {
        EmptyBufferProbeSnapshot(
            total = emptyBuffersTotal,
            whileReplaying = emptyBuffersWhileReplaying,
            whilePaused = emptyBuffersWhilePaused,
            whileActive = emptyBuffersWhileActive,
        )
    }

    // ------------------------------------------------------------ A-055 / A-NEW-09 configure

    /** configure 后首帧 PTS 允许的媒体时间跳变（100ms；跨 flush/seek 不比较）。 */
    const val CONFIGURE_PTS_JUMP_TOLERANCE_US: Long = 100_000L

    private var sinkConfigureCount: Long = 0L
    private var sinkConfigureWithoutFlushCount: Long = 0L
    private var pendingReplayDroppedCount: Long = 0L
    private var pendingReplayContinuedCount: Long = 0L
    private var configurePtsJumpCount: Long = 0L
    private var lastConfigurePtsJumpUs: Long = 0L

    fun onSinkConfigure(hadPendingReplay: Boolean) {
        synchronized(lock) {
            sinkConfigureCount += 1L
            if (hadPendingReplay) {
                // configure 前 pendingReplay 仍非 null 就说明期间没有 flush；
                // 生产代码随后会清掉它 → 记一次「重复 configure 丢重放」。
                sinkConfigureWithoutFlushCount += 1L
                pendingReplayDroppedCount += 1L
            }
        }
    }

    /** configure 后首帧与 configure 前最后一段的媒体时间跳变（us）；负数表示不比较。 */
    fun onSinkFirstPtsAfterConfigure(jumpUs: Long) {
        if (jumpUs < 0L) return
        synchronized(lock) {
            lastConfigurePtsJumpUs = jumpUs
            if (jumpUs > CONFIGURE_PTS_JUMP_TOLERANCE_US) {
                configurePtsJumpCount += 1L
            }
        }
    }


    fun configureSnapshot(): ConfigureProbeSnapshot = synchronized(lock) {
        ConfigureProbeSnapshot(
            configureCount = sinkConfigureCount,
            configureWithoutFlushCount = sinkConfigureWithoutFlushCount,
            pendingReplayDroppedCount = pendingReplayDroppedCount,
            pendingReplayContinuedCount = pendingReplayContinuedCount,
            configurePtsJumpCount = configurePtsJumpCount,
            lastConfigurePtsJumpUs = lastConfigurePtsJumpUs,
        )
    }

    // ------------------------------------------------------------ A-NEW-01 sink position

    private var sinkOffsetUs: Long = 0L
    private var sinkLastDelegatePtsUs: Long = Long.MIN_VALUE
    private var sinkRawPositionUs: Long = Long.MIN_VALUE
    private var sinkReturnedPositionUs: Long = Long.MIN_VALUE

    fun onSinkPosition(offsetUs: Long, lastDelegatePtsUs: Long, rawPositionUs: Long, returnedPositionUs: Long) {
        synchronized(lock) {
            sinkOffsetUs = offsetUs
            sinkLastDelegatePtsUs = lastDelegatePtsUs
            sinkRawPositionUs = rawPositionUs
            sinkReturnedPositionUs = returnedPositionUs
        }
    }

    fun sinkPositionSnapshot(): SinkPositionProbeSnapshot = synchronized(lock) {
        SinkPositionProbeSnapshot(
            offsetUs = sinkOffsetUs,
            lastDelegatePtsUs = sinkLastDelegatePtsUs,
            rawPositionUs = sinkRawPositionUs,
            returnedPositionUs = sinkReturnedPositionUs,
        )
    }
}

/** A-025 快照。 */
data class PeriodProbeSnapshot(
    val createdAtMs: Long,
    val preparedAtMs: Long,
    val continueLoadingAfterPrepare: Long,
    val continueLoadingAtManualKick: Long,
    val manualKicks: Long,
)

/** A-037 快照。 */
data class ExtractorProbeSnapshot(
    val generation: Long,
    val tracksCreated: Int,
    val endTracksSeen: Boolean,
    val seekMapBeforeEndTracks: Boolean,
    val trackAfterEndTracksCount: Long,
    val seekMapAfterEndTracksCount: Long,
    val seekMapMissingCount: Long,
)

/** A-049 快照。 */
data class EmptyBufferProbeSnapshot(
    val total: Long,
    val whileReplaying: Long,
    val whilePaused: Long,
    val whileActive: Long,
)

/** A-055 / A-NEW-09 快照。 */
data class ConfigureProbeSnapshot(
    val configureCount: Long,
    val configureWithoutFlushCount: Long,
    val pendingReplayDroppedCount: Long,
    val pendingReplayContinuedCount: Long,
    val configurePtsJumpCount: Long,
    val lastConfigurePtsJumpUs: Long,
)

/** A-NEW-01 快照。 */
data class SinkPositionProbeSnapshot(
    val offsetUs: Long,
    val lastDelegatePtsUs: Long,
    val rawPositionUs: Long,
    val returnedPositionUs: Long,
)
