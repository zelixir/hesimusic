package com.zjr.hesimusic.ui.player

import android.media.audiofx.Equalizer
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.zjr.hesimusic.ui.common.MusicViewModel
import com.zjr.hesimusic.utils.TimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    onBackClick: () -> Unit,
    viewModel: MusicViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val sleepTimerState by viewModel.sleepTimerState.collectAsState()
    
    var showQueue by remember { mutableStateOf(false) }
    var showSleepTimer by remember { mutableStateOf(false) }
    var showEqualizer by remember { mutableStateOf(false) }

    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("正在播放") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.toggleCurrentSongFavorite() }) {
                        Icon(
                            imageVector = if (uiState.isCurrentSongFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            contentDescription = if (uiState.isCurrentSongFavorite) "取消收藏" else "收藏",
                            tint = if (uiState.isCurrentSongFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(onClick = { showSleepTimer = true }) {
                        Icon(Icons.Rounded.Timer, contentDescription = "睡眠定时")
                    }
                    IconButton(onClick = { showEqualizer = true }) {
                        Icon(Icons.Rounded.GraphicEq, contentDescription = "均衡器")
                    }
                    IconButton(onClick = { showQueue = true }) {
                        Icon(Icons.Rounded.QueueMusic, contentDescription = "播放队列")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            // Cover Art - use artwork bytes from taglib if available
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(uiState.artworkBytes ?: uiState.currentMediaItem?.mediaMetadata?.artworkUri)
                    .crossfade(true)
                    .build(),
                contentDescription = "封面",
                modifier = Modifier
                    .aspectRatio(1f)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentScale = ContentScale.Crop
            )

            // Title & Artist
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = uiState.currentMediaItem?.mediaMetadata?.title?.toString() ?: "未知标题",
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = uiState.currentMediaItem?.mediaMetadata?.artist?.toString() ?: "未知歌手",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Progress
            Column {
                // Ensure valid range for Slider: duration must be positive
                val safeDuration = if (uiState.duration > 0) uiState.duration else 1L
                val safePosition = uiState.currentPosition.coerceIn(0L, safeDuration)
                Slider(
                    value = safePosition.toFloat(),
                    onValueChange = { viewModel.seekTo(it.toLong()) },
                    valueRange = 0f..safeDuration.toFloat()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = TimeFormatter.formatTime(uiState.currentPosition),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = TimeFormatter.formatTime(uiState.duration),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Three-state playback mode button: 列表循环 -> 随机播放 -> 单曲循环
                IconButton(onClick = {
                    val (newShuffle, newRepeat) = when {
                        uiState.shuffleModeEnabled -> false to Player.REPEAT_MODE_ONE // Random -> Single Loop
                        uiState.repeatMode == Player.REPEAT_MODE_ONE -> false to Player.REPEAT_MODE_ALL // Single Loop -> List Loop
                        else -> true to Player.REPEAT_MODE_ALL // List Loop -> Random
                    }
                    viewModel.setShuffleModeEnabled(newShuffle)
                    viewModel.setRepeatMode(newRepeat)
                }) {
                    Icon(
                        imageVector = when {
                            uiState.shuffleModeEnabled -> Icons.Default.Shuffle
                            uiState.repeatMode == Player.REPEAT_MODE_ONE -> Icons.Default.RepeatOne
                            else -> Icons.Default.Repeat
                        },
                        contentDescription = when {
                            uiState.shuffleModeEnabled -> "随机播放"
                            uiState.repeatMode == Player.REPEAT_MODE_ONE -> "单曲循环"
                            else -> "列表循环"
                        },
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                IconButton(onClick = { viewModel.skipToPrevious() }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = "上一首", modifier = Modifier.size(32.dp))
                }

                FilledIconButton(
                    onClick = {
                        if (uiState.isPlaying) viewModel.pause() else viewModel.resume()
                    },
                    modifier = Modifier.size(64.dp)
                ) {
                    Icon(
                        imageVector = if (uiState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "播放/暂停",
                        modifier = Modifier.size(32.dp)
                    )
                }

                IconButton(onClick = { viewModel.skipToNext() }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.SkipNext, contentDescription = "下一首", modifier = Modifier.size(32.dp))
                }

                // Placeholder for balanced layout
                Spacer(modifier = Modifier.size(48.dp))
            }
        }
    }

    if (showQueue) {
        QueueSheet(
            playlist = uiState.playlist,
            currentMediaItem = uiState.currentMediaItem,
            onDismiss = { showQueue = false },
            onItemClick = { item -> 
                val index = uiState.playlist.indexOf(item)
                if (index != -1) {
                    viewModel.seekTo(0) // Or skip to index logic if needed, but usually we just play
                    // Need a way to skip to index in ViewModel
                    // For now, just close
                }
            },
            onRemoveItem = { index -> viewModel.removeMediaItem(index) },
            onClearQueue = { viewModel.clearPlaylist() }
        )
    }

    if (showSleepTimer) {
        SleepTimerDialog(
            remainingTime = sleepTimerState,
            onSetTimer = { minutes -> viewModel.startSleepTimer(minutes) },
            onCancelTimer = { viewModel.cancelSleepTimer() },
            onDismiss = { showSleepTimer = false }
        )
    }

    if (showEqualizer) {
        if (uiState.audioSessionId != 0) {
            EqualizerDialog(
                audioSessionId = uiState.audioSessionId,
                onDismiss = { showEqualizer = false }
            )
        } else {
            Toast.makeText(context, "音频会话ID不可用", Toast.LENGTH_SHORT).show()
            showEqualizer = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(
    playlist: List<MediaItem>,
    currentMediaItem: MediaItem?,
    onDismiss: () -> Unit,
    onItemClick: (MediaItem) -> Unit,
    onRemoveItem: (Int) -> Unit,
    onClearQueue: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("播放队列 (${playlist.size})", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onClearQueue) {
                    Text("清空")
                }
            }
            LazyColumn {
                itemsIndexed(playlist) { index, item ->
                    val isCurrent = item.mediaId == currentMediaItem?.mediaId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onItemClick(item) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${index + 1}",
                            modifier = Modifier.width(32.dp),
                            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.mediaMetadata.title?.toString() ?: "未知",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = item.mediaMetadata.artist?.toString() ?: "未知",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(onClick = { onRemoveItem(index) }) {
                            Icon(Icons.Default.Close, contentDescription = "移除")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SleepTimerDialog(
    remainingTime: Long?,
    onSetTimer: (Int) -> Unit,
    onCancelTimer: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("睡眠定时") },
        text = {
            Column {
                if (remainingTime != null) {
                    Text("剩余时间: ${TimeFormatter.formatTime(remainingTime)}")
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = onCancelTimer) {
                        Text("取消定时")
                    }
                } else {
                    val options = listOf(15, 30, 45, 60)
                    options.forEach { minutes ->
                        TextButton(
                            onClick = {
                                onSetTimer(minutes)
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("$minutes 分钟")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}

@Composable
fun EqualizerDialog(
    audioSessionId: Int,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var equalizer by remember { mutableStateOf<Equalizer?>(null) }
    var bands by remember { mutableStateOf<List<Short>>(emptyList()) }
    var minLevel by remember { mutableStateOf<Short>(0) }
    var maxLevel by remember { mutableStateOf<Short>(0) }

    DisposableEffect(audioSessionId) {
        val eq = try {
            Equalizer(0, audioSessionId).apply {
                enabled = true
            }
        } catch (e: Exception) {
            Toast.makeText(context, "初始化均衡器失败", Toast.LENGTH_SHORT).show()
            null
        }

        equalizer = eq
        if (eq != null) {
            val numBands = eq.numberOfBands
            minLevel = eq.bandLevelRange[0]
            maxLevel = eq.bandLevelRange[1]
            bands = List(numBands.toInt()) { i -> eq.getBandLevel(i.toShort()) }
        }

        onDispose {
            // We might want to keep settings, but for now we just release the object handle.
            // If we release, the effect might stop.
            // To keep it running, we should probably not release it here, but we have no other place.
            // Ideally, Equalizer should be in Service.
            // For this phase, we'll release it and see.
            // Actually, if we release it, the effect is gone.
            // So we should probably NOT release it if we want it to persist?
            // But that leaks resources.
            // The correct way is Service.
            // For now, let's release it.
            eq?.release()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("均衡器") },
        text = {
            if (equalizer != null) {
                Column {
                    bands.forEachIndexed { index, level ->
                        val freq = equalizer?.getCenterFreq(index.toShort())?.div(1000) ?: 0
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${freq}Hz",
                                modifier = Modifier.width(60.dp),
                                style = MaterialTheme.typography.bodySmall
                            )
                            Slider(
                                value = level.toFloat(),
                                onValueChange = { newLevel ->
                                    val newShort = newLevel.toInt().toShort()
                                    equalizer?.setBandLevel(index.toShort(), newShort)
                                    bands = bands.toMutableList().apply { set(index, newShort) }
                                },
                                valueRange = minLevel.toFloat()..maxLevel.toFloat(),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            } else {
                Text("均衡器不可用")
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("完成")
            }
        }
    )
}
