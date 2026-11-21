# 循环“声称已生效”一致性审计（00027 同类问题）

> 审计目标：把**所有会向用户/测试声称“循环正在生效”的读取方**列全，并给出“单一事实源”改造方案。
> 审计范围：主播放页、底部播放条、MediaSession/通知/锁屏/车机/表盘、调试口、不变量、About、
> 分析预览页与结果页状态机、列表/菜单/播放模式、最近播放/恢复、测试脚本。
>
> 结论先行：当前工作区已有一版并行修复（`MusicViewModel.loopEngaged`、`/state.loop.sourceAvailable`、
> `LoopSourceFallback` 预算 10→6），它**只覆盖了主播放 UI 的一部分**；审计时仍有 12 条“窗口在、源没在，
> 但某处仍按循环口径说话”的路径。本文第 8 节按严重度列根因，第 9 节给出 `LoopClaim` 单一事实源，
> 第 10/11 节给出文件级改动与测试矩阵。

## 实施状态（2026-11-21）

已按本清单落地并分步提交：

| 步骤 | commit | 内容 |
|---|---|---|
| P0-1 | `fd965e2` | coordinator 只在工厂真的建成循环源时 `onInstalled`；`isInstalledFor` 查回退登记；build-failed 立即 restore |
| P0-2 | `bce5cfd` | MediaSession/通知时长：回退时也报整曲，不再透传 `TIME_UNSET` |
| P0-3 | `279d926` | WINDOW_INVALID 纳入统一回退登记；`reasonFor` |
| P1 | `e1becc4` | `LoopClaim` / `LoopClaimResolver` / `LoopClaimStore`；session/UI/debug/About 统一读 claim；`/state.loop.claimed/claimActive/sourceActive/claimReason`；`/state.playback.positionMs` 依赖 claim；`loop-claim-without-source` 不变量；claim store 变更即时触发 UI 重算（消灭 1s 脏帧） |
| P2 | `d7238ac` + `23ffd51` + `0447edb` | About 回退时显示普通播放（EXO/none），不再写 M3′；per-song reason；空闲时保留按配置显示 |
| P3 | `c1ac1ca` | `loop-e2e.ps1` / `engine-cost-*.ps1` 等待与断言加 `claimed/sourceActive`；新增真机验证清单 |

验证：`:app:testProdDebugUnitTest` 全量通过；真机清单见 [loop-claim-e2e-checklist.md](loop-claim-e2e-checklist.md)。

### 2026-11-21 追加：#26 / #108 的 projection-loop-source-plain 定性

结论：**假报警**，不是 claim/resolver 的真 bug。

- **#26 长窗口**：evidence `26-long-window-fallback-content.json` 的 `after.loop` 是
  `claimed=true / claimActive=true / sourceActive=true / claimReason=null`，
  `engine=loop / loopPath=source`，content-probe peak=1.00；源确实在环回。
- **#108 暂停改 A/B**：恢复后 `after.loop` 是 `claimed=false / claimActive=false / sourceActive=false`
  （已还原并退出），恢复播放期间 output 88200f/2019ms=99.1%，新 A/B 窗口内源确实在环回。
- **根因**：旧 `SourceInvariantEvaluator` 用
  `windowActive + itemHasLoopTag + factoryLastSelectLoop` 作判据；
  `factoryLastSelectFor(mediaId)` 会被同 mediaId 的队列预取/恢复覆盖，不能代表“当前源”。
  在 #26/#108 中 claim/source 都 true，仅 factory 最近 select 是 delegate → 误报。
- **修复**：`SourceInvariantEvaluator` 改为读 `LoopClaim` 的 `claimActive/sourceActive`：
  - `claimActive && !sourceActive` → `projection-loop-source-plain`（真 bug）；
  - `!claimActive && sourceActive` → `projection-plain-source-loop`；
  - 两者一致 → 不报。
  `itemHasLoopTag` / `factoryLastSelectLoop` 仅保留在 `/state.invariantProbe` 作诊断。
- **回归测试**：`SourceInvariantTest` 增加“claim/source 都 true、但 factory 诊断为 delegate/tag 不可见”
  的 #26/#108 形态，断言不触发。

---

## 0. 口径与术语

| 术语 | 含义 | 当前代码来源 |
|---|---|---|
| **意图窗口 intent window** | 用户显式 armed + `song_loop` 行算出的 A/B。只代表“想要循环” | `LoopRuntime.window`（`LoopEligibility.derive` 的唯一产物） |
| **源真值 source truth** | 播放管线**真的**在环回：SOURCE 路线 = 源已被 factory 建成并接管；M3′ 路线 = 窗口已写入音频层 | `LoopSourceRuntime.installedWindow` / `LoopAudioController.window` |
| **声称 claim** | 对用户/MediaSession/测试暴露的“循环生效”状态 | 目前散落在 `activeLoopWindow`、`loopEngaged`、`sourceUnavailable`、`loopPath`、`isReplaying` 等 |
| **未生效** | 窗口存在但 source truth = false；播放的是普通整曲（或仍是旧循环源的短瞬态） | 00027 根因 |

五种回退原因（必须走同一条“未生效”处理）：

`WINDOW_INVALID`（window-invalid）、`FILE_MISSING`（file-missing）、
`FORMAT_UNSUPPORTED`（format-unsupported）、`RING_TOO_LARGE`（ring-too-large）、
`BUILD_FAILED`（build-failed）。定义见 `service/LoopSourceFallback.kt:38-44`。

---

## 1. 总览：谁在“声称”

| 层 | 声称方式 | 真值来源 | 00027 风险 |
|---|---|---|---|
| 播放页/底部条 | 进度折返、A/B 标记、时间文本 | `MusicViewModel.activeLoopWindow` / `loopEngaged` | 中：已部分修复，但失效时机/流更新有缺口 |
| MediaSession/通知 | position/duration/buffered/seek | `LoopSessionPlayer` | 高：duration 在 SOURCE 回退时可能报 `TIME_UNSET` |
| 调试口 | `/state.playback.positionMs`、`/state.loop.*` | `LoopDebugServer.displayPositionMs` / loop bridge | 高：build-failed 时 `loopPath=source` 且 position 仍折返 |
| 不变量 | `windowActive`、投影/音源一致性 | `PlaybackInvariantMonitor` | 高：没有“claim 无 source”code，build-failed 不报 |
| About | 引擎/循环路径/回退原因 | `LoopEngineInfo` | 中：回退原因 → 路径被写成 m3，且原因是全局的 |
| 分析预览页 | “无缝环回中(接缝已生效)” | `PreviewPlayer.isReplaying` | 低：SOURCE 失败会回退 M3′，M3′ 仍真循环 |
| 主播放页状态机（debug） | `seamless` 模式 | `LoopIntent.armed` | 中：只认意图不认源，影子会误报 seamless |
| 测试脚本 | 等待 `engine=loop`、断言 position ∈ [A,B) | 调试口 | 高：会与调试口一起被骗过 |

