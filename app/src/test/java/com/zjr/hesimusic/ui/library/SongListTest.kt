package com.zjr.hesimusic.ui.library

import com.zjr.hesimusic.data.model.Song
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
}
