#!/usr/bin/env bun
/**
 * tools/scenarios/content-probe.ts —— 「内容真值」判据库（issue 00024 追加，覆盖 00027）。
 *
 * 要解决的问题
 * ------------
 * 00027 的现象是「进度条对、声音错」：display 折返到 A、outputFrames 在涨、engine=loop，
 * 现有判据（元数据层）全部 PASS，但耳朵听到的是 B 之后的内容。元数据判据无法证伪这一类，
 * 必须在**内容层**核对：把实际写进 AudioSink 的 PCM 与「按 display 位置本应听到的那段」
 * 做归一化互相关（NCC），峰值就是「听到的 == 显示的」的量化证据。
 *
 * 数据来源
 * --------
 * 1) 实际输出（tap）：POST /debug/tap {path} 让 TapAudioSink 把**交给 AudioTrack 的 PCM**
 *    原样落成 WAV（44 字节头 + 交错 PCM16），附带同名 .jsonl 侧车事件
 *    （configure/run/flush/seam/end，t = 相对 tap 打开的毫秒）。抓一段就关，
 *    用 adb exec-out run-as <pkg> cat 取回（tap 是应用私有目录）。
 * 2) 应听到的内容（参考）：POST /debug/decode-range {path,aMs,bMs,tailFrames}，
 *    用设备**同一个解码器**从原始文件精确解出区间（PcmDecoder 内部按 PTS 对齐到
 *    ceil(startUs*sr/1e6) 帧，所以分片起点误差 < 1 帧），返回 tailPcmBase64。
 *    分片 1.2s + 0.4s 重叠，保证任意 300ms 窗口完整落在某个分片里。
 *
 * 判据（与 00027 追加要求一致）
 * ---------------------------
 *   peak >= 0.90  内容一致（听到的就是显示的）
 *   0.50~0.90     可疑（先排除对齐误差/降采样）
 *   < 0.50        音画不符 → FAIL
 * 另外叠加**位置一致性**：定位到的内容时间必须与 display 推算的期望值相差不超过
 * driftTolMs（默认 600ms，圆周差）。文档里 00027 的特征是「内容跑到 B 之外」，
 * 这一项会把「内容确实在环带里但位置偏了」也抓出来。
 *
 * 对齐手段（不改 app）
 * -------------------
 * tap 没有逐帧时间戳，所以用「窗口起点对应的 display」做锚：
 *   expectedContentMs = displayAt(窗口起点时刻) + offsetMs
 * 其中 offsetMs 是**基线校准**出来的常量（稳态下 = 写入超前播放的时长 + 采样相位，
 * 一般 100~500ms）。任何一次探测只要 offset 与基线一致，就说明内容与显示同步；
 * 00027 那种源被换掉/读指针不取模的情况，offset 会跳变数秒，同时峰值塌到 <0.5。
 * 复核结论：先证基线峰值 >= 0.95（判据不误杀），再拿它判别人。
 *
 * 用法
 * ----
 *   库：  createProber(cfg, adb, api, {workDir, log}) / prober.capture() / prober.probe()
 *   自检：bun run tools/scenarios/content-probe.ts --selftest
 *         （合成 PCM + 假 transport，离线验证 WAV 解析 / NCC / 分片 / 环带展开 /
 *           正常基线 / 00027 型错内容 / 象限偏移 / 采样率不一致）
 *
 * 本文件不改 app、不依赖 harness 之外的任何东西；场景侧见 scenarios-content.ts。
 */

import { spawnSync } from "node:child_process";
import { mkdirSync, writeFileSync } from "node:fs";
import * as os from "node:os";
import * as path from "node:path";
import type { Adb, Api, Cfg } from "./harness";

export const PROBE_VERSION = "content-probe/1";

// ---------------------------------------------------------------------------
// 基础类型
// ---------------------------------------------------------------------------

export type ContentVerdict = "match" | "suspect" | "mismatch" | "unavailable";

export interface HttpLike {
  status: number;
  json: any;
  text: string;
}

/** harness 的 Api 结构兼容（只用 post/get）。 */
export interface ProbeApi {
  post(apiPath: string, body?: any, timeoutMs?: number): Promise<HttpLike>;
  get(apiPath: string, timeoutMs?: number): Promise<HttpLike>;
}

export interface DisplaySample {
  atMs: number;
  displayMs: number | null;
  playing: boolean | null;
  engine: string | null;
  state: string | null;
}

export interface TapEvent {
  t: number;
  seq: number;
  ev: string;
  [key: string]: any;
}

export interface TapCapture {
  wavPath: string;
  jsonlPath: string | null;
  sampleRate: number;
  channels: number;
  bytesPerFrame: number;
  totalFrames: number;
  /** 窗口（末尾 samplesMs）单声道 PCM，已去直流前的原始幅度 [-1,1]。 */
  window: Float32Array;
  /**
   * 末尾 samplesMs + 700ms 的 PCM：多窗口搜索用（窗口跨接缝时换一个位置再比一次）。
   * tail 的最后 samplesMs 就是 window。
   */
  tail: Float32Array;
  tailStartFrame: number;
  windowStartFrame: number;
  windowEndFrame: number;
  windowMs: number;
  events: TapEvent[];
  seams: TapEvent[];
  flushCount: number;
  configureCount: number;
  displaySamples: DisplaySample[];
  /** 窗口起点时刻最接近的 display 采样（判据锚点）。 */
  displayRef: DisplaySample | null;
  displayAtStop: DisplaySample | null;
  displaySource: string;
  startedAtMs: number;
  stoppedAtMs: number;
  warnings: string[];
}

export interface PcmChunk {
  /** 该分片首样本对应的**内容时间**（毫秒，可能是环带坐标，也可能是展开后的坐标）。 */
  startMs: number;
  endMs: number;
  sampleRate: number;
  channels: number;
  frames: number;
  /** 单声道 [-1,1]。 */
  mono: Float32Array;
}

export interface WideMatch {
  region: "band" | "post-b" | "pre-a" | "file";
  /** 分片在各自坐标系的起点 + 峰值偏移。 */
  positionMs: number;
  peak: number;
}

export interface ProbeRequest {
  songPath: string;
  /** 环带 [aMs,bMs)。mode=file 时忽略。 */
  aMs: number;
  bMs: number;
  /** 期望的内容时间（窗口起点，绝对文件毫秒；band 模式内部按 L 展开）。 */
  expectedContentMs: number;
  /** band = 环回（内容折返到 [A,B)）；file = 自由试听（文件时间线性）。 */
  mode?: "band" | "file";
  /** 期望位置两侧各搜多少毫秒，默认 1200。 */
  searchHalfMs?: number;
  /** 峰值不足时是否做宽域定位（定位「实际听到的是哪一段」）。 */
  wide?: boolean;
  /** 位置一致性容差，默认 600ms。 */
  driftTolMs?: number;
  /** 证据标签。 */
  caption?: string;
}

export interface ProbeResult {
  caption: string;
  verdict: ContentVerdict;
  reason: string;
  peak: number;
  /** 定位到的内容时间（窗口起点，未折返的绝对内容毫秒）。 */
  locatedContentMs: number | null;
  /** 折返到 [A,B) 的展示位置（band 模式）。 */
  locatedBandMs: number | null;
  expectedContentMs: number;
  /** located - expected（band 模式取圆周差）。 */
  driftMs: number | null;
  prominence: number;
  chunks: number;
  /** 多窗口重试时选中的窗口前移量（0/250/500ms）；非 0 说明原窗口跨了接缝。 */
  windowShiftMs: number;
  wide: WideMatch[] | null;
  capture: {
    sampleRate: number;
    channels: number;
    totalFrames: number;
    windowMs: number;
    windowStartFrame: number;
    displayRefMs: number | null;
    displayAtStopMs: number | null;
    displaySource: string;
    seams: number;
    flushCount: number;
    configureCount: number;
    startedAtMs: number;
    stoppedAtMs: number;
    warnings: string[];
  };
  searched: { fromMs: number; toMs: number; mode: "band" | "file" };
}

export const PEAK_MATCH = 0.9;
export const PEAK_SUSPECT = 0.5;
export const DRIFT_TOL_MS = 600;

// ---------------------------------------------------------------------------
// 小工具
// ---------------------------------------------------------------------------

export function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function mod(n: number, m: number): number {
  if (m <= 0) return 0;
  return ((n % m) + m) % m;
}

/** 圆周差 b-a，落在 [-L/2, L/2)。 */
export function circularDelta(a: number, b: number, L: number): number {
  if (!(L > 0)) return a - b;
  return mod(a - b + L / 2, L) - L / 2;
}

export function bandFold(contentMs: number, aMs: number, bMs: number): number {
  const L = bMs - aMs;
  if (!(L > 0)) return contentMs;
  return aMs + mod(contentMs - aMs, L);
}

// ---------------------------------------------------------------------------
// WAV 解析（tap 文件：占位头 / 截断 / 任意声道 / pcm16 与 float32）
// ---------------------------------------------------------------------------

export interface WavInfo {
  sampleRate: number;
  channels: number;
  bitsPerSample: number;
  formatTag: number;
  dataOffset: number;
  dataBytes: number;
  declaredDataBytes: number;
  frames: number;
  bytesPerFrame: number;
  durationMs: number;
  warnings: string[];
}

