# 连续循环引擎开销门禁（SOURCE vs M3′，主指标 CPU）

## 1. 目的

同一台设备、同一首歌、同一个 [A,B) 循环窗口、屏幕常亮、音量不变，连续循环播放 10 分钟：

- A 轮：环带引擎（engine=source）
- B 轮：原始 SDK 引擎（engine=m3，M3′ 捕获+重放）
- 比较该应用进程的 CPU 时间，并用输出帧数确认两轮都真的满占空出声。
- 判据：source.cpuMs <= 1.10 × m3.cpuMs，且两轮 duty >= 0.98。

为什么主指标改成 CPU：屏幕常亮时显示功耗占整机大头，会把引擎差异淹没；CPU 是整数计数、没有电池估算噪声，直接反映“解码 + 喂数据 + 重放”的代价，正好是环带与 M3′ 的差别。

> 本门禁不锁屏、不调音量、不调亮度、不切换 Wi-Fi/飞行模式。屏幕必须由用户保持点亮/解锁；脚本只断言并保持 stay_on_while_plugged_in，退出时恢复原值。

---

## 2. 交付物

脚本：tools/engine-cost-gate.ps1

~~~powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/engine-cost-gate.ps1 ^
  -SongId 40169 -Ams 92752 -Bms 173546 -Minutes 10 -EngineOrder source,m3
~~~

参数：

| 参数 | 默认 | 说明 |
|---|---|---|
| -Device | 自动 | adb serial |
| -SongId | 0 | 0 = 自动挑已确认循环的 mp3；显式 A/B 时必须给 |
| -Ams / -Bms | 0 | 显式循环窗口（ms）；0 = 用已有确认行 |
| -Minutes | 10 | 每轮时长 |
| -EngineOrder | source,m3 | 两轮顺序 |
| -WarmupSec | 20 | 稳态后、CPU 基线前的预热 |
| -SampleSec | 30 | 轮内 /state 采样周期 |
| -RingTimeoutSec | 240 | 等 M3′ replay/环或 SOURCE 首次回绕的上限 |
| -SkipAnalyze | off | 未确认循环时直接失败 |
| -SkipAppStop | off | 不 force-stop 其它第三方 App |
| -SelfTest | off | 只跑解析器/门禁自检，不碰设备 |

退出码：0 PASS / 1 FAIL / 2 环境或测量不可用。

产物：e2e-artifacts/engine-cost/<yyyyMMdd-HHmmss>/

~~~text
run.log
summary.txt
run.json
env-before.json
round-A/
  state-start.json
  state-end.json
  proc-before.txt / proc-after.txt
  cpu-before.json / cpu-after.json
  logcat-loop-perf.txt
  dumpsys-power.txt
  round.json
round-B/ ...
~~~

---

## 3. 指标与采集

### 3.1 CPU（主指标）

~~~bash
adb shell cat /proc/<pid>/stat
adb shell getconf CLK_TCK
~~~

- utime（第 14 字段）与 stime（第 15 字段）是进程累计 CPU ticks；前后差分。
- user_ms = Δutime * 1000 / CLK_TCK，sys_ms = Δstime * 1000 / CLK_TCK，cpu_ms = user_ms + sys_ms。
- Android 的 CLK_TCK 通常 100（10ms 分辨率）；脚本用 getconf 读取，失败回退 100。
- 如果 ROM 隐藏 /proc/<pid>/stat，回退 ps -p <pid> -o PID,TIME,NAME 的累计 TIME（秒级）；此时 user/sys 拆分 N/A，但总 CPU 仍可用于门禁。
- CPU 是进程级累计值，包含音频/解码/UI 等所有线程；两轮使用同一进程模型，可直接比较。

### 3.2 上下文切换与缺页

~~~bash
adb shell cat /proc/<pid>/status
~~~

- voluntary_ctxt_switches / nonvoluntary_ctxt_switches 前后差分。
- /proc/<pid>/stat 的 minflt（第 10 字段）与 majflt（第 12 字段）前后差分。
- 这些是解释 CPU 差异的辅助指标，不单独作为 PASS/FAIL 条件。

### 3.3 输出帧数与占空比

SOURCE：主播放器走 TapAudioSink，/state.invariantProbe.writtenFrames 是 AudioTrack 实际接收帧数。

