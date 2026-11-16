package com.zjr.hesimusic.ui.loop

import com.zjr.hesimusic.ui.loop.state.LoopPageMode

/** UI/播放器模式 <-> 纯模型模式。模型层不依赖 PreviewMode。 */
fun PreviewMode.toPageMode(): LoopPageMode = when (this) {
    PreviewMode.FULL -> LoopPageMode.FULL
    PreviewMode.LOOP_ONLY -> LoopPageMode.LOOP_ONLY
    PreviewMode.SEAM -> LoopPageMode.SEAM
    PreviewMode.FREE -> LoopPageMode.FREE
}

fun LoopPageMode.toPreviewMode(): PreviewMode = when (this) {
    LoopPageMode.FULL -> PreviewMode.FULL
    LoopPageMode.LOOP_ONLY -> PreviewMode.LOOP_ONLY
    LoopPageMode.SEAM -> PreviewMode.SEAM
    LoopPageMode.FREE -> PreviewMode.FREE
}
