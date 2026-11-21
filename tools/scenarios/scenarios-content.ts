#!/usr/bin/env bun
/**
 * tools/scenarios/scenarios-content.ts —— 「内容真值」场景（issue 00024 追加：场景 20~25，覆盖 00027）。
 *
 * 为什么需要这一组
 * ----------------
 * 00027 = 「进度条对、声音错」：display 折回 A、A/B 不变、outputFrames 增长、engine=loop+source，
 * 现有 18 条场景的判据（元数据层）全部 PASS。真正的错误在**内容层**：
 * 写进 AudioTrack 的 PCM 不是在显示位置该听到的那一段。本组场景把
 * 「实际写出的 PCM」与 `/debug/decode-range` 从原始文件解出的「本应听到的那段」
 * 做归一化互相关（NCC，见 content-probe.ts），峰值 >= 0.90 才算内容一致。
 *
 * 场景表
 * ------
 *   20 seek-past-b-content       seek 到 B-5s -> 跨过 B -> 接缝前后各一次内容真值（直接覆盖 00027）
 *   21 seek-near-b-content       seek 到 B-1s -> 跨 B -> 内容真值（更贴边界）
 *   22 loop-one-lap-content      seek A+1s -> 播完一整圈（跨 B 回 A）-> 起点/临 B/回绕后三次抽查
 *   23 seek-boundary-matrix      A-1s / A / A+1 / B-1 / B / B+1：逐点校验 display + 内容
 *   24 seek-random-x5-content    环带内 5 次（定种子）随机 seek，每次内容真值
 *   25 ended-progress-zero       环带播到尾（ENDED 风险区）：断言不出现 ENDED / 进度归零
 *
 * 判据（每条内容抽查）
 * -------------------
 *   peak >= 0.90 且 |偏移| <= 600ms   -> match   （听到的 == 显示的）
 *   peak 0.50~0.90                    -> suspect -> FAIL（可疑，先排除对齐/音量）
 *   peak < 0.50                       -> mismatch -> FAIL（音画不符；失败时做宽域定位，
 *                                        报告「实际最像环带 / B 之后 / A 之前 的哪一段」）
 *   探针不可用（tap 没写出帧/没有 decode-range）-> PENDING（依赖 tap-content-probe）
 *
 * 对齐手段（不改 app）
 * -------------------
 * 每次探测先抓一段 tap（默认 900ms），取末尾 300ms；期望内容 = 窗口起点时刻的 display + 基线偏移。
 * 基线偏移由 calibrate() 在环带中段稳态测出来（稳态 = 写入超前播放 + 采样相位，通常 100~500ms），
 * 之后任何一次偏移跳变都说明「内容与显示脱钩」——00027 正是偏移跳到数秒且峰值塌陷。
 * 判据本身必须先自证：基线峰值必须 >= 0.95（否则整条场景判 FAIL，说明判据不可信），
 * 本次追加的验收口径「正常基线峰值 >= 0.95，不误杀」就落在这一条上。
 *
 * 注册方式（由 lead 统一合并，本文件不改 scenarios.ts / run-scenarios.ts）
 * ---------------------------------------------------------------------
 *   import { CONTENT_SCENARIOS } from "./scenarios-content";
 *   export const SCENARIOS: Scenario[] = [..., ...CONTENT_SCENARIOS];
 *   UI_SCENARIOS（run-scenarios.ts）需要加 20~25 才会被 --skip-ui 分组识别（本组全部依赖 UI/预览页）。
 *
 * 写域：本文件 + content-probe.ts。不改 app；不碰其它 agent 的文件。
 */

import { check, pendingCheck, round, sleep, waitFor } from "./harness";
import type { Check, SongInfo } from "./harness";
import { createProber, defaultWorkDir, PEAK_MATCH } from "./content-probe";
import type { ProbeResult, ProbeStack, TapCapture } from "./content-probe";
import { SkipError } from "./scenarios";
import type { Scenario, ScenarioCtx } from "./scenarios";

// ---------------------------------------------------------------------------
// 常量
// ---------------------------------------------------------------------------

const DWELL_MS = 900;
const SAMPLES_MS = 300;
const DRIFT_TOL_MS = 600;
const BASELINE_PEAK_MIN = 0.95;

// ---------------------------------------------------------------------------
// 小工具
// ---------------------------------------------------------------------------

interface ContentLoop {
  song: SongInfo;
  songPath: string;
  sampleRate: number;
  aMs: number;
  bMs: number;
  durationMs: number;
  L: number;
  snap: any;
}

function decodable(s: SongInfo): boolean {
  return /.(mp3|wav|m4a|aac|flac|ogg|opus)$/i.test(s.path);
}

