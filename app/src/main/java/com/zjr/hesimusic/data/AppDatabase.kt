package com.zjr.hesimusic.data

import androidx.room.Database
import androidx.room.RoomDatabase
import com.zjr.hesimusic.data.dao.FavoriteDao
import com.zjr.hesimusic.data.dao.SongDao
import com.zjr.hesimusic.data.model.Favorite
import com.zjr.hesimusic.data.model.Playlist
import com.zjr.hesimusic.data.model.PlaylistEntry
import com.zjr.hesimusic.data.model.Song

@Database(
    entities = [Song::class, Playlist::class, PlaylistEntry::class, Favorite::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao
    abstract fun favoriteDao(): FavoriteDao
}
