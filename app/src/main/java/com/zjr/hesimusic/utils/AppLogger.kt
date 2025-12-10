package com.zjr.hesimusic.utils

import android.util.Log
import com.zjr.hesimusic.data.dao.LogDao
import com.zjr.hesimusic.data.model.LogEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppLogger @Inject constructor(
    private val logDao: LogDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val maxLogCount = 10000
    
    enum class Level {
        DEBUG, INFO, WARNING, ERROR
    }
    
    fun debug(tag: String, message: String) {
        log(Level.DEBUG, tag, message)
    }
    
    fun info(tag: String, message: String) {
        log(Level.INFO, tag, message)
    }
    
    fun warning(tag: String, message: String) {
        log(Level.WARNING, tag, message)
    }
    
    fun error(tag: String, message: String, throwable: Throwable? = null) {
        val fullMessage = if (throwable != null) {
            "$message\n${throwable.stackTraceToString()}"
        } else {
            message
        }
        log(Level.ERROR, tag, fullMessage)
    }
    
    /**
     * Log a timing event with duration in milliseconds
     */
    fun timing(tag: String, operation: String, durationMs: Long) {
        info(tag, "$operation completed in ${durationMs}ms")
    }
    
    /**
     * Execute a block and log its execution time
     */
    inline fun <T> measureTime(tag: String, operation: String, block: () -> T): T {
        val startTime = System.currentTimeMillis()
        return try {
            block()
        } finally {
            val duration = System.currentTimeMillis() - startTime
            timing(tag, operation, duration)
        }
    }
    
    /**
     * Execute a suspend block and log its execution time
     */
    suspend inline fun <T> measureTimeSuspend(tag: String, operation: String, crossinline block: suspend () -> T): T {
        val startTime = System.currentTimeMillis()
        return try {
            block()
        } finally {
            val duration = System.currentTimeMillis() - startTime
            timing(tag, operation, duration)
        }
    }
    
    private fun log(level: Level, tag: String, message: String) {
        // Also log to Android logcat
        when (level) {
            Level.DEBUG -> Log.d(tag, message)
            Level.INFO -> Log.i(tag, message)
            Level.WARNING -> Log.w(tag, message)
            Level.ERROR -> Log.e(tag, message)
        }
        
        // Store in database
        scope.launch {
            try {
                val logEntry = LogEntry(
                    timestamp = System.currentTimeMillis(),
                    level = level.name,
                    tag = tag,
                    message = message
                )
                logDao.insert(logEntry)
                
                // Check if we need to cleanup old logs
                val currentCount = logDao.getLogCount()
                if (currentCount > maxLogCount) {
                    val deleteCount = currentCount - maxLogCount
                    logDao.deleteOldestLogs(deleteCount)
                }
            } catch (e: Exception) {
                // Fallback to logcat only if database write fails
                Log.e("AppLogger", "Failed to write log to database", e)
            }
        }
    }
    
    suspend fun clearAllLogs() {
        logDao.deleteAllLogs()
    }
}
