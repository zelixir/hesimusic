# 场景化自动化测试（真机场景矩阵）

面向"每个功能单独能跑、按常识组合就出问题"这类缺陷：把用户会做的动作序列固化成 18 条常规场景，
外加 1 条 ~~~--long~~~ 长窗口（>200s）10 分钟稳定性场景，
在真机（debug 包 + adb）上按**常识判据**跑一遍，输出一份可贴进 issue 的 PASS/FAIL 表 + 失败证据。

- 执行器：~~~tools/scenarios/run-scenarios.ts~~~（Bun），一条命令包装：~~~tools/scenarios/run-scenarios.ps1~~~
- 判据与探针：~~~tools/scenarios/harness.ts~~~
- 场景本体：~~~tools/scenarios/scenarios.ts~~~
- 不变量白名单：~~~tools/scenarios/allow-invariants.json~~~

本文档只描述"怎么跑、判什么、怎么扩展"；首轮失败清单见执行产物里的 ~~~report.md~~~。

---

## 1. 一条命令

~~~
powershell -NoProfile -File tools/scenarios/run-scenarios.ps1
~~~

等价的 Bun 命令：

~~~
bun run tools/scenarios/run-scenarios.ts
~~~

常用参数：

| 参数 | 含义 |
|---|---|
| ~~~--device b1e84dad~~~ | 指定 adb 序列号（默认取第一台在线设备） |
| ~~~--only 1,2,9~~~ | 只跑指定场景 |
| ~~~--list~~~ | 列出场景编号 |
| ~~~--skip-ui~~~ | 跳过依赖 uiautomator 的场景（6/7/9/10/11/15/16） |
| ~~~--exclusive~~~ | 只跑独占组（6/7/9/10/11/15，见 §1.4） |
| ~~~--non-exclusive~~~ | 只跑其余场景（可在有人用的设备上先跑） |
| ~~~--timeout 150000~~~ | 单场景硬超时（默认 150s） |
| ~~~--song / --song-short / --song-unconfirmed / --song-long~~~ | 覆盖自动选歌 |
| ~~~--long~~~ | 只跑长窗口 10 分钟稳定性（场景 19，默认不跑；见 §9） |
| ~~~--allow-invariant <code>~~~ | 临时允许某个不变量 code（也可写进 allow-invariants.json） |

### 前置条件

1. 设备在线，已安装 **debug 包**（~~~com.zjr.hesimusic~~~；LoopDebugServer 仅 debug 源集编译）。
2. 屏幕亮、已解锁、应用在前台（执行器会尝试拉起；ROM 拒绝时需手动点一下）。
3. 主播放器走 **SOURCE 路线**（默认）。出声判据依赖 ~~~/state.invariantProbe.writtenFrames~~~，
   该计数只在 SOURCE 路线上更新；M3′ 路线下相关判据会报"探针不可用"而不是假装 PASS。
4. 独占设备：本套件会切页面、改 A/B、暂停/播放。与其它 agent / 手工操作并发时结果不可信
   （进程重装会直接杀掉 app；对方切歌会让"暂停后出声"等判据失真）。
5. 首次运行前至少有一首"已确认循环"的歌；场景 14 需要一首"未确认循环"的歌；
   ~~~--long~~~ 还需要一首时长 ≥240s 的已确认循环歌（没有则场景 19 SKIP）。
   执行器会自动从 ~~~/library/songs~~~ 选歌并打印选择结果，也可用参数指定。

### 产物

~~~
e2e-artifacts/scenarios/<yyyyMMdd-HHmmss>/
  report.md                 结果表 + 失败清单 + 待裁决 + 环境事实（可直接贴 issue）
  report.json               结构化结果（含每条判据、上下文快照、不变量、崩溃行）
  evidence/<NN>-<key>.json  单场景证据：动作序列、before/after /state、新增不变量、失败时 logcat 片段
~~~

控制台同时打印对齐表格与失败摘要。退出码：全 PASS/SKIP/CONTAMINATED=0；有 FAIL 或崩溃=1；执行器自身错误=2。

报告里每条场景都有一张"判据明细"表（判据 / 实测 / 结论 / 证据路径），失败清单与污染清单分开，
证据路径直接指到 ~~~evidence/<NN>-<key>.json~~~，便于把某一条判据贴进 issue。

### 状态语义

