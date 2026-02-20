package com.zjr.hesimusic.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.zjr.hesimusic.data.model.Playlist
import com.zjr.hesimusic.data.model.PlaylistEntry
import com.zjr.hesimusic.data.model.Song
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: Playlist): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistEntry(entry: PlaylistEntry)

    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    fun getPlaylists(): Flow<List<Playlist>>

    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    suspend fun getAllPlaylistsList(): List<Playlist>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(playlists: List<Playlist>)

    @Query("DELETE FROM playlists")
    suspend fun deleteAll()

    @Query("SELECT songs.* FROM songs INNER JOIN playlist_entries ON songs.id = playlist_entries.songId WHERE playlist_entries.playlistId = :playlistId ORDER BY playlist_entries.`order` ASC")
    fun getSongsByPlaylist(playlistId: Long): Flow<List<Song>>

    @Query("SELECT COALESCE(MAX(`order`), -1) + 1 FROM playlist_entries WHERE playlistId = :playlistId")
    suspend fun getNextOrder(playlistId: Long): Int

    @Query("SELECT COUNT(*) FROM playlist_entries WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun countSongInPlaylist(playlistId: Long, songId: Long): Int

    @Query("SELECT COUNT(*) FROM playlist_entries WHERE playlistId = :playlistId")
    fun getPlaylistSongCount(playlistId: Long): Flow<Int>

    @Query("DELETE FROM playlist_entries WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun removeSongFromPlaylist(playlistId: Long, songId: Long): Int

    @Query("DELETE FROM playlist_entries WHERE playlistId = :playlistId AND songId IN (:songIds)")
    suspend fun removeSongsFromPlaylist(playlistId: Long, songIds: List<Long>): Int

    @Query("DELETE FROM playlist_entries WHERE playlistId = :playlistId")
    suspend fun deletePlaylistEntries(playlistId: Long)

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    suspend fun deletePlaylistById(playlistId: Long)

    @Transaction
    suspend fun addSongToPlaylist(playlistId: Long, songId: Long) {
        if (countSongInPlaylist(playlistId, songId) == 0) {
            insertPlaylistEntry(
                PlaylistEntry(
                    playlistId = playlistId,
                    songId = songId,
                    order = getNextOrder(playlistId)
                )
            )
        }
    }

    @Transaction
    suspend fun deletePlaylist(playlistId: Long) {
        deletePlaylistEntries(playlistId)
        deletePlaylistById(playlistId)
    }
}
