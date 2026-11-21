#!/usr/bin/env bun
/**
 * tools/scenarios/harness.ts —— 场景矩阵执行器的公共层。
 *
 * 只做四件事：
 *  1) HTTP 调试口客户端（自动重建 adb forward、区分 4xx/网络错误）；
 *  2) adb 封装（串行执行，最多 1 条并发；证据用 logcat / meminfo / pidof / uiautomator）；
 *  3) 探针：主播放器 writtenFrames、预览页 outputFrames、位置/窗口/不变量；
 *  4) 证据与判据的小工具。
 *
 * 判据口径见 docs/testing-scenarios.md §3。本文件不写死任何场景。
 */

import { spawnSync } from "node:child_process";
import { existsSync, mkdirSync, writeFileSync } from "node:fs";
import * as path from "node:path";

// ---------------------------------------------------------------------------
// 基础类型
// ---------------------------------------------------------------------------

export interface Cfg {
  device: string;
  base: string;
  forwardLocal: number;
  forwardRemote: number;
  outDir: string;
  adbExe: string;
  pkg: string;
  /** 每个场景的硬超时（毫秒） */
  scenarioTimeoutMs: number;
}

export interface SongInfo {
  id: number;
  title: string;
  artist: string;
  durationMs: number;
  path: string;
  hasLoop: boolean;
  loopConfirmed: boolean;
  loopStartMs: number | null;
  loopEndMs: number | null;
  sampleRate: number | null;
}

export interface LoopWindow {
  songId: number;
  loopStartUs: number;
  loopEndUs: number;
}

export interface State {
  screen: string;
  analysis: any;
  playback: any;
  loop: any;
  invariants: any[];
  invariantProbe: any;
  library: any;
  server: any;
  at: number;
}

export interface HttpResult {
  status: number;
  json: any;
  text: string;
  ms: number;
}

export type Check = {
  name: string;
  pass: boolean;
  detail: string;
  /** 失败时的初步归因提示（写给 issue，不写猜测性结论） */
  hint?: string;
  /** true = 已知依赖未落地(如 jump-fill)：不算 FAIL，场景状态记 PENDING。 */
  pending?: boolean;
  /** 依赖项名称，用于报告归纳。 */
  dependency?: string;
};

export interface FrameMeasurement {
  available: boolean;
  reason?: string;
  deltaFrames: number;
  spanMs: number;
  framesPerSec: number;
  ratio: number;
  startAtMs: number;
  endAtMs: number;
  routeSource: boolean | null;
  samples: { atMs: number; frames: number }[];
}

export interface EvidenceStep {
  at: string;
  action: string;
  data?: any;
}

// ---------------------------------------------------------------------------
// 小工具
// ---------------------------------------------------------------------------

export function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/**
 * 本执行器"自己动过设备"的时间戳。污染监控用它区分：
 * - 观测到的状态变化紧跟在自己的 POST/tap 之后 → 自己的动作，不算污染；
 * - 没有自己的动作却变了 → 外部（另一个 agent/手工）在动同一台设备。
 *
 * 这是启发式：3.5s 窗口内自己的动作会"掩盖"外部改动，所以报告仍建议独占窗口。
 */
export const selfActivity = {
  lastAt: 0,
  analysisHoldUntil: 0,
  holdUntil: 0,
  mark(): void {
    this.lastAt = Date.now();
  },
  /** 某些 POST(/confirm) 的播放副作用会延迟数秒；在这段时间内忽略"外部改动"判定。 */
  hold(holdMs: number): void {
    this.holdUntil = Math.max(this.holdUntil, Date.now() + holdMs);
  },
  recent(windowMs = 3500): boolean {
    return (
      (this.lastAt > 0 && Date.now() - this.lastAt <= windowMs) ||
      Date.now() < this.holdUntil
    );
  },
  /** 自己发起的分析会持续几十秒；期间 analysis.phase 的变化不当作外部污染。 */
  markAnalysis(holdMs = 180000): void {
    this.analysisHoldUntil = Date.now() + holdMs;
  },
  analysisHeld(): boolean {
    return Date.now() < this.analysisHoldUntil;
  },
};