---

## 2. 主播放器 UI（进度 / 时长 / A/B 标记）

| # | 文件:行 | 它读什么 | 源未生效时会发生什么 | 判定 |
|---|---|---|---|---|
| U1 | `ui/common/MusicViewModel.kt:122-126` | `combine(loopRuntime.window, _uiState)` → `effectiveLoopWindow(window, songId, loopEngaged(...))` | 窗口命中 + `loopEngaged=false` → 标记层收到 null，A/B 不画。**但**流只在 window/uiState 变化时重算；`installedWindow`/回退登记变化不会主动触发，最坏约 1s 脏帧 | 部分修复，需 claim 版本号 |
| U2 | `ui/common/MusicViewModel.kt:138-143` | `MainPlayerRouting.useSourceForMainPlayer`、`LoopSourceFallback.isUnavailable`、`LoopSourceRuntime.isInstalledFor` | M3′ 路线直接返回 true，**不校验 `audio.window` 是否已写入**；窗口刚算出、音频层还没 apply 的瞬间会短暂声称循环 | 需补 M3′ source truth |
| U3 | `ui/common/MusicViewModel.kt:346-354` | `loopRuntime.window` + `loopEngaged` → `foldDisplayPositionMs(..., sourceLoopActive)` | 五种回退原因下 `loopEngaged=false` → 位置原样透传（正确）。WINDOW_INVALID 在 M3′ 下仍可能 `loopEngaged=true`，靠 `LoopPosition` 内部合法性兜底 | 需统一到 claim |
| U4 | `ui/main/BottomPlayerBar.kt:73,93,101` | `activeLoopWindow` 画标记；`currentPosition/duration` 画细进度 | 回退时标记消失、进度 = 媒体时间（修复后正确）；但 1s 脏帧同 U1 | 依赖 U1 |
| U5 | `ui/player/PlayerScreen.kt:51,154,236` | `activeLoopWindow` 画 A/B；`safePosition` 来自 uiState | 回退时无标记、滑块 = 媒体时间 | 依赖 U1 |
| U6 | `ui/player/PlayerScreen.kt:197,572-577` | `SeekOverlayPolicy.expectedDisplayMs(target, activeLoopWindow)` | 传 null → 期望落点 = 真实目标（正确）。但函数签名只收 `LoopWindow?`，任何调用方误传**原始** loopWindow 就会重新折返 | API 陷阱，需收口到 claim |
| U7 | `ui/player/PlayerScreen.kt:248,252` | 左 `sliderPositionMs`、右 `uiState.duration` | 回退时左 = 媒体时间、右 = 整曲（修复后正确） | 依赖 U3/U4 |
| U8 | `ui/common/LoopMarkerOverlay.kt:24,49-54,79-84` | 调用方传入的 `window: State<LoopWindow?>` | 传 null 时一个像素都不画（正确）；它**无法自己判断**源是否生效 | 需保证调用方只传 claim.effectiveWindow |
| U9 | `ui/common/LoopPlaybackUi.kt:35-51` | 原始 `window` + `sourceLoopActive: Boolean = true` | **默认 true**：任何新调用方漏传就恢复“按意图折返”的 00027 行为 | 高：必须去掉默认值/改 claim |
| U10 | `ui/common/LoopPlaybackUi.kt:64-73` | `loopEngaged` 布尔 + 窗口 + 当前歌 | 布尔由各调用方各自折算（ViewModel 一份、debug 一份、session 一份），三份口径会漂移 | 高：单一事实源要收口 |
| U11 | `ui/common/LoopPlaybackUi.kt:83-90` | `window` + **整曲时长** | 只做比例换算；窗口非法/时长未知返回 null（正确） | 无需改 |
| U12 | `ui/player/state/PlayerPageState.kt:44-50,147-151,723`、`PlayerPageShadow.kt:28-33,65-84,314` | `LoopIntent.armed` 推导 `seamless` 模式 | 源回退后 intent 仍在 → 状态机仍声称 “Playing(seamless)”（debug 影子/状态机），与真实播放形态不符 | 中：debug-only，但属“声称” |
| U13 | `ui/player/PlayerScreen.kt:264-286`、`ui/main/BottomPlayerBar.kt:166-176`、`ui/main/MainScreen.kt:323-324`、`ui/library/SongListScreen.kt:185-186` | `repeatMode == ONE` | 回退后 repeat 仍是 ONE（协调器进入时写的），按钮显示“单曲循环”。整曲循环是真的，但**无缝**是假的；界面没有“未生效”提示 | 中：文案/中性态 |

> 注：U4/U5 的进度条本身读的是 `uiState.currentPosition`，而它由 U3 折返；只要 U3 正确，
> 进度条不会单独错。真正需要防的是“第二个折返点”——U6/U9/U10。

---

## 3. MediaSession / 通知 / 锁屏 / 车机 / 表盘

