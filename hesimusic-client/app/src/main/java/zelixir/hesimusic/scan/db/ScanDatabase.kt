package zelixir.hesimusic.scan.db

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import android.content.Context

@Database(entities = [SongEntity::class, ScanState::class, ScanSettingsEntity::class], version = 2)
abstract class ScanDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao

    companion object {
        private var INSTANCE: ScanDatabase? = null

        fun getInstance(context: Context): ScanDatabase {
            return INSTANCE ?: synchronized(this) {
                val inst = Room.databaseBuilder(context.applicationContext, ScanDatabase::class.java, "scan_db")
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = inst
                inst
            }
        }
    }
}

@androidx.room.Entity(tableName = "scan_state")
data class ScanState(
    @androidx.room.PrimaryKey val id: Int = 1,
    @androidx.room.ColumnInfo val cursor_blob: ByteArray?,
    @androidx.room.ColumnInfo val last_scan_started_at: Long?,
    @androidx.room.ColumnInfo val last_scan_completed_at: Long?,
    @androidx.room.ColumnInfo val total_files_seen: Long?
)
