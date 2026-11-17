# 无缝循环「输出流畅度」测试方案

状态:已落地(脚本 + fixture 自测全绿);真机数据待跑。
写域:`tools/loop-tap-capture.ps1`、`tools/loop-smoothness.ts`、`tools/fixtures/loop-smoothness/`、本文件。

## 0. 这份方案解决什么

用户在日常机(1.7.5)上看无缝循环「音频不顺」,怀疑缓冲没建好。**"不顺"必须能被拆成可证伪的几类**,否则改代码只能靠猜:

| 现象 | 物理含义 | 本方案对应指标 |
|---|---|---|
| 掉一下再回来(几十 ms 的洞) | delegate 欠载 / ring 预填没追上 | `zeroRuns`、`rmsWindows` |
| 每个环回点「啪」一声 | 环路拼接点样本跳变(B 后接 A 不连续) | `clicks`(标 SEAM) |
| 随机位置的爆音 | 解码/写入/重采样损坏 | `clicks`(标非 SEAM) |
| 每轮长度不对、越跑越偏 | 环长错位 / 取素材错位 | `seamAlign` |
| 第 2 轮内容和第 1 轮不一样 | ring 被重播种/部分覆盖,不是同一段在循环 | `dupCheck` |

一句话:**tap 录的是"真正被写进 delegate 的 PCM",所以它可以当作"用户听到的输出"的替身**,对它的连续性做量化断言,就能把"不顺"落到具体机制上。

## 1. 复用的现有能力(不重造)

- `POST /debug/tap {"path": "/data/local/tmp/tap.pcm"}` → `LoopAudioController.setPcmTap(File)`:
  - 在 sink `configure` 时创建文件:**44 字节 WAV 头 + 原始 PCM**(`formatTag` 1=PCM / 3=float,`bits=bytesPerFrame*8/channels`);
  - 每段真正写进 delegate 的数据落盘(`tapPcm`),关闭时回填 `data` 长度(`closeTapLocked`);
  - 关 tap(`path:null`)或 sink release 时收尾。**tap 未正常关闭时 `data` 长度是 0**,分析脚本会按文件剩余长度解析。
- sidecar 事件文件:`<tap 文件名>.jsonl`(与 tap 同目录),每行一个 JSON:
  `{"t":ms,"seq":n,"ev":"..."}`,事件类型 `configure / run / seam / flush / degrade / end`。
  - `configure`:`sr / ch / bpf`
  - `run`:run 快照 `mode / aMs / bMs / sampleRate`(窗口变化会再写一条)
  - `seam`:`iter / outFrames / ptsUs`
  - `degrade`:`targetRawUs`; `flush` 无字段; `end`:`totalFrames / flushCount / degradeCount`
- 既有分析器 `tools/spike-analyze.ts`(A1 零间隙 / A2 零漂移 / A3 帧数账 / A4 flush / B 逐位相同)是**契约门禁**,面向"环回机制是否正确";本文的 `loop-smoothness.ts` 是**输出流畅度**视角,面向"听起来顺不顺"。两者互补,不互相替代;都通过时才算真无缝。

### 1.1 为什么 `seam.outFrames` 是帧精度的锚点

`LoopAudioSink` 在重放分支里的顺序是:先 `controller.tapPcm(...)` 落盘,再 `engine.onReplayChunkConsumed(...)`;接缝回调(`onEngineSeam`)发生在后者内部,而 tap 侧 `outFrames` 用的是"已写入 delegate 的帧数"。因此 `seam.outFrames` **恰好等于"下一帧即 ring[0]"的输出帧号**——这是分析脚本能给出"偏移样本数"而不是"毫秒级估计"的原因。

## 2. 录制:`tools/loop-tap-capture.ps1`

### 2.1 真机命令

```powershell
# 最快路径:歌已确认过环区间(A/B 从 /song/<id> 读)
powershell -NoProfile -File tools/loop-tap-capture.ps1 -SongId 40904

# 歌还没确认:脚本代跑 /analyze + /confirm(用候选 0)
powershell -NoProfile -File tools/loop-tap-capture.ps1 -SongId 40904 -AutoConfirm -Tag mp3-A20-B50

# 显式指定区间与时长
powershell -NoProfile -File tools/loop-tap-capture.ps1 -SongId 40904 -AMs 20000 -BMs 50000 -Seconds 100

# 只看流程不碰设备
powershell -NoProfile -File tools/loop-tap-capture.ps1 -SongId 40904 -AMs 20000 -BMs 50000 -DryRun
```

