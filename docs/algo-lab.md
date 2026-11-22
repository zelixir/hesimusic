# 循环算法实验 app(AlgoLab)使用说明

> 对应 issue 00030 / 任务 B。本文说明**独立的算法实验 app**如何构建、怎么调参、
> 怎么做「初版 vs 新版(接缝质量融合)」对比、怎么判读准确度/性能,以及结果导出格式。
>
> **边界(重要)**:
> - 本 app 是独立 flavor `algolab`,包名 `com.zjr.hesimusic.algolab`,与正式包共存;
> - **正式版(prod)默认仍是初版算法**(任务 A 保证),本 app 不改变任何生产默认值/排名逻辑;
> - Lab 代码只存在于 `app/src/algolab`,prod/demo/v2 源集不包含 Lab 类与入口;
> - 没有新增第三方依赖。
>
> **当前状态**:代码与文档已写完,**尚未构建/安装/实测**(按任务约定交给统一构建)。
> 文末「实测记录」表留空,并给出填法。

---

## 1. 构建与安装

Windows(PowerShell):

~~~powershell
# 只构建实验包(推荐,最快)
.\gradlew.bat :app:assembleAlgolabDebug

# 安装(可与正式版同时安装)
adb install -r app\build\outputs\apk\algolab\debug\app-algolab-debug.apk
~~~

macOS / Linux:

~~~bash
./gradlew :app:assembleAlgolabDebug
adb install -r app/build/outputs/apk/algolab/debug/app-algolab-debug.apk
~~~

说明:

- `assembleDebug` / `assembleRelease` 仍会构建**全部** flavor(prod/demo/algolab/v2),
  只想做实验时用 `:app:assembleAlgolabDebug`。
- algolab 的 `applicationIdSuffix = ".algolab"`、`versionNameSuffix = "-algolab"`,
  应用名 `海瑟音乐 AlgoLab`,所以可以和正式包同时安装,数据库/文件目录互不影响。
- 启动器只有一个(AlgoLab);正式包 `MainActivity` 在 algolab 清单里被**整体移除**
  (源码仍在 main 源集,但本包不可启动)。Lab 自带曲库扫描(复用
  `ScanRepository`/`FileScanner`,写入 algolab 自己的数据库),不依赖 MainActivity。
- 原生库、引擎参数与生产共用同一份 `hesimusic_jni`;Lab 参数**经 JNI 直传**,
  不读环境变量(Android app 内 `setenv` 不可靠)。

构建入口判定:`BuildConfig.ALGO_LAB` 只在 algolab 变体为 `true`(prod/demo/v2 均为
`false`)。`AlgoLabActivity` 里还有一道运行时检查:`if (!BuildConfig.ALGO_LAB) finish()`。

### 1.1 本次交付的验证状态(未构建 APK)

按任务约定**没有构建/安装正式版**,只做了 algolab 变体与不触碰产物的检查:

| 检查 | 命令/方式 | 结果 |
|---|---|---|
| 清单合并 | `:app:processAlgolabDebugMainManifest` | PASS;合并清单只剩 `AlgoLabActivity` 一个 LAUNCHER,`MainActivity` 已整体移除 |
| Kotlin 编译(含 KSP/Hilt/Compose) | `:app:compileAlgolabDebugKotlin` | BUILD SUCCESSFUL |
| 引擎/JNI 语法 | NDK r27 clang `-fsyntax-only`(arm64, C++17) | PASS |
| 引擎 lab 输出(host) | 临时 C++ 测试:12 s 合成音乐,调用 `analyzeWithDebug(..., LabParams, LabInfo)` | PASS:92 个已评分拍对、24 个候选池、接缝阶段 21.7 ms;双排名/融合公式/legacy-only/自定义权重/18 字段平铺均通过 |
| Kotlin 纯逻辑 | 临时 JVM 测试:平铺解析 + 汇总 + w 扫描 + 参考偏差 | PASS |

