package com.zjr.hesimusic.ui.test

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.zjr.hesimusic.data.model.Song
import com.zjr.hesimusic.ui.common.MusicViewModel

@Composable
fun PlayerTestScreen(
    viewModel: PlayerTestViewModel = hiltViewModel(),
    musicViewModel: MusicViewModel = hiltViewModel()
) {
    val songs by viewModel.songs.collectAsState()
    val musicState by musicViewModel.uiState.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f)
        ) {
            items(songs) { song ->
                SongItem(song = song, onClick = {
                    musicViewModel.play(song)
                })
            }
        }

        if (musicState.currentMediaItem != null) {
            MiniPlayer(
                title = musicState.currentMediaItem?.mediaMetadata?.title.toString(),
                isPlaying = musicState.isPlaying,
                onPlayPause = {
                    if (musicState.isPlaying) musicViewModel.pause() else musicViewModel.resume()
                },
                onNext = { musicViewModel.skipToNext() },
                onPrev = { musicViewModel.skipToPrevious() }
            )
        }
    }
}

@Composable
fun SongItem(song: Song, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(song.title) },
        supportingContent = { Text("${song.artist} - ${song.album}") },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

@Composable
fun MiniPlayer(
    title: String,
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit
) {
    Surface(
        tonalElevation = 8.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium
            )
            IconButton(onClick = onPrev) {
                Text("<")
            }
            IconButton(onClick = onPlayPause) {
                Text(if (isPlaying) "||" else ">")
            }
            IconButton(onClick = onNext) {
                Text(">")
            }
        }
    }
}
