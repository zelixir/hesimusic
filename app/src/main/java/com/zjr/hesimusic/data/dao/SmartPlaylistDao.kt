package com.zjr.hesimusic.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.zjr.hesimusic.data.model.SmartPlaylist
import kotlinx.coroutines.flow.Flow

@Dao
interface SmartPlaylistDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(playlist: SmartPlaylist): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(playlists: List<SmartPlaylist>)

    @Query("SELECT * FROM smart_playlists ORDER BY createdAt DESC")
    fun getAll(): Flow<List<SmartPlaylist>>

    @Query("SELECT * FROM smart_playlists ORDER BY createdAt DESC")
    suspend fun getAllList(): List<SmartPlaylist>

    @Query("SELECT * FROM smart_playlists WHERE id = :id")
    suspend fun getById(id: Long): SmartPlaylist?

    @Query("SELECT * FROM smart_playlists WHERE id = :id")
    fun observeById(id: Long): Flow<SmartPlaylist?>

    @Query("SELECT * FROM smart_playlists WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): SmartPlaylist?

    @Query("UPDATE smart_playlists SET name = :name WHERE id = :id")
    suspend fun renameById(id: Long, name: String)

    @Query("DELETE FROM smart_playlists WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM smart_playlists WHERE name = :name")
    suspend fun deleteByName(name: String): Int

    @Query("DELETE FROM smart_playlists")
    suspend fun deleteAll()
}
