package com.zjr.hesimusic.loop.audio.source

/**
 * 环带读取器：纯 JVM，不依赖解码器。
 *
 * - offset 以帧为单位，单调推进，跨 L 自动取模；
 * - 环未满时，只能读 offset < renderedFrames 的部分，越界返回 null（Extractor -> CONTINUE）；
 * - 环满后任意 offset 可读；
 * - seek 只改 offset，不重解码。
 */
class RingBandReader(
    private val ring: LoopRing,
    startOffsetFrames: Long = 0L,
) {

    data class Read(
        val data: ByteArray,
        val frames: Int,
        val ptsUs: Long,
        val offsetFrames: Long,
    )

    private val bandFrames = ring.frames
    private val bpf = ring.bytesPerFrame

    @Volatile
    var offsetFrames: Long = Math.floorMod(startOffsetFrames, bandFrames)
        private set

    @Volatile
    var totalReadFrames: Long = 0L
        private set

    @Volatile
    var reads: Long = 0L
        private set

    @Volatile
    var underruns: Long = 0L
        private set

    @Volatile
    var lastSeamOutputFrames: Long = -1L
        private set

    /** 环的渲染高水位（诊断用）。 */
    val filledFrames: Long get() = ring.renderedFrames

    fun read(maxFrames: Int): Read? {
        if (maxFrames <= 0) return null
        val filled = ring.renderedFrames
        if (filled <= 0L) {
            underruns += 1L
            return null
        }
        val offset = offsetFrames
        val available = if (filled >= bandFrames) {
            minOf(maxFrames.toLong(), bandFrames).toInt()
        } else {
            // 未满时不能跨过已渲染高水位，也不能读入未初始化区域。
            if (offset >= filled) {
                underruns += 1L
                return null
            }
            minOf(maxFrames.toLong(), filled - offset).toInt()
        }
        if (available <= 0) {
            underruns += 1L
            return null
        }

        val data = ring.copyFrames(offset, available)
        val seam = if (filled >= bandFrames && offset + available >= bandFrames) {
            totalReadFrames + (bandFrames - offset)
        } else {
            null
        }
        val pts = ring.presentationTimeUs(totalReadFrames)
        offsetFrames = Math.floorMod(offset + available, bandFrames)
        totalReadFrames += available.toLong()
        reads += 1L
        if (seam != null) lastSeamOutputFrames = seam
        return Read(data, available, pts, offset)
    }

    /** seek 目标为环内帧偏移 [0,L)，只改 offset。 */
    fun seekToFrame(offset: Long) {
        offsetFrames = Math.floorMod(offset, bandFrames)
    }

    fun seekToUs(timeUs: Long) {
        val frames = (timeUs.coerceAtLeast(0L) * ring.sampleRate / 1_000_000L)
        seekToFrame(frames)
    }

    fun readFrames(data: ByteArray): Int = data.size / bpf
}
