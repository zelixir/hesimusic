#!/usr/bin/env bun
/**
 * 临时真机脚本（不提交）：用内容真值探针复现/排除 00027。
 * 4 个场景：A 主播放器 LOOP_ONLY(SOURCE) / B 主播放器 M3 / C 预览 LOOP_ONLY / D 预览 FULL
 * 每个：基线偏移校准 -> seek B-5s -> 跨 B 前一次 tap -> 跨 B 后一次 tap -> NCC 峰值
 */
import * as path from "node:path";
import { Adb, Api, parseUiNodes, sleep } from "./harness";
import type { Cfg, SongInfo } from "./harness";
import { createProber } from "./content-probe";
import type { ProbeResult, TapCapture } from "./content-probe";

const DEVICE = "b1e84dad";
const STAMP = new Date().toISOString().replace(/[:.]/g, "-").slice(0, 19);
const OUT = path.join("e2e-artifacts", "content-00027-" + STAMP);
const cfg: Cfg = {
  device: DEVICE,
  base: "http://127.0.0.1:18765",
  forwardLocal: 18765,
  forwardRemote: 8765,
  outDir: OUT,
  adbExe: process.env.ANDROID_HOME
    ? path.join(process.env.ANDROID_HOME, "platform-tools", "adb.exe")
    : "adb",
  pkg: "com.zjr.hesimusic",
  scenarioTimeoutMs: 90000,
};
const adb = new Adb(cfg.adbExe, DEVICE);
const api = new Api(cfg, adb);
const stack = createProber(cfg, api, { workDir: OUT, log: () => {} });

interface Row {
  name: string;
  mode: string;
  engine: string;
  loopPath: string;
  sourceKind: string;
  displayBefore: number | null;
  displayAfter: number | null;
  framesBefore: number | null;
  framesAfter: number | null;
  nccBefore: number | null;
  nccAfter: number | null;
  driftBefore: number | null;
  driftAfter: number | null;
  notes: string[];
  wavBefore?: string;
  wavAfter?: string;
}

const rows: Row[] = [];

async function waitUntil<T>(fn: () => Promise<T>, pred: (v: T) => boolean, timeoutMs: number, intervalMs = 100): Promise<{ ok: boolean; value: T | null; elapsedMs: number }> {
  const t0 = Date.now();
  let value: T | null = null;
  while (Date.now() - t0 < timeoutMs) {
    value = await fn();
    if (pred(value)) return { ok: true, value, elapsedMs: Date.now() - t0 };
    await sleep(intervalMs);
  }
  return { ok: false, value, elapsedMs: Date.now() - t0 };
}

async function previewSnap(): Promise<any | null> {
  const p = await api.preview();
  return p.available ? p.snapshot : null;
}

async function displayOf(target: "main" | "preview"): Promise<number | null> {
  if (target === "preview") {
    const s = await previewSnap();
    const v = s?.liveDisplayMs ?? s?.displayMs;
    return v == null ? null : Number(v);
  }
  const s = await api.state();
  return s.playback?.positionMs == null ? null : Number(s.playback.positionMs);
}

async function framesOf(target: "main" | "preview"): Promise<number | null> {
  if (target === "preview") {
    const s = await previewSnap();
    return s?.outputFrames == null ? null : Number(s.outputFrames);
  }
  const s = await api.state();
  return s.invariantProbe?.writtenFrames == null ? null : Number(s.invariantProbe.writtenFrames);
}

async function facts(target: "main" | "preview"): Promise<{ engine: string; loopPath: string; sourceKind: string; mode: string; outside: boolean | null }> {
  if (target === "preview") {
    const s = await previewSnap();
    return {
      engine: String(s?.engine ?? "?"),
      loopPath: "preview:" + String(s?.mode ?? "?"),
      sourceKind: String(s?.sourceKind ?? "?"),
      mode: String(s?.mode ?? "?"),
      outside: s?.outsideLoop == null ? null : Boolean(s.outsideLoop),
    };
  }
  const s = await api.state();
  return {
    engine: String(s.playback?.engine ?? "?"),
    loopPath: String(s.loop?.loopPath ?? "?"),
    sourceKind: "main",
    mode: "main",
    outside: null,
  };
}

