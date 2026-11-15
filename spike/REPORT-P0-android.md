# P0 探针 · Android 侧实现与真机取证报告

> 设备:OnePlus 8T(KB2000)/ Android 14(sdk 34)/ 扬声器输出 / 44.1kHz MP3 与 FLAC。
> 运行时间:2026-11-15。产物:spike/out/<mode>-<tag>/{latest.wav,latest.jsonl,run.json,logcat.txt}

## 0. 结论(对应 CONTRACT §4 的四个关键未知)

| # | 问题 | 结论 |
|---|---|---|
| 1 | plain 正常出声、取证管线自洽? | **是**。WAV 帧数 == 所有 feed.frames 之和(5 个 run 全部逐帧相等);PCM 有真实波形(RMS 最高 ~4100/32768);AudioTrack head 正常推进;全段 underrun 增量 0。 |
| 2 | baseline 接缝处 flush/EOS/PTS 复位序列? | **没有 flush()、没有 EOS**。序列是:`output_stream_offset(+L)` → `handleDiscontinuity()` → `configure()`(同格式,AudioTrack 未重建)→ 新轮首样本 pts = offset+A(+15.5ms)→ ~390ms 后 `position_discontinuity(auto_transition,10000→0)`。接缝 PCM 有 **1224 帧全零(27.8ms)**,且每轮多 **216 帧(4.9ms)**。 |
| 3 | window 推进下一窗口是否 flush? | **不调用 AudioSink.flush(),但每个窗口都重新 configure() + handleDiscontinuity()**。MP3 与 baseline 完全同形(1224 帧零 + 216 帧/轮);**FLAC 无零段,但每轮多 1368 帧(31ms)**。→ 自带 gapless 路径也不能做到"零漂移/零重配"。 |
| 4 | sink:EOS 后 renderer 还喂 buffer 吗? | **不喂**。EOS 前最后一个 feed 之后,renderer 只以 ~100Hz 调 `playToEndOfStream()`/`isEnded()`,0 次 handleBuffer。**但 sink 自己可以继续喂**:EOS 后不 flush、直接在独立线程喂同一 AudioTrack,连续输出 13s,underrun 0,轮间帧距 0 误差。M3 不是"出局",而是必须自带喂数线程 + 吞 EOS + 位置映射。 |

## 1. 实现文件

| 路径 | 作用 |
|---|---|
| `spike/src/main/java/com/zjr/spike/SpikeActivity.kt` | 纯 android.app.Activity;解析 intent extras;按 mode 构源;计时/seek/收尾;Player.Listener 打点;error 必落盘 |
| `spike/src/main/java/com/zjr/spike/TapAudioSink.kt` | ForwardingAudioSink 包装 DefaultAudioSink:configure/feed/flush/handleDiscontinuity/playToEndOfStream/isEnded/hasPendingData/setOutputStreamOffsetUs/getCurrentPositionUs 打点;PCM 落 WAV;sink 模式捕获 [A,B) 并在 EOS 后原地重放;seam 打点 |
| `spike/src/main/java/com/zjr/spike/TrackProbeProvider.java` | DefaultAudioSink.AudioTrackProvider(Java;Kotlin K2 对该 interface 的 @Nullable 参数报 overrides nothing),每 500ms 采 underrun/headFrames/state |
| `spike/src/main/java/com/zjr/spike/TapRenderersFactory.kt` | DefaultRenderersFactory.buildAudioSink 注入 TapAudioSink |
| `spike/src/main/java/com/zjr/spike/SpikeRecorder.kt` | JSONL 单锁顺序写(t 相对 play();附 seq);WAV 创建;总帧数统计 |
| `spike/src/main/java/com/zjr/spike/WavWriter.kt` | 44 字节头 + 顺序 PCM,收尾回填 RIFF/fmt/data;支持 pcm8/16/24/32 与 float32 |
| `spike/src/main/java/com/zjr/spike/SpikeConfig.kt` / `SpikeRun.kt` | 参数模型 / 运行级 tap 开关 |
| `tools/spike-run.ps1` | 一键:拷贝设备音频→app 私有目录、force-stop、am start、轮询 end 事件、拉取 WAV/JSONL/logcat 到 spike/out/<mode>-<tag>/ |

