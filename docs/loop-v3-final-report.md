# 无缝循环 v3 · 交付报告

> 分支:`feat/loop-v3` 已合并进 `main`(merge commit `5b70157`);备份 `backup/glm-loop-wip-20261115`(`3bbb081`)保留旧实现。
> 日期:2026-11-15。设备:OnePlus 8T(KB2000,Android 14,48kHz 输出)。

## 1. 结论

- **真无缝已实现并被客观取证**:重放轮次与源 `[A,B)` 区间**逐字节相同**,接缝帧距**恒等于 L(0 帧漂移)**,接缝 **0 帧静音**,
  `flush=0 / discontinuity=0 / underrun=0`,连续 300s(9 个接缝)不累积误差。
- **E2E 门禁 29/29 PASS**:L2 行为 19 项、L3 PCM 取证 3 项(MP3/FLAC/300s)、L4 定向回归 6 项、L5 200 次随机操作风暴 1 项。
- **单测 243 个全绿**(30 个套件);debug/release 均构建通过,release 包**不含**任何 debug 协议类。

## 2. 为什么上一版失败,新方案改了什么

上一版把「无缝」实现成**播放列表的时间线切换**(`[clip 0→B] + [clip A→B]` + `REPEAT_ONE`)。实测(探针取证)证明这条路在物理上不可能无缝:
每个窗口/项边界都会触发 `configure()` + `output_stream_offset` + `handleDiscontinuity()`,AudioSink 据此**补 27.8ms 静音**,
且裁剪边界被量化到解码器帧(MP3 每轮多 216 帧 = 0.05% 变慢)。为压制由此产生的重入/回调风暴,旧实现堆了 repeat 影子变量、
`applyingTimeline` 守卫、`isApplying()` 轮询、六处资格判定 —— 越补越乱。

新方案(**M3′**)把环回下沉到**音频管线内部**:

```
PCM 流在 [A,B) 内被捕获进 ring;越过 B 后丢弃输入、把 ring 里的 PCM 经同一个 DefaultAudioSink 继续喂回,
PTS 按 B+(k-1)L+offset 单调递增。全程没有 period/窗口/EOS/seek 边界 → 播放器的时间线与媒体项完全不变。
```

由此得到的结构性好处:

1. **进入/退出无缝不需要任何媒体操作** —— 只写 `LoopAudioController.window` 一个 volatile 值(旧版要重建时间线);
2. **引擎从不修改 `repeatMode`** —— repeat 只代表用户意图,旧版「切歌后 repeat 被写坏」一类 bug 在结构上消失;
3. **窗口只有一个来源** —— UI 的 A/B 标记、音频层环回、位置投影同读 `LoopRuntime.window`;
4. **位置投影是纯函数**(`LoopPosition`),可穷举单测;
5. 队列/随机/通知/锁屏/耳机键/恢复全部走 ExoPlayer 原生路径,零特判。

## 3. 关键实现与踩过的坑(均已修并回归)

| # | 现象 | 根因 | 修法 |
|---|---|---|---|
| 1 | 环回点永远不命中,到 B 就降级 | `ClippingMediaSource` 让 sink 侧媒体时间整体偏移 `1e12`,而 A/B 是媒体时间 | 用 `AudioSink.setOutputStreamOffsetUs` 做域归一化;并**彻底放弃裁剪**(不裁剪同样能环回,还省掉 `replaceMediaItem` 重建路径) |
| 2 | 进入重放约 12s 后冻结 | 重放态无条件吞输入 → 解码器以 17x 实时跑完整首歌 → 提前 EOS → 泵停 | 重放态按 delegate 背压节流:**写不进 delegate 就不消费输入**(自时钟泵) |
| 3 | 重放开始 display 冲出 B(最大 +2.7s) | MediaSession 的 PlaybackState 约 1s 刷新一次,MediaController 在间隙按 speed=1 外推 | 折返下沉到消费侧(UI + 协议端)幂等再折一次;服务层在重放期回退到同步写入的 `audio.window` 作为投影口径 |
| 4 | 重放中 seek 落回 A | 展示目标被映射到跨轮次的源位置,越界触发 flush 后环游标从 0 开始 | 进入重放时按输入 PTS 的折返相位初始化环内游标 |
| 5 | 冷启后 debug 连接绑到空 player | MediaController 抢跑 | debug 侧加有效性判定 + `/debug/reconnect` |

## 4. 交付物

### 4.1 代码