export function parseWavBuffer(buf: Buffer): WavInfo {
  if (buf.length < 12) throw new Error("WAV 过短(不足 12 字节)");
  const riff = buf.toString("ascii", 0, 4);
  const wave = buf.toString("ascii", 8, 12);
  if (wave !== "WAVE") throw new Error("不是 WAVE 文件(magic=" + riff + "/" + wave + ")");
  const warnings: string[] = [];
  let fmt: any = null;
  let dataOffset = -1;
  let declared = -1;
  let pos = 12;
  let guard = 0;
  while (pos + 8 <= buf.length && guard++ < 64) {
    const id = buf.toString("ascii", pos, pos + 4);
    const size = buf.readUInt32LE(pos + 4);
    const body = pos + 8;
    if (id === "fmt " && fmt == null) {
      if (body + 16 > buf.length) throw new Error("fmt chunk 不完整");
      fmt = {
        formatTag: buf.readUInt16LE(body),
        channels: buf.readUInt16LE(body + 2),
        sampleRate: buf.readUInt32LE(body + 4),
        blockAlign: buf.readUInt16LE(body + 12),
        bitsPerSample: buf.readUInt16LE(body + 14),
        subFormatTag: body + 26 <= buf.length ? buf.readUInt32LE(body + 24) : null,
      };
    } else if (id === "data" && dataOffset < 0) {
      // 关键：tap 打开期间 data size 是 0（占位头）或被 1s 节流更新，按文件实际剩余处理。
      dataOffset = body;
      declared = size;
      const avail = Math.max(0, buf.length - body);
      if (size === 0 || size === 0xffffffff) {
        warnings.push("data 长度=" + (size === 0 ? "0" : "0xffffffff") + "(占位/流式)，按文件剩余 " + avail + " 字节解析");
      } else if (size > avail) {
        warnings.push("data 声明 " + size + " 字节，文件只剩 " + avail + "（头未回填）");
      }
    }
    if (dataOffset >= 0 && fmt) break;
    let advance = 8 + size;
    if (advance < 8) break;
    if (size % 2 === 1) advance += 1;
    pos += advance;
  }
  if (!fmt) throw new Error("WAV 缺少 fmt chunk");
  if (dataOffset < 0) throw new Error("WAV 缺少 data chunk");
  const channels = Math.max(1, fmt.channels | 0);
  const sampleRate = Number(fmt.sampleRate) | 0;
  if (!(sampleRate > 0)) throw new Error("WAV 采样率非法: " + fmt.sampleRate);
  const bits = fmt.bitsPerSample | 0;
  const formatTag = fmt.formatTag | 0;
  const effectiveTag = formatTag === 0xfffe && fmt.subFormatTag != null ? fmt.subFormatTag : formatTag;
  const bytesPerSample = Math.max(1, Math.ceil(bits / 8));
  const bytesPerFrame = bytesPerSample * channels;
  const avail = Math.max(0, buf.length - dataOffset);
  const dataBytes = declared > 0 && declared <= avail ? declared : avail;
  const frames = Math.floor(dataBytes / bytesPerFrame);
  return {
    sampleRate,
    channels,
    bitsPerSample: bits,
    formatTag: effectiveTag,
    dataOffset,
    dataBytes,
    declaredDataBytes: declared,
    frames,
    bytesPerFrame,
    durationMs: (frames * 1000) / sampleRate,
    warnings,
  };
}

/** 取 [fromFrame, fromFrame+frames) 区间，下混单声道 float。 */
export function readWavMono(buf: Buffer, info: WavInfo, fromFrame: number, frames: number): Float32Array {
  const start = Math.max(0, Math.min(info.frames, Math.floor(fromFrame)));
  const count = Math.max(0, Math.min(info.frames - start, Math.floor(frames)));
  const out = new Float32Array(count);
  const bpf = info.bytesPerFrame;
  const ch = info.channels;
  const isFloat = info.formatTag === 3;
  const bits = info.bitsPerSample;
  const base = info.dataOffset + start * bpf;
  for (let f = 0; f < count; f++) {
    let sum = 0;
    const off = base + f * bpf;
    for (let c = 0; c < ch; c++) {
      const p = off + c * (bpf / ch);
      let v = 0;
      if (isFloat && bits === 32) {
        v = buf.readFloatLE(p);
      } else if (bits === 16) {
        v = buf.readInt16LE(p) / 32768;
      } else if (bits === 8) {
        v = (buf.readUInt8(p) - 128) / 128;
      } else if (bits === 24) {
        const b0 = buf.readUInt8(p);
        const b1 = buf.readUInt8(p + 1);
        const b2 = buf.readInt8(p + 2);
        v = ((b2 << 16) | (b1 << 8) | b0) / 8388608;
      } else if (bits === 32) {
        v = buf.readInt32LE(p) / 2147483648;
      } else {
        throw new Error("不支持的 WAV 位深: " + bits);
      }
      sum += v;
    }
    out[f] = sum / ch;
  }
  return out;
}

// ---------------------------------------------------------------------------
// 信号处理：抽取 / 重采样 / NCC
// ---------------------------------------------------------------------------

export function meanOf(x: Float32Array): number {
  if (x.length === 0) return 0;
  let s = 0;
  for (let i = 0; i < x.length; i++) s += x[i];
  return s / x.length;
}

/** 零均值化（返回新数组）。 */
export function removeMean(x: Float32Array): Float32Array {
  const m = meanOf(x);
  const out = new Float32Array(x.length);
  for (let i = 0; i < x.length; i++) out[i] = x[i] - m;
  return out;
}

/** 盒平均抽取（只用于粗搜索，混叠不影响峰值定位）。 */
export function decimate(x: Float32Array, factor: number): Float32Array {
  const f = Math.max(1, Math.floor(factor));
  if (f === 1) return x;
  const n = Math.floor(x.length / f);
  const out = new Float32Array(n);
  for (let i = 0; i < n; i++) {
    let s = 0;
    for (let k = 0; k < f; k++) s += x[i * f + k];
    out[i] = s / f;
  }
  return out;
}

/** 线性插值重采样（采样率不一致时兜底；正常情况下 tap 与 decode-range 同源同率）。 */
export function resampleLinear(x: Float32Array, fromRate: number, toRate: number): Float32Array {
  if (!(fromRate > 0) || !(toRate > 0) || fromRate === toRate) return x;
  const ratio = fromRate / toRate;
  const n = Math.max(1, Math.floor(x.length / ratio));
  const out = new Float32Array(n);
  for (let i = 0; i < n; i++) {
    const src = i * ratio;
    const i0 = Math.floor(src);
    const i1 = Math.min(x.length - 1, i0 + 1);
    const frac = src - i0;
    out[i] = x[i0] * (1 - frac) + x[i1] * frac;
  }
  return out;
}

export interface NccMatch {
  /** 归一化互相关系数峰值，[-1,1]。 */
  peak: number;
  /** 窗口首样本在参考中的帧偏移。 */
  lagFrames: number;
  /** 峰值相对次峰的突出度（越大越确定；纯音会有多个峰）。 */
  prominence: number;
}

const EMPTY_NCC: NccMatch = { peak: 0, lagFrames: 0, prominence: 0 };

/**
 * 单窗口 vs 参考的归一化互相关（mean-removed NCC）。
 *
 * 先按 sr/6000 抽取做 O(n·m) 粗搜索，再在粗峰 ±(factor+2) 帧内做全采样率精搜；
 * 这样 2.4s 参考 × 300ms 窗口在 PC 上只要几十毫秒。
 */
export function nccBest(window: Float32Array, ref: Float32Array, sampleRate: number): NccMatch {
  const n = window.length;
  if (n < 64 || ref.length < n + 8) return EMPTY_NCC;
  const w = removeMean(window);
  let wVar = 0;
  for (let i = 0; i < n; i++) wVar += w[i] * w[i];
  if (!(wVar > 1e-9)) return EMPTY_NCC;

  const factor = Math.max(1, Math.round(sampleRate / 6000));
  const wd = decimate(w, factor);
  const rdRaw = decimate(ref, factor);
  const nd = wd.length;
  if (rdRaw.length < nd + 2) return EMPTY_NCC;
  let wdVar = 0;
  let wdSum = 0;
  for (let i = 0; i < nd; i++) {
    wdSum += wd[i];
    wdVar += wd[i] * wd[i];
  }
  // 抽取后窗口去掉自己的均值，使 Σwd=0（后续 num 就等于协方差）。
  const wdMean = wdSum / nd;
  for (let i = 0; i < nd; i++) wd[i] -= wdMean;
  wdVar = 0;
  for (let i = 0; i < nd; i++) wdVar += wd[i] * wd[i];
  if (!(wdVar > 1e-9)) return EMPTY_NCC;

  const m = rdRaw.length;
  const preSum = new Float64Array(m + 1);
  const preSq = new Float64Array(m + 1);
  for (let i = 0; i < m; i++) {
    preSum[i + 1] = preSum[i] + rdRaw[i];
    preSq[i + 1] = preSq[i] + rdRaw[i] * rdRaw[i];
  }
  const nLags = m - nd;
  let best = -Infinity;
  let bestLag = 0;
  let second = -Infinity;
  let secondLag = -1;
  const sep = Math.max(2, factor);
  for (let lag = 0; lag <= nLags; lag++) {
    let num = 0;
    const end = lag + nd;
    for (let i = 0, j = lag; i < nd; i++, j++) num += wd[i] * rdRaw[j];
    const s = preSum[end] - preSum[lag];
    const sq = preSq[end] - preSq[lag];
    const varR = sq - (s * s) / nd;
    if (!(varR > 1e-12)) continue;
    const v = num / Math.sqrt(wdVar * varR);
    if (v > best) {
      if (secondLag < 0 || Math.abs(lag - bestLag) > sep) {
        second = best;
        secondLag = bestLag;
      }
      best = v;
      bestLag = lag;
    } else if (v > second && Math.abs(lag - bestLag) > sep) {
      second = v;
      secondLag = lag;
    }
  }
  if (!Number.isFinite(best)) return EMPTY_NCC;

  // 全采样率精搜
  const center = bestLag * factor;
  const lo = Math.max(0, center - factor - 2);
  const hi = Math.min(ref.length - n, center + factor + 2);
  let fineBest = -Infinity;
  let fineLag = center;
  for (let lag = lo; lag <= hi; lag++) {
    const v = nccAt(w, ref, lag, wVar);
    if (v > fineBest) {
      fineBest = v;
      fineLag = lag;
    }
  }
  if (!Number.isFinite(fineBest) || fineBest < best - 0.02) {
    // 抽取域更好（抽取会平滑，极端情况下可比全采样略高）：退回抽取峰。
    fineLag = Math.min(Math.max(center, 0), ref.length - n);
    fineBest = nccAt(w, ref, fineLag, wVar);
  }
  return {
    peak: fineBest,
    lagFrames: fineLag,
    prominence: Number.isFinite(second) ? fineBest - second : 1,
  };
}

