package com.zjr.hesimusic.ui.settings

import android.widget.Toast
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.zjr.hesimusic.data.preferences.AppThemePalette
import com.zjr.hesimusic.data.preferences.AppThemeMode
import com.zjr.hesimusic.R
import com.zjr.hesimusic.ui.library.LibraryViewModel
import com.zjr.hesimusic.ui.theme.paletteSeedColor
import java.io.File
import java.io.FileOutputStream

private const val TAG = "SettingsScreen"
private const val SELECTED_PALETTE_SCALE = 1.08f
private const val DEFAULT_IMAGE_EXTENSION = "png"
private val OPTION_SPACING = 10.8.dp
private val PALETTE_CIRCLE_SIZE = 30.6.dp

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
    val customThemeColor by settingsViewModel.customThemeColor.collectAsState()
    val startupImageUri by settingsViewModel.startupImageUri.collectAsState()
    val listBackgroundImageUri by settingsViewModel.listBackgroundImageUri.collectAsState()
    val hiddenSongs by libraryViewModel.hiddenSongs.collectAsState()
    val context = LocalContext.current
    var showHiddenManager by remember { mutableStateOf(false) }
    var showCustomColorDialog by remember { mutableStateOf(false) }

    fun handleImageUriSelection(
        uri: android.net.Uri?,
        fileName: String,
        update: (String) -> Unit
    ) {
        if (uri == null) return
        val savedPath = saveImageToAppDir(context, uri, fileName)
        if (savedPath == null) {
            Toast.makeText(context, context.getString(R.string.settings_image_save_failed), Toast.LENGTH_SHORT).show()
            return
        }
        update(savedPath)
    }

    val startupImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        handleImageUriSelection(uri, "startup_image", settingsViewModel::updateStartupImageUri)
    }

    val listBackgroundLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        handleImageUriSelection(uri, "list_background_image", settingsViewModel::updateListBackgroundImageUri)
    }

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
                Text("专辑列表最少曲目数：$minAlbumTrackCount", fontWeight = FontWeight.Bold)
                Slider(
                    value = minAlbumTrackCount.toFloat(),
                    onValueChange = { settingsViewModel.updateMinAlbumTrackCount(it.toInt()) },
                    valueRange = 0f..50f,
                    steps = 49
                )
                Text("歌手列表最少曲目数：$minArtistTrackCount", fontWeight = FontWeight.Bold)
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
                    Text("显示媒体通知", fontWeight = FontWeight.Bold)
                    Switch(
                        checked = showMediaNotification,
                        onCheckedChange = { settingsViewModel.updateShowMediaNotification(it) }
                    )
                }

                Text(
                    text = stringResource(R.string.settings_theme_title),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 16.dp)
                )
                val themeOptionsDescription = stringResource(R.string.settings_theme_options_description)
                Row(
                    modifier = Modifier
                        .semantics { contentDescription = themeOptionsDescription }
                        .selectableGroup()
                        .horizontalScroll(rememberScrollState())
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(OPTION_SPACING)
                ) {
                    val selectedText = stringResource(R.string.settings_option_selected)
                    val notSelectedText = stringResource(R.string.settings_option_not_selected)
                    AppThemeMode.entries.forEach { mode ->
                        val optionText = when (mode) {
                            AppThemeMode.SYSTEM -> stringResource(R.string.settings_theme_system)
                            AppThemeMode.LIGHT -> stringResource(R.string.settings_theme_light)
                            AppThemeMode.DARK -> stringResource(R.string.settings_theme_dark)
                        }
                        Column(
                            modifier = Modifier
                                .selectable(
                                    selected = (mode == appThemeMode),
                                    onClick = { settingsViewModel.updateAppThemeMode(mode) },
                                    role = Role.RadioButton
                                )
                                .semantics {
                                    stateDescription = if (mode == appThemeMode) selectedText else notSelectedText
                                },
                            horizontalAlignment = Alignment.CenterHorizontally
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
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 16.dp)
                )
                val paletteOptionsDescription = stringResource(R.string.settings_theme_palette_options_description)
                Row(
                    modifier = Modifier
                        .semantics { contentDescription = paletteOptionsDescription }
                        .selectableGroup()
                        .horizontalScroll(rememberScrollState())
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(OPTION_SPACING)
                ) {
                    AppThemePalette.entries.forEach { palette ->
                        val isSelected = palette == appThemePalette
                        val label = paletteLabel(palette)
                        val circleColor = paletteSeedColor(palette, customThemeColor)
                        val checkColor = if (circleColor.luminance() > 0.5f) Color.Black else Color.White
                        val onClick = {
                            if (palette == AppThemePalette.CUSTOM) {
                                showCustomColorDialog = true
                            } else {
                                settingsViewModel.updateAppThemePalette(palette)
                            }
                        }
                        Column(
                            modifier = Modifier
                                .selectable(
                                    selected = isSelected,
                                    onClick = onClick,
                                    role = Role.RadioButton
                                )
                                .padding(vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            val circleModifier = Modifier
                                .size(PALETTE_CIRCLE_SIZE)
                                .clip(CircleShape)
                                .then(
                                    if (palette == AppThemePalette.CUSTOM) {
                                        Modifier.background(
                                            Brush.horizontalGradient(
                                                listOf(
                                                    Color.Red,
                                                    Color.Yellow,
                                                    Color.Green,
                                                    Color.Cyan,
                                                    Color.Blue,
                                                    Color.Magenta
                                                )
                                            )
                                        )
                                    } else {
                                        Modifier.background(circleColor)
                                    }
                                )
                            Box(
                                modifier = if (isSelected) {
                                    circleModifier
                                        .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                                        .scale(SELECTED_PALETTE_SCALE)
                                } else {
                                    circleModifier.alpha(0.85f)
                                }
                            ) {
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = checkColor,
                                        modifier = Modifier.align(Alignment.Center)
                                    )
                                }
                            }
                            Text(
                                text = stringResource(label),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }

                Text(
                    text = stringResource(R.string.settings_startup_image_title),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 16.dp)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = imageDisplayName(startupImageUri, stringResource(R.string.settings_not_set)),
                        modifier = Modifier.weight(1f)
                    )
                    Button(onClick = { startupImageLauncher.launch(arrayOf("image/*")) }) {
                        Text(stringResource(R.string.settings_upload))
                    }
                }

                Text(
                    text = stringResource(R.string.settings_list_background_title),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 16.dp)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = imageDisplayName(listBackgroundImageUri, stringResource(R.string.settings_not_set)),
                        modifier = Modifier.weight(1f)
                    )
                    Button(onClick = { listBackgroundLauncher.launch(arrayOf("image/*")) }) {
                        Text(stringResource(R.string.settings_upload))
                    }
                }

                Text(
                    text = stringResource(R.string.settings_manage_hidden_songs),
                    fontWeight = FontWeight.Bold,
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

    if (showCustomColorDialog) {
        CustomColorPickerDialog(
            initialColor = customThemeColor,
            onDismiss = { showCustomColorDialog = false },
            onConfirm = { color ->
                settingsViewModel.updateCustomThemeColor(color)
                settingsViewModel.updateAppThemePalette(AppThemePalette.CUSTOM)
                showCustomColorDialog = false
            }
        )
    }
}

