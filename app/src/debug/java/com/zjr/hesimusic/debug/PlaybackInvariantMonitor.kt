package com.zjr.hesimusic.debug

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.zjr.hesimusic.core.invariants.AudioOutputFrames
import com.zjr.hesimusic.core.invariants.InvariantEvent
import com.zjr.hesimusic.core.invariants.InvariantRegistry
import com.zjr.hesimusic.core.invariants.OutputInvariantEvaluator
import com.zjr.hesimusic.core.invariants.OutputInvariantSample
import com.zjr.hesimusic.core.invariants.SourceInvariantEvaluator
import com.zjr.hesimusic.core.invariants.SourceInvariantSample
import com.zjr.hesimusic.data.mapper.mediaIdToSongId
import com.zjr.hesimusic.loop.audio.source.LoopAwareMediaSourceFactory
import com.zjr.hesimusic.loop.audio.source.LoopSourceItemTag
import com.zjr.hesimusic.loop.audio.source.LoopSourceMainPlayer
import com.zjr.hesimusic.service.LoopSourceFallback
import com.zjr.hesimusic.service.LoopSourceRuntime

/**
 * 运行期不变量采样器（仅 debug 源集；release 不编译，天然关闭）。
 *
 * 每 500ms 在主线程采一次「事实」，交给 core/invariants 的纯逻辑判定，命中就
 * Log.e + 写入 [InvariantRegistry]（GET /state 的 invariants 数组）。
 *
 * 为什么放 debug：它要直接读主播放器 ExoPlayer 的 currentMediaItem tag（MediaController
 * 跨会话拿不到 localConfiguration.tag），这是 debug 才允许的越层观测；且采样本身有
 * 500ms 的固定开销，不应进 release。
 *
 * 两个不变量对应的真机复现/验证方式见 docs 与提交说明：
 * - paused-but-sink-wrote：暂停主播放后 sink 仍写帧（00020 的“0.x 播 + 0.x 停”）；
 * - projection-loop-source-plain：会话按循环投影但 MediaSource 还是普通文件源（00021）。
 */
internal object PlaybackInvariantMonitor {

    private const val TAG = "LoopInvariant"

    /** 采样周期：任务约定 500ms。 */
    private const val SAMPLE_INTERVAL_MS = 500L

    /**
     * 音源不一致需要连续 2 次采样（≈1s）确认才上报：
     * 进/退循环的那一次主线程调用内部会同时改 item、建源，采样不会插进中间，
     * 但队列重建/换歌等路径可能有短瞬态，1s 确认可避免假警报。
     */
    private const val SOURCE_CONFIRM_SAMPLES = 2

    /** 采样明细日志间隔（logcat -s LoopInvariant）。 */
    private const val SAMPLE_LOG_INTERVAL_MS = 5_000L

    /** 故障注入：暂停仍写（每采样 +96ms@48kHz，模拟 00020 的 96ms 出声）。 */
    const val FAULT_PAUSED_WRITE = "paused-write"

    /** 故障注入：播放但 sink 静音（连续零帧，模拟 playing-but-sink-silent）。 */
    const val FAULT_SILENT = "silent"

    /** 故障注入：循环投影但 item 没有 loop tag / 工厂选的是普通源（模拟 00021）。 */
    const val FAULT_SOURCE_PLAIN = "source-plain"

    /** 故障注入：非循环投影但 item 仍带 loop tag（反向）。 */
    const val FAULT_SOURCE_LOOP = "source-loop"

    private val registry = InvariantRegistry()
    private val outputEvaluator = OutputInvariantEvaluator()
    private val sourceEvaluator = SourceInvariantEvaluator()
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var entry: DebugEntryPoint? = null

    @Volatile
    private var connection: DebugPlayerConnection? = null

    @Volatile
    private var started = false

    @Volatile
    private var lastSample: Map<String, Any?>? = null

    /** 非 null 时用合成采样打真实判定器（真机验证用，见 POST /debug/invariant）。 */
    @Volatile
    private var faultInjection: String? = null

