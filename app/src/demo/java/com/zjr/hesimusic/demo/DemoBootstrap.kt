package com.zjr.hesimusic.demo

import android.app.Application
import android.media.MediaMetadataRetriever
import android.util.Log
import com.zjr.hesimusic.data.dao.SongDao
import com.zjr.hesimusic.data.dao.SongLoopDao
import com.zjr.hesimusic.data.model.Song
import com.zjr.hesimusic.data.model.SongLoop
import com.zjr.hesimusic.loop.analysis.LoopCandidateKt
import com.zjr.hesimusic.loop.analysis.LoopCandidatesJson
import com.zjr.hesimusic.loop.audio.source.LoopSourceMode
import com.zjr.hesimusic.utils.AlphabetIndexer
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToLong

/**
 * demo 包的内置素材启动器(仅 src/demo)。
 *
 * 首次启动(以及每次冷启动,幂等):
 * 1. 把 assets/demo/night.ogg 复制到 filesDir/demo/night.ogg(已存在且非空则跳过);
 * 2. 用 MediaMetadataRetriever 取时长/专辑(标题/歌手取自 assets/demo/loop.json);
 * 3. 按路径查 songs,没有则插入该行;
 * 4. Upsert song_loop:采样点由 loop.json 的 aMs/bMs 直接算出(44100Hz),
 *    confirmed=true、score=0.9(即"已确认的预置分析结果");
 * 5. 把 songId 发布到会话,MainActivity 导航 loop/{songId},页面因此直接显示已确认 A/B。
 *
 * 不触发全库扫描、不请求任何存储权限。重复启动不会重复插入歌曲,只保证 song_loop
 * 与预置窗口一致。
 */
internal object DemoBootstrap {

    private const val TAG = "DemoBootstrap"

    private const val SONG_ASSET = "demo/night.ogg"
    private const val LOOP_ASSET = "demo/loop.json"
    private const val SONG_FILE_NAME = "night.ogg"
    private const val DEMO_DIR = "demo"
    private const val DEFAULT_ALBUM = "HesiMusic Demo"
    private const val DEFAULT_SAMPLE_RATE = 44_100
    private const val PRESET_SCORE = 0.9

    /** Hilt 组件要等 Application.onCreate 才创建;provider 先到,按 100ms 轮询最多约 30s。 */
    private const val HILT_RETRY_DELAY_MS = 100L
    private const val HILT_RETRY_LIMIT = 300

