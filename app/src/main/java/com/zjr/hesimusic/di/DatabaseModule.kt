package com.zjr.hesimusic.di

import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.zjr.hesimusic.data.AppDatabase
import com.zjr.hesimusic.data.dao.FavoriteDao
import com.zjr.hesimusic.data.dao.LogDao
import com.zjr.hesimusic.data.dao.SongDao
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
            .addMigrations(MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    super.onCreate(db)
                    Log.i(TAG, "Database created for the first time")
                }
                
                override fun onOpen(db: SupportSQLiteDatabase) {
                    super.onOpen(db)
                    val openDuration = System.currentTimeMillis() - startTime
                    Log.i(TAG, "Database opened in ${openDuration}ms")
                }
            })
            .build()
        
        val buildDuration = System.currentTimeMillis() - startTime
        Log.i(TAG, "AppDatabase build completed in ${buildDuration}ms")
        
        return database
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
}