async function tapByText(label: string): Promise<boolean> {
  for (let attempt = 0; attempt < 4; attempt++) {
    const dump = await adb.uiDump();
    const node = dump.nodes.find((n) => n.text.trim() === label) ?? dump.nodes.find((n) => n.text.includes(label));
    if (node) {
      const x = (node.bounds.left + node.bounds.right) / 2;
      const y = (node.bounds.top + node.bounds.bottom) / 2;
      await adb.tap(x, y);
      console.log("    tap '" + label + "' @(" + Math.round(x) + "," + Math.round(y) + ")");
      return true;
    }
    await sleep(400);
  }
  const dump = await adb.uiDump();
  console.log("    !! 找不到按钮 '" + label + "'，现有: " + dump.nodes.map((n) => n.text).slice(0, 14).join(" | "));
  return false;
}

async function probe(
  target: "main" | "preview",
  label: string,
  songPath: string,
  aMs: number,
  bMs: number,
  offsetMs: number,
): Promise<{ res: ProbeResult; cap: TapCapture; displayRef: number | null; frames: number | null }> {
  const cap = await stack.prober.capture({ target, samplesMs: 300, dwellMs: 900, workDir: OUT, label });
  const displayRef = cap.displayRef?.displayMs ?? null;
  const frames = await framesOf(target);
  if (displayRef == null) throw new Error(label + ": 没有 display 采样");
  const res = await stack.truth.probeCapture(cap, {
    songPath,
    aMs,
    bMs,
    expectedContentMs: displayRef + offsetMs,
    mode: "band",
    wide: true,
    driftTolMs: 700,
    caption: label,
  });
  console.log(
    "    " + label + ": peak=" + res.peak.toFixed(3) + " display=" + Math.round(displayRef) +
      "ms drift=" + (res.driftMs == null ? "n/a" : Math.round(res.driftMs) + "ms") +
      " chunks=" + res.chunks + (res.wide && res.wide.length ? " wide=" + res.wide[0].region + "@" + Math.round(res.wide[0].positionMs) + "(" + res.wide[0].peak.toFixed(2) + ")" : ""),
  );
  return { res, cap, displayRef, frames };
}

