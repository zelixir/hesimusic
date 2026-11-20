#!/usr/bin/env bun
/**
 * tools/scenarios/scenarios.ts —— 场景矩阵本体（16 条主场景 + 1 条常识补充）。
 *
 * 每条场景只做「用户会做的动作序列」，判据只用常识：
 *   真出声（输出帧增长）、位置对、不卡、不违反不变量、不崩。
 * 需要产品决策的地方不替产品定，写进 pendingDecision，由报告列为「待裁决」。
 *
 * 扩展方式见 docs/testing-scenarios.md §5：实现 run(ctx) 并 push 到 SCENARIOS 即可。
 */

import {
  check,
  measureMainFrames,
  measurePreviewFrames,
  msToSamples,
  round,
  sleep,
  waitFramesSettled,
  waitFor,
  within,
} from "./harness";
import type {
  Adb,
  Api,
  Cfg,
  Check,
  Evidence,
  FrameMeasurement,
  SongInfo,
  State,
} from "./harness";

export class SkipError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "SkipError";
  }
}

export interface PreviewSeekResult {
  ok: boolean;
  via: "endpoint" | "snapshot";
  applyMs: number | null;
  advanceMs: number | null;
  latencyToFramesMs: number | null;
  framesDelta: number;
  displayAfterMs: number | null;
  playerPositionAfterMs: number | null;
  isPlayingAfter: boolean | null;
  mode: string | null;
  pendingDecision?: boolean;
  raw?: any;
}

export interface ScenarioCtx {
  cfg: Cfg;
  api: Api;
  adb: Adb;
  songs: { primary: SongInfo; unconfirmed: SongInfo; short: SongInfo };
  evidence: Evidence;
  log: (message: string) => void;
  sampleRateFor: (songId: number) => number;
  resetForMain: () => Promise<void>;
  ensureLoopPage: (songId: number) => Promise<void>;
  previewReady: (timeoutMs?: number) => Promise<any>;
  previewSeek: (positionMs: number) => Promise<PreviewSeekResult>;
  uiTap: (label: string) => Promise<number>;
  uiLabels: () => string[];
  foregroundForeground: () => Promise<boolean>;
}

export interface ScenarioRunResult {
  checks: Check[];
  summary: string;
  pendingDecision?: string[];
}

export interface Scenario {
  id: number;
  key: string;
  name: string;
  tag: string;
  /** 需要独占设备:会切结果页/改窗口/依赖 UI 时序，--exclusive 只跑这些。 */
  exclusive?: boolean;
  run: (ctx: ScenarioCtx) => Promise<ScenarioRunResult>;
}

// ---------------------------------------------------------------------------
// 小工具
// ---------------------------------------------------------------------------

function loopWindowOf(state: State): { songId: number; aMs: number; bMs: number } | null {
  const w = state.loop?.window;
  if (!w) return null;
  return {
    songId: Number(w.songId),
    aMs: Math.round(Number(w.loopStartUs) / 1000),
    bMs: Math.round(Number(w.loopEndUs) / 1000),
  };
}

function repeatName(mode: number | string | null): string {
  if (mode === 1 || mode === "one") return "one";
  if (mode === 2 || mode === "all") return "all";
  return "off";
}

function audioCheck(name: string, m: FrameMeasurement, minRatio: number, hint?: string): Check {
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
  const wait = await waitFor(
    async () => ctx.api.state(),
    (s) => s.playback?.songId === songId && s.playback?.playing === true && s.playback?.playbackState === "ready",
    8000,
    100,
  );
  if (!wait.ok || !wait.value) {
    throw new Error("play did not become ready/playing in 8s (songId=" + songId + ")");
  }
  return wait.value;
}

async function waitPaused(ctx: ScenarioCtx, timeoutMs = 4000): Promise<{ ok: boolean; elapsedMs: number; state: State | null }> {
  const w = await waitFor(
    async () => ctx.api.state(),
    (s) => s.playback?.playing === false && s.playback?.playWhenReady === false,
    timeoutMs,
    60,
  );
  return { ok: w.ok, elapsedMs: w.elapsedMs, state: w.value };
}

async function waitPlayingState(ctx: ScenarioCtx, timeoutMs = 5000): Promise<{ ok: boolean; elapsedMs: number; state: State | null }> {
  const w = await waitFor(
    async () => ctx.api.state(),
    (s) => s.playback?.playing === true,
    timeoutMs,
    60,
  );
  return { ok: w.ok, elapsedMs: w.elapsedMs, state: w.value };
}

async function enterLoop(
  ctx: ScenarioCtx,
  songId: number,
  play: boolean,
  restart: boolean,
): Promise<{ status: number; json: any; text: string }> {
  const r = await ctx.api.post("/loop/enter", { songId, play, restart });
  return { status: r.status, json: r.json, text: r.text };
}

async function waitLoopInstalled(
  ctx: ScenarioCtx,
  songId: number,
  timeoutMs = 6000,
): Promise<{ ok: boolean; elapsedMs: number; state: State | null }> {
  const w = await waitFor(
    async () => ctx.api.state(),
    (s) => loopWindowOf(s)?.songId === songId && s.playback?.engine === "loop",
    timeoutMs,
    100,
  );
  return { ok: w.ok, elapsedMs: w.elapsedMs, state: w.value };
}

async function waitLoopGone(
  ctx: ScenarioCtx,
  timeoutMs = 6000,
): Promise<{ ok: boolean; elapsedMs: number; state: State | null }> {
  const w = await waitFor(
    async () => ctx.api.state(),
    (s) => loopWindowOf(s) === null && s.playback?.engine !== "loop",
    timeoutMs,
    100,
  );
  return { ok: w.ok, elapsedMs: w.elapsedMs, state: w.value };
}

function mainWindowCheck(name: string, state: State | null, songId: number): Check {
  const w = state ? loopWindowOf(state) : null;
  if (!w) return check(name, false, "loop.window 为空");
  const inWindow =
    w.songId === songId &&
    Number(state!.playback.positionMs) >= w.aMs - 1000 &&
    Number(state!.playback.positionMs) <= w.bMs + 1000;
  return check(
    name,
    inWindow,
    "window songId=" + w.songId + " A=" + w.aMs + " B=" + w.bMs +
      " pos=" + state!.playback.positionMs,
  );
}

// ---------------------------------------------------------------------------
// 场景 1：播放 -> 暂停 -> 继续
// ---------------------------------------------------------------------------

