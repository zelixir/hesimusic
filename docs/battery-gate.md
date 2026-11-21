# 连续播放耗电对标门禁（SOURCE vs M3′）

## 1. 这个门禁回答什么问题

同一台设备、同一首歌、同一个 [A,B) 循环窗口、同一个音量，连续循环播放 10 分钟：

- **A 轮**：环带引擎（engine=source，循环 PCM 数据源 + 环带缓存）
- **B 轮**：原始 SDK 引擎（engine=m3，M3′：LoopAudioSink 捕获 + 重放 + PTS 改写）

比较该应用 UID 的**估算耗电 mAh**，并同时记录 CPU 时间、唤醒次数、输出帧数与 AudioTrack 活跃时长。

**判据**：source.mAh <= 1.10 * m3.mAh，且两轮都确认满占空出声，且引擎在两轮中始终是期望值。

> 目的不是追求绝对 mAh 的精确值，而是用同一套系统统计回答一个相对问题：环带方案常驻的 PCM 环 + 自有解码会话，是否让连续播放的耗电显著高于原始 SDK 路径。

---

## 2. 交付物与使用方式

> 当前状态：脚本与文档已完成，解析器/门禁逻辑已通过 -SelfTest，但**尚未在真机上跑过**。第一次独占窗口建议先确认 setup 阶段（引擎切换、熄屏、reset、帧基线）全部通过，再接受 10 分钟数据。

脚本：tools/battery-gate.ps1

一条命令跑完两轮并打印对比表：

~~~powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/battery-gate.ps1 ^
  -SongId 40904 -Minutes 10 -Volume 8 -EngineOrder source,m3
~~~

参数：

| 参数 | 默认 | 说明 |
|---|---|---|
| -Device | 自动 | adb serial；只有一台设备时可省略 |
| -SongId | 0 | 0 = 自动挑一首已确认循环的 mp3；否则指定歌曲 id |
| -Ams / -Bms | 0 | >0 时用显式 A/B（毫秒）重新确认，必须同时给 -SongId；0 = 用已有确认行 |
| -Minutes | 10 | 每轮时长 |
| -Volume | 8 | STREAM_MUSIC 音量（设备上限通常 15，8 约等于 50%） |
| -EngineOrder | source,m3 | 两轮顺序；门禁始终按 source 对 m3 比较 |
| -WarmupSec | 20 | 进入稳态后、reset 统计前的预热秒数 |
| -SampleSec | 30 | 轮内 /state 采样周期（不要调太小，避免轮询本身污染数据） |
| -RingTimeoutSec | 240 | 等待 M3′ 环完整（或 SOURCE 首次回绕）的上限 |
| -SkipAnalyze | off | 歌曲未确认循环时直接失败，不跑分析 |
| -SkipAppStop | off | 不 force-stop 其它第三方 App |
| -LeaveScreenOff | off | 结束后不主动亮屏 |
| -SelfTest | off | 只跑解析器自检，不碰设备 |

退出码：

| 退出码 | 含义 |
|---|---|
| 0 | PASS：source <= 1.10 * m3，两轮满占空，引擎正确 |
| 1 | FAIL：判据不满足（耗电比超标 / 占空不足 / 播放中断 / 引擎跑偏） |
| 2 | ERROR：环境或测量不可用，未形成有效结论（不能当成 PASS） |

产物目录：e2e-artifacts/battery/<yyyyMMdd-HHmmss>/

~~~text
run.log
summary.txt
run.json
env-before.json
round-A/
  state-loop-started.json
  state-start.json
  state-end.json
  round.json
  batterystats-reset.txt
  batterystats-pkg.txt
  batterystats-pkg-charged.txt
  batterystats-all.txt
  batterystats-full.txt
  dumpsys-battery-before.txt
  dumpsys-battery-after.txt
  dumpsys-power.txt
  dumpsys-audio.txt
  dumpsys-audio-flinger.txt
  proc-stat-before.txt
  proc-stat-after.txt
  ps-after.txt
  logcat-loop-perf.txt
