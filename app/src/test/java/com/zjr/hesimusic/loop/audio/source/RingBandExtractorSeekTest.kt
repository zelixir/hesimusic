package com.zjr.hesimusic.loop.audio.source

import androidx.media3.common.DataReader
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 00022/00023：Extractor 的 seek 语义（请求跳填 + seek 后重新给满发射预算）。
 */
class RingBandExtractorSeekTest {

    private val sr = 1_000
    private val channels = 1

    private class FakeTrack : TrackOutput {
        var totalBytes = 0
        val pts = ArrayList<Long>()
        override fun format(format: androidx.media3.common.Format) = Unit

        override fun sampleData(
            input: DataReader,
            length: Int,
            allowEndOfInput: Boolean,
            sampleDataPart: Int,
        ): Int = 0

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            data.skipBytes(length)
            totalBytes += length
        }

        override fun sampleMetadata(
            timeUs: Long,
            flags: Int,
            size: Int,
            offset: Int,
            cryptoData: TrackOutput.CryptoData?,
        ) {
            pts += timeUs
        }
    }

    private class FakeOutput : ExtractorOutput {
        val track = FakeTrack()
        override fun track(id: Int, type: Int): TrackOutput = track
        override fun endTracks() = Unit
        override fun seekMap(seekMap: SeekMap) = Unit
    }

    private fun fakeInput(): ExtractorInput = Proxy.newProxyInstance(
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

    private fun pcm(startFrame: Long, count: Int): ByteArray {
        val out = ByteArray(count * channels * 2)
        val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) {
            val value = (startFrame + i).toShort()
            repeat(channels) { bb.putShort(value) }
        }
        return out
    }

    /**
     * 00029：extractor seek 现在会启动渲染线程（按需填充）。测试要验证的是
     * “read 只等当前 offset 8ms、不等整环”，所以让 decoder 在渲染线程里停住，
     * 避免它立刻返回 0 帧后 renderer 把缺口按静音补齐（那会让 read 立刻拿到数据）。
     * 渲染线程是 daemon，测试结束即退出。
     */
    private fun emptyDecoder(): RingBandStreamDecoder = object : RingBandStreamDecoder {
        override fun stream(
            startUs: Long,
            endUs: Long,
            prerollUs: Long,
            onPcm: (data: ByteArray, size: Int) -> Unit,
        ) {
            try {
                Thread.sleep(2_000L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        override fun close() = Unit
    }

    @Test
    fun `seek resets the wall clock budget so data flows without realtime wait`() {
        val tl = LoopingPcmTestSupport.timeline(aUs = 0L, bUs = 10_000_000L, sr = sr, ch = channels)
        val ring = LoopRing(sr, channels, tl.loopFrames)
        ring.append(pcm(0, tl.loopFrames.toInt()))
        val reader = RingBandReader(ring)
        val renderer = RingBandRenderer(tl, emptyDecoder(), ring)
        var now = 0L
        var sleeps = 0
        val extractor = RingBandExtractor(
            timeline = tl,
            ring = ring,
            reader = reader,
            renderer = renderer,
            clock = { now },
            sleeper = { now += it; sleeps += 1 },
        )
        val out = FakeOutput()
        extractor.init(out)
        val input = fakeInput()

        extractor.read(input, PositionHolder())
        val emittedAfterFirst = reader.totalReadFrames
        assertTrue("首次读取应吃满 5s 预算", emittedAfterFirst >= 5L * sr - 1L)
        assertEquals(0L, out.track.pts.first())

        // 再读一次进入墙钟节流（不给新预算就无法继续发射）。
        extractor.read(input, PositionHolder())
        assertTrue("进入节流后应 sleep 过", sleeps > 0)

        val bytesBeforeSeek = out.track.totalBytes
        val readsBeforeSeek = reader.reads
        extractor.seek(0L, 6_000_000L)
        extractor.read(input, PositionHolder())
        assertTrue("seek 后必须立刻能发射新数据", out.track.totalBytes > bytesBeforeSeek)
        assertTrue(reader.reads > readsBeforeSeek)
        assertTrue("seek 后首个样本 PTS >= seek 目标", out.track.pts.last() >= 6_000_000L)
    }

    @Test
    fun `seek into an unfilled offset waits only for that offset`() {
        val tl = LoopingPcmTestSupport.timeline(aUs = 0L, bUs = 10_000_000L, sr = sr, ch = channels)
        val ring = LoopRing(sr, channels, tl.loopFrames)
        ring.append(pcm(0, 100))
        val reader = RingBandReader(ring)
        val renderer = RingBandRenderer(tl, emptyDecoder(), ring)
        val extractor = RingBandExtractor(tl, ring, reader, renderer)
        val out = FakeOutput()
        extractor.init(out)
        val input = fakeInput()

        extractor.seek(0L, 9_000_000L)
        assertEquals("Extractor 必须把 seek 目标告诉渲染器", 9_000L, renderer.lastJumpOffsetFrames)

        val started = System.nanoTime()
        val result = extractor.read(input, PositionHolder())
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L
        assertEquals(Extractor.RESULT_CONTINUE, result)
        assertTrue("只等当前 offset(8ms)，不能追整个环：was ${elapsedMs}ms", elapsedMs in 1..200)
        assertEquals(1L, reader.underruns)
        assertEquals(0, out.track.totalBytes)

        // 模拟渲染线程跳到 9000 填了 500 帧。
        ring.writeFramesAt(9_000L, pcm(9_000L, 500))
        extractor.read(input, PositionHolder())
        assertEquals(500 * channels * 2, out.track.totalBytes)
        assertEquals(9_000_000L, out.track.pts.first())
    }
}
