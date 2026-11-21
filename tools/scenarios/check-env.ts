#!/usr/bin/env bun
/**
 * tools/scenarios/check-env.ts —— scenarios-env.ts 的离线自检(不碰设备、不需要 APK)。
 *
 * 它能证明的东西只有一类:「场景对象的形状、编号、超时、副作用声明是自洽的」。
 * 它不能证明场景判据在真机上成立 —— 那必须跑真机(见 docs/testing-scenarios.md)。
 *
 * 用法: bun run tools/scenarios/check-env.ts
 * 退出码: 0 = 全过; 1 = 有 FAIL(逐条打印)
 */

import { readFileSync } from "node:fs";
import * as path from "node:path";

import { SCENARIOS } from "./scenarios";
import { ENV_SCENARIOS, ENV_SCENARIO_BUDGETS } from "./scenarios-env";
import type { EnvScenarioBudget } from "./scenarios-env";

const HERE = path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, "$1"));
const ENV_FILE = path.join(HERE, "scenarios-env.ts");

/** runner 的默认单场景超时(--timeout 默认 150s)。 */
const RUNNER_DEFAULT_TIMEOUT_MS = 150000;
const RESERVED_MIN_ID = 40;
const RESERVED_MAX_ID = 59;

interface Finding {
  ok: boolean;
  rule: string;
  detail: string;
}

const findings: Finding[] = [];
function record(rule: string, ok: boolean, detail: string): void {
  findings.push({ ok, rule, detail });
}
function fail(rule: string, detail: string): void {
  record(rule, false, detail);
}
function pass(rule: string, detail: string): void {
  record(rule, true, detail);
}

const envSource = readFileSync(ENV_FILE, "utf8");

// ---------------------------------------------------------------------------
// 1. 每个场景对象的字段完整性
// ---------------------------------------------------------------------------
const REQUIRED_STRING_FIELDS: (keyof (typeof ENV_SCENARIOS)[number])[] = ["key", "name", "tag"];
for (const s of ENV_SCENARIOS) {
  const anyS: any = s;
  const problems: string[] = [];
  if (!Number.isInteger(anyS.id) || anyS.id <= 0) problems.push("id 不是正整数");
  for (const field of REQUIRED_STRING_FIELDS) {
    const v = anyS[field];
    if (typeof v !== "string" || v.trim().length === 0) problems.push(String(field) + " 为空/非字符串");
  }
  if (typeof anyS.run !== "function") problems.push("run 不是函数");
  if (anyS.run && typeof anyS.run === "function") {
    if (anyS.run.constructor?.name !== "AsyncFunction") problems.push("run 不是 async 函数");
    if (anyS.run.length !== 1) problems.push("run 参数个数=" + anyS.run.length + "(应为 1 个 ctx)");
  }
  if (anyS.exclusive !== undefined && typeof anyS.exclusive !== "boolean") problems.push("exclusive 不是 boolean");
  if (anyS.long !== undefined && typeof anyS.long !== "boolean") problems.push("long 不是 boolean");
  if (anyS.timeoutMs !== undefined && (!Number.isInteger(anyS.timeoutMs) || anyS.timeoutMs <= 0)) {
    problems.push("timeoutMs 不是正整数");
  }
  record("字段完整性", problems.length === 0, "id=" + anyS.id + (problems.length ? ": " + problems.join("; ") : ""));
}

