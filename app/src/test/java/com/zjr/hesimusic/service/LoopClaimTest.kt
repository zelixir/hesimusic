package com.zjr.hesimusic.service

import com.zjr.hesimusic.loop.LoopWindow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 00027 单一事实源单测：五种回退原因 × display / duration / 窗口归属 / resolver / store。
 *
 * 口径：A=50s，B=80s，L=30s；整曲 225s。
 */
class LoopClaimTest {

    private val songId = 7L
    private val window = LoopWindow(songId, 50_000_000L, 80_000_000L)
    private val fullUs = 225_000_000L

    @After
    fun tearDown() {
        LoopClaimStore.resetForTest()
        LoopSourceFallback.reset()
        LoopSourceRuntime.reset()
    }

    @Test
    fun `every fallback reason keeps the claim inactive and display unfolded`() {
        for (reason in LoopSourceFallback.Reason.values()) {
            val claim = LoopClaim(
                songId = songId,
                window = window,
                sourceActive = false,
                fallbackReason = reason,
            )
            assertFalse("reason=" + reason, claim.claimActive)
            assertNull("reason=" + reason, claim.effectiveWindow)
            assertEquals("reason=" + reason, 110_000L, claim.displayPositionMs(110_000L))
            assertEquals("reason=" + reason, 81_000L, claim.displayPositionMs(81_000L))
            assertEquals("reason=" + reason, 65_000L, claim.displayPositionMs(65_000L))
            assertEquals("reason=" + reason, fullUs, claim.durationUs(fullUs))
        }
    }

    @Test
    fun `active claim folds display and reports the full duration`() {
        val claim = LoopClaim(songId = songId, window = window, sourceActive = true)

        assertTrue(claim.claimActive)
        assertEquals(window, claim.effectiveWindow)
        assertEquals("B 点折回 A", 50_000L, claim.displayPositionMs(80_000L))
        assertEquals("B 后 1s = A 后 1s", 51_000L, claim.displayPositionMs(81_000L))
        assertEquals("整一轮后回 A", 50_000L, claim.displayPositionMs(110_000L))
        assertEquals("区间内原样", 65_000L, claim.displayPositionMs(65_000L))
        assertEquals("前奏只播一次", 0L, claim.displayPositionMs(0L))
        assertEquals(fullUs, claim.durationUs(fullUs))
        assertEquals(50_000_000L, claim.displayPositionUs(80_000_000L))
    }

    @Test
    fun `invalid window never claims`() {
        val zero = LoopWindow(songId, 50_000_000L, 50_000_000L)
        val reversed = LoopWindow(songId, 80_000_000L, 50_000_000L)
        for (w in listOf(zero, reversed)) {
            val claim = LoopClaim(songId, w, sourceActive = true)
            assertFalse(claim.claimActive)
            assertNull(claim.effectiveWindow)
            assertEquals(110_000L, claim.displayPositionMs(110_000L))
        }
    }

    @Test
    fun `window of another song or missing song never claims`() {
        val other = LoopWindow(songId + 1, 50_000_000L, 80_000_000L)
        assertFalse(LoopClaim(songId, other, sourceActive = true).claimActive)
        assertFalse(LoopClaim(null, window, sourceActive = true).claimActive)
    }

    @Test
    fun `resolver source route requires an installed non-failed window`() {
        val active = LoopClaimResolver.resolve(
            songId = songId,
            runtimeWindow = window,
            audioWindow = null,
            installedWindow = window,
            isReplaying = false,
            sourceRoute = true,
            sourceUnavailable = false,
        )
        assertTrue(active.claimActive)
        assertEquals(window, active.effectiveWindow)

        val notInstalled = LoopClaimResolver.resolve(
            songId = songId,
            runtimeWindow = window,
            audioWindow = null,
            installedWindow = null,
            isReplaying = false,
            sourceRoute = true,
            sourceUnavailable = false,
        )
        assertFalse(notInstalled.claimActive)
        assertNull(notInstalled.effectiveWindow)

        for (reason in LoopSourceFallback.Reason.values()) {
            val failed = LoopClaimResolver.resolve(
                songId = songId,
                runtimeWindow = window,
                audioWindow = null,
                installedWindow = window,
                isReplaying = false,
                sourceRoute = true,
                sourceUnavailable = true,
                fallbackReason = reason,
            )
            assertFalse("reason=" + reason, failed.claimActive)
            assertEquals("reason=" + reason, reason, failed.fallbackReason)
            assertEquals("reason=" + reason, 110_000L, failed.displayPositionMs(110_000L))
        }
    }

    @Test
    fun `resolver m3 route requires the window to be written into the audio layer`() {
        val notApplied = LoopClaimResolver.resolve(
            songId = songId,
            runtimeWindow = window,
            audioWindow = null,
            installedWindow = null,
            isReplaying = false,
            sourceRoute = false,
            sourceUnavailable = false,
        )
        assertFalse("窗口只算出来、还没写进 sink 时不能声称循环", notApplied.claimActive)

        val applied = LoopClaimResolver.resolve(
            songId = songId,
            runtimeWindow = window,
            audioWindow = window,
            installedWindow = null,
            isReplaying = false,
            sourceRoute = false,
            sourceUnavailable = false,
        )
        assertTrue(applied.claimActive)
    }

    @Test
    fun `resolver keeps the c2 audio fallback while replaying`() {
        val resolved = LoopClaimResolver.resolve(
            songId = songId,
            runtimeWindow = null,
            audioWindow = window,
            installedWindow = null,
            isReplaying = true,
            sourceRoute = false,
            sourceUnavailable = false,
        )
        assertEquals(window, resolved.window)
        assertTrue(resolved.claimActive)
        // 非重放期没有 audio 回退语义。
        assertNull(
            LoopClaimResolver.resolve(
                songId = songId,
                runtimeWindow = null,
                audioWindow = window,
                installedWindow = null,
                isReplaying = false,
                sourceRoute = false,
                sourceUnavailable = false,
            ).window,
        )
    }

    @Test
    fun `resolver rejects a stale runtime window and falls back to the current audio window`() {
        val stale = LoopWindow(songId + 1, 50_000_000L, 80_000_000L)
        val resolved = LoopClaimResolver.resolve(
            songId = songId,
            runtimeWindow = stale,
            audioWindow = window,
            installedWindow = null,
            isReplaying = true,
            sourceRoute = false,
            sourceUnavailable = false,
        )
        assertEquals(window, resolved.window)
        assertTrue(resolved.claimActive)
    }

    @Test
    fun `store serves the installed provider and invalidates on demand`() {
        assertFalse(LoopClaimStore.isInstalled())
        assertFalse(LoopClaimStore.claimFor(songId).claimActive)
        val before = LoopClaimStore.revisionFlow.value

        LoopClaimStore.install { id ->
            LoopClaim(id, window, sourceActive = true)
        }
        assertTrue(LoopClaimStore.isInstalled())
        assertTrue(LoopClaimStore.claimFor(songId).claimActive)
        assertTrue(LoopClaimStore.revisionFlow.value > before)

        val mid = LoopClaimStore.revisionFlow.value
        LoopClaimStore.invalidate()
        assertTrue(LoopClaimStore.revisionFlow.value > mid)

        LoopClaimStore.uninstall()
        assertFalse(LoopClaimStore.isInstalled())
        assertFalse(LoopClaimStore.claimFor(songId).claimActive)
    }
}
