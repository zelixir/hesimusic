#!/usr/bin/env bun
/** 临时真机脚本：B 主播放器 M3 / D 预览 FULL —— 同上内容真值。 */
import * as fs from "node:fs";
import * as path from "node:path";
import { Adb, Api, sleep } from "./harness";
import type { Cfg } from "./harness";
import { createProber } from "./content-probe";

const DEVICE = "b1e84dad";
const STAMP = new Date().toISOString().replace(/[:.]/g, "-").slice(0, 19);
const OUT = path.join("e2e-artifacts", "bd-00027-" + STAMP);
const cfg: Cfg = { device: DEVICE, base: "http://127.0.0.1:18765", forwardLocal: 18765, forwardRemote: 8765, outDir: OUT,
  adbExe: process.env.ANDROID_HOME ? path.join(process.env.ANDROID_HOME, "platform-tools", "adb.exe") : "adb",
  pkg: "com.zjr.hesimusic", scenarioTimeoutMs: 90000 };
const adb = new Adb(cfg.adbExe, DEVICE);
const api = new Api(cfg, adb);
const stack = createProber(cfg, api, { workDir: OUT, log: () => {} });

async function previewSnap() { const p = await api.preview(); return p.available ? p.snapshot : null; }
async function displayOf(t: string) { if (t === "preview") { const s = await previewSnap(); const v = s?.liveDisplayMs ?? s?.displayMs; return v == null ? null : Number(v); } const s = await api.state(); return s.playback?.positionMs == null ? null : Number(s.playback.positionMs); }
async function framesOf(t: string) { if (t === "preview") { const s = await previewSnap(); return s?.outputFrames == null ? null : Number(s.outputFrames); } const s = await api.state(); return s.invariantProbe?.writtenFrames == null ? null : Number(s.invariantProbe.writtenFrames); }
async function tapByText(label: string) { for (let a = 0; a < 5; a++) { const d = await adb.uiDump(); const n = d.nodes.find((x) => x.text.trim() === label) ?? d.nodes.find((x) => x.text.includes(label)); if (n) { await adb.tap((n.bounds.left + n.bounds.right) / 2, (n.bounds.top + n.bounds.bottom) / 2); return true; } await sleep(400); } return false; }
async function probe(target: "main" | "preview", label: string, songPath: string, aMs: number, bMs: number, offset: number) {
  const cap = await stack.prober.capture({ target, samplesMs: 300, dwellMs: 900, workDir: OUT, label });
  const displayRef = cap.displayRef?.displayMs ?? null;
  const res = await stack.truth.probeCapture(cap, { songPath, aMs, bMs, expectedContentMs: (displayRef ?? 0) + offset, mode: "band", wide: true, driftTolMs: 700, caption: label });
  console.log("    " + label + ": peak=" + res.peak.toFixed(3) + " display=" + displayRef + " drift=" + (res.driftMs == null ? "n/a" : Math.round(res.driftMs) + "ms") + (res.wide?.length ? " wide=" + res.wide[0].region + "@" + Math.round(res.wide[0].positionMs) + "(" + res.wide[0].peak.toFixed(2) + ")" : ""));
  return { res, cap, displayRef };
}

