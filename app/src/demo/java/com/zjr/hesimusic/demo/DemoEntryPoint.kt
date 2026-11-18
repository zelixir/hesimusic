package com.zjr.hesimusic.demo

import com.zjr.hesimusic.data.dao.SongDao
import com.zjr.hesimusic.data.dao.SongLoopDao
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * demo 写库入口(仅 demo 源集)。
 *
 * DemoBootstrapProvider.onCreate 早于 Hilt Application 组件初始化,因此不能把 provider 标成
 * @AndroidEntryPoint;改为在后台线程用 [dagger.hilt.android.EntryPointAccessors] 懒解析并重试
 * (与 src/debug 的 DebugEntryPoint 同一模式)。
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface DemoEntryPoint {
    fun songDao(): SongDao

    fun songLoopDao(): SongLoopDao
}
