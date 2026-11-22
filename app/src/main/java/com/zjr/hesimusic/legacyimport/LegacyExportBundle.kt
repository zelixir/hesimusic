package com.zjr.hesimusic.legacyimport

import com.zjr.hesimusic.data.model.Favorite
import com.zjr.hesimusic.data.model.HiddenSong
import com.zjr.hesimusic.data.model.Song
import com.zjr.hesimusic.data.model.SongLoop
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「导入上一版数据」的数据模型与解析器(任务 C / issue 00030)。
 *
 * 契约:导入文件 = **上一版 app 的备份方言**(见 [com.zjr.hesimusic.data.BackupRestoreManager])+
 * 若干只读元信息字段(format/formatVersion/exportedAt/source/generator)。
 *
 * 为什么复用备份方言而不是另发明一套:
 * 1. 上一版 app 已有「备份数据库到 JSON」,同一份文件应该也能导入 v2(用户手上可能只有它);
 * 2. 方言里的 songs/favorites/playlists/playlistEntries/songLoops/preferences 字段名与语义
 *    已被 [com.zjr.hesimusic.data.BackupRestoreManager] 与单测长期钉住,解析风险最低;
 * 3. PC 侧导出脚本(tools/export-legacy-data.ps1)可以直接用 SQLite 读出同样的字段。
 *
 * 与备份方言的差异(全部是**可选**增量,旧读取器忽略即可):
 * - format = "hesi-legacy-export"、formatVersion = 1;
 * - exportedAt/source/generator:来源信息,仅用于 UI 展示;
 * - songLoops[].candidatesJson:与 v10 的 song_loop 表一致(可选)。
 *
 * 线程模型:纯函数,不做 I/O;由 [LegacyImportSourceReader] 负责取字节,这里只解析字符串。
 */

/** 导入过程可直接展示给用户的错误。 */
class LegacyImportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 歌曲的稳定身份。
 *
 * ⚠️ 不能用歌曲 id 匹配:v2 与上一版由各自的 applicationId 隔离,曲库各扫各的,
 * id 完全不同(同一台设备上同一首 CUE 音轨在两边 id 都可能不一样)。上一版里
 * **一条 CUE 文件被拆成多条歌曲**,它们 filePath 相同、startPosition 不同,
 * 因此必须用 (filePath, startPosition) 复合键。
 */
data class LegacySongKey(val filePath: String, val startPosition: Long) {

    companion object {
        fun of(song: Song): LegacySongKey = LegacySongKey(song.filePath, song.startPosition)
    }
}

/** 导出文件里的一首歌;legacyId 仅用于把循环记录/歌单条目关联到它。 */
data class LegacySong(val legacyId: Long, val song: Song) {
    val key: LegacySongKey get() = LegacySongKey.of(song)

    /** 落库时必须是自增主键(id=0),不能沿用上一版的 id。 */
    fun toInsertable(): Song = song.copy(id = 0)
}

data class LegacyPlaylist(val legacyId: Long, val name: String, val createdAt: Long)

data class LegacyPlaylistEntry(val legacyPlaylistId: Long, val legacySongId: Long, val order: Int)

data class LegacySmartPlaylist(val name: String, val createdAt: Long, val sql: String)

/** SharedPreferences 的一条键值(类型名与备份方言一致:string/boolean/int/long/float/string_set)。 */
data class LegacyPreferenceEntry(val key: String, val type: String, val value: Any?)

data class LegacyPreferenceFile(val name: String, val entries: List<LegacyPreferenceEntry>)

data class LegacyExportMeta(
    /** "hesi-legacy-export";上一版 app 自带的备份文件没有该字段(null)。 */
    val format: String?,
    val formatVersion: Int,
    /** 备份方言版本(上一版 app 为 4)。 */
    val backupVersion: Int,
    val exportedAt: Long?,
    val sourceAppId: String?,
    val sourceVersionName: String?,
    val sourceVersionCode: Long?,
    val sourceDatabaseVersion: Int?,
    val generator: String?,
)

