#!/usr/bin/env bun
/** 1269 验证: (b) 41126 主播放器跨B内容真值 / (c) >预算窗口不投影 / (d) 40169 回归 */
import * as fs from "node:fs";
import * as path from "node:path";
import { Adb, Api, sleep } from "./harness";
import type { Cfg } from "./harness";
import { createProber } from "./content-probe";

const DEVICE = "b1e84dad";
const STAMP = new Date().toISOString().replace(/[:.]/g, "-").slice(0, 19);
const OUT = path.join("e2e-artifacts", "verify1269-" + STAMP);
const cfg: Cfg = { device: DEVICE, base: "http://127.0.0.1:18765", forwardLocal: 18765, forwardRemote: 8765, outDir: OUT,
  adbExe: process.env.ANDROID_HOME ? path.join(process.env.ANDROID_HOME, "platform-tools", "adb.exe") : "adb",
  pkg: "com.zjr.hesimusic", scenarioTimeoutMs: 90000 };
const adb = new Adb(cfg.adbExe, DEVICE);
const api = new Api(cfg, adb);
const stack = createProber(cfg, api, { workDir: OUT, log: () => {} });
const rows: any[] = [];

async function probeMain(label: string, songPath: string, aMs: number, bMs: number, offset: number) {
  const cap = await stack.prober.capture({ target: "main", samplesMs: 300, dwellMs: 900, workDir: OUT, label });
  const displayRef = cap.displayRef?.displayMs ?? null;
  const res = await stack.truth.probeCapture(cap, { songPath, aMs, bMs, expectedContentMs: (displayRef ?? 0) + offset, mode: "band", wide: true, driftTolMs: 700, caption: label });
  console.log("    " + label + ": peak=" + res.peak.toFixed(3) + " display=" + displayRef + " drift=" + (res.driftMs == null ? "n/a" : Math.round(res.driftMs) + "ms") + (res.wide?.length ? " wide=" + res.wide[0].region + "@" + Math.round(res.wide[0].positionMs) + "(" + res.wide[0].peak.toFixed(2) + ")" : ""));
  return { res, cap, displayRef };
}

async function enterLoop(songId: number) {
  await api.post("/navigate", { route: "about" });
  await api.post("/loop/exit", {});
  await sleep(500);
  await api.post("/player/play", { songId, repeatOne: false });
  for (let i = 0; i < 80; i++) { await sleep(150); const s = await api.state(); if (s.playback?.songId === songId && s.playback?.playbackState === "ready") break; }
  await api.post("/loop/enter", { songId, play: true, restart: true });
  let s = await api.state();
  for (let i = 0; i < 100; i++) { await sleep(150); s = await api.state(); if (s.loop?.window?.songId === songId) break; }
  return s;
}

async function foldWatch(aMs: number, L: number, ms: number) {
  const series: any[] = [];
  let folded = false; let prev: number | null = null;
  const t0 = Date.now();
  while (Date.now() - t0 < ms) {
    const s = await api.state();
    const pos = Number(s.playback?.positionMs ?? -1);
    series.push({ atMs: Date.now() - t0, pos, state: s.playback?.playbackState });
    if (prev != null && pos < prev - L / 2) folded = true;
    prev = pos;
    await sleep(180);
  }
  return { folded, series, maxPos: Math.max(...series.map((x) => x.pos)), minPos: Math.min(...series.map((x) => x.pos)) };
}

/** (b)/(d): 主播放器跨 B 内容真值 */
async function crossBCheck(tag: string, songId: number, expectSource: boolean) {
  const info = await api.song(songId);
  const aMs = Math.round(Number(info.loopStartMs)); const bMs = Math.round(Number(info.loopEndMs));
  const songPath = String(info.path); const L = bMs - aMs;
  console.log("");
  console.log("== " + tag + " #" + songId + " A=" + aMs + " B=" + bMs + " L=" + (L / 1000).toFixed(1) + "s ==");
  const st = await enterLoop(songId);
  const engine = String(st.playback?.engine); const loopPath = String(st.loop?.loopPath);
  console.log("    engine=" + engine + " loopPath=" + loopPath + " window=" + JSON.stringify(st.loop?.window));
  console.log("    loop 事实: " + JSON.stringify(st.loop).slice(0, 420));
  const row: any = { tag, songId, aMs, bMs, L, engine, loopPath, sourceAvailable: (st.loop as any)?.sourceAvailable ?? null };
  await api.post("/player/seek", { positionMs: Math.round(aMs + L / 2) });
  await sleep(1200);
  const base = await probeMain("校准", songPath, aMs, bMs, 0);
  const offset = base.res.driftMs ?? 0;
  await api.post("/player/seek", { positionMs: bMs - 5000 });
  await sleep(500);
  const before = await probeMain("跨B前", songPath, aMs, bMs, offset);
  const watch = await foldWatch(aMs, L, 14000);
  await sleep(700);
  const after = await probeMain("跨B后", songPath, aMs, bMs, offset);
  Object.assign(row, {
    basePeak: base.res.peak, beforePeak: before.res.peak, afterPeak: after.res.peak,
    beforeDisplay: before.displayRef, afterDisplay: after.displayRef,
    afterDrift: after.res.driftMs, folded: watch.folded, minPos: watch.minPos, maxPos: watch.maxPos,
    offset: Math.round(offset), tapAfter: after.cap.wavPath, series: watch.series,
  });
  row.verdict = row.afterPeak >= 0.9 && watch.folded ? "PASS(折返且内容一致)" : row.afterPeak >= 0.9 && !watch.folded ? "内容一致但未折返" : "FAIL";
  rows.push(row);
  return row;
}

