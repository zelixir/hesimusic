package com.zjr.hesimusic.ui.loop.state

import com.zjr.hesimusic.loop.LoopWindow
import com.zjr.hesimusic.loop.analysis.LoopCandidateKt
import kotlin.math.roundToLong

/**
 * 分析/预览页的**纯模型**状态机（第一轮：只做模型与矩阵测试，不接 UI）。
 *
 * 设计目标：把原先散落在 LoopViewModel 多个 `mutableStateOf`/`StateFlow` 字段里的
 * mode / isPlaying / ringPreparing / candidates / selectedCandidate / A-B / dragging /
 * confirmed / prefill / position 收敛成**一个不可变状态**，所有副作用只以
 * [LoopPageEffect] 声明，由 [LoopPageReducer] 纯函数计算。
 *
 * 本文件禁止依赖 Android / 播放器 / DB：只允许 [LoopWindow] 与 [LoopCandidateKt]
 * 这两个纯数据模型。
 */

/** 四种试听模式。与 ui/loop/PreviewPlayer.kt 的 PreviewMode 一一对应（接 UI 时做映射）。 */
enum class LoopPageMode {
    FULL,
    LOOP_ONLY,
    SEAM,
    FREE,
}

/** 拖拽手柄。 */
enum class LoopHandle { A, B }

/** 分析阶段：解码（0→0.5）/ 候选分析（0.5→1.0）。 */
enum class AnalysisPhase { DECODING, ANALYZING }

/**
 * 后台环缓冲预填状态。
 *
 * - [NotStarted]：未开始（无候选 / 刚进入 Ready 且没有窗口）；
 * - [InProgress]：解码预填中；coveredMs/totalMs 供 UI 显示「准备环缓冲 8s/12s」；
 * - [Partial]：**前缀已就绪**（长窗口只填 [A, A+12s)），可起播；后台/整环可能仍未完整；
 * - [Ready]：当前提交窗口的 ring 已完整；
 * - [Failed]：预填失败（UI 保留降级路径，不阻塞试听）。
 */
sealed interface PrefillState {
    data object NotStarted : PrefillState
    data class InProgress(
        val progress: Float = 0f,
        val coveredMs: Long = 0L,
        val totalMs: Long = 0L,
    ) : PrefillState
    data class Partial(
        val coveredMs: Long,
        val totalMs: Long,
    ) : PrefillState
    data object Ready : PrefillState
    data class Failed(val message: String) : PrefillState

    /**
     * SOURCE 模式：首块 PCM 加载中（1 秒块，通常 20~40ms）。
     * 不阻塞播放按钮/拖动；UI 只在超过 300ms 时短暂提示。
     */
    data object FirstBlockLoading : PrefillState
}

/**
 * 播放状态。
 *
 * [AwaitingPrefill] 是 00009 的核心建模：换窗口/换候选后，在预填完成前**不允许**
 * 声称有音频输出；预填就绪（或失败降级）后 **必须** 产生 [LoopPageEffect.Play]。
 * 模型允许 Tick 继续更新位置；当前 UI 在 AwaitingPrefill 时冻结展示位置，
 * 避免出现“进度条在动但没声音”的假象。
 */
sealed interface PlaybackState {
    data object Stopped : PlaybackState
    data object Playing : PlaybackState
    data object Paused : PlaybackState
    data class AwaitingPrefill(
        val resumeMode: LoopPageMode,
        val resumeAtMs: Long,
    ) : PlaybackState
}

/** 拖拽状态。拖动中只改视觉 A/B，松手后的 CommitWindow 才应用窗口。 */
sealed interface DragState {
    data object Idle : DragState
    data class Dragging(val handle: LoopHandle, val ms: Long) : DragState
}

/**
 * 页面顶层状态。
 *
 * 关键不变量（见 LoopPageReducerTest 的矩阵不变量测试）：
 * - [Ready] 中 `0 <= aMs < bMs <= durationMs` 且 `bMs - aMs >= MIN_LOOP_LENGTH_MS`；
 * - `positionMs in [0, durationMs]`；
 * - 有候选时 `selectedCandidate in candidates.indices`，无候选时为 0；
 * - `drag == Dragging(A, ms)` 时 `aMs == ms`，B 同理；
 * - `playback is AwaitingPrefill` 时 `prefill is InProgress`（因此必定有一个后续
 *   PrefillReady/PrefillFailed 会补发 Play，杜绝「位置在动但无输出」）。
 */
sealed interface LoopPageState {
    /** 首帧骨架 / 入口解析中（歌曲查询、song_loop 查询）。 */
    data object Loading : LoopPageState

    /** 解码 / 分析中。进度已 clamp 到 [0,1]。 */
    data class Analyzing(
        val phase: AnalysisPhase,
        val progress: Float,
    ) : LoopPageState

