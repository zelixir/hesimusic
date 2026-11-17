# 循环数据源设计（00017）— 调查 + 块缓存原型

> 状态：**调查结论 + 最小原型（纯 JVM）已落地**。
> 不接 UI/service、不碰设备、不加依赖；写域仅 `loop/audio/source/**`、对应测试、本文件。
> 全量单测基线 1047 → 当前 **1107**（新增 60；第二阶段接入后），0 failures / 0 skipped。

## 0. 结论摘要

1. **PTS 归我们所有**。media3 1.8.0 里 `presentationTimeUs` 的源头是数据源写入的
   `DecoderInputBuffer.timeUs`；`ProgressiveMediaSource` 只是把 Extractor 的
   `sampleMetadata(timeUs, …)` 交给 `SampleQueue`，`SampleQueue` 再原样交给渲染器。
   自定义 `MediaSource` + 自定义 `SampleStream` 可以完全掌控 PTS。
2. **无限/动态时间轴可用**。`SinglePeriodTimeline(durationUs = C.TIME_UNSET,
   windowDurationUs = C.TIME_UNSET, isDynamic = true, isSeekable = true,
   useLiveConfiguration = false)`：播放器只对**已知 duration**做终点钳制；
   只要 SampleStream 不返回 EOS，播放可以无限进行。B→A 在数据源内部取模，
   不 seek、不 flush、PTS 不回跳。
3. **走 PCM 源（`audio/raw`）**。`MediaCodecAudioRenderer` 对 PCM16 走 bypass，
   不创建 MediaCodec；我们用自己的块缓存 + `PcmDecoder.decodePcm16Range` 精确解码。
   这样避开 MP3/AAC 帧对齐、encoder delay、容器 PTS 和任何 sink 层二次映射。
4. **seek 由 `MediaPeriod.seekToUs()` 承接，但 ExoPlayer 的 seek 必然 flush AudioSink**：
   `stopRenderers → BUFFERING → seekToUs → discardBuffer → resetRendererPosition →
   audioSink.flush()`。数据源能保证的是：清队列、O(1) 重定位、目标块命中零解码、
   未命中只解目标 1 秒块、PTS 在新 epoch 从 T' 单调开始、不重建播放器/不整环解码。
   **"seek 零空洞"物理上做不到**；循环接缝（B→A）不经过 seek，所以接缝仍无缝。
5. **按需解码 + 块缓存**：1 秒一块、块首帧 `aFrame + k*sampleRate`、命中直接供样本、
   未命中只解当前块并预取 k+1/k+2；默认预算 128MB，超预算优先淘汰离播放头最远的块。
6. **首块结构可满足预览**：进页只需解码 1 个 1 秒块（合成音频 20ms 延迟测试中首块
   <500ms，真实解码器预计 20~40ms）；页面不再需要整环/前缀预填。
7. 磁盘缓存只在本文给设计（第二期），未实现。

## 1. PTS 归属：谁生成，能不能完全掌控

### 1.1 标准链路（ProgressiveMediaSource）

```
MediaExtractor（容器/帧头）
  → BundledExtractorsAdapter / ProgressiveMediaExtractor
  → Extractor.read()
  → TrackOutput.sampleMetadata(timeUs, flags, size, offset, crypto)
  → SampleQueue.sampleMetadata()
        timeUs += sampleOffsetUs            // 默认 0
        commitSample(timeUs, …)             // 存进 timesUs[]
  → SampleQueue.peekSampleMetadata()
        buffer.timeUs = timesUs[readIndex]
  → MediaCodecRenderer.readInputSample()
        presentationTimeUs = buffer.timeUs
        codec.queueInputBuffer(..., presentationTimeUs, flags)
  → MediaCodec 输出 info.presentationTimeUs（解码路径）
    或 bypass 直接用 buffer.timeUs（PCM 路径）
  → MediaCodecAudioRenderer.processOutputBuffer(bufferPresentationTimeUs)
  → AudioSink.handleBuffer(buffer, presentationTimeUs, sampleCount)
  → DefaultAudioSink：flush 后首个 buffer 用 PTS 锚定 startMediaTimeUs
```

关键证据（media3 1.8.0）：

| 阶段 | 证据 |
|---|---|
| Extractor 产出样本时间 | `ProgressiveMediaPeriod.ExtractingLoadable.load()` 调 `progressiveMediaExtractor.read()`；Extractor 通过 `TrackOutput.sampleMetadata(timeUs, …)` 写出（`ProgressiveMediaPeriod.java:1146`、`1178-1179`） |
| SampleQueue 接收时间 | `SampleQueue.sampleMetadata()`：`timeUs += sampleOffsetUs`，`commitSample(timeUs, …)`（`SampleQueue.java:612-655`） |
| SampleQueue 读出时间 | `SampleQueue.peekSampleMetadata()`：`buffer.timeUs = timesUs[relativeReadIndex]`（`SampleQueue.java:733`） |
| 渲染器拿到输入 PTS | `MediaCodecRenderer.readInputSample()`：`presentationTimeUs = buffer.timeUs`，再 `queueInputBuffer(presentationTimeUs)`（`MediaCodecRenderer.java:1519`、`1555-1560`） |
| 输出 PTS 回传 sink | `MediaCodecRenderer` 用 `outputBufferInfo.presentationTimeUs` 调 `processOutputBuffer`；`MediaCodecAudioRenderer.processOutputBuffer()` 调 `audioSink.handleBuffer(...)`（`MediaCodecRenderer.java:2180`、`MediaCodecAudioRenderer.java:832`） |
| PCM bypass 的 PTS | bypass 批处理以批内**首样本** `getFirstSampleTimeUs()` 作为整批 PTS（`MediaCodecRenderer.java:2485-2493`） |
| sink 的 PTS 语义 | `DefaultAudioSink.handleBuffer()`：flush 后首个 buffer 用 PTS 锚定 `startMediaTimeUs`；之后按已提交帧数推算期望 PTS，`|expected - actual| > 200ms` 时报告 `UnexpectedDiscontinuityException` 并重锚（`DefaultAudioSink.java:1005-1008`、`1053-1081`） |

