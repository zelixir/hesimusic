#!/usr/bin/env bun
/**
 * tools/spike-analyze.ts —— P0 探针(loop-tap spike)取证分析器
 *
 * 用法:
 *   bun run tools/spike-analyze.ts <tap.wav> <tap.jsonl> [选项]
 *   bun run tools/spike-analyze.ts --selftest
 *
 * 判据(契约 §5 / docs/loop-v3-plan.md §6.2):
 *   A1 零间隙        —— 接缝 ±window 内不存在 >=zeroMinRun 帧的全零段(所有声道为 0)
 *   A2 零漂移        —— 相邻接缝的输出帧距 == 理论 L 帧(0 误差)
 *   A3 零重放/丢帧   —— 总帧数 == 前奏 + N×L(+ 收尾部分轮);有前奏时只报偏差不硬判
 *   A4 零 flush/underrun —— 稳态期 flush/discontinuity 计数 0、underrun 增量 0、喂数间隔 <= 30ms
 *   B  逐位相同      —— 有 seam 事件时每轮 PCM 与首轮逐字节相同
 *   附加:PTS 连续性 —— 由 feed 事件重建,pts 增量与 frames/sr 偏差 > 1ms 记为 discontinuity
 *
 * 退出码:0 = 全部"必须判据"通过;1 = 有必须判据失败;2 = 参数/文件/格式错误。
 */

import { open, readFile, writeFile, mkdir } from "node:fs/promises";
import * as path from "node:path";

const VERSION = "spike-analyze/1";

const SCRIPT_DIR: string = (() => {
  const anyImport: any = import.meta as any;
  if (anyImport && typeof anyImport.dir === "string") return anyImport.dir;
  try {
    return path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, "$1"));
  } catch {
    return process.cwd();
  }
})();

const FIXTURE_DIR = path.join(SCRIPT_DIR, "spike-analyze-fixtures");

// ============================================================================
// 通用小工具
// ============================================================================

const isNum = (v: unknown): v is number => typeof v === "number" && Number.isFinite(v);

function numOf(v: unknown): number | null {
  if (isNum(v)) return v;
  if (typeof v === "string" && v.trim() !== "") {
    const n = Number(v);
    if (Number.isFinite(n)) return n;
  }
  return null;
}

function firstNum(obj: any, keys: string[]): number | null {
  for (const k of keys) {
    const v = numOf(obj?.[k]);
    if (v != null) return v;
  }
  return null;
}

function framesToMs(frames: number, sr: number): number {
  return sr > 0 ? (frames / sr) * 1000 : 0;
}

function msToFrames(ms: number, sr: number): number {
  return Math.round((ms * sr) / 1000);
}

function fmtNum(n: number, digits = 3): string {
  if (!Number.isFinite(n)) return String(n);
  return n.toFixed(digits);
}