/**
 * 一次导入的全部输入。
 *
 * 注意 [songLoops] / [playlistEntries] 里的 id 仍是**上一版**的 id:执行阶段通过
 * [LegacyImportPlanner] 把它们重映射到本机歌曲/歌单 id(见 [LegacyDataImporter])。
 */
data class LegacyExportBundle(
    val meta: LegacyExportMeta,
    val songs: List<LegacySong>,
    val songLoops: List<SongLoop>,
    val playlists: List<LegacyPlaylist>,
    val playlistEntries: List<LegacyPlaylistEntry>,
    val favorites: List<Favorite>,
    val hiddenSongs: List<HiddenSong>,
    val smartPlaylists: List<LegacySmartPlaylist>,
    val preferences: List<LegacyPreferenceFile>,
    /** 解析期间发现并跳过的坏记录(不阻断整体导入)。 */
    val warnings: List<String>,
) {

    val isEmpty: Boolean
        get() = songs.isEmpty() && songLoops.isEmpty() && playlists.isEmpty() &&
            playlistEntries.isEmpty() && favorites.isEmpty() && hiddenSongs.isEmpty() &&
            smartPlaylists.isEmpty()

    /** UI 上的「这份文件里有什么」。 */
    fun describeContents(): String = buildString {
        append("歌曲 ").append(songs.size)
        append(" · 循环记录 ").append(songLoops.size)
        append(" · 歌单 ").append(playlists.size).append("(").append(playlistEntries.size).append(" 条)")
        append(" · 收藏 ").append(favorites.size)
        append(" · 隐藏 ").append(hiddenSongs.size)
        append(" · SQL 歌单 ").append(smartPlaylists.size)
        if (preferences.isNotEmpty()) {
            append(" · 设置 ").append(preferences.sumOf { it.entries.size }).append(" 项")
        }
    }

    /** UI 上的「这份文件从哪来」。 */
    fun describeSource(): String {
        val parts = mutableListOf<String>()
        val appId = meta.sourceAppId ?: "com.zjr.hesimusic"
        val version = meta.sourceVersionName
        val code = meta.sourceVersionCode
        parts += buildString {
            append("来源 ").append(appId)
            if (version != null) {
                append(" ").append(version)
                if (code != null) append("(").append(code).append(")")
            }
        }
        meta.sourceDatabaseVersion?.let { parts += "数据库 v$it" }
        meta.exportedAt?.let { ts ->
            val text = runCatching { TIME_FORMAT.format(Date(ts)) }.getOrNull()
            if (text != null) parts += "导出于 $text"
        }
        meta.generator?.let { parts += it }
        if (meta.format == null) parts += "上一版 app 自带备份文件"
        return parts.joinToString(" · ")
    }

    private companion object {
        val TIME_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    }
}

/**
 * 解析导出文件。
 *
 * 宽容策略:单条记录坏掉只计入 warnings 并跳过;版本/格式不认识才整份失败。
 */
object LegacyExportParser {

    /** 本模块写出的格式标识。 */
    const val FORMAT = "hesi-legacy-export"

    /** 本模块写出的格式版本。 */
    const val FORMAT_VERSION = 1

    /** 能读的最高备份方言版本(与 BackupRestoreManager 同步:当前 4)。 */
    const val MAX_BACKUP_VERSION = 4

    private const val WARNING_LIMIT = 30

    fun parse(text: String): LegacyExportBundle {
        val trimmed = text.trim().removePrefix("\uFEFF").trim()
        if (trimmed.isEmpty()) throw LegacyImportException("文件是空的")
        val json = try {
            JSONObject(trimmed)
        } catch (e: JSONException) {
            throw LegacyImportException("不是合法的 JSON 文件: ${e.message}", e)
        }
        return parse(json)
    }