### 1.2 自定义源的控制点

- 自定义 `MediaSource` 返回自定义 `MediaPeriod`；`selectTracks()` 返回自定义
  `SampleStream`。`SampleStream.readData()` 里由我们设置：
  `formatHolder.format = pcmFormat`、`buffer.timeUs = 我们的 PTS`、`buffer.data = PCM16`。
- 本原型**没有使用 `SampleQueue`**：块缓存就是唯一的 PCM 存储，
  `LoopingPcmSampleStream` 直接从 [PcmBlockCache] 读并填 `DecoderInputBuffer`。
  好处是 seek 清队列只是 cursor 重定位，不会出现"块缓存 + SampleQueue"双份内存。
- 因此 **PTS 的生成者就是我们的 SampleStream**；渲染器只消费，不改写。

### 1.3 "DefaultAudioSink 单调断言"的澄清

media3 1.8.0 的 `DefaultAudioSink` **没有**对 PTS 做硬性单调断言。它做的是：

1. flush 后第一个 buffer：`startMediaTimeUs = max(0, presentationTimeUs)`；
2. 之后每个新 buffer：`expected = startMediaTimeUs + submittedFramesToDurationUs()`；
3. 如果 `|expected - presentationTimeUs| > 200_000us` 且未在等待同步：
   回调 `AudioSink.Listener.onAudioSinkError(UnexpectedDiscontinuityException)`，
   然后 `startMediaTimeUsNeedsSync = true`，下一块把 `startMediaTimeUs` 调整到当前 PTS；
4. `AudioTrack` 时间戳异常（`InvalidAudioTrackTimestampException`）是设备时间戳问题，
   不是数据源 PTS 的单调断言。

之前踩坑的真正机制更可能是：**sink 层改写 PTS** 与 ExoPlayer 的
`flush()/startMediaTimeUs` 重锚语义冲突（重放 PTS 落在旧 epoch，或 offset 域混用），
以及自定义 `LoopAudioSink` 自己的倒退拒收。数据源层只要保证：
**同一次 flush 之后 PTS 严格单调、且与输出帧数一致**，就不会触发 200ms 重锚。

### 1.4 新链路

```
LoopingPcmMediaSource（单窗口未知时长动态 Timeline）
  → LoopingPcmMediaPeriod（core + SampleStream + 单线程 decode worker）
  → LoopingPcmSampleStream.readData()
        Format = audio/raw PCM16
        buffer.timeUs = T（单调递增）
  → MediaCodecAudioRenderer（shouldUseBypass = true，无 MediaCodec）
  → DefaultAudioSink（不再包 LoopAudioSink）
  → AudioTrack
```

`MediaCodecAudioRenderer.shouldUseBypass()` 返回 `audioSink.supportsFormat(format)`；
PCM16 的 `audio/raw` 被 `DefaultAudioSink` 支持，因此不创建解码器
（`MediaCodecAudioRenderer.java:458-470`、`341`）。

## 2. 需要实现的接口清单与复杂度

### 2.1 `MediaSource`

| 方法 | 语义 | 原型实现 | 复杂度 |
|---|---|---|---|
| `prepareSource(caller, transferListener, playerId)` | 注册 caller 并给 Timeline | 保存 caller；立即 `onSourceInfoRefreshed(this, sourceTimeline)` | 低 |
| `maybeThrowSourceInfoRefreshError()` | 抛准备期错误 | 保存 sourceError | 低 |
| `enable(caller)` | 允许创建 period | 标记 enabled | 低 |
| `createPeriod(id, allocator, startPositionUs)` | 创建 MediaPeriod | new `LoopingPcmMediaPeriod` | 低 |
| `releasePeriod(period)` | 释放 period | `release()` + 移除 | 低 |
| `disable(caller)` | 停止加载 | enabled=false | 低 |
| `releaseSource(caller)` | 注销 caller | 清理 | 低 |
| `add/removeEventListener`、`add/removeDrmEventListener` | 事件分发 | 原型只记录（不派发），不影响播放 | 低（样板） |
| `getMediaItem()` | 返回 MediaItem | 保存的 item | 低 |
| `getInitialTimeline()` / `isSingleWindow()` | 初始占位/单窗口 | 返回同一无限 Timeline；true | 低 |

### 2.2 `MediaPeriod`

| 方法 | 语义 | 原型实现 | 复杂度 |
|---|---|---|---|
| `prepare(callback, positionUs)` | 异步准备 | 单线程 worker 解首块；就绪后 `callback.onPrepared` | 中 |
| `maybeThrowPrepareError()` | 准备期错误 | 抛 `IOException` | 低 |
| `getTrackGroups()` | 暴露 TrackGroup | 单个 audio TrackGroup（audio/raw PCM16） | 低 |
| `selectTracks(...)` | 选择/复用 SampleStream | 一个流；返回 positionUs | 中 |
| `discardBuffer(positionUs, toKeyframe)` | 丢弃已缓冲数据 | no-op（seek 已重定位 cursor，无独立队列） | 低 |
| `readDiscontinuity()` | 是否有断点 | **永远 `C.TIME_UNSET`**（循环不是 discontinuity） | 低 |
| `seekToUs(positionUs)` | 跳转 | core 重定位 + 异步补目标块；见 §4 | **高** |
| `getAdjustedSeekPositionUs(...)` | 关键帧对齐 | 直接返回 positionUs（PCM 无关键帧） | 低 |
| `getBufferedPositionUs()` | 已缓冲到哪 | 连续 READY 块覆盖到的 PTS 末尾 | 低 |
| `getNextLoadPositionUs()` | 下一次加载位置 | 需要解码时返回 bufferedEnd；否则 `C.TIME_END_OF_SOURCE` | 中 |
| `continueLoading(LoadingInfo)` | 继续加载 | 提交单线程解码任务，不阻塞 | 中 |
| `isLoading()` | 是否在加载 | activeTasks>0 | 低 |
| `reevaluateBuffer(positionUs)` | 重新评估缓冲 | needsDecode 时提交解码 | 低 |

