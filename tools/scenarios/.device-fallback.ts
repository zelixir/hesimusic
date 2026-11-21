#!/usr/bin/env bun
/**
 * 临时真机脚本：主播放器 + 长窗口(ring 预算超限 -> fallback) 的投影/内容一致性。
 * 目的：确认"回退后 display 是否仍折返到 A"（投影与源不一致 = 用户看到的 00027）。
 */
import * as fs from "node:fs";
import * as path from "node:path";
import { Adb, Api, sleep } from "./harness";
import type { Cfg } from "./harness";
import { collectChunks, createProber, nccBest } from "./content-probe";

const DEVICE = "b1e84dad";
const STAMP = new Date().toISOString().replace(/[:.]/g, "-").slice(0, 19);
const OUT = path.join("e2e-artifacts", "fallback-00027-" + STAMP);
const cfg: Cfg = {
  device: DEVICE,
  base: "http://127.0.0.1:18765",
  forwardLocal: 18765,
  forwardRemote: 8765,
  outDir: OUT,
  adbExe: process.env.ANDROID_HOME ? path.join(process.env.ANDROID_HOME, "platform-tools", "adb.exe") : "adb",
  pkg: "com.zjr.hesimusic",
  scenarioTimeoutMs: 90000,
};
const adb = new Adb(cfg.adbExe, DEVICE);
const api = new Api(cfg, adb);
const stack = createProber(cfg, api, { workDir: OUT, log: () => {} });

const SONG_ID = 41126;

async function logcatTail(pattern: string, lines = 400): Promise<string[]> {
  const r = await adb.run(["logcat", "-d", "-t", String(lines)], 20000);
  const re = new RegExp(pattern, "i");
  return r.out.split(/\r?\n/).filter((l) => re.test(l)).slice(-24);
}

