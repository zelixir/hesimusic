// Loop Lab — single-page client. Pure browser ES module, no external deps.

const $ = (sel) => document.querySelector(sel);
const ALGO = {
  baseline: { label: 'A baseline', short: 'A', color: '#4ea1ff', cls: 'baseline' },
  seam: { label: 'B +seamQuality', short: 'B', color: '#f5a524' },
  subbeat: { label: 'C +sub-beat', short: 'C', color: '#a78bfa' },
  lsh: { label: 'D LSH', short: 'D', color: '#34d399' },
  whole: { label: 'E 整秒重合', short: 'E', color: '#fb7185' },
  bruteOnly: { label: 'A 暴力（仅暴力）', short: 'A*', color: '#f0b7c0' },
};
const ALGO_KEYS = ['baseline', 'seam', 'subbeat', 'lsh', 'whole'];
const CHECKBOX = { baseline: '#algoA', seam: '#algoB', subbeat: '#algoC', lsh: '#algoD', whole: '#algoE' };
const STAGE_LABELS = {
  preprocess: '预处理',
  features: 'STFT / 特征',
  stftA: 'STFT (1/2)',
  chroma: 'chroma / mel',
  rhythm: '节拍 / PLP',
  pairs: '配对',
  score: '评分',
  'e-coarse': 'E 粗筛',
  'e-verify': 'E 波形验证',
};
const FILE_EXT = /\.(mp3|flac|ogg|wav|m4a|aac|opus|webm)$/i;

const state = {
  file: null,
  audio: null,
  sr: 44100,
  channels: 0,
  duration: 0,
  decodeMs: 0,
  envelope: null,
  canvasW: 0,
  canvasH: 0,
  worker: null,
  jobId: 0,
  analyzing: false,
  result: null,
  paramsSnapshot: null,
  selected: null,
  markers: [],
  hoverX: null,
  audioCtx: null,
  masterGain: null,
  source: null,
  seamTimer: null,
  seamNodes: [],
  seamNextTime: 0,
  seamIsLeft: true,
  playing: false,
  mode: 'loop',
  playStart: null,
  playheadSample: null,
  raf: 0,
  progressTimer: 0,
  lastElapsed: 0,
};

function fmtMs(v, digits = 0) {
  return Number.isFinite(v) ? v.toFixed(digits) : '—';
}
function fmtSigned(v, digits = 1) {
  if (!Number.isFinite(v)) return '—';
  return (v >= 0 ? '+' : '') + v.toFixed(digits);
}
function fmtSec(v, digits = 3) {
  return Number.isFinite(v) ? v.toFixed(digits) + 's' : '—';
}
function fmtClock(sec) {
  const m = Math.floor(sec / 60);
  const s = sec - m * 60;
  return m > 0 ? m + ':' + s.toFixed(1).padStart(4, '0') : s.toFixed(1) + 's';
}
function fmtBytes(n) {
  if (!n) return '—';
  if (n < 1024) return n + ' B';
  if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB';
  return (n / 1024 / 1024).toFixed(1) + ' MB';
}
function pct(v, digits = 1) {
  return Number.isFinite(v) ? (v * 100).toFixed(digits) + '%' : '—';
}
function metric(label, value, wide) {
  return '<div class="metric' + (wide ? ' wide' : '') + '"><span>' + label + '</span><b>' + value + '</b></div>';
}
function rowHtml(label, value) {
  return '<div class="tt-row"><span>' + label + '</span><b>' + value + '</b></div>';
}
function setStatus(text, kind) {
  const pill = $('#statusPill');
  pill.textContent = text;
  pill.className = 'status-pill' + (kind ? ' ' + kind : '');
}
function isAlgoEnabled(key) {
  return !!(document.querySelector(CHECKBOX[key]) || {}).checked;
}

// --------------------------------------------------------------- audio core
function ensureCtx() {
  if (!state.audioCtx) {
    const Ctor = window.AudioContext || window.webkitAudioContext;
    state.audioCtx = new Ctor();
    state.masterGain = state.audioCtx.createGain();
    state.masterGain.gain.value = parseFloat($('#volume').value) || 0.8;
    state.masterGain.connect(state.audioCtx.destination);
  }
  return state.audioCtx;
}
function resumeCtx() {
  const ctx = ensureCtx();
  if (ctx.state === 'suspended') {
    // Do not await: a blocked autoplay resume must not leave the UI stuck.
    const p = ctx.resume();
    if (p && p.catch) p.catch(() => {});
  }
  return ctx;
}

// ------------------------------------------------------------ file handling
function adoptAudio(audio, fileMeta, decodeMs) {
  stopPlayback();
  state.audio = audio;
  state.sr = audio.sampleRate;
  state.channels = audio.numberOfChannels;
  state.duration = audio.duration;
  state.decodeMs = decodeMs || 0;
  state.file = fileMeta;
  state.result = null;
  state.selected = null;
  state.paramsSnapshot = null;
  state.progressTimer = 0;

  $('#fileInfo').classList.remove('hidden');
  $('#dropzone').classList.add('hidden');
  $('#fileName').textContent = fileMeta.name;
  const badges = [
    '<span class="badge">时长 <b>' + fmtClock(audio.duration) + '</b></span>',
    '<span class="badge">采样率 <b>' + audio.sampleRate + ' Hz</b></span>',
    '<span class="badge">声道 <b>' + audio.numberOfChannels + '</b></span>',
    '<span class="badge">解码 <b>' + Math.round(decodeMs || 0) + ' ms</b></span>',
  ];
  if (fileMeta.size) badges.push('<span class="badge">文件 <b>' + fmtBytes(fileMeta.size) + '</b></span>');
  $('#fileBadges').innerHTML = badges.join('');

  $('#minLoop').placeholder = 'auto ' + (0.35 * audio.duration).toFixed(2) + ' s';
  $('#maxLoop').placeholder = 'auto ' + audio.duration.toFixed(2) + ' s';
  $('#exportBtn').disabled = true;
  $('#playBtn').disabled = true;
  $('#jumpSeamBtn').disabled = true;
  $('#playerTitle').textContent = '未选择候选';
  $('#position').textContent = '0.0 s';
  $('#emptyResults').classList.remove('hidden');
  $('#emptyResults').textContent = '正在准备分析…';
  $('#recallBadge').classList.add('hidden');
  $('#recallPanel').classList.add('hidden');
  $('#algoCards').innerHTML = '';
  $('#resultBody').innerHTML = '';
  $('#debugPanel').classList.add('hidden');
  $('#longWarning').classList.toggle('hidden', audio.duration <= 600);

  state.envelope = null;
  fitCanvases(true);
  drawWave();
  drawOverlay();
  setStatus('已载入 · 准备分析', 'ok');
}

async function loadFile(file) {
  if (!file) return;
  if (state.analyzing) cancelAnalysis();
  if (!FILE_EXT.test(file.name) && !(file.type && file.type.startsWith('audio/'))) {
    setStatus('不支持的文件类型', 'error');
    return;
  }
  stopPlayback();
  setStatus('解码中…', 'busy');
  try {
    const ctx = ensureCtx();
    const arr = await file.arrayBuffer();
    const t0 = performance.now();
    const audio = await ctx.decodeAudioData(arr);
    const decodeMs = performance.now() - t0;
    adoptAudio(audio, { name: file.name, size: file.size, type: file.type || '' }, decodeMs);
    runAnalysis();
  } catch (err) {
    console.error(err);
    setStatus('解码失败：' + (err && err.message ? err.message : err), 'error');
    $('#emptyResults').textContent = '解码失败。请换一个 mp3/flac/ogg/wav/m4a 文件。';
  }
}