| 状态 | 含义 | 处理 |
|---|---|---|
| PASS | 全部判据通过 | — |
| FAIL | 有判据失败（含执行异常、进程重启） | 看判据与证据，修完重跑 |
| SKIP | 前置条件不具备（旧包缺 /debug/preview、--skip-ui、结果页未就绪等） | 补前置或换包 |
| CONTAMINATED | 场景执行期间检测到外部改动（另一个 agent / 手工 / 重装 APK），结果不可信 | 独占窗口重跑；不计入 FAIL |
| PENDING | 已知依赖未落地（如 jump-fill / media3 原生 seek 成本），当前数值超出目标但不算 FAIL | 依赖落地后自动按严格判据复核 |

### 1.4 如何独占跑（推荐流程）

本套件会切页面、改窗口、暂停播放；与其它 agent/手工操作并发时：

- APK 重装会直接杀掉进程（pid 变化）；
- 对方切歌/暂停会让"暂停后出声""位置连续"等判据失真；
- 结果页预览、/debug/preview-seek 只有一份，两个脚本会互相抢。

因此推荐两段式：

~~~powershell
# 1) 独占窗口：确认没人再 install / 发 /player /loop /navigate /debug 请求
powershell -NoProfile -File tools/scenarios/run-scenarios.ps1 --exclusive

# 2) 其余场景：可以在有人用的设备上跑（若有外部改动，会自动标 CONTAMINATED）
powershell -NoProfile -File tools/scenarios/run-scenarios.ps1 --non-exclusive
~~~

独占组 = 6（改 A/B）、7（四模式）、9（接缝检查）、10（区内 5 次 seek）、11（区外 seek）、
15（极短窗口循环）、18（普通播放 seek 对照）；它们都依赖结果页 UI/预览播放器，最容易被外部操作打断。

执行器自带的污染检测（harness 的 selfActivity + 300ms /state 指纹对比）：

- 每 300ms 比较 songId / engine / window / screen / playing / repeatMode 指纹；
- 指纹变化若发生在"本执行器 POST/tap/HOME 之后 3.5s 内"，算自己的动作；
- 否则记一次污染；场景结束时若检测到污染 → 状态 CONTAMINATED（优先于 FAIL）；
- 场景前后包 versionCode/lastUpdateTime 变化（被重装）也算污染；
- 自己发起的 /analyze 会把 analysis.phase 的变化在 3 分钟内排除在指纹之外（否则自己的分析进度会被误判成外部改动）。

这是启发式，不能替代独占；它的价值是"不把被污染的失败当成产品 bug"。

---

## 2. 场景矩阵

"动作"全部通过现有调试协议 + 真机 UI 点击（仅模式切换/A/B 按钮这类没有协议入口的操作）；
不新增后端接口。

