# Loop Lab — 多算法循环分析对比（本地 Web 工具）

同一个音频文件，在浏览器里同时跑 **A–E 五档循环分析算法**，在波形上对比候选、直接试听整段循环或接缝，并导出带完整指标的 JSON。纯静态页面 + 一个 Bun 静态服务器，**不联网、无外部 CDN、字体与图标全部内联/本地**。

> 目的：先在电脑上判断"哪种算法/参数更准"，**不改 app**。现网 Android 引擎是 PyMusicLooper 3.6.0 的 C++ 移植（`app/src/main/cpp/loop_engine/loop_engine.cpp`），A 档严格复刻它的语义。

---

## 1. 启动与访问

仓库根目录：

```bash
cd D:\Code\hesimusic
bun run tools/loop-web/server.ts
```

或进入工具目录：

```bash
cd D:\Code\hesimusic\tools\loop-web
bun run dev        # 等价于 bun run server.ts
```

默认地址 **http://127.0.0.1:8899**。若 8899 被占用，服务器会从 8899 开始逐个 +1 尝试，并在控制台打印实际端口：

```
  Loop Lab — multi-algorithm loop analysis
  serving: D:\Code\hesimusic\tools\loop-web\public
  open:    http://127.0.0.1:8899
  stop:    Ctrl+C
```

无需安装依赖（Bun 内置 HTTP 服务器与 TypeScript 执行）。页面会把音频解码为 `AudioBuffer`，把单声道 PCM 交给 Web Worker 分析，UI 线程只负责波形/播放。

### 自检

```bash
cd D:\Code\hesimusic\tools\loop-web
bun run selftest
# 或从仓库根：bun run tools/loop-web/selftest.ts
```

自检会：生成 20 s 合成音乐 → 验证 A 有候选且 <3 s；把同一段 PCM 写成 WAV 交给现网 C++ CLI，逐项对比 loopStart/loopEnd/score/chromaDist/loudnessDiff；验证 D 的召回字段；用"2 s 片段复制到 20.5 s"的合成用例验证 E 能找到 A=0.5 s / B=21.0 s、峰值 >0.9；验证重叠规则确实丢掉了自重叠对。产物在 `.selftest/`，可删除/重建。

---

## 2. 目录结构

```
tools/loop-web/
  server.ts                 Bun 静态服务器（端口 8899 起，自动 +1）
  package.json              dev / start / selftest 脚本
  selftest.ts               一条命令跑完的自检（含 C++ CLI 对照）
  public/
    index.html              单页 UI
    styles.css              深色主题，全部本地样式
    app.js                  UI 状态机 / 波形 / 表格 / 试听 / 导出
    worker.js               Worker 入口：只做 analyze() 与进度上报
    analysis/
      fft.js                numpy 语义 FFT（radix-2 + 384 点混合基）
      dsp.js                窗函数/分位数/中位数/PRNG
      features.js           STFT、A 计权、mel、chroma、tuning、onset、CMAX、
                            MFCC/RMS/flux（D 用）
      rhythm.js             PLP、tempogram、beat_track DP、beat 网格并集
      pairs.js              配对、剪枝、子序列评分、过零点吸附
      seam.js               接缝质量（互相关/RMS/频谱通量/样点跳变）与 E 的 8k 选点
      algd.js               D：beat 向量 + PCA/int8 + 随机投影 LSH
      alge.js               E：log-mel 锚点粗筛 + 8k NCC 验证 + 区间选点
      linalg.js             Jacobi 特征分解、QR
      pipeline.js           编排 A–E、召回率、计时、JSON 数据模型
```

所有分析代码都是**纯 ESM、无 DOM 依赖**的模块，因此既能被浏览器 Worker 加载，也能被 Bun 直接 import（`selftest.ts` 正是这样复用的）。

---

## 3. 算法档位

