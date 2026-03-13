package com.zjr.hesimusic

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.zjr.hesimusic.ui.about.AboutScreen
import com.zjr.hesimusic.ui.debug.TagDebugScreen
import com.zjr.hesimusic.ui.equalizer.EqualizerScreen
import com.zjr.hesimusic.ui.library.SongListScreen
import com.zjr.hesimusic.ui.logs.LogScreen
import com.zjr.hesimusic.ui.main.MainScreen
import com.zjr.hesimusic.ui.player.PlayerScreen
import com.zjr.hesimusic.ui.scan.ScanScreen
import com.zjr.hesimusic.ui.settings.BackupRestoreScreen
import com.zjr.hesimusic.ui.settings.SettingsScreen
import com.zjr.hesimusic.ui.sleeptimer.SleepTimerScreen
import com.zjr.hesimusic.ui.test.PlayerTestScreen
import com.zjr.hesimusic.ui.theme.HesimusicTheme
import com.zjr.hesimusic.ui.common.MusicViewModel
import com.zjr.hesimusic.data.preferences.PlaybackPreferences
import com.zjr.hesimusic.data.preferences.resolveDarkTheme
import com.zjr.hesimusic.utils.AppLogger
import coil.compose.AsyncImage
import coil.request.ImageRequest
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.inject.Inject

