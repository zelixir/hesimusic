# 无缝循环基线 CPU 对比（c55ffe4 / 单曲循环）

目的：把「现行环带循环（SOURCE）」的 CPU 成本，与无缝循环开发开始前的最后一版
（commit `c55ffe4` Fix playback queue breaking when the current song is queued）在
**ExoPlayer 原生 repeatMode=ONE** 下的成本对比。判据：现行环带 CPU ≤ baseline × 1.10。

旧版没有调试 HTTP API，因此：

- 旧版必须用**独立包名**旁路安装（旧 DB 是 v9，现行 v10，降级安装会破坏 Room 数据）；
- 驱动只能靠 UI（uiautomator dump + input tap）；
- 采集与现行版共用同一套 `/proc` + AudioFlinger 口径（`tools/engine-cost-baseline.ps1`）。

## 1. 构建旧版旁路包

```powershell
# 工作树已在 D:\Code\hesimusic-baseline，commit c55ffe4
# 该提交没有 -PdevPackage 属性，所以只改两处构建标识（不改业务代码）：
#   app/build.gradle.kts: applicationId = "com.zjr.hesimusic.baseline"
#   app/src/main/AndroidManifest.xml: authorities="${applicationId}.adbdb"（原为写死主包名，会与主包冲突）
cd D:\Code\hesimusic-baseline
.\gradlew.bat :app:assembleDebug "-PreleaseVersionCode=17" "-PreleaseVersionName=1.2.1-rollback" --console=plain
# 产物：app\build\outputs\apk\debug\app-debug.apk
```

## 2. 安装（ColorOS 特殊流程）

ColorOS/一加 14 上，`adb install` 会被 `com.oplus.appdetail` 接管：它会先弹「来自电脑端未知来源」
确认页并**取消 adb 会话**（`Failure [-99]`），真正安装发生在确认页的「继续安装」。

两个坑：

1. **不要带 `-g`**：`com.oplus.appdetail` 没有 `INSTALL_GRANT_RUNTIME_PERMISSIONS`，
   带 `-g` 会在确认后失败（日志：`You need the android.permission.INSTALL_GRANT_RUNTIME_PERMISSIONS ...`）。
2. 确认页必须用 uiautomator 找到「继续安装」按钮并 tap；`adb install` 返回 `Failure [-99]` 是**预期**的，
   不能据此判定失败。

```powershell
powershell -NoProfile -File tools\install-apk-confirm.ps1 `
  -Apk D:\Code\hesimusic-baseline\app\build\outputs\apk\debug\app-debug.apk `
  -PackageName com.zjr.hesimusic.baseline -Device b1e84dad
```

安装后授予「所有文件访问权限」（该 ROM 的 shell 没有 `MANAGE_APP_OPS_MODES`，`appops set` 会被拒）：

```powershell
adb shell am start -a android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION -d package:com.zjr.hesimusic.baseline
# 在设置页把开关打开，然后：
adb shell appops get com.zjr.hesimusic.baseline MANAGE_EXTERNAL_STORAGE   # Uid mode: ... allow
```

## 3. 准备曲库（不扫全库）

需求只是「一首已确认循环的歌」，旧版支持 debug 专属的数据库导入广播
（`AdbDatabaseReceiver`）。本机实测该广播的 `File.isFile()` 校验在此环境不可用，
最终采用**直接注入 Room 行**：把歌曲 40169 插入旧版 v9 数据库（不动表结构，不改 identity hash）。

```powershell
# 1) 让旧版先建好 v9 数据库并取得它
adb shell am start -W -n com.zjr.hesimusic.baseline/com.zjr.hesimusic.MainActivity
adb shell am force-stop com.zjr.hesimusic.baseline
adb exec-out "run-as com.zjr.hesimusic.baseline cat databases/hesimusic_db" > hesimusic_db
# 2) 本地用 sqlite 插入歌曲行（字段见 app/src/main/.../data/model/Song.kt；不要动 room_master_table）
#    40169 カタツムリ /storage/emulated/0/Music/MP3-03. カタツムリ.mp3 duration=225045
# 3) 以 base64 分块写回（adb shell 的 PTY 会破坏二进制 stdin，不能直接 cat > db）
#    tools/push-file-to-app.ps1 即此流程：39 个 3000 字符分块，写回后 sha256 必须一致
```

## 4. 驱动播放并设成单曲循环

```powershell
adb shell monkey -p com.zjr.hesimusic.baseline -c android.intent.category.LAUNCHER 1
# 歌曲列表第一行点一下开始播放（坐标来自 uiautomator dump）
adb shell input tap 292 438
# 点底栏标题进播放页
adb shell input tap 300 2240
# 播放模式按钮：列表循环 -> 随机播放 -> 单曲循环（content-desc 会依次变化）
adb shell input tap 144 2146   # -> 随机播放
adb shell input tap 144 2146   # -> 单曲循环
adb shell run-as com.zjr.hesimusic.baseline cat shared_prefs/playback_prefs.xml  # repeat_mode=1
```

确认口径（三选三）：

1. `uiautomator dump` 里该按钮 content-desc = `单曲循环`；
2. `playback_prefs.xml` 中 `repeat_mode=1`（ExoPlayer REPEAT_MODE_ONE）；
3. 采集中 `dumpsys media_session` 位置回绕（225s 的歌约每 3:45 出现一次 position 回落）
   且 AudioFlinger `Frames written` 持续增长。

## 5. 采集

```powershell
# baseline（旧包，UI 驱动）
powershell -NoProfile -File tools\engine-cost-baseline.ps1 `
  -Package com.zjr.hesimusic.baseline -Mode ui -SongId 40169 `
  -RequireRepeatMode one -Minutes 10 -Device b1e84dad

# 现行环带（需要现行包的调试 API；先 /loop/enter 再采同一套指标）
powershell -NoProfile -File tools\engine-cost-baseline.ps1 `
  -Package com.zjr.hesimusic -Mode api -SongId 40169 -Ams 92752 -Bms 173546 `
  -Minutes 10 -Device b1e84dad -ComparePath <baseline round.json>
```

指标：`/proc/<pid>/stat` 的 utime+stime（user/sys 分开）、`/proc/<pid>/status` 的自愿/非自愿
上下文切换、minflt/majflt、AudioFlinger 顶层输出线程 `Frames written` 增量、duty=帧数/(48000×秒)。

环境约定：屏幕常亮、不动音量/亮度/网络；采集期间 force-stop 另一个音乐包避免媒体会话争抢。
`KEYCODE_F1`（未映射空键）每 4 分钟触发一次仅用于重置息屏计时，不是唤醒/音量键。

## 6. 自检

```powershell
powershell -NoProfile -File tools\engine-cost-baseline.ps1 -SelfTest
```