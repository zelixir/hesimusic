#!/usr/bin/env bun
/** (c) 1269: 造 >预算窗口，验证不建环/不投影 */
import * as fs from "node:fs";
import * as path from "node:path";
import { Adb, Api, sleep } from "./harness";
import type { Cfg } from "./harness";

const DEVICE = "b1e84dad";
const STAMP = new Date().toISOString().replace(/[:.]/g, "-").slice(0, 19);
const OUT = path.join("e2e-artifacts", "verify1269c-" + STAMP);
const cfg: Cfg = { device: DEVICE, base: "http://127.0.0.1:18765", forwardLocal: 18765, forwardRemote: 8765, outDir: OUT,
  adbExe: process.env.ANDROID_HOME ? path.join(process.env.ANDROID_HOME, "platform-tools", "adb.exe") : "adb",
  pkg: "com.zjr.hesimusic", scenarioTimeoutMs: 90000 };
const adb = new Adb(cfg.adbExe, DEVICE);
const api = new Api(cfg, adb);

async function main() {
  await adb.forwardEnsure(cfg.forwardLocal, cfg.forwardRemote);
  const all = await api.songsAll();
  // /confirm 的显式 A/B 需要 sampleRate —— 只有已存在 loop 行的歌才拿得到，所以选最长的"已有循环行"歌。
  const sorted = all
    .filter((s) => /\.(mp3|wav|m4a|aac|flac)$/i.test(s.path) && s.hasLoop && s.sampleRate != null)
    .sort((x, y) => y.durationMs - x.durationMs);
  const hold = sorted[0];
  const orig = await api.song(hold.id);
  const sr = Number(orig.sampleRate ?? 44100) || 44100;
  const dur = Number(orig.durationMs);
  const aMs = 3000;
  const bMs = Math.min(Math.floor((dur - 3000) / 1000) * 1000, 405000);
  const bytes = ((bMs - aMs) / 1000) * sr * 2 * 2;
  console.log("歌 #" + hold.id + " dur=" + (dur / 1000).toFixed(1) + "s sr=" + sr + " 造窗 A=" + aMs + " B=" + bMs + " = " + (bytes / 1048576).toFixed(1) + "MB");
  console.log("原 A/B samples=" + orig.loopStartSamples + ".." + orig.loopEndSamples);
  const out: any = { songId: hold.id, dur, sr, aMs, bMs, windowMb: bytes / 1048576, orig: { a: orig.loopStartSamples, b: orig.loopEndSamples } };
  try {
    let conf = await api.post("/confirm", { songId: hold.id, aSamples: Math.round((aMs * sr) / 1000), bSamples: Math.round((bMs * sr) / 1000), sampleRate: sr });
    console.log("/confirm(+sampleRate) -> " + conf.status + " " + JSON.stringify(conf.json).slice(0, 200));
    if (conf.status !== 200) {
      conf = await api.post("/confirm", { songId: hold.id, aSamples: Math.round((aMs * sr) / 1000), bSamples: Math.round((bMs * sr) / 1000), sampleRate: sr, aMs, bMs });
      console.log("/confirm(+aMs/bMs) -> " + conf.status + " " + JSON.stringify(conf.json).slice(0, 200));
    }
    out.confirm = { status: conf.status, body: conf.json };
    if (conf.status !== 200) throw new Error("/confirm 失败: " + conf.status);
    const after = await api.song(hold.id);
    console.log("落库 A/B: " + after.loopStartSamples + ".." + after.loopEndSamples + " sr=" + after.sampleRate);

    await api.post("/navigate", { route: "about" });
    await api.post("/loop/exit", {});
    await sleep(500);
    await api.post("/player/play", { songId: hold.id, repeatOne: false });
    for (let i = 0; i < 100; i++) { await sleep(150); const s = await api.state(); if (s.playback?.songId === hold.id && s.playback?.playbackState === "ready") break; }
    await api.post("/loop/enter", { songId: hold.id, play: true, restart: true });
    await sleep(2500);
    let st = await api.state();
    console.log("engine(playback)=" + st.playback?.engine);
    console.log("loop 事实: " + JSON.stringify(st.loop));
    out.loopFacts = st.loop;
    await api.post("/player/seek", { positionMs: bMs - 5000 });
    await sleep(500);
    const atSeek = Number((await api.state()).playback?.positionMs ?? -1);
    const series: any[] = [];
    let folded = false; let prev: number | null = null;
    const t0 = Date.now();
    while (Date.now() - t0 < 16000) {
      const s = await api.state();
      const pos = Number(s.playback?.positionMs ?? -1);
      series.push({ atMs: Date.now() - t0, pos, state: s.playback?.playbackState });
      if (prev != null && pos < prev - (bMs - aMs) / 2) folded = true;
      prev = pos;
      await sleep(180);
    }
    const dump = await adb.uiDump();
    const labels = dump.nodes.map((n) => n.text.trim()).filter(Boolean);
    out.atSeek = atSeek; out.folded = folded; out.series = series;
    out.minPos = Math.min(...series.map((s) => s.pos)); out.maxPos = Math.max(...series.map((s) => s.pos));
    out.uiLabels = labels.slice(0, 40);
    console.log("seek 后 pos=" + atSeek + " -> 16s: 折返=" + folded + " min=" + out.minPos + " max=" + out.maxPos + " (B=" + bMs + ")");
    console.log("UI: " + labels.slice(0, 20).join(" | "));
    out.verdict = !folded && out.maxPos > bMs ? "PASS(不折返=按媒体时间)" : "FAIL(仍折返)";
    console.log("判定: " + out.verdict);
  } catch (e: any) {
    out.error = String(e?.message ?? e);
    console.log("!! " + out.error);
  } finally {
    try {
      await api.post("/loop/exit", {});
      const ra = Number(orig.loopStartSamples); const rb = Number(orig.loopEndSamples);
      if (Number.isFinite(ra) && Number.isFinite(rb)) {
        const r = await api.post("/confirm", { songId: hold.id, aSamples: ra, bSamples: rb, sampleRate: sr });
        console.log("恢复原 A/B -> " + r.status);
      }
    } catch (e: any) { console.log("恢复失败: " + e.message); }
    await api.post("/navigate", { route: "about" }).catch(() => null);
  }
  fs.mkdirSync(OUT, { recursive: true });
  fs.writeFileSync(path.join(OUT, "summary.json"), JSON.stringify(out, null, 2), "utf8");
  console.log("产物: " + OUT);
}
main().then(() => process.exit(0)).catch((e) => { console.error("失败: " + (e?.stack ?? e)); process.exit(2); });