function nccAt(wZeroMean: Float32Array, ref: Float32Array, lag: number, wVar: number): number {
  const n = wZeroMean.length;
  if (lag < 0 || lag + n > ref.length) return -Infinity;
  let num = 0;
  let s = 0;
  let sq = 0;
  for (let i = 0; i < n; i++) {
    const r = ref[lag + i];
    num += wZeroMean[i] * r;
    s += r;
    sq += r * r;
  }
  const varR = sq - (s * s) / n;
  if (!(varR > 1e-12)) return -Infinity;
  return num / Math.sqrt(wVar * varR);
}

// ---------------------------------------------------------------------------
// 设备参考解码（/debug/decode-range，分片 + 重叠）
// ---------------------------------------------------------------------------

export interface DecodeRangeResult {
  sampleRate: number;
  channels: number;
  frames: number;
  bytes: number;
  mono: Float32Array;
  elapsedMs: number;
}

const MAX_CHUNK_FRAMES = 60000; // tailFrames 上限 65536，留余量

function pcm16InterleavedToMono(bytes: Buffer, channels: number): Float32Array {
  const ch = Math.max(1, channels | 0);
  const frames = Math.floor(bytes.length / (2 * ch));
  const out = new Float32Array(frames);
  let p = 0;
  for (let f = 0; f < frames; f++) {
    let s = 0;
    for (let c = 0; c < ch; c++) {
      s += bytes.readInt16LE(p);
      p += 2;
    }
    out[f] = s / ch / 32768;
  }
  return out;
}

/** 解一个区间（区间长度必须 <= 1.36s，否则调用方先切）。 */
export async function decodeRangeOnce(
  api: ProbeApi,
  songPath: string,
  aMs: number,
  bMs: number,
  timeoutMs = 30000,
): Promise<DecodeRangeResult> {
  const body = { path: songPath, aMs: Math.round(aMs), bMs: Math.round(bMs), tailFrames: 65536 };
  const r = await api.post("/debug/decode-range", body, timeoutMs);
  if (r.status === 404) throw new Error("当前 build 没有 POST /debug/decode-range");
  if (r.status !== 200 || !r.json) throw new Error("/debug/decode-range -> " + r.status + " " + String(r.text).slice(0, 200));
  const j = r.json;
  if (!j.tailPcmBase64) throw new Error("decode-range 没返回 tailPcmBase64(区间太长? 需 <=1.36s)");
  const bytes: Buffer = Buffer.from(String(j.tailPcmBase64), "base64");
  const sr = Number(j.sampleRate) || 0;
  const ch = Number(j.channels) || 0;
  const frames = Number(j.frames) || 0;
  if (!(sr > 0) || !(ch > 0)) throw new Error("decode-range 返回的格式非法: sr=" + sr + " ch=" + ch);
  if (bytes.length !== Number(j.bytes)) {
    throw new Error(
      "decode-range tail 被截断: tail=" + bytes.length + "B, 区间=" + j.bytes + "B（区间过长，请分片）",
    );
  }
  return {
    sampleRate: sr,
    channels: ch,
    frames,
    bytes: bytes.length,
    mono: pcm16InterleavedToMono(bytes, ch),
    elapsedMs: Number(j.elapsedMs ?? 0),
  };
}

/** 分片解码：chunkMs=1200，overlapMs=400（保证任意 300ms 窗口完整落在某片里）。 */
export async function collectChunks(
  api: ProbeApi,
  songPath: string,
  fromMs: number,
  toMs: number,
  opts: {
    chunkMs?: number;
    overlapMs?: number;
    maxChunks?: number;
    /** true = 单片刻解码失败(常见于越过文件尾)时停止而不是抛。 */
    tolerant?: boolean;
    log?: (m: string) => void;
  } = {},
): Promise<PcmChunk[]> {
  const chunkMs = Math.max(300, Math.min(1300, opts.chunkMs ?? 1200));
  const overlapMs = Math.max(0, Math.min(chunkMs - 200, opts.overlapMs ?? 400));
  const stride = Math.max(150, chunkMs - overlapMs);
  const maxChunks = Math.max(1, opts.maxChunks ?? 64);
  const chunks: PcmChunk[] = [];
  const start = Math.max(0, Math.floor(fromMs));
  const end = Math.max(start, Math.ceil(toMs));
  let cursor = start;
  let guard = 0;
  while (cursor < end && chunks.length < maxChunks && guard++ < 512) {
    const a = cursor;
    // 不越过区间：区间外(尤其 B 之后的文件内容)绝不能混进参考，否则会把 00027 误判成 match。
    const b = Math.min(a + chunkMs, end);
    if (b - a < 200) break;
    try {
      const dec = await decodeRangeOnce(api, songPath, a, b);
      chunks.push({
        startMs: a,
        endMs: b,
        sampleRate: dec.sampleRate,
        channels: dec.channels,
        frames: dec.frames,
        mono: dec.mono,
      });
      opts.log?.("    decode-range " + a + ".." + b + "ms -> " + dec.mono.length + "f in " + dec.elapsedMs + "ms");
    } catch (e: any) {
      opts.log?.("    decode-range " + a + ".." + b + "ms 失败: " + (e?.message ?? e));
      if (!opts.tolerant) throw e;
      break;
    }
    if (b >= end) break;
    cursor += stride;
  }
  return chunks;
}

// ---------------------------------------------------------------------------
// tap 抓取
// ---------------------------------------------------------------------------

export interface CaptureOptions {
  target: "preview" | "main";
  /** 取末尾多长的一段做内容比对，默认 300ms。 */
  samplesMs?: number;
  /** tap 开多久，默认 900ms（必须 > samplesMs，留出发声/写入的稳定期）。 */
  dwellMs?: number;
  /** 采样 display 的间隔，默认 60ms。 */
  sampleIntervalMs?: number;
  /** tap 文件名（设备 filesDir 下）。 */
  remoteName?: string;
  /** 证据目录。 */
  workDir: string;
  label?: string;
}

export interface ProbeDeps {
  api: ProbeApi;
  /** 从应用私有目录取文件（默认 adb exec-out run-as cat）。null = 不存在。 */
  pull: (remoteName: string) => Promise<Buffer | null>;
  log?: (message: string) => void;
}

export function makeAdbPull(cfg: Cfg): (remoteName: string) => Promise<Buffer | null> {
  return async (remoteName: string) => {
    const r = spawnSync(cfg.adbExe, ["-s", cfg.device, "exec-out", "run-as", cfg.pkg, "cat", remoteName], {
      maxBuffer: 128 * 1024 * 1024,
      windowsHide: true,
    });
    if (r.status !== 0) return null;
    const out = r.stdout;
    if (!out || out.length === 0) return null;
    return Buffer.isBuffer(out) ? out : Buffer.from(out);
  };
}

/**
 * 挑一个不跨接缝的 tap 窗口起点。
 *
 * tap 里的 PCM 是**连续写入**的：环回那一帧之后立刻是 A 的内容。若 300ms 窗口正好跨过
 * 接缝，窗口里就是「B 末尾 + A 开头」，而参考是按窗口起点切的一段连续内容 —— 峰值会掉到
 * 0.5~0.9 的 suspect 带里（离线干跑实测 0.789），造成误判。
 * 这里把窗口挪到接缝之后（优先）或之前，并让调用方据此重算「窗口起点的 display」。
 */