### 2.3 `SampleStream`

| 方法 | 语义 | 原型实现 | 复杂度 |
|---|---|---|---|
| `isReady()` | 有数据可读 | 当前块 READY | 低 |
| `maybeThrowError()` | 抛加载错误 | core.error() → IOException | 低 |
| `readData(formatHolder, buffer, flags)` | 读 format/PCM | 首次/REQUIRE_FORMAT 返回 Format；否则填 PCM+PTS；支持 PEEK/OMIT | 中 |
| `skipData(positionUs)` | 跳到指定位置 | core.skipToPts | 低 |

### 2.4 工作量估计（实际完成）

| 模块 | 文件 | 行数（约） |
|---|---|---:|
| 时间轴/帧号算术 | `LoopingPcmTimeline.kt` | 140 |
| 块缓存/淘汰 | `PcmBlockCache.kt` | 190 |
| 核心 cursor/seek/按需解码 | `LoopingPcmSourceCore.kt` | 330 |
| SampleStream | `LoopingPcmSampleStream.kt` | 90 |
| MediaPeriod | `LoopingPcmMediaPeriod.kt` | 230 |
| MediaSource | `LoopingPcmMediaSource.kt` | 150 |
| 契约/解码适配 | `PcmRangeProvider.kt`、`PcmDecoderRangeProvider.kt` | 60 |
| 测试 | 6 个测试类 + 2 个支撑类 | ~700 |

## 3. 无限/动态时间轴

### 3.1 用法

```kotlin
SinglePeriodTimeline(
    /* periodDurationUs = */ C.TIME_UNSET,
    /* windowDurationUs = */ C.TIME_UNSET,
    /* windowPositionInPeriodUs = */ 0L,
    /* windowDefaultStartPositionUs = */ 0L,
    /* isSeekable = */ true,
    /* isDynamic = */ true,
    /* useLiveConfiguration = */ false,
    manifest = null,
    mediaItem = item,
)
```

- `durationUs = C.TIME_UNSET`：`Timeline.getPeriodPositionUs()` 只在
  `period.durationUs != C.TIME_UNSET` 时把位置钳到 `durationUs - 1`；
  未知时长不钳制（`Timeline.java:1246-1251`）。任意 `player.seekTo(T)` 都可用。
- `isDynamic = true` + `useLiveConfiguration = false`：窗口是动态的，但不是 live，
  不会启用直播目标延迟/追直播沿逻辑。
- `isSeekable = true`：让 `Player.isCurrentMediaItemSeekable()` 为 true；
  实际 seek 落到 `MediaPeriod.seekToUs()`。
- 播放永不结束：SampleStream 永不返回 `BUFFER_FLAG_END_OF_STREAM`；
  整环缓存后 `getNextLoadPositionUs()` 可以返回 `C.TIME_END_OF_SOURCE`
  表示"无需再加载"，但已缓存样本仍可无限读出。
- 不需要 `REPEAT_MODE_ONE`，也不需要在 B 处 seek。

### 3.2 需要真机验证的点

- 无限时长下 `player.getDuration()` 为 TIME_UNSET；循环页必须用 `T mod L` 投影显示。
- `getNextLoadPositionUs()` 在"当前块+预取就绪"时返回 END_OF_SOURCE，播放推进到
  下一块时再变回有限值；需要 E2E 确认 ExoPlayer 不会因此进入 ENDED（原型只做 JVM）。
- 位置回调/AudioTrack 缓冲延迟不影响数据源；seek 目标由 Player 的 seek 语义决定。

## 4. 时间轴、块缓存与 seek

### 4.1 时间轴定义

- `A = loopStartUs`，`B = loopEndUs`，`L = B - A`；
- 进入点 `E ∈ [A,B)`，`entryOffsetUs = floorMod(E - A, L)`；
- 对外播放时间 `T ≥ 0`（0 = 进入点），媒体位置：

```
mediaUs(T) = A + floorMod(entryOffsetUs + T, L)
```

- 到 B 时 `floorMod` 自然回到 A；**PTS 继续增长，不 seek、不 flush**。
- 如果希望以 A 为 origin，进入时 `E=A`，`entryOffsetUs=0`，即
  `mediaUs(T)=A+floorMod(T,L)`。

### 4.2 1 秒块网格

```
aFrame = ceil(A * sr / 1e6)
bFrame = ceil(B * sr / 1e6)
loopFrames = bFrame - aFrame
framesPerBlock = sr
blockCount = ceil(loopFrames / framesPerBlock)
blockStartFrame(k) = aFrame + k * framesPerBlock
blockFrames(k) = min(framesPerBlock, loopFrames - k*framesPerBlock)
```

- 块 k 的解码区间：`[blockStartFrame(k), blockStartFrame(k)+blockFrames(k))`；
  首块起点用 `A`，其余用 `frameUs(blockStartFrame(k))`，保证
  `ceil(startUs*sr/1e6) = blockStartFrame(k)`（与 `PcmDecoder.decodePcm16Range` 对齐）。
