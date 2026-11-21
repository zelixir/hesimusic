package com.zjr.hesimusic.service

import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #88：SOURCE 重建后必须等"可播且已开播"再发布 claim。
 *
 * READY 与 isPlaying=true 不是同一个回调（真机相差 ~1ms）；如果只等 READY，
 * /state 仍可能在 READY 与 onIsPlayingChanged 之间采到 claim=true/playing=false。
 * 暂停进入循环（pwr=false）则 READY 即可发布，否则暂停进入会永远 claim=false。
 */
class SourceInstallPublishPolicyTest {

    private fun should(
        state: Int,
        playWhenReady: Boolean = true,
        isPlaying: Boolean = true,
        force: Boolean = false,
    ): Boolean = SourceInstallPublishPolicy.shouldPublish(
        playbackState = state,
        playWhenReady = playWhenReady,
        isPlaying = isPlaying,
        force = force,
    )

    @Test
    fun readyAndPlayingPublishesImmediately() {
        assertTrue(should(Player.STATE_READY, playWhenReady = true, isPlaying = true))
    }

    @Test
    fun readyButNotYetPlaying_waitsForIsPlaying() {
        assertFalse(should(Player.STATE_READY, playWhenReady = true, isPlaying = false))
    }

    @Test
    fun pausedEntry_publishesAtReady() {
        // 暂停中进入循环：不会 isPlaying=true，但 READY 即应 claim。
        assertTrue(should(Player.STATE_READY, playWhenReady = false, isPlaying = false))
    }

    @Test
    fun endedAlsoPublishes() {
        assertTrue(should(Player.STATE_ENDED, playWhenReady = false, isPlaying = false))
    }

    @Test
    fun bufferingDoesNotPublishUntilReady() {
        assertFalse(should(Player.STATE_BUFFERING, playWhenReady = true, isPlaying = false))
    }

    @Test
    fun idleDoesNotPublishUntilReady() {
        assertFalse(should(Player.STATE_IDLE, playWhenReady = true, isPlaying = false))
    }

    @Test
    fun timeoutForcePublishesSoClaimNeverDisappears() {
        assertTrue(should(Player.STATE_BUFFERING, playWhenReady = true, isPlaying = false, force = true))
        assertTrue(should(Player.STATE_IDLE, playWhenReady = false, isPlaying = false, force = true))
    }
}
