# 无缝循环语义规格（用户自然期望 vs 代码实际）

> 任务：00024「功能语义排查 A —— 规格驱动」
> 产出：先定义“一个不懂实现的用户会怎么预期”，再逐条对照代码，给判定与 file:line 证据。
> 范围：主播放器无缝循环 + 分析/预览页四模式 + 通知/MediaSession + 持久化 + 生命周期重建。
> 纪律：本文件只写 `docs/**`，不改任何代码；所有“实际行为”均来自源码。

---

## 0.0 审计快照与在途改动（重要，先读）

- **本文件的“实际行为”快照 = HEAD `ad6d42c`**（审计阅读时间约 20:02–20:12）。行号均按该快照；若按最新工作树搜索，请按符号名定位。
- 审计期间（20:08–20:16）另一路并行工作已在工作树中落了 **00028 三段式** 的在途实现：
  - 新文件：`loop/LoopPhase.kt`、`loop/audio/source/LoopWholeTimeline.kt`、`service/LoopPlaybackPhaseRuntime.kt`、`loop/audio/source/PcmRangeBlockReader.kt`；
  - 修改：`LoopSourceItemPlan`（首次进入 T=0；A/B 变化按当前展示位置映射）、`LoopSessionProjection`（WHOLE time axis + fullDurationUs）、`LoopSessionPlayer`（sourceDisplayUs/seek 带 fullDuration、phaseRuntime 更新）、`LoopClaim`（sourceTimeline/phaseHint）、`LoopPlaybackCoordinator`（entryTimelineUs）、`LoopingPcmTimeline`/`RingBand*`（prelude/tail 帧）。
- 因此：**D01/D02/D03/D04 的部分内容正在被实现**；本文件对这些条目的“实际”描述的是改动前基线，保留它是为了给 00028 的实现与验收提供“用户期望”的判据。
- **在途实现落地后仍需复核**（本审计已预见的交接点）：
  1. 预览页仍走 LEGACY 口径（`fullDurationUs=0`，T=0↔A），主播放已走 WHOLE；预览 `FULL` 仍没有 tail → **D04 的“主播放 vs FULL”可能从“前奏不同”变成“tail 不同”**，需要产品口径统一（预览是否也要 tail/归零）。
  2. 播放页 seek 覆盖层仍只用 window 做折返（`PlayerScreen.kt:572-578` 的 `expectedDisplayMs(target, window)`），没有 phase/sourceTimeline 输入；WHOLE 下 seek 到 B 之后，实际落 tail、overlay 期望折回 A → **可能仍会弹回**（D02 的回归点）。
  3. 通知/MediaSession、退出入口、失败提示、持久化（D05–D16）不在 00028 的三段式范围内，仍按本文件描述。
  4. `LoopSourceItemTag` 新增 `fullDurationUs` 后，`LoopSourceMediaItem`/工厂/旧 tag 的兼容路径需要单测覆盖（本审计未展开）。

---

## 0. 结论摘要

| # | 语义域 | 判定 | 一句话 |
|---|---|---|---|
| 1 | 进入循环 | **不符合** | 进循环直接落到 A（前奏被跳过）；B 之后进入会折回环内；非当前歌也是 A。与 00028 已确认的“位置=0、phase=prelude”冲突。 |
| 2 | 循环播放 | **不符合** | SOURCE 主播放只有 `[A,B)` 一条无限时间线：没有前奏、没有尾奏、曲末永不到达；`FULL` 也不含尾奏。 |
| 3 | seek | **不符合** | 循环中 A 前→A、B 后→B−1ms；UI 覆盖层却按“取模折返”算期望落点，松手后 1.5s 弹回；主播放 SOURCE / 主播放 M3 / 预览三套落点规则不同。 |
| 4 | A/B 编辑 | **部分符合** | 预览页松手生效、边界夹取；但生效即把播放头重置到模式起点；主播放页没有 A/B 编辑入口；合法性存在两套口径。 |
| 5 | 退出循环 | **部分符合** | 位置从“当前听到的位置”继续、repeat/shuffle 基本能恢复；但**没有用户可见的退出入口**，只能靠改 repeat/shuffle/切歌/next/prev 退出。 |
| 6 | 播放控制 | **部分符合** | 暂停/继续保持循环与位置；next/prev 会先退循环；单曲队列 next=重播当前歌；repeat 按钮兼任“无缝开关”，语义重载。 |
| 7 | 通知 / MediaSession | **部分符合** | 位置/时长数值与界面一致（折返位置 + 整曲时长）；但没有循环标识、没有退出命令，seek 只能落在 `[A,B)`，next/prev 会退出循环。 |
| 8 | 引擎与模式 | **不符合** | 主播放（SOURCE）≠ 预览 `FULL`（A>0 时走 M3，有前奏）；同一模式会按 A 是否为 0 切换引擎与语义。 |
| 9 | 失败与回退 | **不符合** | 行为上不再撒谎（不折返、不画 A/B），但用户完全不可感知“没在循环”及原因；repeat 仍是 ONE，整曲播完会重复整曲。 |
| 10 | 持久化 | **部分符合** | A/B 持久化；循环意图**不**持久化；进度保存的是折返后的展示位置，重启后停在环内中段且不再循环。 |
| 11 | 分析 | **不符合** | 进结果页即暂停主播放；确认后从 A 而非 0 开始；未确认不能循环（与 `SongLoop` 文档冲突）；取消确认会静默停掉正在循环的歌；离开页面无条件 resume。 |
| 12 | 多窗口 / 前后台 / 旋转 | **部分符合** | 旋转/分屏/后台主副播放连续；但进出分析页覆盖用户暂停意图；进程死亡丢循环；同进程服务重建语义未定义。 |

**最核心的一条**：这不是若干孤立 bug，而是“**环带源的时间轴只有 `[A,B)`**”这一实现事实与用户“整首歌是一条 0..曲末 的时间线，B 只是唯一折返点”的心智模型之间的冲突（00028 已把后者写成产品规格）。测试抓不到，是因为多条单测把当前实现写成了判据，例如 `LoopSourceItemPlanTest` 直接断言“enter before A starts at A”。

---

## 0.1 口径与名词

| 名词 | 含义 | 代码锚点 |
|---|---|---|
| A / B | 循环窗口起点/终点，半开区间 `[A,B)`，微秒口径 | `LoopWindow.kt:9-23` |
| 数据源时间 T | SOURCE 数据源自己的时间轴，**T=0 恒对应 A**，单调递增 | `LoopingPcmTimeline.kt:8-17,66-74` |
| 展示位置 display | UI/通知/持久化看到的进度；SOURCE 为 `A + floorMod(T,L)`；M3 为 raw 折返，raw<A 透传 | `LoopSourceProjection.kt:19-25`；`LoopPosition.kt:20-24` |
| claim | 唯一“是否真的在循环”的真值：`sourceActive && window 可用 && songId 匹配` | `LoopClaim.kt:43-52` |
| phase | prelude/loop/tail 三段——**当前代码不存在**（00028 验收要求新增，`/state.loop` 无该字段） | `app/src/debug/.../LoopDebugServer.kt:210-249` |
| 前奏 prelude | `[0,A)`，用户心智中“只播一次”的段落 | 00028 规格 |
| 尾奏 tail | `(B,曲末]`，用户心智中“退出循环/最后一轮之后播、播完回 0”的段落 | 00028 规格 |

> 下文所有 `文件:行` 以 2026-11-21 的工作区源码为准；行号可能随重构漂移，按符号名复核。

---

# 第一部分：逐域“用户自然期望 vs 代码实际”

## 1. 进入循环：从哪个位置开始？

### 用户自然期望（不懂实现）
- “我在听 0:30，点『无缝循环』，音乐不应该跳，应该从 0:30 接着放。”
- 按 00028 已确认的产品口径：**从 0 开始（前奏），播到 B 回 A；A 之前能 seek 到前奏，B 之后能 seek 到尾奏。**
- 无论当前在 A 之前、区间内、B 之后，还是当前根本没在播这首歌，进入行为都应该是可预测且一致的。

