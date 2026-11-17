package com.zjr.hesimusic.loop.audio.source

import com.zjr.hesimusic.loop.analysis.Pcm16Frames

/**
 * 循环数据源的纯 JVM 时间轴/帧号算术。
 *
 * 口径：
 * - 窗口是 [loopStartUs, loopEndUs)，A/B 是媒体时间；
 * - 帧网格锚定在 **A 之后第一个整帧** `aFrame = ceil(A*sr/1e6)`，
 *   环长度 `loopFrames = bFrame - aFrame`；
 * - 对外播放时间 T 从 0 开始单调递增；进入循环时的媒体位置 E 决定
 *   `entryOffsetUs = floorMod(E - A, L)`；
 * - `mediaUs(T) = A + floorMod(entryOffsetUs + T, L)`，到 B 时自然回到 A，
 *   **不产生 seek、不重置 T**；
 * - 块大小固定 1 秒：`framesPerBlock = sampleRate`，块首帧严格为
 *   `aFrame + k*framesPerBlock`，最后一块可短于 1 秒。
 */
class LoopingPcmTimeline(
    val songId: Long,
    val loopStartUs: Long,
    val loopEndUs: Long,
    val format: PcmTrackFormat,
    entryPositionUs: Long = loopStartUs,
) {

    init {
        require(songId > 0L) { "songId must be > 0" }
        require(loopStartUs >= 0L) { "loopStartUs must be >= 0" }
        require(loopEndUs > loopStartUs) { "loopEndUs must be > loopStartUs" }
    }

    val sampleRate: Int get() = format.sampleRate
    val channels: Int get() = format.channels
    val bytesPerFrame: Int get() = format.bytesPerFrame

    /** 窗口长度（µs）。 */
    val lengthUs: Long get() = loopEndUs - loopStartUs

    /** A 之后第一个整帧的绝对帧号。 */
    val aFrame: Long = Pcm16Frames.frameAtOrAfterUs(loopStartUs, sampleRate)

    /** B 之后第一个整帧的绝对帧号（环的右开边界）。 */
    val bFrame: Long = Pcm16Frames.frameAtOrAfterUs(loopEndUs, sampleRate)

    /** 环内帧数。 */
    val loopFrames: Long = bFrame - aFrame

    /** 块大小：1 秒。 */
    val framesPerBlock: Int = sampleRate

    /** 块数；最后一块可以短于 1 秒。 */
    val blockCount: Int = ((loopFrames + framesPerBlock - 1L) / framesPerBlock).toInt()

    /** 进入点相对 A 的偏移，落在 [0, L)。 */
    val entryOffsetUs: Long = Math.floorMod(
        entryPositionUs - loopStartUs,
        lengthUs.coerceAtLeast(1L),
    )

    init {
        require(loopFrames > 0L) { "loop window has no full frame: A=$loopStartUs B=$loopEndUs" }
        require(blockCount > 0) { "loop window has no block" }
    }

    /** 对外播放时间 T → 媒体时间（µs）。 */
    fun mediaUs(timelineUs: Long): Long {
        if (timelineUs <= 0L) {
            return loopStartUs + entryOffsetUs
        }
        val t = Math.floorMod(timelineUs, lengthUs)
        val offset = Math.floorMod(entryOffsetUs + t, lengthUs)
        return loopStartUs + offset
    }

    /**
     * 对外播放时间 T → 应输出的第一个绝对帧号。
     *
     * mediaUs(T) 落在帧间时向上取整；若恰好到 B 则回到 A（环右开）。
     */
    fun frameAtTimeline(timelineUs: Long): Long {
        val frame = Pcm16Frames.frameAtOrAfterUs(mediaUs(timelineUs), sampleRate)
        return normalizeFrame(frame)
    }

    /** 把任意绝对帧号映射回 [aFrame, bFrame)。 */
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

    /** 同一个窗口换进入点；A/B 不变时块缓存 key 也不变。 */
    fun withEntryPosition(entryPositionUs: Long): LoopingPcmTimeline =
        LoopingPcmTimeline(
            songId = songId,
            loopStartUs = loopStartUs,
            loopEndUs = loopEndUs,
            format = format,
            entryPositionUs = entryPositionUs,
        )
}
