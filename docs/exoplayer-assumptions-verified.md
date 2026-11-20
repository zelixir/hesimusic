# media3 1.8.0 假定核验报告(任务 B)

> 对应 spec:`docs/exoplayer-assumptions-spec.md`(A-001…A-095,95 条)。
> 源码基线:androidx.media3 1.8.0 sources jar,解压于
> `e2e-artifacts/media3-src/extracted/{media3-common,media3-exoplayer,media3-extractor,media3-decoder,media3-session,media3-container,media3-datasource}-sources`。
> 下文所有 "Xxx.java:行号" 均指该基线文件,可用 `e2e-artifacts/media3-src/extracted/...` 直接复核。

## 统计

| 项目 | 数量 |
|---|---|
| 总条数 | 95 |
| CONFIRMED | 82 |
| DEVIATION | 7( A-016 / A-048 / A-062 / A-066 / A-067 / A-077 / A-085 ) |
| UNDEFINED | 6( A-025 / A-037 / A-049 / A-055 / A-091 / A-093 ) |
| 额外发现的漏项(A-NEW) | 14 |

补充说明:
- spec 里的 confidence 与"依据=我们 docs 的历史记录"一律**未采信**,每条都重新取证。
- 对"我们 docs 引用了行号"的条目(A-044/A-045/A-048/A-062/A-067 等),行号全部重查:部分行号对得上,部分**结论**对不上(见 DEVIATION)。
- 判定口径:CONFIRMED = 源码/接口文档明确支持该断言;DEVIATION = 源码明确反驳或机制归因错误;UNDEFINED = media3 没有承诺该行为(即使实现当下碰巧如此)。

## 判定方法

1. 接口契约读 Javadoc(如 Player.java / MediaPeriod.java / AudioSink.java);
2. 实现路径读调用点(ExoPlayerImpl / ExoPlayerImplInternal / MediaCodecRenderer / DefaultAudioSink / Masking* / MediaSourceList / MediaController*);
3. 每条至少给 1 处 file:line;涉及"顺序"的给调用链;
4. 无法从源码定论的,写 UNDEFINED 并给出"我们应当自己断言什么"。

---

## 1. 播放器生命周期(A-001…A-010)

| ID | 结论 | 源码证据(file:line) | 影响 | 需要的改动 |
|---|---|---|---|---|
| A-001 | CONFIRMED | 访问线程 = Builder 捕获的 Looper:ExoPlayer.java:446(looper = Util.getCurrentOrMainLooper())、ExoPlayerImpl.java:294;每次调用 verifyApplicationThread:ExoPlayerImpl.java:2906-2925。注意默认**只打 warning 不抛**(throwsWhenUsingWrongThread 默认 false,2920-2923) | 我们的"全部主线程"是充分条件(不是必要条件);违反时不保证抛异常,而是未定义行为 → 与我们的防御目标一致 | 无。建议把"错误线程只打 warning"写进注释,避免以后有人以为会抛而省掉串行化 |
| A-002 | CONFIRMED | prepare() 在 timeline 为空时 mask 成 ENDED:ExoPlayerImpl.java:534-536;内部 prepareInternal 同样:ExoPlayerImplInternal.java:962;空 timeline 下位置返回 maskingWindowPositionMs:ExoPlayerImpl.java:2011-2014。补充:空 timeline 上 seekTo 不会直接 ENDED,只记 pending(maskWindowPositionMsOrGetPeriodPositionUs:2698-2714、ExoPlayerImplInternal.java:1603-1605) | 与真机 86a6b1f 现象一致 | 无。可把"空 timeline + seek 也不报错"补进注释 |
| A-003 | CONFIRMED | ExoPlayerImpl.java:528-532(playbackState != IDLE 直接 return);实现里不存在"ENDED 下 prepare 会重启"的路径 | ENDED 下 prepare 无副作用,我们的"先 seek 再 prepare"成立 | 无 |
| A-004 | CONFIRMED | play() 语义 = setPlayWhenReady(true),且只在 READY 后恢复:Player.java:2502-2509;ExoPlayerImpl.java:812-817;内部 setPlayWhenReadyInternal 不改 state:ExoPlayerImplInternal.java:1063-1072 | ENDED 下只 play() 不会动 | 无 |
| A-005 | CONFIRMED | seekTo 把 READY/ENDED mask 成 BUFFERING:ExoPlayerImpl.java:912-915;内部 ENDED 时 forceBufferingState:ExoPlayerImplInternal.java:1641;随后 seek 后 prepare 因非 IDLE 而 no-op(A-003) | 与 Robolectric 断言一致 | 无 |
| A-006 | CONFIRMED | setMediaSourcesInternal 只在 state != IDLE 时 mask 成 BUFFERING:ExoPlayerImpl.java:2443-2452;内部 setMediaItemsInternal 不调用 prepare:ExoPlayerImplInternal.java:968-985 | IDLE 上换源后必须 prepare,我们的做法正确 | 无 |
| A-007 | CONFIRMED(断言本身成立) | 原地更新路径只换 timeline、位置不变:ExoPlayerImpl.java:2972-2991;重建路径按旧 period 位置重解析,找不到就回默认位置:ExoPlayerImpl.java:2686-2693、2484-2501 | 50ms 容差补齐 seek 是安全策略 | 无。可加不变量:重建路径后位置 == window.getDefaultPositionMs() |
| A-008 | CONFIRMED | setMediaSource(source, resetPosition) → setMediaSources(list, resetPosition) → resetToDefaultPosition:ExoPlayerImpl.java:607-614;resetPosition=false 时沿用 currentWindowIndex/currentPositionMs:2432-2435 | resetPosition=false 确实"保留位置";若当前 timeline 空则落到默认起点(maskWindowPositionMsOrGetPeriodPositionUs:2707-2712) | 无 |
| A-009 | CONFIRMED | release() → internalPlayer.release() 阻塞到内部释放完成(带超时):ExoPlayerImplInternal.java:590-598;内部 resetInternal 在所有 renderer 上 reset/disable、最后 releaseRenderers:ExoPlayerImplInternal.java:1858-1875 | release 返回后不会再有 handleBuffer;我们的 released/disabled 防御是冗余但安全 | 无 |
| A-010 | CONFIRMED | 状态只能由内部 setState 改:ExoPlayerImplInternal.java:934-946;应用侧 API 只 mask 本地 PlaybackInfo(ExoPlayerImpl.java:2650-2656) | 我们只读不写是唯一正确姿势 | 无 |

---

## 2. 换源语义(A-011…A-017)

| ID | 结论 | 源码证据(file:line) | 影响 | 需要的改动 |
|---|---|---|---|---|
| A-011 | CONFIRMED | replaceMediaItems 先问 canUpdateMediaSourcesWithMediaItems,true 则只 updateMediaItem、不重建:ExoPlayerImpl.java:739-743、2957-2970;MediaSourceList.updateMediaSourcesWithMediaItems 只调 updateMediaItem:MediaSourceList.java:265-273 | 00021 的根因判断成立:换 item 不等于换源 | 无 |
| A-012 | CONFIRMED | ProgressiveMediaSource 只比较 uri / imageDurationMs / customCacheKey:ProgressiveMediaSource.java:373-381;MaskingMediaSource 原样委托:MaskingMediaSource.java:80-83;tag 不参与 | 我们"只改 tag 会命中捷径"的判断正确 | 无 |
| A-013 | CONFIRMED | customCacheKey 用 Objects.equals 比较(ProgressiveMediaSource.java:380),不同即 false → createMediaSources 重建(ExoPlayerImpl.java:744-751) | cacheKey 规避有效 | 无 |
| A-014 | CONFIRMED | setMediaItems → createMediaSources 对每个 item 调一次工厂:ExoPlayerImpl.java:2028-2033;setMediaSourcesInternal 先清空全部 holder 再逐个新建:2418-2424 | 整表兜底能强制重建 | 无 |
| A-015 | CONFIRMED | 原地路径只动目标 index(MediaSourceList.java:265-273);重建路径在 toIndex 处 add 再 remove 原区间,其它 holder 不动:ExoPlayerImpl.java:751-752 | 队列其它项不被重建 | 无 |
| A-016 | **DEVIATION** | 重设相同队列仍会**新建** MediaSourceHolder(ExoPlayerImpl.java:2472-2481 每次 new holder),每个 holder 的 uid 都是 new Object():MediaSourceList.java:503-515,而 PlaylistTimeline 的 window uid = holder uid(PlaylistTimeline.java:107-110、168-173);evaluateMediaItemTransitionReason 在 window uid 不同时必然返回 transition(ExoPlayerImpl.java:2367-2380),且 timelineChanged 由结构 equals 判定(Timeline.java:1308-1350)。因此 setMediaItems 会触发 onMediaItemTransition(reason=PLAYLIST_CHANGED),不是"不触发" | 我们 docs 记录的"真机 40169 没有事件"实际应是"没有 AUTO/REPEAT 原因的事件"或被别处过滤;靠"等 transition"做初始化的逻辑会**提前**被触发而不是永不触发 | 监听里必须按 reason 过滤(只认 AUTO/REPEAT/SEEK;PLAYLIST_CHANGED 要丢弃),并把 settle 轮询的前提改成"currentMediaItem 由空变非空";最小复现:Robolectric 中 prepare 后 setMediaItems(同一个 List),断言 onMediaItemTransition 被调用且 reason==PLAYLIST_CHANGED |
| A-017 | CONFIRMED | canUpdateMediaItem 只看 uri/imageDurationMs/customCacheKey,无 mediaId:ProgressiveMediaSource.java:373-381 | mediaId 只是我们的身份标签 | 无 |

