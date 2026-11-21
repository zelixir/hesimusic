#!/usr/bin/env bun
/** (c-UI) 播放页有没有 A/B 标记 + 堆预算口径取证 */
import * as fs from "node:fs";
import * as path from "node:path";
import { Adb, Api, sleep } from "./harness";
import type { Cfg } from "./harness";

const DEVICE = "b1e84dad";
const STAMP = new Date().toISOString().replace(/[:.]/g, "-").slice(0, 19);
const OUT = path.join("e2e-artifacts", "verify1269ui-" + STAMP);
const adbExe = process.env.ANDROID_HOME ? path.join(process.env.ANDROID_HOME, "platform-tools", "adb.exe") : "adb";
const cfg: Cfg = { device: DEVICE, base: "http://127.0.0.1:18765", forwardLocal: 18765, forwardRemote: 8765, outDir: OUT,
  adbExe, pkg: "com.zjr.hesimusic", scenarioTimeoutMs: 90000 };
const adb = new Adb(adbExe, DEVICE);
const api = new Api(cfg, adb);

async function heapFacts() {
  const out: any = {};
  const prop = await adb.shell("getprop dalvik.vm.heapsize; getprop dalvik.vm.heapgrowthlimit; getprop dalvik.vm.heapmaxfree");
  out.props = prop.split(/\r?\n/).filter(Boolean);
  const pid = await adb.pidof(cfg.pkg);
  out.pid = pid;
  if (pid) {
    const status = await adb.shell("cat /proc/" + pid + "/status | grep -E 'VmPeak|VmHWM|VmRSS|VmSize'");
    out.procStatus = status.split(/\r?\n/).filter(Boolean);
  }
  const mem = await adb.shell("dumpsys meminfo " + cfg.pkg, 30000);
  out.meminfo = mem.split(/\r?\n/).filter((l) => /Java Heap|Dalvik Heap|TOTAL PSS|App Summary|Native Heap|Heap Alloc|Heap Free/.test(l)).slice(0, 12);
  const log = await adb.run(["logcat", "-d", "-t", "2000"], 20000);
  out.ringHeapLines = log.out.split(/\r?\n/).filter((l) => /RingBand heap|budget|ring-too-large|fallback/.test(l)).slice(-8);
  return out;
}

async function main() {
  await adb.forwardEnsure(cfg.forwardLocal, cfg.forwardRemote);
  const facts0 = await heapFacts();
  console.log("== 堆事实 ==");
  console.log("props: " + facts0.props.join(" / "));
  console.log("proc: " + facts0.procStatus.join(" | "));
  for (const l of facts0.meminfo) console.log("meminfo: " + l.trim());
  for (const l of facts0.ringHeapLines) console.log("ring: " + l.slice(0, 200));

  const all = await api.songsAll();
  const sorted = all.filter((s) => /\.(mp3|wav|m4a|aac|flac)$/i.test(s.path) && s.hasLoop && s.sampleRate != null)
    .sort((x, y) => y.durationMs - x.durationMs);
  const hold = sorted[0];
  const orig = await api.song(hold.id);
  const sr = Number(orig.sampleRate ?? 44100) || 44100;
  const dur = Number(orig.durationMs);
  const aMs = 3000;
  const bMs = Math.min(Math.floor((dur - 3000) / 1000) * 1000, 405000);
  console.log("");
  console.log("== (c-UI) #" + hold.id + " dur=" + (dur / 1000).toFixed(1) + "s 窗 A=" + aMs + " B=" + bMs + " = " + (((bMs - aMs) / 1000) * sr * 4 / 1048576).toFixed(1) + "MB ==");
  const out: any = { songId: hold.id, sr, aMs, bMs, facts0 };
  try {
    const conf = await api.post("/confirm", { songId: hold.id, aSamples: Math.round(aMs * sr / 1000), bSamples: Math.round(bMs * sr / 1000) });
    console.log("/confirm -> " + conf.status);
    await api.post("/navigate", { route: "about" });
    await api.post("/loop/exit", {});
    await sleep(500);
    await api.post("/player/play", { songId: hold.id, repeatOne: false });
    for (let i = 0; i < 100; i++) { await sleep(150); const s = await api.state(); if (s.playback?.songId === hold.id && s.playback?.playbackState === "ready") break; }
    await api.post("/loop/enter", { songId: hold.id, play: true, restart: true });
    await sleep(2500);
    const st = await api.state();
    out.loopFacts = st.loop;
    console.log("loop: " + JSON.stringify(st.loop).slice(0, 420));
    // 打开播放页（route=player），停在它上面
    const nav = await api.post("/navigate", { route: "player" });
    console.log("/navigate player -> " + nav.status);
    await sleep(1500);
    const dump1 = await adb.uiDump();
    const labels1 = dump1.nodes.map((n) => n.text.trim()).filter(Boolean);
    out.playerUiBefore = labels1;
    console.log("播放页 UI(跨B前): " + labels1.slice(0, 24).join(" | "));
    const loopish = labels1.filter((t) => /循环|区间|接缝|A\/B|环回/.test(t));
    console.log("  循环相关文本: " + (loopish.join(" | ") || "(无)"));
    await api.post("/player/seek", { positionMs: bMs - 5000 });
    await sleep(500);
    const series: any[] = []; let folded = false; let prev: number | null = null;
    const t0 = Date.now();
    while (Date.now() - t0 < 12000) {
      const s = await api.state();
      const pos = Number(s.playback?.positionMs ?? -1);
      series.push({ atMs: Date.now() - t0, pos });
      if (prev != null && pos < prev - (bMs - aMs) / 2) folded = true;
      prev = pos;
      await sleep(200);
    }
    const dump2 = await adb.uiDump();
    const labels2 = dump2.nodes.map((n) => n.text.trim()).filter(Boolean);
    out.playerUiAfter = labels2;
    out.series = series; out.folded = folded;
    out.minPos = Math.min(...series.map((s) => s.pos)); out.maxPos = Math.max(...series.map((s) => s.pos));
    console.log("播放页 UI(跨B后): " + labels2.slice(0, 24).join(" | "));
    console.log("  循环相关文本: " + (labels2.filter((t) => /循环|区间|接缝|A\/B|环回/.test(t)).join(" | ") || "(无)"));
    console.log("跨B: 折返=" + folded + " min=" + out.minPos + " max=" + out.maxPos + " (B=" + bMs + ")");
    out.verdict = !folded && out.maxPos > bMs ? "PASS(不折返)" : "FAIL(仍折返)";
    console.log("判定: " + out.verdict);
  } catch (e: any) {
    out.error = String(e?.message ?? e);
    console.log("!! " + out.error);
  } finally {
    try {
      await api.post("/loop/exit", {});
      const ra = Number(orig.loopStartSamples); const rb = Number(orig.loopEndSamples);
      if (Number.isFinite(ra) && Number.isFinite(rb)) {
        const r = await api.post("/confirm", { songId: hold.id, aSamples: ra, bSamples: rb });
        console.log("恢复原 A/B -> " + r.status);
      }
    } catch (e: any) { console.log("恢复失败: " + e.message); }
    await api.post("/navigate", { route: "home" }).catch(() => null);
  }
  fs.mkdirSync(OUT, { recursive: true });
  fs.writeFileSync(path.join(OUT, "summary.json"), JSON.stringify(out, null, 2), "utf8");
  console.log("产物: " + OUT);
}
main().then(() => process.exit(0)).catch((e) => { console.error("失败: " + (e?.stack ?? e)); process.exit(2); });
