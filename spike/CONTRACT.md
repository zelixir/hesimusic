# P0 探针契约(loop-tap spike)

> 本文件是 P0 阶段两个并行任务的**唯一接口契约**。修改契约必须先告知 lead。
> 目标:用**客观 PCM 取证**判定哪种环回机制能做到"真无缝",为 P1 定选型。

## 1. 模块与运行方式

新增独立 Gradle 模块 `:spike`(临时探针,不进正式 APK),包名 `com.zjr.spike`,仅依赖 media3-exoplayer。
探针不接入主 app 的 Hilt/MusicService,自成一体:一个 Activity 按 intent 参数播一段音频,并把
**真正喂给 AudioTrack 的 PCM** 与**音频 sink 的每一次调用**落盘。

    # 安装
    .\gradlew.bat :spike:installDebug
    # 运行(例)
    adb shell am force-stop com.zjr.spike
    adb shell am start -n com.zjr.spike/.SpikeActivity \
      --es mode baseline \
      --es path /sdcard/Music/<测试曲> \
      --ei aMs 20000 --ei bMs 50000 \
      --ei seconds 25 --ei tap 1
    # 取证产物(应用内部目录,用 run-as 拉取)
    adb exec-out run-as com.zjr.spike cat files/spike/latest.wav  > tap.wav
    adb exec-out run-as com.zjr.spike cat files/spike/latest.jsonl > tap.jsonl

- 测试音频:**复用设备上已有的曲目**(/sdcard/Music 下 MP3 与 FLAC),不要往仓库加音频文件。
- 权限:`READ_MEDIA_AUDIO`(SDK 33+)需 `adb shell pm grant com.zjr.spike android.permission.READ_MEDIA_AUDIO`。
- 固定输出文件名 `files/spike/latest.wav`、`files/spike/latest.jsonl`(每次运行覆盖),便于脚本化。

## 2. Activity 参数(intent extras)

| key | 类型 | 默认 | 说明 |
|---|---|---|---|
| `mode` | String | `plain` | `plain` / `baseline` / `window` / `sink` / `media` |
| `path` | String | — | 音频文件绝对路径 |
| `aMs` | int | 20000 | 循环起点 |
| `bMs` | int | 50000 | 循环终点 |
| `seconds` | int | 25 | 播放时长(到时自动停止并收尾) |
| `loops` | int | 100 | `window` 模式的窗口重复次数 |
| `tap` | int | 1 | 1=落盘 PCM,0=只落日志 |
| `seekToMs` | int | -1 | >0 时在开始播放 N ms 后 seek 到该位置(测 seek 后环回) |
| `tag` | String | `default` | 写入日志,便于区分多轮实验 |

**行为约定**:播放 start 时刻为 t=0;到时停止、finalize WAV、写 `end` 事件、`finish()`;
全程任何异常都必须写 `error` 事件(含堆栈)后结束,**不得静默退出**。

各 mode 语义(必须严格按此实现,它们是待比较的候选机制):

- `plain`:单个 MediaItem 整曲播放(对照组,验证取证管线本身)。
- `baseline`:**复刻上一版实现** —— `setMediaItems([clip(0,bMs), clip(aMs,bMs)], 1, 0)` + `repeatMode = REPEAT_MODE_ONE`。
- `window`:`LoopingMediaSource(ClippingMediaSource(ProgressiveMediaSource(file), aMs..bMs), loops)` 经 `player.setMediaSource` 播放(测 ExoPlayer 的"推进到下一个窗口"是否 flush)。
- `sink`:单个 MediaItem clip(0,bMs) + 自定义 AudioSink(见 §4 M3):捕获 [A,B) 的 PCM 并原地重放,尝试吞掉 EOS。
  本 mode 的**首要目的**是记录 EOS 之后 renderer 的调用序列(是否还会喂 buffer),这是 M3 可行性的判据。
- `media`:M1 原型(自研环回 MediaSource)。**第一轮可先不实现**,由 lead 在 baseline/window/sink 结果出来后追加。

## 3. 取证产物

### 3.1 `latest.wav`

把 **每个 `handleBuffer` 收到的 PCM 按到达顺序**写成一个 WAV:
- 采样率/声道数/编码取自 `AudioSink.configure` 的 `Format`(可能是 pcm16 或 float32,按实际写,WAV 里用正确 format tag);
- 不做任何加工(不裁掉、不补零);
- 结束时回填 RIFF 头。

