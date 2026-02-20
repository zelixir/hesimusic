package com.zjr.hesimusic.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.zjr.hesimusic.data.model.HiddenSong
import kotlinx.coroutines.flow.Flow

@Dao
interface HiddenSongDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(hiddenSong: HiddenSong)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(hiddenSongs: List<HiddenSong>)

    @Query("DELETE FROM hidden_songs WHERE filePath = :filePath AND startPosition = :startPosition")
    suspend fun delete(filePath: String, startPosition: Long)

    @Query("DELETE FROM hidden_songs")
    suspend fun deleteAll()

    @Query("SELECT * FROM hidden_songs ORDER BY hiddenAt DESC")
    fun getAll(): Flow<List<HiddenSong>>

    @Query("SELECT * FROM hidden_songs ORDER BY hiddenAt DESC")
    suspend fun getAllHiddenSongsList(): List<HiddenSong>
}
