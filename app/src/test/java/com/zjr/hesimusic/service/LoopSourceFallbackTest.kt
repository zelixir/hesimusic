package com.zjr.hesimusic.service

import com.zjr.hesimusic.loop.LoopWindow
import com.zjr.hesimusic.loop.audio.source.PcmTrackFormat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SOURCE 构建失败的预检/回退单测（纯 JVM，不构造 MediaSource）。
 *
 * 失败面：格式不支持（probe 抛异常）、文件缺失、窗口非法、环超预算（L×192KB/s > heap/6）。
 * 回退语义：不进入 SOURCE（协调器不替换 item / 会话层不投影），并登记该窗口不可用，
 * 同一窗口只回退一次；换窗口或复位后可以重试。
 */
class LoopSourceFallbackTest {

    /** 40s 窗口：48k/16bit/立体声 = 192000 B/s → 7_680_000 B。 */
    private val window = LoopWindow(songId = 7L, loopStartUs = 10_000_000L, loopEndUs = 50_000_000L)
    private val format = PcmTrackFormat(sampleRate = 48_000, channels = 2)
    private val heapBytes = 402_653_184L // 402MB 堆 → 预算 67_108_864 B(heap/6)

    @After
    fun tearDown() {
        LoopSourceFallback.reset()
    }

    @Test
    fun `supported window uses source`() {
        val decision = LoopSourceFallback.evaluate(
            window = window,
            songId = 7L,
            filePath = "/music/a.mp3",
            probe = { format },
            heapBytes = heapBytes,
        )
        assertTrue(decision is LoopSourceFallback.Decision.UseSource)
        assertEquals(7_680_000L, (decision as LoopSourceFallback.Decision.UseSource).ringBytes)
    }

    @Test
    fun `unsupported format falls back to M3 prime`() {
        val decision = LoopSourceFallback.evaluate(
            window = window,
            songId = 7L,
            filePath = "/music/a.mp3",
            probe = { throw IllegalArgumentException("no audio track in /music/a.mp3") },
            heapBytes = heapBytes,
        )
        assertEquals(
            LoopSourceFallback.Reason.FORMAT_UNSUPPORTED,
            (decision as LoopSourceFallback.Decision.UseM3Prime).reason,
        )
        // 没有 probe（拿不到格式）同样回退。
        val noProbe = LoopSourceFallback.evaluate(window, 7L, "/music/a.mp3", null, heapBytes)
        assertEquals(
            LoopSourceFallback.Reason.FORMAT_UNSUPPORTED,
            (noProbe as LoopSourceFallback.Decision.UseM3Prime).reason,
        )
    }

    @Test
    fun `missing file or song falls back`() {
        fun reason(songId: Long?, path: String?): LoopSourceFallback.Reason =
            (LoopSourceFallback.evaluate(window, songId, path, { format }, heapBytes)
                as LoopSourceFallback.Decision.UseM3Prime).reason

        assertEquals(LoopSourceFallback.Reason.FILE_MISSING, reason(7L, null))
        assertEquals(LoopSourceFallback.Reason.FILE_MISSING, reason(7L, "  "))
        assertEquals(LoopSourceFallback.Reason.FILE_MISSING, reason(0L, "/music/a.mp3"))
        assertEquals(LoopSourceFallback.Reason.FILE_MISSING, reason(null, "/music/a.mp3"))
    }

    @Test
    fun `invalid window falls back`() {
        val nullReason = (LoopSourceFallback.evaluate(null, 7L, "/music/a.mp3", { format }, heapBytes)
            as LoopSourceFallback.Decision.UseM3Prime).reason
        assertEquals(LoopSourceFallback.Reason.WINDOW_INVALID, nullReason)
        val broken = LoopWindow(7L, loopStartUs = 50_000_000L, loopEndUs = 10_000_000L)
        val brokenReason = (LoopSourceFallback.evaluate(broken, 7L, "/music/a.mp3", { format }, heapBytes)
            as LoopSourceFallback.Decision.UseM3Prime).reason
        assertEquals(LoopSourceFallback.Reason.WINDOW_INVALID, brokenReason)
    }

    @Test
    fun `oversized ring falls back to M3 prime`() {
        // 400s 窗口 = 76_800_000 B > 67_108_864 B 预算(heap/6)。
        val huge = LoopWindow(7L, loopStartUs = 0L, loopEndUs = 400_000_000L)
        val decision = LoopSourceFallback.evaluate(huge, 7L, "/music/a.mp3", { format }, heapBytes)
        val fallback = decision as LoopSourceFallback.Decision.UseM3Prime
        assertEquals(LoopSourceFallback.Reason.RING_TOO_LARGE, fallback.reason)
        assertTrue(fallback.detail.contains("budget=" + (heapBytes / LoopSourceFallback.RING_BUDGET_DIVISOR)))
        // 刚好卡在预算内：仍走 SOURCE（预算 = heap/6，与 RingBandSource.canUse 同口径）。
        val budget = heapBytes / LoopSourceFallback.RING_BUDGET_DIVISOR
        val frames = budget / format.bytesPerFrame
        val justFitsUs = frames * 1_000_000L / format.sampleRate
        val fits = LoopWindow(7L, loopStartUs = 0L, loopEndUs = justFitsUs)
        val fitsDecision = LoopSourceFallback.evaluate(fits, 7L, "/music/a.mp3", { format }, heapBytes)
        assertTrue(fitsDecision is LoopSourceFallback.Decision.UseSource)
    }

