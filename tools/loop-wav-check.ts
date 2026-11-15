// 无缝环回取证(手工版):给定 WAV + A/B/采样率 + 播放起点,验证环回轮次与首轮逐位相同。
// 用法: bun run tools/loop-wav-check.ts <wav> --a 20000 --b 50000 --sr 48000 --startMs 0
import { readFileSync } from "node:fs";

const args = process.argv.slice(2);
if (args.length < 1) { console.error("usage: loop-wav-check.ts <wav> --a 20000 --b 50000 [--sr 48000] [--startMs 0]"); process.exit(2); }
const wavPath = args[0];
const opt = (name: string, def: number) => { const i = args.indexOf("--" + name); return i >= 0 ? Number(args[i + 1]) : def; };
const aMs = opt("a", 20000), bMs = opt("b", 50000), srOpt = opt("sr", 48000), startMs = opt("startMs", 0);

const buf = readFileSync(wavPath);
if (buf.toString("ascii", 0, 4) !== "RIFF") { console.error("not a RIFF file"); process.exit(2); }
let pos = 12, fmt = 0, channels = 0, sr = 0, bits = 0, dataOff = 0, dataLen = 0;
while (pos + 8 <= buf.length) {
  const id = buf.toString("ascii", pos, pos + 4);
  const size = buf.readUInt32LE(pos + 4);
  if (id === "fmt ") { fmt = buf.readUInt16LE(pos + 8); channels = buf.readUInt16LE(pos + 10); sr = buf.readUInt32LE(pos + 12); bits = buf.readUInt16LE(pos + 22); }
  if (id === "data") { dataOff = pos + 8; dataLen = Math.min(size, buf.length - dataOff); break; }
  pos += 8 + size + (size % 2);
}
const bytesPerFrame = (bits / 8) * channels;
const srEff = sr || srOpt;
const totalFrames = Math.floor(dataLen / bytesPerFrame);
const L = Math.round((bMs - aMs) * srEff / 1000);
const prelude = Math.round((bMs - startMs) * srEff / 1000);
console.log("wav=" + wavPath + " fmt=" + fmt + " sr=" + srEff + " ch=" + channels + " bits=" + bits + " frames=" + totalFrames + " (" + (totalFrames / srEff).toFixed(2) + "s)");
console.log("A=" + aMs + "ms B=" + bMs + "ms start=" + startMs + "ms  L=" + L + " frames, prelude=" + prelude + " frames");

function framesEqual(f1: number, f2: number, n: number) {
  const b1 = dataOff + f1 * bytesPerFrame, b2 = dataOff + f2 * bytesPerFrame, len = n * bytesPerFrame;
  if (b1 + len > buf.length || b2 + len > buf.length) return { ok: false, reason: "out of range" };
  const a = buf.subarray(b1, b1 + len), b = buf.subarray(b2, b2 + len);
  let diff = -1, maxDelta = 0;
  for (let i = 0; i < len; i += 2) {
    const va = a.readInt16LE(i), vb = b.readInt16LE(i);
    if (va !== vb) {
      const d = Math.abs(va - vb);
      if (d > maxDelta) maxDelta = d;
      if (diff < 0) diff = i;
    }
  }
  return { ok: diff < 0, firstDiffSample: diff < 0 ? -1 : Math.floor(diff / bytesPerFrame), maxDelta };
}

// 1) 环回轮次:WAV[prelude ..] 应为 ring 内容的重复
const ringStart = prelude - L; // 首轮在 WAV 中的起点(即 A 的位置)
const rounds: number[] = [];
for (let k = 0; k < 8; k++) {
  const f = prelude + k * L;
  if (f + L > totalFrames) break;
  rounds.push(f);
}
console.log("complete replay rounds in capture: " + rounds.length);
let allSame = true;
for (let k = 0; k < rounds.length; k++) {
  const r = framesEqual(ringStart, rounds[k], L);
  console.log("  round " + (k + 1) + " vs source[A..B): " + (r.ok ? "BIT-IDENTICAL" : "DIFF firstDiffFrame=" + r.firstDiffSample + " maxDelta=" + r.maxDelta));
  if (!r.ok) allSame = false;
}

// 2) 接缝静音检查(接缝前后各 10ms 全零检测)
const zeroRun = (fromFrame: number, toFrame: number) => {
  let run = 0, maxRun = 0, at = -1;
  for (let f = Math.max(0, fromFrame); f < Math.min(toFrame, totalFrames); f++) {
    const off = dataOff + f * bytesPerFrame;
    let zero = true;
    for (let c = 0; c < channels; c++) if (buf.readInt16LE(off + c * 2) !== 0) { zero = false; break; }
    if (zero) { run++; if (run > maxRun) { maxRun = run; at = f - run + 1; } } else run = 0;
  }
  return { maxRun, at };
};
const win = Math.round(srEff * 0.01);
for (const f of [prelude, ...rounds.slice(1)]) {
  const z = zeroRun(f - win, f + win);
  console.log("  seam@" + f + " max zeros in +/-10ms: " + z.maxRun + (z.maxRun > 1 ? " (at frame " + z.at + ") CONCERN" : " OK"));
}

// 3) 整体结论
console.log("");
const verdict = allSame && rounds.length > 0;
console.log(verdict ? "VERDICT: PASS (replayed rounds bit-identical to source region)" : "VERDICT: FAIL/INCONCLUSIVE (see above)");
process.exit(verdict ? 0 : 1);
