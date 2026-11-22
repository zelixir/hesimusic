# loop-ondemand-bench

00027 追问的离线基准：**用生产类回答"必须完整建环吗"**。
不参与 app 构建，不改 app 代码，不复制实现——直接把下面这几个纯 JVM 生产文件编译进本目录的
class 输出，跑测量与仿真：

- `loop/analysis/Pcm16Segment.kt`、`loop/audio/source/PcmRangeProvider.kt`
- `LoopingPcmTimeline.kt`、`PcmBlockCache.kt`、`LoopingPcmSourceCore.kt`
- `LoopRing.kt`、`RingBandReader.kt`

设计结论与全部数字见 `docs/loop-decode-on-demand.md`；原始输出见 `results-00027.md`。

## 运行

```powershell
powershell -NoProfile -File tools/loop-ondemand-bench/run.ps1                 # 全跑（约 8–10 分钟）
powershell -NoProfile -File tools/loop-ondemand-bench/run.ps1 -Task memory    # 单项
```

需要 JDK 17 + kotlinc（优先用 Android Studio 自带的
`pluginsKotlinkotlincinkotlinc.bat`）。class 输出到 `build/loop-ondemand-bench`（已在 .gitignore 的 /build 下）。

## 任务

| Task | 量什么 | 关键口径 |
|---|---|---|
| `memory` | 整环 vs 块缓存的**真实堆增量** | `-Xmx384m` = 402,653,184B（与 00027 真机一致）；GC 后取最小 |
| `alloc` | 读/装块路径的**分配速率** | `ThreadMXBean.getThreadAllocatedBytes`，按"每播放 1 秒音频"归一 |
| `heapslope` | 1x 墙钟下 usedHeap **斜率**（池化前后） | 与 app `RingBandExtractor.logHeapIfDue` 同口径（total-free），线性拟合 |
| `readcost` | 环带读的 CPU 成本 | ms CPU / 播放秒，及相对实时的倍数 |
| `seamcheck` | 跨 B 是否真有 seek / 最后一块多长 | 生产 `LoopingPcmSourceCore`，多次回绕 |
| `lazyring` | L ≤ 缓存容量时是否收敛成"惰性环" | 10min 虚拟时间内的总解码次数 |
| `prefetch` | 预取窗口 1/2/3/5/6 块 × 5 种解码代价分布的可听欠载 | 两级供给模型：prefetcher + 5s 发射预算 + 1x 消费 |
| `prefetchgate` | 现网闸门 `readyBlockCount<w` vs 修法 `needsDecode` | 统计"加载线程内联解码次数/停顿 ms" |

## 模型与局限（必读）

1. **解码代价是模型不是实测**：steady 20–40ms/块来自上一轮真机；p99 尾部分布是假设。
   真机 `decode done block=N ... ms=` 的分布是唯一还缺的输入（见设计文档 §8）。
2. 仿真的时钟是虚拟离散事件：worker 解码占用"worker 忙到期"，loader 有 5s 发射预算，
   consumer 按 1x 消费；缺口 = delivered < demanded（等价 outputFrames 相对墙钟的 gap）。
3. JVM 的分配/GC 与 ART 不同：**同一 JVM 内前后对比**可信，绝对值只作量级参考。
4. `memory` 的 MB 单位是 MiB（1,048,576B）；文档里同时给字节数，避免"42MB/40MiB"混淆。
