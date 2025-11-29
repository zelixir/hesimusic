## design 

本项目是一个Android音乐播放器, 用于播放设备本地储存的各种格式音乐, 并支持cue文件
目标是替换天天动听
本项目的绝大部分代码由AI生成

## 技术架构

*   **开发语言**: Kotlin
*   **UI框架**: Jetpack Compose (Material3)
*   **架构模式**: MVVM (Model-View-ViewModel) + Clean Architecture
*   **依赖注入**: Hilt
*   **本地数据库**: Room (SQLite)
*   **音频引擎**: AndroidX Media3 (ExoPlayer)
*   **异步处理**: Kotlin Coroutines & Flow
*   **图片加载**: Coil (用于加载专辑封面)

## 功能清单与实现方案

- [ ] **扫描本地音乐**
  - [ ] **权限管理**:
    - **核心策略**: 统一申请 `MANAGE_EXTERNAL_STORAGE` (所有文件访问权限)。
    - **原因**: 
      - 确保能稳定读取 `.cue` 文件 (在 Android 10+ 通常被视为非媒体文件)。
      - 简化权限逻辑，避免针对不同 Android 版本维护多套方案 (SAF vs MediaStore)。
      - 允许扫描任意深度的文件夹结构，不受系统媒体库扫描延迟的影响。
    - **实现流程**:
      1. 检查 `Environment.isExternalStorageManager()`。
      2. 若未授权，弹窗解释原因 ("需要完整访问权限以扫描 CUE 文件和音乐库")。
      3. 跳转至 `Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` 页面。
      4. 用户授权后开始扫描。
    - **注意**: 此权限在 Google Play 上架受限，但作为本地全能播放器 (类似文件管理器)，这是实现 CUE 完美支持的最优解。
  - [ ] **核心扫描逻辑 (Repository层)**:
    - **多线程/协程**: 使用 `Kotlin Coroutines` (`Dispatchers.IO`) 将扫描任务放入后台线程，避免阻塞主线程。
    - **Flow数据流**: 扫描过程通过 `Flow<ScanProgress>` 向外发射状态 (当前路径, 已扫描数量)。
    - **两步扫描策略 (为了完美支持CUE)**:
      1.  **预扫描**: 快速遍历所有文件夹，寻找 `.cue` 文件。解析 CUE，记录所有被 CUE 引用的音频文件路径 (存入 `HashSet`)。
      2.  **主扫描**: 再次遍历 (或在同一次遍历中处理)，对每个文件：
          - 若是 `.cue`: 解析出多个 `Song` 对象 (分轨)。
          - 若是音频文件: 检查是否在 `HashSet` 中。若不在，则视为独立歌曲，解析 `Song` 对象。
  - [ ] **元数据解析**:
    - **库**: `JAudiotagger`。
    - **编码识别**: 读取标签前，先读取文件头或 CUE 文本的前几行，使用 `juniversalchardet` 判断编码 (GBK/Big5/Shift-JIS)，再用对应编码读取，防止乱码。
  - [ ] **数据库写入**:
    - **批处理**: 不要每扫一首存一首。维护一个 `Buffer` (如 List<Song>, size=50)。
    - **事务**: 当 Buffer 满时，使用 Room 的 `@Transaction` 批量插入 (`insertAll`)，减少磁盘 I/O 开销，提高速度。
  - [ ] **UI同步 (ViewModel层)**:
    - 维护 `StateFlow<ScanUiState>`。
    - 状态包括: `Idle` (未开始), `Scanning` (包含 `currentPath`, `scannedCount`), `Success` (扫描完成, 显示结果), `Error`。
    - 扫描完成后，触发 `MediaScannerConnection.scanFile` 通知系统媒体库更新 (可选，便于其他App也能看到)。
  - [ ] **过滤机制**: 
    - 数据库查询层过滤时长 < 60s 的条目。
    - 文件遍历层过滤 `.nomedia` 文件夹及隐藏文件夹。
