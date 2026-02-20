package com.zjr.hesimusic.data.repository

import android.util.Log
import com.zjr.hesimusic.data.dao.PlaylistDao
import com.zjr.hesimusic.data.model.Playlist
import com.zjr.hesimusic.data.model.Song
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class PlaylistRepository @Inject constructor(
    private val playlistDao: PlaylistDao
) {
    companion object {
        private const val TAG = "PlaylistRepository"
    }
    fun getPlaylists(): Flow<List<Playlist>> = playlistDao.getPlaylists()

    fun getSongsByPlaylist(playlistId: Long): Flow<List<Song>> = playlistDao.getSongsByPlaylist(playlistId)

    fun getPlaylistSongCount(playlistId: Long): Flow<Int> = playlistDao.getPlaylistSongCount(playlistId)

    suspend fun addSongToPlaylist(playlistId: Long, songId: Long) {
        playlistDao.addSongToPlaylist(playlistId, songId)
    }

    suspend fun createPlaylist(name: String): Long {
        return playlistDao.insertPlaylist(Playlist(name = name.trim()))
    }

    suspend fun removeSongFromPlaylist(playlistId: Long, songId: Long): Int {
        return playlistDao.removeSongFromPlaylist(playlistId, songId)
    }

    suspend fun removeSongsFromPlaylist(playlistId: Long, songIds: List<Long>): Int {
        if (songIds.isEmpty()) return 0
        val deletedCount = playlistDao.removeSongsFromPlaylist(playlistId, songIds)
        if (deletedCount > 0) return deletedCount
        Log.w(TAG, "Batch remove returned 0, fallback to per-song delete. playlistId=$playlistId, songCount=${songIds.size}")
        var fallbackDeletedCount = 0
        songIds.forEach { songId ->
            fallbackDeletedCount += playlistDao.removeSongFromPlaylist(playlistId, songId)
        }
        return fallbackDeletedCount
    }

    suspend fun deletePlaylist(playlistId: Long) {
        playlistDao.deletePlaylist(playlistId)
    }
}