---

## 3. MediaSource / MediaPeriod 契约(A-018…A-033)

| ID | 结论 | 源码证据(file:line) | 影响 | 需要的改动 |
|---|---|---|---|---|
| A-018 | CONFIRMED(实现如此,文档相反;措辞需精确) | 默认 useLazyPreparation=true(ExoPlayerImpl.java:287 传入)。懒准备下 MaskingMediaSource.prepareSourceInternal 不建子源(MaskingMediaSource.java:98-103),子源要到 createPeriod 里才 prepare(MaskingMediaSource.java:106-124);而 MediaSourceList.createPeriod 先 enable 外层 masking、再 createPeriod:MediaSourceList.java:329-341,此时 CompositeMediaSource.enableInternal 遍历的 childSources 还是空的(CompositeMediaSource.java:68-72),之后也不会再触发 enableInternal。结论:**在子源侧,enable(caller) 在懒准备路径上不会被调用**;BaseMediaSource 之类可以在首次 prepareSource 时隐式置 enabled(BaseMediaSource.java:251-255),但自己直接实现 MediaSource 的源会一直是 enabled=false。接口文档要求 createPeriod "only if enabled"(MediaSource.java:542-552),与实现不一致 | 我们"不因 enable 时序抛异常"的宽松实现是对的;但凡是依赖 enable() 来初始化状态的逻辑都会失效 | 保留宽松;自研 MediaSource 必须把"首次 prepareSource"当作启用,禁止依赖 enable() 被调;注释按上面精确化 |
| A-019 | CONFIRMED | prepareChildSource 注册 caller 后立刻 prepareSource:MediaSourceList.java:466-475;同步回调 onSourceInfoRefreshed 会经 onPlaylistUpdateRequested 转成 handler 消息:ExoPlayerImplInternal.java:607-610,不会重入破坏状态 | 同步刷新允许,我们 prepareSource 内同步回调安全 | 无 |
| A-020 | CONFIRMED | 只有 period.durationUs 已知时才 clamp 到 duration-1:Timeline.java:1246-1252;isSeekable 来自 window(Timeline.getWindow),与 duration 无关 | TIME_UNSET + isDynamic + isSeekable 组合不会被钳位 | 无 |
| A-021 | CONFIRMED | EOS 只来自 sample stream:hasReadingPeriodFinishedReading 依赖 stream 的 end:ExoPlayerImplInternal.java:2952-2964;ProgressiveMediaPeriod 把 loadingFinished 传给队列读:ProgressiveMediaPeriod.java:548-549。never-END_OF_INPUT ⇒ loadingFinished 永不置位(ProgressiveMediaPeriod.java:668) | 循环不会被数据源"自动结束" | 无 |
| A-022 | CONFIRMED | getTotalBufferedDurationUs(nextLoadPositionUs) = max(0, nextLoad - pos):ExoPlayerImplInternal.java:3319-3327;END_OF_SOURCE(=Long.MIN_VALUE) 只会让 bufferedDuration 变 0,不会 ENDED;是否继续加载由 LoadControl 决定(3056-3110) | 返回 END_OF_SOURCE 安全,但会让 LoadControl 看到"0 缓冲",从而持续要求 continueLoading(这也是我们必须在 continueLoading 去重的原因) | 无。可补不变量:返回 END_OF_SOURCE 期间 continueLoading 仍被调用 |
| A-023 | CONFIRMED | READY 迁移用 loadingPeriod.getBufferedPositionUs() 算 bufferedDuration 再问 LoadControl:ExoPlayerImplInternal.java:2342-2371;DefaultLoadControl.shouldStartPlayback 比较 bufferForPlaybackUs:DefaultLoadControl.java:409-422。**注意默认值**:bufferForPlayback=1000ms、afterRebuffer=2000ms、min=max=50000ms(DefaultLoadControl.java:50-67,128-131),我们 docs 里的 2500 是错的 | "buffered - position 达阈值才起播"成立;我们用 4000/12000/1500/2500 覆盖是有效的 | 修正 docs 中"默认 2500"的说法(1.8.0 是 1000) |
| A-024 | CONFIRMED(有前提) | BUFFERING_MAXIMUM_INTERVAL_MS = usToMs(Renderer.DEFAULT_DURATION_TO_PROGRESS_US=10_000) = 10ms:ExoPlayerImplInternal.java:181-183、Renderer.java:155;READY 且不需进度时最长 1000ms(183) | "每 ~10ms 调一次"只在 BUFFERING(或 renderer 需要进度)时成立;READY 空闲时可能 1s 一次 | 去重逻辑保留;把注释改成"BUFFERING 下 ≤10ms,READY 下最长 1s" |
| A-025 | UNDEFINED(media3 未承诺) | onPrepared 会被转成 MSG_PERIOD_PREPARED(ExoPlayerImplInternal.java:614-617),handlePeriodPrepared → handleLoadingPeriodPrepared 末尾**主动** maybeContinueLoading(ExoPlayerImplInternal.java:2989-3016) | 我们"主动踢一次"是冗余的(等价于再触发一次同一门控),不会坏,但没有必要 | 降级为 debug 断言:prepared 之后 continueLoading 是否被调用;若已调用则删掉手动踢(见"可删除清单") |
| A-026 | CONFIRMED | MediaPeriod.prepare 的 positionUs 是"期望起始位置":MediaPeriod.java:70-74;播放器传入 info.startPositionUs:ExoPlayerImplInternal.java:2581-2584。注意被 Masking 时可被 override(MaskingMediaSource.java:232-252、MaskingMediaPeriod.java:144-151) | 我们必须按 positionUs 定位,这点正确;但"它一定等于用户 seek 目标"不成立(见 A-NEW-08) | 无 |
| A-027 | CONFIRMED | seekToUs 的返回值被直接采用:ExoPlayerImplInternal.java:1747-1751(periodPositionUs = mediaPeriod.seekToUs(periodPositionUs)) | 返回实际落点是对的 | 无 |
| A-028 | CONFIRMED(无后置条件) | discardBuffer 只有"丢弃已缓冲媒体"的语义,没有强制动作:MediaPeriod.java:148-157 | 我们无队列 → no-op 合法 | 无 |
| A-029 | CONFIRMED | TIME_UNSET = 无 discontinuity,且返回非 UNSET 后所有 stream 从关键帧开始:MediaPeriod.java:159-175 | 循环折返不上报正确 | 无 |
| A-030 | CONFIRMED | selectTracks 的 selections/streams 按 renderer 一维数组:MediaPeriod.java:111-146;播放器按 renderer 索引构造数组(ExoPlayerImplInternal.java:1213-1218) | 我们"只挂第一个非空槽"成立 | 无 |
| A-031 | CONFIRMED | 返回值含义 = tracks 被 enable 时的实际位置:MediaPeriod.java:137-139 | return positionUs 等价于不做调整 | 无 |
| A-032 | CONFIRMED | 每次 doSomeWork 对 playing period 调 maybeThrowPrepareError:ExoPlayerImplInternal.java:1382;IOException 会被转成播放错误 | 我们的 2s 超时上抛能脱离 BUFFERING | 无 |
| A-033 | CONFIRMED | releasePeriod 先在 source 上释放 period、再按"已移除且无活动 period"决定是否 releaseSource:MediaSourceList.java:349-357、477-486;releaseSource 到最后一个 caller 才整体释放:BaseMediaSource.java:285-296 | releasePeriod/releaseSource 时机符合我们释放解码器的假设 | 无。注意:stop→prepare 会重复 prepareSource/releaseSource(见 A-NEW-01) |

