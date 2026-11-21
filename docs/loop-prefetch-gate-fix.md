# 预取闸门修复（00027 发现）— 实现者可直接照做

> 结论一句话：`LoopPcmPrefetcher` 的循环条件用"缓存里任意位置已有 w 块"当闸门，
> 缓存填满后**预取线程永久停摆**，被淘汰的块只能等播放头到达时由**加载线程内联解码**。
> 实测 = 380 次/10min、累计 11.3–11.6s 加载线程停顿（单次 ~30ms）——这就是"50ms 块重建抖动"。
> 改成"播放头前方 w 块是否就绪"后：2 次/10min、累计 53–68ms。

数据来源：`tools/loop-ondemand-bench -Task prefetchgate`（生产类 + 固定种子，可复现）；
完整背景见 `docs/loop-decode-on-demand.md` §5.3。

## 1. 现场

`app/src/main/java/com/zjr/hesimusic/loop/audio/source/LoopPcmPrefetcher.kt:33-37`：

```kotlin
var guard = 0
while (!released && !LoopSourcePauseGate.paused &&
    core.readyBlockCount() < targetBlocks &&      // <-- 就是这一行
    core.needsDecode() &&
    guard < targetBlocks * 2
) { ... }
```

`readyBlockCount()` = `cache.activeBlockCount()` = **缓存内所有 READY 块**（`LoopingPcmSourceCore.kt:83`）。

## 2. 为什么会踩到

块缓存预算 32MiB = 190 块（44.1k 立体声，`tools/loop-ondemand-bench -Task memory` 实测）。

- **L ≤ 190s**：第一圈解完，全部块常驻、不再淘汰 → 没有缺口可预取，闸门真假无所谓（行为正确）；
- **L > 190s**（例：238s 窗口 = 239 块）：第一圈后缓存常驻 ~190 块 ⇒ `readyBlockCount() >= 6` **恒成立**
  ⇒ 闸门永远 false ⇒ 预取线程空转退出；每圈被淘汰的 49 块只能在播放头到达时
  由 `LoopingPcmExtractor.read` 的兜底 `core.pumpDecode()`（`LoopingPcmExtractor.kt:141-146`）**同步解码**。

结果不是欠载（5s 发射预算在兜底），而是**周期性微卡/余量损耗**：

| w | 闸门 | 加载线程内联解码次数 | 内联停顿累计 | 总解码次数（10min） |
|---|---|---|---|---|
| 2 | 现网 `readyBlockCount<w` | **382** | **11,378ms** | 384 |
| 2 | 修后 `needsDecode` | **2** | **53ms** | 401 |
| 3 | 现网 | 381 | 11,316ms | 384 |
| 3 | 修后 | 2 | 68ms | 466 |
| 6 | 现网 | 380 | 11,550ms | 384 |
| 6 | 修后 | 2 | 54ms | 664 |

## 3. 改法（3 处，共 ~6 行）

### 3.1 闸门换成"前沿"（LoopPcmPrefetcher.kt）

```kotlin
// before (L33-37)
while (!released && !LoopSourcePauseGate.paused &&
    core.readyBlockCount() < targetBlocks &&
    core.needsDecode() &&
    guard < targetBlocks * 2
) {

// after
while (!released && !LoopSourcePauseGate.paused &&
    core.needsDecode() &&                     // needsDecode 本身就是"当前块 + 前 w 块有缺口"
    guard < targetBlocks * 2
) {
```

`LoopingPcmSourceCore.needsDecode()`（`:121-134`）已经是正确语义：
当前块非 READY → true；否则检查 `wrappedBlock(current + 1..prefetchBlocks)` 是否有 null/PENDING。
**不要**把 `readyBlockCount() < targetBlocks` 换成 `bufferedEndUs()` / `nextLoadPositionUs()` 之类：
needsDecode 已经覆盖（且它的 lookahead 会跨 B，见设计文档 §2.1）。

**同一个文件还有第二处**（L44-45 的 finally 重踢条件），必须一起改，否则一批解完就不会再续：

```kotlin
// before
if (progressed && !released && !LoopSourcePauseGate.paused &&
    core.readyBlockCount() < targetBlocks && core.needsDecode()
) { kick() }

// after
if (progressed && !released && !LoopSourcePauseGate.paused && core.needsDecode()) { kick() }
```

### 3.2 窗口 6 → 3（LoopingPcmExtractorMediaSource.kt:119）

```kotlin
// before
const val SOURCE_PREFETCH_BLOCKS: Int = 6   // 6s > extractor 的 5s 发射预算，本身是超配

// after
const val SOURCE_PREFETCH_BLOCKS: Int = 3
```

理由：窗口只需覆盖**单块解码代价的尾部**（实测 c_max ≈ 80ms，p99 尾部 0.3–1.8s 也被 5s 预算吸收）；
它**不负责**抗线程冻结（冻结由发射预算兜底，实测把预算压到 1s 时窗口 2/3/5/6 都会破 4–16ms，且与 w 不单调）。
内存：1.01MiB → 517.2KiB（实测）。

