# 全应用状态机架构（00014 第一阶段：框架 + 播放页纯模型）

本轮只新增纯 Kotlin 模型、矩阵测试与本文档，**不接 UI、不改现有播放器/服务/数据层**。

## 0. 本轮交付

| 文件 | 作用 |
|---|---|
| `app/src/main/java/com/zjr/hesimusic/core/statemachine/StateMachine.kt` | 框架契约：`SmState/SmEvent/SmEffect`、`Transition`、`Invariant`、`StateMachineSpec` |
| `app/src/main/java/com/zjr/hesimusic/core/statemachine/MatrixTest.kt` | JUnit 友好的 `状态 × 事件` 全矩阵跑测器与失败报告 |
| `app/src/test/java/com/zjr/hesimusic/core/statemachine/MatrixTestSelfTest.kt` | 框架自测（10 个用例，见 §3.5） |
| `app/src/main/java/com/zjr/hesimusic/ui/player/state/PlayerPageState.kt` | 播放页纯模型：状态 / 事件 / effect / reducer / 不变量 |
| `app/src/main/java/com/zjr/hesimusic/ui/player/state/PlayerPageStateMachine.kt` | 播放页合法状态域（10 代表实例）、事件全集（19 夹具）、effect 类型声明 |
| `app/src/main/java/com/zjr/hesimusic/ui/player/state/PlayerPageShadow.kt` | 第二阶段影子双跑：`PlayerSnapshot`、`project`、`diff`、`PlayerPageShadow`、诊断计数器 |
| `app/src/test/java/com/zjr/hesimusic/ui/player/state/PlayerPageReducerTest.kt` | `PlayerPageReducerTest`（190 矩阵用例）+ 矩阵闸门 + 19 条规则命名用例 |
| `app/src/test/java/com/zjr/hesimusic/ui/player/state/PlayerPageShadowTest.kt` | 影子双跑 27 个用例（project/diff/事件序列/seek 抑制/零 effect） |

纪律：未修改 `ui/player/PlayerScreen.kt`、`ui/common/MusicViewModel.kt`、`ui/loop/**`、`loop/**`、`service/**`、`data/**`、`di/**`、gradle、debug 源集；未安装依赖、未操作设备、未 commit。

---

## 1. 框架契约（`core/statemachine`，纯 Kotlin，零 Android 依赖）

### 1.1 API

```kotlin
interface SmState
interface SmEvent
interface SmEffect

sealed interface Transition<out S : SmState, out F : SmEffect> {
    data class Applied<S, F>(val state: S, val effects: List<F>) : Transition<S, F>
    data class Rejected<S, F>(val state: S, val reason: String) : Transition<S, F>
}

fun interface Invariant<S : SmState> {
    /** 返回违规原因；null 表示通过。 */
    fun violation(state: S): String?
}

class StateMachineSpec<S : SmState, E : SmEvent, F : SmEffect>(
    val name: String,
    val states: List<S>,                 // 合法状态域（代表实例）
    val events: List<E>,                 // 事件全集
    val reduce: (S, E) -> Transition<S, F>,
    val invariants: List<Invariant<S>>,
    val allowed: (S, E) -> Boolean,      // 显式声明「该状态 × 该事件」是否允许
    val effectTypes: Set<KClass<out F>>, // 声明的 effect 具体类型（矩阵逐个校验）
)
```

约定：

1. `allowed(state, event) == true` 的组合必须返回 `Transition.Applied`；
2. `allowed == false` 的组合必须返回 `Transition.Rejected`，且 **`Rejected.state === 入参 state`**（同一实例，用于断言「非法操作没有产生非法状态」）；
3. `Applied` 的结果必须仍在合法状态域内（**按状态类型判定**：`states` 是代表实例，字段级合法性由 `invariants` 负责；否则 `Playing(position)` 只要换位置就“落出状态域”，框架不可用）；
4. 每次转换后（含 `Rejected` 返回的 state）所有 `invariants` 必须通过；
5. `Applied.effects` 的每个具体类型必须在 `effectTypes` 中声明；
6. `allowed` 的推荐实现是对 `sealed` state/event 做**穷尽 `when`（不写 `else`）**：新增 state/event 类型会直接编译失败，强制同步矩阵。这是「不更新矩阵即失败」的编译期闸门。

### 1.2 MatrixTest

```kotlin
MatrixTest(spec).run()        // 返回 MatrixReport（不抛异常，可二次断言）
MatrixTest(spec).assertClean() // 失败时抛 AssertionError，消息就是矩阵片段
```

对 `states × events` 的每个单元格：

- 校验 `allowed` 与 `Transition` 是否自洽；
- 校验非法组合的 state 实例不变；
- 校验合法组合的新状态仍在合法域（状态类型）内；
- 校验所有不变量；
- 校验 effect 类型是否已声明；
- `reduce/allowed` 抛异常也会被收进矩阵失败，而不是让 JUnit 只显示堆栈。

