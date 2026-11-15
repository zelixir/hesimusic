package com.zjr.hesimusic.ui.loop

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zjr.hesimusic.data.dao.SongDao
import com.zjr.hesimusic.data.model.Song
import com.zjr.hesimusic.data.model.SongLoop
import com.zjr.hesimusic.loop.analysis.AnalysisState
import com.zjr.hesimusic.loop.analysis.LoopAnalysisManager
import com.zjr.hesimusic.loop.analysis.LoopCandidateKt
import com.zjr.hesimusic.loop.analysis.PcmDecoder
import com.zjr.hesimusic.loop.analysis.WaveformPeaks
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.math.roundToLong

/** Done 结果页的数据(候选 / 波形 / 时长 / 当前行是否最终结果)。 */
data class LoopResult(
    val songId: Long,
    val candidates: List<LoopCandidateKt>,
    val sampleRate: Int,
    /** null = 后台补波形中(UI 以网格降级先渲染,不阻塞首帧)。 */
    val peaks: WaveformPeaks?,
    val durationMs: Long,
    /** 当前 song_loop 行是否 confirmed=1(驱动「取消最终结果」显隐)。 */
    val confirmed: Boolean,
)

/** 分析/预览页的粗粒度状态机(骨架 / 结果 / 失败)。 */
sealed interface LoopScreenState {
    /** 首帧骨架:只渲染曲名占位 + 进度条,不等任何查询/解码。 */
    data object Skeleton : LoopScreenState

    data class Ready(val result: LoopResult) : LoopScreenState

    data class Failed(val message: String) : LoopScreenState
}

/**
 * 分析/预览页 ViewModel(契约 docs/loop-v3-ui-contract.md §2)。
 *
 * 设计要点:
 * - **首帧零等待**:构造即 Skeleton;歌曲查询、song_loop 查询、波形补解码全部异步;
 * - **进入策略**(§2.1):有行 → 加载行(不重分析)+ 后台补波形;无行 → 自动 analyze();
 * - **单飞**:analyze() 返回 false 表示已有分析,页面保留原状态并提示;
 * - **A/B 双口径**:样本(保存/候选)与毫秒(拖动/显示)同步维护;
 * - **拖动只改视觉**:UI 拖动时调 [dragTo],松手才调 [commitWindow] 应用给预览播放器;
 * - 确认保存 → confirmAndSave → 回调播放该歌 + REPEAT_ONE → 调用方 popBackStack(§2.4)。
 */
