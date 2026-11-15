package com.zjr.hesimusic.loop.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopProgressTest {

    @Test
    fun `解码阶段映射到 0 至 0_5`() {
        assertEquals(0f, LoopProgress.merge(LoopProgress.Phase.DECODING, 0f, 0f), 0f)
        assertEquals(0.25f, LoopProgress.merge(LoopProgress.Phase.DECODING, 0.5f, 0f), 0f)
        assertEquals(0.5f, LoopProgress.merge(LoopProgress.Phase.DECODING, 1f, 0f), 0f)
    }

    @Test
    fun `分析阶段映射到 0_5 至 1_0`() {
        assertEquals(0.5f, LoopProgress.merge(LoopProgress.Phase.ANALYZING, 0f, 0f), 0f)
        assertEquals(0.75f, LoopProgress.merge(LoopProgress.Phase.ANALYZING, 0.5f, 0f), 0f)
        assertEquals(1f, LoopProgress.merge(LoopProgress.Phase.ANALYZING, 1f, 0f), 0f)
    }

    @Test
    fun `阶段内回退被上一次值 clamp`() {
        assertEquals(0.4f, LoopProgress.merge(LoopProgress.Phase.DECODING, 0.2f, 0.4f), 0f)
        assertEquals(0.8f, LoopProgress.merge(LoopProgress.Phase.ANALYZING, 0.1f, 0.8f), 0f)
    }

    @Test
    fun `阶段切换不回退`() {
        val afterDecode = LoopProgress.merge(LoopProgress.Phase.DECODING, 1f, 0f)
        assertEquals(0.5f, afterDecode, 0f)
        val analyzeStart = LoopProgress.merge(LoopProgress.Phase.ANALYZING, 0f, afterDecode)
        assertEquals(0.5f, analyzeStart, 0f)
        val analyzeMid = LoopProgress.merge(LoopProgress.Phase.ANALYZING, 0.5f, analyzeStart)
        assertEquals(0.75f, analyzeMid, 0f)
    }

    @Test
    fun `越界输入被钳制`() {
        assertEquals(0f, LoopProgress.merge(LoopProgress.Phase.DECODING, -1f, 0f), 0f)
        assertEquals(0.5f, LoopProgress.merge(LoopProgress.Phase.DECODING, 2f, 0f), 0f)
        assertEquals(1f, LoopProgress.merge(LoopProgress.Phase.ANALYZING, 2f, 0f), 0f)
    }

    @Test
    fun `NaN 输入不污染进度`() {
        assertEquals(0.3f, LoopProgress.merge(LoopProgress.Phase.DECODING, Float.NaN, 0.3f), 0f)
        assertEquals(0f, LoopProgress.merge(LoopProgress.Phase.DECODING, Float.NaN, 0f), 0f)
    }

    @Test
    fun `完整序列全局单调且不超过 1`() {
        var last = 0f
        for (i in 0..20) {
            val next = LoopProgress.merge(LoopProgress.Phase.DECODING, i / 20f, last)
            assertTrue("decode 序列不得回退", next >= last)
            last = next
        }
        assertEquals(0.5f, last, 0f)
        for (i in 0..20) {
            val next = LoopProgress.merge(LoopProgress.Phase.ANALYZING, i / 20f, last)
            assertTrue("analyze 序列不得回退", next >= last)
            last = next
        }
        assertEquals(1f, last, 0f)
    }
}
