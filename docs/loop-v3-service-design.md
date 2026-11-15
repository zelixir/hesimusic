# 服务层接线契约(P1-c):窗口 → 播放形态 → 位置投影

> **【定稿更新 2026-11-15 · 重要】** 本文档 §2/§3/§4 里描述的「给当前项套 `ClippingMediaSource(0,B)`」**已废弃**,实现改为**完全不裁剪**。
> 原因与收益(真机实证):
> 1. `ClippingMediaSource`/`ClippingConfiguration` 会让 sink 侧媒体时间整体偏移 `1e12`(ExoPlayer 先 `setOutputStreamOffsetUs(1e12)` 再 `configure`),导致音频层按 PTS 捕获 [A,B) 永远不命中;
> 2. 不裁剪同样能环回(音频层在 B 处切到 PCM 重放,越 B 的输入丢弃),因此:
>    - **进入/退出无缝不需要任何媒体操作**(只写 `LoopAudioController.window` 一个 volatile 值);
>    - `player.duration` 天然等于整曲时长(不需要 clip 补偿);
>    - `replaceMediaItem` + `NonUpdatableMediaSource` 那套强制重建机制**已全部删除**。
> 3. 现实现组件:`service/LoopShapePlan.kt`(纯函数形态决策:NOOP/ENTER/PRE_ROLL/EXIT/EXIT_SEEK)、`LoopSessionPlayer.kt`(投影)、`LoopPlaybackCoordinator.kt`、`LoopSessionProjection.kt`。
> 4. 投影的窗口来源:`runtime.window` 优先;**重放期 `runtime.window` 不可用(异步未落值/属于别的歌)时回退 `audio.window`**(否则 display 会跟着原始位置冲出 B,实测 +1.4s~2.7s)。
>
> 下文 §2~§5 保留作设计过程记录,凡与上述冲突处以本节为准。

> 依赖:①`loop/audio/LoopAudioController`(音频层)②`LoopRuntime`(已交付)③`song_loop` 数据层(已交付)。
> 前置事实(P0 实测):引擎侧 sink 位置持续前进,但 **ExoPlayer 对外的 `currentPosition`/`duration` 被 clip 钳住**,
> 所以位置/时长必须由会话层投影 —— 这是本层的核心职责。
> API 已用 javap 核对:`Player.replaceMediaItem(int, MediaItem)`、`setMediaItem(MediaItem, long)` 均存在。

## 1. 目标与硬不变量

1. **无缝播放期间不做任何媒体结构操作**:不换 item、不 apply/teardown 时间线、不改 repeat。
2. **窗口只有一个来源**:`LoopRuntime.window`;UI 标记、音频层 region、位置投影三者同读它。
3. **引擎从不修改 repeatMode**(repeat 只代表用户意图)。
4. 任何事件只做一件事:**重算一次 `LoopRuntime.refresh(...)`**,再按"窗口是否变化"决定是否需要一次性重建播放形态。
5. 播放器状态机(play/pause/queue/repeat/shuffle/notification/focus)保持权威,本层只做投影与形态。

## 2. 组件

```
service/
  LoopMediaSourceFactory.kt   // 装饰 DefaultMediaSourceFactory:当前 item 命中窗口 → ClippingMediaSource(0, B)
  LoopSessionPlayer.kt        // ForwardingPlayer:位置/时长/seek 投影(给 MediaSession 用)
  LoopPlaybackCoordinator.kt  // 窗口应用/拆除 + degrade 处理 + refresh 收口
```

### 2.1 LoopMediaSourceFactory

```kotlin
class LoopMediaSourceFactory(
    private val delegate: MediaSourceFactory,
    private val runtime: LoopRuntime,
) : MediaSourceFactory {
    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val window = runtime.window.value
        val songId = songIdOf(mediaItem)            // 与现有 mediaId 约定一致
        return if (window != null && window.songId == songId) {
            // 输入在 B 结束:解码到 B 即 EOS → sink 在 B 精确切到重放
            ClippingMediaSource(delegate.createMediaSource(mediaItem), 0L, window.loopEndUs)
        } else delegate.createMediaSource(mediaItem)
    }
    // getSupportedTypes/setDrmSessionManager 等一律转发 delegate
}
```

- **队列里的 MediaItem 永远保持原样**(不带 clip),只有"当前播的那一项"被装饰 → 队列/恢复/收藏/上下首零影响。
- clip 后 `player.duration` = B,由 §2.2 投影成整曲时长。

### 2.2 LoopSessionPlayer(ForwardingPlayer)

