package com.zjr.hesimusic.core.invariants

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** 输出帧计数按 AudioSink 属主分桶（主播放器 vs 预览播放器互不污染）。 */
class AudioOutputFramesTest {

    @After
    fun tearDown() {
        AudioOutputFrames.reset()
    }

    @Test
    fun countersArePerOwner() {
        val main = Any()
        val preview = Any()
        AudioOutputFrames.counterFor(main).addAndGet(120L)
        AudioOutputFrames.counterFor(preview).addAndGet(7L)
        assertEquals(120L, AudioOutputFrames.totalFrames(main))
        assertEquals(7L, AudioOutputFrames.totalFrames(preview))
    }

    @Test
    fun unknownOwnerIsZero() {
        assertEquals(0L, AudioOutputFrames.totalFrames(Any()))
    }

    @Test
    fun resetClearsAllCounters() {
        val owner = Any()
        AudioOutputFrames.counterFor(owner).addAndGet(5L)
        AudioOutputFrames.reset()
        assertEquals(0L, AudioOutputFrames.totalFrames(owner))
    }
}
