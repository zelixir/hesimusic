#!/usr/bin/env bun
/**
 * 临时离线干跑（不提交）：用 MockCtx 驱动场景 20~25，验证编排逻辑（不验证设备行为）。
 * 用法: bun run tools/scenarios/.dryrun-content.ts [loop|passthrough]
 */
import { bandFold, buildWav, createProber, makeSongPcm } from "./content-probe";
import { CONTENT_SCENARIOS, setContentPullForTest } from "./scenarios-content";

const BEHAVIOR: "loop" | "passthrough" = (process.argv[2] as any) === "passthrough" ? "passthrough" : "loop";

const SR = 44100;
const DURATION_MS = 60000;
const A_MS = 10000;
const B_MS = 40000; // L = 30s；B 距尾 20s
const SONG = {
  id: 42,
  title: "Mock Song",
  artist: "mock",
  durationMs: DURATION_MS,
  path: "/mock/song.mp3",
  hasLoop: true,
  loopConfirmed: true,
  loopStartMs: A_MS,
  loopEndMs: B_MS,
  sampleRate: SR,
};

const file = makeSongPcm(SR, DURATION_MS, 3);
const WRITE_AHEAD = 260;

class Model {
  raw0 = A_MS + 1000;
  baseWall = Date.now();
  outside = false;
  mode = "LOOP_ONLY";
  engine = "loop";
  playing = true;
  previewPlaying = true;
  previewAttached = true;
  ended = false;

  raw(now = Date.now()) {
    return this.raw0 + (now - this.baseWall);
  }
  display(now = Date.now()) {
    if (this.outside) return Math.max(0, Math.min(DURATION_MS, this.raw(now)));
    return bandFold(this.raw(now), A_MS, B_MS);
  }
  content(now: number) {
    const ahead = this.raw(now) + WRITE_AHEAD;
    if (BEHAVIOR === "passthrough") return Math.min(ahead, DURATION_MS);
    return bandFold(ahead, A_MS, B_MS);
  }
  seek(targetMs: number) {
    const now = Date.now();
    this.raw0 = targetMs - (now - this.baseWall);
    this.outside = targetMs < A_MS - 1 || targetMs > B_MS;
  }
  stateName() {
    if (BEHAVIOR === "passthrough" && this.raw() + WRITE_AHEAD > DURATION_MS) return "ENDED";
    return "READY";
  }
  positionMs(now = Date.now()) {
    if (this.stateName() === "ENDED") return 0;
    return this.display(now);
  }
}

const model = new Model();
let tapOpen = false;
let tapStart = 0;
let tapWav: Buffer | null = null;
let tapJsonl: string | null = null;

function buildTap() {
  const t0 = tapStart;
  const t1 = Date.now();
  const frames = Math.max(1, Math.round(((t1 - t0) * SR) / 1000));
  const lr = new Int16Array(frames * 2);
  for (let i = 0; i < frames; i++) {
    const wall = t0 + (i * 1000) / SR;
    const u = model.content(wall);
    const idx = Math.max(0, Math.min(file.length - 1, Math.round((u * SR) / 1000)));
    const v = file[idx];
    lr[i * 2] = v;
    lr[i * 2 + 1] = Math.round(v * 0.9);
  }
  tapWav = buildWav(lr, SR, 2, 0);
  tapJsonl = JSON.stringify({ t: 0, seq: 1, ev: "configure", sr: SR, ch: 2, bpf: 4 }) + "\n";
}