    fun parse(json: JSONObject): LegacyExportBundle {
        val warnings = WarningSink(WARNING_LIMIT)
        val format = json.optString("format", "").takeIf { it.isNotEmpty() && it != "null" }
        if (format != null && format != FORMAT) {
            throw LegacyImportException("不认识的文件格式: $format(期望 $FORMAT)")
        }
        val formatVersion = json.optInt("formatVersion", if (format != null) FORMAT_VERSION else 0)
        if (formatVersion > FORMAT_VERSION) {
            throw LegacyImportException("文件格式版本过新($formatVersion),请更新到更新版本的 Hesi v2")
        }
        val backupVersion = json.optInt("version", MAX_BACKUP_VERSION)
        if (backupVersion < 1) throw LegacyImportException("不支持的备份版本: $backupVersion")
        if (backupVersion > MAX_BACKUP_VERSION) {
            throw LegacyImportException("备份版本过新($backupVersion),当前版本最多支持 $MAX_BACKUP_VERSION")
        }

        val songs = parseSongs(json.optJSONArray("songs"), warnings)
        val songLoops = parseSongLoops(json.optJSONArray("songLoops"), warnings)
        val playlists = parsePlaylists(json.optJSONArray("playlists"), warnings)
        val playlistEntries = parsePlaylistEntries(json.optJSONArray("playlistEntries"), warnings)
        val favorites = parseFavorites(json.optJSONArray("favorites"), warnings)
        val hiddenSongs = parseHiddenSongs(json.optJSONArray("hiddenSongs"), warnings)
        val smartPlaylists = parseSmartPlaylists(json.optJSONArray("smartPlaylists"), warnings)
        val preferences = parsePreferences(json.optJSONObject("preferences"), warnings)

        val meta = LegacyExportMeta(
            format = format,
            formatVersion = formatVersion,
            backupVersion = backupVersion,
            exportedAt = json.optNullableLong("exportedAt"),
            sourceAppId = json.optNullableString("sourceAppId")
                ?: json.optJSONObject("source")?.optNullableString("applicationId"),
            sourceVersionName = json.optNullableString("sourceVersionName")
                ?: json.optJSONObject("source")?.optNullableString("versionName"),
            sourceVersionCode = json.optNullableLong("sourceVersionCode")
                ?: json.optJSONObject("source")?.optNullableLong("versionCode"),
            sourceDatabaseVersion = json.optJSONObject("source")?.optIntOrNull("databaseVersion"),
            generator = json.optNullableString("generator"),
        )
        val bundle = LegacyExportBundle(
            meta = meta,
            songs = songs,
            songLoops = songLoops,
            playlists = playlists,
            playlistEntries = playlistEntries,
            favorites = favorites,
            hiddenSongs = hiddenSongs,
            smartPlaylists = smartPlaylists,
            preferences = preferences,
            warnings = warnings.toList(),
        )
        if (bundle.isEmpty) {
            throw LegacyImportException("文件里没有可导入的数据(songs/songLoops/playlists/favorites 都是空的)")
        }
        // 循环记录与歌单条目都靠歌曲 id 关联;缺 songs 段就没法映射,提前给出可读错误。
        if (bundle.songs.isEmpty() && (bundle.songLoops.isNotEmpty() || bundle.playlistEntries.isNotEmpty())) {
            throw LegacyImportException(
                "文件缺少 songs 段,无法把循环记录/歌单条目映射到本机歌曲。" +
                    "请用 tools/export-legacy-data.ps1 重新导出(它会同时导出曲库)。"
            )
        }
        return bundle
    }

    // ---- 各段解析(逐条容错) ----

