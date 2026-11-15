# 无缝循环 v3 · 架构方案与测试方案(待确认,未开工)

> 状态:**提案,等用户裁定**。本文档不含实现代码,确认后才动工。
> 基线:`main` = `c55ffe4`(无缝循环功能之前的最后一个提交,已验证 `:app:compileDebugKotlin` 绿)。
> 备份:`backup/glm-loop-wip-20261115` = `3bbb081`(GLM 全部未提交改动,187 文件快照;按约定排除 build 产物/测试 wav/截图)。
> 工作分支:`feat/loop-v3`(已从基线拉出,当前工作树干净)。

---

## 0. 结论先行

1. **上一版失败的根因不在算法,也不在 UI,而在"把无缝实现成了播放列表的时间线切换"**:环回点 = period 边界 = 一次 seek → 必然 flush + 解码器/sink 重建 → 既产生可闻间隙(数学上不可能无缝),又引入 apply/teardown 重入竞态,进而需要一堆影子变量(repeat 快照、代次令牌、isApplying 轮询)去压制,越补越乱。
2. **真无缝的唯一落点:环回必须发生在"音频管线内部"**,即 PCM/样本流在环回点连续输出,**不存在 period 边界、不存在 seek、不存在 EOS、不存在 flush**。播放器的媒体项与时间线在整个无缝播放期间**完全不变**。
3. **是否必须 fork Media3**:不一定。Media3 1.8.0 已提供三个关键扩展点(已在本地 AAR 中核实存在):`DefaultRenderersFactory.buildAudioSink`(可注入自定义 AudioSink)、`androidx.media3.exoplayer.audio.ForwardingAudioSink`(可包装默认 sink)、`MediaSource/MediaPeriod/SampleStream`(公开 API,可做环回源)。**fork 的正确形态是"vendor 单模块源码 + 单文件补丁",不是全仓 fork**;只有在公开扩展点无法满足时才启用。
4. **架构上的目标形态**:单一引擎(仍用 ExoPlayer + MediaSession)+ 环回机制下沉到音频管线 + **单一派生状态**(纯函数算出"当前是否平滑循环、A/B 是多少")+ **一层纯函数位置映射**。所有旧影子变量与重入守卫**全部删除**,不是重写。
5. **测试方案的核心新增**:PCM 取证。把真正输出到 AudioTrack 的 PCM 落盘,PC 侧离线判定"接缝无静音/无丢帧/零漂移/零 underrun"(理想档:每轮逐位相同)。这把"真无缝"从"人耳听"变成可自动化、可复现、可回归的断言。

---

## 1. 上一版架构级根因(逐条对应现象)

| # | 根因 | 现象 |
|---|---|---|
| R1 | 无缝 = 两项时间线 `[clip 0→B] + [clip A→B]` + `REPEAT_ONE`。环回触发的是 **seek**(ExoPlayer 的 REPEAT_ONE 语义就是 seek 回 0),必然 flush 音频 sink + 重置解码器 | 回跳瞬间可闻间隙;偶发卡顿/爆音 |
| R2 | 每次 apply/teardown 都是 `setMediaItems + prepare + seek`,会触发 transition/timeline/repeat 回调;这些回调又调用 apply/teardown | 重入 → 需要 `applyingTimeline` 守卫 + `isApplying()` 20×50ms 轮询(阻塞 1s) |
| R3 | `repeatMode` 被同时当作"用户意图"和"引擎生命周期"(窗口 0 用 OFF、窗口 1 用 ONE) | 需要 `userRepeatMode/expectedRepeatMode/prevRepeatMode/lastRepeatMode` 四个影子值;任何一处漏判就写坏状态(v7.1 §13 实锤:切歌后 repeat 未恢复) |
| R4 | 无缝资格判定散布在 6 个入口(transition / repeat / READY / restore / confirm / demote / delete),每个入口各自补一次触发 | 每加一个状态变更源就多一个漏触发点(v9 §15 文档自己承认);表现为"保存后没进无缝""取消最终结果没退出无缝" |
| R5 | UI 的 A/B 标记、引擎是否无缝、DB 行三者各自推断 | 界面与行为不一致,需要"单一来源"补丁(seamlessActiveSongId),仍靠人工维护 |
| R6 | 分析页/预览页把重活放主线程 + 波形每帧 3000 段绘制 | 冷启动进页卡、拖 A/B 迟滞、进度条跳变(上一版打了 12 轮补丁仍未收干) |

