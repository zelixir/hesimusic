#!/usr/bin/env bun
/**
 * tools/scenarios/scenarios-prelude.ts —— 「前奏 / 尾奏 / 进循环起点」场景（issue 00024 第三批，覆盖 00028）。
 *
 * 为什么补这一批
 * --------------
 * 00028 = 启动无缝循环后直接跳到 A、且 seek 不到 A 之前。48 条既有场景几乎都假设
 * 「已经在循环里、位置在 [A,B) 内」，没有一条测「从区间外进入 / 退出」「进循环时的起始位置」
 * 「前奏段 / 尾奏段的行为」。本组把主播放器当作一个**三段相位状态机**来测：
 *
 *   prelude : display < A   正常播放（进循环统一从曲首 0 起播；不是从 A 起）
 *   loop    : A <= display < B   到 B 折回 A
 *   tail    : display > B   只有 seek 到 B 之后才进入；播到曲末回 0（不是回 A）
 *
 * 判据口径（lead 2026-11-21 定稿；实现方向 = 扩展环带源为整曲时间轴）
 * -----------------------------------------------------------------
 *   1. 进循环：/state.playback.positionMs 从 0 附近开始（不是 A），phase=prelude
 *   2. prelude 播到 A：继续播（不跳），phase 转 loop
 *   3. loop 播到 B：位置回到 A（内容真值确认听到的是 A）
 *   4. seek 到 A 之前：能播，phase=prelude，内容与 display 一致
 *   5. seek 到 B 之后：能播，phase=tail；播到曲末后位置回 0、phase=prelude
 *   6. tail 中 seek 回 [A,B)：phase=loop，B 处重新折返
 *   7. 以上每一步都用内容真值（tap vs decode-range 的 NCC，peak>=0.90），不只比位置
 *
 * phase 字段
 * ----------
 * 实现方正在给 /state.loop 加 phase 字段。取口径顺序：
 *   /state.loop.phase  ->  /state.playback.phase  ->  按 display 与 A/B 推断
 * 每次读相位都把「用的是哪种口径」写进判据明细与证据（phaseSource），
 * 这样"实现尚未落 phase 字段"不会被误判成"产品没有相位语义"。
 *
 * 内容真值（沿用 content-probe.ts，不改）
 * --------------------------------------
 *   实际写出（/debug/tap -> WAV）与 /debug/decode-range 从原始文件解出的应听片段做 NCC：
 *     peak >= 0.90 且 |偏差| <= 600ms -> match
 *     peak 0.50~0.90                  -> suspect（FAIL，先排除对齐/音量）
 *     peak < 0.50                     -> mismatch（FAIL，宽域定位报告实际听到的是哪段）
 *   探针不可用（tap 没写出帧 / build 没 decode-range）-> PENDING（依赖 tap-content-probe），
 *   不会假装 PASS；同时"出声"另有 invariantProbe.writtenFrames 判据兜底。
 *
 * 00028 未修时的预期失败点（本文件用注释标了 [EXPECTED-FAIL 00028]）
 * --------------------------------------------------------------
 *   旧实现（环带源时间轴只有 [A,B)，T=0 即 A）：
 *     - 进循环一律落到 A（进循环起点矩阵 8 点全 FAIL：位置、phase；内容与 display 仍一致）；
 *     - seek 到 A 之前被 clamp 到 A；seek 到 B 之后被 clamp 到 B-1ms（tail 不可达）；
 *     - 前奏段永远观察不到（D 从 A 开始）。
 *   这些 FAIL 正是"判据有效"的证明：修复后应自动转 PASS，无需改判据。
 *
 * 写域 / 注册
 * -----------
 *   本文件只新增，不改 scenarios.ts / run-scenarios.ts / harness.ts / 其它 scenarios-*.ts。
 *   由 lead 合并：
 *     import { PRELUDE_SCENARIOS } from "./scenarios-prelude";
 *     export const SCENARIOS: Scenario[] = [..., ...PRELUDE_SCENARIOS];
 *   本组**不依赖** uiautomator / 结果页预览（只走主播放器 + /debug/tap + /debug/decode-range），
 *   所以不需要并入 runner 的 UI_SCENARIOS（PRELUDE_UI_SCENARIO_IDS 为空）。
 *   68/69 会写 song_loop 行（/confirm），已在 finally 里按原 sample 精确还原；建议独占窗口跑。
 *
 * 场景表（id 60..70）
 * -------------------
 *   60 enter-start-matrix        进循环起点矩阵（0/A-30s/A-1s/A/A+1s/B-1s/B/B+5s）-> 都应从曲首前奏起播
 *   61 prelude-to-loop-transition 前奏播到 A：继续播不跳，phase 转 loop
 *   62 loop-wrap-at-b-content    loop 播到 B：位置回 A，内容真值确认听到的是 A
 *   63 seek-before-a-prelude     循环中 seek 到 A 之前：能播、phase=prelude、内容=请求位置
 *   64 seek-past-b-tail          循环中 seek 到 B 之后：能播、phase=tail、内容=显示
 *   65 tail-to-end-zero          尾奏播到曲末：位置回 0（不是 A）、phase=prelude、出声
 *   66 tail-back-to-loop         tail 中 seek 回 [A,B)：phase=loop，B 处重新折返（内容真值）
 *   67 exit-loop-media-timeline  出循环后位置回媒体时间轴（不是停在环带 T），且可 seek 到 A 之前
 *   68 move-a-past-current       循环中把 A 改到播放头之后：位置/相位/内容自洽
 *   69 move-b-before-current     循环中把 B 改到播放头之前：位置/相位/内容自洽（tail + 回环带）
 *   70 enter-no-jump-a           播放中进循环，80ms 采样监控：从不跳到 A，落在前奏
 */

import { check, measureMainFrames, msToSamples, pendingCheck, round, sleep, waitFor } from "./harness";
import type { Check, FrameMeasurement, SongInfo, State } from "./harness";
import { circularDelta, createProber, defaultWorkDir, PEAK_SUSPECT } from "./content-probe";
import type { ProbeResult, ProbeStack, TapCapture } from "./content-probe";
import type { Scenario, ScenarioCtx, ScenarioRunResult } from "./scenarios";

/**
 * SkipError 必须运行时懒取（与 scenarios-content.ts 同一原因）：
 * scenarios.ts -> import PRELUDE_SCENARIOS -> 本文件 -> import scenarios.ts 是循环依赖，
 * 模块初始化期直接取会命中 TDZ。懒取保证 instanceof 用的仍是同一个类。
 */
let skipErrorCtor: (new (message: string) => Error) | null = null;

function skipCtor(): new (message: string) => Error {
  if (!skipErrorCtor) {
    const mod: any = require("./scenarios");
    skipErrorCtor = mod.SkipError as new (message: string) => Error;
  }
  return skipErrorCtor!;
}

function newSkipError(message: string): Error {
  const Ctor = skipCtor();
  return new Ctor(message);
}

// ---------------------------------------------------------------------------
// 常量
// ---------------------------------------------------------------------------

const DWELL_MS = 900;
const SAMPLES_MS = 300;
const DRIFT_TOL_MS = 600;
const BASELINE_PEAK_MIN = 0.95;
/** 进循环后"前奏起点"的窗口：从 0 起播，等待 + 采样约 1~2s，给到 5s 余量。 */
const PRELUDE_START_MAX_MS = 5000;

// ---------------------------------------------------------------------------
// 基础类型
// ---------------------------------------------------------------------------

export type LoopPhase = "prelude" | "loop" | "tail";

export interface PhaseReading {
  /** 当前相位；null = 既没有 phase 字段也没有窗口可推断（普通播放）。 */
  phase: LoopPhase | null;
  /** 判据口径：实现字段 / 旧字段 / 按 display 推断 / 不可用。 */
  source: "loop.phase" | "playback.phase" | "inferred" | "none";
  /** 原始字段值或推断依据（写进证据）。 */
  raw: string | null;
}

export interface MainLoop {
  song: SongInfo;
  songPath: string;
  sampleRate: number;
  aMs: number;
  bMs: number;
  durationMs: number;
  L: number;
  /** 原 loop 行（写库场景 finally 精确还原）。 */
  origASamples: number;
  origBSamples: number;
}

/** 写入超前播放的偏移：环带口径与自由/前奏口径分开测（同一 sink，但可能不同源路径）。 */
export interface Offsets {
  band: number | null;
  file: number | null;
}

function resolvedOffsets(o: Offsets): { band: number; file: number } {
  const band = o.band ?? o.file ?? 0;
  const file = o.file ?? o.band ?? 0;
  return { band, file };
}

interface MainSample {
  atMs: number;
  posMs: number | null;
  phase: LoopPhase | null;
  phaseSource: PhaseReading["source"];
  engine: string;
  playing: boolean;
  claimActive: boolean;
}

// ---------------------------------------------------------------------------
// 小工具
// ---------------------------------------------------------------------------

function loopWindowOf(state: State): { songId: number; aMs: number; bMs: number } | null {
  const w = state?.loop?.window;
  if (!w) return null;
  return {
    songId: Number(w.songId),
    aMs: Math.round(Number(w.loopStartUs) / 1000),
    bMs: Math.round(Number(w.loopEndUs) / 1000),
  };
}

function claimActiveOf(state: State | null): boolean {
  if (!state) return false;
  const v = (state as any).loop?.claimActive;
  return v === true || (v === undefined && state.playback?.engine === "loop");
}

function normalizePhase(v: any): LoopPhase | null {
  if (v == null) return null;
  const s = String(v).trim().toLowerCase();
  if (s === "prelude" || s === "pre" || s === "intro") return "prelude";
  if (s === "loop" || s === "band" || s === "looping") return "loop";
  if (s === "tail" || s === "outro" || s === "post") return "tail";
  return null;
}

function inferPhase(displayMs: number, aMs: number, bMs: number): LoopPhase {
  if (displayMs < aMs) return "prelude";
  if (displayMs > bMs) return "tail";
  return "loop";
}

/**
 * 读相位：先认实现字段（/state.loop.phase，其次 /state.playback.phase），
 * 都没有时按**当前窗口**（优先 /state 里的窗口，而不是场景加载时的旧 A/B）推断。
 */
export function phaseOf(state: State, fallback: { aMs: number; bMs: number }): PhaseReading {
  const loopPhaseRaw = (state as any)?.loop?.phase;
  const pbPhaseRaw = (state as any)?.playback?.phase;
  const loopPhase = normalizePhase(loopPhaseRaw);
  if (loopPhase) return { phase: loopPhase, source: "loop.phase", raw: String(loopPhaseRaw) };
  const pbPhase = normalizePhase(pbPhaseRaw);
  if (pbPhase) return { phase: pbPhase, source: "playback.phase", raw: String(pbPhaseRaw) };
  const win = loopWindowOf(state);
  if (!win) return { phase: null, source: "none", raw: null };
  const d = Number(state?.playback?.positionMs);
  if (!Number.isFinite(d)) return { phase: null, source: "none", raw: null };
  return {
    phase: inferPhase(d, win.aMs, win.bMs),
    source: "inferred",
    raw: "display=" + Math.round(d) + "ms window=" + win.aMs + ".." + win.bMs,
  };
}

function phaseCheck(label: string, pr: PhaseReading, expected: LoopPhase, displayMs: number): Check {
  if (pr.phase === null) {
    return check(
      label,
      false,
      "拿不到相位: /state.loop.phase 与 /state.playback.phase 都没有，且没有窗口可推断",
      "实现应补 /state.loop.phase；在那之前本判据按 display 与 A/B 推断，缺窗口就无法判",
    );
  }
  const ok = pr.phase === expected;
  const sourceNote =
    pr.source === "inferred"
      ? "口径=按 display 推断(" + (pr.raw ?? "") + ")"
      : "口径=" + pr.source;
  return check(
    label,
    ok,
    "phase=" + pr.phase + " (期望 " + expected + "; " + sourceNote + "; display=" + Math.round(displayMs) + "ms)",
    ok
      ? undefined
      : "00028 定稿语义: prelude(display<A) / loop(A<=display<B) / tail(display>B)；当前相位与播放头位置矛盾",
  );
}

function audioCheck(name: string, m: FrameMeasurement, minRatio: number, hint?: string): Check {
  if (!m.available) {
    return check(name, false, "输出帧探针不可用: " + (m.reason ?? "unknown"), hint ?? "主播放器未走 SOURCE 路线或探测窗口太短");
  }
  const pct = round(m.ratio * 100, 1);
  return check(
    name,
    m.ratio >= minRatio,
    "Δ=" + m.deltaFrames + "f / " + m.spanMs + "ms = " + pct + "%(门槛 " + Math.round(minRatio * 100) + "%)",
    hint,
  );
}

function decodable(s: SongInfo): boolean {
  return /\.(mp3|wav|m4a|aac|flac|ogg|opus)$/i.test(s.path);
}

function factsOf(state: State | null, ml: MainLoop): any {
  if (!state) return { available: false };
  const pr = phaseOf(state, ml);
  return {
    songId: state.playback?.songId,
    positionMs: state.playback?.positionMs,
    playing: state.playback?.playing,
    playbackState: state.playback?.playbackState,
    engine: state.playback?.engine,
    claimActive: claimActiveOf(state),
    window: loopWindowOf(state),
    phase: pr.phase,
    phaseSource: pr.source,
    phaseRaw: pr.raw,
    repeatMode: state.playback?.repeatMode,
  };
}

// ---------------------------------------------------------------------------
// 选歌 / 载入环带事实
// ---------------------------------------------------------------------------

async function selectPreludeSong(
  ctx: ScenarioCtx,
  opts: {
    minA?: number;
    maxA?: number;
    minL?: number;
    maxL?: number;
    minTail?: number;
    preferLargeA?: boolean;
  } = {},
): Promise<SongInfo> {
  const all = await ctx.api.songsAll();
  const minA = opts.minA ?? 12000;
  const maxA = opts.maxA ?? Number.POSITIVE_INFINITY;
  const minL = opts.minL ?? 8000;
  const maxL = opts.maxL ?? 180000;
  const minTail = opts.minTail ?? 6000;
  let cands = all.filter(
    (s) =>
      s.hasLoop &&
      s.loopConfirmed &&
      s.loopStartMs != null &&
      s.loopEndMs != null &&
      decodable(s) &&
      Number(s.loopStartMs) >= minA &&
      Number(s.loopStartMs) <= maxA &&
      Number(s.loopEndMs) - Number(s.loopStartMs) >= minL &&
      Number(s.loopEndMs) - Number(s.loopStartMs) <= maxL &&
      s.durationMs - Number(s.loopEndMs) >= minTail,
  );
  if (cands.length === 0) {
    throw newSkipError(
      "曲库里没有满足约束的已确认循环歌（A∈[" +
        minA +
        "," +
        (Number.isFinite(maxA) ? maxA : "∞") +
        "]ms, 环长∈[" +
        minL +
        "," +
        maxL +
        "]ms, B 距文件尾>=" +
        minTail +
        "ms, 可解码）",
    );
  }
  if (opts.preferLargeA) {
    // A 越大，"跳 A"与"在前奏起点"越容易区分（A-30s 点也才真正存在）。
    cands.sort(
      (x, y) =>
        Number(Number(y.loopStartMs) >= 30000) - Number(Number(x.loopStartMs) >= 30000) ||
        Number(y.loopStartMs) - Number(x.loopStartMs) ||
        Number(x.loopEndMs) - Number(x.loopStartMs) - (Number(y.loopEndMs) - Number(y.loopStartMs)) ||
        x.id - y.id,
    );
  } else {
    // 默认挑最短环带：等待折返/整圈的场景时间才可控。
    cands.sort(
      (x, y) =>
        Number(x.loopEndMs) - Number(x.loopStartMs) - (Number(y.loopEndMs) - Number(y.loopStartMs)) ||
        Number(y.loopStartMs) - Number(x.loopStartMs) ||
        x.id - y.id,
    );
  }
  // 曲库可能残留磁盘上已删除的歌：逐个探文件，最多看 8 个。
  for (const s of cands.slice(0, 8)) {
    if (await ctx.adb.fileExists(s.path)) return s;
  }
  throw newSkipError(
    "前 8 个候选歌的文件都不在磁盘上（曲库记录与文件不一致）: " +
      cands
        .slice(0, 8)
        .map((s) => "#" + s.id + " " + s.path)
        .join(" | "),
  );
}

