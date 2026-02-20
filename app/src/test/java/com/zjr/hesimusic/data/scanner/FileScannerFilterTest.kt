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
    fun `shouldSkipAmrMid only skips amr and mid when enabled`() {
        assertTrue(shouldSkipAmrMid("amr", skipAmrMid = true))
        assertTrue(shouldSkipAmrMid("mid", skipAmrMid = true))
        assertFalse(shouldSkipAmrMid("mp3", skipAmrMid = true))
        assertFalse(shouldSkipAmrMid("amr", skipAmrMid = false))
    }

    @Test
    fun `shouldSkipHiddenFolder checks hidden naming when enabled`() {
        assertTrue(shouldSkipHiddenFolder(File(".secret"), skipHiddenFolders = true))
        assertFalse(shouldSkipHiddenFolder(File("Music"), skipHiddenFolders = true))
        assertFalse(shouldSkipHiddenFolder(File(".secret"), skipHiddenFolders = false))
    }
}
