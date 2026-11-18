# 片段无缝循环:从头设计方案(设计稿,不写实现)

> 状态:提案 / 待决策
> 日期:2026-11-18
> 范围:只读分析现有实现 + 给出方案设计与取舍;不写业务代码、不修改仓库其它文件。
> 参考代码:`loop/audio/source/**`、`loop/audio/**`、`service/LoopSessionPlayer.kt`、`ui/loop/PreviewPlayer.kt`;
> 参考产物:`e2e-artifacts/a7.wav`、`a7.wav.jsonl`、`f6.wav`、`base.wav`;media3 1.8.0 源码 `build/media3-src/`。
> 硬约束:**方案必须能用"秒级、无设备"的两件离线实验证伪(§4.3);真机只做集成层 20 秒单指标验收。**

---

## 0. 决策卡(先看这一节)

**结论:不继续修 SOURCE;换方案 A(环带渲染 + 单次连续解码 + 原生 PCM 播放);M3′ 保留为兜底,不作为终态。**

三条理由:

1. **这一轮的判据不成立。** 离线取证可以证明:`a7.wav`(2.2.2,带 250ms preroll)与 `f6.wav`(2.1.3,无 preroll)**前 30 秒逐样本完全相同**(48ms 恒定偏移,2,880,000 样本 100.000% 相等)。"加 preroll 让掉档 9→16"**不是引擎变差**,而是 100ms 窗的**网格相位**变了:同一段 PCM,网格平移 24ms,掉档窗数量在 6~16 之间跳(实测 16→13→10→6→16)。现有 `rmsWindows` / `clicks` 只能判"内容动态",不能判"引擎缺陷"。
2. **SOURCE 的结构性缺陷是"每 1 秒独立开一次 MediaCodec"。** 每块新起解码器、seek 回 250ms 前、用"第一个输出 buffer 的 PTS"锚定、再按帧数裁切。这条路在语义上不可能保证块间样本级连续(AAC/MP3 的 encoder delay / bit-reservoir 不以样本为单位对齐)。预取、缓存、预滚只能改变"块边界落在哪",不能消除块边界。
3. **方案 A 把接缝从功能里删掉。** 环([A,B) 只解一次、连续写入 PCM)完成后,播放侧只是"按字节取模的无限 PCM 流":接缝处没有解码器、没有 seek、没有块边界、没有缓存状态机;M3′ 的进场预填/时间轴冲突、SOURCE 的块缓存/预取/短读防线全部不存在。而且 A 的**两件离线实验**都能在毫秒级完成(§4.3),满足"小、快"。

**节奏(离线优先):**

| 步骤 | 动作 | 判定 | 耗时 |
|---|---|---|---|
| T0/T1 | 纯 JVM:环核心 3 圈逐字节 + 供给时序离散事件模拟 | 0 丢/0 重;最小缓冲深度 > 1 块 | **秒级,无设备** |
| R0 | 判据修正:同源基线 + 正对照 + 字节锚点 + skipData/decodeCount 打点 | 回答"到底有没有样本级缺陷" | 半天 |
| R1 | 方案 A 最小原型(仅预览页) | T0/T1 + 真机 20s 单指标 | 2~3 天 |
| R2 | 真机验收(2 台) | 掉档窗 ≤ 基线 | 1 天 |

> R0 可能直接证明"不需要重构"(当前证据是双向的);但 T0/T1 只花几十分钟,应当**先做**,它们也是 A 的强制门禁。

---

## 1. 证据复核:为什么这一轮的"9 vs 16"不能作为判据

### 1.1 已复核为事实的部分

