package com.zjr.hesimusic.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "logs",
    indices = [Index(value = ["timestamp"])]
)
data class LogEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long,
    val level: String, // DEBUG, INFO, WARNING, ERROR
    val tag: String,
    val message: String
)