export function nowHHMMSS(date = new Date()): string {
  const p = (n: number) => String(n).padStart(2, "0");
  return (
    p(date.getMonth() + 1) + "-" + p(date.getDate()) + " " +
    p(date.getHours()) + ":" + p(date.getMinutes()) + ":" + p(date.getSeconds())
  );
}

export function stamp(): string {
  const d = new Date();
  const p = (n: number) => String(n).padStart(2, "0");
  return (
    d.getFullYear() + p(d.getMonth() + 1) + p(d.getDate()) + "-" +
    p(d.getHours()) + p(d.getMinutes()) + p(d.getSeconds())
  );
}

export function round(n: number, digits = 1): number {
  if (!Number.isFinite(n)) return n;
  const f = Math.pow(10, digits);
  return Math.round(n * f) / f;
}

export function compactState(s: State): any {
  const pb = s.playback ?? {};
  const loop = s.loop ?? {};
  return {
    screen: s.screen,
    songId: pb.songId,
    title: pb.title,
    playing: pb.playing,
    playWhenReady: pb.playWhenReady,
    positionMs: pb.positionMs,
    durationMs: pb.durationMs,
    repeatMode: pb.repeatMode,
    shuffle: pb.shuffle,
    playbackState: pb.playbackState,
    buffering: pb.buffering,
    engine: pb.engine,
    // /state.loop 才是窗口的权威口径；playback.engine 是同一 bridge 的副本。
    loop: {
      engine: loop.engine,
      loopPath: loop.loopPath,
      window: loop.window
        ? {
            songId: loop.window.songId,
            aMs: Math.round(loop.window.loopStartUs / 1000),
            bMs: Math.round(loop.window.loopEndUs / 1000),
          }
        : null,
      isReplaying: loop.isReplaying,
      seamCount: loop.seamCount,
      degradeCount: loop.degradeCount,
      intent: loop.intent,
    },
    analysis: {
      phase: s.analysis?.phase,
      analyzing: s.analysis?.analyzing,
      songId: s.analysis?.songId,
      candidateCount: s.analysis?.candidateCount,
      progress: s.analysis?.progress,
    },
    probe: s.invariantProbe
      ? {
          atMs: s.invariantProbe.atMs,
          writtenFrames: s.invariantProbe.writtenFrames,
          routeSource: s.invariantProbe.routeSource,
          playWhenReady: s.invariantProbe.playWhenReady,
        }
      : null,
    invariants: (s.invariants ?? []).map((i: any) => ({ code: i.code, count: i.count, atMs: i.atMs })),
  };
}

/** 终端显示宽度：CJK/全角按 2 列算，用于对齐表格。 */
export function displayWidth(text: string): number {
  let w = 0;
  for (const ch of text) {
    const cp = ch.codePointAt(0) ?? 0;
    if (
      (cp >= 0x1100 && cp <= 0x115f) ||
      (cp >= 0x2e80 && cp <= 0xa4cf) ||
      (cp >= 0xac00 && cp <= 0xd7a3) ||
      (cp >= 0xf900 && cp <= 0xfaff) ||
      (cp >= 0xfe30 && cp <= 0xfe6f) ||
      (cp >= 0xff00 && cp <= 0xff60) ||
      (cp >= 0xffe0 && cp <= 0xffe6)
    ) {
      w += 2;
    } else {
      w += 1;
    }
  }
  return w;
}

export function padEndWidth(text: string, width: number): string {
  const diff = Math.max(0, width - displayWidth(text));
  return text + " ".repeat(diff);
}

// ---------------------------------------------------------------------------
// adb
// ---------------------------------------------------------------------------

export function resolveAdb(): string {
  const candidates: string[] = [];
  if (process.env.ANDROID_HOME) candidates.push(path.join(process.env.ANDROID_HOME, "platform-tools", "adb.exe"));
  if (process.env.ANDROID_SDK_ROOT) candidates.push(path.join(process.env.ANDROID_SDK_ROOT, "platform-tools", "adb.exe"));
  if (process.env.LOCALAPPDATA) candidates.push(path.join(process.env.LOCALAPPDATA, "Android", "Sdk", "platform-tools", "adb.exe"));
  for (const c of candidates) {
    if (existsSync(c)) return c;
  }
  const probe = spawnSync(process.platform === "win32" ? "where" : "which", ["adb"], { encoding: "utf8" });
  if (probe.status === 0) {
    const first = (probe.stdout || "").split(/\r?\n/).map((s) => s.trim()).filter(Boolean)[0];
    if (first) return first;
  }
  return "adb";
}

