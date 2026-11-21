package com.zjr.hesimusic.loop.audio.source

import com.zjr.hesimusic.loop.LoopPhase
import com.zjr.hesimusic.loop.analysis.Pcm16Frames

/**
 * 循环数据源的纯 JVM 时间轴/帧号算术。
 *
 * 有两种口径（[wholeTimeline]）：
 *
 * **LEGACY（预览页仍用）**：数据源时间轴 T=0 对应当前窗口的 A。
 * - 环长度 L = B−A；进入点 E 决定 `entryOffsetUs = floorMod(E−A, L)`；
 * - `mediaUs(T) = A + floorMod(entryOffset + T, L)`，到 B 自然回 A，不产生 seek。
 *
 * **WHOLE（主播放器，issue 00028）**：T=0 对应整曲开头。
 * - T<A 前奏按媒体时间直通；A≤T<TAIL_BASE 在 [A,B) 内折返；
 * - T≥[LoopWholeTimeline.TAIL_BASE_US] 是 tail 时间带（只有 seek 到 B 之后才进入）；
 * - 进入循环固定 T=0，不再有 entry offset。
 *
 * 两种口径共用同一套环块网格（帧网格锚定 A，块大小 1 秒），tail 不占环。
 */
class LoopingPcmTimeline(
    val songId: Long,
    val loopStartUs: Long,
    val loopEndUs: Long,
    val format: PcmTrackFormat,
    entryPositionUs: Long = loopStartUs,
    /** 整曲时长（µs）；>0 时启用 WHOLE 口径，0 保持 LEGACY。 */
    val fullDurationUs: Long = 0L,
) {

    init {
        require(songId > 0L) { "songId must be > 0" }
        require(loopStartUs >= 0L) { "loopStartUs must be >= 0" }
        require(loopEndUs > loopStartUs) { "loopEndUs must be > loopStartUs" }
        require(fullDurationUs >= 0L) { "fullDurationUs must be >= 0" }
        if (fullDurationUs > 0L) {
            require(fullDurationUs >= loopEndUs - FULL_TAIL_SLACK_US) {
                "fullDurationUs=$fullDurationUs must cover loopEndUs=$loopEndUs"
            }
        }
    }

    val sampleRate: Int get() = format.sampleRate
    val channels: Int get() = format.channels
    val bytesPerFrame: Int get() = format.bytesPerFrame

    /** true = 主播放器整曲时间轴（prelude + loop + tail）；false = 预览页 T=0↔A。 */
    val wholeTimeline: Boolean get() = fullDurationUs > 0L

    /** 窗口长度（µs）。 */
    val lengthUs: Long get() = loopEndUs - loopStartUs

    /** A 之后第一个整帧的绝对帧号。 */
    val aFrame: Long = Pcm16Frames.frameAtOrAfterUs(loopStartUs, sampleRate)

    /** B 之后第一个整帧的绝对帧号（环的右开边界）。 */
    val bFrame: Long = Pcm16Frames.frameAtOrAfterUs(loopEndUs, sampleRate)

    /** 环内帧数。 */
    val loopFrames: Long = bFrame - aFrame

    /** 整曲末的右开帧号；LEGACY 为 0。 */
    val durationFrames: Long = if (wholeTimeline) {
        Pcm16Frames.frameAtOrAfterUs(fullDurationUs, sampleRate)
    } else {
        0L
    }

    /** tail 帧数（B 之后到曲末）；无尾奏为 0。 */
    val tailFrames: Long = (durationFrames - bFrame).coerceAtLeast(0L)

    /** WHOLE 口径的 epoch 长度（µs）：TAIL_BASE + tail；LEGACY 为 0。 */
    val epochSpanUs: Long = if (wholeTimeline) {
        LoopWholeTimeline.epochSpanUs(fullDurationUs, window())
    } else {
        0L
    }

    /** 当前时间轴位置所在 epoch 的本地偏移（µs）。 */
    fun localTimelineUs(timelineUs: Long): Long =
        if (!wholeTimeline) timelineUs.coerceAtLeast(0L)
        else LoopWholeTimeline.localTimelineUs(timelineUs, fullDurationUs, window())

    /** 当前时间轴位置所在 epoch 的起点（µs）。 */
    fun epochStartUs(timelineUs: Long): Long =
        if (!wholeTimeline) 0L
        else LoopWholeTimeline.epochStartUs(timelineUs, fullDurationUs, window())

    /** tail 播完后的下一个 epoch 起点（T 连续，不倒退）。 */
    fun nextEpochStartUs(timelineUs: Long): Long =
        if (!wholeTimeline) timelineUs.coerceAtLeast(0L)
        else LoopWholeTimeline.nextEpochStartUs(timelineUs, fullDurationUs, window())

    /** 块大小：1 秒。 */
    val framesPerBlock: Int = sampleRate

    /** 块数；最后一块可以短于 1 秒。 */
    val blockCount: Int = ((loopFrames + framesPerBlock - 1L) / framesPerBlock).toInt()

    /** 进入点相对 A 的偏移，落在 [0, L)。WHOLE 固定 0（进循环从 0 开始）。 */
    val entryOffsetUs: Long = if (wholeTimeline) {
        0L
    } else {
        Math.floorMod(
            entryPositionUs - loopStartUs,
            lengthUs.coerceAtLeast(1L),
        )
    }

    init {
        require(loopFrames > 0L) { "loop window has no full frame: A=$loopStartUs B=$loopEndUs" }
        require(blockCount > 0) { "loop window has no block" }
    }

    /** 当前数据源时间轴 T 的阶段（phase 由播放器展示位置判定，这里仅供源内选路）。 */
    fun phaseAt(timelineUs: Long): LoopPhase =
        LoopWholeTimeline.phaseOfTimelineUs(timelineUs, window(), fullDurationUs) ?: LoopPhase.LOOP

    /** 对外播放时间 T → 媒体时间（µs）。 */
    fun mediaUs(timelineUs: Long): Long {
        if (!wholeTimeline) {
            if (timelineUs <= 0L) return loopStartUs + entryOffsetUs
            val t = Math.floorMod(timelineUs, lengthUs)
            val offset = Math.floorMod(entryOffsetUs + t, lengthUs)
            return loopStartUs + offset
        }
        val t = localTimelineUs(timelineUs)
        return when {
            t < loopStartUs -> t
            t < LoopWholeTimeline.TAIL_BASE_US -> {
                loopStartUs + Math.floorMod(t - loopStartUs, lengthUs)
            }
            else -> {
                val media = loopEndUs + (t - LoopWholeTimeline.TAIL_BASE_US)
                if (fullDurationUs > 0L) media.coerceAtMost(fullDurationUs) else media
            }
        }
    }

    /**
     * 对外播放时间 T → 应输出的第一个绝对帧号。
     *
     * LEGACY：始终落在环内 [aFrame,bFrame)。
     * WHOLE：前奏/尾奏返回媒体绝对帧；循环段仍落在环内。
     */
    fun frameAtTimeline(timelineUs: Long): Long {
        if (!wholeTimeline) {
            val frame = Pcm16Frames.frameAtOrAfterUs(mediaUs(timelineUs), sampleRate)
            return normalizeFrame(frame)
        }
        val t = localTimelineUs(timelineUs)
        return when {
            t < loopStartUs -> Pcm16Frames.frameAtOrAfterUs(t, sampleRate)
            t >= LoopWholeTimeline.TAIL_BASE_US ->
                Pcm16Frames.frameAtOrAfterUs(mediaUs(timelineUs), sampleRate)
            else -> {
                val frame = Pcm16Frames.frameAtOrAfterUs(mediaUs(timelineUs), sampleRate)
                if (frame < aFrame) aFrame
                else if (frame < bFrame) frame
                else aFrame + Math.floorMod(frame - aFrame, loopFrames)
            }
        }
    }

    /** RingBandReader 的起始环内 offset。WHOLE 从 A（offset 0）起，进前奏时环也先填 A。 */
    fun readerStartOffsetFrames(): Long =
        if (wholeTimeline) 0L
        else Math.floorMod(frameAtTimeline(0L) - aFrame, loopFrames)

    /** 把任意绝对帧号映射回 [aFrame, bFrame)。LEGACY/环内读取使用。 */
    fun normalizeFrame(frame: Long): Long {
        if (frame < aFrame) return aFrame
        if (frame < bFrame) return frame
        return aFrame + Math.floorMod(frame - aFrame, loopFrames)
    }

    fun blockFrames(block: Int): Long {
        require(block in 0 until blockCount) { "block out of range: $block / $blockCount" }
        return minOf(framesPerBlock.toLong(), loopFrames - block.toLong() * framesPerBlock)
    }

    fun blockStartFrame(block: Int): Long {
        require(block in 0 until blockCount) { "block out of range: $block / $blockCount" }
        return aFrame + block.toLong() * framesPerBlock
    }

    fun blockIndexForFrame(frameAbs: Long): Int {
        val frame = normalizeFrame(frameAbs)
        return ((frame - aFrame) / framesPerBlock).toInt()
    }

    /** 块 k 的解码起点（µs）：首块从 A 起，其余按帧对齐边界。 */
    fun blockDecodeStartUs(block: Int): Long {
        require(block in 0 until blockCount) { "block out of range: $block / $blockCount" }
        return if (block == 0) loopStartUs else frameUs(blockStartFrame(block))
    }

    /** 块 k 的解码终点（µs，右开）。 */
    fun blockDecodeEndUs(block: Int): Long {
        require(block in 0 until blockCount) { "block out of range: $block / $blockCount" }
        return frameUs(blockStartFrame(block) + blockFrames(block))
    }

    /** 帧号 → 媒体时间（floor），与 PcmDecoder 的 ceil(us*sr/1e6) 互逆。 */
    fun frameUs(frameAbs: Long): Long =
        if (frameAbs <= 0L) 0L else frameAbs * 1_000_000L / sampleRate

    fun cacheKey(): PcmBlockCacheKey = PcmBlockCacheKey(
        songId = songId,
        loopStartSamples = aFrame,
        loopEndSamples = bFrame,
        sampleRate = sampleRate,
        channels = channels,
        loopStartUs = loopStartUs,
        loopEndUs = loopEndUs,
    )

    /** 同一个窗口换进入点；A/B/整曲时长不变时块缓存 key 也不变。 */
    fun withEntryPosition(entryPositionUs: Long): LoopingPcmTimeline =
        LoopingPcmTimeline(
            songId = songId,
            loopStartUs = loopStartUs,
            loopEndUs = loopEndUs,
            format = format,
            entryPositionUs = entryPositionUs,
            fullDurationUs = fullDurationUs,
        )

    private fun window(): com.zjr.hesimusic.loop.LoopWindow =
        com.zjr.hesimusic.loop.LoopWindow(songId, loopStartUs, loopEndUs)

    private companion object {
        /** 容器时长与最后一个编码样本常差几十毫秒；B==dur 是合法整曲窗口。 */
        const val FULL_TAIL_SLACK_US: Long = 1_000_000L
    }
}
