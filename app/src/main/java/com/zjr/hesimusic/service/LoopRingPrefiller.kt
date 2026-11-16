package com.zjr.hesimusic.service

import android.util.Log
import com.zjr.hesimusic.loop.LoopWindow
import com.zjr.hesimusic.loop.analysis.Pcm16Segment
import com.zjr.hesimusic.loop.analysis.RingChunkedSeeder
import com.zjr.hesimusic.loop.audio.LoopAudioController
import com.zjr.hesimusic.loop.audio.LoopPcmEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 服务层环缓冲预填：窗口应用后**分片**解码 [A,B) 并逐片追加进 ring（issue 00010）。
 *
 * 与旧版“一次解完整个窗口”的区别：
 * - 第一片（默认 2s）解码完成立即 `seedRingRange` 追加，音频层门槛满足后即可环回；
 * - 后续片按时间顺序在后台继续追加，对播放完全透明（不 seek、不刷新、不碰 Player）；
 * - 解码速度跟不上时音频层自动回退到捕获/降级，本类只负责“尽量多、按顺序”地补。
 *
 * 纪律：
 * - **非阻塞**：路径查询/解码/追加都在 [dispatcher]（默认 IO）上；
 * - **取消**：窗口变化/清空（窗口带 songId，等于切歌）立即取消进行中的分片；
 * - **幂等**：同一窗口只跑一遍；ring 已完整时跳过；
 * - **不轰炸**：解码/追加失败停止并记录，同一窗口不自动重试（窗口再变才会重新开始）。
 *
 * 可注入 [decode]/[resolvePath] 便于纯 JVM 单测（生产由 [LoopPlaybackCoordinator] 接线）。
 */
class LoopRingPrefiller(
    private val audio: LoopAudioController,
    private val scope: CoroutineScope,
    private val decode: suspend (path: String, startUs: Long, endUs: Long) -> Pcm16Segment,
    private val resolvePath: suspend (songId: Long) -> String?,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val chunkMs: Int = RingChunkedSeeder.DEFAULT_CHUNK_MS,
) {

    private val lock = Any()

    /** 进行中的预填任务（窗口变化时取消）。 */
    private var job: Job? = null

    /** 最近一次收到窗口（同一窗口只尝试一次 + 比较并设置）。 */
    private var activeWindow: LoopWindow? = null

    /**
     * 窗口应用后调用（幂等、非阻塞）。
     *
     * - 同一窗口重复调用：直接返回（进行中或已跑过，不重试轰炸）；
     * - [window] 为 null/非法：取消进行中的分片并清空；
     * - ring 已完整：跳过后台解码。
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
        val startedNanos = System.nanoTime()
        val path = try {
            resolvePath(window.songId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logW("prefill path resolve failed songId=" + window.songId, e)
            null
        }
        if (path == null) {
            logW("prefill skipped: no file path songId=" + window.songId)
            return
        }

        var firstChunkMs = -1L
        val result = try {
            RingChunkedSeeder(chunkMs).run(
                window = window,
                // 播放捕获可能已经覆盖了一段连续前缀：从它之后继续追加，不重复解码/写入。
                startFrame = audio.coveredFrames,
                decode = { startUs, endUs -> decode(path, startUs, endUs) },
                seed = { startFrame, segment ->
                    val installed = seedChunk(window, startFrame, segment)
                    if (installed && firstChunkMs < 0L) {
                        firstChunkMs = (System.nanoTime() - startedNanos) / 1_000_000L
                        logD(
                            "prefill first chunk songId=" + window.songId +
                                " frames=" + segment.frames + " cost=" + firstChunkMs + "ms",
                        )
                    }
                    installed
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logW("prefill crashed songId=" + window.songId + " window=" + window, e)
            return
        }

        val message = "prefill done complete=" + result.complete +
            " chunks=" + result.chunks + " frames=" + result.appendedFrames +
            " firstChunk=" + firstChunkMs + "ms stop=" + result.stopReason +
            " songId=" + window.songId + " window=" + window
        if (result.stopReason == null) logD(message) else logW(message)
    }

    /**
     * 追加一片（[startFrame] = 环内帧号，0 = A）。
     *
     * 首片可能赶上 sink 尚未 configure：音频层会返回非 INSTALLED，这里短暂重试；
     * 超过上限仍失败就停（播放侧本来就有捕获/降级兜底，绝不阻塞播放）。
     */
    private suspend fun seedChunk(
        window: LoopWindow,
        startFrame: Long,
        segment: Pcm16Segment,
    ): Boolean {
        var attempt = 0
        while (true) {
            // 比较并设置：解码期间窗口已变 → 丢弃这一片。
            if (audio.window != window) return false
            val result = audio.seedRingRange(
                window,
                startFrame,
                segment.data,
                segment.sampleRate,
                segment.channels,
            )
            if (result.isInstalled) return true
            if (result.status != LoopPcmEngine.SeedStatus.PENDING) {
                // 窗口/格式/边界等硬拒绝：重试也不会成功，停止追加（播放侧有捕获/降级兜底）。
                logW(
                    "seedRingRange rejected status=" + result.status + " startFrame=" + startFrame +
                        " frames=" + segment.frames + " window=" + window +
                        " detail=" + result.detail,
                )
                return false
            }
            // sink 尚未 configure（PENDING）：短暂等待后重试同一片。
            attempt += 1
            if (attempt > SEED_RETRY_LIMIT) {
                logW(
                    "seedRingRange still pending startFrame=" + startFrame +
                        " frames=" + segment.frames + " window=" + window,
                )
                return false
            }
            delay(SEED_RETRY_DELAY_MS)
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

        /** 分片追加被拒时的重试上限（首片等 sink configure，约 1s）。 */
        const val SEED_RETRY_LIMIT = 10

        /** 追加重试间隔。 */
        const val SEED_RETRY_DELAY_MS = 100L
    }
}