    /** 结果页（可试听、可拖拽、可保存）。 */
    data class Ready(
        val songId: Long,
        val durationMs: Long,
        val sampleRate: Int,
        val mode: LoopPageMode,
        val aMs: Long,
        val bMs: Long,
        val candidates: List<LoopCandidateKt>,
        val selectedCandidate: Int,
        val confirmed: Boolean,
        val prefill: PrefillState,
        val playback: PlaybackState,
        val positionMs: Long,
        val drag: DragState,
        /** true = 当前页由 SOURCE 数据源驱动（预览页专用；M3′ 模式为 false）。 */
        val sourceMode: Boolean = false,
        /**
         * true = 循环区外自由试听(issue 00023)。
         *
         * 用户把播放头拖到 [aMs, bMs) 之外时置 true:循环窗口不生效,按**文件时间**播放,
         * 进度条/播放头都是真实媒体时间(不折返);拖回区间内(或显式点模式/选候选)自动恢复循环。
         *
         * 语义取舍(见 PreviewPlayer.seekToDisplayMs 注释):
         * - 区外 seek **允许且必须落到用户点的位置** —— 分析页的核心动作就是试听别的小节来调 A/B,
         *   把目标折回 [A,B) 等于让"其它段落"无法试听;
         * - 回 [A,B) 内的 seek **自动恢复循环**(同一 A/B/模式),因为这一次 seek 的意图就是
         *   "回到循环区",没有理由再让用户手动点一次模式;
         * - 自然播放(不 seek)穿过 A/B 不会自动恢复循环:自由试听就是要连续听完某一段,
         *   播到区间里突然开始环回会打断试听。
         */
        val auditionOutside: Boolean = false,
    ) : LoopPageState

    /** 分析失败 / 入口守卫失败（未找到歌曲、CUE 分轨等）。 */
    data class Failed(val message: String) : LoopPageState
}

/** 页面事件（UI 只派发事件，不直接改状态）。 */
sealed interface LoopPageEvent {
    /**
     * 进入页面。
     *
     * @param hasSavedResult true = 已有 song_loop 行，VM 会加载后派发 [AnalysisDone]；
     *                       false = 无行，reducer 直接进入 Analyzing 并要求 [LoopPageEffect.RestartAnalysis]。
     */
    data class Enter(val hasSavedResult: Boolean) : LoopPageEvent

    data class AnalysisProgress(
        val phase: AnalysisPhase,
        val progress: Float,
    ) : LoopPageEvent

    /**
     * 分析完成 / 从 `song_loop` 行恢复结果页。
     *
     * @param selectedCandidate 恢复时的初始选中下标(issue 00016:重进页面要停在已确认那个候选);
     *   越界自动夹到 `0..size-1`,空列表时为 0。新分析路径不传(默认 0 = score 最优)。
     */
    data class AnalysisDone(
        val songId: Long,
        val candidates: List<LoopCandidateKt>,
        val sampleRate: Int,
        val durationMs: Long,
        val confirmed: Boolean = false,
        val selectedCandidate: Int = 0,
        /** true = 页面使用 SOURCE 数据源（不启动环缓冲预填）。 */
        val sourceMode: Boolean = false,
    ) : LoopPageEvent

    data class AnalysisFailed(val message: String) : LoopPageEvent

    data object Retry : LoopPageEvent

    /** 页内「重新分析」:Loading / Ready / Failed 都可进入 Analyzing;Analyzing 时 no-op。 */
    data object ManualReanalyze : LoopPageEvent

    data class SelectCandidate(val index: Int) : LoopPageEvent

    data class DragAStart(val ms: Long) : LoopPageEvent
    data class DragAMove(val ms: Long) : LoopPageEvent
    data object DragAEnd : LoopPageEvent
    data class DragBStart(val ms: Long) : LoopPageEvent
    data class DragBMove(val ms: Long) : LoopPageEvent
    data object DragBEnd : LoopPageEvent

    /** 松手后应用当前 A/B（拖拽期间只改视觉，不发窗口副作用）。 */
    data object CommitWindow : LoopPageEvent

    data class SwitchMode(val mode: LoopPageMode) : LoopPageEvent

    data object TogglePlay : LoopPageEvent

    /** 播放器位置 tick（展示口径）。 */
    data class Tick(val positionMs: Long) : LoopPageEvent

    data object PrefillStarted : LoopPageEvent
    data class PrefillProgress(
        val progress: Float,
        val coveredMs: Long = 0L,
        val totalMs: Long = 0L,
    ) : LoopPageEvent

    /** 长窗口只填前缀时的“可起播”信号；效果与 PrefillReady 一样（Awaiting 时补 Play）。 */
    data class PrefillPartialReady(
        val coveredMs: Long,
        val totalMs: Long,
    ) : LoopPageEvent

    data object PrefillReady : LoopPageEvent
    data class PrefillFailed(val message: String) : LoopPageEvent

    data object ConfirmAndSave : LoopPageEvent
    data object Demote : LoopPageEvent
    data object Delete : LoopPageEvent
    data object Back : LoopPageEvent

    /** 波形点击 / 「◀ A」/「◀ B−5s」等显式 seek（不自动开始播放）。 */
    data class Seek(val positionMs: Long) : LoopPageEvent
}

/** 页面要执行的副作用（由接 UI 层的 effect runner 执行；模型自身不执行）。 */
sealed interface LoopPageEffect {
    /** 按模式从 startMs 起播。 */
    data class Play(val mode: LoopPageMode, val startMs: Long) : LoopPageEffect

