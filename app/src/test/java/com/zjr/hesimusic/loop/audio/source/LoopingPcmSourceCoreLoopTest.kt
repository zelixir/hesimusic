package com.zjr.hesimusic.loop.audio.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 循环读、按需解码、块预取、首块延迟结构（纯 JVM）。 */
class LoopingPcmSourceCoreLoopTest {

    @Test
    fun `three loops are sample exact and PTS is strictly monotonic`() {
        val tl = LoopingPcmTestSupport.timeline()
        val provider = LoopingPcmTestSupport.provider(tl)
        val core = LoopingPcmSourceCore(tl, provider)

        val results = LoopingPcmTestSupport.readResults(core, 90_000, maxFramesPerRead = 257)
        var frames = 0L
        var lastPts = Long.MIN_VALUE
        val actual = ByteArray(90_000 * tl.bytesPerFrame)
        var copied = 0
        for (result in results) {
            assertTrue("frames must be > 0", result.frames > 0)
            assertEquals("PTS must be derived from cumulative frames", frames * 1_000L, result.ptsUs)
            assertTrue("PTS must strictly increase", result.ptsUs > lastPts)
            lastPts = result.ptsUs
            val take = minOf(result.frames, 90_000 - copied)
            if (take > 0) {
                System.arraycopy(
                    result.data,
                    0,
                    actual,
                    copied * tl.bytesPerFrame,
                    take * tl.bytesPerFrame,
                )
                copied += take
            }
            frames += result.frames
        }
        assertTrue("read past 3 loops", frames >= 90_000L)
        LoopingPcmTestSupport.assertPcmEquals(
            LoopingPcmTestSupport.expectedBytes(90_000),
            actual,
            "three loops must equal [A,B) repeated",
        )
        // 30 个 1s 块各解一次；第 2/3 圈全部命中缓存，不重复解码。
        assertEquals(30L, core.decodeCallCount())
        assertTrue(core.cache.activeBlockCount() <= 30)
    }

    @Test
    fun `PTS continues across B and never resets`() {
        val tl = LoopingPcmTestSupport.timeline()
        val provider = LoopingPcmTestSupport.provider(tl)
        val core = LoopingPcmSourceCore(tl, provider)

        val results = LoopingPcmTestSupport.readResults(core, 35_000, maxFramesPerRead = 250)
        var frames = 0L
        var crossedBoundary = false
        for (result in results) {
            assertTrue(result.ptsUs > frames * 1_000L - 1L)
            val start = frames
            val end = frames + result.frames
            if (start <= 30_000L && end > 30_000L) crossedBoundary = true
            frames = end
        }
        assertTrue("test must cross the B boundary", crossedBoundary)
        assertEquals(5_000L, core.currentFrame()) // 35000 frames wrapped into the 30000-frame loop
        assertEquals(35_000_000L, core.currentPtsUs())
    }

    @Test
    fun `on demand decoding only decodes the played window`() {
        val tl = LoopingPcmTestSupport.timeline()
        val provider = LoopingPcmTestSupport.provider(tl)
        val core = LoopingPcmSourceCore(tl, provider, prefetchBlocks = 0)

        LoopingPcmTestSupport.readExact(core, 3_000, maxFramesPerRead = 257)
        assertEquals(3L, core.decodeCallCount())
        assertTrue("must not prefill the whole loop", core.decodeCallCount() < tl.blockCount)
        assertEquals(setOf(0, 1, 2), core.cache.readyIndices())
    }

    @Test
    fun `prefetch is bounded to current plus two blocks`() {
        val tl = LoopingPcmTestSupport.timeline()
        val provider = LoopingPcmTestSupport.provider(tl)
        val core = LoopingPcmSourceCore(tl, provider, prefetchBlocks = 2)

        assertTrue(core.pumpDecode()) // block 0
        assertTrue(core.pumpDecode()) // block 1
        assertTrue(core.pumpDecode()) // block 2
        assertFalse("prefetch window must stop at k+2", core.pumpDecode())
        assertEquals(setOf(0, 1, 2), core.cache.readyIndices())
    }

    @Test
    fun `seam callback fires once per loop across long playback`() {
        val tl = LoopingPcmTestSupport.timeline(
            aUs = 5_000_000L,
            bUs = 20_000_000L,
            sr = 48_000,
        )
        val provider = LoopingPcmTestSupport.provider(tl)
        val core = LoopingPcmSourceCore(tl, provider)
        var seams = 0
        core.onSeamOutputFrames = { seams += 1 }

        var frames = 0L
        while (frames < 40L * 48_000L) {
            val r = core.read(7_680)
            if (r == null) {
                core.pumpDecode()
                continue
            }
            frames += r.frames
        }
        assertTrue("40s with L=15s should have 2..3 seams, got " + seams, seams in 2..3)
    }

