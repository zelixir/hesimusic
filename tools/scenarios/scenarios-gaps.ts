#!/usr/bin/env bun
/**
 * tools/scenarios/scenarios-gaps.ts —— 覆盖矩阵缺口场景（issue 00024 第四批，id 80~93）。
 *
 * 来源：docs/test-coverage-matrix.md §4「未覆盖清单（按风险排序）」。
 * 本文件只补**现有 50 条没有覆盖的格子**，刻意避开 A 批（00028 区间外位置，id 60~70）：
 *   80 S4×A2  主播放器循环中 seek 区间内 -> 内容真值（00027 同族）
 *   81 S4×A5/A6 主循环中改窗口（新 B）-> 不切歌/不退出/新 B 真的生效
 *   82 S8×A9  暂停中退出循环 -> 不自动播、静音、位置不倒退
 *   83 S8×A2  循环中暂停后 seek -> 保持暂停、落点对、继续后内容对（退出回媒体时间由 A 批 #67 覆盖）
 *   84 S11/S12×A1 预览页 暂停/继续/停止 -> 真静音/真恢复/回模式起点
 *   85 S11×A6 预览页「当前位置→B」-> B 生效、A 不被带坏、仍能播
 *   86 S11×A12 预览页 Ready 中「重新分析」-> 期间不得有看不见的预览声；完成后页面可恢复
 *   87 S4×A16 主循环中「取消最终结果」-> claim 必须退场（禁止假循环）
 *   88 S11×A15 结果页「确认并保存」UI 路径 -> 回列表 + 进入无缝 + 出声
 *   89 S15×A1 主播放页按钮暂停/继续 -> 循环不丢、位置仍在窗口
 *   90 S2/S8×A7 暂停中切歌 -> 保持暂停、不出声、旧窗口不串新歌
 *   91 S4×A18/A17 进入结果页暂停主播放、返回恢复；未点模式不得自动试听
 *   92 S4×A13 循环中旋转 -> claim/窗口/声音连续（不是从 A 重来）
 *   93 S4×A10 切引擎（SOURCE->M3'）-> 不得出现 claim=true 但没声/位置乱的半切换
 *
 * 判据纪律（与既有套件一致）：
 *   - 出声只认真实计数：主 /state.invariantProbe.writtenFrames、预览 /debug/preview.outputFrames；
 *   - 位置只认单一真值源：/state.playback.positionMs（已按 claim 折返）或预览 liveDisplayMs；
 *   - 内容真值：tap PCM 与 /debug/decode-range 原始文件互相关（NCC >= 0.90，content-probe）；
 *   - 改数据/改设备状态一律 finally 恢复，并把恢复结果写成 check。
 *
 * 注册方式（由 lead 合并，本文件不改 scenarios.ts / run-scenarios.ts）：
 *   import { GAP_SCENARIOS } from "./scenarios-gaps";
 *   export const SCENARIOS: Scenario[] = [..., ...GAP_SCENARIOS];
 *   run-scenarios.ts 的 UI_SCENARIOS 需加：80,84,85,86,87,88,89,91,92
 *
 * 写域：本文件。不改 app，不碰其它 scenarios-*.ts。
 */

import { check, measureMainFrames, measurePreviewFrames, pendingCheck, positionPhaseCheck, round, sleep, waitFramesSettled, waitFor } from "./harness";
import type { Check, SongInfo, State } from "./harness";
import { createProber, defaultWorkDir } from "./content-probe";
import type { ProbeResult, ProbeStack } from "./content-probe";
import type { Scenario, ScenarioCtx } from "./scenarios";

// ---------------------------------------------------------------------------
// SkipError：scenarios.ts -> (import GAP_SCENARIOS) -> 本文件 -> (import SkipError) -> scenarios.ts
// 是循环依赖；与 scenarios-content.ts 一样运行时惰性取，保证 instanceof 用的是同一个类。
// ---------------------------------------------------------------------------

let skipErrorCtor: (new (message: string) => Error) | null = null;

function skipCtor(): new (message: string) => Error {
  if (!skipErrorCtor) {
    const mod: any = require("./scenarios");
    skipErrorCtor = mod.SkipError as new (message: string) => Error;
  }
  return skipErrorCtor!;
}

function newSkip(message: string): Error {
  const Ctor = skipCtor();
  return new Ctor(message);
}

// ---------------------------------------------------------------------------
// 本地小工具（口径与 scenarios.ts 一致；不修改它，所以自带最小实现）
// ---------------------------------------------------------------------------

interface Win {
  songId: number;
  aMs: number;
  bMs: number;
}

function loopWin(state: State | null | undefined): Win | null {
  const w: any = state?.loop?.window;
  if (!w) return null;
  return {
    songId: Number(w.songId),
    aMs: Math.round(Number(w.loopStartUs) / 1000),
    bMs: Math.round(Number(w.loopEndUs) / 1000),
  };
}

/** claimActive 是 00027 起的单一真值；旧包按 engine=loop 兜底。 */
function claimActiveOf(state: State | null | undefined): boolean {
  if (!state) return false;
  const v = (state as any).loop?.claimActive;
  return v === true || (v === undefined && state.playback?.engine === "loop");
}

function audioCheck(name: string, m: any, minRatio: number, hint?: string): Check {
  if (!m.available) {
    return check(name, false, "输出帧探针不可用: " + (m.reason ?? "unknown"), hint);
  }
  const pct = round(m.ratio * 100, 1);
  return check(
    name,
    m.ratio >= minRatio,
    "Δ=" + m.deltaFrames + "f / " + m.spanMs + "ms = " + pct + "%(门槛 " + Math.round(minRatio * 100) + "%)",
    hint,
  );
}

async function playSong(ctx: ScenarioCtx, songId: number): Promise<State> {
  const r = await ctx.api.post("/player/play", { songId, repeatOne: false });
  if (r.status !== 200) throw new Error("/player/play -> " + r.status + " " + r.text.slice(0, 200));
  const w = await waitFor(
    async () => ctx.api.state(),
    (s) => s.playback?.songId === songId && s.playback?.playing === true && s.playback?.playbackState === "ready",
    8000,
    100,
  );
  if (!w.ok || !w.value) throw new Error("play 未在 8s 内 ready/playing (songId=" + songId + ")");
  return w.value;
}

async function ensurePaused(ctx: ScenarioCtx, timeoutMs = 6000): Promise<boolean> {
  const t0 = Date.now();
  while (Date.now() - t0 < timeoutMs) {
    const s = await ctx.api.state();
    if (s.playback?.playing === false && s.playback?.playWhenReady === false) return true;
    if (s.playback?.playing === false) {
      await sleep(250);
      continue;
    }
    await ctx.api.post("/player/play-pause").catch(() => null);
    await sleep(400);
  }
  const s = await ctx.api.state().catch(() => null);
  return s?.playback?.playing === false;
}

async function ensurePlaying(ctx: ScenarioCtx, timeoutMs = 8000): Promise<boolean> {
  const t0 = Date.now();
  while (Date.now() - t0 < timeoutMs) {
    const s = await ctx.api.state();
    if (s.playback?.playing === true) return true;
    if (s.playback?.playWhenReady === true) {
      await sleep(250);
      continue;
    }
    await ctx.api.post("/player/play-pause").catch(() => null);
    await sleep(400);
  }
  const s = await ctx.api.state().catch(() => null);
  return s?.playback?.playing === true;
}

async function enterLoop(ctx: ScenarioCtx, songId: number, play: boolean, restart: boolean) {
  return ctx.api.post("/loop/enter", { songId, play, restart });
}

async function waitClaim(
  ctx: ScenarioCtx,
  songId: number,
  timeoutMs = 10000,
): Promise<{ ok: boolean; state: State | null; elapsedMs: number }> {
  const w = await waitFor(
    async () => ctx.api.state(),
    (s) => claimActiveOf(s) && Number(s.playback?.songId) === songId,
    timeoutMs,
    120,
  );
  return { ok: w.ok, state: w.value, elapsedMs: w.elapsedMs };
}

async function previewSnap(ctx: ScenarioCtx): Promise<any | null> {
  const p = await ctx.api.preview();
  return p.available ? p.snapshot : null;
}

async function waitPreview(
  ctx: ScenarioCtx,
  pred: (snap: any) => boolean,
  timeoutMs: number,
  intervalMs = 120,
): Promise<{ ok: boolean; snap: any | null; elapsedMs: number }> {
  const w = await waitFor(async () => previewSnap(ctx), (s) => s != null && pred(s), timeoutMs, intervalMs);
  return { ok: w.ok, snap: w.value, elapsedMs: w.elapsedMs };
}

/** UI dump 里按 content-desc 点按（预览页/播放页的图标按钮只有 contentDescription）。 */
function xmlUnescape(s: string): string {
  return s
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&amp;/g, "&");
}

async function tapByDesc(ctx: ScenarioCtx, desc: string): Promise<boolean> {
  for (let attempt = 0; attempt < 3; attempt++) {
    if (attempt > 0) await sleep(400);
    const dump = await ctx.adb.uiDump();
    if (dump.error || !dump.raw) continue;
    const re = /<node\b([^>]*)>/g;
    let m: RegExpExecArray | null;
    while ((m = re.exec(dump.raw)) !== null) {
      const attrs = m[1];
      const cd = attrs.match(/\bcontent-desc="([^"]*)"/);
      if (!cd || xmlUnescape(cd[1]).trim() !== desc) continue;
      const b = attrs.match(/\bbounds="\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]"/);
      if (!b) continue;
      const x = (Number(b[1]) + Number(b[3])) / 2;
      const y = (Number(b[2]) + Number(b[4])) / 2;
      if (!(x > 0 && y > 0)) continue;
      await ctx.adb.tap(x, y);
      return true;
    }
  }
  return false;
}

async function hasText(ctx: ScenarioCtx, label: string): Promise<boolean> {
  const dump = await ctx.adb.uiDump();
  if (dump.error) return false;
  return dump.nodes.some((n) => n.text.trim() === label || n.text.includes(label));
}

/** 结果页内容是可滚动 Column：底部按钮可能不在首屏，按 bounds 判断是否在可视区内。 */
async function scrollPage(ctx: ScenarioCtx, direction: "down" | "up"): Promise<void> {
  const { width, height } = await ctx.adb.screenSize();
  const x = Math.round(width / 2);
  const y1 = direction === "down" ? Math.round(height * 0.72) : Math.round(height * 0.32);
  const y2 = direction === "down" ? Math.round(height * 0.32) : Math.round(height * 0.72);
  await ctx.adb.shell("input swipe " + x + " " + y1 + " " + x + " " + y2 + " 300");
  await sleep(450);
}

/** 点文本按钮（可视区内才点；不在首屏先向下滚）。返回 false = 找不到/点不到。 */
async function tapTextInView(ctx: ScenarioCtx, label: string, scrollTimes = 2): Promise<boolean> {
  for (let attempt = 0; attempt <= scrollTimes; attempt++) {
    if (attempt > 0) await scrollPage(ctx, "down");
    else await sleep(350);
    const dump = await ctx.adb.uiDump();
    if (dump.error) continue;
    const { width, height } = await ctx.adb.screenSize();
    const node =
      dump.nodes.find((n) => n.text.trim() === label) ?? dump.nodes.find((n) => n.text.includes(label));
    if (!node) continue;
    const b = node.bounds;
    if (b.top < 0 || b.bottom > height || b.left < 0 || b.right > width) continue;
    if (b.top < 60 || b.bottom > height - 20) continue;
    await ctx.adb.tap((b.left + b.right) / 2, (b.top + b.bottom) / 2);
    return true;
  }
  return false;
}

