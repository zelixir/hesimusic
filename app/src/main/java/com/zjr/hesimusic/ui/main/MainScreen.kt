package com.zjr.hesimusic.ui.main

import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.Player
import com.zjr.hesimusic.data.model.Album
import com.zjr.hesimusic.data.model.Artist
import com.zjr.hesimusic.data.model.Song
import com.zjr.hesimusic.data.preferences.PlaylistContext
import com.zjr.hesimusic.data.preferences.PlaylistType
import com.zjr.hesimusic.ui.common.MusicViewModel
import com.zjr.hesimusic.ui.library.AlbumList
import com.zjr.hesimusic.ui.library.ArtistList
import com.zjr.hesimusic.ui.library.BatchActionBar
import com.zjr.hesimusic.ui.library.FolderList
import com.zjr.hesimusic.ui.library.LibraryViewModel
import com.zjr.hesimusic.ui.library.SongList
import com.zjr.hesimusic.ui.library.SongActionHost
import com.zjr.hesimusic.ui.library.AddToPlaylistDialog
import com.zjr.hesimusic.ui.library.buildQueueDisplayBySongId
import kotlinx.coroutines.launch

private const val PLAYLIST_TAB_INDEX = 1

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MainScreen(
    viewModel: LibraryViewModel = hiltViewModel(),
    musicViewModel: MusicViewModel = hiltViewModel(),
    onArtistClick: (Artist) -> Unit,
    onAlbumClick: (Album) -> Unit,
    onPlayerClick: () -> Unit,
    onScanClick: () -> Unit,
    onBackupRestoreClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onEqualizerClick: () -> Unit,
    onAboutClick: () -> Unit,
    onSleepTimerClick: () -> Unit,
    onLogsClick: () -> Unit
) {
    // Get AppLogger from Application context
    val context = LocalContext.current
    val appLogger = remember {
        (context.applicationContext as? com.zjr.hesimusic.HesiMusicApplication)?.appLogger
    }
    
    val pagerState = rememberPagerState(pageCount = { 6 })
    val scope = rememberCoroutineScope()
    val titles = listOf("歌曲", "歌单", "收藏", "文件夹", "歌手", "专辑")
    val musicUiState by musicViewModel.uiState.collectAsState()
    val sleepTimerState by musicViewModel.sleepTimerState.collectAsState()
    val savedPlaylistContext by musicViewModel.savedPlaylistContext.collectAsState()
    val playQueueSongIds by musicViewModel.playQueueSongIds.collectAsState()
    val isSearchActive by viewModel.isSearchActive.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val focusRequester = remember { FocusRequester() }
    val playlists by viewModel.playlists.collectAsState()
    var selectedSongForActions by remember { mutableStateOf<Song?>(null) }
    var batchModeSongs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var batchModePlaylistId by remember { mutableStateOf<Long?>(null) }
    var isBatchMode by remember { mutableStateOf(false) }
    var batchModeTabIndex by remember { mutableStateOf<Int?>(null) }
    var isPlaylistSongsVisible by remember { mutableStateOf(false) }
    var batchSelectedSongIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var batchFavoriteActionText by remember { mutableStateOf("加入收藏") }
    var showBatchAddDialog by remember { mutableStateOf(false) }
    var hasRestoredLibraryContext by remember { mutableStateOf(false) }
    var restoredFolderPath by remember { mutableStateOf<String?>(null) }
    var restoredPlaylistId by remember { mutableStateOf<Long?>(null) }

    // Request focus when search becomes active
    LaunchedEffect(isSearchActive) {
        if (isSearchActive) {
            focusRequester.requestFocus()
        }
    }

    // Handle back button to close search
    BackHandler(enabled = isSearchActive) {
        viewModel.setSearchActive(false)
    }

    fun exitBatchMode() {
        isBatchMode = false
        batchModeTabIndex = null
        batchSelectedSongIds = emptySet()
        batchModeSongs = emptyList()
        batchModePlaylistId = null
        batchFavoriteActionText = "加入收藏"
    }

    BackHandler(enabled = isBatchMode) {
        exitBatchMode()
    }

    LaunchedEffect(pagerState.currentPage) {
        if (isBatchMode && batchModeTabIndex != pagerState.currentPage) {
            exitBatchMode()
        }
    }

    LaunchedEffect(isPlaylistSongsVisible) {
        val shouldExitBatchModeForPlaylistView =
            isBatchMode && batchModeTabIndex == PLAYLIST_TAB_INDEX && !isPlaylistSongsVisible
        if (shouldExitBatchModeForPlaylistView) {
            exitBatchMode()
        }
    }
    
    LaunchedEffect(savedPlaylistContext, hasRestoredLibraryContext) {
        if (hasRestoredLibraryContext) return@LaunchedEffect
        val playlistContext = savedPlaylistContext ?: return@LaunchedEffect
        val targetPage = playlistTypeToTabIndex(playlistContext.type)
        if (pagerState.currentPage != targetPage) {
            pagerState.scrollToPage(targetPage)
        }
        when (playlistContext.type) {
            PlaylistType.ARTIST -> {
                if (playlistContext.value.isNotBlank()) {
                    onArtistClick(Artist(name = playlistContext.value, songCount = 0))
                }
            }
            PlaylistType.ALBUM -> {
                if (playlistContext.value.isNotBlank()) {
                    onAlbumClick(Album(name = playlistContext.value, artist = "", songCount = 0))
                }
            }
            PlaylistType.FOLDER -> {
                restoredFolderPath = playlistContext.value.takeIf { it.isNotBlank() }
            }
            PlaylistType.PLAYLIST -> {
                restoredPlaylistId = parsePlaylistId(playlistContext.value)
            }
            else -> Unit
        }
        hasRestoredLibraryContext = true
        musicViewModel.clearSavedPlaylistContext()
    }

    Scaffold(
        bottomBar = {
            if (isBatchMode) {
                val playlistId = batchModePlaylistId
                val canAddToQueue = when (batchModeTabIndex) {
                    0 -> musicUiState.playlistContext == PlaylistContext.GLOBAL
                    PLAYLIST_TAB_INDEX -> playlistId != null &&
                        musicUiState.playlistContext == PlaylistContext(PlaylistType.PLAYLIST, playlistId.toString())
                    2 -> musicUiState.playlistContext == PlaylistContext.FAVORITES
                    else -> false
                }
                BatchActionBar(
                    showRemoveFromPlaylist = playlistId != null,
                    favoriteActionText = batchFavoriteActionText,
                    allSelected = batchModeSongs.isNotEmpty() && batchSelectedSongIds.size == batchModeSongs.size,
                    onSelectAllChange = { checked ->
                        batchSelectedSongIds = if (checked) {
                            batchModeSongs.map { it.id }.toSet()
                        } else {
                            emptySet()
                        }
                    },
                    onAddToPlaylist = { showBatchAddDialog = true },
                    onAddToQueue = {
                        val selectedSongs = batchModeSongs.filter { it.id in batchSelectedSongIds }
                        if (!canAddToQueue || selectedSongs.isEmpty()) {
                            Toast.makeText(context, "请先在当前播放列表中选择歌曲", Toast.LENGTH_SHORT).show()
                        } else {
                            musicViewModel.addSongsToPlayQueue(selectedSongs)
                            Toast.makeText(context, "已加入队列 ${selectedSongs.size} 首歌曲", Toast.LENGTH_SHORT).show()
                        }
                    }.takeIf { canAddToQueue },
                    onFavoriteAction = {
                        val selectedSongs = batchModeSongs.filter { it.id in batchSelectedSongIds }
                        if (selectedSongs.isEmpty()) {
                            Toast.makeText(context, "请先选择歌曲", Toast.LENGTH_SHORT).show()
                        } else {
                            if (batchFavoriteActionText == "取消收藏") {
                                viewModel.removeSongsFromFavorites(selectedSongs)
                                Toast.makeText(context, "已取消收藏 ${selectedSongs.size} 首歌曲", Toast.LENGTH_SHORT).show()
                            } else {
                                viewModel.addSongsToFavorites(selectedSongs)
                                Toast.makeText(context, "已加入收藏 ${selectedSongs.size} 首歌曲", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onExit = { exitBatchMode() },
                    onRemoveFromPlaylist = if (playlistId == null) null else {
                        {
                            val selectedSongs = batchModeSongs.filter { it.id in batchSelectedSongIds }
                            if (selectedSongs.isEmpty()) {
                                Toast.makeText(context, "请先选择歌曲", Toast.LENGTH_SHORT).show()
                            } else {
                                viewModel.removeSongsFromPlaylist(selectedSongs, playlistId) { deletedCount ->
                                    if (deletedCount > 0) {
                                        Toast.makeText(context, "已从歌单移除 $deletedCount 首歌曲", Toast.LENGTH_SHORT).show()
                                        val remainingSongs = batchModeSongs.filterNot { it.id in batchSelectedSongIds }
                                        if (remainingSongs.isEmpty()) {
                                            exitBatchMode()
                                        } else {
                                            batchModeSongs = remainingSongs
                                            batchSelectedSongIds = emptySet()
                                        }
                                    } else {
                                        Toast.makeText(context, "未从歌单移除任何歌曲", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                    }
                )
            } else {
                BottomPlayerBar(
                    currentMediaItem = musicUiState.currentMediaItem,
                    isPlaying = musicUiState.isPlaying,
                    currentPosition = musicUiState.currentPosition,
                    duration = musicUiState.duration,
                    repeatMode = musicUiState.repeatMode,
                    shuffleModeEnabled = musicUiState.shuffleModeEnabled,
                    onPlayPauseClick = {
                        if (musicUiState.isPlaying) {
                            musicViewModel.pause()
                        } else {
                            musicViewModel.resume()
                        }
                    },
                    onPreviousClick = { musicViewModel.skipToPrevious() },
                    onNextClick = { musicViewModel.skipToNext() },
                    onPlayModeClick = {
                        val (newShuffle, newRepeat) = when {
                            musicUiState.shuffleModeEnabled -> false to Player.REPEAT_MODE_ONE // Random -> Single Loop
                            musicUiState.repeatMode == Player.REPEAT_MODE_ONE -> false to Player.REPEAT_MODE_ALL // Single Loop -> Sequential
                            else -> true to Player.REPEAT_MODE_ALL // Sequential -> Random
                        }
                        musicViewModel.setShuffleModeEnabled(newShuffle)
                        musicViewModel.setRepeatMode(newRepeat)
                    },
                    onClick = onPlayerClick,
                    onScanClick = onScanClick,
                    onBackupRestoreClick = onBackupRestoreClick,
                    onSettingsClick = onSettingsClick,
                    onEqualizerClick = onEqualizerClick,
                    onAboutClick = onAboutClick,
                    onSleepTimerClick = onSleepTimerClick,
                    onLogsClick = onLogsClick,
                    sleepTimerRemaining = sleepTimerState
                )
            }
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            if (isSearchActive) {
                // Search bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextField(
                        value = searchQuery,
                        onValueChange = { viewModel.updateSearchQuery(it) },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester),
                        placeholder = { Text("搜索...") },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        )
                    )
                    IconButton(onClick = { viewModel.setSearchActive(false) }) {
                        Icon(Icons.Default.Close, contentDescription = "关闭搜索")
                    }
                }
            } else {
                // Tab row with search button fixed on right
                Row(modifier = Modifier.fillMaxWidth()) {
                    ScrollableTabRow(
                        selectedTabIndex = pagerState.currentPage,
                        modifier = Modifier.weight(1f),
                        edgePadding = 0.dp
                    ) {
                        titles.forEachIndexed { index, title ->
                            Tab(
                                selected = pagerState.currentPage == index,
                                onClick = {
                                    scope.launch {
                                        pagerState.animateScrollToPage(index)
                                    }
                                },
                                text = { Text(text = title) }
                            )
                        }
                    }
                    // Fixed search button on the right
                    IconButton(
                        onClick = { viewModel.setSearchActive(true) },
                        modifier = Modifier.align(Alignment.CenterVertically)
                    ) {
                        Icon(Icons.Default.Search, contentDescription = "搜索")
                    }
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.Top
            ) { page ->
                // Log when switching tabs
                LaunchedEffect(page) {
                    val tabName = titles.getOrNull(page) ?: "Unknown"
                    Log.d("MainScreen", "Tab switched to: $tabName (index: $page)")
                    appLogger?.info("MainScreen", "Tab switched to: $tabName (index: $page)")
                }
                
                when (page) {
                    0 -> {
                        val songs by viewModel.songs.collectAsState()
                        Log.d("MainScreen", "SongList (Global): displaying ${songs.size} songs")
                        appLogger?.info("MainScreen", "SongList (Global): displaying ${songs.size} songs")
                        SongList(
                            songs = songs,
                            currentPlayingSongId = musicUiState.currentMediaItem?.mediaId,
                            onSongClick = { list, index -> 
                                Log.d("MainScreen", "SongList (Global): playing song at index $index")
                                musicViewModel.playList(list, index, PlaylistContext.GLOBAL)
                            },
                            onSongLongClick = {
                                selectedSongForActions = it
                                batchModeSongs = songs
                                batchModePlaylistId = null
                                batchFavoriteActionText = favoriteActionTextForTab(0)
                            },
                            isBatchMode = isBatchMode && pagerState.currentPage == 0,
                            selectedSongIds = batchSelectedSongIds,
                            queueDisplayBySongId = if (musicUiState.playlistContext == PlaylistContext.GLOBAL) {
                                buildQueueDisplayBySongId(playQueueSongIds)
                            } else {
                                emptyMap()
                            },
                            onBatchSongToggle = { song ->
                                batchSelectedSongIds = if (song.id in batchSelectedSongIds) {
                                    batchSelectedSongIds - song.id
                                } else {
                                    batchSelectedSongIds + song.id
                                }
                            },
                            appLogger = appLogger
                        )
                    }
                    1 -> {
                        com.zjr.hesimusic.ui.library.PlaylistTabScreen(
                            viewModel = viewModel,
                            musicViewModel = musicViewModel,
                            currentPlayingSongId = musicUiState.currentMediaItem?.mediaId,
                            initialSelectedPlaylistId = restoredPlaylistId,
                            onSongLongClick = { song, playlistId, songs ->
                                selectedSongForActions = song
                                batchModeSongs = songs
                                batchModePlaylistId = playlistId
                                batchFavoriteActionText = favoriteActionTextForTab(1)
                            },
                            isBatchMode = isBatchMode && pagerState.currentPage == PLAYLIST_TAB_INDEX,
                            selectedSongIds = batchSelectedSongIds,
                            onBatchSongToggle = { song ->
                                batchSelectedSongIds = if (song.id in batchSelectedSongIds) {
                                    batchSelectedSongIds - song.id
                                } else {
                                    batchSelectedSongIds + song.id
                                }
                            },
                            onPlaylistSongsVisibleChanged = { isPlaylistSongsVisible = it },
                            queueDisplayBySongId = buildQueueDisplayBySongId(playQueueSongIds)
                        )
                    }
                    2 -> {
                        val favoriteSongs by viewModel.favoriteSongs.collectAsState()
                        Log.d("MainScreen", "SongList (Favorites): displaying ${favoriteSongs.size} songs")
                        appLogger?.info("MainScreen", "SongList (Favorites): displaying ${favoriteSongs.size} songs")
                        SongList(
                            songs = favoriteSongs,
                            currentPlayingSongId = musicUiState.currentMediaItem?.mediaId,
                            onSongClick = { list, index -> 
                                Log.d("MainScreen", "SongList (Favorites): playing song at index $index")
                                musicViewModel.playList(list, index, PlaylistContext.FAVORITES)
                            },
                            onSongLongClick = {
                                selectedSongForActions = it
                                batchModeSongs = favoriteSongs
                                batchModePlaylistId = null
                                batchFavoriteActionText = favoriteActionTextForTab(2)
                            },
                            isBatchMode = isBatchMode && pagerState.currentPage == 2,
                            selectedSongIds = batchSelectedSongIds,
                            queueDisplayBySongId = if (musicUiState.playlistContext == PlaylistContext.FAVORITES) {
                                buildQueueDisplayBySongId(playQueueSongIds)
                            } else {
                                emptyMap()
                            },
                            onBatchSongToggle = { song ->
                                batchSelectedSongIds = if (song.id in batchSelectedSongIds) {
                                    batchSelectedSongIds - song.id
                                } else {
                                    batchSelectedSongIds + song.id
                                }
                            },
                            appLogger = appLogger
                        )
                    }
                    3 -> {
                        Log.d("MainScreen", "FolderList view activated")
                        appLogger?.info("MainScreen", "FolderList view activated")
                        FolderList(
                            viewModel = viewModel,
                            initialPath = "/storage/emulated/0",
                            startPath = restoredFolderPath,
                            currentPlayingSongId = musicUiState.currentMediaItem?.mediaId,
                            onSongClick = { list, index, folderPath -> 
                                Log.d("MainScreen", "FolderList: playing song at index $index in folder $folderPath")
                                musicViewModel.playList(list, index, PlaylistContext(PlaylistType.FOLDER, folderPath))
                            },
                            appLogger = appLogger
                        )
                    }
                    4 -> {
                        val artists by viewModel.artists.collectAsState()
                        Log.d("MainScreen", "ArtistList: displaying ${artists.size} artists")
                        appLogger?.info("MainScreen", "ArtistList: displaying ${artists.size} artists")
                        ArtistList(artists = artists, onArtistClick = onArtistClick, appLogger = appLogger)
                    }
                    5 -> {
                        val albums by viewModel.albums.collectAsState()
                        Log.d("MainScreen", "AlbumList: displaying ${albums.size} albums")
                        appLogger?.info("MainScreen", "AlbumList: displaying ${albums.size} albums")
                        AlbumList(albums = albums, onAlbumClick = onAlbumClick, appLogger = appLogger)
                    }
                }
            }

            SongActionHost(
                selectedSong = selectedSongForActions,
                playlists = playlists,
                onDismiss = { selectedSongForActions = null },
                onAddToPlaylist = { song, playlistId -> viewModel.addSongToPlaylist(song, playlistId) },
                onCreatePlaylist = { name, onCreated -> viewModel.createPlaylist(name, onCreated) },
                onBatchManage = { song ->
                    isBatchMode = true
                    batchModeTabIndex = pagerState.currentPage
                    batchSelectedSongIds = setOf(song.id)
                },
                onHideSong = { song -> viewModel.hideSong(song) },
                onDeleteSong = { song, onResult -> viewModel.deleteSongFile(song, onResult) },
                onLoadMetadata = { song, onLoaded -> viewModel.loadSongMetadata(song, onLoaded) }
            )
            if (showBatchAddDialog) {
                AddToPlaylistDialog(
                    playlists = playlists,
                    resetKey = batchSelectedSongIds,
                    onDismiss = { showBatchAddDialog = false },
                    onAdd = { playlistId ->
                        val selectedSongs = batchModeSongs.filter { it.id in batchSelectedSongIds }
                        if (selectedSongs.isEmpty()) {
                            Toast.makeText(context, "请先选择歌曲", Toast.LENGTH_SHORT).show()
                            showBatchAddDialog = false
                            return@AddToPlaylistDialog
                        }
                        viewModel.addSongsToPlaylist(selectedSongs, playlistId) {
                            Toast.makeText(context, "已加入歌单 ${selectedSongs.size} 首歌曲", Toast.LENGTH_SHORT).show()
                            showBatchAddDialog = false
                        }
                    },
                    onCreate = { name ->
                        viewModel.createPlaylist(name) { playlistId ->
                            if (playlistId != null) {
                                val selectedSongs = batchModeSongs.filter { it.id in batchSelectedSongIds }
                                viewModel.addSongsToPlaylist(selectedSongs, playlistId) {
                                    Toast.makeText(context, "歌单已创建并加入 ${selectedSongs.size} 首歌曲", Toast.LENGTH_SHORT).show()
                                    showBatchAddDialog = false
                                }
                            }
                        }
                    }
                )
            }
        }
    }
}

internal fun playlistTypeToTabIndex(type: PlaylistType): Int = when (type) {
    PlaylistType.GLOBAL -> 0
    PlaylistType.PLAYLIST -> 1
    PlaylistType.FAVORITES -> 2
    PlaylistType.FOLDER -> 3
    PlaylistType.ARTIST -> 4
    PlaylistType.ALBUM -> 5
}

internal fun favoriteActionTextForTab(tabIndex: Int): String {
    return if (tabIndex == 2) "取消收藏" else "加入收藏"
}

internal fun parsePlaylistId(value: String): Long? {
    return value.toLongOrNull()?.takeIf { it > 0L }
}