### 代码实际
| 进入时状态 | 实际起点 | 证据 |
|---|---|---|
| 当前歌，位置 < A（前奏） | **A**（前奏被跳过） | `LoopSourceItemPlan.kt:115-120`（`offset<=0 → 0`，而 T=0 即 A）；单测直接钉死：`LoopSourceItemPlanTest.kt:51-56` |
| 当前歌，A ≤ 位置 < B | 保持同一相位（不跳） | `LoopSourceItemPlan.kt:59-82,115-120`；`LoopSourceItemPlanTest.kt:59-66` |
| 当前歌，位置 ≥ B（尾奏/已越过 B） | 折回 `[A,B)` 的同一相位 | `LoopSourceItemPlan.kt:115-120`；`LoopSourceItemPlanTest.kt:68-73` |
| 当前歌暂停中 | 先恢复播放，再按上面三种位置进入 | `LoopSessionPlayer.kt:82-85`（`play()` 开闸）；`MusicViewModel.kt:481-506` |
| 非当前歌 | `setMediaItem`（单项）→ 位置 0 → **A**；**播放队列被压成一首** | `MusicViewModel.kt:463-470,496-499`；`MainActivity.kt:466-474` |
| 入口顺序 | 先 `arm` 意图，再 `play`；arm 触发的首次 refresh 可能还没有当前 item（随后靠 transition/settle 补算） | `MainActivity.kt:378-388,466-474`；`LoopPlaybackCoordinator.kt:619-627,498-529` |

### 判定：**不符合**
- 区间内进入：符合“不跳”。
- A 前 / B 后 / 非当前歌进入：**不符合**“不跳”和 00028 的“从 0 开始”。
- “从 0 开始”在任何路径都不成立；00028 要求的 `phase=prelude` 也未实现。

### 说明
- 这不是“偶发 bug”，而是 SOURCE 数据结构决定的：数据源只有 `[A,B)`，T=0 就是 A，没有 `[0,A)` 的位置可放播放头（`LoopingPcmTimeline.kt:8-17`；`LoopSourceProjection.kt:19-25`）。
- 00024 里“测试抓不到”，证据就是单测把当前行为当成了规格：`LoopSourceItemPlanTest.kt:49-73`。

---

## 2. 循环播放：到 B 之后去哪？前奏与尾奏算什么？

### 用户自然期望（不懂实现）
- 进循环后：`0 → 前奏 → A → … → B → A → …`，前奏只播一次。
- `B` 是**唯一折返点**；在循环段内无限环回。
- 尾奏不算“循环的一部分”，但用户按 00028 的语义期望：seek 到 B 之后可以试听尾奏；尾奏播完**回 0**（不是回 A）。
- 循环中不应出现“播完了/没声了/进度归零”的静默结局。

### 代码实际
| 路线 | 实际行为 | 证据 |
|---|---|---|
| 主播放 **SOURCE**（默认） | 数据源时间线只有 `[A,B)`：`mediaUs(T)=A+floorMod(entryOffset+T,L)`；到 B 自然回 A，不 seek、不重置 T；声明 `durationUs=TIME_UNSET, isDynamic=true`，流永不 EOS | `LoopingPcmTimeline.kt:8-17,66-74`；`LoopingPcmMediaSource.kt:22-26,52-59`；`LoopSourceProjection.kt:19-25` |
| 主播放 **M3′**（开关关闭时） | 前奏 `raw<A` 原样播放一次；到 B 由 sink 裁掉输入并原地重放 ring 回 A；`isEnded()` 在 loop engaged 时恒 false | `LoopPosition.kt:20-24`；`LoopAudioSink.kt:813-826,983-987,1129-1165` |
| 预览 `FULL`（A>0） | 走 M3：从 0 播前奏，到 B 环回 A；**没有尾奏，也永不到曲末** | `PreviewStartPlan.kt:9-22,38-50,56-61`；`PreviewPlayer.kt:508-537,1158-1159` |
| 预览 `FULL`（A=0） | 走 SOURCE：0→B→A，无前奏（本来也没有），无尾奏 | `PreviewStartPlan.kt:56-61`；`PreviewPlayer.kt:1158-1159,1248-1249` |
| 预览 `LOOP_ONLY` / `SEAM` | 从 A−300ms / B−5s 起播，只在 `[A,B)` 内环回；无前奏/尾奏 | `PreviewStartPlan.kt:45-50` |
| 预览 `FREE` | 无窗口、直通整曲，到曲末 ENDED；不循环 | `PreviewPlayer.kt:1260-1264,518-522` |

### 判定：**不符合**
- “B→A 无缝”符合；除此之外：
  - **前奏**：主播放（默认 SOURCE）永不播；只有 M3 路线 / 预览 FULL(A>0) 会播一次。
  - **尾奏**：**任何循环路径都不播**；循环永不结束（SOURCE 无限流；M3 `isEnded` 恒 false）。
  - `FULL` 这个名字承诺“完整循环”，实际是“前奏 + 循环段”，不含尾奏。
- 00028 的“尾奏播完回 0”在代码中不存在；只有退出循环后由普通播放走到曲末，再按 repeat 决定是否回 0。

---

## 3. seek：任意位置分别发生什么？

### 用户自然期望（不懂实现）
- “拖到哪就播哪。”
- 循环不是“把时间线裁掉”：A 之前应能试听前奏，B 之后应能试听尾奏（00028 验收明确要求）。
- 在循环段内拖到某个相位，就落在那个相位；拖到 B 应当听到 B 附近（然后折回 A），而不是“卡在 B 前一毫秒”。
- 拖完 UI 不应先显示一个值、1.5 秒后又弹回另一个值。

### 代码实际（按状态分表）

| 状态 / 目标 | 0 | A 前 | A | `[A,B)` | B | B 后 | 曲末 |
|---|---|---|---|---|---|---|---|
| 主播放，claim 生效，**SOURCE** | **A** | **A** | A | 同当前轮相位 | **B−1ms** | **B−1ms** | **B−1ms** |
| 主播放，claim 生效，**M3′** | 0（前奏） | 原样（前奏） | 原样 | 原样 | 折回 `A+mod(target−A,L)` | 折回 | 折回 |
| 主播放，claim **不**生效（回退/无意图） | 原样 | 原样 | 原样 | 原样 | 原样 | 原样 | 原样 |
| 预览，循环模式，区内 | — | 区外→**自由试听**（原生源） | fast seek | fast seek | 区外→自由试听 | 区外→自由试听 | 区外→自由试听 |
| 预览，已自由试听 | 原生 | 原生 | 拖回区内→**恢复循环** | → 恢复循环 | 原生 | 原生 | 原生 |

证据：
- SOURCE 落点被**钳到** `[A,B−1]`：`LoopSourceProjection.kt:36-49`（`clamped = displayUs.coerceIn(a, b-1)`）；会话层调用：`LoopSessionPlayer.kt:323-329`。
- M3 落点走 `LoopPosition.rawSeekUs`：`target<A` 原样、`target>=B` 取模折回：`LoopPosition.kt:39-48`；会话层：`LoopSessionPlayer.kt:330-338`。
- claim 不生效时完全透传：`LoopSessionPlayer.kt:321-322`。
- 预览分区语义：`PreviewSeekPolicy.kt:71-91`；换原生源/恢复环带：`PreviewPlayer.kt:724-801,387-438,448-506`。
- UI 的期望落点用“取模折返”而不是“钳位”：`PlayerScreen.kt:572-578`（`expectedDisplayMs` 调 `LoopPosition.displayUs`）。于是 SOURCE 下拖到 A 前，UI 期望 A 前、实际落 A；拖到 B 后，UI 期望折返相位、实际落 B−1ms；超过 1.5s 未收敛，覆盖层释放、滑块弹回（`PlayerScreen.kt:194-208,551-597`）。

### 判定：**不符合**
- 主播放 SOURCE：A 前不可达、B 后不可达；且**落点规则与 UI 期望不是同一个函数**，必然出现“松手弹回”。
- 主播放 M3：前奏可达，尾奏仍不可达（取模折回）。
- 预览：能到区外，但代价是**离开循环模式**（换成原生整曲源）；自然播放穿过 A/B 不会自动回循环，必须再 seek 回区内或点模式（`LoopPageState.kt:125-139` 明确写了这个取舍）。