@HiltViewModel
class LoopViewModel @Inject constructor(
    private val analysisManager: LoopAnalysisManager,
    private val songDao: SongDao,
    private val pcmDecoder: PcmDecoder,
    private val previewPlayer: PreviewPlayer,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    companion object {
        private const val TAG = "LoopViewModel"
        const val ARG_SONG_ID = "songId"

        /** A/B 最小间隔(拖拽/精调下限),与 PreviewPlayer 保持一致。 */
        private const val MIN_LOOP_LENGTH_MS = PreviewPlayer.MIN_LOOP_LENGTH_MS
    }

    val songId: Long = savedStateHandle.get<Long>(ARG_SONG_ID) ?: -1L

    private val _song = MutableStateFlow<Song?>(null)
    val song: StateFlow<Song?> = _song.asStateFlow()

    private val _screen = MutableStateFlow<LoopScreenState>(LoopScreenState.Skeleton)
    val screen: StateFlow<LoopScreenState> = _screen.asStateFlow()

    /** 分析状态机(进度面板直接读;Done/Failed 由本 VM 消费成 [LoopScreenState])。 */
    val analysisState: StateFlow<AnalysisState> = analysisManager.state

    // ---- A/B(样本 + 毫秒双口径) ----
    private val _aMs = MutableStateFlow(0L)
    val aMs: StateFlow<Long> = _aMs.asStateFlow()
    private val _bMs = MutableStateFlow(0L)
    val bMs: StateFlow<Long> = _bMs.asStateFlow()

    private var aSamples: Long = 0L
    private var bSamples: Long = 0L

    private val _selectedCandidate = MutableStateFlow(0)
    val selectedCandidate: StateFlow<Int> = _selectedCandidate.asStateFlow()

    private val _mode = MutableStateFlow(PreviewMode.FULL)
    val mode: StateFlow<PreviewMode> = _mode.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)

    /** 一次性提示(Toast);UI 消费后调 [clearNotice]。 */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    // ---- 预览播放器透传 ----
    val isPlaying: StateFlow<Boolean> = previewPlayer.isPlaying
    val buffering: StateFlow<Boolean> = previewPlayer.buffering
    val positionMs: StateFlow<Long> = previewPlayer.positionMs
    val isReplaying: StateFlow<Boolean> = previewPlayer.isReplaying
    val ringReady: StateFlow<Boolean> = previewPlayer.ringReady

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    // ---- 后台预填环缓冲（进页即解码 [A,B) 灌 ring） ----
    private val _ringPreparing = MutableStateFlow(false)

    /** 后台正在解码并预填环缓冲区（UI 显示「正在准备环缓冲…」）。 */
    val ringPreparing: StateFlow<Boolean> = _ringPreparing.asStateFlow()

    private var prefillJob: Job? = null
    private var prefillToken: Any? = null

    /**
     * 后台解码 [A,B) 并预填环缓冲（进 Done 页 / 窗口变化后调用，可取消）。
     *
     * 成功后「接缝检查」、恢复播放等从区间内起播的场景立即可无缝，不必先完整试听一轮；
     * 失败（解码不足/输出格式与 sink 不一致/区间超限）只打日志，原有降级/预卷兜底保留。
     */
    private fun scheduleRingPrefill() {
        val song = _song.value ?: return
        if (song.isCue) return
        val window = previewPlayer.loopWindowFor(song, _aMs.value, _bMs.value) ?: run {
            _ringPreparing.value = false
            return
        }
        if (previewPlayer.isRingReadyFor(window)) {
            // 该窗口的 ring 已完整（预填或捕获）：不重复解码。
            prefillJob?.cancel()
            _ringPreparing.value = false
            return
        }
        prefillJob?.cancel()
        val token = Any()
        prefillToken = token
        _ringPreparing.value = true
        prefillJob = viewModelScope.launch {
            try {
                val segment = pcmDecoder.decodePcm16(
                    song.filePath,
                    window.loopStartUs,
                    window.loopEndUs,
                )
                // 解码期间可能已换窗口（拖动/换候选）：以最新 A/B 重新取窗口，不一致就丢弃。
                val latest = previewPlayer.loopWindowFor(song, _aMs.value, _bMs.value)
                if (latest != window) {
                    Log.d(TAG, "ring prefill dropped: window changed " + window + " -> " + latest)
                    return@launch
                }
                val result = previewPlayer.seedRing(
                    window,
                    segment.data,
                    segment.sampleRate,
                    segment.channels,
                )
                Log.d(
                    TAG,
                    "ring prefill " + result.status + " frames=" + result.frames +
                        " window=" + window + " detail=" + result.detail,
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "ring prefill failed: " + e.message)
            } finally {
                if (prefillToken === token) {
                    _ringPreparing.value = false
                }
            }
        }
    }

    /** 确认并保存已成功:退出时不再恢复原播放(由保存路径接管)。 */
    @Volatile
    var savedAndPlayed: Boolean = false
        private set

    // 播放头插值锚点:positionMs 每次 emission 重置锚点,UI 以 60fps 外推
    @Volatile
    private var anchorMs = 0L

    @Volatile
    private var anchorElapsedRealtime = 0L

    init {
        viewModelScope.launch {
            previewPlayer.positionMs.collect { ms ->
                anchorMs = ms
                anchorElapsedRealtime = SystemClock.elapsedRealtime()
            }
        }
        viewModelScope.launch {
            previewPlayer.error.collect { err ->
                if (err != null) _notice.value = err
            }
        }
        viewModelScope.launch { resolveEntry() }
    }

    // ------------------------------------------------------------------ 进入页面

    /**
     * 入口分流(契约 §2.1):
     * - 有行(临时/最终)→ 加载行,不自动重分析;后台解码补波形;
     * - 无行 → 自动 analyze();
     * - 找不到歌 / CUE 分轨 → Failed(守卫)。
     */
    private suspend fun resolveEntry() {
        val song = try {
            songDao.getSongsByIds(listOf(songId)).firstOrNull()
        } catch (e: Exception) {
            Log.w(TAG, "load song failed", e)
            null
        }
        _song.value = song
        if (song == null) {
            _screen.value = LoopScreenState.Failed("未找到歌曲")
            return
        }
        if (song.isCue) {
            _screen.value = LoopScreenState.Failed("CUE 分轨暂不支持无缝循环")
            return
        }
        val row = try {
            analysisManager.getResultOnce(songId)
        } catch (e: Exception) {
            Log.w(TAG, "query song_loop failed", e)
            null
        }
        if (row != null) {
            loadSaved(song, row)
        } else {
            runAnalysis(song, previous = LoopScreenState.Skeleton)
        }
    }

    /** 已有行 → 直接进结果页(A/B 从行映射),后台补波形;不自动重分析。 */
    private suspend fun loadSaved(song: Song, row: SongLoop) {
        val duration = maxOf(song.duration, row.loopEndMs, MIN_LOOP_LENGTH_MS + 1L)
        _durationMs.value = duration
        val candidate = LoopCandidateKt(
            startSamples = row.loopStartSamples,
            endSamples = row.loopEndSamples,
            score = row.score,
            noteDistance = row.noteDistance,
            loudnessDifference = row.loudnessDifference,
        )
        applyAbFromSamples(row.loopStartSamples, row.loopEndSamples, row.sampleRate, duration)
        _selectedCandidate.value = 0
        _screen.value = LoopScreenState.Ready(
            LoopResult(
                songId = song.id,
                candidates = listOf(candidate),
                sampleRate = row.sampleRate,
                peaks = null,
                durationMs = duration,
                confirmed = row.confirmed,
            )
        )
        refillPeaks(song)
        scheduleRingPrefill()
    }

    /**
     * 波形补解码(契约 §2.1,旧 v5.1 §9.5 #1):解码成功前 UI 以网格降级,
     * 解码在后台 Default 线程,不阻塞页面。
     */
    private fun refillPeaks(song: Song) {
        viewModelScope.launch {
            val peaks = try {
                withContext(Dispatchers.Default) {
                    val mono = pcmDecoder.decodeMono(song.filePath)
                    WaveformPeaks.buildPeaks(mono.data)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "refill peaks failed: " + e.message)
                null
            }
            if (peaks == null) return@launch
            val current = _screen.value as? LoopScreenState.Ready ?: return@launch
            if (current.result.songId != song.id) return@launch
            _screen.value = LoopScreenState.Ready(current.result.copy(peaks = peaks))
        }
    }

    // ------------------------------------------------------------------ 分析

    /** 页内「重新分析」/失败重试。单飞被拒时保留原状态并提示,不把页面打进 Failed。 */
    fun startAnalysis() {
        viewModelScope.launch {
            val song = _song.value ?: return@launch
            if (song.isCue) {
                _notice.value = "CUE 分轨暂不支持无缝循环"
                return@launch
            }
            runAnalysis(song, previous = _screen.value)
        }
    }

    private suspend fun runAnalysis(song: Song, previous: LoopScreenState) {
        _screen.value = LoopScreenState.Skeleton
        val accepted = try {
            analysisManager.analyze(song)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "analyze failed", e)
            _screen.value = LoopScreenState.Failed(e.message ?: "分析失败")
            return
        }
        if (!accepted) {
            _screen.value = previous
            _notice.value = "已有分析进行中"
            return
        }
        when (val state = analysisManager.state.value) {
            is AnalysisState.Done -> {
                if (state.songId != song.id) {
                    _screen.value = LoopScreenState.Failed("分析结果与当前歌曲不匹配")
                } else {
                    applyDone(song, state)
                }
            }
            is AnalysisState.Failed -> _screen.value = LoopScreenState.Failed(state.message)
            else -> _screen.value = LoopScreenState.Failed("分析未完成")
        }
    }

    private fun applyDone(song: Song, done: AnalysisState.Done) {
        val duration = maxOf(done.durationMs, song.duration, MIN_LOOP_LENGTH_MS + 1L)
        _durationMs.value = duration
        val first = done.candidates.firstOrNull()
        if (first != null) {
            applyAbFromSamples(first.startSamples, first.endSamples, done.sampleRate, duration)
        }
        _selectedCandidate.value = 0
        // analyze() 已按语义落库(有最终行的歌不被临时结果覆盖),这里回查一次确认态。
        viewModelScope.launch {
            val row = runCatching { analysisManager.getResultOnce(song.id) }.getOrNull()
            val confirmed = row?.confirmed ?: false
            val current = _screen.value as? LoopScreenState.Ready
            if (current != null && current.result.songId == song.id) {
                _screen.value = LoopScreenState.Ready(current.result.copy(confirmed = confirmed))
            }
        }
        _screen.value = LoopScreenState.Ready(
            LoopResult(
                songId = song.id,
                candidates = done.candidates,
                sampleRate = done.sampleRate,
                peaks = done.peaks,
                durationMs = duration,
                confirmed = false,
            )
        )
        scheduleRingPrefill()
    }

    // ------------------------------------------------------------------ A/B

    /** 采样点 → 毫秒双口径(候选/已存行统一入口)。 */
    private fun applyAbFromSamples(startSamples: Long, endSamples: Long, sampleRate: Int, durationMs: Long) {
        aSamples = startSamples
        bSamples = endSamples
        val sr = if (sampleRate > 0) sampleRate else 44_100
        val a = (startSamples * 1000.0 / sr).roundToLong().coerceIn(0L, durationMs)
        val b = (endSamples * 1000.0 / sr).roundToLong().coerceIn(a + MIN_LOOP_LENGTH_MS, durationMs)
        _aMs.value = a
        _bMs.value = b
    }

    /** 拖动/精调:只改视觉状态(不动预览窗口),松手由 [commitWindow] 应用。 */
    fun dragTo(handleA: Boolean, ms: Long) {
        val duration = _durationMs.value.coerceAtLeast(MIN_LOOP_LENGTH_MS + 1L)
        if (handleA) {
            _aMs.value = ms.coerceIn(0L, (_bMs.value - MIN_LOOP_LENGTH_MS).coerceAtLeast(0L))
        } else {
            _bMs.value = ms.coerceIn(_aMs.value + MIN_LOOP_LENGTH_MS, duration)
        }
        syncSamplesFromMs()
    }

    /** 松手才调:把当前 A/B 应用给预览播放器(新窗口会让 ring 失效并重新起播)。 */
    fun commitWindow() {
        if (!previewPlayer.isPrepared) {
            // 还没起播也要重新预填：新窗口的 ring 需要新的解码结果。
            scheduleRingPrefill()
            return
        }
        previewPlayer.updateWindow(_aMs.value, _bMs.value)
        scheduleRingPrefill()
    }

    private fun syncSamplesFromMs() {
        val result = (_screen.value as? LoopScreenState.Ready)?.result
        val sr = result?.sampleRate?.takeIf { it > 0 } ?: 44_100
        aSamples = (_aMs.value * sr / 1000.0).roundToLong()
        bSamples = (_bMs.value * sr / 1000.0).roundToLong()
    }

    // ------------------------------------------------------------------ 预览

    /** 按当前 A/B 建立预览(幂等;已 prepare 则复用)。 */
    private fun ensurePreview(): Boolean {
        val song = _song.value ?: return false
        if (previewPlayer.isPrepared) return true
        return try {
            previewPlayer.prepare(song, _aMs.value, _bMs.value)
            true
        } catch (e: Exception) {
            Log.w(TAG, "prepare preview failed", e)
            _notice.value = "预览启动失败，请重试"
            false
        }
    }

    private fun startOfMode(mode: PreviewMode): Long = when (mode) {
        PreviewMode.FULL -> 0L
        // 从 A 前 300ms 预卷,保证 ring 完整覆盖 [A,B)(详见 PreviewPlayer.LOOP_PREROLL_MS)
        PreviewMode.LOOP_ONLY -> (_aMs.value - PreviewPlayer.LOOP_PREROLL_MS).coerceAtLeast(0L)
        PreviewMode.SEAM -> (_bMs.value - PreviewPlayer.SEAM_LEAD_MS).coerceAtLeast(0L)
        PreviewMode.FREE -> 0L
    }

    /** 四模式试听(契约 §2.3):点击即起播。 */
    fun setMode(mode: PreviewMode) {
        _mode.value = mode
        if (!ensurePreview()) return
        try {
            previewPlayer.play(mode, startOfMode(mode))
        } catch (e: Exception) {
            Log.w(TAG, "preview play failed", e)
            _notice.value = "预览启动失败，请重试"
        }
    }

    fun togglePlay() {
        if (!ensurePreview()) return
        if (previewPlayer.isPlaying.value) {
            previewPlayer.pause()
        } else {
            val resume = previewPlayer.positionMs.value
                .coerceIn(0L, _durationMs.value.coerceAtLeast(0L))
            previewPlayer.play(_mode.value, resume)
        }
    }

    /** 停止:暂停并回到当前模式起点。 */
    fun stopPreview() {
        previewPlayer.pause()
        previewPlayer.seekToDisplayMs(startOfMode(_mode.value))
    }

    /** 波形点击 seek(展示口径);不自动开始播放。 */
    fun seekToDisplayMs(ms: Long) {
        if (!ensurePreview()) return
        previewPlayer.seekToDisplayMs(ms.coerceIn(0L, _durationMs.value.coerceAtLeast(0L)))
    }

    /** 当前位置 → A(松手语义:立即应用窗口)。 */
    fun setAFromCurrent() {
        dragTo(handleA = true, ms = previewPlayer.positionMs.value)
        commitWindow()
    }

    /** 当前位置 → B(松手语义:立即应用窗口)。 */
    fun setBFromCurrent() {
        dragTo(handleA = false, ms = previewPlayer.positionMs.value)
        commitWindow()
    }

    fun seekToA() {
        seekToDisplayMs(_aMs.value)
    }

    /** 跳到 B−5s(接缝检查起点)。 */
    fun seekToSeam() {
        seekToDisplayMs((_bMs.value - PreviewPlayer.SEAM_LEAD_MS).coerceAtLeast(0L))
    }

    /** 候选点击:应用 A/B 并立即试听当前模式(契约 §2.3)。 */
    fun applyCandidate(index: Int) {
        val result = (_screen.value as? LoopScreenState.Ready)?.result ?: return
        val candidate = result.candidates.getOrNull(index) ?: return
        _selectedCandidate.value = index
        applyAbFromSamples(candidate.startSamples, candidate.endSamples, result.sampleRate, result.durationMs)
        if (!ensurePreview()) return
        previewPlayer.updateWindow(_aMs.value, _bMs.value)
        previewPlayer.play(_mode.value, startOfMode(_mode.value))
        scheduleRingPrefill()
    }

    /** 60fps 播放头插值(契约 §2.2):锚点 + 时间外推,按当前窗口折返;positionMs 仅作校正。 */
    fun interpolatedPositionMs(nowElapsedRealtime: Long): Long {
        if (!previewPlayer.isPlaying.value) return anchorMs
        val duration = _durationMs.value.coerceAtLeast(0L)
        val extrapolated = anchorMs + (nowElapsedRealtime - anchorElapsedRealtime)
        if (_mode.value == PreviewMode.FREE) return extrapolated.coerceIn(0L, duration)
        val a = _aMs.value
        val b = _bMs.value
        val length = b - a
        if (length <= 0L) return extrapolated.coerceIn(0L, duration)
        return if (extrapolated < a) {
            extrapolated.coerceIn(0L, duration)
        } else {
            a + ((extrapolated - a) % length)
        }
    }

    // ------------------------------------------------------------------ 底部操作

    /** 确认并保存 → confirmAndSave → 回调(播放该歌 + REPEAT_ONE + popBackStack)。 */
    fun confirmAndSave(onSaved: (Song) -> Unit) {
        val song = _song.value ?: return
        val result = (_screen.value as? LoopScreenState.Ready)?.result ?: return
        val sampleRate = result.sampleRate
        if (sampleRate <= 0) {
            _notice.value = "保存失败:无效采样率"
            return
        }
        syncSamplesFromMs()
        val source = result.candidates.getOrNull(_selectedCandidate.value)
        val candidate = LoopCandidateKt(
            startSamples = aSamples,
            endSamples = bSamples,
            score = source?.score ?: 0.0,
            noteDistance = source?.noteDistance ?: 0.0,
            loudnessDifference = source?.loudnessDifference ?: 0.0,
        )
        viewModelScope.launch {
            try {
                analysisManager.confirmAndSave(song.id, candidate, sampleRate, result.durationMs)
            } catch (e: Exception) {
                Log.w(TAG, "confirmAndSave failed", e)
                _notice.value = "保存失败，请重试"
                return@launch
            }
            savedAndPlayed = true
            val current = _screen.value as? LoopScreenState.Ready
            if (current != null) {
                _screen.value = LoopScreenState.Ready(current.result.copy(confirmed = true))
            }
            releasePreview()
            onSaved(song)
        }
    }

    /** 页内「取消最终结果」:confirmed 1→0(行保留),页面留在当前内存预览。 */
    fun demoteFinal() {
        viewModelScope.launch {
            try {
                analysisManager.demoteFinal(songId)
            } catch (e: Exception) {
                Log.w(TAG, "demoteFinal failed", e)
                _notice.value = "操作失败，请重试"
                return@launch
            }
            val current = _screen.value as? LoopScreenState.Ready
            if (current != null) {
                _screen.value = LoopScreenState.Ready(current.result.copy(confirmed = false))
            }
            _notice.value = "已转为临时结果，不再无缝"
        }
    }

    /** 页内「删除分析结果」:整行移除,页面留在当前内存预览。 */
    fun deleteResult() {
        viewModelScope.launch {
            try {
                analysisManager.deleteResult(songId)
            } catch (e: Exception) {
                Log.w(TAG, "deleteResult failed", e)
                _notice.value = "删除失败，请重试"
                return@launch
            }
            val current = _screen.value as? LoopScreenState.Ready
            if (current != null) {
                _screen.value = LoopScreenState.Ready(current.result.copy(confirmed = false))
            }
            _notice.value = "已删除分析结果，下次进入将重新分析"
        }
    }

    fun releasePreview() {
        previewPlayer.release()
    }

    fun clearNotice() {
        _notice.value = null
    }

    override fun onCleared() {
        previewPlayer.release()
        super.onCleared()
    }
}
