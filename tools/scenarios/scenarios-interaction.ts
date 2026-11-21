#!/usr/bin/env bun
/**
 * tools/scenarios/scenarios-interaction.ts —— 分支 B：用户交互与状态机边界（12 条）。
 *
 * 与另一个 agent 的分支 A（外部环境 / 系统生命周期：通知栏、来电、锁屏、旋转、低内存）互不重叠：
 * 这里只做「用户手快 / 手滑 / 状态机边界」，全部动作走现有调试协议或结果页 UI，不新增后端接口。
 *
 * 判据只用常识：能不能出声（输出帧增长，不用"位置推进"当出声证据）、位置对不对、卡不卡、崩不崩、
 * A/B 有没有被改。需要产品裁决的地方不替产品定，写进 pendingDecision。
 *
 * 连点/连发的实现说明：
 *   uiautomator 单次 dump 就要 1~2s，物理做不到「0.2s 内连点 10 次」。所以"连点"用
 *   POST /player/play-pause、/loop/enter、/loop/exit、/player/seek 这些**与按钮同一条命令路径**
 *   的协议入口，用 20~100ms 的间隔并发下发；只有"没有协议入口"的按钮（模式切换、当前位置→A/B）
 *   才用 ctx.uiTap。这样量到的才是状态机，而不是 uiautomator 的耗时。
 *
 * 副作用纪律：写 song_loop 行的场景（103/104/105/108）一律先记原 sample 值，finally 里用
 * /confirm 精确还原，并把还原结果写进证据；还原失败会多记一条 FAIL（测试污染用户数据）。
 *
 * 接入方式（由 lead 合并）：把 INTERACTION_SCENARIOS 追加进 SCENARIOS，
 * 并把 INTERACTION_UI_SCENARIO_IDS 并入 runner 的 UI_SCENARIOS。
 * 离线自检：bun run tools/scenarios/check-interaction.ts
 */

import {
  check,
  measureMainFrames,
  measurePreviewFrames,
  msToSamples,
  pendingCheck,
  round,
  samplesToMs,
  sleep,
  waitFramesSettled,
  waitFor,
  within,
} from "./harness";
import type { Check, FrameMeasurement, State } from "./harness";
import { SkipError } from "./scenarios";
import type { Scenario, ScenarioCtx, ScenarioRunResult } from "./scenarios";

// ---------------------------------------------------------------------------
// 场景元数据（给 check-interaction.ts 做离线自检用；runner 不消费）
// ---------------------------------------------------------------------------

export interface InteractionScenarioMeta {
  id: number;
  /** 它想抓什么 bug（一句话，必填）。 */
  goal: string;
  /** 预期耗时（超时必须比它大，且不是无限大）。 */
  expectedMs: number;
  /** 是否依赖结果页 UI（uiautomator 点击）。 */
  usesUi: boolean;
  /** 是否会写 song_loop 行（必须 finally 还原）。 */
  writesLoopData: boolean;
  /** 是否会跑 /analyze（超时按分析量级放宽）。 */
  needsAnalysis?: boolean;
  /** shared = 只动播放态/协议；exclusive = 依赖结果页/改库/全局分析。 */
  deviceInteraction: "shared" | "exclusive";
}

/** 依赖结果页 UI 的场景 id：并入 runner 的 UI_SCENARIOS（--skip-ui 会跳过）。 */
export const INTERACTION_UI_SCENARIO_IDS: number[] = [103, 110, 111, 112];

// ---------------------------------------------------------------------------
// 公共小工具（scenarios.ts 里的同名 helper 未导出，这里按同一口径各留一份）
// ---------------------------------------------------------------------------

interface LoopWin {
  songId: number;
  aMs: number;
  bMs: number;
}

function loopWindowOf(state: State | null): LoopWin | null {
  const w = state?.loop?.window;
  if (!w) return null;
  return {
    songId: Number(w.songId),
    aMs: Math.round(Number(w.loopStartUs) / 1000),
    bMs: Math.round(Number(w.loopEndUs) / 1000),
  };
}

function fmtWindow(w: LoopWin | null): string {
  return w ? "loop#" + w.songId + " A=" + w.aMs + " B=" + w.bMs + " (" + (w.bMs - w.aMs) + "ms)" : "null";
}

