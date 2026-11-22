package com.zjr.hesimusic.legacyimport

import com.zjr.hesimusic.data.AppDatabase
import com.zjr.hesimusic.data.model.Favorite
import com.zjr.hesimusic.data.model.HiddenSong
import com.zjr.hesimusic.data.model.Song
import com.zjr.hesimusic.data.model.SongLoop

/**
 * 导入选项。
 *
 * 两个模式是需求里点名的:
 * - [LOOPS_ONLY]:「只导入循环记录」——不新增歌曲、不动歌单/收藏/设置;
 * - [EVERYTHING]:「全部导入」——歌曲(缺的补上)+ 循环记录 + 歌单 + 收藏 + 隐藏 + SQL 歌单 + 设置。
 */
enum class LegacyImportMode {
    LOOPS_ONLY,
    EVERYTHING,
}

data class LegacyImportOptions(
    val mode: LegacyImportMode = LegacyImportMode.EVERYTHING,
    /**
     * 已有循环记录是否覆盖。
     *
     * 默认 **false**:上一版的「已确认」结果不该悄悄盖掉本机已经确认过的结果
     * (也更符合幂等直觉——第二次导入什么都不改)。勾选后才用上一版的结果覆盖。
     */
    val overwriteExistingLoops: Boolean = false,
    /** 是否导入设置(仅 [LegacyImportMode.EVERYTHING] 生效)。 */
    val importSettings: Boolean = true,
)

/** 导入计划会做多少事(预览与执行共用同一份计算,保证「预览 = 实际」)。 */
data class LegacyImportCounts(
    /** 本机没有、将新增的歌曲数。 */
    val songsNew: Int = 0,
    /** 本机已有(同 filePath + startPosition)、直接复用的歌曲数。 */
    val songsMatched: Int = 0,
    /** 上一版有、本机没有、但当前模式不插入的歌曲数(LOOPS_ONLY 时是“本机缺歌”)。 */
    val songsNotInserted: Int = 0,
    /** 将写入(新增)的循环记录数。 */
    val loopsNew: Int = 0,
    /** 将覆盖的已有循环记录数(仅在勾选「覆盖」时非 0)。 */
    val loopsOverwritten: Int = 0,
    /** 本机已有、按选项保留不动的循环记录数。 */
    val loopsKeptExisting: Int = 0,
    /** 上一版有循环记录、但对应歌曲在本机不存在 → 跳过并报告。 */
    val loopsSongMissing: Int = 0,
    /** 将新建的静态歌单数(按名称匹配,重名视为同一个歌单)。 */
    val playlistsNew: Int = 0,
    /** 按名称匹配到本机已有歌单、复用的数量。 */
    val playlistsReused: Int = 0,
    /** 将新增的歌单条目数。 */
    val entriesNew: Int = 0,
    /** 歌单里已经有的同一首歌 → 跳过(幂等)。 */
    val entriesDuplicate: Int = 0,
    /** 条目对应的歌曲在本机不存在 → 跳过并报告。 */
    val entriesSongMissing: Int = 0,
    val favoritesNew: Int = 0,
    val favoritesKept: Int = 0,
    val hiddenNew: Int = 0,
    val hiddenKept: Int = 0,
    val smartPlaylistsNew: Int = 0,
    val smartPlaylistsKept: Int = 0,
    /** SQL 歌单在本机曲库上校验失败 → 跳过并报告。 */
    val smartPlaylistsInvalid: Int = 0,
    /** 将写入的设置文件数。 */
    val settingsFiles: Int = 0,
    /** 将写入的设置项数(已按白名单过滤)。 */
    val settingsEntries: Int = 0,
) {

    val hasWork: Boolean
        get() = songsNew > 0 || loopsNew > 0 || loopsOverwritten > 0 || playlistsNew > 0 ||
            entriesNew > 0 || favoritesNew > 0 || hiddenNew > 0 || smartPlaylistsNew > 0 ||
            settingsEntries > 0

    /** UI/日志用的可读明细(按模式只显示相关行)。 */
    fun describe(options: LegacyImportOptions): List<String> {
        val lines = mutableListOf<String>()
        if (options.mode == LegacyImportMode.EVERYTHING) {
            lines += buildString {
                append("歌曲:新增 ").append(songsNew).append(" · 复用 ").append(songsMatched)
                if (songsNotInserted > 0) append(" · 本机缺 ").append(songsNotInserted)
            }
            lines += buildString {
                append("歌单:新建 ").append(playlistsNew).append(" · 复用 ").append(playlistsReused)
                append(" · 新增条目 ").append(entriesNew)
                if (entriesDuplicate > 0) append(" · 已有条目跳过 ").append(entriesDuplicate)
                if (entriesSongMissing > 0) append(" · 歌曲缺失跳过 ").append(entriesSongMissing)
            }
            lines += buildString {
                append("收藏:新增 ").append(favoritesNew)
                if (favoritesKept > 0) append(" · 已有 ").append(favoritesKept)
            }
            lines += buildString {
                append("隐藏:新增 ").append(hiddenNew)
                if (hiddenKept > 0) append(" · 已有 ").append(hiddenKept)
            }
            lines += buildString {
                append("SQL 歌单:新增 ").append(smartPlaylistsNew)
                if (smartPlaylistsKept > 0) append(" · 已有 ").append(smartPlaylistsKept)
                if (smartPlaylistsInvalid > 0) append(" · 无效 ").append(smartPlaylistsInvalid)
            }
        } else {
            lines += "只导入循环记录:不会新增歌曲、不改歌单/收藏/设置"
            if (songsNotInserted > 0) {
                lines += "本机缺少 " + songsNotInserted + " 首歌(其中带循环记录的会被跳过)"
            }
        }
        lines += buildString {
            append("循环记录:新增 ").append(loopsNew)
            if (loopsOverwritten > 0) append(" · 覆盖 ").append(loopsOverwritten)
            if (loopsKeptExisting > 0) append(" · 已存在保留 ").append(loopsKeptExisting)
            if (loopsSongMissing > 0) append(" · 歌曲不存在跳过 ").append(loopsSongMissing)
        }
        if (options.mode == LegacyImportMode.EVERYTHING && options.importSettings) {
            lines += "设置:" + settingsFiles + " 个文件 / " + settingsEntries + " 项"
        }
        return lines
    }
}

