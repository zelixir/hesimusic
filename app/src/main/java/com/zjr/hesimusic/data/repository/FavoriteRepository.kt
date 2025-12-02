package com.zjr.hesimusic.data.repository

import com.zjr.hesimusic.data.dao.FavoriteDao
import com.zjr.hesimusic.data.model.Favorite
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class FavoriteRepository @Inject constructor(
    private val favoriteDao: FavoriteDao
) {
    fun getAllFavoritePaths(): Flow<List<String>> = favoriteDao.getAllFavoritePaths()

    fun isFavorite(filePath: String): Flow<Boolean> = favoriteDao.isFavorite(filePath)

    suspend fun isFavoriteSync(filePath: String): Boolean = favoriteDao.isFavoriteSync(filePath)

    suspend fun addFavorite(filePath: String) {
        favoriteDao.insert(Favorite(filePath = filePath))
    }

    suspend fun removeFavorite(filePath: String) {
        favoriteDao.deleteByFilePath(filePath)
    }

    suspend fun toggleFavorite(filePath: String): Boolean {
        return if (favoriteDao.isFavoriteSync(filePath)) {
            favoriteDao.deleteByFilePath(filePath)
            false
        } else {
            favoriteDao.insert(Favorite(filePath = filePath))
            true
        }
    }
}