| # | 场景 | 动作 | 常识预期 | 实现判据 |
|---|---|---|---|---|
| 1 | 播放 -> 暂停 -> 继续 | /player/play；play-pause；再 play-pause | 暂停时位置不动、输出帧 0；继续后输出恢复 | 暂停后等尾帧落定（连续两次 500ms 采样帧数不变）→ 窗口 Δ=0；位置变化 ≤250ms；继续后 2s 出声 ≥90% |
| 2 | 播放中拖动到 50% | /player/seek 到 duration/2 | ≤300ms 到达且出声、不回弹 | 40ms 轮询 positionMs 到目标 ±300ms 的耗时 ≤300ms；随后 1.6s 出声；700ms 后位置不低于目标-600ms |
| 3 | 播放中进循环 | 播到 A+3s；/loop/enter{play:true,restart:false} | 位置在 [A,B)、engine=loop、出声 | 6s 内 engine=loop 且 window.songId=当前歌；displayPos ∈ [A-1s,B+1s]；1.6s 出声；repeat=one |
| 4 | 暂停中进循环 | 暂停；/loop/enter{play:false}；等 2s；手动播放 | 不自动出声；手动播放后出声 | 进入后 playing=false 且 Δframes=0；手动播放后 engine=loop 且 1.8s 出声 |
| 5 | 循环中退出 | 循环中 /loop/exit | 回普通播放、位置回媒体时间 | 6s 内 window=null 且 engine≠loop；仍在播放；位置不回跳 >2s（repeat 只记证据：协议口径只清意图、不恢复 repeat）；继续出声 |
| 6 | 循环中改 A/B | 结果页「仅循环段」播放中；拖到区间内；点「当前位置→A」 | 立即生效、不崩、位置在新区间 | preview.aMs 变化到目标 ±1.5s；bMs 不变；仍在播放；display ∈ 新区间；1.4s 出声；主播放器未崩 |
| 7 | 四种试听模式 | 依次点 完整循环/仅循环段/接缝检查/自由 | 每种都出声、位置对 | 模式切换后 8s 内 isPlaying；1.5s 出声 ≥80%；位置：FULL/FREE 从 0 起、LOOP_ONLY 从 A-300ms、SEAM 从 max(A,B-5s) 起 |
| 8 | 播放中启动分析 | 播放中点 /analyze | 分析期间静音、位置不动；结束不崩 | 分析开始后 250ms 起测 1.5s：Δframes=0、位置变化 ≤250ms、playing=false；分析 phase=done；进程未重启 |
| 9 | 分析完成 -> 点接缝检查 | 结果页点「接缝检查」 | ≤500ms 出声、位置=B-5s | 从 adb tap 返回到 outputFrames 首次增长 ≤500ms（20ms 轮询）；displayMs ≈ max(A,B-5s) ±1.2~3s；mode=SEAM |
| 10 | 循环区内连续 5 次 seek | /debug/preview-seek 在 [A,B) 内取 5 个点 | 每次 ≤100ms 出声、不堆积 | applyMs ≤100ms；displayAfter=目标 ±1.5s；framesDelta>0；seekRequests 恰好 +5；**出声延迟取 stages.playoutMs**（AudioTrack 播放头开始走；advanceMs 旧包会被“seek 未确认的旧位置”提前满足）：≤100ms 通过，100~300ms 标 PENDING（依赖 jump-fill / media3 原生 seek 成本，明细带 AUDIBLE_NATIVE_COST），>300ms 失败 |
| 11 | 循环区外 seek | /debug/preview-seek 到 A-10s / B+10s | 允许、进入自由试听、落点不折返 | HTTP 200 + framesDelta>0；outsideLoop=true；displayAfter=目标 ±1s；windowA/B 不变 |
| 18 | 普通播放(自由试听) 5 次 seek 对照 | 先区外 seek 进入自由试听（SOURCE 整曲、无环回），再 seek 5 次 | 量出 media3 原生 seek 成本用于对照场景 10 | applyMs ≤100ms；出声 ≤100ms 通过、100~300ms PENDING(media3-native-cost)、>300ms 失败；位置 ±1.5s；framesDelta>0 |
| 12 | 播放中切到下一首 | /loop/enter{restart:true} 建队列后 /player/next | 新歌正常出声、循环标记不串歌 | 8s 内 songId 变化且 playing；1.8s 出声；window=null 或 window.songId≠新歌；engine≠loop |
| 13 | 后台 -> 前台 | HOME；1.5s；am start 回前台 | 声音不断、位置连续 | 后台仍 playing、位置前进 ≥800ms、2s 出声；回前台位置不回跳 >1s、仍出声 |
| 14 | 未确认循环进循环 | 对 unconfirmed 歌 /loop/enter | 不得进入循环（或提示）、不崩 | 3s 后 engine≠loop 且 window.songId≠该歌；进程存活 |
| 15 | 极短窗口(A=B-3s)循环 | /confirm 落 3s 窗口；结果页「仅循环段」；采样 9s | 能循环、接缝无停顿 | 9s 内 display 回绕 ≥2 次；outputFrames 相邻 100ms 采样不间断（最长停顿 ≤300ms）；位置始终在 [A,B]；整体出声 ≥80%；结束时**恢复原 A/B** |
| 16 | 全流程连跑 3 轮 | 每轮：播放→seek→进/出循环→暂停/继续→结果页试听→回列表；期间每 30s 采一次 meminfo，不足 5 点补观察 | 无内存增长、无不变量、不崩、无 OOM | 三轮 pid 不变；每轮有输出；Java Heap 末轮 ≤ 首轮+16MB；PSS ≤ 首轮×1.15+30MB；**30s 曲线 ≥5 点 + Theil–Sen 斜率 ≤8MB/min + 后半均值不高于前半+16MB**；无新增不变量；无 OutOfMemoryError |
| 17 | 循环中暂停 -> 继续 | 循环中 pause/resume（常识补充） | 暂停静音、继续仍循环 | 同场景 1 的静音判据 + 继续后 engine=loop 且出声 |

说明：
- 场景 6/7/9/10/11/15 依赖结果页与 ~~~GET /debug/preview~~~（issue 00023 起提供）。旧包会 SKIP 并说明原因。
- 场景 7 的四种模式没有协议入口，只能用 uiautomator 找到按钮文本并 adb tap；
  UI 文案变更时需同步本文档与场景代码。
- 场景 15 会临时改写该歌的 loop 行，finally 里用原 sample 值恢复；恢复失败会额外记一条 FAIL。

**独占组**：6、7、9、10、11、15、18（~~~--exclusive~~~）；其余为 shared 组（~~~--non-exclusive~~~）。
建议先跑独占组，再跑 shared 组。

