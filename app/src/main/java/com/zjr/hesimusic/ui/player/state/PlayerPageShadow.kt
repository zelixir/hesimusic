package com.zjr.hesimusic.ui.player.state

import kotlin.math.abs

/**
 * 播放页状态机的**影子双跑**（第二阶段：只观测、不改行为）。
 *
 * 设计边界：
 * - 纯 Kotlin：不依赖 Android / Media3 / DB，可直接单测；
 * - 不执行 effect：reducer 产生的 effect 只收进 [ShadowObservation.effects]，
 *   由调试侧展示，绝不调用播放器；
 * - 不写业务字段：只维护自己的模型状态与差异计数；
 * - 真实运行时的接线（MusicViewModel）只在 debug 构建启用，见 [PlayerPageShadowGate]。
 */

/** 真实播放器快照里与状态机相关的字段（由 MusicViewModel 从 Media3 字段映射）。 */
data class PlayerSnapshot(
    val songId: Long? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val repeat: RepeatMode = RepeatMode.OFF,
    val shuffle: Boolean = false,
    val playbackState: SnapshotPlaybackState = SnapshotPlaybackState.IDLE,
    /** 播放器错误信息；null = 无错误。 */
    val errorMessage: String? = null,
    /**
     * 显式无缝意图（来自 LoopIntent.armed，而不是状态推导）。
     *
     * 注意：previousRepeat/previousShuffle 是退出时的恢复元数据，不是界面显示字段；
     * [diff] 只比较 seamless 的存在性与 songId，不比较这两个恢复值。
     */
    val seamlessIntent: SeamlessIntent? = null,
    /** UI 本地拖拽位置；VM 通常为 null，单测/后续接 UI 时可填。 */
    val dragMs: Long? = null,
    /** 松手后的 pendingSeek 目标；VM 通常为 null。 */
    val pendingSeekTargetMs: Long? = null,
    /** pendingSeek 的松手时刻（毫秒时钟）。 */
    val pendingSeekSinceMs: Long? = null,
    /** 本次观测的时钟（elapsedRealtime 或测试时钟）。 */
    val nowMs: Long = 0L,
)

/** Media3 Player.STATE_* 的纯 Kotlin 映射（ERROR 由 errorMessage/异常推导）。 */
enum class SnapshotPlaybackState {
    IDLE,
    BUFFERING,
    READY,
    ENDED,
    ERROR,
}

/**
 * 把真实播放器快照映射成状态机状态。
 *
 * 映射口径：
 * - 有拖拽/ pendingSeek 字段时优先映射为 [PlayerPageState.Seeking]；
 * - 有 errorMessage（或 state=ERROR）→ [PlayerPageState.Error]；
 * - ENDED → [PlayerPageState.Ended]；
 * - IDLE 且无歌 → [PlayerPageState.Idle]；
 * - BUFFERING / READY → 按 isPlaying 映射 Playing/Paused。
 *   （状态机的 Buffering 是“已请求播放但位置未推进”的短暂态；当前没有
 *   BufferStarted/BufferReady 事件，真实运行时用 isPlaying 语义近似，避免影子
 *   在每次网络缓冲时制造无法表达的差异。）
 * - seamless 只在“armed 且当前歌匹配且实际模式已是 ONE/false”时进入 mode，
 *   保证投影本身满足状态机的不变量。
 */
