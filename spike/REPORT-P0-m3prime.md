# M3' 探针报告:sink 层 PCM 环回(经 delegate 泵送)

> 设备:OnePlus 8T / Android 14 / 扬声器 / 44.1kHz。产物:spike/out/sink2-*/{latest.wav,latest.jsonl,run.json,logcat.txt}
> 判据工具:tools/spike-analyze.ts(spike-analyze/2)。

## 0. 结论(对应 lead 的 4 个判据问题)

| 问题 | 实测结论 |
|---|---|
| 重放期间 delegate.getCurrentPositionUs() 是否持续前进? | **是,且连续**。MP3 20/50:重放从 t=49.9s 起,pos 从 49.6s 一路前进到 **114.5s**(wall 115.1s);5/15 跑:重放期 pos 15.0→34.3s(wall 35s)。仅首个 position_reset 是坐标基准切换(1e12+ → media time),不是真跳变。**但 player.currentPosition 仍被 ExoPlayer 钳在 duration=clip B**,会话层必须提供自己的位置映射(getDuration 覆盖 + 位置折返/取自 sink)。 |
| 重放期间 renderer 是否仍 ~100Hz 调 playToEndOfStream/isEnded? | **是,泵可持续**。MP3 20/50 的 65s 重放里 playToEndOfStream 被调 **4500 次(≈69Hz)**,另有 isEnded 泵;5/15 跑 1000 次。 |
| 重放期间出现 configure/discontinuity/flush? | **0 / 0 / 0**。全部非 seek run:configure=1(仅初始)、discontinuity=0、flush=2(初始+收尾),track_new=1。 |
| underrun 增量 / track_new 次数? | **underrun 全程 0(增量 0)、track_new=1**(无 AudioTrack 重建)。 |

**核心判据(analyzer)**:
- MP3 a=20/50: A1 **PASS**、A2 **PASS(帧距 1323000,偏差 0)**、A4 硬判据 PASS。
- FLAC a=20/50: A1 **PASS**、A2 **PASS(1323000,偏差 0)**、A4 硬判据 PASS。
- 另:MP3 5/15 三轮、seek 后三轮、pause/resume 后三轮,**全部 A1/A2 PASS、轮间 PCM 逐位相同(bitIdentical=true)**。
- 长跑 300s(tap=0):**29 个接缝,28 个帧距全部 = 441000(0 帧误差,零漂移不累积)**;flush=2、configure=1、discontinuity=0、underrun=0。

## 1. 实现要点(与旧 sink 原型的差别)

文件:spike/src/main/java/com/zjr/spike/TapAudioSink.kt(sink 分支重写)、SpikeActivity.kt(降级 seek / pause-resume / 新 extras)、tools/spike-run.ps1(-DirName/-PauseAtMs/-PauseForMs)。

1. **输入在 B 处截断**:MediaItem 用 ClippingConfiguration.setEndPositionMs(B);handleBuffer 对跨越 B 的最后一个 PCM buffer 按帧裁到 B(trim_at_b),之后输入一律 position=limit; return true 丢弃(feed_discard),让 renderer 继续跑。
2. **切换点在 B,不等 EOS**:ring([A,B) 精确捕获)完整后立刻开始重放;EOS 到来时 playToEndOfStream() 已被我们接管。
3. **泵送**:playToEndOfStream() 里 **不调用 delegate 的同名方法**,只把 ring 写进 delegate 然后返回;isEnded() 里也兜底泵一次并返回 false。delegate 永不进入 ended 态 → AudioTrack 不 stop。
4. **捕获按 PTS 精确切帧**:baseFrame = (ptsUs - outputOffset) * sr / 1e6,只取 [aFrame,bFrame) 的帧;ring 长度严格 = L 帧(20/50 时 1,323,000;5/15 时 441,000)。
5. **重放 PTS 单调**:第 k 轮 = B + (k-1)*L + 环内偏移;seam 事件 ptsUs 即该轮首样本时间戳、outFrames 为累计已写 delegate 帧数(按契约 2026-11-15 裁定)。
6. 硬约束(全部踩过并修复):buffer 必须 LITTLE_ENDIAN;未吃完必须复用同一 ByteBuffer 实例;flush() 必须发生在 playback 线程(本实现不在重放期 flush);release()/stopReplay() 置 disabled,防止收尾期误重启(第一版曾在 stop 后多打一个 seam,已修)。
7. **ring 不完整降级**:seek 进 [A,B) 后 ring 只有后半段 → 打 replay_degraded 并让 Activity seekTo(A-250ms) 预卷补一整轮(直接 seek A 会因解码 seek 精度落到 A+15.5ms,ring 少 682 帧永远不完整 —— 这是首版 seek 测试死循环的原因)。

