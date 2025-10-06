# 扫描音乐实现方案

## 概述

本文件详细描述音乐扫描功能的实现方案。目标是高性能、可靠地扫描设备本地音乐文件，支持常见音频格式与 CUE 文件，正确解析元数据（ID3、Vorbis Comment、APE 等），自动识别非 UTF-8 的中文/日文编码，支持用户配置过滤规则（最短时长、忽略格式、忽略隐藏/指定文件夹等），并在扫描过程中提供实时进度与可中断/恢复能力。


## 功能拆分（现有库 vs 自行实现）

- 使用现有库（优先）
  - 媒体元数据解析
    - 使用 Apache Tika (Java) 或者 mp3agic / jaudiotagger 来解析 ID3 标签。推荐使用 `androidx.media3` 依赖自带的 `Metadata` 解析结合 `jaudiotagger` 作为补充解析工具，以覆盖更多格式。
  - 音频时长与码率
    - 使用 `MediaMetadataRetriever`（Android SDK）或者 `MediaExtractor`（androidx.media3）读取音频时长和音频轨道信息。
  - CUE 文件解析
    - 使用开源 CUE 解析库（Java/CueParser）如果可用；若无合适库，CUE 文件语法简单，采用自研解析器实现（见下）。
  - 字符编码检测
    - 使用 `juniversalchardet`（Mozilla's Universal Charset Detector 的 Java 版）或 `ICU4J` 来检测文本（CUE、文件名、标签字段）编码。
  - SQLite 持久化
    - 使用 Android 的 `Room` 作为 SQLite ORM（更安全、类型化），或直接使用 `SQLiteOpenHelper`。
  - 后台定时/延时扫描
    - 使用 Android 的 `WorkManager` 进行持久化、可重试、可约束的后台任务（适合在设备空闲或充电时运行）。

- 需要自行开发/集成的部分
  - 高效文件系统扫描器
    - 需要实现跨存储卷、支持外置 SD 卡、支持大规模文件集（10k+）的高效遍历策略（递归 + 排除策略 + 并发控制），并处理路径权限和 SAF（Storage Access Framework）与 Android 11+ 的分区存储限制。
  - 智能过滤与编码修正
    - 对标签/文件名的汉字、假名、拼音规则需要自定义逻辑：例如序号排除、中文拼音优先、日文假名索引映射等。
  - CUE 与大文件（单文件多轨）映射逻辑
    - 在遇到单个大文件（例如 FLAC + CUE）时，需要将单一媒体文件与其 CUE 描述的多轨生成多个逻辑歌曲条目（分片索引）并计算每轨的开始/结束时间与 id 生成策略。
  - 扫描结果的去重/冲突解决
    - 文件重命名/移动时的去重策略、基于文件指纹（hash）或文件路径+文件大小+修改时间的匹配算法需要自研。
  - 扫描进度与中断/恢复
    - 在扫描中断后（例如设备重启、权限变化），能够增量恢复扫描进度并继续，而不是每次重头开始。需要设计增量扫描与事务性写入策略。


## 技术路线与原理

### 1) 扫描触发与任务管理

- 使用 `WorkManager` 调度扫描任务。
  - 支持立即扫描（手动触发）与周期/按需扫描（例如首次安装、媒体变更监听、用户请求）。
  - 扫描任务设置为可中断、可重试，并在任务中持久化“扫描游标/状态”以便恢复。
- 媒体库监听：在可能的 Android 版本上订阅系统的媒体扫描广播/MediaStore 变更事件以触发增量扫描。


### 2) 文件系统遍历策略

- 初始阶段：获取所有挂载的存储根路径（内部存储、外置 SD、USB OTG），为每个根路径启动独立的遍历器（线程/协程）。
- 遍历实现：基于 BFS + 线程池的并发遍历，限制并发目录读取数（例如 4-8）以避免 I/O 瓶颈。
- 排除规则：在遍历时即时过滤（基于用户配置）：隐藏文件夹、黑名单路径、黑名单扩展名（amr, mid），最短时长阈值（可在读取元数据后再过滤）。
- 权限处理：优先使用普通文件 API；在 Android 11+ 遇到受限路径或 SAF 情况下，降级到 SAF 授权的访问方式并提示用户授权。


### 3) 文件元数据读取

- 先根据文件扩展名筛选可能的音频文件。
- 对于每个候选文件：
  - 使用 `MediaMetadataRetriever` / `MediaExtractor` 获取基本时长、采样率、比特率、通道数等信息（如果失败，尝试 `androidx.media3`）。
  - 使用 `jaudiotagger` 或 `mp3agic` 提取更丰富的标签：标题、艺术家、专辑、专辑艺术、Track Number、Disc Number、编码信息等。
  - 对标签文本先运行 `juniversalchardet` 检测编码并在必要时解码为 UTF-8。
  - 针对 FLAC/APE/Ogg 等格式，优先使用专门库（例如 `jflac`、`vorbis-java`）以保证字段解析准确。


### 4) CUE 处理与多轨映射

- 识别：当扫描到 .cue 文件时，先解析文本（用 `juniversalchardet` 检测编码，确保正确读取）。
- 解析器：解析 CUE 的 FILE / TRACK / INDEX / TITLE / PERFORMER 等条目，生成一份指向媒体文件的轨道表。
- 关联：验证 CUE 中的 FILE 指向的物理媒体文件是否存在（同目录或用户指定目录），若存在则为该媒体文件生成 N 个逻辑 MusicEntry，每项包含 startTime/endTime 并且 id 为 `relativePath + "#" + trackNumber`。
- 回退：若 CUE 指向不存在文件，则把 CUE 视作独立的元数据源（可能仅包含分轨信息），尝试在库中通过音轨元信息匹配媒体文件。


