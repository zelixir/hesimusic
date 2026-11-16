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
 * 单调 + EMA 平滑(见 [AnalysisProgress] / [AnalysisProgressTracker]);每 100ms 最多上报一次。
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
            val tracker = AnalysisProgressTracker(song.duration) { phase, progress ->
                _state.value = when (phase) {
                    AnalysisProgress.Phase.STARTING,
                    AnalysisProgress.Phase.DECODING -> AnalysisState.Decoding(progress)

                    AnalysisProgress.Phase.ANALYZING,
                    AnalysisProgress.Phase.FINALIZING,
                    AnalysisProgress.Phase.DONE -> AnalysisState.Analyzing(progress)
                }
            }
            tracker.start()
            heartbeat = CoroutineScope(coroutineContext + Dispatchers.Default).launch {
                while (isActive) {
                    delay(AnalysisProgressTracker.UPDATE_INTERVAL_MS)
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

            // 收尾(解析候选/落库)只走最后 5%;台阶由 100ms 心跳逐步补齐到 0.95。
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

            // 收尾进度停在 0.95;若上报值还没跟上,最多等 budget 个 100ms 心跳补齐,
            // 保证 Done 的 1.0 只比它高一个 MAX_STEP(见 issue 00018 的真机指标)。
            val settleBudgetMs = tracker.finalizeWaitBudgetMs()
            var settleWaitedMs = 0L
            while (!tracker.finalizingSettled() && settleWaitedMs < settleBudgetMs) {
                delay(AnalysisProgressTracker.UPDATE_INTERVAL_MS)
                settleWaitedMs += AnalysisProgressTracker.UPDATE_INTERVAL_MS
            }
            // 让 0.95 至少存活一个采样周期,收尾的 0.95 → 1.0 才能被观测成两次更新。
            if (tracker.finalizingSettled()) delay(PROGRESS_FINALIZE_HOLD_MS)
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

        /** 到达 0.95 后保持一小段时间,保证 0.95 → 1.0 能被采样看到两次。 */
        const val PROGRESS_FINALIZE_HOLD_MS = 150L
    }
}
