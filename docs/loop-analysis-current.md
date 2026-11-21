# 循环分析算法现状(逐阶段复核)

> **快照说明**:本文描述 v8(接缝质量 + sub-beat 精修)合入**之前**的引擎行为,
> 作为改进前基线保留。改进设计、量化结果与复现方式见
> `docs/loop-analysis-improvements.md`。
> **行号口径**:下文 file:line 基于 v8 合入前的基线(commit `367fefb` 的
> `loop_engine.cpp`);v8 在 `loop_engine.cpp:1132` 之后插入接缝阶段,
> 因此本文件后半部分的当前行号会整体下移,引用时请以该 commit 为准。

> 复核对象:`app/src/main/cpp/loop_engine/loop_engine.cpp`(1441 行)、
> `loop_engine.h`,`loop_engine_internal.h`,`app/src/main/cpp/loop_jni.cpp`。
> 本文所有参数都直接标注 **file:line**,不写“大概/应该是”。
> 引擎是 PyMusicLooper 3.6.0 的 C++ 移植(`loop_engine.cpp:1-10`),
> 保留了两处关键“librosa 默认 22050 Hz”语义,见 §2.3。

---

## 0. 入口与调用链

| 层 | 位置 | 行为 |
|---|---|---|
| JNI | `loop_jni.cpp:149-157` / `:160-169` | `analyzePcm` / `analyzePcmWithProgress` 两个符号 |
| JNI 共享实现 | `loop_jni.cpp:50-143` | 参数空/非法直接返回空数组(`:53-59`);按 score 降序平铺 5 元组(`:109-123`) |
| 引擎公开入口 | `loop_engine.cpp:1435-1439` | `analyze()` 直接转调 `analyzeWithDebug(…, nullptr, …)` |
| 唯一实现 | `loop_engine.cpp:1137-1432` | `analyzeWithDebug`;整体 `try/catch` 兜底,异常返回空候选(`:1426-1431`) |
| 契约 | `loop_engine.h:35-37` | 输入 **interleaved float PCM**(引擎内部混单声道);永不抛异常 |
| 输出结构 | `loop_engine.h:12-18` | `loopStart`(含)、`loopEnd`(不含)、`score`(降序)、`noteDistance`,`loudnessDifference` |

失败即空候选的统一出口:`loop_jni.cpp:52,142`、`loop_engine.cpp:1145-1147,1179-1180,1224,1233,1235,1303,1323,1326,1334`。
进度回调节流 ≤ 20 Hz(`loop_engine.cpp:48-76`),回调返回 false 即取消并返回空候选。

---

## 1. 预处理

### 1.1 混单声道 + 归一化(`loop_engine.cpp:1149-1184`)
- 输入:interleaved PCM,每帧对 `channels` 求算术平均(`:1157-1172`),写入 `mono`(**double**)。
- 全零(静音)直接返回空(`:1179-1180`)。
- 除以全局 `maxAbs` 归一化到 [-1,1](`:1181-1183`)。
- 复杂度 O(frames × channels)。

### 1.2 首尾静音裁剪 top_db=40(`loop_engine.cpp:1186-1229`)
- RMS 帧数 `nRmsFrames = nTotal/512 + 1`(`:1188`),帧长 2048 / hop 512,居中补零(`:1194-1207`)。
- 参考能量 = 所有 RMS 帧的**最大值**(跨 shard 精确 max,`:1190-1211`),`refDb = 20·log10(max(1e-5, sqrt(maxMse)))`(`:1212`)。
- 首/尾第一个高于 `refDb - 40 dB` 的帧作为裁剪边界(`:1213-1223`);`trimStart = first·512`,`trimEnd = min(n, (last+1)·512)`。
- 之后所有分析都在 `y = mono[trimStart, trimEnd)` 上做(`:1227`);输出时再映射回原始 sample(`:1380-1391`)。
- 复杂度 O(frames × 2048)。

---

## 2. 频谱特征

