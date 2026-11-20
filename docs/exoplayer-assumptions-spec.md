# 我们对 ExoPlayer（androidx.media3）的"假定 spec"

> **任务 A 交付物。** 本文件只陈述一件事：**我们的代码是建立在"ExoPlayer 会这样"的哪些假定之上**。
> 每条假定都取自本仓库自己的用法 / 注释 / 测试 / 日志 / 设计文档；**本文件不判断 ExoPlayer 真实是怎么做的**，那是任务 B 的活。
>
> **取证纪律（本次已遵守）**：只读 `app/src/main/java/com/zjr/hesimusic/**`、`app/src/debug/java/com/zjr/hesimusic/**`、
> `app/src/test/java/com/zjr/hesimusic/**`、`docs/**`。**未**解压 aar、**未**下载/阅读 media3 源码、**未**看 Google 官方文档。
> 因此凡是我们 docs 里引用了 `DefaultAudioSink.java:1005`、`ExoPlayerImplInternal.java:1702` 之类行号的条目，
> 其依据**仍是我们自己的历史分析记录**（不是本次新证据），confidence 一律降档并在条目里注明"依据=我们 docs 的历史记录"。
>
> 行号以本仓库当前工作区版本为准。

## 0. 使用说明

### confidence 判据

| 等级 | 含义 |
|---|---|
| `high` | 我们代码里有**直接用法 / 硬断言**（测试断言、控制流分支、容差常数）证明我们依赖该语义 |
| `medium` | 只有**间接依赖**（防御性兜底、日志口径、容差、设计文档记录）或依赖 docs 的历史记录 |
| `low` | 只能从注释/常识推断，**无代码依据**（条目内显式写"无代码依据"） |

### 与任务 B 的对接约定

* 每条都是可判定的命题（"输入→输出""顺序""线程""边界值"之一或多项）。
* 任务 B 判定档位建议：`符合` / `不符合` / `文档未定义`（media3 未承诺该行为，即使当时观察成立）/ `版本相关`。
* 条目编号稳定（A-001…），任务 B 的结论请回填到同编号。

---

## 1. 播放器生命周期（create/build、setMediaItem、prepare、play、pause、release；IDLE/BUFFERING/READY/ENDED）

### A-001 播放器的一切创建与访问都发生在主线程
- 我们调用的 API / 依赖的行为：`ExoPlayer.Builder(context).build()`、`setRenderersFactory`、`setMediaSourceFactory`、`setAudioAttributes`、`setLoadControl`、`setMediaSource`、`setMediaItem(s)`、`prepare/play/pause/seekTo/release`，以及全部 `Player` 读方法（`currentPosition`/`playbackState`/`currentMediaItem`…）。
- 我们假定的语义：**线程**：上述调用必须在主线程（或至少创建线程的 Looper）上串行执行；跨线程访问需要投递。我们从不加锁保护 `ExoPlayer` 实例，全靠"只在主线程碰"来保证串行。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/ui/loop/PreviewPlayer.kt:77`、`app/src/main/java/com/zjr/hesimusic/di/MediaModule.kt:95`、`app/src/main/java/com/zjr/hesimusic/service/MusicService.kt:88`、`app/src/main/java/com/zjr/hesimusic/service/LoopPlaybackCoordinator.kt:65`、`app/src/main/java/com/zjr/hesimusic/service/LoopPlaybackCoordinator.kt:284`
- 我们代码中的证据：`PreviewPlayer.kt:77` —「线程:ExoPlayer 的创建/访问全部在主线程(VM 调用方与内部 ticker 都在主线程)」；`LoopPlaybackCoordinator.kt:65` —「所有 Player 操作都在主线程（Dispatchers.Main.immediate）」；`MediaModule.kt:108` 直接在 Hilt provider（由 `MusicService.onCreate` 主线程触发）里 `builder.build()`。
- confidence：`high`
- 如果假定是错的，会表现成什么 bug：跨线程改源/seek 时内部状态与调用者顺序错乱 → 随机 `IllegalStateException`、item 已换但出声仍是旧源（正是 00021 的形态）、或 `seekTo` 丢失。
- 现在能否被我们的测试/不变量捕获：**部分**。`PlaybackInvariantMonitor` 每 500ms 在主线程采样（`app/src/debug/java/com/zjr/hesimusic/debug/PlaybackInvariantMonitor.kt:150`），能捕获"投影循环但音源普通"（A-013），但不能捕获线程违规本身；无 JVM 测试。

### A-002 空 timeline 上读位置/seek/prepare 会让播放器直接进入 ENDED
- 我们调用的 API / 依赖的行为：`player.seekTo()` / `player.currentPosition` / `player.prepare()` 在 `mediaItemCount == 0` 时。
- 我们假定的语义：**输入→输出**：空 timeline + `prepare()`（或先 seek 再 prepare）→ `playbackState == STATE_ENDED`，位置冻结不再推进；必须"先有 MediaItem/Source 再 prepare"。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/ui/loop/PreviewPlayer.kt:879-904`、`app/src/main/java/com/zjr/hesimusic/ui/loop/PreviewPlayer.kt:887-896`
- 我们代码中的证据：`PreviewPlayer.kt:881-885` —「M3 播放器必须持有 MediaItem 才能 prepare。历史回归(86a6b1f):…playM3 在空 timeline 上 seek/prepare,ExoPlayer 直接进入 ENDED(真机:进页 state=4、pos 不动)」；`ensureM3MediaItem()` 因此在每次 `playM3` 前做"同一 mediaId 不重复设置"的存在性检查（`:887-904`）。
- confidence：`high`（注释记录了真机复现）
- 如果假定是错的：若 ExoPlayer 其实允许空 timeline 上 prepare 并停在 IDLE，我们的 `ensureM3MediaItem` 会在某些路径上多做一次 `setMediaItem`（多一次 flush、位置归零），用户听到"点模式 chip 就断一下"。
- 现在能否被我们的测试/不变量捕获：**无**（真机回归，未固化成自动化测试）。

### A-003 prepare() 只在 IDLE 有效；ENDED 下是 no-op
- 我们调用的 API / 依赖的行为：`player.prepare()`（在 `playbackState == STATE_ENDED` 时）。
- 我们假定的语义：**顺序/边界**：IDLE ——`prepare()`→ 开始加载；READY/BUFFERING —— `prepare()` 是幂等空操作；ENDED —— `prepare()` **不会**把状态改回 BUFFERING，也**不会**重新出帧。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/ui/loop/PreviewPlayer.kt:342-344`、`app/src/main/java/com/zjr/hesimusic/ui/loop/PreviewPlayer.kt:393-404`、`app/src/main/java/com/zjr/hesimusic/service/LoopPlaybackCoordinator.kt:759`
- 我们代码中的证据：`PreviewPlayer.kt:342-344` —「例外：播放器处于 Player.STATE_ENDED 时 prepare() 是 no-op（ExoPlayer 只在 IDLE 响应 prepare），必须**先** seek 回模式起点触发 ENDED→BUFFERING，再 prepare/play」；`LoopPlaybackCoordinator.kt:759` —「prepare() 在非 IDLE 时是 no-op」。
- confidence：`high`（Robolectric 里用真实 ExoPlayer 直接断言）
- 如果假定是错的：播完一遍后点播放无反应（位置冻结在 ENDED）；反之若 ENDED 的 prepare 有副作用，我们的"seek 再 prepare"会多一次 flush/断音。
- 现在能否被我们的测试/不变量捕获：**有**。`app/src/test/java/com/zjr/hesimusic/loop/integration/RobolectricLoopPipelineTest.kt:166-209`（`playerLeavesEndedBySeekNotPrepare`）：`player.prepare()` 后断言仍 ENDED 且 `sink.feedCount()` 不变（`:187-191`）。

### A-004 单独 play() 不能离开 ENDED
- 我们调用的 API / 依赖的行为：`player.play()`（`playbackState == STATE_ENDED`）。
- 我们假定的语义：**输入→输出**：`play()` 只改 `playWhenReady`，不改变 `playbackState`，也不重新出帧；离开 ENDED 必须靠 `seekTo`。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/ui/loop/PreviewPlayer.kt:316-326`、`app/src/test/java/com/zjr/hesimusic/loop/integration/RobolectricLoopPipelineTest.kt:193-198`
- 我们代码中的证据：`PreviewPlayer.kt:316-317` —「ENDED 必须先 seek 再 prepare:直接 prepare() 会把位置退回默认点,只调 play() 又不会自动回到 A(真机复现:state=ENDED、位置冻结)」。
- confidence：`high`
- 如果假定是错的：`playFromCurrent()` 在 ENDED 下若只做 `play()` 会"永远不开始"；反之若 `play()` 能自动重播，我们的 seek 分支会把"从头重播"变成"从 A 重播"。
- 现在能否被我们的测试/不变量捕获：**有**。`RobolectricLoopPipelineTest.kt:193-197` —「play() alone must not restart rendering」。

### A-005 seekTo() 可以离开 ENDED 并恢复加载
- 我们调用的 API / 依赖的行为：`player.seekTo(positionMs)`（ENDED 态）。
- 我们假定的语义：**顺序**：seek → 状态回到 BUFFERING/READY → 继续出帧；`seekTo` 之后再 `prepare()` 是安全的幂等操作。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/ui/loop/PreviewPlayer.kt:318-325`、`app/src/main/java/com/zjr/hesimusic/service/LoopPlaybackCoordinator.kt:832-835`
- 我们代码中的证据：`RobolectricLoopPipelineTest.kt:199-208` 断言 `seekTo(0)`+`play()` 后 `sink.feedCount()` 增加且状态离开 ENDED；`PreviewPlayer.kt:394-400`「ENDED 时只 prepare/play 不会回到 A…必须按 seek → prepare → play 的顺序」。
- confidence：`high`
- 如果假定是错的：ENDED 后点播放永远不动（需要用户手动拖进度）。
- 现在能否被我们的测试/不变量捕获：**有**（同上测试）。

### A-006 换源/换 item 不等于开始加载：IDLE 上必须显式 prepare()
- 我们调用的 API / 依赖的行为：`player.replaceMediaItem(index, item)`、`player.setMediaItems(items, index, posMs)`。
- 我们假定的语义：**顺序**：这两个调用只改"播放列表/当前项"，**不会**自动开始加载；`playbackState == IDLE` 时必须补 `prepare()` 才会加载。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/service/LoopSourceRuntime.kt:90-100`、`app/src/main/java/com/zjr/hesimusic/service/LoopPlaybackCoordinator.kt:773-777`
- 我们代码中的证据：`LoopSourceRuntime.kt:91` —「换源不会自动开始加载：replaceMediaItem 只改播放列表，IDLE 状态下必须 prepare()」；`LoopPlaybackCoordinator.kt:774` —「换源只改播放列表，**不会自己开始加载**：IDLE 上换源必须 prepare」。
- confidence：`high`
- 如果假定是错的：`/loop/enter` 进循环后 state=idle、pos=0、点播放也没声音；反之若换源自带 prepare，我们会多一次 prepare（多一次 flush）。
- 现在能否被我们的测试/不变量捕获：**有**。`app/src/test/java/com/zjr/hesimusic/service/LoopSourceRuntimeTest.kt:121-134`。

### A-007 replaceMediaItem 之后位置可能保留、也可能复位到 0 —— 因此必须容差补 seek
- 我们调用的 API / 依赖的行为：`player.replaceMediaItem(index, item)` 之后读 `player.currentPosition`，必要时 `player.seekTo(index, target)`。
- 我们假定的语义：**边界值**：替换后位置"要么保留替换前位置、要么复位到 0"二者之一（我们不知道是哪个），所以用 **50ms 容差**判断是否需要补 seek；不追求精确。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/service/LoopPlaybackCoordinator.kt:765-772`
- 我们代码中的证据：`LoopPlaybackCoordinator.kt:766-771` —「replaceMediaItem 会保留替换前的位置…源替换后 ExoPlayer 通常已经把位置复位到 0（或保留了旧位置）；只有实际位置与目标 T 相差超过 50ms 才补 seek，避免一次空 seek 带来的额外 flush」。
- confidence：`medium`（"两种都可能"本身就是不确定的自认）
- 如果假定是错的：若替换后位置总是 0 且我们从 A+offset 进入，`abs(0 - target) > 50` 会补 seek → 多一次 flush（我们注释里明确说想避免的）。
- 现在能否被我们的测试/不变量捕获：**无**（`LoopSourceItemPlanTest` 只测纯计划，不测真实位置）。

### A-008 setMediaSource(source, resetPosition = false) 会安装指定源且不复位位置
- 我们调用的 API / 依赖的行为：`ExoPlayer.setMediaSource(mediaSource, /* resetPosition= */ false)` 紧跟 `prepare()`。
- 我们假定的语义：**输入→输出**：安装后当前项即该 source；`resetPosition=false` 表示保留当前位置；随后 `prepare()` 才开始加载。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/ui/loop/PreviewPlayer.kt:810-811`
- 我们代码中的证据：`PreviewPlayer.kt:810` — `p.setMediaSource(mediaSource, /* resetPosition= */ false)`；`:811` — `p.prepare()`；A/B 变化时注释称"不重建 ExoPlayer"（`:770`）。
- confidence：`medium`
- 如果假定是错的：A/B 变化后预览从头开始（而不是按 `seekTo` 的目标），用户"拖完 A/B 听到开头"。
- 现在能否被我们的测试/不变量捕获：**无**（Robolectric 测试用的是单参 `setMediaSource(source)`）。

### A-009 release() 立即生效，之后不再收到 handleBuffer
- 我们调用的 API / 依赖的行为：`player.release()` → `AudioSink.release()`。
- 我们假定的语义：**顺序**：`release()` 返回后播放器不再向 sink 泵数据；老 sink 上迟到的回调可以丢弃。我们额外用 released/disabled 标志二次防御。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/loop/audio/LoopAudioSink.kt:60-65`、`app/src/main/java/com/zjr/hesimusic/loop/audio/LoopAudioSink.kt:170-173`、`app/src/main/java/com/zjr/hesimusic/loop/audio/LoopAudioSink.kt:879-900`、`app/src/main/java/com/zjr/hesimusic/service/MusicService.kt:457-461`
- 我们代码中的证据：`LoopAudioSink.kt:20` —「release 后置 disabled，防止收尾期被再次泵入（探针曾在 stop 后多打一个 seam）」；`:170-173` 在 handleBuffer 里 `if (released || disabled) { buffer.position(buffer.limit()); return true }`。
- confidence：`medium`（我们自己也不确定，所以加了防御）
- 如果假定是错的：release 后延迟到达的 buffer 被写进已释放的 ring/AudioTrack → 异常或噪声尾音；我们的防御会把这段数据静默吞掉（丢帧但不崩）。
- 现在能否被我们的测试/不变量捕获：**部分**。`app/src/test/java/com/zjr/hesimusic/loop/audio/LoopAudioSinkTest.kt` 有 release 后不泵的用例，但没有"release 后仍收到 handleBuffer"的专项用例。

### A-010 playbackState 的推进完全由内部加载/渲染驱动，我们只读不写
- 我们调用的 API / 依赖的行为：`player.playbackState`、`onPlaybackStateChanged`。
- 我们假定的语义：**顺序**：IDLE→BUFFERING→READY→ENDED 的迁移只由加载与渲染决定；应用侧无法把状态"写"成 READY，也不能靠 `play()` 从 IDLE 直接到 READY。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/service/MusicService.kt:294-297`、`app/src/main/java/com/zjr/hesimusic/service/LoopPlaybackCoordinator.kt:137-140`、`app/src/main/java/com/zjr/hesimusic/ui/player/state/PlayerPageShadow.kt:113-130`
- 我们代码中的证据：`PlayerPageShadow.kt:44` —「Media3 Player.STATE_* 的纯 Kotlin 映射」；`MusicViewModel.kt:425-430` 把状态映射成 `SnapshotPlaybackState`；`LoopPlaybackCoordinator.kt:137-140` 用状态变化触发重算（IDLE 直接 return）。
- confidence：`high`
- 如果假定是错的：状态机（`PlayerPageState`）与真实状态长期不一致 → 播放页显示"暂停"但实际在响（或反之）。
- 现在能否被我们的测试/不变量捕获：**有（视角有限）**。`PlayerPageShadowTest` 只验证映射函数，不验证真实播放器迁移。

---

