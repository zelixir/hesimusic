package com.zjr.spike.loopsource

import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi

/**
 * M1 环回源配置（整曲坐标，单位 µs）。
 *
 * 坐标约定（见 docs/loop-v3-m1-design.md §2.1.1）：
 *  - 第 0 轮 = 前奏：内容 [0, bUs)，base = 0，只播一次；
 *  - 第 j 轮（j>=1）：内容 [aUs, bUs)，base = bUs + (j-1)*loopUs；
 *  - 引擎原始时间轴单调递增，不折返（折返是会话层 LoopPosition 的事）。
 */
@UnstableApi
data class LoopSourceConfig(
    val mediaItem: MediaItem,
    val aUs: Long,
    val bUs: Long,
    val durationUs: Long,
) {
    val loopUs: Long get() = bUs - aUs

    init {
        require(aUs >= 0L) { "aUs must be >= 0: " + aUs }
        require(bUs > aUs) { "bUs must be > aUs: aUs=" + aUs + " bUs=" + bUs }
    }
}
