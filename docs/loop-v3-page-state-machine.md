# 分析/预览页 v3:显式状态机与状态×事件矩阵(第一轮:纯模型)

范围:只新增纯模型与测试,不改现有 UI/播放器/DB。新增文件:

- app/src/main/java/com/zjr/hesimusic/ui/loop/state/LoopPageState.kt
- app/src/test/java/com/zjr/hesimusic/ui/loop/LoopPageReducerTest.kt
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

共 25 种事件类型;矩阵里 Enter 拆成 Enter(noSaved)/Enter(hasSaved)、SwitchMode 拆成
SwitchMode(SEAM)/SwitchMode(FULL),所以矩阵事件夹具为 27 个。

| 事件 | 有效状态 | 主要语义 |
|---|---|---|
| Enter(hasSavedResult) | Loading | 无行 -> Analyzing(DECODING,0) + RestartAnalysis;有行 -> 保持 Loading 等 VM 加载后派发 AnalysisDone |
| AnalysisProgress(phase, progress) | Loading, Analyzing | -> Analyzing(phase, clamp(progress)) |
| AnalysisDone(...) | Loading, Analyzing | -> Ready;有候选时 A/B 取 top1 并 StartPrefill |
| AnalysisFailed(message) | Loading, Analyzing | -> Failed(message) |
| Retry | Failed | -> Analyzing(DECODING,0) + RestartAnalysis |
| SelectCandidate(index) | Ready | 越界 no-op;合法 -> 应用候选 A/B、prefill=InProgress、playback=AwaitingPrefill、effects=[CancelPrefill, StartPrefill(新窗口), SeekTo(模式起点)] |
| DragAStart/DragAMove/DragAEnd | Ready | 只改视觉 A;Start 要求 drag=Idle,Move/End 要求正在拖 A;End 回到 Idle,不发副作用 |
| DragBStart/DragBMove/DragBEnd | Ready | 同上,手柄 B |
| CommitWindow | Ready | 应用当前 A/B;播放中/等待预填 -> AwaitingPrefill;effects=[CancelPrefill, StartPrefill(新窗口), SeekTo(模式起点)] |
| SwitchMode(mode) | Ready | 拖拽中 no-op;同模式且正在播放/等待预填 -> no-op(00008);否则 -> Playing + [SeekTo(目标), Play(mode,目标)] |
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
7. 同一模式重复 SwitchMode 在播放/等待预填时是恒等 no-op,不产生任何 SeekTo(00008);
8. ConfirmAndSave 不检查 prefill,预填未开始/进行中/AwaitingPrefill 都能保存;
9. 所有非法/过期/重复事件返回同一个 state 实例 + 空 effect。

## 6. 矩阵规模与覆盖清单

### 6.1 规模

- 状态夹具:10 个
  1. Loading
  2. Analyzing(DECODING, 0.25)
  3. Ready.NotStarted.Stopped
  4. Ready.PrefillInProgress.Playing
  5. Ready.PrefillReady.Playing
  6. Ready.AwaitingPrefill
  7. Ready.DraggingA
  8. Ready.DraggingB
  9. Ready.Confirmed.Paused
  10. Ready.Seam.Playing
- 事件夹具:27 个(25 种事件类型;Enter 2 个、SwitchMode 2 个)
- 参数化矩阵:10 x 27 = 270 个用例
- 额外测试:矩阵完整性 1、全局不变量 1、bug/非法操作回归 12
- 本轮新增测试执行数:284,全部通过

### 6.2 覆盖策略

- 矩阵默认期望是 no-op 恒等;所有有语义的转换显式列在测试的 EXPECTATIONS 表里。
  如果 reducer 对某个"本应非法"的组合偷偷改了状态,用例会因 assertSame/effect 不等而红。
- 每个组合都断言:新状态(数据类全字段相等)、effect 序列(顺序 + 内容);
  no-op 组合额外断言同一 state 实例。
- 全局不变量测试对 270 个 reduce 结果统一检查第 5 节 1~6 条。

### 6.3 已报 bug 的断言

| 用例 | 断言 |
|---|---|
| issue00008_repeatedSeamSwitchSeeksOnlyOnceToBMinusFiveSeconds | 第一次 SwitchMode(SEAM) 只 seek 到 55_000ms(B-5s);第二次恒等 no-op、零 effect;tick 后再点仍 no-op;任何一次都不得 seek 到 45_000ms(区段中间) |
| issue00008_pausedSeamSwitchRestartsAtBMinusFiveSeconds | 暂停后重新点接缝检查,仍从 B-5s 起,且只 seek 一次 |
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

模型层修正:

- 同模式 + Playing/AwaitingPrefill -> 恒等 no-op,连 SeekTo 都不发;
- 只有真正的模式变化,或从 Paused/Stopped 重新起播,才发一次
  SeekTo(B-5s) + Play(SEAM, B-5s);
- 模型从不产生"区段中间"的 seek 目标。

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
- Play 只在 effect 列表里出现一次时执行一次;重复同模式点击在 reducer 层就是 no-op,
  runner 不会被调用;
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

- app:compileDebugKotlin 通过;
- 新增 ui.loop.LoopPageReducer* 3 个测试类:270 + 2 + 12 = 284 个测试全部通过;
- 全量单测 621 个:0 failed / 0 errors,BUILD SUCCESSFUL。

说明:并发开发期间有一次全量运行出现过 3 个 loop/audio、service 测试失败
(LoopAudioSinkTest / LoopPcmEngineTest / LoopRingPrefillerTest)。这些文件及其被测代码
在本轮开始前就已被另一个 agent 修改,不属于本轮新增模型/测试的写域;本轮未改动它们。
待并发改动稳定后重跑,621 个测试已全部通过。

## 10. 结论

- 页面状态从 N 个可变字段收敛为 1 个不可变状态 + 1 个纯函数 + 1 组声明式 effect;
- 270 个状态 x 事件组合 + 284 个测试执行,把"所有状态都在预期中"变成可执行断言;
- 00008/00009 已各自变成可复现的模型断言,并在 reducer 层给出修复语义;
- 第二轮接 UI 的路径、顺序、性能与回滚方案已明确,且不需要改 PreviewPlayer。
