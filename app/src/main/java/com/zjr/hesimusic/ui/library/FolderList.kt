package com.zjr.hesimusic.ui.library

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import java.nio.file.Path
import java.nio.file.Paths

private const val TAG = "FolderList"

/**
 * Validates that a saved path is safely contained within the initial path.
 * Uses NIO.2 Path API for robust path traversal protection.
 * 
 * @param savedPath The path to validate
 * @param initialPath The base/root path that savedPath must be within
 * @return The validated absolute path, or null if validation fails
 */
private fun validateSavedPath(savedPath: String, initialPath: String): String? {
    return try {
        val initialFile = File(initialPath)
        val savedFile = File(savedPath)
        
        // Check if paths exist before using toRealPath
        if (!initialFile.exists()) {
            Log.w(TAG, "validateSavedPath: initial path '$initialPath' does not exist")
            return null
        }
        
        val initialNioPath: Path = Paths.get(initialPath).toRealPath()
        val savedNioPath: Path = Paths.get(savedPath).normalize()
        
        // Resolve the saved path against initial path and normalize it
        val resolvedPath: Path = if (savedNioPath.isAbsolute) {
            // Check if saved path exists before using toRealPath
            if (!savedFile.exists()) {
                Log.w(TAG, "validateSavedPath: saved path '$savedPath' does not exist")
                return null
            }
            savedNioPath.toRealPath()
        } else {
            val resolved = initialNioPath.resolve(savedNioPath).normalize()
            if (!resolved.toFile().exists()) {
                Log.w(TAG, "validateSavedPath: resolved path '$resolved' does not exist")
                return null
            }
            resolved.toRealPath()
        }
        
        // Verify the resolved path starts with the initial path
        if (resolvedPath.startsWith(initialNioPath)) {
            Log.d(TAG, "validateSavedPath: validated path: $resolvedPath")
            resolvedPath.toString()
        } else {
            Log.w(TAG, "validateSavedPath: path '$savedPath' is outside initial path '$initialPath'")
            null
        }
    } catch (e: Exception) {
        Log.e(TAG, "validateSavedPath: error validating path '$savedPath'", e)
        null
    }
}

@Composable
fun FolderList(
    viewModel: LibraryViewModel,
    modifier: Modifier = Modifier,
    initialPath: String = "/storage/emulated/0",
    currentPlayingSongId: String? = null,
    savedFolderPath: String? = null,  // Path to restore from saved state
    onSongClick: (List<Song>, Int, String) -> Unit  // Added folderPath parameter
) {
    // Start from saved path if available, otherwise use initial path
    // Use NIO.2 Path API for robust path traversal protection
    val startPath = remember(savedFolderPath, initialPath) {
        if (savedFolderPath != null) {
            validateSavedPath(savedFolderPath, initialPath) ?: run {
                Log.d(TAG, "FolderList: falling back to initial path: $initialPath")
                initialPath
            }
        } else {
            Log.d(TAG, "FolderList: starting from initial path: $initialPath")
            initialPath
        }
    }
    
    var currentPath by remember { mutableStateOf(startPath) }
    val items by viewModel.getFolderContents(currentPath).collectAsState(initial = emptyList())

    // Handle back press to go up directory
    // Only enable if we are not at the initial path
    BackHandler(enabled = currentPath != initialPath) {
        val parent = File(currentPath).parent
        if (parent != null) {
            Log.d(TAG, "BackHandler: navigating from $currentPath to $parent")
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
                             Log.d(TAG, "Parent click: navigating from $currentPath to $parent")
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
                        onClick = { 
                            Log.d(TAG, "Folder click: navigating from $currentPath to ${item.path}")
                            currentPath = item.path 
                        }
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
                                Log.d(TAG, "Song click: playing song '${item.song.title}' at index $index in folder $currentPath")
                                onSongClick(songsInFolder, index, currentPath)
                            }
                        }
                    )
                }
            }
        }
    }
}