/** 主播放器：reset -> play -> loop/enter -> 校准 -> seek B-5s -> 跨 B 前后各一次 tap。 */
async function mainScenario(name: string, song: SongInfo, aMs: number, bMs: number, songPath: string): Promise<void> {
  const row: Row = {
    name, mode: "main/loop", engine: "?", loopPath: "?", sourceKind: "main",
    displayBefore: null, displayAfter: null, framesBefore: null, framesAfter: null,
    nccBefore: null, nccAfter: null, driftBefore: null, driftAfter: null, notes: [],
  };
  try {
    await api.post("/navigate", { route: "about" });
    await sleep(500);
    const prev = await api.preview();
    if (prev.available) row.notes.push("警告: 预览页仍挂着(两个引擎会同时写 tap)");
    await api.post("/loop/exit", {});
    await sleep(300);
    const play = await api.post("/player/play", { songId: song.id, repeatOne: false });
    if (play.status !== 200) throw new Error("/player/play -> " + play.status);
    await waitUntil(async () => api.state(), (s) => s.playback?.songId === song.id && s.playback?.playbackState === "ready", 9000);
    const enter = await api.post("/loop/enter", { songId: song.id, play: true, restart: true });
    if (enter.status !== 200) throw new Error("/loop/enter -> " + enter.status + " " + enter.text.slice(0, 120));
    const inst = await waitUntil(async () => api.state(), (s) => s.playback?.engine === "loop" && s.loop?.window?.songId === song.id, 10000);
    if (!inst.ok) throw new Error("进循环失败 engine=" + inst.value?.playback?.engine + " loopPath=" + inst.value?.loop?.loopPath);
    const f0 = await facts("main");
    row.engine = f0.engine;
    row.loopPath = f0.loopPath;
    console.log("    进入循环: engine=" + f0.engine + " loopPath=" + f0.loopPath);

    // 校准（环带中段）
    await api.post("/player/seek", { positionMs: Math.round(aMs + (bMs - aMs) / 2) });
    await sleep(1100);
    const base = await probe("main", "校准", songPath, aMs, bMs, 0);
    const offset = base.res.driftMs ?? 0;
    row.notes.push("baselinePeak=" + base.res.peak.toFixed(3) + " offset=" + Math.round(offset) + "ms");

    const target = Math.max(aMs, bMs - 5000);
    await api.post("/player/seek", { positionMs: target });
    await sleep(400);
    const before = await probe("main", "跨B前", songPath, aMs, bMs, offset);
    row.nccBefore = before.res.peak;
    row.driftBefore = before.res.driftMs;
    row.displayBefore = before.displayRef;
    row.framesBefore = before.frames;
    row.wavBefore = before.cap.wavPath;

    const wrap = await waitUntil(async () => displayOf("main"), (d) => d != null && d < aMs + 3000, 14000, 100);
    row.notes.push(wrap.ok ? "跨B: display " + Math.round(before.displayRef ?? 0) + " -> " + Math.round(wrap.value ?? 0) + " @+" + wrap.elapsedMs + "ms" : "跨B: 14s 未折回");
    await sleep(700);
    const after = await probe("main", "跨B后", songPath, aMs, bMs, offset);
    row.nccAfter = after.res.peak;
    row.driftAfter = after.res.driftMs;
    row.displayAfter = after.displayRef;
    row.framesAfter = after.frames;
    row.wavAfter = after.cap.wavPath;
    const f1 = await facts("main");
    row.notes.push("跨B后 engine=" + f1.engine + " loopPath=" + f1.loopPath);
    if (after.res.wide && after.res.wide.length) row.notes.push("wide=" + after.res.wide[0].region + "@" + Math.round(after.res.wide[0].positionMs) + " peak=" + after.res.wide[0].peak.toFixed(3));
  } catch (e: any) {
    row.notes.push("ERROR: " + (e?.message ?? e));
    console.log("    !! " + (e?.message ?? e));
  }
  rows.push(row);
}