| 参数 | 默认 | 说明 |
|---|---|---|
| `-SongId` | 0 | 必填(0 时从 `/library/songs` 挑第一首,仅方便手测) |
| `-Pkg` | `com.zjr.hesimusic` | `run-as` 用的包名 |
| `-Seconds` | 0 = 自动 | 自动 = `max(20, ceil(3.5×L)+2)`,上限 `-MaxSeconds`;并补到「至少跨 3 个环回点」 |
| `-AMs/-BMs` | -1 = 读快照 | 显式环区间;不填则读 `/song/<id>` 的 `loopStartMs/loopEndMs` |
| `-SeekMs` | 自动 | 默认 `A + max(500ms, 20%×L)`,保证有前奏让它跨过 B 进重放 |
| `-TapPath` | `/data/local/tmp/tap.pcm` | 设备侧落盘路径;脚本用服务端返回的 `path` 为准 |
| `-AutoConfirm` | 关 | 无 A/B 时自动 `/analyze` → 轮询 `/analysis` → `/confirm useCandidate:0` |
| `-NoAnalyze` | 关 | 只采不算 |
| `-NoLogcat` | 关 | 不拉 LoopPerf 日志 |
| `-KeepDeviceFiles` | 关 | 默认采完删设备侧文件 |
| `-MaxSeconds` | 180 | 自动时长上限(环很大时防止录到天荒地老) |
| `-ProbeOnly` | 关 | 只跑 forward + `/state` + A/B + 计划就退出,不 tap / 不播放(先看会录多久、预计几个接缝) |
| `-AdbPath` | 自动 | 指定 adb 可执行文件(多 SDK/自测用) |
| `-SelfTestPull` | 关 | 不接设备验证「二进制拉回」管线(见 §6.2) |

注:显式 `-Seconds` 不会被自动加长(只告警"预计跨 N 个环回点");只有未指定、脚本自己算的值才会补到 >= 3 个环回点。

### 2.2 脚本内部顺序

1. 解析 adb(`ANDROID_HOME` → `ANDROID_SDK_ROOT` → `%LOCALAPPDATA%\Android\Sdk` → PATH),检查设备;
2. `adb forward tcp:18765 tcp:8765`(HTTP 失败时重建 forward 重试一次——这台设备 forward 会被系统回收);
3. `GET /state` 自检 → 定 A/B(或 `-AutoConfirm`)→ 算 seek / 录制秒数;
4. `adb logcat -c`(只留本次窗口);
5. `POST /debug/tap {path}` → 拿服务端解析出的绝对路径;
6. `POST /loop/enter {songId, play:true}` → 1.2s 后 `GET /state` 取**实际窗口**并与预期 A/B 比对(不一致就告警——这是"窗口漂移"的第一现场);
7. `POST /player/seek {positionMs}` → 等 `-Seconds` → `POST /debug/tap {path:null}` → 等 0.8s 让 `end`/WAV 头回填;
8. 拉回 tap + sidecar + LoopPerf 日志;
9. 调分析脚本,输出 `RESULT: PASS/FAIL`,并把参数/产物/结论写 `capture-<songId>-<tag>.json`。

### 2.3 拉文件:必须 `exec-out` + 重定向

```bash
# 正确(脚本内部等价实现)
adb exec-out run-as com.zjr.hesimusic cat /data/local/tmp/tap.pcm > e2e-artifacts/tap-40904-xxx.wav
```

- `run-as` **不能**把文件拷到 `/data/local/tmp`(`Permission denied`),只能读自己 uid 能读的文件;
- PowerShell 的 `>` 会把原生命令输出**按文本解码**再写盘,PCM 必坏。脚本用 `.NET Process` + `StandardOutput.BaseStream` 直接把字节写文件,等价于上面的 shell 重定向;
- sidecar 名字是 **`<tap 文件名>.jsonl`**。脚本按 `<tap>.jsonl` → `<同名>.jsonl` → `<tap>.events.jsonl` 顺序 `ls -l` 探测,并把探测到的名字和 `ls -l` 原文打进日志(便于以后 app 改命名时快速定位);
- 拉完会做三项自检:字节数 > 0、RIFF 头、`events` 里有 `run`/`seam`/`end`;缺 `run` 就提示"window 为 null / writeTapRunLocked 未触发",0 个 `seam` 就提示"录制期没跨过 B"。

