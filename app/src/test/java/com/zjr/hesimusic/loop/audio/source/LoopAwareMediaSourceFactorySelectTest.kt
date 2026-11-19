package com.zjr.hesimusic.loop.audio.source

import android.app.Application
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.MediaSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

/**
 * 00021 不变量取证：工厂按 mediaId 记录每次 select=loop/delegate。
 *
 * 用 Robolectric 只是为了 android.net.Uri.parse（MediaItem 需要真实 uri 才建 LocalConfiguration）；
 * 判定本身是纯逻辑。
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LoopAwareMediaSourceFactorySelectTest {

    @After
    fun tearDown() {
        LoopAwareMediaSourceFactory.resetSelectsForTest()
    }

    private fun fakeSource(): MediaSource = Proxy.newProxyInstance(
        MediaSource::class.java.classLoader,
        arrayOf(MediaSource::class.java),
    ) { _, _, _ -> null } as MediaSource

    private fun delegateFactory(source: MediaSource): MediaSource.Factory = Proxy.newProxyInstance(
        MediaSource.Factory::class.java.classLoader,
        arrayOf(MediaSource.Factory::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getSupportedTypes" -> IntArray(0)
            "createMediaSource" -> source
            else -> when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                else -> null
            }
        }
    } as MediaSource.Factory

    @Test
    fun selectHistoryRecordsLoopAndDelegatePerMediaId() {
        LoopAwareMediaSourceFactory.resetSelectsForTest()
        val fake = fakeSource()
        val factory = LoopAwareMediaSourceFactory(
            delegate = delegateFactory(fake),
            enabled = true,
            sourceFactory = { _, _ -> fake },
        )
        val loopItem = MediaItem.Builder()
            .setUri(Uri.parse("file:///tmp/a.mp3"))
            .setMediaId("42")
            .setTag(LoopSourceItemTag(42L, 20_000_000L, 35_000_000L, "/tmp/a.mp3"))
            .build()
        val plainItem = MediaItem.Builder()
            .setUri(Uri.parse("file:///tmp/b.mp3"))
            .setMediaId("43")
            .build()

        factory.createMediaSource(loopItem)
        factory.createMediaSource(plainItem)

        assertEquals("delegate", LoopAwareMediaSourceFactory.lastSelect()?.kind)
        assertEquals("loop", LoopAwareMediaSourceFactory.lastSelectFor("42")?.kind)
        assertEquals(42L, LoopAwareMediaSourceFactory.lastSelectFor("42")?.songId)
        assertEquals(20_000_000L, LoopAwareMediaSourceFactory.lastSelectFor("42")?.loopStartUs)
        assertEquals("delegate", LoopAwareMediaSourceFactory.lastSelectFor("43")?.kind)
        assertNull(LoopAwareMediaSourceFactory.lastSelectFor("missing"))
    }

    @Test
    fun disabledFactoryAlwaysSelectsDelegate() {
        LoopAwareMediaSourceFactory.resetSelectsForTest()
        val fake = fakeSource()
        val factory = LoopAwareMediaSourceFactory(
            delegate = delegateFactory(fake),
            enabled = false,
            sourceFactory = { _, _ -> fake },
        )
        val loopItem = MediaItem.Builder()
            .setUri(Uri.parse("file:///tmp/a.mp3"))
            .setMediaId("42")
            .setTag(LoopSourceItemTag(42L, 0L, 1_000_000L, "/tmp/a.mp3"))
            .build()

        factory.createMediaSource(loopItem)

        // enabled=false 时 tag 被忽略：真实出声的是普通源 → 正是 00021 形态。
        assertEquals("delegate", LoopAwareMediaSourceFactory.lastSelectFor("42")?.kind)
    }
}
