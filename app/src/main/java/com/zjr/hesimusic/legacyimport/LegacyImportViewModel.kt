package com.zjr.hesimusic.legacyimport

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zjr.hesimusic.legacyimport.LegacyExportBundle
import com.zjr.hesimusic.legacyimport.LegacyImportException
import com.zjr.hesimusic.legacyimport.LegacyImportMode
import com.zjr.hesimusic.legacyimport.LegacyImportOptions
import com.zjr.hesimusic.legacyimport.LegacyImportResult
import com.zjr.hesimusic.legacyimport.LegacyImportSourceReader
import com.zjr.hesimusic.legacyimport.LegacyDataImporter
import com.zjr.hesimusic.utils.AppLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 导入页的阶段(UI 只按这个分支,不读 bundle 内部细节)。 */
enum class LegacyImportPhase {
    /** 还没选文件。 */
    EMPTY,

    /** 正在读/解析导出文件。 */
    LOADING,

    /** 文件已解析,显示预览。 */
    READY,

    /** 正在写库。 */
    IMPORTING,

    /** 导入成功。 */
    DONE,

    /** 失败(读文件/解析/写库)。 */
    FAILED,
}

data class LegacyImportUiState(
    val phase: LegacyImportPhase = LegacyImportPhase.EMPTY,
    val fileName: String? = null,
    val sourceInfo: String? = null,
    val contentsInfo: String? = null,
    val mode: LegacyImportMode = LegacyImportMode.EVERYTHING,
    val overwriteLoops: Boolean = false,
    val importSettings: Boolean = true,
    val previewLines: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val resultLines: List<String> = emptyList(),
    val backupPath: String? = null,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
) {
    val busy: Boolean get() = phase == LegacyImportPhase.LOADING || phase == LegacyImportPhase.IMPORTING
    val hasBundle: Boolean get() = phase == LegacyImportPhase.READY ||
        phase == LegacyImportPhase.IMPORTING || phase == LegacyImportPhase.DONE
}

/**
 * 「导入上一版数据」页的状态机。
 *
 * 只做三件事:把用户选的文件读成 [LegacyExportBundle]、按选项算预览、按选项执行导入。
 * 所有重活(解析/计划/事务)都在同一个 legacyimport 包里,这里只负责串起来。
 */
