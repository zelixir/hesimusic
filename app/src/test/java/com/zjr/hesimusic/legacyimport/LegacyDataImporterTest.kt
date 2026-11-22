package com.zjr.hesimusic.legacyimport

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.zjr.hesimusic.data.AppDatabase
import com.zjr.hesimusic.data.model.Favorite
import com.zjr.hesimusic.data.model.Playlist
import com.zjr.hesimusic.data.model.Song
import com.zjr.hesimusic.data.model.SongLoop
import com.zjr.hesimusic.data.repository.SmartPlaylistRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 导入执行器 + 计划的端到端契约测试(SQLite 用 Robolectric 的真实实现,不走 mock)。
 *
 * 覆盖需求里点名的四条:
 * 1. 重复导入两次结果一致(幂等);
 * 2. 部分失败整体回滚(事务);
 * 3. 歌曲不在本机 → 跳过并报告;
 * 4. 只导入循环记录 / 覆盖已有结果 两种选项的语义;
 * 外加:导入前自动备份、CUE 音轨按 (filePath, startPosition) 区分、设置白名单与幂等。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LegacyDataImporterTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var importer: LegacyDataImporter

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DATABASE_NAME)
        context.filesDir.listFiles { file -> file.name.startsWith("import-backup-") }
            ?.forEach { it.delete() }
        for (name in listOf("playback_prefs", "scan_prefs", "sort_prefs", "loop_settings")) {
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
        database = Room.databaseBuilder(context, AppDatabase::class.java, DATABASE_NAME)
            .allowMainThreadQueries()
            .build()
        importer = LegacyDataImporter(database, SmartPlaylistRepository(database), context)
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(DATABASE_NAME)
    }

    // ---- 用例 ----

    @Test
    fun `全部导入重建歌曲 id 并接回循环记录歌单收藏与设置`() = runBlocking {
        seedLocalLibrary()
        val bundle = fixtureBundle()

        val preview = importer.preview(bundle, LegacyImportOptions())
        assertEquals("歌曲: 新增 2(102/103)复用 1(101)", 2, preview.counts.songsNew)
        assertEquals(1, preview.counts.songsMatched)
        assertEquals(2, preview.counts.loopsNew)
        assertEquals(1, preview.counts.loopsSongMissing)
        assertEquals(1, preview.counts.playlistsNew)
        assertEquals(1, preview.counts.playlistsReused)
        assertEquals(3, preview.counts.entriesNew)
        assertEquals(1, preview.counts.entriesSongMissing)
        assertEquals(1, preview.counts.favoritesNew)
        assertEquals(1, preview.counts.favoritesKept)
        assertEquals(1, preview.counts.hiddenNew)
        assertEquals(1, preview.counts.smartPlaylistsNew)
        assertEquals(2, preview.counts.settingsFiles)
        assertEquals(3, preview.counts.settingsEntries)
        assertTrue(preview.hasWork)

        val result = importer.import(bundle, LegacyImportOptions())

        val songs = database.songDao().getAllSongsList()
        assertEquals(3, songs.size)
        val cueFirst = songs.single { it.filePath == A_FLAC && it.startPosition == 0L }
        val cueSecond = songs.single { it.filePath == A_FLAC && it.startPosition == 180000L }
        assertTrue("CUE 两条音轨必须是两行不同 id", cueFirst.id != cueSecond.id)
        val plain = songs.single { it.filePath == B_OGG }
        assertEquals("2024", plain.year)
        assertEquals("Pop", plain.genre)

        val loops = database.songLoopDao().getAll()
        assertEquals(2, loops.size)
        assertEquals(setOf(cueFirst.id, plain.id), loops.map { it.songId }.toSet())
        val cueLoop = loops.single { it.songId == cueFirst.id }
        assertEquals(441000L, cueLoop.loopStartSamples)
        assertTrue(cueLoop.confirmed)
        assertEquals(
            "候选 JSON 必须原样带过来",
            "[{\"startSamples\":441000,\"endSamples\":1323000,\"score\":0.91}]",
            cueLoop.candidatesJson
        )
        assertFalse(loops.single { it.songId == plain.id }.confirmed)

        val playlists = database.playlistDao().getAllPlaylistsList()
        assertEquals(2, playlists.size)
        val playlistA = playlists.single { it.name == "歌单A" }
        val playlistB = playlists.single { it.name == "歌单B" }
        val entries = database.playlistDao().getAllPlaylistEntriesList()
        assertEquals(3, entries.size)
        val songsById = songs.associateBy { it.id }
        assertEquals(
            listOf("CUE 第一轨", "CUE 第二轨"),
            entries.filter { it.playlistId == playlistA.id }.sortedBy { it.order }
                .map { songsById.getValue(it.songId).title }
        )
        assertEquals(
            listOf("普通歌"),
            entries.filter { it.playlistId == playlistB.id }.sortedBy { it.order }
                .map { songsById.getValue(it.songId).title }
        )
        assertTrue("不存在歌曲 999 的条目必须被跳过", entries.none { it.songId == 999L })

        assertEquals(2, database.favoriteDao().getAllFavoritesList().size)
        assertEquals(1, database.hiddenSongDao().getAllHiddenSongsList().size)
        val smart = database.smartPlaylistDao().getAllList()
        assertEquals(1, smart.size)
        assertEquals("专辑A", smart.single().name)

        val playbackPrefs = context.getSharedPreferences("playback_prefs", Context.MODE_PRIVATE)
        assertEquals("DARK", playbackPrefs.getString("app_theme_mode", null))
        assertEquals(3, playbackPrefs.getInt("min_album_track_count", 0))
        assertFalse("播放状态键不该导入: queue_ids", playbackPrefs.contains("queue_ids"))
        assertFalse("播放状态键不该导入: current_song_id", playbackPrefs.contains("current_song_id"))
        assertFalse("SAF 授权 URI 不该导入", playbackPrefs.contains("startup_image_uri"))
        assertEquals(
            "scan_prefs 应导入",
            "content://com.android.externalstorage.documents/tree/primary%3AMusic",
            context.getSharedPreferences("scan_prefs", Context.MODE_PRIVATE).getString("scan_root_uri", null)
        )
        assertFalse(
            "loop_settings(引擎选择/默认值标记)不在设置白名单",
            context.getSharedPreferences("loop_settings", Context.MODE_PRIVATE).contains("loop_engine")
        )

        assertEquals(3, result.settingsApplied)
        assertNotNull("导入前必须自动备份本机数据库", result.backupPath)
        val backup = File(checkNotNull(result.backupPath))
        assertTrue(backup.isFile)
        assertTrue(backup.name.startsWith("import-backup-"))
        assertEquals("备份必须是可打开的 SQLite 文件", "SQLite format 3", backup.readBytes().copyOfRange(0, 15).toString(Charsets.US_ASCII))
    }

    @Test
    fun `同一份文件导入两次结果完全一致且第二次判为无操作`() = runBlocking {
        seedLocalLibrary()
        val bundle = fixtureBundle()
        val options = LegacyImportOptions()

        importer.import(bundle, options)
        val firstSongs = database.songDao().getAllSongsList()
        val firstLoops = database.songLoopDao().getAll()
        val firstEntries = database.playlistDao().getAllPlaylistEntriesList()

        val second = importer.import(bundle, options)

        assertTrue("第二次导入应判为无操作", second.isNoop)
        assertFalse(second.counts.hasWork)
        assertEquals(0, second.counts.songsNew)
        assertEquals(0, second.counts.loopsNew)
        assertEquals("已有循环记录默认保留", 2, second.counts.loopsKeptExisting)
        assertEquals(0, second.counts.playlistsNew)
        assertEquals("歌单条目已存在 → 全部跳过", 0, second.counts.entriesNew)
        assertEquals(3, second.counts.entriesDuplicate)
        assertEquals(0, second.counts.favoritesNew)
        assertEquals(0, second.counts.hiddenNew)
        assertEquals(0, second.counts.smartPlaylistsNew)
        assertEquals("设置已是同样的值 → 不重复写", 0, second.counts.settingsEntries)
        assertNull("没有写操作就不该再生成备份", second.backupPath)

        assertEquals(firstSongs, database.songDao().getAllSongsList())
        assertEquals(firstLoops, database.songLoopDao().getAll())
        assertEquals(firstEntries, database.playlistDao().getAllPlaylistEntriesList())
        assertEquals(2, database.favoriteDao().getAllFavoritesList().size)
    }

    @Test
    fun `只导入循环记录时不新增歌曲也不碰歌单与设置`() = runBlocking {
        seedLocalLibrary()
        val bundle = fixtureBundle()

        val preview = importer.preview(bundle, LegacyImportOptions(mode = LegacyImportMode.LOOPS_ONLY))
        assertEquals(0, preview.counts.songsNew)
        assertEquals(2, preview.counts.songsNotInserted)
        assertEquals(1, preview.counts.loopsNew)
        assertEquals("103 与 999 两条的歌曲都不在本机", 2, preview.counts.loopsSongMissing)
        assertEquals(0, preview.counts.playlistsNew)
        assertEquals(0, preview.counts.entriesNew)
        assertEquals(0, preview.counts.settingsEntries)

        importer.import(bundle, LegacyImportOptions(mode = LegacyImportMode.LOOPS_ONLY))

        assertEquals("曲库不该被改动", 1, database.songDao().getAllSongsList().size)
        assertEquals(1, database.songLoopDao().getAll().size)
        assertEquals(1, database.playlistDao().getAllPlaylistsList().size)
        assertEquals(0, database.playlistDao().getAllPlaylistEntriesList().size)
        assertEquals(1, database.favoriteDao().getAllFavoritesList().size)
        assertEquals(0, database.hiddenSongDao().getAllHiddenSongsList().size)
        assertEquals(0, database.smartPlaylistDao().getAllList().size)
        assertNull(context.getSharedPreferences("playback_prefs", Context.MODE_PRIVATE).getString("app_theme_mode", null))
    }

    @Test
    fun `不覆盖时保留本机已确认结果_勾选覆盖后使用上一版结果`() = runBlocking {
        val localSongId = seedLocalLibrary()
        database.songLoopDao().upsert(
            SongLoop(
                songId = localSongId,
                loopStartSamples = 100L,
                loopEndSamples = 200L,
                sampleRate = 44100,
                loopStartMs = 1L,
                loopEndMs = 2L,
                score = 0.1,
                noteDistance = 0.1,
                loudnessDifference = 0.1,
                createdAt = 1L,
                confirmed = true,
            )
        )
        val bundle = fixtureBundle()

        val kept = importer.import(bundle, LegacyImportOptions(overwriteExistingLoops = false))
        assertEquals(1, kept.counts.loopsKeptExisting)
        assertEquals(1, kept.counts.loopsNew)
        assertEquals(100L, database.songLoopDao().getBySongId(localSongId)!!.loopStartSamples)

        val overwritten = importer.import(bundle, LegacyImportOptions(overwriteExistingLoops = true))
        assertEquals(0, overwritten.counts.loopsNew)
        assertEquals(2, overwritten.counts.loopsOverwritten)
        assertEquals(441000L, database.songLoopDao().getBySongId(localSongId)!!.loopStartSamples)
    }

    @Test
    fun `写库中途失败时整体回滚且设置不落盘`() = runBlocking {
        seedLocalLibrary()
        val bundle = fixtureBundle()
        importer.beforeCommit = { error("注入的写入失败") }

        val error = runCatching { importer.import(bundle, LegacyImportOptions()) }.exceptionOrNull()

        assertNotNull("异常必须抛给调用方", error)
        assertTrue(error!!.message!!.contains("注入的写入失败"))
        assertEquals("歌曲必须回滚", 1, database.songDao().getAllSongsList().size)
        assertEquals("循环记录必须回滚", 0, database.songLoopDao().getAll().size)
        assertEquals("歌单必须回滚", 1, database.playlistDao().getAllPlaylistsList().size)
        assertEquals("歌单条目必须回滚", 0, database.playlistDao().getAllPlaylistEntriesList().size)
        assertEquals("收藏必须回滚(seed 的那条还在)", 1, database.favoriteDao().getAllFavoritesList().size)
        assertEquals("隐藏必须回滚", 0, database.hiddenSongDao().getAllHiddenSongsList().size)
        assertEquals("SQL 歌单必须回滚", 0, database.smartPlaylistDao().getAllList().size)
        assertNull(
            "事务失败时设置不该写",
            context.getSharedPreferences("playback_prefs", Context.MODE_PRIVATE).getString("app_theme_mode", null)
        )
    }

    @Test
    fun `本机缺歌时跳过并报告而不是插入半条记录`() = runBlocking {
        seedLocalLibrary()
        val bundle = LegacyExportParser.parse(
            """
            {
              "format": "hesi-legacy-export", "formatVersion": 1, "version": 4,
              "songs": [
                {"id": 1, "title": "不在本机", "artist": "", "album": "", "filePath": "/storage/emulated/0/Music/missing.flac",
                 "duration": 1000, "mimeType": "audio/flac", "size": 0, "dateAdded": 1, "startPosition": 0}
              ],
              "songLoops": [
                {"songId": 1, "loopStartSamples": 100, "loopEndSamples": 200, "sampleRate": 44100, "loopStartMs": 1, "loopEndMs": 2,
                 "score": 1.0, "noteDistance": 0.0, "loudnessDifference": 0.0, "createdAt": 1, "confirmed": true}
              ]
            }
            """.trimIndent()
        )

        val result = importer.import(bundle, LegacyImportOptions(mode = LegacyImportMode.LOOPS_ONLY))

        assertEquals(1, result.counts.loopsSongMissing)
        assertEquals(0, result.counts.loopsNew)
        assertTrue(
            "跳过原因必须出现在提示里: " + result.warnings,
            result.warnings.any { it.contains("在本机不存在") }
        )
        assertEquals(0, database.songLoopDao().getAll().size)
        assertEquals(1, database.songDao().getAllSongsList().size)
    }

    @Test
    fun `重新扫描后按路径与起点重新接线循环记录`() = runBlocking {
        seedLocalLibrary()
        val bundle = fixtureBundle()
        importer.import(bundle, LegacyImportOptions())
        assertEquals(3, database.songDao().getAllSongsList().size)

        // 模拟 v2 全量重新扫描:songs 清空重建、id 全变,循环记录要按 (filePath, startPosition) 重新接线
        database.songDao().deleteAll()
        database.songDao().insertAll(
            listOf(
                song("CUE 第一轨", A_FLAC, 0L, isCue = true),
                song("CUE 第二轨", A_FLAC, 180000L, isCue = true),
                song("普通歌", B_OGG, 0L),
            )
        )
        val rescanned = database.songDao().getAllSongsList()
        assertEquals(3, rescanned.size)
        // 清掉旧循环记录后再导入,验证按 (filePath, startPosition) 重新接线
        database.songLoopDao().deleteAll()
        val result = importer.import(bundle, LegacyImportOptions(overwriteExistingLoops = true))
        assertEquals(2, result.counts.loopsNew)
        val loops = database.songLoopDao().getAll()
        assertEquals(2, loops.size)
        val cueFirst = rescanned.single { it.filePath == A_FLAC && it.startPosition == 0L }
        assertTrue(loops.any { it.songId == cueFirst.id })
        assertTrue(
            "循环记录不该挂到 CUE 第二轨",
            loops.none { it.songId == rescanned.single { song -> song.startPosition == 180000L }.id }
        )
    }

    @Test
    fun `设置白名单拦截播放状态与跨应用 URI`() {
        val playback = LegacyPreferenceFile(
            name = "playback_prefs.xml",
            entries = listOf(
                LegacyPreferenceEntry("app_theme_palette", "string", "PURPLE"),
                LegacyPreferenceEntry("queue_ids", "string", "1,2,3"),
                LegacyPreferenceEntry("startup_image_uri", "string", "content://media/1"),
                LegacyPreferenceEntry("list_background_image_uri", "string", "/storage/emulated/0/Pictures/bg.jpg"),
            )
        )
        val allowed = LegacyPreferencePolicy.filter(playback)
        assertEquals(
            listOf("app_theme_palette", "list_background_image_uri"),
            allowed.map { it.key }
        )
        assertTrue(LegacyPreferencePolicy.filter(LegacyPreferenceFile("loop_settings", playback.entries)).isEmpty())
        assertTrue(LegacyPreferencePolicy.filter(LegacyPreferenceFile("loop_intent", playback.entries)).isEmpty())
    }

    @Test
    fun `SQL 歌单校验失败时跳过并报告`() = runBlocking {
        seedLocalLibrary()
        val bundle = LegacyExportParser.parse(
            """
            {
              "format": "hesi-legacy-export", "formatVersion": 1, "version": 4,
              "songs": [],
              "smartPlaylists": [
                {"id": 1, "name": "坏视图", "createdAt": 1, "sql": "DELETE FROM songs"}
              ]
            }
            """.trimIndent()
        )
        // songs 为空且没有循环记录/条目 → 允许解析(空 sections 检查只针对 id 依赖)
        val result = importer.import(bundle, LegacyImportOptions())
        assertEquals(1, result.counts.smartPlaylistsInvalid)
        assertEquals(0, result.counts.smartPlaylistsNew)
        assertTrue(result.warnings.any { it.contains("坏视图") })
        assertEquals(0, database.smartPlaylistDao().getAllList().size)
    }

    // ---- helpers ----

    private suspend fun seedLocalLibrary(): Long {
        database.songDao().insertAll(listOf(song("CUE 第一轨", A_FLAC, 0L, isCue = true)))
        database.playlistDao().insertPlaylist(Playlist(name = "歌单A", createdAt = 1L))
        database.favoriteDao().insertAll(listOf(Favorite(filePath = A_FLAC, startPosition = 0L, dateAdded = 1L)))
        return database.songDao().getAllSongsList().single().id
    }

    private fun song(title: String, filePath: String, startPosition: Long, isCue: Boolean = false) = Song(
        title = title,
        artist = "Artist",
        album = "Album",
        filePath = filePath,
        duration = 180000,
        mimeType = "audio/flac",
        size = 0,
        dateAdded = 1L,
        isCue = isCue,
        startPosition = startPosition,
    )

    private fun fixtureBundle(): LegacyExportBundle = LegacyExportParser.parse(fixtureText())

    private fun fixtureText(): String = checkNotNull(
        javaClass.classLoader?.getResourceAsStream("import/legacy-export-sample.json")
    ).bufferedReader(Charsets.UTF_8).use { it.readText() }

    private companion object {
        const val DATABASE_NAME = "hesimusic_db"
        const val A_FLAC = "/storage/emulated/0/Music/a.flac"
        const val B_OGG = "/storage/emulated/0/Music/b.ogg"
    }
}
