package com.zjr.hesimusic.ui.library

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.zjr.hesimusic.data.model.FileSystemItem
import com.zjr.hesimusic.data.model.Song
import com.zjr.hesimusic.data.preferences.SongSortMode
import com.zjr.hesimusic.data.repository.sortFolderItemsByTrackNumber
import com.zjr.hesimusic.ui.common.FastScrollbar
import com.zjr.hesimusic.ui.common.MusicListItem
import com.zjr.hesimusic.utils.AlphabetIndexer
import com.zjr.hesimusic.utils.AppLogger
import java.io.File
import kotlinx.coroutines.launch

private const val TAG = "FolderList"

@Composable
fun FolderList(
    viewModel: LibraryViewModel,
    modifier: Modifier = Modifier,
    initialPath: String = "/storage/emulated/0",
    startPath: String? = null,
    currentPlayingSongId: String? = null,
    sortMode: SongSortMode = SongSortMode.TITLE_INITIAL,
    onSongClick: (List<Song>, Int, String) -> Unit,
    /**
     * 视图 epoch(issue 00002 回归):MainScreen 每次切换 tab 就 +1,详情页 / 播放页 / 分析页返回不变。
     * 与 [SongList] 同一套语义:epoch 变化 → 重新定位当前播放歌;epoch 不变 → 保留滚动位置。
     * 默认 0 保证未接入的调用点行为不变。
     */
    viewEpoch: Int = 0,
    appLogger: AppLogger? = null
) {
    var currentPath by rememberSaveable(initialPath, startPath) { mutableStateOf(startPath ?: initialPath) }
    val fetchedItems by viewModel.getFolderContents(currentPath).collectAsState(initial = emptyList())
    // 音轨号模式下歌曲按 trackNumber 排（文件夹条目仍在歌曲前按原逻辑）
    val items = remember(fetchedItems, sortMode) {
        if (sortMode == SongSortMode.TRACK_NUMBER) sortFolderItemsByTrackNumber(fetchedItems) else fetchedItems
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Log list size for performance tracking
    LaunchedEffect(items.size, currentPath) {
        val folderCount = items.count { it is FileSystemItem.Folder }
        val fileCount = items.count { it is FileSystemItem.MusicFile }
        Log.d(TAG, "FolderList rendering path: $currentPath with $folderCount folders and $fileCount files")
        appLogger?.info(TAG, "FolderList rendering path: $currentPath with $folderCount folders and $fileCount files")
    }

    // 「本次视图 / 当前目录已自动定位过当前播放歌」的标志(issue 00002 同类;lead 授权一并修),
    // 记录定位成功时的 viewEpoch。
    // 必须可保存:从播放页 / 无缝循环分析页返回时本页会重新组合,普通 remember 会复位成未定位 →
    // 又按当前播放歌 scrollToItem,把用户原本的滚动位置覆盖掉。
    // key 用 (currentPath, viewEpoch):
    // - currentPath:文件夹是「同一页换 path」而不是新路由,进入子目录 / 返回上一级属于换了一个列表,
    //   应重新定位一次;同一目录内返回(重组)则不再重复定位;
    // - viewEpoch:MainScreen 切换 tab 后回到文件夹 → 标志失效,重新定位当前播放歌(00002 回归点)。
    var autoScrolledViewEpoch by rememberSaveable(currentPath, viewEpoch) { mutableStateOf(AUTO_SCROLL_NEVER) }

    // Handle back press to go up directory
    // Only enable if we are not at the initial path
    BackHandler(enabled = currentPath != initialPath) {
        val parent = File(currentPath).parent
        if (parent != null) {
            currentPath = parent
        }
    }
    
    LaunchedEffect(items, currentPlayingSongId, currentPath, initialPath, viewEpoch) {
        if (!shouldAutoScrollToCurrentSong(
                autoScrolledViewEpoch = autoScrolledViewEpoch,
                viewEpoch = viewEpoch,
                currentPlayingSongId = currentPlayingSongId,
                hasItems = items.isNotEmpty()
            )
        ) {
            return@LaunchedEffect
        }
        val itemIndex = items.indexOfFirst { item ->
            item is FileSystemItem.MusicFile && item.song.id.toString() == currentPlayingSongId
        }
        if (itemIndex >= 0) {
            val scrollIndex = if (currentPath != initialPath) itemIndex + 1 else itemIndex
            listState.scrollToItem(scrollIndex)
            autoScrolledViewEpoch = viewEpoch
            Log.d(TAG, "Auto-scrolled to current song at index $scrollIndex (path=$currentPath, viewEpoch=$viewEpoch)")
        }
    }

    val sectionIndices = remember(items, currentPath, initialPath) {
        val parentOffset = if (currentPath != initialPath) 1 else 0
        buildMap {
            items.forEachIndexed { index, item ->
                val title = when (item) {
                    is FileSystemItem.Folder -> item.name
                    is FileSystemItem.MusicFile -> item.song.title
                }
                putIfAbsent(AlphabetIndexer.getInitial(title), index + parentOffset)
            }
        }
    }
    val sections = remember(sectionIndices) { sectionIndices.keys.toList() }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
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

        if (sortMode == SongSortMode.TITLE_INITIAL && sections.isNotEmpty()) {
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