| # | 事实 | 证据 | 含义 |
|---|---|---|---|
| F1 | 送进 sink 的帧数与墙钟严格 1:1 | `a7.wav.jsonl`:`end.t=35030ms`、`totalFrames=1681393`;1681393/48000=35.0290s | 播放期间**没有丢时间、没有补静音、没有欠载** |
| F2 | tap 自报 writtenFrames == seenFrames 100% | 侧车 `tapDuty` 日志 | tap 层没丢样本(但它只是"渲染器→sink"的镜像,见 §1.3) |
| F3 | 两个不同构建的循环 PCM **逐样本相同** | a7 与 f6:恒定 2304 帧(48.000ms)偏移下,前 30s 共 2,880,000 样本 100.000% 相等;偏移 ±2ms 时相等率 0.01% | **"preroll 让掉档 9→16"是假的**:PCM 没变,变的是分析网格 |
| F4 | 26 个样本级 click 在两个录音里逐一对齐、幅度完全相同 | a7 t=1524.1ms `d=18923` ↔ f6 t=1572.1ms `d=18923`;全表 26 对 | click 主要来自素材瞬态,与基线 31 个同源 |
| F5 | 掉档窗数量对网格相位极敏感 | 同一份 a7,网格偏移 0/24/48/72/96ms → 16/13/10/6/16 | 跨录音/跨版本直接比较**没有意义** |
| F6 | 现有基线不可比 | `base.wav` 与 a7 包络互相关最好 0.39(@5.24s),无样本级对齐 | 拿"不同素材/不同起点"当基线,得到的 +4 click/+5 hole 是内容差异 |
| F7 | 无 ≥5ms 精确零段 | `zeroRuns` PASS(两版) | 不存在"补零/静音洞";若有"洞",不是时域空洞 |

### 1.2 三个测量缺陷(必须先修)

1. **掉档判据不平移不变**:`100ms RMS vs 前一窗 >6dB 且 500ms 内恢复` 在音乐上等价于"段落动态检测"(F5)。
2. **基线不同源**:必须同一首歌、同一 A/B、同时长、同设备/音量(F6 不是)。
3. **接缝没有字节锚点**:`seam` 事件的 `outFrames` 是"数据源累计读帧",不是"tap 文件字节偏移",两者未校准,所以"接缝 ptsΔ=0"证明不了**录音里**接缝干净(§1.4 会看到它连 15s 周期都对不上)。

### 1.3 tap 的测量口径

`TapAudioSink` 包在 `DefaultAudioSink` 外、在 `handleBuffer` 之前取数。对 `audio/raw`,media3 走渲染器 direct/bypass 分支(`MediaCodecAudioRenderer.supportsFormat()` 中 `audioSink.supportsFormat(format)` 直接 `FORMAT_HANDLED`,不建 MediaCodec);sink 内部还有 `SilenceSkippingAudioProcessor` / `SonicAudioProcessor`。因此:

- tap 记录的是"**渲染器打算送给 sink 的 PCM**",不是 AudioTrack 真正吃到的 PCM;Sonic、静音跳过、sink 重采样都在 tap 之后,**不可见**;
- `writtenFrames==seenFrames` 在 tap 内部是恒等式,不能当独立证据;
- tap 看不到 AudioTrack underrun(`AudioTrackPositionTracker.onUnderrun` → `onAudioUnderrun`),要配合 F1 才有意义。

### 1.4 一处真异常,必须由 R0/E4 定案

对 a7 做样本级"精确重复"检索(128 帧签名 + 4096 帧复核):

- 环长按时间轴应为 **15.000s(720000 帧)**;
- 整段录音里唯一找到的样本级精确重复是 **+15.025s**(721200 帧)处、且只覆盖约 2 秒;
- 以 15.000s 为周期,整段找不到重复。

即"捕获里的内容周期"与"时间轴周期"差约 **25ms/圈**。可能是:tap 在接缝重复写了一个 ~25ms 缓冲(去重只按 identity+pts);数据源跨 B 多吐 ~1200 帧;渲染器 `skipData` 推进游标却没发数据;或我的哈希检索对微小电平差不敏感(存在,故不作为结论)。**这一条若成立,才是用户能听出来的那种缺陷**(每圈一次 ~25ms 重复/跳变,短环尤其明显)。

### 1.5 关于"每 2 秒 +2.08s"

F1 已证明音频 1:1(35.029s 音频 / 35.030s 墙钟),所以 2.08/2.00 不是播放速度,而是**测位置的采样间隔不是 2.000s**(轮询 `postDelayed(2000)` + 主线程排队;±20ms 抖动符合)。R0 给每次轮询加 `elapsedRealtime`,报"位置增量/墙钟增量"。

---

## 2. 根因候选与"一轮内证伪"

