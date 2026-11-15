> **状态:未选型(2026-11-15)**。P0 取证发现压缩域裁剪边界被量化到解码器帧(每轮多约一帧,MP3 +216 帧),
> 因此 M1 无法满足「零漂移」判据,除非再加一层 PCM 裁剪;主线已改为 M3'(见 plan §11.4)。本文保留作设计参考。

# M1 设计:环回 MediaSource(单 period 无限样本流)

> P0 spike 若判定 M1(公开 API 自研环回源)为选型,则按本文实现。P1 实现者以此为准。
> 前提事实(已用 javap 核对 media3 1.8.0):
> - `SampleStream`:`isReady() / maybeThrowError() / readData(FormatHolder, DecoderInputBuffer, int) / skipData(long)`
> - `MediaPeriod` extends `SequenceableLoader`:`prepare(Callback,long) / getTrackGroups() / selectTracks(...) / discardBuffer(long,boolean) / readDiscontinuity() / seekToUs(long) / getAdjustedSeekPositionUs(long,SeekParameters) / getBufferedPositionUs() / getNextLoadPositionUs() / continueLoading(LoadingInfo) / isLoading() / reevaluateBuffer(long)`
> - `DecoderInputBuffer.timeUs` 是 **public 可写字段**;`Buffer.clearFlag(int)/addFlag(int)` 是 public final → 可改写时间戳、清 EOS 标志。
> - `DefaultRenderersFactory.buildAudioSink(Context,boolean,boolean)` 是 protected → 可注入自定义 AudioSink(若走 M3)。

## 1. 目标形态

一个 `MediaSource` = 播放器里的**一个媒体项**、**一个窗口**、**一个 period**,其音频样本流**永不结束**:
[A,B) 的内容一轮接一轮连续输出,样本时间戳单调递增(不折返),从而:

- 音频管线看不到任何 period/窗口/EOS 边界 → 不会 flush;
- 解码器不重启 → 无重建开销;
- 播放器不会 transition/repeat → 队列、通知、锁屏、上一首/下一首全部零特判;
- 位置在引擎侧单调增长,**展示位置由会话层纯函数折返**(见 `LoopPosition`)。

## 2. 类结构

```
loop/source/
  LoopSourceConfig.kt      // data class: songId, aUs, bUs
  LoopingWindowMediaSource.kt   // MediaSource: 供 timeline + createPeriod
  LoopingWindowPeriod.kt        // MediaPeriod: 管轮次(子 period)生命周期
  LoopingSampleStream.kt        // SampleStream: 读当前轮 + 时间戳改写 + 跨轮切换
  IterationTicker.kt            // 轮次事件打点(仅 debug,供 L3 取证)
```

### 2.1 子轮次(child iteration)

每一轮 = 一个独立的、裁剪到 [A,B) 的子 MediaSource:

```kotlin
val child = ClippingMediaSource(
    ProgressiveMediaSource.Factory(dataSourceFactory, extractorsFactory).createMediaSource(item),
    aUs * 1000, bUs * 1000        // ClippingMediaSource 用 µs
)
```

- 子源数量:同时存活 **2 个**(当前 + 预取下一轮);第 3 个在切换后释放。
- 裁剪的意义:子 period 从 A 起算(样本级对齐,不受 MP3 帧级 seek 精度影响),到 B 结束。
- 每个子源自己带 extractor/Loader/SampleQueue(Media3 原生机制),互不干扰。

### 2.1.1 坐标与"前奏 = 第 0 轮"(必须严格遵守,否则位置映射整体偏一个前奏)

引擎原始时间轴 = **整曲坐标**,且与 `LoopPosition` 严格自洽:

| 轮次 | 内容 | 子源裁剪 | 该轮样本的 base 时间戳 |
|---|---|---|---|
| 0(前奏轮) | 歌曲开头到 B,**只播一次** | `[0, B)` | `0` |
| 1 | 第一次重复 | `[A, B)` | `B` |
| j (≥1) | 第 j 次重复 | `[A, B)` | `B + (j-1)×L` |

即:第 j 轮(j≥1)的起点 = `A + j×L`(= `B + (j-1)×L`),与 `LoopPosition.iterationStartUs(j)` 一致;
因此 `displayUs(raw)` 对 `raw∈[0,A)` 原样、`raw∈[A,B)` 原样、`raw≥B` 折返到 `A + ((raw-A) mod L)` —— 三种情况都正确。

**A == 0 时**:前奏轮与循环轮内容相同(都是 `[0,B)`),实现上可直接把第 0 轮也当循环轮,base 公式不变。

**禁止**让子源交付"文件坐标"的时间戳(裁剪子源的样本时间戳是相对该切口 0 起的),必须用上表的 base 重新基准化;
否则 `displayUs`/seek 全部错位,且表现为"每轮多出/少掉一个前奏"。

### 2.2 轮次切换(核心)

`LoopingWindowPeriod` 内部维护:
```kotlin
private var current: ChildPeriod   // 已 prepare、已 selectTracks 的当前轮
private var next: ChildPeriod?     // 预取中
private var offsetUs: Long = 0     // 当前轮的累计时间偏移 = iteration * L
```

`LoopingSampleStream.readData(...)`:
1. 调 `current.stream.readData(formatHolder, buffer, flags)`;
2. 若返回 `C.RESULT_END_OF_STREAM`(子轮读完):
   - 若 `next` 未就绪 → 返回 `C.RESULT_NOTHING_READ`(且 `isReady()=false`,让 renderer 等);
   - 若就绪 → `current = next; offsetUs += L; next = null; 立刻预取新的 next` → 重试读一次;
