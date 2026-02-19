package com.zjr.hesimusic.ui.library

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.runtime.rememberCoroutineScope
import com.zjr.hesimusic.data.model.Album
import com.zjr.hesimusic.ui.common.FastScrollbar
import com.zjr.hesimusic.ui.common.MusicListItem
import com.zjr.hesimusic.utils.AlphabetIndexer
import com.zjr.hesimusic.utils.AppLogger
import kotlinx.coroutines.launch

private const val TAG = "AlbumList"

@Composable
fun AlbumList(
    albums: List<Album>,
    onAlbumClick: (Album) -> Unit,
    modifier: Modifier = Modifier,
    appLogger: AppLogger? = null
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Log list size for performance tracking
    LaunchedEffect(albums.size) {
        Log.d(TAG, "AlbumList rendering with ${albums.size} albums")
        appLogger?.info(TAG, "AlbumList rendering with ${albums.size} albums")
    }

    val sectionIndices = remember(albums) {
        buildMap {
            albums.forEachIndexed { index, album ->
                putIfAbsent(AlphabetIndexer.getInitial(album.name), index)
            }
        }
    }
    val sections = remember(sectionIndices) { sectionIndices.keys.toList() }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
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