const api: any = {
  async songsAll() {
    return [{ ...SONG }];
  },
  async song(id: number) {
    return { ...SONG };
  },
  async state() {
    const r = await api.get("/state");
    return { ...r.json, at: Date.now() };
  },
  async preview() {
    const r = await api.get("/debug/preview");
    return r.status === 200 ? { available: true, snapshot: r.json.snapshot } : { available: false, reason: "x" };
  },
  async get(p: string) {
    const now = Date.now();
    if (p === "/debug/preview") {
      if (!model.previewAttached) return { status: 409, json: { error: "not attached" }, text: "" };
      return {
        status: 200,
        json: {
          snapshot: {
            engine: "SOURCE",
            mode: model.mode,
            isPlaying: model.previewPlaying && model.stateName() !== "ENDED",
            displayMs: Math.round(model.display(now)),
            liveDisplayMs: Math.round(model.display(now)),
            aMs: A_MS,
            bMs: B_MS,
            durationMs: DURATION_MS,
            sourceKind: model.outside ? "native" : "band",
            outsideLoop: model.outside,
            outputFrames: 123456,
            playbackStateName: model.outside ? "READY" : model.stateName(),
          },
        },
        text: "",
      };
    }
    if (p === "/state") {
      return {
        status: 200,
        json: {
          screen: "loop/42",
          playback: {
            songId: SONG.id,
            positionMs: Math.round(model.positionMs(now)),
            playing: model.playing && model.stateName() !== "ENDED",
            playWhenReady: model.playing,
            playbackState: model.stateName().toLowerCase(),
            engine: model.engine,
          },
          loop: { window: { songId: SONG.id, loopStartUs: A_MS * 1000, loopEndUs: B_MS * 1000 } },
        },
        text: "",
      };
    }
    if (p.startsWith("/song/")) return { status: 200, json: { ...SONG }, text: "" };
    if (p.startsWith("/library/songs")) return { status: 200, json: { songs: [{ ...SONG }], total: 1 }, text: "" };
    return { status: 404, json: null, text: "" };
  },
  async post(p: string, body?: any) {
    const now = Date.now();
    if (p === "/debug/tap") {
      if (body?.path) {
        tapOpen = true;
        tapStart = now;
        return { status: 200, json: { tapping: true, path: body.path }, text: "" };
      }
      if (tapOpen) buildTap();
      tapOpen = false;
      return { status: 200, json: { tapping: false }, text: "" };
    }
    if (p === "/debug/decode-range") {
      const aMs = Number(body?.aMs);
      const bMs = Number(body?.bMs);
      const tailFrames = Number(body?.tailFrames ?? 0);
      const a = Math.max(0, Math.round((aMs * SR) / 1000));
      const b = Math.min(file.length, Math.round((bMs * SR) / 1000));
      const frames = Math.max(0, b - a);
      const interleaved = Buffer.alloc(frames * 4);
      for (let i = 0; i < frames; i++) {
        interleaved.writeInt16LE(file[a + i], i * 4);
        interleaved.writeInt16LE(Math.round(file[a + i] * 0.9), i * 4 + 2);
      }
      const bytes = interleaved.length;
      const tailBytes = Math.min(bytes, tailFrames * 4);
      return {
        status: 200,
        json: {
          sampleRate: SR,
          channels: 2,
          frames,
          bytes,
          elapsedMs: 1,
          tailPcmBase64: interleaved.subarray(bytes - tailBytes).toString("base64"),
        },
        text: "",
      };
    }
    if (p === "/debug/preview-seek") {
      const target = Number(body?.positionMs);
      model.seek(target);
      return {
        status: 200,
        json: {
          ok: true,
          displayAfterMs: Math.round(model.display()),
          playerPositionAfterMs: Math.round(model.display()),
          framesDelta: 4410,
          applyMs: 1,
          stages: { playoutMs: 60 },
          outsideLoopAfter: model.outside,
          mode: model.mode,
          isPlayingAfter: true,
        },
        text: "",
      };
    }
    if (p === "/player/play") {
      model.engine = "exo";
      model.playing = true;
      model.outside = false;
      model.seek(0);
      return { status: 200, json: { ok: true }, text: "" };
    }
    if (p === "/loop/enter") {
      model.engine = "loop";
      model.playing = true;
      model.outside = false;
      model.seek(A_MS);
      return { status: 200, json: { ok: true }, text: "" };
    }
    if (p === "/player/seek") {
      model.seek(Number(body?.positionMs));
      return { status: 200, json: { ok: true }, text: "" };
    }
    if (p === "/player/play-pause") {
      model.playing = !model.playing;
      return { status: 200, json: { ok: true }, text: "" };
    }
    if (p === "/loop/exit") return { status: 200, json: { ok: true }, text: "" };
    if (p === "/navigate") return { status: 200, json: { navigated: true }, text: "" };
    return { status: 404, json: null, text: "" };
  },
};