3. 读到样本后:`buffer.timeUs += offsetUs`;`buffer.clearFlag(C.BUFFER_FLAG_END_OF_STREAM)`;
   保留 `BUFFER_FLAG_KEY_FRAME` 等其他标志不动;
4. **绝不**向 renderer 返回 EOS。

`isReady()` = `current.isReady || (current 已结束 && next?.isReady == true)`。
`maybeThrowError()` 转发当前轮的异常(读失败 → 抛,让播放器走 onPlayerError,不做静默降级)。

### 2.3 时间线

`getTimeline()` 返回 `SinglePeriodTimeline`(或等价自定义 Timeline):
- `durationUs` = **整曲时长**(用于进度条/通知/时长展示);**不要**用 `C.TIME_UNSET`,否则 UI 会当成直播。
- `isSeekable = true`, `isDynamic = false`。
- 媒体项 = 原 `MediaItem`(带 songId 的 mediaId),不改。

> 注意:引擎原始位置会超过 durationUs(因为流永不结束)。这是**预期**的,展示层折返;不要试图钳制。

### 2.4 seek

`seekToUs(positionUs)`(**positionUs 是整曲坐标**):
- `positionUs ∈ [0, A)` → 第 0 轮(前奏),子源 `[0,B)` 内 seek 到 positionUs,base=0;
- `positionUs ∈ [A, B)` → 第 0 轮内 seek 到 positionUs(base=0);
- `positionUs ≥ B` → `j = 1 + (positionUs - B) / L`,`off = (positionUs - A) mod L`,即第 j 轮内 seek 到 A+off,base=`B+(j-1)L`;
- 若目标轮次 == 当前轮次:转发当前子 period 的 `seekToUs`(只 seek 不重建);
- 否则:丢弃当前轮(含预取),新建子源 → seek 到切口内偏移 → 重置 base;
- 返回 positionUs;
- `readDiscontinuity()` **恒返回 C.TIME_UNSET**(我们对外表现为连续时间轴,不触发 renderer 的流切换/flush);
- `getAdjustedSeekPositionUs` 直接返回入参(不做帧对齐)。

### 2.5 缓冲与加载

- `getBufferedPositionUs()` = `offsetUs + current.getBufferedPositionUs()`(为 `C.TIME_END_OF_SOURCE` 时用 `offsetUs + L` / `next` 的偏移);
- `getNextLoadPositionUs()` = `offsetUs + current.getNextLoadPositionUs()`(`next` 未就绪时返回其加载位置);
- `continueLoading(loadingInfo)` → 转发 `current`;`next` 的预取用子 period 自己的 Loader(经 `onContinueLoadingRequested` 回调触发),不需要我们驱动;
- `discardBuffer/reevaluateBuffer/isLoading` 全部转发当前轮。

### 2.6 轨道

- `getTrackGroups()` 用当前轮(或第一轮)的 `TrackGroupArray`;
- `selectTracks` 把选择结果按**索引**转给每个子 period(同一文件 → 轨道结构一致);子 period 返回的 SampleStream 包进 `LoopingSampleStream`;
- 索引不一致(理论上不会发生)时:按 index 兜底,并在 debug 下打日志。

## 3. 边界情形(必须有单测/真机用例)

| 情形 | 期望 |
|---|---|
| L < 200ms | 仍可环回;预取窗口要足够早(在 `bufferedPosition` 距 B 小于 1×L 时预取) |
| A = 0 | 允许;第一轮即从头 |
| B = 曲尾 | 允许;子 period 正常结束 |
| seek 到 A 之前 | 前奏照常播,到 B 进环回(由 offset=k*0 决定) |
| seek 到 B 之后 | 由会话层 `LoopPosition.rawSeekUs` 折返,源只接受合法区间 |
| 循环区间时长 > 内存/缓冲策略上限 | 源本身无固定内存(只有 2 轮子 period 的缓冲),不设上限 |
| 子 period 读失败 | `maybeThrowError` 抛出 → `onPlayerError` → 用户可见提示 |
| 播放中改 A/B | 由服务层**重建 MediaSource**(一次性 seek 级切换,允许短暂停顿),源本身不做热更新 |

## 4. 与服务层的接法(P1)

1. `MusicService` 用 `DefaultMediaSourceFactory` 的子类:`createMediaSource(MediaItem)` 检查 `mediaItem.mediaMetadata.extras` 是否带 `loopAUs/loopBUs`,有则返回 `LoopingWindowMediaSource`,否则 `super`。
2. `LoopRuntime.window` 变化时,服务层:`player.setMediaItem(item.buildUpon().setMediaMetadata(extras(loop)).build(), mappedSeekUs)` + `prepare()`;
   这是**唯一**会打断播放的操作,只发生在"进入/退出无缝、切歌、改 A/B"这类显式状态变化上,**不在环回点**。
3. 展示位置:`sessionPlayer`(ForwardingPlayer) 的 `getCurrentPosition()/seekTo()` 用 `LoopPosition` 折返;
   `getDuration()` 保持原生(整曲)。
4. `onMediaItemTransition` 时 `LoopRuntime.refresh(...)`(重算一次纯函数);`onRepeatModeChanged` 同。

## 5. 可测性

- `IterationTicker`:debug 构建下每完成一轮(切换 next)打一行 `LoopPerf` 日志
  (`iter,offsetUs,outFrames,wallMs`),供 L3 取证脚本做"零漂移/逐轮相同"判定;
- 探针模块(`spike/`)也可复用该类做无 UI 的机制验证。