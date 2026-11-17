package com.zjr.hesimusic.loop.audio.source

import com.zjr.hesimusic.loop.analysis.Pcm16Frames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** 循环数据源测试公共工具（合成 PCM，无 Android 依赖）。 */
object LoopingPcmTestSupport {

    const val SAMPLE_RATE: Int = 1000
    const val CHANNELS: Int = 1
    const val BYTES_PER_FRAME: Int = CHANNELS * 2
    const val LOOP_START_US: Long = 0L
    const val LOOP_END_US: Long = 30_000_000L
    const val LOOP_FRAMES: Long = 30_000L
    const val BLOCK_FRAMES: Int = SAMPLE_RATE

    fun format(
        sampleRate: Int = SAMPLE_RATE,
        channels: Int = CHANNELS,
    ): PcmTrackFormat = PcmTrackFormat(sampleRate, channels)

    fun timeline(
        songId: Long = 7L,
        aUs: Long = LOOP_START_US,
        bUs: Long = LOOP_END_US,
        sr: Int = SAMPLE_RATE,
        ch: Int = CHANNELS,
        entryUs: Long = aUs,
    ): LoopingPcmTimeline = LoopingPcmTimeline(
        songId = songId,
        loopStartUs = aUs,
        loopEndUs = bUs,
        format = format(sr, ch),
        entryPositionUs = entryUs,
    )

    fun provider(timeline: LoopingPcmTimeline): SyntheticPcmProvider =
        SyntheticPcmProvider(
            sampleRate = timeline.sampleRate,
            channels = timeline.channels,
            loopStartFrame = timeline.aFrame,
            loopFrames = timeline.loopFrames,
        )

    fun core(
        timeline: LoopingPcmTimeline = timeline(),
        provider: PcmRangeProvider = provider(timeline),
        maxCacheBytes: Long = PcmBlockCache.DEFAULT_MAX_BYTES,
    ): Pair<LoopingPcmSourceCore, PcmRangeProvider> =
        LoopingPcmSourceCore(
            timeline = timeline,
            provider = provider,
            maxCacheBytes = maxCacheBytes,
        ) to provider

    fun expectedSample(relativeFrame: Long): Short = relativeFrame.toShort()

    /** 理论 [A,B) 循环：从相对帧 0 开始的 [frameCount] 帧 PCM16。 */
    fun expectedBytes(frameCount: Int, channels: Int = CHANNELS): ByteArray {
        val bpf = channels * 2
        val data = ByteArray(frameCount * bpf)
        var offset = 0
        for (i in 0 until frameCount) {
            val value = expectedSample((i % LOOP_FRAMES).toLong())
            for (c in 0 until channels) {
                data[offset++] = (value.toInt() and 0xff).toByte()
                data[offset++] = ((value.toInt() shr 8) and 0xff).toByte()
            }
        }
        return data
    }

    /** 从相对帧 [relativeStart] 开始的理论循环 PCM。 */
    fun expectedBytesFrom(
        relativeStart: Long,
        frameCount: Int,
        channels: Int = CHANNELS,
    ): ByteArray {
        val bpf = channels * 2
        val data = ByteArray(frameCount * bpf)
        var offset = 0
        for (i in 0 until frameCount) {
            val value = expectedSample(Math.floorMod(relativeStart + i, LOOP_FRAMES))
            for (c in 0 until channels) {
                data[offset++] = (value.toInt() and 0xff).toByte()
                data[offset++] = ((value.toInt() shr 8) and 0xff).toByte()
            }
        }
        return data
    }

    /** 读取恰好 [frameCount] 帧（最后一块可裁剪）；返回 PCM 字节。 */
    fun readExact(
        core: LoopingPcmSourceCore,
        frameCount: Int,
        maxFramesPerRead: Int = 257,
    ): ByteArray {
        val out = ByteArray(frameCount * core.timeline.bytesPerFrame)
        var copiedFrames = 0
        var guard = 0
        while (copiedFrames < frameCount) {
            val result = core.read(maxFramesPerRead * core.timeline.bytesPerFrame)
            if (result == null) {
                assertTrue("decode made no progress at frame $copiedFrames", core.pumpDecode())
                guard++
                assertTrue("too many decode stalls", guard < 100_000)
                continue
            }
            val take = minOf(result.frames, frameCount - copiedFrames)
            System.arraycopy(
                result.data,
                0,
                out,
                copiedFrames * core.timeline.bytesPerFrame,
                take * core.timeline.bytesPerFrame,
            )
            copiedFrames += take
        }
        return out
    }

    /** 读取过程中记录每个输出块的 PTS；最多读到 [frameCount] 帧。 */
    fun readResults(
        core: LoopingPcmSourceCore,
        frameCount: Int,
        maxFramesPerRead: Int = 257,
    ): List<LoopingPcmSourceCore.ReadResult> {
        val out = ArrayList<LoopingPcmSourceCore.ReadResult>()
        var frames = 0
        var guard = 0
        while (frames < frameCount) {
            val result = core.read(maxFramesPerRead * core.timeline.bytesPerFrame)
            if (result == null) {
                assertTrue("decode made no progress at frame $frames", core.pumpDecode())
                guard++
                assertTrue("too many decode stalls", guard < 100_000)
                continue
            }
            out += result
            frames += result.frames
        }
        return out
    }

    fun pumpUntil(
        core: LoopingPcmSourceCore,
        predicate: () -> Boolean,
        maxPumps: Int = 64,
    ) {
        var i = 0
        while (!predicate()) {
            assertTrue("pumpUntil stalled after $i pumps", i < maxPumps && core.pumpDecode())
            i++
        }
    }

    fun assertPcmEquals(
        expected: ByteArray,
        actual: ByteArray,
        message: String = "PCM mismatch",
    ) {
        assertEquals(message + " size", expected.size, actual.size)
        var i = 0
        while (i < expected.size) {
            if (expected[i] != actual[i]) {
                assertEquals(message + " first differing byte @" + i, expected[i], actual[i])
            }
            i++
        }
    }

    /** 从 PCM16 小端字节里取第 [frameIndex] 帧第 [channel] 声道的样本。 */
    fun sampleAt(data: ByteArray, frameIndex: Int, channel: Int = 0, channels: Int = CHANNELS): Int {
        val offset = (frameIndex * channels + channel) * 2
        return (data[offset].toInt() and 0xff) or ((data[offset + 1].toInt() and 0xff) shl 8)
    }

    fun frameAtUs(us: Long, sr: Int = SAMPLE_RATE): Long =
        Pcm16Frames.frameAtOrAfterUs(us, sr)
}