性能参考:33s/48kHz/立体声(1584000 帧、6.0 MiB)全量分析(解码+5 项指标+3 处接缝互相关)约 **0.2s**;内存与录长成正比(约 3.5 字节/样本:原始解码 4B + mono 4B + Δ 1B 等),120s 量级需要几百 MB,超长录制建议分段或加大间隔。

## 3. 分析:`tools/loop-smoothness.ts`

```bash
bun run tools/loop-smoothness.ts e2e-artifacts/tap-40904-xxx.wav \
  --events e2e-artifacts/tap-40904-xxx.wav.jsonl \
  --json e2e-artifacts/smoothness-xxx.json
# 只判不细列:
bun run tools/loop-smoothness.ts <wav> --quiet
# 源文件对照(需 ffmpeg,见 §4):
bun run tools/loop-smoothness.ts <wav> --source <song.flac> --source-a-ms 20000 --source-b-ms 50000
```

输入解析:自动识别 WAV 头(RIFf/WAVE,`fmt` tag 1/3 或 0xFFFE ext,8/16/24/32/float32/float64);无头时按 `--sr/--ch/--pcm-format`(或 `configure` 事件)当裸 PCM。内部统一解码成"int16 单位"浮点数组(`±32768` 满幅),所有阈值都在这个口径上定义。

输出固定五项 +`RESULT: PASS/FAIL`+`DIAG:` 行;exit 0=PASS / 1=FAIL / 2=参数或 IO 错误。

### 3.1 `zeroRuns` —— 连续数字静音

- 定义:所有声道逐样本 `== 0` 的连续帧;长度 `>= --zero-ms`(默认 5ms)判 **FAIL**,列出每段起点(帧号+ms)、长度、是否贴采集首/尾、最近的接缝。
- 阈值依据:
  - 5ms @48k = 240 帧,@44.1k ≈ 220 帧。一次 delegate 欠载丢的是"一个或多个 HAL burst",典型 2~10ms;小于 5ms 的零段也可能来自重采样器/DSP 的块边界填充,不足以证明输出中断;
  - 正常音乐(尤其有损编码)几乎不会出现 240 个**精确为 0** 的连续帧(编码器会给数字静音加 dither/噪声),所以"精确 0 长段"是异常而不是安静段;
  - 贴采集首/尾的零段只判 WARN(可能 tap 早于播放开始,或停止后才关)。
- 整段全 0 = FAIL,提示"tap 打开时没在播放 / ring 没建起来"。

### 3.2 `clicks` —— 相邻样本跳变(爆音)

- 定义:逐帧取各声道 `|Δ| = |x[f]-x[f-1]|` 的最大值 `d[f]`;
  `thr = max(p99.9(全局 d), --click-floor=2000)`;`d[f] >= thr` **且** `d[f] >= k × 局部 P95(±20ms)`(`k=--click-locality=3`)才判为爆音;相邻爆音点间隔 `<= 2ms` 合并成一个 click;输出位置(帧/ms)、峰值 Δ、声道、宽度,以及是否落在接缝 `±2ms` 内。
- 阈值依据:
  - 2000/32768 ≈ **-24 dBFS 的单样本跳变**。对满幅正弦,`|Δ|max ≈ 2π·f/sr·32768`:1kHz@48k ≈ 4288,5kHz ≈ 21438,10kHz ≈ 42876 —— 也就是说**高频内容天然会超过 2000**,所以下限必须被"素材自己的 p99.9"抬起来;
  - 但 p99.9 **按定义**会框住 0.1% 的样本(48k 立体声 12s 里有上千个),单靠它必然把密集瞬态的音乐判成满屏爆音。**局部离群门限**才是真正区分"缺陷"与"素材"的部分:孤立缺陷的局部 P95 远低于峰值(比值 > 10),密集鼓点/镲片会让局部 P95 逼近峰值(比值 ≈ 1);
  - fixture 实测:干净素材 `thr=2587`、超阈点 52 个、经门限后 0 个;注入的单样本 +24000 跳变峰值 Δ≈24917、局部离群比 ≫3 → 判出且标 `SEAM`;
  - 想退回"纯 p99.9"口径:`--click-locality 0`(会大量误报,不建议)。
