package com.zjr.hesimusic.debug

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ApplicationInfo
import android.database.Cursor
import android.net.Uri
import android.util.Log
import com.zjr.hesimusic.ui.player.state.PlayerPageShadowGate

/**
 * debug 构建专属启动器(仅存在于 src/debug,release 源集不编译)。
 *
 * 为什么用 ContentProvider:无需修改 main 的 HesiMusicApplication/MainActivity。
 * 系统在 app 进程启动时会实例化所有已注册 provider,这里只做一件事:
 * 起 LoopDebugServer 的 daemon 监听线程(不触碰 Hilt / DB,避免拖慢启动)。
 *
 * 注意:provider 的 onCreate 早于 Application.onCreate,所以服务端对 Hilt 依赖做懒解析
 * (首次请求时 EntryPointAccessors.fromApplication,带 3s 重试)。
 */
class DebugInitProvider : ContentProvider() {

    companion object {
        private const val TAG = "LoopDebug"
    }

    override fun onCreate(): Boolean {
        val app = context?.applicationContext ?: return true
        try {
            val debuggable = (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
            if (!debuggable) {
                Log.w(TAG, "debug server skipped: app is not debuggable")
                return true
            }
            // 只在 debug 进程启用播放页状态机影子双跑（只观测，不执行 effect）。
            PlayerPageShadowGate.enabled = true
            LoopDebugServer.start(app)
        } catch (t: Throwable) {
            // 启动失败绝不影响 app 进程
            Log.e(TAG, "debug server start failed", t)
        }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
