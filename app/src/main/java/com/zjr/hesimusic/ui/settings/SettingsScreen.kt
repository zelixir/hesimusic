package com.zjr.hesimusic.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.zjr.hesimusic.data.preferences.AppThemeMode
import com.zjr.hesimusic.data.preferences.AppThemePalette
import com.zjr.hesimusic.R
import com.zjr.hesimusic.ui.library.LibraryViewModel
import com.zjr.hesimusic.ui.settings.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBackClick: () -> Unit,
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    libraryViewModel: LibraryViewModel = hiltViewModel()
) {
    val minAlbumTrackCount by settingsViewModel.minAlbumTrackCount.collectAsState()
    val minArtistTrackCount by settingsViewModel.minArtistTrackCount.collectAsState()
    val showMediaNotification by settingsViewModel.showMediaNotification.collectAsState()
    val appThemeMode by settingsViewModel.appThemeMode.collectAsState()
    val appThemePalette by settingsViewModel.appThemePalette.collectAsState()
    val hiddenSongs by libraryViewModel.hiddenSongs.collectAsState()
    var showHiddenManager by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { innerPadding ->
        if (!showHiddenManager) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp)
            ) {
                Text("专辑列表最少曲目数：$minAlbumTrackCount")
                Slider(
                    value = minAlbumTrackCount.toFloat(),
                    onValueChange = { settingsViewModel.updateMinAlbumTrackCount(it.toInt()) },
                    valueRange = 0f..50f,
                    steps = 49
                )
                Text("歌手列表最少曲目数：$minArtistTrackCount")
                Slider(
                    value = minArtistTrackCount.toFloat(),
                    onValueChange = { settingsViewModel.updateMinArtistTrackCount(it.toInt()) },
                    valueRange = 0f..50f,
                    steps = 49
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("显示媒体通知")
                    Switch(
                        checked = showMediaNotification,
                        onCheckedChange = { settingsViewModel.updateShowMediaNotification(it) }
                    )
                }

                Text(
                    text = stringResource(R.string.settings_theme_title),
                    modifier = Modifier.padding(top = 16.dp)
                )
                val themeOptionsDescription = stringResource(R.string.settings_theme_options_description)
                Column(
                    modifier = Modifier
                        .semantics { contentDescription = themeOptionsDescription }
                        .selectableGroup()
                ) {
                    val selectedText = stringResource(R.string.settings_option_selected)
                    val notSelectedText = stringResource(R.string.settings_option_not_selected)
                    AppThemeMode.entries.forEach { mode ->
                        val optionText = when (mode) {
                            AppThemeMode.SYSTEM -> stringResource(R.string.settings_theme_system)
                            AppThemeMode.LIGHT -> stringResource(R.string.settings_theme_light)
                            AppThemeMode.DARK -> stringResource(R.string.settings_theme_dark)
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = (mode == appThemeMode),
                                    onClick = { settingsViewModel.updateAppThemeMode(mode) },
                                    role = Role.RadioButton
                                )
                                .semantics {
                                    stateDescription = if (mode == appThemeMode) selectedText else notSelectedText
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = (mode == appThemeMode),
                                onClick = null
                            )
                            Text(
                                text = optionText
                            )
                        }
                    }
                }

                Text(
                    text = stringResource(R.string.settings_theme_palette_title),
                    modifier = Modifier.padding(top = 16.dp)
                )
                val paletteOptionsDescription = stringResource(R.string.settings_theme_palette_options_description)
                Column(
                    modifier = Modifier
                        .semantics { contentDescription = paletteOptionsDescription }
                        .selectableGroup()
                ) {
                    val selectedText = stringResource(R.string.settings_option_selected)
                    val notSelectedText = stringResource(R.string.settings_option_not_selected)
                    AppThemePalette.entries.forEach { palette ->
                        val optionText = when (palette) {
                            AppThemePalette.BLUE -> stringResource(R.string.settings_theme_palette_blue)
                            AppThemePalette.GREEN -> stringResource(R.string.settings_theme_palette_green)
                            AppThemePalette.PURPLE -> stringResource(R.string.settings_theme_palette_purple)
                            AppThemePalette.ORANGE -> stringResource(R.string.settings_theme_palette_orange)
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = (palette == appThemePalette),
                                    onClick = { settingsViewModel.updateAppThemePalette(palette) },
                                    role = Role.RadioButton
                                )
                                .semantics {
                                    stateDescription = if (palette == appThemePalette) selectedText else notSelectedText
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = (palette == appThemePalette),
                                onClick = null
                            )
                            Text(text = optionText)
                        }
                    }
                }

                Text(
                    text = stringResource(R.string.settings_manage_hidden_songs),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showHiddenManager = true }
                        .padding(top = 16.dp)
                )
            }
        } else {
            Column(modifier = Modifier.padding(innerPadding)) {
                TextButton(onClick = { showHiddenManager = false }) {
                    Text("返回")
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(hiddenSongs, key = { "${it.filePath}-${it.startPosition}" }) { hiddenSong ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                        ) {
                            Text(text = hiddenSong.filePath)
                            TextButton(onClick = { libraryViewModel.unhideSong(hiddenSong) }) {
                                Text("取消隐藏")
                            }
                        }
                    }
                }
            }
        }
    }
}