## 2. 关键 JSONL 原文

MP3 a=20/50(sink2-mp3-a20b50):
~~~jsonl
{"t":49900,"seq":6156,"ev":"trim_at_b","ptsUs":1000049986371,"trimmedBytes":2200,"trimmedFrames":550}
{"t":49917,"seq":6159,"ev":"looping","on":true,"aMs":20000,"bMs":50000,"ringFrames":1323000,"ringBytes":5292000,"mode":"delegate-pump","startPtsUs":50000000}
{"t":49921,"seq":6160,"ev":"seam","iter":1,"outFrames":2205001,"ptsUs":50000000,"src":"sink2","trigger":"pump"}
{"t":49959,"seq":6166,"ev":"pump","via":"play_to_eos","calls":1,"replayActive":true,"round":1,"offset":7180}
{"t":79906,"seq":9625,"ev":"seam","iter":2,"outFrames":3528001,"ptsUs":80000000,"src":"sink2","trigger":"pump"}
{"t":109912,"seq":13085,"ev":"seam","iter":3,"outFrames":4851001,"ptsUs":110000000,"src":"sink2","trigger":"pump"}
{"t":51509,"seq":6349,"ev":"pos","us":51155639,"round":1,"ringOffset":282364}
{"t":114873,"seq":13658,"ev":"pos","us":114518443,"round":3,"ringOffset":875068}
{"t":115067,"seq":13680,"ev":"stop","reason":"duration","posMs":49970,"sinkEnded":false,"sinkPending":true,"replayActive":true,"totalFrames":5078329}
{"t":115126,"seq":13686,"ev":"end","totalFrames":5078588,"durationMs":115126,"wavBytes":20314352,"reason":"duration"}
~~~
长跑 300s(sink2-long-a5b15,tap=0):29 seams,相邻 outFrames 距离全部 441000;flush=2 / configure=1 / discontinuity=0 / errors=0;末条 track underrun=0 headFrames=13207068。
seek 降级(sink2-seek-a5b15b):
~~~jsonl
{"t":5824,"seq":769,"ev":"replay_degraded","capturedFrames":131581,"expectedFrames":441000,"action":"seek-to-A"}
{"t":5831,"seq":770,"ev":"degrade_seek","toMs":4750,"ringStartMs":5000}
{"t":15852,"seq":2020,"ev":"looping","on":true,"aMs":5000,"bMs":15000,"ringFrames":441000,"ringBytes":1764000,"mode":"delegate-pump","startPtsUs":15000000}
{"t":15859,"seq":2021,"ev":"seam","iter":1,"outFrames":719279,"ptsUs":15000000,"src":"sink2","trigger":"pump"}
{"t":25850,"seq":3175,"ev":"seam","iter":2,"outFrames":1160279,"ptsUs":25000000,"src":"sink2","trigger":"pump"}
{"t":35849,"seq":4331,"ev":"seam","iter":3,"outFrames":1601279,"ptsUs":35000000,"src":"sink2","trigger":"pump"}
~~~
pause/resume(sink2-pause-a5b15,pauseAt=20s、pauseFor=3s):
~~~jsonl
{"t":20011,"seq":2449,"ev":"pause_request","posMs":14999,"replayActive":true}
{"t":23010,"seq":2467,"ev":"resume_request","posMs":14999,"replayActive":true}
{"t":27892,"seq":3028,"ev":"seam","iter":2,"outFrames":1102501,"ptsUs":25000000,"src":"sink2","trigger":"pump"}
{"t":37892,"seq":4182,"ev":"seam","iter":3,"outFrames":1543501,"ptsUs":35000000,"src":"sink2","trigger":"pump"}
~~~
重放期 pos 在暂停时停在 19.76s(resume 前),恢复后继续 20.2→22.8s;无 position_reset/无跳变,seam 帧距仍精确 441000。

