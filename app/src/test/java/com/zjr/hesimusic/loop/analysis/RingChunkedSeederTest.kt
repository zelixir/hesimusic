package com.zjr.hesimusic.loop.analysis

import com.zjr.hesimusic.loop.LoopWindow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分片预填编排（[RingChunkedSeeder]）纯逻辑单测：
 * 分片顺序/startFrame 连续性、帧对齐边界、尾帧裁剪、取消、失败停止。
 */
class RingChunkedSeederTest {

    private val sr = 44_100

    private fun window(aUs: Long, bUs: Long, songId: Long = 1L): LoopWindow =
        LoopWindow(songId = songId, loopStartUs = aUs, loopEndUs = bUs)

    /** 与真实解码器同口径：给定区间返回 ceil((end−start)*sr/1e6) 帧。 */
    private fun segmentFor(startUs: Long, endUs: Long, sampleRate: Int, channels: Int = 1): Pcm16Segment {
        val frames = Pcm16Frames.framesForUsCeil(endUs - startUs, sampleRate)
        return Pcm16Segment(
            data = ByteArray((frames * channels * 2).toInt()),
            sampleRate = sampleRate,
            channels = channels,
            frames = frames,
        )
    }

    // ---------------------------------------------------------------- 顺序/连续性

    @Test
    fun `按时间顺序分片且 startFrame 严格连续`() = runBlocking {
        val seeder = RingChunkedSeeder(chunkMs = 2_000)
        val w = window(1_000_000L, 9_000_000L) // 8s @44.1k = 352800 帧 = 4 片
        val decodeCalls = mutableListOf<Pair<Long, Long>>()
        val seeds = mutableListOf<Pair<Long, Long>>()

        val result = seeder.run(
            window = w,
            decode = { s, e ->
                decodeCalls += s to e
                segmentFor(s, e, sr)
            },
            seed = { startFrame, segment ->
                seeds += startFrame to segment.frames
                true
            },
        )

        assertTrue(result.complete)
        assertEquals(4, result.chunks)
        assertEquals(352_800L, result.appendedFrames)
        // 第一片 0..N、第二片 N..2N……
        assertEquals(
            listOf(
                0L to 88_200L,
                88_200L to 88_200L,
                176_400L to 88_200L,
                264_600L to 88_200L,
            ),
            seeds,
        )
        // 解码区间按时间顺序推进（帧对齐后仍是整秒边界）。
        assertEquals(
            listOf(
                1_000_000L to 3_000_000L,
                3_000_000L to 5_000_000L,
                5_000_000L to 7_000_000L,
                7_000_000L to 9_000_000L,
            ),
            decodeCalls,
        )
    }

    @Test
    fun `片长可配置`() = runBlocking {
        val seeder = RingChunkedSeeder(chunkMs = 500)
        val w = window(0L, 1_500_000L)
        val sr1k = 1_000
        val seeds = mutableListOf<Long>()
        val result = seeder.run(
            window = w,
            decode = { s, e -> segmentFor(s, e, sr1k) },
            seed = { startFrame, _ -> seeds += startFrame; true },
        )
        assertEquals(3, result.chunks)
        assertEquals(listOf(0L, 500L, 1_000L), seeds)
        assertEquals(1_500L, result.appendedFrames)
        assertTrue(result.complete)
    }

    @Test
    fun `窗口不足一片时一次覆盖并裁剪尾帧`() = runBlocking {
        val seeder = RingChunkedSeeder(chunkMs = 2_000)
        val w = window(1_001_000L, 3_005_000L) // 严格帧数 88377，环帧数 88376
        val seeds = mutableListOf<Pair<Long, Long>>()
        val result = seeder.run(
            window = w,
            decode = { s, e -> segmentFor(s, e, sr) },
            seed = { startFrame, segment -> seeds += startFrame to segment.frames; true },
        )
        // 第二片只取 176 帧（环长度），多余整帧被 takeFrames 裁掉。
        assertEquals(listOf(0L to 88_200L, 88_200L to 176L), seeds)
        assertEquals(88_376L, result.appendedFrames)
        assertTrue(result.complete)
    }

    // ---------------------------------------------------------------- 取消

    @Test
    fun `中途取消后不再解码也不再追加`() = runBlocking {
        val seeder = RingChunkedSeeder(chunkMs = 2_000)
        val w = window(1_000_000L, 9_000_000L)
        val gate = CompletableDeferred<Unit>()
        var decodeCalls = 0
        var seeds = 0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val job = scope.launch {
            seeder.run(
                window = w,
                decode = { s, e ->
                    decodeCalls += 1
                    if (decodeCalls == 2) gate.await() // 第二片卡住，等待外部取消
                    segmentFor(s, e, sr)
                },
                seed = { _, _ -> seeds += 1; true },
            )
        }

        // Unconfined：launch 内已跑完第一片并卡在第二片解码。
        assertEquals(1, seeds)
        job.cancel()
        job.join()

        assertEquals(1, seeds)
        assertEquals(2, decodeCalls)
        assertTrue(job.isCancelled)
    }

