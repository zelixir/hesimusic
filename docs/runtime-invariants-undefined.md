# §12.2 UNDEFINED 运行时断言交付说明

> 来源：`docs/exoplayer-assumptions-verified.md` §12.2（12 条 UNDEFINED；任务描述里写“13 条”，
> 但清单与文档实际都是 12 条，本文按清单逐条交付）。
>
> 代码位置：
> - 判定逻辑（纯 Kotlin / 可 JVM 测）：`app/src/main/java/com/zjr/hesimusic/core/invariants/AssumptionInvariants.kt`
> - 事实探针（main 源集只增计数/读取）：`app/src/main/java/com/zjr/hesimusic/core/invariants/LoopInvariantProbes.kt`
> - 采样与注入：`app/src/debug/java/com/zjr/hesimusic/debug/PlaybackInvariantMonitor.kt`
> - 单测：`app/src/test/java/com/zjr/hesimusic/core/invariants/AssumptionInvariantsTest.kt`
>
> 结果读取：`GET /state.invariants`（最近 10 条，同 code 连续累加 count）与
> `GET /state.invariantProbe`（最近一次采样的原始事实）。判定命中同时 `Log.e`（tag `LoopInvariant`）。
>
> 测量口径（00028/#105）：判定采样周期是 500ms，但 `/state.invariantProbe` 的
> `writtenFrames` 与 `atMs` 在**读取时**用实时计数/当前时间覆盖，其余字段仍来自最近一次
> 完整采样。纪律：**快照周期不得大于任何停顿判据的阈值**（旧实现 500ms 快照 > 400ms 阈值时，
> “帧数不涨”无法区分真断流与采样间隙，#105 的 452/488ms 假阳性即由此而来）。

## 怎么注入（真机一条命令）

```bash
adb forward tcp:18765 tcp:8765

# 注入某一条（kind 见下表）
curl -s -X POST http://127.0.0.1:18765/debug/invariant \
  -d '{"kind":"sink-position-domain"}'

# 看结果：invariants 应出现对应 code（约 1s 内，prepare-error 为 3s 后）
curl -s http://127.0.0.1:18765/state | jq '.invariants, .invariantProbe'

# 关闭注入 / 清空历史
curl -s -X POST http://127.0.0.1:18765/debug/invariant -d '{"kind":"off"}'
curl -s -X POST http://127.0.0.1:18765/debug/invariant -d '{"kind":"clear"}'
```

每种 kind 都是把**合成采样**喂给**真实判定器**，验证「采样 → 判定 → Log.e → /state.invariants」
整条链路；唯一例外是 `prepare-error`：它只记录“注入时刻”，然后观察真实 `onPlayerError`
是否在 3s 内到达（3s 内到 → 不报；没到 → 报）。这样它验证的是真实的错误上抛路径，而不是合成判定。

## 逐条说明