### A. baseline（复刻现网 C++）
- 单声道混合 → 峰值归一化 → 按 top_db=40 修剪首尾静音；
- STFT 2048/512，Hann 周期窗（居中帧，`T = floor(M/512)+1`）；
- A 计权功率 dB → mel 128（fmax 8 kHz）→ onset（mel 正差均值）；
- chroma：22050 Hz 默认率下的 librosa 滤波器组（tuning 由 piptrack 直方图估计）；
- PLP（384 点 FFT、hop=1、librosa istft 的 split OLA）+ beat_track（localscore/DP/tightness=100）→ 并集得到 beat 网格；
- 所有 beat 对中满足 `minLoop ≤ len ≤ maxLoop`（默认 35% 总时长 ~ 总时长）；
- 条件：`chroma 距离 ≤ 0.0875 × |chroma_end|` 且 `|CMAX dB 差| ≤ 0.5`；
- 剪枝（≥100 对时按 50%/75% 分位）→ 前后向加权余弦评分 → 稳定排序 → topN；
- 帧→采样点（加回 trim 偏移）→ Audacity 风格最近过零点吸附。

### B. +seamQuality
对 A 的候选池（默认 top 40）逐对计算：
- `[B-50ms,B)` 与 `[A,A+50ms)` 的波形归一化互相关：最佳 lag（±10 ms，4× 抽取 + 抛物线插值）与峰值；
- 两窗短时 RMS 的 dB 差；
- 接缝处的频谱通量突增（1024/256 帧，接缝帧 vs 两侧中位数）；
- 接缝样点跳变（`|y[0]-x[-1]| / 局部 RMS`）。
合成为 `[0,1]` 的 `seamQuality`，用于排序（`0.6×seam + 0.4×score`）和可选过滤（阈值 UI 可调）。A 的 topN 行也会显示该值，方便同表对比。

### C. +sub-beat 精修
以 B 的 top 候选为起点，在 A、B 各自 ±30 ms 内做坐标下降：先 5 ms 网格、再 1 ms 网格，目标是最大化 `seamQuality`；受 `subbeatBudgetMs` 预算与最短循环长度约束。表格显示精修前后的 ΔA/ΔB 与评估次数。注意：C 的 `score/chromaDist` 仍来自原始 beat 对，精修只改变端点与接缝指标（避免用不到一帧的样本重算 chroma）。

### D. LSH（beat 向量 + 聚类粗筛 + 精筛）
每个 beat 取 ±250 ms 短窗，聚合为 27 维：
`chroma 均值(12) + MFCC 均值(13) + RMS(1) + 频谱通量(1)`。
逐维 z-score → L2 归一化；可选 **PCA 到 12 维**、可选 **int8 量化**。
候选生成：时间窗约束（长度必须在 [minLoop,maxLoop]，且锚点间隔 ≥ 2×窗长不适用于此档）+ **随机投影 LSH**（表数 m、桶宽 w 可调）；对命中的 pair 计算余弦相似度，取精筛池前若干名 → 过零点吸附 → seamQuality；可选 D 自己的 sub-beat 精修。
同一文件、同一参数下，A 的暴力全配对始终会跑（即使 UI 没勾 A），用于计算召回率。

### E. 整秒重合（三级：粗筛 → 波形验证 → 选点）
1. **粗筛（宁多勿漏）**：锚点默认每 0.25 s 一个；每个锚点取 1 s log-mel patch（64 mel × 31 帧 @ 32 ms hop = **1984 维**）；余弦相似度；时间窗约束且**两个锚点距离 ≥ 2×窗长**（硬规则，见第 8 节）；保留 topK（默认 2000）且相似度 ≥ 阈值（默认 0.5）的对。可选半秒滑动多向量（UI 0/2/4，未压缩时生效）。
2. **验证**：把锚点窗口拉长到 **4 s**，降采样到 8 kHz，用 FFT 计算 **归一化互相关（NCC）**，一次性扫 ±4 s 的所有 lag；输出 bestLag、nccPeak、overlapRatio。峰值 ≥ 阈值（默认 0.8）才算真重合；收集所有 ≥ 0.95×峰值 的多峰，每个峰都进入第三级。
3. **选点**：在对齐后的重叠区间内以 50 ms 步长滑动 50 ms 接缝窗，综合"过零点接近度 / 前后短时能量差 / 频谱通量突变 / 样点跳变 / 200 ms 内容 NCC"选最优，再 5 ms→1 ms 细化，得到最终 A/B。
4. **压缩质量**：可选 不压缩 / **PCA 白化到 256 维** / **随机投影 256 维**。PCA 报告方差保留率；RP 报告距离失真；两种压缩都会额外计算"降维前后 top-20 候选重合率"。

