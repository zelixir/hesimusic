package com.hesimusic.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TrackDao {
    @Query("SELECT id, title, artist FROM tracks")
    suspend fun getAllSimple(): List<TrackSimple>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tracks: List<Track>)
}

data class TrackSimple(
    val id: String,
    val title: String,
    val artist: String
)
