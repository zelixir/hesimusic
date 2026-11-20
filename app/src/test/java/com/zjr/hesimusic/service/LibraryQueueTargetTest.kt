package com.zjr.hesimusic.service

import android.app.Application
import com.zjr.hesimusic.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 队列 index 计算（场景 12 / issue 00024）。
 *
 * 纪律：调用方拿到的 index 必须就是同一份 items 里请求歌的位置，
 * 并且 items[index].mediaId == songId —— 绝不允许"另算一份 index"。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LibraryQueueTargetTest {

    private fun song(id: Long, title: String = "song-" + id): Song = Song(
        id = id,
        title = title,
        artist = "artist",
        album = "album",
        filePath = "/storage/emulated/0/Music/" + id + ".mp3",
        duration = 180_000L,
        mimeType = "audio/mpeg",
        size = 1024L,
        dateAdded = 0L,
    )

    private fun library(size: Int): List<Song> = (0 until size).map { song(it + 1L) }

    @Test
    fun locateReturnsTheIndexInsideTheSameItemsList() {
        val songs = library(10)
        val located = LibraryQueueTarget.locate(songs, 7L)
        assertNotNull(located)
        assertEquals("7 号歌在 0-based 列表里是第 6 个", 6, located!!.index)
        assertEquals("队列长度取自同一份列表", 10, located.size)
        assertEquals("index 必须落在请求的歌上", "7", located.mediaId)
        assertEquals("每个 item 的 mediaId 都对应 song.id", "7", located.items[6].mediaId)
    }

    @Test
    fun locateWorksForLibraryOrderThatIsNotIdOrder() {
        // 真机库按 titleInitial/title 排序,id 完全乱序(#39944 在 1086 位)。
        val songs = listOf(song(41405), song(41317), song(39944), song(40904))
        val located = LibraryQueueTarget.locate(songs, 39944L)
        assertNotNull(located)
        assertEquals(2, located!!.index)
        assertEquals("39944", located.mediaId)
    }

    @Test
    fun locateReturnsRealIndexInALargeLibrary() {
        val big = (0 until 1714).map { song(1_000L + it) }
        assertNull("目标歌不在库里返回 null", LibraryQueueTarget.locate(big, 999L))
        val real = LibraryQueueTarget.locate(big, 2_086L)
        assertNotNull(real)
        assertEquals("index 必须等于列表位置", 1086, real!!.index)
        assertEquals("2086", real.mediaId)
    }

    @Test
    fun locateRejectsMissingSongAndInvalidInput() {
        assertNull("空库", LibraryQueueTarget.locate(emptyList(), 1L))
        assertNull("歌不在库里", LibraryQueueTarget.locate(library(3), 99L))
        assertNull("songId 必须为正", LibraryQueueTarget.locate(library(3), 0L))
        assertNull("负 songId", LibraryQueueTarget.locate(library(3), -5L))
    }

    @Test
    fun locatedIndexAlwaysPointsAtTheRequestedSong() {
        val songs = library(50)
        for (target in listOf(1L, 17L, 50L)) {
            val located = LibraryQueueTarget.locate(songs, target)!!
            assertEquals(
                "#" + target + " 的 index 必须指向自己",
                target.toString(),
                located.items[located.index].mediaId,
            )
        }
    }
}
