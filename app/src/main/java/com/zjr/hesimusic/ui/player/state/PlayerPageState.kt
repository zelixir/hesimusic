package com.zjr.hesimusic.ui.player.state

import com.zjr.hesimusic.core.statemachine.Invariant
import com.zjr.hesimusic.core.statemachine.SmEffect
import com.zjr.hesimusic.core.statemachine.SmEvent
import com.zjr.hesimusic.core.statemachine.SmState
import com.zjr.hesimusic.core.statemachine.Transition
import kotlin.math.abs

/**
 * 播放页的**纯模型**（第一轮：只做模型与矩阵测试，不接 UI）。
 *
 * 把原先散落在 PlayerScreen / MusicViewModel 里的
 * isPlaying / repeatMode / shuffleModeEnabled / currentPosition / draggingMs /
 * pendingSeekMs / seamless loop window 收敛成一个不可变状态；
 * 所有副作用只以 [PlayerPageEffect] 声明，由 [PlayerPageReducer] 纯函数计算。
 *
 * 本文件禁止依赖 Android / Media3 / DB。
 */

/** 与 Media3 Player.REPEAT_MODE_* 一一对应的播放模式。 */
enum class RepeatMode {
    OFF,
    ONE,
    ALL,
}

/**
 * 无缝循环意图（显式意图驱动，对应 00004）。
 *
 * 只有**显式进入**（菜单「无缝循环」/ 分析页「确认并保存」）才会创建；
 * 创建时记录进入前的 repeat/shuffle，退出时原样恢复。
 */
data class SeamlessIntent(
    val songId: Long,
    val previousRepeat: RepeatMode,
    val previousShuffle: Boolean,
)

/**
 * 播放页展示的播放模式 = 实际播放器模式的唯一事实源。
 *
 * 不变量（见 [PlayerPageReducer.INVARIANTS]）：
 * - [seamless] != null 时必须 repeat=ONE 且 shuffle=false；
 * - [seamless] == null 时不允许出现 shuffle=true + repeat=ONE 的自相矛盾组合。
 */
data class PlayMode(
    val repeat: RepeatMode = RepeatMode.OFF,
    val shuffle: Boolean = false,
    val seamless: SeamlessIntent? = null,
) {
    companion object {
        val DEFAULT = PlayMode()
    }
}

/** 从 [PlayerPageState.Seeking] 收敛后要回到的传输状态。 */
enum class ResumeTarget { PLAYING, PAUSED }

/**
 * 播放页状态。
 *
 * 核心状态与字段按任务约定；为满足「Seeking 收敛后回到正确传输状态」与
 * 「任何状态下都能校验 mode 不变量」，[PlayerPageState.Seeking] 额外携带
 * mode / resume，[PlayerPageState.Buffering]/[PlayerPageState.Ended]/
 * [PlayerPageState.Error] 额外携带 mode（均有默认值，便于构造与兼容）。
 *
 * [PlayerPageState.Seeking] 的 [PlayerPageState.Seeking.pendingSince]：
 * - null = 手指还在拖（只改本地 dragMs，不下发 seek）；
 * - 非 null = 已松手，处于 pendingSeek 覆盖层，直到 PositionTick 收敛或超时。
 */
sealed interface PlayerPageState : SmState {
    /** 没有当前歌曲 / 尚未开始播放。 */
    data object Idle : PlayerPageState

    /** 正在播放。 */
    data class Playing(
        val positionMs: Long,
        val mode: PlayMode,
    ) : PlayerPageState

    /** 已暂停。 */
    data class Paused(
        val positionMs: Long,
        val mode: PlayMode,
    ) : PlayerPageState

    /**
     * 进度条拖拽 / 松手后的 pendingSeek 覆盖层。
     *
     * - [dragMs]：滑块当前应显示的本地位置；
     * - [targetMs]：松手提交的目标（拖拽中等于 dragMs）；
     * - [pendingSince]：松手时刻（null 表示仍在拖拽）；
     * - [resume]：收敛/超时后回到 Playing 还是 Paused。
     */
    data class Seeking(
        val dragMs: Long,
        val targetMs: Long,
        val pendingSince: Long?,
        val mode: PlayMode,
        val resume: ResumeTarget,
    ) : PlayerPageState

    /** 已请求播放但位置尚未推进（加载/缓冲中）。 */
    data class Buffering(
        val positionMs: Long = 0L,
        val mode: PlayMode = PlayMode.DEFAULT,
    ) : PlayerPageState

    /** 自然播放结束。 */
    data class Ended(
        val mode: PlayMode = PlayMode.DEFAULT,
    ) : PlayerPageState

