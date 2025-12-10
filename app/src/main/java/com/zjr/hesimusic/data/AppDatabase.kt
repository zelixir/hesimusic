package com.zjr.hesimusic.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.zjr.hesimusic.data.dao.FavoriteDao
import com.zjr.hesimusic.data.dao.LogDao
import com.zjr.hesimusic.data.dao.SongDao
import com.zjr.hesimusic.data.model.Favorite
import com.zjr.hesimusic.data.model.LogEntry
import com.zjr.hesimusic.data.model.Playlist
import com.zjr.hesimusic.data.model.PlaylistEntry
import com.zjr.hesimusic.data.model.Song

@Database(
    entities = [Song::class, Playlist::class, PlaylistEntry::class, Favorite::class, LogEntry::class],
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun logDao(): LogDao
    
    companion object {
        private const val TAG = "AppDatabase"
        
        /**
         * Migration from version 2 to 3:
         * - Add startPosition column to favorites table to support CUE tracks
         * - Create new favorites table with composite primary key (filePath, startPosition)
         * - Migrate existing data by setting startPosition to 0 for all existing favorites
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                android.util.Log.d(TAG, "Running MIGRATION_2_3")
                val startTime = System.currentTimeMillis()
                
                // Create new table with composite primary key
                db.execSQL("""
                    CREATE TABLE favorites_new (
                        filePath TEXT NOT NULL,
                        startPosition INTEGER NOT NULL DEFAULT 0,
                        dateAdded INTEGER NOT NULL,
                        PRIMARY KEY(filePath, startPosition)
                    )
                """.trimIndent())
                
                // Copy data from old table (all existing favorites get startPosition = 0)
                db.execSQL("""
                    INSERT INTO favorites_new (filePath, startPosition, dateAdded)
                    SELECT filePath, 0, dateAdded FROM favorites
                """.trimIndent())
                
                // Drop old table
                db.execSQL("DROP TABLE favorites")
                
                // Rename new table to original name
                db.execSQL("ALTER TABLE favorites_new RENAME TO favorites")
                
                val duration = System.currentTimeMillis() - startTime
                android.util.Log.d(TAG, "MIGRATION_2_3 completed in ${duration}ms")
            }
        }
        
        /**
         * Migration from version 3 to 4:
         * - Add titleInitial and folderPath columns to songs table for performance optimization
         * - Add indices on title, artist, album, filePath, titleInitial, and folderPath
         * 
         * Note: After migration, titleInitial and folderPath will be empty for existing songs.
         * Users should re-scan their music library to populate these fields and get full performance benefits.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                android.util.Log.d(TAG, "Running MIGRATION_3_4")
                val startTime = System.currentTimeMillis()
                
                // Add new columns
                db.execSQL("ALTER TABLE songs ADD COLUMN titleInitial TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE songs ADD COLUMN folderPath TEXT NOT NULL DEFAULT ''")
                
                // Create indices for performance
                db.execSQL("CREATE INDEX IF NOT EXISTS index_songs_title ON songs(title)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_songs_artist ON songs(artist)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_songs_album ON songs(album)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_songs_filePath ON songs(filePath)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_songs_titleInitial ON songs(titleInitial)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_songs_folderPath ON songs(folderPath)")
                
                val duration = System.currentTimeMillis() - startTime
                android.util.Log.d(TAG, "MIGRATION_3_4 completed in ${duration}ms")
            }
        }
        
        /**
         * Migration from version 4 to 5:
         * - Add logs table for application logging
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                android.util.Log.d(TAG, "Running MIGRATION_4_5")
                val startTime = System.currentTimeMillis()
                
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS logs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        level TEXT NOT NULL,
                        tag TEXT NOT NULL,
                        message TEXT NOT NULL
                    )
                """.trimIndent())
                
                // Create index on timestamp for efficient ordering
                db.execSQL("CREATE INDEX IF NOT EXISTS index_logs_timestamp ON logs(timestamp)")
                
                val duration = System.currentTimeMillis() - startTime
                android.util.Log.d(TAG, "MIGRATION_4_5 completed in ${duration}ms")
            }
        }
    }
}
