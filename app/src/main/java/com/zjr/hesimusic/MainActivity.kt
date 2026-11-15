package com.zjr.hesimusic

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.Player
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.compose.ui.platform.LocalContext
import com.zjr.hesimusic.data.model.Song
import com.zjr.hesimusic.loop.analysis.LoopAnalysisManager
import com.zjr.hesimusic.ui.about.AboutScreen
import com.zjr.hesimusic.ui.common.UiNavigationHooks
import com.zjr.hesimusic.ui.debug.TagDebugScreen
import com.zjr.hesimusic.ui.equalizer.EqualizerScreen
import com.zjr.hesimusic.ui.library.SongListScreen
import com.zjr.hesimusic.ui.logs.LogScreen
import com.zjr.hesimusic.ui.loop.LoopScreen
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
import kotlinx.coroutines.launch

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.inject.Inject

private const val STARTUP_IMAGE_DISPLAY_DURATION_MILLIS = 1200L

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    companion object {
        /**
         * The startup cover image shown during this process's lifetime, null if none.
         * Kept in memory on purpose: process death (force-stop, OEM killers) resets it
         * so the cover replays on cold start, while a background activity recreation
         * (process still alive) keeps it and skips the cover.
         */
        private var startupCoverShownUri: String? = null
    }

    @Inject
    lateinit var appLogger: AppLogger
    @Inject
    lateinit var playbackPreferences: PlaybackPreferences
    @Inject
    lateinit var loopAnalysisManager: LoopAnalysisManager
    
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
                if (!hasShownStartup && shouldShowStartupImage(startupImageUri)) {
                    LaunchedEffect(startupImageUri) {
                        // Mark this image as shown immediately (before the delay),
                        // so background activity destruction/restarts cannot replay it.
                        startupCoverShownUri = startupImageUri
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

                // main 侧导航钩子:debug 协议 /navigate 经它真正打开路由(debug 源集只读)。
                // 回调可能来自调试服务线程,统一切回主线程再调 NavController。
                LaunchedEffect(navController) {
                    UiNavigationHooks.navigate = { route ->
                        this@MainActivity.runOnUiThread {
                            runCatching { navController.navigate(route.trimStart('/')) }
                                .onFailure { Log.w(TAG, "debug navigate failed: $route", it) }
                        }
                    }
                }
                DisposableEffect(navController) {
                    onDispose { UiNavigationHooks.navigate = null }
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
                            val homeMusicViewModel: MusicViewModel = hiltViewModel()
                            val loopActions = rememberLoopMenuActions(
                                navController = navController,
                                musicViewModel = homeMusicViewModel,
                                loopAnalysisManager = loopAnalysisManager,
                            )
                            MainScreen(
                                musicViewModel = homeMusicViewModel,
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
                                onLogsClick = { navController.navigate("logs") },
                                onSeamlessLoop = loopActions.onSeamlessLoop,
                                onLoopReanalyze = loopActions.onReanalyze,
                                onLoopDemote = loopActions.onDemote,
                                onLoopDelete = loopActions.onDelete,
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
                            val detailMusicViewModel: MusicViewModel = hiltViewModel()
                            val loopActions = rememberLoopMenuActions(
                                navController = navController,
                                musicViewModel = detailMusicViewModel,
                                loopAnalysisManager = loopAnalysisManager,
                            )
                            SongListScreen(
                                type = type,
                                value = value,
                                musicViewModel = detailMusicViewModel,
                                onBack = { navController.popBackStack() },
                                onSongClick = { /* TODO: Play */ },
                                onPlayerClick = { navController.navigate("player") },
                                onScanClick = { navController.navigate("scan") },
                                onBackupRestoreClick = { navController.navigate("backup_restore") },
                                onSettingsClick = { navController.navigate("settings") },
                                onEqualizerClick = { navController.navigate("equalizer") },
                                onAboutClick = { navController.navigate("about") },
                                onSleepTimerClick = { navController.navigate("sleep_timer") },
                                onLogsClick = { navController.navigate("logs") },
                                onSeamlessLoop = loopActions.onSeamlessLoop,
                                onLoopReanalyze = loopActions.onReanalyze,
                                onLoopDemote = loopActions.onDemote,
                                onLoopDelete = loopActions.onDelete,
                            )
                        }

                        // 无缝循环分析/预览页(契约 §1/§2)。CUE 守卫在 LoopViewModel 内
                        // (song.isCue → Failed 文案),菜单侧同时置灰避免进入。
                        composable(
                            route = "loop/{songId}",
                            arguments = listOf(
                                navArgument("songId") { type = NavType.LongType }
                            )
                        ) {
                            val homeEntry = runCatching { navController.getBackStackEntry("home") }.getOrNull()
                            val sharedMusicViewModel: MusicViewModel = if (homeEntry != null) {
                                hiltViewModel(homeEntry)
                            } else {
                                hiltViewModel()
                            }
                            LoopScreen(
                                musicViewModel = sharedMusicViewModel,
                                onBack = { navController.popBackStack() },
                                onSavedAndPlay = { song ->
                                    // 契约 §2.4:确认保存 → 播放该歌 + 单曲循环 → 返回
                                    sharedMusicViewModel.play(song)
                                    sharedMusicViewModel.setRepeatMode(Player.REPEAT_MODE_ONE)
                                    navController.popBackStack()
                                },
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

    /**
     * Whether the startup cover image should be shown. The cover is displayed at
     * most once per image per process: [startupCoverShownUri] remembers in memory
     * which image this process already showed, so the cover replays after process
     * death (cold start, force-stop + relaunch) or when the user picks a different
     * startup image, but not when an OEM background cleaner merely recreates the
     * activity without killing the process (which resets Compose saved state).
     */
    private fun shouldShowStartupImage(startupImageUri: String?): Boolean {
        if (startupImageUri.isNullOrBlank()) return false
        if (startupImageUri == startupCoverShownUri) {
            Log.d(TAG, "Skipping startup image: already shown in this process")
            return false
        }
        Log.d(TAG, "Showing startup image")
        return true
    }
}

/** 菜单「无缝循环」相关事件的处理集合(MainActivity 负责播放/导航/写库,菜单只发事件)。 */
private data class LoopMenuActions(
    val onSeamlessLoop: (Song, Boolean?) -> Unit,
    val onReanalyze: (Song) -> Unit,
    val onDemote: (Song) -> Unit,
    val onDelete: (Song) -> Unit,
)

/**
 * 按契约 §1 的入口分流构造回调:
 * - 行 confirmed=1 → 播放该歌 + repeatMode=ONE(无缝由播放层窗口驱动);
 * - 行 confirmed=0(临时)或无行 → 导航 loop/{songId}(进页加载已存结果 / 自动分析)。
 *
 * 「重新分析」导航到同一路由(页面加载已存结果,重分析走页内按钮);
 * 「取消最终结果」「删除分析结果」直接调 Manager(菜单不写播放逻辑)。
 */
@Composable
private fun rememberLoopMenuActions(
    navController: NavHostController,
    musicViewModel: MusicViewModel,
    loopAnalysisManager: LoopAnalysisManager,
): LoopMenuActions {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    return remember(navController, musicViewModel, loopAnalysisManager) {
        LoopMenuActions(
            onSeamlessLoop = { song, confirmed ->
                when (confirmed) {
                    true -> {
                        musicViewModel.play(song)
                        musicViewModel.setRepeatMode(Player.REPEAT_MODE_ONE)
                    }
                    else -> navController.navigate("loop/${song.id}")
                }
            },
            onReanalyze = { song -> navController.navigate("loop/${song.id}") },
            onDemote = { song ->
                scope.launch {
                    runCatching { loopAnalysisManager.demoteFinal(song.id) }
                    Toast.makeText(context, "已转为临时结果，不再无缝", Toast.LENGTH_SHORT).show()
                }
            },
            onDelete = { song ->
                scope.launch {
                    runCatching { loopAnalysisManager.deleteResult(song.id) }
                    Toast.makeText(context, "已删除分析结果，下次进入将重新分析", Toast.LENGTH_SHORT).show()
                }
            },
        )
    }
}
