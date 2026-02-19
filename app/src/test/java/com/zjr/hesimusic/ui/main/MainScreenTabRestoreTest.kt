package com.zjr.hesimusic.ui.main

import com.zjr.hesimusic.data.preferences.PlaylistType
import org.junit.Assert.assertEquals
import org.junit.Test

class MainScreenTabRestoreTest {

    @Test
    fun `playlist type maps to expected tab index`() {
        assertEquals(0, playlistTypeToTabIndex(PlaylistType.GLOBAL))
        assertEquals(1, playlistTypeToTabIndex(PlaylistType.FAVORITES))
        assertEquals(2, playlistTypeToTabIndex(PlaylistType.ARTIST))
        assertEquals(3, playlistTypeToTabIndex(PlaylistType.ALBUM))
        assertEquals(4, playlistTypeToTabIndex(PlaylistType.FOLDER))
    }
}