M3′：/state.invariantProbe.writtenFrames 只覆盖 SOURCE。2.3.5 的长窗口只预填前缀，M3′ 在 replay 与直通之间交替，pumpWrites 只覆盖 replay 段（实测约 12s/80s），不能代表整轮出声。

因此 M3′ 的帧数改用 AudioFlinger 输出线程计数器：

~~~bash
adb shell dumpsys media.audio_flinger
# 顶层（两空格缩进）的 "Frames written: N"
~~~

取所有顶层输出线程该计数的前后差分之和；这是 48kHz 输出域的实际写入帧数，覆盖 replay 和直通段。duty 的期望值用 48000 × 秒数。LoopPerf pumpWrites 仅保留在 logcat 中作为 replay 诊断。

稳态判定：

- M3′：isRingComplete == true，或 isReplaying == true，或 seamCount > 0。2.3.5 的长窗口预填只保证前缀，isRingComplete 可能长期为 false；后两者是音频层真正在 replay 的直接证据。
- SOURCE：位置第一次回绕（display position 折返）证明数据源循环生效。

判据：

~~~text
expected = sampleRate * frameWindowSeconds
duty     = frames / expected
fullDuty = duty >= 0.98
~~~

### 3.4 电池 mAh（可选，只读）

脚本在每个轮次开始/结束只读执行：

~~~bash
adb shell dumpsys batterystats com.zjr.hesimusic
~~~

USB 充电时 Estimated power use (mAh) 通常只有 Global、Computed drain: 0，没有 per-UID 行；此时 mAh 记为 N/A，不影响 PASS/FAIL。脚本不会 dumpsys battery unplug、不会 batterystats --reset，不会修改电池/统计状态。

### 3.5 屏幕、音量、亮度、网络

- 屏幕：脚本只断言 Wakefulness != Asleep/Dozing、Display != OFF；不发送 KEYCODE_SLEEP/WAKEUP。为防止 30 分钟超时，每 4 分钟发送一次 KEYCODE_F1（未映射、无副作用，只重置 user activity）；不修改超时设置、不锁屏。
- 防超时：只读取 screen_off_timeout，并断言它大于本次运行所需时长；不修改该设置。若超时太短，脚本在 setup 直接报错，需用户先保持屏幕点亮/调长超时。
- 音量：只用 cmd media_session volume --stream 3 --get 读取；两轮记录，若变化则 FAIL。
- 亮度：读 settings get system screen_brightness 与 screen_brightness_mode；不修改。自动亮度可能使两轮读数不同，只记录、不判失败。
- Wi-Fi/飞行模式：只读记录，不切换。
- 其它第三方 App：可 force-stop（-SkipAppStop 关闭），不影响用户可见的系统设置。

---

## 4. 执行顺序

每轮：

1. 设置引擎 -> am force-stop -> am start -> 等 /state 与 controller 连接。
2. POST /loop/enter {songId, play:true}；等到 loop.engine=loop、loop.loopPath 正确、窗口 songId 正确。
3. 等 replay/环稳态（M3′）或首次回绕（SOURCE）。
4. 读当前音量、亮度、屏幕状态、电池电量；断言屏幕仍亮。
5. 预热 -WarmupSec。
6. logcat -c；M3′ 取 pumpWrites 基线。
7. 取 CPU/status 基线，开始计时 -Minutes。
8. 每 -SampleSec 取 /state，检查 playing / loopPath / window / pid。
9. 轮结束：读末态 -> 暂停（cmd media_session dispatch pause）-> 立即取 CPU/status、logcat、state、电量。

两轮都完成后：CPU 比值 + duty 判据 + 表格 + run.json/summary.txt。

---

## 5. 复现

前置：

1. debug 包（含 LoopDebugServer，8765）。
2. 设备已授权、已解锁且屏幕点亮；脚本不会替你唤醒/解锁。请确保 screen_off_timeout 足够覆盖 25-30 分钟（本机当前 1800000ms=30min）。
3. 目标歌曲已确认循环，或允许脚本自动 /analyze + /confirm。
4. USB adb；脚本不禁 Wi-Fi，无线 adb 也可（但 USB 更稳）。
5. 独占窗口；不要同时跑其它 adb 脚本；不要手动调音量/亮度/锁屏。

命令：

~~~powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/engine-cost-gate.ps1 ^
  -SongId 40169 -Ams 92752 -Bms 173546 -Minutes 10 -EngineOrder source,m3
