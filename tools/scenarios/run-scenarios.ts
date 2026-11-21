#!/usr/bin/env bun
/**
 * tools/scenarios/run-scenarios.ts —— 场景矩阵执行器（一条命令跑完，输出表格）。
 *
 * 用法：
 *   bun run tools/scenarios/run-scenarios.ts [选项]
 *   powershell -NoProfile -File tools/scenarios/run-scenarios.ps1 [选项]
 *
 * 选项：
 *   --device <serial>         adb 序列号（默认取第一台在线设备）
 *   --base <url>              调试口地址（默认 http://127.0.0.1:18765）
 *   --out <dir>               产物根目录（默认 e2e-artifacts/scenarios）
 *   --only 1,2,9              只跑指定场景
 *   --list                    列出场景
 *   --skip-ui                 跳过依赖 uiautomator 的场景（6/7/9/10/11/15/16）
 *   --timeout <ms>            单场景硬超时（默认 150000）
 *   --song <id>               指定主场景用歌（有已确认循环）
 *   --song-short <id>         指定分析/接缝/seek 用歌（已确认循环）
 *   --song-unconfirmed <id>   指定未确认循环用歌
 *   --allow-invariant <code>  允许出现的不变量 code（可重复；也可写 tools/scenarios/allow-invariants.json）
 *
 * 前置：debug 包已装、设备已连、LoopDebugServer 在跑（adb forward tcp:18765 tcp:8765
 * 由脚本自动重建）。判据与扩展说明见 docs/testing-scenarios.md。
 */

import * as fs from "node:fs";
import * as path from "node:path";
import { spawnSync } from "node:child_process";
import {
  Adb,
  Api,
  Evidence,
  DEVICE_LOCK_PATHS,
  acquireDeviceLock,
  check,
  releaseDeviceLock,
  selfActivity,
  compactState,
  displayWidth,
  ensureDir,
  nowHHMMSS,
  padEndWidth,
  resolveAdb,
  sleep,
  stamp,
  waitFor,
  writeJson,
} from "./harness";
import type { Cfg, Check, DeviceLockInfo, SongInfo, State } from "./harness";
import { SCENARIOS, SkipError } from "./scenarios";
import type { PreviewSeekResult, Scenario, ScenarioCtx, ScenarioRunResult } from "./scenarios";

// ---------------------------------------------------------------------------
// 参数
// ---------------------------------------------------------------------------

interface Opts {
  device: string;
  base: string;
  out: string;
  only: number[];
  list: boolean;
  skipUi: boolean;
  exclusiveOnly: boolean;
  nonExclusiveOnly: boolean;
  timeoutMs: number;
  song: number;
  songShort: number;
  songUnconfirmed: number;
  songLong: number;
  longOnly: boolean;
  preflightOnly: boolean;
  allowNonSource: boolean;
  noLock: boolean;
  forceLock: boolean;
  allowVisibleStateChanges: boolean;
  allow: string[];
}

function parseArgs(argv: string[]): Opts {
  const opts: Opts = {
    device: "",
    base: "http://127.0.0.1:18765",
    out: path.join("e2e-artifacts", "scenarios"),
    only: [],
    list: false,
    skipUi: false,
    exclusiveOnly: false,
    nonExclusiveOnly: false,
    timeoutMs: 150000,
    song: 0,
    songShort: 0,
    songUnconfirmed: 0,
    songLong: 0,
    longOnly: false,
    preflightOnly: false,
    allowNonSource: false,
    noLock: false,
    forceLock: false,
    allowVisibleStateChanges: false,
    allow: [],
  };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    const next = () => {
      i += 1;
      return argv[i] ?? "";
    };
    if (a === "--device") opts.device = next();
    else if (a === "--base") opts.base = next().replace(/\/$/, "");
    else if (a === "--out") opts.out = next();
    else if (a === "--only") opts.only = next().split(",").map((s) => Number(s.trim())).filter((n) => n > 0);
    else if (a === "--list") opts.list = true;
    else if (a === "--skip-ui") opts.skipUi = true;
    else if (a === "--exclusive") opts.exclusiveOnly = true;
    else if (a === "--non-exclusive") opts.nonExclusiveOnly = true;
    else if (a === "--timeout") opts.timeoutMs = Number(next()) || 150000;
    else if (a === "--song") opts.song = Number(next()) || 0;
    else if (a === "--song-short") opts.songShort = Number(next()) || 0;
    else if (a === "--song-unconfirmed") opts.songUnconfirmed = Number(next()) || 0;
    else if (a === "--song-long") opts.songLong = Number(next()) || 0;
    else if (a === "--long") opts.longOnly = true;
    else if (a === "--allow-non-source") opts.allowNonSource = true;
    else if (a === "--preflight") opts.preflightOnly = true;
    else if (a === "--no-lock") opts.noLock = true;
    else if (a === "--force-lock") opts.forceLock = true;
    else if (a === "--allow-visible-state-changes" || a === "--allow-screen-changes") {
      opts.allowVisibleStateChanges = true;
    }
    else if (a === "--allow-invariant") opts.allow.push(next());
    else if (a === "--help" || a === "-h") {
      printHelp();
      process.exit(0);
    } else if (a.startsWith("--")) {
      console.error("未知参数: " + a);
      process.exit(2);
    }
  }
  return opts;
}

function printHelp(): void {
  console.log(
    [
      "场景矩阵执行器",
      "  bun run tools/scenarios/run-scenarios.ts [--device b1e84dad] [--only 1,2,9] [--skip-ui]",
      "  bun run tools/scenarios/run-scenarios.ts --long   # 长窗口(>200s) 10 分钟稳定性(不放进常规 18 条)",
      "  --allow-non-source  跳过 SOURCE 路线前置检查(仅用于显式研究 M3;出声判据会报不可用)",
      "  --preflight         只做环境/路线自检后退出(不起播、不动播放状态)",
      "  --no-lock           不加设备独占锁(默认会写 /sdcard/.hesi-device-lock.json)",
      "  --force-lock        忽略他人的设备锁/成本锁(危险,仅调试用)",
      "  --allow-visible-state-changes  允许 44/49/51/52 等改用户可见状态(锁屏/熄屏/音量/省电/充电)的场景;默认 SKIP 并排到最后",
      "  powershell -NoProfile -File tools/scenarios/run-scenarios.ps1 ...",
      "产物: e2e-artifacts/scenarios/<时间戳>/report.md + report.json + evidence/*.json",
    ].join("\n"),
  );
}

// ---------------------------------------------------------------------------
// 设备与歌曲
// ---------------------------------------------------------------------------

function pickDevice(adbExe: string, wanted: string): string {
  const out = spawnSync(adbExe, ["devices"], { encoding: "utf8", windowsHide: true });
  const lines = (out.stdout || "").split(/\r?\n/);
  const devices: string[] = [];
  for (const line of lines) {
    const m = line.match(/^(\S+)\s+device\b/);
    if (m) devices.push(m[1]);
  }
  if (wanted) {
    if (!devices.includes(wanted)) throw new Error("设备 " + wanted + " 不在线; 在线: " + (devices.join(",") || "无"));
    return wanted;
  }
  if (devices.length === 0) throw new Error("没有在线 adb 设备");
  return devices[0];
}