- 缓存 key：`PcmBlockCacheKey(songId, loopStartSamples=aFrame, loopEndSamples=bFrame,
  sampleRate, channels, loopStartUs=A, loopEndUs=B)`；A/B/采样率/声道/歌曲任一变化 = 新 key
  （原始 µs 也进 key，避免 A 变但帧号相同的碰撞）。
- 块 PCM 用 16bit 小端交错，大小 `frames*channels*2`；48kHz/2ch 一块 = 192KB。

### 4.3 按需解码与预取

- 播放头在块 k：`cache.get(k)` 命中 → 直接供样本；
- 未命中：worker 只解块 k；随后预取 k+1、k+2（最多 3 块窗口）；
- 不整环预填、不做 12s 前缀预填；
- `LoopingPcmSourceCore.pumpDecode()` 一次只解一块：当前块优先，当前块 READY 后
  才预取；当前块 PENDING 时直接返回，避免重复解码；
- `LoopingPcmMediaPeriod` 的 worker 单线程串行（MediaCodec 单实例约束），
  一次 `continueLoading` 最多补 3 块；`prepare` 只解首块；
- 连续拖动时只有最新 generation 的任务继续解码；命中块零解码。

### 4.4 seek 的实现

```
seekToUs(T'):
  t = max(0, T')
  ptsBase = t
  emittedFrames = 0
  nextFrame = frameAtTimeline(t)          // A + floorMod(entryOffset+t, L) 后取整帧
  cache.playheadBlock = blockIndex(nextFrame)
  cache.retryFailed(playheadBlock)        // seek 回来允许重试失败块
  // 没有独立队列要清；cursor 就是队列头
  requestDecode()                         // 异步；命中则下一 read 立即可读
  return t
```

- **命中已解码块**：`seekToUs` O(1)，下一次 `readData` 立即有样本；
- **未命中**：只解目标块（1s），不在播放线程解码；
- **连续拖动**：每次只改 cursor/PTS 基准，不重建 period、不整环解码；
  旧 generation 的解码任务不会再继续预取；
- **缓冲未就绪 seek**：seek 立即返回；`isReady()=false` 让播放器短暂 BUFFERING，
  目标块就绪后继续；
- **PTS**：新 epoch 从 T' 开始；`pts = ptsBase + emittedFrames*1e6/sr`，
  严格单调；ExoPlayer 在 seek 时会 flush AudioSink，所以跨 seek 不比较 PTS。
- **seek 是否需要等未完成块**：
  - 目标块 READY：不等，`readData` 立即返回；
  - 目标块 PENDING（已经有一个解码在跑）：`seekToUs` 仍立即返回，`isReady()=false`
    让播放器缓冲；worker 完成后同一块直接可用，不会重复提交；
  - 目标块缺失、但 worker 正在解别的块：旧任务最多跑完当前 1 秒块（generation 过期后
    不再预取），目标块排在其后；因此最坏多等约一个块的解码时间（20~40ms），
    **不会整环重解**，也不阻塞播放线程；
  - prepare 期 seek：prepare 的首块任务按最新 cursor 解码，最多 4 次尝试覆盖改目标。

### 4.5 seek 的物理限制（必须写进验收口径）

ExoPlayer 1.8.0 的 seek 路径固定：

```
stopRenderers()
STATE_BUFFERING
mediaPeriod.getAdjustedSeekPositionUs(...)
mediaPeriod.seekToUs(periodPositionUs)
mediaPeriod.discardBuffer(...)
resetRendererPosition(...)
  → Renderer.resetPosition()
  → MediaCodecAudioRenderer.onPositionReset()
  → audioSink.flush()
```

证据：`ExoPlayerImplInternal.java:1702-1705`、`1747-1752`、`1765-1773`；
`MediaCodecAudioRenderer.java:691-694`。

因此**任何用户 seek 都会 flush 一次 AudioTrack，产生一次设备相关的短空洞**。
数据源无法消除它；能保证的是：不重建、不清缓存、目标块命中零解码、未命中只解一块、
最后一次目标精确、PTS 从新目标单调。循环接缝不是 seek，所以接缝仍无缝。
若要求"拖动也零空洞"，只能绕过 ExoPlayer 的 seek 在 sink 注入样本（本次明确禁止），
不能作为数据源验收项。

### 4.6 内存上限与 ExoPlayer 自带缓冲

- 长驻 PCM 只有**一份**：`PcmBlockCache`；`SampleStream` 直接读缓存，
  不再复制进 `SampleQueue`。
- ExoPlayer 侧只有不可避免的短时缓冲：`DecoderInputBuffer` 单块（64KB 上限的 read chunk）、
  `MediaCodecAudioRenderer` 的 bypass batch、AudioSink/AudioTrack 的播放缓冲。
  这些是任何播放器都有的，不是"第二份 128MB 块缓存"。
- `READ_CHUNK_BYTES = 64KB`（约 42ms@48k/2ch），不会把整块 192KB 复制进 renderer。
- 预算默认 128MB；48kHz/2ch 约 682 块（≈11 分钟）。超预算按"离播放头最远优先"淘汰，
  当前块受保护；单块大于预算时也至少保留一块，避免 OOM/死锁。
- 预览页与正式播放共用同一实现，但各自播放器实例各自一份缓存；如需共享，
  可把 `PcmBlockCache` 提升为按 key 的进程级缓存（第二期）。

## 5. 原型实现（已落地）

### 5.1 文件