### 2.1 STFT(n_fft=2048,hop=512)(`loop_engine.cpp:231-309`)
- `kNfft=2048`、`kHop=512`、`kBins=1025`(`:32-34`)。
- 帧数 `T = floor(M/512)+1`(居中,两端补零,`:234,256-260`);周期 Hann(`:241`)。
- pass 1:功率谱 `|STFT|²`;同时求全局最大 dB 作为 `dbFloor = gmax - 80`(`:269,276`,top_db=80)。
- pass 2:每 bin 加 A 计权 dB、下限截断(`:281-291`,`aWeightingDb` 在 `:205-220`,`min_db=-80`),再累加 mel。
- mel:`kMels=128`、`fmax=8000 Hz`(`:35-36`),Slaney 归一化,基矩阵用 float32 舍入(`:168-202`)。
- 并行:按帧分片,归约只做精确 max(`:78-134,246-306`)。
- 复杂度 O(T·N·logN),N=2048;内存 `sPower+sWeighed = 2·T·1025·4 B`(5 分钟歌 T≈25.8k → ≈212 MB),mel 128·T·8 B(≈26 MB)。

### 2.2 调性估计 tuning(`loop_engine.cpp:313-431`)
- 在 **22050 Hz 语义**下限定 150 Hz ≤ f < 4000 Hz(`:317-320`)。`kBeatSr=22050` 定义在 `:42-46`,注释明确这是“librosa 默认 sr 的怪癖”,不能按真实采样率改。
- 逐帧取 `|S|²` 的局部极大 + 抛物线插值(`:335-365`),候选幅度中位数作阈值(`:374-377`)。
- 音高残差直方图 100 桶、步长 0.01 半音(`:380-426`),返回 `-0.5 + 0.01·best` 半音(`:427-430`)。
- 复杂度 O(T·1025) 两遍 + 排序 O(n log n)。

### 2.3 chroma(12 维)(`loop_engine.cpp:434-517`)
- 由 tuning 构造 12×2048 高斯核、按 fft bin 做 L2 归一化、按八度高斯加权、`base_c` 循环移位 3 格(`:434-482`)。
- **仍用 22050 Hz 建滤波器**(`:488-490`),与真实 sr 无关。
- 每帧 `|·|∞` 归一化后转 float32 存回 double(`:505-512`)。
- 复杂度 O(T·12·1025)。

### 2.4 响度代理 CMAX dB(`loop_engine.cpp:520-558`)
- 取 A 计权 dB 谱的**每帧最大值**,减去全局中位数 dB 参考,再按 `gmax-dbRef-80` 截底(`:535-557`)。
- 这是“每帧一个 dB 数”,不是短时 LUFS。复杂度 O(T·1025)。

---

## 3. 节拍网格

### 3.1 onset envelope(`loop_engine.cpp:564-578`)
- `env[j+3] = mean_m max(0, mel[m][j+1] - mel[m][j])`(`:567-577`),即 3 帧延迟补偿(lag=1 + center)。
- 复杂度 O(T·128)。

### 3.2 PLP 脉冲(`loop_engine.cpp:597-721`)
- 窗长 `W=384`、hop=1(`:599-601`),只用 30–300 bpm 的 tempo bin(`:621-626`)。
- 逐帧取 tempo 峰、归一化(`:627-652`),再按 librosa `istft` 的**分块 overlap-add 语义**重建(`:653-698`),`window_sumsquare` 除权(`:699-713`),clip 到 ≥0 并归一化(`:714-720`)。
- 输出同长度 T。

### 3.3 tempo 估计(`loop_engine.cpp:731-815`)
- 自相关 tempogram,win=8 s@22050 → 344 帧(`:734`),FFT 长度 ≥ 2·win-1(`:749-750`)。
- bpm 轴 `60·22050/(512·lag)`(`:798-801`),<320 bpm 才参与(`:802-805`),对数正态先验中心 120 bpm(`:809-811`)。
- 返回 bpm;无有效值则 0。

### 3.4 beat_track DP(Ellis 2007)(`loop_engine.cpp:818-959`)
- onset 先除以标准差(ddof=1)归一化(`:826-835`)。
- `fpb = round((22050/512)·60/bpm)`(`:840-841`)。
- localscore:宽度 `2·fpb+1` 的高斯卷积(`:844-860`)。
- 动态规划:上界 `i - round(fpb/2)`,下界 `i - 2·fpb`,惩罚 `tightness=100`(`:867-890`)。
- 末拍取 cumscore 局部极大且 ≥ 0.5×中位数(`:893-911`),回溯得拍点(`:912-919`),再按 localscore 的 hanning(5) 平滑 RMS 的一半裁掉首尾弱拍(`:920-955`)。