```kotlin
class LoopSessionPlayer(player: Player, private val runtime: LoopRuntime,
                        private val audio: LoopAudioController) : ForwardingPlayer(player) {
    override fun getCurrentPosition(): Long {
        val w = runtime.window.value ?: return super.getCurrentPosition()
        // 重放期:sink 的位置(B + (k-1)L + 环内偏移);非重放期:player 位置(引擎原生)
        val rawUs = if (audio.isReplaying) audio.positionUs else super.getCurrentPosition() * 1000
        return LoopPosition.displayUs(rawUs, w) / 1000
    }
    override fun getBufferedPosition(): Long = /* 同理,display 后 clamp 到 duration */
    override fun getDuration(): Long {
        val w = runtime.window.value ?: return super.getDuration()
        return audio.fullDurationUs / 1000          // 整曲时长(服务层在切歌时写入 controller)
    }
    override fun seekTo(positionMs: Long) = seekToInternal(currentMediaItemIndex, positionMs)
    override fun seekTo(index: Int, positionMs: Long) = seekToInternal(index, positionMs)
    private fun seekToInternal(index: Int, positionMs: Long) {
        val w = runtime.window.value
        val targetUs = positionMs * 1000
        if (w == null || index != currentMediaItemIndex) { super.seekTo(index, positionMs); return }
        val rawUs = LoopPosition.rawSeekUs(targetUs, w, currentRawUs())
        super.seekTo(index, rawUs / 1000)
    }
}
```

- **只重写这些**。`play/pause/setMediaItem/setRepeatMode/shuffleMode` 等一律透传(不要像旧实现那样在 setRepeatMode 里埋逻辑)。
- `currentRawUs()`:`audio.isReplaying → audio.positionUs`,否则 `super.getCurrentPosition() * 1000`。

### 2.3 LoopPlaybackCoordinator(薄,单例,主线程)

```kotlin
@Singleton class LoopPlaybackCoordinator @Inject constructor(
    private val runtime: LoopRuntime,
    private val audio: LoopAudioController,
    private val dao: SongLoopDao,
    private val songs: SongRepository,
) {
    /** 唯一事件入口:切歌/repeat 变化/行变更/restore 完成 都调这里。 */
    suspend fun refresh(songId: Long?, repeatMode: Int, player: Player, reason: String)
    /** 应用/拆除播放形态(幂等)。 */
    private fun applyShape(window: LoopWindow?, player: Player, song: Song)
    /** 音频层请求的降级 seek(ring 不完整)。 */
    fun onDegradeRequested(targetRawUs: Long, player: Player)
}
```

**行变更的触发方式(2026-11-15 裁定:用 Room Flow,不靠 UI 手工通知)**:

`LoopPlaybackCoordinator` 在当前歌切换时重启一个订阅:

```kotlin
observeJob?.cancel()
observeJob = serviceScope.launch {
    dao.getBySongIdFlow(songId).collect { row ->
        runtime.refresh(songId, player.repeatMode, row)
        applyShapeIfChanged(runtime.window.value, player)
    }
}
```

这样 analyze 落库临时结果、confirm、demote、delete、外部改库 **全部自动传导**,UI 不需要认识播放层
(这正对应旧版 §15 抱怨的"demote/delete 只改数据库,没有任何通路通知播放引擎")。

**refresh 流程(严格按序)**:

1. `row = songId?.let { dao.getBySongId(it) }`;
2. `runtime.refresh(songId, repeatMode, row)`(纯函数,幂等);
3. `audio.fullDurationUs = song.duration * 1000`;
4. 比较"上一次已应用的形态"(songId + clipEnd + region)与目标形态:
   - 相同 → **什么都不做**;
   - 不同 → `applyShape(...)`(见 §3)。

### 2.4 MusicService 改动点(最小)

1. 注入 `LoopPlaybackCoordinator` / `LoopRuntime` / `LoopAudioController` / `SongLoopDao`;
2. `player` 的 `MediaSourceFactory` 换成 `LoopMediaSourceFactory(DefaultMediaSourceFactory(...), runtime)`;
3. `sessionPlayer = LoopSessionPlayer(player, runtime, audio)`,`MediaSession.Builder(this, sessionPlayer)`;
4. 监听器:
   - `onMediaItemTransition` → `serviceScope.launch { coordinator.refresh(songIdOf(item), player.repeatMode, player, "transition") }`(在现有 saveCurrentState 之前/之后皆可,但**不要**再有任何 loop 专属分支);
   - `onRepeatModeChanged` → 同上(reason="repeat");
   - `restorePlaybackState` 完成后 → 同上(reason="restore");
