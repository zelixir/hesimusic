package com.zjr.hesimusic.ui.common

import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicViewModelQueueTest {

    @Test
    fun `force queue transition on auto when transitioned song is not queue head`() {
        assertTrue(
            shouldForceQueueTransition(
                reason = Player.MEDIA_ITEM_TRANSITION_REASON_AUTO,
                currentSongId = 20L,
                queuedSongId = 10L
            )
        )
    }

    @Test
    fun `do not force queue transition when transitioned song is queue head`() {
        assertFalse(
            shouldForceQueueTransition(
                reason = Player.MEDIA_ITEM_TRANSITION_REASON_AUTO,
                currentSongId = 10L,
                queuedSongId = 10L
            )
        )
    }

    @Test
    fun `force queue transition on repeat when transitioned song is not queue head`() {
        assertTrue(
            shouldForceQueueTransition(
                reason = Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT,
                currentSongId = 11L,
                queuedSongId = 10L
            )
        )
    }
}
