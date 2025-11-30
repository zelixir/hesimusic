package com.zjr.hesimusic.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zjr.hesimusic.data.model.Song
import com.zjr.hesimusic.ui.common.FastScrollbar
import com.zjr.hesimusic.ui.common.MusicListItem
import com.zjr.hesimusic.utils.AlphabetIndexer
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongList(
    songs: List<Song>,
    currentPlayingSongId: String? = null,
    onSongClick: (List<Song>, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val grouped = remember(songs) {
        songs.groupBy { AlphabetIndexer.getInitial(it.title) }
            .toSortedMap()
    }
    
    val flattenedSongs = remember(grouped) {
        grouped.values.flatten()
    }

    // Calculate the scroll position for each section
    val sectionIndices = remember(grouped) {
        val indices = mutableMapOf<Char, Int>()
        var currentIndex = 0
        grouped.forEach { (key, list) ->
            indices[key] = currentIndex
            currentIndex += 1 + list.size // 1 for header, list.size for items
        }
        indices
    }

    val sections = remember(grouped) { grouped.keys.toList() }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize()
        ) {
            grouped.forEach { (initial, songsInGroup) ->
                stickyHeader {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = initial.toString(),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.titleSmall
                        )
                    }
                }
                
                items(songsInGroup, key = { it.id }) { song ->
                    MusicListItem(
                        title = song.title,
                        subtitle = "${song.artist} - ${song.album}",
                        isCurrent = song.id.toString() == currentPlayingSongId,
                        onClick = { 
                            val index = flattenedSongs.indexOf(song)
                            if (index != -1) {
                                onSongClick(flattenedSongs, index)
                            }
                        }
                    )
                }
            }
        }

        if (sections.isNotEmpty()) {
            FastScrollbar(
                sections = sections,
                onSectionSelected = { index ->
                    val char = sections[index]
                    val scrollIndex = sectionIndices[char] ?: 0
                    scope.launch {
                        listState.scrollToItem(scrollIndex)
                    }
                },
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }
    }
}