| ID | 假设 | 机制 | 支持证据 | 反证据 | 一轮内证伪 | 成立则 |
|---|---|---|---|---|---|---|
| **H0** | **判据/映射错** | 网格相位、基线不同源、seam 无字节锚点、轮询漂移 | F3/F5/F6、§1.4 | 用户主观确有卡顿(可能是别的版本/引擎) | R0:同源基线 + 正对照 + 字节锚点 + 时间戳 | 不重构;修判据后交付 SOURCE |
| **H1** | **每块独立解码造成拼接** | 每 1s 新起 MediaCodec;锚点=首个输出 PTS;按帧数裁切;`SEEK_BACK` 不保证精确 | 掉档位置与 1s 网格大致吻合(上一轮观察) | **F3:改 preroll 后 PCM 逐样本不变** | **T0**:整窗单次连续解码 vs 分块解码,逐样本比对(T0 直接可跑) | 若不同且集中在 1s 网格 → 不修 SOURCE;上 A |
| **H2** | 块首解码预热/低电平 | 新解码器起播几十 ms 电平偏低;250ms preroll 没裁净 | preroll 的动机 | F3 → 预热区没泄漏到输出 | 解码时打点:裁切后前 5/50ms peak/RMS vs 参考 | 删 preroll(简化) |
| **H3** | 预取/供给饥饿 | 块未就绪 → CONTINUE → 渲染器无数据 → sink 饿死 | 规则上成立 | **F1:帧/墙钟 1:1、无 underrun** | 打点:null/CONTINUE 的墙钟与持续时长 + stall 计数(稳态须 0) | 只影响首声/seek;A 消除 |
| **H4** | 缓存淘汰导致重复解码 | 淘汰后重解,若解码不确定 → 同内容 PCM 不同 | 一般风险 | F3 说明解码**确定性** | 打点:每 blockIndex 的 decode 次数(一圈应恰 1) | 预算/淘汰问题;A 不需要 |
| **H5** | **时间轴/内容错位** | 跨圈 +25ms;`skipData` 丢帧;跨 B 多吐帧 | §1.4;`outFrames` 非 720000 整数倍 | 无直接日志 | E4:接缝字节锚点 + skipData 计数 + 参考解码 lag 图 | 必须修的真缺陷;A 从结构上消除 |
| **H6** | Extractor/SampleStream 语义 | 64KB(341ms)大样本;`RESULT_CONTINUE` 无进展时加载线程**自旋**;SampleQueue 语义 | media3:`ProgressiveMediaPeriod.ExtractingLoadable.load()` 的 while 循环 + `loadCondition.block()`,输入位置永不前进 → 条件常开 | 自旋只烧 CPU,不直接产生爆音 | 改 `READ_CHUNK_BYTES` 为 4KB 重录;抓 `top -H` 看 `LoopPcm` 线程是否 100% | A 不用该模型 |
| **H7** | 渲染器 bypass / 下游设备 | Sonic/静音跳过/sink 重采样;蓝牙/扬声器/系统混音 | tap 在 sink 之前,看不到 | 同设备普通播放无异(待确认) | **正对照 E1**:参考解码拼 3 圈写普通 WAV,stock 播 + tap | 若正对照也"卡" → 设备/下游,任何循环方案都不解决 |

**R0 实验清单:**

| # | 实验 | 输出 | 判读 |
|---|---|---|---|
| E1 | 正对照:ffmpeg 解 [A,B) 拼 3 圈写 WAV,stock 播 + tap | `ref-tape.wav` | 与原 WAV 逐样本一致 → 设备/sink 干净;否则先止损 |
| E2 | 同源基线:同一 A/B、同时长普通播放 + tap | `base-same.wav` | 与循环录音同网格比对;差值才是引擎增量 |
| E3 | 整窗连续解码 vs 分块(T0 的真机版) | `cont.wav` | 一致 → H1 否;不同且集中在 1s 网格 → H1 是 |
| E4 | 接缝字节锚点 + skipData/decodeCount 打点 | 日志 | 环回处字节跳变必须恰为 0 或 720000×n 字节 |
| E5 | 参考解码对照:整段 lag/NCC 图 | 每 100ms lag | lag 恒 0 且残差 ≤ -60dBFS → 循环层无样本级缺陷 |

---

## 3. 候选架构

先排除:**继续在 SOURCE 上加预取/缓存/防线** —— 结构上做不到样本级连续,每加一层多一个时序假设。

### 3.1 方案 A:环带渲染 + 单次连续解码 + 原生 PCM 播放(推荐)

```
[A-250ms, B) ──(一个 MediaExtractor + 一个 MediaCodec,顺序解一次)──► PCM 环(内存,可选落盘)
                                                                        │
  播放:虚拟无限 PCM 流(offset mod 环字节数) ──► stock RAW/WAV 路径 ──► AudioSink
  时间轴:T 单调递增;对外位置 = A + floorMod(T, L)(已有 LoopSourceProjection)
  接缝:T 跨 L 时 offset 自然取模 —— 没有 seek、没有解码器、没有边界
```