失败输出包含：

1. 汇总：`N states × M events = X cells（声明允许 / 拒绝），失败 K 处`；
2. ASCII 矩阵：`.` 允许且通过、`R` 拒绝且通过、`!` 失败；
3. 每个失败单元格：`state × event → 实际 Transition`、声明期望、问题、该 state 的整行与 event 的整列；
4. 完整 state/event 图例（`sNN/eNN` 映射）。

### 1.3 框架自测

`MatrixTestSelfTest` 用一个会话状态机验证框架本身能报出：

| 用例 | 验证点 |
|---|---|
| `cleanSpecPassesAndEnumeratesEveryCell` | 干净矩阵通过，单元格数 = states × events |
| `appliedForUndeclaredCombinationIsReported` | 未声明组合却返回 Applied |
| `rejectedThatReplacesStateInstanceIsReported` | Rejected 替换了 state 实例 |
| `matrixOmittedStateIsReportedAsOutOfDomain` | 合法状态域漏了一个状态（矩阵遗漏） |
| `invariantViolationIsReported` | 不变量违反 |
| `undeclaredEffectTypeIsReported` | effect 类型未声明 |
| `emptyEffectTypeDeclarationIsReported` | 未声明 effectTypes（契约不完整） |
| `throwingReducerIsReportedAsMatrixFailureInsteadOfEscaping` | reducer 异常被矩阵报告 |
| `declaredIllegalStateIsReported` | 声明的代表状态自身违反不变量 |
| `assertCleanThrowsAssertionErrorWithMatrixFragment` | 失败消息包含矩阵片段与 `state × event →` |

---

## 2. 播放页纯模型（`ui/player/state`）

### 2.1 状态表

| 状态 | 关键字段 | 含义 |
|---|---|---|
| `Idle` | — | 没有当前歌曲 / 尚未开始 |
| `Playing(positionMs, mode)` | 位置、播放模式 | 正在播放 |
| `Paused(positionMs, mode)` | 位置、播放模式 | 已暂停 |
| `Seeking(dragMs, targetMs, pendingSince, mode, resume)` | 拖拽本地值 / 提交目标 / 松手时刻 / 模式 / 收敛后回到 Playing 还是 Paused | 拖拽或 pendingSeek 覆盖层 |
| `Buffering(positionMs=0, mode)` | 位置、模式 | 已请求播放、位置尚未推进（加载/缓冲） |
| `Ended(mode)` | 模式 | 自然播放结束 |
| `Error(message, mode)` | 错误信息、模式 | 播放错误 |

实现说明：

- 任务约定的核心字段都在；为满足「收敛后回到正确传输状态」「任何状态都能校验 mode 不变量」，`Seeking` 额外携带 `mode`/`resume`，`Buffering/Ended/Error` 额外携带 `mode`（都有默认值）。这是**补充上下文**，不是新增状态语义。
- `Seeking.pendingSince`：`null` = 手指仍在拖（只改本地 `dragMs`，不下发 seek）；非 `null` = 已松手，进入 pendingSeek 覆盖层。
- `mode` 的结构：

```kotlin
enum class RepeatMode { OFF, ONE, ALL }          // 对应 Media3 REPEAT_MODE_*

data class SeamlessIntent(
    val songId: Long,
    val previousRepeat: RepeatMode,               // 进入无缝前记录的 repeat
    val previousShuffle: Boolean,                 // 进入无缝前记录的 shuffle
)

data class PlayMode(
    val repeat: RepeatMode = RepeatMode.OFF,
    val shuffle: Boolean = false,
    val seamless: SeamlessIntent? = null,         // null = 无无缝意图
)
```

`mode` 是「界面显示」与「实际播放器」的**唯一事实源**：任何状态变化都同时更新 mode；effect 只声明需要写播放器的差异。

### 2.2 事件表（19 个矩阵夹具 / 15 种事件类型）

| 事件 | 语义 |
|---|---|
| `Play` / `Pause` / `Toggle` | 播放 / 暂停 / 取反 |
| `DragStart` | 按下进度条：进入 `Seeking`，只改本地值，不 seek |
| `DragMove(positionMs)` | 拖拽中：只改 `dragMs` |
| `DragEnd(targetMs)` | 松手：提交**唯一一次** `SeekTo`，进入 pendingSeek 覆盖层 |
| `Seek(positionMs)` | 程序化 seek（按钮/点击轨道），不自动播放 |
| `SetRepeat(mode)` | 用户改 repeat（播放器已生效后观测到） |
| `SetShuffle(enabled)` | 用户改 shuffle（播放器已生效后观测到） |
| `TrackChanged(songId, hasLoopIntent)` | 播放器切歌；`hasLoopIntent` 决定是否重新武装无缝 |
| `PositionTick(positionMs)` | 播放器位置 tick（展示口径） |
| `PlaybackError(message)` | 播放错误 |
| `EnterSeamless(songId)` | 显式进入无缝循环（菜单 / 分析页确认保存） |
| `ExitSeamless(reason)` | 显式退出无缝循环 |
| `PlaybackEnded` | 自然播放结束（对应 Media3 `STATE_ENDED`） |