---

## 3. 判据实现

### 3.1 "是否真出声"

| 播放器 | 探针 | 口径 |
|---|---|---|
| 主播放器 | ~~~/state.invariantProbe.writtenFrames~~~ | AudioSink 交给 delegate 的累计帧数；采样器 500ms 一次（PlaybackInvariantMonitor）。用 ~~~probe.atMs~~~ 差算真实跨度，避免采样相位差被当成丢帧 |
| 结果页预览 | ~~~/debug/preview.snapshot.outputFrames~~~ | 预览 controller 自己的 TapAudioSink 写出帧数，100ms 轮询（实时计数，不受 500ms 采样限制） |

- 出声判定：窗口内 ~~~Δframes / (sampleRate × 秒数) ≥ 0.85~~~（部分宽松场景 0.8），报告里给出实际百分比。
- **seek 出声延迟**：优先读 ~~~/debug/preview-seek~~~ 的 ~~~stages.playoutMs~~~（AudioTrack 播放头开始走）；
  ~~~advanceMs~~~ 只作旧包兜底（seek 未确认时 currentPosition 可能还是旧位置，会提前满足）。
- 采样率：优先取歌曲 loop 行的 sampleRate，否则按 44100 兜底（报告会标注）。
- **暂停静音**：先等尾帧落定（连续两次 500ms 采样帧数不变，最多 3s），再测 1.2s，要求 Δ=0。
  这样不会把 pause 瞬间的正常硬件尾帧（约 50-100ms）误判成"暂停仍出声"，也能抓住持续的 paused-but-sink-wrote。
- 出声率可能略高于 100%（sink 提交与采样时刻的相位差），不作上限判定。

### 3.2 "位置对"

- 主播放器：~~~GET /state.playback.positionMs~~~（已按 loop window 折返到 [A,B)）。
- 结果页预览：~~~GET /debug/preview.snapshot.displayMs~~~（展示口径；playerPositionMs 是原始时间轴）。
- 容差：seek 目标 ±300~500ms（主）/±1.5s（预览+UI 点击）；窗口判定 ±1s；位置"不动" ≤250ms。

### 3.3 "不卡"

- 主播放器：出声率窗口（≈1/0.9 秒粒度）+ 位置单调（seek 后不回弹、退出循环不回跳）。
- 结果页/接缝：100ms 采样 ~~~outputFrames~~~，相邻样本不增长视为停顿，最长停顿 ≤300ms 判通过
  （场景 15）。500ms 的 invariantProbe 做不到这个粒度，所以场景 15 必须走预览探针。

### 3.4 "不违反不变量"

每条场景执行前后各读一次 ~~~/state.invariants~~~，比较 ~~~code × count~~~：

- 运行前已存在、运行中 count 没增加的 code 不算新增（多次运行/别人留下的历史不背锅）。
- 新增 code 默认判 FAIL；确认属于已知噪声的 code 才写进 ~~~tools/scenarios/allow-invariants.json~~~，
  并要在该文件/报告里写清原因。
- 证据里保留 ~~~before/after invariants~~~ 与失败时 ~~~LoopInvariant~~~ logcat 片段。

### 3.5 "不崩"

- 每条场景前后 ~~~pidof com.zjr.hesimusic~~~ 必须相同（重装/崩溃/被杀都会暴露）。
- **每条场景结束都扫一次 crash buffer 的 ~~~OutOfMemoryError~~~，出现即 FAIL**（OOM 后 pid 可能还没变化，
  只看 pid 会漏）；全量跑完再全局扫一次 crash+main，OOM 同时让整轮退出码为 1。
- 全部跑完后扫 ~~~logcat -b crash~~~ 与 main buffer 中运行开始后的本包 ~~~FATAL EXCEPTION / ANR in~~~。
- 场景内异常（HTTP 5xx、超时、断言抛错）记为"执行异常"并保存现场，不影响后续场景（每条场景开始前重置播放态）。

### 3.6 内存 / heap 曲线（抗 GC 抖动）

- 固定 **30s 采样**；场景 16 不足 5 个点时结尾观察补齐（最多 120s），场景 19 约 20 个点。
- 斜率用 **Theil–Sen 中位斜率**（两两斜率取中位数），不用最小二乘：GC 锯齿/单点尖峰不会把斜率带偏。
- 只有**同时**满足以下两条才判泄漏：
  1. 中位斜率 > **8MB/min**；
  2. 后半段均值 > 前半段均值 + **16MB**（排除"一次性分配后平稳"）。
