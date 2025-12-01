package com.zjr.hesimusic.ui.main

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.Player
import com.zjr.hesimusic.data.model.Album
import com.zjr.hesimusic.data.model.Artist
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
                            onSongClick = { list, index -> musicViewModel.playList(list, index) }
                        )
                    }
                    1 -> {
                        val favoriteSongs by viewModel.favoriteSongs.collectAsState()
                        SongList(
                            songs = favoriteSongs,
                            currentPlayingSongId = musicUiState.currentMediaItem?.mediaId,
                            onSongClick = { list, index -> musicViewModel.playList(list, index) }
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
                            onSongClick = { list, index -> musicViewModel.playList(list, index) }
                        )
                    }
                }
            }
        }
    }
}