fun project(viewModelSnapshot: PlayerSnapshot): PlayerPageState {
    val songId = viewModelSnapshot.songId
    // 状态机的全局不变量：shuffle=true ⇒ repeat != ONE。真实播放器在“写 repeat=ONE、
    // 再关 shuffle”的中间帧可能短暂出现该组合；投影按状态机的归一化口径（关 shuffle）
    // 表示，避免投影本身非法、也避免把服务层的写序当成模型差异。
    val repeat = viewModelSnapshot.repeat
    val shuffle = viewModelSnapshot.shuffle && repeat != RepeatMode.ONE
    val seamless = viewModelSnapshot.seamlessIntent?.takeIf { intent ->
        songId != null &&
            intent.songId == songId &&
            repeat == RepeatMode.ONE &&
            !shuffle
    }
    val mode = PlayMode(
        repeat = repeat,
        shuffle = shuffle,
        seamless = seamless,
    )
    val resume = if (viewModelSnapshot.isPlaying) ResumeTarget.PLAYING else ResumeTarget.PAUSED
    val position = viewModelSnapshot.positionMs.coerceAtLeast(0L)

    viewModelSnapshot.dragMs?.let { drag ->
        val dragMs = drag.coerceAtLeast(0L)
        return PlayerPageState.Seeking(
            dragMs = dragMs,
            targetMs = dragMs,
            pendingSince = null,
            mode = mode,
            resume = resume,
        )
    }
    viewModelSnapshot.pendingSeekTargetMs?.let { target ->
        val targetMs = target.coerceAtLeast(0L)
        return PlayerPageState.Seeking(
            dragMs = targetMs,
            targetMs = targetMs,
            pendingSince = (viewModelSnapshot.pendingSeekSinceMs ?: viewModelSnapshot.nowMs).coerceAtLeast(0L),
            mode = mode,
            resume = resume,
        )
    }

    val error = viewModelSnapshot.errorMessage
    if (error != null) return PlayerPageState.Error(error, mode)

    return when (viewModelSnapshot.playbackState) {
        SnapshotPlaybackState.ENDED -> PlayerPageState.Ended(mode)
        SnapshotPlaybackState.ERROR -> PlayerPageState.Error(DEFAULT_ERROR, mode)
        SnapshotPlaybackState.IDLE ->
            if (songId == null) {
                PlayerPageState.Idle
            } else {
                PlayerPageState.Paused(position, mode)
            }

        SnapshotPlaybackState.BUFFERING,
        SnapshotPlaybackState.READY ->
            if (viewModelSnapshot.isPlaying) {
                PlayerPageState.Playing(position, mode)
            } else {
                PlayerPageState.Paused(position, mode)
            }
    }
}

/**
 * 字段级差异描述：
 * - 状态类型不同 → 一条 "state: expected=..., actual=..."；
 * - 同类型 → 逐字段输出 "field: expected=..., actual=..."；
 * - 位置可配置容差（默认 0，接 UI 前先严格）；
 * - seamless 只比较存在性与 songId；previousRepeat/previousShuffle 是恢复元数据，不参与显示差异。
 */
fun diff(
    expected: PlayerPageState,
    fromModel: PlayerPageState,
    positionToleranceMs: Long = 0L,
): List<String> {
    if (expected::class != fromModel::class) {
        return listOf(
            "state: expected=" + expected.diffLabel() + ", actual=" + fromModel.diffLabel(),
        )
    }

    val out = mutableListOf<String>()
    fun compare(field: String, expectedValue: Any?, actualValue: Any?) {
        if (expectedValue != actualValue) {
            out += field + ": expected=" + expectedValue + ", actual=" + actualValue
        }
    }
    fun comparePosition(field: String, expectedValue: Long, actualValue: Long) {
        if (abs(expectedValue - actualValue) > positionToleranceMs) {
            out += field + ": expected=" + expectedValue + ", actual=" + actualValue
        }
    }

    val expectedMode = PlayerPageReducer.modeOf(expected)
    val actualMode = PlayerPageReducer.modeOf(fromModel)
    if (expectedMode != null && actualMode != null) {
        compare("mode.repeat", expectedMode.repeat, actualMode.repeat)
        compare("mode.shuffle", expectedMode.shuffle, actualMode.shuffle)
        compare("mode.seamless.songId", expectedMode.seamless?.songId, actualMode.seamless?.songId)
    }

    when (expected) {
        is PlayerPageState.Playing -> {
            val actual = fromModel as PlayerPageState.Playing
            comparePosition("positionMs", expected.positionMs, actual.positionMs)
        }

        is PlayerPageState.Paused -> {
            val actual = fromModel as PlayerPageState.Paused
            comparePosition("positionMs", expected.positionMs, actual.positionMs)
        }

        is PlayerPageState.Seeking -> {
            val actual = fromModel as PlayerPageState.Seeking
            compare("dragMs", expected.dragMs, actual.dragMs)
            compare("targetMs", expected.targetMs, actual.targetMs)
            compare("pendingSince", expected.pendingSince, actual.pendingSince)
            compare("resume", expected.resume, actual.resume)
        }

        is PlayerPageState.Buffering -> {
            val actual = fromModel as PlayerPageState.Buffering
            comparePosition("positionMs", expected.positionMs, actual.positionMs)
        }

        is PlayerPageState.Ended -> Unit
        is PlayerPageState.Error -> {
            val actual = fromModel as PlayerPageState.Error
            compare("message", expected.message, actual.message)
        }

        is PlayerPageState.Idle -> Unit
    }

    return out
}

