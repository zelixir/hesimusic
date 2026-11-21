package com.zjr.hesimusic.loop.audio.source

import com.zjr.hesimusic.loop.LoopWindow
import com.zjr.hesimusic.service.LoopClaim
import com.zjr.hesimusic.service.LoopClaimStore
import com.zjr.hesimusic.service.LoopSourceFallback
import com.zjr.hesimusic.service.LoopSourceRuntime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopEngineInfoTest {

    @After
    fun tearDown() {
        LoopSourceMode.current = LoopSourceMode.SOURCE
        LoopSourceMainPlayer.setEnabledForTest(true)
        LoopSourceFallback.reset()
        LoopSourceRuntime.reset()
        LoopClaimStore.uninstall()
        LoopEngineInfo.recordBandBytes(0L)
    }

    @Test
    fun `empty preference defaults to source ring`() {
        assertEquals(LoopSourceMode.SOURCE, LoopSourceMode.defaultMode())
        assertEquals(LoopSourceMode.SOURCE, LoopSourceMode.fromPreference(null))
        assertEquals(LoopSourceMode.SOURCE, LoopSourceMode.fromPreference("source"))
        Sonar.assertDefaultMain()
        val sourceInfo = LoopEngineInfo.of(
            sourceMode = true,
            mainEnabled = true,
            claimActive = true,
        )
        assertEquals(LoopEngineInfo.ENGINE_SOURCE_RING, sourceInfo.engine)
        assertEquals("source", sourceInfo.loopPath)
    }

    @Test
    fun `explicit m3 still wins`() {
        assertEquals(LoopSourceMode.M3_PRIME, LoopSourceMode.fromPreference("m3"))
        val info = LoopEngineInfo.of(
            sourceMode = false,
            mainEnabled = true,
            claimActive = true,
        )
        assertEquals(LoopEngineInfo.ENGINE_M3_PRIME, info.engine)
        assertEquals("m3", info.loopPath)
        assertEquals(0L, info.bandBytes)
    }

    @Test
    fun `fallback reason reports plain playback and keeps reason`() {
        // 00027/P2：主播放器 SOURCE 回退是普通整曲，不是 M3′。
        val info = LoopEngineInfo.of(
            sourceMode = true,
            mainEnabled = true,
            bandBytes = 7_680_000L,
            fallbackReason = LoopSourceFallback.Reason.RING_TOO_LARGE.logName,
        )
        assertEquals(LoopEngineInfo.ENGINE_NONE, info.engine)
        assertEquals(0L, info.bandBytes)
        assertEquals("ring-too-large", info.fallbackReason)
        assertEquals("none", info.loopPath)
        assertTrue(info.label().contains("FALLBACK_REASON=ring-too-large"))
    }

    @Test
    fun `runtime fallback reason is visible through current`() {
        LoopSourceMode.current = LoopSourceMode.SOURCE
        LoopSourceMainPlayer.setEnabledForTest(true)
        LoopSourceFallback.reportUnavailable(
            songId = 7L,
            window = com.zjr.hesimusic.loop.LoopWindow(7L, 0L, 1_000_000L),
            reason = LoopSourceFallback.Reason.RING_TOO_LARGE,
            detail = "test",
        )
        val info = LoopEngineInfo.current()
        assertEquals(LoopEngineInfo.ENGINE_NONE, info.engine)
        assertEquals("none", info.loopPath)
        assertEquals("ring-too-large", info.fallbackReason)
    }

    @Test
    fun `current reads the active claim instead of the global latest reason`() {
        val window = LoopWindow(7L, 0L, 1_000_000L)
        LoopClaimStore.install {
            LoopClaim(
                songId = 7L,
                window = window,
                sourceActive = true,
                fallbackReason = null,
            )
        }
        val active = LoopEngineInfo.current()
        assertEquals(LoopEngineInfo.ENGINE_SOURCE_RING, active.engine)
        assertEquals("source", active.loopPath)

        // 另一个窗口失败（全局 latest 会变），但当前 claim 仍生效：不能串到 About。
        LoopSourceFallback.reportUnavailable(
            songId = 99L,
            window = LoopWindow(99L, 0L, 1_000_000L),
            reason = LoopSourceFallback.Reason.RING_TOO_LARGE,
        )
        val stillActive = LoopEngineInfo.current()
        assertEquals(LoopEngineInfo.ENGINE_SOURCE_RING, stillActive.engine)
        assertEquals("source", stillActive.loopPath)
        assertEquals(null, stillActive.fallbackReason)
    }

    @Test
    fun `source info carries band bytes`() {
        LoopEngineInfo.recordBandBytes(3_456_000L)
        val info = LoopEngineInfo.of(
            sourceMode = true,
            mainEnabled = true,
            bandBytes = 3_456_000L,
            claimActive = true,
        )
        assertEquals(LoopEngineInfo.ENGINE_SOURCE_RING, info.engine)
        assertEquals(3_456_000L, info.bandBytes)
        assertEquals("source", info.loopPath)
        assertTrue(info.label().startsWith("ENGINE=SOURCE_RING LOOP_PATH=source BAND_BYTES=3456000"))
    }

    private object Sonar {
        fun assertDefaultMain() {
            LoopSourceMainPlayer.setEnabledForTest(null)
            LoopSourceMode.current = LoopSourceMode.SOURCE
            assertTrue(LoopSourceMainPlayer.enabled)
            assertTrue(LoopSourceMainPlayer.shouldUseSourceForMainPlayer())
            assertTrue(LoopSourceMainPlayer.shouldUseSourceForMainPlayer(sourceMode = true))
        }
    }
}
