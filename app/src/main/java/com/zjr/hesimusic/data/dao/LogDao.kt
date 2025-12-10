package com.zjr.hesimusic.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.zjr.hesimusic.data.model.LogEntry
import kotlinx.coroutines.flow.Flow

@Dao
interface LogDao {
    @Insert
    suspend fun insert(log: LogEntry)
    
    @Query("SELECT * FROM logs ORDER BY timestamp DESC, id DESC")
    fun getAllLogs(): Flow<List<LogEntry>>
    
    @Query("SELECT COUNT(*) FROM logs")
    suspend fun getLogCount(): Int
    
    @Query("""
        DELETE FROM logs 
        WHERE id IN (
            SELECT id FROM logs 
            ORDER BY timestamp ASC, id ASC 
            LIMIT :count
        )
    """)
    suspend fun deleteOldestLogs(count: Int)
    
    @Query("DELETE FROM logs")
    suspend fun deleteAllLogs()
}
