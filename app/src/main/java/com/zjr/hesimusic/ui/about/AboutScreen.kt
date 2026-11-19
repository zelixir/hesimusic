package com.zjr.hesimusic.ui.about

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.zjr.hesimusic.BuildConfig
import com.zjr.hesimusic.loop.audio.source.LoopEngineInfo

/** 信息区 label 列宽(行内对齐用;复制文本另按显示列对齐,见 [AboutInfoText.formatForClipboard])。 */
private val LABEL_WIDTH = 88.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    // 进入页面时采样一次:版本/渠道/构建时间是编译期常量,引擎是运行时真实值。
    val info = remember { loadAboutInfo(context) }
    val rows = remember(info) { AboutInfoText.rows(info) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("关于") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text("海瑟音乐", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            // 长按整段信息即可复制,方便截图/反馈时带上真实版本与引擎口径。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(info) {
                        detectTapGestures(
                            onLongPress = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                copyAboutInfo(context, info)
                            }
                        )
                    }
                    .padding(vertical = 4.dp)
            ) {
                rows.forEach { row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                    ) {
                        Text(
                            text = row.label + ":",
                            modifier = Modifier.width(LABEL_WIDTH),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = row.value,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = if (row.label == AboutInfoText.LABEL_ENGINE_RAW) {
                                FontFamily.Monospace
                            } else {
                                null
                            }
                        )
                    }
                }
            }
            Text(
                text = "长按上方信息可复制整段",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            Text(
                text = "海瑟音乐是一款专为音乐爱好者打造的本地音乐播放器。我们致力于还原音乐最纯粹的本质，提供无损音质播放、便捷的媒体库管理以及个性化的播放体验。无论您身在何处，海瑟音乐都能让您随时随地享受美妙的旋律。软件界面简洁直观，操作流畅丝滑，支持多种主流音频格式。感谢您选择海瑟音乐，我们将持续改进，为您带来更多惊喜。",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(8.dp))
            Text("开发者: zelixir", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * 读取当前构建与引擎信息。
 *
 * - 版本/渠道/构建时间:编译期写入 `BuildConfig`(见 app/build.gradle.kts 的 buildConfigField);
 * - 引擎/回退原因:`LoopEngineInfo.current(context)` 运行时真实值;
 * - 引擎原始单行:`LoopEngineInfo.display(context)`(截图/反馈直接引用)。
 */
internal fun loadAboutInfo(context: Context): AboutInfo {
    val engine = LoopEngineInfoSource.current(context)
    return AboutInfo(
        versionName = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE.toLong(),
        flavor = BuildConfig.FLAVOR,
        buildTime = BuildConfig.BUILD_TIME,
        engine = engine.engine,
        loopPath = engine.loopPath,
        bandBytes = engine.bandBytes,
        fallbackReason = engine.fallbackReason,
        engineRaw = runCatching { LoopEngineInfo.display(context) }.getOrDefault(""),
    )
}

/** 整段信息写入剪贴板(纯格式化在 [AboutInfoText.formatForClipboard],这里只负责 Android 侧动作)。 */
private fun copyAboutInfo(context: Context, info: AboutInfo) {
    val text = AboutInfoText.formatForClipboard(info)
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("关于", text))
    Toast.makeText(context, "版本信息已复制", Toast.LENGTH_SHORT).show()
}
