package com.zjr.hesimusic.ui.loop

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime
import com.zjr.hesimusic.data.mapper.toMediaItem
import com.zjr.hesimusic.data.model.Song
import com.zjr.hesimusic.loop.LoopPosition
import com.zjr.hesimusic.loop.LoopWindow
import com.zjr.hesimusic.loop.analysis.PcmDecoder
import com.zjr.hesimusic.loop.audio.LoopAudioController
import com.zjr.hesimusic.loop.audio.LoopPcmEngine
import com.zjr.hesimusic.loop.audio.LoopRenderersFactory
import com.zjr.hesimusic.loop.audio.source.LoopSourceMediaTag
import com.zjr.hesimusic.loop.audio.source.LoopSourcePauseGate
import com.zjr.hesimusic.loop.audio.source.LoopSourceMode
import com.zjr.hesimusic.loop.audio.source.LoopSourcePipeline
import com.zjr.hesimusic.loop.audio.source.LoopSourceProjection
import com.zjr.hesimusic.loop.audio.source.LoopPreviewDebugBridge
import com.zjr.hesimusic.loop.audio.source.LoopingPcmExtractorMediaSource
import com.zjr.hesimusic.loop.audio.source.LoopingPcmMediaSource
import com.zjr.hesimusic.loop.audio.source.LoopingPcmTimeline
import com.zjr.hesimusic.loop.audio.source.PcmDecoderRangeProvider
import com.zjr.hesimusic.loop.audio.source.PcmFileFormatProbe
import com.zjr.hesimusic.loop.audio.source.SourceRenderersFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/** 分析/预览页的四种试听模式(契约 §2.3 / v2 §0)。 */
enum class PreviewMode(val label: String) {
    /** 完整循环:从前奏 0 起播,到 B 后由 sink 环回。 */
    FULL("完整循环"),

    /** 仅循环段:从 A 起播,在 A↔B 内环回。 */
    LOOP_ONLY("仅循环段"),

    /** 接缝检查:从 B−5s 起播,重点听 B→A 的接缝。 */
    SEAM("接缝检查"),

    /** 自由:不设窗口,整曲直通播放。 */
    FREE("自由"),
}

/**
 * 预览播放器(契约 §3 / docs/loop-v3-plan.md §5.4)。
 *
 * **与正式播放同机制**:独立 ExoPlayer 实例 + 同一个 [LoopRenderersFactory]/[LoopAudioSink],
 * 只是注入一个**独立**的 [LoopAudioController] 实例(不与主播放共享窗口/ring)。
 * 因此"预览听到的接缝 == 正式播放的接缝",不存在两套播放实现漂移。
 *
 * 形态:
 * - 循环模式:FULL/LOOP_ONLY/SEAM 用带 `ClippingConfiguration(0, B)` 的 MediaItem ——
 *   源端在 B 处 EOS,音频 sink 在 B 精确切到 PCM 重放(越过 B 后的位置由 sink 侧
 *   `LoopAudioController.positionUs` 报告,ExoPlayer 对外的 currentPosition 会被 clip 钳住,
 *   这是 P0 实测结论);
 * - FREE:无剪裁、窗口置空,整曲直通。
 *
 * 线程:ExoPlayer 的创建/访问全部在主线程(VM 调用方与内部 ticker 都在主线程)。
 */