async function loadMainLoop(ctx: ScenarioCtx, song: SongInfo): Promise<MainLoop> {
  const info = await ctx.api.song(song.id);
  const aMs = Math.round(Number(info.loopStartMs));
  const bMs = Math.round(Number(info.loopEndMs));
  const durationMs = Number(info.durationMs);
  const sampleRate = Number(info.sampleRate ?? ctx.sampleRateFor(song.id)) || 44100;
  const songPath = String(info.path ?? song.path ?? "");
  if (!(bMs > aMs)) throw newSkipError("环带非法: A=" + aMs + " B=" + bMs + " (song " + song.id + ")");
  if (!songPath) throw newSkipError("拿不到歌曲文件路径，无法用 decode-range 解参考 PCM");
  if (!(durationMs > bMs)) throw newSkipError("时长/环带异常: duration=" + durationMs + " B=" + bMs);
  return {
    song,
    songPath,
    sampleRate,
    aMs,
    bMs,
    durationMs,
    L: bMs - aMs,
    origASamples: Number(info.loopStartSamples),
    origBSamples: Number(info.loopEndSamples),
  };
}

// ---------------------------------------------------------------------------
// 主播放器动作（不碰预览页 / uiautomator）
// ---------------------------------------------------------------------------

interface PlayResult {
  ok: boolean;
  state: State | null;
  elapsedMs: number;
  detail: string;
}

async function ensureSongPlaying(ctx: ScenarioCtx, songId: number): Promise<PlayResult> {
  const t0 = Date.now();
  const r = await ctx.api.post("/player/play", { songId, repeatOne: false });
  if (r.status !== 200) {
    return { ok: false, state: null, elapsedMs: Date.now() - t0, detail: "/player/play -> " + r.status + " " + r.text.slice(0, 160) };
  }
  const w = await waitFor(
    async () => ctx.api.state(),
    (s) => s.playback?.songId === songId && s.playback?.playing === true && s.playback?.playbackState === "ready",
    9000,
    100,
  );
  return {
    ok: w.ok,
    state: w.value,
    elapsedMs: Date.now() - t0,
    detail:
      "songId=" + songId + " ok=" + w.ok + " 耗时=" + w.elapsedMs + "ms" +
      (w.value ? " playing=" + w.value.playback.playing + " state=" + w.value.playback.playbackState : ""),
  };
}

interface SeekResult {
  ok: boolean;
  status: number;
  response: any;
  landing: number | null;
  latencyMs: number;
  detail: string;
}

async function seekMain(
  ctx: ScenarioCtx,
  targetMs: number,
  opts: { tolMs?: number; timeoutMs?: number } = {},
): Promise<SeekResult> {
  const tol = opts.tolMs ?? 500;
  const target = Math.round(targetMs);
  const t0 = Date.now();
  const r = await ctx.api.post("/player/seek", { positionMs: target });
  if (r.status !== 200) {
    return {
      ok: false,
      status: r.status,
      response: r.json,
      landing: null,
      latencyMs: Date.now() - t0,
      detail: "/player/seek " + target + " -> " + r.status + " " + r.text.slice(0, 160),
    };
  }
  const w = await waitFor(
    async () => ctx.api.state(),
    (s) => s.playback?.positionMs != null && Math.abs(Number(s.playback.positionMs) - target) <= tol,
    opts.timeoutMs ?? 4000,
    60,
  );
  const landing = w.value?.playback?.positionMs == null ? null : Number(w.value.playback.positionMs);
  return {
    ok: w.ok,
    status: 200,
    response: r.json,
    landing,
    latencyMs: Date.now() - t0,
    detail: "target=" + target + " landing=" + (landing === null ? "n/a" : Math.round(landing)) + " (容差 " + tol + "ms) 耗时 " + (Date.now() - t0) + "ms",
  };
}

interface EnterResult {
  ok: boolean;
  status: number;
  response: any;
  state: State | null;
  latencyMs: number;
  detail: string;
}

async function enterLoopMain(ctx: ScenarioCtx, ml: MainLoop, opts: { timeoutMs?: number } = {}): Promise<EnterResult> {
  const t0 = Date.now();
  const r = await ctx.api.post("/loop/enter", { songId: ml.song.id, play: true, restart: false });
  if (r.status !== 200) {
    return {
      ok: false,
      status: r.status,
      response: r.json,
      state: null,
      latencyMs: Date.now() - t0,
      detail: "/loop/enter -> " + r.status + " " + r.text.slice(0, 160),
    };
  }
  const w = await waitFor(
    async () => ctx.api.state(),
    (s) => loopWindowOf(s)?.songId === ml.song.id && s.playback?.engine === "loop",
    opts.timeoutMs ?? 12000,
    100,
  );
  const st = w.value ?? (await ctx.api.state().catch(() => null));
  const f = st ? factsOf(st, ml) : null;
  return {
    ok: w.ok,
    status: 200,
    response: r.json,
    state: st,
    latencyMs: Date.now() - t0,
    detail:
      "/loop/enter ok=" + w.ok + " 耗时=" + w.elapsedMs + "ms engine=" + (f?.engine ?? "?") +
      " claimActive=" + (f?.claimActive ?? "?") + " window=" + (f?.window ? f.window.aMs + ".." + f.window.bMs : "null"),
  };
}

interface ExitResult {
  ok: boolean;
  attempted: boolean;
  state: State | null;
  elapsedMs: number;
  detail: string;
}

async function exitLoopHard(ctx: ScenarioCtx, timeoutMs = 8000): Promise<ExitResult> {
  const st = await ctx.api.state().catch(() => null);
  const inLoop = st != null && (loopWindowOf(st) != null || st.playback?.engine === "loop");
  if (!inLoop) {
    return { ok: true, attempted: false, state: st, elapsedMs: 0, detail: "本来就不在循环里" };
  }
  const t0 = Date.now();
  const r = await ctx.api.post("/loop/exit");
  const w = await waitFor(
    async () => ctx.api.state(),
    (s) => loopWindowOf(s) === null && s.playback?.engine !== "loop",
    timeoutMs,
    100,
  );
  return {
    ok: w.ok,
    attempted: true,
    state: w.value,
    elapsedMs: Date.now() - t0,
    detail:
      "/loop/exit status=" + r.status + " 耗时=" + (Date.now() - t0) + "ms ok=" + w.ok +
      " engine=" + (w.value?.playback?.engine ?? "?") +
      " window=" + (loopWindowOf(w.value ?? ({} as State)) ? "still" : "null"),
  };
}

/**
 * 后台采样主播放器位置/相位。
 *
 * 为什么需要：00028 的跳变发生在 /loop/enter 之后一两百毫秒内，等 HTTP 返回再轮询就错过了。
 * 这里 80~120ms 连续采样，事后从序列里找跳变 / 折返 / 回零。
 */
class MainMonitor {
  readonly series: MainSample[] = [];
  private stopped = false;
  private done: Promise<void> = Promise.resolve();
  private readonly t0 = Date.now();

  constructor(
    private ctx: ScenarioCtx,
    private ml: MainLoop,
    private intervalMs = 90,
  ) {}

  start(): void {
    this.done = (async () => {
      while (!this.stopped) {
        const s = await this.ctx.api.state().catch(() => null);
        if (s) {
          const raw = Number(s.playback?.positionMs);
          const pr = phaseOf(s, this.ml);
          this.series.push({
            atMs: Date.now() - this.t0,
            posMs: Number.isFinite(raw) ? raw : null,
            phase: pr.phase,
            phaseSource: pr.source,
            engine: String(s.playback?.engine ?? "?"),
            playing: s.playback?.playing === true,
            claimActive: claimActiveOf(s),
          });
        }
        await sleep(this.intervalMs);
      }
    })();
  }

  async stop(): Promise<void> {
    this.stopped = true;
    try {
      await this.done;
    } catch {
      // 最后一拍失败忽略
    }
  }

  firstWrap(L: number): { beforeMs: number; afterMs: number; atMs: number } | null {
    for (let i = 1; i < this.series.length; i++) {
      const prev = this.series[i - 1].posMs;
      const cur = this.series[i].posMs;
      if (prev == null || cur == null) continue;
      if (cur < prev - L / 2) return { beforeMs: prev, afterMs: cur, atMs: this.series[i].atMs };
    }
    return null;
  }

  /** 曲末回 0：前一点还在曲末附近，后一点已落到曲首附近。 */
  firstWrapToZero(durationMs: number): { beforeMs: number; afterMs: number; atMs: number } | null {
    for (let i = 1; i < this.series.length; i++) {
      const prev = this.series[i - 1].posMs;
      const cur = this.series[i].posMs;
      if (prev == null || cur == null) continue;
      if (prev >= durationMs - 4000 && cur <= 3000 && cur < prev - 5000) {
        return { beforeMs: prev, afterMs: cur, atMs: this.series[i].atMs };
      }
    }
    return null;
  }

  async waitForWrap(L: number, timeoutMs: number): Promise<{ ok: boolean; beforeMs: number | null; afterMs: number | null }> {
    const t0 = Date.now();
    while (Date.now() - t0 < timeoutMs) {
      const w = this.firstWrap(L);
      if (w) return { ok: true, beforeMs: w.beforeMs, afterMs: w.afterMs };
      await sleep(60);
    }
    return { ok: false, beforeMs: null, afterMs: null };
  }

  async waitForWrapToZero(
    durationMs: number,
    timeoutMs: number,
  ): Promise<{ ok: boolean; beforeMs: number | null; afterMs: number | null }> {
    const t0 = Date.now();
    while (Date.now() - t0 < timeoutMs) {
      const w = this.firstWrapToZero(durationMs);
      if (w) return { ok: true, beforeMs: w.beforeMs, afterMs: w.afterMs };
      await sleep(60);
    }
    return { ok: false, beforeMs: null, afterMs: null };
  }

  async waitForAbove(thresholdMs: number, timeoutMs: number): Promise<{ ok: boolean; posMs: number | null }> {
    const t0 = Date.now();
    while (Date.now() - t0 < timeoutMs) {
      for (let i = this.series.length - 1; i >= 0; i--) {
        const p = this.series[i].posMs;
        if (p != null) {
          if (p >= thresholdMs) return { ok: true, posMs: p };
          break;
        }
      }
      await sleep(60);
    }
    const last = this.series.length ? this.series[this.series.length - 1].posMs : null;
    return { ok: false, posMs: last };
  }

  /** 相邻采样位置的最大步长（只统计两个端点都有的样本）。 */
  maxStep(fromAtMs = 0, toAtMs = Number.POSITIVE_INFINITY): number {
    let max = 0;
    for (let i = 1; i < this.series.length; i++) {
      const a = this.series[i - 1];
      const b = this.series[i];
      if (b.atMs < fromAtMs || b.atMs > toAtMs) continue;
      if (a.posMs == null || b.posMs == null) continue;
      max = Math.max(max, Math.abs(b.posMs - a.posMs));
    }
    return max;
  }

  samplesBelow(thresholdMs: number): MainSample[] {
    return this.series.filter((s) => s.posMs != null && (s.posMs as number) < thresholdMs);
  }

  /** 落在 [a-1s, a+1s] 的采样（用于验证"没有跳到 A"）。 */
  samplesNear(aMs: number, tolMs = 1000): MainSample[] {
    return this.series.filter((s) => s.posMs != null && Math.abs((s.posMs as number) - aMs) <= tolMs);
  }
}

// ---------------------------------------------------------------------------
// 内容真值探针（主播放器）
// ---------------------------------------------------------------------------

let preludePullOverride: ((remoteName: string) => Promise<any>) | null = null;

/** 仅供离线干跑/自检注入 tap 取回函数（真机走 adb exec-out run-as）。 */
export function setPreludePullForTest(pull: ((remoteName: string) => Promise<any>) | null): void {
  preludePullOverride = pull;
}

function stackFor(ctx: ScenarioCtx): ProbeStack {
  return createProber(ctx.cfg, ctx.api, {
    workDir: defaultWorkDir(ctx.cfg),
    log: ctx.log,
    pull: preludePullOverride ?? undefined,
  });
}

function autoMode(displayMs: number, ml: MainLoop): "band" | "file" {
  return displayMs >= ml.aMs - 50 && displayMs <= ml.bMs + 50 ? "band" : "file";
}

interface ProbeMainOpts {
  label: string;
  /** 与显示位置的固定偏移（优先级高于 offsets）。 */
  offsetMs?: number;
  /** 按模式取偏移：band=环带口径，file=前奏/尾奏(整曲)口径。 */
  offsets?: { band: number; file: number };
  mode?: "band" | "file";
  wide?: boolean;
  driftTolMs?: number;
}

interface ProbeMainResult {
  check: Check;
  result: ProbeResult | null;
  capture: TapCapture | null;
  displayRefMs: number | null;
  mode: "band" | "file" | null;
  error?: string;
}

function contentCheck(label: string, r: ProbeResult): Check {
  const loc = r.locatedContentMs == null ? "n/a" : Math.round(r.locatedContentMs) + "ms";
  const drift = r.driftMs == null ? "n/a" : Math.round(r.driftMs) + "ms";
  const wide =
    r.wide && r.wide.length > 0
      ? "；实际最像 " + r.wide[0].region + " @" + Math.round(r.wide[0].positionMs) + "ms(peak " + r.wide[0].peak.toFixed(3) + ")"
      : "";
  const detail =
    "peak=" + r.peak.toFixed(3) + " 期望=" + Math.round(r.expectedContentMs) + "ms 定位=" + loc +
    " 偏差=" + drift + " 口径=" + r.searched.mode + " 参考片=" + r.chunks + " 窗口=" + r.capture.windowMs + "ms" + wide;
  if (r.verdict === "unavailable") {
    return pendingCheck(label, detail + " — " + r.reason, "tap-content-probe", "tap 没写出帧/没有 decode-range；确认 debug 包 + 主播放器 SOURCE 路线");
  }
  if (r.verdict === "match") return check(label, true, detail);
  const hint =
    r.verdict === "mismatch"
      ? "00027/00028 型：写出的 PCM 在期望位置附近找不到（看 wide 定位实际落在环带/B 之后/A 之前）"
      : "可疑：先排除基线对齐误差（基线峰值应 >=0.95）、音量差、降采样";
  return check(label, false, detail + " — " + r.reason, hint);
}

