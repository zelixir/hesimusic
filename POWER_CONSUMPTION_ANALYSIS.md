# 海瑟音乐 - 后台耗电分析报告

## 一、概述

本报告对海瑟音乐(HesiMusic)项目进行了深度代码分析，识别了所有可能导致后台耗电异常的代码模式，并提供了相应的优化方案。

## 二、后台耗电问题分析

### 问题1: MusicService 中的周期性状态保存 (高优先级)

**文件**: `app/src/main/java/com/zjr/hesimusic/service/MusicService.kt`

**问题代码** (第138-147行):
```kotlin
private fun startPeriodicSave() {
    serviceScope.launch {
        while (isActive) {
            if (player.isPlaying) {
                saveCurrentState()
            }
            delay(5000) // Save every 5 seconds
        }
    }
}
```

**问题分析**:
- 即使在后台运行，该协程也会每5秒执行一次状态保存
- `saveCurrentState()` 涉及 SharedPreferences 的 I/O 操作
- 这种高频率的磁盘写入会导致:
  - CPU 唤醒
  - 磁盘 I/O 开销
  - 电池消耗

**优化方案**:
```kotlin
private fun startPeriodicSave() {
    serviceScope.launch {
        while (isActive) {
            if (player.isPlaying) {
                saveCurrentState()
            }
            delay(30000) // 优化: 改为每30秒保存一次
        }
    }
}
```

或者更进一步的优化：
```kotlin
private var lastSavedPosition: Long = 0

private fun startPeriodicSave() {
    serviceScope.launch {
        while (isActive) {
            if (player.isPlaying) {
                val currentPosition = player.currentPosition
                // 仅当播放位置变化超过30秒才保存
                if (currentPosition - lastSavedPosition > 30000) {
                    saveCurrentState()
                    lastSavedPosition = currentPosition
                }
            }
            delay(30000)
        }
    }
}
```

---

### 问题2: MusicViewModel 中的进度更新循环 (高优先级)

**文件**: `app/src/main/java/com/zjr/hesimusic/ui/common/MusicViewModel.kt`

**问题代码** (第105-114行):
```kotlin
private fun startProgressUpdateLoop() {
    viewModelScope.launch {
        while (isActive) {
            if (mediaController?.isPlaying == true) {
                updateState()
            }
            delay(1000) // Update every second
        }
    }
}
```

**问题分析**:
- 每秒执行一次状态更新
- 即使应用在后台，该循环仍然运行
- `updateState()` 涉及多次 MediaController 的属性读取和 StateFlow 更新

**优化方案**:

方案A - 使用 ProcessLifecycleOwner 实现生命周期感知:
```kotlin
// 在 Application 或 ViewModel 中添加前台检测
class AppLifecycleObserver : DefaultLifecycleObserver {
    var isAppInForeground = false
        private set
    
    override fun onStart(owner: LifecycleOwner) {
        isAppInForeground = true
    }
    
    override fun onStop(owner: LifecycleOwner) {
        isAppInForeground = false
    }
}

// 在 HesiMusicApplication 中注册
class HesiMusicApplication : Application() {
    val lifecycleObserver = AppLifecycleObserver()
    
    override fun onCreate() {
        super.onCreate()
        ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)
    }
}

// 在 MusicViewModel 中使用
private fun startProgressUpdateLoop() {
    viewModelScope.launch {
        while (isActive) {
            val app = context.applicationContext as HesiMusicApplication
            if (mediaController?.isPlaying == true && app.lifecycleObserver.isAppInForeground) {
                updateState()
            }
            delay(1000)
        }
    }
}
```

方案B - 使用 Handler 配合 Lifecycle:
```kotlin
// 更简单的方案：在 Compose 中使用 LaunchedEffect 配合 lifecycle
@Composable
fun PlayerProgressTracker(viewModel: MusicViewModel) {
    val lifecycleOwner = LocalLifecycleOwner.current
    
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                viewModel.updateProgressIfPlaying()
                delay(1000)
            }
        }
    }
}
```

---

### 问题3: PlaybackPreferences 频繁使用 apply() (中优先级)

**文件**: `app/src/main/java/com/zjr/hesimusic/data/preferences/PlaybackPreferences.kt`

**问题代码** (第15-18, 30-31, 38-39行):
```kotlin
fun saveQueue(ids: List<Long>) {
    val idsString = ids.joinToString(",")
    prefs.edit().putString("queue_ids", idsString).apply()
}

fun saveCurrentSongIndex(index: Int) {
    prefs.edit().putInt("current_song_index", index).apply()
}

fun saveCurrentPosition(position: Long) {
    prefs.edit().putLong("current_position", position).apply()
}
```