矩阵夹具把 `SetRepeat`、`SetShuffle`、`TrackChanged`、`PositionTick` 各拆成正反/不同取值，共 19 个事件夹具。

**为什么多一个 `PlaybackEnded`**：任务给出的状态域包含 `Ended`，但事件表中没有任何事件能进入 `Ended`。`PlaybackEnded` 是 `Ended` 的唯一入边（真实实现来自 Media3 `STATE_ENDED`），否则 `Ended` 是不可达状态、矩阵只能测它作为初始态的行为。若后续确认必须严格限定事件集，可把它并入一个“播放器状态回调”事件，但语义上仍需要这个入边。

### 2.3 effect 表（6 种，全部已声明）

| effect | 含义 |
|---|---|
| `PlayerPlay` | 开始播放 |
| `PlayerPause` | 暂停 |
| `SeekTo(positionMs)` | 播放下发 seek（展示口径毫秒） |
| `SetRepeat(repeat)` | 写播放器 repeat |
| `SetShuffle(enabled)` | 写播放器 shuffle |
| `ShowToast(message)` | 提示（当前仅 `PlaybackError` 使用） |

### 2.4 不变量（任何状态、任何转换结果都必须满足）

1. **全局模式不变量**：`shuffle == true ⇒ repeat != ONE`。UI 的显示优先级是 shuffle > repeat，两者同时成立时「显示 = 实际」会自相矛盾（00004 的最后一条反馈就是「界面显示随机、实际单曲循环」）。
2. **无缝意图形态**：`mode.seamless != null ⇒ repeat == ONE && shuffle == false && seamless.songId > 0`。
3. **位置非负**：`Playing/Paused/Buffering.positionMs >= 0`；`Seeking.dragMs/targetMs >= 0`；`pendingSince != null ⇒ pendingSince >= 0`。
4. **拖拽一致性**：`Seeking.pendingSince == null ⇒ targetMs == dragMs`（拖拽中没有“提交目标”）。

### 2.5 五条规则的可执行定义

| # | 规则 | reducer 行为 | 命名用例 |
|---|---|---|---|
| 1 | `Seeking` 期间 `PositionTick` 不得改变 `dragMs` | 拖拽中 tick 原样返回**同一 state 实例**、零 effect；pending 中 tick 也不改 `dragMs/targetMs` | `rule00001_positionTickDuringDragNeverMovesDragHandle` |
| 2 | `DragEnd` 后 pendingSeek 覆盖层直到收敛或超时 | `DragEnd` → `Seeking(dragMs=target, targetMs=target, pendingSince=nowMs)` + `SeekTo(target)`；`PositionTick` 满足 `abs(pos-targetMs) <= 1200` 或 `nowMs - pendingSince >= 1500` 才回到 `Playing/Paused(pos, mode)`，否则保持覆盖层 | `rule00001_pendingSeekOverlayHoldsUntilConvergence` / `...ConvergenceBoundaryIsExactly1200ms` / `...TimesOutAt1500ms` / `...ResumesPausedWhenDragStartedFromPaused` |
| 3 | `mode` 显示必须与实际一致（无缝意图驱动） | 见 §2.7；进入/退出/切歌/用户改模式分别按裁决写回 | 6 个 `rule00004_*` 用例 + 矩阵一致性用例 |
| 4 | 任何状态不允许 `shuffle=true && repeat=ONE && 无无缝意图` | 不变量 §2.4-1 全局化；归一化规则见 §2.7 | `rule00004_modeInvariantRejectsShufflePlusSingleLoopWithoutIntent` / `...globalInvariantShuffleImpliesNotSingleLoop` |
| 5 | `Ended/Error` 下除 `Play/TrackChanged` 外全部 Rejected | `allowed` 的统一白名单；`Play` 从 0 重播 | `rule00005_endedAndErrorRejectEverythingExceptPlayAndTrackChanged` / `...playFromEndedOrErrorRestartsAtZero` |

补充：规则 5 的「全部 Rejected」包括 `PlaybackEnded`（已在 Ended/Error 时重复上报结束）和 `PositionTick`；矩阵的 `Ended × 19`、`Error × 19` 行逐格钉住。

### 2.6 时钟注入（保证纯函数可测）

pendingSeek 的 1.5s 超时不能读 `System.currentTimeMillis()`。reducer 签名：

```kotlin
fun reduce(
    state: PlayerPageState,
    event: PlayerPageEvent,
    nowMs: Long = 0L,
): Transition<PlayerPageState, PlayerPageEffect>
```