round-B/ ...
~~~

summary.txt 和终端会打印：

~~~text
Round Engine  Minutes  mAh    CPU_ms  Wakeups  Frames      Duty    Full  Audio_s  LoopPath  Verdict
A     source  10.01    18.42  2345    3        28864000    100.20% yes   ...      source    OK
B     m3      10.00    17.90  2100    2        28820000    100.00% yes   ...      m3        OK

Source/M3 mAh ratio = 1.0291 (limit 1.10)
RESULT: PASS (exit 0)
~~~

---

## 3. 为什么这样采集

### 3.1 耗电（主指标）

每轮开始前：

~~~bash
adb shell dumpsys batterystats --reset
~~~

轮结束（先暂停播放，再立即采集，不等）：

~~~bash
adb shell dumpsys batterystats com.zjr.hesimusic
~~~

解析 Estimated power use (mAh): 段里该 UID 的行：

~~~text
Uid u0a159: 18.42 ( cpu=12.10 audio=4.20 wifi=0.10 )
~~~

- u0a159 是 Android 的 UserHandle.formatUid() 形式：user 0 + (appId - 10000)。脚本先通过 cmd package list packages -U 拿到真实 UID，再映射成该标签。
- 如果带包名的 dump 没有该段，脚本会依次再取 dumpsys batterystats --charged <pkg>、全量 dumpsys batterystats、全量 --charged，直到拿到 mAh 与 Wakeups；每份文本都留档。
- 这是 Android 的**估算模型**，不是电量计差分；它的优势是同一台机、同一套模型下的相对比较稳定。dumpsys battery 的电量百分比只作为兜底参考，分辨率约 1%（几十 mAh），不适合 10 分钟测量。

### 3.2 CPU 时间

~~~bash
adb shell cat /proc/<pid>/stat
adb shell getconf CLK_TCK
~~~

取第 14/15 字段 utime+stime（clock ticks），前后差分后：

~~~text
CPU_ms = (end_ticks - start_ticks) * 1000 / CLK_TCK
~~~

Android 上 CLK_TCK 通常是 100，即 10ms 分辨率。部分 ROM 对 shell 隐藏其它进程的 /proc/<pid>/stat，脚本会退回：

~~~bash
adb shell ps -p <pid> -o PID,TIME,NAME
~~~

TIME 是累计 CPU 时间（秒级），所以 CPU 列的分辨率会变粗；如果两者都不可用，CPU 记为 n/a，门禁不因 CPU 缺失而误判（耗电与占空仍可判定）。

### 3.3 唤醒次数

从同一个 batterystats 包文本解析：

~~~text
Wakeups: 4 alarms, 12 wakeups
~~~

取其中最大的 wakeup/alarm 计数，避免多个 UID 段重复累加。连续本机音频播放主要由 AudioFlinger 持有唤醒锁，应用 UID 的 wakeup 可能很小甚至为 0；它更多用于**解释差异**，不是门禁主判据。

### 3.4 输出帧数（占空比）

这是本门禁最关键、也最容易踩坑的一项。两条引擎的帧计数来源不同：

**SOURCE（环带）**

- 主播放器走 SourceRenderersFactory 的 TapAudioSink，它把真正交给 delegate（AudioTrack）的帧累计到 AudioOutputFrames。
- 调试口在每个 500ms 采样里带回这个值：

~~~bash
curl -s http://127.0.0.1:18765/state
# JSON: invariantProbe.writtenFrames
~~~

- 轮首/轮尾各取一次，差分即输出帧数。窗口用两次请求的墙钟时间差。

**M3′（原始 SDK 引擎）**

- M3′ 的出声在 LoopAudioSink，不经过 TapAudioSink；/state.invariantProbe.writtenFrames 只覆盖 SOURCE 路线，M3′ 下不可用（routeSource=false 时采样器甚至不会生成 output sample）。
- 因此脚本改读 LoopAudioSink 的 1Hz 诊断日志：

