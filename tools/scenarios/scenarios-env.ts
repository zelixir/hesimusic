#!/usr/bin/env bun
/**
 * tools/scenarios/scenarios-env.ts —— 「外部环境与系统生命周期」场景族(分支 A)。
 *
 * 这份文件是**独立于** scenarios.ts 的补充矩阵:元素结构与 scenarios.ts 的 Scenario 完全一致,
 * 只导出数组,不改任何既有文件(文件域纪律:scenarios.ts / run-scenarios.ts / harness.ts / 文档由 lead 合并)。
 *
 * 合并方式(lead,二选一):
 *   1) scenarios.ts 里:import { ENV_SCENARIOS } from "./scenarios-env";
 *      export const SCENARIOS: Scenario[] = [...s1, ..., s19, ...ENV_SCENARIOS];
 *   2) 或在 runner 里 const all = [...SCENARIOS, ...ENV_SCENARIOS](注意 --only 过滤要照样生效)。
 *   **不要**把本文件整段贴进 scenarios.ts:本文件用相对导入 + 惰性 import("./scenarios") 取 SkipError,
 *   贴进去会破坏模块边界。
 *
 * 另外两件 runner 侧的小事(不改会有一点损失,但不影响正确性):
 *   - UI 场景 id 45 用到结果页点击/预览,跑 --skip-ui 时请把 45 加进 runner 的 UI_SCENARIOS,
 *     否则 --skip-ui 下它会去点 UI(只是白跑,不会误判)。
 *   - id 50(重启恢复)必须单独跑:它会让 pid 变化,runner 的「pid 一致性」判据必然 FAIL,
 *     且默认 SKIP(要显式 HESI_ENV_REBOOT=1)。
 *
 * 判据只用常识:能不能出声(输出帧/播放头推进)、位置对不对(是否还在 [A,B))、卡不卡、
 * 崩不崩(pid/crash/ANR/OOM)、A/B 与循环窗口有没有被改。**不引入任何新指标**,
 * 也不拿"位置推进"当出声证据(帧探针不可用时明确标注探针口径)。
 *
 * SOURCE 路线:环境场景都要求主播放器在 SOURCE(writtenFrames 才更新),否则整条 SKIP ——
 * 即使 runner 用了 --allow-non-source 也一样;宁可不跑,也不拿"位置在推进"当出声。
 *
 * 能力探测纪律:每个环境注入手段(媒体键、耳机广播、第三方播放器抢焦点、旋转、分屏、Doze、trim)
 * 都先探测再执行;探测不到就 SkipError(SKIP)并写清原因,**绝不伪造 PASS**。
 *
 * 设备副作用纪律:凡临时改动的设备状态(旋转、低电量模式、电池覆盖、媒体音量、Doze 状态、
 * 第三方播放器)一律在 finally 里恢复,并把恢复结果写成证据 + 一条 check。
 */

import {
  check,
  measureMainFrames,
  measurePreviewFrames,
  pendingCheck,
  round,
  selfActivity,
  sleep,
  waitFramesSettled,
  waitFor,
  within,
} from "./harness";
import type { Check, FrameMeasurement, SongInfo, State } from "./harness";
// 只 type-import:运行时不需要 scenarios.ts;SkipError 用惰性 import 取(避免循环初始化问题)。
import type { Scenario, ScenarioCtx } from "./scenarios";

// ---------------------------------------------------------------------------
// 本地小工具(不修改 scenarios.ts,所以这里自带一份最小实现,口径与 scenarios.ts 一致)
// ---------------------------------------------------------------------------

interface Win {
  songId: number;
  aMs: number;
  bMs: number;
}

function loopWindowOf(state: State): Win | null {
  const w: any = state?.loop?.window;
  if (!w) return null;
  return {
    songId: Number(w.songId),
    aMs: Math.round(Number(w.loopStartUs) / 1000),
    bMs: Math.round(Number(w.loopEndUs) / 1000),
  };
}

function seamCount(state: State): number {
  return Number(state?.loop?.seamCount ?? 0);
}

