package com.zjr.hesimusic.ui.loop

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.zjr.hesimusic.loop.audio.source.LoopSourceMode

/**
 * 结果页的「播放引擎」切换:环形缓冲(旧) vs 按需解码(新)。
 *
 * 只影响**结果页预览**;正式播放(主播放器)仍然是环形缓冲引擎。
 * 切换后需要重新进入本页才生效(播放器在进页时创建)。
 */
@Composable
internal fun LoopEngineSwitch(modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier) {
    val context = LocalContext.current
    var mode by remember { mutableStateOf(LoopSourceMode.current) }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text("引擎", style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
        Spacer(modifier = androidx.compose.ui.Modifier.width(8.dp))
        FilterChip(
            selected = mode == LoopSourceMode.M3_PRIME,
            onClick = {
                LoopSourceMode.select(context, LoopSourceMode.M3_PRIME)
                mode = LoopSourceMode.M3_PRIME
            },
            label = { Text("环形缓冲") },
            colors = FilterChipDefaults.filterChipColors(),
        )
        Spacer(modifier = androidx.compose.ui.Modifier.width(6.dp))
        FilterChip(
            selected = mode == LoopSourceMode.SOURCE,
            onClick = {
                LoopSourceMode.select(context, LoopSourceMode.SOURCE)
                mode = LoopSourceMode.SOURCE
            },
            label = { Text("按需解码") },
            colors = FilterChipDefaults.filterChipColors(),
        )
    }
}