    data object Pause : LoopPageEffect

    /** 展示口径 seek。 */
    data class SeekTo(val positionMs: Long) : LoopPageEffect

    /** 为 window 启动后台环缓冲预填。 */
    data class StartPrefill(val window: LoopWindow) : LoopPageEffect

    /** 取消正在进行的预填（新窗口/新候选会让旧预填失效）。 */
    data object CancelPrefill : LoopPageEffect

    /** SOURCE 模式：把新 A/B 应用到数据源（新 timeline/cache key，不重建 player）。 */
    data class ApplyWindow(val window: LoopWindow) : LoopPageEffect

    data class PersistConfirm(val songId: Long, val window: LoopWindow) : LoopPageEffect
    data class PersistDemote(val songId: Long) : LoopPageEffect
    data class PersistDelete(val songId: Long) : LoopPageEffect

    data class Toast(val text: String) : LoopPageEffect

    data object NavigateBack : LoopPageEffect

    data object RestartAnalysis : LoopPageEffect
}

/**
 * 页面 reducer：**纯函数**，不碰 Android / 播放器 / DB。
 *
 * 约定：
 * - 非法/重复/过期事件一律返回**同一个 state 实例** + 空 effect（矩阵测试用 assertSame 断言恒等）；
 * - 允许的转换返回新 state + 有序 effect 列表；
 * - [LoopPageEvent.Back] 对所有状态都有效，返回原 state + [LoopPageEffect.NavigateBack]。
 */
object LoopPageReducer {

    /** 与 PreviewPlayer.MIN_LOOP_LENGTH_MS 保持一致。 */
    const val MIN_LOOP_LENGTH_MS = 500L

    /** 与 PreviewPlayer.SEAM_LEAD_MS 保持一致：接缝检查从 B−5s 起播。 */
    const val SEAM_LEAD_MS = 5_000L

    /** 与 PreviewPlayer.LOOP_PREROLL_MS 保持一致。 */
    const val LOOP_PREROLL_MS = 300L

    /** 预填失败降级播放时的提示文案。 */
    const val PREFILL_FALLBACK_TOAST = "环缓冲准备失败，已按直通播放"

    fun reduce(
        state: LoopPageState,
        event: LoopPageEvent,
    ): Pair<LoopPageState, List<LoopPageEffect>> {
        if (event is LoopPageEvent.Back) {
            return state to listOf(LoopPageEffect.NavigateBack)
        }
        if (event is LoopPageEvent.ManualReanalyze) {
            return when (state) {
                is LoopPageState.Analyzing -> noEffects(state)
                is LoopPageState.Ready -> {
                    // 场景 #86:Ready 中重新分析 = 页面切去分析骨架,旧预览必须**立刻停住**,
                    // 否则用户看不见预览页,却能听到它在继续写帧(看不见的预览声)。
                    // Pause 停音频输出并关 SOURCE 闸门;CancelPrefill 停后台预填解码。
                    LoopPageState.Analyzing(AnalysisPhase.DECODING, 0f) to listOf(
                        LoopPageEffect.Pause,
                        LoopPageEffect.CancelPrefill,
                        LoopPageEffect.RestartAnalysis,
                    )
                }
                // Loading/Failed 没有预览可停(从未 Ready 过),保持原行为。
                else -> LoopPageState.Analyzing(AnalysisPhase.DECODING, 0f) to
                    listOf(LoopPageEffect.RestartAnalysis)
            }
        }
        return when (state) {
            is LoopPageState.Loading -> reduceLoading(state, event)
            is LoopPageState.Analyzing -> reduceAnalyzing(state, event)
            is LoopPageState.Ready -> reduceReady(state, event)
            is LoopPageState.Failed -> reduceFailed(state, event)
        }
    }

    // ------------------------------------------------------------------ 非 Ready

    private fun reduceLoading(
        state: LoopPageState.Loading,
        event: LoopPageEvent,
    ): Pair<LoopPageState, List<LoopPageEffect>> = when (event) {
        is LoopPageEvent.Enter -> if (event.hasSavedResult) {
            noEffects(state)
        } else {
            LoopPageState.Analyzing(AnalysisPhase.DECODING, 0f) to listOf(LoopPageEffect.RestartAnalysis)
        }

        is LoopPageEvent.AnalysisProgress ->
            LoopPageState.Analyzing(event.phase, clampProgress(event.progress)) to emptyList()

        is LoopPageEvent.AnalysisDone -> analysisDone(event)

        is LoopPageEvent.AnalysisFailed -> LoopPageState.Failed(event.message) to emptyList()

        else -> noEffects(state)
    }

    private fun reduceAnalyzing(
        state: LoopPageState.Analyzing,
        event: LoopPageEvent,
    ): Pair<LoopPageState, List<LoopPageEffect>> = when (event) {
        is LoopPageEvent.AnalysisProgress ->
            LoopPageState.Analyzing(event.phase, clampProgress(event.progress)) to emptyList()

        is LoopPageEvent.AnalysisDone -> analysisDone(event)

        is LoopPageEvent.AnalysisFailed -> LoopPageState.Failed(event.message) to emptyList()

        else -> noEffects(state)
    }