/**
 * 点文本按钮，并在**真正 input tap 之前**立即采样一次预览位置。
 *
 * 为什么不用 ctx.uiTap：uiTap 内部要 uiautomator dump + 等 bounds 稳定，实测从采样到
 * 真正 tap 可能隔 6~7 秒；预览在播放时播放头一直前进，而「当前位置→B」用的是**点按那一刻**
 * 的位置。用 dump 之前的位置对账会看到 B 比采样值大好几秒（场景 #85 的假失败，
 * 2026-11-21 证据：采样 40287 → tap 在 6.4s 后 → B=46062，差值 5775ms 与时间差吻合）。
 *
 * 本 helper 在 tap 前最后一个动作才采样位置，把漂移压到 adb tap 的往返(~几十 ms)以内。
 */
async function tapTextAtLivePosition(
  ctx: ScenarioCtx,
  label: string,
  scrollTimes = 2,
): Promise<{ ok: boolean; positionMs: number | null }> {
  for (let attempt = 0; attempt <= scrollTimes; attempt++) {
    if (attempt > 0) await scrollPage(ctx, "down");
    else await sleep(350);
    const dump = await ctx.adb.uiDump();
    if (dump.error) continue;
    const { width, height } = await ctx.adb.screenSize();
    const node =
      dump.nodes.find((n) => n.text.trim() === label) ?? dump.nodes.find((n) => n.text.includes(label));
    if (!node) continue;
    const b = node.bounds;
    if (b.top < 0 || b.bottom > height || b.left < 0 || b.right > width) continue;
    if (b.top < 60 || b.bottom > height - 20) continue;
    // tap 前最后一个动作：立即采样（中间不再插入 dump/scroll/sleep）。
    const snap = await previewSnap(ctx);
    const positionMs = snap ? Number(snap.liveDisplayMs ?? snap.displayMs ?? 0) : null;
    await ctx.adb.tap((b.left + b.right) / 2, (b.top + b.bottom) / 2);
    return { ok: true, positionMs };
  }
  return { ok: false, positionMs: null };
}

/** 预览输出帧"停稳"（连续两次采样不变），用于暂停静音判据。 */
async function previewFramesSettled(
  ctx: ScenarioCtx,
  timeoutMs = 3000,
): Promise<{ ok: boolean; frames: number; elapsedMs: number }> {
  const t0 = Date.now();
  let lastAt = -1;
  let lastFrames = -1;
  let stableSince = 0;
  while (Date.now() - t0 < timeoutMs) {
    const snap = await previewSnap(ctx);
    if (!snap) break;
    const at = Number(snap.atMs);
    const frames = Number(snap.outputFrames ?? 0);
    if (at !== lastAt) {
      if (lastFrames >= 0 && frames === lastFrames) {
        if (stableSince === 0) stableSince = Date.now();
        if (Date.now() - stableSince >= 450) return { ok: true, frames, elapsedMs: Date.now() - t0 };
      } else {
        stableSince = 0;
      }
      lastAt = at;
      lastFrames = frames;
    }
    await sleep(100);
  }
  return { ok: false, frames: lastFrames, elapsedMs: Date.now() - t0 };
}

async function songInfo(ctx: ScenarioCtx, songId: number): Promise<any> {
  return ctx.api.song(songId);
}

function msToSamples(ms: number, sampleRate: number): number {
  return Math.round((ms * sampleRate) / 1000);
}

async function restoreWindow(
  ctx: ScenarioCtx,
  songId: number,
  aSamples: number,
  bSamples: number,
): Promise<Check> {
  const r = await ctx.api.post("/confirm", { songId, aSamples, bSamples });
  return check(
    "finally: 恢复原循环窗口",
    r.status === 200,
    "POST /confirm -> " + r.status + " " + r.text.slice(0, 160),
  );
}

// ---------------------------------------------------------------------------
// 内容真值（只覆盖主播放器；预览页的内容判据已有 #20~#26）
// ---------------------------------------------------------------------------

interface ContentLoop {
  song: SongInfo;
  songPath: string;
  sampleRate: number;
  aMs: number;
  bMs: number;
  durationMs: number;
  L: number;
}

function decodableOf(path: string): boolean {
  return /\.(mp3|wav|m4a|aac|flac)$/i.test(path);
}

async function pickContentLoop(ctx: ScenarioCtx, opts?: { nearTail?: boolean }): Promise<ContentLoop> {
  const all = await ctx.api.songsAll();
  const cands = all.filter(
    (s) =>
      s.hasLoop &&
      s.loopConfirmed &&
      s.loopStartMs != null &&
      s.loopEndMs != null &&
      Number(s.loopStartMs) >= 3000 &&
      Number(s.loopEndMs) - Number(s.loopStartMs) >= 8000 &&
      Number(s.durationMs) > Number(s.loopEndMs) + 3000 &&
      decodableOf(s.path),
  );
  cands.sort((a, b) => {
    const la = Number(a.loopEndMs) - Number(a.loopStartMs);
    const lb = Number(b.loopEndMs) - Number(b.loopStartMs);
    if (opts?.nearTail) return Number(a.durationMs) - Number(a.loopEndMs) - (Number(b.durationMs) - Number(b.loopEndMs));
    return la - lb || a.id - b.id;
  });
  for (const s of cands.slice(0, 8)) {
    if (!(await ctx.adb.fileExists(s.path))) continue;
    const info = await songInfo(ctx, s.id);
    const aMs = Math.round(Number(info.loopStartMs));
    const bMs = Math.round(Number(info.loopEndMs));
    const durationMs = Number(info.durationMs);
    const sampleRate = Number(info.sampleRate ?? ctx.sampleRateFor(s.id)) || 44100;
    if (!(bMs > aMs) || !info.path) continue;
    return { song: s, songPath: String(info.path), sampleRate, aMs, bMs, durationMs, L: bMs - aMs };
  }
  throw newSkip(
    "曲库里没有可用于内容真值的已确认循环歌（需 A>=3s、环长>=8s、B 距尾 >3s、mp3/wav/m4a/flac，且文件在磁盘上）",
  );
}

function stackFor(ctx: ScenarioCtx): ProbeStack {
  return createProber(ctx.cfg, ctx.api, { workDir: defaultWorkDir(ctx.cfg), log: ctx.log });
}

function contentCheckOf(label: string, r: ProbeResult): Check {
  const loc = r.locatedContentMs == null ? "n/a" : Math.round(r.locatedContentMs) + "ms";
  const drift = r.driftMs == null ? "n/a" : Math.round(r.driftMs) + "ms";
  const wide =
    r.wide && r.wide.length > 0
      ? "；实际最像 " + r.wide[0].region + " @" + Math.round(r.wide[0].positionMs) + "ms(peak " + r.wide[0].peak.toFixed(3) + ")"
      : "";
  const detail =
    "peak=" + r.peak.toFixed(3) + " 期望=" + Math.round(r.expectedContentMs) + "ms 定位=" + loc +
    " 偏差=" + drift + " 参考片=" + r.chunks + " 窗口=" + r.capture.windowMs + "ms" + wide;
  if (r.verdict === "unavailable") {
    return pendingCheck(label, detail + " — " + r.reason, "tap-content-probe", "tap 没写出帧或没有 decode-range；确认 debug 包 + SOURCE 路线");
  }
  if (r.verdict === "match") return check(label, true, detail);
  const hint =
    r.verdict === "mismatch"
      ? "内容层不符(00027 型)：写出的 PCM 在期望位置找不到；看 wide 是否落在 B 之后/环带之外"
      : "可疑：先排除基线对齐、音量差、降采样";
  return check(label, false, detail + " — " + r.reason, hint);
}

interface MainProbeResult {
  content: Check;
  result: ProbeResult;
  displayRefMs: number;
}

async function probeMainAt(
  ctx: ScenarioCtx,
  stack: ProbeStack,
  li: ContentLoop,
  opts: { label: string; offsetMs: number; mode?: "band" | "file"; wide?: boolean; driftTolMs?: number },
): Promise<MainProbeResult> {
  const capture = await stack.prober.capture({
    target: "main",
    samplesMs: 300,
    dwellMs: 900,
    workDir: defaultWorkDir(ctx.cfg),
    label: opts.label,
  });
  const displayRef = capture.displayRef?.displayMs;
  if (displayRef == null) throw newSkip("抓取期间没有 display 采样(" + capture.displaySource + ")，无法定位期望内容");
  const expected = Number(displayRef) + opts.offsetMs;
  const result = await stack.truth.probeCapture(capture, {
    songPath: li.songPath,
    aMs: li.aMs,
    bMs: li.bMs,
    expectedContentMs: expected,
    mode: opts.mode ?? "band",
    wide: opts.wide === true,
    driftTolMs: opts.driftTolMs ?? 600,
    caption: opts.label,
  });
  ctx.evidence.step("content-probe", {
    label: opts.label,
    expectedContentMs: expected,
    displayRefMs: displayRef,
    mode: opts.mode ?? "band",
    verdict: result.verdict,
    peak: result.peak,
    locatedContentMs: result.locatedContentMs,
    driftMs: result.driftMs,
    wide: result.wide,
    capture: result.capture,
    wav: capture.wavPath,
    seams: capture.seams.length,
  });
  ctx.log(
    "  " + opts.label + ": " + result.verdict + " peak=" + result.peak.toFixed(3) +
      " display=" + Math.round(Number(displayRef)) + "ms 期望=" + Math.round(expected) + "ms" +
      " 偏差=" + (result.driftMs == null ? "n/a" : Math.round(result.driftMs) + "ms"),
  );
  return { content: contentCheckOf(opts.label + " 内容真值", result), result, displayRefMs: Number(displayRef) };
}

/** 等主播放展示位置落到目标附近（循环态口径已折返）。 */
async function waitDisplayNear(ctx: ScenarioCtx, targetMs: number, tolMs: number, timeoutMs = 5000) {
  return waitFor(
    async () => ctx.api.state(),
    (s) => Math.abs(Number(s.playback?.positionMs) - targetMs) <= tolMs,
    timeoutMs,
    80,
  );
}

// ---------------------------------------------------------------------------
// 场景 80：主播放器循环中 seek 区间内 -> 内容真值
// ---------------------------------------------------------------------------