/** 从曲库里挑一首「适合做内容真值」的已确认循环歌（A>=3s、B 距尾 >3s、可解码）。 */
async function selectContentSong(
  ctx: ScenarioCtx,
  opts: { minBandMs?: number; maxBandMs?: number; minAMs?: number; nearTail?: boolean } = {},
): Promise<SongInfo> {
  const all = await ctx.api.songsAll();
  const minBand = opts.minBandMs ?? 8000;
  const minA = opts.minAMs ?? 3000;
  let cands = all.filter(
    (s) =>
      s.hasLoop &&
      s.loopConfirmed &&
      s.loopStartMs != null &&
      s.loopEndMs != null &&
      Number(s.loopStartMs) >= minA &&
      Number(s.loopEndMs) - Number(s.loopStartMs) >= minBand &&
      s.durationMs > Number(s.loopEndMs) + 3000 &&
      decodable(s),
  );
  if (opts.maxBandMs != null) {
    const narrowed = cands.filter((s) => Number(s.loopEndMs) - Number(s.loopStartMs) <= Number(opts.maxBandMs));
    if (narrowed.length > 0) cands = narrowed;
  }
  if (cands.length === 0) {
    throw new SkipError(
      "曲库里没有合适的已确认循环歌（需要 A>=" + minA + "ms、环长>=" + minBand + "ms、B 距文件尾 >3s、" +
        "mp3/wav/m4a 等可解码格式）",
    );
  }
  if (opts.nearTail) {
    cands.sort((a, b) => a.durationMs - Number(a.loopEndMs) - (b.durationMs - Number(b.loopEndMs)) || a.id - b.id);
  } else {
    cands.sort(
      (a, b) => Number(a.loopEndMs) - Number(a.loopStartMs) - (Number(b.loopEndMs) - Number(b.loopStartMs)) || a.id - b.id,
    );
  }
  return cands[0];
}

/**
 * 仅供离线干跑/自检注入 tap 取回函数（真机路径不用它）。
 * 生产行为：null = 走 adb exec-out run-as；有值 = 直接返回内存里的 WAV/JSONL。
 */
let contentPullOverride: ((remoteName: string) => Promise<any>) | null = null;

export function setContentPullForTest(pull: ((remoteName: string) => Promise<any>) | null): void {
  contentPullOverride = pull;
}

function stackFor(ctx: ScenarioCtx): ProbeStack {
  return createProber(ctx.cfg, ctx.api, {
    workDir: defaultWorkDir(ctx.cfg),
    log: ctx.log,
    pull: contentPullOverride ?? undefined,
  });
}

async function previewSnap(ctx: ScenarioCtx): Promise<any> {
  const p = await ctx.api.preview();
  return p.available ? p.snapshot : null;
}

/** 预览页的实时展示位置（liveDisplayMs 优先，避开 10Hz ticker 的滞后）。 */
async function previewDisplay(ctx: ScenarioCtx): Promise<number | null> {
  const snap = await previewSnap(ctx);
  if (!snap) return null;
  const v = snap.liveDisplayMs ?? snap.displayMs;
  return v == null ? null : Number(v);
}

/** 主播放器的展示位置（/state 已按窗口折返）。 */
async function mainDisplay(ctx: ScenarioCtx): Promise<number | null> {
  const s = await ctx.api.state();
  const v = s.playback?.positionMs;
  return v == null ? null : Number(v);
}

async function ensurePreviewPlaying(ctx: ScenarioCtx): Promise<boolean> {
  for (let i = 0; i < 10; i++) {
    const snap = await previewSnap(ctx);
    if (snap && snap.isPlaying === true) return true;
    await sleep(250);
  }
  return false;
}

/** 进循环页 + 切「仅循环段」，返回环带事实。 */
async function openLoopOnly(ctx: ScenarioCtx, song: SongInfo): Promise<ContentLoop> {
  await ctx.resetForMain();
  await ctx.ensureLoopPage(song.id);
  const labels = ctx.uiLabels();
  if (labels.length > 0 && !labels.includes("仅循环段")) {
    throw new SkipError("结果页没有「仅循环段」按钮（现有文本: " + labels.slice(0, 16).join(" | ") + "）");
  }
  await ctx.uiTap("仅循环段");
  const ready = await waitFor(
    async () => previewSnap(ctx),
    (s) => s != null && s.mode === "LOOP_ONLY" && s.isPlaying === true,
    9000,
    100,
  );
  if (!ready.ok || !ready.value) throw new SkipError("结果页「仅循环段」9s 内未起播");
  const snap = ready.value;
  if (snap.engine !== "SOURCE") {
    throw new SkipError("预览引擎不是 SOURCE(engine=" + snap.engine + ")：内容真值需要 tap 挂在 SOURCE sink 上");
  }
  const info = await ctx.api.song(song.id);
  const aMs = Math.round(Number(info.loopStartMs));
  const bMs = Math.round(Number(info.loopEndMs));
  const durationMs = Number(info.durationMs);
  const L = bMs - aMs;
  if (!(L > 0)) throw new SkipError("环带长度非法: A=" + aMs + " B=" + bMs);
  const songPath = String(info.path ?? song.path ?? "");
  if (!songPath) throw new SkipError("拿不到歌曲文件路径(无法用 decode-range 解参考 PCM)");
  const sampleRate = Number(info.sampleRate ?? ctx.sampleRateFor(song.id)) || 44100;
  ctx.evidence.step("open-loop-only", { songId: song.id, aMs, bMs, L, durationMs, sampleRate, songPath });
  ctx.log("环带: " + songPath + "  A=" + aMs + " B=" + bMs + " L=" + round(L / 1000, 1) + "s sr=" + sampleRate);
  // 主播放器与预览播放器共用同一个 tap 文件：主播放器还在出声就会把两个引擎的 PCM 混在一起。
  // 只发一次 play-pause（反复 toggle 会把已经暂停的播放器重新播起来），然后等它真的停下。
  const st = await ctx.api.state();
  if (st.playback?.playing === true) {
    ctx.log("主播放器仍在播放，先暂停（避免两个引擎同时写 tap）");
    await ctx.api.post("/player/play-pause").catch(() => null);
    for (let i = 0; i < 8; i++) {
      await sleep(250);
      const s2 = await ctx.api.state();
      if (s2.playback?.playing === false) break;
    }
    const s3 = await ctx.api.state();
    if (s3.playback?.playing === true) {
      ctx.log("警告: 主播放器仍在播放，tap 可能混入两路 PCM（内容真值会显示 configure>1 的告警）");
    }
  }
  return { song, songPath, sampleRate, aMs, bMs, durationMs, L, snap };
}

