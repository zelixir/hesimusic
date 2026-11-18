#!/usr/bin/env bun
/**
 * tools/loop-smoothness.ts —— 无缝循环「输出流畅度」量化分析
 *
 * 输入:POST /debug/tap 落盘的 tap 文件(44 字节 WAV 头 + 原始 PCM)+ 同名 sidecar 事件
 *      文件 <tap>.jsonl(run 快照 / seam / configure / flush / degrade / end)。
 *
 * 用法:
 *   bun run tools/loop-smoothness.ts <tap.pcm> [--events <tap.pcm.jsonl>] [选项]
 *   bun run tools/loop-smoothness.ts --selftest [--fixtures <dir>]
 *
 * 输出 5 项指标 + RESULT: PASS/FAIL(exit 0/1;参数或 IO 错误 exit 2):
 *   zeroRuns   连续数字静音段(所有声道全 0),>= --zero-ms(默认 5ms)判 FAIL,列出每段起点
 *   clicks     相邻样本 |Δ| 超阈值的爆音点;阈值 = max(全局 |Δ| p99.9, 2000) 且要求局部离群
 *              (木桶效应会造成 p99.9 必然被 0.1% 样本超过,局部离群门限见文档 §3)
 *   seamAlign  用快照 aMs/bMs 推算环回帧号,核对:帧距==理论 L / 环回点跨周期互相关 / PTS 一致性
 *   rmsWindows 每 100ms 的 RMS(dBFS),标出突降(> --rms-drop-db,默认 6dB)的窗
 *   dupCheck   相邻两个环回周期的同相位段逐样本比较(环形重放必须逐样本相同)
 *
 * 对照模式(区分「引擎拼接错」与「解码错」):
 *   --source <源文件> --source-a-ms <A> --source-b-ms <B>
 *   用 ffmpeg 解出源文件 [A,B) 的参考 PCM,与 tap 录制做相位/接缝互相关对照。
 *
 * 本脚本只读文件,不碰设备、不改 app 代码。fixture 自测见 --selftest。
 */

import { readFileSync, writeFileSync, mkdirSync, existsSync } from "node:fs";
import * as path from "node:path";
import { spawnSync } from "node:child_process";

const VERSION = "loop-smoothness/1";

const SCRIPT_DIR: string = (() => {
  try {
    const d = (import.meta as any).dir;
    if (typeof d === "string" && d.length > 0) return d;
  } catch (_) {}
  try {
    return path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, "$1"));
  } catch (_) {
    return process.cwd();
  }
})();

function out(s: string) {
  process.stdout.write(s + "\n");
}
function errOut(s: string) {
  process.stderr.write(s + "\n");
}
function numOf(v: any): number | null {
  if (typeof v === "number" && Number.isFinite(v)) return v;
  if (typeof v === "string" && v.trim() !== "") {
    const n = Number(v);
    if (Number.isFinite(n)) return n;
  }
  return null;
}
function firstNumOf(obj: any, keys: string[]): number | null {
  for (const k of keys) {
    const v = numOf(obj?.[k]);
    if (v != null) return v;
  }
  return null;
}
function fmt(n: number, d = 3): string {
  return Number.isFinite(n) ? n.toFixed(d) : String(n);
}
function framesToMs(f: number, sr: number): number {
  return sr > 0 ? (f * 1000) / sr : 0;
}
function msToFrames(ms: number, sr: number): number {
  return Math.round((ms * sr) / 1000);
}
function dbOf(rms: number, fullScale = 32768): number {
  if (rms <= 0) return -Infinity;
  return 20 * Math.log10(rms / fullScale);
}

// ============================================================================
// 参数
// ============================================================================

interface Opts {
  pcm: string;
  events: string | null;
  sr: number;            // 0 = 从 WAV/事件推断
  ch: number;            // 0 = 推断
  pcmFormat: string;     // auto | pcm16 | pcm24 | pcm32 | float32
  aMs: number | null;
  bMs: number | null;
  loopFrames: number | null;
  zeroMs: number;
  clickFloor: number;
  clickLocality: number;
  rmsWinMs: number;
  rmsDropDb: number;
  seamWindowMs: number;
  seamSearchMs: number;
  spacingTolFrames: number;
  lagTolFrames: number;
  ptsTolFrames: number;
  dupTol: number;
  allowSeamClicks: boolean;
  source: string | null;
  sourceAMs: number | null;
  sourceBMs: number | null;
  sourceTolFrames: number;
  sourceStrict: boolean;
  json: string | null;
  quiet: boolean;
  fixtures: string | null;
  maxList: number;
  help: boolean;
  selftest: boolean;
  /** 基线录音:同时分析普通播放,只报告"循环比基线多出来的异常"。 */
  baseline: string | null;
  /** 相对基线的容忍增量(爆音点数)。 */
  baselineClickTol: number;
  /** 相对基线的容忍增量(掉档窗数)。 */
  baselineHoleTol: number;
}

function defaultOpts(): Opts {
  return {
    pcm: "",
    events: null,
    sr: 0,
    ch: 0,
    pcmFormat: "auto",
    aMs: null,
    bMs: null,
    loopFrames: null,
    zeroMs: 5,
    clickFloor: 2000,
    clickLocality: 3,
    rmsWinMs: 100,
    rmsDropDb: 6,
    seamWindowMs: 10,
    seamSearchMs: 5,
    spacingTolFrames: 2,
    lagTolFrames: 1,
    ptsTolFrames: 2,
    dupTol: 0,
    allowSeamClicks: false,
    source: null,
    sourceAMs: null,
    sourceBMs: null,
    sourceTolFrames: 2048,
    sourceStrict: false,
    json: null,
    quiet: false,
    fixtures: null,
    maxList: 20,
    help: false,
    selftest: false,
    baseline: null,
    baselineClickTol: 3,
    baselineHoleTol: 1,
  };
}

function usage(): string {
  return [
    "usage: bun run tools/loop-smoothness.ts <tap.pcm|tap.wav> [--events <tap.jsonl>] [options]",
    "",
    "  --events <path>        tap 的 sidecar 事件文件(默认自动探测 <pcm>.jsonl / <同名>.jsonl)",
    "  --baseline <path>      同时分析一段普通播放录音,只报告循环比基线多出来的异常",
    "  --baseline-click-tol <n> 爆音点允许比基线多的数量(默认 3)",
    "  --baseline-hole-tol <n>  掉档窗允许比基线多的数量(默认 1)",
    "  --a-ms <ms> --b-ms <ms> 无事件文件时手工给环区间(或 --loop-frames <n>)",
    "  --sr <hz> --ch <n> --pcm-format <auto|pcm16|pcm24|pcm32|float32>  裸 PCM 时必需",
    "  --zero-ms <ms>         静音段判 FAIL 的门限(默认 5)",
    "  --click-floor <n>      爆音阈值下限(int16 单位,默认 2000)",
    "  --click-locality <k>   爆音需为局部离群:峰值 >= k × 局部 P95(默认 3;0 = 关闭该门限)",
    "  --rms-win-ms <ms>      RMS 窗宽(默认 100)",
    "  --rms-drop-db <db>     RMS 突降门限(默认 6)",
    "  --seam-window-ms <ms>  接缝互相关窗口(默认 10 = 前后各 10ms,合计 20ms)",
    "  --seam-search-ms <ms>  接缝互相关最大搜索偏移(默认 5)",
    "  --spacing-tol <frames> 环回帧距与理论 L 的允许偏差下限(默认 2;快照只有 ms 时自动放大到 ~1ms)",
    "  --lag-tol <frames>     相邻周期同相位窗口的互相关峰值偏移容忍(默认 1;环形重放应逐样本相同)",
    "  --pts-tol <frames>     接缝 PTS 换算帧号与实测帧号的允许偏差(默认 2)",
    "  --dup-tol <n>          周期逐样本比较容差(默认 0;环形重放应逐位相同)",
    "  --allow-seam-clicks    接缝处爆音不计 FAIL(仅报告)",
    "  --source <file> --source-a-ms <A> --source-b-ms <B>   源文件对照(需要 ffmpeg)",
    "  --source-tol <frames>  源对照相位偏移容忍(默认 2048)",
    "  --source-strict        源对照偏移超容忍时判 FAIL(默认只报告)",
    "  --json <out>           结果写 JSON",
    "  --max-list <n>         每项最多列出的明细条数(默认 20)",
    "  --fixtures <dir>       --selftest 的 fixture 落盘目录",
    "  --quiet                只打结论行",
    "  --selftest             跑 fixture 自测(无需设备/ffmpeg)",
    "  --help                 本帮助",
  ].join("\n");
}

function parseArgs(argv: string[]): Opts {
  const o = defaultOpts();
  const positional: string[] = [];
  const need = (i: number, k: string): string => {
    if (i + 1 >= argv.length) throw new Error("缺少参数值: " + k);
    return argv[i + 1];
  };
  for (let i = 0; i < argv.length; i++) {
    let a = argv[i];
    let inlineVal: string | null = null;
    const eq = a.indexOf("=");
    if (a.startsWith("--") && eq > 0) {
      inlineVal = a.slice(eq + 1);
      a = a.slice(0, eq);
    }
    const take = (): string => {
      if (inlineVal != null) return inlineVal;
      const v = need(i, a);
      i += 1;
      return v;
    };
    switch (a) {
      case "--events": o.events = take(); break;
      case "--baseline": o.baseline = take(); break;
      case "--baseline-click-tol": o.baselineClickTol = Number(take()); break;
      case "--baseline-hole-tol": o.baselineHoleTol = Number(take()); break;
      case "--sr": o.sr = Number(take()); break;
      case "--ch": o.ch = Number(take()); break;
      case "--pcm-format": o.pcmFormat = take(); break;
      case "--a-ms": o.aMs = Number(take()); break;
      case "--b-ms": o.bMs = Number(take()); break;
      case "--loop-frames": o.loopFrames = Number(take()); break;
      case "--zero-ms": o.zeroMs = Number(take()); break;
      case "--click-floor": o.clickFloor = Number(take()); break;
      case "--click-locality": o.clickLocality = Number(take()); break;
      case "--rms-win-ms": o.rmsWinMs = Number(take()); break;
      case "--rms-drop-db": o.rmsDropDb = Number(take()); break;
      case "--seam-window-ms": o.seamWindowMs = Number(take()); break;
      case "--seam-search-ms": o.seamSearchMs = Number(take()); break;
      case "--spacing-tol": o.spacingTolFrames = Number(take()); break;
      case "--lag-tol": o.lagTolFrames = Number(take()); break;
      case "--pts-tol": o.ptsTolFrames = Number(take()); break;
      case "--dup-tol": o.dupTol = Number(take()); break;
      case "--allow-seam-clicks": o.allowSeamClicks = true; break;
      case "--source": o.source = take(); break;
      case "--source-a-ms": o.sourceAMs = Number(take()); break;
      case "--source-b-ms": o.sourceBMs = Number(take()); break;
      case "--source-tol": o.sourceTolFrames = Number(take()); break;
      case "--source-strict": o.sourceStrict = true; break;
      case "--json": o.json = take(); break;
      case "--max-list": o.maxList = Number(take()); break;
      case "--fixtures": o.fixtures = take(); break;
      case "--quiet": o.quiet = true; break;
      case "--selftest": o.selftest = true; break;
      case "--help": case "-h": o.help = true; break;
      default:
        if (a.startsWith("--")) throw new Error("未知参数: " + a);
        positional.push(a);
        break;
    }
  }
  if (positional.length > 0) o.pcm = positional[0];
  if (positional.length > 1 && !o.events) o.events = positional[1];
  return o;
}

