package com.zjr.hesimusic.data.repository

import com.zjr.hesimusic.data.dao.SongDao
import com.zjr.hesimusic.data.model.Song
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class SongRepository @Inject constructor(
    private val songDao: SongDao
) {
    fun getAllSongs(): Flow<List<Song>> = songDao.getAllSongs()

    suspend fun getSongsByIds(ids: List<Long>): List<Song> = songDao.getSongsByIds(ids)
    
    suspend fun getSongByFilePath(filePath: String): Song? = songDao.getSongsByPath(filePath).firstOrNull()
}
