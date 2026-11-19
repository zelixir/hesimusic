package com.zjr.hesimusic.loop.audio.source

import android.app.Application
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 00021 回归：循环 tag 必须**逼 ExoPlayer 重建 MediaSource**。
 *
 * 真机根因：media3 的 Player.replaceMediaItem 对同 uri 的 item 走
 * `canUpdateMediaItem == true` 的捷径——只更新时间线 item、保留旧 MediaSource。
 * ProgressiveMediaSource 的 canUpdateMediaItem 只比较 uri/imageDurationMs/customCacheKey，
 * 不看 tag，于是 tag 到不了 LoopAwareMediaSourceFactory：进度从 A 走、声音还是原曲。
 */
@RunWith(RobolectricTestRunner::class)
// SDK 36 的 android-all 需要 Java 21；本机 JDK 17，SDK 35 足够（同 RobolectricLoopPipelineTest）。
@Config(sdk = [35], application = Application::class)
class LoopSourceMediaItemTest {

    private val tag = LoopSourceItemTag(
        songId = 7L,
        loopStartUs = 20_000_000L,
        loopEndUs = 35_000_000L,
        filePath = "/music/a.mp3",
    )

    private val original: MediaItem = MediaItem.Builder()
        .setMediaId("7")
        .setUri("file:///music/a.mp3")
        .build()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val source: MediaSource =
        DefaultMediaSourceFactory(context).createMediaSource(original)

    @Test
    fun `tagged item forces a new media source`() {
        val tagged = LoopSourceMediaItem.tagged(original, tag)
        assertEquals(tag, tagged.localConfiguration?.tag)
        assertEquals(LoopSourceMediaItem.sourceCacheKey(tag), tagged.localConfiguration?.customCacheKey)
        // 媒体源只凭 item 判断能否原地复用：同 uri + 同 cacheKey 会复用（bug），
        // 我们的循环 item 必须让它返回 false。
        assertTrue("original item may be updated in place", source.canUpdateMediaItem(original))
        assertFalse(
            "loop tag must force createMediaSource",
            source.canUpdateMediaItem(tagged),
        )
    }

    @Test
    fun `restored item forces a new media source too`() {
        val tagged = LoopSourceMediaItem.tagged(original, tag)
        val restored = LoopSourceMediaItem.restored(tagged)
        assertNull(restored.localConfiguration?.tag)
        assertNull(restored.localConfiguration?.customCacheKey)
        val loopSource: MediaSource = DefaultMediaSourceFactory(context).createMediaSource(tagged)
        assertFalse(
            "exiting the loop must rebuild the source too",
            loopSource.canUpdateMediaItem(restored),
        )
    }

    @Test
    fun `cache key is stable per window and changes with the window`() {
        val key = LoopSourceMediaItem.sourceCacheKey(tag)
        assertEquals(key, LoopSourceMediaItem.sourceCacheKey(tag.copy()))
        assertFalse(key == LoopSourceMediaItem.sourceCacheKey(tag.copy(loopEndUs = 36_000_000L)))
        assertFalse(key == LoopSourceMediaItem.sourceCacheKey(tag.copy(loopStartUs = 21_000_000L)))
        assertEquals("hesi-loop:7:20000000:35000000", key)
    }
}
