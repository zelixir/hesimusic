package com.zjr.hesimusic.ui.equalizer

import android.media.audiofx.Equalizer
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.weight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.zjr.hesimusic.ui.common.MusicViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqualizerScreen(
    onBackClick: () -> Unit,
    viewModel: MusicViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var equalizer by remember { mutableStateOf<Equalizer?>(null) }
    var bands by remember { mutableStateOf<List<Short>>(emptyList()) }
    var minLevel by remember { mutableStateOf<Short>(0) }
    var maxLevel by remember { mutableStateOf<Short>(0) }

    DisposableEffect(uiState.audioSessionId) {
        if (uiState.audioSessionId == 0) {
            equalizer = null
            bands = emptyList()
            return@DisposableEffect onDispose { }
        }

        val eq = try {
            Equalizer(0, uiState.audioSessionId).apply { enabled = true }
        } catch (_: Exception) {
            Toast.makeText(context, "初始化均衡器失败", Toast.LENGTH_SHORT).show()
            null
        }

        equalizer = eq
        if (eq != null) {
            minLevel = eq.bandLevelRange[0]
            maxLevel = eq.bandLevelRange[1]
            bands = List(eq.numberOfBands.toInt()) { i -> eq.getBandLevel(i.toShort()) }
        } else {
            bands = emptyList()
        }

        onDispose { eq?.release() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("均衡器") },
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
            if (uiState.audioSessionId == 0) {
                Text("请先播放歌曲后再调整均衡器")
            } else if (equalizer == null) {
                Text("均衡器不可用")
            } else {
                bands.forEachIndexed { index, level ->
                    val freq = equalizer?.getCenterFreq(index.toShort())?.div(1000) ?: 0
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text("${freq}Hz", modifier = Modifier.width(68.dp))
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
        }
    }
}