@Composable
private fun CustomColorPickerDialog(
    initialColor: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    val colorComponents = Color(initialColor)
    var red by remember(initialColor) { mutableStateOf(component255(colorComponents.red)) }
    var green by remember(initialColor) { mutableStateOf(component255(colorComponents.green)) }
    var blue by remember(initialColor) { mutableStateOf(component255(colorComponents.blue)) }
    var hexColor by remember(initialColor) { mutableStateOf(argbToHex(initialColor)) }
    val hasHexError = remember(hexColor) { hexColor.isNotBlank() && parseHexColor(hexColor) == null }
    val currentArgb = rgbToArgb(red, green, blue)
    val preview = Color(currentArgb)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_custom_color_picker_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(preview)
                )
                OutlinedTextField(
                    value = hexColor,
                    onValueChange = {
                        hexColor = it
                        parseHexColor(it)?.let { parsed ->
                            val parsedColor = Color(parsed)
                            red = component255(parsedColor.red)
                            green = component255(parsedColor.green)
                            blue = component255(parsedColor.blue)
                        }
                    },
                    label = { Text(stringResource(R.string.settings_color_hex)) },
                    isError = hasHexError,
                    supportingText = {
                        if (hasHexError) {
                            Text(stringResource(R.string.settings_color_hex_invalid))
                        }
                    },
                    singleLine = true
                )
                Text(stringResource(R.string.settings_color_red))
                Slider(
                    value = red,
                    onValueChange = {
                        red = it
                        hexColor = argbToHex(rgbToArgb(red, green, blue))
                    },
                    valueRange = 0f..255f
                )
                Text(stringResource(R.string.settings_color_green))
                Slider(
                    value = green,
                    onValueChange = {
                        green = it
                        hexColor = argbToHex(rgbToArgb(red, green, blue))
                    },
                    valueRange = 0f..255f
                )
                Text(stringResource(R.string.settings_color_blue))
                Slider(
                    value = blue,
                    onValueChange = {
                        blue = it
                        hexColor = argbToHex(rgbToArgb(red, green, blue))
                    },
                    valueRange = 0f..255f
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(preview.toArgb()) }) {
                Text(stringResource(R.string.settings_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_cancel))
            }
        }
    )
}

