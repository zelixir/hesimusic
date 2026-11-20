package com.zjr.hesimusic.ui.loop

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import com.zjr.hesimusic.core.invariants.AudioOutputFrames
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
 * - 循环模式:FULL/LOOP_ONLY/SEAM 与 FREE 共用 `song.toMediaItem()`,**不额外添加**
 *   循环剪裁。B 处的环回完全由音频层窗口/ring 完成;越过 B 后的位置由 sink 侧
 *   `LoopAudioController.positionUs` 报告,不靠 source 在 B 处 EOS。
 *   (CUE 曲目自带的 clip 是分轨语义:window 时长 = end−start、默认位置 = clip 起点、
 *   位置钳在 [0, clipLen],与环回无关。)
 * - FREE:窗口置空,整曲直通。
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

        /** 接缝检查起播提前量(契约 §2.3:B−5s)。唯一来源见 [PreviewStartPlan.SEAM_LEAD_MS]。 */
        const val SEAM_LEAD_MS = PreviewStartPlan.SEAM_LEAD_MS

        /**
         * 循环模式起播预卷(ms):ExoPlayer 的 seek 会落到目标 ±十几毫秒,若正好从 A 起播,
         * ring 会缺 [A, 实际起点) 这一小段 → 引擎判定 ring 不完整而退化为直通。
         * 因此从 A 前预卷 300ms 起播,让环缓冲区从 A 之前开始捕获。
         * 唯一来源见 [PreviewStartPlan.LOOP_PREROLL_MS]。
         */
        const val LOOP_PREROLL_MS = PreviewStartPlan.LOOP_PREROLL_MS

        /** 循环段最短时长(与 UI 拖拽下限一致)。 */
        const val MIN_LOOP_LENGTH_MS = 500L
    }

    init {
        // 打开结果页时读取真机调试开关（默认 M3′）。
        LoopSourceMode.refreshFromSettings(context)
        LoopSourcePipeline.refreshFromSettings(context)
        // 量测探针：debug 端点读当前页面这一份预览播放器（release 不编译 debug 端点，这里只是挂引用）。
        PreviewProbe.attach(this)
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

    // ---- 量测（issue 00023）：seek 请求/应用时刻与只读快照 ------------------
    // 只累加计数与时间戳，不改变任何播放行为；debug 源集的 /debug/preview[-seek] 读它。

    /** 收到多少次展示口径 seek（含 seekToDisplayMs 的 pending 分支）。 */
    @Volatile
    private var seekRequests: Long = 0L

    /** 最近一次 seek 请求时刻（elapsedRealtime）与目标（展示口径）。 */
    @Volatile
    private var lastSeekRequestAtMs: Long = 0L
    @Volatile
    private var lastSeekRequestedMs: Long = 0L

    /** 最近一次真正落到播放器的 seek：时刻与播放器时间轴目标（T/raw ms）。 */
    @Volatile
    private var lastSeekAppliedAtMs: Long = 0L
    @Volatile
    private var lastSeekPlayerTargetMs: Long = 0L

    /** 主线程繁忙时给 debug 线程回退用的上一份快照。 */
    @Volatile
    private var lastProbeSnapshot: Map<String, Any?>? = null

    private val handler = Handler(Looper.getMainLooper())

    private var song: Song? = null

    /** 当前应用给 sink 的窗口;null = 直通(FREE 或非法窗口)。 */
    @Volatile
    private var window: LoopWindow? = null

    private var prepared: Boolean = false

    /** 本次起播是否已经因「ring 不完整」自动补环 seek 过一次(防反复跳)。 */
    private var degradeRetryUsed: Boolean = false

    /** 仅用于 onPlaybackStateChanged before/after 诊断(真机 ENDED 排查)。 */
    private var lastPlaybackState: Int = Player.STATE_IDLE

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

    private val _outsideLoop = MutableStateFlow(false)

    /**
     * 是否处于"循环区外自由试听"(issue 00023)。
     *
     * true = 用户把播放头拖到了 [A,B) 之外:循环窗口**不生效**,按文件时间播放,
     * 位置/进度条都是真实媒体时间(不折返)。拖回 [A,B) 内或显式选模式/选候选时恢复循环。
     */
    val outsideLoop: StateFlow<Boolean> = _outsideLoop.asStateFlow()

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
     * 为某首歌建立预览(暂停态):记录歌曲/窗口/时长,**不创建播放器也不播放**。
     * 播放器与 MediaItem 延迟到首次 [play] 时创建([playM3] 负责 setMediaItem),
     * 避免预填等待期创建空播放器(86a6b1f 的空 timeline ENDED 回归)。
     */
    fun prepare(song: Song, aMs: Long, bMs: Long) {
        val duration = maxOf(song.duration, bMs, 0L)
        Log.d(
            TAG,
            "prepare path=" + song.filePath + " aMs=" + aMs + " bMs=" + bMs +
                " durationMs=" + duration + " state=" + (player?.playbackState ?: -1) +
                " mediaItem=" + (player?.currentMediaItem?.mediaId),
        )
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
        // 新页面/新歌一律从循环试听开始;自由试听只在本次页面内由区外 seek 触发。
        _outsideLoop.value = false
        _positionMs.value = aa
        updatePositionOnce()
    }

    /**
     * 按模式起播。已 prepare 时只做窗口切换 + seek + play(MediaItem 不裁剪、不重建)。
     *
     * @param freeAudition true = 循环区外自由试听(issue 00023):循环窗口不生效,按**文件时间**
     *   从 [startMs] 播放。由页面状态 [com.zjr.hesimusic.ui.loop.state.LoopPageState.Ready]
     *   的 `auditionOutside` 传入;显式点模式/选候选恒为 false(每次都是明确的循环试听意图)。
     */
    fun play(mode: PreviewMode, startMs: Long, freeAudition: Boolean = false) {
        LoopSourcePauseGate.resume()
        val song = this.song ?: return
        Log.d(
            TAG,
            "play mode=" + mode + " startMs=" + startMs + " freeAudition=" + freeAudition +
                " path=" + song.filePath +
                " state=" + (player?.playbackState ?: -1) +
                " durationMs=" + _durationMs.value +
                " mediaItem=" + (player?.currentMediaItem?.mediaId),
        )
        if (!prepared) {
            prepare(song, aMs, bMs)
        }
        pendingSeekMs = null
        this.mode = mode
        if (freeAudition) {
            // 区外自由试听:循环窗口不生效,按文件时间播放。
            _outsideLoop.value = true
            playFreeAudition(song, startMs, play = true)
            return
        }
        if (_outsideLoop.value) {
            // 从自由试听回到循环(显式点模式/候选/播放键):先装回循环窗口,再从 startMs 起播。
            _outsideLoop.value = false
            armLoopAt(song, startMs, play = true)
            return
        }
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

    // ------------------------------------------------------------ 循环区外自由试听(issue 00023)

    /**
     * 整曲"伪窗口"(0 → 时长):给 SOURCE 数据源当自由试听的源。
     *
     * 为什么不能只把 window 置空:SOURCE 播放器的数据源是按窗口构建的(T=0 对应 A,
     * 媒体位置按 [A,B) 取模),**不存在窗口外的位置**;要按文件时间播放就必须换一个覆盖整曲的
     * 时间轴(T = 媒体时间,环回点落到文件末尾)。这里只换 MediaSource,不重建 ExoPlayer,
     * 所以自由试听与循环试听共用同一个播放器/音频管线。
     */
    private fun freeAuditionWindow(song: Song): LoopWindow? {
        if (!LoopSourceMode.isSource()) return null
        val duration = _durationMs.value.coerceAtLeast(0L)
        if (duration < MIN_LOOP_LENGTH_MS) return null
        return LoopWindow(song.id, 0L, duration * 1000L)
    }

    /**
     * 区外自由试听:按文件时间从 [startMs] 起播(循环窗口不生效)。
     *
     * 优先保 SOURCE 数据源(整曲窗口),失败回退 M3′ 直通;两条路都不做"把目标折回 [A,B)"的投影,
     * 这是 issue 00023 的核心:原生 ExoPlayer 的 seek 目标是必须被尊重的。
     */
    private fun playFreeAudition(song: Song, startMs: Long, play: Boolean) {
        val duration = _durationMs.value.coerceAtLeast(0L)
        val target = if (duration > 0L) startMs.coerceIn(0L, duration) else startMs.coerceAtLeast(0L)
        val current = player
        val freeWindow = freeAuditionWindow(song)
        Log.i(
            TAG,
            "free audition enter song=" + song.id + " target=" + target + " play=" + play +
                " engine=" + engineKind + " freeWindow=" + freeWindow,
        )
        if (freeWindow != null &&
            (current == null || engineKind == EngineKind.SOURCE) &&
            prepareSource(song, freeWindow)
        ) {
            val p = player ?: return
            // 整曲窗口的 T 与媒体时间一一对应;mute M3′ 的窗口字段,避免两条通路对同一字段的理解冲突。
            controller.window = null
            controller.tapWindow = null
            p.seekTo(target)
            recordAppliedSeek(target)
            _sourceFirstBlockReady.value = false
            sourceActive = true
            if (p.playbackState == Player.STATE_IDLE || p.playbackState == Player.STATE_ENDED) {
                p.prepare()
            }
            if (play) p.play() else p.pause()
            markSourceReadyFromState(p)
            updatePositionOnce()
            Log.i(TAG, "free audition source ready target=" + target + " window=" + freeWindow)
            return
        }
        // 回退:M3′ 直通(窗口置空),原生按文件时间播放。
        val p = ensurePlayer(EngineKind.M3)
        ensureM3MediaItem(p, song)
        applyFreeShape()
        val wasEnded = p.playbackState == Player.STATE_ENDED
        p.seekTo(target)
        recordAppliedSeek(target)
        if (wasEnded) {
            p.prepare()
        } else if (p.playbackState == Player.STATE_IDLE) {
            p.prepare()
        }
        if (play) p.play() else p.pause()
        sourceActive = false
        updatePositionOnce()
        Log.i(TAG, "free audition passthrough(m3) target=" + target)
    }

    /**
     * 装回循环窗口并从 [targetMs](展示口径)起播;用于"自由试听 → 拖回 [A,B)"与
     * "自由试听中点模式/选候选"。
     *
     * 落点口径:新装的 SOURCE 数据源 T=0 恒对应 A(entryOffset=0),所以落点 T = target − A,
     * **不能**再走 [LoopSourceProjection.timelineUsForDisplay] 的"当前轮次"映射 —— 那个函数
     * 会把窗口外的目标折回 [A,B),正是本次要修掉的行为。
     */
    private fun armLoopAt(song: Song, targetMs: Long, play: Boolean) {
        val w = window
        val current = player
        val target = targetMs.coerceAtLeast(0L)
        Log.i(TAG, "arm loop target=" + target + " play=" + play + " window=" + w + " engine=" + engineKind)
        if (w != null && shouldUseSource(mode) &&
            (current == null || engineKind == EngineKind.SOURCE) &&
            prepareSource(song, w)
        ) {
            val p = player ?: return
            controller.window = w
            controller.tapWindow = w
            _sourceFirstBlockReady.value = false
            val targetT = (target * 1000L - w.loopStartUs)
                .coerceIn(0L, (w.lengthUs - 1L).coerceAtLeast(0L)) / 1000L
            p.seekTo(targetT)
            recordAppliedSeek(targetT)
            sourceActive = true
            if (p.playbackState == Player.STATE_IDLE || p.playbackState == Player.STATE_ENDED) {
                p.prepare()
            }
            if (play) p.play() else p.pause()
            markSourceReadyFromState(p)
            updatePositionOnce()
            Log.i(TAG, "arm loop source ready targetT=" + targetT + " window=" + w)
            return
        }
        // M3′:窗口装回 + 展示口径 seek(第 0 轮)。自由模式没有循环窗口,不能装回窗口。
        val p = ensurePlayer(EngineKind.M3)
        ensureM3MediaItem(p, song)
        if (mode == PreviewMode.FREE) applyFreeShape() else applyLoopShape(song)
        val wasEnded = p.playbackState == Player.STATE_ENDED
        seekRaw(target)
        if (wasEnded) {
            p.prepare()
        } else if (p.playbackState == Player.STATE_IDLE) {
            p.prepare()
        }
        if (play) p.play() else p.pause()
        sourceActive = false
        updatePositionOnce()
        Log.i(TAG, "arm loop m3 target=" + target + " window=" + window)
    }

    private fun playM3(song: Song, mode: PreviewMode, startMs: Long) {
        val p = ensurePlayer(EngineKind.M3)
        ensureM3MediaItem(p, song)
        Log.d(
            TAG,
            "playM3 enter mode=" + mode + " startMs=" + startMs +
                " state=" + p.playbackState + " pos=" + p.currentPosition +
                " path=" + song.filePath + " durationMs=" + _durationMs.value,
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
    fun playFromCurrent(mode: PreviewMode, freeAudition: Boolean = false) {
        LoopSourcePauseGate.resume()
        val song = this.song ?: return
        if (freeAudition != _outsideLoop.value) {
            // 自由试听 ↔ 循环 的切换必须走完整路径(装/拆整曲源),不能"从当前位置续播"。
            val start = pendingSeekMs ?: _positionMs.value
            pendingSeekMs = null
            play(mode, start, freeAudition)
            return
        }
        val p = player
        if (!prepared || p == null) {
            // 尚未真正创建播放器：退回普通 play，并使用播放器就绪前的 pending seek。
            // 起点必须是**展示口径**(play() 内部再按引擎换算成 T);
            // 用 SOURCE 的 T 当展示位置会把 FULL/SEAM 的起点映射错(issue 00007)。
            val start = pendingSeekMs ?: modeStartMs(mode)
            pendingSeekMs = null
            play(mode, start, freeAudition)
            return
        }
        Log.d(
            TAG,
            "playFromCurrent enter mode=" + mode + " state=" + p.playbackState +
                " prepared=" + prepared + " freeAudition=" + freeAudition,
        )
        if (freeAudition) {
            // 自由试听里继续播放:整曲源/直通已经在位,只 play。
            this.mode = mode
            if (p.playbackState == Player.STATE_IDLE) p.prepare()
            p.play()
            updatePositionOnce()
            return
        }
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
            // 传展示口径起点，由 play() 按目标引擎换算（T / 原始位置）。
            play(mode, modeStartMs(mode))
            return
        }
        this.mode = mode
        ensureM3MediaItem(p, song)
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

    /**
     * 按展示口径 seek。SOURCE 走数据源时间轴 T，M3′ 走 LoopPosition.rawSeekUs。
     *
     * 循环区外 seek(issue 00023):
     * - **允许**,并且落点必须是真实媒体时间:退出循环播放,转"按文件时间"自由试听
     *   (SOURCE 换整曲源 / M3′ 清窗口),进度条与播放头按真实媒体时间走,不再折返;
     * - 回到 [A,B) 内再 seek → 自动恢复循环(同一 A/B/模式),从落点继续;
     * - 自由试听里的普通 seek 走原生 seek(整曲源/直通),不重复换源。
     *
     * 循环区内 seek:保持原来的"快速 seek"路径(只 seekTo,不 prepare/不换源/不重建 player)。
     */
    fun seekToDisplayMs(ms: Long) {
        if (!prepared) return
        // 量测：先记请求（elapsedRealtime），真正 seekTo 后再记应用时刻（00023）。
        seekRequests += 1L
        lastSeekRequestAtMs = SystemClock.elapsedRealtime()
        lastSeekRequestedMs = ms.coerceAtLeast(0L)
        // 00023 后续：逐段计时从这里起算（请求 → 落地 → 首个 PCM → sink.play → READY → 出声）。
        LoopPreviewDebugBridge.seekStages.begin(lastSeekRequestAtMs)
        val w = window
        val p = player
        if (p == null) {
            pendingSeekMs = ms.coerceAtLeast(0L)
            return
        }
        val target = ms.coerceAtLeast(0L)
        val loopArmed = w != null && mode != PreviewMode.FREE
        val inside = if (w == null) {
            false
        } else {
            target >= w.loopStartUs / 1000L && target < w.loopEndUs / 1000L
        }
        if (loopArmed && !inside && !_outsideLoop.value) {
            // 区外 seek:离开循环播放,按文件时间试听(不再把目标折回 [A,B))。
            _outsideLoop.value = true
            Log.i(TAG, "seek outside loop -> free audition target=" + target + " window=" + w)
            val song = this.song ?: return
            playFreeAudition(song, target, play = p.isPlaying)
            return
        }
        if (_outsideLoop.value) {
            val song = this.song ?: return
            if (loopArmed && inside) {
                // 回到区间:恢复循环(同一 A/B/模式),从落点继续。
                val wasPlaying = p.isPlaying
                _outsideLoop.value = false
                Log.i(TAG, "seek back inside loop -> restore loop target=" + target)
                armLoopAt(song, target, play = wasPlaying)
                return
            }
            // 自由试听中的普通 seek:整曲源/直通,原生 seek。
            p.seekTo(target)
            recordAppliedSeek(target)
            updatePositionOnce()
            Log.d(TAG, "free audition seek target=" + target)
            return
        }
        if (sourceActive && w != null && engineKind == EngineKind.SOURCE) {
            _sourceFirstBlockReady.value = false
            val currentT = p.currentPosition.coerceAtLeast(0L) * 1000L
            val targetT = LoopSourceProjection.timelineUsForDisplay(
                displayUs = target * 1000L,
                currentTimelineUs = currentT,
                window = w,
            )
            p.seekTo(targetT / 1000L)
            recordAppliedSeek(targetT / 1000L)
            markSourceReadyFromState(p)
            updatePositionOnce()
            Log.d(TAG, "source seek display=" + target + "ms targetT=" + (targetT / 1000L) + "ms")
            return
        }
        seekRaw(target)
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
        if (_outsideLoop.value) {
            // 自由试听中改 A/B:只记录新窗口,不打断"按文件时间"的试听;拖回区间内会用新窗口恢复循环。
            // (若此时重建 SOURCE 源,播放头会被拽回 A,等于把用户刚拖到的区外位置又夹掉了。)
            Log.d(TAG, "updateWindow while free audition -> defer window=" + newWindow)
            updatePositionOnce()
            return
        }
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
        if (engineKind == EngineKind.SOURCE) {
            // 新模式/新窗口不再由 SOURCE 表达(如前奏非空的 FULL):交给 play() 换到 M3′,
            // 不能对 SOURCE 时间轴做 seekRaw(那会把落点当成 T 再折回 A)。
            play(this.mode, modeStartMs())
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
        PreviewProbe.detach(this)
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
            // 源模式 1 秒块：media3 默认 bufferForPlayback=1s/afterRebuffer=2s。
            // 这里用 1.5s/2.5s + min 4s/max 12s，配合 Extractor 的 2 块起播门槛，
            // 兼顾首声延迟与稳态预取。
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
                    // 00023 后续：AudioTrack 播放头开始推进 = 真正出声（应用侧代理）。
                    LoopPreviewDebugBridge.seekStages.notePlayout(playoutStartSystemTimeMs)
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
        lastPlaybackState = p.playbackState
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
        Log.d(
            TAG,
            "preview player created kind=" + kind + " path=" + (song?.filePath) +
                " state=" + p.playbackState + " durationMs=" + _durationMs.value,
        )
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
        lastPlaybackState = Player.STATE_IDLE
        controller.window = null
        controller.tapWindow = null
        controller.onDegradeRequested = null
        _isReplaying.value = false
        _ringReady.value = false
        _sourceFirstBlockReady.value = false
        _outsideLoop.value = false
    }

    /**
     * 当前模式是否走循环数据源。
     *
     * = 引擎开关为 SOURCE + 有有效窗口 + 模式能被 SOURCE 表达。
     * **前奏非空的 FULL 不走 SOURCE**：SOURCE 时间轴 T=0 恒对应 A,表达不了「0..A 前奏」;
     * 这类 FULL 交给 M3′(前奏原样播放,B 处 sink 折回 A),语义见 [PreviewStartPlan]。
     */
    private fun shouldUseSource(mode: PreviewMode): Boolean =
        PreviewStartPlan.usesSource(mode, window, LoopSourceMode.isSource())

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

    /**
     * SOURCE 模式起始时间轴 T（ms）。T=0 恒对应 A：
     * - FULL（无前奏，A=0）→ 0（= 媒体 0，与 M3′ 起播一致）；
     * - LOOP_ONLY → 0（预卷落点被钳回 A）；
     * - SEAM → max(A, B−5s) − A；
     * - FULL 有前奏（A>0）不会走 SOURCE（[shouldUseSource] 为 false），交给 M3′ 从 0 起播。
     */
    private fun sourceStartMs(mode: PreviewMode): Long =
        PreviewStartPlan.sourceStartMs(mode, aMs, bMs, window)

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
     * **不额外添加循环剪裁**:media3 1.8 给首个 period 的 renderer offset 恒为
     * `INITIAL_RENDERER_POSITION_OFFSET_US = 1e12`(MediaPeriodQueue),它不是 clip 造成的;
     * 真机 logcat 的 `enginePosUs=1000008363999` = 1e12 + 8.364s 的真实位置。
     * clip 的真实效果是改 window:`positionInFirstPeriodUs += startUs`、
     * `durationUs = end − start`,同时 period 的 `positionInWindowUs -= startUs`
     * (两项相加仍是原 window 位置,所以 renderer offset 仍是 1e12),并把位置钳在
     * [0, end−start](ClippingMediaSource)。
     *
     * 环回本来就不需要 source 在 B 处 EOS:LoopAudioSink 会在 PCM 层把越过 B 的输入裁掉
     * 并原地重放 ring(越 B 之后的源样本被丢弃),没有 period/EOS/flush 边界,这也正是
     * M3' 的设计前提。所以循环/自由模式共用同一个 **不额外裁剪** 的 MediaItem;
     * CUE 曲目由 SongMapper 生成的 clip 只决定分轨窗口,不是环回边界。
     */
    private fun buildMediaItem(song: Song): MediaItem = song.toMediaItem()

    /**
     * M3 播放器必须持有 MediaItem 才能 prepare。
     *
     * 历史回归(86a6b1f):播放器改为 play 时才创建后,prepare() 只记录窗口,
     * 原 p.setMediaItem(buildMediaItem(song)) 被删掉;于是 playM3 在空 timeline
     * 上 seek/prepare,ExoPlayer 直接进入 ENDED(真机:进页 state=4、pos 不动)。
     * 同一个 mediaId 不重复设置,避免每次点模式 chip 重建 source/丢缓存。
     */
    private fun ensureM3MediaItem(p: ExoPlayer, song: Song) {
        val expectedId = song.id.toString()
        val current = p.currentMediaItem
        if (current?.mediaId == expectedId && p.mediaItemCount > 0) {
            Log.d(
                TAG,
                "mediaItem ok path=" + song.filePath + " state=" + p.playbackState +
                    " durationMs=" + _durationMs.value,
            )
            return
        }
        Log.d(
            TAG,
            "setMediaItem path=" + song.filePath + " state=" + p.playbackState +
                " durationMs=" + _durationMs.value + " hadMediaItem=" + (current != null),
        )
        p.setMediaItem(buildMediaItem(song), false)
    }

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

    /**
     * 展示口径的模式起点(ms)。FULL 恒为 0(前奏参与);语义唯一来源见 [PreviewStartPlan.displayStartMs]。
     */
    private fun modeStartMs(mode: PreviewMode = this.mode): Long =
        PreviewStartPlan.displayStartMs(mode, aMs, bMs)

    /** 展示口径 seek → 引擎原始位置(窗口存在时按当前轮次映射,避免把播放拽回第 0 轮)。 */
    private fun seekRaw(displayMs: Long) {
        val p = player ?: return
        Log.d(
            TAG,
            "seekRaw displayMs=" + displayMs + " path=" + (song?.filePath) +
                " state=" + p.playbackState + " durationMs=" + _durationMs.value +
                " hasMediaItem=" + (p.currentMediaItem != null),
        )
        val w = window
        if (w == null) {
            p.seekTo(displayMs)
            recordAppliedSeek(displayMs)
            return
        }
        val rawUs = LoopPosition.rawSeekUs(
            targetUs = displayMs * 1000L,
            w = w,
        )
        val targetMs = (rawUs / 1000L).coerceAtLeast(0L)
        Log.d(TAG, "seekRaw target=" + targetMs + "Ms window=" + w)
        p.seekTo(targetMs)
        recordAppliedSeek(targetMs)
    }

    private fun recordAppliedSeek(playerTargetMs: Long) {
        lastSeekAppliedAtMs = SystemClock.elapsedRealtime()
        lastSeekPlayerTargetMs = playerTargetMs
        LoopPreviewDebugBridge.seekStages.noteApplied()
    }

    /**
     * 只读快照（**必须在主线程调用**：ExoPlayer 的位置只能在创建它的线程读）。
     *
     * 字段口径与 /debug/preview 一致；outputFrames 是 TapAudioSink 真正写出的帧数
     * （按 controller 分桶，预览与主播放互不污染），作为"出声"代理：
     * 它增长 = PCM 已交给 AudioTrack。
     */
    fun debugSnapshot(): Map<String, Any?> {
        val p = player
        val nowElapsed = SystemClock.elapsedRealtime()
        val out = linkedMapOf<String, Any?>(
            "atMs" to nowElapsed,
            "prepared" to prepared,
            "songId" to song?.id,
            "durationMs" to _durationMs.value,
            "mode" to mode.name,
            "aMs" to aMs,
            "bMs" to bMs,
            "engine" to engineKind.name,
            "sourceActive" to sourceActive,
            "hasPlayer" to (p != null),
            "hasMediaItem" to (p?.currentMediaItem != null),
            "playbackState" to p?.playbackState,
            "playbackStateName" to playbackStateName(p?.playbackState),
            "isPlaying" to (p?.isPlaying == true),
            "playWhenReady" to (p?.playWhenReady == true),
            "buffering" to _buffering.value,
            "playerPositionMs" to (p?.currentPosition ?: -1L),
            "displayMs" to _positionMs.value,
            // 循环窗口(用于恢复循环);自由试听期间不生效,但保留着等"拖回区间"恢复。
            "windowA_ms" to (window?.loopStartUs?.div(1000L) ?: null),
            "windowB_ms" to (window?.loopEndUs?.div(1000L) ?: null),
            // 当前真正装进 SOURCE 数据源的窗口(自由试听 = 0..duration 的整曲窗口)。
            "sourceWindowA_ms" to (sourceWindow?.loopStartUs?.div(1000L) ?: null),
            "sourceWindowB_ms" to (sourceWindow?.loopEndUs?.div(1000L) ?: null),
            // true = 当前处于循环区外自由试听(循环窗口不生效,位置=文件时间)。
            "outsideLoop" to _outsideLoop.value,
            "outputFrames" to AudioOutputFrames.totalFrames(controller),
            "sourceFirstBlockReady" to _sourceFirstBlockReady.value,
            "seekRequests" to seekRequests,
            "lastSeekRequestedMs" to lastSeekRequestedMs,
            "lastSeekRequestAtMs" to lastSeekRequestAtMs,
            "lastSeekAppliedAtMs" to lastSeekAppliedAtMs,
            "lastSeekPlayerTargetMs" to lastSeekPlayerTargetMs,
            "lastSeekApplyLatencyMs" to if (lastSeekAppliedAtMs > 0L) {
                (lastSeekAppliedAtMs - lastSeekRequestAtMs).coerceAtLeast(0L)
            } else {
                null
            },
            // 00023 后续：最近一次 seek 的逐段耗时（apply/extractor/firstBuffer/sinkPlay/ready/playout）。
            "seekStages" to LoopPreviewDebugBridge.seekStages.snapshot(),
            "error" to _error.value,
        )
        lastProbeSnapshot = out
        return out
    }

    /** 非主线程读快照：post 到主线程并等回填；超时退回上一份（可能 stale，看 atMs）。 */
    fun debugSnapshotOnMain(timeoutMs: Long): Map<String, Any?>? {
        val latch = CountDownLatch(1)
        var result: Map<String, Any?>? = null
        val posted = handler.post {
            result = runCatching { debugSnapshot() }.getOrNull()
            latch.countDown()
        }
        if (!posted) return lastProbeSnapshot
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return result ?: lastProbeSnapshot
    }

    private fun playbackStateName(state: Int?): String? = when (state) {
        null -> null
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"
        Player.STATE_ENDED -> "ENDED"
        else -> "STATE_$state"
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
     * - 其余时候读 ExoPlayer 位置(无环回裁剪;CUE clip 下是轨内位置,
     *   已由 ClippingMediaSource 钳在 [0, clipLen],默认位置 0 = clip 起点)。
     */
    private fun updatePositionOnce() {
        val p = player ?: return
        val w = window
        if (_outsideLoop.value) {
            // 自由试听:位置 = 真实媒体时间(整曲源的 T 与媒体时间一一对应;M3′ 直通同理),
            // 不折返、不读 sink 重放位置 —— 进度条/播放头必须如实反映用户拖到的位置。
            val raw = p.currentPosition.coerceAtLeast(0L)
            val duration = _durationMs.value
            _positionMs.value = if (duration > 0L) raw.coerceAtMost(duration) else raw
            _isPlaying.value = p.isPlaying
            _isReplaying.value = false
            _ringReady.value = false
            return
        }
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
            Log.d(
                TAG,
                "onPlaybackStateChanged before=" + lastPlaybackState + " after=" + playbackState +
                    " path=" + (song?.filePath) + " mediaId=" + (player?.currentMediaItem?.mediaId) +
                    " pos=" + (player?.currentPosition) + " durationMs=" + _durationMs.value +
                    " mediaItemCount=" + (player?.mediaItemCount ?: -1),
            )
            lastPlaybackState = playbackState
            _buffering.value = playbackState == Player.STATE_BUFFERING
            _isPlaying.value = player?.isPlaying == true
            if (sourceActive && engineKind == EngineKind.SOURCE) {
                Log.d(TAG, "source state=" + playbackState + " playing=" + (player?.isPlaying == true))
                if (playbackState == Player.STATE_READY) {
                    if (!_sourceFirstBlockReady.value) {
                        Log.d(TAG, "source first block ready")
                    }
                    _sourceFirstBlockReady.value = true
                    // 00023 后续：seek 后回到 READY 的时刻（LoadControl 是否卡住一眼可见）。
                    LoopPreviewDebugBridge.seekStages.noteReady()
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