| # | 文件:行 | 它读什么 | 源未生效时会发生什么 | 判定 |
|---|---|---|---|---|
| M1 | `service/MusicService.kt:156-162` | `LoopSessionPlayer` 包住 forwardingPlayer，作为 `MediaSession` 的 player | 所有外部消费者（通知、锁屏、车机、表盘、蓝牙）都读这一层 | 关键路径 |
| M2 | `service/LoopSessionPlayer.kt:61-71` | `snapshot().sourceUnavailable` → 直接 `super.getCurrentPosition()` | 预检回退（普通 item）正确返回媒体时间。**build-failed 的短瞬态**：若当前 item 还是旧循环源，`super` 返回数据源时间轴 T（0..L），不是媒体时间 A+mod(T,L) | 高：瞬态口径错 |
| M3 | `service/LoopSessionPlayer.kt:73-100` | 同上 + `LoopSessionProjection.bufferedUs` | 回退时透传；同上瞬态可能给 T 域缓冲位 | 高：同上 |
| M4 | `service/LoopSessionPlayer.kt:102-114` | `sourceUnavailable` → `super.getDuration()` | SOURCE 时间轴的 duration 是 `C.TIME_UNSET`（`LoopingPcmExtractor.kt:70`），回退后通知/锁屏可能显示 **-1/未知**，而不是整曲时长 | **高：直接违反“MediaSession 报整曲时长”** |
| M5 | `service/LoopSessionPlayer.kt:134-157` | `sourceUnavailable` → `super.seekTo(mediaItemIndex, positionMs)` | 预检回退（普通 item）正确。build-failed 瞬态下，展示位置被当成 T 下发，落点偏 A | 高：瞬态 |
| M6 | `service/LoopSessionPlayer.kt:169-195` | `runtime.window` 优先，重放期回退 `audio.window`；再查 `LoopSourceFallback.isUnavailable` | C2 短窗口内 UI 的 `activeLoopWindow` 仍为 null（U1 只看 runtime），但 session 已用 audio 窗口投影——两边不同步，标记会闪 | 中：C2 口径 |
| M7 | `service/MusicService.kt:419-426` | `sessionPlayer.currentPosition` → 持久化“最近播放位置” | 循环生效时保存的是**展示位置**（A..B）；回退时保存媒体时间。恢复时无法区分这两种口径；若恢复后源又不可用，可能从 A..B 起播整曲 | 中：历史/恢复口径 |
| M8 | `service/MusicService.kt:457-469` | media3 默认媒体通知 | 通知的 position/duration/buffered 全部来自 M2-M4；没有单独的循环文案 | 依赖 M2-M4 |
| M9 | 锁屏 / Android Auto / Wear / 蓝牙 | 无应用代码，读 MediaSession `PlaybackState` 的 position/duration/speed | 同 M2-M4；M4 的 -1 会直接显示为未知时长 | 依赖 M4 |
| M10 | `service/MusicService.kt:261-267` | restore 完成后 `loopPlaybackCoordinator.refresh` | 重新进入 SOURCE 时没有 claim 状态，失败会再次走回退；恢复位置来自 M7 | 中 |

---

## 4. 调试口 / 不变量 / 自动化测试脚本

| # | 文件:行 | 它读什么 | 源未生效时会发生什么 | 判定 |
|---|---|---|---|---|
| D1 | `app/src/debug/.../LoopDebugServer.kt:1192` | `snapshotMap.positionMs = displayPositionMs(snapshot)` | 见 D3 | 高 |
| D2 | `LoopDebugServer.kt:1224-1247` | `m3Window ?: installedWindow ?: runtime`，再 `LoopSourceRuntime.loopPath(...)`；`path=="none"` 才透传 | **build-failed 时 `installedWindow` 仍被 coordinator 置位**（`LoopPlaybackCoordinator.kt:810`），`loopPath` 返回 `source` → `positionMs` 继续折返；测试看到“A 点进度”而音频已普通播放 | **高：00027 判决器仍可能被骗** |
| D3 | `LoopDebugServer.kt:193-227` | audio/source/runtime 三个窗口；`engineName`/`loopPath` 来自 `LoopSourceRuntime`；`sourceAvailable` 才查 fallback | 同一份 `/state` 里 `loop.engine="loop"`、`loop.loopPath="source"`，但 `sourceAvailable=false`——自相矛盾；且没有 `loop.claimed` / `loop.sourceActive` 字段 | 高：缺字段 |
| D4 | `service/LoopSourceRuntime.kt:58-77` | `matches(sourceWindow)` 只比窗口字段 | 只要 `installedWindow` 被置位就报 loop/source，**不看 `LoopSourceFallback`**；build-failed 直接误报 | 高：引擎信息根因 |
| D5 | `service/LoopPlaybackCoordinator.kt:801-810` | 整表重建后无条件 `LoopSourceRuntime.onInstalled(w, path)` | factory 已在 `MediaModule.guardedSourceFactory` 登记 `BUILD_FAILED` 并返回普通文件源，但协调器仍宣布“source installed” | **高：D2/D4 的根因** |
| D6 | `service/LoopSourceFallback.kt:125-136` | `reportUnavailable` 对 `window==null || !isValid()` 直接 return | **WINDOW_INVALID 不会被登记**：reason/claimed 无记录，刷新会反复 evaluate/刷日志；与“五种同一条处理”不符 | **高：五原因覆盖缺口** |
| D7 | `service/LoopSourceFallback.kt:152-156` | `isUnavailable` 对非法窗口返回 false | 非法窗口永远不会被 claim 判定为“已登记不可用”，只能靠 `isValid` 兜底 | 高：同 D6 |
| D8 | `service/LoopSourceFallback.kt:171-179` | `latestReason()` 是**全局最近一次** | A 歌回退后切到 B 歌正常播放，About/`/state.loop.fallbackReason` 仍显示 A 的原因 | 中：跨歌污染 |
| D9 | `service/LoopSourceFallback.kt:159-163` | `clear(songId)` 后重算 `latest` | 重算条件在 `failures.remove` 之后判断 `containsKey(songId)`，边界下 `latest` 可能停在已清除的歌 | 低 |
| D10 | `core/invariants/InvariantCode.kt:16-29` | 只有 4 个 code | 没有“claim 没有 source”的 code；`claimActive && !sourceActive` 不会进 `/state.invariants` | **高：测试要求 4.3** |
| D11 | `debug/.../PlaybackInvariantMonitor.kt:406-413,761` | `windowActive = routeSource && runtimeWindow!=null && installed matches && !isUnavailable` | build-failed 时 `windowActive=false`，于是 `SourceInvariantEvaluator` 既不算“投影循环源普通”，也不会报“claim 无 source”——**静默** | 高：需新 code |
| D12 | `tools/loop-e2e.ps1:575,1304,2060,2194-2214` | 等待 `playback.engine=loop`、`loop.window`；断言 `positionMs ∈ [A,B)` | engine/position 都来自同一份被污染的调试口径，会**一起骗过**；00027 正是这样漏掉的 | **高：测试声称** |
| D13 | `tools/engine-cost-gate.ps1:697-698,710,781-786,930` | `loop.engine=loop`、`loop.loopPath=source` 才开测 | build-failed 时仍满足，成本门禁会在“普通播放”上测环带 CPU | 高 |
| D14 | `tools/engine-cost-baseline.ps1:429-430,456-461,589-595` | 同上 | 同上 | 高 |
| D15 | `tools/scenarios/content-probe.ts:7,81,132` | 内容真值判据本身；DisplaySample 里带 `engine` | 内容判据是独立真值，但场景门禁仍可能先被 D12 放行；probe 注释已点名 00027 | 中：需同步字段 |
| D16 | `tools/loop-smoke.ps1:63,121-124`、`seam-verify.ps1:23`、`seam-verify-bg.ps1:39`、`loop-tap-capture.ps1:478,508`、`self-test.ps1:82-85` | `playback.engine` / `playback.positionMs` / `loop.window` | 人工/半自动排障同样会被假位置骗 | 中 |
| D17 | `tools/preview-seek-measure.ps1:258-259,303-336` | 预览 `sourceWindowA/B_ms`、`engine=SOURCE` | 预览 SOURCE 失败会回退 M3′，脚本按 SOURCE 口径判定会失败；不会假阳性，但需识别 fallback | 低 |