| # | code | 对应 | 触发条件 | 注入 kind | 失败时的用户可见后果 |
|---|---|---|---|---|---|
| 1 | `period-continue-loading-missing` | A-025 | 当前 period `onPrepared` 后 500ms 内，media3 调 `continueLoading` 的次数为 0，且我们也没有主动踢（`manualKicks=0`）。probe 里同时暴露 `continueLoadingAtManualKick`，用于判断“media3 是否先于我们的踢调用”。 | `period-continue-loading` | prepared 之后 loader 没有任何驱动 → 播放器停在 BUFFERING（已 prepared 但拿不到后续数据），表现为“一直转圈、不出声/不前进”。 |
| 2 | `extractor-track-after-end-tracks` | A-037 | 同一 extractor 生命周期内 `tracksCreated > 1`；或 endTracks 之后又建 track；或 endTracks 之后才给 seekMap；或 endTracks 时 seekMap 仍为空。 | `extractor-track-after-end` | media3 会丢弃 endTracks 后的新 track/sample（日志 “Extractor added new track … after finishing tracks”）→ 数据被静默丢弃，表现为环回点附近短静音/缺帧，且日志只有一行 warning。 |
| 3 | `sink-empty-buffer-while-playing` | A-049 | `LoopAudioSink.handleBuffer` 收到 `remaining < bytesPerFrame` 的空 buffer，且引擎不在 REPLAYING、sink 也不在暂停态（正常播放态）。 | `sink-empty-buffer` | 源侧短读/丢帧被“返回 true 吞掉”掩盖 → 环回点前后出现可听的断音/跳帧，进度条仍在走。 |
| 4 | `sink-configure-split-mismatch` | A-055 | `configureCount < windowSplitCount`（item/窗口边界发生了但 sink 没 reconfigure），或 configure 后首帧与 configure 前最后一段的媒体时间 PTS 跳变 > 100ms（且 configure 未伴随 flush）。 | `sink-configure-split` | 环回锚点错：新窗口的 PTS 与 delegate 时间轴对不上，表现为边界处爆音、静音或环回点偏移（听起来“接缝不在 A/B 上”）。 |
| 5 | `timeline-frame-us-roundtrip-drift` | A-091 | 任一 A/B 锚点 `us → frame → us` 往返误差 >= 1 帧；或最近最多三圈 A/B 帧号差出现不一致。 | `timeline-frame-us-roundtrip` | 取整方向不一致会逐圈累积漂移：越听越“提前/拖后”，长时间播放后环回点明显偏离 A/B（用户感知是接缝逐渐错位、可能有咔哒声）。 |
| 6 | `projection-duration-branch-mismatch` | A-093 | duration 三态与投影分支不符：负值（含 TIME_UNSET）却没走 FALLBACK；0 被当成“未知”而走 FALLBACK（约定里 0 是真实零长度）；正值没投影成自身时长；或展示位置越出 `[0, duration]`。 | `projection-duration` | 进度条/通知时长错误：未知时长被显示成 0 或末帧，0 时长源被透传成整曲时长；拖动位置可能被钳到末尾，表现为“进度条卡死/跳到结尾”。 |
| 7 | `sink-position-domain-mismatch` | A-NEW-01 | `getCurrentPositionUs` 返回的位置与最近一次写给 delegate 的 PTS 不在同一时间域（offset>0 时一方 >= offset、另一方 < offset）。 | `sink-position-domain` | ExoPlayer 位置静默错约 1e12µs（≈11.6 天）：进度条瞬间跳到极端值或回弹，seek/通知/锁屏位置全部错乱。 |
| 8 | `seek-same-position-discontinuity-timeout` | A-NEW-03 | seek 目标与请求时位置相同（±1ms）、没有收到 discontinuity、超过超时（1.5s）后覆盖层仍 pending，或位置没落在目标 ±50ms。 | `seek-same-position` | 拖到当前位置/轻点进度条后覆盖层永久卡住（UI 停在 Seeking），或 seek 丢失、位置不更新；用户看到“松手后进度条不回弹也不动”。 |
| 9 | `error-prepare-not-surfaced` | A-NEW-07 | 记录到 prepare 错误注入时刻后，3s 内没有 `onPlayerError`（或错误时间晚于 deadline）。 | `prepare-error` | 错误只从 `MediaSource.maybeThrowSourceInfoRefreshError` 抛（该路径播放器不调用）→ 永远 BUFFERING，没有错误提示、也不会自动跳过；用户看到“无限加载”。 |
| 10 | `sink-configure-dropped-pending-replay` | A-NEW-09 | configure 时 `pendingReplay != null`（说明期间没有 flush），生产代码随后清掉它 → 记一次 dropped；判定器对 dropped 增量报错。 | `sink-configure-no-flush` | 重复 configure 丢掉正在重放的一段数据：环回点附近短静音/跳帧（几十 ms），连续触发时听起来像“环回处周期性卡一下”。 |
| 11 | `controller-available-commands-missing` | A-NEW-11 | controller 连接后 `availableCommands` 缺少 `ExpectedControllerCommands.SET` 中的任意一项（play-pause / prepare / seek / next / previous / set-media-item / change-media-items / repeat / shuffle / get-current-media-item / get-timeline）。 | `controller-available-commands` 见下方注 | MediaController 对未授权命令只打 warning 后静默 return → 按钮点了没反应（典型：下一首/上一首、seek、暂停全部无效），且 UI 状态看起来正常。 |
| 12 | `service-notification-gone-after-pause` | A-NEW-12 | 暂停持续超过 N（默认 5s）后，本包自己的媒体通知（id 1001 或静默前台 2001 / 对应 channel）不在 `activeNotifications` 中。 | `notification-gone` | 暂停后通知被撤/服务被回收：用户从通知栏无法恢复播放，锁屏/蓝牙控制也一起消失；后台再回来可能直接冷启动。 |