**一句话**:R1 决定了"不可能真无缝",R2~R5 是 R1 的直接衍生(因为方案要求"不打断播放地切换时间线形态",而 ExoPlayer 没有这种原子操作)。

---

## 2. 目标语义(用户可见行为)

保留用户已确认的产品语义(沿用 v9 §13.4 裁定与 v6 两层结果),但**实现口径全部重做**:

- **无缝生效条件**:当前歌存在 `song_loop` 行且 `confirmed=1` **且** 用户 repeat == 单曲循环。判定为**纯函数**,在任何事件后重新求值一次。
  - 关键变化:**引擎永不再修改 `repeatMode`**。repeat 从此只代表用户意图 → "切歌后 repeat 被写坏"这一类 bug 在架构上不可能发生,不需要 `prevRepeatMode` 快照与恢复。
- **无缝播放期间**:队列、媒体项、时间线不变;通知/锁屏/耳机键/上一首下一首/随机/队列恢复全部走 ExoPlayer 原生路径,零特判。
- **位置语义**:UI 与通知看到的进度 = 折返后的位置(落在 [A, B) 内),时长 = 整曲时长;进度条上的 A/B 标记语义不变。位置映射是**一层纯函数**(见 §5.2),不参与任何状态机。
- **曲线入口**:长按菜单「无缝循环」→ 有结果直接播放+单曲循环;无结果进分析页。菜单「重新分析」「取消最终结果(降级 confirmed=1→0)」「删除分析结果(删行)」语义沿用 v6/v7。
- **切歌**:新歌若满足条件则自动无缝(状态驱动),否则普通播放;**不再有"恢复进入前 repeat 模式"这种动作**。
- **重启恢复**:位置按折返后的值持久化,冷启动恢复后继续无缝(走原生 restore 路径,无交接)。
- **降级**:循环区间超过内存上限(默认 300s 或 64MB,取先到者)或解码失败 → 该歌普通播放并在分析页明示原因,不静默失败。

---

## 3. 目标架构

```
┌──────────────────────── UI / 通知 / MediaSession ────────────────────────┐
│  MusicViewModel(普通 MediaController)                               │
│  LoopScreen / LoopViewModel(分析 + 预览,独立 PreviewExoPlayer)        │
└───────────────▲──────────────────────────────────────────▲──────────────┘
                │ 位置(已折返)/状态                          │ A/B 标记(读 LoopRuntime)
┌───────────────┴───────────────┐              ┌───────────┴──────────────┐
│ MusicService (MediaSessionService)            │ LoopRuntime              │
│  ├─ ExoPlayer(唯一引擎)       │              │  StateFlow<LoopWindow?>   │
│  ├─ SessionPositionMapper     │◀── 纯函数 ───┤  纯函数 derive()          │
│  │   (ForwardingPlayer 位置口径)              │  事件 → 重算一次          │
│  └─ 事件入口(全部 5 个)──────────────────────┘                          │
└───────────────▲──────────────────────────────────────────────────────────┘
                │ 环回窗口(一个 volatile 值)
┌───────────────┴──────────────────────────────────────────────────────────┐
│ 音频管线:ExoPlayer → RenderersFactory(自定义)→ AudioSink(环回实现)      │
│   环回在 PCM/样本层面完成:无 period 边界 / 无 seek / 无 EOS / 无 flush   │
└──────────────────────────────────────────────────────────────────────────┘
                         ▲
        Room song_loop(沿用 v9 schema:v8 建表 + v9 confirmed 列)
```

