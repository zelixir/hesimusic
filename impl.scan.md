## 扫描模块开发计划 (impl.scan.md)

本开发计划基于 `design.scan.md`，将扫描功能拆分为前端与后端任务，每项以待办（todolist）条目形式列出，包含：功能点、技术路线、相关代码文件及建议的方法名/接口。此阶段为设计与任务分配阶段，不包含实际代码实现。

说明：若未在仓库中找到确切的文件路径，我根据 `design.scan.md` 中的模块命名约定给出建议路径；实现时可按需调整。

### 高级契约（简短）
- 输入：用户配置的扫描设置（路径列表、排除规则、最小时长等）、设备存储根路径
- 输出：写入 Room/SQLite 的 songs 表更新、scan_state 更新、实时进度通过 WebView JS Bridge 推送
- 错误模式：权限不足、I/O 错误、元数据解析失败，均应上报给前端并可重试


---

## 后端（Android）任务清单

1. 扫描任务入口与管理
   - 功能点：管理扫描任务的生命周期（start/stop/status/restore），暴露给前端控制接口
   - 技术路线：使用 WorkManager 封装长期/可恢复任务；提供内存层的 ScanManager 作为统一入口；ScanWorker 作为 WorkManager Worker 实现持久化可恢复扫描
   - 代码文件及方法：
     - `app/src/main/java/zelixir/hesimusic/scan/ScanManager.kt`
       - startScan(options: ScanOptions): String // 返回 scanId
       - stopScan(scanId: String): Boolean
       - getStatus(scanId: String): ScanStatus
       - getProgress(scanId: String): ScanProgress
     - `app/src/main/java/zelixir/hesimusic/scan/ScanWorker.kt`
       - doWork(): Result
       - serializeCursor(): ByteArray
       - deserializeCursor(blob: ByteArray)
   - 状态: 已完成

2. 文件系统扫描器 (FileScanner)
   - 功能点：并发遍历存储根路径，产生候选文件路径流，支持排除规则与隐藏目录忽略
   - 技术路线：BFS + 线程池/协程 (Kotlin Coroutines)，对每个挂载根路径启动独立协程；控制并发目录读取（例如 Semaphore 限制并发数）
   - 代码文件及方法：
     - `app/src/main/java/zelixir/hesimusic/scan/FileScanner.kt`
       - scanRoots(roots: List<File>, options: ScanOptions, callback: (File) -> Unit): ScanCursor
       - pauseScan()
       - resumeScan(cursor: ScanCursor)
       - buildExclusionMatcher(options: ScanOptions): (File) -> Boolean
   - 状态: 已完成

3. 元数据提取器 (MetadataExtractor)
   - 功能点：提取时长、标签、比特率、采样率、通道数等；尝试多种后备解析器；对标签文本进行编码检测与转换
   - 技术路线：优先使用 `MediaMetadataRetriever`/`MediaExtractor`，作为补充使用 `jaudiotagger` 与 format-specific libs；使用 `juniversalchardet` 做编码检测；解析过程尽量轻量化，重要字段优先
   - 代码文件及方法：
     - `app/src/main/java/zelixir/hesimusic/scan/MetadataExtractor.kt`
       - extract(file: File): MetadataResult
       - extractLight(file: File): LightMetadata // 仅 size/duration/format
       - decodeTagText(raw: ByteArray): String
   - 状态: 已完成

4. CUE 解析器 (CueParser)
   - 功能点：解析 `.cue` 文本，检测编码，生成 TrackEntry 列表并关联媒体文件路径
   - 技术路线：优先查找现成 Java CUE 解析库；若无则实现小型解析器：基于行解析，解析 FILE/TRACK/INDEX/TITLE/PERFORMER 字段，支持容错和注释
   - 代码文件及方法：
     - `app/src/main/java/zelixir/hesimusic/scan/CueParser.kt`
       - parse(cueFile: File): CueParseResult
       - resolveReferencedMedia(cueResult: CueParseResult, baseDirs: List<File>): List<TrackEntry>
  - 状态: 已完成

5. 去重与指纹 (Deduplicator)
   - 功能点：提供快速去重（path+size+modified）和可选的音频指纹化接口（异步、低优先级）
   - 技术路线：快速键作为默认；指纹使用 chromaprint（如果有 Android 支持）或延迟到后台服务；冲突写入数据库时标记为 duplicate_candidate
   - 代码文件及方法：
     - `app/src/main/java/zelixir/hesimusic/scan/Deduplicator.kt`
       - isDuplicate(candidate: SongEntry): DuplicateCheckResult
       - computeFingerprintAsync(file: File): Deferred<String?>
   - 状态: 已完成

