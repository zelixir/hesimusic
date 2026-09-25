package com.zjr.hesimusic.ui.common

import androidx.media3.common.Player
import com.zjr.hesimusic.data.mapper.buildQueueMediaId
import com.zjr.hesimusic.data.mapper.isQueueMediaId
import com.zjr.hesimusic.data.mapper.mediaIdToSongId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun `queue media id maps back to its song id`() {
        val mediaId = buildQueueMediaId(sequence = 7, songId = 42)
        assertTrue(isQueueMediaId(mediaId))
        assertEquals(42L, mediaIdToSongId(mediaId))
        assertEquals(mediaId, buildQueueMediaId(sequence = 7, songId = 42))
    }

    @Test
    fun `plain media id still maps to song id`() {
        assertFalse(isQueueMediaId("42"))
        assertEquals(42L, mediaIdToSongId("42"))
        assertNull(mediaIdToSongId("not-a-number"))
    }

    @Test
    fun `landing on the queued copy is recognized`() {
        val head = QueuedItem(buildQueueMediaId(sequence = 1, songId = 10), songId = 10L)
        assertEquals(QueueHeadHit.QUEUED_COPY, resolveQueueHeadHit(head.mediaId, head))
    }

    @Test
    fun `landing on another copy of the same song is recognized`() {
        val head = QueuedItem(buildQueueMediaId(sequence = 1, songId = 10), songId = 10L)
        assertEquals(QueueHeadHit.OTHER_COPY, resolveQueueHeadHit("10", head))
    }

    @Test
    fun `landing on a different song or nothing hits none`() {
        val head = QueuedItem(buildQueueMediaId(sequence = 1, songId = 10), songId = 10L)
        assertEquals(QueueHeadHit.NONE, resolveQueueHeadHit("20", head))
        assertEquals(QueueHeadHit.NONE, resolveQueueHeadHit(null, head))
        assertEquals(QueueHeadHit.NONE, resolveQueueHeadHit("10", null))
    }
}