function makeDemoMusic(seconds, sr) {
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

async function loadDemo() {
  try {
    if (state.analyzing) cancelAnalysis();
    const ctx = ensureCtx();
    const sr = 44100;
    const pcm = makeDemoMusic(20, sr);
    const audio = ctx.createBuffer(1, pcm.length, sr);
    audio.copyToChannel(pcm, 0);
    adoptAudio(audio, { name: '合成测试音频 (20s).wav', size: pcm.length * 2, demo: true }, 0);
    runAnalysis();
  } catch (err) {
    console.error(err);
    setStatus('无法创建测试音频：' + err, 'error');
  }
}

// -------------------------------------------------------------- waveform
function computeEnvelope(buckets) {
  const audio = state.audio;
  const n = audio.length;
  const ch = audio.numberOfChannels;
  const chans = [];
  for (let c = 0; c < ch; c++) chans.push(audio.getChannelData(c));
  const min = new Float32Array(buckets);
  const max = new Float32Array(buckets);
  for (let b = 0; b < buckets; b++) {
    const start = Math.floor((b * n) / buckets);
    const end = Math.max(start + 1, Math.min(n, Math.floor(((b + 1) * n) / buckets)));
    let lo = 0;
    let hi = 0;
    for (let i = start; i < end; i++) {
      let v = 0;
      for (let c = 0; c < ch; c++) v += chans[c][i];
      v /= ch;
      if (v < lo) lo = v;
      if (v > hi) hi = v;
    }
    min[b] = lo;
    max[b] = hi;
  }
  state.envelope = { min, max, count: buckets };
}

function fitCanvases(forceEnvelope) {
  const wrap = $('#waveWrap');
  const rect = wrap.getBoundingClientRect();
  const w = Math.max(100, rect.width);
  const h = Math.max(100, rect.height);
  const dpr = Math.min(2, window.devicePixelRatio || 1);
  state.canvasW = w;
  state.canvasH = h;
  for (const id of ['#waveCanvas', '#overlayCanvas']) {
    const c = $(id);
    c.width = Math.round(w * dpr);
    c.height = Math.round(h * dpr);
    const ctx = c.getContext('2d');
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  }
  if (state.audio && (forceEnvelope || !state.envelope || state.envelope.count !== Math.floor(w))) {
    computeEnvelope(Math.max(300, Math.floor(w)));
  }
}

function niceTimeStep(duration, width) {
  const target = duration / Math.max(4, Math.floor(width / 110));
  const steps = [0.25, 0.5, 1, 2, 5, 10, 15, 30, 60, 120, 300, 600];
  for (const s of steps) if (s >= target) return s;
  return 600;
}

function drawWave() {
  const canvas = $('#waveCanvas');
  const ctx = canvas.getContext('2d');
  const w = state.canvasW;
  const h = state.canvasH;
  ctx.clearRect(0, 0, w, h);
  const mid = h / 2;
  ctx.strokeStyle = 'rgba(140,170,200,.14)';
  ctx.lineWidth = 1;
  ctx.beginPath();
  ctx.moveTo(0, mid + 0.5);
  ctx.lineTo(w, mid + 0.5);
  ctx.stroke();

  if (state.audio && state.envelope) {
    const env = state.envelope;
    const amp = h * 0.4;
    const grad = ctx.createLinearGradient(0, mid - amp, 0, mid + amp);
    grad.addColorStop(0, 'rgba(124,196,255,.95)');
    grad.addColorStop(0.5, 'rgba(78,161,255,.75)');
    grad.addColorStop(1, 'rgba(167,139,250,.72)');
    ctx.fillStyle = grad;
    for (let x = 0; x < w; x++) {
      const b = Math.min(env.count - 1, Math.floor((x / w) * env.count));
      const top = mid - env.max[b] * amp;
      const bot = mid - env.min[b] * amp;
      ctx.fillRect(x, top, 1, Math.max(1, bot - top));
    }
  }

  if (state.audio) {
    const step = niceTimeStep(state.duration, w);
    ctx.font = '11px ui-monospace, Consolas, monospace';
    ctx.fillStyle = 'rgba(143,163,186,.8)';
    ctx.textAlign = 'left';
    ctx.textBaseline = 'bottom';
    ctx.strokeStyle = 'rgba(255,255,255,.06)';
    for (let t = 0; t <= state.duration + 1e-9; t += step) {
      const x = (t / state.duration) * w;
      ctx.beginPath();
      ctx.moveTo(x + 0.5, 0);
      ctx.lineTo(x + 0.5, h - 16);
      ctx.stroke();
      const label = step < 1 ? t.toFixed(2) + 's' : fmtClock(t);
      ctx.fillText(label, Math.min(w - 44, x + 4), h - 3);
    }
  }
}

function drawPairMarkers(cand, color, selected, markers, alg, index) {
  const w = state.canvasW;
  const h = state.canvasH;
  if (!state.duration || !w) return;
  const ctx = $('#overlayCanvas').getContext('2d');
  const xA = (cand.aSample / state.sr / state.duration) * w;
  const xB = (cand.bSample / state.sr / state.duration) * w;
  ctx.save();
  ctx.globalAlpha = selected ? 0.98 : (state.showAll ? 0.34 : 0.52);
  ctx.strokeStyle = color;
  ctx.lineWidth = selected ? 2 : 1;
  ctx.beginPath();
  ctx.moveTo(xA + 0.5, 0);
  ctx.lineTo(xA + 0.5, h);
  ctx.stroke();
  ctx.setLineDash([4, 4]);
  ctx.beginPath();
  ctx.moveTo(xB + 0.5, 0);
  ctx.lineTo(xB + 0.5, h);
  ctx.stroke();
  ctx.setLineDash([]);
  ctx.fillStyle = color;
  ctx.beginPath();
  ctx.moveTo(xA - 4, 0);
  ctx.lineTo(xA + 4, 0);
  ctx.lineTo(xA, 7);
  ctx.closePath();
  ctx.fill();
  ctx.beginPath();
  ctx.moveTo(xB - 4, h);
  ctx.lineTo(xB + 4, h);
  ctx.lineTo(xB, h - 7);
  ctx.closePath();
  ctx.fill();
  if (selected) {
    ctx.globalAlpha = 1;
    ctx.font = '600 11px ui-monospace, Consolas, monospace';
    ctx.fillStyle = color;
    ctx.textAlign = 'left';
    ctx.textBaseline = 'top';
    ctx.fillText('A', Math.min(w - 10, xA + 5), 6);
    ctx.textBaseline = 'bottom';
    ctx.fillText('B', Math.min(w - 10, xB + 5), h - 6);
  }
  ctx.restore();
  markers.push({ x: xA, alg, index, cand, kind: 'A' });
  markers.push({ x: xB, alg, index, cand, kind: 'B' });
}

function drawOverlay() {
  const canvas = $('#overlayCanvas');
  const ctx = canvas.getContext('2d');
  const w = state.canvasW;
  const h = state.canvasH;
  ctx.clearRect(0, 0, w, h);
  state.markers = [];
  const r = state.result;
  if (r && r.ok) {
    for (const alg of ALGO_KEYS) {
      if (!isAlgoEnabled(alg)) continue;
      const group = r.algorithms[alg];
      if (!group || !group.candidates.length) continue;
      const selectedHere = state.selected && state.selected.alg === alg;
      const limit = state.showAll ? group.candidates.length : Math.min(3, group.candidates.length);
      const meta = ALGO[alg];
      for (let i = 0; i < group.candidates.length; i++) {
        const isSel = selectedHere && state.selected.index === i;
        if (i >= limit && !isSel) continue;
        drawPairMarkers(group.candidates[i], meta.color, isSel, state.markers, alg, i);
      }
    }
  }
  // hover guide
  if (state.hoverX != null && state.audio) {
    ctx.save();
    ctx.strokeStyle = 'rgba(255,255,255,.24)';
    ctx.setLineDash([2, 3]);
    ctx.beginPath();
    ctx.moveTo(state.hoverX + 0.5, 0);
    ctx.lineTo(state.hoverX + 0.5, h);
    ctx.stroke();
    ctx.restore();
  }
  // playhead
  if (state.playing && state.mode === 'loop' && Number.isFinite(state.playheadSample)) {
    const x = (state.playheadSample / state.sr / state.duration) * w;
    ctx.save();
    ctx.strokeStyle = 'rgba(245,197,66,.95)';
    ctx.lineWidth = 1.5;
    ctx.shadowColor = 'rgba(245,197,66,.8)';
    ctx.shadowBlur = 8;
    ctx.beginPath();
    ctx.moveTo(x + 0.5, 0);
    ctx.lineTo(x + 0.5, h);
    ctx.stroke();
    ctx.restore();
  }
}

// --------------------------------------------------------------- analysis
function numValue(sel, fallback) {
  const raw = $(sel).value.trim();
  if (raw === '') return fallback;
  const v = parseFloat(raw);
  return Number.isFinite(v) ? v : fallback;
}

function collectParams() {
  const minRaw = $('#minLoop').value.trim();
  const maxRaw = $('#maxLoop').value.trim();
  const strideOn = $('#beatStrideEnable').checked;
  return {
    algorithms: {
      baseline: $('#algoA').checked,
      seam: $('#algoB').checked,
      subbeat: $('#algoC').checked,
      lsh: $('#algoD').checked,
      whole: $('#algoE').checked,
    },
    minLoopSec: minRaw === '' ? null : Math.max(0.05, parseFloat(minRaw)),
    maxLoopSec: maxRaw === '' ? null : Math.max(0.1, parseFloat(maxRaw)),
    chromaThreshold: Math.max(0.0001, numValue('#chromaThreshold', 0.0875)),
    dbThreshold: Math.max(0, numValue('#dbThreshold', 0.5)),
    topN: Math.max(1, Math.min(50, Math.round(numValue('#topN', 10)))),
    seamSort: $('#seamSort').checked,
    minSeamQuality: Math.max(0, Math.min(1, numValue('#minSeamQuality', 0))),
    seamPool: Math.max(1, Math.min(300, Math.round(numValue('#seamPool', 40)))),
    subbeatCandidates: Math.max(1, Math.min(10, Math.round(numValue('#subbeatCandidates', 3)))),
    subbeatBudgetMs: Math.max(100, numValue('#subbeatBudget', 1200)),
    beatStride: strideOn ? Math.max(2, Math.min(16, Math.round(numValue('#beatStride', 2)))) : 1,
    d: {
      tables: Math.max(1, Math.min(32, Math.round(numValue('#dTables', 8)))),
      width: Math.max(0.02, Math.min(2, numValue('#dWidth', 0.3))),
      int8: $('#dInt8').checked,
      pca: $('#dPca').checked,
      refinePool: Math.max(10, Math.min(300, Math.round(numValue('#dRefinePool', 60)))),
      subbeat: $('#dSubbeat').checked,
      subbeatCandidates: 2,
    },
    e: {
      anchorStepSec: Math.max(0.1, Math.min(2, numValue('#eAnchorStep', 0.25))),
      patchSec: 4,
      coarseTopK: Math.max(50, Math.min(5000, Math.round(numValue('#eTopK', 2000)))),
      coarseSimThreshold: Math.max(0, Math.min(1, numValue('#eSimThreshold', 0.5))),
      verifyMaxPairs: Math.max(10, Math.min(2000, Math.round(numValue('#eVerifyMax', 250)))),
      verifyPeakThreshold: Math.max(0.3, Math.min(0.99, numValue('#ePeakThreshold', 0.8))),
      verifyBudgetMs: Math.max(200, numValue('#eVerifyBudget', 2500)),
      selectStepMs: Math.max(10, Math.min(200, numValue('#eSelectStep', 50))),
      compression: $('#eCompression').value,
      pcaDims: 256,
      rpDims: 256,
      slideVariants: Math.max(0, Math.min(4, Math.round(numValue('#eSlideVariants', 0)))),
      maxAnchors: Math.max(100, Math.min(4000, Math.round(numValue('#eMaxAnchors', 1600)))),
      maxPeaks: 5,
      multiPeakFactor: 0.95,
      minOverlapFrac: 0.5,
      seed: 20261121,
    },
  };
}

function makeMono() {
  const audio = state.audio;
  const n = audio.length;
  const ch = audio.numberOfChannels;
  const mono = new Float32Array(n);
  if (ch === 1) {
    mono.set(audio.getChannelData(0));
  } else {
    for (let c = 0; c < ch; c++) {
      const data = audio.getChannelData(c);
      for (let i = 0; i < n; i++) mono[i] += data[i];
    }
    for (let i = 0; i < n; i++) mono[i] /= ch;
  }
  return mono;
}

function showProgress(fraction, stage, elapsedMs) {
  $('#progressWrap').classList.remove('hidden');
  const f = Math.max(0, Math.min(1, fraction));
  $('#progressBar').style.width = (f * 100).toFixed(1) + '%';
  const label = STAGE_LABELS[stage] || stage || '分析中';
  $('#progressText').textContent = Math.round(f * 100) + '% · ' + label + ' · ' + (elapsedMs / 1000).toFixed(1) + 's';
}

function hideProgressSoon() {
  clearTimeout(state.progressTimer);
  state.progressTimer = setTimeout(() => {
    $('#progressWrap').classList.add('hidden');
  }, 600);
}

function runAnalysis() {
  if (!state.audio || state.analyzing) return;
  stopPlayback();
  state.result = null;
  state.selected = null;
  state.markers = [];
  state.paramsSnapshot = collectParams();
  $('#emptyResults').classList.add('hidden');
  $('#algoCards').innerHTML = '';
  $('#resultBody').innerHTML = '';
  $('#recallBadge').classList.add('hidden');
  $('#recallPanel').classList.add('hidden');
  $('#debugPanel').classList.add('hidden');
  $('#exportBtn').disabled = true;
  setStatus('分析中…', 'busy');
  state.analyzing = true;
  $('#analyzeBtn').disabled = true;
  $('#cancelBtn').classList.remove('hidden');
  showProgress(0, 'preprocess', 0);

  const worker = new Worker('./worker.js', { type: 'module' });
  state.worker = worker;
  const jobId = ++state.jobId;
  worker.onmessage = (ev) => {
    const msg = ev.data;
    if (!msg || msg.jobId !== jobId) return;
    if (msg.type === 'progress') {
      state.lastElapsed = msg.elapsedMs;
      showProgress(msg.fraction, msg.stage, msg.elapsedMs);
      return;
    }
    if (msg.type === 'result') {
      finishAnalysis(worker, msg.result, msg.elapsedMs);
      return;
    }
    if (msg.type === 'error') {
      finishAnalysis(worker, { ok: false, error: msg.error }, msg.elapsedMs);
    }
  };
  worker.onerror = (ev) => {
    console.error(ev);
    finishAnalysis(worker, { ok: false, error: ev.message || 'Worker 异常' }, state.lastElapsed);
  };
  const mono = makeMono();
  worker.postMessage({ type: 'analyze', jobId, pcm: mono, sampleRate: state.sr, params: state.paramsSnapshot }, [mono.buffer]);
}

function finishAnalysis(worker, result, elapsedMs) {
  try { worker.terminate(); } catch (e) { /* ignore */ }
  if (state.worker === worker) state.worker = null;
  state.analyzing = false;
  $('#analyzeBtn').disabled = false;
  $('#cancelBtn').classList.add('hidden');
  $('#progressBar').style.width = '100%';
  $('#progressText').textContent = '100% · 完成 · ' + ((elapsedMs || 0) / 1000).toFixed(2) + 's';
  hideProgressSoon();
  state.result = result;
  if (!result || !result.ok) {
    setStatus('分析失败', 'error');
    $('#emptyResults').classList.remove('hidden');
    $('#emptyResults').textContent = (result && result.error) || '分析失败';
    drawOverlay();
    return;
  }
  setStatus('完成 · ' + Math.round(result.meta.totalMs) + ' ms', 'ok');
  renderResults();
  $('#exportBtn').disabled = false;
  let any = false;
  for (const alg of ALGO_KEYS) {
    if (isAlgoEnabled(alg) && result.algorithms[alg] && result.algorithms[alg].candidates.length) any = true;
  }
  if (!any) {
    $('#emptyResults').classList.remove('hidden');
    $('#emptyResults').textContent = '分析完成，但启用的算法都没有候选。可放宽阈值、调整循环长度，或只启用 E 看看整秒重合结果。';
  }
  selectFirstCandidate();
}

function cancelAnalysis() {
  if (!state.analyzing) return;
  if (state.worker) {
    try { state.worker.terminate(); } catch (e) { /* ignore */ }
    state.worker = null;
  }
  state.analyzing = false;
  $('#analyzeBtn').disabled = false;
  $('#cancelBtn').classList.add('hidden');
  $('#progressWrap').classList.add('hidden');
  setStatus('已取消', '');
}

function selectFirstCandidate() {
  const r = state.result;
  if (!r || !r.ok) return;
  for (const alg of ALGO_KEYS) {
    if (!isAlgoEnabled(alg)) continue;
    const g = r.algorithms[alg];
    if (g && g.candidates && g.candidates.length) {
      selectCandidate(alg, 0, { play: false });
      return;
    }
  }
}

// --------------------------------------------------------------- rendering
function getCandidates(alg) {
  if (!state.result || !state.result.ok) return [];
  if (alg === 'bruteOnly') return (state.result.comparison && state.result.comparison.bruteOnly) || [];
  const g = state.result.algorithms[alg];
  return (g && g.candidates) || [];
}
function getCandidate(alg, index) {
  return getCandidates(alg)[index] || null;
}

function renderResults() {
  const r = state.result;
  if (!r || !r.ok) return;
  $('#emptyResults').classList.add('hidden');
  renderAlgoCards(r);
  renderRecall(r);
  renderLegend(r);
  renderTable(r);
  renderDebug(r);
  drawOverlay();
}

function renderAlgoCards(r) {
  const host = $('#algoCards');
  host.innerHTML = '';
  for (const alg of ALGO_KEYS) {
    if (!isAlgoEnabled(alg)) continue;
    const g = r.algorithms[alg];
    if (!g) continue;
    const meta = ALGO[alg];
    const t = g.telemetry;
    let metrics = '';
    let count = g.candidates ? g.candidates.length : 0;
    if (alg === 'baseline' && t) {
      metrics += metric('分析耗时', Math.round(t.totalMs) + ' ms');
      metrics += metric('帧 / beat', t.frames + ' / ' + t.beats);
      metrics += metric('每帧耗时', t.timePerFrameMs.toFixed(3) + ' ms');
      metrics += metric('每 beat 配对数', t.pairsPerBeat.toFixed(1));
      metrics += metric('配对检查 / 保留', t.pairsChecked + ' / ' + t.pairsKept);
      metrics += metric('BPM / tuning', t.bpm.toFixed(2) + ' / ' + t.tuning.toFixed(3));
      metrics += metric('STFT / 节拍 / 评分 (ms)', Math.round(t.stftMs) + ' / ' + Math.round(t.rhythmMs) + ' / ' + Math.round(t.scoreMs), true);
    } else if (alg === 'seam' && t) {
      metrics += metric('分析耗时', Math.round(t.totalMs) + ' ms');
      metrics += metric('接缝评估数', t.seamEvals);
      metrics += metric('候选池', t.candidatesInPool);
      metrics += metric('每候选耗时', t.msPerCandidate.toFixed(2) + ' ms');
      metrics += metric('帧 / beat', t.frames + ' / ' + t.beats);
      metrics += metric('排序耗时 / 池评估', Math.round(t.rankMs) + ' / ' + Math.round(t.poolEvalMs) + ' ms');
    } else if (alg === 'subbeat' && t) {
      metrics += metric('分析耗时', Math.round(t.totalMs) + ' ms');
      metrics += metric('seam 评估次数', t.evals);
      metrics += metric('预算耗尽', t.budgetHit ? '是' : '否');
      metrics += metric('帧 / beat', t.frames + ' / ' + t.beats);
    } else if (alg === 'lsh' && t) {
      metrics += metric('分析耗时', Math.round(t.totalMs) + ' ms');
      metrics += metric('帧 / beat', t.frames + ' / ' + t.beats);
      metrics += metric('LSH 候选对', t.lshCandidates);
      metrics += metric('精筛候选', t.refined);
      metrics += metric('特征 / LSH / 接缝 (ms)', Math.round(t.featureMs) + ' / ' + Math.round(t.lshMs) + ' / ' + Math.round(t.seamMs), true);
      metrics += metric('LSH 表数 / 桶宽 / 维度', t.stats.tables + ' / ' + t.stats.width + ' / ' + t.stats.dim);
      if (t.pcaVariance != null) metrics += metric('PCA 方差保留', pct(t.pcaVariance));
      if (r.recall) {
        metrics += metric('召回率（全量）', pct(r.recall.allRatio));
        metrics += metric('topN 覆盖（暴力 topN）', pct(r.recall.topNMembershipRatio));
        metrics += metric('top1 是否变化', r.recall.top1Same ? '否（相同）' : '是（已变化）');
      }
      metrics += metric('每 beat 平均候选', t.beats ? (t.lshCandidates / t.beats).toFixed(1) : '—');
    } else if (alg === 'whole' && t) {
      metrics += metric('分析耗时', Math.round(t.totalMs) + ' ms');
      metrics += metric('锚点数', t.anchors);
      metrics += metric('验证 / 请求', t.stats.verifyDone + ' / ' + t.stats.verifyRequested);
      metrics += metric('命中峰值 / 候选', t.stats.matches + ' / ' + t.stats.candidates);
      metrics += metric('特征 / 压缩 / 重采样 (ms)', Math.round(t.featureMs) + ' / ' + Math.round(t.compressMs) + ' / ' + Math.round(t.resampleMs || 0));
      metrics += metric('粗筛 / 验证 / 选点 (ms)', Math.round(t.coarseMs) + ' / ' + Math.round(t.verifyMs) + ' / ' + Math.round(t.selectMs), true);
      metrics += metric('锚点步长 / 窗长', (t.stats.stepSec * 1000).toFixed(0) + ' ms / ' + t.stats.patchSec + ' s');
      metrics += metric('压缩维度', t.stats.compression === 'none' ? t.stats.patchDim + ' (无压缩)' : t.stats.compressionDim + ' / ' + t.stats.patchDim);
      const q = t.quality || {};
      if (q.varianceRetention != null) metrics += metric('PCA 方差保留', pct(q.varianceRetention));
      if (q.distanceDistortion) metrics += metric('距离失真 (均值/最大)', pct(q.distanceDistortion.mean) + ' / ' + pct(q.distanceDistortion.max));
      metrics += metric('降维前后 top-20 重合', q.top20Overlap != null ? pct(q.top20Overlap, 0) : '—');
      metrics += metric('重叠规则丢弃', t.stats.overlapDropped + ' 对');
      metrics += metric('预算耗尽', t.stats.verifyBudgetHit ? '是' : '否');
    }
    if (g.error) metrics += metric('错误', g.error, true);
    const card = document.createElement('div');
    card.className = 'algo-card';
    card.style.setProperty('--c', meta.color);
    card.innerHTML =
      '<div class="algo-card-head"><span class="dot"></span><b>' + meta.label + '</b><span class="count">' + count + ' 条</span></div>' +
      '<div class="metric-grid">' + metrics + '</div>';
    host.appendChild(card);
  }
}

function renderRecall(r) {
  const badge = $('#recallBadge');
  const panel = $('#recallPanel');
  const rec = r.recall;
  if (!rec || !isAlgoEnabled('lsh')) {
    badge.classList.add('hidden');
    panel.classList.add('hidden');
    return;
  }
  const warn = rec.allRatio < 0.99 || rec.topNMembershipRatio < 0.99;
  badge.classList.remove('hidden');
  badge.className = 'recall-badge' + (warn ? ' warn' : '');
  badge.title = 'LSH 漏检是 silent failure：不报错，只是候选里没有。召回率必须实测。';
  badge.innerHTML =
    '<span>召回率</span><b>' + pct(rec.allRatio) + '</b>' +
    '<span>全量 ' + rec.allIntersection + '/' + rec.bruteAllCount + '</span>' +
    '<span>· topN 覆盖 ' + pct(rec.topNMembershipRatio) + '</span>' +
    '<span>· 耗时 ' + Math.round((r.algorithms.lsh.telemetry || {}).totalMs || 0) + ' ms</span>' +
    '<span>· top1 ' + (rec.top1Same ? '相同' : '已变化') + '</span>' +
    (warn ? '<span>⚠ &lt;99%</span>' : '');

  panel.classList.remove('hidden');
  const sets = rec.sets || { bruteTop: [], lshTop: [] };
  panel.innerHTML =
    '<h3>D 与暴力全配对（算法 A）的召回对照</h3>' +
    '<div class="recall-grid">' +
    metric('全量通过阈值的候选（暴力）', rec.bruteAllCount) +
    metric('LSH 生成候选', rec.lshAllCount) +
    metric('全量交集 / 召回', rec.allIntersection + ' / ' + pct(rec.allRatio)) +
    metric('暴力 topN 被 LSH 覆盖', rec.topNMembership + ' / ' + rec.bruteTopCount + '（' + pct(rec.topNMembershipRatio) + '）') +
    metric('最终 topN 交集', rec.topIntersection + ' / ' + rec.topN + '（' + pct(rec.topRatio) + '）') +
    metric('仅暴力（未进 D topN）', (r.comparison && r.comparison.bruteOnlyCount) || 0) +
    metric('top1 是否变化', rec.top1Same ? '否' : '是') +
    '</div>' +
    '<div class="set-list">暴力 topN key：' + (sets.bruteTop || []).join(', ') + '<br>LSH topN key：' + (sets.lshTop || []).join(', ') + '</div>';
}

function renderLegend(r) {
  const host = $('#legend');
  host.innerHTML = '';
  for (const alg of ALGO_KEYS) {
    if (!isAlgoEnabled(alg)) continue;
    const g = r.algorithms[alg];
    if (!g) continue;
    const meta = ALGO[alg];
    const item = document.createElement('span');
    item.className = 'item';
    item.style.setProperty('--c', meta.color);
    item.innerHTML = '<span class="swatch"></span>' + meta.short + ' · ' + (g.candidates ? g.candidates.length : 0);
    host.appendChild(item);
  }
}

function detailHtml(alg, cand) {
  const parts = [];
  const push = (label, value) => parts.push(metric(label, value));
  push('A / B（采样点）', cand.aSample + ' / ' + cand.bSample);
  push('A / B（ms）', fmtMs(cand.aMs, 1) + ' / ' + fmtMs(cand.bMs, 1));
  push('长度', fmtSec(cand.lengthMs / 1000));
  if (cand.sourceKey) push('sourceKey', cand.sourceKey);
  if (cand.score != null) push('score', cand.score.toFixed(6));
  if (cand.chromaDist != null) push('chromaDist', cand.chromaDist.toFixed(5));
  if (cand.loudnessDiff != null) push('loudnessDiff (dB)', cand.loudnessDiff.toFixed(4));
  if (cand.seam) {
    push('seamQuality', cand.seam.quality.toFixed(4));
    push('xcorr lag / peak', fmtSigned(cand.seam.xcorrLagMs, 2) + ' ms / ' + cand.seam.xcorrPeak.toFixed(3));
    push('RMS 差 (dB)', cand.seam.rmsDiffDb.toFixed(3));
    push('频谱通量突增', cand.seam.fluxJump.toFixed(3) + '（比值 ' + cand.seam.fluxRatio.toFixed(2) + '）');
    push('接缝样点跳变', cand.seam.junctionStep.toFixed(4));
    if (cand.seam.components) {
      push('分量 corr/loud/flux/step', cand.seam.components.corr.toFixed(2) + ' / ' + cand.seam.components.loud.toFixed(2) + ' / ' + cand.seam.components.flux.toFixed(2) + ' / ' + cand.seam.components.step.toFixed(2));
    }
  }
  if (cand.refine) {
    push('精修 ΔA / ΔB (ms)', fmtSigned(cand.refine.dAMs, 1) + ' / ' + fmtSigned(cand.refine.dBMs, 1));
    push('精修 seam 评估数', cand.refine.evals + (cand.refine.budgetHit ? '（预算耗尽）' : ''));
  }
  if (cand.combined != null) push('combined（0.6 seam + 0.4 score）', cand.combined.toFixed(4));
  if (alg === 'lsh') {
    push('beat 特征相似度', (cand.similarity != null ? cand.similarity.toFixed(4) : '—'));
    push('LSH 命中表数', cand.tablesHit);
    push('暴力集合命中', cand.hit === 'hit' ? '命中' : '仅 LSH');
    push('是否进暴力 topN', cand.inBruteTop ? '是' : '否');
  }
  if (alg === 'whole') {
    push('anchorA / anchorB (s)', fmtSec(cand.anchorA) + ' / ' + fmtSec(cand.anchorB));
    push('粗筛余弦相似度', cand.similarity.toFixed(4));
    push('NCC 峰值 / lag', cand.nccPeak.toFixed(4) + ' / ' + fmtSigned(cand.bestLagMs, 1) + ' ms');
    push('overlapRatio', pct(cand.overlapRatio));
    push('多峰数', cand.peakCount);
    if (cand.select) {
      push('选点总分', cand.select.score.toFixed(4));
      push('零交叉 / 能量 / 通量', cand.select.zc.toFixed(3) + ' / ' + cand.select.energy.toFixed(3) + ' / ' + cand.select.flux.toFixed(3));
      push('content NCC / 噪声', cand.select.content != null ? cand.select.content.toFixed(3) : '—');
      push('局部响度差 (dB)', cand.select.loudDiffDb.toFixed(3));
    }
  }
  return '<div class="detail-grid">' + parts.join('') + '<div class="detail-note">点击行或波形标记可定位并试听；空格键播放/暂停。</div></div>';
}

function renderTable(r) {
  const tbody = $('#resultBody');
  tbody.innerHTML = '';
  const frag = document.createDocumentFragment();
  for (const alg of ALGO_KEYS) {
    if (!isAlgoEnabled(alg)) continue;
    const g = r.algorithms[alg];
    if (!g || !g.candidates || !g.candidates.length) continue;
    const meta = ALGO[alg];
    g.candidates.forEach((cand, i) => {
      frag.appendChild(buildRow(alg, i, cand, meta));
      frag.appendChild(buildDetailRow(alg, i, cand));
    });
  }
  if (isAlgoEnabled('lsh') && r.comparison && r.comparison.bruteOnly) {
    r.comparison.bruteOnly.forEach((cand, i) => {
      frag.appendChild(buildRow('bruteOnly', i, cand, ALGO.bruteOnly));
      frag.appendChild(buildDetailRow('bruteOnly', i, cand));
    });
  }
  tbody.appendChild(frag);
  if (state.selected) {
    const row = tbody.querySelector('tr.cand[data-alg="' + state.selected.alg + '"][data-index="' + state.selected.index + '"]');
    if (row) row.classList.add('selected');
    const detail = tbody.querySelector('tr.detail-row[data-detail="' + state.selected.alg + '-' + state.selected.index + '"]');
    if (detail) detail.classList.remove('hidden');
  }
}

function buildRow(alg, index, cand, meta) {
  const tr = document.createElement('tr');
  tr.className = 'cand' + (alg === 'bruteOnly' ? ' brute-only' : '');
  tr.dataset.alg = alg;
  tr.dataset.index = String(index);
  const score = cand.score != null ? cand.score.toFixed(4) : '—';
  const chroma = cand.chromaDist != null ? cand.chromaDist.toFixed(4) : '—';
  const loud = cand.loudnessDiff != null ? cand.loudnessDiff.toFixed(3) : '—';
  const seam = cand.seam ? cand.seam.quality.toFixed(3) : '—';
  const lag = cand.seam ? fmtSigned(cand.seam.xcorrLagMs, 1) : (cand.bestLagMs != null ? fmtSigned(cand.bestLagMs, 1) : '—');
  let sim = '—';
  if (cand.similarity != null) sim = cand.similarity.toFixed(3);
  else if (cand.nccPeak != null) sim = cand.nccPeak.toFixed(3);
  let hit = '—';
  if (alg === 'lsh') {
    hit = cand.hit === 'hit' ? '<span class="hit-ok">命中</span>' : '<span class="hit-only-lsh">仅 LSH</span>';
  } else if (alg === 'bruteOnly') {
    hit = '<span class="hit-brute-only">仅暴力</span>';
  } else if (alg === 'whole' && cand.select) {
    hit = 'C ' + cand.select.content.toFixed(2) + ' · Z ' + cand.select.zc.toFixed(2) + ' · E ' + cand.select.energy.toFixed(2) + ' · F ' + cand.select.flux.toFixed(2) + (cand.peakCount > 1 ? ' · ' + cand.peakCount + ' 峰' : '');
  }
  let align = '—';
  if (alg === 'whole') {
    align = fmtSec(cand.anchorA, 2) + '→' + fmtSec(cand.anchorB, 2) + ' · ' + fmtSigned(cand.bestLagMs, 0) + ' ms · ' + pct(cand.overlapRatio, 1);
  } else if (alg === 'lsh') {
    align = cand.tablesHit + ' 表命中';
  } else if (cand.refine) {
    align = 'ΔA ' + fmtSigned(cand.refine.dAMs, 1) + ' / ΔB ' + fmtSigned(cand.refine.dBMs, 1) + ' ms';
  }
  tr.innerHTML =
    '<td><span class="row-algo" style="--c:' + meta.color + '"><span class="dot"></span>' + meta.short + ' <small>#' + (index + 1) + '</small></span></td>' +
    '<td>' + fmtMs(cand.aMs, 0) + '</td>' +
    '<td>' + fmtMs(cand.bMs, 0) + '</td>' +
    '<td>' + fmtSec(cand.lengthMs / 1000) + '</td>' +
    '<td>' + score + '</td>' +
    '<td>' + chroma + '</td>' +
    '<td>' + loud + '</td>' +
    '<td>' + seam + '</td>' +
    '<td>' + lag + '</td>' +
    '<td>' + sim + '</td>' +
    '<td>' + hit + '</td>' +
    '<td>' + align + '</td>';
  return tr;
}

function buildDetailRow(alg, index, cand) {
  const tr = document.createElement('tr');
  tr.className = 'detail-row hidden';
  tr.dataset.detail = alg + '-' + index;
  const td = document.createElement('td');
  td.colSpan = 12;
  td.innerHTML = detailHtml(alg, cand);
  tr.appendChild(td);
  return tr;
}

function selectCandidate(alg, index, opts) {
  const cand = getCandidate(alg, index);
  if (!cand) return;
  stopPlayback(true);
  state.selected = { alg, index, cand };
  document.querySelectorAll('#resultBody tr.cand.selected').forEach((tr) => tr.classList.remove('selected'));
  document.querySelectorAll('#resultBody tr.detail-row').forEach((tr) => tr.classList.add('hidden'));
  const row = document.querySelector('#resultBody tr.cand[data-alg="' + alg + '"][data-index="' + index + '"]');
  if (row) {
    row.classList.add('selected');
    const detail = document.querySelector('#resultBody tr.detail-row[data-detail="' + alg + '-' + index + '"]');
    if (detail) detail.classList.remove('hidden');
  }
  updatePlayerInfo();
  $('#playBtn').disabled = false;
  $('#jumpSeamBtn').disabled = false;
  drawOverlay();
  if (opts && opts.play) startPlayback(state.mode);
}

function updatePlayerInfo() {
  const sel = state.selected;
  if (!sel) {
    $('#playerTitle').textContent = '未选择候选';
    $('#position').textContent = '0.0 s';
    return;
  }
  const c = sel.cand;
  const meta = ALGO[sel.alg];
  $('#playerTitle').textContent = meta.label + ' #' + (sel.index + 1) + ' · A ' + fmtSec(c.aMs / 1000) + ' → B ' + fmtSec(c.bMs / 1000) + ' · 长度 ' + fmtSec(c.lengthMs / 1000);
  const durSec = state.duration || 1;
  $('#position').textContent = '0.0 s / ' + durSec.toFixed(2) + ' s';
}

function renderDebug(r) {
  const panel = $('#debugPanel');
  const content = $('#debugContent');
  const e = r.algorithms.whole && r.algorithms.whole.telemetry;
  const debug = e && e.debug;
  if (!debug || ((!debug.droppedOverlap || !debug.droppedOverlap.length) && (!debug.lowPeakExamples || !debug.lowPeakExamples.length))) {
    panel.classList.add('hidden');
    return;
  }
  panel.classList.remove('hidden');
  let html = '';
  if (debug.droppedOverlap && debug.droppedOverlap.length) {
    html += '<h4>被“距离 &lt; 2×窗长”规则丢弃的对（前 10）</h4>';
    html += '<div class="debug-note">这些对会共享音频，NCC 会被重叠部分主导，比真接缝更容易得高分 —— 必须排除。</div>';
    html += '<table><thead><tr><th>anchorA (s)</th><th>anchorB (s)</th><th>距离 (s)</th><th>原因</th></tr></thead><tbody>';
    for (const d of debug.droppedOverlap) {
      html += '<tr><td>' + d.anchorA.toFixed(2) + '</td><td>' + d.anchorB.toFixed(2) + '</td><td>' + d.distanceSec.toFixed(2) + '</td><td>' + d.reason + '</td></tr>';
    }
    html += '</tbody></table>';
  }
  if (debug.lowPeakExamples && debug.lowPeakExamples.length) {
    html += '<h4>NCC 峰值低于阈值的对（前 10）</h4>';
    html += '<table><thead><tr><th>anchorA (s)</th><th>anchorB (s)</th><th>峰值</th><th>lag (ms)</th></tr></thead><tbody>';
    for (const d of debug.lowPeakExamples) {
      html += '<tr><td>' + d.anchorA.toFixed(2) + '</td><td>' + d.anchorB.toFixed(2) + '</td><td>' + d.peak.toFixed(3) + '</td><td>' + fmtSigned(d.lagMs, 1) + '</td></tr>';
    }
    html += '</tbody></table>';
  }
  content.innerHTML = html;
}

// --------------------------------------------------------------- playback
function stopPlayback(silent) {
  if (state.source) {
    try { state.source.stop(); } catch (e) { /* ignore */ }
    try { state.source.disconnect(); } catch (e) { /* ignore */ }
    state.source = null;
  }
  if (state.seamTimer) {
    clearInterval(state.seamTimer);
    state.seamTimer = null;
  }
  for (const node of state.seamNodes) {
    try { node.stop(); } catch (e) { /* ignore */ }
    try { node.disconnect(); } catch (e) { /* ignore */ }
  }
  state.seamNodes = [];
  state.playing = false;
  state.playStart = null;
  state.playheadSample = null;
  if (state.raf) {
    cancelAnimationFrame(state.raf);
    state.raf = 0;
  }
  updatePlayButton();
  if (!silent) {
    drawOverlay();
    if (state.selected) {
      const c = state.selected.cand;
      $('#position').textContent = fmtSec(c.aMs / 1000) + ' / ' + fmtSec(state.duration);
    }
  }
}

function updatePlayButton() {
  $('#playIcon').classList.toggle('hidden', state.playing);
  $('#pauseIcon').classList.toggle('hidden', !state.playing);
  $('#playBtn').title = state.playing ? '暂停 (空格)' : '播放 (空格)';
}

function startSeamScheduler(ctx, cand, atSeam) {
  const sr = state.sr;
  const A = cand.aSample;
  const B = cand.bSample;
  const total = state.audio.length;
  let segSamples = Math.min(Math.round(2 * sr), B - A, A, total - B);
  if (segSamples < Math.round(0.08 * sr)) {
    segSamples = Math.max(1, Math.min(B - A, A, total - B));
  }
  if (segSamples < 64) {
    setStatus('循环太短，无法进入接缝模式', 'error');
    return;
  }
  state.seamNodes = [];
  state.seamNextTime = ctx.currentTime + 0.05;
  state.seamIsLeft = true;
  let first = true;
  const schedule = () => {
    if (!state.playing) return;
    while (state.seamNextTime < ctx.currentTime + 0.5) {
      let len = segSamples;
      if (first && atSeam) len = Math.min(Math.round(0.35 * sr), segSamples);
      const isLeft = state.seamIsLeft;
      const offset = isLeft ? B - len : A;
      if (offset < 0 || offset + len > total) break;
      const src = ctx.createBufferSource();
      src.buffer = state.audio;
      src.connect(state.masterGain);
      src.start(state.seamNextTime, offset / sr, len / sr);
      src.onended = () => {
        try { src.disconnect(); } catch (e) { /* ignore */ }
      };
      state.seamNodes.push(src);
      state.seamNextTime += len / sr;
      state.seamIsLeft = !state.seamIsLeft;
      first = false;
      if (state.seamNodes.length > 64) {
        const drop = state.seamNodes.splice(0, state.seamNodes.length - 32);
        for (const n of drop) { try { n.disconnect(); } catch (e) { /* ignore */ } }
      }
    }
  };
  schedule();
  state.seamTimer = setInterval(schedule, 100);
}

async function startPlayback(mode, opts) {
  if (!state.selected) return;
  const ctx = await resumeCtx();
  stopPlayback(true);
  state.mode = mode;
  updateModeSeg();
  const c = state.selected.cand;
  const sr = state.sr;
  const A = c.aSample;
  const B = c.bSample;
  const total = state.audio.length;
  if (!(B > A) || B > total) {
    setStatus('候选端点无效', 'error');
    return;
  }
  state.playing = true;
  if (mode === 'loop') {
    const src = ctx.createBufferSource();
    src.buffer = state.audio;
    src.loop = true;
    src.loopStart = A / sr;
    src.loopEnd = B / sr;
    src.connect(state.masterGain);
    let startSample = A;
    if (opts && opts.atSeam) startSample = Math.max(A, B - Math.round(1.5 * sr));
    src.start(ctx.currentTime + 0.03, startSample / sr);
    state.source = src;
    state.playStart = {
      ctxTime: ctx.currentTime + 0.03,
      loopStart: A,
      loopEnd: B,
      offsetWithin: startSample - A,
    };
  } else {
    state.playStart = null;
    startSeamScheduler(ctx, c, !!(opts && opts.atSeam));
  }
  updatePlayButton();
  animate();
}

function animate() {
  if (state.raf) cancelAnimationFrame(state.raf);
  const tick = () => {
    if (!state.playing) return;
    if (state.mode === 'loop' && state.playStart) {
      const ctx = state.audioCtx;
      const ps = state.playStart;
      const len = (ps.loopEnd - ps.loopStart) / state.sr;
      const elapsed = (ctx.currentTime - ps.ctxTime) + ps.offsetWithin / state.sr;
      const pos = ps.loopStart / state.sr + (((elapsed % len) + len) % len);
      state.playheadSample = Math.round(pos * state.sr);
      $('#position').textContent = fmtSec(pos) + ' / ' + fmtSec(ps.loopEnd / state.sr);
    } else if (state.mode === 'seam' && state.selected) {
      const c = state.selected.cand;
      $('#position').textContent = '接缝模式 · 段长 ' + Math.min(2, (c.bSample - c.aSample) / state.sr).toFixed(2) + 's';
    }
    drawOverlay();
    state.raf = requestAnimationFrame(tick);
  };
  state.raf = requestAnimationFrame(tick);
}

function updateModeSeg() {
  document.querySelectorAll('#modeSeg button').forEach((b) => {
    b.classList.toggle('active', b.dataset.mode === state.mode);
  });
}

async function togglePlay() {
  if (!state.selected) return;
  if (state.playing) {
    stopPlayback();
    return;
  }
  await startPlayback(state.mode);
}

// ---------------------------------------------------------------- export
function exportJson() {
  const r = state.result;
  if (!r || !r.ok) return;
  const payload = {
    schema: 'loop-lab/1.0',
    generatedAt: new Date().toISOString(),
    toolVersion: r.meta && r.meta.toolVersion,
    file: state.file,
    audio: { durationSec: state.duration, sampleRate: state.sr, channels: state.channels },
    params: state.paramsSnapshot,
    meta: r.meta,
    algorithms: {},
    recall: r.recall,
    comparison: r.comparison,
    debug: r.debug,
  };
  for (const alg of ALGO_KEYS) {
    const g = r.algorithms[alg];
    payload.algorithms[alg] = {
      telemetry: g.telemetry,
      candidates: g.candidates,
      skipped: !!g.skipped,
      error: g.error || null,
    };
    if (alg === 'whole' && g.telemetry) {
      payload.algorithms[alg].eDebug = g.telemetry.debug;
      payload.algorithms[alg].matches = g.telemetry.matches;
      payload.algorithms[alg].compression = g.telemetry.quality;
      payload.algorithms[alg].stages = {
        featureMs: g.telemetry.featureMs,
        compressMs: g.telemetry.compressMs,
        coarseMs: g.telemetry.coarseMs,
        verifyMs: g.telemetry.verifyMs,
        selectMs: g.telemetry.selectMs,
      };
    }
  }
  const blob = new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  const base = state.file && state.file.name ? state.file.name.replace(/\.[^.]+$/, '') : 'loop-lab';
  a.href = url;
  a.download = base + '-loop-analysis.json';
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 3000);
  setStatus('已导出 JSON', 'ok');
}