export class Adb {
  /** adb 调用串行队列：设备侧最多 1 条并发（任务纪律：不超过 3 条）。 */
  private chain: Promise<void> = Promise.resolve();
  public calls = 0;

  constructor(private exe: string, private serial: string) {}

  private runRaw(args: string[], timeoutMs: number): { code: number; out: string; err: string } {
    this.calls += 1;
    const r = spawnSync(this.exe, ["-s", this.serial, ...args], {
      encoding: "utf8",
      timeout: timeoutMs,
      windowsHide: true,
      maxBuffer: 32 * 1024 * 1024,
    });
    return {
      code: r.status ?? (r.error ? -1 : 0),
      out: r.stdout ?? "",
      err: r.stderr ?? (r.error ? String(r.error.message || r.error) : ""),
    };
  }

  async run(args: string[], timeoutMs = 20000): Promise<{ code: number; out: string; err: string }> {
    let release!: () => void;
    const gate = new Promise<void>((resolve) => (release = resolve));
    const prev = this.chain;
    this.chain = prev.then(() => gate);
    await prev;
    try {
      return this.runRaw(args, timeoutMs);
    } finally {
      release();
    }
  }

  async shell(command: string, timeoutMs = 20000): Promise<string> {
    const r = await this.run(["shell", command], timeoutMs);
    if (r.code !== 0 && r.err.trim()) return (r.out + "\n" + r.err).trim();
    return r.out.trim();
  }

  async execOut(args: string[], timeoutMs = 20000): Promise<string> {
    const r = await this.run(["exec-out", ...args], timeoutMs);
    return r.out;
  }

  async pidof(pkg: string): Promise<number | null> {
    const out = await this.shell("pidof " + pkg);
    const m = out.match(/(\d{2,})/);
    return m ? Number(m[1]) : null;
  }

  async versionInfo(pkg: string): Promise<{ versionCode: number | null; versionName: string | null; lastUpdateTime: string | null }> {
    const out = await this.shell("dumpsys package " + pkg);
    const code = out.match(/versionCode=(\d+)/)?.[1];
    const name = out.match(/versionName=([^\s]+)/)?.[1];
    const upd = out.match(/lastUpdateTime=([^\n]+)/)?.[1]?.trim() ?? null;
    return {
      versionCode: code ? Number(code) : null,
      versionName: name ?? null,
      lastUpdateTime: upd,
    };
  }

  async forwardEnsure(local: number, remote: number): Promise<void> {
    const list = await this.run(["forward", "--list"]);
    const needle = "tcp:" + local;
    if (list.out.includes(needle)) return;
    await this.run(["forward", "--remove", needle]);
    await this.run(["forward", needle, "tcp:" + remote]);
    await sleep(200);
  }

  async foregroundActivity(): Promise<string> {
    const out = await this.shell("dumpsys activity activities");
    const m = out.match(/(?:mResumedActivity|topResumedActivity)[^\n]*?([A-Za-z0-9_.]+)\/([A-Za-z0-9_.$]+)/);
    return m ? m[1] + "/" + m[2] : "";
  }

  async home(): Promise<void> {
    selfActivity.mark();
    await this.shell("input keyevent KEYCODE_HOME");
  }

  /** 用 resolve-activity 拿到真正的 launcher component，再显式 am start（-p 在本机 ROM 解析不到）。 */
  async bringForeground(pkg: string): Promise<boolean> {
    selfActivity.mark();
    const resolved = await this.shell(
      "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER " + pkg,
    );
    const comp = resolved.match(/([A-Za-z0-9_.]+)\/([A-Za-z0-9_.$]+)/)?.[0];
    if (comp) {
      await this.shell("am start -W -n " + comp, 25000);
    } else {
      await this.shell("monkey -p " + pkg + " -c android.intent.category.LAUNCHER 1", 25000);
    }
    for (let i = 0; i < 25; i++) {
      await sleep(200);
      const fg = await this.foregroundActivity();
      if (fg.startsWith(pkg)) return true;
    }
    return false;
  }

