package com.zjr.hesimusic.ui.player.state

import com.zjr.hesimusic.core.statemachine.StateMachineSpec
import kotlin.reflect.KClass

/**
 * 播放页状态机的**合法状态域与事件全集声明**。
 *
 * 这些代表实例就是矩阵测试的行；新增状态/事件时必须同步这里与
 * [PlayerPageReducer.allowed]（后者对 sealed 类型做穷尽 when，漏改会编译失败）。
 *
 * 矩阵跑测见 PlayerPageReducerTest。
 */
object PlayerPageStateMachine {

    /** 代表实例使用的歌曲 id（与 [PlayerPageEvent.TrackChanged] 的新歌 id 区分开）。 */
    const val CURRENT_SONG_ID = 42L

    /** TrackChanged 夹具使用的新歌 id。 */
    const val NEXT_SONG_ID = 7L

    /** 播放器正常播放（无无缝意图）。 */
    private val normalMode = PlayMode(repeat = RepeatMode.OFF, shuffle = false)

    /** 播放器列表循环 + 随机（无无缝意图）。 */
    private val shuffleMode = PlayMode(repeat = RepeatMode.ALL, shuffle = true)

    private fun seamlessMode(previousRepeat: RepeatMode, previousShuffle: Boolean): PlayMode =
        PlayMode(
            repeat = RepeatMode.ONE,
            shuffle = false,
            seamless = SeamlessIntent(
                songId = CURRENT_SONG_ID,
                previousRepeat = previousRepeat,
                previousShuffle = previousShuffle,
            ),
        )

    /**
     * 10 个代表状态：
     * Idle / Playing(普通) / Playing(无缝) / Paused(随机) / Paused(无缝) /
     * Seeking(拖拽中) / Seeking(pending) / Buffering / Ended / Error。
     */
    val states: List<PlayerPageState> = listOf(
        PlayerPageState.Idle,
        PlayerPageState.Playing(positionMs = 30_000L, mode = normalMode),
        PlayerPageState.Playing(positionMs = 30_000L, mode = seamlessMode(RepeatMode.ALL, false)),
        PlayerPageState.Paused(positionMs = 30_000L, mode = shuffleMode),
        PlayerPageState.Paused(positionMs = 30_000L, mode = seamlessMode(RepeatMode.OFF, false)),
        PlayerPageState.Seeking(
            dragMs = 12_000L,
            targetMs = 12_000L,
            pendingSince = null,
            mode = PlayMode(repeat = RepeatMode.ALL, shuffle = false),
            resume = ResumeTarget.PLAYING,
        ),
        PlayerPageState.Seeking(
            dragMs = 45_000L,
            targetMs = 45_000L,
            pendingSince = 1_000L,
            mode = PlayMode(repeat = RepeatMode.ALL, shuffle = false),
            resume = ResumeTarget.PAUSED,
        ),
        PlayerPageState.Buffering(positionMs = 0L, mode = normalMode),
        PlayerPageState.Ended(mode = PlayMode(repeat = RepeatMode.ALL, shuffle = false)),
        PlayerPageState.Error(message = "decoder boom", mode = PlayMode(repeat = RepeatMode.ONE, shuffle = false)),
    )

    /** 19 个事件夹具（SetRepeat / SetShuffle / TrackChanged / PositionTick 各有正反两个方向）。 */
    val events: List<PlayerPageEvent> = listOf(
        PlayerPageEvent.Play,
        PlayerPageEvent.Pause,
        PlayerPageEvent.Toggle,
        PlayerPageEvent.DragStart,
        PlayerPageEvent.DragMove(positionMs = 20_000L),
        PlayerPageEvent.DragEnd(targetMs = 50_000L),
        PlayerPageEvent.Seek(positionMs = 40_000L),
        PlayerPageEvent.SetRepeat(repeat = RepeatMode.ALL),
        PlayerPageEvent.SetRepeat(repeat = RepeatMode.ONE),
        PlayerPageEvent.SetShuffle(enabled = true),
        PlayerPageEvent.SetShuffle(enabled = false),
        PlayerPageEvent.TrackChanged(songId = NEXT_SONG_ID, hasLoopIntent = false),
        PlayerPageEvent.TrackChanged(songId = NEXT_SONG_ID, hasLoopIntent = true),
        PlayerPageEvent.PositionTick(positionMs = 31_000L),
        PlayerPageEvent.PositionTick(positionMs = 45_500L),
        PlayerPageEvent.PlaybackError(message = "decoder boom"),
        PlayerPageEvent.EnterSeamless(songId = NEXT_SONG_ID),
        PlayerPageEvent.ExitSeamless(reason = "user tapped mode"),
        PlayerPageEvent.PlaybackEnded,
    )

    /** 声明的 effect 类型集合；矩阵测试逐个校验，防止悄悄新增副作用。 */
    val effectTypes: Set<KClass<out PlayerPageEffect>> = setOf(
        PlayerPageEffect.PlayerPlay::class,
        PlayerPageEffect.PlayerPause::class,
        PlayerPageEffect.SeekTo::class,
        PlayerPageEffect.SetRepeat::class,
        PlayerPageEffect.SetShuffle::class,
        PlayerPageEffect.ShowToast::class,
    )

    /**
     * 矩阵用的规范。时间参数取默认 0（pendingSeek 超时由命名用例显式注入当下时间）。
     */
    val spec: StateMachineSpec<PlayerPageState, PlayerPageEvent, PlayerPageEffect> = StateMachineSpec(
        name = "player-page",
        states = states,
        events = events,
        reduce = { state, event -> PlayerPageReducer.reduce(state, event) },
        invariants = PlayerPageReducer.INVARIANTS,
        allowed = { state, event -> PlayerPageReducer.allowed(state, event) },
        effectTypes = effectTypes,
    )
}