async function main(): Promise<void> {
  await adb.forwardEnsure(cfg.forwardLocal, cfg.forwardRemote);
  const info = await api.song(SONG_ID);
  const aMs = Math.round(Number(info.loopStartMs));
  const bMs = Math.round(Number(info.loopEndMs));
  const songPath = String(info.path);
  const L = bMs - aMs;
  const dur = Number(info.durationMs);
  console.log("选歌 #" + SONG_ID + " 「" + info.title + "」A=" + aMs + " B=" + bMs + " L=" + (L / 1000).toFixed(1) + "s dur=" + (dur / 1000).toFixed(1) + "s");
  console.log("path=" + songPath);

  const heap = await adb.meminfo(cfg.pkg);
  console.log("heap=" + heap.javaHeapKb + "KB  (预算 heap/10=" + Math.round((Number(heap.javaHeapKb) / 10) / 1024) + "MB)");
  const windowMb = ((L / 1000) * 44100 * 2 * 2) / 1048576;
  console.log("整窗 PCM 约 " + windowMb.toFixed(1) + "MB / 44.1k 立体声");

  // 切到 SOURCE 路线 + 干净起点
  await api.post("/debug/source-mode", { on: true });
  await api.post("/navigate", { route: "about" });
  await api.post("/loop/exit", {});
  await sleep(600);
  await adb.run(["logcat", "-c"], 10000);
  const play = await api.post("/player/play", { songId: SONG_ID, repeatOne: false });
  console.log("/player/play -> " + play.status);
  let ready = false;
  for (let i = 0; i < 90; i++) {
    await sleep(150);
    const s = await api.state();
    if (s.playback?.songId === SONG_ID && s.playback?.playbackState === "ready") { ready = true; break; }
  }
  console.log("ready=" + ready + " routeSource=" + (await api.state()).invariantProbe?.routeSource);
  const enter = await api.post("/loop/enter", { songId: SONG_ID, play: true, restart: true });
  console.log("/loop/enter -> " + enter.status + " " + JSON.stringify(enter.json).slice(0, 200));

  let st = await api.state();
  for (let i = 0; i < 100; i++) {
    await sleep(150);
    st = await api.state();
    if (st.loop?.window?.songId === SONG_ID && st.playback?.engine === "loop") break;
  }
  console.log("进入后: engine=" + st.playback?.engine + " loopPath=" + st.loop?.loopPath +
    " window=" + JSON.stringify(st.loop?.window) + " isReplaying=" + st.loop?.isReplaying +
    " degrade=" + st.loop?.degradeCount);

  // 等环建成/回退日志
  await sleep(2500);
  const fallbackLines = await logcatTail("fallback|RingBand|LoopSource|degrade|ring budget|seam", 1200);
  console.log("");
  console.log("== ring/fallback 日志 ==");
  for (const l of fallbackLines) console.log("  " + l.slice(0, 220));

  st = await api.state();
  const loopPath = String(st.loop?.loopPath ?? "?");
  const engine = String(st.playback?.engine ?? "?");
  console.log("loopPath=" + loopPath + " engine=" + engine);

  // seek 到 B-5s（媒体时间）
  const target = bMs - 5000;
  const seek = await api.post("/player/seek", { positionMs: target });
  console.log("");
  console.log("/player/seek " + target + " -> " + seek.status);
  await sleep(700);
  const seeked = await api.state();
  console.log("seek 后: pos=" + seeked.playback?.positionMs + " playing=" + seeked.playback?.playing +
    " engine=" + seeked.playback?.engine + " loopPath=" + seeked.loop?.loopPath);

  // 采样 display 跨 B 的行为（12s：B-5s 起 5s 后应跨 B）
  const series: { atMs: number; positionMs: number; state: string | null; engine: string | null }[] = [];
  const t0 = Date.now();
  let crossed = false;
  while (Date.now() - t0 < 13000) {
    const s = await api.state();
    const pos = Number(s.playback?.positionMs ?? -1);
    series.push({ atMs: Date.now() - t0, positionMs: pos, state: s.playback?.playbackState ?? null, engine: s.playback?.engine ?? null });
    if (pos < aMs + 5000) crossed = true;
    await sleep(200);
  }
  const maxPos = Math.max(...series.map((s) => s.positionMs));
  const minPos = Math.min(...series.map((s) => s.positionMs));
  const folded = series.some((s, i) => i > 0 && s.positionMs < series[i - 1].positionMs - L / 2);
  console.log("");
  console.log("== 跨 B 期间 /state 采样 (13s, " + series.length + " 点) ==");
  console.log("min=" + minPos + " max=" + maxPos + " 出现大幅折返=" + folded + " crossedToA=" + crossed);
  for (let i = 0; i < series.length; i += 6) console.log("  +" + series[i].atMs + "ms pos=" + series[i].positionMs + " state=" + series[i].state);
  const tailSample = series[series.length - 1];

  // 内容真值：取一段 tap，先按 band(显示位置) 找，再在文件时间上全域定位
  const cap = await stack.prober.capture({ target: "main", samplesMs: 300, dwellMs: 900, workDir: OUT, label: "after-cross" });
  const displayRef = cap.displayRef?.displayMs ?? null;
  const atStop = cap.displayAtStop?.displayMs ?? null;
  console.log("");
  console.log("tap: frames=" + cap.totalFrames + " sr=" + cap.sampleRate + " ch=" + cap.channels +
    " displayRef=" + displayRef + " displayAtStop=" + atStop + " seam事件=" + cap.seams.length +
    " configure=" + cap.configureCount + " flush=" + cap.flushCount);
  for (const w of cap.warnings) console.log("  warn: " + w);

  // 偏移基线：先在同位置再取一次？直接用 0 偏移做全域定位（不依赖基线）
  const window = cap.window;
  const sr = cap.sampleRate;
  console.log("");
  console.log("== 全域定位(文件时间 " + Math.max(0, aMs - 5000) + ".." + Math.min(dur, bMs + 30000) + ") ==");
  const best: { pos: number; peak: number }[] = [];
  const regions: { from: number; to: number }[] = [
    { from: Math.max(0, bMs - 1000), to: Bc(bMs, dur, 20000) },
    { from: aMs, to: Math.min(bMs, aMs + 20000) },
  ];
  for (const r of regions) {
    const chunks = await collectChunks(api, songPath, r.from, r.to, { chunkMs: 1200, overlapMs: 400, maxChunks: 40, tolerant: true });
    for (const c of chunks) {
      const m = nccBest(window, c.mono, c.sampleRate);
      const pos = c.startMs + (m.lagFrames * 1000) / c.sampleRate;
      if (m.peak > 0.5) best.push({ pos, peak: m.peak });
      if (best.length === 0 || m.peak > best.reduce((x, y) => (y.peak > x.peak ? y : x), best[0]).peak) {
        // keep going
      }
    }
    console.log("  区域 " + r.from + ".." + r.to + " -> 片 " + chunks.length);
  }
  best.sort((x, y) => y.peak - x.peak);
  console.log("  最佳匹配: " + (best.length ? best.slice(0, 5).map((b) => "pos=" + Math.round(b.pos) + "ms peak=" + b.peak.toFixed(3)).join(" | ") : "无(peak 全 <0.5)"));

  const top = best[0];
  const display = displayRef ?? tailSample?.positionMs ?? null;
  const delta = top && display != null ? top.pos - display : null;
  console.log("");
  console.log("== 判定 ==");
  console.log("loopPath=" + loopPath + " engine=" + engine);
  console.log("跨B期间 display 折返=" + folded + " (最大 " + maxPos + "ms, 最小 " + minPos + "ms)");
  console.log("tap 内容实际在文件 " + (top ? Math.round(top.pos) + "ms" : "?") + " (peak " + (top ? top.peak.toFixed(3) : "?") + ")");
  console.log("display=" + display + "ms  内容-display=" + (delta == null ? "?" : Math.round(delta) + "ms"));
  const inPostB = top != null && top.pos >= bMs - 1500 && top.pos <= bMs + 25000;
  const inPreA = top != null && Math.abs(top.pos - display!) <= 800;
  console.log("结论: " + (inPostB ? "内容在 B 之后(源已不是环带/直通)" : inPreA ? "内容与显示一致" : "内容位置异常，需人工看"));

  fs.mkdirSync(OUT, { recursive: true });
  fs.writeFileSync(path.join(OUT, "summary.json"), JSON.stringify({
    device: DEVICE, songId: SONG_ID, aMs, bMs, L, dur, songPath, heapKb: heap.javaHeapKb, windowMb,
    loopPath, engine, target, seekStatus: seek.status, folded, maxPos, minPos,
    displayRef, displayAtStop: atStop, seams: cap.seams.length, configure: cap.configureCount,
    tapWav: cap.wavPath, tapJsonl: cap.jsonlPath, series, bestMatches: best.slice(0, 8),
    fallbackLines,
  }, null, 2), "utf8");
  console.log("产物: " + OUT);
}

function Bc(b: number, dur: number, extra: number): number {
  return Math.min(dur, b + extra);
}

main().then(() => process.exit(0)).catch((e) => { console.error("失败: " + (e?.stack ?? e)); process.exit(2); });
