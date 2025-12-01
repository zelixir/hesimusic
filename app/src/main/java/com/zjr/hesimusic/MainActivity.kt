package com.zjr.hesimusic

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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

private const val TAG = "MainActivity"

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate: MainActivity created")
        enableEdgeToEdge()
        setContent {
            HesimusicTheme {
                val navController = rememberNavController()
                val musicViewModel: MusicViewModel = hiltViewModel()
                val savedPlaylistContext by musicViewModel.savedPlaylistContext.collectAsState()
                
                // Handle navigation to artist/album detail pages based on saved context
                // This runs once when the saved context is available and contains artist/album type
                LaunchedEffect(savedPlaylistContext) {
                    savedPlaylistContext?.let { context ->
                        Log.d(TAG, "LaunchedEffect: checking if navigation needed for context type=${context.type}, value=${context.value}")
                        when (context.type) {
                            PlaylistType.ARTIST -> {
                                if (context.value.isNotEmpty()) {
                                    val encodedName = URLEncoder.encode(context.value, StandardCharsets.UTF_8.toString()).replace("+", "%20")
                                    Log.d(TAG, "LaunchedEffect: navigating to artist detail: ${context.value}")
                                    navController.navigate("details/artist/$encodedName")
                                    musicViewModel.consumeSavedPlaylistContext()
                                }
                            }
                            PlaylistType.ALBUM -> {
                                if (context.value.isNotEmpty()) {
                                    val encodedName = URLEncoder.encode(context.value, StandardCharsets.UTF_8.toString()).replace("+", "%20")
                                    Log.d(TAG, "LaunchedEffect: navigating to album detail: ${context.value}")
                                    navController.navigate("details/album/$encodedName")
                                    musicViewModel.consumeSavedPlaylistContext()
                                }
                            }
                            else -> {
                                // For GLOBAL, FAVORITES, FOLDER - MainScreen will handle tab switching
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