~~~bash
adb logcat -d -v time -s LoopPerf:D
# D/LoopPerf: state=REPLAYING pumpWrites=... pumpCalls=... lastPump=...
~~~

- pumpWrites 是 replay 累计帧数（engine.totalReplayFrames 的增量），在**环完整、进入 REPLAYING 稳态**后约等于每秒 sampleRate 帧。
- 脚本要求 M3′ 的 loop.isRingComplete == true 后才开始计时；环未完整时 B 点会按设计降级直通，pumpWrites 会低估输出，不能用于对比。
- 帧窗口用日志行自带的 MM-DD HH:MM:SS.mmm 时间戳首尾差，避免 reset/日志刷新造成的边界偏差。

**判据**

~~~text
expected = sampleRate * frameWindowSeconds
duty     = frames / expected
fullDuty = duty >= 0.98
~~~

sampleRate 取已确认循环行的 song_loop.sampleRate（文件解码域采样率，不是 AudioTrack 最终输出采样率）。

### 3.5 AudioTrack 活跃时长

- 表里的 Audio_s = frames / sampleRate，即由输出帧反推出的实际出声时长（低于 10 分钟说明有停顿/降级）。
- 轮结束时另外保存 dumpsys audio 与 dumpsys media.audio_flinger，并解析当前 active track 数量，作为“确实还有音频在跑”的现场证据；它们不参与 PASS/FAIL。

---

## 4. 引擎切换与确认

脚本使用已有调试端点：

~~~bash
curl -s -X POST http://127.0.0.1:18765/debug/source-mode \
  -H 'Content-Type: application/json' -d '{"on":true}'   # SOURCE
curl -s -X POST http://127.0.0.1:18765/debug/source-mode \
  -H 'Content-Type: application/json' -d '{"on":false}'  # M3'
~~~

这个端点会同时持久化：

- loop_settings/loop_engine = source / m3
- loop_settings/loop_engine_main = source / m3

脚本随后 am force-stop + am start，让播放器按新引擎重建；再用 run-as 读取 shared_prefs/loop_settings.xml 校验持久化值（不可读时仅告警）。

**引擎确认口径**（重要）：

- /state.loop.engine 只有 loop / exo 两种值，表示“当前是否在循环窗口里”；
- /state.loop.loopPath 才是 source / m3 / none，用来区分哪条引擎。

所以脚本每轮都校验：

~~~text
loop.engine   == "loop"
loop.loopPath == "source"（A 轮） / "m3"（B 轮）
loop.window.songId == SongId
playback.engine == "loop"
~~~

任意一项在预热、计时或轮内采样中跑偏，都会记 Abort 并判失败/错误，而不是继续拿错引擎的数据比较。

---

## 5. 受控变量与执行顺序

脚本对每一轮执行：

1. 设置引擎 -> force-stop -> 启动 App -> 等待 /state 与 controller 连接。
2. 固定 STREAM_MUSIC 音量，并回读校验（media volume --stream 3 --get，失败时退回 dumpsys audio）。
3. 进入循环：POST /loop/enter {songId, play:true}，等到 engine=loop、loopPath 正确、窗口 songId 正确。
4. 若是 M3′，等待 loop.isRingComplete == true；若是 SOURCE，等待位置第一次回绕（证明数据源循环已生效）。
5. 预热 -WarmupSec（默认 20s）。
6. 熄屏：input keyevent KEYCODE_SLEEP，并用 dumpsys power 确认 Display Power: state=OFF（或 Wakefulness=Asleep/Dozing）；确认熄屏后仍在播放且位置继续前进。
7. logcat -c；M3′ 先取 pumpWrites 基线；dumpsys batterystats --reset。
8. 开始计时 -Minutes；每 -SampleSec 取 /state，检查 playing / loopPath / window / pid，记录位置、seam、degrade、ring、frames。
9. 结束：读末态 -> 暂停 -> **立刻**采集 batterystats -> 电量 -> CPU -> logcat -> audio/power dumps。
10. 两轮都完成后计算比值、打印表、写 run.json / summary.txt。

开始时统一控制：

