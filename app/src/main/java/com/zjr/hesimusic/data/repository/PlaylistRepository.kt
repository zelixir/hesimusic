package com.zjr.hesimusic.data.repository

import com.zjr.hesimusic.data.dao.PlaylistDao
import com.zjr.hesimusic.data.model.Playlist
import com.zjr.hesimusic.data.model.Song
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class PlaylistRepository @Inject constructor(
    private val playlistDao: PlaylistDao
) {
    fun getPlaylists(): Flow<List<Playlist>> = playlistDao.getPlaylists()

    fun getSongsByPlaylist(playlistId: Long): Flow<List<Song>> = playlistDao.getSongsByPlaylist(playlistId)

    fun getPlaylistSongCount(playlistId: Long): Flow<Int> = playlistDao.getPlaylistSongCount(playlistId)

    suspend fun addSongToPlaylist(playlistId: Long, songId: Long) {
        playlistDao.addSongToPlaylist(playlistId, songId)
    }

    suspend fun createPlaylist(name: String): Long {
        return playlistDao.insertPlaylist(Playlist(name = name.trim()))
    }
}
