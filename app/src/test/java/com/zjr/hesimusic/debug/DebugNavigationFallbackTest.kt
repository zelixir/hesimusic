package com.zjr.hesimusic.debug

import androidx.media3.common.C
import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #106 debug 端点 next/previous 回退测试（纯 JVM）。
 *
 * 真机 1272：SOURCE item 重建后 controller 缺 COMMAND_SEEK_TO_NEXT，直接 seekToNext()
 * 在客户端 no-op。这里钉住与 MusicViewModel.skipToNext 一致的逐级回退。
 */
class DebugNavigationFallbackTest {

    @Test
    fun next_prefersSeekToNextWhenAvailable() {
        assertEquals(
            DebugNavigationFallback.SEEK_TO_NEXT,
            DebugNavigationFallback.next(
                hasNext = true,
                canSeekToNext = true,
                canSeekToNextMediaItem = true,
                nextMediaItemIndex = 3,
                canSeekDefault = true,
                repeatMode = Player.REPEAT_MODE_OFF,
                mediaItemCount = 5,
            ),
        )
    }

    @Test
    fun next_fallsBackToSeekToNextMediaItem() {
        assertEquals(
            DebugNavigationFallback.SEEK_TO_NEXT_MEDIA_ITEM,
            DebugNavigationFallback.next(
                hasNext = true,
                canSeekToNext = false,
                canSeekToNextMediaItem = true,
                nextMediaItemIndex = 3,
                canSeekDefault = true,
                repeatMode = Player.REPEAT_MODE_OFF,
                mediaItemCount = 5,
            ),
        )
    }

    @Test
    fun next_fallsBackToExplicitDefaultPosition() {
        assertEquals(
            DebugNavigationFallback.SEEK_DEFAULT_NEXT,
            DebugNavigationFallback.next(
                hasNext = true,
                canSeekToNext = false,
                canSeekToNextMediaItem = false,
                nextMediaItemIndex = 3,
                canSeekDefault = true,
                repeatMode = Player.REPEAT_MODE_OFF,
                mediaItemCount = 5,
            ),
        )
    }

    @Test
    fun next_singleItemLoop_wrapsToStart() {
        assertEquals(
            DebugNavigationFallback.WRAP_TO_START,
            DebugNavigationFallback.next(
                hasNext = false,
                canSeekToNext = false,
                canSeekToNextMediaItem = false,
                nextMediaItemIndex = C.INDEX_UNSET,
                canSeekDefault = true,
                repeatMode = Player.REPEAT_MODE_ONE,
                mediaItemCount = 1,
            ),
        )
    }

    @Test
    fun next_repeatAll_letsThePlayerWrap() {
        // repeat=ALL 时 hasNextMediaItem() 恒 true；不该手写绕回。
        assertEquals(
            DebugNavigationFallback.NOOP,
            DebugNavigationFallback.next(
                hasNext = false,
                canSeekToNext = false,
                canSeekToNextMediaItem = false,
                nextMediaItemIndex = C.INDEX_UNSET,
                canSeekDefault = false,
                repeatMode = Player.REPEAT_MODE_ALL,
                mediaItemCount = 1,
            ),
        )
    }

    @Test
    fun previous_prefersSeekToPreviousWhenAvailable() {
        assertEquals(
            DebugNavigationFallback.SEEK_TO_PREVIOUS,
            DebugNavigationFallback.previous(
                hasPrevious = true,
                canSeekToPrevious = true,
                canSeekToPreviousMediaItem = true,
                previousMediaItemIndex = 1,
                canSeekDefault = true,
                repeatMode = Player.REPEAT_MODE_OFF,
                mediaItemCount = 5,
            ),
        )
    }

    @Test
    fun previous_fallsBackToExplicitDefaultPosition() {
        assertEquals(
            DebugNavigationFallback.SEEK_DEFAULT_PREVIOUS,
            DebugNavigationFallback.previous(
                hasPrevious = true,
                canSeekToPrevious = false,
                canSeekToPreviousMediaItem = false,
                previousMediaItemIndex = 1,
                canSeekDefault = true,
                repeatMode = Player.REPEAT_MODE_OFF,
                mediaItemCount = 5,
            ),
        )
    }

    @Test
    fun previous_singleItemLoop_wrapsToEnd() {
        assertEquals(
            DebugNavigationFallback.WRAP_TO_END,
            DebugNavigationFallback.previous(
                hasPrevious = false,
                canSeekToPrevious = false,
                canSeekToPreviousMediaItem = false,
                previousMediaItemIndex = C.INDEX_UNSET,
                canSeekDefault = true,
                repeatMode = Player.REPEAT_MODE_ONE,
                mediaItemCount = 1,
            ),
        )
    }
}
