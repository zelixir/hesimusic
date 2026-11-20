package com.zjr.hesimusic.debug

import android.app.NotificationManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.zjr.hesimusic.core.invariants.AssumptionFrameUs
import com.zjr.hesimusic.core.invariants.AudioOutputFrames
import com.zjr.hesimusic.core.invariants.AssumptionInvariantCode
import com.zjr.hesimusic.core.invariants.AvailableCommandsEvaluator
import com.zjr.hesimusic.core.invariants.AvailableCommandsSample
import com.zjr.hesimusic.core.invariants.ConfigurePendingReplayEvaluator
import com.zjr.hesimusic.core.invariants.ConfigurePendingReplaySample
import com.zjr.hesimusic.core.invariants.ConfigureSplitEvaluator
import com.zjr.hesimusic.core.invariants.ConfigureSplitSample
import com.zjr.hesimusic.core.invariants.DurationBranch
import com.zjr.hesimusic.core.invariants.EmptyBufferEvaluator
import com.zjr.hesimusic.core.invariants.EmptyBufferSample
import com.zjr.hesimusic.core.invariants.ExtractorTrackEvaluator
import com.zjr.hesimusic.core.invariants.ExtractorTrackSample
import com.zjr.hesimusic.core.invariants.FrameUsRoundtripEvaluator
import com.zjr.hesimusic.core.invariants.FrameUsRoundtripSample
import com.zjr.hesimusic.core.invariants.InvariantEvent
import com.zjr.hesimusic.core.invariants.InvariantRegistry
import com.zjr.hesimusic.core.invariants.LoopInvariantProbes
import com.zjr.hesimusic.core.invariants.OutputInvariantEvaluator
import com.zjr.hesimusic.core.invariants.OutputInvariantSample
import com.zjr.hesimusic.core.invariants.PeriodContinueLoadingEvaluator
import com.zjr.hesimusic.core.invariants.PeriodContinueLoadingSample
import com.zjr.hesimusic.core.invariants.PrepareErrorEvaluator
import com.zjr.hesimusic.core.invariants.PrepareErrorSample
import com.zjr.hesimusic.core.invariants.ProjectionDurationEvaluator
import com.zjr.hesimusic.core.invariants.ProjectionDurationSample
import com.zjr.hesimusic.core.invariants.SamePositionSeekEvaluator
import com.zjr.hesimusic.core.invariants.SamePositionSeekSample
import com.zjr.hesimusic.core.invariants.ServiceNotificationAliveEvaluator
import com.zjr.hesimusic.core.invariants.ServiceNotificationSample
import com.zjr.hesimusic.core.invariants.SinkPositionDomainEvaluator
import com.zjr.hesimusic.core.invariants.SinkPositionDomainSample
import com.zjr.hesimusic.core.invariants.SourceInvariantEvaluator
import com.zjr.hesimusic.core.invariants.SourceInvariantSample
import com.zjr.hesimusic.data.mapper.mediaIdToSongId
import com.zjr.hesimusic.loop.audio.source.LoopAwareMediaSourceFactory
import com.zjr.hesimusic.loop.audio.source.LoopSourceItemTag
import com.zjr.hesimusic.loop.audio.source.LoopSourceMainPlayer
import com.zjr.hesimusic.service.LoopSessionProjection
import com.zjr.hesimusic.service.LoopSourceFallback
import com.zjr.hesimusic.service.LoopSourceRuntime

/**
 * 运行期不变量采样器（仅 debug 源集；release 不编译，天然关闭）。
 *
 * 每 500ms 在主线程采一次事实，交给 core/invariants 的纯逻辑判定，命中就
 * Log.e + 写入 [InvariantRegistry]（GET /state 的 invariants 数组）。
 *
 * 两类不变量：
 * - 既有 00020/00021：暂停仍写 / 播放静音 / 循环投影 vs 普通源；
 * - docs/exoplayer-assumptions-verified.md §12.2 的 12 条 UNDEFINED 运行时断言
 *   （A-025/A-037/A-049/A-055/A-091/A-093 + A-NEW-01/03/07/09/11/12）。
 *
 * 真机验证：POST /debug/invariant {kind} 注入对应故障；每种 kind 都用**真实判定器**
 * 跑一遍合成采样，验证「采样 → 判定 → Log.e → /state.invariants」整条链路。
 */
internal object PlaybackInvariantMonitor {

    private const val TAG = "LoopInvariant"

    /** 采样周期：任务约定 500ms。 */
    private const val SAMPLE_INTERVAL_MS = 500L