function contentCheck(label: string, r: ProbeResult): Check {
  const loc = r.locatedContentMs == null ? "n/a" : Math.round(r.locatedContentMs) + "ms";
  const drift = r.driftMs == null ? "n/a" : Math.round(r.driftMs) + "ms";
  const wide =
    r.wide && r.wide.length > 0
      ? "；实际最像 " + r.wide[0].region + " @" + Math.round(r.wide[0].positionMs) + "ms(peak " +
        r.wide[0].peak.toFixed(3) + ")"
      : "";
  const detail =
    "peak=" + r.peak.toFixed(3) + " 期望=" + Math.round(r.expectedContentMs) + "ms 定位=" + loc +
    " 偏差=" + drift + " 参考片=" + r.chunks + " 窗口=" + r.capture.windowMs + "ms" + wide;
  if (r.verdict === "unavailable") {
    return pendingCheck(label, detail + " — " + r.reason, "tap-content-probe", "tap 没写出帧/没有 decode-range；确认 debug 包 + SOURCE 路线");
  }
  if (r.verdict === "match") return check(label, true, detail);
  const hint =
    r.verdict === "mismatch"
      ? "内容层不符(00027 型)：写出的 PCM 在环带里找不到；看 wide 定位是否落在 B 之后/环带之外"
      : "可疑：先排除基线对齐误差（基线峰值应 >=0.95）、音量差、降采样";
  return check(label, false, detail + " — " + r.reason, hint);
}

interface ProbeAtResult {
  capture: TapCapture;
  result: ProbeResult;
  displayRefMs: number;
  displayAtStopMs: number | null;
  contentCheck: Check;
}

async function probeAt(
  ctx: ScenarioCtx,
  stack: ProbeStack,
  li: ContentLoop,
  opts: {
    label: string;
    offsetMs: number;
    mode?: "band" | "file";
    wide?: boolean;
    driftTolMs?: number;
    target?: "preview" | "main";
    samplesMs?: number;
    dwellMs?: number;
  },
): Promise<ProbeAtResult> {
  const target = opts.target ?? "preview";
  const capture = await stack.prober.capture({
    target,
    samplesMs: opts.samplesMs ?? SAMPLES_MS,
    dwellMs: opts.dwellMs ?? DWELL_MS,
    workDir: defaultWorkDir(ctx.cfg),
    label: opts.label,
  });
  const displayRef = capture.displayRef?.displayMs;
  if (displayRef == null) {
    throw new SkipError("抓取期间没有 display 采样(" + capture.displaySource + ")，无法定位期望内容");
  }
  const expected = displayRef + opts.offsetMs;
  const result = await stack.truth.probeCapture(capture, {
    songPath: li.songPath,
    aMs: li.aMs,
    bMs: li.bMs,
    expectedContentMs: expected,
    mode: opts.mode ?? "band",
    wide: opts.wide === true,
    driftTolMs: opts.driftTolMs ?? DRIFT_TOL_MS,
    caption: opts.label,
  });
  const content = contentCheck(opts.label + " 内容真值", result);
  ctx.evidence.step("content-probe", {
    label: opts.label,
    expectedContentMs: expected,
    displayRefMs: displayRef,
    displayAtStopMs: capture.displayAtStop?.displayMs ?? null,
    offsetMs: opts.offsetMs,
    mode: opts.mode ?? "band",
    verdict: result.verdict,
    peak: result.peak,
    locatedContentMs: result.locatedContentMs,
    locatedBandMs: result.locatedBandMs,
    driftMs: result.driftMs,
    chunks: result.chunks,
    wide: result.wide,
    capture: result.capture,
    wav: capture.wavPath,
    jsonl: capture.jsonlPath,
    seams: capture.seams.length,
    flushCount: capture.flushCount,
  });
  ctx.log(
    "  " + opts.label + ": " + result.verdict + " peak=" + result.peak.toFixed(3) +
      " display=" + Math.round(displayRef) + "ms 期望=" + Math.round(expected) + "ms" +
      " 偏差=" + (result.driftMs == null ? "n/a" : Math.round(result.driftMs) + "ms") +
      (capture.seams.length > 0 ? " seams=" + capture.seams.length : ""),
  );
  return {
    capture,
    result,
    displayRefMs: displayRef,
    displayAtStopMs: capture.displayAtStop?.displayMs ?? null,
    contentCheck: content,
  };
}