/** 预览结果(纯计算,不写库)。 */
data class LegacyImportPreview(
    val options: LegacyImportOptions,
    val counts: LegacyImportCounts,
    val warnings: List<String>,
) {
    val hasWork: Boolean get() = counts.hasWork
    fun summaryLines(): List<String> = counts.describe(options)
}

/** 导入执行结果。 */
data class LegacyImportResult(
    val counts: LegacyImportCounts,
    /** 导入前自动备份的本机数据库路径(没有可备份的数据时为 null)。 */
    val backupPath: String?,
    val settingsApplied: Int,
    val durationMs: Long,
    val warnings: List<String>,
    val isNoop: Boolean = false,
) {
    fun summaryLines(options: LegacyImportOptions): List<String> = counts.describe(options)
}

// ---------------------------------------------------------------------------
// 计划(内部):预览与执行共用,执行阶段只需要按计划做 I/O
// ---------------------------------------------------------------------------

/** 上一版歌曲 id 在本机的落点。 */
internal sealed interface LegacySongRef {
    /** 本机已有同一 (filePath, startPosition) 的歌。 */
    data class Existing(val songId: Long) : LegacySongRef

    /** 将新建,落库后按 key 反查 id。 */
    data class New(val key: LegacySongKey) : LegacySongRef

    /** 本机没有,且当前模式不插入(或上一版文件本身缺这首歌)。 */
    object Missing : LegacySongRef
}

/** 上一版歌单 id 在本机的落点。 */
internal sealed interface LegacyPlaylistRef {
    /** 按名称匹配到本机已有歌单(name 留作兜底:计划后该歌单若被删,执行阶段按名字重建)。 */
    data class Reused(val playlistId: Long, val name: String) : LegacyPlaylistRef

    /** 将新建。 */
    data class Create(val playlist: LegacyPlaylist) : LegacyPlaylistRef
}