/**
 * 影子跑测器：把快照之间的变化翻译成事件喂给 reducer，再与 [project] 对比。
 *
 * 事件订阅口径：
 * - 切歌 → TrackChanged；进出无缝 → EnterSeamless/ExitSeamless；
 * - isPlaying 变化 → Play/Pause；repeat/shuffle 变化 → SetRepeat/SetShuffle；
 * - ENDED → PlaybackEnded；错误出现 → PlaybackError；
 * - 每次观测末尾 → PositionTick（显式 seek 后的 2s 抑制窗口内除外）。
 *
 * effect 只记录到 [ShadowObservation.effects]，**从不执行**。
 */
class PlayerPageShadow(
    initialState: PlayerPageState = PlayerPageState.Idle,
) {

    companion object {
        /** seek 后 MediaController 快照收敛窗口（与 MusicViewModel 的 2s 刷新窗口一致）。 */
        const val SEEK_SUPPRESS_MS = 2_000L
    }

    var state: PlayerPageState = initialState
        private set

    var mismatchCount: Long = 0L
        private set

    var suppressedObservationCount: Long = 0L
        private set

    var lastMismatches: List<String> = emptyList()
        private set

    var lastObservation: ShadowObservation? = null
        private set

    private var lastSnapshot: PlayerSnapshot? = null
    private var initialized = false
    private var suppressComparisonUntilMs = Long.MIN_VALUE

    fun reset(initialState: PlayerPageState = PlayerPageState.Idle) {
        state = initialState
        lastSnapshot = null
        initialized = false
        suppressComparisonUntilMs = Long.MIN_VALUE
        mismatchCount = 0L
        suppressedObservationCount = 0L
        lastMismatches = emptyList()
        lastObservation = null
    }

    /**
     * 记录一次显式 seek 请求。
     *
     * MediaController.seekTo 之后立即读到的位置通常还是旧快照（00001 的根因），
     * 因此 seek 后 2s 内只更新模型、不做对比，避免把“快照未收敛”误报成状态机差异。
     *
     * @return false = 当前模型状态不允许 Seek（Ended/Error/Idle/Seeking）。
     */
    fun onSeekRequested(positionMs: Long, nowMs: Long): Boolean {
        if (!initialized) return false
        val event = PlayerPageEvent.Seek(positionMs.coerceAtLeast(0L))
        if (!PlayerPageReducer.allowed(state, event)) return false
        apply(event, nowMs)
        suppressComparisonUntilMs = nowMs + SEEK_SUPPRESS_MS
        return true
    }

    /** 喂入一次真实播放器快照；返回投影、模型、差异与（未执行的）effect。 */
    fun observe(snapshot: PlayerSnapshot): ShadowObservation {
        val projected = project(snapshot)
        if (!initialized) {
            state = projected
            initialized = true
            lastSnapshot = snapshot
            return finish(
                expected = projected,
                mismatches = emptyList(),
                effects = emptyList(),
                suppressed = false,
                observedAtMs = snapshot.nowMs,
            )
        }

        val previous = lastSnapshot
        val effects = mutableListOf<PlayerPageEffect>()
        val nowMs = snapshot.nowMs
        val suppressed = nowMs < suppressComparisonUntilMs
        var structuralChange = false

        fun applyObserved(event: PlayerPageEvent, structural: Boolean = false) {
            if (structural) structuralChange = true
            apply(event, nowMs, effects)
        }

        if (snapshot.songId == null) {
            // 播放停止/清空：以投影为起点（通常是 Idle；带错误时可直接进入 Error），
            // 不把“停止”当成一个未建模的 TrackChanged(0) 事件。
            state = projected
        } else if (state is PlayerPageState.Idle) {
            // 首次装歌（或播放停止后重新装歌）：以投影为起点，不把“加载中”的瞬态
            // 当成 TrackChanged 的语义差异。后续切歌仍走 TrackChanged。
            state = projected
        } else {
            if (previous == null || snapshot.songId != previous.songId) {
                applyObserved(
                    PlayerPageEvent.TrackChanged(
                        songId = snapshot.songId,
                        hasLoopIntent = snapshot.seamlessIntent != null,
                    ),
                    structural = true,
                )
            }

            val errorNow = snapshot.errorMessage
            val errorBefore = previous?.errorMessage
            val endedNow = snapshot.playbackState == SnapshotPlaybackState.ENDED
            val endedBefore = previous?.playbackState == SnapshotPlaybackState.ENDED

            // 进入无缝必须排在 repeat/shuffle 写入之前，才能记录“进入前”的原值。
            snapshot.seamlessIntent?.let { intent ->
                if (PlayerPageReducer.modeOf(state)?.seamless == null) {
                    applyObserved(PlayerPageEvent.EnterSeamless(intent.songId), structural = true)
                }
            }

            if (!endedNow && errorNow == null && previous != null && snapshot.isPlaying != previous.isPlaying) {
                applyObserved(
                    if (snapshot.isPlaying) PlayerPageEvent.Play else PlayerPageEvent.Pause,
                )
            }

            // 用与 project 相同的归一化口径喂模式事件，避免把播放器“先写 ONE 再关 shuffle”
            // 的中间帧翻译成 shuffle=true（那会让模型再归一到 ALL）。
            val targetRepeat = snapshot.repeat
            val targetShuffle = snapshot.shuffle && snapshot.repeat != RepeatMode.ONE
            if (previous != null && targetRepeat != previous.repeat) {
                applyObserved(PlayerPageEvent.SetRepeat(targetRepeat), structural = true)
            }
            if (previous != null && targetShuffle != (previous.shuffle && previous.repeat != RepeatMode.ONE)) {
                applyObserved(PlayerPageEvent.SetShuffle(targetShuffle), structural = true)
            }

            // 模式事件已经先按用户语义退出无缝；若仍未退出（例如 debug clear），补一次退出。
            if (snapshot.seamlessIntent == null && PlayerPageReducer.modeOf(state)?.seamless != null) {
                applyObserved(PlayerPageEvent.ExitSeamless("loop-intent-cleared"), structural = true)
            }

            if (errorNow != null && errorNow != errorBefore) {
                applyObserved(PlayerPageEvent.PlaybackError(errorNow))
            }
            if (endedNow && !endedBefore && state !is PlayerPageState.Error) {
                applyObserved(PlayerPageEvent.PlaybackEnded)
            }
            if (!suppressed && errorNow == null && !endedNow) {
                applyObserved(PlayerPageEvent.PositionTick(snapshot.positionMs.coerceAtLeast(0L)))
            }
        }

        lastSnapshot = snapshot
        if (suppressed) {
            suppressedObservationCount++
            return finish(
                expected = projected,
                mismatches = emptyList(),
                effects = effects,
                suppressed = true,
                observedAtMs = nowMs,
            )
        }

        val mismatches = diff(projected, state)
        if (mismatches.isNotEmpty() && structuralChange) {
            // 切歌/进出无缝/模式写序的中间帧允许“结算一帧”：下一帧稳定观测再计入差异，
            // 避免把服务层写序造成的瞬态误报成状态机 bug。持续差异仍会在下一帧被捕获。
            suppressedObservationCount++
            return finish(
                expected = projected,
                mismatches = emptyList(),
                effects = effects,
                suppressed = true,
                observedAtMs = nowMs,
            )
        }
        return finish(projected, mismatches, effects, suppressed = false, observedAtMs = nowMs)
    }

    private fun apply(
        event: PlayerPageEvent,
        nowMs: Long,
        effects: MutableList<PlayerPageEffect>? = null,
    ) {
        when (val transition = PlayerPageReducer.reduce(state, event, nowMs)) {
            is com.zjr.hesimusic.core.statemachine.Transition.Applied -> {
                effects?.addAll(transition.effects)
                state = transition.state
            }

            is com.zjr.hesimusic.core.statemachine.Transition.Rejected -> {
                state = transition.state
            }
        }
    }

    private fun finish(
        expected: PlayerPageState,
        mismatches: List<String>,
        effects: List<PlayerPageEffect>,
        suppressed: Boolean,
        observedAtMs: Long,
    ): ShadowObservation {
        val observation = ShadowObservation(
            expected = expected,
            model = state,
            mismatches = mismatches,
            effects = effects,
            suppressed = suppressed,
            observedAtMs = observedAtMs,
        )
        lastObservation = observation
        if (mismatches.isNotEmpty()) {
            mismatchCount++
            lastMismatches = mismatches
        }
        return observation
    }
}

