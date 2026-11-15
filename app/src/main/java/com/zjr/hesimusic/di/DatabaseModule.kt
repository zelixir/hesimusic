package com.zjr.hesimusic.di

import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.zjr.hesimusic.data.AppDatabase
import com.zjr.hesimusic.data.dao.FavoriteDao
import com.zjr.hesimusic.data.dao.HiddenSongDao
import com.zjr.hesimusic.data.dao.LogDao
import com.zjr.hesimusic.data.dao.PlaylistDao
import com.zjr.hesimusic.data.dao.SongDao
import com.zjr.hesimusic.data.dao.SongLoopDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    private const val TAG = "DatabaseModule"

    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            Log.d(TAG, "Running MIGRATION_1_2")
            val startTime = System.currentTimeMillis()
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS favorites (
                    filePath TEXT NOT NULL PRIMARY KEY,
                    dateAdded INTEGER NOT NULL
                )
                """.trimIndent()
            )
            val duration = System.currentTimeMillis() - startTime
            Log.d(TAG, "MIGRATION_1_2 completed in ${duration}ms")
        }
    }

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        Log.i(TAG, "Building AppDatabase")
        val startTime = System.currentTimeMillis()
        
        val database = Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "hesimusic_db"
        )
            .addMigrations(
                MIGRATION_1_2,
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6,
                AppDatabase.MIGRATION_6_7,
                AppDatabase.MIGRATION_7_8,
                AppDatabase.MIGRATION_8_9
            )
            .addCallback(object : RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    super.onCreate(db)
                    Log.i(TAG, "Database created for the first time")
                }
                
                override fun onOpen(db: SupportSQLiteDatabase) {
                    super.onOpen(db)
                    val openDuration = System.currentTimeMillis() - startTime
                    Log.i(TAG, "Database opened in ${openDuration}ms")
                    seedSeamlessSmartPlaylist(db)
                }
            })
            .build()
        
        val buildDuration = System.currentTimeMillis() - startTime
        Log.i(TAG, "AppDatabase build completed in ${buildDuration}ms")
        
        return database
    }

    /**
     * 内置智能歌单「无缝循环」= 已确认循环点(confirmed=1)的歌曲集合。
     *
     * 幂等:不存在才插入,存在则仅在 SQL 文本变化时更新;任何异常只记日志,绝不让 DB 打开失败。
     * 注意:智能歌单的 @RawQuery 未观察 SongLoop 表,confirmed 变化不会即时刷新该视图
     * (下一次 songs 侧变更或重启后刷新)。
     */
    private fun seedSeamlessSmartPlaylist(db: SupportSQLiteDatabase) {
        val sql = "SELECT * FROM songs WHERE id IN (SELECT songId FROM song_loop WHERE confirmed = 1)"
        try {
            db.execSQL(
                "INSERT INTO smart_playlists (name, createdAt, sqlText) " +
                    "SELECT '无缝循环', strftime('%s','now') * 1000, ? " +
                    "WHERE NOT EXISTS (SELECT 1 FROM smart_playlists WHERE name = '无缝循环')",
                arrayOf<Any>(sql),
            )
            db.execSQL(
                "UPDATE smart_playlists SET sqlText = ? " +
                    "WHERE name = '无缝循环' AND sqlText <> ?",
                arrayOf<Any>(sql, sql),
            )
        } catch (e: Exception) {
            Log.w(TAG, "seedSeamlessSmartPlaylist failed", e)
        }
    }

    @Provides
    fun provideSongDao(database: AppDatabase): SongDao {
        return database.songDao()
    }

    @Provides
    fun provideFavoriteDao(database: AppDatabase): FavoriteDao {
        return database.favoriteDao()
    }

    @Provides
    fun provideLogDao(database: AppDatabase): LogDao {
        return database.logDao()
    }

    @Provides
    fun providePlaylistDao(database: AppDatabase): PlaylistDao {
        return database.playlistDao()
    }

    @Provides
    fun provideHiddenSongDao(database: AppDatabase): HiddenSongDao {
        return database.hiddenSongDao()
    }

    @Provides
    fun provideSongLoopDao(database: AppDatabase): SongLoopDao {
        return database.songLoopDao()
    }
}
