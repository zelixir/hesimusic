# M3′ 设计:AudioSink 层 PCM 捕获 + 经 delegate 重放(主线方案)

> P0 取证结论:压缩域裁剪边界被量化到解码器帧(MP3 每轮 +216 帧),真无缝必须在 PCM 层控制。
> 本文是实现契约。相关:P0 证据见 `spike/REPORT-P0-android.md` 与 `spike/out/`;判据见 plan §6.2。

## 1. 为什么是 M3′ 而不是 M1 / 自己开线程喂 AudioTrack

| 方案 | 致命问题 |
|---|---|
| 时间线/窗口切边界(baseline / window) | 边界 → configure + discontinuity + 补静音(27.8ms) |
| M1 压缩域无限样本流 | 裁剪边界被量化到解码帧 → 每轮 +216 帧(0.05%),且仍需 PCM 裁剪 |
| M3 自己开线程直写 AudioTrack | AudioTrack 被 delegate stop→play、playhead 归零、player 位置冻结 |
| **M3′(本方案)** | 无上面任何一条:重放数据仍经 `DefaultAudioSink.handleBuffer` 喂,delegate 永不 ended |

核心机制:输入到 B 之后**不结束、不 flush、不切 item**,而是把先前捕获的 [A,B) PCM **继续喂给同一个 AudioSink**,
时间戳按 `B + (k-1)·L + offset` 单调递增。播放器看到的是"一直在播、位置一直在走",没有边界可检查。

## 2. 组件

```
loop/audio/
  LoopAudioSink.kt          // ForwardingAudioSink 包装 DefaultAudioSink(唯一和音频线程打交道的类)
  LoopAudioController.kt    // @Singleton:窗口、ring、捕获进度、重放状态、debug 计数
  LoopRenderersFactory.kt   // DefaultRenderersFactory 子类,注入 LoopAudioSink
loop/                       // 已有:LoopWindow / LoopPosition / LoopEligibility / LoopRuntime
service/
  LoopPlaybackCoordinator.kt  // 服务层:窗口 → MediaItem 形态 / 位置映射接线(薄)
```

### 2.1 LoopAudioController(状态与数据)

```kotlin
@Singleton
class LoopAudioController @Inject constructor() {
    data class Region(val window: LoopWindow, val sampleRate: Int, val bytesPerFrame: Int,
                      val channels: Int, val pcmIsFloat: Boolean)

    @Volatile var region: Region?          // null = 不无缝(纯直通);set 由服务层在窗口/格式变化时写入
    val isReplaying: Boolean               // 调试/E2E 断言
    val ringFrames: Long                   // 已捕获帧数
    val seamCount: Long                    // 环回次数(debug 打点)

    // 以下由 LoopAudioSink(音频线程)调用,内部 synchronized
    fun currentRegion(): Region?
    fun ensureRing(region: Region)         // 分配/复用 ring(容量 = L_frames * bytesPerFrame)
    fun capture(frameOffsetInRegion: Long, src: ByteBuffer, srcOffset: Int, frames: Int)
    fun readRing(frameOffset: Long, dst: ByteBuffer, frames: Int)
    fun isRingComplete(): Boolean          // 是否已覆盖 [A,B) 全域
    fun invalidateRing()
}
```

- ring = `ByteArray(L_frames * bytesPerFrame)`;**容量上限 64MB / 300s**(先到者),超限则该歌降级(region=null + 明确提示)。
- `Region` 用 `window` 的 identity + sampleRate + bytesPerFrame 判定是否需要重建 ring(range 变 → 重建)。

### 2.2 LoopAudioSink(唯一与音频管线交互的类)

状态机(每首歌重置):

```
PASS_THROUGH ──(region 非空 & 采样时间进入 [A,B))──▶ CAPTURING ──(输入越过 B)──▶ REPLAYING
      ▲                                                   │                        │
      └──────────── flush()/reset()/region=null ◀──────────┴────────────────────────┘
```

**configure(Format, bufferSize, outputChannels)**:记录 sampleRate/channels/pcmEncoding/bytesPerFrame,
写入 controller;转发 delegate。

**handleBuffer(buf, ptsUs, frames)**:
1. `region == null` → 直通。
2. 把 buf 按 `srcFrameTimeUs = ptsUs + i * 1e6 / sampleRate` 映射到媒体时间;
3. CAPTURING:
   - 计算 `from = max(0, ceil((A - ptsUs) * sr / 1e6))`、`to = min(frames, ceil((B - ptsUs) * sr / 1e6))`;
   - `controller.capture((srcTime - A) 的帧偏移, buf, from, to-from)`;
   - 只把 `[0, to)` 段交给 delegate(裁掉 B 之后的部分);
   - 若 `to < frames`(本 buffer 越过 B)→ 进入 REPLAYING,随后 `pump()`;
4. REPLAYING:输入**全部消费但不写**(advance buffer position、返回 true),调用 `pump()`。
5. 返回 true(已消费)。

**pump()**(只在音频线程调用):
```kotlin
while (true) {
    val room = min(剩余本轮帧数, ring 可读帧数, 每次喂送上限)
    if (room == 0) break
    fillReuseBuffer(ring, cursor, room)          // 复用同一个 direct LE ByteBuffer
    val pts = baseUs + (cursor / sr) * 1_000_000  // 新的单调时间戳
    if (!delegate.handleBuffer(reuseBuf, pts, 1)) break   // 未吃完:保留 buf 与游标,下次重试
    cursor += room; totalFrames += room
    if (cursor == L_frames) { cursor = 0; iteration++; controller.onSeam(); }  // 打点 + E2E
}
```

