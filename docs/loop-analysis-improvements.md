# 循环分析精度改进 v8:接缝质量 + sub-beat 精修

> 对应 issue 00026 的第二步/第三步。改动前基线(逐阶段、file:line)见
> `docs/loop-analysis-current.md`。本文只写**改了什么、怎么量化、结果如何**。
>
> **结论先行**:接缝质量参与排序 + 拍内精修后,6 首歌里 3 首 top1 发生变化,
> 平均接缝质量 +0.106;两首提升最大(guren +0.284、カタツムリ +0.227,
> 接缝 RMS 跳变分别 4.71→0.24 dB、1.73→0.01 dB)。demo 已确认的
> 白夜洇润循环点(1741→80364 ms)保持不动。top1 的和声序列分最多下降
> 0.123。耗时最坏 +12.4%(桌面 60 s 短歌)/ +7.0%(真机 60 s 短歌),
> 长歌 ≤6.2%/≤4.1%,满足 ≤+20%。

---

## 1. 改动概览

| 编号 | 改动 | 代码位置 | 默认 |
|---|---|---|---|
| A | 接缝质量 seamQuality(0..1) | `loop_engine.cpp:1132-1318`(度量) | 开 |
| B | 拍内 ±30 ms 精修 + 非退化护栏 | `loop_engine.cpp:1320-1395`、`:1715-1745` | 开 |
| C | 融合排序 `score=(1-w)·legacy + w·seamQuality` | `loop_engine.cpp:1654-1755` | w=0.50 |
| D | 候选池:legacy 前 32 + cheap 预筛(16 核心 + 8 外卡) | `loop_engine.cpp:1660-1701` | 开 |
| E | 宿主评测 CLI(WAV + 独立接缝量测 + 计时) | `tests/loop_engine_eval.cpp` | 仅 host |
| F | 并行/确定性测试覆盖 seam 字段 | `tests/loop_engine_parallel_test.cpp:243-260` | 仅 host |

JNI 契约未变:仍返回 5 元组 `(start, end, score, noteDistance, loudnessDifference)`
(`loop_jni.cpp:109-123`);`score` 从“chroma 序列相似度”变为“融合分(0..1)”。
Kotlin/UI 零改动。仅 host 可用的开关:
`LOOP_ENGINE_SEAM=0`(逐位复现旧排序)、`LOOP_ENGINE_SEAM_WEIGHT=0..1`、
`LOOP_ENGINE_SEAM_REFINE=0`(只测 A/C)。

### 1.1 A:接缝质量

对候选 `(A, B)`(原始 sample 坐标,已过零吸附),在裁剪后的归一化 mono 上:

1. **相位/波形连续性** `corr`:把 `(B, B+50ms)` 与 `(A, A+50ms)` 做归一化互相关,
   lag ∈ ±10 ms 取峰值(粗扫步长 2 sample,峰值 ±2 内细扫)。复杂度 O(W·L)。
2. **短时能量跳变**:`rmsJumpDb = |20·log10(RMS(A,A+50ms) / RMS(B-50ms,B))|`。
3. **频谱通量突变**:23 ms 帧(44.1k→1024 点,22.05k→512 点)的归一化幅度谱余弦距离;
   接缝处一帧距离 ÷ 两侧各 3 个相邻帧距离均值。
4. 合成(`loop_engine.cpp:1298-1318`):
   `
   corrScore   = clamp((corr - 0.25) / 0.75, 0, 1)
   energyScore = exp(-rmsJumpDb / 3)
   timbreScore = 1 / (1 + max(0, fluxRatio - 1))
   seamQuality = 0.50·corrScore + 0.30·energyScore + 0.20·timbreScore
   `

### 1.2 为什么是 head-head(而不是 tail-tail 或任务书的 tail-head)