### 3.5 并集拍网格(`loop_engine.cpp:1282-1313`)
- `beats = sort(unique(beat_track 拍点 ∪ PLP 局部极大))`(`:1293-1303`);这是**唯一允许的循环点位置集合**。
- 复杂度:PLP O(T·384·log384 + T·384);DP O(T·fpb)。

---

## 4. 候选配对与剪枝

### 4.1 配对(`loop_engine.cpp:969-1016`)
- 循环长度用“帧”表示:每一对 `(tStart, tEnd)` 必须 `minLoop ≤ tEnd-tStart ≤ maxLoop`(`:996-998`)。
- `minLoop = time_to_frames(0.35 × 总时长)`,`maxLoop = time_to_frames(总时长)`(`:1315-1318`;注意 0.35 是 **int(0.35·duration)** 秒)。
- 和声门槛:`dev = 0.0875 × |chroma(tEnd)|₂`(`:981`),要求 `‖chroma(tEnd)-chroma(tStart)‖₂ ≤ dev`(`:999-1005`)。即 **0.0875 = ACCEPTABLE_NOTE_DEVIATION** 是相对 end 帧范数的比例,不是绝对常数。
- 响度门槛:`|CMAXdB(tEnd) - CMAXdB(tStart)| ≤ 0.5`(`:1006-1007`,ACCEPTABLE_LOUDNESS_DIFFERENCE)。
- 实现细节:外层按 end beat 并行,内层 start beat 一旦 `len < minLoop` 就 break(拍点升序,`:997`);`len > maxLoop` 则 continue(`:998`)。
- 复杂度 O(B²·12),B=拍点数;实际只枚举长度窗口内的组合。

### 4.2 剪枝(`loop_engine.cpp:1018-1056`)
- **仅当候选数 ≥ 100 才剪**(`:1019`)。
- 对 `ld > 1e-3` 的值取 50 分位作 `dbThr`(≤3 个则取最大,`:1027-1042`);对 `nd > 1e-3` 的值取 75 分位作 `ndThr`(`:1043-1049`)。
- 保留 `ld ≤ max(0.25, dbThr)` 且 `nd ≤ ndThr`(`:1050-1055`)。
- 复杂度 O(P log P)。

---

## 5. 打分、排序与输出

### 5.1 子序列相似度(`loop_engine.cpp:1060-1092`)
- 前向:以 start beat 与 end beat 为起点,各取 `testOffset` 帧,逐帧 chroma 余弦相似度,再用**对数递减权重** `w` 加权平均(`:1336-1345`:从 `max(2, testOffset/12)` 指数递减到 1)。
- 反向:同样窗口但用反向权重 `wrev`(`:1358,1372`)。
- `testOffset = time_to_frames(12 拍时长) = floor(secondsToTest·sr/512)`,`secondsToTest = 12/(bpm/60)`(`:1330-1333`);若 `testOffset > T` 则取 `T/4`(`:1333`),<1 直接失败(`:1334`)。
- 打分 `score = max(fwd, bwd)`(`:1373`),范围约 [0,1]。

### 5.2 排序(`loop_engine.cpp:1366-1378`)
- 所有候选并行打分(`:1368-1375`),再 `stable_sort` 按 score 降序(`:1377-1378`)。
- **topN 只由 score 决定**,没有接缝质量、没有音色、没有多样性约束。

### 5.3 过零点吸附(`loop_engine.cpp:1094-1130,1380-1401`)
- 搜索窗 `windowSize = max(1, sr/100)`(10 ms),`offset = windowSize/2`(`:1097-1100`)。
- 代价:`|x| + (与前样本同号?0.4)(前样本>0?0.1) + 0.1·|i-offset|/(windowSize/2)`(`:1106-1117`)。
- 取最小代价点;若最小代价 > 0.2(单声道)或 > 0.6·channels(多声道)则**放弃吸附、原样返回**(`:1127-1129`)。
- 注意:因此“最近上升过零点”实际是“代价函数最小点”,上升沿只加 0.1 的偏好,不是硬约束。
- 输出前的坐标映射 `sf = (sf·512 + trimStart)/512`(`:1386-1389`),即**先落回原始帧网格再乘 512**;refined 子帧位置若存在也会被这一步取整(见改进文档)。

