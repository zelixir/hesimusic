package com.zjr.hesimusic.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zjr.hesimusic.data.model.Playlist
import com.zjr.hesimusic.data.model.SmartPlaylist
import com.zjr.hesimusic.data.model.Song
import com.zjr.hesimusic.data.preferences.PlaylistContext
import com.zjr.hesimusic.data.preferences.PlaylistType
import com.zjr.hesimusic.ui.common.MusicListItem
import com.zjr.hesimusic.ui.common.MusicViewModel
import com.zjr.hesimusic.ui.common.SortModeMenuButton

/** SQL 歌单在 PlaylistContext 中的 value 前缀，与静态歌单的纯数字 id 区分。 */
const val SMART_PLAYLIST_VALUE_PREFIX = "smart:"

fun smartPlaylistContext(smartId: Long) = PlaylistContext(
    PlaylistType.PLAYLIST,
    "$SMART_PLAYLIST_VALUE_PREFIX$smartId"
)

@Composable
fun PlaylistTabScreen(
    viewModel: LibraryViewModel,
    musicViewModel: MusicViewModel,
    currentPlayingSongId: String?,
    initialSelectedPlaylistId: Long? = null,
    onSongLongClick: (Song, Long, List<Song>) -> Unit,
    onSmartSongLongClick: (Song, Long, List<Song>) -> Unit,
    isBatchMode: Boolean,
    selectedSongIds: Set<Long>,
    onBatchSongToggle: (Song) -> Unit,
    onPlaylistSongsVisibleChanged: (Boolean) -> Unit,
    queueDisplayBySongId: Map<Long, String> = emptyMap()
) {
    val musicUiState by musicViewModel.uiState.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    val smartPlaylists by viewModel.smartPlaylists.collectAsState()
    var selectedPlaylistId by rememberSaveable(initialSelectedPlaylistId) {
        mutableLongStateOf(initialSelectedPlaylistId ?: 0L)
    }
    var selectedSmartId by rememberSaveable { mutableLongStateOf(0L) }
    var selectedPlaylistForAction by remember { mutableStateOf<Playlist?>(null) }
    var selectedSmartForAction by remember { mutableStateOf<SmartPlaylist?>(null) }
    var renamingPlaylist by remember { mutableStateOf<Playlist?>(null) }
    var renamingSmart by remember { mutableStateOf<SmartPlaylist?>(null) }
    LaunchedEffect(selectedPlaylistId, selectedSmartId) {
        onPlaylistSongsVisibleChanged(selectedPlaylistId != 0L || selectedSmartId != 0L)
    }
    BackHandler(enabled = (selectedPlaylistId != 0L || selectedSmartId != 0L) && !isBatchMode) {
        selectedPlaylistId = 0L
        selectedSmartId = 0L
    }

    if (selectedPlaylistId == 0L && selectedSmartId == 0L) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(playlists, key = { "static_${it.id}" }) { playlist ->
                val songCount by viewModel.getPlaylistSongCount(playlist.id).collectAsState(initial = 0)
                MusicListItem(
                    title = playlist.name,
                    subtitle = "$songCount 首歌曲",
                    onClick = { selectedPlaylistId = playlist.id },
                    onLongClick = { selectedPlaylistForAction = playlist }
                )
            }
            items(smartPlaylists, key = { "smart_${it.id}" }) { smart ->
                val songCount by viewModel.getSmartPlaylistSongCount(smart.id).collectAsState(initial = 0)
                MusicListItem(
                    title = smart.name,
                    subtitle = "SQL 歌单 · $songCount 首歌曲",
                    icon = Icons.Default.AutoAwesome,
                    onClick = { selectedSmartId = smart.id },
                    onLongClick = { selectedSmartForAction = smart }
                )
            }
        }
    } else if (selectedSmartId != 0L) {
        val smartId = selectedSmartId
        val songs by viewModel.getSmartPlaylistSongs(smartId).collectAsState(initial = emptyList())
        val smartContext = smartPlaylistContext(smartId)
        // 歌曲列表排序模式（按列表维度持久化）
        var sortMode by remember { mutableStateOf(viewModel.getSortMode("smart:$smartId")) }
        Column(modifier = Modifier.fillMaxSize()) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { selectedSmartId = 0L }, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text("返回歌单列表")
                }
                SortModeMenuButton(
                    sortMode = sortMode,
                    onToggle = {
                        val newMode = sortMode.toggled()
                        viewModel.setSortMode("smart:$smartId", newMode)
                        sortMode = newMode
                    }
                )
            }
            SongList(
                songs = songs,
                sortMode = sortMode,
                currentPlayingSongId = currentPlayingSongId,
                onSongClick = { list, index ->
                    musicViewModel.playList(list, index, smartContext)
                },
                onSongLongClick = { song -> onSmartSongLongClick(song, smartId, songs) },
                isBatchMode = isBatchMode,
                selectedSongIds = selectedSongIds,
                queueDisplayBySongId = if (musicUiState.playlistContext == smartContext) {
                    queueDisplayBySongId
                } else {
                    emptyMap()
                },
                onBatchSongToggle = onBatchSongToggle,
                modifier = Modifier.fillMaxSize()
            )
        }
    } else {
        val songs by viewModel.getSongsByPlaylist(selectedPlaylistId).collectAsState(initial = emptyList())
        // 歌曲列表排序模式（按列表维度持久化）
        var sortMode by remember { mutableStateOf(viewModel.getSortMode("playlist:$selectedPlaylistId")) }
        Column(modifier = Modifier.fillMaxSize()) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { selectedPlaylistId = 0L }, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text("返回歌单列表")
                }
                SortModeMenuButton(
                    sortMode = sortMode,
                    onToggle = {
                        val newMode = sortMode.toggled()
                        viewModel.setSortMode("playlist:$selectedPlaylistId", newMode)
                        sortMode = newMode
                    }
                )
            }
            SongList(
                songs = songs,
                sortMode = sortMode,
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
                queueDisplayBySongId = if (musicUiState.playlistContext == PlaylistContext(PlaylistType.PLAYLIST, selectedPlaylistId.toString())) {
                    queueDisplayBySongId
                } else {
                    emptyMap()
                },
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
                Column {
                    Text(
                        text = "重命名",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .clickable {
                                selectedPlaylistForAction = null
                                renamingPlaylist = playlist
                            }
                    )
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
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedPlaylistForAction = null }) {
                    Text("关闭")
                }
            }
        )
    }

    selectedSmartForAction?.let { smart ->
        AlertDialog(
            onDismissRequest = { selectedSmartForAction = null },
            title = { Text(smart.name) },
            text = {
                Column {
                    Text(
                        text = "重命名",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .clickable {
                                selectedSmartForAction = null
                                renamingSmart = smart
                            }
                    )
                    Text(
                        text = "删除 SQL 歌单（不影响歌曲文件）",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .clickable {
                                viewModel.deleteSmartPlaylist(smart.id)
                                selectedSmartForAction = null
                            }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedSmartForAction = null }) {
                    Text("关闭")
                }
            }
        )
    }

    renamingPlaylist?.let { playlist ->
        RenameDialog(
            initialName = playlist.name,
            onConfirm = { newName -> viewModel.renamePlaylist(playlist.id, newName) },
            onDismiss = { renamingPlaylist = null }
        )
    }

    renamingSmart?.let { smart ->
        RenameDialog(
            initialName = smart.name,
            onConfirm = { newName -> viewModel.renameSmartPlaylist(smart.id, newName) },
            onDismiss = { renamingSmart = null }
        )
    }
}

@Composable
private fun RenameDialog(
    initialName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名歌单") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = { onConfirm(text); onDismiss() }
            ) {
                Text("确认")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
