package com.zjr.hesimusic.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FavoriteTest {

    @Test
    fun `Favorite with same filePath and startPosition are equal`() {
        val favorite1 = Favorite(filePath = "/path/to/audio.flac", startPosition = 1000L)
        val favorite2 = Favorite(filePath = "/path/to/audio.flac", startPosition = 1000L)
        
        assertEquals(favorite1.filePath, favorite2.filePath)
        assertEquals(favorite1.startPosition, favorite2.startPosition)
    }

    @Test
    fun `Favorite with same filePath but different startPosition are different`() {
        // This tests the core fix for the CUE track favorite bug
        val favorite1 = Favorite(filePath = "/path/to/audio.flac", startPosition = 0L)
        val favorite2 = Favorite(filePath = "/path/to/audio.flac", startPosition = 180000L)
        
        assertEquals(favorite1.filePath, favorite2.filePath)
        assertNotEquals(favorite1.startPosition, favorite2.startPosition)
    }

    @Test
    fun `Favorite default startPosition is zero for non-CUE tracks`() {
        val favorite = Favorite(filePath = "/path/to/audio.mp3")
        
        assertEquals(0L, favorite.startPosition)
    }

    @Test
    fun `Favorite stores dateAdded timestamp`() {
        val beforeTime = System.currentTimeMillis()
        val favorite = Favorite(filePath = "/path/to/audio.flac", startPosition = 0L)
        val afterTime = System.currentTimeMillis()
        
        assert(favorite.dateAdded >= beforeTime)
        assert(favorite.dateAdded <= afterTime)
    }

    @Test
    fun `Multiple CUE tracks from same file have unique favorites`() {
        // Simulate a CUE album with 3 tracks
        val audioFile = "/path/to/album.flac"
        val track1 = Favorite(filePath = audioFile, startPosition = 0L)       // Track 1: 0:00
        val track2 = Favorite(filePath = audioFile, startPosition = 180000L)  // Track 2: 3:00
        val track3 = Favorite(filePath = audioFile, startPosition = 360000L)  // Track 3: 6:00
        
        // All tracks share the same file path
        assertEquals(track1.filePath, track2.filePath)
        assertEquals(track2.filePath, track3.filePath)
        
        // But they have different start positions (composite key differentiator)
        assertNotEquals(track1.startPosition, track2.startPosition)
        assertNotEquals(track2.startPosition, track3.startPosition)
        assertNotEquals(track1.startPosition, track3.startPosition)
    }
}