function pickSongs(all: SongInfo[], opts: Opts): {
  primary: SongInfo;
  short: SongInfo;
  unconfirmed: SongInfo;
  long: SongInfo | null;
} {
  const byId = (id: number): SongInfo | null => all.find((s) => s.id === id) ?? null;
  const conf = all.filter(
    (s) =>
      s.hasLoop &&
      s.loopConfirmed &&
      s.loopStartMs != null &&
      s.loopEndMs != null &&
      s.durationMs > s.loopEndMs! + 3000,
  );
  const windows = (s: SongInfo) => Number(s.loopEndMs) - Number(s.loopStartMs);

  let primary: SongInfo | null = null;
  if (opts.song > 0) primary = byId(opts.song);
  if (!primary) {
    const cands = conf
      .filter((s) => windows(s) >= 15000 && windows(s) <= 130000 && s.durationMs >= 90000 && s.durationMs <= 320000)
      .sort((a, b) => a.durationMs - b.durationMs || a.id - b.id);
    primary = cands[0] ?? null;
  }
  if (!primary) {
    const cands = conf.sort((a, b) => a.durationMs - b.durationMs || a.id - b.id);
    primary = cands[0] ?? null;
  }
  if (!primary) throw new Error("曲库中没有「已确认循环」的歌;先 /analyze + /confirm 至少一首");

  let short: SongInfo | null = null;
  if (opts.songShort > 0) short = byId(opts.songShort);
  if (!short) {
    // 分析链对 .ogg 会报 "无法读取音频文件"(1258 实测)，优先选 mp3/wav/m4a 的可解码文件。
    const decodable = (s: SongInfo) => /\.(mp3|wav|m4a|aac)$/i.test(s.path);
    const cands = conf
      .filter(
        (s) =>
          s.id !== primary!.id &&
          decodable(s) &&
          windows(s) >= 5000 &&
          s.durationMs >= 40000 &&
          s.durationMs <= 130000,
      )
      .sort((a, b) => a.durationMs - b.durationMs || a.id - b.id);
    short = cands[0] ?? null;
  }
  // 没有第二首可解码的短歌：退化为 primary（mp3 可分析；场景 15 的 A/B 修改会在 finally 恢复）。
  if (!short) short = primary;

  let unconfirmed: SongInfo | null = null;
  if (opts.songUnconfirmed > 0) unconfirmed = byId(opts.songUnconfirmed);
  if (!unconfirmed) {
    const cands = all
      .filter((s) => s.hasLoop && !s.loopConfirmed && s.loopStartMs != null && s.loopEndMs != null)
      .sort((a, b) => a.durationMs - b.durationMs || a.id - b.id);
    unconfirmed = cands[0] ?? null;
  }
  if (!unconfirmed) throw new Error("曲库中没有「未确认循环」的歌;场景 14 无法执行");

  // 长窗口场景用：已确认循环里时长 ≥240s 的最长一首（优先可解码格式）。
  let long: SongInfo | null = null;
  if (opts.songLong > 0) long = byId(opts.songLong);
  if (!long) {
    const decodable = (s: SongInfo) => /\.(mp3|wav|m4a|aac|flac)$/i.test(s.path);
    const longCands = conf
      .filter((s) => s.durationMs >= 240000)
      .sort((a, b) => Number(decodable(b)) - Number(decodable(a)) || b.durationMs - a.durationMs || a.id - b.id);
    long = longCands[0] ?? null;
  }

  return { primary: primary!, short: short!, unconfirmed: unconfirmed!, long };
}

function songLine(s: SongInfo): string {
  return (
    "#" + s.id + " 「" + s.title + "」dur=" + Math.round(s.durationMs / 1000) + "s" +
    (s.loopStartMs != null ? " A=" + s.loopStartMs + " B=" + s.loopEndMs : "")
  );
}

// ---------------------------------------------------------------------------
// 不变量
// ---------------------------------------------------------------------------

function invariantCounts(list: any[]): Map<string, number> {
  const map = new Map<string, number>();
  for (const item of list ?? []) {
    const code = String(item.code ?? "");
    if (!code) continue;
    map.set(code, Number(item.count ?? 1));
  }
  return map;
}

function newInvariantCodes(before: Map<string, number>, after: Map<string, number>): string[] {
  const codes: string[] = [];
  for (const [code, count] of after) {
    const prev = before.get(code) ?? 0;
    if (count > prev) codes.push(code);
  }
  return codes;
}

function loadAllowlist(opts: Opts): Set<string> {
  const set = new Set<string>(opts.allow);
  const file = path.join("tools", "scenarios", "allow-invariants.json");
  if (fs.existsSync(file)) {
    try {
      const json = JSON.parse(fs.readFileSync(file, "utf8"));
      for (const code of json.codes ?? []) set.add(String(code));
    } catch (e: any) {
      console.warn("allow-invariants.json 解析失败: " + e.message);
    }
  }
  return set;
}

// ---------------------------------------------------------------------------
// 超时
// ---------------------------------------------------------------------------

function withTimeout<T>(promise: Promise<T>, ms: number, label: string): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error("场景超时(" + ms + "ms): " + label)), ms);
    promise.then(
      (v) => {
        clearTimeout(timer);
        resolve(v);
      },
      (e) => {
        clearTimeout(timer);
        reject(e);
      },
    );
  });
}

// ---------------------------------------------------------------------------
// 污染检测
// ---------------------------------------------------------------------------

/**
 * 场景期间"外部有人在动设备"的检测：
 * - 每 300ms 读一次 /state，比较 songId/engine/window/screen/playing/repeat 指纹；
 * - 指纹变化时，如果最近 3.5s 内没有本执行器的 POST/tap/HOME/am start，就记一次污染；
 * - 包 lastUpdateTime/versionCode 在场景前后变化（被重新安装）也算污染。
 *
 * 启发式而非证明：自己的动作会短暂"掩盖"外部改动；报告里仍建议独占窗口。
 */
/** 场景前屏幕闸门：唤醒 -> dismiss -> 1s 后复查；仍不 Awake 才判 SKIP（绝不在关屏下继续）。 */
async function ensureScreenAwake(
  adb: Adb,
): Promise<{ awake: boolean; keyguard: boolean; secure: boolean; wakefulness: string }> {
  let s = await adb.wakeAndUnlock();
  if (s.awake) return s;
  await sleep(1000);
  s = await adb.screenState();
  if (s.awake) return s;
  s = await adb.wakeAndUnlock();
  await sleep(1000);
  return adb.screenState();
}

/** 媒体音量闸门：用户要求测试全程 0；不为 0 就强制归零并读回确认。 */
async function ensureMusicVolumeZero(
  adb: Adb,
  fix = true,
): Promise<{ ok: boolean; volume: number | null; raw: string; fixed: boolean }> {
  const before = await adb.musicVolume();
  if (before.volume === 0) return { ok: true, volume: 0, raw: before.raw, fixed: false };
  if (!fix) return { ok: false, volume: before.volume, raw: before.raw, fixed: false };
  const set = await adb.setMusicVolumeZero();
  return { ok: set.ok, volume: set.volume, raw: set.raw, fixed: true };
}

function stateFingerprint(s: State): string {
  const w = s.loop?.window;
  const analysis = selfActivity.analysisHeld()
    ? "analysis-held"
    : (s.analysis?.analyzing === true ? "analyzing" : String(s.analysis?.phase ?? "?")) + ":" + (s.analysis?.songId ?? "null");
  return [
    s.screen,
    s.playback?.songId ?? "null",
    s.playback?.engine ?? "null",
    s.playback?.playing ?? "null",
    s.playback?.repeatMode ?? "null",
    w ? w.songId + "@" + w.loopStartUs + "-" + w.loopEndUs : "none",
    analysis,
  ].join("|");
}

// ---------------------------------------------------------------------------
// 主流程
// ---------------------------------------------------------------------------

const UI_SCENARIOS = new Set([6, 7, 9, 10, 11, 15, 16, 18, 20, 21, 22, 23, 24, 25, 26, 45, 103, 110, 111, 112]);