- 判 FAIL:`--allow-seam-clicks` 未开时,接缝处爆音也 FAIL(它就是"接缝爆音");接缝外的爆音永远 FAIL。

### 3.3 `seamAlign` —— 环回点对齐

逐接缝输出 `frame / spacing / dL / ncc / lag / ptsΔ`,三条判据:

1. **帧距 vs 理论 L**:`spacing = frame[k]-frame[k-1]`,`L_theory` 依次取:`--loop-frames` → run 快照的 µs 字段(`loopStartUs/loopEndUs`,若将来加上)→ `round((bMs-aMs)*sr/1000)`。
   - ⚠️ **快照只有 ms**:`aMs = loopStartUs/1000` 是整数除法,两端各截断到 ms,`L` 的理论值本身就有最坏 ~1ms(48kHz ≈ 48 帧)的量化误差。因此 `--spacing-tol`(默认 2)只是下限,ms 来源时自动放大到 `ceil(sr*0.001)+2`(48k = 50 帧),并在 `note:` 里写明。想精确到帧,需要 app 侧让 `run` 事件带上 `loopStartUs/loopEndUs`(见 §7),分析脚本已前向兼容。
2. **跨周期相位 `lag`**:取接缝前后各 10ms(合计 20ms)窗口,与**相邻接缝**的同相位窗口做一阶差分(高通)+ winsorize(4×RMS 截断)后的归一化互相关,峰值偏移必须 `<= --lag-tol`(默认 1)。环形重放是逐样本拷贝,同相位内容本应完全一致;偏移非 0 说明"环内素材相对自身周期错位"(拼接长度不对/素材取错)。
   - 与理论 L 处比会污染判据(dL 和 lag 互相串),所以只与相邻接缝比:**lag 只反映相位错位,dL 只反映长度错位**。
3. **PTS 一致性**:`ptsΔ = (outFrames[k]-outFrames[0]) - (ptsUs[k]-ptsUs[0])*sr/1e6`,容忍 `--pts-tol`(默认 2 帧)。引擎里 `loopDurationUs = loopFrames*1e6/sr`(整数除法,每轮 <1µs 误差),`replayPts = B + (round-1)*loopDurationUs`,所以正常情况下每轮 PTS 增量与帧增量严格对应;PTS 与帧号脱钩 = 窗口/round 记账错。

`ncc < 0.9` 只降级为 **WARN**,不单独判 FAIL:低相关通常说明"同相位窗口本身不可比"(旁边刚有爆音/静音段/内容变化),这类缺陷由 `clicks`/`zeroRuns` 单独负责,避免一处故障报三处。

无接缝锚点(没有 `seam` 事件 / `outFrames` 越界)= **FAIL**,并直接指出"tap 必须在 /loop/enter 之前打开"。

### 3.4 `rmsWindows` —— 100ms RMS 突降

- 定义:100ms 窗的 RMS(dBFS);相对上一窗下降 `> 6dB` 记为突降,并区分:
  - `hole`:随后 5 个窗(500ms)内回到上一窗 -6dB 以内 → 输出掉块(欠载/重试);
  - `sustained`:没恢复 → 乐段变化(只 WARN)。
- 阈值依据:100ms 窗电平减半(-6dB)= 该窗约 75% 的能量消失;小于 ~75ms 的洞触发不了 6dB,所以这一项和 `zeroRuns`(抓 `>=5ms` 精确静音)分工互补:一个抓"大片能量丢失",一个抓"精确数字静音"。前窗低于 -55dBFS 时不做判据(安静段落无意义)。

### 3.5 `dupCheck` —— 相邻周期逐样本相同

- 定义:以接缝为界取相邻两个完整周期(`[S0,S1)` vs `[S1,S2)`),逐帧逐声道比较;容差 `--dup-tol` 默认 **0**。
- 阈值依据:ring 重放是**字节拷贝**,同一素材第 2 轮必须与第 1 轮完全相同;任何差异都意味着 ring 被改写/部分覆盖(降级重播种、分片预填未完成、窗口切换),即"不是同一段素材在循环"。采集期若有 `flush/degrade` 事件,摘要会提示"先排除重播种"。
- 周期长度不同也记 FAIL(附 `lengthMismatch`)。

### 3.6 对照模式(`--source`)—— 区分"引擎拼接错"与"解码错"

