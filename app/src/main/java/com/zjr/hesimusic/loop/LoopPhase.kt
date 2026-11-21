package com.zjr.hesimusic.loop

/**
 * 主播放器循环的三阶段（issue 00028）。
 *
 * 状态完全由**播放器展示位置**决定，UI/调试口不得自己猜：
 * - display < A                 -> [PRELUDE] 前奏，正常播放；
 * - A <= display < B            -> [LOOP]    循环段，到 B 由数据源折回 A；
 * - display >= B                -> [TAIL]    尾奏，只有 seek 到 B 之后才会进入，播到曲末回 0。
 *
 * 边界口径与窗口一致：A 属于 loop，B 是右开边界（B 属于 tail）。
 */
enum class LoopPhase(val wire: String) {
    PRELUDE("prelude"),
    LOOP("loop"),
    TAIL("tail"),
    ;

    companion object {
        /** 展示位置 -> 阶段；窗口非法/缺失返回 null（此时不得声称任何循环阶段）。 */
        fun ofDisplayUs(displayUs: Long, window: LoopWindow?): LoopPhase? {
            if (window == null || !window.isValid()) return null
            return when {
                displayUs < window.loopStartUs -> PRELUDE
                displayUs < window.loopEndUs -> LOOP
                else -> TAIL
            }
        }
    }
}