---

## 4. Extractor 契约(A-034…A-043)

| ID | 结论 | 源码证据(file:line) | 影响 | 需要的改动 |
|---|---|---|---|---|
| A-034 | CONFIRMED | ExtractingLoadable.load 的内层循环:先 loadCondition.block() 再 read,read 返回 CONTINUE 就立刻继续:ProgressiveMediaPeriod.java:1140-1153(循环体 1142-1152,无 sleep);间隔只在跨过 continueLoadingCheckIntervalBytes 时才回到播放线程:1147-1152 | 连续 CONTINUE 会占满 Loader 线程(不是主线程),所以必须自节流或阻塞等待;我们的 8ms 等待/4ms 节流是对症的 | 保留自节流;可加不变量:单位时间 read 调用次数上限(已有 Robolectric 用例) |
| A-035 | CONFIRMED | loadingFinished 只在 onLoadCompleted 置位:ProgressiveMediaPeriod.java:636-670(668);而 load() 只有在 extractor 返回 END_OF_INPUT 时才结束:1105、1140 | 不返回 END_OF_INPUT = 永不完成 = 可无限播 | 无 |
| A-036 | CONFIRMED(单候选时 sniff 不会被调) | BundledExtractorsAdapter.init:extractors 只有 1 个时直接采用、不调 sniff;否则逐个 sniff 取第一个 true:BundledExtractorsAdapter.java:75-90 | 我们只给一个候选,sniff=true 实际不会被调用 | 注释改为"单候选时 media3 不调用 sniff;sniff=true 只是保险" |
| A-037 | UNDEFINED(顺序无强制) | 硬约束只有两条:(1) endTracks 之后新 track 被丢弃并打 "Extractor added new track ... after finishing tracks":ProgressiveMediaPeriod.java:801-811;(2) prepare 需要 seekMap != null 且每个队列都有 format:ProgressiveMediaPeriod.java:837-845。seekMap 与 endTracks 的先后无要求(771-780 都是 post) | 我们的 track→format→seekMap→endTracks 顺序安全,但不是"必需顺序" | 保持实现;断言改为"track 只建一次 + endTracks 后无新 track + seekMap 非空" |
| A-038 | CONFIRMED | sampleMetadata 的 size 是"该 sample 数据字节数",且数据必须此前已经通过 sampleData 传入:TrackOutput.java:204-219 | "size 必须等于实际写入字节数"成立;写错会错位 | 无 |
| A-039 | CONFIRMED | duration 与 isSeekable 独立:ProgressiveMediaPeriod.setSeekMap 里 durationUs=seekMap.getDurationUs()(可为 TIME_UNSET)、isLive 另算:ProgressiveMediaPeriod.java:825-834;ProgressiveMediaSource 把二者分别喂 SinglePeriodTimeline:ProgressiveMediaSource.java:485-495 | TIME_UNSET + seekable 合法,拖拽仍被接受 | 无 |
| A-040 | CONFIRMED | 默认 SeekParameters.DEFAULT = EXACT:SeekParameters.java:55;progressive 的 getAdjustedSeekPositionUs 只在非 seekable 时强制 0,否则交给 resolveSeekPositionUs(EXACT 时原样返回入参):ProgressiveMediaPeriod.java:512-522 | (p,p) 不会被"对齐到关键帧";精确落点由我们自己的 seek 实现决定 | 无 |
| A-041 | CONFIRMED | Extractor.seek(position,timeUs):position 是字节偏移、timeUs 是目标时间;后续 read 的输入必须从该 position 提供:Extractor.java:126-137 | 只用 timeUs、忽略 position 是合法的(虚拟容器) | 无 |
| A-042 | CONFIRMED | open 返回 LENGTH_UNSET 只表示长度未知:ProgressiveMediaPeriod.java:1113-1116;onLengthKnown 才置 isLengthKnown(797-799);isLive = !isLengthKnown && duration==TIME_UNSET(828);read() 由 extractor 决定调用,不调就永远观察不到 EOF | 空 DataSource + 不读 input ⇒ 不 EOS,成立 | 无 |
| A-043 | CONFIRMED | Extractor.read 在 Loader 线程执行:ProgressiveMediaPeriod 构造 Loader("ProgressiveMediaPeriod"):ProgressiveMediaPeriod.java:218-219;Loader 的加载线程独立于播放线程 | 阻塞 8ms 不会卡主线程/音频线程 | 无。注意仍占 Loader 线程,别无限等待 |

---

## 5. AudioSink 契约(A-044…A-058)

| ID | 结论 | 源码证据(file:line) | 影响 | 需要的改动 |
|---|---|---|---|---|
| A-044 | CONFIRMED | offset 先到:setOutputStreamInfo 立刻回调 onOutputStreamOffsetUsChanged:MediaCodecRenderer.java:2352-2357 → MediaCodecAudioRenderer.java:886-889;configure 晚到(在 onOutputFormatChanged 里):MediaCodecAudioRenderer.java:651-667。补充:AudioSink.setOutputStreamOffsetUs 默认空实现(AudioSink.java:612),DefaultAudioSink 不覆写 → 对 delegate 只是信息 | "不要在 configure 里重置 offset"正确且必要(我们自己用该值做域换算) | 无 |
| A-045 | CONFIRMED | handleBuffer 的 PTS 是 renderer 时间(媒体时间+offset):renderer 原样传(MediaCodecAudioRenderer.java:830-832),DefaultAudioSink 用它做 startMediaTimeUs(DefaultAudioSink.java:1005-1008);ExoPlayerImplInternal 用"renderer 时间 - renderer offset"得 period 位置:ExoPlayerImplInternal.java:1265-1268、MediaPeriodHolder.java:143-154 | PTS 在 offset 域成立;写回 delegate 必须 +offset | 无(见 A-NEW-01:位置查询也必须在同一域) |
| A-046 | CONFIRMED | 未吃完时同一 ByteBuffer 必须继续提供:AudioSink.java:517-538;DefaultAudioSink 用 checkArgument(inputBuffer == null || buffer == inputBuffer) 强制:DefaultAudioSink.java:954 | pendingInput 同实例判断必要 | 无 |
| A-047 | CONFIRMED | 返回 true 只发生在 inputBuffer 无剩余:DefaultAudioSink.java:1096-1100 → position 已推进到 limit;写入通过 AudioTrack.write(ByteBuffer) 直接推进 position:DefaultAudioSink.java:1328、1966-1968 | 用 position 增量统计帧数成立(仅 PCM 路径) | 无 |
| A-048 | **DEVIATION**(机制描述错) | position 推进发生在写入:audioTrack.write(buffer, size, WRITE_NON_BLOCKING)(DefaultAudioSink.java:1966-1968、1328);"完全不前移"只出现在早期返回:mayHandleBuffer 拒绝(1020-1022)、drain 未完成(1044-1048、1068-1070)、AudioTrack 未初始化(989-1002) | "tap 必须在 delegate 之前写"的做法仍正确(delegate 可能把数据吃进 processor 后仍返回 false,且早退路径不推进),但"不保证移动 position / delta=0 不能证明没消费"的表述与源码不符,会误导后续优化 | 改注释为"写入路径按写入量推进 position;早期返回路径不推进。以 consumed 与 position 联合判断";保留早拷贝 |
| A-049 | UNDEFINED(未承诺) | 空 buffer 合法由实现兜住:DefaultAudioSink 对空 buffer 直接返回 true:DefaultAudioSink.java:1024-1030;契约未规定 renderer 是否会给空 buffer | "重放态吞掉空 buffer 并返回 true"是安全兜底 | 保留;可加调试计数断言"空 buffer 只在重放/暂停态出现" |
| A-050 | CONFIRMED | play/pause 权威在 renderer:onStarted → audioSink.play():MediaCodecAudioRenderer.java:701-706;onStopped → audioSink.pause():708-714;renderer start/stop 由 READY+playWhenReady 驱动:ExoPlayerImplInternal.java:1402-1411、1114 | "绝不自己 play"正确 | 无 |
| A-051 | CONFIRMED | outputStreamEnded 时每次 render() 都调 renderToEndOfStream():MediaCodecRenderer.java:867-882(879-881);processEndOfStream 里也直接调:2322-2326;render() 节奏 = doSomeWork 最大间隔(BUFFERING 10ms):ExoPlayerImplInternal.java:181-183 | 重放期靠它做泵成立(约 100Hz 仅在播放中) | 无。可加断言"重放期该调用频率 > 20Hz" |
| A-052 | CONFIRMED | renderer.isEnded() = super.isEnded() && audioSink.isEnded():MediaCodecAudioRenderer.java:754-757;DefaultAudioSink.isEnded 需要 handledEndOfStream && !hasPendingData:DefaultAudioSink.java:1424-1427;我们返回 false 即可不让流结束 | 循环态 isEnded=false 正确 | 无 |
| A-053 | CONFIRMED | 位置不可用返回 CURRENT_POSITION_NOT_SET(Long.MIN_VALUE):AudioSink.java:399-401;renderer 忽略该值并保持单调:MediaCodecAudioRenderer.java:1065-1073(非 discontinuity 时取 max);DefaultAudioSink 的取值:DefaultAudioSink.java:716-724 | "NOT_SET 时回落引擎游标"必要 | 无 |
| A-054 | CONFIRMED | 每个 seek 都 reset renderer 位置并触发 onPositionReset → audioSink.flush():ExoPlayerImplInternal.java:1765-1775 → MediaCodecAudioRenderer.java:691-699;disable/recreate 还会 onDisabled→flush(717-729) | 跨 seek 不比较 PTS 成立 | 无 |
| A-055 | UNDEFINED("每次边界必 reconfigure"无承诺) | 实际触发点是 format/stream 变化:bypass 下新格式首个样本调 onOutputFormatChanged:MediaCodecRenderer.java:2574-2586;configure 在该回调里:MediaCodecAudioRenderer.java:651-667;另外 onDisabled 后下一个 buffer 会重新 configure(audioTrackNeedsConfigure) | 同格式跨 item 是否 configure 取决于 SampleQueue 是否重发 format,文档未承诺 | 不要依赖"边界必 configure",当"可能发生";断言 configure 计数 >= 窗口切分次数,且每次 configure 后首帧 PTS 连续 |
| A-056 | CONFIRMED | handleDiscontinuity 调用点只有:stream 变化(onProcessedStreamChange):MediaCodecAudioRenderer.java:789-793;decode-only buffer:821-827;DecoderAudioRenderer 首样本:DecoderAudioRenderer.java:515-516。onPositionReset 里只 flush、不调它:691-699 | seek 后不保证通知;用输入 PTS 跳变识别是对的 | 无 |
| A-057 | CONFIRMED | onDisabled:flush + audioSinkNeedsReset=true:MediaCodecAudioRenderer.java:716-730;onReset 时才真正 sink.reset():732-744;之后重新工作必须再次 configure(下一个 format 事件) | reset 后需要 configure 成立 | 无 |
| A-058 | CONFIRMED | onRelease → audioSink.release():MediaCodecAudioRenderer.java:746-752;其后 renderer 不再被使用(内部 release 顺序:ExoPlayerImplInternal.java:1858-1875) | release 是最后一个 sink 回调 | 无 |

