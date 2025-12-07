package com.zjr.hesimusic.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.zjr.hesimusic.data.model.FileSystemItem
import com.zjr.hesimusic.data.model.Song
import com.zjr.hesimusic.ui.common.MusicListItem
import java.io.File

@Composable
fun FolderList(
    viewModel: LibraryViewModel,
    modifier: Modifier = Modifier,
    initialPath: String = "/storage/emulated/0",
    currentPlayingSongId: String? = null,
    onSongClick: (List<Song>, Int, String) -> Unit
) {
    var currentPath by remember { mutableStateOf(initialPath) }
    val items by viewModel.getFolderContents(currentPath).collectAsState(initial = emptyList())

    // Handle back press to go up directory
    // Only enable if we are not at the initial path
    BackHandler(enabled = currentPath != initialPath) {
        val parent = File(currentPath).parent
        if (parent != null) {
            currentPath = parent
        }
    }

    LazyColumn(modifier = modifier) {
        if (currentPath != initialPath) {
             item {
                 MusicListItem(
                     title = "..",
                     subtitle = "上级目录",
                     icon = Icons.Default.Folder,
                     onClick = {
                         val parent = File(currentPath).parent
                         if (parent != null) {
                             currentPath = parent
                         }
                     }
                 )
             }
        }

        items(
            items = items,
            key = { item ->
                when (item) {
                    is FileSystemItem.Folder -> item.path
                    is FileSystemItem.MusicFile -> item.song.id
                }
            },
            contentType = { item ->
                when (item) {
                    is FileSystemItem.Folder -> "folder"
                    is FileSystemItem.MusicFile -> "file"
                }
            }
        ) { item ->
            when (item) {
                is FileSystemItem.Folder -> {
                    MusicListItem(
                        title = item.name,
                        subtitle = "${item.songCount} 首歌曲",
                        icon = Icons.Default.Folder,
                        onClick = { currentPath = item.path }
                    )
                }
                is FileSystemItem.MusicFile -> {
                    MusicListItem(
                        title = item.song.title,
                        subtitle = "${item.song.artist} - ${item.song.album}",
                        icon = Icons.Default.MusicNote,
                        isCurrent = item.song.id.toString() == currentPlayingSongId,
                        onClick = { 
                            val songsInFolder = items.filterIsInstance<FileSystemItem.MusicFile>().map { it.song }
                            val index = songsInFolder.indexOf(item.song)
                            if (index != -1) {
                                onSongClick(songsInFolder, index, currentPath)
                            }
                        }
                    )
                }
            }
        }
    }
}