    /** 播放错误。 */
    data class Error(
        val message: String,
        val mode: PlayMode = PlayMode.DEFAULT,
    ) : PlayerPageState
}

/** 播放页事件（UI 只派发事件，不直接改状态）。 */
sealed interface PlayerPageEvent : SmEvent {
    data object Play : PlayerPageEvent
    data object Pause : PlayerPageEvent
    data object Toggle : PlayerPageEvent

    /** 按下进度条：进入 Seeking，只更新本地 dragMs，不下发 seek（00001）。 */
    data object DragStart : PlayerPageEvent

    /** 拖拽中：只更新本地 dragMs。 */
    data class DragMove(val positionMs: Long) : PlayerPageEvent

    /** 松手：提交唯一一次 seek，并进入 pendingSeek 覆盖层（00001）。 */
    data class DragEnd(val targetMs: Long) : PlayerPageEvent

    /** 程序化 seek（按钮/点击轨道等）。 */
    data class Seek(val positionMs: Long) : PlayerPageEvent

    data class SetRepeat(val repeat: RepeatMode) : PlayerPageEvent
    data class SetShuffle(val enabled: Boolean) : PlayerPageEvent

    /**
     * 播放器切歌（MediaItem transition）。
     *
     * @param songId 新歌 id；<= 0 视为非法事件（保持原状态）
     * @param hasLoopIntent 新歌是否已有显式无缝意图：有则重新武装，无则退出并恢复原模式
     */
    data class TrackChanged(
        val songId: Long,
        val hasLoopIntent: Boolean,
    ) : PlayerPageEvent

    data class PositionTick(val positionMs: Long) : PlayerPageEvent

    data class PlaybackError(val message: String) : PlayerPageEvent

    /** 显式进入无缝循环（菜单 / 分析页确认保存）；记录进入前的 repeat/shuffle。 */
    data class EnterSeamless(val songId: Long) : PlayerPageEvent

    /** 退出无缝循环并恢复进入前的 repeat/shuffle。 */
    data class ExitSeamless(val reason: String) : PlayerPageEvent

    /** 自然播放结束（对应 Media3 STATE_ENDED；Ended 状态的唯一入边）。 */
    data object PlaybackEnded : PlayerPageEvent
}

/** 播放页要执行的副作用（由接 UI 层的 effect runner 执行；模型自身不执行）。 */
sealed interface PlayerPageEffect : SmEffect {
    data object PlayerPlay : PlayerPageEffect
    data object PlayerPause : PlayerPageEffect
    data class SeekTo(val positionMs: Long) : PlayerPageEffect
    data class SetRepeat(val repeat: RepeatMode) : PlayerPageEffect
    data class SetShuffle(val enabled: Boolean) : PlayerPageEffect
    data class ShowToast(val message: String) : PlayerPageEffect
}

/**
 * 播放页 reducer：**纯函数**，不碰 Android / Media3 / DB。
 *
 * 约定：
 * - 未声明的组合一律返回 [Transition.Rejected] 且**返回同一个 state 实例**；
 * - 允许的事件返回 [Transition.Applied]，effect 有序；
 * - 时间相关规则（pendingSeek 1.5s 超时）通过显式 [nowMs] 注入，默认 0，
 *   保证纯函数可测；接 UI 时传 SystemClock.elapsedRealtime()。
 */
object PlayerPageReducer {

    /** pendingSeek 收敛阈值（00001 修复口径：±1.2s）。 */
    const val SEEK_CONVERGENCE_MS = 1_200L

    /** pendingSeek 覆盖层最长保留时间（00001 修复口径：1.5s）。 */
    const val PENDING_SEEK_TIMEOUT_MS = 1_500L

    /**
     * 状态不变量。矩阵测试会校验：
     * 1) 每个声明的代表状态本身合法；2) 每次 transition 后的状态合法。
     */
    val INVARIANTS: List<Invariant<PlayerPageState>> = listOf(
        Invariant { state -> positionProblems(state).ifEmpty { null }?.joinToString("；") },
        Invariant { state -> modeProblems(state).ifEmpty { null }?.joinToString("；") },
    )

    /** 状态里携带的播放模式；Idle 没有当前歌，返回 null。 */
    fun modeOf(state: PlayerPageState): PlayMode? = when (state) {
        is PlayerPageState.Idle -> null
        is PlayerPageState.Playing -> state.mode
        is PlayerPageState.Paused -> state.mode
        is PlayerPageState.Seeking -> state.mode
        is PlayerPageState.Buffering -> state.mode
        is PlayerPageState.Ended -> state.mode
        is PlayerPageState.Error -> state.mode
    }