function audioCheck(name: string, m: FrameMeasurement, minRatio: number, hint?: string): Check {
  if (!m.available) {
    return check(name, false, "输出帧探针不可用: " + (m.reason ?? "unknown"), hint);
  }
  return check(
    name,
    m.ratio >= minRatio,
    "Δ=" + m.deltaFrames + "f / " + m.spanMs + "ms = " + round(m.ratio * 100, 1) + "%(门槛 " + Math.round(minRatio * 100) + "%)",
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
  if (!w.ok || !w.value) throw new Error("播放未在 8s 内 ready/playing (songId=" + songId + ")");
  return w.value;
}

/** 幂等暂停：seek/buffering 期间 isPlaying 会短暂为 false，play-pause 会误判成 play。 */
async function ensurePaused(ctx: ScenarioCtx, timeoutMs = 7000): Promise<{ ok: boolean; elapsedMs: number; state: State | null }> {
  const t0 = Date.now();
  while (Date.now() - t0 < timeoutMs) {
    const s = await ctx.api.state();
    if (s.playback?.playing === false && s.playback?.playWhenReady === false) {
      return { ok: true, elapsedMs: Date.now() - t0, state: s };
    }
    if (s.playback?.playing === false) {
      await sleep(250);
      continue;
    }
    const r = await ctx.api.post("/player/play-pause");
    if (r.status !== 200) {
      await sleep(300);
      continue;
    }
    await sleep(450);
  }
  const s = await ctx.api.state().catch(() => null);
  return { ok: false, elapsedMs: Date.now() - t0, state: s };
}

async function ensurePlaying(ctx: ScenarioCtx, timeoutMs = 7000): Promise<{ ok: boolean; elapsedMs: number; state: State | null }> {
  const t0 = Date.now();
  while (Date.now() - t0 < timeoutMs) {
    const s = await ctx.api.state();
    if (s.playback?.playing === true) return { ok: true, elapsedMs: Date.now() - t0, state: s };
    if (s.playback?.playWhenReady === true) {
      await sleep(250);
      continue;
    }
    const r = await ctx.api.post("/player/play-pause");
    if (r.status !== 200) {
      await sleep(300);
      continue;
    }
    await sleep(400);
  }
  const s = await ctx.api.state().catch(() => null);
  return { ok: false, elapsedMs: Date.now() - t0, state: s };
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
  timeoutMs = 8000,
): Promise<{ ok: boolean; elapsedMs: number; state: State | null }> {
  const w = await waitFor(
    async () => ctx.api.state(),
    (s) => loopWindowOf(s)?.songId === songId && s.playback?.engine === "loop" && s.playback?.playing === true,
    timeoutMs,
    100,
  );
  return { ok: w.ok, elapsedMs: w.elapsedMs, state: w.value };
}

async function waitLoopGone(
  ctx: ScenarioCtx,
  timeoutMs = 8000,
): Promise<{ ok: boolean; elapsedMs: number; state: State | null }> {
  const w = await waitFor(
    async () => ctx.api.state(),
    (s) => loopWindowOf(s) === null && s.playback?.engine !== "loop",
    timeoutMs,
    100,
  );
  return { ok: w.ok, elapsedMs: w.elapsedMs, state: w.value };
}

/** 播放态/引擎/窗口/歌曲的指纹：用来判"落定后不再自己翻转"。 */
function fingerprintOf(s: State): string {
  const w = loopWindowOf(s);
  return [
    String(s.playback?.playing),
    String(s.playback?.playWhenReady),
    String(s.playback?.engine),
    String(s.playback?.songId ?? "null"),
    w ? w.songId + "@" + w.aMs + "-" + w.bMs : "none",
  ].join("|");
}

async function waitStableState(
  ctx: ScenarioCtx,
  opts: { stableMs: number; timeoutMs: number; intervalMs?: number },
): Promise<{ ok: boolean; state: State | null; samples: number; elapsedMs: number; changes: number; fingerprint: string }> {
  const interval = opts.intervalMs ?? 250;
  const t0 = Date.now();
  let lastFp = "";
  let stableSince = 0;
  let samples = 0;
  let changes = 0;
  let lastState: State | null = null;
  while (Date.now() - t0 < opts.timeoutMs) {
    const s = await ctx.api.state();
    lastState = s;
    samples += 1;
    const fp = fingerprintOf(s);
    if (lastFp === "") {
      lastFp = fp;
      stableSince = Date.now();
    } else if (fp !== lastFp) {
      lastFp = fp;
      stableSince = Date.now();
      changes += 1;
    }
    if (Date.now() - stableSince >= opts.stableMs) {
      return { ok: true, state: lastState, samples, elapsedMs: Date.now() - t0, changes, fingerprint: fp };
    }
    await sleep(interval);
  }
  return { ok: false, state: lastState, samples, elapsedMs: Date.now() - t0, changes, fingerprint: lastFp };
}

interface LoopSample {
  atMs: number;
  playing: boolean | null;
  playWhenReady: boolean | null;
  positionMs: number | null;
  durationMs: number | null;
  engine: string;
  window: LoopWin | null;
  frames: number | null;
  seamCount: number | null;
  degradeCount: number | null;
  readFailed: boolean;
}

async function sampleStates(ctx: ScenarioCtx, durationMs: number, intervalMs = 100): Promise<LoopSample[]> {
  const out: LoopSample[] = [];
  const t0 = Date.now();
  while (Date.now() - t0 < durationMs) {
    try {
      const s = await ctx.api.state();
      const w = loopWindowOf(s);
      out.push({
        atMs: Date.now(),
        playing: s.playback?.playing ?? null,
        playWhenReady: s.playback?.playWhenReady ?? null,
        positionMs: s.playback?.positionMs == null ? null : Number(s.playback.positionMs),
        durationMs: s.playback?.durationMs == null ? null : Number(s.playback.durationMs),
        engine: String(s.playback?.engine ?? "?"),
        window: w,
        frames: s.invariantProbe?.writtenFrames == null ? null : Number(s.invariantProbe.writtenFrames),
        seamCount: s.loop?.seamCount == null ? null : Number(s.loop.seamCount),
        degradeCount: s.loop?.degradeCount == null ? null : Number(s.loop.degradeCount),
        readFailed: false,
      });
    } catch {
      out.push({
        atMs: Date.now(),
        playing: null,
        playWhenReady: null,
        positionMs: null,
        durationMs: null,
        engine: "?",
        window: null,
        frames: null,
        seamCount: null,
        degradeCount: null,
        readFailed: true,
      });
    }
    await sleep(intervalMs);
  }
  return out;
}

function countWraps(samples: LoopSample[], minDropMs = 250): number {
  let wraps = 0;
  for (let i = 1; i < samples.length; i++) {
    const prev = samples[i - 1].positionMs;
    const cur = samples[i].positionMs;
    if (prev == null || cur == null) continue;
    if (cur < prev - minDropMs) wraps += 1;
  }
  return wraps;
}

function countOutside(samples: LoopSample[], aMs: number, bMs: number, slackMs = 1000): number {
  return samples.filter((s) => {
    if (s.positionMs == null) return false;
    return s.positionMs < aMs - slackMs || s.positionMs > bMs + slackMs;
  }).length;
}

/** 输出帧"最长不增长"时长：500ms 采样粒度下，>1s 才说明真断流。 */
function maxFrameGapMs(samples: LoopSample[]): number {
  let lastChangeAt = 0;
  let lastFrames: number | null = null;
  let maxGap = 0;
  for (const s of samples) {
    if (s.frames == null) continue;
    if (lastChangeAt === 0) {
      lastChangeAt = s.atMs;
      lastFrames = s.frames;
      continue;
    }
    if (s.frames !== lastFrames) {
      lastChangeAt = s.atMs;
      lastFrames = s.frames;
    } else {
      maxGap = Math.max(maxGap, s.atMs - lastChangeAt);
    }
  }
  return maxGap;
}

function frameRatioOf(
  samples: LoopSample[],
  sampleRate: number,
): { deltaFrames: number; spanMs: number; ratio: number; samples: number } {
  const withFrames = samples.filter((s) => s.frames != null);
  if (withFrames.length < 2) return { deltaFrames: 0, spanMs: 0, ratio: 0, samples: withFrames.length };
  const first = withFrames[0];
  const last = withFrames[withFrames.length - 1];
  const spanMs = last.atMs - first.atMs;
  const deltaFrames = Number(last.frames) - Number(first.frames);
  const ratio = spanMs > 0 && sampleRate > 0 ? deltaFrames / ((sampleRate * spanMs) / 1000) : 0;
  return { deltaFrames, spanMs, ratio, samples: withFrames.length };
}

/** song_loop 行恢复（写库场景的 finally 必做）。 */
async function restoreLoopRow(
  ctx: ScenarioCtx,
  songId: number,
  origA: number,
  origB: number,
): Promise<Check> {
  const restore = await ctx.api.post("/confirm", { songId, aSamples: origA, bSamples: origB });
  const restored = await ctx.api.song(songId).catch(() => null);
  const ok =
    restore.status === 200 &&
    restored != null &&
    Math.abs(Number(restored.loopStartSamples) - origA) <= 1 &&
    Math.abs(Number(restored.loopEndSamples) - origB) <= 1;
  ctx.evidence.step("restore-loop", {
    songId,
    status: restore.status,
    origA,
    origB,
    restored: restored ? { A: restored.loopStartSamples, B: restored.loopEndSamples } : null,
    ok,
  });
  return check(
    "恢复原 A/B",
    ok,
    ok ? "已还原 A=" + origA + " B=" + origB : "未能还原 song " + songId + " 的 loop 行,需人工检查",
    ok ? undefined : "测试污染用户数据",
  );
}

interface SongLoopInfo {
  id: number;
  durationMs: number;
  sampleRate: number;
  origA: number;
  origB: number;
  loopStartMs: number;
  loopEndMs: number;
}

async function songLoopInfo(ctx: ScenarioCtx, songId: number): Promise<SongLoopInfo> {
  const info = await ctx.api.song(songId);
  return {
    id: songId,
    durationMs: Number(info.durationMs),
    sampleRate: Number(info.sampleRate ?? ctx.sampleRateFor(songId)),
    origA: Number(info.loopStartSamples),
    origB: Number(info.loopEndSamples),
    loopStartMs: Number(info.loopStartMs),
    loopEndMs: Number(info.loopEndMs),
  };
}

// ---------------------------------------------------------------------------
// 场景 101：0.2 秒内连点「播放/暂停」10 次
//
// 想抓：连点的 10 条命令在状态机里排队/并发时，playing 与 playWhenReady 会不会被写成互相矛盾的
// 半死态；最后一次点击后 UI 显示的"播放/暂停"与实际声音是否一致；会不会把 sink 卡在 stop 状态
// 再也起不来（再次点击不翻转）、或者连点期间 controller 被打挂。
// ---------------------------------------------------------------------------

const s101: Scenario = {
  id: 101,
  key: "rapid-play-pause-x10",
  name: "0.2s 内连点播放/暂停 10 次",
  tag: "交互",
  timeoutMs: 120000,
  async run(ctx): Promise<ScenarioRunResult> {
    const song = ctx.songs.primary;
    const sr = ctx.sampleRateFor(song.id);
    const checks: Check[] = [];
    await ctx.resetForMain();
    const start = await playSong(ctx, song.id);
    ctx.evidence.step("burst-start", { songId: song.id, playing: start.playback.playing, positionMs: start.playback.positionMs });

    const t0 = Date.now();
    const results = await Promise.all(
      Array.from({ length: 10 }, (_, i) =>
        (async () => {
          await sleep(i * 20);
          return ctx.api.post("/player/play-pause", {}, 12000);
        })(),
      ),
    );
    const burstMs = Date.now() - t0;
    const statuses = results.map((r) => r.status);
    ctx.evidence.step("burst", {
      burstMs,
      statuses,
      bodies: results.slice(0, 4).map((r) => r.json),
    });

    checks.push(
      check(
        "10 次连点全部被接受(无 4xx/5xx)",
        statuses.every((s) => s === 200),
        "status=" + statuses.join(",") + "; 实际下发 " + burstMs + "ms",
        statuses.some((s) => s !== 200) ? "连点期间命令被拒绝:UI 会看起来点了没反应,按 503/504 查 controller 命令通道" : undefined,
      ),
    );

    const settled = await waitStableState(ctx, { stableMs: 1500, timeoutMs: 10000 });
    const st = settled.state ?? (await ctx.api.state());
    ctx.evidence.step("settled", {
      ok: settled.ok,
      elapsedMs: settled.elapsedMs,
      samples: settled.samples,
      changes: settled.changes,
      fingerprint: settled.fingerprint,
      playing: st.playback?.playing,
      playWhenReady: st.playback?.playWhenReady,
      positionMs: st.playback?.positionMs,
      engine: st.playback?.engine,
    });

    checks.push(
      check(
        "落定后状态自洽(playing 与 playWhenReady 不矛盾)",
        !(st.playback?.playing === true && st.playback?.playWhenReady === false),
        "playing=" + st.playback?.playing + " playWhenReady=" + st.playback?.playWhenReady,
        "playing=true 但 playWhenReady=false:播放器被停在不可能状态(UI 显示在播、实际不出声)",
      ),
    );
    checks.push(
      check(
        "落定后 1.5s 内不再自行翻转",
        settled.ok,
        "稳定检测 " + settled.elapsedMs + "ms, 样本 " + settled.samples + ", 期间变化 " + settled.changes + " 次; 终态 " + settled.fingerprint,
        settled.ok ? undefined : "连点结束后状态还在自己跳:命令队列没有收敛",
      ),
    );
    checks.push(check("连点后 controller 仍连接", st.playback?.connected === true, "connected=" + st.playback?.connected));

    const duration = Number(st.playback?.durationMs ?? 0);
    const pos = Number(st.playback?.positionMs ?? 0);
    checks.push(
      check(
        "位置没有跑飞",
        pos >= 0 && (duration <= 0 || pos <= duration + 1500),
        "pos=" + pos + " duration=" + duration,
      ),
    );

    // 再点一次必须翻转：证明状态机没被连点卡死。
    const want = st.playback?.playing === true ? false : true;
    const toggle = await ctx.api.post("/player/play-pause");
    const flipped = await waitFor(
      async () => ctx.api.state(),
      (s) => s.playback?.playing === want || s.playback?.playWhenReady === want,
      5000,
      80,
    );
    checks.push(
      check(
        "再来一次点击必翻转(状态机未卡死)",
        toggle.status === 200 && flipped.ok,
        "期望 playing=" + want + "; 实际 playing=" + flipped.value?.playback?.playing +
          " playWhenReady=" + flipped.value?.playback?.playWhenReady + " (status=" + toggle.status + ")",
        flipped.ok ? undefined : "连点后单次点击没有生效:状态机卡住",
      ),
    );

    const finalSt = flipped.value ?? (await ctx.api.state());
    if (finalSt.playback?.playing === true) {
      const audible = await measureMainFrames(ctx.api, 1500, sr);
      checks.push(audioCheck("落定在播放态时真出声", audible, 0.85, "连点后 playing=true 但输出帧不涨"));
    } else {
      await waitFramesSettled(ctx.api, 3000);
      const silent = await measureMainFrames(ctx.api, 1200, sr);
      checks.push(
        check(
          "落定在暂停态时输出帧为 0",
          silent.deltaFrames === 0,
          "Δ=" + silent.deltaFrames + "f / " + silent.spanMs + "ms",
          silent.deltaFrames > 0 ? "连点后暂停态 sink 仍在写帧" : undefined,
        ),
      );
    }

    await ensurePlaying(ctx);
    return {
      checks,
      summary: "10 clicks/" + burstMs + "ms; 终态 playing=" + st.playback?.playing + " (" + settled.changes + " 次翻转)",
    };
  },
};

// ---------------------------------------------------------------------------
// 场景 102：快速连点「进循环 / 退循环」10 次
//
// 想抓：进/出循环的意图(loopIntent)与窗口在并发命令下会不会留下"engine=loop 但 window=null"
// 或"engine=exo 但 window 还在"的半死态；/state 的 playback.engine 与 loop.engine 两处口径会不会
// 长期不一致；落定后声音/位置是否还对得上。
// ---------------------------------------------------------------------------

const s102: Scenario = {
  id: 102,
  key: "rapid-loop-toggle-x10",
  name: "快速连点进/退循环 10 次",
  tag: "循环",
  timeoutMs: 150000,
  async run(ctx): Promise<ScenarioRunResult> {
    const song = ctx.songs.primary;
    const sr = ctx.sampleRateFor(song.id);
    const checks: Check[] = [];
    await ctx.resetForMain();
    await playSong(ctx, song.id);
    const info = await ctx.api.song(song.id);
    const a = Number(info.loopStartMs);
    await ctx.api.post("/player/seek", { positionMs: a + 3000 });
    await waitFor(async () => ctx.api.state(), (s) => Math.abs(Number(s.playback.positionMs) - (a + 3000)) <= 900, 4000, 60);

    const t0 = Date.now();
    const results = await Promise.all(
      Array.from({ length: 10 }, (_, i) =>
        (async () => {
          await sleep(i * 40);
          return i % 2 === 0
            ? ctx.api.post("/loop/enter", { songId: song.id, play: true, restart: false }, 12000)
            : ctx.api.post("/loop/exit", {}, 12000);
        })(),
      ),
    );
    const burstMs = Date.now() - t0;
    const statuses = results.map((r) => r.status);
    ctx.evidence.step("burst", { burstMs, statuses, songId: song.id });

    checks.push(
      check(
        "10 次进/退请求全部被接受(无 4xx/5xx)",
        statuses.every((s) => s === 200),
        "status=" + statuses.join(",") + "; 实际下发 " + burstMs + "ms",
      ),
    );

    const settled = await waitStableState(ctx, { stableMs: 1500, timeoutMs: 12000 });
    const st = settled.state ?? (await ctx.api.state());
    const win = loopWindowOf(st);
    const engine = String(st.playback?.engine ?? "?");
    const loopEngine = String(st.loop?.engine ?? "?");
    const intent = st.loop?.intent ?? null;
    const isLoop = engine === "loop" && win !== null && win.songId === song.id && win.bMs > win.aMs;
    const isPlain = engine !== "loop" && win === null;
    ctx.evidence.step("settled", {
      ok: settled.ok,
      elapsedMs: settled.elapsedMs,
      samples: settled.samples,
      changes: settled.changes,
      engine,
      loopEngine,
      window: win,
      intent,
      playing: st.playback?.playing,
      repeatMode: st.playback?.repeatMode,
      positionMs: st.playback?.positionMs,
    });

    checks.push(
      check(
        "最终状态明确(要么在循环、要么不在)",
        isLoop || isPlain,
        "engine=" + engine + " window=" + fmtWindow(win) + (isLoop ? " -> 在循环" : isPlain ? " -> 不在循环" : " -> 半死"),
        isLoop || isPlain ? undefined : "半死态:engine 与 window 不自洽(可能出现位置不折返/静音/无限重播)",
      ),
    );
    checks.push(
      check(
        "engine 两处口径一致(playback.engine / loop.engine)",
        engine === loopEngine,
        "playback.engine=" + engine + " loop.engine=" + loopEngine,
      ),
    );
    checks.push(
      check(
        "落定后 1.5s 内不再自行翻转",
        settled.ok,
        "稳定检测 " + settled.elapsedMs + "ms, 样本 " + settled.samples + ", 变化 " + settled.changes + " 次",
        settled.ok ? undefined : "连点结束后引擎/窗口还在跳:意图与窗口没收敛",
      ),
    );
    checks.push(
      check(
        "终态与意图一致",
        (isLoop && intent != null && Number(intent.songId) === song.id) || (isPlain && intent == null),
        "engine=" + engine + " intent=" + (intent ? JSON.stringify(intent) : "null"),
        isLoop || isPlain ? undefined : "窗口/引擎与显式意图不一致",
      ),
    );

    const audible = await measureMainFrames(ctx.api, 1600, sr);
    checks.push(audioCheck("落定后仍然出声", audible, 0.85, "连点进/退循环后不出声:引擎切换把 sink 落在停止态"));
    if (win) {
      const p = Number(st.playback?.positionMs ?? -1);
      checks.push(
        check("落定后位置在窗口内", within(p, win.aMs - 1000, win.bMs + 1000), "pos=" + p + " window=" + fmtWindow(win)),
      );
    }
    checks.push(check("落定后 controller 仍连接", st.playback?.connected === true, "connected=" + st.playback?.connected));

    await ctx.api.post("/loop/exit").catch(() => null);
    await waitLoopGone(ctx, 6000);
    await ctx.resetForMain();
    await ensurePlaying(ctx);
    return {
      checks,
      summary: "10 toggles/" + burstMs + "ms; 终态 " + (isLoop ? "在循环" : isPlain ? "不在循环" : "半死") +
        "; 变化 " + settled.changes + " 次; 出声 " + round(audible.ratio * 100, 0) + "%",
    };
  },
};

// ---------------------------------------------------------------------------
// 场景 103：A > B 的非法拖动（把 A 拖到 B 右边）
//
// 想抓：非法窗口从两条路都能进来 ——
//   1) 协议 /confirm 直接给 A>B：必须拒绝(400)且不能落库；
//   2) 结果页把 A 拖过 B 后松手(用"拖到 B 之后 → 点当前位置→A"复现)：必须拒绝或自动纠正，
//      绝不能留下 B<=A 的空窗口（空窗口会让环回静音/死循环），也不能把内存草稿写回数据库。
// ---------------------------------------------------------------------------

const s103: Scenario = {
  id: 103,
  key: "illegal-drag-a-gt-b",
  name: "A>B 非法拖动(A 拖到 B 右边)",
  tag: "边界",
  exclusive: true,
  timeoutMs: 200000,
  async run(ctx): Promise<ScenarioRunResult> {
    const checks: Check[] = [];
    const song = ctx.songs.primary;
    const info = await songLoopInfo(ctx, song.id);
    await ctx.resetForMain();
    try {
      // ---- 1) 协议路径：A>B 必须被拒绝 ----
      const badA = info.origB + msToSamples(5000, info.sampleRate);
      const rejected = await ctx.api.post("/confirm", { songId: song.id, aSamples: badA, bSamples: info.origB });
      ctx.evidence.step("confirm-a-gt-b", { aSamples: badA, bSamples: info.origB, status: rejected.status, body: rejected.json });
      checks.push(
        check(
          "A>B 的 /confirm 被拒绝(HTTP 400)",
          rejected.status === 400,
          "status=" + rejected.status + " body=" + rejected.text.slice(0, 160),
          rejected.status === 200 ? "非法窗口被接受:后续会产出空窗口/半死循环" : undefined,
        ),
      );
      const afterReject = await ctx.api.song(song.id);
      checks.push(
        check(
          "拒绝后落库 A/B 未变",
          Math.abs(Number(afterReject.loopStartSamples) - info.origA) <= 1 &&
            Math.abs(Number(afterReject.loopEndSamples) - info.origB) <= 1,
          "A=" + afterReject.loopStartSamples + " B=" + afterReject.loopEndSamples +
            " (原 " + info.origA + "/" + info.origB + ")",
        ),
      );

      // 被拒之后原有窗口仍要能正常循环（不能因为一次非法请求把播放路径搞坏）。
      await playSong(ctx, song.id);
      await ctx.api.post("/player/seek", { positionMs: info.loopStartMs + 3000 });
      const enter = await enterLoop(ctx, song.id, true, false);
      const installed = await waitLoopInstalled(ctx, song.id, 10000);
      checks.push(
        check(
          "非法请求后原窗口仍能进循环",
          enter.status === 200 && installed.ok,
          "enter=" + enter.status + " engine=" + installed.state?.playback.engine + " window=" + fmtWindow(loopWindowOf(installed.state ?? null)),
        ),
      );
      const sr = ctx.sampleRateFor(song.id);
      const audible = await measureMainFrames(ctx.api, 1500, sr);
      checks.push(audioCheck("非法请求后原窗口仍出声", audible, 0.85));
      await ctx.resetForMain();

      // ---- 2) UI 路径：把 A 拖过 B ----
      await ctx.ensureLoopPage(song.id);
      const labels = ctx.uiLabels();
      if (!labels.includes("仅循环段") || !labels.includes("当前位置→A")) {
        throw new SkipError("结果页缺少「仅循环段」/「当前位置→A」按钮: " + labels.slice(0, 20).join(" | "));
      }
      await ctx.uiTap("仅循环段");
      const ready = await waitFor(
        async () => (await ctx.api.preview()).snapshot,
        (s) => s && s.mode === "LOOP_ONLY" && s.isPlaying === true,
        10000,
        100,
      );
      if (!ready.ok || !ready.value) throw new SkipError("结果页预览未起播,无法模拟 A 拖过 B");
      const snap0 = ready.value;
      const a0 = Number(snap0.aMs);
      const b0 = Number(snap0.bMs);
      const durationMs = Number(snap0.durationMs);

      // 拖到 B 之后(自由试听)再把 A 设到此处 == 把 A 拖过 B 后松手。
      const outside = await ctx.previewSeek(Math.min(durationMs - 1000, b0 + 8000));
      ctx.evidence.step("drag-before", { a0, b0, outsideSeek: outside, target: Math.min(durationMs - 1000, b0 + 8000) });
      checks.push(
        check(
          "前置: 已拖到 B 之后(自由试听)",
          outside.outsideLoopAfter === true,
          "outsideLoop=" + outside.outsideLoopAfter + " display=" + outside.displayAfterMs,
        ),
      );

      const tapAt = await ctx.uiTap("当前位置→A");
      await sleep(700);
      const pv = await ctx.api.preview();
      const snap1 = pv.snapshot;
      if (!snap1) throw new Error("点「当前位置→A」后预览快照丢失(页面可能崩了)");
      const a1 = Number(snap1.aMs);
      const b1 = Number(snap1.bMs);
      const empty = !(Number.isFinite(a1) && Number.isFinite(b1) && b1 - a1 >= 300);
      const outcome = Math.abs(a1 - a0) <= 2 ? "拒绝(保持原 A)" : "自动纠正";
      ctx.evidence.step("drag-after", {
        tapAt,
        a1,
        b1,
        outcome,
        displayMs: snap1.displayMs,
        outsideLoop: snap1.outsideLoop,
        isPlaying: snap1.isPlaying,
        mode: snap1.mode,
        engine: snap1.engine,
        windowA: snap1.windowA_ms,
        windowB: snap1.windowB_ms,
      });
      checks.push(
        check(
          "非法拖动后窗口不为空(B>A 且 ≥300ms)",
          !empty,
          "a=" + a1 + " b=" + b1 + " (拖前 " + a0 + "/" + b0 + ") -> " + outcome,
          empty ? "A 被拖过 B 后留下了空/负窗口:环回会静音或死循环" : undefined,
        ),
      );
      checks.push(
        check(
          "非法拖动后页面未崩",
          snap1.isPlaying !== undefined && String(snap1.mode ?? "").length > 0,
          "mode=" + snap1.mode + " isPlaying=" + snap1.isPlaying + " engine=" + snap1.engine,
        ),
      );
      if (snap1.isPlaying === true) {
        const pvFrames = await measurePreviewFrames(ctx.api, 1400, sr);
        checks.push(audioCheck("非法拖动后仍能出声(≥70%)", pvFrames, 0.7, "非法窗口把预览弄哑了"));
      } else {
        checks.push(check("非法拖动后仍能出声(≥70%)", false, "isPlaying=false: 非法拖动把预览停住了"));
      }
      const display = Number(snap1.displayMs);
      checks.push(
        check(
          "展示位置没跑飞(0..duration)",
          display >= 0 && display <= durationMs + 2000,
          "display=" + display + " duration=" + durationMs,
        ),
      );

      const persisted = await ctx.api.song(song.id);
      checks.push(
        check(
          "页面草稿不落库(A/B 未被写回)",
          Math.abs(Number(persisted.loopStartSamples) - info.origA) <= 1 &&
            Math.abs(Number(persisted.loopEndSamples) - info.origB) <= 1,
          "落库 A=" + persisted.loopStartSamples + " B=" + persisted.loopEndSamples + " (原 " + info.origA + "/" + info.origB + ")",
        ),
      );
      const mainSt = await ctx.api.state();
      const mw = loopWindowOf(mainSt);
      checks.push(
        check(
          "主播放器窗口未被页面草稿改写",
          mw === null || mw.bMs > mw.aMs,
          "主播放器 window=" + fmtWindow(mw),
        ),
      );

      return {
        checks,
        summary: "A>B 拖动 -> " + outcome + " (a=" + a1 + " b=" + b1 + "); 协议路径 400 拒绝; 主播放器未受影响",
      };
    } finally {
      await ctx.resetForMain().catch(() => null);
    }
  },
};

// ---------------------------------------------------------------------------
// 场景 104：A == B（以及只差 1 个样本的"几乎空"窗口）
//
// 想抓：A==B / B=A+1 这种"零长度窗口"会不会被写进库里。拒绝也行、夹到可用最小宽度也行，
// 但绝不能落库成空窗口（此后循环要么静音空转、要么把播放头钉死在一点）。
// ---------------------------------------------------------------------------

const s104: Scenario = {
  id: 104,
  key: "illegal-ab-a-eq-b",
  name: "A==B 零长度窗口",
  tag: "边界",
  exclusive: true,
  timeoutMs: 150000,
  async run(ctx): Promise<ScenarioRunResult> {
    const checks: Check[] = [];
    const pending: string[] = [];
    const song = ctx.songs.primary;
    const info = await songLoopInfo(ctx, song.id);
    await ctx.resetForMain();
    try {
      // ---- 1) A == B ----
      const eq = await ctx.api.post("/confirm", { songId: song.id, aSamples: info.origB, bSamples: info.origB });
      ctx.evidence.step("confirm-a-eq-b", { aSamples: info.origB, bSamples: info.origB, status: eq.status, body: eq.json });
      checks.push(
        check(
          "A==B 的 /confirm 被拒绝(HTTP 400)",
          eq.status === 400,
          "status=" + eq.status + " body=" + eq.text.slice(0, 160),
          eq.status === 200 ? "零长度窗口被接受:循环会空转" : undefined,
        ),
      );
      const afterEq = await ctx.api.song(song.id);
      checks.push(
        check(
          "拒绝后落库 A/B 未变",
          Math.abs(Number(afterEq.loopStartSamples) - info.origA) <= 1 &&
            Math.abs(Number(afterEq.loopEndSamples) - info.origB) <= 1,
          "A=" + afterEq.loopStartSamples + " B=" + afterEq.loopEndSamples,
        ),
      );

      // ---- 2) B = A + 1 sample（比 A==B 更阴险：能过 b>a 的检查）----
      const tinyA = info.origA;
      const tinyB = info.origA + 1;
      const tiny = await ctx.api.post("/confirm", { songId: song.id, aSamples: tinyA, bSamples: tinyB });
      const rowTiny = await ctx.api.song(song.id);
      const widthSamples = Number(rowTiny.loopEndSamples) - Number(rowTiny.loopStartSamples);
      const widthMs = info.sampleRate > 0 ? samplesToMs(widthSamples, info.sampleRate) : 0;
      const acceptedEmpty = tiny.status === 200 && widthSamples < msToSamples(100, info.sampleRate);
      const clampedOk = tiny.status === 200 && widthSamples >= msToSamples(100, info.sampleRate);
      ctx.evidence.step("confirm-1sample", {
        aSamples: tinyA,
        bSamples: tinyB,
        status: tiny.status,
        body: tiny.json,
        stored: { A: rowTiny.loopStartSamples, B: rowTiny.loopEndSamples, widthSamples, widthMs: round(widthMs, 2) },
      });
      checks.push(
        check(
          "1 样本宽窗口要么拒绝、要么夹到 ≥100ms",
          tiny.status === 400 || clampedOk,
          "status=" + tiny.status + "; 落库宽度 " + widthSamples + " samples ≈ " + round(widthMs, 2) + "ms",
          acceptedEmpty ? "几乎零长度的窗口被落库:进入循环会空转/静音(环回时间 < 采样周期)" : undefined,
        ),
      );
      if (tiny.status !== 400 && acceptedEmpty) {
        pending.push("B=A+1 sample 被 /confirm 接受并落库:需要产品裁决最小可循环窗口(建议与预览页 MIN_LOOP_LENGTH_MS=500ms 对齐)");
      }

      // ---- 3) 无论前面被接受还是拒绝，恢复原窗口后循环必须还正常 ----
      checks.push(await restoreLoopRow(ctx, song.id, info.origA, info.origB));
      await ctx.resetForMain();
      await playSong(ctx, song.id);
      await ctx.api.post("/player/seek", { positionMs: info.loopStartMs + 3000 });
      const enter = await enterLoop(ctx, song.id, true, false);
      const installed = await waitLoopInstalled(ctx, song.id, 10000);
      const w = loopWindowOf(installed.state ?? null);
      checks.push(
        check(
          "非法请求后仍能进入有效循环",
          enter.status === 200 && installed.ok && w !== null && w.bMs - w.aMs >= 500,
          "enter=" + enter.status + " window=" + fmtWindow(w),
        ),
      );
      const sr = ctx.sampleRateFor(song.id);
      const audible = await measureMainFrames(ctx.api, 1500, sr);
      checks.push(audioCheck("非法请求后仍出声", audible, 0.85));
      const p = Number(installed.state?.playback?.positionMs ?? -1);
      checks.push(
        check("位置在窗口内", w !== null && within(p, w.aMs - 1000, w.bMs + 1000), "pos=" + p + " window=" + fmtWindow(w)),
      );
      return {
        checks,
        pendingDecision: pending,
        summary: "A==B -> " + eq.status + "; B=A+1 -> " + tiny.status + "/" + widthSamples + " samples; 恢复后仍能循环",
      };
    } finally {
      await ctx.api.post("/loop/exit").catch(() => null);
      const restored = await restoreLoopRow(ctx, song.id, info.origA, info.origB).catch(() =>
        check("恢复原 A/B", false, "restore 异常"),
      );
      if (!restored.pass) checks.push(restored);
      await ctx.resetForMain().catch(() => null);
    }
  },
};

// ---------------------------------------------------------------------------
// 场景 105：极短窗口(0.5s) 与 极长窗口(接近整曲) 都能循环
//
// 想抓：0.5s 窗口是不是比一个采样周期还短的"环回抖动"（位置乱跳/静音/降级/卡死），
// 接近整曲的窗口会不会因为环带预算(heap/10)被静默降级成普通播放（听起来就是"循环没生效"）。
// ---------------------------------------------------------------------------

const s105: Scenario = {
  id: 105,
  key: "extreme-window-loop",
  name: "极短(0.5s)/极长(接近整曲)窗口循环",
  tag: "边界",
  exclusive: true,
  timeoutMs: 260000,
  async run(ctx): Promise<ScenarioRunResult> {
    const checks: Check[] = [];
    const pending: string[] = [];
    const song = ctx.songs.short;
    const info = await songLoopInfo(ctx, song.id);
    const dur = info.durationMs;
    const sr = info.sampleRate > 0 ? info.sampleRate : ctx.sampleRateFor(song.id);
    if (dur < 20000) throw new SkipError("短歌时长 " + Math.round(dur / 1000) + "s 太短,无法构造极短+极长两组窗口");
    await ctx.resetForMain();
    let shortWraps = 0;
    let shortGap = 0;
    let longMs = 0;
    try {
      // ---- 1) 0.5s 窗口 ----
      const aShort = Math.max(1000, Math.round(dur / 2));
      const bShort = aShort + 500;
      const cShort = await ctx.api.post("/confirm", {
        songId: song.id,
        aSamples: msToSamples(aShort, sr),
        bSamples: msToSamples(bShort, sr),
      });
      ctx.evidence.step("confirm-0.5s", { aMs: aShort, bMs: bShort, status: cShort.status, body: cShort.json });
      if (cShort.status !== 200) {
        checks.push(
          check(
            "0.5s 窗口:要么能循环、要么明确拒绝",
            true,
            "status=" + cShort.status + " body=" + cShort.text.slice(0, 160) + " -> 明确拒绝(不落库)",
          ),
        );
        pending.push("0.5s 极短窗口被 /confirm 拒绝(" + cShort.status + "):需要产品裁决最小可循环窗口");
      } else {
        const row = await ctx.api.song(song.id);
        const storedMs = Number(row.loopEndMs) - Number(row.loopStartMs);
        checks.push(
          check(
            "0.5s 窗口已落库(±50ms)",
            Math.abs(storedMs - 500) <= 50,
            "落库窗口 " + storedMs + "ms (期望 500ms)",
          ),
        );
        await ctx.resetForMain();
        await playSong(ctx, song.id);
        await ctx.api.post("/player/seek", { positionMs: aShort + 50 });
        const enter = await enterLoop(ctx, song.id, true, false);
        const installed = await waitLoopInstalled(ctx, song.id, 12000);
        const w0 = loopWindowOf(installed.state ?? null);
        const seam0 = Number(installed.state?.loop?.seamCount ?? 0);
        const deg0 = Number(installed.state?.loop?.degradeCount ?? 0);
        ctx.evidence.step("short-window-enter", {
          enter: enter.status,
          installed: installed.ok,
          window: w0,
          positionMs: installed.state?.playback?.positionMs,
        });
        checks.push(
          check(
            "0.5s 窗口能进入循环(engine=loop + 窗口宽度≈500ms)",
            enter.status === 200 && installed.ok && w0 !== null && Math.abs(w0.bMs - w0.aMs - 500) <= 150,
            "enter=" + enter.status + " window=" + fmtWindow(w0),
            installed.ok ? undefined : "0.5s 窗口下无法进入循环:极短窗口把环回装不上",
          ),
        );
        const samples = await sampleStates(ctx, 3000, 100);
        const wraps = countWraps(samples, 200);
        const outside = countOutside(samples, aShort, bShort, 700);
        const gap = maxFrameGapMs(samples);
        const ratio = frameRatioOf(samples, sr);
        shortWraps = wraps;
        shortGap = gap;
        ctx.evidence.step("short-window-samples", {
          count: samples.length,
          wraps,
          outside,
          maxFrameGapMs: gap,
          frameRatio: round(ratio.ratio, 3),
          deltaFrames: ratio.deltaFrames,
          spanMs: ratio.spanMs,
        });
        checks.push(
          check(
            "0.5s 窗口在 3s 内环回 ≥2 次",
            wraps >= 2,
            "3s 内环回 " + wraps + " 次(理论 ~6 次)",
            wraps < 2 ? "极短窗口没有环回:位置可能被钉在窗口内某点" : undefined,
          ),
        );
        checks.push(
          check(
            "0.5s 窗口位置始终在 [A,B]",
            outside === 0,
            "越界样本 " + outside + "/" + samples.length + " (A=" + aShort + " B=" + bShort + ")",
          ),
        );
        checks.push(
          check(
            "0.5s 窗口输出帧没有长停顿",
            gap <= 400,
            "最长帧停顿 " + gap + "ms (阈值 400ms)",
            gap > 400 ? "环回接缝处 sink 断流" : undefined,
          ),
        );
        const audible = await measureMainFrames(ctx.api, 1400, sr);
        checks.push(audioCheck("0.5s 窗口真出声", audible, 0.85, "极短窗口下 sink 没在写帧"));
        const stEnd = await ctx.api.state();
        const wEnd = loopWindowOf(stEnd);
        const degDelta = Number(stEnd.loop?.degradeCount ?? 0) - deg0;
        const seamDelta = Number(stEnd.loop?.seamCount ?? 0) - seam0;
        checks.push(
          check(
            "0.5s 窗口不移出循环、不降级",
            String(stEnd.playback?.engine) === "loop" && wEnd !== null && degDelta === 0,
            "engine=" + stEnd.playback?.engine + " window=" + fmtWindow(wEnd) + " seam+" + seamDelta + " degrade+" + degDelta,
          ),
        );
        checks.push(check("0.5s 窗口不崩", stEnd.playback?.connected === true, "connected=" + stEnd.playback?.connected));
      }

      // ---- 2) 接近整曲的窗口 ----
      const aLong = 1000;
      const bLong = Math.max(aLong + 5000, dur - 2000);
      const cLong = await ctx.api.post("/confirm", {
        songId: song.id,
        aSamples: msToSamples(aLong, sr),
        bSamples: msToSamples(bLong, sr),
      });
      longMs = bLong - aLong;
      ctx.evidence.step("confirm-long", {
        aMs: aLong,
        bMs: bLong,
        windowMs: longMs,
        status: cLong.status,
        body: cLong.json,
        windowBytes: Math.round((longMs / 1000) * sr * 2 * 2),
      });
      if (cLong.status !== 200) {
        checks.push(
          check(
            "接近整曲的窗口:要么能循环、要么明确拒绝",
            true,
            "status=" + cLong.status + " body=" + cLong.text.slice(0, 160) + " -> 明确拒绝(不落库)",
          ),
        );
        pending.push("接近整曲(" + Math.round(longMs / 1000) + "s)的窗口被 /confirm 拒绝:需要产品裁决上限");
      } else {
        await ctx.resetForMain();
        await playSong(ctx, song.id);
        await ctx.api.post("/player/seek", { positionMs: aLong + 2000 });
        const enterLong = await enterLoop(ctx, song.id, true, false);
        const instLong = await waitLoopInstalled(ctx, song.id, 12000);
        const wLong = loopWindowOf(instLong.state ?? null);
        const deg0 = Number(instLong.state?.loop?.degradeCount ?? 0);
        checks.push(
          check(
            "接近整曲的窗口能进入循环(engine=loop)",
            enterLong.status === 200 && instLong.ok && wLong !== null,
            "enter=" + enterLong.status + " window=" + fmtWindow(wLong),
            instLong.ok ? undefined : "长窗口没进循环:可能是环带预算(heap/10)把窗口回退成普通播放",
          ),
        );
        checks.push(
          check(
            "长窗口宽度≈时长-3s",
            wLong !== null && Math.abs(wLong.bMs - wLong.aMs - longMs) <= 1500,
            "window=" + fmtWindow(wLong) + " 期望≈" + longMs + "ms",
          ),
        );
        const audibleLong = await measureMainFrames(ctx.api, 1600, sr);
        checks.push(audioCheck("长窗口真出声", audibleLong, 0.85));
        const stLong = await ctx.api.state();
        const p = Number(stLong.playback?.positionMs ?? -1);
        checks.push(
          check(
            "长窗口位置在窗口内",
            wLong !== null && within(p, wLong.aMs - 1000, wLong.bMs + 1000),
            "pos=" + p + " window=" + fmtWindow(wLong),
          ),
        );
        const degLong = Number(stLong.loop?.degradeCount ?? 0) - deg0;
        checks.push(
          check(
            "长窗口没有降级",
            degLong === 0,
            "degrade+" + degLong + " (window " + round(longMs / 1000, 0) + "s ≈ " + round((longMs / 1000) * 0.176, 1) + "MB)",
            degLong > 0 ? "长窗口触发降级:确认是预算回退(普通播放)还是半降级" : undefined,
          ),
        );
        checks.push(check("长窗口不崩", stLong.playback?.connected === true, "connected=" + stLong.playback?.connected));
      }
      return {
        checks,
        pendingDecision: pending,
        summary: "0.5s: wraps/3s=" + shortWraps + " gap=" + shortGap + "ms; 长窗口 " + Math.round(longMs / 1000) + "s",
      };
    } finally {
      await ctx.api.post("/loop/exit").catch(() => null);
      checks.push(await restoreLoopRow(ctx, song.id, info.origA, info.origB).catch(() => check("恢复原 A/B", false, "restore 异常")));
      await ctx.resetForMain().catch(() => null);
    }
  },
};

// ---------------------------------------------------------------------------
// 场景 106：循环中切换歌曲
//
// 想抓：切歌时旧循环的显式意图(loopIntent)没清干净 —— 新歌被套上旧窗口（拿旧 A/B 去截新歌，
// 时长不够直接空窗口），或者切回旧歌时循环"自己复活"。要求：旧循环完全退出、新歌不带旧 A/B。
// ---------------------------------------------------------------------------

const s106: Scenario = {
  id: 106,
  key: "switch-song-in-loop",
  name: "循环中切歌(旧循环完全退出)",
  tag: "循环",
  timeoutMs: 180000,
  async run(ctx): Promise<ScenarioRunResult> {
    const checks: Check[] = [];
    const pending: string[] = [];
    const song = ctx.songs.primary;
    const info = await songLoopInfo(ctx, song.id);
    const sr = ctx.sampleRateFor(song.id);
    await ctx.resetForMain();
    try {
      await playSong(ctx, song.id);
      await ctx.api.post("/player/seek", { positionMs: info.loopStartMs + 3000 });
      const enter = await enterLoop(ctx, song.id, true, false);
      const installed = await waitLoopInstalled(ctx, song.id, 10000);
      if (enter.status !== 200 || !installed.ok) throw new SkipError("前置: 未能进入循环(enter=" + enter.status + ")");
      const baseline = await measureMainFrames(ctx.api, 1200, sr);
      checks.push(audioCheck("前置: 循环中正在出声", baseline, 0.85));
      const win0 = loopWindowOf(installed.state ?? null);
      const intent0 = installed.state?.loop?.intent ?? null;
      ctx.evidence.step("loop-before-switch", { window: win0, intent: intent0, repeatMode: installed.state?.playback?.repeatMode });

      const t0 = Date.now();
      await ctx.api.post("/player/next");
      const changed = await waitFor(
        async () => ctx.api.state(),
        (s) => s.playback?.songId != null && Number(s.playback.songId) !== song.id && s.playback.playing === true,
        12000,
        100,
      );
      const st2 = changed.state ?? (await ctx.api.state());
      const newId = Number(st2.playback?.songId ?? -1);
      const w2 = loopWindowOf(st2);
      ctx.evidence.step("after-next", {
        changed: changed.ok,
        latencyMs: Date.now() - t0,
        newSongId: newId,
        engine: st2.playback?.engine,
        loopEngine: st2.loop?.engine,
        window: w2,
        intent: st2.loop?.intent ?? null,
        repeatMode: st2.playback?.repeatMode,
      });
      checks.push(check("切到下一首并在播放", changed.ok, "songId " + song.id + " -> " + newId + " in " + (Date.now() - t0) + "ms"));
      checks.push(check("旧循环完全退出(窗口清空)", w2 === null, "window=" + fmtWindow(w2), w2 !== null ? "旧窗口还挂着:位置会按旧 A/B 折返" : undefined));
      checks.push(
        check(
          "引擎回到普通播放",
          String(st2.playback?.engine) !== "loop" && String(st2.loop?.engine) !== "loop",
          "playback.engine=" + st2.playback?.engine + " loop.engine=" + st2.loop?.engine,
        ),
      );
      checks.push(
        check(
          "旧循环意图已清空",
          st2.loop?.intent == null,
          "intent=" + (st2.loop?.intent ? JSON.stringify(st2.loop.intent) : "null"),
          st2.loop?.intent ? "切歌后意图还在:再回到该歌会无端复活循环" : undefined,
        ),
      );
      checks.push(
        check(
          "新歌不携带旧 A/B",
          w2 === null || w2.songId !== newId,
          "window.songId=" + (w2 ? w2.songId : "null") + " newSongId=" + newId,
        ),
      );
      const srNew = ctx.sampleRateFor(newId);
      const audibleNew = await measureMainFrames(ctx.api, 1800, srNew);
      checks.push(audioCheck("新歌正常出声", audibleNew, 0.8));
      if (String(st2.playback?.repeatMode) === "one") {
        pending.push("切歌后 repeatMode 仍为 one(进入循环前是 " + (intent0?.previousRepeatMode ?? "?") + "):意图里记着 previousRepeatMode,是否应在新歌上恢复?需要产品裁决");
      }

      // 切回旧歌：循环不得复活。
      const back = await ctx.api.post("/player/play", { songId: song.id, repeatOne: false });
      const backOk = await waitFor(
        async () => ctx.api.state(),
        (s) => Number(s.playback?.songId) === song.id && s.playback?.playing === true,
        10000,
        100,
      );
      const st3 = backOk.value ?? (await ctx.api.state());
      const w3 = loopWindowOf(st3);
      ctx.evidence.step("back-to-old", {
        status: back.status,
        engine: st3.playback?.engine,
        window: w3,
        intent: st3.loop?.intent ?? null,
      });
      checks.push(check("切回旧歌", back.status === 200 && backOk.ok, "status=" + back.status + " songId=" + st3.playback?.songId));
      checks.push(
        check(
          "回到旧歌循环未复活",
          String(st3.playback?.engine) !== "loop" && w3 === null,
          "engine=" + st3.playback?.engine + " window=" + fmtWindow(w3),
          String(st3.playback?.engine) === "loop" || w3 !== null ? "切回旧歌后循环自己复活:意图没清干净" : undefined,
        ),
      );
      checks.push(
        check(
          "回到旧歌意图仍为空",
          st3.loop?.intent == null,
          "intent=" + (st3.loop?.intent ? JSON.stringify(st3.loop.intent) : "null"),
        ),
      );
      const audibleBack = await measureMainFrames(ctx.api, 1600, sr);
      checks.push(audioCheck("回到旧歌正常出声", audibleBack, 0.8));

      return {
        checks,
        pendingDecision: pending,
        summary: "loop " + song.id + " -> " + newId + " (window=" + fmtWindow(w2) + "), 回旧歌 engine=" + st3.playback?.engine,
      };
    } finally {
      await ctx.api.post("/loop/exit").catch(() => null);
      await ctx.resetForMain().catch(() => null);
    }
  },
};

// ---------------------------------------------------------------------------
// 场景 107：循环中重新分析
//
// 想抓：分析任务与环带播放器抢同一份资源时，分析期间会不会出现"engine=loop 但窗口空/暂停了
// 却没暂停干净"的半死态；分析结束后旧窗口还在不在、新候选是否被自动套用（不确认就改窗口），
// 以及分析完成后还能不能恢复正常出声。
//
// 注意：按现行为(00020/1258),开始分析会主动暂停正在播的歌。所以"分析期间播放不中断"这一条
// 不硬判 FAIL，而是按 pendingCheck + pendingDecision 记录现状，交产品裁决。
// ---------------------------------------------------------------------------

const s107: Scenario = {
  id: 107,
  key: "reanalyze-during-loop",
  name: "循环中重新分析",
  tag: "分析",
  exclusive: true,
  timeoutMs: 320000,
  async run(ctx): Promise<ScenarioRunResult> {
    const checks: Check[] = [];
    const pending: string[] = [];
    const song = ctx.songs.short;
    const info = await songLoopInfo(ctx, song.id);
    const sr = ctx.sampleRateFor(song.id);
    await ctx.resetForMain();
    await ctx.api.post("/cancelAnalysis").catch(() => null);
    await sleep(400);
    try {
      await playSong(ctx, song.id);
      await ctx.api.post("/player/seek", { positionMs: info.loopStartMs + 2000 });
      const enter = await enterLoop(ctx, song.id, true, false);
      const installed = await waitLoopInstalled(ctx, song.id, 10000);
      if (enter.status !== 200 || !installed.ok) throw new SkipError("前置: 未能进入循环(进入失败就没法测重分析)");
      const win0 = loopWindowOf(installed.state ?? null);
      const before = await measureMainFrames(ctx.api, 1200, sr);
      checks.push(audioCheck("前置: 循环中正在出声", before, 0.85));
      ctx.evidence.step("loop-before-analysis", { window: win0, positionMs: installed.state?.playback?.positionMs });

      const t0 = Date.now();
      let started = await ctx.api.post("/analyze", { songId: song.id });
      if (started.status === 409) {
        await ctx.api.post("/cancelAnalysis");
        await sleep(800);
        started = await ctx.api.post("/analyze", { songId: song.id });
      }
      ctx.evidence.step("analyze-start", { status: started.status, body: started.json });
      if (started.status !== 200) throw new Error("/analyze -> " + started.status + " " + started.text.slice(0, 200));

      const active = await waitFor(
        async () => (await ctx.api.get("/analysis")).json,
        (j) => j && (j.analyzing === true || j.phase === "done" || j.phase === "failed"),
        6000,
        100,
      );
      ctx.evidence.step("analysis-active", { phase: active.value?.phase, elapsedMs: Date.now() - t0 });

      // 分析期间持续采样：只要 /state 读不到、或出现矛盾状态，就说明状态机被打坏。
      let readFailures = 0;
      let contradictions = 0;
      let halfDead = 0;
      let pausedDuring = false;
      let stoppedDuring = false;
      const samples: any[] = [];
      const deadline = Date.now() + 200000;
      let phase = String(active.value?.phase ?? "?");
      while (Date.now() < deadline && phase !== "done" && phase !== "failed") {
        try {
          const s = await ctx.api.state();
          const w = loopWindowOf(s);
          const playing = s.playback?.playing;
          const pwr = s.playback?.playWhenReady;
          if (playing === true && pwr === false) contradictions += 1;
          if (String(s.playback?.engine) === "loop" && w === null) halfDead += 1;
          if (playing === false) pausedDuring = true;
          samples.push({
            atMs: Date.now() - t0,
            phase,
            playing,
            playWhenReady: pwr,
            engine: s.playback?.engine,
            window: w,
            positionMs: s.playback?.positionMs,
            seamCount: s.loop?.seamCount,
            degradeCount: s.loop?.degradeCount,
          });
        } catch {
          readFailures += 1;
        }
        const a = await ctx.api.get("/analysis");
        if (a.status === 200 && a.json) {
          const next = String(a.json.phase ?? "?");
          if (next !== phase) {
            phase = next;
            if (phase === "failed") stoppedDuring = true;
          }
        }
        await sleep(600);
      }
      ctx.evidence.step("analysis-samples", {
        count: samples.length,
        readFailures,
        contradictions,
        halfDead,
        pausedDuring,
        samples: samples.filter((_, i) => i % 8 === 0),
      });

      checks.push(check("分析期间 /state 一直可读(不卡死)", readFailures === 0, "读失败 " + readFailures + " 次 / 采样 " + samples.length));
      checks.push(
        check(
          "分析期间无矛盾状态(playing 但 playWhenReady=false)",
          contradictions === 0,
          "矛盾样本 " + contradictions,
        ),
      );
      checks.push(
        check(
          "分析期间未出现半死循环(engine=loop 但窗口为空)",
          halfDead === 0,
          "半死样本 " + halfDead,
          halfDead > 0 ? "分析把窗口拆了但引擎还标着 loop:之后位置/出声都会错" : undefined,
        ),
      );
      if (pausedDuring) {
        checks.push(
          pendingCheck(
            "分析期间播放不中断",
            "分析期间 playing=false(现行为:开始分析即暂停)",
            "product-decision:analysis-pauses-playback",
            "与场景 8 的现行判据一致;若产品决定改成就地分析不打断,再转硬判",
          ),
        );
        pending.push("循环中重新分析:分析期间播放被暂停(现行契约),而交互期望是'不中断';需要产品裁决");
      } else {
        checks.push(check("分析期间播放不中断", true, "分析期间 playing 一直为 true"));
      }

      const stEnd = await ctx.api.state();
      const analysisEnd = (await ctx.api.get("/analysis")).json;
      ctx.evidence.step("analysis-end", {
        phase: analysisEnd?.phase,
        elapsedMs: Date.now() - t0,
        engine: stEnd.playback?.engine,
        window: loopWindowOf(stEnd),
        playing: stEnd.playback?.playing,
      });
      checks.push(check("分析结束不崩", stEnd.playback?.connected === true, "connected=" + stEnd.playback?.connected));
      checks.push(
        check(
          "分析成功(phase=done)",
          String(analysisEnd?.phase) === "done",
          "phase=" + analysisEnd?.phase + " " + (analysisEnd?.message ?? "") + " (" + Math.round((Date.now() - t0) / 1000) + "s)",
        ),
      );

      // 恢复循环：分析结束后窗口该怎么更新。
      // 分析会把正在播的歌暂停（现行契约），所以先按用户动作"继续播放"，再判窗口是否还可用；
      // 窗口丢了才重新 /loop/enter，这样"窗口是否被分析改掉"的判断不会被"暂停导致没声"污染。
      const loopUsable = (st: State | null): boolean => {
        const w = loopWindowOf(st);
        return String(st?.playback?.engine) === "loop" && w !== null && w.songId === song.id && w.bMs > w.aMs;
      };
      const resumed = await ensurePlaying(ctx);
      await sleep(600);
      let stAfterAnalysis = await ctx.api.state();
      let wAfter = loopWindowOf(stAfterAnalysis);
      ctx.evidence.step("resume-after-analysis", {
        resumed: resumed.ok,
        playing: stAfterAnalysis.playback?.playing,
        engine: stAfterAnalysis.playback?.engine,
        window: wAfter,
      });
      if (!loopUsable(stAfterAnalysis)) {
        await ctx.api.post("/player/seek", { positionMs: info.loopStartMs + 2000 });
        const reenter = await enterLoop(ctx, song.id, true, false);
        const reinstalled = await waitLoopInstalled(ctx, song.id, 12000);
        stAfterAnalysis = reinstalled.state ?? (await ctx.api.state());
        wAfter = loopWindowOf(stAfterAnalysis);
        ctx.evidence.step("reenter-after-analysis", {
          enter: reenter.status,
          installed: reinstalled.ok,
          window: wAfter,
        });
      }
      const rowAfter = await ctx.api.song(song.id);
      const windowChanged =
        win0 !== null && wAfter !== null && (Math.abs(wAfter.aMs - win0.aMs) > 2 || Math.abs(wAfter.bMs - win0.bMs) > 2);
      ctx.evidence.step("window-after-analysis", {
        before: win0,
        after: wAfter,
        changed: windowChanged,
        stored: { A: rowAfter.loopStartSamples, B: rowAfter.loopEndSamples },
        storedChanged:
          Math.abs(Number(rowAfter.loopStartSamples) - info.origA) > 1 || Math.abs(Number(rowAfter.loopEndSamples) - info.origB) > 1,
      });
      checks.push(
        check(
          "分析完成后窗口是本歌的有效窗口",
          wAfter !== null && wAfter.songId === song.id && wAfter.bMs - wAfter.aMs >= 500,
          "window=" + fmtWindow(wAfter),
          wAfter === null ? "分析结束后循环窗口丢了:用户回到页面会变成普通播放" : undefined,
        ),
      );
      const audibleAfter = await measureMainFrames(ctx.api, 1600, sr);
      checks.push(audioCheck("分析完成后仍能出声", audibleAfter, 0.85));
      const stAfter = await ctx.api.state();
      const pAfter = Number(stAfter.playback?.positionMs ?? -1);
      checks.push(
        check(
          "分析完成后位置在窗口内",
          wAfter !== null && within(pAfter, wAfter.aMs - 1000, wAfter.bMs + 1000),
          "pos=" + pAfter + " window=" + fmtWindow(wAfter),
        ),
      );
      if (windowChanged) {
        pending.push(
          "重新分析后窗口从 " + (win0 ? win0.aMs + "/" + win0.bMs : "?") + " 变成 " + (wAfter ? wAfter.aMs + "/" + wAfter.bMs : "?") +
            "(未确认):需要产品裁决分析候选是否允许改动正在循环的窗口",
        );
      }
      return {
        checks,
        pendingDecision: pending,
        summary: "analysis " + Math.round((Date.now() - t0) / 1000) + "s; 期间 " + (pausedDuring ? "暂停" : "不中断") +
          "; 窗口 " + (windowChanged ? "已变化" : "未变") + "; 出声 " + round(audibleAfter.ratio * 100, 0) + "%",
      };
    } finally {
      await ctx.api.post("/loop/exit").catch(() => null);
      // 分析成功会落库"临时结果"，可能改写这首歌的 loop 行；这里把它还原成跑之前那条（用户数据）。
      const restored = await restoreLoopRow(ctx, song.id, info.origA, info.origB).catch(() =>
        check("恢复原 A/B", false, "restore 异常"),
      );
      if (!restored.pass) checks.push(restored);
      await ctx.resetForMain().catch(() => null);
    }
  },
};

// ---------------------------------------------------------------------------
// 场景 108：暂停状态下改 A/B，恢复后从正确的 A 开始
//
// 想抓：暂停时改窗口，恢复播放没有把播放头拉回新窗口 —— 结果是"在播但没声"（位置在 A 之前，
// 环带源没有可写数据）或从旧位置继续（听起来像改窗口没生效）。
// ---------------------------------------------------------------------------

const s108: Scenario = {
  id: 108,
  key: "paused-change-ab-resume",
  name: "暂停中改 A/B 后恢复",
  tag: "边界",
  exclusive: true,
  timeoutMs: 180000,
  async run(ctx): Promise<ScenarioRunResult> {
    const checks: Check[] = [];
    const song = ctx.songs.primary;
    const info = await songLoopInfo(ctx, song.id);
    const sr = ctx.sampleRateFor(song.id);
    const span = info.loopEndMs - (info.loopStartMs + 3000);
    if (span < 3000) throw new SkipError("循环窗口 " + Math.round(span / 1000) + "s 太短,无法构造'暂停位置在新 A 之前'");
    const delta = span > 25000 ? 20000 : Math.max(1500, Math.floor(span / 2));
    const pauseAt = info.loopStartMs + 3000;
    const newA = pauseAt + delta;
    const newB = info.loopEndMs;
    if (newB - newA < 1000) throw new SkipError("构造的新窗口太窄(" + (newB - newA) + "ms)");
    await ctx.resetForMain();
    try {
      await playSong(ctx, song.id);
      await ctx.api.post("/player/seek", { positionMs: pauseAt });
      const enter = await enterLoop(ctx, song.id, true, false);
      const installed = await waitLoopInstalled(ctx, song.id, 10000);
      if (enter.status !== 200 || !installed.ok) throw new SkipError("前置: 未能进入循环");
      await ctx.api.post("/player/seek", { positionMs: pauseAt });
      await sleep(300);
      const paused = await ensurePaused(ctx);
      await waitFramesSettled(ctx.api, 3000);
      ctx.evidence.step("paused-in-loop", {
        paused: paused.ok,
        positionMs: paused.state?.playback?.positionMs,
        window: loopWindowOf(paused.state ?? null),
      });
      checks.push(check("循环中暂停生效", paused.ok, "ready=" + paused.ok));
      const silent1 = await measureMainFrames(ctx.api, 1000, sr);
      checks.push(check("暂停时输出帧为 0", silent1.deltaFrames === 0, "Δ=" + silent1.deltaFrames + "f"));

      // 暂停态下把 A 推到暂停位置之后（用户拖 A 的效果），B 不变。
      const confirm = await ctx.api.post("/confirm", {
        songId: song.id,
        aSamples: msToSamples(newA, sr),
        bSamples: msToSamples(newB, sr),
      });
      ctx.evidence.step("confirm-while-paused", {
        newA,
        newB,
        status: confirm.status,
        body: confirm.json,
        note: "/confirm 自带 autoPlay,所以随后立即重新暂停",
      });
      checks.push(check("暂停态下改 A/B 被接受", confirm.status === 200, "status=" + confirm.status + " " + confirm.text.slice(0, 160)));
      const inst2 = await waitLoopInstalled(ctx, song.id, 10000);
      const w2 = loopWindowOf(inst2.state ?? null);
      checks.push(
        check(
          "新窗口已生效(A≈" + newA + ")",
          w2 !== null && Math.abs(w2.aMs - newA) <= 300 && Math.abs(w2.bMs - newB) <= 300,
          "window=" + fmtWindow(w2) + " (期望 A=" + newA + " B=" + newB + ")",
        ),
      );

      const paused2 = await ensurePaused(ctx);
      await waitFramesSettled(ctx.api, 3000);
      checks.push(check("改窗口后重新暂停成功", paused2.ok, "ready=" + paused2.ok));
      // 把暂停位置放到新 A 之前（用户拖进度条停在窗口外再按播放）。
      await ctx.api.post("/player/seek", { positionMs: pauseAt });
      await sleep(600);
      const beforeResume = await ctx.api.state();
      const silent2 = await measureMainFrames(ctx.api, 900, sr);
      ctx.evidence.step("before-resume", {
        positionMs: beforeResume.playback?.positionMs,
        playing: beforeResume.playback?.playing,
        playWhenReady: beforeResume.playback?.playWhenReady,
        window: loopWindowOf(beforeResume),
        deltaFrames: silent2.deltaFrames,
      });
      checks.push(
        check(
          "暂停位置在新 A 之前(前置)",
          beforeResume.playback?.playing === false,
          "playing=" + beforeResume.playback?.playing + " playWhenReady=" + beforeResume.playback?.playWhenReady,
        ),
      );
      checks.push(check("暂停中不自动出声", silent2.deltaFrames === 0, "Δ=" + silent2.deltaFrames + "f"));

      const resumed = await ensurePlaying(ctx);
      checks.push(check("恢复播放生效", resumed.ok, "ready=" + resumed.ok + " 耗时 " + resumed.elapsedMs + "ms"));
      await sleep(900);
      const stAfter = await ctx.api.state();
      const wAfter = loopWindowOf(stAfter);
      const pAfter = Number(stAfter.playback?.positionMs ?? -1);
      const startedNearA = wAfter !== null && pAfter >= wAfter.aMs - 1000 && pAfter <= wAfter.aMs + 5000;
      ctx.evidence.step("after-resume", {
        positionMs: pAfter,
        window: wAfter,
        startedNearA,
        playing: stAfter.playback?.playing,
      });
      checks.push(
        check(
          "恢复后位置落在新窗口内",
          wAfter !== null && within(pAfter, wAfter.aMs - 1000, wAfter.bMs + 1000),
          "pos=" + pAfter + " window=" + fmtWindow(wAfter),
          wAfter !== null && !within(pAfter, wAfter.aMs - 1000, wAfter.bMs + 1000) ? "恢复后播放头不在新窗口:会在窗口外静音空转" : undefined,
        ),
      );
      const audible = await measureMainFrames(ctx.api, 1600, sr);
      checks.push(audioCheck("恢复后真出声", audible, 0.85, "改窗口后恢复播放但没声:播放头没被拉回新 A"));
      checks.push(
        check(
          "恢复后从新 A 附近起播(A-1s..A+5s)",
          startedNearA,
          "pos=" + pAfter + " 新 A=" + (wAfter?.aMs ?? "?") + " -> " + (startedNearA ? "从 A 起播" : "不是从 A 起播"),
          startedNearA ? undefined : "暂停位置在 A 之前时,恢复应从 A 起播;否则用户听到的是窗口外的一段",
        ),
      );

      return {
        checks,
        summary: "暂停改 A " + info.loopStartMs + "->" + newA + "; 恢复 pos=" + pAfter + "; 出声 " + round(audible.ratio * 100, 0) + "%",
      };
    } finally {
      await ctx.api.post("/loop/exit").catch(() => null);
      checks.push(await restoreLoopRow(ctx, song.id, info.origA, info.origB).catch(() => check("恢复原 A/B", false, "restore 异常")));
      await ctx.resetForMain().catch(() => null);
    }
  },
};

// ---------------------------------------------------------------------------
// 场景 109：重复进入同一首歌的循环
//
// 想抓：重复点"进循环"会不会每点一次就重建一次数据源/再压一层队列 —— 表现是位置被重置回 A、
// 过程中出现长静音（重建源），或者窗口/队列重复堆积。
// ---------------------------------------------------------------------------

const s109: Scenario = {
  id: 109,
  key: "repeat-enter-same-loop",
  name: "重复进入同一首歌的循环 5 次",
  tag: "循环",
  timeoutMs: 150000,
  async run(ctx): Promise<ScenarioRunResult> {
    const checks: Check[] = [];
    const song = ctx.songs.primary;
    const info = await songLoopInfo(ctx, song.id);
    const sr = ctx.sampleRateFor(song.id);
    await ctx.resetForMain();
    try {
      await playSong(ctx, song.id);
      await ctx.api.post("/player/seek", { positionMs: info.loopStartMs + 2000 });
      const first = await enterLoop(ctx, song.id, true, false);
      const installed = await waitLoopInstalled(ctx, song.id, 10000);
      if (first.status !== 200 || !installed.ok) throw new SkipError("前置: 未能进入循环");
      const win0 = loopWindowOf(installed.state ?? null);
      const queue0 = Number(installed.state?.playback?.queueSize ?? -1);
      const deg0 = Number(installed.state?.loop?.degradeCount ?? 0);
      ctx.evidence.step("loop-installed", { window: win0, queueSize: queue0, positionMs: installed.state?.playback?.positionMs });

      // 边连点边采样：位置/心跳/帧计数一起看。
      const sampling: LoopSample[] = [];
      let stop = false;
      const sampler = (async () => {
        while (!stop) {
          try {
            const s = await ctx.api.state();
            sampling.push({
              atMs: Date.now(),
              playing: s.playback?.playing ?? null,
              playWhenReady: s.playback?.playWhenReady ?? null,
              positionMs: s.playback?.positionMs == null ? null : Number(s.playback.positionMs),
              durationMs: s.playback?.durationMs == null ? null : Number(s.playback.durationMs),
              engine: String(s.playback?.engine ?? "?"),
              window: loopWindowOf(s),
              frames: s.invariantProbe?.writtenFrames == null ? null : Number(s.invariantProbe.writtenFrames),
              seamCount: s.loop?.seamCount == null ? null : Number(s.loop.seamCount),
              degradeCount: s.loop?.degradeCount == null ? null : Number(s.loop.degradeCount),
              readFailed: false,
            });
          } catch {
            sampling.push({
              atMs: Date.now(),
              playing: null,
              playWhenReady: null,
              positionMs: null,
              durationMs: null,
              engine: "?",
              window: null,
              frames: null,
              seamCount: null,
              degradeCount: null,
              readFailed: true,
            });
          }
          await sleep(100);
        }
      })();

      const results: number[] = [];
      for (let i = 0; i < 5; i++) {
        const r = await ctx.api.post("/loop/enter", { songId: song.id, play: true, restart: false }, 12000);
        results.push(r.status);
        await sleep(350);
      }
      stop = true;
      await sampler;
      const st = await ctx.api.state();
      const winEnd = loopWindowOf(st);
      const queueEnd = Number(st.playback?.queueSize ?? -1);
      const degDelta = Number(st.loop?.degradeCount ?? 0) - deg0;
      const ratio = frameRatioOf(sampling, sr);
      const outside = win0 ? countOutside(sampling, win0.aMs, win0.bMs, 1000) : 0;
      const windows = new Set(sampling.map((s) => (s.window ? s.window.songId + "@" + s.window.aMs + "-" + s.window.bMs : "none")));
      const nonLoopSamples = sampling.filter((s) => s.engine !== "loop").length;
      const readFailures = sampling.filter((s) => s.readFailed).length;
      // 位置"回退到 A"检测：连点期间总时长 <4s，远小于窗口长度，正常不应出现大幅回退。
      let resets = 0;
      for (let i = 1; i < sampling.length; i++) {
        const prev = sampling[i - 1].positionMs;
        const cur = sampling[i].positionMs;
        if (prev != null && cur != null && cur < prev - 1000) resets += 1;
      }
      ctx.evidence.step("repeat-enter", {
        statuses: results,
        samples: sampling.length,
        ratio: round(ratio.ratio, 3),
        deltaFrames: ratio.deltaFrames,
        spanMs: ratio.spanMs,
        outside,
        resets,
        windows: Array.from(windows),
        nonLoopSamples,
        readFailures,
        windowStart: win0,
        windowEnd: winEnd,
        queue0,
        queueEnd,
        degradeDelta: degDelta,
      });

      checks.push(check("5 次进入请求全部被接受", results.every((s) => s === 200), "status=" + results.join(",")));
      checks.push(
        check(
          "窗口唯一且没变",
          windows.size === 1 && win0 !== null && winEnd !== null && winEnd.songId === win0.songId &&
            Math.abs(winEnd.aMs - win0.aMs) <= 2 && Math.abs(winEnd.bMs - win0.bMs) <= 2,
          "采样中出现的窗口: " + Array.from(windows).join(" | ") + "; 终态 " + fmtWindow(winEnd),
          windows.size !== 1 ? "重复进入把窗口换掉了(可能重建了窗口/数据源)" : undefined,
        ),
      );
      checks.push(
        check(
          "5 次进入期间位置没有被打回 A",
          resets === 0,
          "大幅回退 " + resets + " 次 (阈值单次 >1s)",
          resets > 0 ? "重复进入把播放位置重置了:说明每次都在重建数据源" : undefined,
        ),
      );
      checks.push(
        check(
          "连点期间一直是同一个循环引擎",
          nonLoopSamples === 0 && readFailures === 0,
          "engine!=loop 样本 " + nonLoopSamples + ", 读失败 " + readFailures + " / 共 " + sampling.length,
        ),
      );
      checks.push(
        check(
          "连点期间没有长静音(输出帧连续)",
          ratio.ratio >= 0.85,
          "Δ=" + ratio.deltaFrames + "f / " + ratio.spanMs + "ms = " + round(ratio.ratio * 100, 1) + "%",
          ratio.ratio < 0.85 ? "重复进入期间有 >15% 的时间没写帧:疑似重建了第二路源" : undefined,
        ),
      );
      const audible = await measureMainFrames(ctx.api, 1400, sr);
      checks.push(audioCheck("连点结束后仍出声", audible, 0.85));
      checks.push(
        check(
          "位置仍在窗口内",
          win0 !== null && st.playback?.positionMs != null && within(Number(st.playback.positionMs), win0.aMs - 1000, win0.bMs + 1000),
          "pos=" + st.playback?.positionMs + " window=" + fmtWindow(win0) + "; 越界样本 " + outside,
        ),
      );
      checks.push(check("队列没有重复堆积", queue0 === queueEnd, "queueSize " + queue0 + " -> " + queueEnd));
      checks.push(check("没有降级", degDelta === 0, "degrade+" + degDelta));
      checks.push(check("不崩", st.playback?.connected === true, "connected=" + st.playback?.connected));

      return {
        checks,
        summary: "5x enter; 窗口稳定=" + (windows.size === 1) + "; 位置回退 " + resets + " 次; 出声 " + round(ratio.ratio * 100, 0) + "%",
      };
    } finally {
      await ctx.api.post("/loop/exit").catch(() => null);
      await ctx.resetForMain().catch(() => null);
    }
  },
};

// ---------------------------------------------------------------------------
// 场景 110：循环中把进度条拖到 A 之前 / B 之后，再拖回 [A,B)
//
// 想抓：区外试听后"拖回区间内"没有重新装上环回 —— 表现是回来位置对了但不再折返（跑到 B 就停/继续
// 往前放），或者回来时静音。要求：区外自由试听，回到区间后能在窗口内正确环回。
// ---------------------------------------------------------------------------

const s110: Scenario = {
  id: 110,
  key: "seek-out-then-back-loop",
  name: "循环中拖到区外再拖回区间",
  tag: "seek",
  exclusive: true,
  timeoutMs: 200000,
  async run(ctx): Promise<ScenarioRunResult> {
    const checks: Check[] = [];
    const song = ctx.songs.short;
    const sr = ctx.sampleRateFor(song.id);
    await ctx.resetForMain();
    try {
      await ctx.ensureLoopPage(song.id);
      const labels = ctx.uiLabels();
      if (!labels.includes("仅循环段")) throw new SkipError("结果页缺少「仅循环段」按钮");
      await ctx.uiTap("仅循环段");
      const ready = await waitFor(
        async () => (await ctx.api.preview()).snapshot,
        (s) => s && s.mode === "LOOP_ONLY" && s.isPlaying === true,
        12000,
        100,
      );
      if (!ready.ok || !ready.value) throw new SkipError("结果页预览未起播");
      const snap0 = ready.value;
      const a = Number(snap0.aMs);
      const b = Number(snap0.bMs);
      const duration = Number(snap0.durationMs);
      const before = await measurePreviewFrames(ctx.api, 1000, sr);
      checks.push(audioCheck("前置: 循环段正在出声", before, 0.8));

      const targets = [
        { label: "A 之前", value: Math.max(500, a - 10000) },
        { label: "B 之后", value: Math.min(duration - 1000, b + 10000) },
      ];
      const details: string[] = [];
      for (const t of targets) {
        const r = await ctx.previewSeek(t.value);
        ctx.evidence.step("seek-outside-" + t.label, { target: t.value, a, b, ...r });
        checks.push(
          check(
            t.label + ": 进入自由试听且出声",
            r.outsideLoopAfter === true && r.framesDelta > 0,
            "outsideLoop=" + r.outsideLoopAfter + " framesΔ=" + r.framesDelta + " display=" + r.displayAfterMs,
            r.outsideLoopAfter !== true ? "区外 seek 没切到自由试听" : undefined,
          ),
        );
        details.push(t.label + ":" + (r.displayAfterMs ?? "?"));
      }
      const windowOk =
        ((await ctx.api.preview()).snapshot?.windowA_ms ?? -1) === a &&
        ((await ctx.api.preview()).snapshot?.windowB_ms ?? -1) === b;
      checks.push(
        check(
          "区外试听不改 A/B",
          windowOk,
          "window=" + (await ctx.api.preview()).snapshot?.windowA_ms + ".." + (await ctx.api.preview()).snapshot?.windowB_ms + " (原 " + a + ".." + b + ")",
        ),
      );

      // 拖回区间内：靠近 B 起播，便于在 6s 内观察环回。
      const backTarget = Math.max(a + 200, Math.min(b - 200, b - 1500));
      const back = await ctx.previewSeek(backTarget);
      ctx.evidence.step("seek-back", { target: backTarget, a, b, ...back });
      checks.push(
        check(
          "拖回区间内: 退出自由试听(outside=false)",
          back.outsideLoopAfter === false,
          "outsideLoop=" + back.outsideLoopAfter + " display=" + back.displayAfterMs,
          back.outsideLoopAfter === true ? "拖回区间后仍在自由试听:环回没重新装上" : undefined,
        ),
      );
      checks.push(
        check(
          "拖回区间内: 落点在窗口里(±1.2s)",
          back.displayAfterMs !== null && within(Number(back.displayAfterMs), a - 1200, b + 1200),
          "target=" + backTarget + " display=" + back.displayAfterMs + " window=" + a + ".." + b,
        ),
      );

      // 6s 采样：必须看到环回，且位置不越界、输出帧不断流。
      const samples: { atMs: number; frames: number; display: number; playing: boolean | null }[] = [];
      const t0 = Date.now();
      while (Date.now() - t0 < 6000) {
        const p = await ctx.api.preview();
        if (p.available && p.snapshot) {
          samples.push({
            atMs: Number(p.snapshot.atMs),
            frames: Number(p.snapshot.outputFrames ?? 0),
            display: Number(p.snapshot.displayMs),
            playing: p.snapshot.isPlaying === true,
          });
        }
        await sleep(100);
      }
      let wraps = 0;
      let outside = 0;
      let lastChangeAt = 0;
      let lastFrames: number | null = null;
      let maxGap = 0;
      for (let i = 0; i < samples.length; i++) {
        const s = samples[i];
        if (s.display < a - 1200 || s.display > b + 1200) outside += 1;
        if (i > 0 && s.display < samples[i - 1].display - 500) wraps += 1;
        if (lastChangeAt === 0) {
          lastChangeAt = s.atMs;
          lastFrames = s.frames;
        } else if (s.frames !== lastFrames) {
          lastChangeAt = s.atMs;
          lastFrames = s.frames;
        } else {
          maxGap = Math.max(maxGap, s.atMs - lastChangeAt);
        }
      }
      const spanMs = samples.length > 1 ? samples[samples.length - 1].atMs - samples[0].atMs : 0;
      const deltaFrames = samples.length > 1 ? samples[samples.length - 1].frames - samples[0].frames : 0;
      const ratio = spanMs > 0 ? deltaFrames / ((sr * spanMs) / 1000) : 0;
      ctx.evidence.step("back-loop-samples", {
        count: samples.length,
        wraps,
        outside,
        maxGapMs: maxGap,
        ratio: round(ratio, 3),
        spanMs,
        deltaFrames,
      });
      checks.push(
        check(
          "拖回后能正确环回(6s 内 ≥1 次)",
          wraps >= 1,
          "6s 内 display 回绕 " + wraps + " 次 (A=" + a + " B=" + b + ", 起点 " + backTarget + ")",
          wraps === 0 ? "拖回区间后只往前放、不折返:环回没重新装上" : undefined,
        ),
      );
      checks.push(check("环回后位置只落在 [A,B]", outside === 0, "越界样本 " + outside + "/" + samples.length));
      checks.push(
        check(
          "环回过程没有长停顿",
          maxGap <= 300,
          "最长帧停顿 " + maxGap + "ms (阈值 300ms)",
          maxGap > 300 ? "环回接缝处 sink 断流" : undefined,
        ),
      );
      checks.push(check("整体出声率", ratio >= 0.8, round(ratio * 100, 1) + "%"));
      const finalSnap = (await ctx.api.preview()).snapshot;
      checks.push(check("最后仍在播放", finalSnap?.isPlaying === true, "isPlaying=" + finalSnap?.isPlaying));
      checks.push(
        check(
          "A/B 未被试听改动",
          String(finalSnap?.windowA_ms) === String(a) && String(finalSnap?.windowB_ms) === String(b),
          "window=" + finalSnap?.windowA_ms + ".." + finalSnap?.windowB_ms + " (原 " + a + ".." + b + ")",
        ),
      );

      return {
        checks,
        summary: "outside " + details.join(",") + "; 回区间 wraps=" + wraps + "; gap=" + maxGap + "ms; 出声 " + round(ratio * 100, 0) + "%",
      };
    } finally {
      await ctx.resetForMain().catch(() => null);
    }
  },
};

// ---------------------------------------------------------------------------
// 场景 111：0.1 秒间隔连续 seek 20 次
//
// 想抓：高频 seek 会不会把主线程/播放器命令通道堵死（最后一次 seek 不落地、播放器卡在 buffering、
// 或者 /state 读超时），以及结果页预览的 seek 桥在连发下会不会丢最后一次落点。
// ---------------------------------------------------------------------------

const s111: Scenario = {
  id: 111,
  key: "rapid-seek-x20",
  name: "0.1s 间隔连续 seek 20 次",
  tag: "seek",
  exclusive: true,
  timeoutMs: 220000,
  async run(ctx): Promise<ScenarioRunResult> {
    const checks: Check[] = [];
    await ctx.resetForMain();
    try {
      // ---- A. 主播放器：20 次 100ms 间隔 seek ----
      const song = ctx.songs.primary;
      const sr = ctx.sampleRateFor(song.id);
      const st0 = await playSong(ctx, song.id);
      const duration = Number(st0.playback.durationMs);
      const base = 3000;
      const step = Math.max(500, Math.floor((Math.max(duration, 20000) - 8000) / 21));
      const targets = Array.from({ length: 20 }, (_, i) => Math.min(Math.max(500, duration - 2000), base + i * step));
      const t0 = Date.now();
      const results = await Promise.all(
        targets.map((t, i) =>
          (async () => {
            await sleep(i * 100);
            return ctx.api.post("/player/seek", { positionMs: t }, 12000);
          })(),
        ),
      );
      const burstMs = Date.now() - t0;
      const statuses = results.map((r) => r.status);
      const lastTarget = targets[targets.length - 1];
      ctx.evidence.step("main-rapid-seek", {
        burstMs,
        statuses,
        targets,
        lastTarget,
      });
      checks.push(
        check(
          "主播放器 20 次 seek 全部被接受",
          statuses.every((s) => s === 200),
          "status=" + statuses.join(",") + "; 下发 " + burstMs + "ms",
        ),
      );
      const landed = await waitFor(
        async () => ctx.api.state(),
        (s) => {
          const pos = Number(s.playback.positionMs);
          return pos >= lastTarget - 800 && pos <= lastTarget + 4000;
        },
        5000,
        100,
      );
      const stAfter = landed.value ?? (await ctx.api.state());
      const posAfter = Number(stAfter.playback.positionMs);
      const readT0 = Date.now();
      await ctx.api.state();
      const stateLatency = Date.now() - readT0;
      ctx.evidence.step("main-rapid-seek-after", {
        landed: landed.ok,
        elapsedMs: landed.elapsedMs,
        positionMs: posAfter,
        lastTarget,
        playing: stAfter.playback?.playing,
        buffering: stAfter.playback?.buffering,
        stateLatencyMs: stateLatency,
      });
      checks.push(
        check(
          "最终位置=最后一次目标(±800ms,允许播放推进)",
          landed.ok,
          "last=" + lastTarget + " pos=" + posAfter + " (落定 " + landed.elapsedMs + "ms)",
          landed.ok ? undefined : "20 次连发后最后一次 seek 没生效:命令通道被堵",
        ),
      );
      checks.push(check("最终仍在播放", stAfter.playback?.playing === true, "playing=" + stAfter.playback?.playing + " buffering=" + stAfter.playback?.buffering));
      checks.push(check("落定后 /state 读取未卡死(≤800ms)", stateLatency <= 800, "读取耗时 " + stateLatency + "ms"));
      checks.push(
        check(
          "位置没有跑飞",
          posAfter >= 0 && posAfter <= duration + 2000,
          "pos=" + posAfter + " duration=" + duration,
        ),
      );
      const audible = await measureMainFrames(ctx.api, 1600, sr);
      checks.push(audioCheck("连发 seek 后真出声", audible, 0.85, "高频 seek 后 sink 没在写帧"));

      // ---- B. 结果页预览：同样 100ms 间隔连发（只判落定与出声，不判单次延迟） ----
      await ctx.resetForMain();
      await ctx.ensureLoopPage(song.id);
      const labels = ctx.uiLabels();
      if (!labels.includes("仅循环段")) throw new SkipError("结果页缺少「仅循环段」按钮");
      await ctx.uiTap("仅循环段");
      const ready = await waitFor(
        async () => (await ctx.api.preview()).snapshot,
        (s) => s && s.mode === "LOOP_ONLY" && s.isPlaying === true,
        12000,
        100,
      );
      if (!ready.ok || !ready.value) throw new SkipError("结果页预览未起播");
      const snap0 = ready.value;
      const a = Number(snap0.aMs);
      const b = Number(snap0.bMs);
      const span = Math.max(1000, b - a - 400);
      const pTargets = Array.from({ length: 20 }, (_, i) => Math.round(a + 200 + (span * i) / 20));
      const pLast = pTargets[pTargets.length - 1];
      const t1 = Date.now();
      const pResults = await Promise.all(
        pTargets.map((t, i) =>
          (async () => {
            await sleep(i * 100);
            return ctx.api.post("/debug/preview-seek", { positionMs: t }, 12000);
          })(),
        ),
      );
      const pBurstMs = Date.now() - t1;
      const pStatuses = pResults.map((r) => r.status);
      await sleep(1500);
      const snapEnd = (await ctx.api.preview()).snapshot;
      const displayEnd = Number(snapEnd?.displayMs ?? -1);
      const nearLast = Math.abs(displayEnd - pLast) <= 2000;
      const wrappedNearA = displayEnd >= a - 500 && displayEnd <= a + 3000 && pLast >= b - 3000;
      ctx.evidence.step("preview-rapid-seek", {
        burstMs: pBurstMs,
        statuses: pStatuses,
        targets: pTargets,
        lastTarget: pLast,
        displayEnd,
        isPlaying: snapEnd?.isPlaying,
        window: { a, b },
        seekRequests: snapEnd?.seekRequests,
      });
      const p5xx = pStatuses.filter((s) => s >= 500 && s !== 503).length;
      checks.push(
        check(
          "预览 20 次连发 seek 无 5xx(不含 503 起播中)",
          p5xx === 0,
          "status=" + pStatuses.join(",") + "; 下发 " + pBurstMs + "ms",
        ),
      );
      checks.push(
        check(
          "预览最终落点=最后一次目标(或它环回后的位置)",
          snapEnd != null && (nearLast || wrappedNearA),
          "last=" + pLast + " display=" + displayEnd + " window=" + a + ".." + b,
          snapEnd != null && !nearLast && !wrappedNearA ? "预览被连发 seek 甩到了别的位置" : undefined,
        ),
      );
      checks.push(check("预览最终仍在播放", snapEnd?.isPlaying === true, "isPlaying=" + snapEnd?.isPlaying));
      const pvAudible = await measurePreviewFrames(ctx.api, 1400, sr);
      checks.push(audioCheck("预览连发 seek 后真出声", pvAudible, 0.7));
      checks.push(
        check(
          "连发 seek 不改 A/B",
          String(snapEnd?.windowA_ms) === String(a) && String(snapEnd?.windowB_ms) === String(b),
          "window=" + snapEnd?.windowA_ms + ".." + snapEnd?.windowB_ms + " (原 " + a + ".." + b + ")",
        ),
      );

      return {
        checks,
        summary: "main 20x/" + burstMs + "ms 落定 pos=" + posAfter + "/" + lastTarget +
          "; preview 20x/" + pBurstMs + "ms display=" + displayEnd + "/" + pLast,
      };
    } finally {
      await ctx.resetForMain().catch(() => null);
    }
  },
};

// ---------------------------------------------------------------------------
// 场景 112：结果页预览与主播放器同时操作同一首歌
//
// 想抓：两条播放链路互相踩状态 —— 预览被主播放器的播放/暂停/seek 打断后卡在"显示在播但没声"，
// 或者主播放器的位置被预览的 seek 拖着走，或者焦点切换后预览再也起不来。
// 判据：同一时刻最多一路出声；没出声的那一路必须是明确的暂停态（不能是假播放）；
//      主播放器落点=自己的目标；窗口 A/B 不被另一路改写。
// ---------------------------------------------------------------------------

const s112: Scenario = {
  id: 112,
  key: "preview-and-main-concurrent",
  name: "预览页与主播放器同时操作同一首歌",
  tag: "交互",
  exclusive: true,
  timeoutMs: 260000,
  async run(ctx): Promise<ScenarioRunResult> {
    const checks: Check[] = [];
    const pending: string[] = [];
    const song = ctx.songs.primary;
    const sr = ctx.sampleRateFor(song.id);
    await ctx.resetForMain();
    try {
      await ctx.ensureLoopPage(song.id);
      const labels = ctx.uiLabels();
      for (const l of ["仅循环段", "自由"]) {
        if (!labels.includes(l)) throw new SkipError("结果页缺少「" + l + "」按钮");
      }
      await ctx.uiTap("仅循环段");
      const ready = await waitFor(
        async () => (await ctx.api.preview()).snapshot,
        (s) => s && s.mode === "LOOP_ONLY" && s.isPlaying === true,
        12000,
        100,
      );
      if (!ready.ok || !ready.value) throw new SkipError("结果页预览未起播");
      const snap0 = ready.value;
      const a = Number(snap0.aMs);
      const b = Number(snap0.bMs);
      const duration = Number(snap0.durationMs);
      const before = await measurePreviewFrames(ctx.api, 1200, sr);
      checks.push(audioCheck("并发前预览有声", before, 0.8));
      const mainBefore = await ctx.api.state();
      ctx.evidence.step("concurrent-baseline", {
        window: { a, b },
        previewIsPlaying: snap0.isPlaying,
        mainSongId: mainBefore.playback?.songId,
        mainPlaying: mainBefore.playback?.playing,
        mainEngine: mainBefore.playback?.engine,
      });

      // 主播放器的目标点故意选在预览窗口之外，便于区分"谁的位置被谁改写"。
      const mainTarget = Math.max(1500, Math.min(duration - 5000, a > 25000 ? a - 20000 : Math.max(1500, Math.floor(duration * 0.15))));
      const previewTarget = Math.max(a + 200, Math.min(b - 200, Math.round((a + b) / 2)));

      const ops: { at: number; what: string; status: number; detail?: any }[] = [];
      const rec = async (what: string, p: Promise<{ status: number; json: any }>, detail?: any) => {
        const r = await p;
        ops.push({ at: Date.now(), what, status: r.status, detail });
        return r;
      };
      await rec("main.play", ctx.api.post("/player/play", { songId: song.id, repeatOne: false }));
      await sleep(250);
      await rec("main.seek", ctx.api.post("/player/seek", { positionMs: mainTarget }), { mainTarget });
      await sleep(600);
      const mainLanded = await ctx.api.state();
      const mainPos = Number(mainLanded.playback.positionMs);
      await rec("preview.seek", ctx.api.post("/debug/preview-seek", { positionMs: previewTarget }, 12000), { previewTarget });
      await rec("main.play-pause", ctx.api.post("/player/play-pause"));
      await sleep(250);
      await rec("preview.seek2", ctx.api.post("/debug/preview-seek", { positionMs: Math.max(a + 200, previewTarget - 800) }, 12000), {
        target: Math.max(a + 200, previewTarget - 800),
      });
      await sleep(1200);
      ctx.evidence.step("concurrent-ops", { ops, mainTarget, previewTarget, mainPosAfterSeek: mainPos });

      checks.push(
        check(
          "并发操作全部被接受",
          ops.every((o) => o.status === 200),
          ops.map((o) => o.what + "=" + o.status).join(", "),
        ),
      );
      checks.push(
        check(
          "主播放器落点=自己的 seek 目标(±1.5s)",
          within(mainPos, mainTarget - 1500, mainTarget + 4000),
          "target=" + mainTarget + " pos=" + mainPos,
          within(mainPos, mainTarget - 1500, mainTarget + 4000) ? undefined : "主播放器位置被预览页的 seek 拖走了",
        ),
      );

      // 两路的出声情况：同一时刻最多一路出声；没声的那路必须是明确暂停（不能假播放）。
      const pvFrames = await measurePreviewFrames(ctx.api, 1400, sr);
      const mnFrames = await measureMainFrames(ctx.api, 1400, sr);
      const pvSnap = (await ctx.api.preview()).snapshot;
      const mnSt = await ctx.api.state();
      const pvPlaying = pvSnap?.isPlaying === true;
      const mnPlaying = mnSt.playback?.playing === true;
      const pvRatio = pvFrames.available ? pvFrames.ratio : 0;
      const mnRatio = mnFrames.available ? mnFrames.ratio : 0;
      ctx.evidence.step("concurrent-audible", {
        preview: { playing: pvPlaying, ratio: round(pvRatio, 3), deltaFrames: pvFrames.deltaFrames, available: pvFrames.available, mode: pvSnap?.mode },
        main: { playing: mnPlaying, ratio: round(mnRatio, 3), deltaFrames: mnFrames.deltaFrames, available: mnFrames.available },
      });
      checks.push(
        check(
          "没有'假装在播但没声'(预览)",
          !(pvPlaying && pvRatio < 0.2),
          "isPlaying=" + pvPlaying + " 出声率=" + round(pvRatio * 100, 1) + "%",
          pvPlaying && pvRatio < 0.2 ? "预览显示在播但输出帧不涨:焦点切换后卡成假播放" : undefined,
        ),
      );
      checks.push(
        check(
          "没有'假装在播但没声'(主播放器)",
          !(mnPlaying && mnFrames.available && mnRatio < 0.2),
          "playing=" + mnPlaying + " 出声率=" + round(mnRatio * 100, 1) + "%" + (mnFrames.available ? "" : " (探针不可用)"),
        ),
      );
      const bothLoud = pvRatio >= 0.6 && mnRatio >= 0.6;
      if (bothLoud) {
        checks.push(
          check(
            "同一时刻最多一路出声",
            false,
            "预览 " + round(pvRatio * 100, 0) + "% + 主播放器 " + round(mnRatio * 100, 0) + "% 同时出声",
            "两路同时出声:音频焦点没仲裁,用户会听到两首歌叠在一起",
          ),
        );
      } else {
        checks.push(
          check(
            "同一时刻最多一路出声",
            true,
            "预览 " + round(pvRatio * 100, 0) + "% / 主播放器 " + round(mnRatio * 100, 0) + "%",
          ),
        );
      }
      checks.push(
        check(
          "两路状态各自自洽",
          !(mnSt.playback?.playing === true && mnSt.playback?.playWhenReady === false) &&
            String(pvSnap?.mode ?? "").length > 0,
          "main playing/playWhenReady=" + mnSt.playback?.playing + "/" + mnSt.playback?.playWhenReady + "; preview mode=" + pvSnap?.mode,
        ),
      );
      checks.push(
        check(
          "预览模式未被主播放器操作改写",
          pvSnap?.mode === "LOOP_ONLY",
          "mode=" + pvSnap?.mode + " (期望 LOOP_ONLY)",
        ),
      );
      checks.push(
        check(
          "A/B 未被并发操作改写",
          String(pvSnap?.windowA_ms) === String(a) && String(pvSnap?.windowB_ms) === String(b),
          "window=" + pvSnap?.windowA_ms + ".." + pvSnap?.windowB_ms + " (原 " + a + ".." + b + ")",
        ),
      );
      const persisted = await ctx.api.song(song.id);
      checks.push(
        check(
          "落库 A/B 未被并发操作改写",
          Math.abs(Number(persisted.loopStartMs) - a) <= 2 && Math.abs(Number(persisted.loopEndMs) - b) <= 2,
          "落库 A/B=" + persisted.loopStartMs + "/" + persisted.loopEndMs + "ms (页面 " + a + "/" + b + "ms)",
        ),
      );

      // 收尾：把主播放器停下，再按用户路径重新起播预览，验证预览没有被踩死。
      const paused = await ensurePaused(ctx);
      checks.push(check("主播放器可正常暂停", paused.ok, "ready=" + paused.ok));
      await ctx.uiTap("自由");
      await sleep(900);
      await ctx.uiTap("仅循环段");
      const revived = await waitFor(
        async () => (await ctx.api.preview()).snapshot,
        (s) => s && s.mode === "LOOP_ONLY" && s.isPlaying === true,
        12000,
        100,
      );
      if (revived.ok) {
        const revivedFrames = await measurePreviewFrames(ctx.api, 1500, sr);
        checks.push(audioCheck("主播放器停后预览能重新出声", revivedFrames, 0.75, "焦点冲突后预览起不来了"));
      } else {
        checks.push(
          check(
            "主播放器停后预览能重新出声",
            false,
            "12s 内预览没回到 LOOP_ONLY+isPlaying",
            "焦点冲突后预览卡死:需要退出页面重进才恢复",
          ),
        );
        pending.push("并发操作后预览需要切模式才能恢复:需要产品裁决焦点/暂停策略");
      }

      return {
        checks,
        pendingDecision: pending,
        summary: "并发: 预览 " + round(pvRatio * 100, 0) + "% / 主 " + round(mnRatio * 100, 0) + "%; 主落点 " + mainPos + "/" + mainTarget +
          "; 两路未同时出声=" + (!bothLoud),
      };
    } finally {
      await ctx.api.post("/loop/exit").catch(() => null);
      await ctx.resetForMain().catch(() => null);
    }
  },
};

// ---------------------------------------------------------------------------
// 导出
// ---------------------------------------------------------------------------

export const INTERACTION_SCENARIOS: Scenario[] = [
  s101, s102, s103, s104, s105, s106, s107, s108, s109, s110, s111, s112,
];

export const INTERACTION_META: Record<number, InteractionScenarioMeta> = {
  101: {
    id: 101,
    goal: "连点播放/暂停后出现 playing 与 playWhenReady 矛盾的半死态，或状态机卡死不再翻转",
    expectedMs: 22000,
    usesUi: false,
    writesLoopData: false,
    deviceInteraction: "shared",
  },
  102: {
    id: 102,
    goal: "进/退循环连点后留下 engine=loop 但窗口为空的半死态，或两处 engine 口径长期不一致",
    expectedMs: 26000,
    usesUi: false,
    writesLoopData: false,
    deviceInteraction: "shared",
  },
  103: {
    id: 103,
    goal: "A 被拖到 B 右边后留下空窗口（静音/死循环），或页面草稿被写回数据库",
    expectedMs: 60000,
    usesUi: true,
    writesLoopData: false,
    deviceInteraction: "exclusive",
  },
  104: {
    id: 104,
    goal: "A==B / 只差 1 个样本的零长度窗口被落库，此后循环空转",
    expectedMs: 45000,
    usesUi: false,
    writesLoopData: true,
    deviceInteraction: "exclusive",
  },
  105: {
    id: 105,
    goal: "0.5s 极短窗口环回抖动/静音/降级；接近整曲的长窗口被静默回退成普通播放",
    expectedMs: 90000,
    usesUi: false,
    writesLoopData: true,
    deviceInteraction: "exclusive",
  },
  106: {
    id: 106,
    goal: "循环中切歌后旧意图/旧窗口还在（新歌被套旧 A/B，或切回旧歌循环自己复活）",
    expectedMs: 50000,
    usesUi: false,
    writesLoopData: false,
    deviceInteraction: "shared",
  },
  107: {
    id: 107,
    goal: "分析期间出现半死循环状态；分析结束后窗口丢失或未确认的候选被自动套用",
    expectedMs: 180000,
    usesUi: false,
    writesLoopData: false,
    needsAnalysis: true,
    deviceInteraction: "exclusive",
  },
  108: {
    id: 108,
    goal: "暂停中改 A/B 后恢复播放没有把播放头拉回新窗口（假播放/窗口外静音）",
    expectedMs: 60000,
    usesUi: false,
    writesLoopData: true,
    deviceInteraction: "exclusive",
  },
  109: {
    id: 109,
    goal: "重复进入同一首歌的循环时重建数据源/压层队列（位置被打回 A、过程长静音）",
    expectedMs: 45000,
    usesUi: false,
    writesLoopData: false,
    deviceInteraction: "shared",
  },
  110: {
    id: 110,
    goal: "区外试听后拖回 [A,B) 没有重新装上环回（只往前放不折返，或回来时静音）",
    expectedMs: 60000,
    usesUi: true,
    writesLoopData: false,
    deviceInteraction: "exclusive",
  },
  111: {
    id: 111,
    goal: "0.1s 连发 20 次 seek 后最后一次没落地。/state 卡死；预览 seek 桥在连发下丢落点",
    expectedMs: 80000,
    usesUi: true,
    writesLoopData: false,
    deviceInteraction: "exclusive",
  },
  112: {
    id: 112,
    goal: "预览页与主播放器互相踩状态（假播放、位置被拖走、焦点切换后预览起不来）",
    expectedMs: 90000,
    usesUi: true,
    writesLoopData: false,
    deviceInteraction: "exclusive",
  },
};