与主 app 的构建方式已对齐(di/MediaModule.kt):`DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)`、`DefaultMediaSourceFactory(context, extractors)`、`AudioAttributes(USAGE_MEDIA, CONTENT_TYPE_MUSIC)` + handleAudioFocus + handleAudioBecomingNoisy。
**设备限制**:该 ROM 上 `adb shell pm grant` 被拒(SecurityException: shell 无 GRANT_RUNTIME_PERMISSIONS),因此 run 脚本用 `cat '/sdcard/Music/...' | run-as com.zjr.spike sh -c 'cat > files/media/xxx'` 把音频复制进 app 私有目录后按绝对路径播放(不引入权限、不改 app)。

## 2. 运行命令(全部经 tools/spike-run.ps1)

```powershell
# plain(对照)
tools\spike-run.ps1 -Mode plain -Audio '/sdcard/Music/(01)ANGEL.mp3' -Seconds 12 -Tag plainB
# baseline(复刻上一版:两项 clip,从 index=1 起播,REPEAT_MODE_ONE)
tools\spike-run.ps1 -Mode baseline -Audio '/sdcard/Music/(01)ANGEL.mp3' -AMs 5000 -BMs 15000 -Seconds 35 -Tag a5b15
# window(LoopingMediaSource(ClippingMediaSource))
tools\spike-run.ps1 -Mode window -Audio '/sdcard/Music/(01)ANGEL.mp3' -AMs 5000 -BMs 15000 -Loops 100 -Seconds 35 -Tag a5b15
tools\spike-run.ps1 -Mode window -Audio '/sdcard/Music/(01) [Galneryus] United Blood.flac' -AMs 5000 -BMs 15000 -Loops 100 -Seconds 35 -Tag flac-a5b15
# sink(M3)
tools\spike-run.ps1 -Mode sink -Audio '/sdcard/Music/(01)ANGEL.mp3' -AMs 5000 -BMs 15000 -Seconds 28 -Tag a5b15h2
```

## 3. 关键证据(JSONL 原文,已按契约字段语义打点)

### 3.1 plain(取证管线自洽)
```jsonl
{"t":0,"seq":1,"ev":"run","mode":"plain","aMs":20000,"bMs":50000,"seconds":12,"tap":true,"tag":"plainB",...}
{"t":218,"seq":15,"ev":"configure","sampleRate":44100,"channels":2,"pcm":"pcm16","bytesPerFrame":4,...}
{"t":232,"seq":16,"ev":"track_new","sampleRate":44100,"encoding":2,"channelConfig":12,"bufferSize":56576,"state":1}
{"t":243,"seq":17,"ev":"feed","frames":623,"ptsUs":1000000000000,"bytes":2492,"ret":true,"afterEos":false}
{"t":11826,"seq":1484,"ev":"track","underrun":0,"headFrames":511560,"state":"playing"}
{"t":12068,"seq":1516,"ev":"end","totalFrames":534524,"durationMs":12068,"wavBytes":2138096,"reason":"duration"}
```
自洽校验(5 个 run 全部相等):plainB wavFrames=534524 = Σfeed.frames;baseline=1547060;window-mp3=1547060;window-flac=1545296;sink=1238568(其中 replay 576168)。

### 3.2 baseline(REPEAT_MODE_ONE 接缝;L=10s)
```jsonl
{"t":9965,"seq":1261,"ev":"output_stream_offset","offsetUs":1000010000000}
{"t":9966,"seq":1262,"ev":"discontinuity","lastPtsUs":1000015008374,"posUs":1000014600254}
{"t":9995,"seq":1263,"ev":"configure","sampleRate":44100,"channels":2,"pcm":"pcm16","bytesPerFrame":4,...}
{"t":10005,"seq":1265,"ev":"seam","iter":1,"outFrames":441216,"ptsUs":1000015015472,"src":"baseline","trigger":"offset"}
{"t":10392,"seq":1320,"ev":"position_discontinuity","reason":"auto_transition","oldMs":10000,"newMs":0,"oldItem":1,"newItem":1}
```
- 无 `flush`、无 `eos_out`/`play_to_eos`、`track_new` 只有 1 次(AudioTrack 复用)。
- A1 FAIL:每接缝 1224 帧全零(27.755ms);A2 FAIL:每轮 441216 帧,理论 441000,偏差 +216 帧(4.9ms);A3 总帧 1547060 vs 期望 1543500(+3560);A4 FAIL:稳态 discontinuity=3;underrun 增量 0。
- 说明:上一版 R1 的"REPEAT_ONE=seek→flush"推断在 1.8.0 上不成立;间隙来自**解码器在窗口边界重新出流**(零填充+多帧),而非 AudioSink.flush。

