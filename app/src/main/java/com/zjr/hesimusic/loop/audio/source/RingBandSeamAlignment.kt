package com.zjr.hesimusic.loop.audio.source

/**
 * 环带首块/任意解码 buffer 的帧域对齐（issue 00029 抽出，纯 JVM 可单测）。
 *
 * 契约：buffer 的首帧对应媒体帧 [bufferStartFrame]；目标首帧是 [targetStartFrame]。
 * 需要跳过 buffer 内位于目标之前的帧；buffer 首帧已经晚于目标时无法回退（返回 0，
 * 由调用方决定补静音/重叠）。
 */
object RingBandSeamAlignment {

    /** 从 buffer 首帧跳到目标首帧需要丢弃的帧数（不会为负）。 */
    fun skipFrames(bufferStartFrame: Long, targetStartFrame: Long): Long =
        (targetStartFrame - bufferStartFrame).coerceAtLeast(0L)

    /** buffer 在跳过 [skipFrames] 后还能提供多少帧（不超过剩余目标帧数）。 */
    fun takeFrames(
        bufferFrames: Long,
        skipFrames: Long,
        targetFrames: Long,
        emittedFrames: Long,
    ): Long {
        val available = (bufferFrames - skipFrames).coerceAtLeast(0L)
        val remaining = (targetFrames - emittedFrames).coerceAtLeast(0L)
        return minOf(available, remaining)
    }
}