    /**
     * 音源不一致需要连续 2 次采样（≈1s）确认才上报：
     * 进/退循环的那一次主线程调用内部会同时改 item、建源，采样不会插进中间，
     * 但队列重建/换歌等路径可能有短瞬态，1s 确认可避免假警报。
     */
    private const val SOURCE_CONFIRM_SAMPLES = 2

    /** 采样明细日志间隔（logcat -s LoopInvariant）。 */
    private const val SAMPLE_LOG_INTERVAL_MS = 5_000L

    /** media3 DefaultMediaNotificationProvider 的默认通知 id / channel。 */
    private const val NOTIFICATION_ID_MEDIA3 = 1001
    private const val NOTIFICATION_CHANNEL_MEDIA3 = "default_channel_id"

    /** MusicService 在关闭媒体通知时使用的前台静默通知。 */
    private const val NOTIFICATION_ID_SILENT = 2001
    private const val NOTIFICATION_CHANNEL_SILENT = "silent_media_service"

    // ------------------------------------------------------------ 既有故障注入

    /** 故障注入：暂停仍写（每采样 +96ms@48kHz，模拟 00020 的 96ms 出声）。 */
    const val FAULT_PAUSED_WRITE = "paused-write"

    /** 故障注入：播放但 sink 静音（连续零帧，模拟 playing-but-sink-silent）。 */
    const val FAULT_SILENT = "silent"

    /** 故障注入：循环投影但 item 没有 loop tag / 工厂选的是普通源（模拟 00021）。 */
    const val FAULT_SOURCE_PLAIN = "source-plain"

    /** 故障注入：非循环投影但 item 仍带 loop tag（反向）。 */
    const val FAULT_SOURCE_LOOP = "source-loop"

    // ------------------------------------------------------------ §12.2 故障注入

    const val FAULT_PERIOD_CONTINUE_LOADING = "period-continue-loading"
    const val FAULT_EXTRACTOR_TRACK = "extractor-track-after-end"
    const val FAULT_EMPTY_BUFFER = "sink-empty-buffer"
    const val FAULT_CONFIGURE_SPLIT = "sink-configure-split"
    const val FAULT_FRAME_US = "timeline-frame-us-roundtrip"
    const val FAULT_PROJECTION_DURATION = "projection-duration"
    const val FAULT_POSITION_DOMAIN = "sink-position-domain"
    const val FAULT_SAME_POSITION_SEEK = "seek-same-position"
    const val FAULT_PREPARE_ERROR = "prepare-error"
    const val FAULT_CONFIGURE_NO_FLUSH = "sink-configure-no-flush"
    const val FAULT_CONTROLLER_COMMANDS = "controller-commands"
    const val FAULT_NOTIFICATION = "notification-gone"

    /** POST /debug/invariant 允许的全部 kind（不含 off/clear，由服务端单独放行）。 */
    val FAULT_KINDS: Set<String> = setOf(
        FAULT_PAUSED_WRITE,
        FAULT_SILENT,
        FAULT_SOURCE_PLAIN,
        FAULT_SOURCE_LOOP,
        FAULT_PERIOD_CONTINUE_LOADING,
        FAULT_EXTRACTOR_TRACK,
        FAULT_EMPTY_BUFFER,
        FAULT_CONFIGURE_SPLIT,
        FAULT_FRAME_US,
        FAULT_PROJECTION_DURATION,
        FAULT_POSITION_DOMAIN,
        FAULT_SAME_POSITION_SEEK,
        FAULT_PREPARE_ERROR,
        FAULT_CONFIGURE_NO_FLUSH,
        FAULT_CONTROLLER_COMMANDS,
        FAULT_NOTIFICATION,
    )

    private val registry = InvariantRegistry()
    private val outputEvaluator = OutputInvariantEvaluator()
    private val sourceEvaluator = SourceInvariantEvaluator()
    private val periodEvaluator = PeriodContinueLoadingEvaluator()
    private val extractorEvaluator = ExtractorTrackEvaluator()
    private val emptyBufferEvaluator = EmptyBufferEvaluator()
    private val configureSplitEvaluator = ConfigureSplitEvaluator()
    private val frameUsEvaluator = FrameUsRoundtripEvaluator()
    private val projectionDurationEvaluator = ProjectionDurationEvaluator()
    private val sinkPositionEvaluator = SinkPositionDomainEvaluator()
    private val samePositionSeekEvaluator = SamePositionSeekEvaluator()
    private val prepareErrorEvaluator = PrepareErrorEvaluator()
    private val configurePendingReplayEvaluator = ConfigurePendingReplayEvaluator()
    private val availableCommandsEvaluator = AvailableCommandsEvaluator()
    private val notificationEvaluator = ServiceNotificationAliveEvaluator()

    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var entry: DebugEntryPoint? = null

