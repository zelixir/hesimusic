# media3 1.8.0 源码事实清单(任务 B 前置材料)

> 目的:在任务 A 的 `docs/exoplayer-assumptions-spec.md` 出现前,先把与我们实现直接相关的
> media3 1.8.0 真实行为固定下来,每条都给 `文件:行` 证据。
>
> 源码位置:`e2e-artifacts/media3-src/extracted/<module>-sources/...`(7 个模块全部解压:
> common / exoplayer / extractor / decoder / session / container / datasource)。
> 行号对应 1.8.0 sources jar 原始文件。

## 1. ExoPlayerImpl(应用线程 API)

| # | 事实 | 证据 |
|---|------|------|
| 1.1 | 所有 public 方法先 `verifyApplicationThread()`;在错误线程访问时,默认只打一条 warning(`throwsWhenUsingWrongThread=false`),不会抛异常 | `ExoPlayerImpl.java:2906-2925` |
| 1.2 | `prepare()` 只在 `playbackState == STATE_IDLE` 时生效,其它状态直接 return;然后 mask 成 BUFFERING(timeline 非空)或 ENDED(timeline 为空) | `ExoPlayerImpl.java:528-551` |
| 1.3 | `setMediaSources(list)` 默认 `resetPosition=true`(从第一个 window 的默认位置开始);`setMediaSources(list, startWindowIndex, startPositionMs)` 才是定点 | `ExoPlayerImpl.java:601-622` |
| 1.4 | `setMediaSourcesInternal`:`resetToDefaultPosition` 时用 `timeline.getFirstWindowIndex(shuffle)` + `startPositionMs=TIME_UNSET`;非 IDLE 时把状态 mask 成 BUFFERING;起点超出 windowCount 抛 `IllegalSeekPositionException` | `ExoPlayerImpl.java:2410-2465` |
| 1.5 | `seekTo`:`mediaItemIndex == INDEX_UNSET` 直接 return;timeline 非空且 index >= windowCount 也直接 return(静默);广告播放中被忽略并打 `seekTo ignored because an ad is playing` | `ExoPlayerImpl.java:884-931` |
| 1.6 | `seekTo` 会把 READY/ENDED mask 成 BUFFERING 后再通知,并用 `maskTimelineAndPosition` 改写本地 position | `ExoPlayerImpl.java:911-930` |
| 1.7 | `getDuration()` = `getContentDuration()`(广告期除外);未知时返回 `C.TIME_UNSET` | `ExoPlayerImpl.java:1106-1115` |
| 1.8 | `getCurrentPosition()` 在 timeline 为空时返回 `maskingWindowPositionMs`(上一次的 mask 位置),否则由 `positionUs` 折算 | `ExoPlayerImpl.java:1118-1121`, `2011-2026` |
| 1.9 | `getBufferedPosition()` = `getContentBufferedPosition()`;广告期特殊处理 | `ExoPlayerImpl.java:1124-1132` |
| 1.10 | `stop()` → `stopInternal(null)`:清 loading/buffered,状态转 IDLE;`internalPlayer.stop()` 会释放 media source list 但保留播放位置 | `ExoPlayerImpl.java:1012-1017`, `1969-1988`;`ExoPlayerImplInternal.java:1846-1856` |
| 1.11 | `release()` **没有**幂等保护(不检查 `playerReleased`),只在末尾置 `playerReleased=true`;Player 文档写"release 后不得再使用" | `ExoPlayerImpl.java:1020-1074`;`Player.java:2823-2830` |
| 1.12 | `setPlaybackParameters(null)` 等价 DEFAULT;与当前相同时直接 return(不触发事件) | `ExoPlayerImpl.java:952-971` |

## 2. 时间轴/掩码(MaskingMediaSource、MediaSourceList)

