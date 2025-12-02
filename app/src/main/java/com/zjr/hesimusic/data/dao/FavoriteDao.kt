package com.zjr.hesimusic.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.zjr.hesimusic.data.model.Favorite
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(favorite: Favorite)

    @Delete
    suspend fun delete(favorite: Favorite)

    @Query("DELETE FROM favorites WHERE filePath = :filePath")
    suspend fun deleteByFilePath(filePath: String)

    @Query("SELECT * FROM favorites ORDER BY dateAdded DESC")
    fun getAllFavorites(): Flow<List<Favorite>>

    @Query("SELECT filePath FROM favorites")
    fun getAllFavoritePaths(): Flow<List<String>>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE filePath = :filePath)")
    fun isFavorite(filePath: String): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE filePath = :filePath)")
    suspend fun isFavoriteSync(filePath: String): Boolean
}
