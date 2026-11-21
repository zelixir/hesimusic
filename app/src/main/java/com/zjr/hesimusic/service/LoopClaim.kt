package com.zjr.hesimusic.service

import com.zjr.hesimusic.data.model.SongLoop
import com.zjr.hesimusic.loop.LoopIntent
import com.zjr.hesimusic.loop.LoopPhase
import com.zjr.hesimusic.loop.LoopPosition
import com.zjr.hesimusic.loop.LoopRuntime
import com.zjr.hesimusic.loop.LoopWindow
import com.zjr.hesimusic.loop.audio.LoopAudioController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 对用户 / MediaSession / 调试口暴露的**循环声称**（00027 单一事实源）。
 *
 * 为什么需要它：`LoopRuntime.window` 只代表“用户 armed 了无缝”，SOURCE 路线可能因为
 * 预算/格式/文件/窗口/构建失败回退成普通整曲。之前每个读取方各自推导一个布尔，导致
 * 进度条、MediaSession、调试口、About 可能同时“声称在循环”而耳朵听到的是整曲。
 *
 * 规则：
 * - [claimActive] 只有在 [sourceActive]（源真的建成/写入音频层）且窗口属于当前歌时才为 true；
 * - 所有折返、A/B 标记、MediaSession position/duration 都必须走本对象；
 * - 不再允许任何地方直接读 `LoopRuntime.window` / `LoopAudioController.window` 做折返。
 *
 * 口径：窗口微秒，位置毫秒/微秒都有显式函数，保持与 [LoopPosition] 一致。
 */
data class LoopClaim(
    /** 当前歌；null = 没有当前歌。 */
    val songId: Long?,
    /** 意图窗口（也是 C2 重放期 audio 回退后的有效窗口）；null = 普通播放。 */
    val window: LoopWindow?,
    /** 播放管线真的在环回：SOURCE 已建成并接管，或 M3′ 窗口已写入音频层。 */
    val sourceActive: Boolean,
    /** 未生效原因；sourceActive=true 时为 null。五种回退原因共用。 */
    val fallbackReason: LoopSourceFallback.Reason? = null,
    /**
     * true = 位置已经是 SOURCE 整曲时间轴投影后的展示值（prelude/loop/tail 都合法）。
     * UI 二次折返时 tail 必须原样保留，不能把 B 之后的尾奏折回 A。
     */
    val sourceTimeline: Boolean = false,
    /** 最近一次权威展示位置对应的阶段（来自 [LoopPlaybackPhaseRuntime]），仅用于 UI 折返边界。 */
    val phaseHint: LoopPhase? = null,
) {

    /**
     * 对用户/媒体会话**生效**的循环声明。源没生效时恒 false，哪怕 window 还在。
     *
     * 窗口合法性走 [LoopWindowRules]（含 500ms 最小长度）：历史坏行（A==B、只差 1 个样本、
     * 越界）即使 sourceActive 被误置也绝不算生效。
     */
    val claimActive: Boolean
        get() = sourceActive &&
            window != null &&
            LoopWindowRules.writable(window) != null &&
            songId != null &&
            window.songId == songId

    /** 只有 claimActive 时才返回窗口；显示层必须读它，不得直接读 [window]。 */
    val effectiveWindow: LoopWindow?
        get() = window?.takeIf { claimActive }

    /** 展示位置（毫秒）：claim 未生效 = 原样透传媒体时间。 */
    fun displayPositionMs(rawPositionMs: Long): Long {
        val w = effectiveWindow ?: return rawPositionMs
        if (rawPositionMs > MAX_FOLDABLE_POSITION_MS ||
            rawPositionMs < -MAX_FOLDABLE_POSITION_MS
        ) {
            return rawPositionMs
        }
        // SOURCE 整曲时间轴：服务层给的位置已经是展示值。tail 阶段必须原样保留；
        // loop 阶段仍折返一次，吸收 MediaController 跨 B 的本地外推（幂等）。
        if (sourceTimeline) {
            if (phaseHint == LoopPhase.TAIL) return rawPositionMs.coerceAtLeast(0L)
            return LoopPosition.displayUs(rawPositionMs * 1_000L, w) / 1_000L
        }
        return LoopPosition.displayUs(rawPositionMs * 1_000L, w) / 1_000L
    }

    /** 展示位置（微秒）：claim 未生效 = 原样透传媒体时间。 */
    fun displayPositionUs(rawUs: Long): Long {
        val w = effectiveWindow ?: return rawUs
        return LoopPosition.displayUs(rawUs, w)
    }

    /**
     * 对外时长（微秒）。整曲时长已知就报整曲——**claim 是否生效都一样**；
     * 回退时尤其不能透传 SOURCE 的 TIME_UNSET。
     */
    fun durationUs(fullDurationUs: Long): Long = fullDurationUs.coerceAtLeast(0L)

    /**
     * 当前循环阶段（prelude/loop/tail），由**播放器展示位置**判定，不是第二套事实源。
     * claim 未生效/窗口非法返回 null。
     */
    fun phaseForDisplayUs(displayUs: Long): LoopPhase? =
        if (claimActive) LoopPhase.ofDisplayUs(displayUs, effectiveWindow) else null

    /**
     * /state.loop 的一等字段契约（claim 真值）。claimed = “有意图窗口”，
     * claimActive = 对用户生效，sourceActive = 源真的在环回，claimReason = 未生效原因。
     *
     * 非法窗口（未过 [LoopWindowRules]）在 [LoopClaimResolver] 里已被清成 window=null，
     * 所以这里 claimed 自然为 false：坏行不声称循环，也不会触发
     * `loop-claim-without-source`（那条不变量判的是 claimed && !sourceActive）。
     */
    fun debugFields(): LoopClaimDebugFields = LoopClaimDebugFields(
        // 非法窗口不算"有意图窗口"：claimed=false 才能让 loop-claim-without-source
        // 不误报（那条不变量判 claimed && !sourceActive）。
        claimed = window != null && LoopWindowRules.writable(window) != null,
        claimActive = claimActive,
        sourceActive = sourceActive,
        claimReason = if (sourceActive) null else fallbackReason?.logName,
    )

    companion object {
        /** 毫秒转微秒前的安全上限，避免 Long 乘法溢出（与旧 foldDisplayPositionMs 同口径）。 */
        private const val MAX_FOLDABLE_POSITION_MS: Long = Long.MAX_VALUE / 1_000L

        /** 没有当前歌 / 没有任何窗口。 */
        fun none(songId: Long? = null): LoopClaim =
            LoopClaim(songId = songId, window = null, sourceActive = false)
    }
}

