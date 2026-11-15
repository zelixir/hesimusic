package com.zjr.hesimusic.service

import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.zjr.hesimusic.data.dao.SongLoopDao
import com.zjr.hesimusic.data.mapper.mediaIdToSongId
import com.zjr.hesimusic.data.repository.SongRepository
import com.zjr.hesimusic.loop.LoopEligibility
import com.zjr.hesimusic.loop.LoopPosition
import com.zjr.hesimusic.loop.LoopRuntime
import com.zjr.hesimusic.loop.LoopWindow
import com.zjr.hesimusic.loop.audio.LoopAudioController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 服务层收口：把「事件」翻译成「一次纯函数重算 + 至多一次播放形态重建」。
 *
 * 唯一事件入口是 [refresh]（切歌 / repeat 变化 / song_loop 行变更 / restore 完成），
 * 处理顺序严格按契约 docs/loop-v3-service-design.md §2.3：
 *
 * 1. 查行（[SongLoopDao.getBySongId]）；
 * 2. [LoopRuntime.refresh] 重算窗口（纯函数、幂等）；
 * 3. 写 [LoopAudioController.fullDurationUs]（整曲时长，供会话层 getDuration 投影）；
 * 4. 形态（songId + clipEnd + window）有变化才 [applyShape]；
 * 5. 当前歌变化时重启 song_loop 行订阅（analyze/confirm/demote/delete 落库自动传导）。
 *
 * 不变量：
 * - 引擎从不修改 repeatMode，repeat 只代表用户意图；
 * - 窗口未变且当前项形态一致时不做事（无缝播放期间零重建）；
 * - 所有 Player 操作都在主线程（[Dispatchers.Main.immediate]）。
 */