// ---------------------------------------------------------------------------
// 2. id / key 唯一性(本文件内 + 与既有 SCENARIOS 不冲突)
// ---------------------------------------------------------------------------
const existingIds = new Set(SCENARIOS.map((s) => s.id));
const existingKeys = new Set(SCENARIOS.map((s) => s.key));
const seenIds = new Map<number, number>();
const seenKeys = new Map<string, number>();
for (const s of ENV_SCENARIOS) {
  seenIds.set(s.id, (seenIds.get(s.id) ?? 0) + 1);
  seenKeys.set(s.key, (seenKeys.get(s.key) ?? 0) + 1);
}
const dupIds = [...seenIds.entries()].filter(([, n]) => n > 1).map(([id]) => id);
const dupKeys = [...seenKeys.entries()].filter(([, n]) => n > 1).map(([k]) => k);
record("id 唯一(本文件内)", dupIds.length === 0, dupIds.length ? "重复 id: " + dupIds.join(",") : ENV_SCENARIOS.length + " 个 id 唯一");
record("key 唯一(本文件内)", dupKeys.length === 0, dupKeys.length ? "重复 key: " + dupKeys.join(",") : ENV_SCENARIOS.length + " 个 key 唯一");
const collideIds = ENV_SCENARIOS.filter((s) => existingIds.has(s.id)).map((s) => s.id);
const collideKeys = ENV_SCENARIOS.filter((s) => existingKeys.has(s.key)).map((s) => s.key);
record(
  "与既有 SCENARIOS 不撞号",
  collideIds.length === 0 && collideKeys.length === 0,
  "既有 id 集合=" + [...existingIds].sort((a, b) => a - b).join(",") +
    (collideIds.length ? " 冲突 id: " + collideIds.join(",") : "") +
    (collideKeys.length ? " 冲突 key: " + collideKeys.join(",") : ""),
);

// ---------------------------------------------------------------------------
// 3. id 段与 key 命名空间(和交互/状态机分支并行时不撞号)
// ---------------------------------------------------------------------------
const outOfRange = ENV_SCENARIOS.filter((s) => s.id < RESERVED_MIN_ID || s.id > RESERVED_MAX_ID).map((s) => s.id);
record(
  "id 落在保留段 " + RESERVED_MIN_ID + "-" + RESERVED_MAX_ID,
  outOfRange.length === 0,
  outOfRange.length ? "越界 id: " + outOfRange.join(",") : "全部在保留段内",
);
const badKeys = ENV_SCENARIOS.filter((s) => !/^env-[a-z0-9]+(-[a-z0-9]+)*$/.test(s.key)).map((s) => s.key);
record("key 以 env- 开头且 kebab-case", badKeys.length === 0, badKeys.length ? "不合规 key: " + badKeys.join(",") : "全部合规");

// ---------------------------------------------------------------------------
// 4. 注释与场景一一对应(「想抓什么」必须写在该场景头上)
// ---------------------------------------------------------------------------
for (const s of ENV_SCENARIOS) {
  const head = "// 场景 " + s.id + "：";
  record("场景 " + s.id + " 有注释头", envSource.includes(head), head);
}
const catchComments = (envSource.match(/\/\/\s*想抓:/g) ?? []).length;
record(
  "每个场景都写了「想抓:」",
  catchComments === ENV_SCENARIOS.length,
  "想抓注释 " + catchComments + " 条 / 场景 " + ENV_SCENARIOS.length + " 条",
);

// ---------------------------------------------------------------------------
// 5. 预算表:一一对应 + 超时是否合理
// ---------------------------------------------------------------------------
const budgetIds = Object.keys(ENV_SCENARIO_BUDGETS).map(Number).sort((a, b) => a - b);
const scenarioIds = ENV_SCENARIOS.map((s) => s.id).sort((a, b) => a - b);
const budgetMatches = budgetIds.length === scenarioIds.length && budgetIds.every((id, i) => id === scenarioIds[i]);
record("预算表与场景一一对应", budgetMatches, "预算 id=" + budgetIds.join(",") + " 场景 id=" + scenarioIds.join(","));

