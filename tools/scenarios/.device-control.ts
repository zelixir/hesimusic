#!/usr/bin/env bun
/** 临时真机脚本：对照组（环带建成功的主播放器 / 预览）——内容真值应 >=0.95。 */
import * as fs from "node:fs";
import * as path from "node:path";
import { Adb, Api, sleep } from "./harness";
import type { Cfg } from "./harness";
import { createProber } from "./content-probe";

const DEVICE = "b1e84dad";
const STAMP = new Date().toISOString().replace(/[:.]/g, "-").slice(0, 19);
const OUT = path.join("e2e-artifacts", "control-00027-" + STAMP);
const cfg: Cfg = {
  device: DEVICE, base: "http://127.0.0.1:18765", forwardLocal: 18765, forwardRemote: 8765,
  outDir: OUT, adbExe: process.env.ANDROID_HOME ? path.join(process.env.ANDROID_HOME, "platform-tools", "adb.exe") : "adb",
  pkg: "com.zjr.hesimusic", scenarioTimeoutMs: 90000,
};
const adb = new Adb(cfg.adbExe, DEVICE);
const api = new Api(cfg, adb);
const stack = createProber(cfg, api, { workDir: OUT, log: () => {} });

async function previewSnap() { const p = await api.preview(); return p.available ? p.snapshot : null; }
async function displayOf(target: string) {
  if (target === "preview") { const s = await previewSnap(); const v = s?.liveDisplayMs ?? s?.displayMs; return v == null ? null : Number(v); }
  const s = await api.state(); return s.playback?.positionMs == null ? null : Number(s.playback.positionMs);
}
async function framesOf(target: string) {
  if (target === "preview") { const s = await previewSnap(); return s?.outputFrames == null ? null : Number(s.outputFrames); }
  const s = await api.state(); return s.invariantProbe?.writtenFrames == null ? null : Number(s.invariantProbe.writtenFrames);
}
async function tapByText(label: string): Promise<boolean> {
  for (let a = 0; a < 5; a++) {
    const d = await adb.uiDump();
    const n = d.nodes.find((x) => x.text.trim() === label) ?? d.nodes.find((x) => x.text.includes(label));
    if (n) { await adb.tap((n.bounds.left + n.bounds.right) / 2, (n.bounds.top + n.bounds.bottom) / 2); return true; }
    await sleep(400);
  }
  return false;
}
async function probe(target: "main" | "preview", label: string, songPath: string, aMs: number, bMs: number, offset: number) {
  const cap = await stack.prober.capture({ target, samplesMs: 300, dwellMs: 900, workDir: OUT, label });
  const displayRef = cap.displayRef?.displayMs ?? null;
  const frames = await framesOf(target);
  const res = await stack.truth.probeCapture(cap, {
    songPath, aMs, bMs, expectedContentMs: (displayRef ?? 0) + offset, mode: "band", wide: true, driftTolMs: 700, caption: label,
  });
  console.log("    " + label + ": peak=" + res.peak.toFixed(3) + " display=" + displayRef + " drift=" + (res.driftMs == null ? "n/a" : Math.round(res.driftMs) + "ms") + (res.wide?.length ? " wide=" + res.wide[0].region + "@" + Math.round(res.wide[0].positionMs) + "(" + res.wide[0].peak.toFixed(2) + ")" : ""));
  return { res, cap, displayRef, frames };
}