    private fun reduceFailed(
        state: LoopPageState.Failed,
        event: LoopPageEvent,
    ): Pair<LoopPageState, List<LoopPageEffect>> = when (event) {
        LoopPageEvent.Retry ->
            LoopPageState.Analyzing(AnalysisPhase.DECODING, 0f) to listOf(LoopPageEffect.RestartAnalysis)

        else -> noEffects(state)
    }

    private fun analysisDone(
        event: LoopPageEvent.AnalysisDone,
    ): Pair<LoopPageState, List<LoopPageEffect>> {
        val duration = maxOf(event.durationMs, MIN_LOOP_LENGTH_MS + 1L)
        // issue 00016:恢复路径可能要求"选中已确认那个候选"(而不是 score 最优的第 0 个),
        // 越界一律夹回合法区间;空列表时 selectedCandidate 恒为 0(不变量见 Ready 注释)。
        val selected = event.selectedCandidate
            .coerceIn(0, (event.candidates.size - 1).coerceAtLeast(0))
        val chosen = event.candidates.getOrNull(selected)
        val (aMs, bMs) = if (chosen != null) {
            candidateWindowMs(chosen, event.sampleRate, duration)
        } else {
            0L to duration
        }
        val ready = LoopPageState.Ready(
            songId = event.songId,
            durationMs = duration,
            sampleRate = event.sampleRate,
            mode = LoopPageMode.FULL,
            aMs = aMs,
            bMs = bMs,
            candidates = event.candidates,
            selectedCandidate = selected,
            confirmed = event.confirmed,
            // M3′：预填由 StartPrefill effect 真正启动(播放器未 prepared 时 VM 会推迟)，
            // 因此这里先 NotStarted;PrefillStarted 再进入 InProgress。
            // SOURCE：没有环缓冲预填，首块由数据源按需解码。
            prefill = PrefillState.NotStarted,
            playback = PlaybackState.Stopped,
            positionMs = 0L,
            drag = DragState.Idle,
            sourceMode = event.sourceMode,
        )
        // 场景 #86:重新分析完成后窗口可能变了。必须把新窗口应用到预览播放器,
        // 否则下一次 play 会沿用旧 A/B(页面显示新候选、耳朵却听旧区间)。
        val effects = mutableListOf<LoopPageEffect>(LoopPageEffect.ApplyWindow(ready.window()))
        if (chosen != null && !event.sourceMode) {
            effects += LoopPageEffect.StartPrefill(ready.window())
        }
        return ready to effects
    }

    // ------------------------------------------------------------------ Ready

    private fun reduceReady(
        state: LoopPageState.Ready,
        event: LoopPageEvent,
    ): Pair<LoopPageState, List<LoopPageEffect>> = when (event) {
        is LoopPageEvent.SelectCandidate -> selectCandidate(state, event.index)

        is LoopPageEvent.DragAStart -> dragStart(state, LoopHandle.A, event.ms)
        is LoopPageEvent.DragAMove -> dragMove(state, LoopHandle.A, event.ms)
        LoopPageEvent.DragAEnd -> dragEnd(state, LoopHandle.A)

        is LoopPageEvent.DragBStart -> dragStart(state, LoopHandle.B, event.ms)
        is LoopPageEvent.DragBMove -> dragMove(state, LoopHandle.B, event.ms)
        LoopPageEvent.DragBEnd -> dragEnd(state, LoopHandle.B)

        LoopPageEvent.CommitWindow -> commitWindow(state)

        is LoopPageEvent.SwitchMode -> switchMode(state, event.mode)

        LoopPageEvent.TogglePlay -> togglePlay(state)

        is LoopPageEvent.Tick -> tick(state, event.positionMs)

        LoopPageEvent.PrefillStarted -> prefillStarted(state)
        is LoopPageEvent.PrefillProgress ->
            prefillProgress(state, event.progress, event.coveredMs, event.totalMs)

        is LoopPageEvent.PrefillPartialReady ->
            prefillPartialReady(state, event.coveredMs, event.totalMs)

        LoopPageEvent.PrefillReady -> prefillReady(state)
        is LoopPageEvent.PrefillFailed -> prefillFailed(state, event.message)

        LoopPageEvent.ConfirmAndSave -> confirmAndSave(state)

        LoopPageEvent.Demote -> demote(state)
        LoopPageEvent.Delete -> delete(state)

        is LoopPageEvent.Seek -> seek(state, event.positionMs)

        else -> noEffects(state)
    }