---

## 4. 参数面板

- 基础：minLoop/maxLoop（留空 = auto：0.35×总时长 / 总时长）、chroma 阈值、dB 阈值、topN、**降采样 beat（每 k 拍取一个）**。
- 接缝/精修：seam 排序开关、seam 过滤阈值、接缝候选池、C 的候选数与预算。
- D：表数 m、桶宽 w、int8、PCA-12、精筛池、D 的 sub-beat。
- E：锚点间距、压缩方式、粗筛 topK/余弦阈值、验证对数上限/峰值阈值/预算、选点步长、滑动多向量、锚点上限。

"分析"按钮自动串行执行勾选的档位；**每个算法都在同一个 Worker 里顺序执行**，UI 不阻塞，进度条显示阶段。再次点击分析/载入新文件会终止旧 Worker。

---

## 5. 指标物理含义

| 指标 | 含义 |
|---|---|
| `score` | A 的前后向加权 chroma 子序列余弦相似度，取 max；越大越好（0~1）。 |
| `chromaDist` | 两个 beat 处 12 维归一化 chroma 的 L2 距离；阈值与 end 的 chroma 范数相关（0.0875 × norm(chroma_end)）。 |
| `loudnessDiff` | 两个 beat 处 CMAX（A 计权功率 dB 的逐帧最大值）之差的绝对值，单位 dB；阈值 0.5。 |
| `seamQuality` | 0.15×互相关分 + 0.25×响度分 + 0.35×频谱通量分 + 0.25×样点跳变分，范围 0~1。 |
| `xcorrLagMs` / `xcorrPeak` | 尾部 50 ms 与头部 50 ms 的归一化互相关最佳 lag（ms，带符号）与峰值（0~1）。峰值 1 表示波形高度相似；lag 非 0 表示接缝处存在相位/时移。**它衡量"两侧内容是否像"，不是唯一的 click 检测器**，故权重较低。 |
| 接缝 RMS 差 | 两窗 50 ms RMS 的 dB 差；3 dB 已明显可闻。 |
| 频谱通量突增 | 接缝帧的谱通量相对两侧中位数的超出量；click/爆音会显著抬高。 |
| 接缝样点跳变 | `abs(y[0]-x[-1]) / 局部 RMS`；硬切/爆音的直观指标。 |
| D 相似度 | 27 维 beat 描述子的余弦相似度。 |
| D 表命中数 | 该 beat 对在多少张 LSH 表中落入同一桶；越大越可靠。 |
| D 命中/仅 LSH/仅暴力 | 该 pair 是否也出现在 A 的暴力阈值通过集合中（精确 startF:endF 匹配）。 |
| E 粗筛相似度 | 1 s log-mel patch 的余弦相似度。 |
| E nccPeak / bestLag / overlapRatio | 4 s、8 kHz 归一化互相关的峰值、最佳时移与重叠比例。 |
| E 选点分 | 0.1×零交叉 + 0.2×能量 + 0.25×通量 + 0.15×样点跳变 + 0.3×内容 NCC（8 kHz，200 ms）。 |
| 方差保留率 | PCA 前 256 维特征值之和 / 总方差；越高说明压缩保留的信息越多。 |
| 距离失真 | 随机投影前后欧氏距离的相对误差（抽样均值/最大）；目标 <5%。 |
| top-20 重合率 | 压缩前后按余弦排名的前 20 对候选的重合比例；只反映**第一级粗筛**的稳定性。 |
| 每帧耗时 / 每 beat 配对数 | 特征阶段总耗时 ÷ STFT 帧数；配对检查数 ÷ 参与配对的 beat 数。用于判断瓶颈在 STFT 还是 O(B²) 配对。 |

