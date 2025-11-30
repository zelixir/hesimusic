package com.zjr.hesimusic.ui.library

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.zjr.hesimusic.data.model.Album
import com.zjr.hesimusic.ui.common.MusicListItem

@Composable
fun AlbumList(
    albums: List<Album>,
    onAlbumClick: (Album) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(modifier = modifier) {
        items(albums, key = { "${it.name}-${it.artist}" }) { album ->
            MusicListItem(
                title = album.name,
                subtitle = "${album.artist} • ${album.songCount} songs",
                icon = Icons.Default.Album,
                onClick = { onAlbumClick(album) }
            )
        }
    }
}