| # | 事实 | 证据 |
|---|------|------|
| 2.1 | 未 prepare 的 source 用 `PlaceholderTimeline`:1 window / 1 period,`isSeekable=false`,`isDynamic=true`,`durationUs=TIME_UNSET`,`window.isPlaceholder=true` | `MaskingMediaSource.java:354-397` |
| 2.2 | 真实 timeline 到达后 `onChildSourceInfoRefreshed` 替换 timeline 并 `refreshSourceInfo`,period uid 保持 masking uid | `MaskingMediaSource.java:142-208`, `217-229` |
| 2.3 | `MaskingMediaSource.createPeriod` 在 source 未 prepared 时只创建 1 个 deferred period,并把 prepare 位置覆盖到真实 timeline 的第一 period | `MaskingMediaSource.java:106-124`, `232-252` |
| 2.4 | `MediaSourceList.prepare` 断言 `!isPrepared`,对所有 holder 调 `prepareChildSource` → `mediaSource.prepareSource(...)`;`release()` 对所有 child 调 `releaseSource` | `MediaSourceList.java:310-319`, `466-475`, `360-374` |
| 2.5 | `BaseMediaSource.prepareSource`:第一个 caller 到来(looper==null)时才回调 `prepareSourceInternal`;`releaseSource` 到最后一个 caller 时 `looper=null, timeline=null` 并回调 `releaseSourceInternal` | `BaseMediaSource.java:243-296` |
| 2.6 | 推论(需自定义 MediaSource 遵守):同一个 MediaSource 实例会经历 **prepare → release → 再 prepare** 的循环(stop() 释放 list 后 prepare() 会重新 prepareSource) | 2.4 + 2.5 + `ExoPlayerImplInternal.java:954-966` |
| 2.7 | `MediaSourceList.addMediaSources` 在 `isPrepared` 时逐个 `prepareChildSource`;若当前已有 media period 则新 source 先 `disable` | `MediaSourceList.java:143-175`, `164-171` |
| 2.8 | seek 位置换算 `Timeline.getPeriodPositionUs` 把 period 内位置 clamp 到 `[0, durationUs-1]`(duration 已知时) | `Timeline.java:1206-1252` |

## 3. Progressive 源

| # | 事实 | 证据 |
|---|------|------|
| 3.1 | ProgressiveMediaSource 的 timeline = `SinglePeriodTimeline(timelineDurationUs, timelineIsSeekable, isDynamic=false, useLiveConfiguration=isLive, ...)` | `ProgressiveMediaSource.java:485-495` |
| 3.2 | `onSourceInfoRefreshed` 会保留已取到的 duration(`durationUs == TIME_UNSET` 时用旧值),并抑制无变化的刷新 | `ProgressiveMediaSource.java:457-477` |
| 3.3 | ProgressiveMediaPeriod 的 `prepare` 前不能调用其它 MediaPeriod 方法:`assertPrepared()` 检查 `prepared` 标志 | `ProgressiveMediaPeriod.java:1021-1026` |
| 3.4 | 加载结束早于 prepare 完成 → `maybeThrowPrepareError` 抛 `ParserException.createForMalformedContainer("Loading finished before preparation is complete.")` | `ProgressiveMediaPeriod.java:282-288` |
| 3.5 | 容器没有 duration(如无 Xing 头的 mp3)时,时长在**加载完成**时用"最大排队时间戳 + DEFAULT_LAST_SAMPLE_DURATION_US"补出来 | `ProgressiveMediaPeriod.java:636-670` |
| 3.6 | `seekMap.isSeekable()==false` 时 `seekToUs` 强制成 0(所有 seek 等价 t=0) | `ProgressiveMediaPeriod.java:469-473` |
| 3.7 | `getBufferedPositionUs` 在 loadingFinished 或无启用轨道时返回 `C.TIME_END_OF_SOURCE`(= `Long.MIN_VALUE`) | `ProgressiveMediaPeriod.java:439-466`;`C.java:55` |
| 3.8 | `continueLoading` 在 loadingFinished / fatal error / deferred retry / 无启用轨道时返回 false | `ProgressiveMediaPeriod.java:398-412` |

## 4. Extractor / ExtractorOutput 契约

| # | 事实 | 证据 |
|---|------|------|
| 4.1 | `init(ExtractorOutput)` 至多一次;`read` 不得早于 `init`;seek 只能到 stream 起点或 SeekMap 给出的位置 | `Extractor.java:92-137` |
| 4.2 | `endTracks()` 约定之后再无新 trackId;同一个 id 多次调用 `track()` 返回同一个 TrackOutput | `ExtractorOutput.java:48-64` |
| 4.3 | ProgressiveMediaPeriod 在 `endTracks()` **之后**再收到新 track 只打 warning `Extractor added new track (id=...) after finishing tracks.` 并返回 `DiscardingTrackOutput`(数据被静默丢弃) | `ProgressiveMediaPeriod.java:801-823` |
| 4.4 | prepare 完成需要同时满足:`sampleQueuesBuilt`(endTracks 已调)、`seekMap != null`、每个队列都有 upstream format | `ProgressiveMediaPeriod.java:837-845` |
| 4.5 | `seekMap()` / `endTracks()` 都只是 post 到 playback handler,不在调用线程同步生效 | `ProgressiveMediaPeriod.java:771-780` |

## 5. AudioSink / DefaultAudioSink