const catchesSeen = new Set<string>();
for (const s of ENV_SCENARIOS) {
  const b: EnvScenarioBudget | undefined = ENV_SCENARIO_BUDGETS[s.id];
  if (!b) {
    fail("场景 " + s.id + " 有预算", "ENV_SCENARIO_BUDGETS 缺 " + s.id);
    continue;
  }
  const problems: string[] = [];
  const effective = s.timeoutMs ?? RUNNER_DEFAULT_TIMEOUT_MS;
  if (!Number.isFinite(b.expectedMs) || b.expectedMs <= 0) problems.push("expectedMs 非法");
  if (typeof b.ui !== "boolean") problems.push("ui 不是 boolean");
  if (!Array.isArray(b.deviceMutations)) problems.push("deviceMutations 不是数组");
  if (!Array.isArray(b.skipProne)) problems.push("skipProne 不是数组");
  if (typeof b.catches !== "string" || b.catches.trim().length === 0) problems.push("catches 为空");
  // 超时余量:至少比预期耗时多 80%,且不超过 15 分钟(超长稳定性场景另议)。
  if (b.expectedMs > 0 && effective < b.expectedMs * 1.8) {
    problems.push("timeoutMs=" + effective + " 相对 expectedMs=" + b.expectedMs + " 余量不足(<1.8x)");
  }
  if (effective > 900000) problems.push("timeoutMs=" + effective + " 超过 15 分钟上限");
  // UI 场景必须独占跑(--skip-ui 也应该能跳过它)。
  if (b.ui === true && s.exclusive !== true) problems.push("ui=true 但 exclusive!=true");
  record("场景 " + s.id + " 预算合理", problems.length === 0, problems.length ? problems.join("; ") : "timeout=" + effective + "ms expected=" + b.expectedMs + "ms");
  if (b.catches && catchesSeen.has(b.catches)) fail("场景 " + s.id + " 的 catches 不重复", "与其它场景相同: " + b.catches);
  if (b.catches) catchesSeen.add(b.catches);
}

// ---------------------------------------------------------------------------
// 6. 副作用声明 vs 实现:改了设备状态就必须 finally 恢复
// ---------------------------------------------------------------------------
// 只有「声明里写了要恢复」的副作用才要求 finally(重启这类不可逆动作没法 finally)。
const NEEDS_RESTORE = /恢复|还原|reset|revert/i;
for (const s of ENV_SCENARIOS) {
  const b = ENV_SCENARIO_BUDGETS[s.id];
  if (!b || b.deviceMutations.length === 0) continue;
  if (!b.deviceMutations.some((m) => NEEDS_RESTORE.test(m))) continue;
  const src = String(s.run);
  record(
    "场景 " + s.id + " 有副作用 → 必须 finally 恢复",
    src.includes("finally"),
    "副作用: " + b.deviceMutations.join(" / "),
  );
}

// 文件级配对规则:注入手段与恢复手段必须同时存在。
const PAIRS: { inject: RegExp; restore: RegExp[]; label: string }[] = [
  {
    inject: /settings put global low_power/,
    restore: [/settings get global low_power/, /settings delete global low_power/],
    label: "低电量模式:settings put ←→ settings get/delete 恢复",
  },
  {
    inject: /dumpsys battery set ac/,
    restore: [/dumpsys battery reset/],
    label: "充电状态:dumpsys battery set ←→ dumpsys battery reset",
  },
  {
    inject: /wm user-rotation lock/,
    restore: [/wm user-rotation free/],
    label: "旋转:wm user-rotation lock ←→ free",
  },
  {
    inject: /cmd window user-rotation lock/,
    restore: [/cmd window user-rotation free/],
    label: "旋转:cmd window user-rotation lock ←→ free",
  },
  {
    inject: /deviceidle force-idle/,
    restore: [/deviceidle unforce/],
    label: "Doze:force-idle ←→ unforce",
  },
  {
    inject: /am send-trim-memory/,
    restore: [],
    label: "trim-memory 只读注入(无需恢复)",
  },
  {
    inject: /am force-stop/,
    restore: [/bringForeground/],
    label: "第三方播放器:force-stop 后回前台",
  },
];
for (const pair of PAIRS) {
  if (!pair.inject.test(envSource)) continue;
  const missing = pair.restore.filter((r) => !r.test(envSource));
  record("副作用配对:" + pair.label, missing.length === 0, missing.length ? "缺恢复手段: " + missing.map(String).join(",") : "ok");
}