- 单点 GC 后回落、只涨不连续的情况不算；同时保留 Java Heap 首末 16MB / PSS +15%+30MB 的粗阈值。
- OOM 不看 pid，直接扫 crash buffer 判 FAIL（§3.5）。曲线写进 evidence，并在“判据明细”里显示。

### 3.7 证据

失败场景保存：
- 动作序列（时间、动作、关键参数）；
- 场景前后 ~~~/state~~~ 精简快照（播放/循环/分析/不变量/probe）；
- 所有判据与失败归因线索；
- 失败时间窗的 logcat 片段（LoopDebug/LoopInvariant/LoopPreview/LoopAudio/ExoPlayer/AndroidRuntime 等）。

---

## 4. 场景数据与副作用

- 选歌：优先"已确认循环、窗口 15~130s、时长 90~320s"里最短的一首；分析用"已确认循环、≤130s、可解码"的歌；
  未确认场景取最短的未确认循环歌。控制台会打印三首歌，便于复现。
- **格式坑（1258 实测）**：分析链对 .ogg 会报"无法读取音频文件"；选歌优先 mp3/wav/m4a，若曲库只有 ogg，
  场景 8/9 会明确失败（数据/格式限制，不是播放逻辑）。
- 副作用：
  - 播放队列/当前歌/重复模式会被改；结束时恢复运行前的 repeatMode/shuffle，并 /loop/exit、回 about 页。
  - 场景 15 会改 loop 行并在 finally 恢复（sample 级精确恢复）；恢复结果写进证据。
  - 每次运行都会重新导航页面、创建/销毁预览播放器（场景 16 就是用来暴露这类泄漏的）。
- 不修改应用代码、不安装包、不新增后端接口。

---

## 5. 如何扩展

### 5.1 加一条场景

在 ~~~tools/scenarios/scenarios.ts~~~ 末尾加一个对象并放进 ~~~SCENARIOS~~~：

~~~ts
const s18: Scenario = {
  id: 18,
  key: "repeat-mode-toggle",
  name: "播放中切单曲/列表/关闭循环",
  tag: "播放",
  async run(ctx) {
    const checks: Check[] = [];
    await ctx.resetForMain();
    await playSong(ctx, ctx.songs.primary.id);
    for (const mode of ["one", "all", "off"]) {
      const r = await ctx.api.post("/player/repeat", { mode });
      const st = await ctx.api.state();
      checks.push(check("repeat=" + mode, r.status === 200 && st.playback.repeatMode === mode,
        "status=" + r.status + " repeat=" + st.playback.repeatMode));
    }
    return { checks, summary: "repeat one/all/off" };
  },
};
~~~

要点：
- 用 ~~~ctx.api~~~ 走现有协议；用 ~~~ctx.adb~~~ 做设备侧取证；用 ~~~ctx.uiTap(label)~~~ 点没有协议入口的按钮。
- 一条判据一个 ~~~check(name, pass, detail, hint)~~~；~~~hint~~~ 写"初步归因线索"，不要写猜测性结论。
- 需要跳过的前置条件抛 ~~~SkipError("原因")~~~（报告里会显示 SKIP 而不是 FAIL）。
- 有数据改写时在 ~~~finally~~~ 里恢复，并把恢复结果写进 ~~~ctx.evidence~~~。
- 新场景记得加进 ~~~SCENARIOS~~~；若依赖 UI，把 id 加进 runner 的 ~~~UI_SCENARIOS~~~。

### 5.2 判据公共能力（harness.ts）

- ~~~measureMainFrames(api, ms, sampleRate)~~~ / ~~~measurePreviewFrames(...)~~~：输出帧窗口。
- ~~~waitFramesSettled(api)~~~：等 pause 尾帧落定。
- ~~~waitFor(fn, predicate, timeout, interval)~~~：通用轮询。
- ~~~ctx.previewSeek(ms)~~~：预览 seek + apply/advance/framesDelta/displayAfter 量测。
- ~~~ctx.previewReady()~~~：等结果页预览探针可用。
- ~~~Adb.meminfo / crashScanSince / oomLinesSince / logcatSlice / uiDump / bringForeground~~~：设备侧证据。
- 场景 16 的 30s heap 采样：~~~heapSeries~~~ 写进 evidence，报告里给出 ~~~105MB→100MB→47MB~~~ 形式的曲线。

### 5.3 不变量白名单怎么加

1. 先看失败证据里的 ~~~/state.invariants~~~（code、message、count）与 failure 场景 logcat 的 LoopInvariant 行；
2. 判断是"产品真的违反了运行期不变量"还是"断言本身对该场景不适用"（例如单曲队列没有 seek-to-next）；
3. 确认是后者，才在 ~~~tools/scenarios/allow-invariants.json~~~ 里加 code，并写清原因：

