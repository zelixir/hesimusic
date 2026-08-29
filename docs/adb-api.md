# ADB 数据库接口（AdbDbProvider）

通过 adb 直接操作海瑟音乐的数据库与 SQL 歌单，无需经过任何 UI。
Provider 运行在应用进程内、共用 Room 实例：WAL 一致性由 Room 保证，写入后界面自动刷新。

## 安全模型

Provider 必须 `exported` 才能被 shell 访问，因此运行时校验调用方 UID：
仅放行 **应用自身 / shell (2000) / root (0)**，设备上的其他应用收到 `SecurityException`。
无需 debug 构建，release 包同样可用。

## 调用方式

统一入口是 `content call`，返回 Bundle（`ok` 布尔 + `result` JSON 字符串，失败时 `error`）：

```bash
adb shell content call --uri content://com.zjr.hesimusic.adbdb --method <method> --arg <参数>
```

- `--arg` 传一个字符串；结构化参数传 JSON。
- 返回值可读性差时，追加 `| tr ',' '\n'` 或用 `--user 0`。

## 方法一览

| method | arg | 说明 |
|---|---|---|
| `query` | SQL 字符串 | 只读查询，返回 `{"rows":[...], "truncatedAt":500}`（最多 500 行） |
| `exec` | SQL 字符串 | 写操作（INSERT/UPDATE/DELETE/DDL），返回行数或 lastInsertRowId |
| `list_views` | 无 | 列出全部 SQL 歌单（含定义） |
| `create_view` / `update_view` | `{"name":"...", "sql":"..."}` | 创建/更新（按名称幂等），先校验后写入 |
| `validate_view` | `{"sql":"..."}` | 只校验不写入，返回 `{"valid":bool,"error":...}` |
| `delete_view` | `{"id":1}` 或 `{"name":"..."}` | 删除 SQL 歌单 |
| `schema` | 无 | 全部表结构 + 行数 |
| `export` | 无 | 备份 JSON 写到应用私有目录，返回文件路径 |
| `import` | 可选文件名 | 从应用私有目录导入备份，返回各类条目数 |

## SQL 歌单（视图）

视图是一条存进 `smart_playlists` 表的 SELECT，歌曲页实时求值（曲库/收藏变化自动刷新）。

**契约**：必须是返回歌曲行的单条 SELECT，投影列与 `songs` 表完全一致。规范写法：

```sql
SELECT s.* FROM songs s WHERE <条件> ORDER BY <排序>
```

校验规则（`create_view` / `update_view` 时强制执行）：
- 仅允许 `SELECT` / `WITH` 开头的单条语句（禁止分号、禁止 INSERT/UPDATE/DELETE/PRAGMA/ATTACH 等关键词，词边界匹配）；
- 实际跑一遍并核对返回列与 songs 表一致（`validate_view` 可单独预检）。

常用表：`songs`（id/title/artist/album/filePath/duration/trackNumber/year/genre/dateAdded/isCue/startPosition/endPosition/titleInitial/folderPath/...）、`favorites`(filePath,startPosition,dateAdded)、`hidden_songs`、`playlists`(id,name,createdAt)、`playlist_entries`(playlistId,songId,order)。

## 示例

```bash
U="content://com.zjr.hesimusic.adbdb"

# 建一个 SQL 歌单（按名称幂等）
adb shell content call --uri $U --method create_view --arg '{"name":"近年日语歌","sql":"SELECT s.* FROM songs s WHERE s.year >= 2023 AND s.titleInitial != \"#\" ORDER BY s.title COLLATE NOCASE"}'

# 列出 / 删除
adb shell content call --uri $U --method list_views
adb shell content call --uri $U --method delete_view --arg '{"name":"近年日语歌"}'

# 直接查库 / 写库
adb shell content call --uri $U --method query --arg "SELECT id,name FROM playlists"
adb shell content call --uri $U --method exec --arg "INSERT INTO playlists(name) VALUES('通勤')"

# 表结构
adb shell content call --uri $U --method schema

# 备份到应用私有目录后取回 PC
adb shell content call --uri $U --method export
adb shell run-as com.zjr.hesimusic cat files/adb_export.json > backup.json

# 从 PC 恢复备份
adb push backup.json /data/local/tmp/b.json
adb shell "cat /data/local/tmp/b.json | run-as com.zjr.hesimusic sh -c 'cat > files/adb_restore.json'"
adb shell content call --uri $U --method import
```

注意：`--arg` 里的 JSON 含双引号时，bash 外层用单引号、JSON 内用 `\"` 转义（如上例）；
Windows PowerShell 下引号规则不同，建议用 `tools/hesimusic-adb.ps1`。
