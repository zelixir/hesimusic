package com.zjr.hesimusic.ui.scan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
    private val repository: ScanRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ScanUiState())
    val uiState: StateFlow<ScanUiState> = _uiState.asStateFlow()

    private var timerJob: Job? = null
    private var startTime: Long = 0

    fun startScan() {
        if (_uiState.value.isScanning) return

        _uiState.value = ScanUiState(isScanning = true, statusMessage = "Starting...")
        startTime = System.currentTimeMillis()
        startTimer()

        viewModelScope.launch {
            repository.scanAndSave().collect { status ->
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
    val elapsedTimeMs: Long = 0
)