    @Test
    fun `ring bytes scale with sample rate and channels`() {
        assertEquals(7_680_000L, LoopSourceFallback.ringBytes(window, format))
        assertEquals(3_840_000L, LoopSourceFallback.ringBytes(window, PcmTrackFormat(48_000, 1)))
        assertEquals(7_056_000L, LoopSourceFallback.ringBytes(window, PcmTrackFormat(44_100, 2)))
        assertEquals(0L, LoopSourceFallback.ringBytes(null, format))
        assertEquals(0L, LoopSourceFallback.ringBytes(window, null))
    }

    @Test
    fun `registry remembers one window and retries after the window changes`() {
        assertFalse(LoopSourceFallback.isUnavailable(7L, window))
        LoopSourceFallback.reportUnavailable(
            songId = 7L,
            window = window,
            reason = LoopSourceFallback.Reason.RING_TOO_LARGE,
            detail = "test",
        )
        assertTrue(LoopSourceFallback.isUnavailable(7L, window))
        assertEquals(LoopSourceFallback.Reason.RING_TOO_LARGE, LoopSourceFallback.reasonOf(7L))
        // 换窗口 = 新预算/新环 → 允许重试。
        assertFalse(LoopSourceFallback.isUnavailable(7L, LoopWindow(7L, 20_000_000L, 50_000_000L)))
        // 别的歌不受影响。
        assertFalse(LoopSourceFallback.isUnavailable(9L, window))
        assertNull(LoopSourceFallback.reasonOf(9L))
        // 显式清理后回到可重试状态。
        LoopSourceFallback.clear(7L)
        assertFalse(LoopSourceFallback.isUnavailable(7L, window))
    }

    @Test
    fun `build failure is recorded as build failed and resets with the service`() {
        val reason = LoopSourceFallback.reportBuildFailure(
            songId = 7L,
            window = window,
            throwable = IllegalStateException("decoder init failed"),
        )
        assertEquals(LoopSourceFallback.Reason.BUILD_FAILED, reason)
        assertTrue(LoopSourceFallback.isUnavailable(7L, window))
        assertEquals(1, LoopSourceFallback.failureCount())
        LoopSourceFallback.reset()
        assertFalse(LoopSourceFallback.isUnavailable(7L, window))
        assertEquals(0, LoopSourceFallback.failureCount())
    }

    @Test
    fun `null song or window is ignored`() {
        LoopSourceFallback.reportUnavailable(0L, window, LoopSourceFallback.Reason.FILE_MISSING)
        LoopSourceFallback.reportUnavailable(7L, null, LoopSourceFallback.Reason.FILE_MISSING)
        // 窗口不属于该歌：不能把 A 歌的失败登记到 B 歌上。
        LoopSourceFallback.reportUnavailable(
            7L,
            LoopWindow(9L, 1L, 2L),
            LoopSourceFallback.Reason.FILE_MISSING,
        )
        assertEquals(0, LoopSourceFallback.failureCount())
    }

    @Test
    fun `invalid window is registered as window invalid`() {
        // 00027/P0-3：WINDOW_INVALID 也必须走同一张登记表，否则每次 refresh 反复 evaluate。
        val broken = LoopWindow(7L, 50_000_000L, 10_000_000L)
        LoopSourceFallback.reportUnavailable(
            songId = 7L,
            window = broken,
            reason = LoopSourceFallback.Reason.WINDOW_INVALID,
            detail = "b<=a",
        )

        assertTrue(LoopSourceFallback.isUnavailable(7L, broken))
        assertEquals(LoopSourceFallback.Reason.WINDOW_INVALID, LoopSourceFallback.reasonOf(7L))
        assertEquals(
            LoopSourceFallback.Reason.WINDOW_INVALID,
            LoopSourceFallback.reasonFor(7L, broken),
        )
        // 别的窗口不受影响。
        assertFalse(LoopSourceFallback.isUnavailable(7L, window))
        assertNull(LoopSourceFallback.reasonFor(7L, window))
    }

    @Test
    fun `all five fallback reasons share one registration path`() {
        for (reason in LoopSourceFallback.Reason.values()) {
            LoopSourceFallback.reset()
            LoopSourceFallback.reportUnavailable(7L, window, reason, detail = reason.logName)

            assertTrue("reason=" + reason, LoopSourceFallback.isUnavailable(7L, window))
            assertEquals("reason=" + reason, reason, LoopSourceFallback.reasonFor(7L, window))
            assertEquals("reason=" + reason, reason, LoopSourceFallback.reasonOf(7L))
        }
    }
}
