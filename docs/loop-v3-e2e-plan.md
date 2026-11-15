# P4 E2E 与回归测试方案(v3)

> 目的:把「无缝循环功能完整、无 bug」落成**可执行、可复现、可门禁**的用例集。
> 两个独立证据来源:①HTTP 协议(行为)②PCM 取证(音频层真相,判据见 plan 6.2 节)。
> 执行器:tools/loop-e2e.ps1(待实现);证据 e2e-shots/;汇总 e2e-results.json。

## 0. 约定

- 被测包:com.zjr.hesimusic.dev(dev 后缀,不污染日常包);release 包只验「启动正常 + 无无缝入口」。
- 协议:adb forward tcp:18765 tcp:8765,然后访问 http://127.0.0.1:18765。
- UI 路径类用例:uiautomator dump 按**控件文本**定位真实坐标后 input tap(不用固定坐标)。
- 时间测量用 PC 时钟;≤0.5s 的用例用 250ms 轮询并注明 overhead(判罚放宽到 ≤0.8s)。
- 每个用例至少 1 项可复查产物(PASS/FAIL 由脚本判定,不靠人耳)。

## 1. L2 行为用例(协议驱动)

### A 分析链

| # | 用例 | 判据 |
|---|---|---|
| A1 | 进度双阶段(POST /analyze + 500ms 轮询 /analysis) | Decoding 与 Analyzing 各 >=1 样本;全局进度非降;无 Failed |
| A2 | 分析耗时 | 165s 级曲 <=15s;logcat LoopPerf 有 decode= 与 analyze= 各一条 |
| A3 | 分析即暂停 | 播放中 /analyze 另一首 -> 100ms 内 playing=false |
| A4 | 单飞 | 连续两次 /analyze -> 第二次 409,状态不跳变 |
| A5 | 临时结果落库 | 分析完成 -> /song/{id} confirmed=0 且 A/B 与 Done 候选一致 |
| A6 | 已确认不被覆盖 | /confirm 后再 /analyze -> confirmed 仍=1,内容未变 |

### B 预览(UI 页;协议 /ui/preview/* 后补)

| # | 用例 | 判据 |
|---|---|---|
| B1 | 四模式 | 4 次请求回读 state.mode 一致 |
| B2 | A/B 松手生效 | 改 B 后播到新 B 折返到 A(位置序列) |
| B3 | 接缝检查起点 | 起播位置 ∈ [B-5s, B-4.5s] |
| B4 | 预览流畅 | gfxinfo p90 <=16ms、janky <=5%(10s 静置,播放中) |

### C 播放链(核心)

| # | 用例 | 判据 |
|---|---|---|
| C1 | 已确认歌 + 单曲循环 = 无缝 | 3s 后 /state: window!=null 且 seamCount 增长或 isReplaying=true |
| C2 | 环回正确 | 200ms 采样 60 次跨 B:displayPos 全程 ∈ [A-1s, B+0.5s];跨越后回落到 [A,A+3s];playing 恒 true。**displayPos 取折返后的展示口径**(协议端再折返一次是幂等的);MediaSession 内部约 1s 的 PlaybackState 外推不算缺陷 |
| C3 | 未确认歌不无缝 | window=null、无 A/B 标记 |
| C4 | 切歌取消 | window=null;repeat 未被引擎改写(前后一致);新歌正常播放 |
| C5 | repeat 改 ALL/OFF | <=1s window=null;整曲播放 |
| C6 | demote/delete | <=1s window=null;播放不中断 |
| C7 | 重启恢复 | seek 到区内 -> 2s -> 杀进程冷启:恢复位置 ≈ 展示位置(±15s);再 play 后仍无缝并从恢复位继续 |
| C8 | 切到另一首已确认歌 | 新歌自动无缝(window.songId=新歌) |
| C9 | 通知栏/协议 seek 到区内 | 预卷或 degrade <=1 次,1 轮内恢复 seamless |
| C10 | 暂停/恢复 | seamCount 继续增长;位置不跳变(±1s) |

### D 其他

