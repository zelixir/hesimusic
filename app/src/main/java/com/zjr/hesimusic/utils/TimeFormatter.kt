package com.zjr.hesimusic.utils

import java.util.Locale

/**
 * Utility object for formatting time durations.
 */
object TimeFormatter {
    /**
     * Formats milliseconds to a human-readable time string.
     * 
     * @param millis The time in milliseconds
     * @return A formatted string like "MM:SS" or "H:MM:SS" for longer durations,
     *         or "--:--" for invalid/negative values
     */
    fun formatTime(millis: Long): String {
        // Handle invalid durations (e.g., C.TIME_UNSET = Long.MIN_VALUE or negative values)
        if (millis < 0) {
            return "--:--"
        }
        val totalSeconds = millis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }
    }
}
