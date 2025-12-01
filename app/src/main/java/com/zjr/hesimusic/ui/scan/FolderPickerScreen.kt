package com.zjr.hesimusic.ui.scan

import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.File

data class FolderItem(
    val path: String,
    val name: String,
    val hasSubfolders: Boolean
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderPickerScreen(
    title: String,
    initialSelectedFolders: Set<String>,
    onConfirm: (Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    val rootPath = Environment.getExternalStorageDirectory().absolutePath
    var currentPath by remember { mutableStateOf(rootPath) }
    var selectedFolders by remember { mutableStateOf(initialSelectedFolders) }

    BackHandler(enabled = currentPath != rootPath) {
        val parent = File(currentPath).parent
        if (parent != null && parent.startsWith(rootPath)) {
            currentPath = parent
        } else {
            currentPath = rootPath
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = { onConfirm(selectedFolders) }) {
                        Text("确定")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Current path display
            Text(
                text = currentPath.removePrefix(rootPath).ifEmpty { "/" },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Folder list
            val folders = remember(currentPath) {
                getFolders(currentPath)
            }

            LazyColumn {
                // Parent directory navigation
                if (currentPath != rootPath) {
                    item {
                        ListItem(
                            headlineContent = { Text("..") },
                            supportingContent = { Text("上级目录") },
                            leadingContent = {
                                Icon(Icons.Default.Folder, contentDescription = null)
                            },
                            modifier = Modifier.clickable {
                                val parent = File(currentPath).parent
                                if (parent != null && parent.startsWith(rootPath)) {
                                    currentPath = parent
                                } else {
                                    currentPath = rootPath
                                }
                            }
                        )
                    }
                }

                items(folders, key = { it.path }) { folder ->
                    val isSelected = selectedFolders.contains(folder.path)
                    val isParentSelected = selectedFolders.any { folder.path.startsWith("$it/") }
                    
                    ListItem(
                        headlineContent = { Text(folder.name) },
                        supportingContent = {
                            if (isParentSelected) {
                                Text("父文件夹已选中", color = MaterialTheme.colorScheme.primary)
                            }
                        },
                        leadingContent = {
                            Icon(Icons.Default.Folder, contentDescription = null)
                        },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = isSelected || isParentSelected,
                                    onCheckedChange = { checked ->
                                        if (!isParentSelected) {
                                            selectedFolders = if (checked) {
                                                // Remove any child folders when selecting parent
                                                selectedFolders.filter { !it.startsWith("${folder.path}/") }.toSet() + folder.path
                                            } else {
                                                selectedFolders - folder.path
                                            }
                                        }
                                    },
                                    enabled = !isParentSelected
                                )
                            }
                        },
                        modifier = Modifier.clickable {
                            if (folder.hasSubfolders) {
                                currentPath = folder.path
                            }
                        }
                    )
                }
            }
        }
    }
}

private fun getFolders(path: String): List<FolderItem> {
    val dir = File(path)
    val files = dir.listFiles() ?: return emptyList()
    
    return files
        .filter { it.isDirectory && !it.isHidden && !it.name.startsWith(".") }
        .sortedBy { it.name.lowercase() }
        .map { file ->
            val subFiles = file.listFiles()
            val hasSubfolders = subFiles?.any { it.isDirectory && !it.isHidden } == true
            FolderItem(
                path = file.absolutePath,
                name = file.name,
                hasSubfolders = hasSubfolders
            )
        }
}
