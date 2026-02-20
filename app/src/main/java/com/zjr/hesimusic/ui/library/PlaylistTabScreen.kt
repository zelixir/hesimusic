package com.zjr.hesimusic.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zjr.hesimusic.data.model.Playlist
import com.zjr.hesimusic.data.model.Song
import com.zjr.hesimusic.data.preferences.PlaylistContext
import com.zjr.hesimusic.data.preferences.PlaylistType
import com.zjr.hesimusic.ui.common.MusicListItem
import com.zjr.hesimusic.ui.common.MusicViewModel

@Composable
fun PlaylistTabScreen(
    viewModel: LibraryViewModel,
    musicViewModel: MusicViewModel,
    currentPlayingSongId: String?,
    onSongLongClick: (Song, Long, List<Song>) -> Unit,
    isBatchMode: Boolean,
    selectedSongIds: Set<Long>,
    onBatchSongToggle: (Song) -> Unit,
    onPlaylistSongsVisibleChanged: (Boolean) -> Unit
) {
    val playlists by viewModel.playlists.collectAsState()
    var selectedPlaylistId by remember { mutableLongStateOf(0L) }
    var selectedPlaylistForAction by remember { mutableStateOf<Playlist?>(null) }
    LaunchedEffect(selectedPlaylistId) {
        onPlaylistSongsVisibleChanged(selectedPlaylistId != 0L)
    }

    if (selectedPlaylistId == 0L) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(playlists, key = { it.id }) { playlist ->
                val songCount by viewModel.getPlaylistSongCount(playlist.id).collectAsState(initial = 0)
                MusicListItem(
                    title = playlist.name,
                    subtitle = "$songCount 首歌曲",
                    onClick = { selectedPlaylistId = playlist.id },
                    onLongClick = { selectedPlaylistForAction = playlist }
                )
            }
        }
    } else {
        val songs by viewModel.getSongsByPlaylist(selectedPlaylistId).collectAsState(initial = emptyList())
        Column(modifier = Modifier.fillMaxSize()) {
            TextButton(onClick = { selectedPlaylistId = 0L }, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text("返回歌单列表")
            }
            SongList(
                songs = songs,
                currentPlayingSongId = currentPlayingSongId,
                onSongClick = { list, index ->
                    musicViewModel.playList(
                        list,
                        index,
                        PlaylistContext(PlaylistType.PLAYLIST, selectedPlaylistId.toString())
                    )
                },
                onSongLongClick = { song -> onSongLongClick(song, selectedPlaylistId, songs) },
                isBatchMode = isBatchMode,
                selectedSongIds = selectedSongIds,
                onBatchSongToggle = onBatchSongToggle,
                modifier = Modifier.fillMaxSize()
            )
        }
    }

    selectedPlaylistForAction?.let { playlist ->
        AlertDialog(
            onDismissRequest = { selectedPlaylistForAction = null },
            title = { Text(playlist.name) },
            text = {
                Text(
                    text = "删除歌单",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .clickable {
                            viewModel.deletePlaylist(playlist.id)
                            selectedPlaylistForAction = null
                        }
                )
            },
            confirmButton = {
                TextButton(onClick = { selectedPlaylistForAction = null }) {
                    Text("关闭")
                }
            }
        )
    }
}