    /**
     * 显式「该状态 × 该事件」白名单。
     *
     * 实现对所有 sealed 事件类型做**穷尽 when**（不写 else）：新增事件类型会编译失败，
     * 强制同步本矩阵与 [PlayerPageStateMachine] 的 events 列表。
     */
    fun allowed(state: PlayerPageState, event: PlayerPageEvent): Boolean = when (state) {
        is PlayerPageState.Idle -> idleAllowed(event)
        is PlayerPageState.Playing -> playingAllowed(state.mode, event)
        is PlayerPageState.Paused -> pausedAllowed(state.mode, event)
        is PlayerPageState.Seeking -> seekingAllowed(state, event)
        is PlayerPageState.Buffering -> bufferingAllowed(state.mode, event)
        is PlayerPageState.Ended -> endedAllowed(event)
        is PlayerPageState.Error -> endedAllowed(event)
    }

    fun reduce(
        state: PlayerPageState,
        event: PlayerPageEvent,
        nowMs: Long = 0L,
    ): Transition<PlayerPageState, PlayerPageEffect> = when (state) {
        is PlayerPageState.Idle -> reduceIdle(state, event)
        is PlayerPageState.Playing -> reducePlaying(state, event)
        is PlayerPageState.Paused -> reducePaused(state, event)
        is PlayerPageState.Seeking -> reduceSeeking(state, event, nowMs)
        is PlayerPageState.Buffering -> reduceBuffering(state, event)
        is PlayerPageState.Ended -> reduceEnded(state, event)
        is PlayerPageState.Error -> reduceError(state, event)
    }

    // ------------------------------------------------------------------ allowed

    private fun idleAllowed(event: PlayerPageEvent): Boolean = when (event) {
        is PlayerPageEvent.TrackChanged -> event.songId > 0L
        PlayerPageEvent.Play,
        PlayerPageEvent.Pause,
        PlayerPageEvent.Toggle,
        PlayerPageEvent.DragStart,
        is PlayerPageEvent.DragMove,
        is PlayerPageEvent.DragEnd,
        is PlayerPageEvent.Seek,
        is PlayerPageEvent.SetRepeat,
        is PlayerPageEvent.SetShuffle,
        is PlayerPageEvent.PositionTick,
        is PlayerPageEvent.PlaybackError,
        is PlayerPageEvent.EnterSeamless,
        is PlayerPageEvent.ExitSeamless,
        PlayerPageEvent.PlaybackEnded -> false
    }

    private fun playingAllowed(mode: PlayMode, event: PlayerPageEvent): Boolean = when (event) {
        PlayerPageEvent.Play,
        PlayerPageEvent.Pause,
        PlayerPageEvent.Toggle,
        PlayerPageEvent.DragStart,
        is PlayerPageEvent.Seek,
        is PlayerPageEvent.PositionTick,
        is PlayerPageEvent.PlaybackError,
        PlayerPageEvent.PlaybackEnded -> true

        is PlayerPageEvent.DragMove,
        is PlayerPageEvent.DragEnd -> false

        is PlayerPageEvent.SetRepeat,
        is PlayerPageEvent.SetShuffle -> true

        is PlayerPageEvent.TrackChanged -> event.songId > 0L
        is PlayerPageEvent.EnterSeamless -> mode.seamless == null && event.songId > 0L
        is PlayerPageEvent.ExitSeamless -> mode.seamless != null
    }

    private fun pausedAllowed(mode: PlayMode, event: PlayerPageEvent): Boolean = when (event) {
        PlayerPageEvent.Play,
        PlayerPageEvent.Pause,
        PlayerPageEvent.Toggle,
        PlayerPageEvent.DragStart,
        is PlayerPageEvent.Seek,
        is PlayerPageEvent.PositionTick,
        is PlayerPageEvent.PlaybackError -> true

        is PlayerPageEvent.DragMove,
        is PlayerPageEvent.DragEnd,
        PlayerPageEvent.PlaybackEnded -> false

        is PlayerPageEvent.SetRepeat,
        is PlayerPageEvent.SetShuffle -> true

        is PlayerPageEvent.TrackChanged -> event.songId > 0L
        is PlayerPageEvent.EnterSeamless -> mode.seamless == null && event.songId > 0L
        is PlayerPageEvent.ExitSeamless -> mode.seamless != null
    }

