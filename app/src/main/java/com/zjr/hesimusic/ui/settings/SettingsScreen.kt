package com.zjr.hesimusic.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.zjr.hesimusic.ui.library.LibraryViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: LibraryViewModel = hiltViewModel(),
    onBackClick: () -> Unit
) {
    val hiddenSongs by viewModel.hiddenSongs.collectAsState()
    var showHiddenManager by remember { mutableStateOf(false) }

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
        if (!showHiddenManager) {
            Column(modifier = Modifier.padding(innerPadding)) {
                Text(
                    text = "管理隐藏歌曲",
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showHiddenManager = true }
                        .padding(16.dp)
                )
            }
        } else {
            Column(modifier = Modifier.padding(innerPadding)) {
                TextButton(onClick = { showHiddenManager = false }) {
                    Text("返回")
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(hiddenSongs, key = { "${it.filePath}-${it.startPosition}" }) { hiddenSong ->
                        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                            Text(text = hiddenSong.filePath)
                            TextButton(onClick = { viewModel.unhideSong(hiddenSong) }) {
                                Text("取消隐藏")
                            }
                        }
                    }
                }
            }
        }
    }
}