const cfg: any = {
  device: "mock",
  base: "http://127.0.0.1:1",
  forwardLocal: 1,
  forwardRemote: 1,
  outDir: "e2e-artifacts/dryrun-content",
  adbExe: "adb",
  pkg: "com.zjr.hesimusic",
  scenarioTimeoutMs: 200000,
};

const evidence: any = { steps: [] as any[], note() {}, step(action: string, data?: any) { this.steps.push({ action, data }); }, toJSON() { return { steps: this.steps }; } };

const ctx: any = {
  cfg,
  api,
  adb: { async tap() {}, async uiDump() { return { nodes: [], raw: "" }; }, async shell() { return ""; } },
  songs: { primary: SONG, unconfirmed: SONG, short: SONG, long: SONG },
  evidence,
  log: (m: string) => console.log("    " + m),
  sampleRateFor: () => SR,
  async resetForMain() {},
  async ensureLoopPage() {
    model.previewAttached = true;
  },
  async previewReady() {
    return (await api.get("/debug/preview")).json.snapshot;
  },
  async previewSeek(target: number) {
    const r: any = await api.post("/debug/preview-seek", { positionMs: target });
    return {
      ok: true,
      via: "endpoint",
      applyMs: 1,
      advanceMs: 40,
      stages: r.json.stages,
      playoutMs: 60,
      latencyToFramesMs: 1,
      framesDelta: r.json.framesDelta,
      displayAfterMs: r.json.displayAfterMs,
      playerPositionAfterMs: r.json.playerPositionAfterMs,
      isPlayingAfter: true,
      mode: model.mode,
      outsideLoopAfter: r.json.outsideLoopAfter,
      windowA_ms: A_MS,
      windowB_ms: B_MS,
      sourceWindowA_ms: model.outside ? null : A_MS,
      sourceWindowB_ms: model.outside ? null : B_MS,
    };
  },
  async uiTap(label: string) {
    if (label.includes("仅循环段")) {
      model.mode = "LOOP_ONLY";
      model.previewPlaying = true;
      model.previewAttached = true;
    }
    return Date.now();
  },
  uiLabels() {
    return ["完整循环", "仅循环段", "接缝检查"];
  },
  async foregroundForeground() {
    return true;
  },
};

setContentPullForTest(async (name: string) =>
  name.endsWith(".jsonl") ? (tapJsonl ? Buffer.from(tapJsonl) : null) : tapWav,
);

async function main() {
  console.log("== dry-run behavior=" + BEHAVIOR + " ==");
  let failures = 0;
  for (const scenario of CONTENT_SCENARIOS) {
    console.log("");
    console.log("== #" + scenario.id + " " + scenario.name + " ==");
    const t0 = Date.now();
    evidence.steps = [];
    try {
      const res = await scenario.run(ctx);
      const failed = res.checks.filter((c) => !c.pass && !c.pending);
      const pend = res.checks.filter((c) => c.pending);
      console.log(
        "  " + (failed.length === 0 ? "PASS" : "FAIL") + " " + (Date.now() - t0) + "ms " + failed.length + " failed / " +
          pend.length + " pending / " + res.checks.length + " checks — " + res.summary,
      );
      for (const f of failed) console.log("    x " + f.name + ": " + f.detail);
      for (const p of pend) console.log("    ~ " + p.name + ": " + p.detail);
      if (failed.length > 0) failures += 1;
    } catch (e: any) {
      console.log("  THROW " + (Date.now() - t0) + "ms " + (e?.message ?? e));
      if (e?.stack) console.log(String(e.stack).split("\n").slice(1, 4).join("\n"));
      failures += 1;
    }
  }
  console.log("");
  console.log("dry-run: " + (CONTENT_SCENARIOS.length - failures) + "/" + CONTENT_SCENARIOS.length + " 场景无 FAIL");
  return failures === 0 ? 0 : 1;
}

main().then((c) => process.exit(c));
