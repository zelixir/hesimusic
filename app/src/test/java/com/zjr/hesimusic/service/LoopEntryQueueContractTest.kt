package com.zjr.hesimusic.service

import android.app.Application
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.test.utils.FakeClock
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.test.core.app.ApplicationProvider
import com.zjr.hesimusic.loop.LoopIntent
import com.zjr.hesimusic.loop.LoopRuntime
import com.zjr.hesimusic.loop.audio.LoopAudioController
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * #体验审计2：进循环 setMediaItem 不再把队列压成一首（Robolectric + 真实 ExoPlayer）。
 *
 * - 目标歌已在队列：只移 index，mediaItemCount 不变；
 * - 目标歌不在队列：只把当前项换成请求项，长度/index 不变；
 * - 退场后 next 仍在原队列里切到下一首。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LoopEntryQueueContractTest {

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

    private fun session(
        player: Player,
        intent: LoopIntent,
        onExitLoopForNavigation: (() -> Unit)? = null,
    ): LoopSessionPlayer =
        LoopSessionPlayer(
            player = player,
            runtime = LoopRuntime(),
            audio = LoopAudioController(),
            loopIntent = intent,
            onExitLoopForNavigation = onExitLoopForNavigation,
        )

    private fun queueOf(player: Player): List<String> =
        (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }

    @Test
    fun loopEntry_withSongAlreadyInQueue_keepsQueueAndMovesIndex() {
        val player = newPlayer()
        player.setMediaItems(listOf(item("11"), item("22"), item("33")))
        player.seekTo(0, 0L)
        ShadowLooper.idleMainLooper()
        val intent = LoopIntent()
        intent.arm(22L, Player.REPEAT_MODE_OFF, false)
        val session = session(player, intent)

        session.setMediaItem(item("22"))
        ShadowLooper.idleMainLooper()

        assertEquals("队列不能被压成一首", 3, player.mediaItemCount)
        assertEquals(1, player.currentMediaItemIndex)
        assertEquals("22", player.currentMediaItem?.mediaId)
        assertEquals(listOf("11", "22", "33"), queueOf(player))
    }

    @Test
    fun loopEntry_withSongOutsideQueue_replacesOnlyCurrentItem() {
        val player = newPlayer()
        player.setMediaItems(listOf(item("11"), item("22"), item("33")))
        player.seekTo(1, 0L)
        ShadowLooper.idleMainLooper()
        val intent = LoopIntent()
        intent.arm(99L, Player.REPEAT_MODE_OFF, false)
        val session = session(player, intent)

        session.setMediaItem(item("99"))
        ShadowLooper.idleMainLooper()

        assertEquals("队列长度不变", 3, player.mediaItemCount)
        assertEquals("index 不变", 1, player.currentMediaItemIndex)
        assertEquals("当前项换成请求项", "99", player.currentMediaItem?.mediaId)
        assertEquals("前后歌保留", listOf("11", "99", "33"), queueOf(player))
    }

    @Test
    fun afterLoopExit_nextStillMovesWithinThePreservedQueue() {
        val player = newPlayer()
        player.setMediaItems(listOf(item("11"), item("22"), item("33")))
        player.seekTo(0, 0L)
        ShadowLooper.idleMainLooper()
        val intent = LoopIntent()
        intent.arm(22L, Player.REPEAT_MODE_OFF, false)
        var exits = 0
        val session = session(player, intent) {
            exits += 1
            // 与 LoopPlaybackCoordinator.exitForNavigation 等价的最小动作。
            intent.clear()
            player.repeatMode = Player.REPEAT_MODE_OFF
        }

        session.setMediaItem(item("22"))
        ShadowLooper.idleMainLooper()
        assertEquals(1, player.currentMediaItemIndex)

        player.repeatMode = Player.REPEAT_MODE_ONE
        session.seekToNext()
        ShadowLooper.idleMainLooper()

        assertEquals("next 前必须强制退场", 1, exits)
        assertEquals(3, player.mediaItemCount)
        assertEquals(2, player.currentMediaItemIndex)
        assertEquals("33", player.currentMediaItem?.mediaId)
        assertEquals(Player.REPEAT_MODE_OFF, player.repeatMode)
    }

    @Test
    fun singletonSetMediaItems_isInterceptedTheSameWay() {
        val player = newPlayer()
        player.setMediaItems(listOf(item("11"), item("22"), item("33")))
        player.seekTo(0, 0L)
        ShadowLooper.idleMainLooper()
        val intent = LoopIntent()
        intent.arm(22L, Player.REPEAT_MODE_OFF, false)
        val session = session(player, intent)

        session.setMediaItems(listOf(item("22")))
        ShadowLooper.idleMainLooper()

        assertEquals(3, player.mediaItemCount)
        assertEquals(1, player.currentMediaItemIndex)
        assertEquals("22", player.currentMediaItem?.mediaId)
    }

    @Test
    fun armedButSingleItemQueue_fallsBackToNativeSetMediaItem() {
        val player = newPlayer()
        player.setMediaItems(listOf(item("11")))
        player.seekTo(0, 0L)
        ShadowLooper.idleMainLooper()
        val intent = LoopIntent()
        intent.arm(99L, Player.REPEAT_MODE_OFF, false)
        val session = session(player, intent)

        session.setMediaItem(item("99"))
        ShadowLooper.idleMainLooper()

        assertEquals("单曲队列无可保留", 1, player.mediaItemCount)
        assertEquals("99", player.currentMediaItem?.mediaId)
    }

    @Test
    fun withoutLoopIntent_setMediaItemKeepsNativeSingleItemSemantics() {
        val player = newPlayer()
        player.setMediaItems(listOf(item("11"), item("22"), item("33")))
        player.seekTo(0, 0L)
        ShadowLooper.idleMainLooper()
        val session = session(player, LoopIntent())

        session.setMediaItem(item("99"))
        ShadowLooper.idleMainLooper()

        assertEquals("没有武装意图时保持原生 setMediaItem", 1, player.mediaItemCount)
        assertEquals("99", player.currentMediaItem?.mediaId)
        assertNull(player.currentMediaItem?.mediaMetadata?.title)
    }
}