`--source <源文件> --source-a-ms A --source-b-ms B`:`ffmpeg -i src -ss A -t (B-A) -ac ch -ar sr -f f32le -` 解出参考 `[A,B)`,然后

1. **素材相位**:取录音中段 100ms 探针,在参考环里粗搜(2ms 步进)+ 细搜(±步进)得到相位 `phase` 与 `ncc`。期望 `phase ≈ 0`;大偏差 + 低 ncc = **解码结果与参考不一致(解码错)或素材根本不是该区间**。
2. **接缝对照**:参考侧理论接缝 = `ring[L-half..L) ++ ring[0..half)`,与每个接缝的 20ms 窗口做互相关,给 `lagVsSource` 与 `ncc`。`|lag| > tol` 但 `ncc` 高 = **引擎拼接错位**;`ncc` 低 = **解码错**。

默认只报告不判失败(`--source-strict` 才计入 FAIL):ffmpeg 的有损解码/gapless 补偿可能带来一个固定偏移(几十~两千样本),不该拿它单独给引擎定罪。

## 4. 判读表(怎么把结论读出来)

先看 `RESULT`,再按下面顺序看**第一条 FAIL**:

| 读数 | 结论 | 下一步 |
|---|---|---|
| `zeroRuns FAIL`(成段 0) | **缓冲/预填没接上** | `logcat` 看 `LoopPerf: state=... stallCount= covered= cursor=`;`degradeCount` 是否增长;`replay stuck recovery` 是否出现 |
| `clicks FAIL` 且全部标 `SEAM#k` | **接缝爆音**:B→A 拼接点本身不连续 | 检查 A/B 是否落在波形陡变处(分析里的候选 score / noteDistance);`seamSwitch/seamGap/seamDiscontinuity`;`--source` 看 `lagVsSource` |
| `clicks FAIL` 且标"非接缝" | **不是接缝的问题**:解码/写入损坏 | 看 logcat 解码错误/underrun;`--source` 看 `phase/ncc` 判断解码错 |
| `seamAlign FAIL`(`dL`) | 帧距与理论 L 不符(窗口漂移/快照与 ring 不一致) | 对比脚本打印的"实际窗口 A/B"与 `/song/<id>`;`run` 事件是否中途又写了一条(窗口变化) |
| `seamAlign FAIL`(`lag/PTS`) | 环长错位(多/少帧)、素材取错 | 用 `--source` 复核 `phase` 是否 ≈0;必要时让 app 记 µs A/B |
| `dupCheck FAIL` | ring 内容变了(**不是同一段在循环**) | `flush/degrade` 计数;`isRingComplete`;`covered` 是否 < `ringFrames` |
| `rmsWindows FAIL`(hole) | 中途掉块后恢复 = delegate 欠载 | 同第一行;再看 `pendingReplayBytes` |
| `rmsWindows WARN`(sustained) | 乐段变化,不是缺陷 | 忽略 |

结论行的用法示例:

- `RESULT: PASS` + 五项全 `[ OK ]` → 这段输出**在采样意义上连续**(没有静音洞、没有样本跳变、环长稳定、周期逐样本一致)。若耳朵仍觉得不顺,优先怀疑**采集窗口之外的路径**(扬声器/蓝牙链路、系统混音、音量渐变)或**素材本身的低频相位问题**(A/B 之间本来就有相位跳变,但小于爆音阈值)。
- `RESULT: FAIL` + 只有 `clicks/SEAM` → **接缝问题,不是缓冲**。
- `RESULT: FAIL` + `zeroRuns` 或 `rmsWindows(hole)` → **缓冲/欠载**,环回拼接本身可能是对的。

## 5. 失败时看什么日志

`tools/loop-tap-capture.ps1` 会把本次窗口的 `LoopPerf` 行存成 `e2e-artifacts/loopcat-<tag>.txt`(可 `-NoLogcat` 关掉)。手工命令:

```powershell
adb logcat -c
# ... 录 ...
adb logcat -d -v time | Select-String "LoopPerf|seam|degrade"
```

关键行(1Hz 一行,来自 `LoopAudioSink.maybeLogPerf`):

