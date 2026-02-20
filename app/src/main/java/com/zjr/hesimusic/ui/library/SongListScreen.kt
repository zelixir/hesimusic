package com.zjr.hesimusic.ui.library

import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.Player
import com.zjr.hesimusic.data.model.Song
import com.zjr.hesimusic.data.preferences.PlaylistContext
import com.zjr.hesimusic.data.preferences.PlaylistType
import com.zjr.hesimusic.ui.common.MusicViewModel
import com.zjr.hesimusic.ui.main.BottomPlayerBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongListScreen(
    type: String,
    value: String,
    viewModel: LibraryViewModel = hiltViewModel(),
    musicViewModel: MusicViewModel = hiltViewModel(),
    onBack: () -> Unit,
    onSongClick: (Song) -> Unit,
    onScanClick: () -> Unit,
    onBackupRestoreClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onEqualizerClick: () -> Unit,
    onAboutClick: () -> Unit,
    onSleepTimerClick: () -> Unit,
    onLogsClick: () -> Unit
) {
    val songsFlow = when (type) {
        "artist" -> viewModel.getSongsByArtist(value)
        "album" -> viewModel.getSongsByAlbum(value)
        else -> viewModel.songs // Fallback
    }
    
    val songs by songsFlow.collectAsState(initial = emptyList())
    val context = LocalContext.current
    val musicUiState by musicViewModel.uiState.collectAsState()
    val sleepTimerState by musicViewModel.sleepTimerState.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    var selectedSongForActions by remember { mutableStateOf<Song?>(null) }
    var isBatchMode by remember { mutableStateOf(false) }
    var batchSelectedSongIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var showBatchAddDialog by remember { mutableStateOf(false) }

    val handleSongClick = remember(musicViewModel, type, value) {
        { list: List<Song>, index: Int ->
            val context = when (type) {
                "artist" -> PlaylistContext(PlaylistType.ARTIST, value)
                "album" -> PlaylistContext(PlaylistType.ALBUM, value)
                else -> PlaylistContext.GLOBAL
            }
            Log.d("SongListScreen", "Playing song at index $index with context: $context")
            musicViewModel.playList(list, index, context)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = value) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        bottomBar = {
            if (isBatchMode) {
                BatchActionBar(
                    showRemoveFromPlaylist = false,
                    favoriteActionText = "加入收藏",
                    allSelected = songs.isNotEmpty() && batchSelectedSongIds.size == songs.size,
                    onSelectAllChange = { checked ->
                        batchSelectedSongIds = if (checked) songs.map { it.id }.toSet() else emptySet()
                    },
                    onAddToPlaylist = { showBatchAddDialog = true },
                    onFavoriteAction = {
                        val selectedSongs = songs.filter { it.id in batchSelectedSongIds }
                        if (selectedSongs.isEmpty()) {
                            Toast.makeText(context, "请先选择歌曲", Toast.LENGTH_SHORT).show()
                        } else {
                            viewModel.addSongsToFavorites(selectedSongs)
                            Toast.makeText(context, "已加入收藏 ${selectedSongs.size} 首歌曲", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onExit = {
                        isBatchMode = false
                        batchSelectedSongIds = emptySet()
                    }
                )
            } else {
                BottomPlayerBar(
                    currentMediaItem = musicUiState.currentMediaItem,
                    isPlaying = musicUiState.isPlaying,
                    currentPosition = musicUiState.currentPosition,
                    duration = musicUiState.duration,
                    repeatMode = musicUiState.repeatMode,
                    shuffleModeEnabled = musicUiState.shuffleModeEnabled,
                    onPlayPauseClick = {
                        if (musicUiState.isPlaying) {
                            musicViewModel.pause()
                        } else {
                            musicViewModel.resume()
                        }
                    },
                    onPreviousClick = { musicViewModel.skipToPrevious() },
                    onNextClick = { musicViewModel.skipToNext() },
                    onPlayModeClick = {
                        val (newShuffle, newRepeat) = when {
                            musicUiState.shuffleModeEnabled -> false to Player.REPEAT_MODE_ONE // Random -> Single Loop
                            musicUiState.repeatMode == Player.REPEAT_MODE_ONE -> false to Player.REPEAT_MODE_ALL // Single Loop -> Sequential
                            else -> true to Player.REPEAT_MODE_ALL // Sequential -> Random
                        }
                        musicViewModel.setShuffleModeEnabled(newShuffle)
                        musicViewModel.setRepeatMode(newRepeat)
                    },
                    onClick = { /* TODO: Navigate to player if needed, or just expand */ },
                    onScanClick = onScanClick,
                    onBackupRestoreClick = onBackupRestoreClick,
                    onSettingsClick = onSettingsClick,
                    onEqualizerClick = onEqualizerClick,
                    onAboutClick = onAboutClick,
                    onSleepTimerClick = onSleepTimerClick,
                    onLogsClick = onLogsClick,
                    sleepTimerRemaining = sleepTimerState
                )
            }
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            SongList(
                songs = songs,
                currentPlayingSongId = musicUiState.currentMediaItem?.mediaId,
                onSongClick = handleSongClick,
                onSongLongClick = { selectedSongForActions = it },
                isBatchMode = isBatchMode,
                selectedSongIds = batchSelectedSongIds,
                onBatchSongToggle = { song ->
                    batchSelectedSongIds = if (song.id in batchSelectedSongIds) {
                        batchSelectedSongIds - song.id
                    } else {
                        batchSelectedSongIds + song.id
                    }
                }
            )
            SongActionHost(
                selectedSong = selectedSongForActions,
                playlists = playlists,
                onDismiss = { selectedSongForActions = null },
                onAddToPlaylist = { song, playlistId -> viewModel.addSongToPlaylist(song, playlistId) },
                onCreatePlaylist = { name, onCreated -> viewModel.createPlaylist(name, onCreated) },
                onBatchManage = { song ->
                    isBatchMode = true
                    batchSelectedSongIds = setOf(song.id)
                },
                onHideSong = { song -> viewModel.hideSong(song) },
                onDeleteSong = { song, onResult -> viewModel.deleteSongFile(song, onResult) },
                onLoadMetadata = { song, onLoaded -> viewModel.loadSongMetadata(song, onLoaded) }
            )
            if (showBatchAddDialog) {
                AddToPlaylistDialog(
                    playlists = playlists,
                    resetKey = batchSelectedSongIds,
                    onDismiss = { showBatchAddDialog = false },
                    onAdd = { playlistId ->
                        val selectedSongs = songs.filter { it.id in batchSelectedSongIds }
                        if (selectedSongs.isEmpty()) {
                            Toast.makeText(context, "请先选择歌曲", Toast.LENGTH_SHORT).show()
                        } else {
                            viewModel.addSongsToPlaylist(selectedSongs, playlistId)
                            Toast.makeText(context, "已加入歌单 ${selectedSongs.size} 首歌曲", Toast.LENGTH_SHORT).show()
                        }
                        showBatchAddDialog = false
                    },
                    onCreate = { name ->
                        viewModel.createPlaylist(name) { playlistId ->
                            if (playlistId != null) {
                                val selectedSongs = songs.filter { it.id in batchSelectedSongIds }
                                viewModel.addSongsToPlaylist(selectedSongs, playlistId)
                                Toast.makeText(context, "歌单已创建并加入 ${selectedSongs.size} 首歌曲", Toast.LENGTH_SHORT).show()
                                showBatchAddDialog = false
                            }
                        }
                    }
                )
            }
        }
    }
}