function safeSize(bytes: number): string {
  if (bytes >= 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(2) + " MiB";
  if (bytes >= 1024) return (bytes / 1024).toFixed(1) + " KiB";
  return bytes + " B";
}

// ============================================================================
// WAV 解析(流式;支持 pcm8/16/24/32 与 float32/64、任意声道、WAVE_FORMAT_EXTENSIBLE)
// ============================================================================

type Codec = "pcm8" | "pcm16" | "pcm24" | "pcm32" | "float32" | "float64";

interface WavChunk {
  id: string;
  offset: number;
  size: number;
}

interface WavInfo {
  path: string;
  fileSize: number;
  riff: string;
  formatTag: number;
  effectiveTag: number;
  codec: Codec;
  sampleRate: number;
  channels: number;
  bitsPerSample: number;
  bytesPerSample: number;
  blockAlign: number;
  bytesPerFrame: number;
  dataOffset: number;
  dataBytes: number;
  declaredDataBytes: number;
  frames: number;
  durationMs: number;
  truncated: boolean;
  trailingBytes: number;
  chunks: WavChunk[];
  warnings: string[];
}

async function parseWav(fh: any, filePath: string): Promise<WavInfo> {
  const st = await fh.stat();
  const fileSize: number = st.size;
  const warnings: string[] = [];

  const readAt = async (off: number, len: number): Promise<Buffer> => {
    const buf = Buffer.allocUnsafe(len);
    const { bytesRead } = await fh.read(buf, 0, len, off);
    return buf.subarray(0, bytesRead);
  };

  const head = await readAt(0, 12);
  if (head.length < 12) throw new Error("WAV 文件过短(不足 12 字节)");
  const riff = head.toString("ascii", 0, 4);
  const wave = head.toString("ascii", 8, 12);
  if (wave !== "WAVE") throw new Error("不是 WAVE 文件(magic=" + riff + "/" + wave + ")");
  if (riff !== "RIFF") warnings.push("RIFF magic=" + riff + "(RF64/BW64 未完全支持,按 RIFF 解析)");

  let fmt: any = null;
  let dataOffset = -1;
  let declaredData = -1;
  let actualData = -1;
  let truncated = false;
  const chunks: WavChunk[] = [];
  let pos = 12;
  let guard = 0;

  while (pos + 8 <= fileSize && guard++ < 1024) {
    const hdr = await readAt(pos, 8);
    if (hdr.length < 8) break;
    const id = hdr.toString("ascii", 0, 4);
    const size = hdr.readUInt32LE(4);
    const bodyOff = pos + 8;
    chunks.push({ id, offset: bodyOff, size });

    if (id === "fmt " && fmt == null) {
      const body = await readAt(bodyOff, Math.min(size > 0 ? size : 16, 64));
      if (body.length < 16) throw new Error("fmt chunk 不完整(<16 字节)");
      fmt = {
        formatTag: body.readUInt16LE(0),
        channels: body.readUInt16LE(2),
        sampleRate: body.readUInt32LE(4),
        byteRate: body.readUInt32LE(8),
        blockAlign: body.readUInt16LE(12),
        bitsPerSample: body.readUInt16LE(14),
        validBits: body.length >= 20 ? body.readUInt16LE(18) : 0,
        subFormatTag: body.length >= 26 ? body.readUInt32LE(24) : null,
      };
    } else if (id === "data" && dataOffset < 0) {
      dataOffset = bodyOff;
      declaredData = size;
      const avail = Math.max(0, fileSize - bodyOff);
      if (size === 0 || size === 0xffffffff) {
        warnings.push("data 长度=" + (size === 0 ? "0" : "0xFFFFFFFF") + "(流式/占位),按文件剩余 " + avail + " 字节处理");
        actualData = avail;
      } else if (size > avail) {
        warnings.push("data 声明 " + size + " 字节,但文件只剩 " + avail + " 字节(WAV 被截断)");
        actualData = avail;
        truncated = true;
      } else {
        actualData = size;
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

  const channels: number = fmt.channels;
  if (!channels || channels < 1 || channels > 64) {
    warnings.push("声道数异常: " + channels + ",按 " + Math.max(1, Math.min(64, channels || 1)) + " 处理");
  }
  const ch = Math.max(1, Math.min(64, channels || 1));
  const sampleRate: number = fmt.sampleRate;
  if (!sampleRate || sampleRate <= 0) throw new Error("WAV 采样率非法: " + sampleRate);

  const bits: number = fmt.bitsPerSample || 16;
  const bytesPerSample = Math.max(1, Math.ceil(bits / 8));
  const effectiveTag: number = fmt.formatTag === 0xfffe
    ? (fmt.subFormatTag != null ? (fmt.subFormatTag & 0xffff) : -1)
    : fmt.formatTag;

  let codec: Codec | null = null;
  if (effectiveTag === 1) {
    if (bits === 8) codec = "pcm8";
    else if (bits === 16) codec = "pcm16";
    else if (bits === 24) codec = "pcm24";
    else if (bits === 32) codec = "pcm32";
  } else if (effectiveTag === 3) {
    if (bits === 32) codec = "float32";
    else if (bits === 64) codec = "float64";
  }
  if (!codec) {
    throw new Error("不支持的 WAV 编码: formatTag=" + fmt.formatTag + " effectiveTag=" + effectiveTag + " bits=" + bits);
  }

  const bytesPerFrame = ch * bytesPerSample;
  if (fmt.blockAlign && fmt.blockAlign !== bytesPerFrame) {
    warnings.push("fmt.blockAlign=" + fmt.blockAlign + " 与 channels×bytesPerSample=" + bytesPerFrame + " 不一致,以计算值为准");
  }
  const frames = Math.floor(Math.max(0, actualData) / bytesPerFrame);
  const trailingBytes = Math.max(0, actualData) % bytesPerFrame;
  if (trailingBytes > 0) {
    warnings.push("data 字节数 " + actualData + " 不是帧大小 " + bytesPerFrame + " 的整数倍,尾部 " + trailingBytes + " 字节忽略");
  }

  return {
    path: filePath,
    fileSize,
    riff,
    formatTag: fmt.formatTag,
    effectiveTag,
    codec,
    sampleRate,
    channels: ch,
    bitsPerSample: bits,
    bytesPerSample,
    blockAlign: fmt.blockAlign,
    bytesPerFrame,
    dataOffset,
    dataBytes: Math.max(0, actualData),
    declaredDataBytes: declaredData,
    frames,
    durationMs: framesToMs(frames, sampleRate),
    truncated,
    trailingBytes,
    chunks,
    warnings,
  };
}

class WavReader {
  info: WavInfo;
  private fh: any;

  private constructor(fh: any, info: WavInfo) {
    this.fh = fh;
    this.info = info;
  }

  static async open(filePath: string): Promise<WavReader> {
    const fh = await open(filePath, "r");
    try {
      const info = await parseWav(fh, filePath);
      return new WavReader(fh, info);
    } catch (e) {
      await fh.close();
      throw e;
    }
  }

  async close(): Promise<void> {
    await this.fh.close();
  }

  /** 读取 [startFrame, startFrame+frameCount) 的 PCM 原始字节。 */
  async readFrames(startFrame: number, frameCount: number): Promise<Buffer> {
    const { bytesPerFrame, dataOffset, frames } = this.info;
    const start = Math.max(0, Math.min(Math.floor(startFrame), frames));
    const count = Math.max(0, Math.min(Math.floor(frameCount), frames - start));
    if (count <= 0) return Buffer.alloc(0);
    const byteOff = dataOffset + start * bytesPerFrame;
    const len = count * bytesPerFrame;
    const buf = Buffer.allocUnsafe(len);
    let got = 0;
    while (got < len) {
      const { bytesRead } = await this.fh.read(buf, got, len - got, byteOff + got);
      if (bytesRead <= 0) break;
      got += bytesRead;
    }
    const usable = got - (got % bytesPerFrame);
    return got === len ? buf : buf.subarray(0, usable);
  }
}

/** 判断一帧(所有声道)是否全零。返回 true 表示该帧是数字静音。 */
function frameIsZero(buf: Buffer, off: number, bpf: number, codec: Codec): boolean {
  switch (codec) {
    case "pcm8":
      for (let i = 0; i < bpf; i++) if (buf[off + i] !== 128) return false;
      return bpf > 0;
    case "pcm16":
    case "pcm24":
    case "pcm32":
      for (let i = 0; i < bpf; i++) if (buf[off + i] !== 0) return false;
      return bpf > 0;
    case "float32":
      for (let i = 0; i < bpf; i += 4) if ((buf.readUInt32LE(off + i) & 0x7fffffff) !== 0) return false;
      return true;
    case "float64":
      for (let i = 0; i < bpf; i += 8) {
        const lo = buf.readUInt32LE(off + i);
        const hi = buf.readUInt32LE(off + i + 4);
        if (lo !== 0 || (hi & 0x7fffffff) !== 0) return false;
      }
      return true;
    default:
      return false;
  }
}

// ============================================================================
// JSONL 事件日志解析(容错:未知事件、字段缺失、末行截断)
// ============================================================================

const KNOWN_EVENTS = new Set([
  "run", "configure", "feed", "flush", "discontinuity", "eos_out", "play_to_eos",
  "looping", "seam", "track", "end", "error", "seek", "state", "pause", "resume",
  "delegate", "log", "warning",
]);

interface LogData {
  path: string;
  totalLines: number;
  events: any[];
  eventCount: number;
  unknownEvents: Record<string, number>;
  parseErrors: Array<{ line: number; message: string }>;
  parseErrorCount: number;
  truncatedLastLine: boolean;
  run: any;
  configure: any;
  endEvent: any;
  errorEvents: any[];
  firstT: number | null;
  lastT: number | null;
  hasBom: boolean;
}

async function parseLogFile(filePath: string): Promise<LogData> {
  const raw = await readFile(filePath, "utf8");
  const hasBom = raw.charCodeAt(0) === 0xfeff;
  const text = hasBom ? raw.slice(1) : raw;
  const endsWithNewline = /\r?\n$/.test(text);
  const rawLines = text.split(/\r?\n/);

  const events: any[] = [];
  const unknownEvents: Record<string, number> = {};
  const parseErrors: Array<{ line: number; message: string }> = [];
  let parseErrorCount = 0;
  let truncatedLastLine = false;
  let totalLines = 0;

  for (let i = 0; i < rawLines.length; i++) {
    const line = rawLines[i].trim();
    if (!line) continue;
    totalLines++;
    let ev: any;
    try {
      ev = JSON.parse(line);
      if (!ev || typeof ev !== "object" || Array.isArray(ev)) {
        throw new Error("不是 JSON 对象");
      }
    } catch (e: any) {
      if (i === rawLines.length - 1 && !endsWithNewline) {
        truncatedLastLine = true; // 日志被截断,忽略最后半行
      } else {
        parseErrorCount++;
        if (parseErrors.length < 20) parseErrors.push({ line: i + 1, message: String(e?.message ?? e).slice(0, 200) });
      }
      continue;
    }
    events.push(ev);
    const name = typeof ev.ev === "string" ? ev.ev : "(missing)";
    if (!KNOWN_EVENTS.has(name)) unknownEvents[name] = (unknownEvents[name] || 0) + 1;
  }

  let firstT: number | null = null;
  let lastT: number | null = null;
  for (const ev of events) {
    const t = eventTime(ev);
    if (t == null) continue;
    if (firstT == null || t < firstT) firstT = t;
    if (lastT == null || t > lastT) lastT = t;
  }

  return {
    path: filePath,
    totalLines,
    events,
    eventCount: events.length,
    unknownEvents,
    parseErrors,
    parseErrorCount,
    truncatedLastLine,
    run: events.find((e) => e.ev === "run") ?? null,
    configure: events.find((e) => e.ev === "configure") ?? null,
    endEvent: [...events].reverse().find((e) => e.ev === "end") ?? null,
    errorEvents: events.filter((e) => e.ev === "error"),
    firstT,
    lastT,
    hasBom,
  };
}

function eventTime(ev: any): number | null {
  return numOf(ev?.t);
}

// ============================================================================
// 接缝定位
// ============================================================================

type PosSource = "outFrames" | "feedTime" | "theory" | "none";

interface SeamMark {
  index: number;
  iter: number | null;
  tMs: number | null;
  outFrames: number | null;
  ptsUs: number | null;
  position: number | null;
  source: PosSource;
}

interface SeamResolution {
  marks: SeamMark[];
  source: PosSource;
  inferred: boolean;
  notes: string[];
}

interface FeedPoint {
  tMs: number;
  frames: number;
  cumulative: number; // 该次 feed 之后的累计帧
}

function buildFeedTimeline(events: any[]): FeedPoint[] {
  const out: FeedPoint[] = [];
  let cum = 0;
  for (const ev of events) {
    if (ev.ev !== "feed") continue;
    const frames = numOf(ev.frames) ?? 0;
    cum += frames;
    const t = eventTime(ev);
    if (t != null) out.push({ tMs: t, frames, cumulative: cum });
  }
  return out;
}

/** 按时间取"严格早于 t"的累计输出帧数(接缝时间点落在某次 feed 之前)。 */
function framesAtTimeMs(timeline: FeedPoint[], t: number): number | null {
  if (timeline.length === 0) return null;
  let lo = 0;
  let hi = timeline.length - 1;
  let ans = -1;
  while (lo <= hi) {
    const mid = (lo + hi) >> 1;
    if (timeline[mid].tMs < t) {
      ans = mid;
      lo = mid + 1;
    } else {
      hi = mid - 1;
    }
  }
  if (ans < 0) return 0;
  return timeline[ans].cumulative;
}

function resolveSeams(log: LogData, params: any, totalFrames: number): SeamResolution {
  const seamEvents = log.events.filter((e) => e.ev === "seam");
  const L: number | null = params.loopFrames;
  const preamble: number | null = params.preambleFrames;
  const sr: number = params.sampleRate;
  const notes: string[] = [];
  const marks: SeamMark[] = [];
  let source: PosSource = "none";
  let inferred = false;

  if (seamEvents.length === 0) {
    if (L != null && L > 1 && preamble != null && params.loopingLikely) {
      const maxSeams = 100000;
      for (let k = 0, p = preamble; p < totalFrames && k < maxSeams; k++, p = preamble + k * L) {
        marks.push({
          index: k,
          iter: k + 1,
          tMs: framesToMs(p, sr),
          outFrames: null,
          ptsUs: null,
          position: p,
          source: "theory",
        });
      }
      inferred = true;
      source = "theory";
      notes.push("日志无 seam 事件:按 run/looping 的 aMs/bMs 与理论 L 推断接缝位置(A2/B 不可判定,A1 仍按窗口检查)");
      if (marks.length >= maxSeams) notes.push("推断接缝数量达到上限,已截断");
    }
    return { marks, source, inferred, notes };
  }

  const raw: SeamMark[] = seamEvents.map((e: any, i: number) => ({
    index: i,
    iter: numOf(e.iter),
    tMs: numOf(e.t),
    outFrames: firstNum(e, ["outFrames", "outFrame", "frame", "framePos", "cumulativeFrames", "totalOutFrames", "frames"]),
    ptsUs: numOf(e.ptsUs),
    position: null,
    source: "none",
  }));

  const ofs = raw.map((r) => r.outFrames);
  const allHaveOutFrames = ofs.every((v) => v != null);
  let useCumulative = false;
  let usePerIter = false;

  if (allHaveOutFrames) {
    const vals = ofs as number[];
    const nonDecreasing = vals.every((v, i) => i === 0 || v >= vals[i - 1]);
    const inRange = vals.every((v) => v >= 0 && v <= totalFrames + Math.max(1, Math.round(totalFrames * 0.02)));
    const allApproxL = L != null && L > 0 && vals.every((v) => Math.abs(v - L) <= Math.max(1, Math.round(L * 0.01)));
    if (!nonDecreasing || !inRange) {
      notes.push("seam.outFrames 非单调或越界,不按累计帧解释");
    } else if (allApproxL && vals.length > 1) {
      if (preamble != null) {
        usePerIter = true;
      } else {
        notes.push("seam.outFrames 每项≈L 且无前奏信息,无法定位接缝");
      }
    } else {
      useCumulative = true;
    }
  }

  if (useCumulative) {
    for (const r of raw) {
      marks.push({ ...r, position: r.outFrames as number, source: "outFrames" });
    }
    source = "outFrames";
  } else if (usePerIter) {
    let pos = preamble as number;
    for (const r of raw) {
      marks.push({ ...r, position: pos, source: "outFrames" });
      pos += r.outFrames as number;
    }
    source = "outFrames";
    notes.push("seam.outFrames 按每轮输出帧数解释,累加得到接缝绝对位置");
  } else {
    // 尝试用 feed 时间线把 t 映射为累计帧
    const timeline = buildFeedTimeline(log.events);
    const haveTimes = raw.every((r) => r.tMs != null) && timeline.length > 0;
    let feedPositions: number[] | null = null;
    if (haveTimes) {
      const ps = raw.map((r) => framesAtTimeMs(timeline, r.tMs as number));
      if (ps.every((p) => p != null)) {
        const nums = ps as number[];
        if (nums.every((v, i) => i === 0 || v >= nums[i - 1])) {
          feedPositions = nums;
        } else {
          notes.push("按 feed 时间线换算的接缝位置非单调,弃用");
        }
      }
    }
    if (feedPositions) {
      raw.forEach((r, i) => marks.push({ ...r, position: feedPositions![i], source: "feedTime" }));
      source = "feedTime";
      notes.push("接缝位置由 feed 事件的累计帧数(按 t 插值)换算,属 PCM 时间线口径");
    } else if (L != null && L > 0 && preamble != null) {
      raw.forEach((r, i) => marks.push({ ...r, position: preamble + i * L, source: "theory" }));
      source = "theory";
      notes.push("接缝位置退回理论值(preamble + k×L),A2/B 不可判定");
    }
  }

  // 归一化:裁剪越界、去重、按位置排序
  const positioned = marks.filter((m) => m.position != null && Number.isFinite(m.position) && (m.position as number) >= 0);
  let clamped = 0;
  for (const m of positioned) {
    if ((m.position as number) > totalFrames) {
      m.position = totalFrames;
      clamped++;
    }
  }
  if (clamped > 0) notes.push(clamped + " 个接缝位置超出总帧数,已裁剪到 " + totalFrames);

  const seen = new Set<number>();
  const dedup: SeamMark[] = [];
  for (const m of positioned) {
    const p = m.position as number;
    if (seen.has(p)) {
      notes.push("重复接缝位置 " + p + " 已去重");
      continue;
    }
    seen.add(p);
    dedup.push(m);
  }

  const sorted = [...dedup].sort((a, b) => (a.position as number) - (b.position as number));
  if (sorted.some((m, i) => m !== dedup[i])) {
    notes.push("seam 事件的顺序与其帧位置不一致,已按帧位置排序");
  }
  const finalMarks = sorted.map((m, i) => ({ ...m, index: i }));

  return { marks: finalMarks, source: finalMarks.length > 0 ? source : "none", inferred, notes };
}

// ============================================================================
// 零值段扫描(A1)
// ============================================================================

interface ZeroRun {
  startFrame: number;
  frames: number;
}

interface ZeroRunInWindow {
  run: ZeroRun;
  seamIndices: number[];
}

interface ZeroScanResult {
  globalLongest: ZeroRun | null;
  zeroFrames: number;
  totalRuns: number;
  runsInWindows: ZeroRunInWindow[];
  runsTruncated: boolean;
}

async function scanZeroRuns(
  wav: WavReader,
  windows: Array<{ seamIndex: number; start: number; end: number }>,
  maxRuns: number,
  zeroMinRun: number,
): Promise<ZeroScanResult> {
  const { frames, bytesPerFrame, codec } = wav.info;
  const sortedWindows = [...windows]
    .filter((w) => w.end > w.start)
    .sort((a, b) => a.start - b.start || a.end - b.end);

  const result: ZeroScanResult = {
    globalLongest: null,
    zeroFrames: 0,
    totalRuns: 0,
    runsInWindows: [],
    runsTruncated: false,
  };
  let winPtr = 0;
  let runStart = -1;

  const closeRun = (start: number, len: number) => {
    if (len <= 0) return;
    result.totalRuns++;
    if (!result.globalLongest || len > result.globalLongest.frames) {
      result.globalLongest = { startFrame: start, frames: len };
    }
    if (sortedWindows.length > 0 && len >= Math.max(1, zeroMinRun)) {
      const runEnd = start + len - 1;
      while (winPtr < sortedWindows.length && sortedWindows[winPtr].end <= start) winPtr++;
      const hit: number[] = [];
      for (let j = winPtr; j < sortedWindows.length && sortedWindows[j].start <= runEnd; j++) {
        if (sortedWindows[j].end > start) hit.push(sortedWindows[j].seamIndex);
      }
      if (hit.length > 0) {
        if (result.runsInWindows.length < maxRuns) {
          result.runsInWindows.push({ run: { startFrame: start, frames: len }, seamIndices: hit });
        } else {
          result.runsTruncated = true;
        }
      }
    }
  };

  const CHUNK_FRAMES = 1 << 15;
  let startFrame = 0;
  while (startFrame < frames) {
    const count = Math.min(CHUNK_FRAMES, frames - startFrame);
    const buf = await wav.readFrames(startFrame, count);
    const n = Math.floor(buf.length / bytesPerFrame);
    if (n <= 0) break;
    for (let f = 0; f < n; f++) {
      const zero = frameIsZero(buf, f * bytesPerFrame, bytesPerFrame, codec);
      if (zero) {
        result.zeroFrames++;
        if (runStart < 0) runStart = startFrame + f;
      } else if (runStart >= 0) {
        closeRun(runStart, startFrame + f - runStart);
        runStart = -1;
      }
    }
    startFrame += n;
    if (n < count) break;
  }
  if (runStart >= 0) closeRun(runStart, frames - runStart);

  return result;
}

// ============================================================================
// B:轮间逐字节比较(流式,不整轮进内存)
// ============================================================================

interface RoundRange {
  index: number;
  startFrame: number;
  endFrame: number;
  frames: number;
}

async function compareRounds(wav: WavReader, rounds: RoundRange[], maxMismatches: number): Promise<any> {
  const bpf = wav.info.bytesPerFrame;
  const bps = wav.info.bytesPerSample;
  const result: any = {
    roundCount: rounds.length,
    rounds,
    lengthMismatches: [] as any[],
    mismatches: [] as any[],
    mismatchCount: 0,
  };
  if (rounds.length < 2) return result;

  const base = rounds[0];
  const framesPerChunk = Math.max(1, Math.floor((1 << 20) / bpf));

  for (let r = 1; r < rounds.length; r++) {
    const round = rounds[r];
    if (round.frames !== base.frames) {
      result.lengthMismatches.push({
        roundIndex: r,
        expectedFrames: base.frames,
        actualFrames: round.frames,
        deltaFrames: round.frames - base.frames,
      });
    }
    const compareFrames = Math.min(round.frames, base.frames);
    let mismatchesThisRound = 0;
    outer: for (let off = 0; off < compareFrames; off += framesPerChunk) {
      const n = Math.min(framesPerChunk, compareFrames - off);
      const a = await wav.readFrames(base.startFrame + off, n);
      const b = await wav.readFrames(round.startFrame + off, n);
      const limit = Math.min(a.length, b.length);
      for (let i = 0; i < limit; i++) {
        if (a[i] !== b[i]) {
          const frameInChunk = Math.floor(i / bpf);
          const byteInFrame = i % bpf;
          const channel = Math.floor(byteInFrame / bps);
          result.mismatchCount++;
          if (result.mismatches.length < maxMismatches) {
            result.mismatches.push({
              roundIndex: r,
              roundFrame: off + frameInChunk,
              absFrame: round.startFrame + off + frameInChunk,
              channel,
              byteOffset: i,
              expectedByte: a[i],
              actualByte: b[i],
            });
          }
          mismatchesThisRound++;
          if (mismatchesThisRound >= maxMismatches) break outer;
          // 跳到下一帧,避免同一帧的多个字节重复报告
          const nextFrameByte = (Math.floor(i / bpf) + 1) * bpf;
          i = nextFrameByte - 1;
        }
      }
    }
  }
  return result;
}

// ============================================================================
// PTS 连续性
// ============================================================================

function analyzePts(log: LogData, sr: number, thresholdUs: number): any {
  const feeds = log.events.filter((e) => e.ev === "feed");
  const discontinuities: any[] = [];
  let cumBefore = 0;
  let first: { ptsUs: number; cumBefore: number } | null = null;
  let prev: { ptsUs: number; cumBefore: number } | null = null;
  let count = 0;
  let resets = 0;
  let maxAbsDeviationUs = 0;
  let ptsFeedCount = 0;

  for (let i = 0; i < feeds.length; i++) {
    const f = feeds[i];
    const frames = numOf(f.frames);
    const pts = numOf(f.ptsUs);
    if (pts != null) {
      ptsFeedCount++;
      if (first == null) first = { ptsUs: pts, cumBefore };
      if (prev != null) {
        const span = cumBefore - prev.cumBefore;
        const expectedUs = sr > 0 ? (span / sr) * 1e6 : 0;
        const deltaUs = pts - prev.ptsUs;
        const deviationUs = deltaUs - expectedUs;
        if (Math.abs(deviationUs) > thresholdUs) {
          count++;
          maxAbsDeviationUs = Math.max(maxAbsDeviationUs, Math.abs(deviationUs));
          if (discontinuities.length < 200) {
            discontinuities.push({
              feedIndex: i,
              tMs: eventTime(f),
              framePos: cumBefore,
              prevPtsUs: prev.ptsUs,
              ptsUs: pts,
              deltaUs,
              expectedUs,
              deviationUs,
              kind: deltaUs < 0 ? "backward" : "jump",
            });
          }
        }
        if (deltaUs < 0) resets++;
      }
      prev = { ptsUs: pts, cumBefore };
    }
    cumBefore += frames ?? 0;
  }

  let cumulativeDriftUs: number | null = null;
  if (first != null && prev != null && prev.cumBefore > first.cumBefore) {
    const expected = first.ptsUs + ((prev.cumBefore - first.cumBefore) / sr) * 1e6;
    cumulativeDriftUs = prev.ptsUs - expected;
  }

  return {
    feedCount: feeds.length,
    ptsFeedCount,
    thresholdUs,
    discontinuityCount: count,
    discontinuities,
    resets,
    maxAbsDeviationUs,
    cumulativeDriftUs,
    firstPtsUs: first?.ptsUs ?? null,
    lastPtsUs: prev?.ptsUs ?? null,
  };
}

// ============================================================================
// 分析主流程
// ============================================================================

interface AnalyzeOptions {
  seamWindowMs: number;
  zeroMinRun: number;
  steadyStartMs: number;
  steadyEndMs: number;
  feedGapThresholdMs: number;
  ptsThresholdUs: number;
  strict: boolean;
  maxMismatches: number;
  maxZeroRuns: number;
}

function defaultOptions(): AnalyzeOptions {
  return {
    seamWindowMs: 10,
    zeroMinRun: 1,
    steadyStartMs: 1000,
    steadyEndMs: 1000,
    feedGapThresholdMs: 30,
    ptsThresholdUs: 1000,
    strict: false,
    maxMismatches: 10,
    maxZeroRuns: 2000,
  };
}

async function analyze(wavPath: string, jsonlPath: string, opts: AnalyzeOptions): Promise<any> {
  const wav = await WavReader.open(wavPath);
  try {
    const log = await parseLogFile(jsonlPath);
    return await analyzeCore(wav, log, wavPath, jsonlPath, opts);
  } finally {
    await wav.close();
  }
}

async function analyzeCore(
  wav: WavReader,
  log: LogData,
  wavPath: string,
  jsonlPath: string,
  opts: AnalyzeOptions,
): Promise<any> {
  const wi = wav.info;
  const totalFrames = wi.frames;
  const warnings: string[] = [];
  const run = log.run ?? {};
  const cfg = log.configure ?? {};
  const looping = [...log.events].reverse().find((e) => e.ev === "looping") ?? null;

  // ---- 参数 ----
  const srLog = numOf(cfg.sampleRate) ?? numOf(run.sampleRate);
  const sampleRate = srLog ?? wi.sampleRate;
  if (srLog != null && srLog !== wi.sampleRate) {
    warnings.push("日志采样率 " + srLog + " 与 WAV " + wi.sampleRate + " 不一致,帧换算以日志采样率为准");
  }
  const mode: string | null = typeof run.mode === "string" ? run.mode : null;
  const aMs = numOf(run.aMs) ?? numOf(looping?.aMs);
  const bMs = numOf(run.bMs) ?? numOf(looping?.bMs);
  const loopFrames = aMs != null && bMs != null && bMs > aMs ? msToFrames(bMs - aMs, sampleRate) : null;
  const preambleFrames = aMs != null && aMs >= 0 ? msToFrames(aMs, sampleRate) : null;
  const loopingLikely =
    (mode != null && ["baseline", "window", "sink", "media"].includes(mode)) ||
    (mode == null && aMs != null && bMs != null && bMs > aMs);

  const logDurationRaw = numOf(log.endEvent?.durationMs) ?? log.lastT;
  const durationMs =
    logDurationRaw != null && logDurationRaw > 0 ? logDurationRaw : framesToMs(totalFrames, sampleRate);

  const params = {
    sampleRate,
    mode,
    tag: typeof run.tag === "string" ? run.tag : null,
    path: typeof run.path === "string" ? run.path : null,
    aMs,
    bMs,
    preambleFrames,
    loopFrames,
    loopMs: loopFrames != null ? framesToMs(loopFrames, sampleRate) : null,
    loopingLikely,
    durationMs,
    totalFrames,
    wavDurationMs: wi.durationMs,
  };

  // ---- 接缝 ----
  const seamRes = resolveSeams(log, params, totalFrames);
  const marks = seamRes.marks;
  const positions = marks.map((m) => m.position as number);
  const seamSource = seamRes.source;

  // ---- A1 零间隙 ----
  const halfWindow = Math.max(1, msToFrames(opts.seamWindowMs, sampleRate));
  const windows = positions.map((p, i) => ({
    seamIndex: i,
    start: Math.max(0, p - halfWindow),
    end: Math.min(totalFrames, p + halfWindow + 1),
  }));
  const zeroScan = await scanZeroRuns(wav, windows, opts.maxZeroRuns, opts.zeroMinRun);

  const seamChecks = windows.map((w, i) => ({
    seamIndex: i,
    position: positions[i],
    windowStartFrame: w.start,
    windowEndFrame: w.end,
    runCount: 0,
    longestRunFrames: 0,
    firstRunStartFrame: null as number | null,
  }));
  for (const hit of zeroScan.runsInWindows) {
    for (const idx of hit.seamIndices) {
      const c = seamChecks[idx];
      if (!c) continue;
      c.runCount++;
      if (c.firstRunStartFrame == null) c.firstRunStartFrame = hit.run.startFrame;
      if (hit.run.frames > c.longestRunFrames) c.longestRunFrames = hit.run.frames;
    }
  }

  const minRun = Math.max(1, opts.zeroMinRun);
  let maxZeroRun: any = null;
  for (let i = 0; i < seamChecks.length; i++) {
    const c = seamChecks[i];
    if (c.longestRunFrames >= minRun && (maxZeroRun == null || c.longestRunFrames > maxZeroRun.frames)) {
      let start = c.firstRunStartFrame;
      for (const hit of zeroScan.runsInWindows) {
        if (hit.seamIndices.includes(i) && hit.run.frames === c.longestRunFrames) {
          start = hit.run.startFrame;
          break;
        }
      }
      maxZeroRun = {
        startFrame: start,
        frames: c.longestRunFrames,
        ms: framesToMs(c.longestRunFrames, sampleRate),
        seamIndex: i,
        seamPosition: c.position,
      };
    }
  }

  let a1Status: "pass" | "fail" | "warn" | "skipped";
  let a1Summary: string;
  let a1Vacuous = false;
  if (positions.length === 0) {
    a1Status = "pass";
    a1Vacuous = true;
    a1Summary = "无接缝(单段播放或无法定位接缝),A1 空真通过";
  } else if (maxZeroRun != null) {
    a1Status = "fail";
    a1Summary = "接缝 #" + (maxZeroRun.seamIndex + 1) + " 附近检测到 " + maxZeroRun.frames + " 帧(" + fmtNum(maxZeroRun.ms, 3) + " ms)全零静音,起点帧 " + maxZeroRun.startFrame;
  } else {
    a1Status = "pass";
    a1Summary = positions.length + " 处接缝 ±" + opts.seamWindowMs + "ms 窗口内无 ≥" + minRun + " 帧全零段";
  }
  if (positions.length > 0 && zeroScan.globalLongest && zeroScan.globalLongest.frames >= minRun) {
    if (zeroScan.runsInWindows.length === 0) {
      warnings.push(
        "全文件最长零值段 " + zeroScan.globalLongest.frames + " 帧位于帧 " + zeroScan.globalLongest.startFrame +
        ",不在任何接缝窗口内(可能为曲内静音或未被 seam 覆盖的间隙)",
      );
    }
  }

  // ---- A2 零漂移 ----
  const a2Deviations: any[] = [];
  const canMeasureA2 = positions.length >= 2 && seamSource !== "theory" && loopFrames != null && loopFrames > 0;
  if (canMeasureA2) {
    for (let i = 1; i < positions.length; i++) {
      const distance = positions[i] - positions[i - 1];
      const delta = distance - (loopFrames as number);
      a2Deviations.push({
        seamIndex: i,
        fromPosition: positions[i - 1],
        toPosition: positions[i],
        distanceFrames: distance,
        expectedFrames: loopFrames,
        deltaFrames: delta,
        deltaMs: framesToMs(delta, sampleRate),
      });
    }
  }
  const a2MaxAbs = a2Deviations.reduce((m, d) => Math.max(m, Math.abs(d.deltaFrames)), 0);
  let a2Status: "pass" | "fail" | "warn" | "skipped";
  let a2Summary: string;
  let a2Vacuous = false;
  if (positions.length < 2) {
    a2Status = "pass";
    a2Vacuous = true;
    a2Summary = "接缝少于 2 处,无帧距可比较,A2 空真通过";
  } else if (seamSource === "theory") {
    a2Status = "skipped";
    a2Summary = "接缝位置来自理论推算(preamble+k×L),无处观察实际帧距,A2 跳过";
  } else if (loopFrames == null || loopFrames <= 0) {
    a2Status = "skipped";
    a2Summary = "日志缺少 aMs/bMs(或 bMs<=aMs),无法得到理论 L,A2 跳过";
  } else if (a2MaxAbs > 0) {
    const worst = a2Deviations.reduce((a, b) => (Math.abs(b.deltaFrames) > Math.abs(a.deltaFrames) ? b : a));
    a2Status = "fail";
    a2Summary = "相邻接缝帧距偏离理论 L=" + loopFrames + ":最差在第 " + (worst.seamIndex + 1) + " 处,实测 " + worst.distanceFrames + " 帧,偏差 " + worst.deltaFrames + " 帧(" + fmtNum(worst.deltaMs, 3) + " ms)";
  } else {
    a2Status = "pass";
    a2Summary = a2Deviations.length + " 段接缝帧距全部 == L=" + loopFrames + " 帧(偏差 0)";
  }

  // ---- B 逐位相同 ----
  const bRounds: RoundRange[] = [];
  for (let i = 0; i < positions.length; i++) {
    const start = positions[i];
    const end = i + 1 < positions.length ? positions[i + 1] : totalFrames;
    const frames = end - start;
    if (frames > 0) bRounds.push({ index: i, startFrame: start, endFrame: end, frames });
  }
  let bStatus: "pass" | "fail" | "warn" | "skipped";
  let bSummary: string;
  let bResult: any = null;
  const bMeasurable = seamSource === "outFrames" || seamSource === "feedTime";
  if (bRounds.length < 2) {
    bStatus = "skipped";
    bSummary = "可用轮次少于 2(无 seam 事件或接缝不足),B 跳过";
  } else if (!bMeasurable) {
    bStatus = "skipped";
    bSummary = "接缝位置不可信(理论推算),B 跳过";
  } else {
    bResult = await compareRounds(wav, bRounds, opts.maxMismatches);
    if (bResult.mismatchCount > 0 || bResult.lengthMismatches.length > 0) {
      bStatus = "fail";
      const first = bResult.mismatches[0];
      const lenInfo = bResult.lengthMismatches.length > 0
        ? ";" + bResult.lengthMismatches.length + " 处轮长不一致(例:第 " + (bResult.lengthMismatches[0].roundIndex + 1) + " 轮 " + bResult.lengthMismatches[0].actualFrames + " 帧,首轮 " + bResult.lengthMismatches[0].expectedFrames + " 帧)"
        : "";
      bSummary = first
        ? "轮间 PCM 不一致:第 " + (first.roundIndex + 1) + " 轮帧 " + first.roundFrame + "(绝对帧 " + first.absFrame + ",声道 " + first.channel + ")字节不同" + lenInfo
        : "轮间 PCM 不一致" + lenInfo;
    } else {
      bStatus = "pass";
      bSummary = bRounds.length + " 轮 PCM 与首轮逐字节相同(共 " + bRounds[0].frames + " 帧/轮)";
    }
  }

  // ---- A3 零重放/丢帧 ----
  let a3Status: "pass" | "fail" | "warn" | "skipped";
  let a3Summary: string;
  let a3: any;
  if (loopFrames == null || preambleFrames == null || loopFrames <= 0) {
    a3Status = "skipped";
    a3Summary = "日志缺少 aMs/bMs 或 L<=0,无法核对总帧数,A3 跳过";
    a3 = { preambleFrames, loopFrames, measuredFirstSeam: null, seamCount: positions.length, expectedFrames: null, totalFrames, deviationFrames: null, tailFrames: null, preambleDeviationFrames: null };
  } else {
    const seamCount = positions.length;
    const expectedFrames = preambleFrames + seamCount * loopFrames;
    const tailFrames = totalFrames - expectedFrames;
    const measuredFirstSeam = seamSource !== "theory" && positions.length > 0 ? positions[0] : null;
    const preambleDeviationFrames = measuredFirstSeam != null ? measuredFirstSeam - preambleFrames : null;
    a3 = { preambleFrames, loopFrames, measuredFirstSeam, seamCount, expectedFrames, totalFrames, deviationFrames: tailFrames, tailFrames, preambleDeviationFrames };
    const issues: string[] = [];
    if (preambleDeviationFrames != null && preambleDeviationFrames !== 0) {
      issues.push("首个接缝位置 " + measuredFirstSeam + " 与理论前奏 " + preambleFrames + " 相差 " + preambleDeviationFrames + " 帧");
    }
    if (tailFrames < 0) {
      issues.push("总帧数 " + totalFrames + " 比期望 " + expectedFrames + " 少 " + (-tailFrames) + " 帧(疑似丢帧/提前结束)");
    } else if (tailFrames >= loopFrames) {
      issues.push("总帧数比期望多 " + tailFrames + " 帧(超过一整轮,疑似重放)");
    }
    if (seamCount === 0) {
      a3Status = "skipped";
      a3Summary = "无可定位接缝,无法按 N×L 核对总帧数,A3 跳过";
    } else if (issues.length === 0) {
      a3Status = "pass";
      a3Summary = "总帧数 " + totalFrames + " == 前奏 " + preambleFrames + " + " + seamCount + "×L " + loopFrames + " + 尾部 " + tailFrames + " 帧(收尾部分轮属正常)";
    } else if (preambleFrames > 0 && !opts.strict) {
      a3Status = "warn";
      a3Summary = "有前奏(baseline 语义),按报告不硬判:" + issues.join(";");
    } else {
      a3Status = "fail";
      a3Summary = issues.join(";");
    }
    if (tailFrames > 0 && tailFrames < loopFrames) {
      a3.note = "尾部 " + tailFrames + " 帧为到点停止时的部分轮,正常";
    }
  }

  // ---- A4 零 flush / 零 underrun ----
  const steadyStart = Math.max(0, opts.steadyStartMs);
  const steadyEnd = durationMs - opts.steadyEndMs;
  const hasSteady = steadyEnd > steadyStart;
  const inSteady = (t: number | null): boolean => t != null && hasSteady && t >= steadyStart && t < steadyEnd;

  const allFeeds = log.events.filter((e) => e.ev === "feed");
  const timedFeeds = allFeeds.filter((e) => eventTime(e) != null);

  const flushEvents: any[] = [];
  let flushTotal = 0;
  let flushSteady = 0;
  let flushStartup = 0;
  let flushUnplaced = 0;
  const discEvents: any[] = [];
  let discTotal = 0;
  let discSteady = 0;
  let discUnplaced = 0;

  for (const ev of log.events) {
    if (ev.ev !== "flush" && ev.ev !== "discontinuity") continue;
    const t = eventTime(ev);
    const steady = inSteady(t);
    const entry = { t, ev: ev.ev, steady };
    if (ev.ev === "flush") {
      flushTotal++;
      if (t == null) flushUnplaced++;
      else if (steady) flushSteady++;
      else if (t != null && t < steadyStart) flushStartup++;
      flushEvents.push(entry);
    } else {
      discTotal++;
      if (t == null) discUnplaced++;
      else if (steady) discSteady++;
      discEvents.push(entry);
    }
  }
  if (!hasSteady) warnings.push("稳态窗口为空(duration=" + fmtNum(durationMs, 0) + "ms,排除启动 " + steadyStart + "ms/收尾 " + opts.steadyEndMs + "ms),A4 只做整段统计");

  // underrun
  const trackEvents = log.events
    .filter((e) => e.ev === "track" && numOf(e.underrun) != null)
    .map((e) => ({ t: eventTime(e), underrun: numOf(e.underrun) as number, state: e.state }));
  const steadyTracks = trackEvents.filter((e) => inSteady(e.t ?? null));
  let underrunSteadyDelta = 0;
  let underrunWholeDelta = 0;
  let underrunMaxSingleStep = 0;
  let underrunResets = 0;
  for (let i = 1; i < trackEvents.length; i++) {
    const d = trackEvents[i].underrun - trackEvents[i - 1].underrun;
    if (d < 0) {
      underrunResets++;
      continue;
    }
    underrunWholeDelta += d;
    underrunMaxSingleStep = Math.max(underrunMaxSingleStep, d);
  }
  for (let i = 1; i < steadyTracks.length; i++) {
    const d = steadyTracks[i].underrun - steadyTracks[i - 1].underrun;
    if (d > 0) underrunSteadyDelta += d;
  }

  // feed 间隔
  const steadyFeeds = timedFeeds.filter((e) => inSteady(eventTime(e)));
  const gaps: Array<{ fromTMs: number; toTMs: number; gapMs: number }> = [];
  for (let i = 1; i < steadyFeeds.length; i++) {
    const a = eventTime(steadyFeeds[i - 1]) as number;
    const b = eventTime(steadyFeeds[i]) as number;
    const gap = b - a;
    if (gap > 0) gaps.push({ fromTMs: a, toTMs: b, gapMs: gap });
  }
  gaps.sort((x, y) => y.gapMs - x.gapMs);
  const maxGap = gaps.length > 0 ? gaps[0] : null;

  const a4Problems: string[] = [];
  if (flushSteady > 0) a4Problems.push("稳态 flush " + flushSteady + " 次");
  if (discSteady > 0) a4Problems.push("稳态 discontinuity " + discSteady + " 次");
  if (underrunSteadyDelta > 0) a4Problems.push("稳态 underrun 增量 " + underrunSteadyDelta);
  if (maxGap != null && maxGap.gapMs > opts.feedGapThresholdMs) {
    a4Problems.push("最大喂数间隔 " + fmtNum(maxGap.gapMs, 1) + "ms > " + opts.feedGapThresholdMs + "ms");
  }
  if (flushUnplaced > 0 || discUnplaced > 0) {
    warnings.push((flushUnplaced + discUnplaced) + " 个 flush/discontinuity 事件缺少 t 字段,未计入稳态");
  }
  if (!hasSteady) {
    if (flushTotal > 0 && flushSteady === 0) a4Problems.push("整段 flush " + flushTotal + " 次(稳态窗口为空,按整段计)");
    if (discTotal > 0 && discSteady === 0) a4Problems.push("整段 discontinuity " + discTotal + " 次(稳态窗口为空,按整段计)");
    if (underrunWholeDelta > 0 && underrunSteadyDelta === 0) a4Problems.push("整段 underrun 增量 " + underrunWholeDelta + "(稳态窗口为空,按整段计)");
  }

  const a4Status: "pass" | "fail" | "warn" | "skipped" = a4Problems.length > 0 ? "fail" : "pass";
  const a4Summary = a4Problems.length > 0
    ? a4Problems.join(";")
    : "稳态[" + fmtNum(steadyStart, 0) + "ms, " + fmtNum(Math.max(steadyStart, steadyEnd), 0) + "ms) 内 flush=0/discontinuity=0、underrun 增量 0、最大喂数间隔 " + (maxGap ? fmtNum(maxGap.gapMs, 1) + "ms" : "n/a");

  const a4 = {
    durationMs,
    steady: { startMs: steadyStart, endMs: Math.max(steadyStart, steadyEnd), active: hasSteady },
    feedGapThresholdMs: opts.feedGapThresholdMs,
    flush: { total: flushTotal, steady: flushSteady, startup: flushStartup, unplaced: flushUnplaced, events: flushEvents.slice(0, 100) },
    discontinuity: { total: discTotal, steady: discSteady, unplaced: discUnplaced, events: discEvents.slice(0, 100) },
    underrun: {
      trackCount: trackEvents.length,
      steadyTrackCount: steadyTracks.length,
      steadyDelta: underrunSteadyDelta,
      wholeRunDelta: underrunWholeDelta,
      maxSingleStep: underrunMaxSingleStep,
      resets: underrunResets,
      firstSteady: steadyTracks.length > 0 ? steadyTracks[0].underrun : null,
      lastSteady: steadyTracks.length > 0 ? steadyTracks[steadyTracks.length - 1].underrun : null,
    },
    feed: {
      total: allFeeds.length,
      timedCount: timedFeeds.length,
      steadyCount: steadyFeeds.length,
      maxGapMs: maxGap ? maxGap.gapMs : null,
      maxGap,
      topGaps: gaps.slice(0, 5),
    },
  };

  // ---- PTS ----
  const pts = analyzePts(log, sampleRate, opts.ptsThresholdUs);

  // ---- 判据汇总 ----
  const criteria: any = {
    A1: {
      id: "A1",
      name: "零间隙",
      must: true,
      status: a1Status,
      vacuous: a1Vacuous,
      summary: a1Summary,
      seamWindowMs: opts.seamWindowMs,
      zeroMinRun: minRun,
      maxZeroRun,
      seamChecks,
      globalLongestZeroRun: zeroScan.globalLongest,
      totalZeroFrames: zeroScan.zeroFrames,
      totalZeroRuns: zeroScan.totalRuns,
      runsTruncated: zeroScan.runsTruncated,
    },
    A2: {
      id: "A2",
      name: "零漂移",
      must: true,
      status: a2Status,
      vacuous: a2Vacuous,
      summary: a2Summary,
      positionSource: seamSource,
      expectedLoopFrames: loopFrames,
      deviations: a2Deviations,
      maxAbsDeltaFrames: a2MaxAbs,
    },
    A3: {
      id: "A3",
      name: "零重放/丢帧",
      must: opts.strict || preambleFrames === 0,
      status: a3Status,
      summary: a3Summary,
      ...a3,
    },
    A4: {
      id: "A4",
      name: "零 flush / 零 underrun",
      must: true,
      status: a4Status,
      summary: a4Summary,
      ...a4,
    },
    B: {
      id: "B",
      name: "逐位相同",
      must: !!opts.strict,
      status: bStatus,
      summary: bSummary,
      ...(bResult ?? { roundCount: bRounds.length, rounds: bRounds, lengthMismatches: [], mismatches: [], mismatchCount: 0 }),
    },
  };

  // ---- 结论 ----
  const mustIds = Object.keys(criteria).filter((k) => criteria[k].must);
  const failedMust = mustIds.filter((k) => criteria[k].status === "fail");
  const warned = Object.keys(criteria).filter((k) => criteria[k].status === "warn");
  const skipped = Object.keys(criteria).filter((k) => criteria[k].status === "skipped");
  const exitCode = failedMust.length > 0 ? 1 : 0;

  // ---- 根因提示 ----
  const hints: string[] = [];
  if (a1Status === "fail" && maxZeroRun) {
    hints.push("A1:接缝 #" + (maxZeroRun.seamIndex + 1) + " 处出现 " + maxZeroRun.frames + " 帧全零,典型成因是 seek/解码器重启后 AudioTrack 欠载补零,或机制层主动补静音");
  }
  if (a2Status === "fail") {
    const worst = a2Deviations.reduce((a, b) => (Math.abs(b.deltaFrames) > Math.abs(a.deltaFrames) ? b : a));
    hints.push("A2:第 " + (worst.seamIndex + 1) + " 处帧距偏差 " + worst.deltaFrames + " 帧(正=多播/时间戳前跳,负=少播/回跳),baseline 的 REPEAT_ONE=seek 最容易出现此类偏差");
  }
  if (flushSteady > 0 || discSteady > 0) {
    hints.push("A4:稳态仍出现 flush=" + flushSteady + "/discontinuity=" + discSteady + ",说明机制层在环回时重建了 sink,不是真正稳态");
  }
  if (underrunSteadyDelta > 0) {
    hints.push("A4:稳态 underrun 增加 " + underrunSteadyDelta + ",音频管线出现欠载(听感上就是断音)");
  }
  if (maxGap != null && maxGap.gapMs > opts.feedGapThresholdMs) {
    hints.push("A4:最大喂数间隔 " + fmtNum(maxGap.gapMs, 1) + "ms(t=" + maxGap.fromTMs + "→" + maxGap.toTMs + "ms),超过 " + opts.feedGapThresholdMs + "ms 阈值,可能造成 underrun");
  }
  if (pts.discontinuityCount > 0) {
    const first = pts.discontinuities[0];
    hints.push("PTS:共 " + pts.discontinuityCount + " 处不连续(最大偏差 " + fmtNum(pts.maxAbsDeviationUs / 1000, 3) + "ms),首处 t=" + (first?.tMs ?? "?") + "ms 帧 " + (first?.framePos ?? "?") + ":" + (first?.prevPtsUs ?? "?") + "→" + (first?.ptsUs ?? "?") + "µs,直接暴露 seek/时间戳复位");
  }
  if (bStatus === "fail") {
    hints.push("B:轮间 PCM 不一致,说明每一轮并非由同一缓冲逐位重放(可能是重新解码/重新 seek 得到)");
  }
  if (a3Status === "warn" || a3Status === "fail") {
    hints.push("A3:总帧数与 前奏+N×L 不符,结合 A2 可判断是多播还是丢帧");
  }

  return {
    tool: "spike-analyze",
    version: VERSION,
    generatedAt: new Date().toISOString(),
    inputs: { wav: wavPath, jsonl: jsonlPath },
    wav: {
      path: wi.path,
      fileSize: wi.fileSize,
      formatTag: wi.formatTag,
      effectiveTag: wi.effectiveTag,
      codec: wi.codec,
      sampleRate: wi.sampleRate,
      channels: wi.channels,
      bitsPerSample: wi.bitsPerSample,
      bytesPerFrame: wi.bytesPerFrame,
      dataBytes: wi.dataBytes,
      declaredDataBytes: wi.declaredDataBytes,
      frames: wi.frames,
      durationMs: wi.durationMs,
      truncated: wi.truncated,
      trailingBytes: wi.trailingBytes,
      chunks: wi.chunks,
      warnings: wi.warnings,
    },
    log: {
      path: log.path,
      totalLines: log.totalLines,
      eventCount: log.eventCount,
      unknownEvents: log.unknownEvents,
      parseErrorCount: log.parseErrorCount,
      parseErrors: log.parseErrors,
      truncatedLastLine: log.truncatedLastLine,
      hasBom: log.hasBom,
      firstT: log.firstT,
      lastT: log.lastT,
      run: log.run,
      configure: log.configure,
      end: log.endEvent,
      errorEvents: log.errorEvents,
    },
    params,
    seams: {
      count: marks.length,
      source: seamRes.source,
      inferred: seamRes.inferred,
      marks,
      notes: seamRes.notes,
    },
    criteria,
    pts,
    hints,
    warnings: [...wi.warnings, ...warnings],
    verdict: {
      mustCriteria: mustIds,
      failedMust,
      warned,
      skipped,
      exitCode,
    },
  };
}

// ============================================================================
// 中文摘要渲染
// ============================================================================

function statusTag(status: string): string {
  switch (status) {
    case "pass":
      return "[PASS]";
    case "fail":
      return "[FAIL]";
    case "warn":
      return "[WARN]";
    case "skipped":
      return "[SKIP]";
    default:
      return "[????]";
  }
}

function renderHuman(report: any, opts: AnalyzeOptions): string {
  const L: string[] = [];
  const push = (s = "") => L.push(s);
  const rule = (title: string) => push("── " + title + " " + "─".repeat(Math.max(0, 44 - title.length * 2)));

  push("P0 探针取证分析 · " + report.version);
  push("生成时间: " + report.generatedAt);

  rule("输入");
  push("WAV : " + report.inputs.wav + " (" + safeSize(report.wav.fileSize) + ")");
  push("日志: " + report.inputs.jsonl);

  rule("WAV");
  const w = report.wav;
  push("编码: " + w.codec + " (formatTag=" + w.formatTag + (w.effectiveTag !== w.formatTag ? "→" + w.effectiveTag : "") + ") / " + w.sampleRate + " Hz / " + w.channels + " 声道 / " + w.bitsPerSample + " bit / " + w.bytesPerFrame + " B/帧");
  push("帧数: " + w.frames + " (" + fmtNum(w.durationMs, 3) + " ms),data " + w.dataBytes + " B(声明 " + w.declaredDataBytes + " B)" + (w.truncated ? ",已截断" : ""));
  if (w.trailingBytes > 0) push("  尾部 " + w.trailingBytes + " 字节不足一帧,已忽略");
  for (const warn of w.warnings) push("  ! " + warn);

  rule("日志");
  const lg = report.log;
  push("事件: " + lg.eventCount + " 条(" + lg.totalLines + " 行,未知事件 " + Object.keys(lg.unknownEvents).length + " 类,解析失败 " + lg.parseErrorCount + ",末行截断 " + (lg.truncatedLastLine ? "是" : "否") + ")");
  const r = lg.run ?? {};
  push("运行: mode=" + (r.mode ?? "?") + " aMs=" + (r.aMs ?? "—") + " bMs=" + (r.bMs ?? "—") + " seconds=" + (r.seconds ?? "—") + " tag=" + (r.tag ?? "—"));
  const cfg = lg.configure ?? {};
  push("配置: sampleRate=" + (cfg.sampleRate ?? "?") + " channels=" + (cfg.channels ?? "?") + " pcm=" + (cfg.pcm ?? "?") + " bytesPerFrame=" + (cfg.bytesPerFrame ?? "?"));
  if (lg.end) push("结束: totalFrames=" + (lg.end.totalFrames ?? "—") + " durationMs=" + (lg.end.durationMs ?? "—"));
  for (const err of lg.errorEvents.slice(0, 3)) push("  ! error: " + String(err.message ?? "").slice(0, 160));
  if (lg.parseErrorCount > 0) {
    for (const pe of lg.parseErrors.slice(0, 3)) push("  ! 第 " + pe.line + " 行解析失败: " + pe.message);
  }

  rule("参数");
  const p = report.params;
  push("前奏: " + (p.preambleFrames ?? "—") + " 帧" + (p.preambleFrames != null ? " (" + fmtNum(framesToMs(p.preambleFrames, p.sampleRate), 3) + " ms)" : ""));
  push("L   : " + (p.loopFrames ?? "—") + " 帧" + (p.loopMs != null ? " (" + fmtNum(p.loopMs, 3) + " ms)" : ""));
  push("时长: " + fmtNum(p.durationMs, 1) + " ms(WAV " + fmtNum(p.wavDurationMs, 1) + " ms)");

  rule("接缝");
  const sm = report.seams;
  push("来源: " + sm.source + (sm.inferred ? "(由理论推断)" : "") + "," + sm.count + " 处");
  for (const note of sm.notes) push("  ! " + note);
  for (const m of sm.marks.slice(0, 30)) {
    const tm = m.tMs != null ? "t=" + fmtNum(m.tMs, 1) + "ms" : "t=—";
    const ptsStr = m.ptsUs != null ? "pts=" + m.ptsUs + "µs" : "pts=—";
    push("  #" + (m.index + 1) + " iter=" + (m.iter ?? "—") + " pos=" + (m.position ?? "—") + " (" + (m.position != null ? fmtNum(framesToMs(m.position, p.sampleRate), 3) : "—") + " ms) " + tm + " " + ptsStr + " 源=" + m.source);
  }
  if (sm.marks.length > 30) push("  ... 其余 " + (sm.marks.length - 30) + " 处省略(见 JSON)");

  rule("判据");
  const c = report.criteria;
  for (const key of ["A1", "A2", "A3", "A4", "B"]) {
    const cr = c[key];
    const mustStr = cr.must ? "(必须)" : "(参考)";
    push(statusTag(cr.status) + " " + cr.id + " " + cr.name + mustStr + ": " + cr.summary);
  }

  rule("详细");
  if (c.A1.maxZeroRun) {
    const z = c.A1.maxZeroRun;
    push("A1 最长静音: " + z.frames + " 帧 (" + fmtNum(z.ms, 3) + " ms),起点帧 " + z.startFrame + ",接缝 #" + (z.seamIndex + 1) + " (pos=" + z.seamPosition + ")");
    for (const sc of c.A1.seamChecks) {
      if (sc.runCount > 0) {
        push("   接缝 #" + (sc.seamIndex + 1) + " pos=" + sc.position + ": " + sc.runCount + " 段零值,最长 " + sc.longestRunFrames + " 帧,首段起点 " + sc.firstRunStartFrame);
      }
    }
  } else if (c.A1.seamChecks.length > 0) {
    push("A1 各接缝窗口零值段: 全部为 0");
  }
  if (c.A1.globalLongestZeroRun) {
    push("A1 全文件最长零值段: " + c.A1.globalLongestZeroRun.frames + " 帧 @ " + c.A1.globalLongestZeroRun.startFrame + "(仅供参考)");
  }
  for (const d of c.A2.deviations) {
    push("A2 接缝 #" + (d.seamIndex + 1) + ": " + d.fromPosition + " → " + d.toPosition + ",帧距 " + d.distanceFrames + ",理论 " + d.expectedFrames + ",偏差 " + d.deltaFrames + " 帧 (" + fmtNum(d.deltaMs, 3) + " ms)");
  }
  push("A3 期望: 前奏 " + (c.A3.preambleFrames ?? "—") + " + " + (c.A3.seamCount ?? 0) + "×" + (c.A3.loopFrames ?? "—") + " = " + (c.A3.expectedFrames ?? "—") + " 帧,实际 " + c.A3.totalFrames + " 帧,偏差 " + (c.A3.deviationFrames ?? "—") + " 帧");
  push("A4 稳态 [" + fmtNum(c.A4.steady.startMs, 0) + ", " + fmtNum(c.A4.steady.endMs, 0) + ") ms: flush " + c.A4.flush.steady + "/" + c.A4.flush.total + ", discontinuity " + c.A4.discontinuity.steady + "/" + c.A4.discontinuity.total + ", underrun 增量 " + c.A4.underrun.steadyDelta + "(全段 " + c.A4.underrun.wholeRunDelta + "), 最大喂数间隔 " + (c.A4.feed.maxGapMs != null ? fmtNum(c.A4.feed.maxGapMs, 2) + "ms" : "n/a"));
  for (const f of c.A4.flush.events) push("   flush @ t=" + f.t + "ms" + (f.steady ? " (稳态)" : ""));
  for (const g of c.A4.feed.topGaps) {
    if (g.gapMs > c.A4.feedGapThresholdMs) push("   超阈值喂数间隔 " + fmtNum(g.gapMs, 2) + "ms @ " + g.fromTMs + "→" + g.toTMs + "ms");
  }
  if (c.B.mismatches && c.B.mismatches.length > 0) {
    for (const mm of c.B.mismatches) {
      push("B 不一致: 第 " + (mm.roundIndex + 1) + " 轮帧 " + mm.roundFrame + "(绝对 " + mm.absFrame + "),声道 " + mm.channel + ",字节 " + mm.expectedByte + " vs " + mm.actualByte);
    }
  }
  if (c.B.rounds && c.B.rounds.length > 0) {
    push("B 轮次: " + c.B.rounds.length + " 轮,帧数 " + c.B.rounds.map((x: any) => x.frames).join(" / "));
  }

  rule("PTS 连续性");
  push("feed " + report.pts.feedCount + " 条(含 pts " + report.pts.ptsFeedCount + " 条),阈值 " + report.pts.thresholdUs + " µs,不连续 " + report.pts.discontinuityCount + " 处,累计漂移 " + (report.pts.cumulativeDriftUs != null ? fmtNum(report.pts.cumulativeDriftUs, 1) + " µs" : "n/a"));
  for (const d of report.pts.discontinuities.slice(0, 20)) {
    push("  t=" + (d.tMs ?? "—") + "ms 帧 " + d.framePos + ": " + d.prevPtsUs + " → " + d.ptsUs + " µs (Δ=" + fmtNum(d.deltaUs, 1) + ",期望 " + fmtNum(d.expectedUs, 1) + ",偏差 " + fmtNum(d.deviationUs, 1) + " µs," + d.kind + ")");
  }
  if (report.pts.discontinuityCount > 20) push("  ... 其余 " + (report.pts.discontinuityCount - 20) + " 处省略(见 JSON)");

  if (report.hints.length > 0) {
    rule("根因提示");
    for (const h of report.hints) push("* " + h);
  }
  if (report.warnings.length > 0) {
    rule("警告");
    for (const warn of report.warnings) push("! " + warn);
  }

  rule("结论");
  const v = report.verdict;
  push("检查的必须判据: " + v.mustCriteria.join(", "));
  push("未通过: " + (v.failedMust.length > 0 ? v.failedMust.join(", ") : "无"));
  if (v.warned.length > 0) push("警告: " + v.warned.join(", "));
  if (v.skipped.length > 0) push("跳过: " + v.skipped.join(", "));
  push("退出码: " + v.exitCode + "(" + (v.exitCode === 0 ? "必须判据全部通过" : "存在必须判据失败") + ")");

  return L.join("\n");
}

// ============================================================================
// 自测(--selftest):现场生成 fixtures 并断言判定正确
// ============================================================================

const FIX_SR = 44100;
const FIX_CH = 2;
const FIX_TONE_HZ = 997;

function toneSample(localFrame: number, ch: number): number {
  const amp = ch === 0 ? 0.3 : 0.18;
  const phase = ch === 0 ? 0 : 0.7;
  return Math.round(32767 * amp * Math.sin((2 * Math.PI * FIX_TONE_HZ * localFrame) / FIX_SR + phase));
}

function makeTone(frames: number, localStart = 0): Int16Array {
  const out = new Int16Array(frames * FIX_CH);
  for (let f = 0; f < frames; f++) {
    for (let c = 0; c < FIX_CH; c++) out[f * FIX_CH + c] = toneSample(localStart + f, c);
  }
  return out;
}

function makeSilence(frames: number): Int16Array {
  return new Int16Array(frames * FIX_CH);
}

function concatPcm(parts: Int16Array[]): Int16Array {
  let total = 0;
  for (const p of parts) total += p.length;
  const out = new Int16Array(total);
  let off = 0;
  for (const p of parts) {
    out.set(p, off);
    off += p.length;
  }
  return out;
}

function pcm16WavBuffer(samples: Int16Array): Buffer {
  const bpf = FIX_CH * 2;
  const dataBytes = samples.length * 2;
  const buf = Buffer.alloc(44 + dataBytes);
  buf.write("RIFF", 0, "ascii");
  buf.writeUInt32LE(36 + dataBytes, 4);
  buf.write("WAVE", 8, "ascii");
  buf.write("fmt ", 12, "ascii");
  buf.writeUInt32LE(16, 16);
  buf.writeUInt16LE(1, 20);
  buf.writeUInt16LE(FIX_CH, 22);
  buf.writeUInt32LE(FIX_SR, 24);
  buf.writeUInt32LE(FIX_SR * bpf, 28);
  buf.writeUInt16LE(bpf, 32);
  buf.writeUInt16LE(16, 34);
  buf.write("data", 36, "ascii");
  buf.writeUInt32LE(dataBytes, 40);
  for (let i = 0; i < samples.length; i++) buf.writeInt16LE(samples[i], 44 + i * 2);
  return buf;
}

function ev(name: string, t: number, extra: Record<string, any> = {}): any {
  return { t, ev: name, ...extra };
}

function baseLogEvents(mode: string, aMs: number | null, bMs: number | null): any[] {
  const events: any[] = [];
  const runExtra: any = { mode, path: "/sdcard/Music/fixture.mp3", seconds: 3, loops: 100, tag: "selftest" };
  if (aMs != null) runExtra.aMs = aMs;
  if (bMs != null) runExtra.bMs = bMs;
  events.push(ev("run", 0, runExtra));
  events.push(ev("configure", 0, { sampleRate: FIX_SR, channels: FIX_CH, pcm: "pcm16", bytesPerFrame: FIX_CH * 2 }));
  return events;
}

function makeFeed(t: number, frames: number, ptsUs: number): any {
  return ev("feed", t, { frames, ptsUs });
}

function makeTrack(t: number): any {
  return ev("track", t, { underrun: 0, headFrames: Math.round((t / 1000) * FIX_SR), state: "playing" });
}

async function writeFixture(name: string, wav: Buffer, events: any[]): Promise<{ wavPath: string; jsonlPath: string }> {
  const wavPath = path.join(FIXTURE_DIR, name + ".wav");
  const jsonlPath = path.join(FIXTURE_DIR, name + ".jsonl");
  await writeFile(wavPath, wav);
  await writeFile(jsonlPath, events.map((e) => JSON.stringify(e)).join("\n") + "\n", "utf8");
  return { wavPath, jsonlPath };
}

async function selftest(): Promise<number> {
  await mkdir(FIXTURE_DIR, { recursive: true });
  let passed = 0;
  let failed = 0;

  const expect = (label: string, cond: boolean, detail?: string) => {
    if (cond) {
      passed++;
      console.log("  [OK]   " + label);
    } else {
      failed++;
      console.log("  [FAIL] " + label + (detail ? "  :: " + detail : ""));
    }
  };

  const runAnalyze = async (f: { wavPath: string; jsonlPath: string }, overrides: Partial<AnalyzeOptions> = {}) => {
    const opts = { ...defaultOptions(), ...overrides };
    return analyze(f.wavPath, f.jsonlPath, opts);
  };

  console.log("fixtures 目录: " + FIXTURE_DIR);
  console.log("生成并分析 6 个场景 ...\n");

  // -------- 场景 1:连续正弦,无接缝 -> A1/A2/A4 PASS --------
  {
    console.log("── 场景 1:连续正弦(无接缝),A1/A2/A4 应 PASS ──");
    const totalFrames = FIX_SR * 3; // 3s
    const pcm = makeTone(totalFrames);
    const events = baseLogEvents("plain", null, null);
    const chunk = 441;
    const chunkCount = Math.floor(totalFrames / chunk);
    for (let i = 0; i < chunkCount; i++) events.push(makeFeed(i * 10, chunk, Math.round((i * chunk * 1e6) / FIX_SR)));
    for (let t = 0; t < 3000; t += 500) events.push(makeTrack(t));
    events.push(ev("end", 3000, { totalFrames, durationMs: 3000 }));
    const f = await writeFixture("01-continuous-sine", pcm16WavBuffer(pcm), events);
    const rep = await runAnalyze(f);
    const c = rep.criteria;
    console.log("  结果: A1=" + c.A1.status + " A2=" + c.A2.status + " A3=" + c.A3.status + " A4=" + c.A4.status + " B=" + c.B.status);
    console.log("  A1: " + c.A1.summary);
    console.log("  A4: " + c.A4.summary);
    expect("A1 PASS(无接缝,空真)", c.A1.status === "pass", c.A1.status);
    expect("A2 PASS(无接缝,空真)", c.A2.status === "pass", c.A2.status);
    expect("A4 PASS", c.A4.status === "pass", c.A4.summary);
    expect("B SKIP(无 seam 事件)", c.B.status === "skipped", c.B.status);
    expect("退出码 0", rep.verdict.exitCode === 0, String(rep.verdict.exitCode));
  }

  // -------- 场景 2:接缝插入 5ms 静音 -> A1 FAIL 且定位正确 --------
  {
    console.log("\n── 场景 2:接缝插入 5ms 静音,A1 应 FAIL 并定位到帧 " + msToFrames(5, FIX_SR) + " ──");
    const preamble = FIX_SR; // aMs=1000
    const silenceFrames = msToFrames(5, FIX_SR); // 221
    const loopFrames = FIX_SR;
    const totalFrames = preamble + silenceFrames + loopFrames;
    const pcm = concatPcm([makeTone(preamble), makeSilence(silenceFrames), makeTone(loopFrames)]);
    const events = baseLogEvents("baseline", 1000, 2000);
    for (let i = 0; i < 100; i++) events.push(makeFeed(i * 10, 441, Math.round((i * 441 * 1e6) / FIX_SR)));
    events.push(ev("seam", 1000, { iter: 1, outFrames: preamble, ptsUs: 1000000 }));
    events.push(makeFeed(1000, silenceFrames, 1000000));
    for (let j = 0; j < 100; j++) events.push(makeFeed(1010 + j * 10, 441, 1000000 + (j + 1) * 10000));
    for (let t = 0; t <= 2000; t += 500) events.push(makeTrack(t));
    events.push(ev("end", 2100, { totalFrames, durationMs: 2100 }));
    const f = await writeFixture("02-seam-silence-5ms", pcm16WavBuffer(pcm), events);
    const rep = await runAnalyze(f);
    const a1 = rep.criteria.A1;
    console.log("  结果: A1=" + a1.status + " maxZeroRun=" + JSON.stringify(a1.maxZeroRun));
    console.log("  A1: " + a1.summary);
    expect("A1 FAIL", a1.status === "fail", a1.status);
    expect("静音长度 = " + silenceFrames + " 帧", a1.maxZeroRun?.frames === silenceFrames, String(a1.maxZeroRun?.frames));
    expect("静音起点 = 帧 " + preamble, a1.maxZeroRun?.startFrame === preamble, String(a1.maxZeroRun?.startFrame));
    expect("静音归属接缝 #1", a1.maxZeroRun?.seamIndex === 0, String(a1.maxZeroRun?.seamIndex));
    expect("WAV 总帧数 = " + totalFrames, rep.wav.frames === totalFrames, String(rep.wav.frames));
  }

  // -------- 场景 3:接缝帧距比理论多 100 帧 -> A2 FAIL --------
  {
    console.log("\n── 场景 3:第 2 段接缝帧距 = L+100,A2 应 FAIL(偏差 +100) ──");
    const preamble = FIX_SR;
    const loopFrames = FIX_SR;
    const round1 = loopFrames + 100;
    const round2 = loopFrames + 100;
    const totalFrames = preamble + round1 + round2;
    const pcm = concatPcm([makeTone(preamble), makeTone(round1), makeTone(round2)]);
    const events = baseLogEvents("baseline", 1000, 2000);
    const chunks: Array<{ frames: number; ptsUs: number }> = [];
    let rem = totalFrames;
    let cum = 0;
    let idx = 0;
    while (rem > 0) {
      const n = Math.min(441, rem);
      const offsetUs = idx >= 200 ? 20000 : 0; // 在 seam2 附近伪造一次 PTS 前跳
      chunks.push({ frames: n, ptsUs: Math.round((cum * 1e6) / FIX_SR) + offsetUs });
      cum += n;
      rem -= n;
      idx++;
    }
    for (let i = 0; i < chunks.length; i++) {
      events.push(makeFeed(i * 10, chunks[i].frames, chunks[i].ptsUs));
    }
    events.push(ev("seam", 1000, { iter: 1, outFrames: preamble, ptsUs: 1000000 }));
    events.push(ev("seam", 2002, { iter: 2, outFrames: preamble + round1, ptsUs: 2002000 }));
    for (let t = 0; t <= 3000; t += 500) events.push(makeTrack(t));
    events.push(ev("end", 3100, { totalFrames, durationMs: 3100 }));
    const f = await writeFixture("03-seam-drift-100", pcm16WavBuffer(pcm), events);
    const rep = await runAnalyze(f);
    const a2 = rep.criteria.A2;
    const dev = a2.deviations[0];
    console.log("  结果: A2=" + a2.status + " deviations=" + JSON.stringify(a2.deviations));
    console.log("  A2: " + a2.summary);
    console.log("  PTS 不连续: " + rep.pts.discontinuityCount + " 处,首处 " + JSON.stringify(rep.pts.discontinuities[0] ?? null));
    expect("A2 FAIL", a2.status === "fail", a2.status);
    expect("帧距偏差 = +100 帧", dev?.deltaFrames === 100, String(dev?.deltaFrames));
    expect("帧距 = " + round1 + " 帧", dev?.distanceFrames === round1, String(dev?.distanceFrames));
    expect("PTS 检测到不连续", rep.pts.discontinuityCount >= 1, String(rep.pts.discontinuityCount));
    expect("A1 PASS(正弦无零值)", rep.criteria.A1.status === "pass", rep.criteria.A1.summary);
  }

  // -------- 场景 4:三轮 PCM 逐位相同 -> B PASS;改一个采样 -> B FAIL --------
  {
    console.log("\n── 场景 4:三轮 PCM 逐位相同 -> B PASS;第 3 轮改 1 个采样 -> B FAIL ──");
    const preamble = FIX_SR;
    const loopFrames = FIX_SR;
    const rounds = 3;
    const totalFrames = preamble + rounds * loopFrames;
    const pcm = concatPcm([makeTone(preamble), makeTone(loopFrames), makeTone(loopFrames), makeTone(loopFrames)]);
    const events = baseLogEvents("baseline", 1000, 2000);
    for (let i = 0; i < totalFrames / 441; i++) events.push(makeFeed(i * 10, 441, Math.round((i * 441 * 1e6) / FIX_SR)));
    for (let k = 0; k < rounds; k++) events.push(ev("seam", 1000 + k * 1000, { iter: k + 1, outFrames: preamble + k * loopFrames, ptsUs: (k + 1) * 1000000 }));
    for (let t = 0; t <= 4000; t += 500) events.push(makeTrack(t));
    events.push(ev("end", 4000, { totalFrames, durationMs: 4000 }));
    const f = await writeFixture("04-identical-rounds", pcm16WavBuffer(pcm), events);
    const rep = await runAnalyze(f);
    console.log("  结果(相同): B=" + rep.criteria.B.status + "  A2=" + rep.criteria.A2.status);
    console.log("  B: " + rep.criteria.B.summary);
    expect("B PASS", rep.criteria.B.status === "pass", rep.criteria.B.summary);
    expect("A2 PASS(帧距 = L)", rep.criteria.A2.status === "pass", rep.criteria.A2.summary);

    // 变体:第 3 轮(roundIndex 2)帧 12345 的声道 0 改一个采样
    const badFrame = preamble + 2 * loopFrames + 12345;
    const badPcm = new Int16Array(pcm);
    const sampleIndex = badFrame * FIX_CH + 0;
    badPcm[sampleIndex] = badPcm[sampleIndex] === 32767 ? badPcm[sampleIndex] - 1 : badPcm[sampleIndex] + 1;
    const fBad = await writeFixture("04b-identical-rounds-tampered", pcm16WavBuffer(badPcm), events);
    const repBad = await runAnalyze(fBad);
    const bBad = repBad.criteria.B;
    console.log("  结果(篡改): B=" + bBad.status + " mismatches=" + JSON.stringify(bBad.mismatches.slice(0, 2)));
    console.log("  B: " + bBad.summary);
    expect("B FAIL(篡改后)", bBad.status === "fail", bBad.status);
    expect("不一致位置 = 帧 " + badFrame, bBad.mismatches[0]?.absFrame === badFrame, String(bBad.mismatches[0]?.absFrame));
    expect("不一致轮次 = 第 3 轮", bBad.mismatches[0]?.roundIndex === 2, String(bBad.mismatches[0]?.roundIndex));
  }

  // -------- 场景 5:伪造一次 flush + 20ms 喂数间隔 -> A4 FAIL --------
  {
    console.log("\n── 场景 5:稳态伪造 1 次 flush + 20ms 喂数间隔 -> A4 应 FAIL ──");
    const totalFrames = FIX_SR * 2.5; // 2.5s,稳态 [1000,1500)
    const pcm = makeTone(totalFrames);
    const events = baseLogEvents("baseline", 1000, 2000);
    for (let i = 0; i < Math.floor(totalFrames / 441); i++) {
      const t = i * 10 + (i >= 121 ? 10 : 0); // 1200→1220,20ms
      events.push(makeFeed(t, 441, Math.round((i * 441 * 1e6) / FIX_SR)));
    }
    events.push(ev("flush", 1200, {}));
    for (let t = 0; t <= 2500; t += 500) events.push(makeTrack(t));
    events.push(ev("end", 2500, { totalFrames, durationMs: 2500 }));
    const f = await writeFixture("05-flush-and-gap", pcm16WavBuffer(pcm), events);
    const rep = await runAnalyze(f);
    const a4 = rep.criteria.A4;
    console.log("  结果: A4=" + a4.status + " flush.steady=" + a4.flush.steady + " maxGapMs=" + a4.feed.maxGapMs);
    console.log("  A4: " + a4.summary);
    expect("A4 FAIL", a4.status === "fail", a4.status);
    expect("稳态 flush = 1", a4.flush.steady === 1, String(a4.flush.steady));
    expect("最大喂数间隔 = 20ms", a4.feed.maxGapMs === 20, String(a4.feed.maxGapMs));
    expect("退出码 1", rep.verdict.exitCode === 1, String(rep.verdict.exitCode));
  }

  // -------- 场景 5b:无 flush,仅 45ms 喂数间隔 -> A4 FAIL(间隔报警) --------
  {
    console.log("\n── 场景 5b:无 flush,稳态 45ms 喂数间隔 -> A4 应 FAIL(>30ms 报警) ──");
    const totalFrames = FIX_SR * 3;
    const pcm = makeTone(totalFrames);
    const events = baseLogEvents("baseline", 1000, 2000);
    for (let i = 0; i < Math.floor(totalFrames / 441); i++) {
      const t = i * 10 + (i >= 121 ? 35 : 0); // 1200→1245,45ms
      events.push(makeFeed(t, 441, Math.round((i * 441 * 1e6) / FIX_SR)));
    }
    for (let t = 0; t <= 3000; t += 500) events.push(makeTrack(t));
    events.push(ev("end", 3000, { totalFrames, durationMs: 3000 }));
    const f = await writeFixture("05b-feed-gap-45ms", pcm16WavBuffer(pcm), events);
    const rep = await runAnalyze(f);
    const a4 = rep.criteria.A4;
    console.log("  结果: A4=" + a4.status + " flush.steady=" + a4.flush.steady + " maxGapMs=" + a4.feed.maxGapMs);
    console.log("  A4: " + a4.summary);
    expect("A4 FAIL(喂数间隔报警)", a4.status === "fail", a4.status);
    expect("稳态 flush = 0", a4.flush.steady === 0, String(a4.flush.steady));
    expect("最大喂数间隔 = 45ms", a4.feed.maxGapMs === 45, String(a4.feed.maxGapMs));
  }

  console.log("\n自测结果: " + passed + " 通过," + failed + " 失败");
  if (failed === 0) console.log("全部自测场景通过。");
  return failed === 0 ? 0 : 1;
}

// ============================================================================
// CLI
// ============================================================================

function printHelp(): void {
  console.log([
    "spike-analyze —— P0 探针取证分析器",
    "",
    "用法:",
    "  bun run tools/spike-analyze.ts <tap.wav> <tap.jsonl> [选项]",
    "  bun run tools/spike-analyze.ts --selftest",
    "",
    "选项:",
    "  --json <path>             机器可读 JSON 同时写入文件",
    "  --json-only               只输出 JSON",
    "  --seam-window-ms <n>      A1 零值检测窗口半径(ms),默认 10",
    "  --zero-min-run <n>        静音段最小连续帧数,默认 1",
    "  --steady-start-ms <n>     稳态起点,默认 1000",
    "  --steady-end-ms <n>       稳态终点(从时长里扣),默认 1000",
    "  --feed-gap-ms <n>         喂数间隔阈值,默认 30",
    "  --pts-threshold-us <n>    PTS 不连续阈值(µs),默认 1000",
    "  --max-mismatches <n>      B 每轮最多报告的不一致样本数,默认 10",
    "  --strict                  A3/B 也作为必须判据",
    "  --help                    显示本帮助",
    "",
    "退出码:0 必须判据全部通过;1 有必须判据失败;2 参数/文件/格式错误。",
  ].join("\n"));
}

interface CliConfig {
  files: string[];
  selftest: boolean;
  jsonPath: string | null;
  jsonOnly: boolean;
  help: boolean;
  opts: AnalyzeOptions;
}

function parseArgs(argv: string[]): CliConfig {
  const cfg: CliConfig = {
    files: [],
    selftest: false,
    jsonPath: null,
    jsonOnly: false,
    help: false,
    opts: defaultOptions(),
  };
  const needNum = (name: string, raw: string | undefined): number => {
    const n = Number(raw);
    if (raw == null || !Number.isFinite(n)) throw new Error("选项 " + name + " 需要一个数字,收到: " + (raw ?? "(缺失)"));
    return n;
  };

  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === "--selftest") cfg.selftest = true;
    else if (a === "--help" || a === "-h") cfg.help = true;
    else if (a === "--json") cfg.jsonPath = argv[++i] ?? null;
    else if (a === "--json-only") cfg.jsonOnly = true;
    else if (a === "--seam-window-ms") cfg.opts.seamWindowMs = needNum(a, argv[++i]);
    else if (a === "--zero-min-run") cfg.opts.zeroMinRun = needNum(a, argv[++i]);
    else if (a === "--steady-start-ms") cfg.opts.steadyStartMs = needNum(a, argv[++i]);
    else if (a === "--steady-end-ms") cfg.opts.steadyEndMs = needNum(a, argv[++i]);
    else if (a === "--feed-gap-ms") cfg.opts.feedGapThresholdMs = needNum(a, argv[++i]);
    else if (a === "--pts-threshold-us") cfg.opts.ptsThresholdUs = needNum(a, argv[++i]);
    else if (a === "--max-mismatches") cfg.opts.maxMismatches = needNum(a, argv[++i]);
    else if (a === "--strict") cfg.opts.strict = true;
    else if (a === "--no-color") {
      /* 保留参数,当前无 ANSI 颜色 */
    } else if (a.startsWith("--")) throw new Error("未知选项: " + a);
    else cfg.files.push(a);
  }
  if (cfg.opts.seamWindowMs < 0) throw new Error("--seam-window-ms 不能为负");
  if (cfg.opts.zeroMinRun < 1) throw new Error("--zero-min-run 必须 >= 1");
  if (cfg.opts.maxMismatches < 1) throw new Error("--max-mismatches 必须 >= 1");
  return cfg;
}

async function main(): Promise<void> {
  let cfg: CliConfig;
  try {
    cfg = parseArgs(process.argv.slice(2));
  } catch (e: any) {
    console.error("参数错误: " + (e?.message ?? e));
    process.exit(2);
  }

  if (cfg.help || process.argv.length <= 2) {
    printHelp();
    process.exit(0);
  }

  if (cfg.selftest) {
    const code = await selftest();
    process.exit(code);
  }

  if (cfg.files.length < 2) {
    console.error("用法: bun run tools/spike-analyze.ts <tap.wav> <tap.jsonl> [选项](或 --selftest)");
    process.exit(2);
  }

  const [wavPath, jsonlPath] = cfg.files;
  let report: any;
  try {
    report = await analyze(wavPath, jsonlPath, cfg.opts);
  } catch (e: any) {
    console.error("分析失败: " + (e?.message ?? e));
    if (e?.stack) console.error(String(e.stack).split("\n").slice(1, 4).join("\n"));
    process.exit(2);
  }

  if (!cfg.jsonOnly) {
    console.log(renderHuman(report, cfg.opts));
  }
  const jsonText = JSON.stringify(report, null, 2);
  if (cfg.jsonPath) {
    try {
      await writeFile(cfg.jsonPath, jsonText + "\n", "utf8");
      if (!cfg.jsonOnly) {
        console.log("");
        console.log("机器可读 JSON 已写入: " + cfg.jsonPath);
      }
    } catch (e: any) {
      console.error("写入 JSON 失败: " + (e?.message ?? e));
      process.exit(2);
    }
  }
  if (!cfg.jsonOnly) {
    console.log("");
    console.log("===== JSON =====");
  }
  console.log(jsonText);
  process.exit(report.verdict.exitCode);
}

await main();