### 附：高频 seek 的“没反应”
同项连续 seek 有 120ms 合并窗口：首个立即应用，静默期内只保留最后一次，静默期结束才落地（`LoopSessionPlayer.kt:269-302,420-459`）。拖动期间 UI 本身不 seek、松手才发一次（`PlayerScreen.kt:155-178`），所以正常单次拖动没问题；但外部连续 seek（调试口/车机/自动化连发）会出现“只落最后一次”的现象。

## 4. A/B 编辑：拖动后立即生效吗？会重排位置吗？边界非法如何？

### 用户自然期望（不懂实现）
- 拖动时只看到标记跟着动，音乐不被反复打断。
- 松手立即生效；生效后播放头不应无理由跳到别处（至少应保持/就近，或明确“已回到 A”）。
- A 越过 B、或窗口太短，应被自动夹住并给出可见反馈。
- 主播放页如果也能调 A/B，调整后循环应当连续。

### 代码实际
| 场景 | 实际 | 证据 |
|---|---|---|
| 预览页拖动 | 拖动中只改 `aMs/bMs` 视觉，不发副作用；松手 `CommitWindow` 才应用 | `LoopPageState.kt:496-542,544-600` |
| 预览页应用后位置 | **重置到模式起点**：FULL→0、LOOP_ONLY→A−300ms、SEAM→B−5s、FREE→0；SOURCE 还会 SeekTo+Play | `LoopPageState.kt:555-578,612-630,886-892`；`PreviewPlayer.kt:807-877` |
| 边界非法（交叉/太短） | 拖动时夹住：A ≤ B−500ms，B ≥ A+500ms 且 ≤ 时长；跨不过去，也做不出 <500ms | `LoopPageState.kt:865-871` |
| 边界非法（协议/落库） | `/confirm` 走 `LoopWindowRules`：A<0、B≤A、<500ms、B>时长+100ms 一律 400 | `LoopWindowRules.kt:66-135`；`LoopDebugServer.kt:713-725` |
| 主播放页改 A/B | **没有入口**。唯一路径是去结果页改（会先暂停主播放），或调试口/DB 改行 | `LoopScreen.kt:212-218`；`MainActivity.kt:375-389` |
| DB 行变更传导到主播放 | 行 Flow 触发 refresh → 新窗口 → SOURCE 重建；进入点由**旧窗口下的展示位置**折进新窗口 | `LoopPlaybackCoordinator.kt:1042-1055,807-826` |
| 历史坏行 | claim 层用 `LoopWindowRules.usable` 拒绝（500ms 下限） | `LoopClaim.kt:159-168`；`LoopWindowRules.kt:142-156` |

### 判定：**部分符合 / 部分未定义**
- “松手生效 + 边界夹取”：符合。
- “生效不无故跳位置”：**不符合**（预览页总是回模式起点；用户拖完 A/B 后听到的落点不是他刚拖的位置）。
- “主播放页编辑”：**未定义**（没有产品路径；只有 DB 副作用）。
- 合法性口径：**不符合“单一口径”**，见下。

### 两套合法性口径（重要）
- 进入资格只看 `LoopWindow.isValid()`（songId>0、长度>0、A≥0）：`LoopEligibility.kt:33-43`。
- claim / 回退预检看 `LoopWindowRules.usable()`（额外 500ms 下限 + 时长上界）：`LoopClaim.kt:159-168`；`LoopSourceFallback.kt:81-88`。
- 后果：一条 1-sample（约 0.02ms）的历史行，在 `LoopEligibility` 眼里“有窗口”，在 claim 眼里“非法”。SOURCE 路线会被预检拦下、回退普通播放；**M3′ 路线会把该窗口直接写进音频层**（`LoopShapePlan.kt:88-93` + `LoopPlaybackCoordinator.kt:668-671`），形成零长度/极短环回，可能空转或卡住。

---

## 5. 退出循环：位置回到哪？重复模式恢复成什么？

### 用户自然期望（不懂实现）
- 有一个明确的“关闭无缝循环”入口。
- 退出后从当前听到的位置继续；如果当前在循环段内，就继续往后播（包括尾奏），而不是偷偷回 A。
- 恢复到进入循环前的 repeat/shuffle；不要静默改变用户的播放模式。
- 退出后 UI/通知上的循环样式立即消失，并明确告知“已退出”。

### 代码实际
| 问题 | 实际 | 证据 |
|---|---|---|
| 用户可见的退出入口 | **不存在**。唯一显式退出是调试口 `POST /loop/exit`，且它只清意图、不恢复 repeat | `app/src/debug/.../LoopDebugServer.kt:1263-1269` |
| 隐式退出路径 | ① 用户把 repeat 改成非 ONE；② 打开 shuffle；③ 切歌；④ next/prev；⑤ 服务/进程结束 | `LoopPlaybackCoordinator.kt:353-417,577-595`；`LoopSessionPlayer.kt:189-217` |
| 退出后位置 | SOURCE 把 T 映射回 `A+floorMod(T,L)`（即当前展示位置）；M3 在重放态 seek 回展示位置 | `LoopSourceItemPlan.kt:90-107`；`LoopPlaybackCoordinator.kt:926-960`；`LoopShapePlan.kt:75-85` |
| repeat/shuffle 恢复 | 切歌/next-prev：恢复进入前两项；用户改 repeat：保留用户新值、shuffle 保持 false；用户改 shuffle：恢复 prev repeat（prev==ONE 时强制改 ALL） | `LoopIntentDecision.kt:67-77,101-113,126-160`；`LoopPlaybackCoordinator.kt:384-417,577-595` |
| “尾奏从 B 播” | 不存在：退出点总在 `[A,B)` 内；若在接缝处退出，位置≈A，会重播整个循环段后才到尾奏 | 同上 |
| 退出提示 | 无 Toast/通知/界面文案 | 全仓 `grep` 无“退出无缝”入口；通知也没有状态文案（见 §7） |

### 判定：**部分符合**
- 位置“从当前听到处继续”：符合直觉。
- repeat/shuffle 恢复：符合设计（用户主动改的那一项保留，是合理取舍）。
- **显式退出入口**：不符合。
- “退出=从 B 播尾奏”：不符合（但这是 00028 的一种期望，需产品确认）。

---

## 6. 播放控制：暂停/继续/上一首/下一首 在循环中与非循环中的区别

### 用户自然期望（不懂实现）
- 暂停/继续不改变循环与位置。
- 上一首/下一首是“切歌”，切完不应还留在上一首的 A/B 里；如果是单曲队列，按钮不应假装“下一首”其实是重播当前歌。
- **“单曲循环”是整曲重复，“无缝循环”是另一个开关**；两者视觉与语义应能区分。

### 代码实际
| 操作 | 循环中 | 非循环中 | 证据 |
|---|---|---|---|
| 暂停 | 保留位置/窗口；主播放暂停后 400ms/1.5s/5s 三次复核，确认无其它音频才关共享闸门 | 正常暂停 | `LoopSessionPlayer.kt:87-97`；`SourceAudioArbiter.kt:497-509`；`MusicService.kt:438-454` |
| 继续 | 开闸、不 seek、继续当前相位 | 正常 | `LoopSessionPlayer.kt:82-85` |
| 下一首 | **先彻底退出循环**（清意图、恢复 repeat/shuffle、还原 item），再走原生 next | 原生 next | `LoopSessionPlayer.kt:189-259`；`LoopNavigationPolicy.kt:469-487` |
| 单曲队列的“下一首” | 原生 `seekToNext` 没有下一项，绕回当前项（位置 0）；UI 的 `skipToNext` 还会走 `seekToDefaultPosition(0)` → **同一首从头播 + 退出循环** | — | `LoopNavigationContractTest.kt:87-102`；`MusicNavigationPolicy.kt:15-21`；`MusicViewModel.kt:552-577` |
| 上一首 | 先退出循环；随后 media3 原生规则：位置 >3s 时“回到本曲开头”，否则上一首（00024 #41 已记录为标准行为） | 原生 | `LoopSessionPlayer.kt:200-206`；`MusicService.kt:149-165` |
| repeat 按钮 | 三态：列表循环→随机→单曲循环；进入无缝强制 ONE+shuffle=false，按钮显示“单曲循环”；把 repeat 改走即退出无缝 | 普通三态 | `PlayerScreen.kt:264-270`；`LoopPlaybackCoordinator.kt:419-444` |