    private fun selectCandidate(
        state: LoopPageState.Ready,
        index: Int,
    ): Pair<LoopPageState, List<LoopPageEffect>> {
        val candidate = state.candidates.getOrNull(index) ?: return noEffects(state)
        val (aMs, bMs) = candidateWindowMs(candidate, state.sampleRate, state.durationMs)
        val startMs = modeStartMs(state.mode, aMs, bMs)

        if (state.sourceMode && state.mode != LoopPageMode.FREE) {
            // SOURCE：候选点击 = 新窗口（新 cache key）+ 立即 seek/play；没有预填等待。
            val next = state.copy(
                selectedCandidate = index,
                aMs = aMs,
                bMs = bMs,
                positionMs = startMs,
                drag = DragState.Idle,
                prefill = PrefillState.FirstBlockLoading,
                playback = PlaybackState.Playing,
                // 选候选是明确的循环试听意图:从自由试听回到循环。
                auditionOutside = false,
            )
            return next to listOf(
                LoopPageEffect.ApplyWindow(next.window()),
                LoopPageEffect.SeekTo(startMs),
                LoopPageEffect.Play(state.mode, startMs),
            )
        }

        // 候选点击本身就是“试听”动作:
        // - 正在播放 / 已在等预填 → AwaitingPrefill(00009:等新 ring,完成必补 Play);
        // - Stopped / Paused(含首次进入)→ 立即起播,先把 sink 配置起来,避免
        //   “等预填 seed 需要 sink,而 sink 又等 Play”的死锁。
        val auditionImmediately = state.playback !is PlaybackState.Playing &&
            state.playback !is PlaybackState.AwaitingPrefill
        val playback = if (auditionImmediately) {
            PlaybackState.Playing
        } else {
            PlaybackState.AwaitingPrefill(state.mode, startMs)
        }
        val next = state.copy(
            selectedCandidate = index,
            aMs = aMs,
            bMs = bMs,
            positionMs = startMs,
            drag = DragState.Idle,
            prefill = PrefillState.InProgress(0f),
            playback = playback,
            // 选候选是明确的循环试听意图:从自由试听回到循环。
            auditionOutside = false,
        )
        val effects = mutableListOf<LoopPageEffect>(
            LoopPageEffect.CancelPrefill,
            LoopPageEffect.StartPrefill(next.window()),
            LoopPageEffect.SeekTo(startMs),
        )
        if (auditionImmediately) {
            effects += LoopPageEffect.Play(state.mode, startMs)
        }
        return next to effects
    }

    private fun dragStart(
        state: LoopPageState.Ready,
        handle: LoopHandle,
        ms: Long,
    ): Pair<LoopPageState, List<LoopPageEffect>> {
        if (state.drag != DragState.Idle) return noEffects(state)
        return when (handle) {
            LoopHandle.A -> {
                val aMs = clampA(state, ms)
                state.copy(aMs = aMs, drag = DragState.Dragging(LoopHandle.A, aMs)) to emptyList()
            }

            LoopHandle.B -> {
                val bMs = clampB(state, ms)
                state.copy(bMs = bMs, drag = DragState.Dragging(LoopHandle.B, bMs)) to emptyList()
            }
        }
    }

    private fun dragMove(
        state: LoopPageState.Ready,
        handle: LoopHandle,
        ms: Long,
    ): Pair<LoopPageState, List<LoopPageEffect>> {
        val dragging = state.drag as? DragState.Dragging ?: return noEffects(state)
        if (dragging.handle != handle) return noEffects(state)
        return when (handle) {
            LoopHandle.A -> {
                val aMs = clampA(state, ms)
                state.copy(aMs = aMs, drag = DragState.Dragging(LoopHandle.A, aMs)) to emptyList()
            }

            LoopHandle.B -> {
                val bMs = clampB(state, ms)
                state.copy(bMs = bMs, drag = DragState.Dragging(LoopHandle.B, bMs)) to emptyList()
            }
        }
    }

    private fun dragEnd(
        state: LoopPageState.Ready,
        handle: LoopHandle,
    ): Pair<LoopPageState, List<LoopPageEffect>> {
        val dragging = state.drag as? DragState.Dragging ?: return noEffects(state)
        if (dragging.handle != handle) return noEffects(state)
        return state.copy(drag = DragState.Idle) to emptyList()
    }