~~~

只验解析器与门禁逻辑（不碰设备）：

~~~powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/engine-cost-gate.ps1 -SelfTest
~~~

时间：setup ~1-3 分钟 + 每轮 ~11-12 分钟，共 ~25-30 分钟。屏幕常亮会增加热量，建议两轮连续、同条件；怀疑顺序偏差时用 -EngineOrder m3,source 再跑一次比较比值。

---

## 6. 已知噪声

1. CPU 频率/热：屏幕常亮 + 连续播放会累积热量，后一轮可能降频；两轮顺序固定时先跑的一轮更凉。可用反向顺序复测。
2. 自动亮度：亮度值可能变化；脚本只记录，不参与判据。
3. 系统后台：第三方 App 可 force-stop，但系统服务/桌面/输入法仍在跑；每 30s 检查 app pid，异常 Abort。
4. /state 轮询：每 30s 一次，两轮相同；不要降到 1s。
5. M3′ 日志 1Hz 边界：pumpWrites 首尾行与真实窗口有 <=1s 错位；脚本用日志时间戳对齐，10 分钟误差 <0.2%。
6. SOURCE 探针 500ms：invariantProbe 每 500ms 更新，首尾相位误差 <0.5s。
7. CPU 进程级包含所有线程：音频线程、解码线程、JNI 都算在内；这正是“引擎开销”的口径。
8. mAh 通常 N/A：USB 充电 + 只读统计；不要用 mAh 作为本门禁结论。
9. 屏幕功耗不归因到 CPU：因此显示功耗不会淹没 CPU 差异；但会抬高温度，注意热降频。
10. M3′ 的 pumpWrites 只统计 replay 段：2.3.5 前缀预填下 replay/直通交替，因此 M3′ duty 用 AudioFlinger 输出帧计数，不用 pumpWrites。

---

## 7. 设计取舍

- CPU 而不是 mAh：屏幕常亮下显示功耗占主导；CPU 是整数计数，直接反映引擎代价。
- 不锁屏/不调音量/不切网络：尊重用户设备状态；只读校验 screen_off_timeout，不修改任何设置。
- 不 reset batterystats、不 unplug：避免修改系统统计/电池状态；mAh 只作可选记录。
- 等 replay 稳态而不是 isRingComplete：2.3.5 长窗口只预填前缀，isRingComplete 可能永远 false；isReplaying/seamCount 才是音频层实际进入 replay 的证据。
- 110% 阈值：CPU 比 mAh 稳，但仍受调度/热影响；110% 留出余量，收紧前先积累多轮数据。

---

## 8. 首次实测（2026-11-21，OnePlus 8T / Android 14）

- 设备：b1e84dad（KB2000），歌曲 40169，窗口 92752..173546ms（80.8s），音量 0（两轮一致，未修改），亮度 4118（自动，未修改），屏幕常亮。
- A source：CPU 141,660ms（user 127,920 / sys 13,740），ctxt 5280/2109，minflt 83,612 / majflt 0，frames 26,468,820，duty 99.96%。
- B m3：CPU 243,660ms（user 212,420 / sys 31,240），ctxt 5633/1850，minflt 96,496 / majflt 0，frames 28,408,320（AudioFlinger 48kHz），duty 98.61%。
- Source/M3 CPU 比值 = 0.5814（limit 1.10）→ PASS。
- mAh：只读累计差分 0（USB 充电，per-UID 行缺失）→ N/A，不影响结论。
- 产物：e2e-artifacts/engine-cost/20261121-101312/
- 说明：M3′ 在 2.3.5 下 replay 与直通交替（前缀预填），pumpWrites 只覆盖 replay 段；本轮 M3 的 frames 用 AudioFlinger 输出线程计数，因此 duty 略低于 SOURCE（98.61%），仍满足 >=0.98。

---

## 9. 脚本没有做什么

- 不修改 app/src/**、不新增端点；只使用现有 /state、/debug/source-mode、/loop/enter、/player/*、/analyze、/confirm。
- 不发送 KEYCODE_SLEEP/WAKEUP、不调音量、不调亮度、不切 Wi-Fi/飞行模式。
- 不 reset/unplug batterystats、不改电池状态。
- 不恢复被 force-stop 的第三方 App。
- 不负责唤醒/解锁屏幕；屏幕必须由用户保持点亮。