@HiltViewModel
class LegacyImportViewModel @Inject constructor(
    private val sourceReader: LegacyImportSourceReader,
    private val dataImporter: LegacyDataImporter,
    private val appLogger: AppLogger,
) : ViewModel() {

    private val _state = MutableStateFlow(LegacyImportUiState())
    val state: StateFlow<LegacyImportUiState> = _state.asStateFlow()

    private var bundle: LegacyExportBundle? = null

    /** adb 演练用:文件解析完立即开始导入(仅 debug 构建会置 true,见 LegacyImportActivity)。 */
    private var autoImportPending = false

    /** 用户选了一个目录:自动取里面最新的一份导出。 */
    fun openDirectory(treeUri: Uri) {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                phase = LegacyImportPhase.LOADING,
                statusMessage = "正在查找目录里的导出文件…",
                errorMessage = null,
            )
            try {
                sourceReader.persistReadPermission(treeUri)
                val candidate = sourceReader.findNewestBundle(treeUri)
                    ?: throw LegacyImportException("该目录里没有 *.json 导出文件(应为 legacy-export.json)")
                loadCandidate(candidate.uri, candidate.pathLabel + "/" + candidate.displayName)
            } catch (e: Exception) {
                fail("读取目录失败", e)
            }
        }
    }

    /** 用户直接选了一个 JSON 文件。 */
    fun openFile(uri: Uri, autoImport: Boolean = false) {
        autoImportPending = autoImport
        viewModelScope.launch {
            _state.value = _state.value.copy(
                phase = LegacyImportPhase.LOADING,
                statusMessage = "正在解析导出文件…",
                errorMessage = null,
            )
            try {
                loadCandidate(uri, sourceReader.displayName(uri))
            } catch (e: Exception) {
                fail("读取文件失败", e)
            }
        }
    }

    /** adb `am start -d file:///sdcard/...` 或测试用:直接按绝对路径打开。 */
    fun openPath(path: String, autoImport: Boolean = false) {
        autoImportPending = autoImport
        viewModelScope.launch {
            _state.value = _state.value.copy(
                phase = LegacyImportPhase.LOADING,
                statusMessage = "正在解析导出文件…",
                errorMessage = null,
            )
            try {
                val parsed = sourceReader.readFromPath(path)
                applyBundle(parsed, path.substringAfterLast('/'))
            } catch (e: Exception) {
                fail("读取文件失败", e)
            }
        }
    }

    private suspend fun loadCandidate(uri: Uri, displayName: String) {
        val parsed = sourceReader.read(uri)
        applyBundle(parsed, displayName)
    }

    private suspend fun applyBundle(parsed: LegacyExportBundle, displayName: String) {
        bundle = parsed
        _state.value = _state.value.copy(
            phase = LegacyImportPhase.READY,
            fileName = displayName,
            sourceInfo = parsed.describeSource(),
            contentsInfo = parsed.describeContents(),
            statusMessage = null,
            errorMessage = null,
            resultLines = emptyList(),
            backupPath = null,
            warnings = parsed.warnings,
        )
        refreshPreview()
        if (autoImportPending) {
            autoImportPending = false
            startImport()
        }
    }

    fun setMode(mode: LegacyImportMode) {
        if (_state.value.mode == mode) return
        _state.value = _state.value.copy(mode = mode)
        viewModelScope.launch { refreshPreview() }
    }

    fun setOverwriteLoops(value: Boolean) {
        if (_state.value.overwriteLoops == value) return
        _state.value = _state.value.copy(overwriteLoops = value)
        viewModelScope.launch { refreshPreview() }
    }

    fun setImportSettings(value: Boolean) {
        if (_state.value.importSettings == value) return
        _state.value = _state.value.copy(importSettings = value)
        viewModelScope.launch { refreshPreview() }
    }

    private suspend fun refreshPreview() {
        val current = bundle ?: return
        try {
            val preview = dataImporter.preview(current, options())
            _state.value = _state.value.copy(
                phase = LegacyImportPhase.READY,
                previewLines = preview.summaryLines(),
                warnings = preview.warnings,
            )
        } catch (e: Exception) {
            fail("生成预览失败", e)
        }
    }

    /** 开始导入。 */
    fun startImport() {
        val current = bundle ?: return
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.value = _state.value.copy(
                phase = LegacyImportPhase.IMPORTING,
                statusMessage = "正在导入(写库前会自动备份本机数据库)…",
                errorMessage = null,
                resultLines = emptyList(),
            )
            try {
                val options = options()
                val result = dataImporter.import(current, options)
                _state.value = _state.value.copy(
                    phase = LegacyImportPhase.DONE,
                    statusMessage = if (result.isNoop) "没有需要导入的新数据(已是最新)" else "导入完成",
                    resultLines = describeResult(result, options),
                    backupPath = result.backupPath,
                    warnings = result.warnings,
                    errorMessage = null,
                )
                appLogger.info(
                    TAG,
                    "导入上一版数据: " + (if (result.isNoop) "无新增" else "完成") +
                        " 用时 " + result.durationMs + "ms" +
                        " 备份=" + (result.backupPath ?: "无") +
                        " 设置=" + result.settingsApplied,
                )
            } catch (e: Exception) {
                fail("导入失败", e)
            }
        }
    }

    private fun describeResult(result: LegacyImportResult, options: LegacyImportOptions): List<String> {
        val lines = result.summaryLines(options).toMutableList()
        lines += "用时 " + result.durationMs + " ms"
        if (result.settingsApplied > 0) {
            lines += "已写入设置 " + result.settingsApplied + " 项"
        }
        lines += if (result.backupPath != null) {
            "导入前备份: " + result.backupPath
        } else {
            "未生成本机备份(数据库尚未落盘或无需写入)"
        }
        return lines
    }

    private fun fail(prefix: String, e: Exception) {
        val message = when (e) {
            is LegacyImportException -> e.message ?: prefix
            else -> prefix + ": " + (e.message ?: e.toString())
        }
        appLogger.error(TAG, prefix, e)
        _state.value = _state.value.copy(
            phase = LegacyImportPhase.FAILED,
            statusMessage = null,
            errorMessage = message,
        )
    }

    private fun options(): LegacyImportOptions = LegacyImportOptions(
        mode = _state.value.mode,
        overwriteExistingLoops = _state.value.overwriteLoops,
        importSettings = _state.value.importSettings,
    )

    private companion object {
        const val TAG = "LegacyImport"
    }
}
