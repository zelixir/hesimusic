# 按需解码 vs 完整建环（00027 追问）— 设计与实测

> 问题：**为什么必须完整建环，能不能按需解码？**
> 本文用生产类在纯 JVM 上实测（工具 `tools/loop-ondemand-bench`，原始输出 `tools/loop-ondemand-bench/results-00027.md`），
> 给出两条路线的对比、按需解码无缝的充分条件、风险清单与最小迁移步数。
> 本文不改 app 代码（设计任务）；真机验证协议见 §8。

## 0. 结论（先看这里）

1. **"完整建环"不是必需的，它换来的是三件东西，其中只有一件不可替代。**
   - 接缝零 I/O 零解码 → **可替代**：块路径的 B→A 根本不是 seek，是 `normalizeFrame` 取模 + 一个普通块边界（§2.1 实测）。
   - 接缝处不欠载 → **可替代**：欠载只取决于供给链的**最差单次代价**，与"是否整环"无关（§5 实测：生产档 5s 队列下 25 个组合全部 0 缺口）。
   - **单解码会话 ⇒ 没有块边界 priming**（每块新起 MediaCodec 的 20–80ms 抖动与爆音）→ 这一条是**结构性**的，
     但可以用"常驻解码会话"在块路径上拿回来，不必付出 O(L) 内存。
