#!/usr/bin/env bun
/**
 * tools/scenarios/check-interaction.ts —— 分支 B（交互与状态机边界）场景的**离线**自检。
 *
 * 只读代码、不碰设备、不发任何 HTTP：
 *   1) 结构：id 唯一且与主矩阵不冲突、key 唯一/格式、name/tag/run/timeout 字段完整；
 *   2) 元数据：每个场景都有 meta（goal/expectedMs/usesUi/writesLoopData/deviceInteraction），
 *      且 meta 与场景对象一致（exclusive、UI 依赖、写库、分析）；
 *   3) 超时：>= 预期耗时 ×1.5、>= 40s、<= 900s，且全跑总耗时不至于占满一个设备窗口；
 *   4) 判据来源：run 的源码必须用 ctx.api/ctx.uiTap、必须有 checks.push + evidence，
 *      出声判据必须走 measureMainFrames / measurePreviewFrames（不许拿"位置推进"当出声证据）；
 *   5) 副作用：写 song_loop 行的场景必须带 finally + restoreLoopRow 还原；
 *   6) 依赖：只允许 import ./harness 与 ./scenarios，不许直接起进程 / 读文件系统。
 *
 * 用法：
 *   bun run tools/scenarios/check-interaction.ts              # 校验真实场景文件
 *   bun run tools/scenarios/check-interaction.ts --selftest   # 先跑规则自测(用坏 fixture 验证规则会 FAIL)
 * 退出码：0 全过；1 有 FAIL；2 自检脚本自身异常。
 */

import { readFileSync } from "node:fs";
import * as path from "node:path";

import { SCENARIOS } from "./scenarios";
import { INTERACTION_META, INTERACTION_SCENARIOS, INTERACTION_UI_SCENARIO_IDS } from "./scenarios-interaction";
import type { InteractionScenarioMeta } from "./scenarios-interaction";
import type { Scenario } from "./scenarios";

const SELF_FILE = path.join(import.meta.dir, "scenarios-interaction.ts");
const CHECK_FILE = path.join(import.meta.dir, "check-interaction.ts");

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
  meta: Record<number, InteractionScenarioMeta>;
  uiIds: number[];
  source: string;
  mainScenarios: { id: number; key: string }[];
}

interface Row {
  id: number;
  key: string;
  name: string;
  tag: string;
  kind: string;
  timeoutMs: number;
  expectedMs: number;
  flags: string;
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
  const fail = (code: string, subject: string, detail: string) =>
    issues.push({ level: "FAIL", code, subject, detail });
  const warn = (code: string, subject: string, detail: string) =>
    issues.push({ level: "WARN", code, subject, detail });

  const mainById = new Map<number, { id: number; key: string }>();
  const mainKeys = new Set<string>();
  for (const s of input.mainScenarios) {
    mainById.set(Number(s.id), { id: Number(s.id), key: String(s.key) });
    mainKeys.add(String(s.key));
  }

  if (input.scenarios.length === 0) fail("empty", "-", "INTERACTION_SCENARIOS 为空");

  const ids = new Set<number>();
  const keys = new Set<string>();
  const metaIds = new Set<number>(Object.keys(input.meta).map((k) => Number(k)));
  let expectedTotalMs = 0;
  let exclusive = 0;
  const uiIdsInMeta: number[] = [];

