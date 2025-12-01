package com.zjr.hesimusic

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.zjr.hesimusic.data.preferences.PlaylistType
import com.zjr.hesimusic.ui.about.AboutScreen
import com.zjr.hesimusic.ui.common.MusicViewModel
import com.zjr.hesimusic.ui.debug.TagDebugScreen
import com.zjr.hesimusic.ui.equalizer.EqualizerScreen
import com.zjr.hesimusic.ui.library.SongListScreen
import com.zjr.hesimusic.ui.main.MainScreen
import com.zjr.hesimusic.ui.player.PlayerScreen
import com.zjr.hesimusic.ui.scan.ScanScreen
import com.zjr.hesimusic.ui.settings.SettingsScreen
import com.zjr.hesimusic.ui.sleeptimer.SleepTimerScreen
import com.zjr.hesimusic.ui.test.PlayerTestScreen
import com.zjr.hesimusic.ui.theme.HesimusicTheme
import dagger.hilt.android.AndroidEntryPoint

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HesimusicTheme {
                val navController = rememberNavController()
                val musicViewModel: MusicViewModel = hiltViewModel()
                
                // Remember initial navigation state
                var initialTab by remember { mutableIntStateOf(0) }
                var initialFolderPath by remember { mutableStateOf<String?>(null) }
                var hasRestoredContext by remember { mutableStateOf(false) }

                // Restore playback context on first launch
                LaunchedEffect(Unit) {
                    if (!hasRestoredContext) {
                        hasRestoredContext = true
                        val context = musicViewModel.getPlaybackContext()
                        if (context != null) {
                            when (context.playlistType) {
                                PlaylistType.ALL_SONGS -> {
                                    // Stay on songs tab (index 0)
                                    initialTab = 0
                                }
                                PlaylistType.ARTIST -> {
                                    // Navigate to artist details
                                    val encodedName = URLEncoder.encode(context.identifier, StandardCharsets.UTF_8.toString()).replace("+", "%20")
                                    navController.navigate("details/artist/$encodedName")
                                }
                                PlaylistType.ALBUM -> {
                                    // Navigate to album details
                                    val encodedName = URLEncoder.encode(context.identifier, StandardCharsets.UTF_8.toString()).replace("+", "%20")
                                    navController.navigate("details/album/$encodedName")
                                }
                                PlaylistType.FOLDER -> {
                                    // Switch to folder tab and navigate to the specific folder
                                    initialTab = 3
                                    initialFolderPath = context.identifier
                                }
                            }
                        }
                    }
                }
                
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
                                musicViewModel = musicViewModel,
                                initialTab = initialTab,
                                initialFolderPath = initialFolderPath,
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
                                onSettingsClick = { navController.navigate("settings") },
                                onEqualizerClick = { navController.navigate("equalizer") },
                                onAboutClick = { navController.navigate("about") },
                                onSleepTimerClick = { navController.navigate("sleep_timer") }
                            )
                        }

                        composable("player") {
                            PlayerScreen(onBackClick = { navController.popBackStack() })
                        }

                        composable("settings") {
                            SettingsScreen(onBackClick = { navController.popBackStack() })
                        }

                        composable("equalizer") {
                            EqualizerScreen(onBackClick = { navController.popBackStack() })
                        }

                        composable("about") {
                            AboutScreen(onBackClick = { navController.popBackStack() })
                        }

                        composable("sleep_timer") {
                            SleepTimerScreen(onBackClick = { navController.popBackStack() })
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
                                musicViewModel = musicViewModel,
                                onBack = { navController.popBackStack() },
                                onSongClick = { /* TODO: Play */ },
                                onScanClick = { navController.navigate("scan") },
                                onSettingsClick = { navController.navigate("settings") },
                                onEqualizerClick = { navController.navigate("equalizer") },
                                onAboutClick = { navController.navigate("about") },
                                onSleepTimerClick = { navController.navigate("sleep_timer") }
                            )
                        }
                    }
                }
            }
        }
    }
}