### 3.1 组件与职责(全部为新增/重写,不复用旧的循环层)

| 组件 | 职责 | 规模(估) |
|---|---|---|
| `LoopWindowRegion`(纯 Kotlin,无 Android 依赖) | A/B 窗口 + 折返算术:位置映射、seek 映射、帧/µs 换算、环缓冲索引 | ~150 行,**100% 单测** |
| `LoopEligibility`(纯函数) | `(songId, repeatMode, SongLoop?) -> LoopWindow?`;**唯一**判定入口 | ~40 行,单测 |
| `LoopRuntime` | 持有 `StateFlow<LoopWindow?>`;事件(transition/repeat/行变更/restore 完成)只调用 `refresh(songId, repeatMode)` | ~80 行 |
| 环回引擎(机制待 spike 定,见 §4) | 真正让 PCM 在 B 处连续折返 | 300~900 行 |
| `SessionPositionMapper`(ForwardingPlayer) | 只做位置/seek 口径转换,不持有状态、不做媒体操作 | ~120 行 |
| `LoopAnalysisManager` | 分析状态机(Idle/Decoding/Analyzing/Done/Failed)+ 临时结果落库/确认/降级/删除 | 复用备份 + 审查 |
| `PcmDecoder` | MediaExtractor+MediaCodec 解码(分析用 mono;seek 后补环所需缺失段) | 复用备份 + 审查 |
| C++ `loop_engine` | 循环点分析(移植自 PyMusicLooper) | 复用备份(有 CLI 与参考数据对照) |

**删除清单**(旧实现里这些全部消失):`LoopPlaybackController`、`LoopTimelineExecutor`、`LoopSessionState`、`loopApplied`、`applyingTimeline`、`isApplying()` 轮询、`userRepeatMode/expectedRepeatMode/prevRepeatMode/lastRepeatMode`、`applyLoopTimeline/teardownLoopTimeline`、`seamlessActiveSongId`(由 LoopRuntime 取代)、`delegatePositionProviderMs`。

### 3.2 为什么这样能根除竞态(结构论证)

- **无缝不再改变任何"媒体结构"**:媒体项、时间线、repeat 全程不变 → 不存在"应用/拆除"动作 → 不存在 apply 重入、不存在 transition 风暴、不需要任何守卫变量。
- **状态只有一个来源**:`LoopRuntime.window` 是 UI 标记、引擎环回、位置映射三者共同的唯一输入 → "界面与行为不一致"在结构上不可能(R5)。
- **判定只有一个入口**:5 个事件入口都只是"重算一次纯函数",没有分支各写一套 → 漏触发只能表现为"少算一次",而重算幂等且被"事件风暴测试"覆盖(R4)。
- **repeat 只代表用户意图**:引擎永不写 repeat → R3 类 bug 消失。
- **位置映射是纯函数**:无状态、无时序,可直接单测穷举(R1 的 UI 侧后果消失)。

---

## 4. 环回机制选型(spike 决策,唯一未定项)

### 4.1 候选