---

## 5. About / 引擎信息

| # | 文件:行 | 它读什么 | 源未生效时会发生什么 | 判定 |
|---|---|---|---|---|
| A1 | `ui/about/AboutEngineInfo.kt:38-47` | `LoopEngineInfo.current(context)` 原样搬运 | 见 A2/A3 | 中 |
| A2 | `loop/audio/source/LoopEngineInfo.kt:54-68` | `LoopSourceRuntime.installedWindow != null` 决定 band；`LoopSourceFallback.latestReason()` 全局原因 | build-failed 后 installed 仍非空 → 仍显示 band；别的歌的原因也会显示 | 中 |
| A3 | `LoopEngineInfo.kt:71-90` | `fallbackReason` 非空 → `loopPath = m3`，`engine = M3_PRIME` | 主播放器回退实际是**普通整曲**，既不是 source 也不是 m3；About 却写“老引擎 (M3′)”，且 loopPath=m3 | **中：虚假引擎路径** |
| A4 | `ui/about/AboutInfo.kt:125-140` | engine/loopPath/fallbackReason 行 | 直接展示 A2/A3 的结论 | 中 |
| A5 | `ui/about/AboutInfo.kt:80-102` | 文案映射 | 只做字符串归一化，不参与真值判断 | 无需改 |

---

## 6. 分析预览页 / 结果页状态机

| # | 文件:行 | 它读什么 | 源未生效时会发生什么 | 判定 |
|---|---|---|---|---|
| P1 | `ui/loop/PreviewPlayer.kt:325-368` | `prepareSource` 失败 → 释放 SOURCE、回退 M3′ | 预览页回退后 **M3′ 仍真循环**，所以“循环生效”仍为真；只是引擎从 SOURCE 变 M3′ | 设计上正确 |
| P2 | `PreviewPlayer.kt:672-682` | `hasRingWindow` / `isRingReadyFor`：SOURCE 看 `source`，M3′ 看 `controller.window` | 与 P1 一致；不会声称 SOURCE 已装 | 正确 |
| P3 | `PreviewPlayer.kt:1281-1338` | `engine`、`sourceActive`、`windowA/B`、`sourceWindowA/B`、`liveDisplayMs` | `sourceActive` 在 `releasePlayerOnly` 里复位（1041），回退 M3′ 后为 false；字段是预览自己的真值 | 正确 |
| P4 | `PreviewPlayer.kt:1341-1349` | `window` 直接折返 `liveDisplayMs` | `_outsideLoop` 时透传；SOURCE→M3′ 回退后 window 仍在且 M3′ 真循环，折返正确 | 正确（但属于“直接读 window”的例外） |
| P5 | `ui/loop/PreviewSeekPolicy.kt:34-75` | `window` + 时间轴 | 预览自己的 seek 口径；自由试听由 `outsideLoop` 旁路 | 正确 |
| P6 | `ui/loop/LoopScreen.kt:513-517` | `isReplaying`（来自音频层/预览回调） | 只有真的在重放才显示“无缝环回中(接缝已生效)” | 正确 |
| P7 | `ui/loop/LoopScreen.kt:553-566` | `sourceMode`、`prefixReady`、`ringPreparing`、`loopTooLong` | “环缓冲前缀未就绪”“超过 300 秒降级整曲”是诚实的未生效提示 | 正确 |
| P8 | `ui/loop/LoopViewModel.kt:303-340,639-810` | `applyWindow` / `startPrefill` / `isRingReadyFor` | 预填失败只影响“接缝检查”提示，不声称主播放循环 | 正确 |
| P9 | `ui/loop/state/LoopPageState.kt:897-898` | `Ready.window()` 由 A/B 构造窗口 | 这是**分析结果/意图**，不是“当前正在循环” | 正确（审计中标注） |
| P10 | `ui/loop/LoopPageEffectRunner.kt:96` | 确认/降级文案“已转为临时结果，不再无缝” | 数据层语义，不是播放层 claim | 正确 |
| P11 | `ui/player/state/PlayerPageState.kt:44-50,723`、`PlayerPageShadow.kt:28-33,314` | `LoopIntent.armed` → `seamless` | 主播放页 debug 状态机仍按**意图**声称 seamless，源回退后不退出 | 中：debug 声称 |

---

## 7. 列表 / 菜单 / 播放模式 / 历史 / 分享

