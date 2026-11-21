package com.zjr.hesimusic.service

import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import com.zjr.hesimusic.data.dao.SongLoopDao
import com.zjr.hesimusic.data.mapper.mediaIdToSongId
import com.zjr.hesimusic.data.preferences.PlaybackPreferences
import com.zjr.hesimusic.data.model.Song
import com.zjr.hesimusic.data.model.SongLoop
import com.zjr.hesimusic.data.repository.SongRepository
import com.zjr.hesimusic.loop.LoopEligibility
import com.zjr.hesimusic.loop.LoopIntent
import com.zjr.hesimusic.loop.LoopIntentDecision
import com.zjr.hesimusic.loop.LoopRuntime
import com.zjr.hesimusic.loop.LoopWindow
import com.zjr.hesimusic.loop.analysis.PcmDecoder
import com.zjr.hesimusic.loop.audio.LoopAudioController
import com.zjr.hesimusic.loop.audio.source.LoopAwareMediaSourceFactory
import com.zjr.hesimusic.loop.audio.source.LoopEngineInfo
import com.zjr.hesimusic.loop.audio.source.LoopSourceItemTag
import com.zjr.hesimusic.loop.audio.source.LoopSourceMediaItem
import com.zjr.hesimusic.loop.audio.source.LoopSourceProjection
import com.zjr.hesimusic.loop.audio.source.LoopWholeTimeline
import com.zjr.hesimusic.loop.audio.source.PcmFileFormatProbe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

    /**
     * 事件回调用的**非 immediate** 主线程调度器：
     * `scope` 是 Main.immediate，从主线程 launch 会就地执行；若此刻 refresh 正持有
     * [refreshMutex]，就地重入会把互斥量锁死。事件/补算一律排队到主线程队列。
     */
    private val eventDispatcher = Dispatchers.Main
    private val refreshMutex = Mutex()

    /** 当前已应用到播放器/音频层的窗口；值相等即视为形态未变。 */
    private var appliedWindow: LoopWindow? = null

    /** 进入 SOURCE 前当前 item 的原件；退出时只恢复这一项，队列不动。 */
    private var sourceOriginalItem: MediaItem? = null
    private var sourceOriginalIndex: Int = -1

    private var observeJob: Job? = null
    private var observedSongId: Long? = null

    /** 「意图已武装、但当前 item/窗口还没就绪」时的兜底补算任务（2s 内 10 次，幂等）。 */
    private var intentSettleJob: Job? = null

    @Volatile
    private var playerRef: Player? = null

    /** 已注册 shuffle 监听的 player（同一实例只注册一次，release 时移除）。 */
    private var listenedPlayer: Player? = null

    /**
     * 上一次 transition 时 currentMediaItem 是否存在（A-016 的 PLAYLIST_CHANGED 判据）。
     * 只用于区分「PLAYLIST_CHANGED 真的把 item 从空变非空」与「setMediaItems 重设同队列」。
     */
    private var previousItemPresent: Boolean = false

    /**
     * 本层自己监听播放器事件（不依赖 MusicService 的转发顺序）：
     *
     * - **shuffle**：MusicService 没有转发该事件；不监听的话「用户打开随机」不会退出无缝意图，
     *   显示与实际错位。只在有武装意图时重算。
     * - **transition / timeline / state**：`/loop/enter` 是「先 arm 意图、再 setMediaItems」，
     *   冷启动 restore 也是异步灌队列 —— intent 那一次事件里 `currentMediaItem` 还是 null，
     *   于是 songId=null → window=null（真机 40169 复现）。
     *   注意：`setMediaItems` 每次都会触发 `onMediaItemTransition(PLAYLIST_CHANGED)`（A-016），
     *   不能把"重设队列不触发 transition"当前提；这里按 reason 过滤，PLAYLIST_CHANGED 只在
     *   currentMediaItem **由空变非空**时才补算。窗口没落到当前 item 上时按幂等补一次重算。
     */
    private val playerListener = object : Player.Listener {

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            if (loopIntent.armed.value == null) return
            val player = playerRef ?: return
            Log.d(TAG, "refresh reason=shuffle enabled=" + shuffleModeEnabled)
            scope.launch(eventDispatcher) {
                refresh(currentSongId(player), player.repeatMode, player, REASON_SHUFFLE)
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val previous = previousItemPresent
            previousItemPresent = mediaItem != null
            // A-016: setMediaItems 每次都会派发 PLAYLIST_CHANGED;它不是"换歌信号"。
            // 只有 AUTO / REPEAT / SEEK,或 PLAYLIST_CHANGED 让 currentMediaItem 由空变非空,
            // 才值得补算窗口。其余 reason(含同队列重设)直接忽略。
            if (!LoopIntentRefreshPolicy.shouldRefreshOnTransition(
                    reason = reason,
                    previousItemPresent = previous,
                    currentItemPresent = mediaItem != null,
                )
            ) {
                Log.d(TAG, "transition ignored reason=" + reason + " mediaId=" + mediaItem?.mediaId)
                return
            }
            requestIntentRefresh(REASON_PLAYER_TRANSITION)
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            requestIntentRefresh(REASON_PLAYER_TIMELINE)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            // A-077: ExoPlayer 创建时不会派发初始 IDLE;IDLE 事件只在 stop()/错误重置后出现。
            // 保留这个分支(stop 后确实需要),但初始化不能依赖它 —— 初始态由 refresh 主动读
            // player.playbackState(见 refresh 的日志)。
            if (playbackState == Player.STATE_IDLE) return
            requestIntentRefresh(REASON_PLAYER_STATE)
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
        val startedAtMs = System.currentTimeMillis()
        refreshMutex.withLock {
            playerRef = player
            // 0) 显式意图：进入时在唯一写点同时应用 repeat=ONE + shuffle=false；退出时恢复 prev。
            //    shuffle 只有本层监听（MusicService 未转发），这里顺带确保监听已注册。
            val shuffleEnabled = withContext(mainDispatcher) {
                ensurePlayerListener(player)
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
            // 真机排障口径：一次事件的实际输入与输出（songId/armed/repeat/行 A/B/窗口/播放态）。
            Log.i(
                TAG_PERF,
                "refresh reason=" + reason +
                    " route=" + MainPlayerRouting.current.useSourceForMainPlayer +
                    " replacesItem=" + MainPlayerRouting.current.coordinatorReplacesItem +
                    " songId=" + songId + " armed=" + armedSongId +
                    " repeat=" + mode.repeatMode + " row=" + rowDesc(row) +
                    " window=" + window +
                    " state=" + player.playbackState + " pwr=" + player.playWhenReady,
            )
            // 意图已武装但窗口还没算出来：只在「currentMediaItem 还是 null」时启动兜底补算
            // （先 arm 再 setMediaItems / 冷启动 restore）。A-016:setMediaItems 本身会派发
            // PLAYLIST_CHANGED,所以 settle 的前提是"item 由空变非空",不是"队列没变所以没事件"。
            if (window == null && armedSongId != null &&
                LoopIntentRefreshPolicy.shouldStartSettle(player.currentMediaItem == null)
            ) {
                scheduleIntentSettle(player, reason)
            }
            // SOURCE 路线才需要文件路径；M3′ 路线保持原样（多查一次 DB 没有意义）。
            val sourceSong = if (MainPlayerRouting.current.coordinatorReplacesItem && window != null) {
                songRepository.getSongsByIds(listOf(window.songId)).firstOrNull()
            } else {
                null
            }
            withContext(mainDispatcher) {                          // 2) 形态只在主线程动播放器
                syncShape(window, player, sourceSong, reason)
            }
            ensureObservation(songId, player)                      // 3) 行变更订阅
            // 00027：窗口/音频层/接管状态任一变化后，让 UI 的 claim 立即重算（消灭 1s 脏帧）。
            LoopClaimStore.invalidate()
            val costMs = System.currentTimeMillis() - startedAtMs
            if (costMs >= SLOW_REFRESH_WARN_MS) {
                // 真机排障：refresh 卡住会把主线程/事件链一起拖死（/state 只能读到旧缓存）。
                Log.w(TAG_PERF, "refresh slow reason=" + reason + " cost=" + costMs + "ms songId=" + songId)
            }
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

    /** 注册播放器监听（Player 只在主线程 add/remove；同一实例只注册一次）。 */
    private fun ensurePlayerListener(player: Player) {
        if (listenedPlayer === player) return
        listenedPlayer?.removeListener(playerListener)
        player.addListener(playerListener)
        listenedPlayer = player
        // 注册时的 item 存在性作为第一次 transition 的基线(A-016)。
        previousItemPresent = player.currentMediaItem != null
    }

    /** 事件驱动的补算：只在「意图已武装 + 窗口还没落到当前 item」时触发（幂等）。 */
    private fun requestIntentRefresh(reason: String) {
        if (loopIntent.armed.value == null) return
        val player = playerRef ?: return
        if (windowAppliedForCurrentItem(player)) return
        scope.launch(eventDispatcher) {
            try {
                refresh(currentSongId(player), player.repeatMode, player, reason)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG_PERF, "intent refresh failed reason=" + reason, e)
            }
        }
    }

    /** 当前 item 是否已经拿到属于它的窗口（M3′ 看音频层窗口；SOURCE 看 item tag 与窗口一致）。 */
    private fun windowAppliedForCurrentItem(player: Player): Boolean {
        val window = runtime.window.value ?: return false
        val songId = currentSongId(player) ?: return false
        if (window.songId != songId) return false
        if (!MainPlayerRouting.current.coordinatorReplacesItem) {
            return audio.window == window
        }
        val tag = player.currentMediaItem?.localConfiguration?.tag
        return tag is LoopSourceItemTag &&
            tag.songId == window.songId &&
            tag.loopStartUs == window.loopStartUs &&
            tag.loopEndUs == window.loopEndUs
    }

    /**
     * 意图已武装、窗口却还是 null 时的兜底补算。
     *
     * 真机 40169 复现：`/loop/enter` 先 arm 意图、再 `setMediaItems`，intent 那一次 refresh
     * 里 `currentMediaItem` 还是 null（songId=null → 资格不成立 → window=null）。
     * **A-016 纠正**：`setMediaItems` 每次都会派发 `PLAYLIST_CHANGED`，不是"不会触发
     * transition"；但 PLAYLIST_CHANGED 本身不是换歌信号，所以 settle 的真实前提是
     * **currentMediaItem 由空变非空**。这里在 2s 内每 200ms 试一次：item 出现 / 行就绪就把
     * 窗口算出来并落到 item 上，一旦落到（[windowAppliedForCurrentItem]）立即停。
     */
    private fun scheduleIntentSettle(player: Player, reason: String) {
        // 补算循环内部会再进 refresh(→ 这里)：正在跑就不要 cancel 自己，否则只试一次。
        if (intentSettleJob?.isActive == true) return
        intentSettleJob = scope.launch(eventDispatcher) {
            repeat(INTENT_SETTLE_ATTEMPTS) {
                delay(INTENT_SETTLE_INTERVAL_MS)
                if (playerRef !== player) return@launch
                if (loopIntent.armed.value == null) return@launch
                if (windowAppliedForCurrentItem(player)) return@launch
                // settle 前提:currentMediaItem 已由空变非空;还没出现就继续等。
                if (!LoopIntentRefreshPolicy.shouldAttemptSettle(player.currentMediaItem != null)) {
                    return@repeat
                }
                val songId = currentSongId(player)
                if (songId == null) return@repeat
                Log.i(
                    TAG_PERF,
                    "intent settle reason=" + reason + " try=" + (it + 1) + " songId=" + songId +
                        " state=" + player.playbackState + " pwr=" + player.playWhenReady,
                )
                try {
                    refresh(songId, player.repeatMode, player, REASON_INTENT_SETTLE)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG_PERF, "intent settle failed try=" + (it + 1), e)
                }
                if (windowAppliedForCurrentItem(player)) return@launch
            }
            Log.w(TAG_PERF, "intent settle gave up reason=" + reason + " window=" + runtime.window.value)
        }
    }

    /** 行诊断：A/B(ms) 与 confirmed，只用于日志。 */
    private fun rowDesc(row: SongLoop?): String =
        if (row == null) {
            "none"
        } else {
            "A" + row.loopStartMs + "ms/B" + row.loopEndMs + "ms confirmed=" + row.confirmed
        }

    /** service 销毁时停止行订阅/监听并清空窗口（不取消 [scope]，服务可能被重建）。 */
    fun release() {
        observeJob?.cancel()
        observeJob = null
        observedSongId = null
        intentSettleJob?.cancel()
        intentSettleJob = null
        playerRef = null
        listenedPlayer?.removeListener(playerListener)
        listenedPlayer = null
        appliedWindow = null
        ringPrefiller.cancel()
        if (audio.window != null) {
            audio.window = null
        }
        audio.tapWindow = null
        // 退出 SOURCE 形态：丢掉「原 item」备份与失败登记（下次重建重新判定，不继承旧失败）。
        sourceOriginalItem = null
        sourceOriginalIndex = -1
        LoopSourceFallback.reset()
        LoopSourceRuntime.reset()
        LoopPlaybackPhaseRuntime.reset()
        runtime.clear()
        LoopClaimStore.invalidate()
    }

    /**
     * #106 循环中切歌的**强制退场**（同步、主线程；由 [LoopSessionPlayer.seekToNext/Previous]
     * 在跳过 repeat=ONE 之前调用）。
     *
     * 为什么不能只靠 transition 事件：
     * - 多曲队列下原生 seekToNext 会换项（导航口径把 repeat=ONE 降级成 OFF），但**单曲队列**
     *   没有下一项，原生只绕回当前项、不会产生 transition —— 旧循环（intent/窗口）留在原地；
     * - 即便换项成功，旧 item 上的 [LoopSourceItemTag] 仍留在队列里，下次回到该歌会被工厂
     *   再次当成循环源 —— 必须在**旧 item 还是当前项**时把它还原。
     *
     * 顺序：先清意图（避免恢复 repeat/shuffle 的事件被当成"进入"），再恢复进入前的模式
     * （与 applyIntentMode 同一口径），最后撤掉 SOURCE 接管并清窗口/claim。
     */
    fun exitForNavigation(player: Player) {
        val armed = loopIntent.armed.value
        if (armed != null) {
            loopIntent.clear()
            val currentRepeat = player.repeatMode
            val currentShuffle = player.shuffleModeEnabled
            val restoreRepeat = armed.previousRepeatMode.takeIf { it != currentRepeat }
            val restoreShuffle = armed.previousShuffleEnabled.takeIf { it != currentShuffle }
            if (restoreRepeat != null) player.setRepeatMode(restoreRepeat)
            if (restoreShuffle != null) player.shuffleModeEnabled = restoreShuffle
            restoreRepeat?.let { playbackPreferences.saveRepeatMode(it) }
            restoreShuffle?.let { playbackPreferences.saveShuffleModeEnabled(it) }
            Log.i(
                TAG,
                "loop intent exit: reason=navigation songId=" + armed.songId +
                    " repeat=" + currentRepeat + "->" + (restoreRepeat ?: currentRepeat) +
                    " shuffle=" + currentShuffle + "->" + (restoreShuffle ?: currentShuffle),
            )
        }
        runtime.clear()
        audio.window = null
        audio.tapWindow = null
        appliedWindow = null
        val index = player.currentMediaItemIndex
        val tag = player.currentMediaItem?.localConfiguration?.tag
        if (index >= 0 && tag is LoopSourceItemTag) {
            // 把当前项从循环数据源还原成普通文件源（整表重建；A-086）。
            restoreSourceItem(player, index, tag, REASON_NAVIGATION)
        } else {
            LoopSourceRuntime.onReleased()
        }
        LoopPlaybackPhaseRuntime.reset()
        LoopClaimStore.invalidate()
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
    private fun syncShape(
        window: LoopWindow?,
        player: Player,
        sourceSong: Song?,
        reason: String,
    ) {
        // SOURCE（③ 接线点）：只替换当前 item 为带 tag 的循环数据源，队列/首页/通知栏不变。
        // 数据源自己负责循环/PTS/seek，所以这条分支**不再**写 M3′ 的 window/ring
        // （否则 LoopAudioSink 会二次拦截 PCM）；旧的「已换路就跳过」死代码一并删除。
        Log.i(
            TAG_PERF,
            "syncShape route=" + MainPlayerRouting.current.useSourceForMainPlayer +
                " replacesItem=" + MainPlayerRouting.current.coordinatorReplacesItem +
                " window=" + window + " reason=" + reason +
                " songId=" + sourceSong?.id,
        )
        if (MainPlayerRouting.current.coordinatorReplacesItem) {
            syncSourceItem(window, player, sourceSong, reason)
            return
        }
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


    /**
     * SOURCE 形态的唯一写点（③ 接线点）。
     *
     * 进入：**一次原子整表重建**（[rebuildQueueItem]，等价 `setMediaItems(items, index, pos)`）；
     * 落点由 [LoopSourceItemPlan.enter] 给出（A 或 A+entryOffset），队列内容/长度不变，
     * 其它 item 只被原样带过、不被改写。**不用** `replaceMediaItem`（A-086：换源必须重建
     * MediaSource 时它会把当前项当成"已移除"，repeat=ONE 下直接甩到第一项）。
     * 退出：把数据源时间轴 T 映射回媒体时间（`A + floorMod(T,L)`）后恢复原 item（同样走整表重建）。
     *
     * 失败回退（④ 的入口）：预检不通过（格式不支持 / 环过大 / 文件缺失）或该窗口已被登记
     * 不可用时，**不替换** item、不投影，位置继续是媒体时间，并打一条带原因的日志。
     */
    private fun syncSourceItem(
        window: LoopWindow?,
        player: Player,
        song: Song?,
        reason: String,
    ) {
        val index = player.currentMediaItemIndex
        if (index < 0) {
            Log.w(TAG_PERF, "syncSourceItem skip=noIndex route=" +
                MainPlayerRouting.current.useSourceForMainPlayer + " window=" + window)
            return
        }
        val current = player.currentMediaItem ?: return
        val tag = current.localConfiguration?.tag
        Log.i(
            TAG_PERF,
            "syncSourceItem index=" + index + " tag=" + tag +
                " route=" + MainPlayerRouting.current.useSourceForMainPlayer +
                " replacesItem=" + MainPlayerRouting.current.coordinatorReplacesItem +
                " window=" + window + " reason=" + reason,
        )
        val w = window
        // 0-pre) 当前 item 必须就是窗口所属歌：不校验的话，把 A 歌的循环源挂到 B 歌的 item 上
        // 会让两首歌的音频/位置彻底搅乱（场景 12 的二次污染：item 0 是 #41405，播的却是 #39944）。
        // 这里只做防御：真正的落点由"换源用整表 setMediaItems"保证（见 rebuildQueueItem）。
        val currentSongId = current.mediaId?.let { mediaIdToSongId(it) }
        if (w != null && currentSongId != w.songId) {
            Log.w(
                TAG_PERF,
                "syncSourceItem skip=song-mismatch current=" + currentSongId +
                    " window=" + w.songId + " reason=" + reason,
            )
            return
        }
        val unavailable = LoopSourceFallback.isUnavailable(w?.songId, w)
        // 0) 没有窗口 / 该窗口已登记 SOURCE 不可用 → 退出循环（只恢复当前项）。
        if (w == null || !MainPlayerRouting.coordinatorReplacesItem(w, unavailable)) {
            audio.tapWindow = null
            if (tag is LoopSourceItemTag) {
                restoreSourceItem(player, index, tag, reason)
            } else {
                // 当前项已不是 source item（例如队列被外部重设）：确保接管状态不残留。
                LoopSourceRuntime.onReleased()
            }
            return
        }
        // 1) 已经是同窗口的循环 item：幂等（无缝播放期间零媒体操作）。
        if (tag is LoopSourceItemTag &&
            tag.songId == w.songId &&
            tag.loopStartUs == w.loopStartUs &&
            tag.loopEndUs == w.loopEndUs
        ) {
            audio.tapWindow = w
            return
        }
        // 2) 预检：格式不支持 / 环过大 / 文件缺失 → 本次不进 SOURCE，回退 M3′ 形态并登记。
        val filePath = song?.filePath
        val decision = LoopSourceFallback.evaluate(
            window = w,
            songId = w.songId,
            filePath = filePath,
            probe = filePath?.takeIf { it.isNotBlank() }?.let { path ->
                { PcmFileFormatProbe.probe(path) }
            },
            // 时长上界：历史行 B 越界（> 整曲 + 100ms）也判 window-invalid，不建源。
            durationUs = (song?.duration ?: 0L) * 1_000L,
        )
        val ringBytes = when (val d = decision) {
            is LoopSourceFallback.Decision.UseM3Prime -> {
                LoopSourceFallback.reportUnavailable(
                    songId = w.songId,
                    window = w,
                    reason = d.reason,
                    detail = d.detail,
                )
                audio.tapWindow = null
                if (tag is LoopSourceItemTag) restoreSourceItem(player, index, tag, reason)
                return
            }
            is LoopSourceFallback.Decision.UseSource -> d.ringBytes
        }
        // 新窗口预检通过：清掉该歌旧的失败登记，避免 About/日志停在旧 FALLBACK_REASON。
        LoopSourceFallback.clear(w.songId)
        LoopEngineInfo.recordBandBytes(ringBytes)
        val path = filePath
        if (path.isNullOrBlank()) return
        // issue 00028：主播放器用整曲时间轴（prelude/loop/tail）。
        // 首次进入固定 T=0（曲目开头）；只有 A/B 变化且当前已是 source item 时，
        // 才把当前展示位置映射到新窗口时间轴（不重头、不跳 A）。
        val fullDurationUs = (song?.duration ?: 0L) * 1_000L
        val currentTimelineUs = player.currentPosition * 1_000L
        val entryTimelineUs = if (tag is LoopSourceItemTag) {
            val oldWindow = LoopWindow(tag.songId, tag.loopStartUs, tag.loopEndUs)
            val displayUs = if (tag.fullDurationUs > 0L) {
                LoopWholeTimeline.displayUs(currentTimelineUs, oldWindow, tag.fullDurationUs)
            } else {
                LoopSourceProjection.displayUs(currentTimelineUs, oldWindow)
            }
            if (fullDurationUs > 0L) {
                LoopWholeTimeline.timelineUsForDisplay(
                    displayUs = displayUs,
                    currentTimelineUs = currentTimelineUs,
                    window = w,
                    fullDurationUs = fullDurationUs,
                )
            } else {
                displayUs
            }
        } else {
            LoopWholeTimeline.PRELUDE_START_US
        }
        val positionMs = if (tag is LoopSourceItemTag) {
            val oldWindow = LoopWindow(tag.songId, tag.loopStartUs, tag.loopEndUs)
            LoopSourceProjection.displayMs(player.currentPosition, oldWindow)
        } else {
            player.currentPosition
        }
        val plan = LoopSourceItemPlan.enter(
            index = index,
            itemCount = player.mediaItemCount,
            positionMs = positionMs,
            window = w,
            songId = w.songId,
            filePath = path,
            fullDurationUs = fullDurationUs,
            entryTimelineUs = entryTimelineUs,
        )
        if (plan == null) {
            Log.w(TAG, "source plan rejected songId=" + w.songId + " window=" + w)
            return
        }
        audio.tapWindow = w
        if (tag !is LoopSourceItemTag) {
            sourceOriginalItem = current
            sourceOriginalIndex = index
        }
        val stateBefore = player.playbackState
        val playWhenReadyBefore = player.playWhenReady
        val mediaPositionMs = player.currentPosition
        // 关键（00021 / A-086）：循环 item 带不同的 customCacheKey，ExoPlayer 必然重建 MediaSource。
        // 但**绝不能**用 replaceMediaItem 换当前项：media3 1.8.0 的 ExoPlayerImpl.replaceMediaItems
        // 在"不能原地更新源"时退化成 addMediaSource + removeMediaItem(当前项)，被删掉的正是当前
        // period，于是按"当前项被移除，续播后继"去解析 —— repeat=OFF 会变成下一首（index 不变但
        // item 换人），repeat=ONE 更会回退到**第一项**（getNextWindowIndex 原地返回同一 window，
        // resolveSubsequentPeriod 找不到后继 period 后取 firstWindowIndex=0）。
        // 这就是场景 12 / issue 00024"进循环 500ms 内变成 #41405、repeat=off、intent 被清"的根因。
        // 整表 setMediaItems(items, index, position) 是唯一"重建源 + 落点由调用方指定"的公开 API：
        // 队列内容不变，index 与进入落点（A 或 A+entryOffset）都由参数决定，不会漂。
        val tagged = LoopSourceMediaItem.tagged(current, plan.tag)
        val loopCountBefore = LoopAwareMediaSourceFactory.loopSourcesCreated()
        Log.i(TAG_PERF, "source rebuild before index=" + plan.index +
            " " + describeItem(current) + " -> " + describeItem(tagged))
        rebuildQueueItem(player, plan.index, tagged, plan.seekTimelineMs)
        val loopCountAfter = LoopAwareMediaSourceFactory.loopSourcesCreated()
        val builtByFactory = loopCountAfter > loopCountBefore
        Log.i(TAG_PERF, "source rebuild after index=" + player.currentMediaItemIndex +
            " item=" + describeItem(player.currentMediaItem) +
            " loopSources=" + loopCountAfter + " delta=" + (loopCountAfter - loopCountBefore))
        // 00027/P0-1：只有工厂**确实建成**循环源、且该窗口没有被登记为不可用，才宣布接管。
        // 工厂在 createMediaSource 内发现格式/构建失败时会回退 delegate 并登记 BUILD_FAILED；
        // 此时 item 虽然仍带 tag，实际播放的是普通文件源——绝不能 onInstalled。
        val buildUnavailable = LoopSourceFallback.isUnavailable(w.songId, w)
        if (!builtByFactory || buildUnavailable) {
            LoopSourceRuntime.onReleased()
            audio.tapWindow = null
            Log.w(
                TAG_PERF,
                "source install skipped built=" + builtByFactory +
                    " unavailable=" + buildUnavailable +
                    " reason=" + (LoopSourceFallback.reasonOf(w.songId)?.logName ?: "none") +
                    " songId=" + w.songId + " window=" + w +
                    " index=" + plan.index + " reasonEvt=" + reason,
            )
            // 当前 item 是 tagged 但实际为普通文件源（或根本没建源）：立即把 T 折回媒体时间
            // 并恢复原 item，消灭“tag 在、声音普通、UI 仍可能折返”的中间态。
            restoreSourceItem(
                player = player,
                index = plan.index,
                tag = plan.tag,
                reason = reason + "-source-not-built",
                timelineMs = plan.seekTimelineMs,
            )
            return
        }
        // 整表重建把播放器复位成 IDLE：必须 prepare 才会加载新源；play 只在进入前就要求播放时补
        // （pwr=false 时绝不偷播）。
        val revive = LoopSourceRuntime.revivePlan(stateBefore, playWhenReadyBefore, sourcesRebuilt = true)
        if (revive.prepare) player.prepare()
        if (revive.play) player.play()
        LoopSourceRuntime.onInstalled(w, path)
        Log.i(
            TAG,
            "source item installed reason=" + reason + " songId=" + w.songId +
                " window=" + w + " entryTMs=" + plan.seekTimelineMs +
                " band=" + ringBytes + "B itemCount=" + player.mediaItemCount +
                " stateBefore=" + stateBefore + " prepare=" + revive.prepare +
                " play=" + revive.play + " path=" + path,
        )
        Log.i(
            TAG_PERF,
            "source installed index=" + plan.index + " itemCount=" + player.mediaItemCount +
                " route=" + MainPlayerRouting.current.useSourceForMainPlayer +
                " replacesItem=true window=" + w + " fromMediaMs=" + mediaPositionMs +
                " entryTMs=" + plan.seekTimelineMs + " rebuild=true" +
                " cacheKey=" + LoopSourceMediaItem.sourceCacheKey(plan.tag) +
                " band=" + ringBytes + "B" +
                " stateBefore=" + stateBefore + " stateAfter=" + player.playbackState +
                " pwr=" + player.playWhenReady + " reviving=" + revive.prepare + "/" + revive.play,
        )
    }

    /** 00021 取证：item 的实际字段（mediaId/uri/cacheKey/tag），用于确认 tag 真的挂上。 */
    private fun describeItem(item: MediaItem?): String {
        if (item == null) return "null"
        val cfg = item.localConfiguration
        return "mediaId=" + item.mediaId +
            " uri=" + cfg?.uri +
            " key=" + cfg?.customCacheKey +
            " tag=" + (cfg?.tag?.let { it.javaClass.simpleName } ?: "none")
    }

    /**
     * 退出循环：把数据源时间轴 T 映射回**媒体时间**（`A + floorMod(T,L)`）再恢复原 item。
     *
     * 只替换当前 index（[LoopSourceItemPlan.exit] 的 touchedIndices 恒为单个 index），
     * 队列其它项、首页、通知栏都不变。
     *
     * 退出同样不能用 replaceMediaItem（清 cacheKey 也要重建源，A-086 在 repeat=ONE 下同样会
     * 把当前项甩到第一项），必须走 [rebuildQueueItem] 的整表重建。
     */
    private fun restoreSourceItem(
        player: Player,
        index: Int,
        tag: LoopSourceItemTag,
        reason: String,
        timelineMs: Long = player.currentPosition,
    ) {
        val plan = LoopSourceItemPlan.exit(
            index = index,
            itemCount = player.mediaItemCount,
            timelineMs = timelineMs,
            tag = tag,
        )
        val original = sourceOriginalItem?.takeIf { sourceOriginalIndex == index }
        // 退出同样要重建源：清 tag + 清强制重建键，媒体源会回到普通文件源。
        val restored = original ?: player.currentMediaItem?.let { LoopSourceMediaItem.restored(it) }
        sourceOriginalItem = null
        sourceOriginalIndex = -1
        if (restored == null) return
        val stateBefore = player.playbackState
        val playWhenReadyBefore = player.playWhenReady
        val mediaMs = plan?.seekMediaMs ?: player.currentPosition
        rebuildQueueItem(player, index, restored, mediaMs)
        LoopSourceRuntime.onReleased()
        val revive = LoopSourceRuntime.revivePlan(stateBefore, playWhenReadyBefore, sourcesRebuilt = true)
        if (revive.prepare) player.prepare()
        if (revive.play) player.play()
        Log.i(
            TAG,
            "source item restored reason=" + reason + " index=" + index +
                " mediaMs=" + mediaMs + " window=[" + tag.loopStartUs +
                "," + tag.loopEndUs + ")" +
                " state=" + player.playbackState + " pwr=" + player.playWhenReady,
        )
    }

    /**
     * 原子换源：保持队列内容不变，只把 [index] 换成 [item]，并把落点设成 [positionMs]。
     *
     * **为什么不用 `replaceMediaItem`（A-086 / 场景 12 / issue 00024）**：
     * 循环 item 为了逼 ExoPlayer 重建 MediaSource 换过 customCacheKey，于是
     * `ExoPlayerImpl.replaceMediaItems` 必然走 add + remove(当前项) 分支。当前 period 被删掉后
     * 它按"续播后继"解析：repeat=OFF 恰好把临时插入的新源解析回来（看起来正常），
     * 但 repeat=ONE 时 `getNextWindowIndex` 原地返回同一 window，`resolveSubsequentPeriod`
     * 永远找不到后继 period，最后回退到 **firstWindowIndex = 0** —— 进循环立刻切到第 1 首、
     * transition 把 intent 清掉、repeat 被恢复成 off。
     *
     * 整表 `setMediaItems(items, index, positionMs)` 是唯一同时满足"重建源"与"落点由调用方
     * 指定"的公开 API：队列长度/内容不变，index 与位置都不漂。
     */
    private fun rebuildQueueItem(player: Player, index: Int, item: MediaItem, positionMs: Long) {
        val count = player.mediaItemCount
        if (index < 0 || index >= count) {
            Log.w(TAG_PERF, "rebuild skipped index=" + index + " itemCount=" + count)
            return
        }
        val items = ArrayList<MediaItem>(count)
        for (i in 0 until count) {
            items.add(if (i == index) item else player.getMediaItemAt(i))
        }
        player.setMediaItems(items, index, positionMs.coerceAtLeast(0L))
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

        /** shuffle 变化事件（本类 playerListener.onShuffleModeEnabledChanged）。 */
        const val REASON_SHUFFLE = "shuffle"

        /** song_loop 行变化（ensureObservation）。 */
        const val REASON_ROW = "row"

        /** 显式意图变化（observeLoopIntent）。 */
        const val REASON_INTENT = "intent"

        /** 播放器事件补算（本层 playerListener）：切歌 / 时间线变化 / 播放态变化。 */
        const val REASON_PLAYER_TRANSITION = "player-transition"
        const val REASON_PLAYER_TIMELINE = "player-timeline"
        const val REASON_PLAYER_STATE = "player-state"

        /** 意图武装后当前 item/窗口还没就绪时的兜底补算（scheduleIntentSettle）。 */
        const val REASON_INTENT_SETTLE = "intent-settle"

        /** 循环中 next/prev 的强制退场（exitForNavigation）。 */
        const val REASON_NAVIGATION = "navigation"

        /** 兜底补算：最多 10 次 × 200ms（覆盖 setMediaItems/restore 的异步就绪窗口）。 */
        private const val INTENT_SETTLE_ATTEMPTS = 10
        private const val INTENT_SETTLE_INTERVAL_MS = 200L

        /** 单次 refresh 超过该耗时打 warn（含主线程探针/DB/换源）。 */
        private const val SLOW_REFRESH_WARN_MS = 300L
    }
}