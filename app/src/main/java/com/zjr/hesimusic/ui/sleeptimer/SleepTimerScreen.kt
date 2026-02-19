package com.zjr.hesimusic.ui.sleeptimer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.zjr.hesimusic.ui.common.MusicViewModel
import com.zjr.hesimusic.utils.TimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepTimerScreen(
    onBackClick: () -> Unit,
    viewModel: MusicViewModel = hiltViewModel()
) {
    val remainingTime by viewModel.sleepTimerState.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("睡眠定时") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (remainingTime != null) {
                Text("剩余时间: ${TimeFormatter.formatTime(remainingTime)}")
                Button(
                    onClick = { viewModel.cancelSleepTimer() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("取消定时")
                }
            } else {
                Text("选择睡眠定时时长")
                listOf(15, 30, 45, 60).forEach { minutes ->
                    FilledTonalButton(
                        onClick = { viewModel.startSleepTimer(minutes) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("$minutes 分钟")
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text("时间到后将自动暂停播放")
        }
    }
}