| 层 | 内容 |
|---|---|
| 音频(`loop/audio/`) | `LoopPcmEngine`(纯 Kotlin 状态机,可 JVM 单测)、`LoopAudioSink`(ForwardingAudioSink)、`LoopAudioController`、`LoopRenderersFactory` |
| 纯逻辑(`loop/`) | `LoopWindow` / `LoopPosition` / `LoopEligibility` / `LoopRuntime` |
| 分析(`loop/analysis/` + `cpp/`) | C++ 循环点引擎(JNI,候选与 PyMusicLooper 3.6.0 top1 完全一致)、`PcmDecoder`、`WaveformPeaks`、`LoopAnalysisManager`(单飞/两层结果/单调进度) |
| 服务(`service/`) | `LoopSessionPlayer` / `LoopSessionProjection` / `LoopShapePlan` / `LoopPlaybackCoordinator`;`MusicService` 三处事件接线 |
| 数据(`data/`) | `song_loop`(v8/v9 迁移)、`BackupRestoreManager` v4 备份包含循环点 |
| UI(`ui/loop/`, `ui/common/`, `ui/main/`, `ui/player/`) | 分析/预览页(骨架首帧、预渲染波形、A/B 拖拽、候选弹层、四模式)、菜单四项、播放页与底部条 A/B 标记、展示折返 |
| debug(`app/src/debug/`) | L2 HTTP 协议服务(仅 debug 源集,release 零污染) |

### 4.2 测试与工具

- `tools/loop-e2e.ps1`(120KB):29 个用例的自动化执行器,forward 自愈、冷启静默、adbdb 兜底、session 外推对照;
- `tools/spike-analyze.ts`:PCM 取证分析器(零间隙/零漂移/flush-underrun/逐轮逐位相同/PTS 连续性,带 58 项自测);
- `tools/loop-wav-check.ts`:按 A/B 几何独立复核"重放轮次与源区间逐位相同";
- `tools/loop-smoke.ps1`:主链快速巡检;
- `spike/`(默认不参与构建):P0 机制探针与结论存档,见 `spike/README.md`。

### 4.3 文档

- `docs/loop-v3-plan.md`:架构方案 + P0 实测结论 + 判据;
- `docs/loop-v3-m3-design.md`:音频层实现契约;
- `docs/loop-v3-service-design.md`:服务层接线(含"不裁剪"的定稿说明);
- `docs/loop-v3-ui-contract.md`:UI 契约;
- `docs/loop-v3-e2e-plan.md`:E2E 方案 + 回归证据;
- `docs/loop-v3-m1-design.md`:被淘汰的压缩域方案(留作追溯)。

## 5. 测试与证据汇总

| 层 | 结果 |
|---|---|
| L0/L1 单测 | **243 tests / 0 failures**(折返算术、seek 映射、资格判定、捕获切帧、背压、降级、投影、UI 折返…) |
| L2 协议行为 | A1–A6、C1–C11、D1、D3 **全 PASS** |
| L3 PCM 取证 | MP3 / FLAC / 300s 长跑 **全 exit 0**(A1/A2/A3/A4/B),wav-check 复核逐位相同 |
| L4 定向回归 | 队列杀进程恢复、首尾绕回、repeat 持久化且引擎不改写、备份 v4 含 songLoops、通知/锁屏、库/歌单/收藏冒烟 **全 PASS** |
| L5 风暴 | 200 次随机操作:一致性违规 0、卡死 0、FATAL 0,结束后恢复无缝 |
| DB 迁移 | 真机 v7→v9,1327 首歌库完整,旧 `song_loop` 行可读,智能歌单种子生效 |

证据:`e2e-results.json`、`e2e-shots/<case>/`、`e2e-artifacts/`(本地,已 gitignore)。

## 6. 已知取舍与遗留(不阻塞交付)

1. **冷启动 ring 为空**:捕获式方案的固有代价。生产路径无感(从 0 播放会自然填满;恢复/区内起播有一次预卷 seek)。
   预览页「接缝检查(B−5s)」在全新会话的首轮会提示先试听一轮。**建议后续**:进页后台预解码 `[A,B)` 预填充 ring。
2. **MediaSession 的 PlaybackState 刷新间隔约 1s(实测偶发 4s)**:直接读 PlaybackState 的消费者会看到最多一个间隔的滞后;
   应用内 UI 与协议端已做折返,用户可见进度正常。彻底解决需在接缝处强制刷新 session(Media3 无公开 API)。
3. **`/library/songs?playlist=` 不解析智能歌单**(协议缺口,产品侧「无缝循环」歌单本身正常)。
4. **排序切换/扫描触发** 无协议入口,L4 未自动化(UI-only)。
5. **CUE 分轨守卫**只有代码路径,设备上无 CUE 曲目未实测。
6. `spike/` 模块默认不参与构建(源码保留作探针),需要复跑时按 `spike/README.md` 打开。

## 7. 如何使用/复验

```powershell
# 构建
.\gradlew.bat :app:assembleDebug
# 开发包(独立包名与应用名,不干扰日常包)
cmd /c ".\gradlew.bat :app:assembleDebug -PdevPackage=.dev --console=plain"
# 设备 E2E
adb forward tcp:18765 tcp:8765
powershell -NoProfile -File tools/loop-e2e.ps1                     # L2 全量
powershell -NoProfile -File tools/loop-e2e.ps1 -Case L3-1,L3-2,L3-4 -AppendResults
powershell -NoProfile -File tools/loop-e2e.ps1 -Case L5            # 风暴
```

> 未推送远端:`main` 本地领先 `origin/main` 34 个提交,是否 push / 打 tag 发版由你决定。