    // ---------------------------------------------------------------- 失败停止

    @Test
    fun `单片解码失败后停止且保留已灌入前缀`() = runBlocking {
        val seeder = RingChunkedSeeder(chunkMs = 2_000)
        val w = window(1_000_000L, 9_000_000L)
        var decodeCalls = 0
        val seeds = mutableListOf<Long>()
        val result = seeder.run(
            window = w,
            decode = { s, e ->
                decodeCalls += 1
                if (decodeCalls == 2) throw IllegalStateException("boom")
                segmentFor(s, e, sr)
            },
            seed = { startFrame, _ -> seeds += startFrame; true },
        )
        assertEquals(1, result.chunks)
        assertEquals(88_200L, result.appendedFrames)
        assertFalse(result.complete)
        assertNotNull(result.stopReason)
        assertTrue(result.stopReason!!.startsWith("decode:"))
        assertEquals(listOf(0L), seeds)
    }

    @Test
    fun `追加被拒后停止且不越过分片边界`() = runBlocking {
        val seeder = RingChunkedSeeder(chunkMs = 2_000)
        val w = window(1_000_000L, 9_000_000L)
        var seeds = 0
        val result = seeder.run(
            window = w,
            decode = { s, e -> segmentFor(s, e, sr) },
            seed = { _, _ -> seeds += 1; seeds == 1 },
        )
        assertEquals(1, result.chunks)
        assertEquals(88_200L, result.appendedFrames)
        assertFalse(result.complete)
        assertTrue(result.stopReason!!.contains("seed rejected"))
    }

    @Test
    fun `窗口非法时不解码`() = runBlocking {
        val seeder = RingChunkedSeeder()
        var decoded = 0
        val result = seeder.run(
            window = window(2_000_000L, 1_000_000L), // 负长度
            decode = { s, e -> decoded += 1; segmentFor(s, e, sr) },
            seed = { _, _ -> true },
        )
        assertEquals(0, decoded)
        assertFalse(result.complete)
        assertEquals("invalid window", result.stopReason)
    }

    // ---------------------------------------------------------------- 已有前缀（播放捕获）

    @Test
    fun `已有前缀时从 coveredFrames 之后继续追加`() = runBlocking {
        val seeder = RingChunkedSeeder(chunkMs = 20)
        val w = LoopWindow(1L, 100_000L, 200_000L) // 100 帧 @1kHz
        val sr1k = 1_000
        val seeds = mutableListOf<Pair<Long, Long>>()
        val decodeCalls = mutableListOf<Pair<Long, Long>>()

        // 播放捕获已覆盖 [0,20)：首片整体作废，从 20 帧处继续。
        val result = seeder.run(
            window = w,
            startFrame = 20L,
            decode = { s, e -> decodeCalls += s to e; segmentFor(s, e, sr1k) },
            seed = { startFrame, segment -> seeds += startFrame to segment.frames; true },
        )

        assertTrue(result.complete)
        assertEquals(4, result.chunks)
        assertEquals(100L, result.appendedFrames)
        assertEquals(listOf(20L, 40L, 60L, 80L), seeds.map { it.first })
        assertEquals(listOf(20L, 20L, 20L, 20L), seeds.map { it.second })
        // 首片仍从 A 解码（用于发现采样率），之后直接从第 20 帧对应时间继续。
        assertEquals(100_000L to 120_000L, decodeCalls.first())
        assertEquals(120_000L to 140_000L, decodeCalls[1])
    }

    @Test
    fun `已有前缀落在首片中间时丢掉已覆盖的头部帧`() = runBlocking {
        val seeder = RingChunkedSeeder(chunkMs = 20)
        val w = LoopWindow(1L, 100_000L, 200_000L)
        val sr1k = 1_000
        val seeds = mutableListOf<Pair<Long, Long>>()

        val result = seeder.run(
            window = w,
            startFrame = 10L,
            decode = { s, e -> segmentFor(s, e, sr1k) },
            seed = { startFrame, segment -> seeds += startFrame to segment.frames; true },
        )

        assertTrue(result.complete)
        // 首片只追加 [10,20) 共 10 帧，之后整片追加。
        assertEquals(10L to 10L, seeds.first())
        assertEquals(20L, seeds[1].first)
        assertEquals(100L, result.appendedFrames)
        assertEquals(5, result.chunks)
    }
}