    private fun commitWindow(
        state: LoopPageState.Ready,
    ): Pair<LoopPageState, List<LoopPageEffect>> {
        if (state.auditionOutside) {
            // 自由试听中松手改 A/B:只落窗口(拖拽事件已更新 aMs/bMs),不 seek、不重装源 ——
            // 否则播放头会从"区外试听位置"被拽回 A,等于把用户刚拖到的位置夹掉。
            // ApplyWindow 仅同步窗口记录(播放器在自由试听里 defer 它,不换源),
            // 保证"返回区间"的判定用的是**新的** A/B。
            val next = state.copy(drag = DragState.Idle)
            return next to listOf(LoopPageEffect.ApplyWindow(next.window()))
        }
        val startMs = modeStartMs(state.mode, state.aMs, state.bMs)
        val active = state.playback is PlaybackState.Playing ||
            state.playback is PlaybackState.AwaitingPrefill

        if (state.sourceMode) {
            // SOURCE：A/B 变化只换数据源窗口（新 cache key），不重建 player、不等预填。
            val next = state.copy(
                positionMs = startMs,
                drag = DragState.Idle,
                prefill = if (active && state.mode != LoopPageMode.FREE) {
                    PrefillState.FirstBlockLoading
                } else if (active) {
                    state.prefill
                } else {
                    PrefillState.NotStarted
                },
                playback = if (active) PlaybackState.Playing else state.playback,
            )
            val effects = mutableListOf<LoopPageEffect>(
                LoopPageEffect.ApplyWindow(next.window()),
                LoopPageEffect.SeekTo(startMs),
            )
            if (active) effects += LoopPageEffect.Play(state.mode, startMs)
            return next to effects
        }
        val next = state.copy(
            positionMs = startMs,
            drag = DragState.Idle,
            // 播放中/等待预填:立即开始新预填;暂停/停止:先标记未开始,
            // 由 StartPrefill 真正启动(未 prepare 时 VM 会推迟,避免无尽提示)。
            prefill = if (active) PrefillState.InProgress(0f) else PrefillState.NotStarted,
            // 00009:新窗口会让旧 ring 失效。播放中/已在等预填 → 继续等新预填,
            // 预填完成后补发 Play;暂停/停止态保持原状(只应用窗口,不擅自起播)。
            playback = when (state.playback) {
                PlaybackState.Playing, is PlaybackState.AwaitingPrefill ->
                    PlaybackState.AwaitingPrefill(state.mode, startMs)

                else -> state.playback
            },
        )
        return next to listOf(
            LoopPageEffect.CancelPrefill,
            LoopPageEffect.StartPrefill(next.window()),
            LoopPageEffect.SeekTo(startMs),
        )
    }

    private fun switchMode(
        state: LoopPageState.Ready,
        mode: LoopPageMode,
    ): Pair<LoopPageState, List<LoopPageEffect>> {
        // 拖拽中的模式点击非法:先松手再切模式,避免丢拖拽状态。
        if (state.drag != DragState.Idle) return noEffects(state)

        // 00008(用户口径):重复点击「接缝检查」必须每次都回到 B−5s 重新起播。
        // 同模式也统一产出 SeekTo + Play;由 effect runner 保证同批 SeekTo 后
        // Play 不再二次 raw seek(只 seekToDisplayMs 一次并继续播放,不 release)。
        val startMs = modeStartMs(mode, state.aMs, state.bMs)
        val next = state.copy(
            mode = mode,
            playback = PlaybackState.Playing,
            positionMs = startMs,
            // 点模式 = 明确的循环试听意图:从自由试听回到循环(自由模式本身也不再需要该标记)。
            auditionOutside = false,
            prefill = if (state.sourceMode && mode != LoopPageMode.FREE) {
                PrefillState.FirstBlockLoading
            } else if (state.sourceMode && mode == LoopPageMode.FREE) {
                PrefillState.NotStarted
            } else {
                state.prefill
            },
        )
        return next to listOf(
            LoopPageEffect.SeekTo(startMs),
            LoopPageEffect.Play(mode, startMs),
        )
    }

    private fun togglePlay(
        state: LoopPageState.Ready,
    ): Pair<LoopPageState, List<LoopPageEffect>> = when (state.playback) {
        PlaybackState.Playing -> state.copy(playback = PlaybackState.Paused) to listOf(LoopPageEffect.Pause)
        is PlaybackState.AwaitingPrefill -> state.copy(playback = PlaybackState.Paused) to listOf(LoopPageEffect.Pause)
        PlaybackState.Paused, PlaybackState.Stopped ->
            state.copy(
                playback = PlaybackState.Playing,
                prefill = if (state.sourceMode && state.mode != LoopPageMode.FREE) {
                    PrefillState.FirstBlockLoading
                } else {
                    state.prefill
                },
            ) to listOf(LoopPageEffect.Play(state.mode, state.positionMs))
    }

    private fun tick(
        state: LoopPageState.Ready,
        positionMs: Long,
    ): Pair<LoopPageState, List<LoopPageEffect>> =
        state.copy(positionMs = positionMs.coerceIn(0L, state.durationMs)) to emptyList()

    private fun prefillStarted(
        state: LoopPageState.Ready,
    ): Pair<LoopPageState, List<LoopPageEffect>> {
        if (state.sourceMode) return noEffects(state)
        return when (state.prefill) {
        PrefillState.NotStarted, is PrefillState.Failed ->
            state.copy(prefill = PrefillState.InProgress(0f)) to emptyList()

        else -> noEffects(state)
        }
    }

    private fun prefillProgress(
        state: LoopPageState.Ready,
        progress: Float,
        coveredMs: Long,
        totalMs: Long,
    ): Pair<LoopPageState, List<LoopPageEffect>> {
        if (state.sourceMode) return noEffects(state)
        return when (val prefill = state.prefill) {
        PrefillState.Ready -> noEffects(state)

        is PrefillState.Partial ->
            // 前缀已就绪:背景进度继续推进,但绝不改 PlaybackState/不打断播放。
            state.copy(
                prefill = PrefillState.Partial(
                    coveredMs = coveredMs.coerceAtLeast(prefill.coveredMs),
                    totalMs = if (totalMs > 0L) totalMs else prefill.totalMs,
                ),
            ) to emptyList()

        else -> {
            val previous = prefill as? PrefillState.InProgress
            val nextCovered = coveredMs
                .coerceAtLeast(previous?.coveredMs ?: 0L)
                .coerceAtLeast(0L)
            val nextTotal = if (totalMs > 0L) totalMs else previous?.totalMs ?: 0L
            state.copy(
                prefill = PrefillState.InProgress(
                    progress = clampProgress(progress),
                    coveredMs = nextCovered,
                    totalMs = nextTotal,
                ),
            ) to emptyList()
        }
        }
    }