---

## 6. 与 C++ 引擎的语义一致性

A 档按 `loop_engine.cpp` 逐阶段移植，包括容易忽略的细节：librosa 默认 22050 Hz 造成的 chroma/tempo 语义、tuning 直方图、PLP 的 split OLA 相位、beat_track 的 localscore/DP/trim、CMAX 的二次 `10log10`、过零点吸附的 0.4/0.1 惩罚项等。

**实测对照**（20 s 合成音乐，同一份 16-bit WAV 输入，C++ CLI `tools/loop-engine-cli/build/loop-engine-cli.exe --max 5 --json`）：

```
#1 JS A/B=6684/358941  CLI=6684/358941  score 0.982639 vs 0.982639
#2 JS A/B=12283/364504  CLI=12283/364504  score 0.982207 vs 0.982207
#3 JS A/B=33860/386497  CLI=33860/386497  score 0.981131 vs 0.981131
#4 JS A/B=28638/380888  CLI=28638/380888  score 0.979587 vs 0.979587
#5 JS A/B=55789/408577  CLI=55789/408577  score 0.978282 vs 0.978282
max |dA| = 0, max |dB| = 0
max |dscore| = 4.53e-7, max |dchroma| = 7.49e-8, max |dLoudness| = 4.46e-8
```

### 已知差异（不改变候选语义，但需要知道）

1. **单声道吸附**：app 的过零点吸附跑在原交错立体声上；浏览器工具在 mono 混合后吸附（与 app 处理单声道文件时完全一致）。立体声文件的两端可能相差几个采样点。
2. **两遍 STFT**：为省内存不保存 T×1025 的 power/weighted 矩阵，改为两遍 STFT；数学输入相同。中间特征以 Float32 保存（C++ 用 double），实测 top5 的 loop 点与指标差异在 1e-7 量级。
3. **CMAX 全局参考常量**：C++ 的 `powerDbColMax` 会减去 `dbRef = 10log10(median(sWeighed))`；本工具省略该常量。因为所有消费者只比较两个 CMAX 的差，常量在差值中精确抵消（实测 loudnessDiff 差异 <5e-8），也不影响阈值。
4. **长度范围可覆盖**：C++ 写死 minLoop=35% 总时长、maxLoop=总时长；UI 留空时与 C++ 完全一致，填值则覆盖（用于探索）。
5. **beat 降采样**：默认关闭（stride=1）。超过 10 分钟时 UI 提示并可每 k 拍取一个，这会减少候选与 O(B²) 开销，但不再等价于 C++ 的完整网格。
6. **评分阶段**：C 的精修端点不重算 chroma（不到一帧的移动对 score 影响可忽略）；表格中的 score/chromaDist 仍是原始 beat 对的值。
7. **立体声分析**：工具把多声道平均成 mono 后再分析（与 C++ 的 to_mono 一致），但吸附按 mono 执行（见 1）。

B/C/D/E 是新增实验档，C++ 没有对应实现；它们都复用同一 beat 网格/音频，不改 A 的结果。

---

## 7. UI 与试听

- **波形**：整段时长线性映射到画布像素，逐像素 min/max 降采样，首尾对齐；下方时间刻度。
- **标记**：默认每种算法显示前 3 对（A 实线、B 虚线，按算法着色），勾选"显示全部标记"可显示全部 topN；选中的候选始终高亮并标出 A/B。
- **悬停**：靠近任意 A/B 标记线显示 tooltip（ms、长度、score、chroma、loudness、seamQuality、xcorr lag/peak，D/E 额外字段）。
- **点击**：点表格行或波形标记 → 选中并**立即循环试听**（整段循环模式）；双击行可重新开始。
- **试听模式**：
  - 整段循环：`[A,B)` 无缝循环（`AudioBufferSourceNode.loop`）。
  - 接缝模式：只循环 `[B-2s,B)` + `[A,A+2s)`（段长自动裁剪到可用长度），用前视调度器无缝拼接。
  - "跳到接缝"：整段模式从 B 前 1.5 s 开始；接缝模式从 B 前 0.35 s 的短段开始。
  - 音量滑杆实时作用在 master gain；播放/暂停/空格快捷键。