// ============================================================================
// PCM 解析(WAV 头 + 裸 PCM 兜底)
// ============================================================================

interface PcmMeta {
  hasHeader: boolean;
  sr: number;
  ch: number;
  bpf: number;
  bits: number;
  codec: string;
  dataOffset: number;
  dataBytes: number;
  warnings: string[];
}

const CODEC_BY_BITS: Record<string, string> = { "8": "pcm8", "16": "pcm16", "24": "pcm24", "32": "pcm32" };

function parsePcm(buf: Buffer, o: Opts, cfg: any): PcmMeta {
  const warnings: string[] = [];
  const head = buf.length >= 12 ? buf.toString("latin1", 0, 12) : "";
  if (head.length >= 12 && head.slice(0, 4) === "RIFF" && head.slice(8, 12) === "WAVE") {
    let pos = 12;
    let fmt: any = null;
    let dataOffset = -1;
    let declared = -1;
    while (pos + 8 <= buf.length) {
      const id = buf.toString("latin1", pos, pos + 4);
      const size = buf.readUInt32LE(pos + 4);
      const body = pos + 8;
      if (id === "fmt " && body + 16 <= buf.length) {
        let tag = buf.readUInt16LE(body);
        const ch = buf.readUInt16LE(body + 2);
        const sr = buf.readUInt32LE(body + 4);
        const bits = buf.readUInt16LE(body + 14);
        if (tag === 0xfffe && size >= 40 && body + 26 <= buf.length) {
          tag = buf.readUInt16LE(body + 24);
        }
        fmt = { tag, ch, sr, bits };
      } else if (id === "data") {
        dataOffset = body;
        declared = size;
        break;
      }
      const advance = 8 + size + (size % 2 === 1 ? 1 : 0);
      if (advance <= 8) break;
      pos += advance;
    }
    if (!fmt) throw new Error("WAV 缺少 fmt chunk: " + o.pcm);
    if (dataOffset < 0) throw new Error("WAV 缺少 data chunk: " + o.pcm);
    const avail = Math.max(0, buf.length - dataOffset);
    let dataBytes = avail;
    if (declared === 0 || declared === 0xffffffff) {
      warnings.push("data 长度字段=" + declared + "(tap 未正常关闭时常见),按文件剩余 " + avail + " 字节解析");
    } else if (declared > avail) {
      warnings.push("data 声明 " + declared + " 字节,实际只剩 " + avail + " 字节(截断)");
    } else {
      dataBytes = declared;
    }
    let codec: string;
    if (fmt.tag === 3) {
      if (fmt.bits === 64) codec = "float64";
      else codec = "float32";
    } else {
      codec = CODEC_BY_BITS[String(fmt.bits)] || "pcm16";
      if (!CODEC_BY_BITS[String(fmt.bits)]) warnings.push("异常位深 " + fmt.bits + ",按 pcm16 解析");
    }
    const bytesPerSample = Math.max(1, Math.ceil(fmt.bits / 8));
    const bpf = Math.max(1, bytesPerSample * Math.max(1, fmt.ch));
    if (fmt.sr <= 0) throw new Error("WAV 采样率非法: " + fmt.sr);
    return {
      hasHeader: true,
      sr: fmt.sr,
      ch: Math.max(1, fmt.ch),
      bpf,
      bits: fmt.bits,
      codec,
      dataOffset,
      dataBytes,
      warnings,
    };
  }
  // 裸 PCM:靠 --sr/--ch(或事件里的 configure)推断
  const sr = o.sr > 0 ? o.sr : (numOf(cfg?.sr) ?? 48000);
  const ch = o.ch > 0 ? o.ch : (numOf(cfg?.ch) ?? 2);
  const bpf = numOf(cfg?.bpf) ?? (numOf(o as any)) ?? ch * 2;
  let codec = o.pcmFormat;
  if (codec === "auto") {
    if (bpf === ch * 2) codec = "pcm16";
    else if (bpf === ch * 4) {
      codec = "float32";
      warnings.push("裸 PCM 且每帧 " + bpf + " 字节:默认按 32bit float 解析(如为 pcm32 请 --pcm-format pcm32)");
    } else codec = "pcm16";
  }
  const bits = codec === "float32" || codec === "pcm32" ? 32 : codec === "pcm24" ? 24 : codec === "pcm8" ? 8 : 16;
  warnings.push("输入不是 WAV(无 RIFF 头),按裸 " + codec + " 解析");
  return { hasHeader: false, sr, ch: Math.max(1, ch), bpf: Math.max(1, bpf), bits, codec, dataOffset: 0, dataBytes: buf.length, warnings };
}

interface DecodedPcm {
  inter: Float32Array;
  mono: Float32Array;
  frames: number;
}

/** 解码成「int16 单位」的浮点数组(阈值口径统一:±32768 满幅)。 */
function decodePcm(buf: Buffer, meta: PcmMeta): DecodedPcm {
  const { bpf, ch, codec } = meta;
  const frames = Math.floor(meta.dataBytes / bpf);
  const bps = Math.max(1, Math.floor(bpf / ch));
  const inter = new Float32Array(frames * ch);
  const off0 = meta.dataOffset;
  for (let f = 0; f < frames; f++) {
    const base = off0 + f * bpf;
    for (let c = 0; c < ch; c++) {
      const pos = base + c * bps;
      let v = 0;
      switch (codec) {
        case "pcm8": v = (buf[pos] - 128) * 256; break;
        case "pcm16": v = buf.readInt16LE(pos); break;
        case "pcm24": v = buf.readIntLE(pos, 3) >> 8; break;
        case "pcm32": v = buf.readInt32LE(pos) >> 16; break;
        case "float32": v = buf.readFloatLE(pos) * 32768; break;
        case "float64": v = buf.readDoubleLE(pos) * 32768; break;
        default: v = buf.readInt16LE(pos); break;
      }
      inter[f * ch + c] = v;
    }
  }
  const mono = new Float32Array(frames);
  if (ch === 1) {
    mono.set(inter);
  } else {
    for (let f = 0; f < frames; f++) {
      let s = 0;
      for (let c = 0; c < ch; c++) s += inter[f * ch + c];
      mono[f] = s / ch;
    }
  }
  return { inter, mono, frames };
}

// ============================================================================
// 事件 sidecar 解析
// ============================================================================

interface TapEvents {
  path: string | null;
  events: any[];
  configure: any | null;
  runs: any[];
  seams: any[];
  seamEvents: number;
  flushCount: number;
  degradeCount: number;
  end: any | null;
  parseErrors: number;
  truncatedLastLine: boolean;
}

function parseEvents(filePath: string | null): TapEvents {
  const res: TapEvents = {
    path: filePath,
    events: [],
    configure: null,
    runs: [],
    seams: [],
    seamEvents: 0,
    flushCount: 0,
    degradeCount: 0,
    end: null,
    parseErrors: 0,
    truncatedLastLine: false,
  };
  if (!filePath) return res;
  if (!existsSync(filePath)) throw new Error("事件文件不存在: " + filePath + "(tap 的 sidecar 是 <tap 文件名>.jsonl)");
  const raw = readFileSync(filePath, "utf8");
  const text = raw.charCodeAt(0) === 0xfeff ? raw.slice(1) : raw;
  const endsWithNewline = /\r?\n$/.test(text);
  const lines = text.split(/\r?\n/);
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i].trim();
    if (!line) continue;
    let ev: any;
    try {
      ev = JSON.parse(line);
    } catch (_) {
      if (i === lines.length - 1 && !endsWithNewline) res.truncatedLastLine = true;
      else res.parseErrors += 1;
      continue;
    }
    if (!ev || typeof ev !== "object" || Array.isArray(ev)) continue;
    res.events.push(ev);
  }
  for (const ev of res.events) {
    if (ev.ev === "configure" && !res.configure) res.configure = ev;
    else if (ev.ev === "run") res.runs.push(ev);
    else if (ev.ev === "seam") {
      res.seamEvents += 1;
      if (numOf(ev.outFrames) != null) res.seams.push(ev);
    } else if (ev.ev === "flush") res.flushCount += 1;
    else if (ev.ev === "degrade") res.degradeCount += 1;
    else if (ev.ev === "end" && !res.end) res.end = ev;
  }
  res.seams.sort((a, b) => (numOf(a.outFrames) as number) - (numOf(b.outFrames) as number));
  return res;
}

function autodetectEvents(pcmPath: string): string | null {
  const cands = [
    pcmPath + ".jsonl",
    pcmPath.replace(/\.(pcm|wav)$/i, ".jsonl"),
    pcmPath.replace(/\.(pcm|wav)$/i, ".pcm.jsonl"),
    pcmPath.replace(/\.(pcm|wav)$/i, ".events.jsonl"),
  ];
  for (const c of cands) {
    if (c !== pcmPath && existsSync(c)) return c;
  }
  return null;
}

// ============================================================================
// 互相关小工具
// ============================================================================

function hpWindow(src: Float32Array, start: number, len: number): Float32Array {
  const out = new Float32Array(len);
  for (let i = 0; i < len; i++) {
    const idx = start + i;
    const prev = idx > 0 ? src[idx - 1] : 0;
    out[i] = src[idx] - prev;
  }
  return out;
}

function rmsPair(a: Float32Array, b: Float32Array): number {
  let s = 0;
  for (let i = 0; i < a.length; i++) s += a[i] * a[i] + b[i] * b[i];
  return Math.sqrt(s / Math.max(1, 2 * a.length));
}

/** 双向 winsorize:把超过 k×RMS 的样本截断。单个尖峰(爆音)不应污染相位/相关判定。 */
function winsorizePair(a: Float32Array, b: Float32Array, k: number): void {
  const lim = k * rmsPair(a, b);
  if (!(lim > 0)) return;
  for (let i = 0; i < a.length; i++) {
    if (a[i] > lim) a[i] = lim;
    else if (a[i] < -lim) a[i] = -lim;
    if (b[i] > lim) b[i] = lim;
    else if (b[i] < -lim) b[i] = -lim;
  }
}

