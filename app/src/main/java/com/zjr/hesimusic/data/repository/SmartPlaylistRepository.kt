package com.zjr.hesimusic.data.repository

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.zjr.hesimusic.data.AppDatabase
import com.zjr.hesimusic.data.SmartViewSql
import com.zjr.hesimusic.data.model.SmartPlaylist
import com.zjr.hesimusic.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SQL 歌单（智能视图）仓库。
 * 视图的创建/修改仅通过 ADB 接口或备份导入进入，这里同时承担投影校验。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class SmartPlaylistRepository @Inject constructor(
    private val appDatabase: AppDatabase
) {
    private val dao get() = appDatabase.smartPlaylistDao()

    fun getAll(): Flow<List<SmartPlaylist>> = dao.getAll()

    suspend fun getById(id: Long): SmartPlaylist? = dao.getById(id)

    /**
     * 视图内容实时求值。 订阅 smart_playlists（视图定义变更触发重算），
     * 再经 @RawQuery 订阅 songs 等数据表（曲库/收藏变更触发重算）。
     * 视图被删除后发出空列表。
     */
    fun observeSongs(id: Long): Flow<List<Song>> =
        dao.observeById(id).flatMapLatest { playlist ->
            if (playlist == null) {
                flowOf(emptyList())
            } else {
                flow {
                    val error = validateAgainstDatabase(playlist.sqlText)
                    if (error != null) {
                        android.util.Log.e(TAG, "视图 ${playlist.name} SQL 无效: $error")
                        emit(emptyList())
                    } else {
                        emitAll(
                            appDatabase.songDao()
                                .observeSmartPlaylistQuery(SimpleSQLiteQuery(playlist.sqlText))
                        )
                    }
                }
            }
        }.flowOn(Dispatchers.IO)

    fun observeSongCount(id: Long): Flow<Int> =
        dao.observeById(id).flatMapLatest { playlist ->
            if (playlist == null) {
                flowOf(0)
            } else {
                flow {
                    val error = validateAgainstDatabase(playlist.sqlText)
                    val count = if (error != null) {
                        0
                    } else {
                        appDatabase.songDao()
                            .countSmartPlaylistQuery(SimpleSQLiteQuery(SmartViewSql.wrapCount(playlist.sqlText)))
                            .toInt()
                    }
                    emit(count)
                }
            }
        }.flowOn(Dispatchers.IO)

    /**
     * 校验视图 SQL 能在当前库上执行，且投影列与 songs 表一致（Room 的实体映射要求）。
     * 返回 null 表示通过，否则返回错误描述。
     */
    suspend fun validateAgainstDatabase(rawSql: String): String? = withContext(Dispatchers.IO) {
        when (val result = SmartViewSql.validate(rawSql)) {
            is SmartViewSql.ValidationResult.Invalid -> return@withContext result.reason
            is SmartViewSql.ValidationResult.Valid -> Unit
        }
        val sql = SmartViewSql.normalize(rawSql)
        try {
            appDatabase.withTransaction {
                val query = SimpleSQLiteQuery("SELECT * FROM ($sql) LIMIT 1")
                appDatabase.openHelper.readableDatabase.query(query).use { cursor ->
                    val actual = cursor.columnNames.toSet()
                    val expected = songsColumnNames
                    if (actual != expected) {
                        val missing = expected - actual
                        val extra = actual - expected
                        return@withTransaction buildString {
                            append("返回列与 songs 表不一致")
                            if (missing.isNotEmpty()) append("，缺少: $missing")
                            if (extra.isNotEmpty()) append("，多余: $extra")
                        }
                    }
                    null
                }
            }
        } catch (e: Exception) {
            return@withContext e.message ?: e.toString()
        }
    }

    /**
     * 按名称创建或更新视图（Agent 友好的幂等语义）。
     * 先校验后写入，校验失败抛 IllegalStateException。
     */
    suspend fun upsertByName(name: String, sqlText: String): SmartPlaylist {
        validateAgainstDatabase(sqlText)?.let { error("视图 SQL 无效: $it") }
        val trimmedName = name.trim()
        require(trimmedName.isNotEmpty()) { "视图名称不能为空" }
        return appDatabase.withTransaction {
            dao.deleteByName(trimmedName)
            val id = dao.insert(SmartPlaylist(name = trimmedName, sqlText = SmartViewSql.normalize(sqlText)))
            dao.getById(id) ?: error("视图写入失败")
        }
    }

    suspend fun deleteById(id: Long) = dao.deleteById(id)

    suspend fun rename(id: Long, name: String) {
        val trimmedName = name.trim()
        require(trimmedName.isNotEmpty()) { "歌单名称不能为空" }
        dao.renameById(id, trimmedName)
    }

    suspend fun deleteAll() = dao.deleteAll()

    suspend fun insertAll(playlists: List<SmartPlaylist>) = dao.insertAll(playlists)

    suspend fun getAllList(): List<SmartPlaylist> = dao.getAllList()

    private companion object {
        const val TAG = "SmartPlaylistRepo"

        /** songs 表的列名集合，用于投影校验。 */
        val songsColumnNames: Set<String> = setOf(
            "id", "title", "artist", "album", "filePath", "duration", "trackNumber",
            "year", "genre", "mimeType", "size", "dateAdded", "isCue", "cueFilePath",
            "startPosition", "endPosition", "titleInitial", "folderPath"
        )
    }
}
