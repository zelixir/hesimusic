package com.zjr.hesimusic.ui.library

import android.util.Log
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.zjr.hesimusic.data.model.Song
import com.zjr.hesimusic.ui.common.FastScrollbar
import com.zjr.hesimusic.ui.common.MusicListItem
import com.zjr.hesimusic.utils.AlphabetIndexer
import com.zjr.hesimusic.utils.AppLogger
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.launch

/**
 * Scroll offset in pixels to center the currently playing item on screen.
 * Negative value scrolls upward, positioning the item away from the top edge.
 */
private const val SCROLL_OFFSET_TO_CENTER_ITEM = -200
private const val TAG = "SongList"
private const val BATCH_SELECTED_PREFIX = "✓ "
private const val QUEUE_DISPLAY_MAX_CHARS_PER_LINE = 4

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongList(
    songs: List<Song>,
    currentPlayingSongId: String? = null,
    onSongClick: (List<Song>, Int) -> Unit,
    onSongLongClick: ((Song) -> Unit)? = null,
    preferTrackNumberOrdering: Boolean = false,
    isBatchMode: Boolean = false,
    selectedSongIds: Set<Long> = emptySet(),
    onBatchSongToggle: ((Song) -> Unit)? = null,
    queueDisplayBySongId: Map<Long, String> = emptyMap(),
    modifier: Modifier = Modifier,
    appLogger: AppLogger? = null
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val useTrackNumberOrdering = remember(songs, preferTrackNumberOrdering) {
        shouldUseTrackNumberOrdering(preferTrackNumberOrdering, songs)
    }

    // Log list size for performance tracking
    LaunchedEffect(songs.size, useTrackNumberOrdering) {
        Log.d(TAG, "SongList rendering with ${songs.size} songs")
        appLogger?.info(TAG, "SongList rendering with ${songs.size} songs")
    }

    val trackOrderedSongs = remember(songs, useTrackNumberOrdering) {
        if (useTrackNumberOrdering) orderSongsByTrackNumber(songs) else emptyList()
    }

    // Synchronous grouping to avoid initial empty state
    val grouped by remember(songs, useTrackNumberOrdering) {
        derivedStateOf {
            if (useTrackNumberOrdering) {
                return@derivedStateOf sortedMapOf<Char, List<Song>>()
            }
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
    
    val flattenedSongs = remember(grouped, trackOrderedSongs, useTrackNumberOrdering) {
        val flattenStartTime = System.currentTimeMillis()
        val result = if (useTrackNumberOrdering) trackOrderedSongs else grouped.values.flatten()
        val flattenDuration = System.currentTimeMillis() - flattenStartTime
        Log.d(TAG, "Song flattening completed in ${flattenDuration}ms")
        appLogger?.timing(TAG, "Song flattening", flattenDuration)
        result
    }
    var hasAutoScrolledToCurrentSong = remember { mutableStateOf(false) }

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

    // Auto-scroll to currently playing song once when list is ready.
    // Also listens to currentPlayingSongId so late restore after startup can still scroll.
    LaunchedEffect(grouped, flattenedSongs, currentPlayingSongId, useTrackNumberOrdering) {
        if (!hasAutoScrolledToCurrentSong.value && currentPlayingSongId != null && flattenedSongs.isNotEmpty()) {
            // Find the song in the flattened list
            // Note: MediaItem.mediaId is set as song.id.toString() in SongMapper.toMediaItem()
            val currentSong = flattenedSongs.find { it.id.toString() == currentPlayingSongId }
            if (currentSong != null) {
                val scrollIndex = if (useTrackNumberOrdering) {
                    flattenedSongs.indexOf(currentSong).takeIf { it != -1 }
                } else {
                    // Calculate scroll index by finding which group contains the song
                    calculateScrollIndex(grouped, currentSong)
                }
                
                if (scrollIndex != null) {
                    // Scroll to the item instantly (no animation), centered if possible
                    listState.scrollToItem(scrollIndex, scrollOffset = SCROLL_OFFSET_TO_CENTER_ITEM)
                    hasAutoScrolledToCurrentSong.value = true
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
            if (useTrackNumberOrdering) {
                itemsIndexed(
                    items = flattenedSongs,
                    key = { _, item -> item.id },
                    contentType = { _, _ -> "song" }
                ) { index, song ->
                    val isSelectedInBatch = song.id in selectedSongIds
                    val queueDisplay = queueDisplayBySongId[song.id]
                    MusicListItem(
                        title = if (isBatchMode && isSelectedInBatch) "$BATCH_SELECTED_PREFIX${song.title}" else song.title,
                        subtitle = "${song.artist} - ${song.album}",
                        isCurrent = if (isBatchMode) isSelectedInBatch else song.id.toString() == currentPlayingSongId,
                        index = if (isBatchMode) null else index + 1,
                        indexText = if (isBatchMode) null else queueDisplay,
                        indexTextColor = if (queueDisplay != null) Color.Red else null,
                        onClick = {
                            if (isBatchMode) {
                                onBatchSongToggle?.invoke(song)
                            } else {
                                onSongClick(flattenedSongs, index)
                            }
                        },
                        onLongClick = if (!isBatchMode && onSongLongClick != null) ({ onSongLongClick(song) }) else null
                    )
                }
            } else {
                grouped.forEach { (initial, songsInGroup) ->
                    stickyHeader {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.82f)
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
                        val isSelectedInBatch = song.id in selectedSongIds
                        val queueDisplay = queueDisplayBySongId[song.id]
                        MusicListItem(
                            title = if (isBatchMode && isSelectedInBatch) "$BATCH_SELECTED_PREFIX${song.title}" else song.title,
                            subtitle = "${song.artist} - ${song.album}",
                            isCurrent = if (isBatchMode) isSelectedInBatch else song.id.toString() == currentPlayingSongId,
                            index = if (isBatchMode) null else globalIndex,
                            indexText = if (isBatchMode) null else queueDisplay,
                            indexTextColor = if (queueDisplay != null) Color.Red else null,
                            onClick = {
                                if (isBatchMode) {
                                    onBatchSongToggle?.invoke(song)
                                } else {
                                    val songIndex = flattenedSongs.indexOf(song)
                                    if (songIndex != -1) {
                                        onSongClick(flattenedSongs, songIndex)
                                    }
                                }
                            },
                            onLongClick = if (!isBatchMode && onSongLongClick != null) ({ onSongLongClick(song) }) else null
                        )
                    }
                }
            }
        }

        if (!useTrackNumberOrdering && sections.isNotEmpty()) {
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

internal fun shouldUseTrackNumberOrdering(
    preferTrackNumberOrdering: Boolean,
    songs: List<Song>
): Boolean = preferTrackNumberOrdering && songs.isNotEmpty() && songs.all { it.trackNumber > 0 }

internal fun orderSongsByTrackNumber(songs: List<Song>): List<Song> =
    songs.sortedBy { it.trackNumber }

internal fun buildQueueDisplayBySongId(queueSongIds: List<Long>): Map<Long, String> {
    if (queueSongIds.isEmpty()) return emptyMap()
    val positionsBySongId = linkedMapOf<Long, MutableList<Int>>()
    queueSongIds.forEachIndexed { index, songId ->
        positionsBySongId.getOrPut(songId) { mutableListOf() }.add(index + 1)
    }
    return positionsBySongId.mapValues { (_, positions) ->
        formatQueuePositionsForDisplay(positions)
    }
}

internal fun formatQueuePositionsForDisplay(positions: List<Int>): String {
    if (positions.isEmpty()) return ""
    val raw = positions.joinToString(",")
    if (raw.length <= QUEUE_DISPLAY_MAX_CHARS_PER_LINE) return raw
    val firstLine = raw.take(QUEUE_DISPLAY_MAX_CHARS_PER_LINE)
    val secondLine = raw.drop(QUEUE_DISPLAY_MAX_CHARS_PER_LINE).take(QUEUE_DISPLAY_MAX_CHARS_PER_LINE)
    return if (secondLine.isEmpty()) firstLine else "$firstLine\n$secondLine"
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

internal fun shouldAutoScrollToCurrentSong(
    hasAutoScrolledToCurrentSong: Boolean,
    currentPlayingSongId: String?,
    grouped: Map<Char, List<Song>>
): Boolean {
    return !hasAutoScrolledToCurrentSong && currentPlayingSongId != null && grouped.isNotEmpty()
}