### 5.4 JNI 输出(`loop_jni.cpp:109-123`)
- 平铺 `[loopStart, loopEnd, score, noteDistance, loudnessDifference]`,长度 5×N,score 降序。
- Kotlin 侧按 5 元组解析(`LoopCandidates.kt:7-29`),多/少字段会整体拒绝。

---

## 6. 复杂度与耗时结构(单线程口径)

设 T 为 STFT 帧数,N=2048,H=512,B 为拍点数,P 为通过门槛的配对数:
- STFT:O(T·N log N + T·1025·(1+mel 覆盖))——实际主导项。
- 调性/chroma:O(T·1025·(1+12))。
- 配对:O(B²·12)。
- 打分:O(P·testOffset·12),testOffset≈62k/bpm 帧(约 500 帧@120bpm,6s)。
- 排序:O(P log P);吸附:O(N_cand·sr/100)。
- 并行:按帧/按候选分片,8 线程上限(`:86-98`),所有归约是精确 max/int 和,结果与串行逐位一致(`:78-85`)。

对一首 5 分钟 44.1 kHz 歌曲:T≈25.8k,STFT 主导;chroma 约 3.2 亿次乘加;若 P≈5k、testOffset≈500,打分约 3 千万次乘加。整体在手机上通常数秒级,内存峰值主要来自 `sPower/sWeighed`(§2.1)。

---

## 7. 已知局限(每条都能在代码里指到)

1. **没有任何“接缝质量”项**。候选筛选只看 chroma 欧氏距离(`:1005`)和单帧 CMAX dB 差(`:1007`);打分只对齐 chroma 序列(`:1060-1092`),从不看波形。因此“B→A 拼接处音量跳变/相位跳变/瞬态被切断/混响尾巴被截”完全不在目标函数里。
2. **循环点被拍网格锁死**。候选位置只能是并集拍点(`:1293-1303`),吸附只在 ±5 ms 内挪动(`:1097-1100`),没有拍内(sub-beat)精修。
3. **过零点 ≠ 相位连续**。吸附目标是最小代价点(`:1106-1117`),失败还会直接放弃(`:1127-1129`);即使吸到过零,也只保证端点幅度接近 0,不保证两侧波形斜率/相位一致。
4. **没有音色维度**。特征只有 12 维 chroma(`:434-517`)和一个标量 CMAX dB(`:520-558`);鼓组/配器变化测不到。
5. **响度口径粗**。门槛是拍点所在**单帧**的 CMAX dB 差(`:1006-1007`),不是接缝两侧 20–50 ms 的短时 RMS/LUFS。
6. **候选不保证多样性**。排序后直接取前 `maxCandidates` 个(`:1382-1401`),没有任何最小间距/去重,topN 可能挤在同一段。
7. **重复段落的“第二次出现”未被利用**。配对只在 beat 网格上比 chroma;若两处和声相同但波形不同(不同副歌、不同混音),旧排序无法区分。

---

## 8. 与 issue 00026 描述的差异(复核结论)

- “chroma 欧氏距离 ≤ 0.0875×|chroma|” ✔,但 `|chroma|` 取的是 **end 帧**范数(`:981`)。
- “|CMAX dB 差| ≤ 0.5” ✔(`:1007`)。
- “任意两个 beat” ✔,但实现按 end beat 外层、start 内层且有长度窗口 break/continue(`:988-1012`)。
- “prune ≥100 对” ✔(`:1019`);阈值是 50/75 分位而非固定常数(`:1036-1049`)。
- “按 score 排序取 topN” ✔(`:1377-1401`)。
- “吸附到最近的上升过零点” —— **不完全准确**:代价函数最小点,上升沿只是 0.1 的软偏好,且可能放弃吸附(`:1106-1129`)。
- 另外两个容易被忽略的点:`trimStart` 映射会先把帧号落回整数帧(`:1386-1389`);beat/chroma 的 bpm 与滤波器语义固定为 22050 Hz(`:42-46`),改真实采样率会直接改变结果。