| # | 事实 | 证据 |
|---|------|------|
| 5.1 | `handleBuffer` 返回 false = 没吃完,**同一个 ByteBuffer 必须继续提供**直到吃完(除非中间 flush/configure) | `AudioSink.java:517-538`;`DefaultAudioSink.java:954`(checkArgument 同一实例) |
| 5.2 | `getCurrentPositionUs` 不可用时返回 `CURRENT_POSITION_NOT_SET = Long.MIN_VALUE` | `AudioSink.java:399-401`, `486-493` |
| 5.3 | DefaultAudioSink 在 AudioTrack 未初始化 / start time 未 init 时返回 `CURRENT_POSITION_NOT_SET`,否则 `min(tracker position, written frames)` 再套 playback params/skip | `DefaultAudioSink.java:716-724` |
| 5.4 | `setPlaybackParameters` 把 speed/pitch clamp 到 0.1..8;有 AudioTrack playback params 时走 AudioTrack,否则走 audio processor | `DefaultAudioSink.java:484-496`, `1437-1452`;`Player.java-style` 见 `DefaultAudioSink.java:849` |
| 5.5 | `isEnded()` = 未初始化 或 (已 `playToEndOfStream` 且无 pending data);`playToEndOfStream` 只有 drain 成功才置 `handledEndOfStream` | `DefaultAudioSink.java:1401-1407`, `1424-1434` |
| 5.6 | `flush()` 会 **异步释放 AudioTrack**(已知设备问题),并清空 submitted/written 帧计数 | `DefaultAudioSink.java:1612-1646`, `1692-1700` |
| 5.7 | `handleDiscontinuity()` 只是把 `startMediaTimeUsNeedsSync=true`,真正的 start time 在下一个 buffer 处理时同步 | `DefaultAudioSink.java:945-947`, `1005-1014` |
| 5.8 | `outputStreamOffsetUs` 由 renderer 在 **stream change / enable** 阶段通过 `setOutputStreamOffsetUs` 传入,**先于** `configure`(configure 在首次送 buffer 前的 `updateAudioSinkFormatIfNeeded` 里) | `MediaCodecRenderer.java:2352-2357` → `MediaCodecAudioRenderer.java:886-889`;`MediaCodecAudioRenderer.java:651-667`;`DecoderAudioRenderer.java:715-736`, `481-498`, `515-533` |
| 5.9 | offset 值来自 period 的 renderer offset(`MediaPeriodHolder.rendererPositionOffsetUs`),不是常量;disable 时传 `C.TIME_UNSET`(DecoderAudioRenderer 会跳过 sink 调用) | `MediaPeriodHolder.java:135-168`;`DecoderAudioRenderer.java:700-705`, `529-534` |
| 5.10 | `ForwardingAudioSink` 逐方法委托(含 `setOutputStreamOffsetUs` / `flush` / `reset` / `release`) | `ForwardingAudioSink.java:36-216` |

## 6. AudioFocus

| # | 事实 | 证据 |
|---|------|------|
| 6.1 | duck 只改音量乘数 0.2,不改 playWhenReady | `AudioFocusManager.java:123`, `346-363` |
| 6.2 | `AUDIOFOCUS_LOSS` → `PLAYER_COMMAND_DO_NOT_PLAY`(暂停);`LOSS_TRANSIENT` → 等回调(暂停);`LOSS_TRANSIENT_CAN_DUCK` → 视 `willPauseWhenDucked()` 决定 duck 或暂停 | `AudioFocusManager.java:365-388` |
| 6.3 | 只有 `USAGE_MEDIA`/`USAGE_GAME` 且 `handleAudioFocus=true` 才自动管理焦点;其它 usage 抛 `IllegalArgumentException` | `AudioFocusManager.java:165-173` |

## 7. MediaController / MediaSessionService

| # | 事实 | 证据 |
|---|------|------|
| 7.1 | 未连接时所有命令**静默 no-op**(play/seekTo/stop 等),getter 返回默认值(`getDuration()=TIME_UNSET`、`getCurrentPosition()=0`、`getPlaybackState()=STATE_IDLE`) | `MediaController.java:641-648`, `742-750`, `792-810`, `883-998` |
| 7.2 | `release()` 幂等(released 标志);未连接时 release 会走 `connectionCallback.onRejected()` | `MediaController.java:657-688` |
| 7.3 | 命令受 `intersectedPlayerCommands` 限制,不允许时打 `Controller isn't allowed to call command= N` 并 return | `MediaControllerImplBase.java:3144-3150` |
| 7.4 | `isConnected()` 就是 `iSession != null` | `MediaControllerImplBase.java:316-319` |
| 7.5 | `MediaSessionService.onGetSession` 每来一个 controller 调一次(主线程),返回的 session 自动加入服务;返回 null 拒绝连接 | `MediaSessionService.java:248-282`, `297-316` |
| 7.6 | `onTaskRemoved` 默认:播放未进行或没有 session 在播 → `pauseAllPlayersAndStopSelf()` | `MediaSessionService.java:584-619` |
| 7.7 | 通知/前台由 `onUpdateNotification(session, startInForegroundRequired)` 处理;targetSdk>=28 需要 `FOREGROUND_SERVICE` 权限 | `MediaSessionService.java:646-679`, `665-667` |
| 7.8 | `MediaSession.Callback.onConnect` 默认直接接受(默认 session/player commands);可 `ConnectionResult.reject()` | `MediaSession.java:1462-1487`, `2109-2112` |