## 2. 换源的语义（replaceMediaItem vs setMediaItems；旧 MediaSource 复用；tag/mediaId/cacheKey）

### A-011 replaceMediaItem 可能复用旧 MediaSource（只原地更新 timeline 上的 item）
- 我们调用的 API / 依赖的行为：`player.replaceMediaItem(index, newItem)`。
- 我们假定的语义：**输入→输出**：ExoPlayer 先问 `MediaSource.canUpdateMediaItem(newItem)`；为 true 时**不重建** MediaSource，只替换 timeline item。也就是说"换了 item ≠ 换了音源"。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/loop/audio/source/LoopSourceMediaItem.kt:8-20`、`app/src/main/java/com/zjr/hesimusic/service/LoopPlaybackCoordinator.kt:734-737`
- 我们代码中的证据：`LoopSourceMediaItem.kt:8-13` —「背景（真机 00021 根因）：media3 的 Player.replaceMediaItem **不会**重建 MediaSource。ExoPlayerImpl.replaceMediaItems 先问 MediaSource.canUpdateMediaItem(newItem)…同 uri 的 tag 替换会走"原地更新 item、保留旧 MediaSource"的捷径：时间线上的 item 有了 tag（UI/投影认为已进循环），但真正出声的仍是原来那个普通 MP3 源 → 进度从 A 走、声音从歌曲开头播（00021）」。
- confidence：`high`（真机根因，且我们围绕它做了工程性规避）
- 如果假定是错的：我们的 customCacheKey 规避变成无谓的多一次重建（换源处多一次 flush/断音），不会坏功能。
- 现在能否被我们的测试/不变量捕获：**部分**。`LoopAwareMediaSourceFactory.loopSourcesCreated()` 计数（`app/src/main/java/com/zjr/hesimusic/loop/audio/source/LoopAwareMediaSourceFactory.kt:158-159`）在运行期判定工厂是否真被调用；`PlaybackInvariantMonitor` 的 `projection-loop-source-plain`（`PlaybackInvariantMonitor.kt:34`、`:207-260`）正是这个 bug 的探测器；测试侧只有 `LoopAwareMediaSourceFactorySelectTest`。

### A-012 canUpdateMediaItem 的判定字段不含 tag（含 uri / imageDurationMs / customCacheKey 等）
- 我们调用的 API / 依赖的行为：`MediaItem.Builder().setCustomCacheKey(...)` + `setTag(...)`，依赖 `canUpdateMediaItem` 的字段集合。
- 我们假定的语义：**边界**：uri、imageDurationMs、customCacheKey 等参与比较；`localConfiguration.tag` **不参与**。因此"只换 tag 不换 cacheKey"必然命中原地更新捷径。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/loop/audio/source/LoopSourceMediaItem.kt:8-18`、`app/src/main/java/com/zjr/hesimusic/loop/audio/source/LoopSourceMediaItem.kt:27-28`
- 我们代码中的证据：`LoopSourceMediaItem.kt:10-12` —「ProgressiveMediaSource 只比较 uri / imageDurationMs / customCacheKey 等字段（**不看 tag**）」；`:27-28` —「只影响 media3 的 canUpdateMediaItem 判定；本应用不接缓存 DataSource，因此不会变成真的磁盘缓存键」。
- confidence：`medium`（字段清单来自历史排查，未固化成测试）
- 如果假定是错的：customCacheKey 冗余，"清 tag 即重建"的推理也不再成立（但我们同时清 cacheKey，应仍能重建）。
- 现在能否被我们的测试/不变量捕获：**无直接测试**；`LoopSourceMediaItemTest` 只测 key 字符串格式。

### A-013 customCacheKey 改变足以强制重建 MediaSource
- 我们调用的 API / 依赖的行为：`MediaItem.Builder().setCustomCacheKey("hesi-loop:<songId>:<A>:<B>")` / `setCustomCacheKey(null)`。
- 我们假定的语义：**输入→输出**：cacheKey 与旧 item 不同 → `canUpdateMediaItem` 返回 false → ExoPlayer 走 `createMediaSources` 重建 → 我们的 `LoopAwareMediaSourceFactory.createMediaSource` 被调用 → tag 真正到达工厂。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/loop/audio/source/LoopSourceMediaItem.kt:30-38`、`app/src/main/java/com/zjr/hesimusic/service/LoopPlaybackCoordinator.kt:738-742`
- 我们代码中的证据：`LoopSourceMediaItem.kt:14-17` —「修复：给循环 item 写一个与原始 item 不同的 customCacheKey，令 canUpdateMediaItem 返回 false，逼 ExoPlayer 走 createMediaSources 重建源」。
- confidence：`medium`
- 如果假定是错的：tag 永远到不了工厂 → 时间线显示在循环、出声仍是整曲（00021）；兜底见 A-014。
- 现在能否被我们的测试/不变量捕获：**有（运行期）**。`LoopPlaybackCoordinator.kt:746-764` 用 `loopSourcesCreated()` 前后差判定，差为 0 就打 `source replace shortcut detected: forcing full setMediaItems rebuild`；`PlaybackInvariantMonitor` 的 `source-plain` 在 2 次采样（≈1s）后落 `Log.e` + `/state.invariants`。

### A-014 整表 setMediaItems 一定会为每个 item 调 createMediaSource
- 我们调用的 API / 依赖的行为：`player.setMediaItems(items, index, positionMs)` + `player.prepare()`（作为"强制重建"兜底）。
- 我们假定的语义：**顺序**：`setMediaItems` 会重建全部 MediaSource（工厂对每一项被调用一次），因此 tag 必然到达工厂；换表后需要显式 `prepare()`。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/service/LoopPlaybackCoordinator.kt:746-764`
- 我们代码中的证据：`LoopPlaybackCoordinator.kt:746-749` —「如果 media3 仍然走 updateMediaSourcesWithMediaItems 捷径，tag 只会挂在 timeline item 上，工厂不会被调用（loopSources 不增）。这时用一次性整表 setMediaItems 强制 createMediaSources 重建全部源」；`:759-760` —「prepare() 在非 IDLE 时是 no-op；IDLE/刚重建时保证新源开始加载」。
- confidence：`medium`
- 如果假定是错的：兜底失效 → 00021 无法自愈；或兜底代价被放大（整表重建导致队列位置/缓冲全丢，明显断音）。
- 现在能否被我们的测试/不变量捕获：**无**（只有运行期日志与不变量）。

### A-015 replaceMediaItem 只影响目标 index，队列其它项不变
- 我们调用的 API / 依赖的行为：`player.replaceMediaItem(index, item)`；队列不变量由 `LoopSourceItemPlan.Enter.touchedIndices` 表达。
- 我们假定的语义：**边界**：替换不会移动 index、不会改 `mediaItemCount`、不会触发其它项的源重建。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/service/LoopSourceItemPlan.kt:26-36`、`app/src/main/java/com/zjr/hesimusic/service/LoopPlaybackCoordinator.kt:627-629`
- 我们代码中的证据：`LoopSourceItemPlan.kt:26` —「只替换这**一个** index（队列其它项不动）」；`LoopPlaybackCoordinator.kt:627-629` —「进入：只做**一次** replaceMediaItem(当前 index) + 一次 seek…队列其它项一根手指都不碰」。
- confidence：`high`
- 如果假定是错的：队列/首页/通知栏的其它歌全部重建成循环源 → 每首歌进循环、进度全错。
- 现在能否被我们的测试/不变量捕获：**部分**。`LoopSourceItemPlanTest` 断言 `touchedIndices == listOf(index)`（纯逻辑）；真实播放器行为无测试。

### A-016 相同队列的重设不会触发 onMediaItemTransition
- 我们调用的 API / 依赖的行为：`player.setMediaItems(sameList...)` / 队列与索引都没变时的重设。
- 我们假定的语义：**顺序**：队列内容、当前 index、当前 item 都没变时，`onMediaItemTransition` **不触发**（因此"等 transition 来重算窗口"会永远等不到）。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/service/LoopPlaybackCoordinator.kt:456-492`、`app/src/main/java/com/zjr/hesimusic/service/LoopPlaybackCoordinator.kt:113-116`
- 我们代码中的证据：`LoopPlaybackCoordinator.kt:459-463` —「真机 40169 复现：/loop/enter 先 arm 意图、再 setMediaItems，intent 那一次 refresh 里 currentMediaItem 还是 null…而队列/索引与当前完全相同，setMediaItems 不会触发 transition，于是再没有任何事件」；对策是 `scheduleIntentSettle()` 2s/200ms×10 轮轮询（`:465-492`）。
- confidence：`high`（真机复现）
- 如果假定是错的：settle 轮询冗余（多几次无副作用 refresh），功能不受影响。
- 现在能否被我们的测试/不变量捕获：**无**（真机现象）。

### A-017 mediaId 不参与 canUpdateMediaItem 判定
- 我们调用的 API / 依赖的行为：`MediaItem.mediaId`；换源时我们**保持 mediaId 不变**（只改 tag/cacheKey）。
- 我们假定的语义：**边界**：mediaId 不变/变化都不影响 `canUpdateMediaItem`；真正决定重建的是 cacheKey（见 A-012）。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/loop/audio/source/LoopSourceMediaItem.kt:34-38`、`app/src/main/java/com/zjr/hesimusic/service/LoopPlaybackCoordinator.kt:800-808`
- 我们代码中的证据：`LoopSourceMediaItem.tagged()` 只 `setTag` + `setCustomCacheKey`，**不**改 mediaId（`:34-38`）；`describeItem()` 把 `mediaId/uri/cacheKey/tag` 四个字段都打日志（`:800-808`）。
- confidence：`low` ——**无代码依据**：字段清单来自我们对 `canUpdateMediaItem` 的记忆/历史排查，代码里没有任何断言把 mediaId 排除在外。
- 如果假定是错的：`tagged()` 若因 mediaId 未变而命中捷径 → 同 A-013 的 00021 形态（靠 cacheKey 兜住）。
- 现在能否被我们的测试/不变量捕获：**无**。

---

## 3. MediaSource / MediaPeriod 契约（createPeriod 与 enable 先后、prepare 回调、seek 后 PTS、buffered 口径）

### A-018 createPeriod 可能先于 enable 被调用
- 我们调用的 API / 依赖的行为：`MediaSource.createPeriod(id, allocator, startPositionUs)` / `enable(caller)`。
- 我们假定的语义：**顺序**：真实时序可能是 createPeriod → enable（特别是被 `MaskingMediaSource` 包裹时）；因此 `createPeriod` **不得**因为 `enabled == false` 抛异常，且其实现不得依赖 enable 已完成。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/loop/audio/source/LoopingPcmMediaSource.kt:91-100`、`app/src/test/java/com/zjr/hesimusic/loop/integration/RobolectricLoopPipelineTest.kt:398-459`
- 我们代码中的证据：`LoopingPcmMediaSource.kt:97-99` —「不因 enable 时序直接抛：某些路径可能先 createPeriod 后 enable；记日志即可，createPeriod 本身不依赖 enabled 状态」；`RobolectricLoopPipelineTest.kt:427-428` —「real ExoPlayer ordering with MaskingMediaSource is createPeriod before enable」并断言 `recorder.createSawEnabled == false`。
- confidence：`high`（真实 ExoPlayer + Robolectric 直接断言该顺序）
- 如果假定是错的：我们的宽松实现仍然正确，只是 `createPeriod before enable; continue` 日志不再出现。
- 现在能否被我们的测试/不变量捕获：**有**。`RobolectricLoopPipelineTest.createPeriodBeforeEnableIsToleratedByLoopingSource`（`:400-459`）同时验证"宽松实现能播"与"严格实现会报错"。

### A-019 prepareSource 后必须 onSourceInfoRefreshed 才能出可用 timeline
- 我们调用的 API / 依赖的行为：`MediaSource.prepareSource(caller, transferListener, playerId)` → `caller.onSourceInfoRefreshed(source, timeline)`。
- 我们假定的语义：**顺序**：`prepareSource` 返回时 timeline 还没"定稿"，必须回调刷新；"同步立刻刷新"是允许的（不要求异步）。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/loop/audio/source/LoopingPcmMediaSource.kt:65-79`、`app/src/main/java/com/zjr/hesimusic/loop/audio/source/LoopingPcmMediaSource.kt:136`
- 我们代码中的证据：`LoopingPcmMediaSource.kt:78` — `caller.onSourceInfoRefreshed(this, sourceTimeline)` 在 prepareSource 内同步调用；`:136` `getInitialTimeline()` 返回同一 timeline 占位。
- confidence：`medium`
- 如果假定是错的：同步刷新被当作"回调期间重入"而丢事件 → 播放器永远 BUFFERING（正是 6.9 节形态）。
- 现在能否被我们的测试/不变量捕获：**部分**。`RobolectricLoopPipelineTest` 全用例路径经过它；`LoopingPcmMediaSourceLifecycleTest` 覆盖生命周期。

### A-020 未知时长 + 动态 + 可 seek 的 Timeline 不会被钳制位置
- 我们调用的 API / 依赖的行为：`SinglePeriodTimeline(periodDurationUs = C.TIME_UNSET, windowDurationUs = C.TIME_UNSET, windowPositionInPeriodUs = 0, windowDefaultStartPositionUs = 0, isSeekable = true, isDynamic = true, useLiveConfiguration = false, manifest = null, mediaItem = item)`。
- 我们假定的语义：**边界**：`durationUs = TIME_UNSET` 时播放器**不**把位置钳到 `duration - 1`；`isDynamic = true` + `useLiveConfiguration = false` 不引入直播追帧逻辑；`isSeekable = true` 让任意 `seekTo(T)` 被接受并落到 `MediaPeriod.seekToUs`。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/loop/audio/source/LoopingPcmMediaSource.kt:52-63`、`docs/loop-data-source-design.md:187-197`
- 我们代码中的证据：`LoopingPcmMediaSource.kt:23-25` —「Timeline 是单窗口、未知时长、动态、可 seek 的无限时间轴：durationUs = C.TIME_UNSET，播放器不钳制位置；只要 SampleStream 不返回 EOS，播放就不会结束」；`LoopingPcmMediaSource.kt:52` —「isSeekable=true 让 Player 接受 seek 并落到 MediaPeriod」。
- confidence：`medium`（依据是我们 docs 的历史分析：`docs/loop-data-source-design.md:187-193` 引用 `Timeline.java:1246-1251`，本次未复核）
- 如果假定是错的：无限循环播到"某个时长"后被钳位 → 播到 ENDED 或 seek 被 clamp 到末尾（"循环突然结束/拖不动"）。
- 现在能否被我们的测试/不变量捕获：**有（弱）**。`LoopingPcmMediaSourceTest:22-26` 断言 window.durationUs == `C.TIME_UNSET`；`RobolectricLoopPipelineTest` 跑真实 ExoPlayer 连续三圈未 ENDED（`:101-162`）。

### A-021 只要 SampleStream 不返回 EOS，播放永不进入 ENDED
- 我们调用的 API / 依赖的行为：`SampleStream.readData` 永不返回 `C.BUFFER_FLAG_END_OF_STREAM`；`Extractor` 永不返回 `RESULT_END_OF_INPUT`。
- 我们假定的语义：**输入→输出**：EOS 唯一来源是数据源；数据源不给 EOS → 播放器不会自动 ENDED（即使 `getNextLoadPositionUs` 返回过 END_OF_SOURCE）。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/loop/audio/source/LoopingPcmSampleStream.kt:36-72`、`app/src/main/java/com/zjr/hesimusic/loop/audio/source/LoopingPcmExtractor.kt:70-137`、`app/src/main/java/com/zjr/hesimusic/loop/audio/source/RingBandExtractor.kt:75-198`
- 我们代码中的证据：`LoopingPcmSampleStream.kt:54` 只设 `C.BUFFER_FLAG_KEY_FRAME`，从不设 EOS；`LoopingPcmExtractor.kt:136` / `RingBandExtractor.kt:197` 恒返回 `Extractor.RESULT_CONTINUE`。
- confidence：`high`（Robolectric 用真实 ExoPlayer 连续播三圈）
- 如果假定是错的：循环在第一次"预取满"后就 ENDED。
- 现在能否被我们的测试/不变量捕获：**有**。`RobolectricLoopPipelineTest.ringBandPlaysThreeLapsWithContinuousPts`（`:101-162`）。

### A-022 getNextLoadPositionUs() 返回 C.TIME_END_OF_SOURCE 不会让播放器 ENDED，且播放推进会重新请求加载
- 我们调用的 API / 依赖的行为：`MediaPeriod.getNextLoadPositionUs()`。
- 我们假定的语义：**边界**：返回 `TIME_END_OF_SOURCE` 只表示"暂时没有下一段要加载"；`continueLoading` 仍可能被再次调用；不会因此触发 EOS。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/loop/audio/source/LoopingPcmMediaPeriod.kt:269-273`、`docs/loop-data-source-design.md:202-203`、`docs/loop-data-source-design.md:907-908`
- 我们代码中的证据：`LoopingPcmMediaPeriod.kt:272` — `return next ?: C.TIME_END_OF_SOURCE`；`docs/loop-data-source-design.md:202-203` —「getNextLoadPositionUs() 在"当前块+预取就绪"时返回 END_OF_SOURCE，播放推进到下一块时再变回有限值；需要 E2E 确认 ExoPlayer 不会因此进入 ENDED（原型只做 JVM）」。
- confidence：`medium`（**我们 docs 自己写明"需要 E2E 确认"**）
- 如果假定是错的：缓冲窗口填满那一刻播放结束（听感=每轮 6s 就停一次）。
- 现在能否被我们的测试/不变量捕获：**部分**。`RobolectricLoopPipelineTest.bufferedPositionAdvancesWithReadyBlocks` 断言了返回值（`:328-332`），但没有断言"之后还会重新加载"。

