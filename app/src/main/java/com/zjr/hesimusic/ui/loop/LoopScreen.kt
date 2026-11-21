package com.zjr.hesimusic.ui.loop

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.zjr.hesimusic.data.model.Song
import kotlinx.coroutines.delay
import com.zjr.hesimusic.loop.analysis.WaveformPeaks
import com.zjr.hesimusic.service.LoopClaimStore
import com.zjr.hesimusic.ui.common.MusicViewModel
import com.zjr.hesimusic.ui.player.AppLoopNoticeCenter
import com.zjr.hesimusic.ui.player.LoopNoticeHost
import com.zjr.hesimusic.ui.player.LoopNoticeKind
import com.zjr.hesimusic.ui.player.exitSeamless
import com.zjr.hesimusic.ui.player.fallbackNotice
import com.zjr.hesimusic.ui.player.rememberLoopIntent
import com.zjr.hesimusic.ui.loop.state.AnalysisPhase
import com.zjr.hesimusic.ui.loop.state.LoopHandle
import com.zjr.hesimusic.ui.loop.state.LoopPageEvent
import com.zjr.hesimusic.ui.loop.state.LoopPageMode
import com.zjr.hesimusic.ui.loop.state.LoopPageState
import com.zjr.hesimusic.ui.loop.state.PlaybackState
import com.zjr.hesimusic.ui.loop.state.PrefillState
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

// ---------------------------------------------------------------------------
// 色板(与波形原型 / 播放页 A-B 标记同一套色语言)
// ---------------------------------------------------------------------------
private val WaveBg = Color(0xFF0A0E14)
private val LoopRegionFill = Color(0x1F3FB950)
private val MarkerAColor = Color(0xFFD29922)
private val MarkerBColor = Color(0xFFF85149)
private val PlayheadColor = Color(0xFFE6EDF3)
private val MarkerLabelColor = Color(0xFF0D1117)
private const val WAVE_BG_ARGB = 0xFF0A0E14.toInt()
private const val GRID_LINE_ARGB = 0x246E7681.toInt()
private const val GRID_TEXT_ARGB = 0xFF6E7681.toInt()
private const val WAVE_DIM_ARGB = 0xFF4A535E.toInt()
private const val WAVE_LOOP_ARGB = 0xFF3FB950.toInt()

/** 循环长度占比着色(契约 §2.3):>=80% 绿 / 50~80% 黄 / <50% 红。 */
private fun loopRatioColor(ratio: Float): Color = when {
    ratio >= 0.8f -> Color(0xFF3FB950)
    ratio >= 0.5f -> Color(0xFFD29922)
    else -> Color(0xFFF85149)
}

/**
 * 取承载本组合的 Activity(穿过 ContextThemeWrapper/ContextWrapper 链)。
 *
 * 配置变更重建判定要用 `Activity.isChangingConfigurations()`(场景 #45);
 * 拿不到时按"真正离开"处理(释放预览),保持旧行为不泄漏播放器。
 */
private fun Context.hostActivityOrNull(): Activity? {
    var current: Context? = this
    while (current != null) {
        if (current is Activity) return current
        current = (current as? ContextWrapper)?.baseContext
    }
    return null
}

/** 时间显示 mm:ss.SSS(契约 §2.3,毫秒精度便于 A/B 微调)。 */
internal fun fmtMs(ms: Long): String {
    val v = ms.coerceAtLeast(0L)
    val minutes = v / 60_000L
    val seconds = (v % 60_000L) / 1_000L
    val millis = v % 1_000L
    return String.format(Locale.US, "%02d:%02d.%03d", minutes, seconds, millis)
}

private fun fmtGrid(sec: Double): String {
    val v = if (sec.isFinite()) sec.coerceAtLeast(0.0) else 0.0
    val m = floor(v / 60).toInt()
    return String.format(Locale.US, "%d:%02d", m, (v - m * 60).toInt())
}

/** 网格刻度友好步长(刻度数 <= 14)。 */
private fun niceStep(durationSec: Double): Double {
    for (step in listOf(1.0, 2.0, 5.0, 10.0, 15.0, 30.0, 60.0, 120.0)) {
        if (durationSec / step <= 14) return step
    }
    return 300.0
}