    private var faultFrames = 0L
    private var sourcePendingSamples = 0
    private var lastSampleLogMs = 0L

    /** 由 LoopDebugServer 在 Hilt 依赖就绪后调用一次（幂等）。 */
    @Synchronized
    fun start(d: DebugEntryPoint, playerConnection: DebugPlayerConnection) {
        if (started) return
        started = true
        entry = d
        connection = playerConnection
        handler.postDelayed(sampler, SAMPLE_INTERVAL_MS)
        Log.i(TAG, "invariant monitor started intervalMs=" + SAMPLE_INTERVAL_MS)
    }

    /** GET /state.invariants：[{code,message,atMs,count}]，最近 10 条。 */
    fun snapshotMaps(): List<Map<String, Any?>> = try {
        registry.snapshot().map { it.toMap() }
    } catch (t: Throwable) {
        Log.w(TAG, "invariant snapshot failed", t)
        emptyList()
    }

    /** GET /state.invariantProbe：最近一次采样明细（排障用；未采样为 null）。 */
    fun lastSampleMap(): Map<String, Any?>? = lastSample

    /**
     * 故障注入（仅 debug）：用合成采样喂**真实**判定器，验证
     * 「采样 → 判定 → Log.e → /state.invariants」整条链路在真机可用。
     *
     * kind = null/"off" 关闭；"clear" 只清空历史。只在主线程改判定器状态。
     */
    fun setFaultInjection(kind: String?) {
        handler.post {
            val raw = kind?.trim()?.lowercase()
            val normalized = when (raw) {
                null, "", "off", "none" -> null
                FAULT_PAUSED_WRITE, FAULT_SILENT, FAULT_SOURCE_PLAIN, FAULT_SOURCE_LOOP -> raw
                "clear" -> {
                    registry.clear()
                    null
                }
                else -> null
            }
            faultInjection = normalized
            faultFrames = 0L
            outputEvaluator.reset()
            sourcePendingSamples = 0
            Log.w(TAG, "fault injection -> " + (normalized ?: "off"))
        }
    }

    private val sampler = object : Runnable {
        override fun run() {
            try {
                sampleOnce()
            } catch (t: Throwable) {
                // 采样失败绝不影响播放：只记日志，下一轮继续。
                Log.w(TAG, "invariant sample failed", t)
            }
            handler.postDelayed(this, SAMPLE_INTERVAL_MS)
        }
    }

