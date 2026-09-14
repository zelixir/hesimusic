package com.zjr.hesimusic.ui.library

import com.zjr.hesimusic.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SongListTest {

    @Test
    fun `should auto-scroll when first valid current song appears`() {
        val grouped = mapOf(
            'A' to listOf(
                Song(
                    id = 1L,
                    title = "A Song",
                    artist = "Artist",
                    album = "Album",
                    filePath = "/music/a.mp3",
                    duration = 1000L,
                    mimeType = "audio/mpeg",
                    size = 1024L,
                    dateAdded = 1L
                )
            )
        )

        assertTrue(
            shouldAutoScrollToCurrentSong(
                hasAutoScrolledToCurrentSong = false,
                currentPlayingSongId = "1",
                grouped = grouped
            )
        )
    }

    @Test
    fun `should not auto-scroll after initial restore`() {
        val grouped = mapOf(
            'A' to listOf(
                Song(
                    id = 1L,
                    title = "A Song",
                    artist = "Artist",
                    album = "Album",
                    filePath = "/music/a.mp3",
                    duration = 1000L,
                    mimeType = "audio/mpeg",
                    size = 1024L,
                    dateAdded = 1L
                )
            )
        )

        assertFalse(
            shouldAutoScrollToCurrentSong(
                hasAutoScrolledToCurrentSong = true,
                currentPlayingSongId = "1",
                grouped = grouped
            )
        )
    }

    @Test
    fun `track number ordering sorts by trackNumber then title`() {
        val songs = listOf(
            testSong(id = 1L, title = "B Song", trackNumber = 2),
            testSong(id = 2L, title = "A Song", trackNumber = 1)
        )

        assertEquals(listOf(2L, 1L), orderSongsByTrackNumber(songs).map { it.id })
    }

    @Test
    fun `track number ordering puts missing track numbers last with title tiebreak`() {
        val songs = listOf(
            testSong(id = 1L, title = "B Song", trackNumber = 0),
            testSong(id = 2L, title = "Z Song", trackNumber = 2),
            testSong(id = 3L, title = "A Song", trackNumber = 2),
            testSong(id = 4L, title = "A Song", trackNumber = 0)
        )

        assertEquals(listOf(3L, 2L, 4L, 1L), orderSongsByTrackNumber(songs).map { it.id })
    }

    private fun testSong(id: Long, title: String, trackNumber: Int = 0) = Song(
        id = id,
        title = title,
        artist = "Artist",
        album = "Album",
        filePath = "/music/$id.mp3",
        duration = 1000L,
        trackNumber = trackNumber,
        mimeType = "audio/mpeg",
        size = 1024L,
        dateAdded = 1L
    )
}