// ---------------------------------------------------------------------------
// LoopScreen:分析 / 预览页(契约 §2;入口 = 长按菜单「无缝循环」)
// ---------------------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoopScreen(
    musicViewModel: MusicViewModel,
    onBack: () -> Unit,
    onSavedAndPlay: (Song) -> Unit,
    viewModel: LoopViewModel = hiltViewModel(),
) {
    val pageState by viewModel.pageState.collectAsState()
    val peaks by viewModel.peaks.collectAsState()
    val song by viewModel.song.collectAsState()
    val notice by viewModel.notice.collectAsState()
    val context = LocalContext.current
    val currentOnBack by rememberUpdatedState(onBack)

    // D06：主播放回退到普通播放时，结果页与播放页共用同一条提示通道。
    // 只有“正在播的这首 == 本页分析的这首”才提示，避免把别的歌的回退带到这里。
    val musicUiState by musicViewModel.uiState.collectAsState()
    val currentSongId = musicUiState.currentPlayingSongId?.toLongOrNull()
    val claimRevision by LoopClaimStore.revisionFlow.collectAsState()
    val loopClaim = remember(currentSongId, claimRevision) {
        LoopClaimStore.claimFor(currentSongId)
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val loopNoticeCenter = remember(context) { AppLoopNoticeCenter.get(context) }
    val loopIntent = rememberLoopIntent()
    val armedLoop by loopIntent.armed.collectAsState()
    // 结果页也给一个显式退出入口：只有“本页分析的歌 == 当前武装的无缝歌”才显示。
    val showExitSeamless = song?.id != null && armedLoop?.songId == song?.id
    LaunchedEffect(song?.id, currentSongId, claimRevision) {
        if (song?.id != null && song?.id == currentSongId) {
            fallbackNotice(currentSongId, loopClaim)?.let { loopNoticeCenter.offer(it) }
        }
    }

    // 一次性提示(Toast)消费
    LaunchedEffect(notice) {
        val message = notice ?: return@LaunchedEffect
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        viewModel.clearNotice()
    }

    // 进入页面即暂停主播放(不改变队列/repeat;契约 §2.1 / §2.5)。
    // **只在首次进入**时暂停:配置变更重建(旋转/分屏/深色模式/字体缩放)再 pause 一次,
    // 主播放的暂停会透传到进程级 SOURCE 闸门,把正在播放的预览数据源一起停住(场景 #45)。
    // onHostCreated() 同时负责重建后的预览恢复(常规路径只重挂探针、不 seek)。
    LaunchedEffect(Unit) {
        if (viewModel.onHostCreated()) musicViewModel.pause()
    }

    // effect NavigateBack(含删除持久化成功)统一从这里回到上一页。
    LaunchedEffect(Unit) {
        viewModel.backRequests.collect { currentOnBack() }
    }

    // 宿主销毁/离开页面:只有**真正离开**才释放预览并恢复主播放(原歌原位)。
    //
    // 旋转/分屏/深色模式/字体缩放在未声明 configChanges 的 Activity 上都会重建宿主,
    // 此时 Activity.isChangingConfigurations() == true:预览播放器由 ViewModel 持有、
    // 必须原样活着(位置连续、probe 不 detach)。旧实现无条件 release 会让重建后的页面
    // 永远没有 player、probe 也 detach → /debug/preview 恒 409 "preview probe not attached",
    // 且页面状态仍是 Ready 不会重新 prepare(场景 #45)。
    val lifecycleOwner = LocalLifecycleOwner.current
    val hostActivity = remember(context) { context.hostActivityOrNull() }
    DisposableEffect(lifecycleOwner) {
        var handled = false
        fun onHostGoingAway() {
            // ON_DESTROY 与 onDispose 可能都到(Activity 结束 vs 组合回收):每任宿主只决策一次。
            if (handled) return
            handled = true
            val changingConfigurations = hostActivity?.isChangingConfigurations == true
            if (viewModel.onHostDestroyed(changingConfigurations) ==
                PreviewHostDestroyAction.RELEASE_AND_RESUME
            ) {
                if (!viewModel.savedAndPlayed) musicViewModel.resume()
            }
        }
        // Activity 真正结束时组合也可能先被回收;lifecycleOwner 可能是 NavBackStackEntry,
        // 两种时机都接到同一个幂等处理上。
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) onHostGoingAway()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            onHostGoingAway()
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("无缝循环 · 分析与预览") },
                navigationIcon = {
                    IconButton(onClick = { viewModel.dispatch(LoopPageEvent.Back) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                // 引擎开关暂时收起：SOURCE 路径还在修（暂停/切换闪退），
                // 在自测全绿之前不向用户暴露，避免拿到半成品。
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        ) {
            when (val state = pageState) {
                LoopPageState.Loading -> AnalysisSkeleton(
                    song = song,
                    phase = null,
                    progress = 0f,
                )

                is LoopPageState.Analyzing -> AnalysisSkeleton(
                    song = song,
                    phase = state.phase,
                    progress = state.progress,
                )

                is LoopPageState.Failed -> FailedContent(
                    message = state.message,
                    onRetry = { viewModel.dispatch(LoopPageEvent.Retry) },
                    onBack = { viewModel.dispatch(LoopPageEvent.Back) },
                )

                is LoopPageState.Ready -> ResultContent(
                    state = state,
                    peaks = peaks,
                    song = song,
                    viewModel = viewModel,
                    onSavedAndPlay = onSavedAndPlay,
                    onExitSeamless = if (showExitSeamless) {
                        {
                            exitSeamless(
                                viewModel = musicViewModel,
                                loopIntent = loopIntent,
                                keepPlainSingleLoop = false,
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        }
    }

    LoopNoticeHost(
        songId = song?.id,
        snackbarHostState = snackbarHostState,
        onAction = { notice ->
            if (notice.kind == LoopNoticeKind.FALLBACK) {
                exitSeamless(
                    viewModel = musicViewModel,
                    loopIntent = loopIntent,
                    keepPlainSingleLoop = false,
                )
            }
        },
    )
}

// ---------------------------------------------------------------------------
// 骨架 / 进度(首帧立即渲染,任何查询/解码都异步)
// ---------------------------------------------------------------------------
@Composable
private fun AnalysisSkeleton(
    song: Song?,
    phase: AnalysisPhase?,
    progress: Float,
) {
    // 显示层再 clamp 单调(与 LoopProgress 源头单调双保险,进度条永不回退)
    val lastShown = remember { mutableFloatStateOf(0f) }
    val target = maxOf(progress, lastShown.floatValue).coerceIn(0f, 1f)
    SideEffect { lastShown.floatValue = target }
    val animated by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = 150),
        label = "loopGlobalProgress",
    )
    val phaseText = when (phase) {
        AnalysisPhase.ANALYZING -> "正在分析候选循环点…"
        AnalysisPhase.DECODING -> "正在解码音频…"
        null -> "正在读取曲目信息…"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // 曲名占位:歌曲查询异步补齐,页面打开不等查询
        Text(
            text = song?.title ?: "正在读取曲目信息…",
            style = MaterialTheme.typography.titleMedium,
            color = if (song == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        LinearProgressIndicator(
            progress = { animated },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                phaseText,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                String.format(Locale.US, "%.0f%%", animated * 100),
                style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
                textAlign = TextAlign.End,
                modifier = Modifier.widthIn(min = 52.dp),
            )
        }
        // 骨架波形占位(不阻塞首帧;解码完成后由结果页替换)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(190.dp)
                .clip(RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRect(WaveBg)
            }
            Text(
                "波形将在分析完成后显示",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF6E7681),
            )
        }
    }
}

@Composable
private fun FailedContent(message: String, onRetry: () -> Unit, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
        Text("分析失败", style = MaterialTheme.typography.titleMedium)
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("重试") }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("返回") }
    }
}