@Singleton
class LoopPlaybackCoordinator @Inject constructor(
    private val runtime: LoopRuntime,
    private val audio: LoopAudioController,
    private val songLoopDao: SongLoopDao,
    private val songRepository: SongRepository,
) {

    /** 当前项在播放器里的实际形态：谁在无缝、clip 到哪、窗口是什么。 */
    private data class Shape(
        val songId: Long?,
        val clipEndUs: Long?,
        val window: LoopWindow?,
    )

    private val mainDispatcher = Dispatchers.Main.immediate
    private val scope = CoroutineScope(SupervisorJob() + mainDispatcher)
    private val refreshMutex = Mutex()

    /** 播放列表 index → 该项当前的 clip 形态（只有被我们改过形态的项才会入表）。 */
    private val clipped = HashMap<Int, Shape>()

    private var observeJob: Job? = null
    private var observedSongId: Long? = null

    @Volatile
    private var playerRef: Player? = null

    init {
        installDegradeHandler()
    }

    // ------------------------------------------------------------------ 事件入口

    /**
     * 唯一事件入口：重算窗口并（必要时）应用播放形态。
     *
     * @param songId 当前歌（[com.zjr.hesimusic.data.mapper.mediaIdToSongId] 口径，无效则 null）
     * @param repeatMode 用户意图（[Player.getRepeatMode]），本层只读不写
     * @param reason 仅用于日志：transition / repeat / row / restore
     */
    suspend fun refresh(songId: Long?, repeatMode: Int, player: Player, reason: String) {
        refreshMutex.withLock {
            playerRef = player
            val row = songId?.takeIf { it > 0L }?.let { songLoopDao.getBySongId(it) }
            // 先用同一个纯函数预判一次资格，只为在窗口对外可见**之前**把整曲时长写进音频层：
            // 否则 runtime.refresh 之后还要等一次 DB 查询，UI 可能在这段间隙读到上一首歌的时长。
            val willBeEligible = LoopEligibility.derive(songId, repeatMode, row) != null
            audio.fullDurationUs = if (willBeEligible) songDurationUs(songId) else 0L
            runtime.refresh(songId, repeatMode, row)              // 1) 纯函数重算（唯一窗口来源）
            val window = runtime.window.value
            withContext(mainDispatcher) {                          // 2) 形态只在主线程动播放器
                syncShape(window, player, reason)
            }
            ensureObservation(songId, player)                      // 3) 行变更订阅
        }
    }

    /** service 销毁时停止行订阅并清空窗口（不取消 [scope]，服务可能被重建）。 */
    fun release() {
        observeJob?.cancel()
        observeJob = null
        observedSongId = null
        playerRef = null
        clipped.clear()
        if (audio.window != null) {
            audio.window = null
        }
        runtime.clear()
    }

    // ------------------------------------------------------------------ 形态应用

    /**
     * 形态对账：只有「当前项需要的 clip 形态」与「实际已应用的形态」不同时才动播放器。
     *
     * 这里的依据是 [clipped]（按播放列表 index 记账），而不是「上一次 refresh 的窗口」：
     * replaceMediaItem 替换的是播放列表里那一项的 MediaSource，被替换过的项即使切走再切回
     * 也仍然是带 clip 的源码，必须按 index 记账才能正确去掉它。
     */
    private fun syncShape(window: LoopWindow?, player: Player, reason: String) {
        val index = player.currentMediaItemIndex
        val item = player.currentMediaItem
        if (index < 0 || item == null) {
            if (audio.window != null) {
                audio.window = null
            }
            return
        }
        val itemSongId = mediaIdToSongId(item.mediaId)
        // 窗口只对「当前正在播的那一项」生效：事件可能早于/晚于 transition，以当前 item 为准
        val targetWindow = window?.takeIf { w -> itemSongId != null && w.songId == itemSongId }

        // 音频层永远先落（region 跟着真正在播的那一项走；引擎要求 window 变更伴随一次重建）。
        // 值相同就不重复写：LoopAudioController.window 的 setter 会顺带触发后台 ring 预分配。
        if (audio.window != targetWindow) {
            audio.window = targetWindow
        }

        val targetShape = Shape(itemSongId, targetWindow?.loopEndUs, targetWindow)
        val appliedShape = clipped[index]
        if (appliedShape == targetShape) return              // 幂等：形态未变，零媒体操作
        if (appliedShape == null && targetWindow == null) return  // 未 clip 过，且目标不 clip

        val previousWindow = appliedShape?.window
        applyShape(player, index, item, targetWindow, previousWindow, reason)
        if (targetWindow == null) clipped.remove(index) else clipped[index] = targetShape
    }

    /**
     * 重建当前项的播放形态（窗口变化时唯一一次媒体操作）。
     *
     * 顺序：audio.window 已在 [syncShape] 写入 → replaceMediaItem 让 LoopMediaSourceFactory
     * 重新决定 clip → 从展示位置映射回 raw 续播。
     */
    private fun applyShape(
        player: Player,
        index: Int,
        item: MediaItem,
        targetWindow: LoopWindow?,
        previousWindow: LoopWindow?,
        reason: String,
    ) {
        // 1) 先按旧口径把当前位置换算成展示位置（重放期取 sink 位置，否则取播放器位置）
        val rawUs = currentRawUs(player, previousWindow)
        val displayUs = LoopSessionProjection.displayUs(rawUs, previousWindow)

        // 2) 触发 source 重建：clip 形态由 LoopMediaSourceFactory 依 runtime.window 决定
        player.replaceMediaItem(index, item)

        // 3) 展示位置 → 新形态的 raw 起点
        val targetRawUs = if (targetWindow != null) {
            LoopPosition.rawSeekUs(displayUs, targetWindow, displayUs)
        } else {
            displayUs
        }

        // 4) 起播点自检：落在 [A,B) 内但 ring 还不完整（restore 恢复位置 / 确认保存时正在区内 /
        //    通知栏 seek 都不经过 LoopSessionPlayer.seekTo）→ 预卷到本轮起点前 250ms，
        //    让本轮完整流经 [A,B) 把 ring 填满，否则到 B 会降级。
        val settledRawUs = if (targetWindow != null && !audio.isRingComplete && targetWindow.containsUs(targetRawUs)) {
            val preRollUs = LoopSessionProjection.degradedSeekUs(targetWindow.loopStartUs)
            Log.d(TAG_PERF, "pre-roll reason=" + reason + " window=" + targetWindow +
                " from=" + targetRawUs + "us to=" + preRollUs + "us")
            preRollUs
        } else {
            targetRawUs
        }

        player.seekTo(index, settledRawUs / LoopSessionProjection.US_PER_MS)
        Log.d(
            TAG,
            "applyShape reason=" + reason + " index=" + index + " songId=" + mediaIdToSongId(item.mediaId) +
                " window=" + targetWindow + " raw=" + rawUs + "us display=" + displayUs +
                "us seek=" + settledRawUs + "us",
        )
    }

    /** 引擎原始位置（µs）：旧窗口属于当前项且处于重放期时用 sink 位置，否则用播放器位置。 */
    private fun currentRawUs(player: Player, windowForPosition: LoopWindow?): Long =
        LoopSessionProjection.rawUs(
            isReplaying = windowForPosition != null && audio.isReplaying,
            replayPositionUs = audio.positionUs,
            playerPositionMs = player.currentPosition,
        )

    // ------------------------------------------------------------------ 降级通路

    /**
     * 音频层请求的降级 seek：ring 不完整时，到 B 之前先回跳到「该轮起点 − 250ms」补一轮。
     * 回调由音频层在主线程发出（[LoopAudioController.onDegradeRequested]）。
     */
    private fun installDegradeHandler() {
        audio.onDegradeRequested = { targetRawUs -> onDegradeRequested(targetRawUs) }
    }

    /** 音频层在主线程发出降级请求：目标 raw 位置 = 该轮起点 - 250ms。 */
    private fun onDegradeRequested(targetRawUs: Long) {
        scope.launch {
            val player = playerRef
            if (player == null) {
                Log.w(TAG_PERF, "degraded ignored (no player) target=" + targetRawUs + "us")
                return@launch
            }
            player.seekTo(targetRawUs / LoopSessionProjection.US_PER_MS)
            Log.d(
                TAG_PERF,
                "degraded targetRawUs=" + targetRawUs + "us seekMs=" +
                    (targetRawUs / LoopSessionProjection.US_PER_MS) +
                    " degradeCount=" + audio.degradeCount,
            )
        }
    }

    // ------------------------------------------------------------------ 行订阅

    /**
     * 当前歌变化时重启 song_loop 行订阅：confirm / demote / delete / analyze 落库全部自动传导，
     * UI 不需要认识播放层（契约 §2.3）。
     */
    private fun ensureObservation(songId: Long?, player: Player) {
        val validSongId = songId?.takeIf { it > 0L }
        if (validSongId == observedSongId && observeJob?.isActive == true) return
        observeJob?.cancel()
        observedSongId = validSongId
        observeJob = validSongId?.let { id ->
            scope.launch {
                songLoopDao.getBySongIdFlow(id).collect { _ ->
                    // 行内容变化（含删除后的 null）→ 完整重算一次
                    refresh(id, player.repeatMode, player, "row")
                }
            }
        }
    }

    // ------------------------------------------------------------------ 工具

    private suspend fun songDurationUs(songId: Long?): Long {
        if (songId == null || songId <= 0L) return 0L
        val song = songRepository.getSongsByIds(listOf(songId)).firstOrNull() ?: return 0L
        return song.duration * LoopSessionProjection.US_PER_MS
    }

    private companion object {
        private const val TAG = "LoopPlaybackCoordinator"
        private const val TAG_PERF = "LoopPerf"
    }
}