- 矩阵与默认路径传 0（只测状态图）；
- 命名用例显式传 `nowMs` 测 1200ms 收敛边界与 1500ms 超时边界；
- 接 UI 时传 `SystemClock.elapsedRealtime()`（或统一注入的时钟），拖动过程中 tick 即使带新时间也不改 `dragMs`。

### 2.7 模式配对裁决（00004，与服务层 `LoopIntentDecision` 对齐）

本节语义与 `loop/LoopIntentDecision.kt` 的 `ModeRestore` 保持一致：**用户主动改模式时，用户刚改的字段已经写到播放器，状态机只做意图退出与“另一个字段”的补写**；补写字段为 `null` 表示不写。

| 场景 | 目标 mode | 写回（effect） |
|---|---|---|
| **进入无缝** `EnterSeamless` | `repeat=ONE, shuffle=false`，记录进入前两个值 | 只写与当前不同的字段；顺序：先 `SetShuffle(false)` 再 `SetRepeat(ONE)`（保证中间态也不出现 shuffle+ONE）。**进入不落持久化偏好**（模型只记录会话级 `SeamlessIntent`） |
| **切歌/离开武装歌** `TrackChanged`（无新意图） | 两个都恢复进入前的值 | 只写不同的字段（repeat 先于 shuffle） |
| **切歌且新歌有意图** `TrackChanged(hasLoopIntent=true)` | 先恢复旧意图，再按“恢复后的模式”记录新意图并强制 ONE/false | 只写实际差异；不会把 ONE/false 当成新原值 |
| **用户改 repeat**（无缝中，改成非 ONE） | `repeat=用户值, shuffle=false`，清空 seamless | **不写任何模式**（用户刚写的 repeat 已生效；shuffle 保持 false，不恢复进入前的值）→ `effects = []` |
| **用户改 shuffle**（无缝中，打开随机） | `shuffle=true`，repeat 恢复进入前值；若进入前是 ONE 则改 **ALL** | 只写恢复的 repeat（`SetRepeat(target)`）；**不重复写 shuffle** |
| **用户关闭 shuffle** | 保持 repeat 不变 | 不触发任何不变量强制；无缝态下关闭本就为 false 的 shuffle 是恒等 no-op（保留意图） |
| **显式退出无缝** `ExitSeamless` | 两个都恢复进入前的值 | 只写不同的字段 |
| 非无缝下的模式归一化 | `SetRepeat(ONE)` 且当前 shuffle=true → 关 shuffle；`SetShuffle(true)` 且当前 repeat=ONE → repeat 改 ALL | 只补写“另一个字段”（用户刚改的字段不重复下发） |

为什么用户改 repeat 不恢复 shuffle：三态按钮的语义是「单曲循环 → 列表循环」，若此时把进入前的 `shuffle=true` 恢复回来，界面会跳成「随机播放」，与用户刚按下的操作打架（`LoopIntentDecision.modeToRestoreOnRepeatChange` 返回空 `ModeRestore` 的同一理由）。

### 2.8 事件-effect 语义（接 UI 时必须遵守）

- `SetRepeat` / `SetShuffle` 事件表示「用户操作已写入播放器后，状态机观测到的新模式」。因此 reducer **不会重复下发用户刚改的字段**；effect 只包含：
  - 退出无缝时要恢复的另一个字段；
  - 归一化所需的补写（shuffle+ONE → 关 shuffle，或把 repeat 改 ALL）；
  - 进入/退出无缝、切歌等状态机主动发起的模式写。
- 接 UI 的写切换必须二选一：**动作先写播放器再派发事件**（推荐，贴合当前 `MusicViewModel.setRepeatMode/setShuffleModeEnabled` 与 `LoopIntentDecision` 的持久化偏好判据）；或把“写用户字段”也纳入 effect，但那需要同时改服务层判据，属于更大的迁移。

---

## 3. 矩阵规模与「先红后绿」证据

### 3.1 覆盖规模

| 项 | 数量 |
|---|---|
| 播放页状态代表实例 | 10（Idle、Playing 普通/无缝、Paused 随机/无缝、Seeking 拖拽/pending、Buffering、Ended、Error） |
| 播放页事件夹具 | 19 |
| 播放页矩阵 | 10 × 19 = **190** 个参数化用例 |
| 框架自测 | 10 |
| 矩阵闸门/一致性用例 | 5 |
| 规则命名用例 | 19 |
| 影子双跑用例 | 27 |
| 本轮新增测试合计 | **251** |
| 全量单测（第二阶段完成时） | **1022，0 失败** |

矩阵闸门测试同时断言：

- `states/events` 夹具数量与 `PlayerPageStateMachine` 一致；
- `EXPECTATIONS` 恰好覆盖 190 个 `(stateIndex,eventIndex)`，无重复、无缺失；
- `MatrixTest.assertClean()` 通过（非法组合实例不变、合法组合不落域、不变量、effect 类型）；
- 每个 Applied 结果的 effect 值等于新状态里的 mode（显示=实际）；
- 矩阵产生的所有 effect 类型都在 `effectTypes` 里声明。

