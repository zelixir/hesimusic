# 扫描进度更新流程图

```
┌─────────────────────────────────────────────────────────────────────┐
│                           前端 (Vue)                                 │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │ ScanPage.vue                                                   │  │
│  │   startScan() {                                                │  │
│  │     ScanApi.startScan({ folders, ... }, onProgress)           │  │
│  │   }                                                            │  │
│  │                                                                │  │
│  │   onProgress(p) {                                              │  │
│  │     scannedCount.value = p.count                               │  │
│  │     currentFile.value = p.current                              │  │
│  │     if (p.finished) scanning.value = false                     │  │
│  │   }                                                            │  │
│  └───────────────────────────────────────────────────────────────┘  │
│                                   ↓                                   │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │ scanApi.ts                                                     │  │
│  │   startScan(options, onProgress) {                             │  │
│  │     MusicBridge.call('startScan', { options })                 │  │
│  │     MusicBridge.on('scanProgress', (p) => onProgress(p))       │  │
│  │   }                                                            │  │
│  └───────────────────────────────────────────────────────────────┘  │
│                                   ↓                                   │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │ musicBridge.ts                                                 │  │
│  │   call('startScan', args) {                                    │  │
│  │     payload = JSON.stringify(args)                             │  │
│  │     window.ScanBridge.startScanFromJs(payload)                 │  │
│  │   }                                                            │  │
│  │                                                                │  │
│  │   window.__music_api_emit__(name, payload) {                  │  │
│  │     listeners.get(name).forEach(cb => cb(payload))             │  │
│  │   }                                                            │  │
│  └───────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────┘
                                   ↓
                        WebView JavaScript Bridge
                                   ↓
┌─────────────────────────────────────────────────────────────────────┐
│                         后端 (Android/Kotlin)                        │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │ ScanWebBridge.kt                                               │  │
│  │   @JavascriptInterface                                         │  │
│  │   startScanFromJs(optionsJson) {                               │  │
│  │     options = parse(optionsJson).options                       │  │
│  │     folders = options.folders                                  │  │
│  │     scanId = ScanManager.startScan(...)                        │  │
│  │     return { scanId }                                          │  │
│  │   }                                                            │  │
│  └───────────────────────────────────────────────────────────────┘  │
│                                   ↓                                   │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │ ScanManager.kt                                                 │  │
│  │   startScan(options) {                                         │  │
│  │     scanId = UUID.randomUUID()                                 │  │
│  │     WorkManager.enqueue(ScanWorker)                            │  │
│  │     return scanId                                              │  │
│  │   }                                                            │  │
│  │                                                                │  │
│  │   updateProgress(scanId, progress) {                           │  │
│  │     progressMap[scanId] = progress                             │  │
│  │     writePersistedProgress(scanId, progress)                   │  │
│  │     progressCallback?.invoke(scanId, progress) ← 新增！         │  │
│  │   }                                                            │  │
│  └───────────────────────────────────────────────────────────────┘  │
│                                   ↓                                   │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │ ScanWorker.kt (运行在后台线程)                                   │  │
│  │   doWork() {                                                   │  │
│  │     scanner.scanRoots(...) {                                   │  │
│  │       onFile = { file ->                                       │  │
│  │         scanned++                                              │  │
│  │         ScanManager.updateProgress(                            │  │
│  │           scanId,                                              │  │
│  │           ScanProgress(scanned, found, file.path)              │  │
│  │         )                                                      │  │
│  │       }                                                        │  │
│  │     }                                                          │  │
│  │     // 完成后                                                   │  │
│  │     ScanManager.updateProgress(                                │  │
│  │       scanId,                                                  │  │
│  │       ScanProgress(..., finished=true) ← 新增！                 │  │
│  │     )                                                          │  │
│  │   }                                                            │  │
│  └───────────────────────────────────────────────────────────────┘  │
│                                   ↓                                   │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │ MainActivity.kt                                                │  │
│  │   onCreate() {                                                 │  │
│  │     ScanManager.setProgressCallback { scanId, progress ->      │  │
│  │       progressJson = JSONObject()                              │  │
│  │       progressJson.put("count", progress.scannedCount)         │  │
│  │       progressJson.put("current", progress.currentPath)        │  │
│  │       progressJson.put("finished", progress.finished)          │  │
│  │                                                                │  │
│  │       js = "window.__music_api_emit__('scanProgress', $json)"  │  │
│  │       webView.evaluateJavascript(js, null)                     │  │
│  │     }                                                          │  │
│  │   }                                                            │  │
│  └───────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────┘
                                   ↑
                        WebView JavaScript 执行
                                   ↑
                    回到前端 musicBridge.ts 的 __music_api_emit__

┌─────────────────────────────────────────────────────────────────────┐
│                        关键修复点                                     │
│                                                                       │
│  1. ✅ 添加 ScanManager.progressCallback                              │
│  2. ✅ 在 updateProgress() 中调用 progressCallback                     │
│  3. ✅ MainActivity 注册回调，推送到 WebView                           │
│  4. ✅ 添加 finished 字段标识扫描完成                                  │
│  5. ✅ 修复 ScanWebBridge 参数解析 (options.folders)                  │
└─────────────────────────────────────────────────────────────────────┘
```

## 修复前的问题

```
ScanWorker -> ScanManager.updateProgress() 
    |
    ├─> 保存到内存 (progressMap)
    ├─> 持久化到磁盘 (writePersistedProgress)
    └─> ❌ 没有推送到 WebView！

前端等待 scanProgress 事件 -> ⏱️ 超时
```

## 修复后的流程

```
ScanWorker -> ScanManager.updateProgress()
    |
    ├─> 保存到内存 (progressMap)
    ├─> 持久化到磁盘 (writePersistedProgress)
    └─> ✅ progressCallback?.invoke(scanId, progress)
            |
            └─> MainActivity callback
                    |
                    └─> webView.evaluateJavascript(
                            "window.__music_api_emit__('scanProgress', {...})"
                        )
                            |
                            └─> 前端 listeners 收到事件
                                    |
                                    └─> onProgress(payload)
                                            |
                                            └─> UI 更新！
```