### A-023 getBufferedPositionUs() 的返回值是 LoadControl 起播门槛的判据，必须给"连续可读末尾"
- 我们调用的 API / 依赖的行为：`MediaPeriod.getBufferedPositionUs()` 配合 `DefaultLoadControl.setBufferDurationsMs(...)`。
- 我们假定的语义：**输入→输出**：`buffered - playbackPosition` 必须 ≥ `bufferForPlaybackMs` 才会从 BUFFERING 进 READY；`getBufferedPositionUs` 返回"连续 READY 块覆盖到的末尾"（而不是"已解码的任意块末尾"）。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/loop/audio/source/LoopingPcmMediaPeriod.kt:263-267`、`app/src/main/java/com/zjr/hesimusic/di/MediaModule.kt:100-107`、`app/src/main/java/com/zjr/hesimusic/ui/loop/PreviewPlayer.kt:611-619`
- 我们代码中的证据：`docs/loop-data-source-design.md:583-599` —「media3 DefaultLoadControl 默认 bufferForPlaybackMs=2500；首块 1s ready 后…缓冲只有 1s，达不到 2.5s 门槛」→ 修复为 `min=4000/max=12000/playback=1500/rebuffer=2500`（`MediaModule.kt:104`）。
- confidence：`high`（真机踩坑记录 + 我们主动改 LoadControl）
- 如果假定是错的：无限 BUFFERING（首块已 ready 但 buffered==position，门槛恒不满足）——真机第 3/4 轮就是这个。
- 现在能否被我们的测试/不变量捕获：**有**。`RobolectricLoopPipelineTest.bufferedPositionAdvancesWithReadyBlocks`（`:264-334`）；`LoopingPcmMediaPeriodPrepareTest`（`:78-81`）断言 `>= 3s`。

### A-024 continueLoading 大约每 10ms 被调用一次（因此必须自己去重）
- 我们调用的 API / 依赖的行为：`MediaPeriod.continueLoading(loadingInfo)`。
- 我们假定的语义：**调用节奏**：加载循环高频（~10ms）重试；同一个"等待中的块"会被反复请求，我们的实现必须幂等去重（不能每次提交解码任务、不能递增 generation）。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/loop/audio/source/LoopingPcmMediaPeriod.kt:356-363`、`app/src/main/java/com/zjr/hesimusic/loop/audio/source/LoopingPcmMediaPeriod.kt:334-353`、`docs/loop-data-source-design.md:553`
- 我们代码中的证据：`LoopingPcmMediaPeriod.kt:356-358` —「去重：同一时刻只允许一个 worker 在跑。continueLoading 会被 ExoPlayer 每 ~10ms 调一次；这里不能递增 generation，也不能重复提交任务」；`:349` —「否则会每 10ms 空转一次」。
- confidence：`high`（真机日志：`requestDecode ... gen=418/419` 每 10ms 一条）
- 如果假定是错的：去重只是保守，不会坏；但如果实际是"每次都必须推进否则不重试"，我们的 `return false` 会让加载停住。
- 现在能否被我们的测试/不变量捕获：**有**。`LoopingPcmMediaPeriodPrepareTest.kt:88-118` 断言 provider 只被调用 1 次、generation==0、dedupeSkipCount>=100。

### A-025 onPrepared 之后要主动踢一次 onContinueLoadingRequested，否则加载可能停住
- 我们调用的 API / 依赖的行为：`MediaPeriod.Callback.onPrepared(period)` / `onContinueLoadingRequested(source)`。
- 我们假定的语义：**顺序**：某些路径上 `onPrepared` 之后播放器不会自己再请求加载，需要数据源主动回调一次。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/loop/audio/source/LoopingPcmMediaPeriod.kt:414-422`、`app/src/main/java/com/zjr/hesimusic/loop/audio/source/LoopingPcmMediaPeriod.kt:132-139`、`docs/loop-data-source-design.md:619-620`
- 我们代码中的证据：`LoopingPcmMediaPeriod.kt:418-420` —「有的路径 onPrepared 后 loader 要等下一次回调才继续；主动踢一次，避免 prepared 后不再 continueLoading」。
- confidence：`medium`
- 如果假定是错的：我们多一次 continueLoading（幂等，无副作用）。
- 现在能否被我们的测试/不变量捕获：**无**（`LoopingPcmMediaPeriodPrepareTest` 的 `onContinueLoadingRequested` 是空实现 `:53`）。

### A-026 prepare(callback, positionUs) 传入的 positionUs 是"本次准备的起点"，必须据此 seek
- 我们调用的 API / 依赖的行为：`MediaPeriod.prepare(callback, positionUs)`。
- 我们假定的语义：**输入→输出**：`positionUs` 是期望的起始位置（不是恒 0）；准备阶段就应按它定位；后续 `seekToUs` 可能再来一次。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/loop/audio/source/LoopingPcmMediaPeriod.kt:123-145`
- 我们代码中的证据：`LoopingPcmMediaPeriod.kt:124`（构造函数里 `core.seekToTimelineUs(startPositionUs)`）+ `:141`（`core.seekToTimelineUs(positionUs)`）。
- confidence：`high`（但测试都传 0，无法单独证伪）
- 如果假定是错的：从非 0 位置 prepare（restore 后）会先出 0 附近的数据 → 起播瞬间"跳一下"。
- 现在能否被我们的测试/不变量捕获：**无**（测试全部用 `positionUs = 0`）。

### A-027 seekToUs 的返回值会被播放器当作"实际落点"采用
- 我们调用的 API / 依赖的行为：`MediaPeriod.seekToUs(positionUs)` 返回 Long。
- 我们假定的语义：**输入→输出**：返回值 = 实际 seek 落点；我们返回 core 计算后的目标（可能与入参不同，例如负数归 0）。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/loop/audio/source/LoopingPcmMediaPeriod.kt:251-258`、`app/src/main/java/com/zjr/hesimusic/loop/audio/source/LoopingPcmMediaPeriod.kt:260-261`
- 我们代码中的证据：`LoopingPcmMediaPeriod.kt:257` — `return target`；`getAdjustedSeekPositionUs` 直接返回入参（`:260-261`）。
- confidence：`medium`
- 如果假定是错的：我们返回值无害；但若播放器用它做位置外推，返回值偏差会让进度条错位几 ms。
- 现在能否被我们的测试/不变量捕获：**无**。

### A-028 discardBuffer 可以是 no-op
- 我们调用的 API / 依赖的行为：`MediaPeriod.discardBuffer(positionUs, toKeyframe)`。
- 我们假定的语义：**边界**：播放器允许我们"没有独立样本队列"，seek 已重定位 cursor，因此无需搬数据；不实现丢弃不会导致内存膨胀或错位数据。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/loop/audio/source/LoopingPcmMediaPeriod.kt:245-247`
- 我们代码中的证据：`LoopingPcmMediaPeriod.kt:246` —「本实现没有独立的数据队列：seek 已把 cursor 指向新位置；这里无需搬数据」。
- confidence：`medium`
- 如果假定是错的：seek 后可能吐出旧位置的样本 → 听感"seek 后先放一小段旧内容"。
- 现在能否被我们的测试/不变量捕获：**无**。

### A-029 readDiscontinuity() 恒返回 C.TIME_UNSET 表示"没有断点"
- 我们调用的 API / 依赖的行为：`MediaPeriod.readDiscontinuity()`。
- 我们假定的语义：**输入→输出**：`TIME_UNSET` = 本 period 自上次 seek 以来时间戳连续；循环折返（B→A）**不是** discontinuity，不应上报。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/loop/audio/source/LoopingPcmMediaPeriod.kt:249`、`docs/loop-data-source-design.md:138`
- 我们代码中的证据：`LoopingPcmMediaPeriod.kt:249` — `override fun readDiscontinuity(): Long = C.TIME_UNSET`；docs:138 —「**永远 C.TIME_UNSET**（循环不是 discontinuity）」。
- confidence：`high`
- 如果假定是错的：seek 后渲染器可能继续用旧 epoch 喂 sink → sink 报 `UnexpectedDiscontinuityException`。
- 现在能否被我们的测试/不变量捕获：**无**。

### A-030 selectTracks 的 streams 数组按 **renderer 索引**，音频流不一定在 0 号槽
- 我们调用的 API / 依赖的行为：`MediaPeriod.selectTracks(selections, mayRetainStreamFlags, streams, streamResetFlags, positionUs)`。
- 我们假定的语义：**边界**：数组下标是 renderer 槽位（默认 renderers 下 video=0、audio=1）；"第一个非空 selection"就是应挂流的槽位；其它槽位置空。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/loop/audio/source/LoopingPcmMediaPeriod.kt:215-243`、`app/src/main/java/com/zjr/hesimusic/loop/audio/source/LoopingPcmMediaPeriod.kt:467-488`
- 我们代码中的证据：`LoopingPcmMediaPeriod.kt:222-223` —「注意：数组按 renderer 索引，不保证 audio 在 0（默认 renderers 中 video=0, audio=1）。只把唯一的音频流挂到第一个非空 selection 上，其余清空」。
- confidence：`high`
- 如果假定是错的：流挂错槽 → 无声（renderer 无输入）但状态 READY（正是 6.12「有 onPrepared 无 load」的形态）。
- 现在能否被我们的测试/不变量捕获：**有**。`LoopTrackAssignmentTest.kt`（2 槽只在 index=1 放 selection）；`LoopingPcmMediaPeriodTrackTest`（见 `docs/loop-data-source-design.md:622-624`）。

### A-031 selectTracks 返回 positionUs 表示"未做位置调整"
- 我们调用的 API / 依赖的行为：`selectTracks(...)` 的返回值。
- 我们假定的语义：**边界**：返回入参 `positionUs` 等价于"不做额外偏移"。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/loop/audio/source/LoopingPcmMediaPeriod.kt:242`
- 我们代码中的证据：`LoopingPcmMediaPeriod.kt:242` — `return positionUs`。
- confidence：`medium`
- 如果假定是错的：播放器会把位置钳到 0，每次选轨后回到开头。
- 现在能否被我们的测试/不变量捕获：**无**。

### A-032 准备期错误通过 maybeThrowPrepareError() 上抛（而不是无限 BUFFERING）
- 我们调用的 API / 依赖的行为：`MediaPeriod.maybeThrowPrepareError()`。
- 我们假定的语义：**时序**：播放器在 prepare 之后会周期性调用它；在此抛 `IOException` 会让播放器进入错误态（`onPlayerError`），而不是永远 BUFFERING。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/loop/audio/source/LoopingPcmMediaPeriod.kt:200-203`、`app/src/main/java/com/zjr/hesimusic/loop/audio/source/LoopingPcmMediaPeriod.kt:147-169`
- 我们代码中的证据：`LoopingPcmMediaPeriod.kt:156-158` —「让 ExoPlayer 在下次 maybeThrowPrepareError 时拿到错误，而不是无限 BUFFERING」；2s 超时置 `prepareError`（`:151-154`）。
- confidence：`high`（真机踩坑后的主动设计）
- 如果假定是错的：首块永远解不出来时播放器永远 BUFFERING（"点了没反应"，日志只有 decode 超时）。
- 现在能否被我们的测试/不变量捕获：**有**。`LoopingPcmMediaPeriodPrepareTest.kt:121-152`。

### A-033 releasePeriod / releaseSource 的调用时机允许我们在其中释放解码器
- 我们调用的 API / 依赖的行为：`MediaSource.releasePeriod(period)`、`releaseSource(caller)`。
- 我们假定的语义：**顺序**：播放器弃用 period 时一定先 `releasePeriod`，不会在 release 后继续 read；`releaseSource` 时所有 caller 都已解绑（`callers.isEmpty()` 才整体释放）。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/loop/audio/source/LoopingPcmMediaSource.kt:118-134`、`app/src/main/java/com/zjr/hesimusic/loop/audio/source/LoopingPcmMediaPeriod.kt:306-316`
- 我们代码中的证据：`LoopingPcmMediaSource.kt:129-133` — `if (callers.isEmpty()) { released = true; periods.forEach { it.release() } }`；`LoopingPcmMediaPeriod.release()` 里 `worker.shutdownNow()` + `core.release()`（`:314-315`）。
- confidence：`medium`
- 如果假定是错的：release 后还有线程在 decode → `RejectedExecutionException`（有 catch，`:440-447`）或解码器被提前关闭 → 偶发 play 报错。
- 现在能否被我们的测试/不变量捕获：**部分**。`RingBandSourceReleaseTest`；`LoopingPcmMediaSourceLifecycleTest`。

---

## 4. Extractor 契约（read 的 CONTINUE/END_OF_INPUT、sampleData/metadata、SeekMap）

### A-034 read() 返回 RESULT_CONTINUE 且"没有进展"时，加载线程会立刻重试 → 必须自己节流/等待
- 我们调用的 API / 依赖的行为：`Extractor.read(input, seekPosition)` → `Extractor.RESULT_CONTINUE`。
- 我们假定的语义：**调用节奏**：返回 CONTINUE 表示"还没读到东西，继续调我"；加载器不会因此睡眠，所以**数据源必须自己阻塞/睡眠或快速产出**，否则 100% CPU 空转（连续 CONTINUE 是合法的）。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/loop/audio/source/RingBandExtractor.kt:84-106`、`app/src/main/java/com/zjr/hesimusic/loop/audio/source/RingBandExtractor.kt:130-142`、`app/src/main/java/com/zjr/hesimusic/loop/audio/source/LoopingPcmExtractor.kt:89-95`、`app/src/main/java/com/zjr/hesimusic/loop/audio/source/RingBandExtractor.kt:263`
- 我们代码中的证据：`RingBandExtractor.kt:132` —「高水位后 media3 会立刻重试；必须在这里等新 append，不能空转」；`awaitFilled` 里 `ring.awaitRenderedAtLeast(target, READ_WAIT_MS = 8ms)`（`:211-227`）；节流分支 `sleeper(THROTTLE_SLEEP_MS = 4ms)` 后 `return RESULT_CONTINUE`（`:100-105`）。
- confidence：`high`（真机 + Robolectric 双重证据）
- 如果假定是错的：我们的 8ms 等待会额外拉长起播延迟（累计）。
- 现在能否被我们的测试/不变量捕获：**有**。`RobolectricLoopPipelineTest.noBusySpinWhenExtractorHasNoData`（`:214-249`，300ms 内 read ≤100 次）；`RingBandExtractorTest.kt:65`。

