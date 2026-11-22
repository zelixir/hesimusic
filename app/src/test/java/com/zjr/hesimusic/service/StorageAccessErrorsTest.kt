package com.zjr.hesimusic.service

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException

/**
 * issue 00030：什么样的播放错误算「读不到音频文件」。
 *
 * 判据必须同时覆盖 media3 的 errorCode 和底层 cause 链（EACCES / Permission denied
 * 常被 FileNotFoundException / IOException 多层包住）。
 */
class StorageAccessErrorsTest {

    @Test
    fun noPermissionCodeIsAStorageAccessError() {
        assertTrue(StorageAccessErrors.isStorageAccessError(PlaybackException.ERROR_CODE_IO_NO_PERMISSION, null))
    }

    @Test
    fun fileNotFoundCodeIsAStorageAccessError() {
        assertTrue(StorageAccessErrors.isStorageAccessError(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND, null))
    }

    @Test
    fun otherCodesWithoutEaccesAreNotStorageAccessErrors() {
        assertFalse(StorageAccessErrors.isStorageAccessError(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, null))
        assertFalse(StorageAccessErrors.isStorageAccessError(PlaybackException.ERROR_CODE_DECODING_FAILED, FileNotFoundException("/x")))
        assertFalse(
            "同类但无关的错误不能触发授权引导",
            StorageAccessErrors.isStorageAccessError(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, IOException("disk full")),
        )
    }

    @Test
    fun eaccesDeepInTheCauseChainCounts() {
        val root = IOException("open failed: EACCES (Permission denied)")
        val middle = IllegalStateException("source failed", root)
        val top = RuntimeException("decoder failed", middle)
        assertTrue(
            "EACCES 被包在 3 层 cause 里仍要识别",
            StorageAccessErrors.isStorageAccessError(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, top),
        )
    }

    @Test
    fun permissionDeniedWordingCountsAsWell() {
        assertTrue(
            StorageAccessErrors.isStorageAccessError(
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                IOException("Permission denied"),
            ),
        )
    }

    @Test
    fun blockForOnlyBuildsABlockForStorageErrors() {
        assertNotNull(
            "存储错误 → 事件",
            StorageAccessErrors.blockFor(
                errorCode = PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
                cause = null,
                songId = 42L,
                title = "夜曲",
                nowMs = 123L,
            ),
        )
        assertNull(
            "其它错误不进授权引导（仍走原 Toast）",
            StorageAccessErrors.blockFor(
                errorCode = PlaybackException.ERROR_CODE_DECODING_FAILED,
                cause = IOException("bad container"),
                songId = 42L,
                title = "夜曲",
                nowMs = 123L,
            ),
        )
    }

    @Test
    fun blockCarriesSongTitleAndTimestamp() {
        val block = StorageAccessErrors.blockFor(
            errorCode = PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            cause = null,
            songId = 42L,
            title = "夜曲",
            nowMs = 123L,
        )!!
        assertEquals(42L, block.songId)
        assertEquals(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND, block.errorCode)
        assertEquals("夜曲", block.title)
        assertEquals(123L, block.publishedAtMs)
    }

    @Test
    fun planRequiresTheUiToOwnTheForeground() {
        val block = PlaybackBlock(songId = 1L, errorCode = PlaybackException.ERROR_CODE_IO_NO_PERMISSION, title = "t", publishedAtMs = 1L)
        val foreground = PlaybackErrorRouter.plan(block, appInForeground = true)
        assertEquals("前台：发布事件，交给对话框", block, foreground.publish)
        assertFalse("前台不再叠一条 Toast", foreground.toast)

        val background = PlaybackErrorRouter.plan(block, appInForeground = false)
        assertEquals("后台：事件先记着，回前台再弹", block, background.publish)
        assertTrue("后台保留 Toast 兜底", background.toast)
    }

    @Test
    fun planKeepsTheToastForNonStorageErrors() {
        val plan = PlaybackErrorRouter.plan(block = null, appInForeground = true)
        assertNull(plan.publish)
        assertTrue("非存储错误仍然只弹 Toast", plan.toast)
    }
}
