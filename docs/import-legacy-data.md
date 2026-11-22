# 在 v2 里继承现有 app 的数据（走 app 自己已有的 adb 协议）

v2 与正式版是同一份代码，只是包名不同。数据迁移**不需要任何新功能、按钮或页面**，
用 app 本来就有的 adb 接口即可（见 [adb-api.md](adb-api.md)）：

| 包 | authority |
|---|---|
| 现有 app | `content://com.zjr.hesimusic.adbdb` |
| v2 | `content://com.zjr.hesimusic.v2.adbdb` |

两边都有同一套 `export` / `import`：

- `export` 把备份写到应用私有目录 `files/adb_export.json`（version 4：songs / favorites /
  hiddenSongs / playlists / playlistEntries / smartPlaylists / songLoops / preferences）
- `import` 读 `files/adb_restore.json`，在**一个事务里整体替换**本机数据（保留原 id）

## 三条命令完成迁移

```powershell
$adb = "$env:ANDROID_HOME\platform-tools\adb.exe"

# 1) 从现有 app 导出
& $adb shell content call --uri content://com.zjr.hesimusic.adbdb --method export
& $adb exec-out run-as com.zjr.hesimusic cat files/adb_export.json > legacy.json

# 2) 放进 v2 的私有目录（v2 是 debuggable 构建）
& $adb push legacy.json /data/local/tmp/restore.json
& $adb shell "cat /data/local/tmp/restore.json | run-as com.zjr.hesimusic.v2 sh -c 'cat > files/adb_restore.json'"

# 3) 让 v2 导入（整体替换）
& $adb shell content call --uri content://com.zjr.hesimusic.v2.adbdb --method import
```

## 注意

- `import` 是**整体替换**：会先清空 v2 自己的 songs / 歌单 / 收藏 / 隐藏 / SQL 歌单 / 循环记录再写入。
- 现有 app 的数据不受影响（v2 只读它的导出文件）。
- 导出文件里的 `preferences` 只包含播放/扫描偏好，不含队列、当前播放位置、循环引擎选择。
- 若某天 v2 不是 debuggable 构建，把第 2 步换成 root 或 `adb backup` 即可，协议本身不变。