> 真正的 app 构建、安装与真机实测请在统一构建时进行(见 §7 的填法)。

---

## 2. 界面与参数

界面按三块组织:**选歌 + 参数** → **双算法对比表** → **汇总指标 + 导出**。

### 2.1 选歌

- **从曲库选择**:读 algolab 自己的 Room 曲库(独立包名 = 独立数据库,不会自动继承
  正式版曲库)。首次使用点「扫描曲库」,在 Lab 的扫描对话框里授予「所有文件访问」,
  填扫描目录(默认 `/storage/emulated/0/Music`,可改),开始扫描;扫描结果只写入
  algolab 自己的 songs 表。
- **输入路径**:任意音频文件绝对路径。同样需要「所有文件访问」
  (MANAGE_EXTERNAL_STORAGE),否则解码会失败;扫描对话框里有「去授权 / 重新检查权限」。
- CUE 分轨不参与实验(列表已过滤 `isCue`),因为实验直接解码整个文件。

### 2.2 参数

| UI 参数 | 引擎字段 | 默认 | 建议扫描范围 | 含义 |
|---|---|---|---|---|
| 接缝融合(开/关) | `seamEnabled` | 开 | 开 / 关 | 开 = 一次分析同时测接缝 + 双排名;关 = 只跑初版路径(性能基线) |
| 融合权重 w | `seamWeight` | 0.50 | 0.0 → 1.0,9 个点(步长 0.125) | `score=(1-w)·legacy + w·seamQuality`;w=0 即初版,w=1 只看接缝 |
| corr 权重 | `corrWeight` | 0.50 | 0 / 0.25 / 0.5 / 0.75 / 1.0 | 波形连续性(50 ms head 互相关峰值) |
| RMS 权重 | `rmsWeight` | 0.30 | 0 / 0.15 / 0.30 / 0.50 / 0.75 | 短时能量跳变 `exp(-rmsJumpDb/3)` |
| flux 权重 | `fluxWeight` | 0.20 | 0 / 0.10 / 0.20 / 0.35 / 0.50 | 频谱通量突变 `1/(1+max(0,fluxRatio-1))` |
| 亚拍 refine | `refineEnabled` | 开 | 开 / 关 | 拍点附近 ±range 的局部搜索,采用前用完整接缝分复核(+0.02 门槛) |
| refine 范围 | `refineRangeMs` | 30 ms | 0 / 10 / 20 / 30 / 50 ms | 搜索半径;0 = 不搜索 |
| 候选数量 N | `maxCandidates` | 10 | 5 / 10 / 20 / 30 | 同时决定接缝候选池规模;表格高亮与汇总口径 |
| 排序 | UI only | 按新版名次 | 新版 / 初版 | 只影响表格顺序 |
| 参考 A / B | UI only | 空 | 已知真值(ms) | 填了才计算与参考的偏差;两端都填才生效 |

三点说明:

1. **corr / RMS / flux 会自动归一化**(除以三者之和);UI 显示原始值与归一化后的
   百分比。全填 0 时引擎回退到 0.50/0.30/0.20。归一化在 native 侧再次防御性执行。
2. 三项子权重只影响**最终接缝质量与融合分**;refine 的局部搜索代价函数仍用固定的
   0.55/0.30/0.15 代理(与生产一致),所以调子权重不会改变精修搜索路径。
3. 参数修改后必须重新点「开始分析」才会生效;分析中控件禁用。

---

## 3. 一次分析如何同时得到「初版」与「新版」

这是本 app 的核心约束:**同一次 decoder 输出 + 同一次引擎调用**,绝不为了对比再解码/再分析。

引擎(`loop_engine.cpp`)在原有管线里:

1. 用全部已剪枝拍对算 `legacyScore`——即初版(纯和弦序列相似度)分数;
   排序得到 `legacyRank`(0-based,全局);
