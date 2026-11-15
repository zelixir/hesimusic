package com.zjr.spike

/**
 * 运行级共享状态（tap 开关在 WavWriter 创建前就需要知道）。
 */
object SpikeRun {
    @Volatile
    var tapEnabled: Boolean = true
}