private const val STARTUP_IMAGE_DISPLAY_DURATION_MILLIS = 1200L

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    
    @Inject
    lateinit var appLogger: AppLogger
    @Inject
    lateinit var playbackPreferences: PlaybackPreferences
    
    private val TAG = "MainActivity"
    
    override fun onCreate(savedInstanceState: Bundle?) {
        val activityStartTime = System.currentTimeMillis()
        Log.i(TAG, "MainActivity onCreate started")
        
        val superStartTime = System.currentTimeMillis()
        super.onCreate(savedInstanceState)
        val superDuration = System.currentTimeMillis() - superStartTime
        appLogger.timing(TAG, "Activity super.onCreate (Hilt injection)", superDuration)
        
        val edgeToEdgeStartTime = System.currentTimeMillis()
        enableEdgeToEdge()
        val edgeToEdgeDuration = System.currentTimeMillis() - edgeToEdgeStartTime
        appLogger.timing(TAG, "enableEdgeToEdge", edgeToEdgeDuration)
        
        val uiStartTime = System.currentTimeMillis()
        setContent {
            val appThemeMode by playbackPreferences.appThemeModeFlow.collectAsState()
            val appThemePalette by playbackPreferences.appThemePaletteFlow.collectAsState()
            val customThemeColor by playbackPreferences.customThemeColorFlow.collectAsState()
            val startupImageUri by playbackPreferences.startupImageUriFlow.collectAsState()
            val isSystemDarkTheme = isSystemInDarkTheme()
            HesimusicTheme(
                darkTheme = resolveDarkTheme(appThemeMode, isSystemDarkTheme),
                palette = appThemePalette,
                customColor = customThemeColor
            ) {
                var hasShownStartup by rememberSaveable { mutableStateOf(false) }
                if (!hasShownStartup && !startupImageUri.isNullOrBlank()) {
                    LaunchedEffect(startupImageUri) {
                        delay(STARTUP_IMAGE_DISPLAY_DURATION_MILLIS)
                        hasShownStartup = true
                    }
                    Box(modifier = Modifier.fillMaxSize()) {
                        AsyncImage(
                            model = ImageRequest.Builder(this@MainActivity)
                                .data(startupImageUri)
                                .crossfade(true)
                                .build(),
                            contentDescription = stringResource(R.string.settings_startup_image_title),
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
                    return@HesimusicTheme
                }
                val navController = rememberNavController()
                
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    // We ignore innerPadding here because each screen handles its own Scaffold/Padding
                    
                    NavHost(
                        navController = navController,
                        startDestination = "home",
                        modifier = Modifier.fillMaxSize()
                    ) {
                        composable("scan") {
                            ScanScreen(
                                onDebugClick = { navController.navigate("debug") },
                                onPlayerTestClick = { navController.navigate("player_test") },
                                onLibraryClick = { navController.navigate("home") }
                            )
                        }
                        
                        composable("home") {
                            MainScreen(
                                onArtistClick = { artist ->
                                    val encodedName = URLEncoder.encode(artist.name, StandardCharsets.UTF_8.toString()).replace("+", "%20")
                                    navController.navigate("details/artist/$encodedName")
                                },
                                onAlbumClick = { album ->
                                    val encodedName = URLEncoder.encode(album.name, StandardCharsets.UTF_8.toString()).replace("+", "%20")
                                    navController.navigate("details/album/$encodedName")
                                },
                                onPlayerClick = {
                                    navController.navigate("player")
                                },
                                onScanClick = { navController.navigate("scan") },
                                onBackupRestoreClick = { navController.navigate("backup_restore") },
                                onSettingsClick = { navController.navigate("settings") },
                                onEqualizerClick = { navController.navigate("equalizer") },
                                onAboutClick = { navController.navigate("about") },
                                onSleepTimerClick = { navController.navigate("sleep_timer") },
                                onLogsClick = { navController.navigate("logs") }
                            )
                        }

                        composable("player") {
                            val homeEntry = runCatching { navController.getBackStackEntry("home") }.getOrNull()
                            val sharedMusicViewModel: MusicViewModel = if (homeEntry != null) {
                                hiltViewModel(homeEntry)
                            } else {
                                hiltViewModel()
                            }
                            PlayerScreen(
                                onBackClick = { navController.popBackStack() },
                                viewModel = sharedMusicViewModel
                            )
                        }

                        composable("settings") {
                            SettingsScreen(onBackClick = { navController.popBackStack() })
                        }

                        composable("backup_restore") {
                            BackupRestoreScreen(onBackClick = { navController.popBackStack() })
                        }

                        composable("equalizer") {
                            EqualizerScreen(onBackClick = { navController.popBackStack() })
                        }

                        composable("about") {
                            AboutScreen(onBackClick = { navController.popBackStack() })
                        }

                        composable("sleep_timer") {
                            val homeEntry = runCatching { navController.getBackStackEntry("home") }.getOrNull()
                            val sharedMusicViewModel: MusicViewModel = if (homeEntry != null) {
                                hiltViewModel(homeEntry)
                            } else {
                                hiltViewModel()
                            }
                            SleepTimerScreen(
                                onBackClick = { navController.popBackStack() },
                                viewModel = sharedMusicViewModel
                            )
                        }

                        composable("logs") {
                            LogScreen(onBackClick = { navController.popBackStack() })
                        }

                        composable("debug") {
                            TagDebugScreen()
                        }
                        
                        composable("player_test") {
                            PlayerTestScreen()
                        }
                        
                        composable(
                            route = "details/{type}/{value}",
                            arguments = listOf(
                                navArgument("type") { type = NavType.StringType },
                                navArgument("value") { type = NavType.StringType }
                            )
                        ) { backStackEntry ->
                            val type = backStackEntry.arguments?.getString("type") ?: ""
                            val value = backStackEntry.arguments?.getString("value") ?: ""
                            SongListScreen(
                                type = type,
                                value = value,
                                onBack = { navController.popBackStack() },
                                onSongClick = { /* TODO: Play */ },
                                onPlayerClick = { navController.navigate("player") },
                                onScanClick = { navController.navigate("scan") },
                                onBackupRestoreClick = { navController.navigate("backup_restore") },
                                onSettingsClick = { navController.navigate("settings") },
                                onEqualizerClick = { navController.navigate("equalizer") },
                                onAboutClick = { navController.navigate("about") },
                                onSleepTimerClick = { navController.navigate("sleep_timer") },
                                onLogsClick = { navController.navigate("logs") }
                            )
                        }
                    }
                }
            }
        }
        val uiDuration = System.currentTimeMillis() - uiStartTime
        appLogger.timing(TAG, "UI composition (Compose + Navigation setup)", uiDuration)
        
        val totalDuration = System.currentTimeMillis() - activityStartTime
        appLogger.timing(TAG, "Total MainActivity onCreate", totalDuration)
        appLogger.info(TAG, "MainActivity initialized successfully")
    }
}