| # | 文件:行 | 它读什么 | 是否“运行期循环声称” | 判定 |
|---|---|---|---|---|
| L1 | `ui/common/MusicListItem.kt:72-78,116+` | song_loop 行 confirmed/unconfirmed 画序号下方符号 | **否**：这是“这首歌有没有已确认的无缝结果”，不是“现在正在循环” | 保留；避免文案被误解为运行态 |
| L2 | `ui/library/LibraryViewModel.kt:80`、`SongList.kt:58`、`PlaylistTabScreen.kt:70` | songId → confirmed | 否 | 保留 |
| L3 | `ui/library/SongActionDialogs.kt:418-437` | confirmed=1 才显示“无缝循环”菜单 | 否（入口可用性） | 保留 |
| L4 | `MainActivity.kt:466-474` | arm intent + playKeepPosition | 是“意图”声称，不是源声称；失败后没有提示 | 中：可在 A/B 区域显示“未生效” |
| L5 | `ui/player/PlayerScreen.kt:264-286` 等播放模式按钮 | `repeatMode == ONE` | 整曲循环为真；无缝为假，但按钮不区分 | 中：文案歧义 |
| L6 | `service/MusicService.kt:419-426`、`data/preferences/PlaybackPreferences.kt:180-190` | 保存/恢复展示位置 | 历史位置没有“当时 claim 是否生效”的标记 | 中：恢复口径 |
| L7 | `ui/logs/LogScreen.kt:221-235` | 分享日志文件 | 否：分享的是日志，不含播放进度 | 无需改 |
| L8 | 通知正文 | media3 默认标题/歌手 | 否：没有自定义“循环”文案 | 依赖 M2-M4 |
| L9 | `service/LoopPlaybackCoordinator.kt:61-64,417-442` | 显式意图 → repeat=ONE/shuffle=false | 是“意图→模式”的写回；不校验源 | 设计如此，但 claim 层需区分“无缝”与“单曲” |

---

## 8. 根因清单（按严重度）

**R1（高）没有单一 claim 状态。** 同一事实被至少 5 处各自推导：
`MusicViewModel.loopEngaged`、`LoopSessionPlayer.snapshot.sourceUnavailable`、
`LoopDebugServer.displayPositionMs.loopPath`、`LoopSourceRuntime.engineName/loopPath`、
`PlaybackInvariantMonitor.windowActive`。其中 `MusicViewModel.loopEngaged` 与
`LoopSessionPlayer` 的“SOURCE 已安装”判据相同，但 `loopEngaged` 的 M3′ 分支不校验
`audio.window`，`LoopDebugServer` 完全不查 `isUnavailable`。

**R2（高）build-failed 后仍宣布 source installed。**
`LoopPlaybackCoordinator.kt:801-810` 在整表重建后无条件
`LoopSourceRuntime.onInstalled(w, path)`；而 `MediaModule.guardedSourceFactory` 已经在同一
item 上回退到 delegate 普通文件源并登记 `BUILD_FAILED`。于是 `/state.loop.loopPath` 仍是
`source`，`displayPositionMs` 继续折返。

**R3（高）SOURCE 回退时 `getDuration()` 透传 `TIME_UNSET`。**
`LoopSessionPlayer.kt:102-114` 在 `sourceUnavailable` 时返回 `super.getDuration()`；
SOURCE 时间轴的 duration 恒为 `C.TIME_UNSET`（`LoopingPcmExtractor.kt:70`），
通知/锁屏/车机拿到 -1，而不是整曲时长。

**R4（高）WINDOW_INVALID 没有登记。**
`LoopSourceFallback.reportUnavailable`（125-126）与 `isUnavailable`（153）都对非法窗口
直接放行/返回 false。五种原因里唯一没有被“同一条未生效处理”覆盖的就是它，且会每次
refresh 反复 evaluate/打日志。

**R5（高）没有“claim 无 source”不变量。**
`InvariantCode` 只有 4 个 code；`PlaybackInvariantMonitor` 的 `windowActive` 在 build-failed
时直接为 false，既不会报 `projection-loop-source-plain`，也不会报新 code。00027 这类
“窗口在、源不在”的退化没有可检索证据。

**R6（中）UI 只在 window/uiState 变化时重算 claim。**
`activeLoopWindow`（122-126）不订阅 `LoopSourceRuntime.installedWindow` /
`LoopSourceFallback` 的变更；SOURCE 安装成功/失败后，A/B 标记可能延迟到下一次 1s 位置刷新
才更新（或短暂显示错误状态）。

**R7（中）About 把“普通播放回退”写成 M3′。**
`LoopEngineInfo.of`（71-90）看到任意 `fallbackReason` 就返回 `M3_PRIME` + `m3`；
主播放器 SOURCE 回退实际是**普通整曲**，不是 M3′。且 `current()` 用全局
`latestReason()`，会把 A 歌的回退原因显示在 B 歌上。

**R8（中）主播放页 debug 状态机只认 intent。**
`PlayerPageShadow`/`PlayerPageState` 用 `LoopIntent.armed` 判断 `seamless`；
源回退后 intent 仍 armed（为了允许改 A/B 重试），状态机会继续声称 seamless。

**R9（中）C2 回退窗口只在 session 层，不在 UI claim 层。**
`LoopSessionProjection.resolveWindow` 允许重放期回退到 `audio.window`，但
`MusicViewModel.activeLoopWindow` 只读 `LoopRuntime.window`；两者在 1~1.5s 窗口内不同步。

**R10（中）历史/恢复位置没有 claim 标记。**
`saveCurrentState` 存的是 session player 的位置（可能已折返，也可能是媒体时间），
恢复时无法知道当时 claim 是否生效；源再次失败时可能从 A..B 起播整曲。

**R11（中）测试判据与调试口同源。**
`loop-e2e.ps1` / `engine-cost*.ps1` 等待 `engine=loop` + `positionMs ∈ [A,B)`；
这两个值都由同一份“假 claim”生成，会一起通过。必须让测试同时断言
`loop.claimed=true` 与 `loop.sourceActive=true`，并优先用内容真值（`content-probe.ts`）。

**R12（低）repeat=ONE 的“单曲循环”文案歧义。**
回退后整曲循环仍是真的，但用户点的是“无缝循环”；A/B 标记消失后没有“未生效”文案，
用户可能以为“无缝循环已开”。

---

## 9. 单一事实源方案：`LoopClaim`

### 9.1 数据模型（建议放 `service/LoopClaim.kt`，纯 Kotlin，可 JVM 单测）