## 8. 关键常量

| 常量 | 值 | 证据 |
|------|----|------|
| `C.TIME_END_OF_SOURCE` | `Long.MIN_VALUE` | `C.java:55` |
| `C.TIME_UNSET` | `Long.MIN_VALUE + 1` | `C.java:61` |
| `C.INDEX_UNSET` | `-1` | `C.java:64` |
| `AudioSink.CURRENT_POSITION_NOT_SET` | `Long.MIN_VALUE` | `AudioSink.java:401` |
| `DefaultAudioSink.MIN/MAX_PLAYBACK_SPEED` | 0.1 / 8 | `DefaultAudioSink.java:487-490` |
| `AudioFocusManager.VOLUME_MULTIPLIER_DUCK` | 0.2 | `AudioFocusManager.java:123` |

## 9. 待核对清单(等 spec 落地后逐条判定)

- 自定义 AudioSink 包装:handleBuffer 背压 / 同一 ByteBuffer / position 推进语义
- 自定义 MediaSource/MediaPeriod:prepare 生命周期、endTracks 之后不得新增 track、seekMap 必须给
- stop()→prepare() 的 source 重新 prepare 语义
- controller 连接前后命令可用性、前台服务与通知
- seek clamp、未知时长、非 seekable 源的 seek 行为
- 静音/duck/焦点丢失对 playWhenReady 与 state 的影响

## 10. 六段关键源码摘录(带行号)

### 10.1 `ExoPlayerImpl.prepare()` 的 STATE_IDLE 守卫

`media3-exoplayer-sources/androidx/media3/exoplayer/ExoPlayerImpl.java:527-551`

```java
  @Override
  public void prepare() {
    verifyApplicationThread();
    if (playbackInfo.playbackState != Player.STATE_IDLE) {
      return;                                    // 非 IDLE:静默 no-op
    }
    PlaybackInfo playbackInfo = this.playbackInfo.copyWithPlaybackError(null);
    playbackInfo =
        maskPlaybackState(
            playbackInfo, playbackInfo.timeline.isEmpty() ? STATE_ENDED : STATE_BUFFERING);
    pendingOperationAcks++;
    internalPlayer.prepare();
    updatePlaybackInfo(playbackInfo, TIMELINE_CHANGE_REASON_SOURCE_UPDATE, false, ...);
  }
```

要点:重复 prepare 不会重建源;错误会被清空(`copyWithPlaybackError(null)`)。

### 10.2 replaceMediaItem 复用旧源的条件

`MediaSourceList.updateMediaSourcesWithMediaItems`(`MediaSourceList.java:265-273`)——**只调 updateMediaItem,不重建源**:

```java
  public Timeline updateMediaSourcesWithMediaItems(
      int fromIndex, int toIndex, List<MediaItem> mediaItems) {
    Assertions.checkArgument(fromIndex >= 0 && fromIndex <= toIndex && toIndex <= getSize());
    Assertions.checkArgument(mediaItems.size() == toIndex - fromIndex);
    for (int i = fromIndex; i < toIndex; i++) {
      mediaSourceHolders.get(i).mediaSource.updateMediaItem(mediaItems.get(i - fromIndex));
    }
    return createTimeline();
  }
```

判定入口 `ExoPlayerImpl.replaceMediaItems`(`ExoPlayerImpl.java:729-763`):

```java
  public void replaceMediaItems(int fromIndex, int toIndex, List<MediaItem> mediaItems) {
    verifyApplicationThread();
    checkArgument(fromIndex >= 0 && toIndex >= fromIndex);
    int playlistSize = mediaSourceHolderSnapshots.size();
    if (fromIndex > playlistSize) return;             // 静默
    toIndex = min(toIndex, playlistSize);
    if (canUpdateMediaSourcesWithMediaItems(fromIndex, toIndex, mediaItems)) {
      updateMediaSourcesWithMediaItems(fromIndex, toIndex, mediaItems);  // 复用旧源
      return;
    }
    List<MediaSource> mediaSources = createMediaSources(mediaItems);     // 否则重建
    ...
```

`canUpdateMediaSourcesWithMediaItems`(`ExoPlayerImpl.java:2957-2970`)逐项问 `MediaSource.canUpdateMediaItem`。
`MaskingMediaSource.canUpdateMediaItem` 原样委托 child(`MaskingMediaSource.java:80-83`):