2. 选接缝候选池(legacy 前 `max(32,3N)`,其中核心 `max(16,N+6)` + 最多 8 个
   cheap 预筛外卡),对池内候选测 `corr / rmsJumpDb / fluxRatio`,合成
   `seamQuality = wc·corrScore + wr·energyScore + wf·timbreScore`;
3. `fusedScore = (1-w)·legacyScore + w·seamQuality`,排序得到 `fusedRank`;
4. 一并输出:每个候选的两套分数/名次、接缝分项、两套边界
   (`loopStart/End` = 融合输出,含精修;`anchorStart/End` = 初版输出,不含精修)、
   耗时与候选池规模。

路径参数通过 JNI 直传(`LabParams`/`LabInfo`),生产入口 `analyzePcm` **不传**
`LabParams`,因此生产默认值与排名逻辑完全不受影响。

**seamWeight 扫描**不需要 9 次分析:UI 用同一份 `legacyScore + 三个分项`在本地重算
`score(w)` 再排序,得到「前 N 重合率 vs w」曲线。这不是第二次分析,结果与引擎在相同 w
下应一致(表格里的「融合分/名次」取自引擎,w=当前值)。

`docs/loop-analysis-improvements.md` §1 有接缝度量/精修/融合的算法细节与历史实测。

---

## 4. 怎么做「初版 vs 新版」对比

### 4.1 单次双算法对比(主路径)

1. 选同一首歌,设置 **N=10**、refine 开、范围 30ms、三项权重 0.50/0.30/0.20、
   融合 w=0.50;
2. 保持「接缝融合」**开**,点「开始分析」;
3. 页面直接给出两套排名与全部候选(不需要也不应该再跑一遍解码);
4. 看汇总卡:前 5/前 10 重合、Δ分、名次变化、新增/消失、接缝均值;
5. 看曲线:w=0.0 → 1.0 的 9 个点,曲线越早下降说明融合对初版改动越大。

### 4.2 参数矩阵(建议至少跑 3 首歌)

| 轮次 | 融合开关 | w | corr/RMS/flux | refine | N | 目的 |
|---|---|---|---|---|---|---|
| A | 开 | 0.50 | 0.50/0.30/0.20 | 开 30ms | 10 | 新版默认 |
| B | 关 | — | — | 开 30ms | 10 | 初版基线(同时是性能基线) |
| C | 开 | 0.50 | 1.00/0.00/0.00 | 开 30ms | 10 | 只看波形相关 |
| D | 开 | 0.50 | 0.20/0.60/0.20 | 开 30ms | 10 | 更看重能量跳变 |
| E | 开 | 0.50 | 0.30/0.20/0.50 | 开 30ms | 10 | 更看重频谱通量 |
| F | 开 | 0.50 | 0.50/0.30/0.20 | 关 | 10 | 隔离 refine 贡献 |
| G | 开 | 0.50 | 0.50/0.30/0.20 | 开 30ms | 5 / 20 | 看 N 敏感性 |
| H | 开 | 0.0 / 0.25 / 0.5 / 0.75 / 1.0 | 默认 | 开 30ms | 10 | 曲线 + 关键点复核 |

**注意**:A 与 B 会各解码一次(这是为了量**性能**差);A 本身已经包含两套算法,
做**准确度/排名对比**时不要用 B 的候选去替代 A 里的 legacy 列——B 只用于性能 A/B
与「初版基线是否可复现」的交叉验证。

### 4.3 只看「同歌、同 N、仅改融合开关」

这是任务要求的最小对照:

1. 同歌、N=10、refine 30ms、权重默认;
2. 接缝融合 **开** 跑一次 → 记录新版 top N 与 legacy 列;
3. 接缝融合 **关** 跑一次 → 记录初版 top N;
4. 断言:
   - 关闭时的 top N **位置/分数**应等于生产初版算法的输出(同引擎、同 PCM);
   - 开启时 legacy 列的前 N 与关闭时的前 N 应逐位一致(同一套 legacy 评分);
   - 两者的**分析总耗时(JNI)**比值即新版耗时增幅(不要用端到端耗时,解码重复且波动大)。