---

## 6. 渲染器 / 时钟(A-059…A-063)

| ID | 结论 | 源码证据(file:line) | 影响 | 需要的改动 |
|---|---|---|---|---|
| A-059 | CONFIRMED | 播放位置来自 renderer 媒体时钟:DefaultMediaClock.getPositionUs 取 rendererClock.getPositionUs():DefaultMediaClock.java:134-139;ExoPlayerImplInternal 用该时间减 renderer offset 得 period 位置:ExoPlayerImplInternal.java:1264-1268 | "位置由渲染进度驱动"成立;投影层必要 | 无 |
| A-060 | CONFIRMED | 只有 READY 且 shouldPlayWhenReady 才 startRenderers:ExoPlayerImplInternal.java:1402-1411;pause 时 setPlayWhenReadyInternal 会 stopRenderers:1114(定义 1221-1226) | 暂停后 sink 不再被喂数据(最多收尾),paused-but-sink-wrote 不变量有效 | 无 |
| A-061 | CONFIRMED | shouldUseBypass 最终问 audioSink.supportsFormat:MediaCodecAudioRenderer.java:458-470;DefaultAudioSink 对 PCM16 返回 SUPPORTED_DIRECTLY:DefaultAudioSink.java:687-700 → 不创建 MediaCodec | PCM16 audio/raw 走 bypass、PTS 不被解码器改写,成立 | 无 |
| A-062 | **DEVIATION**(归因错误) | 1e12 不是 clipping 造成,而是所有源的初始 renderer offset:MediaPeriodQueue.INITIAL_RENDERER_POSITION_OFFSET_US=1_000_000_000_000L:MediaPeriodQueue.java:70,赋给首个 holder:220-231。clipping 的真实效果:window.positionInFirstPeriodUs += startUs、window.durationUs = 剪后时长:ClippingMediaSource.java:490-509;period 内位置整体减 startUs:511-520 | 真机 logcat 的 1000008363999 ≈ 1e12 + 8.364s,是基础 offset + 位置,不是 clip 特有;我们"clip 把 offset 变成 -1e12/1e12+真实位置"的结论错误,"放弃 clip"的技术理由需重写(真实理由:clip 改 window 时长/默认位置并把位置钳在 [0,end-start]) | 改写 PreviewPlayer.kt:865-877 注释与 docs 归因;新增断言:sink 收到的 offset == INITIAL_RENDERER_POSITION_OFFSET_US + period.getPositionInWindowUs() |
| A-063 | CONFIRMED | renderer.isReady 依赖 isSourceReady()(stream.isReady)与输出缓冲:MediaCodecRenderer.java:1920-1927;播放器只有 renderersAllowPlayback 时才转 READY:ExoPlayerImplInternal.java:1402-1404 | isReady=false ⇒ 不消费、停在 BUFFERING,成立 | 无 |
---

## 7. 会话层(A-064…A-071)

| ID | 结论 | 源码证据(file:line) | 影响 | 需要的改动 |
|---|---|---|---|---|
| A-064 | CONFIRMED | MediaController 每个 public 方法先 verifyApplicationThread,用的是 checkState(**直接抛 IllegalStateException**,与 ExoPlayer 的 warning 不同):MediaController.java:2112-2114、202-204 | 我们一律 mainHandler.post 正确;违反会抛,不是静默 | 无 |
| A-065 | CONFIRMED | buildAsync 返回 holder,连接完成是异步的:MediaController.java:374-393;未连接时读接口返回默认值(getDuration=TIME_UNSET、getCurrentPosition=0、getPlaybackState=IDLE):906-939;写命令 no-op:742-810 | future 完成前"读无意义但安全";我们的空安全分支正确 | 无 |
| A-066 | **DEVIATION**(数字与机制) | 周期位置更新默认 **3000ms**,不是 1s:MediaSessionImpl.java:109(DEFAULT_SESSION_POSITION_UPDATE_DELAY_MS=3_000)、263、1356-1364(postDelayed,且仅 isPlaying/isLoading);外推用真实 speed 与 elapsedRealtime:MediaUtils.java:229-261(250-259),不是 speed=1;PlayerInfo 另由事件驱动更新 | 我们"约 1s 刷新 + speed=1 外推"在数值与机制上都不对;2s 刷新窗口小于 3s 周期,靠它兜住周期回推**不够**。但 controller 位置本身是连续外推的,UI 卡顿主要来自事件而非位置 | 改注释为"位置由 controller 本地外推(按当前 speed),周期事件 3s";刷新窗口改 >3s 或改事件驱动;断言:两次 PlayerInfo 之间 getCurrentPosition 单调且斜率≈speed |
| A-067 | **DEVIATION**(只对了一半) | seekTo 在 controller 侧**立即**乐观更新(masked PlayerInfo):MediaControllerImplBase.java:2258-2321(2265-2268 把 state mask 成 BUFFERING;2299 maskTimelineAndPositionInfo;2314-2321 updatePlayerInfo) | "seekTo 之后立刻读到旧位置"不成立(读到的是**新位置/新状态**,只是尚未到会话侧)。pendingSeek 覆盖层若以"位置还是旧值"为前提,注释与判据都要改;真正要兜的是 session 侧 clamp/调整后的最终值 | 改写 MusicViewModel.kt:546-554 与 PlayerScreen.kt:189-205 的注释;覆盖层判据改为"等最终值 == 请求值(或超时)",而不是"等旧变新" |
| A-068 | CONFIRMED | MediaSession 直接包传入的 Player 实例:MediaSessionImpl.java:255-267(PlayerWrapper(player)) | controller 读到的就是投影层口径 | 无 |
| A-069 | CONFIRMED | setSessionExtras 会向所有已连接 controller 派发 onSessionExtrasChanged:MediaSessionImpl.java:656-660;定向版本同:662-670 | extras 能跨 controller 传播,audioSessionId 通道可用 | 无 |
| A-070 | CONFIRMED | isConnected 就是 iSession != null:MediaControllerImplBase.java:316-319;断开时 release 路径会 onDisconnected:MediaController.java:682-687。没有"空闲超时断连"机制 | "只看链路"判据正确 | 无 |
| A-071 | CONFIRMED | release 幂等(released 标志),文档明确"之后不得使用":MediaController.java:650-688 | release 后重建 controller 的规则正确;复用是未定义行为 | 无 |