- svc wifi disable、svc data disable（飞行模式未强制，避免影响 USB/adb；如果要更干净可手动开飞行模式）。
- 第三方 App：pm list packages -3 后逐个 am force-stop（-SkipAppStop 可关闭）。
- settings put global stay_on_while_plugged_in 0，防止 USB 供电时屏幕常亮。
- 结束后恢复原 wifi/data/音量/stay_on 设置并亮屏（-LeaveScreenOff 可保留熄屏）。

> 注意：batterystats --reset 会清空设备全局电池历史；不要在共享设备上随意跑。

---

## 6. 如何复现

### 前置

1. 安装 **debug 包**（必须有 LoopDebugServer，端口 8765）。
2. App 已扫描媒体库，目标歌曲可用；没有确认循环时脚本会自动 /analyze + /confirm（可能多花 1-3 分钟）。
3. adb devices 只有目标设备（或显式 -Device）。**必须用 USB adb**：脚本会 svc wifi disable，无线 adb 会自断。
4. 设备最好已解锁/无安全锁；脚本会 KEYCODE_WAKEUP + wm dismiss-keyguard，但安全锁屏下 am start 后播放器初始化可能不稳定。
5. 独占窗口：不要同时跑其它 adb/agent 脚本；不要手动操作设备。

### 运行

~~~powershell
# 设备空闲时，一条命令跑完两轮
powershell -NoProfile -ExecutionPolicy Bypass -File tools/battery-gate.ps1 ^
  -SongId 40904 -Minutes 10 -Volume 8 -EngineOrder source,m3
~~~

指定循环窗口：

~~~powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/battery-gate.ps1 ^
  -SongId 40904 -Ams 19000 -Bms 51000 -Minutes 10 -Volume 8
~~~

先只验证解析器与脚本完整性（不碰设备）：

~~~powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/battery-gate.ps1 -SelfTest
~~~

### 时间预算

- 分析/确认：0-3 分钟（已有确认行时接近 0）。
- 每轮：启动/引擎切换约 10s + 进入循环约 5-20s + 填环/首次回绕约 30-60s + 预热 20s + 10 分钟计时 + 采集约 10s。
- 两轮总计约 **25 分钟**；环很长时可能到 30 分钟。-RingTimeoutSec 是上限保护。

### 失败后看什么

按顺序看：

1. run.log：哪一步 FATAL、哪一轮 Abort。
2. round-X/round.json：Abort、Errors、Frames、FrameSeconds、Duty。
3. round-X/batterystats-*.txt：Estimated power use 里能否找到 Uid u0aXXX:；reset 是否成功。
4. round-X/logcat-loop-perf.txt：M3′ 是否有 pumpWrites= 且单调；SOURCE 可作为 tapDuty writtenFrames= 的旁证。
5. round-X/dumpsys-power.txt / dumpsys-audio*.txt / dumpsys-battery-*.txt：熄屏、音频路由、充电状态。
6. summary.txt / run.json：最终比值与原因。

---

## 7. 已知噪声来源与解释边界

