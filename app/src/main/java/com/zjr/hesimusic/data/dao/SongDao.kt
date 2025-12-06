package com.zjr.hesimusic.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.zjr.hesimusic.data.model.Album
import com.zjr.hesimusic.data.model.Artist
import com.zjr.hesimusic.data.model.Song
import kotlinx.coroutines.flow.Flow

@Dao
interface SongDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(songs: List<Song>)

    @Query("SELECT * FROM songs ORDER BY titleInitial ASC, title ASC")
    fun getAllSongs(): Flow<List<Song>>

    @Query("DELETE FROM songs")
    suspend fun deleteAll()
    
    @Query("SELECT * FROM songs WHERE filePath = :path")
    suspend fun getSongsByPath(path: String): List<Song>
    
    @Query("SELECT * FROM songs WHERE filePath = :path AND startPosition = :startPosition LIMIT 1")
    suspend fun getSongByPathAndStartPosition(path: String, startPosition: Long): Song?

    @Query("SELECT artist as name, COUNT(*) as songCount FROM songs GROUP BY artist ORDER BY artist ASC")
    fun getArtists(): Flow<List<Artist>>

    @Query("SELECT album as name, artist, COUNT(*) as songCount FROM songs GROUP BY album ORDER BY album ASC")
    fun getAlbums(): Flow<List<Album>>

    @Query("SELECT * FROM songs WHERE artist = :artist ORDER BY title ASC")
    fun getSongsByArtist(artist: String): Flow<List<Song>>

    @Query("SELECT * FROM songs WHERE album = :album ORDER BY trackNumber ASC")
    fun getSongsByAlbum(album: String): Flow<List<Song>>

    @Query("SELECT * FROM songs WHERE id IN (:ids)")
    suspend fun getSongsByIds(ids: List<Long>): List<Song>
}