---

## 5. 指标定义与判读

### 5.1 准确度/排名指标(单次分析内)

| 指标 | 定义 | 怎么看 |
|---|---|---|
| 前 N 重合 | `L_N 与 F_N 的交集大小 / N`;候选身份 = 同一拍对(用 legacyRank 作唯一键) | N=5 时 ≥4/5 是「基本不扰动初版」的经验线 |
| Δ分 | `fusedScore - legacyScore` | 正 = 新版更喜欢;负 = 用和声相似度换接缝 |
| 名次变化 | `fusedRank - legacyRank` | 负数 = 上升;外卡候选的 legacyRank 是全局排名,可能与池内名次跨度很大 |
| 新增/消失 | 融合前 N 中不在初版前 N 的候选数 / 反之 | 只统计前 N;全池差异看表格与导出 |
| top1 更换 | 两套 top1 是否同一拍对 | 换了必须试听(尤其已确认的循环点) |
| 接缝均值 | 前 N 的 seamQuality 均值(初版前 N vs 融合前 N) | 融合后应明显更高,否则没有收益 |
| 参考偏差 | 若填了参考 A/B:分别对初版 anchor 坐标、融合 loop 坐标找**最近候选**(两端偏差欧氏距离),给 ΔA/ΔB/距离/±50/±100ms 计数 | 有真值时的硬指标;没有真值只能试听 |

### 5.2 性能指标

- **解码耗时**:PcmDecoder 整轨解码(不算分析);
- **分析总耗时(JNI)**:引擎全流程(含接缝);
- **接缝阶段耗时**:引擎内部测的接缝阶段墙钟(测量 + 精修 + 融合排序);seam 关时为 0;
- **每候选接缝耗时** = 接缝阶段耗时 / 实际评估候选数(并行阶段的均摊值,不是单候选墙钟);
- **估算分析耗时增幅** = `seamStageMs / (analyzeMs - seamStageMs)`。单次分析内的近似;
  精确值用 §4.3 的开/关两次「分析总耗时」比值。
- **内存峰值**:分析回调里 ≤10Hz 采样 Java heap / native heap / PSS 取最大。是近似口径,
  不是系统级峰值,跨设备比较时只比同设备同歌的相对值。

### 5.3 接受 / 拒绝新版的判据(写死到 UI 的「客观判据」)

| 判据 | 通过线 | 理由 |
|---|---|---|
| 前 5 重合 | ≥ 4/5 | 新版不应把初版明显合理的 top5 全部推翻 |
| 接缝均值 | 融合前 N > 初版前 N | 新版必须真的换到接缝更好的候选 |
| 分析耗时增幅 | ≤ 20% | 任务门禁(历史 v8 真机最坏 +7.0%,桌面最坏 +12.4%) |
| 最大初版分让步 | ≥ -0.15(历史 v8 最差 -0.123) | 不允许用大幅降低和声相似度换取微小接缝收益 |

附加人工判据:

- **已确认的循环点不能动**(如 demo 的白夜洇润 1741→80364 ms)。若 top1 变化且该曲已有
  确认结果,**必须试听**;不接受「指标变好但听感变差」。
- 前 N 新增/上升的候选,要逐个听 B→A 接缝;接缝质量高但和声/节奏不搭的候选不能接受。
- 不同歌曲要分别判定;用 6 首歌的中位数/最差值,而不是单曲最好值。

UI 的初步判定:4 项全过 =「建议接受(仍需 A/B 试听)」;2–3 项 =「需试听复核」;
<2 项 =「建议拒绝」;接缝关闭时显示「无法判定」。

---

## 6. 导出与电脑端比对

### 6.1 导出位置

优先写应用外部私有目录(无需存储权限,USB/adb 可直接拉取):

~~~
/sdcard/Android/data/com.zjr.hesimusic.algolab/files/algolab/
~~~

