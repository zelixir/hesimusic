package com.zjr.hesimusic.loop.analysis

/**
 * JNI 平铺 DoubleArray ↔ [LoopCandidateKt] 的纯函数解析(可 JVM 单测)。
 *
 * 引擎/JNI 契约:每 5 个 double 一个候选 [startSamples, endSamples, score,
 * noteDistance, loudnessDifference],按 score 降序。任何结构不符(空数组、
 * 长度 <5、长度非 5 的倍数)一律返回空列表,不猜测截断语义。
 */
object LoopCandidates {

    private const val STRIDE = 5

    fun parse(flat: DoubleArray?): List<LoopCandidateKt> {
        if (flat == null || flat.size < STRIDE || flat.size % STRIDE != 0) return emptyList()
        val list = ArrayList<LoopCandidateKt>(flat.size / STRIDE)
        var i = 0
        while (i + STRIDE <= flat.size) {
            list += LoopCandidateKt(
                startSamples = flat[i].toLong(),
                endSamples = flat[i + 1].toLong(),
                score = flat[i + 2],
                noteDistance = flat[i + 3],
                loudnessDifference = flat[i + 4],
            )
            i += STRIDE
        }
        // 引擎已排序;防御性再排一次(稳定排序,同分保持引擎顺序)。
        return list.sortedByDescending { it.score }
    }
}
