package com.zjr.hesimusic.loop.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 预填解码的纯帧号/裁切逻辑（不依赖 Android，纯 JVM 单测）。 */
class Pcm16FramesTest {

    // ---------------------------------------------------------------- 帧数口径

    @Test
    fun `帧数按 ceil 计算`() {
        assertEquals(0L, Pcm16Frames.framesForUsCeil(0L, 44_100))
        assertEquals(44_100L, Pcm16Frames.framesForUsCeil(1_000_000L, 44_100))
        // 区间不足一帧也按 1 帧（ceil）
        assertEquals(1L, Pcm16Frames.framesForUsCeil(1L, 44_100))
        assertEquals(124L, Pcm16Frames.framesForUsCeil(123_456L, 1_000))
        // 超长区间：300s @48k = 14.4M 帧（纯算术，不做上限裁剪）
        assertEquals(14_400_000L, Pcm16Frames.framesForUsCeil(300_000_000L, 48_000))
        assertEquals(0L, Pcm16Frames.framesForUsCeil(-1L, 44_100))
    }

    @Test
    fun `起点帧号按 ceil 对齐`() {
        assertEquals(0L, Pcm16Frames.frameAtOrAfterUs(0L, 44_100))
        // 22us @44.1k = 0.97 帧 → 第 1 帧
        assertEquals(1L, Pcm16Frames.frameAtOrAfterUs(22L, 44_100))
        // 23us @44.1k = 1.01 帧 → 第 2 帧
        assertEquals(2L, Pcm16Frames.frameAtOrAfterUs(23L, 44_100))
        assertEquals(48_000L, Pcm16Frames.frameAtOrAfterUs(1_000_000L, 48_000))
        // 锚点晚于起点（理论上不应发生）时视为 0
        assertEquals(0L, Pcm16Frames.frameAtOrAfterUs(-1_000L, 44_100))
    }

    // ---------------------------------------------------------------- buffer 裁切

    @Test
    fun `buffer 完全在起点之前被整段丢弃`() {
        assertNull(
            Pcm16Frames.clipBuffer(
                bufferStartFrame = 0L,
                bufferFrames = 20L,
                keepStartFrame = 30L,
                keepFrames = 50L,
            )
        )
    }

    @Test
    fun `buffer 跨越起点时从起点开始保留`() {
        val clip = Pcm16Frames.clipBuffer(0L, 100L, 30L, 50L)
        assertEquals(30L, clip!!.first)
        assertEquals(79L, clip.last)
    }

    @Test
    fun `buffer 部分越过终点时裁到终点`() {
        val clip = Pcm16Frames.clipBuffer(60L, 100L, 30L, 50L)
        assertEquals(60L, clip!!.first)
        assertEquals(79L, clip.last)
    }

    @Test
    fun `buffer 完全在终点之后被整段丢弃`() {
        assertNull(Pcm16Frames.clipBuffer(80L, 10L, 30L, 50L))
    }

    @Test
    fun `buffer 刚好覆盖保留区间`() {
        val clip = Pcm16Frames.clipBuffer(30L, 50L, 30L, 50L)
        assertEquals(30L, clip!!.first)
        assertEquals(79L, clip.last)
    }

    // ---------------------------------------------------------------- 与解码器同口径的边界

    @Test
    fun `开始时间落在帧中间时对齐到下一帧`() {
        // 锚点 0us，A=123456us @1kHz → 起点帧 124
        val keepStart = Pcm16Frames.frameAtOrAfterUs(123_456L, 1_000)
        assertEquals(124L, keepStart)
        val clip = Pcm16Frames.clipBuffer(0L, 300L, keepStart, 112L)
        assertEquals(124L, clip!!.first)
        assertEquals(235L, clip.last)
    }

    @Test
    fun `结束时间落在帧中间时按严格帧数多取末尾整帧`() {
        // A=1001ms,B=3005ms @44.1k：严格帧数 ceil(2004ms*44.1)=88377，
        // 而 PTS < B 的帧数是 88376（严格帧数多 1 个末尾整帧）。
        val startUs = 1_001_000L
        val endUs = 3_005_000L
        val sr = 44_100
        val target = Pcm16Frames.framesForUsCeil(endUs - startUs, sr)
        assertEquals(88_377L, target)
        val startFrame = Pcm16Frames.frameAtOrAfterUs(startUs, sr)
        val naturalEnd = Pcm16Frames.frameAtOrAfterUs(endUs, sr)
        assertEquals(44_145L, startFrame)
        assertEquals(88_376L, naturalEnd - startFrame)
        val clip = Pcm16Frames.clipBuffer(0L, 140_000L, startFrame, target)
        assertEquals(44_145L, clip!!.first)
        assertEquals(44_145L + 88_377L - 1L, clip.last)
    }

    @Test
    fun `区间不足一帧按一帧计`() {
        assertEquals(1L, Pcm16Frames.framesForUsCeil(1L, 1_000))
        val clip = Pcm16Frames.clipBuffer(0L, 10L, 0L, 1L)
        assertEquals(0L, clip!!.first)
        assertEquals(0L, clip.last)
    }
}