```kotlin
data class LoopClaim(
    val songId: Long?,
    val window: LoopWindow?,                 // 意图窗口（也是 C2 回退后的有效窗口）
    val sourceActive: Boolean,               // 源真的在环回
    val fallbackReason: LoopSourceFallback.Reason? = null,
) {
    val claimActive: Boolean
        get() = sourceActive &&
            window != null && window.isValid() &&
            songId != null && window.songId == songId

    val effectiveWindow: LoopWindow?
        get() = window?.takeIf { claimActive }

    fun displayPositionMs(rawMs: Long): Long =
        effectiveWindow?.let { LoopPosition.displayUs(rawMs * 1000L, it) / 1000L } ?: rawMs

    fun displayPositionUs(rawUs: Long): Long =
        effectiveWindow?.let { LoopPosition.displayUs(rawUs, it) } ?: rawUs

    /** 对外时长：整曲时长已知就报整曲（claim 是否生效都一样，回退时尤其要报整曲）。 */
    fun durationUs(fullDurationUs: Long): Long = fullDurationUs.coerceAtLeast(0L)
}
```

### 9.2 唯一解析器

```kotlin
object LoopClaimResolver {
    fun fromProcess(songId: Long?, runtime: LoopRuntime, audio: LoopAudioController): LoopClaim {
        val isReplaying = audio.isReplaying
        val selected = selectWindow(songId, runtime.window.value, audio.window, isReplaying)
        val sourceActive = when {
            selected == null -> false
            MainPlayerRouting.current.coordinatorReplacesItem ->
                LoopSourceRuntime.isInstalledFor(selected) &&
                    !LoopSourceFallback.isUnavailable(songId, selected)
            else -> audio.window == selected      // M3′：窗口已写入 sink 才算生效
        }
        return LoopClaim(
            songId = songId,
            window = selected,
            sourceActive = sourceActive,
            fallbackReason = if (sourceActive) null else LoopSourceFallback.reasonOf(songId),
        )
    }

    fun selectWindow(
        songId: Long?, runtimeWindow: LoopWindow?, audioWindow: LoopWindow?, isReplaying: Boolean,
    ): LoopWindow? {
        val intended = runtimeWindow?.takeIf { it.isValid() && songId != null && it.songId == songId }
        if (intended != null) return intended
        if (!isReplaying) return null
        return audioWindow?.takeIf { it.isValid() && songId != null && it.songId == songId }
    }
}
```

规则：

1. `claimActive = sourceActive && window.isValid() && window.songId == songId`；
2. **只有** `claimActive` 才折返、才画 A/B、才让 `duration` 按循环口径（时长本身仍报整曲）；
3. `sourceActive` 对 SOURCE = `isInstalledFor(window) && !isUnavailable`；
   对 M3′ = `audio.window == window`（必须真的写进音频层）；
4. 五种原因都令 `sourceActive=false`，并带 `fallbackReason`；
5. 任何显示方都不得再直接读 `LoopRuntime.window` / `LoopAudioController.window` 做折返，
   只能读 `claim.effectiveWindow` 或调用 `claim.displayPositionMs/Us`。

### 9.3 发布/失效

```kotlin
object LoopClaimStore {
    private val revision = MutableStateFlow(0L)
    val revisionFlow: StateFlow<Long> = revision.asStateFlow()
    @Volatile private var provider: ((Long?) -> LoopClaim)? = null

    fun install(provider: (Long?) -> LoopClaim) { this.provider = provider; bump() }
    fun uninstall() { provider = null; bump() }
    fun claimFor(songId: Long?): LoopClaim = provider?.invoke(songId) ?: LoopClaim(songId, null, false)
    fun invalidate() { bump() }
    private fun bump() { revision.update { it + 1 } }
}
```

写点：

- `MusicService.onCreate`：`LoopClaimStore.install { id -> LoopClaimResolver.fromProcess(id, loopRuntime, loopAudioController) }`；`onDestroy` uninstall；
- `LoopPlaybackCoordinator.refresh` 末尾、`onInstalled`/`onReleased`、
  `LoopSourceFallback.reportUnavailable/reportBuildFailure/clear/reset`：`invalidate()`；
- `guardedSourceFactory` 的 catch 已经走 `reportBuildFailure`，无需额外写。

读点（全部改走 store/claim）：

- `MusicViewModel.activeLoopWindow`：`combine(_uiState, LoopClaimStore.revisionFlow) { ... claimFor(id).effectiveWindow }`；
- `MusicViewModel.updateState`：`claimFor(id).displayPositionMs(controller.currentPosition)`；
- `LoopSessionPlayer`：`snapshot` 直接用 `LoopClaimStore.claimFor(songId)`（provider 未装时用 resolver）；
- `LoopDebugServer`：`/state.loop.claimed/sourceActive/claimActive`、`positionMs`；
- `PlaybackInvariantMonitor`：采样 `claimed/claimActive/sourceActive/reason`；
- `PlayerScreen.SeekOverlayPolicy`：参数从 `LoopWindow?` 改为 `LoopClaim`（或只收 effectiveWindow + 注释）；
- `AboutEngineInfo`：用 claim 的 `sourceActive` 决定 loopPath（若允许改 ui/about）。

### 9.4 五种回退原因的统一处理

| 原因 | 产生点 | 登记 | claim | display | duration | debug reason | retry |
|---|---|---|---|---|---|---|---|
| WINDOW_INVALID | `evaluate` 79-81 | **需修**：允许非法窗口登记 | false | 媒体时间 | 整曲 | window-invalid | 换窗口 |
| FILE_MISSING | `evaluate` 82-87 | 已有 | false | 媒体时间 | 整曲 | file-missing | 换窗口/重新 grant 后需 clear |
| FORMAT_UNSUPPORTED | `evaluate` 88-98 | 已有 | false | 媒体时间 | 整曲 | format-unsupported | 换窗口 |
| RING_TOO_LARGE | `evaluate` 106-112 | 已有 | false | 媒体时间 | 整曲 | ring-too-large | 换窗口/预算变化 |
| BUILD_FAILED | `guardedSourceFactory` catch | 已有 | false | 媒体时间 | 整曲 | build-failed | 换窗口；协调器需在 rebuild 后检查并 restore |

### 9.5 不变量

新增 code（建议 `loop-claim-without-source`）：

