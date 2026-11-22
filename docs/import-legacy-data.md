# 导入上一版数据(com.zjr.hesimusic → Hesi v2)

面向任务 C(issue 00030):在做**可并存安装**的第二个正式版 v2 的同时,把现有 app 的歌单、循环记录、设置
搬进 v2,**且完全不动现有 app**(不装/不卸载/不改它的数据,也不给它加任何"导出按钮")。

- v2 包名:**com.zjr.hesimusic.v2**,应用名 **Hesi v2**,源码 = 同一份 main 源集(含初版分析算法与播放器改动),
  额外只带 `app/src/v2` 里的「导入上一版数据」入口。
- 数据天然隔离:两个 applicationId → 各自 filesDir/数据库/SharedPreferences。
- 导出在 **PC 侧**完成;v2 侧只读用户用 SAF 选中的 JSON。

---

## 1. 形态与入口

| 项 | 值 |
|---|---|
| product flavor | `v2`(dimension `channel`,与 prod / demo / algolab 并列) |
| applicationId | `com.zjr.hesimusic.v2`(`applicationIdSuffix = ".v2"`) |
| 应用名 | `Hesi v2`(`manifestPlaceholders["appLabel"]`) |
| 版本名后缀 | `-v2` |
| 变体任务 | `:app:assembleV2Debug` / `:app:assembleV2Release`(`assembleDebug` 会构建全部 flavor) |
| 导入入口 | 桌面图标「导入上一版数据」= `LegacyImportActivity` 的 `activity-alias`(仅 v2 源集清单) |

为什么导入页是一个独立 Activity 而不是设置页里的一行:本任务的写域限定在
`app/build.gradle.kts`、v2 源集、main 的 `legacyimport` 包、`tools/`、`docs/`,
不允许改 main 侧的 `MainActivity`/`SettingsScreen` 导航,所以"只在 v2 出现"这件事由 flavor 清单隔离。
后续若要把入口放进设置页,只需在 main 的设置页加一行跳转(属于 main 写域,留给统一改动)。

> ⚠️ **包名不能叫 `import`**:Hilt/Room 的注解处理器会生成 **Java** 源文件,
> 而 `import` 是 Java 关键字,`package com.zjr.hesimusic.import;` 会让 javac/KSP 解析失败
> (表现为 KSP 内部 NPE:`null cannot be cast to non-null type ClassDescriptor`)。
> 因此导入模块的包/目录统一用 `legacyimport`。

---

## 2. 导出(PC 侧,只读上一版)

### 2.0 先探测:这台机器该走哪条路

```powershell
# 接上正式机后先跑这一条(会短暂弹一次系统备份确认框,脚本自动点掉)
pwsh -File tools/detect-legacy-app.ps1
```

输出结论直接给命令:

| 结论 | 含义 | 后续命令 |
|---|---|---|
| **B** | run-as 读库可用(上一版是 debuggable 构建) | `pwsh -File tools/export-legacy-data.ps1 -Method runas` |
| **A** | 只有 adb backup 可用 | `pwsh -File tools/export-legacy-data.ps1 -Method backup -AutoConfirm` |
| **C** | 两条主路都不可用 | 若上一版带 adbdb 接口:`pwsh -File tools/export-detected-json.ps1`(无设置);否则手动 |

探测项:包是否存在 / versionName / versionCode / debuggable / allowBackup / adb backup 实际产出 / adbdb 接口。
加 `-Json` 输出一行 JSON 供脚本消费;`-SkipBackupProbe` 跳过备份探针(快,但 backup 结论只是推断);
`-ProbeOutput <path>` 把探针产出的完整 .ab 留下来,之后可 `-AbFile` 复用(省一次备份)。

> 探针必须**等备份进程结束**才判定:.ab 在整包备份完成前一直是 0 字节(adb 先遍历文件树再写流),
> 按"几秒后看文件大小"的实现会把可用误判成不可用(踩过,见 §7)。
> 自动点确认框时也只能按**精确文案**匹配「备份我的数据」——「不备份」里同样含"备份"两字(踩过)。

### 2.1 三条路径