  async meminfo(pkg: string): Promise<{ javaHeapKb: number | null; totalPssKb: number | null; pssRaw: string }> {
    const out = await this.shell("dumpsys meminfo " + pkg, 30000);
    const java = out.match(/Java Heap:\s+(\d+)/)?.[1];
    let pss = out.match(/TOTAL PSS:\s+(\d+)/)?.[1];
    if (!pss) pss = out.match(/^\s*TOTAL\s+(\d+)/m)?.[1];
    return {
      javaHeapKb: java ? Number(java) : null,
      totalPssKb: pss ? Number(pss) : null,
      pssRaw: out.slice(0, 4000),
    };
  }

  /**
   * crash buffer 里的 OOM 行。OOM 行本身通常不带包名（包名在它前面的 Process: 行），
   * 所以按"上下文 40 行里出现本包"归属，避免把别的 app 的 OOM 算进来。
   */
  private collectOom(lines: string[], epochSec: number, pkg: string): string[] {
    const out: string[] = [];
    for (let i = 0; i < lines.length; i++) {
      const line = lines[i];
      const m = line.match(/^(\d+\.\d+)\s/);
      if (!m || Number(m[1]) < epochSec) continue;
      if (!/OutOfMemoryError|Out of memory/i.test(line)) continue;
      // OOM 行本身不带包名：只认它**前面最近的一条** Process/ANR 行（Android 崩溃转储固定格式），
      // 不看后面，避免把别的 app 的 OOM 误算到本包。
      const before = lines.slice(Math.max(0, i - 40), i);
      const nearestProcess = [...before].reverse().find((l) => /Process:\s|ANR in /.test(l));
      if (!nearestProcess || !nearestProcess.includes(pkg)) continue;
      out.push(line);
    }
    return out;
  }

  /** crash buffer + main buffer 里 runStartEpochSec 之后的 FATAL/ANR/OOM（只认本包）。 */
  async crashScanSince(epochSec: number, pkg: string): Promise<{ fatal: string[]; oom: string[]; anr: string[] }> {
    const fatal: string[] = [];
    const oom: string[] = [];
    const anr: string[] = [];
    const crash = await this.run(["logcat", "-d", "-b", "crash", "-v", "epoch"], 30000);
    const crashLines = crash.out.split(/\r?\n/);
    for (const line of this.collectOom(crashLines, epochSec, pkg)) oom.push("[crash] " + line);
    for (let i = 0; i < crashLines.length; i++) {
      const line = crashLines[i];
      const m = line.match(/^(\d+\.\d+)\s/);
      if (!m || Number(m[1]) < epochSec) continue;
      if (!line.includes("FATAL EXCEPTION")) continue;
      // FATAL 行后面紧跟 Process: 行；只认第一条，遇到下一条 FATAL 就停。
      let processLine = "";
      for (let j = i; j < Math.min(crashLines.length, i + 8); j++) {
        if (j > i && crashLines[j].includes("FATAL EXCEPTION")) break;
        if (/Process:\s/.test(crashLines[j])) {
          processLine = crashLines[j];
          break;
        }
      }
      if (processLine.includes(pkg)) fatal.push("[crash] " + line);
    }
    const main = await this.run(["logcat", "-d", "-v", "epoch", "-t", "20000"], 30000);
    const mainLines = main.out.split(/\r?\n/);
    for (const line of this.collectOom(mainLines, epochSec, pkg)) oom.push("[main] " + line);
    for (let i = 0; i < mainLines.length; i++) {
      const line = mainLines[i];
      const m = line.match(/^(\d+\.\d+)\s/);
      if (!m || Number(m[1]) < epochSec) continue;
      if (line.includes("FATAL EXCEPTION")) {
        let processLine = "";
        for (let j = i; j < Math.min(mainLines.length, i + 8); j++) {
          if (j > i && mainLines[j].includes("FATAL EXCEPTION")) break;
          if (/Process:\s/.test(mainLines[j])) {
            processLine = mainLines[j];
            break;
          }
        }
        if (processLine.includes(pkg)) fatal.push("[main] " + line);
      }
      if (line.includes("ANR in " + pkg)) anr.push("[main] " + line);
    }
    return { fatal: fatal.slice(-20), oom: oom.slice(-20), anr: anr.slice(-20) };
  }

