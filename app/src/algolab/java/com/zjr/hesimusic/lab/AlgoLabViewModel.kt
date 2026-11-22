package com.zjr.hesimusic.lab

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Debug
import android.os.Environment
import android.os.SystemClock
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zjr.hesimusic.data.dao.SongDao
import com.zjr.hesimusic.data.model.Song
import com.zjr.hesimusic.data.preferences.ScanPreferences
import com.zjr.hesimusic.data.repository.ScanRepository
import com.zjr.hesimusic.data.repository.ScanStatus
import com.zjr.hesimusic.loop.analysis.PcmDecoder
import com.zjr.hesimusic.loop.analysis.ProgressSink
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import kotlin.math.roundToInt

enum class LabSortMode { FUSED, LEGACY }

data class LabUiState(
    val songs: List<Song> = emptyList(),
    val songsLoading: Boolean = true,
    val selectedSong: Song? = null,
    val pathInput: String = "",
    val params: LabParams = LabParams(),
    val referenceAMs: String = "",
    val referenceBMs: String = "",
    val sortMode: LabSortMode = LabSortMode.FUSED,
    val onlyTopN: Boolean = true,
    val running: Boolean = false,
    val phase: String = "",
    val progress: Float = 0f,
    val error: String? = null,
    val statusMessage: String? = null,
    val result: LabResult? = null,
    val exportFiles: List<String> = emptyList(),
    val exportDir: String = "",
    // algolab 自己的曲库扫描(不依赖 MainActivity;独立包名/独立数据库)。
    val scanFolder: String = "",
    val scanning: Boolean = false,
    val scanStatus: String = "",
    val scanCount: Int = 0,
    val hasAllFilesAccess: Boolean = false,
)

/**
 * 实验页状态机(单飞:分析中再次点击直接忽略)。
 *
 * 保证用户要求的第一条:**一次解码 + 一次引擎调用,同时得到初版与融合两套
 * 分数/排名**。绝不为对比再解码/再分析一次(解码噪声会让对比不可信);
 * seamWeight 扫描是在同一份接缝分项上重算,不触发第二次分析。
 */
