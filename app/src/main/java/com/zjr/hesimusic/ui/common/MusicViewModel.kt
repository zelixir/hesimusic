package com.zjr.hesimusic.ui.common

import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.os.CountDownTimer
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.zjr.hesimusic.data.mapper.buildQueueMediaId
import com.zjr.hesimusic.data.mapper.mediaIdToSongId
import com.zjr.hesimusic.data.mapper.toMediaItem
import com.zjr.hesimusic.data.model.Song
import com.zjr.hesimusic.data.preferences.PlaybackPreferences
import com.zjr.hesimusic.data.preferences.PlaylistContext
import com.zjr.hesimusic.data.repository.FavoriteRepository
import com.zjr.hesimusic.data.repository.SongRepository
import com.zjr.hesimusic.data.scanner.TagLibHelper
import com.zjr.hesimusic.loop.LoopIntent
import com.zjr.hesimusic.loop.LoopRuntime
import com.zjr.hesimusic.loop.LoopWindow
import com.zjr.hesimusic.service.LoopClaimStore
import com.zjr.hesimusic.service.MusicNavigationPolicy
import com.zjr.hesimusic.service.MusicService
import com.zjr.hesimusic.ui.player.state.PlayerPageShadow
import com.zjr.hesimusic.ui.player.state.PlayerPageShadowDiagnostics
import com.zjr.hesimusic.ui.player.state.PlayerPageShadowGate
import com.zjr.hesimusic.ui.player.state.PlayerSnapshot
import com.zjr.hesimusic.ui.player.state.RepeatMode
import com.zjr.hesimusic.ui.player.state.SeamlessIntent
import com.zjr.hesimusic.ui.player.state.SnapshotPlaybackState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class MusicViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playbackPreferences: PlaybackPreferences,
    private val songRepository: SongRepository,
    private val favoriteRepository: FavoriteRepository,
    private val tagLibHelper: TagLibHelper,
    private val loopRuntime: LoopRuntime,
    private val loopIntent: LoopIntent
) : ViewModel() {

    companion object {
        private const val TAG = "MusicViewModel"
        private const val TAG_SHADOW = "PlayerPageShadow"

        /**
         * seek 后的强制刷新窗口。
         *
         * MediaSession 的周期位置事件默认 3s 一次(A-066),所以窗口必须 >3s;窗口内
         * controller 已乐观 mask 成新位置(A-067),刷新只是为了让暂停态拖动也能在一次
         * tick 内看到 session 侧 clamp/调整后的最终值。
         */
        private const val POSITION_REFRESH_WINDOW_MS = PlaybackPositionContract.REFRESH_WINDOW_MS
    }

    private var mediaControllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null

    /**
     * 播放页状态机的 debug 影子双跑：只读取播放器快照、不执行 effect、不写 UI 字段。
     *
     * 开关由 debug 源集的 DebugInitProvider 置位；release 构建永远为 null。
     */
    private val playerPageShadow: PlayerPageShadow? =
        if (PlayerPageShadowGate.enabled) PlayerPageShadow() else null

    private val _uiState = MutableStateFlow(MusicUiState())
    val uiState: StateFlow<MusicUiState> = _uiState.asStateFlow()

    private val _sleepTimerState = MutableStateFlow<Long?>(null)
    val sleepTimerState: StateFlow<Long?> = _sleepTimerState.asStateFlow()
    
    // Expose the saved playlist context for UI to use when restoring state
    private val _savedPlaylistContext = MutableStateFlow<PlaylistContext?>(null)
    val savedPlaylistContext: StateFlow<PlaylistContext?> = _savedPlaylistContext.asStateFlow()

    /**
     * 当前无缝循环窗口(全局唯一来源 [LoopRuntime] 的直接转发)。
     *
     * null = 普通播放。只读,不做 DB 查询、不做播放操作;切歌/降级/删除行后由服务层
     * 重算 [LoopRuntime.refresh] → 这里自动变 null。
     *
     * 注意:它只代表"用户 armed 了无缝",**不代表源真的在循环**(00027)。展示层请用
     * [activeLoopWindow];位置折返在 [updateState] 里还会额外看 SOURCE 是否可用。
     */
    val loopWindow: StateFlow<LoopWindow?> = loopRuntime.window

    /**
     * **命中当前播放歌、且源真的生效**的无缝窗口;否则 null。
     *
     * 00027:唯一真值来自 [LoopClaimStore]（`LoopClaim.effectiveWindow`）。这里不再自己
     * 读 `loopRuntime.window` 折算布尔；`revisionFlow` 让 registry/接管状态一变就重算，
     * 避免“回退已登记但 A/B 标记要等下一次 1s 位置刷新才消失”的脏帧。
     */
    val activeLoopWindow: StateFlow<LoopWindow?> = combine(_uiState, LoopClaimStore.revisionFlow) { state, _ ->
        val currentSongId = state.currentPlayingSongId?.toLongOrNull()
        LoopClaimStore.claimFor(currentSongId).effectiveWindow
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // Queue entries carry their own unique mediaId so they stay distinguishable from
    // base-playlist copies of the same song while they sit in the player timeline.
    private val _playQueue = MutableStateFlow<List<QueuedItem>>(emptyList())
    val playQueueSongIds: StateFlow<List<Long>> = _playQueue
        .map { queue -> queue.map { it.songId } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private var queueMediaIdSequence = 0L
    private var queuedPlaybackRestoreRepeatMode: Int? = null
    private var queuedPlaybackRestoreShuffleModeEnabled: Boolean? = null
    private var isSeekingToQueuedSong = false

    private var sleepTimer: CountDownTimer? = null
    
    // Track the file path for which artwork has been loaded to avoid redundant loading
    private var loadedArtworkPath: String? = null

    init {
        Log.d(TAG, "init: MusicViewModel created")
        
        // Load last played song and playlist context for immediate UI update
        viewModelScope.launch {
            val queueIds = playbackPreferences.getQueue()
            val savedSongId = playbackPreferences.getCurrentSongId()
            val currentIndex = playbackPreferences.getCurrentSongIndex()
            val playlistContext = playbackPreferences.getPlaylistContext()
            
            Log.d(TAG, "init: restoring state - queueIds=${queueIds.size}, savedSongId=$savedSongId, currentIndex=$currentIndex, playlistContext=$playlistContext")
            
            // Update the saved playlist context for UI to use
            _savedPlaylistContext.value = playlistContext
            
            // Prefer song ID over index (more reliable when shuffle was enabled)
            val songIdToLoad = if (savedSongId > 0) {
                savedSongId
            } else if (queueIds.isNotEmpty() && currentIndex in queueIds.indices) {
                queueIds[currentIndex]
            } else {
                null
            }
            
            if (songIdToLoad != null) {
                val songs = songRepository.getSongsByIds(listOf(songIdToLoad))
                val song = songs.firstOrNull()
                if (song != null) {
                    Log.d(TAG, "init: restored song '${song.title}' (id=$songIdToLoad)")
                    val mediaItem = song.toMediaItem()
                    val position = playbackPreferences.getSavedPosition()
                    val isFavorite = favoriteRepository.isFavoriteSync(song.filePath, song.startPosition)
                    // Ensure duration is valid (not negative)
                    val safeDuration = song.duration.coerceAtLeast(0L)
                    val safePosition = position.coerceAtLeast(0L)
                    _uiState.update {
                        it.copy(
                            currentMediaItem = mediaItem,
                            currentPosition = safePosition,
                            duration = safeDuration,
                            currentSongFilePath = song.filePath,
                            currentSongStartPosition = song.startPosition,
                            isCurrentSongFavorite = isFavorite,
                            playlistContext = playlistContext
                        )
                    }
                } else {
                    Log.w(TAG, "init: could not find song with id $songIdToLoad")
                }
            } else {
                Log.d(TAG, "init: no valid saved state to restore")
            }
        }

        // 00027：claim 真值一变（接管/回退/窗口应用）就立刻重读位置，
        // 不再等 1s 的进度 tick，避免“回退已生效、进度条还折返一帧”。
        viewModelScope.launch {
            LoopClaimStore.revisionFlow.collect {
                // 服务销毁/controller 断连时读位置可能抛：claim 刷新绝不能让 UI 崩。
                runCatching { updateState() }
            }
        }

        val sessionToken = SessionToken(context, ComponentName(context, MusicService::class.java))
        mediaControllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        mediaControllerFuture?.addListener({
            mediaController = mediaControllerFuture?.get()
            mediaController?.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    updateState()
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    updateState()
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    Log.d(TAG, "onMediaItemTransition: mediaId=${mediaItem?.mediaId}, reason=$reason")
                    val landedMediaId = mediaItem?.mediaId
                    if (maybeSeekToQueuedSong(landedMediaId, reason)) {
                        return
                    }
                    consumePlayQueue(landedMediaId)
                    updateState()
                    // Update favorite status when song changes
                    updateCurrentSongFavoriteStatus()
                    // Load artwork when song changes
                    loadArtworkForCurrentSong()
                }

                override fun onRepeatModeChanged(repeatMode: Int) {
                    updateState()
                }

                override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                    updateState()
                }
                
                override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                    pruneQueueMissingItems()
                    updateState()
                }
            })
            // A-077: controller 创建/连接期不会补发初始 IDLE 回调,初始状态必须主动读一次。
            updateState()
            updateCurrentSongFavoriteStatus()
            loadArtworkForCurrentSong()
            startProgressUpdateLoop()
        }, MoreExecutors.directExecutor())
    }

    private fun updateCurrentSongFavoriteStatus() {
        viewModelScope.launch {
            val currentFilePath = _uiState.value.currentSongFilePath
            val currentStartPosition = _uiState.value.currentSongStartPosition
            if (currentFilePath != null) {
                val isFavorite = favoriteRepository.isFavoriteSync(currentFilePath, currentStartPosition)
                _uiState.update { it.copy(isCurrentSongFavorite = isFavorite) }
            }
        }
    }
    
    private fun loadArtworkForCurrentSong() {
        viewModelScope.launch {
            val currentFilePath = _uiState.value.currentSongFilePath
            if (currentFilePath != null && currentFilePath != loadedArtworkPath) {
                // Clear previous artwork immediately when song changes
                _uiState.update { it.copy(artworkBytes = null) }
                loadedArtworkPath = currentFilePath
                
                // Load artwork asynchronously on IO dispatcher
                val artworkBytes = withContext(Dispatchers.IO) {
                    try {
                        tagLibHelper.extractArtwork(currentFilePath)
                    } catch (e: Exception) {
                        null
                    }
                }
                
                // Only update if the current song hasn't changed while loading
                if (_uiState.value.currentSongFilePath == currentFilePath) {
                    _uiState.update { it.copy(artworkBytes = artworkBytes) }
                }
            }
        }
    }

    /** seek 之后的强制刷新窗口(毫秒),见 [startProgressUpdateLoop] 的注释。 */
    @Volatile
    private var positionRefreshUntilMs = 0L

    private fun startProgressUpdateLoop() {
        viewModelScope.launch {
            while (isActive) {
                // 播放中每秒刷新;暂停态只在「刚 seek 过」的窗口内刷新。
                // 机制(A-066):MediaSession 的周期位置事件默认 **3s** 一次(只在播放/加载中),
                // 两次事件之间 controller 按**当前 speed** 本地外推。暂停态没有这个周期事件,
                // 若不补刷新,拖动后要等下一次事件才更新(看起来像"拖动无效/回弹")。
                // 所以窗口由 [PlaybackPositionContract] 保证 >3s;tick 仍是 1s,只决定读的时机。
                if (PlaybackPositionContract.shouldRefresh(
                        isPlaying = mediaController?.isPlaying == true,
                        nowElapsedMs = SystemClock.elapsedRealtime(),
                        refreshUntilMs = positionRefreshUntilMs,
                    )
                ) {
                    updateState()
                }
                delay(1000) // 读位置的节奏,不是 MediaSession 的事件周期
            }
        }
    }

    private fun updateState() {
        mediaController?.let { controller ->
            val playlist = List(controller.mediaItemCount) { i -> controller.getMediaItemAt(i) }
            val audioSessionId = controller.sessionExtras.getInt(MusicService.KEY_AUDIO_SESSION_ID, 0)
            // Extract file path from current media item URI
            val currentFilePath = controller.currentMediaItem?.localConfiguration?.uri?.path
            // Map the mediaId back to a song id so queued copies (unique mediaId) still
            // highlight their song in the UI lists
            val currentPlayingSongId = controller.currentMediaItem?.mediaId
                ?.let { mediaIdToSongId(it)?.toString() }
            // Extract start position from clipping configuration (for CUE tracks)
            // This corresponds to Song.startPosition which is set in SongMapper.toMediaItem()
            // For non-CUE tracks, clippingConfiguration is not set so we default to 0L
            val currentStartPosition = controller.currentMediaItem?.clippingConfiguration?.startPositionMs ?: 0L
            // 无缝循环期间把**展示位置**折返回 [A, B):
            // MediaSession 的周期位置事件默认 3s 一次(A-066),两次事件之间 MediaController 按
            // **当前 speed** 本地外推,所以跨过 B 的瞬间会看到位置冲出区间(下一次事件后回落)。
            // 折返是幂等的:服务层已折返过的位置再折一次不变;窗口不属于当前歌时原样透传。
            // duration/seek 不变。
            //
            // 00027:窗口可能只是"用户意图"——预算/格式/构建失败时 SOURCE 根本没生效,
            // 实际播放的是普通整曲。此时必须原样透传,否则进度条折回 A、耳朵却在尾奏。
            val currentSongIdLong = currentPlayingSongId?.toLongOrNull()
            val loopClaim = LoopClaimStore.claimFor(currentSongIdLong)
            val safePosition = foldDisplayPositionMs(
                rawPositionMs = controller.currentPosition.coerceAtLeast(0L),
                claim = loopClaim,
            )
            
            // Capture all controller state before any async operation
            val isPlaying = controller.isPlaying
            val currentMediaItem = controller.currentMediaItem
            val playbackState = controller.playbackState
            val repeatMode = controller.repeatMode
            val shuffleModeEnabled = controller.shuffleModeEnabled
            val bufferedPosition = controller.bufferedPosition
            val controllerDuration = controller.duration
            val playerError = controller.playerError?.message
            
            // Helper function to update UI state with given duration
            fun performStateUpdate(duration: Long) {
                _uiState.update {
                    it.copy(
                        isPlaying = isPlaying,
                        currentMediaItem = currentMediaItem,
                        playbackState = playbackState,
                        repeatMode = repeatMode,
                        shuffleModeEnabled = shuffleModeEnabled,
                        currentPosition = safePosition,
                        duration = duration,
                        bufferedPosition = bufferedPosition,
                        playlist = playlist,
                        audioSessionId = audioSessionId,
                        currentSongFilePath = currentFilePath,
                        currentSongStartPosition = currentStartPosition,
                        currentPlayingSongId = currentPlayingSongId
                    )
                }
                observePlayerPageShadow(
                    songId = currentPlayingSongId?.toLongOrNull(),
                    isPlaying = isPlaying,
                    playbackState = playbackState,
                    repeatMode = repeatMode,
                    shuffle = shuffleModeEnabled,
                    positionMs = safePosition,
                    durationMs = duration,
                    errorMessage = playerError,
                )
            }
            
            // If controller.duration is negative (C.TIME_UNSET), get duration from database
            if (controllerDuration < 0 && currentFilePath != null) {
                viewModelScope.launch {
                    val song = songRepository.getSongByFilePathAndStartPosition(currentFilePath, currentStartPosition)
                    val correctDuration = song?.duration?.coerceAtLeast(0L) ?: 0L
                    performStateUpdate(correctDuration)
                }
            } else {
                performStateUpdate(controllerDuration.coerceAtLeast(0L))
            }
        }
    }

    /**
     * debug 影子双跑的唯一入口：读取快照、喂状态机、记差异；**不执行 effect、不改 UI 字段**。
     */
    private fun observePlayerPageShadow(
        songId: Long?,
        isPlaying: Boolean,
        playbackState: Int,
        repeatMode: Int,
        shuffle: Boolean,
        positionMs: Long,
        durationMs: Long,
        errorMessage: String?,
    ) {
        val shadow = playerPageShadow ?: return
        val now = SystemClock.elapsedRealtime()
        val armed = loopIntent.armed.value
        val seamless = armed?.takeIf {
            songId != null &&
                it.songId == songId &&
                repeatMode == Player.REPEAT_MODE_ONE &&
                !shuffle
        }?.let {
            SeamlessIntent(
                songId = it.songId,
                previousRepeat = it.previousRepeatMode.toPlayerPageRepeatMode(),
                previousShuffle = it.previousShuffleEnabled,
            )
        }
        val snapshot = PlayerSnapshot(
            songId = songId,
            isPlaying = isPlaying,
            positionMs = positionMs.coerceAtLeast(0L),
            durationMs = durationMs,
            repeat = repeatMode.toPlayerPageRepeatMode(),
            shuffle = shuffle,
            playbackState = playbackState.toSnapshotPlaybackState(),
            errorMessage = errorMessage,
            seamlessIntent = seamless,
            nowMs = now,
        )
        val observation = try {
            shadow.observe(snapshot)
        } catch (t: Throwable) {
            Log.w(TAG_SHADOW, "shadow observe failed", t)
            return
        }
        PlayerPageShadowDiagnostics.record(observation, now)
        if (observation.mismatches.isNotEmpty()) {
            Log.w(
                TAG_SHADOW,
                "mismatch #" + shadow.mismatchCount + ": " + observation.mismatches.joinToString("; "),
            )
        }
    }

    private fun Int.toPlayerPageRepeatMode(): RepeatMode = when (this) {
        Player.REPEAT_MODE_ONE -> RepeatMode.ONE
        Player.REPEAT_MODE_ALL -> RepeatMode.ALL
        else -> RepeatMode.OFF
    }

    private fun Int.toSnapshotPlaybackState(): SnapshotPlaybackState = when (this) {
        Player.STATE_BUFFERING -> SnapshotPlaybackState.BUFFERING
        Player.STATE_READY -> SnapshotPlaybackState.READY
        Player.STATE_ENDED -> SnapshotPlaybackState.ENDED
        else -> SnapshotPlaybackState.IDLE
    }

    fun play(song: Song) {
        mediaController?.let { controller ->
            val mediaItem = song.toMediaItem()
            controller.setMediaItem(mediaItem)
            controller.prepare()
            controller.play()
        }
    }

    /**
     * 播放 [song],但**当它已经是当前 item 时保持时间轴不动**。
     *
     * 无缝循环入口(菜单「无缝循环」/ 结果页「保存并播放」)专用:对正在播放的同一首歌
     * 再走一遍 [play] 会 setMediaItem + prepare 重建队列、位置归零,并让播放器 flush,
     * 用户能听到 0.x 秒的断音。此时只需要 play()(IDLE 时补一次 prepare)。
     *
     * 判定见 [shouldKeepCurrentItem];不满足时退回 [play] 的原有行为。
     */
    fun playKeepPosition(song: Song) {
        val controller = mediaController ?: return
        val currentMediaId = controller.currentMediaItem?.mediaId
        val sameQueueHead = currentMediaId != null &&
            _playQueue.value.firstOrNull()?.mediaId == currentMediaId
        val keepCurrent = shouldKeepCurrentItem(
            currentMediaId = currentMediaId,
            songId = song.id,
            sameQueueHead = sameQueueHead,
        )
        val positionMs = controller.currentPosition.coerceAtLeast(0L)
        Log.d(
            TAG,
            "keep-position play songId=${song.id} sameItem=$keepCurrent posMs=$positionMs"
        )
        if (!keepCurrent) {
            play(song)
            return
        }
        when (controller.playbackState) {
            Player.STATE_IDLE -> controller.prepare()
            // A finished item has no meaningful position to keep; restart it in place.
            Player.STATE_ENDED -> controller.seekToDefaultPosition()
        }
        controller.play()
    }
    
    fun playList(songs: List<Song>, startIndex: Int = 0, context: PlaylistContext? = null) {
        mediaController?.let { controller ->
            Log.d(TAG, "playList: ${songs.size} songs, startIndex=$startIndex, context=$context")
            val mediaItems = songs.map { it.toMediaItem() }
            controller.setMediaItems(mediaItems, startIndex, 0)
            controller.prepare()
            controller.play()
            
            // Save playlist context if provided
            context?.let {
                playbackPreferences.savePlaylistContext(it)
                Log.d(TAG, "playList: saved context $it")
                _uiState.update { state -> state.copy(playlistContext = it) }
            }
            _playQueue.value = emptyList()
            clearQueuePlaybackOverrides()
        }
    }

    fun addSongsToPlayQueue(songs: List<Song>) {
        if (songs.isEmpty()) return
        mediaController?.let { controller ->
            applyQueuePlaybackOverridesIfNeeded(controller)
            val insertIndex = (controller.currentMediaItemIndex + 1 + _playQueue.value.size)
                .coerceIn(0, controller.mediaItemCount)
            // Register queue entries before touching the timeline so the upcoming
            // onTimelineChanged callback still sees them as existing items
            val mediaItems = songs.map { song ->
                val mediaId = buildQueueMediaId(queueMediaIdSequence++, song.id)
                _playQueue.update { it + QueuedItem(mediaId, song.id) }
                song.toMediaItem().buildUpon().setMediaId(mediaId).build()
            }
            controller.addMediaItems(insertIndex, mediaItems)
        }
    }

    fun pause() {
        mediaController?.pause()
    }

    fun resume() {
        mediaController?.play()
    }
    
    fun skipToNext() {
        mediaController?.let { controller ->
            val wasPlaying = controller.isPlaying
            val hasNext = controller.hasNextMediaItem()
            if (hasNext) {
                controller.seekToNext()
                // Auto-play if was paused
                if (!wasPlaying) {
                    controller.play()
                }
            } else if (MusicNavigationPolicy.shouldWrapToStart(
                    hasNext = hasNext,
                    repeatMode = controller.repeatMode,
                    mediaItemCount = controller.mediaItemCount,
                )
            ) {
                // A-085: repeat=ALL 时 hasNextMediaItem() 恒 true(播放器自己绕回),
                // 手写绕回只对 repeat != ALL 的最后一项生效。
                controller.seekToDefaultPosition(0)
                // Auto-play if was paused
                if (!wasPlaying) {
                    controller.play()
                }
            }
        }
    }
    
    fun skipToPrevious() {
        mediaController?.seekToPrevious()
    }

    fun seekTo(position: Long) {
        mediaController?.seekTo(position)
        // 影子只观测：记录 seek 请求（debug 双跑仍按 PlayerPageShadow 自己的窗口抑制对比）。
        playerPageShadow?.onSeekRequested(position.coerceAtLeast(0L), SystemClock.elapsedRealtime())
        // A-067: MediaController.seekTo 会**立即乐观 mask** 成新位置/新状态,这里马上
        // updateState() 读到的已经是新值,不是旧快照。仍保留刷新窗口,是因为要等
        // session 侧 clamp/调整后的最终值(以及暂停态没有 3s 周期事件);窗口必须 >3s。
        positionRefreshUntilMs = SystemClock.elapsedRealtime() + POSITION_REFRESH_WINDOW_MS
        updateState() // Immediate update for UI responsiveness (optimistic mask already applied)
    }

    fun setRepeatMode(repeatMode: Int) {
        mediaController?.repeatMode = repeatMode
        playbackPreferences.saveRepeatMode(repeatMode)
    }

    fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) {
        mediaController?.shuffleModeEnabled = shuffleModeEnabled
        playbackPreferences.saveShuffleModeEnabled(shuffleModeEnabled)
    }
    
    fun clearSavedPlaylistContext() {
        _savedPlaylistContext.value = null
    }

    fun removeMediaItem(index: Int) {
        mediaController?.removeMediaItem(index)
    }

    fun moveMediaItem(currentIndex: Int, newIndex: Int) {
        mediaController?.moveMediaItem(currentIndex, newIndex)
    }
    
    fun clearPlaylist() {
        mediaController?.clearMediaItems()
        _playQueue.value = emptyList()
        clearQueuePlaybackOverrides()
    }

    private fun consumePlayQueue(landedMediaId: String?) {
        val head = _playQueue.value.firstOrNull() ?: return
        when (resolveQueueHeadHit(landedMediaId, head)) {
            QueueHeadHit.QUEUED_COPY -> _playQueue.update { it.drop(1) }
            QueueHeadHit.OTHER_COPY -> {
                _playQueue.update { it.drop(1) }
                // The song was heard through another copy (e.g. the original base-playlist
                // item after a manual skip backwards); remove the queued copy so it does
                // not resurface later as an unexpected replay
                mediaController?.let { controller ->
                    val queuedIndex = queueItemIndex(controller, head.mediaId)
                    if (queuedIndex != null && queuedIndex != controller.currentMediaItemIndex) {
                        controller.removeMediaItem(queuedIndex)
                    }
                }
            }
            QueueHeadHit.NONE -> return
        }
        if (_playQueue.value.isEmpty()) {
            restorePlaybackModeAfterQueue()
        }
    }

    private fun applyQueuePlaybackOverridesIfNeeded(controller: MediaController) {
        if (_playQueue.value.isNotEmpty()) return
        queuedPlaybackRestoreRepeatMode = controller.repeatMode
        queuedPlaybackRestoreShuffleModeEnabled = controller.shuffleModeEnabled
        if (controller.repeatMode == Player.REPEAT_MODE_ONE) {
            controller.repeatMode = Player.REPEAT_MODE_ALL
        }
        if (controller.shuffleModeEnabled) {
            controller.shuffleModeEnabled = false
        }
    }

    private fun restorePlaybackModeAfterQueue() {
        mediaController?.let { controller ->
            queuedPlaybackRestoreRepeatMode?.let { controller.repeatMode = it }
            queuedPlaybackRestoreShuffleModeEnabled?.let { controller.shuffleModeEnabled = it }
        }
        clearQueuePlaybackOverrides()
    }

    private fun clearQueuePlaybackOverrides() {
        queuedPlaybackRestoreRepeatMode = null
        queuedPlaybackRestoreShuffleModeEnabled = null
    }

    private fun maybeSeekToQueuedSong(landedMediaId: String?, reason: Int): Boolean {
        if (isSeekingToQueuedSong) {
            isSeekingToQueuedSong = false
            return false
        }
        val head = _playQueue.value.firstOrNull() ?: return false
        if (resolveQueueHeadHit(landedMediaId, head) != QueueHeadHit.NONE) return false
        if (!shouldForceQueueTransition(reason, landedMediaId?.let { mediaIdToSongId(it) }, head.songId)) return false
        val controller = mediaController ?: return false
        val targetIndex = queueItemIndex(controller, head.mediaId) ?: return false
        if (targetIndex == controller.currentMediaItemIndex) return false
        isSeekingToQueuedSong = true
        controller.seekToDefaultPosition(targetIndex)
        if (!controller.isPlaying) {
            controller.play()
        }
        return true
    }

    private fun queueItemIndex(controller: MediaController, mediaId: String): Int? =
        (0 until controller.mediaItemCount)
            .firstOrNull { controller.getMediaItemAt(it).mediaId == mediaId }

    private fun pruneQueueMissingItems() {
        val controller = mediaController ?: return
        val before = _playQueue.value
        if (before.isEmpty()) return
        val existingMediaIds = (0 until controller.mediaItemCount)
            .mapTo(HashSet()) { controller.getMediaItemAt(it).mediaId }
        val after = before.filter { it.mediaId in existingMediaIds }
        if (after.size == before.size) return
        _playQueue.value = after
        if (after.isEmpty()) {
            restorePlaybackModeAfterQueue()
        }
    }

    fun toggleCurrentSongFavorite() {
        val filePath = _uiState.value.currentSongFilePath ?: return
        val startPosition = _uiState.value.currentSongStartPosition
        viewModelScope.launch {
            val newFavoriteState = favoriteRepository.toggleFavorite(filePath, startPosition)
            _uiState.update { it.copy(isCurrentSongFavorite = newFavoriteState) }
        }
    }

    fun startSleepTimer(minutes: Int) {
        sleepTimer?.cancel()
        sleepTimer = object : CountDownTimer(minutes * 60 * 1000L, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                _sleepTimerState.value = millisUntilFinished
            }
            override fun onFinish() {
                pause()
                _sleepTimerState.value = null
            }
        }.start()
    }

    fun cancelSleepTimer() {
        sleepTimer?.cancel()
        _sleepTimerState.value = null
    }

    override fun onCleared() {
        super.onCleared()
        mediaControllerFuture?.let {
            MediaController.releaseFuture(it)
        }
        sleepTimer?.cancel()
    }
}

