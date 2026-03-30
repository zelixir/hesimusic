package com.zjr.hesimusic.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class AlphabetIndexerTest {

    @Test
    fun testStripTrackNumber_withVariousFormats() {
        // Test standard track number formats
        assertEquals("Song Title", AlphabetIndexer.stripTrackNumber("01. Song Title"))
        assertEquals("Song Title", AlphabetIndexer.stripTrackNumber("1. Song Title"))
        assertEquals("Song Title", AlphabetIndexer.stripTrackNumber("123. Song Title"))
        assertEquals("Track Name", AlphabetIndexer.stripTrackNumber("99. Track Name"))
        
        // Test with multiple spaces after dot
        assertEquals("Song Title", AlphabetIndexer.stripTrackNumber("01.  Song Title"))
        assertEquals("Song Title", AlphabetIndexer.stripTrackNumber("01.   Song Title"))
        
        // Test without track number - should remain unchanged
        assertEquals("Song Title", AlphabetIndexer.stripTrackNumber("Song Title"))
        assertEquals("No Track Number", AlphabetIndexer.stripTrackNumber("No Track Number"))
        
        // Test edge cases
        assertEquals("", AlphabetIndexer.stripTrackNumber("01. "))
        assertEquals("Song", AlphabetIndexer.stripTrackNumber("01.Song")) // No space after dot
        assertEquals("1-Song", AlphabetIndexer.stripTrackNumber("1-Song")) // Different separator
    }

    @Test
    fun testGetInitial_withTrackNumbers() {
        // Test English titles with track numbers
        assertEquals('S', AlphabetIndexer.getInitial("01. Song Title"))
        assertEquals('S', AlphabetIndexer.getInitial("01.Song Title"))
        assertEquals('A', AlphabetIndexer.getInitial("1. Amazing"))
        assertEquals('T', AlphabetIndexer.getInitial("123. Test Track"))
        
        // Test without track numbers
        assertEquals('M', AlphabetIndexer.getInitial("Music"))
        assertEquals('A', AlphabetIndexer.getInitial("Album"))
    }

    @Test
    fun testGetInitial_caseInsensitive() {
        // The initial should be uppercase regardless of input case
        assertEquals('S', AlphabetIndexer.getInitial("song"))
        assertEquals('S', AlphabetIndexer.getInitial("Song"))
        assertEquals('S', AlphabetIndexer.getInitial("SONG"))
        
        // With track numbers
        assertEquals('S', AlphabetIndexer.getInitial("01. song"))
        assertEquals('S', AlphabetIndexer.getInitial("01. Song"))
        assertEquals('S', AlphabetIndexer.getInitial("01. SONG"))
    }

    @Test
    fun testGetInitial_chineseWithTrackNumbers() {
        // Test Chinese characters with track numbers
        assertEquals('N', AlphabetIndexer.getInitial("01. 你好")) // 你 -> Ni
        assertEquals('G', AlphabetIndexer.getInitial("1. 歌曲")) // 歌 -> Ge
        assertEquals('Y', AlphabetIndexer.getInitial("123. 音乐")) // 音 -> Yin
    }

    @Test
    fun testGetInitial_japaneseWithTrackNumbers() {
        // Test Japanese kana with track numbers
        assertEquals('A', AlphabetIndexer.getInitial("01. あいうえお"))
        assertEquals('K', AlphabetIndexer.getInitial("1. かきくけこ"))
        assertEquals('S', AlphabetIndexer.getInitial("123. さしすせそ"))
    }

    @Test
    fun testGetInitial_emptyAndNull() {
        assertEquals('#', AlphabetIndexer.getInitial(null))
        assertEquals('#', AlphabetIndexer.getInitial(""))
        assertEquals('#', AlphabetIndexer.getInitial("01. ")) // Only track number
    }

    @Test
    fun testGetInitial_specialCharacters() {
        // Special characters should return '#'
        assertEquals('#', AlphabetIndexer.getInitial("@Special"))
        assertEquals('#', AlphabetIndexer.getInitial("01. @Special"))
        assertEquals('#', AlphabetIndexer.getInitial("#Hashtag"))
        assertEquals('#', AlphabetIndexer.getInitial("01. #Hashtag"))
    }

    @Test
    fun testGetInitial_numbers() {
        // Leading numbers (not in track number format) should return '#'
        assertEquals('#', AlphabetIndexer.getInitial("2024 Song"))
        assertEquals('S', AlphabetIndexer.getInitial("01. 2024 Song")) // After stripping track number
    }

    @Test
    fun testStripTrackNumber_unicodeCharacters() {
        // Test with various Unicode characters after track number
        assertEquals("歌曲名", AlphabetIndexer.stripTrackNumber("01. 歌曲名"))
        assertEquals("さくら", AlphabetIndexer.stripTrackNumber("1. さくら"))
        assertEquals("Café", AlphabetIndexer.stripTrackNumber("123. Café"))
    }
}