- **接缝**:天然无缝(字节取模),不是"被修复的功能"。
- **seek**:环就绪时 = 偏移换算,≤10ms;环未就绪时目标限于已渲染区间或短等待(≤100ms 超时)。无 sink flush、无重解码。
- **内存**:`L × 192KB/s`(48k/16/立体声):15s≈2.9MB、30s≈5.8MB、60s≈11.5MB;上限 heap/10(402MB→40MB),超出拒绝该窗口并回退 M3′。
- **实现量**:中,~400–600 行(环渲染器 + 环 + 取模 DataSource + 时间轴映射 + 测试);**删除** `PcmBlockCache` / `LoopPcmPrefetcher` / `LoopingPcmExtractor` 的 PTS 与块状态 / `LoopingPcmSourceCore` 的 read/seek/skip。
- **风险**:中低。唯一新难点是"环未满时允许播放 + seek 门控";播放侧是 stock 路径。
- **离线可证伪性**:**最强**(T0 直接逐字节;T1 可用 10 行离散事件模型)。

### 3.2 方案 B:双解码器预滚 + 单时间轴拼接

- 两个连续解码会话(或一会话两窗口),每段解 [X,X+Δ) 与 [Y,Y+Δ),Δ≈1s;切段前用**重叠区互相关**验证对齐,再拼进同一条 PTS 单调流。
- 接缝:只在"验证通过"时无缝;是每次切换都要验证+兜底的运行时契约。
- seek:需重新预滚(Δ=300ms 解码 ≈10–20ms),理论 ≤100ms;两条会话同时重定位,状态机复杂。
- 内存:`4Δ×192KB≈0.8–1.6MB`。实现量:高(800–1200 行)。风险:高(块边界依然存在,只是变大了)。
- 离线可证伪性:中(T0 需要模拟重叠拼接;T1 需要模拟两解码器切换)。
- 定位:仅当 A 的内存/首声在目标机型不达标时降级。

### 3.3 方案 C:自定义 AudioProcessor/AudioSink 样本层循环(= M3′ 家族)

- 接缝:字节拼接,天然无缝;但需要"先捕获、后重放",捕获期必须已完整走过 [A,B)(或另有预解码器填环)。
- seek:播放器时间轴是原生文件时间轴,seek 原生,但 sink flush 后必须重新预填 → 就是 M3′ 现在的痛点。
- 内存:环 + sink 缓冲(与 A 同量级)。实现量:已有(稳定)。风险:中(真机稳定),但进场预填慢/时间轴冲突是产品原始诉求。
- 离线可证伪性:**弱**:3 圈输出必须等"真实播放走完一圈"才能捕获,T0 无法在毫秒级伪造;T1 也依赖真实 sink。**按新约束不推荐作为新架构。**
- 定位:兜底。

### 3.4 方案 D:MediaItem 级拼接 + `ClippingConfiguration`

- 接缝:**不天然无缝**:每次接缝是一次 period 切换;clip 起点依赖 extractor seek 粒度,容器/解码器 priming(AAC 2112 样本≈44ms@48k)造成首样本偏移;media3 gapless 只在"同格式、支持 gapless 的编码 + 连续 period"下尽力而为;切换还可能触发 sink flush/decoder reset。
- seek:最简单(k 圈 = 第 k 个 item)。内存:0。实现量:最小(~100 行)。风险:对核心需求**高**。
- 离线可证伪性:**弱**(没有安卓/ExoPlayer 就复现不出 period 切换与 priming)。
- 定位:只可作为"接受接缝瞬态"的临时版本,不能过 §5。

### 3.5 对比表(含"离线可证伪性")

| 维度 | A 环带 | B 双解码预滚 | C sink 样本层(M3′) | D MediaItem 拼接 | 现 SOURCE |
|---|---|---|---|---|---|
| 接缝是否天然无缝 | **是**(字节取模) | 否(每次验证) | 是(拼接) | 否(period 切换) | 理论是,实际取决于块解码 |
| seek 复杂度/延迟 | **低 / ≤10ms** | 中 / ≤100ms | 中 / 需重预填 | **最低 / 原生** | 低 / 需等块 |
| 内存 | L×192KB(2.9–11.5MB) | 0.8–1.6MB | 环(同 A) | 0 | 32MB 预算 |
| 首声 | 首块渲染 ~100–250ms | 预滚 ≈300ms | 整环/分片预填(慢) | ~300ms | 2 块≈几百 ms |
| 命中零解码 | **是**(稳态零解码) | 部分 | 是 | 是 | 是(块缓存) |
| 实现量 | 中 | 高 | 已存在(重) | 低 | 已存在 |
| 结构风险 | 低 | 高 | 中 | 高 | 高 |
| **T0 秒级证伪** | **可**(纯数据) | 可(需模拟拼接) | 不可(需真实捕获) | 不可 | 可(对照解码) |
| **T1 供给模拟** | **可**(极简) | 可(两解码器) | 不可(真实 sink) | 不可 | 可(块供给) |