### 判定：**部分符合**
- 暂停/继续：符合。
- next/prev：多曲队列基本合理；单曲队列“下一首=重播当前歌”与“上一首=重播当前歌”不符合直觉；且两者都会**先退出循环**（用户可能只想切歌、保留下一首的无缝状态——但当前产品口径是“切歌即退出”，见 `LoopIntent` 文档）。
- repeat 语义：**不符合**。“单曲循环”按钮被无缝占用，用户无法在已确认循环的歌上表达“我只要普通单曲重复”。

---

## 7. 通知与 MediaSession：显示什么时长/位置？支持哪些命令？与界面一致吗

### 用户自然期望（不懂实现）
- 锁屏/通知/车机看到的位置、时长与 app 一致；能看出“正在无缝循环”以及 A/B（至少能看出在循环）。
- 命令与界面一致：暂停/继续/切歌/seek；如果 app 里能退出循环，通知里也应能。
- 不会出现“进度条突然从 B 跳回 A 且没有任何解释”。

### 代码实际
| 项目 | 实际 | 证据 |
|---|---|---|
| MediaSession 的 player | `LoopSessionPlayer`（包着 forwarding player） | `MusicService.kt:170-188` |
| 位置 | 折返后的 display：SOURCE 为 `A+floorMod(T,L)`；M3 为 raw 折返 | `LoopSessionPlayer.kt:99-110`；`LoopSessionProjection.kt:52-57` |
| 时长 | **整曲时长**（即便 claim 回退也报整曲，避免 -1） | `LoopSessionPlayer.kt:146-156`；`LoopSessionProjection.kt:84-94`；`LoopClaim.kt:71-75` |
| 缓冲位置 | 折返 + 保证不小于当前位置 + clamp 到整曲 | `LoopSessionPlayer.kt:112-144`；`LoopSessionProjection.kt:128-137` |
| 命令 | 标准命令 + 强制加入 `COMMAND_SEEK_TO_NEXT/PREVIOUS`（只要队列非空） | `MusicService.kt:114-128` |
| 通知内容 | 默认 media3 通知；只在“关闭媒体通知”时换成静音通道通知；没有循环/A-B/状态文案 | `MusicService.kt:512-524` |
| 通知 seek | 与 app 同一路径 → SOURCE 循环中只能落到 `[A,B)` | `LoopSessionPlayer.kt:317-340` |
| 通知 next/prev | 与 app 同一路径 → **会退出循环** | `LoopSessionPlayer.kt:189-217` |
| 外推越界 | MediaController 两次事件之间会外推，可能冲出 B；UI 再折返一次（幂等） | `LoopPlaybackUi.kt:12-50`；`MusicViewModel.kt:326-339` |

### 判定：**部分符合**
- 位置/时长数值与界面一致：符合（同一个 claim/session 口径）。
- 循环可感知性：**不符合**。通知/锁屏没有 A/B、没有“无缝循环”标识；SOURCE 主播放的进度条永远只在 `[A/时长, B/时长]` 之间走，到 B 回 A，用户无法从通知判断这是循环还是播放器出错。
- 命令一致性：**不符合**。通知没有“退出循环”命令；next/prev 会退出循环；单曲队列也显示 next/prev。

## 8. 引擎与模式：主播放、预览四模式，各自语义是否一致？

### 用户自然期望（不懂实现）
- 引擎/模式是内部实现，用户不该感知。
- “无缝循环”（主播放）应该等于预览里的“完整循环”（FULL）：从 0 开始、B→A、尾奏播完回 0。
- 四种试听模式只是“从哪个位置开始试听”，不应改变“循环是什么”。

### 代码实际
| 路线/模式 | 起点 | 前奏 | B→A | 尾奏 | 证据 |
|---|---|---|---|---|---|
| 主播放 SOURCE（默认） | A | **无** | 有 | **无**（永不结束） | `LoopingPcmTimeline.kt:66-74`；`LoopingPcmMediaSource.kt:22-26` |
| 主播放 M3（隐藏开关） | 当前 raw | 有（raw<A 透传） | 有（sink 重放） | **无**（isEnded=false） | `LoopPosition.kt:20-24`；`LoopAudioSink.kt:813-826,983-987` |
| 预览 FULL，A>0 | 0 | 有 | 有 | **无** | `PreviewStartPlan.kt:9-22,38-50`；`PreviewPlayer.kt:508-537` |
| 预览 FULL，A=0 | 0=A | 无（本来就没有） | 有 | **无** | `PreviewStartPlan.kt:56-61` |
| 预览 LOOP_ONLY | A−300ms（SOURCE 下被钳到 A） | 无 | 有 | 无 | `PreviewStartPlan.kt:45-50`；`PreviewPlayer.kt:1248-1249` |
| 预览 SEAM | max(A,B−5s) | 无 | 有 | 无 | 同上 |
| 预览 FREE | 0 | 整曲 | 无 | 有（直通到 ENDED） | `PreviewPlayer.kt:518-522,1260-1264` |

关键代码事实：
- 主播放路线是**构建时快照**，四个接线点（renderers / factory / coordinator / session projection）共用：`MainPlayerRoute.kt:7-35,59-104`；`MediaModule.kt:73-74`。
- 主播放默认 SOURCE：`LoopSourceMainPlayer.kt:21-37`；`LoopSourceMode.kt:33-48`。
- 预览 FULL 是否走 SOURCE 由“是否有前奏”决定：`PreviewStartPlan.kt:56-61`；`PreviewPlayer.kt:1158-1159`。
- 主播放没有“FULL 模式”，只有“循环/不循环”；SOURCE 分支直接 return，不再走 M3 的形态逻辑：`LoopPlaybackCoordinator.kt:654-657`。

### 判定：**不符合**
- 主播放（SOURCE）与预览 FULL(A>0) 语义不同：前者没有前奏，后者有。
- 预览 FULL 自己也会按 A 是否为 0 切换引擎与起点，同一模式语义分叉。
- 引擎开关在 UI 中被隐藏（`LoopScreen.kt:268-270` 注释），用户无法选择或理解差异；但语义差异真实存在。
- “FULL”名不符实（无尾奏、永不结束）。

---

## 9. 失败与回退：源建不成时，用户看到什么、听到什么、能否感知“没在循环”

### 用户自然期望（不懂实现）
- 如果没能进入循环，应**明确告知**（Toast/横幅/通知），并说明原因与可选动作（去分析页换窗口 / 重试 / 就普通播放）。
- 不应显示循环样式（A/B、区间高亮）来误导；也不应让“单曲循环”按钮继续显示成无缝。
- 需要能区分“无缝循环”与“普通单曲循环”。

### 代码实际
| 项目 | 实际 | 证据 |
|---|---|---|
| 五种失败 | window-invalid / file-missing / format-unsupported / ring-too-large / build-failed | `LoopSourceFallback.kt:38-44,73-121` |
| 失败后行为 | 不替换 item、不投影、位置/时长按媒体时间；claim=false；A/B 标记不画 | `LoopSourceFallback.kt:7-23`；`LoopPlaybackCoordinator.kt:776-801`；`MusicViewModel.kt:119-122`；`LoopPlaybackUi.kt:66-77` |
| 用户提示 | **无** Toast/横幅/通知；只有一条 `Log.w` | `LoopSourceFallback.kt:131-147` |
| 原因可见处 | 仅 About 页（`fallbackReason`） | `AboutScreen.kt:149`；`AboutInfo.kt:135` |
| 模式状态 | 失败不清 intent、不恢复 repeat：仍显示“单曲循环”，整曲播完会重复整曲 | `LoopPlaybackCoordinator.kt:776-801`（只 restore item）；`LoopIntent.kt:46-58` |
| 预览侧 | SOURCE 失败会回退 M3，而 M3 仍会循环 → 预览里可能看不出失败 | `PreviewPlayer.kt:363-368` |
| 失败登记生命周期 | 进程内 per-song/per-window；服务 release 时 reset | `LoopSourceFallback.kt:188-193`；`LoopPlaybackCoordinator.kt:555-558` |