脚本:`tools/export-legacy-data.ps1`(默认 `-Method auto`,按下面顺序自动降级)。

| 方法 | 原理 | 需要 | 拿得到设置? | 说明 |
|---|---|---|---|---|
| `runas` | `adb exec-out run-as <pkg> cat databases/...` | 上一版是 **debuggable** 构建 | ✅ | 保真度最高;同时拉主库 + `-wal`/`-shm` |
| `provider` | 上一版**已存在**的 `content://<pkg>.adbdb` 接口分页 `query` | 上一版含 `AdbDbProvider`(见 `docs/adb-api.md`) | ❌ | release 构建也能用;单页上限 500 行,脚本自动翻页 |
| `backup` | `adb backup -noapk -noshared <pkg>` → 解包 .ab(去头 + inflate + tar) | 设备允许 adb backup | ✅ | Android 12+ 部分 ROM 禁用;确认框可被 `-AutoConfirm` 自动点掉 |

三条路径产出的 `legacy-export.json` 是**同一份格式**(见 §3);`tools/legacy-export-build.ts` 统一转换,
并且**对表做了统一排序**:runas 走 SQL 排序、provider 走 rowid 分页,导出文件的字节不该因为走了哪条路而不同
(已实测:A/B 两条路径的 songs/songLoops/playlists/playlistEntries/favorites/hiddenSongs/smartPlaylists/preferences
逐字节一致)。

### 2.2 命令

```powershell
# 最常见:自动选路径,导出 + 推到设备
pwsh -File tools/export-legacy-data.ps1

# 只导出到本地先看一眼(不推设备)
pwsh -File tools/export-legacy-data.ps1 -Method runas -SkipPush

# 强制走兜底接口(上一版非 debuggable + ROM 禁用 adb backup)
pwsh -File tools/export-detected-json.ps1

# 指定设备 / 指定包(例如测试 dev 包)
pwsh -File tools/export-legacy-data.ps1 -Serial b1e84dad -ApplicationId com.zjr.hesimusic.dev

# 导出前先停掉上一版,保证数据库快照一致(会打断正在播放的音乐)
pwsh -File tools/export-legacy-data.ps1 -ForceStop

# 复用已有 .ab(例如 detect 的 -ProbeOutput 产物)
pwsh -File tools/export-legacy-data.ps1 -Method backup -AbFile backups\drill\legacy-probe.ab
```

### 2.3 产物

```
backups/exports/<yyyyMMdd-HHmmss>/        # PC 侧(仓库 .gitignore 已忽略 /backups/)
  db/hesimusic_db[,-wal,-shm]            # runas/backup 路径拉回的原件(保持原样,不就地打开)
  db-build/                              # 构建时复制的副本(SQLite 打开/关闭会 checkpoint 并删除 WAL)
  prefs/playback_prefs.xml ...           # 只取 allowlist 里的设置文件
  tables/*.json                          # provider 路径落下的表数据
  legacy.ab                              # backup 路径的原始 .ab
  source.json                            # 来源元信息(设备/版本/方法)
  legacy-export.json                     # ★ 最终导入文件

/sdcard/Download/hessi-import/<yyyyMMdd-HHmmss>/legacy-export.json   # 推到设备的目标
```

脚本结束会打印条目数、"下一步"和可直接执行的 `am start` 命令。

### 2.4 接上正式机后的固定执行清单

```powershell
# 0) 探测(只读;会弹一次系统备份确认框,自动点掉)
pwsh -File tools/detect-legacy-app.ps1

# 1) 按探测结论导出(runas 优先;不行用 backup)
pwsh -File tools/export-legacy-data.ps1 -Method runas
#   或(只有 backup 可用时)
pwsh -File tools/export-legacy-data.ps1 -Method backup -AutoConfirm
#   或(两条路都不可用,但上一版带 adbdb 接口;不含设置)
pwsh -File tools/export-detected-json.ps1

# 2) 安装 v2(与既有 app 并存;构建由 lead 统一做)
adb install -r -t app/build/outputs/apk/v2/debug/app-v2-debug.apk

# 3) 打开导入页:选目录 → 看预览 → 开始导入
adb shell am start -n com.zjr.hesimusic.v2/com.zjr.hesimusic.v2.legacyimport.LegacyImportActivity

# 4) 断言(可选:无人值守全链路演练,结束默认卸载 v2)
bun run tools/v2-import-drill.ts --apk app/build/outputs/apk/v2/debug/app-v2-debug.apk --method runas
```

