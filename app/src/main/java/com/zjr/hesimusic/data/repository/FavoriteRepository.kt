package com.zjr.hesimusic.data.repository

import com.zjr.hesimusic.data.dao.FavoriteDao
import com.zjr.hesimusic.data.model.Favorite
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class FavoriteRepository @Inject constructor(
    private val favoriteDao: FavoriteDao
) {
    fun getAllFavorites(): Flow<List<Favorite>> = favoriteDao.getAllFavoritesAsList()

    fun isFavorite(filePath: String, startPosition: Long): Flow<Boolean> = 
        favoriteDao.isFavorite(filePath, startPosition)

    suspend fun isFavoriteSync(filePath: String, startPosition: Long): Boolean = 
        favoriteDao.isFavoriteSync(filePath, startPosition)

    suspend fun addFavorite(filePath: String, startPosition: Long) {
        favoriteDao.insert(Favorite(filePath = filePath, startPosition = startPosition))
    }

    suspend fun removeFavorite(filePath: String, startPosition: Long) {
        favoriteDao.deleteByFilePathAndStartPosition(filePath, startPosition)
    }

    suspend fun toggleFavorite(filePath: String, startPosition: Long): Boolean {
        return if (favoriteDao.isFavoriteSync(filePath, startPosition)) {
            favoriteDao.deleteByFilePathAndStartPosition(filePath, startPosition)
            false
        } else {
            favoriteDao.insert(Favorite(filePath = filePath, startPosition = startPosition))
            true
        }
    }
}