async function main(): Promise<number> {
  const opts = parseArgs(process.argv.slice(2));
  if (opts.list) {
    for (const s of SCENARIOS) {
      const kind = s.long ? "LONG" : s.visibleStateChanges ? "VISIBLE" : s.exclusive ? "EXCL" : "shared";
      console.log(s.id + "\t" + s.tag + "\t" + kind + "\t" + s.name + "\t" + s.key);
    }
    return 0;
  }

  const adbExe = resolveAdb();
  const device = pickDevice(adbExe, opts.device);
  const cfg: Cfg = {
    device,
    base: opts.base,
    forwardLocal: Number(new URL(opts.base).port || 18765),
    forwardRemote: 8765,
    outDir: opts.out,
    adbExe,
    pkg: "com.zjr.hesimusic",
    scenarioTimeoutMs: opts.timeoutMs,
  };
  const adb = new Adb(adbExe, device);
  const api = new Api(cfg, adb);
  const runId = stamp() + "-" + process.pid;
  let lockInfo: DeviceLockInfo | null = null;
  try {
  if (!opts.noLock) {
    const acquired = await acquireDeviceLock(adb, {
      owner: "scenario-suite",
      runId,
      force: opts.forceLock,
    });
    lockInfo = acquired.info;
    console.log("device lock: " + DEVICE_LOCK_PATHS.unified + " runId=" + runId);
    for (const w of acquired.warnings) console.warn("lock: " + w);
    const cleanupOnSignal = () => {
      releaseDeviceLock(adb, runId)
        .catch(() => null)
        .finally(() => process.exit(130));
    };
    process.once("SIGINT", cleanupOnSignal);
    process.once("SIGTERM", cleanupOnSignal);
  } else {
    console.warn("--no-lock: 未写设备独占锁(其它测量工具无法感知)");
  }

  console.log("== 环境 ==");
  console.log("adb: " + adbExe);
  console.log("device: " + device);
  await adb.forwardEnsure(cfg.forwardLocal, cfg.forwardRemote);

  // 安装/重装后进程可能还没起来：先拉起应用（debug server 随进程启动）。
  let pidNow = await adb.pidof(cfg.pkg);
  if (pidNow == null) {
    console.log("应用未运行,正在启动…");
    await adb.bringForeground(cfg.pkg);
    await sleep(1200);
  }
  // 屏幕前置：任何场景都不允许在关屏状态下跑；亮屏+尝试解锁失败就中止。
  const screen0 = await ensureScreenAwake(adb);
  if (!screen0.awake) {
    console.error("!! 前置检查失败: 屏幕不是 Awake(wakefulness=" + screen0.wakefulness + "),无法驱动 UI;先人工点亮/解锁");
    return 2;
  }
  console.log("screen: awake keyguard=" + screen0.keyguard + " (" + screen0.wakefulness + ")");
  if (screen0.keyguard) {
    console.error(
      "!! 前置检查失败: 设备处于锁屏(keyguard showing, secure=" + screen0.secure + "),wake+dismiss 未能解锁。",
    );
    console.error("   需要用户在设备上解锁一次;测试期间请关闭自动锁屏/让屏幕保持常亮。");
    console.error("   （ harness 不会尝试 PIN/密码;锁屏下 am start/uiautomator 全部不可用）");
    return 2;
  }

  // 用户要求：测试全程媒体音量为 0。不为 0 就 cmd audio 归零并读回；仍失败就拒绝开跑。
  const volume0 = await ensureMusicVolumeZero(adb);
  if (!volume0.ok) {
    console.error("!! 前置检查失败: STREAM_MUSIC 音量不是 0(volume=" + String(volume0.volume) + "),无法静音运行");
    console.error("   手工执行: adb -s " + cfg.device + " shell cmd audio volume --stream 3 --set 0");
    return 2;
  }
  console.log("audio: STREAM_MUSIC volume=0" + (volume0.fixed ? " (已强制归零)" : ""));
  const fgOk = await adb.bringForeground(cfg.pkg);
  if (!fgOk) console.warn("警告: 无法把应用带到前台,UI 场景可能失败");
  for (let i = 0; i < 20; i++) {
    pidNow = await adb.pidof(cfg.pkg);
    if (pidNow != null) break;
    await sleep(500);
  }
  if (pidNow == null) throw new Error("应用进程不存在(安装后未启动?)");

  let initial: State | null = null;
  for (let i = 0; i < 10 && !initial; i++) {
    try {
      initial = await api.state();
    } catch (e: any) {
      if (i === 9) throw new Error("调试口不可达: " + e.message + " (确认 debug 包在跑且 forward 正常)");
      await adb.forwardEnsure(cfg.forwardLocal, cfg.forwardRemote);
      await sleep(700);
    }
  }
  const version = await adb.versionInfo(cfg.pkg);
const pid0 = await adb.pidof(cfg.pkg);

  // ---- 前置检查：主播放器必须是 SOURCE 路线 ----
  // 否则 writtenFrames 不更新、预览也没有 SOURCE seek 桥，跑 4 分钟才发现全是“探针不可用”。
  let routeSource: boolean | null = initial!.invariantProbe
    ? initial!.invariantProbe.routeSource === true
    : null;
  if (routeSource === null) {
    for (let i = 0; i < 6 && routeSource === null; i++) {
      await sleep(500);
      try {
        const s = await api.state();
        if (s.invariantProbe) routeSource = s.invariantProbe.routeSource === true;
      } catch {
        // 继续等采样器
      }
    }
  }
  if (routeSource === false) {
    let prefs = "";
    try {
      prefs = (await adb.shell("run-as " + cfg.pkg + " cat shared_prefs/loop_settings.xml")).trim();
    } catch {
      // 非 debuggable 读不到 prefs 不影响结论
    }
    console.error("");
    console.error("!! 前置检查失败: 主播放器不在 SOURCE 路线 (/state.invariantProbe.routeSource=false)");
    console.error("   pkg=" + cfg.pkg + " versionCode=" + version.versionCode + " versionName=" + version.versionName);
    if (prefs) console.error("   持久化偏好: " + prefs.replace(/\s+/g, " ").slice(0, 320));
    console.error("   M3′ 路线下 writtenFrames 不更新、预览也没有 SOURCE seek 桥，出声判据无法判定。");
    console.error("   修复(确认构建包含 SOURCE 后):");
    console.error("     1) Invoke-RestMethod -Method Post -Uri http://127.0.0.1:" + cfg.forwardLocal + "/debug/source-mode -ContentType application/json -Body '{\"on\":true}'");
    console.error("     2) adb -s " + cfg.device + " shell am force-stop " + cfg.pkg);
    console.error("     3) adb -s " + cfg.device + " shell am start -W -n " + cfg.pkg + "/.MainActivity");
    console.error("     4) GET /state 验证 invariantProbe.routeSource=true;打开结果页后 /debug/preview.snapshot.engine=SOURCE");
    if (!opts.allowNonSource) {
      console.error("   仅做位置/UI 类场景可在 M3 上跑: 加 --allow-non-source(出声判据会报“探针不可用”)");
      return 2;
    }
    console.error("   --allow-non-source: 继续,但出声判据会报“探针不可用”。");
  } else if (routeSource === null) {
    if (opts.preflightOnly) {
      console.error("!! 前置自检失败: 3s 内没采到 invariantProbe.routeSource,无法确认 SOURCE");
      return 2;
    }
    console.warn("警告: 3s 内没采到 invariantProbe.routeSource,无法确认 SOURCE;继续(出声判据可能不可用)。");
  } else {
    console.log("routeSource: true (主播放器 SOURCE)");
  }
  if (opts.preflightOnly) {
    console.log("preflight OK: routeSource=true, pkg=" + cfg.pkg + " v" + version.versionCode + "/" + version.versionName);
    return 0;
  }
  const runStartEpochSec = Math.floor(Date.now() / 1000) - 5;
  const startedAt = new Date();
  const runDir = path.join(opts.out, stamp());
  ensureDir(path.join(runDir, "evidence"));
  console.log("pkg: " + cfg.pkg + " versionCode=" + version.versionCode + " versionName=" + version.versionName);
  console.log("pid: " + pid0 + "  screen=" + initial!.screen);
  console.log("foreground: " + (await adb.foregroundActivity()));

  const all = await api.songsAll();
  const songs = pickSongs(all, opts);
  console.log("primary: " + songLine(songs.primary));
  console.log("short:   " + songLine(songs.short));
  console.log("unconf:  " + songLine(songs.unconfirmed));
  console.log("long:    " + (songs.long ? songLine(songs.long) : "(没有 ≥240s 的已确认循环歌;场景 19 会 SKIP)"));

  const sampleRateMap = new Map<number, number>();
  for (const s of all) if (s.sampleRate != null) sampleRateMap.set(s.id, s.sampleRate);
  const sampleRateFor = (songId: number): number => sampleRateMap.get(songId) ?? 44100;

  const allowSet = loadAllowlist(opts);
  const baselineRepeat = initial.playback?.repeatMode ?? null;
  const baselineShuffle = initial.playback?.shuffle ?? null;
  const baselineInv = invariantCounts(initial.invariants ?? []);
  const pendingDecisions: string[] = [];

  // ---- ctx ----
  let uiCache: { at: number; labels: string[] } | null = null;
  const evidenceHolder = { ev: new Evidence("boot") };

  const ctx: ScenarioCtx = {
    cfg,
    api,
    adb,
    songs,
    get evidence() {
      return evidenceHolder.ev;
    },
    log: (message: string) => console.log("    " + message),
    sampleRateFor,
    async resetForMain() {
      await api.post("/loop/exit").catch(() => null);
      await api.post("/navigate", { route: "about" }).catch(() => null);
      await sleep(350);
    },
    async ensureLoopPage(songId: number) {
      const route = "loop/" + songId;
      const nav = await api.post("/navigate", { route });
      if (nav.status !== 200) throw new Error("/navigate " + route + " -> " + nav.status + " " + nav.text.slice(0, 160));
      const ready = await waitFor(
        async () => api.preview(),
        (p) => p.available === true,
        20000,
        150,
      );
      if (!ready.ok) {
        const reason = ready.value?.reason ?? "timeout";
        if (reason === "endpoint-missing") {
          throw new SkipError("当前 build 没有 GET /debug/preview(issue 00023 之前的包),无法观测结果页");
        }
        throw new Error("循环页 20s 内未就绪: " + reason);
      }
      uiCache = null;
      await sleep(300);
    },
    async previewReady(timeoutMs = 10000) {
      const got = await waitFor(async () => api.preview(), (p) => p.available === true, timeoutMs, 120);
      if (!got.ok || !got.value || !got.value.snapshot) {
        const reason = got.value?.reason ?? "timeout";
        if (reason === "endpoint-missing") throw new SkipError("当前 build 没有 /debug/preview");
        throw new SkipError("结果页预览未打开: " + reason);
      }
      return got.value.snapshot;
    },
    async previewSeek(positionMs: number): Promise<PreviewSeekResult> {
      const before = await api.preview();
      if (!before.available || !before.snapshot) {
        if (before.reason === "endpoint-missing") throw new SkipError("当前 build 没有 /debug/preview");
        throw new SkipError("结果页预览未打开: " + (before.reason ?? "unknown"));
      }
      const snapBefore = before.snapshot;
      // 预览也必须在 SOURCE 路线，否则 seek 桥不存在（或 M3 上位置口径不同）。
      // 只看 engine=SOURCE：sourceActive 在页面刚起播/换源后的短暂窗口里可能还是 false，
      // 但 SOURCE player 已创建、seek 桥可用；用它做闸门会误 SKIP。
      if (snapBefore.engine !== "SOURCE") {
        throw new SkipError(
          "预览引擎不是 SOURCE(engine=" + snapBefore.engine + ");用 /debug/source-mode {on:true} + force-stop/start 切回后重跑",
        );
      }
      const r = await api.post("/debug/preview-seek", { positionMs }, 10000);
      if (r.status === 404) throw new SkipError("当前 build 没有 POST /debug/preview-seek");
      if (r.status === 409) throw new SkipError("预览 seek 桥未注册: " + (r.json?.error ?? ""));
      if (r.status !== 200 || !r.json) {
        throw new Error("/debug/preview-seek -> " + r.status + " " + r.text.slice(0, 160));
      }
      const j = r.json;
      let latencyToFramesMs: number | null = null;
      const framesDelta = Number(j.framesDelta ?? 0);
      if (framesDelta > 0) {
        latencyToFramesMs = j.applyMs == null ? null : Number(j.applyMs);
      } else {
        const t0 = Date.now();
        while (Date.now() - t0 < 1200) {
          const p = await api.preview();
          if (p.available && Number(p.snapshot?.outputFrames ?? 0) > Number(snapBefore.outputFrames ?? 0)) {
            latencyToFramesMs = Date.now() - t0;
            break;
          }
          await sleep(20);
        }
      }
      return {
        ok: true,
        via: "endpoint",
        applyMs: j.applyMs == null ? null : Number(j.applyMs),
        advanceMs: j.advanceMs == null ? null : Number(j.advanceMs),
        stages: j.stages ?? null,
        playoutMs: j.stages && j.stages.playoutMs != null ? Number(j.stages.playoutMs) : null,
        latencyToFramesMs,
        framesDelta,
        displayAfterMs: j.displayAfterMs == null ? null : Number(j.displayAfterMs),
        playerPositionAfterMs: j.playerPositionAfterMs == null ? null : Number(j.playerPositionAfterMs),
        isPlayingAfter: j.isPlayingAfter == null ? null : Boolean(j.isPlayingAfter),
        mode: j.mode == null ? null : String(j.mode),
        outsideLoopAfter: j.outsideLoopAfter == null ? null : Boolean(j.outsideLoopAfter),
        windowA_ms: j.windowAfterA_ms == null ? null : Number(j.windowAfterA_ms),
        windowB_ms: j.windowAfterB_ms == null ? null : Number(j.windowAfterB_ms),
        sourceWindowA_ms: j.sourceWindowAfterA_ms == null ? null : Number(j.sourceWindowAfterA_ms),
        sourceWindowB_ms: j.sourceWindowAfterB_ms == null ? null : Number(j.sourceWindowAfterB_ms),
        raw: j,
      };
    },
    async uiTap(label: string): Promise<number> {
      let nodes: { text: string; bounds: { left: number; top: number; right: number; bottom: number } }[] = [];
      let node: (typeof nodes)[number] | null = null;
      let lastErr = "";
      for (let attempt = 0; attempt < 3 && !node; attempt++) {
        if (attempt > 0) await sleep(400);
        const dump = await adb.uiDump();
        if (dump.error) {
          lastErr = "uiautomator dump 失败: " + dump.error;
          continue;
        }
        nodes = dump.nodes
          .map((n) => ({ text: n.text.trim(), bounds: n.bounds }))
          .filter((n) => n.text.length > 0);
        node =
          nodes.find((n) => n.text === label) ??
          nodes.find((n) => n.text.includes(label)) ??
          null;
        if (!node) lastErr = "UI 找不到「" + label + "」";
      }
      if (!node) {
        throw new Error(lastErr + "; 现有文本: " + nodes.map((n) => n.text).slice(0, 24).join(" | "));
      }
      // 页面刚导航时布局还会下沉/重排：连续两帧 bounds 稳定后才点，避免点到过渡态坐标。
      let stable = 0;
      for (let attempt = 0; attempt < 3 && stable < 1; attempt++) {
        await sleep(500);
        const dump2 = await adb.uiDump();
        if (dump2.error) break;
        const nodes2 = dump2.nodes
          .map((n) => ({ text: n.text.trim(), bounds: n.bounds }))
          .filter((n) => n.text.length > 0);
        const node2 = nodes2.find((n) => n.text === label) ?? nodes2.find((n) => n.text.includes(label)) ?? null;
        if (!node2) break;
        const dx = Math.abs(node2.bounds.top - node.bounds.top) + Math.abs(node2.bounds.left - node.bounds.left);
        node = node2;
        nodes = nodes2;
        if (dx <= 4) stable += 1;
      }
      uiCache = { at: Date.now(), labels: nodes.map((n) => n.text) };
      const x = (node.bounds.left + node.bounds.right) / 2;
      const y = (node.bounds.top + node.bounds.bottom) / 2;
      await adb.tap(x, y);
      const tappedAt = Date.now();
      evidenceHolder.ev.step("tap", { label, bounds: node.bounds, tappedAt });
      await sleep(80);
      return tappedAt;
    },
    uiLabels() {
      return uiCache ? uiCache.labels : [];
    },
    async foregroundForeground() {
      return adb.bringForeground(cfg.pkg);
    },
  };

  const refreshUi = async (): Promise<string[]> => {
    const dump = await adb.uiDump();
    uiCache = {
      at: Date.now(),
      labels: dump.nodes.map((n) => n.text.trim()).filter((t) => t.length > 0),
    };
    return uiCache.labels;
  };
  (ctx as any).__refreshUi = refreshUi;

  const ensureLoopPageWithUi = ctx.ensureLoopPage;
  ctx.ensureLoopPage = async (songId: number) => {
    await ensureLoopPageWithUi(songId);
    try {
      await sleep(600);
      await refreshUi();
    } catch (e: any) {
      ctx.log("UI dump 预热失败(不影响后续 uiTap 重试): " + e.message);
    }
  };

  // ---- 场景执行 ----
  if (opts.exclusiveOnly && opts.nonExclusiveOnly) throw new Error("--exclusive 与 --non-exclusive 不能同时用");
  if (opts.longOnly && (opts.exclusiveOnly || opts.nonExclusiveOnly)) {
    throw new Error("--long 不能与 --exclusive/--non-exclusive 同时用");
  }
  const explicitOnly = opts.only.length > 0;
  const selected = SCENARIOS.filter((s) => {
    if (explicitOnly && !opts.only.includes(s.id)) return false;
    if (opts.longOnly && s.long !== true) return false;
    // 常规跑（无 --only/--long）默认排除长稳定性场景。
    if (!opts.longOnly && s.long === true && !explicitOnly) return false;
    if (opts.exclusiveOnly && s.exclusive !== true) return false;
    if (opts.nonExclusiveOnly && s.exclusive === true) return false;
    return true;
  });
  if (selected.length === 0) throw new Error("没有匹配的场景;--list 查看编号");
  // 会改用户可见状态的场景永远排最后（即使 --only 指定多条）。
  selected.sort((a, b) => Number(!!a.visibleStateChanges) - Number(!!b.visibleStateChanges));
  if (opts.exclusiveOnly) {
    console.log("独占组: " + selected.map((s) => "#" + s.id).join(",") + " (跑之前确认设备没有其它 agent 在操作)");
  }
  if (opts.longOnly) {
    console.log("长窗口组: " + selected.map((s) => "#" + s.id).join(",") + " (约 10 分钟,确认设备可独占)");
  }

  interface ScenarioResult {
    id: number;
    key: string;
    name: string;
    tag: string;
    status: "PASS" | "FAIL" | "SKIP" | "CONTAMINATED" | "PENDING";
    durationMs: number;
    checks: Check[];
    summary: string;
    skipReason?: string;
    pendingDecision: string[];
    evidenceFile: string;
    before: any;
    after: any;
    newInvariants: string[];
    pidBefore: number | null;
    pidAfter: number | null;
    contamination: { at: string; before: string; after: string }[];
    screenRestore?: { awake: boolean; keyguard: boolean; secure: boolean; wakefulness: string } | null;
    volumeRestore?: { ok: boolean; volume: number | null; fixed: boolean } | null;
  }
  const results: ScenarioResult[] = [];
  let abortRun: string | null = null;

  for (const scenario of selected) {
    if (abortRun) break;
    evidenceHolder.ev = new Evidence(scenario.key);
    if (opts.skipUi && UI_SCENARIOS.has(scenario.id)) {
      results.push({
        id: scenario.id,
        key: scenario.key,
        name: scenario.name,
        tag: scenario.tag,
        status: "SKIP",
        durationMs: 0,
        checks: [],
        summary: "",
        skipReason: "--skip-ui",
        pendingDecision: [],
        evidenceFile: "",
        before: null,
        after: null,
        newInvariants: [],
        pidBefore: null,
        pidAfter: null,
        contamination: [],
      });
      console.log("#" + scenario.id + " SKIP " + scenario.name + " (--skip-ui)");
      continue;
    }
    if ((scenario.visibleStateChanges || scenario.screenChanges) && !opts.allowVisibleStateChanges) {
      results.push({
        id: scenario.id,
        key: scenario.key,
        name: scenario.name,
        tag: scenario.tag,
        status: "SKIP",
        durationMs: 0,
        checks: [],
        summary: "",
        skipReason:
          "默认禁用: 会改用户可见状态(锁屏/熄屏/音量/省电/充电);确认设备可被占用后加 --allow-visible-state-changes",
        pendingDecision: [],
        evidenceFile: "",
        before: null,
        after: null,
        newInvariants: [],
        pidBefore: null,
        pidAfter: null,
        contamination: [],
        screenRestore: null,
      });
      console.log("#" + scenario.id + " SKIP " + scenario.name + " (visible-state; 需要 --allow-visible-state-changes)");
      continue;
    }

    // 每个场景执行前都确认屏幕是亮的；关屏下继续跑会把后面全部拖挂。
    const screenReady = await ensureScreenAwake(adb);
    if (screenReady.keyguard && screenReady.secure) {
      abortRun = "secure keyguard detected before #" + scenario.id + " (wake+dismiss 无法解除);已中止整轮";
      results.push({
        id: scenario.id,
        key: scenario.key,
        name: scenario.name,
        tag: scenario.tag,
        status: "SKIP",
        durationMs: 0,
        checks: [],
        summary: "",
        skipReason: "aborted: " + abortRun,
        pendingDecision: [],
        evidenceFile: "",
        before: null,
        after: null,
        newInvariants: [],
        pidBefore: null,
        pidAfter: null,
        contamination: [],
        screenRestore: screenReady,
      });
      console.log("#" + scenario.id + " SKIP " + scenario.name + " (ABORT: secure keyguard)");
      break;
    }
    if (!screenReady.awake) {
      results.push({
        id: scenario.id,
        key: scenario.key,
        name: scenario.name,
        tag: scenario.tag,
        status: "SKIP",
        durationMs: 0,
        checks: [],
        summary: "",
        skipReason: "screen off, cannot drive UI (wakefulness=" + screenReady.wakefulness + ")",
        pendingDecision: [],
        evidenceFile: "",
        before: null,
        after: null,
        newInvariants: [],
        pidBefore: null,
        pidAfter: null,
        contamination: [],
        screenRestore: screenReady,
      });
      console.log("#" + scenario.id + " SKIP " + scenario.name + " (screen off: " + screenReady.wakefulness + ")");
      continue;
    }

    console.log("");
    console.log("== #" + scenario.id + " " + scenario.name + " ==");
    const pidBefore = await adb.pidof(cfg.pkg);
    const beforeState = await api.state().catch(() => null);
    const invBefore = invariantCounts(beforeState?.invariants ?? []);
    const before = compactState(beforeState ?? initial!);
    const t0 = Date.now();
    let checks: Check[] = [];
    let summary = "";
    let pending: string[] = [];
    let status: "PASS" | "FAIL" | "SKIP" | "CONTAMINATED" | "PENDING" = "PASS";
    let skipReason: string | undefined;
    const contamination: { at: string; before: string; after: string }[] = [];
    let lastFp = beforeState ? stateFingerprint(beforeState) : "";
    let screenRestore: { awake: boolean; keyguard: boolean; secure: boolean; wakefulness: string } | null = null;
    let volumeRestore: { ok: boolean; volume: number | null; fixed: boolean } | null = null;
    let monitorStop = false;
    const monitor = (async () => {
      while (!monitorStop) {
        await sleep(300);
        if (monitorStop) break;
        try {
          const s = await api.state();
          const fp = stateFingerprint(s);
          if (lastFp && fp !== lastFp) {
            const analysisRelated = /analysis-held|analyzing/.test(lastFp) || /analysis-held|analyzing/.test(fp);
            if (!selfActivity.recent(3500) && !analysisRelated) {
              contamination.push({ at: nowHHMMSS(), before: lastFp, after: fp });
            }
            lastFp = fp;
          } else if (!lastFp) {
            lastFp = fp;
          }
        } catch {
          // 读不到 /state 不作为污染证据
        }
      }
    })();

    try {
      const res: ScenarioRunResult = await withTimeout(
        scenario.run(ctx),
        scenario.timeoutMs ?? cfg.scenarioTimeoutMs,
        scenario.name,
      );
      checks = res.checks;
      summary = res.summary;
      pending = res.pendingDecision ?? [];
    } catch (e: any) {
      if (e instanceof SkipError) {
        status = "SKIP";
        skipReason = e.message;
      } else {
        status = "FAIL";
        checks = [check("执行异常", false, String(e?.message ?? e), e?.stack ? String(e.stack).split("\n")[1]?.trim() : undefined)];
      }
    } finally {
      monitorStop = true;
      await monitor;
      // 每个场景 finally 都恢复屏幕（幂等、约 200ms）：绝不让用户手机留在灭屏/锁屏。
      try {
        screenRestore = await adb.wakeAndUnlock();
        evidenceHolder.ev.step("screen-restore", screenRestore);
        if (screenRestore.keyguard && screenRestore.secure) {
          abortRun = "secure keyguard detected after #" + scenario.id + " (wake+dismiss 无法解除);已中止整轮";
        }
      } catch {
        screenRestore = null;
      }
      try {
        volumeRestore = await ensureMusicVolumeZero(adb);
        evidenceHolder.ev.step("volume-restore", volumeRestore);
      } catch {
        volumeRestore = null;
      }
    }
    const durationMs = Date.now() - t0;
    const afterState = await api.state().catch(() => null);
    const invAfter = invariantCounts(afterState?.invariants ?? []);
    const newInv = newInvariantCodes(invBefore, invAfter);
    const disallowed = newInv.filter((c) => !allowSet.has(c));
    if (status !== "SKIP") {
      checks.push(
        check(
          "无新增不变量 violation",
          disallowed.length === 0,
          disallowed.length > 0
            ? "NEW: " + disallowed.map((c) => c + "x" + invAfter.get(c)).join(", ")
            : newInv.length > 0
              ? "none (allowed: " + newInv.map((c) => c + "x" + invAfter.get(c)).join(", ") + ")"
              : "none",
          disallowed.length > 0 ? "运行期不变量触发;证据里含 /state.invariants 与 logcat LoopInvariant" : undefined,
        ),
      );
    }
    const pidAfter = await adb.pidof(cfg.pkg);
    if (status !== "SKIP" || pidBefore !== pidAfter) {
      checks.push(
        check(
          "进程存活(未重启)",
          pidBefore !== null && pidAfter !== null && pidBefore === pidAfter,
          "pid " + pidBefore + " -> " + pidAfter,
          pidBefore !== pidAfter ? "场景执行中应用进程重启(崩溃/被系统杀/被重新安装)" : undefined,
        ),
      );
    }
    // OOM 不一定会让 pid 立刻变化：必须单独扫 crash buffer 的 OutOfMemoryError。
    const oomLines = await adb.oomLinesSince(Math.floor(t0 / 1000) - 1, cfg.pkg);
    if (oomLines.length > 0 || status !== "SKIP") {
      checks.push(
        check(
          "无 OutOfMemoryError",
          oomLines.length === 0,
          oomLines.length > 0 ? oomLines.slice(-2).join(" | ").slice(0, 400) : "none",
          oomLines.length > 0 ? "crash buffer 出现 OOM:按纪律直接判 FAIL(不能只看 pid 存活)" : undefined,
        ),
      );
    }
    const versionAfter = await adb.versionInfo(cfg.pkg).catch(() => null);
    if (
      versionAfter &&
      (versionAfter.versionCode !== version.versionCode || versionAfter.lastUpdateTime !== version.lastUpdateTime)
    ) {
      contamination.push({
        at: nowHHMMSS(),
        before: "pkg " + version.versionCode + " / " + version.lastUpdateTime,
        after: "pkg " + versionAfter.versionCode + " / " + versionAfter.lastUpdateTime,
      });
    }
    if (volumeRestore && !volumeRestore.ok) {
      checks.push(
        check(
          "媒体音量恢复为 0",
          false,
          "volume=" + String(volumeRestore.volume),
          "用户要求测试全程静音;场景结束时音量不是 0,先 cmd audio volume --stream 3 --set 0 再重跑",
        ),
      );
    }
    if (screenRestore && (!screenRestore.awake || screenRestore.keyguard)) {
      checks.push(
        check(
          "屏幕已恢复亮屏/无锁屏",
          false,
          "awake=" + screenRestore.awake + " keyguard=" + screenRestore.keyguard + " wakefulness=" + screenRestore.wakefulness,
          "不得让用户设备留在锁屏/灭屏;先人工解锁,再重跑本场景",
        ),
      );
    }
    const realFailures = checks.filter((c) => !c.pass && !c.pending);
    if (realFailures.length > 0) status = "FAIL";
    else if (checks.some((c) => c.pending)) status = "PENDING";
    if (contamination.length > 0 && status !== "SKIP") status = "CONTAMINATED";
    if (pending.length > 0) pendingDecisions.push(...pending);

    const logSlice =
      status === "FAIL" || status === "CONTAMINATED" ? await adb.logcatSlice(nowHHMMSS(new Date(t0))) : [];
    const evidenceFile = path.join(runDir, "evidence", String(scenario.id).padStart(2, "0") + "-" + scenario.key + ".json");
    const evidenceJson = evidenceHolder.ev.toJSON({
      scenarioId: scenario.id,
      name: scenario.name,
      status,
      durationMs,
      checks,
      summary,
      skipReason: skipReason ?? null,
      pidBefore,
      pidAfter,
      before,
      after: afterState ? compactState(afterState) : null,
      newInvariants: newInv,
      contamination,
      logcat: logSlice,
    });
    writeJson(evidenceFile, evidenceJson);

    const icon = status;
    const failed = checks.filter((c) => !c.pass && !c.pending);
    const pendingChecks = checks.filter((c) => c.pending);
    console.log("   " + icon + " " + durationMs + "ms " + (summary || skipReason || ""));
    for (const f of failed) console.log("     x " + f.name + ": " + f.detail);
    for (const p of pendingChecks.slice(0, 6)) console.log("     ~ 待依赖[" + (p.dependency ?? "?") + "] " + p.name + ": " + p.detail);
    if (status === "CONTAMINATED") {
      console.log("     ! 外部操作: " + contamination.slice(0, 3).map((c) => c.before + " -> " + c.after).join(" ; "));
    }

    results.push({
      id: scenario.id,
      key: scenario.key,
      name: scenario.name,
      tag: scenario.tag,
      status,
      durationMs,
      checks,
      summary,
      skipReason,
      pendingDecision: pending,
      evidenceFile,
      before,
      after: afterState ? compactState(afterState) : null,
      newInvariants: newInv,
      pidBefore,
      pidAfter,
      contamination,
      screenRestore,
      volumeRestore,
    });
    if (abortRun) break;
  }

  // ---- 全局崩溃/OOM 检查 ----
  const crashScan = await adb.crashScanSince(runStartEpochSec, cfg.pkg);
  const crash = [...crashScan.fatal, ...crashScan.oom, ...crashScan.anr];
  const finalPid = await adb.pidof(cfg.pkg);
  const totalMs = Date.now() - startedAt.getTime();

  // ---- 收尾：尽量把设备放回普通播放状态 ----
  try {
    await api.post("/loop/exit");
    if (baselineRepeat) await api.post("/player/repeat", { mode: baselineRepeat });
    if (baselineShuffle != null) await api.post("/player/shuffle", { on: baselineShuffle });
    await api.post("/navigate", { route: "about" });
  } catch (e: any) {
    console.warn("收尾失败: " + e.message);
  }
  // 收尾也要保证：屏幕亮着、媒体音量 0（用户手机不能被留在灭屏/有声状态）。
  const finalScreen = await adb.wakeAndUnlock().catch(() => null);
  const finalVolume = await ensureMusicVolumeZero(adb).catch(() => null);
  if (finalScreen && !finalScreen.awake) console.warn("!! 收尾后屏幕仍不是 Awake: " + finalScreen.wakefulness);
  if (finalVolume && !finalVolume.ok) console.warn("!! 收尾后媒体音量不是 0: " + String(finalVolume.volume));

  // ---- 报告 ----
  const failedResults = results.filter((r) => r.status === "FAIL");
  const skipResults = results.filter((r) => r.status === "SKIP");
  const passResults = results.filter((r) => r.status === "PASS");
  const contaminatedResults = results.filter((r) => r.status === "CONTAMINATED");
  const pendingResults = results.filter((r) => r.status === "PENDING");

  const report = {
    generatedAt: new Date().toISOString(),
    device,
    package: cfg.pkg,
    versionCode: version.versionCode,
    versionName: version.versionName,
    lastUpdateTime: version.lastUpdateTime,
    pidStart: pid0,
    pidEnd: finalPid,
    adbCalls: adb.calls,
    totalMs,
    songs,
    baselineInvariants: Array.from(baselineInv),
    allowInvariants: Array.from(allowSet),
    crash,
    oom: crashScan.oom,
    anr: crashScan.anr,
    finalScreen,
    finalVolume,
    aborted: abortRun,
    pendingDecisions,
    results: results.map((r) => ({
      ...r,
      failedChecks: r.checks.filter((c) => !c.pass),
    })),
  };
  writeJson(path.join(runDir, "report.json"), report);

  const md = buildMarkdown(report, results, runDir);
  fs.writeFileSync(path.join(runDir, "report.md"), md, "utf8");

  printTable(results, totalMs);
  console.log("");
  console.log(
    "结果: PASS " + passResults.length + " / FAIL " + failedResults.length + " / SKIP " + skipResults.length +
      " / CONTAMINATED " + contaminatedResults.length + " / PENDING " + pendingResults.length +
      "  (总耗时 " + Math.round(totalMs / 1000) + "s, adb 调用 " + adb.calls + " 次)",
  );
  if (contaminatedResults.length > 0) {
    console.log("注意: CONTAMINATED = 场景执行期间检测到外部改动(另一个 agent/重装),结果不可信;独占窗口重跑。");
  }
  if (crashScan.oom.length > 0) {
    console.log("!! OutOfMemoryError 证据(" + crashScan.oom.length + " 条) — 必须修复:");
    for (const line of crashScan.oom.slice(-6)) console.log("   " + line.slice(0, 220));
  }
  if (abortRun) {
    console.log("!! 整轮中止: " + abortRun);
  }
  if (crash.length > 0) {
    console.log("!! 崩溃/ANR 证据(" + crash.length + " 条):");
    for (const line of crash.slice(-8)) console.log("   " + line.slice(0, 220));
  }
  if (contaminatedResults.length > 0) {
    console.log("");
    console.log("污染清单:");
    for (const r of contaminatedResults) {
      console.log("  #" + r.id + " " + r.name + " -> " + r.contamination.slice(0, 2).map((c: any) => c.before + " => " + c.after).join(" ; "));
    }
  }
  if (failedResults.length > 0) {
    console.log("");
    console.log("失败清单:");
    for (const r of failedResults) {
      const first = r.checks.find((c) => !c.pass);
      console.log("  #" + r.id + " " + r.name + " -> " + (first ? first.name + ": " + first.detail : "unknown"));
      if (first?.hint) console.log("      归因线索: " + first.hint);
    }
  }
  console.log("");
  console.log("报告: " + path.join(runDir, "report.md"));
  console.log("数据: " + path.join(runDir, "report.json"));
  console.log("证据: " + path.join(runDir, "evidence"));

  if (abortRun) return 2;
  const bad =
    failedResults.length > 0 ||
    crash.length > 0 ||
    crashScan.oom.length > 0 ||
    finalPid === null;
  return bad ? 1 : 0;
  } finally {
    if (lockInfo) {
      try {
        const rel = await releaseDeviceLock(adb, runId);
        if (rel.released) console.log("device lock released");
        else console.warn("device lock not released: " + rel.reason);
      } catch (e: any) {
        console.warn("device lock release failed: " + (e?.message ?? e));
      }
    }
  }
}