/** 一次影子观测结果。effects 只是记录，从未执行。 */
data class ShadowObservation(
    val expected: PlayerPageState,
    val model: PlayerPageState,
    val mismatches: List<String>,
    val effects: List<PlayerPageEffect>,
    val suppressed: Boolean,
    val observedAtMs: Long,
)

/**
 * debug 构建开关。
 *
 * main 源集不引用 debug 源集（release 无 DebugHooks）；由 debug 源集的
 * DebugInitProvider 在进程启动时置 true，release 恒为 false。
 */
object PlayerPageShadowGate {
    @Volatile
    var enabled: Boolean = false
}

/**
 * 影子双跑诊断计数器；debug 服务器的 /state.playback.shadow 读取这里。
 * 全部 @Volatile，允许主线程写、server 线程读。
 */
object PlayerPageShadowDiagnostics {

    @Volatile
    var shadowMismatchCount: Long = 0L
        private set

    @Volatile
    var shadowSuppressedCount: Long = 0L
        private set

    @Volatile
    var lastMismatches: List<String> = emptyList()
        private set

    @Volatile
    var lastExpected: String? = null
        private set

    @Volatile
    var lastActual: String? = null
        private set

    @Volatile
    var lastObservedAtMs: Long = 0L
        private set

    fun record(observation: ShadowObservation, fallbackNowMs: Long = observation.observedAtMs) {
        lastObservedAtMs = if (observation.observedAtMs != 0L) observation.observedAtMs else fallbackNowMs
        lastExpected = observation.expected.toString()
        lastActual = observation.model.toString()
        if (observation.suppressed) {
            shadowSuppressedCount++
        } else if (observation.mismatches.isNotEmpty()) {
            shadowMismatchCount++
            lastMismatches = observation.mismatches
        }
    }

    fun reset() {
        shadowMismatchCount = 0L
        shadowSuppressedCount = 0L
        lastMismatches = emptyList()
        lastExpected = null
        lastActual = null
        lastObservedAtMs = 0L
    }

    /** JSON 友好的诊断快照（debug /state 使用）。 */
    fun snapshotMap(): Map<String, Any?> = linkedMapOf(
        "enabled" to PlayerPageShadowGate.enabled,
        "mismatchCount" to shadowMismatchCount,
        // 兼容任务里约定的计数器名；/state 消费者两个键都能读。
        "shadowMismatchCount" to shadowMismatchCount,
        "suppressedCount" to shadowSuppressedCount,
        "lastMismatches" to lastMismatches.take(5),
        "lastExpected" to lastExpected,
        "lastActual" to lastActual,
        "lastObservedAtMs" to lastObservedAtMs,
    )
}

private const val DEFAULT_ERROR = "playback error"

private fun PlayerPageState.diffLabel(): String = this::class.simpleName ?: toString()