5. **删除**:无(基线本来就没有 loop 代码)。

## 3. applyShape:窗口变化时唯一的一次媒体操作

```kotlin
private fun applyShape(window: LoopWindow?, player: Player, song: Song) {
    // 1) 先写音频层 region(A/B + 整曲时长),再动播放器
    audio.window = window
    // 2) 当前 item 的形态由 LoopMediaSourceFactory 决定 → 需要重建 source 才生效
    val idx = player.currentMediaItemIndex
    if (idx < 0) return
    val item = player.currentMediaItem ?: return
    val displayUs = /* 当前位置(展示口径) */ player.currentPosition * 1000
    val rawUs = window?.let { LoopPosition.rawSeekUs(displayUs, it, displayUs) } ?: displayUs
    player.replaceMediaItem(idx, item)          // 触发 source 重建(clip 由 factory 施加)
    player.seekTo(idx, rawUs / 1000)
    applied = Shape(song.id, window?.loopEndUs, window)
}
```

- **幂等**:窗口与 clip 形态都未变时不做任何操作(否则每次 repeat 切换都会重建)。
- 进入无缝(=用户点「无缝循环」/确认保存):窗口写入 + 形态应用,**一次**;之后整个播放期不再动。
- 退出无缝(取消最终结果/删除/切歌到无结果歌):窗口置 null + 形态还原(去 clip)+ 从展示位置续播。

## 4. 降级(必须显式,不许静默)

| 触发 | 处理 |
|---|---|
| ring 未覆盖 [A,B)(用户 seek 进区间中部) | `LoopAudioController` 发出 degrade 请求(目标 raw 位置)→ coordinator `player.seekTo` 预卷(实测:seek 到 A 会因解码 seek 精度落到 A+15ms,需回跳 A-250ms 预卷);打 `LoopPerf degraded` |
| ring 超上限(300s/64MB) | 窗口置 null + Toast「该曲循环区间过长,已按普通单曲循环播放」;播放不中断 |
| 解码失败 / 分析链异常 | 同上,窗口置 null |
| 窗口区间 < 1 个解码帧 | 分析侧就该拒绝(候选过滤),此处兜底同样降级 |

## 5. 位置、时长、持久化

- **对外(MediaSession/UI/通知)**:`LoopSessionPlayer` 的 display 位置 + 整曲时长。
- **持久化**(`saveCurrentState`):用 `sessionPlayer.currentPosition`(展示口径),这样重启恢复后位置正确。
- **恢复**:restore 完成后 refresh → 若窗口命中,形态应用 + 从展示位置映射回 raw 起播(不从头)。
- **进度条 A/B 标记**:UI 读 `LoopRuntime.window`(不是自己推断);显示条件 = window != null && window.songId == 当前歌。

## 6. 与既有功能的交互(逐条)

| 功能 | 行为 |
|---|---|
| 队列/上下首/随机 | 原生;**切到别的歌 → refresh → 窗口按新歌重算**(状态驱动:已确认歌 + 单曲循环 → 继续无缝) |
| repeat 切换 | 用户意图;OFF/ALL → 窗口 null(形态还原);ONE → 命中则可无缝 |
| 通知/锁屏/耳机键 | 原生;位置/时长走投影 |
| 音频焦点丢失 | 系统 pause → sink pause → 泵停止;恢复后续跑(已实测) |
| 睡眠定时 | 原生 pause;重放永不 EOS,不影响 |
| 后台/前台 | 原生 |
| 杀进程重启 | 原生 restore + 一次 refresh |
| 分析页预览 | 独立 PreviewPlayer(播放链 P3 再定,复用同一 sink 机制) |

## 7. 测试点

- **L1**:`LoopSessionPlayer` 投影(纯函数,可 fake Player):display/seek/duration 三向一致;窗口 null 时完全透传。
- **L2(协议)**:`/state` 暴露 `engine(sink|plain)`、`window`、`isReplaying`、`positionMs(display)`、`durationMs(整曲)`、`seamCount`、`degradeCount`,供 E2E 断言。
- **L3**:位置序列断言(跨 B 回 A、不越界)、A1/A2/A4/B。
- **L5**:风暴测试下 `isReplaying` 与 `window != null` 恒一致(界面与行为一致性)。

## 8. 实现顺序

1. `LoopSessionPlayer`(纯投影,先测);
2. `LoopMediaSourceFactory` + MediaModule 接线;
3. `LoopPlaybackCoordinator` + MusicService 三处监听;
4. degrade 通路;
5. L2 协议暴露(与 P4 协议改造合并)。