// ---------------------------------------------------------------------------
// 输出
// ---------------------------------------------------------------------------

function truncateWidth(text: string, width: number): string {
  if (displayWidth(text) <= width) return text;
  let out = "";
  for (const ch of text) {
    if (displayWidth(out + ch) > width - 1) break;
    out += ch;
  }
  return out + "…";
}

function firstFailure(result: any): Check | null {
  return (result.checks as Check[]).find((c) => !c.pass) ?? null;
}

function printTable(results: any[], totalMs: number): void {
  console.log("");
  console.log("== 场景矩阵执行结果 ==");
  const header = ["#", "结果", "场景", "用时", "关键证据 / 失败判据"];
  const rows: string[][] = [];
  for (const r of results) {
    const first = firstFailure(r);
    const detail =
      r.status === "FAIL"
        ? (first ? first.name + ": " + first.detail : "unknown")
        : r.status === "SKIP"
          ? (r.skipReason ?? "skip")
          : r.status === "CONTAMINATED"
            ? "外部改动: " + (r.contamination?.[0] ? r.contamination[0].before + " => " + r.contamination[0].after : "detected")
            : r.status === "PENDING"
              ? "依赖未落地: " + ((r.checks as Check[]).find((c) => c.pending)?.detail ?? "pending")
              : (r.summary || (r.checks as Check[]).length + " 项判据通过");
    rows.push([
      String(r.id),
      r.status,
      r.name,
      (r.durationMs / 1000).toFixed(1) + "s",
      detail,
    ]);
  }
  const widths = header.map((h) => displayWidth(h));
  for (const row of rows) {
    for (let i = 0; i < row.length; i++) widths[i] = Math.max(widths[i], displayWidth(row[i]));
  }
  const maxDetail = Math.max(40, 118 - (widths[0] + widths[1] + widths[2] + widths[3] + 12));
  widths[4] = Math.min(widths[4], maxDetail);
  const line = (cells: string[]) =>
    cells.map((c, i) => padEndWidth(truncateWidth(c, widths[i]), widths[i])).join("  ");
  console.log(line(header));
  console.log(widths.map((w) => "-".repeat(w)).join("  "));
  for (const row of rows) console.log(line(row));
  console.log("(总耗时 " + Math.round(totalMs / 1000) + "s)");
}

