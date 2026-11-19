package com.zjr.hesimusic.service

import androidx.media3.common.Player
import com.zjr.hesimusic.loop.LoopWindow

/**
 * 主播放器 SOURCE 的**接管状态**（唯一写点是 [LoopPlaybackCoordinator]）。
 *
 * 为什么需要它：调试口（`/state`）的 engine 口径原先只看 `LoopAudioController.window`
 * （M3′ 的音频窗口）。SOURCE 路线**故意不写** M3′ 窗口（数据源自己负责循环，写了会让
 * LoopAudioSink 二次拦截并额外分配一份 ring），于是即使 SOURCE 已经接管、正在播放，
 * `/state` 仍报 engine=exo，无法从外部判断「到底有没有进循环」。
 *
 * 这里把「哪个窗口被 SOURCE 接管了」记录成一个可读的会话状态，供：
 * - 调试口 engine/loopPath 判定（[engineName]）；
 * - 会话层/协调器自检（[isInstalledFor]）。
 *
 * 只记录状态，不做任何播放操作。
 */
object LoopSourceRuntime {

    /** 当前被 SOURCE 接管的窗口；null = 没有接管（M3′ 或普通播放）。 */
    @Volatile
    var installedWindow: LoopWindow? = null
        private set

    /** 接管时的媒体文件路径（排障用）。 */
    @Volatile
    var installedPath: String? = null
        private set

    fun onInstalled(window: LoopWindow, filePath: String) {
        installedWindow = window
        installedPath = filePath
    }

    fun onReleased() {
        installedWindow = null
        installedPath = null
    }

    fun reset() = onReleased()

    /** 该窗口是否正被 SOURCE 接管（窗口 A/B 必须一致）。 */
    fun isInstalledFor(window: LoopWindow?): Boolean {
        val installed = installedWindow ?: return false
        if (window == null) return false
        return installed.songId == window.songId &&
            installed.loopStartUs == window.loopStartUs &&
            installed.loopEndUs == window.loopEndUs
    }

    /**
     * `/state.engine` 口径的纯函数：M3′ 音频窗口或 SOURCE 接管窗口命中当前歌都算 loop。
     *
     * 与旧行为兼容：M3′ 分支完全沿用原判据（音频窗口非空且属于当前歌；冷启动缓存未知时视为命中）。
     */
    fun engineName(
        m3Window: LoopWindow?,
        sourceWindow: LoopWindow? = installedWindow,
        currentSongId: Long?,
    ): String {
        if (matches(m3Window, currentSongId)) return "loop"
        if (matches(sourceWindow, currentSongId)) return "loop"
        return "exo"
    }

    /** 当前走的是哪条循环通路：m3 / source / none（`/state.loopPath`）。 */
    fun loopPath(
        m3Window: LoopWindow?,
        sourceWindow: LoopWindow? = installedWindow,
        currentSongId: Long?,
    ): String = when {
        matches(m3Window, currentSongId) -> "m3"
        matches(sourceWindow, currentSongId) -> "source"
        else -> "none"
    }

    private fun matches(window: LoopWindow?, currentSongId: Long?): Boolean {
        if (window == null || !window.isValid()) return false
        // 冷启动时 debug 的 Player.Listener 缓存可能还没拿到当前歌：窗口本身已由协调器按当前歌算出。
        return currentSongId == null || currentSongId == window.songId
    }

    // ---------------------------------------------------------------- 换源后的播放拉起

    /** 换源后要做的播放动作（纯数据，单测穷举）。 */
    data class Revive(val prepare: Boolean, val play: Boolean)

    /**
     * 换源不会自动开始加载：`replaceMediaItem` 只改播放列表，IDLE 状态下必须 `prepare()`。
     *
     * 规则（绝不偷播）：
     * - `prepare`：换源前处于 [Player.STATE_IDLE]（`/loop/enter` 之类只 setMediaItems 没 prepare）；
     * - `play`：换源前 `playWhenReady == true`（用户/接口明确要求播放）。
     */
    fun revivePlan(stateBefore: Int, playWhenReadyBefore: Boolean): Revive = Revive(
        prepare = stateBefore == Player.STATE_IDLE,
        play = playWhenReadyBefore,
    )
}