    private fun seekingAllowed(state: PlayerPageState.Seeking, event: PlayerPageEvent): Boolean = when (event) {
        is PlayerPageEvent.DragMove,
        is PlayerPageEvent.DragEnd -> state.pendingSince == null

        is PlayerPageEvent.TrackChanged -> event.songId > 0L
        is PlayerPageEvent.PositionTick,
        is PlayerPageEvent.PlaybackError -> true

        PlayerPageEvent.Play,
        PlayerPageEvent.Pause,
        PlayerPageEvent.Toggle,
        PlayerPageEvent.DragStart,
        PlayerPageEvent.PlaybackEnded,
        is PlayerPageEvent.Seek,
        is PlayerPageEvent.SetRepeat,
        is PlayerPageEvent.SetShuffle,
        is PlayerPageEvent.EnterSeamless,
        is PlayerPageEvent.ExitSeamless -> false
    }

    private fun bufferingAllowed(mode: PlayMode, event: PlayerPageEvent): Boolean = when (event) {
        PlayerPageEvent.Play,
        PlayerPageEvent.Pause,
        PlayerPageEvent.Toggle,
        is PlayerPageEvent.PositionTick,
        is PlayerPageEvent.PlaybackError -> true

        PlayerPageEvent.DragStart,
        PlayerPageEvent.PlaybackEnded,
        is PlayerPageEvent.DragMove,
        is PlayerPageEvent.DragEnd,
        is PlayerPageEvent.Seek -> false

        is PlayerPageEvent.SetRepeat,
        is PlayerPageEvent.SetShuffle -> true

        is PlayerPageEvent.TrackChanged -> event.songId > 0L
        is PlayerPageEvent.EnterSeamless -> mode.seamless == null && event.songId > 0L
        is PlayerPageEvent.ExitSeamless -> mode.seamless != null
    }

    /** 规则 5：Ended / Error 下除 Play / TrackChanged 外全部拒绝。 */
    private fun endedAllowed(event: PlayerPageEvent): Boolean = when (event) {
        PlayerPageEvent.Play -> true
        is PlayerPageEvent.TrackChanged -> event.songId > 0L

        PlayerPageEvent.Pause,
        PlayerPageEvent.Toggle,
        PlayerPageEvent.DragStart,
        PlayerPageEvent.PlaybackEnded,
        is PlayerPageEvent.DragMove,
        is PlayerPageEvent.DragEnd,
        is PlayerPageEvent.Seek,
        is PlayerPageEvent.SetRepeat,
        is PlayerPageEvent.SetShuffle,
        is PlayerPageEvent.PositionTick,
        is PlayerPageEvent.PlaybackError,
        is PlayerPageEvent.EnterSeamless,
        is PlayerPageEvent.ExitSeamless -> false
    }

    // ------------------------------------------------------------------ reducer

    private fun reduceIdle(
        state: PlayerPageState.Idle,
        event: PlayerPageEvent,
    ): Transition<PlayerPageState, PlayerPageEffect> = when (event) {
        is PlayerPageEvent.TrackChanged ->
            if (event.songId > 0L) trackChanged(state, event) else reject(state, event)

        else -> reject(state, event)
    }

    private fun reducePlaying(
        state: PlayerPageState.Playing,
        event: PlayerPageEvent,
    ): Transition<PlayerPageState, PlayerPageEffect> = when (event) {
        PlayerPageEvent.Play -> applied(state)
        PlayerPageEvent.Pause -> applied(
            PlayerPageState.Paused(state.positionMs, state.mode),
            listOf(PlayerPageEffect.PlayerPause),
        )
        PlayerPageEvent.Toggle -> applied(
            PlayerPageState.Paused(state.positionMs, state.mode),
            listOf(PlayerPageEffect.PlayerPause),
        )

        PlayerPageEvent.DragStart -> applied(
            PlayerPageState.Seeking(
                dragMs = state.positionMs,
                targetMs = state.positionMs,
                pendingSince = null,
                mode = state.mode,
                resume = ResumeTarget.PLAYING,
            ),
        )

        is PlayerPageEvent.DragMove -> reject(state, event)
        is PlayerPageEvent.DragEnd -> reject(state, event)

        is PlayerPageEvent.Seek -> {
            val target = clamp(state, event.positionMs)
            applied(
                PlayerPageState.Playing(target, state.mode),
                listOf(PlayerPageEffect.SeekTo(target)),
            )
        }

        is PlayerPageEvent.SetRepeat ->
            setRepeat(state, state.mode, event.repeat) { state.copy(mode = it) }

        is PlayerPageEvent.SetShuffle ->
            setShuffle(state, state.mode, event.enabled) { state.copy(mode = it) }

        is PlayerPageEvent.TrackChanged ->
            if (event.songId > 0L) trackChanged(state, event) else reject(state, event)

        is PlayerPageEvent.PositionTick ->
            applied(state.copy(positionMs = clamp(state, event.positionMs)))

        is PlayerPageEvent.PlaybackError -> playbackError(state, state.mode, event.message)

        is PlayerPageEvent.EnterSeamless ->
            if (state.mode.seamless == null && event.songId > 0L) {
                enterSeamless(state.mode, event.songId) { state.copy(mode = it) }
            } else {
                reject(state, event)
            }

        is PlayerPageEvent.ExitSeamless ->
            if (state.mode.seamless != null) {
                exitSeamless(state.mode) { state.copy(mode = it) }
            } else {
                reject(state, event)
            }

        PlayerPageEvent.PlaybackEnded -> applied(PlayerPageState.Ended(state.mode))
    }