/** 基线校准：稳态下测出「写入超前」偏移，并自证判据峰值 >= 0.95。 */
async function calibrate(
  ctx: ScenarioCtx,
  stack: ProbeStack,
  li: ContentLoop,
  opts: { label?: string; seekMid?: boolean } = {},
): Promise<{ offsetMs: number; checks: Check[]; results: ProbeResult[] }> {
  const label = opts.label ?? "基线";
  if (opts.seekMid !== false) {
    const mid = Math.round(li.aMs + li.L / 2);
    await ctx.previewSeek(mid);
    await sleep(900);
  }
  const first = await probeAt(ctx, stack, li, {
    label: label + "-校准",
    offsetMs: 0,
    wide: false,
    // 校准时不拿偏移判对错：这一枪就是为了测偏移。
    driftTolMs: 5000,
  });
  const checks: Check[] = [];
  checks.push(
    check(
      label + " 判据自证:峰值 >= " + BASELINE_PEAK_MIN,
      first.result.peak >= BASELINE_PEAK_MIN,
      "peak=" + first.result.peak.toFixed(3) + " (期望位置 " + Math.round(first.result.expectedContentMs) + "ms)",
      first.result.peak < BASELINE_PEAK_MIN
        ? "基线都匹配不上 -> 内容真值判据不可信（检查:是否 SOURCE、tap 是否被两个引擎同时写、窗口是否跨了接缝）"
        : undefined,
    ),
  );
  const offset = first.result.driftMs ?? 0;
  checks.push(
    check(
      label + " 写入超前偏移量级合理",
      Math.abs(offset) <= 800,
      "offset=" + Math.round(offset) + "ms（= 写入超前播放 + 采样相位）",
      Math.abs(offset) > 800 ? "偏移过大:先确认窗口起点锚点(displayRef)与 tap 写入是否对齐" : undefined,
    ),
  );
  const second = await probeAt(ctx, stack, li, { label: label + "-复核", offsetMs: offset });
  checks.push(second.contentCheck);
  return { offsetMs: offset, checks, results: [first.result, second.result] };
}

/** 等 display 从 B 侧折回 A 侧（下降幅度 > L/2 记为一次环回）。 */
async function waitDisplayWrap(
  ctx: ScenarioCtx,
  li: ContentLoop,
  opts: { timeoutMs: number; sampleMs?: number; target?: "preview" | "main" },
): Promise<{ ok: boolean; beforeMs: number | null; afterMs: number | null; series: { atMs: number; displayMs: number }[]; elapsedMs: number }> {
  const target = opts.target ?? "preview";
  const sampleMs = opts.sampleMs ?? 100;
  const t0 = Date.now();
  const series: { atMs: number; displayMs: number }[] = [];
  let prev: number | null = null;
  while (Date.now() - t0 < opts.timeoutMs) {
    const d = target === "preview" ? await previewDisplay(ctx) : await mainDisplay(ctx);
    if (d != null) {
      series.push({ atMs: Date.now() - t0, displayMs: d });
      if (prev != null && d < prev - li.L / 2) {
        return { ok: true, beforeMs: prev, afterMs: d, series, elapsedMs: Date.now() - t0 };
      }
      prev = d;
    }
    await sleep(sampleMs);
  }
  return { ok: false, beforeMs: prev, afterMs: null, series, elapsedMs: Date.now() - t0 };
}

/** 等到 display 涨过阈值（用于「临 B」抽点）。 */
async function waitDisplayAbove(
  ctx: ScenarioCtx,
  thresholdMs: number,
  timeoutMs: number,
  target: "preview" | "main" = "preview",
): Promise<{ ok: boolean; displayMs: number | null; elapsedMs: number }> {
  const t0 = Date.now();
  let last: number | null = null;
  while (Date.now() - t0 < timeoutMs) {
    const d = target === "preview" ? await previewDisplay(ctx) : await mainDisplay(ctx);
    if (d != null) {
      last = d;
      if (d >= thresholdMs) return { ok: true, displayMs: d, elapsedMs: Date.now() - t0 };
    }
    await sleep(120);
  }
  return { ok: false, displayMs: last, elapsedMs: Date.now() - t0 };
}

/** 「进度不得突变到 0」——A 不贴近 0 时，任何一次 display<=60ms 都算异常。 */
function zeroGuard(label: string, series: { atMs: number; displayMs: number }[], aMs: number): Check {
  if (aMs <= 300) {
    return check(label, true, "A=" + aMs + "ms 贴近 0，环回本来就回 0，跳过零判据");
  }
  const bad = series.filter((s) => s.displayMs <= 60);
  const detail =
    series.length === 0
      ? "无采样"
      : "采样 " + series.length + " 次, min=" + Math.round(Math.min(...series.map((s) => s.displayMs))) +
        "ms, 越界(<=60ms)=" + bad.length;
  return check(
    label,
    bad.length === 0,
    detail,
    bad.length > 0 ? "位置突变到 0（00027 的第二个现象）：源被换成整曲并在文件尾 ENDED 后归零" : undefined,
  );
}

function summarizeContent(results: ProbeResult[]): string {
  const peaks = results.map((r) => r.peak.toFixed(2));
  const drifts = results.map((r) => (r.driftMs == null ? "n/a" : Math.round(r.driftMs) + "ms"));
  return "peak[" + peaks.join(",") + "] drift[" + drifts.join(",") + "]";
}

// ---------------------------------------------------------------------------
// 场景 20：seek 到 B-5s -> 跨过 B -> 内容真值（直接覆盖 00027）
// ---------------------------------------------------------------------------