> 部分 ROM(OnePlus/ColorOS)在 `adb install` 时会插一个系统「安装向导」确认页;
> 演练脚本会卡在这一步,需要人点一下「继续安装」。

---

## 3. 导入文件格式

= 上一版 app 的**备份方言**(`version: 4`,字段与 `data/BackupRestoreManager.kt` 一致)+ 只读元信息。
因此两个方向都成立:

1. v2 能导入本脚本的产物;
2. v2 也能导入用户在上一版里用「备份数据库到 JSON」导出的文件(没有 `format`/`source` 字段,同样识别)。

| 字段 | 类型 | 说明 |
|---|---|---|
| `format` | string | 固定 `"hesi-legacy-export"`;上一版 app 自带备份没有该字段 |
| `formatVersion` | int | 当前 1;更高版本会被拒绝并提示升级 |
| `version` | int | 备份方言版本,当前 4(含 `songLoops`);> 4 拒绝 |
| `exportedAt` | long | 导出时间(epoch ms) |
| `generator` | string | 产出工具与路径,如 `tools/export-legacy-data.ps1 (runas)` |
| `source` | object | applicationId / versionName / versionCode / databaseVersion / deviceSerial / deviceModel / androidRelease / method |
| `songs[]` | array | 曲库行(**id 是上一版的 id**,导入时只用于关联;落库重建) |
| `songLoops[]` | array | 循环记录(含 `confirmed` 与可选的 `candidatesJson`) |
| `playlists[]` / `playlistEntries[]` | array | 静态歌单与条目(按 `playlistId/songId` 关联) |
| `favorites[]` / `hiddenSongs[]` | array | 以 `(filePath, startPosition)` 为主键 |
| `smartPlaylists[]` | array | SQL 歌单(`name` + `sql`) |
| `preferences` | object | 文件名 → `[{key,type,value}]`;type ∈ string/boolean/int/long/float/string_set |

解析是**逐条容错**的:单条坏记录(缺 filePath、采样率 0、循环点非法…)计入 warnings 并跳过,不阻断整体导入;
版本/格式不认识才会整份失败。缺 `songs` 段却带循环记录/歌单条目时会直接报错——那种文件无法把 id 映射到本机。

### 3.1 设置的导入范围(白名单)

| 文件 | 处理 |
|---|---|
| `playback_prefs` | 除下列**播放状态**键外全部导入:主题、列表阈值、通知开关、`repeat_mode`/`shuffle_mode_enabled` 等播放偏好、绝对路径形式的图片 URI |
| `playback_prefs` 的播放状态键 | 拦截:`queue_ids` / `current_song_index` / `current_song_id` / `current_position` / `playlist_type` / `playlist_value` / `state_version` —— 它们装的是上一版的歌曲 id,搬过来就是一条指向不存在歌曲的队列 |
| `scan_prefs` / `sort_prefs` | 全量导入 |
| `loop_settings` | **不导入**(循环引擎选择 + "默认值已迁移"版本标记,属于算法/引擎工作线,导入旧值会把引擎选择悄悄改回去) |
| `loop_intent` / `loop_notices` | 不导出也不导入(一次性状态/已读标记) |
| 主题/列表背景图片 | 只有 `/storage/...` 或 `file://...` 才导入;`content://`(SAF 授权属于上一版 UID)跳过 |

白名单/黑名单集中在 `legacyimport/LegacyImportPreferences.kt` 的 `LegacyPreferencePolicy`,单测覆盖。

---

## 4. 导入(v2 内)

### 4.1 入口

