package com.zjr.hesimusic.ui.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zjr.hesimusic.data.scanner.TagDebugger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TagDebugViewModel @Inject constructor(
    private val tagDebugger: TagDebugger
) : ViewModel() {

    private val _uiState = MutableStateFlow<TagDebugUiState>(TagDebugUiState.Idle)
    val uiState = _uiState.asStateFlow()

    fun debugFile(path: String) {
        _uiState.value = TagDebugUiState.Loading
        viewModelScope.launch(Dispatchers.IO) {
            val result = tagDebugger.debug(path)
            _uiState.value = TagDebugUiState.Success(result)
        }
    }
}

sealed class TagDebugUiState {
    object Idle : TagDebugUiState()
    object Loading : TagDebugUiState()
    data class Success(val result: TagDebugger.DebugResult) : TagDebugUiState()
}
