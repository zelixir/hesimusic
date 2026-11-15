package com.zjr.hesimusic.loop.analysis

/**
 * 循环分析引擎 JNI 入口(契约 docs/loop-v3-plan.md §3.1)。
 *
 * JNI 只做数据搬运,算法在 native loop_engine(loop_engine/loop_engine.h)。
 * native 符号固定为 Java_com_zjr_hesimusic_loop_analysis_LoopEngineJni_*,
 * 与 [com.zjr.hesimusic.loop.analysis.ProgressSink] 的类名一一对应。
 */
object LoopEngineJni {
    init { System.loadLibrary("hesimusic_jni") }

    /** mono PCM → 候选,无进度回调。返回 DoubleArray =
     *  [startSamples, endSamples, score, noteDistance, loudnessDifference] × N,
     *  按 score 降序;失败返回空数组 */
    external fun analyzePcm(monoPcm: FloatArray, sampleRate: Int, maxCandidates: Int): DoubleArray

    /**
     * 带进度回调的重载(≤20Hz,0..1;sink 抛异常 = 取消分析,返回空数组)。
     * 重载本体是普通函数,实际 native 符号唯一(analyzePcmWithProgress),
     * 避免 JNI 对重载 native 方法的名字改编风险。
     */
    fun analyzePcm(
        monoPcm: FloatArray,
        sampleRate: Int,
        maxCandidates: Int,
        sink: ProgressSink?,
    ): DoubleArray = analyzePcmWithProgress(monoPcm, sampleRate, maxCandidates, sink)

    private external fun analyzePcmWithProgress(
        monoPcm: FloatArray,
        sampleRate: Int,
        maxCandidates: Int,
        sink: ProgressSink?,
    ): DoubleArray
}