/** 预览：navigate loop/<id> -> 选模式 -> 校准 -> previewSeek B-5s -> 跨 B 前后 tap。 */
async function previewScenario(name: string, song: SongInfo, aMs: number, bMs: number, songPath: string, modeLabel: string): Promise<void> {
  const row: Row = {
    name, mode: "preview/" + modeLabel, engine: "?", loopPath: "?", sourceKind: "?",
    displayBefore: null, displayAfter: null, framesBefore: null, framesAfter: null,
    nccBefore: null, nccAfter: null, driftBefore: null, driftAfter: null, notes: [],
  };
  try {
    const nav = await api.post("/navigate", { route: "loop/" + song.id });
    if (nav.status !== 200) throw new Error("/navigate -> " + nav.status);
    const rd = await waitUntil(async () => api.preview(), (p) => p.available === true, 20000, 150);
    if (!rd.ok) throw new Error("预览页 20s 未就绪: " + (rd.value?.reason ?? "?"));
    const t0 = Date.now();
    const tapped = await tapByText(modeLabel);
    if (!tapped) throw new Error("点不到「" + modeLabel + "」");
    const playing = await waitUntil(async () => previewSnap(), (s) => s != null && s.isPlaying === true, 12000, 100);
    const stallMs = Date.now() - t0;
    row.notes.push("点「" + modeLabel + "」到出声=" + stallMs + "ms");
    console.log("    点「" + modeLabel + "」-> 出声 " + stallMs + "ms (isPlaying=" + (playing.ok ? "true" : "false") + ")");
    const snap = await previewSnap();
    row.engine = String(snap?.engine ?? "?");
    row.loopPath = "preview:" + String(snap?.mode ?? "?");
    row.sourceKind = String(snap?.sourceKind ?? "?");
    console.log("    预览: engine=" + row.engine + " mode=" + snap?.mode + " sourceKind=" + row.sourceKind + " A=" + snap?.aMs + " B=" + snap?.bMs);

    // 校准（中段）
    let offset = 0;
    try {
      const mid = await api.post("/debug/preview-seek", { positionMs: Math.round(aMs + (bMs - aMs) / 2) }, 12000);
      if (mid.status === 200) {
        await sleep(1000);
        const base = await probe("preview", "校准", songPath, aMs, bMs, 0);
        offset = base.res.driftMs ?? 0;
        row.notes.push("baselinePeak=" + base.res.peak.toFixed(3) + " offset=" + Math.round(offset) + "ms");
      } else {
        row.notes.push("校准 seek 不可用(status=" + mid.status + ")");
      }
    } catch (e: any) {
      row.notes.push("校准异常: " + (e?.message ?? e));
    }

    const target = Math.max(aMs, bMs - 5000);
    const seek = await api.post("/debug/preview-seek", { positionMs: target }, 12000).catch((e) => ({ status: 0, json: null, text: String(e), ms: 0 }));
    row.notes.push("seek B-5s -> status=" + seek.status + " display=" + (seek as any).json?.displayAfterMs + " playout=" + (seek as any).json?.playoutMs + "ms");
    console.log("    seek B-5s: status=" + seek.status + " display=" + (seek as any).json?.displayAfterMs);
    await sleep(400);
    const before = await probe("preview", "跨B前", songPath, aMs, bMs, offset);
    row.nccBefore = before.res.peak;
    row.driftBefore = before.res.driftMs;
    row.displayBefore = before.displayRef;
    row.framesBefore = before.frames;
    row.wavBefore = before.cap.wavPath;

    const wrap = await waitUntil(async () => displayOf("preview"), (d) => d != null && d < aMs + 3000, 14000, 100);
    row.notes.push(wrap.ok ? "跨B: " + Math.round(before.displayRef ?? 0) + " -> " + Math.round(wrap.value ?? 0) + " @+" + wrap.elapsedMs + "ms" : "跨B: 14s 未折回");
    await sleep(700);
    const after = await probe("preview", "跨B后", songPath, aMs, bMs, offset);
    row.nccAfter = after.res.peak;
    row.driftAfter = after.res.driftMs;
    row.displayAfter = after.displayRef;
    row.framesAfter = after.frames;
    row.wavAfter = after.cap.wavPath;
    if (after.res.wide && after.res.wide.length) row.notes.push("wide=" + after.res.wide[0].region + "@" + Math.round(after.res.wide[0].positionMs) + " peak=" + after.res.wide[0].peak.toFixed(3));
  } catch (e: any) {
    row.notes.push("ERROR: " + (e?.message ?? e));
    console.log("    !! " + (e?.message ?? e));
  }
  rows.push(row);
}

