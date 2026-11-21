import { bandFold, buildWav, createProber, makeSongPcm, nccBest } from "./content-probe";

const SR = 44100;
const DURATION = 60000;
const A = 10000, B = 40000, L = B - A;
const file = makeSongPcm(SR, DURATION, 3);
const AHEAD = 260;
let raw0 = 0, baseWall = Date.now();
const raw = (now = Date.now()) => raw0 + (now - baseWall);
const fold = (u: number) => bandFold(u, A, B);
const playContent = (now: number) => fold(raw(now) + AHEAD);
const startAt = (displayMs: number) => { raw0 = displayMs - (Date.now() - baseWall); };

let tapOpen = false, tapStart = 0, tapWav: Buffer | null = null;
function buildTap() {
  const t0 = tapStart, t1 = Date.now();
  const frames = Math.max(1, Math.round(((t1 - t0) * SR) / 1000));
  const lr = new Int16Array(frames * 2);
  for (let i = 0; i < frames; i++) {
    const wall = t0 + (i * 1000) / SR;
    const u = playContent(wall);
    const idx = Math.max(0, Math.min(file.length - 1, Math.round((u * SR) / 1000)));
    lr[i * 2] = file[idx];
    lr[i * 2 + 1] = Math.round(file[idx] * 0.9);
  }
  tapWav = buildWav(lr, SR, 2, 0);
}

const api: any = {
  async get(p: string) {
    const now = Date.now();
    if (p === "/debug/preview") return { status: 200, json: { snapshot: { engine: "SOURCE", isPlaying: true, liveDisplayMs: Math.round(fold(raw(now))), displayMs: Math.round(fold(raw(now))), aMs: A, bMs: B, sourceKind: "band", outsideLoop: false } }, text: "" };
    if (p === "/state") return { status: 200, json: { playback: { positionMs: Math.round(fold(raw(now))), playing: false, engine: "loop" } }, text: "" };
    return { status: 404, json: null, text: "" };
  },
  async post(p: string, body?: any) {
    if (p === "/debug/tap") {
      if (body?.path) { tapOpen = true; tapStart = Date.now(); return { status: 200, json: { tapping: true }, text: "" }; }
      if (tapOpen) buildTap();
      tapOpen = false;
      return { status: 200, json: { tapping: false }, text: "" };
    }
    if (p === "/debug/decode-range") {
      const a = Math.max(0, Math.round((Number(body.aMs) * SR) / 1000));
      const b = Math.min(file.length, Math.round((Number(body.bMs) * SR) / 1000));
      const frames = Math.max(0, b - a);
      const inter = Buffer.alloc(frames * 4);
      for (let i = 0; i < frames; i++) { inter.writeInt16LE(file[a + i], i * 4); inter.writeInt16LE(Math.round(file[a + i] * 0.9), i * 4 + 2); }
      const bytes = inter.length;
      const tail = Number(body.tailFrames ?? 0) * 4;
      return { status: 200, json: { sampleRate: SR, channels: 2, frames, bytes, elapsedMs: 1, tailPcmBase64: inter.subarray(bytes - Math.min(bytes, tail)).toString("base64") }, text: "" };
    }
    return { status: 404, json: null, text: "" };
  },
};

// 把模型推进到「刚跨过 B」之后 2 秒
startAt(B - 1000);
await new Promise((r) => setTimeout(r, 2400));  // display 从 B-1s 走 2.4s => 已折回 A 后约 1.4s
console.log("now display =", Math.round(fold(raw())), " content =", Math.round(playContent(Date.now())));

const stack = createProber({ adbExe: "adb", device: "m", outDir: "e2e-artifacts/dbg" } as any, api, {
  workDir: "e2e-artifacts/dbg",
  pull: async (n: string) => (n.endsWith(".jsonl") ? null : tapWav),
  log: (m) => console.log(m),
});
const cap = await stack.prober.capture({ target: "preview", samplesMs: 300, dwellMs: 900, workDir: "e2e-artifacts/dbg", label: "dbg" });
const displayRef = Number(cap.displayRef?.displayMs);
console.log("displayRef =", displayRef, " window frames =", cap.window.length, " windowMs =", cap.windowMs);

// 逐块打印 NCC
const chunks = await import("./content-probe").then((m) => m.collectChunks(api, "/x", 10590, 12990));
for (const c of chunks) {
  const w = cap.window;
  const mm = nccBest(w, c.mono, c.sampleRate);
  console.log("chunk", c.startMs + ".." + c.endMs, "peak=" + mm.peak.toFixed(4), "lag=" + mm.lagFrames, " -> pos", (c.startMs + (mm.lagFrames * 1000) / c.sampleRate).toFixed(1));
}
// 直接用正确位置切一段当窗口，看峰值
const a2 = Math.round(((displayRef + AHEAD) * SR) / 1000);
const ideal = new Float32Array(13230);
for (let i = 0; i < ideal.length; i++) ideal[i] = file[Math.min(file.length - 1, a2 + i)] / 32768;
const mv = nccBest(cap.window, ideal, SR);
console.log("窗口 vs 理想位置[同一份文件] peak=" + mv.peak.toFixed(4) + " lag=" + mv.lagFrames);