const s1: Scenario = {
  id: 1,
  key: "play-pause-resume",
  name: "播放 -> 暂停 -> 继续",
  tag: "播放",
  async run(ctx) {
    const song = ctx.songs.primary;
    const sr = ctx.sampleRateFor(song.id);
    const checks: Check[] = [];
    await ctx.resetForMain();
    const playing = await playSong(ctx, song.id);
    ctx.evidence.step("play", { songId: song.id, positionMs: playing.playback.positionMs });

    const base = await measureMainFrames(ctx.api, 1500, sr);
    checks.push(audioCheck("播放基线有输出", base, 0.85, "出声判据失效:主播放器未走 SOURCE 路线或探测窗口太短"));

    const t0 = Date.now();
    await ctx.api.post("/player/play-pause");
    const paused = await waitPaused(ctx);
    checks.push(check("暂停生效", paused.ok, "耗时 " + (Date.now() - t0) + "ms (ready=" + paused.ok + ")"));
    ctx.evidence.step("paused", paused.state ? { positionMs: paused.state.playback.positionMs } : {});

    // pause 瞬间可能还有一小段硬件尾帧：先等 frames 连续两次采样不变，再判"持续静音"。
    const settled = await waitFramesSettled(ctx.api, 3000);
    ctx.evidence.step("pause-settled", settled);
    checks.push(
      check(
        "暂停尾帧在 3s 内落定",
        settled.ok,
        "settle=" + settled.elapsedMs + "ms frames=" + settled.frames,
        settled.ok ? undefined : "3s 内 writtenFrames 仍在增长: 暂停没有真正停住 sink",
      ),
    );
    await sleep(200);
    const beforeSilence = await ctx.api.state();
    const silent = await measureMainFrames(ctx.api, 1200, sr);
    const afterSilence = await ctx.api.state();
    ctx.evidence.step("paused-silence", { deltaFrames: silent.deltaFrames, spanMs: silent.spanMs });
    checks.push(
      check(
        "暂停期间输出帧为 0",
        silent.deltaFrames === 0,
        "Δ=" + silent.deltaFrames + "f / " + silent.spanMs + "ms",
        silent.deltaFrames > 0 ? "暂停后 sink 仍在写帧 → 检查 pause flush/尾帧,参见 00020 同类问题" : undefined,
      ),
    );
    const posTravel = Math.abs(Number(afterSilence.playback.positionMs) - Number(beforeSilence.playback.positionMs));
    checks.push(check("暂停期间位置不动", posTravel <= 250, "位置变化 " + posTravel + "ms (阈值 250ms)"));

    const t1 = Date.now();
    await ctx.api.post("/player/play-pause");
    const resumed = await waitPlayingState(ctx);
    checks.push(check("继续播放生效", resumed.ok, "耗时 " + (Date.now() - t1) + "ms"));
    await sleep(600); // 让 sink 从 start 抖动里出来，再测稳定输出
    const audible = await measureMainFrames(ctx.api, 2000, sr);
    checks.push(audioCheck("继续后有输出", audible, 0.9, "恢复播放但输出帧没有按 0.9×采样率增长"));
    const resumedState = await ctx.api.state();
    checks.push(check("继续后保持播放", resumedState.playback.playing === true, "playing=" + resumedState.playback.playing));

    return {
      checks,
      summary: "pauseΔ=" + silent.deltaFrames + "f; resume=" + round(audible.ratio * 100, 0) + "%",
    };
  },
};

// ---------------------------------------------------------------------------
// 场景 2：播放中拖动到 50%
// ---------------------------------------------------------------------------

const s2: Scenario = {
  id: 2,
  key: "seek-50pct",
  name: "播放中拖动到 50%",
  tag: "播放",
  async run(ctx) {
    const song = ctx.songs.primary;
    const sr = ctx.sampleRateFor(song.id);
    const checks: Check[] = [];
    await ctx.resetForMain();
    const st = await playSong(ctx, song.id);
    const duration = Number(st.playback.durationMs);
    const target = Math.round(duration / 2);
    const t0 = Date.now();
    const seek = await ctx.api.post("/player/seek", { positionMs: target });
    if (seek.status !== 200) throw new Error("/player/seek -> " + seek.status + " " + seek.text.slice(0, 200));
    const reached = await waitFor(
      async () => ctx.api.state(),
      (s) => Math.abs(Number(s.playback.positionMs) - target) <= 300 && s.playback.playing === true,
      3000,
      40,
    );
    const latency = Date.now() - t0;
    ctx.evidence.step("seek", { target, latencyMs: latency, reached: reached.ok });
    checks.push(check("≤300ms 到达目标点", reached.ok && latency <= 300, "耗时 " + latency + "ms; pos=" + reached.value?.playback.positionMs));
    const after = await ctx.api.state();
    checks.push(check("位置=50%", Math.abs(Number(after.playback.positionMs) - target) <= 500, "target=" + target + " pos=" + after.playback.positionMs));
    const audible = await measureMainFrames(ctx.api, 1600, sr);
    checks.push(audioCheck("拖动后出声", audible, 0.85, "seek 后输出帧未按预期增长"));
    await sleep(700);
    const later = await ctx.api.state();
    const rebound = Number(later.playback.positionMs) < target - 600;
    checks.push(check("进度条不回弹", !rebound, "target=" + target + " 700ms 后 pos=" + later.playback.positionMs));

    return { checks, summary: "latency=" + latency + "ms; audible=" + round(audible.ratio * 100, 0) + "%" };
  },
};

// ---------------------------------------------------------------------------
// 场景 3：播放中进循环
// ---------------------------------------------------------------------------

const s3: Scenario = {
  id: 3,
  key: "enter-loop-playing",
  name: "播放中进循环",
  tag: "循环",
  async run(ctx) {
    const song = ctx.songs.primary;
    const sr = ctx.sampleRateFor(song.id);
    const checks: Check[] = [];
    await ctx.resetForMain();
    await playSong(ctx, song.id);
    const info = await ctx.api.song(song.id);
    const a = Number(info.loopStartMs);
    const b = Number(info.loopEndMs);
    const startAt = a + 3000;
    await ctx.api.post("/player/seek", { positionMs: startAt });
    await waitFor(async () => ctx.api.state(), (s) => Math.abs(Number(s.playback.positionMs) - startAt) <= 800, 3000, 60);

    const t0 = Date.now();
    const enter = await enterLoop(ctx, song.id, true, false);
    if (enter.status !== 200) throw new Error("/loop/enter -> " + enter.status + " " + enter.text.slice(0, 200));
    const installed = await waitLoopInstalled(ctx, song.id, 6000);
    const latency = Date.now() - t0;
    ctx.evidence.step("loop-enter", { latencyMs: latency, response: enter.json, window: installed.state ? loopWindowOf(installed.state) : null });
    checks.push(check("进入循环模式", installed.ok, "engine=" + installed.state?.playback.engine + " 耗时 " + latency + "ms"));
    checks.push(mainWindowCheck("位置在 [A,B)", installed.state, song.id));
    const audible = await measureMainFrames(ctx.api, 1600, sr);
    checks.push(audioCheck("进循环后出声", audible, 0.85, "engine=loop 但输出帧未增长"));
    const st = installed.state ?? (await ctx.api.state());
    checks.push(check("repeatMode=one", String(st.playback.repeatMode) === "one", "repeatMode=" + st.playback.repeatMode));

    return { checks, summary: "enter=" + latency + "ms; A=" + a + " B=" + b + " pos=" + st.playback.positionMs };
  },
};

// ---------------------------------------------------------------------------
// 场景 4：暂停中进循环
// ---------------------------------------------------------------------------

