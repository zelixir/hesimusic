package com.zjr.hesimusic.data.repository

import com.zjr.hesimusic.data.dao.SongDao
import com.zjr.hesimusic.data.model.Song
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class SongRepository @Inject constructor(
    private val songDao: SongDao
) {
    fun getAllSongs(): Flow<List<Song>> = songDao.getAllSongs()
}
