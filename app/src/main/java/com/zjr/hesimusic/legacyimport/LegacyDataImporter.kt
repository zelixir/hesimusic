package com.zjr.hesimusic.legacyimport

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.room.withTransaction
import com.zjr.hesimusic.data.AppDatabase
import com.zjr.hesimusic.data.model.Playlist
import com.zjr.hesimusic.data.model.PlaylistEntry
import com.zjr.hesimusic.data.model.SmartPlaylist
import com.zjr.hesimusic.data.repository.SmartPlaylistRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「导入上一版数据」的执行器(任务 C / issue 00030)。
 *
 * 设计要点(全部有单测钉住,见 app/src/test/.../import):
 * 1. **不去碰上一版 app**:导入只读用户自己选的文件;上一版没有任何"导出按钮"改动,
 *    导出在 PC 侧 / 用上一版已有的 adb 数据库接口完成(见 docs/import-legacy-data.md);
 * 2. **按 (filePath, startPosition) 匹配歌曲**,沿用上一版 id → 本机 id 的重映射
 *    (两个 applicationId 的曲库 id 完全不同,CUE 音轨还共用同一个 filePath);
 * 3. **幂等**:第二次导入同一份文件,新增计数全为 0(已有循环记录默认不覆盖);
 * 4. **事务**:全部写库动作在一个 Room 事务里,任何异常整体回滚(设置写入在事务之后,
 *    属于 SharedPreferences,没有事务语义,失败只记日志);
 * 5. **导入前备份**:先 wal_checkpoint 再拷贝 `files/import-backup-<时间戳>.db`,只留最近 5 份;
 * 6. 预览与执行共用同一份计划([LegacyImportPlanner]),所以"预览说会加什么"就是"实际加了什么"。
 */