const g80: Scenario = {
  id: 80,
  key: "main-seek-inside-loop-content",
  name: "主播放器循环中 seek 区间内 x3(内容真值)",
  tag: "seek",
  exclusive: false,
  timeoutMs: 200000,
  async run(ctx) {
    const checks: Check[] = [];
    const li = await pickContentLoop(ctx);
    ctx.log("环带: #" + li.song.id + " A=" + li.aMs + " B=" + li.bMs + " L=" + round(li.L / 1000, 1) + "s sr=" + li.sampleRate);
    ctx.evidence.step("setup", { songId: li.song.id, aMs: li.aMs, bMs: li.bMs, L: li.L, songPath: li.songPath });
    await ctx.resetForMain();
    await playSong(ctx, li.song.id);
    await ctx.api.post("/player/seek", { positionMs: li.aMs + 3000 });
    await waitDisplayNear(ctx, li.aMs + 3000, 800);
    const enter = await enterLoop(ctx, li.song.id, true, false);
    if (enter.status !== 200) throw new Error("/loop/enter -> " + enter.status + " " + enter.text.slice(0, 200));
    const claim = await waitClaim(ctx, li.song.id, 12000);
    checks.push(check("进入循环(claimActive)", claim.ok, "engine=" + claim.state?.playback?.engine + " 耗时 " + claim.elapsedMs + "ms"));
    if (!claim.ok) return { checks, summary: "未进入 claimActive,后续内容判据无意义" };
    if ((await ctx.api.state()).invariantProbe?.routeSource !== true) {
      throw newSkip("主播放器不在 SOURCE 路线(invariantProbe.routeSource!=true)，输出帧/内容探针不可用");
    }

    const stack = stackFor(ctx);
    const mid = Math.round(li.aMs + li.L / 2);
    await ctx.api.post("/player/seek", { positionMs: mid });
    await waitDisplayNear(ctx, mid, 900);
    await sleep(700);
    const base = await probeMainAt(ctx, stack, li, { label: "校准(中段)", offsetMs: 0, driftTolMs: 5000 });
    checks.push(
      check(
        "判据自证:稳态基线峰值 >= 0.95",
        base.result.peak >= 0.95,
        "peak=" + base.result.peak.toFixed(3) + " display=" + Math.round(base.displayRefMs) + "ms",
        base.result.peak < 0.95 ? "基线都对不上 -> 内容判据不可信（检查 SOURCE/tap 是否被别的引擎写）" : undefined,
      ),
    );
    const offset = base.result.driftMs ?? 0;
    checks.push(
      check(
        "写入超前偏移量级合理",
        Math.abs(offset) <= 800,
        "offset=" + Math.round(offset) + "ms",
        Math.abs(offset) > 800 ? "偏移过大:先确认窗口锚点与 tap 写入对齐" : undefined,
      ),
    );

    const targets = Array.from(
      new Set([Math.round(li.aMs + 1000), mid, Math.round(li.bMs - 2500)]),
    ).sort((a, b) => a - b);
    for (const target of targets) {
      const seek = await ctx.api.post("/player/seek", { positionMs: target });
      const landed = await waitDisplayNear(ctx, target, 900, 5000);
      const st = landed.value ?? (await ctx.api.state());
      const display = Number(st.playback?.positionMs);
      checks.push(
        check(
          "seek 到 " + target + "ms 落点对",
          seek.status === 200 && Math.abs(display - target) <= 900,
          "pos=" + display + " target=" + target + " status=" + seek.status,
          Math.abs(display - target) > 900 ? "循环态 seek 落点偏移:检查 LoopSessionPlayer.seekTo 的 rawSeek 映射/等最终值覆盖" : undefined,
        ),
      );
      // 给 media3 flush + AudioTrack 重开留出稳定期，避免把 seek 瞬间的静音算进出声/内容窗口
      await sleep(350);
      const audible = await measureMainFrames(ctx.api, 1200, li.sampleRate);
      checks.push(audioCheck("seek 到 " + target + "ms 后出声", audible, 0.8));
      const content = await probeMainAt(ctx, stack, li, { label: "seek@" + target, offsetMs: offset, wide: true });
      checks.push(content.content);
      ctx.evidence.step("seek-point", { target, display, ratio: round(audible.ratio * 100, 1), peak: content.result.peak });
    }
    return {
      checks,
      summary: targets.length + " 点 seek; offset=" + Math.round(offset) + "ms",
    };
  },
};

// ---------------------------------------------------------------------------
// 场景 81：循环播放中改窗口（新 B）-> 不切歌/不退出/新 B 生效
// ---------------------------------------------------------------------------

const g81: Scenario = {
  id: 81,
  key: "loop-reconfirm-change-b",
  name: "循环中改 B(重新确认)后新 B 生效且不切歌",
  tag: "循环",
  exclusive: false,
  timeoutMs: 160000,
  async run(ctx) {
    const checks: Check[] = [];
    const song = ctx.songs.primary;
    const info = await songInfo(ctx, song.id);
    const aMs = Math.round(Number(info.loopStartMs));
    const bMs = Math.round(Number(info.loopEndMs));
    const sr = Number(info.sampleRate ?? ctx.sampleRateFor(song.id)) || 44100;
    const origA = Number(info.loopStartSamples);
    const origB = Number(info.loopEndSamples);
    if (!(origB > origA) || origA <= 0) throw newSkip("读不到原始 samples，无法在 finally 恢复窗口");
    const newB = bMs - 6000;
    if (newB - aMs < 1500) throw newSkip("循环窗口太短(" + aMs + ".." + bMs + ")，无法构造新 B");
    ctx.evidence.step("setup", { songId: song.id, aMs, bMs, newB, sampleRate: sr });
    await ctx.resetForMain();
    await playSong(ctx, song.id);
    await ctx.api.post("/player/seek", { positionMs: aMs + 3000 });
    await waitDisplayNear(ctx, aMs + 3000, 900);
    await enterLoop(ctx, song.id, true, false);
    const claim0 = await waitClaim(ctx, song.id, 12000);
    checks.push(check("改窗口前已进入循环", claim0.ok, "engine=" + claim0.state?.playback?.engine));
    if (!claim0.ok) return { checks, summary: "未进入循环,跳过改窗口验证" };
    try {
      const confirm = await ctx.api.post("/confirm", {
        songId: song.id,
        aSamples: msToSamples(aMs, sr),
        bSamples: msToSamples(newB, sr),
      });
      checks.push(check("重新确认返回 200", confirm.status === 200, "status=" + confirm.status + " " + confirm.text.slice(0, 160)));
      const changed = await waitFor(
        async () => ctx.api.state(),
        (s) => {
          const w = loopWin(s);
          return w != null && w.songId === song.id && Math.abs(w.bMs - newB) <= 1200;
        },
        15000,
        120,
      );
      const st1 = changed.value ?? (await ctx.api.state());
      const w1 = loopWin(st1);
      ctx.evidence.step("after-confirm", {
        window: w1,
        engine: st1.playback?.engine,
        claimActive: (st1 as any).loop?.claimActive,
        songId: st1.playback?.songId,
        playing: st1.playback?.playing,
        positionMs: st1.playback?.positionMs,
      });
      checks.push(
        check(
          "新 B 生效(±1s)",
          w1 != null && Math.abs(w1.bMs - newB) <= 1000,
          "window=" + JSON.stringify(w1) + " 期望 B=" + newB,
          w1 != null && Math.abs(w1.bMs - newB) > 1000 ? "改 B 没落到窗口:检查 confirmAndSave 的 samples->ms 口径" : undefined,
        ),
      );
      checks.push(check("A 未被带坏(±300ms)", w1 != null && Math.abs(w1.aMs - aMs) <= 300, "A=" + (w1?.aMs ?? "?") + " 原 A=" + aMs));
      checks.push(check("没有切到别的歌", Number(st1.playback?.songId) === song.id, "songId=" + st1.playback?.songId + " 期望 " + song.id));
      const inLoopAgain = await waitClaim(ctx, song.id, 12000);
      checks.push(check("改窗口后仍在无缝(claimActive)", inLoopAgain.ok, "engine=" + (inLoopAgain.state?.playback?.engine ?? "?")));
      await ensurePlaying(ctx, 6000);
      await sleep(500);
      const audible = await measureMainFrames(ctx.api, 1500, sr);
      checks.push(audioCheck("改窗口后仍出声", audible, 0.8, "改 B 后输出帧没有恢复 -> 可能 flush 后没重新起播"));

      // 验证"新 B 真的生效"：把播放头放到新 B 前 2s，短窗口内应回绕到 A（而不是旧 B）
      const seekTo = Math.max(aMs + 500, newB - 2000);
      await ctx.api.post("/player/seek", { positionMs: seekTo });
      await waitDisplayNear(ctx, seekTo, 1200);
      let wrapped = false;
      let maxBefore = -1;
      let landAfter = -1;
      const t0 = Date.now();
      while (Date.now() - t0 < 12000) {
        const s = await ctx.api.state();
        const pos = Number(s.playback?.positionMs);
        if (pos > maxBefore) maxBefore = pos;
        if (maxBefore > newB - 1200 && pos < aMs + 2500) {
          wrapped = true;
          landAfter = pos;
          break;
        }
        await sleep(120);
      }
      checks.push(
        check(
          "在新 B 处回绕(而不是旧 B)",
          wrapped,
          "maxBefore=" + maxBefore + " 回绕落点=" + landAfter + " 新B=" + newB + " 旧B=" + bMs,
          !wrapped ? "12s 内没观察到回绕:新 B 可能没装进音频源(半更新)" : undefined,
        ),
      );
      checks.push(
        check(
          "回绕落点≈A(不是 0)",
          wrapped && landAfter >= aMs - 1000 && landAfter <= aMs + 2500,
          "落点=" + landAfter + " A=" + aMs,
          wrapped && landAfter < aMs - 1000 ? "回绕回到了 A 之前:窗口起点口径错(00028 同族)" : undefined,
        ),
      );
      const stEnd = await ctx.api.state();
      checks.push(
        check(
          "位置没有越过新 B 太多",
          Number(stEnd.playback?.positionMs) <= newB + 1500,
          "pos=" + stEnd.playback?.positionMs + " 新B=" + newB,
        ),
      );
    } finally {
      const restored = await restoreWindow(ctx, song.id, origA, origB);
      checks.push(restored);
      await ctx.api.post("/loop/exit").catch(() => null);
      await sleep(400);
    }
    return { checks, summary: "A " + aMs + " B " + bMs + "->" + newB };
  },
};

// ---------------------------------------------------------------------------
// 场景 82：暂停中退出循环 -> 不自动播/静音/位置不倒退
// ---------------------------------------------------------------------------

const g82: Scenario = {
  id: 82,
  key: "exit-loop-paused",
  name: "暂停中退出循环:不自动播、静音、位置不倒退",
  tag: "循环",
  exclusive: false,
  timeoutMs: 120000,
  async run(ctx) {
    const checks: Check[] = [];
    const song = ctx.songs.primary;
    const info = await songInfo(ctx, song.id);
    const aMs = Math.round(Number(info.loopStartMs));
    const sr = Number(info.sampleRate ?? ctx.sampleRateFor(song.id)) || 44100;
    await ctx.resetForMain();
    await playSong(ctx, song.id);
    await ctx.api.post("/player/seek", { positionMs: aMs + 3000 });
    await waitDisplayNear(ctx, aMs + 3000, 900);
    await enterLoop(ctx, song.id, true, false);
    const claim = await waitClaim(ctx, song.id, 12000);
    checks.push(check("已进入循环", claim.ok, "engine=" + claim.state?.playback?.engine));
    if (!claim.ok) return { checks, summary: "未进入循环,场景跳过" };

    const paused = await ensurePaused(ctx);
    checks.push(check("先暂停", paused, "playing=false"));
    await sleep(300);
    const before = await ctx.api.state();
    const beforePos = Number(before.playback?.positionMs);

    const exit = await ctx.api.post("/loop/exit");
    checks.push(check("退出接口返回 200", exit.status === 200, "status=" + exit.status));
    const gone = await waitFor(
      async () => ctx.api.state(),
      (s) => loopWin(s) === null && !claimActiveOf(s),
      10000,
      120,
    );
    const st = gone.value ?? (await ctx.api.state());
    ctx.evidence.step("after-exit", {
      window: loopWin(st),
      claimActive: (st as any).loop?.claimActive,
      engine: st.playback?.engine,
      playing: st.playback?.playing,
      playWhenReady: st.playback?.playWhenReady,
      positionMs: st.playback?.positionMs,
    });
    checks.push(check("窗口/claim 已退场", gone.ok, "window=" + JSON.stringify(loopWin(st)) + " claimActive=" + String((st as any).loop?.claimActive)));
    checks.push(check("退出后仍是暂停(不自动播)", st.playback?.playing === false && st.playback?.playWhenReady === false, "playing=" + st.playback?.playing + " pwr=" + st.playback?.playWhenReady));
    await sleep(500);
    const silent = await measureMainFrames(ctx.api, 1200, sr);
    checks.push(check("暂停中退出后无输出", silent.deltaFrames === 0, "Δ=" + silent.deltaFrames + "f/" + silent.spanMs + "ms"));
    checks.push(check("进度条没有倒退", Number(st.playback?.positionMs) >= beforePos - 1500, "before=" + beforePos + " after=" + st.playback?.positionMs));

    const resumed = await ensurePlaying(ctx);
    checks.push(check("手动继续生效", resumed, "playing=true"));
    await sleep(500);
    const audible = await measureMainFrames(ctx.api, 1600, sr);
    checks.push(audioCheck("继续后有输出", audible, 0.85));
    return { checks, summary: "paused-exit Δ=" + silent.deltaFrames + "f; resume=" + round(audible.ratio * 100, 0) + "%" };
  },
};

