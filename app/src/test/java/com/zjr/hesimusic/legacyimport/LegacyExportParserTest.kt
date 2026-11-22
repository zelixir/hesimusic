package com.zjr.hesimusic.legacyimport

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 导出文件解析的契约测试(纯解析,不碰数据库)。
 *
 * 固定的 fixture 是 app/src/test/resources/import/legacy-export-sample.json,
 * 结构与 tools/export-legacy-data.ps1 的真实产物一致(见 docs/import-legacy-data.md §3)。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LegacyExportParserTest {

    private fun fixtureJson(): JSONObject = JSONObject(fixtureText())

    private fun fixtureText(): String = checkNotNull(
        javaClass.classLoader?.getResourceAsStream("import/legacy-export-sample.json")
    ) { "缺少测试 fixture: import/legacy-export-sample.json" }
        .bufferedReader(Charsets.UTF_8)
        .use { it.readText() }

    @Test
    fun `完整导出文件解析出全部段与元信息`() {
        val bundle = LegacyExportParser.parse(fixtureText())

        assertEquals(LegacyExportParser.FORMAT, bundle.meta.format)
        assertEquals(1, bundle.meta.formatVersion)
        assertEquals(4, bundle.meta.backupVersion)
        assertEquals(1795335600000L, bundle.meta.exportedAt ?: -1L)
        assertEquals("com.zjr.hesimusic", bundle.meta.sourceAppId)
        assertEquals("2.6.25-test", bundle.meta.sourceVersionName)
        assertEquals(1336L, bundle.meta.sourceVersionCode ?: -1L)
        assertEquals(10, bundle.meta.sourceDatabaseVersion ?: -1)

        assertEquals(3, bundle.songs.size)
        assertEquals(3, bundle.songLoops.size)
        assertEquals(2, bundle.playlists.size)
        assertEquals(4, bundle.playlistEntries.size)
        assertEquals(2, bundle.favorites.size)
        assertEquals(1, bundle.hiddenSongs.size)
        assertEquals(1, bundle.smartPlaylists.size)
        assertEquals(3, bundle.preferences.size)
        assertTrue("解析不该产生警告: " + bundle.warnings, bundle.warnings.isEmpty())

        assertTrue(bundle.describeSource().contains("2.6.25-test"))
        assertTrue(bundle.describeContents().contains("循环记录 3"))
    }

    @Test
    fun `CUE 同文件不同起点解析成两条独立歌曲`() {
        val bundle = LegacyExportParser.parse(fixtureText())
        val cueTracks = bundle.songs.filter { it.song.filePath.endsWith("a.flac") }
        assertEquals(2, cueTracks.size)
        assertEquals(setOf(0L, 180000L), cueTracks.map { it.key.startPosition }.toSet())
        assertEquals(2, cueTracks.map { it.key }.toSet().size)
        assertTrue(cueTracks.all { it.song.isCue })
    }

    @Test
    fun `上一版 app 自带备份无 format 与 source 也能解析`() {
        val json = fixtureJson()
        json.remove("format")
        json.remove("formatVersion")
        json.remove("source")
        json.remove("generator")

        val bundle = LegacyExportParser.parse(json)

        assertNull("自带备份没有 format 字段", bundle.meta.format)
        assertEquals(0, bundle.meta.formatVersion)
        assertNull(bundle.meta.sourceAppId)
        assertTrue(bundle.describeSource().startsWith("来源 com.zjr.hesimusic"))
        assertEquals(3, bundle.songs.size)
    }

    @Test
    fun `循环记录缺 confirmed 时按已确认处理`() {
        val json = fixtureJson()
        val loops = json.getJSONArray("songLoops")
        loops.getJSONObject(0).remove("confirmed")
        val bundle = LegacyExportParser.parse(json)
        assertTrue(bundle.songLoops[0].confirmed)
        assertTrue("显式的 confirmed=false 必须保留", !bundle.songLoops[1].confirmed)
    }

    @Test
    fun `格式标识不认识时拒绝整份文件`() {
        val json = fixtureJson()
        json.put("format", "something-else")
        val error = runCatching { LegacyExportParser.parse(json) }.exceptionOrNull()
        assertTrue(error is LegacyImportException)
        assertTrue(error!!.message!!.contains("不认识的文件格式"))
    }

    @Test
    fun `格式版本过新时给出明确提示`() {
        val json = fixtureJson()
        json.put("formatVersion", 99)
        val error = runCatching { LegacyExportParser.parse(json) }.exceptionOrNull()
        assertTrue(error is LegacyImportException)
        assertTrue(error!!.message!!.contains("格式版本过新"))
    }

    @Test
    fun `备份方言版本过新时拒绝`() {
        val json = fixtureJson()
        json.put("version", 5)
        val error = runCatching { LegacyExportParser.parse(json) }.exceptionOrNull()
        assertTrue(error is LegacyImportException)
        assertTrue(error!!.message!!.contains("备份版本过新"))
    }

    @Test
    fun `缺 songs 段但有循环记录时提示重新导出`() {
        val json = fixtureJson()
        json.put("songs", org.json.JSONArray())
        val error = runCatching { LegacyExportParser.parse(json) }.exceptionOrNull()
        assertTrue(error is LegacyImportException)
        assertTrue(error!!.message!!.contains("缺少 songs 段"))
    }

    @Test
    fun `空文件与坏 JSON 给出可读错误`() {
        assertTrue(runCatching { LegacyExportParser.parse("   ") }.exceptionOrNull() is LegacyImportException)
        val broken = runCatching { LegacyExportParser.parse("{ not json") }.exceptionOrNull()
        assertTrue(broken is LegacyImportException)
        assertTrue(broken!!.message!!.contains("不是合法的 JSON"))
    }

    @Test
    fun `坏记录只跳过并记警告不阻断整体导入`() {
        val json = fixtureJson()
        val songs = json.getJSONArray("songs")
        val brokenSong = JSONObject(songs.getJSONObject(0).toString())
        brokenSong.remove("filePath")
        songs.put(brokenSong)
        val loops = json.getJSONArray("songLoops")
        val brokenLoop = JSONObject(loops.getJSONObject(0).toString())
        brokenLoop.put("sampleRate", 0)
        loops.put(brokenLoop)

        val bundle = LegacyExportParser.parse(json)

        assertEquals("坏歌不该进入结果", 3, bundle.songs.size)
        assertEquals("坏循环记录不该进入结果", 3, bundle.songLoops.size)
        assertTrue(bundle.warnings.any { it.contains("缺少 filePath") })
        assertTrue(bundle.warnings.any { it.contains("采样率无效") })
    }

    @Test
    fun `设置段按文件分组且保留类型`() {
        val bundle = LegacyExportParser.parse(fixtureText())
        val playback = bundle.preferences.first { it.name == "playback_prefs" }
        val theme = playback.entries.first { it.key == "app_theme_mode" }
        assertEquals("string", theme.type)
        assertEquals("DARK", theme.value)
        val count = playback.entries.first { it.key == "min_album_track_count" }
        assertEquals("int", count.type)
        assertEquals(3, count.value)
        assertNotNull(bundle.preferences.firstOrNull { it.name == "scan_prefs" })
    }
}