---

## 8. 回调(A-072…A-078)

| ID | 结论 | 源码证据(file:line) | 影响 | 需要的改动 |
|---|---|---|---|---|
| A-072 | CONFIRMED | ListenerSet 绑定 applicationLooper,事件在应用线程 flush:ExoPlayerImpl.java:297-301、2242(listeners.flushEvents());Player.Listener 文档声明在 application thread 回调 | 回调里直接读 player / 写 StateFlow 安全 | 无 |
| A-073 | CONFIRMED | reason 取值:window uid 变化时按 AUTO(自动推进)/SEEK(seek)/PLAYLIST_CHANGED(列表变化) 分派:ExoPlayerImpl.java:2367-2396 | 用 reason 区分手动/自动切歌可行 | 无 |
| A-074 | CONFIRMED | 同一 window 内 REPEAT:positionDiscontinuity=AUTO_TRANSITION 且 windowSequenceNumber 递增 → REASON_REPEAT:ExoPlayerImpl.java:2382-2390 | REPEAT_MODE_ONE 会产生 reason=REPEAT 的 transition | 无 |
| A-075 | CONFIRMED | setRepeatMode/setShuffleModeEnabled 同步改本地 PlaybackInfo 并在结尾 flushEvents:ExoPlayerImpl.java:824-841、2242;监听器内再写属性即嵌套回调 | "先清意图再写属性"必要 | 无 |
| A-076 | CONFIRMED | timelineChanged 时 queueEvent(onTimelineChanged):ExoPlayerImpl.java:2104、2148-2152 | 用 onTimelineChanged 对账队列可行 | 无 |
| A-077 | **DEVIATION**("包括刚创建时"不成立) | onPlaybackStateChanged 只在 playbackStateChanged 时入队:ExoPlayerImpl.java:2138-2140、2209-2213;构造期不发事件,初始 IDLE 不回调;IDLE 事件只在 stop()/错误重置后出现:ExoPlayerImplInternal.java:1855-1856 | "IDLE 分支是死代码"判断错:stop() 后确实会回调 IDLE;但"创建时也回调"不成立,靠首个回调做初始化会漏 | 保留 IDLE 分支;初始化改主动读状态;注释改为"IDLE 在 stop() 后回调,创建时不回调" |
| A-078 | CONFIRMED | audioSessionId 变化会派发:ExoPlayerImpl.java:3008-3014;设置入口:1510-1533 | 用它绑音效可行 | 无 |
---

## 9. 队列(A-079…A-087)

| ID | 结论 | 源码证据(file:line) | 影响 | 需要的改动 |
|---|---|---|---|---|
| A-079 | CONFIRMED | currentMediaItemIndex 是 window(列表)索引:BasePlayer.java:300-306;shuffle 只影响 next/prev 的选取:BasePlayer.java:263-270 | 用 index 定位队列副本可靠 | 无 |
| A-080 | CONFIRMED | setShuffleModeEnabled 只改 shuffleOrder,不动当前项/位置:ExoPlayerImplInternal.java:1167-1180 | "先 seek 到目标歌、最后开 shuffle"的恢复顺序安全 | 无 |
| A-081 | CONFIRMED | 三参 setMediaItems(items,startIndex,positionMs) → setMediaSources(startIndex, positionMs) → 非 reset 分支:ExoPlayerImpl.java:616-622、2429-2440 | 第三参是"项内起始位置",成立 | 无 |
| A-082 | CONFIRMED | addMediaItems 在 index 处插入,当前项位置按旧 period 解析保留:ExoPlayerImplInternal.java:2484-2501、ExoPlayerImpl.java:2686-2693 | 插入不打断当前播放 | 无 |
| A-083 | CONFIRMED | removeMediaItems 收敛索引/位置:ExoPlayerImpl.java:671-691;删到尾部会把状态 mask 成 ENDED:2518-2527;删当前项时位置重解析到相邻项:2503-2530 | "删当前项会打断、删别的不会"成立 | 无 |
| A-084 | CONFIRMED | seekToDefaultPosition(index) → seekTo(index, C.TIME_UNSET) → window 默认位置:BasePlayer.java:129-136、Timeline.java:1231-1235 | 用它离开 ENDED/回到起点成立 | 无 |
| A-085 | **DEVIATION** | getNextMediaItemIndex 用 getRepeatModeForNavigation(),它只把 REPEAT_ONE 降为 OFF、保留 REPEAT_ALL:BasePlayer.java:263-270、394-397。因此 repeat=ALL 时 hasNextMediaItem() 在最后一项仍返回 true(会绕回);repeat=OFF 时 seekToNext 走 ignoreSeek:BasePlayer.java:190-204 | "hasNext 不把 repeat=ALL 绕回算有下一项"的判断错:repeat=ALL 下手写绕回分支不可达(播放器自己会绕);repeat=OFF 时才有意义 | 把绕回分支限定在 repeat != ALL,或只依赖 seekToNext + 自己判 ENDED;断言:repeat=ALL 时 hasNextMediaItem()==true |
| A-086 | CONFIRMED | getNextWindowIndex 传入 shuffleModeEnabled,按 shuffle 顺序选:BasePlayer.java:263-270 | shuffle 下 seekToNext 不是 index+1,成立 | 无(继续避免手算 index) |
| A-087 | CONFIRMED | mediaId 不参与加载/去重决策(canUpdateMediaItem 也不看它):ProgressiveMediaSource.java:373-381 | mediaId 只是我们的身份标签 | 无 |

---

## 10. 时间单位(A-088…A-095)

| ID | 结论 | 源码证据(file:line) | 影响 | 需要的改动 |
|---|---|---|---|---|
| A-088 | CONFIRMED | Player 的时间参数/返回都是 ms:Player.java 对应 Javadoc;边界换算用 Util.usToMs:ExoPlayerImpl.java:1118-1121 | ms 口径成立 | 无 |
| A-089 | CONFIRMED | AudioSink.handleBuffer 的 presentationTimeUs 是微秒:AudioSink.java:528-537;TrackOutput.sampleMetadata 的 timeUs 是微秒:TrackOutput.java:210 | µs 口径成立 | 无 |
| A-090 | CONFIRMED | usToMs = us/1000(整除截断,保留 TIME_UNSET/TIME_END_OF_SOURCE):Util.java:1612-1614;msToUs = ms*1000:1624-1626 | floor 截断与 media3 一致 | 无 |
| A-091 | UNDEFINED | media3 只规定 PTS 单位是 µs(AudioSink.java:528-537、TrackOutput.java:210),不规定 extractor 内部的帧↔µs 取整方向 | 我们的 ceil/floor 约定是自己的不变量,没有源码背书 | 自己断言:往返 <1 帧;三圈 PTS 逐帧相同 |
| A-092 | CONFIRMED | TIME_UNSET = Long.MIN_VALUE+1(负数)、TIME_END_OF_SOURCE = Long.MIN_VALUE:C.java:55、61 | "duration<0 ⇒ 未知"成立,但 TIME_END_OF_SOURCE 也是负数 | 显式区分两个负值(A-NEW-05) |
| A-093 | UNDEFINED | media3 只把 TIME_UNSET 当未知;0 就是长度为 0 的 window(SinglePeriodTimeline 允许 0),没有额外"不可用"语义 | "0 = 不投影/不可用"是我们自己加的约定 | 断言:duration>=0 按真实值 clamp;TIME_UNSET 走投影/数据库兜底 |
| A-094 | CONFIRMED | currentPosition 与 bufferedPosition 都是当前项内 ms:ExoPlayerImpl.java:1106-1138、1287-1289 | 同单位同坐标系成立 | 缓冲倒退的 max 兜底仍需保留 |
| A-095 | CONFIRMED | 1e12 来自 INITIAL_RENDERER_POSITION_OFFSET_US:MediaPeriodQueue.java:70,与媒体时间相加远小于 Long 上限 | 不做饱和保护安全 | 无 |
---