### 3.2 先红：00001 规则的 mutant

临时把 `seekTick` 的拖拽分支改成写回 `dragMs/targetMs`：

```diff
- val pendingSince = state.pendingSince ?: return applied(state)
+ val pendingSince = state.pendingSince ?: return applied(
+     state.copy(dragMs = clamp(state, positionMs), targetMs = clamp(state, positionMs)),
+ )
```

运行 `PlayerPageRuleTest` + `PlayerPageReducerTest`：

```
PlayerPageRuleTest > rule00001_positionTickDuringDragNeverMovesDragHandle FAILED
PlayerPageReducerTest > matrixCaseMatchesExplicitExpectation[108: Seeking(dragging) × PositionTick(31000)] FAILED
PlayerPageReducerTest > matrixCaseMatchesExplicitExpectation[109: Seeking(dragging) × PositionTick(45500)] FAILED
209 tests completed, 3 failed
```

失败消息（节选）：

```
拖拽中 PositionTick 必须返回同一个 state 实例
  expected same:<Seeking(dragMs=30000, ...)> 
  was not:<Seeking(dragMs=31000, ...)>

Seeking(dragging) × PositionTick(31000)：新状态
  expected:<Seeking(dragMs=12000, ...)> 
  but was:<Seeking(dragMs=31000, ...)>
```

证明「拖拽中 tick 改写本地位置」这类 00001 回弹在模型层会立刻红。

### 3.3 先红：00004 规则的 mutant

临时让 `enterSeamless` 不强制关闭 shuffle：

```diff
- shuffle = false,
+ shuffle = mode.shuffle,   // MUTANT
```

运行 `PlayerPage***`：

```
PlayerPageMatrixGateTest > everyTransitionResultSatisfiesAllInvariants FAILED
PlayerPageMatrixGateTest > frameworkMatrixIsClean FAILED
PlayerPageReducerTest > matrixCaseMatchesExplicitExpectation[73: Paused(shuffle) × EnterSeamless(7)] FAILED
PlayerPageRuleTest > rule00004_enterSeamlessForcesSingleLoopAndRecordsOriginals FAILED
PlayerPageRuleTest > rule00004_exitSeamlessRestoresBothValues FAILED
214 tests completed, 5 failed
```

失败消息（节选）：

```
Paused(shuffle) × EnterSeamless(7)：不变量[2] 违反：
  播放模式自相矛盾：shuffle=true ⇒ repeat != ONE（实际 repeat=ONE）；
  无缝意图要求 repeat=ONE 且 shuffle=false（实际 repeat=ONE, shuffle=true）

[player-page] 10 states × 19 events = 190 cells ... 失败 1 处
s04   . . . . R R . . . . . . . . . . ! R R     ← Paused(shuffle) × EnterSeamless

Paused(shuffle) × EnterSeamless(7)：新状态
  expected:<... mode=PlayMode(repeat=ONE, shuffle=false, seamless=SeamlessIntent(..., previousRepeat=ALL, previousShuffle=true))>
  but was:<... mode=PlayMode(repeat=ONE, shuffle=true, ...)>
```

### 3.4 后绿

两轮 mutant 都用临时备份恢复到原文件（文件哈希一致、源码无 `MUTANT` 标记），随后：

```
> .\gradlew.bat :app:testDebugUnitTest
BUILD SUCCESSFUL
892 tests, 0 failures
```

MUTANT 期间产生的红是真实 JUnit 失败（非手工断言），恢复后全量绿，证明矩阵对 00001/00004 类错误有实际的拦截能力。

### 3.5 框架自测也会红（摘录）

- `matrixOmittedStateIsReportedAsOutOfDomain`：reducer 返回未列入 `states` 的状态类型 → `Applied 落出合法状态域：... 不在 states 中（矩阵遗漏了一个状态？）`；
- `invariantViolationIsReported`：`Active(token="")` → `不变量违反[1]：Active.token 不能为空`；
- `appliedForUndeclaredCombinationIsReported`：`allowed=false，但 reducer 返回了 Applied...`；
- `rejectedThatReplacesStateInstanceIsReported`：`Rejected 必须返回同一个 state 实例`；
- `undeclaredEffectTypeIsReported`：`effect 类型未在 effectTypes 中声明：ClearCache`。

---

## 4. 状态机清单与迁移优先级

