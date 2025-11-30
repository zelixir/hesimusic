package com.zjr.hesimusic

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.zjr.hesimusic.ui.debug.TagDebugScreen
import com.zjr.hesimusic.ui.scan.ScanScreen
import com.zjr.hesimusic.ui.test.PlayerTestScreen
import com.zjr.hesimusic.ui.theme.HesimusicTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HesimusicTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    var currentScreen by remember { mutableStateOf("scan") }

                    Box(modifier = Modifier.padding(innerPadding)) {
                        when (currentScreen) {
                            "scan" -> ScanScreen(
                                onDebugClick = { currentScreen = "debug" },
                                onPlayerTestClick = { currentScreen = "player_test" }
                            )
                            "debug" -> {
                                BackHandler {
                                    currentScreen = "scan"
                                }
                                TagDebugScreen()
                            }
                            "player_test" -> {
                                BackHandler {
                                    currentScreen = "scan"
                                }
                                PlayerTestScreen()
                            }
                        }
                    }
                }
            }
        }
    }
}