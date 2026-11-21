package com.zjr.hesimusic.ui.loop

import com.zjr.hesimusic.loop.LoopWindow

/**
 * 预览 seek 要走哪条路径(纯判定,JVM 单测)。
 *
 * issue 00023:分析页的 seek 有两种语义 —— 循环区内是"环内跳转",区外是"按文件时间自由试听"。
 * 这个纯函数是 [PreviewPlayer.seekToDisplayMs] 的唯一分支依据,把"刚切完模式紧接着区外 seek"
 * 这类时序固定下来(单测直接锁,不需要真机/ExoPlayer)。
 */
enum class PreviewSeekAction {
    /** 循环区内:快速 seek(只 seekTo;不 prepare/不换源/不重建 player)。 */
    LOOP_FAST,

    /** 从循环进入区外自由试听:换原生 media3 源(整曲时间),**一次**。 */
    FREE_ENTER,

    /** 已在自由试听里:原生 seek(不再换源)。 */
    FREE_SEEK,

    /** 从区外拖回 [A,B):装回环带循环源,从落点继续。 */
    LOOP_RESTORE,
}

object PreviewSeekPolicy {

    /**
     * LOOP_RESTORE 的环带时间轴落点 T(ms):新装的环带源 T=0 恒对应 A。
     *
     * 注意这里**不是** (target−A) mod L —— 窗口外的目标钳到窗口起点,绝不能被折到环尾,
     * 否则"点 A 左侧"会跳到 B 左侧(issue 00023 用户报告的现象)。
     */
    fun restoreTimelineMs(targetMs: Long, window: LoopWindow): Long {
        val aMs = window.loopStartUs / 1_000L
        val lengthMs = (window.lengthUs / 1_000L).coerceAtLeast(1L)
        return (targetMs.coerceAtLeast(0L) - aMs).coerceIn(0L, lengthMs - 1L)
    }

    /** 展示位置 ↔ 环带时间轴同口径折返(= A + floorMod(T, L),与 LoopSourceProjection.displayUs 一致)。 */
    fun displayOfTimelineMs(timelineMs: Long, window: LoopWindow): Long {
        val aMs = window.loopStartUs / 1_000L
        val lengthMs = (window.lengthUs / 1_000L).coerceAtLeast(1L)
        return aMs + ((timelineMs % lengthMs) + lengthMs) % lengthMs
    }

    /**
     * 该路径的**展示落点**契约(issue 00023):任何路径的落点都必须等于请求的展示位置。
     * - 自由试听:原生源按媒体时间显示 → 落点 = 请求值(绝不折返);
     * - 环内 / 恢复循环:落点 = 折返后的展示位置(投影正确时等于请求值)。
     *
     * 单测用它对 A 之前/之后、A/B 边界逐点断言;生产端用它打日志/对账。
     */
    fun landingDisplayMs(
        action: PreviewSeekAction,
        targetMs: Long,
        window: LoopWindow?,
    ): Long = when {
        window == null -> targetMs.coerceAtLeast(0L)
        action == PreviewSeekAction.FREE_ENTER || action == PreviewSeekAction.FREE_SEEK ->
            targetMs.coerceAtLeast(0L)
        else -> displayOfTimelineMs(restoreTimelineMs(targetMs, window), window)
    }

    /**
     * @param mode 当前试听模式(FREE = 用户显式选择整段直通,不存在"区外")
     * @param window 当前循环窗口;null = 无循环(自由模式/非法窗口)
     * @param outsideLoop 播放器当前是否已处于自由试听
     * @param targetMs 本次 seek 的展示口径目标
     */
    fun decide(
        mode: PreviewMode,
        window: LoopWindow?,
        outsideLoop: Boolean,
        targetMs: Long,
    ): PreviewSeekAction {
        val loopArmed = window != null && mode != PreviewMode.FREE
        val inside = window != null &&
            targetMs >= window.loopStartUs / 1000L &&
            targetMs < window.loopEndUs / 1000L
        return when {
            // 区外 + 还没进自由试听:进入(只有这一次会换源)。
            loopArmed && !inside && !outsideLoop -> PreviewSeekAction.FREE_ENTER
            // 已自由试听,但目标回到区间内:恢复循环。
            outsideLoop && loopArmed && inside -> PreviewSeekAction.LOOP_RESTORE
            // 已自由试听且目标仍在区外(或窗口已失效):原生 seek。
            outsideLoop -> PreviewSeekAction.FREE_SEEK
            // 其余:区内快速 seek(含 FREE 模式 / 无窗口).
            else -> PreviewSeekAction.LOOP_FAST
        }
    }
}
