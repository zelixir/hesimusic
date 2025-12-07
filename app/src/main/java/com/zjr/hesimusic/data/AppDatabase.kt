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
    version = 4,
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
            }
        }
    }
}
