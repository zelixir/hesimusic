package com.zjr.hesimusic.loop.audio.source

/**
 * 环带读取器：纯 JVM，不依赖解码器。
 *
 * - offset 以帧为单位，跨 L 自动取模；
 * - 可读性由环的**帧级覆盖位图**决定：只返回 [offset, offset+count) 中连续已填充的部分，
 *   未填充区返回 null（Extractor -> CONTINUE），因此 seek 到未填充区不必等解码从 A 追上来；
 * - seek 只改 offset，不重解码；
 * - **PTS 的基准由 seek 决定**（issue 00023 裁决 a）：段内严格单调，段与段之间允许回退
 *   （media3 在 seek 后会丢弃旧样本队列，PTS 比 seek 前小是合法的）；
 * - 参与节流预算/覆盖率统计的 [totalReadFrames] 仍然只增不减。
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

    /** 等待未填充数据的次数（不是每次 read 的失败次数）。 */
    @Volatile
    var underruns: Long = 0L
        private set

    @Volatile
    var lastSeamOutputFrames: Long = -1L
        private set

    /**
     * 本段 PTS 基准（µs）= 最近一次 seek 的目标；播放起点为 0。
     *
     * 旧实现把 PTS 固定成"本 source 已读总帧数"，seek 回退时被 max() 留在读前位置
     * （真机：区内回退到 25s 却停在 46s；区外 seek 1458 落到 13606/24161）。
     */
    @Volatile
    var segmentBasePtsUs: Long = 0L
        private set

    /** 下一帧的 PTS（µs）：段内严格单调，seek 时重置为 [segmentBasePtsUs]。 */
    @Volatile
    private var nextPtsUs: Long = 0L

    val filledFrames: Long get() = ring.coveredFrames

    /** 整环是否可读（会回绕的播放必须整环已填充）。 */
    val isRingComplete: Boolean get() = ring.isFullyCovered

    fun read(maxFrames: Int): Read? {
        if (maxFrames <= 0) return null
        val offset = offsetFrames
        // 只读连续已填充的一段：跳填后目标区可立刻出数据，不必等整个高水位。
        val available = ring.contiguousFilledFrames(offset, maxFrames.toLong())
        if (available <= 0L) return null

        val frames = available.toInt()
        val data = ring.copyFrames(offset, frames)
        val seam = if (offset + frames >= bandFrames) {
            totalReadFrames + (bandFrames - offset)
        } else {
            null
        }
        // PTS = 本段基准 + 本段已读帧（不再与 totalReadFrames 取 max：那是"全局只增"，
        // 会让 seek 回退失效）。段内单调由 nextPtsUs 的累加保证。
        val pts = nextPtsUs
        nextPtsUs = pts + frames.toLong() * 1_000_000L / ring.sampleRate
        offsetFrames = Math.floorMod(offset + frames, bandFrames)
        totalReadFrames += frames.toLong()
        reads += 1L
        if (seam != null) lastSeamOutputFrames = seam
        return Read(data, frames, pts, offset)
    }

    /** 等到环内 [offsetFrames] 起 [countFrames] 帧可读（有新填充会唤醒）。 */
    fun awaitFilled(offsetFrames: Long, countFrames: Long, timeoutMs: Long): Boolean =
        ring.awaitRangeFilled(offsetFrames, countFrames, timeoutMs)

    /** Extractor 每等待一次未填充数据调用一次。 */
    fun recordWait() {
        underruns += 1L
    }

    /**
     * seek 目标为环内帧偏移 [0,L)，只改 offset（跳填由 Extractor 通知 renderer）。
     *
     * **PTS 基准重置为 seek 目标**（允许回退）：media3 的 SeekPoint 是 (p,p)，
     * 首个样本 PTS 取 target 正好落在 seek 位置上；段内仍严格单调。
     */
    fun seekToFrame(offset: Long, targetUs: Long = 0L) {
        offsetFrames = Math.floorMod(offset, bandFrames)
        val base = targetUs.coerceAtLeast(0L)
        segmentBasePtsUs = base
        nextPtsUs = base
    }

    fun seekToOffset(frames: Long, targetUs: Long = 0L) = seekToFrame(frames, targetUs)

    fun seekToUs(timeUs: Long) {
        val t = timeUs.coerceAtLeast(0L)
        val frames = (t * ring.sampleRate / 1_000_000L)
        seekToFrame(frames, t)
    }
}