    private fun parseSongs(array: JSONArray?, warnings: WarningSink): List<LegacySong> {
        if (array == null) return emptyList()
        val out = ArrayList<LegacySong>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index)
            if (item == null) {
                warnings.add("songs[$index] 不是对象,已跳过")
                continue
            }
            val filePath = item.optNullableString("filePath")
            if (filePath.isNullOrBlank()) {
                warnings.add("songs[$index] 缺少 filePath,已跳过")
                continue
            }
            val title = item.optNullableString("title") ?: filePath.substringAfterLast('/')
            val song = Song(
                id = item.optLong("id", 0L),
                title = title,
                artist = item.optNullableString("artist").orEmpty(),
                album = item.optNullableString("album").orEmpty(),
                filePath = filePath,
                duration = item.optLong("duration", 0L),
                trackNumber = item.optInt("trackNumber", 0),
                year = item.optNullableString("year"),
                genre = item.optNullableString("genre"),
                mimeType = item.optNullableString("mimeType").orEmpty(),
                size = item.optLong("size", 0L),
                dateAdded = item.optLong("dateAdded", 0L),
                isCue = item.optBoolean("isCue", false),
                cueFilePath = item.optNullableString("cueFilePath"),
                startPosition = item.optLong("startPosition", 0L),
                endPosition = item.optLong("endPosition", -1L),
                titleInitial = item.optNullableString("titleInitial").orEmpty(),
                folderPath = item.optNullableString("folderPath").orEmpty(),
            )
            out += LegacySong(legacyId = song.id, song = song)
        }
        return out
    }

    private fun parseSongLoops(array: JSONArray?, warnings: WarningSink): List<SongLoop> {
        if (array == null) return emptyList()
        val out = ArrayList<SongLoop>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index)
            if (item == null) {
                warnings.add("songLoops[$index] 不是对象,已跳过")
                continue
            }
            val songId = item.optLong("songId", 0L)
            if (songId <= 0L) {
                warnings.add("songLoops[$index] 缺少 songId,已跳过")
                continue
            }
            val sampleRate = item.optInt("sampleRate", 0)
            if (sampleRate <= 0) {
                warnings.add("songLoops[$index] 采样率无效($sampleRate),已跳过")
                continue
            }
            val startSamples = item.optLong("loopStartSamples", -1L)
            val endSamples = item.optLong("loopEndSamples", -1L)
            if (startSamples < 0L || endSamples <= startSamples) {
                warnings.add("songLoops[$index] 循环点无效($startSamples..$endSamples),已跳过")
                continue
            }
            out += SongLoop(
                songId = songId,
                loopStartSamples = startSamples,
                loopEndSamples = endSamples,
                sampleRate = sampleRate,
                loopStartMs = item.optLong("loopStartMs", startSamples * 1000L / sampleRate),
                loopEndMs = item.optLong("loopEndMs", endSamples * 1000L / sampleRate),
                score = item.optDouble("score", 0.0),
                noteDistance = item.optDouble("noteDistance", 0.0),
                loudnessDifference = item.optDouble("loudnessDifference", 0.0),
                createdAt = item.optLong("createdAt", 0L),
                confirmed = item.optBoolean("confirmed", true),
                candidatesJson = item.optNullableString("candidatesJson"),
            )
        }
        return out
    }

    private fun parsePlaylists(array: JSONArray?, warnings: WarningSink): List<LegacyPlaylist> {
        if (array == null) return emptyList()
        val out = ArrayList<LegacyPlaylist>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val id = item.optLong("id", 0L)
            val name = item.optNullableString("name")?.trim().orEmpty()
            if (id <= 0L || name.isEmpty()) {
                warnings.add("playlists[$index] 缺少 id/name,已跳过")
                continue
            }
            out += LegacyPlaylist(legacyId = id, name = name, createdAt = item.optLong("createdAt", 0L))
        }
        return out
    }

    private fun parsePlaylistEntries(array: JSONArray?, warnings: WarningSink): List<LegacyPlaylistEntry> {
        if (array == null) return emptyList()
        val out = ArrayList<LegacyPlaylistEntry>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val playlistId = item.optLong("playlistId", 0L)
            val songId = item.optLong("songId", 0L)
            if (playlistId <= 0L || songId <= 0L) {
                warnings.add("playlistEntries[$index] 缺少 playlistId/songId,已跳过")
                continue
            }
            out += LegacyPlaylistEntry(
                legacyPlaylistId = playlistId,
                legacySongId = songId,
                order = item.optInt("order", index),
            )
        }
        return out
    }

    private fun parseFavorites(array: JSONArray?, warnings: WarningSink): List<Favorite> {
        if (array == null) return emptyList()
        val out = ArrayList<Favorite>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val filePath = item.optNullableString("filePath")
            if (filePath.isNullOrBlank()) {
                warnings.add("favorites[$index] 缺少 filePath,已跳过")
                continue
            }
            out += Favorite(
                filePath = filePath,
                startPosition = item.optLong("startPosition", 0L),
                dateAdded = item.optLong("dateAdded", 0L),
            )
        }
        return out
    }

    private fun parseHiddenSongs(array: JSONArray?, warnings: WarningSink): List<HiddenSong> {
        if (array == null) return emptyList()
        val out = ArrayList<HiddenSong>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val filePath = item.optNullableString("filePath")
            if (filePath.isNullOrBlank()) {
                warnings.add("hiddenSongs[$index] 缺少 filePath,已跳过")
                continue
            }
            out += HiddenSong(
                filePath = filePath,
                startPosition = item.optLong("startPosition", 0L),
                hiddenAt = item.optLong("hiddenAt", 0L),
            )
        }
        return out
    }

    private fun parseSmartPlaylists(array: JSONArray?, warnings: WarningSink): List<LegacySmartPlaylist> {
        if (array == null) return emptyList()
        val out = ArrayList<LegacySmartPlaylist>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val name = item.optNullableString("name")?.trim().orEmpty()
            val sql = item.optNullableString("sql") ?: item.optNullableString("sqlText")
            if (name.isEmpty() || sql.isNullOrBlank()) {
                warnings.add("smartPlaylists[$index] 缺少 name/sql,已跳过")
                continue
            }
            out += LegacySmartPlaylist(
                name = name,
                createdAt = item.optLong("createdAt", 0L),
                sql = sql,
            )
        }
        return out
    }

    private fun parsePreferences(json: JSONObject?, warnings: WarningSink): List<LegacyPreferenceFile> {
        if (json == null) return emptyList()
        val out = ArrayList<LegacyPreferenceFile>(json.length())
        for (fileName in json.keys()) {
            val array = json.optJSONArray(fileName) ?: continue
            val entries = ArrayList<LegacyPreferenceEntry>(array.length())
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val key = item.optNullableString("key")
                val type = item.optNullableString("type")
                if (key.isNullOrBlank() || type.isNullOrBlank()) {
                    warnings.add("preferences.$fileName[$index] 缺少 key/type,已跳过")
                    continue
                }
                val raw: Any? = when (type) {
                    "string_set" -> {
                        val set = item.optJSONArray("value")
                        if (set == null) emptyList<String>() else List(set.length()) { set.optString(it) }
                    }
                    else -> if (item.isNull("value")) null else item.opt("value")
                }
                entries += LegacyPreferenceEntry(key = key, type = type, value = raw)
            }
            out += LegacyPreferenceFile(name = fileName, entries = entries)
        }
        return out
    }

    // ---- JSON 读取小工具(Android org.json 的 optString 会把 JSON null 变成 "null") ----

    private fun JSONObject.optNullableString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        val value = optString(key, "")
        if (value.isEmpty() || value == "null") return null
        return value
    }

    private fun JSONObject.optNullableLong(key: String): Long? {
        if (!has(key) || isNull(key)) return null
        return when (val raw = opt(key)) {
            is Number -> raw.toLong()
            is String -> raw.toLongOrNull()
            else -> null
        }
    }

    private fun JSONObject.optIntOrNull(key: String): Int? {
        if (!has(key) || isNull(key)) return null
        return when (val raw = opt(key)) {
            is Number -> raw.toInt()
            is String -> raw.toIntOrNull()
            else -> null
        }
    }
}

/** 收集解析/导入期间的可读告警,超过上限只留一行汇总,避免 UI 被上千行刷爆。 */
internal class WarningSink(private val limit: Int) {
    private val items = ArrayList<String>()
    private var suppressed = 0

    fun add(message: String) {
        if (items.size < limit) items += message else suppressed++
    }

    fun toList(): List<String> {
        if (suppressed == 0) return items.toList()
        return items + "…另有 $suppressed 条同类问题未列出"
    }
}