/**
 * [MusicViewModel.playKeepPosition] 是否可以直接沿用当前时间轴(不再 setMediaItem)。
 *
 * 同曲判定基于 mediaId 本身:普通 id 与队列副本 id("queue:<seq>:<songId>")都能解析出
 * songId —— 队列副本同样算「同一首」,**不能**再用「当前 item 是否还是队列头」来收紧
 * (队列头在 transition 时就会被 consume,正在播的副本正常就不是 head 了)。
 * [sameQueueHead] 只作为未知 mediaId 格式的兜底身份信号。
 */
internal fun shouldKeepCurrentItem(
    currentMediaId: String?,
    songId: Long,
    sameQueueHead: Boolean,
): Boolean {
    if (currentMediaId == null) return false
    val currentSongId = mediaIdToSongId(currentMediaId)
    if (currentSongId != null) return currentSongId == songId
    return sameQueueHead
}

internal fun shouldForceQueueTransition(reason: Int, currentSongId: Long?, queuedSongId: Long): Boolean {
    if (currentSongId == null) return false
    if (currentSongId == queuedSongId) return false
    return reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
        reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
}

internal data class QueuedItem(val mediaId: String, val songId: Long)

internal enum class QueueHeadHit { QUEUED_COPY, OTHER_COPY, NONE }