function bestLag(a: Float32Array, b: Float32Array, maxLag: number): { lag: number; ncc: number } {
  const n = a.length;
  let best = { lag: 0, ncc: -2 };
  for (let lag = -maxLag; lag <= maxLag; lag++) {
    const i0 = Math.max(0, -lag);
    const i1 = Math.min(n, n - lag);
    let dot = 0;
    let ea = 0;
    let eb = 0;
    for (let i = i0; i < i1; i++) {
      const x = a[i];
      const y = b[i + lag];
      dot += x * y;
      ea += x * x;
      eb += y * y;
    }
    const den = Math.sqrt(ea * eb);
    const ncc = den > 0 ? dot / den : 0;
    if (ncc > best.ncc) best = { lag, ncc };
  }
  return best;
}

function nccAt(a: Float32Array, b: Float32Array, lag: number): number {
  const n = a.length;
  const i0 = Math.max(0, -lag);
  const i1 = Math.min(n, n - lag);
  let dot = 0;
  let ea = 0;
  let eb = 0;
  for (let i = i0; i < i1; i++) {
    const x = a[i];
    const y = b[i + lag];
    dot += x * y;
    ea += x * x;
    eb += y * y;
  }
  const den = Math.sqrt(ea * eb);
  return den > 0 ? dot / den : 0;
}

// ============================================================================
// 指标 1:zeroRuns
// ============================================================================

interface ZeroRun {
  startFrame: number;
  frames: number;
  ms: number;
  atStart: boolean;
  atEnd: boolean;
  nearestSeam: number | null;
}

function scanZeroRuns(dec: DecodedPcm, ch: number, minFrames: number, seamFrames: number[]): { runs: ZeroRun[]; zeroFrames: number; longest: ZeroRun | null } {
  const { inter, frames } = dec;
  const runs: ZeroRun[] = [];
  let runStart = -1;
  let zeroFrames = 0;
  const flush = (endFrame: number) => {
    if (runStart < 0) return;
    const len = endFrame - runStart;
    runs.push({
      startFrame: runStart,
      frames: len,
      ms: 0,
      atStart: runStart === 0,
      atEnd: endFrame >= frames,
      nearestSeam: nearestOf(runStart, seamFrames),
    });
    runStart = -1;
  };
  for (let f = 0; f < frames; f++) {
    let isZero = true;
    const base = f * ch;
    for (let c = 0; c < ch; c++) {
      if (inter[base + c] !== 0) {
        isZero = false;
        break;
      }
    }
    if (isZero) {
      if (runStart < 0) runStart = f;
      zeroFrames += 1;
    } else {
      flush(f);
    }
  }
  flush(frames);
  let longest: ZeroRun | null = null;
  for (const r of runs) if (!longest || r.frames > longest.frames) longest = r;
  return { runs, zeroFrames, longest };
}

function nearestOf(frame: number, marks: number[]): number | null {
  let best: number | null = null;
  let bestD = Infinity;
  for (const m of marks) {
    const d = Math.abs(m - frame);
    if (d < bestD) {
      bestD = d;
      best = m;
    }
  }
  return best;
}

// ============================================================================
// 指标 2:clicks
// ============================================================================

interface ClickCluster {
  frame: number;
  ms: number;
  peakDelta: number;
  peakChannel: number;
  widthFrames: number;
  nearestSeam: number | null;
  seamDistance: number | null;
}

function scanClicks(
  dec: DecodedPcm,
  ch: number,
  sr: number,
  thr: number,
  locality: number,
  localityMs: number,
  seamFrames: number[],
): { clusters: ClickCluster[]; exceedCount: number } {
  const { inter, frames } = dec;
  if (frames < 3) return { clusters: [], exceedCount: 0 };
  // 逐帧 |Δ| 的最大值(声道内)
  const dmax = new Float32Array(frames);
  for (let f = 1; f < frames; f++) {
    let mx = 0;
    const base = f * ch;
    const pbase = base - ch;
    for (let c = 0; c < ch; c++) {
      const d = Math.abs(inter[base + c] - inter[pbase + c]);
      if (d > mx) mx = d;
    }
    dmax[f] = mx;
  }
  const localHalf = Math.max(1, Math.round((localityMs * sr) / 1000));
  const winSorted: number[] = [];
  let exceedCount = 0;
  const flagged: boolean[] = new Array(frames).fill(false);
  for (let f = 2; f < frames; f++) {
    if (dmax[f] < thr) continue;
    exceedCount += 1;
    if (locality <= 0) {
      flagged[f] = true;
      continue;
    }
    const a = Math.max(1, f - localHalf);
    const b = Math.min(frames - 1, f + localHalf);
    winSorted.length = 0;
    for (let k = a; k <= b; k++) winSorted.push(dmax[k]);
    winSorted.sort((x, y) => x - y);
    const p95 = winSorted[Math.min(winSorted.length - 1, Math.floor(winSorted.length * 0.95))] || 0;
    if (dmax[f] >= thr && (p95 <= 0 ? dmax[f] >= thr : dmax[f] >= locality * p95)) flagged[f] = true;
  }
  // 聚类:相邻超阈点间隔 <= 2ms 合为一个 click
  const gap = Math.max(1, Math.round((2 * sr) / 1000));
  const clusters: ClickCluster[] = [];
  let i = 1;
  while (i < frames) {
    if (!flagged[i]) {
      i += 1;
      continue;
    }
    let j = i;
    let peak = 0;
    let peakFrame = i;
    let peakCh = 0;
    while (j < frames && (flagged[j] || (j + 1 < frames && flagged[j + 1] && j + 1 - peakFrame <= gap))) {
      if (flagged[j] && dmax[j] > peak) {
        peak = dmax[j];
        peakFrame = j;
        // 找出峰值所在声道
        let mx = 0;
        let mc = 0;
        for (let c = 0; c < ch; c++) {
          const d = Math.abs(inter[j * ch + c] - inter[(j - 1) * ch + c]);
          if (d > mx) {
            mx = d;
            mc = c;
          }
        }
        peakCh = mc;
      }
      j += 1;
    }
    const seam = nearestOf(peakFrame, seamFrames);
    clusters.push({
      frame: peakFrame,
      ms: framesToMs(peakFrame, sr),
      peakDelta: peak,
      peakChannel: peakCh,
      widthFrames: j - i,
      nearestSeam: seam,
      seamDistance: seam == null ? null : peakFrame - seam,
    });
    i = j;
  }
  return { clusters, exceedCount };
}

function percentileFromDeltas(dmax: Float32Array): number {
  const n = dmax.length;
  if (n < 2) return 0;
  const copy = new Float32Array(n - 1);
  copy.set(dmax.subarray(1));
  copy.sort();
  const idx = Math.min(copy.length - 1, Math.max(0, Math.floor(copy.length * 0.999)));
  return copy[idx];
}

// ============================================================================
// 指标 3:seamAlign
// ============================================================================

interface SeamRow {
  index: number;
  frame: number;
  ms: number;
  spacing: number | null;
  dL: number | null;
  periodLag: number | null;
  periodNcc: number | null;
  ptsDelta: number | null;
  driftFromSnapshot: number | null;
}

interface SeamAnalysis {
  anchors: number[];
  rows: SeamRow[];
  medianL: number | null;
  spacingMin: number | null;
  spacingMax: number | null;
  maxAbsDL: number | null;
  minPeriodNcc: number | null;
  maxAbsPeriodLag: number | null;
  maxAbsPtsDelta: number | null;
  notes: string[];
}

function analyzeSeams(
  dec: DecodedPcm,
  o: Opts,
  sr: number,
  Ltheory: number | null,
  seams: any[],
): SeamAnalysis {
  const { mono, frames } = dec;
  const notes: string[] = [];
  const anchorEvents = seams
    .map((e) => ({ frame: Math.round(numOf(e.outFrames) as number), ptsUs: numOf(e.ptsUs), iter: numOf(e.iter) }))
    .filter((x) => Number.isFinite(x.frame) && x.frame > 0 && x.frame < frames);
  const anchors = anchorEvents.map((x) => x.frame);
  const rows: SeamRow[] = [];
  const half = Math.max(2, Math.round((o.seamWindowMs * sr) / 1000));
  const maxLag = Math.max(1, Math.round((o.seamSearchMs * sr) / 1000));
  const spacings: number[] = [];
  // 第一遍:位置 / 帧距(与理论 L 的差)/ PTS
  for (let i = 0; i < anchorEvents.length; i++) {
    const e = anchorEvents[i];
    let spacing: number | null = null;
    if (i > 0) {
      spacing = e.frame - anchorEvents[i - 1].frame;
      spacings.push(spacing);
    }
    rows.push({
      index: i + 1,
      frame: e.frame,
      ms: framesToMs(e.frame, sr),
      spacing,
      dL: spacing != null && Ltheory != null ? spacing - Ltheory : null,
      periodLag: null,
      periodNcc: null,
      ptsDelta: null,
      driftFromSnapshot: null,
    });
  }
  // 第二遍:跨周期相位。与「相邻接缝的同相位窗口」比较,而不是与理论 L 处的窗口比较,
  // 这样 lag 只反映相位错位、dL 只反映长度错位,两者不互相污染。
  for (let i = 0; i < anchorEvents.length; i++) {
    const center = anchorEvents[i].frame;
    if (center - half < 0 || center + half > frames) continue;
    const prev = i > 0 ? anchorEvents[i - 1].frame : null;
    const next = i + 1 < anchorEvents.length ? anchorEvents[i + 1].frame : null;
    let ref: number | null = null;
    if (prev != null && prev - half >= 0) ref = prev;
    else if (next != null && next + half <= frames) ref = next;
    if (ref == null) continue;
    const w = hpWindow(mono, center - half, 2 * half);
    const t = hpWindow(mono, ref - half, 2 * half);
    winsorizePair(w, t, 4);
    const r = bestLag(w, t, maxLag);
    rows[i].periodLag = r.lag;
    rows[i].periodNcc = r.ncc;
  }
  // PTS 一致性:同一重放域内 PTS 增量应等于帧增量
  const first = anchorEvents.find((x) => x.ptsUs != null) ?? null;
  if (first != null) {
    for (let i = 0; i < anchorEvents.length; i++) {
      const e = anchorEvents[i];
      if (e.ptsUs == null || first.ptsUs == null) continue;
      const predictedFrames = ((e.ptsUs - first.ptsUs) * sr) / 1e6;
      const actualFrames = e.frame - first.frame;
      rows[i].ptsDelta = actualFrames - predictedFrames;
    }
  }
  if (Ltheory != null && anchorEvents.length > 0) {
    const f0 = anchorEvents[0].frame;
    for (let i = 0; i < anchorEvents.length; i++) {
      rows[i].driftFromSnapshot = anchorEvents[i].frame - (f0 + i * Ltheory);
    }
  }
  const spacingMin = spacings.length ? Math.min(...spacings) : null;
  const spacingMax = spacings.length ? Math.max(...spacings) : null;
  const medianL = spacings.length ? [...spacings].sort((a, b) => a - b)[Math.floor(spacings.length / 2)] : null;
  let maxAbsDL: number | null = null;
  for (const r of rows) if (r.dL != null) maxAbsDL = Math.max(maxAbsDL ?? 0, Math.abs(r.dL));
  let minPeriodNcc: number | null = null;
  for (const r of rows) if (r.periodNcc != null) minPeriodNcc = minPeriodNcc == null ? r.periodNcc : Math.min(minPeriodNcc, r.periodNcc);
  let maxAbsPeriodLag: number | null = null;
  for (const r of rows) if (r.periodLag != null) maxAbsPeriodLag = Math.max(maxAbsPeriodLag ?? 0, Math.abs(r.periodLag));
  let maxAbsPtsDelta: number | null = null;
  for (const r of rows) if (r.ptsDelta != null) maxAbsPtsDelta = Math.max(maxAbsPtsDelta ?? 0, Math.abs(r.ptsDelta));
  return { anchors, rows, medianL, spacingMin, spacingMax, maxAbsDL, minPeriodNcc, maxAbsPeriodLag, maxAbsPtsDelta, notes };
}

