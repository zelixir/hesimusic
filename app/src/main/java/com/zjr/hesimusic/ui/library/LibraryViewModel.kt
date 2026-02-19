package com.zjr.hesimusic.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zjr.hesimusic.data.model.Album
import com.zjr.hesimusic.data.model.Artist
import com.zjr.hesimusic.data.model.FileSystemItem
import com.zjr.hesimusic.data.model.HiddenSong
import com.zjr.hesimusic.data.model.Playlist
import com.zjr.hesimusic.data.model.Song
import com.zjr.hesimusic.data.repository.HiddenSongRepository
import com.zjr.hesimusic.data.repository.LibraryRepository
import com.zjr.hesimusic.data.repository.PlaylistRepository
import com.zjr.hesimusic.data.repository.SongRepository
import com.zjr.hesimusic.data.scanner.TagLibHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@OptIn(FlowPreview::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repository: LibraryRepository,
    private val hiddenSongRepository: HiddenSongRepository,
    private val playlistRepository: PlaylistRepository,
    private val songRepository: SongRepository,
    private val tagLibHelper: TagLibHelper
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _isSearchActive = MutableStateFlow(false)
    val isSearchActive: StateFlow<Boolean> = _isSearchActive.asStateFlow()

    private val debouncedSearchQuery = _searchQuery.debounce(150)

    // Use Lazily for initial loading - only load when first subscriber appears
    private val allSongs: StateFlow<List<Song>> = repository.getAllSongs()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // Lazy load artists - only when artists tab is accessed
    private val allArtists: StateFlow<List<Artist>> = repository.getArtists()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // Lazy load albums - only when albums tab is accessed
    private val allAlbums: StateFlow<List<Album>> = repository.getAlbums()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // Lazy load favorites - only when favorites tab is accessed
    private val allFavoriteSongs: StateFlow<List<Song>> = repository.getFavoriteSongs()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val songs: StateFlow<List<Song>> = combine(allSongs, debouncedSearchQuery) { songs, query ->
        filterSongs(songs, query)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val artists: StateFlow<List<Artist>> = combine(allArtists, debouncedSearchQuery) { artists, query ->
        if (query.isBlank()) artists
        else artists.filter { artist ->
            artist.name.contains(query, ignoreCase = true)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val albums: StateFlow<List<Album>> = combine(allAlbums, debouncedSearchQuery) { albums, query ->
        if (query.isBlank()) albums
        else albums.filter { album ->
            album.name.contains(query, ignoreCase = true) ||
            album.artist.contains(query, ignoreCase = true)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val favoriteSongs: StateFlow<List<Song>> = combine(allFavoriteSongs, debouncedSearchQuery) { songs, query ->
        filterSongs(songs, query)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val playlists: StateFlow<List<Playlist>> = playlistRepository.getPlaylists()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val hiddenSongs: StateFlow<List<HiddenSong>> = hiddenSongRepository.getHiddenSongs()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private fun filterSongs(songs: List<Song>, query: String): List<Song> {
        if (query.isBlank()) return songs
        return songs.filter { song ->
            song.title.contains(query, ignoreCase = true) ||
            song.artist.contains(query, ignoreCase = true) ||
            song.album.contains(query, ignoreCase = true)
        }
    }

    fun getFolderContents(path: String) = repository.getFolderContents(path)
    
    fun getSongsByArtist(artist: String) = repository.getSongsByArtist(artist)
    
    fun getSongsByAlbum(album: String) = repository.getSongsByAlbum(album)

    fun getSongsByPlaylist(playlistId: Long) = combine(
        playlistRepository.getSongsByPlaylist(playlistId),
        hiddenSongRepository.getHiddenSongs()
    ) { songs, hidden ->
        val hiddenKeys = hidden.map { it.filePath to it.startPosition }.toSet()
        songs.filterNot { (it.filePath to it.startPosition) in hiddenKeys }
    }

    fun getPlaylistSongCount(playlistId: Long) = playlistRepository.getPlaylistSongCount(playlistId)

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setSearchActive(active: Boolean) {
        _isSearchActive.value = active
        if (!active) {
            _searchQuery.value = ""
        }
    }

    fun addSongToPlaylist(song: Song, playlistId: Long) {
        viewModelScope.launch {
            playlistRepository.addSongToPlaylist(playlistId, song.id)
        }
    }

    fun createPlaylist(name: String, onCreated: (Long?) -> Unit = {}) {
        viewModelScope.launch {
            val trimmedName = name.trim()
            if (trimmedName.isBlank()) {
                onCreated(null)
                return@launch
            }
            onCreated(playlistRepository.createPlaylist(trimmedName))
        }
    }

    fun hideSong(song: Song) {
        viewModelScope.launch {
            hiddenSongRepository.hideSong(song)
        }
    }

    fun unhideSong(hiddenSong: HiddenSong) {
        viewModelScope.launch {
            hiddenSongRepository.unhideSong(hiddenSong.filePath, hiddenSong.startPosition)
        }
    }

    fun deleteSongFile(song: Song, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val deleted = withContext(Dispatchers.IO) { songRepository.removeSong(song, deleteFile = true) }
            if (deleted) {
                songRepository.removeSongsByFilePath(song.filePath)
            }
            onResult(deleted)
        }
    }

    fun loadSongMetadata(song: Song, onLoaded: (Map<String, String>) -> Unit) {
        viewModelScope.launch {
            val metadata = withContext(Dispatchers.IO) {
                tagLibHelper.extractMetadata(song.filePath) ?: hashMapOf()
            }
            onLoaded(metadata)
        }
    }
}
