package com.zjr.hesimusic.service

import com.zjr.hesimusic.loop.LoopWindow
import com.zjr.hesimusic.loop.audio.source.LoopSourceItemTag
import com.zjr.hesimusic.loop.audio.source.LoopSourceProjection

/**
 * 进入/退出循环的**纯计划**：只描述「动哪个 index、换成什么 tag、seek 到哪」，
 * 真正碰播放器（replaceMediaItem / seekTo）由 [LoopPlaybackCoordinator] 执行。
 *
 * 为什么单独抽出来：队列不变性是产品硬约束（首页/队列/通知栏其它项必须原样），
 * 而 Player 在纯 JVM 单测里造不出来。把「只动当前 index」和「进入/退出的位置映射」
 * 固化成纯数据，就能在没有播放器的情况下穷举断言。
 *
 * 时间轴口径：
 * - 媒体时间 = 普通文件时间轴（A/B 是媒体时间）；
 * - 数据源时间轴 T：T=0 对应当前窗口的 A，displayUs(T) = A + floorMod(T, L)。
 */
object LoopSourceItemPlan {

    private const val US_PER_MS: Long = 1_000L

    /**
     * 进入计划。
     *
     * @param index 只替换这**一个** index（队列其它项不动）
     * @param tag 给当前 item 打上的循环标记
     * @param seekTimelineMs 数据源时间轴上的落点：A（进循环前在 A 之前）或 A+entryOffset
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
     * @param seekMediaMs 恢复原 item 后要 seek 到的**媒体时间**（不是带 offset 的 T）
     */
    data class Exit(
        val index: Int,
        val seekMediaMs: Long,
        val touchedIndices: List<Int>,
    )

    /**
     * 进入循环：只替换当前 item，并把位置映射到数据源时间轴。
     *
     * 落点口径 `T = floorMod(max(0, position - A), L)`：
     * - 位置在 A 之前（前奏段）→ 0，即从 A 起播（前奏只播一次，进循环后不再回头）；
     * - 位置在 [A,B) 内 → A+entryOffset，用户不会被拽回 A；
     * - 位置已在 B 之后（repeat=ONE 收尾）→ 折到同一相位，不放大成多轮。
     *
     * @return null = 参数不合法（index 越界 / 窗口非法 / 歌不属于该窗口 / 路径为空），调用方不得替换
     */
    fun enter(
        index: Int,
        itemCount: Int,
        positionMs: Long,
        window: LoopWindow?,
        songId: Long,
        filePath: String,
    ): Enter? {
        if (index < 0 || index >= itemCount) return null
        if (window == null || !window.isValid() || window.songId != songId) return null
        if (filePath.isBlank()) return null
        val offsetUs = entryOffsetUs(positionMs * US_PER_MS, window)
        return Enter(
            index = index,
            tag = LoopSourceItemTag(
                songId = songId,
                loopStartUs = window.loopStartUs,
                loopEndUs = window.loopEndUs,
                filePath = filePath,
            ),
            seekTimelineMs = offsetUs / US_PER_MS,
            touchedIndices = listOf(index),
        )
    }

    /**
     * 退出循环：把数据源时间轴位置映射回媒体时间。
     *
     * 公式：`mediaMs = A + floorMod(T, L) / 1000`（A/B 来自被替换 item 的 tag）。
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
        return Exit(
            index = index,
            seekMediaMs = LoopSourceProjection.displayMs(timelineMs.coerceAtLeast(0L), window),
            touchedIndices = listOf(index),
        )
    }

    /**
     * 进入偏移（µs）：媒体位置在 [A,B) 内的相对量，折进 [0,L)。
     *
     * 与 [LoopSourceProjection.initialTimelineUs] 的区别：这里对越界位置做取模，
     * 保证落点永远是一轮以内（T 再大也只是同一相位，没必要让 seek 跨轮）。
     */
    private fun entryOffsetUs(mediaUs: Long, window: LoopWindow): Long {
        if (!window.isValid()) return 0L
        val offset = mediaUs - window.loopStartUs
        if (offset <= 0L) return 0L
        return Math.floorMod(offset, window.lengthUs)
    }
}
