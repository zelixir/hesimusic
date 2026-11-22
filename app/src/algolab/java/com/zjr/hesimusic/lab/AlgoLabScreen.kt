package com.zjr.hesimusic.lab

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zjr.hesimusic.data.model.Song
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlgoLabScreen(viewModel: AlgoLabViewModel) {
    val state by viewModel.ui.collectAsState()
    var showSongPicker by remember { mutableStateOf(false) }
    var showScanDialog by remember { mutableStateOf(false) }

    if (showSongPicker) {
        SongPickerDialog(
            songs = state.songs,
            onDismiss = { showSongPicker = false },
            onPick = { song ->
                viewModel.selectSong(song)
                showSongPicker = false
            },
            onOpenScan = {
                showSongPicker = false
                viewModel.refreshPermissionState()
                showScanDialog = true
            },
        )
    }

    if (showScanDialog) {
        ScanDialog(
            state = state,
            onDismiss = { showScanDialog = false },
            onFolderChange = viewModel::setScanFolder,
            onRequestAccess = viewModel::requestAllFilesAccess,
            onRefreshPermission = viewModel::refreshPermissionState,
            onScan = viewModel::startLibraryScan,
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Algo Lab · 循环算法对比",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item("song") {
                SongAndRunCard(
                    state = state,
                    onPickSong = {
                        // 从扫描界面返回后曲库可能已变;每次打开选择器都刷新一次。
                        viewModel.refreshSongs()
                        showSongPicker = true
                    },
                    onScanLibrary = {
                        viewModel.refreshPermissionState()
                        showScanDialog = true
                    },
                    onPathChange = viewModel::setPathInput,
                    onAnalyze = viewModel::analyze,
                    onCancel = viewModel::cancel,
                    onClear = viewModel::clearResult,
                )
            }
            item("params") {
                ParamsCard(
                    state = state,
                    onUpdate = viewModel::updateParams,
                    onReferenceA = viewModel::setReferenceA,
                    onReferenceB = viewModel::setReferenceB,
                )
            }
            if (state.running || state.phase.isNotEmpty()) {
                item("progress") { ProgressCard(state) }
            }
            state.error?.let { message ->
                item("error") {
                    MessageCard(
                        text = message,
                        color = BadColor,
                        onDismiss = viewModel::dismissError,
                    )
                }
            }
            state.statusMessage?.let { message ->
                item("status") {
                    MessageCard(
                        text = message,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        onDismiss = viewModel::dismissStatus,
                    )
                }
            }
            state.result?.let { result ->
                item("metrics") {
                    MeasurementsCard(
                        result = result,
                        onCopyPath = viewModel::copyPath,
                    )
                }
                item("summary") { SummaryCard(result) }
                if (result.sweep.isNotEmpty()) {
                    item("sweep") { SweepCard(result.sweep, result.summary.topN) }
                }
                item("export") {
                    ExportCard(
                        state = state,
                        onExport = viewModel::export,
                        onCopyPath = viewModel::copyPath,
                    )
                }
                item("table") {
                    CandidateTableCard(
                        state = state,
                        result = result,
                        onSortMode = viewModel::setSortMode,
                        onOnlyTopN = viewModel::setOnlyTopN,
                    )
                }
                item("legend") { TableLegend(result) }
            }
        }
    }
}

