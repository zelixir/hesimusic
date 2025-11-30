package com.zjr.hesimusic.ui.scan

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun ScanScreen(
    viewModel: ScanViewModel = hiltViewModel(),
    onDebugClick: () -> Unit = {},
    onPlayerTestClick: () -> Unit = {},
    onLibraryClick: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "音乐扫描", style = MaterialTheme.typography.headlineMedium)
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Text(text = uiState.statusMessage)

        if (uiState.isScanning || uiState.scannedCount > 0) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(text = "已扫描: ${uiState.scannedCount}")
            Text(text = "耗时: ${formatTime(uiState.elapsedTimeMs)}")
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = "当前路径: ${uiState.currentPath}", style = MaterialTheme.typography.bodySmall)
        }
        
        Spacer(modifier = Modifier.height(32.dp))

        Button(onClick = {
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
        }, enabled = !uiState.isScanning) {
            Text(text = if (uiState.isScanning) "扫描中..." else "开始扫描")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = { viewModel.clearDatabase() },
            enabled = !uiState.isScanning
        ) {
            Text(text = "清空数据库")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onLibraryClick,
            enabled = !uiState.isScanning
        ) {
            Text(text = "前往媒体库")
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

fun formatTime(ms: Long): String {
    val seconds = ms / 1000
    val minutes = seconds / 60
    val remainingSeconds = seconds % 60
    val tenths = (ms % 1000) / 100
    return String.format("%02d:%02d.%d", minutes, remainingSeconds, tenths)
}