**playToEndOfStream()**:
- REPLAYING → `pump()` 后**直接返回**(不调 delegate 的)。renderer 会以 ~100Hz 反复回调它,即为泵。
- 否则 → 转发 delegate。

**isEnded()**:REPLAYING → `pump()` 后返回 `false`(delegate 永不被 drain);否则转发。

**getCurrentPositionUs(sourceEnded)**:**直接转发 delegate**(位置原生前进 = 重放帧数)。折返由会话层做。

**flush() / reset() / release()**:置 PASS_THROUGH、清 pending 与游标(ring 保留,region 不变时下次仍可用),转发 delegate。

**handleDiscontinuity / setOutputStreamOffsetUs / setPlaybackParameters / setVolume**:转发(循环态不应该被调用到,出现即打日志)。

### 2.3 LoopRenderersFactory

```kotlin
class LoopRenderersFactory(ctx: Context, private val controller: LoopAudioController)
    : DefaultRenderersFactory(ctx) {
    override fun buildAudioSink(context: Context, floatOutput: Boolean, playbackParams: Boolean): AudioSink =
        LoopAudioSink(controller, DefaultAudioSink.Builder(context).build())
}
```

接入点:`MediaModule.provideExoPlayer` 增加 `.setRenderersFactory(LoopRenderersFactory(context, controller))`。

### 2.4 服务层接线(LoopPlaybackCoordinator,薄)

1. **唯一状态来源**:`LoopRuntime.window`;事件(onMediaItemTransition / onRepeatModeChanged / 行变更 / restore 完成)只调用
   `refresh(songId, repeatMode, row)`(= 一次纯函数重算)。
2. **窗口变化 → 应用形态**(唯一会打断播放的动作,只发生在显式状态变化上):
   - 窗口非空 → `controller.region = Region(...)`;`MediaItem` 加 `ClippingConfiguration(start=0, end=B)`;
     从 `rawSeekUs(display(currentPos))` 起播。
   - 窗口为空 → `controller.region = null`;`MediaItem` 去 clip;从 `display(currentPos)` 起播。
   - 幂等:窗口未变且 item 形态一致 → 不动作(不做任何 media 操作)。
3. **位置口径**:`sessionPlayer`(ForwardingPlayer)重写
   `getCurrentPosition/getBufferedPosition → LoopPosition.displayUs`、
   `seekTo(index, ms) → LoopPosition.rawSeekUs`、
   `getDuration → 无缝时返回整曲时长`(否则原生)。
   **不重写** `play/pause/setMediaItem/repeat` 等任何状态方法 —— 播放器状态机保持权威。
4. **前台服务/通知/耳机键/上一首下一首/随机/队列**:零改动(走原生路径)。
5. **降级**:ring 超上限、解码失败、ring 不完整且补不上 → `controller.region = null` + 一次性提示;
   播放不中断(整曲单曲循环)。

## 3. 边界情形(必须逐条有测试)

| 情形 | 处理 |
|---|---|
| 从 0 播,前奏到 B | 捕获在 [A,B) 段完成;越 B 进 REPLAYING |
| seek 到区间中部 | ring 不完整 → **优先后台预解码补齐 [A,x)**(复用 PcmDecoder);补不上则到 B 时 seek 回 A 降级一轮(打 degraded 事件) |
| seek 到 A 之前 | 正常播前奏,到 B 进重放 |
| seek 到 B 之后 | `rawSeekUs` 折返到 A+phase |
| 暂停 / 恢复 | delegate pause;泵停止(playToEndOfStream 不再被调用);恢复后续跑 |
| 音频焦点丢失 / 来电 | 同上,由系统 pause;恢复后继续 |
| 切歌 | 新歌 refresh;窗口不同 → 重建 item |
| 取消最终结果 / 删除行 | 窗口 → null;重建 item 并从 display 位置续播 |
| 改 A/B(重新分析确认) | region 重建 + ring 失效 + item clip 重建 |
| A/B 区间 < 1 个解码帧 | ring 过短 → 降级(避免空环) |
| 曲尾 B ≥ 曲长 | 正常(EOS 处切重放) |
| 睡眠定时 / 播放结束 | 原生(重放永不结束,睡眠定时器到点 pause 即可) |
| 杀进程重启 | restore 完成后 refresh 一次;位置按 display 持久化 |

## 4. 可测性(与 L1/L3 对接)

- **L1(fake delegate)**:确定性验证 —— 按 PTS 切帧精度(±0 帧)、越 B 切状态、pump 的折返与时间戳单调、flush 清态、
  ring 不完整的降级路径。fake delegate 记录每次 `handleBuffer` 的 (frames, ptsUs, bytes) → 与理想序列逐帧比对。
- **L3(真机 tap)**:不变 —— 输出 PCM 落 WAV,判 A1/A2/A4(此时应全 PASS)与 B(逐轮逐位相同)。
- **打点**:每轮 `LoopPerf seam iter=<k> outFrames=<B+kL> wallMs=<t>`,供漂移判定与 E2E 断言。

## 5. 与旧实现的差异(为什么不会再出那类 bug)

| 旧实现 | M3′ |
|---|---|
| apply/teardown 时间线 + 重入守卫 + 轮询 | 窗口 = 一个 volatile 值;变化时最多一次 item 重建,幂等 |
| 引擎改 repeat,影子变量恢复 | 引擎从不碰 repeat |
| UI 标记、引擎状态、DB 行各自推断 | 三者同读 `LoopRuntime.window` |
| 环回点 = seek | 环回点 = 同一 sink 的 PCM 折返,无边界事件 |
| 位置靠 ExoPlayer 的 seek 语义 | 位置原生前进 + 一层纯函数折返 |