/** 本机当前数据快照(导入前一次性读取,避免在事务里反复全表扫)。 */
internal data class LegacyImportSnapshot(
    val songsByKey: Map<LegacySongKey, Long>,
    val existingLoopSongIds: Set<Long>,
    val playlistsByName: Map<String, Long>,
    /** 歌单 id → 已有的歌曲 id 列表(判重用)。 */
    val entriesByPlaylist: Map<Long, List<Long>>,
    val favoriteKeys: Set<LegacySongKey>,
    val hiddenKeys: Set<LegacySongKey>,
    val smartPlaylistNames: Set<String>,
)

/** 循环记录 + 它指向的**上一版**歌曲 id(执行时换成 v2 的 songId)。 */
internal data class PlannedLoop(val legacySongId: Long, val loop: SongLoop)

internal data class LegacyImportPlan(
    val options: LegacyImportOptions,
    val counts: LegacyImportCounts,
    val songRefs: Map<Long, LegacySongRef>,
    val songsToInsert: List<Song>,
    val loopsToUpsert: List<PlannedLoop>,
    val playlistsToCreate: List<LegacyPlaylist>,
    val playlistRefs: Map<Long, LegacyPlaylistRef>,
    val entriesToInsert: List<LegacyPlaylistEntry>,
    val favoritesToInsert: List<Favorite>,
    val hiddenToInsert: List<HiddenSong>,
    val smartPlaylistsToInsert: List<LegacySmartPlaylist>,
    val preferencesToApply: List<LegacyPreferenceFile>,
    val warnings: List<String>,
)

/** 把导出内容与本机现状对撞成一份可执行计划(纯函数,只通过 [snapshot] 读库)。 */
internal object LegacyImportPlanner {

    suspend fun snapshot(database: AppDatabase): LegacyImportSnapshot {
        val songsByKey = LinkedHashMap<LegacySongKey, Long>()
        for (song in database.songDao().getAllSongsList()) {
            // CUE 音轨同 filePath 不同 startPosition,必须用复合键;重复行取第一条。
            songsByKey.putIfAbsent(LegacySongKey.of(song), song.id)
        }
        val loops = database.songLoopDao().getAll().map { it.songId }.toHashSet()
        val playlistsByName = LinkedHashMap<String, Long>()
        for (playlist in database.playlistDao().getAllPlaylistsList()) {
            playlistsByName.putIfAbsent(playlist.name, playlist.id)
        }
        val entriesByPlaylist = HashMap<Long, MutableList<Long>>()
        for (entry in database.playlistDao().getAllPlaylistEntriesList()) {
            entriesByPlaylist.getOrPut(entry.playlistId) { ArrayList() } += entry.songId
        }
        val favoriteKeys = database.favoriteDao().getAllFavoritesList()
            .map { LegacySongKey(it.filePath, it.startPosition) }
            .toHashSet()
        val hiddenKeys = database.hiddenSongDao().getAllHiddenSongsList()
            .map { LegacySongKey(it.filePath, it.startPosition) }
            .toHashSet()
        val smartNames = database.smartPlaylistDao().getAllList().map { it.name }.toHashSet()
        return LegacyImportSnapshot(
            songsByKey = songsByKey,
            existingLoopSongIds = loops,
            playlistsByName = playlistsByName,
            entriesByPlaylist = entriesByPlaylist,
            favoriteKeys = favoriteKeys,
            hiddenKeys = hiddenKeys,
            smartPlaylistNames = smartNames,
        )
    }

