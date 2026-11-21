package com.zjr.hesimusic.service

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
) {

    /** 对用户/媒体会话**生效**的循环声明。源没生效时恒 false，哪怕 window 还在。 */
    val claimActive: Boolean
        get() = sourceActive &&
            window != null &&
            window.isValid() &&
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
     * /state.loop 的一等字段契约（claim 真值）。claimed = “有意图窗口”，
     * claimActive = 对用户生效，sourceActive = 源真的在环回，claimReason = 未生效原因。
     */
    fun debugFields(): LoopClaimDebugFields = LoopClaimDebugFields(
        claimed = window != null,
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
        val intended = runtimeWindow?.takeIf {
            it.isValid() && songId != null && it.songId == songId
        }
        if (intended != null) return intended
        if (!isReplaying) return null
        return audioWindow?.takeIf {
            it.isValid() && songId != null && it.songId == songId
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
        val sourceActive = when {
            selected == null -> false
            sourceUnavailable -> false
            sourceRoute -> sameWindow(installedWindow, selected)
            else -> sameWindow(audioWindow, selected)
        }
        return LoopClaim(
            songId = selected?.songId ?: songId,
            window = selected,
            sourceActive = sourceActive,
            fallbackReason = if (sourceActive) null else fallbackReason,
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
        val effectiveSongId = songId ?: runtimeWindow?.songId
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
            fallbackReason = if (selected == null) {
                null
            } else {
                LoopSourceFallback.reasonFor(effectiveSongId, selected)
                    ?: if (unavailable) LoopSourceFallback.latestReason() else null
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

    @Volatile
    private var provider: ((Long?) -> LoopClaim)? = null

    fun install(provider: (Long?) -> LoopClaim) {
        this.provider = provider
        invalidate()
    }

    fun uninstall() {
        provider = null
        invalidate()
    }

    fun isInstalled(): Boolean = provider != null

    /** 当前 songId 的 claim；provider 未装/无当前歌时返回未生效。 */
    fun claimFor(songId: Long?): LoopClaim =
        provider?.invoke(songId) ?: LoopClaim.none(songId)

    /** 标记 claim 可能变化（不改变 provider）。 */
    fun invalidate() {
        revision.update { it + 1L }
    }

    /** 仅供单测隔离。 */
    fun resetForTest() {
        provider = null
        revision.value = 0L
    }
}
