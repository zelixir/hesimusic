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

    @Query("DELETE FROM favorites WHERE filePath = :filePath AND startPosition = :startPosition")
    suspend fun deleteByFilePathAndStartPosition(filePath: String, startPosition: Long)

    @Query("SELECT * FROM favorites ORDER BY dateAdded DESC")
    fun getAllFavorites(): Flow<List<Favorite>>

    @Query("SELECT * FROM favorites")
    fun getAllFavoritesAsList(): Flow<List<Favorite>>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE filePath = :filePath AND startPosition = :startPosition)")
    fun isFavorite(filePath: String, startPosition: Long): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE filePath = :filePath AND startPosition = :startPosition)")
    suspend fun isFavoriteSync(filePath: String, startPosition: Long): Boolean
}