1. 桌面图标「导入上一版数据」;
2. `adb shell am start -n com.zjr.hesimusic.v2/com.zjr.hesimusic.v2.legacyimport.LegacyImportActivity`;
3. 带文件直达(会立刻解析并显示预览,便于脚本化验收):
   `adb shell am start -n com.zjr.hesimusic.v2/com.zjr.hesimusic.v2.legacyimport.LegacyImportActivity -d file:///sdcard/Download/hessi-import/<时间>/legacy-export.json`
4. 无人值守(仅 **debug** 构建认这个 extra):再加 `--ez autoImport true`,解析完立刻开始导入。

### 4.2 页面流程

```
选择导出目录(或单个 JSON)
   → 显示来源/内容摘要(歌曲 N · 循环记录 M · 歌单 X(Y 条)…)
   → 选模式 + 选项(每次改动即时重算预览)
   → 「开始导入」→ 结果卡(计数 / 用时 / 备份路径)+ 提示(被跳过的条目)
```

- 「选择导出目录」会先看所选目录,再看**下一层子目录**,自动取最新的一份 `*.json`
  (导出布局是 `hessi-import/<时间戳>/legacy-export.json`,选父目录或时间戳目录都行);
- 目录授权会持久化,下次重开不用再选。

### 4.3 选项

| 选项 | 默认 | 语义 |
|---|---|---|
| 全部导入 | ✅ | 歌曲(缺的补上)+ 循环记录 + 歌单 + 收藏 + 隐藏 + SQL 歌单 + 设置 |
| 只导入循环记录 | | 不新增歌曲、不动歌单/收藏/设置;本机没有的歌对应的循环记录跳过并报告 |
| 覆盖已有的循环记录 | ☐ | 默认保留本机已确认的结果;勾选后用上一版结果覆盖 |
| 同时导入设置 | ✅(全部导入时) | 见 §3.1 |

### 4.4 匹配与幂等

| 对象 | 匹配键 | 已存在时 |
|---|---|---|
| 歌曲 | `(filePath, startPosition)` | 复用本机 id,不改本机元数据(CUE 同一文件的多条音轨靠 startPosition 区分) |
| 循环记录 | 目标歌曲(映射后)的 songId | 默认保留;勾选覆盖才替换 |
| 静态歌单 | `name`(精确) | 复用;条目按 `(playlistId, songId)` 去重,新条目追加在本机末尾 order 之后 |
| 收藏 / 隐藏 | `(filePath, startPosition)` | 保留已有(不覆盖 dateAdded) |
| SQL 歌单 | `name` | 保留已有;新歌单先在本机曲库上校验 SQL,失败跳过并报告 |
| 设置 | 文件 + 键 | 值相同则不写(所以第二次导入真的是"无操作") |

因此**同一份文件导入两次,库内容与设置完全一致,第二次判为"没有需要导入的新数据"**(不写库、不生成备份)。

### 4.5 安全与失败

- 写库全部在一个 Room 事务里;任何异常 → 整体回滚,异常抛到 UI 显示;
- 写库前自动备份本机数据库:`wal_checkpoint(TRUNCATE)` 后拷贝到
  `files/import-backup-<yyyyMMdd_HHmmss_SSS>.db`(保留最近 5 份,checkpoint 失败时连 `-wal/-shm` 一起拷);
- 设置(SharedPreferences)在事务**之后**写,失败只记日志(数据库部分已提交,不会半途而废);
- 所有跳过都在结果页的「提示」里列出(最多 30 条 + 汇总)。

---

## 5. 单测与演练

```powershell
# 只跑导入相关单测(不产 APK、不碰设备)
./gradlew :app:testProdDebugUnitTest --tests "com.zjr.hesimusic.legacyimport.*"

# 只编译 v2 变体的 Kotlin(确认 flavor/源集/清单能过)
./gradlew :app:compileV2DebugKotlin
```

覆盖点(`app/src/test/java/com/zjr/hesimusic/legacyimport/`):

- 解析:完整导出、上一版自带备份(无 meta)、CUE 同文件不同起点、坏记录跳过、版本/格式拒绝、设置分组;
- 导入:歌曲 id 重映射(CUE 两轨分别接线)、歌单/条目/收藏/隐藏/SQL 歌单、设置白名单与幂等、
  **重复导入两次结果一致**、**写库中途失败整体回滚**、**歌曲不在本机时跳过并报告**、
  只导入循环记录、覆盖开关、重新扫描后按路径重新接线、SQL 歌单校验失败跳过。

