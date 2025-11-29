package com.zjr.hesimusic.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "songs")
data class Song(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val artist: String,
    val album: String,
    val filePath: String,
    val duration: Long,
    val trackNumber: Int = 0,
    val year: String? = null,
    val genre: String? = null,
    val mimeType: String,
    val size: Long,
    val dateAdded: Long,
    
    // CUE specific fields
    val isCue: Boolean = false,
    val cueFilePath: String? = null,
    val startPosition: Long = 0,
    val endPosition: Long = -1 // -1 means until end of file or next track
)
