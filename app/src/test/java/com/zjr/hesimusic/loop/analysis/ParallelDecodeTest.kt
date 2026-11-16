package com.zjr.hesimusic.loop.analysis

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * 并行解码的纯逻辑测试(不依赖 MediaCodec,可 JVM 跑):
 * 切段边界、接缝互相关对齐(含偏移漂移)、并行/单线程逐样本一致、接缝失败拒绝、
 * 段失败上抛、取消中断所有段、进度单调。
 *
 * 真机 MediaCodec 的逐样本一致对照见 androidTest 的 PcmDecoderParallelEqualityTest。
 */
class ParallelDecodeTest {

    private companion object {
        const val SR = 48000
        const val OVERLAP = 4096L
        const val MARGIN = 64
    }

    /**
     * 理想解码器:第 f 帧的值只由全局帧号决定。
     * [drift] 模拟各段 seek 后的整体偏移(样本):raw 实际从 start - margin + drift 开始。
     */
    private class FakeDecoder(
        private val totalFrames: Long,
        private val corruptSegment: Int? = null,
        private val failSegment: Int? = null,
        private val drift: (Int) -> Int = { 0 },
        private val started: AtomicInteger? = null,
        private val finished: AtomicInteger? = null,
        private val slow: Boolean = false,
    ) : MonoSegmentDecoder {
        fun value(frame: Long): Float = ((frame % 997L).toFloat() / 997.0f) - 0.5f

        override suspend fun decode(
            segment: DecodeSegment,
            onFrames: (Long) -> Unit,
        ): DecodedSegment {
            started?.incrementAndGet()
            if (slow) {
                repeat(400) {
                    delay(5)
                    onFrames(it.toLong())
                }
            }
            if (segment.index == failSegment) throw IllegalStateException("segment failure")
            val front = if (segment.index == 0) 0 else MARGIN
            val main = if (segment.toEos) totalFrames - segment.startFrame else segment.frameCount
            val shift = drift(segment.index)
            // toEos 段从 start - front + shift 一直解到文件尾;普通段解固定帧数。
            val rawLen = if (segment.toEos) {
                front.toLong() + main - shift
            } else {
                front.toLong() + main + OVERLAP
            }
            val raw = FloatArray(rawLen.toInt()) { j ->
                value(segment.startFrame - front + shift + j)
            }
            if (segment.index == corruptSegment) {
                var seed = 987654321u
                for (i in raw.indices) {
                    seed = seed * 1664525u + 1013904223u
                    raw[i] = ((seed shr 8).toFloat() / 16777216f) * 2f - 1f
                }
            }
            onFrames(main)
            finished?.incrementAndGet()
            return DecodedSegment(segment, front, raw)
        }
    }

    @Test
    fun planSplitsAtExactFrameBoundaries() {
        val plan = SegmentPlanner.plan(1_000_000L, SR, 2, 4, OVERLAP)
        assertEquals(4, plan.segmentCount)
        assertEquals(listOf(0L, 250_000L, 500_000L, 750_000L), plan.segments.map { it.startFrame })
        assertEquals(listOf(250_000L, 250_000L, 250_000L, -1L), plan.segments.map { it.frameCount })
        assertTrue(plan.segments.last().toEos)
        // 段起点 -> 媒体时间(floor)口径:4 段切 4s,第 2 段从第 1s 开始
        val plan1s = SegmentPlanner.plan(4L * SR, SR, 1, 4, 0)
        assertEquals(1_000_000L, plan1s.startUs(plan1s.segments[1]))
    }

    @Test
    fun planCapsSegmentCountByCoresDurationAndRange() {
        assertEquals(4, SegmentPlanner.segmentCount(8, 248_000_000L))
        assertEquals(2, SegmentPlanner.segmentCount(2, 248_000_000L))
        assertEquals(1, SegmentPlanner.segmentCount(8, 10_000_000L))
        assertEquals(3, SegmentPlanner.segmentCount(2, 700_000_000L))
        assertEquals(1, SegmentPlanner.segmentCount(8, 0L))
    }