    private fun prefillPartialReady(
        state: LoopPageState.Ready,
        coveredMs: Long,
        totalMs: Long,
    ): Pair<LoopPageState, List<LoopPageEffect>> {
        if (state.sourceMode) return noEffects(state)
        // 整环已完整 / 已经前缀就绪:重复信号不重复 Play。
        if (state.prefill == PrefillState.Ready || state.prefill is PrefillState.Partial) {
            return noEffects(state)
        }
        val partial = PrefillState.Partial(
            coveredMs = coveredMs.coerceAtLeast(0L),
            totalMs = totalMs.coerceAtLeast(0L),
        )
        val awaiting = state.playback as? PlaybackState.AwaitingPrefill
            ?: return state.copy(prefill = partial) to emptyList()

        // 前缀够用即可起播:与 PrefillReady 一样补 Play,不再等整环。
        val next = state.copy(
            prefill = partial,
            playback = PlaybackState.Playing,
            positionMs = awaiting.resumeAtMs,
        )
        return next to listOf(LoopPageEffect.Play(awaiting.resumeMode, awaiting.resumeAtMs))
    }

    private fun prefillReady(
        state: LoopPageState.Ready,
    ): Pair<LoopPageState, List<LoopPageEffect>> {
        if (state.sourceMode) {
            // SOURCE 的首块就绪：只清提示，不改播放状态。
            return state.copy(prefill = PrefillState.Ready) to emptyList()
        }
        if (state.prefill == PrefillState.Ready) return noEffects(state)
        // 前缀已就绪(Partial)时补一个“整环完成”不改变播放状态。
        if (state.prefill is PrefillState.Partial) {
            return state.copy(prefill = PrefillState.Ready) to emptyList()
        }
        val awaiting = state.playback as? PlaybackState.AwaitingPrefill
            ?: return state.copy(prefill = PrefillState.Ready) to emptyList()

        // 00009:预填完成必须补发 Play(从 await 起点),否则就是“位置在动但无输出”。
        val next = state.copy(
            prefill = PrefillState.Ready,
            playback = PlaybackState.Playing,
            positionMs = awaiting.resumeAtMs,
        )
        return next to listOf(LoopPageEffect.Play(awaiting.resumeMode, awaiting.resumeAtMs))
    }

    private fun prefillFailed(
        state: LoopPageState.Ready,
        message: String,
    ): Pair<LoopPageState, List<LoopPageEffect>> {
        if (state.sourceMode) {
            // SOURCE 的块失败由数据源跳过/上报；页面不进入 AwaitingPrefill。
            return state.copy(prefill = PrefillState.NotStarted) to emptyList()
        }
        if (state.prefill == PrefillState.Ready) return noEffects(state)
        // 前缀已就绪:后续分片失败不影响已可播的前缀,保持 Partial/播放不变。
        if (state.prefill is PrefillState.Partial) return noEffects(state)

        val awaiting = state.playback as? PlaybackState.AwaitingPrefill
            ?: return state.copy(prefill = PrefillState.Failed(message)) to emptyList()

        // 预填失败不能留下静默等待:降级为直通播放并提示用户。
        val next = state.copy(
            prefill = PrefillState.Failed(message),
            playback = PlaybackState.Playing,
            positionMs = awaiting.resumeAtMs,
        )
        return next to listOf(
            LoopPageEffect.Play(awaiting.resumeMode, awaiting.resumeAtMs),
            LoopPageEffect.Toast(PREFILL_FALLBACK_TOAST),
        )
    }

    private fun confirmAndSave(
        state: LoopPageState.Ready,
    ): Pair<LoopPageState, List<LoopPageEffect>> =
        state.copy(confirmed = true) to
            listOf(LoopPageEffect.PersistConfirm(state.songId, state.window()))

    private fun demote(
        state: LoopPageState.Ready,
    ): Pair<LoopPageState, List<LoopPageEffect>> {
        if (!state.confirmed) return noEffects(state)
        return state.copy(confirmed = false) to listOf(LoopPageEffect.PersistDemote(state.songId))
    }

    private fun delete(
        state: LoopPageState.Ready,
    ): Pair<LoopPageState, List<LoopPageEffect>> {
        if (state.candidates.isEmpty()) return noEffects(state)
        val next = state.copy(
            candidates = emptyList(),
            selectedCandidate = 0,
            confirmed = false,
            drag = DragState.Idle,
            prefill = PrefillState.NotStarted,
            // 取消预填后不能再停留在 AwaitingPrefill(否则永远等不到补发 Play)。
            playback = if (state.playback is PlaybackState.AwaitingPrefill) {
                PlaybackState.Paused
            } else {
                state.playback
            },
        )
        return next to listOf(
            LoopPageEffect.CancelPrefill,
            LoopPageEffect.PersistDelete(state.songId),
        )
    }