~~~json
{
  "codes": ["controller-available-commands-missing"],
  "notes": {
    "controller-available-commands-missing": "单曲队列/REPEAT_ONE 下 media3 合法地不提供 seek-to-next；该断言在 queueSize=1 时不适用"
  }
}
~~~

4. 白名单只影响"新增不变量"判据，不会隐藏 frames/位置/崩溃判据；加完重跑，报告会列出当前允许的 code。

### 5.4 改判据阈值

阈值都在 ~~~scenarios.ts~~~ 各场景里显式写死（0.85/0.9/100ms/300ms 等），故意不集中成配置：
一次改动应当有 issue/裁决依据。若只是某台设备噪声，优先加不变量白名单或放宽对应判据并在报告注明。

---

## 6. 覆盖边界（已知）

1. **结果页四模式/接缝检查没有协议入口**，只能 UI 点击；文案或布局大改会让场景 6/7/9 失败/跳过。
   若后续新增"切模式/点接缝检查"协议端点，应改为协议驱动（更稳定、也能量到更细的延迟）。
2. 主播放器 seek 延迟用 HTTP 轮询（30~40ms 间隔）测量，分辨率约 40ms；≤100ms 级要求只对
   ~~~/debug/preview-seek~~~ 的 applyMs/advanceMs 做精确判定。
3. 主播放器出声判据依赖 SOURCE 路线；M3′ 路线无法从现有协议拿到同源帧计数（会明确报探针不可用）。
4. 不变量白名单默认带 3 个 1258 实测噪声 code（见 §8）；新增/调整必须在 allow-invariants.json 里写清原因，并让 invariants owner 复核。
5. 场景 16 的"heap 稳定"是启发式：Java Heap 16MB / PSS +15%+30MB 容差，只能发现明显泄漏，
   不能替代长时间内存压测。
6. 执行器不做 install/启动环境搭建；包不在、调试口不通会直接报环境错误。
7. **长窗口（B−A > 200s）不包含在场景 16 的 3 轮里**：已做成可选的场景 19，
   用 ~~~--long~~~ 单独跑一次 10 分钟稳定性（循环播放 + 每 30s heap + 不变量/崩溃/OOM）。
   分块回退路径怎么造窗口见 §9。

---

## 7. 待裁决（产品行为）

以下是执行器**不替产品决定**、只记录现状的点；首轮结果会明确标注：

1. **循环区外 seek 的期望语义**：已由 seek-ux 裁决为"允许 + 进入自由试听 + 落点不折返"，
   场景 11 按此判定（outsideLoop=true、display=目标 ±1s、A/B 不变）。
2. **未确认循环（confirmed=false）的协议入口**：/loop/enter 是否也应拒绝（当前 UI 可能有拦截）？
   场景 14 按"不得进入循环模式"判定。
3. **暂停中进循环**：进入后 engine 是否应立刻为 loop（还是等手动播放才装窗口）？
   场景 4 只要求"不自动出声 + 手动播放后进入循环"。
4. **极短窗口接缝**：A=B-3s 时接缝允许的最大停顿（当前 300ms）与"可接受"的定义。
5. **暂停尾帧**：pause 后多长时间的硬件尾帧算正常（当前判据：3s 内必须落定，落定后 Δ=0）。
6. **完整循环的起点**：文档语义是"从前奏 0 起播"，1258 的 SOURCE 路线实测从 A 起播（场景 7 FAIL）。
   需要确认是"以文档为准修播放器"还是"改语义"。
7. **/loop/enter restart=true 的队列语义**：期望当前歌进循环且不换歌；1258 实测 ≤500ms 内切到
   #41405 并退出无缝（场景 12）。这是缺陷候选，不属于需要产品裁决的开放问题。

---

## 8. 首轮结果（1258 / 2026-11-20 / 236s）

命令与产物：

~~~powershell
powershell -NoProfile -File tools/scenarios/run-scenarios.ps1
# PASS 15 / FAIL 2 / SKIP 0 / PENDING 1，总耗时 236s，adb 调用 170 次（串行）
# 报告: e2e-artifacts/scenarios/20261120-210353/report.md
# 证据: e2e-artifacts/scenarios/20261120-210353/evidence/*.json
~~~

