package com.zjr.hesimusic.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.zjr.hesimusic.data.dao.FavoriteDao
import com.zjr.hesimusic.data.dao.SongDao
import com.zjr.hesimusic.data.model.Favorite
import com.zjr.hesimusic.data.model.Playlist
import com.zjr.hesimusic.data.model.PlaylistEntry
import com.zjr.hesimusic.data.model.Song

@Database(
    entities = [Song::class, Playlist::class, PlaylistEntry::class, Favorite::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao
    abstract fun favoriteDao(): FavoriteDao
    
    companion object {
        /**
         * Migration from version 2 to 3:
         * - Add startPosition column to favorites table to support CUE tracks
         * - Create new favorites table with composite primary key (filePath, startPosition)
         * - Migrate existing data by setting startPosition to 0 for all existing favorites
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
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
            }
        }
    }
}