6. 数据库与持久化 (ScanRepository)
   - 功能点：定义 Room Entities 与 DAO，批量写入 songs、更新 scan_state，支持事务与游标存储
   - 技术路线：使用 Room，DAO 提供批量插入/更新；在 Worker 中使用事务批量提交（例如每 100 条）
   - 代码文件及方法：
     - `app/src/main/java/zelixir/hesimusic/scan/db/ScanDatabase.kt` (RoomDatabase)
     - `app/src/main/java/zelixir/hesimusic/scan/db/SongEntity.kt`
     - `app/src/main/java/zelixir/hesimusic/scan/db/SongDao.kt`
       - insertSongs(songs: List<SongEntity>)
       - markDuplicate(id: String, duplicateOf: String?)
     - `app/src/main/java/zelixir/hesimusic/scan/ScanRepository.kt`
       - saveBatch(songs: List<SongEntry>)
       - updateScanState(cursor: ByteArray, stats: ScanStats)
  - 状态: 已完成

7. 扫描 Worker 与恢复逻辑
   - 功能点：实现 WorkManager 的 Worker，支持中断保存游标与恢复，定期推送进度
   - 技术路线：Worker 内部循环从 FileScanner 获取文件并调用 MetadataExtractor，按批次写入数据库，遇到中断或异常时序列化 cursor 到 scan_state 表
   - 代码文件及方法：
     - `app/src/main/java/zelixir/hesimusic/scan/ScanWorker.kt`
       - doWork(): Result
       - checkpoint(cursor: ScanCursor)
   - 状态: 已完成

8. 后端 - WebView JS Bridge (ScanWebBridge)
   - 功能点：把扫描控制接口与进度事件暴露给前端，接收前端暂停/取消/配置命令
   - 技术路线：使用 Android WebView 的 addJavascriptInterface 或 AndroidX WebKit; 为安全起见，用消息队列/事件而非直接执行字符串
   - 代码文件及方法：
     - `app/src/main/java/zelixir/hesimusic/scan/ScanWebBridge.kt`
       - startScanFromJs(optionsJson: String): String
       - stopScanFromJs(scanId: String): Boolean
       - onProgressUpdate(scanId: String, progress: ScanProgress)
   - 状态: 已完成

9. 权限处理与 SAF 支持
   - 功能点：在权限不足或需 SAF 授权时上报给前端并暂停，支持从前端回传 SAF Uri 权限
   - 技术路线：在 ScanManager 中检测权限，遇到可恢复授权（SAF）时把 intent/uri 请求发给前端；恢复后继续扫描
   - 代码文件及方法：
     - `app/src/main/java/zelixir/hesimusic/scan/PermissionHelper.kt`
       - ensureStoragePermissions(activity: Activity): PermissionResult
       - requestSafAccess(uri: Uri): Boolean
   - 状态: 已完成

10. 日志、错误上报与监控
    - 功能点：扫描过程中收集错误、失败解析样本并上报给前端；保留本地日志供调试
    - 技术路线：集中日志类，日志分级（INFO/WARN/ERROR），在完成后打包扫描摘要并发送到前端
    - 代码文件及方法：
      - `app/src/main/java/zelixir/hesimusic/scan/ScanLogger.kt`
        - logInfo(msg: String)
        - logError(e: Throwable, context: String)
  - 状态: 已完成


---

## 前端（Web UI）任务清单

前端运行在内置 WebView，使用 Vue + Tailwind。前端任务关注用户交互、配置、进度展示与暂停/取消控制。

1. 扫描页面 UI 基本布局
   - 功能点：展示扫描选项、路径列表、不扫描路径、开始/停止按钮、进度/日志区域
   - 技术路线：Vue 3 组件化；使用 Composition API 与 Pinia（如果项目已使用）管理扫描状态；Tailwind 用于样式
   - 代码文件及方法：
     - `ui/src/components/ScanPage.vue`
       - setup() 中使用 `useScanStore()`
       - methods: onStartScan(), onStopScan(), onAddPath(), onRemovePath()
  - 状态: 已完成

2. 扫描设置表单与验证
   - 功能点：允许用户配置最小时长、忽略扩展、是否扫描隐藏目录、路径白名单/黑名单
   - 技术路线：表单控件 + 本地校验；配置通过 Bridge 保存到 native（调用 MusicBridge.call('setScanSettings', settings)）
   - 代码文件及方法：
     - `ui/src/services/scanApi.ts`
       - saveSettings(settings: ScanSettings): Promise<void>
       - loadSettings(): Promise<ScanSettings>
  - 状态: 已完成

3. 动态进度条与日志视图
   - 功能点：实时显示已扫描数量、发现歌曲数、当前文件路径、错误/警告日志，支持暂停/取消/跳转到详情
   - 技术路线：通过 ScanWebBridge 的事件回调（window.__music_api_on_scan_progress__）接收进度；使用虚拟化列表显示日志/结果（当数据量大时）
   - 代码文件及方法：
     - `ui/src/components/ScanProgress.vue`
       - props: scanId
       - methods: handleProgressEvent(ev), handlePauseClick(), handleCancelClick()
  - 状态: 已完成