// ---------------------------------------------------------------------------
// 场景 83：循环中已暂停时 seek（S8×A2）
// 说明：A 批 #67 已覆盖"退出后位置回媒体时间轴"，本格不再重复；这里补的是
//      "暂停中拖进度条"这条用户动作：必须保持暂停、落点对、继续后听到的内容=显示的。
// ---------------------------------------------------------------------------

const g83: Scenario = {
  id: 83,
  key: "paused-seek-inside-loop-content",
  name: "循环中暂停后 seek:保持暂停、落点对、继续后内容对",
  tag: "seek",
  exclusive: false,
  timeoutMs: 200000,
  async run(ctx) {
    const checks: Check[] = [];
    const li = await pickContentLoop(ctx);
    ctx.evidence.step("setup", { songId: li.song.id, aMs: li.aMs, bMs: li.bMs, L: li.L });
    await ctx.resetForMain();
    await playSong(ctx, li.song.id);
    await ctx.api.post("/player/seek", { positionMs: li.aMs + 3000 });
    await waitDisplayNear(ctx, li.aMs + 3000, 900);
    await enterLoop(ctx, li.song.id, true, false);
    const claim = await waitClaim(ctx, li.song.id, 12000);
    checks.push(check("已进入循环", claim.ok, "engine=" + claim.state?.playback?.engine));
    if (!claim.ok) return { checks, summary: "未进入循环,场景跳过" };
    if ((await ctx.api.state()).invariantProbe?.routeSource !== true) {
      throw newSkip("主播放器不在 SOURCE 路线，内容探针不可用");
    }
    const stack = stackFor(ctx);
    const w0 = loopWin(claim.state);

    // 播放中先校准（band 口径的写入超前偏移；暂停态写入停止，校准必须先在播放态做）
    const mid = Math.round(li.aMs + li.L / 2);
    await ctx.api.post("/player/seek", { positionMs: mid });
    await waitDisplayNear(ctx, mid, 900);
    await sleep(700);
    const base = await probeMainAt(ctx, stack, li, { label: "校准(环带中段)", offsetMs: 0, driftTolMs: 5000 });
    checks.push(check("判据自证:基线峰值 >= 0.95", base.result.peak >= 0.95, "peak=" + base.result.peak.toFixed(3)));
    const offset = base.result.driftMs ?? 0;

    const paused = await ensurePaused(ctx);
    checks.push(check("先暂停", paused, "playing=false"));
    await waitFramesSettled(ctx.api, 3000);
    const target = mid + 5000 <= li.bMs - 2000 ? mid + 5000 : Math.max(li.aMs + 1000, mid - 5000);
    try {
      const seek = await ctx.api.post("/player/seek", { positionMs: target });
      checks.push(check("暂停中 seek 返回 200", seek.status === 200, "status=" + seek.status + " target=" + target));
      const landed = await waitDisplayNear(ctx, target, 1000, 6000);
      const st1 = landed.value ?? (await ctx.api.state());
      const display = Number(st1.playback?.positionMs);
      ctx.evidence.step("paused-seek", {
        target,
        display,
        playing: st1.playback?.playing,
        playWhenReady: st1.playback?.playWhenReady,
        window: loopWin(st1),
      });
      checks.push(
        check(
          "暂停中 seek 落点对(±1s)",
          landed.ok && Math.abs(display - target) <= 1000,
          "pos=" + display + " target=" + target,
          !landed.ok || Math.abs(display - target) > 1000
            ? "暂停中 seek 没落到目标:检查 LoopSessionPlayer.seekTo 在 paused 下的投影/等最终值"
            : undefined,
        ),
      );
      checks.push(
        check(
          "seek 没有打破暂停(不自动播)",
          st1.playback?.playing === false && st1.playback?.playWhenReady === false,
          "playing=" + st1.playback?.playing + " pwr=" + st1.playback?.playWhenReady,
          st1.playback?.playing === true ? "暂停中 seek 把播放拉起来了" : undefined,
        ),
      );
      await sleep(300);
      const silent = await measureMainFrames(ctx.api, 1200, li.sampleRate);
      checks.push(check("暂停中 seek 后无输出", silent.deltaFrames === 0, "Δ=" + silent.deltaFrames + "f/" + silent.spanMs + "ms"));
      const w1 = loopWin(st1);
      checks.push(
        check(
          "窗口未被 seek 改动",
          w1 != null && w0 != null && w1.songId === w0.songId && Math.abs(w1.aMs - w0.aMs) <= 300 && Math.abs(w1.bMs - w0.bMs) <= 300,
          "window " + JSON.stringify(w0) + " -> " + JSON.stringify(w1),
        ),
      );

      const resumed = await ensurePlaying(ctx);
      checks.push(check("继续播放生效", resumed, "playing=true"));
      await sleep(600);
      const st2 = await ctx.api.state();
      const pos2 = Number(st2.playback?.positionMs);
      checks.push(
        check(
          "继续后从落点前进(不是从 A 重来)",
          pos2 >= target - 1000 && pos2 <= target + 3000,
          "pos=" + pos2 + " target=" + target,
          pos2 < target - 1000 ? "继续后位置跳回:暂停中 seek 的落点没被播放采用" : undefined,
        ),
      );
      const audible = await measureMainFrames(ctx.api, 1500, li.sampleRate);
      checks.push(audioCheck("继续后出声", audible, 0.8, "继续后输出帧没有恢复"));
      const content = await probeMainAt(ctx, stack, li, { label: "暂停seek后@" + target, offsetMs: offset, wide: true });
      checks.push(content.content);
    } finally {
      await ctx.api.post("/loop/exit").catch(() => null);
      await sleep(300);
    }
    return { checks, summary: "paused-seek target 落点见判据; offset=" + Math.round(offset) + "ms" };
  },
};

// ---------------------------------------------------------------------------
// 场景 84：预览页播放/暂停/停止（按钮只有 contentDescription）
// ---------------------------------------------------------------------------

const g84: Scenario = {
  id: 84,
  key: "preview-playback-controls",
  name: "预览页 暂停/继续/停止 三态",
  tag: "循环",
  exclusive: true,
  timeoutMs: 160000,
  async run(ctx) {
    const checks: Check[] = [];
    const song = ctx.songs.short;
    const sr = ctx.sampleRateFor(song.id);
    await ctx.resetForMain();
    await ctx.ensureLoopPage(song.id);
    const labels = ctx.uiLabels();
    if (labels.length > 0 && !labels.includes("仅循环段")) {
      throw newSkip("结果页没有「仅循环段」按钮（现有: " + labels.slice(0, 14).join(" | ") + "）");
    }
    await ctx.uiTap("仅循环段");
    const playing = await waitPreview(ctx, (s) => s.mode === "LOOP_ONLY" && s.isPlaying === true, 12000);
    if (!playing.ok || !playing.snap) throw newSkip("结果页「仅循环段」12s 内未起播");
    const snap0 = playing.snap;
    const a0 = Number(snap0.aMs);
    const b0 = Number(snap0.bMs);
    ctx.evidence.step("start", { aMs: a0, bMs: b0, engine: snap0.engine, mode: snap0.mode });
    const base = await measurePreviewFrames(ctx.api, 1500, sr);
    checks.push(audioCheck("起播基线有输出", base, 0.8));

    if (!(await tapByDesc(ctx, "暂停"))) {
      throw newSkip("当前包的预览播放按钮没有 content-desc=暂停（Compose 语义未导出）");
    }
    const paused = await waitPreview(ctx, (s) => s.isPlaying === false, 6000);
    checks.push(check("点暂停后 isPlaying=false", paused.ok, "6s 内未暂停"));
    if (!paused.ok) return { checks, summary: "暂停没生效" };
    await sleep(250);
    const settled = await previewFramesSettled(ctx, 3000);
    checks.push(check("暂停尾帧落定", settled.ok, "frames=" + settled.frames + " 耗时=" + settled.elapsedMs + "ms"));
    const silent = await measurePreviewFrames(ctx.api, 1200, sr);
    checks.push(check("暂停期间预览无输出", silent.deltaFrames === 0, "Δ=" + silent.deltaFrames + "f/" + silent.spanMs + "ms"));
    const snapPaused = await previewSnap(ctx);
    const pausedAt = Number(snapPaused?.displayMs ?? 0);

    if (!(await tapByDesc(ctx, "播放"))) {
      throw newSkip("当前包的预览播放按钮没有 content-desc=播放");
    }
    const resumed = await waitPreview(ctx, (s) => s.isPlaying === true, 8000);
    checks.push(check("点播放后恢复", resumed.ok, "8s 内未恢复播放"));
    await sleep(500);
    const audible = await measurePreviewFrames(ctx.api, 1500, sr);
    checks.push(audioCheck("继续后预览有声", audible, 0.8));
    const snapResumed = await previewSnap(ctx);
    checks.push(
      check(
        "继续后位置前进(不从模式起点重来)",
        Number(snapResumed?.displayMs ?? 0) >= pausedAt - 1000,
        "pausedAt=" + pausedAt + " now=" + snapResumed?.displayMs,
      ),
    );

    if (!(await tapByDesc(ctx, "停止并回到起点"))) {
      checks.push(pendingCheck("停止按钮", "当前包没有 content-desc=停止并回到起点", "preview-stop-button"));
    } else {
      const stopped = await waitPreview(ctx, (s) => s.isPlaying === false, 6000);
      checks.push(check("点停止后不再播放", stopped.ok, "6s 内未停止"));
      // 停止 = pause + seek 回起点;音频侧 in-flight 帧(硬件尾帧,文档口径 50~100ms)
      // 会在点击后几十毫秒才落定。必须先等"帧数停稳(最多 1.5s)"再量 Δ,否则会把
      // 正常尾帧误判成"stop 没停住 sink"(2026-11-21 全量跑:点完立即量 Δ=4079f≈92.5ms@44.1k)。
      const stopSettled = await previewFramesSettled(ctx, 1000);
      checks.push(
        check(
          "停止尾帧落定(≤1s)",
          stopSettled.ok,
          "frames=" + stopSettled.frames + " 耗时=" + stopSettled.elapsedMs + "ms",
          stopSettled.ok ? undefined : "停止后 1s 内帧数仍在变化:疑似 stop 没有真正停住 sink",
        ),
      );
      const snapStop = await previewSnap(ctx);
      const posStop = Number(snapStop?.displayMs ?? -1);
      const expectStart = Math.max(0, a0 - 300);
      checks.push(
        check(
          "停止后回到模式起点(A 附近)",
          posStop >= expectStart - 1500 && posStop <= a0 + 2500,
          "display=" + posStop + " 期望≈" + expectStart,
          posStop < expectStart - 1500 ? "停止后跑到了 A 之前" : posStop > a0 + 2500 ? "停止没有回到起点" : undefined,
        ),
      );
      const stillSilent = await measurePreviewFrames(ctx.api, 1000, sr);
      checks.push(check("停止后无输出(尾帧落定后)", stillSilent.deltaFrames === 0, "Δ=" + stillSilent.deltaFrames + "f/" + stillSilent.spanMs + "ms"));
    }

    const snapEnd = await previewSnap(ctx);
    checks.push(
      check(
        "整个过程 A/B 未被改动",
        snapEnd != null && Math.abs(Number(snapEnd.aMs) - a0) <= 200 && Math.abs(Number(snapEnd.bMs) - b0) <= 200,
        "A/B " + a0 + "/" + b0 + " -> " + snapEnd?.aMs + "/" + snapEnd?.bMs,
      ),
    );
    return { checks, summary: "pause Δ=" + silent.deltaFrames + "f; resume=" + round(audible.ratio * 100, 0) + "%" };
  },
};

