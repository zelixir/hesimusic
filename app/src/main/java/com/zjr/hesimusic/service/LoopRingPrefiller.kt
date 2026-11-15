package com.zjr.hesimusic.service

import android.util.Log
import com.zjr.hesimusic.loop.LoopWindow
import com.zjr.hesimusic.loop.analysis.Pcm16Segment
import com.zjr.hesimusic.loop.audio.LoopAudioController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 服务层环缓冲预填：窗口应用后在后台把 [LoopWindow] 对应的 [A,B) PCM 解码出来灌进 ring。
 *
 * 目的（与试听页同一机制）：正式播放路径不再需要“先完整经过一轮 [A,B)”才有环数据——
 * - 全新会话从 0 播放已确认歌：到 B 时 ring 已完整，不会触发一次预卷 seek + 从 A 重播的跳变；
 * - ring 未完整时把进度条拖到 [A,B) 内部会落回 A：预填完成后可直接落点。
 *
 * 纪律：
 * - **非阻塞**：解码/路径查询都在 [dispatcher]（默认 IO）上，调用方（主线程）只做一次启动；
 * - **透明**：预填完成后不 seek、不刷新播放、不碰 Player；下一次经过 B 自然无缝；
 * - **取消**：窗口变化/清空（含切歌，窗口带 songId）立即取消进行中的预填；
 * - **幂等**：同一窗口只尝试一次；ring 已完整或已有 pending seed 时直接跳过；
 * - **不轰炸**：解码/落盘失败只打日志，同一窗口不自动重试（窗口再次变化才会重新尝试）。
 *
 * 可注入 [decode]/[resolvePath] 便于纯 JVM 单测（生产由 [LoopPlaybackCoordinator] 接线）。
 */
class LoopRingPrefiller(
    private val audio: LoopAudioController,
    private val scope: CoroutineScope,
    private val decode: suspend (path: String, startUs: Long, endUs: Long) -> Pcm16Segment,
    private val resolvePath: suspend (songId: Long) -> String?,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val lock = Any()

    /** 进行中的预填任务（窗口变化时取消）。 */
    private var job: Job? = null

    /** 最近一次收到窗口（用于同一窗口只尝试一次 + 窗口比较并设置）。 */
    private var activeWindow: LoopWindow? = null

    /**
     * 窗口应用后调用（幂等、非阻塞）。
     *
     * - 同一窗口重复调用：直接返回（进行中或已尝试过，不重试轰炸）；
     * - [window] 为 null/非法：取消进行中的任务并清空；
     * - ring 已完整或已有 pending seed：跳过后台解码。
     */
    fun onWindowApplied(window: LoopWindow?) {
        val previous = synchronized(lock) {
            if (window == activeWindow) return
            activeWindow = window
            val old = job
            job = null
            old
        }
        previous?.cancel()
        if (window == null || !window.isValid()) return
        if (audio.isRingComplete) return
        if (audio.hasPendingSeed) return
        val newJob = scope.launch(dispatcher) { prefill(window) }
        synchronized(lock) {
            if (activeWindow == window) {
                job = newJob
            } else {
                // 启动期间窗口又变了：这次结果作废。
                newJob.cancel()
            }
        }
    }

    /** 服务销毁/退出无缝时取消进行中的预填（不取消外部 scope）。 */
    fun cancel() {
        val old = synchronized(lock) {
            activeWindow = null
            val current = job
            job = null
            current
        }
        old?.cancel()
    }

    /** 单测用：是否有进行中的预填。 */
    internal val hasActiveJobForTest: Boolean
        get() = synchronized(lock) { job?.isActive == true }

    private suspend fun prefill(window: LoopWindow) {
        val startNanos = System.nanoTime()
        try {
            val path = resolvePath(window.songId)
            if (path == null) {
                logW("prefill skipped: no file path songId=" + window.songId)
                return
            }
            val segment = decode(path, window.loopStartUs, window.loopEndUs)
            // 比较并设置：解码期间窗口已变 → 丢弃这次结果（不污染新窗口的 ring）。
            if (audio.window != window) {
                logD("prefill discarded: window changed songId=" + window.songId + " window=" + window)
                return
            }
            val result = audio.seedRing(window, segment.data, segment.sampleRate, segment.channels)
            val costMs = (System.nanoTime() - startNanos) / 1_000_000L
            val message = "prefill " + result.status + " songId=" + window.songId +
                " window=" + window + " frames=" + result.frames +
                " detail=" + result.detail + " cost=" + costMs + "ms"
            if (result.isInstalled) logD(message) else logW(message)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logW("prefill failed songId=" + window.songId + " window=" + window, e)
        }
    }

    /** JVM 单测里 android.util.Log 是 stub，统一吞掉以保证纯逻辑可测。 */
    private fun logD(message: String) {
        try {
            Log.d(TAG, message)
        } catch (_: Throwable) {
        }
    }

    private fun logW(message: String, throwable: Throwable? = null) {
        try {
            if (throwable != null) Log.w(TAG, message, throwable) else Log.w(TAG, message)
        } catch (_: Throwable) {
        }
    }

    private companion object {
        const val TAG = "LoopRingPrefill"
    }
}
