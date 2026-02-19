package com.zjr.hesimusic.data.model

import androidx.room.Entity

@Entity(tableName = "hidden_songs", primaryKeys = ["filePath", "startPosition"])
data class HiddenSong(
    val filePath: String,
    val startPosition: Long,
    val hiddenAt: Long = System.currentTimeMillis()
)
