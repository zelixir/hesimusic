package com.zjr.hesimusic.ui.library

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.zjr.hesimusic.data.model.Playlist
import com.zjr.hesimusic.data.model.Song
import com.zjr.hesimusic.utils.TimeFormatter
import java.io.File
import java.util.Locale

@Composable
fun SongActionHost(
    selectedSong: Song?,
    playlists: List<Playlist>,
    onDismiss: () -> Unit,
    onAddToPlaylist: (Song, Long) -> Unit,
    onCreatePlaylist: (String, (Long?) -> Unit) -> Unit,
    onHideSong: (Song) -> Unit,
    onDeleteSong: (Song, (Boolean) -> Unit) -> Unit,
    onLoadMetadata: (Song, (Map<String, String>) -> Unit) -> Unit
) {
    val context = LocalContext.current
    var showAddDialog by remember(selectedSong) { mutableStateOf(false) }
    var showInfoDialog by remember(selectedSong) { mutableStateOf(false) }
    var showDeleteDialog by remember(selectedSong) { mutableStateOf(false) }
    var metadata by remember(selectedSong) { mutableStateOf<Map<String, String>>(emptyMap()) }

    if (selectedSong != null && !showAddDialog && !showInfoDialog && !showDeleteDialog) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(selectedSong.title) },
            text = {
                Column {
                    Text(
                        text = "添加到歌单",
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showAddDialog = true }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "歌曲信息",
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onLoadMetadata(selectedSong) { loaded -> metadata = loaded }
                                showInfoDialog = true
                            }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "从曲库中隐藏歌曲",
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onHideSong(selectedSong)
                                Toast.makeText(context, "已隐藏", Toast.LENGTH_SHORT).show()
                                onDismiss()
                            }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "删除歌曲文件",
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showDeleteDialog = true }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) {
                    Text("关闭")
                }
            }
        )
    }

    if (selectedSong != null && showAddDialog) {
        AddToPlaylistDialog(
            song = selectedSong,
            playlists = playlists,
            onDismiss = { showAddDialog = false },
            onAdd = { playlistId ->
                onAddToPlaylist(selectedSong, playlistId)
                Toast.makeText(context, "已添加到歌单", Toast.LENGTH_SHORT).show()
                showAddDialog = false
                onDismiss()
            },
            onCreate = { name ->
                onCreatePlaylist(name) { playlistId ->
                    if (playlistId != null) {
                        onAddToPlaylist(selectedSong, playlistId)
                        Toast.makeText(context, "歌单已创建并添加歌曲", Toast.LENGTH_SHORT).show()
                        showAddDialog = false
                        onDismiss()
                    }
                }
            }
        )
    }

    if (selectedSong != null && showInfoDialog) {
        SongInfoDialog(
            song = selectedSong,
            metadata = metadata,
            onDismiss = {
                showInfoDialog = false
                onDismiss()
            }
        )
    }

    if (selectedSong != null && showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除歌曲文件") },
            text = { Text("确定要删除文件 ${File(selectedSong.filePath).name} 吗？此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteSong(selectedSong) { deleted ->
                        Toast.makeText(context, if (deleted) "文件已删除" else "删除失败", Toast.LENGTH_SHORT).show()
                    }
                    showDeleteDialog = false
                    onDismiss()
                }) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun AddToPlaylistDialog(
    song: Song,
    playlists: List<Playlist>,
    onDismiss: () -> Unit,
    onAdd: (Long) -> Unit,
    onCreate: (String) -> Unit
) {
    var newPlaylistName by remember(song.id) { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加到歌单") },
        text = {
            Column {
                if (playlists.isNotEmpty()) {
                    LazyColumn(modifier = Modifier.height(180.dp)) {
                        items(playlists) { playlist ->
                            Text(
                                text = playlist.name,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onAdd(playlist.id) }
                            )
                            HorizontalDivider()
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }
                OutlinedTextField(
                    value = newPlaylistName,
                    onValueChange = { newPlaylistName = it },
                    label = { Text("新建歌单") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(newPlaylistName) }) {
                Text("新建并添加")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun SongInfoDialog(
    song: Song,
    metadata: Map<String, String>,
    onDismiss: () -> Unit
) {
    val file = File(song.filePath)
    val bitrate = metadata["BITRATE"]?.let { "$it kbps" } ?: "未知"
    val sampleRate = metadata["SAMPLE_RATE"]?.let { "$it Hz" } ?: "未知"
    val tagType = when (file.extension.lowercase(Locale.getDefault())) {
        "mp3" -> "id3"
        "ape" -> "ape"
        else -> "unknown"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("歌曲信息") },
        text = {
            Column {
                Text("歌手: ${song.artist}")
                Text("专辑: ${song.album}")
                Text("时长: ${TimeFormatter.formatTime(song.duration)}")
                Text("文件名: ${file.name}")
                Text("大小: ${formatSize(song.size)}")
                Text("格式: ${song.mimeType}")
                Text("比特率: $bitrate")
                Text("采样率: $sampleRate")
                Text("标签类型: $tagType")
                Text("路径: ${song.filePath}")
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}

private fun formatSize(size: Long): String {
    if (size <= 0) return "未知"
    val kb = 1024.0
    val mb = kb * 1024
    return if (size >= mb) {
        String.format(Locale.getDefault(), "%.2f MB", size / mb)
    } else {
        String.format(Locale.getDefault(), "%.2f KB", size / kb)
    }
}