> 按"无法用两件离线实验快速证伪就不要推荐"的规则:C、D 被排除,B 仅作降级。

---

## 4. 推荐方案与最小可验证原型边界

### 4.1 为什么是 A

1. **消掉整类缺陷**:接缝、块边界、预取时序、缓存淘汰、Extractor 样本语义、渲染器 skip —— 在 A 里全都不存在。剩下只有"环是否按参考解码正确"和"播放器是否按字节读",都能逐样本判定。
2. **把解码搬出实时路径**:解码只发生一次,且是**一个连续会话**(A-preroll→B,中途不重启),H1/H2 从根上消失。
3. **seek 变便宜**:环常驻 → 偏移换算;顺手解决"进页预填慢、seek 与时间轴冲突"。
4. **两件离线实验都能秒级完成**(§4.3),满足"小、快"。
5. **复用现有正确部分**:`LoopWindow`、`LoopSourceProjection`(`A + floorMod(T,L)`)、`LoopSessionPlayer` 的展示/seek 投影语义不变。

### 4.2 原型边界(v1 做什么 / 不做什么)

**做:**

- `LoopRingRenderer`:一个连续解码会话,从 `A - preroll` 解到 `B`,裁掉 preroll,顺序追加进环;维护 `renderedFrames` 高水位。
- `LoopRing`:`frames = ceil(B*sr/1e6) - ceil(A*sr/1e6)`,PCM16 交错小端;`copyFrames(offsetFrames, count)` 取模。
- `LoopingRingDataSource`:`open` 返回 `LENGTH_UNSET`;`read` 只在 `[0, renderedFrames)` 内供数、`offset mod ringFrames`;**未渲染不返回 -1**(避免 EOS),返回 0 并计 stall。
- 极简 Extractor(或 WAV 头 + stock `WavExtractor`):固定 `audio/raw`/PCM16/真实 sr/ch,每样本 20–40ms,`sampleMetadata(pts = framesToUs(累计帧))`。
- 时间轴/位置/seek 复用现有投影;seek 目标限已渲染范围(v1)。
- 只接**预览页**,主播放器保持 M3′(A/B 对照)。

**不做:**

- 磁盘环 / 持久化缓存 / A/B 热切换;
- 环未就绪时 seek 到远端的优先级解码(超时 spinner);
- crossfade / 等功率拼接 / 节拍对齐(接缝不需要任何 DSP);
- 多 loop 并发、主播放器接线、>60s 长窗口;
- 不删 M3′、不动 `MusicService` 队列。

**接口草图(非业务实现):**

```kotlin
// 只解一次;产出高水位;不阻塞播放线程
class LoopRingRenderer(
    private val window: LoopWindow,
    private val decodeProvider: PcmRangeProvider,   // 复用现有解码入口,但只调一次
    private val ring: LoopRing,
) {
    fun start()                                // A-preroll → B,单会话,顺序写环
    fun awaitRendered(frames: Long): Boolean   // seek 门控(带超时)
    val stallCount: Long                       // 取数时环未就绪次数,稳态必须为 0
}

class LoopRing(val sampleRate: Int, val channels: Int, val frames: Long) {
    @Volatile var renderedFrames: Long = 0
    fun copyFrames(dst: ByteArray, offsetFrames: Long, count: Int): Int  // mod
}

class LoopingRingDataSource(private val ring: LoopRing) : BaseDataSource(false) {
    // open(): length = C.LENGTH_UNSET
    // read(): pos = (readPos mod ringBytes); 未渲染 → return 0
}
```

**不变量:**

1. 环内容 = 参考解码 [A,B) 的逐样本(允许 ±1 LSB 解码差,不允许相位差);
2. 播放流 = 环按 mod 重复,**逐字节**;
3. 稳态下 decode 调用次数不再增长;
4. 接缝处 pts 增量 = 1 帧(不是"帧距=环长"这种事后口径);
5. `renderedFrames` 单调,且 `播放位置 + 预留 < renderedFrames`(否则计 stall)。