// ============================================================================
// 指标 4:rmsWindows
// ============================================================================

interface RmsWindow {
  index: number;
  startFrame: number;
  endFrame: number;
  ms: number;
  rms: number;
  db: number;
  dropDb: number | null; // 相对上一窗的下降
  hole: boolean;         // 快速掉块(随后恢复)
}

function analyzeRms(dec: DecodedPcm, sr: number, winMs: number, dropDb: number): { windows: RmsWindow[]; holes: RmsWindow[]; minDb: number } {
  const { mono, frames } = dec;
  const win = Math.max(1, Math.round((winMs * sr) / 1000));
  const list: RmsWindow[] = [];
  for (let start = 0; start < frames; start += win) {
    const end = Math.min(frames, start + win);
    let sum = 0;
    for (let i = start; i < end; i++) sum += mono[i] * mono[i];
    const count = Math.max(1, end - start);
    const rms = Math.sqrt(sum / count);
    list.push({ index: list.length, startFrame: start, endFrame: end, ms: framesToMs(start, sr), rms, db: dbOf(rms), dropDb: null, hole: false });
  }
  for (let i = 1; i < list.length; i++) {
    const prev = list[i - 1].db;
    const cur = list[i].db;
    if (Number.isFinite(prev) && prev > -55 && Number.isFinite(cur)) {
      const d = prev - cur;
      if (d > dropDb) {
        list[i].dropDb = d;
        // 判断「掉块后恢复」:随后 5 个窗内回到前窗 -dropDb 以内 => hole
        let recovered = false;
        for (let k = i + 1; k < Math.min(list.length, i + 6); k++) {
          if (Number.isFinite(list[k].db) && list[k].db >= prev - dropDb) {
            recovered = true;
            break;
          }
        }
        list[i].hole = recovered;
      }
    }
  }
  const holes = list.filter((w) => w.hole);
  const finite = list.filter((w) => Number.isFinite(w.db));
  const minDb = finite.length ? Math.min(...finite.map((w) => w.db)) : -Infinity;
  return { windows: list, holes, minDb };
}

// ============================================================================
// 指标 5:dupCheck
// ============================================================================

interface DupPair {
  fromSeam: number;
  toSeam: number;
  startA: number;
  startB: number;
  frames: number;
  identical: boolean;
  diffSamples: number;
  firstDiffFrame: number | null;
  firstDiffChannel: number | null;
  maxDelta: number;
  lengthMismatch: number | null;
}

function analyzeDup(dec: DecodedPcm, ch: number, anchors: number[], tol: number, frames: number): { pairs: DupPair[] } {
  const { inter } = dec;
  const pairs: DupPair[] = [];
  for (let i = 0; i + 2 < anchors.length; i++) {
    const a0 = anchors[i];
    const a1 = anchors[i + 1];
    const a2 = anchors[i + 2];
    const lenA = a1 - a0;
    const lenB = a2 - a1;
    const n = Math.min(lenA, lenB);
    let diffSamples = 0;
    let maxDelta = 0;
    let firstFrame: number | null = null;
    let firstChannel: number | null = null;
    for (let f = 0; f < n; f++) {
      for (let c = 0; c < ch; c++) {
        const va = inter[(a0 + f) * ch + c];
        const vb = inter[(a1 + f) * ch + c];
        const d = Math.abs(va - vb);
        if (d > tol) {
          diffSamples += 1;
          if (d > maxDelta) maxDelta = d;
          if (firstFrame == null) {
            firstFrame = f;
            firstChannel = c;
          }
        }
      }
    }
    pairs.push({
      fromSeam: i + 1,
      toSeam: i + 2,
      startA: a0,
      startB: a1,
      frames: n,
      identical: diffSamples === 0 && lenA === lenB,
      diffSamples,
      firstDiffFrame: firstFrame,
      firstDiffChannel: firstChannel,
      maxDelta,
      lengthMismatch: lenA === lenB ? null : lenB - lenA,
    });
  }
  return { pairs };
}

// ============================================================================
// 源文件对照(可选)
// ============================================================================

interface SourceCompare {
  available: boolean;
  reason: string | null;
  phaseFrames: number | null;
  phaseNcc: number | null;
  ringFrames: number | null;
  junctionLag: number | null;
  junctionNcc: number | null;
  perSeam: Array<{ seam: number; lag: number | null; ncc: number | null }>;
}

function loadSourceRing(srcPath: string, aMs: number, bMs: number, sr: number, ch: number): Float32Array {
  const durSec = (bMs - aMs) / 1000;
  const args = [
    "-hide_banner", "-loglevel", "error",
    "-i", srcPath,
    "-ss", (aMs / 1000).toFixed(6),
    "-t", durSec.toFixed(6),
    "-vn", "-ac", String(ch), "-ar", String(sr),
    "-f", "f32le", "-",
  ];
  const r = spawnSync("ffmpeg", args, { maxBuffer: 1024 * 1024 * 512 });
  if (r.error) throw new Error("ffmpeg 启动失败: " + (r.error as any).message);
  if (r.status !== 0) throw new Error("ffmpeg 解码失败(status=" + r.status + "): " + String(r.stderr || "").slice(0, 400));
  const buf = r.stdout as Buffer;
  const frames = Math.floor(buf.length / 4 / ch);
  const ring = new Float32Array(frames * ch);
  for (let i = 0; i < frames * ch; i++) ring[i] = buf.readFloatLE(i * 4) * 32768;
  return ring;
}

function toMonoArr(inter: Float32Array, frames: number, ch: number): Float32Array {
  const m = new Float32Array(frames);
  if (ch === 1) {
    m.set(inter.subarray(0, frames));
    return m;
  }
  for (let f = 0; f < frames; f++) {
    let s = 0;
    for (let c = 0; c < ch; c++) s += inter[f * ch + c];
    m[f] = s / ch;
  }
  return m;
}

function compareWithSource(dec: DecodedPcm, ch: number, sr: number, o: Opts, anchors: number[]): SourceCompare {
  const res: SourceCompare = {
    available: false,
    reason: null,
    phaseFrames: null,
    phaseNcc: null,
    ringFrames: null,
    junctionLag: null,
    junctionNcc: null,
    perSeam: [],
  };
  if (!o.source) {
    res.reason = "未提供 --source";
    return res;
  }
  const aMs = o.sourceAMs ?? o.aMs;
  const bMs = o.sourceBMs ?? o.bMs;
  if (aMs == null || bMs == null || bMs <= aMs) {
    res.reason = "缺少 --source-a-ms/--source-b-ms(或快照 aMs/bMs)";
    return res;
  }
  if (anchors.length === 0) {
    res.reason = "没有可用接缝锚点";
    return res;
  }
  let ring: Float32Array;
  try {
    ring = loadSourceRing(o.source, aMs, bMs, sr, ch);
  } catch (e: any) {
    res.reason = String(e?.message ?? e);
    return res;
  }
  const L = Math.floor(ring.length / ch);
  if (L <= 0) {
    res.reason = "源参考区间为空";
    return res;
  }
  res.available = true;
  res.ringFrames = L;
  const srcMono = toMonoArr(ring, L, ch);
  // 相位搜索:取捕获中段(避开接缝)100ms 作为探针
  const probeLen = Math.max(64, Math.min(Math.round(sr * 0.1), Math.floor(L / 4)));
  const probeStart = anchors[0] + Math.floor(Math.min(L / 2, Math.floor((dec.frames - anchors[0]) / 2)));
  let phase = 0;
  let phaseNcc = -2;
  if (probeStart + probeLen <= dec.frames && probeLen > 0) {
    const probe = hpWindow(dec.mono, probeStart, probeLen);
    let pEnergy = 0;
    for (let i = 0; i < probe.length; i++) pEnergy += probe[i] * probe[i];
    const step = Math.max(1, Math.round(sr * 0.002));
    let coarse = 0;
    let coarseNcc = -2;
    for (let p0 = 0; p0 < L - probeLen; p0 += step) {
      const w = hpWindow(srcMono, p0, probeLen);
      let dot = 0;
      let e = 0;
      for (let i = 0; i < probeLen; i++) {
        dot += probe[i] * w[i];
        e += w[i] * w[i];
      }
      const den = Math.sqrt(pEnergy * e);
      const ncc = den > 0 ? dot / den : 0;
      if (ncc > coarseNcc) {
        coarseNcc = ncc;
        coarse = p0;
      }
    }
    const from = Math.max(0, coarse - step);
    const to = Math.min(L - probeLen, coarse + step);
    for (let p0 = from; p0 <= to; p0++) {
      const w = hpWindow(srcMono, p0, probeLen);
      let dot = 0;
      let e = 0;
      for (let i = 0; i < probeLen; i++) {
        dot += probe[i] * w[i];
        e += w[i] * w[i];
      }
      const den = Math.sqrt(pEnergy * e);
      const ncc = den > 0 ? dot / den : 0;
      if (ncc > phaseNcc) {
        phaseNcc = ncc;
        phase = p0;
      }
    }
  }
  res.phaseFrames = phase;
  res.phaseNcc = phaseNcc;
  // 接缝对照:源侧理论接缝 = ring[L-half..L) ++ ring[0..half)
  const half = Math.max(2, Math.round((o.seamWindowMs * sr) / 1000));
  const maxLag = Math.max(1, Math.round((o.seamSearchMs * sr) / 1000));
  const template = new Float32Array(2 * half);
  for (let i = 0; i < half; i++) template[i] = srcMono[L - half + i];
  for (let i = 0; i < half; i++) template[half + i] = srcMono[i];
  const tHp = new Float32Array(2 * half);
  for (let i = 0; i < 2 * half; i++) tHp[i] = template[i] - (i > 0 ? template[i - 1] : 0);
  let worstLag = 0;
  let worstNcc = 2;
  for (const a of anchors) {
    if (a - half < 0 || a + half > dec.frames) {
      res.perSeam.push({ seam: a, lag: null, ncc: null });
      continue;
    }
    const w = hpWindow(dec.mono, a - half, 2 * half);
    const r = bestLag(w, tHp, maxLag);
    res.perSeam.push({ seam: a, lag: r.lag, ncc: r.ncc });
    if (Math.abs(r.lag) > Math.abs(worstLag)) worstLag = r.lag;
    if (r.ncc < worstNcc) worstNcc = r.ncc;
  }
  res.junctionLag = worstLag;
  res.junctionNcc = Number.isFinite(worstNcc) ? worstNcc : null;
  return res;
}

