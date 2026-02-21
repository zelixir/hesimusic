package com.zjr.hesimusic.ui.library

import org.junit.Assert.assertEquals
import org.junit.Test

class SongListQueueDisplayTest {

    @Test
    fun `build queue display keeps duplicate order positions`() {
        val display = buildQueueDisplayBySongId(listOf(10L, 20L, 10L, 30L, 10L))
        assertEquals("1,3,\n5", display[10L])
        assertEquals("2", display[20L])
        assertEquals("4", display[30L])
    }

    @Test
    fun `format queue positions uses max 4 chars per line and 2 lines`() {
        assertEquals("1,2,3", formatQueuePositionsForDisplay(listOf(1, 2, 3)))
        assertEquals("1,2,\n3,4,", formatQueuePositionsForDisplay(listOf(1, 2, 3, 4, 5)))
    }
}