async function probeMain(
  ctx: ScenarioCtx,
  stack: ProbeStack,
  ml: MainLoop,
  opts: ProbeMainOpts,
): Promise<ProbeMainResult> {
  const label = opts.label + " 内容真值";
  let capture: TapCapture;
  try {
    capture = await stack.prober.capture({
      target: "main",
      samplesMs: SAMPLES_MS,
      dwellMs: DWELL_MS,
      workDir: defaultWorkDir(ctx.cfg),
      label: opts.label,
    });
  } catch (e: any) {
    const msg = String(e?.message ?? e);
    return {
      check: pendingCheck(label, "抓不到 tap: " + msg, "tap-content-probe", "确认 debug 包 / 主播放器 SOURCE 路线 / 当前不是暂停或 ENDED"),
      result: null,
      capture: null,
      displayRefMs: null,
      mode: null,
      error: msg,
    };
  }
  const displayRef = capture.displayRef?.displayMs;
  if (displayRef == null) {
    return {
      check: pendingCheck(label, "抓取期间没有 display 采样(" + capture.displaySource + ")", "tap-content-probe"),
      result: null,
      capture,
      displayRefMs: null,
      mode: null,
      error: "no-display",
    };
  }
  const display = Number(displayRef);
  const mode = opts.mode ?? autoMode(display, ml);
  const modeOffset = mode === "band" ? opts.offsets?.band ?? 0 : opts.offsets?.file ?? 0;
  const expected = display + (opts.offsetMs ?? modeOffset);
  let result: ProbeResult;
  try {
    result = await stack.truth.probeCapture(capture, {
      songPath: ml.songPath,
      aMs: ml.aMs,
      bMs: ml.bMs,
      expectedContentMs: expected,
      mode,
      wide: opts.wide === true,
      driftTolMs: opts.driftTolMs ?? DRIFT_TOL_MS,
      caption: opts.label,
    });
  } catch (e: any) {
    const msg = String(e?.message ?? e);
    return {
      check: pendingCheck(label, "内容比对失败: " + msg, "tap-content-probe"),
      result: null,
      capture,
      displayRefMs: display,
      mode,
      error: msg,
    };
  }
  ctx.evidence.step("content-probe", {
    label: opts.label,
    displayRefMs: display,
    mode,
    expectedContentMs: expected,
    offsetMs: opts.offsetMs ?? modeOffset,
    verdict: result.verdict,
    peak: result.peak,
    locatedContentMs: result.locatedContentMs,
    locatedBandMs: result.locatedBandMs,
    driftMs: result.driftMs,
    chunks: result.chunks,
    windowShiftMs: result.windowShiftMs,
    wide: result.wide,
    capture: result.capture,
    wav: capture.wavPath,
    jsonl: capture.jsonlPath,
    seams: capture.seams.length,
    flushCount: capture.flushCount,
  });
  ctx.log(
    "  " + opts.label + ": " + result.verdict + " peak=" + result.peak.toFixed(3) +
      " display=" + Math.round(display) + "ms 期望=" + Math.round(expected) + "ms(" + mode + ")" +
      " 偏差=" + (result.driftMs == null ? "n/a" : Math.round(result.driftMs) + "ms") +
      (capture.seams.length > 0 ? " seams=" + capture.seams.length : ""),
  );
  return { check: contentCheck(label, result), result, capture, displayRefMs: display, mode };
}

/**
 * "听到的内容是不是请求位置的那段"：用「请求位置 + (探测窗口起点 - seek 落点)」推算期望内容。
 *
 * 落点被夹/折返时，display 会跳到别处，但内容通常跟着 display 走（所以主判据 peak 很高）；
 * 这条判据专门抓「位置被夹走了、声音也没有停在请求位置」这一类。
 */
function contentAtRequestCheck(
  label: string,
  p: ProbeMainResult,
  requestMs: number,
  landingMs: number | null,
  ml: MainLoop,
  tolMs = 2000,
): Check {
  if (!p.result || p.displayRefMs == null) {
    return pendingCheck(label, "内容探针不可用，无法核对请求位置", "tap-content-probe");
  }
  const landing = landingMs ?? p.displayRefMs;
  const expected = requestMs + (p.displayRefMs - landing);
  const r = p.result;
  const delta =
    r.locatedContentMs == null
      ? null
      : r.searched.mode === "band"
        ? circularDelta(r.locatedContentMs, expected, ml.L)
        : r.locatedContentMs - expected;
  const ok = r.peak >= PEAK_SUSPECT && delta != null && Math.abs(delta) <= tolMs;
  return check(
    label,
    ok,
    "请求=" + Math.round(requestMs) + "ms 落点=" + Math.round(landing) + "ms 期望内容=" + Math.round(expected) +
      "ms 定位=" + (r.locatedContentMs == null ? "n/a" : Math.round(r.locatedContentMs)) +
      "ms 偏差=" + (delta == null ? "n/a" : Math.round(delta) + "ms") + " peak=" + r.peak.toFixed(3),
    ok ? undefined : "听到的内容不在请求位置(位置被夹回/折返后内容没跟着请求走)",
  );
}

interface CalibrateResult {
  offsetMs: number;
  mode: "band" | "file" | null;
  checks: Check[];
}

/**
 * 在**当前位置**校准"写入超前"偏移：先 offset=0 探一枪量偏移（driftTol 放宽），
 * 再用量到的偏移复核一枪（严格 driftTol）。这同时是判据自证：基线峰值必须 >=0.95。
 */
async function calibrateAtCurrent(
  ctx: ScenarioCtx,
  stack: ProbeStack,
  ml: MainLoop,
  opts: { label: string; mode?: "band" | "file" },
): Promise<CalibrateResult> {
  const checks: Check[] = [];
  const first = await probeMain(ctx, stack, ml, {
    label: opts.label + "-校准",
    offsets: { band: 0, file: 0 },
    mode: opts.mode,
    driftTolMs: 5000,
  });
  if (!first.result || first.mode == null) {
    checks.push(first.check);
    return { offsetMs: 0, mode: null, checks };
  }
  const offset = first.result.driftMs ?? 0;
  checks.push(
    check(
      opts.label + " 判据自证:峰值 >= " + BASELINE_PEAK_MIN,
      first.result.peak >= BASELINE_PEAK_MIN,
      "peak=" + first.result.peak.toFixed(3) + " 显示=" + Math.round(first.displayRefMs ?? -1) + "ms 偏移=" + Math.round(offset) + "ms",
      first.result.peak < BASELINE_PEAK_MIN
        ? "基线都匹配不上 -> 内容真值判据不可信（是否 SOURCE、tap 是否被两路同时写、窗口是否跨接缝）"
        : undefined,
    ),
  );
  checks.push(
    check(
      opts.label + " 写入超前偏移量级合理",
      Math.abs(offset) <= 800,
      "offset=" + Math.round(offset) + "ms（= 写入超前播放 + 采样相位）",
      Math.abs(offset) > 800 ? "偏移过大:先确认锚点(displayRef)与 tap 写入是否对齐" : undefined,
    ),
  );
  const second = await probeMain(ctx, stack, ml, {
    label: opts.label + "-复核",
    offsetMs: offset,
    mode: first.mode,
  });
  checks.push(second.check);
  return { offsetMs: offset, mode: first.mode, checks };
}

async function restoreLoopRow(ctx: ScenarioCtx, ml: MainLoop): Promise<Check> {
  await ctx.api.post("/loop/exit").catch(() => null);
  const r = await ctx.api.post("/confirm", {
    songId: ml.song.id,
    aSamples: ml.origASamples,
    bSamples: ml.origBSamples,
  });
  const restored = await ctx.api.song(ml.song.id).catch(() => null);
  const ok =
    r.status === 200 &&
    restored != null &&
    Math.abs(Number(restored.loopStartSamples) - ml.origASamples) <= 1 &&
    Math.abs(Number(restored.loopEndSamples) - ml.origBSamples) <= 1;
  return check(
    "恢复原 A/B",
    ok,
    "status=" + r.status + " 期望 A/B samples=" + ml.origASamples + "/" + ml.origBSamples +
      " 实际=" + (restored ? restored.loopStartSamples + "/" + restored.loopEndSamples : "n/a"),
    ok ? undefined : "测试污染用户数据:必须用 /confirm 按原 sample 还原",
  );
}

function buildEntryPoints(ml: MainLoop): { label: string; ms: number; note: string }[] {
  const a30 = ml.aMs > 31000 ? ml.aMs - 30000 : 0;
  return [
    { label: "从0", ms: 0, note: "曲首" },
    { label: "从A-30s", ms: a30, note: ml.aMs > 31000 ? "" : "A<31s:夹到 0" },
    { label: "从A-1s", ms: Math.max(0, ml.aMs - 1000), note: "" },
    { label: "从A", ms: ml.aMs, note: "边界点" },
    { label: "从A+1s", ms: Math.min(ml.bMs - 500, ml.aMs + 1000), note: "" },
    { label: "从B-1s", ms: ml.bMs - 1000, note: "" },
    { label: "从B", ms: ml.bMs, note: "边界点" },
    { label: "从B+5s", ms: Math.min(ml.durationMs - 1200, ml.bMs + 5000), note: "尾奏" },
  ];
}

// ---------------------------------------------------------------------------
// 场景 60：进循环起点矩阵（0 / A-30s / A-1s / A / A+1s / B-1s / B / B+5s）
//
// 想抓：00028 —— 从任何位置进无缝循环，播放头都被重置到 A；定稿语义是从曲首(prelude)起播。
// 判据：每个起点进循环后 positionMs ∈ [0,5s]、phase=prelude、内容真值=曲首前奏内容。
// [EXPECTED-FAIL 00028] 旧实现 8 点全落 A：位置/相位 FAIL（内容与 display 一致，故内容判据 PASS）。
// ---------------------------------------------------------------------------

const s60: Scenario = {
  id: 60,
  key: "enter-start-matrix",
  name: "进循环起点矩阵(8 点):都从曲首前奏起播,不跳 A",
  tag: "前奏",
  timeoutMs: 300000,
  async run(ctx): Promise<ScenarioRunResult> {
    const checks: Check[] = [];
    const pending: string[] = [
      "定稿语义(lead):进无缝循环统一从曲首(prelude)起播，与进循环前的播放头无关。若之后改成'保留当前位置'，本矩阵 8 点应改为断言 position≈进循环前位置。",
    ];
    const song = await selectPreludeSong(ctx, { minA: 12000, minL: 6000, minTail: 7000, preferLargeA: true });
    const ml = await loadMainLoop(ctx, song);
    const stack = stackFor(ctx);
    ctx.evidence.step("setup", {
      covers: "lead 断言 1/7 + 批次需求 1/8",
      songId: song.id,
      title: song.title,
      aMs: ml.aMs,
      bMs: ml.bMs,
      L: ml.L,
      durationMs: ml.durationMs,
      sampleRate: ml.sampleRate,
      songPath: ml.songPath,
      points: buildEntryPoints(ml),
    });
    ctx.log(
      "进循环起点矩阵 #" + song.id + " A=" + ml.aMs + " B=" + ml.bMs + " L=" + round(ml.L / 1000, 1) +
        "s 尾=" + round((ml.durationMs - ml.bMs) / 1000, 1) + "s",
    );

    // 先退出循环并离开结果页：预览页的 tap 与主播放器共用同一个 WAV，
    // 不挪走预览会把两路 PCM 混在一起，内容真值直接失效。
    await ctx.resetForMain();
    const play = await ensureSongPlaying(ctx, song.id);
    checks.push(check("前置:开始播放", play.ok, play.detail));
    if (!play.ok) return { checks, summary: "前置失败: " + play.detail, pendingDecision: pending };

    // 先在循环里做一次基线校准（首个进循环点同时用于判据自证）。
    const first = await enterLoopMain(ctx, ml);
    checks.push(check("进入循环模式(engine=loop)", first.ok, first.detail));
    if (!first.ok) return { checks, summary: "循环源未接管: " + first.detail, pendingDecision: pending };
    await sleep(900);

    const off: Offsets = { band: null, file: null };
    const cal = await calibrateAtCurrent(ctx, stack, ml, { label: "基线" });
    checks.push(...cal.checks);
    if (cal.mode) off[cal.mode] = cal.offsetMs;
    const offsets = resolvedOffsets(off);
    ctx.log("  偏移: band=" + Math.round(offsets.band) + "ms file=" + Math.round(offsets.file) + "ms");

    const rows: { label: string; posMs: number; phase: string | null; verdict: string; peak: number | null }[] = [];
    for (const pt of buildEntryPoints(ml)) {
      const exit = await exitLoopHard(ctx);
      if (!exit.ok) checks.push(check(pt.label + " 退出循环(下一点前置)", false, exit.detail));
      const seek = await seekMain(ctx, pt.ms, { tolMs: 1500 });
      checks.push(check(pt.label + " 进循环前落点=请求值(±1.5s)", seek.ok, "target=" + pt.ms + " " + seek.detail));
      if (!seek.ok) {
        checks.push(
          check(
            pt.label + " 进循环起点",
            false,
            "进循环前就没落到目标位置，本点不可判",
            "先确认非循环态的 /player/seek 可用（00001 类问题会让起点矩阵失效）",
          ),
        );
        continue;
      }
      const enter = await enterLoopMain(ctx, ml);
      checks.push(check(pt.label + " 进循环(engine=loop)", enter.ok, enter.detail));
      if (!enter.ok) continue;
      await sleep(850);
      const st = await ctx.api.state();
      const pos = Number(st.playback.positionMs);
      const pr = phaseOf(st, ml);
      const hint = "00028: 旧实现把播放头重置到 A（环带源时间轴只有 [A,B)）；定稿语义是从曲首前奏起播";
      const finite = Number.isFinite(pos);
      checks.push(
        check(
          pt.label + " 位置=前奏起点(0..5s)",
          finite && pos >= 0 && pos <= PRELUDE_START_MAX_MS,
          "pos=" + (finite ? Math.round(pos) : "n/a") + "ms (进循环前 " + pt.ms + "ms; A=" + ml.aMs + " B=" + ml.bMs + ")",
          finite && pos > PRELUDE_START_MAX_MS ? hint : undefined,
        ),
      );
      const nearA = finite && Math.abs(pos - ml.aMs) <= 2000;
      checks.push(
        check(
          pt.label + " 位置未跳到 A(00028 现象)",
          ml.aMs < 8000 || !nearA,
          "pos=" + (finite ? Math.round(pos) : "n/a") + "ms A=" + ml.aMs + " 距离=" +
            (finite ? Math.round(Math.abs(pos - ml.aMs)) : "n/a") + "ms",
          ml.aMs >= 8000 && nearA ? hint : undefined,
        ),
      );
      checks.push(phaseCheck(pt.label + " phase=prelude", pr, "prelude", pos));
      const audio = await measureMainFrames(ctx.api, 1200, ml.sampleRate);
      checks.push(audioCheck(pt.label + " 前奏出声", audio, 0.8, "进循环后 1.2s 输出帧未按 ≥80% 采样率增长"));
      const probe = await probeMain(ctx, stack, ml, { label: pt.label + "-前奏", offsets, wide: true });
      checks.push(probe.check);
      rows.push({
        label: pt.label,
        posMs: finite ? Math.round(pos) : -1,
        phase: pr.phase,
        verdict: probe.result?.verdict ?? "unavailable",
        peak: probe.result?.peak ?? null,
      });
      ctx.evidence.step("entry-point", {
        label: pt.label,
        targetMs: pt.ms,
        note: pt.note,
        posMs: pos,
        phase: pr.phase,
        phaseSource: pr.source,
        facts: factsOf(st, ml),
        audio: { ratio: round(audio.ratio, 3), deltaFrames: audio.deltaFrames, available: audio.available },
        displayRefMs: probe.displayRefMs,
        probeMode: probe.mode,
        probeVerdict: probe.result?.verdict ?? "unavailable",
        peak: probe.result?.peak ?? null,
        driftMs: probe.result?.driftMs ?? null,
        wav: probe.capture?.wavPath ?? null,
      });
    }
    await exitLoopHard(ctx);
    const okCount = rows.filter((r) => r.posMs >= 0 && r.posMs <= PRELUDE_START_MAX_MS && r.phase === "prelude").length;
    return {
      checks,
      summary:
        "8 点进循环: 前奏起点 " + okCount + "/" + rows.length + "; " +
        rows.map((r) => r.label + ":" + r.posMs + "ms/" + (r.phase ?? "?")).join(" "),
      pendingDecision: pending,
    };
  },
};