    @Test
    fun `B to A seam reports cumulative output frame at loop boundary`() {
        val tl = LoopingPcmTestSupport.timeline()
        val provider = LoopingPcmTestSupport.provider(tl)
        val core = LoopingPcmSourceCore(tl, provider)
        var seamFrames = -1L
        core.onSeamOutputFrames = { seamFrames = it }

        LoopingPcmTestSupport.readExact(core, (tl.loopFrames + 1L).toInt(), maxFramesPerRead = 257)
        assertEquals(tl.loopFrames, seamFrames)
    }

    @Test
    fun `buffered end covers continuous ready blocks and next load ends at prefetch edge`() {
        val tl = LoopingPcmTestSupport.timeline()
        val provider = LoopingPcmTestSupport.provider(tl)
        val core = LoopingPcmSourceCore(tl, provider, prefetchBlocks = 2)

        assertTrue(core.pumpDecode()) // 0
        assertTrue(core.pumpDecode()) // 1
        assertTrue(core.pumpDecode()) // 2
        assertEquals(3_000_000L, core.bufferedEndUs())
        assertEquals(null, core.nextLoadPositionUs())

        // 连续读 1s 后，buffered 必须继续领先当前位置至少 1s（LoadControl 用）。
        val read = core.read(tl.sampleRate * tl.bytesPerFrame)
        assertNotNull(read)
        assertTrue(
            "buffered-current must stay >=1s: buffered=" + core.bufferedEndUs() +
                " current=" + core.currentPtsUs(),
            core.bufferedEndUs() - core.currentPtsUs() >= 1_000_000L,
        )
    }

    @Test
    fun `first block decode after seek covers at most one second`() {
        val tl = LoopingPcmTestSupport.timeline()
        val provider = LoopingPcmTestSupport.provider(tl)
        val core = LoopingPcmSourceCore(tl, provider)

        assertEquals(10_000_000L, core.seekToTimelineUs(10_000_000L))
        assertTrue(core.pumpDecode())
        val calls = provider.callsSnapshot()
        assertEquals(1, calls.size)
        val (startUs, endUs) = calls[0]
        assertTrue("decode range must be at most 1s: " + startUs + ".." + endUs, endUs - startUs <= 1_000_000L)
        assertTrue("seek target block must be ready", core.isCurrentBlockReady())

        val result = core.read(1_000 * tl.bytesPerFrame)
        assertNotNull("first block must be readable", result)
        assertEquals(10_000_000L, result!!.ptsUs)
        assertEquals(10_000, LoopingPcmTestSupport.sampleAt(result.data, 0))
    }

    @Test
    fun `first block latency structure stays within one block`() {
        val tl = LoopingPcmTestSupport.timeline()
        val provider = LoopingPcmTestSupport.provider(tl)
        provider.decodeDelayMs = 20L
        val core = LoopingPcmSourceCore(tl, provider)

        core.seekToTimelineUs(10_000_000L)
        val started = System.nanoTime()
        assertTrue(core.pumpDecode())
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L
        assertTrue("first block took " + elapsedMs + "ms", elapsedMs < 500L)
        assertEquals(1, provider.decodeCount())
        assertEquals(10_000_000L, core.currentPtsUs())
    }

    @Test
    fun `buffered end grows with contiguous ready blocks`() {
        val tl = LoopingPcmTestSupport.timeline()
        val provider = LoopingPcmTestSupport.provider(tl)
        val core = LoopingPcmSourceCore(tl, provider, prefetchBlocks = 2)

        assertEquals(0L, core.bufferedEndUs())
        val ready = core.pumpDecode()
        assertTrue(ready)
        assertEquals(1_000_000L, core.bufferedEndUs())
        assertTrue(core.pumpDecode()) // block 1
        assertEquals(2_000_000L, core.bufferedEndUs())
        assertTrue(core.pumpDecode()) // block 2
        assertEquals(3_000_000L, core.bufferedEndUs())
    }

    @Test
    fun `concurrent pumpDecode calls are serialized`() {
        val tl = LoopingPcmTestSupport.timeline()
        val provider = LoopingPcmTestSupport.provider(tl)
        provider.decodeDelayMs = 5L
        val core = LoopingPcmSourceCore(tl, provider)

        val threads = (1..8).map {
            Thread { core.pumpDecode() }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(1, provider.maxConcurrent)
    }

    @Test
    fun `skip to pts advances without reading`() {
        val tl = LoopingPcmTestSupport.timeline()
        val provider = LoopingPcmTestSupport.provider(tl)
        val core = LoopingPcmSourceCore(tl, provider)

        assertTrue(core.pumpDecode())
        val skipped = core.skipToPts(500_000L)
        assertEquals(500, skipped)
        assertEquals(500_000L, core.currentPtsUs())
        assertEquals(500L, core.currentFrame())
    }
}
