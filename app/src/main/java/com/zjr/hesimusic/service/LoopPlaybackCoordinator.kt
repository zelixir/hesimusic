package com.zjr.hesimusic.service

import android.util.Log
import androidx.media3.common.Player
import com.zjr.hesimusic.data.dao.SongLoopDao
import com.zjr.hesimusic.data.mapper.mediaIdToSongId
import com.zjr.hesimusic.data.preferences.PlaybackPreferences
import com.zjr.hesimusic.data.repository.SongRepository
import com.zjr.hesimusic.loop.LoopEligibility
import com.zjr.hesimusic.loop.LoopIntent
import com.zjr.hesimusic.loop.LoopIntentDecision
import com.zjr.hesimusic.loop.LoopRuntime
import com.zjr.hesimusic.loop.LoopWindow
import com.zjr.hesimusic.loop.analysis.PcmDecoder
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
 * 唯一事件入口是 [refresh]（切歌 / repeat 变化 / song_loop 行变更 / 意图变化 / restore 完成），
 * 处理顺序：
 *
 * 1. **意图退出决策**（[applyIntentExit]）：切到别的歌 → 清意图 + 恢复 prevRepeat；
 *    repeat 被用户改成非 ONE → 清意图。这是引擎唯一会写 repeat 的地方；
 * 2. 查行（[SongLoopDao.getBySongId]）；
 * 3. 预判资格只为先写整曲时长（[LoopAudioController.fullDurationUs]）；
 * 4. [LoopRuntime.refresh] 重算窗口（纯函数、幂等、唯一窗口来源；显式意图是输入之一）；
 * 5. 形态对账：[LoopShapePlan] 决定「写什么窗口 + 是否 seek」——生产路径**不裁剪**，
 *    所以进入/退出无缝只写 [LoopAudioController.window]，没有任何 replaceMediaItem /
 *    时间线重建；只有「进入时 ring 未覆盖 [A,B)」与「退出时处于重放态」两种例外各 seek 一次；
 *    窗口写入后还会触发一次后台环缓冲预填（[LoopRingPrefiller]，非阻塞、不 seek），
 *    预填完成前仍走上面的预卷兜底，完成后到 B/从区间内起播都无需再预卷；
 * 6. 当前歌变化时重启 song_loop 行订阅（analyze/confirm/demote/delete 落库自动传导）。
 *
 * 不变量：
 * - 无缝只由**显式意图**（[LoopIntent]）驱动：没有武装就没有窗口，哪怕 repeat==ONE；
 * - repeat 只代表用户意图，引擎唯一的写回是「离开武装歌时恢复 prevRepeat」（见 [applyIntentExit]）；
 * - 窗口值没变时不做任何事（无缝播放期间零媒体操作、零 seek）；
 * - 所有 Player 操作都在主线程（[Dispatchers.Main.immediate]）。
 */
