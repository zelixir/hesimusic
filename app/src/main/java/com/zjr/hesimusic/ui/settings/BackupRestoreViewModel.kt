package com.zjr.hesimusic.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.zjr.hesimusic.data.AppDatabase
import com.zjr.hesimusic.data.model.Favorite
import com.zjr.hesimusic.data.model.LogEntry
import com.zjr.hesimusic.data.model.Song
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject

@HiltViewModel
class BackupRestoreViewModel @Inject constructor(
    private val appDatabase: AppDatabase,
    @ApplicationContext private val context: Context
) : ViewModel() {
    private val _statusMessage = MutableStateFlow("请选择备份或还原操作")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    fun backupDatabase(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                val backupJson = JSONObject().apply {
                    put("version", 1)
                    put("songs", songsToJson(appDatabase.songDao().getAllSongsList()))
                    put("favorites", favoritesToJson(appDatabase.favoriteDao().getAllFavoritesList()))
                    put("logs", logsToJson(appDatabase.logDao().getAllLogsList()))
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
                val backupContent = context.contentResolver.openInputStream(uri)?.use {
                    it.readBytes().toString(Charsets.UTF_8)
                } ?: error("无法读取备份文件")
                val backupJson = JSONObject(backupContent)
                val songs = jsonToSongs(backupJson.optJSONArray("songs") ?: JSONArray())
                val favorites = jsonToFavorites(backupJson.optJSONArray("favorites") ?: JSONArray())
                val logs = jsonToLogs(backupJson.optJSONArray("logs") ?: JSONArray())
                appDatabase.withTransaction {
                    appDatabase.songDao().deleteAll()
                    appDatabase.favoriteDao().deleteAll()
                    appDatabase.logDao().deleteAllLogs()
                    if (songs.isNotEmpty()) appDatabase.songDao().insertAll(songs)
                    if (favorites.isNotEmpty()) appDatabase.favoriteDao().insertAll(favorites)
                    if (logs.isNotEmpty()) appDatabase.logDao().insertAll(logs)
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
}