    @Volatile
    private var connection: DebugPlayerConnection? = null

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var started = false

    @Volatile
    private var lastSample: Map<String, Any?>? = null

    /** 非 null 时用合成采样打真实判定器（真机验证用，见 POST /debug/invariant）。 */
    @Volatile
    private var faultInjection: String? = null

    private var faultFrames = 0L
    private var sourcePendingSamples = 0
    private var lastSampleLogMs = 0L

    // ---- §12.2 运行时断言采样状态（全部只在主线程读写） ----

    private var exoPlayer: ExoPlayer? = null
    private var playerListener: Player.Listener? = null
    private var lastMediaId: String? = null
    private var windowSplitCount = 0L
    private var windowSplitAtMs = 0L
    private var lastCommandIds: Set<Int>? = null
    private var controllerGeneration = 0L
    private var pausedSinceMs = 0L
    private var wasPlayWhenReady = false
    private var seamCountAtLastSample = -1L
    private var abWindowSignature = ""
    private val abDeltas = ArrayDeque<Long>()
    private var prepareErrorInjectedAtMs = 0L
    private var playerErrorAtMs = 0L
    private var samePositionSeekFaultRequestAtMs = 0L

    /** media3 常量 → 可读命令名（A-NEW-11）。 */
    private val commandNames: Map<Int, String> = mapOf(
        Player.COMMAND_PLAY_PAUSE to "play-pause",
        Player.COMMAND_PREPARE to "prepare",
        Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM to "seek-in-current-media-item",
        Player.COMMAND_SEEK_TO_NEXT to "seek-to-next",
        Player.COMMAND_SEEK_TO_PREVIOUS to "seek-to-previous",
        Player.COMMAND_SET_MEDIA_ITEM to "set-media-item",
        Player.COMMAND_CHANGE_MEDIA_ITEMS to "change-media-items",
        Player.COMMAND_SET_REPEAT_MODE to "set-repeat-mode",
        Player.COMMAND_SET_SHUFFLE_MODE to "set-shuffle-mode",
        Player.COMMAND_GET_CURRENT_MEDIA_ITEM to "get-current-media-item",
        Player.COMMAND_GET_TIMELINE to "get-timeline",
    )

    /** 由 LoopDebugServer 在 Hilt 依赖就绪后调用一次（幂等）。 */
    @Synchronized
    fun start(d: DebugEntryPoint, playerConnection: DebugPlayerConnection, context: Context) {
        if (started) return
        started = true
        entry = d
        connection = playerConnection
        appContext = context.applicationContext
        handler.postDelayed(sampler, SAMPLE_INTERVAL_MS)
        Log.i(TAG, "invariant monitor started intervalMs=" + SAMPLE_INTERVAL_MS)
    }

    /** GET /state.invariants：[{code,message,atMs,count}]，最近 10 条。 */
    fun snapshotMaps(): List<Map<String, Any?>> = try {
        registry.snapshot().map { it.toMap() }
    } catch (t: Throwable) {
        Log.w(TAG, "invariant snapshot failed", t)
        emptyList()
    }

    /** GET /state.invariantProbe：最近一次采样明细（排障用；未采样为 null）。 */
    fun lastSampleMap(): Map<String, Any?>? = lastSample

    /**
     * 故障注入（仅 debug）：用合成采样喂**真实**判定器，验证
     * 「采样 → 判定 → Log.e → /state.invariants」整条链路在真机可用。
     *
     * kind = null/"off" 关闭；"clear" 只清空历史。只在主线程改判定器状态。
     */
    fun setFaultInjection(kind: String?) {
        handler.post {
            val raw = kind?.trim()?.lowercase()
            val normalized = when (raw) {
                null, "", "off", "none" -> null
                "clear" -> {
                    registry.clear()
                    null
                }
                in FAULT_KINDS -> raw
                else -> null
            }
            faultInjection = normalized
            faultFrames = 0L
            outputEvaluator.reset()
            sourcePendingSamples = 0
            resetAssumptionEvaluators()
            prepareErrorInjectedAtMs = 0L
            playerErrorAtMs = 0L
            samePositionSeekFaultRequestAtMs = 0L
            abDeltas.clear()
            abWindowSignature = ""
            if (normalized == FAULT_PREPARE_ERROR) {
                val injectedAt = System.currentTimeMillis()
                prepareErrorInjectedAtMs = injectedAt
                // 已经处于错误态也算「3s 内有错误」，但通常注入时播放器是正常的。
                if (exoPlayer?.playerError != null) playerErrorAtMs = injectedAt
                Log.w(TAG, "prepare-error injection armed at=" + injectedAt + "; expect onPlayerError within 3s")
            }
            if (normalized == FAULT_SAME_POSITION_SEEK) {
                samePositionSeekFaultRequestAtMs = System.currentTimeMillis()
                Log.w(TAG, "seek-same-position injection armed; overlay-pending sample will fire")
            }
            Log.w(TAG, "fault injection -> " + (normalized ?: "off"))
        }
    }