### 3.3 window(LoopingMediaSource;MP3 与 FLAC 同参数)
MP3 与 baseline **逐事件同形**:
```jsonl
{"t":9969,"seq":1228,"ev":"output_stream_offset","offsetUs":1000010000000}
{"t":9970,"seq":1229,"ev":"discontinuity","lastPtsUs":1000015008374,"posUs":1000014596079}
{"t":9990,"seq":1230,"ev":"configure","sampleRate":44100,"channels":2,"pcm":"pcm16",...}
{"t":9998,"seq":1232,"ev":"seam","iter":1,"outFrames":441216,"ptsUs":1000015015472,"src":"window","trigger":"offset"}
{"t":10394,"seq":1290,"ev":"position_discontinuity","reason":"auto_transition","oldMs":10000,"newMs":0,"oldItem":0,"newItem":1}
```
FLAC:
```jsonl
{"t":9995,"seq":1033,"ev":"output_stream_offset","offsetUs":1000010000000}
{"t":9997,"seq":1034,"ev":"discontinuity","lastPtsUs":1000014953650,"posUs":1000014604341}
{"t":10029,"seq":1035,"ev":"configure","sampleRate":44100,"channels":2,"pcm":"pcm16",...}
{"t":10043,"seq":1037,"ev":"seam","iter":1,"outFrames":442368,"ptsUs":1000015015510,"src":"window","trigger":"offset"}
```
- 两格式都 **0 次 flush()**,但每个窗口 **configure + handleDiscontinuity** 各 1 次,AudioTrack 未重建(track_new=1)。
- MP3:A1 FAIL(1224 帧零)、A2 FAIL(+216 帧/轮);FLAC:A1 **PASS**(无零段)、A2 FAIL(+1368 帧/轮,31ms)。
- 结论:"ExoPlayer 自带 gapless 路径"在 ClippingMediaSource+LoopingMediaSource 形态下**不能**给出样品级无缝/零漂移。

### 3.4 sink(M3;捕获 [5s,15s),clip [0,15s))
```jsonl
{"t":14954,"seq":1866,"ev":"play_to_eos","capturedFrames":441000,"ended":false,"pending":true,"posUs":1000014594655}
{"t":14965,"seq":1868,"ev":"looping","on":true,"aMs":5000,"bMs":15000,"ringFrames":441000,"ringBytes":1764000}
{"t":14969,"seq":1869,"ev":"replay_start","frames":441000,"loopUs":10000000,"chunkBytes":4096}
{"t":14980,"seq":1873,"ev":"seam","iter":1,"outFrames":662400,"ptsUs":1000015018412,"src":"replay","trigger":"replay_start"}
{"t":24944,"seq":4414,"ev":"seam","iter":2,"outFrames":1103400,"ptsUs":1000025018412,"src":"replay","trigger":"replay_start"}
{"t":27512,"seq":5059,"ev":"track","underrun":0,"headFrames":552132,"headUs":12520000,"state":"playing"}
{"t":28020,"seq":5186,"ev":"track","underrun":0,"headFrames":575064,"headUs":13040000,"state":"playing"}
{"t":28083,"seq":5198,"ev":"end","totalFrames":1238568,"durationMs":28083,"wavBytes":4954272,"reason":"duration"}
```
- **EOS 之后 renderer 0 次 handleBuffer**(EOS 前最后一个是 pts=1000015008412 的 529 帧 padding),只反复调 playToEndOfStream(本 run 1203 次,~100Hz),player 保持 READY 不 ENDED(靠 tap 的 isEnded()=false)。
- **sink 自行续喂成功**:不 flush、独立线程、连续输出 13s;轮间 seam 帧距 = 441000(0 漂移);underrun 全程 0;flush 仅初始与收尾两次;track_new=1(未重建 AudioTrack)。
- 踩到的三条硬约束(否则 M3 不可行):① buffer 必须 `ByteOrder.LITTLE_ENDIAN`(DefaultAudioSink.handleBuffer 内 checkArgument);② 未吃完时必须复用**同一个** ByteBuffer 实例重试;③ `flush()` 必须在有 Looper 的 playback 线程调用(在自带线程调会 NPE)。
- 代价:AudioTrack 在 EOS 处被 stop()→play(),playback head 归零,ExoPlayer 位置在 ~16s 冻结;EOS→第一段 replay feed 的墙钟间隔 ~30ms(靠 56KB AudioTrack 缓冲兜住,underrun=0)。首个接缝仍含 529 帧源端 padding(导出侧 A1 FAIL,与接缝无关的那段属于 clip 尾 padding)。

