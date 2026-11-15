package com.zjr.hesimusic.loop.analysis

import android.util.Log
import com.zjr.hesimusic.data.dao.SongLoopDao
import com.zjr.hesimusic.data.model.Song
import com.zjr.hesimusic.data.model.SongLoop
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToLong

/**
 * 分析状态机(契约 docs/loop-v3-plan.md §3.1 / docs/loop-seamless-v2.md §1.2)。
 *
 * 进度口径:解码 0→0.5、分析 0.5→1.0,全局单调(见 [LoopProgress])。
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
 * 语义(与上一版一致,契约 §3.1):
 * - [analyze] 单飞:进行中再次调用直接返回 false,不并发;
 * - 分析成功 → Done(内存候选 + 波形)并**自动落库临时结果**(confirmed=0,
 *   仅当无行或行 confirmed=0 时 upsert top1;已确认行不覆盖);
 * - [confirmAndSave] → upsert confirmed=1;
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
     * @return true = 本次调用执行了分析(无论成功/失败);false = 已有分析进行中,本次被拒绝。
     */
    suspend fun analyze(song: Song): Boolean {
        if (!analyzeMutex.tryLock()) return false
        try {
            cancelRequested = false
            _state.value = AnalysisState.Decoding(0f)
            var lastProgress = 0f

            val decodeStart = System.currentTimeMillis()
            val mono: MonoPcm = try {
                pcmDecoder.decodeMono(song.filePath) { fraction ->
                    if (cancelRequested) throw CancellationException("分析已取消")
                    lastProgress = LoopProgress.merge(
                        LoopProgress.Phase.DECODING, fraction, lastProgress,
                    )
                    _state.value = AnalysisState.Decoding(lastProgress)
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

            // 分析阶段起点固定为 0.5(解码已报 1.0),全局进度不回退。
            lastProgress = LoopProgress.merge(LoopProgress.Phase.ANALYZING, 0f, lastProgress)
            _state.value = AnalysisState.Analyzing(lastProgress)

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

            val analyzeStart = System.currentTimeMillis()
            val flat = try {
                withContext(Dispatchers.Default) {
                    val sink = ProgressSink { fraction ->
                        if (cancelRequested) throw CancellationException("分析已取消")
                        lastProgress = LoopProgress.merge(
                            LoopProgress.Phase.ANALYZING, fraction, lastProgress,
                        )
                        _state.value = AnalysisState.Analyzing(lastProgress)
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
                "decode=${decodeMs}ms analyze=${analyzeMs}ms dur=${"%.1f".format(durationS)}s song=${song.id}",
            )

            val candidates = LoopCandidates.parse(flat)
            if (candidates.isEmpty()) {
                _state.value = AnalysisState.Failed("未找到合适的循环点")
                return true
            }

            // 临时结果自动落库:无行或行 confirmed=0 → upsert top1(confirmed=0);
            // 已确认行不覆盖(临时结果仅在页面内存展示)。
            try {
                val existing = songLoopDao.getBySongId(song.id)
                if (existing == null || !existing.confirmed) {
                    saveResultInternal(song.id, candidates.first(), mono.sampleRate, durationMs, confirmed = false)
                    Log.d(TAG, "temporary result saved (confirmed=0) song=${song.id}")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "temporary result save failed", e)
            }

            _state.value = AnalysisState.Done(
                songId = song.id,
                candidates = candidates,
                sampleRate = mono.sampleRate,
                peaks = peaks,
                durationMs = durationMs,
            )
            return true
        } finally {
            analyzeMutex.unlock()
        }
    }

    /** 确认并保存候选(采样点 → ms 换算,夹到 [0, totalMs]);upsert confirmed=1 覆盖旧结果。 */
    suspend fun confirmAndSave(songId: Long, candidate: LoopCandidateKt, sampleRate: Int, durationMs: Long) {
        saveResultInternal(songId, candidate, sampleRate, durationMs, confirmed = true)
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

    /** 统一落库入口:临时结果 confirmed=false / 用户确认 confirmed=true。 */
    private suspend fun saveResultInternal(
        songId: Long,
        candidate: LoopCandidateKt,
        sampleRate: Int,
        durationMs: Long,
        confirmed: Boolean,
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
            ),
        )
    }

    private companion object {
        const val TAG = "LoopAnalysis"
        const val DEFAULT_MAX_CANDIDATES = 10
    }
}
