package com.zjr.hesimusic.loop.audio.source

import androidx.media3.common.MediaItem

/**
 * 循环 item 的 MediaItem 构造（纯函数，可 JVM 单测）。
 *
 * 背景（真机 00021 根因）：media3 的 `Player.replaceMediaItem` **不会**重建 MediaSource。
 * `ExoPlayerImpl.replaceMediaItems` 先问 `MediaSource.canUpdateMediaItem(newItem)`；
 * `ProgressiveMediaSource` 只比较 uri / imageDurationMs / customCacheKey 等字段
 * （**不看 tag**），同 uri 的 tag 替换会走"原地更新 item、保留旧 MediaSource"的捷径：
 * 时间线上的 item 有了 tag（UI/投影认为已进循环），但真正出声的仍是原来那个
 * 普通 MP3 源 → 进度从 A 走、声音从歌曲开头播（00021）。
 *
 * 修复：给循环 item 写一个与原始 item 不同的 [customCacheKey]，令
 * `canUpdateMediaItem` 返回 false，逼 ExoPlayer 走 `createMediaSources` 重建源，
 * 这样 tag 才会真正到达 [LoopAwareMediaSourceFactory]，由它构建循环 PCM 源。
 *
 * key 必须可区分且稳定：同一首歌不同 A/B（换窗口）也必须不同，否则换窗口时又会
 * 命中"原地更新"捷径。退出时恢复原 item（或显式清空 key），同样会重建普通源。
 */
object LoopSourceMediaItem {

    /**
     * 与源 MediaItem 不同、且只由窗口决定的缓存键。
     *
     * 只影响 media3 的 canUpdateMediaItem 判定；本应用不接缓存 DataSource，
     * 因此不会变成真的磁盘缓存键（见 DefaultMediaSourceFactory 的 cacheKey 用途）。
     */
    fun sourceCacheKey(tag: LoopSourceItemTag): String =
        "hesi-loop:" + tag.songId + ":" + tag.loopStartUs + ":" + tag.loopEndUs

    /** 给当前 item 打上循环 tag + 强制重建键（条目 index/queue 不变）。 */
    fun tagged(current: MediaItem, tag: LoopSourceItemTag): MediaItem =
        current.buildUpon()
            .setTag(tag)
            .setCustomCacheKey(sourceCacheKey(tag))
            .build()

    /** 退出循环：清 tag 与强制重建键（让 ExoPlayer 回到普通文件源）。 */
    fun restored(current: MediaItem): MediaItem =
        current.buildUpon()
            .setTag(null)
            .setCustomCacheKey(null)
            .build()
}