- **分析耗时**：每个算法卡片显示总耗时、帧数/beat 数、每帧耗时、每 beat 配对数、分段耗时；D 还显示召回率/top1 变化；E 显示锚点、验证数、三段计时与压缩质量。
- **长文件护栏**：>10 分钟显示黄色警告（"配对阶段 O(B²)，可能很慢"），并提供"每 2 拍降采样"一键开关。

---

## 8. 性能与复杂度（本机实测）

测试环境：Windows + Bun 1.3.11（Chromium 同机），44.1 kHz mono，合成音乐；数字为整条 pipeline 的 wall time（A+B+C；D/E 另计）。

| 音频时长 | 帧数 / beat | 配对检查数 | 总耗时 | 备注 |
|---|---|---|---|---|
| 20 s | 1,723 / 187 | 7,586 | **0.27 s** | 远低于 3 s 要求 |
| 60 s | 5,168 / 543 | 62,849 | 0.64 s | |
| 180 s (3 min) | 15,504 / 1,627 | 559,368 | 1.53 s | ≈0.51 s/分钟 |
| 360 s (6 min) | 31,008 / 3,242 | 2,218,852 | 3.32 s | ≈0.55 s/分钟 |

- **常规 3~6 分钟歌曲由 STFT/chroma 特征主导**：每帧约 0.06–0.08 ms，180 s 时特征+节拍约 1.35 s，配对仅 ~15 ms、评分 ~80 ms。经验值约 **0.55–0.65 s/分钟音频**（比"0.8–1 s/分钟"更宽裕；机器不同会有波动）。
- **长音频/密集 beat 由 B² 主导**：每 beat 配对数从 20 s 的 ~41 增到 6 min 的 ~684（≈17 倍），配对检查数平方增长。10 min 以上建议开启 beat 降采样（每 k 拍 → 配对数约 1/k²）。
- 20 s 全开（A+B+C+D+E，E 验证上限 120 对）约 **1.0–1.3 s**；E 的 8k NCC 是主要增量（131 对 ≈ 0.85 s，单对 3 次 65536 点 FFT）。
- 20 s 文件始终 <3 s；长文件的分析时间与内存随样本数线性增长（解码 + mono + 单遍特征），E 的锚点/窗口为常数级额外内存。

---

## 9. D 的召回率：LSH 的漏检是 silent failure

LSH 不会报错，只是候选里"没有"——所以必须和暴力全配对对照。工具固定做三件事：

1. 同一文件、同一参数下，**A 的暴力全配对始终运行**（即使 UI 没勾 A），得到全部"通过阈值"的 pair 集合；
2. 计算：
   - **全量召回** = |LSH 生成候选 ∩ 暴力阈值通过集合| / |暴力集合|；
   - **topN 覆盖** = 暴力 topN 中，有多少 pair 出现在 LSH 的任意候选中（衡量"真候选有没有被漏掉"）；
   - **最终 topN 交集** = D 精筛/重排后的 topN 与 A topN 的交集（会受重新排序影响，故单列）；
   - **top1 是否变化**；
3. 在表格中标出每条的"命中 / 仅 LSH / 仅暴力"（仅暴力行也会追加到表里），并把 `bruteTop` / `lshTop` 集合写入导出 JSON。

实测（20 s 合成音乐）：暴力集合 178 对，LSH 生成 7,462 对，**全量召回 100%**、topN 覆盖 100%、最终 topN 交集 10%、top1 变化。60 s 音乐开启 PCA-12+int8 时全量召回 94.1%、topN 覆盖 100%；随机投影 256+int8 时全量召回 74.2%、topN 覆盖 90%。这说明：**LSH 的漏检/排序变化是真实存在的，必须用数据说话**；短歌本身配对很便宜，D 的收益主要在大文件。

UI 顶部徽章显示"召回率 / 全量命中 / 耗时 / top1 是否变化"，**<99% 时变黄**；下方 recall 面板展开计数与集合。

---

## 10. E 的"区间重合"坑（务必读）