```java
  public boolean canUpdateMediaItem(MediaItem mediaItem) {
    return mediaSource.canUpdateMediaItem(mediaItem);
  }
```

`updateMediaItem` 里只换 timeline 的 MediaItem 展示信息 + 通知 child(`MaskingMediaSource.java:85-95`)。

**结论:能否复用旧源完全由具体 MediaSource 的 `canUpdateMediaItem` 决定;ProgressiveMediaSource 是 URI 相同即可复用(见 10.3)。**

### 10.3 `ProgressiveMediaSource.canUpdateMediaItem`

`media3-exoplayer-sources/androidx/media3/exoplayer/source/ProgressiveMediaSource.java:373-386`

```java
  @Override
  public boolean canUpdateMediaItem(MediaItem mediaItem) {
    MediaItem.LocalConfiguration existingConfiguration = getLocalConfiguration();
    @Nullable MediaItem.LocalConfiguration newConfiguration = mediaItem.localConfiguration;
    return newConfiguration != null
        && newConfiguration.uri.equals(existingConfiguration.uri)
        && newConfiguration.imageDurationMs == existingConfiguration.imageDurationMs
        && Objects.equals(newConfiguration.customCacheKey, existingConfiguration.customCacheKey);
  }

  @Override
  public synchronized void updateMediaItem(MediaItem mediaItem) {
    this.mediaItem = mediaItem;
  }
```

要点:**uri 不变 + imageDurationMs 不变 + customCacheKey 不变 ⇒ 复用旧源,不会重新 open/seek**;
换 uri 才会走重建分支。`MediaItem.mediaId`/`tag`/`mediaMetadata` 变化不阻止复用。

### 10.4 ProgressiveMediaPeriod 的加载循环与 load 位置

`ProgressiveMediaPeriod.ExtractingLoadable.load()`(`ProgressiveMediaPeriod.java:1102-1163`):

```java
    @Override
    public void load() throws IOException {
      int result = Extractor.RESULT_CONTINUE;
      while (result == Extractor.RESULT_CONTINUE && !loadCanceled) {
        try {
          long position = positionHolder.position;
          dataSpec = buildDataSpec(position);
          long length = dataSource.open(dataSpec);
          if (loadCanceled) break;
          if (length != C.LENGTH_UNSET) { length += position; onLengthKnown(); }
          ...
          while (result == Extractor.RESULT_CONTINUE && !loadCanceled) {
            try { loadCondition.block(); } catch (InterruptedException e) { throw new InterruptedIOException(); }
            result = progressiveMediaExtractor.read(positionHolder);
            long currentInputPosition = progressiveMediaExtractor.getCurrentInputPosition();
            if (currentInputPosition > position + continueLoadingCheckIntervalBytes) {
              position = currentInputPosition;
              loadCondition.close();
              handler.post(onContinueLoadingRequestedRunnable);   // 回到 playback 线程要更多数据
            }
          }
        } finally {
          if (result == Extractor.RESULT_SEEK) result = Extractor.RESULT_CONTINUE;
          else if (progressiveMediaExtractor.getCurrentInputPosition() != C.INDEX_UNSET)
            positionHolder.position = progressiveMediaExtractor.getCurrentInputPosition();
          DataSourceUtil.closeQuietly(dataSource);
        }
      }
    }
```

- 分辨率:`continueLoadingCheckIntervalBytes`(默认 `DEFAULT_LOADING_CHECK_INTERVAL_BYTES = 1024*1024`,即 1MB,`ProgressiveMediaSource.java:312`)或 loadCondition 关闭时才回 playback 线程;
- `Extractor.RESULT_SEEK` 只更新 `positionHolder` 并重开 DataSource,**不结束 load**;
- 结束条件只有 `RESULT_END_OF_INPUT`(-1)或取消。

`getNextLoadPositionUs`(`ProgressiveMediaPeriod.java:419-422`):

```java
  @Override
  public long getNextLoadPositionUs() {
    return getBufferedPositionUs();     // 已加载完时 = C.TIME_END_OF_SOURCE
  }
```

### 10.5 DefaultAudioSink:谁 start / pause AudioTrack

`DefaultAudioSink.play()`(`DefaultAudioSink.java:933-942`)——**唯一无条件 start 的地方**:

```java
  @Override
  public void play() {
    playing = true;
    if (isAudioTrackInitialized()) {
      audioTrackPositionTracker.start();
      if (!stoppedAudioTrack || isOffloadedPlayback(audioTrack)) {
        audioTrack.play();
      }
    }
  }
```