fixture:`app/src/test/resources/import/legacy-export-sample.json`(结构与真实导出完全一致)。

### 5.1 真机演练(一条命令)

```powershell
# 安装 v2 → 自动导入 → 断言 → 截图/日志 → 卸载 v2(默认)
bun run tools/v2-import-drill.ts --apk app/build/outputs/apk/v2/debug/app-v2-debug.apk --method runas
```

演练做的事:

1. 查上一版基线计数(演练后必须完全不变);
2. 调 `export-legacy-data.ps1` 导出(`-SkipPush`,再自己把 JSON 拷进 **v2 私有目录**,
   用 `run-as` 写入 → 导入页用 `file://` 读,无需给 v2 授予"所有文件访问权限");
3. `adb install -r -t` 装 v2;
4. `am start ... -d file:///data/user/0/com.zjr.hesimusic.v2/files/drill-export.json --ez autoImport true`;
5. 等 logcat 里 `LegacyDataImporter: 导入完成`,然后逐项断言:
   歌曲数、歌曲(路径+起点)集合、循环记录数与循环点、歌单名集合、歌单内**顺序**、收藏、隐藏、SQL 歌单、
   设置写入项数、导入前备份、导入耗时;
6. 再触发一次导入 → 断言"无操作"且计数不变;
7. 查上一版计数 → 断言未变;
8. `screencap` + `uiautomator dump` 存证据,`adb uninstall com.zjr.hesimusic.v2`;
9. 写 `drill-report.md`(PASS/FAIL 表 + 证据文件清单),退出码 0 表示全通过。

### 5.2 已跑过的演练结果(2026-11-22,OnePlus 8T / Android 14,serial `b1e84dad`)

上一版:`com.zjr.hesimusic 2.6.25-00029-pausedskip`(debuggable),自带
1746 歌 / 18 循环 / 2 歌单(142 条)/ 11 收藏 / 32 隐藏 / 2 SQL 歌单。

**导出阶段**(三条路径各跑一次):

| 路径 | 结果 |
|---|---|
| B runas | 成功:1746 / 18 / 2(142)/ 11 / 32 / 2 + 设置 1 文件 9 项 |
| A adb backup | 确认框自动点掉,70.99 MB / 14.2 s;解包后条目与 B **逐字节一致** |
| provider | 同上,但设置段为空、数据库版本号为空(按设计) |

**导入阶段**(A、B 各做一次完整演练,均 19/19 断言通过):

| 断言 | 结果 |
|---|---|
| v2 起点空库 / 两个包并存 | PASS |
| 歌曲数 1746、按(路径, CUE 起点)零缺失 | PASS |
| 循环记录 18 条、循环点与目标歌曲全部一致 | PASS |
| 歌单名 `["原神战斗","温柔的"]` 一致;两份歌单内**顺序**一致(28 首 / 114 首) | PASS |
| 收藏 11 / 隐藏 32 / SQL 歌单(内置「无缝循环」重名跳过 + 「HOYO-MiX」导入) | PASS |
| 设置写入 2 项(白名单外的队列/位置/URI 被拦截) | PASS |
| 导入前自动备份 `files/import-backup-<ts>.db`(90112 bytes) | PASS |
| 导入耗时 545 ms(A)/ 618 ms(B) | PASS |
| 第二次导入判为"无操作"、数据计数不变 | PASS |
| **上一版数据完全未变**(6 张表计数逐一相等) | PASS |
| 截图 + UI 树证据、演练后 `adb uninstall` 掉 v2 | PASS |

证据(A/B 各一份,本地不入库):`backups/drill-final-A/` 与 `backups/drill-final-B/`:
`drill-report.md`(PASS/FAIL 表)、`logcat-import.txt`、
`v2-import-first-result.png` / `v2-import-second-result.png`、
`v2-import-first-ui-texts.txt` / `v2-import-ui-texts.txt`、`export/legacy-export.json`。

