package com.zjr.hesimusic.service

import androidx.media3.common.Player
import com.zjr.hesimusic.data.model.SongLoop
import com.zjr.hesimusic.loop.LoopRuntime
import com.zjr.hesimusic.loop.audio.LoopAudioController
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #88：#SOURCE 重建后 READY 之前，claim 必须保持“未生效”，不能先报 claimed=true。
 *
 * 旧行为立即 onInstalled，会制造 claimed=true/sourceActive=false（撞
 * loop-claim-without-source）同时 /state 报 engine=loop 而 playing 仍 false。
 * 现在 pending 期间 claim 不暴露窗口；READY 发布后 claimed/claimActive 才为 true。
 */
class LoopClaimPendingInstallTest {

    private val songId = 7L
    private val fullDurationUs = 225_000_000L

    @After
    fun tearDown() {
        LoopSourceRuntime.reset()
        MainPlayerRouting.reset()
    }

    private fun runtimeWithWindow(): Pair<LoopRuntime, com.zjr.hesimusic.loop.LoopWindow> {
        val runtime = LoopRuntime()
        val row = SongLoop(
            songId = songId,
            loopStartSamples = 441_000L,
            loopEndSamples = 882_000L,
            sampleRate = 44_100,
            loopStartMs = 10_000L,
            loopEndMs = 20_000L,
            score = 1.0,
            noteDistance = 0.0,
            loudnessDifference = 0.0,
            createdAt = 0L,
            confirmed = true,
        )
        runtime.refresh(songId, Player.REPEAT_MODE_ONE, row, armedSongId = songId, durationUs = fullDurationUs)
        val window = runtime.window.value!!
        return runtime to window
    }

    @Test
    fun pendingInstall_isNotClaimedYet() {
        MainPlayerRouting.activate(MainPlayerRoute.of(useSourceForMainPlayer = true))
        val (runtime, window) = runtimeWithWindow()
        LoopSourceRuntime.onInstallPending(window)
        assertTrue(LoopSourceRuntime.isInstallPendingFor(window))

        val claim = LoopClaimResolver.fromProcess(songId, runtime, LoopAudioController())
        assertNull("pending 期间窗口不能对外暴露", claim.window)
        val fields = claim.debugFields()
        assertFalse("claimed 必须 false（否则撞 loop-claim-without-source）", fields.claimed)
        assertFalse(fields.claimActive)
        assertFalse(fields.sourceActive)
    }

    @Test
    fun publishedInstall_claimsTheWindow() {
        MainPlayerRouting.activate(MainPlayerRoute.of(useSourceForMainPlayer = true))
        val (runtime, window) = runtimeWithWindow()
        LoopSourceRuntime.onInstallPending(window)
        LoopSourceRuntime.onInstalled(window, "/music/x.mp3")
        assertFalse(LoopSourceRuntime.isInstallPendingFor(window))

        val claim = LoopClaimResolver.fromProcess(songId, runtime, LoopAudioController())
        assertEquals(window, claim.window)
        assertTrue(claim.claimActive)
        val fields = claim.debugFields()
        assertTrue(fields.claimed)
        assertTrue(fields.sourceActive)
    }

    @Test
    fun releasedInstall_clearsPending() {
        val (_, window) = runtimeWithWindow()
        LoopSourceRuntime.onInstallPending(window)
        LoopSourceRuntime.onReleased()
        assertFalse(LoopSourceRuntime.isInstallPendingFor(window))
        assertNull(LoopSourceRuntime.installedWindow)
    }
}