const s4: Scenario = {
  id: 4,
  key: "enter-loop-paused",
  name: "暂停中进循环",
  tag: "循环",
  async run(ctx) {
    const song = ctx.songs.primary;
    const sr = ctx.sampleRateFor(song.id);
    const checks: Check[] = [];
    await ctx.resetForMain();
    await playSong(ctx, song.id);
    const info = await ctx.api.song(song.id);
    const a = Number(info.loopStartMs);
    const b = Number(info.loopEndMs);
    await ctx.api.post("/player/seek", { positionMs: a + 3000 });
    await ctx.api.post("/player/play-pause");
    const paused = await waitPaused(ctx);
    if (!paused.ok) throw new Error("无法暂停(前置步骤失败)");
    await sleep(400);

    const enter = await enterLoop(ctx, song.id, false, false);
    if (enter.status !== 200) throw new Error("/loop/enter -> " + enter.status + " " + enter.text.slice(0, 200));
    await sleep(1200);
    await waitFramesSettled(ctx.api, 3000);
    const stillPaused = await ctx.api.state();
    const silent = await measureMainFrames(ctx.api, 1200, sr);
    ctx.evidence.step("paused-loop-enter", { response: enter.json, deltaFrames: silent.deltaFrames, window: loopWindowOf(stillPaused) });
    checks.push(check("进循环后不自动出声", stillPaused.playback.playing === false, "playing=" + stillPaused.playback.playing));
    checks.push(check("进循环后输出帧为 0", silent.deltaFrames === 0, "Δ=" + silent.deltaFrames + "f / " + silent.spanMs + "ms"));

    const t1 = Date.now();
    await ctx.api.post("/player/play-pause");
    const resumed = await waitPlayingState(ctx);
    checks.push(check("手动播放生效", resumed.ok, "耗时 " + (Date.now() - t1) + "ms"));
    const installed = await waitLoopInstalled(ctx, song.id, 6000);
    checks.push(check("手动播放后进入循环", installed.ok, "engine=" + installed.state?.playback.engine));
    const audible = await measureMainFrames(ctx.api, 1800, sr);
    checks.push(audioCheck("手动播放后出声", audible, 0.85, "恢复播放但循环路线上没有输出帧"));

    return {
      checks,
      summary: "paused silent=" + silent.deltaFrames + "f; resumeLoop=" + (installed.ok ? "yes" : "no") + "; audible=" + round(audible.ratio * 100, 0) + "%",
    };
  },
};

// ---------------------------------------------------------------------------
// 场景 5：循环中退出
// ---------------------------------------------------------------------------

const s5: Scenario = {
  id: 5,
  key: "exit-loop",
  name: "循环中退出",
  tag: "循环",
  async run(ctx) {
    const song = ctx.songs.primary;
    const sr = ctx.sampleRateFor(song.id);
    const checks: Check[] = [];
    await ctx.resetForMain();
    await playSong(ctx, song.id);
    const info = await ctx.api.song(song.id);
    const a = Number(info.loopStartMs);
    await ctx.api.post("/player/seek", { positionMs: a + 3000 });
    await waitFor(async () => ctx.api.state(), (s) => Math.abs(Number(s.playback.positionMs) - (a + 3000)) <= 800, 3000, 60);
    const enter = await enterLoop(ctx, song.id, true, false);
    if (enter.status !== 200) throw new Error("/loop/enter -> " + enter.status);
    const installed = await waitLoopInstalled(ctx, song.id);
    if (!installed.ok) throw new Error("进入循环失败,无法测试退出");
    const prevRepeat = repeatName(enter.json?.previousRepeatMode ?? null);
    const posBefore = Number(installed.state!.playback.positionMs);
    ctx.evidence.step("repeat-before-exit", { previousRepeatMode: prevRepeat, current: installed.state!.playback.repeatMode });

    const t0 = Date.now();
    const exit = await ctx.api.post("/loop/exit");
    if (exit.status !== 200) throw new Error("/loop/exit -> " + exit.status);
    const gone = await waitLoopGone(ctx, 6000);
    const latency = Date.now() - t0;
    const st = gone.state ?? (await ctx.api.state());
    ctx.evidence.step("loop-exit", { latencyMs: latency, window: loopWindowOf(st), repeatMode: st.playback.repeatMode });
    checks.push(check("退出循环模式", gone.ok, "engine=" + st.playback.engine + " 耗时 " + latency + "ms"));
    // 协议口径 /loop/exit 只清意图、不恢复 repeat（见 LoopDebugServer.handleLoopExit）；repeat 值只记证据。
    checks.push(check("退出后仍在播放", st.playback.playing === true, "playing=" + st.playback.playing + " repeat=" + st.playback.repeatMode));
    const posAfter = Number(st.playback.positionMs);
    checks.push(
      check(
        "位置回到媒体时间(不回跳)",
        posAfter >= posBefore - 2000,
        "退出前 " + posBefore + "ms -> 退出后 " + posAfter + "ms (容差 2s)",
        posAfter < posBefore - 2000 ? "退出时把展示位置折返/重置了;期望回到未折叠的媒体时间" : undefined,
      ),
    );
    const audible = await measureMainFrames(ctx.api, 1600, sr);
    checks.push(audioCheck("退出后继续出声", audible, 0.85));

    return {
      checks,
      summary: "exit=" + latency + "ms; pos " + posBefore + "->" + posAfter + "; repeat=" + st.playback.repeatMode,
    };
  },
};

// ---------------------------------------------------------------------------
// 场景 6：循环中改 A/B（结果页 draft 窗口，不落库）
// ---------------------------------------------------------------------------

const s6: Scenario = {
  id: 6,
  key: "change-ab-in-loop",
  name: "循环中改 A/B",
  tag: "循环",
  exclusive: true,
  async run(ctx) {
    const checks: Check[] = [];
    const song = ctx.songs.primary;
    const sr = ctx.sampleRateFor(song.id);
    await ctx.resetForMain();
    await ctx.ensureLoopPage(song.id);
    await ctx.uiTap("仅循环段");
    const playing = await waitFor(
      async () => (await ctx.api.preview()).snapshot,
      (s) => s && s.mode === "LOOP_ONLY" && s.isPlaying === true,
      8000,
      100,
    );
    if (!playing.ok || !playing.value) throw new SkipError("结果页预览未起播,无法改 A/B");
    const snap0 = playing.value;
    const b = Number(snap0.bMs);
    const target = Number(snap0.aMs) + 15000;
    if (target > b - 2000) throw new SkipError("循环窗口太短,无法在区间内改 A");
    const seek = await ctx.previewSeek(target);
    ctx.evidence.step("preview-seek-before-A", seek.raw ?? seek);
    await waitFor(
      async () => (await ctx.api.preview()).snapshot,
      (s) => s && Math.abs(Number(s.displayMs) - target) <= 1500,
      4000,
      80,
    );

    const labels = ctx.uiLabels();
    if (!labels.includes("当前位置→A")) throw new SkipError("结果页找不到「当前位置→A」按钮, labels=" + labels.slice(0, 12).join("|"));
    await ctx.uiTap("当前位置→A");
    const changed = await waitFor(
      async () => (await ctx.api.preview()).snapshot,
      (s) => s && Math.abs(Number(s.aMs) - target) <= 1500,
      4000,
      80,
    );
    const snap1 = changed.value;
    if (!snap1) throw new Error("改 A 后读不到预览快照");
    ctx.evidence.step("after-set-A", { aMs: snap1.aMs, bMs: snap1.bMs, displayMs: snap1.displayMs, isPlaying: snap1.isPlaying });
    checks.push(check("改 A 立即生效", changed.ok, "A=" + snap1.aMs + " (目标≈" + target + ", 原 A=" + snap0.aMs + ")"));
    checks.push(check("改 A 后 B 不变", Math.abs(Number(snap1.bMs) - b) <= 300, "B=" + snap1.bMs + " (原 " + b + ")"));
    checks.push(check("改 A 后仍在播放", snap1.isPlaying === true, "isPlaying=" + snap1.isPlaying));
    const inNew = Number(snap1.displayMs) >= Number(snap1.aMs) - 800 && Number(snap1.displayMs) <= Number(snap1.bMs) + 800;
    checks.push(check("位置在新区间内", inNew, "display=" + snap1.displayMs + " [" + snap1.aMs + "," + snap1.bMs + "]"));
    const audible = await measurePreviewFrames(ctx.api, 1400, sr);
    checks.push(audioCheck("改 A/B 后仍出声", audible, 0.8, "改窗口后预览输出帧停止增长"));
    const st = await ctx.api.state();
    checks.push(check("改 A/B 过程不崩", st.playback?.connected === true, "主播放器 connected=" + st.playback?.connected));

    return {
      checks,
      summary: "A " + snap0.aMs + "->" + snap1.aMs + "; B=" + snap1.bMs + "; audible=" + round(audible.ratio * 100, 0) + "%",
    };
  },
};