**问题分析**:
- 每次保存都创建新的 Editor 对象
- 多次调用 `apply()` 会触发多次异步磁盘写入
- `saveCurrentState()` 会同时调用 `saveCurrentSongIndex` 和 `saveCurrentPosition`，导致两次独立的磁盘写入

**优化方案**:
```kotlin
fun savePlaybackState(index: Int, position: Long) {
    prefs.edit()
        .putInt("current_song_index", index)
        .putLong("current_position", position)
        .apply()  // 单次写入
}
```

---

### 问题4: ScanViewModel 中的高频计时器 (低优先级)

**文件**: `app/src/main/java/com/zjr/hesimusic/ui/scan/ScanViewModel.kt`

**问题代码** (第67-76行):
```kotlin
private fun startTimer() {
    timerJob?.cancel()
    timerJob = viewModelScope.launch {
        while (true) {
            val elapsed = System.currentTimeMillis() - startTime
            _uiState.value = _uiState.value.copy(elapsedTimeMs = elapsed)
            delay(100) // 0.1 second
        }
    }
}
```

**问题分析**:
- 每100ms更新一次UI状态
- 虽然扫描通常是前台操作，但如果用户切换到后台，该计时器仍然运行
- 高频率的状态更新可能导致不必要的重组

**优化方案**:
```kotlin
private fun startTimer() {
    timerJob?.cancel()
    timerJob = viewModelScope.launch {
        while (true) {
            val elapsed = System.currentTimeMillis() - startTime
            _uiState.value = _uiState.value.copy(elapsedTimeMs = elapsed)
            delay(1000) // 改为1秒更新一次，对于扫描进度显示足够
        }
    }
}
```

---

### 问题5: MusicService setupPlayerListeners 中的过度状态保存 (中优先级)

**文件**: `app/src/main/java/com/zjr/hesimusic/service/MusicService.kt`

**问题代码** (第118-135行):
```kotlin
private fun setupPlayerListeners() {
    player.addListener(object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            saveCurrentState()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            saveCurrentState()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            saveCurrentState()
        }
        
        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
             saveQueueState()
        }
    })
}
```

**问题分析**:
- 多个事件都会触发 `saveCurrentState()`
- 某些事件可能在短时间内连续触发（如播放状态变化时）
- 导致频繁的磁盘I/O操作

**优化方案**:
```kotlin
private var pendingSave = false

private fun saveCurrentStateDebounced() {
    if (!pendingSave) {
        pendingSave = true
        serviceScope.launch {
            delay(500) // 延迟500ms，合并多次保存请求
            saveCurrentState()
            pendingSave = false
        }
    }
}

private fun setupPlayerListeners() {
    player.addListener(object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            saveCurrentStateDebounced()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            saveCurrentStateDebounced()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            saveCurrentStateDebounced()
        }
        
        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            saveQueueState()
        }
    })
}
```

---

### 问题6: Equalizer 资源未正确管理 (低优先级，潜在内存泄漏)

**文件**: `app/src/main/java/com/zjr/hesimusic/ui/player/PlayerScreen.kt`

**问题代码** (第356-386行):
```kotlin
DisposableEffect(audioSessionId) {
    val eq = try {
        Equalizer(0, audioSessionId).apply {
            enabled = true
        }
    } catch (e: Exception) {
        ...
    }
    ...
    onDispose {
        eq?.release()  // 每次关闭对话框都释放均衡器
    }
}
```

**问题分析**:
- 均衡器在对话框中创建和释放，导致每次打开/关闭对话框都需要重新初始化
- 应该由 Service 层管理均衡器生命周期

**优化方案**:
将 Equalizer 移至 MusicService，作为单例管理:
```kotlin
// MusicService.kt
private var equalizer: Equalizer? = null

private fun setupEqualizer() {
    equalizer = try {
        Equalizer(0, player.audioSessionId).apply {
            enabled = true
        }
    } catch (e: Exception) {
        null
    }
}

override fun onDestroy() {
    equalizer?.release()
    ...
}
```

---

### 问题7: MusicViewModel 在初始化时过早连接 MediaController (低优先级)

**文件**: `app/src/main/java/com/zjr/hesimusic/ui/common/MusicViewModel.kt`