```
app/src/main/java/com/zjr/hesimusic/loop/audio/source/
  PcmRangeProvider.kt          // provider 契约 + PcmTrackFormat + PcmBlockCacheKey
  LoopingPcmTimeline.kt        // A/B、entryOffset、mediaUs(T)、1s 块网格
  PcmBlockCache.kt             // 线程安全块缓存、PENDING/READY/FAILED、距离优先淘汰
  LoopingPcmSourceCore.kt      // cursor、按需解码、seek、PTS、失败跳过
  LoopingPcmSampleStream.kt    // media3 SampleStream（直接读块缓存）
  LoopingPcmMediaPeriod.kt     // media3 MediaPeriod + 单线程解码 worker
  LoopingPcmMediaSource.kt     // media3 MediaSource（无限动态 Timeline）
  PcmDecoderRangeProvider.kt  // 生产适配：PcmDecoder.decodePcm16Range
  PcmFileFormatProbe.kt       // MediaExtractor 探测 sr/ch（生产构造前用）
  LoopSourceMode.kt           // M3_PRIME / SOURCE 开关 + settings 读取 + MediaItem tag
  LoopSourceProjection.kt     // T ↔ display/seek 投影（纯函数）
  SourceRenderersFactory.kt   // SOURCE 默认 AudioSink + TapAudioSink（只取证不改 PTS）

app/src/test/java/com/zjr/hesimusic/loop/audio/source/
  LoopingPcmTimelineTest.kt
  PcmBlockCacheTest.kt
  LoopingPcmSourceCoreLoopTest.kt
  LoopingPcmSourceCoreSeekTest.kt
  LoopingPcmSampleStreamTest.kt
  LoopingPcmMediaSourceTest.kt
  LoopTrackAssignmentTest.kt
  LoopSourceProjectionTest.kt
  LoopSourceProjectionTest.kt
  LoopingPcmMediaPeriodPrepareTest.kt
  LoopingPcmMediaSourceLifecycleTest.kt
  SyntheticPcmProvider.kt
  LoopingPcmTestSupport.kt
```

### 5.2 关键实现点

- `PcmBlockCache`：`ConcurrentHashMap` + 全操作锁；`markPending` 原子防重复解码；
  `put` 后淘汰；`retryFailed` 允许 seek 重试；`budgetBytes` 至少一块。
- `LoopingPcmSourceCore`：
  - `decodeLock` 串行化 provider 调用；provider 调用**不持有**状态锁，
    所以 seek/read 不会被 20~40ms 的 MediaCodec 解码阻塞；
  - `read` 只读当前块，不跨块复制；
  - 失败块：标记 FAILED，`read/pumpDecode` 跳过该块并记日志，PTS 继续；
    连续所有块失败才置 fatal error；
  - `skipToPts` 支持 renderer 的 `skipData`。
- `LoopingPcmMediaPeriod`：
  - 单线程 daemon worker，优先级 BACKGROUND；
  - `seekToUs` 只改 core 并提交解码，不等待；
  - `requestGeneration` 让连续拖动只有最后一次继续解码；
  - `prepare` 只解首块（4 次上限覆盖 prepare 期间 seek）；
  - 首块就绪后 `onPrepared`；`getNextLoadPositionUs` 在不需要解码时返回 END_OF_SOURCE。
- `LoopingPcmMediaSource`：`SinglePeriodTimeline` 单窗口、未知时长、dynamic、seekable；
  `getInitialTimeline` 直接返回它。
- 生产适配 `PcmDecoderRangeProvider` 用 `runBlocking` 只在 worker 调用；
  原型每块创建/释放 MediaExtractor+MediaCodec，生产优化方向是常驻解码会话。

### 5.3 测试证据（全量绿）

```
:app:testDebugUnitTest
files=70 tests=1107 failures=0 skipped=0
```

（基线 1047，新增 60；最后以实际跑数为准。）

覆盖：

| 要求 | 测试 |
|---|---|
| 3 圈逐样本等于理论 [A,B) 重复 | `LoopingPcmSourceCoreLoopTest.three loops are sample exact...` |
| PTS 严格单调、按累计帧计算 | 同上；`PTS continues across B...` |
| 前进 10s→60s / 后退 60s→10s | `LoopingPcmSourceCoreSeekTest` |
| seek 后 PCM 连续（无空洞/重复） | `PCM after seek is contiguous...` |
| A+1ms / B−1ms | 同上 |
| 连续 10 次拖动 | `ten rapid different seeks...`、`rapid repeated seeks...` |
| 缓冲未就绪 seek / 解码中 seek | `seek before first decode...`、`seek while a decode is in flight...` |
| 命中即零解码 | `seeking to the same position twice...`、`rapid repeated seeks...` |
| 块首帧 == aFrame + k*sr | `LoopingPcmTimelineTest.block start frames align...` |
| 按需性（播 3s 只解 3 块） | `on demand decoding only decodes the played window` |
| 预取 ≤ 当前+2 | `prefetch is bounded...` |
| 预算 3 块淘汰 | `cache budget of three blocks...`、`PcmBlockCacheTest.eviction...` |
| 解码失败跳过 | `decode failure skips one block...` |
| 并发 pump 串行 | `concurrent pumpDecode calls are serialized` |
| 首块只解 1 块 / 首块延迟结构 | `first block decode after seek...`、`first block latency structure...` |
| SampleStream format/PTS/PEEK/OMIT | `LoopingPcmSampleStreamTest` |
| MediaSource 单窗口动态可 seek | `LoopingPcmMediaSourceTest` |
| 音频 selection 在 renderer index 1 也正确挂流 | `LoopTrackAssignmentTest` |

## 6. 预览页接入（第二阶段，已接）

### 6.1 开关

`LoopSourceMode` 默认 `M3_PRIME`。真机验证无需改 debug 源集/UI：

```
adb shell settings put global hesi_loop_source_mode 1   # SOURCE
adb shell settings put global hesi_loop_source_mode 0   # M3′（默认）
```

`PreviewPlayer` 在打开结果页时读取该 setting；因此**设置后重新进入结果页**即可。
默认 0 时页面状态 `sourceMode=false`，行为与改造前完全一致。