2. **块路径现在真正的病不是"没有建环"，是三个可独立修的缺陷**（都已量化）：
   - **预取闸门写错**：`readyBlockCount() < w` 是"缓存里任意位置有 w 块"，不是"播放头前面有 w 块"。
     缓存填满后预取直接停摆，被淘汰的块在播放头到达时由**加载线程内联解码**：
     实测 **380–382 次/10min、累计 11.3–11.6s 加载线程停顿（单次 ~30ms）**——这就是"50ms 内有块重建抖动"。
     改成 `needsDecode()` 闸门后：**2 次/10min、累计 53–68ms**。
   - **读路径每 chunk 一次拷贝**：162KiB/s（块路径）/**172KiB/s（环带路径同样有）**。
     JVM 实测 usedHeap 斜率 **0.2336MiB/s → 池化后 0.0028MiB/s**（≈0，80 倍下降）。
   - **每块重装解码器 + 三次拷贝**：388.1KiB/块（pool 块数组后 224.0KiB/块；常驻会话后 ≈0）。
3. **推荐主线：块路径（按需解码）+ 三步改动**（§7；闸门那一处的可执行改法单独成页：`docs/loop-prefetch-gate-fix.md`），预取窗口 6s→3s，环带只保留为
   "小窗口零延迟特例 + 兜底"（L ≤ 30s，≤5MB）。**最小可行改动 = 3 步，第 4 步可选**。
4. **内存不是"整环 vs 1MB"，而是三个不同的量**（§4，全部实测）：
   整环 43.00MiB（L=238.156s）｜块缓存窗口 358.9KiB(w=2)…1.01MiB(w=6)｜缓存预算 32MiB（容量 190 块 = 190.2s）。

## 1. 两条路线到底是什么

| | 环带（完整建环，`RingBandSource`） | 按需解码（分块，`LoopingPcmExtractorMediaSource` 的 fallback） |
|---|---|---|
| 数据结构 | `LoopRing`：一整个 `ByteArray(L×4B)` + 帧级覆盖位图 | `PcmBlockCache`：1 秒块（`PcmBlock`）× w 块 + 状态机 PENDING/READY/FAILED |
| 解码 | 后台**单会话**顺序/跳填（`RingBandRenderer`），稳态零解码 | 每块**新建 MediaExtractor+MediaCodec**（`PcmDecoderRangeProvider`），带 250ms preroll 后裁掉 |
| 读路径 | `RingBandReader.read`：offset mod L，`copyFrames` 一次 memcpy，不阻塞 | `LoopingPcmSourceCore.read`：命中块 memcpy；未命中返回 null，加载线程内联解码 |
| 接缝 B→A | 取模，无边界（数据在环里） | `normalizeFrame` 回到 `aFrame`=块 0 起点，**一次普通块边界**，不 seek、不 flush |
| 回退 | 超预算（L > heap/6 = 380.4s）→ 现在会 fallback（00027 根因） | 理论上无上限；受缓存预算/解码吞吐约束 |
| 内存 | O(L)：43.00MiB @238.156s | O(w)：1.01MiB @w=6（+解码中间体，见 §3.3） |
| 稳态 CPU | 0.115ms/播放秒（0.012% 单核，8696× 实时） | 0.64 块解码/秒（238s 窗口 + 190 块容量）→ ~2% 单核 |
| 代码面 | 1,612 行（环 369 + 渲染 369 + 提取 284 + 读 123 + 工厂 107 + 解码器适配 326 + 34）：**代码并不更少，但状态机最少** | 1,286 行（core 464 + cache 209 + prefetch 65 + extractor 285 + timeline 143 + 工厂 120）：多出来的是预取/失败/短读防线 |

## 2. "完整建环"换来了什么：逐项拆

### 2.1 接缝零延迟 / 接缝零 I/O —— 块路径同样能做到，不需要特殊处理

生产 `LoopingPcmSourceCore` 的关键三行（`read`）：
```kotlin
nextFrame = timeline.normalizeFrame(rawNext)      // B→A 取模，不 seek
if (rawNext >= timeline.bFrame) { /* seam 回调，仅统计 */ }
```
预取用的是 `wrappedBlock(current + d)`（`Math.floorMod`），**天然跨过 B**。

实测（`seamcheck`，生产类）：窗口 A=23.301s/B=261.457s，L=10,502,679 帧，239 块，
**最后一块 6,879 帧 = 156.0ms**（不足 1 秒），3 次回绕都精确落在 `aFrame`=块 0 起点：

```
wrap#1 beforeFrame=11523375 (lastBlock=238) afterFrame=1027575 (block=0 isA=true) lastBlockMs=156.0
wrap#2 beforeFrame=11523375 (lastBlock=238) afterFrame=1027575 (block=0 isA=true) lastBlockMs=156.0
wrap#3 beforeFrame=11523375 (lastBlock=238) afterFrame=1027575 (block=0 isA=true) lastBlockMs=156.0
```

**所以"接缝预取怎么触发"的答案是：不需要专门的触发。** 块号 `mod blockCount` 已经把 A 处变成
"播放头前方第 k 块"，只要预取闸门看的是**前沿**（§5.3），块 0 会在进入最后一块之前就解好。
唯一的余量差异：L 不是 1 秒整数倍时最后一块只有 156ms，留给"补齐剩余缺口"的时间更短——
这也是"闸门必须看前沿"的又一个理由。

**唯一需要专门处理的不是接缝，是 seek**：`seekToTimelineUs` 命中缓存立即播，未命中由加载线程
内联解一块（一次 c，约 20–80ms），与环带的"跳填"等价。

### 2.2 确定性（接缝不欠载）—— 是"调度性质"，可以买，但要买对地方

环带的确定性来自**构造性事实**：数据在内存里 ⇒ 读不可能失败。
块路径的确定性来自**供给链余量**：只要"当前块在播放头到达时已 READY"就一直成立。
后者不是玄学，条件是明确的（§5）：单块最差代价 `c_max` < 预取窗口，且**预取闸门看前沿**。

实测（`prefetch`，两级供给仿真：worker=prefetcher、loader=extractor 的 5s 发射预算、consumer=1x sink）：

| 模型（单块解码代价） | ahead=5s（生产）缺口 | ahead=1s（收紧）缺口 |
|---|---|---|
| steady 20–40ms | 0 次 / 0ms（w=1..6 全部） | 0 |
| tail p99 800ms | 0（w=1..6） | 0 |
| hiccup 2% 300ms | 0 | 0 |
| throttled 150–350ms | 0 | 0 |
| suspend 0.5% 1500ms | 0（w=1..6） | **w=2/3/5/6 各 1–3 次、4–16ms**（w 不单调） |

结论（两条，读法不同）：
1. **生产档（5s 队列 + 每秒解码）下，预取窗口 1/2/3/5/6 的欠载次数全部为 0**——窗口 2s 与 6s 在"接缝欠载"上没有区别；
   决定余量的是 5s 发射预算与单块代价的尾部。
2. **窗口不是"抗线程冻结"的手段**：把发射预算压到 1s 后，1.5s 冻结在 w=2..6 都会打出 4–16ms 缺口，
   且与 w 不单调。**抗冻结只能靠发射预算（或把 loader 也做成非阻塞），靠加深预取无效。**

### 2.3 实现简单 —— 这条是真的，但它是 00027 的**原因**之一

环形 PCM 的读 = `offset mod L`；不存在块状态、失败跳过、短读拒绝、预取闸门。
反过来：块路径的复杂度全都花在"保证数据在正确的时刻就绪"上，而这恰恰是可以被 3 处改动大幅削掉的。

### 2.4 单解码会话（块边界 priming）—— 唯一结构性收益

块路径每块新起 MediaCodec，`DECODE_PREROLL_US = 250ms` 就是为它打的补丁
（`docs/loop-data-source-design.md` §6.25"块边界爆音/掉档"：新解码器起播几十 ms 电平偏低/爆音）。
环带用一个会话解整窗，这类风险从设计里消失。
但这不是"整环"的必要条件：**常驻解码会话**（同一个 extractor/codec，跨块只 seek 不重建）同样能拿到，
且内存仍是 O(w)（`docs/loop-data-source-design.md` §9「风险与待确认」第 4 条已把"常驻解码会话"列为生产建议）。

## 3. 按需解码要做到无缝，需要满足什么

### 3.1 块大小

1 秒块 + 250ms preroll 是现网参数。块越大 ⇒ 单块代价越高、预取粒度越粗；块越小 ⇒ 重解/边界越多。
**保持不变（1s）**：它已经让"单块代价 / 可播时长"≈ 3%，且与缓存容量（190 块 = 190.2s）对齐良好。

### 3.2 预取窗口（要多少秒）

设单块解码代价 c（含 codec 启动），稳态利用率 u = c/1s，最大单次 c_max，调度/GC 抖动 j：

- **稳态追赶条件**：u < 1（实测 c=20–40ms ⇒ u=2–4%，余量 25–50 倍）；
- **不欠载条件**：播放头前必须有数据，即 **w ≥ (c_max + j)/1s**；
- **实测标定**：c_max ≈ 80ms（p99，历史真机 20–40ms/块 + 尾部）、j(GC) ≈ 10ms ⇒ **w=1 就够**；
- **w 的作用边界**（§2.2 实测）：w 只覆盖"单块解码代价的尾部"，**不覆盖线程冻结**——
  1.5s 冻结在 w=2..6 下都会造成 4–16ms 缺口（与 w 不单调），那由 5s 发射预算兜底。
  取 **w=3** 是给"p99 解码 + GC + 一次 seek 后的重建"留 3 倍余量，不是给冻结留的。

**建议：6 → 3。** 内存从 1.01MiB 降到 517.2KiB，欠载指标不变（§2.2 表）。

### 3.3 解耦：预取窗口 ≠ 内存

内存 = 缓存里 READY 块×176.4KiB + 一个解码中间体（1.25s ≈ 220KiB）+ 5s SampleQueue（862KiB）。
块缓存预算仍是 32MiB 上限（`PcmBlockCache.MAX_BYTES`），窗口只是**下界**：

| 储备 | 238s 窗口实测/推算 |
|---|---|
| 块缓存（w=3） | 517.2KiB |
| 块缓存（w=6） | 1.01MiB |
| 解码中间体（每块临时） | 220.5KiB（可被常驻会话/池吃掉） |
| SampleQueue（5s 发射预算） | 862KiB |
| 合计常驻 | ~1.4–2.1MiB（vs 整环 43.00MiB） |

### 3.4 接缝（A 处）预取怎么触发

- **不需要专门触发**：`wrappedBlock(current+d)` 已跨 B（§2.1）。
- **需要做对的两件事**：
  1. 闸门看**前沿连续就绪块数**，不是缓存里 READY 总数（§5.3，现网 bug）；
  2. L 非整秒时最后一块更短（156ms），前沿判定不能用"块号差×1s"估算，要用
     `blockStartFrame/blockFrames` 的真实帧数（现有 `needsDecode` 已是按块号，OK）。

### 3.5 块复用池

| 分配点 | 现网实测 | 只池化 | 池化 + 常驻会话 |
|---|---|---|---|
| 读 64KiB chunk（`copyOfRange`） | 162.0KiB/播放秒 | **~0**（816B/60s） | ~0 |
| 每块 Pcm16Segment+dropFrames+PcmBlock | 388.1KiB/播放秒 | 224.0KiB/播放秒 | **~0** |
| **usedHeap 斜率（1x 实测）** | **0.2336MiB/s** | **0.0028MiB/s** | 预期 ~0 |

**"能不能把 +0.15MB/s 降到 0"的答案：能，而且是两段式——**
读缓冲池单独就消掉 172KiB/s（**环带路径同样有这部分**，所以它不是分块路径的锅）；
块数组池 + 常驻解码会话消掉剩下的 388KiB/s。

### 3.6 背压与淘汰

- 背压：5s 发射预算（`MAX_AHEAD_SECONDS`）已在，块缓存窗口应 ≤ 它，当前 6s > 5s 属于**无意义超配**（§3.2 建议 3s）。
- 淘汰：现网 `selectVictimLocked` 用 `abs(index-playhead)`，对取模环来说是"伪距离"（跨 B 的块距离被高估），
  建议改为**前向距离** `floorMod(index-playhead, n)`，并显式保护 `[playhead, playhead+w]`。
  实测 L=239s 时每圈重解 49 块（§4），这些重解必须在**进入前沿之前**完成，这就是 §5.3 的闸门问题。

### 3.7 关于"瞬时 280MB 峰值"（00027 现场观测，本文未复现）

本文的 JVM 1x 实测里 usedHeap 峰值只有 **30.8MiB**（60s，current 路径），没有 280MB 这种量级。
280MB 更可能是**三种口径混在同一个数**里，需要 §8 的真机探针区分，先列假设：

| 假设 | 机制 | 判别方法 | 对应处置 |
|---|---|---|---|
| H-A Java used heap | 分配 churn（0.55MiB/s）在 GC 触发前累积；ART 的 GC 触发点是堆比例，402MB 堆允许 used 爬到 200–300MB 才回收 | `dumpsys meminfo` 的 **Java Heap** 行 + `RingBand heap usedHeap=` 日志 | §7 步 1、2（池化）→ 斜率≈0，峰值自然回落 |
| H-B Native heap | 每块新建 MediaCodec 的 native 输入/输出缓冲；codec 释放不及时会瞬时叠加 | `dumpsys meminfo` 的 **Native Heap** 行与 ±30s 内的 `decode done` 次数对齐 | §7 步 4（常驻会话）→ 每块不再新建 codec |
| H-C 新旧源重叠 | 建新源时旧源（整环 43.00MiB + 旧 SampleQueue）尚未释放，快照把两份都算进去 | 环带 `RingBand release bandBytes=` 与 `RingBand select bandBytes=` 的时间顺序 | 与路线无关；确认释放时机即可 |

**本节只提供判别方法，本文的任何结论都不以 280MB 为论据。**
在 H-A/H-B 之间没有分清之前，也不要用"280MB"论证"块路径内存不可接受"：
- 若是 H-A，池化两步就能把峰值压到与环带同级甚至更低（本文实测 30.8 → 16.9MiB）；
- 若是 H-B，说明问题在"每块重开 codec"，那正是 §7 步 4 要解决的，与"是否整环"无关。

## 4. 内存对比（实测，不是估算）

口径：44.1kHz/立体声/16bit = 176,400B/秒 = 176.4KB/s；JVM `-Xmx384m`（402,653,184B，与真机一致）。

| L（秒） | 帧数 | 整环 data | 整环 data+覆盖位图 | 块缓存 w=2 | w=3 | w=5 | w=6 | 容量（块） | 每圈重解块 |
|---|---|---|---|---|---|---|---|---|---|
| 10 | 441,000 | 1.68MiB | 1.73MiB | 344.5KiB | 516.8KiB | 861.3KiB | 1.01MiB | 190 | 0 |
| 30 | 1,323,000 | 5.05MiB | 5.20MiB | 344.5KiB | 516.8KiB | 861.3KiB | 1.01MiB | 190 | 0 |
| 60 | 2,646,000 | 10.09MiB | 10.41MiB | 344.5KiB | 516.8KiB | 861.3KiB | 1.01MiB | 190 | 0 |
| 120 | 5,292,000 | 20.19MiB | 20.82MiB | 344.5KiB | 516.8KiB | 861.3KiB | 1.01MiB | 190 | 0 |
| 150 | 6,615,000 | 25.23MiB | 26.02MiB | 344.5KiB | 516.8KiB | 861.3KiB | 1.01MiB | 190 | 0 |
| **238.2** | 10,502,680 | **40.06MiB** | **41.32MiB** | 344.5KiB | 516.8KiB | 861.3KiB | 1.01MiB | 190 | **49** |
| 300 | 13,230,000 | 50.47MiB | 52.05MiB | 344.5KiB | 516.8KiB | 861.3KiB | 1.01MiB | 190 | 110 |
| 380 | 16,758,000 | 63.93MiB | 65.92MiB | 344.5KiB | 516.8KiB | 861.3KiB | 1.01MiB | 190 | 190 |

实测堆增量（GC 后取最小）：`LoopRing` L=238.156s = **43.00MiB**
（data 40.06MiB = 42,010,720B = 现场说的"42MB"，coverage 1.25MiB，位图不是零头）；
`PcmBlockCache` w=2/3/5/6 = **358.9 / 517.2 / 862.0 / 1.01MiB**（含 map 开销）。

两个由此得出的**硬边界**（都已在代码里）：
- 整环可行上限：heap/6 = 64MiB ⇒ **380.4s**（42,010,720B 只占 67,108,864B 预算的 62.6%，这就是 00027 里"提到 heap/6 就够"的原因）；
- 块缓存容量：32MiB / 176,400B = **190 块 = 190.2s**。
  ⇒ **L ≤ 190s 时块缓存会收敛成"惰性环"**（见 §4.1），**190s < L ≤ 380s 才是整环严格占优的区间**。

### 4.1 关键推论：L ≤ 缓存容量时，按需解码 = 惰性环（实测）

`lazyring`（steady 20–40ms，10min）：

| L(s) | 块数 | 10min 内总解码次数 | 缺口 |
|---|---|---|---|
| 90 | 90 | 90 | 0 |
| 150 | 150 | 150 | 0 |
| 190 | 190 | 190 | 0 |
| 239 | 239 | **386**（一圈 239 + 每圈重解 ~49×3） | 0 |

即：**窗口装得下缓存时，第一圈解完后再也不解码**——和环带的稳态行为完全一样，
只是"惰性填充 + 预算封顶"。整环的不可替代性只在 **L > 190s** 时出现，而且那也不是
"必须整环"，而是"必须处理 49 块/圈的重解"（§3.6 + §5.3 已给出做法）。

## 5. 预取 / 供给链实测

模型：worker = `LoopPcmPrefetcher`（`readyBlockCount < w` 且 `needsDecode` 时连解）；
loader = `LoopingPcmExtractor`（发射预算 5s，未就绪块**内联解码**，阻塞 loader）；
consumer = 1x；缺口判定 = `delivered < demanded`（等价于 outputFrames 相对墙钟的 gap）。
场景 = 10min/组、w ∈ {1,2,3,5,6}、5 种代价分布、固定种子。

### 5.1 生产档（ahead=5s）

25 个组合**全部 0 缺口**（含 1.7s 线程挂起）。说明：**当前 5s 发射预算把窗口 2/3/5/6 的差别完全掩盖了。**

### 5.2 收紧档（ahead=1s，用来暴露窗口的真实作用）

只有 1.5s 挂起会破防：w=2 起各 1–3 次、4–16ms，且**与 w 不单调**（w=5 的 3 次/16ms 比 w=2 的 1 次/4ms 更差）。
⇒ **若将来把发射预算压小（例如为降内存），窗口必须 ≥2，推荐 3。**

### 5.3 真正的抖动源：预取闸门（实测 380 次/10min 加载线程停顿）

现网 `LoopPcmPrefetcher`：
```kotlin
while (core.readyBlockCount() < targetBlocks && core.needsDecode() && guard < targetBlocks * 2)
```
`readyBlockCount()` 是**缓存内所有 READY 块**。L=239s、容量 190 块时，第一圈后缓存常驻 ~190 块
⇒ 闸门立刻为 false ⇒ 预取线程停摆；被淘汰的 49 块/圈只能在播放头到达时由**加载线程内联解码**。

`prefetchgate`（10min，L=238.156s，ahead=5s）：

| w | 闸门 | 加载线程内联解码次数 | 内联停顿累计 | 总解码次数 | 缺口 |
|---|---|---|---|---|---|
| 2 | prod `readyCount<w` | **382** | **11,378ms** | 384 | 0 |
| 2 | fix `needsDecode` | **2** | **53ms** | 401 | 0 |
| 3 | prod | 381 | 11,316ms | 384 | 0 |
| 3 | fix | 2 | 68ms | 466 | 0 |
| 6 | prod | 380 | 11,550ms | 384 | 0 |
| 6 | fix | 2 | 54ms | 664 | 0 |

读法：
- **"50ms 内有块重建抖动" = 这 380 次内联解码**（平均 30ms/次，11.5s/10min 直接吃在加载线程上）。
  它今天没有变成可听欠载，只是因为 5s 队列在兜底；但它把安全边际从 5s 压到"5s − 抖动尾部"。
- 修法**一行**：把闸门换成 `needsDecode()`（它本来就是按"当前块 + 前 w 块"判定的）。
  代价是总解码次数上升（w=6 时 664 次/10min = 1.1 次/秒，仍只有 ~3–4% 单核），换来 0 加载线程停顿。
- 若同时把 w 降到 3：解码次数 466 次/10min，内联停顿 68ms。

## 6. 风险清单（按"会不会变成可听缺陷"排序）

| # | 风险 | 证据/机制 | 现在会不会可听 | 处置 |
|---|---|---|---|---|
| R1 | **预取闸门停摆 → 加载线程内联解码** | §5.3：380 次/10min、累计 11.5s | 不会（5s 队列兜底），但吃掉 40% 余量 | **一行修**（§7 步 3） |
| **R1b** | **R1 的观测盲区**：停摆表现为**周期性微卡**（单次 ~30ms 的加载线程停顿），不是欠载 | §5.3：11.5s/10min 的停顿全部落在加载线程，5s 队列把它们吸收成"没断" | **不会在 outputFrames 上暴露**：gap 判据恒为 0，/state 采样、tap 无零段、NCC 正常 | **判据必须换**：看单次 `decode start→done` 的**发起时刻分布**（是否提前于播放头 ≥w），以及加载线程 `pumpDecode` 调用次数（修复前 ~0.64 次/秒，修复后 ≈0）。见 §8 与 `docs/loop-prefetch-gate-fix.md` §6 |
| R2 | **块边界 priming / 爆音** | 每块新起 MediaCodec，`docs/loop-data-source-design.md` §6.25 | 可能（250ms preroll 是补丁，不是证明） | 常驻解码会话（§7 步 4）；现网保留 preroll |
| R3 | **分配 churn → usedHeap 斜率/GC 抖动** | §3.5：0.2336MiB/s（池化 0.0028） | 不会（GC 7–11ms/5 次/分钟，远小于 40ms HAL 缓冲） | 池化（§7 步 1、2） |
| R4 | 解码代价尾部（codec 启动 20–80ms、p99 300ms+） | 历史真机 20–40ms；仿真 p99 800ms 仍 0 缺口 | 不会（w≥2 + 5s 队列） | 常驻会话削尾；窗口 ≥3 |
| R5 | IO 抖动（SD/FUSE 冷读、后台限流） | 单块解码内含一次文件读；未见真机数据 | **未知**（需要 §8 的窗口） | 常驻会话保持 fd；窗口 ≥3；必要时磁盘块缓存（二期） |
| R6 | 后台限流/线程挂起 1.5s+ | 仿真 ahead=1s：w=2..6 各 1–3 次/4–16ms（不单调）；ahead=5s：全 0 | 不会（生产档） | 抗冻结靠发射预算，**不靠加深窗口** |
| R7 | 淘汰策略用伪距离（跨 B 高估） | `selectVictimLocked` 用 `abs(index-playhead)` | 不直接可听，放大 R1 | 改前向距离 + 显式保护前沿（§3.6） |
| R8 | L 非整秒 → 最后一块 156ms | §2.1 实测 6,879 帧 | 不会，但余量更小 | 闸门/预取用真实块帧数（现网已按块号） |
| R9 | seek 到未缓存块的一次解码延迟 | 20–80ms（= 一次 c） | 用户可感（拖动后首声） | 环带可做到常数时间；块路径保留"当前块优先"泵 |

## 7. 迁移路径与最小可行改动步数

**目标形态：主线 = 按需解码（w=3、32MiB 缓存上限）；环带 = L ≤ 30s 的零延迟特例 + 超预算兜底。**

| 步 | 改动 | 文件（≤2 个） | 预期收益（实测口径） | 风险 |
|---|---|---|---|---|
| **1** | 读路径复用缓冲：`read()` 写进调用方/核心自持的 `ByteArray` | `LoopingPcmSourceCore.kt`, `LoopingPcmExtractor.kt`（+`LoopingPcmSampleStream.kt`） | 162KiB/s → ~0（环带同理 172KiB/s→0，需 `RingBandReader` 一起做） | 低（纯 memcpy 目标） |
| **2** | 块数组池：`PcmBlockCache` 淘汰时回收 `ByteArray`，`decodeBlock` 写进池块（顺带去掉 dropFrames/takeFrames 两次拷贝） | `PcmBlockCache.kt`, `LoopingPcmSourceCore.kt` | 388.1 → 224.0KiB/秒；usedHeap 斜率 0.2336→0.0028MiB/s | 低/中（拷贝语义要单测） |
| **3** | **预取闸门**：`readyBlockCount() < w` → `needsDecode()`（含 finally 重踢条件）；w 6→3；淘汰改前向距离。**逐行改法见 `docs/loop-prefetch-gate-fix.md`** | `LoopPcmPrefetcher.kt`, `LoopingPcmExtractorMediaSource.kt`(`SOURCE_PREFETCH_BLOCKS`), `PcmBlockCache.kt` | 加载线程内联停顿 380 次/11.5s → **2 次/53–68ms**；内存 1.01→0.52MiB | 低（有 `needsDecode` 现成语义） |
| 4（可选） | 常驻解码会话：`PcmDecoder` 增加"复用 extractor/codec 的 decodeInto"；`PcmDecoderRangeProvider` 持有会话 | `PcmDecoder.kt`, `PcmDecoderRangeProvider.kt` | 224KiB/s → ~0；砍掉 20–80ms/块的 codec 启动尾部（= R2/R4 的结构性处置） | 中（MediaCodec 生命周期/seek 语义，单独一轮） |
| 5（收口） | 选路策略：L ≤ 30s 走环带，其余走块路径；去掉"42MB 才刚好建环"的预算焦虑 | `LoopingPcmExtractorMediaSource.kt`, `RingBandSource.kt` | 00027 的长窗口不再依赖"把预算抬到 heap/6" | 低 |

**最小可行改动 = 3 步**（1+2+3）：完成即可拿到"零稳态分配、零加载线程停顿、窗口 3s、内存 ~0.5MiB"，
并不再需要为 238s 窗口调预算。第 4 步是把"块边界 priming/解码尾部"从设计里删掉的正解，
但它涉及 MediaCodec 会话生命周期，建议单独一轮（可与 00027 的显示层修复并行）。

**建议的验收顺序**：
1. 每步先跑 `tools/loop-ondemand-bench`（离线、秒级、无需设备）对齐本文的收益列；
2. 第 1/2 步后跑现有单测（`PcmBlockCacheTest`/`LoopingPcmSourceCoreLoopTest`/`LoopingPcmExtractorTest`）；
3. 第 3 步后跑真机（§8）确认 `read throttle`/`decode done`/`cacheBytes` 三条日志与 outputFrames 无 gap；
4. 第 4 步单独压测：连续 10 分钟 + 20 次 seek，看 `decode done ... ms=` 的 p99 是否消失。

## 8. 需要真机窗口的验证（我目前只做了 JVM 侧）

设备（b1e84dad）正在被占用，以下协议**只需跑一次 10 分钟**，全部用现成日志，不改 app：

| 要量什么 | 现成探针 | 判定 |
|---|---|---|
| 单块解码代价分布（本文仿真的关键输入） | `logcat -s LoopPcmSource` 里的 `decode done block=N ... ms=` | 取 p50/p99/max，回填 §3.2 的 c_max |
| 可听欠载 | `/state` 的 `outputFrames` 20Hz 采样 vs 墙钟 | Δ帧/Δ秒 < 0.98 或瞬时 gap > 2ms 记一次 |
| 缓存/窗口行为 | `LoopPcmSource cacheBytes=... blocks=... bufferedUs=` | 稳态 blocks 是否 ≈ 190、bufferedUs 是否 ≥ 5s |
| 加载线程停顿 | `decode start block=` 与 `decode done` 的时间差 + `read throttle` | 内联次数应与本文 380 次/10min 同量级 |
| 堆/PSS 斜率 | `adb shell dumpsys meminfo com.zjr.hesimusic` 每 30s + `RingBand heap usedHeap=`（环带路径） | usedHeap 斜率应 ≈ 分配率（0.55MB/s），**GC 后 live 应持平**（本文实测 6.2→6.8MiB） |
| 接缝内容真值 | `tools/loop-tap-capture.ps1` + `bun run tools/loop-smoothness.ts` | 跨 B 前后 NCC/无 ≥5ms 零段 |

> **判据盲区（R1b）**：上表的 outputFrames gap 判据**对"预取停摆 / 加载线程内联解码"完全不敏感**
> （5s 发射预算会把 ~30ms 的停顿吸收掉）。因此真机窗口必须同时采 `decode start block=` 与
> `decode done ... ms=` 的时间点/耗时，判断"解码是否发生在播放头到达之前 ≥w 秒"，而不是只看有没有 gap。

**触发条件**：需要一个窗口强制走块路径（L > 380s 的窗口，或临时把 `BUDGET_DIVISOR_MAIN` 当 debug 参数压低）。
若要按 §5.3 对比"闸门修复前后"，需要一个 debug-only 参数（`-PdevPrefetchBlocks` 或 debug 设置项）——
这是**唯一需要动 app 代码的验证件**，等主线批准后再加。

## 9. 复现

```bash
# 全跑（约 6 分钟；heapslope 是 1x 墙钟，最慢）
powershell -NoProfile -File tools/loop-ondemand-bench/run.ps1
# 分项
powershell -NoProfile -File tools/loop-ondemand-bench/run.ps1 -Task memory
powershell -NoProfile -File tools/loop-ondemand-bench/run.ps1 -Task alloc
powershell -NoProfile -File tools/loop-ondemand-bench/run.ps1 -Task heapslope
powershell -NoProfile -File tools/loop-ondemand-bench/run.ps1 -Task prefetch
powershell -NoProfile -File tools/loop-ondemand-bench/run.ps1 -Task lazyring
powershell -NoProfile -File tools/loop-ondemand-bench/run.ps1 -Task prefetchgate
powershell -NoProfile -File tools/loop-ondemand-bench/run.ps1 -Task seamcheck
powershell -NoProfile -File tools/loop-ondemand-bench/run.ps1 -Task readcost
```

工具只编译**纯 JVM 的生产文件**：`Pcm16Segment.kt`、`PcmRangeProvider.kt`、
`LoopingPcmTimeline.kt`、`PcmBlockCache.kt`、`LoopingPcmSourceCore.kt`、
`LoopRing.kt`、`RingBandReader.kt`（读真实实现，不复制）。

**局限（必须如实标注）**：
1. 解码代价是**模型**（20–40ms/块来自 2026-11 上一轮真机，p99 尾部为假设），不是本文实测；
   `decode done ... ms=` 的真机分布是唯一还缺的输入（§8 第一行）。
2. JVM 的 GC/分配统计与 ART 不同；本文比较的是"同一 JVM 内池化前后"，斜率比值可信，绝对值只作量级参考。
3. `-Xmx384m` 复现了真机 402,653,184B 堆，因此 `heap/6` 与 32MiB 缓存上限与 app 完全一致。
4. 仿真里 `provider.decode` 不分配真实 PCM 数据量（只放在堆上均值），对时序无影响。