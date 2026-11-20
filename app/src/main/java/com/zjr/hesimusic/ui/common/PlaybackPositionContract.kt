package com.zjr.hesimusic.ui.common

import kotlin.math.roundToLong

/**
 * MediaSession 位置事件的数值契约(A-066 / A-067)。
 *
 * media3 的 MediaSession 默认每 [EVENT_PERIOD_MS] 毫秒派发一次 PlayerInfo
 * (MediaSessionImpl.DEFAULT_SESSION_POSITION_UPDATE_DELAY_MS = 3000,且只在播放/加载中
 * 定时派发)。两次派发之间,MediaController 用**当前 speed** 在本地外推位置
 * (MediaUtils.getUpdatedCurrentPositionMs),不是 speed=1。
 *
 * 由此得到两条 UI 规则:
 * - seek 后 controller 已经乐观 mask 成新位置/新状态(A-067),UI 读到的是**新值**,
 *   不需要等"旧值变成新值";要等的是 session 侧 clamp/调整后的最终值;
 * - 暂停态拖动只能靠刷新窗口持续读位置,窗口必须覆盖一个完整事件周期(3s),
 *   否则下一次位置事件到来前会一直显示旧位置。
 */
internal object PlaybackPositionContract {

    /** MediaSession 默认周期位置事件间隔。 */
    const val EVENT_PERIOD_MS: Long = 3_000L

    /** 刷新窗口必须 > 一个完整事件周期(3s);多留 1s 余量。 */
    const val REFRESH_WINDOW_MS: Long = EVENT_PERIOD_MS + 1_000L

    /** 播放中或处于 seek 刷新窗口内时才需要主动读位置。 */
    fun shouldRefresh(isPlaying: Boolean, nowElapsedMs: Long, refreshUntilMs: Long): Boolean =
        isPlaying || nowElapsedMs < refreshUntilMs

    /**
     * 两次位置事件之间的本地外推:position + elapsed × speed。
     *
     * 只用于把数值契约写死(单测锁定"按真实 speed 外推");生产路径的位置仍由
     * MediaController 自己外推,这里不重复叠加。
     */
    fun extrapolateMs(positionMs: Long, elapsedMs: Long, speed: Float): Long {
        if (elapsedMs <= 0L || speed <= 0f) return positionMs.coerceAtLeast(0L)
        return (positionMs + elapsedMs * speed).roundToLong().coerceAtLeast(0L)
    }
}