// ---------------------------------------------------------------------------
// 场景 61：前奏播到 A —— 继续播（不折回/不跳），phase 转 loop
//
// 想抓：前奏段是否真的在播，以及 prelude -> loop 的相位切换是否保持播放头。
// [EXPECTED-FAIL 00028] 旧实现进循环直接落 A：观察不到前奏段，相位切换无法验证。
// ---------------------------------------------------------------------------

const s61: Scenario = {
  id: 61,
  key: "prelude-to-loop-transition",
  name: "前奏播到 A:继续播不跳,phase 转 loop",
  tag: "前奏",
  timeoutMs: 240000,
  async run(ctx): Promise<ScenarioRunResult> {
    const checks: Check[] = [];
    const pending: string[] = [
      "定稿语义:前奏轮只播一次（0 -> B），到 A 不折返、不跳；到达 B 才第一次折回 A。",
    ];
    const song = await selectPreludeSong(ctx, { minA: 4000, maxA: 20000, minL: 8000, minTail: 5000 });
    const ml = await loadMainLoop(ctx, song);
    const stack = stackFor(ctx);
    ctx.evidence.step("setup", { covers: "lead 断言 2/7 + 批次需求 1/8", songId: song.id, aMs: ml.aMs, bMs: ml.bMs, L: ml.L, durationMs: ml.durationMs, songPath: ml.songPath });
    ctx.log("前奏->loop #" + song.id + " A=" + ml.aMs + " B=" + ml.bMs + " L=" + round(ml.L / 1000, 1) + "s");

    // 先退出循环并离开结果页：预览页的 tap 与主播放器共用同一个 WAV，
    // 不挪走预览会把两路 PCM 混在一起，内容真值直接失效。
    await ctx.resetForMain();
    const play = await ensureSongPlaying(ctx, song.id);
    checks.push(check("前置:开始播放", play.ok, play.detail));
    if (!play.ok) return { checks, summary: "前置失败: " + play.detail, pendingDecision: pending };
    const seek0 = await seekMain(ctx, 0, { tolMs: 800 });
    checks.push(check("前置:播放头回到曲首", seek0.ok, seek0.detail));
    const enter = await enterLoopMain(ctx, ml);
    checks.push(check("进入循环模式(engine=loop)", enter.ok, enter.detail));
    if (!enter.ok) return { checks, summary: "循环源未接管: " + enter.detail, pendingDecision: pending };
    await sleep(800);

    const st0 = await ctx.api.state();
    const pos0 = Number(st0.playback.positionMs);
    const pr0 = phaseOf(st0, ml);
    checks.push(
      check(
        "进循环后位置在前奏(0..5s)",
        Number.isFinite(pos0) && pos0 >= 0 && pos0 <= PRELUDE_START_MAX_MS,
        "pos=" + Math.round(pos0) + "ms A=" + ml.aMs,
        pos0 > PRELUDE_START_MAX_MS ? "00028: 进循环直接落在 A，没有前奏段" : undefined,
      ),
    );
    checks.push(phaseCheck("进循环后 phase=prelude", pr0, "prelude", pos0));
    const audio0 = await measureMainFrames(ctx.api, 1200, ml.sampleRate);
    checks.push(audioCheck("前奏出声", audio0, 0.8));

    const off: Offsets = { band: null, file: null };
    const calPre = await calibrateAtCurrent(ctx, stack, ml, { label: "前奏基线", mode: "file" });
    checks.push(...calPre.checks);
    if (calPre.mode) off[calPre.mode] = calPre.offsetMs;
    const probePre = await probeMain(ctx, stack, ml, { label: "前奏段内容", offsets: resolvedOffsets(off) });
    checks.push(probePre.check);

    const monitor = new MainMonitor(ctx, ml, 90);
    monitor.start();
    const timeout = Math.max(9000, Math.max(0, ml.aMs - Math.min(pos0, ml.aMs)) + 8000);
    const crossed = await monitor.waitForAbove(ml.aMs - 150, timeout);
    const preSamples = monitor.samplesBelow(ml.aMs - 400);
    checks.push(
      check(
        "观察到了前奏段(进循环后先在 <A 播放)",
        preSamples.length > 0,
        "前奏采样 " + preSamples.length + " 个 (起点 pos=" + Math.round(pos0) + "ms, A=" + ml.aMs + "ms)",
        preSamples.length === 0 ? "进循环直接落在 A/环带内：没有前奏段（00028 现象）" : undefined,
      ),
    );
    checks.push(
      check(
        "前奏播到 A 继续播(不折回/不跳)",
        crossed.ok,
        crossed.ok
          ? "已到 display=" + Math.round(Number(crossed.posMs)) + "ms (A=" + ml.aMs + "ms)"
          : "等待 " + Math.round(timeout / 1000) + "s 仍未走到 A-150ms",
        crossed.ok ? undefined : "前奏没有向前推进（被暂停 / 被折回 / 源被换掉）",
      ),
    );
    const maxStep = monitor.maxStep();
    checks.push(
      check(
        "跨 A 期间位置连续(相邻采样步长 ≤1.5s)",
        maxStep <= 1500,
        "maxStep=" + Math.round(maxStep) + "ms/采样(90ms)",
        maxStep > 1500 ? "跨 A 时位置跳变：prelude->loop 切换没有保持播放头" : undefined,
      ),
    );
    await monitor.stop();
    await sleep(500);

    const st1 = await ctx.api.state();
    const pos1 = Number(st1.playback.positionMs);
    const pr1 = phaseOf(st1, ml);
    checks.push(phaseCheck("跨 A 后 phase=loop", pr1, "loop", pos1));
    checks.push(
      check(
        "跨 A 后位置在环带内([A,B))",
        Number.isFinite(pos1) && pos1 >= ml.aMs - 1000 && pos1 <= ml.bMs + 1000,
        "pos=" + Math.round(pos1) + "ms A=" + ml.aMs + " B=" + ml.bMs,
      ),
    );
    const calLoop = await calibrateAtCurrent(ctx, stack, ml, { label: "环带基线" });
    checks.push(...calLoop.checks);
    if (calLoop.mode) off[calLoop.mode] = calLoop.offsetMs;
    const probeLoop = await probeMain(ctx, stack, ml, { label: "跨A后环带内容", offsets: resolvedOffsets(off), wide: true });
    checks.push(probeLoop.check);
    const audio1 = await measureMainFrames(ctx.api, 1200, ml.sampleRate);
    checks.push(audioCheck("进入 loop 后出声", audio1, 0.8));
    ctx.evidence.step("transition", {
      pos0,
      phase0: pr0.phase,
      phase0Source: pr0.source,
      preSamples: preSamples.length,
      crossed: { ok: crossed.ok, posMs: crossed.posMs },
      maxStepMs: maxStep,
      pos1,
      phase1: pr1.phase,
      phase1Source: pr1.source,
      facts: factsOf(st1, ml),
      preVerdict: probePre.result?.verdict ?? "unavailable",
      loopVerdict: probeLoop.result?.verdict ?? "unavailable",
      offsets: resolvedOffsets(off),
    });
    return {
      checks,
      summary:
        "前奏->loop: 前奏采样 " + preSamples.length + " 个, maxStep=" + Math.round(maxStep) +
        "ms, 跨 A 后 pos=" + Math.round(pos1) + "ms phase=" + (pr1.phase ?? "?"),
      pendingDecision: pending,
    };
  },
};

// ---------------------------------------------------------------------------
// 场景 62：loop 播到 B —— 回到 A（内容真值确认听到的是 A）
//
// 想抓：位置折返正确但内容仍在 B 之后（00027 型）；以及折返点是否存在断流。
// 旧实现就支持 [A,B) 内环回，所以这条应 PASS —— 用来证明判据不会误杀正常路径。
// ---------------------------------------------------------------------------

const s62: Scenario = {
  id: 62,
  key: "loop-wrap-at-b-content",
  name: "loop 播到 B:回到 A(内容真值确认听到 A)",
  tag: "环带",
  timeoutMs: 180000,
  async run(ctx): Promise<ScenarioRunResult> {
    const checks: Check[] = [];
    const pending: string[] = ["定稿语义:loop 段在 B 处折回 A（内容也必须是 A 之后的那段）。"];
    const song = await selectPreludeSong(ctx, { minA: 6000, minL: 8000, maxL: 20000, minTail: 5000 });
    const ml = await loadMainLoop(ctx, song);
    const stack = stackFor(ctx);
    ctx.evidence.step("setup", { covers: "lead 断言 3/7 + 批次需求 3/4/8", songId: song.id, aMs: ml.aMs, bMs: ml.bMs, L: ml.L, durationMs: ml.durationMs, songPath: ml.songPath });
    ctx.log("B 处折返 #" + song.id + " A=" + ml.aMs + " B=" + ml.bMs + " L=" + round(ml.L / 1000, 1) + "s");

    // 先退出循环并离开结果页：预览页的 tap 与主播放器共用同一个 WAV，
    // 不挪走预览会把两路 PCM 混在一起，内容真值直接失效。
    await ctx.resetForMain();
    const play = await ensureSongPlaying(ctx, song.id);
    checks.push(check("前置:开始播放", play.ok, play.detail));
    if (!play.ok) return { checks, summary: "前置失败: " + play.detail, pendingDecision: pending };
    const enter = await enterLoopMain(ctx, ml);
    checks.push(check("进入循环模式(engine=loop)", enter.ok, enter.detail));
    if (!enter.ok) return { checks, summary: "循环源未接管: " + enter.detail, pendingDecision: pending };
    await sleep(800);

    const off: Offsets = { band: null, file: null };
    const cal = await calibrateAtCurrent(ctx, stack, ml, { label: "环带基线" });
    checks.push(...cal.checks);
    if (cal.mode) off[cal.mode] = cal.offsetMs;
    const offsets = resolvedOffsets(off);

    const target = Math.max(ml.aMs + 1000, ml.bMs - 2500);
    const seek = await seekMain(ctx, target, { tolMs: 1200 });
    checks.push(check("seek 到 B-2.5s 落点=请求值(±1.2s)", seek.ok, seek.detail));
    if (!seek.ok) return { checks, summary: "seek 到 B-2.5s 失败: " + seek.detail, pendingDecision: pending };
    const st0 = await ctx.api.state();
    checks.push(phaseCheck("折返前 phase=loop", phaseOf(st0, ml), "loop", Number(st0.playback.positionMs)));

    // 折返可能就发生在这 2s 的内容探测里：先起采样器，事后再从序列里找折返。
    const monitor = new MainMonitor(ctx, ml, 100);
    monitor.start();
    const before = await probeMain(ctx, stack, ml, { label: "B-2.5s 起播", offsets });
    checks.push(before.check);
    const wrap = await monitor.waitForWrap(ml.L, 12000);
    checks.push(
      check(
        "在 B 处折回 A",
        wrap.ok && wrap.afterMs != null && wrap.afterMs <= ml.aMs + 4000,
        wrap.ok
          ? "before=" + Math.round(Number(wrap.beforeMs)) + "ms -> after=" + Math.round(Number(wrap.afterMs)) + "ms (A=" + ml.aMs + " B=" + ml.bMs + ")"
          : "12s 内没有观察到折返",
        wrap.ok ? undefined : "环回没发生（源被换掉 / 被暂停 / 停在 B）",
      ),
    );
    await sleep(700);
    await monitor.stop();

    const st1 = await ctx.api.state();
    const pos1 = Number(st1.playback.positionMs);
    const pr1 = phaseOf(st1, ml);
    checks.push(
      check(
        "折返后位置回到 A 附近([A,A+4s])",
        Number.isFinite(pos1) && pos1 >= ml.aMs - 600 && pos1 <= ml.aMs + 4000,
        "pos=" + Math.round(pos1) + "ms A=" + ml.aMs + " B=" + ml.bMs,
        Number.isFinite(pos1) && pos1 > ml.bMs ? "折返后仍停在 B 之后：显示层没有折返" : undefined,
      ),
    );
    checks.push(phaseCheck("折返后 phase 仍是 loop", pr1, "loop", pos1));
    const after = await probeMain(ctx, stack, ml, { label: "折返后(应在 A 附近)", offsets, wide: true });
    checks.push(after.check);
    const audio = await measureMainFrames(ctx.api, 1500, ml.sampleRate);
    checks.push(audioCheck("折返后继续出声", audio, 0.85));
    ctx.evidence.step("wrap", {
      target,
      wrap,
      pos1,
      phase1: pr1.phase,
      phase1Source: pr1.source,
      facts: factsOf(st1, ml),
      beforeVerdict: before.result?.verdict ?? "unavailable",
      afterVerdict: after.result?.verdict ?? "unavailable",
      afterPeak: after.result?.peak ?? null,
      offsets,
    });
    return {
      checks,
      summary:
        "B 处折返: " + (wrap.ok ? Math.round(Number(wrap.beforeMs)) + "->" + Math.round(Number(wrap.afterMs)) : "未观察到") +
        " 折返后 pos=" + Math.round(pos1) + "ms; 内容 " + (before.result?.verdict ?? "?") + "/" + (after.result?.verdict ?? "?"),
      pendingDecision: pending,
    };
  },
};

// ---------------------------------------------------------------------------
// 场景 63：循环中 seek 到 A 之前 —— 能播、phase=prelude、内容=请求位置
//
// 想抓：00028 第二半 —— seek 到 A 之前被 clamp 到 A。
// [EXPECTED-FAIL 00028] 旧实现落点=A、相位=loop、内容不在请求位置。
// ---------------------------------------------------------------------------