async function run(name: string, target: "main" | "preview", song: any, aMs: number, bMs: number, songPath: string, modeLabel?: string) {
  console.log("");
  console.log("== " + name + " ==");
  const row: any = { name, target, engine: "?", loopPath: "?", peaks: {}, drift: {} };
  try {
    if (target === "main") {
      await api.post("/navigate", { route: "about" }); await api.post("/loop/exit", {}); await sleep(500);
      await api.post("/player/play", { songId: song.id, repeatOne: false });
      for (let i = 0; i < 80; i++) { await sleep(150); const s = await api.state(); if (s.playback?.songId === song.id && s.playback?.playbackState === "ready") break; }
      await api.post("/loop/enter", { songId: song.id, play: true, restart: true });
      for (let i = 0; i < 80; i++) { await sleep(150); const s = await api.state(); if (s.playback?.engine === "loop") break; }
      const s = await api.state();
      row.engine = String(s.playback?.engine); row.loopPath = String(s.loop?.loopPath);
    } else {
      await api.post("/navigate", { route: "loop/" + song.id });
      for (let i = 0; i < 150; i++) { await sleep(150); const p = await api.preview(); if (p.available) break; }
      await sleep(600);
      const ok = await tapByText(modeLabel ?? "仅循环段");
      console.log("    tap " + (modeLabel ?? "仅循环段") + " -> " + ok);
      for (let i = 0; i < 100; i++) { await sleep(150); const sp = await previewSnap(); if (sp?.isPlaying === true) break; }
      const sp = await previewSnap();
      row.engine = String(sp?.engine); row.loopPath = "preview:" + String(sp?.mode) + "/" + String(sp?.sourceKind);
    }
    console.log("    engine=" + row.engine + " loopPath=" + row.loopPath);

    // 校准
    if (target === "main") { await api.post("/player/seek", { positionMs: Math.round(aMs + (bMs - aMs) / 2) }); }
    else { await api.post("/debug/preview-seek", { positionMs: Math.round(aMs + (bMs - aMs) / 2) }, 12000); }
    await sleep(1100);
    const base = await probe(target, "校准", songPath, aMs, bMs, 0);
    const offset = base.res.driftMs ?? 0;
    row.peaks.baseline = base.res.peak;
    row.drift.baseline = Math.round(offset);

    const target2 = bMs - 5000;
    if (target === "main") await api.post("/player/seek", { positionMs: target2 });
    else await api.post("/debug/preview-seek", { positionMs: target2 }, 12000);
    await sleep(400);
    const before = await probe(target, "跨B前", songPath, aMs, bMs, offset);
    row.peaks.before = before.res.peak; row.drift.before = before.res.driftMs == null ? null : Math.round(before.res.driftMs);

    let folded = false; let prev: number | null = null;
    const t0 = Date.now();
    while (Date.now() - t0 < 12000) {
      const d = await displayOf(target);
      if (d != null) { if (prev != null && d < prev - (bMs - aMs) / 2) { folded = true; break; } prev = d; }
      await sleep(120);
    }
    row.folded = folded;
    console.log("    跨B折返=" + folded);
    await sleep(700);
    const after = await probe(target, "跨B后", songPath, aMs, bMs, offset);
    row.peaks.after = after.res.peak; row.drift.after = after.res.driftMs == null ? null : Math.round(after.res.driftMs);
    row.displayBefore = before.displayRef; row.displayAfter = after.displayRef;
    row.framesBefore = before.frames; row.framesAfter = after.frames;
    row.tapAfter = after.cap.wavPath;
  } catch (e: any) {
    row.error = String(e?.message ?? e);
    console.log("    !! " + row.error);
  }
  return row;
}

async function main() {
  await adb.forwardEnsure(cfg.forwardLocal, cfg.forwardRemote);
  await api.post("/debug/source-mode", { on: true });
  const all = await api.songsAll();
  const cands = all.filter((s) => s.hasLoop && s.loopConfirmed && s.loopStartMs != null && s.loopEndMs != null &&
    Number(s.loopStartMs) >= 3000 && Number(s.loopEndMs) - Number(s.loopStartMs) >= 12000 &&
    Number(s.loopEndMs) - Number(s.loopStartMs) <= 40000 && s.durationMs > Number(s.loopEndMs) + 3000 &&
    /\.(mp3|wav|m4a|aac|flac)$/i.test(s.path));
  cands.sort((a, b) => Number(a.loopEndMs) - Number(a.loopStartMs) - (Number(b.loopEndMs) - Number(b.loopStartMs)) || a.id - b.id);
  if (!cands.length) throw new Error("没有短环带歌");
  const song = cands[0];
  const info = await api.song(song.id);
  const aMs = Math.round(Number(info.loopStartMs)); const bMs = Math.round(Number(info.loopEndMs));
  const heap = await adb.meminfo(cfg.pkg);
  const windowMb = (((bMs - aMs) / 1000) * 44100 * 2 * 2) / 1048576;
  console.log("选歌 #" + song.id + " 「" + song.title + "」A=" + aMs + " B=" + bMs + " L=" + ((bMs - aMs) / 1000).toFixed(1) + "s");
  console.log("heap=" + heap.javaHeapKb + "KB heap/10=" + (Number(heap.javaHeapKb) / 10 / 1024).toFixed(1) + "MB 整窗=" + windowMb.toFixed(1) + "MB");
  const rows = [];
  rows.push(await run("对照组1 主播放器/环带", "main", song, aMs, bMs, String(info.path)));
  rows.push(await run("对照组2 预览/仅循环段", "preview", song, aMs, bMs, String(info.path), "仅循环段"));
  console.log("");
  console.log("== 对照表 ==");
  for (const r of rows) {
    console.log([r.name, "engine=" + r.engine, "loopPath=" + r.loopPath, "base=" + (r.peaks?.baseline == null ? "n/a" : r.peaks.baseline.toFixed(3)),
      "before=" + (r.peaks?.before == null ? "n/a" : r.peaks.before.toFixed(3)), "after=" + (r.peaks?.after == null ? "n/a" : r.peaks.after.toFixed(3)),
      "折返=" + r.folded, r.error ? "ERR:" + r.error : ""].join(" | "));
  }
  fs.mkdirSync(OUT, { recursive: true });
  fs.writeFileSync(path.join(OUT, "summary.json"), JSON.stringify({ device: DEVICE, songId: song.id, aMs, bMs, rows }, null, 2), "utf8");
  console.log("产物: " + OUT);
}
main().then(() => process.exit(0)).catch((e) => { console.error("失败: " + (e?.stack ?? e)); process.exit(2); });
