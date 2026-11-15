package com.zjr.spike.loopsource

import androidx.media3.common.util.UnstableApi

/**
 * 轮次打点（仅取证/调试用）。每次“自然”切换到新一轮（读到上一轮 EOS 后成功切轮）时回调。
 * seek 造成的新轮不打点（那不是环回接缝）。
 */
@UnstableApi
interface IterationTicker {
    /**
     * @param iter 新轮次序号：1 = 第一次重复（前奏 = 第 0 轮）
     * @param baseUs 该轮在原始时间轴上的基准（= 该轮时间戳的理论起点）
     * @param firstPtsUs 该轮第一个样本**改写后**的时间戳（= baseUs + 切口内偏移）
     */
    fun onRoundSwitch(iter: Int, baseUs: Long, firstPtsUs: Long)
}
