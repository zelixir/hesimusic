package com.zjr.hesimusic.loop.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/**
 * 00017 接缝拼接缓冲原型测试：合成 10s 锯齿+正弦，窗口 A=2s B=8s Δ=0.5s。
 * 只测纯逻辑，不接 AudioSink。
 */
class LoopSeamSpliceBufferTest {

    private val sampleRate = 1000
    private val bpf = 2
    private val aFrame = 2000L
    private val bFrame = 8000L
    private val delta = 500L
    private val sourceFrames = 10_000L

    private fun sourceSample(frame: Long): Short {
        val saw = ((frame % 500L) - 250L) * 32L
        val sine = (sin(2.0 * PI * frame / 97.0) * 900.0).toLong()
        return (saw + sine).coerceIn(-32768L, 32767L).toShort()
    }

    private fun pcm(startFrame: Long, frames: Long): ByteArray {
        val bb = ByteBuffer.allocate((frames * bpf).toInt()).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) {
            bb.putShort(sourceSample(startFrame + i))
        }
        bb.flip()
        val out = ByteArray(bb.remaining())
        bb.get(out)
        return out
    }

    private class Harness(
        val sampleRate: Int,
        val bpf: Int,
        val a: Long,
        val b: Long,
        val delta: Long,
        val pcm: (Long, Long) -> ByteArray,
    ) {
        val decoded = mutableListOf<LongRange>()
        val buffer = LoopSeamSpliceBuffer(
            sampleRate = sampleRate,
            bytesPerFrame = bpf,
            windowStartFrame = a,
            windowEndFrame = b,
            deltaFrames = delta,
            decodeRange = { s, e ->
                decoded.add(s until e)
                pcm(s, e - s)
            },
        )
    }

    private fun harness(): Harness = Harness(sampleRate, bpf, aFrame, bFrame, delta, ::pcm).also {
        assertTrue(it.buffer.beginCapture())
        assertTrue("X 应预解码", it.buffer.prepareSeamPrefix())
        assertTrue("Y 应预解码", it.buffer.prepareSeamSuffix())
    }

    private fun cycle(
        h: Harness,
        first: Boolean,
        out: java.io.ByteArrayOutputStream,
    ) {
        if (!first) {
            val y = h.buffer.seamSuffixBytes()
            out.write(y)
            h.buffer.noteOutput(aFrame, delta)
        }
        val normalStart = if (first) aFrame else aFrame + delta
        val normalFrames = bFrame - delta - normalStart
        out.write(pcm(normalStart, normalFrames))
        h.buffer.noteOutput(normalStart, normalFrames)

        assertTrue(
            "切换点在线校验必须通过",
            h.buffer.verifyLivePrefix(pcm(bFrame - delta, delta), delta.toInt()),
        )
        assertTrue(h.buffer.beginSeam())
        out.write(h.buffer.seamPrefixBytes())
        h.buffer.noteOutput(bFrame - delta, delta)
        h.buffer.finishSeam()
        h.buffer.markSourceReady()
    }

    @Test
    fun `三圈输出逐样本等于理论无缝循环`() {
        val h = harness()
        val loops = 3
        val expected = pcm(aFrame, bFrame - aFrame)
        repeat(loops) { index ->
            val out = java.io.ByteArrayOutputStream()
            cycle(h, first = index == 0, out = out)
            assertArrayEquals("第 " + (index + 1) + " 圈必须逐样本一致", expected, out.toByteArray())
        }
        assertEquals(loops.toLong(), h.buffer.seamSwitchCount)
        assertEquals("正常路径只占 2Δ", 2L * delta * bpf, h.buffer.seamBufferBytes)
        assertEquals("切换点样本缺口必须为 0", 0L, h.buffer.maxSwitchGapFrames)
        assertTrue(h.decoded.all { it.last - it.first + 1L == delta })
    }

    @Test
    fun `校验失败本轮不切 下一轮仍可切`() {
        val h = harness()
        // 第一轮正常播到 B-Δ
        val normalFrames = bFrame - delta - aFrame
        h.buffer.noteOutput(aFrame, normalFrames)

        val bad = pcm(bFrame - delta, delta)
        bad[0] = (bad[0].toInt() xor 0xFF).toByte()
        assertFalse(h.buffer.verifyLivePrefix(bad, delta.toInt()))
        assertFalse("校验失败不得切换", h.buffer.beginSeam())
        assertEquals(1L, h.buffer.seamVerifyFailCount)

        // 继续源播到 B（本轮不切）
        h.buffer.noteOutput(bFrame - delta, delta)
        // 下一轮到 B-Δ，用正确 live 校验可切
        assertTrue(h.buffer.verifyLivePrefix(pcm(bFrame - delta, delta), delta.toInt()))
        assertTrue(h.buffer.beginSeam())
        assertEquals(1L, h.buffer.seamVerifyFailCount)
    }

    @Test
    fun `切回点源未就绪时顺延一个 Delta 且连续`() {
        val h = harness()
        // 第一圈正常 + X
        val cycle1 = java.io.ByteArrayOutputStream()
        cycle(h, first = true, out = cycle1)
        assertArrayEquals(pcm(aFrame, bFrame - aFrame), cycle1.toByteArray())

        // S 的 Y，然后源未 ready -> defer 下一个 Δ
        val y = h.buffer.seamSuffixBytes()
        assertArrayEquals(pcm(aFrame, delta), y)
        h.buffer.noteOutput(aFrame, delta)
        assertEquals(aFrame + delta, h.buffer.finishSeam())
        val deferred = h.buffer.deferSeam()
        assertNotNull(deferred)
        assertArrayEquals("顺延段必须是 A+Δ 起的源音频", pcm(aFrame + delta, delta), deferred)
        assertEquals(1L, h.buffer.seamDeferCount)
        assertEquals(aFrame + delta * 2, h.buffer.resumeFrame())
        assertEquals("defer 复用缓冲区，内存仍为 2Δ", 2L * delta * bpf, h.buffer.seamBufferBytes)
    }

    @Test
    fun `十圈以上无漂移 每圈切换点缺帧为 0`() {
        val h = harness()
        val expected = pcm(aFrame, bFrame - aFrame)
        repeat(12) { index ->
            val out = java.io.ByteArrayOutputStream()
            cycle(h, first = index == 0, out = out)
            assertArrayEquals("第 " + (index + 1) + " 圈漂移", expected, out.toByteArray())
        }
        assertEquals(12L, h.buffer.seamSwitchCount)
        assertEquals(0L, h.buffer.maxSwitchGapFrames)
    }

    @Test
    fun `取消立即释放接缝缓冲`() {
        val h = harness()
        assertEquals(2L * delta * bpf, h.buffer.seamBufferBytes)
        h.buffer.cancel()
        assertEquals(0L, h.buffer.seamBufferBytes)
        assertEquals(LoopSeamSpliceBuffer.State.IDLE, h.buffer.state)
        assertFalse(h.buffer.beginCapture())
    }

    @Test
    fun `默认开关为 OFF 且原型只解码 Delta 片`() {
        assertEquals(SeamBufferMode.OFF, LoopAudioController().seamBufferMode)
        val h = harness()
        val out = java.io.ByteArrayOutputStream()
        cycle(h, first = true, out = out)
        val y = h.buffer.seamSuffixBytes()
        h.buffer.noteOutput(aFrame, delta)
        h.buffer.finishSeam()
        h.buffer.deferSeam()
        assertTrue(h.decoded.isNotEmpty())
        assertTrue("不得整环解码", h.decoded.all { it.last - it.first + 1L == delta })
        assertTrue(y.isNotEmpty())
    }
}
