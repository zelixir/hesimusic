package com.zjr.hesimusic.loop.audio.source

import androidx.media3.common.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** MediaSource 壳的最小契约（JVM；不创建 MediaPeriod/不启动播放器）。 */
class LoopingPcmMediaSourceTest {

    @Test
    fun `initial timeline is a single dynamic seekable window`() {
        val tl = LoopingPcmTestSupport.timeline()
        val item = MediaItem.Builder().setMediaId("song-7").build()
        val source = LoopingPcmMediaSource(item, tl, LoopingPcmTestSupport.provider(tl))

        val timeline = source.getInitialTimeline()
        assertEquals(1, timeline.windowCount)
        assertTrue(source.isSingleWindow)
        assertEquals("song-7", source.getMediaItem().mediaId)

        val window = androidx.media3.common.Timeline.Window()
        timeline.getWindow(0, window)
        assertTrue("dynamic", window.isDynamic)
        assertTrue("seekable", window.isSeekable)
        assertEquals(androidx.media3.common.C.TIME_UNSET, window.durationUs)
    }
}
