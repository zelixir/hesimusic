package com.zjr.hesimusic.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zjr.hesimusic.data.model.Song
import com.zjr.hesimusic.ui.common.FastScrollbar
import com.zjr.hesimusic.ui.common.MusicListItem
import com.zjr.hesimusic.utils.AlphabetIndexer
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    val grouped = produceState<Map<Char, List<Song>>>(initialValue = emptyMap(), key1 = songs) {
        value = withContext(Dispatchers.Default) {
            songs.groupBy { AlphabetIndexer.getInitial(it.title) }
                .toSortedMap()
        }
    }.value
    
    val flattenedSongs = remember(grouped) {
        grouped.values.flatten()
    }

    // Calculate the starting index for each group for display
    val groupStartingIndices = remember(grouped) {
        val indices = mutableMapOf<Char, Int>()
        var currentCount = 0
        grouped.forEach { (key, list) ->
            indices[key] = currentCount
            currentCount += list.size
        }
        indices
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

    // Auto-scroll to currently playing song when list initializes or when switching back
    LaunchedEffect(currentPlayingSongId, grouped) {
        if (currentPlayingSongId != null && grouped.isNotEmpty()) {
            // Find the song in the flattened list
            val currentSong = flattenedSongs.find { it.id.toString() == currentPlayingSongId }
            if (currentSong != null) {
                // Find which group the song belongs to
                var scrollIndex = 0
                var found = false
                for ((initial, songsInGroup) in grouped) {
                    val indexInGroup = songsInGroup.indexOf(currentSong)
                    if (indexInGroup != -1) {
                        // Add 1 for the header, then add the index within the group
                        scrollIndex += 1 + indexInGroup
                        found = true
                        break
                    }
                    // Add 1 for header + all songs in this group
                    scrollIndex += 1 + songsInGroup.size
                }
                
                if (found) {
                    // Scroll to the item, centered if possible
                    listState.animateScrollToItem(scrollIndex, scrollOffset = -200)
                }
            }
        }
    }

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
                
                itemsIndexed(
                    items = songsInGroup,
                    key = { _, item -> item.id },
                    contentType = { _, _ -> "song" }
                ) { index, song ->
                    val globalIndex = (groupStartingIndices[initial] ?: 0) + index + 1
                    MusicListItem(
                        title = song.title,
                        subtitle = "${song.artist} - ${song.album}",
                        isCurrent = song.id.toString() == currentPlayingSongId,
                        index = globalIndex,
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
