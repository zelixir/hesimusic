package com.zjr.hesimusic.data.repository

import com.zjr.hesimusic.data.dao.FavoriteDao
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
    private val favoriteDao: FavoriteDao
) {
    fun getAllSongs(): Flow<List<Song>> = songDao.getAllSongs()

    fun getArtists(): Flow<List<Artist>> = songDao.getArtists()

    fun getAlbums(): Flow<List<Album>> = songDao.getAlbums()

    fun getSongsByArtist(artist: String): Flow<List<Song>> = songDao.getSongsByArtist(artist)

    fun getSongsByAlbum(album: String): Flow<List<Song>> = songDao.getSongsByAlbum(album)

    fun getFavoriteSongs(): Flow<List<Song>> {
        return favoriteDao.getAllFavoritePaths().combine(songDao.getAllSongs()) { favoritePaths, allSongs ->
            val favoritePathSet = favoritePaths.toSet()
            allSongs.filter { it.filePath in favoritePathSet }
        }
    }

    fun getFolderContents(parentPath: String): Flow<List<FileSystemItem>> {
        return songDao.getAllSongs().map { songs ->
            val items = mutableListOf<FileSystemItem>()
            val folders = mutableMapOf<String, Int>() // path -> count

            songs.forEach { song ->
                val songFile = File(song.filePath)
                val songParent = songFile.parentFile?.absolutePath ?: ""
                
                if (songParent == parentPath) {
                    items.add(FileSystemItem.MusicFile(song))
                } else if (songParent.startsWith(parentPath)) {
                    // Check if it is a direct subfolder or deeper
                    // Ensure we match directory boundary
                    // If parentPath is "/a/b", and songParent is "/a/b/c", relative is "c"
                    // If parentPath is "/a/b", and songParent is "/a/bc", relative is "c" (Wait, startsWith is true)
                    // So we need to ensure boundary.
                    
                    val validSub = if (parentPath.endsWith(File.separator)) {
                        songParent.startsWith(parentPath)
                    } else {
                        songParent.startsWith(parentPath + File.separator)
                    }

                    if (validSub) {
                        val relative = songParent.removePrefix(parentPath).trimStart(File.separatorChar)
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
                    is FileSystemItem.Folder -> it.name
                    is FileSystemItem.MusicFile -> it.song.title
                }
            }))
        }
    }
}
