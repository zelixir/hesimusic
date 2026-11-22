package com.zjr.hesimusic.legacyimport

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** SAF 目录里的一个候选导出文件。 */
data class LegacyImportCandidate(
    val uri: Uri,
    val displayName: String,
    /** 形如 `Download/hessi-import/20261122-163000/legacy-export.json`,用于排序与展示。 */
    val pathLabel: String,
    val sizeBytes: Long?,
)

/**
 * 从 SAF(用户选的目录/文件)或 `file://` 路径读出导出 JSON。
 *
 * 为什么要支持"选目录":PC 侧脚本的落地约定是
 * `/sdcard/Download/hessi-import/<时间戳>/legacy-export.json`(见 docs/import-legacy-data.md),
 * 用户直接选 `hessi-import` 或某个时间戳目录都行,这里会自己找出最新的那份 JSON。
 *
 * 不引入 androidx.documentfile:直接走 DocumentsContract 查询,少一个依赖、行为也更可控。
 */
@Singleton
class LegacyImportSourceReader @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** 解析一份导出文件(SAF uri / file:// uri 都行)。 */
    suspend fun read(uri: Uri): LegacyExportBundle = withContext(Dispatchers.IO) {
        LegacyExportParser.parse(readText(uri))
    }

    /** 解析一个本机绝对路径(adb `am start -d file://...` 或调试用)。 */
    suspend fun readFromPath(path: String): LegacyExportBundle = withContext(Dispatchers.IO) {
        val file = File(path)
        if (!file.isFile) throw LegacyImportException("文件不存在: " + path)
        LegacyExportParser.parse(file.readText(Charsets.UTF_8))
    }

    suspend fun readText(uri: Uri): String = withContext(Dispatchers.IO) {
        when (uri.scheme?.lowercase()) {
            "file" -> {
                val path = uri.path ?: throw LegacyImportException("无法解析文件路径: " + uri)
                val file = File(path)
                if (!file.isFile) throw LegacyImportException("文件不存在: " + path)
                file.readText(Charsets.UTF_8)
            }
            else -> {
                val text = try {
                    context.contentResolver.openInputStream(uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    }
                } catch (e: SecurityException) {
                    throw LegacyImportException("没有读取该文件的权限,请重新选择: " + e.message, e)
                }
                text ?: throw LegacyImportException("无法打开文件(可能是云盘/虚拟文件,请先下载到本机)")
            }
        }
    }

    /** 记下目录的持久读权限(重启后不用再选一次)。 */
    fun persistReadPermission(treeUri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }.onFailure { Log.w(TAG, "takePersistableUriPermission failed for " + treeUri, it) }
    }

    /**
     * 列出目录里的候选 JSON:先看目录本身,再看**下一层子目录**
     * (导出布局是 `hessi-import/<时间戳>/legacy-export.json`)。
     * 结果按文档路径倒序(时间戳目录名天然有序),最新导出的排最前面。
     */
    suspend fun listCandidates(treeUri: Uri): List<LegacyImportCandidate> = withContext(Dispatchers.IO) {
        val rootDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
        val direct = listChildren(treeUri, rootDocumentId)
        val result = ArrayList<LegacyImportCandidate>()
        result += toCandidates(treeUri, direct)
        for (directory in direct.filter { it.isDirectory }) {
            result += toCandidates(treeUri, listChildren(treeUri, directory.documentId))
        }
        result.sortedByDescending { it.pathLabel }
    }

    /** 目录里最新的一份导出(最常见的用法)。 */
    suspend fun findNewestBundle(treeUri: Uri): LegacyImportCandidate? = listCandidates(treeUri).firstOrNull()

    /** 单个 JSON 文件的显示名(UI 用)。 */
    fun displayName(uri: Uri): String = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment ?: uri.toString()

    private data class Child(
        val documentId: String,
        val displayName: String,
        val isDirectory: Boolean,
        val isJson: Boolean,
        val sizeBytes: Long?,
    )

    private fun toCandidates(treeUri: Uri, children: List<Child>): List<LegacyImportCandidate> =
        children.filter { it.isJson }.map { child ->
            LegacyImportCandidate(
                uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, child.documentId),
                displayName = child.displayName,
                pathLabel = child.documentId.substringAfter(':'),
                sizeBytes = child.sizeBytes,
            )
        }

    private fun listChildren(treeUri: Uri, parentDocumentId: String): List<Child> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        val children = ArrayList<Child>()
        try {
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val documentId = cursor.getString(0) ?: continue
                    val displayName = cursor.getString(1) ?: continue
                    val mimeType = cursor.getString(2) ?: ""
                    val size = if (cursor.isNull(3)) null else cursor.getLong(3)
                    val isDirectory = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
                    val isJson = !isDirectory && displayName.endsWith(".json", ignoreCase = true)
                    children += Child(
                        documentId = documentId,
                        displayName = displayName,
                        isDirectory = isDirectory,
                        isJson = isJson,
                        sizeBytes = size,
                    )
                }
            }
        } catch (e: SecurityException) {
            throw LegacyImportException("没有读取该目录的权限,请重新选择: " + e.message, e)
        }
        return children
    }

    private companion object {
        const val TAG = "LegacyImportSource"
    }
}