async function main() {
  await adb.forwardEnsure(cfg.forwardLocal, cfg.forwardRemote);
  const ver = await adb.versionInfo(cfg.pkg);
  const st0 = await api.state();
  console.log("== 环境 == versionCode=" + ver.versionCode + " versionName=" + ver.versionName + " routeSource=" + st0.invariantProbe?.routeSource);
  const heap = await adb.meminfo(cfg.pkg);
  console.log("heap=" + heap.javaHeapKb + "KB  heap/6=" + (Number(heap.javaHeapKb) / 6 / 1024).toFixed(1) + "MB");

  // (b) 41126
  const b = await crossBCheck("(b) 41126 长窗口(修复后)", 41126, true);

  // (d) 40169
  const d = await crossBCheck("(d) 40169 回归", 40169, true);

  // (c) 造 >预算窗口
  console.log("");
  console.log("== (c) 造 >预算窗口 ==");
  const all = await api.songsAll();
  const sorted = all.filter((s) => /\.(mp3|wav|m4a|aac|flac)$/i.test(s.path)).sort((x, y) => y.durationMs - x.durationMs);
  if (!sorted.length) throw new Error("没有可用歌");
  const hold = sorted[0];
  const orig = await api.song(hold.id);
  const songPath = String(orig.path);
  const sr = Number(orig.sampleRate ?? 44100) || 44100;
  const dur = Number(orig.durationMs);
  const aMs = 3000;
  const bMs = Math.min(dur - 3000, 405000);
  const windowBytes = ((bMs - aMs) / 1000) * sr * 2 * 2;
  console.log("    歌 #" + hold.id + " dur=" + (dur / 1000).toFixed(1) + "s 造窗口 A=" + aMs + " B=" + bMs + " 整窗=" + (windowBytes / 1048576).toFixed(1) + "MB");
  const row: any = { tag: "(c) >预算窗口 不投影", songId: hold.id, aMs, bMs, windowMb: windowBytes / 1048576, sr };
  try {
    const conf = await api.post("/confirm", { songId: hold.id, aSamples: Math.round(aMs * sr / 1000), bSamples: Math.round(bMs * sr / 1000) });
    console.log("    /confirm -> " + conf.status + " " + JSON.stringify(conf.json).slice(0, 200));
    const st = await enterLoop(hold.id);
    const engine = String(st.playback?.engine); const loopPath = String(st.loop?.loopPath);
    console.log("    engine=" + engine + " loopPath=" + loopPath + " window=" + JSON.stringify(st.loop?.window));
    console.log("    loop 事实: " + JSON.stringify(st.loop).slice(0, 420));
    Object.assign(row, { engine, loopPath, sourceAvailable: (st.loop as any)?.sourceAvailable ?? null, windowState: st.loop?.window ?? null });
    // seek 到 B-5s，看跨 B 时 position 是否继续媒体时间（不折返）
    await api.post("/player/seek", { positionMs: bMs - 5000 });
    await sleep(400);
    const atSeek = Number((await api.state()).playback?.positionMs ?? -1);
    const watch = await foldWatch(aMs, bMs - aMs, 15000);
    Object.assign(row, { atSeek, folded: watch.folded, minPos: watch.minPos, maxPos: watch.maxPos, series: watch.series });
    // UI：A/B 标记
    const dump = await adb.uiDump();
    const labels = dump.nodes.map((n) => n.text.trim()).filter(Boolean);
    row.uiLabels = labels.slice(0, 40);
    row.loopUiHints = labels.filter((t) => /循环|A|B|区间/.test(t)).slice(0, 12);
    console.log("    position 折返=" + watch.folded + " min=" + watch.minPos + " max=" + watch.maxPos + " (B=" + bMs + ")");
    console.log("    UI 文本: " + labels.slice(0, 18).join(" | "));
    row.verdict = !watch.folded && watch.maxPos > bMs ? "PASS(不折返,继续媒体时间)" : "FAIL(仍折返)";
  } catch (e: any) {
    row.error = String(e?.message ?? e);
    console.log("    !! " + row.error);
  } finally {
    // 恢复原 A/B
    try {
      const origA = Number(orig.loopStartSamples); const origB = Number(orig.loopEndSamples);
      if (Number.isFinite(origA) && Number.isFinite(origB)) {
        await api.post("/loop/exit", {});
        await api.post("/confirm", { songId: hold.id, aSamples: origA, bSamples: origB });
        console.log("    已恢复原 A/B: " + origA + ".." + origB);
      }
    } catch (e: any) { console.log("    恢复失败: " + e.message); }
  }
  rows.push(row);

  await api.post("/loop/exit", {}).catch(() => null);
  await api.post("/navigate", { route: "about" }).catch(() => null);

  console.log("");
  console.log("== 汇总 ==");
  for (const r of rows) {
    console.log([r.tag, "engine=" + r.engine, "loopPath=" + r.loopPath,
      "base=" + (r.basePeak == null ? "n/a" : Number(r.basePeak).toFixed(3)),
      "before=" + (r.beforePeak == null ? "n/a" : Number(r.beforePeak).toFixed(3)),
      "after=" + (r.afterPeak == null ? "n/a" : Number(r.afterPeak).toFixed(3)),
      "折返=" + r.folded, r.verdict].join(" | "));
  }
  fs.mkdirSync(OUT, { recursive: true });
  fs.writeFileSync(path.join(OUT, "summary.json"), JSON.stringify({ version: ver, heapKb: heap.javaHeapKb, rows }, null, 2), "utf8");
  console.log("产物: " + OUT);
}
main().then(() => process.exit(0)).catch((e) => { console.error("失败: " + (e?.stack ?? e)); process.exit(2); });
