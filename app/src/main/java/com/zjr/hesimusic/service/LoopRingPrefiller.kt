package com.zjr.hesimusic.service

import android.util.Log
import com.zjr.hesimusic.loop.LoopWindow
import com.zjr.hesimusic.loop.analysis.Pcm16Frames
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 服务层环缓冲预填：窗口应用后在后台按时间顺序**分片**解码并逐片追加进 ring。
 *
 * issue 00015 起的行为（“只填一小段前缀就先起播”）：
 * - **长窗口**（长度 > [fullFillMaxMs]）只预填 `[A, A + maxPrefixMs)`（默认 12s）就报完成：
 *   进度状态 `complete=false, prefixOnly=true`，页面/服务可立即起播；
 *   播到覆盖尽头时由音频层「覆盖耗尽 → seek 回 A+cursor 继续捕获」保证听感连续；
 * - **短窗口**（≤ [fullFillMaxMs]，默认 30s）保持旧行为：直接填满整环（`complete=true`）；
 * - 可选 [fillRemainingInBackground]：前缀完成且进度可见后，同一后台任务继续填满整环，
 *   绝不阻塞起播（默认关闭，交给音频层捕获续播）。
 *
 * 进度：通过 [progress]（StateFlow）暴露 `coveredFrames / totalFrames`（还有 fraction、
 * prefixOnly、complete、chunks），页面可显示「准备环缓冲 8s/12s」这类轻量进度。
 *
 * 纪律：
 * - **非阻塞**：路径查询/解码/追加都在 [dispatcher]（默认 IO）上；
 * - **取消**：窗口变化/清空（窗口带 songId，等于切歌）立即取消进行中的分片并复位进度；
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
    /** 前缀上限（帧）。0 = 自动：长窗口取 [maxPrefixMs]，短窗口直接填满。 */
    private val maxPrefixFrames: Long = 0L,
    /** 自动前缀上限时长（默认 12s）；仅长窗口生效。 */
    private val maxPrefixMs: Int = DEFAULT_PREFIX_MS,
    /** 窗口长度 ≤ 此值直接填满（保持旧行为，默认 30s）。 */
    private val fullFillMaxMs: Int = DEFAULT_FULL_FILL_MAX_MS,
    /** 前缀完成后是否继续后台填满整环（默认 false：交给音频层捕获续播）。 */
    private val fillRemainingInBackground: Boolean = false,
) {

    /**
     * 预填进度快照（[progress] 的发射值）。
     *
     * @param coveredFrames 音频层已连续覆盖的前缀帧数
     * @param totalFrames 本次预填目标帧数（长窗口 = 前缀上限帧数；短窗口 = 整环帧数）
     * @param prefixOnly 目标是否为“只填前缀”（true 时到 total 即可起播）
     * @param complete 是否已覆盖整环（prefixOnly=true 时恒为 false）
     * @param chunks 已追加成功的片数
     * @param prefixReady 前缀是否已填够（“可以起播”的标记；即使随后继续填满也保持 true）
     */
    data class Progress(
        val coveredFrames: Long = 0L,
        val totalFrames: Long = 0L,
        val prefixOnly: Boolean = false,
        val complete: Boolean = false,
        val chunks: Int = 0,
        val prefixReady: Boolean = false,
        val sampleRate: Int = 0,
    ) {
        /** 进度比例 ∈ [0,1]（total 未知时为 0）。 */
        val fraction: Float
            get() = if (totalFrames > 0L) {
                (coveredFrames.toFloat() / totalFrames.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }

        /** 目标已达成（整环完成，或前缀已达上限）——即可起播/进度不再增长。 */
        val targetReached: Boolean
            get() = complete || (totalFrames > 0L && coveredFrames >= totalFrames)

        /** 已覆盖时长(ms)；[sampleRate] 未知时为 0。UI 可显示「8s/12s」。 */
        val coveredMs: Long
            get() = if (sampleRate > 0) coveredFrames * 1_000L / sampleRate else 0L

        /** 目标时长(ms)；[sampleRate] 未知时为 0。 */
        val totalMs: Long
            get() = if (sampleRate > 0) totalFrames * 1_000L / sampleRate else 0L
    }

    private val _progress = MutableStateFlow(Progress())

    /** 预填进度（任意线程可读）。 */
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    private val lock = Any()

    /** 进行中的预填任务（窗口变化时取消）。 */
    private var job: Job? = null

    /** 最近一次收到窗口（同一窗口只尝试一次 + 比较并设置）。 */
    private var activeWindow: LoopWindow? = null

    /** 采样率缓存（同一首歌解码器输出格式稳定；显式帧上限换算需要）。 */
    @Volatile
    private var cachedSongId: Long = 0L

    @Volatile
    private var cachedSampleRate: Int = 0

    /**
     * 窗口应用后调用（幂等、非阻塞）。
     *
     * - 同一窗口重复调用：直接返回（进行中或已跑过，不重试轰炸）；
     * - [window] 为 null/非法：取消进行中的分片并复位进度；
     * - ring 已完整：跳过解码，进度直接标记 complete。
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
        _progress.value = Progress()
        if (window == null || !window.isValid()) return
        if (audio.isRingComplete) {
            val covered = audio.coveredFrames
            _progress.value = Progress(
                coveredFrames = covered,
                totalFrames = covered,
                prefixOnly = false,
                complete = true,
                chunks = 0,
                prefixReady = true,
                sampleRate = if (cachedSongId == window.songId) cachedSampleRate else 0,
            )
            return
        }
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

    /** 服务销毁/退出无缝时取消进行中的预填并复位进度（不取消外部 scope）。 */
    fun cancel() {
        val old = synchronized(lock) {
            activeWindow = null
            val current = job
            job = null
            current
        }
        old?.cancel()
        _progress.value = Progress()
    }

    /** 单测用：是否有进行中的预填。 */
    internal val hasActiveJobForTest: Boolean
        get() = synchronized(lock) { job?.isActive == true }

    // ------------------------------------------------------------------ 实现

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

        val aUs = window.loopStartUs
        val bUs = window.loopEndUs
        val fullFill = window.lengthUs <= fullFillMaxMs.coerceAtLeast(0) * 1_000L

        // 目标区间：[A, prefixEndUs)。显式帧上限优先；否则长窗口只填前缀、短窗口填满。
        var prefixEndUs = bUs
        if (maxPrefixFrames > 0L) {
            // 显式帧上限：需要采样率换算（缓存过就直接用，否则先探测一小片）。
            val sr = sampleRateFor(window, path)
            if (sr == null || sr <= 0) {
                logW("prefill skipped: cannot resolve sampleRate songId=" + window.songId)
                return
            }
            val aFrame = Pcm16Frames.frameAtOrAfterUs(aUs, sr)
            val bFrame = Pcm16Frames.frameAtOrAfterUs(bUs, sr)
            val ringFrames = bFrame - aFrame
            if (ringFrames <= 0L) return
            prefixEndUs = if (maxPrefixFrames >= ringFrames) {
                bUs
            } else {
                frameUs(aFrame + maxPrefixFrames, sr)
            }
        } else if (!fullFill) {
            prefixEndUs = minOf(bUs, aUs + maxPrefixMs.coerceAtLeast(1) * 1_000L)
        }
        val prefixOnly = prefixEndUs < bUs

        _progress.value = Progress(
            coveredFrames = audio.coveredFrames,
            totalFrames = 0L,
            prefixOnly = prefixOnly,
            complete = false,
            chunks = 0,
            sampleRate = if (cachedSongId == window.songId) cachedSampleRate else 0,
        )

        val prefixRun = runSeeder(window, path, prefixEndUs, prefixOnly, false, startedNanos) ?: return
        val prefixDone = prefixRun.result.complete
        var complete = prefixDone && !prefixOnly
        emitFinal(window, prefixRun, prefixOnly, complete, prefixReady = prefixOnly && prefixDone)
        logRun("prefill", window, prefixRun, prefixOnly, complete, startedNanos)

        if (prefixOnly && prefixDone && fillRemainingInBackground) {
            // 可选：前缀已就绪后继续后台填满整环（不阻塞起播，失败只记录）。
            val fullRun = runSeeder(window, path, bUs, false, true, startedNanos) ?: return
            complete = fullRun.result.complete
            emitFinal(window, fullRun, false, complete, prefixReady = true)
            logRun("prefill(continue)", window, fullRun, false, complete, startedNanos)
        }
    }

    /** 一次分片预填的结果（内部）。 */
    private class SeedRun(
        val result: RingChunkedSeeder.Result,
        val targetFrames: Long,
        val sampleRate: Int,
    )

    /**
     * 对 [endUs] 之前的区间跑一遍分片追加。
     *
     * @param prefixOnly 本次目标是“前缀上限”而非整环（只用于进度语义）
     */
    private suspend fun runSeeder(
        window: LoopWindow,
        path: String,
        endUs: Long,
        prefixOnly: Boolean,
        prefixReady: Boolean,
        startedNanos: Long,
    ): SeedRun? {
        val startFrame = audio.coveredFrames
        var targetFrames = 0L
        var sampleRate = 0
        var seededChunks = 0
        val clipped = LoopWindow(window.songId, window.loopStartUs, endUs)
        val result = try {
            RingChunkedSeeder(chunkMs).run(
                window = clipped,
                startFrame = startFrame,
                decode = { startUs, segEndUs ->
                    val segment = decode(path, startUs, segEndUs)
                    if (segment.sampleRate > 0) {
                        sampleRate = segment.sampleRate
                        rememberSampleRate(window.songId, segment.sampleRate)
                        if (targetFrames <= 0L) {
                            targetFrames = targetFramesFor(window.loopStartUs, endUs, segment.sampleRate)
                        }
                    }
                    segment
                },
                seed = { startFrameOffset, segment ->
                    val installed = seedChunk(window, startFrameOffset, segment)
                    if (installed) {
                        seededChunks += 1
                        if (targetFrames <= 0L) {
                            targetFrames = targetFramesFor(
                                window.loopStartUs,
                                endUs,
                                segment.sampleRate,
                            )
                        }
                        emitProgress(
                            coveredFrames = audio.coveredFrames,
                            totalFrames = targetFrames,
                            prefixOnly = prefixOnly,
                            complete = false,
                            chunks = seededChunks,
                            prefixReady = prefixReady,
                            sampleRate = sampleRate,
                        )
                    }
                    installed
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logW("prefill crashed songId=" + window.songId + " window=" + window, e)
            return null
        }
        return SeedRun(result, targetFrames, sampleRate)
    }

    private fun emitProgress(
        coveredFrames: Long,
        totalFrames: Long,
        prefixOnly: Boolean,
        complete: Boolean,
        chunks: Int,
        prefixReady: Boolean,
        sampleRate: Int,
    ) {
        val target = if (totalFrames > 0L) maxOf(totalFrames, coveredFrames) else 0L
        _progress.value = Progress(
            coveredFrames = coveredFrames,
            totalFrames = target,
            prefixOnly = prefixOnly && !complete,
            complete = complete,
            chunks = chunks,
            prefixReady = prefixReady,
            sampleRate = sampleRate,
        )
    }

    private fun emitFinal(
        window: LoopWindow,
        run: SeedRun,
        prefixOnly: Boolean,
        complete: Boolean,
        prefixReady: Boolean,
    ) {
        val covered = audio.coveredFrames
        val target = if (run.targetFrames > 0L) run.targetFrames else covered
        _progress.value = Progress(
            coveredFrames = covered,
            totalFrames = maxOf(target, covered),
            prefixOnly = prefixOnly && !complete,
            complete = complete,
            chunks = run.result.chunks,
            prefixReady = prefixReady,
            sampleRate = run.sampleRate,
        )
    }

    private fun targetFramesFor(aUs: Long, endUs: Long, sampleRate: Int): Long {
        if (sampleRate <= 0) return 0L
        val target = Pcm16Frames.frameAtOrAfterUs(endUs, sampleRate) -
            Pcm16Frames.frameAtOrAfterUs(aUs, sampleRate)
        return target.coerceAtLeast(0L)
    }

    /** 采样率：优先用缓存（同一首歌），否则解码一小片探测。 */
    private suspend fun sampleRateFor(window: LoopWindow, path: String): Int? {
        if (cachedSongId == window.songId && cachedSampleRate > 0) return cachedSampleRate
        val probeEndUs = minOf(
            window.loopEndUs,
            window.loopStartUs + chunkMs.coerceAtLeast(1) * 1_000L,
        )
        val segment = try {
            decode(path, window.loopStartUs, probeEndUs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logW("prefill probe decode failed songId=" + window.songId, e)
            null
        } ?: return null
        if (segment.sampleRate <= 0) return null
        rememberSampleRate(window.songId, segment.sampleRate)
        return segment.sampleRate
    }

    private fun rememberSampleRate(songId: Long, sampleRate: Int) {
        if (sampleRate <= 0) return
        cachedSongId = songId
        cachedSampleRate = sampleRate
    }

    /**
     * 追加一片（[startFrame] = 环内帧号，0 = A）。
     *
     * 首片可能赶上 sink 尚未 configure：音频层返回 PENDING 时短暂重试；
     * 其它硬拒绝（窗口/格式/边界/GAP）立即停止（播放侧有捕获/降级兜底）。
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
                logW(
                    "seedRingRange rejected status=" + result.status + " startFrame=" + startFrame +
                        " frames=" + segment.frames + " window=" + window +
                        " detail=" + result.detail,
                )
                return false
            }
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

    private fun logRun(
        tag: String,
        window: LoopWindow,
        run: SeedRun,
        prefixOnly: Boolean,
        complete: Boolean,
        startedNanos: Long,
    ) {
        val costMs = (System.nanoTime() - startedNanos) / 1_000_000L
        val message = tag + " complete=" + complete + " prefixOnly=" + prefixOnly +
            " chunks=" + run.result.chunks + " frames=" + audio.coveredFrames +
            "/" + run.targetFrames + " stop=" + run.result.stopReason +
            " cost=" + costMs + "ms songId=" + window.songId + " window=" + window
        if (run.result.stopReason == null) logD(message) else logW(message)
    }

    /** 环内帧号 -> 媒体时间(µs)：floor(frame*1e6/sr)。 */
    private fun frameUs(frame: Long, sampleRate: Int): Long =
        if (frame <= 0L || sampleRate <= 0) 0L else frame * 1_000_000L / sampleRate

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

        /** 默认前缀上限时长：12s（长窗口只填这么多即可起播）。 */
        const val DEFAULT_PREFIX_MS = 12_000

        /** 窗口 ≤ 此长度直接填满（保持旧行为）。 */
        const val DEFAULT_FULL_FILL_MAX_MS = 30_000

        /** 分片追加被拒时的重试上限（首片等 sink configure，约 1s）。 */
        const val SEED_RETRY_LIMIT = 10

        /** 追加重试间隔。 */
        const val SEED_RETRY_DELAY_MS = 100L
    }
}
