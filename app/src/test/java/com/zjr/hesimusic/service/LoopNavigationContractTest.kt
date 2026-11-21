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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * #106 的 ExoPlayer 契约测试（Robolectric + 真实 ExoPlayer）。
 *
 * 先钉住事实，避免再按错误直觉修：
 * - repeat=ONE **不会**让 seekToNext/Previous 原地返回：导航口径把 ONE 降级成 OFF，
 *   多曲队列原生就能换到下一项；
 * - 单曲队列确实没有下一项（hasNextMediaItem=false），原生只剩"绕回当前项"；
 *   此时循环武装如果没有先退场，就会一直停在旧 A/B —— 这正是场景 106 的观测
 *   （它的 /player/play 只建了 1 首队列，再 restart=false 进入循环）。
 * - 显式 seekTo(target) 在 repeat=ONE 下也能换项（LoopSessionPlayer 的退场后备用）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LoopNavigationContractTest {

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

    @Test
    fun repeatOne_nativeSeekToNextMovesToNextItem_whenQueueHasNext() {
        val player = newPlayer()
        player.setMediaItems(listOf(item("a"), item("b"), item("c")))
        player.repeatMode = Player.REPEAT_MODE_ONE
        player.seekTo(1, 0L)
        ShadowLooper.idleMainLooper()
        assertEquals(1, player.currentMediaItemIndex)

        player.seekToNext()
        ShadowLooper.idleMainLooper()
        assertEquals(
            "repeat=ONE 的导航口径是 OFF：多曲队列原生就能换到下一项",
            2,
            player.currentMediaItemIndex,
        )
        assertEquals("c", player.currentMediaItem?.mediaId)
    }

    @Test
    fun repeatOne_nativeSeekToPreviousMovesToPreviousItem_whenQueueHasPrevious() {
        val player = newPlayer()
        player.setMediaItems(listOf(item("a"), item("b"), item("c")))
        player.repeatMode = Player.REPEAT_MODE_ONE
        player.seekTo(1, 0L)
        ShadowLooper.idleMainLooper()

        player.seekToPrevious()
        ShadowLooper.idleMainLooper()
        assertEquals(0, player.currentMediaItemIndex)
        assertEquals("a", player.currentMediaItem?.mediaId)
    }

    @Test
    fun repeatOne_singleItemQueue_hasNoNextItem_andNativeNextRestartsSameItem() {
        val player = newPlayer()
        player.setMediaItems(listOf(item("only")))
        player.repeatMode = Player.REPEAT_MODE_ONE
        player.seekTo(0, 0L)
        ShadowLooper.idleMainLooper()

        assertFalse("单曲队列没有下一项 —— 场景 106 的原地不动来自这里", player.hasNextMediaItem())
        player.seekToNext()
        ShadowLooper.idleMainLooper()
        assertEquals(
            "原生只能绕回当前项；不先退场就会一直停在旧 A/B 循环",
            0,
            player.currentMediaItemIndex,
        )
    }

    @Test
    fun explicitSeekTo_reallyChangesSongId_underRepeatOne() {
        val player = newPlayer()
        player.setMediaItems(listOf(item("100"), item("200"), item("300")))
        player.repeatMode = Player.REPEAT_MODE_ONE
        player.seekTo(0, 0L)
        ShadowLooper.idleMainLooper()

        player.seekTo(1, 0L)
        ShadowLooper.idleMainLooper()

        assertEquals("200", player.currentMediaItem?.mediaId)
        assertNotEquals("100", player.currentMediaItem?.mediaId)
        assertEquals(3, player.mediaItemCount)
    }
}