### 判定：**不符合**
- “不撒谎”（00027 的目标）符合：不折返、不画 A/B、位置按媒体时间。
- “用户能感知并知道怎么办”：不符合。用户只会发现“A/B 标记没了”，但没有解释；repeat 图标仍是单曲循环，整曲重复很容易被误认为“循环还在，只是不无缝”。
- 预览页反而会回退 M3 继续真循环，导致“预览好好的，主播放不循环”的认知撕裂。

---

## 10. 持久化：重启后 A/B、是否在循环、进度 分别恢复到什么状态

### 用户自然期望（不懂实现）
- 重启后 A/B 还在。
- 如果退出时在循环，重启后应继续循环（或至少问一句/显著提示）；不应悄悄变成普通播放。
- 进度应恢复到他“听到的位置”。
- repeat/shuffle 与退出时一致。

### 代码实际
| 项目 | 实际 | 证据 |
|---|---|---|
| A/B | 持久化在 `song_loop` 表（songId、start/end ms、confirmed、candidatesJson）；备份/恢复包含 | `SongLoop.kt:14-38`；`SongLoopDao.kt:20-57`；`BackupRestoreManager.kt:228-260` |
| 是否在循环 | **不持久化**：`LoopIntent` 是会话级 StateFlow，进程重启为空 | `LoopIntent.kt:9-24,35-38` |
| 进度 | 保存的是 `sessionPlayer.currentPosition`（折返后的展示位置），播放中每 5s + 切歌/状态变化时保存 | `MusicService.kt:456-482` |
| 恢复 | 恢复队列/当前歌/位置/repeat/shuffle；**不恢复循环意图**，因此普通播放 | `MusicService.kt:200-295` |
| repeat/shuffle | 从偏好恢复；**进入循环不写偏好**（只有用户手动改才写） | `LoopPlaybackCoordinator.kt:419-444`；`MusicViewModel.kt:594-601` |
| 调试口对比 | `/loop/enter`、`/confirm autoPlay` 反而会把偏好写成 ONE | `LoopDebugServer.kt:1236-1247,768-790` |
| 预览页草稿 | A/B/mode/position 只在 ViewModel；旋转保留，进程死亡丢失 | `LoopViewModel.kt:1093-1106` |

### 判定：**部分符合**
- A/B：符合。
- “是否在循环”：**不符合**（静默丢失；且没有“上次在循环”的提示）。
- 进度：**不符合**（保存的是折返值，不是“循环语义下的位置”；重启后从 `[A,B)` 中段开始普通播放，用户会觉得“歌被我听了一半”）。
- repeat/shuffle：**未定义/不一致**。UI 路径进入循环不写偏好，重启后偏好是进入前的值；调试路径会写 ONE。两条路径对“重启后显示什么”给出不同答案。

---

## 11. 分析：开始分析是否暂停播放？结果如何进入循环？未确认候选能不能直接循环

### 用户自然期望（不懂实现）
- 分析是后台工作，不应打断正在听的歌；如果必须暂停，至少离开页面时恢复到“我进来前的状态”。
- 分析结果确认后，无缝循环应从 0 开始（00028）。
- 未确认的候选只是草稿，不应影响正在播放的歌；也不应该能“直接循环”。
- 取消确认（demote）如果会停掉正在循环的歌，应明确提示。

### 代码实际
| 问题 | 实际 | 证据 |
|---|---|---|
| 开始分析是否暂停 | **会**：进结果页首帧就暂停主播放；调试口 `/analyze` 也主动 pause | `LoopScreen.kt:212-218`；`LoopDebugServer.kt:597-631` |
| 分析管理器本身 | 不碰播放器（暂停由页面/协议做） | `LoopAnalysisManager.kt` 全文件无 pause |
| 分析完成 | 自动落库临时结果 `confirmed=0`（top1 + 完整候选 JSON）；页面停在 Stopped，position=0 | `LoopAnalysisManager.kt:229-253`；`LoopPageState.kt:364-382` |
| 结果如何进入循环 | “确认并保存” → 写 `confirmed=1` → 回调 MainActivity `arm` + `playKeepPosition` → 主播放进入循环 | `LoopPageState.kt:780-784`；`LoopViewModel.kt:413-420`；`MainActivity.kt:375-389` |
| 进入起点 | 仍是 §1 的规则：通常 A（不是 0） | 见 §1 |
| 未确认候选 | **不能**循环：`LoopEligibility` 要求 `row.confirmed`；菜单也只在 `confirmed=1` 时显示“无缝循环” | `LoopEligibility.kt:33-43`；`SongActionDialogs.kt:421-435` |
| 文档冲突 | `SongLoop` 注释写“song_loop 行存在即生效（临时结果同样无缝播放）” | `SongLoop.kt:10-12` |
| 取消确认（demote） | row `confirmed=false` → 行 Flow 触发 refresh → 窗口变 null → 正在循环的歌**静默退出循环**；intent/repeat 不清 | `LoopAnalysisManager.kt:327-332`；`LoopPlaybackCoordinator.kt:1042-1055,756-766` |
| 离开页面 | 无条件 `musicViewModel.resume()`（除非刚确认保存并播放） | `LoopScreen.kt:234-257`（`244`） |

### 判定：**不符合 / 部分未定义**
- “未确认不能循环”：代码符合，文档不符合（需统一）。
- “确认后从 0 开始”：不符合（从 A）。
- “分析不打断播放 / 离开恢复原状态”：不符合用户期望（进入 pause、离开无条件 resume，覆盖用户原本的暂停）。
- “取消确认停循环”：行为上会停，但静默且不清 intent/repeat，不符合“可感知、可恢复”。

---

## 12. 多窗口 / 前后台 / 旋转：重建后语义是否连续

### 用户自然期望（不懂实现）
- 旋转、分屏、深色模式、字体缩放、前后台切换、系统回收后，音乐与循环状态连续；不因 UI 重建改变播放/暂停。
- 进程被杀后，重启至少不应“假装还在循环”或“悄悄把循环弄丢”。

### 代码实际
| 场景 | 实际 | 证据 |
|---|---|---|
| 旋转/分屏（预览页） | KEEP_ALIVE：播放器/位置/探针保留，不 seek、不重建；播放器真丢了才按页面位置重建 | `PreviewHostLifecycle.kt:66-109,130-157`；`LoopViewModel.kt:1008-1089`；`LoopScreen.kt:232-257` |
| 旋转/分屏（主播放） | 播放器在 Service，不受 Activity 重建影响 | `MusicService.kt:100-198` |
| 后台/前台 | Service 继续播；每 5s 存位置；通知继续 | `MusicService.kt:456-465` |
| 进出分析页 | 进入 pause 主播放；离开无条件 resume（即使进来前是暂停） | `LoopScreen.kt:212-218,244` |
| 进程死亡 | 恢复队列/歌/位置/repeat/shuffle；循环意图丢失；位置是折返值 | `MusicService.kt:200-295,467-482`；`LoopIntent.kt:19-20` |
| 同进程 Service 重建 | `release()` 清 runtime/audio/claim，但 `LoopIntent` 单例仍在；restore 后若偏好恰好是 ONE，refresh 会**重新进入循环**（位置=保存的折返位置）。若偏好不是 ONE，意图仍armed，之后用户手动切到“单曲循环”时会**突然复活**旧的无缝循环 | `LoopPlaybackCoordinator.kt:540-562`；`MusicService.kt:288-294`；`LoopIntent.kt:35-38` |
| 预览进程死亡 | 页面状态丢失；重进结果页按 DB 行恢复候选/已确认窗口，position=0（不是原位置） | `LoopViewModel.kt:501-556`；`LoopPageState.kt:364-382` |

