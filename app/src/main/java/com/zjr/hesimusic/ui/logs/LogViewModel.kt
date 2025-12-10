package com.zjr.hesimusic.ui.logs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zjr.hesimusic.data.dao.LogDao
import com.zjr.hesimusic.data.model.LogEntry
import com.zjr.hesimusic.utils.AppLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LogViewModel @Inject constructor(
    private val logDao: LogDao,
    private val appLogger: AppLogger
) : ViewModel() {
    
    val logs: StateFlow<List<LogEntry>> = logDao.getAllLogs()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
    
    fun clearLogs() {
        viewModelScope.launch {
            appLogger.clearAllLogs()
            appLogger.info("LogViewModel", "All logs cleared by user")
        }
    }
}
