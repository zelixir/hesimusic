package com.zjr.hesimusic.service

/**
 * 00029：用 invariantProbe.writtenFrames 的“停写间隔”度量入口可听空洞。
 *
 * tap PCM 不能用于空洞：tap 开启/换 sink 会固定丢 ~1050ms，且 source 重建时 tap
 * 不连续接管；writtenFrames 是 sink 真正交给 AudioTrack 的帧数，停写 = 用户听到静音。
 *
 * 段定义：从“上一次帧数增长”到“下一次帧数增长”的间隔 >= [minGapMs]。
 * - 第一条增长之前的静态期不算（页面暂停/冷启动前没有可听内容）；
 * - 最后一条增长之后的尾静音不算（采集结束/后续暂停）；
 * - 帧数回退（换 generation）按重新起算处理，不制造假空洞。
 */
object WriteGapAnalyzer {

    data class Sample(val atMs: Long, val writtenFrames: Long)

    data class Gap(val atMs: Long, val durationMs: Long)

    fun internalGaps(samples: List<Sample>, minGapMs: Long = 30L): List<Gap> {
        if (samples.size < 2) return emptyList()
        val sorted = samples.sortedBy { it.atMs }
        val gaps = ArrayList<Gap>()
        var lastFrames = Long.MIN_VALUE
        var lastIncreaseAt = -1L
        for (s in sorted) {
            // 第一条样本只作基线：前面的静态期（页面暂停/冷启动）不是可听空洞。
            if (lastFrames == Long.MIN_VALUE) {
                lastFrames = s.writtenFrames
                continue
            }
            if (s.writtenFrames > lastFrames) {
                if (lastIncreaseAt < 0L) {
                    // 第一次真正增长：只建立锚点，不结算它之前的静态期。
                    lastIncreaseAt = s.atMs
                } else {
                    val gap = s.atMs - lastIncreaseAt
                    if (gap >= minGapMs) gaps += Gap(atMs = lastIncreaseAt, durationMs = gap)
                    lastIncreaseAt = s.atMs
                }
                lastFrames = s.writtenFrames
            }
        }
        return gaps
    }

    fun maxGapMs(samples: List<Sample>, minGapMs: Long = 30L): Long =
        internalGaps(samples, minGapMs).maxOfOrNull { it.durationMs } ?: 0L
}
