# 场景覆盖矩阵审计（issue 00024 · 第四批）

> 目的：把"想到一个场景补一个"换成"从代码枚举用户可见状态 × 用户动作，先找空格子，再按风险补"。
> 本文只做审计：**不含产品修改**，设备未使用，未执行任何场景。
> 覆盖基线：仓库内已注册 **50 条**场景（1–19 / 20–26 / 41–52 / 101–112；其中 19(--long)、50(重启) 默认不跑，常规有效 48 条）。
> 另有 A 批（00028 区间外位置，`tools/scenarios/scenarios-prelude.ts`，id 60–70）为**在途未注册**改动，
> 本文把它记为"已排期"，不重复计入覆盖，也不重复造同类场景（矩阵里标 `A批`）。

---

## 0. 取证口径（这份矩阵凭什么说"覆盖/没覆盖"）

| 维度 | 枚举依据（代码，不是记忆） |
|---|---|
| 调试协议（动作可达性） | `app/src/debug/java/com/zjr/hesimusic/debug/LoopDebugServer.kt:370-403`（路由表）、`:1061-1141`（/player/*）、`:1209-1269`（/loop/enter、/loop/exit）、`:1348-1363`（/navigate）、`:1651-1755`（/debug/preview-seek）、`:1573-1593`（/debug/invariant） |
| 循环"真值" | `service/LoopClaim.kt:26-102`（claimActive/fallbackReason）、`:148-180`（claim 解析）；`/state.loop` 字段 `LoopDebugServer.kt:191-250`（engine/loopPath/claimed/claimActive/sourceActive/claimReason/window/seamCount/degradeCount/intent） |
| 回退原因（claim=false） | `service/LoopSourceFallback.kt:38-44`（window-invalid / file-missing / format-unsupported / ring-too-large / build-failed）、`:73-121`（预检） |
| 结果页/预览页状态 | `ui/loop/state/LoopPageState.kt:20-25`（4 模式）、`:42-61`（预填 6 态）、`:71-85`（播放 4 态 / 拖拽 2 态）、`:98-144`（Loading/Analyzing/Ready/Failed，Ready 含 auditionOutside/sourceMode）、`:147-230`（事件与效果） |
| 预览播放器现场 | `ui/loop/PreviewPlayer.kt:1375-1433`（mode/engine/sourceActive/isPlaying/buffering/displayMs/liveDisplayMs/windowA/B/sourceWindowA/B/sourceKind/outsideLoop/outputFrames）、`PreviewStartPlan.kt:38-70`（各模式起点与路由） |
| 主播放页状态机 | `ui/player/state/PlayerPageState.kt:22-182`（Idle/Playing/Paused/Seeking/Buffering/Ended/Error + SeamlessIntent + 事件） |
| 进入/离开规则 | `MainActivity.kt:363-390`（进结果页、确认保存 → arm + playKeepPosition → pop）、`:444-478`（长按菜单「无缝循环」= arm + playKeepPosition）；`LoopScreen.kt:212-257`（进页暂停主播放；真正离开才 release+resume）、`PreviewHostLifecycle.kt:66-110`（配置变更 KEEP_ALIVE） |
| 资格/引擎开关 | `loop/LoopEligibility.kt:27-44`（intent/confirmed/repeat=ONE/窗口合法）；`loop/audio/source/LoopSourceMainPlayer.kt:17-95` + `LoopDebugServer.kt:1527-1551`（/debug/source-mode） |
| 宿主事件 | `AndroidManifest.xml:24-43`（MainActivity **无** configChanges → 旋转/分屏必重建；MusicService 前台服务） |

**判据口径**保持与现有套件一致：
- 出声 = 主播放器 `invariantProbe.writtenFrames` / 预览 `outputFrames` 的实际增长（不是"位置在动"）；
- 位置 = `/state.playback.positionMs`（已按 claim 折返）或 `/debug/preview.liveDisplayMs`；
- 内容 = `content-probe`（tap PCM vs `/debug/decode-range` 原始文件 NCC ≥ 0.90，能抓"进度条对、声音错"）；
- 不崩 = pid 不变 + crash/ANR/OOM 扫描；不违反不变量 = `/state.invariants` 增量。

---

## 1. 状态维度（用户可见，代码可读）

编号仅用于本表；"可观测字段"是断言时实际能读到的真值。

| # | 状态 | 判定口径（可观测） | 用户可见含义 |
|---|---|---|---|
| S1 | 未进入循环·播放中 | `loop.claimActive=false`、`loop.window=null`、`engine=exo`、`playback.playing=true` | 普通整曲播放，进度条无 A/B 标记 |
| S2 | 未进入循环·已暂停 | S1 + `playing=false && playWhenReady=false` | 暂停在媒体时间某处 |
| S3 | 未进入循环·停止/ENDED | `playbackState` ∈ idle/ended、`playing=false` | 没在播 / 播完停在结尾 |
| S4 | 循环生效·区间内 | `claimActive=true`、`engine=loop`、`loopPath=source`、`A ≤ position < B` | 声音在 A/B 间环回，进度条有 A/B 标记 |
| S5 | 循环生效·接缝瞬间（跨 B 回 A） | `seamCount` 增长；位置跨 B 后回到 A | 听不到断点；指针回到 A |
| S6 | 循环生效·前奏段 `[0,A)` | **当前 build 无此状态**（`/state.loop` 没有 phase 字段；`LoopSourceProjection` 时间轴 T=0 恒为 A）→ 00028 规格未落地 | 进循环后应从头播到 B（规格），现状会直接落在 A |
| S7 | 循环生效·尾段 `(B,end]` | 同上，未落地 | seek 到 B 之后应播尾奏（规格），现状无处可去 |
| S8 | 循环生效·已暂停 | S4 + `playing=false` | 暂停仍保留 A/B 与窗口 |
| S9 | 回退（claim=false） | `claimed=true` 但 `claimActive=false`、`claimReason` ∈ 五原因、`engine=exo`、`window` 可能仍在 | 用户以为在循环，实际整曲直通 —— 00027 的温床 |
| S10 | 预览页·加载/分析中 | 页面 Loading/Analyzing；`/state.analysis.phase` ∈ decoding/analyzing；`/debug/preview` 可能 409 | 结果页转圈/进度，主播放被暂停 |
| S11 | 预览页·Ready·播放中 | `/debug/preview` 有 snapshot，`mode` ∈ FULL/LOOP_ONLY/SEAM/FREE，`isPlaying=true`，`sourceKind` ∈ band/native/m3 | 波形 + 播放头 + 出声 |
| S12 | 预览页·Ready·暂停/停止 | S11 + `isPlaying=false`；页面 PlaybackState=Paused/Stopped | 暂停/停止试听 |
| S13 | 预览页·区外自由试听 | `outsideLoop=true`（`sourceKind=native`，位置=文件时间） | 提示"循环已暂停·按文件时间试听" |
| S14 | 预览页·失败 | 页面 Failed(message) | "分析失败 + 重试/返回" |
| S15 | 主播放页（route=player） | `/state.screen=player`；页面状态机 Idle/Playing/Paused/Seeking/Buffering/Ended/Error；滑条上有 A/B 标记层 | 正在播放页 |
| S16 | 页面/进程级 | 后台（HOME）/ 旋转（Activity 重建）/ 分屏 / 锁屏 / Doze / 重启 / 低内存 / 充电 / 音量 | 系统事件 |

---

## 2. 动作维度（用户能做、套件能驱动）

| # | 动作 | 驱动方式 | 备注 |
|---|---|---|---|
| A1 | 播放 / 暂停 / 继续 | `/player/play-pause`、`/player/play`；结果页播放按钮（contentDescription 暂停/播放）、停止按钮 | 预览页按钮**无文本**，uiTap 只按 text 匹配 → 本批新增按 content-desc 点按 |
| A2 | seek 区间内 | `/player/seek`（循环生效时映射到 SOURCE 时间轴）、`/debug/preview-seek`、预览「◀ A」「◀ B−5s」 | |
| A3 | seek 到 A 之前 | 同上（预览：进入 outsideLoop；主播放：00028 规格要求能播前奏，现状未落地） | |
| A4 | seek 到 B 之后 | 同上 | |
| A5 | 改 A | 结果页「当前位置→A」/ 拖手柄 / `/confirm` 显式 samples | |
| A6 | 改 B | 结果页「当前位置→B」/ 拖手柄 / `/confirm` | |
| A7 | 切歌 | `/player/next`、`/player/previous`、播放页「上一首/下一首」、通知栏/媒体键 | |
| A8 | 进循环 | `/loop/enter`（play/restart 两轴）、长按菜单「无缝循环」、结果页「确认并保存」 | |
| A9 | 出循环 | `/loop/exit`、切歌自动退场、`/confirm` 到未确认状态 | |
| A10 | 切引擎 | `/debug/source-mode {on}`（SOURCE ↔ M3′） | 无用户入口；持久化 prefs，现有播放器不重建 |
| A11 | 切试听模式 | 结果页四个模式按钮（FULL/LOOP_ONLY/SEAM/FREE） | 无协议入口 |
| A12 | 重新分析 | 结果页「重新分析」、`/analyze`（+`/cancelAnalysis`） | |
| A13 | 旋转 | `wm user-rotation lock`（Activity 重建） | |
| A14 | 后台 / 前台 | HOME / `am start` | |
| A15 | 保存（确认并保存） | 结果页「确认并保存」、`/confirm` | UI 路径 = arm + playKeepPosition + pop |
| A16 | 取消/删除结果 | 结果页「取消最终结果」（demote）/「删除分析结果」（delete）/ `/demote`、`/delete` | 会改 `song_loop.confirmed` → 直接影响 claim |
| A17 | 返回 / 离开页面 | 结果页「返回」/ 返回键 / 播放页下滑 | 真正离开才 release+resume |
| A18 | 进入页面 / 导航 | `/navigate`、菜单「无缝分析」 | 进结果页会暂停主播放 |
| A19 | 候选切换 | 结果页「候选(N)」→ BottomSheet | |
| A20 | 通知栏 / 媒体键 | `cmd media_session dispatch` / keyevent | 环境能力，ROM 受限 |
| A21 | 拖拽（波形手柄 / 滑条） | `input swipe` / 滑条 | 套件无拖拽原语；uiautomator 只按文本点按 |

---

## 3. 覆盖矩阵（15 状态 × 21 动作；S16 页面/进程级另表见 §3.0）

图例：
- `#N` = 已覆盖（场景 id），`△#N` = 部分覆盖（只覆盖了同格的一部分），`✗` = 未覆盖，`—(x)` = 不适用，理由见 §5。
- 本批计划补的格子写成 `✗→#8x`（见 §6 与 `tools/scenarios/scenarios-gaps.ts`）。
- 00028 未落地：S6/S7 整行是"规格已定、实现未做"，另有 A 批（区间外位置）场景在建，见 §7。

| 状态 \ 动作 | A1 播/暂 | A2 seek内 | A3 seek前 | A4 seek后 | A5 改A | A6 改B | A7 切歌 | A8 进循环 | A9 出循环 | A10 切引擎 | A11 切模式 | A12 重分析 | A13 旋转 | A14 后台 | A15 保存 | A16 取消/删 | A17 返回 | A18 进页 | A19 候选 | A20 媒体键 | A21 拖拽 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| S1 未循环·播放中 | #1 #101 | —(a) | —(a) | —(a) | —(b) | —(b) | △#12 #16 | #3 #14 #102 | —(c) | ✗ | —(d) | #8 | ✗ | #13 | —(b) | —(b) | —(e) | △#16 | —(b) | ✗ | ✗(f) |
| S2 未循环·已暂停 | #1 | ✗ | —(a) | —(a) | —(b) | —(b) | ✗→#90 | #4 | —(c) | ✗ | —(d) | ✗ | ✗ | ✗ | —(b) | —(b) | —(e) | ✗ | —(b) | △#41 | ✗(f) |
| S3 未循环·停止/ENDED | △#16 | ✗ | —(a) | —(a) | —(b) | —(b) | ✗ | ✗ | —(c) | ✗ | —(d) | ✗ | ✗ | ✗ | —(b) | —(b) | —(e) | — | —(b) | ✗ | ✗(f) |
| S4 循环·区间内 | #17 | ✗→#80 | ✗(A批) | ✗(A批) | ✗→#81 | ✗→#81 | #12 #106 #41 #109 | #3 #102 #109 | #5 #102 △A批#67 | ✗→#93 | —(d) | #107 | ✗→#92 | #47 | ✗→#88 | ✗→#87 | ✗ | ✗→#91 | —(b) | #41 | ✗(f) |
| S5 循环·接缝瞬间 | △#17 | △#10 #111 | — | △#21 | △#6 | ✗→#81 | △#12 | △#3 | △#5 | ✗ | △#7 | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | △#41 | ✗(f) |
| S6 循环·前奏 [0,A) | ✗(00028) | ✗(00028) | ✗(00028) | — | ✗(00028) | ✗(00028) | ✗ | ✗(A批) | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | — | ✗ | ✗ |
| S7 循环·尾段 (B,end] | ✗(00028) | ✗(00028) | — | ✗(00028) | ✗(00028) | ✗(00028) | ✗ | ✗(A批) | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | — | ✗ | ✗ |
| S8 循环·已暂停 | #17 | ✗→#83 | ✗(00028) | ✗(00028) | #108 | #108 | ✗ | #4 | ✗→#82 | ✗ | —(d) | △#107 | ✗ | ✗ | ✗ | ✗ | ✗ | — | — | △#41 | ✗(f) |
| S9 回退 claim=false | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | △#26 #105 | ✗ | ✗ | —(d) | ✗ | ✗ | ✗ | —(b) | —(b) | ✗ | — | — | ✗ | ✗(f) |
| S10 预览页·分析中 | —(g) | ✗ | — | — | — | — | △#107 | — | — | ✗ | — | △#8 | ✗ | ✗ | —(b) | —(b) | ✗ | △#9 | — | — | — |
| S11 预览页·Ready 播放 | ✗→#84 | #10 #24 #111 | △#11 | △#11 #23 | #6 | ✗→#85 | ✗ | —(h) | —(h) | ✗ | #7 | ✗→#86 | #45 | ✗(需裁决) | ✗→#88 | ✗→#87（删=✗） | ✗→#91 | #88 前置 | ✗ | ✗ | △#103 #104 |
| S12 预览页·Ready 暂停/停止 | ✗→#84 | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | —(h) | —(h) | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | — | ✗ | ✗ | ✗ |
| S13 预览页·区外试听 | ✗ | #110 | #11 #18 | #11 #23 | △#103 | ✗→#85 | ✗ | —(h) | —(h) | ✗ | △#112 | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | — | — | ✗ | ✗ |
| S14 预览页·失败 | — | — | — | — | — | — | — | — | — | — | — | △（重试） | ✗ | ✗ | — | — | ✗ | — | — | — | — |
| S15 主播放页 | ✗→#89 | ✗(i) | —(a) | —(a) | —(b) | —(b) | ✗(i) | ✗ | ✗ | ✗ | —(d) | — | ✗ | △#13 | —(b) | —(b) | ✗ | △#89 | —(b) | △#41 | ✗(f) |

### 3.0 S16 页面/进程级（系统事件，单独成表）

系统事件不是"用户对播放器的动作"，塞进 21 列会失真，所以单列一张：

| 当前状态 | 系统事件 | 覆盖 | 说明 |
|---|---|---|---|
| S1 未循环·播放中 | 后台 / 回前台 | #13 | 位置连续、声音不断 |
| S4 循环·区间内 | 后台 60s / 回前台 | #47 | 循环窗口不变、pid 不变、后台仍有声 |
| S4 循环·区间内 | 旋转 | ✗→#92 | 本批补：Activity 重建后 claim/窗口/声音连续 |
| S4 循环·区间内 | 分屏 / 小窗 | #46 | 布局变化不打断 |
| S4 循环·区间内 | 锁屏 / 解锁 | #44 | 需 --allow-visible-state-changes |
| S4 循环·区间内 | Doze / 省电 | #49 | 同上 |
| S4 循环·区间内 | 低内存 trim | #48 | 环带不崩、位置不乱 |
| S4 循环·区间内 | 充电插拔 / 音量键 | #51 / #52 | 可见状态变化，默认 SKIP |
| S4 循环·区间内 | 系统重启后恢复 | #50 | HESI_ENV_REBOOT=1 单独跑 |
| S11 预览页 Ready | 旋转 | #45 | 预览位置连续、A/B 不变 |
| S11 预览页 Ready | 后台 / 回前台 | ✗ | **需产品裁决**：后台是否应停预览（无 ON_STOP 处理） |
| S10 预览页分析中 | 旋转 / 后台 / 退页 | ✗ | 分析是否继续、回来是否还在 Analyzing |
| S15 主播放页 | 旋转 | ✗ | 页面重建后播放页状态机是否复位 |
| 任意 | 任务划掉 / 强杀 | ✗ | 退出后是否残留 FGS/前台通知 |

### 3.1 已覆盖明细（按场景 id，避免误读"这条覆盖了整格"）

| 场景 | 覆盖的格子 |
|---|---|
| #1 | S1/S2 × A1（暂停静音、继续出声、位置不动） |
| #2 | S1 × A2（50% 点）；S1 × 位置不回弹 |
| #3 | S1 → S4 × A8（restart=false，从 A+3s 进） |
| #4 | S2 → S8 × A8（play=false）、S8 × A1 |
| #5 | S4 → S1 × A9（播放中退出，位置不倒退） |
| #6 | S11 × A5（「当前位置→A」，B 不变） |
| #7 | S11 × A11（四模式，播放中） |
| #8 | S1 × A12（分析开始时暂停、静音、位置不动；含一次取消重来） |
| #9 | S10 → S11 × A18/A11（分析完成点「接缝检查」） |
| #10 | S11 × A2（区内 5 次，延迟口径） |
| #11 | S11 → S13 × A3/A4（区外 seek，落点不折返） |
| #12 | S4 × A7（next，退出旧循环） |
| #13 | S1 × A14 |
| #14 | S1 × A8（未确认歌，拒绝进入） |
| #15 | S4/S5 × A5/A6（3s 极短窗口，回绕与出声） |
| #16 | S1/S4 循环跑、heap/OOM |
| #17 | S4/S8 × A1（循环内暂停→继续） |
| #18 | S1 对照 seek（自由试听成本） |
| #19 | S4 × 长窗口稳定性（--long） |
| #20–#25 | S5/S11 × A2/A3/A4 的内容真值（跨 B、边界矩阵、整圈、随机、ENDED 风险） |
| #26 | S9 × A8（ring-too-large 真回退）+ 内容真值 |
| #41 | S4 × A20（通知/媒体键 暂停/继续/上/下一首）；S8 × A20（该场景的暂停段） |
| #42–#52 | S16 环境格（耳机/焦点/锁屏/分屏/后台/trim/Doze/重启/充电/音量） |
| #101 | S1 × A1（连点） |
| #102 | S1↔S4 × A8/A9（连点进出循环） |
| #103 | S11 × A5/A21（非法 A>B 提交）；S13 × A5（区外点「当前位置→A」） |
| #104 | A15 × 非法零长度窗口（协议 400） |
| #105 | S4 × 边界窗口（0.5s / 接近整曲） |
| #106 | S4 × A7（循环中切歌，旧循环完全退出） |
| #107 | S4 × A12（循环中重新分析；分析期间暂停播放待裁决） |
| #108 | S8 × A5/A6（暂停中改 A/B 后恢复） |
| #109 | S4 × A8（重复进入同一循环 5 次） |
| #110 | S13 → S11 × A2（区外再拖回区间） |
| #111 | S11/S1 × A2（高频连发 seek 合并） |
| #112 | S11/S13 × A11（预览与主播放并发） |
| #45 | S16 × A13（仅预览页 Ready） |
| #47 | S4 × A14（后台 60s） |

---

## 4. 未覆盖清单（按风险排序）

风险 = 用户可感知程度 × 出错后果 × 复现难度（越靠前越该先补）。"本批"= `scenarios-gaps.ts`（id 80–93）。

| 顺位 | 格子 | 为什么高风险（用户视角） | 复现难度 | 处置 |
|---|---|---|---|---|
| 1 | **S4 × A2 主播放器循环中 seek 区间内** | 用户最常用的动作；#10/#11/#24 全是**预览页**，#2 是普通播放。00027 的"进度条对、声音错"正是这一类；主播放器的表↔源映射（`LoopSessionPlayer.seekTo`）没有一条内容判据 | 低（协议 + 内容探针） | 本批 #80 |
| 2 | **S4 × A5/A6 主循环中改窗口** | 结果页改 A/B 会走 `/confirm` → arm+重建队列+自动播（`LoopDebugServer.kt:768-796`），与"确认并保存"共用；00020（进循环卡顿/秒级静音）与 00021（从 A 走但声音从头）都在这条链上。现有 #6/#108 只覆盖**预览页**与**暂停态** | 低（`/confirm` 显式 samples） | 本批 #81 |
| 3 | **S9 × A2/A5/A6 回退态下的 seek/改窗口** | 00027 根因就是"回退后仍按窗口折返"。回退态（claim=false）下用户拖进度条/改 A/B，必须"不折返、不假循环、改窗口能重新判定"；现有只有 #26 的单点内容验证 | 中（需要造 ring-too-large 窗口） | 未覆盖（下一批，需长窗口歌） |
| 4 | **S11 × A15 结果页「确认并保存」用户路径** | 用户保存分析结果的主路径：arm → playKeepPosition → pop（`MainActivity.kt:378-388`）。#15/#103–#105 全都用协议 `/confirm`（行为是"重建队列、位置归 0"），**UI 路径一次没测过** | 低（文本按钮「确认并保存」） | 本批 #88 |
| 5 | **S4 × A16 循环中「取消最终结果」** | confirmed 1→0 会让 `LoopEligibility` 拒绝派生窗口；若 claim/进度条没跟着退，就是"显示在循环、实际整曲"的 00027 型假循环。没有任何场景碰过 demote/delete | 低（文本按钮 + `/song` 读回） | 本批 #87 |
| 6 | **S11 × A12 预览页 Ready 中「重新分析」** | Ready→Analyzing 没有看到释放预览的路径（`LoopPageState.kt:288-295`、`LoopViewModel` 无 release），可能出现"页面已转圈、旧预览还在响"；同时重新分析会改写 song_loop 行 → 影响主播放 claim。#107 只在主循环里做 | 低（文本按钮 + 等 phase） | 本批 #86 |
| 7 | **S8 × A9 暂停中退出循环** | 退出是 rebuildQueueItem 路径；暂停态退出必须**不自动播放**、静音、进度条不倒退。现有 #5 只测播放中退出 | 低 | 本批 #82 |
| 8 | **S8 × A2 循环中暂停后 seek** | 暂停后拖进度条是高频动作；暂停门（`LoopSourcePauseGate`）与 `seekTo` 在 paused 下是否落点、继续后落点是否被播放采用、听到的是不是落点内容，没有任何场景（#108 改的是 A/B，不是 seek） | 低 + 内容探针 | 本批 #83 |
| 9 | **S11/S12 × A1 预览页播放/暂停/停止** | `LoopSourcePauseGate` 是历史静音/双声 bug 的高发区；现有场景只"点模式起播"，从没暂停过预览 | 低（按钮只有 content-desc，本批补按 desc 点按） | 本批 #84 |
| 10 | **S11/S13 × A6 预览页改 B** | #6 只改 A 并断言 B 不变；「当前位置→B」从未被点过。改 B 后当前播放头正好落在新 B 上（是否回绕/是否被判区外）是典型边界 | 低 | 本批 #85 |
| 11 | **S4 × A10 切引擎** | SOURCE↔M3′ 是调试开关（无用户入口），但切换瞬间的"半切换"（claim 仍 true 却换了 sink/源）会让后续所有真机结论失真；`LoopDebugServer.kt:1527-1551` 明确"已有播放器不会自动换 sink" | 低（POST + 只读真值） | 本批 #93（不崩/不半切换） |
| 12 | **S4 × A13 旋转** | Activity 无 configChanges → 重建；循环由 Service 持有。旋转后 claim/窗口/声音是否连续，没有场景（#45 只测预览页） | 低（wm 锁旋转 + finally 恢复） | 本批 #92 |
| 13 | **S1/S8 × A7 暂停中切歌** | 暂停时切歌必须保持暂停（ExoPlayer 语义），且旧循环窗口不能套到新歌上；#12/#106 都是播放中切 | 低 | 本批 #90 |
| 14 | **S4 × A18 进入结果页的主播放副作用** | `LoopScreen.kt:212-217`：进页暂停主播放；真正离开才 resume。用户"点开无缝分析看看"就会静音主播放，这是产品级体验，且与 A/B 修改/预览双声强耦合，无任何断言 | 低 | 本批 #91 |
| 15 | **S6/S7 前奏段/尾段（00028）** | 规格已确认：进循环从 0 播到 B 再回 A；整曲都能 seek。现状 T=0 恒为 A → "进循环跳到 A、无法 seek 到 A 之前" | 低，但**依赖修复** | A 批（60–79）在建；矩阵先占位 |
| 16 | **S9 × file-missing / format-unsupported / build-failed** | 五种回退原因里只有 ring-too-large（#26）能稳定构造；另外三种需要磁盘缺文件/不支持格式/构建失败 | 高（要造数据/环境） | 未覆盖：标注"难复现"，不做假场景 |
| 17 | **S10 × A13/A14/A17（分析中旋转/后台/退页）** | 分析在 VM scope；重建/退页时分析是否继续、页面回来是否还在 Analyzing、会不会双份分析 | 中（分析窗口短） | 未覆盖（低优先，可用长歌拉长窗口） |
| 18 | **S14 × A17/A12（失败页 返回/重试）** | .ogg 分析必失败（已知），失败页的"重试/返回"没有场景 | 低但价值低 | 未覆盖（低） |
| 19 | **S11 × A19 候选切换** | 候选 BottomSheet 选中后窗口/播放头/预填的联动；#6 只测「当前位置→A」 | 低 | 未覆盖（低） |
| 20 | **S15 × A1/A7 主播放页按钮** | 播放页按钮只有 content-desc；滑条拖拽、A/B 标记层是否随窗口更新，套件完全没有页面级断言 | 中（按 desc 可做按钮；拖拽不可驱动） | 本批 #89（暂停/继续） |
| 21 | **S2 × A2/A7、S3 × 任意** | 暂停/停止/idle 下的 seek、next、进循环起点（会暴露"从 0 播"还是"从媒体位置播"） | 低 | 未覆盖（低） |
| 22 | **S11/S13 × A14 结果页后台** | 预览播放器由 VM 持有，后台无 ON_STOP 处理 → 可能后台继续出声且通知栏无控制 | 低，但**期望需产品裁决** | 未覆盖（标记待裁决，不写成 FAIL） |

> 已从缺口里移除的格子：**S4 × A9「退出循环后回媒体时间轴」**——A 批 `scenarios-prelude.ts` #67
> （exit-loop-media-timeline）已经覆盖位置口径 + 内容真值 + 退出后 seek 到 A 之前，本批不再重复。
> 同理 #68/#69（A 拖到当前之后 / B 拖到当前之前）覆盖的是"当前位置落到窗口外"，与本批 #81
> （改 B 后新 B 生效 + 在新 B 回绕）不是同一格。

---

## 5. 不适用 / 不可驱动（附原因）

| 标注 | 含义 | 依据 |
|---|---|---|
| (a) | 未武装循环时不存在 `[A,B)` 区间，"区间内/前/后"的语义不成立；普通 seek 已由 #2/#18 覆盖 | `LoopEligibility.kt:27-44`：无 intent 即无窗口 |
| (b) | 该动作入口只存在于结果页（改 A/B、保存、取消、删除、候选），主列表/播放页没有入口 | `LoopScreen.kt:620-720`（全部按钮都在结果页） |
| (c) | "出循环"以"已在循环"为前提 | `/loop/exit` 只清 intent（`LoopDebugServer.kt:1264-1269`） |
| (d) | 试听模式只存在于结果页预览播放器；主播放器没有模式概念 | `PreviewPlayer` 的 `PreviewMode` vs `/state.playback.engine` |
| (e) | "返回"只在子页面（播放页/结果页/分析页）成立 | NavHost 路由 `MainActivity.kt:209-390` |
| (f) | 波形/滑条**拖拽**在本套件不可驱动：`uiTap` 只按文本匹配（`run-scenarios.ts:665-712`），harness 无拖拽原语；等价意图由 #103/#104（非法窗口提交）与 #6/#85（按钮改 A/B）覆盖 | 工具能力 |
| (g) | 分析中没有预览播放器（`/debug/preview` 409），播放类动作不适用；#9 覆盖"分析完成→点接缝检查" | `LoopDebugServer.kt:1624-1632` |
| (h) | 结果页的"进/出循环"由「确认并保存」与「返回」承担，没有独立的进/出循环按钮 | `LoopScreen.kt:688-719` |
| (i) | 主播放页滑条/图标按钮只有 contentDescription（`PlayerScreen.kt:280-307` 等），`uiTap` 按文本匹配不到；#89 用 content-desc 点按只覆盖"播放/暂停" | 工具能力 |
| 后台预览 | 结果页后台继续出声是否是缺陷**需要产品裁决**（`LoopScreen.kt:232-257` 只处理 DESTROY，不处理 ON_STOP） | 待裁决 |

---

## 6. 本批补的缺口场景（`tools/scenarios/scenarios-gaps.ts`，id 80–93）

只新建该文件，导出 `GAP_SCENARIOS`；不改 `scenarios.ts` / `run-scenarios.ts` / `harness.ts` / 其它 `scenarios-*.ts`。
复用 `content-probe.ts`（内容真值）与 `harness` 的量测；预览页/播放页图标按钮只有 contentDescription，
文件内自带「按 content-desc 点按」与「滚动到可视区再点文本按钮」两个小工具（不改 harness）。

| id | key | 对应矩阵格 | 想抓什么 | 主判据（用户可感知） |
|---|---|---|---|---|
| 80 | `main-seek-inside-loop-content` | S4 × A2 | 主播放器在 [A,B) 内 seek 后"听到的 == 显示的" | 落点 ±900ms；出声；内容 NCC ≥0.90（3 个点：A+1s / 中段 / B−2.5s） |
| 81 | `loop-reconfirm-change-b` | S4 × A5/A6 | 循环播放中改窗口（新 B）是否切歌/退出循环/旧 B 仍生效 | 同歌、claim 仍生效、新 B ±1s、A 不变、回绕发生在新 B 且回到 A |
| 82 | `exit-loop-paused` | S8 × A9 | 暂停中退出循环会不会自己响、进度条倒不倒退 | 退出后仍暂停、Δframes=0、窗口清空、位置不倒退；继续后有声 |
| 83 | `paused-seek-inside-loop-content` | S8 × A2 | 暂停中拖进度条是否真的生效、继续后听到的是不是落点 | 落点 ±1s、仍暂停、Δframes=0、继续后位置不跳回、内容 NCC ≥0.90 |
| 84 | `preview-playback-controls` | S11/S12 × A1 | 预览暂停/继续/停止三态是否真静音/真恢复/真回起点 | 暂停 Δframes=0；继续出声；停止后位置回到模式起点、无输出 |
| 85 | `preview-change-b` | S11 × A6 | 「当前位置→B」是否生效、A 是否被带坏、改完还能不能播 | B ±1.5s、A 不变、位置在新窗口附近、出声 |
| 86 | `preview-reanalyze-ready` | S11 × A12 | 重新分析期间"页面转圈但旧预览还在响"；完成后页面能否恢复 | 分析中预览无声/不可用；phase=done；页面回到 Ready 且窗口合法；不崩 |
| 87 | `preview-demote-while-looping` | S4 × A16 | 取消最终结果后主循环是否真的退场（假循环最危险） | confirmed=false；claim 不再生效、窗口清空；返回后普通播放有声；过程中不崩 |
| 88 | `preview-confirm-save-ui` | S11 × A15 | 「确认并保存」用户路径：回列表 + 进入无缝 + 出声 | 预览探针释放、claimActive=true、窗口=该歌 A/B、playing、出声 |
| 89 | `main-player-page-pause` | S15 × A1 | 播放页按钮驱动时循环是否被暂停弄丢/位置乱 | 暂停静音、位置仍在窗口；继续出声；窗口不变 |
| 90 | `next-song-while-paused` | S2/S8 × A7 | 暂停中切歌会不会自动出声、旧窗口串到新歌 | 切歌后仍暂停、Δframes=0、旧窗口不套新歌；继续后有声（单曲队列走 debug 回退 `WRAP_TO_START` 时会主动 play，该分支记 PENDING 待裁决） |
| 91 | `loop-page-entry-pauses-main` | S4 × A18/A17 | 进结果页暂停主播放、离开恢复；未点模式时不应自动试听 | 进页后主播放 pause 且 Δframes=0；返回后恢复 playing + 出声 |
| 92 | `rotate-while-looping` | S4 × A13 | 旋转重建后 claim/窗口/声音是否连续（不是从 A 重来） | window/A/B 不变、位置连续、仍出声、pid 不变；finally 恢复旋转 |
| 93 | `engine-switch-while-looping` | S4 × A10 | 切引擎后是否出现"claim=true 但没声/位置乱"的半切换 | 状态自洽（要么完整保持、要么明确退出）、不崩；finally 切回 SOURCE 并验证 routeSource |

**判据纪律**（沿用现有套件）：
- 出声一律读真实计数（主 `writtenFrames` / 预览 `outputFrames`），不用"位置在动"代替；
- 位置一律读单一真值源（`/state.playback.positionMs` 已折返 / 预览 `liveDisplayMs`），采样周期 < 判据阈值；
- 内容真值只在主/预览 SOURCE 路线可用，探针不可用时记 PENDING 而不是 PASS；
- 会改数据的场景（改 A/B、demote、保存）必须在 `finally` 里恢复原窗口并写证据；
- 会改设备状态的场景（旋转、引擎开关）必须在 `finally` 恢复，并把恢复结果写成 check。

---

## 7. 后续与维护

1. **00028 落地后**：S6/S7 两行要从"规格未落地"改成正常格子；`/state.loop.phase`（prelude/loop/tail）会成为新的状态判据；同时 §3 里 A3/A4 的"主播放器 seek 到区间外"要按新语义重判（现在只能写 PENDING/FAIL 而不是"不适用"）。
2. **A 批（区间外位置）场景**（`scenarios-prelude.ts`，#60–#70，在途）：#60 进循环起点矩阵、#61 前奏→循环过渡、#62 在 B 回绕内容、#63 seek 到 A 之前、#64 seek 到 B 之后、#65 尾段到结尾、#66 尾段回循环、#67 退出后回媒体时间轴、#68 把 A 拖到当前之后、#69 把 B 拖到当前之前、#70 进循环不跳 A。与本批 80–93 不重叠：本批 #80 是**循环中**主播放器 seek 的内容真值（A 批不测区间内 seek）、#81 是**改 B 后新 B 是否生效并在新 B 回绕**、#83 是**暂停态** seek。
3. **注册**（交给 lead，本人不改那两个文件）：
   - `scenarios.ts` 里 `import { GAP_SCENARIOS } from "./scenarios-gaps";` 并 `...GAP_SCENARIOS`；
   - `run-scenarios.ts` 的 `UI_SCENARIOS` 加 `84,85,86,87,88,89,91`（依赖结果页 UI 点按）；
     92（旋转）是设备侧动作、93/90/81/82 走协议，不需要进 UI 组；80/83 复用 `content-probe.ts`（只读，不改）。
4. **本批未跑设备**：所有 80–93 目前只有静态实现，首轮真机结果出来后再按 §4 顺位决定下一批（候选：#3 回退态 seek/改窗口、#16 难复现回退、#17 分析中宿主事件）。
