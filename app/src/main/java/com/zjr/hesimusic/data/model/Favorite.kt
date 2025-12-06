package com.zjr.hesimusic.data.model

import androidx.room.Entity

/**
 * Entity to store favorite songs by file path and start position.
 * Using filePath + startPosition as a composite primary key ensures:
 * 1. Favorites survive music rescans (songs table is cleared during scans)
 * 2. Individual CUE tracks can be favorited separately (they share the same
 *    filePath but have different startPosition values)
 * For non-CUE songs, startPosition is always 0.
 */
@Entity(tableName = "favorites", primaryKeys = ["filePath", "startPosition"])
data class Favorite(
    val filePath: String,
    val startPosition: Long = 0,
    val dateAdded: Long = System.currentTimeMillis()
)