| 优先级 | 范围 | 合法状态域草案 | 主要风险 / 对应 bug |
|---|---|---|---|
| **P1（本轮）** | 播放页 | Idle/Playing/Paused/Seeking/Buffering/Ended/Error + PlayMode | 00001 拖拽回弹/pendingSeek；00004 无缝意图与模式配对；切歌、错误、结束态闸门 |
| **P1.5** | 播放页接 UI | 同上 + effect runner | 双写、effect 循环、服务层 `LoopIntent` 与本模型的所有权冲突、tick 时钟、MediaController 异步 |
| **P2** | 媒体库页 | Loading/Ready/Selecting/BatchAction/Empty/Error/ScanningHint；排序、筛选、歌单归属为子状态 | 00002 返回列表位置、批量删除后的选中态、滚动/快速滚动、长按菜单 |
| **P3** | 设置/扫描 | 设置项分组 + 保存中/失败；扫描 Idle/Scanning/Paused/Completed/Failed；备份 Idle/Running/Verifying/Done/Failed | 低频但状态多、异步任务取消/恢复；可在 P1/P2 模式验证后批量套用 |
| **P3** | 睡眠定时/日志/调试 | 定时器 Idle/Armed/Fired/Cancelled；日志过滤/加载 | 主要是生命周期与计时，状态机收益中等 |

子状态机组合约定（P2/P3 落地时）：

1. 每个子页面一个 `StateMachineSpec`，父页面持有子状态并转发事件；
2. 子机 effect 向上冒泡，由父机统一排序执行；父机不得直接改子状态字段；
3. 父子边界用「离开子页面」事件收口，避免子机事件在父机状态已失效后继续生效；
4. 每条跨页不变量（如“正在播放的歌与播放页 songId 一致”）放到父机 invariants，子机只保证局部不变量。

---

## 5. 矩阵覆盖率闸门约定

1. **每个状态机必须有 `MatrixTest(spec).assertClean()`**，且测试类名包含机器名；
2. `spec.states/events` 是唯一的合法域声明：新增状态类型/事件类型必须同时更新这里；
3. `allowed` 对 sealed state/event 做**穷尽 `when`（禁止 `else`）**——新增类型编译失败，这是第一道闸门；
4. 页面测试必须有**显式期望表**：`状态数 × 事件数 == 期望表条目数`，且每个 `(state,event)` 恰好一条；缺一条测试失败（不允许“默认 no-op”静默通过）；
5. `effectTypes` 必须非空且覆盖矩阵实际产生的 effect；新增 effect 类型不声明 = 测试失败；
6. 不变量列表必须覆盖该页的核心安全性质（模式配对、位置范围、等待/悬挂状态必有出口等）；
7. CI / 本地提交前统一跑 `.\.\gradlew.bat :app:testDebugUnitTest`，矩阵失败信息就是排查入口（`state × event → 实际 Transition` + 行列片段）。

新增 state/event 的标准动作：

1. 在 sealed 类型里新增；
2. `allowed` 的穷尽 `when` 编译报错 → 逐状态补白名单；
3. 在 `Spec.states/events` 补代表实例/夹具；
4. 在页面矩阵期望表补该行/列（覆盖闸门会报缺失）；
5. 如有新 effect 类型，补 `effectTypes` 与 runner（接 UI 阶段）。

---

## 6. 接 UI 的三步安全流程

目标：把现有 `PlayerScreen`/`MusicViewModel` 的散落字段迁移到状态机，**每一步都可回滚、都可观测**，不在一次提交里同时改读写。

### 第 1 步：影子双跑（Shadow Double-Run）

- 新建适配层（建议 `ui/player/state/PlayerPageAdapter`，本轮未创建）：
  - 把现有 `MusicViewModel` 的 `isPlaying/repeatMode/shuffleModeEnabled/currentPosition/buffering/ended/error` 转成事件；
  - 用 `nowMs = SystemClock.elapsedRealtime()` 调 reducer；
  - 把模型状态投影成 `PlayerPageProjection(isPlaying, positionMs, repeat, shuffle, canDrag, showPendingSeek, ...)`；
- **不改变任何 UI 读路径、不执行 effect**；
- 每次 `updateState()` 后对比投影与旧字段，不一致时：
  - debug 构建 `Log.w` + 计数（含 state/event/旧值/新值）；
  - 可选：把差异写到调试页（不弹窗、不阻断播放）；
- 必须覆盖的观测场景：播放/暂停、拖动（含手势 cancel）、切歌、进入/退出无缝、用户改 repeat/shuffle、播放错误、自然结束、队列临时模式改写（`REPEAT_ONE→ALL`）。
- 退出条件：连续多轮真机/日常使用无未解释差异；矩阵全绿；影子日志中不存在“非法状态”（invariants 失败）。

### 第 2 步：读切换（Read Switch）

