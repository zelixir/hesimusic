package com.zjr.hesimusic.loop.analysis

/** 分析进度回调(契约 v2 §1.2)。fraction ∈ [0,1],回调频率 ≤ 20Hz(引擎/JNI 侧节流)。 */
fun interface ProgressSink {
    fun onProgress(fraction: Float)
}
