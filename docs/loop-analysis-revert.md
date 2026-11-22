# 循环分析算法回退说明(生产默认 = 初版纯分数排名)

> **结论**:从本提交起,生产版 `loop_engine` 的候选排序恢复为初版(1be82fa 移植的
> PyMusicLooper 3.6.0 流水线)语义——**只按纯和弦序列相似度(legacyScore)降序**。
> v8 接缝质量(seam quality)+ 拍内精修(sub-beat refine)+ 融合排序全部**代码保留**,
> 但默认关闭;任务 B 的算法实验 app 通过显式开关启用。
>
> 改动只涉及"排名与分数选择",没有回退任何与算法无关的修复(见 §2)。

---

## 1. 生产默认行为

| 项 | 回退前(2026-11-21 之后) | 回退后(本提交) |
|---|---|---|
| 接缝阶段 | 默认运行 | **默认不运行**(未设置 `LOOP_ENGINE_SEAM` = 关闭) |
| 融合权重 | `0.50` | **`0.0`**(未设置 `LOOP_ENGINE_SEAM_WEIGHT`) |
| 排序键 | `0.5*legacy + 0.5*seam` | **`legacyScore` 纯和弦相似度** |
| `LoopCandidate::score` | 融合分 | **== `legacyScore`** |
| `LoopCandidate::seamQuality` | 实测值 | `0.0`("未测量"哨兵,UI 不显示接缝) |
| 候选 loopStart/loopEnd | 可能被 ±30 ms 拍内精修挪动 | **初版坐标**:拍点 + 过零点吸附,无精修 |
| JNI 7 字段契约 | 7 doubles | **不变**(仍 7 doubles,前 5 个位置/语义未动) |

实现上只有两处默认值变化(`app/src/main/cpp/loop_engine/loop_engine.cpp`):

- `seamEnabled()`:未设置 `LOOP_ENGINE_SEAM` 时返回 **false**(此前返回 true);
- `seamWeight()`:未设置 `LOOP_ENGINE_SEAM_WEIGHT` 时返回 **0.0**(此前 0.50)。

接缝/精修/融合的公式、候选池预筛、JNI、Kotlin 解析与展示代码一行未删。

### 任务 B(算法实验 app)如何启用

> **注(issue 00030 任务 B)**:实验 app 使用 **JNI 参数直传**的 `analyzeLab`
> (见 `docs/algo-lab.md`),不依赖这里的环境变量 —— 下列 env 开关保留给 host CLI
> 复现与离线评测;app 内的 `setenv` 不可靠,Lab 从不走这条路。

```text
LOOP_ENGINE_SEAM=1                 # 运行接缝阶段(测量 + 拍内精修)
LOOP_ENGINE_SEAM_WEIGHT=0.50       # 融合进排名;0.0 时只测量、不改变排名
LOOP_ENGINE_SEAM_REFINE=0          # 可选:只排序、不精修(原有 A/B 开关)
```

生产 `analyze()` / JNI 路径不设置任何环境变量,因此不受影响。

---

## 2. 哪些提交被保留,以及为什么

时间线上真正参与本次"算法 vs 修复"判断的提交:

| commit | 性质 | 处理 | 理由 |
|---|---|---|---|
| `1be82fa` | 初版移植(唯一基线) | **保留为生产默认** | 用户要求严格使用初版算法 |
| `dabf3cc` | **混合提交** | 修复保留;算法默认关闭 | 见 §2.1 |
| `f7314bb` | **混合提交** | 修复保留;算法默认关闭 | 见 §2.2(**任务书写的 f7314cc 在仓库里不存在,实际是 f7314bb**) |
| `d31b7f4` | 纯算法(接缝排序 + 精修 + 文档) | 默认关闭,代码保留 | 用户尚未确认新版效果;任务 B 需要 |
| `f9f93c2` | 展示:第 6 字段 seamQuality | **保留** | 不改排序,只是把已算出的接缝分透出;生产默认下为 0("未知") |
| `488b584` | 展示/命名:第 7 字段 legacyScore + "相似度"文案 | **保留** | 同上;UI 的"相似度"本就该显示 legacyScore |
| 其余无关修复(如 `4f67e9b`、`bfb907b`) | 非算法 | **完全不动** | 不在回退范围 |

### 2.1 dabf3cc 是混合提交

提交说明写的是 "treat a tap inside the loop handle band as a seek instead of
committing the window",但同一个提交在 `loop_engine.cpp` 里加入了整套 v8 接缝阶段
(tail 窗口相关、权重 0.30),同时改了 `WaveformGesture` / `PreviewSeekPolicy` /
`LoopPlayhead` / `PreviewPlayer` / `RingBandSource` 等交互实现。

- **只回退算法部分**:接缝阶段默认关闭(不删除);
- **完整保留修复部分**:handle 带内 tap → seek 的手势/预览策略一行未改,
  相关单测(`WaveformGestureTest`、`PreviewSeekPolicyTest`、`LoopPlayheadLoadingTest`、
  `RingBandPreviewBudgetTest`)继续通过。

