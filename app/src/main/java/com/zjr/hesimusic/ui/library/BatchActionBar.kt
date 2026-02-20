package com.zjr.hesimusic.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

private val BatchActionBarHeight = 64.dp

@Composable
fun BatchActionBar(
    showRemoveFromPlaylist: Boolean,
    favoriteActionText: String,
    allSelected: Boolean,
    onSelectAllChange: (Boolean) -> Unit,
    onAddToPlaylist: () -> Unit,
    onFavoriteAction: () -> Unit,
    onExit: () -> Unit,
    onRemoveFromPlaylist: (() -> Unit)? = null
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(BatchActionBarHeight),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clickable { onSelectAllChange(!allSelected) }
                    .semantics {
                        contentDescription = if (allSelected) "清除所有歌曲选择" else "全选所有歌曲"
                    }
            ) {
                Checkbox(checked = allSelected, onCheckedChange = null)
                Text(if (allSelected) "清除" else "全选")
            }
            if (showRemoveFromPlaylist && onRemoveFromPlaylist != null) {
                TextButton(onClick = onRemoveFromPlaylist) {
                    Text("从歌单移除")
                }
            }
            TextButton(onClick = onAddToPlaylist) {
                Text("加入歌单")
            }
            TextButton(onClick = onFavoriteAction) {
                Text(favoriteActionText)
            }
            TextButton(onClick = onExit) {
                Text("退出")
            }
        }
    }
}