    @Test
    fun stitchIsSampleIdenticalToSerial() = runBlocking {
        val total = 1_000_003L
        val plan = SegmentPlanner.plan(total, SR, 2, 4, OVERLAP)
        val decoder = FakeDecoder(total)
        val result = ParallelMonoDecode.decode(plan, decoder, null)
        assertTrue(result is ParallelDecodeResult.Success)
        val serial = FloatArray(total.toInt()) { decoder.value(it.toLong()) }
        val got = (result as ParallelDecodeResult.Success).mono
        assertEquals(serial.size, got.size)
        for (i in serial.indices) {
            if (serial[i] != got[i]) {
                throw AssertionError("sample $i differs: " + serial[i] + " != " + got[i])
            }
        }
    }

    @Test
    fun stitchToleratesPerSeamDrift() = runBlocking {
        val total = 1_000_003L
        val plan = SegmentPlanner.plan(total, SR, 2, 4, OVERLAP)
        // 模拟不同 seek 点的偏移漂移(MP3/PTS 截断):-37 / +53 / -11 样本
        val decoder = FakeDecoder(total, drift = { index ->
            when (index) {
                1 -> -37
                2 -> 53
                3 -> -11
                else -> 0
            }
        })
        val result = ParallelMonoDecode.decode(plan, decoder, null)
        assertTrue(result is ParallelDecodeResult.Success)
        val serial = FloatArray(total.toInt()) { decoder.value(it.toLong()) }
        val got = (result as ParallelDecodeResult.Success).mono
        assertEquals(serial.size, got.size)
        for (i in serial.indices) {
            if (serial[i] != got[i]) {
                throw AssertionError("drift sample $i differs: " + serial[i] + " != " + got[i])
            }
        }
    }

    @Test
    fun seamMismatchIsRejected() = runBlocking {
        val total = 400_000L
        val plan = SegmentPlanner.plan(total, SR, 2, 2, OVERLAP)
        val result = ParallelMonoDecode.decode(plan, FakeDecoder(total, corruptSegment = 1), null)
        assertTrue(result is ParallelDecodeResult.Rejected)
        assertTrue((result as ParallelDecodeResult.Rejected).reason.contains("接缝"))
    }

    @Test
    fun missingOverlapIsRejected() = runBlocking {
        val plan = SegmentPlanner.plan(400_000L, SR, 2, 2, 0L)
        val result = ParallelMonoDecode.decode(plan, FakeDecoder(400_000L), null)
        assertTrue(result is ParallelDecodeResult.Rejected)
    }

    @Test
    fun singleSegmentIsRejected() = runBlocking {
        val plan = SegmentPlanner.plan(400_000L, SR, 2, 1, OVERLAP)
        val result = ParallelMonoDecode.decode(plan, FakeDecoder(400_000L), null)
        assertTrue(result is ParallelDecodeResult.Rejected)
    }

    @Test
    fun segmentFailurePropagatesToCaller() = runBlocking {
        val plan = SegmentPlanner.plan(400_000L, SR, 2, 2, OVERLAP)
        var caught = false
        try {
            ParallelMonoDecode.decode(plan, FakeDecoder(400_000L, failSegment = 1), null)
        } catch (e: IllegalStateException) {
            caught = true
        }
        assertTrue(caught)
    }

    @Test
    fun cancellationInterruptsAllSegments() = runBlocking {
        val plan = SegmentPlanner.plan(4_000_000L, SR, 2, 4, OVERLAP)
        val started = AtomicInteger(0)
        val finished = AtomicInteger(0)
        val decoder = FakeDecoder(
            totalFrames = 4_000_000L,
            started = started,
            finished = finished,
            slow = true,
        )
        val job = launch { ParallelMonoDecode.decode(plan, decoder, null) }
        delay(40)
        assertEquals(4, started.get())
        val t0 = System.currentTimeMillis()
        job.cancelAndJoin()
        val elapsed = System.currentTimeMillis() - t0
        assertTrue("cancel took " + elapsed + "ms", elapsed < 1000)
        assertEquals(0, finished.get())
    }