  for (const s of input.scenarios) {
    const label = "#" + s.id + " " + s.key;
    const id = Number(s.id);
    const key = String(s.key ?? "");

    // ---- id / key ----
    if (!Number.isInteger(id) || id <= 0) fail("id", label, "id 必须是正整数, 实际=" + s.id);
    else if (ids.has(id)) fail("id-dup", label, "id 在分组内重复");
    else if (mainById.has(id)) fail("id-collision", label, "id 与主矩阵场景冲突(合并后会互相覆盖)");
    ids.add(id);

    if (!key) fail("key", label, "key 为空");
    else if (!/^[a-z0-9][a-z0-9-]*$/.test(key)) fail("key-format", label, "key 只允许小写字母/数字/连字符: " + key);
    else if (keys.has(key)) fail("key-dup", label, "key 在分组内重复");
    else if (mainKeys.has(key)) fail("key-collision", label, "key 与主矩阵场景冲突: " + key);
    keys.add(key);

    // ---- 基本字段 ----
    if (!String(s.name ?? "").trim()) fail("name", label, "name 为空");
    if (!String(s.tag ?? "").trim()) fail("tag", label, "tag 为空");
    if (typeof s.run !== "function") {
      fail("run", label, "run 不是函数");
    } else if (s.run.constructor?.name !== "AsyncFunction") {
      fail("run-async", label, "run 必须是 async 函数(实际 " + (s.run.constructor?.name ?? "?") + ")");
    } else if (s.run.length !== 1) {
      fail("run-arity", label, "run 必须只接收 ctx 一个参数(实际 " + s.run.length + " 个)");
    }
    if (s.long === true) fail("long", label, "交互/边界场景不应标 long(长稳定性属于另一个分组)");

    // ---- 元数据 ----
    const meta: InteractionScenarioMeta | undefined = input.meta[id];
    if (!meta) {
      // 不 continue：缺 meta 也要继续查超时/源码，避免"一条坏数据掩盖另一条"。
      fail("meta-missing", label, "缺少 INTERACTION_META 条目(goal/expectedMs/usesUi/writesLoopData)");
    } else {
      if (String(meta.goal ?? "").trim().length < 10) fail("meta-goal", label, "meta.goal 必须写清'它想抓什么 bug'");
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
      if (meta.usesUi) uiIdsInMeta.push(id);
      if (wantExclusive) exclusive += 1;
    }

    // ---- 超时合理性 ----
    const timeout = Number(s.timeoutMs ?? 0);
    const expected = Number(meta?.expectedMs ?? 0);
    if (!Number.isFinite(timeout) || timeout <= 0) {
      fail("timeout-missing", label, "没有显式 timeoutMs(会用默认 150s, 分析/UI 场景会被误杀)");
    } else {
      if (!Number.isInteger(timeout)) fail("timeout-int", label, "timeoutMs 必须是整数: " + timeout);
      if (timeout < 40000) fail("timeout-min", label, "timeoutMs=" + timeout + " 太紧(<40s)");
      if (timeout > 900000) fail("timeout-max", label, "timeoutMs=" + timeout + " 超过 15 分钟上限,报告会被挂死");
      if (timeout < expected * 1.5) {
        fail("timeout-headroom", label, "timeoutMs=" + timeout + " < 预期耗时 " + expected + " ×1.5(没有余量)");
      }
      if (meta?.needsAnalysis && timeout < 180000) {
        fail("timeout-analysis", label, "需要跑 /analyze 的场景 timeoutMs 必须 ≥180s: " + timeout);
      }
    }
    expectedTotalMs += Number.isFinite(expected) ? expected : 0;

    // ---- 源码级约束 ----
    const src = sourceOf(s.run as (...args: any[]) => any);
    if (!src) {
      fail("src", label, "无法取得 run 源码,无法做静态约束检查");
    } else {
      if (!/ctx\.(api|uiTap)\b/.test(src)) fail("src-ctx", label, "run 里既没调 ctx.api 也没调 ctx.uiTap(没走现有协议)");
      if (!src.includes("checks.push(")) fail("src-check", label, "run 里没有 checks.push(至少要有 1 条判据)");
      if (!src.includes("ctx.evidence.step(")) fail("src-evidence", label, "run 里没有 ctx.evidence.step(失败时没有现场证据)");
      const usesUiTap = /ctx\.uiTap\(/.test(src);
      if (meta && usesUiTap !== meta.usesUi) {
        fail("ui-mismatch", label, "源码" + (usesUiTap ? "使用了" : "没有使用") + " ctx.uiTap, 但 meta.usesUi=" + meta.usesUi);
      }
      if (!/measureMainFrames|measurePreviewFrames|outputFrames|writtenFrames/.test(src)) {
        fail("src-audio", label, "run 里没有输出帧探针(measureMainFrames/measurePreviewFrames): 出声判据不能只靠位置");
      }
      if (/process\.exit|child_process|require\(|\bfetch\(/.test(src)) {
        fail("src-forbidden", label, "run 里出现了 process.exit/child_process/require/fetch 直接调用");
      }
      if (/位置推进/.test(src)) warn("src-position-audio", label, "run 里提到'位置推进',确认它只是辅助证据而不是出声证据");
      if (meta?.writesLoopData) {
        if (!src.includes("/confirm")) fail("write-confirm", label, "meta.writesLoopData=true 但 run 里没有 /confirm(窗口写不进去?)");
        if (!src.includes("restoreLoopRow")) fail("write-restore", label, "写 song_loop 行的场景必须在 finally 里用 restoreLoopRow 还原");
        if (!src.includes("finally")) fail("write-finally", label, "写 song_loop 行的场景必须放在 try/finally 里还原");
      } else if (src.includes("/confirm")) {
        warn("confirm-no-restore", label, "调了 /confirm 但 meta.writesLoopData=false: 确认只会被拒绝(400)不写库");
      }
      if (meta?.needsAnalysis && !src.includes("/analyze")) {
        fail("analysis-missing", label, "meta.needsAnalysis=true 但 run 里没有 /analyze");
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
      flags: (meta?.usesUi ? "UI " : "") + (meta?.writesLoopData ? "写库 " : "") + (meta?.needsAnalysis ? "分析" : ""),
    });
  }

  // ---- 元数据反向一致性 ----
  for (const id of metaIds) {
    if (!ids.has(id)) fail("meta-orphan", "#" + id, "INTERACTION_META 里有条目但 INTERACTION_SCENARIOS 里没有该场景");
  }
  const uiDeclared = new Set(input.uiIds);
  for (const id of uiIdsInMeta) {
    if (!uiDeclared.has(id)) {
      fail("ui-list", "#" + id, "meta.usesUi=true 但不在 INTERACTION_UI_SCENARIO_IDS 里(runner --skip-ui 不会跳过它)");
    }
  }
  for (const id of uiDeclared) {
    if (!uiIdsInMeta.includes(id)) fail("ui-list-extra", "#" + id, "INTERACTION_UI_SCENARIO_IDS 里有它,但 meta.usesUi=false");
  }

  // ---- 模块级 import 约束 ----
  const blocks: { names: string; from: string }[] = [];
  const re = /import\s+(?:type\s+)?(?:\{([^}]*)\}|\*\s+as\s+\w+)?\s*from\s+"([^"]+)";/g;
  let m: RegExpExecArray | null;
  while ((m = re.exec(input.source)) !== null) blocks.push({ names: m[1] ?? "", from: m[2] });
  for (const b of blocks) {
    if (b.from !== "./harness" && b.from !== "./scenarios") {
      fail("import-scope", "module", "只允许 import ./harness 与 ./scenarios, 发现: " + b.from);
    }
  }
  const harnessNames = blocks.filter((b) => b.from === "./harness").map((b) => b.names).join(",");
  for (const need of ["measureMainFrames", "measurePreviewFrames", "check", "waitFor", "sleep"]) {
    if (!new RegExp("\\b" + need + "\\b").test(harnessNames)) {
      fail("import-probe", "module", "没有从 ./harness 引入 " + need + "(出声判据必须复用公共探针)");
    }
  }
  if (!blocks.some((b) => b.from === "./scenarios")) {
    fail("import-types", "module", "没有从 ./scenarios 引入 Scenario 类型/SkipError,无法与主矩阵合并");
  }
  if (!input.source.includes("SkipError")) {
    warn("skip-unused", "module", "模块里没用到 SkipError: 前置不满足时应 SKIP 而不是 FAIL");
  }
  if (!input.source.includes("INTERACTION_SCENARIOS: Scenario[]")) {
    fail("export-array", "module", "必须以 INTERACTION_SCENARIOS: Scenario[] 导出数组(lead 才能直接并入 SCENARIOS)");
  }