// --------------------------------------------------------------- tooltips
function tooltipHtml(alg, index, cand) {
  const meta = ALGO[alg];
  let html = '<div class="tt-head" style="--c:' + meta.color + '"><span class="tt-dot"></span>' + meta.label + ' #' + (index + 1) + '</div>';
  html += rowHtml('A / B', fmtMs(cand.aMs, 1) + ' / ' + fmtMs(cand.bMs, 1) + ' ms');
  html += rowHtml('长度', fmtSec(cand.lengthMs / 1000));
  if (cand.score != null) html += rowHtml('score', cand.score.toFixed(4));
  if (cand.chromaDist != null) html += rowHtml('chromaDist', cand.chromaDist.toFixed(4));
  if (cand.loudnessDiff != null) html += rowHtml('loudnessDiff', cand.loudnessDiff.toFixed(3) + ' dB');
  if (cand.seam) {
    html += rowHtml('seamQuality', cand.seam.quality.toFixed(3));
    html += rowHtml('xcorr lag / peak', fmtSigned(cand.seam.xcorrLagMs, 1) + ' ms / ' + cand.seam.xcorrPeak.toFixed(2));
  }
  if (alg === 'lsh') {
    html += rowHtml('LSH 相似度', cand.similarity != null ? cand.similarity.toFixed(3) : '—');
    html += rowHtml('暴力集合', cand.hit === 'hit' ? '命中' : '仅 LSH');
  }
  if (alg === 'whole') {
    html += rowHtml('anchor', fmtSec(cand.anchorA, 2) + ' → ' + fmtSec(cand.anchorB, 2));
    html += rowHtml('NCC 峰值 / lag', cand.nccPeak.toFixed(3) + ' / ' + fmtSigned(cand.bestLagMs, 1) + ' ms');
    html += rowHtml('overlap', pct(cand.overlapRatio));
    if (cand.select) html += rowHtml('选点 content', cand.select.content != null ? cand.select.content.toFixed(3) : '—');
  }
  return html;
}