### A-035 永不返回 RESULT_END_OF_INPUT 就等于"无限长容器"
- 我们调用的 API / 依赖的行为：`Extractor.read` 恒返回 `RESULT_CONTINUE`；`DataSource.read` 恒返回 `C.RESULT_END_OF_INPUT`（但 Extractor 从不调用它）。
- 我们假定的语义：**行为组合**：Extractor 不返回 END_OF_INPUT 时，`ProgressiveMediaPeriod` 不会把 period 判为"加载完成/EOS"，因此可以无限播。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/loop/audio/source/LoopingPcmExtractor.kt:70-137`、`app/src/main/java/com/zjr/hesimusic/loop/audio/source/RingBandExtractor.kt:75-198`、`app/src/main/java/com/zjr/hesimusic/loop/audio/source/LoopingPcmExtractorMediaSource.kt:33-34`
- 我们代码中的证据：`LoopingPcmExtractorMediaSource.kt:17-18` —「虚拟容器 DataSource：Extractor 完全不读取真实字节，所有 PCM 来自块缓存。保留 unknown length，让 ProgressiveMediaSource 按动态/未知时长处理」。
- confidence：`high`
- 如果假定是错的：`IllegalStateException` / 加载失败 → 无限 BUFFERING 或播放错误。
- 现在能否被我们的测试/不变量捕获：**有**（Robolectric 三圈播放 + `LoopingPcmExtractorTest`）。

### A-036 sniff() 返回 true 就会被 ExtractorsFactory 采用
- 我们调用的 API / 依赖的行为：`Extractor.sniff(input): Boolean`。
- 我们假定的语义：**输入→输出**：`sniff` 恒 true 即表示"本容器格式由我处理"，不需要真的读字节（`ExtractorInput` 是空的）。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/loop/audio/source/LoopingPcmExtractor.kt:58`、`app/src/main/java/com/zjr/hesimusic/loop/audio/source/RingBandExtractor.kt:66`
- 我们代码中的证据：两处 `override fun sniff(input: ExtractorInput): Boolean = true`；`LoopingPcmExtractorMediaSource.kt:100-102` 用 `ExtractorsFactory { arrayOf(...) }` 只给一个候选。
- confidence：`high`
- 如果假定是错的：sniff 时若要求读取字节会拿到 EOF → 可能判定"不匹配"→ 无 extractor → 播放错误。
- 现在能否被我们的测试/不变量捕获：**有**（Robolectric 全链路）。

### A-037 init() 的必需动作顺序：track(0, AUDIO) → format() → seekMap() → endTracks()
- 我们调用的 API / 依赖的行为：`ExtractorOutput.track(id, type)`、`TrackOutput.format(format)`、`ExtractorOutput.seekMap(map)`、`ExtractorOutput.endTracks()`。
- 我们假定的语义：**顺序**：`init` 里一次性建一条音轨、发格式、给 SeekMap、`endTracks()` 表示"没有更多轨道"；之后只通过 `TrackOutput` 输出样本。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/loop/audio/source/LoopingPcmExtractor.kt:60-68`、`app/src/main/java/com/zjr/hesimusic/loop/audio/source/RingBandExtractor.kt:68-73`
- 我们代码中的证据：`LoopingPcmExtractor.kt:61-66`；`RingBandExtractor.kt:69-71`。
- confidence：`high`
- 如果假定是错的：轨道选择器看不到音频轨 → 无 `selectTracks` / 无声，或 SeekMap 缺失导致不可 seek。
- 现在能否被我们的测试/不变量捕获：**有**。`LoopingPcmExtractorTest`（fake `ExtractorOutput` 记录 track/endTracks/seekMap，`:25-37`）；`RingBandExtractorTest.kt:22-50`。

### A-038 sampleData + sampleMetadata 的语义：size 必须等于实际写入字节数
- 我们调用的 API / 依赖的行为：`TrackOutput.sampleData(data, length)`、`TrackOutput.sampleMetadata(timeUs, flags, size, offset, cryptoData)`。
- 我们假定的语义：**输入→输出**：`size` 必须与实际写入字节数一致（不能用请求长度冒充）；`offset = 0`、`cryptoData = null`；`flags = C.BUFFER_FLAG_KEY_FRAME`；`timeUs` 即渲染器最终看到的 `presentationTimeUs`（不额外加偏移）。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/loop/audio/source/LoopingPcmExtractor.kt:107-110`、`app/src/main/java/com/zjr/hesimusic/loop/audio/source/RingBandExtractor.kt:143-146`
- 我们代码中的证据：`LoopingPcmExtractor.kt:99-106` —「绝不把短数据/未填满数组当有效样本…不匹配返回 CONTINUE，不推进输出」；`docs/loop-data-source-design.md:849-851` —「sampleData/sampleMetadata 永远用实际字节数，绝不 0 填充到请求长度」。
- confidence：`high`（专门加了防线与测试）
- 如果假定是错的：播放器按 metadata 的 size 读 sampleData，而我们给错 → 读出越界/错位 PCM → 爆音/静音。
- 现在能否被我们的测试/不变量捕获：**有**。`LoopingPcmExtractorTest.kt:93`；`RingBandSizeGuardTest`。

### A-039 SeekMap.getDurationUs() = C.TIME_UNSET 与 isSeekable() = true 可共存
- 我们调用的 API / 依赖的行为：`SeekMap`。
- 我们假定的语义：**边界**：时长未知不影响"可 seek"；`player.duration` 会是 `TIME_UNSET`（负数），而 `seekTo` 仍被接受。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/loop/audio/source/LoopingPcmExtractor.kt:49-56`、`app/src/main/java/com/zjr/hesimusic/loop/audio/source/RingBandExtractor.kt:57-64`、`app/src/main/java/com/zjr/hesimusic/service/LoopSessionPlayer.kt:102-114`
- 我们代码中的证据：`LoopSessionPlayer.kt:107` —「SOURCE 播放器原生 duration 是 TIME_UNSET；对外仍返回整曲时长」。
- confidence：`high`
- 如果假定是错的：SOURCE 模式下进度条拖不动（`seekTo` 无效）。
- 现在能否被我们的测试/不变量捕获：**部分**。`LoopSessionPlayerTest.duration_projectedToFullSongWhenWindowActive`（`:173`）只验证投影；`LoopingPcmExtractorTest` 的 seek 用例（`:140`）验证数据源层。

### A-040 getSeekPoints(positionUs) 返回 (p, p) 等价于"PCM 无关键帧、精确落点"
- 我们调用的 API / 依赖的行为：`SeekMap.getSeekPoints(positionUs): SeekPoints`。
- 我们假定的语义：**输入→输出**：起点和终点给同一时间，播放器就不会做"前一个关键帧对齐"的额外调整；我们随后在 `Extractor.seek` 里自己按帧取整。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/loop/audio/source/LoopingPcmExtractor.kt:52-55`、`app/src/main/java/com/zjr/hesimusic/loop/audio/source/RingBandExtractor.kt:60-63`
- 我们代码中的证据：`SeekMap.SeekPoints(SeekPoint(p, p))`，其中 `p = positionUs.coerceAtLeast(0L)`。
- confidence：`medium`
- 如果假定是错的：seek 被对齐到别的点 → 落点偏移十几毫秒（我们 docs 记录过 `seek 到 A 会落到 A+15ms`，见 `docs/loop-v3-service-design.md:171`）。
- 现在能否被我们的测试/不变量捕获：**无直接测试**（Robolectric 的 seek 用例只断言"不报错、位置大致对"，`:386-395`）。

### A-041 Extractor.seek(position, timeUs) 里 position 是字节偏移、timeUs 是目标时间；我们只用 timeUs
- 我们调用的 API / 依赖的行为：`Extractor.seek(position: Long, timeUs: Long)`。
- 我们假定的语义：**输入→输出**：`position` 对我们无意义（虚拟容器），`timeUs` 是播放器想要的时间点；我们按 `timeUs` 重定位并忽略 `position`。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/loop/audio/source/LoopingPcmExtractor.kt:139-145`、`app/src/main/java/com/zjr/hesimusic/loop/audio/source/RingBandExtractor.kt:229-239`
- 我们代码中的证据：`RingBandExtractor.kt:230-234` 只用 `timeUs` 算 offset，`position` 仅打日志；`LoopingPcmExtractor.kt:140` — `core.seekToTimelineUs(timeUs)`。
- confidence：`medium`
- 如果假定是错的：seek 全部落到错误位置。
- 现在能否被我们的测试/不变量捕获：**有（部分）**。`LoopingPcmExtractorTest.kt:140`；Robolectric `seekMapsToOffsetModLoopLength` 的 5a 直接调 `extractor.seek(0L, seekUs)` 断言 offset（`:351-361`）。

### A-042 空 DataSource（read 恒 END_OF_INPUT、open 返回 LENGTH_UNSET）不会让 ProgressiveMediaSource 提前 EOS
- 我们调用的 API / 依赖的行为：`BaseDataSource.read(...)`、`open(dataSpec)` 返回值。
- 我们假定的语义：**输入→输出**：`open` 返回 `C.LENGTH_UNSET` 表示未知长度；`read` 恒 `RESULT_END_OF_INPUT`，但因为 Extractor 从不调用 `read`，这个 EOF 不会被 `ProgressiveMediaPeriod` 观察到。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/loop/audio/source/LoopingPcmExtractorMediaSource.kt:21-48`
- 我们代码中的证据：`LoopingPcmExtractorMediaSource.kt:30`（`return C.LENGTH_UNSET.toLong()`）+ `:33-34`（`read` 恒 `RESULT_END_OF_INPUT`）。
- confidence：`medium`（依赖"Extractor 不读 input"这个组合事实）
- 如果假定是错的：会立刻拿到 EOF → 加载完成 → 循环只播很短就停。
- 现在能否被我们的测试/不变量捕获：**有**（Robolectric 全链路；`LoopingPcmExtractorTest` 的 fake input 也是空实现）。

### A-043 Extractor.read 在加载线程执行，允许阻塞/睡眠（最长 ~8ms 级）
- 我们调用的 API / 依赖的行为：`Thread.sleep` / `awaitFilled` 在 `read` 内、等待渲染器填充。
- 我们假定的语义：**线程**：`read` 不在音频线程、不在主线程，因此阻塞不会卡渲染/UI。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/loop/audio/source/RingBandExtractor.kt:211-227`、`app/src/main/java/com/zjr/hesimusic/loop/audio/source/RingBandExtractor.kt:263`
- 我们代码中的证据：`RingBandExtractor.kt:263` — `READ_WAIT_MS = 8L`；`awaitFilled` 失败只打日志并返回 false（`:215-225`）。
- confidence：`medium`
- 如果假定是错的：主线程卡顿 → ANR；或音频欠载。
- 现在能否被我们的测试/不变量捕获：**无**（测试注入同步 sleeper/假时钟）。

---

## 5. AudioSink 契约（configure / handleBuffer / play / pause / flush / reset / release 时序；play 权威；format 变化时机）

### A-044 setOutputStreamOffsetUs() 可能在 configure() **之前**被调用；configure() 不得重置它
- 我们调用的 API / 依赖的行为：`AudioSink.setOutputStreamOffsetUs(offsetUs)` / `configure(format, bufferSize, channels)`。
- 我们假定的语义：**顺序**：offset 先到、configure 后到是合法顺序；offset 一旦设置就跨 configure 保留（我们据此把"引擎媒体时间"和"delegate 时间域"分开）。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/loop/audio/LoopAudioSink.kt:27-28`、`app/src/main/java/com/zjr/hesimusic/loop/audio/LoopAudioSink.kt:149-151`、`app/src/main/java/com/zjr/hesimusic/loop/audio/LoopAudioSink.kt:822-825`
- 我们代码中的证据：`LoopAudioSink.kt:149-151` —「注意：不要在这里重置 outputStreamOffsetUs。ExoPlayer 会在 configure 之前先调 setOutputStreamOffsetUs（探针实测 1e12），重置会把 sink 时间轴打回媒体时间，导致 PTS 与 A/B 不同域、捕获永不命中」。
- confidence：`high`（真机探针；且有测试）
- 如果假定是错的：我们保留 offset 的行为无害；但"configure 不重置"这条就没有存在必要。
- 现在能否被我们的测试/不变量捕获：**有**。`LoopAudioSinkTest.kt:702`（offset 在 configure 之前写入时捕获仍按媒体时间命中 B 并重放）；`:750`（offset 在流中途变化时不串味）。

### A-045 handleBuffer 的 presentationTimeUs 在 "output offset 域"（= 媒体时间 + offset）
- 我们调用的 API / 依赖的行为：`AudioSink.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)`。
- 我们假定的语义：**输入→输出**：`mediaPts = presentationTimeUs - outputStreamOffsetUs`；我们写回 delegate 时必须再 `+ outputStreamOffsetUs`（等价于"同一个域"）。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/loop/audio/LoopAudioSink.kt:219-220`、`app/src/main/java/com/zjr/hesimusic/loop/audio/LoopAudioSink.kt:205-206`、`app/src/main/java/com/zjr/hesimusic/loop/audio/source/SourceRenderersFactory.kt:17-23`
- 我们代码中的证据：`LoopAudioSink.kt:25-27` —「delegate：handleBuffer PTS = 引擎媒体时间 + 当前 outputStreamOffsetUs；controller.positionUs：媒体时间（getCurrentPositionUs 已归一化）」。
- confidence：`high`
- 如果假定是错的：我们会把 PTS 整体减/加 1e12 → sink 侧时间轴彻底错乱（`UnexpectedDiscontinuity`，正是 00020/00021 时期的现象）。
- 现在能否被我们的测试/不变量捕获：**有**。`LoopAudioSinkTest.kt:533`（`getCurrentPositionUs 写入 controller 并扣除输出偏移`）；`:702`。

### A-046 handleBuffer 返回 false = 没吃完；同一个 ByteBuffer 会被再次传入
- 我们调用的 API / 依赖的行为：`handleBuffer` 返回值。
- 我们假定的语义：**顺序**：`false` 表示"请再给我一次机会"，renderer 会用**同一个** buffer 实例（position 已推进到我们消费的位置）重试；因此我们必须缓存 pending 且复用同一实例，不能复制/丢弃。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/loop/audio/LoopAudioSink.kt:199-206`、`app/src/main/java/com/zjr/hesimusic/loop/audio/LoopAudioSink.kt:329-336`、`app/src/main/java/com/zjr/hesimusic/loop/audio/LoopAudioSink.kt:1057-1130`
- 我们代码中的证据：`LoopAudioSink.kt:199` —「背压重试：delegate 没吃完的输入必须用同一个 ByteBuffer 继续」；`:202-205` 用 `pending.buffer === buffer` 判同一实例；`:254` —「否则返回 false 让 renderer 重试（重试即再次泵），解码器不会被喂到 EOS」。
- confidence：`high`
- 如果假定是错的：我们的 `pendingInput` 会永远等不到匹配 → 该段数据丢失且返回 false 死循环 → 卡顿/静音。
- 现在能否被我们的测试/不变量捕获：**有**。`LoopAudioSinkTest.kt:319`（多 buffer 背压时复用同一个 ByteBuffer 实例）；`:337`（重放背压时复用同一个 direct LE ByteBuffer）。

### A-047 consumed == true 时 buffer.position() 已推进到 limit()
- 我们调用的 API / 依赖的行为：`handleBuffer` 返回值 + `ByteBuffer.position()`。
- 我们假定的语义：**边界**：返回 true ⇒ 整段被接收（position 到 limit）；返回 false ⇒ position 只推进到实际接收处。我们据此统计"真正交给 delegate 的帧数"（不变量 00020）。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/loop/audio/source/SourceRenderersFactory.kt:153-165`
- 我们代码中的证据：`SourceRenderersFactory.kt:154-156` —「输出侧不变量（00020）：统计真正交给 delegate 的帧数。consumed=true 表示整段被接收（media3 契约：buffer.position 已推进到 limit）；否则按实际推进量计」。
- confidence：`medium`（我们自己在 A-048 里承认 delegate 不保证移动 position，两条互相张力）
- 如果假定是错的：帧计数偏低/偏高，`paused-but-sink-wrote` 不变量误报或漏报。
- 现在能否被我们的测试/不变量捕获：**有**。`app/src/test/java/com/zjr/hesimusic/loop/audio/source/TapAudioSinkOutputFramesTest.kt`；`OutputInvariantTest`。

### A-048 DefaultAudioSink.handleBuffer 不保证移动 ByteBuffer.position
- 我们调用的 API / 依赖的行为：`ForwardingAudioSink.handleBuffer` 调用 delegate 后读 `buffer.position()`。
- 我们假定的语义：**边界**：`before/after/delta == 0` **不能**证明"没被消费"；因此 tap 必须在调用 delegate **之前**自己拷贝数据，不能等 `consumed`/`after > before`。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/loop/audio/source/SourceRenderersFactory.kt:116-151`、`app/src/main/java/com/zjr/hesimusic/loop/audio/source/SourceRenderersFactory.kt:153-165`、`docs/loop-data-source-design.md:673-674`
- 我们代码中的证据：`SourceRenderersFactory.kt:20-22` —「tap 必须在 delegate 之前写：DefaultAudioSink 会异步消费，consumed=false 时 position 也不一定前移，等 consumed 会漏掉大部分数据」；`docs:673` —「DefaultAudioSink.handleBuffer **不保证移动 ByteBuffer.position**；before/after/delta=0 不能证明没消费」。
- confidence：`high`（真机 tap 只写进 10% 数据的实证）
- 如果假定是错的：我们只是多做了一次拷贝（CPU 开销），功能不缺。
- 现在能否被我们的测试/不变量捕获：**有**。`app/src/test/java/com/zjr/hesimusic/loop/audio/source/TapAudioSinkTest.kt:46-69`（delegate 报 not consumed 时仍写满 100 帧）。