    private fun reducePaused(
        state: PlayerPageState.Paused,
        event: PlayerPageEvent,
    ): Transition<PlayerPageState, PlayerPageEffect> = when (event) {
        PlayerPageEvent.Play -> applied(
            PlayerPageState.Playing(state.positionMs, state.mode),
            listOf(PlayerPageEffect.PlayerPlay),
        )
        PlayerPageEvent.Pause -> applied(state)
        PlayerPageEvent.Toggle -> applied(
            PlayerPageState.Playing(state.positionMs, state.mode),
            listOf(PlayerPageEffect.PlayerPlay),
        )

        PlayerPageEvent.DragStart -> applied(
            PlayerPageState.Seeking(
                dragMs = state.positionMs,
                targetMs = state.positionMs,
                pendingSince = null,
                mode = state.mode,
                resume = ResumeTarget.PAUSED,
            ),
        )

        is PlayerPageEvent.DragMove -> reject(state, event)
        is PlayerPageEvent.DragEnd -> reject(state, event)

        is PlayerPageEvent.Seek -> {
            val target = clamp(state, event.positionMs)
            applied(
                PlayerPageState.Paused(target, state.mode),
                listOf(PlayerPageEffect.SeekTo(target)),
            )
        }

        is PlayerPageEvent.SetRepeat ->
            setRepeat(state, state.mode, event.repeat) { state.copy(mode = it) }

        is PlayerPageEvent.SetShuffle ->
            setShuffle(state, state.mode, event.enabled) { state.copy(mode = it) }

        is PlayerPageEvent.TrackChanged ->
            if (event.songId > 0L) trackChanged(state, event) else reject(state, event)

        is PlayerPageEvent.PositionTick ->
            applied(state.copy(positionMs = clamp(state, event.positionMs)))

        is PlayerPageEvent.PlaybackError -> playbackError(state, state.mode, event.message)

        is PlayerPageEvent.EnterSeamless ->
            if (state.mode.seamless == null && event.songId > 0L) {
                enterSeamless(state.mode, event.songId) { state.copy(mode = it) }
            } else {
                reject(state, event)
            }

        is PlayerPageEvent.ExitSeamless ->
            if (state.mode.seamless != null) {
                exitSeamless(state.mode) { state.copy(mode = it) }
            } else {
                reject(state, event)
            }

        PlayerPageEvent.PlaybackEnded -> reject(state, event)
    }

    private fun reduceSeeking(
        state: PlayerPageState.Seeking,
        event: PlayerPageEvent,
        nowMs: Long,
    ): Transition<PlayerPageState, PlayerPageEffect> = when (event) {
        is PlayerPageEvent.DragMove ->
            if (state.pendingSince == null) {
                val position = clamp(state, event.positionMs)
                applied(state.copy(dragMs = position, targetMs = position))
            } else {
                reject(state, event)
            }

        is PlayerPageEvent.DragEnd ->
            if (state.pendingSince == null) {
                val target = clamp(state, event.targetMs)
                applied(
                    state.copy(dragMs = target, targetMs = target, pendingSince = nowMs),
                    listOf(PlayerPageEffect.SeekTo(target)),
                )
            } else {
                reject(state, event)
            }

        is PlayerPageEvent.PositionTick -> seekTick(state, event.positionMs, nowMs)

        is PlayerPageEvent.TrackChanged ->
            if (event.songId > 0L) trackChanged(state, event) else reject(state, event)

        is PlayerPageEvent.PlaybackError -> playbackError(state, state.mode, event.message)

        else -> reject(state, event)
    }