// ---------------------------------------------------------------------------
// 场景 85：预览页「当前位置→B」
// ---------------------------------------------------------------------------

const g85: Scenario = {
  id: 85,
  key: "preview-change-b",
  name: "预览页改 B(当前位置→B)后仍可播且 A 不变",
  tag: "循环",
  exclusive: true,
  timeoutMs: 130000,
  async run(ctx) {
    const checks: Check[] = [];
    const song = ctx.songs.primary;
    const sr = ctx.sampleRateFor(song.id);
    await ctx.resetForMain();
    await ctx.ensureLoopPage(song.id);
    const labels = ctx.uiLabels();
    if (labels.length > 0 && !labels.includes("当前位置→B")) {
      throw newSkip("结果页没有「当前位置→B」按钮（现有: " + labels.slice(0, 14).join(" | ") + "）");
    }
    await ctx.uiTap("仅循环段");
    const playing = await waitPreview(ctx, (s) => s.mode === "LOOP_ONLY" && s.isPlaying === true, 12000);
    if (!playing.ok || !playing.snap) throw newSkip("结果页「仅循环段」12s 内未起播");
    const snap0 = playing.snap;
    const a0 = Number(snap0.aMs);
    const b0 = Number(snap0.bMs);
    const oldA = Number((await songInfo(ctx, song.id)).loopStartSamples);
    const oldB = Number((await songInfo(ctx, song.id)).loopEndSamples);
    const target = Math.max(a0 + 3000, b0 - 10000);
    if (target >= b0 - 1000) throw newSkip("循环窗口太短(" + a0 + ".." + b0 + ")，无法改 B");
    try {
      await ctx.previewSeek(target);
      const landed = await waitPreview(ctx, (s) => Math.abs(Number(s.liveDisplayMs ?? s.displayMs) - target) <= 1200, 5000);
      checks.push(check("改 B 前 seek 到目标", landed.ok, "display=" + landed.snap?.liveDisplayMs + " target=" + target));
      // 关键：位置必须在**真正 tap 前**采样。uiTap 内部有数秒的 dump/稳定等待，
      // 拿 dump 前的采样对账会把"播放头正常前进"误判成"B 没改到当前位置"(场景 #85)。
      const tapSetB = await tapTextAtLivePosition(ctx, "当前位置→B");
      if (!tapSetB.ok) throw newSkip("「当前位置→B」按钮在可视区内点不到(页面已滚动到底仍不可见)");
      const displayAtTap = tapSetB.positionMs ??
        Number(landed.snap?.liveDisplayMs ?? landed.snap?.displayMs ?? target);
      ctx.evidence.step("tap-set-b", { positionAtTap: displayAtTap, sampledBeforeTap: tapSetB.positionMs !== null });
      const changed = await waitPreview(
        ctx,
        (s) => Math.abs(Number(s.bMs) - displayAtTap) <= 1800 && Number(s.bMs) < b0 - 1000,
        6000,
      );
      const snap1 = changed.snap ?? (await previewSnap(ctx));
      ctx.evidence.step("after-set-b", { aMs: snap1?.aMs, bMs: snap1?.bMs, displayMs: snap1?.displayMs, isPlaying: snap1?.isPlaying, displayAtTap });
      checks.push(
        check(
          "改 B 立即生效",
          changed.ok,
          "B=" + snap1?.bMs + " (点按瞬间位置≈" + Math.round(displayAtTap) + ", 原 B=" + b0 + ")",
          !changed.ok
            ? "「当前位置→B」没有改到窗口:B 应等于 tap 前最后采样的位置;若仍差好几秒,检查采样点是否又在慢操作之前"
            : undefined,
        ),
      );
      checks.push(check("改 B 后 A 不变(±300ms)", snap1 != null && Math.abs(Number(snap1.aMs) - a0) <= 300, "A=" + snap1?.aMs + " 原 A=" + a0));
      const playingAgain = await waitPreview(ctx, (s) => s.isPlaying === true, 6000);
      checks.push(check("改 B 后仍在播放", playingAgain.ok, "isPlaying=" + playingAgain.snap?.isPlaying));
      const newB = Number(snap1?.bMs ?? b0);
      const display = Number(snap1?.displayMs ?? 0);
      checks.push(
        check(
          "位置落在新窗口附近",
          display >= a0 - 1500 && display <= newB + 2500,
          "display=" + display + " window=" + snap1?.aMs + ".." + newB,
          display > newB + 2500 ? "改 B 后播放头跑出新区间(可能仍在按旧窗口走)" : undefined,
        ),
      );
      const audible = await measurePreviewFrames(ctx.api, 1500, sr);
      checks.push(audioCheck("改 B 后仍出声", audible, 0.8, "改 B 后预览输出帧没有恢复"));
      const st = await ctx.api.state();
      checks.push(check("改 B 过程主播放未崩", st.playback?.connected === true, "connected=" + st.playback?.connected));
    } finally {
      // 行未保存,页面仍在内存预览:不需要落库恢复;但确保主播放不被留在暂停(离开页时 Kinv)。
      await ctx.api.post("/loop/exit").catch(() => null);
      ctx.evidence.note("旧行 samples: A=" + oldA + " B=" + oldB + "（本场景未落库）");
    }
    return { checks, summary: "B " + b0 + "->" + (await previewSnap(ctx))?.bMs };
  },
};

// ---------------------------------------------------------------------------
// 场景 86：预览页 Ready 中「重新分析」
// ---------------------------------------------------------------------------

const g86: Scenario = {
  id: 86,
  key: "preview-reanalyze-ready",
  name: "预览页 Ready 中重新分析:期间不得有看不见的预览声",
  tag: "分析",
  exclusive: true,
  timeoutMs: 320000,
  async run(ctx) {
    const checks: Check[] = [];
    const song = ctx.songs.primary;
    const sr = ctx.sampleRateFor(song.id);
    const info = await songInfo(ctx, song.id);
    const origA = Number(info.loopStartSamples);
    const origB = Number(info.loopEndSamples);
    const origConfirmed = info.loopConfirmed === true;
    const pid0 = await ctx.adb.pidof(ctx.cfg.pkg);
    await ctx.resetForMain();
    await ctx.ensureLoopPage(song.id);
    const labels = ctx.uiLabels();
    if (labels.length > 0 && !labels.includes("重新分析")) {
      throw newSkip("结果页没有「重新分析」按钮（现有: " + labels.slice(0, 14).join(" | ") + "）");
    }
    await ctx.uiTap("仅循环段");
    const playing = await waitPreview(ctx, (s) => s.mode === "LOOP_ONLY" && s.isPlaying === true, 12000);
    if (!playing.ok || !playing.snap) throw newSkip("结果页「仅循环段」12s 内未起播（已存结果页应能直接试听）");
    ctx.evidence.step("before-reanalyze", { window: [playing.snap.aMs, playing.snap.bMs], engine: playing.snap.engine });

    const reTapped = await tapTextInView(ctx, "重新分析", 3);
    if (!reTapped) throw newSkip("「重新分析」按钮在可视区内点不到");
    checks.push(check("已点「重新分析」", reTapped, "via tapTextInView"));
    const entered = await waitFor(
      async () => {
        const st = await ctx.api.state();
        const p = await ctx.api.preview();
        return { phase: String(st.analysis?.phase ?? "?"), previewAvailable: p.available === true, isPlaying: p.snapshot?.isPlaying === true };
      },
      (v) => v.phase === "decoding" || v.phase === "analyzing" || v.previewAvailable === false,
      12000,
      150,
    );
    checks.push(
      check(
        "点重新分析后进入分析中",
        entered.ok,
        "phase=" + entered.value?.phase + " previewAvailable=" + String(entered.value?.previewAvailable),
        !entered.ok ? "点「重新分析」后页面没进入分析态:检查 ManualReanalyze/RestartAnalysis 接线" : undefined,
      ),
    );

    // 分析期间：旧预览不得在"看不见的页面"继续出声（量真值，不看 isPlaying 标志）
    const during = await measurePreviewFrames(ctx.api, 1500, sr);
    checks.push(
      check(
        "分析期间预览不再写帧(Δ 很小)",
        during.deltaFrames <= Math.round(sr * 0.15),
        "Δ=" + during.deltaFrames + "f/" + during.spanMs + "ms (允许尾帧 " + Math.round(sr * 0.15) + "f)",
        during.deltaFrames > Math.round(sr * 0.15)
          ? "页面已进入分析态但旧预览仍在出声:Ready->Analyzing 没有释放/暂停预览"
          : undefined,
      ),
    );

    const done = await waitFor(
      async () => (await ctx.api.state()).analysis?.phase,
      (p) => p === "done" || p === "failed",
      200000,
      500,
    );
    const phase = String(done.value ?? "?");
    checks.push(
      check(
        "分析结束(phase=done)",
        phase === "done",
        "phase=" + phase + " 耗时 " + done.elapsedMs + "ms" + (phase === "failed" ? " msg=" + String((await ctx.api.state()).analysis?.message ?? "") : ""),
        phase === "failed" ? "分析失败(数据/格式问题?)：见 /state.analysis.message" : undefined,
      ),
    );

    const readyAgain = await waitPreview(ctx, (s) => Number(s.bMs) > Number(s.aMs), 25000);
    checks.push(check("分析完成后页面回到可试听(Ready)", readyAgain.ok, "25s 内 /debug/preview 未恢复", !readyAgain.ok ? "分析完成后页面没有恢复预览:检查 AnalysisDone -> Ready 的 effect" : undefined));
    if (readyAgain.snap) {
      const a = Number(readyAgain.snap.aMs);
      const b = Number(readyAgain.snap.bMs);
      checks.push(check("恢复后窗口合法(A<B)", b > a && a >= 0, "A=" + a + " B=" + b));
    }
    const pid1 = await ctx.adb.pidof(ctx.cfg.pkg);
    checks.push(check("进程未重启", pid0 != null && pid1 === pid0, "pid " + pid0 + " -> " + pid1));
    const stEnd = await ctx.api.state();
    checks.push(check("主播放连接未断", stEnd.playback?.connected === true, "connected=" + stEnd.playback?.connected));

    const restoreChecks: Check[] = [];
    if (origConfirmed && origA > 0 && origB > origA) {
      restoreChecks.push(await restoreWindow(ctx, song.id, origA, origB));
    }
    await ctx.api.post("/loop/exit").catch(() => null);
    return {
      checks: checks.concat(restoreChecks),
      summary: "phase=" + phase + " 期间Δ=" + during.deltaFrames + "f; readyAgain=" + readyAgain.ok,
    };
  },
};

