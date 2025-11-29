# 阶段二：核心播放功能

## 目标
实现基于 ExoPlayer 的音频播放引擎，支持后台播放与通知栏控制。

## 任务列表

### 1. 音频服务 (Service)
- [ ] 创建 `MusicService` 继承自 `MediaSessionService`。
- [ ] 配置 `ExoPlayer` 实例 (Hilt Singleton)。
- [ ] 实现 `MediaSession.Callback`:
    - 处理 `onAddMediaItems`, `onPlay`, `onPause`, `onSkipToNext` 等回调。
- [ ] 在 `AndroidManifest.xml` 注册 Service。

### 2. 播放逻辑实现
- [ ] 实现 `MediaSource` 构建逻辑:
    - 普通歌曲: 直接使用 `MediaItem.fromUri`。
    - CUE 分轨: 使用 `MediaItem.Builder().setClippingConfiguration(...)` 设置起止时间。
- [ ] 实现播放队列管理:
    - 维护当前播放列表。
    - 支持 顺序/随机/单曲循环 模式切换。

### 3. 客户端连接 (ViewModel)
- [ ] 在 `MusicViewModel` 中连接 `MediaController`。
- [ ] 封装 `PlaybackState` (当前歌曲, 播放状态, 进度, 缓冲) 为 `StateFlow`。
- [ ] 实现控制方法: `play`, `pause`, `seek`, `skip`。

### 4. 简易播放器 UI (验证用)
- [ ] 在 `ScanScreen` 或新页面添加播放控制条。
- [ ] 显示当前播放歌曲信息。
- [ ] 提供 播放/暂停 按钮。

## 验收标准
1.  点击数据库中的任意歌曲 (包括 CUE 分轨) 能开始播放。
2.  CUE 分轨播放时，进度条显示的是分轨的时长，而不是整个文件的时长。
3.  CUE 分轨播放结束后，能自动切换到下一轨。
4.  App 退到后台，音乐继续播放。
5.  系统通知栏出现媒体控制器，能控制暂停和切歌。
