package com.zjr.hesimusic.service

import android.util.Log
import androidx.media3.common.Player
import com.zjr.hesimusic.data.dao.SongLoopDao
import com.zjr.hesimusic.data.mapper.mediaIdToSongId
import com.zjr.hesimusic.data.repository.SongRepository
import com.zjr.hesimusic.loop.LoopEligibility
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
 * 服务层收口：把「事件」翻译成「一次纯函数重算 + 至多一次状态写（+ 必要时一次 seek）」。
 *
 * 唯一事件入口是 [refresh]（切歌 / repeat 变化 / song_loop 行变更 / restore 完成），
 * 处理顺序：
 *
 * 1. 查行（[SongLoopDao.getBySongId]）；
 * 2. 预判资格只为先写整曲时长（[LoopAudioController.fullDurationUs]）；
 * 3. [LoopRuntime.refresh] 重算窗口（纯函数、幂等、唯一窗口来源）；
 * 4. 形态对账：[LoopShapePlan] 决定「写什么窗口 + 是否 seek」——生产路径**不裁剪**，
 *    所以进入/退出无缝只写 [LoopAudioController.window]，没有任何 replaceMediaItem /
 *    时间线重建；只有「进入时 ring 未覆盖 [A,B)」与「退出时处于重放态」两种例外各 seek 一次；
 * 5. 当前歌变化时重启 song_loop 行订阅（analyze/confirm/demote/delete 落库自动传导）。
 *
 * 不变量：
 * - 引擎从不修改 repeatMode，repeat 只代表用户意图；
 * - 窗口值没变时不做任何事（无缝播放期间零媒体操作、零 seek）；
 * - 所有 Player 操作都在主线程（[Dispatchers.Main.immediate]）。
 */
@Singleton
class LoopPlaybackCoordinator @Inject constructor(
    private val runtime: LoopRuntime,
    private val audio: LoopAudioController,
    private val songLoopDao: SongLoopDao,
    private val songRepository: SongRepository,
) {

    private val mainDispatcher = Dispatchers.Main.immediate
    private val scope = CoroutineScope(SupervisorJob() + mainDispatcher)
    private val refreshMutex = Mutex()

    /** 当前已应用到播放器/音频层的窗口；值相等即视为形态未变。 */
    private var appliedWindow: LoopWindow? = null

    private var observeJob: Job? = null
    private var observedSongId: Long? = null

    @Volatile
    private var playerRef: Player? = null

    init {
        installDegradeHandler()
    }

    // ------------------------------------------------------------------ 事件入口

    /**
     * 唯一事件入口：重算窗口并（必要时）应用形态。
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
        appliedWindow = null
        if (audio.window != null) {
            audio.window = null
        }
        runtime.clear()
    }

    // ------------------------------------------------------------------ 形态应用

    /**
     * 形态对账：只有「目标窗口」与「已应用窗口」不同才动作，且动作 = 写一次窗口（+ 必要时一次 seek）。
     *
     * 顺序要点：
     * - 重放态必须在写窗口**之前**读：写 null 会让音频层状态立刻复位成 PASS_THROUGH；
     * - ring 覆盖度必须在写窗口**之后**读：setWindow 会让 ring 失效（可能由后台预分配重建）。
     */
    private fun syncShape(window: LoopWindow?, player: Player, reason: String) {
        val index = player.currentMediaItemIndex
        val itemSongId = player.currentMediaItem?.mediaId?.let { mediaIdToSongId(it) }
        val targetWindow = LoopShapePlan.targetWindow(itemSongId, window)

        // 幂等：窗口没变且音频层已同步 → 零操作（无缝播放期间不会碰到播放器）
        if (targetWindow == appliedWindow && audio.window == targetWindow) return

        val wasReplaying = audio.isReplaying
        val rawUs = currentRawUs(player, appliedWindow)

        // 1) 音频层永远是第一落点：不裁剪 ⇒ 进入/退出无缝只有这一个状态写
        if (audio.window != targetWindow) {
            audio.window = targetWindow
        }

        // 2) 纯函数决定本次是否需要 seek（预卷 / 退出重放态）
        val plan = LoopShapePlan.plan(
            songId = itemSongId,
            targetWindow = targetWindow,
            appliedWindow = appliedWindow,
            rawUs = rawUs,
            isRingComplete = audio.isRingComplete,
            wasReplaying = wasReplaying,
        )

        appliedWindow = plan.window
        if (plan.kind == LoopShapePlan.Kind.NOOP) return

        val seekRawUs = plan.seekRawUs
        if (seekRawUs != null && index >= 0) {
            player.seekTo(index, seekRawUs / LoopSessionProjection.US_PER_MS)
        }
        Log.d(
            TAG,
            "applyShape reason=" + reason + " kind=" + plan.kind + " songId=" + itemSongId +
                " window=" + plan.window + " raw=" + rawUs + "us seek=" + seekRawUs,
        )
        if (seekRawUs != null) {
            Log.d(
                TAG_PERF,
                "seek kind=" + plan.kind + " reason=" + reason +
                    " raw=" + seekRawUs + "us window=" + plan.window,
            )
        }
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