/**
 * Which queue-head entry a transition landed on. Distinguishing the queued copy from
 * other copies of the same song (plain mediaId) is what keeps queue bookkeeping correct
 * when the currently playing song is queued again.
 */
internal fun resolveQueueHeadHit(landedMediaId: String?, head: QueuedItem?): QueueHeadHit {
    if (landedMediaId == null || head == null) return QueueHeadHit.NONE
    if (landedMediaId == head.mediaId) return QueueHeadHit.QUEUED_COPY
    if (mediaIdToSongId(landedMediaId) == head.songId) return QueueHeadHit.OTHER_COPY
    return QueueHeadHit.NONE
}

data class MusicUiState(
    val isPlaying: Boolean = false,
    val currentMediaItem: MediaItem? = null,
    val playbackState: Int = Player.STATE_IDLE,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val shuffleModeEnabled: Boolean = false,
    val currentPosition: Long = 0L,
    val duration: Long = 0L,
    val bufferedPosition: Long = 0L,
    val playlist: List<MediaItem> = emptyList(),
    val audioSessionId: Int = 0,
    val currentSongFilePath: String? = null,
    val currentSongStartPosition: Long = 0L,
    val currentPlayingSongId: String? = null,
    val isCurrentSongFavorite: Boolean = false,
    val artworkBytes: ByteArray? = null,
    val playlistContext: PlaylistContext? = null
) {
    // Override equals and hashCode to handle ByteArray comparison properly
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MusicUiState

        if (isPlaying != other.isPlaying) return false
        if (currentMediaItem != other.currentMediaItem) return false
        if (playbackState != other.playbackState) return false
        if (repeatMode != other.repeatMode) return false
        if (shuffleModeEnabled != other.shuffleModeEnabled) return false
        if (currentPosition != other.currentPosition) return false
        if (duration != other.duration) return false
        if (bufferedPosition != other.bufferedPosition) return false
        if (playlist != other.playlist) return false
        if (audioSessionId != other.audioSessionId) return false
        if (currentSongFilePath != other.currentSongFilePath) return false
        if (currentSongStartPosition != other.currentSongStartPosition) return false
        if (currentPlayingSongId != other.currentPlayingSongId) return false
        if (isCurrentSongFavorite != other.isCurrentSongFavorite) return false
        if (playlistContext != other.playlistContext) return false
        if (artworkBytes != null) {
            if (other.artworkBytes == null) return false
            if (!artworkBytes.contentEquals(other.artworkBytes)) return false
        } else if (other.artworkBytes != null) return false

        return true
    }

    override fun hashCode(): Int {
        var result = isPlaying.hashCode()
        result = 31 * result + (currentMediaItem?.hashCode() ?: 0)
        result = 31 * result + playbackState
        result = 31 * result + repeatMode
        result = 31 * result + shuffleModeEnabled.hashCode()
        result = 31 * result + currentPosition.hashCode()
        result = 31 * result + duration.hashCode()
        result = 31 * result + bufferedPosition.hashCode()
        result = 31 * result + playlist.hashCode()
        result = 31 * result + audioSessionId
        result = 31 * result + (currentSongFilePath?.hashCode() ?: 0)
        result = 31 * result + currentSongStartPosition.hashCode()
        result = 31 * result + (currentPlayingSongId?.hashCode() ?: 0)
        result = 31 * result + isCurrentSongFavorite.hashCode()
        result = 31 * result + (playlistContext?.hashCode() ?: 0)
        result = 31 * result + (artworkBytes?.contentHashCode() ?: 0)
        return result
    }
}
