package com.zjr.hesimusic.ui.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import com.zjr.hesimusic.data.model.Song
import com.zjr.hesimusic.ui.common.MusicViewModel
import com.zjr.hesimusic.ui.main.BottomPlayerBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongListScreen(
    type: String,
    value: String,
    viewModel: LibraryViewModel = hiltViewModel(),
    musicViewModel: MusicViewModel = hiltViewModel(),
    onBack: () -> Unit,
    onSongClick: (Song) -> Unit,
    onScanClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onEqualizerClick: () -> Unit,
    onAboutClick: () -> Unit,
    onSleepTimerClick: () -> Unit
) {
    val songsFlow = when (type) {
        "artist" -> viewModel.getSongsByArtist(value)
        "album" -> viewModel.getSongsByAlbum(value)
        else -> viewModel.songs // Fallback
    }
    
    val songs by songsFlow.collectAsState(initial = emptyList())
    val musicUiState by musicViewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = value) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        bottomBar = {
            BottomPlayerBar(
                currentMediaItem = musicUiState.currentMediaItem,
                isPlaying = musicUiState.isPlaying,
                currentPosition = musicUiState.currentPosition,
                duration = musicUiState.duration,
                onPlayPauseClick = {
                    if (musicUiState.isPlaying) {
                        musicViewModel.pause()
                    } else {
                        musicViewModel.resume()
                    }
                },
                onPreviousClick = { musicViewModel.skipToPrevious() },
                onNextClick = { musicViewModel.skipToNext() },
                onClick = { /* TODO: Navigate to player if needed, or just expand */ },
                onScanClick = onScanClick,
                onSettingsClick = onSettingsClick,
                onEqualizerClick = onEqualizerClick,
                onAboutClick = onAboutClick,
                onSleepTimerClick = onSleepTimerClick
            )
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            SongList(
                songs = songs,
                currentPlayingSongId = musicUiState.currentMediaItem?.mediaId,
                onSongClick = { list, index -> musicViewModel.playList(list, index) }
            )
        }
    }
}
