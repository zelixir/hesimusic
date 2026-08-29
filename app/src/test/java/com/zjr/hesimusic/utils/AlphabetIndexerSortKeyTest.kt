package com.zjr.hesimusic.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlphabetIndexerSortKeyTest {

    @Test
    fun hanziSortedByFullPinyin() {
        // 涉(she) < 逝(shi) < 水(shui)
        assertTrue(AlphabetIndexer.sortKey("涉") < AlphabetIndexer.sortKey("逝"))
        assertTrue(AlphabetIndexer.sortKey("逝") < AlphabetIndexer.sortKey("水"))
    }

    @Test
    fun multiCharPinyinConcatenated() {
        assertEquals("gequ", AlphabetIndexer.sortKey("歌曲"))
    }

    @Test
    fun stripsTrackNumberPrefix() {
        assertEquals(AlphabetIndexer.sortKey("水仙十字安眠曲"), AlphabetIndexer.sortKey("32. 水仙十字安眠曲"))
        assertEquals(AlphabetIndexer.sortKey("Song"), AlphabetIndexer.sortKey("01.Song"))
    }

    @Test
    fun englishCaseInsensitive() {
        assertEquals("apple", AlphabetIndexer.sortKey("Apple"))
        assertEquals(AlphabetIndexer.sortKey("ABC"), AlphabetIndexer.sortKey("abc"))
    }

    @Test
    fun englishWordOrder() {
        // Simurgh < Sink（m < n）
        assertTrue(AlphabetIndexer.sortKey("Simurgh") < AlphabetIndexer.sortKey("Sink"))
    }

    @Test
    fun emptyAndPunctuationOnly() {
        assertEquals("", AlphabetIndexer.sortKey(""))
        assertEquals("", AlphabetIndexer.sortKey("01. "))
        assertEquals("@", AlphabetIndexer.sortKey("@"))
    }

    @Test
    fun kanaMapsToRowLetter() {
        assertEquals("s", AlphabetIndexer.sortKey("さ"))
        assertEquals("a", AlphabetIndexer.sortKey("あ"))
    }
}
