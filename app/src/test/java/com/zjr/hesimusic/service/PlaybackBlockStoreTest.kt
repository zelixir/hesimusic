package com.zjr.hesimusic.service

import androidx.media3.common.PlaybackException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * issue 00030：播放阻塞事件的去重 / 一次性消费语义（纯 JVM）。
 *
 * 验收口径：
 * - 同一错误不重复弹（未消费的同类事件 + 3s 时间窗）；
 * - 消费一次即清除（旋转/重进页面不再弹）；
 * - 消费后再失败会重新发布（不能永久静音）。
 */
class PlaybackBlockStoreTest {

    private val noPermission = PlaybackException.ERROR_CODE_IO_NO_PERMISSION
    private val fileNotFound = PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND

    @Before
    fun setUp() {
        PlaybackBlockStore.resetForTest()
    }

    @After
    fun tearDown() {
        PlaybackBlockStore.resetForTest()
    }

    private fun block(
        songId: Long? = 7L,
        errorCode: Int = noPermission,
        atMs: Long = 1_000L,
        title: String? = "song-7",
    ): PlaybackBlock = PlaybackBlock(
        songId = songId,
        errorCode = errorCode,
        title = title,
        publishedAtMs = atMs,
    )

    @Test
    fun publishExposesTheEventUntilConsumed() {
        val b = block()
        assertTrue("首次发布必须成功", PlaybackBlockStore.publish(b))
        assertSame("未消费前 peek 拿到同一条事件", b, PlaybackBlockStore.peek())
        assertEquals(b, PlaybackBlockStore.blockFlow.value)
    }

    @Test
    fun sameUnconsumedErrorIsDroppedEvenLongAfterTheWindow() {
        val first = block(atMs = 1_000L)
        assertTrue(PlaybackBlockStore.publish(first))
        assertFalse(
            "已有未消费的同 key 事件：对话框已展示/即将展示，不得再发布",
            PlaybackBlockStore.publish(block(atMs = 60_000L)),
        )
        assertEquals("store 里仍是第一条", first, PlaybackBlockStore.peek())
    }

    @Test
    fun duplicateWithinThreeSecondsIsThrottled() {
        assertTrue(PlaybackBlockStore.publish(block(atMs = 1_000L)))
        PlaybackBlockStore.consume(PlaybackBlockStore.peek()!!)
        assertFalse(
            "消费后 1s 又报同一错误：属于同一次失败的连发，3s 窗内丢弃",
            PlaybackBlockStore.publish(block(atMs = 2_000L)),
        )
        assertNull(PlaybackBlockStore.peek())
    }

    @Test
    fun sameErrorAfterTheWindowIsPublishedAgain() {
        assertTrue(PlaybackBlockStore.publish(block(atMs = 1_000L)))
        PlaybackBlockStore.consume(PlaybackBlockStore.peek()!!)
        // 3s 窗口边界：恰好 3s 允许再次发布。
        assertTrue(
            "从设置页返回仍未授权、用户再点播放（远超 3s）：必须再弹，不能永久静音",
            PlaybackBlockStore.publish(block(atMs = 1_000L + PlaybackBlockStore.DEDUP_WINDOW_MS)),
        )
        assertEquals(4_000L, PlaybackBlockStore.peek()!!.publishedAtMs)
    }

    @Test
    fun differentSongsOrCodesAreDifferentEvents() {
        assertTrue(PlaybackBlockStore.publish(block(songId = 7L, atMs = 1_000L)))
        assertTrue("换一首歌：新事件", PlaybackBlockStore.publish(block(songId = 8L, atMs = 1_100L)))
        assertTrue("同一首歌换错误码：新事件", PlaybackBlockStore.publish(block(songId = 8L, errorCode = fileNotFound, atMs = 1_200L)))
        assertEquals(fileNotFound, PlaybackBlockStore.peek()!!.errorCode)
    }

    @Test
    fun dedupKeyIgnoresTitleAndTimestamp() {
        assertEquals(block(atMs = 1L).dedupKey, block(atMs = 999L, title = "other").dedupKey)
        assertEquals("7:$noPermission", block().dedupKey)
        assertEquals("null 的歌记成 -1", "-1:$noPermission", block(songId = null).dedupKey)
    }

    @Test
    fun consumeClearsTheEventExactlyOnce() {
        val b = block()
        PlaybackBlockStore.publish(b)
        assertEquals("第一次消费返回事件", b, PlaybackBlockStore.consume(b))
        assertNull("store 已清空", PlaybackBlockStore.peek())
        assertNull("重复消费（旋转后重新 collect）不返回任何东西", PlaybackBlockStore.consume(b))
    }

    @Test
    fun consumeOfAStaleEventDoesNotClearANewerOne() {
        val first = block(songId = 7L, atMs = 1_000L)
        val second = block(songId = 8L, atMs = 1_100L)
        PlaybackBlockStore.publish(first)
        PlaybackBlockStore.publish(second)
        assertNull("旧事件已被新事件替换，不能再消费到", PlaybackBlockStore.consume(first))
        assertSame("新事件必须仍在", second, PlaybackBlockStore.peek())
    }

    @Test
    fun clearForgetsTheDedupWindowToo() {
        assertTrue(PlaybackBlockStore.publish(block(atMs = 1_000L)))
        PlaybackBlockStore.clear()
        assertNull(PlaybackBlockStore.peek())
        assertTrue("清空后同 key 立刻可再次发布（Service 重建场景）", PlaybackBlockStore.publish(block(atMs = 1_100L)))
    }
}
