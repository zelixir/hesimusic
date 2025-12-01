package com.zjr.hesimusic.ui.main

import android.util.Log
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.Player
import com.zjr.hesimusic.data.model.Album
import com.zjr.hesimusic.data.model.Artist
import com.zjr.hesimusic.data.preferences.PlaylistContext
import com.zjr.hesimusic.data.preferences.PlaylistType
import com.zjr.hesimusic.ui.common.MusicViewModel
import com.zjr.hesimusic.ui.library.AlbumList
import com.zjr.hesimusic.ui.library.ArtistList
import com.zjr.hesimusic.ui.library.FolderList
import com.zjr.hesimusic.ui.library.LibraryViewModel
import com.zjr.hesimusic.ui.library.SongList
import kotlinx.coroutines.launch

private const val TAG = "MainScreen"

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MainScreen(
    viewModel: LibraryViewModel = hiltViewModel(),
    musicViewModel: MusicViewModel = hiltViewModel(),
    onArtistClick: (Artist) -> Unit,
    onAlbumClick: (Album) -> Unit,
    onPlayerClick: () -> Unit,
    onScanClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onEqualizerClick: () -> Unit,
    onAboutClick: () -> Unit,
    onSleepTimerClick: () -> Unit
) {
    val pagerState = rememberPagerState(pageCount = { 5 })
    val scope = rememberCoroutineScope()
    val titles = listOf("歌曲", "收藏", "歌手", "专辑", "文件夹")
    val musicUiState by musicViewModel.uiState.collectAsState()
    val savedPlaylistContext by musicViewModel.savedPlaylistContext.collectAsState()
    
    // Remember the saved folder path for FolderList to use
    var savedFolderPath by remember { mutableStateOf<String?>(null) }
    
    // Handle restoring tab position based on saved playlist context
    // Only execute once when savedPlaylistContext is available
    LaunchedEffect(savedPlaylistContext) {
        savedPlaylistContext?.let { context ->
            Log.d(TAG, "LaunchedEffect: restoring tab for context type=${context.type}, value=${context.value}")
            val targetPage = when (context.type) {
                PlaylistType.GLOBAL -> 0
                PlaylistType.FAVORITES -> 1
                PlaylistType.ARTIST -> 2  // Artist tab - but will need to navigate to detail
                PlaylistType.ALBUM -> 3   // Album tab - but will need to navigate to detail
                PlaylistType.FOLDER -> 4  // Folder tab
            }
            
            // Navigate to the correct tab
            if (pagerState.currentPage != targetPage) {
                Log.d(TAG, "LaunchedEffect: scrolling from ${pagerState.currentPage} to $targetPage")
                pagerState.scrollToPage(targetPage)
            }
            
            // Handle different context types
            when (context.type) {
                PlaylistType.ARTIST -> {
                    Log.d(TAG, "LaunchedEffect: should navigate to artist detail: ${context.value}")
                    // The navigation to artist detail will be handled by MainActivity
                }
                PlaylistType.ALBUM -> {
                    Log.d(TAG, "LaunchedEffect: should navigate to album detail: ${context.value}")
                    // The navigation to album detail will be handled by MainActivity
                }
                PlaylistType.FOLDER -> {
                    // Save the folder path for FolderList to use
                    Log.d(TAG, "LaunchedEffect: saving folder path for FolderList: ${context.value}")
                    savedFolderPath = context.value
                    // Consume the context here
                    musicViewModel.consumeSavedPlaylistContext()
                }
                else -> {
                    // For GLOBAL, FAVORITES - just stay on the tab and consume context
                    musicViewModel.consumeSavedPlaylistContext()
                }
            }
        }
    }

    Scaffold(
        bottomBar = {
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
                onSettingsClick = onSettingsClick,
                onEqualizerClick = onEqualizerClick,
                onAboutClick = onAboutClick,
                onSleepTimerClick = onSleepTimerClick
            )
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            ScrollableTabRow(
                selectedTabIndex = pagerState.currentPage,
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

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.Top
            ) { page ->
                when (page) {
                    0 -> {
                        val songs by viewModel.songs.collectAsState()
                        SongList(
                            songs = songs,
                            currentPlayingSongId = musicUiState.currentMediaItem?.mediaId,
                            onSongClick = { list, index -> 
                                Log.d(TAG, "SongList (Global): playing song at index $index")
                                musicViewModel.playList(list, index, PlaylistContext.GLOBAL) 
                            }
                        )
                    }
                    1 -> {
                        val favoriteSongs by viewModel.favoriteSongs.collectAsState()
                        SongList(
                            songs = favoriteSongs,
                            currentPlayingSongId = musicUiState.currentMediaItem?.mediaId,
                            onSongClick = { list, index -> 
                                Log.d(TAG, "SongList (Favorites): playing song at index $index")
                                musicViewModel.playList(list, index, PlaylistContext.FAVORITES) 
                            }
                        )
                    }
                    2 -> {
                        val artists by viewModel.artists.collectAsState()
                        ArtistList(artists = artists, onArtistClick = onArtistClick)
                    }
                    3 -> {
                        val albums by viewModel.albums.collectAsState()
                        AlbumList(albums = albums, onAlbumClick = onAlbumClick)
                    }
                    4 -> {
                        FolderList(
                            viewModel = viewModel,
                            currentPlayingSongId = musicUiState.currentMediaItem?.mediaId,
                            savedFolderPath = savedFolderPath,
                            onSongClick = { list, index, folderPath -> 
                                Log.d(TAG, "FolderList: playing song at index $index from folder $folderPath")
                                musicViewModel.playList(list, index, PlaylistContext(PlaylistType.FOLDER, folderPath)) 
                            }
                        )
                    }
                }
            }
        }
    }
}