| 字段 | 含义 / 判读 |
|---|---|
| `state=CAPTURING/REPLAYING/PASS_THROUGH` | 是否真的进了重放;一直在 CAPTURING 说明预填没完成或 window 为 null |
| `stallCount=` | **覆盖耗尽原地等待的次数** → 直接对应"缓冲没接上" |
| `covered=` / `cursor=` | 已覆盖帧数 / 环内游标;重放中 `covered < ringFrames` 就是分片预填没追上 |
| `degradeCount`(=`/state` `loop.degradeCount`) | 降级回源的次数;它会在 tap 里落 `degrade` 事件 |
| `pumpWrites/pumpCalls/lastPump` | 泵是否在持续出帧;`pumpWrites` 不涨 + `state=REPLAYING` = 卡住 |
| `seamSwitch/seamDefer/seamVerifyFail/seamDiscontinuity/seamGap` | seam-splice(接缝路由)分支的计数:切换次数、推迟次数、校验失败次数、不连续次数、**最大间隙帧数**;`seamVerifyFail>0` 回退 M3′,`seamDiscontinuity>0` 说明接缝拼接被判不连续 |
| `seamBypass` / `seamBytes` / `seamBDeltaUs` | 接缝路由是否被绕过 / 接缝缓冲字节数 / B 的偏移 |
| `replay->capture prelude mediaPtsUs=...` | 降级/se'ek 把播放头拉回前奏,退出重放 |
| `input pts jump a->b -> delegate discontinuity` | 输入 PTS 跳变 >0.5s,主动重置 delegate 基线 |
| `replay stuck recovery attempts=...` | 重放连续零消费触发自愈 |
| `seam prepare unavailable -> fallback M3'` / `seam verify failed -> fallback M3'` | 接缝路由降级到 M3′ |
| `{ tag } seedRing ... status=... window=... frames=...`(`logSeedResult`) | 预填失败的原因(容量/格式/覆盖) |

`GET /state` 侧:`loop.engine`(loop/exo)、`loop.isReplaying`、`loop.seamCount`、`loop.degradeCount`、`loop.isRingComplete`、`loop.window`。

## 6. 脚本自测(不需要设备 / ffmpeg)

### 6.1 分析脚本 fixture

```bash
bun run tools/loop-smoothness.ts --selftest
# fixture 落在 tools/fixtures/loop-smoothness/(可 --fixtures <dir> 换目录)
```

合成参数:16kHz 双声道、环长 L=12800 帧(800ms)、前奏 400ms、3 个环、尾巴 200ms;素材是同周期正弦叠加(各分音频率 = `m×sr/L`,天然以 L 为周期,所以 B→A 连续)。

| fixture | 注入的已知缺陷 | 期望 |
|---|---|---|
| `ok-loop` | 无 | `RESULT: PASS`,五项全 PASS |
| `click-at-seam` | 每个接缝处单样本 +24000(周期性的接缝爆音,环内容仍逐周期相同) | `FAIL`;`clicks` FAIL 且标 `SEAM`;`dupCheck`/`seamAlign` 必须仍 PASS(证明能区分"接缝爆音"与"缓冲/素材"问题) |
| `silence-5ms-at-seam` | 接缝处 80 帧(恰 5ms)全 0 | `FAIL`;`zeroRuns` FAIL 且明细出现 `len=80 frames` 起点帧 6400 |
| `misaligned-loop` | 每轮多 40 帧(2.5ms,超过 ms 量化容忍 18 帧) | `FAIL`;`seamAlign` FAIL(`dL`/`lag`/`ptsΔ`);`dupCheck` 仍 PASS(长度错位不是内容改写) |

一次真实输出:

```
[ OK ] ok-loop  RESULT=PASS  zeroRuns=PASS clicks=PASS seamAlign=PASS rmsWindows=PASS dupCheck=PASS
[ OK ] click-at-seam  RESULT=FAIL  zeroRuns=PASS clicks=FAIL seamAlign=PASS rmsWindows=PASS dupCheck=PASS
[ OK ] silence-5ms-at-seam  RESULT=FAIL  zeroRuns=FAIL clicks=FAIL seamAlign=WARN rmsWindows=PASS dupCheck=FAIL
[ OK ] misaligned-loop  RESULT=FAIL  zeroRuns=PASS clicks=FAIL seamAlign=FAIL rmsWindows=PASS dupCheck=PASS
SELFTEST: PASS (4/4 fixture 判定符合预期)
```

### 6.2 拉文件管线自测(不需要设备)