  // ---- 总耗时 ----
  if (expectedTotalMs > 30 * 60 * 1000) {
    fail(
      "budget",
      "module",
      "全部交互场景预期耗时 " + Math.round(expectedTotalMs / 60000) + " 分钟 > 30 分钟,一个设备窗口跑不完",
    );
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
  const header = ["#", "分组", "预期", "超时", "场景", "key", "依赖"];
  const table = rows.map((r) => [
    String(r.id),
    r.kind,
    Math.round(r.expectedMs / 1000) + "s",
    Math.round(r.timeoutMs / 1000) + "s",
    r.name,
    r.key,
    r.flags.trim() || "-",
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
    console.log("全部检查通过：结构/字段/元数据/超时/判据来源/副作用纪律 均 OK。");
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
    checks.push({ name: "x", pass: true, detail: "y" });
    const m = await measureMainFramesStub(ctx.api, 1, 1);
    checks.push(m);
    return { checks, summary: "" };
  };
  const goodMeta: InteractionScenarioMeta = {
    id: 1,
    goal: "验证规则自测用的目标描述足够长",
    expectedMs: 10000,
    usesUi: false,
    writesLoopData: false,
    deviceInteraction: "shared",
  };
  const baseSource = [
    "import {",
    "  check,",
    "  measureMainFrames,",
    "  measurePreviewFrames,",
    "  sleep,",
    "  waitFor,",
    '} from "./harness";',
    'import { SkipError } from "./scenarios";',
    'import type { Scenario } from "./scenarios";',
    "export const INTERACTION_SCENARIOS: Scenario[] = [];",
  ].join("\n");
  const make = (over: Partial<Scenario>): Scenario =>
    ({ id: 1, key: "ok-key", name: "场景", tag: "交互", timeoutMs: 60000, run: goodRun as any, ...over }) as Scenario;

  const cases: { label: string; expect: string[]; input: ValidateInput }[] = [
    {
      label: "合规 fixture 不应报 FAIL",
      expect: [],
      input: { scenarios: [make({})], meta: { 1: goodMeta }, uiIds: [], source: baseSource, mainScenarios: [] },
    },
    {
      label: "id 重复/与主矩阵冲突应当被发现",
      expect: ["id-dup", "id-collision"],
      input: {
        scenarios: [make({}), make({ key: "other-key" })],
        meta: { 1: goodMeta },
        uiIds: [],
        source: baseSource,
        mainScenarios: [{ id: 1, key: "main-scenario" }],
      },
    },
    {
      label: "缺 timeoutMs / 缺 meta 应当被发现",
      expect: ["timeout-missing", "meta-missing"],
      input: {
        scenarios: [make({ timeoutMs: undefined })],
        meta: {},
        uiIds: [],
        source: baseSource,
        mainScenarios: [],
      },
    },
    {
      label: "写库却没 finally+restore 应当被发现",
      expect: ["write-confirm", "write-restore", "write-finally"],
      input: {
        scenarios: [
          make({
            run: (async (ctx: any) => {
              await ctx.api.post("/player/play", { songId: 1 });
              const checks: any[] = [];
              checks.push({ name: "x", pass: true, detail: "y" });
              ctx.evidence.step("x", {});
              return { checks, summary: "" };
            }) as any,
          }),
        ],
        meta: { 1: { ...goodMeta, writesLoopData: true } },
        uiIds: [],
        source: baseSource,
        mainScenarios: [],
      },
    },
    {
      label: "出声判据不靠探针 / 无 evidence 应当被发现",
      expect: ["src-audio", "src-evidence"],
      input: {
        scenarios: [
          make({
            run: (async (ctx: any) => {
              await ctx.api.post("/player/play", { songId: 1 });
              const checks: any[] = [];
              checks.push({ name: "x", pass: true, detail: "位置推进" });
              return { checks, summary: "" };
            }) as any,
          }),
        ],
        meta: { 1: goodMeta },
        uiIds: [],
        source: baseSource,
        mainScenarios: [],
      },
    },
    {
      label: "ui 依赖与源码不一致 / UI 清单遗漏应当被发现",
      expect: ["ui-mismatch", "ui-list-extra"],
      input: {
        scenarios: [
          make({
            run: (async (ctx: any) => {
              await ctx.uiTap("仅循环段");
              const checks: any[] = [];
              ctx.evidence.step("x", {});
              const m = await measureMainFramesStub(ctx.api, 1, 1);
              checks.push(m);
              return { checks, summary: "" };
            }) as any,
          }),
        ],
        meta: { 1: { ...goodMeta, usesUi: false } },
        uiIds: [1],
        source: baseSource,
        mainScenarios: [],
      },
    },
    {
      label: "越界 import 应当被发现",
      expect: ["import-scope"],
      input: {
        scenarios: [make({})],
        meta: { 1: goodMeta },
        uiIds: [],
        source: 'import { readFileSync } from "node:fs";\n' + baseSource,
        mainScenarios: [],
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

// 自测 fixture 用的占位（只用于源码文本检查，不会被真正调用）
async function measureMainFramesStub(_api: any, _ms: number, _sr: number): Promise<any> {
  return { available: true, ratio: 1 };
}

// ---------------------------------------------------------------------------
// main
// ---------------------------------------------------------------------------

function main(): number {
  const argv = process.argv.slice(2);
  if (argv.includes("--selftest")) {
    const ok = selftest();
    if (!ok) {
      console.log("规则自测未通过: 自检脚本本身不可信");
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

  console.log("== 分支 B：交互与状态机边界场景 离线自检 ==");
  console.log("场景文件: " + SELF_FILE);
  console.log("自检脚本: " + CHECK_FILE);
  console.log("");

  const { issues, rows, expectedTotalMs, exclusive } = validate({
    scenarios: INTERACTION_SCENARIOS,
    meta: INTERACTION_META,
    uiIds: INTERACTION_UI_SCENARIO_IDS,
    source,
    mainScenarios: SCENARIOS.map((s) => ({ id: Number(s.id), key: String(s.key) })),
  });

  printTable(rows);
  console.log("");
  console.log(
    "场景数 " + rows.length + " (shared " + (rows.length - exclusive) + " / exclusive " + exclusive + "); " +
      "预期总耗时 ≈ " + Math.round(expectedTotalMs / 60000) + " 分钟; " +
      "UI 场景 id = [" + INTERACTION_UI_SCENARIO_IDS.join(", ") + "]",
  );
  console.log("");
  printIssues(issues);
  console.log("");
  console.log("合并提示: 把 INTERACTION_SCENARIOS 追加进 SCENARIOS, 并把 INTERACTION_UI_SCENARIO_IDS 并入 runner 的 UI_SCENARIOS。");
  return issues.some((i) => i.level === "FAIL") ? 1 : 0;
}

try {
  process.exit(main());
} catch (e: any) {
  console.error("自检脚本异常: " + (e?.message ?? e));
  if (e?.stack) console.error(String(e.stack).split("\n").slice(0, 6).join("\n"));
  process.exit(2);
}