// ---------------------------------------------------------------------------
// 7. 红线:环境场景不改用户数据(不写 A/B),重启场景必须默认 SKIP
// ---------------------------------------------------------------------------
record(
  "环境场景不写库里的 A/B(不调用 /confirm)",
  !envSource.includes('"/confirm"'),
  envSource.includes('"/confirm"') ? "出现了 /confirm:环境事件不该改用户标记的循环区" : "未使用 /confirm",
);
const rebootScenarios = ENV_SCENARIOS.filter((s) => String(s.run).includes("reboot"));
for (const s of rebootScenarios) {
  const src = String(s.run);
  record(
    "重启场景 " + s.id + " 必须默认可跳过",
    s.long === true && src.includes("HESI_ENV_REBOOT"),
    "long=" + String(s.long) + " envGate=" + src.includes("HESI_ENV_REBOOT"),
  );
}

// ---------------------------------------------------------------------------
// 8. 计划:估算总耗时,方便排跑窗口
// ---------------------------------------------------------------------------
const rows = ENV_SCENARIOS.map((s) => {
  const b = ENV_SCENARIO_BUDGETS[s.id];
  return {
    id: s.id,
    key: s.key,
    name: s.name,
    tag: s.tag,
    exclusive: s.exclusive === true,
    long: s.long === true,
    timeoutMs: s.timeoutMs ?? RUNNER_DEFAULT_TIMEOUT_MS,
    expectedMs: b?.expectedMs ?? 0,
    ui: b?.ui === true,
    mutations: b?.deviceMutations.length ?? 0,
    skipProne: b?.skipProne.length ?? 0,
  };
});

const pad = (text: string | number, width: number): string => {
  const s = String(text);
  return s.length >= width ? s : s + " ".repeat(width - s.length);
};
const head = ["id", "key", "excl", "long", "timeout", "expect", "ui", "副作用", "SKIP风险"];
const widths = head.map((h, i) => Math.max(h.length, ...rows.map((r) => String([r.id, r.key, r.exclusive ? "yes" : "-", r.long ? "yes" : "-", r.timeoutMs + "ms", r.expectedMs + "ms", r.ui ? "yes" : "-", r.mutations, r.skipProne][i]).length)));

const totalExpected = rows.reduce((sum, r) => sum + r.expectedMs, 0);
const exclusiveExpected = rows.filter((r) => r.exclusive).reduce((sum, r) => sum + r.expectedMs, 0);
const longExpected = rows.filter((r) => r.long).reduce((sum, r) => sum + r.expectedMs, 0);

// ---------------------------------------------------------------------------
// 输出
// ---------------------------------------------------------------------------
console.log("== scenarios-env 离线自检 ==");
console.log("文件: " + ENV_FILE);
console.log("场景: " + ENV_SCENARIOS.length + " 条 (id " + scenarioIds.join(",") + ")");
console.log("");
console.log(widths.map((w, i) => pad(head[i], w)).join("  "));
console.log(widths.map((w) => "-".repeat(w)).join("  "));
for (const r of rows) {
  const cells = [r.id, r.key, r.exclusive ? "yes" : "-", r.long ? "yes" : "-", r.timeoutMs + "ms", r.expectedMs + "ms", r.ui ? "yes" : "-", r.mutations, r.skipProne];
  console.log(widths.map((w, i) => pad(cells[i], w)).join("  "));
}
console.log("");
console.log("预计总耗时: " + Math.round(totalExpected / 1000) + "s (独占组 " + Math.round(exclusiveExpected / 1000) + "s, long 组 " + Math.round(longExpected / 1000) + "s)");
console.log("");

const failed = findings.filter((f) => !f.ok);
const passed = findings.length - failed.length;
console.log("规则检查: " + passed + "/" + findings.length + " 通过");
for (const f of findings) {
  if (!f.ok) console.log("  FAIL  " + f.rule + " -> " + f.detail);
}
console.log("");
if (failed.length > 0) {
  console.log("结果: FAIL (" + failed.length + " 条规则未通过)");
  process.exit(1);
}
console.log("结果: PASS (代码形状自洽;判据是否成立仍需真机 --only 跑)");
