# P3 UI 契约:入口 / 分析-预览页 / 播放页标记 / 智能歌单

> 依赖:①`LoopAnalysisManager`(已交付)②`LoopRuntime.window`(已交付)③`LoopAudioSink`(音频层,预览用同一机制)。
> 原则:UI 的无缝显示条件 **必须** 等于 `LoopRuntime.window != null && window.songId == 当前歌`,
> 禁止 UI 自己用 repeatMode + DB 行推断(旧版三重不一致的根因)。

## 1. 入口(SongActionDialogs)

| 菜单项 | 显示条件 | 行为 |
|---|---|---|
| 「无缝循环」 | 非 CUE 分轨(分轨置灰 + 点击提示) | 无行 → `navigate(loop/{songId})`(进页自动分析);行 confirmed=1 → 播放该歌 + `repeatMode=ONE`(无缝自动生效);行 confirmed=0 → `navigate(loop/{songId})`(进页加载临时结果,不重分析) |
| 「重新分析」 | 有行 | `navigate(loop/{songId})`(进页不重分析;页内按钮重分析) |
| 「取消最终结果」 | 行 confirmed=1 | `LoopAnalysisManager.demoteFinal(songId)` + Toast「已转为临时结果,不再无缝」 |
| 「删除分析结果」 | 有行 | 确认对话框 → `deleteResult(songId)` |

- 菜单实现放在现有 `SongActionHost` 结构里,**不要**再把播放逻辑写进菜单;菜单只发事件(回调给调用方)。
- 路由:`MainActivity` NavHost 加 `loop/{songId}`;同时把 `DebugHooks.navigate` 接上(debug 构建 E2E 用;main 里加一行,由 lead 批准)。

## 2. 分析 / 预览页(LoopScreen)

**首帧必须立刻出骨架**(曲名占位 + 进度条),任何查询/解码都异步(旧版 A4 bug)。

### 2.1 状态机

| 状态 | 显示 |
|---|---|
| Idle(无自动分析) | 骨架 + 「开始分析」按钮(仅手动重分析时出现) |
| Decoding(p)/Analyzing(p) | 单条进度条,值 = 全局进度(0→0.5→1.0,**显示层再 clamp 单调**,与 `LoopProgress` 双保险)+ 阶段文案「正在解码音频…」/「正在分析候选循环点…」 |
| Done | 波形 + A/B + 候选弹层入口 + 四模式试听 + 底部按钮组 |
| Failed | 错误文案 + 「重试」 |

进入页面:
- 有行 → 直接加载行(A/B 从行映射到当前波形)+ **后台解码补波形**(解码期网格降级,不阻塞首帧);
- 无行 → 自动 `analyze(song)`(进入前先 `MusicViewModel.pause()`,旧版 A3 要求「开始分析即暂停」);
- 单飞被拒(返回 false)→ 提示「已有分析进行中」。

### 2.2 波形与拖拽(性能硬要求)

- 波形用 `WaveformPeaks`(3000 桶)→ **预渲染到 Bitmap**(尺寸/数据变化时才重建),每帧只 `drawBitmap` + 循环区叠加 + A/B 竖线 + 播放头;
- **禁止**每帧 3000 段 drawLine(旧版 B1/卡顿根因);
- 播放头插值:VM 记 (anchorFrame, anchorElapsedMs, isPlaying),UI 以 60fps 按时间插值,position 仅作校正;
- position 状态只在 Canvas 绘制作用域读取,**不要**让整屏随 position tick 重组;
- 拖 A/B:拖动中仅更新视觉;**松手才应用**(更新预览播放器窗口 + 后续保存用值)。

### 2.3 候选与模式

- 「候选(N)」按钮 → ModalBottomSheet:序号 / A→B 时间 / score / 音高距离 / 响度比 / **占比 NN%**(占比 =(B−A)/时长;≥80% 绿、50~80% 黄、<50% 红)。
- 四模式:完整循环 / 仅循环段 / 接缝检查(B−5s 起)/ 自由。全部走 `PreviewPlayer`(§3)。
- 时间显示 mm:ss.SSS(精调到毫秒,便于 A/B 微调)。

### 2.4 底部按钮