async function main(): Promise<number> {
  await adb.forwardEnsure(cfg.forwardLocal, cfg.forwardRemote);
  const st = await api.state();
  console.log("== 环境 ==");
  console.log("device=" + DEVICE + " screen=" + st.screen + " routeSource=" + st.invariantProbe?.routeSource);
  const ver = await adb.versionInfo(cfg.pkg);
  console.log("pkg versionCode=" + ver.versionCode + " versionName=" + ver.versionName + " lastUpdate=" + ver.lastUpdateTime);
  const all = await api.songsAll();
  const cands = all.filter(
    (s) =>
      s.hasLoop && s.loopConfirmed && s.loopStartMs != null && s.loopEndMs != null &&
      Number(s.loopStartMs) >= 3000 && Number(s.loopEndMs) - Number(s.loopStartMs) >= 12000 &&
      s.durationMs > Number(s.loopEndMs) + 3000 && /\.(mp3|wav|m4a|aac|flac)$/i.test(s.path),
  );
  cands.sort((a, b) => Number(a.loopEndMs) - Number(a.loopStartMs) - (Number(b.loopEndMs) - Number(b.loopStartMs)) || a.id - b.id);
  if (!cands.length) throw new Error("没有合适的已确认循环歌");
  // 挑环带最短、且 B 之后至少还有 6s（给"播 B 之后内容"留出空间）的歌
  const song = cands[0];
  const info = await api.song(song.id);
  const aMs = Math.round(Number(info.loopStartMs));
  const bMs = Math.round(Number(info.loopEndMs));
  const songPath = String(info.path);
  console.log("选歌: #" + song.id + " 「" + song.title + "」A=" + aMs + " B=" + bMs + " L=" + ((bMs - aMs) / 1000).toFixed(1) + "s dur=" + Math.round(info.durationMs / 1000) + "s path=" + songPath);

  // A: 主播放器 LOOP_ONLY (SOURCE)
  console.log("");
  console.log("== A 主播放器 LOOP_ONLY(SOURCE) ==");
  await mainScenario("A 主/LOOP_ONLY", song, aMs, bMs, songPath);

  // C: 预览 LOOP_ONLY
  console.log("");
  console.log("== C 预览 LOOP_ONLY ==");
  await previewScenario("C 预览/仅循环段", song, aMs, bMs, songPath, "仅循环段");

  // D: 预览 FULL
  console.log("");
  console.log("== D 预览 FULL(完整循环) ==");
  await previewScenario("D 预览/完整循环", song, aMs, bMs, songPath, "完整循环");

  // B: 主播放器 M3
  console.log("");
  console.log("== B 主播放器 M3(source-mode off) ==");
  const sm = await api.post("/debug/source-mode", { on: false });
  console.log("    source-mode -> " + JSON.stringify(sm.json));
  await sleep(500);
  await mainScenario("B 主/M3", song, aMs, bMs, songPath);
  const stB = await api.state();
  rows[rows.length - 1].notes.push("routeSource=" + stB.invariantProbe?.routeSource);

  // 恢复 SOURCE
  const sm2 = await api.post("/debug/source-mode", { on: true });
  console.log("恢复 source-mode -> " + JSON.stringify(sm2.json));
  await api.post("/loop/exit", {}).catch(() => null);
  await api.post("/navigate", { route: "about" }).catch(() => null);

  console.log("");
  console.log("== 结果表 ==");
  const header = ["场景", "跨B前NCC", "跨B后NCC", "display前", "display后", "frames前", "frames后", "engine/loopPath", "结论"];
  console.log(header.join(" | "));
  for (const r of rows) {
    const verdict =
      r.nccBefore != null && r.nccAfter != null
        ? r.nccBefore >= 0.9 && r.nccAfter >= 0.9
          ? "音画一致"
          : "!! 音画不符"
        : "未完成(" + (r.notes.find((n) => n.startsWith("ERROR")) ?? "无数据") + ")";
    console.log(
      [r.name, fmt(r.nccBefore), fmt(r.nccAfter), fmtInt(r.displayBefore), fmtInt(r.displayAfter), fmtInt(r.framesBefore), fmtInt(r.framesAfter), r.engine + "/" + r.loopPath, verdict].join(" | "),
    );
  }
  console.log("");
  for (const r of rows) {
    console.log("- " + r.name + ": " + r.notes.join("; "));
    if (r.wavBefore) console.log("    tap(before)=" + r.wavBefore);
    if (r.wavAfter) console.log("    tap(after)=" + r.wavAfter);
  }
  const fs = await import("node:fs");
  fs.mkdirSync(OUT, { recursive: true });
  fs.writeFileSync(path.join(OUT, "summary.json"), JSON.stringify({ device: DEVICE, version: ver, song: { id: song.id, aMs, bMs, path: songPath }, rows }, null, 2), "utf8");
  console.log("");
  console.log("产物: " + OUT);
  return 0;
}

function fmt(n: number | null): string {
  return n == null ? "n/a" : n.toFixed(3);
}
function fmtInt(n: number | null): string {
  return n == null ? "n/a" : String(Math.round(n));
}

main()
  .then((c) => process.exit(c))
  .catch((e) => {
    console.error("失败: " + (e?.stack ?? e));
    process.exit(2);
  });