### 2.2 f7314bb 也是混合提交

提交说明是 "throttle the chunked fallback so it cannot push the sample queue
without bound",但同一提交重做了接缝算法:tail 窗口 → head 窗口、权重 0.30 → 0.50、
新增 `LOOP_ENGINE_SEAM_REFINE` 与 2% 精修门槛。

- **保留限流修复**:`LoopingPcmExtractor.kt` 的 chunked fallback 限流及其
  `LoopingPcmExtractorThrottleTest` 未动;
- **接缝算法部分**随默认关闭而不参与生产排名(实现仍可经环境变量复现,
  见 §3.4 的 v8 复现结果)。

### 2.3 为什么不用 git revert

`dabf3cc` / `f7314bb` 都是"修复 + 算法"混在一起;直接 revert 会把
tap-seek 修复和 chunked fallback 限流一并回退,违反任务约束。任务 B 也需要
接缝代码与已测数据,因此选择**默认关闭开关**而不是删代码。

---

## 3. 验证

### 3.1 现有 host 单测(全绿)

```text
loop_engine_flat_test      ALL PASS
loop_engine_parallel_test  ALL PASS(含默认路径与 seam-on 路径的 bit-exact 校验)
loop_engine_ranking_test   ALL PASS(新增,见 §3.2)
```

构建方式(host,不构建 APK):

```powershell
$cmake = 'C:\Program Files\Microsoft Visual Studio\2022\Community\Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin\cmake.exe'
& $cmake -S app/src/main/cpp/loop_engine/tests -B build/loop-engine-revert -G "Visual Studio 17 2022" -A x64
& $cmake --build build/loop-engine-revert --config Release
```

> 说明:仓库原有的 `build/loop-engine-tests` 缓存是用 Android SDK 的
> CMake 3.22.1 配置的,又被 VS 自带的 3.31 重新生成过,混用会报
> "No preprocessor test for IBMClang";本次验证使用新目录 `build/loop-engine-revert`。

### 3.2 新增回归测试:排名 == legacyScore 排名

`app/src/main/cpp/loop_engine/tests/loop_engine_ranking_test.cpp`,同一份确定性
PCM 下:

1. 默认配置:每个候选 `score == legacyScore`、`seamQuality == 0`、排名按
   legacyScore 非增;debug pairs 的 `seamEvaluated == false`;
2. **默认排名 vs 接缝阶段开启但权重 0.0 的排名**:候选的
   `(startFrame, endFrame, legacyScore, score)` 序列**逐位相同**——接缝测量在跑,
   但排名完全不受影响;
3. 显式 `LOOP_ENGINE_SEAM=1 + LOOP_ENGINE_SEAM_WEIGHT=0.50` 时仍写出
   `score == 0.5*legacy + 0.5*seam`,证明任务 B 的融合代码完好。

### 3.3 三首(实际跑了六首)真实音频对比初版候选顺序

参考基准:`build/loop-eval/baseline.jsonl`,由 `loop_engine_eval_baseline.exe`
于 **2026-11-21 08:38:37** 生成——早于 `dabf3cc`(08:43)加入接缝阶段,即**初版引擎**的
原始输出。对比字段:`start / end / score / noteDistance / loudnessDifference`
(全部候选,不是只比 top1)。

| 歌曲 | 候选数 | 顺序 | 字段差异 | top1(ms) | 与 docs 记录旧值 |
|---|---|---|---|---|---|
| unfurling_night(白夜洇润) | 10 | 相同 | 0 | 1741.293 → 80364.127 | 一致(1741→80364) |
| test_song2 | 10 | 相同 | 0 | 38266.803 → 59269.433 | 一致(38267→59269) |
| shiny_up | 10 | 相同 | 0 | 23300.839 → 261457.574 | 一致(23301→261458) |
| katatsumuri | 3 | 相同 | 0 | 92752.676 → 173547.188 | 一致(92753→173547) |
| guren | 4 | 相同 | 0 | 96862.404 → 269224.717 | 一致(96862→269225) |
| nanairo | 10 | 相同 | 0 | 82756.054 → 231398.730 | 一致(82756→231399) |

**6/6 与初版逐位一致(候选顺序、坐标、score、nd、ld 全部相同)**,没有"浮点/顺序
稳定性"级别的差异需要解释。对照 `docs/loop-analysis-improvements.md` §3.1 记录的
"旧 A→B"与"旧 legacy",也逐项吻合。

三首重点曲目的 top3 顺序(默认配置,与初版完全相同):

```text
unfurling_night  1741.3→80364.1 (0.839377) | 2054.9→80562.6 (0.774575) | 22303.3→80562.6 (0.638734)
test_song2      38266.8→59269.4 (0.722036) | 18888.6→48276.1 (0.659443) | 18888.6→50270.1 (0.653710)
guren           96862.4→269224.7 (0.772556) | 20292.8→189183.7 (0.746831) | 147006.0→261761.1 (0.728002)
```