private fun paletteLabel(palette: AppThemePalette): Int = when (palette) {
    AppThemePalette.BLUE -> R.string.settings_theme_palette_blue
    AppThemePalette.GREEN -> R.string.settings_theme_palette_green
    AppThemePalette.PURPLE -> R.string.settings_theme_palette_purple
    AppThemePalette.ORANGE -> R.string.settings_theme_palette_orange
    AppThemePalette.RED -> R.string.settings_theme_palette_red
    AppThemePalette.PINK -> R.string.settings_theme_palette_pink
    AppThemePalette.TEAL -> R.string.settings_theme_palette_teal
    AppThemePalette.YELLOW -> R.string.settings_theme_palette_yellow
    AppThemePalette.CUSTOM -> R.string.settings_theme_palette_custom
}

private fun imageDisplayName(uri: String?, fallback: String): String {
    return uri?.substringAfterLast('/') ?: fallback
}

private fun component255(value: Float): Float = value * 255f

private fun argbToHex(color: Int): String {
    val alpha = (color ushr 24) and 0xFF
    val rgb = color and 0x00FFFFFF
    return String.format("#%06X%02X", rgb, alpha)
}

private fun parseHexColor(value: String): Int? {
    val normalized = value.trim().removePrefix("#")
    return when (normalized.length) {
        3 -> {
            val expanded = buildString {
                normalized.forEach { append(it).append(it) }
            }
            expanded.toIntOrNull(16)?.let { 0xFF000000.toInt() or it }
        }
        6 -> normalized.toIntOrNull(16)?.let { 0xFF000000.toInt() or it }
        8 -> normalized.toLongOrNull(16)?.let {
            val rgb = (it ushr 8).toInt() and 0x00FFFFFF
            val alpha = (it and 0xFF).toInt() and 0xFF
            (alpha shl 24) or rgb
        }
        else -> null
    }
}

private fun saveImageToAppDir(context: android.content.Context, uri: android.net.Uri, fileName: String): String? {
    return runCatching {
        context.filesDir.listFiles()?.filter { it.nameWithoutExtension == fileName }?.forEach {
            if (!it.delete()) {
                android.util.Log.w(TAG, "Failed to delete old image file: ${it.absolutePath}")
            }
        }
        val mimeType = context.contentResolver.getType(uri).orEmpty()
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)?.takeIf { it.isNotBlank() } ?: run {
            android.util.Log.w(TAG, "Unknown mime type '$mimeType', fallback to .$DEFAULT_IMAGE_EXTENSION")
            DEFAULT_IMAGE_EXTENSION
        }
        val target = File(context.filesDir, "$fileName.$extension")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(target).use { output ->
                input.copyTo(output)
            }
        } ?: return null
        target.absolutePath
    }.getOrNull()
}

private fun rgbToArgb(red: Float, green: Float, blue: Float): Int {
    return Color(red / 255f, green / 255f, blue / 255f).toArgb()
}