// ---------------------------------------------------------------------------
// 场景 7：四种试听模式
// ---------------------------------------------------------------------------

const s7: Scenario = {
  id: 7,
  key: "preview-modes",
  name: "四种试听模式切换",
  tag: "循环",
  exclusive: true,
  async run(ctx) {
    const checks: Check[] = [];
    const song = ctx.songs.primary;
    const sr = ctx.sampleRateFor(song.id);
    await ctx.resetForMain();
    await ctx.ensureLoopPage(song.id);
    const labels = ctx.uiLabels();
    for (const label of ["完整循环", "仅循环段", "接缝检查", "自由"]) {
      if (!labels.includes(label)) throw new SkipError("结果页缺少模式按钮「" + label + "」");
    }
    const modes: { label: string; mode: string }[] = [
      { label: "完整循环", mode: "FULL" },
      { label: "仅循环段", mode: "LOOP_ONLY" },
      { label: "接缝检查", mode: "SEAM" },
      { label: "自由", mode: "FREE" },
    ];
    const summaries: string[] = [];
    for (const item of modes) {
      await ctx.uiTap(item.label);
      const ready = await waitFor(
        async () => (await ctx.api.preview()).snapshot,
        (s) => s && s.mode === item.mode && s.isPlaying === true,
        8000,
        100,
      );
      if (!ready.ok || !ready.value) {
        checks.push(check(item.label + " 起播", false, "8s 内未进入 " + item.mode + " 且 isPlaying"));
        continue;
      }
      await sleep(350);
      const audible = await measurePreviewFrames(ctx.api, 1500, sr);
      const snap = (await ctx.api.preview()).snapshot;
      if (!snap) {
        checks.push(check(item.label + " 出声", false, "预览快照丢失"));
        continue;
      }
      const a = Number(snap.aMs);
      const b = Number(snap.bMs);
      let posOk = false;
      let expect = "";
      if (item.mode === "FULL" || item.mode === "FREE") {
        posOk = Number(snap.displayMs) <= 8000;
        expect = "0..8s";
      } else if (item.mode === "LOOP_ONLY") {
        posOk = within(Number(snap.displayMs), a - 2500, a + 8000) && Number(snap.displayMs) <= b + 500;
        expect = "A-0.3s 起播, A=" + a;
      } else {
        const start = Math.max(a, b - 5000);
        posOk = within(Number(snap.displayMs), start - 1500, start + 5000) && Number(snap.displayMs) <= b + 500;
        expect = "max(A,B-5s)=" + start;
      }
      ctx.evidence.step("mode", {
        label: item.label,
        mode: snap.mode,
        displayMs: snap.displayMs,
        aMs: a,
        bMs: b,
        engine: snap.engine,
        isPlaying: snap.isPlaying,
        frames: audible.deltaFrames,
      });
      checks.push(audioCheck(item.label + " 出声", audible, 0.8, "模式=" + item.mode + " 但输出帧未按预期增长"));
      checks.push(check(item.label + " 位置对", posOk, "display=" + snap.displayMs + " 期望 " + expect));
      summaries.push(item.mode + ":" + round(audible.ratio * 100, 0) + "%");
    }
    return { checks, summary: summaries.join(" ") };
  },
};

// ---------------------------------------------------------------------------
// 场景 8：播放中启动分析
// ---------------------------------------------------------------------------

const s8: Scenario = {
  id: 8,
  key: "analyze-while-playing",
  name: "播放中启动分析",
  tag: "分析",
  async run(ctx) {
    const song = ctx.songs.short;
    const sr = ctx.sampleRateFor(song.id);
    const checks: Check[] = [];
    await ctx.resetForMain();
    await ctx.api.post("/cancelAnalysis");
    await playSong(ctx, song.id);
    await ctx.api.post("/player/seek", { positionMs: 10000 });
    await sleep(400);
    const baseline = await measureMainFrames(ctx.api, 1200, sr);
    checks.push(audioCheck("分析前正在出声", baseline, 0.8));

    const t0 = Date.now();
    let analyze = await ctx.api.post("/analyze", { songId: song.id });
    if (analyze.status === 409) {
      await ctx.api.post("/cancelAnalysis");
      await sleep(600);
      analyze = await ctx.api.post("/analyze", { songId: song.id });
    }
    ctx.evidence.step("analyze", { status: analyze.status, body: analyze.json });
    if (analyze.status !== 200) throw new Error("/analyze -> " + analyze.status + " " + analyze.text.slice(0, 200));

    const active = await waitFor(
      async () => (await ctx.api.get("/analysis")).json,
      (j) => j && (j.analyzing === true || j.phase === "done" || j.phase === "failed"),
      4000,
      100,
    );
    ctx.evidence.step("analysis-active", { phase: active.value?.phase, elapsedMs: Date.now() - t0 });
    await sleep(250);
    const p0 = await ctx.api.state();
    const pos0 = Number(p0.playback.positionMs);
    const silent = await measureMainFrames(ctx.api, 1500, sr);
    const p1 = await ctx.api.state();
    ctx.evidence.step("analysis-silence", { deltaFrames: silent.deltaFrames, spanMs: silent.spanMs, position0: pos0, position1: p1.playback.positionMs, phase: p1.analysis.phase });
    checks.push(check("分析期间播放已暂停", p1.playback.playing === false, "playing=" + p1.playback.playing + " playWhenReady=" + p1.playback.playWhenReady));
    checks.push(
      check(
        "分析期间输出帧为 0",
        silent.deltaFrames === 0,
        "Δ=" + silent.deltaFrames + "f / " + silent.spanMs + "ms",
        silent.deltaFrames > 0 ? "分析开始后 sink 仍在写帧 → pause 未 flush 或分析没有暂停播放" : undefined,
      ),
    );
    const travel = Math.abs(Number(p1.playback.positionMs) - pos0);
    checks.push(check("分析期间位置不动", travel <= 250, "位置变化 " + travel + "ms (阈值 250ms)"));

    const done = await ctx.api.analysisWait(150000);
    const st = await ctx.api.state();
    ctx.evidence.step("analysis-done", { phase: done.phase, elapsedMs: done.elapsedMs, phases: done.phases });
    checks.push(check("分析结束不崩", st.playback?.connected === true, "phase=" + done.phase + ", connected=" + st.playback?.connected + ", 耗时 " + done.elapsedMs + "ms"));
    checks.push(check("分析成功(非 failed)", done.phase === "done", "phase=" + done.phase + " " + (st.analysis?.message ?? "")));

    return {
      checks,
      summary: "analysis " + done.elapsedMs + "ms; duringΔ=" + silent.deltaFrames + "f",
    };
  },
};