### 判定：**部分符合**
- 旋转/分屏/后台：符合。
- 进出分析页：不符合（覆盖暂停意图）。
- 进程死亡：不符合（循环静默丢失）。
- 同进程 Service 重建：**未定义**（intent 存活会让循环“复活”，与进程死亡语义相反；用户无法预期）。需要产品裁决：Service/进程生命周期变化是否一律清 intent，或明确“同一进程内保留意图”。

# 第二部分：偏差清单（按“用户会多困惑”排序）

> 每条：现象 / 用户预期 / 实际 / 代码位置 / 建议改法 / 改动量估计
> 改动量：S ≈ 1–2 文件、<50 行；M ≈ 3–5 文件、50–200 行；L ≈ 跨层（源/会话/UI/测试）>200 行。

## D01 进循环直接跳到 A，前奏永远听不到
- **现象**：点“无缝循环”，播放头从当前位置/0 直接落到 A 开始播。
- **用户预期**：从当前位置继续；按 00028 则从 0 开始、phase=prelude。
- **实际**：SOURCE 进入落点 `floorMod(max(0,pos−A),L)`：<A→0（即 A）；≥B→折回环内；非当前歌先 `setMediaItem` 到 0 再落到 A。
- **代码**：`LoopSourceItemPlan.kt:52-55,115-120`；`LoopSourceItemPlanTest.kt:51-73`；`MainActivity.kt:466-474`；`MusicViewModel.kt:463-470,496-499`。
- **建议**：实现 00028 三段式（见第三部分）。最小可行两步：① 入口把位置置 0 并显式进入 prelude（若沿用 SOURCE，需要数据源支持 `[0,A)`）；② 在支持前，UI 明确提示“将从 A 开始”。
- **改动量**：L（数据源时间轴 + 会话投影 + 状态机 + 测试）。

## D02 循环中 seek 到 A 前/B 后的落点与 UI 期望不一致（松手弹回）
- **现象**：拖到 A 之前，落到 A；拖到 B 之后，落到 B−1ms；滑块先显示目标，1.5s 后弹回。
- **用户预期**：拖到哪播哪；A 前播前奏、B 后播尾奏（00028）。
- **实际**：SOURCE 落点钳到 `[A,B−1]`（`LoopSourceProjection.kt:36-49`；`LoopSessionPlayer.kt:323-329`）；UI 期望用取模折返（`PlayerScreen.kt:572-578`）；两者不是同一函数。
- **建议**：先统一口径——抽一个 `SeekMapping` 纯函数，session 与 UI overlay 共用；在三段式落地前，把不可达区间在 UI 上禁用/标灰，或松手时明确提示“已落在 A/B 边界”。三段式落地后，SOURCE 支持 prelude/tail 分段，seek 按 phase 落点。
- **改动量**：一致化 M；完整前奏/尾奏 L。

## D03 尾奏永不播、曲末永不到，“完整循环”名不符实
- **现象**：循环中歌曲永远不会结束；听不到 B 之后的尾奏；预览 `FULL` 也一样。
- **用户预期**：尾奏该播；播完回 0（00028）；“完整循环”= 整首歌 + 折返。
- **实际**：SOURCE 时间轴只有 `[A,B)`、时长 TIME_UNSET、永不 EOS（`LoopingPcmTimeline.kt:66-74`；`LoopingPcmMediaSource.kt:22-26,52-59`）；M3 在 loop engaged（窗口生效且引擎已配置）时 `isEnded()` 恒 false（`LoopAudioSink.kt:813-826,983-987`）。
- **建议**：同 D01 的三段式；若不实现，至少把模式改名为“前奏+循环段”，并在 UI 说明“退出循环后才播尾奏”。
- **改动量**：L（同 D01）。

## D04 主播放 ≠ 预览 FULL（有前奏时换引擎、换语义）
- **现象**：预览 FULL 能从 0 播前奏；主播放无缝循环直接到 A。同一首歌两种“完整循环”。
- **用户预期**：主播放无缝 = FULL。
- **实际**：FULL 在 A>0 时走 M3（`PreviewStartPlan.kt:56-61`；`PreviewPlayer.kt:1158-1159`），主播放默认 SOURCE（`LoopSourceMainPlayer.kt:21-37`；`LoopSourceMode.kt:33-48`），SOURCE 分支直接 return（`LoopPlaybackCoordinator.kt:654-657`）。
- **建议**：以 FULL 语义为准统一（主播放走 M3，或 SOURCE 扩展 prelude）；若产品坚持“主播放只是纯循环段”，则把 FULL 改名并明确两者不同。
- **改动量**：L（统一引擎）/ S（仅文案与命名）。

## D05 没有显式“退出无缝循环”入口，“单曲循环”按钮兼任开关
- **现象**：进入后只能靠把 repeat 改成非 ONE、打开 shuffle、切歌或 next/prev 退出；找不到“关闭无缝”。
- **用户预期**：一个明显的开/关；单曲循环与无缝循环是两个概念。
- **实际**：唯一显式 `/loop/exit` 在 debug（`LoopDebugServer.kt:1263-1269`）；进入强制 repeat=ONE + shuffle=false（`LoopPlaybackCoordinator.kt:419-444`），播放页三态按钮即无缝状态（`PlayerScreen.kt:264-270`）。
- **建议**：播放页/底部条/通知加“无缝循环”开关；repeat 与 seamless 解耦（进入不强制 ONE，或只在显示层合并）；循环页/长按菜单提供“退出”。
- **改动量**：M。

## D06 回退静默：没在循环，但用户不知道，也听不出“为什么”
- **现象**：窗口太大/格式不支持/构建失败 → A/B 标记消失、按整曲播放；无任何提示；repeat 仍显示单曲循环。
- **用户预期**：明确告知 + 原因 + 可选动作。
- **实际**：只 `Log.w`（`LoopSourceFallback.kt:131-147`）；About 页才有原因（`AboutScreen.kt:149`）；intent/repeat 不清（`LoopPlaybackCoordinator.kt:776-801`）。
- **建议**：claim=false 且 intent armed 时弹一次 Toast/横幅 + 通知文案；提供“退出无缝 / 去分析页换窗口 / 重试”；About 保留详情；repeat 显示与 seamless 解耦。
- **改动量**：S/M。

## D07 重启后循环消失、进度停在循环段中间
- **现象**：上次在循环，重启后普通播放，位置在 `[A,B)` 中段，A/B 标记也没了。
- **用户预期**：继续循环，或至少提示“上次的循环未恢复”。
- **实际**：intent 不持久化（`LoopIntent.kt:9-24,35-38`）；进度保存折返值（`MusicService.kt:467-482`）；restore 不恢复循环（`MusicService.kt:200-295`）。
- **建议**：持久化 loop intent（songId + 是否 active + phase），restore 时自动恢复或询问；进度按 phase + 相位保存；若产品选择“不恢复”，首次播放时给一次性提示。
- **改动量**：M。

## D08 从列表给“另一首歌”进循环，会把播放队列压成一首
- **现象**：正在播 A 歌，长按 B 歌 → 无缝循环；之后“下一首”没有歌，退出循环后队列也只剩 B。
- **用户预期**：循环只改变当前曲目的播放方式，不应摧毁队列。
- **实际**：`playKeepPosition` 对非当前歌走 `play(song)` → `controller.setMediaItem(mediaItem)`（单项）；而普通点歌走 `playList` 保留队列（`MusicViewModel.kt:463-470,481-506,508-525`）。
- **建议**：循环入口复用 `playList`/库队列定位（可参考 debug `/loop/enter` 的 `libraryQueueFor`，`LoopDebugServer.kt:751-759,1228-1233`），不要 `setMediaItem`。
- **改动量**：S/M。

