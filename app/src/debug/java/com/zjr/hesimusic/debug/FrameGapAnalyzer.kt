package com.zjr.hesimusic.debug

/** 停写段分析（纯 JVM，供 FrameGapRecorder 与单测共用）。 */
object FrameGapAnalyzer {

    private const val MIN_THRESHOLD_MS = 60L
    private const val THRESHOLD_FACTOR = 3L

    data class Gap(val startAtMs: Long, val durationMs: Long)

    data class Result(
        val samples: Int,
        val sampleIntervalP50Ms: Long,
        val writeIntervalP50Ms: Long,
        val thresholdMs: Long,
        val gaps: List<Gap>,
        val lastGapMs: Long,
    )

    fun analyze(
        stampsMs: LongArray,
        frames: LongArray,
        windowMs: Long,
        nowMs: Long,
    ): Result {
        require(stampsMs.size == frames.size)
        val from = nowMs - windowMs
        var total = 0
        for (i in stampsMs.indices) if (stampsMs[i] >= from) total += 1
        val ts = LongArray(total)
        val fs = LongArray(total)
        var p = 0
        for (i in stampsMs.indices) {
            if (stampsMs[i] >= from) {
                ts[p] = stampsMs[i]
                fs[p] = frames[i]
                p += 1
            }
        }
        val sampleIntervals = if (total >= 2) LongArray(total - 1) else LongArray(0)
        for (i in 1 until total) sampleIntervals[i - 1] = ts[i] - ts[i - 1]
        sampleIntervals.sort()
        val sampleP50 = if (sampleIntervals.isEmpty()) 0L else sampleIntervals[sampleIntervals.size / 2]

        // 写帧间隔：相邻两次 frames 变化之间的时间（正常节奏由 sink buffer 大小决定）。
        val writeIntervals = ArrayList<Long>()
        val gaps = ArrayList<Gap>()
        var lastChange = -1L
        for (i in 0 until total) {
            if (lastChange >= 0L && fs[i] != fs[i - 1]) {
                val delta = ts[i] - lastChange
                if (delta > 0L) writeIntervals += delta
                lastChange = ts[i]
            } else if (lastChange < 0L) {
                lastChange = ts[i]
            }
        }
        writeIntervals.sort()
        val writeP50 = if (writeIntervals.isEmpty()) 0L else writeIntervals[writeIntervals.size / 2]
        val threshold = maxOf(MIN_THRESHOLD_MS, writeP50 * THRESHOLD_FACTOR)

        // 用实际“两次 frame 变化之间的间隔”判段，不把 5ms 采样平台算进去。
        lastChange = -1L
        for (i in 0 until total) {
            if (lastChange < 0L) {
                lastChange = ts[i]
            } else if (fs[i] != fs[i - 1]) {
                val duration = ts[i] - lastChange
                if (duration >= threshold) gaps += Gap(lastChange, duration)
                lastChange = ts[i]
            }
        }
        if (lastChange >= 0L) {
            val duration = nowMs - lastChange
            if (duration >= threshold) gaps += Gap(lastChange, duration)
        }
        return Result(
            samples = total,
            sampleIntervalP50Ms = sampleP50,
            writeIntervalP50Ms = writeP50,
            thresholdMs = threshold,
            gaps = gaps,
            lastGapMs = gaps.lastOrNull()?.durationMs ?: 0L,
        )
    }
}