// ---------------------------------------------------------------------------
// 场景 87：主循环中「取消最终结果」-> claim 必须退场
// ---------------------------------------------------------------------------

const g87: Scenario = {
  id: 87,
  key: "preview-demote-while-looping",
  name: "循环中取消最终结果:claim 退场、不留假循环",
  tag: "循环",
  exclusive: true,
  timeoutMs: 190000,
  async run(ctx) {
    const checks: Check[] = [];
    const song = ctx.songs.primary;
    const sr = ctx.sampleRateFor(song.id);
    const info = await songInfo(ctx, song.id);
    if (info.loopConfirmed !== true) throw newSkip("该歌不是已确认循环，无法测「取消最终结果」");
    const origA = Number(info.loopStartSamples);
    const origB = Number(info.loopEndSamples);
    if (!(origB > origA) || origA <= 0) throw newSkip("读不到原始 samples，无法在 finally 恢复窗口");
    const aMs = Math.round(Number(info.loopStartMs));
    const pid0 = await ctx.adb.pidof(ctx.cfg.pkg);
    await ctx.resetForMain();
    await playSong(ctx, song.id);
    await ctx.api.post("/player/seek", { positionMs: aMs + 3000 });
    await waitDisplayNear(ctx, aMs + 3000, 900);
    await enterLoop(ctx, song.id, true, false);
    const claim = await waitClaim(ctx, song.id, 12000);
    checks.push(check("已进入循环", claim.ok, "engine=" + claim.state?.playback?.engine));
    if (!claim.ok) return { checks, summary: "未进入循环,场景跳过" };
    const w0 = loopWin(claim.state);
    try {
      await ctx.ensureLoopPage(song.id);
      if (!(await hasText(ctx, "取消最终结果"))) {
        throw newSkip("结果页没有「取消最终结果」按钮（该歌应有 confirmed 行）");
      }
      const preview0 = await waitPreview(ctx, () => true, 12000);
      checks.push(check("结果页预览已就绪(取消前)", preview0.ok, "preview=" + String(preview0.ok)));
      const demoteTapped = await tapTextInView(ctx, "取消最终结果", 3);
      if (!demoteTapped) throw newSkip("「取消最终结果」按钮在可视区内点不到（页面滚动后仍不可见）");
      checks.push(check("已点「取消最终结果」", demoteTapped, "via tapTextInView"));
      const demoted = await waitFor(
        async () => (await songInfo(ctx, song.id)).loopConfirmed === false,
        (v) => v === true,
        15000,
        250,
      );
      checks.push(check("行已转为未确认(confirmed=false)", demoted.ok, "15s 内未生效", !demoted.ok ? "「取消最终结果」没有落库" : undefined));
      const gone = await waitFor(
        async () => ctx.api.state(),
        (s) => !claimActiveOf(s) && loopWin(s) === null,
        12000,
        150,
      );
      const st = gone.value ?? (await ctx.api.state());
      ctx.evidence.step("after-demote", {
        claimActive: (st as any).loop?.claimActive,
        claimReason: (st as any).loop?.claimReason,
        window: loopWin(st),
        engine: st.playback?.engine,
        playing: st.playback?.playing,
        positionMs: st.playback?.positionMs,
      });
      checks.push(
        check(
          "claim/窗口已退场(不留假循环)",
          gone.ok,
          "claimActive=" + String((st as any).loop?.claimActive) + " window=" + JSON.stringify(loopWin(st)) + " reason=" + String((st as any).loop?.claimReason ?? "null"),
          !gone.ok ? "取消确认后仍声称在循环 -> 显示/声音会脱钩(00027 型)" : undefined,
        ),
      );
      checks.push(check("结果页未崩(预览仍可读)", (await ctx.api.preview()).available === true, "/debug/preview 丢失"));
      // 返回：主播放恢复为普通播放（底部按钮可能不在首屏；失败退回系统返回键）
      if (!(await tapTextInView(ctx, "返回", 3))) {
        await ctx.adb.shell("input keyevent KEYCODE_BACK");
      }
      const resumed = await waitFor(
        async () => ctx.api.state(),
        (s) => s.playback?.playing === true,
        15000,
        150,
      );
      checks.push(check("返回后主播放恢复", resumed.ok, "15s 内未恢复 playing"));
      if (resumed.ok) {
        await sleep(500);
        const audible = await measureMainFrames(ctx.api, 1600, sr);
        checks.push(audioCheck("恢复后普通播放有声", audible, 0.8));
        const st2 = resumed.value!;
        checks.push(check("恢复后不在循环", !claimActiveOf(st2), "engine=" + st2.playback?.engine + " claimActive=" + String((st2 as any).loop?.claimActive)));
      }
      const pid1 = await ctx.adb.pidof(ctx.cfg.pkg);
      checks.push(check("进程未重启", pid0 != null && pid1 === pid0, "pid " + pid0 + " -> " + pid1));
    } finally {
      const restored = await restoreWindow(ctx, song.id, origA, origB);
      checks.push(restored);
      await ctx.api.post("/loop/exit").catch(() => null);
      await sleep(400);
      ctx.evidence.step("cleanup", { restoredConfirmed: (await songInfo(ctx, song.id)).loopConfirmed, w0 });
    }
    return { checks, summary: "demote -> claim 退场; 恢复 confirmed=" + (await songInfo(ctx, song.id)).loopConfirmed };
  },
};

// ---------------------------------------------------------------------------
// 场景 88：结果页「确认并保存」UI 路径
// ---------------------------------------------------------------------------

const g88: Scenario = {
  id: 88,
  key: "preview-confirm-save-ui",
  name: "确认并保存(UI):回列表 + 进入无缝 + 出声",
  tag: "循环",
  exclusive: true,
  timeoutMs: 190000,
  async run(ctx) {
    const checks: Check[] = [];
    const song = ctx.songs.primary;
    const sr = ctx.sampleRateFor(song.id);
    const info = await songInfo(ctx, song.id);
    if (info.loopConfirmed !== true) throw newSkip("该歌不是已确认循环，UI「确认并保存」需要已有候选结果");
    const aMs = Math.round(Number(info.loopStartMs));
    const bMs = Math.round(Number(info.loopEndMs));
    const origA = Number(info.loopStartSamples);
    const origB = Number(info.loopEndSamples);
    if (!(origB > origA) || origA <= 0) throw newSkip("读不到原始 samples，无法在 finally 恢复窗口");
    const pid0 = await ctx.adb.pidof(ctx.cfg.pkg);
    await ctx.resetForMain();
    await playSong(ctx, song.id);
    try {
      await ctx.ensureLoopPage(song.id);
      if (!(await hasText(ctx, "确认并保存"))) {
        throw newSkip("结果页没有「确认并保存」按钮");
      }
      const saveTapped = await tapTextInView(ctx, "确认并保存", 3);
      if (!saveTapped) throw newSkip("「确认并保存」按钮在可视区内点不到");
      checks.push(check("已点「确认并保存」", saveTapped, "via tapTextInView"));
      const claimed = await waitFor(
        async () => ctx.api.state(),
        (s) => claimActiveOf(s) && Number(s.playback?.songId) === song.id,
        25000,
        150,
      );
      const st = claimed.value ?? (await ctx.api.state());
      ctx.evidence.step("after-save", {
        claimActive: (st as any).loop?.claimActive,
        window: loopWin(st),
        engine: st.playback?.engine,
        playing: st.playback?.playing,
        positionMs: st.playback?.positionMs,
        previewAvailable: (await ctx.api.preview()).available,
      });
      checks.push(check("保存后进入无缝循环(claimActive)", claimed.ok, "engine=" + st.playback?.engine + " 耗时 " + claimed.elapsedMs + "ms"));
      const w = loopWin(st);
      checks.push(
        check(
          "窗口=该歌的 A/B(±1s)",
          w != null && w.songId === song.id && Math.abs(w.aMs - aMs) <= 1000 && Math.abs(w.bMs - bMs) <= 1000,
          "window=" + JSON.stringify(w) + " 期望 " + aMs + ".." + bMs,
        ),
      );
      checks.push(check("没有切到别的歌", Number(st.playback?.songId) === song.id, "songId=" + st.playback?.songId));
      checks.push(check("保存后开始播放", st.playback?.playing === true, "playing=" + st.playback?.playing));
      // 00028：启动无缝循环从原点（曲目开头）开始，不允许沿用进入前的播放位置。
      // 采样发生在 claim 可见后 ~1.1s，正常已播到 1s 左右；<=3000ms 即视为 prelude 起点。
      checks.push(
        check(
          "进入后从曲目开头起播(prelude)",
          Number(st.playback?.positionMs) <= 3000,
          "positionMs=" + st.playback?.positionMs + "（期望 <=3000）",
        ),
      );
      const released = await waitFor(
        async () => (await ctx.api.preview()).available === false,
        (v) => v === true,
        15000,
        200,
      );
      checks.push(
        check(
          "结果页已释放(预览探针 detach)",
          released.ok,
          "15s 内 /debug/preview 仍 available（页面可能没 pop 或预览没 release）",
        ),
      );
      await sleep(600);
      const audible = await measureMainFrames(ctx.api, 1800, sr);
      checks.push(audioCheck("保存后循环有声", audible, 0.8, "保存后没有输出:arm/playKeepPosition 或 SOURCE 切换失败"));
      const pid1 = await ctx.adb.pidof(ctx.cfg.pkg);
      checks.push(check("进程未重启", pid0 != null && pid1 === pid0, "pid " + pid0 + " -> " + pid1));
    } finally {
      let restore = await restoreWindow(ctx, song.id, origA, origB);
      checks.push(restore);
      await ctx.api.post("/loop/exit").catch(() => null);
      await sleep(400);
    }
    return { checks, summary: "save -> claim=" + (await ctx.api.state()).loop?.claimActive + "; audible" };
  },
};

// ---------------------------------------------------------------------------
// 场景 89：主播放页按钮暂停/继续（循环不丢）
// ---------------------------------------------------------------------------