### A-049 空 buffer（remaining() < bytesPerFrame）也会被送进 handleBuffer
- 我们调用的 API / 依赖的行为：`handleBuffer` 的参数 buffer 可能无有效帧。
- 我们假定的语义：**边界**：空 buffer 是合法输入；若我们在重放态返回 false，renderer 会反复重试同一个空 buffer（空转），因此必须"消费掉并返回 true"。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/loop/audio/LoopAudioSink.kt:208-217`
- 我们代码中的证据：`LoopAudioSink.kt:210` —「空 buffer 在重放态也要消费掉，避免 renderer 因 false 反复重试空 buffer」。
- confidence：`medium`
- 如果假定是错的：我们的分支不触发，无副作用。
- 现在能否被我们的测试/不变量捕获：**无**。

### A-050 play/pause 的权威完全在 ExoPlayer（renderer），sink 不得自行 play()
- 我们调用的 API / 依赖的行为：`AudioSink.play()` / `AudioSink.pause()` / `handleBuffer`。
- 我们假定的语义：**时序**：`play()` 只由 renderer 在启动时调用；sink 在 `handleBuffer` 里**绝对不能**调 `play()`，否则暂停会被悄悄撤销。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/loop/audio/source/SourceRenderersFactory.kt:17-30`、`app/src/main/java/com/zjr/hesimusic/loop/audio/source/SourceRenderersFactory.kt:187-195`、`app/src/test/java/com/zjr/hesimusic/loop/audio/source/TapAudioSinkTest.kt:79-137`
- 我们代码中的证据：`SourceRenderersFactory.kt:24-29` —「**不变量（issue 00020 回归）：本类绝不自己调用 play。**…它在 pause() 后会被重新触发：播放器已暂停…AudioTrack 却被重新 start，于是 ExoPlayer 每次 doSomeWork 喂进来的那一小段 PCM 就变成"~96ms 出声 + ~0.9s 静音"的循环…播放/暂停的唯一所有者是 ExoPlayer，sink 只允许被动响应」。
- confidence：`high`（真机可听 bug + 回归测试）
- 如果假定是错的：我们会永久无声（我们已明确放弃这个兜底）。
- 现在能否被我们的测试/不变量捕获：**有**。`TapAudioSinkTest.kt:79-137`；`docs/loop-data-source-design.md:677-680` 记录了当年那个"临时兜底"被移除。

### A-051 playToEndOfStream() 会在重放期被高频（~100Hz）反复调用，是"泵"的驱动源
- 我们调用的 API / 依赖的行为：`AudioSink.playToEndOfStream()`。
- 我们假定的语义：**调用节奏**：源端 EOS 之后 renderer 会以约 100Hz 轮询该方法（直到 `isEnded()` 为 true）；我们在循环态借此持续把 ring 喂给 delegate，并且不转发给 delegate。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/loop/audio/LoopAudioSink.kt:718-756`、`app/src/main/java/com/zjr/hesimusic/loop/audio/LoopAudioSink.kt:1057`、`app/src/main/java/com/zjr/hesimusic/loop/audio/LoopAudioSink.kt:1256`、`docs/loop-v3-m3-design.md:94`
- 我们代码中的证据：`LoopAudioSink.kt:1057` —「renderer 的 ~100Hz 轮询驱动的泵」；`:1256` —「预分配背压上限：约 2s（renderer ~100Hz 轮询）」；`docs/loop-v3-m3-design.md:94` —「renderer 会以 ~100Hz 反复回调它,即为泵」；`:751` —「关键：循环态只泵，不调用 delegate 同名方法（delegate 永不进入 ended）」。
- confidence：`medium`（频率数字来自我们 docs 的探针记录，代码里只有"约 2s = N 次"的间接常数）
- 如果假定是错的：重放会欠载/停住，所有"靠 playToEndOfStream 泵"的逻辑失效（我们在 `getCurrentPositionUs` 里有兜底，见 A-053）。
- 现在能否被我们的测试/不变量捕获：**有（部分）**。`LoopAudioSinkTest.kt:397`（循环态 playToEndOfStream 不转发 delegate、isEnded 恒 false）；`:848`（getCurrentPositionUs 在重放态也会驱动泵）。

### A-052 循环态 isEnded() 必须返回 false，且不能把 playToEndOfStream 转发给 delegate
- 我们调用的 API / 依赖的行为：`AudioSink.isEnded()` / `playToEndOfStream()`。
- 我们假定的语义：**输入→输出**：只要 `isEnded()` 返回 false，renderer 就不会把流判为播放结束（因此不进 ENDED）；delegate 永不 ended 才能继续接受我们的重放帧。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/loop/audio/LoopAudioSink.kt:758-771`、`app/src/main/java/com/zjr/hesimusic/loop/audio/LoopAudioSink.kt:751-753`
- 我们代码中的证据：`LoopAudioSink.kt:764-767` — `if (loopEngaged()) { pump(); return false }`；`:21` —「重放期不调用 delegate.playToEndOfStream() / 不进入 ended 态（AudioTrack 不 stop）」。
- confidence：`high`
- 如果假定是错的：循环会在 B 处结束（听感=每轮播完就停）。
- 现在能否被我们的测试/不变量捕获：**有**。`LoopAudioSinkTest.kt:397`。

### A-053 getCurrentPositionUs() 是位置权威（AudioTrack playhead），可能返回 CURRENT_POSITION_NOT_SET
- 我们调用的 API / 依赖的行为：`AudioSink.getCurrentPositionUs(forceDiscontinuityPosition)`。
- 我们假定的语义：**输入→输出**：返回 delegate 的原生播放位置（含 offset 域），我们做域归一化后写入 controller；返回 `CURRENT_POSITION_NOT_SET` 时位置未知，必须回落到自己的帧游标；该方法会被渲染器高频调用。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/loop/audio/LoopAudioSink.kt:773-798`、`app/src/main/java/com/zjr/hesimusic/loop/audio/LoopAudioSink.kt:800-808`、`app/src/main/java/com/zjr/hesimusic/loop/audio/LoopAudioController.kt:131-143`
- 我们代码中的证据：`LoopAudioSink.kt:783-795`（`raw != CURRENT_POSITION_NOT_SET` 分支 → `controller.updatePositionUs(media)`；否则重放态回落 `engine.currentPositionUs()`）；`:774` —「EOS 兜底：若 renderer 不再调 playToEndOfStream/isEnded，位置查询仍可驱动泵」。
- confidence：`high`
- 如果假定是错的：会话层位置会跳变（重放期冻结在 B），进度条卡住或冲高。
- 现在能否被我们的测试/不变量捕获：**有**。`LoopAudioSinkTest.kt:533`；`:848`。

### A-054 每次 seek 都会 flush() 一次 sink（因此跨 seek 不比较 PTS）
- 我们调用的 API / 依赖的行为：`AudioSink.flush()`（由 renderer 在 `onPositionReset` 调）。
- 我们假定的语义：**顺序**：seek → `stopRenderers` → BUFFERING → `mediaPeriod.seekToUs` → `discardBuffer` → `resetRendererPosition` → `audioSink.flush()`；flush 后 sink 以新 PTS 重新锚定，所以跨 seek 的 PTS 不要求单调。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/loop/audio/LoopAudioSink.kt:827-856`、`docs/loop-data-source-design.md:285-308`、`docs/loop-data-source-design.md:903`
- 我们代码中的证据：`LoopAudioSink.flush()` 清 pending/bias/`lastDelegatePtsUs = Long.MIN_VALUE`（`:828-853`）；`docs:304` —「因此**任何用户 seek 都会 flush 一次 AudioTrack，产生一次设备相关的短空洞**。数据源无法消除它」；`docs:275` —「ExoPlayer 在 seek 时会 flush AudioSink，所以跨 seek 不比较 PTS」。
- confidence：`high`（我们把它写进验收口径）
- 如果假定是错的：我们清掉 bias/pending 会丢一小段重放数据 → seek 后有短暂静音。
- 现在能否被我们的测试/不变量捕获：**有（部分）**。`LoopAudioSinkTest.kt:414`（flush 清重放态但保留 ring）；`:898`（flush 后输入 PTS 落在区间内部时从该相位继续）。

### A-055 configure() 会在每次 item/窗口边界再次被调用（且伴随 offset 变化 + discontinuity）
- 我们调用的 API / 依赖的行为：`AudioSink.configure` / `setOutputStreamOffsetUs` / `handleDiscontinuity`。
- 我们假定的语义：**时序**：切歌或时间线窗口切换会重新 configure（重新建立 AudioTrack），并在边界处补一小段静音（我们记录为 ~27.8ms）；因此"边界"必然可听，不能靠 sink 层消除。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/loop/audio/LoopAudioSink.kt:140-163`、`docs/loop-v3-final-report.md:15-17`、`docs/loop-v3-service-design.md:5`
- 我们代码中的证据：`docs/loop-v3-final-report.md:16` —「每个窗口/项边界都会触发 configure() + output_stream_offset + handleDiscontinuity()，AudioSink 据此**补 27.8ms 静音**」；`LoopAudioSink.configure()` 里重置 configured/pending 并同步窗口（`:140-163`）。
- confidence：`medium`（27.8ms 是探针实测，未固化为测试；且我们已放弃"靠边界实现无缝"的方案）
- 如果假定是错的：我们对 M3′ 设计前提（"不能走时间线切换实现无缝"）的判断失效，但那只是路线选择问题。
- 现在能否被我们的测试/不变量捕获：**无**（真机探针数据）。

### A-056 handleDiscontinuity() 可能**不会**在 seek 之后被调用
- 我们调用的 API / 依赖的行为：`AudioSink.handleDiscontinuity()`。
- 我们假定的语义：**时序**：seek 后 renderer 不保证通知 sink"时间戳不连续"；因此我们必须自己用"输入 PTS 大跳变"来识别 discontinuity，并主动把 delegate 的基线重置。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/loop/audio/LoopAudioSink.kt:222-239`、`app/src/main/java/com/zjr/hesimusic/loop/audio/LoopAudioSink.kt:814-820`、`app/src/main/java/com/zjr/hesimusic/loop/audio/LoopAudioSink.kt:1174-1180`
- 我们代码中的证据：`LoopAudioSink.kt:227-228` —「ExoPlayer 偶尔不在 seek 后调 handleDiscontinuity；输入 PTS 大跳变等价于时间戳不连续，主动重置 delegate 基线，否则后续重放 PTS 会被拒收」。
- confidence：`high`（真机）
- 如果假定是错的：我们只是多一次 `super.handleDiscontinuity()`，可能让 delegate 重新锚定（多一次软静音）。
- 现在能否被我们的测试/不变量捕获：**有**。`LoopAudioSinkTest.kt:936`（discontinuity 未伴随 flush 时也按输入 PTS 重定位）；`:1153`（输入 PTS 大跳变主动重置 delegate 时间戳基线）。

### A-057 reset() 之后 sink 需要重新 configure() 才能继续工作
- 我们调用的 API / 依赖的行为：`AudioSink.reset()`。
- 我们假定的语义：**顺序**：reset 是"释放底层 AudioTrack、回到未配置"；之后不会再来 `handleBuffer`，除非再次 `configure`。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/loop/audio/LoopAudioSink.kt:858-877`
- 我们代码中的证据：`LoopAudioSink.reset()` 清空全部 pending/bias 并把 `seamState = FALLBACK`；`docs/loop-v3-m3-design.md:101` —「flush() / reset() / release()：置 PASS_THROUGH、清 pending 与游标(ring 保留…),转发 delegate」。
- confidence：`medium`
- 如果假定是错的：我们不做任何事，无副作用。
- 现在能否被我们的测试/不变量捕获：**无**（`LoopAudioSinkTest` 覆盖 flush，未覆盖 reset 后的配置流程）。

### A-058 release() 是 sink 生命周期的最后一个回调
- 我们调用的 API / 依赖的行为：`AudioSink.release()`。
- 我们假定的语义：**顺序**：release 之后不再有 `configure/handleBuffer/flush`，我们的清理（打点、关 tap 文件、回填 WAV 头）可以在这里做最后一次。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/loop/audio/LoopAudioSink.kt:879-900`、`app/src/main/java/com/zjr/hesimusic/loop/audio/source/SourceRenderersFactory.kt:214-217`、`app/src/main/java/com/zjr/hesimusic/loop/audio/LoopAudioController.kt:258-261`
- 我们代码中的证据：`LoopAudioSink.release()` 置 `released/disabled` 并 `engine.onRelease()`（`:880-898`）；`TapAudioSink.release()` 让 controller 关 tap（`SourceRenderersFactory.kt:214-217`）。
- confidence：`medium`
- 如果假定是错的：靠 A-009 的 `released/disabled` 防御兜住（但 tap 文件已关，会丢尾部取证）。
- 现在能否被我们的测试/不变量捕获：**部分**（`LoopAudioSinkTest.kt:1037` 的 tap 侧车用例覆盖 end 事件）。

---

## 6. 渲染器 / 时钟（position 由谁驱动、renderer 从哪个状态开始消费、audio renderer 的 start/stop 条件）

### A-059 播放位置由 renderer/AudioTrack 驱动，而不是由我们的数据源驱动
- 我们调用的 API / 依赖的行为：`Player.getCurrentPosition()`、`AudioSink.getCurrentPositionUs()`。
- 我们假定的语义：**线程/来源**：`player.currentPosition` 是"渲染进度"的反映；在 M3′ 重放期，播放器位置会继续按输入时间前进甚至"冻结在 B"，真实听感位置只能从 sink 拿（`audio.positionUs`），因此位置必须做一层投影。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/service/LoopSessionProjection.kt:36-37`、`app/src/main/java/com/zjr/hesimusic/service/LoopSessionPlayer.kt:61-71`、`app/src/main/java/com/zjr/hesimusic/service/LoopSessionPlayer.kt:197-202`、`docs/loop-v3-service-design.md:16`
- 我们代码中的证据：`LoopSessionProjection.kt:34-37` —「重放期用 sink 推进的位置（此时播放器自身位置冻结在 B）；非重放期用播放器位置（ms → µs）」；`docs/loop-v3-service-design.md:16` —「前置事实(P0 实测):引擎侧 sink 位置持续前进,但 **ExoPlayer 对外的 currentPosition/duration 被 clip 钳住**」。
- confidence：`high`
- 如果假定是错的：我们的双口径投影仍幂等（`displayUs` 对已在区间内的值不变），只是多走一个分支。
- 现在能否被我们的测试/不变量捕获：**有**。`LoopSessionPlayerTest.raw_replayPhaseUsesSinkPositionPlainPhaseUsesPlayerPosition`（`:185`）。

### A-060 renderer 只在 play() 之后才向 sink 喂数据；暂停后停止喂
- 我们调用的 API / 依赖的行为：`Player.play()/pause()` → renderer 启动/停止 → `AudioSink.handleBuffer` 是否被调用。
- 我们假定的语义：**时序**：`playWhenReady=false` 时 sink 不应再收到 `handleBuffer`（最多收尾少量在途数据）；因此"sink 仍在写帧"可以作为"有人越权启动了播放"的强信号。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/core/invariants/OutputInvariantEvaluator.kt`（`paused-but-sink-wrote` 判定）、`app/src/debug/java/com/zjr/hesimusic/debug/PlaybackInvariantMonitor.kt:165-195`、`docs/loop-v3-m3-design.md:143`
- 我们代码中的证据：`PlaybackInvariantMonitor.kt:32-34` —「paused-but-sink-wrote：暂停主播放后 sink 仍写帧（00020 的"0.x 播 + 0.x 停"）」；`docs/loop-v3-m3-design.md:143` —「暂停 / 恢复 | delegate pause;泵停止(playToEndOfStream 不再被调用)」。
- confidence：`high`（这条不变量本身就是我们基于该假定写的探测器）
- 如果假定是错的：不变量会误报"paused-but-sink-wrote"。
- 现在能否被我们的测试/不变量捕获：**有**。`app/src/test/java/com/zjr/hesimusic/core/invariants/OutputInvariantTest.kt` + 运行期 `PlaybackInvariantMonitor`（每 500ms 采样）。