- `PlayerScreen` 改为从 projection 读取：播放键图标、进度条位置、pendingSeek 显示、模式按钮图标/文案、错误/结束态按钮可用性；
- 继续保留旧写路径（`MusicViewModel.setRepeatMode/setShuffleModeEnabled/seekTo` 等），effect **仍不执行**（或只执行无副作用的断言）；
- 重点替换 `PlayerScreen` 里本地 `draggingMs/pendingSeekMs` 覆盖层：改由 `Seeking(dragMs,targetMs,pendingSince)` 提供显示值，tick 时间用 `elapsedRealtime`；
- 若读切换后 UI 行为变化，灰度开关回退到旧读路径（保持旧字段同时更新）。
- 退出条件：拖动/暂停/切歌/无缝显示与旧版一致；无“位置闪回/回弹”；模式图标与实际一致。

### 第 3 步：写切换（Write Switch）

- 所有用户动作改为派发事件，effect runner 执行 `PlayerPlay/Pause/SeekTo/SetRepeat/SetShuffle/ShowToast`；
- **关键约定（与 §2.7/2.8 对齐）**：`SetRepeat/SetShuffle` 的“用户字段”由动作入口先写播放器（或由 runner 在派发前写），状态机只负责退出无缝/归一化/恢复另一字段，避免与 `LoopIntentDecision` 的持久化偏好判据冲突；若要把用户字段也纳入 effect，必须同步修改服务层「偏好==新值」判据，单独评审；
- 无缝意图所有权二选一（必须先定，不能双写）：
  - **方案 A（推荐首版）**：继续由 `service/LoopPlaybackCoordinator + LoopIntent` 拥有实际模式写；状态机只观测并校验，seamless effect 以 assertion/no-op 方式落地；
  - **方案 B（后续）**：状态机拥有 `SeamlessIntent`，service 订阅状态机 effect；需要迁移 `LoopIntent.arm/clear` 与偏好写点，风险更高；
- 删除旧散落字段只能在该路径被状态机完全替代且通过全量测试后进行；保留一个版本周期的开关；
- 退出条件：全量单测 + 真机回归清单（播放/暂停/拖动/无缝进入退出/切歌/模式三态/错误/结束/后台恢复）全绿；无 effect 重复执行日志。

---

## 6.5 第二阶段落地：影子双跑（只观测）

实现文件：`ui/player/state/PlayerPageShadow.kt`（纯 Kotlin）+ `MusicViewModel` 的 debug 接线 + debug `/state`。
**没有切换读路径、没有切换写路径、没有删除旧字段、没有执行任何 effect。**

### 快照与投影

`PlayerSnapshot` 是 VM 与模型之间的边界（纯 Kotlin，无 Media3 依赖）：

| 字段 | 来源 / 说明 |
|---|---|
| `songId` | `currentPlayingSongId` |
| `isPlaying` / `positionMs` | `isPlaying` / `currentPosition`（已做无缝窗口展示折返） |
| `repeat` / `shuffle` | Media3 `repeatMode` / `shuffleModeEnabled`，映射为 `RepeatMode` |
| `playbackState` | Media3 `STATE_*` → `IDLE/BUFFERING/READY/ENDED/ERROR` |
| `errorMessage` | `controller.playerError?.message` |
| `seamlessIntent` | `LoopIntent.armed` 且当前歌匹配且实际模式为 `ONE/false` 时填入 `SeamlessIntent` |
| `dragMs/pendingSeek*` | VM 通常为 null；给单测/后续接 UI 预留 |
| `nowMs` | 调用方注入（接 UI 用 `elapsedRealtime`） |

`project` 映射口径：

- 拖拽 / pendingSeek 优先 → `Seeking`（VM 无 UI 本地字段时不会出现）；
- `errorMessage`（或 `state=ERROR`）→ `Error`；
- `ENDED` → `Ended`；无歌 `IDLE` → `Idle`；
- `READY/BUFFERING` → 按 `isPlaying` 映射 `Playing/Paused`；
- **归一化** `shuffle=true && repeat=ONE`：投影按状态机不变量视为 `shuffle=false`，
  避免服务层「先写 repeat=ONE、再关 shuffle」的中间帧投影出非法状态；
- `seamless` 只在 armed 歌匹配且投影模式合法时进入。

### 事件订阅（由快照差量翻译）

| 快照变化 | 喂给 reducer 的事件 |
|---|---|
| 切歌（首次装歌以投影为起点） | `TrackChanged(songId, hasLoopIntent)` |
| armed 歌 + 模式变为 ONE/false（或 armed 变化） | `EnterSeamless` / `ExitSeamless` |
| `isPlaying` 变化 | `Play` / `Pause` |
| repeat / shuffle 变化（按归一化口径） | `SetRepeat` / `SetShuffle` |
| 出现新错误 | `PlaybackError` |
| `STATE_ENDED` | `PlaybackEnded` |
| 每次观测 | `PositionTick`（seek 抑制窗口内除外） |

`PlayerPageShadow` 只记录 reducer 产生的 effect（`ShadowObservation.effects`），从不调用播放器。
`PlayerPageShadowTest.shadowNeverExecutesEffects` 用 fake runner 断言调用次数为 0。

