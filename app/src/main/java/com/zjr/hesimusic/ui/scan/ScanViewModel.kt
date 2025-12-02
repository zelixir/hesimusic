package com.zjr.hesimusic.ui.scan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zjr.hesimusic.data.preferences.ScanPreferences
import com.zjr.hesimusic.data.repository.ScanRepository
import com.zjr.hesimusic.data.repository.ScanStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ScanViewModel @Inject constructor(
    private val repository: ScanRepository,
    private val scanPreferences: ScanPreferences
) : ViewModel() {

    private val _uiState = MutableStateFlow(ScanUiState())
    val uiState: StateFlow<ScanUiState> = _uiState.asStateFlow()

    private var timerJob: Job? = null
    private var startTime: Long = 0

    init {
        loadFolderSettings()
    }

    private fun loadFolderSettings() {
        _uiState.value = _uiState.value.copy(
            scanFolders = scanPreferences.getScanFolders(),
            excludedFolders = scanPreferences.getExcludedFolders()
        )
    }

    fun updateScanFolders(folders: Set<String>) {
        scanPreferences.saveScanFolders(folders)
        _uiState.value = _uiState.value.copy(scanFolders = folders)
    }

    fun updateExcludedFolders(folders: Set<String>) {
        scanPreferences.saveExcludedFolders(folders)
        _uiState.value = _uiState.value.copy(excludedFolders = folders)
    }

    fun startScan() {
        if (_uiState.value.isScanning) return
        
        val scanFolders = _uiState.value.scanFolders
        if (scanFolders.isEmpty()) {
            _uiState.value = _uiState.value.copy(
                statusMessage = "请先选择要扫描的文件夹"
            )
            return
        }

        _uiState.value = _uiState.value.copy(
            isScanning = true, 
            statusMessage = "Starting..."
        )
        startTime = System.currentTimeMillis()
        startTimer()

        val excludedFolders = _uiState.value.excludedFolders

        viewModelScope.launch {
            repository.scanAndSave(scanFolders, excludedFolders).collect { status ->
                when (status) {
                    is ScanStatus.Idle -> {
                        // Should not happen during scan
                    }
                    is ScanStatus.Scanning -> {
                        _uiState.value = _uiState.value.copy(
                            statusMessage = "Scanning...",
                            scannedCount = status.count,
                            currentPath = status.currentPath
                        )
                    }
                    is ScanStatus.Completed -> {
                        stopTimer()
                        _uiState.value = _uiState.value.copy(
                            isScanning = false,
                            statusMessage = "Scan completed. Found ${status.total} songs.",
                            scannedCount = status.total
                        )
                    }
                    is ScanStatus.Error -> {
                        stopTimer()
                        _uiState.value = _uiState.value.copy(
                            isScanning = false,
                            statusMessage = status.message
                        )
                    }
                }
            }
        }
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (true) {
                val elapsed = System.currentTimeMillis() - startTime
                _uiState.value = _uiState.value.copy(elapsedTimeMs = elapsed)
                delay(100) // 0.1 second
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
    }

    fun clearDatabase() {
        viewModelScope.launch {
            repository.clearDatabase()
            _uiState.value = _uiState.value.copy(
                scannedCount = 0,
                statusMessage = "Database cleared.",
                currentPath = "",
                elapsedTimeMs = 0
            )
        }
    }
}

data class ScanUiState(
    val isScanning: Boolean = false,
    val statusMessage: String = "Ready to scan",
    val scannedCount: Int = 0,
    val currentPath: String = "",
    val elapsedTimeMs: Long = 0,
    val scanFolders: Set<String> = emptySet(),
    val excludedFolders: Set<String> = emptySet()
)