### A-061 audio/raw PCM 走 renderer 的 bypass 分支，不创建 MediaCodec
- 我们调用的 API / 依赖的行为：`MediaCodecAudioRenderer` 对 `audio/raw` 的处理（我们只提供 `Format`）。
- 我们假定的语义：**输入→输出**：只要 Format 是 `MimeTypes.AUDIO_RAW` + `pcmEncoding=16BIT` + 正确 `sampleRate/channelCount`，renderer 就直接把 buffer 交给 `AudioSink`（`supportsFormat` 判定），不初始化解码器；因此我们的 PTS 不会被解码器重写。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/loop/audio/source/LoopingPcmMediaPeriod.kt:76-83`、`app/src/main/java/com/zjr/hesimusic/loop/audio/source/LoopingPcmExtractor.kt:40-47`、`docs/loop-data-source-design.md:18-20`、`docs/loop-data-source-design.md:108-110`
- 我们代码中的证据：`docs/loop-data-source-design.md:18-20` —「走 PCM 源（audio/raw）…MediaCodecAudioRenderer 对 PCM16 走 bypass，不创建 MediaCodec」；`docs:108-110` —「MediaCodecAudioRenderer.shouldUseBypass() 返回 audioSink.supportsFormat(format)；PCM16 的 audio/raw 被 DefaultAudioSink 支持，因此不创建解码器」。
- confidence：`medium`（依据是我们 docs 的历史源码分析，本次未复核）
- 如果假定是错的：Format 需要更多字段（`maxInputSize` 等），且 PTS 可能被解码器改写 → 环回相位错。
- 现在能否被我们的测试/不变量捕获：**部分**。`AnalyticsListener.onAudioInputFormatChanged` 只打日志（`PreviewPlayer.kt:636-646`），没有断言"无 MediaCodec"。

### A-062 ClippingConfiguration 会让 sink 侧媒体时间整体偏移（outputStreamOffsetUs = 1e12）
- 我们调用的 API / 依赖的行为：`MediaItem.ClippingConfiguration`。
- 我们假定的语义：**输入→输出**：一旦给 item 加 clip，ExoPlayer 会先 `setOutputStreamOffsetUs(1e12)` 再 `configure`，导致 sink 看到的 PTS = 1e12 + 真实媒体位置；同时 `player.currentPosition` 被 clip 钳住。这条直接决定了我们**放弃裁剪**。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/ui/loop/PreviewPlayer.kt:865-877`、`app/src/main/java/com/zjr/hesimusic/loop/audio/LoopAudioSink.kt:27-28`、`docs/loop-v3-final-report.md:39`
- 我们代码中的证据：`PreviewPlayer.kt:866-872` —「**不使用 MediaItem.ClippingConfiguration**:实测 media3 1.8 的 clipped source 会把 ExoPlayer 传给 AudioSink 的 outputStreamOffsetUs 变成 -1e12,于是 sink 侧媒体 PTS = 1e12 + 真实位置 → 引擎永远命中不了 [A,B)…(真机 logcat 实证: enginePosUs=1000008363999)」。
- confidence：`high`（真机 logcat 数值）
- 如果假定是错的：我们的 offset 归一化无害，但"放弃裁剪"的理由消失（我们仍选择不裁剪，因为环回不需要 EOS）。
- 现在能否被我们的测试/不变量捕获：**有（部分）**。`LoopAudioSinkTest.kt:702`（offset 在 configure 前写入仍能命中 B）。

### A-063 renderer 只有在 SampleStream.isReady() 为 true 时才消费；否则播放器停留在 BUFFERING
- 我们调用的 API / 依赖的行为：`SampleStream.isReady()` → renderer `readData` 被调用的时机。
- 我们假定的语义：**输入→输出**：`isReady()` 为 false 时 renderer 不读、播放器不前进（BUFFERING）；`readData` 返回 `C.RESULT_NOTHING_READ` 时也会让渲染器下一轮再来。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/loop/audio/source/LoopingPcmSampleStream.kt:30`、`app/src/main/java/com/zjr/hesimusic/loop/audio/source/LoopingPcmSampleStream.kt:50`、`app/src/main/java/com/zjr/hesimusic/loop/audio/source/LoopingPcmSampleStream.kt:71`
- 我们代码中的证据：`LoopingPcmSampleStream.kt:30` — `override fun isReady(): Boolean = core.isCurrentBlockReady()`；`:50` — 读不到就 `return C.RESULT_NOTHING_READ`。
- confidence：`high`
- 如果假定是错的：我们的 `readData` 返回 NOTHING_READ，渲染器空转（占 CPU），不会错数据。
- 现在能否被我们的测试/不变量捕获：**有**。`LoopingPcmSampleStreamTest.kt:45-74`（先 `assertFalse(isReady())` 再 pump）；Robolectric `noBusySpin`（`:237`：player must stay buffering）。

---

## 7. 会话层（MediaController 线程亲和、MediaSessionService 绑定时机、跨会话状态回传延迟）

### A-064 MediaController 的所有 Player 访问都必须投递到主线程
- 我们调用的 API / 依赖的行为：`MediaController`（`androidx.media3.session`）的读写。
- 我们假定的语义：**线程**：MediaController 不保证任意线程安全；我们一律 `mainHandler.post`，并对每次调用设超时（读 1.5s / 写 2.5s）。
- 依赖位置：`app/src/debug/java/com/zjr/hesimusic/debug/DebugPlayerConnection.kt:61-62`、`app/src/debug/java/com/zjr/hesimusic/debug/DebugPlayerConnection.kt:82`、`app/src/debug/java/com/zjr/hesimusic/debug/DebugPlayerConnection.kt:426-477`、`app/src/main/java/com/zjr/hesimusic/ui/common/MusicViewModel.kt:182-226`
- 我们代码中的证据：`DebugPlayerConnection.kt:61-62` —「已连接时所有 Player 访问都投递到主线程,读 1.5s / 写 2.5s 超时,超时用最近缓存兜底(只读)或 503(控制),不会把 server 线程拖死」。
- confidence：`high`
- 如果假定是错的：我们只是多一次 post（延迟），无功能损失。
- 现在能否被我们的测试/不变量捕获：**无**（debug 协议类，无单测）。

### A-065 MediaController 是异步连接：buildAsync() 完成前不能读，完成后才能用
- 我们调用的 API / 依赖的行为：`MediaController.Builder(context, token).buildAsync()` + `addListener(..., MoreExecutors.directExecutor())`。
- 我们假定的语义：**时序**：future 完成回调之后 controller 才可用；未连上时任何读取都无意义（降级成 503/空快照）；连接失败要能重试/重建。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/ui/common/MusicViewModel.kt:182-226`、`app/src/debug/java/com/zjr/hesimusic/debug/DebugPlayerConnection.kt:59-60`
- 我们代码中的证据：`MusicViewModel.kt:183-186`（`buildAsync()` + `addListener { mediaController = future.get() … }`）；`DebugPlayerConnection.kt:59-60` —「未就绪时**绝不阻塞等待**：controllerOrNull() 只投递建连请求后立即返回 null」。
- confidence：`high`
- 如果假定是错的：连上之前读到空队列 → UI 显示空（已有空安全分支）。
- 现在能否被我们的测试/不变量捕获：**部分**。`MusicViewModelQueueTest`/`MusicViewModelKeepPositionTest` 用 fake，不覆盖连接时序。

### A-066 PlaybackState 约 1s 才刷新一次，MediaController 在两次刷新之间按 speed=1 本地外推
- 我们调用的 API / 依赖的行为：`MediaController.getCurrentPosition()`。
- 我们假定的语义：**时序/边界**：controller 端位置是"快照 + 本地外推"，因此 (a) 跨过 B 后位置冲出区间（≤1s 后回落）；(b) `seekTo` 之后立刻读到的还是旧位置。我们因此做幂等折返 + 2s 强制刷新窗口 + pendingSeek 覆盖层。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/ui/common/MusicViewModel.kt:300-308`、`app/src/main/java/com/zjr/hesimusic/ui/common/MusicViewModel.kt:265-284`、`app/src/main/java/com/zjr/hesimusic/ui/common/LoopPlaybackUi.kt:15-45`、`app/src/main/java/com/zjr/hesimusic/ui/player/PlayerScreen.kt:160-205`
- 我们代码中的证据：`MusicViewModel.kt:300-303` —「MediaSession 的 PlaybackState 约 1s 才刷新一次,MediaController 在两次刷新之间按 speed=1 本地外推,跨过 B 的瞬间会看到位置冲出区间(≤1s 后回落)」；`MusicViewModel.kt:272-275` —「MediaController 的位置更新不会触发任何播放状态回调…seekTo 之后立刻调用时拿到的还是旧快照」；`POSITION_REFRESH_WINDOW_MS = 2_000L`（`:69`）。
- confidence：`high`（真机 + 多处补偿代码）
- 如果假定是错的：折返/覆盖层冗余，但不会造成错误（折返幂等）。
- 现在能否被我们的测试/不变量捕获：**有**。`app/src/test/java/com/zjr/hesimusic/ui/common/LoopPlaybackUiTest.kt`（折返幂等）；`app/src/test/java/com/zjr/hesimusic/ui/player/SeekOverlayPolicyTest.kt`（pendingSeek 收敛/超时）。

### A-067 写命令（seekTo/setRepeatMode/…）是异步生效的，返回时状态还没变
- 我们调用的 API / 依赖的行为：`MediaController.seekTo()` 等。
- 我们假定的语义：**时序**：调用返回 ≠ 生效；立刻读 `currentPosition`/`playbackState` 可能仍是旧值；因此不要"调用后同步断言"，而用事件/轮询收敛。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/ui/common/MusicViewModel.kt:546-554`、`app/src/main/java/com/zjr/hesimusic/ui/player/PlayerScreen.kt:189-205`
- 我们代码中的证据：`MusicViewModel.kt:550-552` —「让进度循环在接下来 2s 内持续刷新,覆盖 MediaController 返回新位置之前的空窗」；`PlayerScreen.kt:161-164` —「seek 要经过 MediaController → MediaSession → LoopSessionPlayer → 引擎才生效…松手就把滑块交还给服务端位置,会先跳回旧位置、下一拍才跳到目标」。
- confidence：`high`
- 如果假定是错的：覆盖层多停留 1.5s（体验略钝），不影响正确性。
- 现在能否被我们的测试/不变量捕获：**有**（`SeekOverlayPolicyTest`）。

### A-068 MediaSession.Builder(this, sessionPlayer) 之后，所有 controller 看到的就是这个 ForwardingPlayer 的位置口径
- 我们调用的 API / 依赖的行为：`MediaSession.Builder(context, Player)`。
- 我们假定的语义：**身份**：MediaSession 直接使用我们传入的 Player 实例（我们传 `LoopSessionPlayer`，它包住 `ForwardingPlayer`，再包住 `ExoPlayer`），因此 controller 读到的 `getCurrentPosition/getDuration/seekTo` 都经过投影层；play/pause/队列原样透传到真实播放器。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/service/MusicService.kt:102-152`、`app/src/main/java/com/zjr/hesimusic/service/LoopSessionPlayer.kt:34-38`
- 我们代码中的证据：`MusicService.kt:145-151`（先包 `ForwardingPlayer`，再包 `LoopSessionPlayer`，最后 `MediaSession.Builder(this, sessionPlayer)`）；`LoopSessionPlayer.kt:16-18` —「只重写位置/时长/seek 五个方法，其余（play/pause/setMediaItem/setRepeatMode/shuffle/queue…）一律透传 —— 播放器状态机保持权威」。
- confidence：`high`
- 如果假定是错的：位置投影不生效 → 通知栏/锁屏进度显示原始时间（重放期冲到 B+kL）。
- 现在能否被我们的测试/不变量捕获：**部分**。`LoopSessionPlayerTest` 验证投影逻辑本身；无 MediaSession 集成测试。

### A-069 MediaSession 的 sessionExtras 可携带自定义字段并跨 controller 读取
- 我们调用的 API / 依赖的行为：`MediaSession.setSessionExtras(Bundle)`、`MediaController.getSessionExtras()`。
- 我们假定的语义：**时序**：`onConnect` 返回的 `ConnectionResult` 里的 extras 会作为 controller 的初始 `sessionExtras`；之后 `setSessionExtras` 的更新会反映到已连接的 controller（我们用它传 `AUDIO_SESSION_ID`）。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/service/MusicService.kt:363-375`、`app/src/main/java/com/zjr/hesimusic/service/MusicService.kt:466-484`、`app/src/main/java/com/zjr/hesimusic/ui/common/MusicViewModel.kt:289`
- 我们代码中的证据：`MusicService.kt:371-374`（`session.setSessionExtras(extras)`）；`MusicViewModel.kt:289` — `controller.sessionExtras.getInt(MusicService.KEY_AUDIO_SESSION_ID, 0)`；`onAudioSessionIdChanged` 时更新（`:262-265`）。
- confidence：`medium`（传播延迟/是否推给已连接 controller 未验证）
- 如果假定是错的：`audioSessionId` 恒为 0 → 均衡器绑不到播放音频会话。
- 现在能否被我们的测试/不变量捕获：**无**。

### A-070 MediaController.isConnected / onDisconnected 是链路有效性判据，与"空闲时长"无关
- 我们调用的 API / 依赖的行为：`MediaController.isConnected`、`MediaController.Listener.onDisconnected`。
- 我们假定的语义：**时序**：只有真的断连才会回调 `onDisconnected`；不会因为长时间无操作而断；`isConnected` 可靠反映链路。
- 依赖位置：`app/src/debug/java/com/zjr/hesimusic/debug/DebugPlayerConnection.kt:53-58`、`app/src/debug/java/com/zjr/hesimusic/debug/DebugPlayerConnection.kt:126-136`
- 我们代码中的证据：`DebugPlayerConnection.kt:54-57` —「**有效性只看链路**:controller == null 或 controller.isConnected == false 才算 invalid; age、queueSize、playbackState、position 都不参与判定」。
- confidence：`medium`
- 如果假定是错的：debug 接口会莫名 503（已去掉 age 判据，但没加重连心跳）。
- 现在能否被我们的测试/不变量捕获：**无**（debug 源集，无单测）。

### A-071 MediaController.releaseFuture() 之后该 controller 不可再用
- 我们调用的 API / 依赖的行为：`MediaController.releaseFuture(future)`。
- 我们假定的语义：**顺序**：release 后必须重新 `buildAsync()`；不能复用旧实例（据此在 `onCleared`/重连时丢弃）。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/ui/common/MusicViewModel.kt:696-702`、`app/src/debug/java/com/zjr/hesimusic/debug/DebugPlayerConnection.kt:195-200`
- 我们代码中的证据：`MusicViewModel.kt:698-700`（`mediaControllerFuture?.let { MediaController.releaseFuture(it) }`）；`DebugPlayerConnection.kt:198`（`mainHandler.post { runCatching { MediaController.releaseFuture(staleFuture) } }`）。
- confidence：`medium`
- 如果假定是错的：复用已释放的 controller 会抛异常/静默失效（已包 `runCatching`）。
- 现在能否被我们的测试/不变量捕获：**无**。

---

## 8. 回调（Listener 的线程、顺序、重入；onMediaItemTransition 何时触发/不触发）

### A-072 Player.Listener 回调在主线程（应用线程）触发，且可以安全地做 UI 状态写入
- 我们调用的 API / 依赖的行为：`player.addListener` 的各个回调（MusicService、MusicViewModel、LoopPlaybackCoordinator、PreviewPlayer）。
- 我们假定的语义：**线程**：回调在主线程；因此可以在回调里直接读 `player.currentPosition`、更新 `StateFlow`、`Log`，不需要额外切线程。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/service/MusicService.kt:260-323`、`app/src/main/java/com/zjr/hesimusic/ui/common/MusicViewModel.kt:186-221`、`app/src/main/java/com/zjr/hesimusic/service/LoopPlaybackCoordinator.kt:118-141`
- 我们代码中的证据：`LoopPlaybackCoordinator.kt:417` —「注册播放器监听（Player 只在主线程 add/remove；同一实例只注册一次）」；`MusicService.kt:271-278` 在回调里直接 `serviceScope.launch`（Main scope）。
- confidence：`high`（全部实现都押在这条上，且从未加锁）
- 如果假定是错的：`_uiState.update` 与 Compose 读取会出现数据竞争/崩溃；`player.currentPosition` 也非线程安全。
- 现在能否被我们的测试/不变量捕获：**无**（线程属性无法在 JVM 单测验证；`PlayerPageShadow` 是单线程纯函数）。