### 3.2 `latest.jsonl`

JSON Lines,**每行一个事件**,字段如下(`t` = 相对播放开始的毫秒):

```jsonl
{"t":0,"ev":"run","mode":"baseline","path":"...","aMs":20000,"bMs":50000,"seconds":25,"loops":100,"tag":"default"}
{"t":0,"ev":"configure","sampleRate":44100,"channels":2,"pcm":"pcm16","bytesPerFrame":4}
{"t":12,"ev":"feed","frames":4608,"ptsUs":1234567}
{"t":13,"ev":"flush"}
{"t":14,"ev":"eos_out"}
{"t":15,"ev":"play_to_eos"}
{"t":16,"ev":"looping","on":true,"aMs":20000,"bMs":50000,"ringFrames":1323000}
{"t":17,"ev":"seam","iter":3,"outFrames":1323000,"ptsUs":4000000}
{"t":100,"ev":"track","underrun":0,"headFrames":123456,"state":"playing"}
{"t":30000,"ev":"end","totalFrames":1234567,"durationMs":29980}
{"t":1000,"ev":"error","message":"...","stack":"..."}
```

要求:
- `feed.frames` = 本次**真正写进 delegate sink** 的帧数(不是 buffer 的原始帧数;若包装层做了拆分/丢弃要如实记);
- `feed.ptsUs` = 本次写进 delegate 的**时间戳**(机制层可能改写时间戳,这里必须是被改写后的值);
- `seam` 事件由机制层主动打点(得知轮次时);没有就省略;
  **字段语义(2026-11-15 裁定,消除歧义)**:`seam.outFrames` = **累计**已输出到 delegate 的帧数(自本次运行开始计,
  不是本轮帧数);`seam.iter` = 完成这一轮后的轮次序号(从 1 开始,首轮不需要打点);`seam.ptsUs` = 新一轮第一个样本喂给
  delegate 时使用的时间戳。分析器据此做"零漂移/逐轮逐位相同"判定。
- `flush` 覆盖 `AudioSink.flush()` 与 `handleDiscontinuity()`(后者单独记为 `{"ev":"discontinuity"}`);
- `track` 每 500ms 采一次:`underrun` 用 `AudioTrack.getUnderrunCount()`(经 `DefaultAudioSink.Builder.setAudioTrackProvider` 拿到 AudioTrack 引用),`headFrames` = `getPlaybackHeadPosition()`;
- 日志写入必须线程安全(playback 线程 + 采样线程),用单锁 + 顺序写。

## 4. 已知的关键未知(探针要回答的问题)

1. **REPEAT_ONE 的间隙有多大?**(baseline 基线)
2. **窗口推进(gapless 路径)是否 flush?**(window) —— 若 PASS,baseline 的间隙就是"REPEAT_ONE=seek"造成的,而不是 SDK 不能 gapless。
3. **EOS 之后 renderer 是否还会喂 buffer?**(sink) —— 若**不再喂**,M3(纯 sink 层环回)出局,除非配"永不结束的输入源"。
4. **PTS 是否允许跨轮次单调递增并被音频管线接受?**(media/M1)

## 5. 判据(由 tools/spike-analyze.ts 自动判定)

对一次运行,输出以下指标 + PASS/FAIL:

- **A1 零间隙**:接缝处无静音插入(任何 ≥1 采样的零值段)。
- **A2 零漂移**:相邻接缝的输出帧距 = 理论 L 帧(@sr),误差 **0 帧**(允许 ±0)。
- **A3 零重放/丢帧**:总帧数 = 前奏 + N×L(基线允许说明偏差)。
- **A4 零 flush / 零 underrun**:稳态期 flush 计数 = 0,underrun 增量 = 0,最大喂数间隔 ≤ 30ms。
- **B 逐位相同**(理想档):每轮 PCM 与首轮逐位相同(机制层给了 `seam` 事件时才能分段比较,否则跳过并标注 skipped)。

## 6. 文件域(并发边界)

| 成员 | 独占写域 |
|---|---|
| spike-harness | `spike/**`、`settings.gradle.kts`(仅加 include)、`tools/spike-run.ps1` |
| spike-analyze | `tools/spike-analyze.ts`、`tools/spike-analyze-fixtures/**` |
| lead | `docs/**`、`app/**`、分支与合并 |

禁止改 `app/` 与 `gradle/libs.versions.toml`(需要新依赖先报 lead)。
