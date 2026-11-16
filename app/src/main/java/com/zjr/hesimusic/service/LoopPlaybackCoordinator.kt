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
import kotlinx.coroutines.CancellationException
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
 * 1. **显式意图的进入/退出**（[applyIntentMode]）：arm 后同时应用 repeat=ONE 且
 *    shuffle=false（唯一写点，保证「显示单曲循环」与实际一致）；切到别的歌 → 清意图 +
 *    恢复 prevRepeat/prevShuffle；用户改 repeat → 只清意图（用户刚选的模式原样保留，
 *    不写 shuffle）；用户改 shuffle → 清意图 + repeat 恢复 prevRepeat，且强制不变量
 *    `shuffle=true ⇒ repeat != ONE`。这是引擎唯一会写这两个属性的地方；
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
 * - repeat/shuffle 只代表用户意图，引擎唯一的写回是「进入时 ONE/false、退出时恢复 prev」
 *   （见 [applyIntentMode]）；
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

    /** 已注册 shuffle 监听的 player（同一实例只注册一次，release 时移除）。 */
    private var listenedPlayer: Player? = null

    /**
     * 用户改 shuffle 的事件源：MusicService 只转发了 repeat/transition，shuffle 变化必须由
     * 本层自己监听，否则「用户把随机播放打开」不会退出无缝意图，显示与实际又会错位。
     *
     * 只在有武装意图时触发重算（无意图时 shuffle 与无缝无关，省一次 DB 查询）；
     * 进入/退出时我们自己写 shuffle 引发的回调由持久化偏好判据过滤（见 LoopIntentDecision）。
     */
    private val shuffleListener = object : Player.Listener {
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            if (loopIntent.armed.value == null) return
            val player = playerRef ?: return
            Log.d(TAG, "refresh reason=shuffle enabled=" + shuffleModeEnabled)
            scope.launch {
                refresh(currentSongId(player), player.repeatMode, player, REASON_SHUFFLE)
            }
        }
    }

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

    private data class SeamDecodeKey(
        val songId: Long,
        val startFrame: Long,
        val endFrame: Long,
    )
    private val seamCache = HashMap<SeamDecodeKey, ByteArray>()
    private val seamInFlight = HashSet<SeamDecodeKey>()

    init {
        installDegradeHandler()
        installSeamProviders()
        observeLoopIntent()
    }

    /**
     * 00017 接缝缓冲 provider（仅注入，不改播放决策）：
     * - decodeRange：同步返回缓存；未命中时后台按帧范围精确解码，下一帧重试；
     * - seekRequested：展示位置 A+Δ → 当前轮 raw（刻意跨 B 回跳，绕过降级 backward 拦截）。
     */
    private fun installSeamProviders() {
        audio.seamDecodeRange = { startFrame, endFrame ->
            val w = audio.window
            val sr = audio.outputSampleRate
            if (w == null || sr <= 0 || endFrame <= startFrame) {
                null
            } else {
                val key = SeamDecodeKey(w.songId, startFrame, endFrame)
                val cached = synchronized(seamCache) { seamCache[key] }
                if (cached != null) {
                    cached
                } else {
                    val first = synchronized(seamCache) {
                        if (seamCache.size > 8) seamCache.clear()
                        seamInFlight.add(key)
                    }
                    if (first) {
                        scope.launch(Dispatchers.Default) {
                            try {
                                val path = songRepository
                                    .getSongsByIds(listOf(w.songId))
                                    .firstOrNull()?.filePath
                                if (path != null) {
                                    val startUs = startFrame * 1_000_000L / sr
                                    val endUs = endFrame * 1_000_000L / sr
                                    val seg = pcmDecoder.decodePcm16Range(path, startUs, endUs)
                                    synchronized(seamCache) { seamCache[key] = seg.data }
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Log.w(TAG_PERF, "seam decode failed " + key, e)
                            } finally {
                                synchronized(seamCache) { seamInFlight.remove(key) }
                            }
                        }
                    }
                    null
                }
            }
        }
        audio.seamSeekRequested = { displayUs ->
            scope.launch {
                val player = playerRef ?: return@launch
                val w = audio.window ?: return@launch
                val raw = LoopSessionProjection.seekUs(
                    displayUs,
                    w,
                    player.currentPosition * LoopSessionProjection.US_PER_MS,
                    audio.isRingComplete,
                )
                // 独立于 degrade 通道：这里允许刻意的跨 B 回跳。
                player.seekTo(raw / LoopSessionProjection.US_PER_MS)
                Log.d(TAG_PERF, "seam seek displayUs=" + displayUs + " rawUs=" + raw)
            }
        }
    }

    // ------------------------------------------------------------------ 事件入口

    /**
     * 唯一事件入口：重算窗口并（必要时）应用形态。
     *
     * @param songId 当前歌（[com.zjr.hesimusic.data.mapper.mediaIdToSongId] 口径，无效则 null）
     * @param repeatMode 用户意图（[Player.getRepeatMode]），本层只读（唯一例外见 [applyIntentMode]）
     * @param reason 仅用于日志 + 区分事件类型：transition / repeat / shuffle / row / intent / restore
     */
    suspend fun refresh(songId: Long?, repeatMode: Int, player: Player, reason: String) {
        refreshMutex.withLock {
            playerRef = player
            // 0) 显式意图：进入时在唯一写点同时应用 repeat=ONE + shuffle=false；退出时恢复 prev。
            //    shuffle 只有本层监听（MusicService 未转发），这里顺带确保监听已注册。
            val shuffleEnabled = withContext(mainDispatcher) {
                ensureShuffleListener(player)
                player.shuffleModeEnabled
            }
            val mode = applyIntentMode(songId, repeatMode, shuffleEnabled, player, reason)
            val armedSongId = loopIntent.armed.value?.songId
            val row = songId?.takeIf { it > 0L }?.let { songLoopDao.getBySongId(it) }
            // 先用同一个纯函数预判一次资格，只为在窗口对外可见**之前**把整曲时长写进音频层：
            // 否则 runtime.refresh 之后还要等一次 DB 查询，UI 可能在这段间隙读到上一首歌的时长。
            val willBeEligible =
                LoopEligibility.derive(songId, mode.repeatMode, row, armedSongId) != null
            audio.fullDurationUs = if (willBeEligible) songDurationUs(songId) else 0L
            runtime.refresh(songId, mode.repeatMode, row, armedSongId) // 1) 纯函数重算（唯一窗口来源）
            val window = runtime.window.value
            withContext(mainDispatcher) {                          // 2) 形态只在主线程动播放器
                syncShape(window, player, reason)
            }
            ensureObservation(songId, player)                      // 3) 行变更订阅
        }
    }

    /**
     * 显式意图的进入/退出决策，返回本次重算应使用的播放模式（repeat + shuffle）。
     *
     * **进入（唯一写点）**：armed 非空且当前不是 repeat=ONE/shuffle=false 时，在同一个
     * `withContext(mainDispatcher)` 里**同时**写 repeat=ONE 与 shuffle=false。
     * 这保证菜单/播放页显示「单曲循环」而不是「随机播放」（UI 的显示优先级是
     * shuffle > repeat），也保证这两个属性只有这一处会因为无缝被修改。
     * 进入**不写持久化偏好**：意图是会话级的，偏好保留用户进入前的真实模式；
     * 同时这也保证「偏好 == 新值」只可能来自用户手动改（见 LoopIntentDecision 的判据）。
     *
     * **退出（三条规则，全部集中在这里）**：
     * - 切到别的歌（reason=transition 且目标歌 != 武装歌，含 null）→ 两个属性都恢复 prev；
     * - 用户把 repeat 改成非 ONE（reason=repeat，且偏好已写成新值）→ 只清意图，
     *   **不写任何模式**（用户刚选的 repeat 原样保留，shuffle 保持 false，不与用户操作打架）；
     * - 用户改 shuffle（reason=shuffle，且偏好已写成新值）→ shuffle 保留用户新值，
     *   repeat 恢复 prevRepeat；若 prevRepeat == ONE 且用户打开了 shuffle，按不变量
     *   `shuffle=true ⇒ repeat != ONE` 改写成 REPEAT_MODE_ALL 并打日志。
     * 用「持久化偏好 == 新值」区分「用户手动改」与「内部写入」（无缝进入、退出恢复、
     * 队列临时改写都不写偏好），避免把自己触发的回调当成用户操作。
     *
     * 顺序与幂等：
     * 1. 先 [LoopIntent.clear]，再写播放器 —— 写回触发的 onRepeatModeChanged /
     *    onShuffleModeEnabledChanged 回调 refresh 时 armed=null，不会二次恢复；
     * 2. 只写"进入前的值 != 当前值"的字段（[LoopIntentDecision.ModeRestore] 的 null 字段不写），
     *    避免多余事件；写回的字段同时持久化；
     * 3. 无论是否写回，意图都只清一次；后续事件重算幂等。
     */
    private suspend fun applyIntentMode(
        songId: Long?,
        repeatMode: Int,
        shuffleEnabled: Boolean,
        player: Player,
        reason: String,
    ): LoopIntentDecision.Mode {
        val armed = loopIntent.armed.value
            ?: return LoopIntentDecision.Mode(repeatMode, shuffleEnabled)
        val current = LoopIntentDecision.Mode(repeatMode, shuffleEnabled)

        val restore: LoopIntentDecision.ModeRestore? = when {
            reason == REASON_TRANSITION &&
                LoopIntentDecision.shouldExitOnSongChange(armed, songId) ->
                LoopIntentDecision.modeToRestoreOnSongChange(armed, songId, current)

            reason == REASON_REPEAT && repeatMode != Player.REPEAT_MODE_ONE &&
                LoopIntentDecision.shouldClearOnRepeatChange(
                    armed, repeatMode, playbackPreferences.getRepeatMode(),
                ) ->
                LoopIntentDecision.modeToRestoreOnRepeatChange(armed)

            reason == REASON_SHUFFLE &&
                LoopIntentDecision.shouldClearOnShuffleChange(
                    armed, shuffleEnabled, playbackPreferences.getShuffleModeEnabled(),
                ) ->
                LoopIntentDecision.modeToRestoreOnShuffleChange(armed, current)

            else -> null
        }

        if (restore != null) {
            loopIntent.clear()
            val target = LoopIntentDecision.Mode(
                repeatMode = restore.repeatMode ?: repeatMode,
                shuffleEnabled = restore.shuffleEnabled ?: shuffleEnabled,
            )
            withContext(mainDispatcher) {
                restore.repeatMode?.let { player.setRepeatMode(it) }
                restore.shuffleEnabled?.let { player.shuffleModeEnabled = it }
            }
            restore.repeatMode?.let { playbackPreferences.saveRepeatMode(it) }
            restore.shuffleEnabled?.let { playbackPreferences.saveShuffleModeEnabled(it) }
            if (reason == REASON_SHUFFLE &&
                armed.previousRepeatMode == Player.REPEAT_MODE_ONE &&
                target.repeatMode == Player.REPEAT_MODE_ALL
            ) {
                // 不变量强制:用户打开了 shuffle,而进入前的 repeat 恰好是 ONE。
                // shuffle=true ⇒ repeat != ONE,否则 UI 会同时显示「随机」与「单曲」。
                Log.i(
                    TAG,
                    "loop intent exit: shuffle-on invariant prevRepeat=ONE -> ALL " +
                        "(shuffle=true => repeat != ONE)",
                )
            }
            Log.i(
                TAG,
                "loop intent exit: reason=" + reason + " songId=" + songId +
                    " armedSongId=" + armed.songId +
                    " repeat=" + repeatMode + "->" + target.repeatMode +
                    " shuffle=" + shuffleEnabled + "->" + target.shuffleEnabled +
                    " written=" + (restore.repeatMode != null || restore.shuffleEnabled != null),
            )
            return target
        }

        // 进入：只在「武装歌就是当前歌」时应用 —— arm 之后 play 到武装歌才真正进入无缝，
        // 不在别的歌上留下 ONE/false 的副作用（play 失败时用户当前模式不受影响）。
        if (songId != armed.songId) return current

        // 唯一写点：在主线程读实际值再决定写什么（幂等：已是 ONE/false 则零操作）。
        // 先 repeat 后 shuffle —— shuffle 回调里读到的 repeat 已是 ONE。
        return withContext(mainDispatcher) {
            val actualRepeat = player.repeatMode
            val actualShuffle = player.shuffleModeEnabled
            if (actualRepeat == Player.REPEAT_MODE_ONE && !actualShuffle) {
                LoopIntentDecision.Mode(actualRepeat, actualShuffle)
            } else {
                if (actualRepeat != Player.REPEAT_MODE_ONE) {
                    player.setRepeatMode(Player.REPEAT_MODE_ONE)
                }
                if (actualShuffle) {
                    player.shuffleModeEnabled = false
                }
                Log.i(
                    TAG,
                    "loop intent enter: songId=" + armed.songId +
                        " repeat=" + actualRepeat + "->one shuffle=" + actualShuffle + "->false",
                )
                LoopIntentDecision.Mode(Player.REPEAT_MODE_ONE, shuffleEnabled = false)
            }
        }
    }

    /** 注册 shuffle 监听（Player 只在主线程 add/remove；同一实例只注册一次）。 */
    private fun ensureShuffleListener(player: Player) {
        if (listenedPlayer === player) return
        listenedPlayer?.removeListener(shuffleListener)
        player.addListener(shuffleListener)
        listenedPlayer = player
    }

    /** service 销毁时停止行订阅/监听并清空窗口（不取消 [scope]，服务可能被重建）。 */
    fun release() {
        observeJob?.cancel()
        observeJob = null
        observedSongId = null
        playerRef = null
        listenedPlayer?.removeListener(shuffleListener)
        listenedPlayer = null
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
            ringCoveredFrames = audio.coveredFrames,
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
            // 绝不向后 seek:任何"往回跳"都是用户可感知的位置倒退(issue 00001)。
            // 降级本身已经由音频层负责(回捕获/直通继续喂源输入),这里只放行"不倒退"的目标。
            val currentUs = player.currentPosition * LoopSessionProjection.US_PER_MS
            if (targetRawUs < currentUs) {
                Log.w(
                    TAG_PERF,
                    "degraded ignored (backward) targetRawUs=" + targetRawUs +
                        "us currentUs=" + currentUs + "us degradeCount=" + audio.degradeCount,
                )
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

        /** shuffle 变化事件（本类 shuffleListener.onShuffleModeEnabledChanged）。 */
        const val REASON_SHUFFLE = "shuffle"

        /** song_loop 行变化（ensureObservation）。 */
        const val REASON_ROW = "row"

        /** 显式意图变化（observeLoopIntent）。 */
        const val REASON_INTENT = "intent"
    }
}