外部目录不可用时回退 `/data/data/com.zjr.hesimusic.algolab/files/algolab`(UI 会显示实际路径)。
每次导出生成 3 个文件:

~~~
algolab-YYYYMMDD-HHmmss-<歌曲名>.json           # 完整快照
algolab-YYYYMMDD-HHmmss-<歌曲名>-candidates.csv # 每候选一行(便于 pandas/Excel)
algolab-YYYYMMDD-HHmmss-<歌曲名>-sweep.csv      # w 0..1 的 9 个点
~~~

拉取:

~~~bash
adb pull /sdcard/Android/data/com.zjr.hesimusic.algolab/files/algolab ./algolab-export
~~~

### 6.2 JSON 结构(`schema = hesi-algo-lab/1`)

- `song`:title/artist/album/path/durationMs/sampleRate/frames
- `params`:UI 原始值 + 归一化后的三项权重 + refineRangeMs/maxCandidates
- `engine`:seamEnabled/seamEvaluatedCount/scoredPairs/evaluatedPool/seamStageMs
- `timing`:decodeMs/analyzeMs/seamStageMs/perCandidateMs/estimatedOverheadPercent/totalMs
- `memory`:sampled/peakJavaBytes/peakNativeBytes/peakPssKb
- `summary`:重合数/比例、top1Changed、newCount/missingCount、平均 Δ分、最大名次变化、
  接缝均值、最差初版分让步、两套 top ranks
- `sweep`:9 个 `{seamWeight, overlapAt5, overlapAt10, overlapAtN, top1Same, avgSeamQualityTopN}`
- `reference`:参考 A/B 与初版/融合的最近候选偏差(未填参考时为 `null`)
- `candidates`:每候选的
  `legacyRank/fusedRank/rankShift/startMs/endMs/anchorStartMs/anchorEndMs/
  loopStartSamples/loopEndSamples/anchorStartSamples/anchorEndSamples/
  legacyScore/fusedScore/deltaScore/seamQuality/seamEvaluated/
  seamCorrelation/seamRmsJumpDb/seamFluxRatio/corrScore/energyScore/timbreScore/
  noteDistance/loudnessDifference`

### 6.3 CSV 列

`candidates.csv`:

~~~
legacy_rank,fused_rank,rank_shift,start_ms,end_ms,anchor_start_ms,anchor_end_ms,
loop_start_samples,loop_end_samples,legacy_score,fused_score,delta_score,
seam_quality,seam_evaluated,corr_score,energy_score,timbre_score,
seam_correlation,seam_rms_jump_db,seam_flux_ratio,note_distance,loudness_difference
~~~

`sweep.csv`:

~~~
seam_weight,overlap_at_5,overlap_at_10,overlap_at_n,top1_same,avg_seam_quality_top_n
~~~

### 6.4 电脑端画图示例

~~~python
import pandas as pd
import matplotlib.pyplot as plt

sweep = pd.read_csv("algolab-...-sweep.csv")
plt.plot(sweep["seam_weight"], sweep["overlap_at_5"], marker="o", label="top5 overlap")
plt.plot(sweep["seam_weight"], sweep["overlap_at_10"], marker="s", label="top10 overlap")
plt.xlabel("seamWeight w")
plt.ylabel("overlap with legacy ranking")
plt.ylim(0, 1)
plt.legend()
plt.savefig("sweep.png", dpi=160)

c = pd.read_csv("algolab-...-candidates.csv")
plt.scatter(c["legacy_score"], c["seam_quality"], c=c["rank_shift"], cmap="coolwarm")
plt.colorbar(label="rank shift (fused - legacy)")
plt.xlabel("legacy score")
plt.ylabel("seam quality")
plt.savefig("candidates.png", dpi=160)
~~~

---

## 7. 实测记录(待构建后填写)

> **当前为空**:本次只交付代码 + 文档,未构建、未真机实测。下面给出填法;
> 历史参考数据摘自 `docs/loop-analysis-improvements.md`(v8 桌面评测,不是本 app 的实测),
> 仅用于对照,不要直接抄进「本次实测」。

