package com.zjr.hesimusic.ui.loop

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zjr.hesimusic.data.dao.SongDao
import com.zjr.hesimusic.data.model.Song
import com.zjr.hesimusic.loop.analysis.AnalysisState
import com.zjr.hesimusic.loop.analysis.LoopAnalysisManager
import com.zjr.hesimusic.loop.analysis.LoopCandidateKt
import com.zjr.hesimusic.loop.analysis.Pcm16Segment
import com.zjr.hesimusic.loop.analysis.PcmDecoder
import com.zjr.hesimusic.loop.analysis.RingChunkedSeeder
import com.zjr.hesimusic.loop.analysis.WaveformPeaks
import com.zjr.hesimusic.loop.LoopWindow
import com.zjr.hesimusic.loop.audio.LoopPcmEngine
import com.zjr.hesimusic.ui.loop.state.AnalysisPhase
import com.zjr.hesimusic.ui.loop.state.DragState
import com.zjr.hesimusic.ui.loop.state.LoopHandle
import com.zjr.hesimusic.ui.loop.state.LoopPageEffect
import com.zjr.hesimusic.ui.loop.state.LoopPageEvent
import com.zjr.hesimusic.ui.loop.state.LoopPageMode
import com.zjr.hesimusic.ui.loop.state.LoopPageReducer
import com.zjr.hesimusic.ui.loop.state.LoopPageState
import com.zjr.hesimusic.ui.loop.state.PlaybackState
import com.zjr.hesimusic.ui.loop.state.PrefillState
import com.zjr.hesimusic.ui.loop.state.window
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
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

/** 旧版粗粒度页面状态。保留给兼容读取;UI 现在直接读 [LoopViewModel.pageState]。 */
sealed interface LoopScreenState {
    data object Skeleton : LoopScreenState

    data class Ready(val result: LoopResult) : LoopScreenState

    data class Failed(val message: String) : LoopScreenState
}