`DefaultAudioSink.pause()`(`DefaultAudioSink.java:1601-1610`):

```java
  @Override
  public void pause() {
    playing = false;
    if (isAudioTrackInitialized()) {
      audioTrackPositionTracker.pause();
      if (!stoppedAudioTrack || isOffloadedPlayback(audioTrack)) {
        audioTrack.pause();
      }
    }
  }
```

`handleBuffer` 只在 `startMediaTimeUsNeedsInit` 收尾时**跟随 playing 标志**再调一次 play()
(`DefaultAudioSink.java:1005-1018`):

```java
    if (startMediaTimeUsNeedsInit) {
      startMediaTimeUs = max(0, presentationTimeUs);
      ...
      if (playing) {
        play();                    // playing=false(pause 后)绝不会 start
      }
    }
```

`stoppedAudioTrack` 只由 `playToEndOfStream`→`playPendingData()` 置 true(`DefaultAudioSink.java:2009-2021`),
并在 `resetSinkStateForFlush` 清回 false(`DefaultAudioSink.java:1708`)。

**结论:AudioTrack 的 start/pause 全部由 ExoPlayer 的 renderer 通过 sink.play()/pause() 驱动;
handleBuffer 不会在 `playing==false` 时启动播放。包装 sink 若自己调 play() 会打破 pause 语义(issue 00020 的根因)。**

### 10.6 MediaController 线程亲和

`media3-session-sources/androidx/media3/session/MediaController.java:202-204, 2112-2114`

```java
  private static final String WRONG_THREAD_ERROR_MESSAGE =
      "MediaController method is called from a wrong thread."
          + " See javadoc of MediaController for details.";
  ...
  private void verifyApplicationThread() {
    checkState(Looper.myLooper() == getApplicationLooper(), WRONG_THREAD_ERROR_MESSAGE);
  }
```

与 ExoPlayerImpl 不同:`checkState` 直接抛 `IllegalStateException`(不是只打 warning)。
另外 `MediaController.createDisconnectedFuture()`/`notifyAccepted` 也用 `checkState`(同文件 2081-2096)。

## 11. 我们代码里的调用点清单(按 10 个分类;spec 到手后按它的编号重排)

> 路径相对 `app/src/main/java/com/zjr/hesimusic/`。分类名为临时分组,spec 落地后一一映射。

### A. Player 生命周期 / 状态机
| 调用 | 位置 |
|---|---|
| `player.setMediaItems(...)` + `player.prepare()` | `service/MusicService.kt:195-196` |
| `player.pause()` / `player.play()` | `service/MusicService.kt:224`, `:244` |
| `player.release()` | `service/MusicService.kt:458` |
| `player.replaceMediaItem(...)` /`setMediaItems(items, index, ms)` /`prepare()/play()` 复活 | `service/LoopPlaybackCoordinator.kt:742`, `:758-760`, `:772-777`, `:829-834` |
| `onPlayWhenReadyChanged` / `onIsPlayingChanged` 观测 | `service/MusicService.kt:299-307` |
| `isPlaying` 轮询 | `service/MusicService.kt:380` |

### B. 媒体项 / 播放列表
| 调用 | 位置 |
|---|---|
| `controller.setMediaItem(mediaItem)` | `ui/common/MusicViewModel.kt:435` |
| `controller.setMediaItems(items, startIndex, 0)` | `ui/common/MusicViewModel.kt:481` |
| `controller.addMediaItems(insertIndex, …)` / `removeMediaItem` / `moveMediaItem` | `ui/common/MusicViewModel.kt:509`, `:571`, `:575`, `:596` |
| `player.setMediaItems(mediaItems)`(服务侧) | `service/MusicService.kt:195` |
| replaceMediaItem 后判断"是否真的重建" | `loop/audio/source/LoopAwareMediaSourceFactory.kt:158` |

### C. seek / 位置 / 时长
| 调用 | 位置 |
|---|---|
| `player.seekTo(ms)` / `seekTo(index, ms)` | `service/LoopPlaybackCoordinator.kt:227`, `:607`, `:772`, `:832`, `:882`;`service/MusicService.kt:217` |
| `seekToDefaultPosition(...)` | `service/MusicService.kt:123`, `:136`;`ui/common/MusicViewModel.kt:472`, `:532`, `:644` |
| `controller.seekTo(position)` | `ui/common/MusicViewModel.kt:547` |
| `super.seekTo(...)`(投影包装) | `service/LoopSessionPlayer.kt:120`, `:138`, `:147`, `:156` |
| `getCurrentPosition/getDuration/getBufferedPosition` 包装 | `service/LoopSessionPlayer.kt:61-113`, `:201` |
| 预览播放器 seek | `ui/loop/PreviewPlayer.kt:270`, `:370`, `:498`, `:540-546`, `:704`, `:945-954` |