// ---------------------------------------------------------------------------
// 场景 9：分析完成 -> 点接缝检查
// ---------------------------------------------------------------------------

const s9: Scenario = {
  id: 9,
  key: "seam-check",
  name: "分析完成 -> 点接缝检查",
  tag: "循环",
  exclusive: true,
  async run(ctx) {
    const checks: Check[] = [];
    const song = ctx.songs.short;
    const sr = ctx.sampleRateFor(song.id);
    await ctx.resetForMain();
    const analysis = (await ctx.api.get("/analysis")).json;
    if (!(analysis?.phase === "done" && Number(analysis.songId) === song.id)) {
      const started = await ctx.api.post("/analyze", { songId: song.id });
      if (started.status !== 200) throw new SkipError("分析未完成且无法启动: " + started.status);
      const done = await ctx.api.analysisWait(150000);
      if (done.phase !== "done") throw new Error("分析失败: " + done.phase);
    }
    await ctx.ensureLoopPage(song.id);
    const snap = await ctx.previewReady();
    const a = Number(snap.aMs);
    const b = Number(snap.bMs);
    const expected = Math.max(a, b - 5000);
    const framesBefore = Number(snap.outputFrames ?? 0);
    const labels = ctx.uiLabels();
    if (!labels.includes("接缝检查")) throw new SkipError("结果页缺少「接缝检查」按钮");
    const t0 = await ctx.uiTap("接缝检查");
    let latency: number | null = null;
    let last: any = null;
    while (Date.now() - t0 < 3000) {
      const p = await ctx.api.preview();
      if (p.available && p.snapshot) {
        last = p.snapshot;
        if (Number(p.snapshot.outputFrames ?? 0) > framesBefore) {
          latency = Date.now() - t0;
          break;
        }
      }
      await sleep(20);
    }
    ctx.evidence.step("seam-click", { latencyMs: latency, expectedMs: expected, aMs: a, bMs: b, snapshot: last });
    if (latency === null) {
      checks.push(check("点接缝检查 ≤500ms 出声", false, "3s 内 outputFrames 未增长", "接缝检查起播链路可能在等预填/重建 player"));
    } else {
      checks.push(check("点接缝检查 ≤500ms 出声", latency <= 500, "首帧增长耗时 " + latency + "ms (门槛 500ms)"));
    }
    await sleep(350);
    const after = (await ctx.api.preview()).snapshot;
    if (!after) throw new Error("点接缝检查后预览快照丢失");
    checks.push(check("模式=接缝检查", after.mode === "SEAM", "mode=" + after.mode));
    const posOk = within(Number(after.displayMs), expected - 1200, expected + 3000);
    checks.push(check("位置=B-5s", posOk, "display=" + after.displayMs + " 期望 " + expected));
    const delta = Number(after.outputFrames ?? 0) - framesBefore;
    checks.push(check("接缝检查有输出", delta > 0, "Δ=" + delta + "f"));

    return {
      checks,
      summary: "seam firstFrame=" + (latency === null ? "timeout" : latency + "ms") + "; display=" + after.displayMs,
    };
  },
};

// ---------------------------------------------------------------------------
// 场景 10：循环区内连续 5 次 seek（结果页预览）
// ---------------------------------------------------------------------------

const s10: Scenario = {
  id: 10,
  key: "seek-inside-loop-x5",
  name: "循环区内连续 5 次 seek",
  tag: "seek",
  exclusive: true,
  async run(ctx) {
    const checks: Check[] = [];
    const song = ctx.songs.short;
    await ctx.resetForMain();
    await ctx.ensureLoopPage(song.id);
    await ctx.uiTap("仅循环段");
    const ready = await waitFor(
      async () => (await ctx.api.preview()).snapshot,
      (s) => s && s.mode === "LOOP_ONLY" && s.isPlaying === true,
      8000,
      100,
    );
    if (!ready.ok || !ready.value) throw new SkipError("结果页预览未起播");
    const snap0 = ready.value;
    const a = Number(snap0.aMs);
    const b = Number(snap0.bMs);
    const seekBefore = Number(snap0.seekRequests ?? 0);
    const details: string[] = [];
    let maxApply = 0;
    let minFrames = Number.MAX_SAFE_INTEGER;
    let lastDisplay: number | null = null;
    for (let i = 1; i <= 5; i++) {
      const target = Math.round(a + ((b - a) * i) / 6);
      const r = await ctx.previewSeek(target);
      ctx.evidence.step("seek-" + i, { target, ...r });
      const apply = r.applyMs ?? 9999;
      maxApply = Math.max(maxApply, apply);
      minFrames = Math.min(minFrames, r.framesDelta);
      lastDisplay = r.displayAfterMs;
      const posOk = r.displayAfterMs !== null && Math.abs(Number(r.displayAfterMs) - target) <= 1500;
      checks.push(
        check(
          "第 " + i + " 次 seek ≤100ms",
          apply <= 100,
          "applyMs=" + (r.applyMs === null ? "n/a" : r.applyMs) + " advanceMs=" + (r.advanceMs ?? "n/a") + " via=" + r.via,
          apply > 100 ? "预览 seek 主线程应用延迟过高(issue 00023 量纲)" : undefined,
        ),
      );
      checks.push(check("第 " + i + " 次 seek 位置对", posOk, "target=" + target + " display=" + r.displayAfterMs));
      checks.push(check("第 " + i + " 次 seek 有输出", r.framesDelta > 0, "framesΔ=" + r.framesDelta));
      details.push(i + ":" + apply + "ms");
    }
    const snapEnd = (await ctx.api.preview()).snapshot;
    const seekDelta = snapEnd ? Number(snapEnd.seekRequests ?? 0) - seekBefore : -1;
    checks.push(check("5 次 seek 不堆积", seekDelta === 5, "seekRequests +" + seekDelta + " (期望 +5)"));
    checks.push(
      check(
        "最终位置停在最后一次目标",
        lastDisplay !== null && snapEnd !== null && Math.abs(Number(snapEnd.displayMs) - Number(lastDisplay)) <= 1500,
        "lastDisplay=" + lastDisplay + " final=" + snapEnd?.displayMs,
      ),
    );

    return { checks, summary: "5x " + details.join(",") + "; maxApply=" + maxApply + "ms" };
  },
};

// ---------------------------------------------------------------------------
// 场景 11：循环区外 seek
// ---------------------------------------------------------------------------

