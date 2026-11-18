package com.zjr.hesimusic.demo

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.util.Log

/**
 * demo 包启动器(仅在 src/demo,正式包不编译)。
 *
 * 采用 ContentProvider 的理由与 src/debug 的 DebugInitProvider 相同:provider.onCreate
 * 早于 Application.onCreate 且无需改动 main 的 HesiMusicApplication。这里只做两件事:
 * 1) 把 [DemoBootstrap.start] 创建的会话注册到 main 侧 [DemoEntryHooks](供 MainActivity 读取);
 * 2) 由 DemoBootstrap 在后台 IO 线程复制素材并写 songs/song_loop(Hilt 组件此时尚未初始化,
 *    内部会带重试地等待)。
 *
 * 任何异常都只记录日志,绝不影响 app 进程启动(MainActivity 会回退到正常 home)。
 */
class DemoBootstrapProvider : ContentProvider() {

    companion object {
        private const val TAG = "DemoBootstrap"
    }

    override fun onCreate(): Boolean {
        Log.i(TAG, "DemoBootstrapProvider.onCreate")
        val app = context?.applicationContext as? Application
        if (app == null) {
            Log.w(TAG, "no application context; demo bootstrap skipped")
            return true
        }
        return try {
            DemoEntryHooks.session = DemoBootstrap.start(app)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "demo bootstrap failed to start", t)
            true
        }
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
