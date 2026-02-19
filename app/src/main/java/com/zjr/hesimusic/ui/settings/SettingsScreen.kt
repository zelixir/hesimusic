package com.zjr.hesimusic.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBackClick: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val minAlbumTrackCount by viewModel.minAlbumTrackCount.collectAsState()
    val minArtistTrackCount by viewModel.minArtistTrackCount.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
        ) {
            Text("专辑列表最少曲目数：$minAlbumTrackCount")
            Slider(
                value = minAlbumTrackCount.toFloat(),
                onValueChange = { viewModel.updateMinAlbumTrackCount(it.toInt()) },
                valueRange = 0f..50f,
                steps = 49
            )
            Text("歌手列表最少曲目数：$minArtistTrackCount")
            Slider(
                value = minArtistTrackCount.toFloat(),
                onValueChange = { viewModel.updateMinArtistTrackCount(it.toInt()) },
                valueRange = 0f..50f,
                steps = 49
            )
        }
    }
}