const s63: Scenario = {
  id: 63,
  key: "seek-before-a-prelude",
  name: "循环中 seek 到 A 之前:能播,phase=prelude,内容一致",
  tag: "前奏",
  timeoutMs: 180000,
  async run(ctx): Promise<ScenarioRunResult> {
    const checks: Check[] = [];
    const pending: string[] = [
      "定稿语义:循环中 seek 到 A 之前应能播前奏（phase=prelude），播到 B 才折回 A；旧实现把 seek 夹到 A。",
    ];
    const song = await selectPreludeSong(ctx, { minA: 12000, minL: 6000, minTail: 5000 });
    const ml = await loadMainLoop(ctx, song);
    const stack = stackFor(ctx);
    ctx.evidence.step("setup", { covers: "lead 断言 4/7 + 批次需求 2/8", songId: song.id, aMs: ml.aMs, bMs: ml.bMs, L: ml.L, durationMs: ml.durationMs, songPath: ml.songPath });
    ctx.log("seek 到 A 之前 #" + song.id + " A=" + ml.aMs + " B=" + ml.bMs);

    // 先退出循环并离开结果页：预览页的 tap 与主播放器共用同一个 WAV，
    // 不挪走预览会把两路 PCM 混在一起，内容真值直接失效。
    await ctx.resetForMain();
    const play = await ensureSongPlaying(ctx, song.id);
    checks.push(check("前置:开始播放", play.ok, play.detail));
    if (!play.ok) return { checks, summary: "前置失败: " + play.detail, pendingDecision: pending };
    const enter = await enterLoopMain(ctx, ml);
    checks.push(check("进入循环模式(engine=loop)", enter.ok, enter.detail));
    if (!enter.ok) return { checks, summary: "循环源未接管: " + enter.detail, pendingDecision: pending };
    await sleep(800);

    const off: Offsets = { band: null, file: null };
    const cal = await calibrateAtCurrent(ctx, stack, ml, { label: "基线" });
    checks.push(...cal.checks);
    if (cal.mode) off[cal.mode] = cal.offsetMs;
    const offsets = resolvedOffsets(off);

    const targets: { label: string; ms: number }[] = [
      { label: "0(曲首)", ms: 0 },
      { label: "A-10s", ms: Math.max(0, ml.aMs - 10000) },
      { label: "A-1s", ms: Math.max(0, ml.aMs - 1000) },
    ];
    const rows: any[] = [];
    for (const t of targets) {
      const seek = await seekMain(ctx, t.ms, { tolMs: 1500 });
      checks.push(check(t.label + " seek 被接受(HTTP 200)", seek.status === 200, "status=" + seek.status + " " + seek.detail));
      const st = await ctx.api.state();
      const pos = Number(st.playback.positionMs);
      const pr = phaseOf(st, ml);
      checks.push(
        check(
          t.label + " 位置=请求值(±1.5s)",
          Number.isFinite(pos) && Math.abs(pos - t.ms) <= 1500,
          "请求=" + t.ms + "ms pos=" + (Number.isFinite(pos) ? Math.round(pos) : "n/a") + "ms A=" + ml.aMs,
          Number.isFinite(pos) && Math.abs(pos - t.ms) > 1500
            ? "00028: 区外 seek 被夹回（旧实现 sourceTimelineUsForDisplay 把目标 clamp 到 [A,B)）"
            : undefined,
        ),
      );
      checks.push(phaseCheck(t.label + " phase=prelude", pr, "prelude", pos));
      const audio = await measureMainFrames(ctx.api, 1200, ml.sampleRate);
      checks.push(audioCheck(t.label + " 能播(出声)", audio, 0.8));
      const probe = await probeMain(ctx, stack, ml, { label: t.label + "-前奏", offsets, wide: true });
      checks.push(probe.check);
      checks.push(contentAtRequestCheck(t.label + " 内容=请求位置的内容(±2s)", probe, t.ms, seek.landing, ml));
      rows.push({ label: t.label, request: t.ms, landing: seek.landing, pos: Math.round(pos), phase: pr.phase, verdict: probe.result?.verdict ?? "unavailable" });
      ctx.evidence.step("seek-before-a", {
        label: t.label,
        requestMs: t.ms,
        landingMs: seek.landing,
        posMs: pos,
        phase: pr.phase,
        phaseSource: pr.source,
        facts: factsOf(st, ml),
        audio: { ratio: round(audio.ratio, 3), deltaFrames: audio.deltaFrames, available: audio.available },
        displayRefMs: probe.displayRefMs,
        probeMode: probe.mode,
        verdict: probe.result?.verdict ?? "unavailable",
        peak: probe.result?.peak ?? null,
        driftMs: probe.result?.driftMs ?? null,
        wav: probe.capture?.wavPath ?? null,
      });
    }
    await exitLoopHard(ctx);
    return {
      checks,
      summary: "A 之前 seek: " + rows.map((r) => r.label + " req=" + r.request + "->landing=" + (r.landing == null ? "?" : Math.round(r.landing)) + "(pos " + r.pos + "," + (r.phase ?? "?") + ")").join(" | "),
      pendingDecision: pending,
    };
  },
};

// ---------------------------------------------------------------------------
// 场景 64：循环中 seek 到 B 之后（尾奏）—— 能播、phase=tail、内容=显示
//
// 想抓：旧实现把 seek clamp 到 B-1ms，尾奏不可达（00028 的 (B,duration] 不可达）。
// [EXPECTED-FAIL 00028] 落点=B-1ms、phase=loop。
// ---------------------------------------------------------------------------

const s64: Scenario = {
  id: 64,
  key: "seek-past-b-tail",
  name: "循环中 seek 到 B 之后(尾奏):能播,phase=tail,内容一致",
  tag: "尾奏",
  timeoutMs: 180000,
  async run(ctx): Promise<ScenarioRunResult> {
    const checks: Check[] = [];
    const pending: string[] = [
      "定稿语义:只有 seek 到 B 之后才进入 tail；tail 播到曲末回 0（不是回 A）。",
    ];
    const song = await selectPreludeSong(ctx, { minA: 6000, minL: 6000, minTail: 8000 });
    const ml = await loadMainLoop(ctx, song);
    const stack = stackFor(ctx);
    ctx.evidence.step("setup", { covers: "lead 断言 5/7 + 批次需求 3/8", songId: song.id, aMs: ml.aMs, bMs: ml.bMs, L: ml.L, durationMs: ml.durationMs, songPath: ml.songPath });
    ctx.log("seek 到尾奏 #" + song.id + " A=" + ml.aMs + " B=" + ml.bMs + " 尾=" + round((ml.durationMs - ml.bMs) / 1000, 1) + "s");

    // 先退出循环并离开结果页：预览页的 tap 与主播放器共用同一个 WAV，
    // 不挪走预览会把两路 PCM 混在一起，内容真值直接失效。
    await ctx.resetForMain();
    const play = await ensureSongPlaying(ctx, song.id);
    checks.push(check("前置:开始播放", play.ok, play.detail));
    if (!play.ok) return { checks, summary: "前置失败: " + play.detail, pendingDecision: pending };
    const enter = await enterLoopMain(ctx, ml);
    checks.push(check("进入循环模式(engine=loop)", enter.ok, enter.detail));
    if (!enter.ok) return { checks, summary: "循环源未接管: " + enter.detail, pendingDecision: pending };
    await sleep(800);

    const off: Offsets = { band: null, file: null };
    const cal = await calibrateAtCurrent(ctx, stack, ml, { label: "基线" });
    checks.push(...cal.checks);
    if (cal.mode) off[cal.mode] = cal.offsetMs;
    const offsets = resolvedOffsets(off);

    const targets: { label: string; ms: number }[] = [
      { label: "B+1s", ms: Math.min(ml.durationMs - 2000, ml.bMs + 1000) },
      { label: "B+5s", ms: Math.min(ml.durationMs - 2000, ml.bMs + 5000) },
      { label: "尾末(duration-4s)", ms: Math.max(ml.bMs + 500, ml.durationMs - 4000) },
    ];
    const rows: any[] = [];
    for (const t of targets) {
      const seek = await seekMain(ctx, t.ms, { tolMs: 1500 });
      checks.push(check(t.label + " seek 被接受(HTTP 200)", seek.status === 200, "status=" + seek.status + " " + seek.detail));
      const st = await ctx.api.state();
      const pos = Number(st.playback.positionMs);
      const pr = phaseOf(st, ml);
      checks.push(
        check(
          t.label + " 位置=请求值(±1.5s)",
          Number.isFinite(pos) && Math.abs(pos - t.ms) <= 1500,
          "请求=" + t.ms + "ms pos=" + (Number.isFinite(pos) ? Math.round(pos) : "n/a") + "ms B=" + ml.bMs,
          Number.isFinite(pos) && Math.abs(pos - t.ms) > 1500
            ? "00028: 尾奏 seek 被夹回环带（旧实现 clamp 到 B-1ms），(B,duration] 不可达"
            : undefined,
        ),
      );
      checks.push(phaseCheck(t.label + " phase=tail", pr, "tail", pos));
      const audio = await measureMainFrames(ctx.api, 1200, ml.sampleRate);
      checks.push(audioCheck(t.label + " 能播(出声)", audio, 0.8));
      const probe = await probeMain(ctx, stack, ml, { label: t.label + "-尾奏", offsets, wide: true });
      checks.push(probe.check);
      checks.push(contentAtRequestCheck(t.label + " 内容=请求位置的内容(±2s)", probe, t.ms, seek.landing, ml));
      rows.push({ label: t.label, request: t.ms, landing: seek.landing, pos: Math.round(pos), phase: pr.phase, verdict: probe.result?.verdict ?? "unavailable" });
      ctx.evidence.step("seek-past-b", {
        label: t.label,
        requestMs: t.ms,
        landingMs: seek.landing,
        posMs: pos,
        phase: pr.phase,
        phaseSource: pr.source,
        facts: factsOf(st, ml),
        audio: { ratio: round(audio.ratio, 3), deltaFrames: audio.deltaFrames, available: audio.available },
        displayRefMs: probe.displayRefMs,
        probeMode: probe.mode,
        verdict: probe.result?.verdict ?? "unavailable",
        peak: probe.result?.peak ?? null,
        driftMs: probe.result?.driftMs ?? null,
        wav: probe.capture?.wavPath ?? null,
      });
    }
    // 尾奏里位置是否在前进（不是卡住不动）。
    const p0 = Number((await ctx.api.state()).playback.positionMs);
    await sleep(1000);
    const p1 = Number((await ctx.api.state()).playback.positionMs);
    checks.push(
      check(
        "尾奏里位置在前进(1s 内 ≥600ms)",
        Number.isFinite(p0) && Number.isFinite(p1) && p1 - p0 >= 600,
        "pos " + Math.round(p0) + " -> " + Math.round(p1) + "ms",
        p1 - p0 < 600 ? "尾奏没有推进（停在 B/被暂停/已到曲末）" : undefined,
      ),
    );
    await exitLoopHard(ctx);
    return {
      checks,
      summary: "尾奏 seek: " + rows.map((r) => r.label + " req=" + r.request + "->landing=" + (r.landing == null ? "?" : Math.round(r.landing)) + "(pos " + r.pos + "," + (r.phase ?? "?") + ")").join(" | "),
      pendingDecision: pending,
    };
  },
};

// ---------------------------------------------------------------------------
// 场景 65：尾奏播到曲末 —— 位置回 0（不是 A）、phase=prelude、继续出声
//
// 想抓：定稿语义"tail 播到曲末回 0（不是回 A）"。旧实现到不了 tail，自然到不了曲末回 0。
// [EXPECTED-FAIL 00028] 落点被夹回 B-1ms，曲末不可达。
// ---------------------------------------------------------------------------

const s65: Scenario = {
  id: 65,
  key: "tail-to-end-zero",
  name: "尾奏播到曲末:回到 0(不是 A),phase=prelude,继续出声",
  tag: "尾奏",
  timeoutMs: 180000,
  async run(ctx): Promise<ScenarioRunResult> {
    const checks: Check[] = [];
    const pending: string[] = [
      "定稿语义:tail 播到曲末后位置回 0、phase=prelude（不是回到 A）。这条与 UI 的曲末重播口径一致。",
    ];
    const song = await selectPreludeSong(ctx, { minA: 6000, minL: 6000, minTail: 8000 });
    const ml = await loadMainLoop(ctx, song);
    const stack = stackFor(ctx);
    ctx.evidence.step("setup", { covers: "lead 断言 5/7 + 批次需求 4/8", songId: song.id, aMs: ml.aMs, bMs: ml.bMs, L: ml.L, durationMs: ml.durationMs, songPath: ml.songPath });
    ctx.log("尾奏到曲末 #" + song.id + " A=" + ml.aMs + " B=" + ml.bMs + " duration=" + Math.round(ml.durationMs / 1000) + "s");

    // 先退出循环并离开结果页：预览页的 tap 与主播放器共用同一个 WAV，
    // 不挪走预览会把两路 PCM 混在一起，内容真值直接失效。
    await ctx.resetForMain();
    const play = await ensureSongPlaying(ctx, song.id);
    checks.push(check("前置:开始播放", play.ok, play.detail));
    if (!play.ok) return { checks, summary: "前置失败: " + play.detail, pendingDecision: pending };
    const enter = await enterLoopMain(ctx, ml);
    checks.push(check("进入循环模式(engine=loop)", enter.ok, enter.detail));
    if (!enter.ok) return { checks, summary: "循环源未接管: " + enter.detail, pendingDecision: pending };
    await sleep(800);

    const off: Offsets = { band: null, file: null };
    const cal = await calibrateAtCurrent(ctx, stack, ml, { label: "曲首基线", mode: "file" });
    checks.push(...cal.checks);
    if (cal.mode) off[cal.mode] = cal.offsetMs;
    const offsets = resolvedOffsets(off);

    const target = Math.max(ml.bMs + 500, ml.durationMs - 4000);
    const seek = await seekMain(ctx, target, { tolMs: 1500 });
    checks.push(check("seek 到尾末(duration-4s)落点=请求值(±1.5s)", seek.ok, seek.detail));
    const stTail = await ctx.api.state();
    const posTail = Number(stTail.playback.positionMs);
    const prTail = phaseOf(stTail, ml);
    checks.push(phaseCheck("尾末 phase=tail", prTail, "tail", posTail));
    const inTail = Number.isFinite(posTail) && posTail >= ml.bMs + 200 && posTail <= ml.durationMs;
    checks.push(
      check(
        "确实进入了尾奏(display > B)",
        inTail,
        "pos=" + Math.round(posTail) + "ms B=" + ml.bMs + " duration=" + ml.durationMs,
        inTail ? undefined : "00028: 尾奏不可达（seek 被夹回 B-1ms），无法测曲末回 0",
      ),
    );

    const monitor = new MainMonitor(ctx, ml, 100);
    monitor.start();
    const probeTail = await probeMain(ctx, stack, ml, { label: "尾末内容", offsets, wide: true });
    checks.push(probeTail.check);
    checks.push(contentAtRequestCheck("尾末内容=请求位置的内容(±2s)", probeTail, target, seek.landing, ml));

    let wrap: { ok: boolean; beforeMs: number | null; afterMs: number | null } = { ok: false, beforeMs: null, afterMs: null };
    if (inTail) {
      const timeout = Math.max(8000, ml.durationMs - Math.max(0, Number(seek.landing ?? target)) + 12000);
      wrap = await monitor.waitForWrapToZero(ml.durationMs, timeout);
      checks.push(
        check(
          "尾奏播到曲末后回到 0(不是 A)",
          wrap.ok && wrap.afterMs != null && wrap.afterMs <= 3000,
          wrap.ok
            ? "before=" + Math.round(Number(wrap.beforeMs)) + "ms -> after=" + Math.round(Number(wrap.afterMs)) + "ms"
            : "等待 " + Math.round(timeout / 1000) + "s 未观察到曲末回 0",
          wrap.ok ? undefined : "曲末没有回到 0（可能停在 ENDED / 回到 A / 被暂停）",
        ),
      );
    } else {
      checks.push(
        check(
          "尾奏播到曲末后回到 0(不是 A)",
          false,
          "落点不在尾奏(phase=" + (prTail.phase ?? "?") + ", pos=" + Math.round(posTail) + "ms)，本判据不可测",
          "先修 seek 到尾奏（00028）",
        ),
      );
    }
    await sleep(600);
    await monitor.stop();

    const stEnd = await ctx.api.state();
    const posEnd = Number(stEnd.playback.positionMs);
    const prEnd = phaseOf(stEnd, ml);
    checks.push(
      check(
        "回 0 后位置在曲首([0,5s])",
        Number.isFinite(posEnd) && posEnd >= 0 && posEnd <= PRELUDE_START_MAX_MS,
        "pos=" + (Number.isFinite(posEnd) ? Math.round(posEnd) : "n/a") + "ms",
        Number.isFinite(posEnd) && posEnd > PRELUDE_START_MAX_MS ? "曲末后没有回到曲首" : undefined,
      ),
    );
    checks.push(
      check(
        "回 0 后不是回到 A",
        ml.aMs < 8000 || !Number.isFinite(posEnd) || Math.abs(posEnd - ml.aMs) > 2000,
        "pos=" + Math.round(posEnd) + "ms A=" + ml.aMs + "ms",
        ml.aMs >= 8000 && Math.abs(posEnd - ml.aMs) <= 2000 ? "定稿语义是回 0，不是回 A" : undefined,
      ),
    );
    checks.push(phaseCheck("回 0 后 phase=prelude", prEnd, "prelude", posEnd));
    const audioEnd = await measureMainFrames(ctx.api, 1500, ml.sampleRate);
    checks.push(audioCheck("回 0 后继续出声", audioEnd, 0.8));
    const probe0 = await probeMain(ctx, stack, ml, { label: "回0后-曲首", offsets });
    checks.push(probe0.check);
    ctx.evidence.step("tail-to-end", {
      target,
      landing: seek.landing,
      posTail,
      phaseTail: prTail.phase,
      phaseTailSource: prTail.source,
      inTail,
      wrap,
      posEnd,
      phaseEnd: prEnd.phase,
      phaseEndSource: prEnd.source,
      facts: factsOf(stEnd, ml),
      tailVerdict: probeTail.result?.verdict ?? "unavailable",
      headVerdict: probe0.result?.verdict ?? "unavailable",
      offsets,
    });
    return {
      checks,
      summary:
        "尾奏->曲末: 落点=" + (seek.landing == null ? "?" : Math.round(Number(seek.landing))) + "ms phase=" + (prTail.phase ?? "?") +
        "; 曲末回 0=" + (wrap.ok ? Math.round(Number(wrap.afterMs)) + "ms" : "未观察到") + "; 回 0 后 pos=" + Math.round(posEnd) + "ms phase=" + (prEnd.phase ?? "?"),
      pendingDecision: pending,
    };
  },
};