  /**
   * 轻量 OOM 扫描（只读 crash buffer，供每条场景结束调用）。
   * OOM 不一定会让 pid 立刻变化（被系统回收/重启前有一段时间），必须单独判 FAIL。
   */
  async oomLinesSince(epochSec: number, pkg: string): Promise<string[]> {
    const out = await this.run(["logcat", "-d", "-b", "crash", "-v", "epoch"], 30000);
    return this.collectOom(out.out.split(/\r?\n/), epochSec, pkg).slice(-10);
  }

  /** 失败现场 logcat 片段：按时间过滤（设备与主机同时区）。 */
  async logcatSlice(sinceHHMMSS: string, maxLines = 160): Promise<string[]> {
    const out = await this.run(["logcat", "-d", "-v", "time", "-t", "1500"], 30000);
    const tags = [
      "LoopDebug", "LoopInvariant", "LoopPreview", "LoopAudio", "LoopPerf", "LoopSource",
      "AudioSink", "AudioTrack", "ExoPlayer", "AndroidRuntime", "MediaCodec", "LoopPage",
    ];
    const keep: string[] = [];
    for (const line of out.out.split(/\r?\n/)) {
      const m = line.match(/^(\d\d-\d\d \d\d:\d\d:\d\d)/);
      if (!m || m[1] < sinceHHMMSS) continue;
      if (line.includes("FATAL") || line.includes("ANR in") || line.includes("Exception") || tags.some((t) => line.includes(t))) {
        keep.push(line);
      }
    }
    if (keep.length > maxLines) return keep.slice(keep.length - maxLines);
    return keep;
  }

  async uiDump(): Promise<{ nodes: UiNode[]; raw: string; error?: string }> {
    let lastErr = "";
    for (let attempt = 0; attempt < 4; attempt++) {
      const dump = await this.run(["shell", "uiautomator dump --compressed /sdcard/scn-ui.xml"], 20000);
      const text = (dump.out + "\n" + dump.err).trim();
      if (/dumped to/i.test(text)) {
        const raw = await this.execOut(["cat", "/sdcard/scn-ui.xml"], 20000);
        if (raw.includes("<hierarchy")) {
          return { nodes: parseUiNodes(raw), raw };
        }
        lastErr = "dump file unreadable: " + raw.slice(0, 200);
      } else {
        lastErr = text.slice(0, 300);
      }
      await sleep(350);
    }
    return { nodes: [], raw: "", error: lastErr || "uiautomator dump failed" };
  }

  async tap(x: number, y: number): Promise<void> {
    selfActivity.mark();
    await this.shell("input tap " + Math.round(x) + " " + Math.round(y));
  }

  async screenSize(): Promise<{ width: number; height: number }> {
    const out = await this.shell("wm size");
    const m = out.match(/(\d+)x(\d+)/);
    return m ? { width: Number(m[1]), height: Number(m[2]) } : { width: 1080, height: 2400 };
  }
}

export interface UiNode {
  text: string;
  bounds: { left: number; top: number; right: number; bottom: number };
}

export function parseUiNodes(xml: string): UiNode[] {
  const nodes: UiNode[] = [];
  const re = /<node\b([^>]*)>/g;
  let m: RegExpExecArray | null;
  while ((m = re.exec(xml)) !== null) {
    const attrs = m[1];
    const text = xmlUnescape(attrs.match(/\btext="([^"]*)"/)?.[1] ?? "");
    const b = attrs.match(/\bbounds="\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]"/);
    if (!text || !b) continue;
    nodes.push({
      text,
      bounds: { left: Number(b[1]), top: Number(b[2]), right: Number(b[3]), bottom: Number(b[4]) },
    });
  }
  return nodes;
}

function xmlUnescape(s: string): string {
  return s
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&amp;/g, "&");
}

// ---------------------------------------------------------------------------
// HTTP 客户端
// ---------------------------------------------------------------------------

export class Api {
  constructor(private cfg: Cfg, private adb: Adb) {}