    @Test
    fun progressIsMonotonicAndEndsAtOne() = runBlocking {
        val total = 1_000_000L
        val plan = SegmentPlanner.plan(total, SR, 2, 4, OVERLAP)
        val seen = ArrayList<Float>()
        val result = ParallelMonoDecode.decode(plan, FakeDecoder(total), seen::add)
        assertTrue(result is ParallelDecodeResult.Success)
        assertTrue(seen.isNotEmpty())
        for (i in 1 until seen.size) {
            assertTrue("progress must be monotonic: " + seen, seen[i] >= seen[i - 1])
        }
        assertEquals(1.0f, seen.last(), 0.0f)
        assertTrue(seen.all { it in 0.0f..1.0f })
    }

    // ---------------- SegmentAligner ----------------

    private fun wave(n: Int, phase: Double = 0.0): FloatArray =
        FloatArray(n) { i ->
            (0.6 * kotlin.math.sin((i + phase) * 0.0131) +
                0.3 * kotlin.math.sin((i + phase) * 0.0373) +
                0.1 * kotlin.math.sin((i + phase) * 0.11)).toFloat()
        }

    @Test
    fun alignerFindsKnownPositiveShift() {
        val base = wave(6000)
        // b 比 a 早 37 帧开始:b[i] = base[i - 37]
        val b = FloatArray(4000) { base[it] }
        val a = FloatArray(1500) { base[it + 37] }
        val m = SegmentAligner.bestShift(a, b, radius = 256, window = 1024)
        assertTrue(m != null)
        assertEquals(37, m!!.shift)
        assertTrue("corr=" + m.correlation, m.correlation > 0.999)
    }

    @Test
    fun alignerFindsKnownNegativeShiftAndReportsCorr() {
        val base = wave(6000)
        // b 比 a 晚 529 帧开始(MP3 解码器 delay 量级)
        val b = FloatArray(6000 - 529)
        for (i in b.indices) b[i] = base[i + 529]
        val a = FloatArray(1500) { base[it] }
        val m = SegmentAligner.bestShift(a, b, radius = 1024, window = 1024)
        assertTrue(m != null)
        assertEquals(-529, m!!.shift)
        assertTrue(m.correlation > 0.999)
    }

    @Test
    fun alignerFindsShiftAroundMargin() {
        // 拼接器语义:在 margin 附近搜索(右段 raw 从 start - margin 开始)
        val margin = 2048
        val base = wave(14000)
        val shift = 77 // 右段实际晚 77 帧
        val a = FloatArray(1024) { base[it + margin - shift] }
        val b = FloatArray(9000) { base[it] }
        val m = SegmentAligner.bestShift(a, b, radius = 512, window = 1024, center = margin)
        assertTrue(m != null)
        assertEquals(margin - shift, m!!.shift)
        assertTrue(m.correlation > 0.999)
    }

    @Test
    fun alignerRejectsUnrelatedOrConstantSignals() {
        val a = wave(2000)
        var seed = 12345u
        val noise = FloatArray(4000) {
            seed = seed * 1664525u + 1013904223u
            ((seed shr 8).toFloat() / 16777216f) * 2f - 1f
        }
        val m = SegmentAligner.bestShift(a, noise, radius = 64, window = 512)
        assertTrue("corr=" + (m?.correlation ?: 0.0), m == null || m.correlation < SegmentAligner.MIN_CORRELATION)
        val zerosA = FloatArray(512)
        val zerosB = FloatArray(1024)
        val zeroMatch = SegmentAligner.bestShift(zerosA, zerosB)
        assertTrue(zeroMatch == null || zeroMatch.correlation == 0.0)
        assertEquals(0.0, SegmentAligner.correlation(zerosA, 0, zerosB, 0, 128), 0.0)
    }
}
