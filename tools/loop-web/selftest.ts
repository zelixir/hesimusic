// Self-test for tools/loop-web.
// Run: bun run selftest.ts   (from tools/loop-web, or: bun run tools/loop-web/selftest.ts)
//
// Checks:
//  1. synthetic 20 s music -> baseline produces candidates, deterministic, <3 s
//  2. JS baseline vs the existing C++ CLI (exact loop points, near-equal metrics)
//  3. algorithm D recall metadata is populated
//  4. algorithm E finds a 2 s snippet copied at 20.5 s (A=0.5, B=21.0, peak>0.9)
//  5. overlap-rule guard actually drops the self-overlapping anchor pairs
import { writeFileSync, mkdirSync, readFileSync, existsSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { analyze, defaultParams } from './public/analysis/pipeline.js';

const DIR = new URL('./.selftest/', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1');
mkdirSync(DIR, { recursive: true });

let failures = 0;
function check(cond, label, detail) {
  if (cond) {
    console.log('PASS  ' + label + (detail ? '  [' + detail + ']' : ''));
  } else {
    failures++;
    console.log('FAIL  ' + label + (detail ? '  [' + detail + ']' : ''));
  }
}

function music(seconds, sr) {
  const n = Math.trunc(seconds * sr);
  const out = new Float32Array(n);
  const TAU = Math.PI * 2;
  const chords = [[220, 277.183, 329.628], [246.942, 311.127, 369.994]];
  const melody = [440, 523.251, 587.33, 659.255, 783.991, 659.255, 587.33, 523.251];
  let seed = 12345 >>> 0;
  const rnd = () => {
    seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0;
    return ((seed >>> 8) / 16777216) * 2 - 1;
  };
  for (let i = 0; i < n; i++) {
    const t = i / sr;
    const tl = t % 4;
    const section = Math.floor(tl / 2) & 1;
    let v = 0;
    for (let c = 0; c < 3; c++) v += 0.18 * Math.sin(TAU * chords[section][c] * t);
    const step = Math.floor(tl / 0.25) & 7;
    const env = Math.exp(-(tl % 0.25) * 6);
    v += 0.22 * env * Math.sin(TAU * melody[step] * t);
    const kt = tl % 0.5;
    v += 0.55 * Math.exp(-kt * 18) * Math.sin(TAU * (55 - 25 * kt) * t);
    const ht = tl % 0.125;
    v += 0.06 * Math.exp(-ht * 80) * rnd();
    out[i] = Math.max(-1, Math.min(1, v));
  }
  return out;
}

function writeWav16(path, pcm, sr) {
  const n = pcm.length;
  const buf = Buffer.alloc(44 + n * 2);
  buf.write('RIFF', 0);
  buf.writeUInt32LE(36 + n * 2, 4);
  buf.write('WAVE', 8);
  buf.write('fmt ', 12);
  buf.writeUInt32LE(16, 16);
  buf.writeUInt16LE(1, 20);
  buf.writeUInt16LE(1, 22);
  buf.writeUInt32LE(sr, 24);
  buf.writeUInt32LE(sr * 2, 28);
  buf.writeUInt16LE(2, 32);
  buf.writeUInt16LE(16, 34);
  buf.write('data', 36);
  buf.writeUInt32LE(n * 2, 40);
  for (let i = 0; i < n; i++) {
    buf.writeInt16LE(Math.max(-32768, Math.min(32767, Math.round(pcm[i] * 32767))), 44 + i * 2);
  }
  writeFileSync(path, buf);
}

function readWav16(path) {
  const b = readFileSync(path);
  const channels = b.readUInt16LE(22);
  const sr = b.readUInt32LE(24);
  const bits = b.readUInt16LE(34);
  if (bits !== 16) throw new Error('self-test expects 16-bit PCM');
  const frames = Math.floor((b.length - 44) / 2 / channels);
  const pcm = new Float32Array(frames);
  for (let i = 0; i < frames; i++) {
    let s = 0;
    for (let c = 0; c < channels; c++) s += b.readInt16LE(44 + (i * channels + c) * 2);
    pcm[i] = s / channels / 32768;
  }
  return { pcm, sr, channels };
}

function runCli(wavPath) {
  const candidates = [
    new URL('../loop-engine-cli/build/loop-engine-cli.exe', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1'),
    'D:\\Code\\hesimusic\\tools\\loop-engine-cli\\build\\loop-engine-cli.exe',
  ];
  const exe = candidates.find((p) => existsSync(p));
  if (!exe) return null;
  const res = spawnSync(exe, [wavPath, '--max', '5', '--json'], { encoding: 'utf8', maxBuffer: 1 << 24 });
  if (res.status !== 0 || !res.stdout) return { error: res.stderr || 'cli failed' };
  const start = res.stdout.indexOf('{');
  if (start < 0) return { error: 'no json in cli output' };
  return JSON.parse(res.stdout.slice(start));
}

// ------------------------------------------------------------------ test 1
const SR = 44100;
const synth = music(20, SR);
const synthPath = DIR + 'synth-20s.wav';
writeWav16(synthPath, synth, SR);
const input = readWav16(synthPath);
const p20 = defaultParams();
p20.algorithms = { baseline: true, seam: true, subbeat: true, lsh: false, whole: false };
p20.topN = 5;

console.log('\n=== 1) baseline on synthetic 20 s music ===');
const t0 = performance.now();
const r1 = analyze(Float32Array.from(input.pcm), input.sr, p20);
const ms1 = performance.now() - t0;
check(r1.ok, 'analysis returns ok', r1.ok ? '' : r1.error);
const baseline = r1.algorithms.baseline;
check(baseline.candidates.length > 0, 'baseline produces candidates', baseline.candidates.length + ' candidates');
if (baseline.candidates.length) {
  const c = baseline.candidates[0];
  console.log('      top1: A=' + c.aMs.toFixed(1) + 'ms B=' + c.bMs.toFixed(1) + 'ms len=' + (c.lengthMs / 1000).toFixed(3) + 's score=' + c.score.toFixed(4) + ' seam=' + (c.seam ? c.seam.quality.toFixed(3) : 'n/a'));
  check(c.bSample > c.aSample, 'top1 endpoints ordered');
  check(c.seam && c.seam.quality >= 0 && c.seam.quality <= 1, 'seamQuality is in 0..1');
}
check(ms1 < 3000, '20 s analysis under 3 s', ms1.toFixed(0) + ' ms');
console.log('      timings: feature=' + (baseline.telemetry.timePerFrameMs.toFixed(3)) + ' ms/frame, pairs/beat=' + baseline.telemetry.pairsPerBeat.toFixed(1) + ', total=' + ms1.toFixed(0) + ' ms');

const r1b = analyze(Float32Array.from(input.pcm), input.sr, p20);
const same = baseline.candidates.every((c, i) => {
  const d = r1b.algorithms.baseline.candidates[i];
  return d && c.aSample === d.aSample && c.bSample === d.bSample;
});
check(same, 'deterministic loop points across runs');

// ------------------------------------------------------------------ test 2
console.log('\n=== 2) JS baseline vs C++ loop-engine-cli ===');
const cli = runCli(synthPath);
if (!cli || cli.error) {
  console.log('SKIP  C++ CLI not available (' + (cli ? cli.error : 'not found') + ')');
} else {
  const n = Math.min(5, cli.candidates.length, baseline.candidates.length);
  check(n > 0, 'CLI produced candidates', String(cli.candidates.length));
  let maxA = 0;
  let maxB = 0;
  let maxScore = 0;
  let maxNd = 0;
  let maxLd = 0;
  for (let i = 0; i < n; i++) {
    const a = baseline.candidates[i];
    const b = cli.candidates[i];
    maxA = Math.max(maxA, Math.abs(a.aSample - b.loopStart));
    maxB = Math.max(maxB, Math.abs(a.bSample - b.loopEnd));
    maxScore = Math.max(maxScore, Math.abs(a.score - b.score));
    maxNd = Math.max(maxNd, Math.abs(a.chromaDist - b.noteDistance));
    maxLd = Math.max(maxLd, Math.abs(a.loudnessDiff - b.loudnessDifference));
    console.log('      #' + (i + 1) + ' JS A/B=' + a.aSample + '/' + a.bSample + '  CLI=' + b.loopStart + '/' + b.loopEnd + '  score ' + a.score.toFixed(6) + ' vs ' + b.score.toFixed(6));
  }
  check(maxA === 0 && maxB === 0, 'loop points identical to C++', 'max |dA|=' + maxA + ' |dB|=' + maxB);
  check(maxScore < 2e-5, 'score matches C++ tolerantly', 'max |dscore|=' + maxScore.toExponential(2));
  check(maxNd < 2e-4, 'chroma distance matches', 'max |dnd|=' + maxNd.toExponential(2));
  check(maxLd < 1e-5, 'loudness difference matches', 'max |dld|=' + maxLd.toExponential(2));
}

// ------------------------------------------------------------------ test 3
console.log('\n=== 3) algorithm D recall metadata ===');
const pD = defaultParams();
pD.algorithms = { baseline: true, seam: true, subbeat: false, lsh: true, whole: false };
pD.topN = 10;
const rD = analyze(Float32Array.from(input.pcm), input.sr, pD);
check(rD.ok, 'D analysis ok');
if (rD.ok) {
  check(!!rD.recall, 'recall block present');
  check(rD.algorithms.lsh.candidates.length > 0, 'D produces candidates', String(rD.algorithms.lsh.candidates.length));
  if (rD.recall) {
    console.log('      bruteAll=' + rD.recall.bruteAllCount + ' lshAll=' + rD.recall.lshAllCount + ' recallAll=' + (rD.recall.allRatio * 100).toFixed(1) + '% topN membership=' + (rD.recall.topNMembershipRatio * 100).toFixed(1) + '% top1same=' + rD.recall.top1Same);
    check(Array.isArray(rD.recall.sets.bruteTop) && Array.isArray(rD.recall.sets.lshTop), 'export sets present');
  }
}

// ------------------------------------------------------------------ test 4
console.log('\n=== 4) algorithm E: 2 s snippet copied at 20.5 s (0.5 s offset) ===');
const secs = 30;
const srE = 44100;
const ePcm = new Float32Array(secs * srE);
let seed = 777 >>> 0;
const rnd = () => {
  seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0;
  return (seed / 4294967296) * 2 - 1;
};
const len = 2 * srE;
const snippet = new Float64Array(len);
for (let i = 0; i < len; i++) {
  const t = i / srE;
  snippet[i] = 0.5 * Math.sin(2 * Math.PI * 227 * t) + 0.3 * Math.sin(2 * Math.PI * 389 * t) + 0.1 * rnd();
}
for (let i = 0; i < len; i++) ePcm[i] = snippet[i];
const at = Math.trunc(20.5 * srE);
for (let i = 0; i < len; i++) ePcm[at + i] = snippet[i];
const ePath = DIR + 'e-copy-30s.wav';
writeWav16(ePath, ePcm, srE);
const eInput = readWav16(ePath);
const pE = defaultParams();
pE.algorithms = { baseline: false, seam: false, subbeat: false, lsh: false, whole: true };
pE.e.verifyMaxPairs = 200;
pE.e.verifyBudgetMs = 4000;
const tE0 = performance.now();
const rE = analyze(Float32Array.from(eInput.pcm), eInput.sr, pE);
const msE = performance.now() - tE0;
check(rE.ok, 'E analysis ok', rE.ok ? '' : rE.error);
if (rE.ok) {
  const e = rE.algorithms.whole;
  check(e.candidates.length > 0, 'E produces candidates', String(e.candidates.length));
  const matches = e.telemetry.matches || [];
  const target = matches.filter((m) => Math.abs(m.anchorA - 0.5) < 0.01 && Math.abs(m.anchorB - 21.0) < 0.01 && m.peak > 0.9);
  check(target.length > 0, 'found anchorA=0.5, anchorB=21.0 with peak>0.9', target.length ? ('peak=' + target[0].peak.toFixed(4) + ' lag=' + target[0].lagMs.toFixed(1) + 'ms') : 'no exact anchor pair');
  const equiv = matches.filter((m) => Math.abs((m.anchorB + m.lagMs / 1000) - (m.anchorA + 20.5)) < 0.15 && m.peak > 0.9);
  check(equiv.length > 0, 'equivalent 20.5 s offset matches exist', String(equiv.length));
  check(e.telemetry.stats.overlapDropped > 0, 'overlap guard dropped self-overlapping pairs', String(e.telemetry.stats.overlapDropped));
  console.log('      stages: feature=' + e.telemetry.featureMs.toFixed(0) + 'ms compress=' + e.telemetry.compressMs.toFixed(0) + 'ms coarse=' + e.telemetry.coarseMs.toFixed(0) + 'ms verify=' + e.telemetry.verifyMs.toFixed(0) + 'ms select=' + e.telemetry.selectMs.toFixed(0) + 'ms; total=' + msE.toFixed(0) + 'ms');
  check(!!e.telemetry.quality, 'compression quality report present');
}

// ------------------------------------------------------------------ summary
console.log('\n=== summary ===');
console.log(failures === 0 ? 'ALL CHECKS PASSED' : failures + ' CHECK(S) FAILED');
console.log('self-test artifacts: ' + DIR);
process.exit(failures === 0 ? 0 : 1);
