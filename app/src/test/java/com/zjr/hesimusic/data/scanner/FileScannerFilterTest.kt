package com.zjr.hesimusic.data.scanner

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FileScannerFilterTest {

    @Test
    fun `shouldSkipByDuration only skips songs shorter than 60 seconds when enabled`() {
        assertTrue(shouldSkipByDuration(59_999, skipShortSongs = true))
        assertFalse(shouldSkipByDuration(60_000, skipShortSongs = true))
        assertFalse(shouldSkipByDuration(30_000, skipShortSongs = false))
    }

    @Test
    fun `shouldIncludeAmrMid only includes amr and mid when skip is disabled`() {
        assertTrue(shouldIncludeAmrMid("amr", skipAmrMid = false))
        assertTrue(shouldIncludeAmrMid("mid", skipAmrMid = false))
        assertFalse(shouldIncludeAmrMid("mp3", skipAmrMid = false))
        assertFalse(shouldIncludeAmrMid("amr", skipAmrMid = true))
    }

    @Test
    fun `shouldSkipHiddenFolder checks hidden naming when enabled`() {
        assertTrue(shouldSkipHiddenFolder(File(".secret"), skipHiddenFolders = true))
        assertFalse(shouldSkipHiddenFolder(File("Music"), skipHiddenFolders = true))
        assertFalse(shouldSkipHiddenFolder(File(".secret"), skipHiddenFolders = false))
    }
}