### 7.1 历史参考(v8,仅对照)

| 歌曲 | 时长 s | 旧 top1 A→B (ms) | 新 top1 A→B (ms) | 旧 seam | 新 seam | 旧 legacy | 新 legacy | 桌面耗时增幅 |
|---|---|---|---|---|---|---|---|---|
| unfurling_night | 94.1 | 1741→80364 | 1741→80364 | 0.727 | 0.727 | 0.839 | 0.839 | +5.9% |
| test_song2 | 60.0 | 38267→59269 | 38267→59269 | 0.732 | 0.732 | 0.722 | 0.722 | +12.4% |
| shiny_up | 284.8 | 23301→261458 | 43142→258682 | 0.735 | 0.862 | 0.976 | 0.901 | +6.2% |
| katatsumuri | 225.0 | 92753→173547 | 83521→164136 | 0.564 | 0.791 | 0.874 | 0.751 | +1.3% |
| guren | 316.9 | 96862→269225 | 147006→261761 | 0.193 | 0.477 | 0.773 | 0.728 | -0.2% |
| nanairo | 271.7 | 82756→231399 | 82756→231399 | 0.506 | 0.506 | 0.880 | 0.880 | +4.3% |

### 7.2 本次 App 实测模板(留空,按下列字段填)

设备、系统、构建号、是否 8 核/性能模式要记录,因为耗时是相对值。

| 日期 | 设备/系统 | 歌曲 | 时长 s | N | w | corr/RMS/flux | refine | 初版 top1 A→B (ms) | 新版 top1 A→B (ms) | 重合@5 | 重合@10 | 平均 Δ分 | 最大名次变化 | 新增/消失 | top1 seam 初→新 | 分析耗时 初/新 (ms) | 增幅 % | 结论 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
|  |  |  |  | 10 | 0.50 | 0.50/0.30/0.20 | 开 30ms |  |  |  |  |  |  |  |  |  |  |  |
|  |  |  |  | 10 | 0.50 | 0.50/0.30/0.20 | 开 30ms |  |  |  |  |  |  |  |  |  |  |  |
|  |  |  |  | 10 | 0.50 | 0.50/0.30/0.20 | 开 30ms |  |  |  |  |  |  |  |  |  |  |  |

**填法**(每首歌跑 §4.1 的 A 一次 + 若需要性能比值再跑 B 一次):

- 初版/新版 top1 A→B:表格里按「按新版名次」排序,看「新# = 1」那一行的 A/B(融合坐标);
  初版坐标用导出 JSON 的 `anchorStartMs/anchorEndMs`(或关闭接缝跑 B 看 top1);
- 重合@5 / @10:汇总卡直接读;
- 平均 Δ分 / 最大名次变化 / 新增/消失:汇总卡直接读;
- top1 seam 初→新:汇总卡「top1 接缝:初版 → 融合」;
- 分析耗时初/新:初 = 关闭接缝跑 B 的「分析总耗时(JNI)」,新 = 开启跑 A 的同一字段
  (两次都只比较 JNI,不含解码);
- 增幅 % = `(新-初)/初 × 100`;并与 UI 的「估算分析耗时增幅」交叉核对;
- 结论:按 §5.3 四项 + 试听填写「接受 / 复核 / 拒绝」。

### 7.3 扫描结果模板(可选)

| 歌曲 | w | 重合@5 | 重合@10 | top1 是否同 | 融合 top-N 接缝均值 | 备注 |
|---|---|---|---|---|---|---|
|  | 0.000 | 100% | 100% | 是 |  | 必为初版 |
|  | 0.250 |  |  |  |  |  |
|  | 0.500 |  |  |  |  | 默认 |
|  | 0.750 |  |  |  |  |  |
|  | 1.000 |  |  |  |  | 只看接缝 |

---

## 8. 故障排查