export function chooseWindowStart(
  totalFrames: number,
  wanted: number,
  seamFrames: number[],
  marginFrames = 60,
): { startFrame: number; avoidedSeam: number | null } {
  const lastStart = totalFrames - wanted;
  if (lastStart <= 0) return { startFrame: 0, avoidedSeam: null };
  const hit = seamFrames
    .filter((x) => Number.isFinite(x) && x > 0)
    .find((x) => x > lastStart - marginFrames && x < totalFrames + marginFrames);
  if (hit == null) return { startFrame: lastStart, avoidedSeam: null };
  // 接缝在末尾窗口内 -> 窗口整体挪到接缝之前（接缝之后放不下一个完整窗口）。
  const beforeStart = Math.floor(hit - marginFrames - wanted);
  if (beforeStart >= 0) return { startFrame: beforeStart, avoidedSeam: hit };
  // 接缝太靠前（窗口前放不下一个完整窗口）：只能接受跨接缝，由调用方告警。
  return { startFrame: lastStart, avoidedSeam: hit };
}

export class ContentProber {
  private seq = 0;

  constructor(private deps: ProbeDeps) {}

  private log(message: string): void {
    this.deps.log?.(message);
  }

  private async sampleDisplay(target: "preview" | "main"): Promise<DisplaySample> {
    const atMs = Date.now();
    if (target === "preview") {
      const r = await this.deps.api.get("/debug/preview", 6000).catch(() => null);
      const snap = r && r.status === 200 ? r.json?.snapshot : null;
      if (!snap) {
        return { atMs, displayMs: null, playing: null, engine: null, state: null };
      }
      const display = snap.liveDisplayMs ?? snap.displayMs;
      return {
        atMs,
        displayMs: display == null ? null : Number(display),
        playing: snap.isPlaying == null ? null : Boolean(snap.isPlaying),
        engine: snap.engine == null ? null : String(snap.engine),
        state: snap.playbackStateName == null ? null : String(snap.playbackStateName),
      };
    }
    const r = await this.deps.api.get("/state", 8000).catch(() => null);
    const pb = r && r.status === 200 ? r.json?.playback : null;
    if (!pb) return { atMs, displayMs: null, playing: null, engine: null, state: null };
    return {
      atMs,
      displayMs: pb.positionMs == null ? null : Number(pb.positionMs),
      playing: pb.playing == null ? null : Boolean(pb.playing),
      engine: pb.engine == null ? null : String(pb.engine),
      state: pb.playbackState == null ? null : String(pb.playbackState),
    };
  }

  /**
   * 抓一段 tap：开 tap → 边采 display → 关 tap → 取回 WAV/JSONL → 切末尾窗口。
   *
   * 关 tap 时会回填 WAV 头（LoopAudioController.closeTapLocked），所以取回的是完整文件。
   */
  async capture(opts: CaptureOptions): Promise<TapCapture> {
    const samplesMs = Math.max(100, Math.min(1000, opts.samplesMs ?? 300));
    const dwellMs = Math.max(samplesMs + 250, opts.dwellMs ?? Math.min(1200, samplesMs + 500));
    const intervalMs = Math.max(30, opts.sampleIntervalMs ?? 60);
    const remoteName = opts.remoteName ?? "content-tap.wav";
    const localName = (opts.label ?? "probe") + "-" + String(++this.seq).padStart(2, "0");
    const dir = path.join(opts.workDir, localName);
    mkdirSync(dir, { recursive: true });
    const wavPath = path.join(dir, remoteName);
    const jsonlPath = path.join(dir, remoteName + ".jsonl");
    const warnings: string[] = [];

    // 先确保关闭，避免同一文件被 setPcmTap 早退（tapFile == file 时会直接 return，不截断）。
    await this.deps.api.post("/debug/tap", {}, 8000).catch(() => null);
    const t0 = Date.now();
    const started = await this.deps.api.post("/debug/tap", { path: remoteName }, 8000);
    if (started.status === 404) throw new Error("当前 build 没有 POST /debug/tap");
    if (started.status !== 200) throw new Error("/debug/tap -> " + started.status + " " + started.text.slice(0, 200));
    if (started.json?.tapping !== true) {
      throw new Error("/debug/tap 没有真正打开(tapping=" + JSON.stringify(started.json?.tapping) + ")");
    }

    const displaySamples: DisplaySample[] = [];
    const first = await this.sampleDisplay(opts.target);
    displaySamples.push(first);
    const sampler = (async () => {
      while (true) {
        const elapsed = Date.now() - t0;
        if (elapsed >= dwellMs) break;
        await sleep(intervalMs);
        displaySamples.push(await this.sampleDisplay(opts.target));
      }
    })();
    await sampler;
    const t1 = Date.now();
    const stopped = await this.deps.api.post("/debug/tap", {}, 8000);
    if (stopped.status !== 200) warnings.push("/debug/tap 关闭返回 " + stopped.status + "（文件可能没有回填头）");

    const wavBuf = await this.deps.pull("files/" + remoteName);
    if (!wavBuf) throw new Error("tap WAV 取不回来(run-as 失败或文件不存在): files/" + remoteName);
    writeFileSync(wavPath, wavBuf);
    const jsonlBuf = await this.deps.pull("files/" + remoteName + ".jsonl");
    if (jsonlBuf) writeFileSync(jsonlPath, jsonlBuf);

    const info = parseWavBuffer(wavBuf);
    warnings.push(...info.warnings);
    const wanted = Math.round((samplesMs * info.sampleRate) / 1000);
    if (info.frames < wanted) {
      throw new Error(
        "tap 只写出 " + info.frames + " 帧(< 需要的 " + wanted + " 帧/" + samplesMs + "ms)：引擎没走 SOURCE 路线或处于暂停",
      );
    }
    const events: TapEvent[] = [];
    if (jsonlBuf) {
      for (const line of jsonlBuf.toString("utf8").split(/\r?\n/)) {
        const trimmed = line.trim();
        if (!trimmed.startsWith("{")) continue;
        try {
          events.push(JSON.parse(trimmed) as TapEvent);
        } catch {
          warnings.push("tap jsonl 有一行解析失败: " + trimmed.slice(0, 120));
        }
      }
    } else {
      warnings.push("没有 tap 侧车 jsonl(拿不到 seam/flush 事件)");
    }
    const seams = events.filter((e) => e.ev === "seam");
    const seamFrames = seams.map((e) => Number(e.outFrames)).filter((x) => Number.isFinite(x));
    const chosen = chooseWindowStart(info.frames, wanted, seamFrames);
    const windowStartFrame = chosen.startFrame;
    if (chosen.avoidedSeam != null) {
      warnings.push("窗口跨接缝(outFrames=" + chosen.avoidedSeam + ")，已挪到 startFrame=" + windowStartFrame);
    }
    const window = readWavMono(wavBuf, info, windowStartFrame, wanted);
    const tailWanted = Math.min(info.frames, wanted + Math.round((0.7 * info.sampleRate)));
    const tailStartFrame = Math.max(0, info.frames - tailWanted);
    const tail = readWavMono(wavBuf, info, tailStartFrame, tailWanted);

    const flushCount = events.filter((e) => e.ev === "flush").length;
    const configureCount = events.filter((e) => e.ev === "configure").length;
    if (configureCount > 1) {
      warnings.push("tap 期间 configure 出现 " + configureCount + " 次（两个引擎同时写同一文件？结果可能不可信）");
    }

    // 窗口起点对应的墙钟：tap 帧按 1x 连续写入，所以「还剩多少帧」≈「距今多少毫秒」。
    const windowStartAtMs = t1 - ((info.frames - windowStartFrame) * 1000) / info.sampleRate;
    const displayRef = nearestDisplay(displaySamples, windowStartAtMs);
    const displayAtStop = nearestDisplay(displaySamples, t1);
    return {
      wavPath,
      jsonlPath: jsonlBuf ? jsonlPath : null,
      sampleRate: info.sampleRate,
      channels: info.channels,
      bytesPerFrame: info.bytesPerFrame,
      totalFrames: info.frames,
      window,
      tail,
      tailStartFrame,
      windowStartFrame,
      windowEndFrame: info.frames,
      windowMs: (wanted * 1000) / info.sampleRate,
      events,
      seams,
      flushCount,
      configureCount,
      displaySamples,
      displayRef,
      displayAtStop,
      displaySource: opts.target === "preview" ? "preview.liveDisplayMs" : "state.playback.positionMs",
      startedAtMs: t0,
      stoppedAtMs: t1,
      warnings,
    };
  }
}

function nearestDisplay(samples: DisplaySample[], atMs: number): DisplaySample | null {
  let best: DisplaySample | null = null;
  let bestDiff = Infinity;
  for (const s of samples) {
    if (s.displayMs == null) continue;
    const d = Math.abs(s.atMs - atMs);
    if (d < bestDiff) {
      bestDiff = d;
      best = s;
    }
  }
  return best;
}

// ---------------------------------------------------------------------------
// 内容定位：在「展开的内容时间」上搜窗口
// ---------------------------------------------------------------------------

/**
 * 把展开的内容窗口 [fromU,toU) 映射成若干段环带内的连续区间。
 * 例：A=10s,B=40s(true L=30s)，窗口 [39.5s,41.5s) -> [ [39.5s,40s), [10s,11.5s) ]。
 */
export function unwrapToBandRanges(
  fromU: number,
  toU: number,
  aMs: number,
  bMs: number,
): { aMs: number; bMs: number }[] {
  const L = bMs - aMs;
  if (!(L > 0)) return [{ aMs: fromU, bMs: toU }];
  const out: { aMs: number; bMs: number }[] = [];
  const k0 = Math.floor((fromU - aMs) / L);
  const k1 = Math.floor((toU - aMs) / L);
  for (let k = k0; k <= k1; k++) {
    const segStart = Math.max(fromU, aMs + k * L);
    const segEnd = Math.min(toU, aMs + (k + 1) * L);
    if (segEnd - segStart < 1) continue;
    out.push({ aMs: aMs + (segStart - (aMs + k * L)), bMs: aMs + (segEnd - (aMs + k * L)) });
  }
  return out;
}