const s11: Scenario = {
  id: 11,
  key: "seek-outside-loop",
  name: "循环区外 seek",
  tag: "seek",
  exclusive: true,
  async run(ctx) {
    const checks: Check[] = [];
    const pending: string[] = [];
    const song = ctx.songs.short;
    await ctx.resetForMain();
    await ctx.ensureLoopPage(song.id);
    await ctx.uiTap("仅循环段");
    const ready = await waitFor(
      async () => (await ctx.api.preview()).snapshot,
      (s) => s && s.mode === "LOOP_ONLY" && s.isPlaying === true,
      8000,
      100,
    );
    if (!ready.ok || !ready.value) throw new SkipError("结果页预览未起播");
    const snap0 = ready.value;
    const a = Number(snap0.aMs);
    const b = Number(snap0.bMs);
    const duration = Number(snap0.durationMs);
    const targets = [
      { label: "A 之前", value: Math.max(0, a - 10000) },
      { label: "B 之后", value: Math.min(duration - 1000, b + 10000) },
    ];
    const details: string[] = [];
    for (const t of targets) {
      const r = await ctx.previewSeek(t.value);
      ctx.evidence.step("seek-outside-" + t.label, { target: t.value, a, b, ...r });
      const posOk = r.displayAfterMs !== null && Math.abs(Number(r.displayAfterMs) - t.value) <= 2000;
      checks.push(
        check(
          t.label + " seek 允许且出声",
          r.ok && r.framesDelta > 0,
          "framesΔ=" + r.framesDelta + " display=" + r.displayAfterMs,
        ),
      );
      checks.push(
        check(
          t.label + " seek 不回弹",
          posOk,
          "target=" + t.value + " display=" + r.displayAfterMs + " player=" + r.playerPositionAfterMs,
          posOk ? undefined : "展示位置被夹回/折返;区外 seek 的期望语义(夹回 vs 直通)需要产品裁决",
        ),
      );
      if (!posOk) pending.push("区外 seek(" + t.label + ") 的期望语义:应允许停在区外,还是夹回 [A,B)? 当前 display=" + r.displayAfterMs + ", target=" + t.value);
      details.push(t.label + ":" + (r.displayAfterMs ?? "?") + "/" + t.value);
    }
    return {
      checks,
      summary: details.join(" "),
      pendingDecision: pending,
    };
  },
};

// ---------------------------------------------------------------------------
// 场景 12：播放中切到下一首（循环标记不串歌）
// ---------------------------------------------------------------------------

const s12: Scenario = {
  id: 12,
  key: "next-song-in-loop",
  name: "播放中切到下一首",
  tag: "播放",
  async run(ctx) {
    const song = ctx.songs.primary;
    const sr = ctx.sampleRateFor(song.id);
    const checks: Check[] = [];
    await ctx.resetForMain();
    const enter = await enterLoop(ctx, song.id, true, true);
    if (enter.status !== 200) throw new Error("/loop/enter(restart) -> " + enter.status + " " + enter.text.slice(0, 200));
    const installed = await waitLoopInstalled(ctx, song.id, 8000);
    if (!installed.ok) throw new Error("无法进入循环(前置失败)");
    const queueSize = Number(installed.state!.playback.queueSize ?? 0);
    const t0 = Date.now();
    await ctx.api.post("/player/next");
    const changed = await waitFor(
      async () => ctx.api.state(),
      (s) => s.playback?.songId != null && Number(s.playback.songId) !== song.id && s.playback.playing === true,
      8000,
      100,
    );
    const st = changed.state ?? (await ctx.api.state());
    const newSongId = Number(st.playback.songId);
    ctx.evidence.step("next", { queueSize, latencyMs: Date.now() - t0, newSongId, engine: st.playback.engine, window: loopWindowOf(st), repeatMode: st.playback.repeatMode });
    checks.push(check("切到下一首", changed.ok, "songId " + song.id + " -> " + newSongId + " (queueSize=" + queueSize + ")"));
    checks.push(check("新歌在播放", st.playback.playing === true, "playing=" + st.playback.playing));
    const srNew = ctx.sampleRateFor(newSongId);
    const audibleNew = await measureMainFrames(ctx.api, 1800, srNew);
    checks.push(audioCheck("新歌正常出声", audibleNew, 0.8));
    const w = loopWindowOf(st);
    checks.push(
      check(
        "循环标记不串歌",
        w === null || w.songId !== newSongId,
        "window.songId=" + (w ? w.songId : "null") + " newSongId=" + newSongId,
        w && w.songId === newSongId ? "切歌后旧循环窗口仍套在新歌上" : undefined,
      ),
    );
    checks.push(check("新歌回到普通引擎", st.playback.engine !== "loop", "engine=" + st.playback.engine));

    return {
      checks,
      summary: "next " + song.id + "->" + newSongId + " in " + (Date.now() - t0) + "ms; engine=" + st.playback.engine,
    };
  },
};

// ---------------------------------------------------------------------------
// 场景 13：后台 -> 前台
// ---------------------------------------------------------------------------

const s13: Scenario = {
  id: 13,
  key: "background-foreground",
  name: "后台 -> 前台",
  tag: "页面",
  async run(ctx) {
    const song = ctx.songs.primary;
    const sr = ctx.sampleRateFor(song.id);
    const checks: Check[] = [];
    await ctx.resetForMain();
    await playSong(ctx, song.id);
    await ctx.api.post("/player/seek", { positionMs: 30000 });
    await sleep(500);
    const fg0 = await ctx.api.state();
    const pos0 = Number(fg0.playback.positionMs);

    await ctx.adb.home();
    await sleep(1500);
    const bg = await ctx.api.state();
    const bgPos = Number(bg.playback.positionMs);
    ctx.evidence.step("background", { playing: bg.playback.playing, positionMs: bgPos });
    checks.push(check("切后台后仍在播放", bg.playback.playing === true, "playing=" + bg.playback.playing));
    checks.push(check("后台位置继续前进", bgPos >= pos0 + 800, "pos " + pos0 + " -> " + bgPos));
    const bgAudio = await measureMainFrames(ctx.api, 1800, sr);
    checks.push(audioCheck("后台声音不断", bgAudio, 0.85, "后台时输出帧停止/下降(音频焦点或服务被挂起)"));

    const brought = await ctx.adb.bringForeground(ctx.cfg.pkg);
    await sleep(1200);
    const fg = await ctx.api.state();
    const fgPos = Number(fg.playback.positionMs);
    ctx.evidence.step("foreground", { brought, playing: fg.playback.playing, positionMs: fgPos });
    checks.push(check("回到前台", brought, "foreground activity 匹配失败"));
    checks.push(check("前台仍在播放", fg.playback.playing === true, "playing=" + fg.playback.playing));
    checks.push(
      check(
        "回前台位置连续",
        fgPos >= bgPos - 1000,
        "pos " + bgPos + " -> " + fgPos + " (容差 1s)",
        fgPos < bgPos - 1000 ? "回前台时播放器被重建/位置重置" : undefined,
      ),
    );
    const fgAudio = await measureMainFrames(ctx.api, 1600, sr);
    checks.push(audioCheck("回前台声音不断", fgAudio, 0.85));

    return {
      checks,
      summary: "bg pos=" + bgPos + " fg pos=" + fgPos + "; bgAudio=" + round(bgAudio.ratio * 100, 0) + "%",
    };
  },
};