/** /state.loop 的 claim 字段（纯数据，便于单测与序列化）。 */
data class LoopClaimDebugFields(
    val claimed: Boolean,
    val claimActive: Boolean,
    val sourceActive: Boolean,
    val claimReason: String?,
)

/** 正在循环的歌被取消确认/删除/重启恢复失败时的**显式退场原因**（D13）。 */
enum class LoopExitReason(val wire: String) {
    /** 用户把已确认结果取消确认（confirmed 1→0）。 */
    CONFIRMATION_REVOKED("confirmation-revoked"),

    /** 用户删除了分析结果（整行删除）。 */
    RESULT_DELETED("result-deleted"),

    /** 进程重启时持久化的循环意图已不可恢复（歌/行/窗口/repeat 任一不一致）。 */
    RESTORE_UNAVAILABLE("restore-unavailable"),
}

/**
 * 一次显式退场的可见状态（[LoopClaimStore.exitNoticeFlow]）。
 *
 * 为什么需要：demote 过去只是让窗口"悄悄"变 null（repeat 还停在 ONE、intent 还 armed），
 * 用户只看到循环突然没了。这里把"谁、为什么、退到哪个模式"变成一等状态；UI 收集后提示，
 * 调试口/logcat 也能看到（[summary]）。
 */
data class LoopExitNotice(
    val songId: Long,
    val reason: LoopExitReason,
    val window: LoopWindow?,
    val previousRepeatMode: Int,
    val nextRepeatMode: Int,
    val previousShuffleEnabled: Boolean,
    val nextShuffleEnabled: Boolean,
    val notifiedAtMs: Long,
) {
    fun summary(): String =
        "loop exit reason=" + reason.wire + " songId=" + songId +
            " window=" + window +
            " repeat=" + previousRepeatMode + "->" + nextRepeatMode +
            " shuffle=" + previousShuffleEnabled + "->" + nextShuffleEnabled
}

/**
 * D13：正在循环的歌被 demote / 删除时的**显式退出计划**（纯函数）。
 *
 * 触发条件全部满足：
 * 1. 有显式意图，且武装的是当前歌；
 * 2. 上一轮 refresh 确实算出过窗口（正在循环，或至少已经进入过循环形态）；
 * 3. 当前 song_loop 行不存在（删除）或 confirmed=false（取消确认）。
 *
 * 返回的恢复值来自 [LoopIntent.Armed] 的进入前模式；调用方（协调器）负责清 intent、
 * 写播放器/偏好，并把 [LoopExitNotice] 发布到 [LoopClaimStore]。
 */
object LoopRevocation {

    data class Exit(
        val songId: Long,
        val reason: LoopExitReason,
        val window: LoopWindow,
        val currentRepeatMode: Int,
        val currentShuffleEnabled: Boolean,
        val restoreRepeatMode: Int,
        val restoreShuffleEnabled: Boolean,
    ) {
        val repeatChanged: Boolean get() = restoreRepeatMode != currentRepeatMode
        val shuffleChanged: Boolean get() = restoreShuffleEnabled != currentShuffleEnabled
    }