回退前 v8 会换掉其中 3 首的 top1(shiny_up / katatsumuri / guren);回退后它们全部
回到初版位置,这正是本任务要的结果。

### 3.4 接缝实验仍可复现(证明只是默认关闭,没有丢失实现)

`LOOP_ENGINE_SEAM=1 + LOOP_ENGINE_SEAM_WEIGHT=0.5` 时与 v8 参考记录
(`build/loop-eval/seam_mode.jsonl`)逐位相同:

| 歌曲 | 本构建 top1(ms) | v8 参考 | 一致 |
|---|---|---|---|
| guren | 147006.009 → 261761.134(score 0.602279) | 同 | 是 |
| katatsumuri | 83520.794 → 164136.145(score 0.771117) | 同 | 是 |
| shiny_up | 43141.746 → 258682.132(score 0.881865) | 同 | 是 |

### 3.5 JVM 单测

`gradlew.bat testDebugUnitTest`:**BUILD SUCCESSFUL**,1803 tests / 0 failures /
0 errors / 3 skipped(任务 B native 冻结后重跑;此前一轮为 1783 tests,同样 0 失败)。

---

## 4. 改动文件一览

| 文件 | 改动 |
|---|---|
| `app/src/main/cpp/loop_engine/loop_engine.cpp` | `seamEnabled()` 默认 false;`seamWeight()` 默认 0.0;相关注释 |
| `app/src/main/cpp/loop_engine/loop_engine.h` | 契约注释:生产默认 score == legacyScore |
| `app/src/main/cpp/loop_engine/candidate_flat.h` | 注释:seamQuality=0 哨兵含"生产默认" |
| `app/src/main/cpp/loop_engine/loop_engine_internal.h` | 注释:seamEvaluated 默认 false |
| `app/src/main/cpp/loop_engine/tests/loop_engine_ranking_test.cpp` | **新增**回归测试 |
| `app/src/main/cpp/loop_engine/tests/CMakeLists.txt` | 注册新测试目标 |
| `app/src/main/cpp/loop_engine/tests/loop_engine_flat_test.cpp` | 适配新默认;补"seam on + w=0 不改排名"与"w=0.5 仍融合" |
| `app/src/main/cpp/loop_engine/tests/loop_engine_parallel_test.cpp` | 默认路径 + seam 路径都做 bit-exact 校验 |
| `app/src/main/java/com/zjr/hesimusic/loop/analysis/LoopAnalysisManager.kt` | 注释:生产 score == legacyScore |
| `app/src/main/java/com/zjr/hesimusic/loop/analysis/LoopEngineJni.kt` | 注释:生产默认接缝未运行 |

未改:`loop_jni.cpp`(7 字段契约不变)、UI 展示代码、音频/解码/手势/限流等修复。

> 并行开发提示:同一工作区里任务 B(algo-lab)在 `loop_engine.cpp` /
> `loop_engine_internal.h` **追加** `LabParams/LabInfo`(仅 `debug::analyzeWithDebug`
> 的可选末尾参数,生产 `analyze()` 传 nullptr,不改默认值/排名公式)。其 native 已于
> 2026-11-22 17:01 冻结;本文 §3 的 host 单测与 6 首对比均在冻结后的联合树上重新构建
> 复跑通过。本任务只做定点 edit,未覆盖其追加块;两者的默认值互不影响。

---

## 5. 建议提交说明

```text
fix(analysis): rank like the initial engine again and leave the seam fusion off

生产排名恢复为初版语义:未设置 LOOP_ENGINE_SEAM 时接缝阶段不运行,融合权重默认 0.0,
LoopCandidate::score == legacyScore,候选顺序/坐标与初版逐位一致(6/6 测试曲目)。
接缝质量、拍内精修与融合排序代码完整保留,由 LOOP_ENGINE_SEAM=1 +
LOOP_ENGINE_SEAM_WEIGHT=0.50 显式启用(任务 B 的算法实验 app 使用)。

这是"混合提交只回退算法部分",两类修复均保留:
- dabf3cc:handle 带内 tap 视为 seek 的手势/预览策略保留;该提交同时加入的 v8
  接缝阶段默认关闭。
- f7314bb / 任务书写作 f7314cc(仓库无此 hash):chunked fallback 限流保留;
  同一提交里 tail→head 窗口、权重 0.5、2% 精修门槛等接缝算法默认关闭。

新增 loop_engine_ranking_test:同一份 PCM 下默认排名 == legacyScore 排名
(seam on + weight 0 与默认逐位一致)。
验证:host 三套单测全绿;6 首测试音频候选与初版 baseline 逐位相同;
JVM 单测 1803/0 failures;不构建 APK。
```