### A-073 onMediaItemTransition 的触发条件与 reason 取值
- 我们调用的 API / 依赖的行为：`onMediaItemTransition(mediaItem, reason)` + `Player.MEDIA_ITEM_TRANSITION_REASON_*`。
- 我们假定的语义：**输入→输出**：切歌/自动推进/repeat 回绕/列表变化会触发并给出对应 reason；reason 可用来区分"用户手动切"与"自动推进"。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/ui/common/MusicViewModel.kt:632-649`、`app/src/main/java/com/zjr/hesimusic/ui/common/MusicViewModel.kt:724-729`、`app/src/main/java/com/zjr/hesimusic/service/MusicService.kt:267-279`
- 我们代码中的证据：`MusicViewModel.kt:724-729` — `shouldForceQueueTransition` 只在 `reason == MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == ..._REPEAT` 时才"强制跳到队列头"。
- confidence：`high`（reason 语义是显式分支条件）
- 如果假定是错的：队列副本的"下一次播它"逻辑失效（`playQueue` 错位、重复播放同一首）。
- 现在能否被我们的测试/不变量捕获：**有**。`app/src/test/java/com/zjr/hesimusic/ui/common/MusicViewModelQueueTest.kt` + `app/src/test/java/com/zjr/hesimusic/ui/library/SongListQueueDisplayTest.kt`。

### A-074 REPEAT_MODE_ONE 播完会触发一次 reason = REPEAT 的 transition
- 我们调用的 API / 依赖的行为：`repeatMode = REPEAT_MODE_ONE` 播到结尾。
- 我们假定的语义：**输入→输出**：不会真的"停止并重播"，而是产生一次 item transition（reason=REPEAT）回到同一项；因此用户代码在 transition 里能感知"又一轮开始"。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/ui/common/MusicViewModel.kt:724-729`、`app/src/main/java/com/zjr/hesimusic/service/LoopPlaybackCoordinator.kt:334-352`
- 我们代码中的证据：`shouldForceQueueTransition()` 把 `REASON_REPEAT` 与 `REASON_AUTO` 并列处理（`:727-728`）；`LoopPlaybackCoordinator` 用 `reason == REASON_TRANSITION` 判断切歌（`:335-337`）。
- confidence：`medium`
- 如果假定是错的：队列副本逻辑在 ONE 模式下永远不前进；`LoopIntent` 的"切到别的歌就退出"也不会被触发。
- 现在能否被我们的测试/不变量捕获：**部分**（纯函数测试覆盖判定，未覆盖"ONE 是否产生 transition"）。

### A-075 Listener 回调是可重入的：在回调里写 setRepeatMode/shuffleModeEnabled 会立刻再触发一次回调
- 我们调用的 API / 依赖的行为：`player.setRepeatMode()` / `player.shuffleModeEnabled = …`（在 `refresh` 内部）。
- 我们假定的语义：**顺序/重入**：写属性会同步（或很快）触发 `onRepeatModeChanged`/`onShuffleModeEnabledChanged`，因此 `refresh` 会被重入；必须"先清意图、再写属性"+"Mutex + 非 immediate 调度"避免死锁与二次恢复。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/service/LoopPlaybackCoordinator.kt:81-88`、`app/src/main/java/com/zjr/hesimusic/service/LoopPlaybackCoordinator.kt:316-322`、`app/src/main/java/com/zjr/hesimusic/service/LoopPlaybackCoordinator.kt:354-365`
- 我们代码中的证据：`LoopPlaybackCoordinator.kt:81-86` —「事件回调用的**非 immediate** 主线程调度器：scope 是 Main.immediate，从主线程 launch 会就地执行；若此刻 refresh 正持有 refreshMutex，就地重入会把互斥量锁死」；`:317-318` —「先 LoopIntent.clear，再写播放器 —— 写回触发的 onRepeatModeChanged / onShuffleModeEnabledChanged 回调 refresh 时 armed=null，不会二次恢复」。
- confidence：`high`（真机死锁/二次恢复踩过）
- 如果假定是错的：我们只是多一层排队，无害。
- 现在能否被我们的测试/不变量捕获：**部分**。`LoopIntentDecisionTest` 覆盖决策纯函数；重入死锁无自动化测试。

### A-076 onTimelineChanged 会在队列增删后触发，可用于对账本地队列
- 我们调用的 API / 依赖的行为：`onTimelineChanged(timeline, reason)`。
- 我们假定的语义：**时序**：`addMediaItems`/`removeMediaItem`/`clearMediaItems` 之后会触发 timeline 变化；我们据此剪掉"本地记录但已不在播放器里"的队列项。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/ui/common/MusicViewModel.kt:217-220`、`app/src/main/java/com/zjr/hesimusic/ui/common/MusicViewModel.kt:655-667`、`app/src/main/java/com/zjr/hesimusic/service/MusicService.kt:311-316`
- 我们代码中的证据：`MusicViewModel.kt:217-220` — `onTimelineChanged → pruneQueueMissingItems(); updateState()`；`pruneQueueMissingItems()` 用 `mediaItemCount`/`getMediaItemAt` 与实际队列比对（`:655-667`）。
- confidence：`high`
- 如果假定是错的：本地队列会残留幽灵项 → 下一次"播放队列副本"找不到 index（`queueItemIndex` 返回 null）。
- 现在能否被我们的测试/不变量捕获：**部分**（`MusicViewModelQueueTest` 测纯函数，不测事件流）。

### A-077 onPlaybackStateChanged 在 STATE_IDLE 时也会回调（包括刚创建时）
- 我们调用的 API / 依赖的行为：`onPlaybackStateChanged(playbackState)`。
- 我们假定的语义：**边界**：IDLE 也是一个会回调的状态；我们在 IDLE 上要"忽略"而不是"当成暂停/结束"。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/service/LoopPlaybackCoordinator.kt:137-140`、`app/src/main/java/com/zjr/hesimusic/service/MusicService.kt:294-297`
- 我们代码中的证据：`LoopPlaybackCoordinator.kt:137-140` — `if (playbackState == Player.STATE_IDLE) return` 后 `requestIntentRefresh(...)`。
- confidence：`medium`
- 如果假定是错的：我们的 `return` 分支是死代码，无害。
- 现在能否被我们的测试/不变量捕获：**无**。

### A-078 onAudioSessionIdChanged 会给出可用的 audioSessionId，且可用来绑定音效
- 我们调用的 API / 依赖的行为：`onAudioSessionIdChanged(audioSessionId)`、`player.audioSessionId`。
- 我们假定的语义：**时序**：audio session id 在播放器准备好后才有意义，变化时回调；我们在回调里把 id 写到 session extras 供 UI 使用。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/service/MusicService.kt:262-265`、`app/src/main/java/com/zjr/hesimusic/service/MusicService.kt:363-375`
- 我们代码中的证据：`MusicService.kt:262-265`（回调 → `updateSessionAudioSessionId`）与 `:369`（`if (lastAudioSessionId == audioSessionId) return` 去重）。
- confidence：`medium`
- 如果假定是错的：id 恒 0 / 不回调 → 均衡器绑不到实际输出（`EqualizerScreen` 无声效），播放本身不受影响。
- 现在能否被我们的测试/不变量捕获：**无**。

---

## 9. 队列（setMediaItems 与 currentIndex、repeatMode、shuffleMode 的交互）

### A-079 currentMediaItemIndex 是**列表索引**（shuffle 不改变索引空间）
- 我们调用的 API / 依赖的行为：`currentMediaItemIndex`、`getMediaItemAt(i)`、`mediaItemCount`。
- 我们假定的语义：**边界**：`currentMediaItemIndex ∈ [0, mediaItemCount)`；遍历 `0 until mediaItemCount` 拿到的是列表顺序（与 shuffle 播放顺序无关）；因此用 index 定位队列副本是可靠的。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/ui/common/MusicViewModel.kt:651-653`、`app/src/main/java/com/zjr/hesimusic/ui/common/MusicViewModel.kt:496-511`、`app/src/main/java/com/zjr/hesimusic/service/MusicService.kt:189-217`
- 我们代码中的证据：`MusicViewModel.kt:651-653`（`queueItemIndex` 线性扫 `getMediaItemAt(it).mediaId`）；`:500-501`（`insertIndex = currentMediaItemIndex + 1 + queue.size`，`coerceIn(0, mediaItemCount)`）。
- confidence：`high`
- 如果假定是错的：队列副本会插到错误位置、`removeMediaItem(queuedIndex)` 会删错项。
- 现在能否被我们的测试/不变量捕获：**部分**（`MusicViewModelQueueTest` 覆盖纯函数判定；index 语义本身无测试）。

### A-080 开启 shuffleModeEnabled 不会改变当前播放项
- 我们调用的 API / 依赖的行为：`player.shuffleModeEnabled = true`。
- 我们假定的语义：**输入→输出**：只改"下一首"的选取顺序，不改 `currentMediaItemIndex`/当前音频（不会触发 transition）。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/service/MusicService.kt:226-231`、`app/src/main/java/com/zjr/hesimusic/ui/common/MusicViewModel.kt:561-564`
- 我们代码中的证据：`MusicService.kt:227-231` —「Now enable shuffle mode if it was enabled (after seeking to the correct song)」——恢复流程刻意"先 seek 到目标歌，最后再开 shuffle"。
- confidence：`high`
- 如果假定是错的：恢复后播的歌与保存的歌不一致（重启听到别的歌）；我们的顺序假设失效。
- 现在能否被我们的测试/不变量捕获：**无**（真机恢复流程）。

### A-081 setMediaItems(items, startIndex, positionMs) 的第三个参数是"该项内的起始位置"
- 我们调用的 API / 依赖的行为：`setMediaItems(mediaItems, startIndex, 0)`、`setMediaItems(items, plan.index, plan.seekTimelineMs)`。
- 我们假定的语义：**输入→输出**：三参数版本同时设定"当前项"和"项内位置"；传 0 即从该项起点开始；该调用隐含一次定位（我们随后不再额外 seek）。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/ui/common/MusicViewModel.kt:481`、`app/src/main/java/com/zjr/hesimusic/service/LoopPlaybackCoordinator.kt:758`
- 我们代码中的证据：`MusicViewModel.kt:481` — `controller.setMediaItems(mediaItems, startIndex, 0)` 紧跟 `prepare()`+`play()`；`LoopPlaybackCoordinator.kt:756-758` 用 `plan.seekTimelineMs` 作第三参。
- confidence：`high`
- 如果假定是错的：进循环的落点永远从 0 开始（用户被拽回 A），或播列表从错误位置起播。
- 现在能否被我们的测试/不变量捕获：**部分**（`LoopSourceItemPlanTest` 只算 `seekTimelineMs` 的值，不验证播放器行为）。

### A-082 addMediaItems(insertIndex, items) 是"插入到该位置之前"，不改变当前项
- 我们调用的 API / 依赖的行为：`player.addMediaItems(insertIndex, items)`。
- 我们假定的语义：**边界**：插入不改 `currentMediaItemIndex` 指向的项（若插入点在其之前，index 会顺移但当前项不变）；插入后触发 timeline 变化。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/ui/common/MusicViewModel.kt:496-511`
- 我们代码中的证据：`MusicViewModel.kt:500-509` — 先算 `insertIndex`（当前项之后 + 已有队列长度），再登记 `_playQueue`（注释："Register queue entries before touching the timeline so the upcoming onTimelineChanged callback still sees them as existing items"）。
- confidence：`medium`
- 如果假定是错的：加队列会打断正在播的歌。
- 现在能否被我们的测试/不变量捕获：**无**。

### A-083 removeMediaItem(index) 之后 currentMediaItemIndex 会收敛到合法值，且当前项可能变化
- 我们调用的 API / 依赖的行为：`removeMediaItem(index)`、`clearMediaItems()`。
- 我们假定的语义：**边界**：删除当前项时播放器会切到相邻项（触发 transition）；删除非当前项不打断播放；删除后 `mediaItemCount` 立即反映。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/ui/common/MusicViewModel.kt:570-582`、`app/src/main/java/com/zjr/hesimusic/ui/common/MusicViewModel.kt:584-605`
- 我们代码中的证据：`consumePlayQueue()` 在 `OTHER_COPY` 分支里"如果不是当前项才删"（`:592-597`），说明我们假定"删当前项会打断、删别的不会"。
- confidence：`medium`
- 如果假定是错的：队列消费后播放停止（用户"点下一首没反应"）。
- 现在能否被我们的测试/不变量捕获：**无**。

### A-084 seekToDefaultPosition(index) 会定位到该项默认起点，并可用于离开 ENDED
- 我们调用的 API / 依赖的行为：`seekToDefaultPosition(index)` / `seekToDefaultPosition()`。
- 我们假定的语义：**输入→输出**：等价于 `seekTo(index, 默认位置)`（我们假定为 0，且对 CUE 项为 clip 起点）；在 ENDED 上调用可恢复播放。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/ui/common/MusicViewModel.kt:469-474`、`app/src/main/java/com/zjr/hesimusic/ui/common/MusicViewModel.kt:632-648`、`app/src/main/java/com/zjr/hesimusic/service/MusicService.kt:118-142`
- 我们代码中的证据：`MusicViewModel.kt:469-473` —「A finished item has no meaningful position to keep; restart it in place. Player.STATE_ENDED -> controller.seekToDefaultPosition()」。
- confidence：`high`
- 如果假定是错的：播放结束后点"播放"不动。
- 现在能否被我们的测试/不变量捕获：**无**（`MusicViewModelKeepPositionTest` 只测判定函数）。

### A-085 hasNextMediaItem()/hasPreviousMediaItem() 在列表头尾会返回 false，repeat 模式不改变它
- 我们调用的 API / 依赖的行为：`hasNextMediaItem()`、`hasPreviousMediaItem()`、`seekToNext()`、`seekToPrevious()`。
- 我们假定的语义：**边界**：这两个判据只看"列表里还有没有下一项"（**不**把 repeat=ALL 的绕回算作"有下一项"）；因此我们自己实现了"到尾部绕回第一项/最后一项"。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/service/MusicService.kt:103-143`、`app/src/main/java/com/zjr/hesimusic/ui/common/MusicViewModel.kt:521-544`
- 我们代码中的证据：`MusicService.kt:118-129` — `if (hasNextMediaItem()) super.seekToNext() else { seekToDefaultPosition(0); if (ENDED || !isPlaying) play() }`；`MusicViewModel.kt:521-539` 有同样的绕回逻辑。
- confidence：`medium`
- 如果假定是错的：我们的绕回分支永不执行（但播放器自己会绕回，功能等价）；若返回 false 而播放器又不绕回，我们自己的分支正好补上。
- 现在能否被我们的测试/不变量捕获：**无**。

### A-086 开启 shuffle 后 seekToNext() 走的是**随机顺序**而不是列表顺序
- 我们调用的 API / 依赖的行为：`seekToNext()`（`shuffleModeEnabled = true` 时）。
- 我们假定的语义：**输入→输出**：shuffle 开启时"下一首"由 shuffle 顺序决定，`seekToNext()` 与 `currentMediaItemIndex + 1` 不是一回事；因此列表里的"下一首"按钮不能只靠 index 算术。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/service/MusicService.kt:118-129`、`app/src/main/java/com/zjr/hesimusic/ui/common/MusicViewModel.kt:521-529`
- 我们代码中的证据：我们从不手算 `index+1` 去切歌，而是统一调 `seekToNext()`/`seekToPrevious()`（`MusicViewModel.kt:525`、`:543`），只有在**尾/头**才降级为 `seekToDefaultPosition(0 / count-1)`。
- confidence：`low` ——**无代码依据**：代码只是"避免手算 index"，没有断言 shuffle 顺序的具体语义；尾部降级在 shuffle 下是否会跳到"顺序的 0"我们也没验证。
- 如果假定是错的：shuffle 下点"下一首"可能跳到列表第一项（而不是随机下一首）。
- 现在能否被我们的测试/不变量捕获：**无**。

