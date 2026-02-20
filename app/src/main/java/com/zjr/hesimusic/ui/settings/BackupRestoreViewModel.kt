package com.zjr.hesimusic.ui.settings

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.zjr.hesimusic.data.AppDatabase
import com.zjr.hesimusic.data.model.Favorite
import com.zjr.hesimusic.data.model.HiddenSong
import com.zjr.hesimusic.data.model.LogEntry
import com.zjr.hesimusic.data.model.Playlist
import com.zjr.hesimusic.data.model.PlaylistEntry
import com.zjr.hesimusic.data.model.Song
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject

@HiltViewModel
class BackupRestoreViewModel @Inject constructor(
    private val appDatabase: AppDatabase,
    @ApplicationContext private val context: Context
) : ViewModel() {
    private val preferenceFileNames = listOf("playback_prefs", "scan_prefs")

    private val _statusMessage = MutableStateFlow("请选择备份或还原操作")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    fun backupDatabase(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                val backupJson = withContext(Dispatchers.IO) {
                    JSONObject().apply {
                        put("version", 2)
                        put("songs", songsToJson(appDatabase.songDao().getAllSongsList()))
                        put("favorites", favoritesToJson(appDatabase.favoriteDao().getAllFavoritesList()))
                        put("logs", logsToJson(appDatabase.logDao().getAllLogsList()))
                        put("hiddenSongs", hiddenSongsToJson(appDatabase.hiddenSongDao().getAllHiddenSongsList()))
                        put("playlists", playlistsToJson(appDatabase.playlistDao().getAllPlaylistsList()))
                        put("playlistEntries", playlistEntriesToJson(appDatabase.playlistEntryDao().getAllPlaylistEntriesList()))
                        put("preferences", preferencesToJson())
                    }
                }
                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(backupJson.toString().toByteArray(Charsets.UTF_8))
                } ?: error("无法打开输出文件")
            }.onSuccess {
                _statusMessage.value = "数据库备份成功"
            }.onFailure {
                _statusMessage.value = "数据库备份失败: ${it.message}"
            }
        }
    }

    fun restoreDatabase(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                val backupContent = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    } ?: error("无法读取备份文件")
                }
                val backupJson = JSONObject(backupContent)
                val backupVersion = backupJson.optInt("version", 1)
                if (backupVersion < 1) error("不支持的备份版本: $backupVersion")
                if (backupVersion > 2) error("备份版本过新，当前版本暂不支持: $backupVersion")
                val songs = jsonToSongs(backupJson.optJSONArray("songs") ?: JSONArray())
                val favorites = jsonToFavorites(backupJson.optJSONArray("favorites") ?: JSONArray())
                val logs = jsonToLogs(backupJson.optJSONArray("logs") ?: JSONArray())
                val hiddenSongs = jsonToHiddenSongs(backupJson.optJSONArray("hiddenSongs") ?: JSONArray())
                val playlists = jsonToPlaylists(backupJson.optJSONArray("playlists") ?: JSONArray())
                val playlistEntries = jsonToPlaylistEntries(backupJson.optJSONArray("playlistEntries") ?: JSONArray())
                appDatabase.withTransaction {
                    appDatabase.songDao().deleteAll()
                    appDatabase.playlistEntryDao().deleteAll()
                    appDatabase.playlistDao().deleteAll()
                    appDatabase.favoriteDao().deleteAll()
                    appDatabase.logDao().deleteAllLogs()
                    appDatabase.hiddenSongDao().deleteAll()
                    if (songs.isNotEmpty()) appDatabase.songDao().insertAll(songs)
                    if (playlists.isNotEmpty()) appDatabase.playlistDao().insertAll(playlists)
                    if (playlistEntries.isNotEmpty()) appDatabase.playlistEntryDao().insertAll(playlistEntries)
                    if (favorites.isNotEmpty()) appDatabase.favoriteDao().insertAll(favorites)
                    if (logs.isNotEmpty()) appDatabase.logDao().insertAll(logs)
                    if (hiddenSongs.isNotEmpty()) appDatabase.hiddenSongDao().insertAll(hiddenSongs)
                }
                withContext(Dispatchers.IO) {
                    restorePreferences(backupJson.optJSONObject("preferences"))
                }
            }.onSuccess {
                _statusMessage.value = "数据库还原成功"
            }.onFailure {
                _statusMessage.value = "数据库还原失败: ${it.message}"
            }
        }
    }

    private fun songsToJson(songs: List<Song>) = JSONArray().apply {
        songs.forEach { song ->
            put(
                JSONObject().apply {
                    put("id", song.id)
                    put("title", song.title)
                    put("artist", song.artist)
                    put("album", song.album)
                    put("filePath", song.filePath)
                    put("duration", song.duration)
                    put("trackNumber", song.trackNumber)
                    put("year", song.year)
                    put("genre", song.genre)
                    put("mimeType", song.mimeType)
                    put("size", song.size)
                    put("dateAdded", song.dateAdded)
                    put("isCue", song.isCue)
                    put("cueFilePath", song.cueFilePath)
                    put("startPosition", song.startPosition)
                    put("endPosition", song.endPosition)
                    put("titleInitial", song.titleInitial)
                    put("folderPath", song.folderPath)
                }
            )
        }
    }

    private fun favoritesToJson(favorites: List<Favorite>) = JSONArray().apply {
        favorites.forEach { favorite ->
            put(
                JSONObject().apply {
                    put("filePath", favorite.filePath)
                    put("startPosition", favorite.startPosition)
                    put("dateAdded", favorite.dateAdded)
                }
            )
        }
    }

    private fun logsToJson(logs: List<LogEntry>) = JSONArray().apply {
        logs.forEach { log ->
            put(
                JSONObject().apply {
                    put("id", log.id)
                    put("timestamp", log.timestamp)
                    put("level", log.level)
                    put("tag", log.tag)
                    put("message", log.message)
                }
            )
        }
    }

    private fun hiddenSongsToJson(hiddenSongs: List<HiddenSong>) = JSONArray().apply {
        hiddenSongs.forEach { hiddenSong ->
            put(
                JSONObject().apply {
                    put("filePath", hiddenSong.filePath)
                    put("startPosition", hiddenSong.startPosition)
                    put("hiddenAt", hiddenSong.hiddenAt)
                }
            )
        }
    }

    private fun playlistsToJson(playlists: List<Playlist>) = JSONArray().apply {
        playlists.forEach { playlist ->
            put(
                JSONObject().apply {
                    put("id", playlist.id)
                    put("name", playlist.name)
                    put("createdAt", playlist.createdAt)
                }
            )
        }
    }

    private fun playlistEntriesToJson(entries: List<PlaylistEntry>) = JSONArray().apply {
        entries.forEach { entry ->
            put(
                JSONObject().apply {
                    put("id", entry.id)
                    put("playlistId", entry.playlistId)
                    put("songId", entry.songId)
                    put("order", entry.order)
                }
            )
        }
    }

    private fun preferencesToJson(): JSONObject = JSONObject().apply {
        preferenceFileNames.forEach { name ->
            val sharedPreferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            put(name, sharedPreferencesToJson(sharedPreferences))
        }
    }

    private fun sharedPreferencesToJson(sharedPreferences: SharedPreferences): JSONArray = JSONArray().apply {
        sharedPreferences.all.forEach { (key, value) ->
            val (type, serializedValue) = serializePreferenceValue(value)
            if (type != null) {
                put(
                    JSONObject().apply {
                        put("key", key)
                        put("type", type)
                        put("value", serializedValue)
                    }
                )
            }
        }
    }

    private fun serializePreferenceValue(value: Any?): Pair<String?, Any?> = when (value) {
        is String -> "string" to value
        is Boolean -> "boolean" to value
        is Int -> "int" to value
        is Long -> "long" to value
        is Float -> "float" to value.toDouble()
        is Set<*> -> "string_set" to JSONArray(value.filterIsInstance<String>())
        else -> null to null
    }

    private fun jsonToSongs(jsonArray: JSONArray): List<Song> =
        List(jsonArray.length()) { index ->
            val item = jsonArray.getJSONObject(index)
            Song(
                id = item.optLong("id", 0L),
                title = item.getString("title"),
                artist = item.getString("artist"),
                album = item.getString("album"),
                filePath = item.getString("filePath"),
                duration = item.getLong("duration"),
                trackNumber = item.optInt("trackNumber", 0),
                year = item.optString("year").takeIf { it.isNotEmpty() && it != "null" },
                genre = item.optString("genre").takeIf { it.isNotEmpty() && it != "null" },
                mimeType = item.getString("mimeType"),
                size = item.getLong("size"),
                dateAdded = item.getLong("dateAdded"),
                isCue = item.optBoolean("isCue", false),
                cueFilePath = item.optString("cueFilePath").takeIf { it.isNotEmpty() && it != "null" },
                startPosition = item.optLong("startPosition", 0L),
                endPosition = item.optLong("endPosition", -1L),
                titleInitial = item.optString("titleInitial", ""),
                folderPath = item.optString("folderPath", "")
            )
        }

    private fun jsonToPlaylists(jsonArray: JSONArray): List<Playlist> =
        List(jsonArray.length()) { index ->
            val item = jsonArray.getJSONObject(index)
            Playlist(
                id = item.optLong("id", 0L),
                name = item.getString("name"),
                createdAt = item.optLong("createdAt", System.currentTimeMillis())
            )
        }

    private fun jsonToPlaylistEntries(jsonArray: JSONArray): List<PlaylistEntry> =
        List(jsonArray.length()) { index ->
            val item = jsonArray.getJSONObject(index)
            PlaylistEntry(
                id = item.optLong("id", 0L),
                playlistId = item.getLong("playlistId"),
                songId = item.getLong("songId"),
                order = item.optInt("order", index)
            )
        }

    private fun jsonToFavorites(jsonArray: JSONArray): List<Favorite> =
        List(jsonArray.length()) { index ->
            val item = jsonArray.getJSONObject(index)
            Favorite(
                filePath = item.getString("filePath"),
                startPosition = item.optLong("startPosition", 0L),
                dateAdded = item.getLong("dateAdded")
            )
        }

    private fun jsonToLogs(jsonArray: JSONArray): List<LogEntry> =
        List(jsonArray.length()) { index ->
            val item = jsonArray.getJSONObject(index)
            LogEntry(
                id = item.optLong("id", 0L),
                timestamp = item.getLong("timestamp"),
                level = item.getString("level"),
                tag = item.getString("tag"),
                message = item.getString("message")
            )
        }

    private fun jsonToHiddenSongs(jsonArray: JSONArray): List<HiddenSong> =
        List(jsonArray.length()) { index ->
            val item = jsonArray.getJSONObject(index)
            HiddenSong(
                filePath = item.getString("filePath"),
                startPosition = item.optLong("startPosition", 0L),
                hiddenAt = item.optLong("hiddenAt", System.currentTimeMillis())
            )
        }

    private fun restorePreferences(preferencesJson: JSONObject?) {
        if (preferencesJson == null) return
        preferenceFileNames.forEach { name ->
            val entries = preferencesJson.optJSONArray(name) ?: JSONArray()
            val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
            editor.clear()
            for (index in 0 until entries.length()) {
                val item = entries.optJSONObject(index) ?: continue
                val key = item.optString("key")
                when (item.optString("type")) {
                    "string" -> editor.putString(key, item.optString("value"))
                    "boolean" -> editor.putBoolean(key, item.optBoolean("value"))
                    "int" -> editor.putInt(key, item.optInt("value"))
                    "long" -> editor.putLong(key, item.optLong("value"))
                    "float" -> {
                        val rawValue = item.opt("value")
                        val parsedValue = when (rawValue) {
                            is Number -> rawValue.toFloat()
                            is String -> rawValue.toFloatOrNull()
                            else -> null
                        } ?: error("无效的浮点配置值: key=$key")
                        editor.putFloat(key, parsedValue)
                    }
                    "string_set" -> {
                        val jsonArray = item.optJSONArray("value") ?: JSONArray()
                        val stringSet = mutableSetOf<String>()
                        for (setIndex in 0 until jsonArray.length()) {
                            stringSet.add(jsonArray.optString(setIndex))
                        }
                        editor.putStringSet(key, stringSet)
                    }
                }
            }
            editor.apply()
        }
    }
}
