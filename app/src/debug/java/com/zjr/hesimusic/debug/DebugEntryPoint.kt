package com.zjr.hesimusic.debug

import com.zjr.hesimusic.data.AppDatabase
import com.zjr.hesimusic.data.dao.SongLoopDao
import com.zjr.hesimusic.data.preferences.PlaybackPreferences
import com.zjr.hesimusic.data.repository.LibraryRepository
import com.zjr.hesimusic.data.repository.PlaylistRepository
import com.zjr.hesimusic.data.repository.SmartPlaylistRepository
import com.zjr.hesimusic.data.repository.SongRepository
import com.zjr.hesimusic.loop.LoopIntent
import com.zjr.hesimusic.loop.LoopRuntime
import com.zjr.hesimusic.loop.analysis.LoopAnalysisManager
import com.zjr.hesimusic.loop.analysis.PcmDecoder
import com.zjr.hesimusic.loop.audio.LoopAudioController
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * debug HTTP 服务的依赖入口。
 *
 * ContentProvider 的 onCreate 早于 Hilt Application 组件初始化(与 AdbDbProvider 同理),
 * 所以不在 provider 上标 @AndroidEntryPoint,而是由服务端在首次请求时经
 * EntryPointAccessors.fromApplication 懒获取(带重试,见 LoopDebugServer.deps)。
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface DebugEntryPoint {
    fun libraryRepository(): LibraryRepository
    fun songRepository(): SongRepository
    fun playlistRepository(): PlaylistRepository
    fun smartPlaylistRepository(): SmartPlaylistRepository
    fun playbackPreferences(): PlaybackPreferences
    fun loopAnalysisManager(): LoopAnalysisManager
    fun pcmDecoder(): PcmDecoder
    fun loopIntent(): LoopIntent
    fun loopRuntime(): LoopRuntime
    fun loopAudioController(): LoopAudioController

    /**
     * 主播放器实例（Hilt 单例）。
     *
     * 只给 PlaybackInvariantMonitor 用：MediaController 跨会话拿不到
     * MediaItem.localConfiguration.tag，必须直接读 ExoPlayer 才能判断当前 item
     * 是否带 LoopSourceItemTag。调用方必须在主线程使用（Hilt 首次创建时也会落在主线程）。
     */
    fun exoPlayer(): ExoPlayer

    fun songLoopDao(): SongLoopDao
    fun appDatabase(): AppDatabase
}
