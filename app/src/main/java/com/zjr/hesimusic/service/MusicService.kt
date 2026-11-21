package com.zjr.hesimusic.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.zjr.hesimusic.R
import com.zjr.hesimusic.data.mapper.mediaIdToSongId
import com.zjr.hesimusic.data.mapper.toMediaItem
import com.zjr.hesimusic.data.preferences.PlaybackPreferences
import com.zjr.hesimusic.data.repository.SongRepository
import com.zjr.hesimusic.loop.LoopIntent
import com.zjr.hesimusic.loop.LoopRuntime
import com.zjr.hesimusic.loop.audio.LoopAudioController
import com.zjr.hesimusic.loop.audio.source.LoopSourceItemTag
import com.zjr.hesimusic.loop.audio.source.LoopSourcePauseGate
import com.zjr.hesimusic.utils.AppLogger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MusicService : MediaSessionService() {

    companion object {
        private const val TAG = "MusicService"
        const val KEY_AUDIO_SESSION_ID = "AUDIO_SESSION_ID"
        private const val SILENT_CHANNEL_ID = "silent_media_service"
        private const val SILENT_NOTIFICATION_ID = 2001
        private const val PLAYBACK_ERROR_TOAST_INTERVAL_MS = 3000L

        /** #112 共享闸门守卫的检查间隔（只做布尔读，成本可忽略）。 */
        private const val GATE_GUARD_INTERVAL_MS = 120L

        /** D10：claim 连续变化时通知只刷一次（进入循环的 settle 会连发多次）。 */
        private const val LOOP_NOTIFICATION_DEBOUNCE_MS = 200L
    }

    @Inject
    lateinit var player: ExoPlayer

    @Inject
    lateinit var playbackPreferences: PlaybackPreferences

    @Inject
    lateinit var songRepository: SongRepository

    @Inject
    lateinit var appLogger: AppLogger

    @Inject
    lateinit var loopPlaybackCoordinator: LoopPlaybackCoordinator

    @Inject
    lateinit var loopRuntime: LoopRuntime

    @Inject
    lateinit var loopAudioController: LoopAudioController

    @Inject
    lateinit var loopIntent: LoopIntent

    private val audioManager: AudioManager by lazy { getSystemService(AudioManager::class.java) }

    /** 对外（MediaSession/UI/通知）的位置与时长口径层；见 LoopSessionPlayer。 */
    private lateinit var sessionPlayer: LoopSessionPlayer

    private var mediaSession: MediaSession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    // Flag to prevent saving state while restoring it
    private var isRestoringState = false

    /**
     * restore 期间是否有**外部播放请求**（用户播放 / 调试口 /loop/enter 的 play）。
     *
     * restore 最后会以『不要自动播放』为由 pause 一次；如果用户/接口在 restore 还没结束时
     * 已经要求播放，那次 pause 会把请求吞掉（真机 /loop/enter 之后 idle 的成因之一）。
     */
    private var externalPlayRequestedDuringRestore = false
    private var lastAudioSessionId: Int? = null
    private var showMediaNotificationEnabled: Boolean = true

    override fun onCreate() {
        val startTime = System.currentTimeMillis()
        super.onCreate()
        Log.d(TAG, "onCreate: MusicService created")
        appLogger.info(TAG, "MusicService onCreate started")

        createSilentNotificationChannel()
        showMediaNotificationEnabled = playbackPreferences.getShowMediaNotification()
        serviceScope.launch {
            playbackPreferences.showMediaNotificationFlow.collect { value ->
                showMediaNotificationEnabled = value
            }
        }
        
        // 通知/锁屏/车机与界面共用同一套命令与元数据：next/prev 的首尾绕回与
        // “>3s 回本曲开头”语义由本层对齐（见 MainSessionForwardingPlayer）。
        val forwardingPlayer = MainSessionForwardingPlayer(player) { loopMediaStatus() }

        // 位置/时长投影层：只重写位置/时长/seek，其余全部透传给上面的 forwardingPlayer
        // （队列首尾绕回播放仍由 forwardingPlayer 的 seekToNext/Previous 负责）。
        sessionPlayer = LoopSessionPlayer(
            player = forwardingPlayer,
            runtime = loopRuntime,
            audio = loopAudioController,
            loopIntent = loopIntent,
            // #106：循环中切歌前先彻底退出旧循环（唯一写点在协调器）。
            onExitLoopForNavigation = { loopPlaybackCoordinator.exitForNavigation(forwardingPlayer) },
            // #112：主播放暂停时判断"预览是否还在出声"，避免把共享闸门关死。
            otherSourceAudible = { audioManager?.isMusicActive == true },
        )
        // 00027：安装唯一的 claim provider。UI / MediaSession / debug / About 都通过
        // LoopClaimStore 读同一份真值（窗口 = 意图，sourceActive = 源真的在环回）。
        LoopClaimStore.install { songId ->
            LoopClaimResolver.fromProcess(songId, loopRuntime, loopAudioController)
        }

        mediaSession = MediaSession.Builder(this, sessionPlayer)
            .setCallback(MusicSessionCallback())
            .build()
        updateSessionAudioSessionId()

        // D10：claim 变化（进入 / 回退 / 退出）时刷新 session extras（循环区间/phase/原因）
        // 并重新发一次通知。回退/退出不一定伴随播放器事件，不主动刷会让通知停留在旧文案。
        serviceScope.launch {
            LoopClaimStore.revisionFlow.collect {
                runCatching { updateSessionExtras() }
                    .onFailure { Log.w(TAG, "loop extras update failed", it) }
                scheduleLoopNotificationRefresh()
            }
        }

        restorePlaybackState()
        setupPlayerListeners()
        startPeriodicSave()
        startSourceGateGuard()
        
        val duration = System.currentTimeMillis() - startTime
        appLogger.timing(TAG, "MusicService onCreate", duration)
    }

    private fun restorePlaybackState() {
        Log.d(TAG, "restorePlaybackState: starting state restoration")
        isRestoringState = true  // Prevent saving state during restoration
        
        serviceScope.launch {
            try {
                // Get saved state first
                val repeatMode = playbackPreferences.getRepeatMode()
                val savedShuffleMode = playbackPreferences.getShuffleModeEnabled()
                val savedSongId = playbackPreferences.getCurrentSongId()
                val savedIndex = playbackPreferences.getCurrentSongIndex()
                val savedPosition = playbackPreferences.getSavedPosition()
                val playlistContext = playbackPreferences.getPlaylistContext()
                
                Log.d(TAG, "restorePlaybackState: repeatMode=$repeatMode, shuffleMode=$savedShuffleMode")
                Log.d(TAG, "restorePlaybackState: songId=$savedSongId, index=$savedIndex, playlistContext=$playlistContext")

                val queueIds = playbackPreferences.getQueue()
                
                if (queueIds.isNotEmpty()) {
                    val songs = songRepository.getSongsByIds(queueIds)
                    Log.d(TAG, "restorePlaybackState: found ${songs.size} songs for ${queueIds.size} queue IDs")
                    
                    // Maintain order based on IDs
                    val sortedSongs = queueIds.mapNotNull { id -> songs.find { it.id == id } }
                    val mediaItems = sortedSongs.map { it.toMediaItem() }
                    
                    if (mediaItems.isNotEmpty()) {
                        // Set repeat mode but NOT shuffle mode yet (to avoid shuffling the queue)
                        player.repeatMode = repeatMode
                        player.shuffleModeEnabled = false
                        
                        // Set media items
                        player.setMediaItems(mediaItems)
                        player.prepare()
                        
                        // Find the correct index using song ID (more reliable than saved index when shuffle was enabled)
                        var targetIndex = savedIndex
                        if (savedSongId > 0) {
                            val foundIndex = mediaItems.indexOfFirst { it.mediaId == savedSongId.toString() }
                            if (foundIndex >= 0) {
                                targetIndex = foundIndex
                                Log.d(TAG, "restorePlaybackState: found song by ID at index $foundIndex")
                            } else {
                                Log.w(TAG, "restorePlaybackState: song ID $savedSongId not found, using saved index $savedIndex")
                            }
                        }
                        
                        // Validate and clamp targetIndex to valid range
                        if (targetIndex !in mediaItems.indices) {
                            Log.w(TAG, "restorePlaybackState: index $targetIndex out of range, mediaItems size=${mediaItems.size}, using index 0")
                            targetIndex = 0
                        }
                        
                        Log.d(TAG, "restorePlaybackState: seeking to index=$targetIndex, position=$savedPosition")
                        player.seekTo(targetIndex, savedPosition)
                        
                        // Do not auto-play —— 但若 restore 期间已有外部播放请求（用户/调试口），
                        // 这里的 pause 会把那次 play 吞掉，跳过它。
                        if (externalPlayRequestedDuringRestore) {
                            Log.i(TAG, "restorePlaybackState: keep playing (external play request)")
                        } else {
                            player.pause()
                        }
                        
                        // Now enable shuffle mode if it was enabled (after seeking to the correct song)
                        if (savedShuffleMode) {
                            player.shuffleModeEnabled = true
                            Log.d(TAG, "restorePlaybackState: enabled shuffle mode after restoration")
                        }
                        
                        Log.d(TAG, "restorePlaybackState: state restored successfully")
                    } else {
                        Log.w(TAG, "restorePlaybackState: no media items to restore")
                    }
                } else {
                    Log.d(TAG, "restorePlaybackState: no saved queue to restore")
                }
            } finally {
                if (externalPlayRequestedDuringRestore && !player.playWhenReady) {
                    // 外部请求的 play 可能被 restore 自身的状态写入抹掉：最后再补一次。
                    Log.i(TAG, "restorePlaybackState: re-apply external play request")
                    player.play()
                }
                externalPlayRequestedDuringRestore = false
                isRestoringState = false  // Allow saving state again
            }

            // restore 完成后重算一次（契约 §2.4）：窗口命中则应用形态，并从展示位置起播
            loopPlaybackCoordinator.refresh(
                songIdOf(player.currentMediaItem),
                player.repeatMode,
                player,
                "restore",
            )
        }
    }

    private fun setupPlayerListeners() {
        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                Log.d(TAG, "onAudioSessionIdChanged: audioSessionId=$audioSessionId")
                updateSessionAudioSessionId(audioSessionId)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                Log.d(TAG, "onMediaItemTransition: mediaId=${mediaItem?.mediaId}, reason=$reason")
                saveCurrentState()
                // 事件入口之一：只触发一次窗口重算（不在这里做任何 loop 专属判断）
                serviceScope.launch {
                    loopPlaybackCoordinator.refresh(
                        songIdOf(mediaItem),
                        player.repeatMode,
                        player,
                        "transition",
                    )
                }
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                Log.d(TAG, "onRepeatModeChanged: repeatMode=$repeatMode")
                // repeat 只代表用户意图：引擎从不回写它，这里只重算一次窗口
                serviceScope.launch {
                    loopPlaybackCoordinator.refresh(
                        songIdOf(player.currentMediaItem),
                        player.repeatMode,
                        player,
                        "repeat",
                    )
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                // A-077: ExoPlayer 创建时不会派发初始 IDLE;IDLE 只在 stop()/错误重置后回调。
                // 初始化不能等首个回调,见 setupPlayerListeners 末尾的主动读。
                Log.d(TAG, "onPlaybackStateChanged: state=$playbackState")
                saveCurrentState()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (playWhenReady && isRestoringState) {
                    externalPlayRequestedDuringRestore = true
                    Log.i(TAG, "restore in flight: external play requested reason=" + reason)
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                Log.d(TAG, "onIsPlayingChanged: isPlaying=$isPlaying")
                saveCurrentState()
            }
            
            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                Log.d(TAG, "onTimelineChanged: windowCount=${timeline.windowCount}, reason=$reason")
                if (!isRestoringState) {
                    saveQueueState()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "onPlayerError: code=${error.errorCode}", error)
                showPlaybackErrorToast(error)
            }
        })

        // A-077: 初始 IDLE 不会以回调形式到达;需要初始态时主动读 player.playbackState。
        // 不要在后面任何初始化逻辑里等 onPlaybackStateChanged(IDLE)。
        Log.d(
            TAG,
            "initial player state=" + player.playbackState +
                " pwr=" + player.playWhenReady +
                " playing=" + player.isPlaying +
                " mediaItems=" + player.mediaItemCount +
                " pos=" + player.currentPosition,
        )
    }

    private var lastPlaybackErrorToastTime = 0L
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 播放失败时弹 Toast 告知用户（否则点击播放无反应，用户无从得知原因）。
     * 连续报错（如整个队列都不可读）按时间间隔节流。
     */
    private fun showPlaybackErrorToast(error: PlaybackException) {
        mainHandler.post {
            val now = System.currentTimeMillis()
            if (now - lastPlaybackErrorToastTime < PLAYBACK_ERROR_TOAST_INTERVAL_MS) return@post
            lastPlaybackErrorToastTime = now

            val title = player.currentMediaItem?.mediaMetadata?.title?.toString()
            val message = when {
                isStorageAccessError(error) ->
                    "无法播放${title.orEmpty().ifEmpty { "歌曲" }}：无法读取音频文件，请在系统设置中授予「所有文件访问」权限"
                else -> "播放失败（${error.errorCode}）${title?.let { ": $it" }.orEmpty()}"
            }
            Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun isStorageAccessError(error: PlaybackException): Boolean {
        if (error.errorCode == PlaybackException.ERROR_CODE_IO_NO_PERMISSION ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
        ) {
            return true
        }
        // EACCES 等底层错误可能被包在多层 cause 里
        var cause: Throwable? = error.cause
        while (cause != null) {
            if (cause.message?.contains("EACCES") == true) return true
            cause = cause.cause
        }
        return false
    }

    private fun updateSessionAudioSessionId(audioSessionId: Int = player.audioSessionId) {
        if (mediaSession == null) {
            Log.w(TAG, "updateSessionAudioSessionId: mediaSession is null")
            return
        }
        if (lastAudioSessionId == audioSessionId) return
        lastAudioSessionId = audioSessionId
        updateSessionExtras()
    }

    /** 通知刷新去抖：进入循环时 refresh 会连发多次 claim revision。 */
    @Volatile
    private var loopNotificationGeneration = 0L

    private fun scheduleLoopNotificationRefresh() {
        val generation = ++loopNotificationGeneration
        serviceScope.launch {
            delay(LOOP_NOTIFICATION_DEBOUNCE_MS)
            if (generation != loopNotificationGeneration) return@launch
            val session = mediaSession ?: return@launch
            runCatching { onUpdateNotification(session, false) }
                .onFailure { Log.w(TAG, "loop notification refresh failed", it) }
        }
    }

    /**
     * session extras：车机/手表/其它 controller 也能看到“是否在循环 + A/B + phase + 原因”。
     * 通知正文与锁屏元数据由 [MainSessionForwardingPlayer.getMediaMetadata] 负责。
     */
    private fun updateSessionExtras() {
        val session = mediaSession ?: return
        val status = loopMediaStatus()
        val extras = Bundle(session.sessionExtras ?: Bundle()).apply {
            putInt(KEY_AUDIO_SESSION_ID, player.audioSessionId)
            LoopSessionExtras.applyTo(this, status, loopPhaseWire(status))
        }
        session.setSessionExtras(extras)
    }

    /** 当前 claim 对应的通知/MediaSession 状态；普通播放（无意图/无窗口）返回 null。 */
    private fun loopMediaStatus(): LoopMediaStatus? {
        val songId = songIdOf(player.currentMediaItem) ?: return null
        return LoopMediaMetadata.statusFor(LoopClaimStore.claimFor(songId))
    }

    /** 当前循环 phase（prelude/loop/tail）；只在 claim 生效时有意义。 */
    private fun loopPhaseWire(status: LoopMediaStatus?): String? {
        if (status?.active != true) return null
        val songId = songIdOf(player.currentMediaItem) ?: return null
        if (!::sessionPlayer.isInitialized) return null
        val claim = LoopClaimStore.claimFor(songId)
        return claim.phaseForDisplayUs(
            sessionPlayer.currentPosition * LoopSessionProjection.US_PER_MS,
        )?.wire
    }

    /**
     * #112 主播放侧的共享闸门守卫。
     *
     * 预览与主播放共用 [LoopSourcePauseGate]（进程级布尔）：预览 pause() 会把它关掉，
     * 若此时主播放仍在出声，它会一直"显示在播但不再写帧"（永久哑）。这里周期性地把
     * 闸门重新顶开 —— 只在主播放确实应该出声（SOURCE item + playWhenReady + 非 IDLE）时。
     *
     * 反方向（主播放 pause 把预览静音）由 [LoopSessionPlayer.pause] 的延迟复核负责：
     * 只有确认没有其它音频活动时才关门。
     */
    private fun startSourceGateGuard() {
        serviceScope.launch {
            while (isActive) {
                val sourceItem = player.currentMediaItem?.localConfiguration?.tag is LoopSourceItemTag
                val wantsSound = SourceAudioArbiter.mainWantsToSound(
                    playWhenReady = player.playWhenReady,
                    playbackState = player.playbackState,
                    isSourceItem = sourceItem,
                )
                if (SourceAudioArbiter.shouldResumeGate(wantsSound, LoopSourcePauseGate.paused)) {
                    Log.i(TAG, "source gate guard: resume (a preview pause must not mute the main player)")
                    LoopSourcePauseGate.resume()
                }
                delay(GATE_GUARD_INTERVAL_MS)
            }
        }
    }

    private fun startPeriodicSave() {
        serviceScope.launch {
            while (isActive) {
                if (player.isPlaying && !isRestoringState) {
                    saveCurrentState()
                    // 循环中 phase 会随播放自然变化（prelude→loop→tail）；不刷新的话
                    // session extras 里的 LOOP_PHASE 会停在进入时的值（车机/手表可读）。
                    if (loopMediaStatus()?.active == true) {
                        runCatching { updateSessionExtras() }
                            .onFailure { Log.w(TAG, "periodic loop extras update failed", it) }
                    }
                }
                delay(5000) // Save every 5 seconds
            }
        }
    }

    private fun saveCurrentState() {
        if (isRestoringState) {
            Log.d(TAG, "saveCurrentState: skipped (restoring state)")
            return
        }
        
        val index = player.currentMediaItemIndex
        // 展示口径持久化：重启恢复后位置仍落在 [A,B) 内（契约 §5）
        val position = sessionPlayer.currentPosition
        val mediaItem = player.currentMediaItem
        val songId = mediaItem?.mediaId?.let { mediaIdToSongId(it) } ?: -1L
        
        playbackPreferences.saveCurrentSongIndex(index)
        playbackPreferences.saveCurrentSongId(songId)
        playbackPreferences.saveCurrentPosition(position)
    }
    
    private fun saveQueueState() {
        if (isRestoringState) {
            Log.d(TAG, "saveQueueState: skipped (restoring state)")
            return
        }
        
        val mediaItems = List(player.mediaItemCount) { i -> player.getMediaItemAt(i) }
        // mapNotNull via mediaIdToSongId keeps queued copies persisted as their song id
        val ids = mediaItems.mapNotNull { mediaIdToSongId(it.mediaId) }
        playbackPreferences.saveQueue(ids)
    }

    /** mediaId -> songId（普通 id 与 queue:seq:id 两种形态都支持）。 */
    private fun songIdOf(mediaItem: MediaItem?): Long? =
        mediaItem?.mediaId?.let { mediaIdToSongId(it) }

    private fun createSilentNotificationChannel() {
        val channel = NotificationChannel(
            SILENT_CHANNEL_ID,
            "后台播放服务",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            setShowBadge(false)
        }
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    override fun onUpdateNotification(session: MediaSession, startInForeground: Boolean) {
        if (showMediaNotificationEnabled) {
            super.onUpdateNotification(session, startInForeground)
        } else {
            val notification = NotificationCompat.Builder(this, SILENT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(getString(R.string.app_name))
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setOngoing(true)
                .build()
            startForeground(SILENT_NOTIFICATION_ID, notification)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy: MusicService destroyed, saving final state")
        saveCurrentState() // Save one last time
        saveQueueState()   // Also save queue state
        sessionPlayer.dispose()
        loopPlaybackCoordinator.release()
        LoopClaimStore.uninstall()
        serviceScope.cancel()
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }

    // Callback to handle custom actions if needed
    private inner class MusicSessionCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            Log.d(TAG, "MusicSessionCallback.onConnect: controller=${controller.packageName}")
            val sessionExtras = Bundle()
            // Use try-catch to prevent crashes if player is not ready or other issues
            try {
                sessionExtras.putInt(KEY_AUDIO_SESSION_ID, player.audioSessionId)
                val status = loopMediaStatus()
                LoopSessionExtras.applyTo(sessionExtras, status, loopPhaseWire(status))
            } catch (e: Exception) {
                Log.e(TAG, "MusicSessionCallback.onConnect: error getting audio session ID", e)
                appLogger.error(TAG, "Error getting audio session ID in onConnect", e)
            }
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setSessionExtras(sessionExtras)
                .build()
        }
    }
}

// ---------------------------------------------------------------------------
// D10：通知 / MediaSession / 锁屏的循环标识（纯逻辑，可 JVM 单测）
// ---------------------------------------------------------------------------

/**
 * 一次“是否在循环 / 回退原因”的媒体会话快照。
 *
 * [active] = claim 真的生效；否则 [reasonCode] 是 /state.loop.claimReason 的同一口径。
 */
internal data class LoopMediaStatus(
    val active: Boolean,
    val startMs: Long?,
    val endMs: Long?,
    val reasonCode: String?,
)

/**
 * 锁屏 / 通知正文 / 车机的循环标识。
 *
 * 为什么改 metadata：media3 1.8.0 的 DefaultMediaNotificationProvider 正文就是
 * MediaMetadata.artist，而 MediaSession 的 PlayerInfo.mediaMetadata 也来自
 * player.getMediaMetadata()。因此在这里加后缀可一次覆盖通知正文和锁屏媒体卡。
 *
 * 文案克制（只在有循环意图时出现）：
 * - 生效：      "<artist> · 无缝循环"（区间/相位放 LoopSessionExtras，避免暂停时过期）
 * - 回退：      "<artist> · 未进入无缝循环（ring-too-large）"
 * - 普通播放：  原样返回同一实例，不产生额外 metadata 事件。
 *
 * 原始 artist 写进 [EXTRA_RAW_ARTIST]，App 内 UI 仍读 currentMediaItem 的原始 metadata。
 */
internal object LoopMediaMetadata {

    const val LOOP_MARKER = "无缝循环"

    const val EXTRA_RAW_ARTIST = "com.zjr.hesimusic.extra.RAW_ARTIST"
    const val EXTRA_LOOP_ACTIVE = "com.zjr.hesimusic.extra.LOOP_ACTIVE"
    const val EXTRA_LOOP_START_MS = "com.zjr.hesimusic.extra.LOOP_START_MS"
    const val EXTRA_LOOP_END_MS = "com.zjr.hesimusic.extra.LOOP_END_MS"
    const val EXTRA_LOOP_REASON = "com.zjr.hesimusic.extra.LOOP_REASON"

    /** claim -> 会话状态；普通播放（无窗口且无原因）返回 null。 */
    fun statusFor(claim: LoopClaim): LoopMediaStatus? {
        if (claim.claimActive) {
            val window = claim.effectiveWindow ?: return null
            return LoopMediaStatus(
                active = true,
                startMs = window.loopStartUs / 1_000L,
                endMs = window.loopEndUs / 1_000L,
                reasonCode = null,
            )
        }
        val reason = claim.fallbackReason ?: return null
        val window = claim.window
        return LoopMediaStatus(
            active = false,
            startMs = window?.loopStartUs?.div(1_000L),
            endMs = window?.loopEndUs?.div(1_000L),
            reasonCode = reason.logName,
        )
    }

    /** 纯函数：装饰后的 artist；不需要装饰返回 null（调用方原样返回同一 metadata 实例）。 */
    fun decoratedArtist(rawArtist: CharSequence?, status: LoopMediaStatus?): String? {
        if (status == null) return null
        val artist = rawArtist?.toString()?.trim().orEmpty()
        // 幂等：已经是装饰过的 metadata 就不再叠后缀。
        if (artist.contains(LOOP_MARKER)) return null
        val suffix = if (status.active) {
            LOOP_MARKER
        } else {
            "未进入无缝循环（" + status.reasonCode + "）"
        }
        return if (artist.isEmpty()) suffix else artist + " · " + suffix
    }

    /** 纯函数：要写进 MediaMetadata.extras 的键值（原始 artist 必须仍可取到）。 */
    fun extrasFor(status: LoopMediaStatus?, rawArtist: CharSequence?): Map<String, Any> {
        if (status == null) return emptyMap()
        return buildMap {
            if (rawArtist != null) put(EXTRA_RAW_ARTIST, rawArtist.toString())
            put(EXTRA_LOOP_ACTIVE, status.active)
            status.startMs?.let { put(EXTRA_LOOP_START_MS, it) }
            status.endMs?.let { put(EXTRA_LOOP_END_MS, it) }
            status.reasonCode?.let { put(EXTRA_LOOP_REASON, it) }
        }
    }

    fun decorate(base: MediaMetadata, status: LoopMediaStatus?): MediaMetadata {
        val newArtist = decoratedArtist(base.artist, status) ?: return base
        val extras = Bundle(base.extras ?: Bundle.EMPTY).apply {
            for ((key, value) in extrasFor(status, base.artist)) {
                when (value) {
                    is Boolean -> putBoolean(key, value)
                    is Long -> putLong(key, value)
                    is String -> putString(key, value)
                    else -> Unit
                }
            }
        }
        return base.buildUpon().setArtist(newArtist).setExtras(extras).build()
    }
}

/** session extras：车机/手表/其它 controller 可读的循环区间与 phase。 */
internal object LoopSessionExtras {

    const val KEY_LOOP_ACTIVE = "LOOP_ACTIVE"
    const val KEY_LOOP_START_MS = "LOOP_START_MS"
    const val KEY_LOOP_END_MS = "LOOP_END_MS"
    const val KEY_LOOP_PHASE = "LOOP_PHASE"
    const val KEY_LOOP_REASON = "LOOP_REASON"

    /** 纯函数：session extras 的键值；没有的键由 [applyTo] 负责 remove。 */
    fun extrasFor(status: LoopMediaStatus?, phase: String?): Map<String, Any> = buildMap {
        put(KEY_LOOP_ACTIVE, status?.active == true)
        if (status == null) return@buildMap
        status.startMs?.let { put(KEY_LOOP_START_MS, it) }
        status.endMs?.let { put(KEY_LOOP_END_MS, it) }
        if (status.active && phase != null) put(KEY_LOOP_PHASE, phase)
        status.reasonCode?.let { put(KEY_LOOP_REASON, it) }
    }

    fun applyTo(bundle: Bundle, status: LoopMediaStatus?, phase: String?) {
        for ((key, value) in extrasFor(status, phase)) {
            when (value) {
                is Boolean -> bundle.putBoolean(key, value)
                is Long -> bundle.putLong(key, value)
                is String -> bundle.putString(key, value)
                else -> Unit
            }
        }
        val present = extrasFor(status, phase).keys
        for (key in listOf(KEY_LOOP_START_MS, KEY_LOOP_END_MS, KEY_LOOP_PHASE, KEY_LOOP_REASON)) {
            if (key !in present) bundle.remove(key)
        }
    }
}

/**
 * MediaSession 底下的播放器（包一层，保持 ExoPlayer 为状态机权威）。
 *
 * - next/prev：通知的 MediaItem 版本委托给界面同款 seekToNext/seekToPrevious，
 *   保证队尾绕回与“>3s 回本曲开头”在通知/锁屏/界面一致；
 * - metadata：只加“无缝循环 / 未进入无缝循环（原因）”后缀，不改 title/artwork。
 */
internal class MainSessionForwardingPlayer(
    player: Player,
    private val loopStatus: () -> LoopMediaStatus?,
) : ForwardingPlayer(player) {

    override fun getAvailableCommands(): Player.Commands =
        super.getAvailableCommands().buildUpon()
            .add(Player.COMMAND_SEEK_TO_NEXT)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS)
            .build()

    override fun isCommandAvailable(command: Int): Boolean =
        if (command == Player.COMMAND_SEEK_TO_NEXT || command == Player.COMMAND_SEEK_TO_PREVIOUS) {
            mediaItemCount > 0
        } else {
            super.isCommandAvailable(command)
        }

    override fun getMediaMetadata(): MediaMetadata =
        LoopMediaMetadata.decorate(super.getMediaMetadata(), loopStatus())

    override fun seekToNext() {
        val hasNext = hasNextMediaItem()
        if (hasNext) {
            super.seekToNext()
        } else if (MusicNavigationPolicy.shouldWrapToStart(
                hasNext = hasNext,
                repeatMode = repeatMode,
                mediaItemCount = mediaItemCount,
            )
        ) {
            // A-085: repeat=ALL 时 hasNextMediaItem() 恒 true(播放器自己绕回),
            // 手写绕回只对 repeat != ALL 的最后一项生效。
            seekToDefaultPosition(0)
            // #90：暂停态 next 不能自动开播；只有自然播完(ENDED)才需要续播。
            if (MusicNavigationPolicy.shouldPlayAfterWrap(playbackState)) {
                play()
            }
        }
    }

    override fun seekToPrevious() {
        val hasPrevious = hasPreviousMediaItem()
        if (hasPrevious) {
            super.seekToPrevious()
        } else if (MusicNavigationPolicy.shouldWrapToEnd(
                hasPrevious = hasPrevious,
                repeatMode = repeatMode,
                mediaItemCount = mediaItemCount,
            )
        ) {
            // 同 A-085: repeat=ALL 时上一项由播放器自己绕回。
            seekToDefaultPosition(mediaItemCount - 1)
            if (MusicNavigationPolicy.shouldPlayAfterWrap(playbackState)) {
                play()
            }
        }
    }

    // MediaSession 的 COMMAND_SEEK_TO_NEXT/PREVIOUS 走 MediaItem 版本（真机 1271 已证）。
    // 与界面按钮（seekToNext/seekToPrevious）对齐，避免通知里 next 绕回、界面里不绕回。
    override fun seekToNextMediaItem() {
        seekToNext()
    }

    override fun seekToPreviousMediaItem() {
        seekToPrevious()
    }
}