设备：b1e84dad（OnePlus 8T, Android 14），debug 包 versionCode **1258 / 2.3.0**，pid 22948 全程未重启。
选歌：primary/short = #39944「Redstar Rising」（唯一可分析的已确认循环 mp3），unconfirmed = #41613。
曲库其余已确认循环歌是 .ogg，1258 的分析链报"无法读取音频文件"，所以 short 退化为 primary；
这是数据/格式限制，不是播放逻辑。

### 真实失败（2 条）

1. **#7 完整循环不是从 0 起播** — FAIL
   - 实测：点「完整循环」后 1.9s，displayMs=19325（A=17496，约 A+1.8s），不是 0..8s。
   - FULL/LOOP_ONLY/SEAM 都有输出（99%/100%/100%），只有 FULL 起点不对。
   - 代码线索：PreviewPlayer.sourceStartMs() 把 PreviewMode.FULL 与 LOOP_ONLY 一起映射到
     w.loopStartUs（app/src/main/java/com/zjr/hesimusic/ui/loop/PreviewPlayer.kt:1096-1104）。
     SOURCE 路线下 FULL 的"前奏 0 起播"语义丢失；M3′ 路线才走 playM3(..., 0)。
   - 证据：evidence/07-preview-modes.json（steps 里 FULL 的 display/aMs/bMs/engine）。

2. **#12 /loop/enter restart=true 会切歌并退出无缝** — FAIL（可稳定复现）
   - 实测序列：/player/play 39944 → /loop/enter {play:true,restart:true} 返回
     {entered:true, playing:true}；**≤500ms 后**当前歌变成 #41405「-Paradox-」、playing=false、
     repeat=off、intent 已清空、queueSize=1714，window=null。
   - 独立手工复现：/player/play 后 /loop/enter，500ms 轮询 12 次，第 1 次就已是
     song=41405 / paused / repeat=off（见报告与 issue 00024）。
   - 影响：这是 /confirm 与 UI「无缝循环」共用的"全库队列 + setMediaItems(index)"路径；
     场景 12 的 next 测试因此无法进入循环（重试 restart=false 也一样）。
   - 初步归因方向：libraryQueueFor() 的 index 与 setMediaItems(items,index,0) 实际落点不一致 /
     队列重建触发 media item transition，把 loop intent 按"切歌"清掉。需要播放层 owner 复现。
   - 证据：evidence/12-next-song-in-loop.json（enter/retry 响应、8s 后 song=41405 的 before/after、logcat）。

### 已知成本（PENDING，不算失败）

- **#10 区内 5 次 seek**：apply 2~10ms（达标），出声（advance）166~286ms；
  标 AUDIBLE_NATIVE_COST，依赖 jump-fill / media3 sink flush 成本。
- **#18 普通播放（自由试听）对照**：apply 2~10ms，出声 47~92ms。
  → 环带侧 166~286ms 与普通 seek 47~92ms 相比，多出约 80~200ms，说明当前区内 seek 的出声延迟
  不只是 ExoPlayer 原生成本；jump-fill 落地后应按 ≤100ms 复核。

### 不变量噪声（已 allowlist，待 owner 复核）

1258 上以下 code 在普通播放/暂停/seek/切窗口路径反复触发，已写进 allow-invariants.json
（报告里单列"已允许的不变量告警"，不判场景失败）：

| code | 触发场景 | 初步判断 |
|---|---|---|
| sink-configure-split-mismatch | 普通播放、seek、改窗口、/confirm | configureCount 与 SOURCE 路线口径不一致；断言可能只适用于 M3′ |
| controller-available-commands-missing | 单曲队列（/player/play 单项、repeat=ONE） | media3 在 queueSize=1 时合法地没有 seek-to-next；断言未考虑 |
| paused-but-sink-wrote | 暂停/恢复/seek 过渡样本 | 50~100ms 硬件尾帧被 500ms 采样器抓成 violation；场景 1/17 已用"落定后 Δ=0"做严格判定 |

### 曾误报、已修掉（非产品问题）

- #6「改 A 后仍在播放」：commitWindow 会 ApplyWindow+SeekTo+Play，短暂 isPlaying=false；
  场景等待其恢复后再断言，复跑 PASS。
- 场景 6 的 A 值对账、场景 8 的暂停尾帧、场景 12 的循环重试、场景 18 的自由试听位置重试等，
  都是执行器侧修正，已包含在本轮代码里。

### 结论

除 #7 的 FULL 起点和 #12 的队列换歌外，其余 15 条场景在 1258 上按常识判据通过（含：
暂停静音 Δ=0、循环进出、区内外 seek、四种模式出声、分析静音、后台续播、未确认门禁、
三轮 heap 稳定 JavaHeap 105912→47608KB、接缝检查首声 149ms ≤500ms）。

