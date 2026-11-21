// Analysis worker: keeps decode/analysis off the UI thread.
import { analyze } from './analysis/pipeline.js';

let activeJobId = null;
let cancelled = false;
let lastProgressAt = 0;
let lastStage = '';

function post(msg, transfer) {
  self.postMessage(msg, transfer || []);
}

self.onmessage = (event) => {
  const msg = event.data;
  if (!msg || typeof msg !== 'object') return;
  if (msg.type === 'cancel') {
    cancelled = true;
    return;
  }
  if (msg.type !== 'analyze') return;

  activeJobId = msg.jobId;
  cancelled = false;
  lastProgressAt = 0;
  lastStage = '';
  const started = performance.now();
  try {
    const result = analyze(msg.pcm, msg.sampleRate, msg.params, {
      onProgress(fraction, stage) {
        if (cancelled || activeJobId !== msg.jobId) return;
        const t = performance.now();
        if (stage !== lastStage || t - lastProgressAt > 80 || fraction >= 0.999) {
          lastProgressAt = t;
          lastStage = stage;
          post({ type: 'progress', jobId: msg.jobId, fraction, stage, elapsedMs: t - started });
        }
      },
      isCancelled() {
        return cancelled || activeJobId !== msg.jobId;
      },
    });
    if (cancelled || activeJobId !== msg.jobId) return;
    post({ type: 'result', jobId: msg.jobId, result, elapsedMs: performance.now() - started });
  } catch (err) {
    if (cancelled || activeJobId !== msg.jobId) return;
    post({
      type: 'error',
      jobId: msg.jobId,
      error: err && err.stack ? String(err.stack) : String(err),
      elapsedMs: performance.now() - started,
    });
  }
};