// ---------------------------------------------------------------------------
// 场景 66：tail 中 seek 回 [A,B) —— phase=loop，B 处重新折返（内容真值）
//
// 想抓：tail 进得去、也回得来；回到环带后 B 处仍能折返（不能变成"一次性直通到曲末"）。
// [EXPECTED-FAIL 00028] 前半段（tail 不可达）FAIL；回到环带/折返部分旧实现可 PASS。
// ---------------------------------------------------------------------------

const s66: Scenario = {
  id: 66,
  key: "tail-back-to-loop",
  name: "tail 中 seek 回 [A,B):phase=loop,在 B 处重新折返",
  tag: "尾奏",
  timeoutMs: 180000,
  async run(ctx): Promise<ScenarioRunResult> {
    const checks: Check[] = [];
    const pending: string[] = ["定稿语义:tail 中 seek 回 [A,B) 必须恢复 loop 语义（到 B 折回 A），不能沿用 tail 的「直通到曲末」。"];
    const song = await selectPreludeSong(ctx, { minA: 6000, minL: 8000, maxL: 20000, minTail: 8000 });
    const ml = await loadMainLoop(ctx, song);
    const stack = stackFor(ctx);
    ctx.evidence.step("setup", { covers: "lead 断言 6/7 + 批次需求 3/8", songId: song.id, aMs: ml.aMs, bMs: ml.bMs, L: ml.L, durationMs: ml.durationMs, songPath: ml.songPath });
    ctx.log("tail->loop #" + song.id + " A=" + ml.aMs + " B=" + ml.bMs + " L=" + round(ml.L / 1000, 1) + "s");

    // 先退出循环并离开结果页：预览页的 tap 与主播放器共用同一个 WAV，
    // 不挪走预览会把两路 PCM 混在一起，内容真值直接失效。
    await ctx.resetForMain();
    const play = await ensureSongPlaying(ctx, song.id);
    checks.push(check("前置:开始播放", play.ok, play.detail));
    if (!play.ok) return { checks, summary: "前置失败: " + play.detail, pendingDecision: pending };
    const enter = await enterLoopMain(ctx, ml);
    checks.push(check("进入循环模式(engine=loop)", enter.ok, enter.detail));
    if (!enter.ok) return { checks, summary: "循环源未接管: " + enter.detail, pendingDecision: pending };
    await sleep(800);

    const off: Offsets = { band: null, file: null };
    const mid = Math.round(ml.aMs + ml.L / 2);
    const seekMid = await seekMain(ctx, mid, { tolMs: 1200 });
    checks.push(check("环带中段落点", seekMid.ok, seekMid.detail));
    const calBand = await calibrateAtCurrent(ctx, stack, ml, { label: "环带基线" });
    checks.push(...calBand.checks);
    if (calBand.mode) off[calBand.mode] = calBand.offsetMs;

    // 1) 进入 tail
    const tailTarget = Math.min(ml.durationMs - 3000, ml.bMs + 4000);
    const seekTail = await seekMain(ctx, tailTarget, { tolMs: 1500 });
    checks.push(
      check(
        "seek 到尾奏(B+4s)落点=请求值(±1.5s)",
        seekTail.ok,
        seekTail.detail + " [00028: 旧实现把尾奏 seek 夹回 B-1ms]",
      ),
    );
    const stTail = await ctx.api.state();
    const posTail = Number(stTail.playback.positionMs);
    const prTail = phaseOf(stTail, ml);
    checks.push(phaseCheck("尾奏 phase=tail", prTail, "tail", posTail));
    const probeTail = await probeMain(ctx, stack, ml, { label: "尾奏内容", offsets: resolvedOffsets(off), wide: true });
    checks.push(probeTail.check);

    // 2) 回环带
    const seekBack = await seekMain(ctx, mid, { tolMs: 1500 });
    checks.push(check("从 tail seek 回环带中段落点=请求值(±1.5s)", seekBack.ok, seekBack.detail));
    const stBack = await ctx.api.state();
    const posBack = Number(stBack.playback.positionMs);
    const prBack = phaseOf(stBack, ml);
    checks.push(phaseCheck("回环带后 phase=loop", prBack, "loop", posBack));
    checks.push(
      check(
        "回环带后位置在 [A,B)",
        Number.isFinite(posBack) && posBack >= ml.aMs - 1000 && posBack <= ml.bMs + 1000,
        "pos=" + Math.round(posBack) + "ms A=" + ml.aMs + " B=" + ml.bMs,
      ),
    );
    const probeBack = await probeMain(ctx, stack, ml, { label: "回环带后内容", offsets: resolvedOffsets(off), wide: true });
    checks.push(probeBack.check);

    // 3) B 处重新折返
    const seekNearB = await seekMain(ctx, ml.bMs - 2000, { tolMs: 1200 });
    checks.push(check("seek 到 B-2s 落点=请求值", seekNearB.ok, seekNearB.detail));
    const monitor = new MainMonitor(ctx, ml, 100);
    monitor.start();
    const wrap = await monitor.waitForWrap(ml.L, 12000);
    checks.push(
      check(
        "回到环带后仍在 B 处折返",
        wrap.ok && wrap.afterMs != null && wrap.afterMs <= ml.aMs + 4000,
        wrap.ok
          ? "before=" + Math.round(Number(wrap.beforeMs)) + "ms -> after=" + Math.round(Number(wrap.afterMs)) + "ms"
          : "12s 内没有折返（可能被当成 tail 直通到曲末）",
        wrap.ok ? undefined : "回环带后没有恢复折返：tail→loop 切换没有重建环回语义",
      ),
    );
    await sleep(700);
    await monitor.stop();
    const stWrap = await ctx.api.state();
    const posWrap = Number(stWrap.playback.positionMs);
    const prWrap = phaseOf(stWrap, ml);
    checks.push(phaseCheck("折返后 phase=loop", prWrap, "loop", posWrap));
    const probeWrap = await probeMain(ctx, stack, ml, { label: "折返后(应在 A 附近)", offsets: resolvedOffsets(off), wide: true });
    checks.push(probeWrap.check);
    const audio = await measureMainFrames(ctx.api, 1500, ml.sampleRate);
    checks.push(audioCheck("折返后出声", audio, 0.85));
    ctx.evidence.step("tail-back", {
      tailTarget,
      tailLanding: seekTail.landing,
      posTail,
      phaseTail: prTail.phase,
      posBack,
      phaseBack: prBack.phase,
      posWrap,
      phaseWrap: prWrap.phase,
      wrap,
      facts: factsOf(stWrap, ml),
      verdicts: {
        tail: probeTail.result?.verdict ?? "unavailable",
        back: probeBack.result?.verdict ?? "unavailable",
        wrap: probeWrap.result?.verdict ?? "unavailable",
      },
      offsets: resolvedOffsets(off),
    });
    return {
      checks,
      summary:
        "tail->loop: tail pos=" + Math.round(posTail) + "(" + (prTail.phase ?? "?") + ") -> back pos=" + Math.round(posBack) +
        "(" + (prBack.phase ?? "?") + ") -> wrap=" + (wrap.ok ? "yes" : "no") + "; 折返后 pos=" + Math.round(posWrap),
      pendingDecision: pending,
    };
  },
};

// ---------------------------------------------------------------------------
// 场景 67：出循环后位置回到媒体时间轴（不能停在环带时间轴 T）
//
// 想抓：/loop/exit 后显示位置若还是 SOURCE 的 T（= display - A），用户会看到"进度条倒退一大截"。
// 判据：退出后 position≈退出前 display、且不等于 T；并且能 seek 到 A 之前（恢复整曲可达）。
// 旧实现这条应 PASS（作为"判据不误杀"的对照）。
// ---------------------------------------------------------------------------

const s67: Scenario = {
  id: 67,
  key: "exit-loop-media-timeline",
  name: "出循环后位置回媒体时间轴(不是环带 T),且可 seek 到 A 之前",
  tag: "边界",
  timeoutMs: 180000,
  async run(ctx): Promise<ScenarioRunResult> {
    const checks: Check[] = [];
    const pending: string[] = [
      "定稿语义:退出循环后位置回到媒体时间轴（A+floorMod(T,L)），不是停在环带源的 T；且 [0,A) 重新可达。",
    ];
    const song = await selectPreludeSong(ctx, { minA: 12000, minL: 8000, minTail: 5000 });
    const ml = await loadMainLoop(ctx, song);
    const stack = stackFor(ctx);
    ctx.evidence.step("setup", { covers: "批次需求 5/8", songId: song.id, aMs: ml.aMs, bMs: ml.bMs, L: ml.L, durationMs: ml.durationMs, songPath: ml.songPath });
    ctx.log("退出循环 #" + song.id + " A=" + ml.aMs + " B=" + ml.bMs + " L=" + round(ml.L / 1000, 1) + "s");

    // 先退出循环并离开结果页：预览页的 tap 与主播放器共用同一个 WAV，
    // 不挪走预览会把两路 PCM 混在一起，内容真值直接失效。
    await ctx.resetForMain();
    const play = await ensureSongPlaying(ctx, song.id);
    checks.push(check("前置:开始播放", play.ok, play.detail));
    if (!play.ok) return { checks, summary: "前置失败: " + play.detail, pendingDecision: pending };
    const enter = await enterLoopMain(ctx, ml);
    checks.push(check("进入循环模式(engine=loop)", enter.ok, enter.detail));
    if (!enter.ok) return { checks, summary: "循环源未接管: " + enter.detail, pendingDecision: pending };
    await sleep(700);

    const mid = Math.round(ml.aMs + ml.L / 2);
    const seekMid = await seekMain(ctx, mid, { tolMs: 1200 });
    checks.push(check("循环中 seek 到环带中段落点=请求值", seekMid.ok, seekMid.detail));
    await sleep(700);
    const audioBefore = await measureMainFrames(ctx.api, 1200, ml.sampleRate);
    checks.push(audioCheck("退出前出声", audioBefore, 0.8));

    const off: Offsets = { band: null, file: null };
    const calBand = await calibrateAtCurrent(ctx, stack, ml, { label: "环带基线" });
    checks.push(...calBand.checks);
    if (calBand.mode) off[calBand.mode] = calBand.offsetMs;
    const probeBand = await probeMain(ctx, stack, ml, { label: "退出前环带内容", offsets: resolvedOffsets(off) });
    checks.push(probeBand.check);

    // 退出前最后一眼：displayBefore 必须紧贴 /loop/exit（中间隔着几秒的探测会让"位置≈退出前 display"失真）。
    const stBefore = await ctx.api.state();
    const displayBefore = Number(stBefore.playback.positionMs);
    const prBefore = phaseOf(stBefore, ml);
    checks.push(phaseCheck("退出前 phase=loop", prBefore, "loop", displayBefore));

    const exit = await exitLoopHard(ctx, 10000);
    checks.push(check("退出循环(engine≠loop,window=null)", exit.ok && exit.attempted, exit.detail));
    await sleep(700);
    const stAfter = await ctx.api.state();
    const posAfter = Number(stAfter.playback.positionMs);
    const prAfter = phaseOf(stAfter, ml);
    const tEquivalent = displayBefore - ml.aMs;
    checks.push(
      check(
        "退出后不再声称循环(window=null, claimActive=false)",
        loopWindowOf(stAfter) === null && !claimActiveOf(stAfter),
        "window=" + (loopWindowOf(stAfter) ? "still" : "null") + " claimActive=" + claimActiveOf(stAfter) +
          " phase=" + (prAfter.phase ?? "null") + " 口径=" + prAfter.source,
        loopWindowOf(stAfter) !== null || claimActiveOf(stAfter) ? "退出后仍声称在循环：窗口/claim 没有清干净" : undefined,
      ),
    );
    checks.push(
      check(
        "退出后位置=媒体时间轴(≈退出前 display)",
        Number.isFinite(posAfter) && Math.abs(posAfter - displayBefore) <= 3500,
        "退出前 display=" + Math.round(displayBefore) + "ms 退出后 pos=" + Math.round(posAfter) + "ms (容差 3.5s; 期间在播放)",
        Math.abs(posAfter - displayBefore) > 3500 ? "退出后位置跳变：没有把源时间轴 T 映射回媒体时间" : undefined,
      ),
    );
    checks.push(
      check(
        "退出后位置没有停在环带 T(=display-A)",
        ml.aMs < 6000 || !Number.isFinite(posAfter) || Math.abs(posAfter - tEquivalent) > 3000,
        "退出后 pos=" + Math.round(posAfter) + "ms; T 等价=" + Math.round(tEquivalent) + "ms (display " + Math.round(displayBefore) + " - A " + ml.aMs + ")",
        ml.aMs >= 6000 && Math.abs(posAfter - tEquivalent) <= 3000 ? "位置停在了环带时间轴 T：媒体时间轴丢失（用户会看到进度倒退）" : undefined,
      ),
    );
    checks.push(check("退出后仍在播放", stAfter.playback?.playing === true, "playing=" + stAfter.playback?.playing + " state=" + stAfter.playback?.playbackState));
    const audioAfter = await measureMainFrames(ctx.api, 1400, ml.sampleRate);
    checks.push(audioCheck("退出后继续出声", audioAfter, 0.85));

    const beforeATarget = Math.max(0, ml.aMs - 10000);
    const seekBefore = await seekMain(ctx, beforeATarget, { tolMs: 1200 });
    checks.push(check("退出后可 seek 到 A 之前(±1.2s)", seekBefore.ok, seekBefore.detail));
    const stMedia = await ctx.api.state();
    const posMedia = Number(stMedia.playback.positionMs);
    checks.push(
      check(
        "媒体时间轴可达(位置=A-10s 附近)",
        Number.isFinite(posMedia) && Math.abs(posMedia - beforeATarget) <= 1500,
        "请求=" + beforeATarget + "ms pos=" + Math.round(posMedia) + "ms A=" + ml.aMs,
        Number.isFinite(posMedia) && Math.abs(posMedia - beforeATarget) > 1500 ? "退出后仍到不了 A 之前：媒体时间轴没恢复" : undefined,
      ),
    );
    const prMedia = phaseOf(stMedia, ml);
    checks.push(
      check(
        "A 之前(退出循环后)按媒体时间解释位置",
        loopWindowOf(stMedia) === null && !claimActiveOf(stMedia),
        "window=" + (loopWindowOf(stMedia) ? "still" : "null") + " claimActive=" + claimActiveOf(stMedia) +
          " phase=" + (prMedia.phase ?? "null") + " pos=" + Math.round(posMedia) + "ms",
        loopWindowOf(stMedia) !== null || claimActiveOf(stMedia) ? "退出循环后窗口仍在：位置会被再次折返" : undefined,
      ),
    );
    const probeMedia = await probeMain(ctx, stack, ml, { label: "A 之前内容(媒体时间轴)", offsets: resolvedOffsets(off), wide: true });
    checks.push(probeMedia.check);
    checks.push(contentAtRequestCheck("A 之前内容=请求位置的内容(±2s)", probeMedia, beforeATarget, seekBefore.landing, ml));
    ctx.evidence.step("exit-media-timeline", {
      displayBefore,
      phaseBefore: prBefore.phase,
      exit,
      posAfter,
      tEquivalent,
      phaseAfter: prAfter.phase,
      beforeATarget,
      mediaLanding: seekBefore.landing,
      posMedia,
      facts: factsOf(stMedia, ml),
      verdicts: {
        band: probeBand.result?.verdict ?? "unavailable",
        media: probeMedia.result?.verdict ?? "unavailable",
      },
      offsets: resolvedOffsets(off),
    });
    return {
      checks,
      summary:
        "退出: display " + Math.round(displayBefore) + " -> pos " + Math.round(posAfter) + "ms (T 等价 " + Math.round(tEquivalent) +
        "ms); A 之前 seek=" + (seekBefore.landing == null ? "?" : Math.round(Number(seekBefore.landing))) + "ms",
      pendingDecision: pending,
    };
  },
};