    private fun reduceBuffering(
        state: PlayerPageState.Buffering,
        event: PlayerPageEvent,
    ): Transition<PlayerPageState, PlayerPageEffect> = when (event) {
        PlayerPageEvent.Play -> applied(state)
        PlayerPageEvent.Pause -> applied(
            PlayerPageState.Paused(state.positionMs, state.mode),
            listOf(PlayerPageEffect.PlayerPause),
        )
        PlayerPageEvent.Toggle -> applied(
            PlayerPageState.Paused(state.positionMs, state.mode),
            listOf(PlayerPageEffect.PlayerPause),
        )

        is PlayerPageEvent.PositionTick ->
            if (event.positionMs > 0L) {
                applied(PlayerPageState.Playing(clamp(state, event.positionMs), state.mode))
            } else {
                applied(state)
            }

        is PlayerPageEvent.PlaybackError -> playbackError(state, state.mode, event.message)

        is PlayerPageEvent.SetRepeat ->
            setRepeat(state, state.mode, event.repeat) { state.copy(mode = it) }

        is PlayerPageEvent.SetShuffle ->
            setShuffle(state, state.mode, event.enabled) { state.copy(mode = it) }

        is PlayerPageEvent.TrackChanged ->
            if (event.songId > 0L) trackChanged(state, event) else reject(state, event)

        is PlayerPageEvent.EnterSeamless ->
            if (state.mode.seamless == null && event.songId > 0L) {
                enterSeamless(state.mode, event.songId) { state.copy(mode = it) }
            } else {
                reject(state, event)
            }

        is PlayerPageEvent.ExitSeamless ->
            if (state.mode.seamless != null) {
                exitSeamless(state.mode) { state.copy(mode = it) }
            } else {
                reject(state, event)
            }

        else -> reject(state, event)
    }

    private fun reduceEnded(
        state: PlayerPageState.Ended,
        event: PlayerPageEvent,
    ): Transition<PlayerPageState, PlayerPageEffect> = when (event) {
        PlayerPageEvent.Play -> applied(
            PlayerPageState.Playing(0L, state.mode),
            listOf(PlayerPageEffect.PlayerPlay),
        )

        is PlayerPageEvent.TrackChanged ->
            if (event.songId > 0L) trackChanged(state, event) else reject(state, event)

        else -> reject(state, event)
    }

    private fun reduceError(
        state: PlayerPageState.Error,
        event: PlayerPageEvent,
    ): Transition<PlayerPageState, PlayerPageEffect> = when (event) {
        PlayerPageEvent.Play -> applied(
            PlayerPageState.Playing(0L, state.mode),
            listOf(PlayerPageEffect.PlayerPlay),
        )

        is PlayerPageEvent.TrackChanged ->
            if (event.songId > 0L) trackChanged(state, event) else reject(state, event)

        else -> reject(state, event)
    }

    // ------------------------------------------------------------------ 规则实现

    /**
     * 00001 规则 1/2：拖拽中 tick 原样返回；松手后 pendingSeek 覆盖层
     * 直到位置收敛（±1.2s）或超时（1.5s）才把显示交还真实位置。
     */
    private fun seekTick(
        state: PlayerPageState.Seeking,
        positionMs: Long,
        nowMs: Long,
    ): Transition<PlayerPageState, PlayerPageEffect> {
        // 规则 1：拖拽中 PositionTick 不得改变 dragMs（也不得改 targetMs）。
        val pendingSince = state.pendingSince ?: return applied(state)
        val position = clamp(state, positionMs)
        val converged = abs(position - state.targetMs) <= SEEK_CONVERGENCE_MS
        val timedOut = nowMs >= pendingSince && nowMs - pendingSince >= PENDING_SEEK_TIMEOUT_MS
        if (!converged && !timedOut) return applied(state)

        val next = when (state.resume) {
            ResumeTarget.PLAYING -> PlayerPageState.Playing(position, state.mode)
            ResumeTarget.PAUSED -> PlayerPageState.Paused(position, state.mode)
        }
        return applied(next)
    }

    private fun setRepeat(
        state: PlayerPageState,
        mode: PlayMode,
        repeat: RepeatMode,
        withMode: (PlayMode) -> PlayerPageState,
    ): Transition<PlayerPageState, PlayerPageEffect> {
        val seamless = mode.seamless
        if (seamless != null) {
            // 规则 3：无缝意图下 repeat 固定为 ONE，重复设置是恒等 no-op；
            // 用户改成非 ONE = 显式退出意图（repeat 用用户目标值，shuffle 恢复原值）。
            if (repeat == RepeatMode.ONE) return applied(state)
            val restored = PlayMode(seamless.previousRepeat, seamless.previousShuffle, null)
            val target = restored.copy(repeat = repeat)
            return applied(withMode(target), modeEffects(mode, target))
        }

        val shuffle = if (repeat == RepeatMode.ONE && mode.shuffle) false else mode.shuffle
        val target = mode.copy(repeat = repeat, shuffle = shuffle)
        if (target == mode) return applied(state)
        return applied(withMode(target), modeEffects(mode, target))
    }

