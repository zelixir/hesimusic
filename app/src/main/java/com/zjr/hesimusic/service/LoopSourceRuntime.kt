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
        LoopClaimStore.invalidate()
    }

    fun onReleased() {
        installedWindow = null
        installedPath = null
        LoopClaimStore.invalidate()
    }

    fun reset() = onReleased()

    /**
     * 该窗口是否正被 SOURCE 接管（窗口 A/B 必须一致）。
     *
     * 00027：**接管登记不等于源真的建成**。factory 在 createMediaSource 内回退 delegate 时
     * 仍会走到协调器的 onInstalled；所以这里必须再查一次失败登记，否则 build-failed 会被
     * 当成 source 生效，UI/调试口继续折返。窗口 A/B 相同且未登记不可用才算接管。
     */
    fun isInstalledFor(window: LoopWindow?): Boolean {
        val installed = installedWindow ?: return false
        if (window == null || !window.isValid()) return false
        if (installed.songId != window.songId ||
            installed.loopStartUs != window.loopStartUs ||
            installed.loopEndUs != window.loopEndUs
        ) {
            return false
        }
        return !LoopSourceFallback.isUnavailable(window.songId, window)
    }

    /**
     * 只有工厂**确实建成**循环源时才登记接管（P0-1）。
     *
     * @param buildUnavailable 工厂/预检已经判定该窗口不可用（`LoopSourceFallback.isUnavailable`）
     * @return true = 已登记接管；false = 保持未接管，调用方必须按普通播放处理
     */
    fun onInstalledIfBuilt(
        window: LoopWindow,
        filePath: String,
        buildUnavailable: Boolean,
    ): Boolean {
        if (buildUnavailable || LoopSourceFallback.isUnavailable(window.songId, window)) {
            onReleased()
            return false
        }
        onInstalled(window, filePath)
        return true
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
        // build-failed 的窗口仍可能留在 installedWindow：engine/loopPath 绝不能报 loop。
        if (window == installedWindow && LoopSourceFallback.isUnavailable(window.songId, window)) {
            return false
        }
        // 冷启动时 debug 的 Player.Listener 缓存可能还没拿到当前歌：窗口本身已由协调器按当前歌算出。
        return currentSongId == null || currentSongId == window.songId
    }

    // ---------------------------------------------------------------- 换源后的播放拉起

    /** 换源后要做的播放动作（纯数据，单测穷举）。 */
    data class Revive(val prepare: Boolean, val play: Boolean)

    /**
     * 换源后不会自动开始加载，必须按需 `prepare()`。
     *
     * 规则（绝不偷播）：
     * - `prepare`：[sourcesRebuilt] = true（整表 setMediaItems 会强制把播放器复位成 IDLE，
     *   无论换源前是什么状态都必须 prepare 才会加载新源）；否则只在换源前是 [Player.STATE_IDLE]
     *   时 prepare（`/loop/enter` 之类只 setMediaItems 没 prepare）；
     * - `play`：换源前 `playWhenReady == true`（用户/接口明确要求播放）。
     *
     * @param sourcesRebuilt 本次换源是否走了整表重建（[LoopPlaybackCoordinator] 的
     *   `rebuildQueueItem`；A-086 之后所有换源都是这条路）
     */
    fun revivePlan(
        stateBefore: Int,
        playWhenReadyBefore: Boolean,
        sourcesRebuilt: Boolean = false,
    ): Revive = Revive(
        prepare = sourcesRebuilt || stateBefore == Player.STATE_IDLE,
        play = playWhenReadyBefore,
    )
}
