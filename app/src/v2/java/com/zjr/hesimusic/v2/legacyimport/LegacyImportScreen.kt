package com.zjr.hesimusic.v2.legacyimport

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zjr.hesimusic.legacyimport.LegacyImportMode
import com.zjr.hesimusic.legacyimport.LegacyImportPhase
import com.zjr.hesimusic.legacyimport.LegacyImportUiState
import com.zjr.hesimusic.legacyimport.LegacyImportViewModel

/**
 * 「导入上一版数据」页(v2 专属,见 app/src/v2/AndroidManifest.xml)。
 *
 * 界面只有一条主线:**选导出 → 看预览 → 导入**。所有数字来自 [LegacyImportViewModel]
 * 种的同一份计划,预览与执行不会给出两套口径。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LegacyImportScreen(
    viewModel: LegacyImportViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()

    val directoryPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri -> if (uri != null) viewModel.openDirectory(uri) }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) viewModel.openFile(uri) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("导入上一版数据") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            IntroCard()

            SourceCard(
                state = state,
                onPickDirectory = { directoryPicker.launch(null) },
                onPickFile = {
                    filePicker.launch(
                        arrayOf("application/json", "text/plain", "application/octet-stream")
                    )
                },
            )

            if (state.hasBundle) {
                OptionsCard(
                    state = state,
                    onModeChange = viewModel::setMode,
                    onOverwriteChange = viewModel::setOverwriteLoops,
                    onSettingsChange = viewModel::setImportSettings,
                )
            }

            if (state.previewLines.isNotEmpty()) {
                PreviewCard(lines = state.previewLines, isResult = false)
            }

            if (state.hasBundle) {
                ImportRow(state = state, onImport = viewModel::startImport)
            }

            if (state.phase == LegacyImportPhase.DONE && state.resultLines.isNotEmpty()) {
                PreviewCard(lines = state.resultLines, isResult = true)
                state.backupPath?.let { backup ->
                    Text(
                        text = "备份文件: " + backup,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (state.warnings.isNotEmpty()) {
                WarningsCard(state.warnings)
            }

            state.errorMessage?.let { message ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                ) {
                    Text(
                        text = message,
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
    }
}

@Composable
private fun IntroCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("把上一版的数据搬到 Hesi v2", style = MaterialTheme.typography.titleMedium)
            Text(
                "上一版(com.zjr.hesimusic)不会被修改或卸载:导出在电脑侧完成," +
                    "这个页面只读你选中的 JSON 文件。",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "步骤:电脑上运行 tools/export-legacy-data.ps1(会把 JSON 推到 " +
                    "Download/hessi-import/<时间>/),然后点下面的「选择导出目录」。",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "导入按歌曲的 (文件路径, CUE 起点) 匹配,可重复执行:已经存在的不会重复插入。" +
                    "写库前会自动把本机数据库备份到 app 私有目录。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun SourceCard(
    state: LegacyImportUiState,
    onPickDirectory: () -> Unit,
    onPickFile: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("1. 选择导出文件", style = MaterialTheme.typography.titleSmall)
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = onPickDirectory, enabled = !state.busy) {
                    Text("选择导出目录")
                }
                OutlinedButton(onClick = onPickFile, enabled = !state.busy) {
                    Text("选择 JSON 文件")
                }
                if (state.busy) {
                    CircularProgressIndicator(modifier = Modifier.padding(start = 4.dp))
                }
            }
            state.statusMessage?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            if (state.fileName != null) {
                Text(
                    text = "已选: " + state.fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            state.sourceInfo?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            state.contentsInfo?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun OptionsCard(
    state: LegacyImportUiState,
    onModeChange: (LegacyImportMode) -> Unit,
    onOverwriteChange: (Boolean) -> Unit,
    onSettingsChange: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("2. 导入选项", style = MaterialTheme.typography.titleSmall)

            OptionRow(
                selected = state.mode == LegacyImportMode.EVERYTHING,
                title = "全部导入",
                subtitle = "歌曲(缺的补上)+ 循环记录 + 歌单 + 收藏 + 隐藏 + SQL 歌单 + 设置",
                enabled = !state.busy,
                onClick = { onModeChange(LegacyImportMode.EVERYTHING) },
            )
            OptionRow(
                selected = state.mode == LegacyImportMode.LOOPS_ONLY,
                title = "只导入循环记录",
                subtitle = "不新增歌曲、不改歌单/收藏/设置;本机没有的歌对应的循环记录会跳过并报告",
                enabled = !state.busy,
                onClick = { onModeChange(LegacyImportMode.LOOPS_ONLY) },
            )
            OptionRow(
                selected = state.overwriteLoops,
                title = "覆盖已有的循环记录",
                subtitle = "默认保留本机已确认的结果;勾选后用上一版的结果覆盖",
                enabled = !state.busy,
                onClick = { onOverwriteChange(!state.overwriteLoops) },
                isCheckbox = true,
            )
            if (state.mode == LegacyImportMode.EVERYTHING) {
                OptionRow(
                    selected = state.importSettings,
                    title = "同时导入设置",
                    subtitle = "播放/扫描/排序偏好(不含队列、当前播放位置、循环引擎选择与图片授权)",
                    enabled = !state.busy,
                    onClick = { onSettingsChange(!state.importSettings) },
                    isCheckbox = true,
                )
            }
        }
    }
}

@Composable
private fun OptionRow(
    selected: Boolean,
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit,
    isCheckbox: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (isCheckbox) {
            Checkbox(checked = selected, onCheckedChange = { onClick() }, enabled = enabled)
        } else {
            RadioButton(selected = selected, onClick = onClick, enabled = enabled)
        }
        Column(modifier = Modifier.padding(start = 4.dp, top = 10.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PreviewCard(lines: List<String>, isResult: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (isResult) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = if (isResult) "导入结果" else "3. 预览(将写入的内容)",
                style = MaterialTheme.typography.titleSmall,
            )
            lines.forEach { line ->
                Text(line, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun ImportRow(state: LegacyImportUiState, onImport: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(
            onClick = onImport,
            enabled = !state.busy && state.phase != LegacyImportPhase.DONE,
        ) {
            Text(if (state.phase == LegacyImportPhase.DONE) "已导入" else "开始导入")
        }
        if (state.phase == LegacyImportPhase.IMPORTING) {
            CircularProgressIndicator()
            Text("正在导入…", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun WarningsCard(warnings: List<String>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("提示(已跳过的问题)", style = MaterialTheme.typography.titleSmall)
            warnings.forEach { warning ->
                Text(
                    text = "· " + warning,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