## D09 循环中 next/prev 先退循环；单曲队列 next=重播当前歌；prev>3s=重播当前歌
- **现象**：循环中按“下一首”，先退出无缝再切歌；单曲队列按“下一首”会把当前歌从头重播；按“上一首”若位置 >3s 也重播当前歌。
- **用户预期**：next=下一首、prev=上一首；单曲队列按钮不应假装有下一首；切歌后是否保留无缝应有明确规则。
- **实际**：`navigateOutsideLoop` 先 `exitForNavigation` 再原生导航（`LoopSessionPlayer.kt:189-259`；`LoopNavigationPolicy.kt:469-487`）；单曲队列原生绕回（`LoopNavigationContractTest.kt:87-102`）；UI `skipToNext` 在无下一项时 `seekToDefaultPosition(0)`（`MusicViewModel.kt:552-577`）；prev 走 media3 原生规则（`LoopSessionPlayer.kt:200-206`）。
- **建议**：明确产品规则并让按钮行为一致：① 单曲队列隐藏/禁用 next/prev；② prev 区分“回到本曲开头”和“上一首”（长按/菜单）；③ 若希望“切歌保留无缝”，在 transition 后按新歌的 confirmed+intent 规则重新判定（当前口径是切歌即退出）。
- **改动量**：S/M。

## D10 通知/锁屏无循环标识，seek 只能落在 [A,B)，next/prev 会退出循环
- **现象**：通知/车机显示整曲时长，但进度永远在 A..B 之间回跳；看不到 A/B；按 next 会退出循环；seek 到区间外会弹回。
- **用户预期**：与 app 一致，能看出在循环，命令不产生意外副作用。
- **实际**：默认 media3 通知（`MusicService.kt:512-524`）；位置折返/时长整曲（`LoopSessionPlayer.kt:99-156`）；命令强制加 next/prev（`MusicService.kt:114-128`）；seek 走同一 clamp（`LoopSessionPlayer.kt:317-340`）。
- **建议**：MediaMetadata extras 带 `loopActive/A/B`；自定义通知显示循环状态；增加“退出循环”命令；循环中 seek 到区间外时给出可见提示；单曲队列隐藏 next/prev。
- **改动量**：M。

## D11 分析页：进入即暂停主播放，离开无条件恢复（覆盖用户原本的暂停）
- **现象**：用户暂停音乐后打开分析页再返回，音乐自己开始播。
- **用户预期**：我进来前是暂停，出去还应该暂停；分析不应打断我听歌（或明确告知）。
- **实际**：进页 `musicViewModel.pause()`（`LoopScreen.kt:212-218`）；离开 `musicViewModel.resume()`（`LoopScreen.kt:234-257`）；调试 `/analyze` 也 pause（`LoopDebugServer.kt:597-631`）。
- **建议**：进入时记录 `wasPlaying`，离开只在 `wasPlaying` 时 resume；或产品裁决“分析不暂停”（00024 #107），改为后台分析/降优先级。
- **改动量**：S。

## D12 试听页 A/B 松手后播放头被重置到模式起点
- **现象**：拖完 A/B 松手，音乐跳回 FULL 的 0 / LOOP_ONLY 的 A−300ms / SEAM 的 B−5s。
- **用户预期**：只改循环边界，位置保持/就近；至少不要无声跳走。
- **实际**：`CommitWindow` 固定产出 `SeekTo(modeStartMs)` + `Play`（`LoopPageState.kt:555-578,580-599`）；`PreviewPlayer.updateWindow` 也回到 `sourceStartMs(mode)`（`PreviewPlayer.kt:832-877`）。
- **建议**：新窗口仍包含当前相位时保持位置；否则落到 A 并提示；只有显式点模式才回模式起点。
- **改动量**：S/M。

## D13 未确认/取消确认：文档与代码冲突；demote 静默停掉正在循环的歌
- **现象**：分析完的临时结果显示“可以无缝”，但实际不能进循环；正在循环的歌被“取消最终结果”后静默停止循环。
- **用户预期**：文档说什么就是什么；取消确认应提示“已停止无缝”。
- **实际**：`SongLoop.kt:10-12` 写“行存在即生效”，`LoopEligibility.kt:33-43` 要求 `confirmed=true`；菜单也只显示 confirmed=1（`SongActionDialogs.kt:421-435`）；demote 只改行（`LoopAnalysisManager.kt:327-332`），coordinator 收到行变更后清窗口（`LoopPlaybackCoordinator.kt:1042-1055,756-766`），不清 intent/repeat、不提示。
- **建议**：统一口径（建议代码口径：confirmed 才生效），修正 `SongLoop` 注释；demote 时若当前歌在循环，等同“退出循环”并提示、恢复 repeat/shuffle。
- **改动量**：S。

## D14 合法性两套口径（1-sample 窗口“有资格”但 claim=false；M3 路线可能写进音频层）
- **现象**：历史坏行（A==B 或只差 1 sample）在 SOURCE 下回退普通播放；M3 路线下可能把零长度窗口写进音频层。
- **用户预期**：非法窗口一律不进循环，且行为一致。
- **实际**：`LoopEligibility.kt:33-43` 只判 `isValid`；claim/预检用 `LoopWindowRules.usable`（`LoopClaim.kt:159-168`；`LoopSourceFallback.kt:81-88`）；M3 形态只写窗口不 seek（`LoopShapePlan.kt:88-93`）。
- **建议**：`LoopEligibility` 直接调 `LoopWindowRules.usable`，删除 `isValid`-only 分支；协议/UI/claim 共用同一个 verdict。
- **改动量**：S。

## D15 高频 seek 合并：连发时只落最后一次，中间落点被丢弃
- **现象**：0.1s 间隔连发 20 次 seek，只有最后一次真正落地；从外部看像“前面几次没反应”。
- **用户预期**：至少最后一次生效，且过程有反馈（现在的确保证最后一次，但中间无反馈）。
- **实际**：`RapidSeekCoalescer` 120ms 静默窗口（`LoopSessionPlayer.kt:269-302,420-459`）；拖动中 UI 不 seek（`PlayerScreen.kt:155-178`）。
- **建议**：保持“拖动中不 seek”；对非 UI 连发（车机/调试）在 UI 显示“跳转中”；或把 quiet 窗口缩到 <100ms 并保留首尾。
- **改动量**：S。

## D16 进入循环不写 repeat 偏好 → 重启后显示模式与进入时不一致
- **现象**：进入循环时显示“单曲循环”，重启后变回进入前的模式（如列表循环），但 A/B 还在。
- **用户预期**：看到的模式与持久化一致。
- **实际**：进入只 `player.setRepeatMode(ONE)`，不写偏好（`LoopPlaybackCoordinator.kt:419-444`）；偏好只在用户手动改时写（`MusicViewModel.kt:594-601`）。调试口反而写偏好（`LoopDebugServer.kt:1241-1243,784-786`）。
- **建议**：进入时同步写偏好（并记录 prev 以便恢复），或 restore 时以播放器实际模式为准；保证 UI/持久化/调试口同一口径。
- **改动量**：S。

# 第三部分：建议的目标语义（以 00028 已确认口径为准）

~~~text
时间线：整首歌 0 .. duration 是一条线，B 是唯一折返点。

phase:
  prelude : [0, A)     正常播放，只播一次
  loop    : [A, B)     到 B 立刻回 A（无缝）
  tail    : (B, duration]  只有 seek 到 B 之后才进入；播完回 0（不是回 A）

进入循环: position = 0, phase = prelude
prelude 播到 A        -> phase = loop（继续，不跳）
loop 播到 B           -> position = A, phase = loop
seek 到 A 之前        -> phase = prelude
seek 到 B 之后        -> phase = tail
tail 播到曲末         -> position = 0, phase = prelude
任意 seek             -> 按落点决定 phase
退出循环              -> 从当前听到的位置继续；恢复进入前的 repeat/shuffle
~~~