    private fun sampleOnce() {
        val d = entry ?: return
        val conn = connection ?: return
        // 只在主播放器已真正连上（MusicService 已起、ExoPlayer 已创建）时采样，
        // 避免 debug 请求反向“提前创建”播放器。
        if (!conn.isConnected()) return
        val player: ExoPlayer = d.exoPlayer()
        val now = System.currentTimeMillis()
        val controller = d.loopAudioController()
        val frames = AudioOutputFrames.totalFrames(controller)
        val playbackState = player.playbackState
        // 主播放器是否走 SOURCE（TapAudioSink）路线：帧计数只覆盖这条路线；
        // M3′ 路线由 LoopAudioSink 出声，目前没有同一来源的帧计数，不参与判定（避免误报）。
        val routeSource = LoopSourceMainPlayer.isMainPlayerActive

        val fault = faultInjection
        val outputSample = when (fault) {
            FAULT_PAUSED_WRITE -> {
                // 每个采样都“暂停但仍写 96ms 帧”：真实判定器应报 paused-but-sink-wrote。
                faultFrames += 96L * 48L
                OutputInvariantSample(
                    atMs = now,
                    playWhenReady = false,
                    writtenFrames = faultFrames,
                )
            }
            FAULT_SILENT -> OutputInvariantSample(
                atMs = now,
                playWhenReady = true,
                writtenFrames = faultFrames,
                buffering = false,
                playbackStateReady = true,
            )
            else -> if (routeSource) {
                OutputInvariantSample(
                    atMs = now,
                    playWhenReady = player.playWhenReady,
                    writtenFrames = frames,
                    buffering = playbackState == Player.STATE_BUFFERING,
                    playbackStateReady = playbackState == Player.STATE_READY,
                )
            } else {
                null
            }
        }
        outputSample?.let { sample -> outputEvaluator.onSample(sample)?.let { report(it) } }

        val current = player.currentMediaItem
        val mediaId = current?.mediaId.orEmpty()
        val tag = current?.localConfiguration?.tag
        val itemHasLoopTag = tag is LoopSourceItemTag
        val songId = current?.mediaId?.let { mediaIdToSongId(it) }
        val runtimeWindow = d.loopRuntime().window.value
        val installed = LoopSourceRuntime.installedWindow
        // 「页面/会话投影处于循环模式」= SOURCE 路线 + 会话窗口有效且属于当前歌 +
        // SOURCE 接管已登记同一窗口 + 没有被回退登记。M3′ 路线虽然 window 也非空，
        // 但它的普通文件源是设计如此（环回在 sink 层），不能判成“投影循环、音源普通”。
        val windowActive = routeSource &&
            runtimeWindow != null && runtimeWindow.isValid() &&
            (songId == null || runtimeWindow.songId == songId) &&
            installed != null &&
            installed.songId == runtimeWindow.songId &&
            installed.loopStartUs == runtimeWindow.loopStartUs &&
            installed.loopEndUs == runtimeWindow.loopEndUs &&
            !LoopSourceFallback.isUnavailable(songId, runtimeWindow)
        val select = if (mediaId.isEmpty()) {
            null
        } else {
            LoopAwareMediaSourceFactory.lastSelectFor(mediaId)
        }
        val selectLoop = select?.kind == LoopAwareMediaSourceFactory.SELECT_LOOP

        val sourceEvent = when (fault) {
            FAULT_SOURCE_PLAIN -> sourceEvaluator.onSample(
                SourceInvariantSample(
                    atMs = now,
                    windowActive = true,
                    itemHasLoopTag = false,
                    factoryLastSelectLoop = false,
                ),
            )
            FAULT_SOURCE_LOOP -> sourceEvaluator.onSample(
                SourceInvariantSample(
                    atMs = now,
                    windowActive = false,
                    itemHasLoopTag = true,
                    factoryLastSelectLoop = false,
                ),
            )
            else -> if (routeSource) {
                sourceEvaluator.onSample(
                    SourceInvariantSample(
                        atMs = now,
                        windowActive = windowActive,
                        itemHasLoopTag = itemHasLoopTag,
                        factoryLastSelectLoop = selectLoop,
                    ),
                )
            } else {
                null
            }
        }
        if (sourceEvent == null) {
            sourcePendingSamples = 0
        } else {
            sourcePendingSamples += 1
            if (sourcePendingSamples >= SOURCE_CONFIRM_SAMPLES) {
                sourcePendingSamples = 0
                report(sourceEvent)
            }
        }

        val sample = linkedMapOf<String, Any?>(
            "atMs" to now,
            "routeSource" to routeSource,
            "playWhenReady" to player.playWhenReady,
            "playbackState" to playbackState,
            "writtenFrames" to frames,
            "windowActive" to windowActive,
            "itemHasLoopTag" to itemHasLoopTag,
            "lastSelect" to select?.kind,
            "sourceViolationPending" to sourcePendingSamples,
            "faultInjection" to fault,
            "faultFrames" to faultFrames,
        )
        lastSample = sample
        if (now - lastSampleLogMs >= SAMPLE_LOG_INTERVAL_MS) {
            lastSampleLogMs = now
            Log.d(TAG, "sample " + sample)
        }
    }

    private fun report(event: InvariantEvent) {
        val record = registry.record(event)
        try {
            Log.e(TAG, event.message + " count=" + record.count)
        } catch (_: Throwable) {
            // JVM/异常环境下 Log 不可用也不影响记录。
        }
    }
}
