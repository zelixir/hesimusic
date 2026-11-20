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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * 真实 ExoPlayer 的三条契约(A-077 / A-085 / A-016),用 Robolectric 跑 JVM。
 *
 * - A-077:构造期不派发初始 IDLE,初始化必须主动读 playbackState;
 * - A-085:repeat=ALL 时最后一项 hasNextMediaItem() 仍为 true(播放器自己绕回);
 * - A-016:setMediaItems 每次都会派发 PLAYLIST_CHANGED。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ExoPlayerContractTest {

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
    fun `创建 ExoPlayer 不会回调初始 IDLE`() {
        val player = newPlayer()
        val states = ArrayList<Int>()
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                states += playbackState
            }
        })
        ShadowLooper.idleMainLooper()

        assertEquals("主动读到的初始态", Player.STATE_IDLE, player.playbackState)
        assertTrue("构造期不能派发任何 playbackState 回调: " + states, states.isEmpty())
    }

    @Test
    fun `repeat ALL 时最后一项仍有下一项`() {
        val player = newPlayer()
        player.setMediaItems(listOf(item("a"), item("b"), item("c")))
        player.repeatMode = Player.REPEAT_MODE_ALL
        player.seekTo(2, 0L)
        ShadowLooper.idleMainLooper()

        assertEquals(2, player.currentMediaItemIndex)
        assertTrue("repeat=ALL 由播放器自己绕回", player.hasNextMediaItem())
        assertTrue(player.hasPreviousMediaItem())
        assertFalse(
            "手写绕回分支在 ALL 下必须不可达",
            MusicNavigationPolicy.shouldWrapToStart(
                hasNext = player.hasNextMediaItem(),
                repeatMode = player.repeatMode,
                mediaItemCount = player.mediaItemCount,
            ),
        )

        player.repeatMode = Player.REPEAT_MODE_OFF
        assertFalse("repeat=OFF 的最后一项没有下一项", player.hasNextMediaItem())
        assertTrue(
            MusicNavigationPolicy.shouldWrapToStart(
                hasNext = player.hasNextMediaItem(),
                repeatMode = player.repeatMode,
                mediaItemCount = player.mediaItemCount,
            ),
        )
    }

    @Test
    fun `重设同一个队列仍会派发 PLAYLIST_CHANGED transition`() {
        val player = newPlayer()
        val reasons = ArrayList<Int>()
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                reasons += reason
            }
        })

        val items = listOf(item("a"), item("b"))
        player.setMediaItems(items)
        ShadowLooper.idleMainLooper()
        assertEquals("setMediaItems 首次应产生 PLAYLIST_CHANGED", listOf(Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED), reasons)

        player.setMediaItems(items)
        ShadowLooper.idleMainLooper()
        assertEquals(
            "重设同一个 List 也必须再次派发 PLAYLIST_CHANGED(A-016)",
            2,
            reasons.size,
        )
        assertEquals(Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED, reasons.last())
    }
}