    /**
     * 波形点击/时间 seek(issue 00023)。
     *
     * 循环区外 = 自由试听(按文件时间),区外 seek **不再被折回 [A,B)**;
     * 拖回区间内自动恢复循环(同一 A/B/模式)。只产出一个 [LoopPageEffect.SeekTo]:
     * 真正的换源/恢复由 PreviewPlayer 按目标位置自行完成,UI 不额外加等待、不重建播放器。
     */
    private fun seek(
        state: LoopPageState.Ready,
        positionMs: Long,
    ): Pair<LoopPageState, List<LoopPageEffect>> {
        val target = positionMs.coerceIn(0L, state.durationMs)
        val outside = state.loopArmed() && !state.isInsideLoop(target)
        // 回区间 = 要重新装循环窗口/数据源:给一个"首块加载中"弱提示,READY 事件会清掉;
        // 它不阻塞交互(不冻结位置、不禁用控件)。
        val restoring = state.auditionOutside && !outside
        val switching = outside != state.auditionOutside
        val intentToPlay = state.playback is PlaybackState.Playing ||
            state.playback is PlaybackState.AwaitingPrefill
        val prefill = when {
            restoring && state.sourceMode -> PrefillState.FirstBlockLoading
            outside -> PrefillState.NotStarted
            state.sourceMode && state.mode != LoopPageMode.FREE &&
                state.playback is PlaybackState.Playing -> PrefillState.FirstBlockLoading
            else -> state.prefill
        }
        // 模式切换(区外/回区间)时不再等预填:AwaitingPrefill 转 Playing 并补一次 Play,
        // 保证"位置到了就一定有声音意图",不留下永远等不到的补发。
        val awaiting = state.playback is PlaybackState.AwaitingPrefill
        val playback = if (switching && awaiting) PlaybackState.Playing else state.playback
        val effects = mutableListOf<LoopPageEffect>(LoopPageEffect.SeekTo(target))
        if (switching && awaiting && intentToPlay) {
            effects += LoopPageEffect.Play(state.mode, target)
        }
        return state.copy(
            positionMs = target,
            auditionOutside = outside,
            prefill = prefill,
            playback = playback,
        ) to effects
    }

    // ------------------------------------------------------------------ 纯工具

    private fun noEffects(state: LoopPageState): Pair<LoopPageState, List<LoopPageEffect>> =
        state to emptyList()

    private fun clampProgress(progress: Float): Float = progress.coerceIn(0f, 1f)

    private fun clampA(state: LoopPageState.Ready, ms: Long): Long =
        ms.coerceIn(0L, (state.bMs - MIN_LOOP_LENGTH_MS).coerceAtLeast(0L))

    private fun clampB(state: LoopPageState.Ready, ms: Long): Long {
        val minB = (state.aMs + MIN_LOOP_LENGTH_MS).coerceAtMost(state.durationMs)
        return ms.coerceIn(minB, state.durationMs)
    }

    private fun candidateWindowMs(
        candidate: LoopCandidateKt,
        sampleRate: Int,
        durationMs: Long,
    ): Pair<Long, Long> {
        val sr = sampleRate.takeIf { it > 0 } ?: 44_100
        val maxA = (durationMs - MIN_LOOP_LENGTH_MS).coerceAtLeast(0L)
        val aMs = (candidate.startSamples * 1000.0 / sr).roundToLong().coerceIn(0L, maxA)
        val bMs = (candidate.endSamples * 1000.0 / sr).roundToLong()
            .coerceIn(aMs + MIN_LOOP_LENGTH_MS, durationMs)
        return aMs to bMs
    }

    private fun modeStartMs(mode: LoopPageMode, aMs: Long, bMs: Long): Long = when (mode) {
        LoopPageMode.FULL -> 0L
        LoopPageMode.LOOP_ONLY -> (aMs - LOOP_PREROLL_MS).coerceAtLeast(0L)
        // 极短循环段 B−5s < A 时取 A,保证落点仍在循环区内。
        LoopPageMode.SEAM -> maxOf(aMs, bMs - SEAM_LEAD_MS)
        LoopPageMode.FREE -> 0L
    }

}

/** [LoopPageState.Ready] 当前 A/B 对应的环形窗口(微秒);UI/effect runner 共用。 */
fun LoopPageState.Ready.window(): LoopWindow =
    LoopWindow(songId, aMs * 1000L, bMs * 1000L)

/** 当前是否真的在"循环试听"(自由模式没有循环窗口,整段直通)。 */
fun LoopPageState.Ready.loopArmed(): Boolean =
    mode != LoopPageMode.FREE && bMs > aMs

/**
 * 展示位置是否落在循环区 [aMs, bMs) 内(右开,与音频层环回边界一致)。
 *
 * issue 00023:区外位置 = 自由试听;判定集中在 reducer / 播放器 / 测试三处共用,避免口径漂移。
 */
fun LoopPageState.Ready.isInsideLoop(positionMs: Long): Boolean =
    positionMs in aMs until bMs
