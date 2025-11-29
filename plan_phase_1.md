# 阶段一：基础架构与数据层

## 目标
搭建项目骨架，实现数据库设计，完成核心的文件扫描与 CUE 解析功能。

## 任务列表

### 1. 项目初始化
- [ ] 创建 Android 项目 (Kotlin, Jetpack Compose)。
- [ ] 配置 Gradle 依赖:
    - Hilt (DI)
    - Room (Database)
    - ExoPlayer (Media3)
    - Coil (Image Loading)
    - Accompanist (Permissions)
    - JAudiotagger (Metadata)
    - juniversalchardet (Encoding)
- [ ] 配置 Hilt Application 类与模块。

### 2. 数据库设计 (Room)
- [ ] 定义 `Song` Entity (包含 CUE 字段: `startPosition`, `endPosition`, `cueFilePath` 等)。
- [ ] 定义 `Playlist` 和 `PlaylistEntry` Entity。
- [ ] 实现 `SongDao` (支持批量插入, 按不同维度查询)。
- [ ] 配置 `AppDatabase`。

### 3. 权限管理
- [ ] 在 `AndroidManifest.xml` 中声明 `MANAGE_EXTERNAL_STORAGE`。
- [ ] 实现权限检查与申请逻辑 (跳转系统设置页)。

### 4. 核心扫描逻辑 (Repository)
- [ ] 实现 `CueParser`:
    - 读取 `.cue` 文件文本。
    - 使用 `juniversalchardet` 检测编码。
    - 解析 `FILE`, `TRACK`, `INDEX` 指令，生成 `CueTrack` 对象列表。
    - 使用双重状态机来解析(文件->行), 而不是正则表达式
- [ ] 实现 `FileScanner`:
    - 使用 `FileWalker` 遍历文件系统。
    - **预扫描**: 找出所有 `.cue` 文件并记录其引用的音频文件路径。
    - **主扫描**: 
        - 解析 `.cue` 生成分轨 `Song` 对象。
        - 解析普通音频文件 (排除已被 CUE 引用的) 生成 `Song` 对象。
    - 使用 `JAudiotagger` 读取元数据。
- [ ] 实现 `ScanRepository`:
    - 封装扫描逻辑，提供 `Flow<ScanProgress>`。
    - 实现批量写入数据库 (Transaction)。

### 5. 扫描 UI (初步)
- [ ] 创建 `ScanViewModel`。
- [ ] 创建简单的 `ScanScreen`:
    - 显示权限申请按钮。
    - 显示扫描进度条与文本。
    - 显示扫描结果统计。

## 验收标准
1.  App 启动后能成功引导用户开启 "所有文件访问权限"。
2.  点击扫描后，Logcat 或 UI 能显示正在扫描的文件路径。
3.  扫描完成后，使用 Database Inspector 查看 `songs` 表：
    - 能看到普通 MP3/FLAC 文件。
    - 能看到 CUE 文件被拆分为多行记录，且 `startPosition` 和 `endPosition` 正确。
    - 中文/日文歌曲标题无乱码。