> 注：A-NEW-11 的注入 kind 是 `controller-commands`（上表“注入 kind”列里带“见下方注”的写法只是排版说明）；
> 以 `PlaybackInvariantMonitor.FAULT_KINDS` 为准，服务端 400 响应会回显允许列表。

## 采样点与真机行为

- **A-025**：`LoopingPcmMediaPeriod.init/prepare/continueLoading` 写探针（`onPeriodCreated/Prepared/ContinueLoading/ManualKick`）。
  注意 §12.3 已删除正常路径的“主动踢”，所以 `manualKicks` 正常为 0；probe 里 `continueLoadingAtManualKick`
  仍保留，用于以后需要恢复主动踢时比较“media3 是否先调”。
- **A-037**：`LoopingPcmExtractor.init` / `RingBandExtractor.init` 的 `track/seekMap/endTracks` 顺序写探针。
- **A-049 / A-055 / A-NEW-01 / A-NEW-09**：`LoopAudioSink` 的 `configure/handleBuffer/getCurrentPositionUs/flush`
  只增计数/读取（不改播放行为）；窗口切分次数由 debug monitor 的 `onMediaItemTransition` 统计。
- **A-091**：monitor 从 `controller.window + outputSampleRate` 计算，按 `seamCount` 增量收集最近 3 圈的 A/B 帧号差；
  换算公式与 `LoopPcmEngine` 完全同口径（core 里复刻，单测交叉验证）。
- **A-093**：monitor 读 `player.duration`（ms 三态）与 `LoopSessionProjection.durationUs`，仅当窗口活跃时判定。
- **A-NEW-03**：UI 的 pendingSeek 覆盖层在 `ui/**` 内（本任务不改 ui），debug 侧无法直接读；
  注入 kind 用合成采样表示“超时后覆盖层仍 pending”，同时真机仍可观察 `onPositionDiscontinuity` 是否缺席。
- **A-NEW-07**：monitor 记录 `/debug/invariant {kind:"prepare-error"}` 的时刻，监听真实 `onPlayerError()`；
  3s 内到达则通过，否则上报。
- **A-NEW-11**：`DebugPlayerConnection.availableCommandIds()` 在主线程读 `controller.availableCommands`。
- **A-NEW-12**：monitor 用 `NotificationManager.activeNotifications` 判断本包媒体通知是否仍在；
  暂停时长从 `playWhenReady` 的 true→false 沿开始计。

## 失败时的调试顺序

1. `GET /state.invariantProbe` 看原始计数（例如 `period.continueLoadingAfterPrepare`、`sink.configureCount`、
   `sink.positionReturnedUs`）；
2. `logcat -s LoopInvariant` 看 `INVARIANT violated: <code> ...` 的明细；
3. 需要复现时用上表 kind 注入，确认 code 能在 `/state.invariants` 出现；
4. 修复后用 `kind:"clear"` 清历史，再跑一遍正常播放确认不再出现。

## 跑单测

```bash
./gradlew :app:testProdDebugUnitTest --tests "com.zjr.hesimusic.core.invariants.*"
```

