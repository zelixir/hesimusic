package com.zjr.hesimusic.loop.audio.source

import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.source.MediaSource

/**
 * 00029：入口**预热好的环带源**单槽。
 *
 * 用户打开长按菜单 / 结果页 Ready 时，[com.zjr.hesimusic.service.LoopEntryPrewarmer]
 * 在后台建好环带源并解前奏首块；用户点确认时，
 * [LoopAwareMediaSourceFactory.createMediaSource] 按**完全相同的 tag + mediaId** 取走它，
 * 省掉入口窗口里的建源 + 174~220ms 首块解码。
 *
 * 语义约束：
 * - 只按 Key 精确命中（mediaId/songId/A/B/file/fullDuration 全等）；不命中就正常新建，
 *   绝不把别的歌/别的窗口的源挂上去；
 * - 单槽：新的预热替换旧的（旧的由调用方 release）；入口取走后槽为空；
 * - 本对象只存引用，不做释放（释放由 [LoopEntryPrewarmer] 统一负责）。
 */
internal object LoopSourcePrewarm {

    data class Key(
        val mediaId: String,
        val songId: Long,
        val loopStartUs: Long,
        val loopEndUs: Long,
        val filePath: String,
        val fullDurationUs: Long,
    )

    private val lock = Any()
    private var slot: Pair<Key, MediaSource>? = null

    fun keyOf(mediaItem: MediaItem, tag: LoopSourceItemTag): Key = Key(
        mediaId = mediaItem.mediaId,
        songId = tag.songId,
        loopStartUs = tag.loopStartUs,
        loopEndUs = tag.loopEndUs,
        filePath = tag.filePath,
        fullDurationUs = tag.fullDurationUs,
    )

    /** 放入预热源；返回被替换的旧源（调用方负责 release）。 */
    fun put(key: Key, source: MediaSource): MediaSource? = synchronized(lock) {
        val displaced = slot?.second
        slot = key to source
        displaced
    }

    /** 入口工厂取源：精确命中才返回并清槽。 */
    fun take(mediaItem: MediaItem, tag: LoopSourceItemTag): MediaSource? = synchronized(lock) {
        val current = slot ?: return null
        if (current.first != keyOf(mediaItem, tag)) return null
        slot = null
        current.second
    }

    /** 取消/过期：命中才返回并清槽。 */
    fun remove(key: Key): MediaSource? = synchronized(lock) {
        if (slot?.first != key) return null
        val source = slot!!.second
        slot = null
        source
    }

    fun isSlotFor(key: Key): Boolean = synchronized(lock) { slot?.first == key }

    /** 仅供单测隔离。 */
    fun resetForTest() {
        synchronized(lock) { slot = null }
    }
}
