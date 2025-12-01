package com.zjr.hesimusic.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entity to store favorite songs by file path.
 * Using filePath as the primary key ensures favorites survive music rescans,
 * since the songs table is cleared and repopulated during scans.
 */
@Entity(tableName = "favorites")
data class Favorite(
    @PrimaryKey val filePath: String,
    val dateAdded: Long = System.currentTimeMillis()
)
