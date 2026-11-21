package com.zjr.hesimusic.loop.analysis

/**
 * JNI 平铺 DoubleArray ↔ [LoopCandidateKt] 的纯函数解析(可 JVM 单测)。
 *
 * 当前契约(v8):每 6 个 double 一个候选
 * `[startSamples, endSamples, score, noteDistance, loudnessDifference, seamQuality]`,
 * 按 score 降序。v8 只在**尾部追加** seamQuality,前 5 个字段的位置与语义不变;
 * 解析器同时接受旧的 5 字段数组(seamQuality 读作 0 = 未知),便于旧引擎/旧数据共存。
 *
 * 结构不符(空数组、长度不足一个候选、长度既非 6 也非 5 的倍数)一律返回空列表,
 * 不猜测截断语义。长度为 30/60/…(5 与 6 的公倍数)时按**当前契约 6 字段**解释:
 * maxCandidates=10 时只有 N=5 的旧数组会撞上,正常 APK 内 JNI 与解析器同版本,不会混用。
 */
object LoopCandidates {

    /** 当前 JNI 契约:6 字段/候选(第 6 个是 seamQuality)。 */
    private const val STRIDE = 6

    /** v8 之前的旧契约:5 字段/候选(没有 seamQuality)。 */
    private const val LEGACY_STRIDE = 5

    fun parse(flat: DoubleArray?): List<LoopCandidateKt> {
        if (flat == null) return emptyList()
        val stride = resolveStride(flat.size) ?: return emptyList()
        val list = ArrayList<LoopCandidateKt>(flat.size / stride)
        var i = 0
        while (i + stride <= flat.size) {
            val seamQuality = if (stride == STRIDE) sanitizeSeamQuality(flat[i + 5]) else 0.0
            list += LoopCandidateKt(
                startSamples = flat[i].toLong(),
                endSamples = flat[i + 1].toLong(),
                score = flat[i + 2],
                noteDistance = flat[i + 3],
                loudnessDifference = flat[i + 4],
                seamQuality = seamQuality,
            )
            i += stride
        }
        // 引擎已排序;防御性再排一次(稳定排序,同分保持引擎顺序)。
        return list.sortedByDescending { it.score }
    }

    /**
     * @return 每个候选占用的 double 数;null = 长度不构成任何合法记录(整体拒绝)。
     */
    private fun resolveStride(size: Int): Int? = when {
        size < LEGACY_STRIDE -> null
        size % STRIDE == 0 -> STRIDE // 优先当前契约(公倍数 30 也走这里,见类注释)
        size % LEGACY_STRIDE == 0 -> LEGACY_STRIDE
        else -> null
    }

    /** JNI 关闭接缝阶段时写 0;NaN/Inf 是"未知"的另一种写法,统一归一成 0。 */
    private fun sanitizeSeamQuality(value: Double): Double = if (value.isFinite()) value else 0.0
}