| 代号 | 机制 | 原理 | 优点 | 风险 |
|---|---|---|---|---|
| **M1** | 自研环回 `MediaSource/MediaPeriod`(公开 API) | 单 period 内由我们的 `SampleStream` 连续产出 [A,B) 样本、时间戳单调递增、永不 EOS;内部按需预取下一轮子 period | 不动 Media3 源码;解码器不重启;容器/格式无关 | 需要摸清 SampleStream/MediaPeriod 契约(缓冲位、丢弃位、TrackGroup 映射),实现量中上 |
| **M2** | vendor `media3-exoplayer` 源码 + 补丁 `ProgressiveMediaPeriod` | 把 extractor 读到 B 时 seek 回 A、给样本时间戳加轮次偏移,不产生 EOS | 代码量最小(补丁 ~100 行),行为最贴近原生 | 需要把模块以源码纳入构建(编译时间↑、跟随上游成本);耦合内部实现 |
| **M3** | 自定义 `AudioSink`(包装 `DefaultAudioSink`) | 在 PCM 输出层捕获 [A,B) 并原地重放;**逐位相同**的环回 | 数学上最严格;格式无关;不动 MediaSource | **关键未知**:renderer 在输入 EOS 后是否还会继续喂 buffer。若不会,M3 需配合"永不结束的输入源",否则出局 |
| M4(兜底) | `SimpleBasePlayer` 门面 + 自研 PCM 引擎(无缝歌)与 ExoPlayer(普通歌)双后端 | 单 Player 门面统一对外 | 绝对可控 | 队列/随机/恢复/通知全部要自己实现,回归面最大 |

> 说明:备份里旧实现用的是 `ClippingMediaSource` 两项 + `REPEAT_ONE`。这**不是** ExoPlayer 的 gapless 路径:播放列表里"推进到下一个窗口"那条路径(格式相同时不 flush sink)才是。因此 M1/M2 之外的"用 `LoopingMediaSource` 或 `ConcatenatingMediaSource2` 拼"也在 spike 里顺带测(成本极低),但它们的窗口数会污染队列(mediaItemCount 变大),除非能包成单媒体项。

### 4.2 spike 判定(硬判据,先于一切实现)

统一用 **PCM 取证**(§6 L3)判定,三个候选各自出一份报告:

- **必须通过(判据 A / "真无缝"下限)**:
  1. 接缝处**无静音插入**(任何 ≤1 采样的静音段即视为不合格:间隙 = 0 采样);
  2. **无丢帧/重帧**:累计输出长度与理论值差 = 0 采样;
  3. **零漂移**:第 k 轮起点 = 首轮起点 + k×L,误差 0 采样(连续 20 轮);
  4. **零 flush / 零 underrun**:稳态期间 sink flush 计数 = 0,AudioTrack underrun 增量 = 0。
- **理想档(判据 B)**:每一轮 PCM 与首轮**逐位相同**(仅 M3 可达,M1/M2 不强制,但要求接缝处差分在曲内自然差分分布内)。
- **落选条件**:任一候选触发解码/播放异常、或无法在"seek 进区间 / 切歌 / 恢复"下保持判据 A。

### 4.3 推荐与 gate

- 首选 **M1**;若 M1 在 spike 中卡在契约细节(如跨轮 TrackGroup/时间戳被拒),立刻切 **M2**(补丁更小、行为更原生)。
- **M3 先做 30 分钟的"渲染器 EOS 行为观察"实验**(包装 sink 打日志,看 EOS 后的调用序列)。若 renderer 不再喂 buffer → M3 直接淘汰,不投入。
- **fork 判定**:若 M1 与 M2 均失败,才启用 vendor 补丁的"重形态"(改 `MediaCodecAudioRenderer`/`DefaultAudioSink`);全仓 fork androidx/media 不作为方案(构建/发布/跟随成本不成比例)。
- 预计 spike:2~3 天出结论(结论 = 选定机制 + 取证报告 + 该机制下的 30 秒原型)。

---

## 5. 关键设计细节

### 5.1 时序(无缝稳态,一次都不重建)

```
用户播放已确认歌 + 单曲循环
  → LoopRuntime.refresh() 算出 LoopWindow(songId,A,B) → 写进引擎窗口(单次赋值)
  → ExoPlayer 正常 prepare/play(媒体项 = 整曲,不变)
  → 解码输出 PCM 到 A..B,引擎折返(B→A)…(无边界事件)
  → UI 标记/进度条读 LoopRuntime + 折返后位置
用户暂停/继续/切歌/seek/改 repeat
  → 只触发 refresh() 与位置/seek 映射,不做媒体结构变更
```

### 5.2 位置与 seek 映射(纯函数,单测穷举)

