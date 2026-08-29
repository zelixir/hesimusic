package com.zjr.hesimusic.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zjr.hesimusic.data.BackupRestoreManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.inject.Inject

@HiltViewModel
class BackupRestoreViewModel @Inject constructor(
    private val backupRestoreManager: BackupRestoreManager,
    @ApplicationContext private val context: Context
) : ViewModel() {
    private val _statusMessage = MutableStateFlow("请选择备份或还原操作")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    fun backupDatabase(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                val backupJson = backupRestoreManager.exportJson()
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
                backupRestoreManager.importJson(JSONObject(backupContent))
            }.onSuccess {
                _statusMessage.value = "数据库还原成功"
            }.onFailure {
                _statusMessage.value = "数据库还原失败: ${it.message}"
            }
        }
    }
}