@HiltViewModel
class AlgoLabViewModel @Inject constructor(
    private val songDao: SongDao,
    private val scanRepository: ScanRepository,
    private val scanPreferences: ScanPreferences,
    private val pcmDecoder: PcmDecoder,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _ui = MutableStateFlow(
        LabUiState(
            exportDir = LabExporter.exportDirectory(appContext).absolutePath,
            scanFolder = scanPreferences.getScanFolders().firstOrNull() ?: defaultScanFolder(),
            hasAllFilesAccess = hasAllFilesAccess(),
        )
    )
    val ui: StateFlow<LabUiState> = _ui.asStateFlow()

    private val memoryProbe = MemoryProbe()

    @Volatile
    private var cancelRequested = false

    init {
        refreshSongs()
    }

    fun refreshSongs() {
        viewModelScope.launch {
            _ui.update { it.copy(songsLoading = true) }
            val songs = try {
                songDao.getAllSongsList()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            // CUE 分轨不是独立文件,实验端不支持(生产侧也有 CUE 守卫)。
            _ui.update {
                it.copy(
                    songs = songs.filter { song -> !song.isCue && song.filePath.isNotBlank() },
                    songsLoading = false,
                )
            }
        }
    }

    fun refreshPermissionState() {
        _ui.update { it.copy(hasAllFilesAccess = hasAllFilesAccess()) }
    }

    fun setScanFolder(value: String) {
        _ui.update { it.copy(scanFolder = value, scanStatus = "") }
    }

    /** 打开系统的「所有文件访问」授权页(algolab 是独立包,需单独授权)。 */
    fun requestAllFilesAccess() {
        val packageUri = Uri.parse("package:" + appContext.packageName)
        val appIntent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opened = runCatching { appContext.startActivity(appIntent) }.isSuccess
        if (!opened) {
            runCatching {
                appContext.startActivity(
                    Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.onFailure { error ->
                _ui.update { state ->
                    state.copy(scanStatus = "无法打开授权页面: ${error.message}")
                }
            }
        }
        _ui.update {
            it.copy(scanStatus = "授予「所有文件访问」后回到本页,点「重新检查权限」")
        }
    }

    /**
     * 用 ScanRepository/FileScanner 扫描一个目录,写入 algolab 自己的 songs 表。
     * 不启动正式版 MainActivity(algolab 清单已把它整体移除)。
     */
    fun startLibraryScan() {
        val state = _ui.value
        if (state.scanning || state.running) return
        if (!hasAllFilesAccess()) {
            _ui.update {
                it.copy(
                    hasAllFilesAccess = false,
                    scanStatus = "需要「所有文件访问」权限才能读取曲库目录",
                )
            }
            return
        }
        val folder = state.scanFolder.trim()
        if (folder.isEmpty() || !File(folder).isDirectory) {
            _ui.update { it.copy(scanStatus = "目录不存在或不可读: $folder") }
            return
        }
        _ui.update {
            it.copy(scanning = true, scanStatus = "扫描中…", scanCount = 0, error = null)
        }
        viewModelScope.launch {
            try {
                scanRepository.scanAndSave(
                    scanFolders = setOf(folder),
                    excludedFolders = emptySet(),
                    skipShortSongs = false,
                    skipAmrMid = true,
                    skipHiddenFolders = false,
                ).collect { status ->
                    when (status) {
                        is ScanStatus.Scanning -> _ui.update {
                            it.copy(
                                scanStatus = "扫描中: ${status.currentPath}",
                                scanCount = status.count,
                            )
                        }
                        is ScanStatus.Completed -> _ui.update {
                            it.copy(
                                scanStatus = "扫描完成: ${status.total} 首",
                                scanCount = status.total,
                            )
                        }
                        is ScanStatus.Error -> _ui.update {
                            it.copy(scanStatus = status.message)
                        }
                        ScanStatus.Idle -> Unit
                    }
                }
                scanPreferences.saveScanFolders(setOf(folder))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(scanStatus = "扫描失败: ${e.message}") }
            } finally {
                _ui.update { it.copy(scanning = false) }
                refreshSongs()
            }
        }
    }

    private fun defaultScanFolder(): String {
        val music = File(Environment.getExternalStorageDirectory(), "Music")
        return if (music.isDirectory) {
            music.absolutePath
        } else {
            Environment.getExternalStorageDirectory().absolutePath
        }
    }

    private fun hasAllFilesAccess(): Boolean = try {
        Environment.isExternalStorageManager()
    } catch (_: Exception) {
        false
    }

    fun selectSong(song: Song?) {
        _ui.update {
            it.copy(
                selectedSong = song,
                pathInput = song?.filePath ?: it.pathInput,
                error = null,
                statusMessage = null,
            )
        }
    }

    fun setPathInput(value: String) {
        _ui.update { it.copy(pathInput = value, error = null) }
    }

    fun updateParams(transform: (LabParams) -> LabParams) {
        _ui.update { it.copy(params = transform(it.params).sanitized(), error = null) }
    }

    fun setReferenceA(value: String) {
        _ui.update { it.copy(referenceAMs = value.filter { ch -> ch.isDigit() || ch == '.' || ch == '-' }) }
    }

    fun setReferenceB(value: String) {
        _ui.update { it.copy(referenceBMs = value.filter { ch -> ch.isDigit() || ch == '.' || ch == '-' }) }
    }

    fun setSortMode(mode: LabSortMode) {
        _ui.update { it.copy(sortMode = mode) }
    }

    fun setOnlyTopN(onlyTopN: Boolean) {
        _ui.update { it.copy(onlyTopN = onlyTopN) }
    }

    fun dismissError() {
        _ui.update { it.copy(error = null) }
    }

    fun dismissStatus() {
        _ui.update { it.copy(statusMessage = null) }
    }

    fun clearResult() {
        _ui.update { it.copy(result = null, exportFiles = emptyList(), progress = 0f, phase = "") }
    }

    fun cancel() {
        cancelRequested = true
        _ui.update { it.copy(phase = "正在取消…") }
    }

    fun analyze() {
        val state = _ui.value
        if (state.running) return
        if (state.scanning) {
            _ui.update { it.copy(error = "曲库扫描进行中,请等扫描结束再分析") }
            return
        }
        val path = resolvePath(state)
        if (path == null) {
            _ui.update { it.copy(error = "请先从曲库选择歌曲,或输入音频文件绝对路径") }
            return
        }
        if (!File(path).isFile) {
            _ui.update { it.copy(error = "文件不存在或不可读: $path") }
            return
        }
        cancelRequested = false
        memoryProbe.reset()
        viewModelScope.launch {
            val totalStart = SystemClock.elapsedRealtime()
            _ui.update {
                it.copy(
                    running = true,
                    progress = 0f,
                    phase = "解码中…",
                    error = null,
                    statusMessage = null,
                    result = null,
                    exportFiles = emptyList(),
                )
            }
            try {
                val decodeStart = SystemClock.elapsedRealtime()
                val mono = pcmDecoder.decodeMono(path, maxSeconds = MAX_DECODE_SECONDS) { fraction ->
                    if (cancelRequested) throw CancellationException("分析已取消")
                    memoryProbe.sample()
                    val f = fraction.coerceIn(0f, 1f)
                    _ui.update {
                        it.copy(
                            phase = "解码 ${(f * 100).roundToInt()}%",
                            progress = f * DECODE_PROGRESS,
                        )
                    }
                }
                memoryProbe.sample()
                if (cancelRequested) throw CancellationException("分析已取消")
                val decodeMs = SystemClock.elapsedRealtime() - decodeStart

                val params = _ui.value.params.sanitized()
                val analyzeStart = SystemClock.elapsedRealtime()
                val flat = withContext(Dispatchers.Default) {
                    val sink = ProgressSink { fraction ->
                        if (cancelRequested) throw CancellationException("分析已取消")
                        memoryProbe.sample()
                        val f = fraction.coerceIn(0f, 1f)
                        _ui.update {
                            it.copy(
                                phase = "分析 ${(f * 100).roundToInt()}%",
                                progress = DECODE_PROGRESS + f * (1f - DECODE_PROGRESS),
                            )
                        }
                    }
                    LabEngineJni.analyzeLab(
                        monoPcm = mono.data,
                        sampleRate = mono.sampleRate,
                        maxCandidates = params.maxCandidates,
                        seamEnabled = params.seamEnabled,
                        seamWeight = params.seamWeight,
                        corrWeight = params.corrWeight,
                        rmsWeight = params.rmsWeight,
                        fluxWeight = params.fluxWeight,
                        refineEnabled = params.refineEnabled,
                        refineRangeMs = params.refineRangeMs,
                        sink = sink,
                    )
                }
                memoryProbe.sample()
                if (cancelRequested) throw CancellationException("分析已取消")
                val analyzeMs = SystemClock.elapsedRealtime() - analyzeStart

                val raw = LabResultParser.parse(flat)
                    ?: throw IllegalStateException(
                        "Lab JNI 返回格式不受支持(长度=${flat.size},版本=${flat.getOrNull(0)})"
                    )
                if (raw.candidates.isEmpty()) {
                    throw IllegalStateException("未找到合适的循环点(候选为空)")
                }

                val weights = params.normalizedWeights()
                val summary = LabComparison.summarize(raw, params.maxCandidates)
                val sweep = LabComparison.sweep(raw, params, params.maxCandidates)
                val referenceA = _ui.value.referenceAMs.toDoubleOrNull()
                val referenceB = _ui.value.referenceBMs.toDoubleOrNull()
                val accuracy = if (referenceA != null && referenceB != null) {
                    LabComparison.accuracy(raw, referenceA, referenceB, mono.sampleRate)
                } else {
                    null
                }

                val songInfo = buildSongInfo(path, mono.sampleRate, mono.frames)
                val memory = memoryProbe.snapshot()
                val timing = LabTiming(
                    decodeMs = decodeMs,
                    analyzeMs = analyzeMs,
                    seamStageMs = raw.seamStageMs,
                    perCandidateMs = if (raw.seamEvaluatedCount > 0) {
                        raw.seamStageMs / raw.seamEvaluatedCount
                    } else {
                        Double.NaN
                    },
                    totalMs = SystemClock.elapsedRealtime() - totalStart,
                )
                val result = LabResult(
                    generatedAt = System.currentTimeMillis(),
                    song = songInfo,
                    params = params,
                    normalizedWeights = weights,
                    raw = raw,
                    timing = timing,
                    memory = memory,
                    summary = summary,
                    sweep = sweep,
                    accuracy = accuracy,
                )
                _ui.update {
                    it.copy(
                        running = false,
                        progress = 1f,
                        phase = "完成",
                        result = result,
                        statusMessage = "分析完成:候选池 ${raw.candidates.size},已评分拍对 ${raw.scoredPairs}",
                    )
                }
            } catch (e: CancellationException) {
                _ui.update {
                    it.copy(running = false, progress = 0f, phase = "", statusMessage = "已取消")
                }
            } catch (e: Exception) {
                _ui.update {
                    it.copy(
                        running = false,
                        progress = 0f,
                        phase = "",
                        error = e.message ?: e.javaClass.simpleName,
                    )
                }
            }
        }
    }

    fun export() {
        val result = _ui.value.result ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val output = LabExporter.write(appContext, result)
                _ui.update {
                    it.copy(
                        exportFiles = output.files.map { file -> file.absolutePath },
                        exportDir = output.files.firstOrNull()?.parentFile?.absolutePath
                            ?: it.exportDir,
                        statusMessage = "已导出 ${output.files.size} 个文件(JSON + 2 CSV)",
                        error = null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(error = "导出失败: ${e.message}") }
            }
        }
    }

    fun copyPath(path: String) {
        try {
            val manager = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return
            manager.setPrimaryClip(ClipData.newPlainText("AlgoLab export", path))
            _ui.update { it.copy(statusMessage = "路径已复制") }
        } catch (e: Exception) {
            _ui.update { it.copy(error = "复制失败: ${e.message}") }
        }
    }

    private fun resolvePath(state: LabUiState): String? {
        val typed = state.pathInput.trim()
        if (typed.isNotEmpty()) return typed
        return state.selectedSong?.filePath?.takeIf { it.isNotBlank() }
    }

    private fun buildSongInfo(path: String, sampleRate: Int, frames: Long): LabSongInfo {
        val selected = _ui.value.selectedSong
        val file = File(path)
        val durationMs = when {
            selected != null && selected.duration > 0L && selected.filePath == path -> selected.duration
            sampleRate > 0 -> frames * 1000L / sampleRate
            else -> 0L
        }
        return LabSongInfo(
            title = selected?.takeIf { it.filePath == path }?.title
                ?: file.nameWithoutExtension.ifBlank { file.name },
            artist = selected?.takeIf { it.filePath == path }?.artist.orEmpty(),
            album = selected?.takeIf { it.filePath == path }?.album.orEmpty(),
            path = path,
            durationMs = durationMs,
            sampleRate = sampleRate,
            frames = frames,
        )
    }

    /**
     * 内存峰值采样:解码/分析回调里各采一次(≥100ms),记录 Java heap、native heap、
     * PSS 的最大值。Android 没有"峰值"直读接口,这是可重复的近似口径。
     */
    private class MemoryProbe {
        private val sampleIntervalMs = 100L
        private var peakJava = 0L
        private var peakNative = 0L
        private var peakPss = 0
        private var sampled = false
        private var lastSampleMs = 0L

        @Synchronized
        fun reset() {
            peakJava = 0L
            peakNative = 0L
            peakPss = 0
            sampled = false
            lastSampleMs = 0L
        }

        @Synchronized
        fun sample() {
            val now = SystemClock.elapsedRealtime()
            if (sampled && now - lastSampleMs < sampleIntervalMs) return
            lastSampleMs = now
            try {
                val runtime = Runtime.getRuntime()
                val javaUsed = runtime.totalMemory() - runtime.freeMemory()
                val nativeUsed = Debug.getNativeHeapAllocatedSize()
                val info = Debug.MemoryInfo()
                Debug.getMemoryInfo(info)
                peakJava = maxOf(peakJava, javaUsed)
                peakNative = maxOf(peakNative, nativeUsed)
                peakPss = maxOf(peakPss, info.totalPss)
                sampled = true
            } catch (_: Throwable) {
                // 采样失败不影响分析本身。
            }
        }

        @Synchronized
        fun snapshot(): LabMemory =
            LabMemory(peakJava, peakNative, peakPss, sampled)
    }

    private companion object {
        const val MAX_DECODE_SECONDS = 900
        const val DECODE_PROGRESS = 0.30f
    }
}