每条至少 2 个用例（触发 / 不触发）；`AssumptionInvariantsTest.frameUsProductionMathStaysBelowOneFrame`
额外用 44.1k/48k/96k 与多个锚点验证 A-091 的生产换算确实 < 1 帧。

| code | 单测方法（AssumptionInvariantsTest） |
|---|---|
| `period-continue-loading-missing` | periodContinueLoadingMissingTriggersAfterGrace / …DoesNotTriggerWhenMedia3AlreadyCalled / …InsideGraceWindow / …ReportsOncePerPreparedPeriod |
| `extractor-track-after-end-tracks` | extractorDuplicateTrackTriggers / extractorTrackAfterEndTracksTriggers / extractorSeekMapMissingAtEndTracksTriggers / extractorHealthyLifecycleDoesNotTrigger |
| `sink-empty-buffer-while-playing` | emptyBufferWhilePlayingTriggers / …WhileReplayingDoesNotTrigger / …WhilePausedDoesNotTrigger / emptyBufferReportsOnlyNewOccurrences |
| `sink-configure-split-mismatch` | configureShortfallTriggers / configureFirstPtsJumpTriggers / configureMatchingWindowSplitsDoesNotTrigger / configureShortfallInsideWindowSplitGraceDoesNotTrigger / configureReportsNewBadCountsOnly |
| `timeline-frame-us-roundtrip-drift` | frameUsRoundtripErrorAtOneFrameTriggers / frameUsVaryingAbDeltasTriggers / frameUsHealthyRoundtripDoesNotTrigger / frameUsProductionMathStaysBelowOneFrame |
| `projection-duration-branch-mismatch` | zeroDurationTreatedAsFallbackTriggers / unknownDurationClampedAsPositiveTriggers / unknownDurationFallbackDoesNotTrigger / positiveDurationClampDoesNotTrigger / displayPositionBeyondDurationTriggers |
| `sink-position-domain-mismatch` | mediaDomainPositionWithOffsetDomainPtsTriggers / sameOffsetDomainDoesNotTrigger / notSetPositionDoesNotTrigger / positionDomainReportsOnceUntilItRecovers |
| `seek-same-position-discontinuity-timeout` | samePositionOverlayStillPendingAfterTimeoutTriggers / differentPositionSeekDoesNotTrigger / samePositionWithDiscontinuityDoesNotTrigger / samePositionReachedWithoutOverlayDoesNotTrigger / samePositionInsideTimeoutDoesNotTrigger |
| `error-prepare-not-surfaced` | prepareErrorNotSurfacedAfterDeadlineTriggers / prepareErrorSurfacedInsideDeadlineDoesNotTrigger / prepareErrorBeforeDeadlineDoesNotTrigger |
| `sink-configure-dropped-pending-replay` | configureDroppingPendingReplayTriggers / configureContinuingPendingReplayDoesNotTrigger / configurePendingReplayReportsOnlyNewDrops |
| `controller-available-commands-missing` | missingSeekToNextCommandTriggers / allExpectedCommandsPresentDoesNotTrigger / disconnectedControllerDoesNotTrigger / missingCommandsReportAgainForNewController |
| `service-notification-gone-after-pause` | notificationGoneAfterPauseTriggers / notificationPresentAfterPauseDoesNotTrigger / notificationCheckBeforeThresholdDoesNotTrigger / notificationReArmsAfterPlaybackResumes |

本地验证记录（2026-11-20）：

- `AssumptionInvariantsTest`：**50 tests / 0 failures**（每条 ≥3 个触发/不触发用例）；
- 同包既有不变量：AudioOutputFrames 3 / InvariantRegistry 4 / OutputInvariant 7 / SourceInvariant 5，全绿；
- 被计数的生产类回归：LoopAudioSinkTest 44 / LoopAudioControllerTapTest 5 / LoopingPcmExtractorTest 4 /
  RingBandExtractorTest 4 / LoopingPcmMediaPeriodPrepareTest 3 / LoopingPcmMediaPeriodTrackTest 1，全绿；
- `:app:compileProdDebugKotlin`（含 debug 源集）通过。
