package com.zjr.hesimusic.lab

import com.zjr.hesimusic.loop.analysis.ProgressSink

/**
 * 算法实验专用 JNI 入口(algolab flavor;**只有** app/src/algolab 编译它)。
 *
 * 与生产入口 [com.zjr.hesimusic.loop.analysis.LoopEngineJni] 的关系:
 * - 同一个 native 库(`hesimusic_jni`)、同一次引擎管线;
 * - 生产入口不传任何参数(走生产默认 + 宿主 env 开关);
 * - 本入口把 Lab 参数**直接经 JNI 传入**,不依赖环境变量(app 内 setenv 不可靠)。
 *
 * 返回 DoubleArray 的布局见 native `loop_engine/lab_flat.h`
 * (6 个 header + 18 × N;版本 1)。[LabResultParser] 是唯一解析点。
 */
object LabEngineJni {
    init { System.loadLibrary("hesimusic_jni") }

    /**
     * 一次解码、一次分析,同时产出初版(legacy)与融合(fused)两套分数/排名。
     *
     * @param monoPcm 已解码的 mono float PCM(16bit 满幅尺度,引擎内部归一化)
     * @param sampleRate 源采样率
     * @param maxCandidates 候选数量 N(同时决定接缝候选池规模)
     * @param seamEnabled false = 只跑初版路径(用于性能基线;此时没有接缝分项)
     * @param seamWeight 融合权重 w:score = (1-w)*legacy + w*seamQuality
     * @param corrWeight / @param rmsWeight / @param fluxWeight 接缝质量三项权重(引擎归一化)
     * @param refineEnabled 亚拍精修开关
     * @param refineRangeMs 精修搜索半径(0..100ms;0 = 不搜索)
     * @param sink 进度回调(≤20Hz;抛异常 = 取消,引擎提前返回)
     */
    external fun analyzeLab(
        monoPcm: FloatArray,
        sampleRate: Int,
        maxCandidates: Int,
        seamEnabled: Boolean,
        seamWeight: Double,
        corrWeight: Double,
        rmsWeight: Double,
        fluxWeight: Double,
        refineEnabled: Boolean,
        refineRangeMs: Int,
        sink: ProgressSink?,
    ): DoubleArray
}