async function skipEnv(reason: string): Promise<never> {
  const mod: any = await import("./scenarios");
  throw new mod.SkipError(reason);
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

function claimActiveOf(state: State | null): boolean {
  if (!state) return false;
  const v = (state as any).loop?.claimActive;
  return v === true || (v === undefined && state.playback?.engine === "loop");
}

function mainWindowCheck(name: string, state: State, songId: number, tolMs = 1000): Check {
  const w = loopWindowOf(state);
  if (!claimActiveOf(state)) {
    return check(name, true, "claimActive=false: 源未接管/已回退,不要求 position ∈ [A,B)");
  }
  const phase = (state as any)?.loop?.phase;
  if (phase && phase !== "loop") {
    return check(name, true, "phase=" + phase + ": 前奏/尾奏阶段位置可在 [A,B) 外(00028 整曲时间轴)");
  }
  if (!w) return check(name, false, "loop.window 为空(循环窗口丢失)");
  const pos = Number(state.playback?.positionMs);
  const inWindow = w.songId === songId && pos >= w.aMs - tolMs && pos <= w.bMs + tolMs;
  return check(name, inWindow, "window songId=" + w.songId + " A=" + w.aMs + " B=" + w.bMs + " pos=" + pos);
}

/** 环境事件前后循环窗口(A/B/songId)是否被悄悄改掉。 */
function windowIntactCheck(name: string, w0: Win, state: State): Check {
  const w = loopWindowOf(state);
  if (!w) return check(name, false, "loop.window 为空(窗口丢失)");
  const same = w.songId === w0.songId && Math.abs(w.aMs - w0.aMs) <= 1 && Math.abs(w.bMs - w0.bMs) <= 1;
  return check(
    name,
    same,
    "window=" + w.songId + "#" + w.aMs + ".." + w.bMs + " (原 " + w0.songId + "#" + w0.aMs + ".." + w0.bMs + ")",
    same ? undefined : "环境事件把循环窗口改了/换了歌:用户标记的 A/B 不应被系统事件改写",
  );
}

/** 主播放器必须走 SOURCE 路线,输出帧探针才可信;否则明确 SKIP 而不是用位置推进冒充出声。 */
async function requireSourceRoute(ctx: ScenarioCtx): Promise<void> {
  const probe = await waitFor(
    async () => (await ctx.api.state()).invariantProbe,
    (p) => p?.routeSource === true,
    4000,
    120,
  );
  if (!probe.ok) {
    return await skipEnv(
      "主播放器不在 SOURCE 路线(invariantProbe.routeSource=" + String(probe.value?.routeSource) +
        "),输出帧探针不可用,出声判据无法判定;按 docs/testing-scenarios.md §1.4 切 SOURCE 后重跑" +
        "(环境场景在 M3 上直接 SKIP,不拿位置推进冒充出声)",
    );
  }
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
  if (!wait.ok || !wait.value) throw new Error("play 未在 8s 内 ready/playing (songId=" + songId + ")");
  return wait.value;
}

async function enterLoop(ctx: ScenarioCtx, songId: number, play: boolean, restart: boolean) {
  return await ctx.api.post("/loop/enter", { songId, play, restart });
}

async function waitLoopInstalled(ctx: ScenarioCtx, songId: number, timeoutMs = 8000) {
  const w = await waitFor(
    async () => ctx.api.state(),
    (s) => loopWindowOf(s)?.songId === songId && s.playback?.engine === "loop",
    timeoutMs,
    100,
  );
  return { ok: w.ok, elapsedMs: w.elapsedMs, state: w.value };
}

async function ensurePlaying(ctx: ScenarioCtx, timeoutMs = 6000) {
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

/** 起播 -> seek 到 A+3s -> 进循环;返回窗口与状态,供各场景当基线。 */
async function enterLoopPlaying(
  ctx: ScenarioCtx,
  song: SongInfo,
): Promise<{ a: number; b: number; state: State }> {
  await playSong(ctx, song.id);
  const info = await ctx.api.song(song.id);
  const a = Number(info.loopStartMs);
  const b = Number(info.loopEndMs);
  const target = a + 3000;
  await ctx.api.post("/player/seek", { positionMs: target });
  await waitFor(async () => ctx.api.state(), (s) => Math.abs(Number(s.playback.positionMs) - target) <= 800, 3000, 60);
  const enter = await enterLoop(ctx, song.id, true, false);
  if (enter.status !== 200) throw new Error("/loop/enter -> " + enter.status + " " + enter.text.slice(0, 200));
  const installed = await waitLoopInstalled(ctx, song.id, 8000);
  if (!installed.ok || !installed.state) {
    throw new Error("进循环失败(engine=" + String(installed.state?.playback?.engine) + ")");
  }
  return { a, b, state: installed.state };
}

// ---------------------------------------------------------------------------
// 设备侧注入小工具(每个都先探测能力,失败返回结构化结果,由场景决定 SKIP 还是 PENDING)
// ---------------------------------------------------------------------------

type TransportKey = "play" | "pause" | "next" | "previous";
const TRANSPORT_KEYCODE: Record<TransportKey, number> = { play: 126, pause: 127, next: 87, previous: 88 };

/**
 * 派发媒体传输控制。优先 cmd media_session dispatch(与通知栏按钮同一条 MediaSession 回调),
 * 不行再退到媒体键 input keyevent。两条都试过仍达不到预期状态 → ok=false,由场景 SKIP。
 */
async function dispatchTransport(
  ctx: ScenarioCtx,
  key: TransportKey,
  predicate: (s: State) => boolean,
  timeoutMs = 3500,
): Promise<{ ok: boolean; via: string; detail: string }> {
  const attempts: { via: string; cmd: string }[] = [
    { via: "cmd media_session dispatch " + key, cmd: "cmd media_session dispatch " + key },
    { via: "input keyevent " + TRANSPORT_KEYCODE[key], cmd: "input keyevent " + TRANSPORT_KEYCODE[key] },
  ];
  const details: string[] = [];
  for (const attempt of attempts) {
    selfActivity.mark();
    const r = await ctx.adb.run(["shell", attempt.cmd], 15000);
    const out = (r.out + "\n" + r.err).trim();
    details.push(attempt.via + " -> code=" + r.code + " out=" + out.slice(0, 120));
    if (/unknown command|Unknown command|Exception|not found|Usage:/i.test(out)) continue;
    const hit = await waitFor(async () => ctx.api.state(), predicate, timeoutMs, 100);
    if (hit.ok) return { ok: true, via: attempt.via, detail: "elapsed=" + hit.elapsedMs + "ms" };
  }
  return { ok: false, via: "none", detail: details.join(" | ").slice(0, 400) };
}

const BROADCAST_DENIED =
  /SecurityException|Permission Denial|PERMISSION_DENIED|not allowed|Broadcast not sent|Failure|Exception|Error/i;

async function sendShellBroadcast(
  ctx: ScenarioCtx,
  args: string[],
): Promise<{ ok: boolean; out: string }> {
  selfActivity.mark();
  const r = await ctx.adb.run(["shell", "am broadcast " + args.join(" ")], 20000);
  const out = (r.out + "\n" + r.err).trim();
  const denied = BROADCAST_DENIED.test(out) && !/Broadcast completed/i.test(out);
  return { ok: r.code === 0 && !denied, out: out.slice(0, 400) };
}

async function wakefulness(ctx: ScenarioCtx): Promise<string> {
  const out = await ctx.adb.shell("dumpsys power", 20000);
  return out.match(/mWakefulness=(\w+)/)?.[1] ?? "unknown";
}

async function keyguardShowing(ctx: ScenarioCtx): Promise<boolean | null> {
  const out = await ctx.adb.shell("dumpsys window", 30000);
  const m = out.match(
    /(?:mShowingLockscreen|mKeyguardShowing|isKeyguardShowing|mDreamingLockscreen)[=:]\s*(true|false)/,
  );
  if (m) return m[1] === "true";
  const focus = out.match(/mCurrentFocus=Window\{[^}]*?\s([A-Za-z0-9_.$]+)\//)?.[1] ?? "";
  if (/NotificationShade|StatusBar|Keyguard/i.test(focus)) return true;
  if (focus) return false;
  return null;
}

/** 亮屏 + dismiss keyguard(不改任何设置);返回是否真的回到了可用桌面。 */
async function ensureAwakeUnlocked(
  ctx: ScenarioCtx,
  pkg: string,
): Promise<{ ok: boolean; detail: string }> {
  if ((await wakefulness(ctx)) !== "Awake") {
    selfActivity.mark();
    await ctx.adb.shell("input keyevent KEYCODE_WAKEUP", 15000);
    await waitFor(() => wakefulness(ctx), (v) => v === "Awake", 5000, 200);
  }
  selfActivity.mark();
  await ctx.adb.shell("wm dismiss-keyguard", 15000);
  await sleep(600);
  const locked = await keyguardShowing(ctx);
  const fg = (await ctx.adb.foregroundActivity().catch(() => "")) ?? "";
  if (locked === false) return { ok: true, detail: "unlocked" };
  if (fg.startsWith(pkg)) return { ok: true, detail: "unlocked(fg=" + fg + ")" };
  return { ok: false, detail: "解锁未完成(keyguardShowing=" + String(locked) + ", fg=" + (fg || "?") + ")" };
}

/** 屏幕当前朝向(0..3);读不到返回 null(不猜)。 */
async function currentRotation(ctx: ScenarioCtx): Promise<number | null> {
  const sources = ["dumpsys input", "dumpsys window", "dumpsys display"];
  for (const cmd of sources) {
    const out = await ctx.adb.shell(cmd, 30000);
    const m =
      out.match(/SurfaceOrientation:\s*(\d)/) ??
      out.match(/mRotation=(\d)/) ??
      out.match(/rotation(?:=|\s+)(\d)\b/i);
    if (m) {
      const n = Number(m[1]);
      if (n >= 0 && n <= 3) return n;
    }
  }
  return null;
}

/** 用 wm / cmd window 锁定旋转(不写 settings);返回走了哪条通道。 */
async function setRotation(
  ctx: ScenarioCtx,
  rotation: number,
): Promise<{ ok: boolean; via: "wm" | "cmd-window" | "none"; detail: string }> {
  selfActivity.mark();
  const wm = await ctx.adb.run(["shell", "wm user-rotation lock " + rotation], 15000);
  const wmOut = (wm.out + "\n" + wm.err).trim();
  if (wm.code === 0 && !/unknown command|Unknown command|Exception|Error/i.test(wmOut)) {
    return { ok: true, via: "wm", detail: wmOut.slice(0, 160) };
  }
  const cw = await ctx.adb.run(["shell", "cmd window user-rotation lock " + rotation], 15000);
  const cwOut = (cw.out + "\n" + cw.err).trim();
  if (cw.code === 0 && !/unknown command|Unknown command|Exception|Error/i.test(cwOut)) {
    return { ok: true, via: "cmd-window", detail: cwOut.slice(0, 160) };
  }
  return { ok: false, via: "none", detail: (wmOut + " | " + cwOut).slice(0, 300) };
}

async function freeRotation(
  ctx: ScenarioCtx,
  via: "wm" | "cmd-window" | "none",
): Promise<{ ok: boolean; detail: string }> {
  selfActivity.mark();
  const cmd = via === "cmd-window" ? "cmd window user-rotation free" : "wm user-rotation free";
  const r = await ctx.adb.run(["shell", cmd], 15000);
  const out = (r.out + "\n" + r.err).trim();
  return {
    ok: r.code === 0 && !/Exception|Error|unknown command/i.test(out),
    detail: cmd + " -> " + out.slice(0, 160),
  };
}

/** 恢复旋转:原来是自动旋转 → free;原来是锁定 → 锁回原值。 */
async function restoreRotation(
  ctx: ScenarioCtx,
  via: "wm" | "cmd-window" | "none",
  origAccel: string,
  origUserRotation: string,
): Promise<{ ok: boolean; detail: string }> {
  if (origAccel !== "0" && origAccel !== "null") {
    const freed = await freeRotation(ctx, via);
    return { ok: freed.ok, detail: "auto-rotate 原值=" + origAccel + "; " + freed.detail };
  }
  const target = Number(origUserRotation);
  if (Number.isFinite(target) && target >= 0 && target <= 3) {
    const back = await setRotation(ctx, target);
    return { ok: back.ok, detail: "原锁定=" + origUserRotation + "; " + back.detail };
  }
  const freed = await freeRotation(ctx, via);
  return {
    ok: freed.ok,
    detail: "原 user_rotation 不可解析(" + origUserRotation + "); " + freed.detail,
  };
}

/** dumpsys window windows 里该包的窗口 frame(用于判断「窗口变小/分屏」)。 */
function windowFrameOf(
  dump: string,
  pkg: string,
): { l: number; t: number; r: number; b: number } | null {
  const blocks = dump.split(/\n\s*Window #/);
  for (const block of blocks) {
    if (!block.includes(pkg)) continue;
    const m = block.match(/mFrame=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]/);
    if (!m) continue;
    const l = Number(m[1]);
    const t = Number(m[2]);
    const r = Number(m[3]);
    const b = Number(m[4]);
    if (r - l > 0 && b - t > 0) return { l, t, r, b };
  }
  return null;
}

async function launcherComponent(ctx: ScenarioCtx, pkg: string): Promise<string | null> {
  const resolved = await ctx.adb.shell(
    "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER " + pkg,
    20000,
  );
  return resolved.match(/([A-Za-z0-9_.]+)\/([A-Za-z0-9_.$]+)/)?.[0] ?? null;
}

/** MediaStore 里第一条外部音频的 id(用来喂给第三方播放器)。 */
async function firstMediaStoreAudioId(ctx: ScenarioCtx): Promise<number | null> {
  const out = await ctx.adb.shell(
    "content query --uri content://media/external/audio/media --projection _id",
    25000,
  );
  const m = out.match(/_id=(\d+)/);
  return m ? Number(m[1]) : null;
}

async function mediaVolume(ctx: ScenarioCtx): Promise<number | null> {
  const out = await ctx.adb.shell("media volume --stream 3 --get", 15000);
  const m = out.match(/(?:volume is|volume:\s*)(\d+)/i);
  return m ? Number(m[1]) : null;
}

/** 预览页出声判据:SOURCE 用帧;其它路线明确改成「播放头推进代理」并写进口径。 */
async function previewAudioCheck(
  ctx: ScenarioCtx,
  snap: any,
  sampleRate: number,
  name: string,
): Promise<Check> {
  if (String(snap?.engine ?? "") === "SOURCE") {
    const m = await measurePreviewFrames(ctx.api, 1500, sampleRate);
    return check(
      name,
      m.available && m.ratio >= 0.8,
      "Δ=" + m.deltaFrames + "f/" + m.spanMs + "ms (" + round(m.ratio * 100, 0) + "%)",
      m.available ? undefined : "预览帧探针不可用:页面刚重建或 sink 未起",
    );
  }
  const p0 = Number(snap?.displayMs ?? 0);
  await sleep(1000);
  const p1 = (await ctx.api.preview()).snapshot;
  const adv = p1 ? Number(p1.displayMs) - p0 : 0;
  return check(
    name + "(播放头推进代理,engine=" + String(snap?.engine) + ")",
    adv >= 700,
    "display " + p0 + " -> " + (p1 ? p1.displayMs : "?") + " (" + adv + "ms/1s)",
    adv < 700 ? "非 SOURCE 路线上展示位置没有推进:可能没起播或被暂停" : undefined,
  );
}

// ---------------------------------------------------------------------------
// 场景 41：通知栏/媒体键控制(暂停/继续/上一首/下一首)在循环态下的行为
// 想抓:外部传输控制(通知栏按钮/耳机线控,最终都走 MediaSession 回调)在循环态下
//       把 loop 意图/窗口弄丢,或继续播放后跑到 [A,B) 外面、或者干脆不出声。
// ---------------------------------------------------------------------------

const sEnv41: Scenario = {
  id: 41,
  key: "env-notification-transport",
  name: "通知栏/媒体键控制(暂停/继续/上/下一首)",
  tag: "环境",
  timeoutMs: 150000,
  async run(ctx) {
    const checks: Check[] = [];
    const song = ctx.songs.primary;
    const sr = ctx.sampleRateFor(song.id);
    await ctx.resetForMain();
    await requireSourceRoute(ctx);
    const base = await enterLoopPlaying(ctx, song);
    const w0: Win = { songId: song.id, aMs: base.a, bMs: base.b };

    // 通知存在性只作证据:media3 MediaSessionService 会发默认媒体通知;用户关掉通知时
    // 媒体键仍走同一条 MediaSession 回调,所以下面用传输控制驱动判据,不依赖通知可见。
    const notifDump = await ctx.adb.shell("dumpsys notification --noredact", 30000);
    const notifLines = notifDump.split(/\r?\n/).filter((l) => l.includes(ctx.cfg.pkg)).length;
    ctx.evidence.step("baseline", {
      window: w0,
      engine: base.state.playback.engine,
      repeatMode: base.state.playback.repeatMode,
      notificationLines: notifLines,
    });

    const audio0 = await measureMainFrames(ctx.api, 1500, sr);
    checks.push(audioCheck("控制前有输出", audio0, 0.85));

    // 1) 暂停
    const paused = await dispatchTransport(
      ctx,
      "pause",
      (s) => s.playback?.playing === false && s.playback?.playWhenReady === false,
      3500,
    );
    if (!paused.ok) {
      return await skipEnv(
        "该 ROM 无法用 shell 注入媒体传输控制(cmd media_session dispatch / input keyevent 都无效): " +
          paused.detail,
      );
    }
    checks.push(check("暂停生效", paused.ok, "via=" + paused.via + " " + paused.detail));
    await waitFramesSettled(ctx.api, 3000);
    const silent = await measureMainFrames(ctx.api, 1200, sr);
    checks.push(
      check("暂停后静音", silent.deltaFrames === 0, "Δ=" + silent.deltaFrames + "f / " + silent.spanMs + "ms"),
    );
    const stPaused = await ctx.api.state();
    checks.push(mainWindowCheck("暂停时位置仍在 [A,B)", stPaused, song.id));
    checks.push(windowIntactCheck("暂停未改 A/B", w0, stPaused));

    // 2) 继续
    const resumed = await dispatchTransport(ctx, "play", (s) => s.playback?.playing === true, 5000);
    checks.push(check("继续生效", resumed.ok, "via=" + resumed.via + " " + resumed.detail));
    await sleep(500);
    const audioResume = await measureMainFrames(ctx.api, 1800, sr);
    checks.push(audioCheck("继续后有输出", audioResume, 0.85));
    const stResumed = await ctx.api.state();
    checks.push(
      check(
        "继续后仍在循环",
        stResumed.playback.engine === "loop" && loopWindowOf(stResumed)?.songId === song.id,
        "engine=" + stResumed.playback.engine + " window=" + JSON.stringify(loopWindowOf(stResumed)),
      ),
    );
    checks.push(mainWindowCheck("继续后位置仍在 [A,B)", stResumed, song.id));
    checks.push(windowIntactCheck("继续未改 A/B", w0, stResumed));

    // 3) 下一首(循环态:REPEAT_ONE 下 media3 可能重播当前曲,也可能切歌 —— 两种都允许,
    //    但不允许把播放头丢到循环窗口外还说是"正常")。
    const nextHit = await dispatchTransport(
      ctx,
      "next",
      (s) => Number(s.playback?.songId) !== song.id || Number(s.playback?.positionMs) < w0.aMs + 1500,
      6000,
    );
    checks.push(check("下一首生效", nextHit.ok, "via=" + nextHit.via + " " + nextHit.detail));
    await sleep(600);
    const stNext = await ctx.api.state();
    const nextSongId = Number(stNext.playback.songId);
    const nextSr = ctx.sampleRateFor(nextSongId);
    const audioNext = await measureMainFrames(ctx.api, 1800, nextSr);
    checks.push(audioCheck("下一首后仍有输出", audioNext, 0.85, "按了下一首但 sink 没有恢复输出"));
    ctx.evidence.step("next", {
      songId: nextSongId,
      engine: stNext.playback.engine,
      window: loopWindowOf(stNext),
      positionMs: stNext.playback.positionMs,
    });
    if (nextSongId !== song.id) {
      const wNext = loopWindowOf(stNext);
      checks.push(
        check(
          "切歌后旧循环窗口没有套到新歌",
          wNext === null || wNext.songId !== nextSongId,
          "window.songId=" + (wNext ? wNext.songId : "null") + " newSongId=" + nextSongId,
          wNext && wNext.songId === nextSongId ? "切歌后旧窗口套到新歌上" : undefined,
        ),
      );
    } else {
      checks.push(mainWindowCheck("下一首后位置仍在 [A,B)(未切歌)", stNext, song.id));
    }

    // 4) 上一首：Android/ExoPlayer 标准语义有两条分支
    //    position > maxSeekToPreviousPosition(默认 3s) -> 回到本曲开头(songId 不变,pos≈0)
    //    position <= 3s                                -> 切到上一首(songId 变化)
    await ctx.api.post("/player/seek", { positionMs: 6000 });
    await waitFor(async () => ctx.api.state(), (s) => Number(s.playback?.positionMs) >= 3000, 5000, 100);
    const beforePrevA = await ctx.api.state();
    const prevA = await dispatchTransport(
      ctx,
      "previous",
      (s) =>
        Number(s.playback?.songId) !== Number(beforePrevA.playback.songId) ||
        Number(s.playback?.positionMs) <= 2000,
      6000,
    );
    await sleep(500);
    const afterPrevA = await ctx.api.state();
    const aSongChanged = Number(afterPrevA.playback.songId) !== Number(beforePrevA.playback.songId);
    const aRewound = !aSongChanged && Number(afterPrevA.playback.positionMs) <= 2000;
    checks.push(
      check(
        "上一首(位置>3s): 切上一首 或 回到本曲开头",
        prevA.ok && (aSongChanged || aRewound) && afterPrevA.playback.playing === true,
        "pos " + beforePrevA.playback.positionMs + "->" + afterPrevA.playback.positionMs +
          "; song " + beforePrevA.playback.songId + "->" + afterPrevA.playback.songId +
          "; playing=" + afterPrevA.playback.playing + "; via=" + prevA.via,
        prevA.ok ? undefined : "previous 完全无效果：检查 MediaSession 回调/seekToPrevious",
      ),
    );
    ctx.evidence.step("prev-over-3s", {
      before: { songId: beforePrevA.playback.songId, positionMs: beforePrevA.playback.positionMs },
      after: { songId: afterPrevA.playback.songId, positionMs: afterPrevA.playback.positionMs },
      songChanged: aSongChanged,
      rewound: aRewound,
      via: prevA.via,
    });

    // 4b) position < 3s：退出循环后先制造"确实有上一首"的队列位置，再 seek 1.5s 测 previous。
    await ctx.api.post("/loop/exit");
    await waitFor(async () => ctx.api.state(), (s) => loopWindowOf(s) === null, 6000, 100);
    const beforeSetup = await ctx.api.state();
    await ctx.api.post("/player/repeat", { mode: "off" }).catch(() => null);
    await ctx.api.post("/player/next").catch(() => null);
    const movedNext = await waitFor(
      async () => ctx.api.state(),
      (s) => Number(s.playback?.songId) !== Number(beforeSetup.playback.songId),
      6000,
      100,
    );
    let afterPrevB: State | null = null;
    if (!movedNext.ok) {
      checks.push(
        pendingCheck(
          "上一首(位置<3s): 切到上一首",
          "队列当前没有下一首可先进入（无法构造'有上一首'的位置）",
          "queue-no-next",
          "用带前后曲目的列表/专辑队列重跑该分支",
        ),
      );
    } else {
      await ctx.api.post("/player/seek", { positionMs: 1500 });
      await waitFor(async () => ctx.api.state(), (s) => Number(s.playback?.positionMs) <= 3000, 4000, 100);
      const beforePrevB = await ctx.api.state();
      const prevB = await dispatchTransport(
        ctx,
        "previous",
        (s) => Number(s.playback?.songId) !== Number(beforePrevB.playback.songId),
        8000,
      );
      await sleep(600);
      afterPrevB = await ctx.api.state();
      checks.push(
        check(
          "上一首(位置<3s): 切到上一首",
          prevB.ok && Number(afterPrevB.playback.songId) !== Number(beforePrevB.playback.songId) &&
            afterPrevB.playback.playing === true,
          "pos " + beforePrevB.playback.positionMs + "; song " + beforePrevB.playback.songId +
            "->" + afterPrevB.playback.songId + "; playing=" + afterPrevB.playback.playing + "; via=" + prevB.via,
          prevB.ok ? undefined : "位置<3s 时 previous 没有切到上一首：检查队列/seekToPrevious 实现",
        ),
      );
      ctx.evidence.step("prev-under-3s", {
        before: { songId: beforePrevB.playback.songId, positionMs: beforePrevB.playback.positionMs },
        after: { songId: afterPrevB.playback.songId, positionMs: afterPrevB.playback.positionMs },
        via: prevB.via,
      });
    }
    const stPrev = afterPrevB ?? (await ctx.api.state());
    const prevSr = ctx.sampleRateFor(Number(stPrev.playback.songId));
    const audioPrev = await measureMainFrames(ctx.api, 1600, prevSr);
    checks.push(audioCheck("上一首后仍有输出", audioPrev, 0.85));
    const songInfoAfter = await ctx.api.song(song.id);
    checks.push(
      check(
        "传输控制没有改库里的 A/B",
        Math.abs(Number(songInfoAfter.loopStartMs) - w0.aMs) <= 2 &&
          Math.abs(Number(songInfoAfter.loopEndMs) - w0.bMs) <= 2,
        "库 A/B=" + songInfoAfter.loopStartMs + "/" + songInfoAfter.loopEndMs +
          " (原 " + w0.aMs + "/" + w0.bMs + ")",
      ),
    );
    const stEnd = await ctx.api.state();
    if (stEnd.playback.engine === "loop" && loopWindowOf(stEnd)?.songId === song.id) {
      checks.push(mainWindowCheck("上一首后位置仍在 [A,B)", stEnd, song.id));
    }

    return {
      checks,
      summary:
        "pause=" + paused.via + " resume=" + resumed.via + "; next=" + nextSongId +
        " prev=" + Number(stPrev.playback.songId) + "; audio " + round(audioResume.ratio * 100, 0) +
        "%/" + round(audioPrev.ratio * 100, 0) + "%",
      pendingDecision: [
        "循环态下「下一首」的期望行为(切歌 vs 重播当前曲)未定;本场景只判:不出错、A/B 不被改、" +
          "若仍在循环则播放头不得跑到窗口外。",
      ],
    };
  },
};

// ---------------------------------------------------------------------------
// 场景 42：耳机插拔(拔耳机 -> 按系统规则暂停 -> 插回不自动续播 -> 手动继续)
// 想抓:AUDIO_BECOMING_NOISY(拔耳机)没让循环暂停(继续外放)、暂停后位置或窗口被改、
//       插回耳机自动续播(不符合系统/用户预期)、手动继续后回不到 [A,B) 循环。
// 注入手段:HEADSET_PLUG 广播需要 BROADCAST_HEADSET_PLUG 签名权限,多数 ROM 拒绝;
//           退路是 AUDIO_BECOMING_NOISY(系统拔耳机时发的就是这条),两条都不通就 SKIP。
// ---------------------------------------------------------------------------

const sEnv42: Scenario = {
  id: 42,
  key: "env-headset-unplug",
  name: "耳机插拔(拔->暂停->插回不自动播->手动继续)",
  tag: "环境",
  timeoutMs: 120000,
  async run(ctx) {
    const checks: Check[] = [];
    const song = ctx.songs.primary;
    const sr = ctx.sampleRateFor(song.id);
    await ctx.resetForMain();
    await requireSourceRoute(ctx);
    const base = await enterLoopPlaying(ctx, song);
    const w0: Win = { songId: song.id, aMs: base.a, bMs: base.b };
    const audio0 = await measureMainFrames(ctx.api, 1500, sr);
    checks.push(audioCheck("拔耳机前有输出", audio0, 0.85));

    const plugOut = await sendShellBroadcast(ctx, [
      "-a",
      "android.intent.action.HEADSET_PLUG",
      "--ei",
      "state",
      "0",
      "--ei",
      "microphone",
      "0",
      "--es",
      "name",
      "scn-env",
    ]);
    ctx.evidence.step("broadcast-headset-plug-out", plugOut);

    let pauseCause = "";
    let injection = "";
    const isPaused = (s: State) => s.playback?.playing === false && s.playback?.playWhenReady === false;
    if (plugOut.ok) {
      const hit = await waitFor(async () => ctx.api.state(), isPaused, 4000, 80);
      if (hit.ok) {
        pauseCause = "HEADSET_PLUG(state=0)";
        injection = "headset-plug-broadcast";
      }
    }
    if (!pauseCause) {
      const noisy = await sendShellBroadcast(ctx, ["-a", "android.media.AUDIO_BECOMING_NOISY"]);
      ctx.evidence.step("broadcast-becoming-noisy", noisy);
      if (noisy.ok) {
        const hit = await waitFor(async () => ctx.api.state(), isPaused, 4000, 80);
        if (hit.ok) {
          pauseCause = "AUDIO_BECOMING_NOISY";
          injection = "becoming-noisy-broadcast";
        }
      }
    }
    if (!pauseCause) {
      return await skipEnv(
        "该 ROM 不允许 shell 注入耳机拔出(HEADSET_PLUG 需签名权限;AUDIO_BECOMING_NOISY 也未生效)。" +
          "建议真机手动拔一次,或用 AudioDeviceCallback 的单元测试覆盖。plugOut=" +
          plugOut.out.slice(0, 160),
      );
    }

    checks.push(check("拔耳机后按系统规则暂停", true, "注入=" + injection + "; 命中 " + pauseCause));
    await waitFramesSettled(ctx.api, 3000);
    const silent = await measureMainFrames(ctx.api, 1200, sr);
    checks.push(
      check(
        "拔耳机后不再出声",
        silent.deltaFrames === 0,
        "Δ=" + silent.deltaFrames + "f / " + silent.spanMs + "ms",
        silent.deltaFrames > 0 ? "拔耳机后 sink 仍在写帧:becameNoisy 只改了 UI 状态,没真暂停" : undefined,
      ),
    );
    const stPaused = await ctx.api.state();
    checks.push(mainWindowCheck("暂停时位置仍在 [A,B)", stPaused, song.id));
    checks.push(windowIntactCheck("拔耳机未改 A/B", w0, stPaused));
    checks.push(
      check(
        "拔耳机后仍在循环模式(意图不丢)",
        stPaused.playback.engine === "loop" && loopWindowOf(stPaused)?.songId === song.id,
        "engine=" + stPaused.playback.engine + " window=" + JSON.stringify(loopWindowOf(stPaused)),
      ),
    );
    const posFrozen0 = Number(stPaused.playback.positionMs);
    await sleep(700);
    const posFrozen1 = Number((await ctx.api.state()).playback.positionMs);
    checks.push(
      check(
        "拔耳机后位置不动",
        Math.abs(posFrozen1 - posFrozen0) <= 250,
        "位置变化 " + Math.abs(posFrozen1 - posFrozen0) + "ms",
      ),
    );

    // 插回耳机:只验证"不自动续播"(用户在暂停态插回耳机,播放器不应自己开声)。
    const plugIn = await sendShellBroadcast(ctx, [
      "-a",
      "android.intent.action.HEADSET_PLUG",
      "--ei",
      "state",
      "1",
      "--ei",
      "microphone",
      "0",
      "--es",
      "name",
      "scn-env",
    ]);
    ctx.evidence.step("broadcast-headset-plug-in", plugIn);
    if (plugIn.ok) {
      await sleep(1200);
      const stIn = await ctx.api.state();
      checks.push(
        check(
          "插回耳机不自动续播",
          stIn.playback.playing === false && stIn.playback.playWhenReady === false,
          "playing=" + stIn.playback.playing + " playWhenReady=" + stIn.playback.playWhenReady,
          stIn.playback.playing === true ? "插回耳机后自己开声:应等用户点播放" : undefined,
        ),
      );
    } else {
      checks.push(
        pendingCheck(
          "插回耳机不自动续播",
          "无法注入 HEADSET_PLUG(state=1),只能验证拔出侧: " + plugIn.out.slice(0, 160),
          "device-headset-plug-injection",
          "真机手动插回一次即可覆盖;或改成单元测试",
        ),
      );
    }

    // 手动继续(用户点播放)
    const manual = await ensurePlaying(ctx);
    checks.push(check("手动继续生效", manual.ok, "elapsedMs=" + manual.elapsedMs));
    await sleep(600);
    const audioAfter = await measureMainFrames(ctx.api, 1800, sr);
    checks.push(audioCheck("手动继续后有输出", audioAfter, 0.85, "恢复播放但 sink 没有输出"));
    const stAfter = await ctx.api.state();
    checks.push(
      check(
        "继续后回到循环",
        stAfter.playback.engine === "loop" && loopWindowOf(stAfter)?.songId === song.id,
        "engine=" + stAfter.playback.engine,
      ),
    );
    checks.push(mainWindowCheck("继续后位置仍在 [A,B)", stAfter, song.id));
    checks.push(windowIntactCheck("继续未改 A/B", w0, stAfter));

    return {
      checks,
      summary:
        "inject=" + injection + "; pauseΔ=" + silent.deltaFrames + "f; resume=" +
        round(audioAfter.ratio * 100, 0) + "%",
      pendingDecision: [
        "拔耳机暂停后、插回耳机是否应自动续播:Android 惯例是不自动播(需要用户确认);若产品要求自动续播请裁决。",
      ],
    };
  },
};

// ---------------------------------------------------------------------------
// 场景 43：音频焦点被其它 App 抢走 -> 循环暂停 -> 焦点回来后状态自洽
// 想抓:外部播放器起播后本应用没让出音频(两个 App 同时出声)、让出后循环窗口丢了、
//       焦点回来后位置越出 [A,B) 或引擎掉出 loop、手动继续起不来。
// 注入手段:启动一个能接收 VIEW audio/* 的第三方播放器(真实抢焦点),没有就 SKIP。
// ---------------------------------------------------------------------------

const sEnv43: Scenario = {
  id: 43,
  key: "env-audio-focus-steal",
  name: "音频焦点被其它 App 抢走",
  tag: "环境",
  exclusive: true,
  timeoutMs: 180000,
  async run(ctx) {
    const checks: Check[] = [];
    const song = ctx.songs.primary;
    const sr = ctx.sampleRateFor(song.id);
    await ctx.resetForMain();
    await requireSourceRoute(ctx);
    const base = await enterLoopPlaying(ctx, song);
    const w0: Win = { songId: song.id, aMs: base.a, bMs: base.b };
    const audio0 = await measureMainFrames(ctx.api, 1500, sr);
    checks.push(audioCheck("抢焦点前有输出", audio0, 0.85));

    const mediaId = await firstMediaStoreAudioId(ctx);
    if (mediaId === null) {
      return await skipEnv("MediaStore 里没有可播放音频,无法给第三方播放器喂数据,也就无法注入外部音频焦点竞争");
    }
    const uri = "content://media/external/audio/media/" + mediaId;
    const resolved = await ctx.adb.shell(
      "cmd package resolve-activity --brief -a android.intent.action.VIEW -t audio/mpeg -d " + uri,
      20000,
    );
    const comp = resolved.match(/([A-Za-z0-9_.]+)\/([A-Za-z0-9_.$]+)/)?.[0] ?? "";
    const otherPkg = comp.split("/")[0] ?? "";
    ctx.evidence.step("focus-competitor", { mediaId, uri, resolved: resolved.slice(0, 300), comp, otherPkg });
    if (!comp || !otherPkg || otherPkg === ctx.cfg.pkg) {
      return await skipEnv(
        "设备上没有能接收 VIEW audio/* 的第三方播放器(component=" + (comp || "none") +
          "),无法在不改设置的前提下注入外部音频焦点竞争",
      );
    }

    let started = false;
    try {
      selfActivity.mark();
      const r = await ctx.adb.run(
        ["shell", "am start -W -n " + comp + " -a android.intent.action.VIEW -t audio/mpeg -d " + uri],
        30000,
      );
      const out = (r.out + "\n" + r.err).trim();
      started = !/Error|Exception|does not exist|No Activity/i.test(out);
      ctx.evidence.step("start-competitor", { code: r.code, out: out.slice(0, 500), started });
      if (!started) {
        await ctx.adb.shell("am force-stop " + otherPkg, 20000).catch(() => "");
        await ctx.adb.bringForeground(ctx.cfg.pkg).catch(() => false);
        // 竞品 Activity 会顶掉主界面；重启应用确保 UiNavigationHooks 重新接线（#45 依赖）。
        await ctx.adb.shell("am force-stop " + ctx.cfg.pkg).catch(() => "");
        await ctx.adb.shell("am start -W -n " + ctx.cfg.pkg + "/.MainActivity").catch(() => "");
        await sleep(1200);
        return await skipEnv("第三方播放器拒绝 VIEW 播放: " + out.slice(0, 200));
      }

      const lost = await waitFor(
        async () => ctx.api.state(),
        (s) => s.playback?.playing === false,
        8000,
        100,
      );
      const stLost = (lost.value as State) ?? (await ctx.api.state());
      ctx.evidence.step("focus-loss", {
        paused: lost.ok,
        elapsedMs: lost.elapsedMs,
        playing: stLost.playback.playing,
        playWhenReady: stLost.playback.playWhenReady,
        positionMs: stLost.playback.positionMs,
      });
      if (!lost.ok) {
        // 先确认竞品真的起播/请求了焦点；只是把 Activity 拉起来不算"抢焦点"。
        const sess = await ctx.adb.shell("dumpsys media_session", 25000);
        const at = sess.indexOf("package=" + otherPkg);
        const block = at >= 0 ? sess.slice(at, at + 2000) : "";
        const competitorPlaying = /state=PLAYING/.test(block) && /state=PlaybackState/.test(block);
        ctx.evidence.step("competitor-playback", { competitorPlaying, block: block.slice(0, 600) });
        if (!competitorPlaying) {
          await ctx.adb.shell("am force-stop " + otherPkg, 20000).catch(() => "");
          await ctx.adb.bringForeground(ctx.cfg.pkg).catch(() => false);
        // 竞品 Activity 会顶掉主界面；重启应用确保 UiNavigationHooks 重新接线（#45 依赖）。
        await ctx.adb.shell("am force-stop " + ctx.cfg.pkg).catch(() => "");
        await ctx.adb.shell("am start -W -n " + ctx.cfg.pkg + "/.MainActivity").catch(() => "");
        await sleep(1200);
          return await skipEnv(
            "竞品 " + otherPkg + " 被拉起但未真正起播/未请求音频焦点（dumpsys media_session 无 PLAYING）；" +
              "环境未证实，换一个确定会起播的播放器再测",
          );
        }
      }
      checks.push(
        check(
          "外部 App 起播后本应用让出音频(暂停)",
          lost.ok,
          "8s 内 playing=" + stLost.playback.playing + " playWhenReady=" + stLost.playback.playWhenReady,
          lost.ok ? undefined : "两个 App 可能同时出声:检查 ExoPlayer.setAudioAttributes(handleAudioFocus=true)",
        ),
      );
      if (lost.ok) {
        await waitFramesSettled(ctx.api, 3000);
        const silent = await measureMainFrames(ctx.api, 1200, sr);
        checks.push(
          check("被抢焦点后静音", silent.deltaFrames === 0, "Δ=" + silent.deltaFrames + "f / " + silent.spanMs + "ms"),
        );
        const st1 = await ctx.api.state();
        checks.push(mainWindowCheck("暂停时位置仍在 [A,B)", st1, song.id));
        checks.push(windowIntactCheck("抢焦点未改 A/B", w0, st1));
        checks.push(
          check(
            "抢焦点后仍在循环模式(意图不丢)",
            st1.playback.engine === "loop" && loopWindowOf(st1)?.songId === song.id,
            "engine=" + st1.playback.engine,
          ),
        );
      }

      // 释放焦点:关掉竞争者(AUDIOFOCUS_GAIN => 系统允许本应用不自动续播,只要求状态自洽)
      const seamBefore = seamCount(await ctx.api.state());
      selfActivity.mark();
      await ctx.adb.shell("am force-stop " + otherPkg, 20000);
      await sleep(1800);
      const stBack = await ctx.api.state();
      const wBack = loopWindowOf(stBack);
      const inWindow =
        wBack !== null && wBack.songId === song.id &&
        Number(stBack.playback.positionMs) >= wBack.aMs - 1500 &&
        Number(stBack.playback.positionMs) <= wBack.bMs + 1500;
      const selfConsistent = inWindow && stBack.playback.engine === "loop";
      ctx.evidence.step("focus-back", {
        playing: stBack.playback.playing,
        playWhenReady: stBack.playback.playWhenReady,
        engine: stBack.playback.engine,
        window: wBack,
        positionMs: stBack.playback.positionMs,
      });
      checks.push(
        check(
          "焦点回落后状态自洽(自动续播或干净暂停,位置不越界)",
          selfConsistent,
          "playing=" + stBack.playback.playing + " engine=" + stBack.playback.engine +
            " pos=" + stBack.playback.positionMs + " window=" + JSON.stringify(wBack),
          selfConsistent ? undefined : "焦点回落后循环窗口/位置不自洽(掉了 loop 或播放头跑到窗口外)",
        ),
      );

      const manual = await ensurePlaying(ctx);
      checks.push(check("焦点回来后手动继续生效", manual.ok, "elapsedMs=" + manual.elapsedMs));
      await sleep(600);
      const audio2 = await measureMainFrames(ctx.api, 1800, sr);
      checks.push(audioCheck("焦点回来后仍能出声", audio2, 0.85, "恢复播放但 sink 没有输出"));
      const st3 = await ctx.api.state();
      checks.push(
        check(
          "焦点回来后仍在循环",
          st3.playback.engine === "loop" && loopWindowOf(st3)?.songId === song.id,
          "engine=" + st3.playback.engine + " window=" + JSON.stringify(loopWindowOf(st3)),
        ),
      );
      checks.push(mainWindowCheck("焦点回来后位置仍在 [A,B)", st3, song.id));
      checks.push(windowIntactCheck("焦点回来后 A/B 未变", w0, st3));
      checks.push(
        check(
          "循环未重置(seamCount 单调)",
          seamCount(st3) >= seamBefore,
          "seam " + seamBefore + " -> " + seamCount(st3),
        ),
      );

      return {
        checks,
        summary:
          "competitor=" + otherPkg + "; paused=" + lost.ok + "; audio=" +
          round(audio2.ratio * 100, 0) + "%",
        pendingDecision: [
          "其它 App 以 AUDIOFOCUS_GAIN(永久)抢焦点后是否应自动续播属系统/产品规则;" +
            "本场景只要求状态自洽(不越界/不掉 loop)+ 手动继续能恢复。" +
            "来电(TRANSIENT)应当自动续播,但 adb 无法注入。",
        ],
      };
    } finally {
      selfActivity.mark();
      await ctx.adb.shell("am force-stop " + otherPkg, 20000).catch(() => "");
      await ctx.adb.bringForeground(ctx.cfg.pkg).catch(() => false);
      const cleanup = await ctx.api.state().catch(() => null);
      ctx.evidence.step("cleanup", {
        competitorStopped: true,
        stateReadable: cleanup !== null,
        engine: cleanup?.playback?.engine,
        window: cleanup ? loopWindowOf(cleanup) : null,
      });
      // 竞品 Activity 退出后主界面导航 hook 可能未重挂；重启一次，隔离后续 #45。
      await ctx.adb.shell("am force-stop " + ctx.cfg.pkg).catch(() => "");
      await ctx.adb.shell("am start -W -n " + ctx.cfg.pkg + "/.MainActivity").catch(() => "");
      await sleep(1200);
    }
  },
};

// ---------------------------------------------------------------------------
// 场景 44：锁屏 -> 解锁(只锁屏,不改用户设置)
// 想抓:锁屏时前台服务/音频被系统回收(声音断)、锁屏/解锁把播放器重建导致位置重置或掉出循环、
//       解锁后 UI 与播放状态不一致。
// ---------------------------------------------------------------------------

const sEnv44: Scenario = {
  id: 44,
  key: "env-screen-lock",
  name: "锁屏 -> 解锁",
  tag: "环境",
  exclusive: true,
  screenChanges: true,
  visibleStateChanges: true,
  timeoutMs: 150000,
  async run(ctx) {
    const checks: Check[] = [];
    const song = ctx.songs.primary;
    const sr = ctx.sampleRateFor(song.id);
    await ctx.resetForMain();
    const awake = await ensureAwakeUnlocked(ctx, ctx.cfg.pkg);
    if (!awake.ok) return await skipEnv("无法唤醒/解锁设备,锁屏场景无法开始: " + awake.detail);
    await requireSourceRoute(ctx);
    const base = await enterLoopPlaying(ctx, song);
    const w0: Win = { songId: song.id, aMs: base.a, bMs: base.b };
    const audio0 = await measureMainFrames(ctx.api, 1500, sr);
    checks.push(audioCheck("锁屏前有输出", audio0, 0.85));

    selfActivity.mark();
    await ctx.adb.shell("input keyevent KEYCODE_SLEEP", 15000);
    const off = await waitFor(() => wakefulness(ctx), (v) => v !== "Awake", 5000, 200);
    ctx.evidence.step("screen-off", { wakefulness: off.value, elapsedMs: off.elapsedMs });
    checks.push(check("能锁屏(屏幕熄灭)", off.ok, "wakefulness=" + String(off.value ?? "unknown")));
    if (!off.ok) {
      return await skipEnv("该 ROM 拒绝 shell 熄屏(KEYCODE_SLEEP 无效),无法在不改设置的前提下测锁屏");
    }

    try {
      await sleep(2000);
      const stLocked = await ctx.api.state();
      checks.push(
        check(
          "锁屏后仍在循环播放",
          stLocked.playback.playing === true && stLocked.playback.engine === "loop",
          "playing=" + stLocked.playback.playing + " engine=" + stLocked.playback.engine,
        ),
      );
      const audioLocked = await measureMainFrames(ctx.api, 2000, sr);
      checks.push(
        audioCheck("锁屏期间声音不断", audioLocked, 0.85, "锁屏后输出帧停了:前台服务/音频焦点被系统回收"),
      );
      checks.push(mainWindowCheck("锁屏期间位置仍在 [A,B)", stLocked, song.id));
      checks.push(windowIntactCheck("锁屏未改 A/B", w0, stLocked));
      const seamLocked = seamCount(stLocked);

      const back = await ensureAwakeUnlocked(ctx, ctx.cfg.pkg);
      checks.push(check("能亮屏解锁", back.ok, back.detail));
      if (!back.ok) {
        checks.push(
          pendingCheck(
            "解锁后位置连续",
            "解锁失败: " + back.detail,
            "device-keyguard",
            "设备有安全锁屏;需人工/指纹解锁后重跑,或换无锁屏的测试机",
          ),
        );
        return {
          checks,
          summary:
            "locked ok; unlock=" + back.detail + "; lockedAudio=" + round(audioLocked.ratio * 100, 0) + "%",
        };
      }

      const stUnlocked = await ctx.api.state();
      checks.push(
        check(
          "解锁后仍在播放",
          stUnlocked.playback.playing === true,
          "playing=" + stUnlocked.playback.playing + " playWhenReady=" + stUnlocked.playback.playWhenReady,
        ),
      );
      checks.push(
        check(
          "解锁后循环没有被重置(seamCount 单调)",
          seamCount(stUnlocked) >= seamLocked,
          "seam " + seamLocked + " -> " + seamCount(stUnlocked),
          seamCount(stUnlocked) < seamLocked ? "解锁时循环播放器被重建:seam 计数回退" : undefined,
        ),
      );
      checks.push(
        check(
          "解锁后回到循环",
          stUnlocked.playback.engine === "loop" && loopWindowOf(stUnlocked)?.songId === song.id,
          "engine=" + stUnlocked.playback.engine,
        ),
      );
      checks.push(mainWindowCheck("解锁后位置仍在 [A,B)", stUnlocked, song.id));
      checks.push(windowIntactCheck("解锁未改 A/B", w0, stUnlocked));
      const audioAfter = await measureMainFrames(ctx.api, 1800, sr);
      checks.push(audioCheck("解锁后声音不断", audioAfter, 0.85));

      return {
        checks,
        summary:
          "lock ok; lockedAudio=" + round(audioLocked.ratio * 100, 0) + "% unlockedAudio=" +
          round(audioAfter.ratio * 100, 0) + "%; pos=" + String(stUnlocked.playback.positionMs),
      };
    } finally {
      // 兜底:中途抛错时别把设备留在锁屏/灭屏状态。先幂等亮屏，再尝试解锁。
      await ctx.adb.wakeAndUnlock();
      const backCleanup = await ensureAwakeUnlocked(ctx, ctx.cfg.pkg);
      const stCleanup = await ctx.api.state().catch(() => null);
      ctx.evidence.step("cleanup", {
        unlocked: backCleanup.ok,
        detail: backCleanup.detail,
        playing: stCleanup?.playback?.playing,
      });
      if (stCleanup && stCleanup.playback?.playing !== true) await ensurePlaying(ctx);
    }
  },
};

// ---------------------------------------------------------------------------
// 场景 45：屏幕旋转 -> 结果页重建 -> 预览是否仍在 [A,B)
// 想抓:旋转触发 Activity 重建后预览播放器没有恢复(页面空白/不播)、A/B 被重置、
//       播放头跑到窗口外、或重建后声音卡顿/断流。
// 注入:wm/cmd window user-rotation(不写 settings);finally 按原 accelerometer_rotation 恢复。
// ---------------------------------------------------------------------------

const sEnv45: Scenario = {
  id: 45,
  key: "env-rotation-preview",
  name: "屏幕旋转 -> 结果页重建 -> 预览仍在 [A,B)",
  tag: "环境",
  exclusive: true,
  allowPidRestart: true,
  timeoutMs: 180000,
  async run(ctx) {
    const checks: Check[] = [];
    const song = ctx.songs.short;
    const sr = ctx.sampleRateFor(song.id);
    await ctx.resetForMain();
    const awake = await ensureAwakeUnlocked(ctx, ctx.cfg.pkg);
    if (!awake.ok) return await skipEnv("无法唤醒/解锁设备,旋转场景无法开始: " + awake.detail);

    const origAccel = (await ctx.adb.shell("settings get system accelerometer_rotation", 15000)).trim();
    const origUserRotation = (await ctx.adb.shell("settings get system user_rotation", 15000)).trim();
    const rot0 = await currentRotation(ctx);
    ctx.evidence.step("rotation-before", { rot0, origAccel, origUserRotation });

    // 先用命令本身探测能力(拿不到能力就直接 SKIP,不留半改状态)。
    // 页面必须在「原朝向」建起来,再翻到另一朝向,否则重建路径根本没被走到。
    const startRot = rot0 === 1 || rot0 === 3 ? 1 : 0;
    const target = startRot === 1 ? 0 : 1;
    const probeSet = await setRotation(ctx, target);
    ctx.evidence.step("rotation-probe", { ...probeSet, rot0, startRot, target });
    if (!probeSet.ok) {
      return await skipEnv(
        "该 ROM 不支持 wm/cmd window user-rotation,无法在不改设置的前提下旋转屏幕: " + probeSet.detail,
      );
    }
    let via: "wm" | "cmd-window" | "none" = probeSet.via;
    try {
      // 摆回起始朝向:探测用的那次旋转不能算作"旋转测试"。
      const backToStart = await setRotation(ctx, startRot);
      ctx.evidence.step("rotation-back-to-start", { startRot, ...backToStart });
      await sleep(600);
      try {
        await ctx.ensureLoopPage(song.id);
      } catch (e: any) {
        // #43 拉起竞品后返回，主 Activity 的导航 hook 可能未重挂；重启一次应用再试。
        if (/navigate 未接线|preview probe not attached/.test(String(e?.message ?? e))) {
          ctx.evidence.step("navigate-retry", { error: String(e?.message ?? e) });
          await ctx.adb.shell("am force-stop " + ctx.cfg.pkg);
          await ctx.adb.shell("am start -W -n " + ctx.cfg.pkg + "/.MainActivity");
          await sleep(1500);
          await ctx.ensureLoopPage(song.id);
        } else {
          throw e;
        }
      }
      await ctx.uiTap("仅循环段");
      let ready = await waitFor(
        async () => (await ctx.api.preview()).snapshot,
        (s) => s && s.mode === "LOOP_ONLY" && s.isPlaying === true,
        10000,
        120,
      );
      if (!ready.ok || !ready.value) {
        // 页面重建后按钮可能晚一拍：再等一拍并重试一次点击。
        await sleep(800);
        await ctx.uiTap("仅循环段");
        ready = await waitFor(
          async () => (await ctx.api.preview()).snapshot,
          (s) => s && s.mode === "LOOP_ONLY" && s.isPlaying === true,
          8000,
          120,
        );
      }
      if (!ready.ok || !ready.value) {
        return await skipEnv("结果页「仅循环段」未起播,无法验证旋转后重建(先按 docs §1.4 确认引擎)");
      }
      const snap0: any = ready.value;
      const a = Number(snap0.aMs);
      const b = Number(snap0.bMs);
      checks.push(await previewAudioCheck(ctx, snap0, sr, "旋转前预览有声"));

      const rotated = await setRotation(ctx, target);
      via = rotated.via;
      ctx.evidence.step("rotation-set", { target, ...rotated });
      checks.push(check("旋转命令下发成功", rotated.ok, rotated.detail));
      if (!rotated.ok) return await skipEnv("旋转命令在探测后失效: " + rotated.detail);

      const rotNow = await currentRotation(ctx);
      if (rotNow === null) {
        checks.push(
          pendingCheck(
            "屏幕已旋转",
            "读不到当前朝向(dumpsys input/window/display 都没有 mRotation)",
            "device-rotation-readback",
          ),
        );
      } else {
        checks.push(
          check("屏幕已旋转", rotNow === target, "rotation " + String(rot0) + " -> " + rotNow + " (目标 " + target + ")"),
        );
      }

      // 旋转会触发页面重建:预览端点可能短暂 409 / isPlaying=false
      const rebuilt = await waitFor(
        async () => (await ctx.api.preview()).snapshot,
        (s) => s && s.isPlaying === true && s.mode === "LOOP_ONLY",
        15000,
        150,
      );
      checks.push(
        check(
          "旋转后预览恢复播放(页面重建)",
          rebuilt.ok,
          "15s 内 isPlaying=" + String((rebuilt.value as any)?.isPlaying) +
            " mode=" + String((rebuilt.value as any)?.mode),
          rebuilt.ok ? undefined : "旋转后结果页预览没恢复:检查 configChanges/播放器重建路径",
        ),
      );
      if (rebuilt.ok && rebuilt.value) {
        const s1: any = rebuilt.value;
        checks.push(
          check(
            "旋转后 A/B 未变",
            Math.abs(Number(s1.aMs) - a) <= 1 && Math.abs(Number(s1.bMs) - b) <= 1,
            "A/B " + a + "/" + b + " -> " + s1.aMs + "/" + s1.bMs,
          ),
        );
        checks.push(
          check(
            "旋转后位置仍在 [A,B)",
            within(Number(s1.displayMs), a - 1500, b + 1500),
            "display=" + s1.displayMs + " window=" + a + ".." + b,
          ),
        );
        checks.push(await previewAudioCheck(ctx, s1, sr, "旋转后预览有声"));
        ctx.evidence.step("rotation-after", {
          mode: s1.mode,
          engine: s1.engine,
          isPlaying: s1.isPlaying,
          displayMs: s1.displayMs,
          aMs: s1.aMs,
          bMs: s1.bMs,
          outputFrames: s1.outputFrames,
        });
      }
      return {
        checks,
        summary:
          "rotation " + String(rot0) + "->" + String(target) + "; via=" + via +
          "; previewDisplay=" + String((rebuilt.value as any)?.displayMs),
      };
    } finally {
      const restored = await restoreRotation(ctx, via, origAccel, origUserRotation);
      const accelNow = (await ctx.adb.shell("settings get system accelerometer_rotation", 15000)).trim();
      const rotBack = await currentRotation(ctx);
      ctx.evidence.step("rotation-restore", { ...restored, accelNow, rotBack, origAccel, origUserRotation });
      checks.push(check("恢复原旋转设置", restored.ok, restored.detail + "; accelerometer_rotation=" + accelNow));
    }
  },
};

// ---------------------------------------------------------------------------
// 场景 46：分屏/小窗(设备支持时)-> 布局变化不打断播放
// 想抓:进分屏后 Activity 重建/布局重排把循环播放打断(掉了 loop / 没声 / 播放头跑到窗口外),
//       退出分屏后循环状态回不去。
// 注入:am start --windowingMode 4;ROM 不支持 / 没真正进分屏就 SKIP(不伪造通过)。
// ---------------------------------------------------------------------------

const sEnv46: Scenario = {
  id: 46,
  key: "env-split-screen",
  name: "分屏/小窗 -> 布局变化不打断播放",
  tag: "环境",
  exclusive: true,
  timeoutMs: 150000,
  async run(ctx) {
    const checks: Check[] = [];
    const song = ctx.songs.primary;
    const sr = ctx.sampleRateFor(song.id);
    await ctx.resetForMain();
    const awake = await ensureAwakeUnlocked(ctx, ctx.cfg.pkg);
    if (!awake.ok) return await skipEnv("无法唤醒/解锁设备,分屏场景无法开始: " + awake.detail);
    await requireSourceRoute(ctx);
    const base = await enterLoopPlaying(ctx, song);
    const w0: Win = { songId: song.id, aMs: base.a, bMs: base.b };
    const audio0 = await measureMainFrames(ctx.api, 1500, sr);
    checks.push(audioCheck("分屏前有输出", audio0, 0.85));

    const comp = await launcherComponent(ctx, ctx.cfg.pkg);
    if (!comp) return await skipEnv("拿不到 launcher component,无法用 am start 触发多窗口");
    const screen = await ctx.adb.screenSize();
    const dump0 = await ctx.adb.shell("dumpsys window windows", 30000);
    const frame0 = windowFrameOf(dump0, ctx.cfg.pkg);
    ctx.evidence.step("before-split", { comp, screen, frame0 });

    selfActivity.mark();
    const r = await ctx.adb.run(["shell", "am start -W --windowingMode 4 -n " + comp], 30000);
    const out = (r.out + "\n" + r.err).trim();
    ctx.evidence.step("split-start", { code: r.code, out: out.slice(0, 600) });
    if (/Unknown option|unrecognized option|Error:.*windowingMode/i.test(out)) {
      return await skipEnv("该 ROM 的 am start 不支持 --windowingMode: " + out.slice(0, 200));
    }
    if (/Error|Exception/i.test(out) && /does not exist|No Activity/i.test(out)) {
      return await skipEnv("am start 无法启动 launcher activity: " + out.slice(0, 200));
    }

    try {
      const engaged = await waitFor(
        async () => windowFrameOf(await ctx.adb.shell("dumpsys window windows", 30000), ctx.cfg.pkg),
        (f) => f !== null && f.r - f.l <= screen.width * 0.8,
        8000,
        500,
      );
      const frame1 = engaged.value;
      ctx.evidence.step("split-frame", { frame1, screen, engaged: engaged.ok });
      if (!engaged.ok) {
        return await skipEnv(
          "分屏没有生效(窗口宽度仍≈全屏):frame=" + JSON.stringify(frame0) + " -> " +
            JSON.stringify(frame1) + ";ROM 可能要求从最近任务手动进入分屏",
        );
      }
      checks.push(
        check(
          "进入分屏/小窗(窗口变窄)",
          true,
          "frame " + JSON.stringify(frame0) + " -> " + JSON.stringify(frame1) + " (屏宽 " + screen.width + ")",
        ),
      );
      await sleep(1200);
      const st = await ctx.api.state();
      const wNow = loopWindowOf(st);
      checks.push(
        check(
          "分屏后仍在循环播放",
          st.playback.playing === true && st.playback.engine === "loop" && wNow?.songId === song.id,
          "playing=" + st.playback.playing + " engine=" + st.playback.engine +
            " window=" + JSON.stringify(wNow),
          st.playback.playing !== true ? "布局变化把播放打断了(Activity 重建时把 player 停了)" : undefined,
        ),
      );
      checks.push(mainWindowCheck("分屏后位置仍在 [A,B)", st, song.id));
      checks.push(windowIntactCheck("分屏未改 A/B", w0, st));
      const audioSplit = await measureMainFrames(ctx.api, 2000, sr);
      checks.push(audioCheck("分屏后声音不断", audioSplit, 0.85, "分屏后输出帧断流:布局重排打断了音频输出"));

      return {
        checks,
        summary: "split " + JSON.stringify(frame1) + "; audio=" + round(audioSplit.ratio * 100, 0) + "%",
      };
    } finally {
      // 退回全屏:再拉一次 launcher activity(不带 windowingMode),并核对窗口宽度恢复
      selfActivity.mark();
      await ctx.adb.run(["shell", "am start -W -n " + comp], 30000).catch(() => null);
      await sleep(1200);
      const f = windowFrameOf(await ctx.adb.shell("dumpsys window windows", 30000), ctx.cfg.pkg);
      const restoredOk = f === null || f.r - f.l >= screen.width * 0.9;
      const stCleanup = await ctx.api.state().catch(() => null);
      ctx.evidence.step("exit-split", { frame: f, screen, restoredOk, engine: stCleanup?.playback?.engine });
      checks.push(check("退出分屏回到全屏", restoredOk, "frame=" + JSON.stringify(f)));
      if (stCleanup && stCleanup.playback?.playing !== true) await ensurePlaying(ctx);
    }
  },
};

// ---------------------------------------------------------------------------
// 场景 47：切后台 60 秒再回前台 -> 位置连续、不重启
// 想抓:后台驻留期间被系统冻结/回收(音乐断、位置重置、进程重启),回前台后 UI 与播放状态不一致。
// ---------------------------------------------------------------------------

const sEnv47: Scenario = {
  id: 47,
  key: "env-background-60s",
  name: "后台 60s -> 回前台",
  tag: "环境",
  exclusive: true,
  timeoutMs: 220000,
  async run(ctx) {
    const checks: Check[] = [];
    const song = ctx.songs.primary;
    const sr = ctx.sampleRateFor(song.id);
    await ctx.resetForMain();
    await requireSourceRoute(ctx);
    const base = await enterLoopPlaying(ctx, song);
    const w0: Win = { songId: song.id, aMs: base.a, bMs: base.b };
    const pid0 = await ctx.adb.pidof(ctx.cfg.pkg);
    const audio0 = await measureMainFrames(ctx.api, 1500, sr);
    checks.push(audioCheck("切后台前有输出", audio0, 0.85));

    await ctx.adb.home();
    try {
      const t0 = Date.now();
      await sleep(1500);
      const bg1 = await ctx.api.state();
      checks.push(check("切后台后仍在播放", bg1.playback.playing === true, "playing=" + bg1.playback.playing));
      checks.push(mainWindowCheck("切后台后位置仍在 [A,B)", bg1, song.id));

      // 后台驻留:前段/中段各测一次输出,避免"只测第一秒"漏掉被冻结。
      await sleep(20000);
      const bgAudio1 = await measureMainFrames(ctx.api, 2000, sr);
      checks.push(
        audioCheck("后台 20s 仍有输出", bgAudio1, 0.85, "后台 20s 就没声了:前台服务被冻结/回收"),
      );
      await sleep(22000);
      const bgAudio2 = await measureMainFrames(ctx.api, 2000, sr);
      checks.push(audioCheck("后台 45s 仍有输出", bgAudio2, 0.85));
      const remain = Math.max(0, 60000 - (Date.now() - t0));
      if (remain > 0) await sleep(remain);
      const bg2 = await ctx.api.state();
      const bgPid = await ctx.adb.pidof(ctx.cfg.pkg);
      checks.push(check("后台 60s 后仍在播放", bg2.playback.playing === true, "playing=" + bg2.playback.playing));
      checks.push(
        check(
          "后台 60s 进程未重启",
          pid0 !== null && bgPid === pid0,
          "pid " + String(pid0) + " -> " + String(bgPid),
          bgPid !== pid0 ? "后台期间进程被重启:检查 FGS 是否真的在跑" : undefined,
        ),
      );
      checks.push(mainWindowCheck("后台 60s 位置仍在 [A,B)", bg2, song.id));
      checks.push(windowIntactCheck("后台 60s 未改 A/B", w0, bg2));

      const brought = await ctx.adb.bringForeground(ctx.cfg.pkg);
      await sleep(1500);
      const fg = await ctx.api.state();
      const fgPid = await ctx.adb.pidof(ctx.cfg.pkg);
      checks.push(check("回到前台", brought, "foreground activity 匹配失败"));
      checks.push(check("回前台仍在播放", fg.playback.playing === true, "playing=" + fg.playback.playing));
      checks.push(mainWindowCheck("回前台位置仍在 [A,B)", fg, song.id));
      checks.push(windowIntactCheck("回前台未改 A/B", w0, fg));
      checks.push(check("回前台进程未变", fgPid === pid0, "pid=" + String(fgPid) + " (原 " + String(pid0) + ")"));
      const fgAudio = await measureMainFrames(ctx.api, 1800, sr);
      checks.push(audioCheck("回前台仍有输出", fgAudio, 0.85));

      return {
        checks,
        summary:
          "bg60s audio " + round(bgAudio1.ratio * 100, 0) + "%/" + round(bgAudio2.ratio * 100, 0) +
          "% fg " + round(fgAudio.ratio * 100, 0) + "%; pid " + String(pid0) + "===" + String(bgPid),
      };
    } finally {
      // 兜底:中途抛错时把应用拉回前台,不把设备留在后台状态。
      const backCleanup = await ctx.adb.bringForeground(ctx.cfg.pkg).catch(() => false);
      const stCleanup = await ctx.api.state().catch(() => null);
      ctx.evidence.step("cleanup", { foreground: backCleanup, playing: stCleanup?.playback?.playing });
      if (stCleanup && stCleanup.playback?.playing !== true) await ensurePlaying(ctx);
    }
  },
};

// ---------------------------------------------------------------------------
// 场景 48：低内存压力(am send-trim-memory)-> 环带不崩、位置不乱
// 想抓:系统 trim(COMPLETE/MODERATE)把环带缓存回收后播放断流、环带被降级、位置/窗口乱掉,或直接 OOM。
// ---------------------------------------------------------------------------

const sEnv48: Scenario = {
  id: 48,
  key: "env-trim-memory",
  name: "低内存压力(trim-memory)",
  tag: "环境",
  timeoutMs: 150000,
  async run(ctx) {
    const checks: Check[] = [];
    const song = ctx.songs.primary;
    const sr = ctx.sampleRateFor(song.id);
    await ctx.resetForMain();
    await requireSourceRoute(ctx);
    const base = await enterLoopPlaying(ctx, song);
    const w0: Win = { songId: song.id, aMs: base.a, bMs: base.b };
    const pid0 = await ctx.adb.pidof(ctx.cfg.pkg);
    if (pid0 === null) throw new Error("拿不到应用 pid,无法 send-trim-memory");
    const epochSec = Math.floor(Date.now() / 1000);
    const mem0 = await ctx.adb.meminfo(ctx.cfg.pkg);
    const audio0 = await measureMainFrames(ctx.api, 1500, sr);
    checks.push(audioCheck("trim 前有输出", audio0, 0.85));
    ctx.evidence.step("before-trim", {
      pid0,
      javaHeapKb: mem0.javaHeapKb,
      totalPssKb: mem0.totalPssKb,
      window: w0,
    });

    for (const level of ["MODERATE", "COMPLETE"] as const) {
      selfActivity.mark();
      const r = await ctx.adb.run(["shell", "am send-trim-memory " + pid0 + " " + level], 30000);
      const out = (r.out + "\n" + r.err).trim();
      const bad = /unknown command|Unknown command|Usage:|Error|Exception/i.test(out);
      ctx.evidence.step("trim-" + level, { code: r.code, out: out.slice(0, 300), bad });
      if (bad && level === "MODERATE") {
        return await skipEnv("该 ROM 不支持 am send-trim-memory: " + out.slice(0, 200));
      }
      checks.push(check("trim(" + level + ") 下发成功", !bad, out.slice(0, 200) || "ok"));
      const st = await ctx.api.state();
      checks.push(
        check(
          "trim(" + level + ") 后仍在循环播放",
          st.playback.playing === true && st.playback.engine === "loop",
          "playing=" + st.playback.playing + " engine=" + st.playback.engine,
          st.playback.playing !== true ? "trim 后播放停了:系统内存压力不该停掉前台音乐" : undefined,
        ),
      );
      const audio = await measureMainFrames(ctx.api, 2000, sr);
      checks.push(
        audioCheck(
          "trim(" + level + ") 后 2s 无断流",
          audio,
          0.85,
          "trim 后输出帧断流:环带/缓存被系统回收后没有重建",
        ),
      );
      const st2 = await ctx.api.state();
      checks.push(mainWindowCheck("trim(" + level + ") 后位置仍在 [A,B)", st2, song.id));
      checks.push(windowIntactCheck("trim(" + level + ") 未改 A/B", w0, st2));
    }

    const pidAfter = await ctx.adb.pidof(ctx.cfg.pkg);
    const oom = await ctx.adb.oomLinesSince(epochSec, ctx.cfg.pkg);
    const mem1 = await ctx.adb.meminfo(ctx.cfg.pkg);
    ctx.evidence.step("after-trim", {
      pidAfter,
      javaHeapKb: mem1.javaHeapKb,
      totalPssKb: mem1.totalPssKb,
      oom,
    });
    checks.push(check("进程未重启", pidAfter === pid0, "pid " + String(pid0) + " -> " + String(pidAfter)));
    checks.push(
      check("crash buffer 无 OOM", oom.length === 0, oom.length === 0 ? "none" : oom.join(" | ").slice(0, 300)),
    );

    return {
      checks,
      summary:
        "trim MODERATE/COMPLETE; heap " + String(mem0.javaHeapKb) + "->" + String(mem1.javaHeapKb) +
        "KB; pid " + String(pid0) + (pidAfter === pid0 ? " 稳定" : " -> " + String(pidAfter)),
    };
  },
};

// ---------------------------------------------------------------------------
// 场景 49：省电模式 + Doze(能触发时)-> 播放不中断,或按系统规则暂停后能恢复
// 想抓:省电/Doze 下前台音乐被冻结(音乐断)、Doze 退出后循环回不去、被系统暂停后位置/窗口丢失。
// 副作用:临时写 global low_power(恢复原值)、进入/退出 deviceidle;finally 全部还原并核对。
// ---------------------------------------------------------------------------

const sEnv49: Scenario = {
  id: 49,
  key: "env-power-saver-doze",
  name: "省电模式 + Doze",
  tag: "环境",
  exclusive: true,
  screenChanges: true,
  visibleStateChanges: true,
  timeoutMs: 180000,
  async run(ctx) {
    const checks: Check[] = [];
    const song = ctx.songs.primary;
    const sr = ctx.sampleRateFor(song.id);
    await ctx.resetForMain();
    await requireSourceRoute(ctx);
    const base = await enterLoopPlaying(ctx, song);
    const w0: Win = { songId: song.id, aMs: base.a, bMs: base.b };
    const audio0 = await measureMainFrames(ctx.api, 1500, sr);
    checks.push(audioCheck("省电前有输出", audio0, 0.85));

    const origLowPower = (await ctx.adb.shell("settings get global low_power", 15000)).trim();
    ctx.evidence.step("orig-low-power", { origLowPower });
    let dozeForced = false;
    let screenOff = false;

    try {
      selfActivity.mark();
      const set = await ctx.adb.run(["shell", "settings put global low_power 1"], 15000);
      const setOut = (set.out + "\n" + set.err).trim();
      checks.push(
        check(
          "能开启省电模式(临时)",
          set.code === 0 && !/Error|Exception/i.test(setOut),
          setOut.slice(0, 200) || "ok",
        ),
      );
      await sleep(3000);
      const stSaver = await ctx.api.state();
      checks.push(
        check(
          "省电模式下仍在循环播放",
          stSaver.playback.playing === true && stSaver.playback.engine === "loop",
          "playing=" + stSaver.playback.playing + " engine=" + stSaver.playback.engine,
        ),
      );
      const audioSaver = await measureMainFrames(ctx.api, 2000, sr);
      checks.push(
        audioCheck(
          "省电模式下声音不断",
          audioSaver,
          0.85,
          "省电模式不该停掉前台音乐服务;若停,检查 FGS/省电白名单",
        ),
      );
      checks.push(mainWindowCheck("省电模式位置仍在 [A,B)", stSaver, song.id));
      checks.push(windowIntactCheck("省电模式未改 A/B", w0, stSaver));

      // Doze 需要熄屏条件;先熄屏(不改设置),再 force-idle
      selfActivity.mark();
      await ctx.adb.shell("input keyevent KEYCODE_SLEEP", 15000);
      const off = await waitFor(() => wakefulness(ctx), (v) => v !== "Awake", 5000, 200);
      screenOff = off.ok;
      ctx.evidence.step("doze-screen-off", { ok: off.ok, wakefulness: off.value });
      if (!screenOff) {
        checks.push(pendingCheck("Doze 下播放不中断", "熄屏失败,Doze 注入前提不满足", "device-screen-off-injection"));
      } else {
        let idleOut = "";
        for (const args of ["force-idle deep", "force-idle"]) {
          selfActivity.mark();
          const idle = await ctx.adb.run(["shell", "dumpsys deviceidle " + args], 30000);
          idleOut = (idle.out + "\n" + idle.err).trim();
          if (/forced in to (deep )?idle/i.test(idleOut)) {
            dozeForced = true;
            break;
          }
          if (!/Unknown|Usage|Error/i.test(idleOut)) break;
        }
        ctx.evidence.step("force-idle", { forced: dozeForced, out: idleOut.slice(0, 400) });
        if (dozeForced) {
          const stDoze = await ctx.api.state();
          const audioDoze = await measureMainFrames(ctx.api, 2500, sr);
          checks.push(
            check(
              "Doze 下仍在循环播放",
              stDoze.playback.playing === true && stDoze.playback.engine === "loop",
              "playing=" + stDoze.playback.playing + " engine=" + stDoze.playback.engine,
            ),
          );
          checks.push(
            audioCheck("Doze 下声音不断", audioDoze, 0.85, "Doze 期间音乐停:前台服务/音频焦点未按系统规则豁免"),
          );
          checks.push(mainWindowCheck("Doze 位置仍在 [A,B)", stDoze, song.id));
          checks.push(windowIntactCheck("Doze 未改 A/B", w0, stDoze));
          ctx.evidence.step("doze-audio", { deltaFrames: audioDoze.deltaFrames, ratio: audioDoze.ratio });
        } else {
          checks.push(
            pendingCheck(
              "Doze 下播放不中断",
              "force-idle 未被系统接受: " + idleOut.slice(0, 200),
              "device-doze-injection",
              "部分 ROM 直接拒绝 force-idle;可改成真机静置后看电池历史",
            ),
          );
        }
      }
      return {
        checks,
        summary:
          "saver audio=" + round(audioSaver.ratio * 100, 0) + "%; doze=" +
          (dozeForced ? "forced" : "not-triggerable"),
        pendingDecision: [
          "Doze 期间是否允许系统暂停播放(而非前台服务豁免)属系统/产品规则;本场景只要求:能播就别断," +
            "被系统暂停则退出 Doze 后能恢复且位置/窗口不变。",
        ],
      };
    } finally {
      // 退出 Doze
      selfActivity.mark();
      const unforce = await ctx.adb.run(["shell", "dumpsys deviceidle unforce"], 20000);
      // 恢复低电量设置(原值可能是 null / 0 / 1)
      const restoreCmd =
        origLowPower === "null" || origLowPower === ""
          ? "settings delete global low_power"
          : "settings put global low_power " + origLowPower;
      const restore = await ctx.adb.run(["shell", restoreCmd], 15000);
      const now = (await ctx.adb.shell("settings get global low_power", 15000)).trim();
      const ok =
        origLowPower === "null" || origLowPower === "" ? now === "null" || now === "" : now === origLowPower;
      // 不管熄屏状态读到什么，finally 都要把屏幕恢复：用户手机不能被留在灭屏/锁屏。
      const wake = await ctx.adb.wakeAndUnlock();
      const back = await ensureAwakeUnlocked(ctx, ctx.cfg.pkg);
      ctx.evidence.step("doze-screen-on", { ok: back.ok, detail: back.detail, wake });
      const stCleanup = await ctx.api.state().catch(() => null);
      ctx.evidence.step("restore-power", {
        unforceOut: (unforce.out + unforce.err).slice(0, 200),
        restoreOut: (restore.out + restore.err).slice(0, 200),
        origLowPower,
        now,
        ok,
        engine: stCleanup?.playback?.engine,
        playing: stCleanup?.playback?.playing,
      });
      checks.push(check("恢复低电量设置", ok, "low_power=" + now + " (原 " + origLowPower + ")"));
      if (stCleanup && stCleanup.playback?.playing !== true) await ensurePlaying(ctx);
    }
  },
};

// ---------------------------------------------------------------------------
// 场景 50：系统重启后恢复上次循环状态(默认 SKIP;--long 且 HESI_ENV_REBOOT=1 才跑)
// 想抓:重启后循环行/播放状态没恢复(A/B 丢了、回到默认列表)、或恢复出来的 A/B 与重启前不一致。
// 注意:重启必然改变 pid 并可能清掉 adb forward,runner 的「pid 一致性」判据会 FAIL;
//       所以这条只能单独跑(--only 50),报告里按独立结论处理。
// ---------------------------------------------------------------------------

const sEnv50: Scenario = {
  id: 50,
  key: "env-reboot-restore",
  name: "系统重启后恢复上次循环状态",
  tag: "环境",
  long: true,
  visibleStateChanges: true,
  timeoutMs: 480000,
  async run(ctx) {
    const checks: Check[] = [];
    const env: any = (globalThis as any).process?.env ?? {};
    if (String(env.HESI_ENV_REBOOT ?? "") !== "1") {
      return await skipEnv(
        "重启会改变 pid / 清 adb forward,runner 的 pid 一致性判据必然 FAIL;" +
          "设 HESI_ENV_REBOOT=1 并单独 --only 50 跑",
      );
    }
    const song = ctx.songs.primary;
    await ctx.resetForMain();
    const base = await enterLoopPlaying(ctx, song);
    const w0: Win = { songId: song.id, aMs: base.a, bMs: base.b };
    const preSong = await ctx.api.song(song.id);
    ctx.evidence.step("before-reboot", {
      window: w0,
      engine: base.state.playback.engine,
      playing: base.state.playback.playing,
      repeatMode: base.state.playback.repeatMode,
      songRow: { a: preSong.loopStartMs, b: preSong.loopEndMs, confirmed: preSong.loopConfirmed },
    });

    selfActivity.mark();
    await ctx.adb.shell("reboot", 20000);
    // 等设备重启完成;期间 adb shell 会报错/挂起,统一吞掉。
    const boot = await waitFor(
      async () => {
        try {
          const out = await ctx.adb.shell("getprop sys.boot_completed", 8000);
          return out.trim();
        } catch {
          return "";
        }
      },
      (v) => v === "1",
      300000,
      3000,
    );
    checks.push(
      check("设备重启完成", boot.ok, "boot_completed=" + String(boot.value) + " 耗时 " + boot.elapsedMs + "ms"),
    );
    if (!boot.ok) {
      return { checks, summary: "reboot 未在 300s 内完成(boot_completed=" + String(boot.value) + ")" };
    }

    await ctx.adb.forwardEnsure(ctx.cfg.forwardLocal, ctx.cfg.forwardRemote);
    const awake = await ensureAwakeUnlocked(ctx, ctx.cfg.pkg);
    selfActivity.mark();
    const launched = await ctx.adb.bringForeground(ctx.cfg.pkg);
    checks.push(check("重启后应用可前台启动", launched, "bringForeground=" + launched));
    const apiUp = await waitFor(async () => (await ctx.api.get("/state")).status, (s) => s === 200, 60000, 1000);
    checks.push(check("重启后调试口可用", apiUp.ok, "GET /state -> " + String(apiUp.value)));
    if (!apiUp.ok) return { checks, summary: "重启后调试口不可用" };
    const stAfter = await ctx.api.state();
    const songAfter = await ctx.api.song(song.id).catch(() => null);
    checks.push(
      check(
        "重启后 A/B 仍在库中(持久化)",
        songAfter !== null &&
          Math.abs(Number(songAfter.loopStartMs) - w0.aMs) <= 2 &&
          Math.abs(Number(songAfter.loopEndMs) - w0.bMs) <= 2,
        "A/B=" + (songAfter ? songAfter.loopStartMs + "/" + songAfter.loopEndMs : "?") +
          " (重启前 " + w0.aMs + "/" + w0.bMs + ")",
      ),
    );
    // 重新进循环能出声(重启不该把环带链路弄坏)
    const reEnter = await enterLoop(ctx, song.id, true, true);
    const reinstalled = await waitLoopInstalled(ctx, song.id, 15000);
    const sr = ctx.sampleRateFor(song.id);
    const audioAfter = reinstalled.ok ? await measureMainFrames(ctx.api, 2000, sr) : null;
    checks.push(
      check(
        "重启后能重新进循环",
        reEnter.status === 200 && reinstalled.ok,
        "enter=" + reEnter.status + " engine=" + String(reinstalled.state?.playback?.engine),
      ),
    );
    if (audioAfter) checks.push(audioCheck("重启后循环有声", audioAfter, 0.85));
    ctx.evidence.step("after-reboot", {
      awake,
      songRow: songAfter ? { a: songAfter.loopStartMs, b: songAfter.loopEndMs } : null,
      state: {
        engine: stAfter.playback?.engine,
        playing: stAfter.playback?.playing,
        positionMs: stAfter.playback?.positionMs,
        window: loopWindowOf(stAfter),
      },
    });

    return {
      checks,
      summary:
        "reboot ok; A/B " + w0.aMs + "/" + w0.bMs + " preserved=" + (songAfter ? "yes" : "no") +
        "; reenter=" + (reinstalled.ok ? "yes" : "no"),
      pendingDecision: [
        "重启后是否要自动恢复上次的播放/循环(自动起播、回到上次位置)未定;" +
          "本场景只判 A/B 持久化与循环链路可用。",
      ],
    };
  },
};

// ---------------------------------------------------------------------------
// 场景 51：充电器插拔 -> 播放不断
// 想抓:电源状态变化(充电/断电)触发系统行为后音乐断、循环掉、位置乱。
// 注入:dumpsys battery set ac 0/1;finally 用 dumpsys battery reset 还原(不碰用户设置)。
// ---------------------------------------------------------------------------

const sEnv51: Scenario = {
  id: 51,
  key: "env-usb-charging",
  name: "充电器插拔",
  tag: "环境",
  visibleStateChanges: true,
  timeoutMs: 120000,
  async run(ctx) {
    const checks: Check[] = [];
    const song = ctx.songs.primary;
    const sr = ctx.sampleRateFor(song.id);
    await ctx.resetForMain();
    await requireSourceRoute(ctx);
    const base = await enterLoopPlaying(ctx, song);
    const w0: Win = { songId: song.id, aMs: base.a, bMs: base.b };
    const battery0 = await ctx.adb.shell("dumpsys battery", 20000);
    ctx.evidence.step("battery-before", {
      plugged: /AC powered: true/i.test(battery0),
      usb: /USB powered: true/i.test(battery0),
    });
    if (!/AC powered/i.test(battery0)) {
      return await skipEnv("dumpsys battery 输出里没有 AC powered 字段,无法模拟充电状态");
    }
    const audio0 = await measureMainFrames(ctx.api, 1500, sr);
    checks.push(audioCheck("插拔前有输出", audio0, 0.85));

    try {
      for (const ac of [1, 0]) {
        const label = ac === 1 ? "插上充电器" : "拔掉充电器";
        selfActivity.mark();
        const set = await ctx.adb.run(["shell", "dumpsys battery set ac " + ac], 15000);
        const out = (set.out + "\n" + set.err).trim();
        ctx.evidence.step("battery-set-ac" + ac, { code: set.code, out: out.slice(0, 200) });
        if (/Error|Exception|Unknown/i.test(out)) {
          return await skipEnv("该 ROM 不支持 dumpsys battery set ac: " + out.slice(0, 200));
        }
        await sleep(2500);
        const st = await ctx.api.state();
        checks.push(
          check(
            label + "后仍在循环播放",
            st.playback.playing === true && st.playback.engine === "loop",
            "playing=" + st.playback.playing + " engine=" + st.playback.engine,
          ),
        );
        const audio = await measureMainFrames(ctx.api, 1800, sr);
        checks.push(audioCheck(label + "后声音不断", audio, 0.85, "电源状态变化不应打断循环播放"));
        checks.push(mainWindowCheck(label + "后位置仍在 [A,B)", st, song.id));
        checks.push(windowIntactCheck(label + "后 A/B 未变", w0, st));
      }
      return { checks, summary: "ac 1->0 循环未断" };
    } finally {
      selfActivity.mark();
      const reset = await ctx.adb.run(["shell", "dumpsys battery reset"], 15000);
      const batteryAfter = await ctx.adb.shell("dumpsys battery", 20000);
      const resetOk = !/Error|Exception/i.test(reset.out + reset.err);
      ctx.evidence.step("battery-reset", {
        out: (reset.out + reset.err).slice(0, 200),
        acAfter: /AC powered: true/i.test(batteryAfter),
        usbAfter: /USB powered: true/i.test(batteryAfter),
      });
      checks.push(check("恢复电池状态(reset)", resetOk, "AC/USB 覆盖已 reset"));
      const stCleanup = await ctx.api.state().catch(() => null);
      if (stCleanup && stCleanup.playback?.playing !== true) await ensurePlaying(ctx);
    }
  },
};

// ---------------------------------------------------------------------------
// 场景 52：音量键调整 -> 循环不受影响
// 想抓:按音量键(外部硬件事件)把播放暂停/静音/丢焦点,或位置、A/B 被改。
// 副作用:媒体音量会被调,finally 用原级恢复。
// ---------------------------------------------------------------------------

const sEnv52: Scenario = {
  id: 52,
  key: "env-volume-keys",
  name: "音量键调整",
  tag: "环境",
  visibleStateChanges: true,
  timeoutMs: 120000,
  async run(ctx) {
    const checks: Check[] = [];
    const song = ctx.songs.primary;
    const sr = ctx.sampleRateFor(song.id);
    await ctx.resetForMain();
    await requireSourceRoute(ctx);
    const base = await enterLoopPlaying(ctx, song);
    const w0: Win = { songId: song.id, aMs: base.a, bMs: base.b };
    const vol0 = await mediaVolume(ctx);
    const audio0 = await measureMainFrames(ctx.api, 1500, sr);
    checks.push(audioCheck("调音量前有输出", audio0, 0.85));
    ctx.evidence.step("volume-before", { vol0 });

    try {
      for (let i = 0; i < 3; i++) {
        selfActivity.mark();
        await ctx.adb.shell("input keyevent KEYCODE_VOLUME_UP", 15000);
        await sleep(250);
      }
      await sleep(800);
      const volUp = await mediaVolume(ctx);
      const stUp = await ctx.api.state();
      if (vol0 !== null && volUp !== null) {
        checks.push(check("音量键生效(媒体音量变化)", volUp !== vol0, "volume " + vol0 + " -> " + volUp));
      } else {
        checks.push(
          pendingCheck(
            "音量键生效(媒体音量变化)",
            "读不到媒体音量(media volume --stream 3 --get 不支持)",
            "device-volume-readback",
          ),
        );
      }
      const audioUp = await measureMainFrames(ctx.api, 1600, sr);
      checks.push(audioCheck("按音量键后仍有输出", audioUp, 0.85, "按音量键把播放弄停了:音量键应只改音量"));
      checks.push(
        check(
          "按音量键后仍在循环",
          stUp.playback.engine === "loop" && loopWindowOf(stUp)?.songId === song.id,
          "engine=" + stUp.playback.engine,
        ),
      );
      checks.push(mainWindowCheck("按音量键后位置仍在 [A,B)", stUp, song.id));
      checks.push(windowIntactCheck("按音量键后 A/B 未变", w0, stUp));

      for (let i = 0; i < 3; i++) {
        selfActivity.mark();
        await ctx.adb.shell("input keyevent KEYCODE_VOLUME_DOWN", 15000);
        await sleep(250);
      }
      await sleep(600);
      const audioDown = await measureMainFrames(ctx.api, 1500, sr);
      checks.push(audioCheck("调回音量后仍有输出", audioDown, 0.85));
      const stDown = await ctx.api.state();
      checks.push(check("调回音量后仍在播放", stDown.playback.playing === true, "playing=" + stDown.playback.playing));

      return {
        checks,
        summary:
          "volume " + String(vol0) + "->" + String(volUp) + "->" + String(await mediaVolume(ctx)) +
          "; audio=" + round(audioUp.ratio * 100, 0) + "%",
      };
    } finally {
      // 恢复原音量(能读到才恢复)
      if (vol0 !== null) {
        selfActivity.mark();
        const restore = await ctx.adb.run(["shell", "media volume --stream 3 --set " + vol0], 15000);
        const volNow = await mediaVolume(ctx);
        const ok = volNow === vol0;
        ctx.evidence.step("restore-volume", {
          out: (restore.out + restore.err).slice(0, 200),
          target: vol0,
          now: volNow,
          ok,
        });
        checks.push(check("恢复原媒体音量", ok, "volume=" + String(volNow) + " (原 " + String(vol0) + ")"));
      }
    }
  },
};

export const ENV_SCENARIOS: Scenario[] = [
  sEnv41,
  sEnv42,
  sEnv43,
  sEnv44,
  sEnv45,
  sEnv46,
  sEnv47,
  sEnv48,
  sEnv49,
  sEnv50,
  sEnv51,
  sEnv52,
];

// ---------------------------------------------------------------------------
// 离线自检用预算表(check-env.ts 校验「超时是否合理」「要不要 UI」「会不会改设备状态」)
// ---------------------------------------------------------------------------

export interface EnvScenarioBudget {
  /** 预期耗时(ms,含起播/取证/等待);check-env 用它校验 timeoutMs 留够余量。 */
  expectedMs: number;
  /** 依赖应用 UI(点击/结果页):--skip-ui 时请把 id 加进 runner 的 UI_SCENARIOS。 */
  ui: boolean;
  /** 本场景会临时改动的设备状态,必须在 finally 里恢复;空数组 = 只读设备。 */
  deviceMutations: string[];
  /** 可能因设备/ROM 能力缺失而 SKIP 的原因(报告里按这个归纳)。 */
  skipProne: string[];
  /** 一句话:这条场景想抓什么 bug(与文件里的「想抓:」注释一致)。 */
  catches: string;
}

export const ENV_SCENARIO_BUDGETS: Record<number, EnvScenarioBudget> = {
  41: {
    expectedMs: 55000,
    ui: false,
    deviceMutations: [],
    skipProne: ["ROM 不响应 cmd media_session dispatch / 媒体键"],
    catches: "通知栏/媒体键控制把循环窗口弄丢、继续后播放头跑到 [A,B) 外、或干脆不出声",
  },
  42: {
    expectedMs: 40000,
    ui: false,
    deviceMutations: [],
    skipProne: ["ROM 不允许 shell 注入 HEADSET_PLUG / AUDIO_BECOMING_NOISY"],
    catches: "拔耳机不暂停(继续外放)、暂停后位置/窗口被改、插回自动续播、手动继续回不到循环",
  },
  43: {
    expectedMs: 80000,
    ui: false,
    deviceMutations: ["启动并 force-stop 第三方播放器"],
    skipProne: ["没有可播放音频", "没有能接收 VIEW audio/* 的第三方播放器"],
    catches: "外部 App 抢焦点后两个 App 同时出声、焦点回落后掉出循环或播放头越界、手动继续起不来",
  },
  44: {
    expectedMs: 50000,
    ui: false,
    deviceMutations: ["锁屏/亮屏(结束时恢复亮屏解锁)", "解锁(只 dismiss keyguard,不改锁屏设置)"],
    skipProne: ["ROM 拒绝 shell 熄屏", "设备有安全锁屏导致无法解锁"],
    catches: "锁屏时音乐被系统回收、锁屏/解锁把播放器重建导致位置重置或掉出循环",
  },
  45: {
    expectedMs: 65000,
    ui: true,
    deviceMutations: ["旋转方向(恢复原 accelerometer_rotation/user_rotation)"],
    skipProne: ["ROM 不支持 wm/cmd window user-rotation", "结果页预览未起播"],
    catches: "旋转重建后预览不恢复、A/B 被重置、播放头跑到窗口外、重建后断流",
  },
  46: {
    expectedMs: 50000,
    ui: false,
    deviceMutations: ["进入分屏(退出时拉回全屏)"],
    skipProne: ["am start 不支持 --windowingMode", "ROM 没有真正进入分屏"],
    catches: "分屏/布局重排打断循环播放(掉 loop / 没声 / 播放头越界)",
  },
  47: {
    expectedMs: 100000,
    ui: false,
    deviceMutations: ["切后台 60s(结束时恢复前台)"],
    skipProne: [],
    catches: "后台被冻结/回收(音乐断)、位置重置、进程重启、回前台状态不一致",
  },
  48: {
    expectedMs: 45000,
    ui: false,
    deviceMutations: [],
    skipProne: ["ROM 不支持 am send-trim-memory"],
    catches: "系统 trim 把环带缓存回收后断流/降级、位置与窗口乱掉、OOM 或进程重启",
  },
  49: {
    expectedMs: 70000,
    ui: false,
    deviceMutations: [
      "global low_power(恢复原值)",
      "deviceidle force-idle/unforce(退出时还原)",
      "熄屏/亮屏",
    ],
    skipProne: ["ROM 拒绝 force-idle", "熄屏失败"],
    catches: "省电/Doze 下前台音乐被冻结、Doze 退出后循环回不去、被暂停后位置/窗口丢失",
  },
  50: {
    expectedMs: 240000,
    ui: false,
    deviceMutations: ["重启设备(不可逆,必须单独跑)"],
    skipProne: ["默认 SKIP,需要 HESI_ENV_REBOOT=1"],
    catches: "重启后 A/B 没持久化/被改写、循环链路坏掉",
  },
  51: {
    expectedMs: 35000,
    ui: false,
    deviceMutations: ["dumpsys battery set ac(恢复时 dumpsys battery reset)"],
    skipProne: ["dumpsys battery 不支持覆盖充电状态"],
    catches: "充电器插拔触发系统行为后音乐断、循环掉、位置乱",
  },
  52: {
    expectedMs: 35000,
    ui: false,
    deviceMutations: ["媒体音量(恢复原级)"],
    skipProne: ["读不到媒体音量"],
    catches: "按音量键把播放弄停/丢焦点,或位置、A/B 被改",
  },
};