| # | 用例 | 判据 |
|---|---|---|
| D1 | 智能歌单「无缝循环」 | 歌单存在;内容 = confirmed=1 集合 |
| D2 | CUE 分轨守卫 | 菜单项存在但置灰;点击不导航 |
| D3 | 应用名区分 | dev label=海瑟音乐 Dev;release=海瑟音乐 |
| D4 | 备份/恢复(song_loop 是否纳入待定) | 若纳入:导出->清库->导入 后行恢复 |

## 2. L3 PCM 取证(真无缝的唯一判据)

矩阵每项跑一次 tap 取证,adb pull 后执行:
bun run tools/spike-analyze.ts <wav> <jsonl> --mode sink

| # | 矩阵项 | 判据 |
|---|---|---|
| L3-1 | MP3, A=20s B=50s, 115s | exit 0(A1/A2/A3/B 全 PASS) |
| L3-2 | FLAC, 同上 | exit 0 |
| L3-3 | AAC/M4A, 同上 | exit 0(设备解码支持时) |
| L3-4 | 长跑 300s(>=28 接缝) | A2 全部 0 漂移 |
| L3-5 | 区内 seek 后连续跨 3 接缝 | 降级 <=1 次,之后 0 漂移 |
| L3-6 | 从 0 播(前奏) -> 首次环回 | 接缝 A1/A2 通过 |
| L3-7 | 暂停/恢复后跨 2 接缝 | 0 漂移 |
| L3-8 | 锁屏播放跨 2 接缝 | 0 漂移 |

门禁:exit code == 0 且 failedMust 为空(A4 的喂数间隔是参考项)。
tap 开关:debug 协议 POST /debug/tap(路径)或直连 LoopAudioController.setPcmTap;产物拉 e2e-artifacts/(不入库)。

## 3. L4 既有功能回归

| 模块 | 用例 |
|---|---|
| 队列/恢复 | 播放列表播放 -> 杀进程 -> 冷启:队列/索引/位置/repeat/shuffle 全部还原 |
| 首尾绕回 | 最后一首 next -> 第一首;第一首 prev -> 最后一首 |
| 歌单/收藏 | 建/加歌/移除/改名/删除;收藏切换 |
| 扫描 | 启动扫描、过滤开关、增量重扫后歌单关联不丢 |
| 排序/首字母 | 各视图排序切换 + 首字母跳转 |
| 备份恢复 | 导出 -> 清库 -> 导入:库/歌单/收藏/设置一致 |
| 通知/耳机键 | 通知按钮、耳机播放暂停与上下首 |
| 睡眠定时 | 1 分钟到点暂停 |
| CUE | 分轨播放与列表显示 |
| DB 迁移 | v7 升级到 v9 成功且旧数据完整 |

## 4. L5 竞态风暴(针对上一版所有竞态场景)

随机 200 次操作,间隔 100~800ms,操作集:play / pause / next / previous / seek(区内·区外·曲尾) /
repeat one|all|off / 切歌(已确认·未确认) / confirm / demote / delete / analyze / 前后台切换 / 通知栏操作。
**全程并行跑 PCM tap 取证。**

判据:
1. 无 crash、无 ANR(logcat 无 FATAL);
2. **界面与行为一致**:window!=null <=> 当前歌 seamCount 增长或 isReplaying;UI 标记与 window 同源;
3. 无卡死(playing=true 且位置连续 5s 不动);
4. tap 片段的可判定部分 A1/A2 不破;
5. 结束后 30s 内能稳定回到正常播放。

## 5. 执行顺序与门禁

1. 冒烟:装 dev 包 -> /state 可用 -> /analyze 成功(JNI 在设备上真跑)-> /confirm -> /play -> seamCount 增长;
2. L2 A 组 -> C 组 -> D 组;
3. L3 矩阵(可按格式分批);
4. L4 回归;
5. L5 风暴;
6. 全 PASS 才允许合并 main 并出包。

## 6. 产物

- e2e-results.json:{id, status, metrics, evidence[], startedAt, durationMs}
- e2e-shots/<case>/":截图、JSONL 片段、logcat 摘录
- e2e-artifacts/:L3 的 tap.wav/tap.jsonl 与 analyzer 报告(体积大,不入库)