const g89: Scenario = {
  id: 89,
  key: "main-player-page-pause",
  name: "主播放页按钮暂停/继续:循环不丢、位置仍在窗口",
  tag: "页面",
  exclusive: true,
  timeoutMs: 130000,
  async run(ctx) {
    const checks: Check[] = [];
    const song = ctx.songs.primary;
    const sr = ctx.sampleRateFor(song.id);
    const info = await songInfo(ctx, song.id);
    const aMs = Math.round(Number(info.loopStartMs));
    const bMs = Math.round(Number(info.loopEndMs));
    await ctx.resetForMain();
    await playSong(ctx, song.id);
    await ctx.api.post("/player/seek", { positionMs: aMs + 3000 });
    await waitDisplayNear(ctx, aMs + 3000, 900);
    await enterLoop(ctx, song.id, true, false);
    const claim = await waitClaim(ctx, song.id, 12000);
    checks.push(check("已进入循环", claim.ok, "engine=" + claim.state?.playback?.engine));
    if (!claim.ok) return { checks, summary: "未进入循环,场景跳过" };
    try {
      const nav = await ctx.api.post("/navigate", { route: "player" });
      checks.push(check("打开主播放页", nav.status === 200, "/navigate player -> " + nav.status));
      const onPage = await waitFor(async () => (await ctx.api.state()).screen, (s) => s === "player", 5000, 100);
      checks.push(check("页面=player", onPage.ok, "screen=" + String(onPage.value)));
      await sleep(700);
      if (!(await tapByDesc(ctx, "播放/暂停"))) {
        throw newSkip("主播放页的播放按钮没有 content-desc=播放/暂停");
      }
      const paused = await waitFor(async () => (await ctx.api.state()).playback?.playing, (p) => p === false, 6000, 100);
      checks.push(check("播放页点暂停生效", paused.ok, "playing=" + String(paused.value)));
      await sleep(300);
      const silent = await measureMainFrames(ctx.api, 1200, sr);
      checks.push(check("暂停期间无输出", silent.deltaFrames === 0, "Δ=" + silent.deltaFrames + "f/" + silent.spanMs + "ms"));
      const stPaused = await ctx.api.state();
      checks.push(positionPhaseCheck("暂停后位置仍在窗口/前奏尾奏", stPaused, aMs, bMs));
      if (!(await tapByDesc(ctx, "播放/暂停"))) throw newSkip("播放页播放按钮消失(content-desc 读不到)");
      const resumed = await waitFor(async () => (await ctx.api.state()).playback?.playing, (p) => p === true, 8000, 100);
      checks.push(check("播放页点继续生效", resumed.ok, "playing=" + String(resumed.value)));
      await sleep(500);
      const audible = await measureMainFrames(ctx.api, 1600, sr);
      checks.push(audioCheck("继续后有输出", audible, 0.85));
      const stEnd = await ctx.api.state();
      checks.push(check("继续后仍是循环(claimActive)", claimActiveOf(stEnd), "engine=" + stEnd.playback?.engine));
      const w = loopWin(stEnd);
      checks.push(
        check(
          "窗口未被页面操作改动",
          w != null && w.songId === song.id && Math.abs(w.aMs - aMs) <= 300 && Math.abs(w.bMs - bMs) <= 300,
          "window=" + JSON.stringify(w),
        ),
      );
    } finally {
      await ctx.api.post("/loop/exit").catch(() => null);
      await ctx.api.post("/navigate", { route: "about" }).catch(() => null);
    }
    return { checks, summary: "page pause Δ=0; resume audible" };
  },
};

// ---------------------------------------------------------------------------
// 场景 90：暂停中切歌（循环/普通都适用；重点"保持暂停"）
// ---------------------------------------------------------------------------

const g90: Scenario = {
  id: 90,
  key: "next-song-while-paused",
  name: "暂停中切歌:保持暂停、不出声、旧窗口不串",
  tag: "播放",
  exclusive: false,
  timeoutMs: 130000,
  async run(ctx) {
    const checks: Check[] = [];
    const song = ctx.songs.primary;
    const sr = ctx.sampleRateFor(song.id);
    const info = await songInfo(ctx, song.id);
    const aMs = Math.round(Number(info.loopStartMs));
    await ctx.resetForMain();
    await playSong(ctx, song.id);
    await ctx.api.post("/player/seek", { positionMs: aMs + 3000 });
    await waitDisplayNear(ctx, aMs + 3000, 900);
    await enterLoop(ctx, song.id, true, false);
    const claim = await waitClaim(ctx, song.id, 12000);
    checks.push(check("已进入循环", claim.ok, "engine=" + claim.state?.playback?.engine));
    if (!claim.ok) return { checks, summary: "未进入循环,场景跳过" };
    const paused = await ensurePaused(ctx);
    checks.push(check("先暂停", paused, "playing=false"));
    await sleep(300);
    const before = await ctx.api.state();
    const beforeId = Number(before.playback?.songId);
    try {
      const next = await ctx.api.post("/player/next");
      const nextPath = String(next.json?.path ?? "");
      checks.push(check("切歌接口返回 200", next.status === 200, "status=" + next.status + " path=" + nextPath));
      const moved = await waitFor(
        async () => ctx.api.state(),
        (s) => Number(s.playback?.songId) !== beforeId || Number(s.playback?.positionMs) <= 3000,
        10000,
        120,
      );
      const st = moved.value ?? (await ctx.api.state());
      const newId = Number(st.playback?.songId);
      ctx.evidence.step("after-next", {
        beforeId,
        newId,
        playing: st.playback?.playing,
        playWhenReady: st.playback?.playWhenReady,
        window: loopWin(st),
        engine: st.playback?.engine,
        positionMs: st.playback?.positionMs,
      });
      checks.push(check("切歌生效(换歌或回开头)", moved.ok, "songId " + beforeId + " -> " + newId + " pos=" + st.playback?.positionMs));
      const stillPaused = st.playback?.playing === false && st.playback?.playWhenReady === false;
      if (nextPath === "WRAP_TO_START") {
        // 单曲队列/最后一项时 /player/next 的 debug 回退显式 play()（与真实 UI 同路径的兜底）；
        // 这条分支不判"保持暂停"，只记录并改判为待裁决，避免把回退语义当成产品 bug。
        checks.push(
          pendingCheck(
            "单曲队列回退路径的暂停语义",
            "path=WRAP_TO_START 时 debug 端点会 seekToDefaultPosition(0)+play()，playing=" + String(st.playback?.playing),
            "debug-next-wrap-plays",
            "需要产品裁决：暂停中按下一首、队列只有一首时是否应自动开播",
          ),
        );
      } else {
        checks.push(
          check(
            "切歌后仍暂停(不自动出声)",
            stillPaused,
            "playing=" + st.playback?.playing + " pwr=" + st.playback?.playWhenReady,
            st.playback?.playing === true ? "暂停中切歌自动开播了:用户会以为按错了" : undefined,
          ),
        );
      }
      await sleep(500);
      const silent = await measureMainFrames(ctx.api, 1200, ctx.sampleRateFor(newId || song.id));
      if (nextPath === "WRAP_TO_START") {
        checks.push(pendingCheck("单曲队列回退路径无输出", "Δ=" + silent.deltaFrames + "f（回退路径会主动 play）", "debug-next-wrap-plays"));
      } else {
        checks.push(check("切歌后无输出", silent.deltaFrames === 0, "Δ=" + silent.deltaFrames + "f/" + silent.spanMs + "ms"));
      }
      const w = loopWin(st);
      checks.push(
        check(
          "旧循环窗口没有套到新歌",
          w === null || w.songId !== newId,
          "window=" + JSON.stringify(w) + " newId=" + newId,
        ),
      );
      const resumed = await ensurePlaying(ctx);
      checks.push(check("手动继续生效", resumed, "playing=true"));
      await sleep(500);
      const audible = await measureMainFrames(ctx.api, 1600, ctx.sampleRateFor(newId || song.id));
      checks.push(audioCheck("继续后有输出", audible, 0.85));
    } finally {
      await ctx.api.post("/loop/exit").catch(() => null);
    }
    return { checks, summary: "paused-next Δ=" + 0 + "f(见判据)" };
  },
};

// ---------------------------------------------------------------------------
// 场景 91：进入结果页暂停主播放、返回恢复；未点模式不得自动试听
// ---------------------------------------------------------------------------

const g91: Scenario = {
  id: 91,
  key: "loop-page-entry-pauses-main",
  name: "进结果页暂停主播放/返回恢复;未点模式不自动试听",
  tag: "页面",
  exclusive: true,
  timeoutMs: 130000,
  async run(ctx) {
    const checks: Check[] = [];
    const song = ctx.songs.primary;
    const sr = ctx.sampleRateFor(song.id);
    await ctx.resetForMain();
    const playing = await playSong(ctx, song.id);
    const base = await measureMainFrames(ctx.api, 1500, sr);
    checks.push(audioCheck("进页前主播放有输出", base, 0.85));
    await ctx.ensureLoopPage(song.id);
    const paused = await waitFor(
      async () => (await ctx.api.state()).playback,
      (p) => p?.playing === false && p?.playWhenReady === false,
      10000,
      120,
    );
    checks.push(
      check(
        "进结果页后主播放暂停",
        paused.ok,
        "10s 内 playing/playWhenReady 未变 false",
        !paused.ok ? "进结果页没有暂停主播放 -> 会与预览双声(00020 同族)" : undefined,
      ),
    );
    await sleep(300);
    const silent = await measureMainFrames(ctx.api, 1200, sr);
    checks.push(check("主播放进页后无输出", silent.deltaFrames === 0, "Δ=" + silent.deltaFrames + "f/" + silent.spanMs + "ms"));
    const p = await ctx.api.preview();
    if (p.available && p.snapshot) {
      checks.push(
        check(
          "未点模式时预览不自动出声",
          Number(p.snapshot.outputFrames ?? 0) >= 0 && p.snapshot.isPlaying !== true,
          "isPlaying=" + String(p.snapshot.isPlaying) + " frames=" + String(p.snapshot.outputFrames),
          p.snapshot.isPlaying === true ? "进结果页就自动试听了(用户没点任何模式)" : undefined,
        ),
      );
    } else {
      checks.push(pendingCheck("预览未自动起播", "/debug/preview 尚未可用: " + String(p.reason ?? ""), "preview-probe"));
    }
    if (!(await tapTextInView(ctx, "返回", 3))) {
      await ctx.adb.shell("input keyevent KEYCODE_BACK");
    }
    const resumed = await waitFor(
      async () => (await ctx.api.state()).playback,
      (p2) => p2?.playing === true,
      15000,
      150,
    );
    checks.push(check("返回后主播放恢复", resumed.ok, "15s 内未恢复 playing", !resumed.ok ? "真正离开结果页后没有 resume 主播放" : undefined));
    if (resumed.ok) {
      await sleep(500);
      const audible = await measureMainFrames(ctx.api, 1600, sr);
      checks.push(audioCheck("返回后主播放有声", audible, 0.8));
    }
    const st = await ctx.api.state();
    checks.push(check("返回后仍是普通播放(未武装循环)", !claimActiveOf(st), "engine=" + st.playback?.engine));
    return { checks, summary: "entry pause Δ=" + silent.deltaFrames + "f; resume=" + resumed.ok };
  },
};

// ---------------------------------------------------------------------------
// 场景 92：循环中旋转（Activity 重建）-> claim/窗口/声音连续
// ---------------------------------------------------------------------------

async function currentRotation(ctx: ScenarioCtx): Promise<number | null> {
  for (const cmd of ["dumpsys input", "dumpsys window", "dumpsys display"]) {
    const out = await ctx.adb.shell(cmd, 30000);
    const m = out.match(/SurfaceOrientation:\s*(\d)/) ?? out.match(/mRotation=(\d)/) ?? out.match(/rotation(?:=|\s+)(\d)\b/i);
    if (m) {
      const n = Number(m[1]);
      if (n >= 0 && n <= 3) return n;
    }
  }
  return null;
}

async function setRotation(ctx: ScenarioCtx, rotation: number): Promise<{ ok: boolean; via: "wm" | "cmd-window" | "none"; detail: string }> {
  const wm = await ctx.adb.run(["shell", "wm user-rotation lock " + rotation], 15000);
  const wmOut = (wm.out + "\n" + wm.err).trim();
  if (wm.code === 0 && !/unknown command|Unknown command|Exception|Error/i.test(wmOut)) {
    return { ok: true, via: "wm", detail: wmOut.slice(0, 160) };
  }
  const cw = await ctx.adb.run(["shell", "cmd window user-rotation lock " + rotation], 15000);
  const cwOut = (cw.out + "\n" + cw.err).trim();
  if (cw.code === 0 && !/Unknown command|Exception|Error/i.test(cwOut)) {
    return { ok: true, via: "cmd-window", detail: cwOut.slice(0, 160) };
  }
  return { ok: false, via: "none", detail: (wmOut + " | " + cwOut).slice(0, 300) };
}

