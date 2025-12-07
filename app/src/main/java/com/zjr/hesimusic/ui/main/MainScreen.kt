package com.zjr.hesimusic.ui.main

import android.util.Log
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
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
    val isSearchActive by viewModel.isSearchActive.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val focusRequester = remember { FocusRequester() }

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
                when (page) {
                    0 -> {
                        val songs by viewModel.songs.collectAsState()
                        Log.d("MainScreen", "SongList (Global): displaying ${songs.size} songs")
                        SongList(
                            songs = songs,
                            currentPlayingSongId = musicUiState.currentMediaItem?.mediaId,
                            onSongClick = { list, index -> 
                                Log.d("MainScreen", "SongList (Global): playing song at index $index")
                                musicViewModel.playList(list, index, PlaylistContext.GLOBAL)
                            }
                        )
                    }
                    1 -> {
                        val favoriteSongs by viewModel.favoriteSongs.collectAsState()
                        Log.d("MainScreen", "SongList (Favorites): displaying ${favoriteSongs.size} songs")
                        SongList(
                            songs = favoriteSongs,
                            currentPlayingSongId = musicUiState.currentMediaItem?.mediaId,
                            onSongClick = { list, index -> 
                                Log.d("MainScreen", "SongList (Favorites): playing song at index $index")
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
                            onSongClick = { list, index, folderPath -> 
                                Log.d("MainScreen", "FolderList: playing song at index $index in folder $folderPath")
                                musicViewModel.playList(list, index, PlaylistContext(PlaylistType.FOLDER, folderPath))
                            }
                        )
                    }
                }
            }
        }
    }
}
