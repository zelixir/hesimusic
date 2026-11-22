package com.zjr.hesimusic

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.zjr.hesimusic.data.model.Song
import com.zjr.hesimusic.demo.DemoEntryHooks
import com.zjr.hesimusic.loop.LoopIntent
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

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.inject.Inject

private const val STARTUP_IMAGE_DISPLAY_DURATION_MILLIS = 1200L

/**
 * How long the demo preparing screen stays in the home slot after pushing the
 * loop page. Longer than NavHost's default fade so the transition never briefly
 * reveals the (empty) home library underneath. Demo-only.
 */
private const val DEMO_LOADER_HOLD_MILLIS = 800L

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

        /**
         * Whether this process already pushed the demo loop page.
         *
         * Process-scoped on purpose (same lifetime rule as [startupCoverShownUri]):
         * an Activity recreation must not push it again after the user has backed
         * out to home, while a cold start after process death must replay it.
         */
        private var demoLandingDone: Boolean = false
    }

    @Inject
    lateinit var appLogger: AppLogger
    @Inject
    lateinit var playbackPreferences: PlaybackPreferences
    @Inject
    lateinit var loopIntent: LoopIntent
    
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

                // ---- demo 包直达结果页(正式包 demoSession 恒为 null,以下逻辑不生效) ----
                // 会话由 demo 源集的 ContentProvider 在进程启动早期注册;它异步完成
                // 「复制 assets/demo/night.ogg → 写 songs/song_loop」后给出 songId。
                // 准备期间不组合 NavHost(避免先闪一下空曲库/触发无关初始化),准备完成后
                // 进入正常 NavHost,并在首帧后导航到现有 loop/{songId} 路由。
                val demoSession = remember { DemoEntryHooks.session }
                val demoSongId: Long?
                val demoFailure: String?
                if (demoSession != null) {
                    demoSongId = demoSession.songId.collectAsState().value
                    demoFailure = demoSession.failure.collectAsState().value
                } else {
                    demoSongId = null
                    demoFailure = null
                }
                if (demoSession != null && demoSongId == null && demoFailure == null) {
                    DemoPreparingScreen()
                    return@HesimusicTheme
                }
                if (demoFailure != null) {
                    Log.w(TAG, "demo bootstrap failed, fall back to normal home: $demoFailure")
                }

                val navController = rememberNavController()

                // 起点始终是 home;demo 首帧由 home 槽位先显示准备页,再把 loop/{songId}
                // 压到它的上面(home 留在栈底,返回键 / 确认保存后的 pop 行为与原流程一致)。

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
                            // demo 首帧:home 槽位先显示准备页,把 loop/{songId} 压到栈顶,
                            // 并在导航过渡期间继续盖住 home,避免露出尚未加载的空曲库。
                            // 过渡结束/home 被重新组合时,demoLandingDone 已为 true,直接进入正常界面。
                            var demoLoaderDone by remember { mutableStateOf(demoLandingDone) }
                            val showDemoLoader = demoSession != null &&
                                demoSongId != null &&
                                !demoLoaderDone
                            if (showDemoLoader) {
                                DemoPreparingScreen()
                                LaunchedEffect(demoSongId) {
                                    if (!demoLandingDone) {
                                        val songId = demoSongId ?: return@LaunchedEffect
                                        // 双保险:即使 bootstrap 的 arm 因任何原因没执行,
                                        // 真正导航进结果页前也重新 arm 一次(幂等,消费后清空)。
                                        DemoEntryHooks.armAutoAudition(songId)
                                        Log.i(TAG, "demo auto audition arm at navigation songId=$songId")
                                        navController.navigate("loop/$songId")
                                        demoLandingDone = true
                                    }
                                    delay(DEMO_LOADER_HOLD_MILLIS)
                                    demoLoaderDone = true
                                }
                            } else {
                                val homeMusicViewModel: MusicViewModel = hiltViewModel()
                                val loopActions = rememberLoopMenuActions(
                                    navController = navController,
                                    musicViewModel = homeMusicViewModel,
                                    loopIntent = loopIntent,
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
                                    onSeamlessAnalysis = loopActions.onSeamlessAnalysis,
                                    onPrewarmSeamlessLoop = { song, aMs, bMs ->
                                        homeMusicViewModel.prewarmSeamlessLoopEntry(song, aMs, bMs)
                                    },
                                    onCancelPrewarm = { songId ->
                                        homeMusicViewModel.cancelSeamlessLoopPrewarm(songId)
                                    },
                                )
                            }
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
                                loopIntent = loopIntent,
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
                                onSeamlessAnalysis = loopActions.onSeamlessAnalysis,
                                onPrewarmSeamlessLoop = { song, aMs, bMs ->
                                    detailMusicViewModel.prewarmSeamlessLoopEntry(song, aMs, bMs)
                                },
                                onCancelPrewarm = { songId ->
                                    detailMusicViewModel.cancelSeamlessLoopPrewarm(songId)
                                },
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
                                    // 契约 §2.4：确认保存 → 显式武装该歌的无缝意图 → 返回。
                                    // 00029：入口必须**先停旧声、位置归 0**，等环带源 READY 由
                                    // 播放层从 0 起播；不再走 playKeepPosition（那会先播 ~100ms
                                    // 旧位置再跳回 0，用户肉眼可见）。
                                    sharedMusicViewModel.beginSeamlessLoopEntry(song)
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

/**
 * demo 包准备中的过渡页(仅 demo 包会看到:正式包 demoSession 为 null,不会进入此分支)。
 *
 * 素材只有 4MB,通常不到 1 秒;这里只负责在写库完成前占住首帧,避免闪现空曲库。
 */
@Composable
private fun DemoPreparingScreen() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "正在准备 Demo…",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** 菜单「无缝循环 / 无缝分析」事件(MainActivity 负责播放/导航,菜单只发事件)。 */
private data class LoopMenuActions(
    val onSeamlessLoop: (Song) -> Unit,
    val onSeamlessAnalysis: (Song) -> Unit,
)

/**
 * 构造菜单两个入口的回调:
 * - 「无缝循环」(菜单仅在行 confirmed=1 时显示)→ **显式武装该歌的无缝意图** + 播放该歌
 *   (repeat=ONE / shuffle=false 由播放层唯一写点应用,菜单不直接写这两个属性);
 * - 「无缝分析」(始终显示,仅 CUE 置灰)→ 导航 loop/{songId}(进页加载已存结果 / 自动分析)。
 *
 * 「重新分析 / 取消最终结果 / 删除分析结果」已从长按菜单移入结果页(页内自行处理)。
 */
@Composable
private fun rememberLoopMenuActions(
    navController: NavHostController,
    musicViewModel: MusicViewModel,
    loopIntent: LoopIntent,
): LoopMenuActions {
    return remember(navController, musicViewModel, loopIntent) {
        LoopMenuActions(
            onSeamlessLoop = { song ->
                // 00029：显式进入 = armEntry + 立刻停旧声/位置归 0，源 READY 后由播放层
                // 从 0 起播（repeat=ONE / shuffle=false 仍由播放层唯一写点应用）。
                musicViewModel.beginSeamlessLoopEntry(song)
            },
            onSeamlessAnalysis = { song -> navController.navigate("loop/${song.id}") },
        )
    }
}