function showTooltip(marker, x) {
  const tt = $('#tooltip');
  tt.innerHTML = tooltipHtml(marker.alg, marker.index, marker.cand);
  tt.classList.remove('hidden');
  const maxLeft = Math.max(8, state.canvasW - 330);
  tt.style.left = Math.min(maxLeft, x + 16) + 'px';
  tt.style.top = '10px';
}

function hideTooltip() {
  $('#tooltip').classList.add('hidden');
}

function nearestMarker(x) {
  let best = null;
  let bestD = 7;
  for (const m of state.markers) {
    const d = Math.abs(m.x - x);
    if (d < bestD) {
      bestD = d;
      best = m;
    }
  }
  return best;
}

// ----------------------------------------------------------------- events
function initEvents() {
  const dropzone = $('#dropzone');
  const fileInput = $('#fileInput');
  const openPicker = () => fileInput.click();
  $('#chooseBtn').addEventListener('click', (e) => { e.stopPropagation(); openPicker(); });
  $('#demoBtn').addEventListener('click', (e) => { e.stopPropagation(); loadDemo(); });
  dropzone.addEventListener('click', (e) => {
    if (e.target.closest('button')) return;
    openPicker();
  });
  dropzone.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); openPicker(); }
  });
  fileInput.addEventListener('change', () => {
    if (fileInput.files && fileInput.files[0]) loadFile(fileInput.files[0]);
    fileInput.value = '';
  });
  ['dragenter', 'dragover'].forEach((type) => {
    dropzone.addEventListener(type, (e) => { e.preventDefault(); e.stopPropagation(); dropzone.classList.add('dragover'); });
  });
  ['dragleave', 'drop'].forEach((type) => {
    dropzone.addEventListener(type, (e) => { e.preventDefault(); e.stopPropagation(); dropzone.classList.remove('dragover'); });
  });
  dropzone.addEventListener('drop', (e) => {
    const f = e.dataTransfer && e.dataTransfer.files && e.dataTransfer.files[0];
    if (f) loadFile(f);
  });
  window.addEventListener('dragover', (e) => e.preventDefault());
  window.addEventListener('drop', (e) => e.preventDefault());
  $('#clearBtn').addEventListener('click', () => {
    stopPlayback();
    state.audio = null;
    state.result = null;
    state.selected = null;
    state.envelope = null;
    $('#fileInfo').classList.add('hidden');
    $('#dropzone').classList.remove('hidden');
    $('#longWarning').classList.add('hidden');
    $('#exportBtn').disabled = true;
    $('#playBtn').disabled = true;
    $('#jumpSeamBtn').disabled = true;
    $('#algoCards').innerHTML = '';
    $('#resultBody').innerHTML = '';
    $('#emptyResults').classList.remove('hidden');
    $('#emptyResults').textContent = '拖入音频（或点击“载入合成测试音频”）后会自动分析；结果、耗时与召回率显示在这里。';
    $('#recallBadge').classList.add('hidden');
    $('#recallPanel').classList.add('hidden');
    $('#debugPanel').classList.add('hidden');
    $('#playerTitle').textContent = '未选择候选';
    $('#position').textContent = '0.0 s';
    setStatus('就绪', '');
    state.hoverX = null;
    drawWave();
    drawOverlay();
  });
  $('#changeBtn').addEventListener('click', openPicker);

  $('#analyzeBtn').addEventListener('click', runAnalysis);
  $('#cancelBtn').addEventListener('click', cancelAnalysis);
  $('#exportBtn').addEventListener('click', exportJson);

  $('#beatStrideEnable').addEventListener('change', () => {
    $('#beatStride').disabled = !$('#beatStrideEnable').checked;
  });
  $('#suggestStride').addEventListener('click', () => {
    $('#beatStrideEnable').checked = true;
    $('#beatStride').disabled = false;
    $('#beatStride').value = '2';
    setStatus('已启用每 2 拍降采样，重新分析后生效', 'busy');
  });
  for (const key of ALGO_KEYS) {
    $(CHECKBOX[key]).addEventListener('change', () => {
      if (state.result && state.result.ok) {
        renderAlgoCards(state.result);
        renderLegend(state.result);
        renderTable(state.result);
        renderRecall(state.result);
        if (state.selected && !isAlgoEnabled(state.selected.alg)) {
          state.selected = null;
          updatePlayerInfo();
          $('#playBtn').disabled = true;
          $('#jumpSeamBtn').disabled = true;
        }
        drawOverlay();
      }
    });
  }
  $('#showAllMarkers').addEventListener('change', () => {
    state.showAll = $('#showAllMarkers').checked;
    drawOverlay();
  });

  $('#resultBody').addEventListener('click', (e) => {
    const tr = e.target.closest('tr.cand');
    if (!tr) return;
    selectCandidate(tr.dataset.alg, parseInt(tr.dataset.index, 10), { play: true });
  });

  $('#playBtn').addEventListener('click', togglePlay);
  $('#jumpSeamBtn').addEventListener('click', () => {
    if (!state.selected) return;
    startPlayback(state.mode, { atSeam: true });
  });
  document.querySelectorAll('#modeSeg button').forEach((b) => {
    b.addEventListener('click', async () => {
      const mode = b.dataset.mode;
      if (state.mode === mode) return;
      state.mode = mode;
      updateModeSeg();
      if (state.playing) await startPlayback(mode);
      else drawOverlay();
    });
  });
  $('#volume').addEventListener('input', () => {
    if (state.masterGain) state.masterGain.gain.value = parseFloat($('#volume').value);
  });

  const wrap = $('#waveWrap');
  wrap.addEventListener('pointermove', (e) => {
    if (!state.audio) return;
    const rect = wrap.getBoundingClientRect();
    const x = e.clientX - rect.left;
    state.hoverX = x;
    const marker = nearestMarker(x);
    drawOverlay();
    if (marker) showTooltip(marker, x);
    else hideTooltip();
  });
  wrap.addEventListener('pointerleave', () => {
    state.hoverX = null;
    hideTooltip();
    drawOverlay();
  });
  wrap.addEventListener('click', (e) => {
    if (!state.audio) return;
    const rect = wrap.getBoundingClientRect();
    const x = e.clientX - rect.left;
    const marker = nearestMarker(x);
    if (marker) selectCandidate(marker.alg, marker.index, { play: true });
  });

  document.addEventListener('keydown', (e) => {
    if (e.key !== ' ' && e.code !== 'Space') return;
    const tag = (e.target && e.target.tagName) || '';
    if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || tag === 'BUTTON') return;
    e.preventDefault();
    togglePlay();
  });

  let resizeTimer = 0;
  const onResize = () => {
    clearTimeout(resizeTimer);
    resizeTimer = setTimeout(() => {
      fitCanvases(true);
      drawWave();
      drawOverlay();
    }, 120);
  };
  window.addEventListener('resize', onResize);
  if (window.ResizeObserver) {
    const ro = new ResizeObserver(onResize);
    ro.observe(wrap);
  }

  // default inactive state
  $('#beatStride').disabled = true;
  state.showAll = false;
}

function boot() {
  initEvents();
  fitCanvases(true);
  drawWave();
  drawOverlay();
  updatePlayButton();
  updateModeSeg();
  setStatus('就绪', '');
  if (new URLSearchParams(location.search).get('demo') === '1') {
    setTimeout(loadDemo, 50);
  }
}

window.addEventListener('error', (event) => {
  console.error(event.error || event.message);
  setStatus('脚本错误：' + (event.message || '未知'), 'error');
});

window.__loopLabDebug = state;

boot();
