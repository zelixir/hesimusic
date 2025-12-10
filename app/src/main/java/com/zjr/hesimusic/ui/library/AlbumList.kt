package com.zjr.hesimusic.ui.library

import android.util.Log
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import com.zjr.hesimusic.data.model.Album
import com.zjr.hesimusic.ui.common.MusicListItem

private const val TAG = "AlbumList"

@Composable
fun AlbumList(
    albums: List<Album>,
    onAlbumClick: (Album) -> Unit,
    modifier: Modifier = Modifier
) {
    // Log list size for performance tracking
    LaunchedEffect(albums.size) {
        Log.d(TAG, "AlbumList rendering with ${albums.size} albums")
    }
    
    LazyColumn(modifier = modifier) {
        items(
            items = albums,
            key = { "${it.name}-${it.artist}" },
            contentType = { "album" }
        ) { album ->
            MusicListItem(
                title = album.name,
                subtitle = "${album.artist} • ${album.songCount} 首歌曲",
                icon = Icons.Default.Album,
                onClick = { onAlbumClick(album) }
            )
        }
    }
}