设窗口 `[a,b)`,L = b−a,raw = 引擎原始位置,pos = 展示位置:

- `pos = if (raw < a) raw else a + ((raw - a) mod L)`(**前奏只播一次**,与原设计一致)
- `seekTo(target)`:
  - target < a → 原始 seek 到 target(前奏),到 b 后自动进入折返;
  - a ≤ target < b → 原始 seek 到 `k*L + (target - a)`,k = 当前轮次(保持音频连续,不产生回跳感);
  - target ≥ b → 折返到 `a + ((target - a) mod L)`(与旧行为一致)。
- 时长恒为整曲时长(进度条语义不变);A/B 标记来自 `LoopRuntime.window`。

### 5.3 边界情形(逐一列出,逐条测试)

| 情形 | 处理 |
|---|---|
| 从 0 播到 B(前奏) | 前奏正常播,到 B 首次折返;环缓冲在首轮自然填满 |
| seek 进区间中部 | 环缓冲可能缺 [A,x) 段 → 后台补解码该段并拼接;补解码完成前若已到 B,先"妥协折返"(标记一次 degraded 事件,取证脚本允许 1 次) |
| loopEnd 恰好等于曲尾 | 视为有效窗口,环回照常 |
| A/B 被用户改了 | 失效环缓冲 → 重建(一次赋值),位置按 §5.2 重映射 |
| 用户 seek 到前奏之外(≤A) | 退出折返,正常播到 B 再进折返(不重建媒体项) |
| 切歌 | refresh() 重算;新歌无窗口 → 窗口置空,普通播放(无"恢复 repeat"动作) |
| 取消最终结果/删除 | refresh() → 窗口置空;若正在折返,当前轮播完到 B 后回到自然播放(不硬切) |
| 通知/锁屏/耳机键 | 原生路径,零特判 |
| 杀进程重启 | 位置按 pos 持久化;restore 完成后 refresh() 一次 |
| 睡眠定时 / 音频焦点丢失 | 原生路径;焦点恢复后按当前状态重建窗口(幂等赋值) |
| 解码失败 / 超内存上限 | 窗口置空 + 明确提示,普通播放 |

### 5.4 预览(分析页)

预览用一个**独立 ExoPlayer 实例**,同样挂环回引擎(同一套机制),因此"预览听到的接缝 = 正式播放听到的接缝",不会出现"预览好、播放差"的漂移。进入预览暂停主播放,退出恢复。

---

## 6. 测试方案

### 6.1 分层

| 层 | 手段 | 覆盖 | 门禁 |
|---|---|---|---|
| **L0 纯逻辑单测**(JVM,无 Android) | JUnit,与现有 16 个测试文件同风格 | 折返算术/位置映射/seek 映射/资格判定/帧↔µs/环缓冲索引/行状态迁移/恢复位置钳制 | 每次提交;行覆盖率 ≥95% |
| **L1 组件单测**(JVM + fake) | 环回引擎对 **fake AudioSink / fake SampleStream** 做字节级验证 | 喂 buffer→折返点拆分→EOS 吞/续→flush 清理→位置折返;确定性、可 CI | 每次提交 |
| **L2 真机 E2E 协议**(保留/重写 LoopDebugServer) | `adb forward` + HTTP JSON(仅 debug 构建,且**迁到 src/debug**) | 分析/确认/降级/删除、播放、切歌、seek、恢复、通知状态、队列/随机/repeat 持久化 | 每次打包 |
| **L3 PCM 取证**(核心新增) | 引擎内置 tap → WAV + 事件日志 → PC 侧离线判定 | §4.2 判据 A/B;格式矩阵(MP3/FLAC/AAC/WAV);A 在曲中/曲尾;L=20s/60s;连续 20 轮;seek 入区;切歌;锁屏 | **真无缝的唯一验收标准** |
| **L4 既有功能回归** | L0 补齐 + L2 协议覆盖 | 队列/歌单/收藏/随机/repeat 持久化、扫描与过滤、排序、备份恢复、CUE 守卫、睡眠定时、通知开关 | 每次打包 |
| **L5 竞态风暴** | 脚本随机 200 次操作(play/pause/seek/切歌/改 repeat/确认/取消/前后台,100~800ms 间隔),全程 L3 tap 同时运行 | 旧版所有竞态场景的等价覆盖;断言:无 crash、无常量不一致(UI 标记 ⇔ 实际折返)、判据 A 不破 | 发布前必跑 |

