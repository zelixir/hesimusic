package com.zjr.hesimusic.ui.library

import com.zjr.hesimusic.data.model.Album
import com.zjr.hesimusic.data.model.Artist
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryViewModelTest {

    @Test
    fun `filterAlbums excludes albums with fewer than five songs`() {
        val albums = listOf(
            Album(name = "A", artist = "Artist A", songCount = 4),
            Album(name = "B", artist = "Artist B", songCount = 5)
        )

        val result = filterAlbums(albums, query = "", minAlbumTrackCount = 5)

        assertEquals(listOf("B"), result.map { it.name })
    }

    @Test
    fun `filterAlbums applies search after song count filter`() {
        val albums = listOf(
            Album(name = "Acoustic", artist = "Artist A", songCount = 4),
            Album(name = "Acoustic Live", artist = "Artist B", songCount = 5),
            Album(name = "Studio", artist = "Acoustic Band", songCount = 6)
        )

        val result = filterAlbums(albums, query = "acoustic", minAlbumTrackCount = 5)

        assertEquals(listOf("Acoustic Live", "Studio"), result.map { it.name })
    }

    @Test
    fun `filterArtists excludes artists with fewer songs than threshold`() {
        val artists = listOf(
            Artist(name = "Artist A", songCount = 4),
            Artist(name = "Artist B", songCount = 5)
        )

        val result = filterArtists(artists, query = "", minArtistTrackCount = 5)

        assertEquals(listOf("Artist B"), result.map { it.name })
    }
}
