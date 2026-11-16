# 分析/预览页 v3:显式状态机与状态×事件矩阵(第一轮:纯模型)

范围:只新增纯模型与测试,不改现有 UI/播放器/DB。新增文件:

- app/src/main/java/com/zjr/hesimusic/ui/loop/state/LoopPageState.kt
- app/src/main/java/com/zjr/hesimusic/ui/loop/LoopPageEffectRunner.kt(第二轮)
- app/src/main/java/com/zjr/hesimusic/ui/loop/LoopPageModeMapping.kt(第二轮)
- app/src/test/java/com/zjr/hesimusic/ui/loop/LoopPageReducerTest.kt
- app/src/test/java/com/zjr/hesimusic/ui/loop/LoopPageEffectRunnerTest.kt(第二轮)
- docs/loop-v3-page-state-machine.md(本文件)

现有 ui/loop/LoopViewModel.kt、LoopScreen.kt、PreviewPlayer.kt 本轮没有由本 agent 改动;
loop/audio/**、service/**、data/**、di/**、gradle、app/src/debug/** 也未改动。

注意:当前工作区里 LoopViewModel.kt / PreviewPlayer.kt / loop/audio/** /
service/LoopRingPrefiller.kt 等已有另一个 agent 的未提交改动(预填 UI/分片重构);
本轮新增文件与它们没有写冲突,也没有修改它们。

## 1. 目标

00003(预填)/00008(重复点接缝检查)/00009(改候选后无声)的共同根因是页面状态散落在
多个独立可变字段里(mode / isPlaying / ringPreparing / candidates / selectedCandidate /
aMs / bMs / dragging / confirmed / analysis phase / notice / position ...)。这些字段的
组合没有地方被系统性验证,所以边角组合一直漏。

第一轮把它们收敛成:

    reduce(state, event) -> (newState, effects)

规则:

1. 状态变化只发生在纯函数 LoopPageReducer.reduce;
2. 副作用只以 LoopPageEffect 声明,模型自身不执行;
3. 非法/过期/重复事件必须是 no-op 恒等(同一 state 实例 + 空 effect);
4. UI 只做三件事:渲染 state、派发 event、执行 effects。

## 2. 状态表

### 2.1 顶层状态

| 状态 | 关键字段 | 含义 |
|---|---|---|
| Loading | — | 首帧骨架 / 入口解析中 |
| Analyzing | phase = DECODING/ANALYZING, progress | 解码/分析中,progress 已 clamp 到 [0,1] |
| Ready | 见 2.2 | 结果页:试听、拖拽、换候选、保存 |
| Failed | message | 分析失败 / 入口守卫失败 |

### 2.2 Ready 字段

| 字段 | 类型 | 说明 |
|---|---|---|
| songId | Long | 当前歌曲 |
| durationMs | Long | 展示时长(>= 501ms) |
| sampleRate | Int | 候选采样率 |
| mode | LoopPageMode | FULL / LOOP_ONLY / SEAM / FREE |
| aMs, bMs | Long | 当前窗口(展示口径毫秒) |
| candidates | List<LoopCandidateKt> | 候选列表 |
| selectedCandidate | Int | 选中下标;无候选时为 0 |
| confirmed | Boolean | 是否已保存为最终结果 |
| prefill | PrefillState | 环缓冲预填状态 |
| playback | PlaybackState | 播放状态 |
| positionMs | Long | 当前展示位置 |
| drag | DragState | 拖拽状态 |

### 2.3 Ready 子状态

PrefillState:

| 值 | 含义 |
|---|---|
| NotStarted | 未开始(无候选 / 删除后) |
| InProgress(progress) | 解码预填中 |
| Ready | 当前提交窗口的 ring 已完整 |
| Failed(message) | 预填失败(保留降级直通路径) |

PlaybackState:

| 值 | 含义 |
|---|---|
| Stopped | 尚未起播 |
| Playing | 有音频输出 |
| Paused | 已暂停(保留位置) |
| AwaitingPrefill(resumeMode, resumeAtMs) | 等新窗口预填:位置可 tick,但尚无音频输出;PrefillReady/PrefillFailed 必须补发 Play |

AwaitingPrefill 是 00009 的核心建模:把"位置在动但没声音"从不可见的字段组合变成一个
显式、可断言的状态。只要处于该状态,就必然有一个 PrefillReady 或 PrefillFailed 会把
状态带回 Playing 并发出 Play。

DragState:

| 值 | 含义 |
|---|---|
| Idle | 未拖拽 |
| Dragging(handle, ms) | 正在拖 A/B;只改视觉,松手后的 CommitWindow 才应用窗口 |

## 3. 事件表

共 26 种事件类型;矩阵里 Enter 拆成 Enter(noSaved)/Enter(hasSaved)、SwitchMode 拆成
SwitchMode(SEAM)/SwitchMode(FULL),所以矩阵事件夹具为 28 个。

| 事件 | 有效状态 | 主要语义 |
|---|---|---|
| Enter(hasSavedResult) | Loading | 无行 -> Analyzing(DECODING,0) + RestartAnalysis;有行 -> 保持 Loading 等 VM 加载后派发 AnalysisDone |
| AnalysisProgress(phase, progress) | Loading, Analyzing | -> Analyzing(phase, clamp(progress)) |
| AnalysisDone(...) | Loading, Analyzing | -> Ready;有候选时 A/B 取 top1 并 StartPrefill |
| AnalysisFailed(message) | Loading, Analyzing | -> Failed(message) |
| Retry | Failed | -> Analyzing(DECODING,0) + RestartAnalysis |
| ManualReanalyze | Loading / Ready / Failed | -> Analyzing(DECODING,0) + RestartAnalysis;Analyzing 时 no-op(第二轮新增) |
| SelectCandidate(index) | Ready | 越界 no-op;合法 -> 应用候选 A/B、prefill=InProgress;Playing/AwaitingPrefill -> playback=AwaitingPrefill + [CancelPrefill, StartPrefill(新窗口), SeekTo(模式起点)];Stopped/Paused -> playback=Playing + 同样 effects + Play(候选点击本身是试听动作,先起播避免 seed 等 sink 的死锁) |
| DragAStart/DragAMove/DragAEnd | Ready | 只改视觉 A;Start 要求 drag=Idle,Move/End 要求正在拖 A;End 回到 Idle,不发副作用 |
| DragBStart/DragBMove/DragBEnd | Ready | 同上,手柄 B |
| CommitWindow | Ready | 应用当前 A/B;播放中/等待预填 -> AwaitingPrefill;effects=[CancelPrefill, StartPrefill(新窗口), SeekTo(模式起点)] |
| SwitchMode(mode) | Ready | 拖拽中 no-op;其余情况(含同模式重复点击)统一 -> Playing + [SeekTo(目标), Play(mode,目标)]。SEAM 目标 = max(A, B−5s);重复点接缝检查每次都回到 B−5s 重新起播(00008 用户口径) |
| TogglePlay | Ready | Playing/AwaitingPrefill -> Paused + Pause;Paused/Stopped -> Playing + Play(mode,当前位置) |
| Tick(positionMs) | Ready | 更新展示位置(clamp);不改播放状态、不发 effect |
| PrefillStarted | Ready | NotStarted/Failed -> InProgress(0);已在 InProgress/Ready -> no-op |
| PrefillProgress(progress) | Ready | 非 Ready -> InProgress(clamp);Ready -> no-op |
| PrefillReady | Ready | prefill -> Ready;若 AwaitingPrefill -> Playing + Play(resumeMode, resumeAtMs) |
| PrefillFailed(message) | Ready | prefill -> Failed;若 AwaitingPrefill -> 降级 Playing + Play + Toast |
| ConfirmAndSave | Ready | 无论 prefill 是否就绪都保存:confirmed=true + PersistConfirm(songId, window) |
| Demote | Ready 且 confirmed | confirmed=false + PersistDemote(songId);未 confirmed -> no-op |
| Delete | Ready 且有候选 | 清空候选/confirmed、prefill=NotStarted;若在 AwaitingPrefill 则转 Paused;effects=[CancelPrefill, PersistDelete(songId)] |
| Back | 所有状态 | 不改 state,只发 NavigateBack |
| Seek(positionMs) | Ready | 更新位置(clamp)+ SeekTo(target);不自动播放 |

## 4. Effect 语义

| Effect | 第二轮执行者 | 语义 |
|---|---|---|
| Play(mode, startMs) | LoopViewModel -> PreviewPlayer.play | 按模式从 startMs 起播 |
| Pause | -> PreviewPlayer.pause | 暂停,保留位置 |
| SeekTo(ms) | -> seekToDisplayMs | 展示口径 seek |
| StartPrefill(window) | -> PcmDecoder + PreviewPlayer.seedRing | 为 window 后台解码并灌 ring;完成后派发 PrefillReady/Failed |
| CancelPrefill | -> 取消当前预填 Job | 新窗口/新候选让旧预填失效 |
| PersistConfirm(songId, window) | -> LoopAnalysisManager.confirmAndSave | 落库 confirmed=1 |
| PersistDemote(songId) | -> demoteFinal | confirmed 1->0 |
| PersistDelete(songId) | -> deleteResult | 删行 |
| Toast(text) | -> _notice / Toast | 一次性提示 |
| NavigateBack | -> onBack() | 退出页面 |
| RestartAnalysis | -> analysisManager.analyze(song) | 开始/重试分析 |

effect 顺序即执行顺序。换候选/提交窗口的序列固定为
CancelPrefill -> StartPrefill(新窗口) -> SeekTo(起点);预填完成后由 PrefillReady
单独补 Play。这样既不会多余地 Pause,也不会出现"位置在动但没有输出"。

## 5. 关键不变量

1. Ready 中 0 <= aMs < bMs <= durationMs;
2. bMs - aMs >= 500ms(与 PreviewPlayer.MIN_LOOP_LENGTH_MS 一致);
3. positionMs in [0, durationMs];
4. 有候选时 selectedCandidate in candidates.indices;无候选时为 0;
5. drag = Dragging(A, ms) 时 aMs == ms,Dragging(B, ms) 时 bMs == ms;
6. playback is AwaitingPrefill => prefill is InProgress。因此一定存在后续
   PrefillReady/PrefillFailed 把状态带回 Playing 并补发 Play;不允许"位置在动但无输出";
7. 同一模式重复 SwitchMode 不是 no-op:每次都重新 SeekTo 到模式起点并 Play;
   SEAM 起点 = max(A, B−5s);同批 SeekTo 后的 Play 必须 seek=false(一次点击只 seek 一次,00008);
8. ConfirmAndSave 不检查 prefill,预填未开始/进行中/AwaitingPrefill 都能保存;
9. 所有非法/过期/重复事件返回同一个 state 实例 + 空 effect。

## 6. 矩阵规模与覆盖清单

### 6.1 规模

- 状态夹具:11 个
  1. Loading
  2. Analyzing(DECODING, 0.25)
  3. Ready.NotStarted.Stopped
  4. Ready.PrefillInProgress.Playing
  5. Ready.PrefixPartial.Playing(PrefillState.Partial(8s,12s))
  6. Ready.PrefillReady.Playing
  7. Ready.AwaitingPrefill
  8. Ready.DraggingA
  9. Ready.DraggingB
  10. Ready.Confirmed.Paused
  11. Ready.Seam.Playing
- 事件夹具:29 个(27 种事件类型;Enter 2 个、SwitchMode 2 个;新增 PrefillPartialReady)
- 参数化矩阵:11 x 29 = 319 个用例
- 额外测试:矩阵完整性 1、全局不变量 1、bug/非法操作回归 17、effect runner 14、
  预填文案 4、前缀策略 5
- 本轮新增测试执行数:361,全部通过

### 6.2 覆盖策略

- 矩阵默认期望是 no-op 恒等;所有有语义的转换显式列在测试的 EXPECTATIONS 表里。
  如果 reducer 对某个"本应非法"的组合偷偷改了状态,用例会因 assertSame/effect 不等而红。
- 每个组合都断言:新状态(数据类全字段相等)、effect 序列(顺序 + 内容);
  no-op 组合额外断言同一 state 实例。
- 全局不变量测试对 319 个 reduce 结果统一检查第 5 节 1~6 条。

### 6.3 已报 bug 的断言

| 用例 | 断言 |
|---|---|
| issue00008_repeatedSeamSwitchAlwaysReturnsToBMinusFiveSeconds | 每次 SwitchMode(SEAM)(第一次、第二次、tick 后第三次)都产生 SeekTo(55_000ms) + Play(SEAM,55_000ms),位置回到 B−5s;任何一次都不得 seek 到 45_000ms(区段中间) |
| issue00008_pausedSeamSwitchRestartsAtBMinusFiveSeconds | 暂停后重新点接缝检查,仍从 B-5s 起,且只 seek 一次 |
| seamStartClampsToAWhenLoopShorterThanFiveSeconds | 极短循环段(A=30s,B=32s)点接缝检查,目标取 max(A,B−5s)=A |
| issue00009_selectCandidateWhilePlayingNeverPausesAndPlaysAfterPrefillReady | 播放中换候选:effects 不含 Pause,含 CancelPrefill + StartPrefill(新窗口) + SeekTo;状态 AwaitingPrefill;tick 后仍 AwaitingPrefill;PrefillReady 必然产出 Play(mode, resumeAt) |
| issue00009_commitWindowWhilePlayingNeverPausesAndPlaysAfterPrefillReady | 播放中拖 A 松手并提交:effects 不含 Pause;状态 AwaitingPrefill;PrefillReady 必然 Play |
| prefillFailureWhileAwaitingFallsBackToPlayInsteadOfStayingSilent | 预填失败也不能静默:补发 Play + Toast,状态回 Playing |
| confirmAndSaveWorksWhenPrefillIsNotReady | NotStarted / InProgress / AwaitingPrefill 三种预填状态下 ConfirmAndSave 都产出 PersistConfirm |

## 7. 与当前实现的差异(为什么当前实现会红)

### 7.1 00008:重复点接缝检查落点变成区段中间

当前 LoopViewModel.setMode(mode):

    _mode.value = mode
    if (!ensurePreview()) return
    previewPlayer.play(mode, startOfMode(mode))

PreviewPlayer.play 每次都:

    degradeRetryUsed = false
    seekRaw(startMs.coerceIn(0L, duration))
    p.play()

seekRaw 会把展示口径的 B-5s 经 LoopPosition.rawSeekUs(targetUs, window, currentRawUs)
映射到"当前轮次"的原始位置(A + k*L + (target-A))。重复点击会再次走这条映射;
当 currentRawUs 来自 sink 的 controller.positionUs(重放态)或刚好跨轮时,k 的取值
会让落点漂到区段中间,而不是 B-5s。同时 degradeRetryUsed = false 把一次性降级补环
也重置了,进一步放大"每次点击都重新 seek"的行为。

模型层修正(用户口径:重复点击必须每次都回到 B−5s):

- 每次 SwitchMode(SEAM) 都产生 SeekTo(max(A, B−5s)) + Play(SEAM, max(A, B−5s));
- effect runner 执行同批 SeekTo 后,Play 以 seek=false 调用 playFromCurrent,不再走
  rawSeekUs 的第二次映射;一次点击只 seek 一次,不 release 播放器;
- 模型从不产生"区段中间"的 seek 目标;极短循环段 B−5s < A 时取 A。

### 7.2 00009:播放中改候选/重准备缓冲后完全没有声音

当前 LoopViewModel.applyCandidate(index):

    _selectedCandidate.value = index
    applyAbFromSamples(...)
    if (!ensurePreview()) return
    previewPlayer.updateWindow(_aMs.value, _bMs.value)
    previewPlayer.play(_mode.value, startOfMode(_mode.value))
    scheduleRingPrefill()

scheduleRingPrefill() 只负责取消旧 Job、解码、seedRing;它的 finally 只把
_ringPreparing=false,不会在 seed 完成后重新触发播放。而 play() 已经让 ExoPlayer
的时钟走起来;如果这次 play() 发生在 seed 之前,sink 在等 ring,位置继续前进但
音频没有输出;seed 完成后也没有任何代码补发 play()。这正是"进度条在动,但没声音;
缓冲准备完了也没声音"。

模型层修正:

- 播放中换候选/提交窗口 -> playback = AwaitingPrefill(mode, startMs);
- effects 只声明 CancelPrefill + StartPrefill(新窗口) + SeekTo(start),不产生 Pause;
- PrefillReady 在 AwaitingPrefill 下 -> Playing + Play(mode, resumeAt);
- PrefillFailed 在 AwaitingPrefill 下 -> 降级 Play + Toast;
- 不变量 AwaitingPrefill => prefill InProgress 保证"等待"一定会结束,且结束必补 Play。

## 8. 第二轮接 UI 方案(行为保持)

前提:另一个 agent 正在改预填 UI;本轮不动 LoopViewModel.kt / LoopScreen.kt /
PreviewPlayer.kt。第二轮等其收尾后再接,避免互相覆盖。

### 8.1 要改的文件

| 文件 | 改动 |
|---|---|
| ui/loop/LoopViewModel.kt | 持有 MutableStateFlow<LoopPageState> + dispatch(event);把现有字段收敛/派生;实现 effect runner |
| ui/loop/LoopScreen.kt | 单次 collectAsState 渲染 pageState;所有回调改为 dispatch(event) |
| ui/loop/LoopPageEffectRunner.kt(新) | 可选:把 effect 执行从 VM 拆出,便于 fake 测试 |
| ui/loop/state/LoopPageState.kt | 第二轮补充异步落库结果事件(ConfirmSaveSucceeded/Failed、SavedLoaded) |
| ui/loop/LoopPageStateAdapterTest.kt(新) | 用 FakePreviewPlayer/FakeAnalysisManager 断言 effect 执行顺序 |

PreviewPlayer.kt 保持不变;模式映射放在 UI 层:

    fun PreviewMode.toPageMode(): LoopPageMode = when (this) {
        PreviewMode.FULL -> LoopPageMode.FULL
        PreviewMode.LOOP_ONLY -> LoopPageMode.LOOP_ONLY
        PreviewMode.SEAM -> LoopPageMode.SEAM
        PreviewMode.FREE -> LoopPageMode.FREE
    }
    fun LoopPageMode.toPreviewMode(): PreviewMode = ...

### 8.2 事件接线

| 当前 UI 回调 | 第二轮派发 |
|---|---|
| viewModel.setMode(m) | SwitchMode(m.toPageMode()) |
| viewModel.togglePlay() | TogglePlay |
| viewModel.stopPreview() | TogglePlay(若正在播放)+ Seek(模式起点);或新增 Stop 事件 |
| viewModel.seekToDisplayMs(ms) | Seek(ms) |
| viewModel.dragTo(handle, ms) | DragAStart/DragAMove 或 DragBStart/DragBMove |
| viewModel.commitWindow() | DragAEnd/DragBEnd + CommitWindow |
| viewModel.applyCandidate(i) | SelectCandidate(i) |
| viewModel.confirmAndSave { ... } | ConfirmAndSave |
| viewModel.demoteFinal() | Demote |
| 删除确认 | Delete |
| 返回按钮 | Back |
| 重试按钮 | Retry |
| 重分析按钮 | 新增 ManualReanalyze 事件(避免与 Failed 语义混淆) |

### 8.3 effect 执行顺序

    effects.forEach { effect ->
        when (effect) {
            is Play -> previewPlayer.play(effect.mode.toPreviewMode(), effect.startMs)
            Pause -> previewPlayer.pause()
            is SeekTo -> previewPlayer.seekToDisplayMs(effect.positionMs)
            CancelPrefill -> prefillJob?.cancel()
            is StartPrefill -> launchPrefill(effect.window)
            is PersistConfirm -> persistConfirm(effect)
            is PersistDemote -> persistDemote(effect)
            is PersistDelete -> persistDelete(effect)
            is Toast -> _notice.value = effect.text
            NavigateBack -> onBack()
            RestartAnalysis -> startAnalysis()
        }
    }

- StartPrefill 异步;其回调必须带 token(窗口/候选版本),过期结果丢弃,与现有
  prefillToken 逻辑一致;
- 每次同模式点击 reducer 都会产出一个 Play;runner 按同批 SeekTo 把它降为
  seek=false(只恢复播放),避免一次点击 seek 两次;
- **避免 Play 二次 seek**:同一批 effect 里若已有 SeekTo 到相同位置,Play 应实现为
  "从当前位置起播/仅恢复播放",不要再走 PreviewPlayer.play(mode,start) 内部的
  rawSeekUs 映射;否则 00008 的映射会被重新触发。第二轮可给 PreviewPlayer 增加
  playAt/keepPosition 参数(本轮不改 PreviewPlayer),或由 runner 在已 SeekTo 时
  调用 play-only 路径。

### 8.4 行为保持与迁移步骤

1. 影子模式:VM 同时维护旧字段和新 pageState,每个 UI 事件同时喂旧路径和新 reducer;
   Debug 日志比较旧 (mode, isPlaying, a, b, selected, confirmed, prefill) 与新 state。
   先只观察,不改变实际播放路径。
2. 读切换:LoopScreen 改为读 pageState;旧 StateFlow 保留为派生/镜像,确保回滚容易。
3. 写切换:UI 回调改为 dispatch;effect runner 接管播放/预填/落库。旧方法保留一个版本,
   用 debug flag 切换。
4. 收尾:删除旧 _mode/_aMs/_bMs/_selectedCandidate/_ringPreparing 等散落字段,
   只保留 _notice/savedAndPlayed 这类页面外状态;移除 flag 与旧路径。

### 8.5 性能注意

- 不要把 60fps 播放头插值走 reducer:播放头仍在 Compose 的 withFrameNanos 里用
  positionMs + elapsedRealtime 外推,只把 ~10Hz 的 Tick 送进状态机;
- 拖拽 DragAMove/DragBMove 是高频事件。若发现 StateFlow 每帧重组,可让 Compose 本地
  保存拖拽中的视觉值,只在 Start/End 与最终 ms 上派发事件(模型支持这种用法);
- 波形 Bitmap 预渲染、候选/进度面板的现有性能约束不变。

### 8.6 第二轮建议补充的事件

- ConfirmSaveSucceeded / ConfirmSaveFailed(message):当前 ConfirmAndSave 乐观置
  confirmed=true;落库失败需要回滚 + Toast;
- DemoteFailed(message) / DeleteFailed(message);
- SavedLoaded(songId, candidate, ...):区分"已有行加载"与"分析完成";
- ManualReanalyze:页内"重新分析"按钮。

## 9. 验证结果

    .\gradlew.bat :app:testDebugUnitTest --tests "com.zjr.hesimusic.ui.loop.LoopPageReducer*"
    .\gradlew.bat :app:compileDebugKotlin :app:testDebugUnitTest

结果:

- 第二轮改动后的 :app:compileDebugKotlin 通过;
- 新增/更新 ui.loop 测试:LoopPageReducerTest 319 + MatrixCoverage 2 + Regression 17 +
  EffectRunner 14 + PrefillUi 4 + PrefillPolicy 5 = 361 个测试全部通过;
- 全量单测 955 个:0 failed / 0 errors,BUILD SUCCESSFUL。
  并发开发期间一度受另一个 agent 的 LoopIntent.arm debug 调用点影响,已由 lead 修复;
  loop/audio、service 测试的临时失败也已随并发改动收口消失。

## 10. 结论

- 页面状态从 N 个可变字段收敛为 1 个不可变状态 + 1 个纯函数 + 1 组声明式 effect;
- 319 个状态 x 事件组合 + 361 个测试执行,把"所有状态都在预期中"变成可执行断言;
- 00008/00009 已各自变成可复现的模型断言,并在 reducer/effect runner 层给出修复语义;
- 第二轮已把状态机接进 LoopViewModel/LoopScreen,并修复 00012 的候选清单/选中项/占比遮挡
  (行内宽度竞争 + sheet/导航栏底部留白,见 §11.4)。


## 11. 第二轮接线与 00012 布局修复(2026-11-16)

### 11.1 接线后 state 与旧字段映射

| 旧字段/API | 第二轮实现 |
|---|---|
| _screen: StateFlow<LoopScreenState> | 由 pageState + peaks 派生(仅兼容;LoopScreen 直接读 pageState) |
| _mode: MutableStateFlow<PreviewMode> | pageState.Ready.mode;公开 mode 为派生流 |
| _aMs / _bMs | pageState.Ready.aMs / bMs;公开 aMs/bMs 为派生流 |
| _selectedCandidate | pageState.Ready.selectedCandidate |
| _durationMs | pageState.Ready.durationMs |
| _ringPreparing | pageState.Ready.prefill is InProgress;公开 ringPreparing 为派生流 |
| previewPlayer.isPlaying | pageState.Ready.playback is Playing/AwaitingPrefill(播放键图标);buffering/isReplaying/ringReady 仍读播放器 |
| previewPlayer.positionMs | pageState.Ready.positionMs;player 10Hz 只负责派发 Tick |
| setMode(PreviewMode) | dispatch(SwitchMode(mode.toPageMode())) |
| togglePlay() | dispatch(TogglePlay) |
| stopPreview() | dispatch(TogglePlay)(若在播/等待)+ dispatch(Seek(模式起点)) |
| seekToDisplayMs(ms) | dispatch(Seek(ms)) |
| dragTo(handleA, ms) | dispatch(DragAStart/Move、DragBStart/Move) |
| commitWindow() | dispatch(CommitWindow) |
| applyCandidate(index) | dispatch(SelectCandidate(index)) |
| confirmAndSave(onSaved) | 保存回调 + dispatch(ConfirmAndSave) |
| demoteFinal() | dispatch(Demote) |
| deleteResult() | dispatch(Delete);持久化成功后 effect runner 发 NavigateBack |
| startAnalysis() | dispatch(ManualReanalyze) |
| — | 仍保留:_notice、_peaks、prefillJob/prefillToken、anchorMs/anchorElapsedRealtime |

LoopScreen 现在只 collect 一次 pageState + peaks + song + notice;播放器观测仍单独 collect
(buffering/isReplaying/ringReady),避免整页随 10Hz position tick 重组。

### 11.2 effect runner 的顺序/幂等设计

1. reducer 对非法/重复事件返回空 effect,runner 天然不会被调用;
2. 同一批 effect 严格按列表顺序执行:CancelPrefill → StartPrefill → SeekTo → Play;
3. Play 若同批前面已有同位置 SeekTo,以 seek=false 执行;LoopViewModel 里再用
   seekAppliedForNextPlay 确认 SeekTo 是否真的应用到已 prepare 的播放器,未应用则 Play
   仍走普通 seek(避免“以为 seek 过、其实没 prepare”);
4. PreviewPlayer 新增:
   - playFromCurrent(mode):只恢复播放,不 seek;
   - applyWindowForPrefill(window):只把窗口写给音频层,不 seek/不播放;
5. 预填异步:StartPrefill 先 dispatch PrefillStarted(无 effect,安全),再后台分片;
   PrefillReady/PrefillFailed 通过 viewModelScope.launch 派发,避免在 StartPrefill 的
   effect 批次里同步重入 Play(否则 Play 可能跑到同批 SeekTo 之前)。注意 viewModelScope
   使用 Main.immediate,launch 会同步执行到第一个挂起点,所以 fast-path 里显式 yield()
   再 dispatch,保证外层 effect runner 先跑完;
6. AwaitingPrefill 的收尾:
   - PrefillReady → Playing + Play(resumeMode,resumeAtMs);
   - PrefillFailed → Playing + Play + Toast(“环缓冲准备失败，已按直通播放”);
7. 落库:PersistConfirm 成功 → onConfirmSaved(release + onSaved);PersistDemote 成功 →
   Toast;PersistDelete 成功 → Toast + NavigateBack;失败只 Toast,不导航。

### 11.3 00008 / 00009 真机应看到的行为

00008(重复点接缝检查,用户口径:每次都回 B−5s):
- 第一次点「接缝检查」:从 B−5s 起播;
- 第二次及之后点同一模式:仍然 SeekTo(B−5s) + Play,播放位置回到 B−5s;
- 每次点击只 seek 一次:runner 的 Play 以 seek=false 执行,不会二次 raw seek,也不 release 播放器;
- 极短循环段 B−5s < A 时落点取 A;
- logcat 不应出现"第二次 seek 到区段中间"的落点。

00009(播放中改候选):
- 点候选后:候选 A/B 立即更新,界面显示「正在准备环缓冲…」,播放头冻结在模式起点
  (不再出现“进度条在动但没声音”);
- 预填完成:自动从模式起点起播(Play),接缝检查/仅循环段都能听到声音;
- 预填失败:自动降级直通播放 + Toast,不会停在静默等待;
- 确认保存:无论预填是否就绪都能保存,成功后才返回并播放该歌。

### 11.4 00012:候选清单/选中项/占比被遮挡

代码层确认的“遮挡层”:

1. **不是 zIndex/Box 覆盖**:结果页没有额外 zIndex;波形 Canvas 在自己的 Box 内且有界;
   唯一覆盖页面的是按设计弹出的 ModalBottomSheet。
2. **选中项/占比**:CandidateRow 原第一行把 “A→B · ★最佳 · 占比 NN%” 放进
   Text(weight(1f), maxLines=1);选中行尾部还有 Check 图标占宽,导致行尾的“占比”被
   省略号截掉/视觉上被勾选图标挤没。这是行内宽度竞争,不是浮层。
3. **候选清单底部**:候选 sheet 的 LazyColumn 原来只有 padding(bottom=24dp),没有
   navigationBars inset,也没有有界高度;10 条候选时最后一行会落到系统导航栏/sheet
   底边下面。
4. **结果页底部**:底部按钮本来就在 verticalScroll 内容里,不是 fixed bottom bar,不会
   覆盖上方内容;但滚动容器只有 16dp 底 padding,最后一行按钮可能贴到导航栏。

修复(全部用布局约束,没有 offset/zIndex 硬凑):

- CandidateRow 拆成两行:
  - 第一行 A→B/★最佳,超长只省略时间;
  - 第二行“占比 NN%”固定在前(永远可见),score/音高/响度在后,空间不足才省略;
  - 选中 Check 图标固定在行尾,不参与第二行宽度竞争。
- 候选 sheet 的 LazyColumn:
  - Modifier.weight(1f, fill=false):1 条时贴内容,10 条时获得有界高度并滚动;
  - Modifier.navigationBarsPadding() + contentPadding(bottom=24dp):最后一行不被导航栏/
    sheet 底边盖住。
- 结果页滚动容器:padding(bottom = 16dp + WindowInsets.navigationBars 的 bottom inset),
  最后一行按钮可完整滚到可点区域。
- 删除确认不再用独立 ViewModel 的协程去 pop;改由 Delete effect 持久化成功后再
  NavigateBack,避免“先返回导致删除协程被取消”。

验证说明:当前环境不操作设备,无法附真机截图;以上是代码层布局约束与覆盖层排查结论。
建议真机按“1 条候选”和“10 条候选”各开一次 sheet,确认最后一行/选中项/占比完整可见,
并在结果页滚到底确认按钮不被导航栏遮挡。

### 11.5 本轮验证

- 新增/更新测试:LoopPageReducerTest 319、MatrixCoverage 2、Regression 17、
  EffectRunner 14、PrefillUi 4、PrefillPolicy 5,合计 361,全部通过;
- :app:compileDebugKotlin 在本轮改动后通过;
- 全量单测 955 个,0 failed / 0 errors,BUILD SUCCESSFUL。


## 12. 00015:预览预填只填前缀 + 不阻塞起播 + 进度显示

### 12.1 问题

页面预览此前直接对**整窗**用 RingChunkedSeeder 分片:269s 窗口 = 135 片,
现场一直显示「正在准备环缓冲…」约 30s,起播被整环预填阻塞。

### 12.2 页面策略(纯函数,可单测)

LoopPagePrefillPolicy:

- 窗口 <=30s:整窗填满(30s 窗口 15 片,与旧行为一致);
- 窗口 >30s:只解 [A, A+12s) = 6 片(2s/片),前缀就绪即可起播,不再等整环;
- 与 service/LoopRingPrefiller 的 maxPrefixMs=12000 对齐;页面不参与覆盖耗尽后的续播
  (audio-engine 已负责 seek 到 A+cursor 继续捕获)。

VM 的 StartPrefill:
1. 仍把**完整** A/B 窗口写给音频层(applyWindowForPrefill),保证 seedRingRange 的窗口匹配;
2. 只把**前缀窗口**交给 RingChunkedSeeder 解码;首帧相对 A 的帧号与完整 ring 对齐,
   seed 仍写完整窗口的 ring;
3. 每落一片 dispatch PrefillProgress(progress, coveredMs, totalMs);
4. 前缀窗口 complete:
   - 长窗口 -> PrefillPartialReady(totalMs,totalMs),立即允许 Play;
   - 短窗口(整窗)-> PrefillReady(旧行为)。

### 12.3 状态机

新增:

- PrefillState.Partial(coveredMs,totalMs):前缀已就绪、可起播;
- InProgress(progress,coveredMs,totalMs):进度文案用;
- LoopPageEvent.PrefillPartialReady(coveredMs,totalMs)。

语义:

- PrefillProgress:
  - InProgress:更新 progress/covered/total,coveredMs 单调不减;
  - Partial:继续推进 covered/total,但**不改变 PlaybackState、不产生 effect**;
  - Ready:no-op;
- PrefillPartialReady:
  - AwaitingPrefill -> Playing + Play(resumeMode,resumeAtMs)(前缀够用即起播);
  - 其余播放状态只把 prefill 置 Partial,不产生 Play;
  - 已经 Partial/Ready 时 no-op(避免重复 Play);
- PrefillReady 在 Partial 基础上补一个“整环完成”只改 prefill=Ready,不改播放/不重复 Play;
- PrefillFailed 在 Partial 时 no-op(前缀已可播,后续分片失败不影响);
- 因此 AwaitingPrefill => InProgress 不变量仍成立,Partial 不会和 Awaiting 共存。

### 12.4 UI 进度

- 预填 InProgress 超过 1.5s 才显示弱提示(LaunchedEffect + delay);
- 文案:prefillProgressLabel -> 「准备环缓冲 8s/12s」;total=0 时「正在准备环缓冲…」;
- 前缀就绪(PrefillState.Partial)或整环 Ready 立即隐藏;
- 接缝检查的“环缓冲未就绪”警告改用 prefillPrefixReady(Partial/Ready),不再死等
  player.ringReady(长窗口不会整环完成)。

### 12.5 验收单测

- LoopPagePrefillPolicyTest:
  - 269s 窗口 -> prefix 12s、6 片;
  - 30s 窗口 -> 整窗、15 片;
  - 20s 窗口 -> 整窗、10 片;
- LoopPagePrefillUiTest:
  - 0s/12s -> 4s/12s -> 8s/12s -> 12s/12s,超界 clamp;
  - Partial/Ready 不显示进度;
- LoopPageReducerRegressionTest:
  - AwaitingPrefill + PrefillPartialReady -> Playing + Play;
  - 之后 PrefillProgress 继续推进,PlaybackState 仍 Playing、effect 为空;
  - 非 Awaiting 的 PartialReady 不产生 Play;
- LoopPageEffectRunnerTest:
  - PrefillProgress 不产生 Play/Pause;
  - PrefillPartialReady 在 Awaiting 下产出 Play。

结果:ui.loop 361 个测试全绿;全量 955 个测试,0 failed / 0 errors。


## 13. 00015 复验:首次起播 PENDING 自锁修复

### 13.1 根因

seedRingRange 在音频 sink 未 configure 时恒返回 PENDING;而 sink 只有在播放真正开始
(renderer prepared)后才 configure。首次进入页面时如果走「等预填 → 才 Play」:

- seed 等 sink configure;
- sink 等 Play;
- Play 等 PrefillReady(seed)。

三方互等。00009 那条路径能工作,是因为换候选时播放已经在跑、sink 早已 configure。

### 13.2 修正(按播放器是否已 prepared 分流)

- LoopPagePrefillPolicy.shouldDeferStart(isPlayerPrepared) = !isPlayerPrepared;
- LoopViewModel.startPrefillInternal:
  - 播放器**未 prepared**(首次起播/换歌后):不启动 seed,只把窗口记到
    deferredPrefillWindow,立即返回;**不 dispatch PrefillStarted、不进入 AwaitingPrefill**;
  - 播放器**已 prepared**(播放中换候选/换模式):保持原流程,立即 seed;
- LoopViewModel.play 真正 prepare 并 Play 之后,取出 deferredPrefillWindow 补做预填:
  - sink configure 后 seed 成功 → 加速环回;
  - 仍 PENDING/失败 → 后台重试/只记日志,**不影响已经开始的播放**;
- ensurePreview 从“只看 isPrepared”改为“isPrepared && songId 匹配”,避免换歌后复用
  上一首的 prepared 状态;
- AnalysisDone 不再直接置 prefill=InProgress,而是 NotStarted + StartPrefill effect:
  未 prepare 时 VM 推迟,首屏不会出现没有尽头的「正在准备环缓冲…」;prepare 后
  PrefillStarted 才进入 InProgress;
- CommitWindow 在 Paused/Stopped 下也先置 NotStarted(播放中/等待预填仍 InProgress),
  避免暂停态拖动后出现无尽进度提示;
- 初始 TogglePlay 的 reducer 语义不变:Stopped -> Playing + Play,不产生 AwaitingPrefill;
- 已 prepared + 换候选仍走 AwaitingPrefill -> PrefillReady/PartialReady -> Play(00009 不回退)。

### 13.3 验收单测

- LoopPagePrefillPolicyTest:
  - shouldDeferStart(false)=true、shouldDeferStart(true)=false;
- LoopPageReducerRegressionTest:
  - togglePlayFromStoppedStartsImmediatelyWithoutAwaiting:effects 含 Play、不含 AwaitingPrefill;
  - prefillProgressWhilePlayingOnlyUpdatesProgress:进度事件只改 PrefillState,
    PlaybackState 保持 Playing、effect 为空;
- 原有 00009 用例保持:
  - 已 prepared + 换候选 -> AwaitingPrefill;
  - PrefillReady/PrefillPartialReady -> Play。

结果:ui.loop 361 个测试全绿;全量 955 个测试,0 failed / 0 errors。
