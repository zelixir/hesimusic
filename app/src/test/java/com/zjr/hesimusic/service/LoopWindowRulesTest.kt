package com.zjr.hesimusic.service

import com.zjr.hesimusic.loop.LoopWindow
import com.zjr.hesimusic.ui.loop.PreviewPlayer
import com.zjr.hesimusic.ui.loop.state.LoopPageReducer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #104 零长度窗口：协议层/播放层的唯一窗口口径（纯 JVM）。
 *
 * 用例矩阵直接对齐场景 #104 的要求：A==B / B<A / B=A+1 sample / B-A=499ms /
 * B-A=500ms（通过）/ 越界 / 未知时长 / 负 A / 非法采样率。
 */
class LoopWindowRulesTest {

    private val songId = 39944L
    private val sampleRate = 44_100
    private val durationMs = 90_828L

    private fun samples(ms: Long): Long = ms * sampleRate / 1_000L

    private fun verdict(
        aSamples: Long,
        bSamples: Long,
        durationMs: Long = this.durationMs,
    ): LoopWindowRules.Verdict = LoopWindowRules.validateSamples(
        songId = songId,
        aSamples = aSamples,
        bSamples = bSamples,
        sampleRate = sampleRate,
        durationMs = durationMs,
    )

    private fun codeOf(v: LoopWindowRules.Verdict): LoopWindowRules.Code? =
        (v as? LoopWindowRules.Verdict.Invalid)?.code

    @Test
    fun `a equals b is rejected as a-not-before-b`() {
        val a = samples(17_496L)
        val result = verdict(a, a)
        assertEquals(LoopWindowRules.Code.A_NOT_BEFORE_B, codeOf(result))
        assertTrue("必须是 4xx", (result as LoopWindowRules.Verdict.Invalid).status in 400..499)
        assertEquals("window-a-not-before-b", result.code.wire)
    }

    @Test
    fun `b before a is rejected as a-not-before-b`() {
        val a = samples(30_000L)
        val b = samples(20_000L)
        assertEquals(LoopWindowRules.Code.A_NOT_BEFORE_B, codeOf(verdict(a, b)))
    }

    @Test
    fun `one sample window is rejected as too short`() {
        val a = samples(17_496L)
        val result = verdict(a, a + 1L)
        assertEquals(LoopWindowRules.Code.TOO_SHORT, codeOf(result))
        assertTrue((result as LoopWindowRules.Verdict.Invalid).message.contains("0.02"))
        assertEquals("window-too-short", result.code.wire)
    }

    @Test
    fun `499ms window is rejected and exactly 500ms passes`() {
        val a = samples(10_000L)
        val under = verdict(a, a + samples(499L))
        assertEquals(LoopWindowRules.Code.TOO_SHORT, codeOf(under))

        // 500ms 必须通过：协议层下限与 UI 的 MIN_LOOP_LENGTH_MS 同口径。
        val exact = verdict(a, a + samples(500L))
        assertTrue("500ms 应通过, got " + exact, exact is LoopWindowRules.Verdict.Valid)
        val window = (exact as LoopWindowRules.Verdict.Valid).window
        assertEquals(songId, window.songId)
        assertEquals(500_000L, window.lengthUs)
        assertTrue(window.isValid())
    }

    @Test
    fun `half open window of 500ms at a non aligned a is accepted`() {
        // 场景 #105 的真实 A/B（A 非 500ms 对齐）：B-A 恰好 500ms。
        val a = 2_002_757L
        val b = 2_024_807L
        assertTrue(verdict(a, b) is LoopWindowRules.Verdict.Valid)
    }

    @Test
    fun `window past the song duration is rejected`() {
        val a = samples(90_000L)
        val b = samples(91_000L) // > 90828ms + 100ms 容差
        assertEquals(LoopWindowRules.Code.PAST_DURATION, codeOf(verdict(a, b)))
        assertEquals("window-past-duration", LoopWindowRules.Code.PAST_DURATION.wire)
    }

    @Test
    fun `window ending exactly at the duration passes`() {
        // 整曲窗口是合法用例（库里确实有 A=0/B=duration 的已确认行）。
        val a = samples(1_000L)
        val b = samples(durationMs)
        assertTrue(verdict(a, b) is LoopWindowRules.Verdict.Valid)
    }

    @Test
    fun `unknown duration skips the upper bound only`() {
        val a = samples(1_000L)
        assertTrue(verdict(a, samples(5_000L), durationMs = 0L) is LoopWindowRules.Verdict.Valid)
        assertEquals(
            LoopWindowRules.Code.TOO_SHORT,
            codeOf(verdict(a, a + 1L, durationMs = 0L)),
        )
    }

    @Test
    fun `negative a and bad sample rate are rejected with their own codes`() {
        assertEquals(
            LoopWindowRules.Code.A_NEGATIVE,
            codeOf(verdict(-1L, samples(1_000L))),
        )
        val badRate = LoopWindowRules.validateSamples(1L, 0L, 44_100L, 0, 10_000L)
        assertEquals(LoopWindowRules.Code.INVALID_SAMPLE_RATE, codeOf(badRate))
    }

    @Test
    fun `invalid song or missing window is rejected`() {
        val bad = LoopWindowRules.validateSamples(0L, 0L, samples(1_000L), sampleRate, durationMs)
        assertEquals(LoopWindowRules.Code.INVALID_SONG, codeOf(bad))
        assertEquals(LoopWindowRules.Code.INVALID_SONG, LoopWindowRules.violationOf(null))
        assertFalse(LoopWindowRules.usable(null))
    }

    @Test
    fun `stored row window is validated without a sample rate`() {
        // 历史行只有 ms 口径的 LoopWindow：1 sample ≈ 0.02ms 的坏行必须不可用。
        val tiny = LoopWindow(songId, 17_496_000L, 17_496_023L)
        assertEquals(LoopWindowRules.Code.TOO_SHORT, LoopWindowRules.violationOf(tiny))
        assertFalse(LoopWindowRules.usable(tiny))
        assertTrue(LoopWindowRules.describe(tiny).contains("window-too-short"))

        val ok = LoopWindow(songId, 17_496_000L, 17_996_000L)
        assertNull(LoopWindowRules.violationOf(ok))
        assertTrue(LoopWindowRules.usable(ok))

        // 91.5s > 90828ms + 100ms 容差
        val pastDuration = LoopWindow(songId, 91_000_000L, 91_500_000L)
        assertEquals(
            LoopWindowRules.Code.PAST_DURATION,
            LoopWindowRules.violationOf(pastDuration, durationMs * 1_000L),
        )
    }

    @Test
    fun `validate us mirrors the sample rules`() {
        val shortUs = LoopWindowRules.validateUs(songId, 1_000_000L, 1_499_999L)
        assertNotNull(shortUs)
        assertEquals(LoopWindowRules.Code.TOO_SHORT, codeOf(shortUs))
        assertTrue(
            LoopWindowRules.validateUs(songId, 1_000_000L, 1_500_000L) is
                LoopWindowRules.Verdict.Valid,
        )
    }

    @Test
    fun `min loop length matches the ui constants`() {
        assertEquals(LoopPageReducer.MIN_LOOP_LENGTH_MS, LoopWindowRules.MIN_LOOP_LENGTH_MS)
        assertEquals(PreviewPlayer.MIN_LOOP_LENGTH_MS, LoopWindowRules.MIN_LOOP_LENGTH_MS)
    }
}