### 5) 去重与指纹

- 快速去重：优先使用文件路径 + 文件大小 + lastModified 作为快速唯一键。
- 强一致性：对重要文件（例如大小相同、名字相近）可选择性计算音频指纹（如 chromaprint/AcousticID）—这是一个可选耗时操作，应在后台延迟执行并可由用户开启。
- 冲突解决策略：当检测到疑似重复时，保留原有条目并标记为重复候选，等待后续用户/自动合并策略。


### 6) 持久化策略（数据库设计）

使用 `Room` 来管理 SQLite：

- 表：
  - songs
    - id (主键) — 对于普通文件使用 path；对于 CUE 轨道使用 path#track
    - path
    - cue_blob (nullable) — CUE 相关的原始文本或结构化 json
    - title
    - artist
    - album
    - duration_ms
    - size_bytes
    - format
    - bitrate
    - sample_rate
    - channels
    - tags_json (通用元数据 json)
    - fingerprint (nullable)
    - last_scanned_at
  - scan_state
    - id (固定1条)
    - cursor_blob (序列化的扫描游标，用于恢复)
    - last_scan_started_at
    - last_scan_completed_at
    - total_files_seen
  - user_scan_settings
    - id
    - min_duration_seconds
    - excluded_extensions
    - excluded_paths

- 写入策略：批量写入，每达到一定条数（例如 100）或时间间隔就提交一次事务。


### 7) 进度报告与 UI 交互

- 扫描 Worker 中周期性（例如每 200ms 或每 100 条文件）通过 WebView 的 JS Bridge 推送进度（已扫描计数、当前路径、发现新歌曲数），并在完成后回传结果摘要。
- 扫描页面（Web 前端）显示动态进度、日志以及扫描结果列表，并允许用户暂停/取消。


## 代码架构设计（模块化）

总体采用清晰分层设计：

- app/src/main/java/zelixir/hesimusic/scan/
  - ScanManager
    - 负责协调扫描任务、提供 API 给 UI（start/stop/status）、管理扫描策略、维护 ScanState。
  - FileScanner
    - 负责文件系统遍历逻辑（多线程/协程实现），产生候选文件路径流或回调。
  - MetadataExtractor
    - 对候选文件读取元数据（时长、标签），封装对各种底层库（MediaMetadataRetriever、jaudiotagger、format-specific libs）的调用。
  - CueParser
    - 解析 .cue 文件并生成 TrackEntry 列表，支持编码检测与容错。
  - Deduplicator
    - 提供去重与指纹计算接口（可同步或异步），并标记重复。
  - ScanRepository (Room)
    - 定义 Entities 和 DAO，负责批量写入与事务管理。
  - ScanWorker (WorkManager Worker)
    - 将扫描过程封装为可恢复的后台任务，实现中断/恢复逻辑。
  - ScanWebBridge
    - 与前端通信的适配层：把扫描进度、错误、结果发给 Web 前端，接收用户指令（暂停/取消/配置）。


## 接口与契约（简要）

- ScanManager API
  - startScan(options): ScanId
  - stopScan(scanId)
  - getStatus(scanId): ScanStatus
  - getProgress(scanId): { scannedCount, foundSongs, currentPath }

- MetadataExtractor contract
  - extract(filePath): Promise<MetadataResult>
    - MetadataResult: { durationMs, title, artist, album, bitrate, sampleRate, channels, tags, success }

- CueParser contract
  - parse(cuePath): CueParseResult
    - CueParseResult: { tracks: [{ title, performer, startMs, endMs, fileReference }], errors }


## 边界情况与异常处理

- 权限拒绝：在遇到权限问题时，记录并上报给前端，暂停扫描并请求权限。
- 大量文件/内存：使用流式处理与批量写入，避免一次性将所有候选保存在内存中。
- 格式兼容性：若一个库无法解析某格式，尝试回退至另一解析器或记录解析失败以便后续处理。
- 中断恢复：在 Worker 停止前，把当前处理的目录/文件写入 `scan_state.cursor_blob`，Worker 重启时读取游标并跳过已扫过的路径。


## 性能考虑与优化

- 并发控制：限制文件读取/解析并发度，避免磁盘 I/O 饱和。
- 延迟解析策略：先只读取轻量级信息（扩展名 + 文件大小 + MF metadata），在发现可能是音频且需要时再进行重量级解析（例如指纹或完整 tag 解析）。
- 优先级队列：把修改时间近、用户常访问目录优先扫描，改善首次体验。
- 低优先级后台任务：把音频指纹计算等重任务放到低优先级或用户允许的情况下执行。


## 测试建议

- 单元测试：CUE 解析器、去重算法、MetadataExtractor 的 wrapper（使用小样本音频文件）。
- 集成测试：在模拟目录结构下运行完整扫描，验证数据库写入与恢复逻辑。
- 性能测试：在包含 10k+ 音频文件的虚拟文件系统上测试扫描时间与内存消耗。


## 迁移与兼容性

- Android 版本适配：在 Android 11+ 需要处理分区存储（Scoped Storage）限制；尽量使用 MediaStore + SAF 在受限路径获取访问权限。
- 外部存储：检测并支持多挂载点（Environment.getExternalStorageDirectory()、StorageManager list）。