// ============================================================================
// 主分析
// ============================================================================

interface Item {
  name: string;
  status: "PASS" | "FAIL" | "WARN" | "SKIP";
  summary: string;
  details: string[];
  data: any;
}

interface AnalysisResult {
  version: string;
  input: any;
  params: any;
  zeroRuns: Item;
  clicks: Item;
  seamAlign: Item;
  rmsWindows: Item;
  dupCheck: Item;
  source: SourceCompare | null;
  result: "PASS" | "FAIL";
  failReasons: string[];
  notes: string[];
  diagnosis: string[];
  exitCode: number;
}

function analyzeFile(pcmPath: string, opts: Opts): AnalysisResult {
  if (!existsSync(pcmPath)) throw new Error("文件不存在: " + pcmPath);
  const buf = readFileSync(pcmPath);
  const eventsPath = opts.events ?? autodetectEvents(pcmPath);
  const ev = parseEvents(eventsPath);
  const meta = parsePcm(buf, opts, ev.configure);
  const dec = decodePcm(buf, meta);
  const sr = o_sr(opts, meta, ev);
  const ch = meta.ch;
  const frames = dec.frames;
  if (frames <= 0) throw new Error("PCM 帧数为 0: " + pcmPath);

  const notes: string[] = [];
  const failReasons: string[] = [];
  const diagnosis: string[] = [];
  const run = ev.runs.length > 0 ? ev.runs[ev.runs.length - 1] : null;
  const aMs = opts.aMs ?? numOf(run?.aMs);
  const bMs = opts.bMs ?? numOf(run?.bMs);
  let Ltheory = opts.loopFrames;
  let Lsource: "cli" | "us" | "ms" | "none" = Ltheory != null ? "cli" : "none";
  // 快照只有 ms(loopStartUs/1000 截断),L 的取整误差可达 1ms(48kHz 下约 48 帧)。
  // 若 run 事件里将来能带上 µs(aUs/bUs/loopStartUs/loopEndUs),这里就能算出与引擎
  // planRing 完全一致的 L:ceil(bUs*sr/1e6) - ceil(aUs*sr/1e6)。
  if (Ltheory == null) {
    const aUs = firstNumOf(run, ["loopStartUs", "aUs", "startUs"]);
    const bUs = firstNumOf(run, ["loopEndUs", "bUs", "endUs"]);
    if (aUs != null && bUs != null && bUs > aUs) {
      Ltheory = Math.ceil((bUs * sr) / 1e6) - Math.ceil((aUs * sr) / 1e6);
      Lsource = Ltheory > 0 ? "us" : "none";
    } else if (aMs != null && bMs != null && bMs > aMs) {
      Ltheory = msToFrames(bMs - aMs, sr);
      Lsource = "ms";
    }
  }
  // ms 快照的量化误差:两端各截断到 ms,最坏差 ~1ms
  const msQuantFrames = Lsource === "ms" ? Math.ceil(sr * 0.001) + 2 : 0;
  const spacingTol = Math.max(opts.spacingTolFrames, msQuantFrames);
  if (Lsource === "ms" && spacingTol > opts.spacingTolFrames) {
    notes.push("run 快照只有 ms 精度(loopStartUs/1000 截断):帧距容忍放大到 ±" + spacingTol + " 帧(±" +
      fmt((spacingTol * 1000) / sr, 2) + "ms);要精确到帧请让 run 事件带 loopStartUs/loopEndUs(µs)");
  }
  const seamFrames = ev.seams.map((e) => Math.round(numOf(e.outFrames) as number));

  if (!eventsPath) {
    notes.push("未找到 sidecar 事件文件(<tap>.jsonl):seamAlign/dupCheck 无接缝锚点会降级,无法判定环回对齐");
  }
  if (ev.runs.length > 1) {
    notes.push("run 快照写了 " + ev.runs.length + " 条(采集期内窗口变化过):L/帧距判据以最后一条为准,变化前的接缝可能属于旧窗口");
  }
  const endFrames = numOf(ev.end?.totalFrames);
  if (endFrames != null && endFrames !== frames) {
    notes.push("end.totalFrames=" + endFrames + " 与 PCM 帧数 " + frames + " 不一致:WAV 头未回填/文件被截断(未正常关 tap,或拉取不完整)");
  }

  // ---------------- zeroRuns ----------------
  const minZero = Math.max(1, msToFrames(opts.zeroMs, sr));
  const zr = scanZeroRuns(dec, ch, minZero, seamFrames);
  const zrBad = zr.runs.filter((r) => r.frames >= minZero && !r.atStart && !r.atEnd);
  const zrEdge = zr.runs.filter((r) => r.frames >= minZero && (r.atStart || r.atEnd));
  const zeroDetails: string[] = [];
  for (const r of zr.runs.filter((x) => x.frames >= Math.max(2, Math.round(sr * 0.0005))).slice(0, opts.maxList)) {
    const near = r.nearestSeam != null ? " seam@" + r.nearestSeam + "(" + (r.startFrame - r.nearestSeam) + ")" : "";
    zeroDetails.push(
      "run start=frame " + r.startFrame + " (" + fmt(framesToMs(r.startFrame, sr), 3) + "ms) len=" + r.frames +
      " frames (" + fmt(framesToMs(r.frames, sr), 3) + "ms)" + (r.atStart ? " [capture-start]" : "") + (r.atEnd ? " [capture-end]" : "") + near,
    );
  }
  const allZero = zr.zeroFrames === frames;
  let zeroStatus: Item["status"] = "PASS";
  let zeroSummary = "无 >= " + fmt(opts.zeroMs, 3) + "ms 的数字静音段(" + zr.runs.length + " 个零值段,最长 " +
    (zr.longest ? fmt(framesToMs(zr.longest.frames, sr), 3) + "ms" : "0ms") + ")";
  if (allZero) {
    zeroStatus = "FAIL";
    zeroSummary = "整段 PCM 全 0(采集期内没有真实输出:缓冲没建起来 / tap 打开时未播放)";
  } else if (zrBad.length > 0) {
    zeroStatus = "FAIL";
    zeroSummary = zrBad.length + " 段 >= " + fmt(opts.zeroMs, 3) + "ms 的数字静音(首段起点 frame " + zrBad[0].startFrame +
      " / " + fmt(framesToMs(zrBad[0].startFrame, sr), 3) + "ms,长 " + zrBad[0].frames + " 帧)";
  } else if (zrEdge.length > 0) {
    zeroStatus = "WARN";
    zeroSummary = "仅在采集首/尾存在静音段(采集起止边界,不算输出中断)";
  }
  const zeroItem: Item = { name: "zeroRuns", status: zeroStatus, summary: zeroSummary, details: zeroDetails, data: { runs: zr.runs.length, zeroFrames: zr.zeroFrames, bad: zrBad.length, edge: zrEdge.length } };
  if (zeroStatus === "FAIL") failReasons.push("zeroRuns: " + zeroSummary);

  // ---------------- clicks ----------------
  const dmax = new Float32Array(frames);
  for (let f = 1; f < frames; f++) {
    let mx = 0;
    for (let c = 0; c < ch; c++) {
      const d = Math.abs(dec.inter[f * ch + c] - dec.inter[(f - 1) * ch + c]);
      if (d > mx) mx = d;
    }
    dmax[f] = mx;
  }
  const p999 = percentileFromDeltas(dmax);
  const thr = Math.max(p999, opts.clickFloor);
  const seamTol = Math.max(2, Math.round(sr * 0.002));
  const clickRes = scanClicks(dec, ch, sr, thr, opts.clickLocality, 20, seamFrames);
  const seamClicks = clickRes.clusters.filter((c) => c.seamDistance != null && Math.abs(c.seamDistance) <= seamTol);
  const nonSeamClicks = clickRes.clusters.filter((c) => !(c.seamDistance != null && Math.abs(c.seamDistance) <= seamTol));
  const clickDetails: string[] = [];
  for (const c of clickRes.clusters.slice(0, opts.maxList)) {
    const seamTag = c.seamDistance != null && Math.abs(c.seamDistance) <= seamTol
      ? " SEAM#" + (seamFrames.indexOf(c.nearestSeam as number) + 1) + "(dt=" + c.seamDistance + " frames)"
      : "";
    clickDetails.push(
      "click frame=" + c.frame + " (" + fmt(c.ms, 3) + "ms) peakDelta=" + Math.round(c.peakDelta) + " ch=" + c.peakChannel +
      " width=" + c.widthFrames + " frames" + seamTag,
    );
  }
  let clickStatus: Item["status"] = "PASS";
  let clickSummary = "无爆音(阈值 " + Math.round(thr) + " = max(p99.9=" + Math.round(p999) + ", floor=" + opts.clickFloor +
    "),阈值超限点 " + clickRes.exceedCount + " 个,经局部离群门限后 " + clickRes.clusters.length + " 处)";
  if (nonSeamClicks.length > 0) {
    clickStatus = "FAIL";
    clickSummary = nonSeamClicks.length + " 处非接缝爆音(最响 frame " + nonSeamClicks[0].frame + " / " + fmt(nonSeamClicks[0].ms, 3) + "ms,Δ=" + Math.round(nonSeamClicks[0].peakDelta) + ")";
  } else if (seamClicks.length > 0) {
    if (opts.allowSeamClicks) {
      clickStatus = "WARN";
      clickSummary = seamClicks.length + " 处接缝爆音(--allow-seam-clicks:仅报告)";
    } else {
      clickStatus = "FAIL";
      clickSummary = seamClicks.length + " 处接缝爆音(最响 frame " + seamClicks[0].frame + " / " + fmt(seamClicks[0].ms, 3) + "ms,Δ=" + Math.round(seamClicks[0].peakDelta) + ")";
    }
  }
  const clickItem: Item = { name: "clicks", status: clickStatus, summary: clickSummary, details: clickDetails, data: { threshold: Math.round(thr), p999: Math.round(p999), exceed: clickRes.exceedCount, clusters: clickRes.clusters.length, seamClicks: seamClicks.length, nonSeamClicks: nonSeamClicks.length } };
  if (clickStatus === "FAIL") failReasons.push("clicks: " + clickSummary);

  // ---------------- seamAlign ----------------
  const sa = analyzeSeams(dec, opts, sr, Ltheory, ev.seams);
  const seamDetails: string[] = [];
  for (const r of sa.rows.slice(0, opts.maxList)) {
    const parts: string[] = [];
    parts.push("#" + r.index + " frame=" + r.frame + " (" + fmt(r.ms, 1) + "ms)");
    if (r.spacing != null) parts.push("spacing=" + r.spacing + (r.dL != null ? " (dL=" + (r.dL > 0 ? "+" : "") + r.dL + ")" : ""));
    if (r.periodNcc != null) parts.push("ncc=" + fmt(r.periodNcc, 4) + " lag=" + (r.periodLag != null && r.periodLag > 0 ? "+" : "") + r.periodLag);
    if (r.ptsDelta != null) parts.push("ptsΔ=" + fmt(r.ptsDelta, 2) + "f");
    if (r.driftFromSnapshot != null && Lsource !== "ms") parts.push("drift=" + (r.driftFromSnapshot > 0 ? "+" : "") + r.driftFromSnapshot);
    seamDetails.push(parts.join(" | "));
  }
  let seamStatus: Item["status"] = "PASS";
  let seamSummary = "";
  const seamIssues: string[] = [];
  if (sa.rows.length === 0) {
    seamStatus = "FAIL";
    seamSummary = "无可用接缝锚点(事件文件里没有 seam 事件,或 outFrames 越界):无法验证环回对齐;请确认 tap 在 /loop/enter 之前打开";
  } else if (sa.rows.length === 1) {
    seamStatus = "SKIP";
    seamSummary = "只捕获到 1 处接缝:无法核对帧距/漂移(建议 --seconds 覆盖 >= 3 个环)";
  } else {
    if (sa.maxAbsDL != null && Ltheory != null && sa.maxAbsDL > spacingTol) {
      seamIssues.push("环回帧距偏离理论 L:最大 |dL|=" + sa.maxAbsDL + " 帧(容忍 " + spacingTol + " 帧),实测 spacing " +
        sa.spacingMin + ".." + sa.spacingMax + " 帧,理论 L=" + Ltheory + "(来源 " + Lsource + ")");
    }
    if (sa.maxAbsPeriodLag != null && sa.maxAbsPeriodLag > opts.lagTolFrames) {
      seamIssues.push("接缝互相关峰值偏移 " + sa.maxAbsPeriodLag + " 帧(容忍 " + opts.lagTolFrames +
        " 帧):相邻周期在同一相位处内容错位(环内素材被移位/拼接长度不一致)");
    }
    if (sa.maxAbsPtsDelta != null && sa.maxAbsPtsDelta > opts.ptsTolFrames) {
      seamIssues.push("接缝 PTS 与输出帧号不一致:最大偏差 " + fmt(sa.maxAbsPtsDelta, 2) + " 帧(容忍 " + opts.ptsTolFrames + " 帧)");
    }
    const lowNcc = sa.minPeriodNcc != null && sa.minPeriodNcc < 0.9;
    if (seamIssues.length > 0) {
      seamStatus = "FAIL";
      seamSummary = seamIssues.join(";");
    } else if (Ltheory == null) {
      seamStatus = "WARN";
      seamSummary = "接缝 " + sa.rows.length + " 处,跨周期 ncc>=" + fmt(sa.minPeriodNcc ?? 0, 4) +
        ",但快照无 aMs/bMs:帧距无法与理论 L 比对(仅周期一致性)";
    } else if (lowNcc) {
      // ncc 低说明「同相位窗口」本身不可比(附近有爆音/静音段/内容变化),相位结论置信度下降;
      // 这类缺陷由 clicks/zeroRuns 单项负责判失败,这里只降级为 WARN,避免一处故障报三处。
      seamStatus = "WARN";
      seamSummary = sa.rows.length + " 处接缝帧距/相位一致(dL<=" + (sa.maxAbsDL ?? 0) + " 帧),但跨周期相关偏低(ncc=" +
        fmt(sa.minPeriodNcc ?? 0, 4) + " < 0.9):接缝附近有爆音/静音/内容变化,相位结论置信度下降(不单独判失败)";
    } else {
      seamStatus = "PASS";
      seamSummary = sa.rows.length + " 处接缝:帧距 " + (sa.spacingMin === sa.spacingMax ? String(sa.spacingMin) : sa.spacingMin + ".." + sa.spacingMax) +
        " 帧" + (Ltheory != null ? "(L=" + Ltheory + ")" : "") +
        ",跨周期 ncc>=" + fmt(sa.minPeriodNcc ?? 0, 4) +
        ",峰值偏移<=" + (sa.maxAbsPeriodLag ?? 0) + " 帧(容忍 " + opts.lagTolFrames + ")" +
        (sa.maxAbsPtsDelta != null ? ",PTS 偏差<=" + fmt(sa.maxAbsPtsDelta, 2) + " 帧" : "");
    }
  }
  const seamItem: Item = { name: "seamAlign", status: seamStatus, summary: seamSummary, details: seamDetails, data: { anchors: sa.rows.length, Ltheory, medianL: sa.medianL, spacingMin: sa.spacingMin, spacingMax: sa.spacingMax, maxAbsDL: sa.maxAbsDL, minPeriodNcc: sa.minPeriodNcc, maxAbsPeriodLag: sa.maxAbsPeriodLag, maxAbsPtsDelta: sa.maxAbsPtsDelta, rows: sa.rows } };
  if (seamStatus === "FAIL") failReasons.push("seamAlign: " + seamSummary);

  // ---------------- rmsWindows ----------------
  const rm = analyzeRms(dec, sr, opts.rmsWinMs, opts.rmsDropDb);
  const rmsDetails: string[] = [];
  for (const w of rm.windows) {
    if (w.dropDb != null) {
      rmsDetails.push(
        "drop " + fmt(w.dropDb, 1) + "dB @ " + fmt(w.ms, 1) + "ms (frame " + w.startFrame + "): " +
        fmt(rm.windows[w.index - 1].db, 2) + " -> " + fmt(w.db, 2) + " dBFS" + (w.hole ? " [hole: 随后恢复]" : " [sustained: 电平变化]"),
      );
    }
  }
  const holeCount = rm.holes.length;
  const sustained = rm.windows.filter((w) => w.dropDb != null && !w.hole).length;
  let rmsStatus: Item["status"] = "PASS";
  let rmsSummary = rm.windows.length + " 个 " + fmt(opts.rmsWinMs, 0) + "ms 窗,最低 " + (Number.isFinite(rm.minDb) ? fmt(rm.minDb, 2) : "-inf") + " dBFS,无 > " + opts.rmsDropDb + "dB 突降";
  if (holeCount > 0) {
    rmsStatus = "FAIL";
    rmsSummary = holeCount + " 个窗突降 > " + opts.rmsDropDb + "dB 且随后恢复(输出掉块,最早 " + fmt(rm.holes[0].ms, 1) + "ms)";
  } else if (sustained > 0) {
    rmsStatus = "WARN";
    rmsSummary = sustained + " 个窗电平下降 > " + opts.rmsDropDb + "dB 但未恢复(多为乐段变化,不判失败)";
  }
  const rmsItem: Item = { name: "rmsWindows", status: rmsStatus, summary: rmsSummary, details: rmsDetails, data: { winMs: opts.rmsWinMs, windowCount: rm.windows.length, minDb: Number.isFinite(rm.minDb) ? rm.minDb : null, holes: holeCount, sustained, drops: rm.windows.filter((w) => w.dropDb != null).map((w) => ({ ms: w.ms, dropDb: w.dropDb, hole: w.hole })) } };
  if (rmsStatus === "FAIL") failReasons.push("rmsWindows: " + rmsSummary);

  // ---------------- dupCheck ----------------
  const anchors = sa.anchors;
  const dup = analyzeDup(dec, ch, anchors, opts.dupTol, frames);
  const dupDetails: string[] = [];
  for (const p of dup.pairs.slice(0, opts.maxList)) {
    if (p.identical) {
      dupDetails.push("pair seam#" + p.fromSeam + "->#" + p.toSeam + " (" + p.frames + " frames) identical");
    } else {
      dupDetails.push(
        "pair seam#" + p.fromSeam + "->#" + p.toSeam + " (" + p.frames + " frames) differs: diffSamples=" + p.diffSamples +
        " maxDelta=" + Math.round(p.maxDelta) + " firstDiff=period+frame " + p.firstDiffFrame + " ch" + p.firstDiffChannel +
        (p.lengthMismatch != null ? " lengthMismatch=" + p.lengthMismatch + " frames" : ""),
      );
    }
  }
  let dupStatus: Item["status"] = "PASS";
  let dupSummary = "";
  if (anchors.length < 3) {
    dupStatus = "SKIP";
    dupSummary = "完整环回周期少于 2 个(接缝 " + anchors.length + " 处),无法比较相邻周期";
  } else {
    const bad = dup.pairs.filter((p) => !p.identical);
    if (bad.length === 0) {
      dupSummary = dup.pairs.length + " 对相邻周期逐样本相同(" + dup.pairs[0].frames + " 帧/周期,容差 " + opts.dupTol + ")";
    } else {
      const firstBad = bad[0];
      dupStatus = "FAIL";
      dupSummary = bad.length + "/" + dup.pairs.length + " 对相邻周期不一致(首个 seam#" + firstBad.fromSeam + "->#" + firstBad.toSeam +
        ": diffSamples=" + firstBad.diffSamples + " maxDelta=" + Math.round(firstBad.maxDelta) +
        " firstDiff=period+frame " + firstBad.firstDiffFrame + " ch" + firstBad.firstDiffChannel +
        (firstBad.lengthMismatch != null ? " lengthMismatch=" + firstBad.lengthMismatch + " 帧" : "") + ")";
      if (ev.flushCount > 0 || ev.degradeCount > 0) {
        dupSummary += "(注意:采集期内 flush=" + ev.flushCount + " degrade=" + ev.degradeCount + ",重播种会导致周期不同,先排除该因素)";
      }
    }
  }
  const dupItem: Item = { name: "dupCheck", status: dupStatus, summary: dupSummary, details: dupDetails, data: { pairs: dup.pairs, flushCount: ev.flushCount, degradeCount: ev.degradeCount } };
  if (dupStatus === "FAIL") failReasons.push("dupCheck: " + dupSummary);

  // ---------------- 源对照 ----------------
  const src = opts.source ? compareWithSource(dec, ch, sr, opts, anchors) : null;

  // ---------------- 诊断 ----------------
  if (zeroStatus === "FAIL") diagnosis.push("DIAG zeroRuns: 输出里出现成段数字静音 => 缓冲/预填没接上(ring 未完整或 delegate 饥饿),先看 LoopPerf stallCount / covered / cursor");
  if (nonSeamClicks.length > 0) diagnosis.push("DIAG clicks(非接缝): 与接缝无关的爆音 => 解码/写入损坏或重采样错,对照 dump 源 PCM 与 logcat 解码错误");
  if (seamClicks.length > 0 && nonSeamClicks.length === 0 && zeroStatus !== "FAIL") {
    diagnosis.push("DIAG clicks(接缝): 接缝点样本跳变 => 环路拼接点本身不连续(取点选在波形陡变处,或 B->A 拼接长度错)");
  }
  if (dupStatus === "FAIL") diagnosis.push("DIAG dupCheck: 相邻周期不一致 => ring 内容被改写/部分覆盖(degrade 重播种、预填分片未完成),不是同一段素材在循环");
  if (rmsStatus === "FAIL") diagnosis.push("DIAG rmsWindows: 输出中途掉块后恢复 => delegate 欠载/重试,查 LoopPerf stallCount、pendingReplayBytes");
  if (seamStatus === "FAIL" && sa.rows.length > 1 && nonSeamClicks.length === 0 && seamClicks.length === 0 && dupStatus !== "FAIL") {
    diagnosis.push("DIAG seamAlign: 接缝本身无爆音但帧距/相位不符 => 快照 A/B 与实际 ring 边界不一致(取素材错位或窗口漂移)");
  }
  if (src && src.available && src.junctionLag != null && Math.abs(src.junctionLag) > opts.spacingTolFrames && (src.junctionNcc ?? 0) > 0.95) {
    diagnosis.push("DIAG source: tap 在接缝处与源 [A,B) 的 B->A 接缝相关峰偏移 " + src.junctionLag + " 帧(ncc=" + fmt(src.junctionNcc ?? 0, 3) + ") => 引擎拼接错位,不是解码错");
  }
  if (src && src.available && (src.phaseNcc ?? 0) < 0.9) {
    diagnosis.push("DIAG source: tap 内容与 ffmpeg 解出的 [A,B) 相位相关仅 ncc=" + fmt(src.phaseNcc ?? 0, 3) + " => 解码结果与参考不一致(解码错)或素材不是该区间");
  }
  if (diagnosis.length === 0) diagnosis.push("DIAG: 5 项指标均未发现异常,输出连续性达标");

  // ---------------- 汇总 ----------------
  const items = [zeroItem, clickItem, seamItem, rmsItem, dupItem];
  const result: "PASS" | "FAIL" = failReasons.length === 0 ? "PASS" : "FAIL";

  const input = {
    pcm: pcmPath,
    events: eventsPath,
    pcmBytes: buf.length,
    hasWavHeader: meta.hasHeader,
    sampleRate: sr,
    channels: ch,
    bits: meta.bits,
    codec: meta.codec,
    frames,
    durationMs: framesToMs(frames, sr),
    eventsCount: ev.events.length,
    seamEvents: ev.seamEvents,
    runs: ev.runs,
    end: ev.end,
    flushCount: ev.flushCount,
    degradeCount: ev.degradeCount,
    parseErrors: ev.parseErrors,
    truncatedLastLine: ev.truncatedLastLine,
  };
  const params = {
    aMs,
    bMs,
    loopFramesTheory: Ltheory,
    zeroMs: opts.zeroMs,
    clickFloor: opts.clickFloor,
    clickLocality: opts.clickLocality,
    rmsWinMs: opts.rmsWinMs,
    rmsDropDb: opts.rmsDropDb,
    seamWindowMs: opts.seamWindowMs,
    seamSearchMs: opts.seamSearchMs,
    LtheorySource: Lsource,
    spacingTolFrames: spacingTol,
    lagTolFrames: opts.lagTolFrames,
    ptsTolFrames: opts.ptsTolFrames,
    dupTol: opts.dupTol,
  };

  const res: AnalysisResult = {
    version: VERSION,
    input,
    params,
    zeroRuns: zeroItem,
    clicks: clickItem,
    seamAlign: seamItem,
    rmsWindows: rmsItem,
    dupCheck: dupItem,
    source: src,
    result,
    failReasons,
    notes: notes.concat(meta.warnings),
    diagnosis,
    exitCode: result === "PASS" ? 0 : 1,
  };
  return res;
}

