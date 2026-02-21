package com.zjr.hesimusic.data.repository

import com.zjr.hesimusic.data.model.Favorite
import com.zjr.hesimusic.data.model.PlaylistEntry
import com.zjr.hesimusic.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Test

class ScanRepositoryTest {

    @Test
    fun `remapPlaylistEntries keeps songs that still exist after rescan`() {
        val existingSongs = listOf(
            testSong(id = 10, filePath = "/music/a.mp3", startPosition = 0),
            testSong(id = 11, filePath = "/music/b.mp3", startPosition = 0)
        )
        val existingEntries = listOf(
            PlaylistEntry(id = 1, playlistId = 7, songId = 10, order = 0),
            PlaylistEntry(id = 2, playlistId = 7, songId = 11, order = 1)
        )
        val insertedSongs = listOf(
            testSong(id = 101, filePath = "/music/a.mp3", startPosition = 0)
        )

        val remapped = remapPlaylistEntries(existingSongs, existingEntries, insertedSongs)

        assertEquals(1, remapped.size)
        assertEquals(7L, remapped[0].playlistId)
        assertEquals(101L, remapped[0].songId)
        assertEquals(0, remapped[0].order)
    }

    @Test
    fun `filterFavoritesByExistingSongs removes favorites for missing files`() {
        val favorites = listOf(
            Favorite(filePath = "/music/a.mp3", startPosition = 0),
            Favorite(filePath = "/music/missing.mp3", startPosition = 0)
        )
        val insertedSongs = listOf(
            testSong(id = 201, filePath = "/music/a.mp3", startPosition = 0)
        )

        val remaining = filterFavoritesByExistingSongs(favorites, insertedSongs)

        assertEquals(1, remaining.size)
        assertEquals("/music/a.mp3", remaining[0].filePath)
        assertEquals(0L, remaining[0].startPosition)
    }

    private fun testSong(id: Long, filePath: String, startPosition: Long) = Song(
        id = id,
        title = "title",
        artist = "artist",
        album = "album",
        filePath = filePath,
        duration = 120_000L,
        mimeType = "audio/mpeg",
        size = 1024L,
        dateAdded = 1L,
        startPosition = startPosition
    )
}