1. **batterystats 是估算模型**：Estimated power use (mAh) 由时间 * 功耗模型计算，不是库仑计差分；不同 ROM 的模型、调度、统计归属不同。只做同机同版本相对比较。
2. **USB 供电/充电状态**：adb 常走 USB，设备可能在充电。估算法仍会计算 UID 耗电，但 dumpsys battery 的电量差不能作为主指标；脚本只用它做参考。
3. **屏幕/息屏策略**：ColorOS 的 AOD、抬腕亮屏、通知亮屏会引入显示耗电，且不一定归属应用 UID。脚本会尝试熄屏并确认，但无法保证整个 10 分钟没有瞬时亮屏；两轮同样条件可部分抵消。
4. **音频路由**：外放 / 有线 / 蓝牙的功放功耗差别很大。脚本固定 STREAM_MUSIC 音量但不会强制路由；跑前请拔掉耳机/断开蓝牙，确保两轮同一路由。dumpsys audio 会留档。
5. **音量与响度**：音量索引相同不代表输出功率完全相同（不同曲目/母带、DAC 增益）；同一首歌可消除这一项。
6. **M3′ 帧计数的 1Hz 边界**：pumpWrites 每秒打一行，首尾行与真实开始/结束有 <=1s 的错位；脚本用日志时间戳窗口对齐，10 分钟下误差 <0.2%，远小于 2% 容差。
7. **SOURCE 帧计数的 500ms 采样**：invariantProbe 每 500ms 更新，首尾各有 <=0.5s 的相位误差；同样远小于容差。
8. **pumpWrites 只统计 replay 帧**：M3′ 在环未完整时会直通，脚本强制 isRingComplete 后才计时；若环一直不完整，脚本报 ERROR 而不是拿低占空数据做比较。
9. **/state 轮询自身**：每 30s 一次 /state 会唤醒应用做少量工作（库计数、快照），两轮频率相同；不要改成 1s 轮询。
10. **系统后台**：即使 force-stop 第三方 App，系统服务、桌面、输入法、推送框架仍在运行；ColorOS 可能在计时中重启某些进程。脚本每 30s 检查 app pid，异常即 Abort。
11. **热与调度**：两轮连续跑会累积热量，后一轮可能被降频。建议两轮之间保持同样间隔（脚本是连续执行）；对严格结论，可重复 2-3 次取中位数/均值。
12. **统计重置时机**：reset 紧贴计时开始，但仍可能有 <1s 的偏差；CPU 与耗电都按同一轮窗口采集。
13. **唤醒次数归属**：AudioFlinger 的唤醒锁常记在 audioserver/系统 UID，应用 UID 的 Wakeups 可能为 0；不要把 0 次理解为没有唤醒。
14. **mAh 量级**：10 分钟连续本机播放通常只有十几 mAh；1.10 的阈值约等于 1-2 mAh。单次结果接近阈值时不要直接下结论，重复测量。
15. **不同构建/不同设备不可比**：这个门禁只回答“同一构建、同一设备、同一首歌、同一窗口下，两条引擎的相对耗电”。
16. **轮次顺序/热积累偏差**：默认先 source 后 m3，先跑的一轮设备更凉。若怀疑顺序影响，用 -EngineOrder m3,source 再跑一次，比较两次的 source/m3 比值是否一致；差距很大时以两次的中位数/均值判断。

---

## 8. 设计取舍

- **为什么用 batterystats 而不是 dumpsys battery 电平差**：10 分钟电平差通常只有 0-1%，分辨率不足；batterystats 的 UID 估算给出 0.01 mAh 量级。
- **为什么用两轮独立 reset**：设备全局统计会持续累积，不 reset 就无法把两轮分开；--charged 只是同一份统计的另一种视图，不能代替 reset。
- **为什么两轮都重启 App**：引擎选择在播放器构建时读取；不重启会出现“数据源未接管、engine=exo”的半切换（项目里已有 /debug/source-mode 的持久化 + 重启约定）。
- **为什么强制等环完整/首次回绕**：M3′ 未填满环时 B 点直通，SOURCE 未接管时仍是普通播放；这两种状态下的“耗电”不能代表连续循环。
- **为什么 110% 而不是 105%**：同机同曲的估算模型噪声、1Hz/500ms 计数边界、ColorOS 后台波动叠加后，105% 容易误报；110% 对应 10 分钟约 1-2 mAh 的余量。若后续多轮数据表明噪声更小，可收紧到 1.05。

---

## 9. 脚本没有做什么

- 不修改 app/src/**，不新增端点；只使用现有 /state、/debug/source-mode、/loop/enter、/player/*、/analyze、/confirm。
- 不安装依赖、不下载任何东西；只依赖 adb 与 Windows PowerShell 5.1+。
- 不自动 root、不改系统分区、不卸载/安装 App。
- 不保证恢复被 force-stop 的第三方 App（它们需要用户自行重新打开）。
- 不在设备被其它 agent 占用时运行；脚本没有设备锁，跑之前请确认独占。