@Singleton
class LoopPlaybackCoordinator @Inject constructor(
    private val runtime: LoopRuntime,
    private val audio: LoopAudioController,
    private val songLoopDao: SongLoopDao,
    private val songRepository: SongRepository,
    private val loopIntent: LoopIntent,
    private val playbackPreferences: PlaybackPreferences,
    private val pcmDecoder: PcmDecoder,
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

    /**
     * 服务层环缓冲预填：窗口应用后在后台解码 [A,B) 灌 ring（不阻塞、不 seek、窗口变化即取消）。
     *
     * 与试听页同一套音频层 API（[LoopAudioController.seedRing]），解码复用分析侧
     * [PcmDecoder.decodePcm16]（精确对齐 + BACKGROUND 优先级）。
     */
    private val ringPrefiller = LoopRingPrefiller(
        audio = audio,
        scope = scope,
        decode = { path, startUs, endUs -> pcmDecoder.decodePcm16(path, startUs, endUs) },
        resolvePath = { songId -> songRepository.getSongsByIds(listOf(songId)).firstOrNull()?.filePath },
    )

    init {
        installDegradeHandler()
        observeLoopIntent()
    }

    // ------------------------------------------------------------------ 事件入口

    /**
     * 唯一事件入口：重算窗口并（必要时）应用形态。
     *
     * @param songId 当前歌（[com.zjr.hesimusic.data.mapper.mediaIdToSongId] 口径，无效则 null）
     * @param repeatMode 用户意图（[Player.getRepeatMode]），本层只读（唯一例外见 [applyIntentExit]）
     * @param reason 仅用于日志 + 区分事件类型：transition / repeat / row / intent / restore
     */
    suspend fun refresh(songId: Long?, repeatMode: Int, player: Player, reason: String) {
        refreshMutex.withLock {
            playerRef = player
            // 0) 显式意图的退出决策（唯一会写 repeat 的地方，集中在 applyIntentExit）
            val effectiveRepeatMode = applyIntentExit(songId, repeatMode, player, reason)
            val armedSongId = loopIntent.armed.value?.songId
            val row = songId?.takeIf { it > 0L }?.let { songLoopDao.getBySongId(it) }
            // 先用同一个纯函数预判一次资格，只为在窗口对外可见**之前**把整曲时长写进音频层：
            // 否则 runtime.refresh 之后还要等一次 DB 查询，UI 可能在这段间隙读到上一首歌的时长。
            val willBeEligible =
                LoopEligibility.derive(songId, effectiveRepeatMode, row, armedSongId) != null
            audio.fullDurationUs = if (willBeEligible) songDurationUs(songId) else 0L
            runtime.refresh(songId, effectiveRepeatMode, row, armedSongId) // 1) 纯函数重算（唯一窗口来源）
            val window = runtime.window.value
            withContext(mainDispatcher) {                          // 2) 形态只在主线程动播放器
                syncShape(window, player, reason)
            }
            ensureObservation(songId, player)                      // 3) 行变更订阅
        }
    }

    /**
     * 显式意图的退出决策，返回本次重算应使用的 repeat 模式。
     *
     * 两条退出规则：
     * - **切到别的歌**（reason=transition 且目标歌 != 武装歌，含当前歌为 null）：
     *   立即退出无缝（窗口随后由纯函数置空），并把 repeat 恢复为进入前的 prevRepeat。
     *   新歌 == 武装歌时**不退出**：显式入口是「先 arm 再 play」，进入武装歌那一次
     *   transition 必须保留意图，否则无缝永远无法生效。
     * - **用户把 repeat 改成非 ONE**（reason=repeat）：退出并清空意图，切回 ONE 也不会自动恢复。
     *   这里用「持久化偏好 == 新 repeat」区分「用户手动改」与「内部临时改写」：
     *   队列播放（MusicViewModel）会在 REPEAT_ONE 下临时改成 ALL 且**不写偏好**，
     *   那种临时态不能清掉意图（否则往队列加歌会把正在播放的无缝打断）。
     *
     * 顺序与幂等（恢复 repeat 的唯一落点）：
     * 1. 先 [LoopIntent.clear]，再写 repeat —— 写 repeat 会同步回调 onRepeatModeChanged，
     *    那次 refresh 读到 armed=null，不会二次恢复；
     * 2. 恢复写回同时持久化，避免进程重启后 repeat 又回到 ONE；
     * 3. 无论恢复是否发生（prevRepeat 可能等于当前值），意图都只清一次；后续事件重算幂等。
     */
    private suspend fun applyIntentExit(
        songId: Long?,
        repeatMode: Int,
        player: Player,
        reason: String,
    ): Int {
        val armed = loopIntent.armed.value ?: return repeatMode

        if (reason == REASON_REPEAT && repeatMode != Player.REPEAT_MODE_ONE) {
            val persisted = playbackPreferences.getRepeatMode()
            if (LoopIntentDecision.shouldClearOnRepeatChange(armed, repeatMode, persisted)) {
                loopIntent.clear()
                Log.i(
                    TAG,
                    "loop intent cleared: user repeat=$repeatMode songId=$songId armed=" + armed.songId,
                )
            } else {
                // 内部临时改写（队列播放把 ONE 临时改成 ALL，不落偏好）：保留意图。
                // 窗口此刻自然为 null（repeat != ONE），repeat 恢复成 ONE 后无缝会自动回来。
                Log.d(
                    TAG,
                    "loop intent kept: internal repeat override=$repeatMode persisted=$persisted " +
                        "songId=$songId armed=" + armed.songId,
                )
            }
            return repeatMode
        }

        // 只认 onMediaItemTransition 事件：restore / row / intent 不代表用户切歌。
        if (reason != REASON_TRANSITION) return repeatMode
        if (!LoopIntentDecision.shouldExitOnSongChange(armed, songId)) return repeatMode

        val restore = LoopIntentDecision.repeatToRestore(armed, songId, repeatMode)
        loopIntent.clear()
        if (restore != null) {
            withContext(mainDispatcher) { player.setRepeatMode(restore) }
            playbackPreferences.saveRepeatMode(restore)
            Log.i(
                TAG,
                "loop intent exit: songId=$songId armedSongId=" + armed.songId +
                    " restoreRepeat=$restore currentRepeat=$repeatMode reason=$reason",
            )
            return restore
        }
        Log.i(
            TAG,
            "loop intent exit: songId=$songId armedSongId=" + armed.songId +
                " restoreRepeat=none(already $repeatMode) reason=$reason",
        )
        return repeatMode
    }

    /** service 销毁时停止行订阅并清空窗口（不取消 [scope]，服务可能被重建）。 */
    fun release() {
        observeJob?.cancel()
        observeJob = null
        observedSongId = null
        playerRef = null
        appliedWindow = null
        ringPrefiller.cancel()
        if (audio.window != null) {
            audio.window = null
        }
        runtime.clear()
    }

    // ------------------------------------------------------------------ 意图订阅

    /**
     * 监听显式意图变化：arm / clear 本身不碰播放器，但会改变资格（例如在**正在播放**的歌上
     * 点「无缝循环」时 repeat 已经是 ONE，不会再有 transition/repeat 事件），所以这里补一次
     * 重算。collect 与 refresh 在同一 scope：refresh 内部 clear 只会在本次 collect 返回后
     * 再触发一轮（StateFlow 语义），那一轮 armed 已为 null，重算幂等。
     */
    private fun observeLoopIntent() {
        scope.launch {
            loopIntent.armed.collect { armed ->
                val player = playerRef ?: return@collect
                Log.d(TAG, "refresh reason=intent armed=" + armed?.songId)
                refresh(currentSongId(player), player.repeatMode, player, REASON_INTENT)
            }
        }
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

        // 1b) 窗口写入后触发后台预填（透明、非阻塞）：完成后下一次经过 B 即可无缝，
        //     拖到区间内部也不会再被预卷逻辑拽回 A；失败/格式不符仍走原有预卷兜底。
        ringPrefiller.onWindowApplied(targetWindow)

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
                    refresh(id, player.repeatMode, player, REASON_ROW)
                }
            }
        }
    }

    // ------------------------------------------------------------------ 工具

    private fun currentSongId(player: Player): Long? =
        player.currentMediaItem?.mediaId?.let { mediaIdToSongId(it) }

    private suspend fun songDurationUs(songId: Long?): Long {
        if (songId == null || songId <= 0L) return 0L
        val song = songRepository.getSongsByIds(listOf(songId)).firstOrNull() ?: return 0L
        return song.duration * LoopSessionProjection.US_PER_MS
    }

    private companion object {
        private const val TAG = "LoopPlaybackCoordinator"
        private const val TAG_PERF = "LoopPerf"

        /** 切歌事件（MusicService.onMediaItemTransition）。 */
        const val REASON_TRANSITION = "transition"

        /** repeat 变化事件（MusicService.onRepeatModeChanged）。 */
        const val REASON_REPEAT = "repeat"

        /** song_loop 行变化（ensureObservation）。 */
        const val REASON_ROW = "row"

        /** 显式意图变化（observeLoopIntent）。 */
        const val REASON_INTENT = "intent"
    }
}
