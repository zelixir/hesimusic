package com.zjr.hesimusic.data.repository

import com.zjr.hesimusic.data.model.FileSystemItem
import com.zjr.hesimusic.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryRepositoryTest {

    @Test
    fun `sortFolderItems sorts folders and files by computed initials`() {
        val items = listOf(
            folder("音乐"),
            musicFile("你好"),
            folder("歌单"),
            musicFile("Zebra"),
            folder("Apple")
        )

        val result = sortFolderItems(items)

        assertEquals(
            listOf("Apple", "歌单", "音乐", "你好", "Zebra"),
            result.map(::displayName)
        )
    }

    @Test
    fun `sortFolderItems ignores track number prefixes when ordering songs with same initial`() {
        val items = listOf(
            musicFile("01. Apricot"),
            musicFile("10. Apple"),
            musicFile("02. Avocado")
        )

        val result = sortFolderItems(items)

        assertEquals(
            listOf("10. Apple", "01. Apricot", "02. Avocado"),
            result.map(::displayName)
        )
    }

    private fun folder(name: String) = FileSystemItem.Folder(
        name = name,
        path = "/music/$name",
        songCount = 1
    )

    private fun musicFile(title: String) = FileSystemItem.MusicFile(
        Song(
            id = title.hashCode().toLong(),
            title = title,
            artist = "artist",
            album = "album",
            filePath = "/music/$title.mp3",
            duration = 180_000L,
            mimeType = "audio/mpeg",
            size = 1024L,
            dateAdded = 1L
        )
    )

    private fun displayName(item: FileSystemItem): String = when (item) {
        is FileSystemItem.Folder -> item.name
        is FileSystemItem.MusicFile -> item.song.title
    }
}
