package com.zjr.hesimusic.loop.audio.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch

/** seek 矩阵：前进/后退、A±ε/B−ε、连续拖动、缓冲未就绪、失败跳过、预算。 */
class LoopingPcmSourceCoreSeekTest {

    private fun newCore(
        prefetchBlocks: Int = 2,
        maxCacheBytes: Long = PcmBlockCache.DEFAULT_MAX_BYTES,
    ): Triple<LoopingPcmSourceCore, SyntheticPcmProvider, LoopingPcmTimeline> {
        val tl = LoopingPcmTestSupport.timeline()
        val provider = LoopingPcmTestSupport.provider(tl)
        val core = LoopingPcmSourceCore(
            timeline = tl,
            provider = provider,
            maxCacheBytes = maxCacheBytes,
            prefetchBlocks = prefetchBlocks,
        )
        return Triple(core, provider, tl)
    }

    private fun readFirst(core: LoopingPcmSourceCore, tl: LoopingPcmTimeline): LoopingPcmSourceCore.ReadResult {
        var result = core.read(4_000 * tl.bytesPerFrame)
        var guard = 0
        while (result == null) {
            assertTrue("decode made no progress", guard < 64 && core.pumpDecode())
            guard++
            result = core.read(4_000 * tl.bytesPerFrame)
        }
        return result
    }

    private fun expectedFirstSample(tl: LoopingPcmTimeline, timelineUs: Long): Int {
        val frame = tl.frameAtTimeline(timelineUs)
        return LoopingPcmTestSupport.expectedSample(frame - tl.aFrame).toInt()
    }

    @Test
    fun `entry in the middle starts at entry point with PTS zero`() {
        val tl = LoopingPcmTestSupport.timeline(entryUs = 10_000_000L)
        val provider = LoopingPcmTestSupport.provider(tl)
        val core = LoopingPcmSourceCore(tl, provider)
        val result = readFirst(core, tl)
        assertEquals(0L, result.ptsUs)
        assertEquals(10_000, LoopingPcmTestSupport.sampleAt(result.data, 0))
    }

    @Test
    fun `forward seek 10s to 60s maps to media A and PTS 60s`() {
        val (core, _, tl) = newCore()
        assertEquals(10_000_000L, core.seekToTimelineUs(10_000_000L))
        readFirst(core, tl)

        assertEquals(60_000_000L, core.seekToTimelineUs(60_000_000L))
        val result = readFirst(core, tl)
        assertEquals(60_000_000L, result.ptsUs)
        assertEquals(0, LoopingPcmTestSupport.sampleAt(result.data, 0))
        assertEquals(0L, tl.mediaUs(60_000_000L))
    }

    @Test
    fun `PCM after seek is contiguous with the theoretical loop`() {
        val (core, _, tl) = newCore()
        core.seekToTimelineUs(10_000_000L)
        val actual = LoopingPcmTestSupport.readExact(core, 2_000, maxFramesPerRead = 257)
        LoopingPcmTestSupport.assertPcmEquals(
            LoopingPcmTestSupport.expectedBytesFrom(10_000L, 2_000),
            actual,
            "PCM after seek must continue the loop without gap/duplicate",
        )
    }

    @Test
    fun `backward seek 60s to 10s starts at media 10s`() {
        val (core, _, tl) = newCore()
        core.seekToTimelineUs(60_000_000L)
        readFirst(core, tl)

        assertEquals(10_000_000L, core.seekToTimelineUs(10_000_000L))
        val result = readFirst(core, tl)
        assertEquals(10_000_000L, result.ptsUs)
        assertEquals(10_000, LoopingPcmTestSupport.sampleAt(result.data, 0))
    }

    @Test
    fun `seek to A plus one millisecond starts at frame one`() {
        val (core, _, tl) = newCore()
        assertEquals(1_000L, core.seekToTimelineUs(1_000L))
        val result = readFirst(core, tl)
        assertEquals(1_000L, result.ptsUs)
        assertEquals(1, LoopingPcmTestSupport.sampleAt(result.data, 0))
        assertEquals(2, LoopingPcmTestSupport.sampleAt(result.data, 1))
    }

    @Test
    fun `seek to B minus one millisecond wraps to A without PTS reset`() {
        val (core, _, tl) = newCore()
        assertEquals(29_999_000L, core.seekToTimelineUs(29_999_000L))
        val first = readFirst(core, tl)
        assertEquals(29_999_000L, first.ptsUs)
        assertEquals(29_999, LoopingPcmTestSupport.sampleAt(first.data, 0))

        val second = readFirst(core, tl)
        assertEquals("PTS must keep increasing across B", 30_000_000L, second.ptsUs)
        assertEquals(0, LoopingPcmTestSupport.sampleAt(second.data, 0))
        assertEquals(1, LoopingPcmTestSupport.sampleAt(second.data, 1))
    }

    @Test
    fun `seeking to the same position twice decodes zero extra blocks`() {
        val (core, provider, tl) = newCore()
        core.seekToTimelineUs(10_000_000L)
        val first = readFirst(core, tl)
        assertEquals(10_000_000L, first.ptsUs)
        val callsAfterFirst = provider.decodeCount()

        core.seekToTimelineUs(10_000_000L)
        val second = core.read(4_000 * tl.bytesPerFrame)
        assertNotNull("target block must be a cache hit", second)
        assertEquals(10_000_000L, second!!.ptsUs)
        assertEquals("second seek must not decode", callsAfterFirst, provider.decodeCount())
    }