### 6.2 启动/拖动时序（SOURCE）

1. **进页**：`PreviewPlayer.prepare()` 只写 song/A/B/window/时长，**不创建播放器**；
   `LoopPageState.Ready.sourceMode=true`。
2. **第一次 Play**：按模式选引擎：
   - FULL/LOOP_ONLY/SEAM：`SourceRenderersFactory` + `LoopingPcmMediaSource`；
   - FREE：仍走 M3′/普通播放（SOURCE 不接管 FREE）。
3. **首块**：`MediaPeriod.prepare()` 只解当前 1 秒块 → `onPrepared` → player READY →
   `PreviewPlayer.sourceFirstBlockEvents` → reducer 把 `FirstBlockLoading` 清为 READY。
   UI 仅在 >300ms 时显示「正在加载首块…」，不阻塞播放按钮/拖动。
4. **拖动**：`PreviewPlayer.seekToDisplayMs` 把展示位置反算为数据源时间轴 T，
   调用普通 `player.seekTo(T)` → `MediaPeriod.seekToUs`。命中块立即出样本；
   未命中只解目标块；ExoPlayer 自己的 seek flush 约几十 ms（验收 ≤100ms）。
5. **A/B 变化**：reducer 发 `ApplyWindow` + `SeekTo` + `Play`；`PreviewPlayer.updateWindow`
   用新 timeline 构建新 `LoopingPcmMediaSource`，`player.setMediaSource(new, resetPosition=false)`，
   **不重建 ExoPlayer**；新 `PcmBlockCacheKey` 丢弃旧块。
   同一 A/B 不重建 source，只 seek。

### 6.3 模式差异

- SOURCE 的 FULL 从 **A** 起播（数据源时间轴 T=0 对应 A，前奏不参与循环）；
  UI 已在该模式下提示。需要严格“从前奏 0 起播”请切回 M3′。
- LOOP_ONLY：从 A 起。
- SEAM：从 B−5s 起（数据源 T=L−5s，显示仍 B−5s）。
- FREE/M3_PRIME：原路径完全不变。

### 6.4 改动的文件

| 文件 | 改动 |
|---|---|
| `ui/loop/PreviewPlayer.kt` | SOURCE/M3′ 双引擎；SOURCE 默认 AudioSink + TapAudioSink；A/B 新 source 不重建 player；source 首块事件 |
| `ui/loop/state/LoopPageState.kt` | `Ready.sourceMode`、`AnalysisDone.sourceMode`、`PrefillState.FirstBlockLoading`、`LoopPageEffect.ApplyWindow`；SOURCE 不启动预填、不进入 AwaitingPrefill |
| `ui/loop/LoopViewModel.kt` | 读取/传递 sourceMode；SOURCE 跳过 `startPrefillInternal`；`ApplyWindow` 接到 PreviewPlayer；首块事件清提示 |
| `ui/loop/LoopPageEffectRunner.kt` | 新增 `ApplyWindow` 执行；`StartPrefill/CancelPrefill` 仍保留给 M3′ |
| `ui/loop/LoopPagePrefillUi.kt` / `LoopScreen.kt` | FirstBlockLoading 文案、300ms 提示、SOURCE 下隐藏 M3 长窗口/前缀未就绪提示 |
| `service/LoopSessionPlayer.kt` | tag 识别的 source 投影（`A + floorMod(T,L)`，seek 反算 T）；M3′ 分支不变 |
| `service/LoopPlaybackCoordinator.kt` | 仅对 tagged source item 跳过 M3 shape，避免 sink 二次拦截 |
| `di/MediaModule.kt` | SOURCE 时选 `SourceRenderersFactory`，M3′ 时保持 `LoopRenderersFactory` |
| `loop/audio/source/*` | `LoopSourceMode/LoopSourceProjection/SourceRenderersFactory(TapAudioSink)` |

### 6.5 无缝取证

- `TapAudioSink` 只委托 `DefaultAudioSink` 并 tap 实际消费的 PCM，不改 PTS；
- `LoopAudioController.window` 在 SOURCE 启动时写入 A/B，因此既有
  `POST /debug/tap` + `tools/loop-smoothness.ts` 仍可分析 SOURCE 预览输出；
- 期望：`seamAlign` 帧距 == L、`zeroRuns/clicks` PASS；用户 seek 的空洞 ≤100ms。

### 6.6 正式播放（边界说明）

`LoopSessionPlayer` 已按 `LoopSourceMediaTag` 支持 source 投影，`LoopPlaybackCoordinator`
也会跳过 tagged source 的 M3 shape。但把主播放器队列里的当前 item 换成自定义 MediaSource
需要 `MusicService`/MediaSourceFactory 级改动（不在本阶段写域）；本阶段 SOURCE 的**实际接线是预览页**。
默认 M3_PRIME 下正式播放路径完全未变；主播放器即使启动时 SourceRenderersFactory，
也不会自动获得 loop source，需下一阶段接 MediaSourceFactory/队列。

### 6.7 首块延迟预算

- 1s 块，48kHz/2ch 解码目标 20~40ms；
- 首块链路：worker → `PcmDecoder.decodePcm16Range` → 块 READY → onPrepared；
  无整环、无预取（预取在 onPrepared 后）；
- 合成音频 20ms 人工延迟测试：只解 1 块、<500ms；
- 真机验收：进页首声 ≤300ms；拖动命中 ≤150ms、未命中 ≤250ms。

### 6.8 上机验证步骤（给 lead）