> 本轮之后新增的判据（30s heap 曲线、逐场景 OOM 扫描、以 stages.playoutMs 作为出声延迟）已在代码与
> 文档中就位，等 1259+ 正式包下一轮全量验证；长窗口（>200s）用 --long 单独跑，见 §9。

---

## 9. 长窗口（>200s）与分块回退路径

### 9.1 一条命令跑 10 分钟稳定性

~~~powershell
powershell -NoProfile -File tools/scenarios/run-scenarios.ps1 --long
# 约 630s；只跑场景 19，不进入常规 18 条
~~~

- 选歌：已确认循环、时长 ≥240s 里最长的一首（优先 mp3/flac）；没有则 SKIP。也可 --song-long <id>。
- 构造窗口：A=5s，B=A+210s（曲目不够则夹到 duration-2s）；写入 loop 行前先记下原 sample 值，
  finally 里用 /confirm 精确恢复（和场景 15 一样，测试污染用户数据必须还原）。
- 进入循环后跑 19 个采样点 × 30s（约 10 分钟），每点记录：engine/window/playing/position/seam/degrade/
  Java Heap/PSS；每 60s 额外测 2s 输出帧。
- 判据：10 分钟保持 engine=loop+playing；位置始终在 [A,B]；seamCount 增长 ≥2；周期性出声；
  degradeCount 不增长；heap 曲线 ≥15 点且斜率/前后半均值不超阈值；跑满 ≥540s；无 OOM/崩溃。
- 若中途 engine=exo / window=null：先看窗口 PCM 是否超过**主播放器**预算 heap/10（见 §9.2），
  超预算会回退普通播放（主播放器不会回退到分块实现）。

### 9.2 整窗 PCM 预算与"分块回退"怎么造

窗口 PCM 字节数（PCM16）：

~~~text
bytes = (B - A) 秒 × sampleRate × channels × 2
# 44.1kHz 立体声 16bit ≈ 176.4 KB/s ≈ 0.168 MB/s
~~~

预算：

| 播放器 | 环带预算 | 超过预算的行为 |
|---|---|---|
| 主播放器 | heap / 10 | 预检 RING_TOO_LARGE → 回退"普通播放/不循环"，不是分块 |
| 结果页预览 | heap / 6 | LoopingPcmExtractorMediaSource 回退 **1 秒块 + 块缓存**（分块路径） |

所以"分块回退路径"要在**结果页预览**上造，而不是主播放器。

步骤（以 heap 402MB、44.1kHz 立体声为例）：

1. 取 heap（两个来源任选）：
   ~~~powershell
   adb shell getprop dalvik.vm.heapsize        # 例如 512m
   adb logcat -d -s LoopPcmSource | Select-String "RingBand (select|fallback)"
   # 日志里有 heap=... budget=... divisor=6，直接是实际 maxMemory/预算
   ~~~
2. 算超预算窗口：heap/6=67MB → 需要 > 67MB / 0.168MB/s ≈ **> 400s** 的窗口；
   选一首时长 ≥ 430s 的已确认循环歌（没有就用 /analyze + /confirm 先确认）。
3. 用 /confirm 显式写 A/B（会落库，跑完记得恢复原值）：
   ~~~powershell
   # sr 从 /song/{id}.sampleRate 取；A=5s、B=405s
   $body = '{"songId":<id>,"aSamples":220500,"bSamples":17860500}'
   Invoke-RestMethod -Method Post -Uri http://127.0.0.1:18765/confirm -ContentType application/json -Body $body
   ~~~
4. 打开结果页并点「仅循环段」后看日志：
   ~~~powershell
   adb logcat -d -s LoopPcmSource | Select-String "RingBand fallback reason=SIZE"
   # 期望: RingBand fallback reason=SIZE bandBytes=... budget=... divisor=6
   ~~~
   同时 GET /debug/preview 应看到 engine=SOURCE、window 是新区间、outputFrames 持续增长、
   isPlaying=true——说明分块实现仍然能出声/环回，只是首声与 seek 延迟会变高。
5. 判定：出现 "fallback reason=SIZE" 且出声/环回正常 = 分块回退路径可用；
   若首声 > 500ms 或 seek playout > 300ms，按场景 9/10 的口径记录（属于长窗口路径的成本，不是常规回归）。

注意：主播放器超过 heap/10 会直接回退普通播放（不循环），不要把这种窗口拿去做"长窗口循环稳定性"；
场景 19 用 210s（约 37MB@44.1k）留在常见 heap/10 预算内，就是为避免这条回退干扰稳定性判定。