## 11. 漏项(A-NEW):我们依赖但 spec 没写的 media3 行为

| ID | 结论 | 源码证据(file:line) | 为什么重要 | 建议的不变量/改动 |
|---|---|---|---|---|
| A-NEW-01 | sink 的位置查询必须在 renderer 时间域 | getCurrentPositionUs 被 renderer 直接采用:MediaCodecAudioRenderer.java:1065-1073;ExoPlayerImplInternal 再减 renderer offset 得 period 位置:ExoPlayerImplInternal.java:1264-1268、MediaPeriodHolder.java:143-154;DefaultAudioSink 也在同一域:DefaultAudioSink.java:716-724 | spec 只写了 PTS 在 offset 域(A-045),没写位置查询也在同一域。我们的 toMediaPositionUs 用"raw<offset 就不减"的启发式,一旦 delegate 返回媒体域值就静默错 1e12 | 断言:返回值的域与最近一次 handleBuffer 的 PTS 域一致;禁止启发式猜域 |
| A-NEW-02 | prepare() 会清空 playbackError | ExoPlayerImpl.java:533 | 用 prepare 重试时 getPlayerError() 会变 null,错误态观察会漏 | 断言:错误后 prepare,onPlayerErrorChanged(null) 必先到 |
| A-NEW-03 | seek 到"当前 ms 位置"是 no-op 且无 discontinuity | ExoPlayerImplInternal.java:1628-1634 | pendingSeek 覆盖层若等 onPositionDiscontinuity 收敛,会永远等不到 | 逻辑:seek 后若 100ms 内无 discontinuity 且位置已等于目标,视为成功 |
| A-NEW-04 | setMediaSources 在 IDLE 上不 mask 成 BUFFERING | ExoPlayerImpl.java:2443-2452 | 与 A-006 同源但独立:换源后 state 仍 IDLE,直到 prepare | 断言:IDLE 上换源后 playbackState==IDLE |
| A-NEW-05 | TIME_END_OF_SOURCE 与 TIME_UNSET 都是负数 | C.java:55、61;Util.usToMs 原样保留两者:Util.java:1612-1614 | 只判 <0 会把 END_OF_SOURCE 当"未知时长" | 显式判 == C.TIME_UNSET / == C.TIME_END_OF_SOURCE |
| A-NEW-06 | MediaPeriod.Callback 文档要求 playback 线程,但实现转成 handler 消息 | 文档:MediaPeriod.java:33-36;实现:onPrepared/onContinueLoadingRequested 都 sendToTarget:ExoPlayerImplInternal.java:614-621 | 跨线程回调"能工作"是容错不是契约;CallbackDispatcher 不能借此省掉串行化 | 断言:回调线程 == 播放线程,或文档写明"只保证串行" |
| A-NEW-07 | MediaSource.maybeThrowSourceInfoRefreshError 在播放路径没有调用者 | 全模块唯一调用点是 MaskingMediaPeriod.maybeThrowPrepareError:MaskingMediaPeriod.java:153-170;ExoPlayerImplInternal/MediaSourceList 均不调 | 我们在该方法里抛的错不会被播放器看到 → 会一直 BUFFERING | 错误必须同时/改为从 MediaPeriod.maybeThrowPrepareError 抛;断言:注入错误后 3s 内 onPlayerError 必到 |
| A-NEW-08 | Masking 会 override prepare 位置 | MaskingMediaSource.java:232-252(overridePreparePositionUs);MaskingMediaPeriod.java:144-151、177-190 | A-026 的 positionUs 可能不等于用户 seek 目标(被改成 timeline 默认起点或 clamp 到 duration-1) | 断言:prepare positionUs 在 [0, duration-1] 内,且与"窗口默认起点/请求位置"之一相符 |
| A-NEW-09 | 同 format 的 configure 可能重复到达且不清空 delegate 状态 | DefaultAudioSink.configure 在 pipeline 等价时复用旧 pipeline:DefaultAudioSink.java:755-759;configure 由 format 事件驱动:MediaCodecAudioRenderer.java:599-667 | 我们的 configure 里无条件清 pending/清 bias,若重复 configure 会丢正在重放的数据 | 断言:configure 若不伴随 flush,则 pending 重放必须继续(或显式记录 configure 即断) |
| A-NEW-10 | float 输出开关影响 bypass 判定与重采样 | getFormatSupport:PCM_FLOAT 仅当 enableFloatOutput 时 SUPPORTED_DIRECTLY,否则 WITH_TRANSCODING:DefaultAudioSink.java:687-700;转码管线:740-753 | 我们两个 RenderersFactory 一个开 float 一个关,行为不同(是否经过 audio processor),差异会被误归因到环回逻辑 | 断言:两种模式的 bypass 与否写入日志/不变量 |
| A-NEW-11 | Player.Commands 白名单会静默吞命令 | controller 侧 isPlayerCommandAvailable 不通过就 return 并打 "Controller isn't allowed to call command=":MediaControllerImplBase.java:3144-3150;可用命令来自 ConnectionResult:MediaSession.java:1930-1944 | 我们 buildUpon().add(NEXT/PREVIOUS) 若漏项,controller.seekToNext() 静默无效 | 断言:连接后 availableCommands 含期望集合,启动时校验并 Log.e |
| A-NEW-12 | 前台服务/通知的默认行为与超时 | 默认 onUpdateNotification 走 MediaNotificationManager,播放时进前台、否则退后台:MediaSessionService.java:646-679;FOREGROUND_SERVICE 权限(targetSdk>=28):665-667;可配 setForegroundServiceTimeoutMs:524、setShowNotificationForIdlePlayer:540;onTaskRemoved 默认暂停并 stopSelf:612-619 | 附录 C.4 的空白;影响"暂停后服务被回收/通知消失" | 断言:暂停后 N 秒内通知仍在(或按配置的 timeout);任务移除后 onDestroy 必到 |
| A-NEW-13 | MediaSourceEventListener 不派发只影响分析,不影响正确性 | MediaSourceList 为每个 child 注册并传入 listener:MediaSourceList.java:466-475;事件消费者是 AnalyticsListener/EventLogger,加载重试在 Loader/LoadErrorHandlingPolicy 内部 | 附录 C.3 的空白:不派发不会导致重试/加载异常,只是调试可见性缺失 | 非必需;建议 debug 构建补派发,让 event log 对齐 |
| A-NEW-14 | 音频焦点:焦点丢失真的会 pause,duck 只改音量 | 仅 USAGE_MEDIA/USAGE_GAME 自动管理焦点,否则 checkArgument 抛:AudioFocusManager.java:165-173;LOSS → PLAYER_COMMAND_DO_NOT_PLAY、LOSS_TRANSIENT → WAIT_FOR_CALLBACK、CAN_DUCK → 0.2 音量乘数:365-388、123、346-363;执行路径:ExoPlayerImplInternal.java:1036-1041、653-659 | 附录 C.6 的空白:我们"系统 pause"的说法成立,细节是"命令式暂停 + suppression reason",不是系统直接 pause | 断言:焦点丢失后 playWhenReady=false 且 getPlaybackSuppressionReason()==TRANSIENT_AUDIO_FOCUS_LOSS |
---

## 12. 三张清单

### 12.1 必须改代码的 DEVIATION(按用户可见风险排序)

