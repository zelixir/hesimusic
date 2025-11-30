package com.zjr.hesimusic.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zjr.hesimusic.data.model.Song
import com.zjr.hesimusic.ui.common.MusicListItem

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongList(
    songs: List<Song>,
    currentPlayingSongId: String? = null,
    onSongClick: (List<Song>, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val grouped = remember(songs) {
        songs.groupBy { it.title.firstOrNull()?.uppercaseChar() ?: '#' }
            .toSortedMap()
    }
    
    val flattenedSongs = remember(grouped) {
        grouped.values.flatten()
    }

    LazyColumn(modifier = modifier) {
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
}