    /**
     * @param validateSmartPlaylist SQL 歌单校验(返回 null 表示通过);由
     * [com.zjr.hesimusic.data.repository.SmartPlaylistRepository] 提供。
     */
    suspend fun plan(
        bundle: LegacyExportBundle,
        options: LegacyImportOptions,
        snapshot: LegacyImportSnapshot,
        validateSmartPlaylist: suspend (String) -> String?,
        preferenceAlreadyMatches: (String, LegacyPreferenceEntry) -> Boolean = { _, _ -> false },
    ): LegacyImportPlan {
        val warnings = WarningSink(30)
        bundle.warnings.forEach { warnings.add(it) }

        val every = options.mode == LegacyImportMode.EVERYTHING

        // ---- 歌曲 ----
        val songRefs = LinkedHashMap<Long, LegacySongRef>()
        val songsToInsert = LinkedHashMap<LegacySongKey, Song>()
        var songsNew = 0
        var songsMatched = 0
        var songsNotInserted = 0
        for (record in bundle.songs) {
            val key = record.key
            val existingId = snapshot.songsByKey[key]
            val ref: LegacySongRef = when {
                existingId != null -> {
                    songsMatched++
                    LegacySongRef.Existing(existingId)
                }
                every -> {
                    if (songsToInsert.putIfAbsent(key, record.toInsertable()) == null) songsNew++
                    LegacySongRef.New(key)
                }
                else -> {
                    songsNotInserted++
                    LegacySongRef.Missing
                }
            }
            if (record.legacyId > 0L) songRefs[record.legacyId] = ref
        }

        // ---- 循环记录 ----
        val loopsToUpsert = ArrayList<PlannedLoop>()
        var loopsNew = 0
        var loopsOverwritten = 0
        var loopsKeptExisting = 0
        var loopsSongMissing = 0
        val loopSongsPlanned = HashSet<Long>()
        for (loop in bundle.songLoops) {
            val ref = songRefs[loop.songId] ?: LegacySongRef.Missing
            if (ref is LegacySongRef.Missing) {
                loopsSongMissing++
                if (loopsSongMissing <= 5) {
                    warnings.add("循环记录跳过:上一版歌曲 id=${loop.songId} 在本机不存在(先扫描曲库再导入)")
                }
                continue
            }
            if (!loopSongsPlanned.add(loop.songId)) continue // 同一首歌多条结果,取第一条
            val existingSongId = (ref as? LegacySongRef.Existing)?.songId
            val alreadyAnalysed = existingSongId != null && existingSongId in snapshot.existingLoopSongIds
            when {
                !alreadyAnalysed -> {
                    loopsNew++
                    loopsToUpsert += PlannedLoop(loop.songId, loop)
                }
                options.overwriteExistingLoops -> {
                    loopsOverwritten++
                    loopsToUpsert += PlannedLoop(loop.songId, loop)
                }
                else -> loopsKeptExisting++
            }
        }
        if (loopsSongMissing > 5) {
            warnings.add("…另有 ${loopsSongMissing - 5} 条循环记录的歌曲在本机不存在")
        }

        // ---- 歌单 ----
        val playlistRefs = LinkedHashMap<Long, LegacyPlaylistRef>()
        val playlistsToCreate = ArrayList<LegacyPlaylist>()
        var playlistsNew = 0
        var playlistsReused = 0
        if (every) {
            for (playlist in bundle.playlists) {
                val existing = snapshot.playlistsByName[playlist.name]
                if (existing != null) {
                    playlistsReused++
                    playlistRefs[playlist.legacyId] = LegacyPlaylistRef.Reused(existing, playlist.name)
                } else {
                    playlistsNew++
                    playlistsToCreate += playlist
                    playlistRefs[playlist.legacyId] = LegacyPlaylistRef.Create(playlist)
                }
            }
        }

        // ---- 歌单条目 ----
        val entriesToInsert = ArrayList<LegacyPlaylistEntry>()
        var entriesNew = 0
        var entriesDuplicate = 0
        var entriesSongMissing = 0
        if (every) {
            val seen = HashSet<Pair<Long, Long>>()
            for (entry in bundle.playlistEntries) {
                val playlistRef = playlistRefs[entry.legacyPlaylistId] ?: continue
                val songRef = songRefs[entry.legacySongId]
                if (songRef == null || songRef is LegacySongRef.Missing) {
                    entriesSongMissing++
                    continue
                }
                val alreadyInPlaylist = when {
                    playlistRef is LegacyPlaylistRef.Reused && songRef is LegacySongRef.Existing ->
                        songRef.songId in snapshot.entriesByPlaylist[playlistRef.playlistId].orEmpty()
                    else -> false
                }
                if (alreadyInPlaylist || !seen.add(entry.legacyPlaylistId to entry.legacySongId)) {
                    entriesDuplicate++
                    continue
                }
                entriesNew++
                entriesToInsert += entry
            }
        }

        // ---- 收藏 / 隐藏 ----
        val favoritesToInsert = ArrayList<Favorite>()
        var favoritesNew = 0
        var favoritesKept = 0
        val hiddenToInsert = ArrayList<HiddenSong>()
        var hiddenNew = 0
        var hiddenKept = 0
        if (every) {
            for (favorite in bundle.favorites) {
                if (LegacySongKey(favorite.filePath, favorite.startPosition) in snapshot.favoriteKeys) {
                    favoritesKept++
                } else {
                    favoritesNew++
                    favoritesToInsert += favorite
                }
            }
            for (hidden in bundle.hiddenSongs) {
                if (LegacySongKey(hidden.filePath, hidden.startPosition) in snapshot.hiddenKeys) {
                    hiddenKept++
                } else {
                    hiddenNew++
                    hiddenToInsert += hidden
                }
            }
        }

        // ---- SQL 歌单 ----
        val smartToInsert = ArrayList<LegacySmartPlaylist>()
        var smartNew = 0
        var smartKept = 0
        var smartInvalid = 0
        if (every) {
            for (smart in bundle.smartPlaylists) {
                if (smart.name in snapshot.smartPlaylistNames) {
                    smartKept++
                    continue
                }
                val error = validateSmartPlaylist(smart.sql)
                if (error != null) {
                    smartInvalid++
                    warnings.add("SQL 歌单「${smart.name}」校验失败,已跳过: $error")
                    continue
                }
                smartNew++
                smartToInsert += smart
            }
        }

        // ---- 设置 ----
        // 只保留"本机值不同"的项:重复导入同一份文件时设置计数归零,整次导入可判为无操作。
        val preferencesToApply = if (every && options.importSettings) {
            bundle.preferences.mapNotNull { file ->
                val entries = LegacyPreferencePolicy.filter(file)
                    .filterNot { entry -> preferenceAlreadyMatches(file.name, entry) }
                if (entries.isEmpty()) null else file.copy(entries = entries)
            }
        } else {
            emptyList()
        }

        val counts = LegacyImportCounts(
            songsNew = songsNew,
            songsMatched = songsMatched,
            songsNotInserted = songsNotInserted,
            loopsNew = loopsNew,
            loopsOverwritten = loopsOverwritten,
            loopsKeptExisting = loopsKeptExisting,
            loopsSongMissing = loopsSongMissing,
            playlistsNew = playlistsNew,
            playlistsReused = playlistsReused,
            entriesNew = entriesNew,
            entriesDuplicate = entriesDuplicate,
            entriesSongMissing = entriesSongMissing,
            favoritesNew = favoritesNew,
            favoritesKept = favoritesKept,
            hiddenNew = hiddenNew,
            hiddenKept = hiddenKept,
            smartPlaylistsNew = smartNew,
            smartPlaylistsKept = smartKept,
            smartPlaylistsInvalid = smartInvalid,
            settingsFiles = preferencesToApply.size,
            settingsEntries = preferencesToApply.sumOf { it.entries.size },
        )
        if (every && entriesSongMissing > 0) {
            warnings.add("$entriesSongMissing 条歌单条目的歌曲在本机不存在,已跳过(先扫描曲库再导入)")
        }
        return LegacyImportPlan(
            options = options,
            counts = counts,
            songRefs = songRefs,
            songsToInsert = songsToInsert.values.toList(),
            loopsToUpsert = loopsToUpsert,
            playlistsToCreate = playlistsToCreate,
            playlistRefs = playlistRefs,
            entriesToInsert = entriesToInsert,
            favoritesToInsert = favoritesToInsert,
            hiddenToInsert = hiddenToInsert,
            smartPlaylistsToInsert = smartToInsert,
            preferencesToApply = preferencesToApply,
            warnings = warnings.toList(),
        )
    }
}