| 按钮 | 行为 |
|---|---|
| 确认并保存 | `confirmAndSave` → 播放该歌 + repeat ONE → `popBackStack` |
| 重新分析 | `analyze(song)`(单飞保护;进行中禁用按钮) |
| 取消最终结果 | `demoteFinal`(行保留) |
| 删除分析结果 | 确认框 → `deleteResult` |
| 返回 | 恢复进入前的播放状态(§2.5) |

### 2.5 播放交接(避免旧版"退出后播放乱"的一组 bug)

- **进入页面**:`MusicViewModel.pause()`(不改变队列、不改 repeat);
- **离开页面**:`PreviewPlayer.release()` → 若刚才只是预览,恢复 `MusicViewModel.play()`(原歌原位);若刚点了「确认并保存」,则按 §2.4 播放被分析歌;
- **不自动切歌**(旧版"退出回歌"语义过复杂,取消)。

## 3. PreviewPlayer(预览与正式播放同机制)

- 独立 ExoPlayer 实例 + 同一套 `LoopAudioSink`(注入同一 `LoopAudioController` 的**独立实例**,避免与主播放互相干扰);
- API(供 VM 调):`prepare(song, window)`、`play`、`pause`、`seekToDisplayMs`、`setMode(FULL|LOOP_ONLY|SEAM|FREE, startMs)`、`updateWindow(aMs,bMs)`(松手时)、`release()`;
- 暴露 `positionMs`(展示口径)、`isPlaying`、`buffering`、`isReplaying`;
- **语义**:预览是显式行为,允许预览未确认的临时 A/B(与"临时结果不自动驱动正式无缝"不冲突);
- 进预览时主播放已暂停 → 无音频竞争。

## 4. 播放页与底部条标记(单一来源)

- `BottomPlayerBar` 细进度条与 `PlayerScreen` 全宽滑条:当 `LoopRuntime.window` 命中当前歌时,绘制 A/B 刻度(A 琥珀 / B 红)+ 循环区高亮;
- 位置/时长直接用 `MusicViewModel` 现有的 position/duration(会话层已投影为展示口径);
- **不满足条件时完全不绘制**(普通播放零改动);
- 切歌/降级/删除行 → window 变 null → 标记自动消失(同一来源,无需额外逻辑)。

## 5. 内置智能歌单「无缝循环」

- 在 `di/DatabaseModule` 的 `onOpen` 里注册(照备份那 16 行幂等 SQL):
  `INSERT/UPDATE smart_playlists ... SELECT ... FROM song_loop WHERE confirmed=1`;
- 随库展示,无需 UI 改动;若现有智能歌单视图有排序/图标逻辑,按现有机制补一条。

## 6. 文件域

| 成员 | 独占写域 |
|---|---|
| ui-a(分析/预览页) | `ui/loop/**`、`MainActivity.kt`(仅路由 + DebugHooks 接线)、`ui/library/SongActionDialogs.kt`、`ui/library/SongListScreen.kt`/`ui/main/MainScreen.kt`(仅透传参数) |
| ui-b(播放页标记/智能歌单) | `ui/main/BottomPlayerBar.kt`、`ui/player/PlayerScreen.kt`、`ui/common/MusicViewModel.kt`(仅暴露 window 流)、`di/DatabaseModule.kt`(仅智能歌单注册) |
| lead | `docs/**`、`loop/**`、`service/**`、`data/**`、`cpp/**`、`tools/**`、`spike/**`、gradle |

## 7. 验收(真机)

1. 冷启动 → 长按歌 → 菜单即出;点「无缝循环」→ **0.5s 内看到进度页骨架**(旧 A4);
2. 分析进度双阶段、百分比单调不回退(旧 A1/A2);
3. 结果页:波形完整、拖 A/B 立即响应、四模式正确、候选弹层占比不被遮挡(旧 A5/B1~B7);
4. 确认保存 → 立即播放该歌 + 单曲循环 + A/B 标记出现 + 播到 B 回 A 无缝;
5. 播放中切歌 → 新歌按状态判定(已确认 + 单曲循环 = 继续无缝,否则普通);repeat 不被改写;
6. 取消最终结果/删除 → 标记消失、退回整曲循环(≤1s);
7. 智能歌单「无缝循环」= confirmed=1 集合;
8. gfxinfo:预览播放 p90 ≤ 16ms,janky ≤ 5%。
