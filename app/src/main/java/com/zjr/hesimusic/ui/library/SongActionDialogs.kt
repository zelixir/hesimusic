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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.zjr.hesimusic.data.model.Playlist
import com.zjr.hesimusic.data.model.Song
import com.zjr.hesimusic.data.model.SongLoop
import com.zjr.hesimusic.loop.analysis.LoopAnalysisManager
import com.zjr.hesimusic.ui.common.doubleScaled
import com.zjr.hesimusic.ui.common.largeScaled
import com.zjr.hesimusic.utils.TimeFormatter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject
import java.io.File
import java.util.Locale

@Composable
fun SongActionHost(
    selectedSong: Song?,
    playlists: List<Playlist>,
    onDismiss: () -> Unit,
    onAddToPlaylist: (Song, Long) -> Unit,
    onCreatePlaylist: (String, (Long?) -> Unit) -> Unit,
    onBatchManage: (Song) -> Unit,
    onHideSong: (Song) -> Unit,
    onDeleteSong: (Song, (Boolean) -> Unit) -> Unit,
    onLoadMetadata: (Song, (Map<String, String>) -> Unit) -> Unit,
    onAddToQueue: ((Song) -> Unit)? = null,
    /**
     * 「无缝循环」:菜单只发事件(契约 §1)。第二个参数 = 当前 song_loop 行的 confirmed
     * (null = 无行/查询失败,false = 临时结果,true = 最终结果),由调用方决定
     * 「直接播放 + REPEAT_ONE」还是「导航到分析页」。
     */
    onSeamlessLoop: ((Song, Boolean?) -> Unit)? = null,
    /** 「重新分析」:仅已有结果时显示,导航到分析页(页内加载已存结果,不自动重分析)。 */
    onLoopReanalyze: ((Song) -> Unit)? = null,
    /** 「取消最终结果」:仅 confirmed=1 时显示,调用方做 demoteFinal。 */
    onLoopDemote: ((Song) -> Unit)? = null,
    /** 「删除分析结果」:仅已有结果时显示(确认框在本菜单内),调用方做 deleteResult。 */
    onLoopDelete: ((Song) -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val loopEntryViewModel: LoopEntryViewModel = hiltViewModel()
    var showAddDialog by remember(selectedSong) { mutableStateOf(false) }
    var showInfoDialog by remember(selectedSong) { mutableStateOf(false) }
    var showHideConfirmDialog by remember(selectedSong) { mutableStateOf(false) }
    var showDeleteDialog by remember(selectedSong) { mutableStateOf(false) }
    var showDeleteLoopDialog by remember(selectedSong) { mutableStateOf(false) }
    var metadata by remember(selectedSong) { mutableStateOf<Map<String, String>>(emptyMap()) }
    // 当前歌的 song_loop 行(驱动「重新分析 / 取消最终结果 / 删除分析结果」显隐);
    // 打开菜单即异步预查,点击「无缝循环」时再实时复查一次。
    var loopRow by remember(selectedSong) { mutableStateOf<SongLoop?>(null) }
    val menuTextStyle = largeMenuTextStyle()

    LaunchedEffect(selectedSong) {
        val song = selectedSong ?: return@LaunchedEffect
        loopRow = runCatching { loopEntryViewModel.loopRow(song.id) }.getOrNull()
    }

    if (selectedSong != null && !showAddDialog && !showInfoDialog && !showHideConfirmDialog && !showDeleteDialog && !showDeleteLoopDialog) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(selectedSong.title) },
            text = {
                Column {
                    if (onAddToQueue != null) {
                        Text(
                            text = "加入播放队列",
                            style = menuTextStyle,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onAddToQueue(selectedSong)
                                    Toast.makeText(context, "已加入播放队列", Toast.LENGTH_SHORT).show()
                                    onDismiss()
                                }
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                    Text(
                        text = "添加到歌单",
                        style = menuTextStyle,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showAddDialog = true }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "批量管理",
                        style = menuTextStyle,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onBatchManage(selectedSong)
                                onDismiss()
                            }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "歌曲信息",
                        style = menuTextStyle,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onLoadMetadata(selectedSong) { loaded -> metadata = loaded }
                                showInfoDialog = true
                            }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    if (onSeamlessLoop != null) {
                        // CUE 分轨不支持无缝循环(分析会拿到整文件 PCM):置灰 + 点击提示
                        val isCueTrack = selectedSong.isCue
                        Text(
                            text = "无缝循环",
                            style = menuTextStyle,
                            color = if (isCueTrack) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (isCueTrack) {
                                        Toast.makeText(context, "CUE 分轨暂不支持无缝循环", Toast.LENGTH_SHORT).show()
                                    } else {
                                        val song = selectedSong
                                        scope.launch {
                                            val row = runCatching { loopEntryViewModel.loopRow(song.id) }.getOrNull()
                                            loopRow = row
                                            onSeamlessLoop.invoke(song, row?.confirmed)
                                            onDismiss()
                                        }
                                    }
                                }
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        if (onLoopReanalyze != null && loopRow != null && !isCueTrack) {
                            Text(
                                text = "重新分析",
                                style = menuTextStyle,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onLoopReanalyze.invoke(selectedSong)
                                        onDismiss()
                                    }
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                        if (onLoopDemote != null && loopRow?.confirmed == true && !isCueTrack) {
                            Text(
                                text = "取消最终结果",
                                style = menuTextStyle,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onLoopDemote.invoke(selectedSong)
                                        onDismiss()
                                    }
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                        if (onLoopDelete != null && loopRow != null && !isCueTrack) {
                            Text(
                                text = "删除分析结果",
                                style = menuTextStyle,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showDeleteLoopDialog = true }
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                    }
                    Text(
                        text = "从曲库中隐藏歌曲",
                        style = menuTextStyle,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showHideConfirmDialog = true }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "删除歌曲文件",
                        style = menuTextStyle,
                        color = MaterialTheme.colorScheme.error,
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
            playlists = playlists,
            resetKey = selectedSong.id,
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

    if (selectedSong != null && showHideConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showHideConfirmDialog = false },
            title = { Text("隐藏歌曲") },
            text = { Text("确定要隐藏这首歌曲吗？隐藏后不会显示在任何列表中。") },
            confirmButton = {
                TextButton(onClick = {
                    onHideSong(selectedSong)
                    Toast.makeText(context, "已隐藏", Toast.LENGTH_SHORT).show()
                    showHideConfirmDialog = false
                    onDismiss()
                }) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { showHideConfirmDialog = false }) {
                    Text("取消")
                }
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

    if (selectedSong != null && showDeleteLoopDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteLoopDialog = false },
            title = { Text("删除分析结果") },
            text = { Text("确定删除这首歌的无缝循环分析结果吗？删除后下次进入需要重新分析。") },
            confirmButton = {
                TextButton(onClick = {
                    onLoopDelete?.invoke(selectedSong)
                    showDeleteLoopDialog = false
                    onDismiss()
                }) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteLoopDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

/**
 * 菜单侧只读查询 song_loop 行(决定「无缝循环」三态与三个结果菜单项的显隐)。
 * **不做任何播放/写库动作** —— 菜单只发事件(契约 §1)。
 */
@HiltViewModel
class LoopEntryViewModel @Inject constructor(
    private val analysisManager: LoopAnalysisManager,
) : ViewModel() {
    suspend fun loopRow(songId: Long): SongLoop? = analysisManager.getResultOnce(songId)
}

@Composable
fun AddToPlaylistDialog(
    playlists: List<Playlist>,
    resetKey: Any? = null,
    onDismiss: () -> Unit,
    onAdd: (Long) -> Unit,
    onCreate: (String) -> Unit
) {
    var newPlaylistName by remember(resetKey) { mutableStateOf("") }

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
                                style = androidx.compose.material3.MaterialTheme.typography.bodyLarge.doubleScaled(),
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
            TextButton(onClick = {
                if (newPlaylistName.isNotBlank()) {
                    onCreate(newPlaylistName)
                }
            }) {
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

@Composable
private fun largeMenuTextStyle(): TextStyle {
    return androidx.compose.material3.MaterialTheme.typography.bodyLarge.largeScaled()
}
