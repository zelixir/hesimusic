package com.zjr.hesimusic.loop.analysis

import android.util.Log
import com.zjr.hesimusic.data.dao.SongLoopDao
import com.zjr.hesimusic.data.model.Song
import com.zjr.hesimusic.data.model.SongLoop
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToLong

/**
 * 分析状态机(契约 docs/loop-v3-plan.md §3.1 / docs/loop-seamless-v2.md §1.2)。
 *
 * 进度口径(issue 00018):**已完成工作量 / 预计总工作量**,解码/分析按实测时间比例加权,
 * 单调 + EMA 平滑(见 [AnalysisProgress]);每 100ms 最多上报一次。
 */
sealed interface AnalysisState {
    data object Idle : AnalysisState
    data class Decoding(val progress: Float) : AnalysisState
    data class Analyzing(val progress: Float) : AnalysisState
    data class Done(
        val songId: Long,
        val candidates: List<LoopCandidateKt>,
        val sampleRate: Int,
        val peaks: WaveformPeaks,
        val durationMs: Long,
    ) : AnalysisState
    data class Failed(val message: String) : AnalysisState
}

/** 循环候选点的 Kotlin 侧模型(采样点基于源采样率)。 */
data class LoopCandidateKt(
    val startSamples: Long,
    val endSamples: Long,
    val score: Double,
    val noteDistance: Double,
    val loudnessDifference: Double,
)

/**
 * 无缝循环分析管理器(Hilt 单例)。
 *
 * 语义(契约 §3.1):
 * - [analyze] 单飞:进行中再次调用直接返回 false,不并发;
 * - 分析成功 → Done(内存候选 + 波形)并**自动落库临时结果**(confirmed=0,
 *   仅当无行或行 confirmed=0 时 upsert top1;已确认行不覆盖);同一次 upsert 还把
 *   **完整候选数组**写进 `song_loop.candidatesJson`(issue 00016),重进结果页可恢复候选列表;
 * - [confirmAndSave] → upsert confirmed=1(带内存候选列表时一并刷新 JSON,不带时保留旧 JSON);
 * - [demoteFinal] → confirmed 1→0,行保留;
 * - [deleteResult] → 删行。
 */
