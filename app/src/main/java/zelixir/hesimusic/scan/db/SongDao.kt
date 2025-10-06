package zelixir.hesimusic.scan.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface SongDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSongs(songs: List<SongEntity>)

    @Query("UPDATE scan_state SET cursor_blob = :cursor, last_scan_completed_at = :completedAt WHERE id = :id")
    suspend fun updateScanStateCursor(id: Int, cursor: ByteArray?, completedAt: Long?)

    @Query("SELECT COUNT(*) FROM songs")
    suspend fun countSongs(): Int
}