### 5.3 演练途中踩到的坑(改代码前先看)

1. **包名不能叫 `import`**:KSP/Hilt 生成的 Java 源文件里 `package ...import;` 非法,表现为 KSP 内部 NPE。
2. **adbdb 接口单次最多 500 行**:直接 `SELECT ... FROM songs` 只拿到 500 首,断言会误判;
   演练脚本统一按主键翻页(`fetchAll`)。
3. **`am start` 命中栈顶同组件不会重建**:第二次导入要加 `-S`(先 kill)否则"第二次导入"根本没发生。
4. **ColorOS/OPPO 的"安装向导"确认页**会挡住 `adb install`:演练脚本用 uiautomator 自动点「继续安装」。
5. **adb backup 的 .ab 在完成前一直是 0 字节**:探针必须等进程结束才判定,不能看几秒后的大小。
6. **「不备份」按钮里也含"备份"两字**:自动点确认框必须精确匹配「备份我的数据」。
7. **`pm clear` 在部分 ROM 上清不掉数据**:演练脚本额外 `run-as ... rm -rf databases shared_prefs` 保证空库;
   否则设置项因"值已相同"而计数为 0。

---

## 6. 已知限制 / 注意

1. **先让 v2 扫描完曲库再导入**。导入按路径匹配,本机没有的歌对应的循环记录/歌单条目会被跳过并报告;
   若 v2 之后又做**全量重扫**,歌曲 id 会重建,此时**再导入一次**即可把循环记录/歌单条目接回新 id
   (匹配键是路径,不依赖 id;单测 `重新扫描后按路径与起点重新接线循环记录` 覆盖)。
2. provider 路径**拿不到设置**(SharedPreferences 不经过 adb 数据库接口),也不报数据库版本;需要设置请用 runas / backup。
3. `loop_settings` 与 `loop_intent`/`loop_notices` 不导入,理由见 §3.1。
4. 导入是**合并**,不是覆盖式还原:上一版删掉的歌单/收藏不会从 v2 里删掉。需要完全一致的副本请用 v2 自己的「备份与还原」。
5. 同一份文件里出现**同名歌单**(上一版允许)时:第一个按名字复用/新建,后续同名歌单都会新建同名副本(条目不会混在一起)。
6. 设置里的队列/当前播放位置**不迁移**:v2 首次播放按自己的队列逻辑重新开始。
7. 本任务不构建正式包、不安装到用户设备:APK 与正式安装由 lead 统一做(v2 变体名 `v2Debug`/`v2Release`)。
8. `adb backup` 在 Android 12+ 属废弃接口,部分 ROM 直接禁用;检测脚本会如实给出结论,别硬编码路径。

---

## 7. 相关文件

| 路径 | 作用 |
|---|---|
| `app/build.gradle.kts` | `v2` flavor(applicationId/label/version 后缀) |
| `app/src/v2/AndroidManifest.xml` | 注册导入页与桌面别名(只在 v2 变体合并) |
| `app/src/v2/java/com/zjr/hesimusic/v2/legacyimport/` | Activity + Compose 页面(只在 v2 变体) |
| `app/src/main/java/com/zjr/hesimusic/legacyimport/` | 导入引擎 + 页面 ViewModel(全变体编译,只有 v2 有入口) |
| `tools/detect-legacy-app.ps1` | 判定 A/B/C(包/版本/debuggable/allowBackup/backup 探针/adbdb) |
| `tools/export-legacy-data.ps1` | 导出编排(三条路径 / 推送 / 自动确认框 / 提示) |
| `tools/export-detected-json.ps1` | 兜底别名(= `-Method provider`) |
| `tools/legacy-export-build.ts` | SQLite / tables / .ab → `legacy-export.json`(Bun) |
| `tools/legacy-backup-confirm.ps1` | 自动点掉系统 adb backup 确认框(uiautomator) |
| `tools/v2-import-drill.ts` | 真机演练:装 v2 → 导入 → 断言 → 证据 → 卸载 |
| `app/src/test/java/com/zjr/hesimusic/legacyimport/` | 单测 |