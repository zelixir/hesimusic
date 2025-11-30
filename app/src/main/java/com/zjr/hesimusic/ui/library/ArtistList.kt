package com.zjr.hesimusic.ui.library

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.zjr.hesimusic.data.model.Artist
import com.zjr.hesimusic.ui.common.MusicListItem

@Composable
fun ArtistList(
    artists: List<Artist>,
    onArtistClick: (Artist) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(modifier = modifier) {
        items(
            items = artists,
            key = { it.name },
            contentType = { "artist" }
        ) { artist ->
            MusicListItem(
                title = artist.name,
                subtitle = "${artist.songCount} 首歌曲",
                icon = Icons.Default.Person,
                onClick = { onArtistClick(artist) }
            )
        }
    }
}