// ---------------------------------------------------------------------------
// 场景 14：未确认循环进循环
// ---------------------------------------------------------------------------

const s14: Scenario = {
  id: 14,
  key: "unconfirmed-loop-enter",
  name: "未确认循环进循环",
  tag: "边界",
  async run(ctx) {
    const checks: Check[] = [];
    const song = ctx.songs.unconfirmed;
    await ctx.resetForMain();
    const enter = await enterLoop(ctx, song.id, true, true);
    ctx.evidence.step("enter-unconfirmed", { songId: song.id, status: enter.status, body: enter.json });
    await sleep(3000);
    const st = await ctx.api.state();
    const w = loopWindowOf(st);
    const entered = st.playback.engine === "loop" && w?.songId === song.id;
    ctx.evidence.step("after-3s", { engine: st.playback.engine, window: w, repeatMode: st.playback.repeatMode, playing: st.playback.playing });
    checks.push(
      check(
        "未确认循环不得进入循环模式",
        !entered,
        "engine=" + st.playback.engine + " window=" + (w ? "songId=" + w.songId + " A=" + w.aMs + " B=" + w.bMs : "null"),
        entered ? "协议路径 /loop/enter 没有 confirmed 门禁(UI 可能有拦截);需产品决定协议是否也要拒绝" : undefined,
      ),
    );
    checks.push(check("不崩", st.playback?.connected === true, "connected=" + st.playback?.connected));
    return { checks, summary: "engine=" + st.playback.engine + "; window=" + (w ? "loop#" + w.songId : "null") };
  },
};

// ---------------------------------------------------------------------------
// 场景 15：极短窗口（A=B-3s）循环（结果页预览，100ms 粒度看接缝）
// ---------------------------------------------------------------------------

const s15: Scenario = {
  id: 15,
  key: "short-window-loop",
  name: "极短窗口(A=B-3s)循环",
  tag: "边界",
  exclusive: true,
  async run(ctx) {
    const checks: Check[] = [];
    const song = ctx.songs.short;
    const original = await ctx.api.song(song.id);
    const sr = Number(original.sampleRate ?? 44100);
    const origA = Number(original.loopStartSamples);
    const origB = Number(original.loopEndSamples);
    const duration = Number(original.durationMs);
    const bMs = Math.min(Number(original.loopEndMs ?? duration - 5000), duration - 5000);
    const aMs = Math.max(0, bMs - 3000);
    if (bMs - aMs < 2900) throw new SkipError("短窗口无法构造(B 太靠前)");
    const confirm = await ctx.api.post("/confirm", {
      songId: song.id,
      aSamples: msToSamples(aMs, sr),
      bSamples: msToSamples(bMs, sr),
    });
    ctx.evidence.step("confirm-short-window", { aMs, bMs, status: confirm.status, body: confirm.json });
    if (confirm.status !== 200) throw new Error("/confirm -> " + confirm.status + " " + confirm.text.slice(0, 200));
    let shortWraps = 0;
    let shortGap = 0;
    try {
      const afterConfirm = await ctx.api.song(song.id);
      checks.push(
        check(
          "短窗口已落库",
          Math.abs(Number(afterConfirm.loopStartMs) - aMs) <= 150 && Math.abs(Number(afterConfirm.loopEndMs) - bMs) <= 150,
          "A=" + afterConfirm.loopStartMs + " B=" + afterConfirm.loopEndMs + " (期望≈" + aMs + "/" + bMs + ")",
        ),
      );
      await ctx.resetForMain();
      await ctx.ensureLoopPage(song.id);
      await ctx.uiTap("仅循环段");
      const ready = await waitFor(
        async () => (await ctx.api.preview()).snapshot,
        (s) => s && s.mode === "LOOP_ONLY" && s.isPlaying === true && Math.abs(Number(s.aMs) - aMs) <= 300,
        10000,
        100,
      );
      if (!ready.ok || !ready.value) throw new Error("短窗口预览未起播");
      const t0 = Date.now();
      const samples: { at: number; frames: number; display: number }[] = [];
      while (Date.now() - t0 < 9000) {
        const p = await ctx.api.preview();
        if (p.available && p.snapshot) {
          samples.push({ at: Number(p.snapshot.atMs), frames: Number(p.snapshot.outputFrames ?? 0), display: Number(p.snapshot.displayMs) });
        }
        await sleep(100);
      }
      ctx.evidence.step("short-window-samples", { count: samples.length, samples: samples.filter((_, i) => i % 10 === 0) });
      let wraps = 0;
      let maxGapMs = 0;
      let gapStart = 0;
      for (let i = 1; i < samples.length; i++) {
        if (samples[i].display < samples[i - 1].display - 1500) wraps += 1;
        if (samples[i].frames <= samples[i - 1].frames) {
          if (gapStart === 0) gapStart = samples[i - 1].at;
          maxGapMs = Math.max(maxGapMs, samples[i].at - gapStart);
        } else {
          gapStart = 0;
        }
      }
      const spanMs = samples.length > 1 ? samples[samples.length - 1].at - samples[0].at : 0;
      const deltaFrames = samples.length > 1 ? samples[samples.length - 1].frames - samples[0].frames : 0;
      const ratio = spanMs > 0 ? deltaFrames / ((sr * spanMs) / 1000) : 0;
      shortWraps = wraps;
      shortGap = maxGapMs;
      const outside = samples.filter((s) => s.display < aMs - 1000 || s.display > bMs + 1000).length;
      ctx.evidence.step("short-window-summary", { wraps, maxGapMs, spanMs, deltaFrames, ratio, outside });
      checks.push(check("能循环(≥2 次环回)", wraps >= 2, "9s 内环回 " + wraps + " 次 (3s 窗口)"));
      checks.push(
        check(
          "接缝无停顿",
          maxGapMs <= 300,
          "最长输出帧停顿 " + maxGapMs + "ms (阈值 300ms)",
          maxGapMs > 300 ? "环回点之后 sink 断流;检查 seam 重放/预填" : undefined,
        ),
      );
      checks.push(check("位置始终在 [A,B]", outside === 0, "越界样本 " + outside + "/" + samples.length));
      checks.push(check("整体出声率", ratio >= 0.8, round(ratio * 100, 1) + "%"));
      const st = await ctx.api.state();
      checks.push(check("短窗口循环不崩", st.playback?.connected === true, "connected=" + st.playback?.connected));
    } finally {
      // 恢复原始环区间（本次测试会落库，必须还原）。
      const restore = await ctx.api.post("/confirm", {
        songId: song.id,
        aSamples: origA,
        bSamples: origB,
      });
      const restored = await ctx.api.song(song.id).catch(() => null);
      const ok =
        restore.status === 200 &&
        restored != null &&
        Math.abs(Number(restored.loopStartSamples) - origA) <= 1 &&
        Math.abs(Number(restored.loopEndSamples) - origB) <= 1;
      ctx.evidence.step("restore-loop", { status: restore.status, origA, origB, restored: restored ? { A: restored.loopStartSamples, B: restored.loopEndSamples } : null, ok });
      if (!ok) {
        checks.push(check("恢复原 A/B", false, "未能还原 song " + song.id + " 的 loop 行;需人工检查", "测试污染用户数据"));
      } else {
        checks.push(check("恢复原 A/B", true, "已还原 A=" + origA + " B=" + origB));
      }
    }
    return { checks, summary: "A=B-3s; wraps/9s=" + shortWraps + "; maxGap=" + shortGap + "ms" };
  },
};

