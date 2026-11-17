package com.zjr.hesimusic.loop.audio.source

import com.zjr.hesimusic.loop.analysis.Pcm16Frames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 循环时间轴/块网格算术（纯 JVM）。 */
class LoopingPcmTimelineTest {

    @Test
    fun `block start frames align to A plus k seconds`() {
        val tl = LoopingPcmTestSupport.timeline(
            aUs = 1_001_000L,
            bUs = 9_001_000L,
            sr = 44_100,
        )
        assertEquals(Pcm16Frames.frameAtOrAfterUs(1_001_000L, 44_100), tl.aFrame)
        assertEquals(Pcm16Frames.frameAtOrAfterUs(9_001_000L, 44_100), tl.bFrame)
        assertEquals(8 * 44_100L, tl.loopFrames)
        assertEquals(8, tl.blockCount)
        for (k in 0 until tl.blockCount) {
            assertEquals(tl.aFrame + k.toLong() * 44_100L, tl.blockStartFrame(k))
            assertEquals(44_100L, tl.blockFrames(k))
            val startFrame = tl.blockStartFrame(k)
            assertEquals(
                "block $k decode start must map back to its first frame",
                startFrame,
                Pcm16Frames.frameAtOrAfterUs(tl.blockDecodeStartUs(k), 44_100),
            )
            assertEquals(
                "block $k decode end must map back to its end frame",
                startFrame + 44_100L,
                Pcm16Frames.frameAtOrAfterUs(tl.blockDecodeEndUs(k), 44_100),
            )
        }
    }

    @Test
    fun `last block may be shorter than one second`() {
        val tl = LoopingPcmTestSupport.timeline(
            aUs = 1_001_000L,
            bUs = 9_001_500L,
            sr = 44_100,
        )
        val last = tl.blockCount - 1
        assertEquals(9, tl.blockCount)
        assertTrue(tl.blockFrames(last) in 1L until 44_100L)
        assertEquals(tl.bFrame, tl.blockStartFrame(last) + tl.blockFrames(last))
        assertEquals(tl.bFrame, Pcm16Frames.frameAtOrAfterUs(tl.blockDecodeEndUs(last), 44_100))
    }

    @Test
    fun `media time wraps at B and keeps increasing at timeline level`() {
        val tl = LoopingPcmTestSupport.timeline(entryUs = 0L)
        assertEquals(0L, tl.mediaUs(0L))
        assertEquals(10_000_000L, tl.mediaUs(10_000_000L))
        assertEquals(29_999_000L, tl.mediaUs(29_999_000L))
        assertEquals(0L, tl.mediaUs(30_000_000L))
        assertEquals(15_000_000L, tl.mediaUs(45_000_000L))
        assertEquals(0L, tl.mediaUs(60_000_000L))
    }

    @Test
    fun `entry in the middle maps timeline zero to the entry point`() {
        val tl = LoopingPcmTestSupport.timeline(entryUs = 10_000_000L)
        assertEquals(10_000_000L, tl.mediaUs(0L))
        assertEquals(20_000_000L, tl.mediaUs(10_000_000L))
        assertEquals(0L, tl.mediaUs(20_000_000L))
        assertEquals(5_000_000L, tl.mediaUs(25_000_000L))
    }

    @Test
    fun `frame at timeline wraps from B to A`() {
        val tl = LoopingPcmTestSupport.timeline()
        assertEquals(0L, tl.frameAtTimeline(0L))
        assertEquals(29_999L, tl.frameAtTimeline(29_999_000L))
        assertEquals(0L, tl.frameAtTimeline(30_000_000L))
        assertEquals(29_999L, tl.frameAtTimeline(59_999_000L))
        assertEquals(0L, tl.frameAtTimeline(60_000_000L))
    }

    @Test
    fun `cache key changes with window song rate and channels`() {
        val base = LoopingPcmTestSupport.timeline()
        val same = LoopingPcmTestSupport.timeline()
        assertEquals(base.cacheKey(), same.cacheKey())
        assertNotEquals(base.cacheKey(), LoopingPcmTestSupport.timeline(songId = 8L).cacheKey())
        assertNotEquals(
            base.cacheKey(),
            LoopingPcmTestSupport.timeline(aUs = 1_000L, bUs = 30_000_000L).cacheKey(),
        )
        assertNotEquals(
            base.cacheKey(),
            LoopingPcmTestSupport.timeline(sr = 2_000, bUs = 30_000_000L).cacheKey(),
        )
        assertNotEquals(
            base.cacheKey(),
            LoopingPcmTestSupport.timeline(ch = 2).cacheKey(),
        )
        // 同一帧但 A 不同的窗口也不能共用块缓存。
        assertNotEquals(
            LoopingPcmTestSupport.timeline(aUs = 100L, bUs = 30_000_000L).cacheKey(),
            LoopingPcmTestSupport.timeline(aUs = 200L, bUs = 30_000_000L).cacheKey(),
        )
    }

    @Test
    fun `with entry position keeps block cache key`() {
        val base = LoopingPcmTestSupport.timeline()
        assertEquals(base.cacheKey(), base.withEntryPosition(10_000_000L).cacheKey())
    }

    @Test
    fun `block index normalizes wrapped frame`() {
        val tl = LoopingPcmTestSupport.timeline()
        assertEquals(0, tl.blockIndexForFrame(0L))
        assertEquals(1, tl.blockIndexForFrame(1_000L))
        assertEquals(29, tl.blockIndexForFrame(29_999L))
        assertEquals(0, tl.blockIndexForFrame(30_000L))
        assertEquals(0, tl.blockIndexForFrame(60_000L))
    }
}
