package com.zjr.hesimusic.ui.library

import com.zjr.hesimusic.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SongListTest {

    // ---- 自动定位当前播放歌(issue 00002)----
    // 判定只看「视图 epoch 是否变化」:epoch 变化 = 切换视图后回到列表,应重新定位;
    // epoch 不变 = 从详情页 / 分析页返回(列表重新组合),不能再滚一次把用户位置顶掉。

    @Test
    fun `auto-scrolls on first entry into a list`() {
        assertTrue(
            shouldAutoScrollToCurrentSong(
                autoScrolledViewEpoch = AUTO_SCROLL_NEVER,
                viewEpoch = 0,
                currentPlayingSongId = "1",
                hasItems = true
            )
        )
    }

    @Test
    fun `epoch change re-auto-scrolls after switching views`() {
        // 在 epoch 7 已经定位过一次(标志记下 7);切到别的视图再切回,MainScreen 把 epoch 推到 8。
        assertTrue(
            shouldAutoScrollToCurrentSong(
                autoScrolledViewEpoch = 7,
                viewEpoch = 8,
                currentPlayingSongId = "1",
                hasItems = true
            )
        )
    }

    @Test
    fun `same epoch keeps scroll position when returning from details`() {
        // 从详情页 / 无缝循环分析页返回:列表重新组合,但 epoch 没变 → 不再定位,保住滚动位置。
        assertFalse(
            shouldAutoScrollToCurrentSong(
                autoScrolledViewEpoch = 7,
                viewEpoch = 7,
                currentPlayingSongId = "1",
                hasItems = true
            )
        )
    }

    @Test
    fun `never auto-scrolls without a current playing song`() {
        assertFalse(
            shouldAutoScrollToCurrentSong(
                autoScrolledViewEpoch = AUTO_SCROLL_NEVER,
                viewEpoch = 8,
                currentPlayingSongId = null,
                hasItems = true
            )
        )
    }

    @Test
    fun `never auto-scrolls an empty list`() {
        assertFalse(
            shouldAutoScrollToCurrentSong(
                autoScrolledViewEpoch = AUTO_SCROLL_NEVER,
                viewEpoch = 0,
                currentPlayingSongId = "1",
                hasItems = false
            )
        )
    }

    @Test
    fun `track number ordering sorts by trackNumber then title`() {
        val songs = listOf(
            testSong(id = 1L, title = "B Song", trackNumber = 2),
            testSong(id = 2L, title = "A Song", trackNumber = 1)
        )

        assertEquals(listOf(2L, 1L), orderSongsByTrackNumber(songs).map { it.id })
    }

    @Test
    fun `track number ordering puts missing track numbers last with title tiebreak`() {
        val songs = listOf(
            testSong(id = 1L, title = "B Song", trackNumber = 0),
            testSong(id = 2L, title = "Z Song", trackNumber = 2),
            testSong(id = 3L, title = "A Song", trackNumber = 2),
            testSong(id = 4L, title = "A Song", trackNumber = 0)
        )

        assertEquals(listOf(3L, 2L, 4L, 1L), orderSongsByTrackNumber(songs).map { it.id })
    }

    private fun testSong(id: Long, title: String, trackNumber: Int = 0) = Song(
        id = id,
        title = title,
        artist = "Artist",
        album = "Album",
        filePath = "/music/$id.mp3",
        duration = 1000L,
        trackNumber = trackNumber,
        mimeType = "audio/mpeg",
        size = 1024L,
        dateAdded = 1L
    )
}