    private fun setShuffle(
        state: PlayerPageState,
        mode: PlayMode,
        enabled: Boolean,
        withMode: (PlayMode) -> PlayerPageState,
    ): Transition<PlayerPageState, PlayerPageEffect> {
        val seamless = mode.seamless
        if (seamless != null) {
            // 规则 3：无缝期间 shuffle 固定 false；用户打开 shuffle = 显式退出意图。
            if (!enabled) return applied(state)
            val restored = PlayMode(seamless.previousRepeat, seamless.previousShuffle, null)
            val repeat = if (restored.repeat == RepeatMode.ONE) RepeatMode.ALL else restored.repeat
            val target = restored.copy(repeat = repeat, shuffle = true)
            return applied(withMode(target), modeEffects(mode, target))
        }

        if (mode.shuffle == enabled) return applied(state)
        // 规则 4：shuffle + repeat=ONE 自相矛盾，打开 shuffle 时把 repeat 归一到 ALL。
        val repeat = if (enabled && mode.repeat == RepeatMode.ONE) RepeatMode.ALL else mode.repeat
        val target = mode.copy(repeat = repeat, shuffle = enabled)
        return applied(withMode(target), modeEffects(mode, target))
    }

    private fun enterSeamless(
        mode: PlayMode,
        songId: Long,
        withMode: (PlayMode) -> PlayerPageState,
    ): Transition<PlayerPageState, PlayerPageEffect> {
        val target = PlayMode(
            repeat = RepeatMode.ONE,
            shuffle = false,
            seamless = SeamlessIntent(
                songId = songId,
                previousRepeat = mode.repeat,
                previousShuffle = mode.shuffle,
            ),
        )
        return applied(withMode(target), modeEffects(mode, target))
    }

    private fun exitSeamless(
        mode: PlayMode,
        withMode: (PlayMode) -> PlayerPageState,
    ): Transition<PlayerPageState, PlayerPageEffect> {
        val intent = mode.seamless ?: return applied(withMode(mode))
        val target = PlayMode(
            repeat = intent.previousRepeat,
            shuffle = intent.previousShuffle,
            seamless = null,
        )
        return applied(withMode(target), modeEffects(mode, target))
    }

    /**
     * 切歌 = 显式退出旧意图（恢复进入前模式）；新歌带显式意图时再重新武装，
     * 并把「恢复后的模式」记为新的进入前原值（不会把 ONE/false 当成用户原值）。
     */
    private fun trackChanged(
        state: PlayerPageState,
        event: PlayerPageEvent.TrackChanged,
    ): Transition<PlayerPageState, PlayerPageEffect> {
        val base = modeOf(state) ?: PlayMode.DEFAULT
        val restored = base.restoreSeamless()
        val target = if (event.hasLoopIntent) {
            PlayMode(
                repeat = RepeatMode.ONE,
                shuffle = false,
                seamless = SeamlessIntent(
                    songId = event.songId,
                    previousRepeat = restored.repeat,
                    previousShuffle = restored.shuffle,
                ),
            )
        } else {
            restored
        }

        val effects = modeEffects(base, target).toMutableList()
        val next = when (state) {
            is PlayerPageState.Idle,
            is PlayerPageState.Ended,
            is PlayerPageState.Error -> {
                effects += PlayerPageEffect.PlayerPlay
                PlayerPageState.Playing(0L, target)
            }

            is PlayerPageState.Playing,
            is PlayerPageState.Buffering -> PlayerPageState.Buffering(0L, target)

            is PlayerPageState.Paused -> PlayerPageState.Paused(0L, target)

            is PlayerPageState.Seeking -> when (state.resume) {
                ResumeTarget.PLAYING -> PlayerPageState.Buffering(0L, target)
                ResumeTarget.PAUSED -> PlayerPageState.Paused(0L, target)
            }
        }
        return applied(next, effects)
    }

    private fun playbackError(
        state: PlayerPageState,
        mode: PlayMode,
        message: String,
    ): Transition<PlayerPageState, PlayerPageEffect> = applied(
        PlayerPageState.Error(message, mode),
        listOf(PlayerPageEffect.ShowToast(message)),
    )

