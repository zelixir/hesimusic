# Loop Seam Splice Buffer (00017) — 设计

## 1. 目标

长窗口（>30s）不预填整环，只用 **2Δ** 的小接缝缓冲（Δ=1..2s，典型 0.5~1s）完成：
- 正常播放 A → B−Δ 直接走源解码；
- B−Δ 处切到接缝缓冲 S，跨过 B 并覆盖下一轮 A → A+Δ；
- S 播放期间 seek 源到 A+Δ 并预滚；
- S 播完切回源 A+Δ，继续正常播放；
- 源未就绪时用同一个 2Δ 缓冲的后半段继续解码下一个 Δ 续播（defer），最多 N 次，失败回退现有 M3′/透传。

内存：2Δ × bytesPerFrame。48kHz/2ch/16bit、Δ=1s 时约 384KB（2×48000×4）。

## 2. 缓冲布局

```
S = [B-Δ, B)  ‖  [A, A+Δ)
    └── X ──┘     └── Y ──┘
|X| = |Y| = Δ
```

- **X：预解码**。`PcmDecoder.decodePcm16Range(B−Δ, B)` 与 Y 使用同一套严格帧对齐
  流程，确定性输出与源播放帧级一致；因此第一轮即可在 B−Δ 切 S，无需 warm-up。
- **在线校验**：切换点拿实际 live PCM 的首个/前 16 个样本与 X 对比，超过阈值
  （默认 64 LSB）则本轮不切、继续源播，`seamVerifyFailCount++`；下一轮重新校验。
  若个别格式存在 1~2 帧对齐差，也不会在错误位置切换。
- **Y：精确解码** `PcmDecoder.decodePcm16Range(A, A+Δ)`，安装到 S 后半段。

## 3. 状态机

```
IDLE
  └─(warm-up 开始/窗口设置)─> CAPTURING_SEAM_PREFIX
        │  正常输出 A..B-Δ；在 [B-Δ,B) 捕获 X
        │  prepare Y = decode [A,A+Δ)
        ▼
   PLAYING_SEAM  (B-Δ)
        │  依序输出 X → Y（长度 2Δ）
        │  同时请求 seek 到 A+Δ 并预滚
        ▼
   RESUMING_SOURCE
        │  source ready?
        ├─ 是 → IDLE，源从 A+Δ 继续
        └─ 否 → defer: 复用 Y 区解码下一个 Δ，输出；seamDeferCount++
                连续 defer 超过 N → FALLBACK（M3′/透传），绝不 stall
```

切换点连续性：
- normal last frame = B−Δ−1，S first = B−Δ → gap 0；
- X last = B−1，Y first = A → 循环边界，gap 0；
- Y last = A+Δ−1，源 resume first = A+Δ → gap 0；
- defer：Y last = A+Δ−1，defer first = A+Δ → gap 0。

## 4. 时序

```
源:      A ......... B-Δ | B          A+Δ ......... B-Δ | B
输出:    A ......... B-Δ | X(B-Δ..B) Y(A..A+Δ) | A+Δ ..... B-Δ | X ...
                          └──── S ────┘
                                  └ seek A+Δ / pre-roll
```

## 5. 兜底

- source 在 S 播完仍未 ready：把 Y 区覆盖为 `[A+Δ+(k-1)Δ, A+kΔ)` 并输出，
  内存仍 2Δ；`seamDeferCount++`；最多 N 次。
- 超过 N：关闭 seam 模式，回退 M3′（完整环/分片预填）或透传；打
  `seamDeferCount` / `seamFallbackCount`，绝不无声 stall。
- 窗口变更/取消：立即 `cancel()`，释放 S（`seamBufferBytes=0`）。

## 6. 统计

- `seamBufferBytes`：正常 2Δ×bpf，defer 复用不增长；
- `seamSwitchCount`：进入 PLAYING_SEAM 次数；
- `seamDeferCount`：源未就绪顺延次数；
- `maxSwitchGapFrames`：相邻输出段样本索引缺口，正常应恒为 0。

## 7. 与 M3′ 的取舍

| 场景 | 建议 |
|---|---|
| 窗口 ≤ 30s，或预填可快速完成 | M3′ 整环/分片预填：实现成熟、无 seek 预滚 |
| 窗口 > 30s，内存敏感 | Seam Splice：2Δ 内存、~0.2s 起播；依赖 seek 预滚 |
| seek/循环不稳定设备 | M3′/透传兜底 |
| 需要严格逐样本跨接缝 | Seam Splice（X 实时捕获） |

## 8. 接线状态（00017 第二步）

- `LoopSeamSpliceBuffer`：纯 Kotlin 状态机与缓冲；
- `SeamBufferMode` 默认 `OFF`；
- 真机验证开关：`seamSinkRouteEnabled` 默认 false；只有
  `seamActiveForWindow && seamSinkRouteEnabled` 才进入 sink 路由；
- `LoopAudioSink` 路由：NORMAL 源直通到 B−Δ → 在线校验 → SEAM 输出 X/Y →
  `seamSeekRequested(A+Δ)` → WAIT_SOURCE → `handleDiscontinuity` + 源写回；
  S 背压走 `seamQueue/pendingSeam`，部分消费重试；
- provider 注入：`LoopPlaybackCoordinator` 提供 `seamDecodeRange`（缓存 + 后台 Δ 解码）
  与 `seamSeekRequested`（独立 seek，绕过 degrade backward 拦截）；
- 默认 `OFF` 时 sink 仍走 M3′，不改 PTS/位置投影。
