package com.zjr.hesimusic.adb

import com.zjr.hesimusic.data.AppDatabase
import com.zjr.hesimusic.data.BackupRestoreManager
import com.zjr.hesimusic.data.repository.SmartPlaylistRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * AdbDbProvider 的依赖入口。
 * Hilt 不支持 ContentProvider 的 @AndroidEntryPoint（Provider 创建时机早于组件初始化），
 * 因此通过 EntryPointAccessors 手动获取依赖。
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AdbDbEntryPoint {
    fun appDatabase(): AppDatabase
    fun backupRestoreManager(): BackupRestoreManager
    fun smartPlaylistRepository(): SmartPlaylistRepository
}