@Composable
private fun SongAndRunCard(
    state: LabUiState,
    onPickSong: () -> Unit,
    onScanLibrary: () -> Unit,
    onPathChange: (String) -> Unit,
    onAnalyze: () -> Unit,
    onCancel: () -> Unit,
    onClear: () -> Unit,
) {
    LabCard(title = "1. 选歌 + 运行") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    state.selectedSong?.title ?: "未选择曲库歌曲",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    state.selectedSong?.let { "${it.artist} · ${it.album}" } ?: "可直接在下方输入文件绝对路径",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            OutlinedButton(onClick = onPickSong, enabled = !state.running) {
                Text("从曲库选择")
            }
        }
        OutlinedTextField(
            value = state.pathInput,
            onValueChange = onPathChange,
            label = { Text("音频文件绝对路径") },
            singleLine = true,
            enabled = !state.running,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = onScanLibrary,
                enabled = !state.running && !state.scanning,
            ) { Text("扫描曲库") }
            Spacer(Modifier.width(8.dp))
            Text(
                if (state.scanning) "扫描中…" else "algolab 独立数据库,首次使用先扫描",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "曲库 ${state.songs.size} 首(不含 CUE)。路径模式读取任意目录需要「所有文件访问」权限;" +
                "曲库歌曲由 algolab 自己的扫描流程授权,通常无需再次授权。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onAnalyze,
                enabled = !state.running && !state.scanning,
            ) {
                Text(if (state.result == null) "开始分析" else "重新分析")
            }
            OutlinedButton(onClick = onCancel, enabled = state.running) { Text("取消") }
            TextButton(onClick = onClear, enabled = !state.running && state.result != null) {
                Text("清空结果")
            }
        }
        Text(
            "一次解码 + 一次分析 → 同时输出初版与融合两套分数/排名;" +
                "不做第二次解码,对比不受解码噪声影响。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ParamsCard(
    state: LabUiState,
    onUpdate: ((LabParams) -> LabParams) -> Unit,
    onReferenceA: (String) -> Unit,
    onReferenceB: (String) -> Unit,
) {
    val params = state.params
    val enabled = !state.running
    val weights = remember(params.corrWeight, params.rmsWeight, params.fluxWeight) {
        params.normalizedWeights()
    }
    LabCard(title = "2. 参数") {
        SwitchRow(
            title = "接缝融合(双算法输出)",
            checked = params.seamEnabled,
            enabled = enabled,
            subtitle = if (params.seamEnabled) {
                "开:同时测量接缝质量,输出初版 + 融合两套排名(默认)"
            } else {
                "关:只跑初版路径 = 性能基线,没有 corr/rms/flux 分项"
            },
            onCheckedChange = { value -> onUpdate { it.copy(seamEnabled = value) } },
        )
        WeightSlider(
            label = "融合权重 w(初版 ↔ 接缝)",
            value = params.seamWeight,
            range = 0.0..1.0,
            enabled = enabled,
            valueText = "${fmt(params.seamWeight, 2)}",
            onChange = { value -> onUpdate { it.copy(seamWeight = value) } },
        )
        Text(
            "score = (1-w)·legacyScore + w·seamQuality;默认 0.50。扫描建议 0.0 → 1.0(9 点)。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider()
        Text("接缝质量分项权重(引擎按和归一化)", style = MaterialTheme.typography.labelLarge)
        WeightSlider(
            label = "corr 波形相关",
            value = params.corrWeight,
            range = 0.0..1.0,
            enabled = enabled,
            valueText = fmt(params.corrWeight, 2),
            onChange = { value -> onUpdate { it.copy(corrWeight = value) } },
        )
        WeightSlider(
            label = "RMS 能量跳变",
            value = params.rmsWeight,
            range = 0.0..1.0,
            enabled = enabled,
            valueText = fmt(params.rmsWeight, 2),
            onChange = { value -> onUpdate { it.copy(rmsWeight = value) } },
        )
        WeightSlider(
            label = "flux 频谱通量",
            value = params.fluxWeight,
            range = 0.0..1.0,
            enabled = enabled,
            valueText = fmt(params.fluxWeight, 2),
            onChange = { value -> onUpdate { it.copy(fluxWeight = value) } },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "归一化后: corr ${pct(weights[0])} / RMS ${pct(weights[1])} / flux ${pct(weights[2])}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = {
                    onUpdate {
                        it.copy(corrWeight = 0.50, rmsWeight = 0.30, fluxWeight = 0.20)
                    }
                },
                enabled = enabled,
            ) { Text("恢复 0.50/0.30/0.20") }
        }
        HorizontalDivider()
        SwitchRow(
            title = "亚拍 refine",
            checked = params.refineEnabled,
            enabled = enabled,
            subtitle = "对拍点做 ±${params.refineRangeMs} ms 局部搜索;采用前用完整接缝分复核(+0.02 门槛)",
            onCheckedChange = { value -> onUpdate { it.copy(refineEnabled = value) } },
        )
        WeightSlider(
            label = "refine 搜索范围 ±ms",
            value = params.refineRangeMs.toDouble(),
            range = 0.0..60.0,
            enabled = enabled && params.refineEnabled,
            valueText = "${params.refineRangeMs} ms",
            onChange = { value -> onUpdate { it.copy(refineRangeMs = value.roundToInt()) } },
        )
        WeightSlider(
            label = "候选数量 N",
            value = params.maxCandidates.toDouble(),
            range = 3.0..40.0,
            enabled = enabled,
            valueText = "${params.maxCandidates}",
            onChange = { value -> onUpdate { it.copy(maxCandidates = value.roundToInt()) } },
        )
        HorizontalDivider()
        Text("参考标注(可选,ms):填了才会算与参考 A/B 的偏差", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = state.referenceAMs,
                onValueChange = onReferenceA,
                label = { Text("参考 A (ms)") },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = state.referenceBMs,
                onValueChange = onReferenceB,
                label = { Text("参考 B (ms)") },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ProgressCard(state: LabUiState) {
    LabCard(title = "分析进度") {
        LinearProgressIndicator(
            progress = { state.progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            state.phase.ifEmpty { if (state.progress >= 1f) "完成" else "准备中…" },
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "解码 0–30%,引擎 30–100%(接缝阶段在引擎后半段)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MessageCard(text: String, color: Color, onDismiss: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = text,
                color = color,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) { Text("知道了") }
        }
    }
}

@Composable
private fun MeasurementsCard(result: LabResult, onCopyPath: (String) -> Unit) {
    val timing = result.timing
    val memory = result.memory
    LabCard(title = "3. 性能") {
        MetricRow("解码耗时", "${timing.decodeMs} ms")
        MetricRow("分析总耗时(JNI)", "${timing.analyzeMs} ms")
        MetricRow("接缝阶段耗时", "${fmt(timing.seamStageMs, 1)} ms")
        MetricRow("每候选接缝耗时", "${fmt(timing.perCandidateMs, 2)} ms / 候选")
        MetricRow("端到端耗时", "${timing.totalMs} ms")
        MetricRow(
            "估算分析耗时增幅",
            "${fmt(timing.estimatedOverheadPercent, 1)}%(分母 = 分析总耗时 − 接缝阶段)",
        )
        MetricRow(
            "内存峰值(Java / native / PSS)",
            "${fmtBytes(memory.peakJavaBytes)} / ${fmtBytes(memory.peakNativeBytes)} / " +
                "${if (memory.peakPssKb > 0) "${memory.peakPssKb} KB" else "—"}" +
                if (memory.sampled) "" else "(未采样)",
        )
        Spacer(Modifier.width(4.dp))
        Text(
            "内存峰值按 ≤10Hz 采样取最大,是近似口径,不是系统级峰值。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "歌曲: ${result.song.title}  ·  ${result.song.sampleRate} Hz  ·  " +
                "${fmt(result.song.frames / 1000.0, 1)}k 帧",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "路径: ${result.song.path}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        TextButton(onClick = { onCopyPath(result.song.path) }) { Text("复制歌曲路径") }
    }
}

@Composable
private fun SummaryCard(result: LabResult) {
    val summary = result.summary
    val timing = result.timing
    val overhead = timing.estimatedOverheadPercent
    LabCard(title = "4. 双算法对比汇总") {
        MetricRow("候选池 / 已评分拍对", "${result.raw.candidates.size} / ${result.raw.scoredPairs}")
        MetricRow(
            "前 5 名重合",
            "${summary.overlapAt5}/5(${pct(summary.overlapAt5Fraction)})",
            valueColor = if (summary.overlapAt5 >= 4) GoodColor else WarnColor,
        )
        MetricRow(
            "前 10 名重合",
            "${summary.overlapAt10}/10(${pct(summary.overlapAt10Fraction)})",
        )
        MetricRow(
            "前 N 名重合(N=${summary.topN})",
            "${summary.overlapAtN}/${summary.topN}(${pct(summary.overlapAtNFraction)})",
        )
        MetricRow("top1 是否更换", if (summary.top1Changed) "是" else "否")
        MetricRow(
            "新增 / 消失(前 N)",
            "${summary.newInFused.size} / ${summary.missingFromFused.size}",
        )
        MetricRow(
            "平均 Δ分(前 N 并集)",
            signed(summary.avgDeltaScoreTopN),
            valueColor = if (summary.avgDeltaScoreTopN >= 0) GoodColor else BadColor,
        )
        MetricRow("平均 |Δ分|(整个池)", fmt(summary.avgAbsDeltaScorePool))
        MetricRow(
            "最大名次变化",
            "${summary.maxRankShift}" +
                (summary.maxRankShiftCandidate?.let { " (初版 #${it.legacyRank + 1})" } ?: ""),
        )
        MetricRow(
            "接缝均值: 初版前 N → 融合前 N",
            "${fmt(summary.avgSeamQualityLegacyTop)} → ${fmt(summary.avgSeamQualityFusedTop)}",
            valueColor = if (summary.avgSeamQualityFusedTop > summary.avgSeamQualityLegacyTop) {
                GoodColor
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        MetricRow(
            "top1 接缝: 初版 → 融合",
            "${fmt(summary.legacyTop1Seam)} → ${fmt(summary.fusedTop1Seam)}",
        )
        MetricRow(
            "前 N 最大初版分让步",
            signed(summary.worstLegacyScoreDropTopN),
            valueColor = if (summary.worstLegacyScoreDropTopN >= -0.15) GoodColor else BadColor,
        )
        if (!summary.seamMeasured) {
            Text(
                "接缝未测量(接缝融合已关闭):只能对比初版自身,请打开接缝融合后再做新旧对比。",
                color = WarnColor,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        result.accuracy?.let { reference ->
            HorizontalDivider()
            Text("参考标注准确度", style = MaterialTheme.typography.labelLarge)
            Text(
                "参考 A/B = ${fmt(reference.referenceStartMs, 1)} / ${fmt(reference.referenceEndMs, 1)} ms",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            AccuracyLine("初版", reference.legacy)
            AccuracyLine("融合", reference.fused)
            Text(
                "「最近候选」按两端偏差的欧氏距离选取;±50ms 内计数用于粗略容忍度。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider()
        Text("客观判据(初步)", style = MaterialTheme.typography.labelLarge)
        JudgmentLine("前 5 名重合 ≥ 4/5", summary.overlapAt5 >= 4)
        JudgmentLine(
            "融合前 N 的接缝均值高于初版",
            summary.avgSeamQualityFusedTop > summary.avgSeamQualityLegacyTop,
        )
        JudgmentLine("估算分析耗时增幅 ≤ 20%", overhead <= 20.0)
        JudgmentLine("前 N 最大初版分让步 ≥ -0.15", summary.worstLegacyScoreDropTopN >= -0.15)
        val passCount = listOf(
            summary.overlapAt5 >= 4,
            summary.avgSeamQualityFusedTop > summary.avgSeamQualityLegacyTop,
            overhead <= 20.0,
            summary.worstLegacyScoreDropTopN >= -0.15,
        ).count { it }
        val verdict = when {
            !summary.seamMeasured -> "无法判定(接缝未测量)"
            passCount == 4 -> "建议接受(仍需 A/B 试听)"
            passCount >= 2 -> "需试听复核"
            else -> "建议拒绝"
        }
        Text(
            "初步判定:$verdict   (${passCount}/4 项通过)",
            style = MaterialTheme.typography.titleSmall,
            color = when {
                !summary.seamMeasured -> WarnColor
                passCount == 4 -> GoodColor
                passCount >= 2 -> WarnColor
                else -> BadColor
            },
        )
        Text(
            "这是客观指标初筛;最终以「同歌、同 N、仅改融合开关/权重」的试听与实测为准。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ExportCard(
    state: LabUiState,
    onExport: () -> Unit,
    onCopyPath: (String) -> Unit,
) {
    LabCard(title = "5. 导出(JSON + CSV)") {
        Text(
            "目录: ${state.exportDir}",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
        TextButton(onClick = { onCopyPath(state.exportDir) }) { Text("复制目录") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onExport, enabled = !state.running) { Text("导出本次结果") }
        }
        if (state.exportFiles.isNotEmpty()) {
            state.exportFiles.forEach { path ->
                Text(
                    path,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onCopyPath(path) }
                        .padding(vertical = 2.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            "电脑端拉取示例(USB 调试):adb pull \"${state.exportDir}\" .",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            "CSV 列:候选明细(名次/分数/位置/seam 分项)与 seamWeight 扫描;" +
                "JSON 另含歌曲信息、参数、耗时、内存、汇总与参考偏差。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TableLegend(result: LabResult) {
    Text(
        "表注:A/B 为融合排名输出坐标(开启 refine 时已精修);初版输出坐标见导出 anchor_*。" +
            "corr/RMS/flux 为归一化 0..1 分项;Δ# = 融合名次 − 初版名次(负数 = 上升)。" +
            "新# 是接缝候选池内名次,初# 是全部已评分拍对的全局名次(外卡候选的跨度会很大)。" +
            "名次后的 ＋ = 融合前 N 新增,－ = 初版前 N 消失;" +
            "N = ${result.summary.topN},候选池 = ${result.raw.candidates.size}。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun AccuracyLine(label: String, accuracy: AlgoAccuracy) {
    val candidate = accuracy.candidate
    val text = if (candidate == null) {
        "—"
    } else {
        "候选 #${candidate.legacyRank + 1}(#新 ${candidate.fusedRank + 1});" +
            " ΔA ${signed(accuracy.startDeviationMs ?: Double.NaN, 1)} ms," +
            " ΔB ${signed(accuracy.endDeviationMs ?: Double.NaN, 1)} ms," +
            " 距离 ${fmt(accuracy.distanceMs ?: Double.NaN, 1)} ms"
    }
    MetricRow(
        "$label 最近参考候选",
        text,
        valueColor = if (candidate != null &&
            (accuracy.distanceMs ?: Double.MAX_VALUE) <= 100.0
        ) {
            GoodColor
        } else {
            MaterialTheme.colorScheme.onSurface
        },
    )
    MetricRow(
        "$label 容差计数(±50 / ±100 ms)",
        "${accuracy.within50Ms} / ${accuracy.within100Ms}",
    )
}

@Composable
private fun JudgmentLine(text: String, pass: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (pass) "✓" else "✗",
            color = if (pass) GoodColor else BadColor,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            modifier = Modifier.width(22.dp),
        )
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun LabCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun MetricRow(
    label: String,
    value: String,
    valueColor: Color = Color.Unspecified,
) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = valueColor,
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    enabled: Boolean,
    subtitle: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun WeightSlider(
    label: String,
    value: Double,
    range: ClosedFloatingPointRange<Double>,
    enabled: Boolean,
    valueText: String,
    onChange: (Double) -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                valueText,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toDouble()) },
            valueRange = range.start.toFloat()..range.endInclusive.toFloat(),
            enabled = enabled,
        )
    }
}

@Composable
private fun ScanDialog(
    state: LabUiState,
    onDismiss: () -> Unit,
    onFolderChange: (String) -> Unit,
    onRequestAccess: () -> Unit,
    onRefreshPermission: () -> Unit,
    onScan: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        title = { Text("扫描曲库(algolab 独立数据库)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!state.hasAllFilesAccess) {
                    Text(
                        "需要「所有文件访问」权限才能遍历曲库目录。",
                        color = WarnColor,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onRequestAccess) { Text("去授权") }
                        OutlinedButton(onClick = onRefreshPermission) { Text("重新检查权限") }
                    }
                } else {
                    Text(
                        "已获得「所有文件访问」权限。",
                        color = GoodColor,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedTextField(
                    value = state.scanFolder,
                    onValueChange = onFolderChange,
                    label = { Text("扫描目录") },
                    singleLine = true,
                    enabled = !state.scanning,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state.scanning) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                Text(
                    state.scanStatus.ifBlank {
                        "选择目录后开始扫描;扫描会重建 algolab 的 songs 表。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                if (state.scanCount > 0) {
                    Text("已发现 ${state.scanCount} 首", style = MaterialTheme.typography.bodySmall)
                }
                Button(
                    onClick = onScan,
                    enabled = !state.scanning && state.hasAllFilesAccess && !state.running,
                ) {
                    Text(if (state.scanning) "扫描中…" else "开始扫描")
                }
                Text(
                    "扫描会清空并重建 algolab 自己的 songs 表(与正式版数据库无关)。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

@Composable
private fun SongPickerDialog(
    songs: List<Song>,
    onDismiss: () -> Unit,
    onPick: (Song) -> Unit,
    onOpenScan: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, songs) {
        if (query.isBlank()) {
            songs.take(500)
        } else {
            songs.asSequence()
                .filter {
                    it.title.contains(query, ignoreCase = true) ||
                        it.artist.contains(query, ignoreCase = true) ||
                        it.filePath.contains(query, ignoreCase = true)
                }
                .take(500)
                .toList()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        title = { Text("从曲库选择(${songs.size} 首)") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("筛选标题 / 歌手 / 路径") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.width(4.dp))
                if (songs.isEmpty()) {
                    Text(
                        "algolab 是独立包名/独立数据库,不会自动继承正式版的曲库。" +
                            "先扫描一次曲库目录(或用路径模式),扫描结果只写入 algolab 自己的数据库。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = onOpenScan) {
                        Text("扫描曲库")
                    }
                } else {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
                        items(filtered, key = { it.id }) { song ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(song) }
                                    .padding(vertical = 6.dp),
                            ) {
                                Text(
                                    song.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    "${song.artist} · ${song.filePath}",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
    )
}