interface Located {
  peak: number;
  prominence: number;
  contentMs: number | null;
  chunks: number;
}

function searchChunks(
  chunks: PcmChunk[],
  window: Float32Array,
  windowRate: number,
  mode: "band" | "file",
  aMs: number,
  bMs: number,
  anchorU: number,
): Located {
  let best: NccMatch | null = null;
  let bestStart = 0;
  let bestRate = windowRate;
  const L = bMs - aMs;
  for (const chunk of chunks) {
    const w = Math.abs(chunk.sampleRate - windowRate) > 1 ? resampleLinear(window, windowRate, chunk.sampleRate) : window;
    const m = nccBest(w, chunk.mono, chunk.sampleRate);
    if (!best || m.peak > best.peak) {
      best = m;
      bestStart = chunk.startMs;
      bestRate = chunk.sampleRate;
    }
  }
  if (!best) return { peak: 0, prominence: 0, contentMs: null, chunks: chunks.length };
  const startMs = bestStart + (best.lagFrames * 1000) / bestRate;
  let contentMs = startMs;
  if (mode === "band" && L > 0) {
    // 环带坐标 -> 展开坐标：取离锚点最近的那个代表。
    const k = Math.round((anchorU - startMs) / L);
    contentMs = startMs + k * L;
  }
  return { peak: best.peak, prominence: best.prominence, contentMs, chunks: chunks.length };
}

// ---------------------------------------------------------------------------
// ContentProber.probe：主判据
// ---------------------------------------------------------------------------

export interface ProbeOptions {
  target?: "preview" | "main";
  samplesMs?: number;
  dwellMs?: number;
  workDir: string;
  label?: string;
  remoteName?: string;
}

export class ContentTruth {
  constructor(private deps: ProbeDeps, private prober: ContentProber) {}

  /** 抓一段 tap 并就给定期望位置做内容比对。 */
  async probe(req: ProbeRequest, captureOpts: ProbeOptions): Promise<ProbeResult> {
    const mode = req.mode ?? "band";
    const half = Math.max(300, req.searchHalfMs ?? 1200);
    const tol = req.driftTolMs ?? DRIFT_TOL_MS;
    const capture = await this.prober.capture({
      target: captureOpts.target ?? "preview",
      samplesMs: captureOpts.samplesMs,
      dwellMs: captureOpts.dwellMs,
      workDir: captureOpts.workDir,
      label: captureOpts.label,
      remoteName: captureOpts.remoteName,
    });
    return this.probeCapture(capture, req, { mode, half, tol });
  }

  async probeCapture(
    capture: TapCapture,
    req: ProbeRequest,
    extra?: { mode?: "band" | "file"; half?: number; tol?: number },
  ): Promise<ProbeResult> {
    const mode = extra?.mode ?? req.mode ?? "band";
    let half = extra?.half ?? Math.max(300, req.searchHalfMs ?? 1200);
    const tol = extra?.tol ?? req.driftTolMs ?? DRIFT_TOL_MS;
    const aMs = req.aMs;
    const bMs = req.bMs;
    const L = bMs - aMs;
    // 环带比搜索窗口还短时，展开坐标会多解（内容按 L 周期重复）；把窗口收窄到 L/3。
    if (mode === "band" && L > 0 && L < 3 * half) half = Math.max(250, L / 3);
    const expected = req.expectedContentMs;
    const base = {
      caption: req.caption ?? "",
      expectedContentMs: expected,
      capture: {
        sampleRate: capture.sampleRate,
        channels: capture.channels,
        totalFrames: capture.totalFrames,
        windowMs: Math.round(capture.windowMs),
        windowStartFrame: capture.windowStartFrame,
        displayRefMs: capture.displayRef?.displayMs ?? null,
        displayAtStopMs: capture.displayAtStop?.displayMs ?? null,
        displaySource: capture.displaySource,
        seams: capture.seams.length,
        flushCount: capture.flushCount,
        configureCount: capture.configureCount,
        startedAtMs: capture.startedAtMs,
        stoppedAtMs: capture.stoppedAtMs,
        warnings: capture.warnings,
      },
    };

    const chunks = await this.collectWindowChunks(req.songPath, expected, half, mode, aMs, bMs);
    if (chunks.length === 0) {
      return {
        ...base,
        verdict: "unavailable",
        reason: "没有可用的参考 PCM（decode-range 失败）",
        peak: 0,
        locatedContentMs: null,
        locatedBandMs: null,
        driftMs: null,
        prominence: 0,
        chunks: 0,
        windowShiftMs: 0,
        wide: null,
        searched: { fromMs: expected - half, toMs: expected + half, mode },
      };
    }
    // 多窗口搜索：窗口若正好跨接缝（tap 里 B 之后立刻是 A），单窗口峰值会掉进 suspect 带；
    // 把窗口沿 tap 尾巴往前挪 250/500ms 再比一次（期望位置同步前移），取峰值最高的那个。
    const shiftsMs = [0, 250, 500];
    let best: { located: Located; shiftMs: number } | null = null;
    for (const shiftMs of shiftsMs) {
      const shiftFrames = Math.round((shiftMs * capture.sampleRate) / 1000);
      const need = capture.window.length + shiftFrames;
      if (capture.tail.length < need) continue;
      const win = capture.tail.subarray(capture.tail.length - need, capture.tail.length - shiftFrames);
      const loc = searchChunks(chunks, win, capture.sampleRate, mode, aMs, bMs, expected - shiftMs);
      if (!best || loc.peak > best.located.peak + 1e-6) best = { located: loc, shiftMs };
    }
    if (!best) best = { located: searchChunks(chunks, capture.window, capture.sampleRate, mode, aMs, bMs, expected), shiftMs: 0 };
    const raw = best.located;
    // 换窗口会让定位结果整体前移 shiftMs：加回去，统一按「原窗口起点」的口径报位置。
    const located: Located = {
      ...raw,
      contentMs: raw.contentMs == null ? null : raw.contentMs + best.shiftMs,
    };
    const drift =
      located.contentMs == null
        ? null
        : mode === "band" && L > 0
          ? circularDelta(located.contentMs, expected, L)
          : located.contentMs - expected;
    const locatedBand = located.contentMs == null ? null : mode === "band" ? bandFold(located.contentMs, aMs, bMs) : null;

    let verdict: ContentVerdict;
    let reason: string;
    if (located.peak < PEAK_SUSPECT) {
      verdict = "mismatch";
      reason = "峰值 " + located.peak.toFixed(3) + " < " + PEAK_SUSPECT + "：这段音频在期望位置附近找不到（音画不符）";
    } else if (located.peak < PEAK_MATCH) {
      verdict = "suspect";
      reason = "峰值 " + located.peak.toFixed(3) + " 落在 [" + PEAK_SUSPECT + "," + PEAK_MATCH + ")：可疑，先排除对齐/音量差";
    } else if (drift != null && Math.abs(drift) > tol) {
      verdict = "suspect";
      reason =
        "峰值 " + located.peak.toFixed(3) + " 但位置偏 " + Math.round(drift) + "ms（容差 " + tol + "ms）：内容在环带内但不在显示位置";
    } else {
      verdict = "match";
      reason = "峰值 " + located.peak.toFixed(3) + "，位置偏差 " + (drift == null ? "n/a" : Math.round(drift) + "ms");
    }

    let wide: WideMatch[] | null = null;
    if (req.wide && located.peak < PEAK_MATCH) {
      wide = await this.wideLocate(req.songPath, capture, aMs, bMs);
      if (wide.length > 0) {
        const top = wide[0];
        reason += "；实际最像 " + top.region + " @" + Math.round(top.positionMs) + "ms(peak " + top.peak.toFixed(3) + ")";
      }
    }
    return {
      ...base,
      verdict,
      reason,
      peak: located.peak,
      locatedContentMs: located.contentMs,
      locatedBandMs: locatedBand,
      driftMs: drift,
      prominence: located.prominence,
      chunks: located.chunks,
      windowShiftMs: best.shiftMs,
      wide,
      searched: { fromMs: expected - half, toMs: expected + half, mode },
    };
  }

  private async collectWindowChunks(
    songPath: string,
    expected: number,
    half: number,
    mode: "band" | "file",
    aMs: number,
    bMs: number,
  ): Promise<PcmChunk[]> {
    if (mode === "file") {
      return collectChunks(this.deps.api, songPath, expected - half, expected + half, { log: this.deps.log });
    }
    // band：把展开窗口映射成环带内的连续区间（可能跨 B->A 边界，拆成两段），
    // 逐段解码；searchChunks 再把分片起点按 L 换回展开坐标。
    const ranges = unwrapToBandRanges(expected - half, expected + half, aMs, bMs);
    const out: PcmChunk[] = [];
    for (const r of ranges) {
      const chunks = await collectChunks(this.deps.api, songPath, r.aMs, r.bMs, { log: this.deps.log });
      for (const c of chunks) out.push(c);
    }
    return out;
  }

