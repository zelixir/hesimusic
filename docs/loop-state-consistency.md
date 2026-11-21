# 循环状态一致性（D13 / D14 / D16 / D07 产品决定）

> 上游审计：docs/loop-semantics-spec.md 的 D13/D14/D16（以及 D07 的持久化裁决）。
> 本文记录**已落地的口径**，供 UI / 调试口 / 后续维护对照；实现以本文 + 单测为准。

## 1. 窗口合法性只有一套（D14）

唯一判据是 service/LoopWindowRules.kt：

| 入口 | 使用方式 |
|---|---|
| 主播放资格 LoopEligibility.derive | LoopWindowRules.writable(window, durationUs) |
| claim / UI / 通知 LoopClaimResolver | selectWindow 先丢非法窗口，claim 层 writable 再判一次 |
| SOURCE 预检 LoopSourceFallback.evaluate | LoopWindowRules.usable(window, durationUs) |
| M3 写入音频层（协调器 syncShape） | 写 audio.window 前 writable(window, audio.fullDurationUs) |

同一窗口（1-sample / 零长 / 反向 / 负 A / 499ms / B 越界 / 恰好 500ms）在四条路径上的
结论必须一致；LoopWindowConsistencyTest 钉住这个矩阵。

原因可见性：selectWindow 丢弃非法窗口后，LoopClaimResolver 仍直接对 runtimeWindow
做同一套判定，claimReason=window-invalid 不会因“窗口已被丢弃”而丢失。

## 2. confirmed 语义（D13）

- **主播放**：song_loop.confirmed=1（用户确认的最终结果）才允许进入无缝循环。
  未确认的临时候选是草稿，**只能在结果页预览试听**；预览路径不经过
  LoopEligibility，这是刻意的产品区分。
- **demote / delete**：正在循环的歌被取消确认或删除时，协调器**显式退场**：
  1. 清空显式意图（LoopIntent.clear()）；
  2. 恢复进入前的 repeat / shuffle（播放器 + 持久化偏好）；
  3. 发布 LoopClaimStore.publishExit(LoopExitNotice)，UI 可一次性提示；
  4. 窗口清空后走既有 SOURCE/M3 退出路径，位置映射回**媒体时间轴**继续播放。
  退场原因：confirmation-revoked / result-deleted / restore-unavailable。

## 3. repeat 偏好与重启（D16 + D07 产品决定）

**进入循环**：播放器与实际显示一致地设置 repeat=ONE + shuffle=false，并把这些值写进
PlaybackPreferences（LoopModePreference.Entered）；进入前的真实模式记录在
LoopIntent.Armed，并随意图一起持久化（SharedPreferences 文件 loop_intent）。

**退出循环**：清意图，恢复进入前的 repeat / shuffle（播放器 + 偏好）。

**进程重启：这是产品决定（D07）——恢复循环**，因为“上次在循环，回来还在循环”更符合用户心智。
但恢复前必须通过完整一致性校验，**只有以下全部成立才恢复**：

1. 当前歌 == 武装歌（且当前歌存在）；
2. song_loop 行存在且 confirmed=1；
3. 窗口通过 LoopWindowRules（≥500ms、B 不越界）；
4. 当前 repeat == REPEAT_MODE_ONE。

**不恢复的三种情况**（任一命中即显式丢弃，绝不假循环）：

1. 当前歌与武装歌不一致 / 没有当前歌；
2. 行不存在（delete）或 confirmed=0（demote）；
3. 窗口非法（1-sample / <500ms / B 越界）或 repeat 已不是 ONE。

丢弃时：清 intent → 恢复进入前模式（播放器 + 偏好）→ 发布
RESTORE_UNAVAILABLE 退场通知 → claim=false。这样“重启后是否在循环”与
“UI 显示的循环模式”永远由同一份状态决定，不会出现“显示单曲循环但实际普通播放”。

**重启期的瞬态 transition（真机实测）**：restore 装好循环后，setMediaItems/seek 会先派发
一次“队列首项（不是武装歌）”的 transition，约 300ms 后才回到武装歌。若按普通切歌立即清意图，
重启恢复会自己把自己拆掉。协调器的规则：只要 armed 歌与 transition 报的当前歌不一致，就先不动作、排一次 800ms 复核
（restore 前后的瞬态顺序不固定，所以不要求窗口已装好）；回到武装歌就取消，仍在别的歌才真正按
切歌退出（真实用户切歌最多延迟 800ms 恢复 repeat/shuffle，next/prev 的 exitForNavigation 不受影响）。

## 4. UI 消费接口（给 semantics-visible 路的一句话）

> collect(LoopClaimStore.exitNoticeFlow) 或 consumeExitNotice()；用
> notice.reason.wire + notice.summary() 映射一次性 Toast/横幅即可，不要自己从
> row/repeat 变化反推“是否刚被 demote”。

LoopClaimStore.revisionFlow 仍是 claim 重算信号；exitNoticeFlow 只负责“刚刚退场”的
一次性提示，不是第二套 claim 真值。

## 5. 验证

- JVM：LoopWindowConsistencyTest（四路径一致）、LoopDemotionConsistencyTest（demote 显式退场）、
  LoopIntentPersistenceTest（重启一致性）、LoopClaimWindowInvalidTest（原因可见）。
- 真机：进入循环后 /demote，/state 应看到 claimActive=false、repeat 回到进入前、
  位置继续在媒体时间轴；force-stop 后重启，/state.loop.claimActive 与
  /state.playback.repeatMode 必须同时为“在循环/单曲”或“不循环/进入前模式”。