## 4. 分析器(lead 的 tools/spike-analyze.ts)判据汇总

| run | A1 | A2 | A4 | B | 备注 |
|---|---|---|---|---|---|
| plainB | PASS(空真) | PASS(空真) | FAIL(喂数间隔 47ms>30ms) | SKIP | underrun 全段 0;47ms 是 renderer 喂数节奏,非真 underrun |
| baseline mp3 | FAIL 1224 帧零 | FAIL +216 帧/轮 | FAIL discontinuity=3 | FAIL | |
| window mp3 | FAIL 1224 帧零 | FAIL +216 帧/轮 | FAIL discontinuity=3 | FAIL | |
| window flac | PASS | FAIL +1368 帧/轮 | FAIL discontinuity=3 | FAIL | 零段消失,漂移更大 |
| sink mp3 | FAIL(529 帧源端 padding) | **PASS(0 帧)** | FAIL 喂数间隔 49ms | FAIL(轮长不齐) | underrun 0、flush 0、discontinuity 0;轮间逐位相同理论上成立(同一 ring) |

## 5. 遗留问题 / 风险

1. **analyzer 的 feed-间隔判据(≤30ms)对所有 mode 都 FAIL**,但 underrun 增量恒为 0、pending 数据始终存在;建议把 A4 的"喂数间隔"改为参考项(或只在 underrun 增量>0 时判 FAIL),否则 plain 也会红。
2. **PTS 连续性噪声**:delegate 未吃完时我们对同一 buffer 的每次"部分消费"都按契约记一条 feed(pts 相同、frames 不同),analyzer 会判成 561~1681 处 PTS jump。建议分析侧对 `ret=false` 的 feed 视为同一 buffer 的续写(或把 pts 连续性与 feed 去重)。
3. M3 若进 P1,需要额外解决:① 用 ring 前先截掉 B 之后的解码器 padding(否则 529 帧零);② EOS 前预喂(避免 stop()/play() 与 30ms 间隔);③ 位置时钟(getCurrentPositionUs/playhead stop 归零)必须走映射层;④ renderer 空转 100Hz 的 CPU 成本。
4. 本轮未验证:`tap=0`、`seekToMs`(seek 后环回)、`media`/M1、超长 run(>2min)、APE/AAC。
5. 设备环境:OEM 禁止 adb 授权,复现依赖 run 脚本的 run-as 拷贝;测试音频只读设备文件,未入仓库。

## 6. 产物

- 源码:`spike/src/main/java/com/zjr/spike/*`,脚本:`tools/spike-run.ps1`
- 证据:`spike/out/{plain-plainA,plain-plainB,baseline-a5b15,window-a5b15,window-flac-a5b15,sink-a5b15,sink-a5b15b,...,sink-a5b15h2}/`
  - sink 系列依次是:M3 演进证据(无 capture→capture 偏移修正→flush 尝试→little-endian→同 buffer 重试→playback 线程→异步线程)。最终有效证据为 `sink-a5b15h2`(异步、无 flush)。
- 每个目录含 latest.wav / latest.jsonl / run.json / logcat.txt;WAV 为 pcm16/44.1kHz/2ch,可直接用 analyzer 复算。