    private val sampler = object : Runnable {
        override fun run() {
            try {
                sampleOnce()
            } catch (t: Throwable) {
                // 采样失败绝不影响播放：只记日志，下一轮继续。
                Log.w(TAG, "invariant sample failed", t)
            }
            handler.postDelayed(this, SAMPLE_INTERVAL_MS)
        }
    }

    private fun resetAssumptionEvaluators() {
        periodEvaluator.reset()
        extractorEvaluator.reset()
        emptyBufferEvaluator.reset()
        configureSplitEvaluator.reset()
        frameUsEvaluator.reset()
        projectionDurationEvaluator.reset()
        sinkPositionEvaluator.reset()
        samePositionSeekEvaluator.reset()
        prepareErrorEvaluator.reset()
        configurePendingReplayEvaluator.reset()
        availableCommandsEvaluator.reset()
        notificationEvaluator.reset()
    }

    private fun ensurePlayer(d: DebugEntryPoint): ExoPlayer {
        exoPlayer?.let { return it }
        val player = d.exoPlayer()
        exoPlayer = player
        lastMediaId = player.currentMediaItem?.mediaId
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val previous = lastMediaId
                lastMediaId = mediaItem?.mediaId
                // 首次装歌（null -> item）不算窗口切分；之后每次 item 变化算一次。
                if (previous != null) {
                    windowSplitCount += 1L
                    windowSplitAtMs = System.currentTimeMillis()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (playerErrorAtMs == 0L) {
                    playerErrorAtMs = System.currentTimeMillis()
                }
            }
        }
        player.addListener(listener)
        playerListener = listener
        return player
    }

    private fun sampleOnce() {
        val d = entry ?: return
        val conn = connection ?: return
        // 只在主播放器已真正连上（MusicService 已起、ExoPlayer 已创建）时采样，
        // 避免 debug 请求反向“提前创建”播放器。
        if (!conn.isConnected()) return
        val player: ExoPlayer = ensurePlayer(d)
        val now = System.currentTimeMillis()
        val controller = d.loopAudioController()
        val frames = AudioOutputFrames.totalFrames(controller)
        val playbackState = player.playbackState
        // 主播放器是否走 SOURCE（TapAudioSink）路线：帧计数只覆盖这条路线；
        // M3′ 路线由 LoopAudioSink 出声，目前没有同一来源的帧计数，不参与判定（避免误报）。
        val routeSource = LoopSourceMainPlayer.isMainPlayerActive

        val fault = faultInjection

        // ---- 暂停时长（A-NEW-12） ----
        val playWhenReady = player.playWhenReady
        if (playWhenReady) {
            pausedSinceMs = 0L
        } else if (wasPlayWhenReady && pausedSinceMs == 0L) {
            pausedSinceMs = now
        }
        wasPlayWhenReady = playWhenReady
        val pausedForMs = if (pausedSinceMs > 0L) now - pausedSinceMs else 0L

        // ---- 既有 00020 输出侧 ----
        val outputSample = when (fault) {
            FAULT_PAUSED_WRITE -> {
                faultFrames += 96L * 48L
                OutputInvariantSample(
                    atMs = now,
                    playWhenReady = false,
                    writtenFrames = faultFrames,
                )
            }
            FAULT_SILENT -> OutputInvariantSample(
                atMs = now,
                playWhenReady = true,
                writtenFrames = faultFrames,
                buffering = false,
                playbackStateReady = true,
            )
            else -> if (routeSource) {
                OutputInvariantSample(
                    atMs = now,
                    playWhenReady = playWhenReady,
                    writtenFrames = frames,
                    buffering = playbackState == Player.STATE_BUFFERING,
                    playbackStateReady = playbackState == Player.STATE_READY,
                )
            } else {
                null
            }
        }
        outputSample?.let { sample -> outputEvaluator.onSample(sample)?.let { report(it) } }

        // ---- 既有 00021 音源侧 ----
        val current = player.currentMediaItem
        val mediaId = current?.mediaId.orEmpty()
        val tag = current?.localConfiguration?.tag
        val itemHasLoopTag = tag is LoopSourceItemTag
        val songId = current?.mediaId?.let { mediaIdToSongId(it) }
        val runtimeWindow = d.loopRuntime().window.value
        val installed = LoopSourceRuntime.installedWindow
        // 「页面/会话投影处于循环模式」= SOURCE 路线 + 会话窗口有效且属于当前歌 +
        // SOURCE 接管已登记同一窗口 + 没有被回退登记。M3′ 路线虽然 window 也非空，
        // 但它的普通文件源是设计如此（环回在 sink 层），不能判成“投影循环、音源普通”。
        val windowActive = routeSource &&
            runtimeWindow != null && runtimeWindow.isValid() &&
            (songId == null || runtimeWindow.songId == songId) &&
            installed != null &&
            installed.songId == runtimeWindow.songId &&
            installed.loopStartUs == runtimeWindow.loopStartUs &&
            installed.loopEndUs == runtimeWindow.loopEndUs &&
            !LoopSourceFallback.isUnavailable(songId, runtimeWindow)
        val select = if (mediaId.isEmpty()) {
            null
        } else {
            LoopAwareMediaSourceFactory.lastSelectFor(mediaId)
        }
        val selectLoop = select?.kind == LoopAwareMediaSourceFactory.SELECT_LOOP

        val sourceEvent = when (fault) {
            FAULT_SOURCE_PLAIN -> sourceEvaluator.onSample(
                SourceInvariantSample(
                    atMs = now,
                    windowActive = true,
                    itemHasLoopTag = false,
                    factoryLastSelectLoop = false,
                ),
            )
            FAULT_SOURCE_LOOP -> sourceEvaluator.onSample(
                SourceInvariantSample(
                    atMs = now,
                    windowActive = false,
                    itemHasLoopTag = true,
                    factoryLastSelectLoop = false,
                ),
            )
            else -> if (routeSource) {
                sourceEvaluator.onSample(
                    SourceInvariantSample(
                        atMs = now,
                        windowActive = windowActive,
                        itemHasLoopTag = itemHasLoopTag,
                        factoryLastSelectLoop = selectLoop,
                    ),
                )
            } else {
                null
            }
        }
        if (sourceEvent == null) {
            sourcePendingSamples = 0
        } else {
            sourcePendingSamples += 1
            if (sourcePendingSamples >= SOURCE_CONFIRM_SAMPLES) {
                sourcePendingSamples = 0
                report(sourceEvent)
            }
        }

        // ---- §12.2 A-025：prepared 后 continueLoading 驱动 ----
        val period = LoopInvariantProbes.periodSnapshot()
        val periodSample = if (fault == FAULT_PERIOD_CONTINUE_LOADING) {
            PeriodContinueLoadingSample(
                atMs = now,
                preparedAtMs = now - 1_000L,
                continueLoadingAfterPrepare = 0L,
                continueLoadingAtManualKick = 0L,
                manualKicks = 0L,
            )
        } else {
            PeriodContinueLoadingSample(
                atMs = now,
                preparedAtMs = period.preparedAtMs,
                continueLoadingAfterPrepare = period.continueLoadingAfterPrepare,
                continueLoadingAtManualKick = period.continueLoadingAtManualKick,
                manualKicks = period.manualKicks,
            )
        }
        reportIfAny(periodEvaluator.evaluate(periodSample))

        // ---- §12.2 A-037：extractor track / seekMap / endTracks ----
        val extractor = LoopInvariantProbes.extractorSnapshot()
        val extractorSample = if (fault == FAULT_EXTRACTOR_TRACK) {
            ExtractorTrackSample(
                atMs = now,
                generation = extractor.generation + 1L,
                tracksCreated = 2,
                endTracksSeen = true,
                seekMapBeforeEndTracks = true,
                trackAfterEndTracksCount = 1L,
                seekMapAfterEndTracksCount = 0L,
                seekMapMissingCount = 0L,
            )
        } else {
            ExtractorTrackSample(
                atMs = now,
                generation = extractor.generation,
                tracksCreated = extractor.tracksCreated,
                endTracksSeen = extractor.endTracksSeen,
                seekMapBeforeEndTracks = extractor.seekMapBeforeEndTracks,
                trackAfterEndTracksCount = extractor.trackAfterEndTracksCount,
                seekMapAfterEndTracksCount = extractor.seekMapAfterEndTracksCount,
                seekMapMissingCount = extractor.seekMapMissingCount,
            )
        }
        reportIfAny(extractorEvaluator.evaluate(extractorSample))

        // ---- §12.2 A-049：空 buffer 只在重放/暂停态 ----
        val empty = LoopInvariantProbes.emptyBufferSnapshot()
        val emptySample = if (fault == FAULT_EMPTY_BUFFER) {
            EmptyBufferSample(
                atMs = now,
                total = empty.total + 1L,
                whileReplaying = empty.whileReplaying,
                whilePaused = empty.whilePaused,
                whileActive = empty.whileActive + 1L,
            )
        } else {
            EmptyBufferSample(
                atMs = now,
                total = empty.total,
                whileReplaying = empty.whileReplaying,
                whilePaused = empty.whilePaused,
                whileActive = empty.whileActive,
            )
        }
        reportIfAny(emptyBufferEvaluator.evaluate(emptySample))

        // ---- §12.2 A-055 / A-NEW-09：configure / 窗口切分 / pending 重放 ----
        val configure = LoopInvariantProbes.configureSnapshot()
        val configureSample = if (fault == FAULT_CONFIGURE_SPLIT) {
            ConfigureSplitSample(
                atMs = now,
                configureCount = 0L,
                windowSplitCount = 1L,
                configurePtsJumpCount = 0L,
                lastConfigurePtsJumpUs = 0L,
                lastWindowSplitAtMs = now - 10_000L,
            )
        } else {
            ConfigureSplitSample(
                atMs = now,
                configureCount = configure.configureCount,
                windowSplitCount = windowSplitCount,
                configurePtsJumpCount = configure.configurePtsJumpCount,
                lastConfigurePtsJumpUs = configure.lastConfigurePtsJumpUs,
                lastWindowSplitAtMs = windowSplitAtMs,
            )
        }
        reportIfAny(configureSplitEvaluator.evaluate(configureSample))

        val configurePendingSample = if (fault == FAULT_CONFIGURE_NO_FLUSH) {
            ConfigurePendingReplaySample(
                atMs = now,
                configureCount = configure.configureCount,
                configureWithoutFlushCount = configure.configureWithoutFlushCount + 1L,
                pendingReplayDroppedCount = configure.pendingReplayDroppedCount + 1L,
                pendingReplayContinuedCount = configure.pendingReplayContinuedCount,
            )
        } else {
            ConfigurePendingReplaySample(
                atMs = now,
                configureCount = configure.configureCount,
                configureWithoutFlushCount = configure.configureWithoutFlushCount,
                pendingReplayDroppedCount = configure.pendingReplayDroppedCount,
                pendingReplayContinuedCount = configure.pendingReplayContinuedCount,
            )
        }
        reportIfAny(configurePendingReplayEvaluator.evaluate(configurePendingSample))

        // ---- §12.2 A-091：帧↔µs 往返 + 连续三圈 A/B 帧号差 ----
        val window = controller.window
        val sampleRate = controller.outputSampleRate
        val frameSample = if (fault == FAULT_FRAME_US) {
            FrameUsRoundtripSample(
                atMs = now,
                sampleRate = 48_000,
                frameDurationUs = 21L,
                roundtripErrorUs = 42L,
                abFrameDeltas = listOf(100L, 101L, 100L),
            )
        } else if (window != null && sampleRate > 0) {
            val signature = window.songId.toString() + ":" + window.loopStartUs + ":" +
                window.loopEndUs + ":" + sampleRate
            if (signature != abWindowSignature) {
                abWindowSignature = signature
                abDeltas.clear()
                seamCountAtLastSample = controller.seamCount
            }
            if (controller.seamCount != seamCountAtLastSample) {
                seamCountAtLastSample = controller.seamCount
                abDeltas.addLast(
                    AssumptionFrameUs.abFrameDelta(window.loopStartUs, window.loopEndUs, sampleRate),
                )
                while (abDeltas.size > 3) abDeltas.removeFirst()
            }
            FrameUsRoundtripSample(
                atMs = now,
                sampleRate = sampleRate,
                frameDurationUs = AssumptionFrameUs.frameDurationUs(sampleRate),
                roundtripErrorUs = AssumptionFrameUs.maxRoundtripErrorUs(
                    window.loopStartUs,
                    window.loopEndUs,
                    sampleRate,
                ),
                abFrameDeltas = abDeltas.toList(),
            )
        } else {
            null
        }
        frameSample?.let { reportIfAny(frameUsEvaluator.evaluate(it)) }

        // ---- §12.2 A-093：duration 三态投影 ----
        val durationMs = player.duration
        val durationUs = when {
            durationMs < 0L -> -1L
            durationMs == 0L -> 0L
            else -> durationMs * 1_000L
        }
        val projectedUs = LoopSessionProjection.durationUs(window, durationUs)
        val branch = if (projectedUs > 0L) DurationBranch.CLAMP else DurationBranch.FALLBACK
        val displayedPositionUs = if (window != null) {
            LoopSessionProjection.displayUs(controller.positionUs, window)
        } else {
            controller.positionUs
        }
        val projectionSample = if (fault == FAULT_PROJECTION_DURATION) {
            ProjectionDurationSample(
                atMs = now,
                windowActive = true,
                durationUs = 0L,
                branch = DurationBranch.FALLBACK,
                projectedUs = 0L,
                displayedPositionUs = 0L,
            )
        } else {
            ProjectionDurationSample(
                atMs = now,
                windowActive = window != null,
                durationUs = durationUs,
                branch = branch,
                projectedUs = projectedUs,
                displayedPositionUs = displayedPositionUs,
            )
        }
        reportIfAny(projectionDurationEvaluator.evaluate(projectionSample))

        // ---- §12.2 A-NEW-01：sink 位置域 ----
        val sinkPosition = LoopInvariantProbes.sinkPositionSnapshot()
        val positionSample = if (fault == FAULT_POSITION_DOMAIN) {
            val offset = 1_000_000_000_000L
            SinkPositionDomainSample(
                atMs = now,
                offsetUs = offset,
                lastDelegatePtsUs = offset + 5_000_000L,
                rawPositionUs = 5_000_000L,
                returnedPositionUs = 5_000_000L,
            )
        } else {
            SinkPositionDomainSample(
                atMs = now,
                offsetUs = sinkPosition.offsetUs,
                lastDelegatePtsUs = sinkPosition.lastDelegatePtsUs,
                rawPositionUs = sinkPosition.rawPositionUs,
                returnedPositionUs = sinkPosition.returnedPositionUs,
            )
        }
        reportIfAny(sinkPositionEvaluator.evaluate(positionSample))

        // ---- §12.2 A-NEW-03：同 ms seek 超时语义（UI overlay 仅在注入时模拟） ----
        if (fault == FAULT_SAME_POSITION_SEEK && samePositionSeekFaultRequestAtMs > 0L) {
            reportIfAny(
                samePositionSeekEvaluator.evaluate(
                    SamePositionSeekSample(
                        atMs = now,
                        requestAtMs = samePositionSeekFaultRequestAtMs,
                        requestedMs = 10_000L,
                        positionAtRequestMs = 10_000L,
                        positionNowMs = 10_000L,
                        discontinuitySeen = false,
                        elapsedSinceRequestMs = now - samePositionSeekFaultRequestAtMs,
                        overlayPending = true,
                    ),
                ),
            )
        }

        // ---- §12.2 A-NEW-07：prepare 错误必须在 3s 内变成 onPlayerError ----
        if (prepareErrorInjectedAtMs > 0L) {
            if (playerErrorAtMs > prepareErrorInjectedAtMs) {
                Log.i(
                    TAG,
                    "prepare error surfaced in " + (playerErrorAtMs - prepareErrorInjectedAtMs) + "ms",
                )
                prepareErrorInjectedAtMs = 0L
            } else {
                val event = prepareErrorEvaluator.evaluate(
                    PrepareErrorSample(
                        atMs = now,
                        injectedAtMs = prepareErrorInjectedAtMs,
                        playerErrorAtMs = playerErrorAtMs,
                    ),
                )
                if (event != null) {
                    report(event)
                    prepareErrorInjectedAtMs = 0L
                }
            }
        }

        // ---- §12.2 A-NEW-11：controller availableCommands ----
        val commandIds = conn.availableCommandIds()
        val notificationPresent = mediaNotificationPresent()
        if (commandIds != null) {
            if (lastCommandIds == null) controllerGeneration += 1L
            lastCommandIds = commandIds
            val availableNames = commandIds.mapNotNull { commandNames[it] }.toSet()
            val commandsSample = if (fault == FAULT_CONTROLLER_COMMANDS) {
                AvailableCommandsSample(
                    atMs = now,
                    controllerGeneration = controllerGeneration + 1L,
                    connected = true,
                    available = availableNames - "seek-to-next",
                )
            } else {
                AvailableCommandsSample(
                    atMs = now,
                    controllerGeneration = controllerGeneration,
                    connected = true,
                    available = availableNames,
                )
            }
            reportIfAny(availableCommandsEvaluator.evaluate(commandsSample))
        } else {
            lastCommandIds = null
        }

        // ---- §12.2 A-NEW-12：暂停后通知仍在 ----
        val notificationSample = if (fault == FAULT_NOTIFICATION) {
            ServiceNotificationSample(
                atMs = now,
                pausedForMs = 6_000L,
                notificationPresent = false,
            )
        } else {
            ServiceNotificationSample(
                atMs = now,
                pausedForMs = pausedForMs,
                notificationPresent = notificationPresent,
            )
        }
        reportIfAny(notificationEvaluator.evaluate(notificationSample))

        val sample = linkedMapOf<String, Any?>(
            "atMs" to now,
            "routeSource" to routeSource,
            "playWhenReady" to playWhenReady,
            "playbackState" to playbackState,
            "writtenFrames" to frames,
            "windowActive" to windowActive,
            "itemHasLoopTag" to itemHasLoopTag,
            "lastSelect" to select?.kind,
            "sourceViolationPending" to sourcePendingSamples,
            "faultInjection" to fault,
            "faultFrames" to faultFrames,
            "windowSplitCount" to windowSplitCount,
            "period" to linkedMapOf(
                "preparedAtMs" to period.preparedAtMs,
                "continueLoadingAfterPrepare" to period.continueLoadingAfterPrepare,
                "continueLoadingAtManualKick" to period.continueLoadingAtManualKick,
                "manualKicks" to period.manualKicks,
            ),
            "extractor" to linkedMapOf(
                "generation" to extractor.generation,
                "tracksCreated" to extractor.tracksCreated,
                "endTracksSeen" to extractor.endTracksSeen,
                "seekMapBeforeEndTracks" to extractor.seekMapBeforeEndTracks,
                "trackAfterEndTracksCount" to extractor.trackAfterEndTracksCount,
                "seekMapAfterEndTracksCount" to extractor.seekMapAfterEndTracksCount,
                "seekMapMissingCount" to extractor.seekMapMissingCount,
            ),
            "sink" to linkedMapOf(
                "configureCount" to configure.configureCount,
                "configureWithoutFlush" to configure.configureWithoutFlushCount,
                "pendingReplayDropped" to configure.pendingReplayDroppedCount,
                "configurePtsJumpCount" to configure.configurePtsJumpCount,
                "emptyActive" to empty.whileActive,
                "positionOffsetUs" to sinkPosition.offsetUs,
                "positionLastPtsUs" to sinkPosition.lastDelegatePtsUs,
                "positionRawUs" to sinkPosition.rawPositionUs,
                "positionReturnedUs" to sinkPosition.returnedPositionUs,
            ),
            "durationUs" to durationUs,
            "projectedDurationUs" to projectedUs,
            "notificationPresent" to notificationPresent,
            "pausedForMs" to pausedForMs,
            "controllerGeneration" to controllerGeneration,
            "prepareErrorInjectedAtMs" to prepareErrorInjectedAtMs,
        )
        lastSample = sample
        if (now - lastSampleLogMs >= SAMPLE_LOG_INTERVAL_MS) {
            lastSampleLogMs = now
            Log.d(TAG, "sample " + sample)
        }
    }

    /** 当前包自己的媒体通知是否仍在（媒体通知 1001 / 静默前台通知 2001）。 */
    private fun mediaNotificationPresent(): Boolean {
        val ctx = appContext ?: return false
        return try {
            val manager = ctx.getSystemService(NotificationManager::class.java) ?: return false
            manager.activeNotifications.any { status ->
                status.id == NOTIFICATION_ID_MEDIA3 ||
                    status.id == NOTIFICATION_ID_SILENT ||
                    status.notification.channelId == NOTIFICATION_CHANNEL_MEDIA3 ||
                    status.notification.channelId == NOTIFICATION_CHANNEL_SILENT
            }
        } catch (t: Throwable) {
            Log.w(TAG, "notification probe failed", t)
            false
        }
    }

    private fun reportIfAny(event: InvariantEvent?) {
        if (event != null) report(event)
    }

    private fun report(event: InvariantEvent) {
        val record = registry.record(event)
        try {
            Log.e(TAG, event.message + " count=" + record.count)
        } catch (_: Throwable) {
            // JVM/异常环境下 Log 不可用也不影响记录。
        }
    }
}
