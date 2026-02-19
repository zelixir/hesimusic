package com.zjr.hesimusic.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.zjr.hesimusic.data.model.PlaylistEntry

@Dao
interface PlaylistEntryDao {
    @Query("SELECT * FROM playlist_entries ORDER BY `order` ASC")
    suspend fun getAllPlaylistEntriesList(): List<PlaylistEntry>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<PlaylistEntry>)

    @Query("DELETE FROM playlist_entries")
    suspend fun deleteAll()
}
