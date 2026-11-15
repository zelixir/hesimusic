package com.zjr.hesimusic.loop

/**
 * 无缝循环窗口(A/B)。
 *
 * 时间轴口径为**微秒**(与音频层的帧/sampleRate 解耦):UI 标记、引擎环回、位置映射
 * 全部只认这一个口径,换算成帧只在音频管线内部发生。
 *
 * 窗口是 [loopStartUs, loopEndUs) 半开区间:起点包含、终点不包含。
 */
data class LoopWindow(
    val songId: Long,
    val loopStartUs: Long,
    val loopEndUs: Long,
) {
    /** 循环区间长度(µs)。非法窗口下可能 <= 0,用 [isValid] 判定后再参与算术。 */
    val lengthUs: Long get() = loopEndUs - loopStartUs

    /** songId 有效 && 区间长度 > 0 && 起点非负。 */
    fun isValid(): Boolean = songId > 0L && lengthUs > 0L && loopStartUs >= 0L

    /** 位置是否落在 [loopStartUs, loopEndUs) 内;非法窗口不包含任何位置。 */
    fun containsUs(us: Long): Boolean = isValid() && us >= loopStartUs && us < loopEndUs
}