### 4.3 强制:两件离线实验(T0 / T1,秒级、无设备)

> 这两个实验是**新方案的入场券**;任何候选方案如果不能用它们快速证伪,不进推荐列表。

#### T0 核心逻辑:3 圈逐字节(纯 JVM,无安卓,< 1s)

**目的**:一次跑完就回答"接缝有没有丢/重/错位",不需要真机、不需要分析器。

**做法**:

1. 测试 fixture:用**合成 PCM**(正弦 + 噪声 + 已知标记帧)或预置小 WAV 片段,构造 [A,B),帧数 L = 例如 48k×2s。
2. 把"环核心 + 取模读数"当纯函数跑:让它连续输出 **3 圈**(3L 帧),边输出边与"参考序列 `src[A..B) × 3` 拼接"逐字节比较。
3. 断言:
   - 逐字节相等(不匹配样本 = **0**);
   - 接缝位置 = 恰好 `k*L` 帧,且接缝两侧样本在参考序列里连续(`out[L-1]` 与 `out[L]` 分别等于 `src[B-1]` 与 `src[A]`);
   - PTS 单调、每样本增量 = 1 帧(bs 到 us 用整数换算,余数不累积);
   - 总输出帧数恰为 3L。
4. 失败注入(证明测试有效,4 个 fixture):少 1 帧、多 1 帧、接缝处 1 帧重复、接缝处 1 帧跳过 —— 四个都必须 FAIL,并报出帧号。

**对候选方案的适用性**:A 直接可用;B 需要额外模拟"重叠区拼接";C/D 需要真实播放/period 切换 → 不能秒级完成。**只凭这一条,C、D 就应退出候选。**

#### T1 供给时序:离散事件模拟(纯 JVM,无线程/无 sleep,< 100ms)

**目的**:回答"环会不会饿死",不依赖设备、不依赖真实时钟。

**输入参数(全部可配)**:

| 参数 | 取值 | 依据 |
|---|---|---|
| 读取频率 | **135 次/秒**(≈7.4ms/次) | 现场实测口径(也测 60/135/200 做敏感性) |
| 每次读取量 | 1 块(如 1s 或 20–40ms 样本) | 与实现一致 |
| 解码延迟 | 每块 20–40ms 均匀分布(另跑 p99=80ms 的尾部分布) | 上一轮实测 1s 块 20–40ms |
| 渲染/解码并发度 | 1(单解码会话) | 方案 A |
| 环容量 | L 块 | 方案 A |
| 启动策略 | 首块就绪即播 / 预渲染 N 块 | 方案 A 的两种策略 |

**模型**:虚拟时钟推进;事件 = {读请求, 块解码完成};消费者按"每 1/135 秒消耗 1 块数据"推进;解码器空闲则立刻取下一块,延迟从分布采样(固定种子,可复现)。输出:

- **最小缓冲深度**(块,稳态最小值)—— 门限 **> 1 块**;
- 最小剩余可播时间(ms)—— 门限 > 0;
- 饥饿事件数 —— 门限 **0**;
- 首声延迟(首块解码完成时刻)—— 门限 ≤ 300ms。

**判定**:稳态最小缓冲深度 ≤ 1 块,或出现任何饥饿事件 → 该方案/该参数组合**直接不推荐**(不需要真机)。同时扫描"预渲染块数 1..6"给出最小安全预渲染量,作为实现参数。

**对候选方案的适用性**:A 只需 ~10 行(单解码器);B 需要加"两解码器切换延迟";C/D 依赖真实 sink/period,模型不可信。**这一条进一步支持 A。**

#### T0/T1 与真机的分工

| 层 | 验证什么 | 手段 | 时长 |
|---|---|---|---|
| 核心逻辑 | 接缝无丢/无重、PTS 连续 | T0(JVM,3 圈逐字节) | 秒级 |
| 供给时序 | 稳态不饥饿、最小缓冲深度 | T1(离散事件,参数扫描) | 秒级 |
| 集成层 | 真机只有"掉档窗 ≤ 基线" | 20s 录制 + 同源基线 | 分钟级 |

---

## 5. 验收判据(可量化,离线优先)

### 5.1 离线门禁(必须先过,不过不进真机)

