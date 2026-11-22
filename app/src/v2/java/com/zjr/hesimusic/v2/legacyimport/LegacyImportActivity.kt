package com.zjr.hesimusic.v2.legacyimport

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.zjr.hesimusic.BuildConfig
import com.zjr.hesimusic.data.preferences.PlaybackPreferences
import com.zjr.hesimusic.legacyimport.LegacyImportViewModel
import com.zjr.hesimusic.data.preferences.resolveDarkTheme
import com.zjr.hesimusic.ui.theme.HesimusicTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 「导入上一版数据」入口(v2 专属 Activity,见 app/src/v2/AndroidManifest.xml 与 docs/import-legacy-data.md)。
 *
 * 为什么是一个独立 Activity 而不是设置页里的一行:
 * 本任务的写域(issue 00030 任务 C)限定在 v2 源集、main 的 import 包、tools/ 与 docs/ 等目录,
 * 不允许改 main 侧的 MainActivity/SettingsScreen 导航;独立 Activity 因此既是唯一合法的接入点,
 * 也让"只在 v2 出现"这件事由清单隔离(prod/demo/algolab 不合并这个源集)。
 *
 * 入口:
 * 1. 桌面图标「导入上一版数据」(activity-alias,与 Hesi v2 主图标并存);
 * 2. `adb shell am start -n com.zjr.hesimusic.v2/com.zjr.hesimusic.v2.legacyimport.LegacyImportActivity`;
 * 3. 直接带文件:加了 `-d file:///sdcard/Download/hessi-import/<时间>/legacy-export.json`
 *    (或 content:// )会自动解析并显示预览,便于脚本化验证。
 */
@AndroidEntryPoint
class LegacyImportActivity : ComponentActivity() {

    @Inject
    lateinit var playbackPreferences: PlaybackPreferences

    private val viewModel: LegacyImportViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            val appThemeMode by playbackPreferences.appThemeModeFlow.collectAsState()
            val appThemePalette by playbackPreferences.appThemePaletteFlow.collectAsState()
            val customThemeColor by playbackPreferences.customThemeColorFlow.collectAsState()
            val isSystemDarkTheme = isSystemInDarkTheme()
            HesimusicTheme(
                darkTheme = resolveDarkTheme(appThemeMode, isSystemDarkTheme),
                palette = appThemePalette,
                customColor = customThemeColor,
            ) {
                LegacyImportScreen(viewModel = viewModel, onBack = { finish() })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /**
     * 允许从 adb/其它入口直接带一个文件进来(仅 file:// 与 content://)。
     *
     * `--ez autoImport true` 会在解析完成后**立刻开始导入**(供 tools/v2-import-drill.ps1 做无人值守演练);
     * 该开关只在 debug 构建生效(BuildConfig.DEBUG),release 包忽略,避免"一个 intent 就改数据库"。
     */
    private fun handleIntent(intent: Intent?) {
        val data: Uri = intent?.data ?: return
        val autoImport = intent.getBooleanExtra(EXTRA_AUTO_IMPORT, false) && BuildConfig.DEBUG
        when (data.scheme?.lowercase()) {
            "file" -> data.path?.let { path -> viewModel.openPath(path, autoImport) }
            "content" -> viewModel.openFile(data, autoImport)
        }
    }

    private companion object {
        const val EXTRA_AUTO_IMPORT = "autoImport"
    }
}