```kotlin
const val LOOP_CLAIM_WITHOUT_SOURCE = "loop-claim-without-source"

data class LoopClaimSample(
    val atMs: Long,
    val songId: Long?,
    val claimed: Boolean,       // window 存在且属于当前歌（意图）
    val claimActive: Boolean,   // 对用户生效（应恒为 sourceActive && claimed）
    val sourceActive: Boolean,
    val reason: String?,
)

class LoopClaimInvariantEvaluator {
    fun onSample(s: LoopClaimSample): InvariantEvent? {
        // 规则：claimActive 不得脱离 sourceActive；同时把“有意图、无源”的退化也记下来。
        if (s.claimActive && !s.sourceActive) return event(s, "claimActive=true but sourceActive=false")
        if (s.claimed && !s.sourceActive) return event(s, "claimed loop without source reason=" + s.reason)
        return null
    }
}
```

`PlaybackInvariantMonitor.sampleOnce` 每 500ms 采样一次并写入 `/state.invariants`；
`/state.invariantProbe` 增加 `claimed/sourceActive/claimActive/reason` 字段。

---

## 10. 文件级改动清单（按“先单一事实源、后接线”排序）

1. **新增** `app/src/main/java/com/zjr/hesimusic/service/LoopClaim.kt`
   - `LoopClaim` / `LoopClaimResolver` / `LoopClaimStore`（纯 Kotlin，无 Android/Compose）。
2. **新增** `app/src/main/java/com/zjr/hesimusic/service/LoopClaimInvariants.kt`（或放
   `core/invariants` 若允许）——code + `LoopClaimSample` + evaluator。
3. **改** `service/LoopSourceFallback.kt`
   - `reportUnavailable` 接受非法窗口；
   - `isUnavailable` 对非法窗口也按 start/end 匹配；
   - `reasonFor(songId, window)` 统一给 claim 用；
   - `latestReason` 增加 per-song 版本或让 About 用 `reasonOf`；
   - 每次 report/clear/reset 调 `LoopClaimStore.invalidate()`。
4. **改** `service/LoopPlaybackCoordinator.kt`
   - `rebuildQueueItem` 后检查 `LoopSourceFallback.isUnavailable(w.songId, w)`：
     失败则 `LoopSourceRuntime.onReleased()` + restore（不要 `onInstalled`）；
   - 成功才 `onInstalled`；
   - `refresh` 末尾与 restore/release 调 `invalidate`。
5. **改** `service/LoopSourceRuntime.kt`
   - `isInstalledFor` 增加 `!LoopSourceFallback.isUnavailable(...)`（防 D4）；
   - `engineName/loopPath` 用 `isInstalledFor` 而不是裸窗口比较；
   - `onInstalled/onReleased` invalidate。
6. **改** `service/LoopSessionPlayer.kt`
   - `snapshot` 用 `LoopClaimStore.claimFor(songId)`；
   - `getDuration`：`audio.fullDurationUs > 0` 时优先返回整曲（含回退）；
   - `getCurrentPosition/getBufferedPosition/seekTo` 按 `claim.claimActive` 分支；
   - C2 回退继续保留，但由 claim 统一表达。
7. **改** `service/MusicService.kt`
   - install/uninstall `LoopClaimStore`；
   - `saveCurrentState` 继续用 session player（口径已统一）。
8. **改** `ui/common/MusicViewModel.kt`
   - 删除 `loopEngaged`，`activeLoopWindow` 改读 store + revision；
   - `updateState` 用 `claim.displayPositionMs`；
   - `loopWindow` 可保留为诊断，但禁止用于显示。
9. **改** `ui/common/LoopPlaybackUi.kt`
   - `foldDisplayPositionMs` 去掉默认 true 的布尔重载，改为 `foldDisplayPositionMs(raw, claim)`；
   - `effectiveLoopWindow` 改为收 `LoopClaim`；
   - 保留旧函数仅作 `@Deprecated` 兼容（或直接删，改测试）。
10. **改** `ui/player/PlayerScreen.kt`
    - `expectedDisplayMs` 收 `LoopClaim`（或 `claim.displayPositionMs(target)`）。
11. **改** `app/src/debug/.../LoopDebugServer.kt`
    - `snapshotMap.positionMs` 用 claim；
    - `/state.loop` 增加 `claimed`、`claimActive`、`sourceActive`、`fallbackReason`（兼容保留 `sourceAvailable`）；
    - `engine/loopPath` 从 claim 派生：`claimActive && routeSource -> loop/source`，
      `claimActive && !routeSource -> loop/m3`，否则 `exo/none`。
12. **改** `app/src/debug/.../PlaybackInvariantMonitor.kt`
    - 采样并上报 `LOOP_CLAIM_WITHOUT_SOURCE`；
    - `lastSampleMap` 加字段。
13. **改** `ui/player/state/PlayerPageShadow.kt`（可选）
    - seamless 判定改为 `claimActive`，或至少标注“intent-only”。
14. **改** `ui/about/AboutEngineInfo.kt` + `LoopEngineInfo`（需确认写域）
    - fallback 时 loopPath 应为 `none`（普通播放）而不是 `m3`；
    - 用 per-song reason，不用全局 latest。
15. **改** 测试脚本（`tools/loop-e2e.ps1`、`engine-cost*.ps1`、`loop-smoke` 等）
    - 等待条件加 `loop.claimed=true` 且 `loop.sourceActive=true`；
    - 位置断言只在 claimActive 时检查 ∈ [A,B)，否则检查线性增长。
16. **改** `docs`（本文件 + 必要时 `loop-v3-service-design.md` 的 claim 契约）。

---

## 11. 测试矩阵

### 11.1 单测（纯 JVM）