async function main() {
  await adb.forwardEnsure(cfg.forwardLocal, cfg.forwardRemote);
  const all = await api.songsAll();
  const cands = all.filter((s) => s.hasLoop && s.loopConfirmed && s.loopStartMs != null && s.loopEndMs != null &&
    Number(s.loopStartMs) >= 3000 && Number(s.loopEndMs) - Number(s.loopStartMs) >= 12000 && Number(s.loopEndMs) - Number(s.loopStartMs) <= 40000 &&
    s.durationMs > Number(s.loopEndMs) + 3000 && /\.(mp3|wav|m4a|aac|flac)$/i.test(s.path));
  cands.sort((a, b) => Number(a.loopEndMs) - Number(a.loopStartMs) - (Number(b.loopEndMs) - Number(b.loopStartMs)) || a.id - b.id);
  const song = cands[0];
  const info = await api.song(song.id);
  const aMs = Math.round(Number(info.loopStartMs)); const bMs = Math.round(Number(info.loopEndMs));
  const songPath = String(info.path);
  console.log("选歌 #" + song.id + " A=" + aMs + " B=" + bMs + " L=" + ((bMs - aMs) / 1000).toFixed(1) + "s");

  // B 主播放器 M3
  console.log("");
  console.log("== B 主播放器 M3(source-mode off) ==");
  const off = await api.post("/debug/source-mode", { on: false });
  console.log("    source-mode -> " + JSON.stringify(off.json));
  await api.post("/navigate", { route: "about" }); await api.post("/loop/exit", {}); await sleep(600);
  await api.post("/player/play", { songId: song.id, repeatOne: false });
  for (let i = 0; i < 80; i++) { await sleep(150); const s = await api.state(); if (s.playback?.songId === song.id && s.playback?.playbackState === "ready") break; }
  await api.post("/loop/enter", { songId: song.id, play: true, restart: true });
  for (let i = 0; i < 90; i++) { await sleep(150); const s = await api.state(); if (s.playback?.engine === "loop") break; }
  let s = await api.state();
  console.log("    engine=" + s.playback?.engine + " loopPath=" + s.loop?.loopPath + " routeSource=" + s.invariantProbe?.routeSource);
  await api.post("/player/seek", { positionMs: Math.round(aMs + (bMs - aMs) / 2) });
  await sleep(1100);
  let base: any = null, offset = 0;
  try { base = await probe("main", "校准", songPath, aMs, bMs, 0); offset = base.res.driftMs ?? 0; } catch (e: any) { console.log("    校准失败: " + e.message); }
  await api.post("/player/seek", { positionMs: bMs - 5000 });
  await sleep(400);
  let before: any = null, after: any = null, folded = false;
  try { before = await probe("main", "跨B前", songPath, aMs, bMs, offset); } catch (e: any) { console.log("    跨B前失败: " + e.message); }
  { let prev: number | null = null; const t0 = Date.now(); while (Date.now() - t0 < 12000) { const d = await displayOf("main"); if (d != null) { if (prev != null && d < prev - (bMs - aMs) / 2) { folded = true; break; } prev = d; } await sleep(120); } }
  await sleep(700);
  try { after = await probe("main", "跨B后", songPath, aMs, bMs, offset); } catch (e: any) { console.log("    跨B后失败: " + e.message); }
  console.log("    M3 折返=" + folded + " base=" + (base?.res.peak.toFixed(3) ?? "n/a") + " before=" + (before?.res.peak.toFixed(3) ?? "n/a") + " after=" + (after?.res.peak.toFixed(3) ?? "n/a"));
  const m3 = { engine: "m3", loopPath: String((await api.state()).loop?.loopPath), folded, base: base?.res.peak ?? null, before: before?.res.peak ?? null, after: after?.res.peak ?? null };

  // D 预览 FULL
  console.log("");
  console.log("== D 预览 FULL(完整循环) ==");
  await api.post("/debug/source-mode", { on: true });
  await api.post("/navigate", { route: "loop/" + song.id });
  for (let i = 0; i < 150; i++) { await sleep(150); const p = await api.preview(); if (p.available) break; }
  await sleep(700);
  const t0 = Date.now();
  const ok = await tapByText("完整循环");
  let stalled = 0;
  for (let i = 0; i < 120; i++) { await sleep(100); const sp = await previewSnap(); if (sp?.isPlaying === true) { stalled = Date.now() - t0; break; } }
  const sp = await previewSnap();
  console.log("    tap=" + ok + " 到出声=" + stalled + "ms engine=" + sp?.engine + " mode=" + sp?.mode + " sourceKind=" + sp?.sourceKind + " display=" + (sp?.liveDisplayMs ?? sp?.displayMs));
  let dBase: any = null, dOffset = 0;
  const mid = await api.post("/debug/preview-seek", { positionMs: Math.round(aMs + (bMs - aMs) / 2) }, 12000).catch(() => ({ status: 0, json: null, text: "" } as any));
  console.log("    preview-seek 中点 -> " + mid.status);
  let dBefore: any = null, dAfter: any = null, dFolded = false;
  if (mid.status === 200) {
    await sleep(1100);
    try { dBase = await probe("preview", "校准", songPath, aMs, bMs, 0); dOffset = dBase.res.driftMs ?? 0; } catch (e: any) { console.log("    校准失败: " + e.message); }
    const tgt = await api.post("/debug/preview-seek", { positionMs: bMs - 5000 }, 12000).catch(() => ({ status: 0 } as any));
    console.log("    seek B-5s -> " + tgt.status);
    await sleep(400);
    try { dBefore = await probe("preview", "跨B前", songPath, aMs, bMs, dOffset); } catch (e: any) { console.log("    跨B前失败: " + e.message); }
    { let prev: number | null = null; const t1 = Date.now(); while (Date.now() - t1 < 12000) { const d = await displayOf("preview"); if (d != null) { if (prev != null && d < prev - (bMs - aMs) / 2) { dFolded = true; break; } prev = d; } await sleep(120); } }
    await sleep(700);
    try { dAfter = await probe("preview", "跨B后", songPath, aMs, bMs, dOffset); } catch (e: any) { console.log("    跨B后失败: " + e.message); }
  }
  const full = { engine: String(sp?.engine), mode: String(sp?.mode), sourceKind: String(sp?.sourceKind), stallMs: stalled, seekStatus: mid.status,
    folded: dFolded, base: dBase?.res.peak ?? null, before: dBefore?.res.peak ?? null, after: dAfter?.res.peak ?? null };
  console.log("    FULL 折返=" + dFolded + " base=" + (dBase?.res.peak.toFixed(3) ?? "n/a") + " before=" + (dBefore?.res.peak.toFixed(3) ?? "n/a") + " after=" + (dAfter?.res.peak.toFixed(3) ?? "n/a"));

  await api.post("/debug/source-mode", { on: true });
  await api.post("/loop/exit", {}).catch(() => null);
  await api.post("/navigate", { route: "about" }).catch(() => null);
  fs.mkdirSync(OUT, { recursive: true });
  fs.writeFileSync(path.join(OUT, "summary.json"), JSON.stringify({ device: DEVICE, songId: song.id, aMs, bMs, m3, full }, null, 2), "utf8");
  console.log("");
  console.log("B: " + JSON.stringify(m3));
  console.log("D: " + JSON.stringify(full));
  console.log("产物: " + OUT);
}
main().then(() => process.exit(0)).catch((e) => { console.error("失败: " + (e?.stack ?? e)); process.exit(2); });
