#!/usr/bin/env bun
/**
 * tools/scenarios/check-prelude.ts —— 「前奏/尾奏/进循环起点」场景（id 60..79）的**离线**自检。
 *
 * 只读代码、不碰设备、不发任何 HTTP：
 *   1) 结构：id 在 60..79 且唯一、不与主矩阵冲突；key 唯一/格式；name/tag/run/timeout 完整；
 *   2) 元数据：每条都有 meta（goal=想抓什么 / expectedMs / usesUi / writesLoopData /
 *      deviceInteraction / assertions=覆盖 lead 的哪几条断言 / requirements=覆盖批次哪几条需求）；
 *   3) 覆盖：lead 定稿的 7 条断言必须全部被至少一条场景覆盖；批次 8 条需求同理；
 *   4) 超时：>= 预期耗时 ×1.5、>= 40s、<= 900s，全组预期总耗时 <= 30 分钟；
 *   5) 判据来源：run 源码必须走 ctx.api、必须有 checks.push + ctx.evidence.step、
 *      **必须有内容真值（probeMain）**、**必须有相位判据（phaseCheck）**、必须有出声探针
 *      （measureMainFrames），不能只比位置；
 *   6) 副作用：写 song_loop 行的场景（68/69）必须 exclusive，且带 /confirm + finally + restoreLoopRow；
 *   7) 依赖：只允许 import ./harness / ./scenarios / ./content-probe，不许起进程 / 读文件系统 / fetch；
 *   8) 导出：必须以 PRELUDE_SCENARIOS: Scenario[] 导出，UI 依赖清单必须为空（本组不点 UI）。
 *
 * 用法：
 *   bun run tools/scenarios/check-prelude.ts              # 校验真实场景文件
 *   bun run tools/scenarios/check-prelude.ts --selftest   # 先跑规则自测（坏 fixture 必须被规则抓到）
 * 退出码：0 全过；1 有 FAIL；2 自检脚本自身异常。
 */

import { readFileSync } from "node:fs";
import * as path from "node:path";

import { SCENARIOS } from "./scenarios";
import { PRELUDE_META, PRELUDE_SCENARIOS, PRELUDE_SCENARIO_IDS, PRELUDE_UI_SCENARIO_IDS } from "./scenarios-prelude";
import type { PreludeScenarioMeta } from "./scenarios-prelude";
import type { Scenario } from "./scenarios";

const SELF_FILE = path.join(import.meta.dir, "scenarios-prelude.ts");
const CHECK_FILE = path.join(import.meta.dir, "check-prelude.ts");

const ID_MIN = 60;
const ID_MAX = 79;
const LEAD_ASSERTIONS = [1, 2, 3, 4, 5, 6, 7];
const BATCH_REQUIREMENTS = [1, 2, 3, 4, 5, 6, 7, 8];

// ---------------------------------------------------------------------------
// 规则实现（与 IO 解耦，便于 --selftest 用坏 fixture 验证规则本身）
// ---------------------------------------------------------------------------

interface Issue {
  level: "FAIL" | "WARN";
  code: string;
  subject: string;
  detail: string;
}

interface ValidateInput {
  scenarios: Scenario[];
  meta: Record<number, PreludeScenarioMeta>;
  ids: number[];
  uiIds: number[];
  source: string;
  mainScenarios: { id: number; key: string }[];
  /** 规则自测的坏 fixture 不声明全量覆盖，跳过覆盖矩阵检查。 */
  skipCoverage?: boolean;
}

interface Row {
  id: number;
  key: string;
  name: string;
  tag: string;
  kind: string;
  timeoutMs: number;
  expectedMs: number;
  covers: string;
  expectedFail: string;
}

function sourceOf(fn: (...args: any[]) => any): string {
  try {
    return Function.prototype.toString.call(fn);
  } catch {
    return "";
  }
}

