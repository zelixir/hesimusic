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
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * 00029 契约：prepare 只在 rebuild 后仍 IDLE 时调用。
 *
 * 事实（Robolectric + 真实 ExoPlayer）：IDLE 上直接 play() **不会**自动 prepare，
 * 所以不能无条件跳过 prepare；但 setMediaItems 后已经 BUFFERING/READY 时，
 * 再 prepare() 会 flush 已配置的 AudioSink，造成二次 configure / 双静音。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LoopSourceReviveContractTest {

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
    fun prepareOnIdle_startsTheSource_playAloneDoesNot() {
        val player = newPlayer()
        player.setMediaItems(listOf(item("11")))
        player.seekTo(0, 0L)
        player.stop()
        ShadowLooper.idleMainLooper()
        assertEquals("stop 后应为 IDLE", Player.STATE_IDLE, player.playbackState)

        // 契约 1：只 play() 不会 prepare（IDLE 保持）。
        player.play()
        ShadowLooper.idleMainLooper()
        assertEquals("play() 单独不会拉起 IDLE", Player.STATE_IDLE, player.playbackState)

        // 契约 2：prepare() 才会真正建源。
        player.prepare()
        ShadowLooper.idleMainLooper()
        assertNotEquals("prepare() 必须把源拉起", Player.STATE_IDLE, player.playbackState)
    }
}