播放到接缝时是 `… y[B-2], y[B-1], y[A], y[A+1] …`。
“跳过去之后”真正播放的是 `(A, A+50ms)`;如果循环是真正的重复,
它应当等于“不跳、继续播”的 `(B, B+50ms)`。
因此 **head-head 的互相关高 = 接缝在听感上等同于“歌继续播”**,同时
最佳 lag 就是残余相位差。tail-tail(两段切点之前的 50 ms)也能用,但
它包含的是上一拍的尾音/混响,演奏细节差异更大;任务书建议的 tail-head 是
“一句的结尾 vs 另一句的开头”,不是同一个乐句位置,不能作为接缝判据。

实测(6 首歌旧 top1,同一 50 ms / ±10 ms 参数):

| 定义 | 中位数 | 说明 |
|---|---|---|
| head-head `(B..B+W)`, `(A..A+W)` | **0.706** | 本实现采用 |
| tail-tail `…B-W..B`, …A-W..A` | 0.550 | 也可用,但更受前一拍演奏/混响影响 |
| tail-head `(B-W..B)`, `(A..A+W)` | 0.453 | 任务书建议;句尾对句首,不是接缝判据 |

### 1.3 B:sub-beat 精修

- 起点:beat 帧 → 原始 sample → **先做一次过零吸附**。
- 搜索:坐标下降 B/A;粗阶段 ±30 ms、步长 5 ms、2 轮;细阶段 ±5 ms、
  步长 1 ms、1 轮(`loop_engine.cpp:1362-1395`)。
- 便宜目标(一次 ~10^3 乘加):10 ms **head** 互相关(0.55)+ 短时能量(0.30)+
  边界阶跃/斜率平滑(0.15),`loop_engine.cpp:1320-1360`。
- **非退化护栏**:精修后重新过零吸附,用完整 seamQuality 同时评估
  “beat 原点”和“精修点”;只有精修点高出 **0.02**(2% 量程)才采用
  (`loop_engine.cpp:1725-1744`)。因此:
  ① 精修在数学上不会让某候选的完整 seam 变差;
  ② 不会为了噪声级差异挪动切点(实测避免过 test_song2 的
  “RMS 变好、频谱通量反而 1.0→7.65”的交换)。
- 长度约束:始终满足 `minLoop ≤ B-A ≤ maxLoop`(样本口径,
  `loop_engine.cpp:1576-1581`)。

### 1.4 C:融合排序与候选池

- 先按 legacy 分取前 `max(32, 3·maxCandidates)`(生产 `maxCandidates=10` → 32)
  (`:1660-1661`)。
- **cheap 预筛**:legacy 前 `max(16, maxCandidates+6)`(N=10 → 16)全部做完整评估;
  其余 16 个里按 cheap 代理前 8 名做“外卡”(`:1673-1701`)。这样既把
  “legacy 排名 32 但接缝极好”的候选(shiny_up 的 43142→258682)带进来,
  又把完整 xcorr 次数控制在 ≤24。
- 最终 `score = 0.5·legacyScore + 0.5·seamQuality`(`:1753`),stable_sort 后取 topN。
- 权重扫描见 §3.4。

---

## 2. 量化方法(可复现)

### 2.1 音频集(6 首,44.1 kHz mono float)

| 标签 | 来源 | 时长 | 说明 |
|---|---|---|---|
| unfurling_night | `tools/loop-engine-cli/test_audio.wav` | 94.1 s | **白夜洇润**:demo 资产 `app/src/demo/assets/demo/loop.json` 记录 A=1741/B=80364 ms,与基线 top1 完全一致 |
| test_song2 | `tools/loop-engine-cli/test_song2.wav` | 60.0 s | 现有测试音频 |
| shiny_up | 手机 `/sdcard/Music/车万/Shiny Up！.mp3` | 284.8 s | adb pull → ffmpeg |
| katatsumuri | 手机 `/sdcard/Music/MP3-03. カタツムリ.mp3` | 225.0 s | 同上 |
| guren | 手机 `/sdcard/Music/(01)紅蓮の弓矢.mp3` | 316.9 s | 同上 |
| nanairo | 手机 `/sdcard/Music/01 - Nanairo Symphony.mp3` | 271.7 s | 同上 |

手机上找不到名为“白夜洇润/Unfurling”的文件;demo loop.json 的 A/B 与
`test_audio.wav` 完全对上,故用它代表该曲。转码:
`ffmpeg -y -i <src> -ac 1 -c:a pcm_f32le <out>.wav`。

### 2.2 独立量测

`tests/loop_engine_eval.cpp` **不读引擎内部 seam 字段**,而是自己从 PCM 重算
`tailCorr / headCorr / rmsJumpDb / fluxRatio / stepNorm / seamScore`(公式同 §1.1),
用同一把尺子量“旧 top1”和“新 top1”。引擎内部值另经 `DebugInfo::pairs`
输出(`loop_engine_internal.h:11-31`),仅用于交叉核对。

### 2.3 基线可复现性

- 改动前构建了基线二进制并留档。
- 新二进制在 `LOOP_ENGINE_SEAM=0` 下,6 首歌的候选**位置、score、
  noteDistance、loudnessDifference 与基线逐位相同**(6/6 PASS)。
- host 回归测试在 seam 开启下,1/2/3/4/8/16 线程的候选与全部 seam 诊断字段
  逐位一致(ALL PASS)。

---

## 3. 结果(默认 w=0.50)

### 3.1 top1 对比

| 歌曲 | 时长 s | 旧 A→B (ms) | 新 A→B (ms) | ΔA/ΔB (ms) | 旧 seam | 新 seam | Δseam | 旧 legacy | 新 legacy | Δlegacy | 是否换候选 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| unfurling_night | 94.1 | 1741→80364 | 1741→80364 | 0/0 | 0.727 | 0.727 | 0.000 | 0.839 | 0.839 | 0.000 | 否(demo 已确认) |
| test_song2 | 60.0 | 38267→59269 | 38267→59269 | 0/0 | 0.732 | 0.732 | 0.000 | 0.722 | 0.722 | 0.000 | 否 |
| shiny_up | 284.8 | 23301→261458 | 43142→258682 | +19840.9/-2775.4 | 0.735 | 0.862 | **+0.127** | 0.976 | 0.901 | -0.075 | 是 |
| katatsumuri | 225.0 | 92753→173547 | 83521→164136 | -9231.9/-9411.0 | 0.564 | 0.791 | **+0.227** | 0.874 | 0.751 | -0.123 | 是 |
| guren | 316.9 | 96862→269225 | 147006→261761 | +50143.6/-7463.6 | 0.193 | 0.477 | **+0.284** | 0.773 | 0.728 | -0.045 | 是 |
| nanairo | 271.7 | 82756→231399 | 82756→231399 | 0/0 | 0.506 | 0.506 | 0.000 | 0.880 | 0.880 | 0.000 | 否 |

平均 Δseam = **+0.106**;最差 Δlegacy = **-0.123**;3/6 换 top1,另 3 首
(含 demo 已确认的白夜洇润)不动。

### 3.2 接缝分项(top1,独立量测)

| 歌曲 | headCorr | tailCorr | rmsJump dB | fluxRatio | stepNorm |
|---|---|---|---|---|---|
| unfurling_night | 0.789(不变) | 0.677 | 1.74 | 0.74 | 0.082 |
| test_song2 | 0.900(不变) | 0.656 | 3.31 | 1.00 | 0.617 |
| shiny_up | 0.666→0.871 | 0.533→0.806 | 0.46→0.57 | 0.79→0.71 | 0.389→0.048 |
| katatsumuri | 0.745→0.789 | 0.568→0.744 | 1.73→**0.01** | 3.04→1.51 | 0.106→0.259 |
| guren | 0.215→0.219 | 0.247→0.356 | 4.71→**0.24** | 1.54→0.86 | 0.162→0.092 |
| nanairo | 0.467(不变) | 0.528 | 0.31 | 2.20 | 0.376 |

两处最大的客观改善:
- **guren**:接缝 RMS 跳变 4.71→0.24 dB、通量 1.54→0.86;旧 top1 是
  96.9→269.2 s,新 top1 是 147.0→261.8 s(换了段落,legacy 只降 0.045);
- **カタツムリ**:RMS 1.73→0.01 dB、tailCorr 0.568→0.744;
  旧 top1 92.8→173.5 s,新 top1 83.5→164.1 s(legacy 降 0.123)。
- shiny_up 的改善主要在相位/阶跃(headCorr 0.666→0.871,stepNorm 0.389→0.048),
  RMS 略升 0.11 dB,合成仍 +0.127。

### 3.3 top5 平均接缝质量

| 歌曲 | 旧 top5 | 新 top5 | Δ |
|---|---|---|---|
| unfurling_night | 0.769 | 0.769 | 0.000 |
| test_song2 | 0.554 | 0.671 | +0.118 |
| shiny_up | 0.586 | 0.757 | +0.171 |
| katatsumuri | 0.534 | 0.570 | +0.036 |
| guren | 0.307 | 0.313 | +0.006 |
| nanairo | 0.498 | 0.561 | +0.063 |

6/6 不下降(5 首上升、1 首持平),说明不是单点运气;guren/katatsumuri 这类“整首只有
3–4 个合格候选”的曲子,提升集中在 top1。

### 3.4 权重扫描

| w | 换 top1 | 平均 Δseam | 最差 Δlegacy | 备注 |
|---|---|---|---|---|
| 0.2 | 2/6 | +0.054 | -0.045 | 只换 shiny/guren |
| 0.3 | 2/6 | +0.054 | -0.045 | 同 0.2 |
| 0.4 | 3/6 | +0.092 | -0.123 | 加入 katatsumuri |
| **0.5** | **3/6** | **+0.106** | **-0.123** | 加入 shiny 的 43142→258682(seam 0.735→0.862) |
| 0.6 | 5/6 | +0.126 | -0.123 | 开始改掉 demo 确认的白夜洇润(0.727→0.77)、nanairo 也换 |

0.50 是拐点:相对 0.4 多出的唯一替换是 shiny_up(seam +0.127 换 legacy -0.075,
比例 1.7:1,与 katatsumuri 的 1.8:1 同量级);到 0.6 会覆盖 demo 已确认的
循环点,收益不抵风险。

### 3.5 A 与 B 各自贡献(隔离实验)

`LOOP_ENGINE_SEAM_REFINE=0`(只保留 A/C 排序):

| 歌曲 | top1 seam 仅排序 | +精修 | Δ | top5 仅排序 | top5 +精修 | Δtop5 |
|---|---|---|---|---|---|---|
| unfurling_night | 0.727 | 0.727 | 0.000 | 0.769 | 0.769 | 0.000 |
| test_song2 | 0.732 | 0.732 | 0.000 | 0.660 | 0.671 | +0.011 |
| shiny_up | 0.862 | 0.862 | 0.000 | 0.740 | 0.757 | +0.018 |
| katatsumuri | 0.713 | 0.791 | **+0.077** | 0.534 | 0.570 | +0.036 |
| guren | 0.477 | 0.477 | 0.000 | 0.307 | 0.313 | +0.006 |
| nanairo | 0.506 | 0.506 | 0.000 | 0.561 | 0.561 | 0.000 |

解读:
- **A(接缝排序)是主要收益来源**:guren、shiny_up 的 top1 换人完全由排序完成;
- **B(精修)是“临门一脚”**:katatsumuri 的 top1 在仅排序时还是 0.713,
  精修把 B 挪 19 ms(+0.077)才定案;其余 5 首 top1 不动,但 top5 均值有
  0~+0.018 的提升。2% 门槛让 test_song2/nanairo 的“0.017 微调”被拒绝,
  避免用 RMS 换频谱通量的可疑改动。

### 3.6 旧 top10 里更高的接缝候选为何没被选中

| 歌曲 | 旧 top1 seam | 旧 top10 最好 seam | 该候选 legacy | w=0.5 是否选中 |
|---|---|---|---|---|
| unfurling_night | 0.727 | 0.861(rank4) | 0.638 | 否(需 w≈0.60) |
| test_song2 | 0.732 | 0.732(本身) | 0.722 | — |
| shiny_up | 0.735 | 0.776(rank6) | 0.969 | 否;最终选了 top10 之外的 rank32(0.862/legacy 0.901) |
| katatsumuri | 0.564 | 0.713(rank2) | 0.751 | 是(精修后 0.791) |
| guren | 0.193 | 0.477(rank3) | 0.728 | 是 |
| nanairo | 0.506 | 0.577 | 0.780 | 否(合成 0.679 < 0.693) |

说明这是“精度 vs 和声相似度”的显式取舍:0.5 权重只接受性价比高的替换。

---

## 4. 耗时(<= +20% 门禁)

### 4.1 桌面(MSVC 19.44 -O2,8 线程,7 次中位数)

| 歌曲 | 旧 ms | 新 ms | 增幅 |
|---|---|---|---|
| unfurling_night | 202.9 | 214.8 | +5.9% |
| test_song2 | 148.5 | 167.0 | **+12.4%** |
| shiny_up | 576.6 | 612.3 | +6.2% |
| katatsumuri | 435.3 | 441.0 | +1.3% |
| guren | 680.8 | 679.2 | -0.2%(噪声内) |
| nanairo | 545.2 | 568.4 | +4.3% |

### 4.2 真机(用户手机 b1e84dad,arm64,NDK r27 交叉编译同一 CLI,3 次)

| 歌曲 | 旧中位 ms | 新中位 ms | 中位增幅 | 旧最小 ms | 新最小 ms | 最小增幅 |
|---|---|---|---|---|---|---|
| unfurling_night | 2064.2 | 2149.1 | +4.1% | 2057.8 | 2110.2 | +2.5% |
| test_song2 | 1478.8 | 1580.0 | +6.8% | 1444.4 | 1545.4 | **+7.0%** |
| shiny_up | 6055.0 | 6288.4 | +3.9% | 6020.1 | 6197.9 | +3.0% |
| katatsumuri | 4683.0 | 4686.5 | +0.1% | 4666.1 | 4672.6 | +0.1% |
| guren | 6875.4 | 6892.0 | +0.2% | 6794.9 | 6866.7 | +1.1% |
| nanairo | 5782.0 | 5868.0 | +1.5% | 5712.6 | 5852.1 | +2.4% |

注意:真机测量时手机在并发负载下,绝对耗时比空载(如 shiny_up 3.8 s)高,
**比值才是门禁口径**。最坏 +7.0%,长歌 ≤4.1%;桌面最坏 +12.4%。均 <20%。
真机没有安装 app:host CLI 推到 `/data/local/tmp` 直接跑,验证后已删除。

加速手段:
- 完整评估的候选 **≤24 个**(32 池 → 16 核心 + 8 cheap 外卡);
- lag 粗扫步长 2 + 峰值 ±2 细扫(≈2×);
- 接缝阶段按候选并行,每个 shard 写自己的结构体;
- 精修用 10 ms 短窗 cheap 代理,完整量测只在原点和精修点各一次。

---

## 5. 反例、没变的、风险

- **unfurling_night(白夜洇润)不动**:demo loop.json 已确认 1741→80364。
  旧 top10 里确有 seam 0.861、A 在 22.2 s 的候选,但它的 legacy 只有 0.638;
  0.5 权重下合成 0.75 < 0.783,不替换。这是本次最重要的“不误伤”证据。
- **test_song2/nanairo 不动**:两者旧 top1 本身就是各自 pool 里接缝最高的
  候选(0.732/0.506);精修提议的 +0.017 被 2% 门槛拒绝。
- **shiny_up 的 RMS 略升**(0.46→0.57 dB),但相位/阶跃大幅改善
  (headCorr 0.666→0.871,stepNorm 0.389→0.048),合成 +0.127。
- **katatsumuri 的 stepNorm 0.106→0.259 变差**:能量/相位收益更大
  (RMS 1.73→0.01 dB),这是三项折中的正常现象。
- **“score” 语义变化且 UI 可见**:`LoopScreen.kt:817` 直接显示 “score…”;
  同一个候选旧版可能 0.880、新版显示融合分。若 UI 要分别展示
  “和声相似度 / 接缝质量”,需把 seamQuality 作为第 6 字段透出并改 Kotlin/UI,
  超出本次写域,未做。
- **权重是在 6 首歌上选的**,存在过拟合风险;未做听感测试。建议真机抽查
  top1 换人的 3 首(guren / カタツムリ / Shiny Up)。
- **接缝窗只有 50 ms**,更长的混响尾巴(>50 ms)测不到;频谱通量用固定
  1024/512 点窗,低频时间分辨率有限。

---

## 6. 复现步骤

### 6.1 构建 host 评测器

`
$cmake = "$env:LOCALAPPDATA/Android/Sdk/cmake/3.22.1/bin/cmake.exe"
& $cmake -S app/src/main/cpp/loop_engine/tests -B build/loop-engine-tests -G "Visual Studio 17 2022" -A x64
& $cmake --build build/loop-engine-tests --config Release --target loop_engine_eval
`

### 6.2 旧/新对比

`
$exe = 'build/loop-engine-tests/Release/loop_engine_eval.exe'
# 旧排序(逐位等于 v8 之前)
$env:LOOP_ENGINE_SEAM='0'; & $exe build/loop-eval/audio/guren.wav 10 guren
# 新排序(默认 w=0.50)
Remove-Item Env:LOOP_ENGINE_SEAM; & $exe build/loop-eval/audio/guren.wav 10 guren
# 只测排序 / 扫权重
$env:LOOP_ENGINE_SEAM_REFINE='0'; & $exe build/loop-eval/audio/guren.wav 10 guren
$env:LOOP_ENGINE_SEAM_REFINE='1'; $env:LOOP_ENGINE_SEAM_WEIGHT='0.4'; & $exe build/loop-eval/audio/guren.wav 10 guren
`

JSON 每行一首歌,含 `analysisMs`、每个候选的 `tailCorr/headCorr/rmsJumpDb/
fluxRatio/stepNorm/seamScore` 与 `debugPairs`(引擎内部值,交叉核对)。

### 6.3 回归测试与原生构建

`
& $cmake --build build/loop-engine-tests --config Release --target loop_engine_parallel_test
& build/loop-engine-tests/Release/loop_engine_parallel_test.exe   # 期望 ALL PASS