const s20: Scenario = {
  id: 20,
  key: "seek-past-b-content",
  name: "seek 到 B-5s -> 跨 B -> 内容真值(00027)",
  tag: "内容",
  exclusive: true,
  timeoutMs: 240000,
  async run(ctx) {
    const checks: Check[] = [];
    const song = await selectContentSong(ctx, { minBandMs: 12000 });
    const stack = stackFor(ctx);
    const li = await openLoopOnly(ctx, song);
    if (li.L < 6000) throw new SkipError("环带只有 " + li.L + "ms，B-5s 会落到 A 之前");
    const cal = await calibrate(ctx, stack, li, { label: "环带中段基线" });
    checks.push(...cal.checks);
    const offset = cal.offsetMs;

    const target = Math.max(li.aMs, li.bMs - 5000);
    const seek = await ctx.previewSeek(target);
    checks.push(
      check(
        "seek 到 B-5s 落点正确",
        seek.displayAfterMs != null && Math.abs(Number(seek.displayAfterMs) - target) <= 1200,
        "target=" + target + " display=" + seek.displayAfterMs + " playout=" + (seek.playoutMs ?? "n/a") + "ms",
      ),
    );
    await sleep(750);
    // 接缝前抽查：此时 display 应在 B-5s 附近，内容必须是环带里的这一段。
    const before = await probeAt(ctx, stack, li, { label: "跨接缝前(B-5s)", offsetMs: offset });
    checks.push(before.contentCheck);

    const wrap = await waitDisplayWrap(ctx, li, { timeoutMs: 14000 });
    checks.push(
      check(
        "跨过 B 后 display 折回 A",
        wrap.ok && wrap.afterMs != null && wrap.afterMs <= li.aMs + 4000,
        wrap.ok
          ? "before=" + Math.round(Number(wrap.beforeMs)) + "ms -> after=" + Math.round(Number(wrap.afterMs)) + "ms (A=" + li.aMs + ")"
          : "14s 内没有观察到 display 折回",
        wrap.ok ? undefined : "B-5s 起播 5s 后应跨过 B；没折回说明环回没发生（engine/源被换掉）",
      ),
    );
    checks.push(zeroGuard("跨接缝期间 position 不归零", wrap.series, li.aMs));
    await sleep(900);
    // 接缝后抽查：这就是 00027 的现场——显示已回 A，声音必须也是 A 之后的内容。
    const after = await probeAt(ctx, stack, li, { label: "跨接缝后(回到 A)", offsetMs: offset, wide: true });
    checks.push(after.contentCheck);
    checks.push(
      check(
        "跨接缝后 display 在 [A, A+4s]",
        after.displayRefMs >= li.aMs - 500 && after.displayRefMs <= li.aMs + 4500,
        "display=" + Math.round(after.displayRefMs) + "ms A=" + li.aMs + " B=" + li.bMs,
      ),
    );
    const endSnap = await previewSnap(ctx);
    checks.push(
      check(
        "跨接缝后仍是环带源(engine=SOURCE/band)",
        endSnap != null && endSnap.engine === "SOURCE" && endSnap.sourceKind === "band",
        "engine=" + endSnap?.engine + " sourceKind=" + endSnap?.sourceKind,
        endSnap?.sourceKind !== "band" ? "源被换成了 native/m3：00027 的「源被换掉」分支" : undefined,
      ),
    );
    return {
      checks,
      summary:
        "B-5s 跨接缝: " + summarizeContent([before.result, after.result]) + "; 偏移基线=" + Math.round(offset) + "ms",
    };
  },
};

// ---------------------------------------------------------------------------
// 场景 21：seek 到 B-1s -> 跨 B -> 内容真值（更贴边界）
// ---------------------------------------------------------------------------

const s21: Scenario = {
  id: 21,
  key: "seek-near-b-content",
  name: "seek 到 B-1s -> 跨 B -> 内容真值",
  tag: "内容",
  exclusive: true,
  timeoutMs: 210000,
  async run(ctx) {
    const checks: Check[] = [];
    const song = await selectContentSong(ctx, { minBandMs: 10000 });
    const stack = stackFor(ctx);
    const li = await openLoopOnly(ctx, song);
    if (li.L < 4000) throw new SkipError("环带只有 " + li.L + "ms，B-1s 太贴近 A");
    const cal = await calibrate(ctx, stack, li, { label: "基线" });
    checks.push(...cal.checks);
    const offset = cal.offsetMs;

    const target = li.bMs - 1000;
    const seek = await ctx.previewSeek(target);
    checks.push(
      check(
        "seek 到 B-1s 落点正确",
        seek.displayAfterMs != null && Math.abs(Number(seek.displayAfterMs) - target) <= 800,
        "target=" + target + " display=" + seek.displayAfterMs,
      ),
    );
    await sleep(450);
    const before = await probeAt(ctx, stack, li, { label: "跨接缝前(B-1s)", offsetMs: offset });
    checks.push(before.contentCheck);
    const wrap = await waitDisplayWrap(ctx, li, { timeoutMs: 9000, sampleMs: 80 });
    checks.push(
      check(
        "B-1s 起播后 9s 内折回 A",
        wrap.ok && wrap.afterMs != null && wrap.afterMs <= li.aMs + 3000,
        wrap.ok ? "after=" + Math.round(Number(wrap.afterMs)) + "ms (A=" + li.aMs + ")" : "未观察到折回",
      ),
    );
    checks.push(zeroGuard("跨接缝期间 position 不归零", wrap.series, li.aMs));
    await sleep(700);
    const after = await probeAt(ctx, stack, li, { label: "跨接缝后(回到 A)", offsetMs: offset, wide: true });
    checks.push(after.contentCheck);
    return {
      checks,
      summary: "B-1s 跨接缝: " + summarizeContent([before.result, after.result]) + "; 偏移基线=" + Math.round(offset) + "ms",
    };
  },
};

// ---------------------------------------------------------------------------
// 场景 22：seek A+1s -> 播完一整圈（跨 B 回 A）-> 内容真值
// ---------------------------------------------------------------------------

