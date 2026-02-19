package com.zjr.hesimusic.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun BatchActionBar(
    showRemoveFromPlaylist: Boolean,
    favoriteActionText: String,
    onAddToPlaylist: () -> Unit,
    onFavoriteAction: () -> Unit,
    onExit: () -> Unit,
    onRemoveFromPlaylist: (() -> Unit)? = null
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
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
