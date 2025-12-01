package com.zjr.hesimusic.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeFormatterTest {

    @Test
    fun `formatTime returns correct format for zero milliseconds`() {
        assertEquals("00:00", TimeFormatter.formatTime(0))
    }

    @Test
    fun `formatTime returns correct format for seconds only`() {
        assertEquals("00:30", TimeFormatter.formatTime(30_000))
        assertEquals("00:59", TimeFormatter.formatTime(59_000))
    }

    @Test
    fun `formatTime returns correct format for minutes and seconds`() {
        assertEquals("01:00", TimeFormatter.formatTime(60_000))
        assertEquals("05:30", TimeFormatter.formatTime(330_000))
        assertEquals("45:00", TimeFormatter.formatTime(45 * 60 * 1000L))
        assertEquals("59:59", TimeFormatter.formatTime(59 * 60 * 1000L + 59_000))
    }

    @Test
    fun `formatTime returns hours format for long durations`() {
        assertEquals("1:00:00", TimeFormatter.formatTime(3600_000))
        assertEquals("1:30:00", TimeFormatter.formatTime(5400_000))
        assertEquals("2:15:30", TimeFormatter.formatTime(2 * 3600_000L + 15 * 60_000L + 30_000L))
    }

    @Test
    fun `formatTime handles negative values with placeholder`() {
        assertEquals("--:--", TimeFormatter.formatTime(-1))
        assertEquals("--:--", TimeFormatter.formatTime(-1000))
        assertEquals("--:--", TimeFormatter.formatTime(Long.MIN_VALUE)) // C.TIME_UNSET
    }

    @Test
    fun `formatTime handles very long durations correctly`() {
        // 10 hours
        assertEquals("10:00:00", TimeFormatter.formatTime(10 * 3600_000L))
        // 24 hours
        assertEquals("24:00:00", TimeFormatter.formatTime(24 * 3600_000L))
        // 100 hours
        assertEquals("100:00:00", TimeFormatter.formatTime(100 * 3600_000L))
    }

    @Test
    fun `formatTime handles edge case at 59 minutes 59 seconds`() {
        // Just under 1 hour (59:59)
        assertEquals("59:59", TimeFormatter.formatTime(59 * 60 * 1000L + 59 * 1000L))
        // Exactly 1 hour
        assertEquals("1:00:00", TimeFormatter.formatTime(60 * 60 * 1000L))
    }
}