const s22: Scenario = {
  id: 22,
  key: "loop-one-lap-content",
  name: "A+1s 起播 -> 一整圈 -> 内容真值",
  tag: "内容",
  exclusive: true,
  timeoutMs: 300000,
  async run(ctx) {
    const checks: Check[] = [];
    // 一整圈要等 L 秒：挑最短的可行环带（<=25s），否则场景时间不可控。
    const song = await selectContentSong(ctx, { minBandMs: 8000, maxBandMs: 25000 });
    const stack = stackFor(ctx);
    const li = await openLoopOnly(ctx, song);
    if (li.L > 40000) throw new SkipError("最短可用环带仍为 " + round(li.L / 1000, 1) + "s，整圈场景太慢");

    const cal = await calibrate(ctx, stack, li, { label: "基线" });
    checks.push(...cal.checks);
    const offset = cal.offsetMs;

    const start = li.aMs + 1000;
    const seek = await ctx.previewSeek(start);
    checks.push(
      check(
        "seek 到 A+1s 落点正确",
        seek.displayAfterMs != null && Math.abs(Number(seek.displayAfterMs) - start) <= 900,
        "target=" + start + " display=" + seek.displayAfterMs,
      ),
    );
    await sleep(700);
    const atStart = await probeAt(ctx, stack, li, { label: "整圈起点(A+1s)", offsetMs: offset });
    checks.push(atStart.contentCheck);

    // 临 B 抽查（接缝前）
    const nearB = await waitDisplayAbove(ctx, li.bMs - 2500, li.L + 12000);
    checks.push(
      check("一整圈里 display 走到 B-2.5s", nearB.ok, "display=" + (nearB.displayMs == null ? "null" : Math.round(nearB.displayMs)) + "ms"),
    );
    if (nearB.ok) {
      const beforeWrap = await probeAt(ctx, stack, li, { label: "整圈临 B", offsetMs: offset });
      checks.push(beforeWrap.contentCheck);
    }
    const wrap = await waitDisplayWrap(ctx, li, { timeoutMs: 15000, sampleMs: 120 });
    checks.push(
      check(
        "一整圈后折回 A",
        wrap.ok && wrap.afterMs != null && wrap.afterMs <= li.aMs + 4000,
        wrap.ok
          ? "before=" + Math.round(Number(wrap.beforeMs)) + "ms -> after=" + Math.round(Number(wrap.afterMs)) + "ms"
          : "15s 内未折回",
      ),
    );
    checks.push(zeroGuard("一整圈期间 position 不归零", wrap.series, li.aMs));
    await sleep(800);
    const afterLap = await probeAt(ctx, stack, li, { label: "整圈回绕后", offsetMs: offset, wide: true });
    checks.push(afterLap.contentCheck);
    checks.push(
      check(
        "回绕后 display 在 [A, A+4s]",
        afterLap.displayRefMs >= li.aMs - 500 && afterLap.displayRefMs <= li.aMs + 4500,
        "display=" + Math.round(afterLap.displayRefMs) + "ms",
      ),
    );
    return {
      checks,
      summary:
        "整圈 " + round(li.L / 1000, 1) + "s: 起点/临B/回绕后 " + summarizeContent([atStart.result, afterLap.result]) +
        "; 偏移基线=" + Math.round(offset) + "ms",
    };
  },
};

// ---------------------------------------------------------------------------
// 场景 23：边界矩阵 A-1s / A / A+1 / B-1 / B / B+1
// ---------------------------------------------------------------------------

const s23: Scenario = {
  id: 23,
  key: "seek-boundary-matrix-content",
  name: "边界矩阵(A-1s/A/A+1/B-1/B/B+1) display+内容",
  tag: "内容",
  exclusive: true,
  timeoutMs: 300000,
  async run(ctx) {
    const checks: Check[] = [];
    const song = await selectContentSong(ctx, { minBandMs: 10000, minAMs: 6000 });
    const stack = stackFor(ctx);
    const li = await openLoopOnly(ctx, song);
    const cal = await calibrate(ctx, stack, li, { label: "环带基线" });
    checks.push(...cal.checks);
    const offsetBand = cal.offsetMs;

    // 自由试听（区外）的偏移另测一次：那条链路走 native 源，写入超前与环带不同。
    let offsetFile = offsetBand;
    const freeBase = Math.max(0, li.aMs - 8000);
    if (freeBase > 500) {
      const seekFree = await ctx.previewSeek(freeBase);
      await sleep(800);
      const free = await probeAt(ctx, stack, li, {
        label: "自由试听基线(A-8s)",
        offsetMs: 0,
        mode: "file",
        driftTolMs: 5000,
      });
      checks.push(
        check(
          "自由试听基线峰值 >= " + BASELINE_PEAK_MIN,
          free.result.peak >= BASELINE_PEAK_MIN,
          "peak=" + free.result.peak.toFixed(3) + " display=" + Math.round(free.displayRefMs) + "ms" +
            " outside=" + (seekFree.outsideLoopAfter === true),
        ),
      );
      offsetFile = free.result.driftMs ?? offsetFile;
      await sleep(250);
    }

    const points: { label: string; ms: number; expect: "inside" | "outside" }[] = [
      { label: "A-1s", ms: Math.max(0, li.aMs - 1000), expect: "outside" },
      { label: "A", ms: li.aMs, expect: "inside" },
      { label: "A+1s", ms: li.aMs + 1000, expect: "inside" },
      { label: "B-1s", ms: li.bMs - 1000, expect: "inside" },
      { label: "B", ms: li.bMs, expect: "inside" },
      { label: "B+1s", ms: li.bMs + 1000, expect: "outside" },
    ];
    const results: ProbeResult[] = [];
    for (const p of points) {
      const seek = await ctx.previewSeek(p.ms);
      await sleep(750);
      const snap = await previewSnap(ctx);
      const outside = snap == null ? null : snap.outsideLoop === true;
      const mode: "band" | "file" = outside === true ? "file" : "band";
      const offset = mode === "file" ? offsetFile : offsetBand;
      const displayOk =
        seek.displayAfterMs != null && Math.abs(Number(seek.displayAfterMs) - p.ms) <= 1200;
      checks.push(
        check(
          p.label + " seek 落点/边界语义",
          displayOk && (outside == null || (p.expect === "outside" ? outside : true)),
          "target=" + p.ms + " display=" + seek.displayAfterMs + " outside=" + outside + " mode=" + mode,
          p.expect === "outside" && outside !== true ? "区外 seek 应进入自由试听(outside=true)" : undefined,
        ),
      );
      const probe = await probeAt(ctx, stack, li, {
        label: p.label + " 内容",
        offsetMs: offset,
        mode,
        wide: outside !== true && p.expect === "inside",
      });
      results.push(probe.result);
      checks.push(probe.contentCheck);
      // B 点：display 允许停在 B 或折回 A（边界点语义未定，只要求「在边界上」）。
      if (p.label === "B") {
        const nearEdge = Math.abs(probe.displayRefMs - li.bMs) <= 600 || Math.abs(probe.displayRefMs - li.aMs) <= 600;
        checks.push(check("B 点 display 落在边界(A 或 B)", nearEdge, "display=" + Math.round(probe.displayRefMs) + "ms"));
      }
      await sleep(200);
    }
    ctx.evidence.step("boundary-matrix", {
      points: points.map((p) => ({ label: p.label, ms: p.ms, expect: p.expect })),
      offsetBand,
      offsetFile,
    });
    return {
      checks,
      summary: "6 点边界: " + summarizeContent(results) + "; offsetBand=" + Math.round(offsetBand) + "ms offsetFile=" + Math.round(offsetFile) + "ms",
    };
  },
};