/**
 * 分析/预览页 ViewModel(契约 docs/loop-v3-ui-contract.md §2)。
 *
 * 第二轮:页面唯一事实源 = [pageState](纯 [LoopPageState])。
 * - UI 只 dispatch [LoopPageEvent],状态迁移只发生在 [LoopPageReducer];
 * - 所有副作用由 [LoopPageEffectRunner] 顺序执行;
 * - 旧公开 API(setMode/togglePlay/applyCandidate/...)保留为 thin wrapper,内部转 dispatch,
 *   让 LoopScreen 可以逐步切换而不必一次性大改。
 *
 * 仍然保留的非页面状态:
 * - [_notice]:一次性 Toast;
 * - [_peaks]:波形异步补解码资源;
 * - prefillJob/prefillToken:异步预填任务身份;
 * - anchorMs/anchorElapsedRealtime:60fps 播放头外推锚点。
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

        /** 分片预填片长:2s(第一片落地即可起播,其余后台补)。 */
        private const val PREFILL_CHUNK_MS = RingChunkedSeeder.DEFAULT_CHUNK_MS

        // 前缀上限/长窗口阈值见 LoopPagePrefillPolicy(可单测)。

        /** 预览侧等待 sink 就绪的重试间隔与上限(约 60s)。 */
        private const val PREVIEW_SEED_RETRY_DELAY_MS = 100L
        private const val PREVIEW_SEED_RETRY_LIMIT = 600
    }

    val songId: Long = savedStateHandle.get<Long>(ARG_SONG_ID) ?: -1L

    private val _song = MutableStateFlow<Song?>(null)
    val song: StateFlow<Song?> = _song.asStateFlow()

    private val _pageState = MutableStateFlow<LoopPageState>(LoopPageState.Loading)

    /** 页面唯一事实源。 */
    val pageState: StateFlow<LoopPageState> = _pageState.asStateFlow()

    private val _peaks = MutableStateFlow<WaveformPeaks?>(null)

    /** 波形峰值(异步补解码;不在页面状态机里)。 */
    val peaks: StateFlow<WaveformPeaks?> = _peaks.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)

    /** 一次性提示(Toast);UI 消费后调 [clearNotice]。 */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    /** 分析管理器状态(兼容旧 UI;新 UI 读 pageState 的 Analyzing)。 */
    val analysisState: StateFlow<AnalysisState> = analysisManager.state

    // ---- 兼容旧 UI 的派生流(不再单独维护;全部来自 pageState) ----
    val aMs: StateFlow<Long> = pageState
        .map { (it as? LoopPageState.Ready)?.aMs ?: 0L }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    val bMs: StateFlow<Long> = pageState
        .map { (it as? LoopPageState.Ready)?.bMs ?: 0L }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    val mode: StateFlow<PreviewMode> = pageState
        .map { (it as? LoopPageState.Ready)?.mode?.toPreviewMode() ?: PreviewMode.FULL }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PreviewMode.FULL)

    val selectedCandidate: StateFlow<Int> = pageState
        .map { (it as? LoopPageState.Ready)?.selectedCandidate ?: 0 }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val durationMs: StateFlow<Long> = pageState
        .map { (it as? LoopPageState.Ready)?.durationMs ?: 0L }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    val positionMs: StateFlow<Long> = pageState
        .map { (it as? LoopPageState.Ready)?.positionMs ?: 0L }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    /** UI 播放键图标:Playing / AwaitingPrefill 都显示“暂停”。 */
    val isPlaying: StateFlow<Boolean> = pageState
        .map {
            val playback = (it as? LoopPageState.Ready)?.playback
            playback is PlaybackState.Playing || playback is PlaybackState.AwaitingPrefill
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** 环缓冲预填中(含 AwaitingPrefill);UI 显示「正在准备环缓冲…」。 */
    val ringPreparing: StateFlow<Boolean> = pageState
        .map { (it as? LoopPageState.Ready)?.prefill is PrefillState.InProgress }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** 兼容旧 UI 的粗粒度状态。 */
    val screen: StateFlow<LoopScreenState> = combine(pageState, _peaks) { state, peaks ->
        when (state) {
            LoopPageState.Loading -> LoopScreenState.Skeleton
            is LoopPageState.Analyzing -> LoopScreenState.Skeleton
            is LoopPageState.Failed -> LoopScreenState.Failed(state.message)
            is LoopPageState.Ready -> LoopScreenState.Ready(
                LoopResult(
                    songId = state.songId,
                    candidates = state.candidates,
                    sampleRate = state.sampleRate,
                    peaks = peaks,
                    durationMs = state.durationMs,
                    confirmed = state.confirmed,
                ),
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LoopScreenState.Skeleton)

    // ---- 预览播放器透传(播放器自身的观测,不属于页面状态机) ----
    val buffering: StateFlow<Boolean> = previewPlayer.buffering
    val isReplaying: StateFlow<Boolean> = previewPlayer.isReplaying
    val ringReady: StateFlow<Boolean> = previewPlayer.ringReady

    private val _backRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** effect NavigateBack / 删除成功后的返回请求;LoopScreen collect 后调 onBack()。 */
    val backRequests: SharedFlow<Unit> = _backRequests

    private var prefillJob: Job? = null
    private var prefillToken: Any? = null

    private var onSavedCallback: ((Song) -> Unit)? = null

    /** 上一次 SeekTo 是否已经真正应用到已 prepare 的播放器;用于避免 Play 二次 seek。 */
    private var seekAppliedForNextPlay: Long? = null

    // 播放头插值锚点:positionMs 每次 emission 重置锚点,UI 以 60fps 外推
    @Volatile
    private var anchorMs = 0L

    @Volatile
    private var anchorElapsedRealtime = 0L

    /** 确认并保存已成功:退出时不再恢复原播放(由保存路径接管)。 */
    @Volatile
    var savedAndPlayed: Boolean = false
        private set

    private val dispatchLock = Any()

    private val effectRunner = LoopPageEffectRunner(object : LoopPageEffectHost {

        override fun play(mode: LoopPageMode, startMs: Long, seek: Boolean) {
            val song = _song.value ?: return
            if (!ensurePreview(song)) return
            val appliedSeek = seekAppliedForNextPlay
            seekAppliedForNextPlay = null
            try {
                val previewMode = mode.toPreviewMode()
                if (seek || appliedSeek != startMs || !previewPlayer.isPrepared) {
                    previewPlayer.play(previewMode, startMs)
                } else {
                    previewPlayer.playFromCurrent(previewMode)
                }
                anchorMs = startMs
                anchorElapsedRealtime = SystemClock.elapsedRealtime()
            } catch (e: Exception) {
                Log.w(TAG, "preview play failed", e)
                _notice.value = "预览启动失败，请重试"
            }
        }

        override fun pause() {
            seekAppliedForNextPlay = null
            previewPlayer.pause()
        }

        override fun seekTo(positionMs: Long) {
            if (!previewPlayer.isPrepared) {
                seekAppliedForNextPlay = null
                return
            }
            previewPlayer.seekToDisplayMs(positionMs)
            seekAppliedForNextPlay = positionMs
            anchorMs = positionMs
            anchorElapsedRealtime = SystemClock.elapsedRealtime()
        }

        override fun startPrefill(window: LoopWindow) {
            startPrefillInternal(window)
        }

        override fun cancelPrefill() {
            cancelPrefillInternal()
        }

        override fun persistConfirm(
            songId: Long,
            window: LoopWindow,
            onResult: (Boolean) -> Unit,
        ) {
            val ready = _pageState.value as? LoopPageState.Ready
            if (ready == null || ready.sampleRate <= 0) {
                onResult(false)
                return
            }
            val sr = ready.sampleRate
            val startSamples = (window.loopStartUs * sr / 1_000_000.0).roundToLong()
            val endSamples = (window.loopEndUs * sr / 1_000_000.0).roundToLong()
            val source = ready.candidates.getOrNull(ready.selectedCandidate)
            val candidate = LoopCandidateKt(
                startSamples = startSamples,
                endSamples = endSamples,
                score = source?.score ?: 0.0,
                noteDistance = source?.noteDistance ?: 0.0,
                loudnessDifference = source?.loudnessDifference ?: 0.0,
            )
            viewModelScope.launch {
                val ok = try {
                    analysisManager.confirmAndSave(songId, candidate, sr, ready.durationMs)
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "confirmAndSave failed", e)
                    false
                }
                onResult(ok)
            }
        }

        override fun persistDemote(songId: Long, onResult: (Boolean) -> Unit) {
            viewModelScope.launch {
                val ok = try {
                    analysisManager.demoteFinal(songId)
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "demoteFinal failed", e)
                    false
                }
                onResult(ok)
            }
        }

        override fun persistDelete(songId: Long, onResult: (Boolean) -> Unit) {
            viewModelScope.launch {
                val ok = try {
                    analysisManager.deleteResult(songId)
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "deleteResult failed", e)
                    false
                }
                onResult(ok)
            }
        }

        override fun onConfirmSaved() {
            val song = _song.value ?: return
            savedAndPlayed = true
            releasePreview()
            val callback = onSavedCallback
            onSavedCallback = null
            callback?.invoke(song)
        }

        override fun toast(text: String) {
            _notice.value = text
        }

        override fun navigateBack() {
            _backRequests.tryEmit(Unit)
        }

        override fun restartAnalysis() {
            val song = _song.value ?: return
            viewModelScope.launch { runAnalysis(song) }
        }
    })

    init {
        viewModelScope.launch {
            analysisManager.state.collect { state -> handleAnalysisState(state) }
        }
        viewModelScope.launch {
            previewPlayer.positionMs.collect { ms ->
                val ready = _pageState.value as? LoopPageState.Ready
                if (ready != null && ready.playback is PlaybackState.AwaitingPrefill) {
                    // 00009:等待预填期间冻结展示位置,避免“进度条在动但没声音”。
                    return@collect
                }
                anchorMs = ms
                anchorElapsedRealtime = SystemClock.elapsedRealtime()
                dispatch(LoopPageEvent.Tick(ms))
            }
        }
        viewModelScope.launch {
            previewPlayer.error.collect { err ->
                if (err != null) _notice.value = err
            }
        }
        viewModelScope.launch { resolveEntry() }
    }

    // ------------------------------------------------------------------ 状态机入口

    /** UI/内部唯一状态变更入口:纯 reduce + 顺序执行 effects。 */
    fun dispatch(event: LoopPageEvent) {
        var effects: List<LoopPageEffect> = emptyList()
        synchronized(dispatchLock) {
            val current = _pageState.value
            val (next, produced) = LoopPageReducer.reduce(current, event)
            if (next !== current || produced.isNotEmpty()) {
                _pageState.value = next
                effects = produced
            }
        }
        if (effects.isNotEmpty()) {
            effectRunner.run(effects)
        }
    }

    // ------------------------------------------------------------------ 入口

    private suspend fun resolveEntry() {
        val song = try {
            songDao.getSongsByIds(listOf(songId)).firstOrNull()
        } catch (e: Exception) {
            Log.w(TAG, "load song failed", e)
            null
        }
        _song.value = song
        if (song == null) {
            dispatch(LoopPageEvent.AnalysisFailed("未找到歌曲"))
            return
        }
        if (song.isCue) {
            dispatch(LoopPageEvent.AnalysisFailed("CUE 分轨暂不支持无缝循环"))
            return
        }
        val row = try {
            analysisManager.getResultOnce(songId)
        } catch (e: Exception) {
            Log.w(TAG, "query song_loop failed", e)
            null
        }
        if (row != null) {
            val duration = maxOf(song.duration, row.loopEndMs, MIN_LOOP_LENGTH_MS + 1L)
            _peaks.value = null
            val candidate = LoopCandidateKt(
                startSamples = row.loopStartSamples,
                endSamples = row.loopEndSamples,
                score = row.score,
                noteDistance = row.noteDistance,
                loudnessDifference = row.loudnessDifference,
            )
            dispatch(
                LoopPageEvent.AnalysisDone(
                    songId = song.id,
                    candidates = listOf(candidate),
                    sampleRate = row.sampleRate,
                    durationMs = duration,
                    confirmed = row.confirmed,
                ),
            )
            refillPeaks(song)
        } else {
            // 无行:Analyzing + RestartAnalysis(由 effect runner 调用 analyze)。
            dispatch(LoopPageEvent.Enter(false))
        }
    }

    private suspend fun handleAnalysisState(state: AnalysisState) {
        when (state) {
            is AnalysisState.Decoding -> {
                if (_pageState.value is LoopPageState.Analyzing) {
                    dispatch(LoopPageEvent.AnalysisProgress(AnalysisPhase.DECODING, state.progress))
                }
            }

            is AnalysisState.Analyzing -> {
                if (_pageState.value is LoopPageState.Analyzing) {
                    dispatch(LoopPageEvent.AnalysisProgress(AnalysisPhase.ANALYZING, state.progress))
                }
            }

            is AnalysisState.Done -> {
                if (_pageState.value !is LoopPageState.Analyzing) return
                if (state.songId != songId) return
                _peaks.value = state.peaks
                val confirmed = runCatching {
                    analysisManager.getResultOnce(songId)?.confirmed ?: false
                }.getOrDefault(false)
                dispatch(
                    LoopPageEvent.AnalysisDone(
                        songId = state.songId,
                        candidates = state.candidates,
                        sampleRate = state.sampleRate,
                        durationMs = state.durationMs,
                        confirmed = confirmed,
                    ),
                )
            }

            is AnalysisState.Failed -> {
                if (_pageState.value is LoopPageState.Analyzing) {
                    dispatch(LoopPageEvent.AnalysisFailed(state.message))
                }
            }

            AnalysisState.Idle -> Unit
        }
    }

    private suspend fun runAnalysis(song: Song) {
        val accepted = try {
            analysisManager.analyze(song)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "analyze failed", e)
            dispatch(LoopPageEvent.AnalysisFailed(e.message ?: "分析失败"))
            return
        }
        if (!accepted) {
            _notice.value = "已有分析进行中"
        }
    }

    /** 波形补解码(契约 §2.1):解码成功前 UI 以网格降级,不阻塞页面。 */
    private fun refillPeaks(song: Song) {
        viewModelScope.launch {
            val peaks = try {
                withContext(Dispatchers.Default) {
                    val mono = pcmDecoder.decodeMono(song.filePath)
                    WaveformPeaks.buildPeaks(mono.data)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "refill peaks failed: " + e.message)
                null
            }
            if (peaks == null) return@launch
            val ready = _pageState.value as? LoopPageState.Ready ?: return@launch
            if (ready.songId != song.id) return@launch
            _peaks.value = peaks
        }
    }

    // ------------------------------------------------------------------ 分片预填

    private fun startPrefillInternal(window: LoopWindow) {
        val song = _song.value ?: return
        val current = _pageState.value as? LoopPageState.Ready ?: return
        if (current.window() != window) return
        if (song.isCue) {
            viewModelScope.launch {
                yield()
                dispatch(LoopPageEvent.PrefillFailed("CUE 分轨暂不支持无缝循环"))
            }
            return
        }

        // 269s 这类长窗口只解 [A, A+12s);<=30s 保持整窗。seederWindow 只影响
        // “解多少”,seed 仍写进完整 A/B 窗口的 ring(两者起点都是 A,帧号对齐)。
        val seederWindow = LoopPagePrefillPolicy.prefillWindow(window)
        val prefixOnly = seederWindow != window
        val totalMs = (seederWindow.lengthUs / 1000L).coerceAtLeast(0L)

        prefillJob?.cancel()
        val token = Any()
        prefillToken = token

        // 先把**完整**窗口写给音频层,分片 seed 才能通过;不 seek/不播放。
        previewPlayer.applyWindowForPrefill(window)
        dispatch(LoopPageEvent.PrefillStarted)

        if (previewPlayer.isRingReadyFor(window)) {
            // 该窗口已经完整(上一轮预填/捕获):先 yield 再补 Ready。
            // viewModelScope 用 Main.immediate,直接 launch 会在当前 effect 批次里同步执行,
            // 导致 Play 跑到同批 SeekTo 之前;yield 保证外层 runner 先完成。
            viewModelScope.launch {
                yield()
                dispatch(LoopPageEvent.PrefillReady)
            }
            return
        }

        prefillJob = viewModelScope.launch {
            try {
                val seeder = RingChunkedSeeder(PREFILL_CHUNK_MS)
                val result = withContext(Dispatchers.Default) {
                    seeder.run(
                        // 只解前缀;但 seed 写完整窗口的 ring。
                        window = seederWindow,
                        // 播放捕获/上一轮预填可能已覆盖前缀:从它之后继续追加。
                        startFrame = previewPlayer.ringCoveredFrames,
                        decode = { startUs, endUs ->
                            pcmDecoder.decodePcm16Range(song.filePath, startUs, endUs)
                        },
                        seed = { startFrame, segment ->
                            val seeded = seedPreviewChunk(song, window, startFrame, segment)
                            if (seeded && segment.sampleRate > 0) {
                                val coveredMs = (
                                    (startFrame + segment.frames) * 1000L / segment.sampleRate
                                    ).coerceIn(0L, totalMs)
                                val progress = if (totalMs > 0L) {
                                    coveredMs.toFloat() / totalMs.toFloat()
                                } else {
                                    0f
                                }
                                withContext(Dispatchers.Main) {
                                    dispatch(
                                        LoopPageEvent.PrefillProgress(
                                            progress = progress,
                                            coveredMs = coveredMs,
                                            totalMs = totalMs,
                                        ),
                                    )
                                }
                            }
                            seeded
                        },
                    )
                }
                if (prefillToken !== token) return@launch
                val latest = _pageState.value as? LoopPageState.Ready ?: return@launch
                if (latest.window() != window) return@launch

                Log.d(
                    TAG,
                    "ring prefill complete=" + result.complete +
                        " chunks=" + result.chunks +
                        " frames=" + result.appendedFrames +
                        " prefixOnly=" + prefixOnly +
                        " targetMs=" + totalMs +
                        " covered=" + previewPlayer.ringCoveredFrames +
                        " stop=" + result.stopReason + " window=" + window,
                )
                if (result.complete) {
                    if (prefixOnly) {
                        // 前缀就绪即可起播;不再等整环。
                        dispatch(LoopPageEvent.PrefillPartialReady(totalMs, totalMs))
                    } else {
                        dispatch(LoopPageEvent.PrefillReady)
                    }
                } else if (result.stopReason != null) {
                    dispatch(LoopPageEvent.PrefillFailed(result.stopReason))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (prefillToken === token) {
                    dispatch(LoopPageEvent.PrefillFailed(e.message ?: "环缓冲预填失败"))
                }
            } finally {
                if (prefillToken === token) {
                    prefillToken = null
                    prefillJob = null
                }
            }
        }
    }



    /**
     * 预览侧追加一片。首片可能早于用户按下播放:此时音频层还没有输出格式
     * (PENDING),后台等待重试(取消即退出),不阻塞 UI、不丢数据。
     */
    private suspend fun seedPreviewChunk(
        song: Song,
        window: LoopWindow,
        startFrame: Long,
        segment: Pcm16Segment,
    ): Boolean {
        var attempt = 0
        while (true) {
            val latest = _pageState.value as? LoopPageState.Ready
            if (latest == null || latest.window() != window) return false
            val result = previewPlayer.seedRingRange(
                window,
                startFrame,
                segment.data,
                segment.sampleRate,
                segment.channels,
            )
            if (result.isInstalled) return true
            if (result.status != LoopPcmEngine.SeedStatus.PENDING &&
                result.status != LoopPcmEngine.SeedStatus.REJECTED_WINDOW
            ) {
                // 格式/边界等硬拒绝:重试不会成功,停止追加(走音频层捕获兜底)。
                return false
            }
            attempt += 1
            if (attempt > PREVIEW_SEED_RETRY_LIMIT) {
                Log.d(TAG, "ring prefill gave up: " + result.status + " window=" + window)
                return false
            }
            delay(PREVIEW_SEED_RETRY_DELAY_MS)
        }
    }

    private fun cancelPrefillInternal() {
        prefillJob?.cancel()
        prefillJob = null
        prefillToken = null
    }

    // ------------------------------------------------------------------ 预览

    /** 按当前 pageState 的 A/B 建立预览(幂等;已 prepare 则复用)。 */
    private fun ensurePreview(song: Song): Boolean {
        if (previewPlayer.isPrepared) return true
        val ready = _pageState.value as? LoopPageState.Ready ?: return false
        return try {
            previewPlayer.prepare(song, ready.aMs, ready.bMs)
            true
        } catch (e: Exception) {
            Log.w(TAG, "prepare preview failed", e)
            _notice.value = "预览启动失败，请重试"
            false
        }
    }

    private fun modeStartMs(mode: LoopPageMode, aMs: Long, bMs: Long): Long = when (mode) {
        LoopPageMode.FULL -> 0L
        LoopPageMode.LOOP_ONLY -> (aMs - PreviewPlayer.LOOP_PREROLL_MS).coerceAtLeast(0L)
        // 极短循环段 B−5s < A 时取 A,与 reducer/PreviewPlayer 保持一致。
        LoopPageMode.SEAM -> maxOf(aMs, bMs - PreviewPlayer.SEAM_LEAD_MS)
        LoopPageMode.FREE -> 0L
    }

    // ------------------------------------------------------------------ 旧 API thin wrappers

    /** 四模式试听:同模式重复点击由 reducer 恒等 no-op(00008)。 */
    fun setMode(mode: PreviewMode) {
        dispatch(LoopPageEvent.SwitchMode(mode.toPageMode()))
    }

    fun togglePlay() {
        dispatch(LoopPageEvent.TogglePlay)
    }

    /** 停止:暂停并回到当前模式起点。 */
    fun stopPreview() {
        val ready = _pageState.value as? LoopPageState.Ready ?: return
        if (ready.playback is PlaybackState.Playing ||
            ready.playback is PlaybackState.AwaitingPrefill
        ) {
            dispatch(LoopPageEvent.TogglePlay)
        }
        dispatch(LoopPageEvent.Seek(modeStartMs(ready.mode, ready.aMs, ready.bMs)))
    }

    /** 波形点击 seek(展示口径);不自动开始播放。 */
    fun seekToDisplayMs(ms: Long) {
        dispatch(LoopPageEvent.Seek(ms))
    }

    /** 当前拖动位置 → A(松手语义:立即应用窗口)。 */
    fun setAFromCurrent() {
        val position = currentPositionMs()
        dispatch(LoopPageEvent.DragAStart(position))
        dispatch(LoopPageEvent.DragAEnd)
        dispatch(LoopPageEvent.CommitWindow)
    }

    /** 当前拖动位置 → B(松手语义:立即应用窗口)。 */
    fun setBFromCurrent() {
        val position = currentPositionMs()
        dispatch(LoopPageEvent.DragBStart(position))
        dispatch(LoopPageEvent.DragBEnd)
        dispatch(LoopPageEvent.CommitWindow)
    }

    fun seekToA() {
        val ready = _pageState.value as? LoopPageState.Ready ?: return
        dispatch(LoopPageEvent.Seek(ready.aMs))
    }

    /** 跳到 B−5s(接缝检查起点)。 */
    fun seekToSeam() {
        val ready = _pageState.value as? LoopPageState.Ready ?: return
        dispatch(
            LoopPageEvent.Seek(
                maxOf(ready.aMs, ready.bMs - PreviewPlayer.SEAM_LEAD_MS),
            ),
        )
    }

    /** 候选点击:应用 A/B 并试听当前模式(契约 §2.3)。 */
    fun applyCandidate(index: Int) {
        dispatch(LoopPageEvent.SelectCandidate(index))
    }

    /** 拖动/精调:只改视觉状态(不动预览窗口),松手由 [commitWindow] 应用。 */
    fun dragTo(handleA: Boolean, ms: Long) {
        val ready = _pageState.value as? LoopPageState.Ready ?: return
        val handle = if (handleA) LoopHandle.A else LoopHandle.B
        val dragging = ready.drag as? DragState.Dragging
        when {
            dragging == null -> dispatch(
                if (handleA) LoopPageEvent.DragAStart(ms) else LoopPageEvent.DragBStart(ms),
            )

            dragging.handle == handle -> dispatch(
                if (handleA) LoopPageEvent.DragAMove(ms) else LoopPageEvent.DragBMove(ms),
            )
        }
    }

    /** 松手才调:把当前 A/B 应用给预览播放器(新窗口会让 ring 失效并重新预填)。 */
    fun commitWindow() {
        dispatch(LoopPageEvent.CommitWindow)
    }

    /** 确认并保存 → 落库成功后 release + onSaved(播放该歌 + REPLACE_ONE + pop)。 */
    fun confirmAndSave(onSaved: (Song) -> Unit) {
        onSavedCallback = onSaved
        dispatch(LoopPageEvent.ConfirmAndSave)
    }

    /** 页内「取消最终结果」:confirmed 1→0(行保留),页面留在当前内存预览。 */
    fun demoteFinal() {
        dispatch(LoopPageEvent.Demote)
    }

    /** 页内「删除分析结果」:持久化成功后由 effect runner 发 NavigateBack。 */
    fun deleteResult() {
        dispatch(LoopPageEvent.Delete)
    }

    /** 页内「重新分析」/失败重试。 */
    fun startAnalysis() {
        val managerState = analysisManager.state.value
        if (managerState is AnalysisState.Decoding || managerState is AnalysisState.Analyzing) {
            _notice.value = "已有分析进行中"
            return
        }
        dispatch(LoopPageEvent.ManualReanalyze)
    }

    /** 60fps 播放头插值:锚点 + 时间外推,按当前窗口折返;positionMs 仅作校正。 */
    fun interpolatedPositionMs(nowElapsedRealtime: Long): Long {
        val ready = _pageState.value as? LoopPageState.Ready ?: return 0L
        if (ready.playback !is PlaybackState.Playing) return ready.positionMs
        if (anchorElapsedRealtime == 0L) return ready.positionMs

        val duration = ready.durationMs.coerceAtLeast(0L)
        val extrapolated = anchorMs + (nowElapsedRealtime - anchorElapsedRealtime)
        if (ready.mode == LoopPageMode.FREE) return extrapolated.coerceIn(0L, duration)

        val a = ready.aMs
        val b = ready.bMs
        val length = b - a
        if (length <= 0L) return extrapolated.coerceIn(0L, duration)
        return if (extrapolated < a) {
            extrapolated.coerceIn(0L, duration)
        } else {
            a + ((extrapolated - a) % length)
        }
    }

    private fun currentPositionMs(): Long =
        (_pageState.value as? LoopPageState.Ready)?.positionMs ?: 0L

    // ------------------------------------------------------------------ 释放 / 提示

    fun releasePreview() {
        cancelPrefillInternal()
        previewPlayer.release()
    }

    fun clearNotice() {
        _notice.value = null
    }

    override fun onCleared() {
        cancelPrefillInternal()
        previewPlayer.release()
        super.onCleared()
    }
}