function buildMarkdown(report: any, results: any[], runDir: string): string {
  const L: string[] = [];
  L.push("# 场景矩阵执行报告");
  L.push("");
  L.push("- 生成时间: " + report.generatedAt);
  L.push("- 设备: " + report.device + "  包: " + report.package + "  versionCode: " + report.versionCode + " (" + report.versionName + ")");
  L.push("- 包更新时间: " + report.lastUpdateTime);
  L.push("- pid: " + report.pidStart + " -> " + report.pidEnd + "  adb 调用: " + report.adbCalls);
  L.push("- 总耗时: " + Math.round(report.totalMs / 1000) + "s");
  if (report.aborted) L.push("- **整轮中止**: " + report.aborted);
  L.push("- 选歌: primary " + songLine(report.songs.primary) + "; short " + songLine(report.songs.short) + "; unconfirmed " + songLine(report.songs.unconfirmed));
  L.push("");
  L.push("## 结果总览");
  L.push("");
  L.push("| # | 结果 | 场景 | 用时 | 关键证据 / 失败判据 |");
  L.push("|---|---|---|---|---|");
  for (const r of results) {
    const first = firstFailure(r);
    const detail =
      r.status === "FAIL"
        ? (first ? "**" + first.name + "**: " + first.detail : "unknown")
        : r.status === "SKIP"
          ? (r.skipReason ?? "skip")
          : r.status === "CONTAMINATED"
            ? "外部改动(结果不可信): " + (r.contamination?.[0] ? r.contamination[0].before + " => " + r.contamination[0].after : "detected")
            : r.status === "PENDING"
              ? "依赖未落地: " + ((r.checks as Check[]).find((c) => c.pending)?.detail ?? "pending")
              : r.summary;
    L.push("| " + r.id + " | " + r.status + " | " + r.name + " | " + (r.durationMs / 1000).toFixed(1) + "s | " + String(detail).replace(/\|/g, "\\|") + " |");
  }
  L.push("");
  L.push("## 判据明细");
  L.push("");
  for (const r of results) {
    const ev = r.evidenceFile ? "evidence/" + path.basename(r.evidenceFile) : "-";
    L.push("### #" + r.id + " " + r.name + " — " + r.status + " (" + (r.durationMs / 1000).toFixed(1) + "s)");
    L.push("");
    if (!r.checks || r.checks.length === 0) {
      L.push(r.skipReason ? "跳过: " + r.skipReason : "无判据。");
      L.push("");
      continue;
    }
    L.push("| 判据 | 实测 | 结论 | 证据路径 |");
    L.push("|---|---|---|---|");
    for (const c of r.checks as Check[]) {
      L.push(
        "| " + c.name + " | " + String(c.detail).replace(/\|/g, "\\|") + " | " + (c.pass ? "通过" : c.pending ? "待依赖(" + (c.dependency ?? "?") + ")" : "失败") + " | " + ev + " |",
      );
    }
    if (r.status === "CONTAMINATED" && r.contamination.length > 0) {
      L.push("");
      L.push("污染事件:");
      for (const e of r.contamination) L.push("- " + e.at + "  " + e.before + "  =>  " + e.after);
    }
    if (r.newInvariants.length > 0) {
      L.push("");
      L.push("新增不变量: " + r.newInvariants.join(", "));
    }
    L.push("");
  }
  const failed = results.filter((r) => r.status === "FAIL");
  const skipped = results.filter((r) => r.status === "SKIP");
  const contaminated = results.filter((r) => r.status === "CONTAMINATED");
  L.push("## 失败清单");
  L.push("");
  if (failed.length === 0) {
    L.push("无。");
  } else {
    for (const r of failed) {
      L.push("### #" + r.id + " " + r.name + " — FAIL");
      L.push("");
      for (const c of (r.checks as Check[]).filter((x) => !x.pass)) {
        L.push("- 判据失败: **" + c.name + "** — " + c.detail);
        if (c.hint) L.push("  - 归因线索: " + c.hint);
      }
      L.push("- 场景摘要: " + (r.summary || "(无)"));
      L.push("- 证据: evidence/" + path.basename(r.evidenceFile));
      if (r.newInvariants.length > 0) L.push("- 新增不变量: " + r.newInvariants.join(", "));
      L.push("");
    }
  }
  L.push("## 污染清单（CONTAMINATED）");
  L.push("");
  if (contaminated.length === 0) {
    L.push("无。");
  } else {
    for (const r of contaminated) {
      L.push("### #" + r.id + " " + r.name);
      L.push("");
      L.push("- 场景期间的判据结果不可信（设备被外部操作/重装）；请在独占窗口重跑。");
      for (const e of r.contamination) L.push("- " + e.at + "  " + e.before + "  =>  " + e.after);
      L.push("- 证据: evidence/" + path.basename(r.evidenceFile));
      L.push("");
    }
  }
  L.push("## 已允许的不变量告警");
  L.push("");
  const allowedSeen: string[] = [];
  for (const r of results) {
    const allowedHits = (r.newInvariants as string[]).filter((c) => (report.allowInvariants as string[]).includes(c));
    if (allowedHits.length > 0) {
      allowedSeen.push("#" + r.id + " " + r.name + ": " + allowedHits.join(", ") + " (evidence/" + path.basename(r.evidenceFile) + ")");
    }
  }
  if (allowedSeen.length === 0) L.push("无。");
  else {
    L.push("这些 code 在本轮被触发，但按 tools/scenarios/allow-invariants.json 归为已知噪声；不判场景失败，仍需 invariants owner 复核：");
    L.push("");
    for (const line of allowedSeen) L.push("- " + line);
  }
  L.push("");
  L.push("## 屏幕/音量恢复（每场景）");
  L.push("");
  L.push("| # | 场景 | screenRestore | volumeRestore |");
  L.push("|---|---|---|---|");
  for (const r of results) {
    const s = r.screenRestore;
    const v = r.volumeRestore;
    L.push(
      "| " + r.id + " | " + r.name + " | " +
        (s ? "awake=" + s.awake + ", keyguard=" + s.keyguard : (r.status === "SKIP" ? "skipped" : "n/a")) + " | " +
        (v ? "volume=" + String(v.volume) + (v.fixed ? " (fixed)" : "") : (r.status === "SKIP" ? "skipped" : "n/a")) + " |",
    );
  }
  L.push("");
  L.push("- 收尾屏幕: " + (report.finalScreen ? "awake=" + report.finalScreen.awake + ", keyguard=" + report.finalScreen.keyguard : "n/a"));
  L.push("- 收尾媒体音量: " + (report.finalVolume ? String(report.finalVolume.volume) + (report.finalVolume.ok ? "" : " (异常)") : "n/a"));
  L.push("");
  L.push("## 待落地依赖");
  L.push("");
  const pendingRows = results.filter((r) => (r.checks as Check[]).some((c) => c.pending));
  if (pendingRows.length === 0) {
    L.push("无。");
  } else {
    for (const r of pendingRows) {
      L.push("### #" + r.id + " " + r.name);
      L.push("");
      for (const c of (r.checks as Check[]).filter((x) => x.pending)) {
        L.push("- [" + (c.dependency ?? "?") + "] " + c.name + " — " + c.detail);
        if (c.hint) L.push("  - 说明: " + c.hint);
      }
      L.push("");
    }
  }
  L.push("## 跳过");
  L.push("");
  if (skipped.length === 0) L.push("无。");
  else for (const r of skipped) L.push("- #" + r.id + " " + r.name + ": " + (r.skipReason ?? "skip"));
  L.push("");
  L.push("## 待裁决（产品行为）");
  L.push("");
  const decisions: string[] = Array.from(new Set(report.pendingDecisions ?? []));
  if (decisions.length === 0) L.push("无。");
  else for (const d of decisions) L.push("- " + d);
  L.push("");
  L.push("## 环境事实");
  L.push("");
  L.push("- 起始不变量: " + (report.baselineInvariants.length ? report.baselineInvariants.map((x: any) => x[0] + "x" + x[1]).join(", ") : "none"));
  L.push("- 允许的不变量: " + (report.allowInvariants.length ? report.allowInvariants.join(", ") : "none"));
  L.push("- 崩溃/ANR(运行期): " + (report.crash.length ? report.crash.length + " 条(见 report.json)" : "无"));
  L.push("- OutOfMemoryError: " + ((report.oom ?? []).length ? report.oom.length + " 条 — 必须修复" : "无"));
  L.push("- 产物目录: " + runDir);
  L.push("");
  L.push("## 判据说明");
  L.push("");
  L.push("- 出声 = 主播放器 invariantProbe.writtenFrames / 预览页 outputFrames 在窗口内 >= 0.85~0.9 × 采样率 × 秒数；暂停静音要求 Δ=0（先等尾帧落定：连续两次 500ms 采样帧数不变）。");
  L.push("- 位置 = /state.playback.positionMs（已按窗口折返）或 /debug/preview.snapshot.displayMs。");
  L.push("- 不变量 = 每条场景前后对比 /state.invariants 的 code×count，出现新增即失败（allowlist 见 tools/scenarios/allow-invariants.json）。");
  L.push("- 不崩 = 进程 pid 不变 + crash buffer/main logcat 无本包 FATAL/ANR。");
  L.push("");
  return L.join("\n");
}

main()
  .then((code) => process.exit(code))
  .catch((e) => {
    console.error("");
    console.error("执行器失败: " + (e?.message ?? e));
    if (e?.stack) console.error(String(e.stack).split("\n").slice(0, 6).join("\n"));
    process.exit(2);
  });