  /** 宽域定位：在环带、B 之后、A 之前分别搜一遍，回答「实际听到的是哪一段」。 */
  async wideLocate(
    songPath: string,
    capture: TapCapture,
    aMs: number,
    bMs: number,
    opts: { postMs?: number; preMs?: number; maxChunks?: number } = {},
  ): Promise<WideMatch[]> {
    const postMs = opts.postMs ?? 30000;
    const preMs = opts.preMs ?? 15000;
    const maxChunks = opts.maxChunks ?? 64;
    const regions: { region: WideMatch["region"]; from: number; to: number }[] = [];
    if (bMs - aMs <= 120000) regions.push({ region: "band", from: aMs, to: bMs });
    if (postMs > 0) regions.push({ region: "post-b", from: bMs, to: bMs + postMs });
    if (aMs > 2000) regions.push({ region: "pre-a", from: Math.max(0, aMs - preMs), to: aMs });
    const out: WideMatch[] = [];
    for (const r of regions) {
      try {
        const chunks = await collectChunks(this.deps.api, songPath, r.from, r.to, {
          maxChunks,
          tolerant: true,
          log: this.deps.log,
        });
        let best: WideMatch | null = null;
        for (const c of chunks) {
          const w =
            Math.abs(c.sampleRate - capture.sampleRate) > 1
              ? resampleLinear(capture.window, capture.sampleRate, c.sampleRate)
              : capture.window;
          const m = nccBest(w, c.mono, c.sampleRate);
          const pos = c.startMs + (m.lagFrames * 1000) / c.sampleRate;
          if (!best || m.peak > best.peak) best = { region: r.region, positionMs: pos, peak: m.peak };
        }
        if (best) out.push(best);
      } catch (e: any) {
        this.deps.log?.("    wide-locate " + r.region + " 失败: " + (e?.message ?? e));
      }
    }
    out.sort((x, y) => y.peak - x.peak);
    return out;
  }
}

// ---------------------------------------------------------------------------
// 工厂
// ---------------------------------------------------------------------------

export interface ProbeStack {
  prober: ContentProber;
  truth: ContentTruth;
  deps: ProbeDeps;
}

/**
 * 建一套探针：
 * - pull 默认走 `adb exec-out run-as <pkg> cat files/<name>`（tap 落在应用私有目录）；
 * - workDir 放证据（每次抓取一个子目录：WAV + JSONL）。
 */
export function createProber(
  cfg: Cfg,
  api: ProbeApi,
  opts: { workDir: string; log?: (message: string) => void; pull?: (name: string) => Promise<Buffer | null> },
): ProbeStack {
  const deps: ProbeDeps = {
    api,
    pull: opts.pull ?? makeAdbPull(cfg),
    log: opts.log,
  };
  const prober = new ContentProber(deps);
  const truth = new ContentTruth(deps, prober);
  return { prober, truth, deps };
}

/** 默认证据目录：<outDir>/content-tap（run-scenarios 的产物根下，便于和报告一起归档）。 */
export function defaultWorkDir(cfg: Cfg): string {
  return path.join(cfg.outDir, "content-tap");
}

// ---------------------------------------------------------------------------
// 自检（离线）：合成 PCM + 假 transport，不需要设备
// ---------------------------------------------------------------------------

export function buildWav(pcm: Int16Array, sampleRate: number, channels: number, declaredBytes?: number): Buffer {
  const dataBytes = pcm.length * 2;
  const header = Buffer.alloc(44);
  header.write("RIFF", 0, "ascii");
  header.writeUInt32LE(36 + dataBytes, 4);
  header.write("WAVE", 8, "ascii");
  header.write("fmt ", 12, "ascii");
  header.writeUInt32LE(16, 16);
  header.writeUInt16LE(1, 20);
  header.writeUInt16LE(channels, 22);
  header.writeUInt32LE(sampleRate, 24);
  header.writeUInt32LE(sampleRate * channels * 2, 28);
  header.writeUInt16LE(channels * 2, 32);
  header.writeUInt16LE(16, 34);
  header.write("data", 36, "ascii");
  const declared = declaredBytes == null ? dataBytes : declaredBytes;
  header.writeUInt32LE(declared, 40);
  const body = Buffer.alloc(dataBytes);
  for (let i = 0; i < pcm.length; i++) body.writeInt16LE(pcm[i], i * 2);
  return Buffer.concat([header, body]);
}

function lcg(seed: number): () => number {
  let s = seed >>> 0;
  return () => {
    s = (s * 1664525 + 1013904223) >>> 0;
    return s / 4294967296;
  };
}

/**
 * 造一段「非平稳」的假音乐：随机低通噪声 + 每 120ms 换一次的随机包络 + 弱音调。
 *
 * 为什么不用纯正弦：稳态音调会让任意 300ms 窗口都跟别处高相关（自检里实测 0.5+），
 * 那样「错内容」永远落在 0.5~0.9 的 suspect 带里，无法验证 < 0.5 的硬判据。
 * 真音乐同样更接近这里的宽带/非平稳特征。
 */
export function makeSongPcm(sampleRate: number, ms: number, seed = 7): Int16Array {
  const n = Math.round((sampleRate * ms) / 1000);
  const rnd = lcg(seed);
  const out = new Int16Array(n);
  const block = Math.max(1, Math.round((0.12 * sampleRate) / 1)); // 120ms 一个块
  let lp = 0;
  let env = 0.5;
  let cut = 0.12;
  let tone = 0;
  for (let i = 0; i < n; i++) {
    if (i % block === 0) {
      env = 0.15 + 0.85 * rnd();
      cut = 0.05 + 0.6 * rnd();
      tone = (rnd() < 0.5 ? 1 : -1) * (0.05 + 0.25 * rnd());
    }
    const t = i / sampleRate;
    lp = lp * (1 - cut) + (rnd() * 2 - 1) * cut;
    const v = 1.35 * lp + tone * Math.sin(2 * Math.PI * (180 + 240 * env) * t);
    out[i] = Math.max(-32767, Math.min(32767, Math.round(v * env * 12000)));
  }
  return out;
}

interface MockWorld {
  sr: number;
  file: Int16Array;
  aMs: number;
  bMs: number;
  startWall: number;
  startRawMs: number;
  writeAheadMs: number;
  behavior: "loop" | "passthrough" | "offset";
  offsetExtraMs: number;
  previewAttached: boolean;
}

function worldRaw(w: MockWorld, wall: number): number {
  return w.startRawMs + (wall - w.startWall);
}

function worldDisplay(w: MockWorld, wall: number): number {
  return bandFold(worldRaw(w, wall), w.aMs, w.bMs);
}

function worldContent(w: MockWorld, wall: number): number {
  const raw = worldRaw(w, wall) + w.writeAheadMs;
  if (w.behavior === "passthrough") return Math.min(raw, (w.file.length * 1000) / w.sr - 2);
  if (w.behavior === "offset") return bandFold(raw + w.offsetExtraMs, w.aMs, w.bMs);
  return bandFold(raw, w.aMs, w.bMs);
}

function fileValueAt(w: MockWorld, contentMs: number): number {
  const idx = Math.max(0, Math.min(w.file.length - 1, Math.round((contentMs * w.sr) / 1000)));
  return w.file[idx] / 32768;
}

class MockApi implements ProbeApi {
  tapWav: Buffer | null = null;
  tapJsonl: string | null = null;
  private tapOpen = false;
  private tapStart = 0;
  decodeCalls = 0;

  constructor(private w: MockWorld) {}

  async get(apiPath: string): Promise<HttpLike> {
    const now = Date.now();
    if (apiPath === "/debug/preview") {
      if (!this.w.previewAttached) return { status: 409, json: { error: "preview-not-attached" }, text: "" };
      const display = Math.round(worldDisplay(this.w, now));
      return {
        status: 200,
        json: {
          snapshot: {
            engine: "SOURCE",
            mode: "LOOP_ONLY",
            isPlaying: true,
            displayMs: display,
            liveDisplayMs: display,
            aMs: this.w.aMs,
            bMs: this.w.bMs,
            outputFrames: Math.round(((now - this.w.startWall) * this.w.sr) / 1000),
            playbackStateName: "READY",
          },
        },
        text: "",
      };
    }
    if (apiPath === "/state") {
      const display = Math.round(worldDisplay(this.w, now));
      return {
        status: 200,
        json: {
          playback: {
            songId: 1,
            positionMs: display,
            engine: "loop",
            playing: true,
            playbackState: "ready",
          },
          loop: { window: { songId: 1, loopStartUs: this.w.aMs * 1000, loopEndUs: this.w.bMs * 1000 } },
        },
        text: "",
      };
    }
    return { status: 404, json: null, text: "not found" };
  }

