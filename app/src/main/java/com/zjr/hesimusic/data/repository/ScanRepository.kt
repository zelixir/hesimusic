package com.zjr.hesimusic.data.repository

import com.zjr.hesimusic.data.dao.SongDao
import com.zjr.hesimusic.data.scanner.FileScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import javax.inject.Inject

sealed class ScanStatus {
    object Idle : ScanStatus()
    data class Scanning(val currentPath: String, val count: Int) : ScanStatus()
    data class Completed(val total: Int) : ScanStatus()
    data class Error(val message: String) : ScanStatus()
}

class ScanRepository @Inject constructor(
    private val fileScanner: FileScanner,
    private val songDao: SongDao
) {
    fun scanAndSave(): Flow<ScanStatus> = flow {
        emit(ScanStatus.Scanning("Starting...", 0))
        try {
            val songs = fileScanner.scan { path, count ->
                emit(ScanStatus.Scanning(path, count))
            }
            emit(ScanStatus.Scanning("Saving to database...", songs.size))
            songDao.deleteAll() // Clear old data
            songDao.insertAll(songs)
            emit(ScanStatus.Completed(songs.size))
        } catch (e: Exception) {
            emit(ScanStatus.Error("Scan failed: ${e.message}"))
            e.printStackTrace()
        }
    }.flowOn(Dispatchers.IO)

    suspend fun clearDatabase() {
        songDao.deleteAll()
    }
}
