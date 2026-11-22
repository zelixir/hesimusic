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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
     * 「无缝循环」:仅当该歌 song_loop 行 confirmed=1 时显示(菜单内部按异步查询结果决定),
     * 点击 = 调用方武装无缝意图 + 播放该歌 + REPEAT_ONE。
     */
    onSeamlessLoop: ((Song) -> Unit)? = null,
    /**
     * 「无缝分析」:始终显示(仅 CUE 分轨置灰 + 点击提示),点击导航到「分析与预览」页
     * (已存结果直接加载,无行则自动分析)。
     */
    onSeamlessAnalysis: ((Song) -> Unit)? = null,
    /**
     * 00029：菜单打开、且该歌已确认循环时预热入口源（后台建源+解前奏首块，不播放）。
     * 关闭菜单/选别的动作时由 [onCancelPrewarm] 释放。
     */
    onPrewarmSeamlessLoop: ((Song, Long, Long) -> Unit)? = null,
    onCancelPrewarm: ((Long) -> Unit)? = null
) {
    val context = LocalContext.current
    val loopEntryViewModel: LoopEntryViewModel = hiltViewModel()
    var showAddDialog by remember(selectedSong) { mutableStateOf(false) }
    // 入口已被点击：对话框销毁时不能再取消预热（协调器还要取走它）。
    var prewarmConsumed by remember { mutableStateOf(false) }
    var showInfoDialog by remember(selectedSong) { mutableStateOf(false) }
    var showHideConfirmDialog by remember(selectedSong) { mutableStateOf(false) }
    var showDeleteDialog by remember(selectedSong) { mutableStateOf(false) }
    var metadata by remember(selectedSong) { mutableStateOf<Map<String, String>>(emptyMap()) }
    // 当前歌的 song_loop 行是否 confirmed=1;null = 查询中/查询失败/无行 → 按未确认处理,
    // 即菜单只显示「无缝分析」,避免「无缝循环」先闪现再消失。
    var loopConfirmed by remember(selectedSong) { mutableStateOf<Boolean?>(null) }
    val menuTextStyle = largeMenuTextStyle()

    LaunchedEffect(selectedSong) {
        prewarmConsumed = false
        val song = selectedSong ?: return@LaunchedEffect
        val row = runCatching { loopEntryViewModel.loopRow(song.id) }.getOrNull()
        loopConfirmed = row?.confirmed
        if (row?.confirmed == true) {
            onPrewarmSeamlessLoop?.invoke(song, row.loopStartMs, row.loopEndMs)
        }
    }

    // 菜单关闭（selectedSong -> null / 离开宿主）时释放未消费的预热源。
    DisposableEffect(selectedSong) {
        val songId = selectedSong?.id
        onDispose {
            if (songId != null && !prewarmConsumed) onCancelPrewarm?.invoke(songId)
        }
    }

    if (selectedSong != null && !showAddDialog && !showInfoDialog && !showHideConfirmDialog && !showDeleteDialog) {
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
                    if (onSeamlessLoop != null || onSeamlessAnalysis != null) {
                        // CUE 分轨不支持无缝循环(分析会拿到整文件 PCM):置灰 + 点击提示
                        val isCueTrack = selectedSong.isCue
                        val seamlessMenu = resolveSeamlessMenuState(isCue = isCueTrack, confirmed = loopConfirmed)
                        if (seamlessMenu.showSeamlessLoop && onSeamlessLoop != null) {
                            Text(
                                text = "无缝循环",
                                style = menuTextStyle,
                                color = if (seamlessMenu.seamlessLoopEnabled) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (!seamlessMenu.seamlessLoopEnabled) {
                                            Toast.makeText(context, "CUE 分轨暂不支持无缝循环", Toast.LENGTH_SHORT).show()
                                        } else {
                                            // 入口立即消费预热源：不能让 onDismiss 的
                                            // DisposableEffect 在协调器取源前把它释放。
                                            prewarmConsumed = true
                                            onSeamlessLoop.invoke(selectedSong)
                                            onDismiss()
                                        }
                                    }
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                        if (seamlessMenu.showSeamlessAnalysis && onSeamlessAnalysis != null) {
                            Text(
                                text = "无缝分析",
                                style = menuTextStyle,
                                color = if (seamlessMenu.seamlessAnalysisEnabled) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (!seamlessMenu.seamlessAnalysisEnabled) {
                                            Toast.makeText(context, "CUE 分轨暂不支持无缝循环", Toast.LENGTH_SHORT).show()
                                        } else {
                                            // 去了分析页，入口源不再需要，立刻释放（TTL 兜底仍在）。
                                            prewarmConsumed = true
                                            onCancelPrewarm?.invoke(selectedSong.id)
                                            onSeamlessAnalysis.invoke(selectedSong)
                                            onDismiss()
                                        }
                                    }
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
}

/**
 * 菜单侧只读查询 song_loop 行(仅决定「无缝循环」是否显示:confirmed=1 才显示)。
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

/**
 * 「无缝」两项菜单的显示/可用决策(纯函数,单测见 SeamlessMenuStateTest)。
 *
 * - 「无缝分析」:始终显示,仅 CUE 分轨置灰;
 * - 「无缝循环」:仅 song_loop 行 confirmed=1 时显示;null(查询中/查询失败/无行)
 *   一律按未确认处理,避免菜单先闪出「无缝循环」再消失。
 */
internal data class SeamlessMenuState(
    val showSeamlessLoop: Boolean,
    val seamlessLoopEnabled: Boolean,
    val showSeamlessAnalysis: Boolean,
    val seamlessAnalysisEnabled: Boolean,
)

internal fun resolveSeamlessMenuState(isCue: Boolean, confirmed: Boolean?): SeamlessMenuState {
    val confirmedFinal = confirmed == true
    return SeamlessMenuState(
        showSeamlessLoop = confirmedFinal,
        seamlessLoopEnabled = confirmedFinal && !isCue,
        showSeamlessAnalysis = true,
        seamlessAnalysisEnabled = !isCue,
    )
}
