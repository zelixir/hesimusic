package com.zjr.hesimusic.ui.common

import androidx.media3.common.PlaybackException
import com.zjr.hesimusic.service.PlaybackBlock
import com.zjr.hesimusic.service.PlaybackBlockStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * issue 00030：UI 侧的三条行为判据（纯 JVM，不启动 Compose）。
 *
 * - 后台不弹也不消费（回前台再弹）；
 * - 一次消费（旋转/重进页面不重复弹）；
 * - 已授权时静默丢弃（恢复权限后不再打扰）；
 * - 消费后再失败仍能再次弹（不能永久静音，store 侧见 PlaybackBlockStoreTest）。
 */
class StoragePermissionPromptTest {

    @Before
    fun setUp() {
        PlaybackBlockStore.resetForTest()
    }

    @After
    fun tearDown() {
        PlaybackBlockStore.resetForTest()
    }

    private fun publish(atMs: Long = 1_000L, songId: Long = 7L, errorCode: Int = PlaybackException.ERROR_CODE_IO_NO_PERMISSION): PlaybackBlock {
        val block = PlaybackBlock(songId = songId, errorCode = errorCode, title = "song-7", publishedAtMs = atMs)
        assertTrue(PlaybackBlockStore.publish(block))
        return block
    }

    private fun take(allFilesAccessGranted: Boolean, appInForeground: Boolean): PlaybackBlock? =
        takeBlockForPrompt(
            candidate = PlaybackBlockStore.peek(),
            allFilesAccessGranted = allFilesAccessGranted,
            appInForeground = appInForeground,
            consume = PlaybackBlockStore::consume,
        )

    @Test
    fun backgroundKeepsTheEventForTheNextForeground() {
        val block = publish()
        assertNull("后台不弹", take(allFilesAccessGranted = false, appInForeground = false))
        assertSame("后台也不消费：事件留在 store 里", block, PlaybackBlockStore.peek())

        assertSame(
            "回前台后同一事件立即弹出（没有丢）",
            block,
            take(allFilesAccessGranted = false, appInForeground = true),
        )
        assertNull("弹过即消费", PlaybackBlockStore.peek())
    }

    @Test
    fun foregroundWithPermissionShowsTheDialogAndConsumesIt() {
        val block = publish()
        assertSame(block, take(allFilesAccessGranted = false, appInForeground = true))
        assertNull("消费一次即清除", PlaybackBlockStore.peek())
    }

    @Test
    fun rotatingTheScreenDoesNotShowTheSameEventAgain() {
        publish()
        assertTrue("首次展示", take(allFilesAccessGranted = false, appInForeground = true) != null)
        assertNull(
            "旋转后新的组合重新 collect：store 已空，不再弹",
            take(allFilesAccessGranted = false, appInForeground = true),
        )
    }

    @Test
    fun permissionAlreadyGrantedSilentlyDropsTheEvent() {
        publish()
        assertNull("已授权：不弹授权对话框", take(allFilesAccessGranted = true, appInForeground = true))
        assertNull("过时事件被消费掉，不会在下次前台再冒出来", PlaybackBlockStore.peek())
    }

    @Test
    fun lostRaceWithAnotherHostShowsNothing() {
        publish()
        assertNull(
            "另一个 host 已经消费（例如过渡帧里两个页面同时 collect）",
            takeBlockForPrompt(
                candidate = PlaybackBlockStore.peek(),
                allFilesAccessGranted = false,
                appInForeground = true,
                consume = { null },
            ),
        )
    }

    @Test
    fun policyTableMatchesTheIssueContract() {
        val block = PlaybackBlock(1L, PlaybackException.ERROR_CODE_IO_NO_PERMISSION, "t", 1L)
        assertTrue("前台 + 未授权 → 弹", StoragePermissionPromptPolicy.shouldPrompt(block, false, true))
        assertFalse("后台 → 不弹", StoragePermissionPromptPolicy.shouldPrompt(block, false, false))
        assertFalse("已授权 → 不弹", StoragePermissionPromptPolicy.shouldPrompt(block, true, true))
        assertFalse("没有事件 → 不弹", StoragePermissionPromptPolicy.shouldPrompt(null, false, true))
    }

    @Test
    fun dialogCopyMatchesTheIssueContract() {
        assertEquals("无法读取音频文件", StoragePermissionDialogStrings.TITLE)
        assertEquals("应用需要「所有文件访问」权限才能播放本地音乐", StoragePermissionDialogStrings.MESSAGE)
        assertEquals("去授权", StoragePermissionDialogStrings.CONFIRM)
        assertEquals("稍后", StoragePermissionDialogStrings.DISMISS)
    }
}