    fun detect(
        armed: LoopIntent.Armed?,
        previousWindow: LoopWindow?,
        songId: Long?,
        row: SongLoop?,
        currentRepeatMode: Int,
        currentShuffleEnabled: Boolean,
    ): Exit? {
        if (armed == null || previousWindow == null) return null
        if (songId == null || songId <= 0L || armed.songId != songId) return null
        if (previousWindow.songId != songId) return null
        if (row != null && row.confirmed) return null
        return Exit(
            songId = songId,
            reason = if (row == null) LoopExitReason.RESULT_DELETED else LoopExitReason.CONFIRMATION_REVOKED,
            window = previousWindow,
            currentRepeatMode = currentRepeatMode,
            currentShuffleEnabled = currentShuffleEnabled,
            restoreRepeatMode = armed.previousRepeatMode,
            restoreShuffleEnabled = armed.previousShuffleEnabled,
        )
    }
}

/**
 * 唯一的 claim 解析器：输入“意图窗口 + 音频窗口 + 已安装窗口 + 路线 + 回退登记”，
 * 输出 [LoopClaim]。会话层、UI、debug、About 都只能通过它（或 [LoopClaimStore]）拿真值。
 */
object LoopClaimResolver {

    /**
     * 选当前项该用的窗口。
     *
     * - 优先 `runtimeWindow`（服务层派生态；必须合法且属于当前歌）；
     * - 只有重放期才回退 `audioWindow`（C2：runtime 异步落值前的短窗口，音频层是权威）；
     * - 都不可用 → null（完全透传）。
     */
    fun selectWindow(
        songId: Long?,
        runtimeWindow: LoopWindow?,
        audioWindow: LoopWindow?,
        isReplaying: Boolean,
    ): LoopWindow? {
        // D14：窗口合法性只认 LoopWindowRules；非法 runtime 窗口不能挡住重放期的 audio 回退。
        val intended = LoopWindowRules.writable(runtimeWindow)?.takeIf {
            songId != null && it.songId == songId
        }
        if (intended != null) return intended
        if (!isReplaying) return null
        return LoopWindowRules.writable(audioWindow)?.takeIf {
            songId != null && it.songId == songId
        }
    }

    /**
     * 纯函数解析（单测穷举五种回退原因 / 源生效 / 非法窗口 / 别歌窗口）。
     *
     * @param installedWindow SOURCE 已登记接管的窗口（[LoopSourceRuntime.installedWindow]）
     * @param sourceRoute true = SOURCE 路线；false = M3′ 路线
     * @param sourceUnavailable 该窗口是否已被 [LoopSourceFallback] 判为不可用
     */
    fun resolve(
        songId: Long?,
        runtimeWindow: LoopWindow?,
        audioWindow: LoopWindow?,
        installedWindow: LoopWindow?,
        isReplaying: Boolean,
        sourceRoute: Boolean,
        sourceUnavailable: Boolean,
        fallbackReason: LoopSourceFallback.Reason? = null,
    ): LoopClaim {
        val selected = selectWindow(songId, runtimeWindow, audioWindow, isReplaying)
        // 窗口合法性（500ms 下限 / A<B / 时长上界）在 claim 层再判一次：
        // 历史坏行即使把源装上了也绝不能声称循环。非法窗口不下发（window=null），
        // 这样 claimed=false、claimActive=false，且 reason=window-invalid 仍可见。
        val usableWindow = LoopWindowRules.writable(selected)
        val sourceActive = when {
            usableWindow == null -> false
            sourceUnavailable -> false
            sourceRoute -> sameWindow(installedWindow, usableWindow)
            else -> sameWindow(audioWindow, usableWindow)
        }
        // 原因必须能看见：selectWindow 已经先丢掉非法窗口，不能再用 selected != null 判坏窗口。
        // 直接对 runtimeWindow 做同一套 LoopWindowRules 判定（坏行即使被丢弃也报 window-invalid）；
        // runtimeWindow 为空/别的歌时，才回退到 sourceUnavailable 的其它原因。
        val runtimeInvalid = runtimeWindow != null &&
            LoopWindowRules.writable(runtimeWindow) == null &&
            (songId == null || runtimeWindow.songId == songId)
        val reason = when {
            runtimeInvalid -> LoopSourceFallback.Reason.WINDOW_INVALID
            selected != null && usableWindow == null -> LoopSourceFallback.Reason.WINDOW_INVALID
            sourceActive -> null
            else -> fallbackReason
        }
        val effectiveSongId = usableWindow?.songId ?: songId
        return LoopClaim(
            songId = effectiveSongId,
            window = usableWindow,
            sourceActive = sourceActive,
            fallbackReason = reason,
            sourceTimeline = sourceRoute && sourceActive,
            phaseHint = if (sourceRoute && sourceActive && usableWindow != null) {
                LoopPlaybackPhaseRuntime.phaseFor(effectiveSongId, usableWindow)
            } else {
                null
            },
        )
    }