function o_sr(o: Opts, meta: PcmMeta, ev: TapEvents): number {
  const logSr = numOf(ev.configure?.sr);
  const sr = meta.hasHeader ? meta.sr : (o.sr > 0 ? o.sr : (logSr ?? meta.sr));
  if (logSr != null && sr !== logSr) {
    // 采样率不一致时以 WAV 头为准(它就是实际写盘的格式)
    meta.warnings.push("事件 configure.sr=" + logSr + " 与 PCM 采样率 " + sr + " 不一致,以 PCM 为准");
  }
  return sr;
}

// ============================================================================
// 打印
// ============================================================================

function printItem(item: Item, quiet: boolean) {
  const tag = item.status === "PASS" ? "[ OK ]" : item.status === "FAIL" ? "[FAIL]" : item.status === "WARN" ? "[WARN]" : "[SKIP]";
  out(tag + " " + item.name.padEnd(11, " ") + " " + item.summary);
  if (!quiet) {
    for (const d of item.details) out("        " + d);
  }
}

function printResult(r: AnalysisResult, quiet: boolean) {
  if (!quiet) {
    out("");
    out("== input ==");
    out("pcm    : " + r.input.pcm);
    out("events : " + (r.input.events ?? "(未找到 sidecar jsonl)"));
    out("format : " + r.input.codec + " sr=" + r.input.sampleRate + " ch=" + r.input.channels + " bits=" + r.input.bits +
      " frames=" + r.input.frames + " (" + fmt(r.input.durationMs, 2) + "ms) wavHeader=" + r.input.hasWavHeader);
    out("events : total=" + r.input.eventsCount + " seam=" + r.input.seamEvents + " flush=" + r.input.flushCount + " degrade=" + r.input.degradeCount +
      (r.input.end ? " end.totalFrames=" + (r.input.end.totalFrames ?? "?") : ""));
    const a = r.params.aMs;
    const b = r.params.bMs;
    out("window : aMs=" + (a ?? "?") + " bMs=" + (b ?? "?") + " => L_theory=" + (r.params.loopFramesTheory ?? "?") +
      " frames (" + (a != null && b != null && r.params.loopFramesTheory != null
        ? fmt((r.params.loopFramesTheory * 1000) / r.input.sampleRate, 3) + "ms)"
        : "?)"));
    for (const w of r.notes) out("note   : " + w);
    out("");
    out("== metrics ==");
  }
  printItem(r.zeroRuns, quiet);
  printItem(r.clicks, quiet);
  printItem(r.seamAlign, quiet);
  printItem(r.rmsWindows, quiet);
  printItem(r.dupCheck, quiet);
  if (r.source) {
    const s = r.source;
    if (s.available) {
      out("[SRC ] source      ring=" + s.ringFrames + " frames, phase=" + s.phaseFrames + " (ncc=" + fmt(s.phaseNcc ?? 0, 4) +
        "), junctionLag=" + s.junctionLag + " ncc=" + fmt(s.junctionNcc ?? 0, 4));
    } else {
      out("[SKIP] source      " + (s.reason ?? ""));
    }
  }
  out("");
  for (const d of r.diagnosis) out(d);
  if (r.failReasons.length > 0) {
    out("");
    out("failures:");
    for (const f of r.failReasons) out("  - " + f);
  }
  out("");
  out("RESULT: " + r.result);
}

