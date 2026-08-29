package com.zjr.hesimusic.adb

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteException
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.util.Log
import androidx.sqlite.db.SimpleSQLiteQuery
import com.zjr.hesimusic.data.SmartViewSql
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 供 adb/Agent 直接操作应用数据库的接口（需求：不经 UI 管理数据库与 SQL 歌单）。
 *
 * 安全模型：provider 必须 exported 才能被 shell 访问，因此运行时校验调用方 UID，
 * 仅放行 应用自身 / shell(2000) / root(0)，设备上的其他应用一律 SecurityException。
 *
 * 全部操作经 [call] 分发，返回 Bundle：ok(Boolean) + result(String, JSON) / error(String)。
 * 用法见 docs/adb-api.md。
 */
class AdbDbProvider : ContentProvider() {

    private fun entry() = EntryPointAccessors.fromApplication(
        context!!.applicationContext,
        AdbDbEntryPoint::class.java
    )

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        checkCallerUid()
        return runCatching {
            runBlocking { withContext(Dispatchers.IO) { dispatch(method, arg, extras) } }
        }.fold(
            onSuccess = { json -> Bundle().apply { putBoolean("ok", true); putString("result", json.toString()) } },
            onFailure = { e ->
                Log.e(TAG, "method=$method failed", e)
                Bundle().apply {
                    putBoolean("ok", false)
                    putString("error", e.message ?: e.toString())
                }
            }
        )
    }

    private fun checkCallerUid() {
        val uid = Binder.getCallingUid()
        val allowed = uid == Process.myUid() || uid == Process.SHELL_UID || uid == Process.ROOT_UID
        if (!allowed) {
            throw SecurityException("AdbDbProvider 仅允许 shell/root/应用自身调用 (uid=$uid)")
        }
    }

    private suspend fun dispatch(method: String, arg: String?, extras: Bundle?): JSONObject {
        val entry = entry()
        return when (method) {
            METHOD_QUERY -> doQuery(entry, arg)
            METHOD_EXEC -> doExec(entry, arg)
            METHOD_CREATE_VIEW, METHOD_UPDATE_VIEW -> doUpsertView(entry, arg)
            METHOD_VALIDATE_VIEW -> doValidateView(entry, arg)
            METHOD_DELETE_VIEW -> doDeleteView(entry, arg)
            METHOD_LIST_VIEWS -> doListViews(entry)
            METHOD_SCHEMA -> doSchema(entry)
            METHOD_EXPORT -> doExport(entry)
            METHOD_IMPORT -> doImport(entry, arg)
            else -> error("未知 method: $method（支持: query/exec/create_view/validate_view/delete_view/list_views/schema/export/import）")
        }
    }

    // ---- query ----

    private suspend fun doQuery(entry: AdbDbEntryPoint, arg: String?): JSONObject {
        val sql = SmartViewSql.normalize(requireText(arg, "缺少 SQL（放 --arg）"))
        when (val v = SmartViewSql.validate(sql)) {
            is SmartViewSql.ValidationResult.Invalid -> error("SQL 校验失败: ${v.reason}")
            is SmartViewSql.ValidationResult.Valid -> Unit
        }
        val limited = "SELECT * FROM ($sql) LIMIT $QUERY_ROW_LIMIT"
        val rows = entry.appDatabase().openHelper.readableDatabase
            .query(SimpleSQLiteQuery(limited)).use { cursor -> cursorToJson(cursor) }
        return JSONObject().put("rows", rows).put("truncatedAt", QUERY_ROW_LIMIT)
    }

    private fun cursorToJson(cursor: Cursor): JSONArray {
        val rows = JSONArray()
        while (cursor.moveToNext()) {
            val row = JSONObject()
            for (i in 0 until cursor.columnCount) {
                val name = cursor.getColumnName(i)
                when (cursor.getType(i)) {
                    Cursor.FIELD_TYPE_NULL -> row.put(name, JSONObject.NULL)
                    Cursor.FIELD_TYPE_INTEGER -> row.put(name, cursor.getLong(i))
                    Cursor.FIELD_TYPE_FLOAT -> row.put(name, cursor.getDouble(i))
                    Cursor.FIELD_TYPE_BLOB -> row.put(name, "<blob ${cursor.getBlob(i).size} bytes>")
                    else -> row.put(name, cursor.getString(i) ?: JSONObject.NULL)
                }
            }
            rows.put(row)
        }
        return rows
    }

    // ---- exec ----

    private suspend fun doExec(entry: AdbDbEntryPoint, arg: String?): JSONObject {
        val sql = SmartViewSql.normalize(requireText(arg, "缺少 SQL（放 --arg）"))
        val leading = sql.trimStart().substringBefore(' ').lowercase()
        if (leading in setOf("select", "with", "pragma")) {
            error("exec 仅用于写操作，查询请用 query method")
        }
        val db = entry.appDatabase().openHelper.writableDatabase
        val result = JSONObject()
        when (leading) {
            "insert" -> {
                val id = db.compileStatement(sql).executeInsert()
                result.put("lastInsertRowId", id)
            }
            "update", "delete" -> {
                val changes = db.compileStatement(sql).executeUpdateDelete()
                result.put("changes", changes)
            }
            else -> {
                db.execSQL(sql)
                result.put("executed", true)
            }
        }
        return result
    }

    // ---- views ----

    private suspend fun doUpsertView(entry: AdbDbEntryPoint, arg: String?): JSONObject {
        val body = JSONObject(requireText(arg, "缺少参数 JSON（{\"name\":..., \"sql\":...}）"))
        val name = body.optString("name").trim()
        val sql = body.optString("sql")
        val view = entry.smartPlaylistRepository().upsertByName(name, sql)
        return viewToJson(view).put("ok", true)
    }

    private suspend fun doValidateView(entry: AdbDbEntryPoint, arg: String?): JSONObject {
        val body = JSONObject(requireText(arg, "缺少参数 JSON（{\"sql\":...}）"))
        val sql = body.optString("sql")
        val error = entry.smartPlaylistRepository().validateAgainstDatabase(sql)
        return JSONObject().put("valid", error == null).put("error", error ?: JSONObject.NULL)
    }

    private suspend fun doDeleteView(entry: AdbDbEntryPoint, arg: String?): JSONObject {
        val body = JSONObject(requireText(arg, "缺少参数 JSON（{\"id\":...} 或 {\"name\":...}）"))
        val repo = entry.smartPlaylistRepository()
        val deleted = when {
            body.has("id") -> {
                repo.deleteById(body.getLong("id"))
                1
            }
            body.has("name") -> {
                entry.appDatabase().smartPlaylistDao().deleteByName(body.getString("name"))
            }
            else -> error("需要 id 或 name 字段")
        }
        return JSONObject().put("deleted", deleted)
    }

    private suspend fun doListViews(entry: AdbDbEntryPoint): JSONObject {
        val views = JSONArray()
        entry.smartPlaylistRepository().getAllList().forEach { views.put(viewToJson(it)) }
        return JSONObject().put("views", views)
    }

    private fun viewToJson(view: com.zjr.hesimusic.data.model.SmartPlaylist): JSONObject =
        JSONObject()
            .put("id", view.id)
            .put("name", view.name)
            .put("createdAt", view.createdAt)
            .put("sql", view.sqlText)

    // ---- schema / backup ----

    private suspend fun doSchema(entry: AdbDbEntryPoint): JSONObject {
        val db = entry.appDatabase().openHelper.readableDatabase
        val tables = JSONArray()
        val tableNames = mutableListOf<String>()
        db.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'room_' ORDER BY name").use { c ->
            while (c.moveToNext()) tableNames.add(c.getString(0))
        }
        for (table in tableNames) {
            val columns = JSONArray()
            db.query("PRAGMA table_info(\"$table\")").use { c ->
                while (c.moveToNext()) {
                    columns.put(
                        JSONObject()
                            .put("name", c.getString(c.getColumnIndexOrThrow("name")))
                            .put("type", c.getString(c.getColumnIndexOrThrow("type")))
                            .put("notNull", c.getInt(c.getColumnIndexOrThrow("notnull")) == 1)
                            .put("pk", c.getInt(c.getColumnIndexOrThrow("pk")) > 0)
                    )
                }
            }
            val rowCount = db.query("SELECT COUNT(*) FROM \"$table\"").use { c ->
                if (c.moveToFirst()) c.getLong(0) else 0L
            }
            tables.put(JSONObject().put("table", table).put("rowCount", rowCount).put("columns", columns))
        }
        return JSONObject().put("tables", tables)
    }

    private suspend fun doExport(entry: AdbDbEntryPoint): JSONObject {
        val json = entry.backupRestoreManager().exportJson()
        val outFile = File(appContext().filesDir, EXPORT_FILE)
        outFile.writeText(json.toString(), Charsets.UTF_8)
        return JSONObject()
            .put("path", outFile.absolutePath)
            .put("bytes", outFile.length())
            .put("version", json.optInt("version"))
    }

    private suspend fun doImport(entry: AdbDbEntryPoint, arg: String?): JSONObject {
        val path = when {
            !arg.isNullOrBlank() -> arg.trim()
            else -> IMPORT_FILE
        }
        require(!path.contains("..") && !path.startsWith("/")) { "非法路径: $path" }
        val inFile = File(appContext().filesDir, path)
        require(inFile.isFile) { "导入文件不存在: $inFile（先用 run-as 放入）" }
        val summary = entry.backupRestoreManager().importJson(JSONObject(inFile.readText(Charsets.UTF_8)))
        return JSONObject()
            .put("songs", summary.songs)
            .put("playlists", summary.playlists)
            .put("playlistEntries", summary.playlistEntries)
            .put("favorites", summary.favorites)
            .put("hiddenSongs", summary.hiddenSongs)
            .put("smartPlaylists", summary.smartPlaylists)
            .put("logs", summary.logs)
    }

    private fun requireText(value: String?, message: String): String {
        require(!value.isNullOrBlank()) { message }
        return value
    }

    private fun appContext(): android.content.Context = context ?: error("Context 不可用")

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = throw UnsupportedOperationException("请使用 call()，见 docs/adb-api.md")

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("请使用 call()")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("请使用 call()")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("请使用 call()")

    companion object {
        private const val TAG = "AdbDbProvider"

        /** adb shell content call 的 arg 有长度限制，查询默认截断到 500 行。 */
        const val QUERY_ROW_LIMIT = 500

        const val METHOD_QUERY = "query"
        const val METHOD_EXEC = "exec"
        const val METHOD_CREATE_VIEW = "create_view"
        const val METHOD_UPDATE_VIEW = "update_view"
        const val METHOD_VALIDATE_VIEW = "validate_view"
        const val METHOD_DELETE_VIEW = "delete_view"
        const val METHOD_LIST_VIEWS = "list_views"
        const val METHOD_SCHEMA = "schema"
        const val METHOD_EXPORT = "export"
        const val METHOD_IMPORT = "import"

        const val EXPORT_FILE = "adb_export.json"
        const val IMPORT_FILE = "adb_restore.json"
    }
}