  async post(apiPath: string, body?: any): Promise<HttpLike> {
    if (apiPath === "/debug/tap") {
      const want = body?.path;
      if (want) {
        this.tapOpen = true;
        this.tapStart = Date.now();
        return { status: 200, json: { tapping: true, path: "/data/user/0/pkg/files/" + want }, text: "" };
      }
      if (this.tapOpen) this.buildTap();
      this.tapOpen = false;
      return { status: 200, json: { tapping: false, path: null }, text: "" };
    }
    if (apiPath === "/debug/decode-range") {
      this.decodeCalls += 1;
      const aMs = Number(body?.aMs ?? 0);
      const bMs = Number(body?.bMs ?? 0);
      const tailFrames = Number(body?.tailFrames ?? 0);
      const sr = this.w.sr;
      const a = Math.max(0, Math.round((aMs * sr) / 1000));
      const b = Math.min(this.w.file.length, Math.round((bMs * sr) / 1000));
      const frames = Math.max(0, b - a);
      const interleaved = Buffer.alloc(frames * 4);
      for (let i = 0; i < frames; i++) {
        const v = this.w.file[a + i];
        interleaved.writeInt16LE(v, i * 4);
        interleaved.writeInt16LE(Math.round(v * 0.9), i * 4 + 2);
      }
      const bytes = interleaved.length;
      const tailBytes = tailFrames > 0 ? Math.min(bytes, tailFrames * 4) : 0;
      const tail = tailBytes > 0 ? interleaved.subarray(bytes - tailBytes) : Buffer.alloc(0);
      return {
        status: 200,
        json: {
          path: body?.path,
          aMs,
          bMs,
          sampleRate: sr,
          channels: 2,
          frames,
          bytes,
          tailFrames,
          tailPcmBase64: tail.toString("base64"),
          elapsedMs: 1,
        },
        text: "",
      };
    }
    return { status: 404, json: null, text: "not found" };
  }

  private buildTap(): void {
    const w = this.w;
    const t0 = this.tapStart;
    const t1 = Date.now();
    const frames = Math.max(1, Math.round(((t1 - t0) * w.sr) / 1000));
    const lr = new Int16Array(frames * 2);
    let lastDisplay = worldDisplay(w, t0);
    const events: any[] = [
      { t: 0, seq: 1, ev: "configure", sr: w.sr, ch: 2, bpf: 4 },
      { t: 0, seq: 2, ev: "run", aMs: w.aMs, bMs: w.bMs },
    ];
    let seq = 2;
    for (let i = 0; i < frames; i++) {
      const wall = t0 + (i * 1000) / w.sr;
      const u = worldContent(w, wall);
      const v = Math.round(fileValueAt(w, u) * 32767);
      lr[i * 2] = v;
      lr[i * 2 + 1] = Math.round(v * 0.9);
      const display = worldDisplay(w, wall);
      if (display + 1 < lastDisplay) {
        seq += 1;
        events.push({ t: Math.round(wall - t0), seq, ev: "seam", outFrames: i });
      }
      lastDisplay = display;
    }
    seq += 1;
    events.push({ t: Math.round(t1 - t0), seq, ev: "end", totalFrames: frames, frames, flushCount: 0 });
    this.tapWav = buildWav(lr, w.sr, 2, 0); // 占位头：data size = 0（真机上关闭前就是这样）
    this.tapJsonl = events.map((e) => JSON.stringify(e)).join(String.fromCharCode(10)) + String.fromCharCode(10);
  }

  pull = async (remoteName: string): Promise<Buffer | null> => {
    if (remoteName.endsWith(".jsonl")) return this.tapJsonl ? Buffer.from(this.tapJsonl, "utf8") : null;
    return this.tapWav;
  };
}

function makeCapture(
  w: MockWorld,
  windowStartContentMs: number,
  samplesMs: number,
  displayMs: number,
): TapCapture {
  const a = Math.max(0, Math.round((windowStartContentMs * w.sr) / 1000));
  const n = Math.max(64, Math.round((samplesMs * w.sr) / 1000));
  const window = new Float32Array(n);
  for (let i = 0; i < n; i++) {
    const idx = Math.min(w.file.length - 1, a + i);
    window[i] = w.file[idx] / 32768;
  }
  return {
    wavPath: "(memory)",
    jsonlPath: null,
    sampleRate: w.sr,
    channels: 1,
    bytesPerFrame: 2,
    totalFrames: a + n,
    window,
    tail: window,
    tailStartFrame: a,
    windowStartFrame: a,
    windowEndFrame: a + n,
    windowMs: samplesMs,
    events: [],
    seams: [],
    flushCount: 0,
    configureCount: 1,
    displaySamples: [{ atMs: Date.now(), displayMs, playing: true, engine: "SOURCE", state: "READY" }],
    displayRef: { atMs: Date.now(), displayMs, playing: true, engine: "SOURCE", state: "READY" },
    displayAtStop: { atMs: Date.now(), displayMs, playing: true, engine: "SOURCE", state: "READY" },
    displaySource: "mock",
    startedAtMs: Date.now(),
    stoppedAtMs: Date.now(),
    warnings: [],
  };
}

