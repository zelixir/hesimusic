package com.zjr.hesimusic.ui.library

import android.util.Log
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import com.zjr.hesimusic.data.model.Artist
import com.zjr.hesimusic.ui.common.MusicListItem
import com.zjr.hesimusic.utils.AppLogger

private const val TAG = "ArtistList"

@Composable
fun ArtistList(
    artists: List<Artist>,
    onArtistClick: (Artist) -> Unit,
    modifier: Modifier = Modifier,
    appLogger: AppLogger? = null
) {
    // Log list size for performance tracking
    LaunchedEffect(artists.size) {
        Log.d(TAG, "ArtistList rendering with ${artists.size} artists")
        appLogger?.info(TAG, "ArtistList rendering with ${artists.size} artists")
    }
    
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