## 3. 与判据/分析器的口径说明

- **A3 的"前奏"假设**:analyzer 按 baseline 语义取 前奏 = A(20s),而本实现从 0 起播、clip 到 B,前奏是 [0,B)(50s),所以 A3 报"首个接缝比理论前奏多一整个 L" —— 这是口径差,不是丢帧。建议 analyzer 对 sink2 用"前奏 = B"或从首个 seam 起算。
- **A4 的 feed-gap**:重放期若暂停,feed 间隔 2s(正常);非暂停 run 40~50ms,但 delegate+AudioTrack 56KB 缓冲下 underrun 恒 0,建议维持参考项。
- **B(逐位相同)**:analyzer 因末轮被 stop 截断而 FAIL;手工逐字节比较前两个完整重放轮 **完全相同**(mp3-20/50、flac-20/50、5/15、seek、pause 全部 bitIdentical=true)。建议 analyzer 只比较完整轮。
- 每次运行末尾 stop 之后、end 之前仍会有 1~5k 帧被泵入(收尾竞态);不影响接缝数据。

## 4. 遗留问题 / 风险

1. **player 位置被钳在 clip B**:引擎位置(sink)连续前进,但 ExoPlayer 对外 position/duration 基于 clip 时间线。P1 必须由会话层提供位置(或把 item duration 做投影),否则通知/进度条停在 B。
2. **ring 完整性依赖 [A,B) 至少完整播过一遍**:seek 进区间需回跳补 ring(已验证,代价一次 seek+flush);若产品要求"seek 后立即无缝",需要在后台解码补 ring(P1 的 PcmDecoder 路径)。
3. **重放期 CPU**:泵由 renderer 轮询驱动(~70~100Hz),无需自建线程/定时器。
4. **flush/reset 语义**:当前 flush 会清 ring(seek 场景需要);若 P1 希望"窗口不变时保留 ring",需要区分 seek 原因(可加 onPositionDiscontinuity 通知)。
5. **M1 状态**:已按指示停手。停止前最后一次运行已成功切到第 1 轮(source_switch iter=1 base=15s),随后在 getNextLoadPositionUs 查询未 prepared 的子 period 时命中 ProgressiveMediaPeriod.assertPrepared 崩溃;代码保留于 spike/src/main/java/com/zjr/spike/loopsource/,不再投入。

## 5. 产物清单

| 目录 | 内容 |
|---|---|
| spike/out/sink2-mp3-a20b50 | MP3 20/50 115s,A1/A2 PASS,B bitIdentical |
| spike/out/sink2-flac-a20b50 | FLAC 20/50 115s,A1/A2 PASS,B bitIdentical |
| spike/out/sink2-smoke2 | MP3 5/15 35s(对照) |
| spike/out/sink2-long-a5b15 | 300s 长跑,tap=0,29 seams 全 441000 |
| spike/out/sink2-seek-a5b15b | seek 12000ms → 降级回 A-250ms → 3 seams 0 漂移 |
| spike/out/sink2-pause-a5b15 | pause 20s / resume 23s → 3 seams 0 漂移 |