@Singleton
class LegacyDataImporter @Inject constructor(
    private val database: AppDatabase,
    private val smartPlaylistRepository: SmartPlaylistRepository,
    @ApplicationContext private val context: Context,
) {

    private val preferenceWriter = LegacyPreferenceWriter(context)

    /**
     * 测试用故障注入:在事务即将提交前调用一次。抛异常即可验证"部分失败整体回滚"。
     * 生产代码永远为 null。
     */
    @VisibleForTesting
    internal var beforeCommit: (() -> Unit)? = null

    /** 只算不写:给导入页显示"将新增/更新多少"。 */
    suspend fun preview(
        bundle: LegacyExportBundle,
        options: LegacyImportOptions = LegacyImportOptions(),
    ): LegacyImportPreview = withContext(Dispatchers.IO) {
        val plan = buildPlan(bundle, options)
        LegacyImportPreview(options = options, counts = plan.counts, warnings = plan.warnings)
    }

    /** 真正导入:备份 → 单事务写库 → 写设置。 */
    suspend fun import(
        bundle: LegacyExportBundle,
        options: LegacyImportOptions = LegacyImportOptions(),
    ): LegacyImportResult = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val plan = buildPlan(bundle, options)
        if (!plan.counts.hasWork) {
            Log.i(TAG, "导入计划为空,不做任何写操作")
            return@withContext LegacyImportResult(
                counts = plan.counts,
                backupPath = null,
                settingsApplied = 0,
                durationMs = System.currentTimeMillis() - startedAt,
                warnings = plan.warnings,
                isNoop = true,
            )
        }
        val backupPath = backupDatabaseBeforeImport()
        database.withTransaction { execute(plan) }
        val settingsApplied = if (plan.preferencesToApply.isEmpty()) {
            0
        } else {
            runCatching { preferenceWriter.apply(plan.preferencesToApply) }
                .onFailure { Log.w(TAG, "写入设置失败(数据库部分已提交)", it) }
                .getOrDefault(0)
        }
        val result = LegacyImportResult(
            counts = plan.counts,
            backupPath = backupPath,
            settingsApplied = settingsApplied,
            durationMs = System.currentTimeMillis() - startedAt,
            warnings = plan.warnings,
        )
        Log.i(
            TAG,
            "导入完成: songs+" + plan.counts.songsNew +
                " loops+" + plan.counts.loopsNew +
                "(覆盖 " + plan.counts.loopsOverwritten + ")" +
                " playlists+" + plan.counts.playlistsNew +
                " entries+" + plan.counts.entriesNew +
                " favorites+" + plan.counts.favoritesNew +
                " smart+" + plan.counts.smartPlaylistsNew +
                " settings=" + settingsApplied +
                " 用时 " + result.durationMs + "ms" +
                " 备份 " + (backupPath ?: "无"),
        )
        result
    }

    // ---- 计划 ----

    private suspend fun buildPlan(
        bundle: LegacyExportBundle,
        options: LegacyImportOptions,
    ): LegacyImportPlan {
        val snapshot = LegacyImportPlanner.snapshot(database)
        return LegacyImportPlanner.plan(
            bundle = bundle,
            options = options,
            snapshot = snapshot,
            validateSmartPlaylist = { sql -> smartPlaylistRepository.validateAgainstDatabase(sql) },
            preferenceAlreadyMatches = { fileName, entry -> preferenceWriter.alreadyMatches(fileName, entry) },
        )
    }

    // ---- 执行(必须在事务里调用) ----

    private suspend fun execute(plan: LegacyImportPlan) {
        val songIds = HashMap<Long, Long>()
        for ((legacyId, ref) in plan.songRefs) {
            if (ref is LegacySongRef.Existing) songIds[legacyId] = ref.songId
        }

        if (plan.songsToInsert.isNotEmpty()) {
            database.songDao().insertAll(plan.songsToInsert)
            // 自增 id 由 SQLite 分配,插入后按同一套键反查,保证 CUE 音轨(startPosition 不同)对得上。
            val afterInsert = LegacyImportPlanner.snapshot(database).songsByKey
            for ((legacyId, ref) in plan.songRefs) {
                if (ref is LegacySongRef.New) {
                    val songId = afterInsert[ref.key]
                        ?: error("歌曲写入后找不到(曲库可能被并发修改): " + ref.key.filePath)
                    songIds[legacyId] = songId
                }
            }
        }

        if (plan.loopsToUpsert.isNotEmpty()) {
            val loops = plan.loopsToUpsert.map { planned ->
                val songId = songIds[planned.legacySongId]
                    ?: error("循环记录找不到目标歌曲: legacyId=" + planned.legacySongId)
                planned.loop.copy(songId = songId)
            }
            database.songLoopDao().upsertAll(loops)
        }

        // 歌单:先按名字对齐本机已有歌单,再建新的;条目顺序按本机现有最大 order 继续追加。
        val playlistIds = HashMap<Long, Long>()
        if (plan.playlistRefs.isNotEmpty()) {
            val existingByName = LinkedHashMap<String, Long>()
            for (playlist in database.playlistDao().getAllPlaylistsList()) {
                existingByName.putIfAbsent(playlist.name, playlist.id)
            }
            for ((legacyId, ref) in plan.playlistRefs) {
                when (ref) {
                    is LegacyPlaylistRef.Reused -> {
                        playlistIds[legacyId] = existingByName[ref.name] ?: ref.playlistId
                    }
                    is LegacyPlaylistRef.Create -> {
                        val createdAt = ref.playlist.createdAt.takeIf { it > 0L }
                            ?: System.currentTimeMillis()
                        val id = database.playlistDao().insertPlaylist(
                            Playlist(name = ref.playlist.name, createdAt = createdAt)
                        )
                        playlistIds[legacyId] = id
                    }
                }
            }
        }

        if (plan.entriesToInsert.isNotEmpty()) {
            val nextOrder = HashMap<Long, Int>()
            for (entry in database.playlistDao().getAllPlaylistEntriesList()) {
                nextOrder[entry.playlistId] = maxOf(nextOrder[entry.playlistId] ?: -1, entry.order)
            }
            val rows = ArrayList<PlaylistEntry>(plan.entriesToInsert.size)
            for (entry in plan.entriesToInsert) {
                val playlistId = playlistIds[entry.legacyPlaylistId] ?: continue
                val songId = songIds[entry.legacySongId] ?: continue
                val order = (nextOrder[playlistId] ?: -1) + 1
                nextOrder[playlistId] = order
                rows += PlaylistEntry(playlistId = playlistId, songId = songId, order = order)
            }
            if (rows.isNotEmpty()) database.playlistDao().insertPlaylistEntries(rows)
        }

        if (plan.favoritesToInsert.isNotEmpty()) {
            database.favoriteDao().insertAll(plan.favoritesToInsert)
        }
        if (plan.hiddenToInsert.isNotEmpty()) {
            database.hiddenSongDao().insertAll(plan.hiddenToInsert)
        }
        if (plan.smartPlaylistsToInsert.isNotEmpty()) {
            val rows = plan.smartPlaylistsToInsert.map { smart ->
                SmartPlaylist(
                    name = smart.name,
                    createdAt = smart.createdAt.takeIf { it > 0L } ?: System.currentTimeMillis(),
                    sqlText = smart.sql,
                )
            }
            database.smartPlaylistDao().insertAll(rows)
        }

        beforeCommit?.invoke()
    }

    // ---- 导入前备份 ----

    /**
     * 把本机数据库拷到 `files/import-backup-<yyyyMMdd_HHmmss_SSS>.db`。
     *
     * 先 `PRAGMA wal_checkpoint(TRUNCATE)` 把 WAL 内容并回主库,再拷主库 → 备份是单个自包含文件;
     * checkpoint 失败(WAL 有活跃读者等)时退化为"主库 + wal/shm 一起拷",仍可用 sqlite 打开恢复。
     */
    private fun backupDatabaseBeforeImport(): String? {
        val source = context.getDatabasePath(DATABASE_NAME)
        if (!source.isFile) {
            Log.w(TAG, "数据库尚未落盘,跳过导入前备份: " + source.absolutePath)
            return null
        }
        val checkpointed = runCatching {
            database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
            true
        }.getOrElse {
            Log.w(TAG, "wal_checkpoint(TRUNCATE) 失败,改为一起备份 WAL", it)
            false
        }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val target = File(context.filesDir, "import-backup-" + timestamp + ".db")
        copyFile(source, target)
        if (!checkpointed) {
            copyFile(File(source.absolutePath + "-wal"), File(target.absolutePath + "-wal"))
            copyFile(File(source.absolutePath + "-shm"), File(target.absolutePath + "-shm"))
        }
        pruneBackups()
        Log.i(TAG, "导入前备份完成: " + target.absolutePath + "(" + target.length() + " bytes)")
        return target.absolutePath
    }

    private fun copyFile(source: File, target: File) {
        if (!source.isFile) return
        source.inputStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
        }
    }

    /** 只保留最近 [MAX_BACKUPS] 份,避免每次导入都在 filesDir 里堆一份 20MB 的库。 */
    private fun pruneBackups() {
        val all = context.filesDir.listFiles { file ->
            file.isFile && file.name.startsWith(BACKUP_PREFIX) && file.name.endsWith(".db")
        } ?: return
        all.sortedByDescending { it.name }.drop(MAX_BACKUPS).forEach { stale ->
            runCatching {
                stale.delete()
                File(stale.absolutePath + "-wal").delete()
                File(stale.absolutePath + "-shm").delete()
            }
        }
    }

    private companion object {
        const val TAG = "LegacyDataImporter"
        const val DATABASE_NAME = "hesimusic_db"
        const val BACKUP_PREFIX = "import-backup-"
        const val MAX_BACKUPS = 5
    }
}