| 序 | ID | 我们代码位置 | 用户可见后果 | 最小修复 | 最小复现 |
|---|---|---|---|---|---|
| 1 | A-067 | MusicViewModel.kt:546-554;PlayerScreen.kt:189-205 | seek 后位置回弹/覆盖层判据错;松手瞬间 UI 可能"闪一下再跳" | 覆盖层判据改为"等最终值 == 请求值(或超时)",不要假设"读到旧值" | Robolectric/仪器:controller.seekTo(X) 后立刻 getCurrentPosition(),断言 == X(而非旧值) |
| 2 | A-066 | MusicViewModel.kt:265-308;PlayerScreen.kt:160-205 | 2s 刷新窗口短于 3s 周期 → "跨过 B 冲出区间后回落"仍会出现 | 刷新窗口改 >3s,或改为"位置越界即折返 + 事件驱动",注释按实际机制改写 | 单元测:注入 eventTimeMs 为 3s 前的 PlayerInfo,断言 getCurrentPosition 按 speed 外推 |
| 3 | A-062 | PreviewPlayer.kt:865-877(注释与 reasons);docs/loop-v3-final-report.md:39 | 把 1e12 归因给 clip,会掩盖真正的 offset 处理 bug(例如 clip 下 window.duration/默认位置变化没处理) | 注释/文档改写为"1e12 是初始 renderer offset;clip 改 window 时长/默认位置并把位置钳在 [0,end-start]" | 单测:构造 clip item,断言 sink 收到的 offset == 1e12 + period.positionInWindowUs,且 position 被钳在 [0, clipLen] |
| 4 | A-016 | LoopPlaybackCoordinator.kt:456-492;113-116 | 依赖"重设队列不触发 transition"的 settle 逻辑会提前被 PLAYLIST_CHANGED 触发,可能重复恢复/误判切歌 | 监听里只认 AUTO/REPEAT/SEEK;PLAYLIST_CHANGED 单独处理(或忽略);settle 前提改为"currentMediaItem 由空变非空" | Robolectric:prepare 后 setMediaItems(同一个 List),断言 onMediaItemTransition 触发且 reason==PLAYLIST_CHANGED |
| 5 | A-048 | SourceRenderersFactory.kt:20-22、153-165 | 注释误导后续优化(以为 delegate 不推进 position,可能删掉必要的早拷贝或错误统计) | 注释改为"写入路径按写入量推进;早期返回不推进";保留早拷贝 | 单测:delegate 返回 false 且 position 前进 100 字节时,统计仍按 100 计 |
| 6 | A-077 | MusicService.kt:294-297;LoopPlaybackCoordinator.kt:137-140 | 依赖"创建时也会收到 IDLE 回调"做初始化会漏(实际只在 stop 后回调) | 初始化主动读 playbackState;注释改写;IDLE 分支保留(stop 后确实需要) | 单测:构造 ExoPlayer 后不触发任何操作,断言监听器未收到 onPlaybackStateChanged |
| 7 | A-085 | MusicService.kt:118-129;MusicViewModel.kt:521-544 | repeat=ALL 下手写"尾/头绕回"分支不可达(播放器自己绕);repeat=OFF 时才需要。功能不受影响,但代码语义错 | 绕回分支限定 repeat != ALL,或统一改为"seekToNext + ENDED 判定" | 单测:repeat=ALL 且 index==count-1 时,断言 hasNextMediaItem()==true |

### 12.2 需要加运行时断言的 UNDEFINED(可复用 /debug/invariant 机制)

> 已接线：12 条全部有 code / 纯判定器 / JVM 单测 / POST /debug/invariant 注入。
> 逐条触发条件、注入命令与用户可见后果见 `docs/runtime-invariants-undefined.md`。
> code 与判定器在 `app/src/main/java/com/zjr/hesimusic/core/invariants/AssumptionInvariants.kt`。

| ID | 断言内容 | 触发点 | 失败含义 |
|---|---|---|---|
| UNDEFINED A-025 | prepared 之后 continueLoading 是否**已经**被 media3 调过(我们在 handlePeriodPrepared 之后 1 帧内计数) | MediaPeriod.onPrepared 回调后 | 若从未被调,说明"主动踢"是必要的;若已调,可删掉我们的踢 |
| UNDEFINED A-037 | extractor.init 内:track 只建一次、seekMap 在 endTracks 前给出、endTracks 后无新 track | LoopingPcmExtractor/RingBandExtractor | 出现 "added new track after finishing tracks" 即数据被静默丢弃 |
| UNDEFINED A-049 | 空 buffer(remaining < bytesPerFrame)只在重放/暂停态出现 | LoopAudioSink.handleBuffer 开头 | 正常播放态出现空 buffer 说明源侧产出异常 |
| UNDEFINED A-055 | configure 次数与窗口切分次数关系 + 每次 configure 后首帧 PTS 连续 | LoopAudioSink.configure | configure 未按预期发生(或 PTS 断) → 环回锚点错 |
| UNDEFINED A-091 | 帧↔µs 往返误差 <1 帧;连续三圈 A/B 的帧号差恒定 | LoopingPcmTimeline / 环回 seam | 出现漂移说明取整不一致,长期会累积 |
| UNDEFINED A-093 | duration 三态(-1/0/正)在 UI 的 clamp/投影分支符合预期 | LoopSessionProjection.durationUs / MusicViewModel | 0 被当未知或被 clamp 到末帧 |
| A-NEW-01 | sink 返回位置的域与最近 handleBuffer 的 PTS 域一致 | LoopAudioSink.getCurrentPositionUs | 域混用 → ExoPlayer 位置错 1e12 |
| A-NEW-03 | seek 到同 ms 位置时,不等待 discontinuity(超时视为成功) | pendingSeek 收敛逻辑 | 覆盖层卡死/位置不更新 |
| A-NEW-07 | 注入 prepare 错误后 3s 内 onPlayerError 必到 | 错误路径 | 只从 source 抛错会无限 BUFFERING |
| A-NEW-09 | configure 若未伴随 flush,重放 pending 必须继续 | LoopAudioSink.configure | 重复 configure 丢重放数据 |
| A-NEW-11 | 连接后 controller.availableCommands 含期望集合 | MediaController 连接完成 | 命令被静默吞(只有日志 warning) |
| A-NEW-12 | 暂停后 N 秒内通知仍在(按配置 timeout) | MediaSessionService | 通知提前消失/服务被回收 |

### 12.3 可以直接删除/简化的过时假定

| 对象 | 位置 | 处理 |
|---|---|---|
| "onPrepared 之后必须主动踢一次" | LoopingPcmMediaPeriod.kt:414-422 | media3 已在 handleLoadingPeriodPrepared 末尾调 maybeContinueLoading(ExoPlayerImplInternal.java:2989-3016)。保留无害,但降级为 debug 断言;若确认无路径需要,直接删 |
| "sniff=true 保证被采用" | LoopingPcmExtractor.kt:58、RingBandExtractor.kt:66 | 单候选时 media3 不调 sniff(BundledExtractorsAdapter.java:75-90)。保留代码,注释降级为"保险" |
| "consumed=false ⇒ position 没前移" | SourceRenderersFactory.kt:20-22 | 改写为"写入路径按量前移;早期返回不前移"(A-048) |
| "clip 让 offset 变 1e12" | PreviewPlayer.kt:865-877;docs/loop-v3-final-report.md:39 | 改写为"1e12 是初始 renderer offset;clip 只改 window 位置/时长"(A-062) |
| "MediaSession PlaybackState 约 1s 刷新、按 speed=1 外推" | MusicViewModel.kt:300-303 | 改为"周期位置事件 3s;位置按当前 speed 本地外推"(A-066) |
| "seekTo 之后立刻读还是旧位置" | MusicViewModel.kt:550-552;PlayerScreen.kt:161-164 | 改为"controller 立即乐观 mask;要等的是最终值/超时"(A-067) |
| "默认 bufferForPlaybackMs=2500" | docs/loop-data-source-design.md:583-599 | 1.8.0 实际 1000ms(after rebuffer 2000ms):DefaultLoadControl.java:61-67 |
| "重设队列不会触发 transition" | LoopPlaybackCoordinator.kt:459-463 | 见 A-016;PLAYLIST_CHANGED 会触发,不能当"无事件" |
---

## 13. 附录 B(10 条高风险项)速答

