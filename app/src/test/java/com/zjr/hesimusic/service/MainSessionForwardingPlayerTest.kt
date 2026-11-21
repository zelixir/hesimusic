package com.zjr.hesimusic.service

import android.app.Application
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.test.utils.FakeClock
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * D10 契约测试（Robolectric + 真实 ExoPlayer）：
 *
 * 1) 通知的 MediaItem 版 next/prev 与界面 seekToNext/seekToPrevious 行为一致
 *    （队尾绕回、>3s 回本曲开头）；原生 MediaItem 版不会绕回，这是之前通知/界面不一致的来源；
 * 2) 锁屏/通知 metadata 被装饰，但 App 内 UI 读的 currentMediaItem 仍是原始 artist。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MainSessionForwardingPlayerTest {

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

    private fun item(id: String, artist: String? = null): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle("Title-" + id)
            .apply { if (artist != null) setArtist(artist) }
            .build()
        return MediaItem.Builder()
            .setUri(Uri.parse("file:///test/" + id + ".mp3"))
            .setMediaId(id)
            .setMediaMetadata(metadata)
            .build()
    }

    @Test
    fun notification_next_at_queue_end_wraps_like_the_ui_button() {
        val native = newPlayer()
        native.setMediaItems(listOf(item("a"), item("b")))
        native.repeatMode = Player.REPEAT_MODE_OFF
        native.seekTo(1, 0L)
        ShadowLooper.idleMainLooper()
        native.seekToNextMediaItem()
        ShadowLooper.idleMainLooper()
        assertEquals("原生 MediaItem 版在队尾不会绕回", 1, native.currentMediaItemIndex)

        val player = newPlayer()
        player.setMediaItems(listOf(item("a"), item("b")))
        player.repeatMode = Player.REPEAT_MODE_OFF
        player.seekTo(1, 0L)
        ShadowLooper.idleMainLooper()
        val wrapper = MainSessionForwardingPlayer(player) { null }
        wrapper.seekToNextMediaItem()
        ShadowLooper.idleMainLooper()
        assertEquals("通知 next 必须和界面 seekToNext 一样绕回", 0, player.currentMediaItemIndex)
    }

    @Test
    fun notification_previous_long_position_restarts_current_item_like_the_ui_button() {
        val player = newPlayer()
        player.setMediaItems(listOf(item("a"), item("b"), item("c")))
        player.repeatMode = Player.REPEAT_MODE_OFF
        player.seekTo(1, 10_000L)
        ShadowLooper.idleMainLooper()

        val wrapper = MainSessionForwardingPlayer(player) { null }
        wrapper.seekToPreviousMediaItem()
        ShadowLooper.idleMainLooper()

        assertEquals(">3s：回本曲开头，而不是上一首（与界面一致）", 1, player.currentMediaItemIndex)
        assertEquals(0L, player.currentPosition)
    }

    @Test
    fun notification_previous_short_position_goes_to_previous_item() {
        val player = newPlayer()
        player.setMediaItems(listOf(item("a"), item("b"), item("c")))
        player.repeatMode = Player.REPEAT_MODE_OFF
        player.seekTo(1, 1_000L)
        ShadowLooper.idleMainLooper()

        val wrapper = MainSessionForwardingPlayer(player) { null }
        wrapper.seekToPreviousMediaItem()
        ShadowLooper.idleMainLooper()

        assertEquals(0, player.currentMediaItemIndex)
    }

    @Test
    fun notification_commands_are_advertised_when_queue_is_not_empty() {
        val player = newPlayer()
        player.setMediaItem(item("a"))
        val wrapper = MainSessionForwardingPlayer(player) { null }
        assertTrue(wrapper.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
        assertTrue(wrapper.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS))
    }

    @Test
    fun session_metadata_is_decorated_but_item_metadata_stays_raw() {
        val player = newPlayer()
        player.setMediaItem(item("7", artist = "Original"))
        ShadowLooper.idleMainLooper()

        val status = LoopMediaStatus(active = true, startMs = 10_000L, endMs = 50_000L, reasonCode = null)
        val wrapper = MainSessionForwardingPlayer(player) { status }

        assertEquals("Original · 无缝循环", wrapper.mediaMetadata.artist?.toString())
        assertEquals(
            "App 内 UI 读 currentMediaItem，必须仍是原始 artist",
            "Original",
            player.currentMediaItem?.mediaMetadata?.artist?.toString(),
        )
        assertEquals(
            "原始 artist 仍可从 metadata extras 取到",
            "Original",
            wrapper.mediaMetadata.extras?.getString(LoopMediaMetadata.EXTRA_RAW_ARTIST),
        )
    }

    @Test
    fun plain_playback_keeps_raw_metadata() {
        val player = newPlayer()
        player.setMediaItem(item("7", artist = "Original"))
        ShadowLooper.idleMainLooper()

        val wrapper = MainSessionForwardingPlayer(player) { null }
        assertEquals("Original", wrapper.mediaMetadata.artist?.toString())
    }
}
