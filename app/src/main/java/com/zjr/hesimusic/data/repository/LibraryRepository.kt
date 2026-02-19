package com.zjr.hesimusic.data.repository

import com.zjr.hesimusic.data.dao.FavoriteDao
import com.zjr.hesimusic.data.dao.HiddenSongDao
import com.zjr.hesimusic.data.dao.SongDao
import com.zjr.hesimusic.data.model.Album
import com.zjr.hesimusic.data.model.Artist
import com.zjr.hesimusic.data.model.FileSystemItem
import com.zjr.hesimusic.data.model.Song
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.io.File
import javax.inject.Inject

class LibraryRepository @Inject constructor(
    private val songDao: SongDao,
    private val favoriteDao: FavoriteDao,
    private val hiddenSongDao: HiddenSongDao
) {
    private val visibleSongs: Flow<List<Song>> = songDao.getAllSongs().combine(hiddenSongDao.getAll()) { songs, hidden ->
        val hiddenKeys = hidden.map { it.filePath to it.startPosition }.toSet()
        songs.filterNot { (it.filePath to it.startPosition) in hiddenKeys }
    }

    fun getAllSongs(): Flow<List<Song>> = visibleSongs

    fun getArtists(): Flow<List<Artist>> = visibleSongs.map { songs ->
        songs.groupBy { it.artist }
            .map { Artist(name = it.key, songCount = it.value.size) }
            .sortedBy { it.name.lowercase() }
    }

    fun getAlbums(): Flow<List<Album>> = visibleSongs.map { songs ->
        songs.groupBy { it.album to it.artist }
            .map { Album(name = it.key.first, artist = it.key.second, songCount = it.value.size) }
            .sortedBy { it.name.lowercase() }
    }

    fun getSongsByArtist(artist: String): Flow<List<Song>> = visibleSongs.map { songs ->
        songs.filter { it.artist == artist }.sortedBy { it.title.lowercase() }
    }

    fun getSongsByAlbum(album: String): Flow<List<Song>> = visibleSongs.map { songs ->
        songs.filter { it.album == album }.sortedBy { it.trackNumber }
    }

    fun getFavoriteSongs(): Flow<List<Song>> {
        return favoriteDao.getAllFavoritesAsList().combine(visibleSongs) { favorites, allSongs ->
            // Create a set of (filePath, startPosition) pairs for efficient lookup
            val favoriteKeys = favorites.map { it.filePath to it.startPosition }.toSet()
            allSongs.filter { (it.filePath to it.startPosition) in favoriteKeys }
        }
    }

    fun getFolderContents(parentPath: String): Flow<List<FileSystemItem>> {
        return visibleSongs.map { songs ->
            val items = mutableListOf<FileSystemItem>()
            val folders = mutableMapOf<String, Int>() // path -> count

            songs.forEach { song ->
                // Use pre-computed folderPath for faster filtering
                if (song.folderPath == parentPath) {
                    items.add(FileSystemItem.MusicFile(song))
                } else if (song.folderPath.startsWith(parentPath)) {
                    // Check if it is a direct subfolder
                    val validSub = if (parentPath.endsWith(File.separator)) {
                        song.folderPath.startsWith(parentPath)
                    } else {
                        song.folderPath.startsWith(parentPath + File.separator)
                    }

                    if (validSub) {
                        val relative = song.folderPath.removePrefix(parentPath).trimStart(File.separatorChar)
                        if (relative.isNotEmpty()) {
                            val firstPart = relative.split(File.separatorChar).first()
                            val folderPath = if (parentPath.endsWith(File.separator)) "$parentPath$firstPart" else "$parentPath${File.separator}$firstPart"
                            folders[folderPath] = (folders[folderPath] ?: 0) + 1
                        }
                    }
                }
            }
            
            folders.forEach { (path, count) ->
                val name = File(path).name
                items.add(FileSystemItem.Folder(name, path, count))
            }
            
            items.sortedWith(compareBy({ it is FileSystemItem.MusicFile }, { 
                when(it) {
                    is FileSystemItem.Folder -> it.name.lowercase()
                    is FileSystemItem.MusicFile -> it.song.title.lowercase()
                }
            }))
        }
    }
}