### 3.3 淘汰距离改前向（PcmBlockCache.kt:178-195）

```kotlin
// before（伪距离：跨 B 的块被当成"很远"，实际它是"下一圈才需要"）
val distance = abs(index - playheadBlock)

// after（前向距离：离播放头越远的块越晚需要，先淘汰）
val distance = Math.floorMod(index - playheadBlock, blockCount)
```

注意：`PcmBlockCache` 目前只有 key 里的帧号、没有 `blockCount`，需要加一个构造参数
（`LoopingPcmSourceCore` 构造 cache 时传 `timeline.blockCount`；单测里同步补）。
额外加一条显式保护，防止极端预算下把前沿窗口淘汰掉：

```kotlin
if (index == playheadBlock) continue
val forward = Math.floorMod(index - playheadBlock, blockCount)
if (forward <= forwardWindowBlocks) continue   // forwardWindowBlocks = 3（= 预取窗口）
```

`evictForIncomingLocked` 会先腾空间再插新块，保护前沿不会导致插入失败；若真的腾不出来，
就退化为"允许一次插入超预算"，与现有 `playheadBlock` 保护的兜底语义一致。

## 4. 预期与代价

- **加载线程内联解码**：380–382 次/10min → **2 次**；累计停顿 11.3–11.6s → **53–68ms**；
- **总解码次数**：384 → 401（w=2）/ 466（w=3）/ 664（w=6），即 0.67–1.1 次/秒；
  按单块 30ms 计 ≈ 2–3.3% 单核（`decode done ... ms=` 可核对）；
- **内存**：窗口 6→3，块缓存 1.01MiB → 517.2KiB；
- **可听欠载**：离线仿真两档均 0 缺口（修复前后一致）——**不要用"没有欠载"验收，要用停顿分布验收**（见 §6）。

## 5. 不要顺手改的地方

| 位置 | 为什么不动 |
|---|---|
| `LoopingPcmSourceCore.needsDecode()` | 已经是"当前 + 前 w 块"的正确语义，是修法的依据 |
| `LoopingPcmExtractor.read` 的内联 `pumpDecode()` | 它是失败/首读兜底，不能删；修完闸门后它应该几乎不触发（建议加计数日志用于验收） |
| `LoopingPcmMediaPeriod`（手写 period，预览/旧路径） | 已按 `core.needsDecode()` 门控（`:358`），没有本 bug；不要按各路径各写一套 |
| 缓存 32MiB 预算 / 16MiB 下限 | 与本 bug 无关；容量 190 块是"每圈重解 49 块"的来源，属预期 |

## 6. 验证

**离线（不需要设备，秒级）**

```powershell
powershell -NoProfile -File tools/loop-ondemand-bench/run.ps1 -Task prefetchgate
# 期望：prod 档 inlineDecodes ≈ 380、inlineStallMs ≈ 11.3–11.6s；
#       fix  档 inlineDecodes = 2、inlineStallMs ≈ 50–70ms；两档 gapEvents = 0
powershell -NoProfile -File tools/loop-ondemand-bench/run.ps1 -Task memory   # w=3：517.2KiB
```

**单测**（现有 + 1 条新增）

- 现有：`PcmBlockCacheTest` / `LoopingPcmSourceCoreLoopTest` / `LoopingPcmExtractorTest`；
- 新增（建议）：`prefetch keeps the forward window ready when the cache is full of ready blocks elsewhere`——
  构造 L > 容量（例如 40 块 + 预算 10 块）、手动泵满缓存、把播放头推进到被淘汰块前，断言
  "在播放头到达前该块已 READY"（不依赖时间，用 `core.pumpDecode()` 驱动）。

**真机（等设备恢复）**

| 观测 | 修复前 | 修复后 |
|---|---|---|
| `logcat -s LoopPcmSource` 的 `decode start block=` 时间点 | 集中在播放头到达该块时 | 提前 ≥3s |
| `decode done ... ms=` 的 p50/p99 | 与"到达时刻"耦合 | 仍是单块代价，但不再构成 loading 停顿 |
| 加载线程上的 `pumpDecode`（建议加一行计数日志） | ~0.64 次/秒 | ≈0 |
| `/state` 的 `outputFrames` gap | **0**（看不到问题） | 0 |
| 主观/`tap` 微卡 | 周期性 | 消失或显著下降 |

> **判据警告**：修复前 `outputFrames` 是断不了的（5s 预算兜底），所以
> "outputFrames 无 gap" 不能证明没有问题；必须看**单次解码的耗时 / 发起时刻分布**（见设计文档 R10）。

## 7. 回滚

三处改动互相独立，可分别回滚：

1. 闸门（3.1）单独回滚 → 回到 380 次内联解码/10min，其余不变；
2. 窗口（3.2）单独回滚 → 内存回到 1.01MiB，停顿指标不变；
3. 淘汰距离（3.3）需要 `PcmBlockCache` 构造参数，回滚时把参数保留、把距离公式换回 `abs` 即可。

建议顺序：先 3.1 + 3.2（低风险、收益最大），观察一轮真机后再动 3.3。