### D. 播放参数(speed/pitch)
| 现状 | 位置 |
|---|---|
| 代码里**没有** `setPlaybackSpeed/setPlaybackParameters` 调用(grep 0 命中) | — |

### E. AudioSink 契约(包装/自研)
| 调用 | 位置 |
|---|---|
| `configure(format, bufferSize, channelConfig)` | `loop/audio/LoopAudioSink.kt:140`;`loop/audio/source/SourceRenderersFactory.kt:61` |
| `handleBuffer(...)` 背压与同 buffer 复用 | `loop/audio/LoopAudioSink.kt:165-336`(尤其 :199-206, :308, :336);`SourceRenderersFactory.kt:94-185` |
| `super.handleDiscontinuity()` | `loop/audio/LoopAudioSink.kt:231`;`SourceRenderersFactory.kt:197` |
| `play/pause/flush/reset/release` 覆写 | `loop/audio/source/SourceRenderersFactory.kt:187-217` |
| 直接向 delegate 写 PCM(`super.handleBuffer` 带改写 PTS) | `loop/audio/LoopAudioSink.kt:308-317` |
| sink 注入点 | `loop/audio/LoopRenderersFactory.kt:21-31` |

### F. MediaSource / MediaPeriod 契约(自研源)
| 调用 | 位置 |
|---|---|
| `maybeThrowPrepareError` / `getTrackGroups` / `selectTracks` | `loop/audio/source/LoopingPcmMediaPeriod.kt:200`, `:205`, `:215` |
| `readDiscontinuity()` 恒 `C.TIME_UNSET` | `loop/audio/source/LoopingPcmMediaPeriod.kt:249` |
| `seekToUs(positionUs)` | `loop/audio/source/LoopingPcmMediaPeriod.kt:251` |
| `continueLoading` 去重(同一时刻一个 worker) | `loop/audio/source/LoopingPcmMediaPeriod.kt:356` 附近 |
| `Handler(Looper.myLooper() ?: main)` | `loop/audio/source/LoopingPcmMediaPeriod.kt:88` |

### G. Extractor / ExtractorOutput(自研 extractor)
| 调用 | 位置 |
|---|---|
| `init(output)` 里 `output.seekMap(seekMap)` + `output.endTracks()` | `loop/audio/source/LoopingPcmExtractor.kt:60-66` |
| `SeekMap.getSeekPoints` 返回单点 | `loop/audio/source/LoopingPcmExtractor.kt:49-54` |
| `trackOutput.sampleData/sampleMetadata` | `loop/audio/source/LoopingPcmExtractor.kt:108-109` |
| RingBandExtractor 高水位后必须等新 append | `loop/audio/source/RingBandExtractor.kt:132` |
| "seek 后 PTS 必须 >= 目标且不回退" | `loop/audio/source/RingBandReader.kt:89` |

### H. MediaSession / MediaController
| 调用 | 位置 |
|---|---|
| `MediaController` 连接/位置刷新/命令 | `ui/common/MusicViewModel.kt:273`, `:435-575`, `:607-644` |
| `MediaSessionService` 子类 + session 创建 | `service/MusicService.kt`(类定义与 `:118-137`, `:455-458`) |
| session 投影(通知栏/进度读媒体时间) | `service/LoopSessionProjection.kt:50-55` |
| 调试控制器 | `debug/DebugPlayerConnection.kt` |

### I. 音频焦点 / 音量
| 调用 | 位置 |
|---|---|
| `setAudioAttributes(...)`(handleAudioFocus 开关) | `di/MediaModule.kt:40-42`, `:98`;`ui/loop/PreviewPlayer.kt:598-609` |

### J. 线程 / Looper
| 调用 | 位置 |
|---|---|
| `Handler(Looper.getMainLooper())` | `service/MusicService.kt:326` |
| `Looper.myLooper()` 在 MediaPeriod 内 | `loop/audio/source/LoopingPcmMediaPeriod.kt:88` |
| 协调器主线程假设 | `service/LoopPlaybackCoordinator.kt:165`, `:859` |

## 12. 预核对:我们自己代码注释里的行为断言 vs 源码

> 这些不需要等 spec,先按源码判一遍。spec 落地后如果收录了同一断言,直接引用本节证据。

### 12.1 "outputStreamOffsetUs 可能先于 configure 被设置(探针实测 1e12)" → **CONFIRMED,且 1e12 有明确来源**

