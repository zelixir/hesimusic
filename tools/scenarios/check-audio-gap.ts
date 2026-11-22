#!/usr/bin/env bun
/** 离线自测：writtenFrames 停写段分析（不占设备）。 */
import { analyzeWriteGaps, writeGapCheck } from "./harness";

function s(atMs: number, frames: number, ok = true) {
  return { atMs, frames, ok, error: ok ? undefined : "fail" };
}
function fail(atMs: number) {
  return { atMs, frames: NaN, ok: false, error: "timeout" };
}

let failed = 0;
function expect(name: string, got: any, want: any) {
  const ok = JSON.stringify(got) === JSON.stringify(want);
  if (!ok) failed++;
  console.log((ok ? "PASS " : "FAIL ") + name + " got=" + JSON.stringify(got) + " want=" + JSON.stringify(want));
}

// 1) 连续写帧：无停写段
const continuous = Array.from({ length: 100 }, (_, i) => s(1000 + i * 10, 44100 + i * 441));
expect("连续写帧无段", analyzeWriteGaps(continuous).length, 0);

// 2) 单段 80ms 停写：40 帧后 flat 80ms，再继续
const one: any[] = [];
let f = 1000;
for (let i = 0; i < 20; i++) one.push(s(1000 + i * 10, (f += 441)));
const flatStart = 1000 + 20 * 10;
for (let i = 0; i < 8; i++) one.push(s(flatStart + i * 10, f)); // 80ms
for (let i = 0; i < 20; i++) one.push(s(flatStart + 80 + i * 10, (f += 441)));
const g1 = analyzeWriteGaps(one);
expect("单段数量", g1.length, 1);
expect("单段时长", Math.round(g1[0].durationMs), 90);

// 3) 两段 40ms
const two: any[] = [];
f = 1000;
for (let i = 0; i < 10; i++) two.push(s(2000 + i * 10, (f += 441)));
let t = 2000 + 100;
for (let k = 0; k < 2; k++) {
  for (let i = 0; i < 4; i++) two.push(s(t + i * 10, f));
  t += 40;
  for (let i = 0; i < 6; i++) two.push(s(t + i * 10, (f += 441)));
  t += 60;
}
expect("两段数量", analyzeWriteGaps(two).length, 2);

// 4) 采样失败不算停写：中间插入失败，且前后 dt 超过 maxPair 被断开
const failMid = [s(3000, 1000), s(3010, 1000), fail(3020), s(3030, 1000), s(3040, 1441)];
expect("失败样本不造成停写", analyzeWriteGaps(failMid).length, 0);

// 5) 长采样空洞不当作停写
const hole = [s(4000, 1000), s(4100, 1000), s(4200, 1441)];
expect("采样丢失不当作停写", analyzeWriteGaps(hole).length, 0);

// 6) 末尾 open 段
const open = [s(5000, 1000), s(5010, 1000), s(5020, 1000), s(5030, 1000)];
const go = analyzeWriteGaps(open);
expect("末尾 open 段", go.length, 1);
expect("末尾 open 标记", go[0].open, true);

// 7) 断言包装
expect("writeGapCheck 通过", writeGapCheck("x", g1, 1, 120).pass, true);
expect("writeGapCheck 超阈值", writeGapCheck("x", g1, 1, 50).pass, false);

console.log(failed ? "SELFTEST FAIL " + failed : "SELFTEST PASS");
process.exit(failed ? 1 : 0);