// ============================================================================
// fixture 自测
// ============================================================================

const FIXTURE_SR = 16000;
const FIXTURE_CH = 2;
const FIXTURE_B_MS = 800;               // L = 800ms = 12800 frames
const FIXTURE_L = Math.round((FIXTURE_B_MS * FIXTURE_SR) / 1000);
const FIXTURE_PRELUDE = Math.round(FIXTURE_SR * 0.4);
const FIXTURE_LOOPS = 3;
const FIXTURE_TAIL = Math.round(FIXTURE_SR * 0.2);

function synthRing(sr: number, L: number, ch: number): Float32Array {
  const out = new Float32Array(L * ch);
  const partials = [
    { m: 176, a: 0.32 },  // 220 Hz
    { m: 264, a: 0.18 },  // 330 Hz
    { m: 440, a: 0.12 },  // 550 Hz
    { m: 777, a: 0.06 },  // 971 Hz
  ];
  for (let f = 0; f < L; f++) {
    let v = 0;
    for (const p of partials) v += p.a * Math.sin((2 * Math.PI * p.m * f) / L + p.m * 0.7);
    const s = v * 32767 * 0.8;
    for (let c = 0; c < ch; c++) out[f * ch + c] = s * (c === 0 ? 1 : 0.85);
  }
  void sr;
  return out;
}

function buildWav(inter: Float32Array, sr: number, ch: number): Buffer {
  const frames = Math.floor(inter.length / ch);
  const dataBytes = frames * ch * 2;
  const buf = Buffer.alloc(44 + dataBytes);
  buf.write("RIFF", 0, "latin1");
  buf.writeUInt32LE(36 + dataBytes, 4);
  buf.write("WAVE", 8, "latin1");
  buf.write("fmt ", 12, "latin1");
  buf.writeUInt32LE(16, 16);
  buf.writeUInt16LE(1, 20);
  buf.writeUInt16LE(ch, 22);
  buf.writeUInt32LE(sr, 24);
  buf.writeUInt32LE(sr * ch * 2, 28);
  buf.writeUInt16LE(ch * 2, 32);
  buf.writeUInt16LE(16, 34);
  buf.write("data", 36, "latin1");
  buf.writeUInt32LE(dataBytes, 40);
  for (let i = 0; i < frames * ch; i++) {
    let v = Math.round(inter[i]);
    if (v > 32767) v = 32767;
    if (v < -32768) v = -32768;
    buf.writeInt16LE(v, 44 + i * 2);
  }
  return buf;
}

interface FixtureSpec {
  name: string;
  kind: "ok" | "click" | "silence" | "misaligned";
}

