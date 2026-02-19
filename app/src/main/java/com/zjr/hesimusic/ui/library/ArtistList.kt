package com.zjr.hesimusic.ui.library

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.zjr.hesimusic.data.model.Artist
import com.zjr.hesimusic.ui.common.FastScrollbar
import com.zjr.hesimusic.ui.common.MusicListItem
import com.zjr.hesimusic.utils.AlphabetIndexer
import com.zjr.hesimusic.utils.AppLogger
import androidx.compose.ui.Alignment
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

private const val TAG = "ArtistList"

@Composable
fun ArtistList(
    artists: List<Artist>,
    onArtistClick: (Artist) -> Unit,
    modifier: Modifier = Modifier,
    appLogger: AppLogger? = null
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Log list size for performance tracking
    LaunchedEffect(artists.size) {
        Log.d(TAG, "ArtistList rendering with ${artists.size} artists")
        appLogger?.info(TAG, "ArtistList rendering with ${artists.size} artists")
    }

    val sectionIndices = remember(artists) {
        buildMap {
            artists.forEachIndexed { index, artist ->
                putIfAbsent(AlphabetIndexer.getInitial(artist.name), index)
            }
        }
    }
    val sections = remember(sectionIndices) { sectionIndices.keys.toList() }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
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

        if (sections.isNotEmpty()) {
            FastScrollbar(
                sections = sections,
                onSectionSelected = { index ->
                    val char = sections[index]
                    val scrollIndex = sectionIndices[char] ?: 0
                    scope.launch { listState.scrollToItem(scrollIndex) }
                },
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }
    }
}