| 用例 | 输入 | 断言 |
|---|---|---|
| 五种回退原因 → 未生效 | 对每个 `Reason` 构造 `LoopClaim(songId=7, window, sourceActive=false, reason)` | `claimActive=false`；`effectiveWindow=null`；`displayPositionMs(B+5000)=B+5000`；`displayPositionMs(A+1000)=A+1000`；`durationUs(225_000_000)=225_000_000` |
| 源生效 → 照旧 | `sourceActive=true` | `claimActive=true`；`displayPositionMs(B)=A`；`displayPositionMs(B+1000)=A+1000`；`durationUs(full)=full`；marker window 非空 |
| 窗口非法 | `LoopWindow(7, B, A)` | claim=false；display 透传；duration=整曲 |
| 窗口属于别的歌 | songId=8, window.songId=7 | claim=false；display 透传 |
| 无当前歌 | songId=null | claim=false |
| M3′ 未写入音频层 | `audioWindow=null`，runtime window 非空 | sourceActive=false（新 resolver 行为） |
| M3′ 已写入 | `audioWindow==window` | sourceActive=true |
| C2 重放期 audio 回退 | runtime=null, audio=window, isReplaying=true | selected=window；claim 取决于 sourceActive |
| WINDOW_INVALID 登记 | `reportUnavailable(7, invalidWindow, WINDOW_INVALID)` | `isUnavailable=true`、`reasonOf=WINDOW_INVALID` |
| 五原因登记 | 依次报告五种 | `isUnavailable=true`、`reasonOf` 正确；换窗口后 false |
| claim 无 source 不变量 | sample `{claimed=true, claimActive=false, sourceActive=false, reason}` | 返回 code=`loop-claim-without-source` |
| 正常不变量 | `{claimed=true, claimActive=true, sourceActive=true}` / `{false,false,false}` | 不返回事件 |
| Registry 去重 | 连续同一 code | count 累加（复用现有 `InvariantRegistryTest` 风格） |

### 11.2 现有测试更新

- `ui/common/LoopPlaybackUiTest.kt`：保留旧 API 断言；新增 `claim` 版本（或把
  `sourceLoopActive=false` 用例迁到 `LoopClaimTest`）。
- `service/LoopSourceFallbackTest.kt`：新增 WINDOW_INVALID 登记、五原因 reasonOf。
- `service/LoopSessionPlayerTest.kt`：新增 `LoopClaim` 的 duration/position 分支
  （不构造 Player，继续测 `LoopSessionProjection` 纯函数）。
- `ui/player/SeekOverlayPolicyTest.kt`：新增 claim 版本（源未生效 → expected=target）。
- `core/invariants/SourceInvariantTest.kt`：保持不变；新增 `LoopClaimInvariantTest`。

### 11.3 运行命令

```powershell
# 只跑相关单测（Gradle）
.\gradlew.bat :app:testDebugUnitTest --tests "com.zjr.hesimusic.service.*LoopClaim*" --tests "com.zjr.hesimusic.service.LoopSourceFallbackTest" --tests "com.zjr.hesimusic.ui.common.LoopPlaybackUiTest" --tests "com.zjr.hesimusic.service.LoopSessionPlayerTest" --tests "com.zjr.hesimusic.ui.player.SeekOverlayPolicyTest"
```

```powershell
# 设备侧：先确认 /state 字段，再跑 00027 场景
curl http://127.0.0.1:<port>/state | ConvertFrom-Json | Select-Object -Expand loop
# 期望：claimed / claimActive / sourceActive / fallbackReason 四个字段齐全；
# 回退时 claimActive=false、positionMs 不折返、durationMs=整曲。
```

---

## 12. 范围与待确认

1. **写域冲突**：任务写域包含 `ui/common`、`ui/loop`、`service`（仅 MediaSession 进度）、
   `app/src/debug`、`docs`、`app/src/test`；但“修 About 的循环路径”必须动
   `ui/about/**` 或 `loop/audio/source/LoopEngineInfo.kt`（非解码逻辑），
   “加不变量 code”动 `core/invariants/**`。**需确认是否放行这三个目录的非解码改动**；
   若不放行，About 与 code 只能按本文方案留待后续。
2. **并行写者**：当前工作区有另一路 `seek-past-b` 正在改
   `MusicViewModel`/`LoopPlaybackUi`/`LoopDebugServer`/`LoopSourceFallback`。
   本审计的第 9/10 节应在它落盘后合入，避免覆盖；建议先以本清单评审，
   再由同一写者（或串行）实现 `LoopClaim`。
3. **M3′ 的 source truth**：本文按“`audio.window == window` 才算生效”定义；
   若产品认为 M3′ 只要 `runtime.window` 非空就算（即使音频层尚未 apply），
   需明确接受瞬时假阳性。
4. **build-failed 瞬态**：factory 失败后协调器要到下一次 refresh 才 restore item；
   这期间 item 可能仍是旧循环源。本文建议在 `guardedSourceFactory` 记录失败后，
   由协调器在 `rebuildQueueItem` 同一主线程调用里检查并立即 restore，消灭瞬态。
5. **通知/车机是否要额外“未生效”文案**：当前方案只保证 position/duration 真值，
   不在通知正文写“循环未生效”；若需要，得用 `MediaSession` 自定义
   `MediaNotification.Provider`，超出本任务写域。

---

## 附录 A：审计检索命令（可复现）

```powershell
rg -n "loopRuntime\.window|activeLoopWindow|LoopWindow|isLooping|LoopIntent" app/src/main/java
rg -n "LoopSourceFallback|LoopSourceRuntime|sessionPlayer|getCurrentPosition|getDuration" app/src/main/java
rg -n "sourceAvailable|loopPath|positionMs|displayPositionMs" app/src/debug tools
rg -n "windowActive|PROJECTION_LOOP|InvariantCode" app/src/main/java app/src/debug
rg -n "saveCurrentPosition|getSavedPosition|MediaSession|onUpdateNotification" app/src/main/java
```

## 附录 B：快速判定表

| 现象 | 当前错误来源 | claim 修复后 |
|---|---|---|
| 进度条在 A，声音在 B 之后 | UI 折返 + 服务层折返 | claimActive=false → 全部透传 |
| 通知时长 -1 | `getDuration` 透传 TIME_UNSET | 优先 `audio.fullDurationUs` |
| `/state.loop.engine=loop` 但声音普通 | `installedWindow` 无条件置位 | claim.sourceActive=false + engine 派生 |
| `loopPath=source` 但工厂回退 delegate | `LoopSourceRuntime` 不查 fallback | `isInstalledFor` 查 fallback |
| 测试全 PASS 但 00027 复现 | 测试读同一份假 claim | 测试加 `claimed/sourceActive` + 内容真值 |
| About 显示 M3′ 但实际普通播放 | `LoopEngineInfo.of` fallback→m3 | claim 派生 loopPath=none |
| 列表符号被当成“正在循环” | confirmed 数据符号 | 文案保持“已确认/未确认”，不与运行态混用 |

---

*本文只出清单与方案，按任务纪律在清单评审后再动生产代码。*
