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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * #90 暂停中 next 的"绕回"分支：UI skipToNext / debug 回退都会在 seekToDefaultPosition
 * 之后紧跟一次 play()。会话层吞掉这一次，保证暂停态切歌后仍暂停、不出声；
 * 用户再按播放仍然立即生效（一次即消费，不会永久吞操作）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PausedWrapPlaySuppressionTest {

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
    fun pausedWrap_suppressesTheFollowingAutoPlay_andNextUserPlayWorks() {
        val player = newPlayer()
        player.setMediaItems(listOf(item("11"), item("22")))
        player.seekTo(0, 0L)
        player.repeatMode = Player.REPEAT_MODE_ONE
        ShadowLooper.idleMainLooper()
        val intent = LoopIntent()
        intent.arm(11L, Player.REPEAT_MODE_OFF, false)
        var exits = 0
        val session = LoopSessionPlayer(
            player = player,
            runtime = LoopRuntime(),
            audio = LoopAudioController(),
            loopIntent = intent,
            onExitLoopForNavigation = {
                exits += 1
                intent.clear()
                player.repeatMode = Player.REPEAT_MODE_OFF
            },
        )

        player.pause()
        ShadowLooper.idleMainLooper()
        assertFalse(player.playWhenReady)

        session.seekToDefaultPosition(0)
        ShadowLooper.idleMainLooper()
        assertEquals("必须先退场", 1, exits)
        assertFalse(player.playWhenReady)

        // UI/debug 的绕回分支紧随其后的 play()：必须被吞掉。
        session.play()
        ShadowLooper.idleMainLooper()
        assertFalse("暂停态切歌不能自动开播", player.playWhenReady)

        // 用户再次按播放：立即生效。
        session.play()
        ShadowLooper.idleMainLooper()
        assertTrue("用户播放不能被永久吞掉", player.playWhenReady)
    }

    @Test
    fun negativeControl_withoutLoopExit_playStillStarts() {
        val player = newPlayer()
        player.setMediaItems(listOf(item("11")))
        player.seekTo(0, 0L)
        ShadowLooper.idleMainLooper()
        val session = LoopSessionPlayer(
            player = player,
            runtime = LoopRuntime(),
            audio = LoopAudioController(),
            loopIntent = LoopIntent(),
        )
        player.pause()
        ShadowLooper.idleMainLooper()
        session.play()
        ShadowLooper.idleMainLooper()
        assertTrue(player.playWhenReady)
    }
}
