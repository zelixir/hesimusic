package com.zjr.hesimusic.ui.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
    onSongLongClick: (Song) -> Unit
) {
    val playlists by viewModel.playlists.collectAsState()
    var selectedPlaylistId by remember { mutableLongStateOf(0L) }

    if (selectedPlaylistId == 0L) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(playlists, key = { it.id }) { playlist ->
                MusicListItem(
                    title = playlist.name,
                    subtitle = "歌单",
                    onClick = { selectedPlaylistId = playlist.id }
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
                onSongLongClick = onSongLongClick,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