// ---------------------------------------------------------------------------
// 结果页
// ---------------------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResultContent(
    state: LoopPageState.Ready,
    peaks: WaveformPeaks?,
    song: Song?,
    viewModel: LoopViewModel,
    onSavedAndPlay: (Song) -> Unit,
    onExitSeamless: (() -> Unit)? = null,
) {
    val buffering by viewModel.buffering.collectAsState()
    val isReplaying by viewModel.isReplaying.collectAsState()

    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    val aMs = state.aMs
    val bMs = state.bMs
    val mode = state.mode
    val selectedCandidate = state.selectedCandidate
    val durationMs = state.durationMs
    val candidates = state.candidates
    val sampleRate = state.sampleRate
    val confirmed = state.confirmed
    val playingIntent = state.playback is PlaybackState.Playing ||
        state.playback is PlaybackState.AwaitingPrefill
    val prefill = state.prefill
    val firstBlockLoading = prefill is PrefillState.FirstBlockLoading
    val ringPreparing = prefill is PrefillState.InProgress || firstBlockLoading
    val prefixReady = prefillPrefixReady(prefill)
    val prefillLabel = prefillProgressLabel(prefill)

    // 弱提示:SOURCE 首块超过 300ms、M3′ 预填超过 1.5s 才显示;就绪立即隐藏。
    var showPrefillHint by remember { mutableStateOf(false) }
    LaunchedEffect(ringPreparing, firstBlockLoading) {
        if (!ringPreparing) {
            showPrefillHint = false
        } else {
            showPrefillHint = false
            delay(if (firstBlockLoading) 300L else 1_500L)
            showPrefillHint = true
        }
    }

    val loopRatio = if (durationMs > 0L) {
        ((bMs - aMs).toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val loopTooLong = !state.sourceMode && (bMs - aMs) > 300_000L

    // 60fps 插值播放头:该状态只在波形 Canvas 绘制作用域读取 → 每帧只失效 draw phase
    val playheadMs = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { }
            val next = viewModel.interpolatedPositionMs(SystemClock.elapsedRealtime())
            if (next != playheadMs.longValue) playheadMs.longValue = next
        }
    }

    var showCandidateSheet by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    // 拖拽只在首次进入某个手柄时发 Start,之后发 Move;松手统一 End + CommitWindow。
    var dragHandle by remember { mutableStateOf<LoopHandle?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            // 底部留白 = 常规 16dp + 系统导航栏 inset;最后一行按钮可完整滚到可点区域。
            .padding(bottom = 16.dp + bottomInset),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                song?.title ?: "未知曲目",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "时长 " + fmtMs(durationMs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // 波形:预渲染 Bitmap;每帧只 drawBitmap + 循环区叠加 + A/B 竖线 + 播放头
        WaveformCanvas(
            peaks = peaks,
            durationMs = durationMs,
            aMs = aMs,
            bMs = bMs,
            playheadMs = playheadMs,
            onSeek = { viewModel.dispatch(LoopPageEvent.Seek(it)) },
            onDrag = { handleA, ms ->
                val handle = if (handleA) LoopHandle.A else LoopHandle.B
                if (dragHandle != handle) {
                    viewModel.dispatch(
                        if (handleA) LoopPageEvent.DragAStart(ms) else LoopPageEvent.DragBStart(ms),
                    )
                    dragHandle = handle
                } else {
                    viewModel.dispatch(
                        if (handleA) LoopPageEvent.DragAMove(ms) else LoopPageEvent.DragBMove(ms),
                    )
                }
            },
            onDragEnd = {
                val handle = dragHandle
                dragHandle = null
                if (handle != null) {
                    when (handle) {
                        LoopHandle.A -> viewModel.dispatch(LoopPageEvent.DragAEnd)
                        LoopHandle.B -> viewModel.dispatch(LoopPageEvent.DragBEnd)
                    }
                    // 只有真的拖过手柄才提交窗口;没拖过的"点一下"绝不能 CommitWindow
                    // (否则播放头会退回模式起点:SEAM=B−5s,B 点左侧 —— issue 00023 用户报告的现象)。
                    viewModel.dispatch(LoopPageEvent.CommitWindow)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp),
        )
        if (peaks == null) {
            Text(
                "波形解码中…(可先拖拽/试听)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // 播放控制行:时间文本单独 collect position(≈10Hz),只有该 Text 重组
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledIconButton(onClick = { viewModel.dispatch(LoopPageEvent.TogglePlay) }, modifier = Modifier.size(52.dp)) {
                Icon(
                    if (playingIntent) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (playingIntent) "暂停" else "播放",
                )
            }
            FilledTonalIconButton(onClick = { viewModel.stopPreview() }, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Filled.Stop, contentDescription = "停止并回到起点")
            }
            PreviewTimeText(viewModel = viewModel, durationMs = durationMs, modifier = Modifier.weight(1f))
        }
        // 状态反馈
        when {
            buffering && !playingIntent -> Text(
                "准备试听音轨…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            isReplaying -> Text(
                "无缝环回中(接缝已生效)",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF3FB950),
            )
            showPrefillHint && prefillLabel != null -> Text(
                prefillLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.auditionOutside) {
            // 00023:区外 seek = 按文件时间自由试听;这条提示说明"现在没有在环回",以及怎么回去。
            Text(
                "循环已暂停 · 按文件时间试听(拖回 A/B 区间内自动恢复循环)",
                style = MaterialTheme.typography.bodySmall,
                color = MarkerAColor,
            )
        }

        // 四模式(契约 §2.3):点击即试听
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            val modes = PreviewMode.values()
            modes.forEachIndexed { index, previewMode ->
                SegmentedButton(
                    selected = mode == previewMode.toPageMode(),
                    onClick = { viewModel.dispatch(LoopPageEvent.SwitchMode(previewMode.toPageMode())) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                    modifier = Modifier.weight(1f),
                    label = { Text(previewMode.label, fontSize = 11.sp, maxLines = 1) },
                )
            }
        }
        // issue 00007:完整循环在 SOURCE 设备上也必须「从 0 起播」——SOURCE 时间轴表达不了前奏,
        // 该模式由播放器自动改走 M3′(见 PreviewStartPlan),所以这里不再提示「从 A 起播」。
        Text(
            modeHint(mode.toPreviewMode()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!state.sourceMode && mode == LoopPageMode.SEAM && !prefixReady && !ringPreparing) {
            Text(
                "环缓冲前缀尚未就绪:请先完整试听一轮「完整循环」或「仅循环段」,接缝检查才能无缝环回",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (loopTooLong) {
            Text(
                "循环段超过 300 秒,引擎会降级为整曲播放(不会环回)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        // A/B 时间与精调
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "A " + fmtMs(aMs),
                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                color = MarkerAColor,
            )
            Text(
                "B " + fmtMs(bMs),
                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                color = MarkerBColor,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                "循环段 " + fmtMs(bMs - aMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = { viewModel.setAFromCurrent() }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text("当前位置→A", fontSize = 12.sp, maxLines = 1)
            }
            TextButton(onClick = { viewModel.setBFromCurrent() }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text("当前位置→B", fontSize = 12.sp, maxLines = 1)
            }
            TextButton(onClick = { viewModel.seekToA() }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text("◀ A", fontSize = 12.sp, maxLines = 1)
            }
            TextButton(onClick = { viewModel.seekToSeam() }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text("◀ B−5s", fontSize = 12.sp, maxLines = 1)
            }
        }

        // 候选入口(单按钮)+ ModalBottomSheet
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "候选循环点",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            FilledTonalButton(
                onClick = { showCandidateSheet = true },
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
                Text("候选(" + candidates.size + ")", fontSize = 12.sp)
            }
        }

        // 统计行:循环段时长 + 占比色点
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "循环段 " + fmtMs(bMs - aMs) +
                    " · 前奏 " + fmtMs(aMs) +
                    " · 尾奏 " + fmtMs((durationMs - bMs).coerceAtLeast(0L)) +
                    " · 占比 " + String.format(Locale.US, "%.0f%%", loopRatio * 100),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text("●", color = loopRatioColor(loopRatio), fontSize = 12.sp)
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

        // 底部按钮组(契约 §2.4)。按钮在滚动内容里,不覆盖上方内容;
        // 滚动容器底部预留 nav bar inset,最后一行可完整滚到。
        if (onExitSeamless != null) {
            OutlinedButton(onClick = onExitSeamless, modifier = Modifier.fillMaxWidth()) {
                Text("退出无缝循环", maxLines = 1)
            }
        }
        Button(onClick = { viewModel.confirmAndSave { saved -> onSavedAndPlay(saved) } }, modifier = Modifier.fillMaxWidth()) {
            Text("确认并保存")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { viewModel.startAnalysis() }, modifier = Modifier.weight(1f)) {
                Text("重新分析", maxLines = 1)
            }
            if (confirmed) {
                OutlinedButton(
                    onClick = { viewModel.dispatch(LoopPageEvent.Demote) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("取消最终结果", maxLines = 1, fontSize = 12.sp)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            // 「删除分析结果」:仅确有结果(候选已加载/已落库)时出现;
            // 删除持久化成功后由 VM effect runner 发 NavigateBack。
            if (candidates.isNotEmpty()) {
                OutlinedButton(
                    onClick = { showDeleteDialog = true },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("删除分析结果", maxLines = 1, fontSize = 12.sp)
                }
            }
            OutlinedButton(onClick = { viewModel.dispatch(LoopPageEvent.Back) }, modifier = Modifier.weight(1f)) {
                Text("返回", maxLines = 1)
            }
        }
    }

    if (showCandidateSheet) {
        // skipPartiallyExpanded:打开即全展开,避免部分展开↔全展开中间态闪烁。
        // 列表用 weight(1f, fill=false) 获得有界高度:1 条时贴内容,10 条时可滚动;
        // 底部的 contentPadding + navigationBarsPadding 保证最后一行/选中项不被
        // 系统导航栏或 sheet 底边盖住。
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { showCandidateSheet = false }, sheetState = sheetState) {
            Text(
                "选择候选循环点",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            // 必须有**有界**高度才能滚动:ModalBottomSheet 的内容 Column 高度是 wrapContent,
            // weight(1f) 在这里换不到空间,LazyColumn 只会渲染到约一行(issue 00012:后续候选被遮挡)。
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .navigationBarsPadding(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            ) {
                itemsIndexed(candidates) { index, candidate ->
                    val candidateAMs = if (sampleRate > 0) {
                        (candidate.startSamples * 1000.0 / sampleRate).toLong()
                    } else {
                        0L
                    }
                    val candidateBMs = if (sampleRate > 0) {
                        (candidate.endSamples * 1000.0 / sampleRate).toLong()
                    } else {
                        0L
                    }
                    CandidateRow(
                        index = index,
                        isBest = index == 0,
                        isSelected = index == selectedCandidate,
                        aMs = candidateAMs,
                        bMs = candidateBMs,
                        durationMs = durationMs,
                        score = candidate.score,
                        legacyScore = candidate.legacyScore,
                        noteDistance = candidate.noteDistance,
                        loudnessDifference = candidate.loudnessDifference,
                        seamQuality = candidate.seamQuality,
                        onClick = {
                            viewModel.dispatch(LoopPageEvent.SelectCandidate(index))
                            showCandidateSheet = false
                        },
                    )
                    if (index < candidates.size - 1) HorizontalDivider()
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除分析结果") },
            text = { Text("确定删除这首歌的无缝循环分析结果吗？删除后下次进入需要重新分析。") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    viewModel.dispatch(LoopPageEvent.Delete)
                }) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("取消")
                }
            },
        )
    }
}

private fun modeHint(mode: PreviewMode): String = when (mode) {
    PreviewMode.FULL -> "从前奏开始播放,越过 B 点由音频管线无缝回到 A —— 即正式播放的实际效果"
    PreviewMode.LOOP_ONLY -> "只在 A↔B 区间内循环播放"
    PreviewMode.SEAM -> "从 B 点前 5 秒起播,重点检查 B→A 的接缝是否自然"
    PreviewMode.FREE -> "自由播放,不循环(适合完整听一遍)"
}

/** 候选弹层行:序号 + A→B 时间 + 占比/指标;点击应用并关闭,长按看排序依据。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CandidateRow(
    index: Int,
    isBest: Boolean,
    isSelected: Boolean,
    aMs: Long,
    bMs: Long,
    durationMs: Long,
    score: Double,
    /** 融合前相似度;0/未知时回退显示"综合分"(见 [candidateMetricsText])。 */
    legacyScore: Double,
    noteDistance: Double,
    loudnessDifference: Double,
    /** 接缝质量;0/未知时第二行不显示接缝。 */
    seamQuality: Double,
    onClick: () -> Unit,
) {
    var showRankingInfo by remember { mutableStateOf(false) }
    val ratio = if (durationMs > 0L) {
        ((bMs - aMs).toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = { showRankingInfo = true },
            )
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "#" + (index + 1),
            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 15.sp),
            fontWeight = FontWeight.SemiBold,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // 第一行只放 A→B/最佳标记;过长时省略号只影响时间,不挤掉占比。
            Text(
                "A " + fmtMs(aMs) + " → B " + fmtMs(bMs) +
                    (if (isBest) " · ★最佳" else ""),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            // 第二行:占比固定在前(永远可见),指标在后(空间不足时才省略)。
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("●", color = loopRatioColor(ratio), fontSize = 11.sp)
                Text(
                    "占比 " + String.format(Locale.US, "%.0f%%", ratio * 100),
                    style = MaterialTheme.typography.bodySmall,
                    color = loopRatioColor(ratio),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Text(
                    // 相似度(legacyScore)+ 接缝;seam 未知时不显示接缝,legacy 未知时回退综合分。
                    candidateMetricsText(
                        score = score,
                        legacyScore = legacyScore,
                        noteDistance = noteDistance,
                        loudnessDifference = loudnessDifference,
                        seamQuality = seamQuality,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (isSelected) {
            Icon(Icons.Filled.Check, contentDescription = "当前候选", tint = MaterialTheme.colorScheme.primary)
        }
    }
    // 长按:解释"为什么它排在这里"——排序用的是融合分,不是相似度或接缝单项。
    if (showRankingInfo) {
        AlertDialog(
            onDismissRequest = { showRankingInfo = false },
            title = { Text("排序依据") },
            text = { Text(candidateRankingText(score, legacyScore, seamQuality)) },
            confirmButton = {
                TextButton(onClick = { showRankingInfo = false }) { Text("知道了") }
            },
        )
    }
}

/** 预览时间文本:仅此 Text collect positionMs(≈10Hz),不会让整页随 tick 重组。 */
@Composable
private fun PreviewTimeText(viewModel: LoopViewModel, durationMs: Long, modifier: Modifier = Modifier) {
    val positionMs by viewModel.positionMs.collectAsState()
    Text(
        fmtMs(positionMs) + " / " + fmtMs(durationMs),
        modifier = modifier,
        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 15.sp),
    )
}

// ---------------------------------------------------------------------------
// 波形 Canvas(契约 §2.2 性能要求):
// 静态层(背景 + 网格刻度 + 暗色波形 / 绿色循环波形)预渲染为 Bitmap,
// peaks/尺寸/时长变化才重建;每帧只 drawBitmap + 循环区叠加 + A/B 竖线 + 播放头。
// 禁止每帧 3000 段 drawLine。播放头 State 只在绘制作用域读取。
// ---------------------------------------------------------------------------
@Composable
private fun WaveformCanvas(
    peaks: WaveformPeaks?,
    durationMs: Long,
    aMs: Long,
    bMs: Long,
    playheadMs: State<Long>,
    onSeek: (Long) -> Unit,
    onDrag: (Boolean, Long) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current.density
    val handleTouchPx = with(LocalDensity.current) { 24.dp.toPx() }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    val durationSec = durationMs.coerceAtLeast(0L) / 1000.0
    val aSec = aMs / 1000.0
    val bSec = bMs / 1000.0

    // 手势回调里读最新值,但 pointerInput 的 key 保持 Unit —— 拖拽中途不重启手势
    val durationSecState = rememberUpdatedState(durationSec)
    val aSecState = rememberUpdatedState(aSec)
    val bSecState = rememberUpdatedState(bSec)
    val onSeekState = rememberUpdatedState(onSeek)
    val onDragState = rememberUpdatedState(onDrag)
    val onDragEndState = rememberUpdatedState(onDragEnd)

    // A/B 标签文字测量缓存(不进每帧路径)
    val labelStyle = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MarkerLabelColor)
    val labelA = remember(textMeasurer) { textMeasurer.measure("A", labelStyle) }
    val labelB = remember(textMeasurer) { textMeasurer.measure("B", labelStyle) }

    // 静态层位图:数据/尺寸/时长变化才重建(3000 段 drawLine 只在这里发生)
    val bitmaps = remember(peaks, canvasSize, durationSec, density) {
        renderWaveBitmaps(peaks, canvasSize.width, canvasSize.height, density, durationSec)
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .onSizeChanged { canvasSize = it },
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val width = size.width.toFloat()
                        val durationMs = (durationSecState.value * 1000.0).toLong()
                        if (width <= 0f || durationMs <= 0L) return@awaitEachGesture
                        val target = WaveformGesture.handleAt(
                            x = down.position.x,
                            width = width,
                            aMs = (aSecState.value * 1000.0).toLong(),
                            bMs = (bSecState.value * 1000.0).toLong(),
                            durationMs = durationMs,
                            tolerancePx = handleTouchPx,
                        )
                        if (target == null) {
                            val up = waitForUpOrCancellation()
                            if (up != null) {
                                up.consume()
                                onSeekState.value(
                                    WaveformGesture.positionMsAt(up.position.x, width, durationMs),
                                )
                            }
                            return@awaitEachGesture
                        }
                        // 按在手柄命中区:先不急着当拖动 —— 没移动的"点一下"必须当 seek,
                        // 否则松手只会 CommitWindow,把播放头退回模式起点(SEAM = B−5s)。
                        down.consume()
                        val slopPx = handleTouchPx / 6f
                        var dragged = false
                        var lastX = down.position.x
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            change.consume()
                            lastX = change.position.x
                            if (!change.pressed) break
                            if (!dragged && abs(change.position.x - down.position.x) < slopPx) continue
                            dragged = true
                            onDragState.value(
                                target == 'A',
                                WaveformGesture.positionMsAt(change.position.x, width, durationMs),
                            )
                        }
                        when (val release = WaveformGesture.release(dragged, lastX, width, durationMs)) {
                            WaveformRelease.Commit -> onDragEndState.value()
                            is WaveformRelease.Seek -> onSeekState.value(release.positionMs)
                        }
                    }
                },
        ) {
            val width = size.width
            val height = size.height
            drawRect(WaveBg)
            bitmaps.first?.let { drawImage(it.asImageBitmap()) }
            if (durationSec <= 0.0) return@Canvas

            val xa = (aSec / durationSec * width).toFloat().coerceIn(0f, width)
            val xb = (bSec / durationSec * width).toFloat().coerceIn(0f, width)

            // 循环区高亮(波形下)
            drawRect(
                LoopRegionFill,
                topLeft = Offset(xa, 0f),
                size = Size((xb - xa).coerceAtLeast(0f), height),
            )
            // 循环区内绿色波形:clip 后叠画绿色位图
            bitmaps.second?.let { green ->
                clipRect(left = xa, top = 0f, right = xb, bottom = height) {
                    drawImage(green.asImageBitmap())
                }
            }

            // A/B 竖线 + 标签
            drawLine(MarkerAColor, Offset(xa, 0f), Offset(xa, height), strokeWidth = 2f)
            drawLine(MarkerBColor, Offset(xb, 0f), Offset(xb, height), strokeWidth = 2f)
            val labelTop = 6.dp.toPx()
            val labelHeight = 15.dp.toPx()
            val labelWidth = 20.dp.toPx()
            drawRect(MarkerAColor, topLeft = Offset(xa - labelWidth / 2f, labelTop), size = Size(labelWidth, labelHeight))
            drawRect(MarkerBColor, topLeft = Offset(xb - labelWidth / 2f, labelTop), size = Size(labelWidth, labelHeight))
            drawText(labelA, topLeft = Offset(xa - labelA.size.width / 2f, labelTop + (labelHeight - labelA.size.height) / 2f))
            drawText(labelB, topLeft = Offset(xb - labelB.size.width / 2f, labelTop + (labelHeight - labelB.size.height) / 2f))

            // 播放头(60fps 插值;State 只在此绘制作用域读取,零整屏重组)
            val posSec = playheadMs.value.coerceAtLeast(0L) / 1000.0
            val p = (posSec.coerceIn(0.0, durationSec) / durationSec * width).toFloat()
            drawLine(PlayheadColor, Offset(p, 0f), Offset(p, height), strokeWidth = 2f)
            val triangle = Path().apply {
                moveTo(p - 5.dp.toPx(), 0f)
                lineTo(p + 5.dp.toPx(), 0f)
                lineTo(p, 7.dp.toPx())
                close()
            }
            drawPath(triangle, PlayheadColor)
        }
        if (peaks == null) {
            Text(
                "波形准备中…",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF6E7681),
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

/**
 * 静态层位图:
 * - 第一张 = 背景 + 网格刻度 + 暗色全宽波形;
 * - 第二张 = 透明底绿色波形(循环区内 clip 叠画)。
 * 3000 段 drawLine 只发生在这里,每帧路径零波形绘制。
 */
private fun renderWaveBitmaps(
    peaks: WaveformPeaks?,
    widthPx: Int,
    heightPx: Int,
    density: Float,
    durationSec: Double,
): Pair<Bitmap?, Bitmap?> {
    if (widthPx <= 0 || heightPx <= 0) return null to null

    fun newBitmap(): Bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)

    val dark = newBitmap()
    val darkCanvas = android.graphics.Canvas(dark)
    darkCanvas.drawColor(WAVE_BG_ARGB)
    if (durationSec > 0.0) {
        val line = Paint().apply { color = GRID_LINE_ARGB; strokeWidth = density }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = GRID_TEXT_ARGB
            textSize = 10f * density
            typeface = Typeface.MONOSPACE
        }
        val step = niceStep(durationSec)
        var t = 0.0
        while (t <= durationSec + 0.01) {
            val x = (t / durationSec * widthPx).toFloat()
            darkCanvas.drawLine(x, 0f, x, heightPx.toFloat(), line)
            darkCanvas.drawText(fmtGrid(t), x + 3f * density, heightPx - 5f * density, text)
            t += step
        }
    }
    if (peaks != null && peaks.buckets > 0) {
        val wave = Paint().apply { color = WAVE_DIM_ARGB; strokeWidth = density }
        val mid = heightPx / 2f
        val reach = mid - 5f * density
        for (i in 0 until peaks.buckets) {
            val x = i.toFloat() / peaks.buckets * widthPx
            val y0 = mid - peaks.mx[i] * reach
            var y1 = mid - peaks.mn[i] * reach
            if (y1 <= y0) y1 = y0 + 0.5f
            darkCanvas.drawLine(x + 0.5f, y0, x + 0.5f, y1, wave)
        }
    }

    val green: Bitmap? = if (peaks != null && peaks.buckets > 0) {
        val bmp = newBitmap()
        val canvas = android.graphics.Canvas(bmp)
        val wave = Paint().apply { color = WAVE_LOOP_ARGB; strokeWidth = density }
        val mid = heightPx / 2f
        val reach = mid - 5f * density
        for (i in 0 until peaks.buckets) {
            val x = i.toFloat() / peaks.buckets * widthPx
            val y0 = mid - peaks.mx[i] * reach
            var y1 = mid - peaks.mn[i] * reach
            if (y1 <= y0) y1 = y0 + 0.5f
            canvas.drawLine(x + 0.5f, y0, x + 0.5f, y1, wave)
        }
        bmp
    } else {
        null
    }

    return dark to green
}

