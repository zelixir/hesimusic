package com.zjr.hesimusic.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.zjr.hesimusic.data.model.Album
import com.zjr.hesimusic.data.model.Artist
import com.zjr.hesimusic.data.model.Favorite
import com.zjr.hesimusic.data.model.HiddenSong
import com.zjr.hesimusic.data.model.LogEntry
import com.zjr.hesimusic.data.model.Playlist
import com.zjr.hesimusic.data.model.PlaylistEntry
import com.zjr.hesimusic.data.model.Song
import kotlinx.coroutines.flow.Flow

@Dao
interface SongDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(songs: List<Song>)

    @Query("SELECT * FROM songs")
    suspend fun getAllSongsList(): List<Song>

    @Query("SELECT * FROM songs ORDER BY titleInitial COLLATE NOCASE ASC, title COLLATE NOCASE ASC")
    fun getAllSongs(): Flow<List<Song>>

    @Query("DELETE FROM songs")
    suspend fun deleteAll()
    
    @Query("SELECT * FROM songs WHERE filePath = :path")
    suspend fun getSongsByPath(path: String): List<Song>
    
    @Query("SELECT * FROM songs WHERE filePath = :path AND startPosition = :startPosition LIMIT 1")
    suspend fun getSongByPathAndStartPosition(path: String, startPosition: Long): Song?

    @Query("SELECT artist as name, COUNT(*) as songCount FROM songs GROUP BY artist ORDER BY artist COLLATE NOCASE ASC")
    fun getArtists(): Flow<List<Artist>>

    @Query("SELECT album as name, artist, COUNT(*) as songCount FROM songs GROUP BY album ORDER BY album COLLATE NOCASE ASC")
    fun getAlbums(): Flow<List<Album>>

    @Query("SELECT * FROM songs WHERE artist = :artist ORDER BY title COLLATE NOCASE ASC")
    fun getSongsByArtist(artist: String): Flow<List<Song>>

    @Query("SELECT * FROM songs WHERE album = :album ORDER BY trackNumber ASC")
    fun getSongsByAlbum(album: String): Flow<List<Song>>

    @Query("SELECT * FROM songs WHERE id IN (:ids)")
    suspend fun getSongsByIds(ids: List<Long>): List<Song>

    @Query("DELETE FROM songs WHERE id = :songId")
    suspend fun deleteById(songId: Long)

    @Query("DELETE FROM songs WHERE filePath = :filePath")
    suspend fun deleteByFilePath(filePath: String)

    /**
     * SQL 歌单视图的实时求值。 观察全部业务表：视图 SQL 可能引用任意表，
     * 任何相关数据变化都应触发重算。
     */
    @RawQuery(
        observedEntities = [
            Song::class, Favorite::class, HiddenSong::class,
            LogEntry::class, Playlist::class, PlaylistEntry::class
        ]
    )
    fun observeSmartPlaylistQuery(query: SupportSQLiteQuery): Flow<List<Song>>

    @RawQuery(observedEntities = [Song::class])
    suspend fun countSmartPlaylistQuery(query: SupportSQLiteQuery): Long
}
