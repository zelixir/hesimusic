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
    fun kanaMapsToRomaji() {
        // Kana map to full Hepburn romaji, not just the row letter.
        assertEquals("sa", AlphabetIndexer.sortKey("さ"))
        assertEquals("a", AlphabetIndexer.sortKey("あ"))
        assertEquals("shi", AlphabetIndexer.sortKey("し"))
        assertEquals("tsu", AlphabetIndexer.sortKey("つ"))
        assertEquals("n", AlphabetIndexer.sortKey("ん"))
        assertEquals("ru", AlphabetIndexer.sortKey("ル"))
        assertEquals("sakura", AlphabetIndexer.sortKey("さくら"))
    }

    @Test
    fun kanaOrderFollowsGojuon() {
        // さ(sa) < し(shi) < す(su) keep gojūon order for 2ndary keys.
        assertTrue(AlphabetIndexer.sortKey("さくら") < AlphabetIndexer.sortKey("しずく"))
        assertTrue(AlphabetIndexer.sortKey("しずく") < AlphabetIndexer.sortKey("すみれ"))
    }

    @Test
    fun realLibraryYouTitlesClusterAdjacent() {
        // Regression for the failed in-app verification: these real library
        // titles all start with 幽 (you-) and must sort together; full-pinyin
        // keys order them 灯(deng) < 独(du) < 谷(gu) < 芒(mang), and the
        // "22. " track-number prefix must be stripped before keying.
        val keys = listOf(
            AlphabetIndexer.sortKey("幽灯所及之崖 Glimmer's End"),
            AlphabetIndexer.sortKey("幽独的喧嚣 Too Loud a Solitude"),
            AlphabetIndexer.sortKey("幽谷舟咏·其三 The Rime of the Ancient Bargeman (III)"),
            AlphabetIndexer.sortKey("22. 幽芒驻息 Where the Wandering Ones Rest")
        )
        assertEquals(keys.sorted(), keys)
        assertEquals(
            AlphabetIndexer.sortKey("幽芒驻息 Where the Wandering Ones Rest"),
            AlphabetIndexer.sortKey("22. 幽芒驻息 Where the Wandering Ones Rest")
        )
        // Without the prefix the keys would differ in length/prefix; with it they match.
        assertTrue(keys[0].startsWith("youdeng"))
        assertTrue(keys[3].startsWith("youmang"))
    }
}