    // ------------------------------------------------------------------ 纯工具

    private fun applied(
        state: PlayerPageState,
        effects: List<PlayerPageEffect> = emptyList(),
    ): Transition<PlayerPageState, PlayerPageEffect> = Transition.Applied(state, effects)

    private fun reject(
        state: PlayerPageState,
        event: PlayerPageEvent,
    ): Transition<PlayerPageState, PlayerPageEffect> = Transition.Rejected(
        state,
        "状态 ${stateKind(state)} 不允许事件 ${eventKind(event)}",
    )

    private fun stateKind(state: PlayerPageState): String = when (state) {
        is PlayerPageState.Idle -> "Idle"
        is PlayerPageState.Playing -> "Playing"
        is PlayerPageState.Paused -> "Paused"
        is PlayerPageState.Seeking -> "Seeking"
        is PlayerPageState.Buffering -> "Buffering"
        is PlayerPageState.Ended -> "Ended"
        is PlayerPageState.Error -> "Error"
    }

    private fun eventKind(event: PlayerPageEvent): String = event::class.simpleName ?: "UnknownEvent"

    private fun clamp(state: PlayerPageState, positionMs: Long): Long = positionMs.coerceAtLeast(0L)

    /**
     * 模式变更的 effect 序列，只发真正的差异；顺序保证**中间态也不非法**：
     * - 收敛到 repeat=ONE + shuffle=false 时先关 shuffle 再设 ONE；
     * - 其余情况先设 repeat 再设 shuffle。
     */
    private fun modeEffects(old: PlayMode, new: PlayMode): List<PlayerPageEffect> {
        val repeat = if (old.repeat != new.repeat) PlayerPageEffect.SetRepeat(new.repeat) else null
        val shuffle = if (old.shuffle != new.shuffle) PlayerPageEffect.SetShuffle(new.shuffle) else null
        if (repeat != null && shuffle != null) {
            return if (new.repeat == RepeatMode.ONE) {
                listOf(shuffle, repeat)
            } else {
                listOf(repeat, shuffle)
            }
        }
        return listOfNotNull(repeat, shuffle)
    }

    private fun PlayMode.restoreSeamless(): PlayMode =
        seamless?.let { PlayMode(it.previousRepeat, it.previousShuffle, null) } ?: this

    private fun clamp(state: PlayerPageState.Seeking, positionMs: Long): Long =
        positionMs.coerceAtLeast(0L)

    private fun positionProblems(state: PlayerPageState): List<String> = when (state) {
        is PlayerPageState.Idle -> emptyList()

        is PlayerPageState.Playing ->
            if (state.positionMs < 0L) listOf("Playing.positionMs 不能为负：${state.positionMs}") else emptyList()

        is PlayerPageState.Paused ->
            if (state.positionMs < 0L) listOf("Paused.positionMs 不能为负：${state.positionMs}") else emptyList()

        is PlayerPageState.Seeking -> buildList {
            if (state.dragMs < 0L) add("Seeking.dragMs 不能为负：${state.dragMs}")
            if (state.targetMs < 0L) add("Seeking.targetMs 不能为负：${state.targetMs}")
            if (state.pendingSince != null && state.pendingSince < 0L) {
                add("Seeking.pendingSince 不能为负：${state.pendingSince}")
            }
            if (state.pendingSince == null && state.targetMs != state.dragMs) {
                add("拖拽中 targetMs 必须等于 dragMs：target=${state.targetMs}, drag=${state.dragMs}")
            }
        }

        is PlayerPageState.Buffering ->
            if (state.positionMs < 0L) listOf("Buffering.positionMs 不能为负：${state.positionMs}") else emptyList()

        is PlayerPageState.Ended -> emptyList()
        is PlayerPageState.Error -> emptyList()
    }

    private fun modeProblems(state: PlayerPageState): List<String> {
        val mode = modeOf(state) ?: return emptyList()
        val intent = mode.seamless
        return buildList {
            if (intent != null) {
                if (mode.repeat != RepeatMode.ONE || mode.shuffle) {
                    add(
                        "无缝意图要求 repeat=ONE 且 shuffle=false（实际 repeat=${mode.repeat}, shuffle=${mode.shuffle}）",
                    )
                }
                if (intent.songId <= 0L) {
                    add("无缝意图 songId 非法：${intent.songId}")
                }
            } else if (mode.shuffle && mode.repeat == RepeatMode.ONE) {
                add("播放模式自相矛盾：shuffle=true 且 repeat=ONE 且不在无缝意图中")
            }
        }
    }
}