package com.zjr.hesimusic.loop

/**
 * 位置/seek 口径映射(纯函数,无状态、无时序)。
 *
 * 口径约定:
 * - **原始位置(raw)**:引擎(ExoPlayer)自己的播放位置。无环回时它与媒体时间轴一致;
 *   环回时引擎会把第 k 轮的起点当作 a + k×L 继续推进(见 [LoopPosition.iterationStartUs])。
 * - **展示位置(display)**:UI/通知/持久化看到的进度,永远折返进 [a, b)。
 * - **前奏**:raw < a 的部分只播一次,display 原样返回。
 */
object LoopPosition {

    /**
     * 引擎原始位置 -> 展示位置。
     *
     * raw < loopStartUs 时原样返回(前奏只播一次);否则折返到 [start, end)。
     * 非法窗口(见 [LoopWindow.isValid])不做任何折返,原样返回,避免把坏数据放大成跳变。
     */
    fun displayUs(rawUs: Long, w: LoopWindow): Long {
        if (!w.isValid()) return rawUs
        if (rawUs < w.loopStartUs) return rawUs
        return w.loopStartUs + Math.floorMod(rawUs - w.loopStartUs, w.lengthUs)
    }

    /**
     * 用户/UI seek 目标(展示口径)-> 引擎原始 seek 位置。
     *
     * - target < start:原样返回(回到前奏),播到 b 后自然进入折返;
     * - start <= target < end:映射到**第 0 轮**的同一相位(start + (target - start))。
     *   **不再按当前轮次 k 偏移**:跨轮映射会让「拖到 35s」变成「seek 到 65s(第 1 轮)」,
     *   而引擎随后要按输入 PTS 重新定相,任何对不齐都会让落点漂到别的相位(issue 00001 复现到的
     *   「拖到区间中→落到 A 附近」)。固定落在第 0 轮后,seek 的目标 = 引擎的输入 PTS,
     *   位置确定、可预测;向后 seek 由引擎的 discontinuity 处理(不再向后预卷)。
     * - target >= end:折返到 start + ((target-start) mod L)(同样落在第 0 轮坐标)。
     *
     * 非法窗口原样返回 target。
     */
    fun rawSeekUs(targetUs: Long, w: LoopWindow): Long {
        if (!w.isValid()) return targetUs
        val start = w.loopStartUs
        val length = w.lengthUs
        return when {
            targetUs < start -> targetUs
            targetUs >= w.loopEndUs -> start + Math.floorMod(targetUs - start, length)
            else -> start + (targetUs - start)
        }
    }

    /**
     * 第 k 轮的原始起点 = start + k×L(测试/引擎用)。
     * 非法窗口或 k <= 0 时返回 loopStartUs(第 0 轮起点)。
     */
    fun iterationStartUs(k: Long, w: LoopWindow): Long {
        if (!w.isValid() || k <= 0L) return w.loopStartUs
        return w.loopStartUs + k * w.lengthUs
    }

    /**
     * 原始位置当前处于第几轮(0 起):<= currentRawUs 的最大 k。
     * 前奏期(currentRawUs < start)与非法窗口返回 0。
     *
     * 仅模块内可见:公开契约只需要上面三个函数,引擎/测试用它做环缓冲轮次定位。
     */
    internal fun iterationIndexUs(currentRawUs: Long, w: LoopWindow): Long {
        if (!w.isValid() || currentRawUs < w.loopStartUs) return 0L
        return (currentRawUs - w.loopStartUs) / w.lengthUs
    }
}
