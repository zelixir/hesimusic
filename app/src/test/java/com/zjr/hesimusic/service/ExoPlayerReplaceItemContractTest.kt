package com.zjr.hesimusic.service

import android.app.Application
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.test.utils.FakeClock
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * A-086:当前 item 的 MediaSource 必须重建时,replaceMediaItem 会**丢掉当前项**。
 *
 * 场景 12 / issue 00024 的根因就在这条 ExoPlayer 契约上:
 * - 循环入口为了逼 ExoPlayer 重建 MediaSource,给 item 换了 customCacheKey;
 * - ExoPlayerImpl.replaceMediaItems 发现不能原地更新源,退化成 add + remove(current);
 * - remove 掉的是当前 period,resolveSubsequentPeriod 把"当前项被移除"理解为"续播后继":
 *   repeat=OFF → 下一首;repeat=ONE → getNextWindowIndex 原地返回同 index,
 *   找不到后继 period 后回退到**第一项**(index 0)。
 * 所以进循环就切歌、repeat 被恢复成 off、intent 被 transition 清掉。
 *
 * 修复口径:换源必须走整表 setMediaItems(items, index, position)(落点由调用方指定),
 * 绝不对**当前项**用 replaceMediaItem。这里的断言锁住上面那条平台行为,防止有人再把
 * "replace 一下当前 item 就行"写回去。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ExoPlayerReplaceItemContractTest {

    private val players = ArrayList<ExoPlayer>()

    @After
    fun tearDown() {
        players.forEach { runCatching { it.release() } }
        players.clear()
    }

    private fun newPlayer(): ExoPlayer =
        TestExoPlayerBuilder(ApplicationProvider.getApplicationContext())
            .setClock(FakeClock(/* isAutoAdvancing= */ false))
            .build()
            .also { players += it }

    private fun item(id: String): MediaItem =
        MediaItem.Builder().setUri(Uri.parse("file:///test/" + id + ".mp3")).setMediaId(id).build()

    private fun queue(size: Int): List<MediaItem> = (0 until size).map { item(it.toString()) }

    /** 换 cacheKey = ProgressiveMediaSource.canUpdateMediaItem 返回 false = 必须重建源。 */
    private fun retagged(item: MediaItem): MediaItem =
        item.buildUpon().setCustomCacheKey("hesi-loop:" + item.mediaId).build()

    @Test
    fun repeatOneReplacingCurrentSourceFallsBackToFirstWindow() {
        val player = newPlayer()
        player.setMediaItems(queue(4), /* startIndex= */ 2, /* startPositionMs= */ 0L)
        player.repeatMode = Player.REPEAT_MODE_ONE
        ShadowLooper.idleMainLooper()
        assertEquals(2, player.currentMediaItemIndex)

        val current = player.currentMediaItem!!
        player.replaceMediaItem(2, retagged(current))
        ShadowLooper.idleMainLooper()

        assertEquals(
            "A-086:repeat=ONE 时替换当前项的源会回退到第一项(不是 2)",
            0,
            player.currentMediaItemIndex,
        )
        assertEquals("回退后当前 item 就是第 0 首", "0", player.currentMediaItem?.mediaId)
    }

    @Test
    fun repeatOffReplacingCurrentSourceStaysOnCurrentItem() {
        val player = newPlayer()
        player.setMediaItems(queue(4), /* startIndex= */ 2, /* startPositionMs= */ 0L)
        player.repeatMode = Player.REPEAT_MODE_OFF
        ShadowLooper.idleMainLooper()
        assertEquals(2, player.currentMediaItemIndex)

        val current = player.currentMediaItem!!
        player.replaceMediaItem(2, retagged(current))
        ShadowLooper.idleMainLooper()

        // OFF 下 add+remove 临时插进来的新源正好被当成"后继 period"解析回来,
        // 所以 replace 看起来没事 —— bug 只在 repeat=ONE 时暴露(而循环入口恰好是 ONE)。
        assertEquals("A-086:repeat=OFF 时留在当前 index", 2, player.currentMediaItemIndex)
        assertEquals("A-086:repeat=OFF 时当前 item 不变", "2", player.currentMediaItem?.mediaId)
    }

    @Test
    fun fullSetMediaItemsRebuildKeepsRequestedIndex() {
        val player = newPlayer()
        val items = queue(4)
        player.setMediaItems(items, /* startIndex= */ 2, /* startPositionMs= */ 0L)
        player.repeatMode = Player.REPEAT_MODE_ONE
        ShadowLooper.idleMainLooper()

        val rebuilt = items.toMutableList().apply { this[2] = retagged(items[2]) }
        player.setMediaItems(rebuilt, /* startIndex= */ 2, /* startPositionMs= */ 1234L)
        player.prepare()
        ShadowLooper.idleMainLooper()

        assertEquals("换源后必须还在请求的 index", 2, player.currentMediaItemIndex)
        assertEquals("落点由 startPositionMs 决定", 1234L, player.currentPosition)
        assertEquals("队列长度不变", 4, player.mediaItemCount)
    }
}