    fun start(app: Application): DemoSession {
        applyEngineFile(app)
        Log.i(TAG, "demo bootstrap start (auto audition arms every launch)")
        // 每次启动都清掉旧意图后再 arm,避免上次进程/恢复状态残留。
        DemoEntryHooks.clearAutoAudition()
        val session = DemoSessionImpl()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val songId = seed(app)
                Log.i(TAG, "demo seed ready: songId=" + songId)
                // 无条件 arm(与歌曲是否已存在无关):冷启动首次进入结果页后自动试听
                // 「仅循环段」;LoopViewModel 进入 Ready 或后续收到 arm 时消费一次。
                DemoEntryHooks.armAutoAudition(songId)
                Log.i(TAG, "demo auto audition armed: songId=" + songId + " mode=LOOP_ONLY")
                session.ready(songId)
            } catch (t: Throwable) {
                Log.e(TAG, "demo seed failed", t)
                session.fail(t.message ?: "demo 初始化失败")
            }
        }
        return session
    }

    /**
     * demo 专用引擎切换：push 一个文本文件到
     * getExternalFilesDir(null)/engine，内容 source 或 m3。
     *
     * 不存在/内容无效时保持当前默认；写入的是用户偏好，因此后续
     * LoopSourceMode.refreshFromSettings 会继续使用该模式。
     */
    private fun applyEngineFile(app: Application) {
        val dir = app.getExternalFilesDir(null) ?: return
        val file = File(dir, "engine")
        if (!file.isFile) {
            Log.i(TAG, "engine file absent; keep default mode=" + LoopSourceMode.current)
            return
        }
        val raw = try {
            file.readText(Charsets.UTF_8).trim().lowercase()
        } catch (t: Throwable) {
            Log.w(TAG, "read engine file failed path=" + file.absolutePath, t)
            return
        }
        val mode = when (raw) {
            "source" -> LoopSourceMode.SOURCE
            "m3", "m3_prime", "m3-prime" -> LoopSourceMode.M3_PRIME
            else -> null
        }
        if (mode == null) {
            Log.w(TAG, "engine file invalid content=" + raw + " path=" + file.absolutePath)
            return
        }
        LoopSourceMode.select(app, mode)
        Log.i(TAG, "engine file applied mode=" + mode + " path=" + file.absolutePath)
    }

    private suspend fun seed(app: Application): Long {
        val spec = loadLoopSpec(app)
        Log.i(
            TAG,
            "demo seed spec title=" + spec.title + " aMs=" + spec.aMs + " bMs=" + spec.bMs +
                " sampleRate=" + spec.sampleRate,
        )
        val songFile = ensureSongFile(app)
        val audio = readAudioMeta(songFile)
        val entryPoint = awaitEntryPoint(app)
        val songDao = entryPoint.songDao()
        val song = ensureSongRow(songDao, songFile, spec, audio)
        upsertPresetLoop(entryPoint.songLoopDao(), song.id, spec)
        return song.id
    }

    /** 读取预置分析结果(assets/demo/loop.json)。 */
    private fun loadLoopSpec(app: Application): DemoLoopSpec {
        val raw = app.assets.open(LOOP_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val json = JSONObject(raw)
        val sampleRate = json.optInt("sampleRate", DEFAULT_SAMPLE_RATE)
            .takeIf { it > 0 } ?: DEFAULT_SAMPLE_RATE
        val spec = DemoLoopSpec(
            title = json.optString("title").ifBlank { "Unfurling Night" },
            artist = json.optString("artist").ifBlank { "Unknown Artist" },
            aMs = json.optLong("aMs", 0L),
            bMs = json.optLong("bMs", 0L),
            sampleRate = sampleRate,
            confirmed = json.optBoolean("confirmed", true),
        )
        require(spec.aMs >= 0L) { "invalid aMs: " + spec.aMs }
        require(spec.bMs > spec.aMs) { "invalid loop window: aMs=" + spec.aMs + " bMs=" + spec.bMs }
        return spec
    }

    /** 首次启动把内置歌曲复制到私有目录;已存在(非空)则直接复用。 */
    private fun ensureSongFile(app: Application): File {
        val dir = File(app.filesDir, DEMO_DIR)
        if (!dir.isDirectory && !dir.mkdirs()) {
            throw IllegalStateException("cannot create demo dir: " + dir.absolutePath)
        }
        val dest = File(dir, SONG_FILE_NAME)
        if (dest.isFile && dest.length() > 0L) {
            Log.i(TAG, "reuse existing demo asset path=" + dest.absolutePath + " bytes=" + dest.length())
            return dest
        }

        // 先写临时文件再改名,避免进程被杀留下半个文件被下次误判为"已存在"。
        val tmp = File(dir, SONG_FILE_NAME + ".tmp")
        app.assets.open(SONG_ASSET).use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        }
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
        if (dest.length() <= 0L) throw IllegalStateException("demo song copy is empty")
        Log.i(TAG, "copied demo asset -> " + dest.absolutePath + " (" + dest.length() + " bytes)")
        return dest
    }

    /** 时长/专辑从音频文件解码;失败不阻塞(页面以 song_loop 的 loopEndMs 兜底时长)。 */
    private fun readAudioMeta(file: File): DemoAudioMeta {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            DemoAudioMeta(
                durationMs = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
                    ?.coerceAtLeast(0L)
                    ?: 0L,
                album = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() },
            )
        } catch (t: Throwable) {
            Log.w(TAG, "read demo audio metadata failed", t)
            DemoAudioMeta(durationMs = 0L, album = null)
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** 按路径查歌曲,没有才插入;老 demo 数据 duration=0 时用解码结果修补。 */
    private suspend fun ensureSongRow(
        songDao: SongDao,
        songFile: File,
        spec: DemoLoopSpec,
        audio: DemoAudioMeta,
    ): Song {
        val path = songFile.absolutePath
        val existing = songDao.getSongByPathAndStartPosition(path, 0L)
        if (existing != null) {
            Log.i(TAG, "song row exists (skip insert): id=" + existing.id + " path=" + path)
            if (existing.duration <= 0L && audio.durationMs > 0L) {
                val repaired = existing.copy(duration = audio.durationMs)
                songDao.insertAll(listOf(repaired))
                return repaired
            }
            return existing
        }
        Log.i(TAG, "song row missing, inserting: path=" + path)
        val song = Song(
            title = spec.title,
            artist = spec.artist,
            album = audio.album ?: DEFAULT_ALBUM,
            filePath = path,
            duration = audio.durationMs,
            mimeType = "audio/ogg",
            size = songFile.length(),
            dateAdded = System.currentTimeMillis(),
            titleInitial = AlphabetIndexer.getInitial(spec.title).toString(),
            folderPath = songFile.parent ?: "",
        )
        songDao.insertAll(listOf(song))
        return songDao.getSongByPathAndStartPosition(path, 0L)
            ?: throw IllegalStateException("song row not found after insert: " + path)
    }

    /**
     * 写入/覆盖预置的已确认循环行。
     *
     * 采样点严格按验收公式 aMs * 44100 / 1000、bMs * 44100 / 1000 计算;
     * ms 字段用与正式保存路径相同的"采样点 → ms(round)"换算,保证页面显示
     * 00:01.741 / 01:20.364。
     */
    private suspend fun upsertPresetLoop(
        songLoopDao: SongLoopDao,
        songId: Long,
        spec: DemoLoopSpec,
    ) {
        val startSamples = spec.aMs * spec.sampleRate / 1000L
        val endSamples = spec.bMs * spec.sampleRate / 1000L
        require(endSamples > startSamples) {
            "invalid loop samples: start=" + startSamples + " end=" + endSamples
        }
        val startMs = (startSamples * 1000.0 / spec.sampleRate).roundToLong()
        val endMs = (endSamples * 1000.0 / spec.sampleRate).roundToLong()
        val candidate = LoopCandidateKt(
            startSamples = startSamples,
            endSamples = endSamples,
            score = PRESET_SCORE,
            noteDistance = 0.0,
            loudnessDifference = 0.0,
        )
        songLoopDao.upsert(
            SongLoop(
                songId = songId,
                loopStartSamples = startSamples,
                loopEndSamples = endSamples,
                sampleRate = spec.sampleRate,
                loopStartMs = startMs,
                loopEndMs = endMs,
                score = PRESET_SCORE,
                noteDistance = 0.0,
                loudnessDifference = 0.0,
                createdAt = System.currentTimeMillis(),
                confirmed = spec.confirmed,
                // 与真实分析落库一致:候选列表 JSON 一并写入,重进结果页可恢复「候选(1)」。
                candidatesJson = LoopCandidatesJson.encode(listOf(candidate)),
            ),
        )
        Log.i(
            TAG,
            "preset loop upserted: songId=" + songId +
                " start=" + startSamples + " end=" + endSamples +
                " (" + startMs + "ms.." + endMs + "ms) confirmed=" + spec.confirmed,
        )
    }

    /** 等 Hilt singleton 组件可用(provider.onCreate 早于 Application.onCreate)。 */
    private suspend fun awaitEntryPoint(app: Application): DemoEntryPoint {
        var last: Throwable? = null
        repeat(HILT_RETRY_LIMIT) {
            try {
                return EntryPointAccessors.fromApplication(app, DemoEntryPoint::class.java)
            } catch (e: Exception) {
                last = e
                delay(HILT_RETRY_DELAY_MS)
            }
        }
        throw IllegalStateException("Hilt component not ready for demo bootstrap", last)
    }

    private data class DemoLoopSpec(
        val title: String,
        val artist: String,
        val aMs: Long,
        val bMs: Long,
        val sampleRate: Int,
        val confirmed: Boolean,
    )

    private data class DemoAudioMeta(
        val durationMs: Long,
        val album: String?,
    )
}

/** 会话实现:准备完成后发布 songId;失败时发布原因供 UI 回退。 */
private class DemoSessionImpl : DemoSession {

    private val _songId = MutableStateFlow<Long?>(null)
    override val songId: StateFlow<Long?> = _songId.asStateFlow()

    private val _failure = MutableStateFlow<String?>(null)
    override val failure: StateFlow<String?> = _failure.asStateFlow()

    fun ready(id: Long) {
        _failure.value = null
        _songId.value = id
    }

    fun fail(message: String) {
        _failure.value = message
    }
}