// ---------------------------------------------------------------------------
// 场景 68：循环中把 A 改到播放头之后
//
// 想抓：00028 同类 —— 改窗口后播放头被重置到新 A；以及"新窗口生效后位置/相位/内容是否自洽"。
// 协议说明：没有"不重启改 A/B"的入口，这里用 /confirm（会按新窗口重新起播）近似 UI 拖动，
// 断言的是**新窗口生效后**的自洽性（不是旧位置被夹）。旧实现会落在新 A -> 位置/相位 FAIL。
// ---------------------------------------------------------------------------

const s68: Scenario = {
  id: 68,
  key: "move-a-past-current",
  name: "循环中把 A 改到播放头之后:位置/相位/内容自洽",
  tag: "边界",
  exclusive: true,
  timeoutMs: 240000,
  async run(ctx): Promise<ScenarioRunResult> {
    const checks: Check[] = [];
    const pending: string[] = [
      "协议侧没有'不重启改 A/B'的入口（拖动 A/B 是 UI 手势，脚本化不可靠）；这里用 POST /confirm 近似（会按新窗口重新起播）。断言的是'新窗口生效后位置/相位/内容三者自洽'，不是'旧位置被夹'。若后续提供 PATCH /loop/window，应改为原地改窗口后重测。",
    ];
    const song = await selectPreludeSong(ctx, { minA: 6000, minL: 15000, minTail: 5000 });
    const base = await loadMainLoop(ctx, song);
    let live: MainLoop = { ...base };
    const stack = stackFor(ctx);
    const off: Offsets = { band: null, file: null };
    ctx.evidence.step("setup", { covers: "批次需求 6/8", songId: song.id, aMs: base.aMs, bMs: base.bMs, L: base.L, durationMs: base.durationMs, songPath: base.songPath });
    try {
      // 先退出循环并离开结果页：预览页的 tap 与主播放器共用同一个 WAV，
      // 不挪走预览会把两路 PCM 混在一起，内容真值直接失效。
      await ctx.resetForMain();
      const play = await ensureSongPlaying(ctx, song.id);
      checks.push(check("前置:开始播放", play.ok, play.detail));
      if (!play.ok) return { checks, summary: "前置失败: " + play.detail, pendingDecision: pending };
      const enter = await enterLoopMain(ctx, live);
      checks.push(check("进入循环模式(engine=loop)", enter.ok, enter.detail));
      if (!enter.ok) return { checks, summary: "循环源未接管: " + enter.detail, pendingDecision: pending };
      await sleep(700);

      const p = Math.round(live.aMs + live.L * 0.6);
      const seekP = await seekMain(ctx, p, { tolMs: 1200 });
      checks.push(check("改 A 前:播放头在环带内(0.6L)", seekP.ok, seekP.detail));
      await sleep(700);
      const cal = await calibrateAtCurrent(ctx, stack, live, { label: "环带基线" });
      checks.push(...cal.checks);
      if (cal.mode) off[cal.mode] = cal.offsetMs;

      const aNew = Math.round(p + 3000);
      if (aNew >= live.bMs - 1000) throw newSkipError("环带太短，无法把 A 拖到播放头之后（L=" + live.L + "ms）");
      const confirm = await ctx.api.post("/confirm", {
        songId: live.song.id,
        aSamples: msToSamples(aNew, live.sampleRate),
        bSamples: base.origBSamples,
      });
      checks.push(check("改 A 被接受(HTTP 200)", confirm.status === 200, "status=" + confirm.status + " " + String(confirm.text).slice(0, 160)));
      const winReady = await waitFor(
        async () => ctx.api.state(),
        (s) => {
          const w = loopWindowOf(s);
          return w != null && Math.abs(w.aMs - aNew) <= 300 && Math.abs(w.bMs - live.bMs) <= 300;
        },
        20000,
        150,
      );
      checks.push(
        check(
          "新 A 生效(A'=" + aNew + "ms, B 不变)",
          winReady.ok,
          winReady.ok ? "window=" + aNew + ".." + live.bMs : "20s 内窗口未更新: " + JSON.stringify(factsOf(winReady.value, live)),
        ),
      );
      const loopBack = await waitFor(async () => ctx.api.state(), (s) => s.playback?.engine === "loop", 15000, 150);
      checks.push(check("新窗口循环源已接管(engine=loop)", loopBack.ok, "engine=" + (loopBack.value?.playback?.engine ?? "?")));
      if (!winReady.ok || !loopBack.ok) {
        return { checks, summary: "改 A 后窗口/源未生效，后续不可判", pendingDecision: pending };
      }
      live = { ...live, aMs: aNew, L: live.bMs - aNew };
      await sleep(900);

      const st = await ctx.api.state();
      const pos = Number(st.playback.positionMs);
      const pr = phaseOf(st, live);
      const finite = Number.isFinite(pos);
      checks.push(
        check(
          "改 A 后位置=前奏起点(0..5s),没跳到新 A",
          finite && pos >= 0 && pos <= PRELUDE_START_MAX_MS,
          "pos=" + (finite ? Math.round(pos) : "n/a") + "ms 新 A'=" + aNew + "ms 原播放头=" + p + "ms",
          finite && pos > PRELUDE_START_MAX_MS ? "00028 同类: 改窗口后播放头被重置到 A'" : undefined,
        ),
      );
      checks.push(
        check(
          "改 A 后位置未跳到新 A(00028 现象)",
          !finite || Math.abs(pos - aNew) > 2000,
          "pos=" + (finite ? Math.round(pos) : "n/a") + " 新 A'=" + aNew + " 距离=" + (finite ? Math.round(Math.abs(pos - aNew)) : "n/a") + "ms",
        ),
      );
      checks.push(phaseCheck("改 A 后 phase=prelude", pr, "prelude", pos));
      const audio0 = await measureMainFrames(ctx.api, 1200, live.sampleRate);
      checks.push(audioCheck("改 A 后出声", audio0, 0.8));
      const probe0 = await probeMain(ctx, stack, live, { label: "改A后-前奏", offsets: resolvedOffsets(off), wide: true });
      checks.push(probe0.check);

      const preTarget = Math.max(0, aNew - 2000);
      const seekPre = await seekMain(ctx, preTarget, { tolMs: 1500 });
      checks.push(check("新 A 之前(前奏)seek 落点=请求值(±1.5s)", seekPre.ok, seekPre.detail));
      const stPre = await ctx.api.state();
      checks.push(phaseCheck("新 A 之前 phase=prelude", phaseOf(stPre, live), "prelude", Number(stPre.playback.positionMs)));
      const probePre = await probeMain(ctx, stack, live, { label: "新A之前-前奏", offsets: resolvedOffsets(off) });
      checks.push(probePre.check);
      checks.push(contentAtRequestCheck("新 A 之前内容=请求位置(±2s)", probePre, preTarget, seekPre.landing, live));

      const inTarget = aNew + 800;
      const seekIn = await seekMain(ctx, inTarget, { tolMs: 1500 });
      checks.push(check("新 A 之后(环带)seek 落点=请求值(±1.5s)", seekIn.ok, seekIn.detail));
      const stIn = await ctx.api.state();
      checks.push(phaseCheck("新 A 之后 phase=loop", phaseOf(stIn, live), "loop", Number(stIn.playback.positionMs)));
      const probeIn = await probeMain(ctx, stack, live, { label: "新A之后-环带", offsets: resolvedOffsets(off), wide: true });
      checks.push(probeIn.check);
      ctx.evidence.step("move-a", {
        oldA: base.aMs,
        playbackHead: p,
        newA: aNew,
        confirm: { status: confirm.status, body: confirm.json },
        window: loopWindowOf(await ctx.api.state()),
        posAfterMove: pos,
        phaseAfterMove: pr.phase,
        phaseSource: pr.source,
        preTarget,
        inTarget,
        facts: factsOf(stIn, live),
        verdicts: {
          after: probe0.result?.verdict ?? "unavailable",
          prelude: probePre.result?.verdict ?? "unavailable",
          loop: probeIn.result?.verdict ?? "unavailable",
        },
        offsets: resolvedOffsets(off),
      });
      return {
        checks,
        summary:
          "A " + base.aMs + " -> " + aNew + "ms (播放头 " + p + "ms); 改后 pos=" + Math.round(pos) + "ms phase=" + (pr.phase ?? "?") +
          "; 新 A 之前 " + (probePre.result?.verdict ?? "?") + " / 之后 " + (probeIn.result?.verdict ?? "?"),
        pendingDecision: pending,
      };
    } finally {
      checks.push(await restoreLoopRow(ctx, base));
    }
  },
};

// ---------------------------------------------------------------------------
// 场景 69：循环中把 B 改到播放头之前
//
// 想抓：B 被拖到播放头之前后，位置应落在 tail（> 新 B）且内容一致；seek 回环带后仍能在新 B' 折返。
// 旧实现：改窗口后台 restart 落在新 A（相位/位置 FAIL），tail 不可达。
// ---------------------------------------------------------------------------

const s69: Scenario = {
  id: 69,
  key: "move-b-before-current",
  name: "循环中把 B 改到播放头之前:tail 可达并在新 B' 折返",
  tag: "边界",
  exclusive: true,
  timeoutMs: 240000,
  async run(ctx): Promise<ScenarioRunResult> {
    const checks: Check[] = [];
    const pending: string[] = [
      "协议侧没有'不重启改 A/B'的入口；这里用 POST /confirm 近似（会按新窗口重新起播），再 seek 到新 B' 之后复现'播放头在新 B 之外'的现场。断言：tail 可达 + 内容一致 + 回环带后仍在新 B' 折返。00028 未修时改窗口后播放头会落回 A、tail 不可达，预期部分 FAIL。",
    ];
    const song = await selectPreludeSong(ctx, { minA: 6000, minL: 15000, minTail: 5000 });
    const base = await loadMainLoop(ctx, song);
    let live: MainLoop = { ...base };
    const stack = stackFor(ctx);
    const off: Offsets = { band: null, file: null };
    ctx.evidence.step("setup", { covers: "批次需求 7/8", songId: song.id, aMs: base.aMs, bMs: base.bMs, L: base.L, durationMs: base.durationMs, songPath: base.songPath });
    try {
      // 先退出循环并离开结果页：预览页的 tap 与主播放器共用同一个 WAV，
      // 不挪走预览会把两路 PCM 混在一起，内容真值直接失效。
      await ctx.resetForMain();
      const play = await ensureSongPlaying(ctx, song.id);
      checks.push(check("前置:开始播放", play.ok, play.detail));
      if (!play.ok) return { checks, summary: "前置失败: " + play.detail, pendingDecision: pending };
      const enter = await enterLoopMain(ctx, live);
      checks.push(check("进入循环模式(engine=loop)", enter.ok, enter.detail));
      if (!enter.ok) return { checks, summary: "循环源未接管: " + enter.detail, pendingDecision: pending };
      await sleep(700);

      const p = Math.round(live.aMs + live.L * 0.6);
      const seekP = await seekMain(ctx, p, { tolMs: 1200 });
      checks.push(check("改 B 前:播放头在环带内(0.6L)", seekP.ok, seekP.detail));
      await sleep(700);
      const calBand = await calibrateAtCurrent(ctx, stack, live, { label: "环带基线" });
      checks.push(...calBand.checks);
      if (calBand.mode) off[calBand.mode] = calBand.offsetMs;

      const bNew = Math.round(p - 5000);
      if (bNew <= live.aMs + 2000) throw newSkipError("环带太短，无法把 B 拖到播放头之前（L=" + live.L + "ms）");
      const confirm = await ctx.api.post("/confirm", {
        songId: live.song.id,
        aSamples: base.origASamples,
        bSamples: msToSamples(bNew, live.sampleRate),
      });
      checks.push(check("改 B 被接受(HTTP 200)", confirm.status === 200, "status=" + confirm.status + " " + String(confirm.text).slice(0, 160)));
      const winReady = await waitFor(
        async () => ctx.api.state(),
        (s) => {
          const w = loopWindowOf(s);
          return w != null && Math.abs(w.bMs - bNew) <= 300 && Math.abs(w.aMs - live.aMs) <= 300;
        },
        20000,
        150,
      );
      checks.push(
        check(
          "新 B 生效(B'=" + bNew + "ms, A 不变)",
          winReady.ok,
          winReady.ok ? "window=" + live.aMs + ".." + bNew : "20s 内窗口未更新: " + JSON.stringify(factsOf(winReady.value, live)),
        ),
      );
      const loopBack = await waitFor(async () => ctx.api.state(), (s) => s.playback?.engine === "loop", 15000, 150);
      checks.push(check("新窗口循环源已接管(engine=loop)", loopBack.ok, "engine=" + (loopBack.value?.playback?.engine ?? "?")));
      if (!winReady.ok || !loopBack.ok) {
        return { checks, summary: "改 B 后窗口/源未生效，后续不可判", pendingDecision: pending };
      }
      live = { ...live, bMs: bNew, L: bNew - live.aMs };
      await sleep(900);

      const st0 = await ctx.api.state();
      const pos0 = Number(st0.playback.positionMs);
      const pr0 = phaseOf(st0, live);
      checks.push(
        check(
          "改 B 后位置回到前奏起点(0..5s)",
          Number.isFinite(pos0) && pos0 >= 0 && pos0 <= PRELUDE_START_MAX_MS,
          "pos=" + Math.round(pos0) + "ms (新 B'=" + bNew + ", 原播放头=" + p + ")",
          pos0 > PRELUDE_START_MAX_MS ? "改窗口后播放头没有回到前奏" : undefined,
        ),
      );
      checks.push(phaseCheck("改 B 后 phase=prelude", pr0, "prelude", pos0));
      const calFile = await calibrateAtCurrent(ctx, stack, live, { label: "前奏/曲首基线", mode: "file" });
      checks.push(...calFile.checks);
      if (calFile.mode) off[calFile.mode] = calFile.offsetMs;
      const probe0 = await probeMain(ctx, stack, live, { label: "改B后-前奏", offsets: resolvedOffsets(off), wide: true });
      checks.push(probe0.check);

      // 新 B 之前的位置（原播放头）现在落在 tail：seek 过去应能播且内容一致。
      const seekTail = await seekMain(ctx, p, { tolMs: 1500 });
      checks.push(check("seek 到新 B 之后(tail)落点=请求值(±1.5s)", seekTail.ok, seekTail.detail));
      const stTail = await ctx.api.state();
      const posTail = Number(stTail.playback.positionMs);
      const prTail = phaseOf(stTail, live);
      checks.push(phaseCheck("新 B 之后 phase=tail", prTail, "tail", posTail));
      const probeTail = await probeMain(ctx, stack, live, { label: "新B之后-尾奏", offsets: resolvedOffsets(off), wide: true });
      checks.push(probeTail.check);
      checks.push(contentAtRequestCheck("新 B 之后内容=请求位置(±2s)", probeTail, p, seekTail.landing, live));

      // 回环带：仍然要能在新 B' 折返。
      const backTarget = Math.round(live.aMs + live.L / 2);
      const seekBack = await seekMain(ctx, backTarget, { tolMs: 1500 });
      checks.push(check("from tail seek 回新环带中段落点=请求值", seekBack.ok, seekBack.detail));
      const stBack = await ctx.api.state();
      const posBack = Number(stBack.playback.positionMs);
      const prBack = phaseOf(stBack, live);
      checks.push(phaseCheck("回环带后 phase=loop", prBack, "loop", posBack));
      const probeBack = await probeMain(ctx, stack, live, { label: "新环带内容", offsets: resolvedOffsets(off), wide: true });
      checks.push(probeBack.check);

      const seekNearB = await seekMain(ctx, Math.max(live.aMs + 500, live.bMs - 2000), { tolMs: 1200 });
      checks.push(check("seek 到新 B'-2s 落点=请求值", seekNearB.ok, seekNearB.detail));
      const monitor = new MainMonitor(ctx, live, 100);
      monitor.start();
      const wrap = await monitor.waitForWrap(live.L, 12000);
      checks.push(
        check(
          "在新 B' 处折回 A",
          wrap.ok && wrap.afterMs != null && wrap.afterMs <= live.aMs + 4000,
          wrap.ok
            ? "before=" + Math.round(Number(wrap.beforeMs)) + "ms -> after=" + Math.round(Number(wrap.afterMs)) + "ms (A=" + live.aMs + " B'=" + live.bMs + ")"
            : "12s 内没有在新 B' 处折返",
          wrap.ok ? undefined : "改 B 后环回没有跟随新窗口",
        ),
      );
      await sleep(700);
      await monitor.stop();
      const stWrap = await ctx.api.state();
      const posWrap = Number(stWrap.playback.positionMs);
      const prWrap = phaseOf(stWrap, live);
      checks.push(phaseCheck("折返后 phase=loop", prWrap, "loop", posWrap));
      const probeWrap = await probeMain(ctx, stack, live, { label: "折返后(应在 A 附近)", offsets: resolvedOffsets(off), wide: true });
      checks.push(probeWrap.check);
      const audio = await measureMainFrames(ctx.api, 1500, live.sampleRate);
      checks.push(audioCheck("折返后出声", audio, 0.85));
      ctx.evidence.step("move-b", {
        oldB: base.bMs,
        playbackHead: p,
        newB: bNew,
        confirm: { status: confirm.status, body: confirm.json },
        posAfterMove: pos0,
        tailTarget: p,
        tailLanding: seekTail.landing,
        posTail,
        phaseTail: prTail.phase,
        posBack,
        phaseBack: prBack.phase,
        wrap,
        posWrap,
        phaseWrap: prWrap.phase,
        facts: factsOf(stWrap, live),
        verdicts: {
          prelude: probe0.result?.verdict ?? "unavailable",
          tail: probeTail.result?.verdict ?? "unavailable",
          back: probeBack.result?.verdict ?? "unavailable",
          wrap: probeWrap.result?.verdict ?? "unavailable",
        },
        offsets: resolvedOffsets(off),
      });
      return {
        checks,
        summary:
          "B " + base.bMs + " -> " + bNew + "ms (播放头 " + p + "ms); tail pos=" + Math.round(posTail) + "(" + (prTail.phase ?? "?") +
          "); 新 B' 折返=" + (wrap.ok ? "yes" : "no") + "; 折返后 pos=" + Math.round(posWrap),
        pendingDecision: pending,
      };
    } finally {
      checks.push(await restoreLoopRow(ctx, base));
    }
  },
};