1. 装 debug 包，先保持默认 `hesi_loop_source_mode=0`，进结果页播放/拖动，确认与改造前一致（M3′ 回退）。
2. `adb shell settings put global hesi_loop_source_mode 1`，退出结果页再进入（让 PreviewPlayer 重新读 setting）。
3. 选「仅循环段」或「接缝检查」→ Play；logcat 过滤：
   - `LoopPreview`：`source installed ...`、`play source mode=... startT=...`、`source first block ready`、`source seek display=... targetT=...`、`source window updated ...`;
   - `LoopSourceMode`：`loop source mode -> SOURCE`；
   - `LoopPerf`：现有 frame/underrun 统计。
4. `POST /debug/tap {"path":"/sdcard/Download/source.pcm"}`，播 2~3 圈，停；拉文件 + sidecar：
   `bun run tools/loop-smoothness.ts source.pcm --events source.pcm.jsonl`；
   期望 `RESULT: PASS`，`seamAlign` 帧距 == L，`zeroRuns/clicks` 通过。
5. seek 空洞：在 tap 里量 seek 前后连续非零样本距；或 logcat 记录
   `applyVolumeShaper/seek` 与 `source first block ready` 的时间差，目标 ≤100ms。
6. 反复拖同一段：观察 `LoopingPcmSourceCore.decodeCallCount`（可加 1Hz 日志）或
   `source seek` 日志，第二次起不再出现新的 `decode block`。
7. A/B 改动：拖动 A/B 松手，确认 logcat 出现 `source window updated` 且没有
   `preview player created`（没有重建 player）。
8. 回退：`adb shell settings put global hesi_loop_source_mode 0`，退出重进；
   确认 `sourceMode=false`，预填/环回行为与改造前一致。

注意：SOURCE 的 FULL 从 A 起播（前奏不参与）；要验证前奏请用 M3′ 或 FREE。

### 6.9 真机 BUFFERING 卡死根因与修复（2026-11-17）

真机 1.8.5 首次运行 SOURCE：`source installed` 后一直 `state=BUFFERING`，无
`LoopPcmSource decode block` 日志。根因：

1. media3 1.8.0 的 `MaskingMediaSource` 在懒准备路径下会**先**
   `createPeriod()`，再通过 `prepareChildSource()` 触发子 source 的
   `prepareSource()`/`enable()`；
2. 旧 `LoopingPcmMediaSource.createPeriod()` 在 `enabled == false` 时直接
   `throw IllegalStateException("MediaSource is not enabled")`，导致周期根本没创建，
   自然没有 `MediaPeriod.prepare()`/首块解码，播放器表现就是无限 BUFFERING；
3. 该异常没有进入首块/解码日志，所以只看到 BUFFERING。

修复：

- `createPeriod()` 不再因 enable 时序抛异常，只记
  `createPeriod before enable; continue`；enable/disable 仅做日志/记录；
- `LoopingPcmMediaPeriod` 增加：
  - `prepare start/after seek`、`requestDecode firstBlock/gen`、
  - `pumpDecode ... pumped/state`、`first block check ready`、
  - `decode start/done block=... ms=... calls=...`、`decode failed/crashed`，
  - `first block ready -> onPrepared`；
- **首块 2s 超时**：时间到仍不 ready 时置 `prepareError`（含 core 状态），
  `maybeThrowPrepareError()` 抛 IOException，ExoPlayer 进入错误态而不是无限 BUFFERING；
- `PcmRangeProvider` 路径：`PreviewPlayer` 用 `song.filePath` probe/解码，
  `source installed ... path=...` 日志可核对；
- 新增 JVM 测试：
  - `LoopingPcmMediaSourceLifecycleTest`：**createPeriod before enable 不抛**且能创建 period；
  - `LoopingPcmMediaPeriodPrepareTest`：正常 provider 首块 `onPrepared`；
    provider 永久 pending 时超时后 `maybeThrowPrepareError` 抛错。

### 6.10 活锁：同一块永远 PENDING、requestDecode 每 10ms 刷（2026-11-17）

真机第二轮：生命周期问题修复后能解码了，但播放中同一块长期 `PENDING`：

```text
requestDecode firstBlock=false gen=418 frame=1429632 block=23 state=PENDING ready=23 calls=24
requestDecode firstBlock=false gen=419 ...（每 10ms 一条，gen 一路涨）
```

根因：

- ExoPlayer 的 loading loop 约每 10ms 调一次 `continueLoading()`；
- 旧 `requestDecode()` **每次调用都 `requestGeneration.incrementAndGet()` 并提交任务**；
- worker 的非首块循环在每次 `pumpDecode()` 前检查 generation，generation 被
  `continueLoading` 顶掉后，正在解的目标块任务被“作废”，块一直停在 PENDING；
- 新任务看到 PENDING 又不再解码，形成活锁。

修复（[LoopingPcmMediaPeriod.kt](app/src/main/java/com/zjr/hesimusic/loop/audio/source/LoopingPcmMediaPeriod.kt)）：

- 增加 `decodeScheduled: AtomicBoolean`：worker 在跑时 `continueLoading` 只累加
  `dedupeSkips`，**不提交任务、不递增 generation**；dedupe 日志最多 1 秒一条/换块时一条；
- `requestGeneration` **只在 `seekToUs()` 递增**；read/continueLoading 不改变它；
- worker 不再按 generation 中断，任务一旦开始就把当前目标解完；
- 当前块已是 PENDING 时直接 dedupe 返回，不再提交空转任务；
- 增加 2s decode watchdog / pending 超时：worker 卡住时把当前 PENDING 块置 FAILED 并跳过，
  迟到的解码结果不会覆盖 FAILED，
  首块则同时置 `prepareError`，避免整条播放无限等待；
- 日志保留 `requestDecode start`、`requestDecode dedupe`、`decode start/done`、
  `decode stuck ... marked FAILED`、`first block ready -> onPrepared`。

