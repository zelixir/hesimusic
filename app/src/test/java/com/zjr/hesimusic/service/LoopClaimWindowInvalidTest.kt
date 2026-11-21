package com.zjr.hesimusic.service

import com.zjr.hesimusic.loop.LoopWindow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #104 历史坏窗口进入播放：必须被判 `window-invalid` 且 claim=false。
 *
 * 覆盖两条路径：
 * - [LoopClaimResolver]（生产唯一解析器）：非法窗口不下发、不声称、reason 可见；
 * - [LoopClaim] 直接构造（UI/debug 的兜底）：claimActive 也按同一口径拒绝。
 */
class LoopClaimWindowInvalidTest {

    private val songId = 7L
    private val validWindow = LoopWindow(songId, 10_000_000L, 10_500_000L)
    private val oneMsWindow = LoopWindow(songId, 10_000_000L, 10_001_000L)
    private val oneSampleWindow = LoopWindow(songId, 17_496_000L, 17_496_023L)

    @After
    fun tearDown() {
        LoopClaimStore.resetForTest()
        LoopSourceFallback.reset()
        LoopSourceRuntime.reset()
    }

    @Test
    fun `resolver drops a sub minimum installed window and reports window invalid`() {
        for (bad in listOf(oneMsWindow, oneSampleWindow)) {
            val claim = LoopClaimResolver.resolve(
                songId = songId,
                runtimeWindow = bad,
                audioWindow = null,
                installedWindow = bad,
                isReplaying = false,
                sourceRoute = true,
                sourceUnavailable = false,
            )
            assertNull("坏窗口不得下发 (window=" + bad + ")", claim.window)
            assertFalse(claim.claimActive)
            assertFalse("必须给出原因", claim.claimActive)
            assertEquals(
                LoopSourceFallback.Reason.WINDOW_INVALID,
                claim.fallbackReason,
            )
            val fields = claim.debugFields()
            assertFalse("claimed 必须是 false（否则会误报 loop-claim-without-source）", fields.claimed)
            assertFalse(fields.claimActive)
            assertFalse(fields.sourceActive)
            assertEquals("window-invalid", fields.claimReason)
            // 位置/时长完全透传（不折返）。
            assertEquals(12_345L, claim.displayPositionMs(12_345L))
        }
    }

    @Test
    fun `exactly 500ms still claims`() {
        val claim = LoopClaimResolver.resolve(
            songId = songId,
            runtimeWindow = validWindow,
            audioWindow = null,
            installedWindow = validWindow,
            isReplaying = false,
            sourceRoute = true,
            sourceUnavailable = false,
        )
        assertTrue(claim.claimActive)
        assertEquals(validWindow, claim.effectiveWindow)
        assertNull(claim.fallbackReason)
    }

    @Test
    fun `directly constructed claim refuses a sub minimum window`() {
        for (bad in listOf(oneMsWindow, oneSampleWindow)) {
            val claim = LoopClaim(songId = songId, window = bad, sourceActive = true)
            assertFalse("坏窗口即使 sourceActive=true 也不生效", claim.claimActive)
            assertNull(claim.effectiveWindow)
            assertEquals(42_000L, claim.displayPositionMs(42_000L))
        }
        assertTrue(LoopClaim(songId, validWindow, sourceActive = true).claimActive)
    }

    @Test
    fun `store serves a degraded claim for a sub minimum window`() {
        LoopClaimStore.install { LoopClaim(songId, oneSampleWindow, sourceActive = true) }
        val claim = LoopClaimStore.claimFor(songId)
        assertFalse(claim.claimActive)
        assertFalse(claim.debugFields().claimed)
    }

    @Test
    fun `fallback evaluate rejects short and past duration windows as window invalid`() {
        val shortDecision = LoopSourceFallback.evaluate(
            window = oneSampleWindow,
            songId = songId,
            filePath = "/music/a.mp3",
            probe = { com.zjr.hesimusic.loop.audio.source.PcmTrackFormat(44_100, 2) },
        )
        assertTrue(shortDecision is LoopSourceFallback.Decision.UseM3Prime)
        assertEquals(
            LoopSourceFallback.Reason.WINDOW_INVALID,
            (shortDecision as LoopSourceFallback.Decision.UseM3Prime).reason,
        )
        assertTrue(shortDecision.detail.contains("window-too-short"))

        val pastEnd = LoopWindow(songId, 20_000_000L, 30_000_000L)
        val pastDecision = LoopSourceFallback.evaluate(
            window = pastEnd,
            songId = songId,
            filePath = "/music/a.mp3",
            probe = { com.zjr.hesimusic.loop.audio.source.PcmTrackFormat(44_100, 2) },
            durationUs = 25_000_000L,
        )
        assertEquals(
            LoopSourceFallback.Reason.WINDOW_INVALID,
            (pastDecision as LoopSourceFallback.Decision.UseM3Prime).reason,
        )
        assertTrue(pastDecision.detail.contains("window-past-duration"))
    }
}
