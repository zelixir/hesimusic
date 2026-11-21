package com.zjr.hesimusic.loop.analysis

/**
 * JNI 平铺 DoubleArray ↔ [LoopCandidateKt] 的纯函数解析(可 JVM 单测)。
 *
 * 当前契约(v8.1):每 7 个 double 一个候选
 * `[startSamples, endSamples, score, noteDistance, loudnessDifference, seamQuality, legacyScore]`,
 * 按 score(融合分)降序。v8/v8.1 只在**尾部追加**字段,前 5 个字段的位置与语义不变;
 * 解析器同时接受:
 * - 7 字段(当前):seamQuality 与 legacyScore 都按第 6/7 个字段读取;
 * - 6 字段(v8 过渡):seamQuality 读第 6 个;legacyScore 未知 → 0(UI 回退显示综合分);
 * - 5 字段(v8 之前):没有 seam → 0(未知);此时 score **就是**旧语义分 → legacyScore = score。
 *
 * 结构不符(空数组、长度不足一个候选、长度非 5/6/7 的倍数)一律返回空列表,
 * 不猜测截断语义。长度为公倍数时按**更大的新契约优先**解释(30→6 字段、35/42→7 字段):
 * 正常 APK 内 JNI 与解析器同版本,不会混用;这里只做防御。
 */
object LoopCandidates {

    /** 当前 JNI 契约:7 字段/候选(第 6 个 seamQuality,第 7 个 legacyScore)。 */
    private const val STRIDE = 7

    /** v8 过渡契约:6 字段/候选(有 seamQuality,没有 legacyScore)。 */
    private const val V8_STRIDE = 6

    /** v8 之前的旧契约:5 字段/候选(没有 seamQuality,score 即 legacy)。 */
    private const val LEGACY_STRIDE = 5

    fun parse(flat: DoubleArray?): List<LoopCandidateKt> {
        if (flat == null) return emptyList()
        val stride = resolveStride(flat.size) ?: return emptyList()
        val list = ArrayList<LoopCandidateKt>(flat.size / stride)
        var i = 0
        while (i + stride <= flat.size) {
            val score = flat[i + 2]
            val seamQuality = if (stride >= V8_STRIDE) {
                sanitizeNonFinite(flat[i + 5])
            } else {
                0.0
            }
            val legacyScore = when (stride) {
                STRIDE -> sanitizeNonFinite(flat[i + 6])
                // v8 之前的 score 就是旧语义分(排序从未融合),直接带出即可。
                LEGACY_STRIDE -> sanitizeNonFinite(score)
                // 6 字段过渡格式的 score 是融合分,legacy 没有透出 → 未知(0)。
                else -> 0.0
            }
            list += LoopCandidateKt(
                startSamples = flat[i].toLong(),
                endSamples = flat[i + 1].toLong(),
                score = score,
                noteDistance = flat[i + 3],
                loudnessDifference = flat[i + 4],
                seamQuality = seamQuality,
                legacyScore = legacyScore,
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
        size % STRIDE == 0 -> STRIDE
        size % V8_STRIDE == 0 -> V8_STRIDE
        size % LEGACY_STRIDE == 0 -> LEGACY_STRIDE
        else -> null
    }

    /** JNI 关闭接缝阶段时写 0;NaN/Inf 是"未知"的另一种写法,统一归一成 0。 */
    private fun sanitizeNonFinite(value: Double): Double = if (value.isFinite()) value else 0.0
}
