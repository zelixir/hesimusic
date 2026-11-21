# 循环 claim 真机验证清单（00027）

> 目标：在真机上确认 **「声称循环」==「源真的在环回」**，并用内容真值（PCM 互相关）兜底，
> 不再只信元数据层的 `engine=loop` / A/B 标记。
>
> 适用构建：带 `app/src/debug` 的 debug 包（LoopDebugServer 监听 127.0.0.1:8765）。
> 先执行：`adb forward tcp:8765 tcp:8765`；后续所有 `curl` 都打 `http://127.0.0.1:8765`。

---

## 0. 先看 /state 的新字段

```powershell
curl -s http://127.0.0.1:8765/state | ConvertFrom-Json | Select-Object -Expand loop
```

必须包含：

| 字段 | 含义 | 期望 |
|---|---|---|
| `claimed` | 当前歌有意图窗口（用户/播放层声称要循环） | 进入无缝后 true |
| `claimActive` | 对用户/MediaSession **生效**的 claim | 只有源真的建成才 true |
| `sourceActive` | 源真的在环回（SOURCE 建成 / M3′ 写入 sink） | 与 claimActive 一致 |
| `claimReason` | claim 未生效的原因 | 正常为 null；回退时为五种之一 |
| `fallbackReason` | 兼容字段，最近一次回退原因 | 与 claimReason 一致 |
| `window` | 意图窗口 | 进入后命中当前歌 |
| `engine` / `loopPath` | 派生口径 | claimActive=true → `loop` + `source`/`m3`；false → `exo` + `none` |

`/state.playback.positionMs` 完全依赖 claim：
- claimActive=true → 折返到 [A,B)；
- claimActive=false → 原样线性媒体时间（**允许超出 B**）。

---

## 1. 正常路径（源生效）

1. 用已确认循环的歌执行 `POST /loop/enter {songId, play:true}`。
2. 轮询 `/state`，直到：
   ```text
   loop.claimed        = true
   loop.claimActive    = true
   loop.sourceActive   = true
   loop.claimReason    = null
   loop.engine         = "loop"
   loop.loopPath       = "source"（SOURCE 路线）
   playback.positionMs ∈ [A,B)
   playback.durationMs = 整曲时长
   ```
3. seek 到 B-5s，跨过 B；每 500ms 采样一次：
   - claimActive 必须一直 true；
   - positionMs 必须始终 ∈ [A,B)；
   - `/state.invariants` 不应出现 `loop-claim-without-source`。

4. **内容真值**（必跑）：
   ```powershell
   powershell -NoProfile -File tools/scenarios/run-scenarios.ps1 --exclusive
   ```
   关注 20~25 号内容场景（seek-past-b / near-b / one-lap / boundary / random / ended）。
   判据：NCC peak >= 0.90 且 |偏移| <= 600ms；基线自身 peak >= 0.95。
   只有这一层能证伪「进度条对、声音错」。

---

## 2. 五种回退原因的真机/半自动验证

> 单测已覆盖五种原因（`LoopClaimTest` / `LoopSourceFallbackTest` /
> `LoopClaimInvariantTest`）；下面是真机取证的推荐做法。

### 2.1 ring-too-large（最容易复现）

- 选一首循环窗口 PCM > heap/6 的歌（例如 4 分钟以上、48k/16bit/立体声）；
- 进入无缝后，预期：
  ```text
  loop.claimed        = true
  loop.claimActive    = false
  loop.sourceActive   = false
  loop.claimReason    = "ring-too-large"
  loop.engine         = "exo"
  loop.loopPath       = "none"
  playback.positionMs = 线性媒体时间（可 > B）
  playback.durationMs = 整曲
  ```
- `/state.invariants` 在 1s 内出现 `loop-claim-without-source`（count>=1）。
- 通知/锁屏时长 = 整曲，不是 -1。

### 2.2 format-unsupported

- 用容器可读、音轨不可解码的样本（或 probe 抛异常的格式）；
- 预期同上，`claimReason="format-unsupported"`，日志 `LoopPerf` 中出现
  `source unavailable -> fallback M3' reason=format-unsupported`。

