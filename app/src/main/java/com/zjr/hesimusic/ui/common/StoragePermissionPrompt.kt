package com.zjr.hesimusic.ui.common

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.zjr.hesimusic.service.PlaybackBlock
import com.zjr.hesimusic.service.PlaybackBlockStore

/*
 * 「播放因文件权限失败」的授权对话框（issue 00030）。
 *
 * Service 侧（PlaybackBlockStore）只发布事件；这里负责三件事：
 * 1. 前台才弹（后台时不消费，事件留在 store 里，回前台再弹）；
 * 2. 一次消费（旋转 / 重进页面不会重复弹）；
 * 3. 已授予「所有文件访问」时静默丢弃（恢复权限后不再打扰）。
 *
 * 两条按钮：去授权 → 系统「所有文件访问」页（不可用时退回应用详情页）；稍后 → 关闭。
 */

/** 对话框固定文案；单测断言用同一份常量。 */
internal object StoragePermissionDialogStrings {
    const val TITLE = "无法读取音频文件"
    const val MESSAGE = "应用需要「所有文件访问」权限才能播放本地音乐"
    const val CONFIRM = "去授权"
    const val DISMISS = "稍后"
}

/** 当前是否已授予「所有文件访问」（minSdk 31，无需版本分支）。 */
internal fun hasAllFilesAccess(): Boolean = Environment.isExternalStorageManager()

/**
 * 纯判据：这条事件现在该不该弹对话框。
 *
 * 已授权时即使底层仍报 IO 错误也不再引导授权（那种情况要么是文件真的没了，
 * 要么是存储未挂载，授权页帮不上忙），避免把用户反复送去设置页。
 */
internal object StoragePermissionPromptPolicy {
    fun shouldPrompt(
        block: PlaybackBlock?,
        allFilesAccessGranted: Boolean,
        appInForeground: Boolean,
    ): Boolean = block != null && appInForeground && !allFilesAccessGranted
}

/**
 * 从 store 取一条要展示的事件（一次性消费的唯一入口，纯函数 + 注入 consume 便于 JVM 单测）。
 *
 * - 不在前台：**不消费**（后台先记着，回前台再弹）；
 * - 已授权：消费后丢弃（返回 null）；
 * - 否则：消费并返回，交给 AlertDialog。
 */
internal fun takeBlockForPrompt(
    candidate: PlaybackBlock?,
    allFilesAccessGranted: Boolean,
    appInForeground: Boolean,
    consume: (PlaybackBlock) -> PlaybackBlock?,
): PlaybackBlock? {
    val block = candidate ?: return null
    if (!appInForeground) return null
    val consumed = consume(block) ?: return null
    return if (StoragePermissionPromptPolicy.shouldPrompt(consumed, allFilesAccessGranted, true)) {
        consumed
    } else {
        null
    }
}

/**
 * 跳转「所有文件访问」设置页。
 *
 * 部分 OEM / 精简系统没有 [Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION]
 * 的 Activity（抛 ActivityNotFoundException），此时退回本应用的详情页
 * （[Settings.ACTION_APPLICATION_DETAILS_SETTINGS]），用户仍能进「权限」页授权。
 */
internal object AllFilesAccessLauncher {

    fun primaryIntent(packageName: String): Intent =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))

    fun fallbackIntent(packageName: String): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))

    /** 返回真正用上的 Intent；两个都不可用（极罕见）时返回 null。 */
    fun start(packageName: String, startActivity: (Intent) -> Unit): Intent? {
        val primary = primaryIntent(packageName)
        return try {
            startActivity(primary)
            primary
        } catch (_: ActivityNotFoundException) {
            val fallback = fallbackIntent(packageName)
            try {
                startActivity(fallback)
                fallback
            } catch (_: ActivityNotFoundException) {
                null
            }
        }
    }

    fun launch(context: Context): Intent? = start(context.packageName) { intent ->
        // 非 Activity context 上启动设置页需要 NEW_TASK；Compose 里通常是 Activity，保持无标志。
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}

/**
 * 把 host 挂在主界面 / 播放页（NavHost 同时只组合一个目标页，所以任一时刻最多一个 host
 * 拿到事件；消费是原子的，过渡帧里也不会弹两次）。
 */
@Composable
internal fun StoragePermissionDialogHost() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var visibleBlock by remember { mutableStateOf<PlaybackBlock?>(null) }

    // repeatOnLifecycle(STARTED)：后台时停止收集（事件留在 store），回前台立刻拿到当前值再弹。
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            PlaybackBlockStore.blockFlow.collect { incoming ->
                val consumed = takeBlockForPrompt(
                    candidate = incoming,
                    allFilesAccessGranted = hasAllFilesAccess(),
                    appInForeground = true,
                    consume = PlaybackBlockStore::consume,
                ) ?: return@collect
                visibleBlock = consumed
            }
        }
    }

    visibleBlock ?: return
    AlertDialog(
        onDismissRequest = { visibleBlock = null },
        title = { Text(StoragePermissionDialogStrings.TITLE) },
        text = { Text(StoragePermissionDialogStrings.MESSAGE) },
        confirmButton = {
            TextButton(
                onClick = {
                    visibleBlock = null
                    AllFilesAccessLauncher.launch(context)
                },
            ) {
                Text(StoragePermissionDialogStrings.CONFIRM)
            }
        },
        dismissButton = {
            TextButton(onClick = { visibleBlock = null }) {
                Text(StoragePermissionDialogStrings.DISMISS)
            }
        },
    )
}
