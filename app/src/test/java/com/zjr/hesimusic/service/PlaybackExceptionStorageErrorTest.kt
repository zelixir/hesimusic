package com.zjr.hesimusic.service

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * issue 00030：真实 [PlaybackException] 实例上的判据。
 *
 * 单独放 Robolectric：media3 的 PlaybackException 构造器会读 android.os.SystemClock，
 * 纯 JVM 单测会报 "not mocked"。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaybackExceptionStorageErrorTest {

    @Test
    fun wrappedEaccesProducesAStorageBlock() {
        val error = PlaybackException(
            "source failed",
            IOException("open failed: EACCES (Permission denied)"),
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        )
        assertTrue(StorageAccessErrors.isStorageAccessError(error))
        val block = StorageAccessErrors.blockFor(error, songId = 9L, title = "夜曲", nowMs = 5L)
        assertNotNull(block)
        assertEquals(9L, block!!.songId)
        assertEquals(5L, block.publishedAtMs)
    }

    @Test
    fun decodingFailureOnAPlaybackExceptionIsNotAStorageBlock() {
        val error = PlaybackException(
            "decoder failed",
            IOException("unrecognized container"),
            PlaybackException.ERROR_CODE_DECODING_FAILED,
        )
        assertEquals(null, StorageAccessErrors.blockFor(error, songId = 9L, title = "夜曲", nowMs = 5L))
    }
}