### 2.3 file-missing

- 进入无缝后把文件移走/改名（或进入前路径不可读）；
- 预期 `claimReason="file-missing"`，位置线性、通知时长整曲。
- 恢复文件后，改一次 A/B 触发重新判定（旧登记按窗口区分；同窗口不会自动重试）。

### 2.4 window-invalid

- 正常 UI 路径不会产生非法窗口（`LoopEligibility.derive` 已过滤）；
- 半自动：直接往 `song_loop` 插一行 `loopStartMs >= loopEndMs` 的已确认行，再 arm；
- 预期 `claimed=true`（如果窗口仍被算出）/ `claimActive=false`，`claimReason="window-invalid"`。
- 或只依赖单测：`LoopSourceFallbackTest.invalid window is registered as window invalid`。

### 2.5 build-failed

- 构造「probe 能过、真正建源/解码首块失败」的样本（截断文件、坏帧头）；
- 预期日志 `LoopPerf: source install skipped built=... unavailable=true reason=build-failed`，
  且不再出现 `source item installed`；
- `/state.loop`：`sourceActive=false`、`claimReason="build-failed"`、`engine=exo`；
- 位置线性、时长整曲、A/B 标记不画。

---

## 3. 逐项断言脚本（手工）

```powershell
$st = curl -s http://127.0.0.1:8765/state | ConvertFrom-Json
$l = $st.loop
$p = $st.playback
# 1) engine/claim 一致性
if (($l.engine -eq 'loop') -ne [bool]$l.claimActive) { throw "engine/claim mismatch" }
if ([bool]$l.claimActive -ne [bool]$l.sourceActive) { throw "claim/source mismatch" }
# 2) 回退时不得折返、时长必须整曲
if (-not $l.claimActive) {
    if ($null -eq $l.claimReason) { throw "inactive claim without reason" }
    if ($p.durationMs -le 0) { throw "duration is not the full song" }
}
# 3) 生效时位置必须在 [A,B)
if ($l.claimActive) {
    $a = [long]($l.window.loopStartUs / 1000)
    $b = [long]($l.window.loopEndUs / 1000)
    if ($p.positionMs -lt $a -or $p.positionMs -ge $b) { throw "position outside [A,B)" }
}
# 4) 不变量
$inv = curl -s http://127.0.0.1:8765/state | ConvertFrom-Json
$inv.invariants | Where-Object { $_.code -eq 'loop-claim-without-source' }
```

---

## 4. 自动化脚本已加的等待条件

已更新（P3）：

- `tools/loop-e2e.ps1`
  - `Initialize-Seamless` / 再次进入 / 区内 seek 恢复：等待
    `loop.claimed=true` 且 `loop.sourceActive=true`；
  - 位置断言只在 `claimActive=true` 时执行（回退时允许线性位置）；
  - 风暴测试新增 `claimFallbacks` 指标；违规判据改为
    `engine == claimActive`、`claimActive == sourceActive`、`claimActive ⇒ window 属于当前歌`。
- `tools/engine-cost-gate.ps1`：开测/稳态等待要求 `sourceActive=true`。
- `tools/engine-cost-baseline.ps1`：已就绪判定与最终检查要求 `sourceActive=true`。

仍建议后续把 `tools/loop-e2e.ps1` 里其余 `engine=loop` 等待逐条换成
`claimActive`/`sourceActive` 双条件；本次先改关键成功路径与位置断言，避免大范围改脚本。

---

## 5. 通过标准

1. 正常路径：`claimActive=sourceActive=true`，位置 ∈ [A,B)，时长整曲，内容 NCC >= 0.90；
2. 五种回退：`claimed=true, claimActive=false, sourceActive=false, claimReason=<五因之一>`，
   位置线性、时长整曲、A/B 不画、不变量出现 `loop-claim-without-source`；
3. `/state.playback.positionMs` 与 `/state.loop.claimActive` 严格同步；
4. 通知/锁屏/车机时长不再是 -1；
5. About 在回退时显示「无循环 (普通播放)」，不再显示 M3′；不同歌的 reason 不串。