// ---------------------------------------------------------------------------
// 场景 24：环带内连续 5 次随机 seek，每次内容真值
// ---------------------------------------------------------------------------

const s24: Scenario = {
  id: 24,
  key: "seek-random-x5-content",
  name: "环带内 5 次随机 seek 内容真值",
  tag: "内容",
  exclusive: true,
  timeoutMs: 240000,
  async run(ctx) {
    const checks: Check[] = [];
    const song = await selectContentSong(ctx, { minBandMs: 12000 });
    const stack = stackFor(ctx);
    const li = await openLoopOnly(ctx, song);
    const cal = await calibrate(ctx, stack, li, { label: "基线" });
    checks.push(...cal.checks);
    const offset = cal.offsetMs;

    // 定种子伪随机：可复现，且避开贴边（0.15~0.85 L），专抓「某次 seek 之后源被换掉」。
    let seed = 1234567;
    const rnd = () => {
      seed = (seed * 1664525 + 1013904223) >>> 0;
      return seed / 4294967296;
    };
    const results: ProbeResult[] = [];
    const series: { atMs: number; displayMs: number }[] = [];
    for (let i = 1; i <= 5; i++) {
      const target = Math.round(li.aMs + li.L * (0.15 + 0.7 * rnd()));
      const seek = await ctx.previewSeek(target);
      await sleep(800);
      const displayOk = seek.displayAfterMs != null && Math.abs(Number(seek.displayAfterMs) - target) <= 1500;
      checks.push(check("第 " + i + " 次随机 seek 落点(±1.5s)", displayOk, "target=" + target + " display=" + seek.displayAfterMs));
      const probe = await probeAt(ctx, stack, li, {
        label: "第 " + i + " 次 seek 内容(目标 " + target + "ms)",
        offsetMs: offset,
        wide: i >= 4,
      });
      results.push(probe.result);
      checks.push(probe.contentCheck);
      series.push({ atMs: i, displayMs: probe.displayRefMs });
      // 每次 seek 后引擎/源必须还是环带（防止「某次 seek 把源换掉」逃过内容比对）。
      const snap = await previewSnap(ctx);
      checks.push(
        check(
          "第 " + i + " 次 seek 后仍是环带源",
          snap != null && snap.engine === "SOURCE" && snap.sourceKind === "band" && snap.outsideLoop === false,
          "engine=" + snap?.engine + " sourceKind=" + snap?.sourceKind + " outside=" + snap?.outsideLoop,
        ),
      );
      await sleep(150);
    }
    checks.push(zeroGuard("5 次 seek 期间 position 不归零", series, li.aMs));
    return { checks, summary: "5 次随机 seek: " + summarizeContent(results) + "; 偏移基线=" + Math.round(offset) + "ms" };
  },
};

// ---------------------------------------------------------------------------
// 场景 25：环带播到尾 —— 断言不出现 ENDED / 进度归零（00027 的第二个现象）
// ---------------------------------------------------------------------------