落地要点（与本文偏差一一对应）：
1. **单一时间线**：数据源/会话层不能只认识 `[A,B)`；需要“整曲时间轴 + 一个折返点”。两条实现路：主播放切 M3（现成前奏能力，补 tail/归零），或把 SOURCE 扩展成整曲时间线（`<A` 按需解码、`>=B` 继续读、到曲末回 0）。
2. **单一 seek 映射**：UI overlay、MediaSession、主播放、预览共用同一个纯函数；先保证“期望落点 == 实际落点”，再谈前奏/尾奏。
3. **phase 一等字段**：`/state.loop.phase`（00028 已承诺）、通知、播放页都读它；“是否在循环”不再靠 A/B 标记隐式表达。
4. **模式解耦**：repeat 按钮回归“整曲重复/列表/随机”；无缝循环有自己的开关与退出入口；失败回退必须可见。
5. **持久化语义**：要么持久化 loop intent 并在重启时恢复/询问，要么明确“重启不恢复循环”并一次性提示；进度按 phase 存。

### 落地顺序建议（按“性价比”而非单纯困惑度）

1. **先做三段式（D01/D02/D03）**：已经在途（§0.0）。它同时解决“跳 A / 前奏不可听 / 尾奏不可达 / 永不结束”四个最大困惑；做完后本文件 §1–§3 需按新实现复核，尤其是 **UI seek overlay 必须接同一套映射**（否则 tail seek 仍会弹回）。
2. **再做“可感知性”三件（D05/D06/D10）**：显式退出入口、回退提示、通知/MediaSession 的 phase 展示。它们不改播放核心，但直接决定用户是否信任“无缝循环”这个功能。
3. **然后做“状态一致性”三件（D13/D14/D16）**：统一 confirmed 语义、统一窗口合法性口径、统一 repeat 偏好写入。都是小改动，能消掉一批“看起来随机”的行为。
4. **最后做“生命周期与队列”（D07/D08/D09/D11）**：需要产品裁决（重启是否恢复循环、单曲队列 next 的语义、分析是否暂停），不应由实现单方面决定。
5. **D12/D15** 可随各自主线顺手修（A/B 松手落点、seek 合并反馈）。

---

# 第四部分：证据索引（按文件）

| 文件 | 关键行 | 内容 |
|---|---|---|
| `service/LoopClaim.kt` | 43-52, 55-75, 148-180 | claimActive / effectiveWindow / display / duration / resolver |
| `service/LoopWindowRules.kt` | 27, 66-135, 142-156 | 500ms 下限、A<B、时长上界、usable |
| `service/LoopSessionPlayer.kt` | 82-97, 99-156, 189-259, 269-340, 420-459 | 暂停/位置/时长/next-prev/seek 合并/seek 投影 |
| `service/LoopSessionProjection.kt` | 36-41, 52-64, 84-111, 128-137 | raw/display/source 投影、时长、seek、buffer |
| `service/LoopPlaybackCoordinator.kt` | 353-444, 540-609, 638-706, 721-905, 926-960, 1042-1055 | 意图进入/退出、形态、SOURCE 换源、导航退场、行订阅 |
| `service/LoopShapePlan.kt` | 59-93 | 进入/退出是否 seek |
| `service/LoopSourceItemPlan.kt` | 52-55, 59-82, 90-107, 115-120 | 进入/退出落点 |
| `service/LoopSourceFallback.kt` | 7-23, 38-44, 73-147, 188-193 | 五种失败、回退、登记 |
| `service/LoopSourceRuntime.kt` | 32-63, 89-108, 125-145 | 接管登记/engine/loopPath/revive |
| `service/LoopIntentRefreshPolicy.kt` | 15-42 | transition 过滤 |
| `service/MusicService.kt` | 114-128, 170-198, 200-295, 438-482, 512-524 | 命令、session、restore、闸门、保存、通知 |
| `service/MusicNavigationPolicy.kt` | 15-21 | 队列绕回 |
| `loop/LoopPosition.kt` | 20-24, 39-48, 54-58 | display / rawSeek / iteration |
| `loop/LoopWindow.kt` | 11-24 | 窗口与 isValid |
| `loop/LoopIntent.kt` | 9-24, 35-58 | 会话级意图 |
| `loop/LoopIntentDecision.kt` | 67-77, 91-113, 126-160 | 退出与恢复 |
| `loop/LoopEligibility.kt` | 27-44 | 进入资格 |
| `loop/audio/LoopAudioSink.kt` | 813-826, 983-987, 1129-1165 | M3 环回/isEnded |
| `loop/audio/source/LoopSourceProjection.kt` | 19-25, 36-49 | SOURCE display / seek 钳位 |
| `loop/audio/source/LoopingPcmTimeline.kt` | 8-17, 66-74 | T=0↔A、无限折返 |
| `loop/audio/source/LoopingPcmMediaSource.kt` | 22-26, 52-59 | 无限动态源、TIME_UNSET |
| `ui/common/MusicViewModel.kt` | 119-122, 326-339, 463-506, 552-601 | claim 转发、折返、play/keepPosition、next/prev、模式 |
| `ui/common/LoopPlaybackUi.kt` | 12-77 | 折返/生效窗口/A-B 比例 |
| `ui/loop/PreviewPlayer.kt` | 299-371, 387-506, 508-545, 724-877, 1158-1259, 1338-1360, 1495-1529 | 四模式起播、自由试听、arm、A/B、SOURCE、seek、位置 |
| `ui/loop/PreviewStartPlan.kt` | 9-61 | FULL/LOOP_ONLY/SEAM/FREE 起播与 SOURCE 可表达性 |
| `ui/loop/PreviewSeekPolicy.kt` | 71-91 | 区外/区内 seek 决策 |
| `ui/loop/state/LoopPageState.kt` | 269-273, 364-382, 496-600, 612-647, 780-784, 823-892 | 页面 reducer、A/B、模式、确认、seek |
| `ui/loop/LoopViewModel.kt` | 501-556, 573-630, 937-940, 1008-1089, 1093-1106 | 入口、分析、确认、宿主重建 |
| `ui/loop/LoopScreen.kt` | 212-257 | 进页暂停、离开恢复 |
| `ui/player/PlayerScreen.kt` | 151-255, 264-270, 551-597 | 滑条/覆盖层/A-B 标记/模式按钮 |
| `ui/library/SongActionDialogs.kt` | 421-435 | 菜单 confirmed 门槛 |
| `MainActivity.kt` | 375-389, 466-474 | 确认保存/菜单入口 |
| `debug/.../LoopDebugServer.kt` | 210-249, 597-631, 660-749, 1209-1269 | /state.loop、/analyze、/confirm、/loop/enter/exit |

---

# 第五部分：方法与限制

1. **证据来源**：本次通读了 source of truth：`LoopClaim`、`LoopSessionPlayer`、`LoopPlaybackCoordinator`、`LoopWindowRules`、`LoopSessionProjection`、`LoopSourceItemPlan`、`LoopSourceFallback`、`LoopSourceRuntime`、`LoopIntent`/`LoopIntentDecision`/`LoopEligibility`、`PreviewPlayer`/`PreviewStartPlan`/`PreviewSeekPolicy`、`LoopPageState` reducer、`LoopViewModel`、`MusicViewModel`、`MusicService`、`LoopAudioSink`、`LoopingPcm*`、`MainActivity` 与相关单测；并用 grep 枚举了所有进入/退出/seek/通知的调用点。
2. **未改代码**：本文件只做审计，没有修改任何 `app/**` 文件，也没有写数据库。
3. **未操作真机**：为避免与其他 agent 的设备占用冲突，本次未执行 adb/HTTP 操作；所有结论均可由源码复核。需要真机确认的点主要是“用户听感”（例如 M3 前奏的实际情况、共享闸门 400ms 延迟、通知栏 UI），已由 §6/§7 的证据链给出可测判据。
4. **文档冲突**：已单独标出 `SongLoop.kt:10-12`（“行存在即生效”）与 `LoopEligibility.kt:33-43`（要求 confirmed）的冲突。00028 承诺的 `phase` 字段在本审计基线（HEAD）未实现；审计期间的在途改动已新增 `LoopPhase`/`LoopWholeTimeline`（见 §0.0），落地后需把本文件的 §1–§3 按新实现复核一遍。
5. **行号漂移**：行号基于本次审计时的源码；后续重构请按符号名定位。
6. **不在本审计范围**：内容真值（听到的是不是显示的位置）、性能/接缝质量、内存预算、长窗口降级后的可听性。这些已有 00027/00024 的 content-truth 线覆盖。