  async request(method: string, apiPath: string, body?: any, timeoutMs = 15000): Promise<HttpResult> {
    if (method !== "GET") selfActivity.mark();
    if (apiPath === "/analyze" || apiPath === "/cancelAnalysis") selfActivity.markAnalysis();
    if (apiPath === "/confirm") selfActivity.hold(8000);
    const t0 = Date.now();
    const url = this.cfg.base + apiPath;
    const init: RequestInit = {
      method,
      headers: { "Content-Type": "application/json; charset=utf-8" },
      signal: AbortSignal.timeout(timeoutMs),
    };
    if (body !== undefined) init.body = JSON.stringify(body);
    try {
      const res = await fetch(url, init);
      const text = await res.text();
      let json: any = null;
      try {
        json = text ? JSON.parse(text) : null;
      } catch {
        json = null;
      }
      return { status: res.status, json, text, ms: Date.now() - t0 };
    } catch (e: any) {
      return { status: 0, json: null, text: String(e?.message ?? e), ms: Date.now() - t0 };
    }
  }

  async call(method: string, apiPath: string, body?: any, timeoutMs = 15000): Promise<HttpResult> {
    let r = await this.request(method, apiPath, body, timeoutMs);
    if (r.status === 0) {
      // forward 被清掉是这台设备的已知现象：重建后重试一次。
      await this.adb.forwardEnsure(this.cfg.forwardLocal, this.cfg.forwardRemote);
      await sleep(250);
      r = await this.request(method, apiPath, body, timeoutMs);
    }
    return r;
  }

  async get(apiPath: string, timeoutMs = 15000): Promise<HttpResult> {
    return this.call("GET", apiPath, undefined, timeoutMs);
  }

  async post(apiPath: string, body?: any, timeoutMs = 15000): Promise<HttpResult> {
    return this.call("POST", apiPath, body ?? {}, timeoutMs);
  }

  async state(): Promise<State> {
    const r = await this.get("/state");
    if (r.status !== 200 || !r.json) {
      throw new Error("/state " + r.status + " " + r.text.slice(0, 300));
    }
    return { ...(r.json as any), at: Date.now() } as State;
  }

  async songsAll(): Promise<SongInfo[]> {
    const all: SongInfo[] = [];
    for (let offset = 0; offset < 5000; offset += 500) {
      const r = await this.get("/library/songs?limit=500&offset=" + offset);
      if (r.status !== 200 || !r.json) throw new Error("/library/songs " + r.status);
      const songs = (r.json.songs ?? []) as any[];
      for (const s of songs) {
        all.push({
          id: Number(s.id),
          title: String(s.title ?? ""),
          artist: String(s.artist ?? ""),
          durationMs: Number(s.durationMs ?? 0),
          path: String(s.path ?? ""),
          hasLoop: !!s.hasLoop,
          loopConfirmed: !!s.loopConfirmed,
          loopStartMs: s.loopStartMs == null ? null : Number(s.loopStartMs),
          loopEndMs: s.loopEndMs == null ? null : Number(s.loopEndMs),
          sampleRate: s.sampleRate == null ? null : Number(s.sampleRate),
        });
      }
      if (all.length >= Number(r.json.total ?? 0)) break;
    }
    return all;
  }

  async song(songId: number): Promise<any> {
    const r = await this.get("/song/" + songId);
    if (r.status !== 200) throw new Error("/song/" + songId + " " + r.status + " " + r.text.slice(0, 200));
    return r.json;
  }

  /** GET /debug/preview：端点不存在（旧包）→ null；页面没开（409）→ null + reason。 */
  async preview(): Promise<{ available: boolean; reason?: string; snapshot?: any }> {
    const r = await this.get("/debug/preview", 8000);
    if (r.status === 200 && r.json?.snapshot) return { available: true, snapshot: r.json.snapshot };
    if (r.status === 404) return { available: false, reason: "endpoint-missing" };
    if (r.status === 409) return { available: false, reason: String(r.json?.error ?? "preview-not-attached") };
    return { available: false, reason: "http-" + r.status };
  }

  async analysisWait(timeoutMs = 180000): Promise<{ phase: string; phases: string[]; elapsedMs: number }> {
    const t0 = Date.now();
    const phases: string[] = [];
    let last = "";
    while (Date.now() - t0 < timeoutMs) {
      const r = await this.get("/analysis");
      if (r.status === 200 && r.json) {
        const phase = String(r.json.phase ?? "?");
        if (phase !== last) {
          phases.push(phase + "@" + (Date.now() - t0) + "ms");
          last = phase;
        }
        if (phase === "done" || phase === "failed") {
          return { phase, phases, elapsedMs: Date.now() - t0 };
        }
      }
      await sleep(250);
    }
    return { phase: last || "timeout", phases, elapsedMs: Date.now() - t0 };
  }
}