function buildFixture(spec: FixtureSpec): { wav: Buffer; jsonl: string; expect: any } {
  const ring = synthRing(FIXTURE_SR, FIXTURE_L, FIXTURE_CH);
  // 错位 40 帧(2.5ms @16k):超过 ms 量化容忍(18 帧),同时跨过 lag/PTS 判据
  const extra = spec.kind === "misaligned" ? 40 : 0;
  const period = FIXTURE_L + extra;
  // 错位 fixture 不加尾巴:否则尾巴与「多出来的 40 帧」之间会产生一个非接缝跳变,污染 clicks 判据
  const tail = spec.kind === "misaligned" ? 0 : FIXTURE_TAIL;
  const total = FIXTURE_PRELUDE + FIXTURE_LOOPS * period + tail;
  const inter = new Float32Array(total * FIXTURE_CH);
  // 前奏 = ring 尾部(与 ring[0] 连续,模拟从 B 前 prelude 处播放到 B)
  for (let f = 0; f < FIXTURE_PRELUDE; f++) {
    const src = ((FIXTURE_L - FIXTURE_PRELUDE + f) % FIXTURE_L) * FIXTURE_CH;
    for (let c = 0; c < FIXTURE_CH; c++) inter[f * FIXTURE_CH + c] = ring[src + c];
  }
  let pos = FIXTURE_PRELUDE;
  for (let k = 0; k < FIXTURE_LOOPS; k++) {
    for (let f = 0; f < FIXTURE_L; f++) {
      for (let c = 0; c < FIXTURE_CH; c++) inter[(pos + f) * FIXTURE_CH + c] = ring[f * FIXTURE_CH + c];
    }
    // 错位 fixture:每轮多 7 帧(取 ring 头部),使周期长度 != L
    for (let f = 0; f < extra; f++) {
      for (let c = 0; c < FIXTURE_CH; c++) inter[(pos + FIXTURE_L + f) * FIXTURE_CH + c] = ring[f * FIXTURE_CH + c];
    }
    pos += period;
  }
  for (let f = 0; f < tail; f++) {
    for (let c = 0; c < FIXTURE_CH; c++) inter[(pos + f) * FIXTURE_CH + c] = ring[f * FIXTURE_CH + c];
  }
  // 已知故障注入
  if (spec.kind === "click") {
    // 真实「接缝爆音」是周期性的:每个环回点都有同样的样本跳变,环内容本身仍逐周期相同
    for (let k = 0; k < FIXTURE_LOOPS; k++) {
      const f = FIXTURE_PRELUDE + k * period;
      inter[f * FIXTURE_CH] += 24000;
      if (inter[f * FIXTURE_CH] > 32767) inter[f * FIXTURE_CH] = 32767;
    }
  } else if (spec.kind === "silence") {
    const len = Math.round((5 * FIXTURE_SR) / 1000); // 恰好 5ms
    const f0 = FIXTURE_PRELUDE;
    for (let f = f0; f < f0 + len; f++) {
      for (let c = 0; c < FIXTURE_CH; c++) inter[f * FIXTURE_CH + c] = 0;
    }
  }
  const framesTotal = FIXTURE_PRELUDE + FIXTURE_LOOPS * period + tail;
  const seamLines: string[] = [];
  for (let k = 0; k < FIXTURE_LOOPS; k++) {
    const frame = FIXTURE_PRELUDE + k * period;
    const ptsUs = FIXTURE_B_MS * 1000 + k * FIXTURE_B_MS * 1000;
    seamLines.push(JSON.stringify({ t: 400 + k * FIXTURE_B_MS, seq: 3 + k, ev: "seam", iter: k + 1, outFrames: frame, ptsUs }));
  }
  const lines = [
    JSON.stringify({ t: 0, seq: 1, ev: "configure", sr: FIXTURE_SR, ch: FIXTURE_CH, bpf: FIXTURE_CH * 2 }),
    JSON.stringify({ t: 0, seq: 2, ev: "run", mode: "sink", aMs: 0, bMs: FIXTURE_B_MS, sampleRate: FIXTURE_SR }),
    ...seamLines,
    JSON.stringify({ t: 400 + FIXTURE_LOOPS * FIXTURE_B_MS, seq: 100, ev: "end", totalFrames: framesTotal, flushCount: 0, degradeCount: 0 }),
  ];
  return {
    wav: buildWav(inter, FIXTURE_SR, FIXTURE_CH),
    jsonl: lines.join("\n") + "\n",
    expect:
      spec.kind === "ok"
        ? { result: "PASS", items: { zeroRuns: "PASS", clicks: "PASS", seamAlign: "PASS", rmsWindows: "PASS", dupCheck: "PASS" } }
        : spec.kind === "click"
          // 纯接缝爆音:只有 clicks 该判 FAIL,其余指标必须保持 PASS(否则就分不清"接缝爆音"与"缓冲没接上")
          ? { result: "FAIL", item: "clicks", status: "FAIL", items: { zeroRuns: "PASS", seamAlign: "PASS", rmsWindows: "PASS", dupCheck: "PASS" } }
          : spec.kind === "silence"
            ? { result: "FAIL", item: "zeroRuns", status: "FAIL", items: { rmsWindows: "PASS" } }
            // 环长错位 = 每轮多/少 frames,接缝处必然伴随样本跳变(clicks 也会 FAIL),这里只锚定 seamAlign
            : { result: "FAIL", item: "seamAlign", status: "FAIL", items: { zeroRuns: "PASS", rmsWindows: "PASS", dupCheck: "PASS" } },
  };
}

function runSelftest(dir: string): number {
  mkdirSync(dir, { recursive: true });
  const specs: FixtureSpec[] = [
    { name: "ok-loop", kind: "ok" },
    { name: "click-at-seam", kind: "click" },
    { name: "silence-5ms-at-seam", kind: "silence" },
    { name: "misaligned-loop", kind: "misaligned" },
  ];
  out("== loop-smoothness selftest ==");
  out("fixtures: " + dir);
  let failures = 0;
  for (const spec of specs) {
    const built = buildFixture(spec);
    const wavPath = path.join(dir, spec.name + ".wav");
    const jsonPath = path.join(dir, spec.name + ".jsonl");
    writeFileSync(wavPath, built.wav);
    writeFileSync(jsonPath, built.jsonl);
    const opts = defaultOpts();
    opts.pcm = wavPath;
    opts.events = jsonPath;
    let res: AnalysisResult;
    try {
      res = analyzeFile(wavPath, opts);
    } catch (e: any) {
      out("[FAIL] " + spec.name + " — 分析抛错: " + String(e?.message ?? e));
      failures += 1;
      continue;
    }
    const exp = built.expect;
    const checks: string[] = [];
    let ok = true;
    if (res.result !== exp.result) {
      ok = false;
      checks.push("RESULT 期望 " + exp.result + " 实得 " + res.result);
    }
    if (exp.item) {
      const item = (res as any)[exp.item] as Item;
      if (!item || item.status !== exp.status) {
        ok = false;
        checks.push(exp.item + " 期望 " + exp.status + " 实得 " + (item ? item.status : "(缺失)"));
      }
    }
    if (exp.items) {
      for (const nm of Object.keys(exp.items)) {
        const want = exp.items[nm];
        const it = (res as any)[nm] as Item;
        if (!it || it.status !== want) {
          ok = false;
          checks.push(nm + " 期望 " + want + " 实得 " + (it ? it.status + " (" + it.summary + ")" : "(缺失)"));
        }
      }
    }
    if (spec.kind === "click") {
      const c = res.clicks.data;
      if (!(c.seamClicks >= 1)) {
        ok = false;
        checks.push("clicks 未把爆音标为接缝处(seamClicks=" + c.seamClicks + ")");
      }
    }
    if (spec.kind === "silence") {
      const details = res.zeroRuns.details.join(" | ");
      if (!/len=80 frames/.test(details)) {
        ok = false;
        checks.push("zeroRuns 明细未报出 80 帧(5ms)静音段: " + details);
      }
      const runStart = /run start=frame (\d+)/.exec(details);
      if (!runStart || Number(runStart[1]) !== FIXTURE_PRELUDE) {
        ok = false;
        checks.push("zeroRuns 静音段起点应为接缝帧 " + FIXTURE_PRELUDE + " 实得 " + (runStart ? runStart[1] : "(未找到)"));
      }
    }
    if (!ok) failures += 1;
    out((ok ? "[ OK ] " : "[FAIL] ") + spec.name + "  RESULT=" + res.result +
      "  zeroRuns=" + res.zeroRuns.status + " clicks=" + res.clicks.status +
      " seamAlign=" + res.seamAlign.status + " rmsWindows=" + res.rmsWindows.status + " dupCheck=" + res.dupCheck.status);
    for (const c of checks) out("        " + c);
    if (!ok) {
      for (const it of [res.zeroRuns, res.clicks, res.seamAlign, res.rmsWindows, res.dupCheck]) {
        if (it.status !== "PASS") out("        " + it.name + ": " + it.summary);
      }
    }
  }
  out("");
  if (failures === 0) {
    out("SELFTEST: PASS (4/4 fixture 判定符合预期)");
    return 0;
  }
  out("SELFTEST: FAIL (" + failures + "/" + specs.length + " 个 fixture 判定不符)");
  return 1;
}

// ============================================================================
// main
// ============================================================================

function main(): number {
  let opts: Opts;
  try {
    opts = parseArgs(process.argv.slice(2));
  } catch (e: any) {
    errOut(String(e?.message ?? e));
    errOut(usage());
    return 2;
  }
  try {
    if (opts.help) {
      out(usage());
      return 0;
    }
    if (opts.selftest) {
      const dir = opts.fixtures ?? path.join(SCRIPT_DIR, "fixtures", "loop-smoothness");
      return runSelftest(dir);
    }
    if (!opts.pcm) {
      errOut("缺少 PCM 文件参数");
      errOut(usage());
      return 2;
    }
    let res = analyzeFile(opts.pcm, opts);
    if (opts.baseline) {
      // 基线对比:普通播放自身也会有素材瞬态/RMS 起伏,只应报告"循环多出来的"异常。
      const baseOpts: Opts = { ...opts, baseline: null, events: null, quiet: true };
      let base: AnalysisResult;
      try {
        base = analyzeFile(opts.baseline, baseOpts);
      } catch (e: any) {
        errOut("基线分析失败: " + String(e?.message ?? e));
        return 2;
      }
      const countOf = (it: Item): number => {
        if (typeof it?.data?.count === "number") return it.data.count;
        // details 会被 maxList 截断,真实条数从 summary 的开头数字取。
        const m = /^(\d+)/.exec(String(it?.summary ?? ""));
        if (m) return Number(m[1]);
        return Array.isArray(it?.details) ? it.details.length : 0;
      };
      const loopClicks = countOf(res.clicks);
      const baseClicks = countOf(base.clicks);
      const loopHoles = countOf(res.rmsWindows);
      const baseHoles = countOf(base.rmsWindows);
      const clickExtra = loopClicks - baseClicks;
      const holeExtra = loopHoles - baseHoles;
      const ok = clickExtra <= opts.baselineClickTol && holeExtra <= opts.baselineHoleTol;
      const lines = [
        "",
        "== baseline ==",
        `baseline : ${opts.baseline}`,
        `clicks   : loop=${loopClicks} baseline=${baseClicks} extra=${clickExtra} (tol ${opts.baselineClickTol})`,
        `rmsHoles : loop=${loopHoles} baseline=${baseHoles} extra=${holeExtra} (tol ${opts.baselineHoleTol})`,
        ok ? "BASELINE: PASS(循环与普通播放无实质差异)" : "BASELINE: FAIL(循环比普通播放多出异常)",
        "",
      ];
      for (const l of lines) out(l);
      if (!ok) {
        res = {
          ...res,
          result: "FAIL",
          exitCode: 1,
          failReasons: [...res.failReasons, `baseline: clicks ++${clickExtra}, rmsHoles ++${holeExtra}`],
        };
      } else {
        // 素材本身的瞬态/起伏不算循环缺陷:清掉这两类失败理由。
        const keep = res.failReasons.filter((r) => !/^(clicks|rmsWindows)/.test(r));
        res = { ...res, failReasons: keep, result: keep.length === 0 ? "PASS" : "FAIL", exitCode: keep.length === 0 ? 0 : 1 };
      }
    }
    printResult(res, opts.quiet);
    if (opts.json) {
      const dir = path.dirname(opts.json);
      if (dir && dir !== ".") mkdirSync(dir, { recursive: true });
      writeFileSync(opts.json, JSON.stringify(res, null, 2), "utf8");
      out("json: " + opts.json);
    }
    return res.exitCode;
  } catch (e: any) {
    errOut("ERROR: " + String(e?.message ?? e));
    return 2;
  }
}

process.exit(main());
