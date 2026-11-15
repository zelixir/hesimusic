package com.zjr.hesimusic.loop.analysis

/**
 * 分析进度映射(契约 §1.2 / v2 §5.2):解码 0→0.5、分析 0.5→1.0,
 * 全局单调(每阶段 merge 时 clamp 到上一次值)。
 *
 * 纯函数:无 Android/协程依赖,可 JVM 单测。
 */
object LoopProgress {

    /** 解码阶段结束值,也是分析阶段起点。 */
    const val DECODE_END = 0.5f

    enum class Phase { DECODING, ANALYZING }

    /**
     * 把单阶段原始进度 [fraction]∈[0,1] 映射为全局进度,并与 [last](上一次全局进度)
     * 取 max 保证单调;越界/NaN 输入被 clamp/归零,返回 ∈[0,1]。
     */
    fun merge(phase: Phase, fraction: Float, last: Float): Float {
        val p = if (fraction.isNaN()) 0f else fraction.coerceIn(0f, 1f)
        val mapped = when (phase) {
            Phase.DECODING -> p * DECODE_END
            Phase.ANALYZING -> DECODE_END + (1f - DECODE_END) * p
        }
        val prev = if (last.isNaN()) 0f else last.coerceIn(0f, 1f)
        return maxOf(prev, mapped)
    }
}