```powershell
powershell -NoProfile -File tools/loop-tap-capture.ps1 -SelfTestPull
```

用 `cmd /c type` 代替 `adb exec-out`,走同一段 `.NET Process + BaseStream` 落盘代码,断言:SHA256 与源文件一致、拉回仍是 RIFF、文件不存在时 `exit!=0 & size=0`(不会写出半个文件冒充成功)。

```
[step] pull 自测: cmd /c type -> Process.BaseStream -> 文件
       bytes=192044 sha256=36A333017DE90F64...
       missing-file: exit=1 size=0(应为非 0/0)
SELFTEST-PULL: PASS(字节精确、缺失文件不冒充成功)
```

### 6.3 端到端接线自测(假 adb + stub API,不需要设备)

在开发这套脚本时用「stub `/state`+`/song`+`/debug/tap`+`/loop/enter`+`/player/seek` HTTP 服务 + 一个假 `adb.exe`(实现 `devices/forward/logcat/shell run-as ls|rm/exec-out … cat`)」把整条链跑通,验证过:

- `/debug/tap` 开/关的 JSON body 分别是 `{"path":"..."}` 和 `{"path":null}`(stub 侧逐条打印确认);
- A/B 解析、seek 位置、自动录制时长、"预计接缝数"计算;
- 二进制拉回(192044 bytes 与源文件 SHA256 一致)、sidecar 名字探测、logcat 过滤;
- 分析结果、manifest、退出码(RESULT: PASS → exit 0;无 sidecar / FAIL → exit 1 且不崩)。

这一步抓出并修掉了 3 个只在真机会犯的错:①原生 `adb` 往 stderr 打 `ls: ... No such file` 时,`2>&1` 在 `$ErrorActionPreference="Stop"` 下会抛 `NativeCommandError` 让脚本"假崩溃";②侧钻到 `adb pull` 兜底路径的同类问题;③显式 `-Seconds` 被自动加长。这类问题在真机上表现为"脚本跑到一半莫名退出",所以值得单独留一条自测路径。

## 7. 已知限制(以及需要 app 侧配合的元数据)

1. **A/B 只有 ms**:`run` 事件写的是 `loopStartUs/1000` 的整数毫秒,`seamAlign` 的"帧距 vs 理论 L"因此只能容忍 ~1ms。要做到帧级判据,建议在 `writeTapRunLocked` 里补两个字段(分析脚本已前向兼容,有就用):
   ```kotlin
   "loopStartUs" to w?.loopStartUs,
   "loopEndUs" to w?.loopEndUs,
   ```
2. **stallCount / covered / cursor 只在 logcat**,不在 tap/状态里。如果希望"一句命令给出缓冲证据",可考虑把 `engine.stallCount`、`engine.coveredFrames`、`engine.ringFrames` 也写进 `run`(或让 `end` 事件带上)。
3. **采集起点未知**:tap 从"当前播放位置"开始录,所以分析脚本不假设"A 一定在录音的某处";接缝位置全部来自 `seam.outFrames`。若改用其它 tap 实现,必须保证同样语义,否则 `seamAlign` 会 FAIL(这是有意的:宁可不判,也不假 PASS)。
4. **degrade/flush 会污染 dupCheck**:采集期内发生过重播种,相邻周期本来就会不同。摘要会提示,判读时先排除。
5. **源对照的 ffmpeg 参考不是逐位真值**:有损解码/gapless 补偿会带固定偏移,`--source` 默认只报告。
6. `clicks` 的局部离群门限会让"极短、幅度刚好在素材 P95 附近"的瑕疵漏判;需要更激进时 `--click-locality 2` 并接受更多误报。

## 8. 门禁与产物

- 门禁:`loop-smoothness.ts` exit 0(`RESULT: PASS`);真机 L3 矩阵建议每一项都跑一遍本方案,产物:
  `e2e-artifacts/tap-<songId>-<tag>.wav`、`.wav.jsonl`、`loopcat-<tag>.txt`、`smoothness-<tag>.json`、`capture-<songId>-<tag>.json`。
- 与 `spike-analyze.ts` 的 A1/A2/B 一起看:两边同时 PASS,才允许宣称"真无缝 + 输出连续"。
- 全部产物落在 `e2e-artifacts/`(已在 `.gitignore`),不入库;fixture 在 `tools/fixtures/loop-smoothness/`(小体积,入库,便于回归)。