// ---------------------------------------------------------------------------
// 探针：是不是真出声
// ---------------------------------------------------------------------------

/**
 * 主播放器输出帧窗口。
 *
 * invariantProbe.writtenFrames 是主播放器 AudioSink 交给 delegate 的累计帧数，
 * 采样器 500ms 一次；用 probe.atMs 计算**真实采样跨度**，避免把采样相位差算成丢帧。
 * 只有主播放器走 SOURCE 路线（routeSource=true）时这个计数才更新；M3′ 路线返回
 * available=false，由调用方决定 SKIP 还是用预览探针替代。
 */
export async function measureMainFrames(api: Api, ms: number, sampleRate: number): Promise<FrameMeasurement> {
  const t0 = Date.now();
  let firstState: State | null = null;
  let lastState: State | null = null;
  const samples: { atMs: number; frames: number }[] = [];
  while (Date.now() - t0 < ms + 6000) {
    const s = await api.state();
    const p = s.invariantProbe;
    if (!p) break;
    if (!firstState) firstState = s;
    lastState = s;
    if (samples.length === 0 || samples[samples.length - 1].atMs !== p.atMs) {
      samples.push({ atMs: Number(p.atMs), frames: Number(p.writtenFrames) });
    }
    if (firstState && Number(p.atMs) - Number(firstState.invariantProbe.atMs) >= ms) break;
    await sleep(120);
  }
  if (!firstState || !lastState) {
    return {
      available: false,
      reason: "invariantProbe missing",
      deltaFrames: 0,
      spanMs: 0,
      framesPerSec: 0,
      ratio: 0,
      startAtMs: 0,
      endAtMs: 0,
      routeSource: null,
      samples,
    };
  }
  const p0 = firstState.invariantProbe;
  const p1 = lastState.invariantProbe;
  const spanMs = Number(p1.atMs) - Number(p0.atMs);
  const delta = Number(p1.writtenFrames) - Number(p0.writtenFrames);
  const framesPerSec = spanMs > 0 ? (delta * 1000) / spanMs : 0;
  const ratio = spanMs > 0 && sampleRate > 0 ? delta / ((sampleRate * spanMs) / 1000) : 0;
  const routeSource = p0.routeSource === true;
  return {
    available: spanMs >= ms * 0.9 && routeSource,
    reason: routeSource ? undefined : "main player not on SOURCE route (writtenFrames not updated)",
    deltaFrames: delta,
    spanMs,
    framesPerSec,
    ratio,
    startAtMs: Number(p0.atMs),
    endAtMs: Number(p1.atMs),
    routeSource,
    samples,
  };
}

/** 预览页输出帧窗口（/debug/preview.outputFrames，每 100ms 直接读实时计数）。 */
export async function measurePreviewFrames(
  api: Api,
  ms: number,
  sampleRate: number,
): Promise<FrameMeasurement> {
  const t0 = Date.now();
  const samples: { atMs: number; frames: number }[] = [];
  let firstSnap: any = null;
  let lastSnap: any = null;
  while (Date.now() - t0 < ms + 5000) {
    const p = await api.preview();
    if (!p.available || !p.snapshot) break;
    const snap = p.snapshot;
    if (!firstSnap) firstSnap = snap;
    lastSnap = snap;
    if (samples.length === 0 || samples[samples.length - 1].atMs !== Number(snap.atMs)) {
      samples.push({ atMs: Number(snap.atMs), frames: Number(snap.outputFrames ?? 0) });
    }
    if (firstSnap && Number(snap.atMs) - Number(firstSnap.atMs) >= ms) break;
    await sleep(100);
  }
  if (!firstSnap || !lastSnap) {
    return {
      available: false,
      reason: "preview probe unavailable",
      deltaFrames: 0,
      spanMs: 0,
      framesPerSec: 0,
      ratio: 0,
      startAtMs: 0,
      endAtMs: 0,
      routeSource: null,
      samples,
    };
  }
  const spanMs = Number(lastSnap.atMs) - Number(firstSnap.atMs);
  const delta = Number(lastSnap.outputFrames ?? 0) - Number(firstSnap.outputFrames ?? 0);
  const framesPerSec = spanMs > 0 ? (delta * 1000) / spanMs : 0;
  const ratio = spanMs > 0 && sampleRate > 0 ? delta / ((sampleRate * spanMs) / 1000) : 0;
  return {
    available: spanMs >= ms * 0.9,
    deltaFrames: delta,
    spanMs,
    framesPerSec,
    ratio,
    startAtMs: Number(firstSnap.atMs),
    endAtMs: Number(lastSnap.atMs),
    routeSource: firstSnap.sourceActive === true,
    samples,
  };
}