const s25: Scenario = {
  id: 25,
  key: "ended-progress-zero",
  name: "环带播到尾:不出现 ENDED/进度归零",
  tag: "内容",
  exclusive: false,
  timeoutMs: 180000,
  async run(ctx) {
    const checks: Check[] = [];
    // 优先挑 B 贴近文件尾的歌：若源被换成整曲，几秒内就会播到文件尾 -> ENDED -> 归零。
    const song = await selectContentSong(ctx, { minBandMs: 8000, nearTail: true });
    const info = await ctx.api.song(song.id);
    const aMs = Math.round(Number(info.loopStartMs));
    const bMs = Math.round(Number(info.loopEndMs));
    const durationMs = Number(info.durationMs);
    if (aMs <= 300) throw new SkipError("这首歌 A=" + aMs + "ms 贴近 0，进度归零判据无意义");

    await ctx.resetForMain();
    const play = await ctx.api.post("/player/play", { songId: song.id, repeatOne: false });
    if (play.status !== 200) throw new Error("/player/play -> " + play.status);
    const ready = await waitFor(
      async () => ctx.api.state(),
      (s) => s.playback?.songId === song.id && s.playback?.playbackState === "ready",
      9000,
      100,
    );
    if (!ready.ok) throw new Error("播放未就绪(songId=" + song.id + ")");
    const enter = await ctx.api.post("/loop/enter", { songId: song.id, play: true, restart: true });
    if (enter.status !== 200) throw new Error("/loop/enter -> " + enter.status + " " + enter.text.slice(0, 160));
    const installed = await waitFor(
      async () => ctx.api.state(),
      (s) => s.loop?.window?.songId === song.id && s.playback?.engine === "loop",
      10000,
      100,
    );
    if (!installed.ok) throw new Error("进循环失败(engine/window 不匹配)");
    const target = Math.max(aMs, bMs - 5000);
    await ctx.api.post("/player/seek", { positionMs: target });
    await sleep(400);

    // 观测窗口：从 B-5s 到「文件尾 + 4s」（若源被换成整曲，这段足够暴露 ENDED）。
    const toFileEnd = Math.max(1200, durationMs - target);
    const watchMs = Math.min(25000, Math.max(9000, toFileEnd + 4000));
    const t0 = Date.now();
    const samples: { atMs: number; positionMs: number; state: string | null; engine: string | null; playing: boolean | null }[] = [];
    let sawWrap = false;
    let prev: number | null = null;
    while (Date.now() - t0 < watchMs) {
      const s = await ctx.api.state();
      const pos = Number(s.playback?.positionMs ?? -1);
      const sample = {
        atMs: Date.now() - t0,
        positionMs: pos,
        state: s.playback?.playbackState == null ? null : String(s.playback.playbackState),
        engine: s.playback?.engine == null ? null : String(s.playback.engine),
        playing: s.playback?.playing == null ? null : Boolean(s.playback.playing),
      };
      samples.push(sample);
      if (prev != null && pos < prev - (bMs - aMs) / 2) sawWrap = true;
      prev = pos;
      await sleep(180);
    }
    ctx.evidence.step("ended-watch", { songId: song.id, aMs, bMs, durationMs, target, watchMs, samples });

    const ended = samples.filter((s) => s.state === "ENDED" || s.state === "ended");
    const zeros = samples.filter((s) => s.positionMs >= 0 && s.positionMs <= 60);
    const outOfBand = samples.filter((s) => s.positionMs < aMs - 1000 || s.positionMs > bMs + 1000);
    const notLoop = samples.filter((s) => s.engine !== "loop");
    const stopped = samples.filter((s) => s.playing === false);

    checks.push(
      check(
        "观测窗内不出现 ENDED",
        ended.length === 0,
        "ENDED 采样 " + ended.length + "/" + samples.length + " (watch=" + Math.round(watchMs / 1000) + "s, B 距尾 " +
          round((durationMs - bMs) / 1000, 1) + "s)",
        ended.length > 0 ? "00027 第二个现象：源播到文件尾 -> ENDED -> 进度归零" : undefined,
      ),
    );
    checks.push(
      check(
        "观测窗内进度不归零",
        zeros.length === 0,
        "position<=60ms 采样 " + zeros.length + "/" + samples.length + " (A=" + aMs + "ms)",
        zeros.length > 0 ? "00027 第二个现象：播完归零（源已不是环带）" : undefined,
      ),
    );
    checks.push(
      check("进度始终在 [A,B]", outOfBand.length === 0, "越界采样 " + outOfBand.length + "/" + samples.length),
    );
    checks.push(
      check(
        "观测窗内保持环带引擎",
        notLoop.length === 0,
        "engine!=loop 采样 " + notLoop.length + "/" + samples.length + " engine=" + (samples[samples.length - 1]?.engine ?? "?"),
      ),
    );
    checks.push(
      check(
        "观测窗内持续播放",
        stopped.length === 0,
        "playing=false 采样 " + stopped.length + "/" + samples.length,
      ),
    );
    checks.push(
      check(
        "从 B-5s 起 观测到回绕",
        sawWrap || bMs - target < watchMs - 1500,
        sawWrap
          ? "已观察到 display 折回 A"
          : "watch=" + Math.round(watchMs / 1000) + "s 未折回（B-5s 起算，离 B 只有 " + round((bMs - target) / 1000, 1) + "s）",
        !sawWrap ? "没跨过 B 就结束观测：环回没发生" : undefined,
      ),
    );
    return {
      checks,
      summary:
        "A=" + aMs + " B=" + bMs + " 尾距=" + round((durationMs - bMs) / 1000, 1) + "s; watch=" +
        round(watchMs / 1000, 1) + "s; ENDED=" + ended.length + " 归零=" + zeros.length +
        " minPos=" + (samples.length ? Math.round(Math.min(...samples.map((s) => s.positionMs))) : -1) + "ms",
    };
  },
};

// ---------------------------------------------------------------------------
// 导出（由 lead 合并注册；id/key 与 scenarios.ts 保持同一套口径）
// ---------------------------------------------------------------------------

export const CONTENT_SCENARIOS: Scenario[] = [s20, s21, s22, s23, s24, s25];

/** 内容真值场景依赖 uiautomator + 预览页（--skip-ui 时应整组跳过）。 */
export const CONTENT_SCENARIO_IDS = CONTENT_SCENARIOS.map((s) => s.id);

export default CONTENT_SCENARIOS;
