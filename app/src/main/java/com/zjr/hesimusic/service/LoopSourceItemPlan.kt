package com.zjr.hesimusic.service

import com.zjr.hesimusic.loop.LoopWindow
import com.zjr.hesimusic.loop.audio.source.LoopSourceItemTag
import com.zjr.hesimusic.loop.audio.source.LoopSourceProjection
import com.zjr.hesimusic.loop.audio.source.LoopWholeTimeline

/**
 * 进入/退出循环的**纯计划**：只描述「动哪个 index、换成什么 tag、seek 到哪」，
 * 真正碰播放器（replaceMediaItem / seekTo）由 [LoopPlaybackCoordinator] 执行。
 *
 * 为什么单独抽出来：队列不变性是产品硬约束（首页/队列/通知栏其它项必须原样），
 * 而 Player 在纯 JVM 单测里造不出来。把「只动当前 index」和「进入/退出的位置映射」
 * 固化成纯数据，就能在没有播放器的情况下穷举断言。
 *
 * 时间轴口径（issue 00028）：
 * - 主播放器 WHOLE：T=0 对应整曲开头；前奏/循环/tail 见 [LoopWholeTimeline]；
 * - 预览 LEGACY：T=0 对应当前窗口 A（[LoopSourceProjection]）。
 *
 * 进循环的起点**永远是 0**（曲目开头），不从当前播放位置继续，也不跳 A。
 * A/B 变化（已经处于 source item）才用 [entryTimelineUs] 保持当前展示位置。
 */
object LoopSourceItemPlan {

    private const val US_PER_MS: Long = 1_000L

    /**
     * 进入计划。
     *
     * @param index 只替换这**一个** index（队列其它项不动）
     * @param tag 给当前 item 打上的循环标记
     * @param seekTimelineMs 数据源时间轴上的落点：首次进入恒 0；A/B 变化时保持当前展示位置
     * @param touchedIndices 本计划碰到的 index（恒为 listOf(index)，单测据此断言队列不变）
     */
    data class Enter(
        val index: Int,
        val tag: LoopSourceItemTag,
        val seekTimelineMs: Long,
        val touchedIndices: List<Int>,
    )

    /**
     * 退出计划。
     *
     * @param seekMediaMs 恢复原 item 后要 seek 到的**媒体时间**（不是带 sentinel 的 T）
     */
    data class Exit(
        val index: Int,
        val seekMediaMs: Long,
        val touchedIndices: List<Int>,
    )

    /**
     * 进入循环：只替换当前 item，并把位置映射到数据源时间轴。
     *
     * @param fullDurationUs 整曲时长（µs）；>0 = WHOLE 口径（主播放器）。
     * @param entryTimelineUs A/B 变化时调用方算好的 T；null/首次进入 = 0（前奏起点）。
     * @return null = 参数不合法（index 越界 / 窗口非法 / 歌不属于该窗口 / 路径为空），调用方不得替换
     */
    fun enter(
        index: Int,
        itemCount: Int,
        positionMs: Long,
        window: LoopWindow?,
        songId: Long,
        filePath: String,
        fullDurationUs: Long = 0L,
        entryTimelineUs: Long = LoopWholeTimeline.PRELUDE_START_US,
    ): Enter? {
        if (index < 0 || index >= itemCount) return null
        if (window == null || !window.isValid() || window.songId != songId) return null
        if (filePath.isBlank()) return null
        val timelineUs = if (fullDurationUs > 0L) {
            // 首次进入由调用方传 0；A/B 变化时调用方按新窗口/当前 T 算好。
            entryTimelineUs.coerceAtLeast(0L)
        } else {
            // 预览/旧口径：保持原行为（T = A + floorMod(position - A, L)，A 之前为 0）。
            entryOffsetUs(positionMs * US_PER_MS, window)
        }
        return Enter(
            index = index,
            tag = LoopSourceItemTag(
                songId = songId,
                loopStartUs = window.loopStartUs,
                loopEndUs = window.loopEndUs,
                filePath = filePath,
                fullDurationUs = fullDurationUs,
            ),
            seekTimelineMs = timelineUs / US_PER_MS,
            touchedIndices = listOf(index),
        )
    }

    /**
     * 退出循环：把数据源时间轴位置映射回媒体时间。
     *
     * WHOLE：display = 前奏直通 / [A,B) 折返 / tail 直通；
     * LEGACY：`mediaMs = A + floorMod(T, L)`（预览旧口径）。
     * 非法 tag/index 返回 null（调用方只做 tag 清理，不做 seek）。
     */
    fun exit(
        index: Int,
        itemCount: Int,
        timelineMs: Long,
        tag: LoopSourceItemTag?,
    ): Exit? {
        if (tag == null || index < 0 || index >= itemCount) return null
        val window = LoopWindow(
            songId = tag.songId,
            loopStartUs = tag.loopStartUs,
            loopEndUs = tag.loopEndUs,
        )
        val seekMediaMs = if (tag.fullDurationUs > 0L) {
            LoopWholeTimeline.displayMs(timelineMs.coerceAtLeast(0L), window, tag.fullDurationUs)
        } else {
            LoopSourceProjection.displayMs(timelineMs.coerceAtLeast(0L), window)
        }
        return Exit(
            index = index,
            seekMediaMs = seekMediaMs,
            touchedIndices = listOf(index),
        )
    }

    /**
     * 旧口径进入偏移（µs）：媒体位置在 [A,B) 内的相对量，折进 [0,L)。
     * 仅 fullDurationUs=0 的预览/兼容路径使用。
     */
    private fun entryOffsetUs(mediaUs: Long, window: LoopWindow): Long {
        if (!window.isValid()) return 0L
        val offset = mediaUs - window.loopStartUs
        if (offset <= 0L) return 0L
        return Math.floorMod(offset, window.lengthUs)
    }
}
