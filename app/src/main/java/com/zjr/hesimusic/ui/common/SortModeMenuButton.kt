package com.zjr.hesimusic.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.zjr.hesimusic.data.preferences.SongSortMode

/**
 * 排序模式切换按钮：点击排序图标弹出菜单，菜单项显示当前排序模式，
 * 点击即在「首字母」与「音轨号」两种模式间切换。
 */
@Composable
fun SortModeMenuButton(
    sortMode: SongSortMode,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Default.Sort, contentDescription = "排序")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                text = { Text("排序：${sortMode.label}") },
                onClick = {
                    expanded = false
                    onToggle()
                }
            )
        }
    }
}