| # | 判据 | 方法 | 门限 |
|---|---|---|---|
| G0a | 3 圈逐字节 | T0 | 不匹配样本 = **0**;帧数 = 3L |
| G0b | 供给不饥饿 | T1(135 次/s,20–40ms) | 最小缓冲深度 **> 1 块**;饥饿事件 = 0 |
| G0c | 失败注入有效 | T0 的 4 个坏 fixture | 全部 FAIL 且报出正确帧号 |
| G0d | 解码正确性 | 离线:环 vs 参考解码(同源文件,ffmpeg) | 残差 ≤ -60dBFS;lag 恒 0 |

### 5.2 真机门禁(集成层,**单指标**)

| # | 判据 | 方法 | 门限 |
|---|---|---|---|
| G1 | **掉档窗 ≤ 基线** | 20s 录制,同源基线(同一 A/B/时长/设备),同网格计算 | **≤ 基线**(理想 0 增量) |
| G2 | seek 出声 | `seekTo()` → tap 出目标 PCM,10 次 p90 | ≤100ms(环就绪);≤300ms(未就绪) |
| G3 | 首声 | 进页 → tap 首帧 | ≤300ms |
| G4 | 内存 | 环字节 + heap 快照 | 环 ≤ heap/10(40MB@402MB) |
| G5 | 稳态零解码 | 60s 内 decodeCalls 增量 | **0** |
| G6 | 稳态 stall | 60s 内 stallCount 增量 | **0** |
| G7 | 稳定性 | 10 圈 + 20 随机 seek + 暂停/恢复×5 + 前后台×5 | 无异常、无漂移(=0 帧)、无 OOM |

### 5.3 次判据与判读

- 次判据(不单独判 FAIL,只报告):`clicks` 增量 ≤ +3(同网格同基线)、zeroRuns = 0、seamAlign 帧距 = 环长、ptsΔ = 0。
- G1 不过但 G0a/G0b 过 → 先查**测量**(基线/网格/接线/引擎选择是否真是 A),不要改引擎。
- G0d 不过而 G0a 过 → 解码/参考解码口径不一致(采样率、声道、ffmpeg 参数),先对齐口径。

---

## 6. 失败判据(及早止损)

| 级别 | 观测 | 结论/动作 |
|---|---|---|
| **架构级** | T0/T1 无法在一个下午实现,或结果对参数过敏(任何参数下都饥饿/丢帧) | 该方案不可用,不进真机 |
| **架构级** | R0 的 E1/E5 证明循环 PCM 与参考解码逐样本一致、多圈逐样本重复 | **不需要重构**:修判据/测量,交付 SOURCE,停止本提案 |
| **架构级** | E1 正对照(普通 WAV,stock 播放)也出现同样的"洞" | 问题在下游(设备/蓝牙/系统混音),任何方案不解决;回退 M3′,记录结论 |
| **实现级** | 单会话连续解码仍与参考有 >1 帧相位差,或 chunk 边界拼接 | 解码层不可靠 → 不投 A;单独查解码器(此时 M3′ 也受影响) |
| **实现级** | 环 > heap/10(p95 窗口 >45s)且不做磁盘 | A 不适用长窗口;评估磁盘环或 B |
| **实现级** | 首声 >500ms,或 T1 显示任何饥饿,或实测 stall > 0 | 放弃 A;回退 M3′ |
| **实现级** | 环就绪后 seek p90 >100ms | A 的映射不是零成本;回退 |
| **环境级** | 两台设备逐样本行为不一致(一台 bit-exact,一台不是) | 解码器非确定性 → A 不可移植;保 M3′ |
| **时间盒** | T0/T1 + R0 + R1 共 3 轮仍 G0a/G1 不过 | 停止重构;交付 M3′ + 本文档作为二期输入 |

---

## 7. 与现有实现的取舍(明确删/留)

| 现有组件 | A 下的处置 | 理由 |
|---|---|---|
| `LoopingPcmSourceCore`(读/seek/skip/块状态) | **删** | 环取模后不需要游标/块状态 |
| `PcmBlockCache` / `LoopPcmPrefetcher` | **删** | 环只解一次,无"按块命中" |
| `LoopingPcmExtractor` 的 PTS/块逻辑 | **删**(换 ~20 行固定格式 extractor) | PTS = 累计帧换算 |
| `LoopingPcmMediaPeriod`(手写 period) | **删** | 用 Progressive + 极简 extractor |
| `LoopSourceProjection` / `LoopSessionPlayer` 投影 | **留** | 语义不变 |
| `LoopWindow` / 分析候选 / 环长校验 | **留** | 与引擎无关 |
| `LoopAudioSink` / `LoopPcmEngine`(M3′) | **留作兜底** | 真机稳定;A 出问题可回退 |
| `TapAudioSink` | **留 + 加字节锚点** | 唯一可信的取证口径 |

