package com.hesimusic.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tracks")
data class Track(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String,
    val album: String?,
    val duration: Long,
    val path: String
)