### 6.2 "真无缝"判据(可自动化、可复现)

1. **零间隙**:输出 PCM 中不存在 ≤1 采样的静音/零值插入;接缝相邻样本差 ∈ 曲内自然差分分布。
2. **零漂移**:连续 20 轮,第 k 轮起点 = 首轮 + k×L,**0 采样**误差。
3. **零重放/丢帧**:累计输出 = N×L + 前奏。
4. **零 underrun / 零 flush**:稳态期间计数为 0。
5. (理想档)每轮逐位相同。

> 这五条同时覆盖"听起来无缝"和"结构上没有偷偷 seek/flush"。

### 6.3 工具与产物

- `tools/loop-tap-analyze.ts`(bun):读 WAV + 事件日志,输出 §6.2 五项指标 + PASS/FAIL + 接缝波形图。
- `tools/loop-e2e.ps1`(重写):L2/L3/L5 驱动,产物落 `e2e-results.json` + `e2e-shots/`。
- 所有真机测试都要求设备在线(`adb devices` 当前为空,**需要你插上手机**)。
- 证据留存:每次发布前归档 `e2e-results.json` + 取证 WAV/报告。

### 6.4 既有功能"补测试"清单(用户明确要求)

| 模块 | 现状 | 补充 |
|---|---|---|
| 队列/播放上下文 | 有 `MusicViewModelQueueTest` | 补:切歌后队列不变、恢复后索引/位置、随机开启后的前后首尾回绕 |
| repeat/shuffle 持久化 | 无 | 新增:偏好读写、与无缝窗口的独立性(窗口变化不得写 repeat) |
| 恢复流程 | 无 | 新增:restore 与用户命令竞态(restoreInvalidated 场景)、位置钳制 |
| 智能歌单 SQL | 有 | 补:内置「无缝循环」智能歌单 = confirmed=1 集合 |
| 备份/恢复 | 无 | 新增:song_loop 是否纳入备份(决策项)及其 JSON 往返 |
| 扫描过滤/排序 | 有 | 保持不变,回归跑通 |

---

## 7. 里程碑、分支与并行分工

| 阶段 | 分支 | 产出 | 预估 |
|---|---|---|---|
| P0 探针 | `spike/loop-audio-probe` | 机制选型结论 + PCM 取证报告 + 30s 原型 | 2~3 天 |
| P1 环回内核 + 会话层 | `feat/loop-v3-core` | 引擎、LoopWindow/映射/资格、LoopRuntime、位置层 + L0/L1 测试 | 3~4 天 |
| P2 数据层 + 分析链 | `feat/loop-v3-analysis` | 引擎/解码复用审查、Room v9、分析状态机、进度 | 2~3 天 |
| P3 UI | `feat/loop-v3-ui` | 菜单、分析页(波形/AB/候选/四模式)、播放页 A/B 标记、智能歌单 | 3~4 天 |
| P4 测试与 E2E | `feat/loop-v3-test` | LoopDebugServer 迁移+重写、L2~L5 脚本、取证分析器 | 3~4 天 |
| P5 收口 | 合并到 `feat/loop-v3` | 全量构建、真机验收、文档定稿 | 1~2 天 |