@Singleton
class LoopAnalysisManager @Inject constructor(
    private val pcmDecoder: PcmDecoder,
    private val songLoopDao: SongLoopDao,
) {

    private val _state = MutableStateFlow<AnalysisState>(AnalysisState.Idle)
    val state: StateFlow<AnalysisState> = _state.asStateFlow()

    /** 单飞互斥:进行中再次 analyze 直接返回 false(不排队、不并发)。 */
    private val analyzeMutex = Mutex()

    @Volatile
    private var cancelRequested = false

    /** 请求取消进行中的分析(解码阶段与引擎阶段均在进度回调处生效)。 */
    fun cancel() {
        cancelRequested = true
    }

    /**
     * 分析一首歌:解码(mono,带进度)→ 波形峰值 → JNI 引擎(带进度)→ Done + 临时结果落库。
     *
     * 进度:工作量单位化(帧/块)+ 时间加权 + 单调 EMA(见 [AnalysisProgress]);
     * 每 100ms 最多上报一次,另有 100ms 心跳在工作回调空档做极小步进,避免长时间完全静止。
     *
     * @return true = 本次调用执行了分析(无论成功/失败);false = 已有分析进行中,本次被拒绝。
     */
    suspend fun analyze(song: Song): Boolean {
        if (!analyzeMutex.tryLock()) return false
        var heartbeat: Job? = null
        try {
            cancelRequested = false
            val tracker = ProgressTracker(song.duration)
            tracker.start()
            heartbeat = CoroutineScope(coroutineContext + Dispatchers.Default).launch {
                while (isActive) {
                    delay(PROGRESS_UPDATE_INTERVAL_MS)
                    tracker.heartbeat()
                }
            }

            val decodeStart = System.currentTimeMillis()
            val mono: MonoPcm = try {
                pcmDecoder.decodeMono(song.filePath) { fraction ->
                    if (cancelRequested) throw CancellationException("分析已取消")
                    tracker.onDecode(fraction)
                }
            } catch (e: CancellationException) {
                if (cancelRequested) {
                    _state.value = AnalysisState.Failed("分析已取消")
                    return true
                }
                _state.value = AnalysisState.Idle
                throw e
            } catch (e: LoopException) {
                _state.value = AnalysisState.Failed(e.message ?: "音频解码失败")
                return true
            } catch (e: Exception) {
                _state.value = AnalysisState.Failed(e.message ?: "音频解码失败")
                return true
            }
            val decodeMs = System.currentTimeMillis() - decodeStart
            tracker.onDecodeComplete(mono.frames)

            val peaksStart = System.currentTimeMillis()
            val peaks = try {
                withContext(Dispatchers.Default) { WaveformPeaks.buildPeaks(mono.data) }
            } catch (e: CancellationException) {
                if (cancelRequested) {
                    _state.value = AnalysisState.Failed("分析已取消")
                    return true
                }
                _state.value = AnalysisState.Idle
                throw e
            }
            val peaksMs = System.currentTimeMillis() - peaksStart

            val analyzeStart = System.currentTimeMillis()
            val flat = try {
                withContext(Dispatchers.Default) {
                    val sink = ProgressSink { fraction ->
                        if (cancelRequested) throw CancellationException("分析已取消")
                        tracker.onAnalyze(fraction)
                    }
                    LoopEngineJni.analyzePcm(
                        mono.data, mono.sampleRate, DEFAULT_MAX_CANDIDATES, sink,
                    )
                }
            } catch (e: CancellationException) {
                if (cancelRequested) {
                    _state.value = AnalysisState.Failed("分析已取消")
                    return true
                }
                _state.value = AnalysisState.Idle
                throw e
            } catch (e: Exception) {
                _state.value = AnalysisState.Failed(e.message ?: "循环点分析失败")
                return true
            }
            val analyzeMs = System.currentTimeMillis() - analyzeStart

            if (cancelRequested) {
                _state.value = AnalysisState.Failed("分析已取消")
                return true
            }

            val durationMs = if (song.duration > 0L) {
                song.duration
            } else if (mono.sampleRate > 0) {
                (mono.frames.toDouble() * 1000.0 / mono.sampleRate).roundToLong()
            } else {
                0L
            }
            val durationS = if (mono.sampleRate > 0) mono.frames.toDouble() / mono.sampleRate else 0.0
            Log.d(
                "LoopPerf",
                "decode=" + decodeMs + "ms analyze=" + analyzeMs + "ms peaks=" + peaksMs +
                    "ms dur=" + "%.1f".format(durationS) + "s song=" + song.id,
            )

            // issue 00018:把本次实测的 decode:analyze 比例记到时长档,下次同档位按它分配预算。
            // 峰值构建属于"分析"总预算,一起计入 analyze。
            AnalysisTiming.record(
                durationMs = if (song.duration > 0L) song.duration else durationMs,
                decodeMs = decodeMs,
                analyzeMs = peaksMs + analyzeMs,
            )

            // 收尾(解析候选/落库)只走最后 5%,进度停在 0.95 等 Done。
            tracker.onFinalizing()

            val candidates = LoopCandidates.parse(flat)
            if (candidates.isEmpty()) {
                _state.value = AnalysisState.Failed("未找到合适的循环点")
                return true
            }

            // 临时结果自动落库:无行或行 confirmed=0 → upsert top1(confirmed=0);
            // 已确认行不覆盖(临时结果仅在页面内存展示)。
            // issue 00016:同一次 upsert 把**完整候选数组**写进 candidatesJson,
            // 重新进入结果页才能恢复「候选(N)」而不是只剩 1 个。
            try {
                val existing = songLoopDao.getBySongId(song.id)
                if (existing == null || !existing.confirmed) {
                    saveResultInternal(
                        songId = song.id,
                        candidate = candidates.first(),
                        sampleRate = mono.sampleRate,
                        durationMs = durationMs,
                        confirmed = false,
                        candidatesJson = LoopCandidatesJson.encode(candidates),
                    )
                    Log.d(
                        TAG,
                        "temporary result saved (confirmed=0) candidates=" + candidates.size +
                            " song=" + song.id,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "temporary result save failed", e)
            }

            tracker.finish()
            _state.value = AnalysisState.Done(
                songId = song.id,
                candidates = candidates,
                sampleRate = mono.sampleRate,
                peaks = peaks,
                durationMs = durationMs,
            )
            return true
        } finally {
            heartbeat?.cancel()
            analyzeMutex.unlock()
        }
    }

    /**
     * 单次分析的进度跟踪器(issue 00018)。
     *
     * - 工作单位:解码帧 / 分析块;解码/分析回调只给 fraction,这里按标称总量还原成工作量
     *   (比例不变,避免为了拿帧号去改解码器/JNI 契约);
     * - 预算:解码/分析按 [AnalysisTiming] 最近一次同档位实测比例加权(默认 0.66/0.34);
     * - 输出:单调 + EMA 平滑 + 台阶上限(见 [AnalysisProgress.compute]);
     * - 节流:常规更新 ≤10Hz(100ms),阶段切换/收尾强制立即上报;
     * - 心跳:工作回调空档(或进度已追平工作量目标)时按 [PROGRESS_CREEP_STEP] 微步推进,
     *   避免解码预热/引擎粗粒度回调造成 >1s 完全静止。
     */
    private inner class ProgressTracker(durationMs: Long) {

        private val decodeWeight = AnalysisTiming.decodeWeightFor(durationMs)

        /** 预计总帧:时长已知时按 44.1kHz 估算(只影响归一化比例),未知时用标称工作量。 */
        private var totalFrames: Long = if (durationMs > 0L) {
            durationMs * NOMINAL_SAMPLE_RATE / 1000L
        } else {
            NOMINAL_WORK_UNITS
        }

        private var phase = AnalysisProgress.Phase.STARTING
        private var smoothed = 0f
        private var lastWorkTarget = 0f
        private var lastWorkMs = 0L
        private var lastEmitMs = 0L
        private var lastEmitted = -1f
        private var finished = false

        @Synchronized
        fun start() {
            lastWorkMs = System.currentTimeMillis()
            update(
                phase = AnalysisProgress.Phase.STARTING,
                decodedFrames = 0L,
                analyzedBlocks = 0L,
                force = true,
            )
        }

        @Synchronized
        fun onDecode(fraction: Float) {
            lastWorkMs = System.currentTimeMillis()
            update(
                phase = AnalysisProgress.Phase.DECODING,
                decodedFrames = (sanitizeFraction(fraction) * totalFrames).roundToLong(),
                analyzedBlocks = 0L,
                force = false,
            )
        }

        /** 解码完成:用真实帧数替换估算总量,并立即上报分析起点。 */
        @Synchronized
        fun onDecodeComplete(frames: Long) {
            if (frames > 0L) totalFrames = frames
            lastWorkMs = System.currentTimeMillis()
            update(
                phase = AnalysisProgress.Phase.ANALYZING,
                decodedFrames = totalFrames,
                analyzedBlocks = 0L,
                force = true,
            )
        }

        @Synchronized
        fun onAnalyze(fraction: Float) {
            lastWorkMs = System.currentTimeMillis()
            update(
                phase = AnalysisProgress.Phase.ANALYZING,
                decodedFrames = totalFrames,
                analyzedBlocks = (sanitizeFraction(fraction) * PROGRESS_BLOCKS).roundToLong(),
                force = false,
            )
        }

        @Synchronized
        fun onFinalizing() {
            lastWorkMs = System.currentTimeMillis()
            update(
                phase = AnalysisProgress.Phase.FINALIZING,
                decodedFrames = totalFrames,
                analyzedBlocks = PROGRESS_BLOCKS,
                force = true,
            )
        }

        /**
         * 心跳:工作回调空档 ≥ [PROGRESS_STALL_FILL_AFTER_MS],或进度已经追平/超过工作量目标
         * (例如并行解码失败后回退单线程、进度不再前进)时,做一次极小步进,保证持续可见。
         */
        @Synchronized
        fun heartbeat() {
            if (finished) return
            val now = System.currentTimeMillis()
            val stalled = now - lastWorkMs >= PROGRESS_STALL_FILL_AFTER_MS
            val aheadOfWork = smoothed > lastWorkTarget
            if (!stalled && !aheadOfWork) return
            if (now - lastEmitMs < PROGRESS_UPDATE_INTERVAL_MS) return
            val next = minOf(AnalysisProgress.WORK_END, smoothed + PROGRESS_CREEP_STEP)
            if (next <= smoothed) return
            smoothed = next
            emit()
        }

        /** 分析成功:标记结束(下一个状态就是 Done,不再上报进度)。 */
        @Synchronized
        fun finish() {
            finished = true
            smoothed = 1f
        }

        private fun update(
            phase: AnalysisProgress.Phase,
            decodedFrames: Long,
            analyzedBlocks: Long,
            force: Boolean,
        ) {
            if (finished) return
            this.phase = phase
            val analyzeWeight = 1f - decodeWeight
            lastWorkTarget = AnalysisProgress.workTarget(
                phase = phase,
                decodedFrames = decodedFrames,
                totalFrames = totalFrames,
                analyzedBlocks = analyzedBlocks,
                totalBlocks = PROGRESS_BLOCKS,
                decodeWeight = decodeWeight,
                analyzeWeight = analyzeWeight,
            )
            smoothed = AnalysisProgress.compute(
                phase = phase,
                decodedFrames = decodedFrames,
                totalFrames = totalFrames,
                analyzedBlocks = analyzedBlocks,
                totalBlocks = PROGRESS_BLOCKS,
                smoothedPrev = smoothed,
                decodeWeight = decodeWeight,
                analyzeWeight = analyzeWeight,
            )
            if (!force) {
                val now = System.currentTimeMillis()
                if (now - lastEmitMs < PROGRESS_UPDATE_INTERVAL_MS) return
                if (smoothed <= lastEmitted + EMIT_EPSILON) return
            }
            emit()
        }

        private fun emit() {
            lastEmitMs = System.currentTimeMillis()
            lastEmitted = smoothed
            _state.value = when (phase) {
                AnalysisProgress.Phase.STARTING,
                AnalysisProgress.Phase.DECODING -> AnalysisState.Decoding(smoothed)

                AnalysisProgress.Phase.ANALYZING,
                AnalysisProgress.Phase.FINALIZING -> AnalysisState.Analyzing(smoothed)

                AnalysisProgress.Phase.DONE -> AnalysisState.Analyzing(1f)
            }
        }

        private fun sanitizeFraction(fraction: Float): Float =
            if (fraction.isNaN()) 0f else fraction.coerceIn(0f, 1f)
    }

    /**
     * 确认并保存候选(采样点 → ms 换算,夹到 [0, totalMs]);upsert confirmed=1 覆盖旧结果。
     *
     * 不带候选列表的重载(debug 服务器/老调用):**保留**行内已有的 candidatesJson,
     * 不会因为一次确认把已落库的候选列表清空(issue 00016 §2)。
     */
    suspend fun confirmAndSave(songId: Long, candidate: LoopCandidateKt, sampleRate: Int, durationMs: Long) {
        confirmAndSave(songId, candidate, sampleRate, durationMs, candidates = null)
    }

    /**
     * 确认并保存,并把结果页当前候选列表一并落库。
     *
     * @param candidates 结果页内存里的完整候选列表(可能含用户微调后的窗口对应的**选中项**);
     *   非空 → 重新序列化写入;null/空 → 保留行内已有 JSON(老数据/无内存列表路径)。
     */
    suspend fun confirmAndSave(
        songId: Long,
        candidate: LoopCandidateKt,
        sampleRate: Int,
        durationMs: Long,
        candidates: List<LoopCandidateKt>?,
    ) {
        val candidatesJson = if (!candidates.isNullOrEmpty()) {
            LoopCandidatesJson.encode(candidates)
        } else {
            // legacy 行(或调用方没有内存列表):保留已落库的 JSON,不能清空。
            try {
                songLoopDao.getBySongId(songId)?.candidatesJson
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "read existing candidatesJson failed", e)
                null
            }
        }
        saveResultInternal(
            songId = songId,
            candidate = candidate,
            sampleRate = sampleRate,
            durationMs = durationMs,
            confirmed = true,
            candidatesJson = candidatesJson,
        )
    }

    /** 取消最终结果:confirmed 1→0(行保留);无行/已是临时状态时幂等。 */
    suspend fun demoteFinal(songId: Long) {
        val row = songLoopDao.getBySongId(songId) ?: return
        if (!row.confirmed) return
        songLoopDao.upsert(row.copy(confirmed = false))
    }

    /** 删除分析结果(整行移除)。 */
    suspend fun deleteResult(songId: Long) {
        songLoopDao.deleteBySongId(songId)
    }

    /** 响应式观察某首歌的分析结果。 */
    fun resultFor(songId: Long): Flow<SongLoop?> = songLoopDao.getBySongIdFlow(songId)

    /** 一次性查询(菜单入口分流用)。 */
    suspend fun getResultOnce(songId: Long): SongLoop? = songLoopDao.getBySongId(songId)

    /**
     * 统一落库入口:临时结果 confirmed=false / 用户确认 confirmed=true。
     *
     * @param candidatesJson 完整候选数组 JSON;null 表示老数据/未知(结果页退回单候选)。
     */
    private suspend fun saveResultInternal(
        songId: Long,
        candidate: LoopCandidateKt,
        sampleRate: Int,
        durationMs: Long,
        confirmed: Boolean,
        candidatesJson: String?,
    ) {
        if (sampleRate <= 0) throw LoopException("无效采样率")
        val totalMs = maxOf(0L, durationMs)
        val startMs = (candidate.startSamples * 1000.0 / sampleRate).roundToLong().coerceIn(0L, totalMs)
        val endMs = (candidate.endSamples * 1000.0 / sampleRate).roundToLong().coerceIn(startMs, totalMs)
        songLoopDao.upsert(
            SongLoop(
                songId = songId,
                loopStartSamples = candidate.startSamples,
                loopEndSamples = candidate.endSamples,
                sampleRate = sampleRate,
                loopStartMs = startMs,
                loopEndMs = endMs,
                score = candidate.score,
                noteDistance = candidate.noteDistance,
                loudnessDifference = candidate.loudnessDifference,
                createdAt = System.currentTimeMillis(),
                confirmed = confirmed,
                candidatesJson = candidatesJson,
            ),
        )
    }

    private companion object {
        const val TAG = "LoopAnalysis"

        const val DEFAULT_MAX_CANDIDATES = 10

        /** 常规进度上报间隔(≤10Hz)。 */
        const val PROGRESS_UPDATE_INTERVAL_MS = 100L

        /** 工作回调空档超过该值 → 心跳微步,避免 >1s 完全静止。 */
        const val PROGRESS_STALL_FILL_AFTER_MS = 250L

        /** 心跳微步(每次 100ms  tick 最多推进 0.2%)。 */
        const val PROGRESS_CREEP_STEP = 0.002f

        /** 低于该增量不重复上报。 */
        const val EMIT_EPSILON = 1e-4f

        /** 分析块标称总量(引擎只给 fraction,这里归一化成块数)。 */
        const val PROGRESS_BLOCKS = 1_000L

        /** 时长未知时的标称总帧数(只影响归一化比例)。 */
        const val NOMINAL_WORK_UNITS = 1_000_000L

        /** 解码帧数估算用标称采样率。 */
        const val NOMINAL_SAMPLE_RATE = 44_100
    }
}