### 10.1 为什么需要距离 ≥ 2×窗长
两个锚点的窗口如果相邻或重叠，NCC 在 `lag ≈ 两点距离` 处存在**平凡解**：把一个窗口平移过去就能和自己完全重合，峰值接近 1，比真接缝还高。这是这类方法最容易踩的坑。因此第一级直接要求 `|anchorB-anchorA| ≥ 2×4 s = 8 s`；被丢弃的对数在 UI/导出里单独统计（合成用例中丢掉了 2,759 对）。

### 10.2 为什么必须用 NCC 而不是点积
重叠部分会主导点积；归一化互相关按重叠能量归一，重叠比例还会作为 `overlapRatio` 显示。NCC 在 ±4 s 的所有 lag 上搜索，不只比较 lag=0。

### 10.3 网格只决定"从哪找"，lag 搜索决定"真正在哪对齐"
典型例子：真实对应是 `10.0–11.0 == 84.5–85.5`（偏移 74.5 s），但锚点网格只有 84.0/84.5/85.0。1 s 窗口在 84.0 时只有 50% 真重合，NCC 峰值 0.5~0.7，会被 0.8 阈值漏掉。改用 **4 s 窗口 + 连续 lag 搜索**后，84.0 vs 10.0 在 lag≈74.5 s 处仍有 ~87.5% 重叠，峰值可 >0.9，进入第三级选点。UI 的每一行都显示 `anchorA | anchorB | bestLag | nccPeak | overlapRatio | 最终 A/B`，就是这个原因。

### 10.4 多峰与选点
NCC 可能有多个 ≥0.95×峰值的峰（周期性/重复段落），全部进入第三级，每个峰独立选接缝点，输出多个候选（表格"多峰数"）。第三级在重叠区间内滑动 50 ms 窗，比较零交叉、能量差、频谱通量、样点跳变和 200 ms 内容 NCC；再 5 ms→1 ms 细化。

### 10.5 压缩只影响第一级召回，不影响最终接缝质量
PCA/随机投影只作用于 log-mel 粗筛向量；第二级 8k 波形 NCC 和第三级接缝选点始终用原始波形。因此压缩可能改变"哪些对进入第二级"，但一旦进入，最终接缝质量不受压缩维度影响。UI 报告方差保留率、距离失真、降维前后 top-20 重合率，供判断第一级是否还可靠。

**为什么第一级不直接比原始波形**：C 个锚点两两做 1 s × 44.1 kHz 的互相关，代价约 C²/2 × 44100 次乘加（4 分钟、480 锚点 ≈ 5.7×10¹² 次），完全不可接受；log-mel 把单次比较降到 1984 维，且每个锚点的 patch 可以预先算好一次，粗筛只做余弦。

### 10.6 压缩实测（60 s 合成音乐，锚点 225）
- PCA 白化：k=112（样本数 ≤512 且 k≤S/2 时的上限），方差保留 **97.3%**；白化后 L2 归一化，降维前后 top-20 重合率 **10%**（该素材没有真实整秒重复，排名本身不稳定）。
- 随机投影 256：距离失真均值 **3.7%**、最大 13.9%；top-20 重合率 **20%**。
- 在"2 s 片段复制"的合成用例（105 锚点）中，PCA 与 RP-256 的 top-20 重合率都是 **100%**，RP 距离失真均值 5.5%、最大 8.3%，说明有真实重复时第一级是稳定的。

> 注意：PCA 的拟合样本上限为 512 个锚点、主成分数 ≤ S/2；短文件的维度可能低于 256。这样报告的方差保留率才有意义。

---

## 11. 如何加一种新算法

