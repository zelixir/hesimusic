# 扫描音乐进度更新修复

## 问题描述
点击"开始扫描"按钮后，按钮变成"扫描中..."状态，但是没有进度更新显示，一段时间后前端报超时错误。

## 根本原因
设计文档规定扫描 Worker 应该周期性地通过 WebView 的 JS Bridge 推送进度更新，但现有实现存在两个问题：

1. **缺少进度推送机制**: `ScanWorker` 调用 `ScanManager.updateProgress()` 时只将进度保存到磁盘，没有推送到 WebView
2. **参数格式不匹配**: 前端发送 `{options: {folders: [...]}}` 但后端期望 `{paths: []}`

## 修复内容

### 1. ScanManager.kt
- 添加 `progressCallback` 字段和 `setProgressCallback()` 方法
- 在 `updateProgress()` 中调用回调函数通知进度更新
- 在 `ScanProgress` 数据类中添加 `finished` 字段标识扫描完成
- 更新进度持久化方法以保存/读取 `finished` 字段

### 2. MainActivity.kt  
- 注册进度回调，将进度更新转发到 WebView
- 通过 `window.__music_api_emit__('scanProgress', progressJson)` 推送事件
- 进度 JSON 包含两种格式字段以保证兼容性：
  - `count` / `current` (前端期望的格式)
  - `scannedCount` / `currentPath` (内部格式)

### 3. ScanWorker.kt
- 扫描完成时发送最终进度更新，设置 `finished: true`

### 4. ScanWebBridge.kt
- 修复参数解析以正确提取 `options.folders`
- 支持新格式 `folders` 和旧格式 `paths` 以保证向后兼容

## 数据流

1. **前端启动扫描**:
   ```typescript
   ScanApi.startScan({ 
     folders: ['/storage/...'], 
     skipShort: true, 
     ... 
   }, onProgress, onError)
   ```

2. **MusicBridge 转发**:
   ```typescript
   MusicBridge.call('startScan', { options: {...} })
   → JSON.stringify({ options: { folders: [...], ... } })
   → window.ScanBridge.startScanFromJs(payload)
   ```

3. **原生端启动扫描**:
   ```kotlin
   ScanWebBridge.startScanFromJs(optionsJson)
   → 解析 options.folders
   → ScanManager.startScan(ScanOptions(...))
   → WorkManager 启动 ScanWorker
   ```

4. **进度更新推送**:
   ```kotlin
   ScanWorker 扫描文件
   → ScanManager.updateProgress(scanId, progress)
   → progressCallback?.invoke(scanId, progress)
   → MainActivity 的回调被调用
   → webView.evaluateJavascript("window.__music_api_emit__('scanProgress', {...})")
   ```

5. **前端接收更新**:
   ```typescript
   window.__music_api_emit__('scanProgress', payload)
   → 调用所有注册的监听器
   → ScanApi.startScan 中的监听器被调用
   → onProgress(payload) 更新 UI
   ```

## 验证方法

### 前端验证
1. 打开浏览器开发者工具的控制台
2. 点击"开始扫描"
3. 应该能看到以下日志：
   - `[scanApi] startScan -> calling MusicBridge`
   - `[musicBridge] calling ScanBridge.startScanFromJs`
   - `[musicBridge] __music_api_emit__ { name: 'scanProgress', payload: {...} }`
4. UI 应该显示：
   - "已扫描: X" 数字持续增加
   - "当前文件: /path/to/file" 不断更新
   - 扫描完成后按钮恢复到"开始扫描"

### 后端验证
查看 Logcat 日志应该包含：
- `ScanManager: Progress callback invoked`
- `MainActivity: Forwarding scanProgress to webview`
- 进度更新的 JSON 数据

## 测试场景

1. **正常扫描**: 选择包含音乐文件的文件夹，启动扫描，验证进度实时更新
2. **扫描完成**: 验证扫描完成后 `finished: true` 被正确发送，按钮状态恢复
3. **错误处理**: 验证扫描过程中的错误能正确显示
4. **多文件夹**: 选择多个文件夹扫描，验证进度正确累加

## 相关文件

- `/hesimusic-client/app/src/main/java/zelixir/hesimusic/scan/ScanManager.kt`
- `/hesimusic-client/app/src/main/java/com/hesimusic/MainActivity.kt`
- `/hesimusic-client/app/src/main/java/zelixir/hesimusic/scan/ScanWorker.kt`
- `/hesimusic-client/app/src/main/java/zelixir/hesimusic/scan/ScanWebBridge.kt`
- `/ui/src/services/musicBridge.ts`
- `/ui/src/services/scanApi.ts`
- `/ui/src/components/ScanPage.vue`