- [ ] **播放列表视图**
  - [ ] **实现技术**: 使用 Room 数据库进行高效查询和分组。
  - [ ] **视图分类**:
    - **全部歌曲**: `SELECT * FROM songs ORDER BY title`
    - **歌手列表**: `SELECT artist, count(*) FROM songs GROUP BY artist`
    - **专辑列表**: `SELECT album, artist, count(*) FROM songs GROUP BY album`
    - **文件夹**: 基于 `path` 字段进行层级归类。
  - [ ] **首字母索引**: 
    - 在扫描入库时，使用 `pinyin4j` 或类似库生成标题的拼音/首字母，存入数据库字段 `sort_key`。
    - 针对日文，尝试提取假名或统一归类到 "Other" 或根据汉字拼音处理。
- [ ] **播放功能**
  - [ ] **音频引擎**: 使用 `ExoPlayer`。
  - [ ] **CUE播放**: 使用 `MediaItem.Builder().setClippingConfiguration(...)` 设置起始和结束时间，实现分轨播放。
  - [ ] **后台播放**: 实现 `MediaSessionService`，处理 `MediaSession` 回调，支持通知栏控制、锁屏控制及蓝牙耳机指令。
  - [ ] **睡眠模式**: 使用 Kotlin Coroutines 的 `delay` 或 `Timer`，时间到后发送 `pause` 指令并停止 Service。
  - [ ] **均衡器**: 使用 ExoPlayer 的 `AudioProcessor` 或 Android 原生 `Equalizer` API。

## 暂不考虑支持的功能

* 歌词
* UI主题
* 启动时自动播放
* 启动时从中断处继续播放
* 播放统计
  


## 数据结构 (Room Entities)

### 歌曲 (Song)
对应数据库表 `songs`
*   `id`: Long (主键, 自增)
*   `title`: String (标题)
*   `artist`: String (歌手)
*   `album`: String (专辑)
*   `path`: String (文件绝对路径)
*   `displayName`: String (文件名)
*   `duration`: Long (时长, 毫秒)
*   `size`: Long (文件大小)
*   `mimeType`: String (MIME类型)
*   // CUE 特有字段
*   `isCueTrack`: Boolean (是否为CUE分轨)
*   `cueFilePath`: String? (所属CUE文件的路径, 若是普通文件则为null)
*   `startPosition`: Long (播放起始位置, 毫秒. 普通文件为0)
*   `endPosition`: Long (播放结束位置, 毫秒. 普通文件为文件总时长)
*   `trackIndex`: Int (音轨号)
*   // 排序与索引
*   `sortKey`: String (用于首字母索引的拼音/Key)
*   `dateAdded`: Long (入库时间)

### 播放列表 (Playlist)
对应数据库表 `playlists`
*   `id`: Long (主键)
*   `name`: String (列表名)
*   `createTime`: Long

### 播放列表条目 (PlaylistEntry)
对应数据库表 `playlist_entries` (多对多关系)
*   `playlistId`: Long
*   `songId`: Long
*   `sortOrder`: Int (在列表中的排序)

## 播放控制器 (Architecture)

### 架构设计
*   **Service层**: `MusicService` (继承 `MediaSessionService`)
    *   持有 `ExoPlayer` 实例。
    *   管理 `MediaSession`。
    *   处理音频焦点 (Audio Focus)。
    *   维护 "当前播放队列" (ExoPlayer 内部队列或自定义列表)。
*   **UI层**: `MusicViewModel`
    *   通过 `MediaController` 连接 Service。
    *   暴露 `StateFlow<PlaybackState>` 给 UI 观察 (包含当前歌曲、进度、播放状态)。
*   **交互接口**:
    *   `play(mediaItem)`
    *   `pause()` / `resume()`
    *   `skipToNext()` / `skipToPrevious()`
    *   `seekTo(position)`
    *   `setShuffleMode(boolean)` / `setRepeatMode(int)`
    *   `addToQueue(song)`

