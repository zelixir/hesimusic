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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongListScreen(
    type: String,
    value: String,
    viewModel: LibraryViewModel = hiltViewModel(),
    musicViewModel: MusicViewModel = hiltViewModel(),
    onBack: () -> Unit,
    onSongClick: (Song) -> Unit
) {
    val songsFlow = when (type) {
        "artist" -> viewModel.getSongsByArtist(value)
        "album" -> viewModel.getSongsByAlbum(value)
        else -> viewModel.songs // Fallback
    }
    
    val songs by songsFlow.collectAsState(initial = emptyList())

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
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            SongList(
                songs = songs,
                onSongClick = { list, index -> musicViewModel.playList(list, index) }
            )
        }
    }
}
