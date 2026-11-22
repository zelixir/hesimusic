package com.zjr.hesimusic.lab

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.hilt.navigation.compose.hiltViewModel
import com.zjr.hesimusic.BuildConfig
import com.zjr.hesimusic.ui.theme.HesimusicTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * 算法实验 app 的唯一入口(algolab flavor)。
 *
 * 防御:即使有人错误地把 Lab 类打进其它 flavor,[BuildConfig.ALGO_LAB] 也保证
 * 入口直接退出。正常构建下 algolab 的 ALGO_LAB=true,prod/demo 的 algolab 源集
 * 根本不参与编译。
 */
@AndroidEntryPoint
class AlgoLabActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.ALGO_LAB) {
            finish()
            return
        }
        enableEdgeToEdge()
        setContent {
            HesimusicTheme {
                val viewModel: AlgoLabViewModel = hiltViewModel()
                AlgoLabScreen(viewModel)
            }
        }
    }
}
