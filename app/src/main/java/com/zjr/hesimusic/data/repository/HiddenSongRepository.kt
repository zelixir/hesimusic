package com.zjr.hesimusic.data.repository

import com.zjr.hesimusic.data.dao.HiddenSongDao
import com.zjr.hesimusic.data.model.HiddenSong
import com.zjr.hesimusic.data.model.Song
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class HiddenSongRepository @Inject constructor(
    private val hiddenSongDao: HiddenSongDao
) {
    fun getHiddenSongs(): Flow<List<HiddenSong>> = hiddenSongDao.getAll()

    suspend fun hideSong(song: Song) {
        hiddenSongDao.insert(HiddenSong(song.filePath, song.startPosition))
    }

    suspend fun unhideSong(filePath: String, startPosition: Long) {
        hiddenSongDao.delete(filePath, startPosition)
    }
}