    /**
     * 生产路径：从进程内单例读取真值。
     *
     * @param songId 当前歌；null 时用意图窗口的 songId（About / 冷启动 debug 用）
     */
    fun fromProcess(
        songId: Long?,
        runtime: LoopRuntime,
        audio: LoopAudioController,
    ): LoopClaim {
        val runtimeWindow = runtime.window.value
        val effectiveSongId = songId
            ?: runtimeWindow?.songId
            ?: audio.window?.songId
            ?: LoopSourceRuntime.installedWindow?.songId
        val selected = selectWindow(effectiveSongId, runtimeWindow, audio.window, audio.isReplaying)
        val sourceRoute = MainPlayerRouting.current.coordinatorReplacesItem
        val unavailable = selected != null &&
            LoopSourceFallback.isUnavailable(effectiveSongId, selected)
        return resolve(
            songId = effectiveSongId,
            runtimeWindow = runtimeWindow,
            audioWindow = audio.window,
            installedWindow = LoopSourceRuntime.installedWindow,
            isReplaying = audio.isReplaying,
            sourceRoute = sourceRoute,
            sourceUnavailable = unavailable,
            // 只认该窗口的 per-song reason，绝不用全局 latest 串歌。
            fallbackReason = if (selected == null) {
                null
            } else {
                LoopSourceFallback.reasonFor(effectiveSongId, selected)
            },
        )
    }

    private fun sameWindow(a: LoopWindow?, b: LoopWindow?): Boolean =
        a != null && b != null &&
            a.songId == b.songId &&
            a.loopStartUs == b.loopStartUs &&
            a.loopEndUs == b.loopEndUs
}

/**
 * 进程内 claim 发布/读取点。
 *
 * - provider 由 `MusicService` 在构造好播放器后安装（见 [install]）；
 * - [revisionFlow] 在 registry / 接管状态 / refresh 变化时自增，UI 用它触发重算，
 *   避免“回退已登记但 A/B 标记要等下一次 1s 位置刷新才消失”的脏帧；
 * - provider 未安装时返回 [LoopClaim.none]，绝不猜测。
 */
object LoopClaimStore {

    private val revision = MutableStateFlow(0L)

    /** 每次 claim 相关事实可能变化时自增；UI collect 它来触发重算。 */
    val revisionFlow: StateFlow<Long> = revision.asStateFlow()

    private val exitNotice = MutableStateFlow<LoopExitNotice?>(null)

    /**
     * 最近一次显式退场（D13）。UI 收集它做一次性 Toast/横幅；consumeExitNotice()
     * 读后即清，避免旋转/重进页面重复弹。**不是**第二套 claim 真值：是否在循环仍只读 claim。
     */
    val exitNoticeFlow: StateFlow<LoopExitNotice?> = exitNotice.asStateFlow()

    @Volatile
    private var provider: ((Long?) -> LoopClaim)? = null

    /** 发布一次显式退场（demote / delete / 重启恢复失败）。 */
    fun publishExit(notice: LoopExitNotice) {
        exitNotice.value = notice
        invalidate()
    }

    /** 读取并清空（一次性提示语义；没有 notice 时返回 null）。 */
    fun consumeExitNotice(): LoopExitNotice? {
        val current = exitNotice.value ?: return null
        exitNotice.value = null
        return current
    }

    fun clearExitNotice() {
        exitNotice.value = null
    }

    fun install(provider: (Long?) -> LoopClaim) {
        this.provider = provider
        invalidate()
    }

    fun uninstall() {
        provider = null
        invalidate()
    }

    fun isInstalled(): Boolean = provider != null

    /**
     * 当前 songId 的 claim；provider 未装/无当前歌/解析异常时返回未生效。
     *
     * 读 claim 是**只读**路径：任何异常都不能让播放/UI 崩，最坏退化为“普通播放”。
     */
    fun claimFor(songId: Long?): LoopClaim {
        val p = provider ?: return LoopClaim.none(songId)
        return try {
            p(songId)
        } catch (_: Throwable) {
            LoopClaim.none(songId)
        }
    }

    /** 标记 claim 可能变化（不改变 provider）。 */
    fun invalidate() {
        revision.update { it + 1L }
    }

    /** 仅供单测隔离。 */
    fun resetForTest() {
        provider = null
        revision.value = 0L
        exitNotice.value = null
    }
}