### A-087 mediaId 完全由我们决定，不影响播放（只是身份标签）
- 我们调用的 API / 依赖的行为：`MediaItem.Builder().setMediaId("42")` / `"queue:<seq>:<songId>"`。
- 我们假定的语义：**边界**：mediaId 只用于我们自己的映射（`mediaIdToSongId`），ExoPlayer 不用它做加载/去重决策（除 `canUpdateMediaItem` 之外，见 A-017）。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/ui/common/MusicViewModel.kt:112-118`、`app/src/main/java/com/zjr/hesimusic/data/mapper/SongMapper.kt:26-29`、`app/src/main/java/com/zjr/hesimusic/data/mapper/QueueMediaIds.kt`
- 我们代码中的证据：`SongMapper.kt:27` — `setMediaId(id.toString())`；`MusicViewModel.kt:112-113` —「Queue entries carry their own unique mediaId so they stay distinguishable from base-playlist copies of the same song while they sit in the player timeline」。
- confidence：`high`
- 如果假定是错的：队列副本与基础副本会被当成同一项 → 队列消费错乱。
- 现在能否被我们的测试/不变量捕获：**有**。`MusicViewModelQueueTest`；`SongListQueueDisplayTest`。

---

## 10. 时间单位（ms/µs 混用处、取整方向）

### A-088 Player 的所有公开时间参数/返回值单位都是**毫秒**
- 我们调用的 API / 依赖的行为：`seekTo(ms)`、`getCurrentPosition(): ms`、`getDuration(): ms`、`getBufferedPosition(): ms`、`setMediaItems(items, index, positionMs)`、`MediaItem.ClippingConfiguration.setStartPositionMs(...)`。
- 我们假定的语义：**单位**：全部 ms；只有 `Player` 之外的（`Format`/`presentationTimeUs`/`MediaPeriod`）才是 µs。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/service/LoopSessionProjection.kt:23`、`app/src/main/java/com/zjr/hesimusic/service/LoopSessionPlayer.kt:61-114`、`app/src/main/java/com/zjr/hesimusic/data/mapper/SongMapper.kt:32-40`
- 我们代码中的证据：`LoopSessionProjection.US_PER_MS = 1_000L`（`:23`）是**唯一**换算常数；`LoopSessionPlayer` 里每个出入口都做 `/1000` 或 `*1000`（`:61-157`）。
- confidence：`high`
- 如果假定是错的：位置会差 1000 倍（进度条瞬间到末尾或永远 0）。
- 现在能否被我们的测试/不变量捕获：**有**。`LoopSessionPlayerTest.millisecondsConversionKeepsDisplayedPosition`（`:253`）。

### A-089 AudioSink.handleBuffer / SampleStream.readData 的时间戳单位是**微秒**
- 我们调用的 API / 依赖的行为：`presentationTimeUs`、`DecoderInputBuffer.timeUs`、`TrackOutput.sampleMetadata(timeUs)`。
- 我们假定的语义：**单位**：µs；我们的 `LoopWindow` A/B 也是 µs，因此引擎内部不需要换算（只在帧↔µs 之间换算）。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/loop/LoopWindow.kt:3-10`、`app/src/main/java/com/zjr/hesimusic/loop/audio/source/LoopingPcmSampleStream.kt:53`、`app/src/main/java/com/zjr/hesimusic/loop/audio/source/LoopingPcmExtractor.kt:109`
- 我们代码中的证据：`LoopWindow.kt:6-7` —「时间轴口径为**微秒**（与音频层的帧/sampleRate 解耦）」；`LoopPcmEngine.kt:15` —「输入 PTS 一律是**媒体时间**」。
- confidence：`high`
- 如果假定是错的：捕获窗口全部偏移 1000 倍（永不命中 A/B）。
- 现在能否被我们的测试/不变量捕获：**有**。`LoopSessionPlayerTest`、`LoopAudioSinkTest`（窗口用 µs 常量）、`LoopingPcmSampleStreamTest`（`buffer.timeUs == 0 / 10_000_000`）。

### A-090 µs → ms 一律**整除截断**（floor），ms → µs 一律乘法
- 我们调用的 API / 依赖的行为：`rawUs / 1000`、`ms * 1000`。
- 我们假定的语义：**取整方向**：所有对外位置都 floor 到 ms（宁可显示略早）；seek 目标同样 floor（可能比请求早 <1ms）。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/service/LoopSessionPlayer.kt:70`、`app/src/main/java/com/zjr/hesimusic/service/LoopSessionPlayer.kt:147`、`app/src/main/java/com/zjr/hesimusic/service/LoopSourceProjection.kt:24-25`、`app/src/main/java/com/zjr/hesimusic/service/LoopSourceItemPlan.kt:79`
- 我们代码中的证据：`LoopSourceProjection.displayMs()` — `displayUs(timelineMs * US_PER_MS, window) / US_PER_MS`（`:24-25`）；`LoopSourceItemPlan.enter()` — `seekTimelineMs = offsetUs / US_PER_MS`（`:79`）。
- confidence：`high`
- 如果假定是错的：位置/落点最多差 1ms（48kHz 下约 48 帧）；但若某处是 ceil 期望，会累积成"每次循环少/多 1ms"（我们的 ring 按帧取整，所以不累积）。
- 现在能否被我们的测试/不变量捕获：**有**。`LoopSourceProjectionTest`、`LoopingPcmTimelineTest`。

### A-091 帧 ↔ 时间换算的取整约定：us→frame 用 **ceil**，frame→us 用 **floor**
- 我们调用的 API / 依赖的行为：我们自己定义的换算（不依赖 ExoPlayer）。
- 我们假定的语义：**边界**：`frameAtOrAfterUs(us) = ceil(us*sr/1e6)`（"第一个不早于该时间的帧"）；`frameUs(f) = floor(f*1e6/sr)`；两者互逆到 <1 帧；区间帧数用 `ceil(duration*sr/1e6)`。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/loop/analysis/Pcm16Segment.kt:40-61`、`app/src/main/java/com/zjr/hesimusic/loop/audio/source/LoopingPcmTimeline.kt:120-122`、`app/src/main/java/com/zjr/hesimusic/loop/audio/source/LoopingPcmTimeline.kt:41-47`
- 我们代码中的证据：`Pcm16Segment.kt:43-46` —「口径：帧 f 的媒体时间(相对锚点) = floor(f * 1e6 / sampleRate)。因此"第一个 PTS ≥ us 的帧"是 ceil(us * sampleRate / 1e6)」；`LoopingPcmTimeline.kt:120` —「帧号 → 媒体时间（floor），与 PcmDecoder 的 ceil(us*sr/1e6) 互逆」。
- confidence：`high`
- 如果假定是错的：环长度与 A/B 差 1 帧 → 每轮多/少 1 帧（48kHz 下 20.8µs/帧），长期会累积成可闻的相位漂移（我们的验收口径是"接缝 0 帧静音"，见 `docs/loop-v3-final-report.md:8`）。
- 现在能否被我们的测试/不变量捕获：**有**。`app/src/test/java/com/zjr/hesimusic/loop/analysis/Pcm16FramesTest.kt`、`LoopingPcmTimelineTest`、`LoopAudioSinkTest` 的"三圈逐位相同"用例（`:363`）。

### A-092 C.TIME_UNSET 是**负数**，因此"duration < 0"可判"时长未知"
- 我们调用的 API / 依赖的行为：`player.getDuration()` / `window.durationUs`。
- 我们假定的语义：**边界**：`getDuration()` 返回 `TIME_UNSET`（负）表示未知；我们据此回退到数据库时长/整曲时长，而不是显示负数或 0。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/ui/common/MusicViewModel.kt:351-360`、`app/src/main/java/com/zjr/hesimusic/service/LoopSessionPlayer.kt:102-114`、`app/src/test/java/com/zjr/hesimusic/loop/audio/source/LoopingPcmMediaSourceTest.kt:26`
- 我们代码中的证据：`MusicViewModel.kt:351-352` —「If controller.duration is negative (C.TIME_UNSET), get duration from database」；`LoopingPcmMediaSourceTest.kt:26` — `assertEquals(C.TIME_UNSET, window.durationUs)`。
- confidence：`high`
- 如果假定是错的：UI 进度条分母为 0，`safeDuration` 走 `1L` 兜底（`PlayerScreen.kt:152`），进度显示错乱（拖动无效）。
- 现在能否被我们的测试/不变量捕获：**有（部分）**。`LoopingPcmMediaSourceTest:26`；`LoopSessionPlayerTest.duration_projectedToFullSongWhenWindowActive`（`:173`）。

### A-093 duration == 0 与"未知"是两种不同语义，我们分别处理
- 我们调用的 API / 依赖的行为：`getDuration()` 返回 0 vs 负数。
- 我们假定的语义：**边界**：0 表示"0 长度/不可用"，负数（`TIME_UNSET`）表示"未知"；我们的兜底链是 `投影时长 > 0 ? 投影 : 原生`，0 时不参与 clamp。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/service/LoopSessionProjection.kt:64-65`、`app/src/main/java/com/zjr/hesimusic/ui/common/MusicViewModel.kt:352-360`
- 我们代码中的证据：`LoopSessionProjection.durationUs()` — `if (window != null && fullDurationUs > 0L) fullDurationUs else 0L`（0 = 不投影）；`MusicViewModel.kt:359` — `controllerDuration.coerceAtLeast(0L)`。
- confidence：`medium`
- 如果假定是错的：某些歌会显示 0 时长（进度条不可拖）。
- 现在能否被我们的测试/不变量捕获：**有（部分）**（`LoopSessionPlayerTest` 的 duration 用例）。

### A-094 currentPosition 与 bufferedPosition 同单位同坐标系（都是"该项内"的 ms）
- 我们调用的 API / 依赖的行为：`getCurrentPosition()` / `getBufferedPosition()`。
- 我们假定的语义：**口径**：两者都相对当前项起点、单位 ms；因此可以对它们做 max/clamp 与同一套折返；`buffered` 可能小于 `position`（重放/环回后），必须自己取 max 防倒退。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/service/LoopSessionProjection.kt:99-108`、`app/src/main/java/com/zjr/hesimusic/service/LoopSessionPlayer.kt:73-100`
- 我们代码中的证据：`LoopSessionProjection.kt:94-97` —「重放期播放器自身缓冲位冻结在 B，折返后是 A，若不与当前位置取 max，进度条的缓冲条会「倒退」到环回点前」。
- confidence：`high`
- 如果假定是错的：缓冲条位置会明显偏移（跳变）。
- 现在能否被我们的测试/不变量捕获：**有**。`LoopSessionPlayerTest.buffered_foldsAndClampsToDuration`（`:223`）。

### A-095 1e12 级别的 outputStreamOffsetUs 不会让我们的 Long 运算溢出（边界假设）
- 我们调用的 API / 依赖的行为：`presentationTimeUs + seamPtsBiasUs + outputStreamOffsetUs`、`rawUs - offset`。
- 我们假定的语义：**边界值**：offset 最大到 1e12 量级，PTS 也在 1e12 量级，二者相加仍在 Long 安全范围（9.2e18）；我们不做饱和保护，只做"`raw >= offset` 才减"的方向判断。
- 依赖位置：`app/src/main/java/com/zjr/hesimusic/loop/audio/LoopAudioSink.kt:784-790`、`app/src/main/java/com/zjr/hesimusic/loop/audio/LoopAudioSink.kt:800-808`
- 我们代码中的证据：`LoopAudioSink.kt:802-807` —「delegate 位置 = outputOffset + media；少数内部 reset 情况下可能已经是 media，因此 raw < offset 时不再减」。
- confidence：`medium`
- 如果假定是错的：加法仍安全，但"raw < offset"的方向判断会误判（把媒体时间再减一次）。
- 现在能否被我们的测试/不变量捕获：**有（部分）**。`LoopAudioSinkTest.kt:750`（offset 在流中途变化时不串味）。

---

## 附录 A：清单覆盖对照

| 必查项 | 覆盖条目 |
|---|---|
| 1 播放器生命周期（create/build、setMediaItem(s)、prepare/play/pause/release；状态迁移；ENDED 后行为） | A-001…A-010 |
| 2 换源语义（replaceMediaItem vs setMediaItems；旧源复用；tag/mediaId/cacheKey） | A-011…A-017 |
| 3 MediaSource/MediaPeriod（createPeriod/enable 时序、prepare 回调、seek 后 PTS、buffered 口径） | A-018…A-033（nextLoad 见 A-022） |
| 4 Extractor（CONTINUE/END_OF_INPUT、sampleData/Metadata、SeekMap、getNextLoadPositionUs） | A-034…A-043（+ A-022） |
| 5 AudioSink（configure/handleBuffer/play/pause/flush/reset/release 时序；play 权威；format 变化） | A-044…A-058（+ A-062 的 clip/offset） |
| 6 渲染器/时钟（position 驱动、renderer 消费起点、audio renderer start/stop） | A-059…A-063 |
| 7 会话层（MediaController 线程亲和、Service 绑定、跨会话延迟） | A-064…A-071 |
| 8 回调（线程、顺序、重入、onMediaItemTransition） | A-072…A-078 |
| 9 队列（setMediaItems/currentIndex/repeat/shuffle 交互） | A-079…A-087 |
| 10 时间单位（ms/µs 混用、取整方向） | A-088…A-095 |

## 附录 B：给任务 B 的高风险条目（建议优先判定）

按"错了会立刻出可闻/可见 bug"排序：

1. **A-003 / A-004 / A-005**（ENDED 语义）——决定"播完再点播放"是否可用；已有 Robolectric 断言，若与 media3 文档不符，说明我们的测试只证明"当时版本行为"。
2. **A-011 / A-012 / A-013**（replaceMediaItem 复用旧源）——00021 的根因判断；若文档明确 canUpdateMediaItem 包含 tag，我们的整条规避是多余的。
3. **A-018**（createPeriod 先于 enable）——6.9 节"无限 BUFFERING"的根因；若文档规定了顺序，说明我们当时踩的是实现细节/版本行为。
4. **A-022**（END_OF_SOURCE 不导致 ENDED）——我们 docs 自己标注"需 E2E 确认"，是**已知不确定点**。
5. **A-023**（buffered 口径决定起播门槛）——决定 1s 首块能否起播。
6. **A-044 / A-045**（offset 先于 configure；PTS 在 offset 域）——M3′ 全链路时间域的基础。
7. **A-050 / A-052**（play 权威、循环态 isEnded=false）——00020"0.x 秒播放 + 停顿"的直接成因。
8. **A-066 / A-067**（PlaybackState ~1s + 外推；写命令异步）——决定 UI 上所有"位置回弹/闪跳"补偿是否必要。
9. **A-080 / A-081**（shuffle 不改当前项；setMediaItems 三参语义）——决定重启恢复能否播到正确位置。
10. **A-091**（帧↔µs 取整方向）——决定"接缝 0 帧漂移"的验收是否成立。

## 附录 C：本文件未覆盖（明确的知识空白）

以下问题本仓库**没有**任何代码/测试/文档依据，因此没有成条（显式空白，供任务 B 决定是否补）：

1. `MediaSource.maybeThrowSourceInfoRefreshError()` 的调用时机与频率（我们只实现"有错就抛"，没验证谁在何时调）。
2. `MediaPeriod.prepare` 的 Callback 是否允许从任意线程回调（我们用 `CallbackDispatcher` 切到 `Looper.myLooper()`，但没验证契约要求）。
3. `MediaSourceEventListener` / `DrmSessionEventListener` 的派发义务（我们**只记录不派发**，见 `LoopingPcmMediaSource.kt:27-28`；若播放器依赖这些事件做统计/重试，可能少数据）。
4. `MediaSessionService` 的前台服务/通知生命周期细节（我们只重写了 `onUpdateNotification`/`onGetSession`）。
5. `Player.Commands` 白名单副作用（`MusicService.kt:103-116` 用 `buildUpon().add(...)` 放开 NEXT/PREVIOUS）。
6. `ExoPlayer.setAudioAttributes(attrs, handleAudioFocus = true)` 是否真的会在焦点丢失时 pause（我们只在 docs 里写"系统 pause"，无代码依据）。