/**
 * 等输出帧"停稳"：连续两次采样（>=500ms）frames 不变，说明 pause 的尾帧已经落完。
 * 用于「暂停后输出帧为 0」这类判据，避免把 pause 瞬间的一小段硬件尾帧当成持续出声。
 */
export async function waitFramesSettled(
  api: Api,
  timeoutMs = 3000,
): Promise<{ ok: boolean; elapsedMs: number; frames: number; samples: number }> {
  const t0 = Date.now();
  let lastAt = -1;
  let lastFrames = -1;
  let stableSince = 0;
  let samples = 0;
  while (Date.now() - t0 < timeoutMs) {
    const s = await api.state();
    const p = s.invariantProbe;
    if (!p) break;
    const at = Number(p.atMs);
    const frames = Number(p.writtenFrames);
    if (at === lastAt) {
      await sleep(100);
      continue;
    }
    samples += 1;
    if (lastFrames >= 0 && frames === lastFrames) {
      if (stableSince === 0) stableSince = Date.now();
      if (Date.now() - stableSince >= 500) {
        return { ok: true, elapsedMs: Date.now() - t0, frames, samples };
      }
    } else {
      stableSince = 0;
    }
    lastAt = at;
    lastFrames = frames;
    await sleep(120);
  }
  return { ok: false, elapsedMs: Date.now() - t0, frames: lastFrames, samples };
}

export async function waitFor<T>(
  fn: () => Promise<T>,
  predicate: (value: T) => boolean,
  timeoutMs: number,
  intervalMs = 100,
): Promise<{ ok: boolean; value: T | null; elapsedMs: number; polled: number }> {
  const t0 = Date.now();
  let value: T | null = null;
  let polled = 0;
  while (Date.now() - t0 < timeoutMs) {
    value = await fn();
    polled += 1;
    if (predicate(value)) return { ok: true, value, elapsedMs: Date.now() - t0, polled };
    await sleep(intervalMs);
  }
  return { ok: false, value, elapsedMs: Date.now() - t0, polled };
}

// ---------------------------------------------------------------------------
// 证据
// ---------------------------------------------------------------------------

export class Evidence {
  readonly steps: EvidenceStep[] = [];
  readonly notes: string[] = [];

  constructor(private name: string) {}

  step(action: string, data?: any): void {
    this.steps.push({ at: nowHHMMSS(), action, data });
  }

  note(text: string): void {
    this.notes.push(text);
  }

  toJSON(extra: any): any {
    return { scenario: this.name, steps: this.steps, notes: this.notes, ...extra };
  }
}

export function ensureDir(dir: string): void {
  mkdirSync(dir, { recursive: true });
}

export function writeJson(file: string, value: any): void {
  ensureDir(path.dirname(file));
  writeFileSync(file, JSON.stringify(value, null, 2), "utf8");
}

// ---------------------------------------------------------------------------
// 判据小工具
// ---------------------------------------------------------------------------

export function check(
  name: string,
  pass: boolean,
  detail: string,
  hint?: string,
): Check {
  return { name, pass, detail, hint };
}

/** 已知依赖（例如 jump-fill 未落地时的出声延迟）不算失败，标 PENDING 等依赖落地后自动变严。 */
export function pendingCheck(name: string, detail: string, dependency: string, hint?: string): Check {
  return { name, pass: false, detail: detail, hint, pending: true, dependency };
}

export function within(value: number, low: number, high: number): boolean {
  return Number.isFinite(value) && value >= low && value <= high;
}

export function msToSamples(ms: number, sampleRate: number): number {
  return Math.round((ms * sampleRate) / 1000);
}

export function samplesToMs(samples: number, sampleRate: number): number {
  return (samples * 1000) / sampleRate;
}