说明: 
*   **最近播放**: 使用 `DataStore` 或单独的数据库表 `history` 记录最近播放的歌曲 ID 列表。
*   **播放队列**: 运行时队列由 ExoPlayer 管理，UI 层仅负责同步显示和操作。

### 歌曲库
歌曲清单

### 扫描设置

### 播放控制器
当前播放歌曲id, 当前播放列表
当前播放模式, 均衡器设置


## 界面设计 (Jetpack Compose)

### 总体布局
*   使用 `Scaffold` 作为基础布局。
*   **导航**: 使用 `Navigation Compose` 管理页面跳转 (Library -> Player -> Scanner)。

### 歌曲库页面 (首页)
*   **组件**: `Pager` (配合 `TabRow`) 实现顶部4个Tab切换。
*   **Tabs**: 歌曲, 歌手, 专辑, 文件夹。
*   **列表**: 使用 `LazyColumn` 展示内容。
*   **底部栏**: `BottomAppBar` 或自定义 `Box` 放置播放状态栏。

### 播放状态栏控件
*   **位置**: 首页底部，固定悬浮。
*   **内容**: 
    *   左侧: 歌曲信息 (Column: Title, Artist)。
    *   右侧: 控制按钮 (Row: Play/Pause, Next, Menu)。
    *   底部: `LinearProgressIndicator` (高度2dp) 显示进度。
*   **交互**: 点击整体导航至 "正在播放页"。

### 睡眠模式对话框
*   **组件**: `AlertDialog` 或 `ModalBottomSheet`。
*   **逻辑**: 选择时间后，启动倒计时协程。

### 扫描音乐页面
*   **布局**: `Column`。
*   **设置项**: `Switch` 组件控制开关 (过滤短歌曲, 过滤格式等)。
*   **文件夹选择**: 点击弹出文件选择器或进入多级文件夹选择页。
*   **进度展示**: 扫描时显示 `LinearProgressIndicator` 和当前扫描路径文本。

### 通用列表控件 (Abstract List Component)
*   **实现**: 自定义 Composable `MusicListItem`。
*   **参数**: 
    *   `title`: String
    *   `subtitle`: String?
    *   `trailingContent`: @Composable () -> Unit (用于放置首字母索引或更多按钮)
    *   `onItemClick`: () -> Unit
*   **功能**:
    *   **首字母索引**: 使用 `LazyColumn` 的 `stickyHeader` 或右侧自定义 `Draggable` 索引条 (类似通讯录)。
    *   **多选模式**: 长按触发 `ViewModel` 状态切换，列表项变为带 `Checkbox` 的样式。

### 歌曲列表控件
*   基于 `MusicListItem`。
*   **状态**: 
    *   高亮当前播放歌曲 (字体变色或显示播放图标)。
*   **更多菜单**: 点击展开按钮弹出 `DropdownMenu` 或 `ModalBottomSheet`，提供 "下一首播放", "添加到队列", "查看详情" 等选项。

### 歌曲详情控件
*   **组件**: `Column` + `Text` (Key-Value 布局)。
*   展示元数据详情。

### 文件夹/歌手/专辑列表控件
*   基于 `MusicListItem`。
*   点击跳转到 "动态歌曲列表页面"，传递对应的 ID 或 Path 参数。

### 动态歌曲列表页面
*   **路由**: `route = "song_list/{type}/{value}"`
*   复用 "歌曲列表控件"。

### 正在播放页
*   **布局**: `Column` (全屏)。
*   **顶部**: TopBar (标题, 返回按钮)。
*   **中间**: `Pager` (4页: 封面/歌词, 歌曲列表, 详情, 均衡器)。
    *   *注: 原设计为4个Tab, 建议简化为左右滑动或底部Tab切换*。
*   **底部**: 播放控制区。
    *   `Slider`: 进度条 (支持拖动 seek)。
    *   `Row`: 播放控制按钮 (Shuffle, Prev, Play/Pause, Next, Repeat)。

### 均衡器控件
*   使用垂直 `Slider` 组代表不同频段。