@UnstableApi
class PreviewPlayer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pcmDecoder: PcmDecoder,
) {

    companion object {
        private const val TAG = "LoopPreview"

        /** 展示位置刷新周期:插值在 UI 侧 60fps 做,状态源 10Hz 足够。 */
        private const val POSITION_TICK_MS = 100L

        /** 接缝检查起播提前量(契约 §2.3:B−5s)。 */
        const val SEAM_LEAD_MS = 5_000L

        /**
         * 循环模式起播预卷(ms):ExoPlayer 的 seek 会落到目标 ±十几毫秒,若正好从 A 起播,
         * ring 会缺 [A, 实际起点) 这一小段 → 引擎判定 ring 不完整而退化为直通。
         * 因此从 A 前预卷 300ms 起播,让环缓冲区从 A 之前开始捕获。
         */
        const val LOOP_PREROLL_MS = 300L

        /** 循环段最短时长(与 UI 拖拽下限一致)。 */
        const val MIN_LOOP_LENGTH_MS = 500L
    }

    init {
        // 打开结果页时读取真机调试开关（默认 M3′）。
        LoopSourceMode.refreshFromSettings(context)
        LoopSourcePipeline.refreshFromSettings(context)
    }

    /** 独立音频控制器:窗口/ring/位置都只属于预览,不污染主播放。 */
    private val controller = LoopAudioController()

    private enum class EngineKind { M3, SOURCE }

    private var engineKind: EngineKind = EngineKind.M3

    private var player: ExoPlayer? = null

    /** SOURCE 路径当前持有的 MediaSource / timeline / 窗口。 */
    private var source: MediaSource? = null
    private var sourceTimeline: LoopingPcmTimeline? = null
    private var sourceWindow: LoopWindow? = null
    private var sourceSongId: Long? = null

    /** play 真正创建播放器之前收到的展示口径 seek；首次 play 时使用。 */
    private var pendingSeekMs: Long? = null

    /** SOURCE 播放器是否正在运行（位置口径/UI 状态用）。 */
    @Volatile
    private var sourceActive: Boolean = false

    private val handler = Handler(Looper.getMainLooper())

    private var song: Song? = null

    /** 当前应用给 sink 的窗口;null = 直通(FREE 或非法窗口)。 */
    @Volatile
    private var window: LoopWindow? = null

    private var prepared: Boolean = false

    /** 本次起播是否已经因「ring 不完整」自动补环 seek 过一次(防反复跳)。 */
    private var degradeRetryUsed: Boolean = false

    private var mode: PreviewMode = PreviewMode.FULL

    private var aMs: Long = 0L
    private var bMs: Long = 0L

    private val _positionMs = MutableStateFlow(0L)

    /** 展示口径位置(已按当前窗口折返,落在 [A, B) 或前奏内)。 */
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _buffering = MutableStateFlow(false)

    /** ExoPlayer buffering(UI 显示"准备试听音轨…")。 */
    val buffering: StateFlow<Boolean> = _buffering.asStateFlow()

    private val _isReplaying = MutableStateFlow(false)

    /** sink 是否处于 PCM 重放态(接缝已生效)。 */
    val isReplaying: StateFlow<Boolean> = _isReplaying.asStateFlow()

    private val _ringReady = MutableStateFlow(false)

    /** 环缓冲区是否已完整覆盖 [A,B)(false 时接缝检查会退化为普通播放)。 */
    val ringReady: StateFlow<Boolean> = _ringReady.asStateFlow()

    private val _sourceFirstBlockReady = MutableStateFlow(false)
    private val _sourceFirstBlockEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    /** SOURCE 模式每次首块就绪的事件（StateFlow 会合并，这里用 SharedFlow 保证每次播放/seek 都能通知）。 */
    val sourceFirstBlockEvents: SharedFlow<Unit> = _sourceFirstBlockEvents

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** 窗口/歌曲已写入（播放器可延迟到 play 时按模式创建）。 */
    val isPrepared: Boolean
        get() = prepared && song != null

    /** 实际 ExoPlayer 是否已创建（M3′ 预填延迟判据）。 */
    val hasPlayer: Boolean
        get() = player != null

    val songId: Long?
        get() = song?.id

    // ------------------------------------------------------------------ API

    /**
     * 为某首歌建立预览(暂停态):写窗口、建 MediaItem(不裁剪,见 [buildMediaItem])、prepare。
     * 不自动播放(播放由 [play] 触发);重复调用会整体重建。
     */
    fun prepare(song: Song, aMs: Long, bMs: Long) {
        val duration = maxOf(song.duration, bMs, 0L)
        this.song = song
        this.mode = PreviewMode.FULL
        _durationMs.value = duration
        _error.value = null

        val (aa, bb) = sanitize(aMs, bMs, duration)
        this.aMs = aa
        this.bMs = bb

        // 播放器延迟到 play 时按模式创建（SOURCE 用默认 AudioSink，M3′ 用 LoopRenderersFactory）。
        val newWindow = buildWindow(song.id, aa, bb, allowLong = LoopSourceMode.isSource())
        window = newWindow
        controller.fullDurationUs = duration * 1000L
        controller.window = newWindow
        controller.tapWindow = newWindow
        prepared = true
        pendingSeekMs = null
        _positionMs.value = aa
        updatePositionOnce()
    }

    /** 按模式起播。已 prepare 时只做窗口切换 + seek + play(MediaItem 不裁剪、不重建)。 */
    fun play(mode: PreviewMode, startMs: Long) {
        LoopSourcePauseGate.resume()
        val song = this.song ?: return
        if (!prepared) {
            prepare(song, aMs, bMs)
        }
        pendingSeekMs = null
        this.mode = mode
        val useSource = shouldUseSource(mode)
        val p = ensurePlayer(if (useSource) EngineKind.SOURCE else EngineKind.M3)

        if (useSource) {
            _sourceFirstBlockReady.value = false
            val w = window
            if (w != null && prepareSource(song, w)) {
                val currentT = if (sourceActive && p.currentPosition > 0L) {
                    p.currentPosition * 1000L
                } else {
                    sourceStartMs(mode) * 1000L
                }
                val startT = LoopSourceProjection.timelineUsForDisplay(
                    displayUs = startMs.coerceAtLeast(0L) * 1000L,
                    currentTimelineUs = currentT,
                    window = w,
                ) / 1000L
                val wasEnded = p.playbackState == Player.STATE_ENDED
                p.seekTo(startT)
                if (wasEnded) {
                    Log.w(TAG, "play source from ENDED -> seek+prepare startT=" + startT + "Ms")
                    p.prepare()
                } else if (p.playbackState == Player.STATE_IDLE) {
                    p.prepare()
                }
                p.play()
                sourceActive = true
                markSourceReadyFromState(p)
                updatePositionOnce()
                Log.d(
                    TAG,
                    "play source mode=" + mode + " startT=" + startT + "Ms window=" + w +
                        " source=" + source,
                )
                return
            }
            // 探测/构建失败：释放 SOURCE 播放器，回退 M3′，不阻塞预览。
            Log.w(TAG, "source prepare failed; fallback to M3' mode=" + mode)
            releasePlayerOnly()
            _sourceFirstBlockEvents.tryEmit(Unit)
            ensurePlayer(EngineKind.M3)
        }

        playM3(song, mode, startMs)
    }

    private fun playM3(song: Song, mode: PreviewMode, startMs: Long) {
        val p = ensurePlayer(EngineKind.M3)
        Log.d(
            TAG,
            "playM3 enter mode=" + mode + " startMs=" + startMs +
                " state=" + p.playbackState + " pos=" + p.currentPosition,
        )
        if (mode == PreviewMode.FREE) {
            applyFreeShape()
        } else {
            applyLoopShape(song)
        }

        val duration = _durationMs.value.coerceAtLeast(0L)
        degradeRetryUsed = false
        val wasEnded = p.playbackState == Player.STATE_ENDED
        // ENDED 必须先 seek 再 prepare:直接 prepare() 会把位置退回默认点,
        // 只调 play() 又不会自动回到 A(真机复现:state=ENDED、位置冻结)。
        seekRaw(startMs.coerceIn(0L, duration))
        if (wasEnded) {
            Log.w(TAG, "play from ENDED -> seek+prepare mode=" + mode + " startMs=" + startMs)
            p.prepare()
        } else if (p.playbackState == Player.STATE_IDLE) {
            Log.w(TAG, "play from IDLE -> prepare()")
            p.prepare()
        }
        p.play()
        sourceActive = false
        updatePositionOnce()
        Log.d(
            TAG,
            "play mode=" + mode + " startMs=" + startMs + " window=" + window +
                " durationMs=" + duration,
        )
    }

    /**
     * 从当前位置继续播放；常规路径**不 seek**。
     *
     * effect runner 在同一批 effect 已先执行 SeekTo 时用它，避免 Play 内部再次走
     * [LoopPosition.rawSeekUs]（00008 的重复映射来源）。
     *
     * 例外：播放器处于 [Player.STATE_ENDED] 时 prepare() 是 no-op（ExoPlayer 只在 IDLE
     * 响应 prepare），必须**先** seek 回模式起点触发 ENDED→BUFFERING，再 prepare/play；
     * 否则会停在 ENDED、位置冻结（真机复现）。
     */
    fun playFromCurrent(mode: PreviewMode) {
        LoopSourcePauseGate.resume()
        val song = this.song ?: return
        val p = player
        if (!prepared || p == null) {
            // 尚未真正创建播放器：退回普通 play，并使用播放器就绪前的 pending seek。
            val start = pendingSeekMs
                ?: if (shouldUseSource(mode)) sourceStartMs(mode) else modeStartMs()
            pendingSeekMs = null
            play(mode, start)
            return
        }
        Log.d(
            TAG,
            "playFromCurrent enter mode=" + mode + " state=" + p.playbackState +
                " prepared=" + prepared,
        )
        if (sourceActive && shouldUseSource(mode)) {
            this.mode = mode
            val wasEnded = p.playbackState == Player.STATE_ENDED
            if (wasEnded) {
                // SOURCE：ENDED 后 timeline 仍有效,直接回到该模式的初始 T,再 prepare/play。
                val targetT = sourceStartMs(mode)
                Log.w(TAG, "play from ENDED(source) -> seek+prepare mode=" + mode + " targetT=" + targetT + "Ms")
                p.seekTo(targetT)
                p.prepare()
            } else if (p.playbackState == Player.STATE_IDLE) {
                Log.w(TAG, "playFromCurrent from IDLE -> prepare()")
                p.prepare()
            }
            p.play()
            updatePositionOnce()
            Log.d(TAG, "playFromCurrent source mode=" + mode + " window=" + window)
            return
        }
        val sourceEngine = engineKind == EngineKind.SOURCE
        val wantSource = shouldUseSource(mode)
        if (sourceEngine != wantSource) {
            // 模式跨 SOURCE/M3′：重建播放器（预览页允许，A/B 改动不会走到这里）。
            play(mode, sourceStartMs(mode))
            return
        }
        this.mode = mode
        if (mode == PreviewMode.FREE) applyFreeShape() else applyLoopShape(song)
        degradeRetryUsed = false
        val duration = _durationMs.value.coerceAtLeast(0L)
        val wasEnded = p.playbackState == Player.STATE_ENDED
        // ENDED 时只 prepare/play 不会回到 A(真机复现:位置冻结、state 仍 ENDED):
        // 必须按 seek → prepare → play 的顺序,先把位置放回当前模式的起点。
        if (wasEnded) {
            val target = modeStartMs().coerceIn(0L, duration)
            Log.w(TAG, "play from ENDED -> seek+prepare mode=" + mode + " targetMs=" + target)
            seekRaw(target)
            p.prepare()
        } else if (p.playbackState == Player.STATE_IDLE) {
            Log.w(TAG, "playFromCurrent from IDLE -> prepare()")
            p.prepare()
        }
        p.play()
        updatePositionOnce()
        Log.d(TAG, "playFromCurrent mode=" + mode + " window=" + window)
    }

    /**
     * 预填/等待阶段先把窗口写给音频层，**不 seek、不播放**。
     *
     * 分片预填需要 controller.window 已写入才能 seed；若等 Play 才写，
     * 而 Play 又等 PrefillReady，会形成“seed 等 sink / sink 等 Play”的死锁。
     */
    fun applyWindowForPrefill(window: LoopWindow) {
        this.aMs = window.loopStartUs / 1000L
        this.bMs = window.loopEndUs / 1000L
        this.window = window
        degradeRetryUsed = false
        controller.window = window
        controller.tapWindow = window
        updatePositionOnce()
    }

    /**
     * 预填目标窗口（与 [prepare] 使用同一套 sanitize/buildWindow 规则）。
     *
     * 纯函数、任意线程可调用；后台预填解码按它取 [LoopWindow] 的时间边界，
     * 保证解码区间与最终 sink 窗口严格一致。
     */
    fun loopWindowFor(song: Song, aMs: Long, bMs: Long): LoopWindow? {
        val duration = maxOf(song.duration, bMs, 0L)
        val (aa, bb) = sanitize(aMs, bMs, duration)
        return buildWindow(song.id, aa, bb)
    }

    /**
     * 分片预填：把 [startFrame]（**环内帧号**，0 = 窗口起点 A）起的 PCM16（交错小端）
     * 追加进 ring。任意线程可调用；返回是否成功追加（音频层门槛自动判断可否起播）。
     */
    fun seedRingRange(
        window: LoopWindow,
        startFrame: Long,
        pcm16: ByteArray,
        sampleRate: Int,
        channels: Int,
    ): LoopPcmEngine.SeedResult {
        if (LoopSourceMode.isSource()) {
            return LoopPcmEngine.SeedResult(
                status = LoopPcmEngine.SeedStatus.REJECTED_RELEASED,
                detail = "source mode has no ring prefill",
            )
        }
        return controller.seedRingRange(window, startFrame, pcm16, sampleRate, channels)
    }

    /** 当前 ring 已连续覆盖的前缀帧数（分片预填进度）。 */
    val ringCoveredFrames: Long
        get() = if (LoopSourceMode.isSource()) 0L else controller.coveredFrames

    /** 预览播放器是否已经把窗口写进音频层（= 已 prepare/起播过）。 */
    val hasRingWindow: Boolean
        get() = if (LoopSourceMode.isSource()) source != null else controller.window != null

    /**
     * 当前窗口的 ring 是否已经完整（预填或捕获）。为 true 时后台预填解码可以跳过，
     * 避免同一窗口反复解码大区间。
     */
    fun isRingReadyFor(window: LoopWindow): Boolean =
        if (LoopSourceMode.isSource()) sourceWindow == window && source != null
        else controller.window == window && controller.isRingComplete

    /** 暂停(保留位置与已捕获的 ring;resume 走 [play] 从展示位置起播)。 */
    fun pause() {
        LoopSourcePauseGate.pause()
        player?.pause()
        _isPlaying.value = false
    }

    /** 按展示口径 seek。SOURCE 走数据源时间轴 T，M3′ 走 LoopPosition.rawSeekUs。 */
    fun seekToDisplayMs(ms: Long) {
        if (!prepared) return
        val w = window
        val p = player
        if (p == null) {
            pendingSeekMs = ms.coerceAtLeast(0L)
            return
        }
        if (sourceActive && w != null && engineKind == EngineKind.SOURCE) {
            _sourceFirstBlockReady.value = false
            val currentT = p.currentPosition.coerceAtLeast(0L) * 1000L
            val targetT = LoopSourceProjection.timelineUsForDisplay(
                displayUs = ms.coerceAtLeast(0L) * 1000L,
                currentTimelineUs = currentT,
                window = w,
            )
            p.seekTo(targetT / 1000L)
            markSourceReadyFromState(p)
            updatePositionOnce()
            Log.d(TAG, "source seek display=" + ms + "ms targetT=" + (targetT / 1000L) + "ms")
            return
        }
        seekRaw(ms.coerceAtLeast(0L))
        updatePositionOnce()
    }

    /**
     * A/B 松手后应用新窗口:更新 sink 窗口,并按当前模式回到该模式起点重新播放
     * (新窗口会让 ring 失效,从起点起播才能重新捕获完整 ring)。
     */
    fun updateWindow(aMs: Long, bMs: Long) {
        val song = this.song ?: run {
            this.aMs = aMs
            this.bMs = bMs
            return
        }
        val duration = _durationMs.value.coerceAtLeast(0L)
        val (aa, bb) = sanitize(aMs, bMs, duration)
        this.aMs = aa
        this.bMs = bb
        if (this.mode == PreviewMode.FREE) return

        val newWindow = buildWindow(song.id, aa, bb, allowLong = LoopSourceMode.isSource())
        window = newWindow
        controller.window = newWindow
        controller.tapWindow = newWindow

        val p = player
        if (shouldUseSource(this.mode) && p != null && engineKind == EngineKind.SOURCE) {
            _sourceFirstBlockReady.value = false
            val wasPlaying = p.isPlaying
            if (newWindow == null) {
                // 窗口失效：回退 M3′/直通，保持播放器不崩。
                play(this.mode, modeStartMs())
                return
            }
            if (sourceWindow == newWindow && source != null) {
                // 同窗口（例如只改显示）：只重新定位，不重建 source。
                p.seekTo(sourceStartMs(this.mode))
                if (wasPlaying) p.play() else p.pause()
                updatePositionOnce()
                return
            }
            if (prepareSource(song, newWindow)) {
                p.seekTo(sourceStartMs(this.mode))
                if (wasPlaying) p.play() else p.pause()
                markSourceReadyFromState(p)
                updatePositionOnce()
                Log.d(TAG, "source window updated window=" + newWindow + " wasPlaying=" + wasPlaying)
            } else {
                Log.w(TAG, "source window update failed; fallback M3'")
                releasePlayerOnly()
                _sourceFirstBlockEvents.tryEmit(Unit)
                play(this.mode, modeStartMs())
            }
            return
        }

        if (p == null) {
            updatePositionOnce()
            return
        }
        val wasPlaying = p.isPlaying
        degradeRetryUsed = false
        seekRaw(modeStartMs())
        if (wasPlaying) p.play() else p.playWhenReady = false
        updatePositionOnce()
    }

    /** 释放预览播放器与音频控制器(退出页面/确认保存时调用,幂等)。 */
    fun release() {
        releasePlayerOnly()
        handler.removeCallbacks(ticker)
        controller.window = null
        controller.tapWindow = null
        controller.fullDurationUs = 0L
        song = null
        window = null
        prepared = false
        aMs = 0L
        bMs = 0L
        _positionMs.value = 0L
        _isPlaying.value = false
        _buffering.value = false
        _isReplaying.value = false
        _ringReady.value = false
        _durationMs.value = 0L
    }

    // ------------------------------------------------------------------ 内部

    private fun ensurePlayer(kind: EngineKind): ExoPlayer {
        player?.let { existing ->
            if (engineKind == kind) return existing
            releasePlayerOnly()
        }
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()
        val renderersFactory = if (kind == EngineKind.SOURCE) {
            SourceRenderersFactory(context, controller)
        } else {
            LoopRenderersFactory(context, controller)
        }
        val builder = ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
        if (kind == EngineKind.SOURCE) {
            // 源模式 1 秒块：默认 2.5s bufferForPlayback 会要求 3 块才起播。
            // 放宽为 1s 起播，min 3s/max 8s 继续预取，兼顾首声延迟与稳态。
            builder.setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(4_000, 12_000, 1_500, 2_500)
                    .build(),
            )
            Log.d(TAG, "source load control min=4000 max=12000 playback=1500 rebuffer=2500")
        }
        val p = builder.build()
        if (kind == EngineKind.SOURCE) {
            p.addAnalyticsListener(object : AnalyticsListener {
                override fun onPlaybackStateChanged(eventTime: EventTime, state: Int) {
                    Log.d(TAG, "analytics state=" + state + " event=" + eventTime)
                }

                override fun onIsPlayingChanged(eventTime: EventTime, isPlaying: Boolean) {
                    Log.d(TAG, "analytics isPlaying=" + isPlaying + " event=" + eventTime)
                }

                override fun onAudioEnabled(eventTime: EventTime, decoderCounters: DecoderCounters) {
                    Log.d(TAG, "analytics audio enabled event=" + eventTime)
                }

                override fun onAudioInputFormatChanged(
                    eventTime: EventTime,
                    format: androidx.media3.common.Format,
                    decoderReuseEvaluation: DecoderReuseEvaluation?,
                ) {
                    Log.d(
                        TAG,
                        "analytics audio format=" + format.sampleMimeType + "/" + format.pcmEncoding +
                            " " + format.sampleRate + "Hz/" + format.channelCount + "ch",
                    )
                }

                override fun onAudioPositionAdvancing(eventTime: EventTime, playoutStartSystemTimeMs: Long) {
                    Log.d(TAG, "analytics audio position advancing at=" + playoutStartSystemTimeMs)
                }

                override fun onAudioUnderrun(
                    eventTime: EventTime,
                    bufferSize: Int,
                    bufferSizeMs: Long,
                    elapsedSinceLastFeedMs: Long,
                ) {
                    Log.w(
                        TAG,
                        "analytics audio underrun size=" + bufferSize + " ms=" + bufferSizeMs +
                            " sinceFeed=" + elapsedSinceLastFeedMs,
                    )
                }

                override fun onAudioDisabled(eventTime: EventTime, decoderCounters: DecoderCounters) {
                    Log.w(TAG, "analytics audio disabled event=" + eventTime)
                }

                override fun onRenderedFirstFrame(
                    eventTime: EventTime,
                    output: Any,
                    renderTimeMs: Long,
                ) {
                    Log.d(TAG, "analytics rendered first frame")
                }
            })
        }
        p.addListener(listener)
        if (kind == EngineKind.SOURCE) {
            LoopPreviewDebugBridge.register { positionMs ->
                // HTTP/debug 线程 → 播放器必须回到主线程。
                handler.post { seekToDisplayMs(positionMs) }
            }
            // 预览用独立 controller:把 tap 也镜像到它，否则 SOURCE 路径的 tapOut 恒为 null。
            LoopPreviewDebugBridge.registerTap { file -> controller.setPcmTap(file) }
        }
        player = p
        engineKind = kind
        sourceActive = false
        if (kind == EngineKind.M3) {
            // 引擎请求降级(ring 不完整/重放失败)时给调试日志;预览侧不做自动 seek 回补,
            // 由 UI 提示用户改试听模式(完整循环/仅循环段会重新捕获 ring)。
            controller.onDegradeRequested = { targetRawUs ->
                Log.w(TAG, "engine degrade requested targetRawUs=" + targetRawUs)
                // 补环:引擎发现 ring 没覆盖 [A,B)(典型场景:接缝检查从 B−5s 冷启动)。
                // 按引擎建议的目标(当前轮起点 −250ms)seek 回去,让 ring 重新完整捕获;
                // 每次起播只补一次,避免反复跳播。
                if (!degradeRetryUsed) {
                    degradeRetryUsed = true
                    val targetMs = (targetRawUs / 1000L).coerceAtLeast(0L)
                    Log.w(TAG, "degrade recovery seek -> " + targetMs + "ms")
                    try {
                        player?.seekTo(targetMs)
                    } catch (t: Throwable) {
                        Log.w(TAG, "degrade recovery seek failed", t)
                    }
                }
            }
        } else {
            controller.onDegradeRequested = null
        }
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        Log.d(TAG, "preview player created kind=" + kind)
        return p
    }

    private fun markSourceReadyFromState(p: ExoPlayer) {
        val ready = p.playbackState == Player.STATE_READY
        val newlyReady = ready && !_sourceFirstBlockReady.value
        _sourceFirstBlockReady.value = ready
        if (newlyReady) {
            _sourceFirstBlockEvents.tryEmit(Unit)
        }
    }

    private fun releasePlayerOnly() {
        LoopPreviewDebugBridge.release()
        LoopSourcePauseGate.reset()
        handler.removeCallbacks(ticker)
        val p = player
        player = null
        if (p != null) {
            try {
                p.removeListener(listener)
                p.release()
            } catch (t: Throwable) {
                Log.w(TAG, "release player failed", t)
            }
        }
        source = null
        sourceTimeline = null
        sourceWindow = null
        sourceSongId = null
        sourceActive = false
        engineKind = EngineKind.M3
        controller.window = null
        controller.tapWindow = null
        controller.onDegradeRequested = null
        _isReplaying.value = false
        _ringReady.value = false
        _sourceFirstBlockReady.value = false
    }

    /** 当前模式是否走循环数据源：SOURCE + 非 FREE + 有有效窗口。 */
    private fun shouldUseSource(mode: PreviewMode): Boolean =
        LoopSourceMode.isSource() && mode != PreviewMode.FREE && window != null

    /**
     * 为当前窗口构建/安装循环 PCM 数据源。
     *
     * - 数据源时间轴 T=0 对应当前 A；
     * - MediaItem 打 [LoopSourceMediaTag]，供 LoopSessionPlayer 未来识别；
     * - A/B 变化 = 新 timeline/new source（新缓存 key），不重建 ExoPlayer。
     */
    private fun prepareSource(song: Song, window: LoopWindow): Boolean {
        val p = ensurePlayer(EngineKind.SOURCE)
        if (source != null &&
            sourceWindow == window &&
            sourceSongId == song.id &&
            engineKind == EngineKind.SOURCE
        ) {
            return true
        }
        return try {
            val format = PcmFileFormatProbe.probe(song.filePath)
            val timeline = LoopingPcmTimeline(
                songId = song.id,
                loopStartUs = window.loopStartUs,
                loopEndUs = window.loopEndUs,
                format = format,
                entryPositionUs = window.loopStartUs,
            )
            val item = song.toMediaItem().buildUpon()
                .setTag(LoopSourceMediaTag)
                .build()
            val provider = PcmDecoderRangeProvider(pcmDecoder, song.filePath)
            val mediaSource: MediaSource = if (LoopSourcePipeline.isExtractor()) {
                LoopingPcmExtractorMediaSource.create(
                    item = item,
                    timeline = timeline,
                    provider = provider,
                    logger = { message -> Log.d("LoopPcmSource", message) },
                    onSeamOutputFrames = { outFrames -> controller.tapSeam(outFrames) },
                )
            } else {
                LoopingPcmMediaSource(
                    item = item,
                    timeline = timeline,
                    provider = provider,
                )
            }
            Log.d(TAG, "source pipeline=" + LoopSourcePipeline.current + " source=" + mediaSource)
            p.setMediaSource(mediaSource, /* resetPosition= */ false)
            p.prepare()
            this.source = mediaSource
            this.sourceTimeline = timeline
            this.sourceWindow = window
            this.sourceSongId = song.id
            sourceActive = true
            markSourceReadyFromState(p)
            controller.window = window
            controller.tapWindow = window
            controller.fullDurationUs = _durationMs.value * 1000L
            Log.d(
                TAG,
                "source installed song=" + song.id + " window=" + window +
                    " format=" + format + " timelineFrames=" + timeline.loopFrames +
                    " path=" + song.filePath,
            )
            true
        } catch (t: Throwable) {
            Log.w(TAG, "prepare source failed", t)
            source = null
            sourceTimeline = null
            sourceWindow = null
            sourceSongId = null
            sourceActive = false
            false
        }
    }

    /** SOURCE 模式起始时间轴 T（ms）。FULL/LOOP_ONLY 从 A 起，SEAM 从 B−5s 起。 */
    private fun sourceStartMs(mode: PreviewMode): Long {
        val w = window ?: return 0L
        val startUs = when (mode) {
            PreviewMode.FULL, PreviewMode.LOOP_ONLY -> w.loopStartUs
            PreviewMode.SEAM -> maxOf(w.loopStartUs, w.loopEndUs - SEAM_LEAD_MS * 1000L)
            PreviewMode.FREE -> 0L
        }
        return LoopSourceProjection.initialTimelineUs(startUs, w) / 1000L
    }

        private fun applyLoopShape(song: Song) {
        val w = window ?: buildWindow(song.id, aMs, bMs).also {
            window = it
        }
        window = w
        controller.window = w
        controller.tapWindow = w
    }

    private fun applyFreeShape() {
        window = null
        controller.window = null
        controller.tapWindow = null
    }

    /**
     * 建 MediaItem。
     *
     * **不使用 MediaItem.ClippingConfiguration**:实测 media3 1.8 的 clipped source 会把
     * ExoPlayer 传给 AudioSink 的 `outputStreamOffsetUs` 变成 -1e12,于是 sink 侧媒体 PTS
     * = 1e12 + 真实位置 → 引擎永远命中不了 [A,B) → 到 B 直接降级为直通(真机 logcat 实证:
     * `enginePosUs=1000008363999`、`degrade requested targetRawUs=A-250ms`)。
     *
     * 环回本来就不需要 source 在 B 处 EOS:LoopAudioSink 会在 PCM 层把越过 B 的输入裁掉
     * 并原地重放 ring(越 B 之后的源样本被丢弃),没有 period/EOS/flush 边界,这也正是
     * M3' 的设计前提。所以循环/自由模式共用同一个不裁剪的 MediaItem。
     */
    private fun buildMediaItem(song: Song): MediaItem = song.toMediaItem()

    private fun buildWindow(
        songId: Long,
        aMs: Long,
        bMs: Long,
        allowLong: Boolean = false,
    ): LoopWindow? {
        if (songId <= 0L || bMs - aMs < MIN_LOOP_LENGTH_MS) return null
        // M3′ 的 ring 上限(300s / 64MB):超限时引擎会降级直通,这里同步不启用窗口。
        // SOURCE 数据源按 1s 块按需解码,没有这个上限。
        if (!allowLong && bMs - aMs > LoopPcmEngine.MAX_LOOP_DURATION_US / 1000L) return null
        return LoopWindow(songId, aMs * 1000L, bMs * 1000L)
    }

    private fun sanitize(aMs: Long, bMs: Long, durationMs: Long): Pair<Long, Long> {
        val duration = durationMs.coerceAtLeast(MIN_LOOP_LENGTH_MS + 1L)
        val a = aMs.coerceIn(0L, (duration - MIN_LOOP_LENGTH_MS).coerceAtLeast(0L))
        val b = bMs.coerceIn(a + MIN_LOOP_LENGTH_MS, duration)
        return a to b
    }

    private fun modeStartMs(): Long = when (mode) {
        PreviewMode.FULL -> 0L
        PreviewMode.LOOP_ONLY -> (aMs - LOOP_PREROLL_MS).coerceAtLeast(0L)
        // 极短循环段 B−5s < A 时取 A。
        PreviewMode.SEAM -> maxOf(aMs, bMs - SEAM_LEAD_MS)
        PreviewMode.FREE -> 0L
    }

    /** 展示口径 seek → 引擎原始位置(窗口存在时按当前轮次映射,避免把播放拽回第 0 轮)。 */
    private fun seekRaw(displayMs: Long) {
        val p = player ?: return
        val w = window
        if (w == null) {
            p.seekTo(displayMs)
            return
        }
        val rawUs = LoopPosition.rawSeekUs(
            targetUs = displayMs * 1000L,
            w = w,
        )
        p.seekTo((rawUs / 1000L).coerceAtLeast(0L))
    }

    private fun rawNowUs(): Long {
        val p = player ?: return 0L
        return if (controller.isReplaying && controller.positionUs > 0L) {
            controller.positionUs
        } else {
            p.currentPosition.coerceAtLeast(0L) * 1000L
        }
    }

    /**
     * 展示口径位置:
     * - 重放期读 sink 报告的引擎原始位置(B + (k−1)L + 环内偏移),再经 [LoopPosition.displayUs]
     *   折返到 [A, B);
     * - 其余时候读 ExoPlayer 位置(被 clip 钳在 [0, B],前奏/区间内原样)。
     */
    private fun updatePositionOnce() {
        val p = player ?: return
        val w = window
        if (sourceActive && engineKind == EngineKind.SOURCE && w != null) {
            val timelineUs = p.currentPosition.coerceAtLeast(0L) * 1000L
            val displayUs = LoopSourceProjection.displayUs(timelineUs, w)
            _positionMs.value = displayUs.coerceAtLeast(0L) / 1000L
            _isPlaying.value = p.isPlaying
            _isReplaying.value = _sourceFirstBlockReady.value
            _ringReady.value = true
            return
        }
        val rawUs = if (w != null && controller.isReplaying) {
            val engineUs = controller.positionUs
            if (engineUs > 0L) engineUs else p.currentPosition.coerceAtLeast(0L) * 1000L
        } else {
            p.currentPosition.coerceAtLeast(0L) * 1000L
        }
        val displayUs = if (w != null) LoopPosition.displayUs(rawUs, w) else rawUs
        _positionMs.value = (displayUs.coerceAtLeast(0L)) / 1000L
        _isPlaying.value = p.isPlaying
        _isReplaying.value = controller.isReplaying
        _ringReady.value = w != null && controller.isRingComplete
    }

    private var tickCount = 0

    private val ticker = object : Runnable {
        override fun run() {
            updatePositionOnce()
            tickCount++
            if (tickCount % 30 == 0) {
                val p = player
                Log.d(
                    TAG,
                    "tick posMs=" + _positionMs.value + " playerPos=" + p?.currentPosition +
                        " buffered=" + p?.bufferedPosition +
                        " dur=" + p?.duration +
                        " playing=" + (p?.isPlaying == true) +
                        " playWhenReady=" + (p?.playWhenReady == true) +
                        " state=" + p?.playbackState +
                        " error=" + p?.playerError +
                        " replaying=" + controller.isReplaying +
                        " ringReady=" + controller.isRingComplete +
                        " seam=" + controller.seamCount +
                        " degrade=" + controller.degradeCount +
                        " enginePosUs=" + controller.positionUs,
                )
            }
            handler.postDelayed(this, POSITION_TICK_MS)
        }
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            _buffering.value = playbackState == Player.STATE_BUFFERING
            _isPlaying.value = player?.isPlaying == true
            if (sourceActive && engineKind == EngineKind.SOURCE) {
                Log.d(TAG, "source state=" + playbackState + " playing=" + (player?.isPlaying == true))
                if (playbackState == Player.STATE_READY) {
                    if (!_sourceFirstBlockReady.value) {
                        Log.d(TAG, "source first block ready")
                    }
                    _sourceFirstBlockReady.value = true
                    _sourceFirstBlockEvents.tryEmit(Unit)
                } else if (playbackState == Player.STATE_BUFFERING) {
                    _sourceFirstBlockReady.value = false
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
            if (sourceActive && engineKind == EngineKind.SOURCE) {
                Log.d(TAG, "source isPlaying=" + isPlaying)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "preview playback error: " + error.errorCodeName, error)
            _error.value = "预览播放失败:" + error.errorCodeName
        }
    }
}
