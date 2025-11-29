package com.zjr.hesimusic.ui.scan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zjr.hesimusic.data.repository.ScanRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ScanViewModel @Inject constructor(
    private val repository: ScanRepository
) : ViewModel() {

    private val _scanStatus = MutableStateFlow("Ready to scan")
    val scanStatus: StateFlow<String> = _scanStatus

    fun startScan() {
        viewModelScope.launch {
            repository.scanAndSave().collect { status ->
                _scanStatus.value = status
            }
        }
    }
}