| 序 | 条目 | 结论 | 一句话结论 + 关键证据 |
|---|---|---|---|
| 1 | A-003/A-004/A-005 | CONFIRMED | prepare 只在 IDLE 生效(ExoPlayerImpl.java:528-532);play 只改 playWhenReady(Player.java:2502-2509);seek 把 ENDED mask 成 BUFFERING(ExoPlayerImpl.java:912-915、ExoPlayerImplInternal.java:1641)。Robolectric 断言与 1.8.0 实现一致,不是"只证明当时版本" |
| 2 | A-011/A-012/A-013 | CONFIRMED | canUpdateMediaItem 只比 uri/imageDurationMs/customCacheKey(ProgressiveMediaSource.java:373-381),不含 tag/mediaId;customCacheKey 规避**必要** |
| 3 | A-018 | CONFIRMED(但属实现细节) | 接口文档要求 createPeriod 只在 enabled 后(MediaSource.java:542-552),而默认懒准备路径实际先 createPeriod 后 enable(MaskingMediaSource.java:106-124 + CompositeMediaSource.java:68-72 + MediaSourceList.java:329-341)。我们踩的是实现细节,有版本风险 → 保留宽松实现 |
| 4 | A-022 | CONFIRMED | END_OF_SOURCE 不会 ENDED,但会让 LoadControl 看到 bufferedDuration=0,从而**继续要求** continueLoading(ExoPlayerImplInternal.java:3319-3327、3056-3110)。"已知不确定点"可以结案:安全,但伴随高频加载请求 |
| 5 | A-023 | CONFIRMED(阈值数字需修正) | 起播门槛确实看 bufferedPosition(ExoPlayerImplInternal.java:2342-2371);但默认 bufferForPlayback=1000ms、afterRebuffer=2000ms(DefaultLoadControl.java:61-67),不是我们 docs 写的 2500 |
| 6 | A-044/A-045 | CONFIRMED(需补 A-NEW-01) | offset 先于 configure(MediaCodecRenderer.java:2352-2357 → MediaCodecAudioRenderer.java:886-889);PTS 在 offset 域(AudioSink.java:517-537 + DefaultAudioSink.java:1005-1008)。位置查询也必须同域(见 A-NEW-01) |
| 7 | A-050/A-052 | CONFIRMED | renderer 独占 play/pause(MediaCodecAudioRenderer.java:701-714);isEnded 由 sink 决定(754-757 + DefaultAudioSink.java:1424-1427)。"sink 自己 play"确实会撤销暂停(handleBuffer 内 1005-1018 只在 playing 时 play) |
| 8 | A-066/A-067 | DEVIATION | 周期位置更新 3s(MediaSessionImpl.java:109、1356-1364),按真实 speed 外推(MediaUtils.java:250-259);seekTo 会立即乐观 mask(MediaControllerImplBase.java:2258-2321) |
| 9 | A-080/A-081 | CONFIRMED | shuffle 不改当前项(ExoPlayerImplInternal.java:1167-1180);三参 setMediaItems 的第三参是项内位置(ExoPlayerImpl.java:616-622) |
| 10 | A-091 | UNDEFINED | media3 不定义帧↔µs 取整;我们自己的 ceil/floor 约定要靠自测保证(往返 <1 帧、三圈无漂移) |

---

## 14. 附录 C(6 个空白)逐项回答

| # | 空白 | 结论 | 源码依据 |
|---|---|---|---|
| 1 | MediaSource.maybeThrowSourceInfoRefreshError 的调用时机 | **1.8.0 播放路径不调用它**,唯一调用点是 MaskingMediaPeriod.maybeThrowPrepareError(当 period 还没建时) | MaskingMediaPeriod.java:153-170;ExoPlayerImplInternal/MediaSourceList 无调用 |
| 2 | MediaPeriod.Callback 是否允许任意线程 | 文档要求 playback 线程,但实现把 onPrepared/onContinueLoadingRequested 转成 handler 消息,跨线程**能工作**但不是契约 | MediaPeriod.java:33-36;ExoPlayerImplInternal.java:614-621 |
| 3 | MediaSourceEventListener/DrmSessionEventListener 派发义务 | MediaSourceList 会注册并传入 listener,但事件只被 analytics 消费;不派发不影响加载/重试正确性,只丢调试可见性 | MediaSourceList.java:466-475 |
| 4 | MediaSessionService 前台服务/通知生命周期 | 默认 onUpdateNotification 用 MediaNotificationManager 管理前台/通知;可配 timeout/空闲通知;onTaskRemoved 默认暂停并 stopSelf;targetSdk>=28 需 FOREGROUND_SERVICE | MediaSessionService.java:646-679、665-667、612-619、524、540 |
| 5 | Player.Commands 白名单副作用 | controller 侧逐命令检查,不通过则 return + 打 "Controller isn't allowed to call command=",命令静默失效;可用命令来自 ConnectionResult | MediaControllerImplBase.java:3144-3150;MediaSession.java:1930-1944 |
| 6 | setAudioAttributes(...,true) 焦点行为 | 真的会暂停:焦点丢失 → PLAYER_COMMAND_DO_NOT_PLAY/WAIT_FOR_CALLBACK;可 duck 时只把音量乘 0.2;非 MEDIA/GAME usage 直接抛 | AudioFocusManager.java:165-173、365-388、123;ExoPlayerImplInternal.java:1036-1041、653-659 |

---

## 15. 关键证据速查(按文件)

| 文件 | 本报告用到的关键行 |
|---|---|
| ExoPlayerImpl.java | 528-536(prepare)、884-931(seekTo)、1106-1138(位置/时长)、1969-1988(stopInternal)、2410-2465(setMediaSourcesInternal)、2443-2452(mask)、2539-2548(masking timeline)、2906-2925(verifyApplicationThread)、2957-2991(canUpdate/update)、2104/2138-2213(事件派发)、2345-2398(transition reason) |
| ExoPlayerImplInternal.java | 590-598(release 阻塞)、954-966(prepareInternal)、1382(maybeThrowPrepareError)、1402-1411(READY/startRenderers)、1747-1751(seekToUs 采用)、1765-1775(resetRendererPosition)、181-183(10ms/1s 调度)、2989-3016(period prepared → continueLoading)、3319-3327(bufferedDuration) |
| MediaSourceList.java | 265-273(update items)、310-319(prepare)、329-341(createPeriod)、349-357(releasePeriod)、466-475(child prepare)、477-486(maybe release) |
| MaskingMediaSource.java | 98-124(prepare/createPeriod)、142-208(onChildSourceInfoRefreshed)、232-252(prepare position override)、354-397(PlaceholderTimeline) |
| ProgressiveMediaSource.java | 373-381(canUpdateMediaItem)、457-495(onSourceInfoRefreshed / timeline)、312(1MB 加载检查间隔) |
| ProgressiveMediaPeriod.java | 439-466(getBufferedPositionUs)、512-522(adjusted seek)、636-670(onLoadCompleted/时长补算)、801-811(endTracks 后新 track)、825-834(setSeekMap)、837-845(maybeFinishPrepare)、1102-1163(load 循环) |
| AudioSink.java | 401(CURRENT_POSITION_NOT_SET)、486-545(position/configure/handleBuffer/playToEndOfStream) |
| DefaultAudioSink.java | 687-706(supportsFormat)、716-724(getCurrentPositionUs)、933-947(play/discontinuity)、951-1108(handleBuffer)、1401-1434(playToEndOfStream/isEnded)、1612-1662(flush/reset) |
| MediaCodecRenderer.java | 1920-1927(isReady)、2352-2357(outputStreamOffsetUs 通知)、2574-2586(bypass 新格式) |
| MediaCodecAudioRenderer.java | 458-470(shouldUseBypass)、651-667(configure)、691-699(onPositionReset→flush)、701-714(start/stop)、717-744(disable/reset)、746-752(release)、754-757(isEnded)、1065-1073(position) |
| MediaPeriodQueue.java | 70(1e12)、220-231(renderer offset 赋值) |
| MediaPeriodHolder.java | 135-168(toPeriodTime/toRendererTime/getRendererOffset) |
| DefaultLoadControl.java | 50-67(默认阈值)、375-422(shouldContinueLoading/shouldStartPlayback) |
| BasePlayer.java | 129-136(seekToDefaultPosition)、263-270(next/prev 用 repeat+shuffle)、394-397(getRepeatModeForNavigation)、181-204(hasNext/seekToNext) |
| Util.java | 1612-1626(usToMs/msToUs) |
| MediaController.java | 374-393(buildAsync)、650-688(release)、742-810/906-939(未连接 no-op/默认值)、2112-2114(线程检查) |
| MediaControllerImplBase.java | 316-319(isConnected)、2258-2321(seekTo 乐观 mask)、3144-3150(命令门控) |
| MediaSessionImpl.java | 109/263/1356-1364(3s 周期)、255-267(PlayerWrapper)、656-670(sessionExtras) |
| MediaSessionService.java | 248-282(onGetSession)、612-619(onTaskRemoved)、646-679(通知/前台) |

---

## 16. 结案说明

- 95 条全部给出 CONFIRMED/DEVIATION/UNDEFINED,无"待定"。
- 7 条 DEVIATION 已给出:源码证据 + 我们代码位置 + 用户可见后果 + 最小修复 + 最小复现(12.1 表)。
- 6 条 UNDEFINED 已转为"我们自己必须断言"的清单(12.2 表),并尽量复用现有 /debug/invariant 机制。
- 14 条 A-NEW 是 spec 的盲区,其中 A-NEW-01/07/11 会直接导致"位置错 1e12 / 永久 BUFFERING / 命令静默无效",建议优先补断言。







