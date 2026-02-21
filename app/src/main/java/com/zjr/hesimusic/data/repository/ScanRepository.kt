package com.zjr.hesimusic.data.repository

import com.zjr.hesimusic.data.dao.FavoriteDao
import com.zjr.hesimusic.data.dao.PlaylistDao
import com.zjr.hesimusic.data.dao.SongDao
import com.zjr.hesimusic.data.model.Favorite
import com.zjr.hesimusic.data.model.PlaylistEntry
import com.zjr.hesimusic.data.model.Song
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
    private val songDao: SongDao,
    private val playlistDao: PlaylistDao,
    private val favoriteDao: FavoriteDao
) {
    fun scanAndSave(
        scanFolders: Set<String> = emptySet(),
        excludedFolders: Set<String> = emptySet(),
        skipShortSongs: Boolean = false,
        skipAmrMid: Boolean = false,
        skipHiddenFolders: Boolean = false
    ): Flow<ScanStatus> = flow {
        emit(ScanStatus.Scanning("Starting...", 0))
        try {
            val songs = fileScanner.scan(
                scanFolders = scanFolders,
                excludedFolders = excludedFolders,
                skipShortSongs = skipShortSongs,
                skipAmrMid = skipAmrMid,
                skipHiddenFolders = skipHiddenFolders
            ) { path, count ->
                emit(ScanStatus.Scanning(path, count))
            }
            emit(ScanStatus.Scanning("Saving to database...", songs.size))
            val existingSongs = songDao.getAllSongsList()
            val existingPlaylistEntries = playlistDao.getAllPlaylistEntriesList()
            val existingFavorites = favoriteDao.getAllFavoritesList()

            songDao.deleteAll()
            songDao.insertAll(songs)

            val insertedSongs = songDao.getAllSongsList()
            val remappedEntries = remapPlaylistEntries(existingSongs, existingPlaylistEntries, insertedSongs)
            val remainingFavorites = filterFavoritesByExistingSongs(existingFavorites, insertedSongs)

            playlistDao.deleteAllPlaylistEntries()
            if (remappedEntries.isNotEmpty()) {
                playlistDao.insertPlaylistEntries(remappedEntries)
            }

            favoriteDao.deleteAll()
            if (remainingFavorites.isNotEmpty()) {
                favoriteDao.insertAll(remainingFavorites)
            }

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

internal fun remapPlaylistEntries(
    existingSongs: List<Song>,
    existingPlaylistEntries: List<PlaylistEntry>,
    insertedSongs: List<Song>
): List<PlaylistEntry> {
    val insertedSongIdByIdentity = insertedSongs.associate { SongIdentity(it.filePath, it.startPosition) to it.id }
    val existingSongById = existingSongs.associateBy { it.id }

    return existingPlaylistEntries.mapNotNull { entry ->
        val existingSong = existingSongById[entry.songId] ?: return@mapNotNull null
        val newSongId = insertedSongIdByIdentity[SongIdentity(existingSong.filePath, existingSong.startPosition)] ?: return@mapNotNull null
        entry.copy(id = 0, songId = newSongId)
    }
}

internal fun filterFavoritesByExistingSongs(
    favorites: List<Favorite>,
    songs: List<Song>
): List<Favorite> {
    val identities = songs.mapTo(mutableSetOf()) { SongIdentity(it.filePath, it.startPosition) }
    return favorites.filter { SongIdentity(it.filePath, it.startPosition) in identities }
}

private data class SongIdentity(
    val filePath: String,
    val startPosition: Long
)
