package com.zjr.hesimusic.ui.library

import android.util.Log
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
import com.zjr.hesimusic.utils.AppLogger
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import kotlinx.coroutines.launch

/**
 * Scroll offset in pixels to center the currently playing item on screen.
 * Negative value scrolls upward, positioning the item away from the top edge.
 */
private const val SCROLL_OFFSET_TO_CENTER_ITEM = -200
private const val TAG = "SongList"

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongList(
    songs: List<Song>,
    currentPlayingSongId: String? = null,
    onSongClick: (List<Song>, Int) -> Unit,
    modifier: Modifier = Modifier,
    appLogger: AppLogger? = null
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Log list size for performance tracking
    LaunchedEffect(songs.size) {
        Log.d(TAG, "SongList rendering with ${songs.size} songs")
        appLogger?.info(TAG, "SongList rendering with ${songs.size} songs")
    }

    // Since songs are already sorted by titleInitial from database, we can group directly
    // Using remember + derivedStateOf for synchronous computation during composition
    val grouped by remember(songs) {
        derivedStateOf {
            val groupingStartTime = System.currentTimeMillis()
            // Group songs by titleInitial, then sort groups alphabetically
            val result = songs.groupBy { song ->
                // Use pre-computed titleInitial field
                val initial = song.titleInitial.firstOrNull() ?: '#'
                if (initial.isLetter() || initial == '#') initial else '#'
            }.toSortedMap()
            val groupingDuration = System.currentTimeMillis() - groupingStartTime
            Log.d(TAG, "Song grouping completed in ${groupingDuration}ms, ${result.size} groups")
            appLogger?.timing(TAG, "Song grouping (${result.size} groups)", groupingDuration)
            result
        }
    }
    
    val flattenedSongs = remember(grouped) {
        val flattenStartTime = System.currentTimeMillis()
        val result = grouped.values.flatten()
        val flattenDuration = System.currentTimeMillis() - flattenStartTime
        Log.d(TAG, "Song flattening completed in ${flattenDuration}ms")
        appLogger?.timing(TAG, "Song flattening", flattenDuration)
        result
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

    // Auto-scroll to currently playing song when list initializes or when search closes
    // Only triggers when grouped changes (not when currentPlayingSongId changes)
    // to avoid scrolling when user manually switches songs
    LaunchedEffect(grouped) {
        if (currentPlayingSongId != null && grouped.isNotEmpty()) {
            // Find the song in the flattened list
            // Note: MediaItem.mediaId is set as song.id.toString() in SongMapper.toMediaItem()
            val currentSong = flattenedSongs.find { it.id.toString() == currentPlayingSongId }
            if (currentSong != null) {
                // Calculate scroll index by finding which group contains the song
                val scrollIndex = calculateScrollIndex(grouped, currentSong)
                
                if (scrollIndex != null) {
                    // Scroll to the item instantly (no animation), centered if possible
                    listState.scrollToItem(scrollIndex, scrollOffset = SCROLL_OFFSET_TO_CENTER_ITEM)
                    Log.d(TAG, "Auto-scrolled to current song at index $scrollIndex")
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

/**
 * Calculates the scroll index for a song in a grouped LazyColumn.
 * Accounts for sticky headers (1 per group) and items within each group.
 * Returns null if the song is not found in any group.
 */
private fun calculateScrollIndex(grouped: Map<Char, List<Song>>, targetSong: Song): Int? {
    var scrollIndex = 0
    for ((_, songsInGroup) in grouped) {
        val indexInGroup = songsInGroup.indexOf(targetSong)
        if (indexInGroup != -1) {
            // Found the song: add 1 for the header, then add the index within the group
            return scrollIndex + 1 + indexInGroup
        }
        // Not in this group: add 1 for header + all songs in this group
        scrollIndex += 1 + songsInGroup.size
    }
    return null
}