**问题代码** (第71-103行):
```kotlin
init {
    // ... 恢复播放状态
    
    val sessionToken = SessionToken(context, ComponentName(context, MusicService::class.java))
    mediaControllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
    mediaControllerFuture?.addListener({
        mediaController = mediaControllerFuture?.get()
        ...
        startProgressUpdateLoop()  // 立即启动进度更新循环
    }, MoreExecutors.directExecutor())
}
```

**问题分析**:
- ViewModel 初始化时立即启动进度更新循环
- 即使用户不在播放器页面，也会持续更新

**优化方案**:
使用懒加载模式，仅在需要时启动更新:
```kotlin
private var isProgressLoopStarted = false

fun ensureProgressLoop() {
    if (!isProgressLoopStarted) {
        isProgressLoopStarted = true
        startProgressUpdateLoop()
    }
}
```

---

### 问题8: 缺少 WakeLock 管理 (潜在问题)

**文件**: `app/src/main/java/com/zjr/hesimusic/service/MusicService.kt`

**问题分析**:
- 当前代码没有显式管理 WakeLock
- Media3 的 MediaSessionService 会自动管理部分 WakeLock
- 但如果有其他后台任务，可能导致不必要的唤醒

**建议**:
Media3 的 ExoPlayer 在播放音频时会自动管理 WakeLock。无需手动管理，但应确保：
1. 不要有其他后台任务（如周期性协程）阻止系统休眠
2. 使用 `setWakeMode(context, C.WAKE_MODE_NETWORK)` 或 `C.WAKE_MODE_LOCAL` 如果需要额外控制
3. 确保在 `MediaModule.kt` 中正确配置 ExoPlayer:

```kotlin
@Provides
@Singleton
fun provideExoPlayer(
    @ApplicationContext context: Context,
    audioAttributes: AudioAttributes
): ExoPlayer =
    ExoPlayer.Builder(context)
        .setAudioAttributes(audioAttributes, true)
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(context, C.WAKE_MODE_LOCAL) // 仅在本地播放时保持设备唤醒
        .build()
```

---

### 问题9: Flow 订阅未优化 (低优先级)

**文件**: `app/src/main/java/com/zjr/hesimusic/ui/library/LibraryViewModel.kt`

**问题代码** (第21-28行):
```kotlin
val songs: StateFlow<List<Song>> = repository.getAllSongs()
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

val artists: StateFlow<List<Artist>> = repository.getArtists()
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

val albums: StateFlow<List<Album>> = repository.getAlbums()
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
```

**问题分析**:
- `WhileSubscribed(5000)` 意味着在最后一个订阅者取消订阅后，Flow 会保持活跃5秒
- 这是合理的配置，但可以根据实际情况调整

**优化方案** (可选):
```kotlin
// 如果数据变化不频繁，可以使用 Lazily 或更长的超时
val songs: StateFlow<List<Song>> = repository.getAllSongs()
    .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
```

---

## 三、优化优先级总结

| 优先级 | 问题 | 预期改善 |
|--------|------|----------|
| 🔴 高 | 周期性状态保存 (5秒) | 减少80%的磁盘I/O |
| 🔴 高 | 进度更新循环 (1秒) | 减少后台CPU唤醒 |
| 🟡 中 | SharedPreferences 多次写入 | 减少50%的磁盘I/O |
| 🟡 中 | 事件监听器过度保存 | 减少冗余保存操作 |
| 🟢 低 | 扫描计时器 (100ms) | UI性能优化 |
| 🟢 低 | Equalizer 资源管理 | 避免内存泄漏 |

## 四、实施建议

### 第一阶段 - 立即修复 (高优先级)
1. 将周期性保存间隔从 5秒 改为 30秒
2. 添加后台检测，在应用后台时暂停进度更新循环

### 第二阶段 - 优化改进 (中优先级)
1. 合并 PlaybackPreferences 的多次写入操作
2. 添加保存状态的防抖机制

### 第三阶段 - 架构优化 (低优先级)
1. 将 Equalizer 移至 Service 层管理
2. 优化 Flow 订阅策略

## 五、测试建议

1. 使用 Android Profiler 监控后台电量消耗
2. 使用 Battery Historian 分析 WakeLock 使用情况
3. 通过 `adb shell dumpsys batterystats` 获取详细电量统计
4. 在后台播放音乐30分钟，对比优化前后的电量消耗

## 六、结论

主要的后台耗电问题集中在:
1. **过于频繁的周期性任务** - 每5秒保存状态、每1秒更新UI
2. **不必要的磁盘I/O** - 多次独立的 SharedPreferences 写入
3. **缺少生命周期感知** - 后台运行时仍执行前台任务

通过实施上述优化方案，预计可以显著降低后台耗电量。