.\gradlew.bat :app:externalNativeBuildDemoDebug :app:externalNativeBuildProdDebug --offline
`
(本工程有 demo/prod 两个 flavor,裸写 `:app:externalNativeBuildDebug` 会报
ambiguous;两个 flavor 均 BUILD SUCCESSFUL。)

---

## 7. 改动文件(本次)

| 文件 | 变化 |
|---|---|
| `app/src/main/cpp/loop_engine/loop_engine.cpp` | 接缝度量 + 精修 + 融合排序 + cheap 预筛 + env 开关;输出阶段也响应取消 |
| `app/src/main/cpp/loop_engine/loop_engine_internal.h` | `PairInfo` 增加 legacyScore/seamQuality/seamCorrelation/seamRmsJumpDb/seamFluxRatio/seamEvaluated/refinedStartSample/refinedEndSample |
| `app/src/main/cpp/loop_engine/tests/loop_engine_eval.cpp` | 新增 host 评测 CLI(独立双相关 + 计时) |
| `app/src/main/cpp/loop_engine/tests/CMakeLists.txt` | 新增 `loop_engine_eval` target |
| `app/src/main/cpp/loop_engine/tests/loop_engine_parallel_test.cpp` | seam 字段跨线程逐位一致性 |
| `docs/loop-analysis-current.md` | 改动前基线(逐阶段,file:line) |
| `docs/loop-analysis-improvements.md` | 本文 |

不涉及 UI / 播放 / 环带代码。

---

## 8. 后续可做(本次未做)

1. seamQuality / corr / rmsJumpDb 作为第 6+ 字段透出 JNI,UI 分开显示;
2. 音色维度(24 带 log-mel/MFCC)替代 23 ms 谱通量;
3. 接缝短时 LUFS 与 >50 ms 混响尾巴检测;
4. 候选多样性(最小间距/去重);
5. 更大规模(≥20 首)重标定 w 与 0.5/0.3/0.2 子权重;
6. 评测器输出 A/B 拼接 wav,供人工试听。
