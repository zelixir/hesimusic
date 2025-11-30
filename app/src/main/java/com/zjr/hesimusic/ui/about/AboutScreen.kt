package com.zjr.hesimusic.ui.about

import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBackClick: () -> Unit
) {
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
        Column(modifier = Modifier.padding(innerPadding)) {
            Text("海瑟音乐 v1.0")
            Text("海瑟音乐是一款专为音乐爱好者打造的本地音乐播放器。我们致力于还原音乐最纯粹的本质，提供无损音质播放、便捷的媒体库管理以及个性化的播放体验。无论您身在何处，海瑟音乐都能让您随时随地享受美妙的旋律。软件界面简洁直观，操作流畅丝滑，支持多种主流音频格式。感谢您选择海瑟音乐，我们将持续改进，为您带来更多惊喜。")
            Text("开发者: zelixir")
        }
    }
}