async function selfTest(): Promise<number> {
  const results: string[] = [];
  let failed = 0;
  const ok = (name: string, cond: boolean, detail: string) => {
    results.push((cond ? "PASS " : "FAIL ") + name + " — " + detail);
    if (!cond) failed += 1;
  };

  // ---- 1. WAV 解析（占位头 / 声道 / 帧数） ----
  {
    const sr = 44100;
    const mono = makeSongPcm(sr, 1000);
    const stereo = new Int16Array(mono.length * 2);
    for (let i = 0; i < mono.length; i++) {
      stereo[i * 2] = mono[i];
      stereo[i * 2 + 1] = mono[i];
    }
    const wav = buildWav(stereo, sr, 2, 0);
    const info = parseWavBuffer(wav);
    ok(
      "WAV 占位头(data size=0)按文件剩余解析",
      info.frames === mono.length && info.channels === 2 && info.warnings.some((x) => x.includes("占位")),
      "frames=" + info.frames + " ch=" + info.channels + " warn=" + info.warnings.length,
    );
    const win = readWavMono(wav, info, info.frames - 441, 441);
    ok("readWavMono 取末尾 10ms", win.length === 441 && Math.abs(win[0] - mono[mono.length - 441] / 32768) < 1e-3, "len=" + win.length);
  }

  // ---- 2. NCC：同一段应峰值≈1，位置精确 ----
  const sr = 44100;
  const song = makeSongPcm(sr, 62000, 11);
  const monoOf = (fromMs: number, ms: number): Float32Array => {
    const a = Math.round((fromMs * sr) / 1000);
    const n = Math.round((ms * sr) / 1000);
    const out = new Float32Array(n);
    for (let i = 0; i < n; i++) out[i] = song[Math.min(song.length - 1, a + i)] / 32768;
    return out;
  };
  {
    const ref = monoOf(20000, 2400);
    const win = monoOf(21000, 300);
    const m = nccBest(win, ref, sr);
    ok(
      "NCC 同一段：峰值≈1、位置落在 +1000ms",
      m.peak > 0.999 && Math.abs(m.lagFrames / (sr / 1000) - 1000) <= 2,
      "peak=" + m.peak.toFixed(4) + " lag=" + (m.lagFrames / (sr / 1000)).toFixed(1) + "ms prom=" + m.prominence.toFixed(3),
    );
  }
  {
    const ref = monoOf(50000, 2400);
    const win = monoOf(21000, 300);
    const m = nccBest(win, ref, sr);
    ok("NCC 不同内容：峰值 < 0.5", m.peak < 0.5, "peak=" + m.peak.toFixed(4));
  }

  // ---- 3. 抽样率不一致（tap 48k vs 参考 44.1k） ----
  {
    const ref = monoOf(30000, 2400);
    const winHigh = resampleLinear(monoOf(31000, 300), sr, 48000);
    const back = resampleLinear(winHigh, 48000, sr);
    const m = nccBest(back, ref, sr);
    ok("重采样往返后仍能定位（峰 > 0.9）", m.peak > 0.9, "peak=" + m.peak.toFixed(4));
  }

  // ---- 3.5 窗口跨接缝时自动挪开 ----
  {
    const wanted = 13230;
    const total = 44100;
    const noSeam = chooseWindowStart(total, wanted, []);
    ok("窗口无接缝:起点=末尾-窗口", noSeam.startFrame === total - wanted && noSeam.avoidedSeam == null, "start=" + noSeam.startFrame);
    const tailSeam = total - 5000;
    const midSeam = chooseWindowStart(total, wanted, [tailSeam]);
    ok(
      "接缝在末尾窗口内:窗口整体挪到接缝之前",
      midSeam.avoidedSeam === tailSeam && midSeam.startFrame + wanted <= tailSeam - 60,
      "start=" + midSeam.startFrame + " end=" + (midSeam.startFrame + wanted) + " seam=" + tailSeam,
    );
    const earlySeam = chooseWindowStart(total, wanted, [2000]);
    ok(
      "接缝远离末尾窗口:不动窗口(不误挪)",
      earlySeam.avoidedSeam == null && earlySeam.startFrame === total - wanted,
      "start=" + earlySeam.startFrame,
    );
  }

  // ---- 4. decode-range 分片：区间过长必须报错（防止 tail 静默截断） ----
  {
    const w: MockWorld = {
      sr,
      file: song,
      aMs: 10000,
      bMs: 40000,
      startWall: Date.now(),
      startRawMs: 10500,
      writeAheadMs: 250,
      behavior: "loop",
      offsetExtraMs: 0,
      previewAttached: true,
    };
    const api = new MockApi(w);
    let threw = false;
    try {
      await decodeRangeOnce(api, "/x.mp3", 10000, 13000);
    } catch (e: any) {
      threw = String(e?.message ?? e).includes("截断");
    }
    ok("decode-range 超长区间报『截断』而不是静默短解", threw, "threw=" + threw);
  }

  // ---- 5. 端到端 capture（模拟正常循环 + 250ms 写入超前） ----
  const workDir = path.join(os.tmpdir(), "hesi-content-probe-selftest-" + process.pid);
  const w: MockWorld = {
    sr,
    file: song,
    aMs: 10000,
    bMs: 40000,
    startWall: Date.now(),
    startRawMs: 25000,
    writeAheadMs: 250,
    behavior: "loop",
    offsetExtraMs: 0,
    previewAttached: true,
  };
  const api = new MockApi(w);
  const stack = createProber(
    { adbExe: "adb", device: "mock", outDir: workDir } as unknown as Cfg,
    api,
    { workDir, pull: api.pull },
  );
  let baselineOffset = 0;
  {
    const capture = await stack.prober.capture({
      target: "preview",
      samplesMs: 200,
      dwellMs: 450,
      workDir,
      label: "baseline",
    });
    ok(
      "capture：抓到 tap 窗口 + display 锚点",
      capture.window.length > 1000 && capture.displayRef != null && capture.seams.length >= 0,
      "frames=" + capture.totalFrames + " win=" + capture.window.length + " display=" + capture.displayRef?.displayMs,
    );
    const displayRef = Number(capture.displayRef?.displayMs ?? 0);
    const res = await stack.truth.probeCapture(capture, {
      songPath: "/song.mp3",
      aMs: w.aMs,
      bMs: w.bMs,
      expectedContentMs: displayRef,
      mode: "band",
    });
    baselineOffset = res.driftMs ?? 0;
    ok(
      "正常循环基线：峰值 >= 0.95 且偏移≈写入超前(250ms)",
      res.verdict === "match" && res.peak >= 0.95 && Math.abs(baselineOffset - 250) <= 120,
      "verdict=" + res.verdict + " peak=" + res.peak.toFixed(4) + " drift=" + Math.round(baselineOffset) + "ms",
    );
  }

  // ---- 6. 00027 型：显示折返到 A，但写出的内容是 B 之后 ----
  {
    const u = w.bMs + 2000;
    const displayAfterWrap = w.aMs + 500;
    const capture = makeCapture(w, u, 300, displayAfterWrap);
    const res = await stack.truth.probeCapture(capture, {
      songPath: "/song.mp3",
      aMs: w.aMs,
      bMs: w.bMs,
      expectedContentMs: displayAfterWrap + baselineOffset,
      mode: "band",
      wide: true,
    });
    ok(
      "00027 型（内容是 B 之后）：判 mismatch",
      res.verdict === "mismatch" && res.peak < 0.5,
      "verdict=" + res.verdict + " peak=" + res.peak.toFixed(4) + " drift=" + (res.driftMs == null ? "null" : Math.round(res.driftMs)),
    );
    const top = res.wide?.[0];
    ok(
      "00027 型：宽域定位指向 B 之后",
      !!top && top.region === "post-b" && Math.abs(top.positionMs - u) < 60 && top.peak > 0.9,
      top ? "region=" + top.region + " pos=" + top.positionMs.toFixed(0) + " peak=" + top.peak.toFixed(4) : "wide=null",
    );
  }

  // ---- 7. 环带内错位（源被换到环带另一处）：峰值高但位置偏 -> suspect ----
  {
    const expected = w.aMs + 2000;
    const actual = bandFold(expected + 5000 + baselineOffset, w.aMs, w.bMs);
    const capture = makeCapture(w, actual, 300, expected);
    const res = await stack.truth.probeCapture(capture, {
      songPath: "/song.mp3",
      aMs: w.aMs,
      bMs: w.bMs,
      expectedContentMs: expected + baselineOffset,
      mode: "band",
      searchHalfMs: 7000,
    });
    ok(
      "环带内位置错 5s：峰值高但判 suspect（位置一致性生效）",
      res.verdict === "suspect" && res.peak > 0.9 && Math.abs(Number(res.driftMs) - 5000) < 200,
      "verdict=" + res.verdict + " peak=" + res.peak.toFixed(4) + " drift=" + (res.driftMs == null ? "null" : Math.round(res.driftMs)),
    );
  }

  // ---- 8. 环带边界展开：窗口内容跨 B->A 也能定位 ----
  {
    const expected = w.aMs + 100;
    const actual = bandFold(w.bMs - 200 + baselineOffset, w.aMs, w.bMs);
    const capture = makeCapture(w, actual, 300, expected);
    const res = await stack.truth.probeCapture(capture, {
      songPath: "/song.mp3",
      aMs: w.aMs,
      bMs: w.bMs,
      expectedContentMs: expected + baselineOffset,
      mode: "band",
    });
    ok(
      "环带边界展开（B-200 内容、期望在 A+100）：match 且圆周差 ≈ -300ms",
      res.verdict === "match" && res.peak > 0.95 && Math.abs(Number(res.driftMs) + 300) <= 120,
      "verdict=" + res.verdict + " peak=" + res.peak.toFixed(4) + " drift=" + (res.driftMs == null ? "null" : Math.round(res.driftMs)),
    );
  }

  // ---- 8.5 窗口跨接缝：多窗口搜索应换一个不跨接缝的窗口并恢复峰值 ----
  {
    const sr2 = sr;
    const w2: MockWorld = {
      sr: sr2,
      file: song,
      aMs: 10000,
      bMs: 40000,
      startWall: Date.now(),
      startRawMs: 20000,
      writeAheadMs: 0,
      behavior: "loop",
      offsetExtraMs: 0,
      previewAttached: true,
    };
    const monoAt = (u: number, ms: number): Float32Array => {
      const a = Math.round((u * sr2) / 1000);
      const n = Math.round((ms * sr2) / 1000);
      const out = new Float32Array(n);
      for (let i = 0; i < n; i++) out[i] = song[Math.min(song.length - 1, a + i)] / 32768;
      return out;
    };
    // tail = 800ms(接缝前) + 200ms(接缝后 A 起)；末尾 300ms 窗口 = 100ms 前 + 200ms 后 -> 跨接缝
    const pre = monoAt(w2.bMs - 800, 800);
    const post = monoAt(w2.aMs, 200);
    const tail = new Float32Array(pre.length + post.length);
    tail.set(pre, 0);
    tail.set(post, pre.length);
    const wanted = Math.round((300 * sr2) / 1000);
    const window = tail.slice(tail.length - wanted);
    const cap: TapCapture = {
      wavPath: "(memory-seam)",
      jsonlPath: null,
      sampleRate: sr2,
      channels: 1,
      bytesPerFrame: 2,
      totalFrames: tail.length,
      window,
      tail,
      tailStartFrame: 0,
      windowStartFrame: tail.length - wanted,
      windowEndFrame: tail.length,
      windowMs: 300,
      events: [],
      seams: [{ t: 0, seq: 1, ev: "seam", outFrames: pre.length }],
      flushCount: 0,
      configureCount: 1,
      displaySamples: [],
      displayRef: { atMs: Date.now(), displayMs: w2.bMs - 100, playing: true, engine: "SOURCE", state: "READY" },
      displayAtStop: null,
      displaySource: "mock",
      startedAtMs: Date.now(),
      stoppedAtMs: Date.now(),
      warnings: [],
    };
    const expected = w2.bMs - 100;
    const res = await stack.truth.probeCapture(cap, {
      songPath: "/song.mp3",
      aMs: w2.aMs,
      bMs: w2.bMs,
      expectedContentMs: expected,
      mode: "band",
    });
    ok(
      "窗口跨接缝:多窗口搜索换窗口后峰值恢复(>=0.95)",
      res.peak >= 0.95 && res.windowShiftMs > 0 && Math.abs(Number(res.driftMs)) <= 120,
      "peak=" + res.peak.toFixed(3) + " shift=" + res.windowShiftMs + "ms drift=" + (res.driftMs == null ? "null" : Math.round(res.driftMs)),
    );
  }

  // ---- 9. 自由试听（file 模式） ----
  {
    const expected = 6000;
    const actual = expected + baselineOffset + 120;
    const capture = makeCapture(w, actual, 300, expected);
    const res = await stack.truth.probeCapture(capture, {
      songPath: "/song.mp3",
      aMs: w.aMs,
      bMs: w.bMs,
      expectedContentMs: expected + baselineOffset,
      mode: "file",
    });
    ok(
      "file 模式（区外自由试听）：match",
      res.verdict === "match" && res.peak > 0.95 && Math.abs(Number(res.driftMs) - 120) <= 120,
      "verdict=" + res.verdict + " peak=" + res.peak.toFixed(4) + " drift=" + (res.driftMs == null ? "null" : Math.round(res.driftMs)),
    );
  }

  console.log("");
  for (const line of results) console.log(line);
  console.log("");
  console.log("content-probe 自检: " + (results.length - failed) + "/" + results.length + " 通过");
  return failed === 0 ? 0 : 1;
}

const invokedDirectly = (() => {
  const meta: any = import.meta as any;
  if (meta && typeof meta.main === "boolean") return meta.main;
  const arg = process.argv[1] ?? "";
  return /content-probe.(ts|js)$/.test(arg);
})();

if (invokedDirectly) {
  if (process.argv.includes("--selftest") || process.argv.length <= 2) {
    selfTest()
      .then((code) => process.exit(code))
      .catch((e) => {
        console.error("自检失败: " + (e?.stack ?? e));
        process.exit(1);
      });
  } else {
    console.log("用法: bun run tools/scenarios/content-probe.ts --selftest");
  }
}