| 现象 | 原因/处理 |
|---|---|
| `:app:assembleAlgolabDebug` 找不到任务 | 确认当前分支包含 `app/build.gradle.kts` 的 algolab flavor;先 `./gradlew :app:tasks --all` 查变体名 |
| 安装后只有一个图标且是正式版 | 装错 APK;algolab 的包名必须是 `com.zjr.hesimusic.algolab`(用 `adb shell pm list packages` 过滤 algolab 确认) |
| 曲库为空 | algolab 是独立数据库;点「扫描曲库」→ 授予「所有文件访问」→ 填目录扫描一次,或直接输路径 |
| 输入路径后解码失败 | 未授予「所有文件访问」;到系统设置给 algolab 打开该权限,或改用曲库歌曲 |
| 分析一直 0% / 无响应 | 查看 `adb logcat -s LoopJni PcmDecoder LoopPerf`;取消后重试;注意长歌解码本身较慢 |
| 提示「曲目过长」 | 分析解码上限 900 s(与生产一致);更长的曲子需要先裁剪 |
| 出现两个启动器图标 | 检查 algolab 合并清单里 `MainActivity` 是否已被整体移除、只剩 `AlgoLabActivity` 一个 LAUNCHER(`app/build/intermediates/merged_manifest/algolabDebug/processAlgolabDebugMainManifest/AndroidManifest.xml`) |
| 扫描无法开始 / 目录不可读 | 扫描对话框里点「去授权」授予「所有文件访问」,回来点「重新检查权限」;确认目录存在且不是 CUE 文件 |
| 接缝分项全是「—」 | 「接缝融合」被关闭了;打开后重跑 |
| 导出的目录在文件管理器看不到 | Android 11+ 的应用外部私有目录;用 `adb pull`(见 §6.1),或在 UI 里复制路径 |
| 与正式版结果不同 | 正常:algolab 的 Lab 参数默认开接缝;正式版默认初版。对比时以「关闭接缝」为初版基线 |

---

## 9. 代码位置与改动范围

| 文件 | 作用 |
|---|---|
| `app/build.gradle.kts` | 新增 `algolab` flavor(.algolab 后缀、ALGO_LAB=true、label) |
| `app/src/algolab/AndroidManifest.xml` | 只保留 LabActivity 启动器;整体移除 MainActivity(Lab 自带扫描) |
| `app/src/algolab/java/.../LabEngineJni.kt` | JNI 参数直传入口 `analyzeLab` |
| `app/src/algolab/java/.../LabModels.kt` | 参数/候选模型、JNI 平铺解析、对比/扫描/准确度纯逻辑 |
| `app/src/algolab/java/.../AlgoLabViewModel.kt` | 单次解码 + 单次分析、曲库扫描(ScanRepository)、状态机、内存采样、导出 |
| `app/src/algolab/java/.../AlgoLabScreen.kt` | 选歌/参数/汇总/导出 UI |
| `app/src/algolab/java/.../LabFormat.kt` | UI 共用数字格式化与状态色 |
| `app/src/algolab/java/.../LabTable.kt` | 双算法对比表 + seamWeight 曲线 |
| `app/src/algolab/java/.../AlgoLabActivity.kt` | 入口 Activity + ALGO_LAB 守卫 |
| `app/src/main/cpp/loop_engine/loop_engine_internal.h` | 追加 `LabParams/LabCandidate/LabInfo`;analyzeWithDebug 追加两个默认参数 |
| `app/src/main/cpp/loop_engine/loop_engine.cpp` | 接缝分项拆分、权重/半径参数化、lab 输出;生产默认路径不变 |
| `app/src/main/cpp/loop_engine/lab_flat.h` | Lab JNI 平铺布局(6 header + 18×N,版本 1) |
| `app/src/main/cpp/loop_jni.cpp` | 新增 `analyzeLab` 符号;生产符号不变 |

**没有**修改生产默认值、默认排名、UI 入口、播放链或数据库 schema。