新增测试 `LoopingPcmMediaPeriodPrepareTest`：

- 同一 PENDING 块连续 `continueLoading()` 100 次：provider 只调用 **1 次**，
  `currentRequestGeneration()` 保持 **0**，`dedupeSkipCount() >= 100`。


## 7. 与 M3′ / Seam Splice 的取舍

| 维度 | M3′（sink 重写 PTS） | Seam Splice（2Δ） | 本方案（循环数据源） |
|---|---|---|---|
| 循环接缝 | sink 层改 PTS，跨 B 续写 | 2Δ 缓冲拼接 | 数据源内部取模，PTS 连续 |
| seek | sink/引擎映射，易混 offset | 依赖 seek 预滚 + defer | `MediaPeriod.seekToUs`，player 原生 |
| 内存 | 整环/分片 | 2Δ ≈384KB@48k/2ch/1s | 1s 块按需，默认 128MB 上限 |
| 重复解码 | 分片一次 | 每圈解 X/Y | 块缓存命中零解码，未命中只解 1 块 |
| 实现风险 | PTS 与 flush/offset 冲突（已踩坑） | 状态机复杂 | 接口面大，但语义最干净 |
| 对 seek 友好 | 差 | 中 | **最好**（原生 seek） |
| 适用 | 现状兜底 | 长窗口/内存敏感 | 本地文件、追求正确性/预览共用 |

迁移路径：

1. 本阶段只落数据源 + 块缓存 + 测试，不接线；
2. 页面派工后加 `LoopSourceMode`（M3′/SEAM/DATA_SOURCE），默认仍 M3′；
3. 数据源路径用默认 AudioSink，删除 `LoopAudioSink` 的 PTS 改写；
4. `LoopSessionPlayer` 投影改为 `T mod L`；
5. 数据源稳定后删除 Seam Splice sink 路由，M3′ 仅保底；
6. 失败/格式不支持/预算异常 → 回退 M3′。

## 8. 磁盘缓存（第二期，不实现）

设计：

- 文件按块存：`cacheDir/loop-pcm/<keyHash>/<blockIndex>.pcm`；
- `keyHash = sha256(songId, aFrame, bFrame, sampleRate, channels, decoderVersion)`；
- 块文件头：magic/version、frames、bytesPerFrame、CRC32；读取时校验，损坏则删除重解；
- 内存缓存只保留最近 N 块；磁盘 LRU 由独立 manifest（访问时间/总大小）管理；
- 写入异步：解码成功先入内存，再 worker 落盘；播放不等待；
- 预算默认 512MB，超预算按"离播放头最远 + 最久未用"淘汰；
- seek 到磁盘命中块：异步读文件 + 校验，仍不阻塞播放线程；未命中才解码；
- 删除/窗口变化：按 keyHash 整个目录异步清理；不阻塞预览。
- 二期需真机评估 I/O 延迟（1s 块 192KB，随机读通常 <10ms，但低端机/省电模式要测）。

## 9. 风险与待确认

1. **seek 必然有一次 AudioSink flush**：这是 ExoPlayer 语义；若验收要求"拖动零空洞"，
   数据源做不到，需要明确取舍。
2. **未知 duration 的 UI 影响**：`player.getDuration()=TIME_UNSET`，循环页必须用
   `T mod L` 投影；现有 `LoopSessionPlayer` 已在做类似投影，但数据源路径要改公式。
3. **`getNextLoadPositionUs` 的 END_OF_SOURCE 语义**：JVM 测试通过，真机需确认
   ExoPlayer 不会因此 ENDED、并在播放推进时重新请求加载。
4. **每块新建 MediaCodec**：原型 `PcmDecoderRangeProvider` 每次范围解码创建/释放
   extractor+codec；1s 块 20~40ms 可接受，但连续拖动/低端机可能抖动。生产建议常驻
   解码会话（同一 extractor/codec 顺序读，跨块不重建）。
5. **A/B 变化**：本阶段用新 MediaSource（新 cache key）+ 同一个 player；
   若要求"不替换 source、只换窗口"，需要再做 period 级 rebind（本阶段未实现）。
6. **无设备**：AudioTrack 行为、seek flush 时长、首声延迟、`loop-smoothness.ts` PASS
   都需真机验证；本阶段只证明数据/PTS 语义。
7. **事件派发**：MediaSource 监听器原型只记录不派发；生产接线需补
   `MediaSourceEventListener.EventDispatcher`（不影响播放正确性）。
8. **并发**：块缓存/核心已做锁与 generation；MediaPeriod 的 Handler/worker 未做 JVM
   集成测试，真机需跑连续拖动/失败注入。

## 10. 源码证据索引

- media3 1.8.0 sources（Google Maven，解压到 `build/media3-src` 只读分析，不提交）：
  `ProgressiveMediaPeriod.java`、`SampleQueue.java`、`MediaCodecRenderer.java`、
  `MediaCodecAudioRenderer.java`、`DefaultAudioSink.java`、`Timeline.java`、
  `SinglePeriodTimeline.java`、`ExoPlayerImplInternal.java`、`MediaSource.java`、
  `MediaPeriod.java`、`SampleStream.java`。
- 现有解码/对齐：
  `app/src/main/java/com/zjr/hesimusic/loop/analysis/PcmDecoder.kt:248-275`、
  `Pcm16Frames.kt`、`RingChunkedSeeder.kt`。
- 现有 M3′/接缝路径：
  `app/src/main/java/com/zjr/hesimusic/loop/audio/LoopPcmEngine.kt`、
  `LoopAudioSink.kt`、`LoopSeamSpliceBuffer.kt`（迁移时保留兜底）。
- 基线：`docs/loop-seam-buffer-design.md`；全量测试结果
  `app/build/test-results/testDebugUnitTest`。
