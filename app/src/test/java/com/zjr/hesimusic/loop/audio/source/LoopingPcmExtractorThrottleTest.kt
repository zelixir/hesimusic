package com.zjr.hesimusic.loop.audio.source

import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * issue 00022/00026：分块（块缓存）路径的**发射预算**。
 *
 * 现场：结果页长时间开着 238 秒的长窗口（Shiny Up），环带因预算回退到分块实现后，
 * loader 把 SampleQueue 一路推进，最终 OOM @ ExoPlayer:Loader:ProgressiveMediaPeriod（402MB 堆满）。
 * 环带路径（RingBandExtractor）早就有“最多领先播放头 5 秒”的墙钟节流，分块路径没有 —— 这里补上。
 *
 * 判据（与环带同口径）：发射量 <= 基准 + 5 秒 + 墙钟活跃时间；消费方不读时**不再产出**。
 */
class LoopingPcmExtractorThrottleTest {

    private class FakeOutput : ExtractorOutput {
        val track = FakeTrack()
        var seekMap: SeekMap? = null
        override fun track(id: Int, type: Int): TrackOutput = track
        override fun endTracks() = Unit
        override fun seekMap(seekMap: SeekMap) {
            this.seekMap = seekMap
        }
    }

    private class FakeTrack : TrackOutput {
        var totalBytes = 0L
        var samples = 0
        override fun format(format: Format) = Unit

        override fun sampleData(
            input: DataReader,
            length: Int,
            allowEndOfInput: Boolean,
            sampleDataPart: Int,
        ): Int = 0

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            data.skipBytes(length)
            totalBytes += length
            samples += 1
        }

        override fun sampleMetadata(
            timeUs: Long,
            flags: Int,
            size: Int,
            offset: Int,
            cryptoData: TrackOutput.CryptoData?,
        ) = Unit
    }

    private companion object {
        fun fakeInput(): ExtractorInput = Proxy.newProxyInstance(
            ExtractorInput::class.java.classLoader,
            arrayOf(ExtractorInput::class.java),
        ) { _, method, _ ->
            when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                else -> null
            }
        } as ExtractorInput
    }

    /** 44.1kHz/单声道/3 秒环：贴近真机采样率，让 50ms 级的预算台阶大于最小样本帧数。 */
    private fun harnessTimeline(): LoopingPcmTimeline = LoopingPcmTestSupport.timeline(
        aUs = 0L,
        bUs = 3_000_000L,
        sr = 44_100,
        ch = 1,
    )

    /** 固定时钟 + 不睡眠的 sleeper：让“墙钟活跃时间”完全由测试控制。 */
    private class Harness(val timeline: LoopingPcmTimeline) {
        var nowMs: Long = 1_000L
        var sleptMs: Long = 0L
        var sleepCalls: Int = 0
        val core = LoopingPcmSourceCore(
            timeline = timeline,
            provider = LoopingPcmTestSupport.provider(timeline),
            prefetchBlocks = 2,
        )
        val out = FakeOutput()
        val extractor = LoopingPcmExtractor(
            core = core,
            timeline = timeline,
            clock = { nowMs },
            sleeper = { ms ->
                sleepCalls += 1
                sleptMs += ms
            },
        ).also { it.init(out) }

        val input = fakeInput()
        val holder = PositionHolder()

        fun read(times: Int) {
            repeat(times) { extractor.read(input, holder) }
        }
    }

    @Test
    fun consumerThatDoesNotAdvanceGetsBoundedEmission() {
        val h = Harness(harnessTimeline())
        val sr = h.timeline.sampleRate
        // 时钟完全不走（= renderer 一点都没消费），狂读 200 次。
        h.read(200)
        val emittedFrames = h.core.totalReadFrames()
        val maxAhead = 5L * sr
        assertTrue(
            "emission must be bounded by 5s budget, got " + emittedFrames + " frames (" +
                (emittedFrames / sr) + "s)",
            emittedFrames <= maxAhead + 4L * sr,
        )
        // 已经被节流：读不到新数据时返回 CONTINUE 并短暂等待，而不是继续产出。
        assertTrue("throttle must have slept at least once", h.sleepCalls > 0)
        assertEquals(
            "every emitted frame must have reached the track output",
            h.out.track.totalBytes,
            h.core.totalReadFrames() * h.timeline.bytesPerFrame,
        )
        assertTrue("extractor must report throttling", h.extractor.isThrottling())
        assertTrue(
            "ahead of budget must stay inside maxAhead, ahead=" + h.extractor.aheadOfBudgetSeconds(),
            h.extractor.aheadOfBudgetSeconds() >= 0.0,
        )
    }

    @Test
    fun realTimeConsumptionUnlocksMoreEmission() {
        val h = Harness(harnessTimeline())
        val sr = h.timeline.sampleRate
        h.read(200)
        val before = h.core.totalReadFrames()
        // 之后按真实播放速度推进时钟 10 秒（消费方在读），应当又能产出约 10 秒。
        repeat(100) {
            h.nowMs += 100L
            h.read(1)
        }
        val after = h.core.totalReadFrames()
        val grewSeconds = (after - before).toDouble() / sr
        assertTrue(
            "10s of wall-clock playback must unlock roughly 10s of emission, grew=" + grewSeconds,
            grewSeconds >= 8.0 && grewSeconds <= 12.0,
        )
    }

    @Test
    fun seekRenewsTheBudget() {
        val h = Harness(harnessTimeline())
        val sr = h.timeline.sampleRate
        h.read(200)
        val before = h.core.totalReadFrames()
        h.extractor.seek(0L, 10_000_000L)
        h.read(20)
        val after = h.core.totalReadFrames()
        assertTrue(
            "seek must renew the 5s budget (before=" + before + " after=" + after + ")",
            (after - before) >= 4L * sr,
        )
    }

    @Test
    fun playingConsumerKeepsEmissionBounded() {
        // 回归保护：真正在播（时钟跟着走、消费方按实时读）时，发射量应稳定在
        // "5 秒 + 已播时间"附近，既不断流也不会无限领先。
        val h = Harness(harnessTimeline())
        val sr = h.timeline.sampleRate
        repeat(100) {
            h.nowMs += 100L // 消费方按实时推进（10 秒播放）
            h.read(1)
        }
        val frames = h.core.totalReadFrames()
        val seconds = frames.toDouble() / sr
        assertTrue("must keep emitting while playing, emitted=" + seconds + "s", seconds >= 2.0)
        assertTrue(
            "emission must stay within maxAhead + elapsed, emitted=" + seconds + "s",
            seconds <= 5.0 + 10.0 + 1.0,
        )
    }
}