async function freeRotation(ctx: ScenarioCtx, via: "wm" | "cmd-window" | "none"): Promise<{ ok: boolean; detail: string }> {
  const cmd = via === "cmd-window" ? "cmd window user-rotation free" : "wm user-rotation free";
  const r = await ctx.adb.run(["shell", cmd], 15000);
  const out = (r.out + "\n" + r.err).trim();
  return { ok: r.code === 0 && !/Exception|Error|unknown command/i.test(out), detail: cmd + " -> " + out.slice(0, 160) };
}

const g92: Scenario = {
  id: 92,
  key: "rotate-while-looping",
  name: "循环中旋转:claim/窗口/声音连续",
  tag: "环境",
  exclusive: true,
  timeoutMs: 150000,
  async run(ctx) {
    const checks: Check[] = [];
    const song = ctx.songs.primary;
    const sr = ctx.sampleRateFor(song.id);
    const info = await songInfo(ctx, song.id);
    const aMs = Math.round(Number(info.loopStartMs));
    const bMs = Math.round(Number(info.loopEndMs));
    await ctx.resetForMain();
    await playSong(ctx, song.id);
    await ctx.api.post("/player/seek", { positionMs: aMs + 3000 });
    await waitDisplayNear(ctx, aMs + 3000, 900);
    await enterLoop(ctx, song.id, true, false);
    const claim = await waitClaim(ctx, song.id, 12000);
    checks.push(check("已进入循环", claim.ok, "engine=" + claim.state?.playback?.engine));
    if (!claim.ok) return { checks, summary: "未进入循环,场景跳过" };
    const w0 = loopWin(claim.state);
    const pos0 = Number(claim.state?.playback?.positionMs);
    const pid0 = await ctx.adb.pidof(ctx.cfg.pkg);
    const origAccel = (await ctx.adb.shell("settings get system accelerometer_rotation", 15000)).trim();
    const origUser = (await ctx.adb.shell("settings get system user_rotation", 15000)).trim();
    const rot0 = await currentRotation(ctx);
    ctx.evidence.step("rotation-before", { rot0, origAccel, origUser, window: w0, position: pos0 });
    if (rot0 == null) throw newSkip("读不到当前屏幕朝向(dumpsys input/window/display 均无 mRotation)");
    const start = rot0 === 1 || rot0 === 3 ? 1 : 0;
    const target = start === 0 ? 1 : 0;
    const probe = await setRotation(ctx, target);
    if (!probe.ok) {
      await freeRotation(ctx, probe.via);
      throw newSkip("该 ROM 不支持 wm/cmd window user-rotation: " + probe.detail);
    }
    try {
      const rotNow = await currentRotation(ctx);
      checks.push(check("屏幕已旋转", rotNow === target, "rotation " + rot0 + " -> " + String(rotNow) + " (目标 " + target + ")"));
      const st = await waitFor(
        async () => ctx.api.state(),
        (s) => claimActiveOf(s) && s.playback?.playing === true,
        15000,
        150,
      );
      const s1 = st.value ?? (await ctx.api.state());
      const w1 = loopWin(s1);
      ctx.evidence.step("rotation-after", {
        window: w1,
        engine: s1.playback?.engine,
        playing: s1.playback?.playing,
        positionMs: s1.playback?.positionMs,
      });
      checks.push(check("旋转后仍在循环且播放", st.ok, "15s 内 claimActive&&playing 未恢复", !st.ok ? "旋转重建后循环/播放丢了:检查 Service 持有与页面重建路径" : undefined));
      checks.push(
        check(
          "窗口 A/B 未变(±1s)",
          w1 != null && w0 != null && w1.songId === w0.songId && Math.abs(w1.aMs - w0.aMs) <= 1000 && Math.abs(w1.bMs - w0.bMs) <= 1000,
          "window " + JSON.stringify(w0) + " -> " + JSON.stringify(w1),
        ),
      );
      const pos1 = Number(s1.playback?.positionMs);
      checks.push(
        check(
          "位置连续(不是从 A 重来)",
          w0 != null && (pos1 >= Math.min(pos0, w0.aMs) - 1000) && pos1 <= (w0?.bMs ?? 0) + 1000,
          "pos " + pos0 + " -> " + pos1 + " window=" + JSON.stringify(w0),
          w0 != null && pos1 < w0.aMs - 1000 ? "旋转后位置回到 A 之前:重建把预览/主播放重置了" : undefined,
        ),
      );
      await sleep(600);
      const audible = await measureMainFrames(ctx.api, 1600, sr);
      checks.push(audioCheck("旋转后仍有输出", audible, 0.85));
      const pid1 = await ctx.adb.pidof(ctx.cfg.pkg);
      checks.push(check("进程未重启", pid0 != null && pid1 === pid0, "pid " + pid0 + " -> " + pid1));
    } finally {
      const restored = origAccel !== "0" && origAccel !== "null"
        ? await freeRotation(ctx, probe.via)
        : (Number.isFinite(Number(origUser)) ? await setRotation(ctx, Number(origUser)) : await freeRotation(ctx, probe.via));
      checks.push(check("finally: 恢复原旋转设置", restored.ok, restored.detail));
      await ctx.api.post("/loop/exit").catch(() => null);
      ctx.evidence.step("rotation-restore", { restored, origAccel, origUser });
    }
    return { checks, summary: "rotation " + rot0 + "->" + target };
  },
};

// ---------------------------------------------------------------------------
// 场景 93：切引擎（SOURCE -> M3'）不得半切换
// ---------------------------------------------------------------------------

const g93: Scenario = {
  id: 93,
  key: "engine-switch-while-looping",
  name: "循环中切引擎(SOURCE->M3'):不得半切换",
  tag: "循环",
  exclusive: false,
  timeoutMs: 130000,
  async run(ctx) {
    const checks: Check[] = [];
    const song = ctx.songs.primary;
    const sr = ctx.sampleRateFor(song.id);
    const info = await songInfo(ctx, song.id);
    const aMs = Math.round(Number(info.loopStartMs));
    const bMs = Math.round(Number(info.loopEndMs));
    await ctx.resetForMain();
    await playSong(ctx, song.id);
    await ctx.api.post("/player/seek", { positionMs: aMs + 3000 });
    await waitDisplayNear(ctx, aMs + 3000, 900);
    await enterLoop(ctx, song.id, true, false);
    const claim = await waitClaim(ctx, song.id, 12000);
    checks.push(check("已进入循环", claim.ok, "engine=" + claim.state?.playback?.engine));
    if (!claim.ok) return { checks, summary: "未进入循环,场景跳过" };
    const w0 = loopWin(claim.state);
    const pid0 = await ctx.adb.pidof(ctx.cfg.pkg);
    const base = await measureMainFrames(ctx.api, 1500, sr);
    checks.push(audioCheck("切换前有输出", base, 0.85));
    try {
      const sw = await ctx.api.post("/debug/source-mode", { on: false });
      checks.push(check("切到 M3' 返回 200", sw.status === 200, "status=" + sw.status + " body=" + sw.text.slice(0, 160)));
      await sleep(1500);
      const s = await ctx.api.state();
      const w = loopWin(s);
      const claimNow = claimActiveOf(s);
      const engine = String(s.playback?.engine ?? "?");
      const pos = Number(s.playback?.positionMs);
      const audible = await measureMainFrames(ctx.api, 1500, sr);
      ctx.evidence.step("after-engine-switch", {
        claimActive: claimNow,
        claimReason: (s as any).loop?.claimReason ?? null,
        window: w,
        engine,
        playing: s.playback?.playing,
        positionMs: pos,
        audible: round(audible.ratio * 100, 1),
        sourceMode: sw.json?.loopSourceMode,
      });
      // 允许两种收敛：完整保持（claim 仍生效、仍出声、窗口不变）或明确退场（window 清空、不假装循环）。
      const windowSame =
        w != null && w0 != null && w.songId === w0.songId &&
        Math.abs(w.aMs - w0.aMs) <= 300 && Math.abs(w.bMs - w0.bMs) <= 300;
      const keptShape = claimNow && engine === "loop" && s.playback?.playing === true && windowSame;
      const keptAudio: boolean | null = audible.available ? audible.ratio >= 0.8 : null;
      const exitedCleanly = !claimNow && w == null;
      checks.push(
        check(
          "切换后 claim/window 自洽(无半切换)",
          (claimNow && windowSame) || exitedCleanly,
          "claim=" + claimNow + " engine=" + engine + " playing=" + s.playback?.playing +
            " window=" + JSON.stringify(w) + " 原窗口=" + JSON.stringify(w0),
          !(claimNow && windowSame) && !exitedCleanly
            ? "半切换:claim 仍 true 但窗口变了/引擎不是 loop，或 claim 已 false 但窗口还在。后续真机测量不可信"
            : undefined,
        ),
      );
      if (claimNow) {
        if (keptAudio === null) {
          checks.push(
            pendingCheck(
              "切换后仍出声",
              "输出帧探针不可用(writtenFrames routeSource=false)，无法量真声；claim 仍生效时不要据此判 PASS",
              "engine-switch-probe",
              "切 M3' 后 invariantProbe.routeSource 可能变 false；要么保持 SOURCE 再测，要么按位置推进代理另立口径",
            ),
          );
        } else {
          checks.push(audioCheck("切换后仍出声", audible, 0.8, "claim 仍 true 但输出帧没有恢复 -> 半切换"));
        }
      } else {
        checks.push(check("明确退场(窗口已清空)", w == null, "window=" + JSON.stringify(w)));
      }
      checks.push(
        check(
          "位置仍在窗口内/或已明确退出",
          exitCondition(pos, claimNow, w, (s as any).loop?.phase ?? null),
          "pos=" + pos + " window=" + JSON.stringify(w) + " claim=" + claimNow + " phase=" + String((s as any).loop?.phase ?? "?"),
        ),
      );
      const pid1 = await ctx.adb.pidof(ctx.cfg.pkg);
      checks.push(check("进程未重启", pid0 != null && pid1 === pid0, "pid " + pid0 + " -> " + pid1));
    } finally {
      const back = await ctx.api.post("/debug/source-mode", { on: true }).catch(() => null);
      const route = await waitFor(
        async () => (await ctx.api.state()).invariantProbe?.routeSource,
        (v) => v === true,
        8000,
        200,
      );
      checks.push(
        check(
          "finally: 切回 SOURCE 且 routeSource=true",
          back?.status === 200 && route.ok,
          "status=" + String(back?.status) + " routeSource=" + String(route.value),
          !route.ok ? "没能恢复 SOURCE 路线：后续场景的出声判据会全部失效，需要 --debug/source-mode {on:true} + 重启" : undefined,
        ),
      );
      await ctx.api.post("/loop/exit").catch(() => null);
      await sleep(400);
    }
    return { checks, summary: "SOURCE->M3' 自洽性检查" };
  },
};

/** 位置自洽：claim 生效时位置在 [A,B]（允许 ±1s），否则不假装窗口。 */
function exitCondition(position: number, claimNow: boolean, w: Win | null, phase: string | null): boolean {
  if (!claimNow) return true;
  if (w == null) return false;
  // 00028：prelude/tail 播放头在媒体时间轴上，不要求 [A,B)。
  if (phase && phase !== "loop") return true;
  return position >= w.aMs - 1000 && position <= w.bMs + 1000;
}

// ---------------------------------------------------------------------------
// 导出（由 lead 合并注册）
// ---------------------------------------------------------------------------

export const GAP_SCENARIOS: Scenario[] = [
  g80,
  g81,
  g82,
  g83,
  g84,
  g85,
  g86,
  g87,
  g88,
  g89,
  g90,
  g91,
  g92,
  g93,
];