### 与“逐次对比”相关的两个收敛窗口

1. **显式 seek**：`seekTo()` 后通知影子；2s 内（与 VM 的位置刷新窗口一致）不做对比，
   否则 MediaController 的旧位置快照会把“seek 尚未生效”误报成模型差异；
2. **结构切换帧**：切歌 / 进出无缝 / repeat/shuffle 写序的第一帧若产生差异，记为
   `suppressed`（不增加 mismatch 计数），下一帧稳定观测仍会捕获持续差异。
   这样“服务层写序的瞬态”不会污染 `shadowMismatchCount`，但持续不一致不会被吞掉。

### debug-only 接线与 /state

- 开关：main 的 `PlayerPageShadowGate.enabled` 默认 false；只有 debug 源集的
  `DebugInitProvider` 在进程启动时置 true。release 不创建影子对象。
- `MusicViewModel` 在每次 `updateState()` 的 `_uiState` 更新后调用影子；`seekTo()`
  额外通知一次 seek。除影子自己的状态外不写任何字段、不执行 effect。
- `/state` 的 playback 下新增：

```json
"shadow": {
  "enabled": true,
  "mismatchCount": 0,
  "shadowMismatchCount": 0,
  "suppressedCount": 3,
  "lastMismatches": [],
  "lastExpected": "Playing(positionMs=..., mode=...)",
  "lastActual": "Playing(positionMs=..., mode=...)",
  "lastObservedAtMs": 123456
}
```

真机复测时看 `playback.shadow.mismatchCount` 是否为 0；若不为 0，
`lastMismatches` 直接给出字段名与 expected/actual。

---

## 7. 风险评估

| 风险 | 影响 | 缓解 |
|---|---|---|
| 双写/effect 循环 | 模式回调触发事件、事件再写播放器，形成抖振 | 明确单写者；`SetRepeat/SetShuffle` 只做补写；effect runner 幂等；日志断言 written 字段 |
| 无缝意图所有权冲突（`LoopIntent` vs 状态机） | 两处同时写 repeat/shuffle，恢复值被覆盖 | 首版采用方案 A（service 拥有写，状态机影子校验）；迁移方案 B 前先统一偏好写点 |
| `MusicViewModel` 偏好判据与模型语义不一致 | 用户操作被误判为内部写，意图不退出 | 严格保持「用户字段已写、偏好==新值」；矩阵/命名用例锁定“用户改 repeat 不写 shuffle”等裁决 |
| pendingSeek 时钟来源不一致 | 超时/收敛判定漂移，UI 闪回 | reducer 显式 `nowMs`；接 UI 用 `elapsedRealtime`；tick 与拖拽事件同一时钟源 |
| MediaController 异步快照 | seek 生效前的旧位置被当成真实位置 | 第 2 步替换本地覆盖层；收敛 ±1.2s / 超时 1.5s；延续 00001 的 2s 刷新窗口 |
| M3 Slider 手势 cancel | `onValueChangeFinished` 不回调，pending 卡住 | 保留 `DragInteraction.Stop/Cancel` 兜底派发 `DragEnd`；模型有 1.5s 超时兜底 |
| 队列临时模式改写（REPEAT_ONE→ALL） | 被误判为用户改 repeat → 退出无缝 | 服务层用持久化偏好区分；接 UI 时事件来源需带“是否用户操作”标记或只消费偏好已变的回调 |
| 进程重启/恢复 | 状态机从 Idle 恢复，无缝意图本应会话级失效 | 启动时 `TrackChanged` + 持久化偏好重建 `PlayMode`；不恢复 `SeamlessIntent`（与 00004 一致） |
| 重复进入无缝 | 记录错误的进入前原值，退出后无法恢复 | 当前模型重复 `EnterSeamless` 为 Rejected（保留首次原值）；若产品要求“再次进入以新值为准”，需单独裁决并更新矩阵 |
| 位置折返（无缝窗口） | 模型位置与 UI 折返显示不一致 | 接 UI 时投影层沿用 `foldDisplayPositionMs`；状态机只存展示口径 |
| 新增事件不可达 | 如本轮 `PlaybackEnded`；矩阵能测但 UI 未派发 | 接 UI 时把 `STATE_ENDED` 映射为 `PlaybackEnded`；若产品不采用，删除事件并同步状态域 |

---

## 8. 本轮验证命令

```powershell
# 新框架 + 播放页
.\.\gradlew.bat :app:testDebugUnitTest --tests "com.zjr.hesimusic.core.statemachine.*" --tests "com.zjr.hesimusic.ui.player.state.*"

# 全量
.\.\gradlew.bat :app:testDebugUnitTest
```

结果：阶段一新增 224 个测试、阶段二新增 27 个影子测试，共 251 个新测试全绿；全量 1022 个测试、0 失败。
未切换读写路径、未执行 effect、未修改任何被禁止的现有文件。
