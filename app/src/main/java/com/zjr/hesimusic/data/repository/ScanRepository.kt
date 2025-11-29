package com.zjr.hesimusic.data.repository

import com.zjr.hesimusic.data.dao.SongDao
import com.zjr.hesimusic.data.scanner.FileScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import javax.inject.Inject

class ScanRepository @Inject constructor(
    private val fileScanner: FileScanner,
    private val songDao: SongDao
) {
    fun scanAndSave(): Flow<String> = flow {
        emit("Scanning started...")
        try {
            val songs = fileScanner.scan()
            emit("Found ${songs.size} songs. Saving to database...")
            songDao.deleteAll() // Clear old data
            songDao.insertAll(songs)
            emit("Scan completed. ${songs.size} songs added.")
        } catch (e: Exception) {
            emit("Scan failed: ${e.message}")
            e.printStackTrace()
        }
    }.flowOn(Dispatchers.IO)
}