- 顺序:`MediaCodecRenderer.setOutputStreamInfo` 在 stream change 时立刻回调 `onOutputStreamOffsetUsChanged(streamOffsetUs)`
  → `MediaCodecAudioRenderer.onOutputStreamOffsetUsChanged` → `audioSink.setOutputStreamOffsetUs(...)`
  (`MediaCodecRenderer.java:2352-2357`、`MediaCodecAudioRenderer.java:886-889`);
  `configure` 则发生在之后第一次 `updateAudioSinkFormatIfNeeded`(`MediaCodecAudioRenderer.java:651-667`)。
  DecoderAudioRenderer 走 `onStreamChanged` → `setOutputStreamOffsetUs`(`DecoderAudioRenderer.java:715-736`)。
- 数值:首个 period 的 renderer offset 就是 `MediaPeriodQueue.INITIAL_RENDERER_POSITION_OFFSET_US = 1_000_000_000_000L`
  (=1e12,`MediaPeriodQueue.java:70`),由 `enqueueNextMediaPeriodHolder` 赋给第一个 holder(`MediaPeriodQueue.java:220-231`);
  后续 period 的 offset = 前一 period offset + 前 duration − 新 startPosition(`:224`)。
- 含义:`outputStreamOffsetUs` **不是常量**,也不是 0;它是"renderer 时间 − 媒体时间"的偏移,首个 period 恰好是 1e12。

### 12.2 "Player.replaceMediaItem 不会重建 MediaSource" → **条件成立(uri 不变时成立)**

- 只有 `canUpdateMediaSourcesWithMediaItems` 全部为 true 才走"只 updateMediaItem"分支
  (`ExoPlayerImpl.java:739-743` → `:2957-2970`)。
- ProgressiveMediaSource 的判据:uri 相同 + imageDurationMs 相同 + customCacheKey 相同
  (`ProgressiveMediaSource.java:373-381`)。`mediaId/tag/mediaMetadata` 变化不影响复用。
- 换 uri ⇒ 走 remove+add 重建(`ExoPlayerImpl.java:744-762`)。

### 12.3 "delegate 没吃完时必须复用同一个 ByteBuffer 实例重试" → **CONFIRMED**

`AudioSink.handleBuffer` 契约(`AudioSink.java:517-538`);DefaultAudioSink 用
`Assertions.checkArgument(inputBuffer == null || buffer == inputBuffer)` 强制(`DefaultAudioSink.java:954`)。

### 12.4 "consumed=false 时 position 也不一定前移" → **部分正确,需分路径**

- `drainOutputBuffer` 直接 `audioTrack.write(buffer, size, WRITE_NON_BLOCKING)`
  (`DefaultAudioSink.java:1966-1968`、`:1328`),AudioTrack 写入多少就推进 `buffer.position` 多少,
  **所以 consumed=false 时 position 通常已部分前移**(写入成功但未写完)。
- 真正常见的"完全不前移"只发生在早期返回:`mayHandleBuffer` 为 false(`:1020-1022`)、
  drain 未完成(`:1044-1048`、`:1068-1070`)、AudioTrack 未初始化(`:989-1002`)。
- 结论:用 `buffer.position` 增量做 tap 统计可行(我们 `SourceRenderersFactory.kt:156-165` 就是这么做的),
  但注释应改成"早期返回路径不前移,写入路径按写入量前移"。

### 12.5 "MediaController 的位置更新约 1s 才刷新一次" → **不准确(位置是本地外推的)**

- `MediaController.getCurrentPosition()`(`MediaController.java:930-933`)→ `MediaControllerImplBase.getCurrentPosition()`
  (`:618-626`)→ `MediaUtils.getUpdatedCurrentPositionMs`(`MediaUtils.java:229-261`):
  `positionMs + elapsedTimeMs * playbackSpeed`,并按 duration 截断。
- 即**播放中位置连续递增**(用 elapsedRealtime 外推),不是 1s 一跳;
  `pause` 后位置停在最后一次 PlayerInfo 的 `positionMs`(`MediaUtils.java:237-242`)。
- 真正"1s 一次"的是 PlayerInfo/事件推送;位置变化本身没有任何 callback(Player.Listener 只有
  `onPositionDiscontinuity`)。

### 12.6 "ExoPlayer 的 seek 会落到目标 ±十几毫秒" → **不是 ExoPlayer 的取整**

- 默认 `SeekParameters.DEFAULT = EXACT`(`SeekParameters.java:55`),progressive 源里
  `getAdjustedSeekPositionUs` 只在非 seekable 时强制 0,否则把请求位置原样交给
  `resolveSeekPositionUs`(`ProgressiveMediaPeriod.java:512-522`)。
- ±十几毫秒来自 MP3 帧对齐/编码器延迟(解出的首帧 PTS 与请求点不同),不是 media3 把 seek 目标取整。
  断言应写成"首帧 PTS 落在 [target-帧长, target+帧长]"而不是"seek 精确"。
