package com.zjr.hesimusic.ui.player

import android.media.audiofx.Equalizer
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.zjr.hesimusic.data.mapper.mediaIdToSongId
import com.zjr.hesimusic.loop.LoopPhase
import com.zjr.hesimusic.loop.LoopPosition
import com.zjr.hesimusic.loop.LoopWindow
import com.zjr.hesimusic.service.LoopClaimStore
import com.zjr.hesimusic.ui.common.MusicViewModel
import com.zjr.hesimusic.ui.common.StoragePermissionDialogHost
import com.zjr.hesimusic.utils.TimeFormatter
import kotlin.math.abs
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    onBackClick: () -> Unit,
    viewModel: MusicViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val sleepTimerState by viewModel.sleepTimerState.collectAsState()
    // 命中当前歌的无缝窗口;只在标记层的 DrawScope 里读值,窗口变化只重绘、不重组本页
    val loopWindowState = viewModel.activeLoopWindow.collectAsState()
    
    var showQueue by remember { mutableStateOf(false) }
    var showSleepTimer by remember { mutableStateOf(false) }
    var showEqualizer by remember { mutableStateOf(false) }

    val context = LocalContext.current

    // D06：回退提示与“为什么没在循环”的常驻状态行，都读同一条 claim 真值。
    val snackbarHostState = remember { SnackbarHostState() }
    val currentSongId = uiState.currentPlayingSongId?.toLongOrNull()
    val claimRevision by LoopClaimStore.revisionFlow.collectAsState()
    val loopClaim = remember(currentSongId, claimRevision) {
        LoopClaimStore.claimFor(currentSongId)
    }
    // 阶段只看权威展示位置（与 /state.loop.phase 同一口径）：
    // prelude=前奏、loop=循环段、tail=尾奏；claim 未生效时 phase=null。
    val loopPhase = loopClaim.phaseForDisplayUs(uiState.currentPosition * 1_000L)
    val bandPhase = loopPhase ?: if (loopClaim.claimActive) LoopPhase.LOOP else null
    val loopRangeLabel = loopClaim.effectiveWindow?.let {
        LoopSeamlessExitPolicy.loopRangeLabel(it)
    }
    val loopStatusText = loopPhaseStatusText(loopPhase, loopRangeLabel)
    val loopNoticeCenter = remember(context) { AppLoopNoticeCenter.get(context) }
    val loopIntent = rememberLoopIntent()
    val armedLoop by loopIntent.armed.collectAsState()
    // 00029：入口准备中（旧源已停声、环带源还没 READY）。UI 必须显示"准备中"而不是
    // "未生效"（红色错误），且位置已归 0，不允许先显示旧位置。
    val entryPendingSongId by loopIntent.entryPendingSongId.collectAsState()
    val seamlessEngaged = LoopSeamlessExitPolicy.shouldShowExitEntry(
        currentSongId = currentSongId,
        armedSongId = armedLoop?.songId,
        claimActive = loopClaim.claimActive,
    )
    LaunchedEffect(currentSongId, claimRevision) {
        fallbackNotice(currentSongId, loopClaim)?.let { loopNoticeCenter.offer(it) }
    }
    LoopNoticeHost(
        songId = currentSongId,
        snackbarHostState = snackbarHostState,
        onAction = { notice ->
            if (notice.kind == LoopNoticeKind.FALLBACK) {
                exitSeamless(
                    viewModel = viewModel,
                    loopIntent = loopIntent,
                    keepPlainSingleLoop = false,
                )
            }
        },
    )

    // issue 00030：从播放页触发的播放失败同样由这个 host 弹「去授权」对话框
    // （NavHost 同一时刻只组合一个目标页，事件消费是原子的，不会和主界面重复弹）。
    StoragePermissionDialogHost()

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
                        Icon(
                            imageVector = if (sleepTimerState != null) Icons.Default.AlarmOn else Icons.Rounded.Timer,
                            contentDescription = "睡眠定时",
                            tint = if (sleepTimerState != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(onClick = { showEqualizer = true }) {
                        Icon(Icons.Rounded.GraphicEq, contentDescription = "均衡器")
                    }
                    IconButton(onClick = { showQueue = true }) {
                        Icon(Icons.Rounded.QueueMusic, contentDescription = "播放队列")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            val artworkData = uiState.artworkBytes ?: uiState.currentMediaItem?.mediaMetadata?.artworkUri
            if (artworkData != null) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(artworkData)
                        .crossfade(true)
                        .build(),
                    contentDescription = "封面",
                    // 封面最大 280dp：小屏上给状态行 + 播放控制留出高度，避免被挤出/裁掉。
                    modifier = Modifier
                        .sizeIn(maxWidth = 280.dp, maxHeight = 280.dp)
                        .aspectRatio(1f)
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .sizeIn(maxWidth = 280.dp, maxHeight = 280.dp)
                        .aspectRatio(1f)
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "暂无封面",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

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
                // 位置已是展示口径(MusicViewModel 已做幂等折返,跨 B 的外推不会冲出区间)
                val safePosition = uiState.currentPosition.coerceIn(0L, safeDuration)
                // 拖动期间的**本地**位置(issue 00001)。
                // 无缝态下 LoopSessionPlayer.seekTo 会把展示位置映射到引擎位置并触发真实 flush,
                // 拖动中每帧都 seek 会不断打断环回、位置又被回写,滑块与播放位置互相打架(拖动没反应/被弹回)。
                // 因此拖动中只更新这里、绝不 seek;松手才下发一次 seek。
                var draggingMs by remember { mutableStateOf<Long?>(null) }
                // 松手后**待生效**的 seek 目标(issue 00001 第 2 条:松手后不再闪回旧位置)。
                // A-067: MediaController.seekTo 在 controller 侧**立即乐观 mask** 成新位置/新状态;
                // UI 读到的已经是新值,不是"旧快照"。真正要等的是 session 侧 clamp/调整后的
                // **最终值**(以及它对应的 PlaybackState 到达 UI)。MediaSession 的周期位置事件
                // 默认 3s 一次,所以覆盖层不能用"约 1s"作假设,只以收敛或超时释放,
                // 见 [SeekOverlayPolicy]。
                var pendingSeekMs by remember { mutableStateOf<Long?>(null) }
                val sliderInteractionSource = remember { MutableInteractionSource() }

                // 松手提交:onValueChangeFinished 与 interactionSource 兜底共用,靠 draggingMs 保证幂等。
                // pendingSeekMs 先赋值、draggingMs 后清空(同一次快照内生效),滑块不会闪回旧位置。
                val commitDragSeek = {
                    val target = draggingMs
                    if (target != null) {
                        pendingSeekMs = target
                        draggingMs = null
                        viewModel.seekTo(target)
                    }
                }

                // 兜底提交:Material3 Slider 的指针事件被上层消费 / 手势 cancel 时不保证回调
                // onValueChangeFinished,这里在拖拽交互结束时再提交一次(没有拖动时是空操作)。
                LaunchedEffect(sliderInteractionSource) {
                    sliderInteractionSource.interactions.collect { interaction ->
                        if (interaction is DragInteraction.Stop || interaction is DragInteraction.Cancel) {
                            commitDragSeek()
                        }
                    }
                }

                // 待生效目标的生命周期:位置收敛(±1200ms)或超时(1500ms)后释放覆盖层。
                // 判据是"实际展示位置 == 最终期望值(容差内)或超时",不是"等旧值变成新值"
                // (A-067:controller 已乐观 mask,旧值根本不会出现)。期望落点必须用
                // **折返后的展示位置**(有循环窗口时引擎会把目标折回 [A,B)),否则无缝态永远等不到收敛。
                LaunchedEffect(pendingSeekMs) {
                    val target = pendingSeekMs ?: return@LaunchedEffect
                    // loopWindowState = activeLoopWindow:源未真正循环时为 null -> expected 用真实媒体时间。
                    // issue 00028：SOURCE 整曲时间轴下 tail 是合法落点，overlay 不能按 [A,B)
                    // 折返（否则松手 1.5s 后位置会弹回 A）。claim 是同一条事实源。
                    val songId = uiState.currentMediaItem?.mediaId?.let { mediaIdToSongId(it) }
                    val claim = LoopClaimStore.claimFor(songId)
                    val expected = SeekOverlayPolicy.expectedDisplayMs(
                        targetMs = target,
                        window = loopWindowState.value,
                        wholeTimeline = claim.sourceTimeline,
                    )
                    val startedAtMs = SystemClock.uptimeMillis()
                    while (!SeekOverlayPolicy.shouldRelease(
                            actualDisplayMs = uiState.currentPosition,
                            expectedDisplayMs = expected,
                            elapsedMs = SystemClock.uptimeMillis() - startedAtMs,
                        )
                    ) {
                        delay(50L)
                    }
                    pendingSeekMs = null
                }

                // 切歌/切走时旧目标失效,避免把上一首的目标位置套到新歌上。
                LaunchedEffect(uiState.currentMediaItem?.mediaId) {
                    draggingMs = null
                    pendingSeekMs = null
                }

                // 拖动中 > 待生效目标 > 服务端回写的展示位置(issue 00001)
                val sliderPositionMs = SeekOverlayPolicy.shownPositionMs(
                    draggingMs = draggingMs,
                    pendingSeekMs = pendingSeekMs,
                    safePositionMs = safePosition,
                ).coerceIn(0L, safeDuration)
                // 滑条 + 无缝循环 A/B 标记层(未命中当前歌时标记层不画任何像素)
                Box(modifier = Modifier.fillMaxWidth()) {
                    Slider(
                        value = sliderPositionMs.toFloat(),
                        onValueChange = {
                            // 新的拖动开始:上一次的待生效目标立即失效,避免覆盖层与新拖动打架
                            pendingSeekMs = null
                            draggingMs = it.toLong().coerceIn(0L, safeDuration)
                        },
                        onValueChangeFinished = { commitDragSeek() },
                        valueRange = 0f..safeDuration.toFloat(),
                        interactionSource = sliderInteractionSource,
                    )
                    LoopPhaseMarkerOverlay(
                        window = loopWindowState,
                        phase = bandPhase,
                        durationMs = uiState.duration,
                        modifier = Modifier.matchParentSize(),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // 左侧时间与滑块同口径:拖动中显示拖到的位置,松手后继续显示 seek 目标
                    // (直到位置收敛或超时),不会在 seek 生效前先跳回旧位置(issue 00001)
                    Text(
                        text = TimeFormatter.formatTime(sliderPositionMs),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = TimeFormatter.formatTime(uiState.duration),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                // D05/D06：状态行与退出入口合并为**一行**，避免挤压下方播放控制。
                // - claim 生效：phase 文案 + 退出；
                // - 回退态：原因文案（带 code）+ 退出；
                // - 非无缝但有历史原因：只显示原因行（正常不会出现）。
                if (seamlessEngaged) {
                    // 入口：arm 之后、claim 可见之前都算"准备中"。不能只看 entryPending ——
                    // 协调器在起播时会清它，而 claim 还要等 READY+350ms，中间不能闪"未生效"。
                    val entryPreparing = (entryPendingSongId != null &&
                        entryPendingSongId == currentSongId) ||
                        (!loopClaim.claimActive && loopClaim.fallbackReason == null)
                    val statusText = when {
                        loopClaim.claimActive -> loopStatusText
                        entryPreparing -> "正在进入无缝循环…"
                        else -> loopClaim.fallbackReason?.let { fallbackNoticeMessage(it) }
                            ?: "无缝循环未生效"
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (loopClaim.claimActive || entryPreparing) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                            maxLines = if (loopClaim.claimActive) 1 else 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = {
                                exitSeamless(
                                    viewModel = viewModel,
                                    loopIntent = loopIntent,
                                    keepPlainSingleLoop = false,
                                )
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp),
                        ) {
                            Text("退出无缝循环", maxLines = 1, fontSize = 11.sp)
                        }
                    }
                } else {
                    loopClaim.fallbackReason?.let { reason ->
                        Text(
                            text = fallbackNoticeMessage(reason),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            // Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Three-state playback mode button: 列表循环 -> 随机播放 -> 单曲循环。
                // 无缝态下“单曲循环”按钮 = 退出无缝 + 普通单曲循环（XOR），不再用 ALL 隐式退出。
                IconButton(onClick = {
                    when (LoopSeamlessExitPolicy.repeatTap(
                        shuffleEnabled = uiState.shuffleModeEnabled,
                        repeatMode = uiState.repeatMode,
                        seamlessActive = seamlessEngaged,
                    )) {
                        LoopSeamlessExitPolicy.RepeatTap.EXIT_KEEP_PLAIN_SINGLE -> {
                            exitSeamless(
                                viewModel = viewModel,
                                loopIntent = loopIntent,
                                keepPlainSingleLoop = true,
                            )
                        }

                        LoopSeamlessExitPolicy.RepeatTap.TO_SINGLE_LOOP -> {
                            viewModel.setShuffleModeEnabled(false)
                            viewModel.setRepeatMode(Player.REPEAT_MODE_ONE)
                        }

                        LoopSeamlessExitPolicy.RepeatTap.TO_LIST_LOOP -> {
                            viewModel.setShuffleModeEnabled(false)
                            viewModel.setRepeatMode(Player.REPEAT_MODE_ALL)
                        }

                        LoopSeamlessExitPolicy.RepeatTap.TO_SHUFFLE -> {
                            viewModel.setShuffleModeEnabled(true)
                            viewModel.setRepeatMode(Player.REPEAT_MODE_ALL)
                        }
                    }
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

/**
 * 松手 seek 的「待生效目标」覆盖层策略(纯函数,无 Compose/Android 依赖,便于单测)。
 *
 * 背景(issue 00001 第 2 条 + A-067):`MediaController.seekTo` 先在 controller 侧乐观
 * mask 成新位置/新状态,之后才经过 `MediaSession → LoopSessionPlayer → 引擎` 到达最终值。
 * 所以覆盖层等待的是「实际展示位置 == session 侧最终期望值」,**不是**「旧位置变成新位置」;
 * 等不到就按 [MAX_HOLD_MS] 超时释放。MediaSession 的周期位置事件默认 3s(A-066),
 * 因此这里不假设"约 1s 一刷"。
 */
internal object SeekOverlayPolicy {

    /** 收敛判定容差(毫秒):展示位置与期望落点相差不超过该值即视为 seek 已生效。 */
    const val CONVERGE_TOLERANCE_MS: Long = 1_200L

    /** 待生效目标最长保留时间(毫秒):超时强制释放,避免位置永远停在目标上。 */
    const val MAX_HOLD_MS: Long = 1_500L

    /** 毫秒转微秒前的溢出上限(与 [com.zjr.hesimusic.ui.common.foldDisplayPositionMs] 同口径)。 */
    private const val MAX_FOLDABLE_POSITION_MS: Long = Long.MAX_VALUE / 1_000L

    /**
     * 松手 seek 目标在**展示口径**下的期望落点。
     *
     * 传入的 [window] 必须是 [com.zjr.hesimusic.ui.common.effectiveLoopWindow] 的结果
     * (源真的接管了循环才是非 null)。00027:SOURCE 回退/构建失败时窗口仍在,但播放是
     * 普通整曲、位置不折返;此时传 null,期望落点 = 真实媒体时间。
     *
     * 有生效窗口时引擎会把目标折回 [A, B)(见 [LoopPosition.displayUs]),UI 之后读到的展示
     * 位置就是折返后的值,所以收敛判定必须用折返后的目标;无窗口(或非法窗口)时就是目标本身。
     */
    fun expectedDisplayMs(
        targetMs: Long,
        window: LoopWindow?,
        /**
         * true = SOURCE 整曲时间轴（prelude/loop/tail）。此时服务层**保留**展示目标：
         * seek 到 A 之前落前奏、A..B 落循环、B 之后落 tail，overlay 期望值就是目标本身。
         * false = M3′/普通播放，仍按 [LoopPosition.displayUs] 折返。
         */
        wholeTimeline: Boolean = false,
    ): Long {
        if (window == null) return targetMs
        if (targetMs > MAX_FOLDABLE_POSITION_MS || targetMs < -MAX_FOLDABLE_POSITION_MS) {
            return targetMs
        }
        if (wholeTimeline) return targetMs.coerceAtLeast(0L)
        return LoopPosition.displayUs(targetMs * 1_000L, window) / 1_000L
    }

    /** 覆盖层显示的位置:拖动中 > 待生效目标 > 服务端回写的展示位置。 */
    fun shownPositionMs(
        draggingMs: Long?,
        pendingSeekMs: Long?,
        safePositionMs: Long,
    ): Long = draggingMs ?: pendingSeekMs ?: safePositionMs

    /** 展示位置是否已收敛到期望落点(容差 [CONVERGE_TOLERANCE_MS])。 */
    fun hasConverged(actualDisplayMs: Long, expectedDisplayMs: Long): Boolean =
        abs(actualDisplayMs - expectedDisplayMs) <= CONVERGE_TOLERANCE_MS

    /** 待生效目标是否已超过最长保留时间。 */
    fun isExpired(elapsedMs: Long): Boolean = elapsedMs >= MAX_HOLD_MS

    /** 是否应释放覆盖层:已收敛,或已超时兜底(位置一直没追上来时避免永远卡住)。 */
    fun shouldRelease(actualDisplayMs: Long, expectedDisplayMs: Long, elapsedMs: Long): Boolean =
        isExpired(elapsedMs) || hasConverged(actualDisplayMs, expectedDisplayMs)
}