1. 在 `public/analysis/` 新建 `algX.js`，导出一个纯函数，输入尽量复用 pipeline 已算好的 `features/beats/pre.mono`，返回候选数组（至少含 `aSample/bSample/score` 或等价指标）和 `timings/stats`。
2. 在 `pipeline.js` 中调用它（参考 D/E 分支），把结果放进 `algorithms`；如需接缝指标，复用 `createSeamEvaluator(pre.mono, sampleRate)` 和 `evaluate(A,B)`。
3. 在 `app.js` 的 `ALGO` / `ALGO_KEYS` / `CHECKBOX` 中加颜色与 id；在 `index.html` 加勾选框；`collectParams()` 读取参数。
4. 在 `defaultParams()` 加默认参数；在 UI 参数区加输入；表格按通用列展示，特殊指标写进 `detailHtml`。
5. 在 `selftest.ts` 加一条断言；在导出 JSON 的 `algorithms` 里会自动带上 `telemetry/candidates`。
6. 若算法需要新的帧级特征，优先加到 `features.js` 的 pass B（一次 STFT 内顺带算），不要重复 STFT。

分析必须留在 Worker 内（`worker.js` 只做转发）；不要在 UI 线程写重循环。

---

## 12. 导出 JSON

点击"导出对比 JSON"会下载 `<文件名>-loop-analysis.json`，包含：

- `schema / generatedAt / toolVersion / file / audio / params`
- `meta`：时长、采样率、T、BPM、tuning、beatsRaw/beatsUsed、各阶段耗时、警告
- `algorithms.{baseline,seam,subbeat,lsh,whole}`：完整候选数组 + telemetry（耗时、帧/beat、配对/召回/压缩质量等）
- D：`recall`（全量/topN/top1 + `sets.bruteTop / sets.lshTop`）与 `comparison.bruteOnly`
- E：`eDebug`（前 10 个被重叠规则丢弃的对、前 10 个低峰值）、`matches`（anchor/lag/peak/overlap）、`compression`、`stages` 三段耗时
- 每条候选的 A/B 采样点与 ms、长度、score、chromaDist、loudnessDiff、seamQuality、xcorrLag/peak，以及 D/E 的扩展字段

---

## 13. 已知限制

- 浏览器只能解码浏览器支持的格式（mp3/flac/ogg/wav/m4a 通常都支持；具体取决于 Chromium 构建）。
- 立体声吸附按 mono 执行（见第 6 节）；如要与 app 的立体声结果逐采样对齐，需要在 Worker 里保留交错双声道并改 `nearestZeroCrossing` 的调用。
- E 的 8 kHz NCC 在超长文件上仍是主要开销；可用"验证对数上限 / 预算"控制，或关闭 E。
- 波形标记默认只画每档 top 3（可勾选"显示全部"）；topN 很大时画面会较密。
- 本工具只做离线对比，不修改 app，也不写入任何用户数据；所有音频只在本地内存中处理。

---

## 14. 自检输出（本机最后一次运行）

```
=== 1) baseline on synthetic 20 s music ===
PASS  baseline produces candidates  [5 candidates]
      top1: A=151.6ms B=8139.3ms len=7.988s score=0.9826 seam=0.645
PASS  20 s analysis under 3 s  [267 ms]
PASS  deterministic loop points across runs
=== 2) JS baseline vs C++ loop-engine-cli ===
PASS  loop points identical to C++  [max |dA|=0 |dB|=0]
PASS  score matches C++ tolerantly  [max |dscore|=4.53e-7]
PASS  chroma distance matches  [max |dnd|=7.49e-8]
PASS  loudness difference matches  [max |dld|=4.46e-8]
=== 3) algorithm D recall metadata ===
PASS  recall block present
      bruteAll=178 lshAll=7462 recallAll=100.0% topN membership=100.0% top1same=false
=== 4) algorithm E: 2 s snippet copied at 20.5 s (0.5 s offset) ===
PASS  E produces candidates  [8 candidates]
PASS  found anchorA=0.5, anchorB=21.0 with peak>0.9  [peak=1.0000 lag=0.0ms]
PASS  equivalent 20.5 s offset matches exist  [58]
PASS  overlap guard dropped self-overlapping pairs  [2759]
      stages: feature=17ms compress=22ms coarse=2ms verify=301ms select=34ms; total=928ms
PASS  compression quality report present
ALL CHECKS PASSED
```

（数字随机器/音频略有波动；C++ CLI 不存在时会自动 SKIP 第 2 组。）
