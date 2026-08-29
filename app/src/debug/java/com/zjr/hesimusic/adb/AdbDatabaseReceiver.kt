package com.zjr.hesimusic.adb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.zjr.hesimusic.data.BackupRestoreManager
import com.zjr.hesimusic.data.BackupSummary
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import javax.inject.Inject

/**
 * Debug 构建专属：供 adb 驱动的数据库导入/导出接口。
 * 仅在 debug 源码集注册，release 包不含此组件。
 *
 * 导出: adb shell am broadcast -a com.zjr.hesimusic.adb.ACTION_EXPORT -n com.zjr.hesimusic/com.zjr.hesimusic.adb.AdbDatabaseReceiver
 *      结果写入 filesDir/adb_export.json，可通过 adb shell run-as com.zjr.hesimusic cat files/adb_export.json 取回
 * 导入: 先用 run-as 将 JSON 放入 filesDir，再广播 ACTION_IMPORT 并以 extra "path" 指定文件名（相对 filesDir，禁止 ".." 与绝对路径）
 */
@AndroidEntryPoint
class AdbDatabaseReceiver : BroadcastReceiver() {

    @Inject
    lateinit var backupRestoreManager: BackupRestoreManager

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    ACTION_EXPORT -> export(context)
                    ACTION_IMPORT -> import(context, intent)
                    else -> Log.w(TAG, "未知 action: ${intent.action}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "操作失败", e)
                writeResult(context, ok = false, message = e.message ?: e.toString(), summary = null)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun export(context: Context) {
        val json = backupRestoreManager.exportJson()
        val outFile = File(context.filesDir, EXPORT_FILE)
        outFile.writeText(json.toString(), Charsets.UTF_8)
        val summary = mapOf(
            "songs" to json.optJSONArray("songs")?.length(),
            "playlists" to json.optJSONArray("playlists")?.length(),
            "favorites" to json.optJSONArray("favorites")?.length(),
            "hiddenSongs" to json.optJSONArray("hiddenSongs")?.length()
        )
        Log.i(TAG, "导出完成: $outFile ($summary)")
        writeResult(context, ok = true, message = "导出完成: $outFile", summary = summary)
    }

    private suspend fun import(context: Context, intent: Intent) {
        val name = intent.getStringExtra(EXTRA_PATH) ?: IMPORT_FILE
        require(!name.contains("..") && !name.startsWith("/")) { "非法路径: $name" }
        val inFile = File(context.filesDir, name)
        require(inFile.isFile) { "导入文件不存在: $inFile" }
        val json = JSONObject(inFile.readText(Charsets.UTF_8))
        val summary = backupRestoreManager.importJson(json)
        Log.i(TAG, "还原完成: $summary")
        writeResult(context, ok = true, message = "还原完成", summary = summary.toLogMap())
    }

    private fun writeResult(context: Context, ok: Boolean, message: String, summary: Map<String, Any?>?) {
        runCatching {
            File(context.filesDir, RESULT_FILE).writeText(
                JSONObject().apply {
                    put("ok", ok)
                    put("message", message)
                    put("summary", JSONObject(summary ?: emptyMap<String, Any?>()))
                }.toString(),
                Charsets.UTF_8
            )
        }
    }

    private fun BackupSummary.toLogMap() = mapOf(
        "songs" to songs,
        "playlists" to playlists,
        "playlistEntries" to playlistEntries,
        "favorites" to favorites,
        "hiddenSongs" to hiddenSongs,
        "smartPlaylists" to smartPlaylists,
        "logs" to logs
    )

    companion object {
        private const val TAG = "AdbDatabase"
        const val ACTION_EXPORT = "com.zjr.hesimusic.adb.ACTION_EXPORT"
        const val ACTION_IMPORT = "com.zjr.hesimusic.adb.ACTION_IMPORT"
        const val EXTRA_PATH = "path"
        const val EXPORT_FILE = "adb_export.json"
        const val IMPORT_FILE = "adb_restore.json"
        const val RESULT_FILE = "adb_result.json"
    }
}
