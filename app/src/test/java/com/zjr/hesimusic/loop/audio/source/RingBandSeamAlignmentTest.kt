package com.zjr.hesimusic.loop.audio.source

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 00029 A 交界：真机环带首 buffer PTS 在 A 前 161 帧，skip 必须精确为 161；
 * 跳过 161 帧后的首样本就是目标帧（991 = 1152 - 161 帧可写入）。
 */
class RingBandSeamAlignmentTest {

    private val target = 4_090_364L

    @Test
    fun bufferStarting161FramesBeforeTargetSkipsExactly161() {
        val bufferStart = target - 161L
        assertEquals(161L, RingBandSeamAlignment.skipFrames(bufferStart, target))
        assertEquals(
            991L,
            RingBandSeamAlignment.takeFrames(
                bufferFrames = 1_152L,
                skipFrames = 161L,
                targetFrames = 10_000L,
                emittedFrames = 0L,
            ),
        )
        assertEquals(target, bufferStart + 161L)
    }

    @Test
    fun bufferStartingAfterTargetCannotGoBackwards() {
        val bufferStart = target + 205L
        assertEquals(0L, RingBandSeamAlignment.skipFrames(bufferStart, target))
        assertEquals(
            0L,
            RingBandSeamAlignment.takeFrames(
                bufferFrames = 1_152L,
                skipFrames = 0L,
                targetFrames = 0L,
                emittedFrames = 0L,
            ),
        )
    }

    @Test
    fun takeCapsAtRemainingTargetFrames() {
        assertEquals(
            10L,
            RingBandSeamAlignment.takeFrames(
                bufferFrames = 1_152L,
                skipFrames = 0L,
                targetFrames = 1_000L,
                emittedFrames = 990L,
            ),
        )
    }
}