// ---------------------------------------------------------------------------
// 场景 70：播放中进循环 —— 80ms 采样监控进循环瞬间从不跳到 A
//
// 想抓：00028 的现场（正在播 -> 点无缝循环 -> 立刻跳到 A）。瞬时跳变只有高频采样能抓到。
// [EXPECTED-FAIL 00028] 旧实现监控窗内位置会出现在 A 附近。
// ---------------------------------------------------------------------------

const s70: Scenario = {
  id: 70,
  key: "enter-no-jump-a",
  name: "播放中进循环:80ms 采样监控,从不跳到 A",
  tag: "前奏",
  timeoutMs: 180000,
  async run(ctx): Promise<ScenarioRunResult> {
    const checks: Check[] = [];
    const pending: string[] = [
      "定稿语义:进循环后从曲首前奏起播；'跳到 A' 是本批要消灭的现象（00028）。",
    ];
    const song = await selectPreludeSong(ctx, { minA: 12000, minL: 8000, minTail: 5000 });
    const ml = await loadMainLoop(ctx, song);
    const stack = stackFor(ctx);
    ctx.evidence.step("setup", { covers: "lead 断言 1/7 + 批次需求 1/8", songId: song.id, aMs: ml.aMs, bMs: ml.bMs, L: ml.L, durationMs: ml.durationMs, songPath: ml.songPath });
    ctx.log("进循环瞬间 #" + song.id + " A=" + ml.aMs + " B=" + ml.bMs);

    // 先退出循环并离开结果页：预览页的 tap 与主播放器共用同一个 WAV，
    // 不挪走预览会把两路 PCM 混在一起，内容真值直接失效。
    await ctx.resetForMain();
    const play = await ensureSongPlaying(ctx, song.id);
    checks.push(check("前置:开始播放", play.ok, play.detail));
    if (!play.ok) return { checks, summary: "前置失败: " + play.detail, pendingDecision: pending };

    const startAt = Math.round(ml.aMs + ml.L / 2);
    const seek = await seekMain(ctx, startAt, { tolMs: 1200 });
    checks.push(check("前置:播放头在环带中段(0.5L)", seek.ok, seek.detail));
    await sleep(500);
    const stBefore = await ctx.api.state();
    const posBefore = Number(stBefore.playback.positionMs);

    const monitor = new MainMonitor(ctx, ml, 80);
    monitor.start();
    const enter = await enterLoopMain(ctx, ml);
    checks.push(check("播放中进循环(engine=loop)", enter.ok, enter.detail));
    await sleep(1800);
    await monitor.stop();

    const st = await ctx.api.state();
    const pos = Number(st.playback.positionMs);
    const pr = phaseOf(st, ml);
    // 只统计"进循环之后"的采样：进循环前播放头本来就在环带中段，不能拿来判"进循环后越过环带"。
    const afterEnter = monitor.series.filter((s) => s.engine === "loop");
    const nearA = afterEnter.filter((s) => s.posMs != null && Math.abs(Number(s.posMs) - ml.aMs) <= 1000);
    const maxPosAfter = Math.max(0, ...afterEnter.filter((s) => s.posMs != null).map((s) => Number(s.posMs)));
    const seriesForEvidence = afterEnter.filter((_, i) => i % 5 === 0);
    checks.push(
      check(
        "进循环后位置=前奏起点(0..5s)",
        Number.isFinite(pos) && pos >= 0 && pos <= PRELUDE_START_MAX_MS,
        "pos=" + (Number.isFinite(pos) ? Math.round(pos) : "n/a") + "ms (进循环前 " + Math.round(posBefore) + "ms; A=" + ml.aMs + ")",
        Number.isFinite(pos) && pos > PRELUDE_START_MAX_MS ? "00028: 进循环后播放头不在前奏" : undefined,
      ),
    );
    checks.push(
      check(
        "监控窗内从未跳到 A(±1s)",
        nearA.length === 0,
        "落在 A±1s 的采样 " + nearA.length + "/" + afterEnter.length + " 个（进循环后） (A=" + ml.aMs + "ms, 采样 80ms)",
        nearA.length > 0 ? "00028 现场：进循环瞬间播放头跳到 A（瞬时跳变，低频轮询抓不到）" : undefined,
      ),
    );
    checks.push(
      check(
        "监控窗内位置没有越过环带(A+1s)",
        ml.aMs < 8000 || maxPosAfter < ml.aMs + 1000,
        "监控窗内最大位置=" + Math.round(maxPosAfter) + "ms A=" + ml.aMs + "ms",
        ml.aMs >= 8000 && maxPosAfter >= ml.aMs + 1000 ? "进循环后播放头进入了环带（应停在前奏 0..5s）" : undefined,
      ),
    );
    checks.push(phaseCheck("进循环后 phase=prelude", pr, "prelude", pos));
    const audio = await measureMainFrames(ctx.api, 1200, ml.sampleRate);
    checks.push(audioCheck("前奏出声", audio, 0.8));
    const off: Offsets = { band: null, file: null };
    const cal = await calibrateAtCurrent(ctx, stack, ml, { label: "基线", mode: "file" });
    checks.push(...cal.checks);
    if (cal.mode) off[cal.mode] = cal.offsetMs;
    const probe = await probeMain(ctx, stack, ml, { label: "进循环后前奏内容", offsets: resolvedOffsets(off), wide: true });
    checks.push(probe.check);
    ctx.evidence.step("enter-monitor", {
      posBefore,
      posAfter: pos,
      phaseAfter: pr.phase,
      phaseSource: pr.source,
      samples: monitor.series.length,
      samplesAfterEnter: afterEnter.length,
      samplesNearA: nearA.map((s) => ({ atMs: s.atMs, posMs: s.posMs })),
      maxPosAfter,
      series: seriesForEvidence,
      facts: factsOf(st, ml),
      verdict: probe.result?.verdict ?? "unavailable",
      peak: probe.result?.peak ?? null,
      offsets: resolvedOffsets(off),
    });
    return {
      checks,
      summary:
        "进循环: pos " + Math.round(posBefore) + " -> " + Math.round(pos) + "ms; A±1s 采样=" + nearA.length +
        "/" + afterEnter.length + "; phase=" + (pr.phase ?? "?") + "; 内容=" + (probe.result?.verdict ?? "?"),
      pendingDecision: pending,
    };
  },
};

// ---------------------------------------------------------------------------
// 元数据（给离线自检与 lead 合并时看"每条想抓什么 / 覆盖哪条断言"）
// ---------------------------------------------------------------------------

export interface PreludeScenarioMeta {
  id: number;
  /** 想抓什么（自检要求 >=10 字符且包含"抓"）。 */
  goal: string;
  expectedMs: number;
  usesUi: boolean;
  writesLoopData: boolean;
  deviceInteraction: "shared" | "exclusive";
  /** 覆盖 lead 定稿的 7 条断言。 */
  assertions: number[];
  /** 覆盖 issue 00024 第三批的 8 条需求。 */
  requirements: number[];
  /** 00028 未修时是否预期出现 FAIL（用来对照"判据有效性"）。 */
  expectedFailBeforeFix: boolean;
}

export const PRELUDE_META: Record<number, PreludeScenarioMeta> = {
  60: {
    id: 60,
    goal: "抓进循环的起始位置:旧实现从任何位置进循环都跳到 A(00028)，应改为从曲首前奏起播",
    expectedMs: 90000,
    usesUi: false,
    writesLoopData: false,
    deviceInteraction: "shared",
    assertions: [1, 7],
    requirements: [1, 8],
    expectedFailBeforeFix: true,
  },
  61: {
    id: 61,
    goal: "抓前奏段是否真的在播、跨 A 时是否保持播放头不折返不跳，以及 phase 是否 prelude->loop",
    expectedMs: 60000,
    usesUi: false,
    writesLoopData: false,
    deviceInteraction: "shared",
    assertions: [2, 7],
    requirements: [1, 8],
    expectedFailBeforeFix: true,
  },
  62: {
    id: 62,
    goal: "抓环回到 A 时听到的是不是 A(位置折返对但内容仍在 B 之后的 00027 型)",
    expectedMs: 45000,
    usesUi: false,
    writesLoopData: false,
    deviceInteraction: "shared",
    assertions: [3, 7],
    requirements: [3, 4, 8],
    expectedFailBeforeFix: false,
  },
  63: {
    id: 63,
    goal: "抓循环中 seek 到 A 之前是否可达:旧实现 clamp 到 A，位置被夹、内容不在请求位置",
    expectedMs: 50000,
    usesUi: false,
    writesLoopData: false,
    deviceInteraction: "shared",
    assertions: [4, 7],
    requirements: [2, 8],
    expectedFailBeforeFix: true,
  },
  64: {
    id: 64,
    goal: "抓循环中 seek 到 B 之后(尾奏)是否可达:旧实现 clamp 到 B-1ms，(B,duration] 不可达",
    expectedMs: 55000,
    usesUi: false,
    writesLoopData: false,
    deviceInteraction: "shared",
    assertions: [5, 7],
    requirements: [3, 8],
    expectedFailBeforeFix: true,
  },
  65: {
    id: 65,
    goal: "抓尾奏播到曲末后是否回到 0 而不是回 A、是否继续出声、内容是否为曲首",
    expectedMs: 50000,
    usesUi: false,
    writesLoopData: false,
    deviceInteraction: "shared",
    assertions: [5, 7],
    requirements: [4, 8],
    expectedFailBeforeFix: true,
  },
  66: {
    id: 66,
    goal: "抓 tail 中 seek 回环带后是否恢复 loop 语义并在 B 处重新折返(内容真值)",
    expectedMs: 60000,
    usesUi: false,
    writesLoopData: false,
    deviceInteraction: "shared",
    assertions: [6, 7],
    requirements: [3, 8],
    expectedFailBeforeFix: true,
  },
  67: {
    id: 67,
    goal: "抓出循环后位置是否回到媒体时间轴(不能停在环带 T)，以及 A 之前是否重新可达",
    expectedMs: 50000,
    usesUi: false,
    writesLoopData: false,
    deviceInteraction: "shared",
    assertions: [],
    requirements: [5, 8],
    expectedFailBeforeFix: false,
  },
  68: {
    id: 68,
    goal: "抓循环中把 A 改到播放头之后:新窗口生效后位置/相位/内容是否自洽(是否被夹到新 A)",
    expectedMs: 70000,
    usesUi: false,
    writesLoopData: true,
    deviceInteraction: "exclusive",
    assertions: [],
    requirements: [6, 8],
    expectedFailBeforeFix: true,
  },
  69: {
    id: 69,
    goal: "抓循环中把 B 改到播放头之前:位置是否落到 tail 且内容一致、回环带后是否仍在新 B 折返",
    expectedMs: 70000,
    usesUi: false,
    writesLoopData: true,
    deviceInteraction: "exclusive",
    assertions: [],
    requirements: [7, 8],
    expectedFailBeforeFix: true,
  },
  70: {
    id: 70,
    goal: "抓播放中进循环的瞬间是否跳到 A(00028 现场)：80ms 采样抓瞬时跳变",
    expectedMs: 40000,
    usesUi: false,
    writesLoopData: false,
    deviceInteraction: "shared",
    assertions: [1, 7],
    requirements: [1, 8],
    expectedFailBeforeFix: true,
  },
};

export const PRELUDE_SCENARIOS: Scenario[] = [s60, s61, s62, s63, s64, s65, s66, s67, s68, s69, s70];

/** 本组全部依赖主播放器协议 + tap/decode-range，不依赖 uiautomator / 结果页预览。 */
export const PRELUDE_SCENARIO_IDS: number[] = PRELUDE_SCENARIOS.map((s) => s.id);

/** 需要并入 runner UI_SCENARIOS 的 id（本组为空）。 */
export const PRELUDE_UI_SCENARIO_IDS: number[] = [];

export default PRELUDE_SCENARIOS;
