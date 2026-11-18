package com.zjr.hesimusic.loop.audio.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import com.zjr.hesimusic.loop.analysis.Pcm16Frames
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * T0:环带核心 3 圈逐字节 + 4 个坏 fixture（尺子必须能抓错）。
 * 纯 JVM；L = 2s @48k，3 圈约 5.76MB，运行 <1s。
 */
class LoopRingT0Test {

    private val sampleRate = 48_000
    private val channels = 2
    private val bpf = channels * 2
    private val aFrame = 48_000L
    private val bFrame = 144_000L
    private val loopFrames = bFrame - aFrame

    private fun syntheticSrc(frames: Long): ByteArray {
        val out = ByteArray((frames * bpf).toInt())
        val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        for (f in 0 until frames) {
            // 确定性的有标记波形：左右声道不同，避免全零误判。
            val v = ((f * 37L + 11L) % 30_000L - 15_000L).toInt()
            bb.putShort((v + 1).toShort())
            bb.putShort((v - 1).toShort())
        }
        return out
    }

    private fun expectedThreeLaps(src: ByteArray): ByteArray {
        val loopBytes = ((bFrame - aFrame) * bpf).toInt()
        val from = (aFrame * bpf).toInt()
        val out = ByteArray(loopBytes * 3)
        for (k in 0 until 3) {
            System.arraycopy(src, from, out, k * loopBytes, loopBytes)
        }
        return out
    }

    private fun firstMismatch(expected: ByteArray, actual: ByteArray): Long? {
        val n = minOf(expected.size, actual.size)
        var i = 0
        while (i < n) {
            if (expected[i] != actual[i]) return (i / bpf).toLong()
            i += 1
        }
        if (expected.size != actual.size) return (n / bpf).toLong()
        return null
    }

    private fun assertBadFixture(name: String, actual: ByteArray, expected: ByteArray) {
        val mismatch = firstMismatch(expected, actual)
        assertNotNull("$name 必须被尺子判 FAIL", mismatch)
        println("T0 bad fixture $name -> FAIL at frame $mismatch")
    }

    @Test
    fun `three laps are byte exact with frame PTS and four bad fixtures fail`() {
        val started = System.nanoTime()
        val src = syntheticSrc(6L * sampleRate)
        val loop = src.copyOfRange((aFrame * bpf).toInt(), (bFrame * bpf).toInt())
        val ring = LoopRing(sampleRate, channels, loopFrames)
        assertEquals(loopFrames.toInt(), ring.append(loop))
        assertEquals(loopFrames, ring.renderedFrames)

        val expected = expectedThreeLaps(src)
        val actual = ring.copyFrames(0L, (loopFrames * 3L).toInt())
        assertEquals("总帧数必须为 3L", (loopFrames * 3).toLong() * bpf, actual.size.toLong())
        assertNull("3 圈必须逐字节相同", firstMismatch(expected, actual))

        // 接缝两侧：out[L-1] == src[B-1]，out[L] == src[A]
        val lastLoopFrame = ((bFrame - 1) * bpf).toInt()
        val firstLoopFrame = (aFrame * bpf).toInt()
        val seamOut = (loopFrames * bpf).toInt()
        for (i in 0 until bpf) {
            assertEquals("B-1 seam byte $i", src[lastLoopFrame + i], actual[(loopFrames * bpf).toInt() - bpf + i])
            assertEquals("A seam byte $i", src[firstLoopFrame + i], actual[seamOut + i])
        }

        // PTS：本实现的对外 PTS = 帧号 * 1e6 / sr，换算回帧号必须每帧 +1。
        for (i in 0 until (loopFrames * 3L).toInt()) {
            val ptsFrame = Pcm16Frames.frameAtOrAfterUs(
                ring.presentationTimeUs(i.toLong()),
                sampleRate,
            )
            assertEquals("PTS frame at $i", i.toLong(), ptsFrame)
        }

        // 4 个坏 fixture：少 1 帧 / 多 1 帧 / 接缝重复 1 帧 / 接缝跳过 1 帧。
        assertBadFixture("short-1", actual.copyOf(actual.size - bpf), expected)
        assertBadFixture("long-1", actual + ByteArray(bpf), expected)

        val repeated = actual.copyOf()
        System.arraycopy(
            actual,
            (loopFrames * bpf).toInt() - bpf,
            repeated,
            (loopFrames * bpf).toInt(),
            bpf,
        )
        assertBadFixture("repeat-1-at-seam", repeated, expected)

        val skipped = ByteArray(actual.size)
        System.arraycopy(actual, 0, skipped, 0, (loopFrames * bpf).toInt())
        System.arraycopy(
            actual,
            (loopFrames * bpf).toInt() + bpf,
            skipped,
            (loopFrames * bpf).toInt(),
            actual.size - (loopFrames * bpf).toInt() - bpf,
        )
        assertBadFixture("skip-1-at-seam", skipped, expected)

        val elapsedMs = (System.nanoTime() - started) / 1_000_000L
        println("T0 elapsedMs=$elapsedMs frames=${loopFrames * 3} bytes=${actual.size}")
        assertTrue("T0 must run <1s, was ${elapsedMs}ms", elapsedMs < 1_000L)
    }
}
