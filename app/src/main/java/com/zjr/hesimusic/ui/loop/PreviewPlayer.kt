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
import androidx.media3.exoplayer.ExoPlayer
import com.zjr.hesimusic.data.mapper.toMediaItem
import com.zjr.hesimusic.data.model.Song
import com.zjr.hesimusic.loop.LoopPosition
import com.zjr.hesimusic.loop.LoopWindow
import com.zjr.hesimusic.loop.audio.LoopAudioController
import com.zjr.hesimusic.loop.audio.LoopPcmEngine
import com.zjr.hesimusic.loop.audio.LoopRenderersFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
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

    /** 独立音频控制器:窗口/ring/位置都只属于预览,不污染主播放。 */
    private val controller = LoopAudioController()

    private var player: ExoPlayer? = null

    private val handler = Handler(Looper.getMainLooper())

    private var song: Song? = null

    /** 当前应用给 sink 的窗口;null = 直通(FREE 或非法窗口)。 */
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

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val isPrepared: Boolean
        get() = prepared && player != null

    val songId: Long?
        get() = song?.id

    // ------------------------------------------------------------------ API

    /**
     * 为某首歌建立预览(暂停态):写窗口、建 MediaItem(不裁剪,见 [buildMediaItem])、prepare。
     * 不自动播放(播放由 [play] 触发);重复调用会整体重建。
     */
    fun prepare(song: Song, aMs: Long, bMs: Long) {
        val p = ensurePlayer()
        val duration = maxOf(song.duration, bMs, 0L)
        this.song = song
        this.mode = PreviewMode.FULL
        _durationMs.value = duration
        _error.value = null

        val (aa, bb) = sanitize(aMs, bMs, duration)
        this.aMs = aa
        this.bMs = bb

        val newWindow = buildWindow(song.id, aa, bb)
        window = newWindow
        controller.fullDurationUs = duration * 1000L
        controller.window = newWindow

        p.setMediaItem(buildMediaItem(song))
        p.prepare()
        p.playWhenReady = false
        prepared = true
        updatePositionOnce()
    }

    /** 按模式起播。已 prepare 时只做窗口切换 + seek + play(MediaItem 不裁剪、不重建)。 */
    fun play(mode: PreviewMode, startMs: Long) {
        val song = this.song ?: return
        val p = ensurePlayer()
        if (!prepared) {
            prepare(song, aMs, bMs)
        }
        this.mode = mode

        if (mode == PreviewMode.FREE) {
            applyFreeShape()
        } else {
            applyLoopShape(song)
        }

        val duration = _durationMs.value.coerceAtLeast(0L)
        degradeRetryUsed = false
        seekRaw(startMs.coerceIn(0L, duration))
        p.play()
        updatePositionOnce()
        Log.d(
            TAG,
            "play mode=" + mode + " startMs=" + startMs + " window=" + window +
                " durationMs=" + duration,
        )
    }

    /** 暂停(保留位置与已捕获的 ring;resume 走 [play] 从展示位置起播)。 */
    fun pause() {
        player?.pause()
        _isPlaying.value = false
    }

    /** 按展示口径 seek(内部经 LoopPosition.rawSeekUs 映射到引擎原始位置)。 */
    fun seekToDisplayMs(ms: Long) {
        if (!prepared) return
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

        val p = player ?: return
        val newWindow = buildWindow(song.id, aa, bb)
        window = newWindow
        controller.window = newWindow

        val wasPlaying = p.isPlaying
        degradeRetryUsed = false
        seekRaw(modeStartMs())
        if (wasPlaying) p.play() else p.playWhenReady = false
        updatePositionOnce()
    }

    /** 释放预览播放器与音频控制器(退出页面/确认保存时调用,幂等)。 */
    fun release() {
        handler.removeCallbacks(ticker)
        val p = player
        player = null
        if (p != null) {
            try {
                p.release()
            } catch (t: Throwable) {
                Log.w(TAG, "release failed", t)
            }
        }
        controller.window = null
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

    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()
        val p = ExoPlayer.Builder(context)
            .setRenderersFactory(LoopRenderersFactory(context, controller))
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        p.addListener(listener)
        player = p
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
        handler.post(ticker)
        Log.d(TAG, "preview player created")
        return p
    }

    private fun applyLoopShape(song: Song) {
        val w = window ?: buildWindow(song.id, aMs, bMs).also {
            window = it
        }
        window = w
        controller.window = w
    }

    private fun applyFreeShape() {
        window = null
        controller.window = null
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

    private fun buildWindow(songId: Long, aMs: Long, bMs: Long): LoopWindow? {
        if (songId <= 0L || bMs - aMs < MIN_LOOP_LENGTH_MS) return null
        // 引擎的 ring 上限(300s / 64MB):超限时引擎会降级直通,这里同步不启用窗口,
        // 预览按整曲播放而不是在 B 处停住。
        if (bMs - aMs > LoopPcmEngine.MAX_LOOP_DURATION_US / 1000L) return null
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
        PreviewMode.SEAM -> (bMs - SEAM_LEAD_MS).coerceAtLeast(0L)
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
            currentRawUs = rawNowUs(),
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
                        " dur=" + p?.duration +
                        " playing=" + (p?.isPlaying == true) +
                        " state=" + p?.playbackState +
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
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "preview playback error: " + error.errorCodeName, error)
            _error.value = "预览播放失败:" + error.errorCodeName
        }
    }
}