function validate(input: ValidateInput): { issues: Issue[]; rows: Row[]; expectedTotalMs: number; exclusive: number } {
  const issues: Issue[] = [];
  const rows: Row[] = [];
  const fail = (code: string, subject: string, detail: string) => issues.push({ level: "FAIL", code, subject, detail });
  const warn = (code: string, subject: string, detail: string) => issues.push({ level: "WARN", code, subject, detail });

  const mainIds = new Map<number, string>();
  for (const s of input.mainScenarios) mainIds.set(Number(s.id), String(s.key));

  if (input.scenarios.length === 0) fail("empty", "-", "PRELUDE_SCENARIOS 为空");
  if (input.scenarios.length > ID_MAX - ID_MIN + 1) {
    fail("too-many", "-", "场景数 " + input.scenarios.length + " 超过 id 段容量 " + (ID_MAX - ID_MIN + 1));
  }

  const ids = new Set<number>();
  const keys = new Set<string>();
  const metaIds = new Set<number>(Object.keys(input.meta).map((k) => Number(k)));
  const coveredAssertions = new Set<number>();
  const coveredRequirements = new Set<number>();
  let expectedTotalMs = 0;
  let exclusive = 0;

  for (const s of input.scenarios) {
    const label = "#" + s.id + " " + s.key;
    const id = Number(s.id);
    const key = String(s.key ?? "");

    // ---- id / key ----
    if (!Number.isInteger(id) || id < ID_MIN || id > ID_MAX) {
      fail("id-range", label, "id 必须落在 " + ID_MIN + ".." + ID_MAX + "（本批次预留段），实际=" + s.id);
    } else if (ids.has(id)) {
      fail("id-dup", label, "id 在分组内重复");
    } else if (mainIds.has(id)) {
      fail("id-collision", label, "id 与主矩阵场景冲突（合并后会互相覆盖）: " + mainIds.get(id));
    }
    ids.add(id);

    if (!key) fail("key", label, "key 为空");
    else if (!/^[a-z0-9][a-z0-9-]*$/.test(key)) fail("key-format", label, "key 只允许小写字母/数字/连字符: " + key);
    else if (keys.has(key)) fail("key-dup", label, "key 在分组内重复");
    keys.add(key);

    // ---- 基本字段 ----
    if (!String(s.name ?? "").trim()) fail("name", label, "name 为空");
    if (!String(s.tag ?? "").trim()) fail("tag", label, "tag 为空");
    if (typeof s.run !== "function") {
      fail("run", label, "run 不是函数");
    } else if (s.run.constructor?.name !== "AsyncFunction") {
      fail("run-async", label, "run 必须是 async 函数（实际 " + (s.run.constructor?.name ?? "?") + "）");
    } else if (s.run.length !== 1) {
      fail("run-arity", label, "run 必须只接收 ctx 一个参数（实际 " + s.run.length + " 个）");
    }
    if (s.long === true) fail("long", label, "本组不应标 long（长稳定性属于另一个分组）");

    // ---- 元数据 ----
    const meta: PreludeScenarioMeta | undefined = input.meta[id];
    if (!meta) {
      fail("meta-missing", label, "缺少 PRELUDE_META 条目（goal/expectedMs/usesUi/writesLoopData/assertions/requirements）");
    } else {
      if (String(meta.goal ?? "").trim().length < 10 || !String(meta.goal).includes("抓")) {
        fail("meta-goal", label, "meta.goal 必须写清'想抓什么 bug'（>=10 字符且包含'抓'）");
      }
      if (!Number.isFinite(Number(meta.expectedMs)) || Number(meta.expectedMs) <= 0) {
        fail("meta-expected", label, "meta.expectedMs 必须为正数");
      }
      if (meta.deviceInteraction !== "shared" && meta.deviceInteraction !== "exclusive") {
        fail("meta-interaction", label, "deviceInteraction 只能是 shared/exclusive: " + meta.deviceInteraction);
      }
      const wantExclusive = meta.deviceInteraction === "exclusive";
      if (Boolean(s.exclusive) !== wantExclusive) {
        fail(
          "exclusive-mismatch",
          label,
          "exclusive=" + Boolean(s.exclusive) + " 与 meta.deviceInteraction=" + meta.deviceInteraction + " 不一致",
        );
      }
      if (meta.writesLoopData && !wantExclusive) {
        fail("write-shared", label, "写 song_loop 行的场景必须 exclusive（会改用户数据）");
      }
      if (typeof meta.expectedFailBeforeFix !== "boolean") {
        fail("meta-expected-fail", label, "meta.expectedFailBeforeFix 必须是 boolean（对照 00028 修复前后）");
      }
      for (const a of meta.assertions ?? []) {
        if (!LEAD_ASSERTIONS.includes(a)) fail("meta-assertion-range", label, "assertions 里出现越界编号: " + a);
        else coveredAssertions.add(a);
      }
      for (const r of meta.requirements ?? []) {
        if (!BATCH_REQUIREMENTS.includes(r)) fail("meta-requirement-range", label, "requirements 里出现越界编号: " + r);
        else coveredRequirements.add(r);
      }
      if ((meta.assertions ?? []).length === 0 && (meta.requirements ?? []).length === 0) {
        fail("meta-cover-empty", label, "assertions 与 requirements 都为空：这条场景没有声明覆盖什么");
      }
      if (wantExclusive) exclusive += 1;
    }

    // ---- 超时合理性 ----
    const timeout = Number(s.timeoutMs ?? 0);
    const expected = Number(meta?.expectedMs ?? 0);
    if (!Number.isFinite(timeout) || timeout <= 0) {
      fail("timeout-missing", label, "没有显式 timeoutMs（会用默认 150s）");
    } else {
      if (!Number.isInteger(timeout)) fail("timeout-int", label, "timeoutMs 必须是整数: " + timeout);
      if (timeout < 40000) fail("timeout-min", label, "timeoutMs=" + timeout + " 太紧(<40s)");
      if (timeout > 900000) fail("timeout-max", label, "timeoutMs=" + timeout + " 超过 15 分钟上限");
      if (timeout < expected * 1.5) {
        fail("timeout-headroom", label, "timeoutMs=" + timeout + " < 预期耗时 " + expected + " ×1.5（没有余量）");
      }
    }
    expectedTotalMs += Number.isFinite(expected) ? expected : 0;

    // ---- 源码级约束 ----
    const src = sourceOf(s.run as (...args: any[]) => any);
    if (!src) {
      fail("src", label, "无法取得 run 源码，无法做静态约束检查");
    } else {
      if (!/ctx\.api\b/.test(src)) fail("src-ctx", label, "run 里没有调 ctx.api（没走现有协议）");
      if (!src.includes("checks.push(")) fail("src-check", label, "run 里没有 checks.push（至少要有 1 条判据）");
      if ((src.match(/checks\.push\(/g) ?? []).length < 3) {
        fail("src-check-count", label, "判据太少（<3 条）：位置 / 相位 / 内容 至少要各一条");
      }
      if (!src.includes("ctx.evidence.step(")) fail("src-evidence", label, "run 里没有 ctx.evidence.step（失败时没有现场证据）");
      if (!/probeMain\(/.test(src)) {
        fail("src-content", label, "run 里没有 probeMain（需求 8：每一条都要用内容真值，不能只比位置）");
      }
      if (!/phaseCheck\(/.test(src)) {
        fail("src-phase", label, "run 里没有 phaseCheck（必须显式断言 prelude/loop/tail 相位）");
      }
      if (!/phaseOf\(/.test(src)) {
        fail("src-phaseof", label, "run 里没有 phaseOf（相位口径必须走同一处：loop.phase -> playback.phase -> 推断）");
      }
      if (!/measureMainFrames\(/.test(src)) {
        fail("src-audio", label, "run 里没有 measureMainFrames（出声判据不能只靠位置）");
      }
      // 注意：bun 的 Function.prototype.toString 会把非 ASCII 字符串转成 \uXXXX，
      // 所以不能在 run 源码里查中文（"位置"）。位置判据在模块级用文件原文核对。
      if (/process\.exit|child_process|\brequire\(|\bfetch\(/.test(src)) {
        fail("src-forbidden", label, "run 里出现了 process.exit/child_process/require/fetch 直接调用");
      }
      if (meta && meta.usesUi) {
        if (!/ctx\.uiTap\(/.test(src)) fail("ui-missing", label, "meta.usesUi=true 但 run 里没有 ctx.uiTap");
      } else if (/ctx\.uiTap\(/.test(src) || /ctx\.ensureLoopPage\(/.test(src)) {
        fail("ui-unexpected", label, "本组不依赖 UI，但 run 里用了 ctx.uiTap/ctx.ensureLoopPage（--skip-ui 下会整组被跳过）");
      }
      if (meta?.writesLoopData) {
        if (!src.includes("/confirm")) fail("write-confirm", label, "meta.writesLoopData=true 但 run 里没有 /confirm（窗口写不进去）");
        if (!src.includes("restoreLoopRow(")) fail("write-restore", label, "写 song_loop 行必须在 finally 里用 restoreLoopRow 还原");
        if (!src.includes("finally")) fail("write-finally", label, "写 song_loop 行必须放在 try/finally 里还原");
      }
      // 00028 的预期失败必须写清（注释里出现 [EXPECTED-FAIL 00028] 或 00028）
      if (meta?.expectedFailBeforeFix && !src.includes("00028")) {
        fail("expected-fail-doc", label, "expectedFailBeforeFix=true 但 run 里没有提到 00028（判据里要写清预期失败点）");
      }
    }

    rows.push({
      id,
      key,
      name: String(s.name),
      tag: String(s.tag),
      kind: s.exclusive ? "EXCL" : "shared",
      timeoutMs: timeout,
      expectedMs: Number.isFinite(expected) ? expected : 0,
      covers:
        "A[" + (meta?.assertions ?? []).join(",") + "] R[" + (meta?.requirements ?? []).join(",") + "]",
      expectedFail: meta?.expectedFailBeforeFix ? "预 FAIL" : "-",
    });
  }

  // ---- 元数据反向一致性 ----
  for (const id of metaIds) {
    if (!ids.has(id)) fail("meta-orphan", "#" + id, "PRELUDE_META 里有条目但 PRELUDE_SCENARIOS 里没有该场景");
  }
  const declaredIds = new Set(input.ids.map((x) => Number(x)));
  for (const id of ids) {
    if (!declaredIds.has(id)) fail("ids-missing", "#" + id, "PRELUDE_SCENARIO_IDS 里缺这个 id（runner 分组会漏）");
  }
  for (const id of declaredIds) {
    if (!ids.has(id)) fail("ids-extra", "#" + id, "PRELUDE_SCENARIO_IDS 里有它，但 PRELUDE_SCENARIOS 里没有");
  }
  if (input.uiIds.length > 0) {
    fail("ui-ids", "-", "本组不依赖 uiautomator，PRELUDE_UI_SCENARIO_IDS 必须为空，实际=" + input.uiIds.join(","));
  }

  // ---- 覆盖矩阵 ----
  if (!input.skipCoverage) {
    for (const a of LEAD_ASSERTIONS) {
      if (!coveredAssertions.has(a)) fail("coverage-assertion", "lead 断言 " + a, "没有任何场景声明覆盖这条断言");
    }
    for (const r of BATCH_REQUIREMENTS) {
      if (!coveredRequirements.has(r)) fail("coverage-requirement", "批次需求 " + r, "没有任何场景声明覆盖这条需求");
    }
  }

  // ---- 模块级 import / 导出约束 ----
  const blocks: { names: string; from: string }[] = [];
  // 头注释里有 "import ... from './scenarios-prelude'" 示例（合并提示），必须先剥注释再扫 import。
  const codeOnly = input.source.replace(/\/\*[\s\S]*?\*\//g, "").replace(/^\s*\/\/.*$/gm, "");
  const re = /import\s+(?:type\s+)?(?:\{([^}]*)\}|\*\s+as\s+\w+)?\s*from\s+"([^"]+)";/g;
  let m: RegExpExecArray | null;
  while ((m = re.exec(codeOnly)) !== null) blocks.push({ names: m[1] ?? "", from: m[2] });
  for (const b of blocks) {
    if (b.from !== "./harness" && b.from !== "./scenarios" && b.from !== "./content-probe") {
      fail("import-scope", "module", "只允许 import ./harness / ./scenarios / ./content-probe，发现: " + b.from);
    }
  }
  const harnessNames = blocks.filter((b) => b.from === "./harness").map((b) => b.names).join(",");
  for (const need of ["measureMainFrames", "check", "sleep", "waitFor", "pendingCheck"]) {
    if (!new RegExp("\\b" + need + "\\b").test(harnessNames)) {
      warn("import-probe", "module", "没有从 ./harness 引入 " + need + "（判据/探针应复用公共实现）");
    }
  }
  const probeNames = blocks.filter((b) => b.from === "./content-probe").map((b) => b.names).join(",");
  if (!/\bcreateProber\b/.test(probeNames)) {
    fail("import-content", "module", "没有从 ./content-probe 引入 createProber（内容真值必须复用判据库）");
  }
  if (!blocks.some((b) => b.from === "./scenarios")) {
    fail("import-types", "module", "没有从 ./scenarios 引入 Scenario 类型/SkipError，无法与主矩阵合并");
  }
  if (!input.source.includes("SkipError")) {
    warn("skip-unused", "module", "模块里没用到 SkipError：前置不满足时应 SKIP 而不是 FAIL");
  }
  // run 源码里的中文会被 bun 转义，位置判据只能在模块级按文件原文核对。
  const positionMentions = (input.source.match(/位置/g) ?? []).length;
  if (positionMentions < Math.max(3, input.scenarios.length)) {
    fail(
      "src-position",
      "module",
      "文件里'位置'判据只出现 " + positionMentions + " 处（< 场景数 " + input.scenarios.length + "）：每个场景都要断位置，不能只靠相位/内容",
    );
  }
  if (!/export const PRELUDE_SCENARIOS:\s*Scenario\[\]/.test(input.source)) {
    fail("export-array", "module", "必须以 PRELUDE_SCENARIOS: Scenario[] 导出数组（lead 才能直接并入 SCENARIOS）");
  }
  if (!input.source.includes("PRELUDE_UI_SCENARIO_IDS")) {
    fail("export-ui", "module", "缺少 PRELUDE_UI_SCENARIO_IDS 导出（明确告诉 runner 本组不进 --skip-ui 分组）");
  }
  const expectedFailDocs = (input.source.match(/\[EXPECTED-FAIL 00028\]/g) ?? []).length;
  if (expectedFailDocs < 5) {
    warn("expected-fail-comments", "module", "[EXPECTED-FAIL 00028] 注释只有 " + expectedFailDocs + " 处（建议每条预期失败的场景都标注，便于修完对照）");
  }

  // ---- 总耗时 ----
  if (expectedTotalMs > 30 * 60 * 1000) {
    fail("budget", "module", "全组预期耗时 " + Math.round(expectedTotalMs / 60000) + " 分钟 > 30 分钟，一个设备窗口跑不完");
  }

  return { issues, rows, expectedTotalMs, exclusive };
}

// ---------------------------------------------------------------------------
// 输出
// ---------------------------------------------------------------------------

function displayWidth(text: string): number {
  let w = 0;
  for (const ch of text) {
    const cp = ch.codePointAt(0) ?? 0;
    const wide =
      (cp >= 0x1100 && cp <= 0x115f) ||
      (cp >= 0x2e80 && cp <= 0xa4cf) ||
      (cp >= 0xac00 && cp <= 0xd7a3) ||
      (cp >= 0xf900 && cp <= 0xfaff) ||
      (cp >= 0xfe30 && cp <= 0xfe6f) ||
      (cp >= 0xff00 && cp <= 0xff60) ||
      (cp >= 0xffe0 && cp <= 0xffe6);
    w += wide ? 2 : 1;
  }
  return w;
}

function printTable(rows: Row[]): void {
  const header = ["#", "分组", "预期", "超时", "覆盖", "00028", "场景", "key"];
  const table = rows.map((r) => [
    String(r.id),
    r.kind,
    Math.round(r.expectedMs / 1000) + "s",
    Math.round(r.timeoutMs / 1000) + "s",
    r.covers,
    r.expectedFail,
    r.name,
    r.key,
  ]);
  const widths = header.map((h, i) => Math.max(displayWidth(h), ...table.map((row) => displayWidth(row[i]))));
  const line = (cells: string[]) =>
    cells.map((c, i) => c + " ".repeat(Math.max(0, widths[i] - displayWidth(c)))).join("  ");
  console.log(line(header));
  console.log(widths.map((w) => "-".repeat(w)).join("  "));
  for (const row of table) console.log(line(row));
}

function printIssues(issues: Issue[]): void {
  const fails = issues.filter((i) => i.level === "FAIL");
  const warns = issues.filter((i) => i.level === "WARN");
  if (issues.length === 0) {
    console.log("全部检查通过：结构/元数据/覆盖矩阵/超时/判据来源/副作用纪律/导出 均 OK。");
    return;
  }
  for (const i of issues) {
    console.log((i.level === "FAIL" ? "x FAIL " : "~ WARN ") + "[" + i.code + "] " + i.subject + ": " + i.detail);
  }
  console.log("");
  console.log((fails.length === 0 ? "OK" : "FAIL") + " — FAIL " + fails.length + " / WARN " + warns.length);
}

// ---------------------------------------------------------------------------
// 规则自测：用故意写坏的 fixture 验证规则本身会报 FAIL
// ---------------------------------------------------------------------------

function selftest(): boolean {
  const goodRun = async function (ctx: any) {
    const checks: any[] = [];
    await ctx.api.post("/loop/enter", { songId: 1 });
    ctx.evidence.step("x", {});
    const tag = "00028"; // 预期失败标注（ASCII，run 源码里可静态核对）
    const pr = phaseOf(null, { aMs: 0, bMs: 1 });
    checks.push(phaseCheck("位置/相位 " + tag, pr, "prelude", 0));
    const audio = await measureMainFrames(ctx.api, 1, 1);
    checks.push(audio);
    const probe = await probeMain(ctx, null, null, { label: "x" });
    checks.push(probe.check);
    return { checks, summary: "" };
  };
  const goodMeta: PreludeScenarioMeta = {
    id: 60,
    goal: "抓一个足够长的目标描述（规则自测用）",
    expectedMs: 60000,
    usesUi: false,
    writesLoopData: false,
    deviceInteraction: "shared",
    assertions: [1],
    requirements: [1],
    expectedFailBeforeFix: true,
  };
  const baseSource = [
    "// 位置 位置 位置 位置（fixture 里仅供模块级'位置判据'规则核对）",
    "import {",
    "  check,",
    "  measureMainFrames,",
    "  pendingCheck,",
    "  sleep,",
    "  waitFor,",
    '} from "./harness";',
    'import { createProber } from "./content-probe";',
    'import type { Scenario } from "./scenarios";',
    "export const PRELUDE_SCENARIOS: Scenario[] = [];",
    "export const PRELUDE_UI_SCENARIO_IDS: number[] = [];",
  ].join("\n");
  const make = (over: Partial<Scenario>): Scenario =>
    ({ id: 60, key: "ok-key", name: "场景", tag: "前奏", timeoutMs: 120000, run: goodRun as any, ...over }) as Scenario;

  const cases: { label: string; expect: string[]; input: ValidateInput }[] = [
    {
      label: "合规 fixture 不应报 FAIL",
      expect: [],
      input: {
        scenarios: [make({})],
        meta: { 60: goodMeta },
        ids: [60],
        uiIds: [],
        source: baseSource,
        mainScenarios: [{ id: 1, key: "main" }],
        skipCoverage: true,
      },
    },
    {
      label: "id 越界 / 与主矩阵冲突应被发现",
      expect: ["id-range", "id-collision"],
      input: {
        scenarios: [make({ id: 59 }), make({ id: 60, key: "collide-key" })],
        meta: { 59: { ...goodMeta, id: 59 }, 60: goodMeta },
        ids: [59, 60],
        uiIds: [],
        source: baseSource,
        mainScenarios: [{ id: 60, key: "main" }],
        skipCoverage: true,
      },
    },
    {
      label: "缺 meta / 缺 timeout 应被发现",
      expect: ["meta-missing", "timeout-missing"],
      input: {
        scenarios: [make({ timeoutMs: undefined })],
        meta: {},
        ids: [60],
        uiIds: [],
        source: baseSource,
        mainScenarios: [],
        skipCoverage: true,
      },
    },
    {
      label: "没内容真值 / 没相位判据 应被发现",
      expect: ["src-content", "src-phase"],
      input: {
        scenarios: [
          make({
            run: (async (ctx: any) => {
              await ctx.api.post("/player/play", { songId: 1 });
              const checks: any[] = [];
              ctx.evidence.step("x", {});
              checks.push({ name: "a", pass: true, detail: "00028" });
              const m = await measureMainFrames(ctx.api, 1, 1);
              checks.push(m);
              checks.push(m);
              return { checks, summary: "" };
            }) as any,
          }),
        ],
        meta: { 60: goodMeta },
        ids: [60],
        uiIds: [],
        source: baseSource,
        mainScenarios: [],
        skipCoverage: true,
      },
    },
    {
      label: "写库场景缺 exclusive/finally/restore 应被发现",
      expect: ["write-shared", "write-confirm", "write-restore", "write-finally", "exclusive-mismatch"],
      input: {
        scenarios: [
          make({
            exclusive: true,
            run: (async (ctx: any) => {
              await ctx.api.post("/player/seek", { positionMs: 1 });
              const checks: any[] = [];
              ctx.evidence.step("x", {});
              const pr = phaseOf(null, { aMs: 0, bMs: 1 });
              checks.push(phaseCheck("x 00028", pr, "prelude", 0));
              const audio = await measureMainFrames(ctx.api, 1, 1);
              checks.push(audio);
              const probe = await probeMain(ctx, null, null, { label: "x" });
              checks.push(probe.check);
              return { checks, summary: "" };
            }) as any,
          }),
        ],
        meta: { 60: { ...goodMeta, writesLoopData: true } },
        ids: [60],
        uiIds: [],
        source: baseSource,
        mainScenarios: [],
        skipCoverage: true,
      },
    },
    {
      label: "覆盖矩阵缺失应被发现（不跳过覆盖检查）",
      expect: ["coverage-assertion", "coverage-requirement"],
      input: {
        scenarios: [make({})],
        meta: { 60: { ...goodMeta, assertions: [1], requirements: [1] } },
        ids: [60],
        uiIds: [],
        source: baseSource,
        mainScenarios: [],
      },
    },
    {
      label: "越界 import / 缺导出应被发现",
      expect: ["import-scope", "export-array"],
      input: {
        scenarios: [make({})],
        meta: { 60: goodMeta },
        ids: [60],
        uiIds: [],
        source:
          'import { readFileSync } from "node:fs";\n' +
          baseSource.replace("export const PRELUDE_SCENARIOS: Scenario[] = [];", "export const SCENARIOS = [];"),
        mainScenarios: [],
        skipCoverage: true,
      },
    },
  ];

  let ok = true;
  console.log("== 规则自测（坏 fixture 必须被规则抓到）==");
  for (const c of cases) {
    const { issues } = validate(c.input);
    const fails = issues.filter((i) => i.level === "FAIL").map((i) => i.code);
    const missing = c.expect.filter((code) => !fails.includes(code));
    const unexpected = c.expect.length === 0 ? fails : [];
    const pass = missing.length === 0 && unexpected.length === 0;
    if (!pass) ok = false;
    console.log(
      (pass ? "OK   " : "FAIL ") + c.label +
        (missing.length > 0 ? " | 漏报: " + missing.join(",") : "") +
        (unexpected.length > 0 ? " | 误报: " + unexpected.join(",") : ""),
    );
  }
  console.log("");
  return ok;
}

// ---------------------------------------------------------------------------
// main
// ---------------------------------------------------------------------------

function main(): number {
  const argv = process.argv.slice(2);
  if (argv.includes("--selftest")) {
    const ok = selftest();
    if (!ok) {
      console.log("规则自测未通过：自检脚本本身不可信");
      return 1;
    }
  }

  let source = "";
  try {
    source = readFileSync(SELF_FILE, "utf8");
  } catch (e: any) {
    console.error("读不到场景文件 " + SELF_FILE + ": " + (e?.message ?? e));
    return 2;
  }

  console.log("== 第三批：前奏/尾奏/进循环起点场景 离线自检 ==");
  console.log("场景文件: " + SELF_FILE);
  console.log("自检脚本: " + CHECK_FILE);
  console.log("");

  const { issues, rows, expectedTotalMs, exclusive } = validate({
    scenarios: PRELUDE_SCENARIOS,
    meta: PRELUDE_META,
    ids: PRELUDE_SCENARIO_IDS,
    uiIds: PRELUDE_UI_SCENARIO_IDS,
    source,
    mainScenarios: SCENARIOS.map((s) => ({ id: Number(s.id), key: String(s.key) })),
  });

  printTable(rows);
  console.log("");
  console.log(
    "场景数 " + rows.length + " (shared " + (rows.length - exclusive) + " / exclusive " + exclusive + "); " +
      "预期总耗时 ≈ " + Math.round(expectedTotalMs / 60000) + " 分钟; UI 场景 id = [" + PRELUDE_UI_SCENARIO_IDS.join(", ") + "]",
  );
  console.log("");
  printIssues(issues);
  console.log("");
  console.log("合并提示: 把 PRELUDE_SCENARIOS 追加进 SCENARIOS；本组不进 UI_SCENARIOS（PRELUDE_UI_SCENARIO_IDS 为空）。");
  return issues.some((i) => i.level === "FAIL") ? 1 : 0;
}

try {
  process.exit(main());
} catch (e: any) {
  console.error("自检脚本异常: " + (e?.message ?? e));
  if (e?.stack) console.error(String(e.stack).split("\n").slice(0, 6).join("\n"));
  process.exit(2);
}