    @Test
    fun `ten rapid different seeks decode at most one block per target`() {
        val (core, provider, tl) = newCore()
        val targets = listOf(
            5_000_000L,
            15_000_000L,
            25_000_000L,
            500_000L,
            12_000_000L,
            3_000_000L,
            29_000_000L,
            7_000_000L,
            22_000_000L,
            11_000_000L,
        )
        var last = 0L
        for (target in targets) {
            last = core.seekToTimelineUs(target)
            val result = readFirst(core, tl)
            assertEquals(target, result.ptsUs)
            assertEquals(expectedFirstSample(tl, target), LoopingPcmTestSupport.sampleAt(result.data, 0))
        }
        assertEquals(11_000_000L, last)
        assertTrue("decode calls=" + provider.decodeCount(), provider.decodeCount() <= targets.size)
        // 最后目标再 seek 一次：已缓存，零解码。
        val before = provider.decodeCount()
        core.seekToTimelineUs(last)
        assertNotNull(core.read(4_000 * tl.bytesPerFrame))
        assertEquals(before, provider.decodeCount())
    }

    @Test
    fun `rapid repeated seeks to one block decode only once`() {
        val (core, provider, tl) = newCore()
        core.seekToTimelineUs(10_000_000L)
        readFirst(core, tl)
        val baseline = provider.decodeCount()
        repeat(10) {
            core.seekToTimelineUs(10_000_000L)
            assertNotNull(core.read(4_000 * tl.bytesPerFrame))
        }
        assertEquals("repeated seeks must be cache hits", baseline, provider.decodeCount())
    }

    @Test
    fun `seek before first decode starts at the latest target`() {
        val (core, _, tl) = newCore()
        core.seekToTimelineUs(20_000_000L)
        assertNull(core.read(4_000 * tl.bytesPerFrame))
        assertTrue(core.pumpDecode())
        val result = core.read(4_000 * tl.bytesPerFrame)
        assertNotNull(result)
        assertEquals(20_000_000L, result!!.ptsUs)
        assertEquals(20_000, LoopingPcmTestSupport.sampleAt(result.data, 0))
    }

    @Test
    fun `seek while a decode is in flight does not block and ignores stale target`() {
        val (core, provider, tl) = newCore()
        val gate = CountDownLatch(1)
        provider.gate = gate
        val decoder = Thread { core.pumpDecode() }
        decoder.start()
        provider.awaitDecodeStarted()

        // 旧目标 block 0 还在解码；seek 立即返回，不等待。
        assertEquals(20_000_000L, core.seekToTimelineUs(20_000_000L))
        assertNull(core.read(4_000 * tl.bytesPerFrame))

        gate.countDown()
        decoder.join()
        var result: LoopingPcmSourceCore.ReadResult? = null
        var guard = 0
        while (result == null) {
            assertTrue("decode made no progress", guard < 64 && core.pumpDecode())
            guard++
            result = core.read(4_000 * tl.bytesPerFrame)
        }
        assertEquals(20_000_000L, result!!.ptsUs)
        assertEquals(20_000, LoopingPcmTestSupport.sampleAt(result.data, 0))
    }

    @Test
    fun `decode failure skips one block and continues monotonically`() {
        val (core, provider, tl) = newCore()
        provider.failWhen = { startUs, endUs ->
            startUs <= 4_000_000L && endUs >= 4_000_000L
        }

        core.seekToTimelineUs(4_000_000L)
        assertTrue(core.pumpDecode()) // block 4 fails
        assertNull(core.read(4_000 * tl.bytesPerFrame))

        var result: LoopingPcmSourceCore.ReadResult? = null
        var guard = 0
        while (result == null) {
            assertTrue("decode made no progress", guard < 64 && core.pumpDecode())
            guard++
            result = core.read(4_000 * tl.bytesPerFrame)
        }
        assertEquals(1L, core.skippedBlocks())
        assertEquals(5_000_000L, result!!.ptsUs)
        assertEquals(5_000, LoopingPcmTestSupport.sampleAt(result.data, 0))
    }

    @Test
    fun `cache budget of three blocks keeps the playhead neighborhood`() {
        val blockBytes = 1_000L * 2L
        val (core, _, tl) = newCore(maxCacheBytes = 3L * blockBytes)
        var frames = 0
        while (frames < 10_000) {
            val result = core.read(4_000 * tl.bytesPerFrame)
            if (result == null) {
                assertTrue(core.pumpDecode())
                continue
            }
            frames += result.frames
            assertTrue(
                "active=" + core.cache.activeBlockCount(),
                core.cache.activeBlockCount() <= 3,
            )
        }
        var guard = 0
        while (!core.isCurrentBlockReady()) {
            assertTrue(guard < 64 && core.pumpDecode())
            guard++
        }
        assertTrue(core.cache.activeBlockCount() <= 3)
        assertTrue(core.cache.readyIndices().contains(core.currentBlockIndex()))
    }

    @Test
    fun `PTS strictly increases within each seek epoch`() {
        val (core, _, tl) = newCore()
        core.seekToTimelineUs(10_000_000L)
        val results = LoopingPcmTestSupport.readResults(core, 3_000, maxFramesPerRead = 250)
        var expected = 10_000_000L
        for (result in results) {
            assertTrue("PTS must not jump backwards", result.ptsUs >= expected)
            expected = result.ptsUs + result.frames * 1_000L
        }
        assertTrue(core.currentPtsUs() > 10_000_000L)
    }
}
