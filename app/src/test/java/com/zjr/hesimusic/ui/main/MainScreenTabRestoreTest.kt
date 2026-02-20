package com.zjr.hesimusic.ui.main

import com.zjr.hesimusic.data.preferences.PlaylistType
import org.junit.Assert.assertEquals
import org.junit.Test

class MainScreenTabRestoreTest {

    @Test
    fun `playlist type maps to expected tab index`() {
        assertEquals(0, playlistTypeToTabIndex(PlaylistType.GLOBAL))
        assertEquals(1, playlistTypeToTabIndex(PlaylistType.PLAYLIST))
        assertEquals(2, playlistTypeToTabIndex(PlaylistType.FAVORITES))
        assertEquals(3, playlistTypeToTabIndex(PlaylistType.FOLDER))
        assertEquals(4, playlistTypeToTabIndex(PlaylistType.ARTIST))
        assertEquals(5, playlistTypeToTabIndex(PlaylistType.ALBUM))
    }

    @Test
    fun `favorite action text follows tab context`() {
        assertEquals("加入收藏", favoriteActionTextForTab(0))
        assertEquals("加入收藏", favoriteActionTextForTab(1))
        assertEquals("取消收藏", favoriteActionTextForTab(2))
    }

    @Test
    fun `parse playlist id returns valid positive id`() {
        assertEquals(123L, parsePlaylistId("123"))
        assertEquals(null, parsePlaylistId("0"))
        assertEquals(null, parsePlaylistId("-1"))
        assertEquals(null, parsePlaylistId("abc"))
    }
}