---

## 8. 风险与待定

1. **长窗口**:环内存随 L 线性增长;需统计产品窗口 p95;>45s 用磁盘环或 M3′。
2. **首声**:取决于首块渲染;不达标就"边渲染边播"(生产者 20–40x 实时,余量足够,T1 可量化)。
3. **环未就绪时的 seek**:v1 限已渲染范围;产品化需优先级解码 + 重叠区校验(会部分引入 B 的风险,二期)。
4. **解码确定性**:F3 证明当前解码的两个构建逐样本相同,是 A 的前提;换机型需抽检。
5. **A/B 变更**:新窗口 = 新环;热切换(双环交叉)v1 不做。
6. **主播放器接线**:v1 只接预览页;主播放器沿用 `LoopAwareMediaSourceFactory` 的 tag 结构即可。

---

## 9. 附录:证据与复现

### 9.1 本轮离线复核数字

```
a7.wav : 1,681,393 帧 / 48k = 35.0290s;jsonl end t=35030ms
f6.wav : 1,679,857 帧 = 34.997s
base.wav: 1,681,920 帧 = 35.040s
a7 vs f6  : 恒定 2304 帧(48.000ms)偏移,前 30s 2,880,000 样本 100.000% 相等
            (偏移 ±2ms 时相等率 0.01%)
clicks(样本级,2ms 合并):a7 26 个,f6 26 个,逐一对应(时间差恒 48ms,|Δ| 完全相同)
rmsWindows(100ms,>6dB 且 5 窗内恢复):a7 16 / f6 9 / base 4
  同一份 a7,网格偏移 0/24/48/72/96ms → 16/13/10/6/16
```

### 9.2 复现命令(只读)

```bash
bun run tools/loop-smoothness.ts e2e-artifacts/a7.wav --events e2e-artifacts/a7.wav.jsonl --baseline e2e-artifacts/base.wav
bun run tools/loop-smoothness.ts e2e-artifacts/a7.wav --events e2e-artifacts/a7.wav.jsonl \
  --source <song> --source-a-ms 20000 --source-b-ms 35000
```

### 9.3 参考代码位置

- 逐块解码:`app/src/main/java/com/zjr/hesimusic/loop/audio/source/LoopingPcmSourceCore.kt:358-446`(`decodeBlock`:preroll/裁切/短读拒绝)
- 块缓存:`app/src/main/java/com/zjr/hesimusic/loop/audio/source/PcmBlockCache.kt`
- 虚拟容器提取器:`app/src/main/java/com/zjr/hesimusic/loop/audio/source/LoopingPcmExtractor.kt:70-137`
- 预取:`app/src/main/java/com/zjr/hesimusic/loop/audio/source/LoopPcmPrefetcher.kt`
- 时间轴取模:`app/src/main/java/com/zjr/hesimusic/loop/audio/source/LoopingPcmTimeline.kt:66-91`
- 渲染器直通/绕过:`build/media3-src/media3-exoplayer/androidx/media3/exoplayer/audio/MediaCodecAudioRenderer.java:320-343,437-455`
- sink 处理器链:`build/media3-src/media3-exoplayer/androidx/media3/exoplayer/audio/DefaultAudioSink.java:169-236`
- 加载循环/自旋:`build/media3-src/media3-exoplayer/androidx/media3/exoplayer/source/ProgressiveMediaPeriod.java:1102-1163`
- underrun 来源:`DefaultAudioSink.java:2226-2297`(`AudioTrackPositionTracker.onUnderrun`)

---

## 10. 一句话给决策者

**先花几十分钟做 T0/T1(纯 JVM:3 圈逐字节 + 供给离散事件),它们能秒级否掉除 A 以外的方案;再花半天把判据修对(同源基线 + 正对照 + 字节锚点)。若判据证明现有 SOURCE 逐样本正确,修判据后交付、不重构;若证明存在样本级缺陷(尤其 §1.4 的 25ms/圈),不要继续修 SOURCE —— 用方案 A:解码一次写进环,播放按字节取模,接缝从设计里消失。**
