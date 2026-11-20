package com.zjr.hesimusic.service

import androidx.media3.common.MediaItem
import com.zjr.hesimusic.data.mapper.toMediaItem
import com.zjr.hesimusic.data.model.Song

/**
 * 「全库队列 + 目标歌 index」的唯一定位口径（/confirm autoPlay 与 /loop/enter 共用）。
 *
 * 不变量（场景 12 / issue 00024）：
 * - [Located.index] 必须真的指向 [Located.items] 里请求的那首歌（`mediaId == songId`）；
 * - 调用方只能把这一对原样交给 `setMediaItems(items, index, position)`，
 *   不要再自己用另一个排序/list 另算一份 index（历史 bug 就是"index 与落点不一致"）。
 */
object LibraryQueueTarget {

    /** 队列 + 目标歌在队列里的位置。 */
    data class Located(val items: List<MediaItem>, val index: Int) {
        /** index 处 item 的 mediaId（应当等于请求的 songId 字符串）。 */
        val mediaId: String? get() = items.getOrNull(index)?.mediaId
        val size: Int get() = items.size
    }

    /**
     * 在全库（调用方传入的可见歌曲列表）里定位 [songId]。
     *
     * @return null = 歌不在库里 / 列表为空 / 定位结果与 mediaId 对不上（绝不返回错位的 index）
     */
    fun locate(songs: List<Song>, songId: Long): Located? {
        if (songId <= 0L || songs.isEmpty()) return null
        val index = songs.indexOfFirst { it.id == songId }
        if (index < 0) return null
        val items = songs.map { it.toMediaItem() }
        // 双保险：index 必须落在请求的歌上（toMediaItem 的 mediaId 就是 song.id）。
        if (items[index].mediaId != songId.toString()) return null
        return Located(items, index)
    }
}
