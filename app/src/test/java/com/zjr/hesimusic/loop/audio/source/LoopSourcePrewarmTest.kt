package com.zjr.hesimusic.loop.audio.source

import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.source.MediaSource
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * 00029：入口预热源**精确命中**规则（mediaId + tag 全等）。
 *
 * 预热源一旦被错配到别的 item/窗口，就是"声音从别的歌/别的 A-B 出来"的严重回归；
 * 这里把 key 匹配/取走/取消的边界锁死。
 */
class LoopSourcePrewarmTest {

    @Before
    fun reset() {
        LoopSourcePrewarm.resetForTest()
    }

    private fun item(mediaId: String): MediaItem =
        MediaItem.Builder().setMediaId(mediaId).build()

    private fun tag(aUs: Long = 1_000_000L, bUs: Long = 9_000_000L): LoopSourceItemTag =
        LoopSourceItemTag(
            songId = 42L,
            loopStartUs = aUs,
            loopEndUs = bUs,
            filePath = "/tmp/song.mp3",
            fullDurationUs = 60_000_000L,
        )

    /** 只用于占位；put/take/remove 不会调用 MediaSource 的任何方法。 */
    private fun fakeSource(): MediaSource = Proxy.newProxyInstance(
        MediaSource::class.java.classLoader,
        arrayOf(MediaSource::class.java),
    ) { _, method, _ -> error("MediaSource." + method.name + " must not be called by the registry") }
        as MediaSource

    @Test
    fun `精确命中才取走并清空槽`() {
        val source = fakeSource()
        val mediaItem = item("42")
        val key = LoopSourcePrewarm.keyOf(mediaItem, tag())
        LoopSourcePrewarm.put(key, source)
        assertSame(source, LoopSourcePrewarm.take(mediaItem, tag()))
        assertNull("取走后槽必须为空，不能重复交给第二个 item", LoopSourcePrewarm.take(mediaItem, tag()))
    }

    @Test
    fun `mediaId 不同不命中且保留原槽`() {
        val source = fakeSource()
        val mediaItem = item("queue:7:42")
        LoopSourcePrewarm.put(LoopSourcePrewarm.keyOf(mediaItem, tag()), source)
        assertNull(
            "队列副本的 mediaId 与预热项不同：绝不能把源挂到别的 item 上",
            LoopSourcePrewarm.take(item("42"), tag()),
        )
        assertSame(source, LoopSourcePrewarm.take(mediaItem, tag()))
    }

    @Test
    fun `A-B 不同不命中`() {
        val source = fakeSource()
        val mediaItem = item("42")
        LoopSourcePrewarm.put(LoopSourcePrewarm.keyOf(mediaItem, tag(1_000_000L, 9_000_000L)), source)
        assertNull(
            "换窗口必须新建源（窗口不同声音不同）",
            LoopSourcePrewarm.take(mediaItem, tag(2_000_000L, 8_000_000L)),
        )
        assertSame(source, LoopSourcePrewarm.take(mediaItem, tag(1_000_000L, 9_000_000L)))
    }

    @Test
    fun `remove 命中才返回`() {
        val source = fakeSource()
        val mediaItem = item("42")
        val key = LoopSourcePrewarm.keyOf(mediaItem, tag())
        LoopSourcePrewarm.put(key, source)
        assertNull("别的歌不能取消本次预热", LoopSourcePrewarm.remove(key.copy(songId = 43L)))
        assertSame(source, LoopSourcePrewarm.remove(key))
        assertNull("取消后槽为空", LoopSourcePrewarm.remove(key))
    }

    @Test
    fun `重复 put 返回被替换的旧源`() {
        val first = fakeSource()
        val second = fakeSource()
        val mediaItem = item("42")
        val key = LoopSourcePrewarm.keyOf(mediaItem, tag())
        assertNull(LoopSourcePrewarm.put(key, first))
        assertSame(first, LoopSourcePrewarm.put(key, second))
        assertSame(second, LoopSourcePrewarm.take(mediaItem, tag()))
    }

    @Test
    fun `key 对窗口与路径敏感`() {
        val base = LoopSourcePrewarm.keyOf(item("42"), tag())
        assertTrue(base != base.copy(loopStartUs = 2_000_000L))
        assertTrue(base != base.copy(loopEndUs = 8_000_000L))
        assertTrue(base != base.copy(filePath = "/tmp/other.mp3"))
        assertTrue(base != base.copy(fullDurationUs = 61_000_000L))
    }
}