执行方式(按 AGENTS.md 协作纪律):**我是协调者**,负责契约/文件域/红线、裁决、收口(type-check、git、格式)、验收;编码、调试、测试用例迭代由子 agent 并行完成,期间我用完成通知驱动,不空转轮询。
文件域划分(避免并发冲突):core=`loop/`+`service/MusicService.kt`+`di/`;analysis=`data/`+`cpp/`+`loop/analysis`;ui=`ui/loop/`+`ui/library/SongActionDialogs.kt`+`MainActivity.kt`+播放页标记;test=`app/src/debug/`+`tools/`。

---

## 8. 风险与回滚

| 风险 | 影响 | 对策 |
|---|---|---|
| 环回引擎在部分格式/容器上出问题 | 个别歌不无缝 | 格式矩阵测试(MP3/FLAC/AAC/WAV/APE)+ 按格式降级(明确提示,不静默) |
| 长循环区间内存 | OOM | 上限 300s/64MB + 明确降级文案 |
| 位置映射与原生 seek 的交互 | 进度条跳变 | 纯函数穷举单测 + L3 位置序列断言 |
| 子 agent 各自改契约 | 集成失败 | 契约文档先行(本文档 + 接口签名),文件域独占 |
| 现有播放功能回归 | 用户日常使用受损 | L4 回归门禁 + 分阶段合并(每阶段全量 E2E) |
| spike 全部候选失败 | 需要 M4(门面重写) | 提前立 gate,不拖到最后;M4 单独评估(需你再确认) |

回滚:任何阶段不达标 → 该阶段分支不合并;`feat/loop-v3` 保持在基线附近可随时放弃;`main` 始终是无缝功能的稳定版;旧实现完整保留在 `backup/glm-loop-wip-20261115`。

---

## 9. 需要你裁定的 6 件事

1. **机制路线**:同意"先走 Media3 公开扩展点(M1/M2),spike 用 PCM 判据定选型,fork 只作为兜底的最小 vendor 补丁"吗?(不直接全仓 fork 的理由:构建/跟随成本高,且 fork 并不能替代位置映射层。)
2. **无缝触发语义**:沿用"已确认循环点 + 单曲循环 = 无缝"(状态驱动),并**彻底取消"引擎改 repeat / 切歌恢复 repeat"**?这样切歌后新歌若已确认也会继续无缝。
3. **复用清单**:同意复用 ①C++ 分析引擎 ②PcmDecoder ③Room `song_loop`(沿用 v8/v9 schema,保证旧开发包数据可平滑升级)④LoopDebugServer 骨架(迁到 src/debug)⑤分析页 UI 结构与波形渲染思路;其余(播放集成层)全部重写?
4. **真无缝判据**:接受 §6.2 的五条作为验收标准(其中"每轮逐位相同"作为理想档而非硬性)?
5. **备份范围**:是否确认备份分支已足够(排除 build 产物/测试 wav/截图);测试 wav 与截图仍留在工作目录未入库。
6. **设备**:E2E/取证需要真机在线,P0 开始前请插上(`adb devices` 现在为空)。

---

## 10. 附:本次已执行的只读事实核验

- `main`@`c55ffe4`:`:app:compileDebugKotlin` BUILD SUCCESSFUL(39s)。
- `backup/glm-loop-wip-20261115`@`3bbb081`:187 文件快照(15 改 + 63 新增 + docs/tools)。
- Media3 1.8.0 本地 AAR 内已确认存在:`DefaultRenderersFactory.buildAudioSink`、`ForwardingAudioSink`(含 `handleBuffer/handleDiscontinuity/playToEndOfStream/isEnded/getCurrentPositionUs`)、`LoopingMediaSource`(含 `loopCount`)、`ConcatenatingMediaSource2`、`SimpleBasePlayer`。
- 现有单测 16 个文件,无 instrumented 测试;现有工具 `tools/hesimusic-adb.ps1`、debug 源集 `AdbDatabaseReceiver`。
- 数据库:基线 v7;备份的 v8(建 `song_loop` 表)+ v9(加 `confirmed`)迁移可直接沿用。