// ---------------------------------------------------------------------------
// 场景 16：全流程连跑 3 轮
// ---------------------------------------------------------------------------

const s16: Scenario = {
  id: 16,
  key: "triple-round",
  name: "全流程连跑 3 轮",
  tag: "稳定性",
  async run(ctx) {
    const checks: Check[] = [];
    const song = ctx.songs.primary;
    const sr = ctx.sampleRateFor(song.id);
    const info = await ctx.api.song(song.id);
    const a = Number(info.loopStartMs);
    await ctx.resetForMain();
    await sleep(500);
    const mem0 = await ctx.adb.meminfo(ctx.cfg.pkg);
    ctx.evidence.step("memory-before", mem0);
    const rounds: any[] = [];
    let audibleFail = 0;
    for (let round = 1; round <= 3; round++) {
      if (round > 1) await ctx.resetForMain();
      const t0 = Date.now();
      await playSong(ctx, song.id);
      await ctx.api.post("/player/seek", { positionMs: a + 3000 });
      const enter = await enterLoop(ctx, song.id, true, false);
      if (enter.status !== 200) throw new Error("round " + round + " loop/enter -> " + enter.status);
      const installed = await waitLoopInstalled(ctx, song.id, 6000);
      if (!installed.ok) throw new Error("round " + round + " 未进入循环");
      const audible = await measureMainFrames(ctx.api, 1200, sr);
      if (audible.ratio < 0.8) audibleFail += 1;
      await ctx.api.post("/loop/exit");
      await waitLoopGone(ctx, 5000);
      await ctx.api.post("/player/play-pause");
      await sleep(300);
      await ctx.api.post("/player/play-pause");
      await waitPlayingState(ctx, 4000);
      await ctx.ensureLoopPage(song.id);
      await ctx.uiTap("仅循环段");
      const preview = await waitFor(
        async () => (await ctx.api.preview()).snapshot,
        (s) => s && s.mode === "LOOP_ONLY" && s.isPlaying === true,
        8000,
        100,
      );
      if (!preview.ok) throw new Error("round " + round + " 预览未起播");
      const previewAudio = await measurePreviewFrames(ctx.api, 1200, sr);
      await ctx.resetForMain();
      await sleep(400);
      const mem = await ctx.adb.meminfo(ctx.cfg.pkg);
      const pid = await ctx.adb.pidof(ctx.cfg.pkg);
      const st = await ctx.api.state();
      const invariants = (st.invariants ?? []).map((i: any) => ({ code: i.code, count: i.count }));
      rounds.push({ round, ms: Date.now() - t0, audible: audible.ratio, previewAudio: previewAudio.ratio, mem, pid, invariants });
      ctx.evidence.step("round-" + round, { ms: Date.now() - t0, audible: audible.ratio, preview: previewAudio.ratio, javaHeapKb: mem.javaHeapKb, totalPssKb: mem.totalPssKb, pid });
    }
    const pids = rounds.map((r) => r.pid);
    checks.push(check("三轮进程存活", pids.every((p) => p !== null) && new Set(pids).size === 1, "pid=" + pids.join(",")));
    checks.push(check("三轮均有输出", audibleFail === 0, "出声率低于 80% 的轮数=" + audibleFail));
    const java0 = rounds[0].mem.javaHeapKb;
    const javaN = rounds[rounds.length - 1].mem.javaHeapKb;
    const pss0 = rounds[0].mem.totalPssKb;
    const pssN = rounds[rounds.length - 1].mem.totalPssKb;
    const javaOk = java0 && javaN ? javaN <= java0 + 16384 : true;
    const pssOk = pss0 && pssN ? pssN <= pss0 * 1.15 + 30720 : true;
    checks.push(
      check(
        "heap 稳定",
        javaOk && pssOk,
        "Java Heap " + java0 + "->" + javaN + "KB (容差 16MB); TOTAL PSS " + pss0 + "->" + pssN + "KB (容差 +15%/+30MB)",
        !javaOk || !pssOk ? "三轮后堆增长超阈值;先看 GC 后是否回落,再看预览页是否每轮泄漏" : undefined,
      ),
    );
    // 不变量由 runner 统一做「场景前后 code×count diff」；这里不再用"invariants 必须为空"误伤历史事件。
    return {
      checks,
      summary: "3 rounds; JavaHeap " + java0 + "->" + javaN + "KB; pids=" + new Set(pids).size,
    };
  },
};

// ---------------------------------------------------------------------------
// 场景 17：循环中暂停 -> 继续（常识补充）
// ---------------------------------------------------------------------------

const s17: Scenario = {
  id: 17,
  key: "loop-pause-resume",
  name: "循环中暂停 -> 继续",
  tag: "循环",
  async run(ctx) {
    const checks: Check[] = [];
    const song = ctx.songs.primary;
    const sr = ctx.sampleRateFor(song.id);
    await ctx.resetForMain();
    await playSong(ctx, song.id);
    const info = await ctx.api.song(song.id);
    const a = Number(info.loopStartMs);
    await ctx.api.post("/player/seek", { positionMs: a + 3000 });
    const enter = await enterLoop(ctx, song.id, true, false);
    if (enter.status !== 200) throw new Error("/loop/enter -> " + enter.status);
    const installed = await waitLoopInstalled(ctx, song.id);
    if (!installed.ok) throw new Error("进循环失败");
    await sleep(300);
    const pos0 = Number((await ctx.api.state()).playback.positionMs);
    await ctx.api.post("/player/play-pause");
    const paused = await waitPaused(ctx);
    checks.push(check("循环中暂停生效", paused.ok, "ready=" + paused.ok));
    await waitFramesSettled(ctx.api, 3000);
    await sleep(200);
    const silent = await measureMainFrames(ctx.api, 1200, sr);
    const pos1 = (await ctx.api.state()).playback.positionMs;
    checks.push(check("循环中暂停静音", silent.deltaFrames === 0, "Δ=" + silent.deltaFrames + "f"));
    checks.push(check("循环中暂停位置不动", Math.abs(Number(pos1) - pos0) <= 250, "位置变化 " + Math.abs(Number(pos1) - pos0) + "ms"));
    await ctx.api.post("/player/play-pause");
    const resumed = await waitPlayingState(ctx);
    checks.push(check("循环中继续生效", resumed.ok, "ready=" + resumed.ok));
    const audible = await measureMainFrames(ctx.api, 1800, sr);
    checks.push(audioCheck("循环中继续出声", audible, 0.85));
    const st = await ctx.api.state();
    checks.push(check("继续后仍在循环", st.playback.engine === "loop" && loopWindowOf(st)?.songId === song.id, "engine=" + st.playback.engine));
    return { checks, summary: "pauseΔ=" + silent.deltaFrames + "f; resume=" + round(audible.ratio * 100, 0) + "%" };
  },
};

export const SCENARIOS: Scenario[] = [s1, s2, s3, s4, s5, s6, s7, s8, s9, s10, s11, s12, s13, s14, s15, s16, s17];
