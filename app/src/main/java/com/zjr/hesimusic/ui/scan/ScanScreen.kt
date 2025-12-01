package com.zjr.hesimusic.ui.scan

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

enum class FolderPickerMode {
    SCAN,
    EXCLUDE
}

@Composable
fun ScanScreen(
    viewModel: ScanViewModel = hiltViewModel(),
    onDebugClick: () -> Unit = {},
    onPlayerTestClick: () -> Unit = {},
    onLibraryClick: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var showFolderPicker by remember { mutableStateOf(false) }
    var folderPickerMode by remember { mutableStateOf(FolderPickerMode.SCAN) }

    if (showFolderPicker) {
        FolderPickerScreen(
            title = if (folderPickerMode == FolderPickerMode.SCAN) "选择扫描文件夹" else "选择屏蔽文件夹",
            initialSelectedFolders = if (folderPickerMode == FolderPickerMode.SCAN) uiState.scanFolders else uiState.excludedFolders,
            onConfirm = { folders ->
                if (folderPickerMode == FolderPickerMode.SCAN) {
                    viewModel.updateScanFolders(folders)
                } else {
                    viewModel.updateExcludedFolders(folders)
                }
                showFolderPicker = false
            },
            onDismiss = { showFolderPicker = false }
        )
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text(
                    text = "音乐扫描",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            // Scan folders section
            item {
                FolderSelectionCard(
                    title = "扫描文件夹（必选）",
                    folders = uiState.scanFolders,
                    onAddClick = {
                        folderPickerMode = FolderPickerMode.SCAN
                        showFolderPicker = true
                    },
                    onRemoveFolder = { folder ->
                        viewModel.updateScanFolders(uiState.scanFolders - folder)
                    },
                    isEmpty = uiState.scanFolders.isEmpty(),
                    emptyMessage = "请点击添加要扫描的文件夹"
                )
            }

            // Excluded folders section
            item {
                FolderSelectionCard(
                    title = "屏蔽文件夹（可选）",
                    folders = uiState.excludedFolders,
                    onAddClick = {
                        folderPickerMode = FolderPickerMode.EXCLUDE
                        showFolderPicker = true
                    },
                    onRemoveFolder = { folder ->
                        viewModel.updateExcludedFolders(uiState.excludedFolders - folder)
                    },
                    isEmpty = uiState.excludedFolders.isEmpty(),
                    emptyMessage = "未设置屏蔽文件夹"
                )
            }

            // Status section
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(text = uiState.statusMessage)

                        if (uiState.isScanning || uiState.scannedCount > 0) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(text = "已扫描: ${uiState.scannedCount}")
                            Text(text = "耗时: ${formatTime(uiState.elapsedTimeMs)}")
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "当前路径: ${uiState.currentPath}",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        if (uiState.isScanning) {
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }

            // Action buttons
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                if (!Environment.isExternalStorageManager()) {
                                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                                    intent.data = Uri.parse("package:${context.packageName}")
                                    context.startActivity(intent)
                                } else {
                                    viewModel.startScan()
                                }
                            } else {
                                viewModel.startScan()
                            }
                        },
                        enabled = !uiState.isScanning && uiState.scanFolders.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = if (uiState.isScanning) "扫描中..." else "开始扫描")
                    }

                    Button(
                        onClick = { viewModel.clearDatabase() },
                        enabled = !uiState.isScanning,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = "清空数据库")
                    }

                    Button(
                        onClick = onLibraryClick,
                        enabled = !uiState.isScanning,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = "前往媒体库")
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderSelectionCard(
    title: String,
    folders: Set<String>,
    onAddClick: () -> Unit,
    onRemoveFolder: (String) -> Unit,
    isEmpty: Boolean,
    emptyMessage: String
) {
    val rootPath = Environment.getExternalStorageDirectory().absolutePath

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium
                )
                TextButton(onClick = onAddClick) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text("添加")
                }
            }

            if (isEmpty) {
                Text(
                    text = emptyMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            } else {
                folders.forEach { folder ->
                    ListItem(
                        headlineContent = {
                            Text(
                                text = folder.removePrefix(rootPath).ifEmpty { "/" },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        leadingContent = {
                            Icon(Icons.Default.Folder, contentDescription = null)
                        },
                        trailingContent = {
                            IconButton(onClick = { onRemoveFolder(folder) }) {
                                Icon(Icons.Default.Close, contentDescription = "删除")
                            }
                        }
                    )
                }
            }
        }
    }
}

fun formatTime(ms: Long): String {
    val seconds = ms / 1000
    val minutes = seconds / 60
    val remainingSeconds = seconds % 60
    val tenths = (ms % 1000) / 100
    return String.format("%02d:%02d.%d", minutes, remainingSeconds, tenths)
}
