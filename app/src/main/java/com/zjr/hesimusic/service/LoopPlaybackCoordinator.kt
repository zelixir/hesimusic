package com.zjr.hesimusic.service

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
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
import com.zjr.hesimusic.loop.LoopModePreference
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

    // ------------------------------------------------------------ 入口快路径缓存(00029)
    //
    // 真机实测：入口从 arm 到开始换源之前有 ~150ms 花在 Room 查询上
    // （song_loop 行 + 整曲时长 + Song 文件路径），而这段时间旧声已经停了，
    // 全部变成入口静音。行订阅本来就会把当前歌的 song_loop 行推过来，
    // 时长/路径对同一首歌也不会变 —— 缓存它们，入口 refresh 不再等 DB。

    /** 最近一次行订阅/查询得到的行（按 songId）。 */
    private var cachedRowSongId: Long? = null
    private var cachedRow: SongLoop? = null

    /** 最近一次查到的 Song（路径/时长），供换源与时长判断复用。 */
    private var cachedSongId: Long? = null
    private var cachedSong: Song? = null

    /** songId -> 整曲时长(us)；文件重扫可能改变时长，但入口静音窗口里不值得再查。 */
    private val durationCacheUs = HashMap<Long, Long>()

    /** 进入 SOURCE 前当前 item 的原件；退出时只恢复这一项，队列不动。 */
    private var sourceOriginalItem: MediaItem? = null
    private var sourceOriginalIndex: Int = -1

    /**
     * 00029：入口快路径插入新 item 后，旧 item 的延迟删除点（-1 = 无）。
     *
     * 删除在 play() 之后 80ms 执行：首声（~60ms）已经出来，队列时间线的 O(N) 维护
     * 不再挡在首声前面；如果这期间发生退出/换歌，[settlePendingEntryRemoval] 会先
     * 把它落地，避免带 tag 的项与索引错位。
     */
    private var pendingEntryOldIndex: Int = -1

    /**
     * #88：SOURCE 已重建但播放器还没 READY 时，先不对外声称"已接管"。
     * 否则 /state 会先报 engine=loop/claim=true，而声音还要等首块解码（真机 ~300ms），
     * "保存并播放"看起来像没播。等 READY（或超时兜底）再 onInstalled + invalidate。
     */
    private data class PendingSourceInstall(
        val window: LoopWindow,
        val path: String,
        val player: Player,
    )
    private var pendingSourceInstall: PendingSourceInstall? = null
    private var pendingInstallTimeout: Job? = null
    private var pendingPublishDelay: Job? = null

    /** 上一次 refresh 看到的意图对象（同一实例 = 没有重新 arm）；用于识别“新一轮进入”。 */
    private var lastArmedRef: LoopIntent.Armed? = null

    /**
     * 00028：用户**新一次** arm 无缝循环后，下一次 source 重建必须落到 T=0（曲目开头）。
     *
     * 不能只看 tag：若进入前当前 item 已经是循环源（例如刚退出/重进、或 A/B 调整），
     * tag 分支会把“当前展示位置”映射到新时间轴（真机 1293 曾出现 pos=15904ms）。
     * “确认并保存”/菜单/调试 /loop/enter 都属于启动无缝循环，一律从 0 起播；
     * 只有进程重启的 restore 保持原位置。
     */
    private var forcePreludeStartOnNextInstall = false
    private val installReadyListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            maybePublishSourceInstall()
        }

        // READY 与 isPlaying=true 不是同一个回调：READY 先到，isPlaying 晚 ~1ms。
        // #88 要求"claim 可见时 playing 已经 true"，所以必须等 isPlaying 回调再发布。
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            maybePublishSourceInstall()
        }

        override fun onPlayerError(error: PlaybackException) {
            clearPendingSourceInstall()
        }
    }

    private var observeJob: Job? = null
    private var observedSongId: Long? = null

    /** 「意图已武装、但当前 item/窗口还没就绪」时的兜底补算任务（2s 内 10 次，幂等）。 */
    private var intentSettleJob: Job? = null

    /**
     * 00029：入口准备兜底。正常路径由 [syncSourceItem] 在环带源 READY 时起播；
     * 若一直没装上/没失败，到点退回普通播放，绝不把播放器留在"准备中"。
     */
    private var entryTimeoutJob: Job? = null

    @Volatile
    private var playerRef: Player? = null

    /** 已注册 shuffle 监听的 player（同一实例只注册一次，release 时移除）。 */
    private var listenedPlayer: Player? = null

    /**
     * D16：切歌退出的**延迟复核**。
     *
     * 进程重启时 setMediaItems + seekTo 会先派发一次“回到第一项”的 transition
     * （真机：restore 已把循环装好，随后 300ms 内 currentMediaItem 短暂变成队列首项再回来）。
     * 若立刻按“切到别的歌”清意图，重启恢复会自己把自己拆掉。因此 armed 歌上已有窗口时，
     * 先不动任何状态，800ms 后仍在别的歌才真正退出。
     */
    private var transitionExitJob: Job? = null

    /**
     * 上一次 transition 时 currentMediaItem 是否存在（A-016 的 PLAYLIST_CHANGED 判据）。
     * 只用于区分「PLAYLIST_CHANGED 真的把 item 从空变非空」与「setMediaItems 重设同队列」。
     */
    private var previousItemPresent: Boolean = false

    /**
     * D16：本层进入循环时写 shuffle=false 的“自写标记”。
     *
     * 进入会把 shuffle 关掉并同步写偏好；随后回来的 onShuffleModeEnabledChanged(false) 与
     * 偏好同值，若当成用户操作会立刻清掉刚建立的意图。标记在事件匹配时被消费；
     * 用户手动改 shuffle（值不同）仍会正常走退出判定。
     */
    private var engineShuffleWrite: Boolean? = null

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
            if (loopIntent.armed.value == null) {
                engineShuffleWrite = null
                return
            }
            val ownWrite = engineShuffleWrite
            if (ownWrite != null && ownWrite == shuffleModeEnabled) {
                // D16：本层进入时写 false 的回声，不是用户操作。
                engineShuffleWrite = null
                Log.d(TAG, "shuffle change ignored engine-write=" + shuffleModeEnabled)
                return
            }
            engineShuffleWrite = null
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
        Log.i(TAG_PERF, "refresh enter reason=" + reason + " songId=" + songId)
        refreshMutex.withLock {
            playerRef = player
            // 0) 显式意图：进入时在唯一写点同时应用 repeat=ONE + shuffle=false；退出时恢复 prev。
            //    shuffle 只有本层监听（MusicService 未转发），这里顺带确保监听已注册。
            val shuffleEnabled = withContext(mainDispatcher) {
                ensurePlayerListener(player)
                player.shuffleModeEnabled
            }
            val armedBefore = loopIntent.armed.value
            // 00028：识别“新一次显式进入”（LoopIntent.arm 每次都会新建 Armed 实例）。
            // 同实例的后续 refresh（row/repeat/transition）不重置落点。
            if (armedBefore == null) {
                lastArmedRef = null
            } else if (armedBefore !== lastArmedRef) {
                lastArmedRef = armedBefore
                // restore 是进程重启恢复，保留保存的位置；其余（菜单/确认并保存/debug enter）从 0。
                if (LoopEntryStartPolicy.shouldForcePreludeStart(
                        armedChanged = true,
                        reason = reason,
                        restoreReason = REASON_RESTORE,
                    )
                ) {
                    forcePreludeStartOnNextInstall = true
                }
                // 00029：入口的起播权在协调器（UI 已 pause 等源）。超时兜底。
                if (loopIntent.isEntryPendingFor(armedBefore.songId)) {
                    Log.i(TAG_PERF, "entry armed observed songId=" + armedBefore.songId)
                    armEntryTimeout(armedBefore.songId, player)
                    // UI 的 pause 命令与 arm 是同一帧发出的两条路径：先等它真的落到
                    // 播放器上（最多 60ms），否则我们起播后那条迟到的 pause 会把新循环暂停。
                    val pauseStartMs = SystemClock.elapsedRealtime()
                    awaitEntryPause(player)
                    Log.i(TAG_PERF, "entry pause done waitMs=" + (SystemClock.elapsedRealtime() - pauseStartMs))
                    // 让出一帧：UI 需要先把 entryPending（"正在进入无缝循环…"）和乐观 0
                    // 画出来，再被这里的换源（主线程同步 work）挡住。否则用户先看到旧位置。
                    // 00029：结果页确认后页面正在 pop、播放页不可见，没有旧位置 UI 需要保护，
                    // 这次 delay 只会等主线程 pop 结束（1333 trace 实测 142ms），跳过。
                    val skipUiSettle = loopIntent.shouldSkipEntryUiSettle(armedBefore.songId)
                    if (!skipUiSettle) delay(ENTRY_UI_SETTLE_MS)
                    Log.i(TAG_PERF, "entry ui settle done skipped=" + skipUiSettle)
                }
            }
            // 上一轮的窗口必须在任何 clear 之前读：D13 的 demote 检测靠它判断“刚才是否真在循环”。
            val previousWindow = runtime.window.value
            // 回到武装歌 / 意图已清 → 之前排队的“切歌退出”复核作废。
            if (armedBefore == null || songId == armedBefore.songId) {
                transitionExitJob?.cancel()
                transitionExitJob = null
            }
            // 重启恢复期的瞬态 transition（setMediaItems/seek 先报队列首项再回来）不能当切歌。
            // 无论窗口是否已装好都先返回（restore 前/后的瞬态顺序不固定），800ms 后仍在别的歌
            // 才由 settle 真正退出。
            if (reason == REASON_TRANSITION && armedBefore != null &&
                songId != armedBefore.songId
            ) {
                scheduleTransitionExitCheck(armedBefore)
                Log.i(
                    TAG,
                    "transition deferred (transient rebuild?) armed=" + armedBefore.songId +
                        " songId=" + songId + " window=" + previousWindow,
                )
                return
            }
            val depsStartMs = SystemClock.elapsedRealtime()
            val row = songId?.takeIf { it > 0L }?.let { rowFor(it) }
            val rowDoneMs = SystemClock.elapsedRealtime()
            // 只有「武装歌 == 当前歌」时才需要整曲时长（D14 的时长上界判据）；其它情况跳过 DB。
            val durationUs = if (armedBefore != null && songId != null && armedBefore.songId == songId) {
                songDurationUs(songId)
            } else {
                0L
            }
            Log.i(
                TAG_PERF,
                "entry deps reason=" + reason + " rowMs=" + (rowDoneMs - depsStartMs) +
                    " totalMs=" + (SystemClock.elapsedRealtime() - depsStartMs) +
                    " armed=" + armedBefore?.songId,
            )

            // 0a) D13：正在循环的歌被 demote / delete → 显式退场（清 intent + 恢复模式 + notice）。
            val revocation = LoopRevocation.detect(
                armed = armedBefore,
                previousWindow = previousWindow,
                songId = songId,
                row = row,
                currentRepeatMode = repeatMode,
                currentShuffleEnabled = shuffleEnabled,
            )
            // 0b) D16/D07：进程重启的一致性门。持久化 intent 只有歌/行/窗口/repeat 全部仍一致
            //     才允许恢复；否则等同“显式退出”，绝不让 UI 显示循环而实际没循环。
            val staleRestore = reason == REASON_RESTORE && armedBefore != null &&
                LoopEligibility.derive(
                    songId = songId,
                    repeatMode = repeatMode,
                    row = row,
                    armedSongId = armedBefore.songId,
                    durationUs = durationUs,
                ) == null

            val mode: LoopIntentDecision.Mode = when {
                revocation != null -> exitRevokedLoop(
                    armed = armedBefore!!,
                    reason = revocation.reason,
                    window = revocation.window,
                    player = player,
                    currentRepeat = repeatMode,
                    currentShuffle = shuffleEnabled,
                    restoreRepeat = revocation.restoreRepeatMode,
                    restoreShuffle = revocation.restoreShuffleEnabled,
                )

                staleRestore -> exitRevokedLoop(
                    armed = armedBefore!!,
                    reason = LoopExitReason.RESTORE_UNAVAILABLE,
                    window = previousWindow,
                    player = player,
                    currentRepeat = repeatMode,
                    currentShuffle = shuffleEnabled,
                    restoreRepeat = armedBefore.previousRepeatMode,
                    restoreShuffle = armedBefore.previousShuffleEnabled,
                )

                else -> applyIntentMode(songId, repeatMode, shuffleEnabled, player, reason)
            }
            val armedSongId = loopIntent.armed.value?.songId
            // 先用同一个纯函数预判一次资格，只为在窗口对外可见**之前**把整曲时长写进音频层：
            // 否则 runtime.refresh 之后还要等一次 DB 查询，UI 可能在这段间隙读到上一首歌的时长。
            val willBeEligible = LoopEligibility.derive(
                songId = songId,
                repeatMode = mode.repeatMode,
                row = row,
                armedSongId = armedSongId,
                durationUs = durationUs,
            ) != null
            audio.fullDurationUs = if (willBeEligible) durationUs else 0L
            runtime.refresh(songId, mode.repeatMode, row, armedSongId, durationUs) // 1) 纯函数重算（唯一窗口来源）
            val window = runtime.window.value
            // #88：SOURCE 路线下窗口已算出、但**当前 item 还不是该窗口的 source tag** →
            // 标记 pending，claim 先保持未生效（不能用 installedWindow 判断：进程内可能
            // 残留上一轮的登记，导致 claim 在重建窗口里先变 true）。
            val currentTag = player.currentMediaItem?.localConfiguration?.tag
            val itemAlreadySource = currentTag is LoopSourceItemTag &&
                window != null &&
                currentTag.songId == window.songId &&
                currentTag.loopStartUs == window.loopStartUs &&
                currentTag.loopEndUs == window.loopEndUs
            if (window != null && MainPlayerRouting.current.coordinatorReplacesItem && !itemAlreadySource) {
                LoopSourceRuntime.onInstallPending(window)
            } else if (window == null) {
                LoopSourceRuntime.clearPendingInstall()
            }
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
                songFor(window.songId)
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
     * 进入**同时写持久化偏好**（D16）：单曲循环 + 关随机必须与 UI 显示一致，重启后才能
     * 由同一个偏好恢复出同一个模式；进入前的真实模式记录在 [LoopIntent.Armed] 里，退出时恢复。
     * 自写的 shuffle=false 事件由 [engineShuffleWrite] 标记消费，不会被误判成用户操作
     * （见 LoopIntentDecision 的“偏好 == 新值 = 用户手动改”判据）。
     *
     * **退出（三条规则，全部集中在这里）**：
     * - 切到别的歌（reason=transition 且目标歌 != 武装歌，含 null）→ 两个属性都恢复 prev；
     * - 用户把 repeat 改成非 ONE（reason=repeat，且偏好已写成新值）→ 只清意图，
     *   **不写任何模式**（用户刚选的 repeat 原样保留，shuffle 保持 false，不与用户操作打架）；
     * - 用户改 shuffle（reason=shuffle，且偏好已写成新值）→ shuffle 保留用户新值，
     *   repeat 恢复 prevRepeat；若 prevRepeat == ONE 且用户打开了 shuffle，按不变量
     *   `shuffle=true ⇒ repeat != ONE` 改写成 REPEAT_MODE_ALL 并打日志。
     * 用「持久化偏好 == 新值」区分「用户手动改」与「内部写入」：进入写偏好但写的是 ONE/false
     * （repeat 事件不会清意图），自写的 shuffle=false 由 [engineShuffleWrite] 吞掉；退出恢复与
     * 队列临时改写不写偏好，因此只有用户手动改才会命中 clean 判据。
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
            (reason == REASON_TRANSITION || reason == REASON_TRANSITION_SETTLE) &&
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
        val entered = withContext(mainDispatcher) {
            val actualRepeat = player.repeatMode
            val actualShuffle = player.shuffleModeEnabled
            if (actualRepeat == Player.REPEAT_MODE_ONE && !actualShuffle) {
                LoopIntentDecision.Mode(actualRepeat, actualShuffle)
            } else {
                if (actualRepeat != Player.REPEAT_MODE_ONE) {
                    player.setRepeatMode(Player.REPEAT_MODE_ONE)
                }
                if (actualShuffle) {
                    // D16：记下“这次 false 是本层写的”，事件回来时不要当成用户操作。
                    engineShuffleWrite = false
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
        // D16：进入 = 把“当前实际模式”写进偏好，UI 显示 / 播放器 / 重启恢复三者同一口径。
        persistEnteredMode(armed.songId)
        return entered
    }

    /** 把 [LoopModePreference.Entered] 写进偏好（已一致时零写入）。 */
    private fun persistEnteredMode(songId: Long) {
        val entered = LoopModePreference.Entered
        val repeatDirty = playbackPreferences.getRepeatMode() != entered.repeatMode
        val shuffleDirty = playbackPreferences.getShuffleModeEnabled() != entered.shuffleEnabled
        if (repeatDirty) playbackPreferences.saveRepeatMode(entered.repeatMode)
        if (shuffleDirty) playbackPreferences.saveShuffleModeEnabled(entered.shuffleEnabled)
        if (repeatDirty || shuffleDirty) {
            Log.i(
                TAG,
                "loop mode persisted songId=" + songId +
                    " repeat=" + entered.repeatMode + " shuffle=" + entered.shuffleEnabled +
                    " (repeatDirty=" + repeatDirty + " shuffleDirty=" + shuffleDirty + ")",
            )
        }
    }

    /**
     * D13 / D16：**显式退场**（demote、删除结果、重启恢复失败）。
     *
     * 顺序：先清 intent（写回触发的事件不会二次进入）→ 写播放器 → 写偏好 → 发布可见 notice。
     * 偏好无条件写成进入前的值（重复写同值无副作用），保证“重启后显示的模式”与“实际是否在循环”
     * 由同一份数据决定；notice 由 UI 收集（LoopClaimStore.exitNoticeFlow）做一次性提示。
     */
    private suspend fun exitRevokedLoop(
        armed: LoopIntent.Armed,
        reason: LoopExitReason,
        window: LoopWindow?,
        player: Player,
        currentRepeat: Int,
        currentShuffle: Boolean,
        restoreRepeat: Int,
        restoreShuffle: Boolean,
    ): LoopIntentDecision.Mode {
        loopIntent.clear()
        engineShuffleWrite = null
        val actual = withContext(mainDispatcher) {
            if (player.repeatMode != restoreRepeat) player.setRepeatMode(restoreRepeat)
            if (player.shuffleModeEnabled != restoreShuffle) {
                player.shuffleModeEnabled = restoreShuffle
            }
            LoopIntentDecision.Mode(player.repeatMode, player.shuffleModeEnabled)
        }
        playbackPreferences.saveRepeatMode(restoreRepeat)
        playbackPreferences.saveShuffleModeEnabled(restoreShuffle)
        val notice = LoopExitNotice(
            songId = armed.songId,
            reason = reason,
            window = window,
            previousRepeatMode = currentRepeat,
            nextRepeatMode = actual.repeatMode,
            previousShuffleEnabled = currentShuffle,
            nextShuffleEnabled = actual.shuffleEnabled,
            notifiedAtMs = System.currentTimeMillis(),
        )
        LoopClaimStore.publishExit(notice)
        Log.i(TAG, notice.summary())
        return actual
    }

    /** 排一次切歌退出复核（已有排队任务则复用）。 */
    private fun scheduleTransitionExitCheck(armed: LoopIntent.Armed) {
        if (transitionExitJob?.isActive == true) return
        transitionExitJob = scope.launch(eventDispatcher) {
            delay(TRANSITION_EXIT_SETTLE_MS)
            val player = playerRef ?: return@launch
            val stillArmed = loopIntent.armed.value ?: return@launch
            if (stillArmed.songId != armed.songId) return@launch
            val nowSong = currentSongId(player)
            if (nowSong == armed.songId) return@launch
            Log.i(
                TAG,
                "transition settle: armed=" + armed.songId + " current=" + nowSong +
                    " -> exit intent",
            )
            try {
                refresh(nowSong, player.repeatMode, player, REASON_TRANSITION_SETTLE)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG_PERF, "transition settle refresh failed", e)
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

    // ------------------------------------------------------------------ 00029 入口闸门

    /**
     * 00029：入口准备兜底。
     *
     * 正常路径：UI 先 pause + 归 0 → 环带源重建 READY → [syncSourceItem] 清 entry 并起播。
     * 异常路径（item/行一直不就绪、窗口算不出来）：到点清 entry 并退回普通播放，
     * 绝不把播放器永远留在"准备中"（用户会以为卡死）。
     */
    /**
     * 00029：等 UI 入口的 pause 落到播放器（MediaController 命令与本地 arm 是两条路径）。
     *
     * 不等待的后果：协调器在源 READY 后 play()，随后迟到的 pause 命令到达，把新循环停在 0。
     * 正常情况下一到两次 10ms 就能观察到 pwr=false；60ms 兜底（宁可多一点静音，不可旧声/假死）。
     */
    private suspend fun awaitEntryPause(player: Player) {
        repeat(ENTRY_PAUSE_WAIT_STEPS) {
            if (!player.playWhenReady) return
            delay(ENTRY_PAUSE_WAIT_STEP_MS)
        }
        Log.w(TAG_PERF, "entry pause not observed pwr=true after wait")
    }

    private fun armEntryTimeout(songId: Long, player: Player) {
        entryTimeoutJob?.cancel()
        entryTimeoutJob = scope.launch(eventDispatcher) {
            delay(ENTRY_FALLBACK_TIMEOUT_MS)
            if (!loopIntent.isEntryPendingFor(songId)) return@launch
            Log.w(
                TAG_PERF,
                "entry timeout -> normal playback songId=" + songId +
                    " state=" + player.playbackState + " item=" + describeItem(player.currentMediaItem),
            )
            loopIntent.clearEntry(songId)
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            player.play()
        }
    }

    /**
     * 00029：入口失败（源不可用 / 建源失败 / 计划被拒）时的回退。
     *
     * 用户点「无缝循环」的意图是**播放这首歌**；环带不可用不能让播放器停在入口的暂停态。
     * 此时 item 已被还原为普通源、落点 0，直接 prepare + play（普通播放）即可；
     * UI 会通过 claim 的 fallbackReason 显示"未生效"的原因。
     */
    private fun abortEntryPlayback(player: Player, songId: Long, reason: String) {
        if (!loopIntent.isEntryPendingFor(songId)) return
        loopIntent.clearEntry(songId)
        Log.w(
            TAG_PERF,
            "entry aborted reason=" + reason + " songId=" + songId +
                " state=" + player.playbackState + " pos=" + player.currentPosition,
        )
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        player.play()
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
        clearPendingSourceInstall()
        observeJob?.cancel()
        observeJob = null
        observedSongId = null
        intentSettleJob?.cancel()
        intentSettleJob = null
        entryTimeoutJob?.cancel()
        entryTimeoutJob = null
        transitionExitJob?.cancel()
        transitionExitJob = null
        playerRef = null
        listenedPlayer?.removeListener(playerListener)
        listenedPlayer = null
        engineShuffleWrite = null
        appliedWindow = null
        cachedRowSongId = null
        cachedRow = null
        cachedSongId = null
        cachedSong = null
        durationCacheUs.clear()
        pendingEntryOldIndex = -1
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
        settlePendingEntryRemoval(player)
        clearPendingSourceInstall()
        val armed = loopIntent.armed.value
        if (armed != null) {
            loopIntent.clear()
            val currentRepeat = player.repeatMode
            val currentShuffle = player.shuffleModeEnabled
            val restoreRepeat = armed.previousRepeatMode.takeIf { it != currentRepeat }
            val restoreShuffle = armed.previousShuffleEnabled.takeIf { it != currentShuffle }
            if (restoreRepeat != null) player.setRepeatMode(restoreRepeat)
            if (restoreShuffle != null) player.shuffleModeEnabled = restoreShuffle
            // D16：导航退场同样恢复“进入前模式”，偏好与玩家值一起写，重启后一致。
            playbackPreferences.saveRepeatMode(armed.previousRepeatMode)
            playbackPreferences.saveShuffleModeEnabled(armed.previousShuffleEnabled)
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
                Log.i(TAG_PERF, "entry collector armed songId=" + armed?.songId)
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
    private suspend fun syncShape(
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
        // D14：M3 写入音频层前的最后一道闸门——窗口必须过 LoopWindowRules。
        // eligibility 已拦住坏行；这里防的是 audio/runtime 回退等旁路把 1-sample/越界窗口写进 sink。
        val targetWindow = LoopWindowRules.writable(
            LoopShapePlan.targetWindow(itemSongId, window),
            audio.fullDurationUs,
        )

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
    private suspend fun syncSourceItem(
        window: LoopWindow?,
        player: Player,
        song: Song?,
        reason: String,
    ) {
        // 上一轮入口的旧 item 如果还没删，先落地：否则后面的 index/tag 判断会错位。
        settlePendingEntryRemoval(player)
        val index = player.currentMediaItemIndex
        if (index < 0) {
            Log.w(TAG_PERF, "syncSourceItem skip=noIndex route=" +
                MainPlayerRouting.current.useSourceForMainPlayer + " window=" + window)
            LoopSourceRuntime.clearPendingInstall()
            return
        }
        val current = player.currentMediaItem ?: run {
            LoopSourceRuntime.clearPendingInstall()
            return
        }
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
            LoopSourceRuntime.clearPendingInstall()
            return
        }
        val unavailable = LoopSourceFallback.isUnavailable(w?.songId, w)
        // 0) 没有窗口 / 该窗口已登记 SOURCE 不可用 → 退出循环（只恢复当前项）。
        if (w == null || !MainPlayerRouting.coordinatorReplacesItem(w, unavailable)) {
            clearPendingSourceInstall()
            audio.tapWindow = null
            // 退出：先把入口留下的延迟旧项删掉，再接"当前项"还原（index 会回到换源前）。
            settlePendingEntryRemoval(player, force = true)
            val exitIndex = player.currentMediaItemIndex
            if (tag is LoopSourceItemTag) {
                restoreSourceItem(player, exitIndex, tag, reason)
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
            // 00029：入口时当前项已经是同一环带源（再次点「无缝循环」/ A-B 未变）：
            // UI 已 pause + 归 0，这里直接从 0 起播，不走重建（重建会白等 ~300ms）。
            if (loopIntent.isEntryPendingFor(w.songId)) {
                player.seekTo(index, LoopWholeTimeline.PRELUDE_START_US / 1_000L)
                LoopSourceRuntime.onInstalled(
                    w,
                    song?.filePath ?: LoopSourceRuntime.installedPath.orEmpty(),
                )
                loopIntent.clearEntry(w.songId)
                LoopClaimStore.invalidate()
                if (player.playbackState == Player.STATE_IDLE) player.prepare()
                player.play()
                Log.i(
                    TAG_PERF,
                    "entry on installed source songId=" + w.songId +
                        " pos=" + player.currentPosition + " reason=" + reason,
                )
                return
            }
            // 只有"没有正在等待 READY 的 pending"时才补发布；否则这里的 row/repeat/transition
            // 重复 refresh 会把 deferred publish 提前，claim 又回到 playing=false 的窗口。
            if (LoopSourceRuntime.installedWindow != w && !LoopSourceRuntime.isInstallPendingFor(w)) {
                LoopSourceRuntime.onInstalled(w, song?.filePath ?: LoopSourceRuntime.installedPath.orEmpty())
            }
            return
        }
        // 1b) 入口过渡期还没删旧项、而窗口已经变了：先归一化队列，再重新走一次 refresh。
        // 否则下面会拿到 index=旧项+1、把新窗口挂到错误的 item 上。
        if (pendingEntryOldIndex >= 0) {
            Log.i(
                TAG_PERF,
                "entry pending old item settled before window change index=" + index +
                    " window=" + w + " reason=" + reason,
            )
            settlePendingEntryRemoval(player, force = true)
            requestIntentRefresh(REASON_ROW)
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
                // 00029：入口目标不可用（格式/预算/文件）→ 从 0 普通播放，不停在准备中。
                abortEntryPlayback(player, w.songId, "source-unavailable:" + d.reason.logName)
                return
            }
            is LoopSourceFallback.Decision.UseSource -> d.ringBytes
        }
        // 新窗口预检通过：清掉该歌旧的失败登记，避免 About/日志停在旧 FALLBACK_REASON。
        LoopSourceFallback.clear(w.songId)
        LoopEngineInfo.recordBandBytes(ringBytes)
        val path = filePath
        if (path.isNullOrBlank()) {
            LoopSourceRuntime.clearPendingInstall()
            abortEntryPlayback(player, w.songId, "missing-path")
            return
        }
        // issue 00028：主播放器用整曲时间轴（prelude/loop/tail）。
        // 首次进入固定 T=0（曲目开头）；只有 A/B 变化且当前已是 source item 时，
        // 才把当前展示位置映射到新窗口时间轴（不重头、不跳 A）。
        val fullDurationUs = (song?.duration ?: 0L) * 1_000L
        val currentTimelineUs = player.currentPosition * 1_000L
        val forcePreludeStart = forcePreludeStartOnNextInstall
        val entryTimelineUs = if (forcePreludeStart && fullDurationUs > 0L) {
            // 00028：新一次进入必须从曲目开头（prelude T=0），即使当前 item 已经是循环源。
            LoopWholeTimeline.PRELUDE_START_US
        } else if (tag is LoopSourceItemTag) {
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
            LoopSourceRuntime.clearPendingInstall()
            abortEntryPlayback(player, w.songId, "plan-rejected")
            return
        }
        // 真正重建前再标一次 pending（refresh 与这里之间可能被 row/repeat 事件插队）。
        LoopSourceRuntime.onInstallPending(w)
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
        val entryPending = loopIntent.isEntryPendingFor(w.songId)
        Log.i(TAG_PERF, "source rebuild before index=" + plan.index +
            " " + describeItem(current) + " -> " + describeItem(tagged) +
            " entryTMs=" + plan.seekTimelineMs + " forcedPreludeStart=" + forcePreludeStart +
            " entryPending=" + entryPending)
        // 入口：先把新 item 插到当前项之后并切过去（只建 1 个源），旧项等 play() 之后
        // 再删 —— 删除的 1714 项队列时间线维护不必挡在首声前面。
        var pendingOldIndex = -1
        if (entryPending) {
            pendingOldIndex = installEntrySourceItem(player, plan.index, tagged, plan.seekTimelineMs)
            if (pendingOldIndex < 0) {
                Log.w(TAG_PERF, "entry fast install failed -> full rebuild index=" + plan.index)
                rebuildQueueItem(player, plan.index, tagged, plan.seekTimelineMs)
            }
        } else {
            rebuildQueueItem(player, plan.index, tagged, plan.seekTimelineMs)
        }
        forcePreludeStartOnNextInstall = false
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
            clearPendingSourceInstall()
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
            if (pendingOldIndex >= 0) {
                rollbackEntrySourceItem(player, pendingOldIndex, plan.seekTimelineMs)
            } else {
                restoreSourceItem(
                    player = player,
                    index = plan.index,
                    tag = plan.tag,
                    reason = reason + "-source-not-built",
                    timelineMs = plan.seekTimelineMs,
                )
            }
            abortEntryPlayback(player, w.songId, "source-not-built")
            return
        }
        // 整表重建把播放器复位成 IDLE：必须 prepare 才会加载新源；play 只在进入前就要求播放时补
        // （pwr=false 时绝不偷播）。
        // 00029：以“rebuild 完成后的实际状态”决定是否需要显式 prepare，避免二次 configure。
        val stateAfterRebuild = player.playbackState
        val revive = LoopSourceRuntime.revivePlan(
            stateBefore,
            playWhenReadyBefore,
            sourcesRebuilt = true,
            stateAfterRebuild = stateAfterRebuild,
        )
        if (revive.prepare) player.prepare()
        if (entryPending) {
            // 00029：入口在源 READY 前是暂停的（旧位置绝不出声），这里才是真正的起播点。
            // 先清 entry，再 play（媒体会话层的入口闸门只拦"源未就绪时的普通 play"）。
            loopIntent.clearEntry(w.songId)
            player.play()
        } else if (revive.play) {
            player.play()
        }
        // play() 已经发出：旧项留到 claim 发布（~READY+350ms）时再删。首声先出来，
        // 队列时间线的 O(N) 维护不再挡在首声前面（真机：立刻删会让首声多等 ~55ms）。
        // 期间的 player-state/timeline refresh 会命中"同窗口"幂等分支，不触发删除。
        if (pendingOldIndex >= 0) {
            pendingEntryOldIndex = pendingOldIndex
        }
        Log.i(
            TAG_PERF,
            "source revive stateBefore=" + stateBefore + " stateAfterRebuild=" + stateAfterRebuild +
                " pwr=" + playWhenReadyBefore + " entryPending=" + entryPending +
                " prepare=" + revive.prepare + " play=" + (revive.play || entryPending),
        )
        scheduleSourceInstallPublish(player, w, path)
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

    /**
     * #88：只在该窗口的数据源真的可播（READY/ENDED）时才发布接管；否则挂起等回调，
     * 2.5s 超时兜底发布（避免暂停/异常时永远不 claim）。
     */
    private fun scheduleSourceInstallPublish(player: Player, window: LoopWindow, path: String) {
        clearPendingSourceInstall()
        pendingSourceInstall = PendingSourceInstall(window, path, player)
        player.addListener(installReadyListener)
        if (SourceInstallPublishPolicy.shouldPublish(
                playbackState = player.playbackState,
                playWhenReady = player.playWhenReady,
                isPlaying = player.isPlaying,
                force = false,
            )
        ) {
            scheduleDeferredPublish()
            return
        }
        pendingInstallTimeout = scope.launch(eventDispatcher) {
            delay(SOURCE_INSTALL_PUBLISH_TIMEOUT_MS)
            if (pendingSourceInstall != null) {
                Log.w(
                    TAG_PERF,
                    "source install publish timeout state=" + player.playbackState +
                        " window=" + window,
                )
                // 00029：入口等不到 READY 也要表态 —— 清 entry 并起播，否则用户只看到
                // "准备中"且没有声音（入口的暂停是我们自己做的，不是用户意图）。
                val entryPending = loopIntent.isEntryPendingFor(window.songId)
                if (entryPending) loopIntent.clearEntry(window.songId)
                publishPendingSourceInstall(force = true)
                if (entryPending) {
                    if (player.playbackState == Player.STATE_IDLE) player.prepare()
                    player.play()
                    Log.w(TAG_PERF, "entry played on publish timeout songId=" + window.songId)
                }
            }
        }
        Log.i(
            TAG_PERF,
            "source install pending state=" + player.playbackState +
                " window=" + window + " (claim waits for READY)",
        )
    }

    private fun maybePublishSourceInstall() {
        val pending = pendingSourceInstall ?: return
        // 00029：首声已经渲染（isPlaying=true）时再删入口留下的旧项：既不影响首声，
        // 也把"队列多一项"的过渡窗口压到最短（之后才 claim 发布的 350ms 等待）。
        if (pending.player.isPlaying) {
            settlePendingEntryRemoval(pending.player, force = true)
        }
        if (SourceInstallPublishPolicy.shouldPublish(
                playbackState = pending.player.playbackState,
                playWhenReady = pending.player.playWhenReady,
                isPlaying = pending.player.isPlaying,
                force = false,
            )
        ) {
            scheduleDeferredPublish()
        }
    }

    /**
     * 等一个小的主线程窗口再发布：MediaSession 先把 isPlaying=true 同步给 controller，
     * 我们再把 claim 放出来。否则会出现 claim=true / controller playing=false 的 1ms 假死。
     */
    private fun scheduleDeferredPublish() {
        if (pendingPublishDelay != null) return
        val pending = pendingSourceInstall
        Log.i(
            TAG_PERF,
            "source install deferred-publish in " + CLAIM_PUBLISH_SETTLE_MS + "ms" +
                " state=" + (pending?.player?.playbackState ?: -1) +
                " isPlaying=" + (pending?.player?.isPlaying == true) +
                " window=" + pending?.window,
        )
        pendingPublishDelay = scope.launch(eventDispatcher) {
            delay(CLAIM_PUBLISH_SETTLE_MS)
            pendingPublishDelay = null
            publishPendingSourceInstall(force = false)
        }
    }

    private fun publishPendingSourceInstall(force: Boolean) {
        val pending = pendingSourceInstall ?: return
        if (!SourceInstallPublishPolicy.shouldPublish(
                playbackState = pending.player.playbackState,
                playWhenReady = pending.player.playWhenReady,
                isPlaying = pending.player.isPlaying,
                force = force,
            )
        ) {
            return
        }
        pendingSourceInstall = null
        pendingInstallTimeout?.cancel()
        pendingInstallTimeout = null
        pendingPublishDelay?.cancel()
        pendingPublishDelay = null
        pending.player.removeListener(installReadyListener)
        // 入口过渡期的第 N+1 项到此为止：claim 可见时队列必须已回到原始长度/索引。
        settlePendingEntryRemoval(pending.player, force = true)
        publishSourceInstall(pending.window, pending.path, pending.player.playbackState)
    }

    private fun publishSourceInstall(window: LoopWindow, path: String, playbackState: Int) {
        LoopSourceRuntime.onInstalled(window, path)
        LoopClaimStore.invalidate()
        Log.i(
            TAG_PERF,
            "source install published playbackState=" + playbackState +
                " window=" + window + " songId=" + window.songId,
        )
    }

    private fun clearPendingSourceInstall() {
        pendingInstallTimeout?.cancel()
        pendingInstallTimeout = null
        pendingPublishDelay?.cancel()
        pendingPublishDelay = null
        pendingSourceInstall?.player?.removeListener(installReadyListener)
        pendingSourceInstall = null
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
        clearPendingSourceInstall()
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
        val stateAfterRebuild = player.playbackState
        val revive = LoopSourceRuntime.revivePlan(
            stateBefore,
            playWhenReadyBefore,
            sourcesRebuilt = true,
            stateAfterRebuild = stateAfterRebuild,
        )
        if (!playWhenReadyBefore) {
            // #90 回归：暂停态换源时 setMediaItems 仍可能自动 prepare 并预填一个 buffer，
            // 造成 writtenFrames 增长（真机 4079f）。立即 stop 丢弃预填，只保留一个
            // pending seek；等用户真正 play() 时再由 LoopSessionPlayer 统一 prepare。
            player.stop()
            player.seekTo(index, mediaMs)
            Log.i(
                TAG,
                "source restore paused: stop-and-seek mediaMs=" + mediaMs +
                    " index=" + index + " reason=" + reason +
                    " state=" + player.playbackState + " pwr=" + player.playWhenReady,
            )
        } else {
            if (revive.prepare) player.prepare()
            if (revive.play) player.play()
        }
        Log.i(
            TAG,
            "source item restored reason=" + reason + " index=" + index +
                " mediaMs=" + mediaMs + " window=[" + tag.loopStartUs +
                "," + tag.loopEndUs + ")" +
                " state=" + player.playbackState + " pwr=" + player.playWhenReady,
        )
    }

    /**
     * 00029：入口专用的换源（只为当前项建一个 MediaSource）。
     *
     * 语义与 `setMediaItems(整表, index, positionMs)` 等价，但不重建队列其它 N-1 项：
     * 1. 在当前项**之后**插入带 tag 的循环 item（工厂只为这一项 createMediaSource）；
     * 2. 切到该项（入口落点恒为 0）；
     * 3. 删掉旧项 —— 此时它不是当前项，不会触发 A-086 的"当前 period 被移除"问题。
     *
     * 为什么必须这样做：media3 的 setMediaItems 会为队列每一项同步 createMediaSource；
     * 真机 1714 首实测 setMediaItems=170ms（建源 92ms），而入口 pause 已经让旧声停止，
     * 这 170ms 会全部变成静音，超过 #88(120ms)/#94(300ms) 的入口空洞判据。
     * 本路径只建 1 个环带源（~11ms）+ 一次切项。
     *
     * @return true = 完成且当前项确实是带 tag 的循环 item；false = 已回滚，调用方必须走整表重建。
     */
    private suspend fun installEntrySourceItem(
        player: Player,
        index: Int,
        item: MediaItem,
        positionMs: Long,
    ): Int {
        val count = player.mediaItemCount
        if (index < 0 || index >= count) return -1
        val expected = item.localConfiguration?.tag as? LoopSourceItemTag ?: return -1
        val startedMs = SystemClock.elapsedRealtime()
        var injected = false
        return try {
            LoopAwareMediaSourceFactory.resetCreateStats()
            player.addMediaItem(index + 1, item)
            val addMs = SystemClock.elapsedRealtime() - startedMs
            injected = true
            player.seekTo(index + 1, positionMs.coerceAtLeast(0L))
            val seekMs = SystemClock.elapsedRealtime() - startedMs - addMs
            // 入口在暂停态（结果页确认）时 add/seek 的 MediaController 命令落地有异步窗口：
            // 立刻读 currentMediaItemIndex 会看到旧 index，误判失败后走整表重建，首声多 300ms+。
            // 等当前项真正切到 index+1（最多 250ms）再判定。
            var currentIndex = player.currentMediaItemIndex
            var tag = player.currentMediaItem?.localConfiguration?.tag as? LoopSourceItemTag
            val waitStart = SystemClock.elapsedRealtime()
            while (SystemClock.elapsedRealtime() - waitStart < 250L) {
                currentIndex = player.currentMediaItemIndex
                tag = player.currentMediaItem?.localConfiguration?.tag as? LoopSourceItemTag
                val ready = currentIndex == index + 1 &&
                    player.mediaItemCount == count + 1 &&
                    tag != null &&
                    tag.songId == expected.songId &&
                    tag.loopStartUs == expected.loopStartUs &&
                    tag.loopEndUs == expected.loopEndUs
                if (ready) break
                delay(5L)
            }
            val waitMs = SystemClock.elapsedRealtime() - waitStart
            val (created, createMs) = LoopAwareMediaSourceFactory.drainCreateStats()
            val ok = currentIndex == index + 1 &&
                player.mediaItemCount == count + 1 &&
                tag != null &&
                tag.songId == expected.songId &&
                tag.loopStartUs == expected.loopStartUs &&
                tag.loopEndUs == expected.loopEndUs
            Log.i(
                TAG_PERF,
                "entry fast install insert count=" + count + " index=" + index +
                    " current=" + currentIndex + " addMs=" + addMs + " seekMs=" + seekMs +
                    " waitMs=" + waitMs +
                    " sourcesCreated=" + created + " createSourcesMs=" + createMs +
                    " ok=" + ok,
            )
            if (ok) index else {
                rollbackEntrySourceItem(player, index, positionMs)
                -1
            }
        } catch (t: Throwable) {
            Log.w(TAG_PERF, "entry fast install failed injected=" + injected, t)
            runCatching {
                if (injected && player.mediaItemCount == count + 1) {
                    player.removeMediaItem(index + 1)
                }
            }
            -1
        }
    }

    /**
     * 把入口留下的"多一项"落地。
     *
     * - 非 force：当前项仍是入口插入的那一项时**不删**（入口自己的 player-state/timeline
     *   幂等 refresh 不能把旧项删在首声前面）；
     * - force：退出/发布 claim 时立即归一化队列（index 回到换源前）。
     */
    private fun settlePendingEntryRemoval(player: Player, force: Boolean = false) {
        val oldIndex = pendingEntryOldIndex
        if (oldIndex < 0) return
        if (!force && player.currentMediaItemIndex == oldIndex + 1) return
        pendingEntryOldIndex = -1
        runCatching {
            if (player.mediaItemCount > oldIndex &&
                player.currentMediaItemIndex == oldIndex + 1
            ) {
                player.removeMediaItem(oldIndex)
                Log.i(TAG_PERF, "pending entry old item removed oldIndex=" + oldIndex)
            }
        }
    }

    /** 入口换源失败：删掉插入的带 tag 项，把当前项还原成原 item / 落点。 */
    private fun rollbackEntrySourceItem(player: Player, oldIndex: Int, positionMs: Long) {
        runCatching {
            if (player.mediaItemCount > oldIndex + 1 &&
                player.currentMediaItemIndex == oldIndex + 1
            ) {
                player.removeMediaItem(oldIndex + 1)
            }
            player.seekTo(oldIndex, positionMs.coerceAtLeast(0L))
        }.onFailure { Log.w(TAG_PERF, "entry rollback failed oldIndex=$oldIndex", it) }
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
        val startedMs = android.os.SystemClock.elapsedRealtime()
        val items = ArrayList<MediaItem>(count)
        for (i in 0 until count) {
            items.add(if (i == index) item else player.getMediaItemAt(i))
        }
        val listMs = android.os.SystemClock.elapsedRealtime() - startedMs
        LoopAwareMediaSourceFactory.resetCreateStats()
        val setStartedMs = android.os.SystemClock.elapsedRealtime()
        player.setMediaItems(items, index, positionMs.coerceAtLeast(0L))
        val setMs = android.os.SystemClock.elapsedRealtime() - setStartedMs
        val (created, createMs) = LoopAwareMediaSourceFactory.drainCreateStats()
        // 00029：入口静音窗口 = setMediaItems 的同步阻塞时间。queue 越大，这里越贵
        // （media3 会为每一项调一次 createMediaSource）；把两个分量分开记录才能判断
        // "缩短静音"该优化队列重建还是环带源本身。
        Log.i(
            TAG_PERF,
            "rebuild timing count=" + count + " listMs=" + listMs +
                " setMediaItemsMs=" + setMs + " sourcesCreated=" + created +
                " createSourcesMs=" + createMs,
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
        // 换歌后旧歌的缓存不能当新歌用；新订阅的首次 emission 会立刻把行写回缓存。
        cachedRowSongId = null
        cachedRow = null
        observeJob = validSongId?.let { id ->
            scope.launch {
                songLoopDao.getBySongIdFlow(id).collect { row ->
                    // 行内容变化（含删除后的 null）→ 完整重算一次；同时暖入口缓存。
                    cachedRowSongId = id
                    cachedRow = row
                    refresh(id, player.repeatMode, player, REASON_ROW)
                }
            }
        }
    }

    // ------------------------------------------------------------------ 工具

    private fun currentSongId(player: Player): Long? =
        player.currentMediaItem?.mediaId?.let { mediaIdToSongId(it) }

    /** song_loop 行：行订阅已推过来的当前歌直接命中缓存，入口不再等 Room。 */
    private suspend fun rowFor(songId: Long): SongLoop? {
        if (cachedRowSongId == songId) return cachedRow
        val row = songLoopDao.getBySongId(songId)
        cachedRowSongId = songId
        cachedRow = row
        return row
    }

    /** Song（路径/时长）：同一首歌复用，避免入口 refresh 里的第二次 Room 查询。 */
    private suspend fun songFor(songId: Long): Song? {
        if (cachedSongId == songId) return cachedSong
        // 00029：结果页/菜单入口把 Song 一起带过来，入口不再等 Room（1334 trace 132ms）。
        loopIntent.entrySongFor(songId)?.let { song ->
            cachedSongId = songId
            cachedSong = song
            durationCacheUs[songId] = song.duration * LoopSessionProjection.US_PER_MS
            return song
        }
        val song = songRepository.getSongsByIds(listOf(songId)).firstOrNull()
        if (song != null) {
            cachedSongId = songId
            cachedSong = song
            durationCacheUs[songId] = song.duration * LoopSessionProjection.US_PER_MS
        }
        return song
    }

    private suspend fun songDurationUs(songId: Long?): Long {
        if (songId == null || songId <= 0L) return 0L
        durationCacheUs[songId]?.let { return it }
        val song = songFor(songId) ?: return 0L
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

        /** #88：数据源 READY 前不发布接管，最多等这么久（暂停/异常兜底）。 */
        private const val SOURCE_INSTALL_PUBLISH_TIMEOUT_MS = 2_500L

        /**
         * #88：READY/isPlaying 之后等 controller 收到状态再发布 claim。
         *
         * 真机 1288 实测：isPlaying=true 到 MediaController 快照更新约 200ms；80ms 仍会
         * 采到 claim=true/playing=false。350ms 覆盖这段 IPC/快照滞后，用户侧只多等 0.35s。
         */
        private const val CLAIM_PUBLISH_SETTLE_MS = 350L

        /** MusicService restorePlaybackState 完成后的重算（D16 的来源，用于一致性校验）。 */
        const val REASON_RESTORE = "restore"

        /** 瞬态 transition 的延迟复核（800ms 后仍在别的歌才退出）。 */
        const val REASON_TRANSITION_SETTLE = "transition-settle"

        /** 重启恢复期 setMediaItems/seek 派发的“队列首项”瞬态窗口。 */
        private const val TRANSITION_EXIT_SETTLE_MS = 800L

        /** 兜底补算：最多 10 次 × 200ms（覆盖 setMediaItems/restore 的异步就绪窗口）。 */
        private const val INTENT_SETTLE_ATTEMPTS = 10
        private const val INTENT_SETTLE_INTERVAL_MS = 200L

        /**
         * 00029：入口准备的最长等待（与 claim 发布超时同量级）。
         * 超过它仍没有可播的环带源 → 清 entry 并退回普通播放。
         */
        private const val ENTRY_FALLBACK_TIMEOUT_MS = 2_500L

        /** 00029：等 UI 入口 pause 落地的最多步数/步长（总 60ms）。 */
        private const val ENTRY_PAUSE_WAIT_STEPS = 6
        private const val ENTRY_PAUSE_WAIT_STEP_MS = 10L

        /** 00029：入口换源前让出的一帧（给 Compose 画"准备中"+0，避免先显示旧位置）。 */
        private const val ENTRY_UI_SETTLE_MS = 16L


        /** 单次 refresh 超过该耗时打 warn（含主线程探针/DB/换源）。 */
        private const val SLOW_REFRESH_WARN_MS = 300L
    }
}

/**
 * #88：SOURCE 重建后"何时对外发布接管"的纯策略（单测 SourceInstallPublishPolicyTest）。
 *
 * 旧行为是 rebuild 完立即 onInstalled：/state 先报 engine=loop/claim=true，但首块 PCM
 * 还要 ~300ms 才 READY，于是"保存并播放"那一刻 playing=false（听觉上像没播）。
 * 现在等播放器 READY/ENDED 再发布；超时/异常由协调器强制兜底，不会永久不 claim。
 */
/**
 * 00028：新一次进入无缝循环是否必须从曲目开头（prelude T=0）起播。
 *
 * “确认并保存”/菜单/debug /loop/enter 都算启动无缝循环 → 0；
 * 进程重启的 restore 保留保存位置；同一 intent 的后续 refresh 不算新进入。
 */
internal object LoopEntryStartPolicy {

    fun shouldForcePreludeStart(armedChanged: Boolean, reason: String, restoreReason: String): Boolean =
        armedChanged && reason != restoreReason
}

internal object SourceInstallPublishPolicy {

    /**
     * READY 只说明可以播；如果用户要求播放（pwr=true）还要等 isPlaying=true，
     * 保证"claim 可见"与"playing=true"同一次 UI 采样里成立（#88 的 300ms 假死窗口）。
     * 暂停态（pwr=false）在 READY 即可发布：暂停进入循环也必须 claim=true。
     */
    fun shouldPublish(
        playbackState: Int,
        playWhenReady: Boolean,
        isPlaying: Boolean,
        force: Boolean,
    ): Boolean {
        if (force) return true
        if (playbackState == Player.STATE_ENDED) return true
        if (playbackState != Player.STATE_READY) return false
        return isPlaying || !playWhenReady
    }
}