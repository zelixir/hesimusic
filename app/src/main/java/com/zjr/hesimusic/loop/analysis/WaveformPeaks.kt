package com.zjr.hesimusic.loop.analysis

/**
 * 波形峰值缓存(契约 §2.4;UI Canvas 直接用 mn/mx 绘制)。
 *
 * 语义与原型 D:/Code/tmp/loop-player/index.html 的 buildPeaks 一致:
 * 时间轴等分 buckets 桶(默认 3000),每桶取 mono 混叠样本的 min/max;
 * 归一化到 [-1,1](mono 来自 16bit PCM,除以 32768 满幅)。
 */
data class WaveformPeaks(val mn: FloatArray, val mx: FloatArray, val buckets: Int) {

    companion object {
        const val DEFAULT_BUCKETS = 3000

        /** 16bit PCM 满幅 → float [-1,1)。 */
        const val PCM16_SCALE = 32768f

        /**
         * 在 mono 混叠流上按原型 buildPeaks 语义计算峰值:
         * step = max(1, len / buckets);空桶(样本越界)保持 0。
         * alreadyNormalized = true 时输入已是 [-1,1](统一解码的派生流),跳过 ÷32768。
         */
        fun buildPeaks(
            mono: FloatArray,
            buckets: Int = DEFAULT_BUCKETS,
            alreadyNormalized: Boolean = false,
        ): WaveformPeaks {
            val n = if (buckets > 0) buckets else DEFAULT_BUCKETS
            val mn = FloatArray(n)
            val mx = FloatArray(n)
            val len = mono.size
            if (len == 0) return WaveformPeaks(mn, mx, n)
            val step = maxOf(1, len / n)
            for (i in 0 until n) {
                var lo = 0f
                var hi = 0f
                val s0 = i * step
                if (s0 >= len) continue
                val s1 = minOf(len, s0 + step)
                for (j in s0 until s1) {
                    val v = if (alreadyNormalized) mono[j] else mono[j] / PCM16_SCALE
                    if (v < lo) lo = v
                    if (v > hi) hi = v
                }
                mn[i] = lo
                mx[i] = hi
            }
            return WaveformPeaks(mn, mx, n)
        }
    }
}