4. 结果展示与差异处理
   - 功能点：扫描完成后显示新增、更新、冲突候选（重复）的歌曲条目，支持查看单项详情（跳转到歌曲详情页）
   - 技术路线：在前端接收完成事件后，通过 API 拉取新近批次（或使用 WebSocket / bridge 拉取），显示差异并提供合并或忽略动作（可选）
   - 代码文件及方法：
     - `ui/src/components/ScanResultList.vue`
       - methods: fetchScanResults(scanId), applyMerge(id, action)
  - 状态: 已完成

5. SAF 权限交互与说明弹窗
   - 功能点：当后端请求 SAF 或权限时，展示指引弹窗并触发系统权限流程（通过 bridge）
   - 技术路线：Bridge 事件 -> 弹窗 -> 用户确认 -> 调用 bridge 请求系统权限
   - 代码文件及方法：
     - `ui/src/components/PermissionModal.vue`
       - methods: onGrantClick(), onCancelClick()
  - 状态: 已完成

6. 小工具与调试视图（开发期可选）
   - 功能点：显示最近解析失败的文件样本、手动触发元数据解析重试、下载 CUE/元数据文本
   - 技术路线：仅在开发/调试模式显示，提供复制/下载按钮
   - 代码文件及方法：
     - `ui/src/components/ScanDebugPanel.vue`
       - methods: retryMetadata(filePath), downloadCue(cueId)
  - 状态: 已完成


---

## 集成契约（前后端消息/接口示例）

- 前端 -> 后端（通过 MusicBridge.call）
  - MusicBridge.call('startScan', { paths: [...], settings: {...} }) -> { scanId }
  - MusicBridge.call('stopScan', { scanId }) -> { success }
  - MusicBridge.call('getScanStatus', { scanId }) -> { scannedCount, foundSongs, currentPath }

- 后端 -> 前端（通过 window callback 或 addJavascriptInterface 触发）
  - window.__music_api_on_scan_progress__(scanId, { scannedCount, foundSongs, currentPath, logs: [...] })
  - window.__music_api_on_scan_complete__(scanId, { summary })
  - window.__music_api_on_scan_error__(scanId, { errorCode, message })


## 测试计划（建议）

- 单元测试
  - `CueParser` 单元测试 (happy path, malformed cue, unsupported encodings)
  - `MetadataExtractor` wrapper 的 mock 测试（不同格式）
  - `Deduplicator` 快速键重合与指纹冲突测试

- 集成测试
  - 在 `app/src/androidTest` 下建立小型虚拟文件结构，运行 `ScanWorker` 并验证 `songs` 与 `scan_state` 写入

- 性能测试
  - 使用 10k+ 虚拟文件集合测量扫描时间、内存峰值、数据库写入吞吐


## 交付物 (文件/方法清单汇总)

- 后端
  - `ScanManager.kt` - startScan/stopScan/getStatus/getProgress
  - `ScanWorker.kt` - doWork/checkpoint
  - `FileScanner.kt` - scanRoots/scanCursor/pauseResume
  - `MetadataExtractor.kt` - extract/extractLight/decodeTagText
  - `CueParser.kt` - parse/resolveReferencedMedia
  - `Deduplicator.kt` - isDuplicate/computeFingerprintAsync
  - `ScanRepository.kt` + `db/` entities & DAO - insertSongs/updateScanState
  - `ScanWebBridge.kt` - start/stop/progress callbacks
  - `PermissionHelper.kt` - ensureStoragePermissions/requestSafAccess
  - `ScanLogger.kt` - logInfo/logError

- 前端
  - `ui/src/components/ScanPage.vue` - UI 与交互
  - `ui/src/components/ScanProgress.vue` - 进度/日志视图
  - `ui/src/components/ScanResultList.vue` - 扫描结果展示
  - `ui/src/components/PermissionModal.vue` - 权限指引
  - `ui/src/services/scanApi.ts` - 与后端桥接的封装方法


## 假设与注意事项

- 假设项目的 Android 包名为 `zelixir.hesimusic`（请根据实际包名调整路径）。
- 假设前端使用 Vue 3 + Composition API，且项目已有统一的 Bridge 实现（`musicBridge.ts` / `musicApi.ts`），若不存在请先实现通用桥接层。
- 指纹计算（chromaprint）为可选且昂贵的任务，建议作为鼠标/定时后台任务或用户开启的选项。
- SAF 与 Android 11+ 的分区存储问题需要在开发时逐步测试并优化回退逻辑。


---

## 下一步

1. （已 in-progress）完成此 `impl.scan.md` 的初稿并 commit。
2. 将每个后端任务拆成 Issue 或 Sprint 卡片，并为 `FileScanner`、`CueParser` 和 `MetadataExtractor` 编写单元测试用例草案。
3. 在小型样本目录上做一次手动扫描集成验证。

完成状态：起草完成，待仓库 review 与任务分配。
